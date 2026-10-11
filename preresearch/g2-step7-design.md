# G2 step 7: FEATURES serial replay (structures off)

Code: `core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java`
(`features`, `RegionView`, `surfaceVanilla`, `carveVanilla`), probe
`FeaturesCheck.java`. Everything below is javap-cited against the 26.2 inner
server jar (`ChunkGenerator.applyBiomeDecoration`,
`ChunkStatusTasks.generateFeatures`, `WorldGenRegion`, `ProtoChunk`,
`NoiseBasedChunkGenerator.applyCarvers`).

## 1. generateFeatures equivalent, in order

1. `Heightmap.primeHeightmaps(center, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES,
   OCEAN_FLOOR, WORLD_SURFACE)` after carvers (trap 14). Neighbours are not
   primed; `ProtoChunk.getHeight` primes a missing map lazily on first read.
2. Chunk status: every prefix chunk is `CARVERS` while it is decorated and
   `FEATURES` afterwards. This matters: `ProtoChunk.setBlockState` updates
   `getPersistedStatus().heightmapsAfter()`, which is FINAL for CARVERS and
   later, WORLDGEN before.
3. `WorldgenRandom(XoroshiroRandomSource)` (trap 8),
   `decorationSeed = setDecorationSeed(worldSeed, origin.x, origin.z)` with
   `origin = SectionPos.of(chunkPos, minSectionY).origin()` (block coords,
   traps 9, 10).
4. Possible biomes: biome palettes of the 3x3 chunks (`ObjectArraySet`), then
   `retainAll(biomeSource.possibleBiomes())`.
5. `for stepIndex < max(Decoration.values().length, featuresPerStep.size())`:
   structure block first (skipped: `shouldGenerateStructures()` is false; step
   8 adds it with the absent-structure index, traps 2, 3, 11), then the union of
   per-biome global indices from `FeatureSorter.StepFeatureData.indexMapping`,
   `Arrays.sort`ed (trap 12), each `setFeatureSeed(decorationSeed, index, step)`
   then vanilla `placeWithBiomeCheck` (trap 1: the lazy placement stream is
   never ported).
6. `Blender.generateBorderTicks` is ticks only; skipped (D7).

`featuresPerStep` is the generator's private memoized Supplier, read by
reflection so the global index is vanilla's own. The optional key filter
(`features(driver, chunk, view, filter)`) seeds before testing, so a skipped
feature cannot shift a later seed. Top-20 mode is that filter.

## 2. Radius-1 write view (`RegionView`)

A `WorldGenLevel` dynamic proxy over a chunk map (3x3 required, bigger allowed
for reads). Mirrors `WorldGenRegion`: `ensureCanWrite` = chessboard distance
<= 1 from center, out-of-zone writes return false silently (D8); `setBlock`
writes `chunk.setBlockState`, DUMMY block-entity NBT on proto chunks, marks
post-process positions unless flag 16; `getHeight(type,x,z)` is chunk height
+1; BiomeManager over the view with `obfuscateSeed(worldSeed)`, falling back
to the biome source outside the map (`getUncachedNoiseBiome`); random is
`randomState.getOrCreateRandomFactory("worldgen_region_random").at(center)`;
ticks go to per-chunk `WorldGenTickAccess`; `getGameTime` answers 0 (ticks
only). Unimplemented abstract methods throw by name. Ores bypass the view via
`BulkSectionAccess` (sections directly), exactly as vanilla.

## 3. RNG per stage

| Stage | Source | Seeding |
|---|---|---|
| CARVERS | Legacy | `setLargeFeatureSeed(seed + carverIndex, startX, startZ)` |
| FEATURES | Xoroshiro | `setDecorationSeed` then `setFeatureSeed(dec, globalIndex, step)` |
| structures (step 8) | Legacy starts; features-random for pieces | `setFeatureSeed(dec, structureIndex, step)` |

## 4. Prefix fixes found on the way (additive methods)

- `carveVanilla`: `applyCarvers` carves the CENTER chunk (local 7) for every
  start in -8..8; the neighbour is only a carver-biome lookup, which is the
  biome SOURCE at quart (minX>>2, 0, minZ>>2). The committed `carve()` carves
  into the neighbour and zooms the lookup. +0.6 pp on chunk (0,0).
- `surfaceVanilla`: region BiomeManager seed `obfuscateSeed(seed)` instead of
  0L. No cell change on chunk (0,0); kept for correctness.

## 5. Measured (Pi, JDK 25, seed 4242424242, oracle `worldflat/r.0.0.mca`)

| Chunk (0,0) | same / 98304 |
|---|---|
| noise+surface (committed) | 91052 (92.62%) |
| + carveVanilla | 91627 (93.21%) |
| + features, 3x3 row-major | 97925 (99.61%) |
| + features, top-20 keys only, row-major | 97925 (99.61%) |
| + features, 3x3 column-major (x outer) | 97988 (99.68%) |
| diag: column-major + leaf distance fixpoint | 98304 (100.00%) |

Chunk (5,7): 92.14% to 99.85% (99.96% with the leaf fixpoint; the last 33 cells
are `water -> air`, likely fluid ticks after FULL). Other ring orders
(center-first/last, reversed) score 98.7-99.3%: trap 13 is real and large.

## 6. Remainder (follow-ups, not this step)

- Leaf `distance` (316 cells on (0,0)): the oracle ran `LeavesBlock` ticks
  after FULL. `FeaturesCheck.settleLeaves` is a diagnostic only; real parity
  needs the tick stage (step 9) or a documented settle.
- Recorded vanilla FEATURES order (tier R, D1); column-major is best of five
  guesses on two chunks, not evidence of vanilla's order.
- Cross-order P3 rate at N >= 1000; all stepIndex coverage is already exercised
  (filter = null) but only verified on two chunks.
- Fluid ticks (`water -> air`), structures (step 8), block entities (DUMMY NBT).
