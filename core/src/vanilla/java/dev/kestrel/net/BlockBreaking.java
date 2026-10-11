package dev.kestrel.net;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * One player's block-breaking state machine: a transcription of vanilla's
 * {@code ServerPlayerGameMode} block-break half for a survival player
 * ({@code ServerPlayerGameMode.java:42-50} fields, {@code :109-144} tick and
 * progress, {@code :152-253} {@code handleBlockBreakAction},
 * {@code :255-262} {@code destroyAndAck}, {@code :264-302}
 * {@code destroyBlock}).
 *
 * <p>The destroy progress itself is vanilla's own
 * {@code BlockState.getDestroyProgress} evaluated on the session's vanilla
 * player, so tool speed, the correct-tool divisor, the airborne penalty and
 * the held item come from the real {@code Player.getDestroySpeed}.
 *
 * <p>The crack stage goes to the other players through
 * {@link World#destroyBlockProgress} at every point vanilla calls
 * {@code level.destroyBlockProgress}.
 *
 * <p>START runs vanilla's {@code EnchantmentHelper.onHitBlock} and
 * {@code BlockState.attack} on the stand-in player and its level (slice 4); what
 * each block's {@code attack} can reach is whatever the stand-in level answers
 * (see {@code docs/ROADMAP.md}, G18).
 * {@code playerWillDestroy} and {@code Block.destroy} are slice 3a; tool wear is vanilla's own
 * {@code ItemStack.mineBlock} (slice 3); drops (G19), exhaustion and stats are
 * completed here by vanilla's {@code Block.playerDestroy}. Players are
 * always survival in Kestrel ({@code KestrelServerPlayer.gameMode}), so the
 * creative ({@code instabuild}) and adventure/spectator
 * ({@code blockActionRestricted}) branches are inert and not transcribed.
 *
 * <p>Thread-safety: tick thread only.
 */
final class BlockBreaking {

    /** The world the state machine reads and writes. */
    interface World {
        BlockState getBlockState(BlockPos pos);

        /** {@code Level.removeBlock(pos, false)}: the fluid's legacy block, flags 3. */
        boolean removeBlock(BlockPos pos);

        /** {@code connection.send(new ClientboundBlockUpdatePacket(pos, getBlockState(pos)))} to the breaker. */
        void sendBlockUpdate(BlockPos pos);

        /** {@code MinecraftServer.isUnderSpawnProtection} for this player. */
        boolean isUnderSpawnProtection(BlockPos pos);

        /**
         * {@code level.destroyBlockProgress(player.getId(), pos, progress)}:
         * the crack stage to the other players nearby
         * ({@link BlockDestructionBroadcast}).
         */
        void destroyBlockProgress(BlockPos pos, int progress);
    }

    static final int START_DESTROY_BLOCK = 0;
    static final int ABORT_DESTROY_BLOCK = 1;
    static final int STOP_DESTROY_BLOCK = 2;

    private boolean isDestroyingBlock;
    private int destroyProgressStart;
    private BlockPos destroyPos = BlockPos.ZERO;
    private int gameTicks;
    private boolean hasDelayedDestroy;
    private BlockPos delayedDestroyPos = BlockPos.ZERO;
    private int delayedTickStart;
    private int lastSentState = -1;

    /** {@code ServerPlayerGameMode.tick} ({@code :109-132}), run where {@code ServerPlayer.tick} runs it. */
    void tick(ServerPlayer player, World world) {
        gameTicks++;
        if (hasDelayedDestroy) {
            BlockState blockState = world.getBlockState(delayedDestroyPos);
            if (blockState.isAir()) {
                hasDelayedDestroy = false;
            } else {
                float destroyProgress = incrementDestroyProgress(player, world, blockState, delayedDestroyPos, delayedTickStart);
                if (destroyProgress >= 1.0F) {
                    hasDelayedDestroy = false;
                    destroyBlock(player, world, delayedDestroyPos);
                }
            }
        } else if (isDestroyingBlock) {
            BlockState blockState = world.getBlockState(destroyPos);
            if (blockState.isAir()) {
                world.destroyBlockProgress(destroyPos, -1);
                lastSentState = -1;
                isDestroyingBlock = false;
            } else {
                incrementDestroyProgress(player, world, blockState, destroyPos, destroyProgressStart);
            }
        }
    }

    /** {@code :134-144}: a new crack stage goes out when it changes. */
    private float incrementDestroyProgress(ServerPlayer player, World world, BlockState blockState, BlockPos pos,
            int destroyStartTick) {
        int ticksSpentDestroying = gameTicks - destroyStartTick;
        float destroyProgress = blockState.getDestroyProgress(player, player.level(), pos) * (ticksSpentDestroying + 1);
        int state = (int) (destroyProgress * 10.0F);
        if (state != lastSentState) {
            world.destroyBlockProgress(pos, state);
            lastSentState = state;
        }
        return destroyProgress;
    }

    /**
     * {@code handleBlockBreakAction} ({@code :152-253}) for the three destroy
     * actions; the caller acks {@code sequence} afterwards in every case
     * ({@code ServerGamePacketListenerImpl.java:1317-1322}).
     */
    void handleBlockBreakAction(ServerPlayer player, World world, BlockPos pos, int action) {
        int maxY = player.level().getMaxY(); // handlePlayerAction passes this.player.level().getMaxY()
        if (!player.isWithinBlockInteractionRange(pos, 1.0)) {
            return; // "too far": no correction, only the ack
        }
        if (pos.getY() > maxY) {
            world.sendBlockUpdate(pos);
            return;
        }
        if (action == START_DESTROY_BLOCK) {
            if (world.isUnderSpawnProtection(pos)) {
                // ServerPlayerGameMode.java:162-166: the red "build.spawn_protection"
                // overlay, vanilla's own, through the stand-in's connection.
                player.sendSpawnProtectionMessage(pos);
                return;
            }
            // ServerLevel.mayInteract (:871-873): spawn protection or outside the border.
            if (world.isUnderSpawnProtection(pos) || !player.level().getWorldBorder().isWithinBounds(pos)) {
                world.sendBlockUpdate(pos);
                return;
            }
            destroyProgressStart = gameTicks;
            float progress = 1.0F;
            BlockState blockState = world.getBlockState(pos);
            if (!blockState.isAir()) {
                // ServerPlayerGameMode.java:190-199: the two hit effects, vanilla's own, in
                // vanilla's order, on the state read above (the progress below keeps reading
                // that local even when attack replaced the block, as vanilla does).
                EnchantmentHelper.onHitBlock(player.level(), player.getMainHandItem(), player, player,
                        EquipmentSlot.MAINHAND, Vec3.atCenterOf(pos), blockState,
                        item -> player.onEquippedItemBroken(item, EquipmentSlot.MAINHAND));
                blockState.attack(player.level(), pos, player);
                progress = blockState.getDestroyProgress(player, player.level(), pos);
            }
            if (!blockState.isAir() && progress >= 1.0F) {
                destroyAndAck(player, world, pos);
            } else {
                if (isDestroyingBlock) {
                    world.sendBlockUpdate(destroyPos);
                }
                isDestroyingBlock = true;
                destroyPos = pos.immutable();
                int state = (int) (progress * 10.0F);
                world.destroyBlockProgress(pos, state);
                lastSentState = state;
            }
        } else if (action == STOP_DESTROY_BLOCK) {
            if (pos.equals(destroyPos)) {
                int ticksSpentDestroying = gameTicks - destroyProgressStart;
                BlockState state = world.getBlockState(pos);
                if (!state.isAir()) {
                    float destroyProgress = state.getDestroyProgress(player, player.level(), pos) * (ticksSpentDestroying + 1);
                    if (destroyProgress >= 0.7F) {
                        isDestroyingBlock = false;
                        world.destroyBlockProgress(pos, -1);
                        destroyAndAck(player, world, pos);
                        return;
                    }
                    if (!hasDelayedDestroy) {
                        isDestroyingBlock = false;
                        hasDelayedDestroy = true;
                        delayedDestroyPos = pos;
                        delayedTickStart = destroyProgressStart;
                    }
                }
            }
        } else if (action == ABORT_DESTROY_BLOCK) {
            isDestroyingBlock = false;
            if (!Objects.equals(destroyPos, pos)) {
                // Vanilla also logs "Mismatch in destroy block pos" at WARN.
                world.destroyBlockProgress(destroyPos, -1);
            }
            world.destroyBlockProgress(pos, -1);
        }
    }

    /** {@code :255-262}. */
    private void destroyAndAck(ServerPlayer player, World world, BlockPos pos) {
        if (!destroyBlock(player, world, pos)) {
            world.sendBlockUpdate(pos);
        }
    }

    /**
     * {@code destroyBlock} ({@code :264-302}) through {@code Block.destroy}:
     * the held item's veto, the game-master block guard (a survival player is
     * never allowed: {@code canUseGameMasterBlocks} needs {@code instabuild}),
     * vanilla's own {@code playerWillDestroy} on the stand-in's level (the
     * break sound and particles, level event 2001, to everyone but the
     * breaker), {@code Level.removeBlock} and {@code Block.destroy}. Vanilla's
     * {@code sendBlockUpdated} on the game-master branch marks the block for
     * every tracking player; here the caller's correction covers the breaker
     * only. After removal, this also transcribes the G19 drop half through
     * {@code ItemStack.mineBlock} (tool wear) and {@code Block.playerDestroy}.
     */
    private boolean destroyBlock(ServerPlayer player, World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!player.getMainHandItem().canDestroyBlock(state, player.level(), pos, player)) {
            return false;
        }
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        Block block = state.getBlock();
        if (block instanceof GameMasterBlock) {
            return false;
        }
        BlockState adjustedState = block.playerWillDestroy(player.level(), pos, state, player);
        boolean changed = world.removeBlock(pos);
        if (changed) {
            block.destroy(player.level(), pos, adjustedState);
        }
        if (player.preventsBlockDrops()) {
            return true;
        }
        ItemStack itemStack = player.getMainHandItem();
        ItemStack destroyedWith = itemStack.copy();
        boolean canDestroy = player.hasCorrectToolForDrops(adjustedState);
        // ServerPlayerGameMode.java:293: tool wear on the real held stack (the
        // menu diff carries the new damage to the client), ITEM_USED, and the
        // break event when it wears out. Loot still sees the pre-wear copy.
        itemStack.mineBlock(player.level(), adjustedState, pos, player);
        if (changed && canDestroy) {
            block.playerDestroy(player.level(), player, pos, adjustedState, blockEntity, destroyedWith);
        }
        return true;
    }

    // ---- read-only views for checks ----

    boolean isDestroyingBlock() { return isDestroyingBlock; }
    boolean hasDelayedDestroy() { return hasDelayedDestroy; }
    BlockPos destroyPos() { return destroyPos; }
    int lastSentState() { return lastSentState; }
    int gameTicks() { return gameTicks; }
    int destroyProgressStart() { return destroyProgressStart; }
    int delayedTickStart() { return delayedTickStart; }

    @Override
    public String toString() {
        return "BlockBreaking[destroying=" + isDestroyingBlock + " at " + destroyPos
                + ", delayed=" + hasDelayedDestroy + " at " + Objects.toString(delayedDestroyPos)
                + ", gameTicks=" + gameTicks + "]";
    }
}
