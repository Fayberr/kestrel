# Step 7 scoping: Alternate Current as opt-in flag, default off

Status: SCOPING (no production code). Companion to the step 7 vs 9
comparison; this doc specifies the work, it does not do it.

## 1. Vanilla grounding

`RedStoneWireBlock.updatePowerStrength(Level, BlockPos, BlockState,
Orientation, boolean)` branches per call on the private static
`useExperimentalEvaluator(Level)` (verified live via `javap -p -c` on the
26.2 classes: dispatch at the updatePowerStrength body, static check, and
the private check method). Source lines per LineNumberTable:
`RedStoneWireBlock.java:255-260` (dispatch),
`DefaultRedstoneWireEvaluator.java:16-17` (ctor into the base class).
The default path (`DefaultRedstoneWireEvaluator`, 43 lines) is
recompute-one-wire plus recursive neighbor dispatch; the experimental path
(`ExperimentalRedstoneWireEvaluator`, 226 lines) is BFS over two queues
with a packed orientation+power map, single setBlock per changed wire, and
orientation-ordered neighbor updates (see
`preresearch/redstone-path-analysis.md:1-34`).

Default path stays the sole parity target. The experimental evaluator was
evaluated and rejected as a parity target (reference data only):
`preresearch/redstone-experiments-eval.md`. Carried hazards: P1
orientation seed is level-RNG-drawn (deterministic per seed only), P2
`IllegalStateException("Turning off wire...")` reachable via
overlay-vs-live race on clocks, P4 O(depth) `contains` scans, P5 mixed
time-domain reads, P6 fan-out not really reduced for mixed builds.
Already measured: same-seed two-world default-vs-experimental differential,
581 common ticks, power payloads IDENTICAL after stripping 3
build-transient wires, no throw either side.

## 2. What AC changes (external mod, SpaceWalkerRS)

Network build of connected wires, outside-power entries, outward
propagation, one final setBlock per wire, 24 block + 6 shape updates per
change instead of 42 + up to 22, deterministic power-flow ordering instead
of locational MC-11193 chaos. Design goals, vanilla-problem analysis, and
the up-to-20x MSPT claim: [Alternate Current repo](https://github.com/SpaceWalkerRS/alternate-current).

## 3. Flag seam design

- New `AcFlag` (or config-gated boolean): default OFF, checked once per
  propagation entry, never per wire (contrast P7: the vanilla experiment
  checks per call; hoist it).
- Default path untouched: existing `WireConnectionCache`,
  `WireUpdateFilter`, `WireSectionLayout` keep running exactly as proven.
- AC path is a separate evaluator class beside them, sharing only the
  flat-layout read/write helpers. No shared mutable evaluator state
  (Mica lesson: per-worker state, no ThreadLocal-gated behavior smeared
  across files; see `preresearch/mica-findings.md` lesson 3).
- `shouldSignal` reentrancy guard preserved exactly on both paths
  (parity constraint, `redstone-path-analysis.md`).
- Power changes commit only through real setBlock (same rule as
  `WireSectionLayout`).

## 4. Headless battery spec (new `AcProofCheck`)

Mirror the `RedstoneProofCheck` PASS pattern:

1. Network build: connected wires collected once, isolated wires excluded.
2. Outside-power entries: each wire samples non-wire sources at most
   once; nearby wires at most twice (AC algorithm contract).
3. One write per wire: settled value only, no intermediate writes.
4. Update counts: 24 block + 6 shape per change; same-network wire
   notices skipped.
5. Flow-order goldens: deterministic neighbor order from power-flow
   direction, seed-independent (contrast P1: assert same order across
   two level seeds).
6. Negative controls: naive recursive port must diverge on a grid corpus
   (same discipline as the E5 fused-walk control); a vacuous battery is
   a failure (same discipline as the hopper `checkCooldownHandoff`
   positive-coverage guard).

## 5. Live differential spec (bench PC)

- Corpus: existing `harness/corpus/redstone-line/` (settled 41-wire line
  S, repeater clock C, connection variants V, torch line T, climbing
  steps P), already a trusted oracle (869-tick PASS).
- Runs: flag OFF vs flag ON, same seed 4242424242, `-SkipTicks 5`, flat
  world + `forceload` on the corpus volume.
- Capture per-tick POWER traces (blockstate POWER per wire pos, every
  tick) under both flags; compare (a) final powers, (b) transient
  sequences, (c) setBlock counts, (d) notification counts, (e)
  throw-or-not on clock corpus C.
- Pre-check every run: grep `stdout.log` for `Failed to load function`;
  a corpus that failed to load looks healthy on an empty world and every
  PASS from it is artifact.
- Expected result: flag OFF bit-identical to current default behavior;
  flag ON diverges in order/counts with documented divergences, and the
  divergence list is the shipped documentation.

## 6. Cost/benefit recap

Small isolated subsystem, all proof infrastructure landed (port hosts,
proof pattern, corpus, PowerTrace tooling). Benefit 11-21x on active
redstone for owners who opt in; zero default-path risk when off. Proof
cost low: extends the existing headless + live differential pattern.
Do before step 9, which needs a server (networking/storage/tick loop)
that does not exist yet, a new packet oracle, and overlaps step 15.
