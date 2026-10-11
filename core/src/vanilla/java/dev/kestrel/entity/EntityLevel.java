package dev.kestrel.entity;

import dev.kestrel.collision.ShapeBoxCache;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/**
 * What a ticking entity reads from the world: the block states it collides
 * with and stands on, and the world's floor for the below-world discard.
 * The live server answers from the {@code TickLoop}'s store; the gate
 * answers from a fixture world shared with the vanilla oracle.
 *
 * <p>There are no entity colliders (no boats or shulkers exist) and no
 * world border collider (the border is vanilla's default, far outside any
 * world Kestrel serves); both are named where the tick would consult them.
 */
public interface EntityLevel {

    /** Block states and fluid states, as vanilla's collision walk reads them. */
    BlockGetter blocks();

    /** The shape fast path {@code KestrelCollide} consults. */
    ShapeBoxCache shapeCache();

    /** {@code LevelHeightAccessor.getMinY}. */
    int getMinY();

    /** {@code LevelHeightAccessor.getMaxY}. */
    default int getMaxY() { return getMinY() + blocks().getHeight() - 1; }

    /*
     * Everything below changes the world or reads state vanilla keeps on the
     * level, so there is no default: an implementation that forgot one would
     * diverge without an error. A world that never reaches one says so by
     * throwing.
     */

    /** The level as a vanilla {@code LevelReader}, for {@code canSurvive}. */
    LevelReader levelReader();

    /** {@code Level.setBlock(pos, state, flags)} through the live write path. */
    boolean setBlock(BlockPos pos, BlockState state, int flags);

    /** {@code ServerLevel.levelEvent(null, type, pos, data)}. */
    void levelEvent(int type, BlockPos pos, int data);

    /**
     * {@code ServerLevel.levelEvent(source, type, pos, data)} with a player as
     * the source ({@code ServerLevel.java:1110-1122}): everyone but that player.
     * A level with no players to leave out treats it as a sourceless event.
     */
    default void levelEvent(java.util.@org.jspecify.annotations.Nullable UUID exceptPlayer, int type, BlockPos pos,
            int data) {
        levelEvent(type, pos, data);
    }

    /**
     * {@code ServerLevel.blockEvent(pos, block, b0, b1)} ({@code ServerLevel.java:1252-1254}): queue
     * the event for the level's own block-event phase. It only enqueues; what the phase then does with
     * it is the loop's. A level that never reaches one says so by throwing.
     */
    default void blockEvent(BlockPos pos, net.minecraft.world.level.block.Block block, int b0, int b1) {
        throw new UnsupportedOperationException("this level has no block event queue");
    }

    /**
     * {@code ServerLevel.scheduleTick(pos, block, delay, priority)}: what
     * {@code Block.setPlacedBy} reaches for a repeater, comparator or crafter. A level with no
     * tick queue says so by throwing.
     */
    default void scheduleTick(BlockPos pos, net.minecraft.world.level.block.Block block, int delay,
            net.minecraft.world.ticks.TickPriority priority) {
        throw new UnsupportedOperationException("this level has no scheduled tick queue");
    }

    /**
     * {@code ServerLevel.updateNeighborsAt} and {@code updateNeighborsAtExceptFromFacing}
     * ({@code ServerLevel.java:1167-1181}); a null {@code skipDirection} skips nothing. What a
     * tripwire hook's {@code setPlacedBy} reaches. A level with no neighbour updater says so by
     * throwing.
     */
    default void updateNeighborsAt(BlockPos pos, net.minecraft.world.level.block.Block sourceBlock,
            net.minecraft.core.@org.jspecify.annotations.Nullable Direction skipDirection) {
        throw new UnsupportedOperationException("this level has no neighbour updater");
    }

    /**
     * The extra {@code ClientboundBlockUpdatePacket} a landing falling block
     * sends to the players tracking it ({@code FallingBlockEntity.java:197-199}).
     */
    void landingBlockUpdate(KestrelEntity entity, BlockPos pos, BlockState state);

    /** {@code Level.getRandom}: the level random. */
    RandomSource random();

    /** {@code ServerLevel.getGameRules}: the live rules, read where vanilla reads them. */
    net.minecraft.world.level.gamerules.GameRules gameRules();

    /**
     * {@code Level.playSound(null, x, y, z, sound, source, volume, pitch)}, as
     * {@code Entity.playSound} raises it. Delivery to clients is G67.
     */
    void playSound(double x, double y, double z, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound,
            net.minecraft.sounds.SoundSource source, float volume, float pitch);

    /** {@code ServerLevel.broadcastEntityEvent(entity, event)}. Delivery to clients is G67. */
    void entityEvent(KestrelEntity entity, byte event);

    /** Spawn one vanilla-style item drop through the level's entity world. */
    default void spawnItem(double x, double y, double z, net.minecraft.world.item.ItemStack stack) {
        throw new UnsupportedOperationException("this level has no entity world");
    }

    /** Observer for the gate's spawn-order log; the live server has nothing to do. */
    default void entityAdded(KestrelEntity entity) { }

    /** The entity world, if this level is wired to one. */
    default @org.jspecify.annotations.Nullable EntityWorld world() {
        return null;
    }

    /** Registries used by vanilla loot codecs and enchantment effects. */
    default net.minecraft.core.RegistryAccess registryAccess() {
        return net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
    }

    /**
     * {@code MinecraftServer.getRandomSequence} ({@code MinecraftServer.java:1787-1789}): the
     * per-loot-table random every block loot table names ({@code "random_sequence"}), seeded
     * from the world seed. Only a level with loot tables loaded rolls one.
     */
    default net.minecraft.util.RandomSource randomSequence(net.minecraft.resources.Identifier key) {
        throw new IllegalStateException("no random sequences on this level (" + key + ")");
    }

    /**
     * Whether the chunk {@code chunkKey} ({@code ChunkPos.pack}) is loaded, answering
     * {@code Level.isLoaded} ({@code Level.java:722-726}) for loot {@code location_check}
     * predicates. A test arena holds every chunk it uses.
     */
    default boolean isLoadedChunk(long chunkKey) {
        return true;
    }

    /** The reloadable loot registries exposed by {@code MinecraftServer}. */
    default net.minecraft.server.ReloadableServerRegistries.Holder reloadableRegistries() {
        return new net.minecraft.server.ReloadableServerRegistries.Holder(registryAccess());
    }

    /** Accept a vanilla entity created by a reused block hook. */
    default boolean addFreshVanillaEntity(net.minecraft.world.entity.Entity entity) {
        EntityWorld w = world();
        if (w != null) {
            return w.addFreshVanillaEntity(entity);
        }
        throw new UnsupportedOperationException("vanilla entity spawn");
    }
}
