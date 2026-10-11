# Roadmap Item G12: Redstone Simulation Pre-Research

Status: PRE-RESEARCH, grounded in the decompiled vanilla 26.2 server jar (`server-26.2.jar`) decompiled via Vineflower.
Target: Ground truth analysis for roadmap item G12 (redstone gameplay) in Kestrel.
Scope: No Java changes, no builds, no servers. Pure specification analysis and architecture gap analysis.

> Status update (2026-09-25, main = `08fe226`). The work this document specifies
> has LANDED. Slices 1 to 5 plus the two coverage-gap fixtures merged on
> 2026-09-25: slice 1 (wire, neighbor updater, signals, diodes) in `024ed43`,
> slices 2 to 4 (comparators, torch burnout, observers, lamps) in `3f0f7a3`,
> slice 5 (pistons, the block-event queue, moving pistons; this delivers
> section 9's slices 5 and 6 together) in `db4aff8`, and the two gap fixtures in
> `8091127`. `RedstoneParityCheck` reports 29/29 exact against the 29 vanilla
> oracle fixtures in `harness/corpus/redstone/oracle/`.
>
> Read sections 1 to 4 and 6 to 8 as the vanilla ground truth they are. Read
> section 5 ("Kestrel Today") and section 9 ("Recommended Implementation
> Order") as the pre-implementation snapshot and the order that was then
> followed, not as open work.
>
> Still NOT built, and the largest remaining G12 gap: the section 1.3 secondary
> components (target block, daylight detector, tripwire and hook, note block,
> doors, trapdoors, fence gates, dispenser, dropper, TNT, rails; most need
> entities or containers, S14, G17, G28), plus pressure plates from section
> 1.2. The live coverage list is the class javadoc of
> `core/src/vanilla/java/dev/kestrel/redstone/BlockLogic.java`.

---

## 1. Scope and Component List

Redstone in Minecraft 26.2 operates as a hybrid discrete-event and immediate-dispatch simulation. Signal transmission through redstone dust is evaluated instantaneously, whereas active logic elements (repeaters, comparators, torches, observers) schedule deferred state transitions through the scheduled-tick queue (`ServerLevel.blockTicks`), and mechanical elements (pistons) execute deferred phase changes through block events (`ServerLevel.blockEvents`) and moving block entities (`PistonMovingBlockEntity`).

### 1.1 Signal Power Model: Strong vs Weak Power

Vanilla formalizes signal querying in `net.minecraft.world.level.SignalGetter` (`SignalGetter.java:10-106`) and `net.minecraft.world.level.block.state.BlockBehaviour` (`BlockBehaviour.java:370-385, 632-655`):

- **Strong Power (Direct Signal):** Queried via `SignalGetter.getDirectSignal(pos, direction)` (`SignalGetter.java:13-15`), delegating to `BlockState.getDirectSignal` (`BlockBehaviour.java:652-654`). Strong power is emitted by signal sources directly into an adjacent block. If the receiving neighbor is an opaque solid block (`state.isRedstoneConductor`), that conductor block is energized and subsequently emits weak power to all its neighbors.
- **Weak Power (Indirect Signal):** Queried via `SignalGetter.getSignal(pos, direction)` (`SignalGetter.java:65-69`). A block's weak signal equals `state.getSignal(level, pos, direction)`, but if the block is a redstone conductor, it returns `Math.max(signal, this.getDirectSignalTo(pos))` (`SignalGetter.java:68`). Weak power can activate redstone consumers directly adjacent to the conductor, but cannot energize a second conductor block (power does not propagate through two solid blocks in series).
- **Direction Convention:** In `SignalGetter.getSignal(neighborPos, direction)` and `SignalGetter.getDirectSignal(neighborPos, direction)`, the `direction` argument is the vector from the querying block toward the signal source (`neighborPos = pos.relative(direction)` in `SignalGetter.java:94`). Thus, from the signal source's perspective, `direction.getOpposite()` is the direction of emission.
- **Conductor Definition:** `BlockBehaviour.BlockStateBase.isRedstoneConductor` (`BlockBehaviour.java:620-622`) checks `state.isCollisionShapeFullBlock(level, pos) && state.canOcclude()`. Transparent blocks, slabs, stairs, glass, hoppers, and air are non-conductors.

| Component | Direct Signal (Strong) | Indirect Signal (Weak) | Source File Citation |
|---|---|---|---|
| Redstone Wire | Downward to floor block only (`direction == UP`) | Horizontal connected directions and downward (`direction != DOWN`) | `RedStoneWireBlock.java:362-381` |
| Redstone Torch | Upward into block above only (`direction == DOWN`) | 5 directions (above, N, S, E, W; none below: `direction != UP`) | `RedstoneTorchBlock.java:99-116` |
| Redstone Wall Torch | Upward into block above only (`direction == DOWN`) | 5 directions (above, below, 3 horizontal; not attached wall) | `RedstoneWallTorchBlock.java:89-101` |
| Repeater | Forward into front block only (`direction == FACING`) | Forward only (`direction == FACING`, 15 if powered) | `DiodeBlock.java:147-154` |
| Comparator | Forward into front block only (`direction == FACING`) | Forward only (`direction == FACING`, dynamic output) | `DiodeBlock.java:147-154`, `ComparatorBlock.java:67-69` |
| Observer | Rear output block only (`direction == FACING`) | Rear output block only (`direction == FACING`, 15 for 2 ticks) | `ObserverBlock.java:105-112` |
| Lever | Attached support block only (`getConnectedDirection == direction`) | All 6 adjacent directions (15 if powered) | `LeverBlock.java:127-139` |
| Button | Attached support block only (`getConnectedDirection == direction`) | All 6 adjacent directions (15 if pressed) | `ButtonBlock.java:132-144` |
| Pressure Plate | Downward floor block only (`direction == UP`) | All 6 adjacent directions (15 if pressed) | `BasePressurePlateBlock.java:132-134` |
| Redstone Block | None (`getDirectSignal` returns 0) | All 6 adjacent directions (15 unconditionally) | `PoweredBlock.java:22-29` |
| Target Block | None (`getDirectSignal` returns 0) | All 6 adjacent directions (0 to 15 based on projectile hit) | `TargetBlock.java:92-99` |
| Tripwire Hook | Attached support block only (`direction == FACING`) | All 6 adjacent directions (15 if powered) | `TripWireHookBlock.java:247-257` |

### 1.2 Core Components in Detail

#### 1. Redstone Dust (Wire)
- **Vanilla Classes:** `net.minecraft.world.level.block.RedStoneWireBlock` (`RedStoneWireBlock.java:1-525`), `net.minecraft.world.level.redstone.RedstoneWireEvaluator` (`RedstoneWireEvaluator.java:1-49`), `net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator` (`DefaultRedstoneWireEvaluator.java:1-45`).
- **Signal Calculation:** Wire power is stored in `BlockStateProperties.POWER` (0 to 15). Target power is `max(externalSignal, incomingWireSignal - 1)` (`DefaultRedstoneWireEvaluator.java:40-43`). External signal is queried via `RedStoneWireBlock.getBlockSignal` (`RedStoneWireBlock.java:284-289`), which temporarily sets `shouldSignal = false` to prevent wire feedback while invoking `level.getBestNeighborSignal(pos)`. Incoming wire signal inspects 4 horizontal neighbors, plus ascending wires over non-conducting blocks and descending wires over non-conducting floor blocks (`RedstoneWireEvaluator.java:29-47`).
- **Update Trigger:** `neighborChanged` (`RedStoneWireBlock.java:342-355`) checks `state.canSurvive` (drops if floor lost) and calls `updatePowerStrength`. `onPlace` (`RedStoneWireBlock.java:302-312`) and `affectNeighborsAfterRemoval` (`RedStoneWireBlock.java:315-324`) execute neighbor updates across vertical and horizontal planes.
- **Scheduled Delay and Priority:** None. Wire power updates execute synchronously within the calling tick or neighbor dispatch.
- **Block Entity:** None.
- **Randomness:** None during simulation (particle display in `animateTick` is client-side only).

#### 2. Redstone Torch and Wall Torch
- **Vanilla Classes:** `net.minecraft.world.level.block.RedstoneTorchBlock` (`RedstoneTorchBlock.java:1-167`), `net.minecraft.world.level.block.RedstoneWallTorchBlock` (`RedstoneWallTorchBlock.java:1-114`).
- **Signal Calculation:** Emits power 15 when `LIT == true`. Standing torch checks `level.hasSignal(pos.below(), Direction.DOWN)` (`RedstoneTorchBlock.java:63-65`). Wall torch checks `level.hasSignal(pos.relative(opposite), opposite)` where `opposite = state.getValue(FACING).getOpposite()` (`RedstoneWallTorchBlock.java:83-86`).
- **Update Trigger:** `neighborChanged` (`RedstoneTorchBlock.java:90-96`) compares `state.getValue(LIT)` with `hasNeighborSignal`. If they match (torch is lit and receives power, or unlit and loses power) and `!level.getBlockTicks().willTickThisTick(pos, this)`, it calls `level.scheduleTick(pos, this, 2)`.
- **Scheduled Delay and Priority:** 2 game ticks (1 redstone tick) delay (`TOGGLE_DELAY = 2`, `RedstoneTorchBlock.java:31`). `TickPriority.NORMAL`.
- **Burnout Mechanism:** Tracked via `RECENT_TOGGLES` (`WeakHashMap<BlockGetter, List<RedstoneTorchBlock.Toggle>>`, `RedstoneTorchBlock.java:27`). When ticking (`tick`, `RedstoneTorchBlock.java:68-87`), toggles older than 60 ticks are pruned (`RECENT_TOGGLE_TIMER = 60`, `RedstoneTorchBlock.java:28`). If a torch toggles 8 times within 60 ticks (`MAX_RECENT_TOGGLES = 8`, `RedstoneTorchBlock.java:29`), it burns out: extinguishes (`LIT = false`), plays smoke event 1502 (`level.levelEvent(1502, pos, 0)`), and schedules a restart tick with delay 160 ticks (8 seconds) (`RESTART_DELAY = 160`, `RedstoneTorchBlock.java:30, 81`).
- **Block Entity:** None.
- **Randomness:** None in logic. Burnout is strictly deterministic based on toggle timestamps.

#### 3. Repeater (Diode)
- **Vanilla Classes:** `net.minecraft.world.level.block.DiodeBlock` (`DiodeBlock.java:1-208`), `net.minecraft.world.level.block.RepeaterBlock` (`RepeaterBlock.java:1-116`).
- **Signal Calculation:** Emits strong power 15 out the front (`direction == FACING`) when `POWERED == true`. Rear input is `level.getSignal(targetPos, direction)` or wire power (`DiodeBlock.java:113-123`).
- **Locking Mechanism:** `isLocked` (`RepeaterBlock.java:83-85`) evaluates `getAlternateSignal(level, pos, state) > 0`, which queries side inputs exclusively from other diodes (`sideInputDiodesOnly() == true`, `RepeaterBlock.java:88-90`). When locked, the repeater maintains its `POWERED` state and ignores rear input changes (`DiodeBlock.java:55, 89`). Side updates change `LOCKED` blockstate immediately in `updateShape` (`RepeaterBlock.java:76-78`).
- **Scheduled Delay and Priority:**
  - Delay: `DELAY * 2` game ticks (values 1, 2, 3, 4 yield 2, 4, 6, 8 game ticks) (`RepeaterBlock.java:52-54`).
  - Priority: Default `TickPriority.HIGH` (`DiodeBlock.java:93`). If `shouldPrioritize` is true (facing another diode facing a different direction), priority promotes to `TickPriority.EXTREMELY_HIGH` (`DiodeBlock.java:95, 200-204`). If already `POWERED` (falling edge), priority is `TickPriority.VERY_HIGH` (`DiodeBlock.java:97`). If turning on while input already turned off, it schedules an instant turn-off with `TickPriority.VERY_HIGH` (`DiodeBlock.java:63`).
- **Block Entity:** None.
- **Randomness:** None.

#### 4. Comparator
- **Vanilla Classes:** `net.minecraft.world.level.block.ComparatorBlock` (`ComparatorBlock.java:1-203`), `net.minecraft.world.level.block.entity.ComparatorBlockEntity` (`ComparatorBlockEntity.java:1-33`).
- **Signal Calculation:**
  - Compare Mode (`ComparatorMode.COMPARE`): `output = (sideSignal > rearSignal) ? 0 : rearSignal` (`ComparatorBlock.java:77, 80`).
  - Subtract Mode (`ComparatorMode.SUBTRACT`): `output = Math.max(rearSignal - sideSignal, 0)` (`ComparatorBlock.java:80`).
  - Side signal (`getAlternateSignal`, `DiodeBlock.java:125-134`): Max signal from left and right sides. Accepts wires, redstone blocks, diodes, and any signal sources (`sideInputDiodesOnly() == false`).
  - Rear input (`getInputSignal`, `ComparatorBlock.java:97-118`): Queries regular signal, or if rear block has analog output (`targetState.hasAnalogOutputSignal()`), reads `targetState.getAnalogOutputSignal(level, targetPos, direction.getOpposite())`. If rear block is a solid conductor, checks through the conductor to the block behind it for analog output, and checks for `ItemFrame` entities hanging on the rear or conductor face (`ComparatorBlock.java:104-115`).
- **Update Trigger:** `checkTickOnNeighbor` (`ComparatorBlock.java:148-157`) checks if `calculateOutputSignal` differs from `ComparatorBlockEntity.getOutputSignal()` or if `POWERED` needs toggling. If changed, schedules tick.
- **Scheduled Delay and Priority:** 2 game ticks (`getDelay` returns 2, `ComparatorBlock.java:47`). Priority is `TickPriority.HIGH` if `shouldPrioritize` is true, else `TickPriority.NORMAL` (`ComparatorBlock.java:153`).
- **Block Entity:** `ComparatorBlockEntity` stores `output: int` (`ComparatorBlockEntity.java:13-33`). Output is updated in `refreshOutputState` (`ComparatorBlock.java:159-179`).
- **Randomness:** None.

#### 5. Observer
- **Vanilla Class:** `net.minecraft.world.level.block.ObserverBlock` (`ObserverBlock.java:1-137`).
- **Signal Calculation:** Emits strong power 15 out its back (`FACING.getOpposite()`) when `POWERED == true` (`ObserverBlock.java:100-112`).
- **Update Trigger:** `updateShape` (`ObserverBlock.java:63-78`) detects changes on the observed face (`state.getValue(FACING) == directionToNeighbour`). If not powered and no scheduled tick is pending (`!ticks.getBlockTicks().hasScheduledTick(pos, this)`), schedules an activation tick with delay 2 (`ObserverBlock.java:80-84`).
- **Scheduled Delay and Priority:**
  - Activation delay: 2 game ticks (`scheduleTick(pos, this, 2)`).
  - Active duration: 2 game ticks (in `tick`, if powered is false, sets `POWERED = true` and immediately calls `scheduleTick(pos, this, 2)` to schedule deactivation) (`ObserverBlock.java:55-57`).
  - Priority: `TickPriority.NORMAL`.
- **Block Entity:** None.
- **Randomness:** None.

#### 6. Lever
- **Vanilla Class:** `net.minecraft.world.level.block.LeverBlock` (`LeverBlock.java:1-155`).
- **Signal Calculation:** Player toggle flips `POWERED`. When true, emits strong power 15 to the block it is attached to (`getConnectedDirection(state) == direction`, `LeverBlock.java:128`), and weak power 15 to all other 5 adjacent positions (`LeverBlock.java:138`).
- **Update Trigger:** Player interaction (`useWithoutItem`, `LeverBlock.java:102-116`). Fired synchronously: sets block state, then invokes `updateNeighbours` (`LeverBlock.java:141-149`), which calls `updateNeighborsAt(pos)` and `updateNeighborsAt(pos.relative(front))`.
- **Scheduled Delay and Priority:** None. Instantaneous.
- **Block Entity:** None.
- **Randomness:** None.

#### 7. Buttons
- **Vanilla Class:** `net.minecraft.world.level.block.ButtonBlock` (`ButtonBlock.java:1-194`).
- **Signal Calculation:** Strong power 15 to the attached block (`ButtonBlock.java:133`), weak power 15 to all other adjacent blocks (`ButtonBlock.java:143`).
- **Timing and Trigger:** Activated by player interaction or projectile hit (`ButtonBlock.java:86-95, 154-160`). In `press` (`ButtonBlock.java:108-114`), sets `POWERED = true`, notifies neighbors, and calls `level.scheduleTick(pos, this, this.ticksToStayPressed)`.
  - Stone Buttons (`STONE_BUTTON`, `POLISHED_BLACKSTONE_BUTTON`): 20 game ticks (1.0 second) (`Blocks.java:1930, 4938`). Not triggered by arrows.
  - Wooden Buttons (Oak, Spruce, Birch, Jungle, Acacia, Dark Oak, Mangrove, Cherry, Pale Oak, Bamboo, Crimson, Warped): 30 game ticks (1.5 seconds) (`Blocks.java:2724-2733, 4752-4753`). Can be triggered and held depressed by arrows (`canButtonBeActivatedByArrows() == true`, `ButtonBlock.java:157, 163-177`).
- **Priority:** `TickPriority.NORMAL`.
- **Block Entity:** None.
- **Randomness:** None.

#### 8. Pressure Plates
- **Vanilla Classes:** `net.minecraft.world.level.block.BasePressurePlateBlock` (`BasePressurePlateBlock.java:1-156`), `net.minecraft.world.level.block.PressurePlateBlock` (`PressurePlateBlock.java:1-56`), `net.minecraft.world.level.block.WeightedPressurePlateBlock` (`WeightedPressurePlateBlock.java:1-71`).
- **Signal Calculation:** Strong power downward into floor block (`direction == Direction.UP`, `BasePressurePlateBlock.java:133`), weak power to all adjacent blocks.
  - Wooden Pressure Plates (`EVERYTHING`): 15 power if any `Entity.class` inside bounding box (`PressurePlateBlock.java:45`).
  - Stone Pressure Plates (`MOBS`): 15 power only if a `LivingEntity.class` inside bounding box (`PressurePlateBlock.java:46`). Items/arrows ignored.
  - Light Weighted (Gold): `POWER = ceil((count / 15) * 15)`, capped at 15 entities (`WeightedPressurePlateBlock.java:42-45`).
  - Heavy Weighted (Iron): `POWER = ceil((count / 150) * 15)`, capped at 150 entities (`WeightedPressurePlateBlock.java:42-45`).
- **Delays:** Wooden and stone plates stay pressed for 20 ticks (`getPressedTime` returns 20, `BasePressurePlateBlock.java:45`). Weighted plates stay pressed for 10 ticks (`getPressedTime` returns 10, `WeightedPressurePlateBlock.java:63`).
- **Priority:** `TickPriority.NORMAL`.
- **Block Entity:** None.
- **Randomness:** None.

#### 9. Redstone Block
- **Vanilla Class:** `net.minecraft.world.level.block.PoweredBlock` (`PoweredBlock.java:1-31`).
- **Signal Calculation:** Emits weak power 15 to all 6 adjacent neighbors (`ownSignal` returns 15, `PoweredBlock.java:28`). Does NOT override `getDirectSignal` (returns 0), so it never strongly powers adjacent blocks.
- **Update Trigger:** Static block state. Neighbor updates occur only when placed or removed.
- **Scheduled Delay and Priority:** None.
- **Block Entity:** None.
- **Randomness:** None.

#### 10. Redstone Lamp
- **Vanilla Class:** `net.minecraft.world.level.block.RedstoneLampBlock` (`RedstoneLampBlock.java:1-64`).
- **Signal Calculation:** Pure consumer. Emits light level 15 when `LIT == true`.
- **Update Trigger:** `neighborChanged` (`RedstoneLampBlock.java:37-50`) checks `level.hasNeighborSignal(pos)`.
  - Turn ON: If not lit and receives signal, toggles immediately to lit (`level.setBlock(pos, state.cycle(LIT), 2)`, 0-tick instant response).
  - Turn OFF: If lit and loses signal, schedules delayed deactivation tick with delay 4 (`level.scheduleTick(pos, this, 4)`).
- **Scheduled Delay and Priority:** 4 game ticks (2 redstone ticks) for turn-off. `TickPriority.NORMAL`.
- **Block Entity:** None.
- **Randomness:** None.

#### 11. Piston and Sticky Piston
- **Vanilla Classes:** `net.minecraft.world.level.block.piston.PistonBaseBlock` (`PistonBaseBlock.java:1-391`), `net.minecraft.world.level.block.piston.PistonStructureResolver` (`PistonStructureResolver.java:1-197`), `net.minecraft.world.level.block.piston.PistonMovingBlockEntity` (`PistonMovingBlockEntity.java:1-405`).
- Detailed analysis provided in Section 4.

### 1.3 Secondary Components Summary

| Component | Role | Vanilla Class | Key Logic and Delay |
|---|---|---|---|
| Target Block | Redirector and signal source | `TargetBlock.java:26-114` | Emits weak signal 1-15 based on projectile hit accuracy. Duration: 20 ticks (arrows), 8 ticks (other). Solid redstone conductor that redirects wire. |
| Daylight Detector | Signal source | `DaylightDetectorBlock.java:28-124` | `DaylightDetectorBlockEntity` ticks every game tick, computing astronomical sun angle and sky brightness into power 0-15. |
| Tripwire and Hook | Sensor and source | `TripWireHookBlock.java:24-274`, `TripWireBlock.java:1-177` | Strong power to wall block. 10-tick deactivation delay (`SCHEDULED_TICK_DELAY = 10`). Unarmed string can be disarmed with shears. |
| Note Block | Consumer | `NoteBlock.java:1-125` | Plays sound on rising edge (`signal && !state.getValue(POWERED)`). Instrument determined by block underneath. |
| Doors / Trapdoors / Fence Gates | Consumer | `DoorBlock.java:1-267`, `TrapDoorBlock.java:1-180`, `FenceGateBlock.java:1-148` | Doors check signal on BOTH top and bottom half (`DoorBlock.java:229-230`). Iron doors and iron trapdoors accept only redstone activation. |
| Dispenser / Dropper | Consumer | `DispenserBlock.java:1-155`, `DropperBlock.java:1-79` | Quasi-connectivity (`hasNeighborSignal(pos) \|\| hasNeighborSignal(pos.above())`, `DispenserBlock.java:133`). 4-tick activation delay via scheduled tick. |
| Hopper | Consumer | `HopperBlock.java:1-150` | `ENABLED = !hasNeighborSignal(pos)` (`HopperBlock.java:128-131`). Redstone signal locks transfer and suction immediately. |
| TNT | Consumer | `TntBlock.java:1-137` | Primed immediately on `hasNeighborSignal(pos)` (`TntBlock.java:60-62`). Replaced by `PrimedTnt` entity, 80-tick fuse. |
| Rails (Powered, Detector, Activator) | Hybrid | `PoweredRailBlock.java:1-166`, `DetectorRailBlock.java:1-124` | Powered/Activator rails propagate signal along track up to 8 rails (`PoweredRailBlock.java:31`). Detector rails detect carts via AABB entity search and emit power. |

---

## 2. Update Propagation and Ordering

### 2.1 Update Methods in Level and ServerLevel

Vanilla triggers neighbor notifications through `net.minecraft.world.level.Level` and `ServerLevel`:
- `Level.updateNeighborsAt(BlockPos pos, Block sourceBlock)` (`Level.java:389-391`) delegates directly to `this.neighborUpdater.updateNeighborsAtExceptFromFacing(pos, sourceBlock, null, null)`.
- `Level.updateNeighborsAtExceptFromFacing(BlockPos pos, Block sourceBlock, Direction skipDirection, @Nullable Orientation orientation)` (`Level.java:393-395`) delegates to `this.neighborUpdater.updateNeighborsAtExceptFromFacing(pos, sourceBlock, skipDirection, orientation)`.
- `Level.neighborChanged(BlockPos pos, Block sourceBlock, @Nullable Orientation orientation)` (`Level.java:385-387`) notifies a single position.

### 2.2 NeighborUpdater and CollectingNeighborUpdater

In `ServerLevel`, the active `NeighborUpdater` is `CollectingNeighborUpdater` (`ServerLevel.java:214`), initialized with `maxChainedNeighborUpdates = 1000000` (1 million).

`CollectingNeighborUpdater` (`CollectingNeighborUpdater.java:17-220`) implements update batching and depth control:
1. **Reentrancy Queue (`addedThisLayer`):** When `addAndRun` (`CollectingNeighborUpdater.java:69-86`) is called while an update cascade is already running (`this.count > 0`), the new update is appended to `this.addedThisLayer`.
2. **Layer Inversion (LIFO between layers, FIFO within layers):** In `runUpdates()` (`CollectingNeighborUpdater.java:88-113`):
   > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
   Because `addedThisLayer` is drained in reverse order onto `stack`, child updates pop in forward order (FIFO). If a step in `runNext()` triggers sub-updates, `addedThisLayer` becomes non-empty, interrupting the inner loop. The child updates are pushed on top of `stack`, executing before the parent resumes.
3. **MultiNeighborUpdate State Machine:** `MultiNeighborUpdate` (`CollectingNeighborUpdater.java:140-183`) steps through `NeighborUpdater.UPDATE_ORDER` using an internal index `this.idx`. Each step executes one `neighborChanged`. If that neighbor triggers nested updates, the nested updates run immediately, and `MultiNeighborUpdate` resumes from its saved index once the nested work completes.

### 2.3 Constant Direction Arrays

The order in which directions are traversed is critical to redstone parity and MC-11193 update-order behavior:

```
Array 1: NeighborUpdater.UPDATE_ORDER (NeighborUpdater.java:18)
Index 0: WEST  (-X)
Index 1: EAST  (+X)
Index 2: DOWN  (-Y)
Index 3: UP    (+Y)
Index 4: NORTH (-Z)
Index 5: SOUTH (+Z)
Note: Grouped as X-axis, then Y-axis, then Z-axis.

Array 2: BlockBehaviour.UPDATE_SHAPE_ORDER (BlockBehaviour.java:86-88)
Index 0: WEST  (-X)
Index 1: EAST  (+X)
Index 2: NORTH (-Z)
Index 3: SOUTH (+Z)
Index 4: DOWN  (-Y)
Index 5: UP    (+Y)
Note: Grouped as horizontal X, then horizontal Z, then vertical Y.

Array 3: Direction.values() / SignalGetter.DIRECTIONS (Direction.java:23, SignalGetter.java:11)
Index 0: DOWN  (-Y)
Index 1: UP    (+Y)
Index 2: NORTH (-Z)
Index 3: SOUTH (+Z)
Index 4: WEST  (-X)
Index 5: EAST  (+X)
Note: Standard enum ordinal order (Y, Z, X).
```

### 2.4 Shape Updates vs Neighbor Updates

1. **`updateShape` (`BlockBehaviour.BlockStateBase.updateShape`, `BlockBehaviour.java:672`):** Executed synchronously when an adjacent neighbor changes. Evaluates whether the block can still survive (e.g. torch falling off a broken wall) and re-evaluates physical connectivity (e.g. fence connections, repeater locking, redstone wire visual shape).
2. **`neighborShapeChanged` (`LevelAccessor.neighborShapeChanged`, `Level.java:401-404`):** Dispatches shape updates to neighbors via `NeighborUpdater.shapeUpdate`.
3. **`updateIndirectNeighbourShapes` (`BlockBehaviour.BlockStateBase.updateIndirectNeighbourShapes`, `BlockBehaviour.java:744-750`):** Called during block placements and removals. Only a single block in vanilla overrides this: `RedStoneWireBlock` (`RedStoneWireBlock.java:210-234`). When a wire connects toward a horizontal neighbor, it fires `neighborShapeChanged` on the diagonal positions (one up, one down) so ascending and descending wires maintain shape synchronization.

### 2.5 Redstone Evaluators in 26.2: Default vs Experimental

Minecraft 26.2 contains two distinct redstone wire evaluators:

1. **Default Evaluator (`DefaultRedstoneWireEvaluator`, `DefaultRedstoneWireEvaluator.java:12-44`):**
   - Active by default in all standard worlds.
   - Evaluates a single wire position at a time: `target = max(externalSignal, incomingWireSignal - 1)`.
   - If target differs from current power, sets the block state (`level.setBlock(pos, state.setValue(POWER, target), 2)`), adds `pos` plus all 6 neighbors into a `HashSet<BlockPos>`, and fires `level.updateNeighborsAt` on all 7 positions (`DefaultRedstoneWireEvaluator.java:27-36`).
   - Traversal order is recursive through `neighborChanged`, resulting in the classic locational and directional update chaos (MC-11193).
2. **Experimental Evaluator (`ExperimentalRedstoneWireEvaluator`, `ExperimentalRedstoneWireEvaluator.java:1-226`):**
   - Enabled ONLY when `FeatureFlags.REDSTONE_EXPERIMENTS` is present in `level.enabledFeatures()` (`RedStoneWireBlock.java:357-359`).
   - Uses two BFS queues (`wiresToTurnOff`, `wiresToTurnOn`) and an `Object2IntLinkedOpenHashMap` overlay.
   - Propagates changes across the entire contiguous network before performing block writes.
   - Performs a single `setBlock` per changed wire, followed by orientation-ordered neighbor updates.
   - Traversal starts from a randomly seeded `Orientation` (`Orientation.random(level.getRandom())`, `ExperimentalRedstoneUtils.java:12`).

As established in `preresearch/redstone-path-analysis.md` and `preresearch/redstone-experiments-eval.md`, the default evaluator is the sole parity target for Kestrel. The experimental evaluator changes observable settle order, introduces RNG dependence, and is excluded from Mojang's own `GameTestServer`.

---

## 3. Where Redstone Work Runs Inside One Server Tick

### 3.1 Vanilla ServerTick Phase Order

In `net.minecraft.server.level.ServerLevel.tick(BooleanSupplier haveTime)` (`ServerLevel.java:354-460`), the tick execution pipeline proceeds in strict sequence:

```
Phase 1: Time Advance (ServerLevel.java:383)
  this.tickTime(); // Advances gameTime and dayTime

Phase 2: Scheduled Block Ticks (ServerLevel.java:389-390)
  this.blockTicks.tick(tick, 65536, this::tickBlock);
  // Repeaters toggle, redstone torches toggle/burnout, comparators update,
  // observers activate/deactivate, redstone lamps turn off, target blocks reset.

Phase 3: Scheduled Fluid Ticks (ServerLevel.java:391-392)
  this.fluidTicks.tick(tick, 65536, this::tickFluid);
  // Flowing fluid spread and block washing.

Phase 4: Raids (ServerLevel.java:398)
  this.raids.tick(this);

Phase 5: Chunk Management (ServerLevel.java:402)
  this.getChunkSource().tick(haveTime, true);

Phase 6: Block Events (ServerLevel.java:404-406)
  this.runBlockEvents();
  // Drains blockEvents queue: pistons extend, contract, and drop blocks!

Phase 7: Entities (ServerLevel.java:420-450)
  this.entityTickList.forEach(entity -> ...);

Phase 8: Block Entities (ServerLevel.java:451-452)
  this.tickBlockEntities();
  // Ticks moving piston entities (PistonMovingBlockEntity.tick), daylight detectors, etc.
```

### 3.2 Block Events Pipeline (`runBlockEvents`)

Block events are vanilla's mechanism for deferring multi-phase block actions from scheduled ticks or neighbor updates to a designated phase later in the server tick (`ServerLevel.java:1256-1286`):
- **Storage:** `ServerLevel.blockEvents` is an `ObjectLinkedOpenHashSet<BlockEventData>` (`ServerLevel.java:215`). Identical `(pos, block, paramA, paramB)` events within the same tick are deduplicated by hash set semantics while preserving FIFO insertion order.
- **Execution:** `runBlockEvents()` removes elements from the front (`removeFirst()`). If `shouldTickBlocksAt(pos)` is true, it calls `doBlockEvent(eventData)`, which invokes `state.triggerEvent(level, pos, paramA, paramB)` (`ServerLevel.java:1283-1286`).
- **Cascading Events:** If executing a block event causes another block event to be enqueued (for instance, an extending piston pushes a redstone block that powers a second piston), the new event is added to the tail of `blockEvents` and is executed within the SAME `runBlockEvents` phase of the current tick!

### 3.3 Kestrel TickLoop Gap Analysis

Kestrel's `TickLoop` (`core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1192-1207`) currently executes:
```java
public void tick() {
    gameTime++;
    drainScheduled();      // Phase 2: scheduled block ticks
    drainFluidTicks();     // Phase 3: scheduled fluid ticks
    level.sleepers().beginPass();
    for (long id : level.sleepers().walkOrder()) {
        sleepTick(id);     // Phase 8: block entity sleepers
    }
    drainDispatch();       // Residual neighbor dispatch
    if (powerTraceEnabled) {
        tracePower();
    }
}
```

To achieve redstone parity, the following must be added to `TickLoop`:
1. **Block Events Queue and Drain Phase:** An `ObjectLinkedOpenHashSet<BlockEventData>` must be added to `TickLoop`, drained immediately after `drainFluidTicks()` (matching vanilla Phase 6, before block entity ticking).
2. **Piston Block Entity Lifecycle:** `PistonMovingBlockEntity` ticking must be registered in the block entity tick pass (Phase 8), advancing `progress` by 0.5 per tick and completing after 2 ticks.
3. **Neighbor Dispatch Drain Timing:** `drainDispatch()` must run whenever block changes occur, rather than only at the very end of the tick. In vanilla, `CollectingNeighborUpdater` drains its stack immediately within `addAndRun` when `count == 0`.

---

## 4. Pistons in Depth

### 4.1 Trigger Logic and Quasi-Connectivity

Piston activation is checked in `PistonBaseBlock.checkIfExtend` (`PistonBaseBlock.java:104-125`), called on neighbor updates, placement, and block changes.

Quasi-connectivity (QC) is implemented in `PistonBaseBlock.getNeighborSignal` (`PistonBaseBlock.java:127-147`):
> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

**Key QC Behaviors:**
1. A piston evaluates direct power on 5 faces (excluding the push face `pushDirection`).
2. It then inspects the block directly above (`pos.above()`). If ANY of the blocks adjacent to `pos.above()` (except the block below it, which is the piston itself) emits signal into `pos.above()`, the piston considers itself POWERED.
3. However, the piston is NOT a neighbor to the blocks powering `pos.above()`. Therefore, changes in those power sources do not fire `neighborChanged` on the piston! The piston remains in its old state until an adjacent block triggers a neighbor update, creating a Block Update Detector (BUD).

### 4.2 Block Event Dispatch and Event IDs

When `checkIfExtend` detects a power change, it does NOT move blocks immediately. It dispatches a block event (`PistonBaseBlock.java:109, 123`):
- **Event 0 (`TRIGGER_EXTEND`):** Fired when unextended piston becomes powered. Resolves structure; if pushable, enqueues event 0 with `paramA = 0`, `paramB = direction.get3DDataValue()`.
- **Event 1 (`TRIGGER_CONTRACT`):** Fired when extended piston loses power. Standard retraction.
- **Event 2 (`TRIGGER_DROP`):** Fired when an extended piston loses power while an extension is still in flight (progress < 0.5 or on the same tick) (`PistonBaseBlock.java:119-120`). Triggers the 1-tick/0-tick sticky block drop.

### 4.3 Structure Resolution (`PistonStructureResolver`)

`PistonStructureResolver` (`PistonStructureResolver.java:1-197`) calculates the moving structure:
- **Push Limit:** Maximum 12 blocks (`MAX_PUSH_DEPTH = 12`, `PistonStructureResolver.java:13`). If `toPush.size() > 12`, resolution fails completely (`return false`).
- **Sticky Blocks:** Slime (`Blocks.SLIME_BLOCK`) and Honey (`Blocks.HONEY_BLOCK`) (`PistonStructureResolver.java:63`).
- **Stickiness Rules:** Slime sticks to all movable blocks EXCEPT Honey. Honey sticks to all movable blocks EXCEPT Slime (`PistonStructureResolver.java:67-71`). Slime and Honey do not stick to each other.
- **Push Reactions (`net.minecraft.world.level.material.PushReaction`):**
  - `PushReaction.BLOCK`: Completely immovable. Includes Obsidian, Crying Obsidian, Respawn Anchor, Bedrock, Jukebox, Enchanting Table, Reinforced Deepslate (`PistonBaseBlock.java:249-250`).
  - `PushReaction.DESTROY`: Destroyed and dropped as items when pushed (torches, redstone wire, flowers, rails) (`PistonBaseBlock.java:251-252`). Cannot be pulled by sticky pistons (`PistonStructureResolver.java:42`).
  - `PushReaction.PUSH_ONLY`: Can only be pushed in direction of piston; cannot be pulled (Glazed Terracotta) (`PistonBaseBlock.java:253-254`).
  - `PushReaction.IGNORE`: Ignored by piston calculations.
  - `PushReaction.NORMAL`: Standard push and pull.
- **Block Entities:** Any block with a block entity (`state.hasBlockEntity() == true`) cannot be pushed or pulled in vanilla Java Edition (`PistonBaseBlock.java:260`).

### 4.4 Moving Piston Block and Entity

In `PistonBaseBlock.moveBlocks` (`PistonBaseBlock.java:264-364`):
1. Destroyed blocks (`toDestroy`) are broken and dropped.
2. Pushed blocks (`toPush`) are replaced with `Blocks.MOVING_PISTON` (flag 324: `4 | 64 | 256`), holding a `PistonMovingBlockEntity` containing the original block state.
3. Original positions are cleared to air (flag 82: `2 | 16 | 64`).
4. `PistonMovingBlockEntity.tick` (`PistonMovingBlockEntity.java:307-340`) increments progress by 0.5 per tick (`TICKS_TO_EXTEND = 2`).
5. After 2 ticks (`progress >= 1.0F`), the block entity removes itself and places the final block state via `Block.updateFromNeighbourShapes`.
6. **0-Tick Retract / Drop:** If interrupted by `finalTick()` (`PistonMovingBlockEntity.java:275-296`) or if event 2 (`TRIGGER_DROP`) fires, the sticky piston retracts without pulling the block (`PistonBaseBlock.java:204-208`).

*(Entity movement, collision pushing, and bounce physics are out of scope for G12 block parity).*

---

## 5. Kestrel Today: Current State and Gap Analysis

### 5.1 Redstone Package Inventory (`core/src/vanilla/java/dev/kestrel/redstone/`)

| Class | Status | Role and Implementation |
|---|---|---|
| `WireConnectionCache` | Transcribed | Memoized 4-side connection state (`RedstoneSide`). Generation-scoped invalidation on neighbor changes. |
| `WireUpdateFilter` | Custom optimization | Plans no-op update suppression on the default path across 4 rules (R1 ceiling, R2 monotonicity, R3 connection gating, R4 self-dispatch). |
| `WireSectionLayout` | Custom optimization | Flat 16x16x16 chunk-section scratch store for wire power and connection nibbles. |
| `AcFlag`, `AcWireNetwork`, `AcDirections`, `AcFlowTable`, `AcUpdateOrder` | Transcribed / Seam | Alternate Current implementation gated behind opt-in AC flag. |
| `RedstoneProofCheck`, `AcWireProofCheck` | Tests | Unit verification of cache, filter rules, and layout indexing. |

### 5.2 Loop Package Inventory (`core/src/vanilla/java/dev/kestrel/loop/`)

- `TickLoop.java`:
  - Contains default-path wire power algebra: `calculateTarget` (`TickLoop.java:437`), `getBlockSignal` (`TickLoop.java:455`), `getIncomingWireSignal` (`TickLoop.java:496`).
  - Implements per-world `signalReadsOpen` reentrancy guard (`TickLoop.java:564-570`).
  - Implements scheduled tick queue (`TickLoop.java:869-940`) with `ScheduledKey(pos, block)`.
  - Implements basic neighbor dispatch (`enqueueFanOut`, `pushStep`, `drainDispatch`, `updateNeighborsAt`, `TickLoop.java:734-823`).
  - In `updateNeighborsAt` (`TickLoop.java:788-823`), partially handles:
    - `Blocks.REDSTONE_WIRE`: calls `updatePowerAt`.
    - `Blocks.LEVER`: if powered, pushes dispatch step.
    - `Blocks.REDSTONE_TORCH`: checks `hasSignalBelow`, schedules tick with delay 2. Wall torches are NOT checked!
    - `Blocks.REPEATER`: calls `scheduleRepeaterCheck` (`TickLoop.java:831-839`).
  - In `runScheduled` (`TickLoop.java:1138-1180`), toggles torch `LIT` and repeater `POWERED`.
- `FluidFlow.java`:
  - Contains exact transcriptions of redstone neighbor fan-out required for fluid interaction (`FluidFlow.java:624-715`):
    - `removalFanOut` (`FluidFlow.java:633-653`): Wire removal (6x `updateNeighborsAt` + `neighboringWires`), torch removal (6x `updateNeighborsAt`), lever removal (front block update), repeater removal (`diodeFront`).
    - `placeFanOut` (`FluidFlow.java:656-672`): Wire onPlace (UP/DOWN + `neighboringWires`), torch onPlace, repeater onPlace (`diodeFront`).
    - `diodeFront` (`FluidFlow.java:675-680`): Single neighbor notification in front plus `NeighborsAt` except facing.
    - `leverConnected` (`FluidFlow.java:683-689`): FaceAttached directional mapping.
    - `neighboringWires` and `checkCornerChangeAt` (`FluidFlow.java:692-714`): Full corner update fan-out.
  - Implements mini `CollectingNeighborUpdater` with `NeighborsAt` and `SingleNeighbor` (`FluidFlow.java:722-830`).

### 5.3 What Is Missing in Kestrel

1. **Missing Components in Dispatch and Tick:**
   - `RedstoneWallTorchBlock`: Not handled in `TickLoop.updateNeighborsAt` or `runScheduled`.
   - Torch Burnout: `RECENT_TOGGLES` map, toggle frequency counting, 160-tick burnout restart completely absent.
   - Repeater Locking: `isLocked` check absent in `TickLoop.scheduleRepeaterCheck` and `runScheduled`.
   - `ComparatorBlock`: Completely absent. No compare/subtract logic, no `ComparatorBlockEntity`, no container reading.
   - `ObserverBlock`: Completely absent. No `updateShape` start signal, no 2-tick pulse.
   - `ButtonBlock`: Absent from `updateNeighborsAt` and tick execution.
   - `PressurePlateBlock` / `WeightedPressurePlateBlock`: Absent.
   - `RedstoneLampBlock`: Instant on and 4-tick off absent.
   - `PistonBaseBlock` and `PistonMovingBlockEntity`: Completely unmodeled.
2. **Missing Infrastructure:**
   - `CollectingNeighborUpdater` layer queue: `TickLoop` uses a simple `dispatchStack` LIFO instead of vanilla's `addedThisLayer` / layer-inversion queue.
   - Block Events Pipeline: No `blockEvents` queue (`ObjectLinkedOpenHashSet<BlockEventData>`), no `runBlockEvents` phase in `TickLoop`.
   - Block Entity Ticking for Redstone: `PistonMovingBlockEntity` and `ComparatorBlockEntity` integration.

---

## 6. Proposed Parity Test Corpus

The test corpus consists of 27 scenarios executed against a vanilla oracle (`FluidOracle` style, in-process vanilla 26.2 server stepped via tick freeze). Each scenario is constructed with raw setup flags (`2 | 16 | 512`) at y=100, triggered by an action at tick 0, and recorded per tick.

| # | Scenario Name | Layout and Trigger | Ticks | Rules Pinned Down |
|---|---|---|---|---|
| 1 | `wire_falloff_16` | 16-block stone floor with redstone wire. Lever placed at pos (0,100,0) and powered on at tick 0. | 5 | Power falloff from 15 down to 1 at block 15, block 16 remaining power 0. |
| 2 | `wire_branching_t` | T-junction of wire (stem 5 blocks, left/right arms 5 blocks each) powered from stem base. | 5 | Symmetric power propagation into multiple branches on the default path. |
| 3 | `wire_stair_climb` | Wire climbing a 3-step solid stone staircase. | 5 | Vertical wire connectivity over solid blocks and signal propagation up and down stairs. |
| 4 | `wire_stair_occlusion` | Same as #3, but with a solid stone block placed directly above the middle step. | 5 | Redstone conductor edge occlusion breaking vertical wire connection. |
| 5 | `torch_inversion` | Solid block with redstone torch on top. Wire powers the solid block at tick 0. | 8 | 2-tick torch deactivation delay and 2-tick reactivation delay when wire unpowered at tick 4. |
| 6 | `torch_tower` | Vertical tower of 5 alternating solid blocks and redstone torches. | 15 | Inversion propagation delay: exactly 2 ticks per torch level (10 ticks total for 5 tiers). |
| 7 | `torch_burnout_clock` | Torch on side of solid block, wire on top leading back into the solid block. | 180 | Rapid toggle accumulation, burnout threshold (8 toggles in 60 ticks), and 160-tick extinguish duration. |
| 8 | `repeater_delays` | 4 parallel lines with repeaters set to delays 1, 2, 3, 4 (delays 2, 4, 6, 8 ticks). | 15 | Exact delay timings (2, 4, 6, 8 game ticks) and simultaneous signal output verification. |
| 9 | `repeater_locking` | Repeater A powered on. Repeater B faces into side of A, powering on at tick 2. Input to A cut at tick 4. | 12 | Locking via side diode in `updateShape`, signal retention while locked, and unlocking. |
| 10 | `repeater_pulse_stretch`| 1-tick pulse input (lever on/off in same tick) fed into 4-delay repeater. | 16 | `TickPriority.VERY_HIGH` turn-on reschedule behavior preserving minimum 2-tick output pulse. |
| 11 | `repeater_priority_drain`| Two repeaters with different priorities (`HIGH` vs `VERY_HIGH`) scheduled for the same tick. | 5 | Sub-tick scheduled queue drain order based on `TickPriority`. |
| 12 | `comparator_compare` | Comparator in compare mode. Rear signal 10. Side signal 8 at tick 0, then side signal 12 at tick 4. | 10 | Output stays 10 when side <= rear; output drops to 0 when side > rear. 2-tick delay. |
| 13 | `comparator_subtract`| Comparator in subtract mode. Rear signal 13, side signal 5 at tick 0. Side increased to 9 at tick 4. | 10 | Output = 8 at tick 2; output drops to 4 at tick 6. Mode toggle arithmetic. |
| 14 | `comparator_container`| Barrel behind comparator filled with 1 stack (signal 1) and 9 stacks (signal 5). | 6 | `getAnalogOutputSignal` calculation and container menu filling ratio. |
| 15 | `comparator_through_block`| Barrel separated from comparator by a solid stone conductor block. | 6 | Conductor passthrough reading analog output through an intermediate solid block. |
| 16 | `observer_single_pulse`| Observer facing an air block. Stone placed in front at tick 0. | 8 | 2-tick activation delay, exactly 2-tick output pulse duration, and 2-tick deactivation. |
| 17 | `observer_clock` | Two observers facing directly into each other. Placed at tick 0. | 20 | Rapid continuous 2-tick clock oscillation and deterministic phase timing. |
| 18 | `lever_power_types` | Lever on stone block surrounded by wire on floor and wire on stone side. | 4 | Strong power into attached block (powers wire on its other side) vs weak power to surrounding air. |
| 19 | `button_stone_duration`| Stone button pressed at tick 0. Output wire observed. | 25 | Exactly 20-tick press duration before deactivation. |
| 20 | `button_wood_duration` | Oak button pressed at tick 0. Output wire observed. | 35 | Exactly 30-tick press duration before deactivation. |
| 21 | `lamp_timings` | Redstone lamp powered at tick 0, depowered at tick 4. | 12 | 0-tick instant turn-on, followed by exactly 4-tick turn-off delay. |
| 22 | `piston_qc_bud` | Piston facing up. Redstone block placed at `pos.above().north()` at tick 0. Neighbor updated at tick 4. | 10 | Quasi-connectivity: piston does not extend at tick 0; extends at tick 4 upon neighbor notification. |
| 23 | `piston_push_limit_12` | Regular piston pushing 12 stone blocks vs 13 stone blocks. | 10 | 12 blocks push successfully; 13 blocks fail to push. `MAX_PUSH_DEPTH = 12`. |
| 24 | `piston_slime_honey_split`| Sticky piston pushing alternating slime and honey columns. | 10 | Slime adheres to stone but not adjacent honey; structure resolves correctly without jamming. |
| 25 | `piston_block_drop` | Sticky piston holding stone block receives 2-tick pulse (extending into immediate retract). | 10 | Event 2 `TRIGGER_DROP`: piston retracts without pulling the stone block, leaving it behind. |
| 26 | `piston_block_events_fifo`| Two pistons triggered on the exact same tick. | 8 | Block event FIFO queue drain order and execution sequence within `runBlockEvents()`. |
| 27 | `update_order_tie` | Two repeaters pointing toward the same wire line, powered simultaneously. | 6 | MC-11193 update ordering resolved via `NeighborUpdater.UPDATE_ORDER`. |

---

## 7. Randomness and Determinism

Redstone simulation in Minecraft 26.2 is overwhelmingly deterministic, but contains three isolated interactions with `RandomSource`:

### 7.1 Piston Sound Pitch (Server-Side Level Random)
- **Source:** In `PistonBaseBlock.triggerEvent` (`PistonBaseBlock.java:165-173, 217`), every piston extension and retraction calls `level.getRandom()`:
  - Extension: `random.nextFloat() * 0.25F + 0.6F` (`PistonBaseBlock.java:172`).
  - Retraction: `random.nextFloat() * 0.15F + 0.6F` (`PistonBaseBlock.java:217`).
- **Effect on World State:** The resulting float is passed to `level.playSound` to populate the pitch field of `ClientboundSoundPacket`. It does not alter block states.
- **RNG Coupling Gotcha:** Because `level.getRandom()` is the shared world RNG, each piston extension or retraction advances the generator state by 1 draw. In a world where other random features run (e.g. mob spawns, dispenser item spread), this advance can desynchronize subsequent random choices.
- **Oracle Replay Solution:** In the oracle capture harness, intercept `level.getRandom()` via a delegating `RandomSource` (identical to `FluidOracle`'s handling of `LavaFluid.getSpreadDelay`) and record the float draws in `R` lines, or freeze/stub the sound call during headless verification.

### 7.2 Experimental Evaluator Orientation (Flagged)
- **Source:** In `ExperimentalRedstoneUtils.initialOrientation` (`ExperimentalRedstoneUtils.java:12`), if `REDSTONE_EXPERIMENTS` is active, every propagation with `front == null` calls `Orientation.random(level.getRandom())`.
- **Effect on World State:** Randomizes the initial direction bias of wire BFS propagation, altering the order of `setBlock` and neighbor notifications across worlds with different seeds.
- **Oracle Replay Solution:** G12 parity targets the default evaluator where `ExperimentalRedstoneUtils.initialOrientation` returns `null` unconditionally (`ExperimentalRedstoneUtils.java:23`). No RNG draws occur under the default flag setting.

### 7.3 Dispenser and Dropper Slot Selection
- **Source:** `DispenserBlockEntity.getRandomSlot` draws from `level.random` to select among multiple occupied inventory slots.
- **Effect on World State:** Determines which item is dispensed or dropped.
- **Oracle Replay Solution:** Oracle test scenarios for dispenser/dropper consumers must use single-item-slot setups or record slot draws.

---

## 8. Risks and Open Questions (Ordered by Severity)

### R1. Piston Block Event Pipeline and 2-Tick Moving Entity Parity (High Severity)
Pistons are not regular block updates. They execute across three distinct phases: trigger evaluation in neighbor updates, event resolution in `runBlockEvents`, and 2-tick interpolation in `PistonMovingBlockEntity`. If Kestrel does not faithfully replicate `ObjectLinkedOpenHashSet` FIFO event ordering and 2-tick moving block entity progress, complex redstone contraptions (piston feed tapes, flying machines, 0-tick generators) will fail.
*Mitigation:* Transcribe `PistonStructureResolver` and `PistonMovingBlockEntity` directly from vanilla, avoiding custom approximations.

### R2. `CollectingNeighborUpdater` LIFO/FIFO Layer Interaction (High Severity)
Vanilla's `CollectingNeighborUpdater` uses a specific nested stack and queue structure (`addedThisLayer` reversed into `stack`), giving FIFO order within a layer and LIFO order between layers. Kestrel's current `TickLoop.dispatchStack` is a flat LIFO stack. In multi-branch redstone lines and torch towers, this difference can cause signals to reach destinations in divergent orders.
*Mitigation:* Port the exact `CollectingNeighborUpdater` data structure and `MultiNeighborUpdate` state machine into Kestrel's core loop.

### R3. Quasi-Connectivity and BUD Desynchronization (Medium Severity)
Quasi-connectivity requires that pistons, dispensers, and droppers check power on `pos.above()`, but DO NOT receive neighbor updates when `pos.above()`'s neighbors change. If an implementer accidentally wires neighbor updates to the QC check, pistons will auto-update without needing a neighbor update, breaking vanilla BUD contraptions.
*Mitigation:* Adhere strictly to vanilla's `getNeighborSignal` without expanding the neighbor notification radius.

### R4. Comparator Analog Conductor Traversal and ItemFrame Queries (Medium Severity)
Comparators read inventory signals through solid opaque blocks and query `ItemFrame` entities hanging on blocks. Kestrel's block layer must correctly differentiate redstone conductors from transparent blocks, and provide an abstraction for container analog signals.
*Mitigation:* Create a dedicated `AnalogSignalResolver` interface that encapsulates container inventory filling math and conductor passthrough.

### R5. Torch Burnout Memory Leak and State Retention (Low Severity)
Vanilla stores burnout toggles in a static `WeakHashMap<BlockGetter, List<Toggle>>`. In long-running servers or after world reloads, stale toggle maps can alter burnout timing if not properly scoped to world lifecycles.
*Mitigation:* Store torch toggle history within the world tick loop instance rather than in static memory.

---

## 9. Recommended Implementation Order

The implementation should proceed in 6 self-contained vertical slices, each gated by a subset of the proposed oracle corpus:

### Slice 1: Exact Neighbor Updater and Wire Core
- **Scope:** Implement vanilla's `CollectingNeighborUpdater` (with `addedThisLayer` layer inversion and `MultiNeighborUpdate` stepping over `NeighborUpdater.UPDATE_ORDER`). Hook default-path wire propagation with exact `HashSet` slot ordering.
- **Gating Scenarios:** 1 (`wire_falloff_16`), 2 (`wire_branching_t`), 3 (`wire_stair_climb`), 4 (`wire_stair_occlusion`), 27 (`update_order_tie`).

### Slice 2: Diodes (Repeaters and Comparators)
- **Scope:** Port `DiodeBlock`, `RepeaterBlock`, and `ComparatorBlock`. Implement repeater delay multipliers (2, 4, 6, 8 ticks), locking via side diodes, comparator compare/subtract algebra, and `ComparatorBlockEntity`. Connect to `TickLoop` scheduled block ticks with `TickPriority`.
- **Gating Scenarios:** 8 (`repeater_delays`), 9 (`repeater_locking`), 10 (`repeater_pulse_stretch`), 11 (`repeater_priority_drain`), 12 (`comparator_compare`), 13 (`comparator_subtract`), 14 (`comparator_container`), 15 (`comparator_through_block`).

### Slice 3: Redstone Torches and Burnout
- **Scope:** Implement standing and wall redstone torches. Add `RECENT_TOGGLES` tracking with 60-tick rolling window, 8-strike burnout detection, 160-tick restart scheduling, and level event 1502.
- **Gating Scenarios:** 5 (`torch_inversion`), 6 (`torch_tower`), 7 (`torch_burnout_clock`).

### Slice 4: Sensors and Simple Consumers (Observers, Lamps, Buttons, Plates)
- **Scope:** Port `ObserverBlock` (2-tick pulse via `updateShape`), `RedstoneLampBlock` (instant on, 4-tick off), `ButtonBlock` (stone 20-tick, wood 30-tick), `BasePressurePlateBlock`, and `PoweredBlock`.
- **Gating Scenarios:** 16 (`observer_single_pulse`), 17 (`observer_clock`), 18 (`lever_power_types`), 19 (`button_stone_duration`), 20 (`button_wood_duration`), 21 (`lamp_timings`).

### Slice 5: Block Events Pipeline and Basic Pistons
- **Scope:** Add `blockEvents` (`ObjectLinkedOpenHashSet<BlockEventData>`) and `runBlockEvents()` phase to `TickLoop`. Implement `PistonBaseBlock.checkIfExtend` with quasi-connectivity (`pos.above()`), event 0/1 dispatch, `PistonStructureResolver` push limit (12), and push reaction handling.
- **Gating Scenarios:** 22 (`piston_qc_bud`), 23 (`piston_push_limit_12`), 26 (`piston_block_events_fifo`).

### Slice 6: Sticky Pistons, Slime/Honey, and Moving Block Entity
- **Scope:** Implement `MovingPistonBlock` and `PistonMovingBlockEntity` (2-tick progress, 0.5 per tick, `finalTick`). Implement slime/honey non-stick rules in `PistonStructureResolver`. Implement event 2 (`TRIGGER_DROP`) 1-tick block dropping.
- **Gating Scenarios:** 24 (`piston_slime_honey_split`), 25 (`piston_block_drop`).
