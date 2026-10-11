# G2 step 10: exact chunk generation at Paper speed

Goal, set on 2026-09-24: generate chunks at least as fast as Paper
while staying exactly Vanilla. This replaces the old plan in
`g2-build-order.md`, where Step 10 was an optional Tier-F parallel FEATURES
mode. Nothing here is an opt-in fast mode. Every chunk must still pass both
Tier-R gates (`FeaturesTraceReplay --structures` and
`LiveStructuresTraceCheck --replay-chunk-loads --replay-lighting`). Any
reordering must be an order Vanilla's own scheduler can produce.

Code: `LiveGenerator` (`enableParallelCarvers`, `prefetchCenters`,
`ensureCarvers`, `buildCarvers`), `HeadlessNoise` (`prepareStructural`,
`fillPrepared`, `readSectionCells`, `writeStoreCompact`, `RegionView`),
`SectionStore.compactFromCells`, `AsyncChunkGenerator.runOne`.
Measurement and checks: `GenSpeedBench`, `ParallelCarversCheck`,
`SectionReadCheck`, `harness/paper-genbench`.

## 1. Why the chunk-local stages may run in parallel

Vanilla runs a chunk's BIOMES and NOISE fills on background worker
threads (`NoiseBasedChunkGenerator.createBiomes` and `fillFromNoise` hand
their work to `Util.backgroundExecutor()`), and different chunks' early
stages overlap freely. Only FEATURES and light are serialized, on
consecutive executors. So a Vanilla server can legally produce "the
chunk-local stages of many chunks run at once, in any interleaving". Each of
them is a pure function of:

- the chunk position and the seed;
- the chunk's structure starts and references (Beardifier input), fixed once
  STARTS/REFERENCES are done;
- for SURFACE, the neighbouring chunks' biomes (also pure).

Kestrel's split:

| work | where | why |
|---|---|---|
| STRUCTURE_STARTS, STRUCTURE_REFERENCES (`structureWindow`) | owner thread, in the same order as before; STARTS for lookahead chunks may be computed ahead on `kestrel-starts` (section 6) | mutates the shared `structureStarts` map, and gate reload replays hook in here |
| BIOMES, NOISE, SURFACE, CARVERS | pool (`kestrel-carvers-N`) | pure per chunk |
| FEATURES, INITIALIZE_LIGHT, light, store writes | owner thread | Vanilla serializes these |

How the pooled part stays pure:

- It reads a private copy of the 17x17 starts window, taken on the owner
  thread after REFERENCES. Its `StructureManager` never sees the live
  `HashMap`, which the owner keeps mutating.
- SURFACE reads neighbour biomes from `biomeReady`, a `ConcurrentHashMap`
  mirror of `grid`, or from the concurrent `noiseMargin`. Biomes are pure,
  so which source answers cannot change the result.
- `ensureChunk` joins its whole 3x3 before FEATURES. So FEATURES sees
  exactly the chunks it saw before, in the same state.
- Gate-only reload hooks (`replayChunkReloadForGate`, the block-entity and
  heightmap variants) throw if the chunk is still owned by the pool, so a
  replayed reload can never race a pooled build.

`AsyncChunkGenerator` passes its queued centres as lookahead, so the pool
builds the next centres' 3x3 while the owner runs FEATURES for the current
one. There are at most `MAX_PREFETCHED` (24) outstanding builds.

## 2. Owner-thread cost cuts (same output)

- `writeStoreCompact` reads a section's cells straight from the paletted
  container's storage and palette (MethodHandles on `PalettedContainer.data`,
  `Data.storage`, `Data.palette`), instead of making 4096 `getBlockState`
  calls. `SectionReadCheck` compares both paths cell by cell.
- `compactFromCells` reuses the previous cell's id for runs. It also assigns
  palette ids with a linear identity scan, falling back to an
  `IdentityHashMap` past 24 states, instead of a `LinkedHashMap`. This is
  safe because `StateHolder.equals`/`hashCode` are `Object`'s (javap), so
  identity is equality, and ids stay in first-occurrence order.
- With structures on, the neighbour margin only builds biomes, because
  SURFACE reads nothing else from it.
- `RegionView`'s `WorldGenLevel` used to be a `java.lang.reflect.Proxy`
  whose handler switched on the method name. In the pooled profile that
  handler, `Proxy.invokeDefault` and its lookups took about 16% of the
  owner thread. It is now an inner class, `RegionLevel`, that writes the
  handler out one method at a time:
  - every name the switch answered has an override with the same body;
  - every default method the switch passed through is left inherited;
  - the abstract methods it never handled (`getChunkSource`,
    `getLevelData`, `getServer`) throw `UnsupportedOperationException`.
- The live store is rewritten for each chunk of the 3x3 around every centre,
  so a chunk is published about nine times, and most of its sections have not
  changed since the last time. Grid chunks are now built with
  `HeadlessNoise.TrackedSection`, a `LevelChunkSection` subclass whose flag is
  set when a block write changes a cell (the old state is not the new one)
  or when `read` replaces the contents. Sections come from the same
  constructor and factory that `replaceMissingSections` would use. javap
  shows that only `LevelChunkSection` writes its block container, and that
  the 4-argument `setBlockState` delegates to the 5-argument one, so the two
  overrides see every change. `writeStoreCompact` skips a clean section only
  when the target is the generator's declared exclusive store
  (`LiveGenerator.setExclusiveStore`). In that case the section's last
  publication is still exactly what publishing it again would leave there.
  The async worker's private shadow store and the gate and benchmark stores
  are exclusive. The synchronous path writes the live store, which player
  edits also touch, so it keeps full writes. Switching to another exclusive
  store marks every section dirty.

## 3. Evidence

All gate traces are the retained w00/w01 windows in `/tmp/traces`.

| change set | strict w00 | live w00 | live w01 | pooled live w00 | pooled live w01 |
|---|---|---|---|---|---|
| write path + margin (`19d8cd2`) | PASS 4324/4324 | PASS 4324 | PASS 4289 | PASS 4324 | PASS 4289 |
| + RegionLevel, palette scan, fastutil maps, store fast path | PASS 4324/4324 | | | PASS 4324 (668 s) | PASS 4289 (640 s) |
| + dirty-section tracking | PASS 4324/4324 | | | PASS 4324 (585 s) | PASS 4289 (593 s) |
| + STARTS thread (section 6) | PASS 4324/4324 | | | PASS 4324 (622 s) | PASS 4289 |
| + margin biome reuse (section 7) | PASS 4324/4324 | | | PASS 4324 | PASS 4289 |

`ParallelCarversCheck` runs the production job sequence twice, inline and
pooled with lookahead. It then compares:

- every block of every touched chunk;
- the generated key sets;
- every biome and light wire payload, and the light emission order;
- the final light wire of every chunk in the square.

A flipped cell must be reported exactly once (the negative control). All
runs PASSED:

| seed | centre | size | lookahead | chunks | serial s | pooled s |
|---|---|---|---|---|---|---|
| 4242424242 | 1000,1000 | 12 | 4 | 256 | 43.3 | 20.6 |
| 7272240032024010793 | 0,0 | 16 | 8 | 400 | 54.4 | 29.5 |
| 7272240032024010793 | -200,150 | 32 | 4 | 1296 | 127.5 | 58.0 |
| same, with dirty-section tracking | -200,150 | 32 | 4 | 1296 | 108.8 | 47.0 |
| same, + STARTS thread | -200,150 | 32 | 4 | 1296 | 93.6 | 32.7 |
| same, + margin biome reuse (1293 chunks reused) | -200,150 | 32 | 4 | 1296 | 106.9 | 46.2 |

The biome-reuse run shared the machine with two other gate JVMs, so its times
are not comparable to the row above. Its serial pass runs Vanilla's own
`createBiomes`, and only the pooled pass reuses margin biomes, so the check
compares the two directly.

The last run's square contains a trial chambers start (ENCAPSULATE terrain
adaptation, so the Beardifier is exercised), 8 mineshafts, a jungle pyramid
and two kinds of ruined portal.

## 4. Speed

Oracle VPS (4 cores, Ampere), seed 4242424242, 32x32 chunks around
1000,1000 brought to LIGHT (Kestrel) or FULL (Paper), fresh world each run.

| server | mode | chunks/s |
|---|---|---|
| Paper 26.2, default config (1 chunk worker) | `getChunkAtAsync`, 64 in flight | 14.71, 13.39 |
| Kestrel before Step 10 | direct | 6.55 |
| + biomes-only margin | direct | 7.10 |
| + section read path | direct | 9.69 |

Final numbers (dirty-section tracking in, 2026-09-24, machine otherwise idle
apart from an unrelated indexer using about half a core):

| server | mode | chunks/s |
|---|---|---|
| Kestrel, all of the above | direct, inline | 11.47 |
| Kestrel, all of the above | direct, 3 pool threads | 28.45 |
| Kestrel, all of the above | production pipeline (`AsyncChunkGenerator` + tick loop), 3 pool threads | 21.48 |
| Paper 26.2, default config (1 chunk worker on this machine) | `getChunkAtAsync`, 64 in flight | 14.71, 13.39 |
| Paper 26.2, `chunk-system.worker-threads: 3` | same | 36.45, 33.36 |

CPU cost: Kestrel inline spends about 88 CPU seconds on the 1024 chunks.
Paper with 3 workers spends 104 to 111 CPU seconds, on 3.6 to 3.7 cores.

## 5. Results

(These were the results before sections 6 to 8. Section 8 has the current
numbers, where Kestrel matches Paper given 3 workers.)

- Against Paper as shipped (default config) Kestrel's production pipeline is
  1.46x faster (21.48 vs 14.71 chunks/s) and every gate stays exact.
- Against Paper given the same 3 extra threads it is not: Paper reaches about
  35 chunks/s. Kestrel's pooled direct run reaches 28.45 and the pipeline
  21.48. Kestrel needs less CPU per chunk, but it does not scale as well.
  Paper runs FEATURES for chunks whose 3x3 regions do not overlap on
  several threads at once. Vanilla runs FEATURES one at a time, and Kestrel
  keeps it on the owner thread.
- Where the owner thread's time goes (JFR, 16x16 pooled, generation only):
  - FEATURES 43%;
  - STRUCTURE_STARTS/REFERENCES 30% (much of it one-time template loading
    in a short run);
  - the store write 21%, down from about 37% before dirty-section tracking.
  The pool threads are mostly idle, so the owner thread is the limit.
- Next candidates:
  - move STRUCTURE_STARTS to the pool for lookahead chunks (pure per chunk;
    needs a thread-safety audit of the jigsaw and template caches, because
    Vanilla never runs two STARTS at once);
  - write only a chunk's final state once its whole 3x3 has run FEATURES
    (changes what partially generated chunks look like in the store).

## 6. STRUCTURE_STARTS on its own thread

`LiveGenerator.enableParallelCarvers` also starts one daemon thread,
`kestrel-starts`. `prefetchCenters` queues a STARTS `FutureTask` for every
chunk within radius 9 of each lookahead centre that has no start yet (at most
`MAX_STARTS_AHEAD`, 1024; when full, entries more than 32 chunks from the
current centre are cancelled). The owner thread still walks
`structureWindow` in exactly the old order. When it needs a chunk's starts it
takes that chunk's future: if the future has not started, the owner thread
runs it itself; otherwise it waits for it. Either way the owner thread puts the
result into `structureStarts` at the same point in its walk as before.
Pooled chunk builds are only submitted once the chunk's radius-8 STARTS window
is ready, so a lookahead build never waits on a STARTS future.

Why this is safe:

- STARTS for a chunk is a pure function of the seed, the chunk position and
  the registries. javap shows that `StructureCheck` and
  `ChunkGeneratorStructureState` are only used by `createStructures` and the
  locate paths, so the only state STARTS shares with FEATURES is the
  template palette cache.
- `StructureTemplate.Palette`'s cache is a plain `HashMap`.
  `HeadlessNoise.ConcurrentTemplates` swaps it for a `ConcurrentHashMap`,
  which holds the same values, when a template is first loaded.
- `createStructures` itself runs under `HeadlessNoise.STARTS_LOCK`, so two
  STARTS never run at the same time, as in Vanilla.

The thread is neutral for speed on the 4-core VPS: direct mode went from
29.04 and 29.52 to 28.24 and 29.17 chunks/s. It does take STARTS off the owner
thread (its STARTS share went from about 13% to 0%). The reason is below.

### The machine is CPU-bound

Per-thread CPU on a 32x32 direct run (from `/proc/<pid>/task/*/stat`,
sampled every 2 s):

| thread | CPU seconds |
|---|---|
| `Worker-Main-N` (Vanilla's background executor: createBiomes, fillFromNoise) | about 55 |
| `kestrel-carvers-N` (SURFACE, CARVERS, orchestration) | about 22 |
| owner (`main`) | 20 to 25 |
| C2 and C1 compilers | about 22 |
| `kestrel-starts` | 4 |
| GC | 3 |

Inline generation uses 126.7 CPU seconds for the run, and pooled uses about
125.5, so the pool adds no overhead. With about 3.5 of 4 cores busy, running
more stages in parallel cannot help. Only less work per chunk can. JFR by
stage: NOISE 32%, BIOMES 17%, SURFACE 13%, CARVERS 10%, FEATURES 6%,
STARTS 4%, store writes 4%.

## 7. Reusing the margin chunk's biomes

With structures on, SURFACE needs its neighbours' biomes, so for a chunk not
yet in the grid `prepareBiomeGrid` builds a biomes-only margin chunk. When
that chunk is later built for real, Vanilla's BIOMES computed the same biomes
a second time. That was about 8% of all CPU.

`HeadlessNoise.createBiomesFrom` now does what
`NoiseBasedChunkGenerator.doCreateBiomes` does (javap, 26.2), step by step:

1. `chunk.getOrCreateNoiseChunk(c -> createNoiseChunk(c, manager, blender,
   randomState))`, calling the private method through a MethodHandle;
2. `BelowZeroRetrogen.getBiomeResolver(blender.getBiomeResolver(biomeSource),
   chunk)`, which returns the biome source itself for `Blender.empty()` and a
   chunk that is not upgrading;
3. `chunk.fillBiomesFromNoise(resolver,
   noiseChunk.cachedClimateSampler(router, spawnTarget))`.

The one change is the resolver in step 3. It returns the biome the margin
chunk holds at the same quart. The margin chunk ran this same BIOMES at the
same position with the same inputs, and the biome source's answer depends
only on the quart position and the seed. So each value is exactly what the
biome source would return. `fillBiomesFromNoise` still builds every
section's container itself, in its own order, so the palettes are identical
bit for bit. Steps 1 and 2 are kept, so the NoiseChunk that NOISE uses next
is created exactly as before.

It only applies on the pool path (`fillPrepared` with a donor from
`noiseMargin`), and only when the blender is `Blender.empty()`, neither chunk
is upgrading, and position and section range match. Otherwise Vanilla's
`createBiomes` runs. `HeadlessNoise.BIOMES_FROM_DONOR` counts reuses:
1293 of 1296 chunks in the 32x32 check.

## 8. Speed after sections 6 and 7

Same machine and square as section 4, idle machine, runs interleaved A/B.

| build | mode | chunks/s |
|---|---|---|
| `7539cb0` (queue cap 8) | direct, cold JVM | 28.99, 29.39 |
| + STARTS thread, biome reuse | direct, cold JVM | 32.16, 29.64 |
| `7539cb0` | production pipeline, cold JVM | 27.84, 27.81 |
| + STARTS thread, biome reuse | production pipeline, cold JVM | 29.70, 27.79 |
| + STARTS thread, biome reuse | direct, warm JVM | 37.77, 37.89 |
| + STARTS thread, biome reuse | production pipeline, warm JVM | 35.47, 34.65 |
| Paper 26.2, `worker-threads: 3` | `getChunkAtAsync`, warm JVM | 36.45, 33.36 |

`GenSpeedBench -Dgenspeed.warmup=24` first generates a 24x24 square around
chunk 0,0 in a separate `LiveGenerator`, then times the real square in a
fresh one. Paper's timed run starts after its spawn area is prepared, so it
is always warm. Its progress output still climbed from 24 to 33 chunks/s
during the run.

Result: with both JVMs warm, Kestrel's production pipeline matches Paper
given 3 chunk workers (mean 35.06 against 34.91 chunks/s), and direct
generation is about 8% faster (37.8). Every chunk is still exact, and
FEATURES and light stay serialized. Cold, the JIT costs Kestrel about 22 CPU
seconds, so a cold run is about 18% slower.


## 9. Close-out verification (2026-09-24)

Everything below uses frozen classes of `22ddbe5`.

- `ParallelCarversCheck` with 3 threads passes on three more seeds, each a
  32x32 square, 1,296 chunks and 127,401,984 cells:

  | seed | chunk area | lookahead | serial / pooled (s) |
  |---|---|---|---|
  | 4242424242 | 31,31 | 8 | 120.8 / 45.3 |
  | -8812345678901234567 | 1250,-940 | 8 | 94.4 / 48.1 |
  | 1316656063 | -3000,2000 | 4 | 97.3 / 56.2 |

  Lookahead 8 is the production queue cap. `biomesFromDonor` was 1293 in all
  three runs.
- Live replay with `--carver-threads=3` passes on 12 captured traces
  (51,642 chunks, 0 diffs):
  - VPS: w00 and w01.
  - Bench PC: camp4 w01, w07, w09, w10 and w15; camp1 w07; camp2 w03 and w04;
    camp3 w03.
  - camp5 w00, whose campaign passes `--live-arg=--carver-threads=3`, so
    every camp5 window now exercises the pool.
- `g2-final-regression.sh` passes all 7 checks and both self-tests.
- Live route (`g2-final-kestrel-live.sh`, VPS): 65/65 chunks with 0 block,
  biome-wire and light-wire diffs, with and without `MALLOC_ARENA_MAX=2`.
- Interleaved speed rounds against Paper with 3 workers, both warm:
  34.07 vs 33.54 chunks/s at 1000,1000 and 39.65 vs 37.11 at -2500,1800
  (`docs/BENCHMARKS.md`).

Open items that are not part of Step 10:
- ~~`--async-chunkgen` is still opt-in~~ Done 2026-09-24: async generation is
  the default, and `--sync-chunkgen` selects the old tick-thread path.
- Live-world handoff of generated block-entity NBT, generated entities and
  scheduled ticks. See the banner in `docs/SKIPPED-FEATURES.md`.
- Strict-harness classes 2 and 6. These are limits of the instrument; no
  generation diff was found.
