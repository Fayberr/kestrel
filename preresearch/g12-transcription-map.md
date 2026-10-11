# Roadmap Item G12: Redstone Simulation Transcription Map

Status: TRANSCRIPTION MAP grounded in decompiled vanilla 26.2 server jar (`server-26.2.jar`) decompiled via Vineflower.
Target: Method-by-method transcription map and implementation checklist for roadmap item G12 in Kestrel.
Scope: No Java changes, no builds, no servers. Pure transcription map, code checklist, and architectural gap analysis.
Companion document: `preresearch/g12-redstone-preresearch.md` (architectural design, phase order, and 27 oracle test scenarios).

> Status update (2026-09-25, main = `08fe226`). The map has been executed. Slices
> 1 to 5 landed and merged on 2026-09-25 (`024ed43` slice 1, `3f0f7a3` slices 2
> to 4, `db4aff8` slice 5, `8091127` the two coverage-gap fixtures), and
> `RedstoneParityCheck` reports 29/29 exact against the 29 fixtures in
> `harness/corpus/redstone/oracle/`. Section 6 ("Order of work") is therefore
> history, not a plan.
>
> Every "Kestrel today" cell in the tables below was written against the
> pre-G12 loop, so a "none" there means "this was still missing when the map
> was written", not "this is missing now". The engine that filled them is
> `core/src/vanilla/java/dev/kestrel/redstone/` (`WireLogic`, `DiodeLogic`,
> `PistonLogic`, `MovingPiston`, `BlockLogic`, `VanillaRedstone`, `RedstoneHost`,
> `AnalogOutput`, plus the Alternate Current set), driven from
> `dev.kestrel.loop.TickLoop`; `BlockLogic`'s class javadoc is the current
> coverage list, and the fixtures named per slice in section 6 are the gates
> that now pass.
>
> Unbuilt: pressure plates and the section 1.3 secondary components of the
> companion document (target block, daylight detector, tripwire and hook, note
> block, doors, trapdoors, fence gates, dispenser, dropper, TNT, rails; most
> need entities or containers, S14, G17, G28).

> Review note (2026-09-25): method names and files were checked mechanically against vanilla-src-26.2. About 86% of line ranges are exact; the rest are off by 1 or 2 lines (usually the closing brace). Locate methods by signature, not by line number. Kestrel-side citations are approximate for the same reason.

---

## 1. Shared Redstone Infrastructure

Vanilla redstone relies on shared infrastructure across world signal querying, update propagation batching, 3D orientation tracking, deferred block event queues, and scheduled tick prioritization.

### 1.1 SignalGetter (`net/minecraft/world/level/SignalGetter.java`)

`SignalGetter` is the foundation interface (extending `BlockGetter`) that defines strong power (`getDirectSignal`), weak power (`getSignal`), and conductor energization rules.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `default int getDirectSignal(final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/SignalGetter.java:13-15` | Returns strong direct power emitted by block at pos in given direction | `BlockGetter.getBlockState(pos)`, `BlockState.getDirectSignal(this, pos, direction)` | `none` (Kestrel only queries weak signal via `TickLoop.java:488`) |
| `default int getDirectSignalTo(final BlockPos pos)` | `net/minecraft/world/level/SignalGetter.java:17-46` | Checks 6 adjacent neighbors (DOWN, UP, NORTH, SOUTH, WEST, EAST) for strong direct signal into pos, exiting early on 15 | `this.getDirectSignal(pos.relative(...), ...)`, `BlockPos.below`, `above`, `north`, `south`, `west`, `east` | `none` (conductor strong-power passthrough is completely missing in Kestrel) |
| `default int getControlInputSignal(final BlockPos pos, final Direction direction, final boolean onlyDiodes)` | `net/minecraft/world/level/SignalGetter.java:48-59` | Evaluates control input from a side neighbor: diode output if onlyDiodes is true, redstone block (15), wire power, or signal source direct signal | `BlockGetter.getBlockState(pos)`, `DiodeBlock.isDiode`, `BlockState.is`, `BlockState.getValue`, `BlockState.isSignalSource`, `this.getDirectSignal` | `none` (Kestrel has ad-hoc `repeaterInput` in `TickLoop.java:835-851`, but not this general method) |
| `default boolean hasSignal(final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/SignalGetter.java:61-63` | Returns true if weak signal at pos in given direction exceeds 0 | `this.getSignal(pos, direction)` | `none` (partial: `TickLoop.java:820-823` `hasSignalBelow` checks DOWN only) |
| `default int getSignal(final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/SignalGetter.java:65-69` | Queries weak power from block, returning max of weak signal and strong signal into conductor if block is a redstone conductor | `BlockGetter.getBlockState(pos)`, `BlockState.getSignal(this, pos, direction)`, `BlockState.isRedstoneConductor(this, pos)`, `this.getDirectSignalTo(pos)` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:484-489 (signalOf)` (partial: calls `BlockState.getSignal`, misses conductor check and `getDirectSignalTo`) |
| `default int getBestOwnOrNeighbourSignal(final BlockPos pos)` | `net/minecraft/world/level/SignalGetter.java:71-74` | Returns max of best neighbor signal and own signal if block is a signal source | `this.getBestNeighborSignal(pos)`, `BlockState.isSignalSource`, `BlockState.getOwnSignal(this, pos)` | `none` |
| `default boolean hasNeighborSignal(final BlockPos blockPos)` | `net/minecraft/world/level/SignalGetter.java:76-88` | Returns true if any of 6 adjacent neighbors emit weak signal into pos | `this.getSignal(pos.relative(...), ...)` | `none` |
| `default int getBestNeighborSignal(final BlockPos pos)` | `net/minecraft/world/level/SignalGetter.java:90-105` | Iterates all 6 directions (DIRECTIONS order) and returns highest weak signal (0-15) | `this.getSignal(pos.relative(direction), direction)` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:456-474 (getBlockSignal)` (partial: iterates 6 directions, but delegates to incomplete `signalOf`) |

---

### 1.2 Level and ServerLevel Redstone Signals and Updates (`net/minecraft/world/level/Level.java`, `net/minecraft/server/level/ServerLevel.java`)

`Level` provides abstract hooks for neighbor dispatch, and `ServerLevel` binds them to `NeighborUpdater`.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public void updateNeighborsAt(final BlockPos pos, final Block sourceBlock)` | `net/minecraft/server/level/ServerLevel.java:1167-1169` | Initiates 6-way neighbor update around pos, passing initial orientation if redstone experiments active | `this.updateNeighborsAt(pos, sourceBlock, orientation)`, `ExperimentalRedstoneUtils.initialOrientation` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:794-818 (updateNeighborsAt)` (partial: hardcoded to wire/lever/torch/repeater) |
| `public void updateNeighborsAt(final BlockPos pos, final Block sourceBlock, @Nullable final Orientation orientation)` | `net/minecraft/server/level/ServerLevel.java:1172-1174` | Delegates neighbor update cascade to active NeighborUpdater without skipping any face | `NeighborUpdater.updateNeighborsAtExceptFromFacing(pos, sourceBlock, null, orientation)` | `none` |
| `public void updateNeighborsAtExceptFromFacing(final BlockPos pos, final Block blockObject, final Direction skipDirection, @Nullable final Orientation orientation)` | `net/minecraft/server/level/ServerLevel.java:1177-1181` | Delegates neighbor update cascade to active NeighborUpdater skipping a specified direction | `NeighborUpdater.updateNeighborsAtExceptFromFacing(pos, blockObject, skipDirection, orientation)` | `none` |
| `public void neighborChanged(final BlockPos pos, final Block changedBlock, @Nullable final Orientation orientation)` | `net/minecraft/server/level/ServerLevel.java:1184-1186` | Notifies single target position that a neighbor changed | `NeighborUpdater.neighborChanged(pos, changedBlock, orientation)` | `none` |
| `public void neighborChanged(final BlockState state, final BlockPos pos, final Block changedBlock, @Nullable final Orientation orientation, final boolean movedByPiston)` | `net/minecraft/server/level/ServerLevel.java:1188-1192` | Notifies single target position with known BlockState that a neighbor changed | `NeighborUpdater.neighborChanged(state, pos, changedBlock, orientation, movedByPiston)` | `none` |
| `public void neighborShapeChanged(final Direction direction, final BlockPos pos, final BlockPos neighborPos, final BlockState neighborState, @Block.UpdateFlags final int updateFlags, final int updateLimit)` | `net/minecraft/world/level/Level.java:333-342` | Dispatches physical shape update notification via neighborUpdater | `NeighborUpdater.shapeUpdate(direction, neighborState, pos, neighborPos, updateFlags, updateLimit)` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:778-796 (ShapeUpdate)` (partial: liquids only) |
| `public void updateNeighbourForOutputSignal(final BlockPos pos, final Block changedBlock)` | `net/minecraft/world/level/Level.java:1007-1023` | Searches 4 horizontal directions for adjacent comparators or comparators behind solid conductors and notifies them | `this.hasChunkAt`, `this.getBlockState`, `BlockState.is(Blocks.COMPARATOR)`, `BlockState.isRedstoneConductor`, `this.neighborChanged` | `none` |

---

### 1.3 NeighborUpdater and CollectingNeighborUpdater (`net/minecraft/world/level/redstone/NeighborUpdater.java`, `CollectingNeighborUpdater.java`)

Vanilla uses `CollectingNeighborUpdater` on `ServerLevel` to prevent stack overflow and manage the recursive neighbor update cascade with exact LIFO/FIFO layer semantics.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `void shapeUpdate(Direction direction, BlockState neighborState, BlockPos pos, BlockPos neighborPos, @Block.UpdateFlags int updateFlags, int updateLimit)` | `net/minecraft/world/level/redstone/NeighborUpdater.java:20` / `CollectingNeighborUpdater.java:35-48` | Wraps shape update into ShapeUpdate record and invokes addAndRun | `this.addAndRun`, `CollectingNeighborUpdater.ShapeUpdate.new` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:778-796` (partial, liquids only) |
| `void neighborChanged(BlockPos pos, Block changedBlock, @Nullable Orientation orientation)` | `net/minecraft/world/level/redstone/NeighborUpdater.java:22` / `CollectingNeighborUpdater.java:50-53` | Wraps simple neighbor update into SimpleNeighborUpdate record and invokes addAndRun | `this.addAndRun`, `CollectingNeighborUpdater.SimpleNeighborUpdate.new` | `none` |
| `void neighborChanged(BlockState state, BlockPos pos, Block changedBlock, @Nullable Orientation orientation, boolean movedByPiston)` | `net/minecraft/world/level/redstone/NeighborUpdater.java:24` / `CollectingNeighborUpdater.java:55-60` | Wraps full neighbor update into FullNeighborUpdate record and invokes addAndRun | `this.addAndRun`, `CollectingNeighborUpdater.FullNeighborUpdate.new` | `none` |
| `default void updateNeighborsAtExceptFromFacing(final BlockPos pos, final Block block, @Nullable final Direction skipDirection, @Nullable final Orientation orientation)` | `net/minecraft/world/level/redstone/NeighborUpdater.java:26-34` / `CollectingNeighborUpdater.java:62-67` | In NeighborUpdater: iterates UPDATE_ORDER. In CollectingNeighborUpdater: wraps into MultiNeighborUpdate and invokes addAndRun | `this.addAndRun`, `CollectingNeighborUpdater.MultiNeighborUpdate.new` | `none` |
| `static void executeShapeUpdate(final LevelAccessor level, final Direction direction, final BlockPos pos, final BlockPos neighborPos, final BlockState neighborState, @Block.UpdateFlags final int updateFlags, final int updateLimit)` | `net/minecraft/world/level/redstone/NeighborUpdater.java:36-59` | Executes BlockState.updateShape and updates or destroys block unless flag 128 is set on wire | `LevelAccessor.getBlockState(pos)`, `BlockState.updateShape`, `Block.updateOrDestroy` | `none` |
| `static void executeUpdate(final Level level, final BlockState state, final BlockPos pos, final Block changedBlock, @Nullable final Orientation orientation, final boolean movedByPiston)` | `net/minecraft/world/level/redstone/NeighborUpdater.java:61-93` | Executes BlockState.handleNeighborChanged with crash report handling | `BlockState.handleNeighborChanged` | `none` |
| `private void addAndRun(final BlockPos pos, final CollectingNeighborUpdater.NeighborUpdates update)` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:69-86` | Enforces update depth cap (1,000,000); queues to addedThisLayer if already running, else to stack and invokes runUpdates | `List.add`, `ArrayDeque.push`, `this.runUpdates` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:799-819 (addAndRun)` (partial: liquids only) |
| `private void runUpdates()` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:88-113` | Reverses addedThisLayer into stack (LIFO across layers, FIFO within layer) and drains | `ArrayDeque.push`, `ArrayDeque.peek`, `ArrayDeque.pop`, `NeighborUpdates.runNext` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:822-843 (runUpdates)` (partial: liquids only; `TickLoop.java:778-785` uses flat LIFO stack) |
| `public boolean MultiNeighborUpdate.runNext(final Level level)` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:150-172` | Steps index through UPDATE_ORDER (WEST, EAST, DOWN, UP, NORTH, SOUTH), executes one neighborChanged, returns true while steps remain | `NeighborUpdater.UPDATE_ORDER`, `BlockPos.relative`, `Level.getBlockState`, `NeighborUpdater.executeUpdate` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:736-758 (NeighborsAt.runNext)` (partial: liquids only) |
| `public boolean FullNeighborUpdate.runNext(final Level level)` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:117-121` | Executes single neighborChanged with stored BlockState | `NeighborUpdater.executeUpdate` | `none` |
| `public boolean SimpleNeighborUpdate.runNext(final Level level)` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:207-212` | Fetches live BlockState at pos and executes single neighborChanged | `Level.getBlockState(pos)`, `NeighborUpdater.executeUpdate` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:761-775 (SingleNeighbor.runNext)` (partial: liquids only) |
| `public boolean ShapeUpdate.runNext(final Level level)` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:194-198` | Calls NeighborUpdater.executeShapeUpdate | `NeighborUpdater.executeShapeUpdate` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:778-796 (ShapeUpdate.runNext)` (partial: liquids only) |

---

### 1.4 Orientation (`net/minecraft/world/level/redstone/Orientation.java`)

`Orientation` encodes 3D propagation bias (48 unique orientations) used by experimental redstone and neighbor dispatch.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public static Orientation of(final Direction up, final Direction front, final Orientation.SideBias sideBias)` | `net/minecraft/world/level/redstone/Orientation.java:54-56` | Looks up precomputed orientation from 48-element array by up, front, and side bias | `generateIndex(up, front, sideBias)`, `ORIENTATIONS` array | `none` |
| `public Orientation withUp(final Direction up)` | `net/minecraft/world/level/redstone/Orientation.java:58-60` | Returns cached precomputed orientation rotated to given up direction | `Map.get(up)` | `none` |
| `public Orientation withFront(final Direction front)` | `net/minecraft/world/level/redstone/Orientation.java:62-64` | Returns cached precomputed orientation rotated to given front direction | `Map.get(front)` | `none` |
| `public Orientation withFrontPreserveUp(final Direction front)` | `net/minecraft/world/level/redstone/Orientation.java:66-68` | Returns orientation with front updated if front does not collide with up axis | `Direction.getAxis`, `this.withFront(front)` | `none` |
| `public Orientation withFrontAdjustSideBias(final Direction front)` | `net/minecraft/world/level/redstone/Orientation.java:70-73` | Returns orientation with front updated and side bias mirrored if necessary | `this.withFront(front)`, `this.withMirror` | `none` |
| `public Orientation withSideBias(final Orientation.SideBias sideBias)` | `net/minecraft/world/level/redstone/Orientation.java:75-77` | Returns cached precomputed orientation with specified side bias | `Map.get(sideBias)` | `none` |
| `public Orientation withMirror()` | `net/minecraft/world/level/redstone/Orientation.java:79-81` | Returns cached orientation with mirrored side bias (LEFT <-> RIGHT) | `this.withSideBias`, `SideBias.getOpposite` | `none` |
| `public Direction getFront()` | `net/minecraft/world/level/redstone/Orientation.java:83-85` | Returns front direction vector | none | `none` |
| `public Direction getUp()` | `net/minecraft/world/level/redstone/Orientation.java:87-89` | Returns up direction vector | none | `none` |
| `public Direction getSide()` | `net/minecraft/world/level/redstone/Orientation.java:91-93` | Returns perpendicular side direction vector | none | `none` |
| `public Orientation.SideBias getSideBias()` | `net/minecraft/world/level/redstone/Orientation.java:95-97` | Returns side bias enum (LEFT or RIGHT) | none | `none` |
| `public List<Direction> getDirections()` | `net/minecraft/world/level/redstone/Orientation.java:99-101` | Returns 6 directions ordered: back, front, side, side opposite, up opposite, up | none | `none` |
| `public List<Direction> getHorizontalDirections()` | `net/minecraft/world/level/redstone/Orientation.java:103-105` | Returns 4 horizontal directions orthogonal to up axis | none | `none` |
| `public List<Direction> getVerticalDirections()` | `net/minecraft/world/level/redstone/Orientation.java:107-109` | Returns 2 directions collinear with up axis | none | `none` |
| `public int getIndex()` | `net/minecraft/world/level/redstone/Orientation.java:116-118` | Returns compact integer index (0-47) | none | `none` |
| `public static Orientation fromIndex(final int index)` | `net/minecraft/world/level/redstone/Orientation.java:120-122` | Returns precomputed Orientation from integer index | `ORIENTATIONS[index]` | `none` |
| `public static Orientation random(final RandomSource rand)` | `net/minecraft/world/level/redstone/Orientation.java:124-126` | Selects uniform random orientation from 48 precomputed instances | `Util.getRandom(ORIENTATIONS, rand)` | `none` |
| `private static Orientation generateContext(final Orientation self, final Orientation[] lookup)` | `net/minecraft/world/level/redstone/Orientation.java:128-164` | Recursively generates all 48 orientations and populates lookup graph | `Orientation.new`, `EnumMap.put` | `none` |
| `protected static int generateIndex(final Direction up, final Direction front, final Orientation.SideBias sideBias)` | `net/minecraft/world/level/redstone/Orientation.java:166-180` | Bitwise pack: `(((up << 2) + frontKey) << 1) + sideBias` | bitwise operators | `none` |

---

### 1.5 ServerLevel Block Events Pipeline (`net/minecraft/server/level/ServerLevel.java`)

Block events are vanilla's mechanism for deferring multi-phase actions (piston extension, retraction, chest opening) from scheduled ticks or neighbor updates to Phase 6 of the server tick (`ServerLevel.tick`).

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public void blockEvent(final BlockPos pos, final Block block, final int b0, final int b1)` | `net/minecraft/server/level/ServerLevel.java:1252-1254` | Enqueues BlockEventData into blockEvents set (deduplicated by pos, block, b0, b1 while preserving FIFO order) | `ObjectLinkedOpenHashSet.add(new BlockEventData(...))` | `none` (TickLoop completely lacks block events queue) |
| `private void runBlockEvents()` | `net/minecraft/server/level/ServerLevel.java:1256-1281` | Drains blockEvents FIFO; executes doBlockEvent, broadcasts packets to tracking players, and re-enqueues unticked events | `ObjectLinkedOpenHashSet.removeFirst`, `this.shouldTickBlocksAt`, `this.doBlockEvent`, `PlayerList.broadcast`, `addAll` | `none` (TickLoop completely lacks Phase 6 block events drain) |
| `private boolean doBlockEvent(final BlockEventData eventData)` | `net/minecraft/server/level/ServerLevel.java:1283-1286` | Fetches block state at pos and calls triggerEvent if block matches | `Level.getBlockState(eventData.pos())`, `BlockState.is(eventData.block())`, `BlockState.triggerEvent` | `none` |

---

### 1.6 LevelTicks Scheduling and TickPriority (`net/minecraft/world/ticks/LevelTicks.java`)

`LevelTicks` manages discrete scheduled tick queues for blocks (`blockTicks`) and fluids (`fluidTicks`), ordering execution by `triggerTick`, `priority`, and `subTickOrder`.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public void schedule(final ScheduledTick<T> tick)` | `net/minecraft/world/ticks/LevelTicks.java:70-79` | Dispatches scheduled tick to chunk container; drops duplicates for pending (pos, type) | `ChunkPos.pack`, `Long2ObjectMap.get`, `LevelChunkTicks.schedule` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:864-897 (scheduleTick)` |
| `public void tick(final long currentTick, final int maxTicksToProcess, final BiConsumer<BlockPos, T> output)` | `net/minecraft/world/ticks/LevelTicks.java:81-92` | Drains due scheduled ticks up to maxTicksToProcess (65,536) in priority order and invokes callback | `this.collectTicks`, `this.runCollectedTicks`, `this.cleanupAfterTick` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1111-1133 (drainScheduled)` |
| `private void collectTicks(final long currentTick, final int maxTicksToProcess, final ProfilerFiller profiler)` | `net/minecraft/world/ticks/LevelTicks.java:94-99` | Sorts container queue and drains due ticks into toRunThisTick | `this.sortContainersToTick`, `this.drainContainers`, `this.rescheduleLeftoverContainers` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1111-1126 (drainScheduled collect phase)` |
| `private void sortContainersToTick(final long currentTick)` | `net/minecraft/world/ticks/LevelTicks.java:101-124` | Scans containers with due ticks and enqueues candidate containers into containersToTick PriorityQueue | `Long2LongMaps.fastIterator`, `PriorityQueue.add` | `none` (Kestrel uses single global PriorityQueue rather than per-chunk containers) |
| `private void drainContainers(final long currentTick, final int maxTicksToProcess)` | `net/minecraft/world/ticks/LevelTicks.java:126-141` | Polls top container, drains ticks with intra-tick priority comparison, updates container scheduling | `PriorityQueue.poll`, `LevelChunkTicks.poll`, `this.scheduleForThisTick`, `this.drainFromCurrentContainer` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1111-1126` |
| `private void drainFromCurrentContainer(final Queue<LevelChunkTicks<T>> containersToTick, final LevelChunkTicks<T> currentContainer, final long currentTick, final int maxTicksToProcess)` | `net/minecraft/world/ticks/LevelTicks.java:153-172` | Drains ticks from current container while their priority precedes the next container's head | `ScheduledTick.INTRA_TICK_DRAIN_ORDER.compare`, `this.scheduleForThisTick` | `none` |
| `private void runCollectedTicks(final BiConsumer<BlockPos, T> output)` | `net/minecraft/world/ticks/LevelTicks.java:182-192` | Drains toRunThisTick queue, removes from toRunThisTickSet, moves to alreadyRunThisTick, invokes callback | `Queue.poll`, `Set.remove`, `List.add`, `BiConsumer.accept` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1127-1133 (runScheduled loop)` |
| `public boolean willTickThisTick(final BlockPos pos, final T type)` | `net/minecraft/world/ticks/LevelTicks.java:207-211` | Returns true if the tick for (pos, type) was collected and is pending execution in this tick pass | `this.calculateTickSetIfNeeded`, `Set.contains`, `ScheduledTick.probe` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:969-974 (willTickThisTick)` |
| `public boolean hasScheduledTick(final BlockPos pos, final T block)` | `net/minecraft/world/ticks/LevelTicks.java:201-205` | Returns true if (pos, block) has any pending tick scheduled in the future | `Long2ObjectMap.get`, `LevelChunkTicks.hasScheduledTick` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:983-988 (hasScheduledTick)` |
| `public void clearArea(final BoundingBox area)` | `net/minecraft/world/ticks/LevelTicks.java:236-252` | Removes all pending and in-flight ticks within a bounding box (used when cloning or clearing areas) | `this.forContainersInArea`, `LevelChunkTicks.removeIf`, `List.removeIf` | `none` |
| `public void copyAreaFrom(final LevelTicks<T> source, final BoundingBox area, final Vec3i offset)` | `net/minecraft/world/ticks/LevelTicks.java:258-272` | Copies scheduled ticks within an area with an offset and re-indexed subTickOrder | `BoundingBox.isInside`, `this.schedule`, `ScheduledTick.new` | `none` |


---

## 2. Slice 1: Redstone Dust (Wire)

Redstone wire is the core analog/digital transmission component in Minecraft. Power propagates synchronously in 0 ticks with signal falloff (15 down to 0).

### 2.1 RedStoneWireBlock (`net/minecraft/world/level/block/RedStoneWireBlock.java`)

`RedStoneWireBlock` stores 4-side connection properties (`NORTH`, `EAST`, `SOUTH`, `WEST` as `RedstoneSide: NONE, SIDE, UP`) and signal intensity (`POWER`: 0 to 15).

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<RedStoneWireBlock> codec()` | `net/minecraft/world/level/block/RedStoneWireBlock.java:71-73` | Returns serialization codec for redstone wire block | none | `none` |
| `public RedStoneWireBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:75-90` | Initializes wire block, registers default state (NONE, POWER=0), builds shapes and crossState | `this.registerDefaultState`, `this.makeShapes`, `this.defaultBlockState` | `none` (Kestrel uses static vanilla block reference `Blocks.REDSTONE_WIRE`) |
| `private Function<BlockState, VoxelShape> makeShapes()` | `net/minecraft/world/level/block/RedStoneWireBlock.java:92-113` | Precomputes collision/selection shapes for all combinations of horizontal wire connections | `Shapes.rotateHorizontal`, `Shapes.or`, `Block.column`, `Block.boxZ` | `none` |
| `protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:116-118` | Returns cached VoxelShape for wire state | `Function.apply(state)` | `none` |
| `public BlockState getStateForPlacement(final BlockPlaceContext context)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:121-123` | Computes initial wire connection state upon placement against surrounding neighbors | `this.getConnectionState(context.getLevel(), this.crossState, context.getClickedPos())` | `none` |
| `private BlockState getConnectionState(final BlockGetter level, BlockState state, final BlockPos pos)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:125-155` | Resolves 4 horizontal connections; converts isolated dots or 1-direction stubs into 2-way straight lines | `isDot`, `this.getMissingConnections`, `BlockState.setValue` | `none` |
| `private BlockState getMissingConnections(final BlockGetter level, BlockState state, final BlockPos pos)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:157-168` | Scans unconnected directions, checks conductor above for vertical connection gating, and resolves connecting sides | `BlockGetter.getBlockState(pos.above())`, `BlockState.isRedstoneConductor`, `this.getConnectingSide` | `none` |
| `protected BlockState updateShape(final BlockState state, final LevelReader level, final ScheduledTickAccess ticks, final BlockPos pos, final Direction directionToNeighbour, final BlockPos neighbourPos, final BlockState neighbourState, final RandomSource random)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:171-193` | Drops wire if floor missing; re-evaluates wire connection states when adjacent or diagonal blocks change | `this.canSurviveOn`, `this.getConnectingSide`, `this.getConnectionState`, `isCross` | `none` |
| `private static boolean isCross(final BlockState state)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:195-200` | Returns true if wire connects in all 4 cardinal directions | `BlockState.getValue(NORTH/SOUTH/EAST/WEST).isConnected()` | `none` |
| `private static boolean isDot(final BlockState state)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:202-207` | Returns true if wire has zero connections in all 4 cardinal directions | `BlockState.getValue(NORTH/SOUTH/EAST/WEST).isConnected()` | `none` |
| `protected void updateIndirectNeighbourShapes(final BlockState state, final LevelAccessor level, final BlockPos pos, @Block.UpdateFlags final int updateFlags, final int updateLimit)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:210-233` | Notifies diagonal (one up, one down) neighbors so ascending/descending wire connections stay synchronized | `LevelAccessor.getBlockState`, `LevelAccessor.neighborShapeChanged`, `BlockState.is(this)` | `none` (partially mirrored in `FluidFlow.java:697-710` for liquid displacement) |
| `private RedstoneSide getConnectingSide(final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:235-237` | Overload querying connecting side with vertical connectivity checked from block above | `this.getConnectingSide(level, pos, direction, !conductorAbove)` | `none` |
| `private RedstoneSide getConnectingSide(final BlockGetter level, final BlockPos pos, final Direction direction, final boolean canConnectUp)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:239-258` | Checks face sturdiness and conductor rules to decide if wire connects NONE, SIDE, or UP | `BlockGetter.getBlockState`, `shouldConnectTo`, `BlockState.isFaceSturdy`, `BlockState.isRedstoneConductor` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:529-552 (sideToward)` (partial: reads cached or stored sides, misses evaluation) |
| `protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:260-264` | Checks if block beneath can support wire | `LevelReader.getBlockState(pos.below())`, `this.canSurviveOn` | `none` |
| `private boolean canSurviveOn(final BlockGetter level, final BlockPos relativePos, final BlockState relativeState)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:266-268` | Returns true if support block has sturdy top face or is a hopper | `BlockState.isFaceSturdy(level, relativePos, Direction.UP)`, `relativeState.is(Blocks.HOPPER)` | `none` |
| `private void updatePowerStrength(final Level level, final BlockPos pos, final BlockState state, @Nullable final Orientation orientation, final boolean shapeUpdateWiresAroundInitialPosition)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:270-282` | Dispatches wire power calculation to either default or experimental evaluator based on feature flags | `useExperimentalEvaluator`, `RedstoneWireEvaluator.updatePowerStrength` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:594-615 (updatePowerAt)` (partial: calls `calculateTarget` and `enqueueFanOut`) |
| `public int getBlockSignal(final Level level, final BlockPos pos)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:284-289` | Queries external non-wire power by temporarily setting shouldSignal=false and calling getBestNeighborSignal | `level.getBestNeighborSignal(pos)` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:456-474 (getBlockSignal)` (wires `signalReadsOpen` guard) |
| `private void checkCornerChangeAt(final Level level, final BlockPos pos)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:291-299` | Fires updateNeighborsAt on target position and all 6 surrounding neighbors if wire is present | `Level.getBlockState(pos).is(this)`, `Level.updateNeighborsAt` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:712-724 (checkCornerChangeAt)` |
| `protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:302-312` | Evaluates power on placement, fires updateNeighborsAt on vertical neighbors, and notifies neighboring wires | `this.updatePowerStrength`, `level.updateNeighborsAt(pos.above()/below(), this)`, `this.updateNeighborsOfNeighboringWires` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:667-674 (placeFanOut)` (partial: neighbor dispatch only) |
| `protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:315-324` | Fires 6-way updateNeighborsAt, re-evaluates power strength, and notifies neighboring wires after removal | `level.updateNeighborsAt`, `this.updatePowerStrength`, `this.updateNeighborsOfNeighboringWires` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:644-649 (removalFanOut)` (partial: neighbor dispatch only) |
| `private void updateNeighborsOfNeighboringWires(final Level level, final BlockPos pos)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:326-339` | Fires checkCornerChangeAt on all horizontal neighbors and their vertical diagonals | `this.checkCornerChangeAt`, `Level.getBlockState`, `BlockState.isRedstoneConductor` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:697-710 (neighboringWires)` |
| `protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, @Nullable final Orientation orientation, final boolean movedByPiston)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:342-355` | Validates survival (drops item if broken); triggers updatePowerStrength on surviving wire | `state.canSurvive`, `this.updatePowerStrength`, `dropResources`, `level.removeBlock` | `none` (Kestrel runs custom `updatePowerAt` directly from `TickLoop.java:802`) |
| `private static boolean useExperimentalEvaluator(final Level level)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:357-359` | Checks if FeatureFlags.REDSTONE_EXPERIMENTS is active | `level.enabledFeatures().contains(...)` | `none` (Kestrel uses `acFlag.enabled()` in `TickLoop.java:603` instead) |
| `protected int getDirectSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:362-364` | Returns strong direct power downward into floor block only; 0 if shouldSignal is false | `state.getSignal(level, pos, direction)` | `none` |
| `protected int getSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:367-381` | Emits power to connected horizontal directions and downward, returning 0 if shouldSignal is false or direction is DOWN | `this.ownSignal`, `this.getConnectionState`, `RedstoneSide.isConnected` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:577-582 (wireNeighborSignal)` (partial: delegates to vanilla `BlockState.getSignal`) |
| `protected int ownSignal(final BlockState state, final BlockGetter level, final BlockPos pos)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:384-386` | Returns current POWER property value (0-15) | `state.getValue(POWER)` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:515-517 (wirePowerAt)` |
| `protected static boolean shouldConnectTo(final BlockState blockState)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:388-390` | Overload checking connection with null direction | `shouldConnectTo(blockState, null)` | `none` |
| `protected static boolean shouldConnectTo(final BlockState blockState, @Nullable final Direction direction)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:392-402` | Returns true for wire, repeaters aligned with wire axis, observers facing into wire, or general signal sources | `blockState.is(Blocks.REDSTONE_WIRE)`, `blockState.is(Blocks.REPEATER)`, `blockState.is(Blocks.OBSERVER)`, `blockState.isSignalSource` | `none` |
| `protected boolean isSignalSource(final BlockState state)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:404-406` | Returns true when shouldSignal flag is true | none | `none` |
| `public static int getColorForPower(final int power)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:408-410` | Returns RGB color integer for power level 0-15 (particle/rendering) | `COLORS[power]` | `none` |
| `private static void spawnParticlesAlongLine(...)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:412-431` | Renders redstone dust particles along wire connection edges | `RandomSource.nextFloat`, `Level.addParticle` | `none` (client side only) |
| `public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:434-452` | Client visual particles | `spawnParticlesAlongLine` | `none` (client side only) |
| `protected BlockState rotate(final BlockState state, final Rotation rotation)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:454-471` | Rotates NORTH, SOUTH, EAST, WEST connection properties | `BlockState.setValue` | `none` |
| `protected BlockState mirror(final BlockState state, final Mirror mirror)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:473-483` | Mirrors horizontal connection properties across axis | `BlockState.setValue` | `none` |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:485-487` | Registers NORTH, EAST, SOUTH, WEST, and POWER state properties | `builder.add` | `none` |
| `protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:490-509` | Player right-click toggles between dot and cross state | `isCross`, `isDot`, `this.getConnectionState`, `level.setBlock`, `this.updatesOnShapeChange` | `none` |
| `private void updatesOnShapeChange(final Level level, final BlockPos pos, final BlockState oldState, final BlockState newState)` | `net/minecraft/world/level/block/RedStoneWireBlock.java:511-523` | Fires neighbor updates to adjacent conductors when dot/cross toggle alters connection sides | `ExperimentalRedstoneUtils.initialOrientation`, `Level.getBlockState`, `BlockState.isRedstoneConductor`, `Level.updateNeighborsAtExceptFromFacing` | `none` |

---

### 2.2 RedstoneWireEvaluator (`net/minecraft/world/level/redstone/RedstoneWireEvaluator.java`)

Abstract base class defining wire signal queries from external blocks and neighboring wires.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `protected RedstoneWireEvaluator(final RedStoneWireBlock wireBlock)` | `net/minecraft/world/level/redstone/RedstoneWireEvaluator.java:13-15` | Stores wire block instance reference | none | `none` |
| `public abstract void updatePowerStrength(final Level level, final BlockPos pos, final BlockState state, @Nullable final Orientation orientation, final boolean skipShapeUpdates)` | `net/minecraft/world/level/redstone/RedstoneWireEvaluator.java:17-19` | Abstract entry point for wire recomputation and neighbor dispatch | none | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:594-615 (updatePowerAt)` |
| `protected int getBlockSignal(final Level level, final BlockPos pos)` | `net/minecraft/world/level/redstone/RedstoneWireEvaluator.java:21-23` | Queries non-wire external signal via wire block helper | `RedStoneWireBlock.getBlockSignal(level, pos)` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:456-474 (getBlockSignal)` |
| `protected int getWireSignal(final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/redstone/RedstoneWireEvaluator.java:25-27` | Extracts POWER property value if block is wire, else 0 | `BlockState.is(this.wireBlock)`, `BlockState.getValue(POWER)` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:515-517 (wirePowerAt)` |
| `protected int getIncomingWireSignal(final Level level, final BlockPos pos)` | `net/minecraft/world/level/redstone/RedstoneWireEvaluator.java:29-47` | Finds max wire signal across 4 horizontal neighbors, plus ascending wires over non-conductors and descending wires under non-conductors; subtracts 1 | `this.getWireSignal`, `Level.getBlockState`, `BlockState.isRedstoneConductor`, `Math.max(0, best - 1)` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:496-513 (getIncomingWireSignal)` |

---

### 2.3 DefaultRedstoneWireEvaluator (`net/minecraft/world/level/redstone/DefaultRedstoneWireEvaluator.java`)

Vanilla's default wire evaluator. This is the sole parity target for G12.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public DefaultRedstoneWireEvaluator(final RedStoneWireBlock wireBlock)` | `net/minecraft/world/level/redstone/DefaultRedstoneWireEvaluator.java:13-15` | Constructor passing wire block to super | `super(wireBlock)` | `none` |
| `public void updatePowerStrength(final Level level, final BlockPos pos, final BlockState state, @Nullable final Orientation orientation, final boolean skipShapeUpdates)` | `net/minecraft/world/level/redstone/DefaultRedstoneWireEvaluator.java:18-38` | Computes target power; if changed, sets block (flags=2), collects pos + 6 neighbors into HashSet, and calls updateNeighborsAt on all 7 positions | `this.calculateTargetStrength`, `Level.setBlock`, `Sets.newHashSet`, `Level.updateNeighborsAt` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:594-615 (updatePowerAt)` and `742-756 (enqueueFanOut)` |
| `private int calculateTargetStrength(final Level level, final BlockPos pos)` | `net/minecraft/world/level/redstone/DefaultRedstoneWireEvaluator.java:40-43` | Returns max of external signal and incoming wire signal (or 15 if external signal is 15) | `this.getBlockSignal(level, pos)`, `this.getIncomingWireSignal(level, pos)` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:456-474` and `496-513` |

---

### 2.4 ExperimentalRedstoneWireEvaluator (`net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java`)

Experimental breadth-first search evaluator gated behind `FeatureFlags.REDSTONE_EXPERIMENTS`. Not active on standard worlds.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public ExperimentalRedstoneWireEvaluator(final RedStoneWireBlock wireBlock)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:26-28` | Constructor initializing evaluator queues and wire block | `super(wireBlock)` | `none` |
| `public void updatePowerStrength(final Level level, final BlockPos pos, final BlockState state, @Nullable final Orientation orientation, final boolean skipShapeUpdates)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:30-60` | Runs two-queue (turn-off then turn-on) network propagation and commits settled blocks with orientation updates | `getInitialOrientation`, `calculateCurrentChanges`, `causeNeighborUpdates`, `Level.setBlock` | `none` (Kestrel has Alternate Current in `AcWireNetwork.java:30-180` behind `acFlag`, not this BFS evaluator) |
| `private void causeNeighborUpdates(final Level level)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:62-90` | Dispatches orientation-ordered neighbor updates to neighbors of all changed wires | `Orientation.getDirections`, `Level.updateNeighborsAt`, `Level.neighborChanged` | `none` |
| `private static boolean isConnected(final BlockState state, final Direction direction)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:92-95` | Returns true if wire connects toward direction | `BlockState.getValue(PROPERTY_BY_DIRECTION).isConnected()` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:524-527 (connectedToward)` |
| `private static Orientation getInitialOrientation(final Level level, @Nullable final Orientation incomingOrigination)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:97-106` | Initializes random orientation if null | `Orientation.random(level.getRandom())` | `none` |
| `private void calculateCurrentChanges(final Level level, final BlockPos initialPosition, final Orientation initialOrientation)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:108-159` | Drains wiresToTurnOff queue and propagates turn-off and turn-on across network | `this.propagateChangeToNeighbors`, `this.setPower` | `none` |
| `private static int packOrientationAndPower(final Orientation orientation, final int power)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:161-163` | Packs orientation index and power byte into integer | bitwise packing | `none` |
| `private static Orientation unpackOrientation(final int packed)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:165-167` | Unpacks orientation from packed integer | `Orientation.fromIndex` | `none` |
| `private static int unpackPower(final int packed)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:169-171` | Unpacks power from lower 4 bits | bitwise mask `& 15` | `none` |
| `private void setPower(final BlockPos pos, final int newPower, final Orientation orientation)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:173-179` | Writes packed power and orientation to internal map | `Object2IntLinkedOpenHashMap.put` | `none` |
| `private void propagateChangeToNeighbors(final Level level, final BlockPos pos, final int newPower, final Orientation orientation, final boolean allowTurningOff)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:181-204` | Spreads power change to horizontal and vertical neighbor wires | `this.enqueueNeighborWire`, `Orientation.withFront` | `none` |
| `private void enqueueNeighborWire(final Level level, final BlockPos pos, final int newFromPower, final Orientation orientation, final boolean allowTurningOff)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:206-221` | Enqueues connected neighbor wire into turn-off or turn-on queue | `this.wiresToTurnOff.enqueue`, `this.wiresToTurnOn.enqueue` | `none` |
| `protected int getWireSignal(final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java:223-225` | Reads overlay power if present, otherwise reads from blockstate | `Object2IntLinkedOpenHashMap.get`, `unpackPower` | `none` |


---

## 3. Slice 2: Redstone Torch, Repeater, and Simple Sources/Consumers

Slice 2 covers signal inversion (torches), signal amplification and delays (repeaters), player/entity input sources (levers, buttons, pressure plates), constant sources (redstone block), and visual consumers (lamps).

### 3.1 RedstoneTorchBlock (`net/minecraft/world/level/block/RedstoneTorchBlock.java`)

`RedstoneTorchBlock` inverts power with a 2-tick delay, emits strong power upward, weak power to 5 faces, and implements the 8-toggle burnout mechanism.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<? extends RedstoneTorchBlock> codec()` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:34-36` | Returns codec for redstone torch block | none | `none` |
| `protected RedstoneTorchBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:38-41` | Registers default state with LIT=true | `this.registerDefaultState` | `none` |
| `protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:44-46` | Fires notifyNeighbors on placement | `this.notifyNeighbors(level, pos, state)` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:675-678 (placeFanOut)` |
| `private void notifyNeighbors(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:48-54` | Dispatches updateNeighborsAt to all 6 adjacent positions with orientation | `this.randomOrientation`, `Level.updateNeighborsAt`, `ExperimentalRedstoneUtils.withFront` | `none` |
| `protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:57-61` | Fires notifyNeighbors when torch is removed without piston move | `this.notifyNeighbors(level, pos, state)` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:650-653 (removalFanOut)` |
| `protected boolean hasNeighborSignal(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:63-65` | Returns true if the block underneath receives weak power from below | `level.hasSignal(pos.below(), Direction.DOWN)` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:820-823 (hasSignalBelow)` |
| `protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:68-87` | Toggles LIT property; prunes old burnout entries; if toggled 8 times in 60 ticks, plays sound/smoke event 1502 and schedules 160-tick restart delay | `this.hasNeighborSignal`, `RECENT_TOGGLES.get`, `isToggledTooFrequently`, `Level.setBlock`, `Level.levelEvent`, `Level.scheduleTick` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1148-1161 (runScheduled)` (partial: flips LIT and calls `enqueueFanOut`, completely lacks burnout mechanism) |
| `protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, @Nullable final Orientation orientation, final boolean movedByPiston)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:90-96` | If torch lit matches neighbor signal and no tick pending, schedules toggle tick with delay 2 | `this.hasNeighborSignal`, `Level.getBlockTicks().willTickThisTick`, `Level.scheduleTick(pos, this, 2)` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:807-812 (updateNeighborsAt)` (partial: hardcoded check in tick loop) |
| `protected int getDirectSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:99-101` | Emits strong direct signal upward into the block above (direction == DOWN) | `state.getSignal(level, pos, direction)` | `none` |
| `protected boolean isSignalSource(final BlockState state)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:104-106` | Returns true unconditionally | none | `none` |
| `protected int ownSignal(final BlockState state, final BlockGetter level, final BlockPos pos)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:109-111` | Returns 15 if LIT is true, else 0 | `state.getValue(LIT)` | `none` |
| `protected int getSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:114-116` | Emits weak power 15 to 5 directions (above, north, south, west, east); returns 0 downward (direction == UP) | `this.ownSignal(state, level, pos)` | `none` |
| `public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:119-126` | Emits redstone smoke particles when lit | `Level.addParticle` | `none` (client only) |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:129-131` | Registers LIT property | `builder.add(LIT)` | `none` |
| `private static boolean isToggledTooFrequently(final Level level, final BlockPos pos, final boolean add)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:133-150` | Tracks toggle timestamps in RECENT_TOGGLES; returns true if pos toggled >= 8 times in last 60 ticks | `WeakHashMap.computeIfAbsent`, `List.add`, `Level.getGameTime` | `none` (torch burnout is completely missing in Kestrel) |
| `protected Orientation randomOrientation(final Level level, final BlockState state)` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:153-155` | Computes initial orientation with up bias for neighbor updates | `ExperimentalRedstoneUtils.initialOrientation(level, null, Direction.UP)` | `none` |

---

### 3.2 RedstoneWallTorchBlock (`net/minecraft/world/level/block/RedstoneWallTorchBlock.java`)

`RedstoneWallTorchBlock` attaches to the side of a block, checking power input from the support block behind it.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<RedstoneWallTorchBlock> codec()` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:30-32` | Returns codec for wall torch | none | `none` |
| `protected RedstoneWallTorchBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:34-37` | Sets default state with FACING=NORTH and LIT=true | `this.registerDefaultState` | `none` |
| `protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:40-42` | Returns directional VoxelShape from WallTorchBlock | `WallTorchBlock.getShape(state)` | `none` |
| `protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:45-47` | Returns true if supporting wall face is sturdy | `WallTorchBlock.canSurvive(level, pos, facing)` | `none` |
| `protected BlockState updateShape(final BlockState state, final LevelReader level, final ScheduledTickAccess ticks, final BlockPos pos, final Direction directionToNeighbour, final BlockPos neighbourPos, final BlockState neighbourState, final RandomSource random)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:50-61` | Drops torch if supporting wall is broken | `state.canSurvive`, `Blocks.AIR.defaultBlockState` | `none` |
| `public BlockState getStateForPlacement(final BlockPlaceContext context)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:64-68` | Selects clicked horizontal face for placement | `Blocks.WALL_TORCH.getStateForPlacement(context)` | `none` |
| `public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:71-80` | Emits redstone smoke particles with wall offset | `Level.addParticle` | `none` (client only) |
| `protected boolean hasNeighborSignal(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:83-86` | Checks power from the wall block it is attached to (`pos.relative(facing.getOpposite())`) | `level.hasSignal(pos.relative(opposite), opposite)` | `none` (TickLoop only checks standing torch `hasSignalBelow`) |
| `protected int getSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:89-91` | Emits weak power to 5 directions, excluding the wall it is attached to (`direction == FACING`) | `this.ownSignal(state, level, pos)` | `none` |
| `protected BlockState rotate(final BlockState state, final Rotation rotation)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:94-96` | Rotates FACING property | `Rotation.rotate` | `none` |
| `protected BlockState mirror(final BlockState state, final Mirror mirror)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:99-101` | Mirrors FACING property | `Mirror.getRotation` | `none` |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:104-106` | Registers FACING and LIT properties | `builder.add(FACING, LIT)` | `none` |
| `protected Orientation randomOrientation(final Level level, final BlockState state)` | `net/minecraft/world/level/block/RedstoneWallTorchBlock.java:109-112` | Computes orientation oriented away from wall | `ExperimentalRedstoneUtils.initialOrientation(level, facing.getOpposite(), Direction.UP)` | `none` |

---

### 3.3 DiodeBlock (`net/minecraft/world/level/block/DiodeBlock.java`)

`DiodeBlock` is the abstract base class for repeaters and comparators, defining scheduled tick toggling, rear input querying, side locking signals, and front neighbor notifications.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `protected DiodeBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/DiodeBlock.java:31-33` | Constructor | `super(properties)` | `none` |
| `protected abstract MapCodec<? extends DiodeBlock> codec()` | `net/minecraft/world/level/block/DiodeBlock.java:35-36` | Abstract codec declaration | none | `none` |
| `protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context)` | `net/minecraft/world/level/block/DiodeBlock.java:38-41` | Returns flat 2-pixel slab VoxelShape | none | `none` |
| `protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos)` | `net/minecraft/world/level/block/DiodeBlock.java:43-47` | Checks if floor block can support rigid diode | `LevelReader.getBlockState(pos.below())`, `this.canSurviveOn` | `none` |
| `protected boolean canSurviveOn(final LevelReader level, final BlockPos neightborPos, final BlockState neighborState)` | `net/minecraft/world/level/block/DiodeBlock.java:49-51` | Checks if floor has rigid sturdy top face | `neighborState.isFaceSturdy(level, neightborPos, Direction.UP, SupportType.RIGID)` | `none` |
| `protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/DiodeBlock.java:54-67` | Toggles POWERED blockstate; if turning on and input already dropped, reschedules immediate turn-off with TickPriority.VERY_HIGH (pulse preservation) | `this.isLocked`, `this.shouldTurnOn`, `Level.setBlock`, `Level.scheduleTick` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1162-1178 (runScheduled)` (partial: toggles repeater POWERED, lacks pulse preservation check) |
| `protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, @Nullable final Orientation orientation, final boolean movedByPiston)` | `net/minecraft/world/level/block/DiodeBlock.java:70-86` | Validates survival (drops item if floor broken) and triggers checkTickOnNeighbor | `state.canSurvive`, `this.checkTickOnNeighbor`, `dropResources`, `level.removeBlock`, `level.updateNeighborsAt` | `none` |
| `protected void checkTickOnNeighbor(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/DiodeBlock.java:88-103` | If unlocked and power state differs from shouldTurnOn, schedules tick with delay and priority (HIGH normally, VERY_HIGH if on, EXTREMELY_HIGH if prioritized) | `this.isLocked`, `this.shouldTurnOn`, `level.getBlockTicks().willTickThisTick`, `this.shouldPrioritize`, `this.getDelay`, `level.scheduleTick` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:825-833 (scheduleRepeaterCheck)` (partial: hardcoded priority logic) |
| `public boolean isLocked(final LevelReader level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/DiodeBlock.java:105-107` | Default returns false (overridden by RepeaterBlock) | none | `none` |
| `protected boolean shouldTurnOn(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/DiodeBlock.java:109-111` | Returns true if rear input signal > 0 | `this.getInputSignal(level, pos, state) > 0` | `none` |
| `protected int getInputSignal(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/DiodeBlock.java:113-123` | Queries rear signal via level.getSignal or directly from wire power | `state.getValue(FACING)`, `level.getSignal(targetPos, direction)`, `level.getBlockState`, `BlockState.is(Blocks.REDSTONE_WIRE)` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:835-851 (repeaterInput)` |
| `protected int getAlternateSignal(final SignalGetter level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/DiodeBlock.java:125-134` | Reads side inputs from clockwise and counter-clockwise directions | `state.getValue(FACING)`, `Direction.getClockWise`, `Direction.getCounterClockWise`, `level.getControlInputSignal` | `none` (repeater locking side reads are completely missing in Kestrel) |
| `protected boolean isSignalSource(final BlockState state)` | `net/minecraft/world/level/block/DiodeBlock.java:136-138` | Returns true unconditionally | none | `none` |
| `protected int ownSignal(final BlockState state, final BlockGetter level, final BlockPos pos)` | `net/minecraft/world/level/block/DiodeBlock.java:141-143` | Returns getOutputSignal if POWERED, else 0 | `state.getValue(POWERED)`, `this.getOutputSignal` | `none` |
| `protected int getDirectSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/DiodeBlock.java:146-148` | Strong direct signal equals weak signal (emits forward only) | `state.getSignal(level, pos, direction)` | `none` |
| `protected int getSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/DiodeBlock.java:151-153` | Emits signal forward out the front face only (`direction == FACING`) | `this.ownSignal(state, level, pos)` | `none` |
| `public BlockState getStateForPlacement(final BlockPlaceContext context)` | `net/minecraft/world/level/block/DiodeBlock.java:156-158` | Faces diode away from player placement angle | `this.defaultBlockState().setValue(FACING, opposite)` | `none` |
| `public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, @Nullable final LivingEntity by, final ItemStack itemStack)` | `net/minecraft/world/level/block/DiodeBlock.java:161-165` | If placed powered, schedules 1-tick initialization tick | `this.shouldTurnOn`, `level.scheduleTick(pos, this, 1)` | `none` |
| `protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston)` | `net/minecraft/world/level/block/DiodeBlock.java:168-170` | Updates neighbors in front | `this.updateNeighborsInFront(level, pos, state)` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:679-680 (placeFanOut)` |
| `protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston)` | `net/minecraft/world/level/block/DiodeBlock.java:173-177` | Updates neighbors in front when removed without piston move | `this.updateNeighborsInFront(level, pos, state)` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:660-661 (removalFanOut)` |
| `protected void updateNeighborsInFront(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/DiodeBlock.java:179-185` | Fires neighborChanged on front neighbor and updateNeighborsAtExceptFromFacing around front neighbor | `state.getValue(FACING)`, `ExperimentalRedstoneUtils.initialOrientation`, `level.neighborChanged`, `level.updateNeighborsAtExceptFromFacing` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:683-687 (diodeFront)` |
| `protected boolean sideInputDiodesOnly()` | `net/minecraft/world/level/block/DiodeBlock.java:187-189` | Returns false by default | none | `none` |
| `protected int getOutputSignal(final BlockGetter level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/DiodeBlock.java:191-193` | Returns 15 (overridden by ComparatorBlock) | none | `none` |
| `public static boolean isDiode(final BlockState state)` | `net/minecraft/world/level/block/DiodeBlock.java:195-197` | Returns true if block instanceof DiodeBlock | `state.getBlock() instanceof DiodeBlock` | `none` |
| `public boolean shouldPrioritize(final BlockGetter level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/DiodeBlock.java:199-203` | Returns true if rear block is a diode facing into this diode | `isDiode`, `BlockState.getValue(FACING)` | `none` |
| `protected abstract int getDelay(BlockState state)` | `net/minecraft/world/level/block/DiodeBlock.java:205` | Abstract tick delay calculation | none | `none` |

---

### 3.4 RepeaterBlock (`net/minecraft/world/level/block/RepeaterBlock.java`)

`RepeaterBlock` implements delay multiplier (1 to 4 delay settings = 2, 4, 6, 8 game ticks) and side locking from other repeaters or comparators.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<RepeaterBlock> codec()` | `net/minecraft/world/level/block/RepeaterBlock.java:28-30` | Returns codec for repeater | none | `none` |
| `protected RepeaterBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/RepeaterBlock.java:32-37` | Sets default state (FACING=NORTH, DELAY=1, LOCKED=false, POWERED=false) | `this.registerDefaultState` | `none` |
| `protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult)` | `net/minecraft/world/level/block/RepeaterBlock.java:40-49` | Player right-click cycles DELAY property (1->2->3->4->1) | `state.cycle(DELAY)`, `level.setBlock(pos, state, 3)` | `none` |
| `protected int getDelay(final BlockState state)` | `net/minecraft/world/level/block/RepeaterBlock.java:52-54` | Converts DELAY setting (1-4) to game ticks: `DELAY * 2` (2, 4, 6, or 8 ticks) | `state.getValue(DELAY) * 2` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:830` and `1174` |
| `public BlockState getStateForPlacement(final BlockPlaceContext context)` | `net/minecraft/world/level/block/RepeaterBlock.java:57-60` | Computes placement state with LOCKED resolved from side diodes | `super.getStateForPlacement`, `this.isLocked` | `none` |
| `protected BlockState updateShape(final BlockState state, final LevelReader level, final ScheduledTickAccess ticks, final BlockPos pos, final Direction directionToNeighbour, final BlockPos neighbourPos, final BlockState neighbourState, final RandomSource random)` | `net/minecraft/world/level/block/RepeaterBlock.java:63-80` | Drops if floor lost; updates LOCKED state immediately on side neighbor changes | `this.canSurviveOn`, `this.isLocked`, `BlockState.setValue(LOCKED, ...)` | `none` |
| `public boolean isLocked(final LevelReader level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/RepeaterBlock.java:83-85` | Evaluates getAlternateSignal > 0 (side diode power) | `this.getAlternateSignal(level, pos, state) > 0` | `none` (repeater locking is completely missing in Kestrel) |
| `protected boolean sideInputDiodesOnly()` | `net/minecraft/world/level/block/RepeaterBlock.java:88-90` | Returns true (repeaters lock only from diodes, not raw wire) | none | `none` |
| `public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/RepeaterBlock.java:93-109` | Renders redstone torch particle on repeater base | `Level.addParticle` | `none` (client only) |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/RepeaterBlock.java:112-114` | Registers FACING, DELAY, LOCKED, POWERED properties | `builder.add` | `none` |

---

### 3.5 LeverBlock (`net/minecraft/world/level/block/LeverBlock.java`)

`LeverBlock` provides manual latching redstone power: strong power 15 to the block attached to, and weak power 15 to all other 5 adjacent neighbors.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<LeverBlock> codec()` | `net/minecraft/world/level/block/LeverBlock.java:43-45` | Returns codec for lever | none | `none` |
| `protected LeverBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/LeverBlock.java:47-51` | Registers default state (NORTH, POWERED=false, WALL) | `this.registerDefaultState`, `this.makeShapes` | `none` |
| `private Function<BlockState, VoxelShape> makeShapes()` | `net/minecraft/world/level/block/LeverBlock.java:53-56` | Precomputes shapes for CEILING, FLOOR, and WALL orientations | `Shapes.rotateAttachFace`, `Block.boxZ` | `none` |
| `protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context)` | `net/minecraft/world/level/block/LeverBlock.java:59-61` | Returns directional shape | `Function.apply(state)` | `none` |
| `protected InteractionResult useWithoutItem(final BlockState stateBefore, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult)` | `net/minecraft/world/level/block/LeverBlock.java:64-77` | Handles player right click to flip lever state | `this.pull(stateBefore, level, pos, null)` | `none` |
| `protected void onExplosionHit(final BlockState state, final ServerLevel level, final BlockPos pos, final Explosion explosion, final BiConsumer<ItemStack, BlockPos> onHit)` | `net/minecraft/world/level/block/LeverBlock.java:80-88` | Flips lever if triggered by explosion | `this.pull` | `none` |
| `public void pull(BlockState state, final Level level, final BlockPos pos, @Nullable final Player player)` | `net/minecraft/world/level/block/LeverBlock.java:90-96` | Cycles POWERED, writes blockstate (flags=3), notifies neighbors, plays click sound, and fires game event | `BlockState.cycle(POWERED)`, `level.setBlock`, `this.updateNeighbours`, `playSound`, `level.gameEvent` | `none` (Kestrel sets lever state manually in harness setup) |
| `protected static void playSound(@Nullable final Player player, final LevelAccessor level, final BlockPos pos, final BlockState stateAfter)` | `net/minecraft/world/level/block/LeverBlock.java:98-101` | Plays click sound with pitch 0.6 (on) or 0.5 (off) | `level.playSound(..., SoundEvents.LEVER_CLICK, ...)` | `none` |
| `private static void makeParticle(final BlockState state, final LevelAccessor level, final BlockPos pos, final float scale)` | `net/minecraft/world/level/block/LeverBlock.java:103-110` | Spawns redstone dust particles when pulled | `LevelAccessor.addParticle` | `none` (client only) |
| `public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/LeverBlock.java:113-117` | Randomly spawns redstone particles while powered | `makeParticle` | `none` (client only) |
| `protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston)` | `net/minecraft/world/level/block/LeverBlock.java:120-124` | Fires updateNeighbours when powered lever is removed | `this.updateNeighbours(state, level, pos)` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:654-659 (removalFanOut)` |
| `protected int getDirectSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/LeverBlock.java:127-129` | Emits strong power 15 directly into the support block it is attached to (`getConnectedDirection == direction`) | `getConnectedDirection(state) == direction` | `none` |
| `protected boolean isSignalSource(final BlockState state)` | `net/minecraft/world/level/block/LeverBlock.java:132-134` | Returns true unconditionally | none | `none` |
| `protected int ownSignal(final BlockState state, final BlockGetter level, final BlockPos pos)` | `net/minecraft/world/level/block/LeverBlock.java:137-139` | Emits weak power 15 to all 6 adjacent positions when POWERED is true | `state.getValue(POWERED)` | `none` |
| `private void updateNeighbours(final BlockState state, final Level level, final BlockPos pos)` | `net/minecraft/world/level/block/LeverBlock.java:141-148` | Calls updateNeighborsAt on lever pos and on attached support block | `getConnectedDirection`, `ExperimentalRedstoneUtils.initialOrientation`, `level.updateNeighborsAt` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:803-806` (partial) |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/LeverBlock.java:151-153` | Registers FACE, FACING, POWERED properties | `builder.add` | `none` |

---

### 3.6 ButtonBlock (`net/minecraft/world/level/block/ButtonBlock.java`)

`ButtonBlock` provides momentary pulse signals: 20 ticks for stone buttons, 30 ticks for wooden buttons. Wooden buttons can also be activated by projectiles (arrows).

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<ButtonBlock> codec()` | `net/minecraft/world/level/block/ButtonBlock.java:57-59` | Returns codec for button | none | `none` |
| `protected ButtonBlock(final BlockSetType type, final int ticksToStayPressed, final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/ButtonBlock.java:61-67` | Registers default state and assigns ticksToStayPressed (20 stone, 30 wood) | `this.registerDefaultState`, `this.makeShapes` | `none` |
| `private Function<BlockState, VoxelShape> makeShapes()` | `net/minecraft/world/level/block/ButtonBlock.java:69-78` | Precomputes unpressed and pressed shapes | `Shapes.rotateAttachFace`, `Shapes.join` | `none` |
| `protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context)` | `net/minecraft/world/level/block/ButtonBlock.java:81-83` | Returns shape based on pressed state | `Function.apply(state)` | `none` |
| `protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult)` | `net/minecraft/world/level/block/ButtonBlock.java:86-95` | Player right-click presses unpowered button | `this.press(state, level, pos, player)` | `none` |
| `protected void onExplosionHit(final BlockState state, final ServerLevel level, final BlockPos pos, final Explosion explosion, final BiConsumer<ItemStack, BlockPos> onHit)` | `net/minecraft/world/level/block/ButtonBlock.java:98-106` | Presses button if hit by explosion | `this.press` | `none` |
| `public void press(final BlockState state, final Level level, final BlockPos pos, @Nullable final Player player)` | `net/minecraft/world/level/block/ButtonBlock.java:108-114` | Sets POWERED=true, updates neighbors, schedules deactivation tick with ticksToStayPressed, plays sound, fires game event | `level.setBlock(pos, state.setValue(POWERED, true), 3)`, `this.updateNeighbours`, `level.scheduleTick(pos, this, this.ticksToStayPressed)`, `this.playSound` | `none` |
| `protected void playSound(@Nullable final Player player, final LevelAccessor level, final BlockPos pos, final boolean pressed)` | `net/minecraft/world/level/block/ButtonBlock.java:116-118` | Plays click on/off sound from BlockSetType | `level.playSound` | `none` |
| `protected SoundEvent getSound(final boolean pressed)` | `net/minecraft/world/level/block/ButtonBlock.java:120-122` | Resolves sound event from BlockSetType | `this.type.buttonClickOn()`, `this.type.buttonClickOff()` | `none` |
| `protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston)` | `net/minecraft/world/level/block/ButtonBlock.java:125-129` | Updates neighbors if removed while pressed | `this.updateNeighbours(state, level, pos)` | `none` |
| `protected int getDirectSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/ButtonBlock.java:132-134` | Emits strong power 15 to attached block only | `state.getValue(POWERED) && getConnectedDirection(state) == direction` | `none` |
| `protected boolean isSignalSource(final BlockState state)` | `net/minecraft/world/level/block/ButtonBlock.java:137-139` | Returns true unconditionally | none | `none` |
| `protected int ownSignal(final BlockState state, final BlockGetter level, final BlockPos pos)` | `net/minecraft/world/level/block/ButtonBlock.java:142-144` | Emits weak power 15 to all 6 neighbors when pressed | `state.getValue(POWERED)` | `none` |
| `protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/ButtonBlock.java:147-151` | Scheduled tick handler checking if button should unpress or stay held by arrow | `this.checkPressed(state, level, pos)` | `none` |
| `protected void entityInside(final BlockState state, final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier, final boolean isPrecise)` | `net/minecraft/world/level/block/ButtonBlock.java:154-160` | Checks arrow intersection for wooden buttons | `this.type.canButtonBeActivatedByArrows()`, `this.checkPressed` | `none` |
| `protected void checkPressed(final BlockState state, final Level level, final BlockPos pos)` | `net/minecraft/world/level/block/ButtonBlock.java:162-177` | Evaluates arrow presence; toggles POWERED state and notifies neighbors; reschedules tick if arrow persists | `level.getEntitiesOfClass(AbstractArrow.class, ...)`, `level.setBlock`, `this.updateNeighbours`, `this.playSound`, `level.scheduleTick` | `none` |
| `private void updateNeighbours(final BlockState state, final Level level, final BlockPos pos)` | `net/minecraft/world/level/block/ButtonBlock.java:179-186` | Notifies button position and attached support block | `getConnectedDirection`, `ExperimentalRedstoneUtils.initialOrientation`, `level.updateNeighborsAt` | `none` |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/ButtonBlock.java:189-191` | Registers FACING, POWERED, FACE properties | `builder.add` | `none` |

---

### 3.7 BasePressurePlateBlock (`net/minecraft/world/level/block/BasePressurePlateBlock.java`)

`BasePressurePlateBlock` defines entity detection bounding boxes, downward strong signal emission, and press/unpress scheduled tick lifecycles.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `protected BasePressurePlateBlock(final BlockBehaviour.Properties properties, final BlockSetType type)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:31-34` | Initializes plate and sound type | `super(properties.sound(type.soundType()))` | `none` |
| `protected abstract MapCodec<? extends BasePressurePlateBlock> codec()` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:37` | Abstract codec declaration | none | `none` |
| `protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:40-42` | Returns 0.5-pixel high pressed shape or 1-pixel high unpressed shape | `this.getSignalForState(state) > 0` | `none` |
| `protected int getPressedTime()` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:44-46` | Returns 20 ticks delay for wooden/stone plates | none | `none` |
| `public boolean isPossibleToRespawnInThis(final BlockState state)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:49-51` | Returns true | none | `none` |
| `protected BlockState updateShape(final BlockState state, final LevelReader level, final ScheduledTickAccess ticks, final BlockPos pos, final Direction directionToNeighbour, final BlockPos neighbourPos, final BlockState neighbourState, final RandomSource random)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:54-67` | Drops plate if floor cannot support it | `state.canSurvive(level, pos)` | `none` |
| `protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:70-73` | Verifies support block has rigid top face or center support | `canSupportRigidBlock`, `canSupportCenter` | `none` |
| `protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:76-81` | Checks if plate should depress or stay pressed | `this.checkPressed(null, level, pos, state, signal)` | `none` |
| `protected void entityInside(final BlockState state, final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier, final boolean isPrecise)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:84-93` | Triggers checkPressed on entity collision | `this.checkPressed(entity, level, pos, state, signal)` | `none` |
| `private void checkPressed(@Nullable final Entity sourceEntity, final Level level, final BlockPos pos, final BlockState state, final int oldSignal)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:95-117` | Queries entity signal; if changed, writes blockstate (flags=2), notifies neighbors, plays sound, fires game event, and schedules deactivation tick | `this.getSignalStrength`, `this.setSignalForState`, `level.setBlock`, `this.updateNeighbours`, `level.playSound`, `level.scheduleTick` | `none` |
| `protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:120-124` | Updates neighbors if removed while emitting power | `this.updateNeighbours(level, pos)` | `none` |
| `protected void updateNeighbours(final Level level, final BlockPos pos)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:126-129` | Fires updateNeighborsAt on plate pos and on the floor block below | `level.updateNeighborsAt(pos, this)`, `level.updateNeighborsAt(pos.below(), this)` | `none` |
| `protected int getDirectSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:132-134` | Emits strong power downward into floor block only (`direction == UP`) | `direction == Direction.UP ? this.getSignalForState(state) : 0` | `none` |
| `protected boolean isSignalSource(final BlockState state)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:137-139` | Returns true unconditionally | none | `none` |
| `protected int ownSignal(final BlockState state, final BlockGetter level, final BlockPos pos)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:142-144` | Emits weak power to all 6 adjacent positions | `this.getSignalForState(state)` | `none` |
| `protected static int getEntityCount(final Level level, final AABB entityDetectionBox, final Class<? extends Entity> entityClass)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:146-148` | Queries entity count inside bounding box excluding spectators | `level.getEntitiesOfClass` | `none` |
| `protected abstract int getSignalStrength(Level level, BlockPos pos)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:150` | Abstract calculation of active signal strength | none | `none` |
| `protected abstract int getSignalForState(BlockState state)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:152` | Abstract extraction of power level from blockstate | none | `none` |
| `protected abstract BlockState setSignalForState(BlockState state, int signal)` | `net/minecraft/world/level/block/BasePressurePlateBlock.java:154` | Abstract application of power level to blockstate | none | `none` |

---

### 3.8 PressurePlateBlock (`net/minecraft/world/level/block/PressurePlateBlock.java`)

`PressurePlateBlock` implements binary 0 or 15 power for wooden (all entities) and stone (living mobs/players only) plates.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<PressurePlateBlock> codec()` | `net/minecraft/world/level/block/PressurePlateBlock.java:23-25` | Returns codec for pressure plate | none | `none` |
| `protected PressurePlateBlock(final BlockSetType type, final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/PressurePlateBlock.java:27-30` | Registers default state with POWERED=false | `this.registerDefaultState` | `none` |
| `protected int getSignalForState(final BlockState state)` | `net/minecraft/world/level/block/PressurePlateBlock.java:33-35` | Returns 15 if POWERED is true, else 0 | `state.getValue(POWERED)` | `none` |
| `protected BlockState setSignalForState(final BlockState state, final int signal)` | `net/minecraft/world/level/block/PressurePlateBlock.java:38-40` | Sets POWERED = (signal > 0) | `state.setValue(POWERED, signal > 0)` | `none` |
| `protected int getSignalStrength(final Level level, final BlockPos pos)` | `net/minecraft/world/level/block/PressurePlateBlock.java:43-49` | Checks entities in TOUCH_AABB: Entity.class for EVERYTHING, LivingEntity.class for MOBS; returns 15 if any present | `getEntityCount(level, TOUCH_AABB.move(pos), entityClass) > 0 ? 15 : 0` | `none` |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/PressurePlateBlock.java:52-54` | Registers POWERED property | `builder.add(POWERED)` | `none` |

---

### 3.9 WeightedPressurePlateBlock (`net/minecraft/world/level/block/WeightedPressurePlateBlock.java`)

`WeightedPressurePlateBlock` implements analog signal scaling: light/gold (up to 15 entities, 1 entity per signal level) and heavy/iron (up to 150 entities, 10 entities per signal level) with 10-tick delay.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<WeightedPressurePlateBlock> codec()` | `net/minecraft/world/level/block/WeightedPressurePlateBlock.java:30-32` | Returns codec for weighted plate | none | `none` |
| `protected WeightedPressurePlateBlock(final int maxWeight, final BlockSetType type, final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/WeightedPressurePlateBlock.java:34-38` | Registers default state with POWER=0 and stores maxWeight (15 or 150) | `this.registerDefaultState` | `none` |
| `protected int getSignalStrength(final Level level, final BlockPos pos)` | `net/minecraft/world/level/block/WeightedPressurePlateBlock.java:41-49` | Computes proportional signal `ceil((min(count, maxWeight) / maxWeight) * 15)` | `getEntityCount(level, TOUCH_AABB.move(pos), Entity.class)`, `Mth.ceil` | `none` |
| `protected int getSignalForState(final BlockState state)` | `net/minecraft/world/level/block/WeightedPressurePlateBlock.java:52-54` | Returns POWER property value (0-15) | `state.getValue(POWER)` | `none` |
| `protected BlockState setSignalForState(final BlockState state, final int signal)` | `net/minecraft/world/level/block/WeightedPressurePlateBlock.java:57-59` | Sets POWER property value | `state.setValue(POWER, signal)` | `none` |
| `protected int getPressedTime()` | `net/minecraft/world/level/block/WeightedPressurePlateBlock.java:62-64` | Returns 10 ticks delay | none | `none` |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/WeightedPressurePlateBlock.java:67-69` | Registers POWER property | `builder.add(POWER)` | `none` |

---

### 3.10 PoweredBlock (Redstone Block) (`net/minecraft/world/level/block/PoweredBlock.java`)

`PoweredBlock` is a constant signal source emitting weak power 15 to all 6 adjacent neighbors. It never emits direct strong power.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<PoweredBlock> codec()` | `net/minecraft/world/level/block/PoweredBlock.java:13-15` | Returns codec for powered block | none | `none` |
| `public PoweredBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/PoweredBlock.java:17-19` | Constructor | `super(properties)` | `none` |
| `protected boolean isSignalSource(final BlockState state)` | `net/minecraft/world/level/block/PoweredBlock.java:22-24` | Returns true unconditionally | none | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:711` |
| `protected int ownSignal(final BlockState state, final BlockGetter level, final BlockPos pos)` | `net/minecraft/world/level/block/PoweredBlock.java:27-29` | Returns 15 unconditionally (weak power to all 6 sides) | none | `none` |

---

### 3.11 RedstoneLampBlock (`net/minecraft/world/level/block/RedstoneLampBlock.java`)

`RedstoneLampBlock` is an instant-on (0-tick), delayed-off (4-tick = 2 redstone ticks) visual consumer emitting light level 15 when lit.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<RedstoneLampBlock> codec()` | `net/minecraft/world/level/block/RedstoneLampBlock.java:21-23` | Returns codec for redstone lamp | none | `none` |
| `public RedstoneLampBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/RedstoneLampBlock.java:25-28` | Registers default state with LIT=false | `this.registerDefaultState` | `none` |
| `public BlockState getStateForPlacement(final BlockPlaceContext context)` | `net/minecraft/world/level/block/RedstoneLampBlock.java:32-34` | Initializes LIT matching hasNeighborSignal on placement | `context.getLevel().hasNeighborSignal(context.getClickedPos())` | `none` |
| `protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, @Nullable final Orientation orientation, final boolean movedByPiston)` | `net/minecraft/world/level/block/RedstoneLampBlock.java:37-50` | Turn-on: sets LIT=true immediately (0-tick); Turn-off: schedules delayed deactivation tick with delay 4 | `level.hasNeighborSignal(pos)`, `level.setBlock(pos, state.cycle(LIT), 2)`, `level.scheduleTick(pos, this, 4)` | `none` (lamp logic is completely missing in Kestrel) |
| `protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/RedstoneLampBlock.java:53-57` | Turns off lamp (LIT=false, flags=2) if power is still absent after 4 ticks | `state.getValue(LIT)`, `level.hasNeighborSignal(pos)`, `level.setBlock(pos, state.cycle(LIT), 2)` | `none` |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/RedstoneLampBlock.java:60-62` | Registers LIT property | `builder.add(LIT)` | `none` |


---

## 4. Slice 3: Comparator and Observer

Slice 3 covers pulse-generation from block updates (observers) and dynamic analog signal processing (comparators).

### 4.1 ComparatorBlock (`net/minecraft/world/level/block/ComparatorBlock.java`)

`ComparatorBlock` evaluates rear inputs against side inputs in COMPARE mode (`rear >= side ? rear : 0`) or SUBTRACT mode (`max(0, rear - side)`), reads container filling ratios directly or through solid conductors, and stores its cached output in `ComparatorBlockEntity`.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<ComparatorBlock> codec()` | `net/minecraft/world/level/block/ComparatorBlock.java:36-38` | Returns codec for comparator | none | `none` |
| `public ComparatorBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/ComparatorBlock.java:40-43` | Registers default state (NORTH, POWERED=false, MODE=COMPARE) | `this.registerDefaultState` | `none` |
| `protected int getDelay(final BlockState state)` | `net/minecraft/world/level/block/ComparatorBlock.java:46-48` | Returns 2 game ticks (1 redstone tick) delay unconditionally | none | `none` |
| `public BlockState updateShape(final BlockState state, final LevelReader level, final ScheduledTickAccess ticks, final BlockPos pos, final Direction directionToNeighbour, final BlockPos neighbourPos, final BlockState neighbourState, final RandomSource random)` | `net/minecraft/world/level/block/ComparatorBlock.java:51-64` | Drops if floor is broken, otherwise delegates to DiodeBlock.updateShape | `this.canSurviveOn`, `super.updateShape` | `none` |
| `protected int getOutputSignal(final BlockGetter level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/ComparatorBlock.java:67-69` | Reads cached output signal (0-15) from ComparatorBlockEntity | `level.getBlockEntity(pos)`, `ComparatorBlockEntity.getOutputSignal` | `none` |
| `private int calculateOutputSignal(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/ComparatorBlock.java:71-83` | Computes arithmetic output: 0 if side > rear; else rear in COMPARE mode, (rear - side) in SUBTRACT mode | `this.getInputSignal`, `this.getAlternateSignal`, `state.getValue(MODE)` | `none` |
| `protected boolean shouldTurnOn(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/ComparatorBlock.java:86-94` | Returns true if rear > side (or rear == side in COMPARE mode with rear > 0) | `this.getInputSignal`, `this.getAlternateSignal`, `state.getValue(MODE)` | `none` |
| `protected int getInputSignal(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/ComparatorBlock.java:97-118` | Queries rear signal: inspects target block for analog output, or passes through solid conductor to read container/item frame behind it | `super.getInputSignal`, `BlockState.hasAnalogOutputSignal`, `BlockState.getAnalogOutputSignal`, `BlockState.isRedstoneConductor`, `this.getItemFrame` | `none` (conductor traversal and analog reads are completely missing in Kestrel) |
| `private ItemFrame getItemFrame(final Level level, final Direction direction, final BlockPos tPos)` | `net/minecraft/world/level/block/ComparatorBlock.java:120-128` | Searches for ItemFrame entity on target block facing given direction | `level.getEntitiesOfClass(ItemFrame.class, ...)` | `none` |
| `protected InteractionResult useWithoutItem(BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult)` | `net/minecraft/world/level/block/ComparatorBlock.java:131-145` | Toggles between COMPARE and SUBTRACT modes on player right click, plays click sound, and refreshes output | `state.cycle(MODE)`, `level.playSound`, `level.setBlock(pos, state, 2)`, `this.refreshOutputState` | `none` |
| `protected void checkTickOnNeighbor(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/ComparatorBlock.java:148-157` | If output signal or power state would change, schedules 2-tick recomputation (priority HIGH if prioritized, else NORMAL) | `level.getBlockTicks().willTickThisTick`, `this.calculateOutputSignal`, `this.shouldPrioritize`, `level.scheduleTick` | `none` |
| `private void refreshOutputState(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/ComparatorBlock.java:159-179` | Recalculates output, updates ComparatorBlockEntity, updates POWERED blockstate (flags=2), and updates front neighbors | `this.calculateOutputSignal`, `ComparatorBlockEntity.setOutputSignal`, `this.shouldTurnOn`, `level.setBlock`, `this.updateNeighborsInFront` | `none` |
| `protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/ComparatorBlock.java:182-184` | Scheduled tick handler executing refreshOutputState | `this.refreshOutputState(level, pos, state)` | `none` |
| `protected boolean triggerEvent(final BlockState state, final Level level, final BlockPos pos, final int b0, final int b1)` | `net/minecraft/world/level/block/ComparatorBlock.java:187-191` | Delegates block event to block entity | `level.getBlockEntity(pos).triggerEvent(b0, b1)` | `none` |
| `public BlockEntity newBlockEntity(final BlockPos worldPosition, final BlockState blockState)` | `net/minecraft/world/level/block/ComparatorBlock.java:194-196` | Instantiates new ComparatorBlockEntity | `new ComparatorBlockEntity(worldPosition, blockState)` | `none` |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/ComparatorBlock.java:199-201` | Registers FACING, MODE, POWERED properties | `builder.add` | `none` |

---

### 4.2 ComparatorBlockEntity (`net/minecraft/world/level/block/entity/ComparatorBlockEntity.java`)

`ComparatorBlockEntity` holds the cached integer signal strength (0-15) output by the comparator, persisted under NBT key `OutputSignal`.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public ComparatorBlockEntity(final BlockPos worldPosition, final BlockState blockState)` | `net/minecraft/world/level/block/entity/ComparatorBlockEntity.java:12-14` | Initializes block entity with BlockEntityTypes.COMPARATOR | `super(BlockEntityTypes.COMPARATOR, worldPosition, blockState)` | `none` |
| `protected void saveAdditional(final ValueOutput output)` | `net/minecraft/world/level/block/entity/ComparatorBlockEntity.java:17-20` | Serializes cached output signal to NBT key "OutputSignal" | `output.putInt("OutputSignal", this.output)` | `none` |
| `protected void loadAdditional(final ValueInput input)` | `net/minecraft/world/level/block/entity/ComparatorBlockEntity.java:23-26` | Deserializes cached output signal from NBT key "OutputSignal" | `input.getIntOr("OutputSignal", 0)` | `none` |
| `public int getOutputSignal()` | `net/minecraft/world/level/block/entity/ComparatorBlockEntity.java:28-30` | Returns cached output signal (0-15) | none | `none` |
| `public void setOutputSignal(final int value)` | `net/minecraft/world/level/block/entity/ComparatorBlockEntity.java:32-34` | Updates cached output signal | none | `none` |

---

### 4.3 ObserverBlock (`net/minecraft/world/level/block/ObserverBlock.java`)

`ObserverBlock` detects shape and state updates on its input face, emitting a 2-tick strong pulse of power 15 out its rear face with a 2-tick activation delay.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<ObserverBlock> codec()` | `net/minecraft/world/level/block/ObserverBlock.java:26-28` | Returns codec for observer | none | `none` |
| `public ObserverBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/ObserverBlock.java:30-33` | Registers default state (FACING=SOUTH, POWERED=false) | `this.registerDefaultState` | `none` |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/ObserverBlock.java:36-38` | Registers FACING and POWERED properties | `builder.add` | `none` |
| `protected BlockState rotate(final BlockState state, final Rotation rotation)` | `net/minecraft/world/level/block/ObserverBlock.java:41-43` | Rotates 3D FACING property | `Rotation.rotate` | `none` |
| `protected BlockState mirror(final BlockState state, final Mirror mirror)` | `net/minecraft/world/level/block/ObserverBlock.java:46-48` | Mirrors 3D FACING property | `Mirror.getRotation` | `none` |
| `protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random)` | `net/minecraft/world/level/block/ObserverBlock.java:51-60` | Deactivation: sets POWERED=false (flags=2); Activation: sets POWERED=true and immediately schedules 2-tick deactivation tick | `level.setBlock`, `level.scheduleTick(pos, this, 2)`, `this.updateNeighborsInFront` | `none` (observer simulation is completely missing in Kestrel) |
| `protected BlockState updateShape(final BlockState state, final LevelReader level, final ScheduledTickAccess ticks, final BlockPos pos, final Direction directionToNeighbour, final BlockPos neighbourPos, final BlockState neighbourState, final RandomSource random)` | `net/minecraft/world/level/block/ObserverBlock.java:63-78` | Triggers startSignal when observed face matches directionToNeighbour and observer is unpowered | `this.startSignal(level, ticks, pos)`, `super.updateShape` | `none` |
| `private void startSignal(final LevelReader level, final ScheduledTickAccess ticks, final BlockPos pos)` | `net/minecraft/world/level/block/ObserverBlock.java:80-84` | Schedules 2-tick activation tick if no tick currently pending | `ticks.getBlockTicks().hasScheduledTick(pos, this)`, `ticks.scheduleTick(pos, this, 2)` | `none` |
| `protected void updateNeighborsInFront(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/ObserverBlock.java:86-92` | Notifies rear output block: neighborChanged on opposite pos, and updateNeighborsAtExceptFromFacing around opposite pos | `state.getValue(FACING)`, `ExperimentalRedstoneUtils.initialOrientation`, `level.neighborChanged`, `level.updateNeighborsAtExceptFromFacing` | `none` |
| `protected boolean isSignalSource(final BlockState state)` | `net/minecraft/world/level/block/ObserverBlock.java:95-97` | Returns true unconditionally | none | `none` |
| `protected int ownSignal(final BlockState state, final BlockGetter level, final BlockPos pos)` | `net/minecraft/world/level/block/ObserverBlock.java:100-102` | Returns 15 if POWERED is true, else 0 | `state.getValue(POWERED)` | `none` |
| `protected int getDirectSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/ObserverBlock.java:105-107` | Strong direct signal equals weak signal (emits strong power rearward) | `state.getSignal(level, pos, direction)` | `none` |
| `protected int getSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction)` | `net/minecraft/world/level/block/ObserverBlock.java:110-112` | Emits power rearward only (`direction == FACING`) | `this.ownSignal(state, level, pos)` | `none` |
| `protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston)` | `net/minecraft/world/level/block/ObserverBlock.java:114-123` | If placed powered without scheduled tick, clears POWERED (flags=18) and updates rear | `level.getBlockTicks().hasScheduledTick`, `level.setBlock`, `this.updateNeighborsInFront` | `none` |
| `protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston)` | `net/minecraft/world/level/block/ObserverBlock.java:125-130` | If removed while powered with scheduled tick pending, updates rear with POWERED=false | `level.getBlockTicks().hasScheduledTick`, `this.updateNeighborsInFront` | `none` |
| `public BlockState getStateForPlacement(final BlockPlaceContext context)` | `net/minecraft/world/level/block/ObserverBlock.java:133-135` | Places observer facing toward the player's look vector | `this.defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite().getOpposite())` | `none` |


---

## 5. Slice 4: Pistons

Pistons are the most mechanically complex redstone component, involving quasi-connectivity, deferred block event queues (`triggerEvent`), recursive 12-block structure resolution (`PistonStructureResolver`), slime/honey adhesion, 2-tick moving block entities (`PistonMovingBlockEntity`), and sticky block dropping (event 2).

### 5.1 PistonBaseBlock (`net/minecraft/world/level/block/piston/PistonBaseBlock.java`)

`PistonBaseBlock` evaluates quasi-connectivity power in neighbor updates, enqueues block events (0=extend, 1=contract, 2=drop), and executes block movements within `triggerEvent`.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<PistonBaseBlock> codec()` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:59-61` | Returns codec for piston base block | none | `none` |
| `public PistonBaseBlock(final boolean isSticky, final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:63-68` | Registers default state (FACING=NORTH, EXTENDED=false) and sets isSticky | `this.registerDefaultState` | `none` |
| `protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:70-73` | Returns retracted full cube or directional extended base shape | `state.getValue(EXTENDED)`, `Shapes.block()` | `none` |
| `public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, @Nullable final LivingEntity by, final ItemStack itemStack)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:75-80` | Invokes checkIfExtend on placement | `this.checkIfExtend(level, pos, state)` | `none` |
| `protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, @Nullable final Orientation orientation, final boolean movedByPiston)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:82-89` | Invokes checkIfExtend when adjacent block updates | `this.checkIfExtend(level, pos, state)` | `none` (pistons are completely unhandled in Kestrel) |
| `protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:91-98` | Invokes checkIfExtend if newly placed and no block entity present | `this.checkIfExtend(level, pos, state)` | `none` |
| `public BlockState getStateForPlacement(final BlockPlaceContext context)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:100-102` | Faces piston away from player look direction with EXTENDED=false | `context.getNearestLookingDirection().getOpposite()` | `none` |
| `private void checkIfExtend(final Level level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:104-125` | Evaluates getNeighborSignal (QC); if extending, verifies pushability and calls blockEvent(0); if retracting, checks in-flight entity progress for event 2 (drop) vs event 1 (contract) | `this.getNeighborSignal`, `PistonStructureResolver.resolve`, `level.blockEvent`, `level.getBlockEntity`, `PistonMovingBlockEntity.getProgress` | `none` |
| `private boolean getNeighborSignal(final SignalGetter level, final BlockPos pos, final Direction pushDirection)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:127-148` | Evaluates quasi-connectivity: checks 5 non-push faces of pos, checks directly below pos, and checks all faces of pos.above() (except directly under pos.above()) | `level.hasSignal(pos.relative(direction), direction)`, `level.hasSignal(pos, Direction.DOWN)`, `level.hasSignal(above.relative(d), d)` | `none` (quasi-connectivity is completely missing in Kestrel) |
| `protected boolean triggerEvent(final BlockState state, final Level level, final BlockPos pos, final int b0, final int b1)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:150-222` | Block event dispatcher: b0=0 executes extension; b0=1 or 2 executes retraction (b0=2 drops sticky block); handles sounds, block entities, and game events | `this.getNeighborSignal`, `this.moveBlocks`, `level.setBlock`, `level.playSound`, `PistonMovingBlockEntity.finalTick`, `level.setBlockEntity`, `MovingPistonBlock.newMovingBlockEntity` | `none` |
| `public static boolean isPushable(final BlockState state, final Level level, final BlockPos pos, final Direction direction, final boolean allowDestroyable, final Direction connectionDirection)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:224-262` | Evaluates whether block at pos can be pushed/pulled: checks world height, immovable blocks (obsidian/bedrock), hardness (-1), PushReaction, extended state, and block entities | `Level.getMinY/getMaxY`, `WorldBorder.isWithinBounds`, `BlockState.isAir`, `BlockState.getDestroySpeed`, `BlockState.getPistonPushReaction`, `BlockState.hasBlockEntity` | `none` |
| `private boolean moveBlocks(final Level level, final BlockPos pistonPos, final Direction direction, final boolean extending)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:264-365` | Solves structure via resolver; destroys toDestroy blocks; places MOVING_PISTON with PistonMovingBlockEntity at destination positions; clears source positions to air; updates neighbors | `PistonStructureResolver.resolve`, `dropResources`, `level.setBlock`, `level.setBlockEntity`, `MovingPistonBlock.newMovingBlockEntity`, `state.updateIndirectNeighbourShapes`, `level.updateNeighborsAt` | `none` |
| `protected BlockState rotate(final BlockState state, final Rotation rotation)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:367-370` | Rotates 3D FACING property | `Rotation.rotate` | `none` |
| `protected BlockState mirror(final BlockState state, final Mirror mirror)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:372-375` | Mirrors 3D FACING property | `Mirror.getRotation` | `none` |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:377-380` | Registers FACING and EXTENDED properties | `builder.add` | `none` |
| `protected boolean useShapeForLightOcclusion(final BlockState state)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:382-385` | Returns EXTENDED property value | `state.getValue(EXTENDED)` | `none` |
| `protected boolean isPathfindable(final BlockState state, final PathComputationType type)` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java:387-389` | Returns false unconditionally | none | `none` |

---

### 5.2 PistonStructureResolver (`net/minecraft/world/level/block/piston/PistonStructureResolver.java`)

`PistonStructureResolver` calculates the blocks to push and destroy, enforcing the 12-block push limit and slime/honey stickiness rules.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public PistonStructureResolver(final Level level, final BlockPos pistonPos, final Direction direction, final boolean extending)` | `net/minecraft/world/level/block/piston/PistonStructureResolver.java:23-35` | Computes startPos and pushDirection based on extending vs retracting | `BlockPos.relative` | `none` |
| `public boolean resolve()` | `net/minecraft/world/level/block/piston/PistonStructureResolver.java:37-60` | Main entry point: checks initial block pushability, calls addBlockLine, and branches into sticky neighbor blocks; returns false if push fails | `PistonBaseBlock.isPushable`, `this.addBlockLine`, `isSticky`, `this.addBranchingBlocks` | `none` |
| `private static boolean isSticky(final BlockState state)` | `net/minecraft/world/level/block/piston/PistonStructureResolver.java:62-64` | Returns true for slime or honey blocks | `state.is(Blocks.SLIME_BLOCK)`, `state.is(Blocks.HONEY_BLOCK)` | `none` |
| `private static boolean canStickToEachOther(final BlockState state1, final BlockState state2)` | `net/minecraft/world/level/block/piston/PistonStructureResolver.java:66-72` | Returns true if either is sticky, except slime and honey never stick to each other | `isSticky(state1)`, `isSticky(state2)` | `none` |
| `private boolean addBlockLine(final BlockPos start, final Direction direction)` | `net/minecraft/world/level/block/piston/PistonStructureResolver.java:74-148` | Recursively walks line of pushed blocks; tracks push depth (max 12); handles sticky chain continuation and collision reordering | `Level.getBlockState`, `PistonBaseBlock.isPushable`, `canStickToEachOther`, `this.reorderListAtCollision`, `this.addBranchingBlocks` | `none` |
| `private void reorderListAtCollision(final int blocksAdded, final int collisionPos)` | `net/minecraft/world/level/block/piston/PistonStructureResolver.java:150-161` | Rearranges toPush list when branching sticky blocks meet an existing push line | `List.subList`, `List.addAll`, `List.clear` | `none` |
| `private boolean addBranchingBlocks(final BlockPos fromPos)` | `net/minecraft/world/level/block/piston/PistonStructureResolver.java:163-176` | Checks 4 orthogonal directions from a sticky block and attempts to push connected clusters | `canStickToEachOther`, `this.addBlockLine` | `none` |
| `public Direction getPushDirection()` | `net/minecraft/world/level/block/piston/PistonStructureResolver.java:178-180` | Returns pushDirection vector | none | `none` |
| `public List<BlockPos> getToPush()` | `net/minecraft/world/level/block/piston/PistonStructureResolver.java:182-184` | Returns list of blocks to be moved as MOVING_PISTON | none | `none` |
| `public List<BlockPos> getToDestroy()` | `net/minecraft/world/level/block/piston/PistonStructureResolver.java:186-188` | Returns list of blocks destroyed by piston (flowers, torches, etc.) | none | `none` |

---

### 5.3 MovingPistonBlock (`net/minecraft/world/level/block/piston/MovingPistonBlock.java`)

`MovingPistonBlock` is the technical placeholder block occupying positions where blocks are in flight.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<MovingPistonBlock> codec()` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:44-46` | Returns codec for moving piston block | none | `none` |
| `public MovingPistonBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:48-51` | Registers default state (FACING=NORTH, TYPE=DEFAULT) | `this.registerDefaultState` | `none` |
| `public BlockEntity newBlockEntity(final BlockPos worldPosition, final BlockState blockState)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:55-57` | Returns null (entities are instantiated via newMovingBlockEntity helper) | none | `none` |
| `public static BlockEntity newMovingBlockEntity(final BlockPos position, final BlockState blockState, final BlockState movedState, final Direction direction, final boolean extending, final boolean isSourcePiston)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:59-68` | Instantiates new PistonMovingBlockEntity with moved state and movement vectors | `new PistonMovingBlockEntity(...)` | `none` |
| `public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final Level level, final BlockState blockState, final BlockEntityType<T> type)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:72-74` | Binds PistonMovingBlockEntity::tick as block entity ticker | `createTickerHelper(type, BlockEntityTypes.PISTON, PistonMovingBlockEntity::tick)` | `none` |
| `public void destroy(final LevelAccessor level, final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:77-83` | Destroys base piston block if moving piston placeholder is broken | `level.getBlockState`, `level.removeBlock` | `none` |
| `protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:86-95` | Removes ghost moving piston if clicked when block entity is missing | `level.removeBlock(pos, false)` | `none` |
| `protected List<ItemStack> getDrops(final BlockState state, final LootParams.Builder params)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:98-101` | Returns loot drops of the underlying moved block state | `entity.getMovedState().getDrops(params)` | `none` |
| `protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:104-106` | Returns empty shape for selection raycasts | `Shapes.empty()` | `none` |
| `protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:109-112` | Delegates collision shape to PistonMovingBlockEntity interpolated collision | `entity.getCollisionShape(level, pos)` | `none` |
| `private PistonMovingBlockEntity getBlockEntity(final BlockGetter level, final BlockPos pos)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:115-117` | Helper casting block entity at pos to PistonMovingBlockEntity | `level.getBlockEntity(pos)` | `none` |
| `protected RenderShape getRenderShape(final BlockState state)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:120-122` | Returns RenderShape.INVISIBLE (rendered dynamically by entity renderer) | none | `none` |
| `protected ItemStack getCloneItemStack(final LevelReader level, final BlockPos pos, final BlockState state, final boolean includeData)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:125-127` | Returns ItemStack.EMPTY | none | `none` |
| `protected BlockState rotate(final BlockState state, final Rotation rotation)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:130-132` | Rotates 3D FACING property | `Rotation.rotate` | `none` |
| `protected BlockState mirror(final BlockState state, final Mirror mirror)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:134-137` | Mirrors 3D FACING property | `Mirror.getRotation` | `none` |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:140-142` | Registers FACING and TYPE properties | `builder.add` | `none` |
| `protected boolean isPathfindable(final BlockState state, final PathComputationType type)` | `net/minecraft/world/level/block/piston/MovingPistonBlock.java:145-147` | Returns false unconditionally | none | `none` |

---

### 5.4 PistonMovingBlockEntity (`net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java`)

`PistonMovingBlockEntity` executes 2-tick block movement interpolation (+0.5 progress per tick), moves/crushes entities in its path, and places the final settled blockstate on completion (`finalTick`).

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public PistonMovingBlockEntity(final BlockPos worldPosition, final BlockState blockState)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:50-52` | Constructor initializing entity type | `super(BlockEntityTypes.PISTON, ...)` | `none` |
| `public PistonMovingBlockEntity(final BlockPos worldPosition, final BlockState blockState, final BlockState movedState, final Direction direction, final boolean extending, final boolean isSourcePiston)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:54-68` | Main constructor storing moved blockstate, direction, and extension flags | `this(worldPosition, blockState)` | `none` |
| `public CompoundTag getUpdateTag(final HolderLookup.Provider registries)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:70-72` | Returns network update NBT tag | `this.saveCustomOnly` | `none` |
| `public boolean isExtending()` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:74-76` | Returns true if extending, false if retracting | none | `none` |
| `public Direction getDirection()` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:78-80` | Returns movement direction | none | `none` |
| `public boolean isSourcePiston()` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:82-84` | Returns true if this is the piston head itself | none | `none` |
| `public float getProgress(float a)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:86-92` | Returns linear interpolation of progress between progressO and progress | `Mth.lerp` | `none` |
| `public float getXOff(final float a)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:94-96` | Returns X offset float | `direction.getStepX() * getExtendedProgress(...)` | `none` |
| `public float getYOff(final float a)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:98-100` | Returns Y offset float | `direction.getStepY() * getExtendedProgress(...)` | `none` |
| `public float getZOff(final float a)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:102-104` | Returns Z offset float | `direction.getStepZ() * getExtendedProgress(...)` | `none` |
| `private float getExtendedProgress(final float progress)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:106-108` | Returns progress or `progress - 1.0F` based on extending flag | none | `none` |
| `private BlockState getCollisionRelatedBlockState()` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:110-118` | Returns piston head blockstate if extending source, else movedState | none | `none` |
| `private static void moveCollidedEntities(final Level level, final BlockPos pos, final float newProgress, final PistonMovingBlockEntity self)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:120-190` | Pushes and crushes entities colliding with moving block AABB | `level.getEntities`, `moveEntityByPiston`, `fixEntityWithinPistonBase` | `none` |
| `private static void moveEntityByPiston(final Direction pistonDirection, final Entity entity, final double delta, final Direction movement)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:192-199` | Moves entity along movement vector and applies piston bounce/damping | `entity.move(MoverType.PISTON, ...)` | `none` |
| `private static void moveStuckEntities(final Level level, final BlockPos pos, final float newProgress, final PistonMovingBlockEntity self)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:201-214` | Pulls entities resting on sticky (slime/honey) moving blocks | `self.isStickyForEntities`, `level.getEntities`, `matchesStickyCritera` | `none` |
| `private static boolean matchesStickyCritera(final AABB aabb, final Entity entity, final BlockPos pos)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:216-220` | Checks if entity AABB intersects top surface of moving block | none | `none` |
| `private boolean isStickyForEntities()` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:222-224` | Returns true if movedState is slime or honey | `movedState.is(Blocks.SLIME_BLOCK)` | `none` |
| `public Direction getMovementDirection()` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:226-228` | Returns pushDirection if extending, opposite if retracting | none | `none` |
| `private static double getMovement(final AABB aabbToBeOutsideOf, final Direction movement, final AABB aabb)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:230-246` | Calculates distance required to push entity outside moving block bounding box | none | `none` |
| `private static AABB moveByPositionAndProgress(final BlockPos pos, final AABB aabb, final PistonMovingBlockEntity entity)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:248-255` | Offsets AABB by pos and interpolated progress | `aabb.move(...)` | `none` |
| `private static void fixEntityWithinPistonBase(final BlockPos pos, final Entity entity, final Direction direction, final double deltaProgress)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:257-269` | Corrects entity penetration inside piston base block | none | `none` |
| `public BlockState getMovedState()` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:271-273` | Returns original BlockState being transported | none | `none` |
| `public void finalTick()` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:275-297` | Forces completion (progress=1.0); removes block entity; places final settled blockstate (or air for arm) via updateFromNeighbourShapes (flags=3); fires neighborChanged | `Level.removeBlockEntity`, `Block.updateFromNeighbourShapes`, `Level.setBlock`, `Level.neighborChanged`, `ExperimentalRedstoneUtils.initialOrientation` | `none` (critical for 0-tick and 1-tick piston mechanics) |
| `public void preRemoveSideEffects(final BlockPos pos, final BlockState state)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:299-301` | Invokes finalTick when block entity is destroyed early | `this.finalTick()` | `none` |
| `public Direction getPushDirection()` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:303-305` | Returns direction if extending, opposite if retracting | none | `none` |
| `public static void tick(final Level level, final BlockPos pos, final BlockState state, final PistonMovingBlockEntity entity)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:307-341` | Ticks entity in Phase 8: advances progress by 0.5; moves entities; when progress >= 1.0, removes entity and places final blockstate (flags=67) with neighbor updates | `entity.progress += 0.5F`, `moveCollidedEntities`, `moveStuckEntities`, `level.removeBlockEntity`, `Block.updateFromNeighbourShapes`, `level.setBlock`, `level.neighborChanged` | `none` (TickLoop currently does no-op sleepTick in Phase 8) |
| `protected void loadAdditional(final ValueInput input)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:343-352` | Deserializes blockState, facing, progress, extending, source from NBT | `input.read`, `input.getFloatOr`, `input.getBooleanOr` | `none` |
| `protected void saveAdditional(final ValueOutput output)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:354-361` | Serializes blockState, facing, progress, extending, source to NBT | `output.store`, `output.putFloat`, `output.putBoolean` | `none` |
| `public VoxelShape getCollisionShape(final BlockGetter level, final BlockPos pos)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:363-391` | Computes dynamic VoxelShape shifted by progress | `Shapes.create`, `VoxelShape.move` | `none` |
| `public long getLastTicked()` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:393-395` | Returns world gameTime when this entity last ticked | none | `none` |
| `public void setLevel(final Level level)` | `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:398-401` | Updates level reference and synchronizes lastTicked | `super.setLevel`, `level.getGameTime` | `none` |

---

### 5.5 PistonHeadBlock (`net/minecraft/world/level/block/piston/PistonHeadBlock.java`)

`PistonHeadBlock` represents the extended piston arm and head platform, forwarding neighbor updates and destruction events back to the base piston.

| Method (exact signature) | vanilla file:line range | What it does in one line | Calls into (other vanilla methods it depends on) | Kestrel today (file + method that already covers it, or "none") |
|---|---|---|---|---|
| `public MapCodec<PistonHeadBlock> codec()` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:45-47` | Returns codec for piston head | none | `none` |
| `public PistonHeadBlock(final BlockBehaviour.Properties properties)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:49-52` | Registers default state (NORTH, DEFAULT, SHORT=false) | `this.registerDefaultState` | `none` |
| `protected boolean useShapeForLightOcclusion(final BlockState state)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:54-57` | Returns true unconditionally | none | `none` |
| `protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:59-62` | Returns composite shape for platform plus arm rod | `state.getValue(SHORT)`, `SHAPES_SHORT`, `SHAPES` | `none` |
| `private boolean isFittingBase(final BlockState armState, final BlockState potentialBase)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:64-67` | Returns true if potentialBase matches piston type, is EXTENDED, and shares same FACING | `potentialBase.is`, `potentialBase.getValue(EXTENDED)`, `potentialBase.getValue(FACING)` | `none` |
| `public BlockState playerWillDestroy(final Level level, final BlockPos pos, final BlockState state, final Player player)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:69-79` | When player in creative destroys head, destroys base piston without drops | `this.isFittingBase`, `level.destroyBlock(basePos, false)` | `none` |
| `protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:81-87` | When head is broken in survival, destroys base piston with item drops | `this.isFittingBase`, `level.destroyBlock(basePos, true)` | `none` |
| `protected BlockState updateShape(final BlockState state, final LevelReader level, final ScheduledTickAccess ticks, final BlockPos pos, final Direction directionToNeighbour, final BlockPos neighbourPos, final BlockState neighbourState, final RandomSource random)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:89-103` | Drops head if base piston behind it is broken | `state.canSurvive`, `Blocks.AIR.defaultBlockState` | `none` |
| `protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:105-109` | Checks if base position holds matching extended piston or moving piston | `this.isFittingBase`, `base.is(Blocks.MOVING_PISTON)` | `none` |
| `protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, @Nullable final Orientation orientation, final boolean movedByPiston)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:111-120` | Forwards neighbor updates from head to base piston behind it | `state.canSurvive`, `level.neighborChanged(basePos, block, orientation)` | `none` |
| `protected ItemStack getCloneItemStack(final LevelReader level, final BlockPos pos, final BlockState state, final boolean includeData)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:122-125` | Pick-block returns sticky piston or regular piston item | `new ItemStack(isSticky ? STICKY_PISTON : PISTON)` | `none` |
| `protected BlockState rotate(final BlockState state, final Rotation rotation)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:127-130` | Rotates 3D FACING property | `Rotation.rotate` | `none` |
| `protected BlockState mirror(final BlockState state, final Mirror mirror)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:132-135` | Mirrors 3D FACING property | `Mirror.getRotation` | `none` |
| `protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:137-140` | Registers FACING, TYPE, SHORT properties | `builder.add` | `none` |
| `protected boolean isPathfindable(final BlockState state, final PathComputationType type)` | `net/minecraft/world/level/block/piston/PistonHeadBlock.java:142-145` | Returns false unconditionally | none | `none` |


## 6. Order of work

### Slice 1: Redstone Dust
Port the signal and neighbor update query core first, followed by wire propagation:
1. Query and update infrastructure: `SignalGetter.getSignal`, `getDirectSignal`, `hasSignal`, `getBestNeighborSignal`, and `CollectingNeighborUpdater.neighborChanged` / `shapeUpdated`.
2. Evaluator and Wire: `DefaultRedstoneWireEvaluator.updatePowerStrength`, `calculateTargetStrength`, and `RedStoneWireBlock.updatePowerStrength`, `onPlace`, `affectNeighborsAfterRemoval`, `neighborChanged`, `getSignal`, `getDirectSignal`.
- Gating fixtures: `harness/corpus/redstone/oracle/wire_falloff_16.txt`, `wire_branching_t.txt`, `wire_stair_climb.txt`, `wire_stair_occlusion.txt`, `update_order_tie.txt`.

### Slice 2: Redstone Torch, Repeater, and Simple Sources/Consumers
Port scheduled tick draining and constant sources first, then toggle and delay devices:
1. Infrastructure and static sources: `LevelTicks` scheduled tick priority drain and `PoweredBlock.getSignal`.
2. Manual inputs: `LeverBlock.useWithoutItem` / `getSignal`, `ButtonBlock.useWithoutItem` / `tick`, and `BasePressurePlateBlock.tick`.
3. Inverters and Diodes: `RedstoneTorchBlock` / `RedstoneWallTorchBlock` (`neighborChanged`, `tick`, `isToggledTooFrequently`), `DiodeBlock` base, and `RepeaterBlock` (`neighborChanged`, `tick`, `updateShape`, `isLocked`).
4. Consumers: `RedstoneLampBlock.neighborChanged` and `tick`.
- Gating fixtures: `harness/corpus/redstone/oracle/torch_inversion.txt`, `torch_tower.txt`, `torch_burnout_clock.txt`, `repeater_delays.txt`, `repeater_locking.txt`, `repeater_pulse_stretch.txt`, `repeater_priority_drain.txt`, `lever_power_types.txt`, `button_stone_duration.txt`, `button_wood_duration.txt`, `lamp_timings.txt`.

### Slice 3: Comparator and Observer
Port block entity state storage first, then analog comparator logic and pulse detection:
1. Block Entity: `ComparatorBlockEntity.getOutputSignal` and `setOutputSignal`.
2. Comparator: `ComparatorBlock.calculateOutputSignal`, `shouldTurnOn`, `refreshOutputState`, `neighborChanged`, `tick`, and `getSignal`.
3. Observer: `ObserverBlock.updateShape`, `tick`, `neighborChanged`, and `getSignal`.
- Gating fixtures: `harness/corpus/redstone/oracle/comparator_compare.txt`, `comparator_subtract.txt`, `comparator_container.txt`, `comparator_through_block.txt`, `observer_single_pulse.txt`, `observer_clock.txt`.

### Slice 4: Pistons
Port block event queuing first, then tree resolution, motion execution, and head blocks:
1. Event Queue: `ServerLevel.blockEvent` and `runBlockEvents` (FIFO dispatch phase in tick loop).
2. Structure Solver: `PistonStructureResolver.resolve`, `addBlockLine`, and `addBranchingBlocks`.
3. Base Block: `PistonBaseBlock.checkIfExtend`, `getNeighborSignal` (quasi-connectivity), `triggerEvent`, and `moveBlocks`.
4. Moving Entity: `MovingPistonBlock` and `PistonMovingBlockEntity.tick`, `finalTick`, and `moveCollidedEntities`.
5. Head Block: `PistonHeadBlock.updateShape` and `neighborChanged`.
- Gating fixtures: `harness/corpus/redstone/oracle/piston_qc_bud.txt`, `piston_push_limit_12.txt`, `piston_block_events_fifo.txt`, `piston_slime_honey_split.txt`, `piston_block_drop.txt`.
