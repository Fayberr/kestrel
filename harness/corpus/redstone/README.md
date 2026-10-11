# G12 redstone oracle corpus

29 vanilla 26.2 fixtures under `oracle/`, one per scenario in
`RedstoneScenarios.all()`. Ground truth for the G12 redstone parity gate:
G12 has landed and `RedstoneParityCheck` reports 29/29 exact against these
fixtures. Captured with `RedstoneOracle`
(`core/src/vanilla/java/dev/kestrel/loop/RedstoneOracle.java`), modelled on
the G11 `FluidOracle`/`FluidScenarios` harness. The first 27 are the section
6 corpus; the last two (`piston_block_events_contention`,
`piston_settle_power_cut`) are the coverage-gap fixtures added after slice 5
(see "Coverage gaps closed after slice 5" below).

## Capture command

From an empty scratch directory (the server writes its world and properties
into the working directory), one JVM at a time, foreground only:

```
mkdir /tmp/redstone-oracle-<random> && cd /tmp/redstone-oracle-<random>
$HOME/.local/toolchains/jdk-25.0.4.1+1/bin/java \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  -cp "<worktree>/core/build/classes/java/main:$(cut -d: -f2- $HOME/kestrel-run/cp.txt)" \
  dev.kestrel.loop.RedstoneOracle <worktree>/harness/corpus/redstone/oracle [scenario...]
```

Port 25798, `pause-when-empty-seconds=0`. Omit the scenario list to recapture
all 29.

The two gap fixtures were captured with exactly this command, one JVM at a
time, foreground, from an empty scratch directory:

```
$HOME/.local/toolchains/jdk-25.0.4.1+1/bin/java \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  -cp "<worktree>/core/build/classes/java/main:$(cut -d: -f2- $HOME/kestrel-run/cp.txt)" \
  dev.kestrel.loop.RedstoneOracle <worktree>/harness/corpus/redstone/oracle \
  piston_block_events_contention piston_settle_power_cut
```

Both capture runs booted the full 26.2 dedicated server (flat world,
`generate-structures=false`, frozen tick clock) and stepped each scenario one
tick at a time, so the `E`/`C`/`Q` lines are vanilla's own, not hand-written.

## Fixture format (v2)

```
# kestrel redstone oracle v2 scenario=<name> ticks=<n>
S
C x y z <state>
...
BE x y z <key>=<value>
...
T 0
E ...
C x y z <state>
...
Q <n>
q x y z <block> <relTick> <priority> <subTickOrder>
...
T 1
...
```

### Setup and settle (the S section)

Setup cells are placed **with flag 3 (`UPDATE_ALL`)**, in list order (not the
raw `2|16|512` flags fluid setups use). Containers get their items
immediately after their cell is placed, same as before.

Placing with real update flags still doesn't mean the box is in a
vanilla-reachable state: a wire placed next to an already-existing power
source connects and computes its power over one or more block/neighbor
updates, and a repeater or comparator placed already-`powered`/with an
output signal has no cause for that state until its input is evaluated. So
after setup, the harness steps the server **unrecorded** ("settling") until:

- the box (2-block padded) has no pending block or fluid ticks, and
- no cell in the box changed over the last 2 consecutive steps,

capped at 400 steps (throws if it never converges). **Setups must not
contain clocks** - a clock never settles and will hit the cap. Anything
that starts a clock (e.g. `torch_burnout_clock`'s wire) is placed by a
tick-0 action instead, after settling, never in `setup()`.

The settled state is then recorded as the `S` section: one line `S`,
followed by a `C x y z <state>` line for every non-air cell in the box (full
listing, not a delta - this is what a parity check loads raw instead of
replaying the setup), followed by `BE x y z <key>=<value>` lines for
block-entity state that matters to redstone and can't be read off the block
state itself:

- comparators: `BE x y z output=<n>` - `ComparatorBlockEntity.getOutputSignal()`.
- containers (barrels, etc.): `BE x y z slot<i>=<item_id>:<count>` - one line
  per non-empty slot.

`S` is a real, vanilla-reachable, stable state: no pending ticks, no cell
about to change on its own. Tick 0 then behaves exactly as before: tick-0
actions are applied, and `T 0` is recorded as a delta **relative to `S`**
(the recorder's baseline is seeded from the `S` snapshot, not from air).

### Action timing convention

Same as `FluidOracle`: a tick-`t` action is applied immediately *before*
`T t` is recorded if `t == 0` (so tick-0 actions are visible starting in the
`T 0` delta), and immediately *after* `T t` is recorded for `t > 0` (so a
tick-`t` action's effects first appear in the `T t+1` delta, once vanilla
has had a tick to react to it).

### E lines

`E <relTick> x y z <block_id> <paramA> <paramB>` - one line per block event
(`BlockEventData`) fired during that tick, in the order the level's event
set produced them, listed directly under the `T <tick>` line before the `C`
deltas.

### Isolation

`RedstoneTorchBlock.RECENT_TOGGLES` is a static, per-level `WeakHashMap` that
tracks each torch's rapid-toggle history for burnout detection. Since every
scenario shares the same in-process level, the harness clears it via
reflection right before each scenario's setup is placed, so burnout state
(and everything else keyed off it) never leaks from one fixture's capture
into the next regardless of run order.

## Verification: fixture vs. spec (section 6 of g12-redstone-preresearch.md)

| Scenario | What the fixture shows | Spec expectation | Match? |
|---|---|---|---|
| `wire_falloff_16` | Lever on at T0, wire power falls 15→...→1 across 15 blocks, block 16 stays 0. | Falloff 15→1, block 16 at power 0. | Yes |
| `wire_branching_t` | Stem lever on at T0; power propagates symmetrically into both 5-block arms at the junction. | Symmetric branch propagation. | Yes |
| `wire_stair_climb` | Lever on at T0; wire connects and powers up all 3 steps to the top. | Vertical stair connectivity. | Yes |
| `wire_stair_occlusion` | S settles with lever off, dot wires at power 0 (now vanilla-reachable). Lever on at T0 powers steps 1–2; the stone block above step 2 occludes the vertical link to step 3, which stays unpowered. | Conductor edge occlusion breaks the vertical link. | Yes |
| `torch_inversion` | Torch lit at S/T0 (block unpowered). Wire on at T0 → torch goes unlit at T2 (2-tick delay). Wire off at T4 → torch relights at T6. | 2-tick deactivation and reactivation delay. | Yes |
| `torch_tower` | Lever on at T0; each alternating torch level flips exactly 2 ticks after the one below it (T2, T4, T6, T8, T10 for the 5 tiers). | 2 ticks per level, 10 ticks total for 5 tiers. | Yes |
| `torch_burnout_clock` | Wire placed by a tick-0 action (not setup, since this is the clock). Torch toggles on a 2-tick queue through T30, then the 8th toggle inside the 60-tick window burns it out; it stays `lit=false` through T200 (160-tick restart timer not yet elapsed, and the queued 2-tick restart lands on the same tick as the 160-tick one, which vanilla drops as a duplicate). | Burnout after 8 toggles/60 ticks, 160-tick extinguish. | Yes (unchanged from the prior fix; re-verified against the new settle-based harness) |
| `repeater_delays` | Lever on at T0; the 4 repeaters (delay 1–4) each flip exactly 2/4/6/8 ticks later, all still visible together once settled at their respective delay ticks. | Delays 2, 4, 6, 8 ticks. | Yes |
| `repeater_locking` | A powered on at T0 (2-tick delay to T2), B powered on at T2 (locks A at T4), A's input cut at T4 (A stays powered - locked), B's input cut at T8 (unlocks A, which then updates). | Locking via side input, retention while locked, unlocking. | Yes |
| `repeater_pulse_stretch` | Lever pulsed on+off within tick 0 into a delay-4 repeater; output still stretches to the repeater's minimum pulse width (4-tick high) despite the 1-tick input. | `VERY_HIGH` turn-on reschedule preserves minimum output pulse. | Yes |
| `repeater_priority_drain` | S settles with A powered by an always-on lever (repeater A `powered=true`), B unpowered. At T0 both levers flip (A's off, B's on); both repeaters schedule for the same absolute tick, and the queue drains A's turn-off (priority −2, `VERY_HIGH`) before B's turn-on (priority −1, `HIGH`). | Sub-tick queue drain order by `TickPriority`. | Yes (scenario redesigned during this pass - see below) |
| `comparator_compare` | S settles with rear wire at power 10 (redstone block 6 cells back), comparator output 10, side wire unpowered. Side block placed at T0 gives side power 8 at the comparator (output stays 10, side ≤ rear). Side block moved at T4 gives side power 12 (output drops to 0 at T6, side > rear, 2-tick delay). | Rear 10; side 8 → output unchanged; side 12 → output 0; 2-tick delay. | Yes (side-block placement corrected - see below) |
| `comparator_subtract` | S settles with rear wire at power 13, output 13 (side 0). Side block at T0 gives side power 5 → output 8. Side block moved at T4 gives side power 9 → output 4. | Rear 13, side 5 → output 8, side 9 → output 4. | Yes (side-block placement corrected - see below) |
| `comparator_container` | Barrel filled with 1 stack via a tick-0 action; comparator turns on 2 ticks later (`output=0`→ signal 1 → `powered=true`). Second fill (9 stacks, signal 5) at T2 is absorbed into the same settled/delta chain. | `getAnalogOutputSignal` container fill ratio. | Yes |
| `comparator_through_block` | Barrel behind a solid stone conductor; comparator still reads the analog signal through the block and turns on 2 ticks after the fill action. | Conductor passthrough of container signal. | Yes |
| `observer_single_pulse` | Stone placed at T0 in front of the observer; observer pulses `powered=true` at T2, back to `false` at T4 - a clean 2-tick-on pulse with 2-tick activation delay. | 2-tick delay, 2-tick pulse, 2-tick deactivation. | Yes |
| `observer_clock` | Second observer placed at T0 facing the first; both start pulsing each other on a steady 2-tick cadence from T2 onward. | Continuous 2-tick clock, deterministic phase. | Yes |
| `lever_power_types` | Lever on at T0: the wire strongly powered through the attached block reads full power (block conducts the lever's strong power onward), the wire directly on the lever's floor face reads weak/direct power only. | Strong power through the attached block vs. weak power to the lever's own face. | Yes |
| `button_stone_duration` | Stone button pressed at T0 (`powered=true`); stays powered until it flips back to `false` exactly at T20. | 20-tick press duration. | Yes |
| `button_wood_duration` | Oak button pressed at T0; flips back to `false` exactly at T30. | 30-tick press duration. | Yes |
| `lamp_timings` | Lever on at T0: lamp lights the same tick (`lit=true` in the T0 delta, 0-tick turn-on). Lever off at T4: lamp stays lit until T8 (4-tick turn-off delay). | Instant on, 4-tick delayed off. | Yes |
| `piston_qc_bud` | Redstone block placed at `pos.above().north()` at T0: piston does not extend (no direct neighbor update reaches it). Explicit neighbor update at T4: piston extends. | Quasi-connectivity: no extend at T0, extends on the T4 neighbor nudge. | Yes |
| `piston_push_limit_12` | Both levers on at T0. The 12-block column (piston A) pushes fully. The 13-block column (piston B) fails to move (`MAX_PUSH_DEPTH = 12` exceeded). | 12 pushes, 13 fails. | Yes |
| `piston_slime_honey_split` | Lever on at T0: sticky piston pushes the slime+stone column; the adjacent honey column (not directly touched by the piston or the moving slime's push face) stays stationary. | Slime/honey adhesion split resolves without jamming. | Yes |
| `piston_block_drop` | Lever on then off within a 2-tick window at T0/T2: piston extends and immediately retracts on the drop-trigger path, leaving the stone block behind rather than pulling it back. | `TRIGGER_DROP`: retract without pulling the held block. | Yes |
| `piston_block_events_fifo` | Lever on at T0 powers both pistons' backing wire simultaneously; both extend, block events drain and pistons act in the level's FIFO event order. | Block event FIFO drain order. | Yes |
| `update_order_tie` | Lever on at T0 feeds both repeaters (A facing east, B facing south) into the shared wire junction simultaneously; the recorded update/drain order matches `NeighborUpdater.UPDATE_ORDER` (MC-11193). | Update ordering resolved per `NeighborUpdater.UPDATE_ORDER`. | Yes |
| `piston_block_events_contention` | Both redstone blocks placed at T0 (`E 0 0 100 0 piston 0 5` then `E 0 4 100 0 piston 0 4`). At T1 the piston at (0,100,0) is `extended=true` with its stone moving into (2,100,0); the piston at (4,100,0) stays `extended=false` and keeps its stone at (3,100,0). | The first-drained event moves its block; the second resolver finds the winner's MOVING_PISTON in the destination and fails. | Yes |
| `piston_settle_power_cut` | `E 0` carries both enqueues (the occluder piston first, then the sticky piston under test). Both extend at T1. At T3 the occluder's pushed-block placeholder settles the stone onto the wire (3,101,4) directly above it, that wire's climb collapses to `power=0`, and in the same tick the sticky piston's retraction is enqueued as `E 3 4 100 4 sticky_piston 1 5` (TRIGGER_CONTRACT). At T4 the stone at (6,100,4) is pulled back to (5,100,4). | With `handlingTick` false during the block-entity pass, `checkIfExtend` fails all three disjuncts and contracts; with it still true it would drop. | Yes |

### Coverage gaps closed after slice 5

Two fixtures were added on top of the original 27 because slice 5 shipped
with coverage that could not fail in the places it looked like it did. Both
were captured with the standard `RedstoneOracle` path above; neither
existing fixture was modified.

- **`piston_block_events_contention` - the block-event drain order.**
  `piston_block_events_fifo` pins the *enqueue* order and the resulting cell
  set, but its two pistons extend identically whichever event runs first, so
  swapping the drain to LIFO (`removeLast()`) still passed it. The new
  scenario makes the two queued extensions race for one destination cell: the
  first-drained event extends, and the second resolver then sees the winner's
  `MOVING_PISTON` in the shared cell and fails, so which piston ends up
  extended is a function of the drain order alone. Verified by mutation:
  with `removeLast()` the new fixture fails at `T 1` (vanilla shows
  `C 0 100 0 piston[extended=true,facing=east]`, the mutated build shows
  `C 2 100 0 moving_piston[facing=west]` - the west-facing piston won instead)
  while `piston_block_events_fifo` still passes, confirming the old fixture's
  blind spot and the new fixture's coverage.
- **`piston_settle_power_cut` - `handlingTick` across the block-entity pass.**
  `TickLoop.tick` used to hold the engine's `handlingTick` flag for the whole
  tick body, including the block-entity pass, where vanilla has already
  cleared it (`ServerLevel.java:408`, after `runBlockEvents` and before
  `tickBlockEntities`). The only reader is `PistonBaseBlock.checkIfExtend`
  (`PistonBaseBlock.java:119`), and the flag only matters when the pusher's
  target holds an extending `MOVING_PISTON` with `getProgress(0.0F) >= 0.5`
  and `gameTime != lastTicked` - that is, exactly when a *settling* piston
  fires a neighbour update into another piston during the block-entity pass.
  The scenario builds that: a stuck piston is mid-extension with progress
  0.5 when a second, earlier-started piston's placeholder settles a stone
  onto the wire feeding it, so the power drop reaches it from inside the
  block-entity pass. Vanilla's `T 3` enqueue is the contract event (1) and
  the held stone is pulled back at `T 4`; the pre-fix Kestrel build enqueued
  the drop event (2) instead and left the stone out at (6,100,4). With
  `TickLoop` clearing the flag after `runBlockEvents` all 29 fixtures pass.

Additions to `RedstoneScenarios.all()` are append-only: a new scenario must
not renumber, retitle or re-lay out an existing one, because every fixture is
a recording of that scenario's exact setup.

### Fixes applied in this pass

- **`comparator_compare` / `comparator_subtract`**: the original side-signal
  block placements were off by one wire cell (a leftover from when the rear
  line was pinned raw at an unreachable power value). Recomputed against the
  wire falloff formula `power(z) = 16 − Z_source + z` and moved the
  placements so the comparator's *side* input actually reads 8→12
  (compare) and 5→9 (subtract) as the spec calls for; rear (10, 13) was
  already correct once the S-section settle made it a real value instead of
  a raw-placed one.
- **`repeater_priority_drain`**: the original setup raw-placed repeater A as
  already `powered=true` with no power source ever behind it - an
  unreachable start state the settle pass now correctly collapses back to
  `powered=false`. Redesigned so A is fed by a lever that starts on (settles
  to a genuine `powered=true`) and B by a lever that starts off; both levers
  flip at tick 0, producing real falling/rising edges that land on the same
  drain tick and exercise the `VERY_HIGH`/`HIGH` priority order the scenario
  is meant to test.

No other scenario needed a layout change; all 12 fixtures previously
affected by the raw-setup/unreachable-dot-wire bug (`comparator_compare`,
`comparator_subtract`, `comparator_container`, `comparator_through_block`,
`observer_clock`, `observer_single_pulse`, `repeater_delays`,
`repeater_locking`, `repeater_priority_drain`, `repeater_pulse_stretch`,
`update_order_tie`, `wire_stair_occlusion`) now settle to a real vanilla
start state before `T 0` and match their spec row.
