---
title: Tier R, first real measurement
date: 2026-09-18
status: measured
---

# Tier R: 99.999219% under the replayed order

## 0. What changed

Every Kestrel worldgen parity number so far was measured against a saved
vanilla region capture. That oracle carries two confounds, both now measured:

- **Chunk order.** Vanilla does not reproduce its own worlds. Two runs of the
  same seed in different orders agree on 99.666% of cells and only 14.50% of
  chunks (`g2-vanilla-order-determinism.md`).
- **Tick history.** The oracle was saved with 1847 fluid updates still queued
  and ~42000 non-source fluid cells that exist only because the server ticked
  (`g2-fluid-oracle-artifact.md`).

Tier R (decision D1) removes both. It replays vanilla's recorded
`generateFeatures` order and compares against `FeaturesTrace` snapshots taken
in memory at the generation event, before the world ever ticks.

## 1. Preconditions, both confirmed

- **FEATURES is serial.** The `active` column of `events.tsv` is max 1 across
  two independent 228-event captures, including one spread over two worker
  threads. The recorded entry sequence is a total order, so replay is exact
  rather than approximate.
- **The trace validates.** `FeaturesTraceReplay` already checked sequence
  continuity, ENTER/COMPLETE pairing, non-overlap, write radius 1, and full
  3x3 dependency windows, and refuses the trace otherwise.

## 2. The scoping bug that had to be fixed first

The replay compared **every** chunk the trace ever observed. That is wrong.

Write radius is 1, so a chunk's final state is only determined by the trace if
every center that writes into it was recorded. A chunk at the edge of the
traced center set still received spill in vanilla from a center outside the
trace, and Kestrel was never asked to run that center. Comparing those chunks
charges Kestrel for work it was not given.

The effect is not cosmetic. On the FEATURES-order capture:

| | chunks | differing cells |
|---|---|---|
| all observed coverage | 196 | 77325 |
| fully traced interior | 56 | **43** |
| edge, not Kestrel's to answer for | 140 | 77282 |

**99.94% of the apparent divergence was the scoping bug.** Five boundary
chunks alone held 77142 of it.

This is the same fail-open shape as C1, the fabricated parity oracle and the
air oracle in `FeaturesCheck`: a comparison that silently substituted
something plausible for something absent.

## 3. Result

```
VALIDATED seed=3527749958874813416 centers=114 coverage=196 familyGuard=true
TIER_R scoredChunks=56 exact=54 (96.43%) cells=5505024 diffs=43 parity=99.999219%
TIER_R failing chunks: -7,8=9 -6,8=34
TIER_R VERDICT: FAIL, 43 cells across 2 chunks diverge under the replayed order
```

**54 of 56 fully traced chunks are bit identical.** The remaining 43 cells sit
in two adjacent chunks, `(-7,8)` and `(-6,8)`.

For scale, against the contaminated oracle the same pipeline scored 99.4441%.
Order-matched and pre-tick, it scores **99.999219%**. The residual that was
being attributed to Kestrel shrinks by roughly **270x**, from ~0.21% to
~0.0008%.

## 4. What this does and does not say

Does say: Kestrel's FEATURES implementation reproduces vanilla essentially
exactly when asked the same question vanilla was asked. The old gap was
dominated by the measurement, not the implementation.

Does not say: the remaining 43 cells are unimportant, or that 56 chunks is a
corpus. The step 7 exit criteria wants N >= 1000. This is 56. The next moves
are to record a wider trace and to chase those 43 cells, in that order or
either order.

## 5. Gate behaviour

`FeaturesTraceReplay` now **fails closed**. It throws on any scored
divergence. Pass `--report-only` to diagnose without failing. It also refuses
a trace with no fully covered interior chunk rather than reporting a vacuous
pass on zero cells.

It also no longer hardcodes the seed (read from the trace, all rows must
agree) or require the three chunks from the original kelp/seagrass
investigation, which are now an opt-in regression guard that engages only when
a trace covers them.

## 6. Artifacts

- Tool: `core/src/vanilla/java/dev/kestrel/worldgen/FeaturesTraceReplay.java`.
- Trace and output: temporary files (114 centers), not kept.
