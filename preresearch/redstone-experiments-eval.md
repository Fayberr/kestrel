# Redstone experiments in 26.2: evaluation

Status: EVALUATED 2026-09-10, grounded in Vineflower-decompiled 26.2
source read in full (not inferred from behavior). Scope of this note:
evaluation of Mojang's `REDSTONE_EXPERIMENTS` system specifically -
problems found, open risks, and what it means for Kestrel. The default
path stays the parity target (see `redstone-path-analysis.md`).

## What the experiment actually is

`FeatureFlags.REDSTONE_EXPERIMENTS` (`redstone_experiments`, vanilla
universe, created in `FeatureFlags` static init). When ON, three things
change together:

1. **Wire evaluator swap** (`RedStoneWireBlock.updatePowerStrength`):
   `DefaultRedstoneWireEvaluator` (recompute-one-wire + recursive
   neighbor dispatch) is replaced wholesale by
   `ExperimentalRedstoneWireEvaluator` (BFS over two queues +
   packed orientation+power map, single setBlock per changed wire,
   orientation-ordered neighbor updates).
2. **Update plumbing carries an Orientation everywhere.** `ServerLevel`
   creates one for every parameterless `updateNeighborsAt` call
   (`ExperimentalRedstoneUtils.initialOrientation(this, null, null)`),
   `CollectingNeighborUpdater.MultiNeighborUpdate` lazily creates one
   and threads `withFront(direction)` per neighbor execution, and 13
   signal-source blocks stamp their own orientations on dispatch
   (repeater/diode, observer, torch, lever, button, lightning rod,
   tripwire hook, lectern, trapped chest, piston base/head/body).
3. **Wire-to-wire updates stop being recursive.** `neighborChanged`
   ignores calls where `block == this` while the flag is on (the BFS
   already computed the network); non-wire neighbor notifications
   still flow through `causeNeighborUpdates` at the end.

Flag OFF (default world): every one of these paths returns null /
skips, and the extra parameter is dead weight. Verified per call site.

## How to turn it on (and what that implies for testing)

- New world via the Create-World screen: Experiments toggle. Existing
  world: `datapack enable` path filters packs by
  `getRequestedFeatures().isSubsetOf(enabledFeatures)` - the LEVEL's
  flag set gates content, so a world created without the flag cannot
  just flip it on by command (world-load enforcement in
  `WorldDataConfiguration` / `LevelStorageSource`).
- Consequence for the harness: default-vs-experimental comparison
  needs TWO worlds with the same seed + same corpus, not one world
  toggled mid-run. The open step-7 item in `redstone-path-analysis.md`
  stands, with this constraint added.
- Note: Mojang's own `GameTestServer` EXCLUDES both
  `REDSTONE_EXPERIMENTS` and `MINECART_IMPROVEMENTS` from its enabled
  set - their automated tests run the default path.

## Problems found (ranked by severity)

### P1. Orientation seed is RNG-drawn: determinism rests on world seed, not on the algorithm

`getInitialOrientation`: incoming null orientation ->
`Orientation.random(level.getRandom())`, then forced
`.withUp(UP).withSideBias(LEFT)` but the FRONT (which neighbor is
"front", i.e. visited first) stays random. `ServerLevel`'s
parameterless `updateNeighborsAt` - the most common entry - always
passes null/null, so EVERY propagation's traversal order starts from
a level-RNG draw. Two consequences:

- Same contraption in two worlds (different seeds) can settle in a
  different ORDER and, where order is observable (piston timing,
  torch burnout interleaving, 0-tick edges), a different RESULT.
  Repro from the corpus: build the torch line T in a fresh world and
  tick-by-tick power traces can legitimately differ seed to seed.
  This is not a theoretical complaint: prior sessions established
  that update-order chaos is observable behavior (MC-11193), and the
  experimental evaluator replaces one chaos source with a seeded one.
- Kestrel impact: a differential harness that compares tick hashes
  across runs MUST pin the world seed AND the exact call sequence
  feeding `level.getRandom()` before the propagation, or mismatches
  prove nothing. Our existing `determinism-check.ps1` (same world,
  two runs) is unaffected; cross-world default-vs-experimental
  comparison must control seed explicitly.

Verdict: works as designed for a single world, but "deterministic
traversal order" (the AC comparison in the path analysis) is only
true per seed, not universally. Document, do not fix - it is vanilla
behavior under the flag.

### P2. `IllegalStateException("Turning off wire while trying to turn it on")` is reachable by construction, not just corrupt state

Turn-on phase (`calculateCurrentChanges`, second loop): any wire
whose recomputed power is LOWER than the overlay value throws. The
overlay value for a wire already visited in the turn-off phase is 0
or stale-pass power, recomputed from CURRENT block reads
(`getBlockSignal` + `getIncomingWireSignal` read live level state,
not the overlay - except `getWireSignal`, which IS overlay-aware).
Feedback through a live block read that changed between phases
(torch toggle recorded in `RECENT_TOGGLES`, repeater scheduled tick
firing mid-propagation, a second `neighborChanged` re-entering on a
non-wire path) lowers the recompute below the overlay, and the
server thread throws out of a block update - crashing or (inside a
`CollectingNeighborUpdater` batch) aborting the remaining queued
updates. The throw reads as an "should never happen" assert, but the
inputs it compares (overlay vs live world) are time-separated by
design: the turn-off loop runs informational block reads, neighbor
enqueueing, and arbitrary `getBlockState` traffic between the write
of the overlay value and the turn-on recompute. Any concurrent
scheduled tick (repeaters, torches) landing in that window feeds a
different live value into the second read.

Concrete trigger shape (needs harness proof, not yet run): clock
corpus C (repeater clock driving a wire line) with the flag on - the
repeater's scheduled tick can fire between the two BFS phases while
its output wire sits in `wiresToTurnOn`. If the recompute then reads
the repeater's new output as lower than the overlay, the throw fires.

Kestrel impact: if we ever gate behavior on this evaluator, that
throw must be treated as a parity-relevant crash vector (vanilla
with flag ON throws; default path never throws here). A port MUST
reproduce the throw-or-not bit-exactly, which means reproducing the
interleaving window - strong argument for keeping the default path
as the only parity target and the experiment as reference data.

### P3. Per-propagation allocation: new evaluator + ArrayDeques + fastutil map on EVERY update

`RedStoneWireBlock.updatePowerStrength` does
`new ExperimentalRedstoneWireEvaluator(this)` per call - fresh
`ArrayDeque` x2 + `Object2IntLinkedOpenHashMap` per wire update
entry, plus `BlockPos` keys (immutable copies) per visited wire and
`Orientation` lookups per edge. The default path allocates too (a
`HashSet` per changed wire), but the experimental one allocates its
whole BFS workspace even for a 1-wire no-change propagation (the
`calculateCurrentChanges` seed + map compute run before anything
knows whether power moved). On a settled line S with a lever
re-flicked to the same power, the default path does one recompute
and stops; the experimental path still builds the workspace, seeds
the queues, runs both loops over the neighborhood, then writes
nothing. For Kestrel's step-3 targets this matters: the "Mojang
already shipped the answer" hope from the path analysis does not
extend to allocation - an exact port inherits per-update workspace
allocation, and pooling it changes object identity (BlockPos keys in
a reused map) without changing semantics only if done carefully.

### P4. `contains` scans on ArrayDeque: O(depth) membership per edge

`enqueueNeighborWire` and the turn-off loop call
`wiresToTurnOn.contains(pos)` / `wiresToTurnOff.contains(pos)`
(`ArrayDeque.contains` = linear scan, `BlockPos.equals` per
element). On a deep line (S corpus: 41 wires; real builds: hundreds)
each edge enqueue scans the queue. Total enqueue cost is
O(V * queue-depth), i.e. quadratic in propagation depth in the worst
case - the exact pathology BFS is supposed to avoid. A port would
want a dirty-bit set alongside the deque; behavior-identical (same
order, same contents) since membership semantics don't change, only
the lookup cost. Flag as a Kestrel-side improvement if the
experimental path is ever ported; do NOT "fix" it in analysis code.

### P5. Dual read inconsistency: overlay-aware wire reads vs live block reads in the same recompute

`getWireSignal` is overridden to consult `updatedWires` first (dirty
overlay), but `getBlockSignal` (torches, levers, repeaters via
`getBestNeighborSignal`) and the conductor/shape checks inside
`getIncomingWireSignal` read LIVE level state. Mid-propagation the
two disagree by design (overlay holds the future, level holds the
present). The turn-off loop's `newPower = max(blockPower_live,
wirePower_overlay-mixed)` therefore mixes time domains in one
expression. Consequences: (a) the P2 throw above; (b) a wire whose
only power source is a torch that toggles mid-propagation can settle
at a power neither the start state nor the end state would produce
in a single atomic recompute - an intermediate-state artifact
SPECIFIC to the experimental evaluator, distinct from default-path
MC-11193 chaos. Any differential test must compare full transient
traces, not just final powers (our hasher already hashes every tick,
so the harness is ready; the comparison just hasn't been run).

### P6. Neighbor-update fan-out is NOT reduced for the mixed case

The headline ("single setBlock per changed wire") holds for wires.
But `causeNeighborUpdates` still calls `level.neighborChanged` per
connected direction per changed wire, PLUS the conductor echo (for a
wire against a conductor: re-notify all of the conductor's other
sides). A wire line running alongside stone notifies ~2x per wire -
same order of dispatch traffic as the default path's 7 dispatches
per change, just reordered through orientation traversal. And every
non-wire neighbor (lamp, piston, door, bulb - all orientation-blind,
see below) still does its full `hasNeighborSignal` recompute per
notification. Expected net: fewer block WRITES, similar notification
COUNT. The tick-cost delta (open measurement) may be much smaller
than the algorithm suggests - measure before claiming.

### P7 (minor). `useExperimentalEvaluator` is checked per call, not per propagation

`level.enabledFeatures().contains(...)` runs on every
`updatePowerStrength`, every `initialOrientation`, every
`MultiNeighborUpdate.runNext` step. `FeatureFlagSet.contains` is
cheap (bitset), but it sits inside the hottest per-wire call pair
(update + orientation unpack per visited wire). Negligible alone;
listed so a port hoists it once per propagation instead of cargo-
culting the per-call check.

### P8 (minor). Debug subscription hook runs even when nobody listens - guarded, cheap

`causeNeighborUpdates` checks `hasAnySubscriberFor` before sending
per-wire orientation values. Correct pattern, no problem; noted
because a naive port might drop the guard or the values. Keep both.

## What is NOT a problem (checked, cleared)

- **The 38 orientation-signature blocks are almost all passive.**
  Of 55 `Orientation` references in the jar, 17 are plumbing
  (`NeighborUpdater`, both updaters, `Level`/`ServerLevel`,
  `BlockBehaviour`, debug). Of the 38 block classes, ~25 take the
  parameter and ignore it (door, trapdoor, fence gate, TNT, hopper,
  crafter, dispenser, lamp... - their `neighborChanged` bodies never
  touch `orientation`). Only the experimental evaluator itself plus
  `PistonHeadBlock` (`withFront` passthrough) CONSUME it, and 13
  blocks SOURCE it. Blast radius of a behavior change is therefore
  small: wire + torch + repeater + observer + piston + lever/button
  paths. Lamps, doors, bulbs, rails behave identically under both
  flags (same recompute, orientation ignored).
- **Signal math is shared.** `RedstoneWireEvaluator.getIncomingWireSignal`
  (horizontal + above/below conductor logic, -1 falloff) is the
  superclass method used by BOTH evaluators. The falloff rule and the
  conductor checks cannot diverge between flags - only traversal,
  write batching, and notification order differ.
- **`shouldSignal` guard unchanged.** `getBlockSignal` flips the same
  reentrancy flag in both paths (it lives on the block, outside the
  evaluators). No new feedback hole introduced by the experiment.
- **Torch burnout logic untouched.** `RECENT_TOGGLES`
  (`WeakHashMap<BlockGetter, List<Toggle>>`, 8-strike limit) is
  identical under both flags. Burnout timing can still SHIFT under
  the experiment (different notification order -> different toggle
  interleaving), but the mechanism and limits are the same code.
- **Flag 128 is named and safe.** The experimental write path uses
  `2 | 128` = `UPDATE_CLIENTS | UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE`
  (real constant in `Block`, not a magic number). Shape updates are
  deferred to the orientation-ordered `causeNeighborUpdates`, not
  skipped - connections still resolve. A port must preserve the two-
  phase split (write powers first, shapes/notifications after).
- **Default path is byte-identical with the flag off.** Every
  experiment call site goes through
  `ExperimentalRedstoneUtils.initialOrientation`, which returns null
  unless the flag is on, and `useExperimentalEvaluator` gates the
  evaluator swap. No behavior leaks into default worlds. (This is
  also why Moyang's gametests exclude the flag: they test default.)

## Kestrel consequences

1. **Default path remains the sole parity target.** The experiment
   changes traversal order (seeded RNG), intermediate states (P5),
   and crash vectors (P2) - porting it as "the fast path" would move
   the parity goalposts. Step 3 targets (no-op suppression,
   connection cache, flat layout, all order-preserving) are
   unaffected by this evaluation.
2. **The experiment is reference data, not a shortcut.** Its best
   ideas (compute-before-write, one setBlock per changed wire,
   per-neighbor relevance in `causeNeighborUpdates`) are portable as
   techniques onto the DEFAULT traversal order - that is the real
   win available here, and it avoids P1/P2/P5 entirely.
3. **The P4 fix (membership set alongside the deque) is the one
   safe micro-win** if the BFS is ever ported: same order, same
   contents, linear -> O(1) lookup. Everything else about the
   evaluator must stay bit-exact or stay out.
4. **Differential test design (open item, now specified):** two
   worlds, same seed, same `redstone-line` corpus; capture per-tick
   power traces (blockstate POWER per wire pos, every tick) under
   both flags; compare (a) final powers, (b) transient sequences,
   (c) setBlock counts, (d) notification counts, (e) throw-or-not on
   clock corpus C. Decides step-7 scoping (AC port still needed?).
   World-creation constraint: flag set at creation, cannot flip an
   existing world by command.

## Reproducibility appendix (so anyone can re-verify)

- Jar: `preresearch/vanilla-26.2/server.jar` (bundler) ->
  inner `META-INF/versions/26.2/server-26.2.jar`, extracted locally,
  never committed (gitignored).
- Classes read in full via Vineflower 1.11.1 on JDK 17:
  `ExperimentalRedstoneWireEvaluator` (227 lines),
  `DefaultRedstoneWireEvaluator` (43),
  `RedstoneWireEvaluator` (49), `RedstoneWireBlock` (513),
  `Orientation` (205), `ExperimentalRedstoneUtils` (31),
  `CollectingNeighborUpdater` (219), `NeighborUpdater` (dispatch),
  `DiodeBlock` (scheduling/passthrough), `RedstoneTorchBlock`
  (burnout map), `LeverBlock`/`ButtonBlock`/`ObserverBlock`/
  `CopperBulbBlock`/`RedstoneLampBlock` (source/consumer roles),
  `PistonBaseBlock`/`PistonHeadBlock`/`PistonMovingBlockEntity`,
  `LightningRodBlock`/`TripWireHookBlock`/`LecternBlock`/
  `TrappedChestBlockEntity`/`RedstoneWallTorchBlock`,
  `ServerLevel` + `Level` neighbor plumbing,
  `FeatureFlags` + `GameTestServer.ENABLED_FEATURES`,
  `Block` update-flag constants.
- Jar-wide bytecode scan (constant-pool substring over all 7,445
  classes): 55 reference `Orientation` (17 plumbing, 38 blocks),
  17 reference `ExperimentalRedstoneUtils`, 6 reference
  `REDSTONE_EXPERIMENTS` (`RedStoneWireBlock`,
  `CollectingNeighborUpdater$MultiNeighborUpdate`,
  `ExperimentalRedstoneUtils`, `FeatureFlags`, `GameTestServer`,
  `data/Main`). Full consumer list is in session history; the
  per-block source/consume/ignore roles above are the distilled
  result.
