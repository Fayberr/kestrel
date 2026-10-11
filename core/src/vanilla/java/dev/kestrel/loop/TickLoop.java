package dev.kestrel.loop;

import dev.kestrel.blockentity.SleeperRegistry;
import dev.kestrel.redstone.AcFlag;
import dev.kestrel.redstone.AcWireNetwork;
import dev.kestrel.redstone.WireConnectionCache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase A in-process tick loop: writable section storage, a real setBlock
 * path, SleepLoop pass discipline, default-path redstone dispatch plus the AC
 * flag seam, and step-5 sleep/wake wiring. No networking (Phase B), no
 * persistence (Phase C).
 *
 * <p>Dispatch order is transcribed from the decompiled vanilla 26.2 source
 * (decompiled with Vineflower from the pinned server jar):
 *
 * <ul>
 *   <li>{@code DefaultRedstoneWireEvaluator.updatePowerStrength}
 *       ({@code RedstoneWireEvaluator.java:29-47},
 *       {@code DefaultRedstoneWireEvaluator.java:18-44}): recompute target =
 *       {@code max(external, incoming - 1)}; skip when unchanged; else
 *       {@code setBlock(pos, POWER=target, flags=2)} then
 *       {@code updateNeighborsAt} on self + all 6 neighbors
 *       ({@code RedStoneWireBlock.java:270-282, 277-281}).</li>
 *   <li>{@code MultiNeighborUpdate.runNext} walks
 *       {@code NeighborUpdater.UPDATE_ORDER} =
 *       {@code {WEST, EAST, DOWN, UP, NORTH, SOUTH}}
 *       ({@code NeighborUpdater.java:19}), one neighbor per step, and
 *       {@code CollectingNeighborUpdater.addAndRun/runUpdates} drains nested
 *       dispatches LIFO off a stack ({@code CollectingNeighborUpdater.java}),
 *       so a wire that changes mid-cascade re-enters before the outer
 *       cascade continues.</li>
 *   <li>{@code Level.setBlock} with flags 2 skips the flags-1 neighbor
 *       dispatch ({@code Level.java:248-253}) but still runs shape updates
 *       ({@code Level.java:255-260}); the evaluator's explicit 7-dispatch
 *       fan-out is the neighbor notification, not the setBlock flags.</li>
 *   <li>{@code ScheduledTick.DRAIN_ORDER} orders by (triggerTick, priority,
 *       subTickOrder) and {@code INTRA_TICK_DRAIN_ORDER} by (priority,
 *       subTickOrder) ({@code ScheduledTick.java:9-21}); dedupe keeps the
 *       earliest trigger per (pos, block) ({@code LevelChunkTicks}
 *       UNIQUE_TICK_HASH semantics).</li>
 * </ul>
 *
 * <p>G12 moved all of this into {@link dev.kestrel.redstone.VanillaRedstone},
 * an exact transcription of the vanilla classes named above. What changed in
 * the process, beyond moving code:
 *
 * <ul>
 *   <li>The 7-target fan-out now fires in {@code HashSet} iteration order,
 *       as vanilla's {@code Sets.newHashSet} does. The loop previously fired
 *       a fixed slot order (self, DOWN, UP, N, S, W, E), which is not what
 *       vanilla does and diverged from it on the update-order corpus.</li>
 *   <li>Neighbor notification goes through the real
 *       {@code CollectingNeighborUpdater} layering (a resumable
 *       {@code MultiNeighborUpdate} interleaved with the cascades its own
 *       steps start), not a flat LIFO stack of whole fan-outs, and it runs
 *       inside the call that started it rather than at a later drain.</li>
 *   <li>Wire recompute reads signals through {@code SignalGetter.getSignal},
 *       so a redstone conductor now contributes the strong signal reaching
 *       it. The old path called {@code BlockState.getSignal} directly and
 *       missed that branch entirely.</li>
 *   <li>Blocks react through transcribed {@code neighborChanged} bodies
 *       rather than an inline switch, and a changed block's writes follow
 *       the real {@code Level.setBlock} sequence (removal side effects,
 *       {@code onPlace}, flag-1 cascade, then the three shape passes).</li>
 * </ul>
 *
 * <p>Still deliberately absent: null orientation throughout (the default
 * path only, REDSTONE_EXPERIMENTS off). Pistons arrived with G12 slice 5 and
 * with them the block-event queue and the moving-piston placeholders; see
 * {@link dev.kestrel.redstone.PistonLogic} and
 * {@link dev.kestrel.redstone.BlockLogic} for what is transcribed.
 *
 * <p>G11 fluids: water and lava run on a second scheduled-tick queue with
 * the same drain discipline, drained after block ticks. {@link #tick}
 * follows {@code ServerLevel.tick}: gameTime advances first, then block
 * ticks, then fluid ticks. Every {@code setBlock} that is not vetoed hands
 * its fluid consequences (liquid onPlace, neighbor and shape updates in
 * {@code CollectingNeighborUpdater} order) to {@link FluidFlow}. Parity is
 * gated line for line against vanilla by {@code FluidParityCheck}.
 *
 * <p>Thread-safety: single tick-thread only. No synchronization.
 */
public final class TickLoop {

    /** Neighbor dispatch order: vanilla's 7-slot order (self handled separately). */
    static final Direction[] DISPATCH_ORDER = {
        Direction.DOWN, Direction.UP, Direction.NORTH,
        Direction.SOUTH, Direction.WEST, Direction.EAST,
    };

    /** Vanilla's dispatch fan-out order inside one updateNeighborsAt call. */
    static final Direction[] UPDATE_ORDER = {
        Direction.WEST, Direction.EAST, Direction.DOWN,
        Direction.UP, Direction.NORTH, Direction.SOUTH,
    };

    /** Vanilla block update flags used on the power-commit setBlock (flags=2). */
    public static final int SETBLOCK_FLAGS = 2;

    private final KestrelLevel level;
    private final SectionStore store;
    private final WireConnectionCache connectionCache = new WireConnectionCache();
    private final AcFlag acFlag = new AcFlag();
    private final EventHooks hooks = new EventHooks();
    private final Block wireBlock = Blocks.REDSTONE_WIRE;
    /**
     * G12: the exact vanilla redstone engine (Level.setBlock commit sequence,
     * CollectingNeighborUpdater, SignalGetter algebra, block neighborChanged
     * fan-out). Everything on the redstone path runs through it; the loop
     * keeps only the storage, scheduling and hook seams it needs.
     */
    private final dev.kestrel.redstone.VanillaRedstone redstone =
            new dev.kestrel.redstone.VanillaRedstone(new RedstoneHostImpl());
    /**
     * G12: live block entities (comparator output, container contents), the
     * {@code LevelChunk.blockEntities} stand-in. See {@link BlockEntityStore}.
     */
    private final BlockEntityStore blockEntities = new BlockEntityStore();

    private long gameTime;
    private long subTickOrder;
    private long setBlockCount;
    /**
     * H3 mutation journal: every position whose committed state changed
     * through this loop since the last {@link #drainBlockChanges}, in
     * first-change order. Both commit points record here ({@link #setBlock}
     * and the shape write in {@code runShapeUpdates}), so scheduled ticks,
     * redstone commits, shape side effects and player dig/place all reach
     * networking through one path. Generation and import write the store
     * directly and are published by their own revision path in ChunkSender.
     * Bounded by the number of distinct edited cells between drains.
     */
    private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet changedPositions =
            new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    /**
     * G30: the cells vanilla's {@code sendBlockUpdated} would report this
     * tick (writes with {@code UPDATE_CLIENTS}), grouped the way
     * {@code ChunkHolder} groups them for the broadcast packets.
     */
    private final ChangedBlocks changedBlocks;
    private long neighborDispatchCount;
    private long updatesDropped;
    /**
     * Level events kept for a gate, or null. Null on the live server, where
     * events go to {@link #levelEventSink} and nothing accumulates.
     */
    private List<LevelEvent> levelEvents;
    /** Delivery of {@code ServerLevel.levelEvent(null, ...)}; the network layer sets it. */
    private LevelEventSink levelEventSink;

    public record LevelEvent(int type, BlockPos pos, int data) {
    }

    /** Where level events go: {@code ServerLevel.levelEvent} with no source entity. */
    @FunctionalInterface
    public interface LevelEventSink {
        void levelEvent(int type, BlockPos pos, int data);

        /** A player-sourced event: every player but {@code exceptPlayer}. */
        default void levelEvent(java.util.@org.jspecify.annotations.Nullable UUID exceptPlayer, int type, BlockPos pos,
                int data) {
            levelEvent(type, pos, data);
        }
    }

    public void setLevelEventSink(LevelEventSink sink) {
        this.levelEventSink = sink;
    }

    /** Start keeping level events for {@link #dumpLevelEvents} (gates only). */
    public void captureLevelEvents() {
        if (levelEvents == null) {
            levelEvents = new ArrayList<>();
        }
    }

    public List<LevelEvent> dumpLevelEvents() {
        if (levelEvents == null) {
            throw new IllegalStateException("level events are not being captured");
        }
        return List.copyOf(levelEvents);
    }

    public void clearLevelEvents() {
        if (levelEvents != null) {
            levelEvents.clear();
        }
    }

    /**
     * Receives every committed state change, in commit order, while set. Only
     * {@link PostProcess} sets it, for the duration of one pass, so the pass
     * can hand its writes to the light engine the way {@code LevelChunk.setBlockState}
     * would have queued them. Null otherwise: no cost on any other path.
     */
    interface WriteObserver {
        void written(BlockPos pos, BlockState oldState, BlockState newState);
    }

    private WriteObserver writeObserver;

    /** Install (or clear, with null) the pass-scoped write observer. */
    void setWriteObserver(WriteObserver observer) {
        this.writeObserver = observer;
    }

    private void observeWrite(BlockPos pos, BlockState oldState, BlockState newState) {
        if (writeObserver != null) {
            writeObserver.written(pos.immutable(), oldState, newState);
        }
    }

    /** Scheduled block ticks: ordered by (triggerTick, priority, subTickOrder). */
    private final java.util.PriorityQueue<ScheduledEntry> scheduled =
            new java.util.PriorityQueue<>((a, b) -> {
                int c = Long.compare(a.triggerTick, b.triggerTick);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(a.priority.getValue(), b.priority.getValue());
                return c != 0 ? c : Long.compare(a.subTickOrder, b.subTickOrder);
            });
    /**
     * Dedupe index over the pending queue: one entry per (pos, block), the
     * FIRST one scheduled. Mirrors vanilla's {@code LevelChunkTicks.ticksPerPosition}
     * ({@code UNIQUE_TICK_HASH}): {@code schedule} only enqueues when the
     * key is absent, and {@code poll} (the collect step) removes the key.
     */
    private final Map<ScheduledKey, ScheduledEntry> scheduledIndex = new HashMap<>();
    /**
     * Ticks collected for this tick and not yet run: vanilla's
     * {@code LevelTicks.toRunThisTickSet}, which {@code willTickThisTick} reads.
     */
    private final java.util.Set<ScheduledKey> toRunThisTick = new java.util.HashSet<>();
    /**
     * Scheduled fluid ticks (G11): vanilla keeps a second {@code LevelTicks}
     * for fluids, drained after the block ticks with the same cap and the
     * same collect-then-run, first-schedule-wins discipline. The
     * subTickOrder counter is shared with block ticks, as vanilla's
     * {@code Level.nextSubTickCount} is.
     */
    private final java.util.PriorityQueue<FluidEntry> fluidScheduled =
            new java.util.PriorityQueue<>((a, b) -> {
                int c = Long.compare(a.triggerTick, b.triggerTick);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(a.priority.getValue(), b.priority.getValue());
                return c != 0 ? c : Long.compare(a.subTickOrder, b.subTickOrder);
            });
    private final Map<ScheduledKey, FluidEntry> fluidIndex = new HashMap<>();
    private final java.util.Set<ScheduledKey> fluidToRunThisTick = new java.util.HashSet<>();
    /** Water and lava flow (G11). */
    private final FluidFlow fluids;
    /** Vanilla's per-tick block-tick cap ({@code ServerLevel.tick}: 65536). */
    private static final int MAX_SCHEDULED_TICKS_PER_TICK = 65536;
    /** Maximum retained POWER trace lines to bound diagnostic memory growth. */
    private static final int MAX_POWER_TRACE_LINES = 1024;
    /** Whether diagnostic POWER tracing is enabled (defaults to false for production). */
    private boolean powerTraceEnabled = false;

    /** S14 slice 2 entity tracker, ticked in vanilla's chunkSource slot; null until wired. */
    private dev.kestrel.entity.EntityTracker entityTracker;

    /**
     * ServerChunkCache.tickChunks broadcasts dirty chunks immediately before
     * ChunkMap.tick runs the entity tracker. The live network layer installs
     * its block-publication callback here so packet order stays vanilla.
     */
    private Runnable beforeEntityTracker = () -> {};
    private Runnable afterEntityTick = () -> {};

    /** Wire the entity tracker (the network layer owns the observers). */
    public void setEntityTracker(dev.kestrel.entity.EntityTracker tracker) {
        this.entityTracker = tracker;
    }

    public void setBeforeEntityTracker(Runnable callback) {
        this.beforeEntityTracker = java.util.Objects.requireNonNull(callback);
    }

    public void setAfterEntityTick(Runnable callback) {
        this.afterEntityTick = java.util.Objects.requireNonNull(callback);
    }

    /**
     * G30 random ticks ({@code ServerChunkCache.tickChunks}' chunk half),
     * created on first use: a loop nobody gives players or asks for random
     * ticks never builds one and ticks exactly as before.
     */
    private RandomTicks randomTicks;

    public RandomTicks randomTicks() {
        if (randomTicks == null) {
            randomTicks = new RandomTicks(this);
        }
        return randomTicks;
    }

    /** S14 slice 3 entities, ticked in vanilla's entities slot; null until wired. */
    private dev.kestrel.entity.EntityWorld entityWorld;
    /**
     * {@code ServerChunkCache.hasActiveTickets}: whether a ticket keeps the
     * dimension active. Kestrel has no ticket system; the owner answers
     * (the live server: a player is connected). Default: always active.
     */
    private java.util.function.BooleanSupplier activeTickets = () -> true;
    /** {@code ServerLevel.emptyTime}: ticks since the dimension last had an active ticket. */
    private int emptyTime;

    /** Wire the entities of this level (the network layer owns the spawn sources). */
    public void setEntityWorld(dev.kestrel.entity.EntityWorld entityWorld) {
        this.entityWorld = entityWorld;
        redstone.setFallingTick(entityWorld::fallingBlockTick);
    }

    public dev.kestrel.entity.EntityWorld entityWorld() {
        return entityWorld;
    }

    public void setActiveTickets(java.util.function.BooleanSupplier activeTickets) {
        this.activeTickets = activeTickets;
    }

    public int emptyTime() {
        return emptyTime;
    }

    /** Reset the empty-time counter: ServerLevel.resetEmptyTime (:805-807). */
    public void resetEmptyTime() {
        this.emptyTime = 0;
    }

    /**
     * The world's block states as a vanilla {@code BlockGetter}, for the
     * entity collision walk.
     */
    public net.minecraft.world.level.BlockGetter blockGetter() {
        return loopGetter();
    }
    /** Per-tick POWER trace lines: tick,phase,wire_count,wires=x,y,z:power;... */
    private final List<String> powerTrace = new ArrayList<>();
    /** Wire positions tracked by the trace (discovered on first build). */
    private final List<BlockPos> tracedWires = new ArrayList<>();


    private static final class ScheduledEntry {
        final BlockPos pos;
        final Block block;
        final long triggerTick;
        final TickPriority priority;
        final long subTickOrder;

        ScheduledEntry(BlockPos pos, Block block, long triggerTick, TickPriority priority, long subTickOrder) {
            this.pos = pos;
            this.block = block;
            this.triggerTick = triggerTick;
            this.priority = priority;
            this.subTickOrder = subTickOrder;
        }
    }

    private static final class FluidEntry {
        final BlockPos pos;
        final net.minecraft.world.level.material.Fluid fluid;
        final long triggerTick;
        final TickPriority priority;
        final long subTickOrder;

        FluidEntry(BlockPos pos, net.minecraft.world.level.material.Fluid fluid, long triggerTick,
                TickPriority priority, long subTickOrder) {
            this.pos = pos;
            this.fluid = fluid;
            this.triggerTick = triggerTick;
            this.priority = priority;
            this.subTickOrder = subTickOrder;
        }
    }

    /** Dedupe key: (pos, tick type), type compared by identity (a Block or a Fluid). */
    private static final class ScheduledKey {
        final BlockPos pos;
        final Object block;

        ScheduledKey(BlockPos pos, Object block) {
            this.pos = pos;
            this.block = block;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ScheduledKey k)) {
                return false;
            }
            return pos.equals(k.pos) && block == k.block;
        }

        @Override
        public int hashCode() {
            return 31 * pos.hashCode() + System.identityHashCode(block);
        }
    }

    public TickLoop(KestrelLevel level) {
        this.level = level;
        this.store = level.store();
        this.fluids = new FluidFlow(this, level.dimension() == KestrelLevel.Dimension.NETHER);
        this.changedBlocks = new ChangedBlocks(store.getMinY(), store.getHeight());
    }

    /** Legacy shim: adopts the store's level when it already has one (tests migrate off this). */
    public TickLoop(SectionStore store) {
        this(store.owningLevel() != null
                ? store.owningLevel()
                : new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, store));
    }

    public KestrelLevel level() {
        return level;
    }

    public SectionStore store() {
        return store;
    }

    public AcFlag acFlag() {
        return acFlag;
    }

    /** Seam: the vanilla redstone engine driving this world. */
    public dev.kestrel.redstone.VanillaRedstone redstone() {
        return redstone;
    }

    /** Live block entities of this loop's world (G12 minimal store). */
    public BlockEntityStore blockEntities() {
        return blockEntities;
    }

    public SleeperRegistry sleepers() {
        return level.sleepers();
    }

    /**
     * Stage 0 hook seam (decided in {@code docs/EVENTS.md}): subscriber
     * bitset plus registration; nothing subscribes yet.
     */
    public EventHooks hooks() {
        return hooks;
    }

    public long gameTime() {
        return gameTime;
    }

    public long setBlockCount() {
        return setBlockCount;
    }

    public long neighborDispatchCount() {
        return neighborDispatchCount;
    }

    void resetNeighborDispatchCount() {
        neighborDispatchCount = 0;
    }

    public long updatesDropped() {
        return updatesDropped;
    }

    public List<String> powerTrace() {
        return powerTrace;
    }

    public boolean isPowerTraceEnabled() {
        return powerTraceEnabled;
    }

    public void setPowerTraceEnabled(boolean powerTraceEnabled) {
        this.powerTraceEnabled = powerTraceEnabled;
    }

    public void clearPowerTrace() {
        powerTrace.clear();
    }

    // ---- world access ----

    /** Read the state at a position (unset cells read as air). */
    public KestrelState getBlockState(KestrelPos pos) {
        return KestrelState.wrap(store.get(pos.toVanilla()));
    }

    /** Seam overload: vanilla-typed read for mechanics internals and tests. */
    public BlockState getBlockState(BlockPos pos) {
        return store.get(pos);
    }

    /**
     * Facade setBlock path: swap the stored state, count the write, run shape
     * updates on the position ({@code Level.setBlock} flags-2 semantics:
     * neighbor dispatch is the caller's job, shape updates are not), then
     * fire the {@code BlockChange} hook. A hook veto returns false: the
     * write is committed, downstream dispatch the caller would have caused
     * must not run.
     */
    public boolean setBlock(KestrelPos pos, KestrelState state, int flags) {
        return redstone.setBlock(pos.toVanilla(), state.unwrap(), flags);
    }

    /** Seam overload: vanilla-typed write for mechanics internals and tests. */
    public boolean setBlock(BlockPos pos, BlockState state, int flags) {
        return setBlock(KestrelPos.fromVanilla(pos), KestrelState.wrap(state), flags);
    }

    public boolean setBlock(BlockPos pos, BlockState state, int flags, int updateLimit) {
        return redstone.setBlock(pos, state, flags, updateLimit);
    }

    /**
     * G30: the cells {@code UPDATE_CLIENTS} writes reported since the last
     * broadcast, grouped like {@code ChunkHolder.changedBlocksPerSection}.
     * The network layer drains it once per tick where vanilla broadcasts.
     */
    public ChangedBlocks changedBlocks() {
        return changedBlocks;
    }

    /** True when committed writes are waiting in the H3 mutation journal. */
    public boolean hasBlockChanges() {
        return !changedPositions.isEmpty();
    }

    /**
     * Hand every journaled position ({@link BlockPos#asLong}) to the sink in
     * first-change order, then clear the journal. A position that changed
     * several times since the last drain appears once; the consumer reads
     * the current (final) state from the store.
     */
    public void drainBlockChanges(java.util.function.LongConsumer sink) {
        if (changedPositions.isEmpty()) {
            return;
        }
        long[] drained = changedPositions.toLongArray();
        changedPositions.clear();
        for (long p : drained) {
            sink.accept(p);
        }
    }

    /**
     * Shape updates after a setBlock: the position's own updateShape against
     * each neighbor direction, committed via updateOrDestroy semantics
     * (air result destroys, changed state re-sets with flags minus the drop
     * bit, {@code Block.java:223-242}).
     */
    // ---- redstone reads retained for the Alternate Current seam ----

    /**
     * Incoming wire signal: max neighbor wire power (same level, above
     * through conductors, below through non-conductors), minus 1, floored at
     * 0 ({@code RedstoneWireEvaluator.java:29-47}). Retained for the AC
     * topology build; the default path uses the engine's own transcription.
     */
    int getIncomingWireSignal(BlockPos pos) {
        int best = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos npos = pos.relative(dir);
            BlockState nstate = store.get(npos);
            best = Math.max(best, wirePowerAt(npos, nstate));
            BlockPos above = pos.above();
            BlockState aboveState = store.get(above);
            if (isConductor(nstate, npos) && !isConductor(aboveState, above)) {
                BlockPos aboveNeighbor = npos.above();
                best = Math.max(best, wirePowerAt(aboveNeighbor, store.get(aboveNeighbor)));
            } else if (!isConductor(nstate, npos)) {
                BlockPos belowNeighbor = npos.below();
                best = Math.max(best, wirePowerAt(belowNeighbor, store.get(belowNeighbor)));
            }
        }
        return Math.max(0, best - 1);
    }

    private int wirePowerAt(BlockPos pos, BlockState state) {
        return state.is(wireBlock) ? state.getValue(RedStoneWireBlock.POWER) : 0;
    }

    private boolean isConductor(BlockState state, BlockPos pos) {
        return state.isRedstoneConductor(loopGetter(), pos);
    }

    /** True when the wire at pos connects toward the given direction (R3 gating input). */
    boolean connectedToward(BlockPos pos, Direction dir) {
        net.minecraft.world.level.block.state.properties.RedstoneSide side = sideToward(pos, dir);
        return side != null && side.isConnected();
    }

    private RedstoneSide sideToward(BlockPos pos, Direction dir) {
        WireConnectionCache.Record r = connectionCache.recordFor(pos);
        RedstoneSide[] snap = connectionCache.snapshot(r);
        if (snap == null) {
            BlockState state = store.get(pos);
            if (!state.is(wireBlock)) {
                return RedstoneSide.NONE;
            }
            snap = new RedstoneSide[]{
                state.getValue(RedStoneWireBlock.NORTH),
                state.getValue(RedStoneWireBlock.EAST),
                state.getValue(RedStoneWireBlock.SOUTH),
                state.getValue(RedStoneWireBlock.WEST),
            };
            connectionCache.reportResolved(r, snap[0], snap[1], snap[2], snap[3]);
        }
        return switch (dir) {
            case NORTH -> snap[0];
            case EAST -> snap[1];
            case SOUTH -> snap[2];
            case WEST -> snap[3];
            default -> RedstoneSide.NONE;
        };
    }

    // ---- propagation entry: default path + AC flag seam ----

    /**
     * Default-path entry for one wire position: recompute; skip when
     * unchanged (outer {@code if (POWER != target)}); else commit via real
     * {@code RedStoneWireBlock.updatePowerStrength}. The AC flag is checked
     * ONCE per propagation entry, never per wire; with the flag off the call
     * lands in the exact vanilla engine, which recomputes and fans out
     * synchronously (vanilla's CollectingNeighborUpdater runs the cascade
     * inside the entry call, it does not defer it to a later drain).
     */
    /** Facade overload: Kestrel position on the public path. */
    public void updatePowerAt(KestrelPos pos) {
        updatePowerAt(pos.toVanilla());
    }

    /** Seam overload: vanilla-typed entry for dispatch internals and tests. */
    public void updatePowerAt(BlockPos pos) {
        BlockState state = store.get(pos);
        if (!state.is(wireBlock)) {
            return;
        }
        if (acFlag.enabled()) {
            propagateAc(pos);
            return;
        }
        redstone.updateWirePower(pos);
    }

    /**
     * AC flag seam: build the caller topology around the entry wire (current
     * powers, external power, offer/accept links over same-level connected
     * wires), run {@link AcWireNetwork#propagate}, commit settled finals via
     * real setBlock. One write per changed wire, then the same fan-out as
     * the default path.
     */
    void propagateAc(BlockPos entry) {
        Map<Integer, AcWireNetwork.WireInput> wires = new LinkedHashMap<>();
        Map<Integer, List<AcWireNetwork.Link>> links = new HashMap<>();
        Map<BlockPos, Integer> ids = new HashMap<>();
        List<BlockPos> order = new ArrayList<>();
        collectNetwork(entry, wires, links, ids, order);
        if (wires.isEmpty()) {
            return;
        }
        Set<Integer> roots = new HashSet<>();
        roots.add(ids.get(entry));
        AcWireNetwork.Result result = AcWireNetwork.propagate(
                wires, links, roots, acFlag.order());
        for (Map.Entry<Integer, Integer> e : result.finalPower.entrySet()) {
            BlockPos pos = order.get(e.getKey());
            BlockState state = store.get(pos);
            if (!state.is(wireBlock)) {
                continue;
            }
            int current = state.getValue(RedStoneWireBlock.POWER);
            if (current != e.getValue()) {
                if (setBlock(pos, state.setValue(RedStoneWireBlock.POWER, e.getValue()), SETBLOCK_FLAGS)) {
                    enqueueFanOut(pos);
                }
            }
        }
    }

    /** BFS over same-level connected wires from the entry (isolated wires excluded). */
    private void collectNetwork(BlockPos entry,
            Map<Integer, AcWireNetwork.WireInput> wires,
            Map<Integer, List<AcWireNetwork.Link>> links,
            Map<BlockPos, Integer> ids,
            List<BlockPos> order) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(entry);
        ids.put(entry, 0);
        order.add(entry);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos npos = pos.relative(dir);
                if (ids.containsKey(npos)) {
                    continue;
                }
                BlockState nstate = store.get(npos);
                if (!nstate.is(wireBlock)) {
                    continue;
                }
                if (!connectedToward(pos, dir)) {
                    continue;
                }
                ids.put(npos, order.size());
                order.add(npos);
                queue.add(npos);
            }
        }
        for (int i = 0; i < order.size(); i++) {
            BlockPos pos = order.get(i);
            BlockState state = store.get(pos);
            int current = state.getValue(RedStoneWireBlock.POWER);
            wires.put(i, new AcWireNetwork.WireInput(i, current, externalAt(pos)));
            List<AcWireNetwork.Link> ls = new ArrayList<>();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos npos = pos.relative(dir);
                Integer j = ids.get(npos);
                if (j == null) {
                    continue;
                }
                int di = acIndex(dir);
                boolean offer = connectedToward(pos, dir);
                boolean accept = connectedToward(npos, dir.getOpposite());
                ls.add(new AcWireNetwork.Link(j, di, offer, accept));
            }
            links.put(i, ls);
        }
    }

    /** External (non-wire) power sampled once per wire for the AC topology. */
    private int externalAt(BlockPos pos) {
        int best = 0;
        for (Direction dir : Direction.values()) {
            BlockPos npos = pos.relative(dir);
            BlockState nstate = store.get(npos);
            if (nstate.is(wireBlock)) {
                continue;
            }
            if (nstate.isSignalSource() || isConductor(nstate, npos)) {
                best = Math.max(best, nstate.getSignal(loopGetter(), npos, dir));
            }
            if (best >= 15) {
                return 15;
            }
        }
        return best;
    }

    private static int acIndex(Direction dir) {
        return switch (dir) {
            case WEST -> 0;
            case NORTH -> 1;
            case EAST -> 2;
            case SOUTH -> 3;
            case DOWN -> 4;
            case UP -> 5;
        };
    }

    // ---- neighbor dispatch (delegated to the exact vanilla engine) ----

    /**
     * Fan-out for a block that changed on the redstone path: the changed
     * position plus all 6 neighbors, exactly as
     * {@code DefaultRedstoneWireEvaluator.updatePowerStrength} does it.
     *
     * <p>The targets go through a {@link HashSet} and fire in that set's
     * iteration order, which is {@link BlockPos#hashCode} bucket order. That
     * is not a cosmetic detail: vanilla's own order here is the same HashSet
     * order, and it is what produces the locational, direction-dependent
     * update behaviour of MC-11193. An earlier version of this loop fired a
     * fixed direction order instead and diverged from vanilla.
     */
    void enqueueFanOut(BlockPos pos) {
        Set<BlockPos> targets = new HashSet<>();
        targets.add(pos);
        for (Direction dir : Direction.values()) {
            targets.add(pos.relative(dir));
        }
        for (BlockPos target : targets) {
            redstone.updateNeighborsAt(target, wireBlock);
        }
    }

    /**
     * Retained entry point for callers written against the old deferred
     * model. Vanilla's {@code CollectingNeighborUpdater} runs a cascade to
     * completion inside the call that started it, so by the time control
     * returns to a caller there is never anything left to drain and this is
     * a no-op. Kept so existing call sites keep reading as "settle now".
     */
    void drainDispatch() {
        // Intentionally empty: the engine drains cascades synchronously.
    }

    // ---- scheduled ticks ----

    /**
     * Facade schedule: Kestrel position plus tick target on the public path.
     * The target kind maps back to the vanilla block at the seam; OTHER has
     * no schedulable block and is ignored.
     */
    public void scheduleTick(KestrelPos pos, KestrelTickTarget target, int delay) {
        Block block = blockOf(target.kind());
        if (block == null) {
            return;
        }
        scheduleTick(pos.toVanilla(), block, delay, priorityOf(target.priority()));
    }

    /** Seam overload: vanilla-typed schedule for dispatch internals and tests. */
    public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {
        // Past-due schedules fire this tick (never in the past): vanilla's
        // queue only holds present/future entries, and the drain condition
        // (triggerTick <= gameTime) would fire a negative-delay entry on
        // the very next tick anyway. Clamping keeps the invariant explicit.
        if (delay < 0) {
            delay = 0;
        }
        // First schedule wins (H4): vanilla LevelChunkTicks.schedule only
        // enqueues when ticksPerPosition.add succeeds, so a duplicate for a
        // pending (pos, block) is dropped whatever its delay or priority.
        // A tick already collected for this tick is no longer pending, so
        // rescheduling it from its own body is admitted, as in vanilla.
        long order = subTickOrder++;
        admitBlockTick(pos, block, gameTime + delay, priority, order);
    }

    /**
     * {@code LevelChunkTicks.schedule}: enqueue a created tick unless one is
     * already pending for (pos, block). The sub-tick order was consumed by
     * the caller's {@code Level.createTick}, admitted or not.
     */
    private void admitBlockTick(BlockPos pos, Block block, long triggerTick, TickPriority priority, long order) {
        ScheduledKey key = new ScheduledKey(pos.immutable(), block);
        if (scheduledIndex.containsKey(key)) {
            return;
        }
        ScheduledEntry entry = new ScheduledEntry(pos.immutable(), block, triggerTick, priority, order);
        scheduledIndex.put(key, entry);
        scheduled.offer(entry);
    }

    /**
     * Stage 2 seam: immutable snapshot of the pending scheduled queue in
     * drain order ((triggerTick, priority, subTickOrder)). Each record carries the absolute trigger tick plus the
     * facade kind/priority pair, which is all the restore path needs: the
     * tick-thread subTickOrder counter is intentionally not preserved.
     */
    public record QueuedTick(KestrelPos pos, KestrelTickTarget.Kind kind,
            KestrelTickTarget.Priority priority, long triggerTick) {
    }

    /** Stage 2 seam: pending queue contents in drain order. */
    /**
     * Pending block tick with everything the G12 oracle records, including
     * the raw vanilla block and the sub-tick counter that {@link QueuedTick}
     * drops. Ordered by {@code ScheduledTick.DRAIN_ORDER}
     * (triggerTick, then priority, then subTickOrder).
     */
    public record PendingBlockTick(BlockPos pos, Block block, long triggerTick,
            TickPriority priority, long subTickOrder) {
    }

    /** Every pending block tick in vanilla drain order. */
    public List<PendingBlockTick> dumpBlockTicks() {
        List<ScheduledEntry> live = new ArrayList<>(scheduled);
        live.sort((a, b) -> {
            int c = Long.compare(a.triggerTick, b.triggerTick);
            if (c != 0) {
                return c;
            }
            c = a.priority.compareTo(b.priority);
            return c != 0 ? c : Long.compare(a.subTickOrder, b.subTickOrder);
        });
        List<PendingBlockTick> out = new ArrayList<>(live.size());
        for (ScheduledEntry e : live) {
            out.add(new PendingBlockTick(e.pos, e.block, e.triggerTick, e.priority, e.subTickOrder));
        }
        return out;
    }

    public List<QueuedTick> dumpQueue() {
        List<ScheduledEntry> live = new ArrayList<>(scheduled);
        live.sort((a, b) -> {
            int c = Long.compare(a.triggerTick, b.triggerTick);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.priority.getValue(), b.priority.getValue());
            return c != 0 ? c : Long.compare(a.subTickOrder, b.subTickOrder);
        });
        List<QueuedTick> out = new ArrayList<>(live.size());
        for (ScheduledEntry e : live) {
            out.add(new QueuedTick(KestrelPos.fromVanilla(e.pos),
                    KestrelTickTarget.kindOf(e.block),
                    KestrelTickTarget.priorityOf(e.priority),
                    e.triggerTick));
        }
        return out;
    }

    /**
     * Stage 2 seam: restore a queue entry with an absolute trigger tick.
     * Used by the persistence restore path so reload preserves due times
     * exactly (delays are re-based against the restored gameTime by the
     * caller: delay = triggerTick - gameTime).
     */
    public void restoreQueueEntry(KestrelPos pos, KestrelTickTarget.Kind kind,
            KestrelTickTarget.Priority priority, long triggerTick) {
        Block block = blockOf(kind);
        if (block == null) {
            return;
        }
        // Absolute trigger, same first-wins admission as scheduleTick (a
        // saved queue holds at most one entry per key, as vanilla's does).
        BlockPos p = pos.toVanilla().immutable();
        ScheduledKey key = new ScheduledKey(p, block);
        if (scheduledIndex.containsKey(key)) {
            return;
        }
        ScheduledEntry entry = new ScheduledEntry(
                p, block, triggerTick, KestrelTickTarget.vanillaPriority(priority), subTickOrder++);
        scheduledIndex.put(key, entry);
        scheduled.offer(entry);
    }

    /** Stage 2 seam: drop every pending queue entry. Restore calls this first. */
    public void clearQueue() {
        scheduled.clear();
        scheduledIndex.clear();
        toRunThisTick.clear();
        fluidScheduled.clear();
        fluidIndex.clear();
        fluidToRunThisTick.clear();
    }

    /** Stage 2 seam: restore the tick clock without running a tick. */
    public void setGameTime(long time) {
        this.gameTime = time;
    }

    /**
     * Seed the sub-tick counter ({@code Level.subTickCount}).
     *
     * <p>Vanilla's counter lives as long as the level does and is never
     * reset, so its absolute value at any moment is a function of every
     * schedule the world has ever made. Replaying a single captured scenario
     * on a fresh world therefore cannot reproduce it without also replaying
     * everything that ran before the capture. Parity harnesses use this to
     * align the origin; the differences between counter values, which are
     * what actually order a tick, stay a strict comparison.
     */
    public void setSubTickOrder(long order) {
        this.subTickOrder = order;
    }

    /**
     * True when a tick for (pos, target) was collected for this tick and
     * has not run yet: vanilla {@code LevelTicks.willTickThisTick}
     * ({@code toRunThisTickSet}). The tick currently running and ticks
     * already run this tick are excluded, as in vanilla.
     */
    public boolean willTickThisTick(KestrelPos pos, KestrelTickTarget target) {
        Block block = blockOf(target.kind());
        if (block == null) {
            return false;
        }
        return willTickThisTick(pos.toVanilla(), block);
    }

    /** Seam overload: vanilla-typed queue read for dispatch internals and tests. */
    public boolean willTickThisTick(BlockPos pos, Block block) {
        return toRunThisTick.contains(new ScheduledKey(pos, block));
    }

    /**
     * True when a tick for (pos, block) is pending in the queue (not yet
     * collected): vanilla {@code LevelTicks.hasScheduledTick}.
     */
    public boolean hasScheduledTick(BlockPos pos, Block block) {
        return scheduledIndex.containsKey(new ScheduledKey(pos, block));
    }

    /** Seam mapping: facade kind back to the schedulable vanilla block. */
    private static Block blockOf(KestrelTickTarget.Kind kind) {
        return switch (kind) {
            case REDSTONE_TORCH -> Blocks.REDSTONE_TORCH;
            case REPEATER -> Blocks.REPEATER;
            case OTHER -> null;
        };
    }

    /** Seam mapping: facade priority back to vanilla priority. */
    private static TickPriority priorityOf(KestrelTickTarget.Priority priority) {
        return KestrelTickTarget.vanillaPriority(priority);
    }

    // ---- scheduled fluid ticks (G11) ----

    /**
     * Schedule a fluid tick: {@code ScheduledTickAccess.scheduleTick(pos,
     * fluid, delay)}. Same admission as block ticks: the subTickOrder is
     * consumed first, a pending (pos, fluid) keeps its first schedule.
     */
    public void scheduleFluidTick(BlockPos pos, net.minecraft.world.level.material.Fluid fluid, int delay,
            TickPriority priority) {
        if (delay < 0) {
            delay = 0;
        }
        long order = subTickOrder++;
        admitFluidTick(pos, fluid, gameTime + delay, priority, order);
    }

    /** {@code LevelChunkTicks.schedule} for the fluid list: first schedule wins. */
    private void admitFluidTick(BlockPos pos, net.minecraft.world.level.material.Fluid fluid, long triggerTick,
            TickPriority priority, long order) {
        BlockPos p = pos.immutable();
        ScheduledKey key = new ScheduledKey(p, fluid);
        if (fluidIndex.containsKey(key)) {
            return;
        }
        FluidEntry entry = new FluidEntry(p, fluid, triggerTick, priority, order);
        fluidIndex.put(key, entry);
        fluidScheduled.offer(entry);
    }

    /** Vanilla {@code getFluidTicks().hasScheduledTick(pos, fluid)}. */
    public boolean hasScheduledFluidTick(BlockPos pos, net.minecraft.world.level.material.Fluid fluid) {
        return fluidIndex.containsKey(new ScheduledKey(pos, fluid));
    }

    /** Vanilla {@code getFluidTicks().willTickThisTick(pos, fluid)}. */
    public boolean willFluidTickThisTick(BlockPos pos, net.minecraft.world.level.material.Fluid fluid) {
        return fluidToRunThisTick.contains(new ScheduledKey(pos, fluid));
    }

    /** One pending fluid tick: absolute trigger, vanilla priority value. */
    public record QueuedFluidTick(KestrelPos pos, net.minecraft.world.level.material.Fluid fluid,
            int priority, long triggerTick) {
    }

    /** Pending fluid ticks in drain order ((triggerTick, priority, subTickOrder)). */
    public List<QueuedFluidTick> dumpFluidQueue() {
        List<FluidEntry> live = new ArrayList<>(fluidScheduled);
        live.sort(fluidScheduled.comparator());
        List<QueuedFluidTick> out = new ArrayList<>(live.size());
        for (FluidEntry e : live) {
            out.add(new QueuedFluidTick(KestrelPos.fromVanilla(e.pos), e.fluid,
                    e.priority.getValue(), e.triggerTick));
        }
        return out;
    }

    /** Restore a pending fluid tick at its absolute trigger (persistence path). */
    public void restoreFluidQueueEntry(KestrelPos pos, net.minecraft.world.level.material.Fluid fluid,
            int priority, long triggerTick) {
        BlockPos p = pos.toVanilla().immutable();
        ScheduledKey key = new ScheduledKey(p, fluid);
        if (fluidIndex.containsKey(key)) {
            return;
        }
        FluidEntry entry = new FluidEntry(p, fluid, triggerTick, TickPriority.byValue(priority), subTickOrder++);
        fluidIndex.put(key, entry);
        fluidScheduled.offer(entry);
    }

    /**
     * Seed the {@code Level.getRandom()} stand-in (lava's spread delay, the
     * post-processing pass's shape updates), for gates that must draw the
     * same numbers as a vanilla level.
     */
    public void setLevelRandom(net.minecraft.util.RandomSource random) {
        fluids.setRandom(random);
    }

    /** Fluid flow engine seam for same-package gates (random, gamerules). */
    FluidFlow fluidFlow() {
        return fluids;
    }

    /**
     * Fluid ticks due now, vanilla {@code LevelTicks.tick} discipline (see
     * {@link #drainScheduled}), each run through {@code tickFluid}. Fluid
     * ticks do not fire the {@code BlockTick} hook.
     */
    void drainFluidTicks() {
        List<FluidEntry> collected = new ArrayList<>();
        while (collected.size() < MAX_SCHEDULED_TICKS_PER_TICK) {
            FluidEntry entry = fluidScheduled.peek();
            if (entry == null || entry.triggerTick > gameTime) {
                break;
            }
            fluidScheduled.poll();
            ScheduledKey key = new ScheduledKey(entry.pos, entry.fluid);
            fluidIndex.remove(key);
            fluidToRunThisTick.add(key);
            collected.add(entry);
        }
        try {
            for (FluidEntry entry : collected) {
                fluidToRunThisTick.remove(new ScheduledKey(entry.pos, entry.fluid));
                fluids.tickFluid(entry.pos, entry.fluid);
            }
        } finally {
            fluidToRunThisTick.clear();
        }
    }

    /**
     * Run due scheduled ticks the way vanilla {@code LevelTicks.tick} does:
     * first COLLECT every entry with {@code triggerTick <= gameTime} (up to
     * the per-tick cap) in (triggerTick, priority, subTickOrder) order,
     * removing each from the pending index; then RUN the collected list in
     * that order. A tick scheduled while running, even with delay 0, joins
     * the queue for a later tick instead of this one, and
     * {@link #willTickThisTick} sees exactly the collected-not-yet-run set.
     */
    void drainScheduled() {
        List<ScheduledEntry> collected = new ArrayList<>();
        while (collected.size() < MAX_SCHEDULED_TICKS_PER_TICK) {
            ScheduledEntry entry = scheduled.peek();
            if (entry == null || entry.triggerTick > gameTime) {
                break;
            }
            scheduled.poll();
            ScheduledKey key = new ScheduledKey(entry.pos, entry.block);
            scheduledIndex.remove(key);
            toRunThisTick.add(key);
            collected.add(entry);
        }
        try {
            for (ScheduledEntry entry : collected) {
                toRunThisTick.remove(new ScheduledKey(entry.pos, entry.block));
                runScheduled(entry);
            }
        } finally {
            toRunThisTick.clear();
        }
    }

    /**
     * Run one due scheduled tick ({@code ServerLevel.tickBlock}): the block
     * must still be the one scheduled, then its vanilla tick body runs in
     * the engine. The {@code BlockTick} hook fires first: a veto consumes the entry
     * without running the body (no reschedule from the cancel itself).
     */
    private void runScheduled(ScheduledEntry entry) {
        BlockState state = store.get(entry.pos);
        if (state.getBlock() != entry.block) {
            return;
        }
        if (hooks.fireBlockTick(this, KestrelPos.fromVanilla(entry.pos),
                new KestrelTickTarget(KestrelTickTarget.kindOf(entry.block),
                        KestrelTickTarget.priorityOf(entry.priority)))) {
            return;
        }
        if (RandomTicks.runsVanillaScheduledTick(state)) {
            // G30: leaves run vanilla's own LeavesBlock.tick on the stand-in.
            randomTicks().scheduledTick(state, entry.pos);
            return;
        }
        // Vanilla Block.tick bodies (ServerLevel.tickBlock -> BlockState.tick),
        // through the engine so their writes and neighbor fan-out follow the
        // real Level.setBlock sequence. Blocks with no transcribed tick are
        // inert there, never a throw.
        redstone.tickBlock(entry.pos, state);
    }

    // ---- tick entry ----

    /**
     * One tick, in {@code ServerLevel.tick} phase order: advance the clock,
     * drain due block ticks, then fluid ticks, then the entity tracker, then
     * the block-event queue (Phase 6), then the entities (S14 slice 3), then
     * the block-entity pass (Phase 8: the SleepLoop walk and the moving
     * pistons), then the residual neighbor cascade, then trace POWER.
     *
     * <p>The engine's {@code handlingTick} flag is the transcription of
     * {@code ServerLevel.handlingTick}, which {@code PistonBaseBlock.checkIfExtend}
     * reads through {@code ServerLevel.isHandlingTick}. Vanilla raises it at
     * the top of {@code ServerLevel.tick} (ServerLevel.java:356) and clears it
     * right after {@code runBlockEvents} (ServerLevel.java:408), before
     * {@code tickBlockEntities} - so the flag covers the scheduled-tick and
     * block-event phases only, and the block-entity pass already sees it
     * false. That is the phase in which a settling moving piston can fire a
     * neighbour update into another piston, so the ordering is load-bearing:
     * with the flag still up, such a check picks the drop event instead of the
     * contract event.
     */
    public void tick() {
        // Vanilla ServerLevel.tick advances the clock (tickTime) BEFORE it
        // drains blockTicks then fluidTicks, so a schedule made between ticks
        // with delay d runs on the d-th following tick.
        gameTime++;
        redstone.setHandlingTick(true);
        try {
            drainScheduled();
            drainFluidTicks();
            // ServerChunkCache.tickChunks (ServerChunkCache.java:374-415):
            // the random ticks run before the block publication and the
            // entity tracker (G30).
            if (randomTicks != null) {
                randomTicks.tick();
            }
            // ServerLevel.java:402 getChunkSource().tick -> ServerChunkCache
            // .tick:333 chunkMap.tick(): the entity tracker runs after block
            // and fluid ticks and before block events and entity ticking.
            beforeEntityTracker.run();
            if (entityTracker != null) {
                entityTracker.tick();
            }
            // Phase 6: ServerLevel.runBlockEvents. Runs before the block
            // entities, so a placeholder a piston creates here is ticked by
            // this same tick's Phase 8, as vanilla's ticker registration is.
            redstone.runBlockEvents();
            // ServerLevel.java:408: the flag comes down with runBlockEvents,
            // not with the tick. The finally clears it again as a safety net
            // if a phase below throws.
            redstone.setHandlingTick(false);
            // ServerLevel.java:409-453: the empty-time clock, then the
            // entity phase and block-entity pass inside "emptyTime < 300",
            // after the block events and before entity management.
            if (activeTickets.getAsBoolean()) {
                resetEmptyTime();
            }
            emptyTime++;
            if (emptyTime < 300) {
                if (entityWorld != null) {
                    entityWorld.tickEntities();
                }
                afterEntityTick.run();
                level.sleepers().beginPass();
                for (long id : level.sleepers().walkOrder()) {
                    sleepTick(id);
                }
                tickMovingPistons();
            }
        } finally {
            redstone.setHandlingTick(false);
        }
        drainDispatch();
        if (powerTraceEnabled) {
            tracePower();
        }
    }

    /**
     * Phase 8, the block entities this loop owns: every moving piston
     * placeholder advances one half-step
     * ({@code PistonMovingBlockEntity.tick}), or settles on its third tick.
     * The list is a snapshot and every entry is re-checked, because settling
     * one placeholder can drop another.
     */
    private void tickMovingPistons() {
        for (dev.kestrel.redstone.MovingPiston movingPiston : blockEntities.movingPistonsInTickOrder()) {
            if (!movingPiston.isRemoved()) {
                movingPiston.tick(redstone);
            }
        }
    }

    /**
     * Block-entity tick body hook: ids registered in the sleeper registry get
     * a sleep evaluation after their (currently no-op) body. Real per-type
     * bodies arrive with the block-entity owners; the discipline (walk awake
     * in order, reportIdle post-tick) is what this loop pins.
     */
    private void sleepTick(long id) {
        level.sleepers().reportIdle(id, true);
    }

    /** Register a block-entity id with its sleep-set type (level owns the record). */
    public void registerBlockEntity(BlockPos pos, SleeperRegistry.Type type) {
        level.registerBlockEntity(KestrelPos.fromVanilla(pos), type);
    }

    /** Facade overload: Kestrel types on the public path. */
    public void registerBlockEntity(KestrelPos pos, SleeperRegistry.Type type) {
        level.registerBlockEntity(pos, type);
    }

    /** Wake hook fan-in (production: called from the mutation paths). */
    public void wakeBlockEntity(BlockPos pos, SleeperRegistry.WakeSignal signal) {
        level.wakeBlockEntity(KestrelPos.fromVanilla(pos), signal);
    }

    /** Facade overload: Kestrel types on the public path. */
    public void wakeBlockEntity(KestrelPos pos, SleeperRegistry.WakeSignal signal) {
        level.wakeBlockEntity(pos, signal);
    }

    // ---- POWER trace ----

    /** Discover traced wire positions (called after the corpus build). Enables power trace. */
    public void discoverWires() {
        this.powerTraceEnabled = true;
        tracedWires.clear();
        for (BlockPos pos : store.writtenPositions()) {
            if (store.get(pos).is(wireBlock)) {
                tracedWires.add(pos);
            }
        }
        tracedWires.sort((a, b) -> {
            int c = Integer.compare(a.getX(), b.getX());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.getY(), b.getY());
            return c != 0 ? c : Integer.compare(a.getZ(), b.getZ());
        });
    }

    /** Per-tick POWER line in power-trace.log format (exit phase). */
    void tracePower() {
        if (!powerTraceEnabled || tracedWires.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        // Label = the tick's pre-advance time, the numbering the trace has
        // always used (tick() now advances the clock first).
        sb.append(gameTime - 1).append(",exit,").append(tracedWires.size()).append(',');
        for (BlockPos pos : tracedWires) {
            BlockState state = store.get(pos);
            int power = state.is(wireBlock) ? state.getValue(RedStoneWireBlock.POWER) : -1;
            sb.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ())
                    .append(':').append(power).append(';');
        }
        while (powerTrace.size() >= MAX_POWER_TRACE_LINES) {
            powerTrace.remove(0);
        }
        powerTrace.add(sb.toString());
    }

    // ---- loop-owned BlockGetter access ----

    private LoopGetter loopGetterInstance;

    LoopGetter loopGetter() {
        if (loopGetterInstance == null) {
            loopGetterInstance = new LoopGetter();
        }
        return loopGetterInstance;
    }

    /**
     * Minimal BlockGetter over the store for vanilla signal/conductor reads.
     * Only getBlockState/getFluidState/getHeight/getMinY are served; vanilla
     * signal reads touch nothing else on this path.
     */
    final class LoopGetter implements net.minecraft.world.level.BlockGetter {
        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return blockEntities.get(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return store.get(pos);
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return store.getHeight();
        }

        @Override
        public int getMinY() {
            return store.getMinY();
        }
    }

    // ---- minimal proxies for vanilla updateShape plumbing ----

    /**
     * LevelReader over the store (same serving rule as
     * {@code NeighborStateResolver.asLevelReader}: getBlockState/getFluidState
     * served, everything else vanilla-tolerated defaults). Reuses the inner
     * {@link LoopGetter} for state reads.
     */
    private net.minecraft.world.level.LevelReader levelReaderInstance;

    net.minecraft.world.level.LevelReader levelReader() {
        if (levelReaderInstance == null) {
            LoopGetter getter = loopGetter();
            levelReaderInstance = (net.minecraft.world.level.LevelReader)
                    java.lang.reflect.Proxy.newProxyInstance(
                            TickLoop.class.getClassLoader(),
                            new Class<?>[]{net.minecraft.world.level.LevelReader.class},
                            (proxy, method, args) -> {
                                String name = method.getName();
                                if (name.equals("getBlockState") && args.length == 1) {
                                    return getter.getBlockState((BlockPos) args[0]);
                                }
                                if (name.equals("getFluidState") && args.length == 1) {
                                    return getter.getFluidState((BlockPos) args[0]);
                                }
                                if (name.equals("getBlockEntity") && args.length == 1) {
                                    return getter.getBlockEntity((BlockPos) args[0]);
                                }
                                if (name.equals("getBlockEntity") && args.length >= 1) {
                                    return null;
                                }
                                if (name.equals("getHeight") || name.equals("getMinY")) {
                                    return name.equals("getMinY") ? getter.getMinY() : getter.getHeight();
                                }
                                if (name.equals("isClientSide")) {
                                    return false;
                                }
                                if (name.equals("toString")) {
                                    return "TickLoopLevelReader";
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
        return levelReaderInstance;
    }

    /**
     * The level's game rules ({@code ServerLevel.getGameRules}), vanilla's own
     * {@code GameRules} at its defaults. Kestrel does not load them from
     * {@code level.dat} or change them by command yet; readers that vanilla
     * gates on a rule read it here, so the gate can flip it.
     */
    private net.minecraft.world.level.gamerules.GameRules gameRules;

    public net.minecraft.world.level.gamerules.GameRules gameRules() {
        if (gameRules == null) {
            gameRules = new net.minecraft.world.level.gamerules.GameRules(
                    net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
        }
        return gameRules;
    }

    public net.minecraft.world.level.LevelReader entityLevelReader() { return levelReader(); }
    public net.minecraft.util.RandomSource levelRandom() { return fluids.random(); }
    /** {@code ServerLevel.levelEvent(null, type, pos, data)}: captured for a gate if asked, and delivered. */
    public void emitLevelEvent(int type, BlockPos pos, int data) {
        emitLevelEvent(null, type, pos, data);
    }

    /** {@code ServerLevel.levelEvent(source, ...)}: a player source is left out of the delivery. */
    public void emitLevelEvent(java.util.@org.jspecify.annotations.Nullable UUID exceptPlayer, int type, BlockPos pos,
            int data) {
        if (levelEvents != null) {
            levelEvents.add(new LevelEvent(type, pos.immutable(), data));
        }
        if (levelEventSink != null) {
            if (exceptPlayer == null) {
                levelEventSink.levelEvent(type, pos.immutable(), data);
            } else {
                levelEventSink.levelEvent(exceptPlayer, type, pos.immutable(), data);
            }
        }
    }
    public void setDetailedWriteListener(dev.kestrel.redstone.VanillaRedstone.WriteListener listener) {
        redstone.setWriteListener(listener);
    }

    /**
     * ScheduledTickAccess that keeps FLUID schedules (into the fluid queue,
     * {@code createTick} default priority NORMAL unless given) and drops
     * block schedules: the fluid part of a vanilla {@code updateShape} on a
     * waterlogged neighbor.
     */
    private net.minecraft.world.level.ScheduledTickAccess fluidTicksInstance;

    net.minecraft.world.level.ScheduledTickAccess fluidCapturingTicks() {
        if (fluidTicksInstance == null) {
            fluidTicksInstance = (net.minecraft.world.level.ScheduledTickAccess)
                    java.lang.reflect.Proxy.newProxyInstance(
                            TickLoop.class.getClassLoader(),
                            new Class<?>[]{net.minecraft.world.level.ScheduledTickAccess.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("scheduleTick") && args != null
                                        && args.length >= 3
                                        && args[1] instanceof net.minecraft.world.level.material.Fluid f) {
                                    TickPriority pr = args.length == 4 ? (TickPriority) args[3] : TickPriority.NORMAL;
                                    scheduleFluidTick((BlockPos) args[0], f, (Integer) args[2], pr);
                                    return null;
                                }
                                if (method.getName().equals("getBlockTicks")
                                        || method.getName().equals("getFluidTicks")) {
                                    // Never null: ObserverBlock.startSignal queries the
                                    // block list from updateShape.
                                    return net.minecraft.world.ticks.BlackholeTickAccess.emptyLevelList();
                                }
                                if (method.getName().equals("scheduleTick")) {
                                    // Block schedule from a non-modeled block: consumes the
                                    // shared subTickOrder as vanilla's createTick would.
                                    subTickOrder++;
                                    return null;
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
        return fluidTicksInstance;
    }

    // ---- redstone engine seam ----

    /**
     * {@link dev.kestrel.redstone.RedstoneHost} over this loop: storage, the
     * mutation journal, the block-tick queue and the plugin hooks. Keeps the
     * engine a pure vanilla transcription with no loop internals inside it.
     */
    private final class RedstoneHostImpl implements dev.kestrel.redstone.RedstoneHost {
        @Override
        public BlockState getBlockState(BlockPos pos) {
            return store.get(pos);
        }

        @Override
        public BlockState rawSet(BlockPos pos, BlockState state) {
            BlockState old = store.set(pos, state);
            if (old != state) {
                setBlockCount++;
                changedPositions.add(pos.asLong());
                observeWrite(pos, old, state);
            }
            return old;
        }

        @Override
        public void blockUpdated(BlockPos pos) {
            changedBlocks.blockChanged(pos);
        }

        @Override
        public boolean fireBlockChange(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
            return hooks.fireBlockChange(TickLoop.this, KestrelPos.fromVanilla(pos), oldState, newState, flags);
        }

        @Override
        public void countNeighborDispatch() {
            neighborDispatchCount++;
        }

        @Override
        public void countUpdateDropped() {
            updatesDropped++;
        }

        @Override
        public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {
            TickLoop.this.scheduleTick(pos, block, delay, priority);
        }

        @Override
        public boolean willTickThisTick(BlockPos pos, Block block) {
            return TickLoop.this.willTickThisTick(pos, block);
        }

        @Override
        public net.minecraft.world.level.LevelReader levelReader() {
            return redstoneLevelReader();
        }

        @Override
        public net.minecraft.world.level.ScheduledTickAccess tickAccess() {
            return loopTicks();
        }

        @Override
        public net.minecraft.util.RandomSource random() {
            return fluids.random();
        }

        @Override
        public void afterSetBlock(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
            connectionCache.invalidate(pos);
            for (Direction direction : Direction.values()) {
                connectionCache.invalidate(pos.relative(direction));
            }
            if ((flags & 512) == 0) {
                fluids.onPlace(pos, newState);
                if (newState.getBlock() instanceof net.minecraft.world.level.block.FallingBlock falling) {
                    scheduleTick(pos, newState.getBlock(), fallingBlockDelay(falling), TickPriority.NORMAL);
                }
            }
        }

        private int fallingBlockDelay(net.minecraft.world.level.block.FallingBlock falling) {
            try {
                java.lang.reflect.Method method = falling.getClass().getDeclaredMethod("getDelayAfterPlace");
                method.setAccessible(true);
                return (int) method.invoke(falling);
            } catch (NoSuchMethodException e) {
                try {
                    java.lang.reflect.Method method = net.minecraft.world.level.block.FallingBlock.class
                            .getDeclaredMethod("getDelayAfterPlace");
                    method.setAccessible(true);
                    return (int) method.invoke(falling);
                } catch (ReflectiveOperationException nested) {
                    throw new IllegalStateException("cannot read falling-block placement delay", nested);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot read falling-block placement delay", e);
            }
        }

        @Override
        public void neighborChanged(BlockPos pos, BlockState state) {
            fluids.neighborChanged(pos, state);
        }

        @Override
        public void shapeChanged(BlockPos pos, BlockState state, Direction direction, BlockState neighborState) {
            fluids.shapeChanged(pos, state, direction, neighborState);
        }

        @Override
        public boolean hasScheduledTick(BlockPos pos, Block block) {
            return TickLoop.this.hasScheduledTick(pos, block);
        }

        @Override
        public long gameTime() {
            return gameTime;
        }

        @Override
        public void levelEvent(int type, BlockPos pos, int data) {
            emitLevelEvent(type, pos, data);
        }

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return blockEntities.get(pos);
        }

        @Override
        public dev.kestrel.redstone.MovingPiston getMovingPiston(BlockPos pos) {
            return blockEntities.movingPiston(pos);
        }

        @Override
        public void setMovingPiston(dev.kestrel.redstone.MovingPiston movingPiston) {
            blockEntities.putMovingPiston(movingPiston);
        }

        @Override
        public void removeMovingPiston(BlockPos pos) {
            blockEntities.removeMovingPiston(pos);
        }

        @Override
        public int minBuildY() {
            return store.getMinY();
        }

        @Override
        public int maxBuildY() {
            return store.getMinY() + store.getHeight() - 1;
        }

        @Override
        public void blockEntityBeforeRemoval(BlockPos pos, BlockState oldState, BlockState newState) {
            blockEntities.beforeRemovalEffects(pos, oldState, newState);
        }

        @Override
        public void blockEntityAfterPlace(BlockPos pos, BlockState newState) {
            blockEntities.afterPlace(pos, newState);
        }
    }

    /**
     * The loop's real {@code ScheduledTickAccess}, the {@code ServerLevel}
     * half vanilla {@code updateShape} sees: {@code createTick} is
     * {@code Level.createTick} (gameTime + delay, next sub-tick order) and the
     * two lists are this loop's block and fluid queues, with vanilla's
     * first-schedule-wins admission and {@code toRunThisTick} reads.
     */
    private net.minecraft.world.level.ScheduledTickAccess loopTicksInstance;

    net.minecraft.world.level.ScheduledTickAccess loopTicks() {
        if (loopTicksInstance == null) {
            loopTicksInstance = new LoopTickAccess();
        }
        return loopTicksInstance;
    }

    private final class LoopTickAccess implements net.minecraft.world.level.ScheduledTickAccess {
        private final net.minecraft.world.ticks.LevelTickAccess<Block> blockTicks =
                new net.minecraft.world.ticks.LevelTickAccess<>() {
                    @Override
                    public void schedule(net.minecraft.world.ticks.ScheduledTick<Block> tick) {
                        admitBlockTick(tick.pos(), tick.type(), tick.triggerTick(), tick.priority(),
                                tick.subTickOrder());
                    }

                    @Override
                    public boolean hasScheduledTick(BlockPos pos, Block type) {
                        return TickLoop.this.hasScheduledTick(pos, type);
                    }

                    @Override
                    public boolean willTickThisTick(BlockPos pos, Block type) {
                        return TickLoop.this.willTickThisTick(pos, type);
                    }

                    @Override
                    public int count() {
                        return scheduled.size();
                    }
                };
        private final net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.material.Fluid> fluidTicks =
                new net.minecraft.world.ticks.LevelTickAccess<>() {
                    @Override
                    public void schedule(
                            net.minecraft.world.ticks.ScheduledTick<net.minecraft.world.level.material.Fluid> tick) {
                        admitFluidTick(tick.pos(), tick.type(), tick.triggerTick(), tick.priority(),
                                tick.subTickOrder());
                    }

                    @Override
                    public boolean hasScheduledTick(BlockPos pos, net.minecraft.world.level.material.Fluid type) {
                        return hasScheduledFluidTick(pos, type);
                    }

                    @Override
                    public boolean willTickThisTick(BlockPos pos, net.minecraft.world.level.material.Fluid type) {
                        return willFluidTickThisTick(pos, type);
                    }

                    @Override
                    public int count() {
                        return fluidScheduled.size();
                    }
                };

        /** {@code Level.createTick(pos, type, delay, priority)}. */
        @Override
        public <T> net.minecraft.world.ticks.ScheduledTick<T> createTick(BlockPos pos, T type, int tickDelay,
                TickPriority priority) {
            return new net.minecraft.world.ticks.ScheduledTick<>(type, pos, gameTime + tickDelay, priority,
                    subTickOrder++);
        }

        /** {@code Level.createTick(pos, type, delay)}: NORMAL priority. */
        @Override
        public <T> net.minecraft.world.ticks.ScheduledTick<T> createTick(BlockPos pos, T type, int tickDelay) {
            return createTick(pos, type, tickDelay, TickPriority.NORMAL);
        }

        @Override
        public net.minecraft.world.ticks.LevelTickAccess<Block> getBlockTicks() {
            return blockTicks;
        }

        @Override
        public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
            return fluidTicks;
        }
    }

    /**
     * LevelReader proxy for the redstone path.
     *
     * <p>Distinct from {@link #levelReader()} on purpose. That one answers
     * only state reads and returns zero/false for everything else, which is
     * fine for fluids but silently wrong here: {@code LevelReader} extends
     * {@link net.minecraft.world.level.SignalGetter}, and a JDK proxy does
     * not run a default method unless the handler says so. Under the plain
     * proxy {@code RepeaterBlock.updateShape} would resolve every locking
     * query to "no side signal" and quietly unlock every repeater. Here the
     * SignalGetter half is forwarded to the engine, which answers off the
     * same store with the same shouldSignal gate.
     */
    private net.minecraft.world.level.LevelReader redstoneLevelReaderInstance;

    /** The light reads the shape-update reader hands to the random tick level (G30). */
    private static final java.util.Set<String> LIGHT_READS = java.util.Set.of("getLightEngine",
            "getBrightness", "getRawBrightness", "getMaxLocalRawBrightness", "canSeeSky", "getSkyDarken");

    net.minecraft.world.level.LevelReader redstoneLevelReader() {
        if (redstoneLevelReaderInstance == null) {
            LoopGetter getter = loopGetter();
            redstoneLevelReaderInstance = (net.minecraft.world.level.LevelReader)
                    java.lang.reflect.Proxy.newProxyInstance(
                            TickLoop.class.getClassLoader(),
                            new Class<?>[]{net.minecraft.world.level.LevelReader.class},
                            (proxy, method, args) -> {
                                if (method.getDeclaringClass()
                                        == net.minecraft.world.level.SignalGetter.class) {
                                    return method.invoke(redstone, args);
                                }
                                String name = method.getName();
                                if (name.equals("getBlockState") && args.length == 1) {
                                    return getter.getBlockState((BlockPos) args[0]);
                                }
                                if (name.equals("getFluidState") && args.length == 1) {
                                    return getter.getFluidState((BlockPos) args[0]);
                                }
                                if (name.equals("getBlockEntity") && args.length == 1) {
                                    return getter.getBlockEntity((BlockPos) args[0]);
                                }
                                if (name.equals("getBlockEntity") && args.length >= 1) {
                                    return null;
                                }
                                if (name.equals("getHeight") && (args == null || args.length == 0)) {
                                    return getter.getHeight();
                                }
                                if (name.equals("getMinY")) {
                                    return getter.getMinY();
                                }
                                if (name.equals("isClientSide")) {
                                    return false;
                                }
                                if (name.equals("getRandom")) {
                                    return net.minecraft.util.RandomSource.create(0);
                                }
                                // G30: once the world has random ticks, their environment
                                // answers light (CropBlock.canSurvive reads brightness in
                                // updateShape); before that these reads stay as they were.
                                if (randomTicks != null && LIGHT_READS.contains(name)) {
                                    return method.invoke(randomTicks.level(), args);
                                }
                                if (name.equals("toString")) {
                                    return "TickLoopRedstoneLevelReader";
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
        return redstoneLevelReaderInstance;
    }

    /** No-op ScheduledTickAccess: fixture states are dry, ticks are swallowed. */
    private net.minecraft.world.level.ScheduledTickAccess noOpTicksInstance;

    net.minecraft.world.level.ScheduledTickAccess noOpTicks() {
        if (noOpTicksInstance == null) {
            noOpTicksInstance = (net.minecraft.world.level.ScheduledTickAccess)
                    java.lang.reflect.Proxy.newProxyInstance(
                            TickLoop.class.getClassLoader(),
                            new Class<?>[]{net.minecraft.world.level.ScheduledTickAccess.class},
                            (proxy, method, args) -> {
                                // getBlockTicks/getFluidTicks: vanilla's own empty
                                // lists, never null, so an updateShape that queries
                                // the queue (ObserverBlock.startSignal) cannot NPE.
                                if (method.getName().equals("getBlockTicks")
                                        || method.getName().equals("getFluidTicks")) {
                                    return net.minecraft.world.ticks.BlackholeTickAccess.emptyLevelList();
                                }
                                if (method.getName().equals("scheduleTick")) {
                                    return null;
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
        return noOpTicksInstance;
    }
}
