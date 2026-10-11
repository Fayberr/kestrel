# Tier R Residual Differences Diagnosis

> **RESOLVED: 2026-09-19**
>
> This document preserves the historical 575-cell diagnosis below. The fixes were subsequently implemented and the final FEATURES-lighting residual was resolved with a shared Vanilla-compatible LevelLightEngine lifecycle.
>
> Final fail-closed Tier R result on `/tmp/kestrel-features-trace-large-36-v2/trace`: **1,433 / 1,433 scoreable chunks exact, 140,869,632 scored cells, 0 differences, 100.000000% parity**.
>
> The final lighting mechanism was not a constant brightness or direct chunk-status rule. Vanilla `INITIALIZE_LIGHT` registers non-empty sections plus neighbouring light-only sections in one shared light storage; unregistered columns read skylight 15 while registered columns read their DataLayer. `WorldgenLightLifecycle` now carries that frontier and is shared by the replay and live generator.

**Code references as of 2026-09-19.** The line numbers, links and method names in sections 1 to 5 are the ones the diagnosis used, and the worldgen code has moved since; read them as history. Both fixes recommended in section 6 were implemented (status there).

## 1. Executive Summary & Measured Baseline

Following the 3D biome collection fix in [`HeadlessNoise.collectPossibleBiomes`](../core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java#L1179-L1202) (commit `0a6ae1e`), the matched-order Tier R replay (`FeaturesTraceReplay`) on the 36×36 multi-chunk capture (`/tmp/kestrel-features-trace-large-36-v2/trace`) was remeasured directly:

* **Scored Chunks (ring=2 closure)**: 1,433 chunks (out of 1,766 replayed centers)
* **Total Scored Cells**: 140,869,632 cells
* **Exact Parity Chunks**: 1,404 / 1,433 (**97.98%**)
* **Failing Chunks**: 29 / 1,433 (**2.02%**)
* **Differing Cells**: **575 cells** (down from 154,101)
* **Block-State Parity**: **99.999592%**

This investigation was diagnostic-only (no production code modifications were made). Every single one of the 575 differing cells has been traced to its exact root cause, disassembled to the underlying JVM bytecode, and verified with dedicated test probes.

---

## 2. Complete Residual Taxonomy (575 Cells)

Every failing cell falls into one of two fundamental architectural mechanisms:

| Category | Cells | % Total | Affected Chunks | Root Mechanism |
|---|---|---|---|---|
| **1. Unauthorized 2nd Monster Room** | **261** | 45.4% | `(-13,-13)`: 226<br>`(-14,-13)`: 35 | In-feature RNG desync: missing Spawner BlockEntity skips `random.nextInt(4)` draw, allowing attempt 2 to place an extra dungeon. |
| **2. Coastal / Beach Transitions** | **213** | 37.0% | `(-11,15)`: 99<br>`(-2,9)`: 72<br>`(5,18)`: 36<br>`(-1,10)`: 5<br>`(5,7)`: 1 | Surface `BiomeManager` bypasses chunk section palettes, querying raw noise sampler instead of cached climate sampler. |
| **3. Underwater Sea-Floor Gravel** | **44** | 7.7% | `(7,-3)`: 11, `(-2,-1)`: 8, `(-6,-1)`: 6, `(-3,-1)`: 5, `(13,-2)`: 4, `(14,-2)`: 4, `(-3,-2)`: 2, `(-8,0)`: 2, `(-6,-2)`: 1, `(-2,-2)`: 1 | Same surface `BiomeManager` palette bypass on river/ocean floors (evaluates `gravel` fallback instead of `sand`). |
| **4. Surface Dirt vs Stone Slopes** | **43** | 7.5% | `(-2,16)`: 39<br>`(-3,16)`: 4 | Same surface `BiomeManager` palette bypass across chunk borders during 3D Voronoi zoom. |
| **5. Dungeon Chest Placement Shifts** | **10** | 1.7% | `(18,-14)`: 2, `(-9,15)`: 2, `(0,7)`: 1, `(1,7)`: 1, `(1,-7)`: 1, `(4,-4)`: 1, `(3,12)`: 1, `(-7,17)`: 1 | In-feature RNG desync: missing Chest BlockEntity skips `random.nextLong()` loot table seed draw on Chest 1, shifting Chest 2 rolls. |
| **6. Adjacent Surface Cascades** | **4** | 0.7% | `(-1,16)`: 2 (adjacent to `(-2,16)`)<br>`(6,18)`: 2 (adjacent to `(5,18)`) | Features reading neighbor chunk surface blocks that diverged in Category 2/4. |
| **TOTAL** | **575** | **100.0%** | **29 failing chunks** | **100% Accounted For** |

---

## 3. Root Cause 1: Surface `BiomeManager` Bypasses Chunk Section Palettes (304 Cells)

### Mechanism & Bytecode Proof
In vanilla Minecraft [`NoiseBasedChunkGenerator.buildSurface`](../core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java#L563-L572):
> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

In `WorldGenRegion`:
1. `WorldGenRegion.getBiomeManager()` is constructed as `new BiomeManager(this, obfuscateSeed(seed))`.
2. `WorldGenRegion` implements `LevelReader`, whose default `getNoiseBiome(qx, qy, qz)` method is:
   ```java
   public default Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
       ChunkAccess chunk = this.getChunk(QuartPos.toSection(qx), QuartPos.toSection(qz), ChunkStatus.BIOMES, false);
       if (chunk != null) {
           return chunk.getNoiseBiome(qx, qy, qz);
       }
       return this.getUncachedNoiseBiome(qx, qy, qz);
   }
   ```
3. When `SurfaceRules.Context` calls `biomeManager.getBiome(pos)` to determine whether surface blocks should be sand, grass, dirt, or gravel, `BiomeManager` performs a 3D Voronoi zoom sampling an 8-corner cell of noise quarts. In vanilla, every noise quart inside the 3×3 region is read from **`chunk.getNoiseBiome(qx, qy, qz)`**, which directly accesses the `LevelChunkSection`'s `PalettedContainerRO<Holder<Biome>>`.

In Kestrel [`HeadlessNoise.surfaceVanilla`](../core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java#L563-L572) and [`regionBiomes`](../core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java#L700-L705):
```java
private static BiomeManager regionBiomes(Driver driver) {
    return new BiomeManager(
        (qx, qy, qz) -> driver.generator().getBiomeSource().getNoiseBiome(
            qx, qy, qz, driver.randomState().sampler()), // <--- Bypasses chunk section palettes
        BiomeManager.obfuscateSeed(driver.seed()));
}
```

### Why Raw Noise Sampler Differs From Stored Chunk Biomes
When a chunk runs `createBiomes`, vanilla populates the section palettes using:
```java
chunk.fillBiomesFromNoise(resolver, noiseChunk.cachedClimateSampler(router, spawnTarget));
```
`NoiseChunk.cachedClimateSampler` wraps the density functions with the `NoiseChunk` cell interpolator and cache. By contrast, `driver.randomState().sampler()` is an uncached, un-interpolated sampler.

**Direct Empirical Verification (`TestSamplerDifference` & `CompareBiomeManagers`):**
In chunk `(-11, 15)` at quart `(-42, 14, 61)` (corresponding to surface diff cell `(-166, 57, 246)`):
* Stored chunk section palette: `minecraft:beach`
* Vanilla `region.getBiomeManager().getBiome(pos)`: `minecraft:beach` -> places **`sandstone`**
* Kestrel `regionBiomes(driver).getBiome(pos)`: queries raw `randomState.sampler()` -> returns `minecraft:birch_forest` -> places **`stone`**

### Empirical Proof of Elimination
We constructed `.diagnostics/TestSurfaceWithGridBiomes.java` passing a 3×3 grid-backed `BiomeManager` reading `chunk.getNoiseBiome(qx, qy, qz)` on chunks with `ChunkStatus.BIOMES`:

```
Chunk (-11,  15): vanillaCarverDiffs orig= 98 -> withGridBiomes= 0
Chunk ( -2,   9): vanillaCarverDiffs orig= 71 -> withGridBiomes= 0
Chunk (  5,  18): vanillaCarverDiffs orig= 35 -> withGridBiomes= 0
Chunk ( -2,  16): vanillaCarverDiffs orig= 39 -> withGridBiomes= 0
Chunk (  7,  -3): vanillaCarverDiffs orig= 11 -> withGridBiomes= 0
Chunk ( -2,  -1): vanillaCarverDiffs orig=  8 -> withGridBiomes= 0
```
**Every single tested chunk across Categories 2, 3, and 4 drops to EXACTLY ZERO differences.**

---

## 4. Root Cause 2: BlockEntity Absence Desynchronizes In-Feature Dungeon RNG (271 Cells)

### Mechanism & Bytecode Proof
In vanilla Minecraft [`MonsterRoomFeature.place`](../core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java#L794-L806):
Both `minecraft:monster_room` (count = 10) and `minecraft:monster_room_deep` (count = 4) run multiple placement attempts per chunk. All attempts within a feature call share a **single `WorldgenRandom random` instance**, which is seeded once at the start of the feature via `random.setFeatureSeed(decorationSeed, globalIndex, stepIndex)`.

When a dungeon successfully carves a room:
1. **Chest Placement (lines 722–731)**:
   ```java
   RandomizableContainer.setBlockEntityLootTable(level, random, chestPos, BuiltInLootTables.SIMPLE_DUNGEON);
   ```
   Bytecode of `RandomizableContainer.setBlockEntityLootTable`:
   ```bytecode
   0: aload_0  // level
   1: aload_2  // chestPos
   2: invokeinterface BlockGetter.getBlockEntity:(BlockPos)BlockEntity;
   7: astore 4
   9: aload 4
   11: instanceof RandomizableContainer
   14: ifeq 38
   28: invokeinterface RandomSource.nextLong:()J // <--- DRAWS FROM RNG!
   33: invokeinterface setLootTable:(ResourceKey;J)V
   38: return
   ```
2. **Spawner Placement (lines 763–801)**:
   ```bytecode
   766: invokeinterface WorldGenLevel.getBlockEntity:(BlockPos)BlockEntity;
   771: astore 16
   773: aload 16
   775: instanceof SpawnerBlockEntity
   778: ifeq 804
   793: invokevirtual randomEntityId:(RandomSource)EntityType; // <--- DRAWS FROM RNG: random.nextInt(4)!
   798: invokevirtual SpawnerBlockEntity.setEntityId:(EntityType;RandomSource)V
   801: goto 848
   804: LOGGER.error("Failed to fetch mob spawner entity at ({}, {}, {})", ...)
   ```

### The Two Consequences in Kestrel

#### Consequence A: Unauthorized Second Dungeon (Category 1: 261 cells)
In chunk `(-13, -13)`:
* `monster_room_deep` Attempt 1 successfully places a dungeon at `(-202, -14, -196)`.
* In vanilla, the spawner block entity is retrieved and `randomEntityId(random)` draws `random.nextInt(4)`.
* In Kestrel, `level.getBlockEntity` returns `null` (`Failed to fetch mob spawner entity at (-202, -14, -196)`). The RNG draw is **skipped**.
* Because `random` was not advanced, Attempt 2 in Kestrel rolls different coordinates than vanilla. It selects `(-205, -14, -200)`, passes all wall opening checks, and carves an **unauthorized second dungeon**, destroying 261 cells of deepslate into cobblestone, mossy cobblestone, chests, spawner, and cave air across `(-13,-13)` and `(-14,-13)`.
* In vanilla, with the advanced RNG, Attempt 2 tests an invalid location and does not place.

#### Consequence B: Chest Placement Shifts (Category 5: 10 cells)
In single-dungeon chunks with 2 chest attempts (e.g. `(18,-14)` at `(292, -53, -210)`):
* Chest 1 is placed at `(295, -53, -210)`.
* In vanilla, `setBlockEntityLootTable` retrieves the `ChestBlockEntity` and calls `random.nextLong()` to set the loot seed.
* In Kestrel, `level.getBlockEntity` returns `null`, skipping `random.nextLong()`.
* When Chest 2 is rolled:
  * In vanilla: `random` was advanced by `nextLong()`, picking `(294, -53, -210)` (East wall double-chest).
  * In Kestrel: `random` was NOT advanced, picking `(289, -53, -209)` (West wall single-chest).
* Exactly 2 cells differ in `(18,-14)`. The other 8 chunks in Category 5 exhibit the identical 1-chest shift or orientation diff.

---

## 5. What Was Disproven or Ruled Out

1. **"Skipped FossilFeature shifts downstream feature RNG seeds" - DISPROVEN**:
   [`HeadlessNoise.features`](../core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java#L798) calls `random.setFeatureSeed(decorationSeed, globalIndex, stepIndex)` explicitly before each placed feature. Skipping `FossilFeature` leaves zero trace on subsequent feature seeds. What *does* shift RNG is intra-feature attempts sharing the same `random` instance when block entity lookups fail.
2. **"Stage E metadata differences cause block-state parity loss" - DISPROVEN**:
   All 575 block-state differences were present before Stage E (300 diverged at `surfaceVanilla`, 271 diverged at `features`). Stage E serialization/metadata had no impact on block states.
3. **"Vanilla generation order nondeterminism" - DISPROVEN**:
   Replay against vanilla trace order with ring-2 closure is fully deterministic. Even on the exact vanilla pre-decoration snapshot, Kestrel reproduces the dungeon divergence because of the missing block entities.
4. **"Heightmap or preliminary surface noise discrepancy in surface rules" - DISPROVEN**:
   Terrain heights and `WORLD_SURFACE_WG` heightmaps were verified to be bit-for-bit identical between vanilla and Kestrel (e.g. height=63 in both at `(-166, 246)`). The divergence was exclusively the biome returned to the rule.

---

## 6. Recommended Next Actions & Implementation Boundary

When authorization to fix these issues in production worldgen is given:

> **Status 2026-09-25: BOTH ACTIONS IMPLEMENTED.** The SURFACE pipeline now resolves biomes through a palette-backed `BiomeManager` over a prepared grid (`HeadlessNoise.gridBiomes`/`prepareBiomeGrid`, used by `surfaceVanilla`), keeping the old raw-source shape only as the negative control in `SurfaceBiomeGridCheck` ("removed from production"). `RegionView.getBlockEntity` now mirrors `WorldGenRegion` bytecode and lazily promotes the `DUMMY` pending NBT into a real block entity on first read, so the in-feature RNG draws (chest loot-table seed, spawner entity id) execute; `DungeonBlockEntityCheck` is the regression gate, asserting both dungeons named in section 4. The action list below is kept as written.

1. **Surface Biome Manager (fixes 304 cells / 52.9% of residuals)**:
   * Update [`HeadlessNoise.surfaceVanilla`](../core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java#L563-L572) (and any chunk generator surface pipeline) to provide a `BiomeManager` backed by the 3×3 neighborhood's populated section palettes (`chunk.getNoiseBiome`) rather than delegating directly to `source.getNoiseBiome` with raw `randomState.sampler()`.
2. **Headless BlockEntity / RNG Emulation for Decoration (fixes 271 cells / 47.1% of residuals)**:
   * In [`HeadlessNoise.RegionView`](../core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java), ensure `level.getBlockEntity(pos)` either:
     * Instantiates minimal block entities for containers/spawners during decoration, OR
     * Emulates the necessary RNG draws (`random.nextLong()` on `RandomizableContainer` placement, `random.nextInt(4)` on spawner placement) so intra-feature RNG remains synchronized with vanilla.

**Parity Gate Compliance**: Neither fix relaxes any gates, excludes block families, or hardcodes chunk coordinates. Both align Kestrel's execution pipeline with vanilla Minecraft's exact contracts.
