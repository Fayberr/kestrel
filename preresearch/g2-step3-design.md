# G2 step 3 design: headless worldgen registries + RandomState, first fillFromNoise

Step 3 = load the worldgen registries without a server, build a RandomState,
and run vanilla's own BIOMES + NOISE stages on a ProtoChunk inside Kestrel.
Step 4 (the full NOISE handoff) reuses this driver; step 3 is the smaller
first slice. All javap citations below are against
`preresearch/vanilla-26.2/server.jar`, outer bundler; game classes live in
the inner `META-INF/versions/26.2/server-26.2.jar` (7445 classes). javap runs
against the inner jar path (see vanilla-26.2-fetch.md for the layout).

## 1. Minimal call sequence

Boot first. `net.minecraft.server.Bootstrap.bootStrap()` (static, no args;
javap `net.minecraft.server.Bootstrap`) registers blocks, items and the
built-in registries. Alone it is NOT enough for worldgen: biomes, noise
settings and placed features ship as JSON under `data/minecraft/worldgen/`
in the jar (263 `placed_feature` entries), and trap 20 (chunkgen
path-analysis section 4) expects bootstrap-alone to leave the worldgen
registries empty. So after bootstrap:

1. Load worldgen registries data-driven via
   `net.minecraft.resources.RegistryDataLoader` (javap:
   `WORLDGEN_REGISTRIES` list plus static `load(ResourceManager,
   List<RegistryLookup>, List<RegistryData>, Executor)` returning
   `CompletableFuture<RegistryAccess$Frozen>`). Feed it the server jar's
   `data/` tree as the ResourceManager, the `WORLDGEN_REGISTRIES` list, and
   a same-thread executor for determinism. The `Frozen` registry access is
   the `HolderGetter.Provider` that `RandomState.create` wants.
2. Resolve the overworld noise settings key from the loaded access, then
   `RandomState.create(HolderGetter$Provider, ResourceKey<NoiseGeneratorSettings>,
   long seed)` (javap `net.minecraft.world.level.levelgen.RandomState`:
   two `create` overloads plus the private constructor; seed =
   4242424242 for corpus parity). Keep the returned `RandomState`: it owns
   the `NoiseRouter`, the `Climate.Sampler`, the `SurfaceSystem`, the
   aquifer/ore random factories and the noise-instance memo map
   (`noiseIntances`). One RandomState per seed, shared across chunks on one
   thread (step 5 forks per-thread state later; do NOT share one across
   threads yet, trap 22/23).
3. Build the generator: `new NoiseBasedChunkGenerator(BiomeSource,
   Holder<NoiseGeneratorSettings>)` (javap
   `net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator`: public
   ctor takes exactly those two). The BiomeSource comes from the loaded
   registries (multi-noise source over the biome holders); the settings
   holder from the same access.
4. Build the chunk: `new ProtoChunk(ChunkPos, UpgradeData,
   LevelHeightAccessor, PalettedContainerFactory, BlendingData)` (javap
   `net.minecraft.world.level.chunk.ProtoChunk`: two public ctors, the
   short one is enough for fresh chunks). Height accessor = overworld
   dimensions (minY -64, height 384); blending data null-equivalent for
   fresh worlds (see Blender below).
5. Build the structure manager stub: `new
   StructureManager(LevelAccessor, WorldOptions, StructureCheck)` (javap
   `net.minecraft.world.level.StructureManager`: public 3-arg ctor).
   Structures are OFF for step 3, so the stub only needs
   `shouldGenerateStructures()` false and `startsForStructure` yielding
   nothing. Do NOT use `forWorldGenRegion` (it throws unless the region's
   level is the manager's level, trap 21); construct the stub directly.
   The `LevelAccessor` behind it can be a minimal fake: `fillFromNoise`
   never touches it (signature takes ChunkAccess, not level).
6. Run BIOMES: `generator.createBiomes(randomState, Blender.empty(),
   structureManager, protoChunk)` (javap: public, returns
   `CompletableFuture<ChunkAccess>`; private worker is `doCreateBiomes`
   with the same four args in Blender-first order). `Blender.empty()`
   is the exact choice for fresh worlds (javap
   `net.minecraft.world.level.levelgen.blending.Blender`: static
   `empty()`; note the package is `levelgen.blending`, not `levelgen`).
   Join the future on the calling thread.
7. Run NOISE: `generator.fillFromNoise(Blender.empty(), randomState,
   structureManager, protoChunk)` (javap: public, same shape). Private
   worker `doFill` plus `createNoiseChunk` (both in the javap listing)
   build one NoiseChunk per call; keep that NoiseChunk alive through
   step 6's carvers (trap 17: carvers call `aquifer.computeSubstance`
   against the same cached aquifer). For step 3, the future join is the
   end of the line.

Executor note: both stage methods return futures but the work is
calling-thread synchronous in vanilla's flow (the `lambda$fillFromNoise$0`
in the javap listing is the section-write fold). Join immediately; no pool
in step 3. Section locks inside `doFill` (trap 22) stay as vanilla wrote
them; a single-threaded driver never contends.

## 2. Where the output blocks land

`fillFromNoise` writes into the ProtoChunk's `LevelChunkSection[]` via
`setBlockState`. The handoff to Kestrel:

- Walk the ProtoChunk sections bottom-up, read each
  `LevelChunkSection` palette + data, and write through
  `SectionStore.writeCell(sectionKey, cellIndex, state, written=true)`
  (`core/src/vanilla/java/dev/kestrel/loop/SectionStore.java`: `keyFor`,
  `indexFor`, `writeCell`, `writtenBitset`). Section keys are Kestrel-side
  (long), cells are vanilla `BlockState` references, no conversion.
- DENSE materialization. Step 3 writes every section DENSE (the store's
  only kind today). The COMPACT design (g2-memory-design section 2) is the
  step-2b import optimization for *serving* the corpus; generation output
  goes DENSE first because the next stages (surface, carvers, features)
  mutate in place and COMPACT is immutable. Memory verdict for generated
  chunks comes from the step-2 import census, not from this doc.
- P1 cut point: after `fillFromNoise` returns and before any surface work,
  hash every section via `BlockHash.chunkHex(store, chunkX, chunkZ)`
  (`core/src/vanilla/java/dev/kestrel/persist/BlockHash.java`) and compare
  against the oracle `hash-probe.csv` P1 rows (`chunk_x,chunk_z,cut,hash`).
  P2 (post-surface/carvers) and P3 (post-features) belong to steps 6/7.

## 3. HD gate command

```
KestrelServer --import <fullrun-corpus-dir> --spawn --no-save --mem-probe \
  --block-hash-csv hash-probe.csv --hd-gate P1 --chunks 1000 --order shuffled
```

Semantics: import the staged corpus regions, run the step-3 driver over
1000 corpus chunk positions in shuffled order, hash P1 per chunk, exit 0
iff 100% equal (step-1 exit criterion for P1/P2 is 100% across launches
and orders; step 4's exit is P1 100% on 1000+ chunks shuffled). On mismatch
print chunk pos, expected hash, actual hash, and the first differing
section Y. The `--order shuffled` flag exists because trap 13 (features
write into neighbours) makes queue order load-bearing later; P1 is pure
so shuffle must NOT matter, and the flag proves it.

## 4. Complexity and risks

Complexity **6** (matches the build-order estimate; the hypothesis
(`RegistryDataLoader` path) is now confirmed by javap, but the
ResourceManager wiring against the jar's `data/` tree is still unwritten).

1. ResourceManager wiring. `RegistryDataLoader.load` wants a live
   `ResourceManager` over the jar's datapack JSON. No Kestrel code does
   this yet; `net/Datapack.java` reads vanilla datapack JSON but is not
   a ResourceManager. Risk: medium. Fallback: extract `data/minecraft/
   worldgen/` to disk and use a file-backed resource pack.
2. StructureManager stub depth. `fillFromNoise` takes the manager but
   with structures off it should only ask `shouldGenerateStructures()`.
   If the noise path touches `startsForStructure` anywhere (Beardifier
   input), the stub must return empty lists, not null. Risk: low, visible
   on first run as NPE vs green P1.
3. NoiseChunk lifetime. `createNoiseChunk` is private; the driver gets
   its NoiseChunk implicitly per `fillFromNoise` call. Step 3 is fine
   (one call, one chunk). Step 4+ must NOT rebuild per stage (trap 17
   cost + aquifer memo identity). Risk: low now, flagged for step 4.
4. Thread confinement. RandomState memoizes noise instances in a plain
   map (`noiseIntances` field). Step 3 is single-threaded; step 5 must
   fork per-thread state vanilla-style before any pool. Risk: none in
   step 3, must-not-forget in step 5.
5. P1 mismatch triage. If P1 is not 100% on the first run, the prime
   suspects in order: wrong seed passed to `RandomState.create`, wrong
   BiomeSource (fixed-biome vs multi-noise), Blender not empty, height
   accessor mismatched (minY/height). The gate's first-diff-Y output
   discriminates: uniform mismatch = seed/source, topographic mismatch =
   height/blender.

Out of scope: SURFACE + CARVERS (step 6), FEATURES (step 7), structures
(step 8), parallelism (step 5), COMPACT storage (step 2b).
```

