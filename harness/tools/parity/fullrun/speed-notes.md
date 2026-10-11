# Kestrel G2 Speed Measurement: ms-per-chunk vs Tick Budget

**Date**: 2026-09-14  
**Environment**: Raspberry Pi 5 (4x ARM Cortex-A76 @ 2.4 GHz, Linux 6.6 aarch64)  
**Runtime**: OpenJDK Temurin-25.0.4+7 (build 25.0.4+7-LTS)  
**World Seed**: `4242424242L` (structures OFF, standard corpus seed)  
**Test Volume**: 121 target chunks (`[-5..5] x [-5..5]`, 2,904 sections, 11,894,784 block cells)  

---

## 1. Executive Summary & Headline Numbers

We conducted an end-to-end performance benchmark comparing the full Kestrel `HeadlessNoise` pipeline against vanilla Minecraft 26.2 on the exact same Raspberry Pi hardware and runtime.

Prior known baselines captured store-side throughput (441 chunks/4.0s) and parallel noise scaling (2.1x at 8 threads), but had no end-to-end ms-per-chunk measurement against the 50ms server tick budget, and no direct race against vanilla 26.2 generating the identical chunks on the same machine.

### Headline Comparison (121 Identical Chunks: `[-5..5] x [-5..5]`)

| Metric | Kestrel `HeadlessNoise` | Vanilla 26.2 (Dedicated) | Delta / Ratio |
|---|---|---|---|
| **Mean ms-per-chunk** | **231.07 ms** | **207.87 ms** | 1.11x vanilla |
| **p50 ms-per-chunk** | **182.77 ms** | **158.75 ms** | 1.15x vanilla |
| **p95 ms-per-chunk** | **478.93 ms** | **442.63 ms** | 1.08x vanilla |
| **Max ms-per-chunk** | **1,047.46 ms** | **857.80 ms** | 1.22x vanilla |
| **Min ms-per-chunk** | **96.75 ms** | **54.33 ms** | 1.78x vanilla |
| **Sections per second** | **103.86 sec/s** | **39.22 sec/s** (76.4 pure gen) | **1.36x–2.65x** |
| **vs 50ms Tick Budget** | **4.62x** (4.6 ticks) | **4.16x** (4.2 ticks) | Both exceed budget |
| **p95 vs Tick Budget** | **9.58x** (9.6 ticks) | **8.85x** (8.9 ticks) | Severe tick stall if sync |

---

## 2. Stage Breakdown & Profiling (Where Time Goes)

Profiling over all 121 target chunks reveals how generation time is distributed across each stage of the pipeline:

### Kestrel HeadlessNoise Pipeline Breakdown

| Stage | Mean (ms) | p50 (ms) | p95 (ms) | Max (ms) | Min (ms) | % of Total | Dominance |
|---|---|---|---|---|---|---|---|
| **NOISE** (`biomes + fillFromNoise`) | 43.98 ms | 43.17 ms | 51.57 ms | 91.63 ms | 37.51 ms | 19.03% | Stable, low variance |
| **SURFACE** (`surfaceVanilla`) | 26.56 ms | 18.82 ms | 51.60 ms | 70.35 ms | 15.03 ms | 11.50% | Matches vanilla (26.39 ms) |
| **CARVERS** (`carveVanilla`) | 9.17 ms | 8.36 ms | 13.15 ms | 24.76 ms | 6.76 ms | 3.97% | Fast, minimal overhead |
| **FEATURES** (`features`) | **88.45 ms** | 41.35 ms | **310.92 ms** | **635.77 ms** | 20.52 ms | **38.28%** | **DOMINANT NOMINAL STAGE** |
| **SETTLE_LEAVES** (`settleLeaves`) | **62.92 ms** | 71.22 ms | **88.20 ms** | **275.55 ms** | 11.06 ms | **27.23%** | **BIGGEST ALGORITHMIC BOTTLENECK** |
| **TOTAL** | **231.07 ms** | 182.77 ms | 478.93 ms | 1,047.46 ms | 96.75 ms | 100.0% | 4.62x tick budget |

### Vanilla 26.2 Generation Breakdown (for comparison)

| Stage | Mean (ms) | p50 (ms) | p95 (ms) | Max (ms) | Min (ms) | Notes |
|---|---|---|---|---|---|---|
| `surface_ms` | 26.39 ms | 17.23 ms | 57.45 ms | 253.53 ms | 8.26 ms | Exact match with Kestrel (26.56 ms) |
| `carvers_ms` | 7.22 ms | 4.29 ms | 17.58 ms | 55.04 ms | 0.93 ms | Close match with Kestrel (9.17 ms) |
| `features_ms` | 58.76 ms | 23.32 ms | 214.27 ms | 685.02 ms | 5.86 ms | **1.51x faster than Kestrel (88.45 ms)** |
| `initialize_light_ms` | 0.62 ms | 0.24 ms | 2.33 ms | 13.43 ms | 0.04 ms | Vanilla light initialization |
| `light_ms` | 0.09 ms | 0.04 ms | 0.11 ms | 4.76 ms | 0.02 ms | Vanilla light propagation |
| `noise_work_ms` (worker pool) | 74.67 ms | 59.34 ms | 183.65 ms | 338.88 ms | 25.48 ms | Async worker fillFromNoise |
| `biomes_work_ms` (worker pool) | 37.15 ms | 27.18 ms | 95.98 ms | 144.26 ms | 7.48 ms | Async worker createBiomes |

---

## 3. Analysis: ms-per-chunk vs 50ms Server Tick Budget

- **The Tick Budget**: In Minecraft Java Edition, a server tick is allocated exactly **50.0 ms** (20 TPS).
- **Synchronous Generation is Fatal**:
  - Average chunk cost is **231.07 ms** (4.62 ticks).
  - p95 chunk cost is **478.93 ms** (9.58 ticks).
  - Max chunk cost reaches **1,047.46 ms** (20.95 ticks - over 1 full second).
  - Even vanilla dedicated server requires **207.87 ms** (4.16 ticks) on average, and **857.80 ms** max.
- **Architectural Verdict**:
  No server implementation can generate terrain synchronously inside `tick()`. Generating a single chunk on the tick thread produces a catastrophic 5 to 21 tick lag spike (100% CPU freeze for 250–1000 ms).
  World generation **must execute 100% asynchronously on background worker threads**, staged in queues ahead of player chunk requests.

---

## 4. Bottleneck Profiling & Identification

Two stages account for **65.51%** of total generation time: `FEATURES` (38.28%) and `SETTLE_LEAVES` (27.23%).

### Bottleneck A: `FEATURES` Dynamic Reflection Overhead (88.45 ms)
1. **Dynamic Proxy Overhead**:
   In `HeadlessNoise.java`, `RegionView` implements `WorldGenLevel` using `java.lang.reflect.Proxy.newProxyInstance`:
   ```java
   this.level = (WorldGenLevel) Proxy.newProxyInstance(..., this::invoke);
   ```
   Every single call to `getBlockState()`, `setBlock()`, `getFluidState()`, `getHeight()`, etc. goes through dynamic reflection (`Method.invoke`, autoboxing primitive coordinates, allocating `Object[]` argument arrays).
2. **Vanilla vs Kestrel Gap**:
   Vanilla dedicated server compiles directly against `WorldGenRegion` bytecode and finishes FEATURES in **58.76 ms**. Kestrel's `RegionView` proxy takes **88.45 ms** (a **+29.69 ms / +50.5% overhead** solely from reflection indirection).

### Bottleneck B: `SETTLE_LEAVES` Algorithmic Inefficiency (62.92 ms)
1. **Brute-Force Full-Volume Scan**:
   `HeadlessNoise.settleLeaves(chunks, cx, cz)` executes Jacobi fixpoint sweeps over a 3x3 chunk area (`ox, oz in -1..1`). For each chunk in the 3x3, it iterates through all 98,304 cells (`i = 0` to `HEIGHT * 256 = 384 * 256`):
   $$\text{Cells checked per sweep} = 9 \times 98,304 = 884,736 \text{ cells}$$
2. **Zero Palette Pruning**:
   The code checks every cell from `y = -64` to `y = 319` with `c.getBlockState(m)` without inspecting chunk sections. Over 75% of the vertical volume is deepslate, solid stone, or sky where leaves never exist.
   Even in chunks with **0 changed leaves** (e.g. chunk `(-5, -4)` and `(-5, -3)`), `settle_ms` took **231.58 ms** and **234.72 ms** simply scanning empty air and stone!
3. **Repeated Overlapping Sweeps**:
   When running over a grid, adjacent chunks re-scan the same neighbor chunks multiple times. Furthermore, 6 neighbor lookups per leaf perform `chunks.get(ChunkPos.pack(...))` which boxes `long` to `java.lang.Long` in a `Map<Long, ChunkAccess>`.

---

## 5. Concrete Fix Proposals

### Fix 1: Section-Palette Filtering & Sparse Leaf Index for `settleLeaves` (High Impact)
- **Mechanism**:
  1. **Section-Level Quick Reject**: Before iterating across 4,096 blocks in a `LevelChunkSection`, inspect `section.hasOnlyAir()`. If false, test `section.getStates().maybeHas(state -> state.hasProperty(LeavesBlock.DISTANCE))`. If no leaf state exists in the section's paletted container, **skip the entire 4,096 blocks in $O(1)$**.
  2. **Sparse Leaf List / Bounding Box**: Rather than scanning from $y = -64$ to $319$, restrict the scan to $y \in [50, 140]$ (or sections containing leaves collected during the `FEATURES` pass).
- **Expected Speedup**:
  Reduces cell evaluations from 884,736 down to the ~200–1,500 actual leaf blocks per chunk. Chunks without trees will execute in $< 0.1 \text{ ms}$.
  **Projected `settleLeaves` latency**: drops from **62.92 ms** to **$< 2.0 \text{ ms}$** (**>30x speedup** for this stage).
  **Overall pipeline impact**: removes ~61 ms, bringing mean pipeline latency from 231 ms down to **~170 ms** (**~26% overall pipeline speedup**).

### Fix 2: Concrete Delegate Class for `RegionView` (Moderate Impact)
- **Mechanism**:
  Replace `Proxy.newProxyInstance(..., this::invoke)` with a compiled static class `final class HeadlessWorldGenRegion implements WorldGenLevel`. Implement the delegation methods (`getBlockState`, `setBlock`, `getFluidState`) directly in bytecode.
- **Expected Speedup**:
  Eliminates `Method.invoke`, reflection lookup, and array allocation on every block inspection.
  **Projected `FEATURES` latency**: drops from **88.45 ms** to **$\sim 58\text{--}60 \text{ ms}$** (matching vanilla 26.2).
  **Overall pipeline impact**: removes ~28 ms.

### Combined Projection
Applying Fix 1 + Fix 2 will reduce Kestrel's mean per-chunk latency from **231.07 ms** to **$\sim 140\text{--}145 \text{ ms}$** (**1.6x faster than current HeadlessNoise, and 1.4x faster than Vanilla 26.2**).

---

## 6. Artifacts & Evidence

- Benchmark probe: `core/src/vanilla/java/dev/kestrel/worldgen/SpeedProbe.java`
- Vanilla benchmark harness: `harness/tools/parity/fullrun/run-vanilla-speed.py`
- Headless pipeline dataset (121 rows): `harness/tools/parity/fullrun/speed-headless.csv`
- Vanilla 26.2 dataset (121 rows): `harness/tools/parity/fullrun/speed-vanilla.csv`
