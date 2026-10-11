package dev.kestrel.net;

import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Use-item-on for one player: a transcription of vanilla's
 * {@code ServerGamePacketListenerImpl.handleUseItemOn}
 * ({@code ServerGamePacketListenerImpl.java:1340-1408}) and the item half of
 * {@code ServerPlayerGameMode.useItemOn} ({@code ServerPlayerGameMode.java:344-394}),
 * run on the session's stand-in player and its level.
 *
 * <p>Everything the click does to the world after those two methods is vanilla's own code on
 * the stand-in: {@code ItemStack.useOn}, {@code BlockItem.useOn}/{@code place},
 * {@code BlockPlaceContext}, {@code Block.getStateForPlacement}, {@code canSurvive},
 * {@code Level.isUnobstructed}, {@code Block.setPlacedBy}, the {@code PLACED_BLOCK}
 * trigger and {@code ItemStack.consume}. The stand-in level answers the writes
 * ({@code VanillaEntityLevel.setBlock} goes to the live loop) and the entity reads.
 *
 * <p>Seams, named so they are not forgotten:
 * <ul>
 *   <li><b>Block interactions are G21.</b> {@code ServerPlayerGameMode.useItemOn} first offers the
 *       click to the clicked block's own {@code useItemOn} and {@code useWithoutItem} (a door, a
 *       chest, a lever, a bed, a cauldron), unless the player is sneaking with something in a
 *       hand. That block is not transcribed here, so a click on an interactive block places
 *       against it exactly as the stone stub did.
 *   <li>The place sound is G67 and game events have no listener until G12.
 *   <li>Players are always survival ({@code KestrelServerPlayer}), so the spectator branch, the
 *       infinite-materials count restore and adventure-mode {@code canPlaceOn} are not transcribed.
 *   <li>Block entities exist only for the types {@code BlockEntityStore.supports} creates, so the
 *       block-entity data and components of an item for any other type are dropped, and a sign's
 *       editor is not opened.
 * </ul>
 *
 * <p>Thread-safety: tick thread only.
 */
final class BlockPlacing {

    /** What the handler reads and sends beyond the stand-in player itself. */
    interface World {
        /** {@code connection.send(new ClientboundBlockUpdatePacket(level, pos))} to the placer. */
        void sendBlockUpdate(BlockPos pos);

        /** {@code MinecraftServer.isUnderSpawnProtection} for this player. */
        boolean isUnderSpawnProtection(BlockPos pos);

        /** {@code awaitingPositionFromClient != null}: a teleport the client has not accepted yet. */
        boolean awaitingTeleport();

        /** {@code ServerPlayer.resetLastActionTime}. */
        void resetLastActionTime();
    }

    private static final double CURSOR_LIMIT = 1.0000001;

    private BlockPlacing() {
    }

    /**
     * {@code handleUseItemOn} after the client-loaded test and the sequence ack, which the caller
     * owns ({@code :1342-1344}).
     */
    static void handleUseItemOn(ServerPlayer player, World world, InteractionHand hand, BlockHitResult blockHit) {
        ServerLevel level = player.level();
        ItemStack itemStack = player.getItemInHand(hand);
        if (!itemStack.isItemEnabled(level.enabledFeatures())) {
            return;
        }
        Vec3 location = blockHit.getLocation();
        BlockPos pos = blockHit.getBlockPos();
        if (!player.isWithinBlockInteractionRange(pos, 1.0)) {
            return; // out of reach: no correction, only the ack
        }
        Vec3 distance = location.subtract(Vec3.atCenterOf(pos));
        if (!(Math.abs(distance.x()) < CURSOR_LIMIT && Math.abs(distance.y()) < CURSOR_LIMIT
                && Math.abs(distance.z()) < CURSOR_LIMIT)) {
            return; // vanilla logs "too far away from hit block" and does nothing else
        }
        Direction direction = blockHit.getDirection();
        world.resetLastActionTime();
        int maxY = level.getMaxY();
        int minY = level.getMinY();
        if (pos.getY() > maxY) {
            player.sendBuildLimitMessage(true, maxY);
            return; // the two block updates sit inside the in-range branch
        }
        if (pos.getY() < minY) {
            player.sendBuildLimitMessage(false, minY);
            return;
        }
        if (world.isUnderSpawnProtection(pos)) {
            player.sendSpawnProtectionMessage(pos);
        } else if (!world.awaitingTeleport() && mayInteract(player, pos)) {
            InteractionResult interactionResult = useItemOn(player, level, itemStack, hand, blockHit);
            if (interactionResult.consumesAction()) {
                CriteriaTriggers.ANY_BLOCK_USE.trigger(player, blockHit.getBlockPos(), itemStack);
            }
            // :1371-1380 and :1382-1392, as written: the swing code is in both chains, so a
            // SERVER-sourced success swings twice.
            if (direction == Direction.UP && !interactionResult.consumesAction() && pos.getY() >= maxY
                    && wasBlockPlacementAttempt(player, itemStack)) {
                player.sendBuildLimitMessage(true, maxY);
            } else if (interactionResult instanceof InteractionResult.Success success
                    && success.swingSource() == InteractionResult.SwingSource.SERVER) {
                player.swing(hand, true);
            }
            if (!interactionResult.consumesAction() && wasBlockPlacementAttempt(player, itemStack)) {
                if (direction == Direction.UP && pos.getY() >= maxY) {
                    player.sendBuildLimitMessage(true, maxY);
                } else if (direction == Direction.DOWN && pos.getY() <= minY) {
                    player.sendBuildLimitMessage(false, minY);
                }
            } else if (interactionResult instanceof InteractionResult.Success success
                    && success.swingSource() == InteractionResult.SwingSource.SERVER) {
                player.swing(hand, true);
            }
        } else {
            // A pending teleport or a position outside the border: vanilla's own "too high" overlay.
            player.sendBuildLimitMessage(true, maxY);
        }
        world.sendBlockUpdate(pos);
        world.sendBlockUpdate(pos.relative(direction));
    }

    /**
     * {@code ServerLevel.mayInteract} ({@code ServerLevel.java:871-873}) without the spawn-protection
     * half, which the caller has just tested on its own to send the overlay.
     */
    private static boolean mayInteract(ServerPlayer player, BlockPos pos) {
        return player.level().getWorldBorder().isWithinBounds(pos);
    }

    /** {@code ServerGamePacketListenerImpl.wasBlockPlacementAttempt} ({@code :1329-1337}). */
    private static boolean wasBlockPlacementAttempt(ServerPlayer player, ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        Item item = itemStack.getItem();
        return (item instanceof BlockItem || item instanceof BucketItem bucket && bucket.getContent() != Fluids.EMPTY)
                && !player.getCooldowns().isOnCooldown(itemStack);
    }

    /**
     * {@code ServerPlayerGameMode.useItemOn} ({@code :344-394}) for a survival player, less the
     * clicked block's own interaction (G21, see the class comment).
     */
    private static InteractionResult useItemOn(ServerPlayer player, ServerLevel level, ItemStack itemStack,
            InteractionHand hand, BlockHitResult hitResult) {
        BlockPos pos = hitResult.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!state.getBlock().isEnabled(level.enabledFeatures())) {
            return InteractionResult.FAIL;
        }
        ItemStack usedItemStack = itemStack.copy();
        // G21 seam: here vanilla offers the click to state.useItemOn(...) and then, for the main
        // hand, state.useWithoutItem(...), unless player.isSecondaryUseActive() with something in
        // either hand. Their results are CONSUME/SUCCESS to stop here, or PASS to fall through.
        if (!itemStack.isEmpty() && !player.getCooldowns().isOnCooldown(itemStack)) {
            UseOnContext context = new UseOnContext(player, hand, hitResult);
            InteractionResult success = itemStack.useOn(context);
            if (success.consumesAction()) {
                CriteriaTriggers.ITEM_USED_ON_BLOCK.trigger(player, pos, usedItemStack);
            }
            return success;
        }
        return InteractionResult.PASS;
    }
}
