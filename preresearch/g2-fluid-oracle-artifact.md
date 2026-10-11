---
title: The oracle's fluids are not ground truth for a headless generator
date: 2026-09-18
status: measured
---

# The fluid gap is a harness artifact, not a Kestrel defect

## 0. The anomaly

Two vanilla worlds from the same seed in different chunk orders disagree on
~0.33% of cells. Across 131343 such differing cells in the D vs E run,
**water and lava appear zero times**. Not rare. Zero.

But Kestrel's headless pipeline against the saved vanilla oracle has
`water -> air` as its single most common first difference (113 chunks, 11.0%,
14614 cells) and `lava -> air` third (68 chunks, 6.6%)
(`harness/tools/parity/fullrun/batch-parity-notes.md`).

A block type vanilla never disagrees with itself about, topping Kestrel's
mismatch list, is either a real defect or a broken comparison.

## 1. Two hypotheses

- **H1, artifact.** The oracle came from a live vanilla server that ran game
  ticks, so its fluids *flowed* after placement. Kestrel's headless capture
  never ticks. We would be comparing a settled world against a fresh one.
- **H2, defect.** Kestrel genuinely fails to place spring and fluid features.

H1 predicts the oracle holds pending fluid ticks and a meaningful population
of non-source (flowing and falling) fluid. H2 predicts settled fluids: no
pending ticks, essentially all source blocks.

## 2. Measurement

`core/src/vanilla/java/dev/kestrel/worldgen/FluidTickCensus.java`, a pure
read over saved region files. No generation, no server.

| | oracle corpus | control runD |
|---|---|---|
| full chunks | 2809 | 400 |
| chunks with pending fluid ticks | 264 | 30 |
| total pending fluid ticks | 1847 | 70 |
| max pending fluid ticks in one chunk | 22 | 7 |
| total pending block ticks | 25577 | 6153 |
| water cells | 3542865 | 305194 |
| water non-source | 31539 (0.89%) | 8249 (2.70%) |
| lava cells | 115335 | 12720 |
| lava non-source | 10438 (9.05%) | 1207 (9.49%) |

Oracle water breakdown: `level=0` 3511326, `level=8` (falling) 14348,
`level=1` 7715, `level=2` 3449, `level=3` 1925, `level=4` 1366,
`level=5` 1085, `level=6` 895, `level=7` 756.

**H1 holds.** The oracle was saved with 1847 fluid updates still queued, and
carries 31539 water cells and 10438 lava cells that exist only because the
server ticked. Those cells are air in any untickled generation.

## 3. How much of the gap it explains

Honest arithmetic, because this does not close to 100%.

The census covers 2809 chunks; the parity comparison covered 1024. Scaling
the oracle's 11.2 non-source water cells per chunk gives roughly **11500**
flowing water cells inside the compared region, against **14614** observed
`water -> air` mismatches. So flowing water plausibly accounts for around
three quarters of that row, not all of it.

Caveats on that estimate, both real:

- The 1024 target chunks are not a uniform sample of the 2809; water density
  varies with biome and the target square may be wetter than average.
- `water[level=8]` alone is 14348, strikingly close to the 14614 mismatches,
  but that closeness is across different chunk counts and should be treated
  as coincidence until measured on the same footprint.

So: H1 is the dominant explanation and H2 is not dead. A residual fluid
defect may exist underneath. The point is that the current number cannot
measure it.

## 3b. Correcting the size claim (mine)

This was first described here as "the single biggest item" in the residual
gap. That was wrong, and the mistake is worth naming because it is easy to
repeat.

`water -> air` leads the *first difference by chunk frequency* table, which
scans each chunk upward from y=-64 and records the first mismatch. Aquifer
water sits low, so it wins that race in 11% of chunks. That statistic says
nothing about volume.

By volume it is small. 14614 cells out of 100663296 is **0.0145%**, against a
total Kestrel-vs-oracle gap of 0.5559% (559555 cells). Water is about **2.6%
of the gap**; adding lava brings fluids to roughly 3-4%. The bulk of the gap
by cell count is leaves, sculk and ore, which is the order-noise population
documented in `g2-vanilla-order-determinism.md`.

So this finding removes a confound and a whole error class. It does not move
the headline number much.

## 4. The consequence that matters

**A ticked, saved world is not a valid oracle for an untickled headless
generator, and no amount of Kestrel work fixes that.** The mismatch is
manufactured by the comparison.

Three ways out, in order of cost:

1. Report parity with and without fluid cells, so the artifact is visible
   instead of silently charged to Kestrel. Cheap, dishonest to skip.
2. Capture the oracle before any ticking. Hard: the server ticks while it
   generates.
3. Tick Kestrel's output to match. Expensive and reintroduces a second
   nondeterminism.

## 5. Why tier R sidesteps this entirely

The better answer is that the problem dissolves under the gate the project
already designed.

`FeaturesTrace` (`harness/agent/.../FeaturesTrace.java`) snapshots chunk
contents **at the generateFeatures event itself**, in memory, content
addressed under `chunks/<sha>.bin.gz`. Those snapshots are taken before the
world ever ticks. A tier R replay compares Kestrel against *those*, not
against a saved region file.

So tier R is immune to the fluid artifact by construction. That is now an
additional argument for building it, on top of cancelling the cross-order
noise: it replaces an oracle that is contaminated by two separate confounds
(chunk order and tick history) with one that has neither.

## 6. Artifacts

- Tool: `core/src/vanilla/java/dev/kestrel/worldgen/FluidTickCensus.java`
  (one arg, a region dir).
- Raw output: `/tmp/vanilla-order/fluid-oracle.txt`, `fluid-runD.txt`
  (throwaway, not committed).
- Related: `g2-vanilla-order-determinism.md`, `g2-build-order.md` D1/D7/Q11.
