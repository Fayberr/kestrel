package dev.kestrel.redstone;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact transcription of vanilla 26.2's redstone update machinery: the
 * {@code Level.setBlock} / {@code LevelChunk.setBlockState} commit sequence,
 * {@code CollectingNeighborUpdater}, the {@code SignalGetter} signal algebra,
 * and the block-side {@code neighborChanged} / {@code updateShape} fan-out.
 *
 * <p>This is the default (non-experimental) path only. Vanilla picks the
 * evaluator in {@code RedStoneWireBlock.updatePowerStrength} by testing
 * {@code level.enabledFeatures().contains(FeatureFlags.REDSTONE_EXPERIMENTS)}
 * ({@code RedStoneWireBlock.useExperimentalEvaluator}); that flag is off in
 * every standard world, so {@code DefaultRedstoneWireEvaluator} is the sole
 * parity target and the only evaluator implemented here.
 *
 * <p>Why a separate engine rather than calls into vanilla: every one of these
 * methods takes a concrete {@code net.minecraft.world.level.Level}, which
 * Kestrel has no instance of and cannot proxy. Block-state-only helpers
 * ({@code BlockState.updateShape}, {@code getSignal}, {@code isRedstoneConductor})
 * do take interfaces, so those are still dispatched into real vanilla code
 * through {@link RedstoneHost#levelReader()} and this class's own
 * {@link SignalGetter} implementation.
 */
public final class VanillaRedstone implements SignalGetter {

    /** {@code NeighborUpdater.UPDATE_ORDER}: X axis, then Y, then Z (MC-11193). */
    public static final Direction[] UPDATE_ORDER = {
        Direction.WEST, Direction.EAST, Direction.DOWN,
        Direction.UP, Direction.NORTH, Direction.SOUTH,
    };

    /** {@code BlockBehaviour.UPDATE_SHAPE_ORDER}: horizontal X, horizontal Z, then Y. */
    public static final Direction[] UPDATE_SHAPE_ORDER = {
        Direction.WEST, Direction.EAST, Direction.NORTH,
        Direction.SOUTH, Direction.DOWN, Direction.UP,
    };

    /** {@code ServerLevel} builds its updater with maxChainedNeighborUpdates = 1000000. */
    public static final int MAX_CHAINED_NEIGHBOR_UPDATES = 1_000_000;

    private final RedstoneHost host;
    private java.util.function.BiConsumer<BlockPos, BlockState> fallingTick;
    private WriteListener writeListener;

    @FunctionalInterface
    public interface WriteListener {
        void written(BlockPos pos, BlockState oldState, BlockState newState, int flags);
    }

    public void setWriteListener(WriteListener listener) { this.writeListener = listener; }

    public void setFallingTick(java.util.function.BiConsumer<BlockPos, BlockState> fallingTick) {
        this.fallingTick = fallingTick;
    }

    void fallingTick(BlockPos pos, BlockState state) {
        if (fallingTick != null) fallingTick.accept(pos, state);
    }

    /**
     * {@code RedStoneWireBlock.shouldSignal}. Vanilla keeps this on the
     * shared block singleton and flips it across {@code getBlockSignal};
     * Kestrel cannot write the singleton (it is shared with whatever else
     * runs in this JVM, including the oracle), so the flag lives here and
     * {@link #getSignal} / {@link #getDirectSignal} apply it to wire states.
     * Single tick thread, so a plain field is equivalent.
     */
    private boolean shouldSignal = true;

    // ---- CollectingNeighborUpdater state ----
    private final ArrayDeque<NeighborUpdates> stack = new ArrayDeque<>();
    private final List<NeighborUpdates> addedThisLayer = new ArrayList<>();
    private int count;

    // ---- block events (ServerLevel.blockEvents) ----

    /**
     * {@code ServerLevel.blockEvents}: insertion-ordered, deduplicated by
     * {@code (pos, block, paramA, paramB)}. A piston never moves blocks inside
     * the neighbor update that powered it; it enqueues an event here and the
     * loop drains the queue in its own tick phase.
     */
    private final ObjectLinkedOpenHashSet<BlockEventData> blockEvents = new ObjectLinkedOpenHashSet<>();

    /**
     * Enqueue log for the parity harness: one entry per accepted {@code add},
     * in enqueue order, with the game time of the enqueue. That is exactly
     * what the oracle's {@code EventLoggingSet} records into the {@code E}
     * lines, so a replay can reproduce them. Off by default: nothing in the
     * running world reads it, and an unbounded log of every piston activation
     * would be a leak.
     */
    private final List<EnqueuedBlockEvent> enqueuedBlockEvents = new ArrayList<>();
    private boolean blockEventLogEnabled;

    /**
     * {@code ServerLevel.handlingTick}: raised at the top of
     * {@code ServerLevel.tick} (ServerLevel.java:356) and cleared once
     * {@code runBlockEvents} has returned (ServerLevel.java:408), so it is
     * down again for the block entities. {@code PistonBaseBlock.checkIfExtend}
     * reads it to treat a retraction seen during the block-event phase as a
     * drop, while one seen later - from a settling moving piston's neighbour
     * update - is a contract.
     */
    private boolean handlingTick;

    /** One {@code Level.blockEvent} enqueue: the tick it happened on and its payload. */
    public record EnqueuedBlockEvent(long gameTime, BlockPos pos, Block block, int paramA, int paramB) {
    }

    public VanillaRedstone(RedstoneHost host) {
        this.host = host;
    }

    // =====================================================================
    // BlockGetter / SignalGetter
    // =====================================================================

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return host.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return host.getBlockState(pos).getFluidState();
    }

    /** {@code Level.getBlockEntity}: the loop's live instance, or null. */
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return host.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return 4096;
    }

    @Override
    public int getMinY() {
        return -2048;
    }

    /**
     * {@code SignalGetter.getDirectSignal} plus the wire shouldSignal gate.
     * {@code RedStoneWireBlock.getDirectSignal} returns 0 whenever
     * shouldSignal is false, which is exactly the state
     * {@link #getBlockSignal} holds it in, so wires never feed their own
     * recompute through a conductor.
     */
    @Override
    public int getDirectSignal(BlockPos pos, Direction direction) {
        BlockState state = getBlockState(pos);
        if (!shouldSignal && state.is(Blocks.REDSTONE_WIRE)) {
            return 0;
        }
        return state.getDirectSignal(this, pos, direction);
    }

    /**
     * {@code SignalGetter.getSignal}: the block's own weak signal, raised to
     * the strong signal reaching it when the block is a redstone conductor.
     * Kestrel's previous wire recompute called {@code BlockState.getSignal}
     * directly and so missed the conductor branch entirely.
     */
    @Override
    public int getSignal(BlockPos pos, Direction direction) {
        BlockState state = getBlockState(pos);
        int signal = !shouldSignal && state.is(Blocks.REDSTONE_WIRE)
                ? 0
                : state.getSignal(this, pos, direction);
        return state.isRedstoneConductor(this, pos)
                ? Math.max(signal, this.getDirectSignalTo(pos))
                : signal;
    }

    /** {@code RedStoneWireBlock.getBlockSignal}: best neighbor signal with wires muted. */
    int getBlockSignal(BlockPos pos) {
        this.shouldSignal = false;
        int blockSignal = this.getBestNeighborSignal(pos);
        this.shouldSignal = true;
        return blockSignal;
    }

    /** True while wire states report their own signal ({@code shouldSignal}). */
    boolean shouldSignal() {
        return this.shouldSignal;
    }

    // =====================================================================
    // Level.setBlock / LevelChunk.setBlockState
    // =====================================================================

    /** {@code Level.setBlock(pos, state, flags)}: updateLimit defaults to 512. */
    public boolean setBlock(BlockPos pos, BlockState blockState, int updateFlags) {
        return setBlock(pos, blockState, updateFlags, 512);
    }

    /**
     * {@code Level.setBlock(pos, state, flags, updateLimit)} folded together
     * with the server-side half of {@code LevelChunk.setBlockState} that it
     * calls into. Ordering is vanilla's and load-bearing:
     * removal side effects, then {@code onPlace} (both inside the chunk
     * write), then the flag-1 neighbor cascade, then the three shape passes.
     *
     * <p>Dropped deliberately, none of which is observable in the fixtures:
     * heightmaps, lighting and POI. Client packets are the host's: it hears
     * {@link RedstoneHost#blockUpdated} where vanilla calls
     * {@code sendBlockUpdated}. Block entities follow the chunk's
     * drop/create lifecycle through the host.
     *
     * <p>Returns false for a hook veto (see
     * {@link RedstoneHost#fireBlockChange}) and, as vanilla does, for a
     * no-op write. {@code FallingBlockEntity.tick} reads that false
     * ({@code FallingBlockEntity.java:196}, {@code :222}); the wire callers
     * that read the result only ever write a changed power level. The
     * out-of-bounds false is not modelled: callers never write outside the
     * store.
     */
    public boolean setBlock(BlockPos pos, BlockState blockState, int updateFlags, int updateLimit) {
        BlockState oldState = getBlockState(pos);
        // LevelChunk.setBlockState: oldState == state returns null, and
        // Level.setBlock then does nothing at all and returns false.
        if (oldState == blockState) {
            return false;
        }
        pos = pos.immutable();
        host.rawSet(pos, blockState);
        if (writeListener != null) writeListener.written(pos, oldState, blockState, updateFlags);
        Block newBlock = blockState.getBlock();
        boolean movedByPiston = (updateFlags & 64) != 0;
        boolean blockChanged = !oldState.is(newBlock);
        // LevelChunk.setBlockState: a real block swap that drops the old block
        // entity and does not skip side effects gives it one last word
        // (preRemoveSideEffects) before the chunk drops it. For a moving
        // piston placeholder that is PistonMovingBlockEntity.finalTick, which
        // settles the transported block instead of letting it vanish.
        if (blockChanged && oldState.hasBlockEntity()
                && !blockState.shouldChangedStateKeepBlockEntity(oldState)
                && (updateFlags & 256) == 0) {
            BlockEntity oldBlockEntity = host.getBlockEntity(pos);
            if (oldBlockEntity != null) {
                oldBlockEntity.preRemoveSideEffects(pos, oldState);
            }
            MovingPiston oldMovingPiston = host.getMovingPiston(pos);
            if (oldMovingPiston != null) {
                oldMovingPiston.finalTick(this);
            }
        }
        // LevelChunk.setBlockState: the old block entity goes on a real block
        // swap unless the new state keeps it (preRemoveSideEffects would drop
        // container contents as item entities; this world has none).
        host.blockEntityBeforeRemoval(pos, oldState, blockState);
        if (host.fireBlockChange(pos, oldState, blockState, updateFlags)) {
            host.blockEntityAfterPlace(pos, blockState);
            return false;
        }

        // LevelChunk.setBlockState: affectNeighborsAfterRemoval only on a
        // real block swap (or any rail write) that carries neighbor updates.
        if ((blockChanged || newBlock instanceof BaseRailBlock) && ((updateFlags & 1) != 0 || movedByPiston)) {
            BlockLogic.affectNeighborsAfterRemoval(this, oldState, pos, movedByPiston);
        }
        // LevelChunk.setBlockState: removal side effects replaced the block,
        // so the chunk returns null and Level.setBlock does nothing more.
        if (!getBlockState(pos).is(newBlock)) {
            return false;
        }
        // LevelChunk.setBlockState: onPlace unless UPDATE_SKIP_ON_PLACE (512).
        if ((updateFlags & 512) == 0) {
            BlockLogic.onPlace(this, blockState, pos, oldState, movedByPiston);
            host.afterSetBlock(pos, oldState, blockState, updateFlags);
        }
        // LevelChunk.setBlockState: create, re-point or replace the block entity.
        if (getBlockState(pos).is(newBlock)) {
            host.blockEntityAfterPlace(pos, blockState);
        }
        // Level.setBlock: everything below runs only if the cell still holds
        // exactly the state written (onPlace may have rewritten it, as
        // ObserverBlock.onPlace does).
        if (getBlockState(pos) != blockState) {
            return true;
        }
        // Level.setBlock: UPDATE_CLIENTS (2) reports the cell to the chunk
        // holder's broadcast (sendBlockUpdated), before the neighbour updates.
        if ((updateFlags & 2) != 0) {
            host.blockUpdated(pos);
        }
        // Level.setBlock: UPDATE_NEIGHBORS (1). Note the source block handed
        // to the cascade is the OLD block, not the one just written.
        if ((updateFlags & 1) != 0) {
            this.updateNeighborsAt(pos, oldState.getBlock());
            if (blockState.hasAnalogOutputSignal()) {
                this.updateNeighbourForOutputSignal(pos, newBlock);
            }
        }
        // Level.setBlock: UPDATE_KNOWN_SHAPE (16) suppresses the shape passes.
        // -34 clears UPDATE_NEIGHBORS (1) and UPDATE_MOVE_BY_PISTON (32).
        if ((updateFlags & 16) == 0 && updateLimit > 0) {
            int neighbourUpdateFlags = updateFlags & -34;
            BlockLogic.updateIndirectNeighbourShapes(this, oldState, pos, neighbourUpdateFlags, updateLimit - 1);
            this.updateNeighbourShapes(blockState, pos, neighbourUpdateFlags, updateLimit - 1);
            BlockLogic.updateIndirectNeighbourShapes(this, blockState, pos, neighbourUpdateFlags, updateLimit - 1);
        }
        return true;
    }

    /**
     * {@code Level.updateNeighbourForOutputSignal}: a block whose analog
     * output may have changed pokes the comparators reading it, directly
     * beside it or one conductor further out, horizontally only.
     */
    public void updateNeighbourForOutputSignal(BlockPos pos, Block changedBlock) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos relativePos = pos.relative(direction);
            BlockState state = getBlockState(relativePos);
            if (state.is(Blocks.COMPARATOR)) {
                this.neighborChanged(state, relativePos, changedBlock, false);
            } else if (state.isRedstoneConductor(this, relativePos)) {
                relativePos = relativePos.relative(direction);
                state = getBlockState(relativePos);
                if (state.is(Blocks.COMPARATOR)) {
                    this.neighborChanged(state, relativePos, changedBlock, false);
                }
            }
        }
    }

    /**
     * {@code BaseContainerBlockEntity.setItem} followed by the
     * {@code BlockEntity.setChanged} it ends with. The instance has no level
     * (see the loop's block-entity store), so vanilla's own setChanged is a
     * no-op and its one world effect, {@code Level.blockEntityChanged} then
     * {@code updateNeighbourForOutputSignal} for a non-air state, is applied
     * here. Returns false (nothing written) when pos holds no container.
     */
    public boolean containerSetItem(BlockPos pos, int slot, net.minecraft.world.item.ItemStack stack) {
        if (!(getBlockEntity(pos) instanceof net.minecraft.world.Container container)) {
            return false;
        }
        container.setItem(slot, stack);
        BlockState state = getBlockState(pos);
        if (!state.isAir()) {
            this.updateNeighbourForOutputSignal(pos, state.getBlock());
        }
        return true;
    }

    /** {@code Level.removeBlock}: air at flags 3, plus the piston bit. */
    public boolean removeBlock(BlockPos pos, boolean movedByPiston) {
        FluidState fluidState = this.getFluidState(pos);
        return setBlock(pos, fluidState.createLegacyBlock(), 3 | (movedByPiston ? 64 : 0));
    }

    /**
     * {@code Level.destroyBlock}. Item drops are out of scope for the
     * redstone engine (no entities in this world), so this is the state
     * half only: the block becomes air with full updates.
     */
    public boolean destroyBlock(BlockPos pos, int updateLimit) {
        BlockState blockState = getBlockState(pos);
        if (blockState.isAir()) {
            return false;
        }
        FluidState fluidState = blockState.getFluidState();
        if (!(blockState.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock)) {
            levelEvent(2001, pos, Block.getId(blockState));
        }
        return setBlock(pos, fluidState.createLegacyBlock(), 3, updateLimit);
    }

    /** {@code BlockBehaviour.BlockStateBase.updateNeighbourShapes}. */
    void updateNeighbourShapes(BlockState state, BlockPos pos, int updateFlags, int updateLimit) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : UPDATE_SHAPE_ORDER) {
            cursor.setWithOffset(pos, direction);
            this.neighborShapeChanged(direction.getOpposite(), cursor, pos, state, updateFlags, updateLimit);
        }
    }

    /** {@code Block.updateOrDestroy}. */
    public void updateOrDestroy(BlockState oldState, BlockState newState, BlockPos pos, int updateFlags, int updateLimit) {
        if (newState != oldState) {
            if (newState.isAir()) {
                this.destroyBlock(pos, updateLimit);
            } else {
                // -33 clears UPDATE_MOVE_BY_PISTON only.
                this.setBlock(pos, newState, updateFlags & -33, updateLimit);
            }
        }
    }

    // =====================================================================
    // Level / ServerLevel update entry points
    // =====================================================================

    /** {@code ServerLevel.updateNeighborsAt}. */
    public void updateNeighborsAt(BlockPos pos, Block sourceBlock) {
        host.countNeighborDispatch();
        this.updateNeighborsAtExceptFromFacing(pos, sourceBlock, null);
    }

    /** {@code ServerLevel.updateNeighborsAtExceptFromFacing}. */
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block sourceBlock, Direction skipDirection) {
        this.addAndRun(new MultiNeighborUpdate(pos.immutable(), sourceBlock, skipDirection));
    }

    /** {@code ServerLevel.neighborChanged(pos, block, orientation)}. */
    public void neighborChanged(BlockPos pos, Block changedBlock) {
        this.addAndRun(new SimpleNeighborUpdate(pos.immutable(), changedBlock));
    }

    /** {@code ServerLevel.neighborChanged(state, pos, block, orientation, moved)}. */
    public void neighborChanged(BlockState state, BlockPos pos, Block changedBlock, boolean movedByPiston) {
        this.addAndRun(new FullNeighborUpdate(state, pos.immutable(), changedBlock, movedByPiston));
    }

    /** {@code Level.neighborShapeChanged}. */
    public void neighborShapeChanged(Direction direction, BlockPos pos, BlockPos neighborPos,
            BlockState neighborState, int updateFlags, int updateLimit) {
        this.addAndRun(new ShapeUpdate(direction, neighborState, pos.immutable(), neighborPos.immutable(),
                updateFlags, updateLimit));
    }

    /** {@code ServerLevel.scheduleTick}. */
    public void scheduleTick(BlockPos pos, Block block, int delay, net.minecraft.world.ticks.TickPriority priority) {
        host.scheduleTick(pos.immutable(), block, delay, priority);
    }

    /** {@code ServerLevel.scheduleTick} with the default NORMAL priority. */
    public void scheduleTick(BlockPos pos, Block block, int delay) {
        this.scheduleTick(pos, block, delay, net.minecraft.world.ticks.TickPriority.NORMAL);
    }

    /** {@code LevelTicks.willTickThisTick}. */
    public boolean willTickThisTick(BlockPos pos, Block block) {
        return host.willTickThisTick(pos, block);
    }

    /** {@code LevelTicks.hasScheduledTick}. */
    public boolean hasScheduledTick(BlockPos pos, Block block) {
        return host.hasScheduledTick(pos, block);
    }

    /** {@code Level.getGameTime}. */
    public long getGameTime() {
        return host.gameTime();
    }

    // =====================================================================
    // Block events (ServerLevel Phase 6)
    // =====================================================================

    /** {@code ServerLevel.blockEvent}: queue it, keeping FIFO order and dropping exact duplicates. */
    public void blockEvent(BlockPos pos, Block block, int b0, int b1) {
        BlockEventData eventData = new BlockEventData(pos.immutable(), block, b0, b1);
        boolean added = this.blockEvents.add(eventData);
        if (added && this.blockEventLogEnabled) {
            this.enqueuedBlockEvents.add(
                    new EnqueuedBlockEvent(getGameTime(), eventData.pos(), block, b0, b1));
        }
    }

    /**
     * Turn the enqueue log on. Parity harnesses that record the oracle's
     * {@code E} lines enable it before replaying; the running world leaves it
     * off.
     */
    public void setBlockEventLogEnabled(boolean enabled) {
        this.blockEventLogEnabled = enabled;
        this.enqueuedBlockEvents.clear();
    }

    /** Queued block events not yet run (the entity phase's slot proof reads it). */
    public int pendingBlockEventCount() {
        return this.blockEvents.size();
    }

    /** Events enqueued since the last call, in enqueue order; the parity harness reads these. */
    public List<EnqueuedBlockEvent> takeEnqueuedBlockEvents() {
        List<EnqueuedBlockEvent> out = List.copyOf(this.enqueuedBlockEvents);
        this.enqueuedBlockEvents.clear();
        return out;
    }

    /**
     * {@code ServerLevel.runBlockEvents}: drain the queue front to back and
     * trigger each block's event. An event that enqueues another one during
     * its own execution joins the tail and is consumed in this same drain, as
     * in vanilla; an event whose position no longer holds the block it was
     * queued for is dropped.
     *
     * <p>Vanilla's {@code shouldTickBlocksAt} guard is absent: it decides
     * whether this level ticks blocks at the event's position at all, and
     * every position in a Kestrel world does, so the reschedule list vanilla
     * keeps for the unticked ones would always be empty. The packet broadcast
     * is absent too: there are no clients.
     */
    public void runBlockEvents() {
        while (!this.blockEvents.isEmpty()) {
            BlockEventData eventData = this.blockEvents.removeFirst();
            this.doBlockEvent(eventData);
        }
    }

    /** {@code ServerLevel.doBlockEvent}. */
    private boolean doBlockEvent(BlockEventData eventData) {
        BlockState state = getBlockState(eventData.pos());
        return state.is(eventData.block())
                && BlockLogic.triggerEvent(this, state, eventData.pos(), eventData.paramA(), eventData.paramB());
    }

    /** {@code ServerLevel.isHandlingTick}. */
    public boolean isHandlingTick() {
        return this.handlingTick;
    }

    /** The loop brackets each tick with this, as {@code ServerLevel.tick} does. */
    public void setHandlingTick(boolean handlingTick) {
        this.handlingTick = handlingTick;
    }

    // =====================================================================
    // Moving pistons and build bounds
    // =====================================================================

    /** {@code Level.getBlockEntity} narrowed to the moving-piston placeholder. */
    public MovingPiston getMovingPiston(BlockPos pos) {
        return host.getMovingPiston(pos);
    }

    /** {@code Level.setBlockEntity} for the placeholder entity. */
    public void setMovingPiston(MovingPiston movingPiston) {
        host.setMovingPiston(movingPiston);
    }

    /** {@code Level.removeBlockEntity} for the placeholder entity. */
    public void removeMovingPiston(BlockPos pos) {
        host.removeMovingPiston(pos);
    }

    /** {@code Level.getMinY}, the real build floor of this world. */
    public int minBuildY() {
        return host.minBuildY();
    }

    /** {@code Level.getMaxY}: {@code getMinY() + getHeight() - 1}. */
    public int maxBuildY() {
        return host.maxBuildY();
    }

    /**
     * A {@code LevelAccessor} over this world, for the vanilla methods that
     * take one ({@code Block.updateFromNeighbourShapes}). State reads come
     * from the store; the {@code ScheduledTickAccess} half is the loop's real
     * queue, so a shape update that schedules still lands in the right place.
     */
    private net.minecraft.world.level.LevelAccessor levelAccessorInstance;

    net.minecraft.world.level.LevelAccessor levelAccessor() {
        if (levelAccessorInstance == null) {
            levelAccessorInstance = (net.minecraft.world.level.LevelAccessor)
                    java.lang.reflect.Proxy.newProxyInstance(
                            VanillaRedstone.class.getClassLoader(),
                            new Class<?>[]{net.minecraft.world.level.LevelAccessor.class},
                            (proxy, method, args) -> {
                                String name = method.getName();
                                if (name.equals("getBlockState") && args != null && args.length == 1) {
                                    return getBlockState((BlockPos) args[0]);
                                }
                                if (name.equals("getFluidState") && args != null && args.length == 1) {
                                    return getFluidState((BlockPos) args[0]);
                                }
                                if (name.equals("getBlockEntity") && args != null && args.length == 1) {
                                    return getBlockEntity((BlockPos) args[0]);
                                }
                                if (name.equals("getBlockEntity") && args != null && args.length >= 1) {
                                    return null;
                                }
                                if (name.equals("getHeight")) {
                                    return getHeight();
                                }
                                if (name.equals("getMinY")) {
                                    return getMinY();
                                }
                                if (name.equals("getRandom")) {
                                    return randomSource();
                                }
                                if (name.equals("isClientSide")) {
                                    return false;
                                }
                                // ScheduledTickAccess half: the loop's real queue, so the
                                // default methods on it run as written.
                                if (name.equals("createTick") || name.equals("scheduleTick")
                                        || name.equals("getBlockTicks") || name.equals("getFluidTicks")) {
                                    return method.invoke(tickAccess(), args);
                                }
                                if (name.equals("toString")) {
                                    return "VanillaRedstoneLevelAccessor";
                                }
                                if (name.equals("hashCode")) {
                                    return System.identityHashCode(proxy);
                                }
                                if (name.equals("equals")) {
                                    return proxy == args[0];
                                }
                                Class<?> rt = method.getReturnType();
                                if (!rt.isPrimitive()) {
                                    return null;
                                }
                                if (rt == boolean.class) {
                                    return false;
                                }
                                if (rt == void.class) {
                                    return null;
                                }
                                return 0;
                            });
        }
        return levelAccessorInstance;
    }

    /**
     * The RNG handed to vanilla {@code updateShape} bodies. No block on the
     * G12 path reads randomness in {@code updateShape}, so a fresh source
     * seeded the same way on every call is as good as the level's shared
     * generator and keeps a replay independent of call order. This matches
     * the loop's own shape pass ({@code TickLoop.runShapeUpdates}).
     */
    RandomSource randomSource() {
        return host.random();
    }

    /** {@code Level.levelEvent}. */
    void levelEvent(int type, BlockPos pos, int data) {
        host.levelEvent(type, pos.immutable(), data);
    }

    /**
     * {@code RedstoneTorchBlock.RECENT_TOGGLES} entry for this level. Vanilla
     * keys a static WeakHashMap by level; one engine serves one level, so the
     * list lives here and dies with the world.
     */
    record Toggle(BlockPos pos, long when) {
    }

    private final List<Toggle> recentToggles = new ArrayList<>();

    /** {@code RECENT_TOGGLES.get(level)} for this engine's level. */
    List<Toggle> recentToggles() {
        return recentToggles;
    }

    net.minecraft.world.level.LevelReader levelReader() {
        return host.levelReader();
    }

    net.minecraft.world.level.ScheduledTickAccess tickAccess() {
        return host.tickAccess();
    }

    // =====================================================================
    // CollectingNeighborUpdater
    // =====================================================================

    /**
     * {@code CollectingNeighborUpdater.addAndRun}. An update raised while a
     * cascade is already running joins {@code addedThisLayer} instead of
     * running now; only the outermost call drives {@link #runUpdates}.
     */
    private void addAndRun(NeighborUpdates update) {
        boolean runningAlready = this.count > 0;
        boolean tooManyUpdates = this.count >= MAX_CHAINED_NEIGHBOR_UPDATES;
        this.count++;
        if (tooManyUpdates) {
            host.countUpdateDropped();
        } else if (runningAlready) {
            this.addedThisLayer.add(update);
        } else {
            this.stack.push(update);
        }
        if (!runningAlready) {
            this.runUpdates();
        }
    }

    /**
     * {@code CollectingNeighborUpdater.runUpdates}. The two orderings that
     * matter, and why:
     *
     * <ul>
     * <li>{@code addedThisLayer} is drained onto the stack back to front, so
     *     the updates a single step raised pop in the order they were raised
     *     (FIFO within a layer).</li>
     * <li>The inner loop keeps stepping the top entry only while
     *     {@code addedThisLayer} is empty. The moment a step raises children
     *     it breaks out, the children are pushed above the parent, and the
     *     parent resumes from its saved index once they finish (LIFO between
     *     layers). That is what makes a {@code MultiNeighborUpdate} interleave
     *     with the cascades its own steps start, rather than running all six
     *     directions first.</li>
     * </ul>
     */
    private void runUpdates() {
        try {
            while (!this.stack.isEmpty() || !this.addedThisLayer.isEmpty()) {
                for (int i = this.addedThisLayer.size() - 1; i >= 0; i--) {
                    this.stack.push(this.addedThisLayer.get(i));
                }
                this.addedThisLayer.clear();
                NeighborUpdates nextUpdates = this.stack.peek();
                while (this.addedThisLayer.isEmpty()) {
                    if (!nextUpdates.runNext(this)) {
                        this.stack.pop();
                        break;
                    }
                }
            }
        } finally {
            this.stack.clear();
            this.addedThisLayer.clear();
            this.count = 0;
        }
    }

    /** {@code NeighborUpdater.executeUpdate}: dispatch to the block's neighborChanged. */
    private static void executeUpdate(VanillaRedstone level, BlockState state, BlockPos pos,
            Block changedBlock, boolean movedByPiston) {
        level.host.neighborChanged(pos, state);
        BlockLogic.neighborChanged(level, state, pos, changedBlock, movedByPiston);
    }

    /** {@code NeighborUpdater.executeShapeUpdate}. */
    private static void executeShapeUpdate(VanillaRedstone level, Direction direction, BlockPos pos,
            BlockPos neighborPos, BlockState neighborState, int updateFlags, int updateLimit) {
        BlockState currentState = level.getBlockState(pos);
        // Flag 128 (UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE) is how the experimental
        // evaluator skips wire shape churn; on the default path it is never set.
        if ((updateFlags & 128) == 0 || !currentState.is(Blocks.REDSTONE_WIRE)) {
            if (currentState.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                level.host.shapeChanged(pos, currentState, direction, neighborState);
                return;
            }
            BlockState newState = currentState.updateShape(
                    level.levelReader(), level.tickAccess(),
                    pos, direction, neighborPos, neighborState, level.randomSource());
            level.updateOrDestroy(currentState, newState, pos, updateFlags, updateLimit);
        }
    }

    private interface NeighborUpdates {
        /** Runs one step; true when this entry has more steps left. */
        boolean runNext(VanillaRedstone level);
    }

    /** {@code CollectingNeighborUpdater.SimpleNeighborUpdate}. */
    private record SimpleNeighborUpdate(BlockPos pos, Block block) implements NeighborUpdates {
        @Override
        public boolean runNext(VanillaRedstone level) {
            executeUpdate(level, level.getBlockState(this.pos), this.pos, this.block, false);
            return false;
        }
    }

    /** {@code CollectingNeighborUpdater.FullNeighborUpdate}. */
    private record FullNeighborUpdate(BlockState state, BlockPos pos, Block block, boolean movedByPiston)
            implements NeighborUpdates {
        @Override
        public boolean runNext(VanillaRedstone level) {
            executeUpdate(level, this.state, this.pos, this.block, this.movedByPiston);
            return false;
        }
    }

    /** {@code CollectingNeighborUpdater.ShapeUpdate}. */
    private record ShapeUpdate(Direction direction, BlockState neighborState, BlockPos pos,
            BlockPos neighborPos, int updateFlags, int updateLimit) implements NeighborUpdates {
        @Override
        public boolean runNext(VanillaRedstone level) {
            executeShapeUpdate(level, this.direction, this.pos, this.neighborPos, this.neighborState,
                    this.updateFlags, this.updateLimit);
            return false;
        }
    }

    /**
     * {@code CollectingNeighborUpdater.MultiNeighborUpdate}: a resumable walk
     * over {@code UPDATE_ORDER}, one direction per {@code runNext}.
     */
    private static final class MultiNeighborUpdate implements NeighborUpdates {
        private final BlockPos sourcePos;
        private final Block sourceBlock;
        private final Direction skipDirection;
        private int idx;

        MultiNeighborUpdate(BlockPos sourcePos, Block sourceBlock, Direction skipDirection) {
            this.sourcePos = sourcePos;
            this.sourceBlock = sourceBlock;
            this.skipDirection = skipDirection;
            if (UPDATE_ORDER[this.idx] == skipDirection) {
                this.idx++;
            }
        }

        @Override
        public boolean runNext(VanillaRedstone level) {
            Direction direction = UPDATE_ORDER[this.idx++];
            BlockPos neighborPos = this.sourcePos.relative(direction);
            BlockState state = level.getBlockState(neighborPos);
            executeUpdate(level, state, neighborPos, this.sourceBlock, false);
            if (this.idx < UPDATE_ORDER.length && UPDATE_ORDER[this.idx] == this.skipDirection) {
                this.idx++;
            }
            return this.idx < UPDATE_ORDER.length;
        }
    }

    // =====================================================================
    // Wire entry point
    // =====================================================================

    /**
     * {@code RedStoneWireBlock.updatePowerStrength} on the default path:
     * recompute this wire and fan out. The loop's public {@code updatePowerAt}
     * lands here.
     */
    public void updateWirePower(BlockPos pos) {
        BlockState state = getBlockState(pos);
        if (!state.is(Blocks.REDSTONE_WIRE)) {
            return;
        }
        WireLogic.updatePowerStrength(this, pos, state);
    }

    /** {@code BlockState.tick} -> {@code Block.tick} for a due scheduled tick. */
    public void tickBlock(BlockPos pos, BlockState state) {
        BlockLogic.tick(this, state, pos);
    }

    /** {@code LeverBlock.pull}: the oracle's USE action on a lever. */
    public void leverPull(BlockPos pos, BlockState state) {
        BlockLogic.leverPull(this, state, pos);
    }

    /** {@code ButtonBlock.press}: the oracle's USE action on a button. */
    public void buttonPress(BlockPos pos, BlockState state, int ticksToStayPressed) {
        BlockLogic.buttonPress(this, state, pos, ticksToStayPressed);
    }

    /** {@code ComparatorBlock.useWithoutItem}: the mode toggle (player may build). */
    public void comparatorUse(BlockPos pos, BlockState state) {
        DiodeLogic.comparatorUseWithoutItem(this, state, pos);
    }

    /** {@code RedStoneWireBlock.useWithoutItem}: the dot/cross toggle. */
    public boolean wireUseWithoutItem(BlockPos pos, BlockState state) {
        return WireLogic.useWithoutItem(this, state, pos);
    }

    /** True when the wire at pos connects toward the given direction. */
    public boolean isWireConnected(BlockPos pos, Direction direction) {
        BlockState state = getBlockState(pos);
        return state.is(Blocks.REDSTONE_WIRE)
                && state.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction)).isConnected();
    }

    /** True for the diode blocks the engine knows how to tick. */
    public static boolean isDiode(BlockState state) {
        return state.getBlock() instanceof DiodeBlock;
    }
}
