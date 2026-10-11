# G2 Step-2 Memory Verdict (Q5): W1/W2 Real-Corpus Import Bench

**Date**: 2026-09-16, ~21:31-21:45 UTC (23:31-23:45 local bench PC time)
**Corpus**: real 49x49 vanilla corpus, seed `4242424242`, `nostruct`, imported from
`C:\Users\bench\kestrel\corpus\seed-4242424242-nostruct` into a fresh bench world.
**Build**: private staging (`C:\Users\bench\kestrel-s2-mem`), git sha `ca7568e`.
**Tool**: `harness/tools/mem-bench.ps1`, analyzed with `harness/tools/g2-mem-analyze.py`.
**Repeats**: 3 per variant, `-Xms128M -Xmx1G -XX:+UseSerialGC`, 120s stay + 10s settle,
4 RSS samples 5s apart, jcmd histogram/NMT/heap-info per run.

This closes out the memory-cost open question (Q5) from `preresearch/g2-memory-design.md`,
which two earlier attempts could not reach because of a network-reachability gap
between the build host and the bench PC, not a code problem. This run is the actual numbers.

## Raw results (from `analysis.txt`/`analysis.json`)

| Variant | ImportSquare | Chunks | Sections | Sections/chunk | Air-only | B (block storage) | vs 116.7 MiB line | RSS idle (avg) | RSS after load (avg) | Heap committed/used | NMT |
|---|---|---|---|---|---|---|---|---|---|---|---|
| W1 | `0,0,10` | 441 | 5887 | 11.35 | 52.7% | **115.64 MiB** | 0.991x | 581.5 MB | 685.0 MB | 672.4 / 280.1 MB | 848.2 MB |
| W2 | `0,0,15` | 961 | 10187 | 10.6 | 55.8% | **200.11 MiB** | 1.715x | 855.75 MB | 972.1 MB | 872.7 / 365.6 MB | 1055.9 MB |

Offline census cross-check (`offline-census-W1.txt` / `-W2.txt`, matches the live numbers,
`Section bytes ok=True` on all 6 repeats): W1 palette distribution
`1:5577(airOnly),2-16:4218,17-32:723,33-64:66`; W2 `1:12878,2-16:8652,17-32:1413,33-64:121`.

## Regression + design-doc decision rule

Two-point linear fit `B(chunks) = fixedMiB + perChunkMiB * chunks`:
- `perChunkMiB = 0.1624`, `fixedMiB = 44.0` (solves both W1 and W2 points exactly).
- Design doc's own gating formula (`g2-memory-design.md:207-208,261-262`) is
  `perChunk x 2205` (2205 = 5 concurrent players each with a disjoint view-distance-10
  square, worst case) compared against the 350 MiB whole-server budget:
  **`0.1624 x 2205 = 358.2 MiB`, already over the entire 350 MiB budget from block storage
  alone - before adding the 44 MiB fixed overhead (~402 MiB total projected).**

Design doc decision table match (`g2-memory-design.md:261-262`):
- Both variants are `<= 13 sections/chunk` (11.35, 10.6) - the "air-only skipped" bar is met.
- But `perChunk x 2205 > 350 MiB` - so the **second row fires**:
  > "Rule does not fire. Record it as a step-9 risk and schedule 2b before step 9 exit 3."

`g2-mem-analyze.py` prints this exact decision string, confirming it's implementing the
documented rule, not an ad hoc read.

## Verdict

**2b (paletted/compact section storage) is not optional - it's required before step 9.**
At today's storage format (air-only-section skip only, from `93008b6`), a single
view-distance-10 player is already at 99.1% of the *1/3-budget* line by itself (115.64 of
116.7 MiB), and the 5-concurrent-player worst case the design doc gates on projects to
~358-402 MiB - matching or exceeding the *entire* 350 MiB server budget from block storage
alone, leaving nothing for entities, chunk logic, or JVM/GC overhead.

**This corrects `93008b6`'s commit message** ("24.0 to 11.4 sections/chunk, B under budget"),
which had no committed evidence behind it. The sections/chunk number in that commit
was directionally right (11.35-10.6 here vs. its claimed 11.4), but "under budget" does not
hold at the design doc's own real-deployment-scale gate. Single-player, single-VD10-square
usage is fine (0.991x); the multi-player projection is not.

## What this means for the build order

Per the design doc's own text at the fired row, the correct next step is **not** to block
step 3 (headless worldgen registries) - that work is independent of section storage format.
It means: schedule the compact/paletted section-storage work (2b) to land **before step 9's
exit criteria are checked** (step 9 = live generation, the point where real concurrent-player
memory pressure actually matters). Steps 3-8 can proceed on the current dense-minus-air-only
format in the meantime.

## Artifacts in this directory

- `analysis.json` / `analysis.txt` - full analyzer output (all 6 repeats + regression).
- `campaign.json` - exact bench parameters (world path, import square, spawn, JVM flags, seed).
- `mem-bench.log` - runner log (boot times, RSS samples, offline census summaries).
- `offline-census-W1.txt` / `-W2.txt` - standalone census cross-check.
- `W1-heapinfo.txt` / `W2-heapinfo.txt` - one representative jcmd `GC.heap_info` sample each
  (repeat 1 of 3; all 3 repeats agreed within noise per `analysis.json`).
- `W1-heap-postload.txt` / `W2-heap-postload.txt`, `W1-run.json` / `W2-run.json` - matching
  per-repeat context for the heap-info samples above.
- Not included (too large / redundant for evidence purposes, left on bench PC only): full
  `histogram.txt` (~1.5 MB each x6), `gc.log`, `nmt.txt`, `blocks-hash.csv` per repeat -
  available at `C:\Users\bench\bench\g2mem-s2\run-20260916\` on bench PC if deeper class-histogram
  inspection is ever needed.
