# Kestrel: Subsystem Benchmarks & Performance Grounding

Developer-facing record of Kestrel's measured microbenchmarks, baseline sweeps, and parity verification against vanilla Minecraft 26.2.

**Caveat up front, stated plainly:** Microbenchmarks measure isolated algorithmic routines, not whole-server tick time. Whole-tick numbers depend on call frequency and Amdahl's Law (A* pathfinding, level RNG draws, and physics still run). Real whole-server speedup will be measured under live player loads once the server skeleton is fully integrated.

---

## 1. Scope & Headline Numbers

Kestrel is an own-architecture 26.x-only Minecraft server built for 100% vanilla parity while using about 340 MiB RSS idle (337.4-343.2 MiB measured on the VPS on 2026-09-24; 410-470 MiB with a player connected, about 430 MiB p50 and 450 MiB max under `MALLOC_ARENA_MAX=2`) instead of ~1.5 GB, and running technical workloads several times faster. Chunk generation runs at Paper's speed with byte-exact Vanilla terrain (section "G2 Step 10" below). Those footprints were measured with `-Xmx256M`; the live play server now launches with `-Xmx2G` plus an OOM heap dump (2026-09-25), because `-Xmx256M` OOMed after about 900 generated chunks, since `LiveGenerator`'s chunk grid grows and is never evicted.

Rather than taking shortcuts that alter game behavior (skipping ticks, throttling AI, or desynchronizing update cascades), Kestrel achieves its gains by replacing memory layouts, pre-resolving static geometry, and filtering provable no-op dispatches.

* **Target Entity Performance:** 50% to 75% overall reduction in entity tick cost (2x to 4x faster) on a single thread under exact parity.
* **Isolated Subsystem Wins:** Targeted hot paths benchmark at 6x to 200x faster than vanilla 26.2 bytecode.
* **Whole-Server Target:** 3x to 5x throughput gain on technical and redstone-heavy worlds.

---

## 2. Test Environment & Methodology

All benchmarks were run against deobfuscated Minecraft 26.2 bytecode under controlled conditions.

### Test Systems
* **Primary Rig (bench PC):** AMD Ryzen 7 5700X3D (8C/16T, 64 GB DDR4 RAM), Windows 11 Pro.
* **Reference Rig (Pi 5):** Broadcom BCM2712 (4 cores Cortex-A76, 4 GB LPDDR4X RAM), Raspberry Pi OS. Used to confirm platform independence of all proofs.
* **JDK:** Eclipse Temurin OpenJDK 25.0.4.1+1 LTS (`-XX:+UseG1GC`, 64-bit).

### How It Was Measured

#### A. Isolated Microbenchmarks (`*Bench.java`)
Microbenchmarks link directly against extracted vanilla `server-26.2.jar` classes (`BlockState`, `VoxelShape`, `Shapes`, `Vec3`, `BlockPos`, etc.) via `-PvanillaJar`:
* **Warmup:** 2,000 iterations per routine to allow C2 JIT compilation to settle.
* **Measurement:** 20,000 iterations across multiple trial sizes, best-of-3 runs to filter OS noise.
* **Anti-DCE Sinks:** All computation outputs accumulate into volatile blackhole sinks so the JIT cannot optimize away loops.
* **Clock:** High-resolution timestamps via `System.nanoTime()`.

#### B. Full-Server Baseline Harness (`harness/`)
Full-tick baseline runs attach an ASM-based JavaAgent (`harness/agent/`) to an unmodded vanilla 26.2 server:
* **MSPT Proxy:** Exit-phase `nanos` deltas from `ticks.log` (TickLogger schema 6; enter lines carry a 0-hash sentinel), analyzed by `harness/tools/mspt-analyze.ps1`: exit-phase only, SkipTicks warmup discard, p99 GC-outlier trim, median/p95.
* **SkipTicks Discipline:** First 5 ticks of each run are discarded (`-SkipTicks 5`) to exclude world initialization and spawn-chunk lighting settling windows.
* **GC Outlier Trimming:** Per-tick deltas exceeding the raw 99th percentile are trimmed before computing reported medians and p95 figures.
* **Corpus Standardization:** 60-second fixed runs (`-RunSeconds 60`) on fixed world seed `4242424242` with view distance 3.
* **Known limitations (documented, not fixed):** bot stepping is packet-driven (bot cadence follows server responsiveness, not a wall clock), and load generation is co-located (bots run on the bench PC host itself, so bot CPU contends with the server; treat cpuLoad as comparative within one host, never absolute). Churn load rows predating the WI-1 dwell fix are SUPERSEDED (old bots disconnected instantly and measured an idle server) - see `summarize-results.py`.

---

## 3. Vanilla 26.2 Baseline Tick Costs (The Problem Space)

Isolated stress corpora run on the bench PC rig profiled vanilla 26.2's tick costs across critical subsystems:

| Subsystem | Stress Corpus | Paired Ticks | Median MSPT | p95 MSPT | Primary Vanilla Bottleneck |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Mob AI** | `mob-ai` | 109 | **4.305 ms** | **14.800 ms** | Unsorted POI scans, LOS allocations, repeated distance math |
| **Redstone** | `redstone-line` | 319 | **2.837 ms** | **7.378 ms** | Recursive neighbor updates, connection re-resolutions |
| **Block Entities** | `block-entity-census` | 892 | **0.509 ms** | **1.362 ms** | 9-step preamble per ticker per tick on 87.5% idle blocks |
| **Spawn Calculations** | `spawn-census` (spawn OFF) | 383 | **0.648 ms** | **1.414 ms** | `createState` full entity scan runs even with mobs disabled |
| **Spawn Calculations** | `spawn-census` (+1 AFK bot)| 1400 | **1.772 ms** | **3.165 ms** | Attempt loop execution + player proximity scanning (+1.1 ms delta) |
| **Collision** | `collision-stress` | 750 | **0.261 ms** | **0.838 ms** | VoxelShape boolean algebra on non-cube blocks |
| **Hoppers** | `hopper-chain` | 812 | **0.237 ms** | **0.573 ms** | Full 27-slot scans on blocked/full containers |

---

## 4. Subsystem Benchmark Results

A recurring pattern in grounding these optimizations against decompiled source: several initial assumptions were found to be already solved in vanilla 26.2 (entity-vs-entity push is already O(1) and section-indexed; line-of-sight raycasts are already cached per tick in `Sensing`). The optimizations below target the real remaining bottlenecks.

### 4.1. Entity Movement & Collision (`CollisionBench.java`)
Vanilla's `BlockCollisions` already fast-paths full-cube blocks (`Shapes.block()`). However, every non-full-cube block (stairs, slabs, fences, walls, hoppers) falls through to `Shapes.joinIsNotEmpty()`, running full 3D voxel grid boolean algebra.

* **Audit Result:** Reflection audit of all 32,366 vanilla blockstates confirmed that **99.8% decompose into 5 or fewer collision boxes**.
* **Kestrel Implementation:** `ShapeBoxCache` pre-caches static AABB lists and performs direct bounding-box intersection tests, falling back to vanilla VoxelShapes only for the 66 states that exceed 5 boxes.
* **Microbenchmark (400 non-cube states x 5 probe boxes x 500 iters):**
  * Vanilla `Shapes.joinIsNotEmpty`: **267.6 ms**
  * Kestrel `ShapeBoxCache`: **34.8 ms**
  * **Speedup:** **7.68x faster (87% reduction in collision test time)**.
* **Parity Verification:** `ShapeBoxCacheCheck` tested 226,562 blockstate-vs-entity probe pairs against vanilla with **0 mismatches**.

### 4.2. Mob AI, Brain, & Sensory Primitives (`SensorScanBench.java`)

#### E1: Candidate Distance Sorting (`sort`)
* **Problem:** Vanilla's `NearestLivingEntitySensor` passes `Comparator.comparingDouble(origin::distanceToSqr)` to `List.sort()`, recomputing 3D distance $O(n \log n)$ times.
* **Kestrel Implementation:** `ScanOrder` implements a decorate-sort-undecorate stable merge sort computing distance exactly $n$ times.
* **Results:**
  * $n = 4$: **1.06x**
  * $n = 16$: **2.62x**
  * $n = 64$: **2.73x**
  * $n = 256$: **2.25x**

#### E2: POI Nearest-5 Selection (`poitopk`)
* **Problem:** When acquiring beds, meeting points, or job sites, vanilla queries nearby POIs (radius 48), fully sorts the entire match list by distance, and calls `.limit(5)`.
* **Kestrel Implementation:** `PoiTopK` maintains a stable min-heap of size 5 over precomputed distance keys, avoiding sorting the entire candidate list.
* **Results:**
  * $n = 4$: **2.37x**
  * $n = 16$: **6.57x**
  * $n = 64$: **14.81x**
  * $n = 256$: **22.45x to 25.3x**

#### E3: Secondary POI Scanning (`secpoi` / `secpoi0`)
* **Problem:** Every 40 ticks, `SecondaryPoiSensor` scans a 405-block volume (9x5x9) around each villager, querying `level.getBlockState()` for job sites.
* **Kestrel Implementation:** Kestrel hoists the profession check. 14 out of 15 registered professions (including Unemployed and Nitwit) have an empty secondary POI set; their scans are skipped entirely. For Farmers (who check farmland), `SecondaryPoiScan` replaces 405 `BlockPos` allocations with a mutable cursor.
* **Results:**
  * `secpoi0` (14 non-farmer professions): **85x to 200x faster (99% reduction)**.
  * `secpoi` (Farmer profession): **1.40x faster** (allocation elimination).

#### E4: Player Sensory Projection (`project`)
* **Problem:** `PlayerSensor` filters players through three separate stream pipelines, re-running distance and line-of-sight checks.
* **Kestrel Implementation:** `PlayerScan.project()` evaluates targetability in a single pass and projects all three lists.
* **Results:**
  * $n = 4$: **4.01x**
  * $n = 16$: **2.02x**
  * $n = 64$: **1.38x**

#### E5: Brain Behavior Scheduling (`brainwalk`)
* **Problem:** Vanilla iterates nested priority maps, activity maps, and behavior sets twice per entity per tick, allocating fresh `ObjectArrayList` snapshots on each tick.
* **Kestrel Implementation:** `BehaviorSchedule` flattens the hierarchy into immutable arrays at initialization and fills a reusable buffer.
* **Results:** **1.4x to 9.7x faster** (averaging ~3.5x across sizes $n = 4 \dots 256$).

#### Idle Sensory Memory Allocation (`idle`)
* **Problem:** Vanilla allocates an `Object2BooleanOpenHashMap` for every mob scan, even if no behavior queries line-of-sight during that scan.
* **Kestrel Implementation:** `LosVerdictCache` uses generation-stamped reusable tables.
* **Results:** **1.3x to 694x faster** (eliminates per-scan map allocation).

### 4.3. Hoppers (`HopperBench.java`)
* **Idle Push Gate (`hopidle`):** Facing a full chest, vanilla scans all 27 slots and runs 5 failed move attempts. Kestrel's `HopperState` validates fullness once and suppresses subsequent wake checks via a dirty flag: **4.5x to 15.2x faster**.
* **Full Suck Gate (`hopfull`):** Full hopper inventory scan suppressed: **5.5x to 6.7x faster**.
* **Transfer Math (`hopadd`):** Measured at **~1.0x** (exact line-for-line port preserving the 8-tick / 7-tick down-chain cooldown rule). *Note on benchmark honesty:* Earlier bench runs measured 0.53x to 0.90x, which exposed a dropped `wasEmpty` parameter in the port; once corrected to match vanilla's gate structure, transfer speed measured 1.0x as expected.

### 4.4. Block-Entity Tick Sleeping
* **Census Findings:** In a flat world census across 1,580 ticks, **87.5% of ticking block entities were provably idle** (100% idle for signs, shulker boxes, campfires, crafters, and unlit furnaces).
* **Kestrel Implementation:** `SleeperRegistry` monitors 25 wake signals across 10 block-entity types, skipping vanilla's 9-step preamble per tick while maintaining exact wake latency.

### 4.5. Redstone Dust
* **Default Path:** `WireUpdateFilter` eliminates redundant neighbor dispatches using 4 provably sound rules (R1 ceiling, R2 monotonicity, R3 connection gating, R4 self-dispatch). `WireConnectionCache` avoids re-resolving 4-way connections on signal reads.
* **Parity Result:** Over a 75-second live baseline run (`power-trace-vanilla-26.2-2026-09-12.log`), Kestrel's tick loop power trace showed **zero mismatches across all comparisons**.
* **Alternate Current Flag (Opt-in):** Ported into `AcWireNetwork`. Provides **11x to 21x** speedup on active redstone for server owners who opt into deterministic BFS power propagation.
* **G12 engine (2026-09-25):** the wire work above now sits inside a full redstone transcription, exact against every committed oracle fixture (see the G12 section at the end of this document): wire/dust, torches including burnout, repeaters, comparators, observers, levers, buttons, lamps, pistons and sticky pistons, the block-event queue and moving pistons.

---

## 5. Translating Microbenchmarks to Real Server Environments

A common trap in server optimization is assuming an isolated 10x loop speedup yields a 10x faster server tick.

### Amdahl's Law in Minecraft Entities
A full entity tick consists of several distinct phases:
1. **Collision Detection & Axis Stepping:** Accelerated 6x to 8x by Kestrel.
2. **Sensory Scanning & POI Lookups:** Accelerated 2x to 25x by Kestrel.
3. **Behavior Traversal & Allocation:** Accelerated ~3.5x by Kestrel.
4. **A* Pathfinding & Navigation:** Runs unmodified to preserve exact path selection.
5. **Level RNG Sequencing:** Preserved verbatim to avoid desynchronizing world randomness.
6. **Physics & State Synchronization:** Runs unmodified.

Because phases 4, 5, and 6 remain at 1x, overall single-threaded entity processing realistically accelerates by **2x to 4x (a 50% to 75% reduction in entity tick cost)**.

### Where These Gains Matter Most
* **Technical SMPs & Villager Halls:** High concentrations of non-cube blocks (hoppers, stairs, slabs) and villager trading halls heavily stress collision and POI routines. Kestrel's optimizations directly target these specific bottlenecks.
* **Farms with Hoppers & Redstone:** Redstone no-op suppression and hopper dirty-flag gating prevent idle farm arrays from consuming tick budgets.

---

## 6. Parity Verification Architecture

Kestrel enforces a strict rule: **no optimization lands without automated differential proof against vanilla bytecode.**

1. **Deterministic State Hasher (`WorldStateHasher`):** Computes a per-tick 64-bit FNV-1a hash over all loaded chunk blockstates, block-entity inventories, and entity coordinates/motion. Any divergence between vanilla and Kestrel immediately fails the test runner.
2. **Intermediate Power Traces:** Redstone testing verifies not just final power levels, but every intermediate transient update order.
3. **Negative Controls:** All unit proof checks (`*Check.java`) include deliberate negative controls (e.g., intentionally flipping tie-breakers or altering dispatch orders) to verify that tests fail when parity is broken.

---

## 7. Whole-Server Comparison (2026-09-13, bench PC Ryzen 7 5700X3D)

Full matrix: 7 scenarios x vanilla/paper/kestrel x default/tuned = 42 runs,
03:13-04:07, 2 bots per scenario (`--bots 2 --stay 20`), all joins 2/2,
all churns cycles=8, zero INVALID rows. Tuned flags:
`-Xms128M -Xmx1G -XX:+UseSerialGC`. Replaces the 2026-09-12 crowd-only
table (whose Vanilla-tuned 0% load cell was already flagged suspect there;
it now reads a credible 0.4%).

RSS in MB; cpu = % of 16 threads; join = mean join latency.

**Boot (s)**

| Scenario | Vanilla def | Vanilla tuned | Paper def | Paper tuned | Kestrel def | Kestrel tuned |
|---|---|---|---|---|---|---|
| idle | 13.3 | 13.3 | 18.3 | 15.3 | 10.1 | 9.1 |
| single | 14.3 | 14.2 | 14.3 | 12.2 | 9.1 | 10.1 |
| crowd | 14.3 | 13.2 | 15.3 | 12.2 | 9.1 | 10.1 |
| stress | 14.3 | 13.2 | 14.3 | 11.2 | 9.1 | 10.1 |
| joinstorm | 14.2 | 13.2 | 13.3 | 12.2 | 10.1 | 10.1 |
| megacrowd | 13.2 | 13.2 | 15.3 | 11.1 | 9.1 | 10.1 |
| churn | 12.2 | 13.2 | 15.2 | 11.2 | 10.1 | 9.1 |

**RSS idle / loaded (MB)**

| Scenario | Vanilla def | Vanilla tuned | Paper def | Paper tuned | Kestrel def | Kestrel tuned |
|---|---|---|---|---|---|---|
| idle | 636 / 790 | 386 / 422 | 818 / 864 | 702 / 746 | 418 / 476 | 272 / 278 |
| single | 648 / 800 | 386 / 420 | 821 / 847 | 703 / 742 | 419 / 474 | 271 / 281 |
| crowd | 522 / 648 | 390 / 420 | 814 / 876 | 704 / 736 | 540 / 689 | 272 / 284 |
| stress | 558 / 649 | 386 / 417 | 820 / 916 | 712 / 752 | 418 / 421 | 271 / 280 |
| joinstorm | 578 / 610 | 386 / 419 | 825 / 851 | 708 / 740 | 420 / 480 | 272 / 281 |
| megacrowd | 540 / 624 | 384 / 420 | 674 / 738 | 704 / 752 | 418 / 425 | 272 / 282 |
| churn | 536 / 688 | 384 / 598 | 816 / 868 | 700 / 772 | 410 / 470 | 270 / 298 |

**CPU load (% of 16 threads)**

| Scenario | Vanilla def | Vanilla tuned | Paper def | Paper tuned | Kestrel def | Kestrel tuned |
|---|---|---|---|---|---|---|
| idle | 0.3 | 0.4 | 1.1 | 0.6 | 0 | 0 |
| single | 0.2 | 0.1 | 0.8 | 0.8 | 0.1 | 0 |
| crowd | 0.2 | 0.4 | 2 | 0.6 | 1 | 1 |
| stress | 0.3 | 0.4 | 5.7 | 0.7 | 1.5 | 1.1 |
| joinstorm | 0.3 | 0.3 | 0.8 | 0.6 | 0 | 0 |
| megacrowd | 0.4 | 0.3 | 1 | 0.8 | 1.1 | 1 |
| churn | 4.5 | 4.4 | 1.9 | 1.9 | 2.3 | 2.1 |

**Mean join latency (s)**

| Scenario | Vanilla def | Vanilla tuned | Paper def | Paper tuned | Kestrel def | Kestrel tuned |
|---|---|---|---|---|---|---|
| idle | 0.6 | 0.4 | 1.1 | 0.9 | 0.8 | 0.7 |
| single | 0.5 | 0.4 | 1.2 | 0.8 | 0.8 | 0.7 |
| crowd | 0.5 | 0.6 | 1.2 | 1.2 | 0.8 | 0.7 |
| stress | 0.4 | 0.6 | 1 | 0.8 | 0.7 | 0.8 |
| joinstorm | 0.6 | 0.4 | 1.1 | 0.8 | 0.8 | 0.7 |
| megacrowd | 0.5 | 0.8 | 1 | 0.8 | 0.7 | 0.7 |
| churn | 0.3 | 0.2 | 0.4 | 0.3 | 0.6 | 0.6 |

Consistent ranking in every scenario: Kestrel-tuned boots in ~9-10s at
~270 MB idle (~280 MB loaded), vs ~13s/~385 MB vanilla-tuned and ~11-15s/
~700 MB paper-tuned. CPU-load gaps at 2 bots are thin (0.1-0.5 pp) and
should be read as directional only; the load scenarios were designed for
5-20 bots (single 1, crowd/stress 5, joinstorm 10, megacrowd 20).

Caveats, stated plainly: vanilla/paper ran real flat terrain with lighting,
spawn logic, and mobs; Kestrel served a dead 17x17 platform with fake
full-bright light. Their numbers include real content, ours does not yet, so
this table tracks hosting cost (RAM) and startup, not game performance (MSPT).
Kestrel-def load RSS (~470 MB, G1 heap headroom) vs tuned (~280 MB, SerialGC):
ship tuned flags as defaults.

Superseded in part (2026-09-25): the live play server does not ship the
SerialGC tuned flags recommended in the line above. It launches with G1 plus
the concurrent periodic-GC flags (`-XX:+UseG1GC`,
`-XX:G1PeriodicGCInterval=10000`, `-XX:G1PeriodicGCSystemLoadThreshold=0`,
`-XX:+G1PeriodicGCInvokesConcurrent`) and `-Xmx2G` with an OOM heap dump, the
same JVM family the Step 9 and Step 10 live routes used (see
`harness/tools/g2-final-kestrel-live.sh` and `docs/RUNNING.md`). The forced
periodic Full-GC tuning was rejected in the Step 9 gate above.

---

## 8. How to Run These Benchmarks

Developers can reproduce these benchmarks locally from the repository root:

```bash
# Whole-server suite (needs the bench PC bench installs; see harness/bench.yaml):
#   dry run (generate only) / detached full matrix / poll results
python3 harness/tools/run-bench.py --config harness/bench.yaml
python3 harness/tools/run-bench.py --config harness/bench.yaml --launch
python3 harness/tools/run-bench.py --poll
# Narrow it: --only crowd --servers kestrel --flags tuned --bots 10
#   --repeats 3 --stay 120 --mode quality
```

```bash
# Run the default reproduction check suite (9 checks; Stage A gates C1, H1, H3,
# H4, H9 plus LiveGenerator, G11's fluid gate, S34's status ping and S32's
# server.properties: TerrainOverwriteCheck, EventReentrancyCheck,
# DuplicateScheduleCheck, ScheduledTickRevisionCheck, ReloadCompletionCheck,
# LiveGeneratorCheck, FluidParityCheck, StatusPingCheck, ServerPropertiesCheck)
./core/gradlew -p core checkRepro \
  -PvanillaJar=$HOME/kestrel-run/vinner/META-INF/versions/26.2/server-26.2.jar \
  -PvanillaLibs=$HOME/kestrel-run/vinner

# Run specific reproduction checks or parity gates via checkRepro (-Pcheck=<name>).
# Any short name in core/build.gradle's checkClassMap (56 registered checks) works.
./core/gradlew -p core checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=TerrainOverwriteCheck
./core/gradlew -p core checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=BatchParityGate
./core/gradlew -p core checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=ReloadCompletionCheck

# Run specific microbenchmarks or component proofs via checkRepro. These three
# are NOT registered in checkClassMap, so they need the fully qualified class
# name (a dotted name is passed through unchanged; an unregistered short name is
# not resolved and the run fails with ClassNotFound):
./core/gradlew -p core checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=dev.kestrel.ai.SensorScanBench
./core/gradlew -p core checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=dev.kestrel.collision.CollisionBench
./core/gradlew -p core checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=dev.kestrel.hopper.HopperBench

# Run the standalone core check suite (PowerShell / Linux compatible)
./harness/tools/run-core-check.ps1 -All
```

The manual-dispatch CI workflow (`.github/workflows/gates.yml`, 30 minute cap)
runs a different fast set of **10** checks by default, not `checkRepro`'s
`defaultReproChecks` list of 9: `TickLoopCheck`, `FluidParityCheck`,
`EventReentrancyCheck`, `DuplicateScheduleCheck`, `ScheduledTickRevisionCheck`,
`RedstoneProofCheck`, `AcWireProofCheck`, `RedstoneParityCheck`,
`StatusPingCheck` and `ServerPropertiesCheck`. The workflow's
`checks` input overrides the list and `scripts/ci-validate-checks.sh` rejects any
name that is not in `checkClassMap` before Gradle starts. Measured on run
36164148445 (2026-09-25): about 49 seconds for the check step alone and under
two minutes for the job end to end with warm caches.

## G2 Step 9 final live/system gate (2026-09-21)

Pinned source: `f320e560192ac985a360453f1243c5ee3574b3f0`.
Environment: Windows 11 Pro, Ryzen 7 5700X3D, 64 GiB RAM, Java 25.0.4.1+1.
Seed 4242424242; player route `(0,0) -> (4,0) -> (4,4)`, view distance 2,
65 scored chunks. Two fresh Kestrel async boots were compared.

- Live/network repeat: 65/65 scored chunks present, 0 block diffs,
  0 biome-wire diffs, 0 light-wire diffs, 65/65 light-scored, 0 parse failures.
- Connected tick-work samples: n=1519, p50 0.0647 ms, p95 4.1233 ms,
  max 103.4178 ms. Async queue p95/max = 4/4 with cap 4.
- Async production benchmark: 121 chunks in 12.634 s = 9.577 chunks/s.
  Request->publish latency p50 386.398 ms, p95 635.595 ms, max 1325.775 ms.
  Worker generation p50 74.379 ms, p95 232.975 ms. Publication p50 1.883 ms,
  p95 2.362 ms. Simulated tick-thread work p50 0.004 ms, p95 0.011 ms.
- Connected RSS: 88 samples, min 338.082 MiB, p50 396.742 MiB,
  max 428.586 MiB.
- GC: 418 pause events, p50 2.181 ms, p95 7.866 ms, max 99.612 ms.
  One Full GC was observed, but zero periodic Full-GC events occurred with the
  production concurrent-periodic G1 configuration. The prior forced periodic
  Full-GC memory tuning is rejected.
- Exact Vanilla semantics remain gated independently by strict matched-order
  structures-on Tier-R; the canonical large proof is 1418/1418 exact,
  139,395,072 cells and 0 diffs.

The live-route test intentionally uses Kestrel fresh-boot repeatability rather
than one uninstrumented Vanilla route as an exact final-world oracle, because
identical Vanilla 26.2 routes were previously shown to be order-sensitive.
No tolerance or captured Vanilla answer is injected into Kestrel.

## G2 Step 10 chunk generation speed (2026-09-24)

Step 10 is merged to main (the frozen Step-10 classes `22ddbe5` are an ancestor
of the current main). The numbers below are the close-out measurements.

Oracle VPS (4 Ampere cores), Java 25.0.4.1+1, seed 4242424242, 32x32 chunks
around block 1000,1000, fresh world each run. Kestrel brings the square to
LIGHT (`GenSpeedBench`), Paper to FULL (`harness/paper-genbench`). Both need
FEATURES on the square plus one ring.

| server | mode | chunks/s |
|---|---|---|
| Kestrel before Step 10 | direct, inline | 6.55 |
| Kestrel Step 10 | direct, inline | 11.47 |
| Kestrel Step 10 | direct, 3 pool threads | 28.45 |
| Kestrel Step 10 | production pipeline, 3 pool threads, queue cap 4 | 21.48 |
| + queue cap 8 (`7539cb0`) | production pipeline, 3 pool threads | 27.84, 27.81 |
| + STARTS thread, margin biome reuse | direct, 3 pool threads, cold JVM | 32.16, 29.64 |
| same | production pipeline, 3 pool threads, cold JVM | 29.70, 27.79 |
| same | direct, 3 pool threads, warm JVM | 37.77, 37.89 |
| same | production pipeline, 3 pool threads, warm JVM | 35.47, 34.65 |
| Paper 26.2 default config (1 chunk worker here) | 64 requests in flight | 14.71, 13.39 |
| Paper 26.2, `worker-threads: 3` | 64 requests in flight, warm JVM | 36.45, 33.36 |

"Warm JVM" means the timed square runs after a 24x24 square around chunk
0,0 in a separate generator (`-Dgenspeed.warmup=24`). Paper's timed run always
starts warm, after it has prepared its spawn area. Warm, Kestrel's production
pipeline matches Paper with 3 workers (mean 35.06 against 34.91 chunks/s), and
direct generation beats it by about 8%. Cold, JIT compilation alone costs
Kestrel about 22 CPU seconds on this 4-core machine.

Every Step 10 change passes both Tier-R gates on the w00 and w01 traces
(strict 4324/4324, live 4324 and 4289), and `ParallelCarversCheck` output is
identical to the inline run. Kestrel inline uses about 88 CPU seconds for the
1024 chunks, against 104 to 111 for Paper. With 3 pool threads the machine is
CPU-bound, so the remaining gains come from doing less work per chunk, not
from more parallelism.

### Interleaved rerun against Paper (2026-09-24, evening)

Same VPS and seed, same frozen Step-10 classes (`22ddbe5`), Paper with
`worker-threads: 3`. Each round ran Paper and Kestrel back to back on the
same area, three rounds in total, so both servers saw the same background
load. Paper always runs warm (after its spawn area is prepared); Kestrel
"warm" uses `-Dgenspeed.warmup=24`. Values are chunks/s for 1,024 chunks.

| area (chunk centre) | server | runs | mean |
|---|---|---|---|
| 1000,1000 | Paper, 3 workers | 32.98, 35.44, 32.21 | 33.54 |
| 1000,1000 | Kestrel production pipeline, warm | 33.03, 34.30, 34.88 | 34.07 |
| 1000,1000 | Kestrel direct, warm | 38.02, 37.20, 36.31 | 37.18 |
| 1000,1000 | Kestrel production pipeline, cold | 28.66, 28.46, 28.30, 28.22 | 28.41 |
| -2500,1800 | Paper, 3 workers | 39.70, 37.24, 33.58, 39.09, 35.94 | 37.11 |
| -2500,1800 | Kestrel production pipeline, warm | 39.98, 39.07, 39.91 | 39.65 |

One cold Kestrel run (22.27) overlapped another agent's CPU-heavy job and is
left out; it was repeated twice. The Paper value 33.58 came from the same
window and is kept, which only makes Paper look slower. With both JVMs warm,
Kestrel's production pipeline is 1.6% faster than Paper at 1000,1000 and 6.8%
faster at -2500,1800. The spread between runs of one server (up to 6
chunks/s for Paper) is larger than the gap between the two servers, so the
honest reading is "the same speed". A cold Kestrel JVM is about 15% slower
because the first chunks run in the interpreter while the JIT compiles; a
real server is past that point once its spawn area has generated.

### Live route with the Step-10 build (2026-09-24, VPS)

`harness/tools/g2-final-kestrel-live.sh` (65-chunk route at view distance 2,
two fresh boots, `-Xmx256M`, concurrent periodic G1, `--async-chunkgen`).
All four runs: 0 block, 0 biome-wire and 0 light-wire diffs.

| build | RSS p50 / max (MiB) | chunks/s | request to publish p50 / p95 (ms) | tick p95 / max (ms) |
|---|---|---|---|---|
| Step 9 (`93d46f0`) | 428.1 / 437.0 | 5.57 | 602 / 924 | 4.97 / 112 |
| Step 9, `MALLOC_ARENA_MAX=2` | 411.7 / 417.5 | 6.12 | 506 / 818 | 5.22 / 109 |
| Step 10 (`22ddbe5`) | 469.0 / 476.7 | 8.07 | 702 / 1405 | 5.78 / 183 |
| Step 10, `MALLOC_ARENA_MAX=2` | 430.3 / 450.2 | 8.16 | 698 / 1367 | 5.78 / 154 |

Connected tick work p50 is 0.08 ms on every build. The async queue peaks at
4 (its cap) on Step 9 and 8 (its cap) on Step 10. Step 10 generates the route about 45% faster. Its request-to-publish latency
is higher because the queue cap is 8 instead of 4, so a request can wait
behind more queued chunks. The single worst tick is also longer (154-183 ms
against 109-112 ms): on this 4-core machine the generation pool and the tick
thread compete for CPU while the route is generating. p95 tick work rises
only from about 5.0 to 5.8 ms. The extra RSS is almost all glibc malloc arenas
for the pool threads (NMT committed: 382 MiB on Step 9, 377 MiB on Step
10); `MALLOC_ARENA_MAX=2` removes most of it and is now part of the
`docs/RUNNING.md` launch. Idle RSS after boot and spawn generation, same
flags, last 120 of 300 s: 337.4-343.2 MiB p50 for both builds, with or
without the arena limit.

## G11 fluid parity gate (2026-09-25)

`FluidParityCheck` replays every `FluidScenarios` scenario on a fresh
`TickLoop` and compares the recording line for line against the committed
vanilla oracle fixtures in `harness/corpus/fluid-flow/oracle/` (25 files).
All 25 are exact: flat water and lava, Nether fast lava, slope finding into
holes, vertical fall and impact spread, infinite sources (including the
floating failure and the diagonal case), water meeting lava source and
flowing lava, lava dropped onto a water source, a second lava source raising a
flow (the fixture's random 4x spread delays), the basalt conversion, recede,
wall cut, waterlogging and waterlogged-slab wake, torch washing, lava burning
a torch, dig next to water, campfire dousing and the washed comparator. Merged
2026-09-25 (`15ac05f` water and lava; `aaf7d1f` added water dousing lit
campfires and candles and the comparator front fan-out).

Known gaps, from the `FluidFlow` javadoc: drops
(`WaterFluid.beforeDestroyingBlock`) and fizz level events (1501) are no-ops,
because there are no item entities and no level-event channel yet; bubble
columns are not modeled; the level random that lava's spread delay draws from
is a seeded per-loop source and is not persisted; only fluid-relevant update
targets are modeled (non-fluid neighbor shape changes are not). Worldgen
spring ticks are a separate gap: fluid ticks scheduled during world
generation are still not ingested at handoff, so natural springs do not flow
until a neighbor update disturbs them (tracked in `docs/SKIPPED-FEATURES.md`
3.7).

## G12 redstone parity gate (2026-09-25)

`RedstoneParityCheck` replays every `RedstoneScenarios` scenario on a fresh
`TickLoop`, from the `S` snapshot each v2 fixture carries, and compares the
recording line for line against the 29 committed vanilla oracle fixtures in
`harness/corpus/redstone/oracle/`. All 29 run and all 29 are required, and all
29 are exact. Covered: wire/dust (falloff, branching T, stair climb and
occlusion, update-order ties), torches including burnout and the 160-tick
restart, repeaters (delays, locking, pulse stretch, priority drain),
comparators (compare, subtract, container, through-block), observers, levers,
buttons (stone and wood durations), redstone lamps, and the pistons (block
drop, push limit, quasi-connectivity, slime/honey split, settle power cut, the
block-event queue drain order and moving pistons). One value cannot be
reproduced and is seeded from the fixture, not compared: `subTickOrder` on the
`q` lines (vanilla's `Level.subTickCount` lives as long as the level, so a
fresh per-scenario loop cannot reconstruct it); every difference between
counter values is still a strict comparison.

Merged 2026-09-25: `024ed43` (slice 1, wire, neighbor updater, signals,
diodes, 14/27 fixtures at the time), `3f0f7a3` (slices 2 to 4: comparators,
torch burnout, observers, lamps, 22/27), `db4aff8` (slice 5: pistons, the
block-event queue, moving pistons, 27/27), `8091127` (the two coverage-gap
fixtures: block-event drain order and the `handlingTick` lifetime, 29/29).

Not built (G12 section 1.3 secondary components): target block, daylight
detector, tripwire, note block, doors, trapdoors, fence gates, dispenser,
dropper, TNT and rails. Those are separate items, not regressions here.

## G10 Nether and End light replay (2026-09-25)

Branch `g10-nether-end-terrain` (5 commits not in main, 39 commits behind it)
replays the captured vanilla light executor stream in every dimension. 324
scored chunks per dimension: Nether light 0 diffs and End light 0 diffs (the
Nether replay also scores 0 block-observation diffs with an exact hidden/biome
handoff), and the boundary equivalence assertion stays enabled. The Overworld
replay on the same branch was open at 6 chunks / 41 cells off (down from
83 light-different chunks before the executor replay landed, commit
`be04c41`).

Resolved and merged 2026-09-26. The 41 cells were the missing chunk-activation
post-processing (`LevelChunk.postProcessGeneration`); round 8 (`c56323e`) added
`ChunkActivation`, so the Overworld replay is now 0 diffs as well and the light
replay is exact in all three dimensions at 324/324 chunks each.
`PostProcessParityCheck` holds the pass at 1323 checks, and G10 landed on main as
`42d7027`.

