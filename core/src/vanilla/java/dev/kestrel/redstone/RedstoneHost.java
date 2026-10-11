package dev.kestrel.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.util.RandomSource;

/**
 * Seam between {@link VanillaRedstone} and the tick loop that owns the world.
 *
 * <p>The engine transcribes vanilla's {@code Level}/{@code LevelChunk}
 * redstone paths method for method; everything it cannot compute from block
 * states alone (storage, the mutation journal, the scheduled-tick queue, the
 * plugin hooks) it asks the host for. Keeping those behind an interface is
 * what lets the engine stay a pure transcription with no loop internals in it.
 */
public interface RedstoneHost {

    /** Committed state at a position; unset cells read as air. */
    BlockState getBlockState(BlockPos pos);

    /**
     * Raw store write returning the previous state, with the loop's write
     * counter and mutation journal updated. Stands in for
     * {@code LevelChunkSection.setBlockState} inside
     * {@code LevelChunk.setBlockState}: no shape updates, no neighbor
     * dispatch, no side effects. The engine sequences all of those itself.
     */
    BlockState rawSet(BlockPos pos, BlockState state);

    /**
     * Kestrel's {@code BlockChange} hook, fired on the redstone commit path.
     * True means a subscriber vetoed: the write stands (it is already
     * committed) but every downstream dispatch the write would have caused
     * is dropped. Vanilla has no equivalent; this is the one deliberate
     * addition to the transcription.
     */
    boolean fireBlockChange(BlockPos pos, BlockState oldState, BlockState newState, int flags);

    /** One more {@code updateNeighborsAt} step, for the loop's dispatch counter. */
    void countNeighborDispatch();

    /**
     * One neighbor update skipped because the cascade hit
     * {@code maxChainedNeighborUpdates}. Vanilla logs once and drops the
     * rest; Kestrel counts them so the condition is observable.
     */
    void countUpdateDropped();

    /** {@code ServerLevel.scheduleTick}: into the loop's block-tick queue. */
    void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority);

    /** {@code LevelTicks.willTickThisTick}: collected for this tick, not yet run. */
    boolean willTickThisTick(BlockPos pos, Block block);

    /**
     * LevelReader proxy for vanilla {@code updateShape} calls. Must answer
     * the {@link net.minecraft.world.level.SignalGetter} defaults off the
     * same world the engine reads, or repeater locking silently resolves to
     * "unlocked" on every shape update.
     */
    LevelReader levelReader();

    /**
     * ScheduledTickAccess handed to vanilla {@code updateShape}: a real one,
     * backed by the loop's queues, so {@code ObserverBlock.startSignal} can
     * query and schedule.
     */
    ScheduledTickAccess tickAccess();

    RandomSource random();

    void afterSetBlock(BlockPos pos, BlockState oldState, BlockState newState, int flags);

    void neighborChanged(BlockPos pos, BlockState state);

    void shapeChanged(BlockPos pos, BlockState state, Direction direction, BlockState neighborState);

    /** {@code LevelTicks.hasScheduledTick}: pending in the block queue, not yet collected. */
    boolean hasScheduledTick(BlockPos pos, Block block);

    /** {@code Level.getGameTime}. */
    long gameTime();

    /** {@code Level.getBlockEntity}: the live instance at pos, or null. */
    BlockEntity getBlockEntity(BlockPos pos);

    /**
     * The moving-piston placeholder entity at pos, or null. Vanilla's moving
     * block entity is a {@link BlockEntity} on a real {@code Level}; Kestrel
     * has none, so the state lives in {@link MovingPiston} and the loop's
     * block-entity store owns it (see {@code BlockEntityStore}).
     */
    MovingPiston getMovingPiston(BlockPos pos);

    /** {@code Level.setBlockEntity} for a moving-piston placeholder. */
    void setMovingPiston(MovingPiston movingPiston);

    /** {@code Level.removeBlockEntity} for a moving-piston placeholder. */
    void removeMovingPiston(BlockPos pos);

    /** {@code Level.getMinY}: the lowest buildable Y of this world. */
    int minBuildY();

    /** {@code Level.getMaxY}: the highest buildable Y of this world. */
    int maxBuildY();

    /**
     * {@code LevelChunk.setBlockState}, block-entity removal half: runs after
     * the section write and before {@code affectNeighborsAfterRemoval}.
     */
    void blockEntityBeforeRemoval(BlockPos pos, BlockState oldState, BlockState newState);

    /**
     * {@code LevelChunk.setBlockState}, block-entity creation half: runs
     * after {@code onPlace}.
     */
    void blockEntityAfterPlace(BlockPos pos, BlockState newState);

    /**
     * {@code Level.levelEvent} (for example 1502, redstone torch burnout
     * smoke). Client-only effects in vanilla; nothing in the world state
     * depends on it, so the default is a no-op until the loop grows a
     * level-event channel.
     */
    default void levelEvent(int type, BlockPos pos, int data) {
    }

    /**
     * {@code Level.sendBlockUpdated} from {@code Level.setBlock}: a write
     * carrying {@code UPDATE_CLIENTS} (2) left the cell holding the written
     * state, so vanilla reports it to {@code ChunkHolder.blockChanged} for
     * this tick's broadcast. Called before the neighbour updates, where
     * vanilla calls it.
     */
    default void blockUpdated(BlockPos pos) {
    }
}
