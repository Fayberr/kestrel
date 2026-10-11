# Optimization #3 grounding: redstone dust

Status: ANALYSIS, grounded in the real decompiled vanilla 26.2 source
(`RedStoneWireBlock.java` 526 lines, `DefaultRedstoneWireEvaluator` 43
lines, `ExperimentalRedstoneWireEvaluator` 226 lines, base
`RedstoneWireEvaluator` read in full via Vineflower against the
deobfuscated jar - no mapping toolchain needed).

## The headline: vanilla ALREADY ships the fast algorithm (gated)

26.2 contains TWO evaluators behind the `REDSTONE_EXPERIMENTS` feature
flag (`level.enabledFeatures()`, checked per call in
`RedStoneWireBlock.updatePowerStrength`):

- **Default (live):** `DefaultRedstoneWireEvaluator` - recompute-one-wire
  (`calculateTargetStrength` = max(external signal, incoming wire - 1)),
  set block if changed, then `updateNeighborsAt` on self + all 6
  neighbors. Each neighbor wire re-runs the whole thing recursively
  through the neighbor-changed dispatch. A power change at one end of an
  N-wire line costs O(N) block sets x O(6) neighbor updates each, and
  every intermediate wire re-reads its 4 horizontal neighbors (+
  above/below conductor checks) to recompute - so a single toggle does
  O(N) full recomputes of O(1) neighborhoods, each firing O(6) downstream
  updates. Correct, but update-fanout-heavy: the cost is in the
  `updateNeighborsAt` cascade, not the arithmetic.
- **Experimental (flagged):** `ExperimentalRedstoneWireEvaluator` - BFS
  propagation with two work queues (`wiresToTurnOff`, `wiresToTurnOn`) +
  a packed orientation+power map (`updatedWires`). Computes the whole
  affected network BEFORE writing any block: turn-off pass propagates
  zeros outward, turn-on pass propagates new power, then a single
  `setBlock` per actually-changed wire + orientation-ordered neighbor
  updates. This is Mojang's own answer to the same problem Alternate
  Current solves (deterministic traversal order instead of locational
  update chaos), and it ships IN the jar, just not enabled by default.

Implication for the plan's step 3 + step 7 (Alternate Current as opt-in):
the "exact-only skip no-op updates + flat layout" target stands for the
default path, but the experimental evaluator deserves a real differential
test before writing any AC port - if Mojang's own BFS evaluator is already
parity-acceptable (it is vanilla code, so by definition it preserves
whatever IT preserves), enabling/embedding it may obsolete half the
custom redstone work.

## The actual expensive paths (default evaluator)

**A. Neighbor-update fanout per changed wire.** Every wire whose POWER
changes fires `updateNeighborsAt` on itself + 6 neighbors (7 dispatch
calls). Each dispatch re-enters `neighborChanged` on every neighbor block,
which for neighboring wires re-runs `updatePowerStrength` recursively.
A line of N wires changing: N setBlocks x 7 dispatches, with most
dispatches re-checking wires that will not change. The dispatch itself
(neighbor lookup, shape check, flag handling) dominates the arithmetic by
an order of magnitude.

**B. Redundant recompute of settled wires.** `calculateTargetStrength`
re-reads external signal (`getBestNeighborSignal`: scans all 6 neighbors'
shapes/signals) + `getIncomingWireSignal` (4 horizontal neighbors + 2
conditional above/below reads each, with `isRedstoneConductor` shape
checks). A wire in the middle of a settled line recomputes this full
neighborhood every time ANY neighbor toggles, even when its own power
cannot change (already at max from another direction). No memoization, no
"my inputs didn't change" check.

**C. Connection-state recompute on the signal path.** `getSignal` calls
`getConnectionState` (full neighbor-connection resolution: 4x
`getConnectingSide` with face-sturdiness + conductor checks) on EVERY
signal read, not just when connections change. Signal reads happen per
neighbor update per wire - so the O(4-neighbor) connection resolve
multiplies the O(7) fanout. Connections only change on block
place/remove; caching them with invalidation on neighbor-shape change
removes this entire term.

## What vanilla does NOT do (the concrete Kestrel targets, default path)

1. **No no-op update suppression.** `updatePowerStrength` DOES skip the
   setBlock when power is unchanged... but still fires all 7 neighbor
   updates unconditionally after ANY change, including changes that
   provably cannot affect a given neighbor (power dropped 15->14 still
   re-notifies the neighbor that reads max-1 = already satisfied from
   another side). Target: per-neighbor relevance check before dispatch
   (would this neighbor's recompute even see a different input?).
2. **No connection caching.** See path C above: cache per-wire connection
   sides, invalidate on shape updates only. `updateShape` already runs on
   exactly the right signal (neighbor block change) - hook it.
3. **No settled-network skip.** A fully-settled network (no pending power
   changes) still re-propagates the whole cascade on any external toggle,
   including toggles that resolve to the same power (lever flicked twice).
   Target: compare-then-propagate at the entry (the experimental evaluator
   already structures this correctly - another reason to test it first).
4. **Flat layout for the changing case.** The per-wire data touched during
   propagation (power, 4 connection sides, neighbor refs) is pointer-chased
   across `Level.getBlockState` (chunk lookup + section + palette decode)
   per read. Target: dense per-chunk-section wire arrays (power byte +
   connection nibble per wire position), populated once per propagation,
   written back once. Same traversal order, same code path - only the
   storage is flat.

## Parity constraints (must replicate exactly)

- **Update-order chaos (MC-11193) is OBSERVABLE on the default path.**
  The plan already settled this: everything exact including transients,
  intermediate settle states, 0-tick, locational quirks. The default
  evaluator's recursive dispatch order IS the chaos - any "same final
  power, cleaner order" change is the opt-in AC flag, never the default.
  The experimental evaluator has its OWN deterministic order (orientation
  BFS) which is also observable and differs from default - enabling it is
  a behavior change vs default vanilla, test accordingly.
- **POWER is a blockstate (0-15).** Every power change is a `setBlock`
  (blockstate swap + shape re-resolve + neighbor dispatch). Cannot
  "update power in place" - the state object is immutable and shared.
  The flat-layout target must write back through real setBlock (or prove
  the bypass identical tick-by-tick, which is the whole harness's job).
- **`shouldSignal` reentrancy guard.** `getBlockSignal` flips
  `shouldSignal` false during `getBestNeighborSignal` to avoid wire
  self-power feedback. Any port that reads neighbor signals must preserve
  this guard window exactly.
- **RedstoneSide connections (NONE/SIDE/UP) affect shape AND signal.**
  `getSignal` returns 0 toward directions the connection state does not
  connect to - connection caching must invalidate on the same signal that
  changes shape (`updateShape` with horizontal directions), or signal
  reads go stale.

## Next steps (in order)

1. Redstone corpus for the harness: settled line (long, powered, then
   idle - the no-op suppression target), toggling clock (active worst
   case), cross/dot variants (connection states), torch-powered line,
   repeater/comparator feeds (external-signal path). Power changes must be
   hash-visible (blockstate POWER is already in the block hash - verify).
2. Differential-test the EXPERIMENTAL evaluator first: enable
   `REDSTONE_EXPERIMENTS` on a test world, capture the same corpus under
   both evaluators, quantify divergence (final powers? transients? order?)
   + measure tick cost delta. This decides whether step 7 (AC port) is
   still needed or Mojang already shipped the answer.
3. Only then: implement suppression/connection-cache/flat-layout targets
   in `core/` against the default path.

## Step-3 default-path implementation (2026-09-10, commit d09611d)

`core/src/vanilla/java/dev/kestrel/redstone/`: three classes + one proof,
all grounded in the decompiled 26.2 source verified this session:

- **`WireConnectionCache`**: per-wire cached 4-side connection state
  (N/E/S/W RedstoneSides). Values enter only via `reportResolved` with
  vanilla-resolved sides; validity is generation-scoped
  (`onNeighborChanged` bumps, `invalidate` is surgical from the
  shape-update path). Same memo-not-reimplementation pattern as
  `HopperState`'s attach generation.
- **`WireUpdateFilter`**: no-op dispatch suppression planner. Input: old
  power, new power (different; equal powers keep nothing, mirroring
  vanilla's existing outer skip), per-neighbor relevance from real vanilla
  state. Rules: R1 absolute-ceiling (neighbor wire already >= both
  candidate inputs), R2 binary monotonicity (rising into ON / falling into
  OFF skips; ANALOG never skipped), R3 connection gating (NONE side toward
  us skips), R4 self-dispatch always kept (shape/observer/second-order
  effects). Output follows vanilla's 7-slot dispatch order (self, DOWN,
  UP, N, S, W, E). Null neighbor info fails open (kept).
- **`WireSectionLayout`**: flat per-section (16x16x16) scratch store
  (power byte + connection nibble, section-local index
  `(y << 8) | (z << 4) | x`), populated from real blockstates, committed
  only through real `setBlock`. Cross-section reads fall back to live
  lookup; the layout never fabricates out-of-section data.
- **`RedstoneProofCheck`**: unit proof, PASS live on bench PC (JDK 25,
  real 26.2 classes): cache resolve/snapshot/invalidate/generation-bump
  lifecycle, every filter rule incl. the settled-line case (middle wire
  15->14 with both flanks at 15: 5 kept, both flanks skip), layout
  pack/unpack + index math incl. negative coords, vanilla dispatch-domain
  (7 targets) + POWER setValue/getValue round-trip.

Corrections to the earlier analysis found while implementing (decompiled
source wins over the first writeup):
- `updatePowerStrength` DOES skip the setBlock when power is unchanged
  (outer `if (POWER != target)`), but the 7-dispatch cascade only runs
  inside the changed branch - there is no "fires 7 updates after an
  unchanged recompute" case. The filter therefore plans the changed-power
  case only; equal powers = keep nothing.
- The dispatch set is built in a `HashSet` (`Sets.newHashSet`), so
  iteration order is allocation-dependent, not source order - the filter
  returns vanilla's SLOT order (self, DOWN, UP, NORTH, SOUTH, WEST, EAST),
  which is the deterministic contract, not the HashSet's accident.
- `neighborChanged` with `block == this` is NOT ignored on the default
  path (that ignore is experimental-only); default-path wires recurse
  through neighbor dispatch, which is exactly the fanout this step trims.
- `getSignal` re-resolves connections via `getConnectionState` (not a
  stored field read), confirming path C; `getIncomingWireSignal` reads
  neighbor POWERS directly (no connection check on that path), so R3
  applies to the `getSignal`/`getBlockSignal` direction only - passed as
  caller-observed connectedness, never assumed.

Live proofs (bench PC, 2026-09-10): `RedstoneProofCheck: PASS`;
`redstone-line` oracle re-verified PASS (869 ticks compared, bundler jar,
`-SkipTicks 5`). Gotcha: invoking `run-harness.ps1`/`determinism-check.ps1`
with the NESTED `server-26.2.jar` fails (`NoClassDefFoundError:
joptsimple/ValueConverter` - the nested jar has no bundled libraries);
always pass the BUNDLER `server.jar`, which self-extracts libraries +
real server. The `-PvanillaJar` Gradle property is the opposite: it wants
the NESTED jar (real classes at top level).

## Harness validation: redstone-line corpus live (2026-09-10)

Built `harness/corpus/redstone-line/` (settled 41-wire lever line S,
repeater clock C, connection variants V, torch line T, climbing steps P).
No hasher change needed (POWER is a blockstate, already in the v1 block
hash). Verified live on bench PC:

- Corpus loads and ticks: `[kestrel-corpus] building redstone-line
  scenario -> ... ready`, 962 data lines, **zero -1 hash failures**.
- Only 2 unique hashes: expected (settled line S goes static after
  power-on; clock C toggles but slowly vs the 45s window; most ticks are
  steady-state). Power movement IS visible (2 states, not 1).
- `determinism-check.ps1 -SkipTicks 5`: **PASS (803 ticks compared)**.
  redstone-line is a trusted oracle for optimization #3 work.
- Experimental-evaluator comparison (default vs REDSTONE_EXPERIMENTS on
  this corpus) NOT yet run - open item for step 7 scoping.
