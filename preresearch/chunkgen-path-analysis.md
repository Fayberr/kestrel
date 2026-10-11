# G2 grounding: chunk-generation path analysis

Status: ANALYSIS ONLY, no production code. Based on the actual decompiled
vanilla 26.2 source, not secondary-source guesswork. Written ahead of G2 (real
terrain) so the stopwatch script and any later Kestrel port start from what
the jar really does.

Method: extracted `META-INF/versions/26.2/server-26.2.jar` (sha256 prefix
`183c0499c5f85557`) from `preresearch/vanilla-26.2/server.jar`, then decompiled
a selected class set with Vineflower 1.12.0 (`-e=server-26.2.jar` for type
resolution). All `File.java:N` citations below are line numbers in **that
Vineflower output**. Fresh decompiles with another Vineflower version shift
lines cosmetically (decompiler output is not stable across versions), so treat line numbers as
anchors and the quoted signatures as the ground truth. Paths are relative to
`net/minecraft/`.

Measurement status: **nothing in this doc was timed.** The Pi has JDK 17, no
JDK 25 runtime and no bundler `libraries/` for a live run. Every cost claim is
marked **HYPOTHESIS**. Timing has to be done on the bench PC.

Read in full or in the relevant part: `ChunkStatus`, `ChunkPyramid`, `ChunkStep`,
`ChunkStatusTasks`, `ChunkGenerator`, `NoiseBasedChunkGenerator`, `NoiseChunk`
(constructor, slice/cell fill), `RandomState`, `WorldgenRandom`,
`XoroshiroRandomSource$XoroshiroPositionalRandomFactory.at`, `SurfaceSystem`,
`Aquifer` (RNG and fluid-level parts), `OreVeinifier`, `Beardifier`,
`WorldCarver`, `CaveWorldCarver`, `ConfiguredWorldCarver`, `PlacedFeature`,
`RepeatingPlacement`/`CountPlacement`/`InSquarePlacement`/`BiomeFilter`,
`StructureStart.placeInChunk`, `Structure` (generate/RNG parts),
`RandomSpreadStructurePlacement`, `StructurePlacement`,
`ChunkGeneratorStructureState.generateRingPositions`, `StructureManager`,
`ChunkAccess` (structure maps, noise chunk memo), `BiomeManager.getBiome`,
`Blender.of`, `WorldGenRegion` (chunk access and write guard),
`ChunkGenerationTask`, `ChunkMap` (worldgen scheduling), `GenerationChunkHolder.applyStep`,
`ChunkTaskDispatcher`, `AbstractConsecutiveExecutor`, `ThreadedLevelLightEngine`
(initializeLight/lightChunk), `LevelLightEngine`, `SkyLightEngine.propagateLightSources`,
`JfrProfiler`, `ChunkGenerationEvent`.

**NOT decompiled / not read** (claims touching these are flagged inline):
`NaturalSpawner` (spawn step), `Feature` subclasses (tree, ore, lake bodies),
`SurfaceRules` rule-tree internals beyond the context caches, `DensityFunctions`
and `NormalNoise`/`ImprovedNoise` bodies, `BlockLightEngine`, `LightEngine` BFS
internals, `LevelChunk` constructor, `ChunkTaskPriorityQueue` ordering,
`RegistryDataLoader`, `StructureCheck` internals, jigsaw (`JigsawPlacement`).

## Premise check (corrections to the task framing)

The task lists stages as "noise, surface, carvers, features, structures,
lighting". The real 26.2 order differs in two ways that matter:

1. **Structures are not one stage and are not late.** Structure *starts* and
   *references* are computed **first**, before biomes and noise
   (`ChunkStatus.java:22-23`, `ChunkPyramid.java:13-14`). Structure *pieces*
   are only placed into blocks during FEATURES, interleaved per decoration
   step with ordinary features (`ChunkGenerator.java:345-366`). Noise already
   reads the starts (terrain adaptation via `Beardifier`), so structures sit on
   both ends of the pipeline.
2. **There is a BIOMES stage before NOISE, and it creates the NoiseChunk.**
   `NoiseBasedChunkGenerator.doCreateBiomes` calls
   `protoChunk.getOrCreateNoiseChunk(...)` (`NoiseBasedChunkGenerator.java:98`),
   which memoizes the `NoiseChunk` on the chunk (`ChunkAccess.java:418-423`).
   NOISE, SURFACE and CARVERS then reuse that same object
   (`NoiseBasedChunkGenerator.java:302, 324, 395`). So "noise cost" is split
   across BIOMES (router wiring, Beardifier build, aquifer allocation) and
   NOISE (the fill).

Two more "looks nondeterministic, isn't" premises worth killing early:

3. Features, carvers and spawn each build their RNG from
   `RandomSupport.generateUniqueSeed()` (`ChunkGenerator.java:328`,
   `NoiseBasedChunkGenerator.java:321, 502`), which is time/entropy based.
   **That seed is dead**: every one of them is immediately overwritten by
   `setDecorationSeed` / `setLargeFeatureSeed`, both of which start with
   `this.setSeed(seed)` (`WorldgenRandom.java:44-51, 58-64`). The generated
   terrain is deterministic in the seed. A port must still reseed exactly the
   same way; it must not "simplify" by seeding the constructor.
4. JFR already emits a per-chunk, per-status timing event. No bytecode agent
   is needed for the first stage-level numbers (see section 5).

## 1. Pipeline stages in order

Registry order from `ChunkStatus.java:21-32`:
`empty -> structure_starts -> structure_references -> biomes -> noise -> surface
-> carvers -> features -> initialize_light -> light -> spawn -> full`.

Per-stage dependencies and write radius come from
`ChunkPyramid.GENERATION_PYRAMID` (`ChunkPyramid.java:11-42`). "Requires X @ r"
means every chunk within chessboard radius r must be at least status X.
`blockStateWriteRadius` is enforced by `WorldGenRegion.ensureCanWrite`
(`WorldGenRegion.java:274-296`; out-of-zone writes are logged and dropped).
Neighbor reads beyond the allowed status/radius crash
(`WorldGenRegion.java:119-139`).

Task entry points are in `world/level/chunk/status/ChunkStatusTasks.java`.
Every task runs through `ChunkStep.apply` (`ChunkStep.java:21-28`), which marks
the persisted status on completion (`ChunkStep.java:30-40`).

### 1.1 STRUCTURE_STARTS
- Entry: `ChunkStatusTasks.generateStructureStarts` (`:41`) ->
  `ChunkGenerator.createStructures(RegistryAccess, ChunkGeneratorStructureState,
  StructureManager, ChunkAccess, StructureTemplateManager, ResourceKey<Level>)`
  (`ChunkGenerator.java:471-558`), then `level.onStructureStartsAvailable(chunk)`.
- Requires: nothing (only parent EMPTY). Write radius: default.
- Inputs: level seed, chunk pos, `ChunkGeneratorStructureState.possibleStructureSets()`,
  noise via `RandomState`, biome source.
- Flow per structure set: skip if a valid start already exists on this chunk
  (`:489-494`); `StructurePlacement.isStructureChunk` (`StructurePlacement.java:82-93`:
  placement grid, frequency reduction, exclusion zone); if one structure in
  the set, `tryGenerateStructure`, else weighted selection loop (`:511-552`).
  `tryGenerateStructure` -> `Structure.generate(...)` (`Structure.java:83`) ->
  `findValidGenerationPoint` filtered by `isValidBiome` (`Structure.java:204-205, 131-138`);
  valid starts stored via `setStartForStructure` (`ChunkGenerator.java:590-592`).
- Outputs: `ChunkAccess.structureStarts` (a `HashMap<Structure, StructureStart>`,
  `ChunkAccess.java:79`).
- RNG:
  - Placement grid: `RandomSpreadStructurePlacement.getPotentialStructureChunk`
    builds `new WorldgenRandom(new LegacyRandomSource(0L))` then
    `setLargeFeatureWithSalt(seed, gridX, gridZ, salt)` (`RandomSpreadStructurePlacement.java:67-76`).
    Stateless per grid cell.
  - Weighted choice among a set's structures: `WorldgenRandom(LegacyRandomSource(0L))`
    + `setLargeFeatureSeed(levelSeed, chunkX, chunkZ)` (`ChunkGenerator.java:513-514`).
    Draws `nextInt(total)` once per attempt, **including failed attempts**
    (a rejected candidate is removed and the loop draws again, `:521-552`).
  - Structure body: `Structure.GenerationContext.makeRandom` =
    `WorldgenRandom(LegacyRandomSource(0L))` + `setLargeFeatureSeed(seed, chunkX, chunkZ)`
    (`Structure.java:247-250`).
  - Concentric rings (strongholds): computed once per world, not per chunk, in
    `ChunkGeneratorStructureState.generateRingPositions`
    (`ChunkGeneratorStructureState.java:107-167`): one `RandomSource` seeded
    with `concentricRingsSeed`; per ring position it draws angle/distance and
    `random.fork()` **sequentially on the calling thread** (`:125-128`) before
    handing the biome search to `Util.backgroundExecutor()` (`:130-149`).
    This is vanilla's own "fork before going parallel" pattern and is the
    model to copy for any Kestrel parallelism.
- Height sampling inside structure generation goes through
  `getFirstOccupiedHeight` -> `NoiseBasedChunkGenerator.iterateNoiseColumn`
  (`NoiseBasedChunkGenerator.java:195-268`), which constructs a **fresh
  1-cell `NoiseChunk` per column query** (`:229-239`) with
  `BeardifierMarker.INSTANCE` and `Blender.empty()`.
- Timing hook: `Structure.generate` already wraps itself in
  `JvmProfiler.INSTANCE.onStructureGenerate` (`Structure.java:97`).

### 1.2 STRUCTURE_REFERENCES
- Entry: `ChunkStatusTasks.generateStructureReferences` (`:68`) ->
  `ChunkGenerator.createReferences(WorldGenLevel, StructureManager, ChunkAccess)`
  (`ChunkGenerator.java:605-635`).
- Requires: STRUCTURE_STARTS @ 8 (`ChunkPyramid.java:14`).
- Flow: for each source chunk in the 17x17 square (`:614-615`), for each valid
  start whose bounding box intersects this chunk's XZ, add the source chunk
  key to `structuresRefences` (`:618-621`; a `HashMap<Structure, LongSet>`
  with `LongOpenHashSet` values, `ChunkAccess.java:80, 231`).
- RNG: none.

### 1.3 BIOMES
- Entry: `ChunkStatusTasks.generateBiomes` (`:77`) ->
  `NoiseBasedChunkGenerator.createBiomes` (`NoiseBasedChunkGenerator.java:88-95`),
  **async** on `Util.backgroundExecutor().forName("init_biomes")`.
- Requires: STRUCTURE_STARTS @ 8.
- Flow (`doCreateBiomes`, `:97-101`): get-or-create the chunk's `NoiseChunk`
  (`createNoiseChunk`, `:103-107` -> `NoiseChunk.forChunk` with
  `Beardifier.forStructuresInChunk(structureManager, pos)`), then
  `fillBiomesFromNoise(biomeResolver, noiseChunk.cachedClimateSampler(router, spawnTarget))`.
- `NoiseChunk` constructor (`NoiseChunk.java:108-177`) wires the whole noise
  router through `this::wrap` (`:155`), which materializes per-chunk caches
  for `Interpolated` / `FlatCache` / `Cache2D` / `CacheOnce` / `CacheAllInCell`
  markers (`:390-402`), allocates the aquifer (`Aquifer.create`, `:162-164`)
  and the ore veinifier (`:173`).
- Beardifier build (`Beardifier.java:41-91`) walks starts from
  `structureManager.startsForStructure(chunkPos, terrainAdaptation != NONE)`
  and collects rigid boxes and jigsaw junctions within 12 blocks.
- RNG: none. Biomes are sampled from climate density functions.
- Blending: `Blender.of(region)` returns `EMPTY` unless an old (pre-1.18)
  chunk is around (`Blender.java:57-62`). For fresh worlds this is a no-op.

### 1.4 NOISE
- Entry: `ChunkStatusTasks.generateNoise` (`:86`) ->
  `NoiseBasedChunkGenerator.fillFromNoise` (`NoiseBasedChunkGenerator.java:359-385`),
  **async** on `Util.backgroundExecutor().forName("wgen_fill_noise")`, with
  per-section `acquire()`/`release()` around `doFill`. Followed by the
  `BelowZeroRetrogen` bedrock fix-up for upgraded chunks only
  (`ChunkStatusTasks.java` `generateNoise` body).
- Requires: STRUCTURE_STARTS @ 8, BIOMES @ 1. Write radius 0
  (`ChunkPyramid.java:16-22`).
- Flow (`doFill`, `:387-468`): overworld settings are
  `NoiseSettings.create(-64, 384, 1, 2)` (`NoiseSettings.java:22`), so
  `cellWidth = 4`, `cellHeight = 8` (`getCellWidth/Height`, `:46-52`): 4x4 cells
  in XZ, 48 cells in Y. Loop order is **cellX -> cellZ -> cellY (top-down) ->
  yInCell (top-down) -> xInCell -> zInCell** (`:409-441`), with
  `advanceCellX` filling the next interpolation slice (`NoiseChunk.java:270-273`,
  `fillSlice` `:241-258`) and `selectCellYZ` filling every `CacheAllInCell`
  for the cell (`NoiseChunk.java:305-317`). Per block:
  `noiseChunk.getInterpolatedState()` (material rule list: aquifer
  `computeSubstance` on the cached final density, then ore veins,
  `NoiseChunk.java:167-176`), `section.setBlockState`, two heightmap updates
  (`OCEAN_FLOOR_WG`, `WORLD_SURFACE_WG`), and fluid post-processing marks
  (`NoiseBasedChunkGenerator.java:442-455`). `swapSlices()` after each cellX (`:463`).
- RNG: positional only, no sequential stream.
  - Aquifer: `positionalRandomFactory.at(gridX, gridY, gridZ)` per aquifer grid
    point, memoized in `aquiferLocationCache` (`Aquifer.java:190-199`).
    Randomized fluid levels use noise, not RNG (`Aquifer.java:484-496`).
  - Ore veins: `oreVeinsPositionalRandomFactory.at(x, y, z)` **per block** that
    passes the vein toggle (`OreVeinifier.java:43-51`), allocating a new
    `XoroshiroRandomSource` each call (`XoroshiroRandomSource.java:122-126`).
  - Both factories come from `RandomState` via `fromHashOf("aquifer"/"ore")`
    (`RandomState.java:45-46`), themselves forked from the settings' RNG algorithm
    seeded with the world seed (`RandomState.java:43`).

### 1.5 SURFACE
- Entry: `ChunkStatusTasks.generateSurface` (`:108`) ->
  `NoiseBasedChunkGenerator.buildSurface(WorldGenRegion, ...)` (`:271-277`) ->
  `SurfaceSystem.buildSurface(...)` (`SurfaceSystem.java:68-167`).
  **Synchronous** (runs on the worldgen dispatcher thread, see 1.11).
- Requires: STRUCTURE_STARTS @ 8, BIOMES @ 1. Write radius 0.
- Inputs: this chunk's blocks after noise, `WORLD_SURFACE_WG` heightmap,
  biomes via `region.getBiomeManager()`, `collectPossibleBiomes(region, 1)`
  over the 3x3 chunks (`NoiseBasedChunkGenerator.java:279-290`), the memoized
  `NoiseChunk`.
- Flow: per column (x 0..15, z 0..15), eroded-badlands extension, then scan
  y from `WORLD_SURFACE_WG + 1` down to `minY` (`SurfaceSystem.java:128`),
  tracking stone depth above/below and water height; **evaluates the surface
  rule for every block that is still exactly `defaultBlock`** (`:153-157`),
  then frozen-ocean extension (`:162-164`).
- Biome lookup: `BiomeManager.getBiome` does the fuzzed 8-corner zoom
  (`BiomeManager.java:31-65`), which reads neighboring quart cells and so can
  cross into adjacent chunks' biome data (hence BIOMES @ 1).
- RNG: positional only. `noiseRandom.at(blockX, 0, blockZ)` in
  `getSurfaceDepth` (`SurfaceSystem.java:171`) and the frozen-ocean extension
  (`:269-276`). Clay bands are drawn once per `RandomState` at construction
  (`SurfaceSystem.java:57`, `generateBands` `:288-326`).

### 1.6 CARVERS
- Entry: `ChunkStatusTasks.generateCarvers` (`:117`) (plus
  `Blender.addAroundOldChunksCarvingMaskFilter` for upgraded worlds) ->
  `NoiseBasedChunkGenerator.applyCarvers(region, seed, randomState, biomeManager,
  structureManager, chunk)` (`:309-356`). **Synchronous.**
- Requires: STRUCTURE_STARTS @ 8. Write radius 0 (`ChunkPyramid.java:30`).
- Flow: one `WorldgenRandom(LegacyRandomSource(uniqueSeed))` for the whole call
  (`:321`). For each source chunk in the 17x17 square (dx then dz, `:331-332`),
  resolve the source chunk's carver biome
  (`carverCenterChunk.carverBiome(...)`, memoized on the **source** chunk
  object, `ChunkAccess.java:427-432`; computed from `biomeSource.getNoiseBiome`
  at quart (minBlockX, 0, minBlockZ), not from stored biomes). For each carver
  in that biome, in list order with `index` counting all carvers:
  `random.setLargeFeatureSeed(seed + index, sourceX, sourceZ)` (`:346`),
  `carver.isStartChunk(random)` (`CaveWorldCarver.java:22`: one `nextFloat`),
  then `carver.carve(context, chunk, ..., random, aquifer, sourcePos, mask)` (`:348`).
- Carving reads and writes **only the target chunk**: `carveEllipsoid` clamps
  to the chunk's 0..15 indices (`WorldCarver.java:77-90`), checks/sets the
  `CarvingMask` (`:107-108`), and `carveBlock` asks the shared aquifer for the
  replacement (`:168-179`), fixing grass/mycelium top material via surface
  rules (`:153-163`). Overworld biomes use `cave`, `cave_extra_underground`,
  `canyon` (`data/minecraft/worldgen/biome/plains.json`).
- RNG: sequential `LegacyRandomSource` stream per (seed + index, source chunk).
  The tunnel walk draws continuously (`CaveWorldCarver.java:36-65, 92-94,
  145-177`) and the `random.nextInt(4)` skip draw happens **before** the
  `canReach` early-return (`:210-213`). The whole tunnel is re-walked for
  every target chunk within range, whether or not an ellipsoid lands in it.

### 1.7 FEATURES (includes structure piece placement)
- Entry: `ChunkStatusTasks.generateFeatures` (`:133`): first
  `Heightmap.primeHeightmaps(chunk, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES,
  OCEAN_FLOOR, WORLD_SURFACE)` (`:137`), then
  `ChunkGenerator.applyBiomeDecoration(region, chunk, structureManager)`
  (`ChunkGenerator.java:320-418`), then `Blender.generateBorderTicks` (`:143`).
  **Synchronous.**
- Requires: STRUCTURE_STARTS @ 8, CARVERS @ 1. **Write radius 1**
  (`ChunkPyramid.java:31-37`): features for chunk C may write into all 8 neighbors.
- Flow:
  - `structuresByStep` = structure registry grouped by `structure.step().ordinal()`
    (`:326`); `featureList = featuresPerStep.get()`, a memoized global
    ordering built by `FeatureSorter.buildFeaturesPerStep` over all possible
    biomes (`:98-100`, `:327`).
  - `possibleBiomes` = every biome in the palettes of the 3x3 chunks, intersected
    with the biome source's possible set (`:330-338`).
  - For `stepIndex` over `max(Decoration.values().length (11), featureStepCount)`
    (`:343-345`; steps listed in `GenerationStep.java:8-18`):
    1. **Structures first**: for each structure in this step, in registry
       stream order, `random.setFeatureSeed(decorationSeed, index, stepIndex)`
       then `startsForStructure(sectionPos, structure).forEach(start ->
       start.placeInChunk(...))` (`:347-366`). `index++` for **every**
       structure in the step, present or not (`:364`).
    2. **Then features**: union of per-biome feature indices for this step,
       sorted ascending (`:369-384`); for each,
       `random.setFeatureSeed(decorationSeed, globalIndexOfFeature, stepIndex)`
       and `feature.placeWithBiomeCheck(level, this, random, origin)` (`:387-401`).
  - `StructureStart.placeInChunk` (`StructureStart.java:79-101`): for each piece
    whose box intersects the chunk's writable box, `piece.postProcess(..., random, ...)`,
    then `structure.afterPlace(...)`, all on the **same** random.
  - `PlacedFeature.placeWithContext` (`PlacedFeature.java:43-61`) chains
    placement modifiers as lazy `Stream.flatMap`s and places the configured
    feature per emitted position, on the same random.
- RNG: one `WorldgenRandom(XoroshiroRandomSource(...))` (`:328`), decoration
  seed from `setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ())`
  (`:329`), where `origin` is the chunk's **minimum block** coordinate
  (`SectionPos.origin()`), not the chunk coordinate. Reseeded per structure
  and per feature via `setFeatureSeed(seed + index + 10000 * step)`
  (`WorldgenRandom.java:53-56`).

### 1.8 INITIALIZE_LIGHT
- Entry: `ChunkStatusTasks.initializeLight` (`:147`): `chunk.initializeLightSources()`,
  attach the light engine to the ProtoChunk, then
  `ThreadedLevelLightEngine.initializeLight(chunk, lighted)`
  (`ThreadedLevelLightEngine.java:144-162`): PRE_UPDATE task marks non-empty
  sections, POST_UPDATE future does `setLightEnabled` + `retainData`.
- Requires: parent only. Runs on the light task queue, not the worldgen one.
- RNG: none.

### 1.9 LIGHT
- Entry: `ChunkStatusTasks.light` (`:157`) ->
  `ThreadedLevelLightEngine.lightChunk` (`ThreadedLevelLightEngine.java:164-180`):
  PRE_UPDATE `propagateLightSources(pos)` if not already lit, POST_UPDATE sets
  `lightCorrect`.
- Requires: INITIALIZE_LIGHT @ 1 (`ChunkPyramid.java:39`).
- `LevelLightEngine.propagateLightSources` (`LevelLightEngine.java:86-94`)
  runs block then sky. Sky reads the 4 orthogonal neighbors' sky-light sources
  (`SkyLightEngine.java:303-310`). Light updates are batched in
  `runUpdate` (up to 1000 tasks per batch, `ThreadedLevelLightEngine.java:191-192`).
- RNG: none.

### 1.10 SPAWN and FULL
- SPAWN: `ChunkStatusTasks.generateSpawn` (`:164`) ->
  `NoiseBasedChunkGenerator.spawnOriginalMobs` (`:498-506`):
  `WorldgenRandom(LegacyRandomSource(uniqueSeed))` +
  `setDecorationSeed(seed, minBlockX, minBlockZ)`, then
  `NaturalSpawner.spawnMobsForChunkGeneration` (**not decompiled**). Requires
  BIOMES @ 1.
- FULL: `ChunkStatusTasks.full` (`:174`) runs on
  `context.mainThreadExecutor()`: builds `LevelChunk` from the ProtoChunk,
  swaps in an `ImposterProtoChunk`, registers block entities and tick
  containers, loads worldgen entities.

### 1.11 Who runs what, on which thread
- Each chunk request becomes a `ChunkGenerationTask` that walks statuses
  layer by layer over its dependency square (`ChunkGenerationTask.java:43-72, 115-157`).
- `ChunkMap.runGenerationTask` submits `task.runUntilWait()` to
  `worldgenTaskDispatcher` (`ChunkMap.java:668-676`), a `ChunkTaskDispatcher`
  over `new ConsecutiveExecutor(executor, "worldgen")` (`ChunkMap.java:195-198`).
  `AbstractConsecutiveExecutor.run` pops **one** task, runs it, then
  re-registers (`AbstractConsecutiveExecutor.java:39-60`, status CAS at `:117`),
  so worldgen dispatch is serialized. The queue is ordered by chunk ticket
  level (`ChunkTaskDispatcher.submit`, `ChunkTaskDispatcher.java:63-76`;
  `ChunkTaskPriorityQueue` not decompiled).
- Consequence, grounded in which tasks return `completedFuture` vs
  `supplyAsync`: STRUCTURE_STARTS, STRUCTURE_REFERENCES, SURFACE, CARVERS,
  FEATURES and SPAWN do their work **inline on the single worldgen consecutive
  executor**. Only BIOMES and NOISE fan out to `Util.backgroundExecutor()`.
  Lighting runs on its own consecutive executor ("light", `ChunkMap.java:197-200`).
  FULL runs on the main thread.

## 2. Pure vs neighbor/global-dependent stages

"Pure" here means: output for chunk C is a deterministic function of
(world seed, C's coordinates, static registries/datapack), independent of what
other chunks have been generated or in what order.

| Stage | Reads beyond own chunk | Writes beyond own chunk | Verdict |
|---|---|---|---|
| STRUCTURE_STARTS | Noise columns + biome source (computed, not stored); global `ChunkGeneratorStructureState` (ring positions) | none | **Pure** (rings are a pure function of seed, computed once) |
| STRUCTURE_REFERENCES | Stored starts of 17x17 neighbors | none | **Pure given starts** (starts are pure, so recomputable) |
| BIOMES | Stored starts within radius 8 (Beardifier) | none | **Pure given starts**; biomes themselves are pure climate sampling |
| NOISE | Same NoiseChunk/Beardifier; `Blender` only for old worlds | none (radius 0) | **Pure given starts** for fresh worlds |
| SURFACE | Neighbor biomes (3x3, zoom) | none (radius 0) | **Pure** (neighbor biomes are pure) |
| CARVERS | 17x17 source-chunk carver biomes (computed from noise, memoized on neighbor objects) | none (radius 0) | **Pure** |
| FEATURES | 3x3 blocks/biomes/heightmaps; starts within radius 8 | **3x3 blocks** | **Order-dependent** |
| INITIALIZE_LIGHT / LIGHT | Neighbor light sources and data | neighbor light data | **Order-dependent** (inherits FEATURES output) |
| SPAWN | Biome (radius 1); NaturalSpawner not read | entities | **Not established** (NaturalSpawner not read; entity `random` is unseeded per collision-path-analysis.md) |
| FULL | none of consequence | level registration | main-thread bookkeeping |

What this means for Kestrel (HYPOTHESIS until measured, but grounded in the
radii above):

- **Everything through CARVERS is a per-chunk pure function of seed, given
  the structure starts in a radius-8 window.** That prefix can be
  precomputed, cached to disk keyed on (seed, datapack hash, chunk pos), or
  generated in parallel without any cross-chunk locking, provided the
  per-chunk `NoiseChunk` is never shared across threads (it is stateful,
  `NoiseChunk.java:260-267` throws on double initialization).
- **FEATURES is the parallelism wall.** With write radius 1, two chunks
  within distance 2 of each other can both write into the chunk between them,
  so the final blocks depend on the order the worldgen queue ran them.
  Vanilla serializes this on one consecutive executor, so there is no race,
  but the order follows ticket levels, which follow player movement. So
  **vanilla itself is not order-independent at FEATURES**: two vanilla runs
  that load chunks in a different order can produce different blocks where
  features from neighboring chunks overlap. HYPOTHESIS on how often this
  shows up; the mechanism (radius-1 writes + priority-ordered serialization) is grounded.
- The carver-biome and noise-chunk memos are written lazily on chunk objects
  without synchronization (`ChunkAccess.java:418-432`). This is safe in
  vanilla only because values are deterministic and the pyramid orders
  access. A parallel Kestrel implementation must keep the "same value
  regardless of who computes it first" property, or compute them eagerly.

## 3. Cost hypotheses per stage (ALL UNMEASURED)

Ranked by expected ms/chunk on a fresh overworld, from work counts in the
decompiled loops. None of this is measured; section 5 says how to measure it.

1. **NOISE: HYPOTHESIS, largest single stage.** `doFill` touches every one of
   the 16x16x384 = 98,304 block positions (`NoiseBasedChunkGenerator.java:409-441`).
   Per position: interpolator reads, `CacheAllInCell` final density
   (`NoiseChunk.java:168-171, 315-317`), an aquifer `computeSubstance`, the ore
   veinifier (which allocates a `XoroshiroRandomSource` per passing block,
   `OreVeinifier.java:43`), and `setBlockState` plus two heightmap updates for
   non-air. Corner evaluation per interpolated function is 5 x 5 x 49 samples
   (slices at `cellCountXZ + 1` and `cellCountY + 1`, `NoiseChunk.java:84-89, 245`).
   It runs off-thread, so it costs throughput more than MSPT.
2. **FEATURES: HYPOTHESIS, second, with the most variance by biome.** 11
   decoration steps, every structure in each step reseeded even when absent
   (`ChunkGenerator.java:348-365`), dozens of ore/vegetation features per
   biome (plains lists 11 ore features in one step alone), each walking a
   lazy placement-modifier stream with per-position biome checks
   (`BiomeFilter.java:21-25`) through `WorldGenRegion`. Serialized on the
   worldgen thread.
3. **LIGHT (+INITIALIZE_LIGHT): HYPOTHESIS, significant.** Sky-light
   propagation walks every column of every section below the top
   (`SkyLightEngine.java:316-328`) and then BFS work (not read). Separate
   consecutive executor.
4. **SURFACE: HYPOTHESIS, moderate, but serialized.** 256 columns x full
   height scan; the surface rule tree is invoked for every remaining
   `defaultBlock` (stone) position (`SurfaceSystem.java:153-154`), which is
   most of the underground. Rule internals not read, so per-call cost is
   unknown. Runs inline on the worldgen thread.
5. **CARVERS: HYPOTHESIS, moderate and very repetitive.** 289 source chunks x
   3 overworld carvers = ~867 `setLargeFeatureSeed` + `isStartChunk` per
   target chunk (`NoiseBasedChunkGenerator.java:331-352`), plus a full tunnel
   re-walk for every nearby start chunk, repeated identically for each of the
   up to 289 target chunks that tunnel can reach. Tunnels are pure per
   (seed, source, carver), so this is the best **cache** candidate.
   Serialized on the worldgen thread.
6. **BIOMES: HYPOTHESIS, small-moderate.** Climate sampling at quart
   resolution plus the NoiseChunk construction (router `mapAll` wrap,
   aquifer arrays). Off-thread.
7. **STRUCTURE_STARTS: HYPOTHESIS, cheap on average, spiky.** Most chunks
   fail the placement grid check cheaply. Chunks that pass pay
   `Structure.generate`, whose height probes each build a fresh 1-cell
   NoiseChunk and scan a column top-down (`NoiseBasedChunkGenerator.java:229-264`);
   `getLowestY` alone does 4 probes (`Structure.java:159-162`). Jigsaw
   structures (not read) likely probe many more.
8. **STRUCTURE_REFERENCES: HYPOTHESIS, cheap.** 289 map iterations.
9. **FULL: HYPOTHESIS, cheap per chunk but on the main thread**, so it counts
   directly against MSPT, unlike everything above.

Cross-cutting HYPOTHESIS: in a fresh-world benchmark, total generation
throughput may be capped by the **single worldgen consecutive executor**
running SURFACE+CARVERS+FEATURES inline (1.11), not by NOISE CPU. If that holds,
the first win comes from getting the pure serialized stages (SURFACE, CARVERS)
off that thread, not from making noise faster.

## 4. Traps (break parity if reordered or "simplified")

RNG sequence traps (same class as the spawn leg-2 lesson in
`spawn-path-analysis.md`: shuffle before attempts, and draws on failed
attempts are part of the sequence):

1. **Placement-modifier draws interleave with feature draws.**
   `PlacedFeature.placeWithContext` builds `Stream.flatMap` chains
   (`PlacedFeature.java:44-48`) and consumes them with `forEach` calling
   `feature.place` (`:52-59`). `RepeatingPlacement.getPositions` draws the count
   eagerly, then emits lazily (`RepeatingPlacement.java:12-13`,
   `CountPlacement.java:27-28`). `InSquarePlacement` draws x and z when called
   for each emitted element (`InSquarePlacement.java:17-20`). The real sequence
   is therefore `count, [x, z, filters..., feature draws], [x, z, ..., feature
   draws], ...`, **not** "all positions, then all placements". A port that
   collects positions into a list first shifts every draw after the first
   placement.
2. **Structure seeds collide with feature seeds by design.** Structures in
   step s use `setFeatureSeed(decorationSeed, index, s)` with `index` counting
   every structure in the step (`ChunkGenerator.java:349, 364`); features use
   `setFeatureSeed(decorationSeed, globalIndexOfFeature, s)` (`:391`). Structure
   #0 and feature #0 in the same step get the same seed. Keep it.
3. **`index` counts absent structures.** Skipping structures with no starts in
   this chunk, or filtering by `shouldGenerateStructures` differently, changes
   every later structure's seed. Note the whole structure block is skipped
   when `shouldGenerateStructures()` is false (`:347`).
4. **Multiple starts of one structure share one random, not reseeded.**
   `startsForStructure(...).forEach(start -> start.placeInChunk(..., random, ...))`
   (`ChunkGenerator.java:356-357`), and inside, every intersecting piece shares
   it (`StructureStart.java:93-99`). Order = `LongOpenHashSet` iteration order
   of the chunk's references (`StructureManager.java:65-85`). Generation inserts
   in a fixed sourceX/sourceZ order (`ChunkGenerator.java:614-621`), so this is
   stable for generated chunks; for chunks whose references were reloaded
   from disk (`ChunkAccess.setAllReferences`, `:241-244`), the insertion order
   comes from the save data. Not verified whether that matches.
5. **Weighted structure selection draws on failures.** The `while
   (!options.isEmpty())` loop draws `nextInt(total)` again after each failed
   `tryGenerateStructure` and shrinks `total` (`ChunkGenerator.java:521-552`).
   Same pattern as spawn leg 2.
6. **Carver draws before the reachability cull.** `random.nextInt(4)` is drawn
   before `canReach` returns (`CaveWorldCarver.java:210-213`). A "skip tunnels
   that can't reach this chunk" optimization must still consume the full
   draw sequence, or cache the fully drawn tunnel path.
7. **Carver `index` is per source biome's carver list,** and the seed is
   `seed + index`, not a hash (`NoiseBasedChunkGenerator.java:342-351`).
   Dropping or reordering a carver in the list shifts later carvers.
8. **Two RNG algorithms, not interchangeable.** Structures, carvers and spawn
   use `LegacyRandomSource`; features use `XoroshiroRandomSource`
   (`ChunkGenerator.java:328` vs `:513`, `NoiseBasedChunkGenerator.java:321, 502`).
   `WorldgenRandom.next` routes differently per backend (`WorldgenRandom.java:30-35`).
9. **Decoration seed takes block coordinates.** `setDecorationSeed(seed,
   origin.getX(), origin.getZ())` with `origin` = chunk min block
   (`ChunkGenerator.java:323-329`). Spawn does the same with
   `getMinBlockX/Z` (`NoiseBasedChunkGenerator.java:503`). Passing chunk coords
   compiles and silently changes every feature.
10. **`setDecorationSeed` and `setLargeFeatureSeed` differ by one operator**:
    `x*a + z*b ^ seed` with `|1L` scales (`WorldgenRandom.java:46-48`) vs
    `x*a ^ z*b ^ seed` (`:60-62`). Java precedence makes the first one
    `(x*a + z*b) ^ seed`.

Ordering and structural traps:

11. **Structure pieces go before features within each step, and steps
    interleave the two.** Collapsing to "all structures, then all features"
    reorders block writes in overlapping positions (`ChunkGenerator.java:345-402`).
12. **Feature order is the global sorted index, not the biome's list order.**
    Indices come from the global `FeatureSorter` mapping and are sorted
    ascending (`ChunkGenerator.java:369-389`). The set is the union over the
    3x3 chunks' biomes, so a chunk may run features its own biome doesn't
    list, filtered later by `BiomeFilter` at the placement position.
13. **FEATURES writes into neighbors (radius 1)** and vanilla's result depends
    on worldgen queue order (section 2). A Kestrel harness comparing chunk
    hashes against vanilla must load chunks in the same order, or compare
    only the pure prefix (through CARVERS), or accept a documented tolerance
    at overlaps. Otherwise it will report "divergence" that vanilla has
    against itself. Same flavor as the collision-stress tick-0-5 settling
    window.
14. **Heightmap priming happens at the start of FEATURES** (`ChunkStatusTasks.java:137`),
    after carvers changed blocks. NOISE maintains only the `_WG` heightmaps.
    Features that read `MOTION_BLOCKING` etc. depend on that priming point.
15. **SURFACE only rewrites blocks still equal to `defaultBlock` by identity**
    (`old == this.defaultBlock`, `SurfaceSystem.java:153`), and CARVERS runs after
    SURFACE, patching grass tops itself (`WorldCarver.java:153-163`). Swapping
    SURFACE and CARVERS changes the terrain.
16. **The CarvingMask is shared across all carvers for the chunk** and the
    first carver to claim a block wins (`WorldCarver.java:107-108`). Carver
    iteration order (dx outer, dz inner, then carver index) is load-bearing.
17. **One NoiseChunk (and aquifer memo) spans BIOMES -> CARVERS.** Carvers call
    `aquifer.computeSubstance` against the same cached aquifer used in NOISE
    (`NoiseBasedChunkGenerator.java:324-325`, `WorldCarver.java:172`). A port
    that rebuilds the NoiseChunk per stage should be value-identical, since the
    memos store deterministic values (HYPOTHESIS), but it pays the construction cost again.

Possible vanilla-internal nondeterminism (worth knowing before trusting a
determinism check on real terrain; **not verified to change output**):

18. **Beardifier contribution order follows HashMap-of-Structure iteration.**
    `startsForStructure(ChunkPos, Predicate)` iterates
    `getAllReferences().entrySet()` (`StructureManager.java:51-63`), a
    `Maps.newHashMap()` keyed by `Structure` (`ChunkAccess.java:80`). `Structure`
    declares no `hashCode`/`equals` (grep of `Structure.java`: none), so key
    order follows identity hash codes, which vary per JVM run. `Beardifier.compute`
    sums doubles in that order (`Beardifier.java:126-158`). When two or more
    terrain-adapting structures touch one chunk, the floating-point sum order
    can differ between runs, which could flip a block at the density threshold.
    Whether that ever happens in practice is untested.
19. **Spawn-stage entities** use `NaturalSpawner` (not read). Per
    `collision-path-analysis.md`, `Entity.random` is `RandomSource.create()`
    without a seed, so any entity-side randomness after construction is not
    seed-reproducible. Exclude worldgen entities from parity hashes until
    `NaturalSpawner.spawnMobsForChunkGeneration` has been read.

Port-shape traps for `core/` (per `core-block-model-design.md`: embed
vanilla, drive it, don't reimplement):

20. **Worldgen registries are data-driven, not bootstrap-registered.** Biomes,
    carvers and placed features ship as JSON under `data/minecraft/worldgen/`
    in the jar (e.g. `worldgen/biome/plains.json`, 263 `placed_feature` entries).
    `RandomState.create` needs `NoiseGeneratorSettings` and a `NoiseParameters`
    `HolderGetter` (`RandomState.java:34-40`). Expect `Bootstrap.bootStrap()`
    alone to be insufficient (HYPOTHESIS, `RegistryDataLoader` not read),
    matching the "Bootstrap binds no tags" finding in the block-model doc.
21. **`WorldGenRegion` and `StructureManager` are welded to `ServerLevel`.**
    `WorldGenRegion`'s constructor pulls `level.getChunkSource().randomState()`
    (`WorldGenRegion.java:84-97`); `StructureManager.forWorldGenRegion` throws
    unless `region.getLevel()` is the manager's level (`StructureManager.java:43-48`).
    The stages that do **not** need a region are the ones with pure inputs:
    `createBiomes`/`fillFromNoise` take `(Blender, RandomState, StructureManager,
    ChunkAccess)` (`NoiseBasedChunkGenerator.java:88, 359`), and `Blender.empty()`
    is exact for fresh worlds (`Blender.java:60-61`). So a standalone
    Kestrel noise/biome driver is shaped like the vanilla API. Surface,
    carvers and features need a `WorldGenRegion`-like view.
22. **`fillFromNoise` acquires section locks** (`NoiseBasedChunkGenerator.java:371-382`).
    A driver that reuses sections across threads must keep that.
23. **`iterateNoiseColumn` and `doFill` walk Y top-down** and stop
    interpolation explicitly (`NoiseBasedChunkGenerator.java:243-267, 416-466`).
    `NoiseChunk` asserts single interpolation (`NoiseChunk.java:261-263`). Don't
    reuse a NoiseChunk for concurrent column probes.

## 5. Stopwatch script: what to instrument first

Ordered cheapest-to-richest. Each step answers a question the next one needs.

1. **Zero-code baseline: vanilla's own JFR events.** `ChunkStep.apply` wraps
   every generation step in `JvmProfiler.INSTANCE.onChunkGenerate(pos,
   dimension, status)` (`ChunkStep.java:23-24`), which emits
   `minecraft.ChunkGeneration` with `chunkPosX/Z` and `status` fields
   (`ChunkGenerationEvent.java:14-48`; commit on completion,
   `JfrProfiler.java:285-293`). `Structure.generate` emits
   `minecraft.StructureGeneration` (`Structure.java:97`). Both are in
   `CUSTOM_EVENTS` and get enabled when Mojang's profiler starts
   (`JfrProfiler.java:62-73, 176`). Start via the `--jfrProfile` server option
   (string present in `server/Main.class`) or `/jfr start` / `/jfr stop`
   (`JfrCommand.java:31-34`). Parse the `.jfr` with `jfr print --events
   minecraft.ChunkGeneration` and aggregate duration by `status`.
   **Caveat that decides how to read it:** the event begins before
   `task.doWork` and commits in `thenApply` (`ChunkStep.java:23-24`). For the
   async stages (BIOMES, NOISE on the background pool; INITIALIZE_LIGHT/LIGHT
   on the light queue; FULL on the main thread) the duration **includes queue
   wait**, not just CPU. Only the inline stages (STRUCTURE_STARTS,
   STRUCTURE_REFERENCES, SURFACE, CARVERS, FEATURES, SPAWN) are close to true
   work time. Record that split in the output, or step 2 will be misread.
2. **Separate work from wait for the async stages.** Instrument inside the
   supplier lambdas: `NoiseBasedChunkGenerator.doCreateBiomes` (`:97`),
   `NoiseBasedChunkGenerator.doFill` (`:387`), and the PRE_UPDATE runnables in
   `ThreadedLevelLightEngine.initializeLight`/`lightChunk` (`:146, :167`).
   Compared with step 1, this measures queue latency vs CPU for each stage,
   which tests the "single worldgen executor is the bottleneck" HYPOTHESIS (section 3).
3. **Worldgen dispatcher occupancy.** Time spent inside
   `ChunkGenerationTask.runUntilWait` per call (`ChunkGenerationTask.java:43`),
   plus queue depth (`ChunkTaskDispatcher.hasWork`, `:33-35`). If the worldgen
   consecutive executor is busy close to 100% of the time while background
   threads idle, the inline stages are the ceiling.
4. **Split FEATURES.** Timers around the structure block (`ChunkGenerator.java:347-366`)
   vs the feature block (`:368-402`), and per `stepIndex`. Optionally top-N
   by feature key using the `currentlyGenerating` supplier the loop already
   builds (`:390`). Tells whether ores, vegetation, or structure pieces dominate.
5. **Split NOISE internals** only if step 2 shows NOISE dominates: time
   `initializeForFirstCellX`/`advanceCellX` (slice fills, `NoiseChunk.java:260-273`)
   vs `selectCellYZ` (cell caches, `:305-317`) vs the per-block inner loop
   (`NoiseBasedChunkGenerator.java:431-457`). Per-block timers will distort
   this loop badly; use cumulative nanoTime per cellX slice, or a sampling
   profiler instead.
6. **Carver replay count.** Count `isStartChunk == true` hits and
   `carveEllipsoid` calls that pass the range check (`WorldCarver.java:81`) per
   target chunk. The ratio of tunnels walked vs ellipsoids actually carved
   sizes the tunnel-path cache idea (section 3, item 5) without timing anything.
7. **Structure height-probe count.** Count
   `NoiseBasedChunkGenerator.iterateNoiseColumn` calls per STRUCTURE_STARTS
   (`:195`). This tests the "spiky starts" HYPOTHESIS.

Benchmark hygiene for all of the above: pregenerate with a fixed seed and a
fixed, scripted load order (e.g. spiral from a known center with no player
movement), and report per-status medians and p95s over a large chunk count.
Given trap 13, a moving player changes which neighbor-dependent work runs
when, even with the same seed.
