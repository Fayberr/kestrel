package dev.kestrel.loop;

import dev.kestrel.redstone.MovingPiston;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Live block-entity storage for the TickLoop world: real vanilla
 * {@link BlockEntity} instances keyed by position, with the lifecycle
 * {@code LevelChunk.setBlockState} gives them (drop on block swap, create
 * after {@code onPlace}, re-point at the new state otherwise).
 *
 * <p>Deliberately minimal (G12 slices 2-4). Only the types the redstone path
 * reads are created: the comparator's cached output and the containers whose
 * analog signal is {@code AbstractContainerMenu.getRedstoneSignalFromBlockEntity}.
 * Every other {@link EntityBlock} gets no instance, so readers see null and
 * fall back to vanilla's own "no block entity" branch. The instances carry no
 * level ({@code BlockEntity.level} stays null), so vanilla's own
 * {@code setChanged} is a no-op; the redstone engine applies its side effect
 * explicitly. Slice 5 adds the moving-piston placeholders, which are not
 * vanilla {@link BlockEntity} instances at all (see below). Not persisted
 * yet: full block entities (tickers, NBT, sleeper integration) are roadmap
 * S14/G28 and grow from this map.
 */
public final class BlockEntityStore {
    private final Long2ObjectOpenHashMap<BlockEntity> byPos = new Long2ObjectOpenHashMap<>();
    /**
     * Moving-piston placeholders (G12 slice 5). Vanilla's
     * {@code PistonMovingBlockEntity} is a real block entity attached to a
     * concrete {@code Level}; Kestrel has none, so the state lives in
     * {@link MovingPiston} and is kept here, alongside the block entities it
     * stands in for, rather than in a second store. Insertion order is the
     * order the loop ticks them in, which is the order vanilla's
     * {@code blockEntityTickers} list holds.
     */
    private final Long2ObjectOpenHashMap<MovingPiston> movingPistons = new Long2ObjectOpenHashMap<>();
    private final LongArrayList movingPistonOrder = new LongArrayList();

    /** {@code LevelChunk.getBlockEntity(pos, CHECK)}: the stored instance or null. */
    public BlockEntity get(BlockPos pos) {
        return byPos.get(pos.asLong());
    }

    /** The moving-piston placeholder at pos, or null. */
    public MovingPiston movingPiston(BlockPos pos) {
        return movingPistons.get(pos.asLong());
    }

    /** {@code Level.setBlockEntity} for a moving-piston placeholder. */
    public void putMovingPiston(MovingPiston movingPiston) {
        long key = movingPiston.pos().asLong();
        if (movingPistons.put(key, movingPiston) == null) {
            movingPistonOrder.add(key);
        }
    }

    /** {@code Level.removeBlockEntity} for a moving-piston placeholder, marking it removed. */
    public void removeMovingPiston(BlockPos pos) {
        MovingPiston removed = movingPistons.remove(pos.asLong());
        if (removed != null) {
            removed.setRemoved();
            movingPistonOrder.rem(pos.asLong());
        }
    }

    /**
     * Live moving pistons in tick order. The caller may remove entities while
     * walking this list (settling one placeholder can drop another), so it is
     * a snapshot and each entry is checked before it is used, the way
     * vanilla's ticker iterator does.
     */
    public java.util.List<MovingPiston> movingPistonsInTickOrder() {
        java.util.List<MovingPiston> out = new java.util.ArrayList<>(movingPistonOrder.size());
        for (int i = 0; i < movingPistonOrder.size(); i++) {
            MovingPiston mp = movingPistons.get(movingPistonOrder.getLong(i));
            if (mp != null && !mp.isRemoved()) {
                out.add(mp);
            }
        }
        return out;
    }

    /** Store an instance at its own position, replacing any previous one. */
    public void put(BlockEntity blockEntity) {
        byPos.put(blockEntity.getBlockPos().asLong(), blockEntity);
    }

    /** {@code LevelChunk.removeBlockEntity}: drop and mark removed. */
    public void remove(BlockPos pos) {
        BlockEntity removed = byPos.remove(pos.asLong());
        if (removed != null) {
            removed.setRemoved();
        }
    }

    public int size() {
        return byPos.size() + movingPistons.size();
    }

    public void clear() {
        byPos.clear();
        movingPistons.clear();
        movingPistonOrder.clear();
    }

    /**
     * {@code LevelChunk.setBlockState}, the removal half: on a real block swap
     * the old block entity goes unless the new state keeps it. Runs before
     * {@code affectNeighborsAfterRemoval}, as in vanilla. Container contents
     * would be dropped by {@code preRemoveSideEffects}; there are no item
     * entities in this world, so they are discarded with the instance.
     */
    public void beforeRemovalEffects(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!oldState.is(newState.getBlock()) && oldState.hasBlockEntity()
                && !newState.shouldChangedStateKeepBlockEntity(oldState)) {
            remove(pos);
            removeMovingPiston(pos);
        }
    }

    /**
     * {@code LevelChunk.setBlockState}, the creation half, after
     * {@code onPlace}: re-point a still-valid instance at the new state, drop
     * a mismatched one, and create one for a supported {@link EntityBlock}
     * that has none.
     */
    public void afterPlace(BlockPos pos, BlockState newState) {
        if (!newState.hasBlockEntity()) {
            return;
        }
        BlockEntity blockEntity = get(pos);
        if (blockEntity != null && !blockEntity.isValidBlockState(newState)) {
            remove(pos);
            blockEntity = null;
        }
        if (blockEntity == null) {
            if (supports(newState)) {
                BlockEntity created = ((EntityBlock) newState.getBlock()).newBlockEntity(pos.immutable(), newState);
                if (created != null) {
                    put(created);
                }
            }
        } else {
            blockEntity.setBlockState(newState);
        }
    }

    /** Both halves for a write that has no removal side effects of its own (facade path). */
    public void onSetBlock(BlockPos pos, BlockState oldState, BlockState newState) {
        beforeRemovalEffects(pos, oldState, newState);
        afterPlace(pos, newState);
    }

    /**
     * Types this store instantiates: the comparator, and the containers whose
     * {@code getAnalogOutputSignal} is exactly
     * {@code getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos))}.
     */
    public static boolean supports(BlockState state) {
        return state.is(Blocks.COMPARATOR) || dev.kestrel.redstone.AnalogOutput.isPlainContainer(state);
    }
}
