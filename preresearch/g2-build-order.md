# G2 build order: real terrain (hills, caves, ores) instead of the flat platform

Status: G2 STEP 10 COMPLETE. Steps 1-10 are complete (Step 10 on
`step10-parallel-gen`, 2026-09-24). Step 8's frozen exact
milestone is `d29b2520156608413627359938ad95fd9eee9b93`. Step 9's final
product/runtime head `f320e560192ac985a360453f1243c5ee3574b3f0` passed the
player-driven live/network and performance gates, and closeout head
`910d979a7f3bbcb0abbd4ba6efef8dbafb8f8622` passed the full regression plus
canonical structures-on Tier-R rerun. Step 9 is integrated to `main`.
Step 10 (Paper-speed generation, still exact) is complete (see below).

Implementation checkpoint:

| Step | State |
|---|---|
| 1, oracle corpus | Done; P1/P2/P3 control evidence retained |
| 2, corpus import | Done; W1/W2 memory gate measured |
| 2b, compact storage | Done; W1 B=12.79 MiB, 5-player projection=40.5 MiB |
| 3-4, headless registries + NOISE | Done; P1 oracle gates green |
| 5, parallel NOISE | Done; 1/2/4/8-thread P1 equality verified |
| 6-7, SURFACE/CARVERS/FEATURES | Done; exact matched-order Tier-R proof retained |
| 8, structures | **DONE + FROZEN**; LARGE 1418/1418 exact, 139,395,072 cells, 0 diffs |
| 9, live generation | **DONE**; 65/65 live repeat exact, bench PC MSPT/latency/throughput/RSS/GC gates green, final full regression + 1418/1418 canonical Tier-R green |
| 10, Paper-speed exact generation | **DONE**; warm pipeline 34.07 vs Paper 33.54 and 39.65 vs 37.11 chunks/s; 12 traces / 51,642 chunks replayed live through the pool with 0 diffs |

This revision replaces the stopwatch-v1 version (commit 9a73698). That
version was built on ~10 rows from a stopwatch with two bugs; both were fixed
in `bc454e7`, `dce8d50`, and `7ad2340`.

Inputs, in the order they were used:

- `preresearch/chunkgen-path-analysis.md` (the "grounding doc"). Cited as
  **§N** (section) and **trap N**.
- **jfr-v1**: vanilla's own `minecraft.ChunkGeneration` JFR events, parsed by
  `harness/tools/jfr-baseline.py`. Bench PC 5700X3D, seed 4242424242, 689
  events. NOISE median 208 ms, SURFACE 13.6 ms, BIOMES 49 ms, CARVERS ~10 ms,
  FEATURES n=1 at 243 ms, STRUCTURE_* ~0. Event counts shrink
  529 -> 49 -> 25 -> 10 -> 1 down the pyramid. Only the center chunk reached
  FEATURES in 4 minutes.
- **ASM-v2**: the ASM stopwatch after the bug fixes. 81 rows, separate
  INITIALIZE_LIGHT / LIGHT labels. Inline medians: total ~25 ms,
  FEATURES ~13 ms, SURFACE ~9 ms, CARVERS ~2 ms. BIOMES, NOISE and LIGHT
  columns only time the submit, not the work.
- **MSPT-ref**: tick-log MSPT proxy (`harness/tools/mspt-table.py`), same
  seed, tp-bot spiral. Vanilla median ~412 ms (saturated by chunkgen), Paper
  median ~50 ms (async chunk pipeline).
- `preresearch/core-block-model-design.md`: `core/` embeds real vanilla
  classes and drives them. It does not reimplement them.

Rules used below:

- A cost claim is **HYPOTHESIS** unless it is a jfr-v1 / ASM-v2 / MSPT-ref
  number, or plain arithmetic on one (marked "derived from ...").
- Structural claims cite the grounding doc (which cites decompiled
  class:line), or a Kestrel repo file:line.

## 0. What the numbers do and do not say

### 0.1 Which instrument can be trusted for which stage

| Stage | Runs on (§1.11) | jfr-v1 duration means | ASM-v2 column means |
|---|---|---|---|
| STRUCTURE_STARTS / REFS | worldgen consecutive executor, inline | work | work |
| BIOMES | `Util.backgroundExecutor()` | work **+ pool queue wait** | submit only |
| NOISE | `Util.backgroundExecutor()` | work **+ pool queue wait** | submit only |
| SURFACE / CARVERS / FEATURES | worldgen executor, inline | ~work | work |
| INITIALIZE_LIGHT / LIGHT | "light" consecutive executor | work + light queue wait | submit only |
| FULL | main thread | work + main-thread wait | not probed |

The JFR event opens before `task.doWork` and commits in `thenApply`
(`ChunkStep.java:23-24`, §5 item 1 caveat). That split is why the JFR and
ASM columns mean different things for the async stages.

### 0.2 Findings

1. **NOISE dominates wall-clock time per chunk** (jfr-v1: 208 ms median, vs
   ~25 ms for the whole inline chain in ASM-v2). That fits §3 item 1. What
   is **not** known is how much of the 208 ms is `doFill` CPU and how much is
   waiting for a pool thread. BIOMES is already 49 ms in jfr-v1 while it is
   "small-moderate" by hypothesis (§3 item 6), which suggests that pool
   queue wait is a real part of both numbers (HYPOTHESIS).
2. **The inline worldgen thread is not what limits this run.** Derived from
   ASM-v2: 25 ms per chunk serialized is a ceiling of ~40 chunks/s. Observed
   in jfr-v1: 1 chunk reached FEATURES in 4 minutes. Chunks are not blocked
   in the inline stages. They wait upstream (NOISE / BIOMES futures) or on
   ticket propagation (Q2).
3. **Inside the inline chain, FEATURES is the largest share, but by less
   than v1 said.** Derived from ASM-v2: 13 / 25 ≈ 52% (v1 said ~71%). If
   SURFACE + CARVERS moved off that thread, the inline ceiling would go from
   1000/25 = 40 to 1000/13 ≈ 77 chunks/s (~1.9x). That only pays off after
   finding 2 is fixed, so it comes after NOISE in the build order.
4. **ASM-v2 and jfr-v1 disagree on the inline stages**, and they come from
   different runs and chunk populations. SURFACE: 9 vs 13.6 ms. CARVERS: 2 vs
   ~10 ms. FEATURES: 13 ms median over 81 rows vs one 243 ms sample. The v1
   plan's gate ("ASM within ±10% of JFR for inline stages") has **not been
   evaluated**. The data does not fail it, it just cannot test it. One run
   with both instruments, joined on `(chunkX, chunkZ, status)`, is needed
   before either number becomes an exit threshold (§4, row "M0").
5. **The live pyramid cannot supply FEATURES samples.** jfr-v1's
   529 -> ... -> 1 shrink is the dependency pyramid of a small target
   (`ChunkPyramid.java:11-42`, §1). To get N >= 1000 FEATURES rows, the target
   area has to be ~32x32 chunks at FULL. Its lower-status rings add roughly
   another 8 chunks of margin per side (REFERENCES and BIOMES require
   STRUCTURE_STARTS @ 8, §1.2-1.3, `ChunkPyramid.java:14`), which is
   (32+16)^2 ≈ 2300 chunks of starts work. Derived from jfr-v1's pace, that
   is not feasible live, so FEATURES evidence comes from a **pregenerated
   corpus** (step 1), not from watching a spiral.
6. **MSPT-ref gives G2 its live target.** When vanilla generates on this
   route, its MSPT median is ~412 ms. Paper's async pipeline gets ~50 ms.
   Kestrel's live generation (step 9) is gated against both numbers, on the
   same route and proxy.

## 1. What G2 has to deliver, and the shape constraint

Today Kestrel sends plains biome and full-bright light
(`core/src/vanilla/java/dev/kestrel/net/ChunkSender.java:36`) for a flat
platform. Blocks live in `SectionStore`, which holds a dense
`BlockState[4096]` plus `boolean[4096]` per live section
(`core/src/vanilla/java/dev/kestrel/loop/SectionStore.java:57-59`).

G2 = the terrain vanilla 26.2 generates for the same seed: hills, caves,
ores, then structures and real light. Per `core-block-model-design.md`
("Decision: embed"), Kestrel **drives vanilla's generator classes**. Speed
comes from scheduling, caching and parallelism around vanilla calls, which the
§2 purity table allows up to CARVERS.

Parity oracle: a per-chunk **block hash** (block states + primed heightmaps,
entities excluded, trap 19) taken at three cut points:

- **P1** = NOISE complete (the `fillFromNoise` future completes, not method
  exit, §0.1)
- **P2** = CARVERS exit (end of the pure prefix, §2)
- **P3** = FULL

## 2. Ordered build list

Complexity 1-10. Each step lists Entry, Exit and the exit instrument:
**HD** = headless differential against the corpus, **LT** = live trace,
**MSPT** = MSPT delta on MSPT-ref's route and proxy.

### Step 1: Vanilla oracle corpus, structures OFF, with NOISE work/wait split (zero-risk first step)

Harness only. Vanilla runs unmodified, and nothing Kestrel serves changes.

- Work:
  1. Pregenerate a fixed area (block-coordinate forceload, as fixed in
     `harness/tools/chunkgen-watch.ps1:88-91`) with `generate-structures=false`.
     Record per-chunk P1/P2/P3 hashes and the global sequence of
     `generateFeatures` entries (needed for step 7, §3 D1). Save the region
     files.
  2. In the same run, add inside-supplier timers on
     `NoiseBasedChunkGenerator.doFill` (`:387`) and `doCreateBiomes` (`:97`)
     (§5 item 2), plus JFR on. That gives NOISE work vs wait from the same
     chunks.
  3. Repeat in 3 separate JVM launches with the same order, then once in a
     second order (reverse spiral).
- Why this first, from the numbers:
  - The dominant cost is NOISE (finding 1), and NOISE sits inside the prefix
    that §2 claims is pure. Every later Kestrel step that saves time touches
    NOISE (steps 4-6). That claim should be **proved on vanilla** (P1/P2
    identical across launches and orders) before building on it.
  - FEATURES evidence at any useful N only comes from a corpus (finding 5).
  - The 208 ms has to be split into work and wait (finding 1) before a
    parallel NOISE driver can be sized. The split is a cheap addition to the
    same run.
  - Structures off removes traps 2, 3, 4, 5, 11 and 18 from the oracle
    without touching vanilla. `generateStructureStarts` skips
    `createStructures` when `generateStructures()` is false (javap
    `ChunkStatusTasks`, offset 17). With no starts, the Beardifier adds
    nothing (§1.3). jfr-v1 already shows STRUCTURE_* ~0, so for a first
    corpus the loss of coverage is small.
  - Risk: the worst case is a wrong hash probe, and the 3-launch repeat
    catches that.
- Entry: none beyond what exists (fixed forceload, split light labels,
  `jfr-baseline.py`). Needs a normal-world structures-off switch in the run
  script (harness only).
- Exit:
  1. HD (vanilla vs vanilla): P1 and P2 100% equal across 3 launches and
     across both orders, N >= 1000 chunks at P2. If P2 depends on order, §2 is
     wrong and **the plan stops for re-grounding**.
  2. P3 cross-order diff rate and its spatial pattern recorded (trap 13,
     Q3).
  3. NOISE work median and p95 (inside-supplier) next to jfr-v1-style
     completion median, N >= 1000.
- Complexity: **3**.

### Step 2: Kestrel serves the imported corpus (no generation)

- Work: decode the step-1 region files with Kestrel's `persist/ChunkCodec`
  into `SectionStore` and stream them with the existing `ChunkSender`. Biomes
  stay plains and light stays full-bright.
- Entry: step 1 corpus. Flat-platform bench baseline (RSS, MSPT, boot) from
  the existing `harness/bench.yaml` scenarios.
- Exit:
  1. HD: Kestrel block hash per imported chunk == P3.
  2. LT: tp-bot client receives sections whose re-hashed blocks == P3.
  3. RSS at view distance 10 recorded vs the flat baseline (Q5 memory
     estimate). No threshold yet, but step 9 needs the number.
     **MEASURED 2026-09-16**: `harness/tools/parity/mem-w1w2/mem-w1w2-notes.md`.
     Verdict: 2b (compact/paletted section storage) is required before step 9
     exit, the design doc's own multi-player gate (`perChunk x 2205`) already
     exceeds the 350 MiB whole-server budget from block storage alone.
- Complexity: **4**.
- Why here: it is the first Kestrel-side change and still has zero parity
  risk (the blocks are vanilla's). It also exposes the dense-section memory
  cost (Q5), which might force a storage change unrelated to worldgen.

### Step 3: Headless worldgen registries + RandomState

- Work: in `core/`, load the worldgen registries (noise settings, noise
  parameters, biomes, carvers, placed features) without a server, then
  `RandomState.create` (`RandomState.java:34-40`). Start from
  `net/Datapack.java`, which already reads vanilla datapack JSON.
- Entry: step 2 exit. Q7 (minimal loader path). Trap 20 says
  `Bootstrap.bootStrap()` alone will not do it, and
  `core-block-model-design.md` already found "Bootstrap binds no tags".
- Exit: HD. For 1000 fixed quart positions, `biomeSource.getNoiseBiome`
  matches the corpus biomes. `SurfaceSystem.generateBands` clay bands match
  element by element (`SurfaceSystem.java:57, 288-326`).
- Complexity: **6** (HYPOTHESIS, `RegistryDataLoader` not decompiled).

### Step 4: Headless NOISE (BIOMES + fillFromNoise), structures off

- Work: `createBiomes` + `fillFromNoise` on a fresh `ProtoChunk` with
  `Blender.empty()` (exact for fresh worlds, `Blender.java:57-62`) and a
  structure manager that yields no starts. These entry points take
  `(Blender, RandomState, StructureManager, ChunkAccess)` and no region
  (trap 21), so no `WorldGenRegion` is needed yet. Keep the section locks
  (trap 22), the top-down Y walk (trap 23), and one NoiseChunk per chunk per
  thread (`NoiseChunk.java:260-267`). Keep that NoiseChunk alive through
  step 6's CARVERS (trap 17).
- Entry: step 3 exit. Step 1 NOISE work median known.
- Exit: HD. P1 100% equal on >= 1000 corpus chunks, including a shuffled
  order.
- Complexity: **5**.
- Why before SURFACE/CARVERS: NOISE is the dominant cost (finding 1) and the
  one pure stage that needs no region view. It is the smallest headless
  surface that covers the biggest cost.

### Step 5: Parallel NOISE driver

- Work: run step 4 on K threads, forking per-thread state the way vanilla
  does before going parallel (`ChunkGeneratorStructureState.java:125-128`).
  Compute per-chunk memos eagerly per thread, never lazily on shared objects
  (`ChunkAccess.java:418-432`, §2 last bullet).
- Entry: step 4 exit. Step 1 work/wait split shows that wait is a large share
  of the 208 ms (if wait ≈ 0, parallelism only helps when chunks overlap in
  time, and the step shrinks to "pool size = cores").
- Exit:
  1. HD: P1 100% equal at 1, 2, 4, 8 threads, shuffled, 3 launches.
  2. Throughput: NOISE-complete chunks/s vs vanilla's completion rate
     (JFR, same area). Target: completion ≈ work time (no queue wait),
     HYPOTHESIS.
- Complexity: **5**.

### Step 6: Headless SURFACE + CARVERS (full pure prefix), parallel

- Work: `SurfaceSystem.buildSurface` and `applyCarvers` against a Kestrel
  region view. Keep SURFACE before CARVERS (trap 15), the carver order
  dx -> dz -> index with one shared CarvingMask (traps 7, 16), the Legacy RNG
  for carvers (trap 8), and the `nextInt(4)` draw before `canReach` (trap 6).
  Run on the step-5 pool.
- Entry: step 5 exit. Q6 answered (region view without `ServerLevel`,
  trap 21).
- Exit: HD. P2 100% equal at 1/2/4/8 threads, shuffled, 3 launches,
  N >= 1000. Throughput re-derived against finding 3's bound.
- Complexity: **7** (trap 21 is the hard part).
- A tunnel-path cache is **not** part of this step. Per ASM-v2, CARVERS is
  ~2 ms, too small to justify a cache before §5 item 6 counts replays.

### Step 7: FEATURES, structures off, serialized, replaying vanilla's order

- Work: a `generateFeatures` equivalent: heightmap priming, then
  `applyBiomeDecoration` (trap 14), in a radius-1 write view that drops
  out-of-zone writes like `WorldGenRegion.ensureCanWrite`
  (`WorldGenRegion.java:274-296`). Vanilla `PlacedFeature` code is called
  unchanged. One thread. The parity strategy is section 3.
- Entry: step 6 exit. §5 item 4 FEATURES split (per `stepIndex`, top-20
  feature keys) from a corpus-sized run. Step 1 P3 cross-order diff rate.
- Exit: HD tier R (P3 100% under the replayed order, N >= 1000). HD tier F
  (Kestrel's own order) within vanilla's cross-order diff, in overlap zones
  only.
- Complexity: **8**.

### Step 8: Structures ON (starts, references, Beardifier, FEATURES structure block)

**State: COMPLETE AND FROZEN** at
`d29b2520156608413627359938ad95fd9eee9b93`.

The structures-on path is landed end-to-end: real STARTS/REFERENCES,
Beardifier-backed NOISE, explicit FEATURES structure placement,
structure-reference deserialize round-trips, template-backed headless
feature/structure views, and captured light-lifecycle ordering. Exact gates
fail closed on missing lifecycle evidence or feature exceptions.

Canonical structures-on evidence, seed `4242424242`:

- SMALL: 49 / 49 ring-2 scoreable chunks exact, 4,816,896 cells, 0 diffs;
- LARGE: 1,418 / 1,418 exact, 139,395,072 cells, 0 diffs;
- structure call/source/reference-state coverage complete;
- no captured block, height, light, or iterator answer is injected.

Do not modify this frozen branch as part of Step 9.

### Step 9: Live generation in Kestrel, with real light and biomes on the wire

**State: COMPLETE** (2026-09-21). The final runtime head `f320e56` passed the
Bench PC live/system gate, closeout head `910d979` passed the full regression and
the canonical 1,418/1,418 structures-on Tier-R gate, and the branch is merged
to `main`. The "Remaining Step-9 work" list below is kept for history; all
three items are done.

Completed and gated through
`fc5285014ac14bb933d082fefc8c4ea4514f68fa`:

- fresh generation moved off the tick thread;
- bounded async backlog with deduplication/failure propagation and one
  deterministic publication path;
- exact generated biome-container wire state handed to live chunk packets;
- authoritative generated LIGHT payloads handed off and already-lit neighbours
  republished when later propagation changes them;
- focused async/order/biome/light regression checks green;
- FAR80 normal strict Tier-R: 121 / 121 exact, 11,894,784 cells, 0 diffs;
- native worldgen SPAWN gate: 169 / 169 captured stages matched, 36 inserted
  entities, 169 FULL-LIGHT completions replayed, 0 final-state diffs;
- KEX4 durability: generated completion and exact biome/light wire metadata
  survive save/reload, metadata participates in snapshot hashes and is
  region-validated, sidecar-less import clears stale metadata, and KEX1-KEX3
  remain backward-readable.

The SPAWN gate executes Kestrel's own
`NaturalSpawner.spawnMobsForChunkGeneration`. The capture supplies ordering
and expected assertions only. Narrow normalization is allowed only for proven
entity-local entropy; reproducible worldgen state remains strict.

Remaining Step-9 work:

1. run the final player-driven Vanilla-vs-Kestrel route gate, including block
   state, biome and light/network output;
2. record MSPT p50/p95, fresh completion throughput and p50/p95 latency, queue
   depth, RSS, and connected-player GC behavior on the established route;
3. merge Step 9 only after all mandatory correctness and system gates are
   green.

### Step 10: Paper-speed generation, still exact

Replaced on 2026-09-24. The old text made Step 10 an optional Tier-F
parallel FEATURES mode. The goal is now to generate chunks at least as fast
as Paper while every chunk still passes both Tier-R gates. Only orders
Vanilla's own scheduler can produce are allowed, and there is no opt-in fast
mode. Design, evidence and measurements: `g2-step10-design.md`.

**State: COMPLETE** (2026-09-24, `step10-parallel-gen`). BIOMES, NOISE,
SURFACE and CARVERS run on a pool of up to 3 threads, and STRUCTURE_STARTS
for lookahead chunks runs on its own thread. BIOMES reuses the biomes that a
chunk's margin copy already computed. FEATURES and light stay serialized in
Vanilla's order. Results:

- With both JVMs warm, the production pipeline measured 34.07 chunks/s
  against Paper's 33.54 (3 chunk workers) at 1000,1000, and 39.65 against
  37.11 at -2500,1800. Direct generation measured 37.18.
- 12 captured traces were replayed live with `--carver-threads=3`. That is
  51,642 chunks, with 0 diffs.
- The strict gate is exact (w00 4324/4324), and `ParallelCarversCheck`
  matches inline output.
- The full Step-9 regression passes, and the 65-chunk live route has 0 block,
  biome-wire and light-wire diffs.

### Worldgen SPAWN status

The old assumption that SPAWN was outside G2 is retired. Vanilla 26.2's SPAWN
stage is now grounded and captured with exact scheduler/light cutpoints. At
`a5e51e6`, the strict native gate matches 169 / 169 captured SPAWN stages
and 36 entity insertions while preserving 0-diff final Tier-R state. Captured
entity/light answers are never fed back into generation.

## 3. FEATURES parity strategy

Constraints, each grounded:

- **Neighbor writes.** A chunk's features write into its 8 neighbors
  (radius 1, `ChunkPyramid.java:31-37`, trap 13). Out-of-zone writes are
  dropped silently (`WorldGenRegion.java:274-296`).
- **Order.** Vanilla serializes FEATURES on one consecutive executor in
  ticket-level order (§1.11, `AbstractConsecutiveExecutor.java:39-60`,
  `ChunkTaskDispatcher.java:63-76`; the priority queue itself was not
  decompiled). So vanilla does not give the same blocks for every load
  order (§2).
- **Lazy-stream RNG interleave.** Placement modifiers draw lazily, mixed in
  with the feature's own draws (trap 1). Collecting positions first and then
  placing shifts every later draw.

Decisions:

- **D1. Primary gate = replay vanilla's recorded `generateFeatures` order
  (tier R).** Because the executor is consecutive, the entry sequence is a
  total order that can be recorded (step 1) and replayed exactly. For
  **structures-off**, FEATURES order is the full replay key: FEATURES is the
  only stage with write radius > 0 (§2 table), neighbors are already at
  CARVERS before a chunk's FEATURES runs (§1.7), and heightmap priming is per
  chunk at FEATURES start (trap 14). For **structures-on**, Q9 proved one
  additional lifecycle input is observable: a partial chunk may be serialized
  and reloaded between FEATURES calls, rebuilding its structure-reference
  `LongOpenHashSet` and changing iteration order without changing membership.
  Tier R therefore also records timestamped `SerializableChunkData.read`
  events and replays the deterministic reload operation at the same event
  cutpoint. The fresh structures-on runtime trace established a second
  scheduler input: `FEATURES` runs on the worldgen consecutive executor
  while `INITIALIZE_LIGHT/LIGHT` run on a separate consecutive light
  executor, so their interleaving is not derivable from FEATURES order alone.
  Strict structures-on Tier R therefore records the actual post-status
  `INITIALIZE_LIGHT` completions in `lighting-events.tsv` at the
  `ChunkStep.apply` future-completion boundary. The async callback freezes
  its `afterEventSeq` / active-origin cutpoint before taking the trace writer
  lock, so snapshot I/O cannot move a completion across a FEATURES boundary.
  Replay uses the captured row only to decide *when* to execute Kestrel's real
  `WorldgenLightLifecycle.initialize` operation. It fails closed on a
  non-`OK` completion, a sequence gap, incomplete replay coverage, or an
  intra-FEATURES completion until a finer hook exists. Captured persisted
  statuses and light nibbles are never written into Kestrel. It must never
  copy captured iterator order, `n/minN`, height answers, light answers, or
  blocks back into Kestrel. Traps 4, 13, 14.
- **D2. Three separate gates.**
  - Tier P: P1/P2 exact in any order and thread count (§2). Gates steps 4-6.
  - Tier R: P3 exact under the replayed order. Gates steps 7-9.
  - Tier F: P3 under Kestrel's own order. The allowed diff equals vanilla's
    own cross-order diff (step 1), **only inside overlap zones** (within
    chunk distance 2 of a neighbor that ran in a different order, trap 13
    geometry). A diff outside an overlap zone is always a bug, never
    tolerance.
- **D3. Never port placement streams (trap 1).** `PlacedFeature.placeWithContext`
  and its modifiers run as vanilla code. Any later FEATURES optimization
  (biome prefilter, trap 12; position batching, trap 1) must also pass an
  **RNG trace diff**: `WorldgenRandom` state after every
  `(chunk, stepIndex, featureIndex)`, vanilla vs Kestrel, 100 chunks. Block
  equality can hide a shifted draw that only shows up in rare chunks.
- **D4. Reseeding copied verbatim.** Decoration seed from block-min
  coordinates (trap 9). `setFeatureSeed` per global feature index (trap 12)
  and per structure index, counting absent structures (traps 2, 3). Xoroshiro
  for features, Legacy for structures and carvers (trap 8). Keep the
  one-operator difference between `setDecorationSeed` and
  `setLargeFeatureSeed` (trap 10). Embedding gives all of this for free. D3's
  trace diff is the tripwire if someone hand-writes any of it.
- **D5. Structure pieces go before features within each step (trap 11), and
  only from step 8 on.** Structures off first (step 1 rationale). Trap 18
  (Beardifier `HashMap` order) is measured by the structures corpus 3-launch
  repeat, not assumed away.
- **D6. Reference iteration is lifecycle-sensitive (trap 4).** Starts of one
  structure share one random, and `StructureManager.startsForStructure`
  consumes the iteration order of the chunk's `LongOpenHashSet` references.
  Fastutil does **not** preserve insertion order here: its iterator scans the
  open-addressing table from high slots to low slots. A fresh reference set is
  created with `new LongOpenHashSet()`, while
  `SerializableChunkData.unpackStructureReferences` reconstructs a saved set
  with `new LongOpenHashSet(long[])`; the different table size/probe layout
  can reverse two equal reference memberships. This can happen after an
  in-process partial-chunk unload/reload without a server restart, so merely
  keeping one oracle JVM alive does not guarantee "fresh" references. Q9 must
  reproduce and model that partial-chunk lifecycle, not sort or globally trim
  reference sets.
- **D7. Hash contents.** Block states + the four primed heightmaps (trap 14).
  Block-entity ids yes, NBT no. Entities no (trap 19). Fluid post-processing
  marks and border ticks (`NoiseBasedChunkGenerator.java:442-455`) stay out
  until Q11.
- **D8. Out-of-zone writes are dropped, not thrown.** Kestrel mirrors
  `ensureCanWrite`. Throwing would "fix" features that vanilla silently
  truncates.
- **D9. FEATURES cost targets come from the corpus, not from jfr-v1.** jfr-v1
  has one 243 ms sample and ASM-v2 has a ~13 ms median. Nearly 20x apart, so
  variance is large (fits §3 item 2's per-biome hypothesis). No FEATURES
  optimization is proposed until §5 item 4 runs at the N in section 4.

## 4. Measurement per step

Tool choice, following §0.1:

- Inline stages: **ASM** gives per-row values joinable by chunk.
- Async stages: **JFR** for completion time (includes wait) plus **ASM
  inside-supplier** for work.
- FULL: JFR, plus a new ASM probe.
- Tick impact: MSPT proxy.

Medians are only compared at N >= 1000. p95 needs the same N.

| Id / before step | Rows | N | JFR vs ASM | Notes |
|---|---|---|---|---|
| M0 (inside step 1) | STARTS, REFS, SURFACE, CARVERS, FEATURES inline; BIOMES/NOISE completion | same run, joined on (x, z, status) | **both** | Settles finding 4. Gate: inline ASM within ±10% of JFR per joined chunk population. If they disagree, the probe is suspect |
| 1 | `doFill`, `doCreateBiomes` work; NOISE/BIOMES completion; P1/P2/P3 hashes; FEATURES sequence | >= 1000 at P2; P3 as many as the corpus reaches | ASM inside-supplier + JFR | 3 launches + second order. Hashes on future completion |
| 2 | none new | all corpus chunks | none | bench baseline: RSS, MSPT p50/p95, boot |
| 3 | none | 1000 quart positions | none | functional HD only |
| 4 | Kestrel NOISE work ms per chunk | >= 1000 | ASM-style nanoTime in Kestrel | compare with step-1 vanilla work median (±10%, embed means the same code) |
| 5 | chunks/s at 1/2/4/8 threads; pool idle % | >= 1000 per config | Kestrel counters; vanilla JFR completion as reference | §5 item 3 occupancy on vanilla for contrast |
| 6 | SURFACE, CARVERS work; carver `isStartChunk` hits and in-range `carveEllipsoid` per chunk | >= 1000 | ASM (vanilla), Kestrel nanoTime | §5 item 6 counts, no cache without them |
| 7 | FEATURES structure vs feature block, per `stepIndex`, top-20 feature keys | >= 1000 corpus chunks; >= 200 per dominant biome | ASM (vanilla corpus run) | D9. §5 item 4 |
| 8 | same as 7 on structures corpus; STARTS/REFS p95; `iterateNoiseColumn` calls per STARTS | >= 1000 | ASM | §5 item 7; trap-18 list from 3 launches |
| 9 | INIT_LIGHT/LIGHT work (inside PRE_UPDATE runnables, `ThreadedLevelLightEngine.java:146, 167`); FULL main-thread ms; MSPT p50/p95 | route of MSPT-ref length; >= 1000 FULL chunks | JFR (FULL, light completion) + ASM (work) + MSPT proxy | same seed, route, proxy as MSPT-ref |

## 5. Open questions (with proposed experiments)

- **Q1. How much of jfr-v1's 208 ms NOISE is CPU?** *Experiment:* step 1
  item 2. Inside-supplier `doFill` timer vs JFR completion, same chunks. If
  work ≈ 208 ms, step 5's value is "more cores". If work is much smaller,
  vanilla's pool is starved and step 5's value is "no queue".
- **Q2. Why did only one chunk reach FEATURES in 4 minutes?** Derived from
  ASM-v2, the inline chain alone would allow ~40 chunks/s. *Experiment:* in
  one run, record the forceload/target area actually requested, JFR
  completion timestamps per status, and §5 item 3 worldgen executor busy %.
  Candidates: small target area (pyramid geometry alone gives 1 FULL chunk
  for a 1-chunk target), pool starvation (Q1), or ticket propagation stalled
  behind a saturated main thread (MSPT-ref vanilla ~412 ms). HYPOTHESIS, all
  three.
- **Q3. How often does vanilla disagree with itself at FEATURES?** (Trap 13.)
  ANSWERED, 2026-09-18, see `g2-vanilla-order-determinism.md`. Seed
  4242424242, structures off, 400 `minecraft:full` chunks. Thread scheduling
  alone: 99.8476% cell parity, 42.75% of chunks byte-identical. Adding a
  reversed row order: 99.6560%, 12.25%. Same with random ticks frozen:
  99.6660%, 14.50%. So the answer is "most of the time", not "rarely", and
  the diffs do not sit only in overlap zones: the nested-interior ladder
  shows the rate rising toward the middle of the box. Kestrel's own
  cross-order figure is 99.6564%, which matches vanilla within 0.01
  percentage points.
- **Q4. Is trap 18 real?** *Experiment:* use `/locate` on seed 4242424242 to
  find chunks with >= 2 terrain-adapting starts. Generate them in 3 JVM
  launches with the same order and compare P1.
- **Q5. What does real terrain cost in Kestrel RAM?** Groundable from
  `SectionStore.java:57-59` and `:63`, as HYPOTHESIS arithmetic
  (compressed oops, 64-bit HotSpot, 16-byte array headers):
  - Per live section: `BlockState[4096]` = 16 + 4096 x 4 ≈ 16.4 KB, plus
    `boolean[4096]` ≈ 4.1 KB, plus the Section object, the boxed `Long` key
    and the `HashMap` node (~0.1 KB). About **20.6 KB per section**.
  - Overworld is 24 sections tall (y -64..319). If import only writes
    non-air cells, sections above the surface stay absent. At a surface of
    ~y 64-100, about 11 sections per chunk are live: 11 x 20.6 ≈ 227 KB per
    chunk. If import writes air explicitly, all 24 are live: ≈ 494 KB per
    chunk.
  - One player at view distance 10 (21 x 21 = 441 chunks): **~100 MB** (sparse
    import) to **~218 MB** (air written), for blocks alone, against the
    ~350 MB whole-server target (`README.md`).
  - For contrast, vanilla's paletted storage for a section with <= 16 states
    is 4 bits x 4096 = ~2 KB plus the palette, about 10x smaller (HYPOTHESIS,
    `PalettedContainer` not decompiled in this repo's grounding docs).
  - *Experiment:* step 2 exit 3. Import the corpus at view distance 10,
    measure RSS and a heap histogram (`jcmd GC.class_histogram`, count of
    `BlockState[]` and `boolean[]`), and check whether `ChunkCodec` writes
    air cells. If blocks exceed ~1/3 of the budget, a paletted section
    becomes its own step between 2 and 3.
- **Q6. Can a `WorldGenRegion`-compatible view be built without
  `ServerLevel`?** (Trap 21.) *Experiment:* javap the constructor and every
  `level.` call reachable from `buildSurface`, `applyCarvers`,
  `applyBiomeDecoration`. Try the dynamic-proxy approach that worked for
  `LevelReader` (`core-block-model-design.md`). Fall back to a headless
  `ServerLevel` and record its RSS.
- **Q7. What is the minimal headless registry load?** (Trap 20.)
  *Experiment:* decompile `RegistryDataLoader` and the `WorldLoader` path in
  `server/Main`. Then run step 3's 1000-position biome diff.
- **Q8. Generate light, or import vanilla's?** *Experiment:* check whether
  corpus region files carry section light and whether `ChunkCodec`
  round-trips it. Compare with light work time from the step-9 row.
- **Q9. Do structure references reloaded from disk keep their iteration
  order?** (Trap 4.) **ANSWERED: no.** In 26.2,
  `ChunkAccess.addReferenceForStructure` creates a default
  `LongOpenHashSet`, while `SerializableChunkData.unpackStructureReferences`
  reloads the serialized long array with `new LongOpenHashSet(long[])`.
  Fastutil iteration scans backing slots from high to low, so changing the
  table size/probe layout can change iteration order even when membership is
  identical. For seed 4242424242 the two mineshaft refs `(0,6)` and `(3,7)`
  are a concrete witness: the fresh and one-roundtrip layouts iterate in
  opposite orders, which changes the shared structure RNG stream and therefore
  blocks. The remaining experiment is narrower: trace `n/minN` plus chunk
  lifecycle during one uninterrupted capture to locate the in-process
  partial-chunk unload/reload boundary that triggers the roundtrip.
- **Q10. Is the `generateFeatures` order a sufficient replay key?** (D1.)
  **ANSWERED 2026-09-19: yes for structures-off; not by itself for
  structures-on.** FEATURES is serial: independent captures report
  **max active = 1** even when the consecutive executor moves work between
  worker threads, so `events.tsv` is a genuine total order. For
  structures-off that order is sufficient: the current Tier-R proof scores
  **1433/1433 chunks exact, 140,869,632 cells, 0 differences**, satisfying the
  Step-7 >=1000 requirement. The old 54/56 / 43-cell result in
  `g2-tier-r-first-result.md` is historical, not current state.
  Structures-on exposed two additional cross-executor/lifecycle inputs that
  FEATURES order cannot encode: partial chunk deserialize events can rebuild
  structure-reference hash tables, and `INITIALIZE_LIGHT` completes on the
  separate light executor. Strict structures-on Tier R therefore combines
  FEATURES total order with timestamped `SerializableChunkData.read` events
  and explicit post-status `ChunkStep.apply` lighting-completion events. The
  fresh far-window regression on seed `4242424242` replays all 121 captured
  light completions and scores 49 ring-2-closed chunks / 4,816,896 cells with
  **0 differences**; the independent native live gate is also exact. No
  captured block, height, light, persisted-status, or structure-iterator
  answer is injected.
  A partial reload also drops heightmaps: `SerializableChunkData` persists
  only `getPersistedStatus().heightmapsAfter()`, which from CARVERS on is
  `FINAL_HEIGHTMAPS`, so a stale `OCEAN_FLOOR_WG` is discarded and re-primed
  lazily from current blocks. The strict replay models this since `682ca2c`
  (2026-09-23); without it the 96x96 structures-on window at chunk 2000,2000
  (seed 1860846406223168458) failed at the first reload cutpoint with a
  height mismatch that turns into block diffs. With it: **9,529/9,529
  ring-2-closed chunks, 936,738,816 cells, 0 differences**, 195,966,626
  height queries exact.
  The native live gate (`LiveStructuresTraceCheck`) needs the same two
  cross-executor inputs. `LiveGenerator` initializes light immediately after
  each center's FEATURES, but Vanilla completes INITIALIZE_LIGHT on the light
  executor in later batches, and an uninitialized column reads skylight 15:
  on an 11x11 window at chunk 1000,1000 of seed 4242424242 that flipped 14
  mushroom survival checks (brightness < 13) at origin 1012,1010. With
  `--replay-chunk-loads --replay-lighting` the gate defers light
  initialization to the captured completion cutpoints, round-trips reload
  heightmaps like the strict replay, and treats a captured re-initialization
  after a replayed reload as a no-op (Vanilla re-runs INITIALIZE_LIGHT on
  reload; 3016 of 10342 chunks on big96). Result (`5f14a99`): far1000
  169/169 chunks exact, big96 **9,529/9,529 chunks, 936,738,816 cells,
  0 differences**, 10,345 FEATURES windows exact, all 13,358 light
  completions accounted for.
  Oracle read semantics: the capture snapshots `LevelChunkSection
  .getBlockState`, while `ProtoChunk.getBlockState` answers AIR for any
  section whose `hasOnlyAir()` is true, cave_air included. Comparing through
  the chunk reported cave_air written into an otherwise empty section as a
  diff (origin 11x11 window of seed 1: a mineshaft corridor's top layer at
  y=-16). Since `66d557a` every oracle comparison reads the raw section
  (`FeaturesTraceReplay.capturedState`); that seed-1 window now passes
  169/169 and big96 still passes 9,529/9,529.
  Spawn-area windows: Vanilla generates chunks while selecting the world
  spawn, before the capture's ready marker, so origin windows used to see
  them as untraced, already-advanced chunks (seed 4242424242:
  LIFECYCLE_STATUS_MISMATCH at (-1,-1)). `capture-features-order.py
  --trace-startup` arms the trace before boot. Startup also saves and
  reloads chunks already at INITIALIZE_LIGHT with the completion and the
  reload at the same FEATURES cutpoint; since Vanilla publishes that status
  only after the completion, the live gate now applies the cutpoint's
  pending completions through that chunk before the reload (`36f7555`).
  Seed 4242424242 origin 11x11 then passes 169/169 in both the strict
  replay and the live gate. `harness/tools/parity-campaign.py` runs the
  unattended multi-seed campaign (capture, strict replay, live gate,
  pipelined; origin windows use --trace-startup).
- **Q11. Do fluid post-processing marks and border ticks change visible
  terrain?** PARTLY ANSWERED 2026-09-18, see `g2-fluid-oracle-artifact.md`.
  Yes, and it already contaminates the oracle. The saved corpus holds 1847
  pending fluid ticks across 264 chunks, plus 31539 non-source water cells
  and 10438 non-source lava cells that exist only because the server ticked.
  Kestrel's headless run never ticks, so those cells are air, which
  manufactures the `water -> air` and `lava -> air` rows that top its
  mismatch list. Roughly three quarters of the `water -> air` row is
  accounted for this way; the remainder is not yet explained. Consequence: a
  ticked saved world is not a valid oracle for an untickled generator. Tier R
  avoids this entirely, because `FeaturesTrace` snapshots chunks in memory at
  the generateFeatures event, before any ticking.
  Original question follows. *Experiment:* count scheduled fluid ticks per corpus chunk at
  FULL, tick 200 game ticks with no players, diff blocks. Nonzero means they
  have to be generated and saved.
- **Q12. Does `generate-structures=false` also skip FEATURES' structure
  block?** Starts are skipped (javap offset 17). The skip at
  `ChunkGenerator.java:347` depends on `WorldGenRegion.shouldGenerateStructures()`,
  which was not traced. *Experiment:* `javap -c WorldGenRegion`, plus a step-1
  counter showing 0 `placeInChunk` calls.
- **Q13. Why do ASM-v2 and jfr-v1 disagree on CARVERS (2 vs ~10 ms)?**
  *Experiment:* M0 joined run. If the gap remains for the same chunks, check
  what JFR counts inside `ChunkStep.apply` besides `doWork`
  (`ChunkStep.java:21-40`, status marking) before blaming either
  instrument.
