# G30 Pre-Research: Random Ticks and Environmental Simulation Specification

Status: PRE-RESEARCH, grounded in the decompiled vanilla 26.2 server jar (`server-26.2.jar`) in `~/kestrel-run/vanilla-src-26.2`, and existing Kestrel implementations in `core/src/vanilla/java/dev/kestrel/`.
Target: Ground truth specification and architecture roadmap for roadmap item G30 (random ticks) in Kestrel.
Scope: Architectural analysis, vanilla code audits with exact file:line citations, chunk selection and iteration algebra, RandomSource draw sequences, dynamic section filtering, block and fluid random tick mechanics, every 26.2 randomly ticking block family, precipitation and weather integration, scheduled tick distinctions (fire spread vs lava ignition), current Kestrel audit, dependency sequencing, and vertical slice plans with vanilla-oracle gates and concrete mutants. Plain markdown only: no em-dash or en-dash characters, no builds, no servers, no Java source changes.

---

## 1. Executive Summary and Scope

### 1.1 Scope Boundary
Roadmap item G30 establishes the complete chunk-level random tick and environmental update subsystem in Kestrel:
- Chunk random tick loop driver conforming to `ServerChunkCache.tickChunks` (`ServerChunkCache.java:374-415`) and `ServerLevel.tickChunk` (`ServerLevel.java:496-539`).
- Chunk selection and iteration filtering over active ticking chunks via `DistanceManager` and `ChunkMap` (`ChunkMap.java:960-970`, `DistanceManager.java:177-188`).
- Strict adherence to the `randomTickSpeed` game rule (`GameRules.RANDOM_TICK_SPEED`, default 3).
- Per-section block position selection using Minecraft's canonical Linear Congruential Generator (LCG) math (`Level.getBlockRandomPos`, `Level.java:1067-1071`).
- RNG stream separation: position sampling via the internal LCG state (`Level.randValue`, `Level.java:116`) vs block and fluid simulation logic driven by `ServerLevel.random` (`LegacyRandomSource`, `Level.java:122`).
- Section-level ticking filters (`LevelChunkSection.isRandomlyTicking`, `LevelChunkSection.java:111-121`), gating RNG consumption and block lookups on `tickingBlockCount > 0 || tickingFluidCount > 0`.
- Block-level dynamic ticking filters (`BlockBehaviour.BlockStateBase.isRandomlyTicking`, `BlockBehaviour.java:871-873`), evaluating static properties or state properties (such as leaf distance, crop age, copper oxidation state, and ore illumination).
- Fluid random tick dispatch (`FluidState.isRandomlyTicking`, `FluidState.java:87-89`, `LavaFluid.java:81-116`), where lava is the only randomly ticking fluid in 26.2.
- Environmental precipitation in the chunk loop (`ServerLevel.tickPrecipitation`, `ServerLevel.java:578-608`): biome surface freezing to ice (`Biome.shouldFreeze`), snow layer accumulation (`Biome.shouldSnow`, `SnowLayerBlock.LAYERS`), entity displacement (`Block.pushEntitiesUp`), and cauldron filling (`CauldronBlock.java:42-52`, `LayeredCauldronBlock.java:116-122`).
- Atmospheric lightning and thunder hooks (`ServerLevel.tickThunder`, `ServerLevel.java:541-575`): skeleton trap generation, lightning rod targeting, and entity spawning.
- Comprehensive implementation of every randomly ticking block family in Minecraft 26.2:
  - Crops (`CropBlock`, `BeetrootBlock`, `TorchflowerCropBlock`, `PitcherCropBlock`): light levels, hydration speed multipliers, crowding penalties, double-plant expansions.
  - Stems (`StemBlock`, `AttachedStemBlock`): pumpkin and melon fruit attachment, horizontal direction sampling, attached stem reversion on harvest.
  - Saplings and Propagules (`SaplingBlock`, `MangrovePropaguleBlock`, `TreeGrower`): stage cycles, 2x2 mega trees, configured feature generation in live worlds, failure rollbacks.
  - Soil spread and decay (`SpreadingSnowyBlock`, `GrassBlock`, `MyceliumBlock`, `NyliumBlock`): light dampening mortality, snow coverage preservation, 4-attempt coordinate walks.
  - Leaf decay (`LeavesBlock`): distance 1-7 decay pipeline, persistent tag immunity, neighbor update shape scheduling vs random tick decay.
  - Thermal phase changes (`IceBlock`, `SnowLayerBlock`): block light vs sky light boundaries (block light > 11), evaporation in arid/Nether dimensions.
  - Columnar plants (`CactusBlock`, `SugarCaneBlock`, `BambooStalkBlock`, `BambooSaplingBlock`, `GrowingPlantHeadBlock`): height limits, age increments, cactus flowers, bamboo stalk leaf stages.
  - Weathering copper (`WeatheringCopper`, `ChangeOverTimeBlock`): daily chance `0.05688889F`, Manhattan distance 4 scan, younger neighbor suppression rule, older neighbor probability acceleration.
  - Combustion mechanics (`FireBlock`, `LavaFluid`): scheduled tick spread versus random tick ignition, burn-out math, flammability ratings, rain quenching.
  - Farmland moisture (`FarmlandBlock`): 9x2x9 water bounding box scan, rain hydration, moisture dehydration, trampling and decay to dirt.
  - Specialized 26.2 blocks: Sweet Berry Bush, Cocoa, Nether Wart, Chorus Flower, Budding Amethyst, Turtle Egg, Mushroom, Pointed Dripstone / Speleothem / Sulfur Spike, Eyeblossom, Dried Ghast, Nether Portal, Redstone Ore, Vine, and Potted Eyeblossom.

### 1.2 Relationship to Other Roadmap Items
G30 provides the world's autonomous living simulation, interacting with several core engine milestones:
- **S16 (Chunk Tickets & Simulation Distance):** S16 manages chunk ticket levels (`ENTITY_TICKING` <= 31, `BLOCK_TICKING` <= 32). In vanilla, `ServerChunkCache.tickChunks` iterates only chunks inside `inEntityTickingRange` for block random ticks (`ChunkLevel.java:59-61`, `DistanceManager.java:177-188`). Before S16 lands, G30 operates over all in-memory chunks or chunks within player simulation distance.
- **S14 (Entity System):** Required for entities spawned by random ticks: items dropped by leaf decay (`LeavesBlock.java:69`), turtles hatched from eggs (`TurtleEggBlock.java:109-114`), zombified piglins from Nether portals (`NetherPortalBlock.java:80-87`), and lightning bolts / skeleton horses (`ServerLevel.java:555-571`).
- **G66 (Block Updates & Live setBlock Path):** Random tick block state changes must execute through Kestrel's live write pipeline (`VanillaRedstone.setBlock`), firing shape updates (flags 2 or 3) and neighbor cascades so surrounding blocks react immediately.
- **G11 (Fluids):** Lava random ticking (`LavaFluid.randomTick`) places fire blocks in adjacent air cells. Water freezing (`Biome.shouldFreeze`) converts flowing or source water into ice blocks.
- **G12 (Redstone):** Observer blocks detect block state changes triggered by random ticks (e.g. crop growth, redstone ore turning off). Comparators read analog signal updates from cauldrons filling with precipitation (`LayeredCauldronBlock.java:125-127`).
- **G3 (Weather Cycle):** Precipitation ticking (`ServerLevel.tickPrecipitation`) depends directly on `level.isRaining()`, rain timers, and biome precipitation classification.
- **G50 (Lightning):** Atmospheric thunder ticks (`ServerLevel.tickThunder`) depend on rain, thunder level, and lightning entity dispatch.
- **G19 (Block Drops):** Leaf decay and trampled farmland drop items through block loot tables and item entity physics.

---

## 2. 26.2 Chunk Ticking and Random Tick Loop Architecture

### 2.1 Chunk Selection and Iteration Algebra
In vanilla 26.2, chunk simulation is driven by `ServerChunkCache.tickChunks` (`ServerChunkCache.java:374-415`):
> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

The chunk iteration proceeds through two distinct lists:
1. **Spawning Chunks (`spawningChunks`):**
   - Populated by `ChunkMap.collectSpawningChunks` (`ChunkMap.java:943-958`), which filters `visibleChunkMap` entries by `anyPlayerCloseEnoughForSpawningInternal` (Chebyshev distance <= 8 chunks from any player).
   - Shuffled using `Util.shuffle(spawningChunks, this.level.getRandom())` (`ServerChunkCache.java:397`).
   - Each chunk receives `tickSpawningChunk` (`ServerChunkCache.java:417-425`), which increments chunk inhabited time (`chunk.incrementInhabitedTime(timeDiff)`) and runs `tickThunder(chunk)` if `distanceManager.inEntityTickingRange(chunkPos.pack())`.
2. **Block Ticking Chunks (`forEachBlockTickingChunk`):**
   - Implemented in `ChunkMap.java:960-970`:
     > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
   - Delegates directly to `DistanceManager.forEachEntityTickingChunk` (`DistanceManager.java:177-188`):
     > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
   - Invariant: A chunk must have ticket level <= 31 (`ChunkLevel.isEntityTicking`, `ChunkLevel.java:59-61`) in `simulationChunkTracker` to receive random ticks.
   - Note on iteration order: `simulationChunkTracker.chunks` is a fastutil `Long2ByteOpenHashMap`. The traversal order is raw hash-table order (determined by table capacity and long key hashing), NOT spatial coordinate order.

### 2.2 The randomTickSpeed Game Rule
The integer game rule `GameRules.RANDOM_TICK_SPEED` governs tick frequency:
- Registered default value: `3` (`GameRules.java`).
- Read on the tick thread once per chunk tick pass: `int tickSpeed = this.level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);` (`ServerChunkCache.java:382`).
- If `tickSpeed <= 0`, block and fluid random ticks are completely bypassed:
  ```java
  // ServerLevel.java:510
  if (tickSpeed > 0) { ... }
  ```
- If `tickSpeed > 0`, the loop executes exactly `tickSpeed` iterations per section for block/fluid ticking, and `tickSpeed` attempts for precipitation.

### 2.3 Per-Section Position Draws and LCG Math
For every random tick draw, coordinates within a section are sampled using a dedicated 32-bit Linear Congruential Generator.
In `Level.java:1067-1071`:
```java
public BlockPos getBlockRandomPos(final int xo, final int yo, final int zo, final int yMask) {
   this.randValue = this.randValue * 3 + 1013904223;
   int val = this.randValue >> 2;
   return new BlockPos(xo + (val & 15), yo + (val >> 16 & yMask), zo + (val >> 8 & 15));
}
```

The generator details:
1. `randValue` state is stored as a protected field on `Level` (`Level.java:116`):
   ```java
   protected int randValue = RandomSource.createThreadLocalInstance().nextInt();
   ```
2. Linear recurrence: `randValue' = (randValue * 3 + 1013904223) & 0xFFFFFFFF`.
3. Bit extraction from `val = randValue >> 2`:
   - X offset: `val & 15` (bits 2 to 5 of `randValue`, range 0..15).
   - Z offset: `(val >> 8) & 15` (bits 10 to 13 of `randValue`, range 0..15).
   - Y offset: `(val >> 16) & yMask` (bits 18 to 21 when `yMask = 15`, range 0..15).
4. Section coordinate alignment:
   In `ServerLevel.tickChunk` (`ServerLevel.java:516-520`):
   - `int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);`
   - `int minYInSection = SectionPos.sectionToBlockCoord(sectionY);` (i.e. `sectionY << 4`).
   - `BlockPos pos = this.getBlockRandomPos(minX, minYInSection, minZ, 15);`
   - Section-local block retrieval: `section.getBlockState(pos.getX() - minX, pos.getY() - minYInSection, pos.getZ() - minZ);`
   - This directly accesses `val & 15`, `(val >> 16) & 15`, and `(val >> 8) & 15` within the 4096-element palette container.

### 2.4 RandomSource Architecture: Coordinate LCG vs Block RNG
Minecraft 26.2 maintains a strict separation between coordinate generation and simulation logic:
1. **Coordinate LCG (`Level.randValue`):** An integer field mutated exactly once per call to `getBlockRandomPos`. It does NOT touch `RandomSource` and does NOT alter the seed/state of game randoms.
2. **Simulation RNG (`Level.random` / `ServerLevel.random`):**
   - An instance of `LegacyRandomSource` (`RandomSource.create()`, `Level.java:122`) wrapping Java's 48-bit LCG (`multiplier = 0x5DEECE66DL`, `addend = 0xBL`, `mask = (1L << 48) - 1`).
   - Passed directly into `blockState.randomTick(this, pos, this.random)` and `fluidState.randomTick(this, pos, this.random)`.
   - Used for all probabilistic decisions inside block implementations (e.g. crop growth roll `random.nextInt(...)`, copper oxidation rolls, leaf decay checks).

Sequence of draws during `ServerLevel.tickChunk` (`ServerLevel.java:496-539`):
1. **Precipitation Phase:**
   - Loop `i` from 0 to `tickSpeed - 1`:
     - Draw 1: `this.random.nextInt(48)` from `ServerLevel.random`.
     - If outcome == 0:
       - Step coordinate LCG: `this.getBlockRandomPos(minX, 0, minZ, 15)` advances `randValue`.
       - Execute `tickPrecipitation`: may draw from `level.getRandom()` (cauldrons) or modify blocks.
2. **Section Random Tick Phase:**
   - Iterate section indices from 0 to `sections.length - 1`:
     - Test `section.isRandomlyTicking()`. If false, NOTHING is drawn: no LCG advance, no RNG draws.
     - If true:
       - Loop `ix` from 0 to `tickSpeed - 1`:
         - Step coordinate LCG: `this.getBlockRandomPos(minX, minYInSection, minZ, 15)` advances `randValue`.
         - Lookup block state: `section.getBlockState(lx, ly, lz)`.
         - If `blockState.isRandomlyTicking()`: invoke `blockState.randomTick(this, pos, this.random)`. Block draws from `this.random`.
         - Lookup fluid state: `blockState.getFluidState()`.
         - If `fluidState.isRandomlyTicking()`: invoke `fluidState.randomTick(this, pos, this.random)`. Fluid draws from `this.random`.

Critical Parity Invariant: Because `getBlockRandomPos` is called ONLY inside `if (section.isRandomlyTicking())`, any desynchronization in section ticking classification causes immediate divergence of all subsequent coordinate samples across the entire level.

### 2.5 Section-Level and Block-Level isRandomlyTicking Filters
Section filtering prevents iterating through empty or inert air/rock volumes.
In `LevelChunkSection.java:111-121`:
> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

These counts are updated on every block modification in `setBlockState` (`LevelChunkSection.java:70-98`):
- If `previous.isRandomlyTicking()`, decrement `this.tickingBlockCount--`.
- If `previousFluid.isRandomlyTicking()`, decrement `this.tickingFluidCount--`.
- If `state.isRandomlyTicking()`, increment `this.tickingBlockCount++`.
- If `fluid.isRandomlyTicking()`, increment `this.tickingFluidCount++`.
- During bulk chunk deserialization, `recalcBlockCounts()` (`LevelChunkSection.java:123-155`) performs a full pass over the paletted container to recount non-empty, fluid, and randomly ticking entries.

Block-level evaluation:
- In `BlockBehaviour.BlockStateBase.isRandomlyTicking()` (`BlockBehaviour.java:871-873`), defaults to the boolean cached from `BlockBehaviour.Properties.randomTicks()` (`BlockBehaviour.java:91`, `456`).
- Overridden dynamically by classes whose ticking status depends on block properties:
  - `LeavesBlock.java:62-64`: `state.getValue(DISTANCE) == 7 && !state.getValue(PERSISTENT)`
  - `CropBlock.java:74-76`: `!this.isMaxAge(state)`
  - `PitcherCropBlock.java:139-141`: `state.getValue(HALF) == DoubleBlockHalf.LOWER && !this.isMaxAge(state)`
  - `SweetBerryBushBlock.java:67-69`: `state.getValue(AGE) < 3`
  - `CocoaBlock.java:47-49`: `state.getValue(AGE) < 2`
  - `NetherWartBlock.java:47-49`: `state.getValue(AGE) < 3`
  - `GrowingPlantHeadBlock.java:45-47`: `state.getValue(AGE) < 25`
  - `BambooStalkBlock.java:116-118`: `state.getValue(STAGE) == 0`
  - `RedStoneOreBlock.java:82-84`: `state.getValue(LIT)`
  - `WeatheringCopper...`: `WeatheringCopper.getNext(state.getBlock()).isPresent()`
  - `LiquidBlock.java:104-106`: `state.getFluidState().isRandomlyTicking()`
  - `FlowerPotBlock.java:136-138`: `state.is(Blocks.POTTED_OPEN_EYEBLOSSOM) || state.is(Blocks.POTTED_CLOSED_EYEBLOSSOM)`

### 2.6 Fluid Random Ticks
In Minecraft 26.2, fluids participate in random ticks via `FluidState.randomTick` (`FluidState.java:91-93`, `Fluid.java:63-65`):
- `WaterFluid` does not override `isRandomlyTicking()`, returning `false` (`Fluid.java:80`). Water does NOT randomly tick.
- `LavaFluid` overrides `isRandomlyTicking()` to return `true` (`LavaFluid.java:222-224`).
- `LavaFluid.randomTick` (`LavaFluid.java:81-116`):
  1. Checks `level.canSpreadFireAround(pos)` (`ServerLevel.java:1830-1833`), evaluating `GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER`.
  2. Samples `int passes = random.nextInt(3)`.
  3. If `passes > 0`:
     - Performs `passes` attempts stepping outward: `testPos = testPos.offset(random.nextInt(3) - 1, 1, random.nextInt(3) - 1)`.
     - If `testPos` is not loaded, aborts.
     - If `blockState.isAir()` and `hasFlammableNeighbours(level, testPos)`: places fire via `level.setBlockAndUpdate(testPos, BaseFireBlock.getState(level, testPos))` and returns.
     - If `blockState.blocksMotion()`, aborts.
  4. If `passes == 0`:
     - Checks 3 positions: `testPos = pos.offset(random.nextInt(3) - 1, 0, random.nextInt(3) - 1)`.
     - If `level.isEmptyBlock(testPos.above())` and `isFlammable(level, testPos)`: places fire above `testPos` via `level.setBlockAndUpdate(testPos.above(), BaseFireBlock.getState(level, testPos))`.
- Double-call detail in `LiquidBlock`:
  In `ServerLevel.tickChunk` (`ServerLevel.java:523-530`), if a block is a `LiquidBlock` holding lava:
  1. `blockState.isRandomlyTicking()` is true -> calls `LiquidBlock.randomTick` (`LiquidBlock.java:109-111`), which calls `fluidState.randomTick(...)`.
  2. `fluidState.isRandomlyTicking()` is also true -> calls `fluidState.randomTick(...)` directly.
  Lava therefore receives two random tick opportunities per drawn position in vanilla!

### 2.7 Precipitation, Ice, and Snow in the Chunk Loop
Precipitation ticking runs in `ServerLevel.tickChunk` (`ServerLevel.java:503-507`):
```java
for (int i = 0; i < tickSpeed; i++) {
   if (this.random.nextInt(48) == 0) {
      this.tickPrecipitation(this.getBlockRandomPos(minX, 0, minZ, 15));
   }
}
```

Detailed logic in `ServerLevel.tickPrecipitation` (`ServerLevel.java:578-608`):
1. `BlockPos topPos = this.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);`
2. `BlockPos belowPos = topPos.below();`
3. `Biome biome = this.getBiome(topPos).value();`
4. **Water Freezing:**
   - Evaluates `biome.shouldFreeze(this, belowPos)` (`Biome.java`): checks sea-level temperature, whether `belowPos.getY() >= level.getMinY()` and `< level.getMaxY()`, and whether the block is a valid water source with no motion-blocking roof.
   - If true: `this.setBlockAndUpdate(belowPos, Blocks.ICE.defaultBlockState());` (flags 3).
5. **Snow Accumulation and Cauldron Filling (gated on `this.isRaining()`):**
   - Snow accumulation:
     - `int maxHeight = this.getGameRules().get(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT);` (default 1).
     - If `maxHeight > 0` and `biome.shouldSnow(this, topPos)`:
       - If `state.is(Blocks.SNOW)`:
         - `int currentLayers = state.getValue(SnowLayerBlock.LAYERS);`
         - If `currentLayers < Math.min(maxHeight, 8)`:
           - `BlockState newState = state.setValue(SnowLayerBlock.LAYERS, currentLayers + 1);`
           - `Block.pushEntitiesUp(state, newState, this, topPos);`
           - `this.setBlockAndUpdate(topPos, newState);`
       - If not snow: `this.setBlockAndUpdate(topPos, Blocks.SNOW.defaultBlockState());`
   - Cauldron handling:
     - `Biome.Precipitation precipitation = biome.getPrecipitationAt(belowPos, this.getSeaLevel());`
     - If `precipitation != Biome.Precipitation.NONE`:
       - `BlockState belowState = this.getBlockState(belowPos);`
       - `belowState.getBlock().handlePrecipitation(belowState, this, belowPos, precipitation);`
       - `CauldronBlock.java:42-52`: If empty cauldron:
         - Rain: chance `level.getRandom().nextFloat() < 0.05F` -> writes `Blocks.WATER_CAULDRON.defaultBlockState()`, emits `GameEvent.BLOCK_CHANGE`.
         - Snow: chance `level.getRandom().nextFloat() < 0.1F` -> writes `Blocks.POWDER_SNOW_CAULDRON.defaultBlockState()`, emits `GameEvent.BLOCK_CHANGE`.
       - `LayeredCauldronBlock.java:116-122`: If partially filled cauldron (`LEVEL < 3`) matching precipitation type:
         - Cycles `LEVEL` property (`newLevel = currentLevel + 1`), sets block with flags 3, emits `GameEvent.BLOCK_CHANGE`.

### 2.8 Lightning and Thunder in the Chunk Loop
Atmospheric thunder is driven by `ServerChunkCache.tickSpawningChunk` (`ServerChunkCache.java:422-424`), executed only for chunks within `distanceManager.inEntityTickingRange`:
In `ServerLevel.tickThunder` (`ServerLevel.java:541-575`):
1. Condition: `this.isRaining() && this.isThundering() && this.random.nextInt(100000) == 0`.
2. Samples target position: `this.findLightningTargetAround(this.getBlockRandomPos(minX, 0, minZ, 15))`.
   - Checks for nearby lightning rods via POI (`findLightningRod`, radius 128, `PoiTypes.LIGHTNING_ROD`).
   - If no rod: searches for living entities under open sky within bounding box (`search.inflate(3.0)`).
   - Fallback: returns motion-blocking heightmap position.
3. If `this.isRainingAt(pos)`:
   - Calculates local difficulty: `DifficultyInstance difficulty = this.getCurrentDifficultyAt(pos)`.
   - Tests for skeleton horse trap:
     `this.getGameRules().get(GameRules.SPAWN_MOBS) && this.random.nextDouble() < difficulty.getEffectiveDifficulty() * 0.01 && !this.getBlockState(pos.below()).is(BlockTags.LIGHTNING_RODS)`.
   - Spawns skeleton horse trap entity if true.
   - Spawns `LightningBolt` entity (`snapTo(Vec3.atBottomCenterOf(pos))`, `setVisualOnly(isTrap)`).

---

## 3. Audit of Every Randomly Ticking Block Family in 26.2

### 3.1 Crops (`CropBlock`, `BeetrootBlock`, `TorchflowerCropBlock`, `PitcherCropBlock`)
- **Vanilla method:** `CropBlock.randomTick` (`CropBlock.java:79-89`).
- **Filter:** `isRandomlyTicking(BlockState)` (`CropBlock.java:74-76`) returns `!this.isMaxAge(state)`.
- **Light check:** `level.getRawBrightness(pos, 0) >= 9` (`CropBlock.java:80`).
- **Growth speed calculation:** `getGrowthSpeed(this, level, pos)` (`CropBlock.java:100-142`):
  - Base speed: `1.0F`.
  - Scans 3x3 footprint on `pos.below()`: checks `BlockTags.GROWS_CROPS`. Farmland with `MOISTURE > 0` contributes 3.0F; unmoistened farmland or valid soil contributes 1.0F. Non-central blocks divided by 4.0F.
  - Crowding penalty: checks adjacent cardinal and diagonal neighbor positions for the same crop type. If rows exist in both horizontal and vertical directions, or diagonally, speed is halved (`speed /= 2.0F`).
- **Random draw:** `random.nextInt((int)(25.0F / growthSpeed) + 1) == 0`.
- **Block write:** `level.setBlock(pos, this.getStateForAge(age + 1), 2)` (flags = 2: `UPDATE_CLIENTS`, no neighbor notifications, shape updates active).
- **Subclass variations:**
  - `BeetrootBlock.java:50-54`: Gated by additional roll `random.nextInt(3) != 0` before calling `super.randomTick` (33% growth suppression; max age 3).
  - `TorchflowerCropBlock.java:66-70`: Gated by `random.nextInt(3) != 0`; at age 2 converts directly to `Blocks.TORCHFLOWER.defaultBlockState()`.
  - `PitcherCropBlock.java:139-161`:
    - `isRandomlyTicking` only returns true for `HALF == DoubleBlockHalf.LOWER` and `!isMaxAge`.
    - At age 3 -> 4, transitions to two blocks tall: writes lower half with flags 2 (`level.setBlock(lowerPos, newLowerState, 2)`), and writes upper half with flags 3 (`level.setBlock(lowerPos.above(), newLowerState.setValue(HALF, DoubleBlockHalf.UPPER), 3)`).

### 3.2 Stems (`StemBlock`, `AttachedStemBlock`)
- **Vanilla method:** `StemBlock.randomTick` (`StemBlock.java:84-108`).
- **Filter:** `Properties.randomTicks()` (static).
- **Light check:** `level.getRawBrightness(pos, 0) >= 9`.
- **Growth speed:** Reuses `CropBlock.getGrowthSpeed(this, level, pos)`.
- **Random draw:** `random.nextInt((int)(25.0F / growthSpeed) + 1) == 0`.
- **Branching logic:**
  - If `age < 7`: advances age via `level.setBlock(pos, state.setValue(AGE, age + 1), 2)`.
  - If `age == 7` (fruit production):
    - Draws random horizontal direction: `Direction direction = Direction.Plane.HORIZONTAL.getRandomDirection(random)`.
    - Target: `BlockPos relative = pos.relative(direction)`.
    - If `level.getBlockState(relative).isAir()` and `level.getBlockState(relative.below()).is(this.fruitSupportBlocks)`:
      - Writes fruit block: `level.setBlockAndUpdate(relative, fruit.get().defaultBlockState())` (flags 3).
      - Converts stem to attached stem: `level.setBlockAndUpdate(pos, stem.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, direction))` (flags 3).
- **Harvest reversion:** When fruit is broken, `AttachedStemBlock.updateShape` (`AttachedStemBlock.java:82-87`) detects missing fruit at `state.getValue(FACING)` and returns `stem.get().defaultBlockState().trySetValue(StemBlock.AGE, 7)`.

### 3.3 Saplings and Propagules (`SaplingBlock`, `MangrovePropaguleBlock`, `TreeGrower`)
- **Vanilla method:** `SaplingBlock.randomTick` (`SaplingBlock.java:45-49`).
- **Filter:** `Properties.randomTicks()` (static).
- **Light check:** `level.getMaxLocalRawBrightness(pos.above()) >= 9`.
- **Random draw:** `random.nextInt(7) == 0` (14.28% chance).
- **Advance tree (`SaplingBlock.advanceTree`, lines 51-57):**
  - If `state.getValue(STAGE) == 0`: cycles stage via `level.setBlock(pos, state.cycle(STAGE), 260)` (flags `4 | 256` = `UPDATE_CLIENTS | UPDATE_NO_REPLACE`).
  - If `state.getValue(STAGE) == 1`: invokes `treeGrower.growTree(level, generator, pos, state, random)`.
- **Tree feature placement (`TreeGrower.java:129-181`):**
  - Evaluates 2x2 mega tree (`isTwoByTwoSapling`, Dark Oak, Spruce, Jungle):
    - Clears 4 sapling positions to air (`level.setBlock(pos.offset(dx, 0, dz), air, 260)`).
    - Calls `feature.place(level, generator, random, pos.offset(dx, 0, dz))`.
    - Rollback invariant: If feature placement returns false, restores all 4 saplings using `level.setBlock(..., state, 260)`.
  - Evaluates 1x1 tree:
    - Replaces sapling with fluid block: `BlockState emptyBlock = level.getFluidState(pos).createLegacyBlock(); level.setBlock(pos, emptyBlock, 260);`.
    - Calls `feature.place(level, generator, random, pos)`.
    - If placement fails, restores sapling via `level.setBlock(pos, state, 260)`.
    - If placement succeeds, sends `level.sendBlockUpdated(pos, state, emptyBlock, 2)`.

### 3.4 Grass, Mycelium, and Nylium (`SpreadingSnowyBlock`, `GrassBlock`, `MyceliumBlock`, `NyliumBlock`)
- **Vanilla method:** `SpreadingSnowyBlock.randomTick` (`SpreadingSnowyBlock.java:48-67`).
- **Filter:** `Properties.randomTicks()` (static).
- **Survival check (`canStayAlive`, lines 30-40):**
  - `BlockState aboveState = level.getBlockState(pos.above());`
  - If `aboveState.is(Blocks.SNOW) && aboveState.getValue(SnowLayerBlock.LAYERS) == 1`: survives.
  - If `aboveState.getFluidState().isFull()`: dies (drowns in water).
  - Light dampening check: `LightEngine.getLightDampeningInto(state, aboveState, Direction.UP, aboveState.getLightDampening()) < 15`.
- **Decay:** If `!canStayAlive`, writes dirt via `level.setBlockAndUpdate(pos, baseBlock.get().defaultBlockState())` (flags 3).
- **Spread:**
  - If survives and `level.getMaxLocalRawBrightness(pos.above()) >= 9`:
    - Loops 4 times (`for (int i = 0; i < 4; i++)`):
      - Samples relative offset: `dx = random.nextInt(3) - 1`, `dy = random.nextInt(5) - 3` (-3..+1), `dz = random.nextInt(3) - 1`.
      - Target pos: `pos.offset(dx, dy, dz)`.
      - If target block is `baseBlock` (Dirt) and `canPropagate(defaultBlockState, level, testPos)`:
        - Writes spread block: `level.setBlockAndUpdate(testPos, defaultBlockState.setValue(SNOWY, isSnowySetting(level.getBlockState(testPos.above()))))` (flags 3).
- **Nylium variation (`NyliumBlock.java:40-44`):**
  - Evaluates `!canBeNylium(state, level, pos)`. If light dampening >= 15, turns to Netherrack via `level.setBlockAndUpdate(pos, Blocks.NETHERRACK.defaultBlockState())`. Nylium does NOT naturally spread on random tick (only spreads via bonemeal).

### 3.5 Leaf Decay (`LeavesBlock`)
- **Vanilla method:** `LeavesBlock.randomTick` (`LeavesBlock.java:67-72`).
- **Filter:** `LeavesBlock.isRandomlyTicking` (`LeavesBlock.java:62-64`):
  ```java
  return state.getValue(DISTANCE) == 7 && !state.getValue(PERSISTENT);
  ```
- **Decay action (`LeavesBlock.decaying`, lines 74-76):**
  - Drops loot resources: `dropResources(state, level, pos)`.
  - Removes leaf block: `level.removeBlock(pos, false)` (writes air with flags 3).
- **Distance update pipeline (`LeavesBlock.updateDistance`, lines 111-124):**
  - Logs (`BlockTags.PREVENTS_NEARBY_LEAF_DECAY`) have distance 0.
  - Leaves calculate `min(neighbor_distance + 1)` across all 6 directions.
  - Distance values range from 1 to 7. If distance becomes 7, leaf becomes eligible for random tick decay.
  - Propagated through `updateShape` (`LeavesBlock.java:89-109`): if neighbor distance changes, schedules a block tick with delay 1 (`ticks.scheduleTick(pos, this, 1)`), which executes `level.setBlock(pos, updateDistance(state, level, pos), 3)`.

### 3.6 Ice and Snow Melting (`IceBlock`, `SnowLayerBlock`)
- **Vanilla method:** `IceBlock.randomTick` (`IceBlock.java:59-63`), `SnowLayerBlock.randomTick` (`SnowLayerBlock.java:105-110`).
- **Filter:** `Properties.randomTicks()` (static).
- **Light check:** Evaluates `LightLayer.BLOCK` exclusively:
  - Ice: `level.getBrightness(LightLayer.BLOCK, pos) > 11 - state.getLightDampening()` (i.e. block light >= 12). Sky light NEVER melts ice.
  - Snow: `level.getBrightness(LightLayer.BLOCK, pos) > 11` (i.e. block light >= 12). Sky light NEVER melts snow layers.
- **Melt transformation:**
  - Ice (`IceBlock.melt`, lines 65-72):
    - If `EnvironmentAttributes.WATER_EVAPORATES` (Nether): `level.removeBlock(pos, false)`.
    - Else: `level.setBlockAndUpdate(pos, meltsInto())` (writes water with flags 3) and `level.neighborChanged(pos, meltsInto().getBlock(), null)`.
  - Snow (`SnowLayerBlock.randomTick`, lines 106-109):
    - `dropResources(state, level, pos)`.
    - `level.removeBlock(pos, false)`.

### 3.7 Cactus, Sugar Cane, Bamboo, Kelp
- **Cactus (`CactusBlock.java:54-82`):**
  - Target: `pos.above()` must be empty block (`level.isEmptyBlock(above)`).
  - Scans downward height: if `height == 3 && age == 15`, aborts.
  - Cactus flower growth: if `age == 8` and can survive:
    - Chance: `height >= 3 ? 0.25 : 0.10`.
    - If roll passes: `level.setBlockAndUpdate(above, Blocks.CACTUS_FLOWER.defaultBlockState())`.
  - Height extension: if `age == 15 && height < 3`:
    - Writes cactus above: `level.setBlockAndUpdate(above, this.defaultBlockState())` (flags 3).
    - Resets current age: `level.setBlock(pos, state.setValue(AGE, 0), 260)` (flags 260).
    - Notifies neighbor: `level.neighborChanged(aboveBlock, above, this, null, false)`.
  - Age increment: if `age < 15`: `level.setBlock(pos, state.setValue(AGE, age + 1), 260)`.
- **Sugar Cane (`SugarCaneBlock.java:50-68`):**
  - If `level.isEmptyBlock(pos.above())`: counts height downward.
  - If `height < 3`:
    - If `age == 15`: writes sugar cane above with flags 3 (`level.setBlockAndUpdate(pos.above(), this.defaultBlockState())`), resets age with flags 260 (`level.setBlock(pos, state.setValue(AGE, 0), 260)`).
    - If `age < 15`: increments age with flags 260 (`level.setBlock(pos, state.setValue(AGE, age + 1), 260)`).
- **Bamboo (`BambooSaplingBlock.java:40-44`, `BambooStalkBlock.java:121-214`):**
  - Sapling: `random.nextInt(3) == 0 && level.isEmptyBlock(pos.above()) && level.getRawBrightness(pos.above(), 0) >= 9`:
    - Writes stalk above: `level.setBlock(pos.above(), Blocks.BAMBOO.defaultBlockState().setValue(BambooStalkBlock.LEAVES, BambooLeaves.SMALL), 3)`.
  - Stalk: filter `STAGE == 0`.
    - If `random.nextInt(3) == 0 && level.isEmptyBlock(pos.above()) && level.getRawBrightness(pos.above(), 0) >= 9`:
      - Height check: `height < 16`.
      - Evaluates leaf stages: `NONE`, `SMALL`, `LARGE`. Updates states at `pos.below()` and `pos.below(2)` to `SMALL` and `NONE` as top grows.
      - Sets top stalk with `STAGE = (height < 11 || !(random.nextFloat() < 0.25F)) && height != 15 ? 0 : 1`.
- **Kelp and Vines (`GrowingPlantHeadBlock.java:50-56`):**
  - Filter: `state.getValue(AGE) < 25`.
  - Growth probability: `random.nextDouble() < this.growPerTickProbability` (Kelp: `0.14`, Twisting Vines: `0.10`, Weeping Vines: `0.10`, Cave Vines: `0.11`).
  - Target: `growthPos = pos.relative(this.growthDirection)`.
  - Write: `level.setBlockAndUpdate(growthPos, this.getGrowIntoState(state, level.getRandom()))` (flags 3, cycles age).
  - Old head converts to body via `updateShape`.

### 3.8 Copper Oxidation (`WeatheringCopper`, `ChangeOverTimeBlock`)
- **Vanilla method:** `WeatheringCopper.changeOverTime` (`ChangeOverTimeBlock.java:16-21`).
- **Filter:** `WeatheringCopper.getNext(state.getBlock()).isPresent()`. Fully oxidized blocks do not randomly tick.
- **Initial roll:** `random.nextFloat() < 0.05688889F` (`64.0F / 1125.0F`, approx once per 20-minute Minecraft day).
- **Neighbor scan (`getNextState`, `ChangeOverTimeBlock.java:25-56`):**
  - Scans `BlockPos.withinManhattan(pos, 4, 4, 4)`.
  - Suppressive rule: If any neighbor copper block has `foundAge < ownAge` (less weathered), returns `Optional.empty()` immediately! A fresh block protects adjacent weathered blocks from oxidizing further.
  - Accumulation: counts `olderCount` (more weathered) and `sameAgeCount`.
  - Probability formula:
    ```java
    float chance = (float)(olderCount + 1) / (olderCount + sameAgeCount + 1);
    float actualChance = chance * chance * this.getChanceModifier();
    ```
    where `getChanceModifier()` is `0.75F` for `UNAFFECTED` copper and `1.0F` otherwise (`WeatheringCopper.java:80`).
- **Roll and write:** If `random.nextFloat() < actualChance`, writes next oxidation stage via `level.setBlockAndUpdate(pos, weatheredState)` (flags 3).

### 3.9 Fire Spread and Burn-Out (`FireBlock`, `LavaFluid`)
- **Architectural distinction:**
  - `LavaFluid.randomTick` (`LavaFluid.java:81-116`) is a RANDOM TICK: ignites air blocks adjacent to lava.
  - `FireBlock.tick` (`FireBlock.java:142-210`) is a SCHEDULED TICK: driven by `level.blockTicks`, NOT random ticks.
  - When fire is created (`onPlace`), it schedules itself via `level.scheduleTick(pos, this, getFireTickDelay(level.getRandom()))` where delay is `30 + random.nextInt(10)`.
- **Inside `FireBlock.tick`:**
  - Re-schedules next tick with `getFireTickDelay`.
  - Evaluates `level.canSpreadFireAround(pos)`.
  - Checks rain extinguish: `!infiniBurn && level.isRaining() && this.isNearRain(level, pos) && random.nextFloat() < 0.2F + age * 0.03F` -> removes fire.
  - Age increment: `newAge = Math.min(15, age + random.nextInt(3) / 2)`. Updates with flags 260.
  - Burn-out: If not infiniburn, `age == 15 && random.nextInt(4) == 0 && !canBurn(below)` -> extinguishes.
  - Neighbor spread: 3x3x6 box (`xx: -1..1, zz: -1..1, yy: -1..4`). Computes `odds = (igniteOdds + 40 + difficulty * 7) / (age + 30)`. If roll succeeds, sets fire.

### 3.10 Farmland Moisture (`FarmlandBlock`)
- **Vanilla method:** `FarmlandBlock.randomTick` (`FarmlandBlock.java:95-106`).
- **Filter:** `Properties.randomTicks()` (static).
- **Hydration condition:** `isNearWater(level, pos) || level.isRainingAt(pos.above())`.
  - `isNearWater` (`FarmlandBlock.java:131-139`): checks `BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))` for `FluidTags.WATER`. (9x2x9 box).
- **Moisture updates:**
  - If hydrated: if `moisture < 7`, writes `level.setBlock(pos, state.setValue(MOISTURE, 7), 2)`.
  - If not hydrated:
    - If `moisture > 0`: writes `level.setBlock(pos, state.setValue(MOISTURE, moisture - 1), 2)`.
    - If `moisture == 0`: checks `shouldMaintainFarmland(level, pos)` (`level.getBlockState(pos.above()).is(BlockTags.MAINTAINS_FARMLAND)`). If false, turns to dirt via `turnToDirt(null, state, level, pos)`:
      - `BlockState newState = pushEntitiesUp(state, Blocks.DIRT.defaultBlockState(), level, pos);`
      - `level.setBlockAndUpdate(pos, newState);`
      - `level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(null, newState));`

### 3.11 All Other 26.2 Blocks with randomTick
1. **Sweet Berry Bush (`SweetBerryBushBlock.java:72-79`):** Filter `AGE < 3`. If `random.nextInt(5) == 0 && level.getRawBrightness(pos.above(), 0) >= 9`, writes `state.setValue(AGE, age + 1)` with flags 2, emits `GameEvent.BLOCK_CHANGE`.
2. **Cocoa (`CocoaBlock.java:52-59`):** Filter `AGE < 2`. If `level.getRandom().nextInt(5) == 0`, writes `state.setValue(AGE, age + 1)` with flags 2.
3. **Nether Wart (`NetherWartBlock.java:52-58`):** Filter `AGE < 3`. If `random.nextInt(10) == 0`, writes `state.setValue(AGE, age + 1)` with flags 2.
4. **Chorus Flower (`ChorusFlowerBlock.java:64-126`):** Filter `AGE < 5`. If empty above, grows upward (writes chorus plant, places flower above with levelEvent 1033) or branches horizontally into 1-4 branches. If aging fails or branches cannot form, places dead flower with levelEvent 1034.
5. **Budding Amethyst (`BuddingAmethystBlock.java:27-50`):** If `random.nextInt(5) == 0`: samples 1 of 6 directions. If adjacent block is air/water, grows small bud. If small bud, grows medium bud. If medium bud, grows large bud. If large bud, grows cluster. Writes with flags 3.
6. **Turtle Egg (`TurtleEggBlock.java:95-139`):** If on sand and `shouldUpdateHatchLevel` (`EnvironmentAttributes.TURTLE_EGG_HATCH_CHANCE` roll passes):
   - If `hatch < 2`: plays `TURTLE_EGG_CRACK`, writes `HATCH = hatch + 1` with flags 2.
   - If `hatch == 2`: plays `TURTLE_EGG_HATCH`, removes block, spawns baby turtle entities (`turtle.setAge(-24000)`).
7. **Mushroom (`MushroomBlock.java:48-75`):** If `random.nextInt(25) == 0`: checks 9x3x9 box around mushroom. If count >= 5, aborts. Takes up to 4 random jumps (`dx: -1..1, dy: -1..1, dz: -1..1`). If valid surface, writes mushroom with flags 2.
8. **Pointed Dripstone & Speleothem (`PointedDripstoneBlock.java:85-132`, `SpeleothemBlock.java:291-318`):**
   - Dripstone transfer: checks fluid source above stalactite. Water (17.57% chance) or lava (5.85% chance). If mud above, turns mud to clay (`level.setBlockAndUpdate(..., CLAY)`) and emits levelEvent 1504. If cauldron below, schedules cauldron tick (`scheduleTick(cauldronPos, delay)`).
   - Speleothem growth (`SpeleothemBlock.java:291`): if `random.nextFloat() < 0.011377778F`, grows stalactite down or stalagmite up.
9. **Eyeblossom (`EyeblossomBlock.java:62-95`):** Evaluates `EnvironmentAttributes.EYEBLOSSOM_OPEN`. If open/closed state mismatches day/night, transforms to opposite type with flags 3, plays switch sound, and schedules cascaded ticks on matching neighbors within 7x5x7 box.
10. **Dried Ghast (`DriedGhastBlock.java:162-166`):** If waterlogged or hydration > 0, and no tick scheduled, schedules rehydration tick with delay 5000.
11. **Nether Portal (`NetherPortalBlock.java:69-90`):** If monster spawning enabled, not peaceful, and `random.nextInt(2000) < difficulty.getId()`: spawns zombified piglin at base of portal.
12. **Redstone Ore (`RedStoneOreBlock.java:87-91`):** Filter `LIT == true`. Random tick unsets lit property: `level.setBlock(pos, state.setValue(LIT, false), 3)`.
13. **Vine (`VineBlock.java:167-240`):** Gated by `GameRules.SPREAD_VINES`. If `random.nextInt(4) == 0`: samples random direction, spreads horizontally, downwards, or onto ceiling with flags 2.
14. **Flower Pot (`FlowerPotBlock.java:141-154`):** Filter `is(POTTED_OPEN_EYEBLOSSOM) || is(POTTED_CLOSED_EYEBLOSSOM)`. Toggles open/closed state based on `EYEBLOSSOM_OPEN` attribute.

---

## 4. Analysis of Kestrel Today

### 4.1 Current TickLoop Phases and Ordering
In `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1157-1204`:
```java
public void tick() {
    gameTime++;
    redstone.setHandlingTick(true);
    try {
        drainScheduled();
        drainFluidTicks();
        if (entityTracker != null) {
            entityTracker.tick();
        }
        redstone.runBlockEvents();
        redstone.setHandlingTick(false);
        if (activeTickets.getAsBoolean()) {
            emptyTime = 0;
        }
        emptyTime++;
        if (emptyTime < 300 && entityWorld != null) {
            entityWorld.tickEntities();
        }
        level.sleepers().beginPass();
        for (long id : level.sleepers().walkOrder()) {
            sleepTick(id);
        }
        tickMovingPistons();
    } finally {
        redstone.setHandlingTick(false);
    }
    drainDispatch();
    if (powerTraceEnabled) {
        tracePower();
    }
}
```

Vanilla `ServerLevel.tick` phase comparison:
1. Advance clock (`tickTime`) -> Kestrel `gameTime++`.
2. Scheduled block ticks (`blockTicks.tick`) -> Kestrel `drainScheduled()`.
3. Scheduled fluid ticks (`fluidTicks.tick`) -> Kestrel `drainFluidTicks()`.
4. Chunk source tick (`chunkSource.tick`):
   - `tickChunks`: natural mob spawns, inhabited time, thunder, precipitation, and block/fluid random ticks.
   - `chunkMap.tick`: entity tracker sync and changed chunk broadcasting.
5. Block events (`runBlockEvents`) -> Kestrel `redstone.runBlockEvents()`.
6. Entity ticking (`entityTickList.forEach`) -> Kestrel `entityWorld.tickEntities()`.
7. Block entity ticking (`tickBlockEntities`) -> Kestrel `sleepTick` and `tickMovingPistons`.

Finding: Kestrel currently has a clean phase boundary after `drainFluidTicks()` and before `entityTracker.tick()`. In vanilla, `chunkSource.tick` executes random ticks BEFORE updating chunk holders and entity trackers. Kestrel currently executes `entityTracker.tick()` immediately after fluid ticks, skipping the random tick phase entirely.

### 4.2 Scheduled Tick Drain vs Random Ticks
Kestrel implements exact vanilla `LevelTicks` scheduled drain in `drainScheduled()` and `drainFluidTicks()` (`TickLoop.java:1058-1111`):
- Priority queues indexed by `(triggerTick, priority, subTickOrder)`.
- Capped at `MAX_SCHEDULED_TICKS_PER_TICK = 65536`.
- Scheduled block ticks and fluid ticks are completely separated from random ticks. Scheduled ticks are deterministic delays requested by blocks (e.g. redstone repeaters, water flow spread, leaf decay distance recalculations), whereas random ticks are sampled each tick via chunk iteration and LCG position rolls.

### 4.3 G66 Live setBlock Path
Kestrel's block mutation pipeline is consolidated in `VanillaRedstone.java:216-320`:
- Handles `rawSet` on `SectionStore`.
- Dispatches write events to `writeListener` (which queues chunk packets for `ChunkSender`).
- Handles `preRemoveSideEffects` and block entity cleanup.
- Dispatches `affectNeighborsAfterRemoval` when `(updateFlags & 1) != 0`.
- Invokes `BlockLogic.onPlace` unless `(updateFlags & 512) != 0`.
- Executes neighbor notifications: `updateNeighborsAt` (flags 1).
- Executes shape updates: `updateIndirectNeighbourShapes` and `updateNeighbourShapes` (unless suppressed by flags 16).
- All block writes from random ticks (crops with flags 2, grass with flags 3, saplings with flags 260) will seamlessly flow through this existing pipeline.

### 4.4 Section Storage and Memory Representation
In `core/src/vanilla/java/dev/kestrel/loop/SectionStore.java:75-115`:
- Sections are stored in `Section` objects containing `cells` (`BlockState[4096]`), `written` bitset, `edited` bitset, and compact palettes.
- Critical gap: `Section` tracks `writtenCount` and `editedCount`, but DOES NOT track `tickingBlockCount` or `tickingFluidCount`.
- To support vanilla's `LevelChunkSection.isRandomlyTicking()` filter without doing an O(4096) scan every tick, `Section` must maintain `tickingBlockCount` and `tickingFluidCount`, updated on every `set` or bulk load.

### 4.5 Simulation Distance and Player Tick Scoping
- In `ServerProperties.java:425`, `simulationDistance()` defaults to 10.
- In `EntityWorld.java:34-40`, Kestrel notes:
  "Explicit approximations, because Kestrel has no chunk ticket or Visibility lifecycle yet (slice 5) and never unloads a chunk: every chunk an entity is in is treated as ENTITY_TICKING... and inEntityTickingRange is a predicate the owner supplies, true everywhere on the live server."
- For G30, the random tick driver can similarly iterate loaded chunks or use a Chebyshev distance predicate around online players (`dist <= simulationDistance`) before full S16 ticket management lands.

### 4.6 Weather and Rain State in WorldTime
In `core/src/vanilla/java/dev/kestrel/net/WorldTime.java:16-120`:
- Tracks `dayTime` (0..23999), `raining` (boolean), `rainTime`, and `rainLevel` (eased by 0.01 per tick).
- Accurately mirrors `ServerLevel.advanceWeatherCycle` rain countdowns (clear delay 12,000 to 180,000 ticks; rain duration 12,000 to 24,000 ticks).
- Line 23 notes: "THUNDER_STUB: thunder is not simulated (thunderLevel stays 0, no THUNDER_LEVEL_CHANGE is sent)."
- Finding: `WorldTime.raining()` is already available to drive `tickPrecipitation` (snow layers, ice freezing, and cauldron filling). Thunder remains stubbed until G50.

### 4.7 Insertion Point: Where Random Ticks Must Sit in TickLoop.tick
To match `ServerLevel.tick`'s exact sequence:
1. `drainScheduled();` (Scheduled block ticks)
2. `drainFluidTicks();` (Scheduled fluid ticks)
3. **`tickRandomChunks();` (G30: precipitation, block random ticks, fluid random ticks)**
4. `if (entityTracker != null) { entityTracker.tick(); }` (Outbound chunk and entity sync)
5. `redstone.runBlockEvents();` (Block events)
6. `entityWorld.tickEntities();` (Entity movement and living ticks)
7. `sleepTick` and `tickMovingPistons();` (Block entities)

Placing `tickRandomChunks()` immediately after `drainFluidTicks()` and before `entityTracker.tick()` ensures that any block updates created by random ticks are journaled and broadcast to tracking clients in the exact same tick frame as vanilla.

---

## 5. Roadmap Dependencies and Decoupling

### 5.1 S16 Chunk Tickets and Simulation Distance
- **Vanilla coupling:** Vanilla drives random ticks by iterating `simulationChunkTracker.chunks` with level <= 31 (`ChunkLevel.isEntityTicking`).
- **Decoupling strategy:** G30 does NOT require S16 to be completed first.
  - Phase 1 (Standalone): The random tick driver iterates the active chunks held in `SectionStore` (or filtered by player Chebyshev simulation distance: `max(|chunkX - playerChunkX|, |chunkZ - playerChunkZ|) <= simulationDistance`).
  - Phase 2 (S16 Integration): Once S16 lands, the driver switches its chunk source to `DistanceManager.forEachEntityTickingChunk` without modifying any block ticking logic.

### 5.2 G50 Lightning and Weather Integration
- **Vanilla coupling:** `tickThunder` runs during chunk ticking and spawns `LightningBolt` and `SkeletonHorse` entities.
- **Decoupling strategy:** `tickThunder` is decoupled from G30. G30 implements `tickChunk` (precipitation and block/fluid random ticks). The thunder check can be cleanly stubbed or left inactive until G50 introduces the lightning bolt entity and sound packets.

### 5.3 G3 Weather Cycle Integration
- **Vanilla coupling:** Snow accumulation, ice formation, and cauldron filling require active rain.
- **Existing capability:** Kestrel already possesses a functional rain state machine in `WorldTime.java:40-69`. G30 queries `worldTime.raining()` directly to determine precipitation activity.

### 5.4 Feature Placement and Worldgen Seam
- **Vanilla coupling:** Tree growth from saplings (`TreeGrower.growTree`) executes configured feature placement (`ConfiguredFeature.place`).
- **Existing capability:** Kestrel already has full vanilla worldgen configured features and tree placement from G2/Step 10 worldgen (`core/src/vanilla/java/dev/kestrel/worldgen`). When a sapling matures, it invokes the existing `TreeGrower` and `ConfiguredFeature` infrastructure on the live `TickLoop` level.

---

## 6. Vertical Slice Implementation Plan

### Slice 1: Chunk Random Tick Driver, Section Ticking Counters, and Position LCG
- **Objective:** Establish the per-tick chunk random loop driver, section-level `tickingBlockCount` and `tickingFluidCount` tracking, the canonical 32-bit position LCG math, and the `randomTickSpeed` game rule configuration.
- **Classes to Implement / Extend:**
  - `dev.kestrel.loop.SectionStore`: Add `short tickingBlockCount` and `short tickingFluidCount` to `Section`. Update counters in `set()` and bulk imports. Expose `isRandomlyTicking()` on sections.
  - `dev.kestrel.loop.TickLoop`: Add `randValue` LCG state, `tickRandomChunks()`, and insert it into `TickLoop.tick` between `drainFluidTicks()` and `entityTracker.tick()`.
  - Add game rule accessor for `randomTickSpeed` (default 3).
- **Vanilla-Oracle Gate (`RandomTickDriverParityCheck.java`):**
  - Construct identical section storage configurations in Kestrel and in a vanilla oracle `ServerLevel`.
  - Scenario 1: A chunk containing 16 sections, where only section Y=4 contains randomly ticking blocks. Assert that `getBlockRandomPos` is called exactly `randomTickSpeed` times per tick, and only for section Y=4.
  - Scenario 2: Verify `randValue` LCG progression: after 10,000 draws from seed `0x12345678`, verify that Kestrel's generated `BlockPos` sequence matches vanilla's `Level.getBlockRandomPos` coordinate-for-coordinate.
  - Scenario 3: Verify dynamic counter tracking: placing a crop increments `tickingBlockCount`; breaking it decrements `tickingBlockCount`. When count reaches 0, section is omitted from ticking.
  - Scenario 4: Change `randomTickSpeed` to 0, 3, and 20; assert exact draw count scaling.
- **Mutants:**
  - *Mutant 1:* Change LCG multiplier from 3 to 5 (`randValue * 5 + 1013904223`) -> gate fails position parity checks.
  - *Mutant 2:* Omit `tickingBlockCount` decrement on block removal -> gate detects phantom ticking on empty sections.
  - *Mutant 3:* Use `pos.getY() & 15` without `yMask` in `getBlockRandomPos` -> gate fails Y-coordinate boundary tests.
  - *Mutant 4:* Draw random positions when `randomTickSpeed == 0` -> gate fails zero-tick speed check.

### Slice 2: Agricultural Growth - Crops, Stems, Farmland Moisture, and Sweet Berry Bushes
- **Objective:** Implement the crop growth algorithm, hydration speed calculation, stem fruit attachment, farmland hydration/dehydration, and sweet berry bush aging.
- **Classes to Implement / Extend:**
  - Implement `CropGrowthLogic`: replicates `CropBlock.getGrowthSpeed`, light checks (>= 9), row crowding penalties, and age advancement with flags 2.
  - Implement `StemGrowthLogic`: replicates `StemBlock.randomTick`, horizontal fruit placement with flags 3, and attached stem conversion.
  - Implement `FarmlandLogic`: replicates 9x2x9 water scan, moisture decrement, and `turnToDirt` with entity displacement.
  - Implement `SweetBerryBushLogic`: replicates age 0-3 growth and game event emission.
- **Vanilla-Oracle Gate (`CropFarmlandParityCheck.java`):**
  - Scenario 1: Wheat crop on hydrated farmland next to identical crop rows vs isolated crop: assert calculated growth speeds match vanilla `CropBlock.getGrowthSpeed` to float precision (`4.0F` vs `2.0F` vs `1.75F`).
  - Scenario 2: Replay identical RNG seeds for 100 ticks of wheat growth: verify age states match vanilla tick-for-tick.
  - Scenario 3: Pumpkin stem at age 7 with adjacent stone vs dirt: verify fruit only spawns on valid soil and stem facing points to fruit.
  - Scenario 4: Farmland with water 4 blocks away horizontally: verify remains moisture 7. Remove water: verify moisture drops 7 to 0 and turns to dirt if unplanted.
- **Mutants:**
  - *Mutant 1:* Farmland water scan checked as 7x2x7 instead of 9x2x9 (radius 3 instead of 4) -> gate fails boundary water test.
  - *Mutant 2:* Crop row crowding penalty forgotten -> gate fails growth speed check on parallel rows.
  - *Mutant 3:* Stem fruit write uses flags 2 instead of flags 3 -> gate fails neighbor block update assertion.
  - *Mutant 4:* Allow crops to grow at raw brightness 8 -> gate fails light threshold check (minimum is 9).

### Slice 3: Environmental Spread and Decay - Grass, Mycelium, Leaf Decay, Ice/Snow, and Precipitation
- **Objective:** Implement grass/mycelium spread and death, leaf decay distance tracking and decay dispatch, ice and snow melting under block light, and precipitation ticking (freezing water, accumulating snow layers, filling cauldrons).
- **Classes to Implement / Extend:**
  - Implement `SoilSpreadLogic`: replicates `SpreadingSnowyBlock.randomTick`, light dampening checks, and 4-attempt coordinate walks.
  - Implement `LeafDecayLogic`: integrates `LeavesBlock.randomTick`, distance 7 filter, persistent check, and resource dropping.
  - Implement `ThermalMeltingLogic`: replicates `IceBlock` and `SnowLayerBlock` melting gated on block light > 11.
  - Implement `PrecipitationLogic`: replicates `ServerLevel.tickPrecipitation` 1-in-48 chance, ice freezing, snow layer stacking up to gamerule max, and cauldron filling.
- **Vanilla-Oracle Gate (`EnvironmentalSpreadParityCheck.java`):**
  - Scenario 1: Grass block under solid stone block: verify decays to dirt within expected random tick distribution. Grass block next to dirt under sky light: verify spreads to dirt.
  - Scenario 2: Oak leaves at distance 7 without persistent tag: verify decays on random tick and drops items. Oak leaves with persistent tag: verify never decays.
  - Scenario 3: Ice block exposed to sky light 15 and block light 0: verify does NOT melt. Place torch with block light 12: verify melts into water source.
  - Scenario 4: Enable rain in cold biome: verify water source freezes to ice, snow layers accumulate up to max height, and empty cauldron fills with water/powder snow.
- **Mutants:**
  - *Mutant 1:* Ice melts when sky light > 11 instead of block light > 11 -> gate fails daylight ice preservation check.
  - *Mutant 2:* Grass spread Y-range sampled as `random.nextInt(3) - 1` instead of `random.nextInt(5) - 3` -> gate fails vertical step spread check (-3..+1).
  - *Mutant 3:* Leaf decay occurs when `DISTANCE < 7` -> gate fails premature leaf decay check.
  - *Mutant 4:* Snow layers accumulate beyond `MAX_SNOW_ACCUMULATION_HEIGHT` -> gate fails snow accumulation height cap.

### Slice 4: Columnar and Vertical Plants - Cactus, Sugar Cane, Bamboo, Kelp, and Speleothems
- **Objective:** Implement vertical plant elongation: cactus (with cactus flower growth), sugar cane, bamboo stalk and sapling stages, kelp/vines head growth, and pointed dripstone fluid transfers.
- **Classes to Implement / Extend:**
  - Implement `CactusLogic`: height limit 3, age 0-15, cactus flower at age 8, neighbor notifications.
  - Implement `SugarCaneLogic`: height limit 3, age 0-15.
  - Implement `BambooLogic`: sapling growth, stalk height limit 16, stage 0-1, leaves states.
  - Implement `GrowingPlantHeadLogic`: probability-based elongation, age limit 25, body block conversion.
  - Implement `DripstoneLogic`: stalactite drip transfer to cauldrons and mud-to-clay conversion.
- **Vanilla-Oracle Gate (`TallPlantParityCheck.java`):**
  - Scenario 1: Cactus at height 1, age 14: advance 1 age, verify height stays 1. At age 15, verify grows to height 2 and age resets to 0. At height 3, verify growth ceases.
  - Scenario 2: Bamboo sapling: verify grows into bamboo stalk with small leaves. Bamboo stalk grows up to height 16, transitioning leaves from small to large and updating two blocks below.
  - Scenario 3: Kelp head at age 24: verify grows into age 25. At age 25, verify growth ceases (`isRandomlyTicking` returns false).
  - Scenario 4: Pointed dripstone under water source over mud block: verify mud converts to clay. Over empty cauldron: verify schedules cauldron tick.
- **Mutants:**
  - *Mutant 1:* Allow cactus to grow to height 4 -> gate fails max height check (limit 3).
  - *Mutant 2:* Cactus resets age with flags 3 instead of flags 260 -> gate fails block update flag assertion.
  - *Mutant 3:* Bamboo growth doesn't update leaves on lower stalks -> gate fails bamboo leaf state check.
  - *Mutant 4:* Kelp continues growing at age 25 -> gate fails kelp age clamp check.

### Slice 5: Weathering Copper Oxidation, Budding Amethyst, Nether Portals, and Specialty Blocks
- **Objective:** Implement copper weathering with the Manhattan distance 4 neighbor scan, budding amethyst cluster progression, zombified piglin portal spawning, redstone ore unlighting, and eyeblossom day/night switching.
- **Classes to Implement / Extend:**
  - Implement `WeatheringCopperLogic`: daily rate roll, Manhattan distance 4 scan, younger neighbor suppression, older neighbor acceleration formula.
  - Implement `BuddingAmethystLogic`: 6-direction bud/cluster stage progression.
  - Implement `NetherPortalRandomTickLogic`: difficulty-scaled piglin spawning.
  - Implement `EyeblossomLogic`: day/night environmental state toggle and cascade scheduling.
  - Implement `RedstoneOreLogic`: lit to unlit reversion.
- **Vanilla-Oracle Gate (`WeatheringSpecialtyParityCheck.java`):**
  - Scenario 1: Exposed copper block surrounded by unaffected (fresh) copper within Manhattan distance 4: verify oxidation is 100% suppressed.
  - Scenario 2: Exposed copper block surrounded by oxidized copper: verify oxidation rate is accelerated by `(olderCount + 1) / (olderCount + 1)`.
  - Scenario 3: Budding amethyst next to small bud: verify advances to medium bud, large bud, and amethyst cluster.
  - Scenario 4: Lit redstone ore receives random tick: verify turns off (`LIT = false`) with flags 3.
- **Mutants:**
  - *Mutant 1:* Copper Manhattan distance scanned as 3 instead of 4 -> gate fails suppression boundary check at distance 4.
  - *Mutant 2:* Copper younger neighbor suppression omitted -> gate fails protected copper assertion.
  - *Mutant 3:* Amethyst cluster facing does not match growth direction -> gate fails block state property check.
  - *Mutant 4:* Redstone ore unlighting uses flags 2 instead of flags 3 -> gate fails light update assertion.

### Slice 6: Sapling Tree Feature Placement and Full TickLoop Phase Integration
- **Objective:** Connect sapling maturation to live configured feature tree placement (`TreeGrower` and worldgen engine), implement mega-tree 2x2 detection and rollback, and integrate the full random tick driver into Kestrel's live server loop.
- **Classes to Implement / Extend:**
  - Implement `SaplingTreeGrowthLogic`: connects `TreeGrower` to Kestrel's live `TickLoop` and `ChunkGenerator`.
  - Implement 2x2 mega sapling check and failure rollback.
  - Wire `tickRandomChunks` into `TickLoop.tick` between `drainFluidTicks()` and `entityTracker.tick()`.
- **Vanilla-Oracle Gate (`SaplingTickLoopParityCheck.java`):**
  - Scenario 1: Oak sapling at stage 1: trigger maturation with fixed seed. Assert oak tree trunk and leaves match vanilla feature placement cell-for-cell.
  - Scenario 2: 2x2 Dark Oak saplings with obstruction preventing growth: verify 4 saplings are cleared to air during placement attempt, and then restored to original sapling states upon placement failure.
  - Scenario 3: Full tick integration: place crops, leaves, ice, and saplings in Kestrel `TickLoop`. Run 100 game ticks. Verify block changes occur in exact phase order between fluid drain and entity tracker.
- **Mutants:**
  - *Mutant 1:* Mega tree failure fails to restore cleared saplings -> gate fails sapling conservation check on blocked growth.
  - *Mutant 2:* Random tick phase executed after `redstone.runBlockEvents()` -> gate fails phase ordering assertion.
  - *Mutant 3:* Sapling tree write sends block update with flags 3 instead of flags 260 -> gate fails write listener event diff.
  - *Mutant 4:* Tree feature placement mutates coordinates without offsetting by dx/dz -> gate fails mega tree trunk alignment.

---

## 7. Verification Audit and Unverified Items

### 7.1 Ground Truth Verified Against 26.2 Server Jar
The following subsystems and classes have been decompiled, read, and verified line by line in `~/kestrel-run/vanilla-src-26.2`:
1. `ServerChunkCache.java:374-415` (`tickChunks`, randomTickSpeed lookup, chunk shuffling, loop dispatch).
2. `ServerChunkCache.java:417-425` (`tickSpawningChunk`, inhabited time, thunder dispatch).
3. `ChunkMap.java:943-970` (`collectSpawningChunks`, `forEachBlockTickingChunk`).
4. `DistanceManager.java:177-188` (`forEachEntityTickingChunk`, simulation chunk tracker walk).
5. `ChunkLevel.java:10-70` (`ENTITY_TICKING_LEVEL = 31`, `BLOCK_TICKING_LEVEL = 32`, status checks).
6. `ServerLevel.java:496-539` (`tickChunk`, precipitation rolls, section random tick loop, fluid tick dispatch).
7. `ServerLevel.java:541-575` (`tickThunder`, lightning target finding, skeleton trap odds, entity creation).
8. `ServerLevel.java:578-608` (`tickPrecipitation`, heightmap pos, biome freeze/snow, cauldron precipitation).
9. `Level.java:1067-1071`, `116-122` (`getBlockRandomPos`, LCG formula `* 3 + 1013904223`, bit extraction, `randValue` and `random` fields).
10. `LevelChunkSection.java:62-155` (`isRandomlyTicking`, `tickingBlockCount`, `tickingFluidCount`, `setBlockState` counter maintenance, `recalcBlockCounts`).
11. `CropBlock.java:70-142` (`isRandomlyTicking`, `randomTick`, `getGrowthSpeed` 3x3 hydration math, row/diagonal penalties, flags 2).
12. `BeetrootBlock.java:50-54` and `TorchflowerCropBlock.java:66-70` (33% randomTick suppression, flower conversion).
13. `PitcherCropBlock.java:139-161` (lower half ticking, age 4 double-plant creation with flags 3).
14. `StemBlock.java:84-108` and `AttachedStemBlock.java:82-87` (stem growth, fruit placement flags 3, attached stem updateShape reversion).
15. `SaplingBlock.java:45-57` and `TreeGrower.java:129-181` (light check >= 9, 1-in-7 roll, stage 260 write, 2x2 mega tree rollback, feature placement).
16. `SpreadingSnowyBlock.java:30-67` (`canStayAlive`, light dampening < 15, snow layer 1 survival, fluid drowning, 4-attempt spread walk).
17. `NyliumBlock.java:32-44` (light dampening check, decay to netherrack, no natural spread).
18. `LeavesBlock.java:62-124` (`isRandomlyTicking` distance 7 and non-persistent, decay drop and removeBlock, distance update pipeline).
19. `IceBlock.java:59-72` and `SnowLayerBlock.java:105-110` (block light > 11 requirement, water evaporation in Nether, flags 3).
20. `CactusBlock.java:54-82` and `SugarCaneBlock.java:50-68` (height limit 3, age 0-15, cactus flower at age 8, flags 260 vs 3).
21. `BambooSaplingBlock.java:40-44` and `BambooStalkBlock.java:116-214` (sapling to stalk, height limit 16, leaves NONE/SMALL/LARGE, stage 0-1).
22. `GrowingPlantHeadBlock.java:40-69` (age < 25 filter, probability roll, body conversion).
23. `WeatheringCopper.java:21-81` and `ChangeOverTimeBlock.java:16-56` (daily roll 0.05688889F, Manhattan distance 4 scan, younger suppression, older acceleration).
24. `FireBlock.java:142-210` (scheduled tick engine, re-schedule delay `30 + random.nextInt(10)`, age increment, burn-out, 3x3x6 spread).
25. `LavaFluid.java:81-116`, `222-224` (`isRandomlyTicking = true`, 3 passes, fire placement near flammable neighbors).
26. `FarmlandBlock.java:95-139` (moisture 0-7, 9x2x9 water scan, rain hydration, `turnToDirt` with `pushEntitiesUp`).
27. `SweetBerryBushBlock.java:67-79`, `CocoaBlock.java:47-59`, `NetherWartBlock.java:47-58` (age progression and flags).
28. `ChorusFlowerBlock.java:54-136` (upward growth, branching, levelEvents 1033/1034).
29. `BuddingAmethystBlock.java:27-50` (6-direction bud to cluster progression).
30. `TurtleEggBlock.java:95-139` (sand check, night hatch chance attribute, crack sound, baby turtle entity creation).
31. `MushroomBlock.java:48-75` (max 5 in 9x3x9 box, 4 random walk jumps).
32. `PointedDripstoneBlock.java:85-132`, `SpeleothemBlock.java:291-318`, `SulfurSpikeBlock.java:8-32` (mud to clay, cauldron transfer, stalactite/stalagmite elongation).
33. `EyeblossomBlock.java:62-95` and `FlowerPotBlock.java:136-154` (day/night open/closed transformation, neighbor cascade scheduling).
34. `NetherPortalBlock.java:69-90` (difficulty-scaled zombified piglin spawning).
35. `RedStoneOreBlock.java:82-91` (unlighting lit ore with flags 3).
36. `VineBlock.java:167-240` (multi-directional growth under `GameRules.SPREAD_VINES`).
37. `CauldronBlock.java:42-52` and `LayeredCauldronBlock.java:116-122` (precipitation fill probabilities, level cycling, analog comparator output).

### 7.2 Unverified Items
The following secondary interactions were not directly checked in the 26.2 jar and are marked as unverified:
1. *Bee pollination growth bonus:* The exact code path where bees pollinate crops (`Bee.java`); crop growth triggered by bees is part of mob AI (G7/Phase C), not base chunk random ticks.
2. *Raid spawn precipitation interaction:* Unverified whether rain/snow alters pillager raid pathfinding or spawn positioning in 26.2.
3. *Light update micro-benchmarks:* The exact microsecond cost of `LevelChunkSection.recalcBlockCounts` during rapid block churning in high-frequency redstone contraptions.
4. *End gateway random ticks:* Unverified whether End gateways or End dimension portal beams participate in any dimension-specific random tick loops.
