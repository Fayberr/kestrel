# G7 Pre-Research: Mobs and Animals Architecture and Parity Specification

Status: PRE-RESEARCH, grounded in the decompiled vanilla 26.2 server jar (`server-26.2.jar`) decompiled via Vineflower 1.11.1, datapack game data under `packdump/data`, and existing Kestrel implementations in `core/src/vanilla/java/dev/kestrel/`.
Target: Ground truth specification and architecture roadmap for item G7 (mobs and animals) in Kestrel.
Scope: Specification analysis, vanilla code audits, networking definitions, and parity verification plans. No Java source changes, no builds, no servers.

---

## 1. Scope: Target Set and AI Architecture

### 1.1 First Target Set Proposal

The initial target set is partitioned into two functional groups:

1. **Passive Animals (Slice A):** Cow (`Cow.java:32`), Pig (`Pig.java:64`), Sheep (`Sheep.java:58`), Chicken (`Chicken.java:58`).
2. **Hostile Monsters (Slice B):** Zombie (`Zombie.java:72`), Skeleton (`Skeleton.java:17`), Creeper (`Creeper.java:45`), Spider (`Spider.java:48`).

This target set represents the bedrock of Minecraft Overworld entity mechanics. Implementing these eight mobs exercises all core entity subsystems without requiring external dimension logic, boss overlays, or complex memory-blackboard structures.

### 1.2 Class Inheritance Hierarchies

All eight mobs share a deep, strictly layered inheritance hierarchy rooted in `Entity.java` and `Mob.java`:

```
Entity (Entity.java:163)
  └── LivingEntity (LivingEntity.java:141)
        └── Mob (Mob.java:99)
              └── PathfinderMob (PathfinderMob.java:13)
                    ├── AgeableMob (AgeableMob.java:28)
                    │     └── Animal (Animal.java:43)
                    │           ├── AbstractCow (AbstractCow.java:32)
                    │           │     └── Cow (Cow.java:32)
                    │           ├── Pig (Pig.java:64)
                    │           ├── Sheep (Sheep.java:58)
                    │           └── Chicken (Chicken.java:58)
                    └── Monster (Monster.java:30)
                          ├── Zombie (Zombie.java:72)
                          ├── AbstractSkeleton (AbstractSkeleton.java:51)
                          │     └── Skeleton (Skeleton.java:17)
                          ├── Creeper (Creeper.java:45)
                          └── Spider (Spider.java:48)
```

The inheritance structure establishes exact code sharing boundaries:

| Class | Source Location | Key Responsibilities Introduced |
|---|---|---|
| `Entity` | `net/minecraft/world/entity/Entity.java:163` | Position (`Vec3`), velocity (`deltaMovement`), bounding box (`AABB`), collision sweep (`move`, `collide`), section indexing, networking ID (`id`), entity data (`entityData`), random source (`random`). |
| `LivingEntity` | `net/minecraft/world/entity/LivingEntity.java:141` | Health, damage pipeline (`hurtServer`), attributes (`attributes`), movement physics (`travel`, `travelInAir`, `travelInFluid`), friction, gravity, jumping, death (`die`), drop loot. |
| `Mob` | `net/minecraft/world/entity/Mob.java:99` | AI controllers (`goalSelector`, `targetSelector`), look/move/jump controls (`LookControl`, `MoveControl`, `JumpControl`), sensing (`Sensing`), navigation (`PathNavigation`), despawn check (`checkDespawn`), equipment. |
| `PathfinderMob` | `net/minecraft/world/entity/PathfinderMob.java:13` | Restricts pathfinding bounds, leash mechanics, spawn restriction rules. |
| `AgeableMob` | `net/minecraft/world/entity/AgeableMob.java:28` | Age tracking (`age`), baby scaling dimensions, baby growth countdown. |
| `Animal` | `net/minecraft/world/entity/animal/Animal.java:43` | In-love breeding countdown (`inLove`), mate attraction, player food interaction, animal spawn rules (`checkAnimalSpawnRules`), vetoes despawning (`removeWhenFarAway = false`). |
| `Monster` | `net/minecraft/world/entity/monster/Monster.java:30` | Hostility tagging (`Enemy`), light level spawn checks (`checkMonsterSpawnRules`, `isDarkEnoughToSpawn`), daylight burning checks, attack damage attribute. |

### 1.3 Goal-Based vs Brain-Based AI in Vanilla 26.2

Vanilla 26.2 contains two distinct AI execution engines:
1. **Goal-Based AI (`GoalSelector`):** Priority-ordered list of discrete `Goal` objects with active flag locking (`Goal.Flag`). Ticked directly in `Mob.serverAiStep` (`Mob.java:731-746`).
2. **Brain-Based AI (`Brain`):** Blackboard architecture using `MemoryModuleType`, `Sensor`, and priority-indexed `Activity` behaviors. Ticked via `Brain.tick` inside `LivingEntity.aiStep` (`LivingEntity.java:2407`).

Every mob in vanilla 26.2 inherits a default `Brain` instance (`LivingEntity.java:303`), but only mobs that override `makeBrain` actually configure sensors and behavior schedules. In the 26.2 server jar, exactly 20 entity types override `makeBrain`:

| Brain-Based Entity | Class Location in 26.2 Source | Override Location |
|---|---|---|
| `Villager` | `net/minecraft/world/entity/npc/villager/Villager.java:197` | `makeBrain(Brain.Packed)` |
| `Piglin` | `net/minecraft/world/entity/monster/piglin/Piglin.java:217` | `makeBrain(Brain.Packed)` |
| `PiglinBrute` | `net/minecraft/world/entity/monster/piglin/PiglinBrute.java:73` | `makeBrain(Brain.Packed)` |
| `Hoglin` | `net/minecraft/world/entity/monster/hoglin/Hoglin.java:133` | `makeBrain(Brain.Packed)` |
| `Zoglin` | `net/minecraft/world/entity/monster/Zoglin.java:87` | `makeBrain(Brain.Packed)` |
| `Warden` | `net/minecraft/world/entity/monster/warden/Warden.java:375` | `makeBrain(Brain.Packed)` |
| `Breeze` | `net/minecraft/world/entity/monster/breeze/Breeze.java:88` | `makeBrain(Brain.Packed)` |
| `Creaking` | `net/minecraft/world/entity/monster/creaking/Creaking.java:115` | `makeBrain(Brain.Packed)` |
| `Frog` | `net/minecraft/world/entity/animal/frog/Frog.java:96` | `makeBrain(Brain.Packed)` |
| `Tadpole` | `net/minecraft/world/entity/animal/frog/Tadpole.java:71` | `makeBrain(Brain.Packed)` |
| `Axolotl` | `net/minecraft/world/entity/animal/axolotl/Axolotl.java:537` | `makeBrain(Brain.Packed)` |
| `Allay` | `net/minecraft/world/entity/animal/allay/Allay.java:117` | `makeBrain(Brain.Packed)` |
| `Camel` | `net/minecraft/world/entity/animal/camel/Camel.java:152` | `makeBrain(Brain.Packed)` |
| `Goat` | `net/minecraft/world/entity/animal/goat/Goat.java:109` | `makeBrain(Brain.Packed)` |
| `Armadillo` | `net/minecraft/world/entity/animal/armadillo/Armadillo.java:133` | `makeBrain(Brain.Packed)` |
| `Sniffer` | `net/minecraft/world/entity/animal/sniffer/Sniffer.java:443` | `makeBrain(Brain.Packed)` |
| `CopperGolem` | `net/minecraft/world/entity/animal/golem/CopperGolem.java:152` | `makeBrain(Brain.Packed)` |
| `Nautilus` | `net/minecraft/world/entity/animal/nautilus/Nautilus.java:41` | `makeBrain(Brain.Packed)` |
| `ZombieNautilus` | `net/minecraft/world/entity/animal/nautilus/ZombieNautilus.java:66` | `makeBrain(Brain.Packed)` |
| `HappyGhast` | `net/minecraft/world/entity/animal/happyghast/HappyGhast.java:393` | `makeBrain(Brain.Packed)` |

**Conclusion and Boundary:** All eight target mobs (Cow, Pig, Sheep, Chicken, Zombie, Skeleton, Creeper, Spider) use **Goal-Based AI exclusively**. None of them override `makeBrain`. Their AI state is completely contained within `Mob.goalSelector` and `Mob.targetSelector`. Brain-based mobs are strictly out of scope for G7; they depend on POI managers, section storage tickets, memory TTLs, and schedule providers that add massive surface area without benefiting standard mobs.

---

## 2. Entity Lifecycle Inside the Server Tick

### 2.1 Placement in `ServerLevel.tick`

The server tick sequence in `ServerLevel.tick(BooleanSupplier)` (`ServerLevel.java:354-468`) executes subsystems in a strict, unvarying order:

1. **World border:** `this.getWorldBorder().tick()` (`ServerLevel.java:362`).
2. **Weather cycle:** `this.advanceWeatherCycle()` (`ServerLevel.java:364`).
3. **Player sleep status & world clocks:** `this.sleepStatus...` (`ServerLevel.java:368-379`).
4. **Time advancement:** `this.tickTime()` (`ServerLevel.java:383`).
5. **Scheduled block ticks:** `this.blockTicks.tick(tick, 65536, this::tickBlock)` (`ServerLevel.java:390`).
6. **Scheduled fluid ticks:** `this.fluidTicks.tick(tick, 65536, this::tickFluid)` (`ServerLevel.java:392`).
7. **Raid coordinator:** `this.raids.tick(this)` (`ServerLevel.java:398`).
8. **Chunk source tick (chunk unload / ticket drain):** `this.getChunkSource().tick(haveTime, true)` (`ServerLevel.java:402`).
9. **Block events (piston motions, chest latches):** `this.runBlockEvents()` (`ServerLevel.java:405`).
10. **Entities (`entityTickList` iteration):** `profiler.push("entities")` (`ServerLevel.java:420-449`).
11. **Block entities (`tickBlockEntities`):** `this.tickBlockEntities()` (`ServerLevel.java:451`).
12. **Entity manager queue flush:** `this.entityManager.tick()` (`ServerLevel.java:456`).
13. **Debug synchronizers:** `this.debugSynchronizers.tick(...)` (`ServerLevel.java:466`).

Entities tick strictly AFTER scheduled block ticks, fluid ticks, and block events have settled for the tick, and strictly BEFORE block entities tick.

### 2.2 `EntityTickList`, `guardEntityTick`, and Passenger Gating

Inside `ServerLevel.tick` (`ServerLevel.java:427-449`), entity ticking is gated by `this.emptyTime < 300`:

> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

Key lifecycle mechanics:
- **`EntityTickList.java:12-66`:** Implements active and passive `Int2ObjectLinkedOpenHashMap` maps. When `forEach` starts (`EntityTickList.java:48`), `this.iterated = this.active`. Any concurrent additions or removals trigger `ensureActiveIsNotIterated()` (`EntityTickList.java:18-32`), which copies `active` into `passive` and swaps references, ensuring safe iteration without allocation.
- **Despawn precedes distance check:** `entity.checkDespawn()` (`ServerLevel.java:431`) is evaluated on EVERY unfrozen entity in `entityTickList`, even if the entity is currently outside the entity ticking range.
- **Entity ticking range:** `DistanceManager.inEntityTickingRange(long chunkPos)` (`ServerLevel.java:433`). Mobs in chunks with ticket levels above 31 (i.e. only `TRACKED` or `BORDER`) do not execute non-passenger ticks.
- **Passenger inversion:** If an entity has a valid vehicle, its direct tick is skipped (`return;`). The vehicle ticks all its passengers inside `ServerLevel.tickPassenger` (`ServerLevel.java:838-853`), invoking `entity.rideTick()` instead of `entity.tick()`.
- **`guardEntityTick` (`Level.java:556-565`):** Wraps `tick.accept(entity)` in a try-catch block catching `Throwable`. On error, it populates a `CrashReportCategory` via `entity.fillCrashReportCategory` and re-throws as `ReportedException`.
- **`tickNonPassenger` (`ServerLevel.java:824-836`):**
  1. `entity.setOldPosAndRot()`: copies current coordinates and rotations to `xOld`, `yOld`, `zOld`, `yRotO`, `xRotO`.
  2. `entity.tickCount++`.
  3. `entity.tick()`: runs entity-specific tick logic (`LivingEntity.tick` -> `aiStep` -> `serverAiStep` -> movement -> collisions).
  4. Recursively ticks all passengers via `tickPassenger`.

### 2.3 Entity Sections and Chunk Visibility

Entity storage is managed by `PersistentEntitySectionManager<Entity>` (`PersistentEntitySectionManager.java:30-464`), instantiated in `ServerLevel.java:258`.

Entities are bucketed into 16x16x16 spatial sections (`EntitySection.java`). Section visibility is synchronized with chunk loading tickets through `Visibility` (`Visibility.java:5-33`):
- `Visibility.HIDDEN`: Chunk not loaded or beyond simulation distance (`accessible = false`, `ticking = false`).
- `Visibility.TRACKED`: Chunk loaded at status `FULL` or `BLOCK_TICKING` (`accessible = true`, `ticking = false`). Entities can be queried by spatial searches and raycasts, but do not tick.
- `Visibility.TICKING`: Chunk loaded at status `ENTITY_TICKING` (ticket level <= 31) (`accessible = true`, `ticking = true`).

When `PersistentEntitySectionManager.updateChunkStatus` (`PersistentEntitySectionManager.java:140-171`) transitions a chunk between visibility states:
- Transition to `TICKING`: triggers `startTicking(entity)` -> `LevelCallback.onTickingStart(entity)` (`ServerLevel.java:1928`), which adds the entity to `ServerLevel.this.entityTickList`.
- Transition away from `TICKING`: triggers `stopTicking(entity)` -> `LevelCallback.onTickingEnd(entity)` (`ServerLevel.java:1932`), which removes the entity from `entityTickList`.
- Transition to `TRACKED`: triggers `startTracking(entity)` -> `ServerLevel.this.getChunkSource().addEntity(entity)` (`ServerLevel.java:1937`), creating a `ChunkMap.TrackedEntity` network pipeline.

### 2.4 Despawning Rules

Despawning is evaluated in `Mob.checkDespawn()` (`Mob.java:698-722`):

> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

Detailed despawn constraints:
- **Persistence Immunity:** Despawning is completely aborted if `isPersistenceRequired()` is true (`Mob.java:1136`) or `requiresCustomPersistence()` is true (`Mob.java:693`).
  - `requiresCustomPersistence()` returns `this.isPassenger() || this.isLeashed()`.
  - `persistenceRequired` is set to `true` when a mob is nametagged, bred by a player, given custom equipment (`Mob.java:563`), or spawned by certain commands/structures.
- **The Animal Veto (`removeWhenFarAway`):**
  - In `Mob.java:689`, `removeWhenFarAway(distSqr)` returns `true` by default (monsters despawn).
  - In `Animal.java:129-131`, `removeWhenFarAway(distSqr)` is overridden to return `false`.
  - **Critical Consequence:** Animals (cows, pigs, sheep, chickens) **never despawn naturally**, even when 10,000 blocks away from any player, unless difficulty is peaceful and the mob is disallowed.
- **Distance Boundaries (`MobCategory.java:7-24`):**
  - `noDespawnDistance = 32` blocks across all categories (`noDespawnDistanceSqr = 1024.0`).
  - `despawnDistance = 128` blocks for MONSTER, CREATURE, AMBIENT (`despawnDistanceSqr = 16384.0`). For WATER_AMBIENT, it is 64 blocks (`despawnDistanceSqr = 4096.0`).
- **Despawn Timer & Random Draw:**
  - If a monster is between 32 and 128 blocks from the nearest player, it increments `noActionTime` each tick (`Mob.java:726`).
  - Once `noActionTime > 600` (30 seconds of inactivity), it draws `this.random.nextInt(800) == 0` every tick (1/800 probability per tick, half-life of ~27.7 seconds).
  - If the player approaches within 32 blocks, `noActionTime` is immediately reset to 0 (`Mob.java:716`).

---

## 3. Natural Spawning and Chunk Generation Spawning

### 3.1 `NaturalSpawner` Global Architecture

Natural spawning is coordinated once per tick in `ServerChunkCache.tickChunks` (`ServerChunkCache.java:369-420`):

1. **`NaturalSpawner.createState` (`NaturalSpawner.java:67-98`):**
   - Counts all loaded, non-persistent entities in `level.getAllEntities()` (`NaturalSpawner.java:76-95`).
   - Aggregates mob counts by `MobCategory` into `Object2IntOpenHashMap<MobCategory>`.
   - Populates `PotentialCalculator` charge points for entities with biome spawn costs (`NaturalSpawner.java:84`).
   - Populates `LocalMobCapCalculator` per player within 128 blocks of chunk centers (`NaturalSpawner.java:88`).
2. **Category Global Cap Gate (`NaturalSpawner.java:104-116`):**
   - Iterates `SPAWNING_CATEGORIES` (all categories except `MISC`).
   - Evaluates `state.canSpawnForCategoryGlobal(mobCategory)` (`NaturalSpawner.java:538`):
     $$\text{mobCount} < \frac{\text{maxPerChunk} \times \text{spawnableChunkCount}}{289}$$
     where 289 is $17^2$ (`MAGIC_NUMBER`, `NaturalSpawner.java:64`).
3. **Chunk Candidate Collection & Shuffle:**
   - Collects all ticking chunks with a player within 128 blocks (`ChunkMap.collectSpawningChunks`).
   - Shuffles the chunk list with `Util.shuffle(spawningChunks, level.getRandom())` (`Util.java:991`). This consumes Fisher-Yates random draws on the level RNG every tick.
4. **Per-Chunk Category Iteration (`NaturalSpawner.spawnForChunk`, lines 118-131):**
   - For each active category, checks local cap: `state.canSpawnForCategoryLocal(mobCategory, chunk.getPos())` (`NaturalSpawner.java:125`).

### 3.2 Spawning Attempt Algorithm (`spawnCategoryForPosition`)

When a chunk and category pass all cap gates, `spawnCategoryForChunk` picks an initial position (`NaturalSpawner.java:140`):
- `start = getRandomPosWithin(level, chunk)` (`NaturalSpawner.java:341-348`):
  - $x = \text{chunkMinX} + \text{random.nextInt}(16)$
  - $z = \text{chunkMinZ} + \text{random.nextInt}(16)$
  - $topEmptyY = \text{chunk.getHeight(Heightmap.Types.WORLD\_SURFACE}, x, z) + 1$
  - $y = \text{Mth.randomBetweenInclusive}(\text{level.random}, \text{level.getMinY}(), topEmptyY)$

If $y \ge \text{level.getMinY}() + 1$ and `!state.isRedstoneConductor(chunk, start)`, `spawnCategoryForPosition` (`NaturalSpawner.java:151-226`) begins:

- Loops up to 3 groups (`groupCount = 0..2`):
  - Resets $(x, z)$ to start position.
  - Draws group attempt limit: `max = Mth.ceil(level.random.nextFloat() * 4.0F)` (1 to 4 attempts).
  - Loops `ll = 0` to `max - 1`:
    - Jitters coordinates: $x \mathrel{+}= \text{random.nextInt}(6) - \text{random.nextInt}(6)$, $z \mathrel{+}= \text{random.nextInt}(6) - \text{random.nextInt}(6)$ (`NaturalSpawner.java:177-178`).
    - Finds nearest player to $(x + 0.5, yStart, z + 0.5)$. If null, try fails.
    - Evaluates distance gates (`NaturalSpawner.java:228-243`):
      - Distance squared to player must be $> 576.0$ ($> 24$ blocks).
      - Must not be within 24 blocks of the world spawn point in the same dimension.
      - Target chunk must equal candidate chunk or pass `level.canSpawnEntitiesInChunk(chunkPos)`.
    - If `currentSpawnData == null`, draws mob type from biome spawn list:
      - `getRandomSpawnMobAt(level, structureManager, generator, mobCategory, level.random, pos)` (`NaturalSpawner.java:292-304`).
      - In `WATER_AMBIENT`, if biome is `REDUCED_WATER_AMBIENT_SPAWNS`, draws `random.nextFloat() < 0.98F` to veto spawn (`NaturalSpawner.java:301`).
      - Draws weighted entry from `mobsAt(...)` using `level.random`.
      - Rerolls pack size: `max = currentSpawnData.minCount() + level.random.nextInt(1 + currentSpawnData.maxCount() - currentSpawnData.minCount())` (`NaturalSpawner.java:195`).
    - Validates spawn placement: `isValidSpawnPostitionForType` (`NaturalSpawner.java:245-268`):
      - `SpawnPlacements.isSpawnPositionOk(type, level, pos)`
      - `SpawnPlacements.checkSpawnRules(type, level, NATURAL, pos, level.random)`
      - `level.noCollision(type.getSpawnAABB(x + 0.5, y, z + 0.5))`
    - Spawns entity:
      - Snaps to position: `mob.snapTo(xx, yStart, zz, level.random.nextFloat() * 360.0F, 0.0F)`.
      - Secondary check: `isValidPositionForMob`: `mob.checkSpawnRules` and `mob.checkSpawnObstruction(level)`.
      - Finalizes spawn: `mob.finalizeSpawn(level, difficulty, NATURAL, groupData)`.
      - Inserts: `level.addFreshEntityWithPassengers(mob)`.
      - Fires callback: `afterSpawn` updates charge in `PotentialCalculator`, increments category counts.

### 3.3 Spawn Rules and Light Checks (`SpawnPlacements`)

In `SpawnPlacements.java`, all eight target mobs declare explicit placement rules and heightmaps:

| Entity Type | Placement Type | Heightmap | Rule Predicate | Detailed Light & Block Rule |
|---|---|---|---|---|
| `COW` | `ON_GROUND` | `MOTION_BLOCKING_NO_LEAVES` | `Animal::checkAnimalSpawnRules` (`SpawnPlacements.java:116`) | Ground block is in `#minecraft:animals_spawnable_on` (grass, dirt, etc.) AND `level.getRawBrightness(pos, 0) > 8` (`Animal.java:116, 120`). |
| `PIG` | `ON_GROUND` | `MOTION_BLOCKING_NO_LEAVES` | `Animal::checkAnimalSpawnRules` (`SpawnPlacements.java:139`) | Same as Cow. |
| `SHEEP` | `ON_GROUND` | `MOTION_BLOCKING_NO_LEAVES` | `Animal::checkAnimalSpawnRules` (`SpawnPlacements.java:147`) | Same as Cow. |
| `CHICKEN` | `ON_GROUND` | `MOTION_BLOCKING_NO_LEAVES` | `Animal::checkAnimalSpawnRules` (`SpawnPlacements.java:115`) | Same as Cow. |
| `CREEPER` | `ON_GROUND` | `MOTION_BLOCKING_NO_LEAVES` | `Monster::checkMonsterSpawnRules` (`SpawnPlacements.java:117`) | Ground block `isValidSpawn`, sky brightness $\le \text{random.nextInt}(32)$, block light $\le 0$, total local raw brightness $\le \text{monsterSpawnLightTest().sample(random)}$ (`Monster.java:85-104`). |
| `SKELETON` | `ON_GROUND` | `MOTION_BLOCKING_NO_LEAVES` | `Monster::checkMonsterSpawnRules` (`SpawnPlacements.java:149`) | Same as Creeper. |
| `SPIDER` | `ON_GROUND` | `MOTION_BLOCKING_NO_LEAVES` | `Monster::checkMonsterSpawnRules` (`SpawnPlacements.java:155`) | Same as Creeper. |
| `ZOMBIE` | `ON_GROUND` | `MOTION_BLOCKING_NO_LEAVES` | `Monster::checkMonsterSpawnRules` (`SpawnPlacements.java:167`) | Same as Creeper. |

### 3.4 Biome Spawn Lists from the Datapack

Datapack JSON files under `packdump/data/minecraft/worldgen/biome/` define spawn tables. For example, `plains.json` (`packdump/data/minecraft/worldgen/biome/plains.json:84-199`):

- **Creature (`CREATURE`, cap 10):**
  - `minecraft:sheep`: weight 12, minCount 4, maxCount 4 (`plains.json:96-100`).
  - `minecraft:pig`: weight 10, minCount 4, maxCount 4 (`plains.json:102-106`).
  - `minecraft:chicken`: weight 10, minCount 4, maxCount 4 (`plains.json:108-112`).
  - `minecraft:cow`: weight 8, minCount 4, maxCount 4 (`plains.json:114-118`).
  - `minecraft:horse`: weight 5, minCount 2, maxCount 6 (`plains.json:120-124`).
  - `minecraft:donkey`: weight 1, minCount 1, maxCount 3 (`plains.json:126-130`).
- **Monster (`MONSTER`, cap 70):**
  - `minecraft:spider`: weight 100, minCount 4, maxCount 4 (`plains.json:135-139`).
  - `minecraft:zombie`: weight 90, minCount 4, maxCount 4 (`plains.json:141-145`).
  - `minecraft:skeleton`: weight 100, minCount 4, maxCount 4 (`plains.json:159-163`).
  - `minecraft:creeper`: weight 100, minCount 4, maxCount 4 (`plains.json:165-169`).
  - `minecraft:slime`: weight 100, minCount 4, maxCount 4 (`plains.json:171-175`).
  - `minecraft:enderman`: weight 10, minCount 1, maxCount 4 (`plains.json:177-181`).
  - `minecraft:witch`: weight 5, minCount 1, maxCount 1 (`plains.json:183-187`).
  - `minecraft:zombie_villager`: weight 5, minCount 1, maxCount 1 (`plains.json:147-151`).
  - `minecraft:zombie_horse`: weight 5, minCount 1, maxCount 1 (`plains.json:153-157`).

### 3.5 Spawning During Chunk Generation (`spawnMobsForChunkGeneration`)

Chunk generation populates initial animal groups when a chunk reaches `FULL` status (`NaturalSpawner.spawnMobsForChunkGeneration`, lines 364-435):

1. **Probability Gate:**
   `while (random.nextFloat() < mobSettings.getCreatureProbability())` (`NaturalSpawner.java:373`).
   For plains and forest biomes, `creatureProbability` is typically 0.1 (10% chance per group).
2. **Pack Selection:**
   Draws random entry from `mobs.getRandom(random)` (`NaturalSpawner.java:374`).
   Draws pack count: `count = minCount + random.nextInt(1 + maxCount - minCount)` (`NaturalSpawner.java:377`).
3. **Anchor Selection:**
   $x = \text{chunkMinX} + \text{random.nextInt}(16)$, $z = \text{chunkMinZ} + \text{random.nextInt}(16)$.
4. **Member Placement (up to `count` times):**
   - For each member, makes up to 4 attempts (`attempts = 0..3`, line 387):
     - Probes top surface: `getTopNonCollidingPos(level, type, x, z)` via `SpawnPlacements.getHeightmapType(type)` (`NaturalSpawner.java:437-439`).
     - Clamps coordinates inside chunk bounds accounting for entity width:
       `fx = Mth.clamp(x, xo + width, xo + 16.0 - width)` (`NaturalSpawner.java:391`).
     - Evaluates collision and spawn rules with `EntitySpawnReason.CHUNK_GENERATION`. Note that `Animal.checkAnimalSpawnRules` still checks `isBrightEnoughToSpawn` (> 8 light) unless reason is `TRIAL_SPAWNER` (`EntitySpawnReason.java:28-30`).
     - Sets yaw: `entity.snapTo(fx, y, fz, random.nextFloat() * 360.0F, 0.0F)` (`NaturalSpawner.java:412`).
     - Invokes `mob.finalizeSpawn(..., CHUNK_GENERATION, groupSpawnData)` and adds to level.
     - Jitters position for next member:
       $x \mathrel{+}= \text{random.nextInt}(5) - \text{random.nextInt}(5)$ (`NaturalSpawner.java:422`),
       $z \mathrel{+}= \text{random.nextInt}(5) - \text{random.nextInt}(5)$, re-rolling if leaving chunk boundaries.

---

## 4. Movement and Physics for a Basic Mob

### 4.1 The `travel` and `travelInAir` Pipeline

Mob physics executes inside `LivingEntity.travel(Vec3 input)` (`LivingEntity.java:2433-2441`):

> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

In standard land movement (`travelInAir`, `LivingEntity.java:2471-2498`):

1. **Ground Friction:**
   - Evaluates block position below: `posBelow = this.getBlockPosBelowThatAffectsMyMovement()` (`LivingEntity.java:2472`).
   - If `onGround()`: `blockFriction = computeModifiedFriction(block.getFriction(), frictionModifier)` (`LivingEntity.java:2474`). For stone/dirt/grass, friction is 0.6F; for ice, 0.98F.
   - If not `onGround()`: `blockFriction = 1.0F`.
2. **Horizontal Acceleration:**
   - Evaluates `handleRelativeFrictionAndCalculateMovement(input, blockFriction)` (`LivingEntity.java:2677-2687`):
     - `this.moveRelative(this.getFrictionInfluencedSpeed(blockFriction), input)`:
       Calculates movement speed: $S = \text{onGround} ? \text{speed} \times \left(\frac{0.21600002F}{\text{blockFriction}^3}\right) : \text{flyingSpeed}$ (0.02F for walking mobs in air).
       Projects input vector $(xxa, yya, zza)$ onto world $(X, Z)$ based on entity body rotation `yRot`.
     - `this.move(MoverType.SELF, this.getDeltaMovement())`.
3. **Gravity & Air Drag:**
   - Gravity: `movementY -= this.getEffectiveGravity()` (`LivingEntity.java:2482`). Default gravity is 0.08 blocks/tick$^2$ (`Attributes.GRAVITY`).
   - Drag application (`LivingEntity.java:2493-2496`):
     - Horizontal drag: $\text{airDrag} = 0.91\text{F}$. $\text{friction} = \text{blockFriction} \times \text{airDrag}$.
     - Vertical drag: $0.98\text{F}$ (`computeModifiedFriction(0.98F, airDragModifier)`).
     - Resulting velocity: $\Delta X' = \Delta X \times \text{friction}$, $\Delta Y' = \Delta Y \times 0.98$, $\Delta Z' = \Delta Z \times \text{friction}$.

### 4.2 Collision Sweep and Step Height (`Entity.move` and `Entity.collide`)

`Entity.move(MoverType, Vec3 delta)` (`Entity.java:722-811`) and `Entity.collide` (`Entity.java:1150-1179`):

1. **Bounding Box Expansion:**
   - Entity bounding box (`aabb`) is expanded by `delta`: `aabb.expandTowards(delta)`.
2. **Collider Collection:**
   - Collects block collision shapes in sweep volume (`Entity.collideBoundingBox`, line 1209).
   - Collects entity colliders via `level.getEntityCollisions(this, expandedBox)` (`Entity.java:1152`).
3. **Axis Resolution (`collideWithShapes`):**
   - Resolves Y axis first: `Shapes.collide(Direction.Axis.Y, aabb, colliders, delta.y)`.
   - Resolves horizontal axes (X and Z) ordered by magnitude: larger horizontal displacement resolves first, smaller second.
4. **Step-Up Second Pass (Auto-Step onto Slabs and Stairs):**
   - Evaluated if `maxUpStep > 0.0F` (standard mob step height is 0.6F), entity is on ground or landing on ground, and a horizontal collision occurred (`Entity.java:1158`).
   - Grounded AABB is expanded upward by `maxUpStep`.
   - Collects candidate step-up heights from all colliders within the step volume via `collectCandidateStepUpHeights` (`Entity.java:1181-1206`).
   - For each candidate height, simulates horizontal motion from the stepped height. If horizontal displacement exceeds the ground-level collision displacement, the step succeeds and entity is lifted.
5. **Collision Flag Restitution:**
   - Sets `horizontalCollision = (delta.x != movement.x || delta.z != movement.z)`.
   - Sets `verticalCollision = (delta.y != movement.y)`.
   - Sets `verticalCollisionBelow = (verticalCollision && delta.y < 0.0)`.
   - Updates `onGround` status.
   - Clears velocity on collided axes (`restituteMovementAfterCollisions`, line 813).

### 4.3 Fluid Physics and Fall Damage

- **Water Travel (`travelInWater`, `LivingEntity.java:2517-2544`):**
  - Horizontal movement speed reduced: `moveRelative(0.02F, input)`.
  - Water drag multiplier: `movement.multiply(0.8F, 0.8F, 0.8F)`.
  - Buoyancy: `getFluidFallingAdjustedMovement` (`LivingEntity.java:2689-2702`) applies reduced gravity ($g / 16.0$) and Terminal falling speed adjustment (-0.003).
  - Floating: swimming mobs jump automatically in water via `jumpControl` or `FloatGoal`.
- **Lava Travel (`travelInLava`, `LivingEntity.java:2550`):**
  - High drag: velocity scaled by $0.5$ (`LivingEntity.java:2463`).
- **Fall Damage (`LivingEntity.checkFallDamage`, lines 1888-1920):**
  - When in air and falling ($\Delta Y < 0$), accumulates `fallDistance -= movement.y`.
  - On landing (`onGround` becomes true): computes fall damage if `fallDistance > 3.0F`:
    $$\text{damage} = \lceil\text{fallDistance} - 3.0\text{F}\rceil$$
  - Applied as `damageSources().fall()` (`LivingEntity.java:1907`).
  - Landing in water or on cobweb/slime resets `fallDistance` to 0.

### 4.4 Mapping to Existing Kestrel Code

Kestrel has a production-tested collision kernel in `core/src/vanilla/java/dev/kestrel/collision/`:

| Vanilla Physics Feature | Vanilla Class:Line | Kestrel Status in `dev/kestrel/collision` | What is Missing in Kestrel |
|---|---|---|---|
| Swept block collision bounds | `BlockCollisions.java:55` | Implemented in `KestrelCollide.collectBlockColliders` (`KestrelCollide.java:72-141`). | None (matches vanilla sweep box and `Cursor3D` corner skip). |
| Axis resolution order | `Entity.java:1208`, `Shapes.java` | Implemented in `KestrelCollide.collideWithShapes` (`KestrelCollide.java:150-183`). | None (resolves Y first, then X/Z by magnitude). |
| Fast shape overlap cache | `VoxelShape.java` | Implemented in `ShapeBoxCache.java:1-120`. | None (cached against `BlockState` collision shapes). |
| Step-up pass & candidate heights | `Entity.java:1158-1206` | Implemented in `KestrelCollide.collide` and `candidateStepUpHeights` (`KestrelCollide.java:201-262`). | Needs wiring to entity `travel` loop. |
| Entity-vs-entity push colliders | `Level.java:getEntityCollisions` | **MISSING** in Kestrel. | Querying nearby entity AABBs through section storage. |
| World border collision | `WorldBorder.java` | **MISSING** in Kestrel collision. | Clamping movement against border shape. |
| Ground friction & air drag | `LivingEntity.java:2471-2498` | **MISSING** in Kestrel. | The entire `travelInAir` equation pipeline. |
| Fluid movement & buoyancy | `LivingEntity.java:2505-2544` | **MISSING** in Kestrel. | Fluid drag and buoyancy adjustments. |
| Fall distance & damage | `LivingEntity.java:1888-1920` | **MISSING** in Kestrel. | Accumulating distance and invoking damage sources. |

---

## 5. AI for the Target Set

### 5.1 `GoalSelector` Scheduling Engine

The `GoalSelector` (`GoalSelector.java:1-128`) orchestrates active goals using priority ordering and flag mutual exclusion:

1. **Flag Invariants (`Goal.Flag`):** Four control flags exist: `MOVE`, `LOOK`, `JUMP`, `TARGET`. Two goals sharing any flag cannot run simultaneously.
2. **Alternating Tick Schedule:**
   In `Mob.serverAiStep` (`Mob.java:731-746`):
   > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
   - **Odd Ticks:** Evaluates only `tickRunningGoals(false)`. Running goals are ticked only if `goal.requiresUpdateEveryTick()` is true. No goal start/stop checks occur!
   - **Even Ticks:** Evaluates full `GoalSelector.tick()` (`GoalSelector.java:66-94`):
     - **Cleanup:** Stops any running goal if `!goal.canContinueToUse()` or its flags are disabled.
     - **Update:** For each available goal not currently running, if not disabled, can replace current goals on all its flags (i.e. `newGoal.getPriority() <= currentGoal.getPriority()`), and `goal.canUse()` returns true:
       - Stops previous flag owners.
       - Locks flags to the new goal.
       - Calls `goal.start()`.
     - Calls `tickRunningGoals(true)`.

### 5.2 Registered Goals for the Target Set

Every target mob registers its goals inside `registerGoals()`:

#### Cow (`AbstractCow.java:40-49`)
- **Prio 0:** `FloatGoal(this)` (jumps when in water, Flag: `JUMP`).
- **Prio 1:** `PanicGoal(this, 2.0)` (runs at 2.0x speed when hurt, Flag: `MOVE`).
- **Prio 2:** `BreedGoal(this, 1.0)` (seeks mate when in love, Flag: `MOVE`, `LOOK`).
- **Prio 3:** `TemptGoal(this, 1.25, i -> i.is(ItemTags.COW_FOOD), false)` (follows wheat, Flag: `MOVE`, `LOOK`).
- **Prio 4:** `FollowParentGoal(this, 1.25)` (baby follows adult cow, Flag: `MOVE`, `LOOK`).
- **Prio 5:** `WaterAvoidingRandomStrollGoal(this, 1.0)` (wanders on land, Flag: `MOVE`).
- **Prio 6:** `LookAtPlayerGoal(this, Player.class, 6.0F)` (turns head to player, Flag: `LOOK`).
- **Prio 7:** `RandomLookAroundGoal(this)` (random head rotation, Flag: `LOOK`).

#### Pig (`Pig.java:80-90`)
- **Prio 0:** `FloatGoal(this)`
- **Prio 1:** `PanicGoal(this, 1.25)`
- **Prio 3:** `BreedGoal(this, 1.0)`
- **Prio 4:** `TemptGoal(this, 1.2, carrot-on-a-stick, false)`
- **Prio 4:** `TemptGoal(this, 1.2, i -> i.is(ItemTags.PIG_FOOD), false)` (carrots, potatoes, beetroots)
- **Prio 5:** `FollowParentGoal(this, 1.1)`
- **Prio 6:** `WaterAvoidingRandomStrollGoal(this, 1.0)`
- **Prio 7:** `LookAtPlayerGoal(this, Player.class, 6.0F)`
- **Prio 8:** `RandomLookAroundGoal(this)`

#### Sheep (`Sheep.java:74-85`)
- **Prio 0:** `FloatGoal(this)`
- **Prio 1:** `PanicGoal(this, 1.25)`
- **Prio 2:** `BreedGoal(this, 1.0)`
- **Prio 3:** `TemptGoal(this, 1.1, i -> i.is(ItemTags.SHEEP_FOOD), false)` (wheat)
- **Prio 4:** `FollowParentGoal(this, 1.1)`
- **Prio 5:** `this.eatBlockGoal` (`EatBlockGoal(this)`, eats grass block or fern, regrows wool, Flag: `MOVE`, `LOOK`, `JUMP`).
- **Prio 6:** `WaterAvoidingRandomStrollGoal(this, 1.0)`
- **Prio 7:** `LookAtPlayerGoal(this, Player.class, 6.0F)`
- **Prio 8:** `RandomLookAroundGoal(this)`

#### Chicken (`Chicken.java:85-94`)
- **Prio 0:** `FloatGoal(this)`
- **Prio 1:** `PanicGoal(this, 1.4)`
- **Prio 2:** `BreedGoal(this, 1.0)`
- **Prio 3:** `TemptGoal(this, 1.0, i -> i.is(ItemTags.CHICKEN_FOOD), false)` (seeds)
- **Prio 4:** `FollowParentGoal(this, 1.1)`
- **Prio 5:** `WaterAvoidingRandomStrollGoal(this, 1.0)`
- **Prio 6:** `LookAtPlayerGoal(this, Player.class, 6.0F)`
- **Prio 7:** `RandomLookAroundGoal(this)`

#### Zombie (`Zombie.java:113-130`)
- **`goalSelector`:**
  - **Prio 2:** `SpearUseGoal<>(this, 1.0, 1.0, 10.0F, 2.0F)` (26.2 spear attack).
  - **Prio 3:** `ZombieAttackGoal(this, 1.0, false)` (melee pursuit and strike, Flag: `MOVE`, `LOOK`).
  - **Prio 4:** `ZombieAttackTurtleEggGoal(this, 1.0, 3)` (stomps turtle eggs).
  - **Prio 6:** `MoveThroughVillageGoal(this, 1.0, true, 4, this::canBreakDoors)`
  - **Prio 7:** `WaterAvoidingRandomStrollGoal(this, 1.0)`
  - **Prio 8:** `LookAtPlayerGoal(this, Player.class, 8.0F)`
  - **Prio 8:** `RandomLookAroundGoal(this)`
- **`targetSelector`:**
  - **Prio 1:** `HurtByTargetGoal(this).setAlertOthers(ZombifiedPiglin.class)` (retaliates against attacker, alerts nearby allies, Flag: `TARGET`).
  - **Prio 2:** `NearestAttackableTargetGoal<>(this, Player.class, true)` (targets survival/adventure players, Flag: `TARGET`).
  - **Prio 3:** `NearestAttackableTargetGoal<>(this, AbstractVillager.class, false)` (targets villagers).
  - **Prio 3:** `NearestAttackableTargetGoal<>(this, IronGolem.class, true)` (targets iron golems).
  - **Prio 5:** `NearestAttackableTargetGoal<>(this, Turtle.class, 10, true, false, Turtle.BABY_ON_LAND_SELECTOR)`

#### Skeleton (`AbstractSkeleton.java:81-92`)
- **`goalSelector`:**
  - **Prio 2:** `RestrictSunGoal(this)` (avoids daylight burning outdoors).
  - **Prio 3:** `FleeSunGoal(this, 1.0)` (runs to shade or water during daylight).
  - **Prio 3:** `AvoidEntityGoal<>(this, Wolf.class, 6.0F, 1.0, 1.2)` (flees wolves).
  - **Prio 5:** `WaterAvoidingRandomStrollGoal(this, 1.0)`
  - **Prio 6:** `LookAtPlayerGoal(this, Player.class, 8.0F)`
  - **Prio 6:** `RandomLookAroundGoal(this)`
- **`targetSelector`:**
  - **Prio 1:** `HurtByTargetGoal(this)`
  - **Prio 2:** `NearestAttackableTargetGoal<>(this, Player.class, true)`
  - **Prio 3:** `NearestAttackableTargetGoal<>(this, IronGolem.class, true)`
  - **Prio 3:** `NearestAttackableTargetGoal<>(this, Turtle.class, 10, true, false, Turtle.BABY_ON_LAND_SELECTOR)`
- *Note:* When holding a bow, `reassessWeaponGoal` (`AbstractSkeleton.java:119-140`) dynamically inserts `RangedBowAttackGoal<AbstractSkeleton>` at priority 4 (strafe navigation, bow draw, arrow projectile launch).

#### Creeper (`Creeper.java:64-75`)
- **`goalSelector`:**
  - **Prio 1:** `FloatGoal(this)`
  - **Prio 2:** `SwellGoal(this)` (advances explosion fuse when near target, Flag: `MOVE`, `LOOK`).
  - **Prio 3:** `AvoidEntityGoal<>(this, Ocelot.class, 6.0F, 1.0, 1.2)` (flees ocelots).
  - **Prio 3:** `AvoidEntityGoal<>(this, Cat.class, 6.0F, 1.0, 1.2)` (flees cats).
  - **Prio 4:** `MeleeAttackGoal(this, 1.0, false)` (stalks target).
  - **Prio 5:** `WaterAvoidingRandomStrollGoal(this, 0.8)`
  - **Prio 6:** `LookAtPlayerGoal(this, Player.class, 8.0F)`
  - **Prio 6:** `RandomLookAroundGoal(this)`
- **`targetSelector`:**
  - **Prio 1:** `NearestAttackableTargetGoal<>(this, Player.class, true)`
  - **Prio 2:** `HurtByTargetGoal(this)`

#### Spider (`Spider.java:57-68`)
- **`goalSelector`:**
  - **Prio 1:** `FloatGoal(this)`
  - **Prio 2:** `AvoidEntityGoal<>(this, Armadillo.class, 6.0F, 1.0, 1.2, ...)` (flees unscared armadillos).
  - **Prio 3:** `LeapAtTargetGoal(this, 0.4F)` (pounces horizontally toward target, Flag: `JUMP`, `MOVE`).
  - **Prio 4:** `Spider.SpiderAttackGoal(this)` (melee attack).
  - **Prio 5:** `WaterAvoidingRandomStrollGoal(this, 0.8)`
  - **Prio 6:** `LookAtPlayerGoal(this, Player.class, 8.0F)`
  - **Prio 6:** `RandomLookAroundGoal(this)`
- **`targetSelector`:**
  - **Prio 1:** `HurtByTargetGoal(this)`
  - **Prio 2:** `Spider.SpiderTargetGoal<>(this, Player.class)` (hostile only at light level < 8, neutral in daylight!).
  - **Prio 3:** `Spider.SpiderTargetGoal<>(this, IronGolem.class)`
- *Note:* Overrides `createNavigation` to use `WallClimberNavigation(this, level)` (`Spider.java:72`), setting `climbing = true` when `horizontalCollision` occurs (`Spider.java:85`).

### 5.3 Pathfinding Stack and Controls

The pathfinding pipeline connects goals to motor execution:

1. **`PathNavigation` (`GroundPathNavigation.java`):**
   - Ticked from `Mob.serverAiStep` (`Mob.java:749`).
   - If a path exists, advances waypoints inside `followThePath()` (`PathNavigation.java:251-260`) when entity is within `maxDistanceToWaypoint` (0.75F - width / 2).
   - Recomputes path every 20 ticks if target moves (`recomputePath`, `PathNavigation.java:104`).
   - Dispatches target coordinate to move controller: `mob.getMoveControl().setWantedPosition(target.x, getGroundY(target), target.z, speedModifier)` (`PathNavigation.java:241`).
2. **`PathFinder` and `WalkNodeEvaluator` (`PathFinder.java:35-180`, `WalkNodeEvaluator.java`):**
   - `findPath` runs A* using a custom binary min-heap (`BinaryHeap.java`).
   - Node evaluation limit: $maxVisitedNodes = 200 \times multiplier$.
   - `WalkNodeEvaluator` samples block shapes in a 3D window around candidate coordinates. It computes `PathType` (e.g. `WALKABLE`, `BLOCKED`, `WATER`, `LAVA`, `DOOR_OPEN`, `DANGER_FIRE`) and applies danger maluses (cactus, sweet berry bushes, fire).
3. **`MoveControl` (`MoveControl.java:40-120`):**
   - Receives `wantedX`, `wantedY`, `wantedZ`, `speedModifier`.
   - Computes delta $(xd, yd, zd)$ and angular heading:
     $yRotD = \text{atan2}(zd, xd) \times \frac{180}{\pi} - 90.0F$.
   - Smoothly lerps entity rotation: `mob.setYRot(rotlerp(mob.getYRot(), yRotD, 90.0F))` (`MoveControl.java:96`).
   - Sets forward movement: `mob.setSpeed(speedModifier * attributeSpeed)` and `mob.setZza(1.0F)`.
   - Obstacle jump trigger: if $yd > \text{mob.maxUpStep}()$ and horizontal distance is small, or an obstruction collision shape exceeds mob feet level, invokes `mob.getJumpControl().jump()` (`MoveControl.java:106`).
4. **`LookControl` (`LookControl.java:30-90`):**
   - Slerps head rotation (`yHeadRot`) towards target coordinate at `yMaxRotSpeed` degrees/tick.
   - Clamps head yaw relative to body yaw within 75 degrees (`clampHeadRotationToBody`, line 63).

---

## 6. Networking Specification

### 6.1 Packet Inventory for Entities in 26.2

All entity network synchronization is governed by `ServerEntity.java` (`ServerEntity.java:91-273`). In Minecraft 26.2 (protocol 776), the following packets are required to create, update, and remove entities:

| Packet Name | 26.2 Numeric ID (Hex / Dec) | Registration Site in `GameProtocols.java` | Trigger / Interval | Payload Summary |
|---|---|---|---|---|
| `ClientboundAddEntityPacket` | `0x01` (1) | `GameProtocols.java:134` | Player starts tracking entity (`sendPairingData`). | VarInt id, UUID, VarInt entityTypeId, double x, y, z, byte pitch, byte yaw, byte headYaw, VarInt data, short vx, vy, vz. |
| `ClientboundSetEntityDataPacket` | `0x63` (99) | `GameProtocols.java:232` | Pairing or when `entityData.packDirty()` is non-null. | VarInt id, list of `DataValue<?>` (id, serializer id, value). |
| `ClientboundUpdateAttributesPacket` | `0x83` (131) | `GameProtocols.java:264` | Pairing or dirty attributes (`LivingEntity`). | VarInt id, list of syncable attributes with base value and modifiers. |
| `ClientboundSetEquipmentPacket` | `0x66` (102) | `GameProtocols.java:235` | Pairing or equipment slot change (`LivingEntity`). | VarInt id, list of slot-item pairs (byte slot with MSB continuation, item stack). |
| `ClientboundSetPassengersPacket` | `0x6B` (107) | `GameProtocols.java:240` | Pairing or when passenger list changes. | VarInt vehicleId, VarInt count, int[] passengerIds. |
| `ClientboundSetEntityLinkPacket` | `0x64` (100) | `GameProtocols.java:233` | Pairing or leash attachment/break. | int entityId, int holderId (0 = attached to lead). |
| `ClientboundBundlePacket` | `0x00` (0) | `GameProtocols.java:133` | Wraps initial spawn bundle (`ServerEntity.java:271`). | Delimiter-bracketed packet sequence. |
| `ClientboundMoveEntityPacket.Pos` | `0x35` (53) | `GameProtocols.java:186` | Every 3 ticks if delta within +/- 8 blocks. | VarInt id, short deltaX, short deltaY, short deltaZ, boolean onGround. |
| `ClientboundMoveEntityPacket.Rot` | `0x38` (56) | `GameProtocols.java:189` | Every 3 ticks if rotation changed and position didn't. | VarInt id, byte yaw, byte pitch, boolean onGround. |
| `ClientboundMoveEntityPacket.PosRot` | `0x36` (54) | `GameProtocols.java:187` | Every 3 ticks if both position and rotation changed. | VarInt id, short dx, short dy, short dz, byte yaw, byte pitch, boolean onGround. |
| `ClientboundEntityPositionSyncPacket` | `0x23` (35) | `GameProtocols.java:168` | Delta > 8 blocks, onGround changed, or every 400 ticks. | VarInt id, double x, double y, double z, double vx, vy, vz, float yaw, float pitch, boolean onGround. |
| `ClientboundRotateHeadPacket` | `0x53` (83) | `GameProtocols.java:216` | Every 3 ticks if head yaw delta $\ge 1$ byte. | VarInt id, byte headYaw. |
| `ClientboundSetEntityMotionPacket` | `0x65` (101) | `GameProtocols.java:234` | When hurt (`hurtMarked`) or projectile sync. | VarInt id, short vx, short vy, short vz (scaled by 8000). |
| `ClientboundDamageEventPacket` | `0x19` (25) | `GameProtocols.java:158` | Entity damaged (`ServerLevel.java:1202`). | VarInt id, VarInt damageTypeId, VarInt causeId, VarInt directId, Optional<Vec3> pos. |
| `ClientboundEntityEventPacket` | `0x22` (34) | `GameProtocols.java:167` | Death (3), in-love hearts (18), sheep eat grass (10). | int id, byte eventId. |
| `ClientboundRemoveEntitiesPacket` | `0x4D` (77) | `GameProtocols.java:210` | Entity untracked or discarded. | VarInt count, int[] entityIds. |

### 6.2 Tracking Ranges and Update Intervals

Registered in `EntityTypes.java` and defaulted in `EntityType.Builder` (`EntityType.java:490-491`):

| Entity Type | Client Tracking Range (Chunks / Blocks) | Update Interval (Game Ticks) | Source Registration in `EntityTypes.java` |
|---|---|---|---|
| `COW` | 10 chunks (160 blocks) | 3 ticks | `EntityTypes.java:308` |
| `PIG` | 10 chunks (160 blocks) | 3 ticks | `EntityTypes.java:754` |
| `SHEEP` | 10 chunks (160 blocks) | 3 ticks | `EntityTypes.java:825` |
| `CHICKEN` | 10 chunks (160 blocks) | 3 ticks | `EntityTypes.java:294` |
| `CREEPER` | 8 chunks (128 blocks) | 3 ticks | `EntityTypes.java:315` |
| `SKELETON` | 8 chunks (128 blocks) | 3 ticks | `EntityTypes.java:844` |
| `SPIDER` | 8 chunks (128 blocks) | 3 ticks | `EntityTypes.java:904` |
| `ZOMBIE` | 8 chunks (128 blocks) | 3 ticks | `EntityTypes.java:1096` |

### 6.3 Delta Encoding Rules (`VecDeltaCodec`)

Relative coordinate deltas are encoded via `VecDeltaCodec.java:1-55`:
- **Scale Factor:** Fixed at 4096.0 steps per block (`VecDeltaCodec.java:7`).
- **Encoding Formula:**
  $$\text{encoded}(v) = \text{round}(v \times 4096.0)$$
  $$\text{delta} = \text{encoded}(pos) - \text{encoded}(base)$$
- **Short Range Clamping:** Short range is $[-32768, 32767]$, which maps to exactly $[-8.0, +7.999755859375]$ blocks of movement per update window.
- **Position Sync Fallback (`ClientboundEntityPositionSyncPacket`, `0x23`):**
  Triggered when (`ServerEntity.java:154-158`):
  1. Any axis delta exceeds short range ($|delta| > 32767$).
  2. `teleportDelay > 400` ticks (forced periodic absolute resync every 20 seconds).
  3. `wasRiding` is true (entity dismounted).
  4. `wasOnGround != entity.onGround()` (`onGround` status changed).
  5. `entity.getRequiresPrecisePosition()` is true.
- **Angle Packing:**
  Packed to byte via `Mth.packDegrees(rot)`:
  $$\text{byteAngle} = \lfloor rot \times \frac{256.0}{360.0} \rfloor$$

### 6.4 Cross-Check with Kestrel's `dev/kestrel/net`

Auditing `core/src/vanilla/java/dev/kestrel/net/PacketIds.java` and `Outbound.java`:
- **`PacketIds.java` status:** Currently defines handshake, login, configuration, and play packets for blocks, chunks, lighting, time, chat, and player telemetry. **It contains ZERO entity packet IDs.**
- **`Outbound.java` status:** Contains serializers for chunks, lighting, block updates, time, weather, and join packets. **It contains ZERO entity serialization methods.**
- **Gaps to Implement in Kestrel:**
  1. Add packet IDs for the 16 entity packets listed in Section 6.1 into `PacketIds.java`.
  2. Implement binary serializers in `Outbound.java` for `addEntity`, `setEntityData`, `updateAttributes`, `moveEntityPosRot`, `entityPositionSync`, `rotateHead`, `setEntityMotion`, `entityEvent`, and `removeEntities`.
  3. Build an entity tracking layer (`ServerEntity` equivalent) attached to `ChunkSender` or player sessions.

---

## 7. Interactions and State

### 7.1 Health, Damage, and Death

1. **Damage Pipeline (`LivingEntity.hurtServer`, `LivingEntity.java:1189-1260`):**
   - Checks invulnerability: `isInvulnerableTo(level, source)` (`LivingEntity.java:1190`).
   - If health $\le 0$, aborts.
   - Calculates armor reduction via `DamageModel.getDamageAfterAbsorb`.
   - Deducts health: `setHealth(getHealth() - damage)`.
   - Computes knockback: `getKnockback(attacker, source)` (`LivingEntity.java:1550`), applying horizontal impulse scaled by `Attributes.ATTACK_KNOCKBACK` and enchantment modifiers.
   - Broadcasts `ClientboundDamageEventPacket` (`0x19`) (`ServerLevel.java:1202`).
   - Marks hurt: `this.hurtMarked = true`, triggering `ClientboundSetEntityMotionPacket` (`ServerEntity.java:228`).
2. **Death Pipeline (`LivingEntity.die`, `LivingEntity.java:1461-1492`):**
   - Sets `dead = true` (`LivingEntity.java:1495`).
   - Broadcasts entity event byte 3 (`ClientboundEntityEventPacket`, `0x22`) (`LivingEntity.java:1487`), triggering client death tilt animation.
   - Sets pose to `Pose.DYING`.
   - Drops death loot: `dropAllDeathLoot(serverLevel, source)` (`LivingEntity.java:1483`).
   - Spawns death experience: `dropExperience` (`LivingEntity.java:1533-1540`) awards experience orbs if killed by player.
   - Post-death countdown (`tickDeath`, `LivingEntity.java:583-589`): increments `deathTime` up to 20 ticks. At tick 20, broadcasts entity event byte 60 (smoke poof particles) and calls `remove(Entity.RemovalReason.KILLED)`.

### 7.2 Loot Drops (Datapack Loot Tables)

Loot resolution queries the datapack JSON tables under `packdump/data/minecraft/loot_table/`:
- `data/minecraft/loot_table/entities/cow.json`: 0-2 Leather, 1-3 Raw Beef (Cooked Beef if on fire).
- `data/minecraft/loot_table/entities/pig.json`: 1-3 Raw Porkchop (Cooked Porkchop if on fire).
- `data/minecraft/loot_table/entities/sheep.json`: 1-2 Raw Mutton, 1 Wool matching sheep color.
- `data/minecraft/loot_table/entities/chicken.json`: 1 Raw Chicken, 0-2 Feathers.
- `data/minecraft/loot_table/entities/zombie.json`: 0-2 Rotten Flesh; rare drops: Iron Ingot, Carrot, Potato.
- `data/minecraft/loot_table/entities/skeleton.json`: 0-2 Bones, 0-2 Arrows.
- `data/minecraft/loot_table/entities/creeper.json`: 0-2 Gunpowder; drops Music Disc if killed by skeleton arrow.
- `data/minecraft/loot_table/entities/spider.json`: 0-2 String, 0-1 Spider Eye if killed by player.

### 7.3 Breeding, Baby Growth, Shearing, and Milking

- **Animal Breeding (`Animal.java:212-245`):**
  - Triggered by player feeding food item (`ItemTags.COW_FOOD`, `PIG_FOOD`, `SHEEP_FOOD`, `CHICKEN_FOOD`).
  - Sets `inLove = 600` (30 seconds). Emits love hearts (event byte 18).
  - Two in-love animals navigate toward each other via `BreedGoal`.
  - Spawns offspring: `offspring.setBaby(true)` (`AgeableMob.age = -24000`, 20 minutes to adult).
  - Sets parent cooldown: `setAge(6000)` (5 minutes cooldown).
  - Drops 1-7 XP via `ExperienceOrb` (`Animal.java:233`).
- **Baby Growth Acceleration (`AgeableMob.ageUp`, `AgeableMob.java:112`):**
  - Feeding a baby animal reduces remaining baby time: `ageUp((int)((float)(-age / 20) * 0.1F))`, accelerating growth by 10%.
- **Sheep Shearing & Wool Regrowth (`Sheep.java:148-203, 294-300`):**
  - Right clicking adult unsheared sheep with Shears:
    - Drops 1-3 wool matching sheep color from `data/minecraft/loot_table/shearing/sheep.json`.
    - Plays `SoundEvents.SHEEP_SHEAR`.
    - Sets `sheared = true` (`Sheep.java:185`). Synced to client via `DATA_FLAGS_ID` in `SynchedEntityData`.
  - Wool regrowth: when sheep executes `EatBlockGoal` and consumes a grass block:
    - Calls `Sheep.ate()` (`Sheep.java:294-300`).
    - Sets `sheared = false` (`Sheep.java:296`).
- **Cow Milking (`AbstractCow.mobInteract`, `AbstractCow.java:90-100`):**
  - Right clicking adult cow with empty bucket (`Items.BUCKET`):
    - Plays `SoundEvents.COW_MILK`.
    - Converts empty bucket to `Items.MILK_BUCKET` in player inventory.
    - Returns `InteractionResult.SUCCESS`. Does not trigger if `isBaby()` is true.

### 7.4 Persistence and Region Storage

- **Entity NBT (`Entity.save`, `Entity.load`):**
  - Serialized via `addAdditionalSaveData(ValueOutput)` and `readAdditionalSaveData(ValueInput)`.
  - Base fields (`Entity.java:376-430`): `id`, `Pos` (3 doubles), `Motion` (3 doubles), `Rotation` (2 floats), `FallDistance`, `Fire`, `Air`, `OnGround`, `Invulnerable`, `UUID`.
  - Living fields (`LivingEntity.java:770-850`): `Health`, `HurtTime`, `DeathTime`, `Attributes` list, `ActiveEffects` list.
  - Mob fields (`Mob.java:376-410`): `CanPickUpLoot`, `PersistenceRequired`, `drop_chances`, `Leash`, `LeftHanded`.
  - Subclass fields: `Age`, `InLove`, `Sheared`, `Color`.
- **Region Storage (`EntityStorage.java:1-140`):**
  - Stored in separate region files under `entities/r.<rx>.<rz>.mca`.
  - Chunk format: `CompoundTag` with `DataVersion`, `Position: [I; x, z]`, and `Entities: ListTag<CompoundTag>`.
  - Loaded asynchronously in `EntityStorage.loadEntities` (`EntityStorage.java:46-82`) and deserialized through `EntityType.loadEntitiesRecursive`.

---

## 8. Kestrel Today: Code Inventory and Gap Analysis

An audit of Kestrel's existing codebase in `core/src/vanilla/java/dev/kestrel/` reveals the exact current status of entity-related components:

| Package | Existing Classes | Purpose in Kestrel Today | Missing for G7 Mobs |
|---|---|---|---|
| `dev.kestrel.ai` | `VisibleEntityScan`, `PlayerScan`, `SecondaryPoiScan`, `PoiTopK`, `BehaviorSchedule`, `LosVerdictCache`, `ScanOrder`, `MobAiProofCheck`, `SensorScanBench`. | Optimized Brain/Sensor algorithms created for Step 4 (Villager sensor optimizations). | **GoalSelector, Goal, PathNavigation, PathFinder, Controls.** Kestrel has no Goal-based AI code whatsoever. |
| `dev.kestrel.spawn` | `SpawnCaps`, `SpawnPotential`, `SpawnDrawModel`, `SpawnCapsCheck`. | Pure mathematical transcription of vanilla mob caps and potential energy charges for Step 6. | **Active Spawner Loop.** No connection to live ticking, no biome spawn parsing, no `NaturalSpawner` chunk attempt execution. |
| `dev.kestrel.worldgen` | `HeadlessEntityServerLevel.java`, `AsyncChunkGenerator.java`. | Dummy `ServerLevel` shell allocated via Unsafe to allow structure pieces (e.g. witch huts) to instantiate entities during chunk generation without NPEs. | Does not tick entities, does not store entities, has no live networking or physics. |
| `dev.kestrel.collision` | `ShapeBoxCache`, `KestrelCollide`, `ShapeBoxCacheCheck`, `CollisionBench`. | High-performance swept block collision resolution and slab/stair step-up pass. | Entity-vs-entity collisions, world border collisions, fluid buoyancy/drag, fall damage calculation, entity travel loop integration. |
| `dev.kestrel.net` | `PacketIds`, `Outbound`, `ChunkSender`, `NetServer`, `KestrelPlayer`, etc. | Network engine for chunks, blocks, time, weather, chat, player position. | **Every entity packet.** No `AddEntity`, `SetEntityData`, `MoveEntity`, `EntityPositionSync`, `RotateHead`, `EntityEvent`, or `RemoveEntities`. |
| `dev.kestrel.loop` | `TickLoop`, `FluidFlow`, `FluidOracle`, `SectionStore`. | Exact tick loop running scheduled block ticks and fluid ticks with sub-tick ordering. | **Entity tick phase.** `TickLoop` ticks blocks and fluids, but has no entity list and does not invoke entity ticks. |

---

## 9. Parity Test Plan: In-Process Oracle and 20 Scenarios

### 9.1 In-Process Entity Oracle Architecture

Following the successful pattern established in `core/src/vanilla/java/dev/kestrel/loop/FluidOracle.java`:

1. **Server Harness Boot:**
   - Boots unmodified vanilla 26.2 dedicated server (`net.minecraft.server.Main`) in-process.
   - `server.properties`: `level-type=minecraft\:flat`, `generate-structures=false`, `pause-when-empty-seconds=0` (prevents server sleep pause after 60s), `view-distance=3`, `simulation-distance=3`.
2. **Environment Freezing:**
   - Freezes tick clock: `server.tickRateManager().setFrozen(true)`.
   - Disables ambient interference: `gamerule random_tick_speed 0`, `gamerule spawn_mobs false`, `gamerule advance_weather false`, `gamerule advance_time false`.
   - Force-loads scenario chunks (`forceload add -1 -1 0 0`) and waits until chunks reach `ENTITY_TICKING` status.
3. **Random Source Interception & Determinism:**
   - **`Level.random`:** Replace `Level.random` with a delegating `RandomSource` that records every draw (method caller, arguments, returned value).
   - **`Entity.random`:** Vanilla `Entity.random` is instantiated unseeded (`RandomSource.create()`). For deterministic oracle fixtures, replace each scenario entity's `random` field via reflection with a seeded Xoroshiro generator (seed 424242L) or a recording/replaying proxy.
   - **UUID Normalization:** `/summon` assigns random UUIDs that pollute hashes. Force deterministic UUIDs: `new UUID(0L, scenarioEntityIndex)`.
4. **Per-Tick Snapshot Schema:**
   After each `/tick step 1` invocation, record:
   - `tick`: game tick index relative to scenario start.
   - `pos`: exact IEEE-754 bit representations of X, Y, Z (`Double.doubleToRawLongBits`).
   - `motion`: raw long bits of deltaMovement X, Y, Z.
   - `rot`: `yRot`, `xRot`, `yHeadRot`, `yBodyRot` (floats).
   - `flags`: `onGround`, `horizontalCollision`, `verticalCollision`, `verticalCollisionBelow`, `minorHorizontalCollision`, `isInWater`, `isInLava`.
   - `health`: float value, `fallDistance` (float), `noActionTime` (int).
   - `goals`: active goal names and priorities in `goalSelector` and `targetSelector`.
   - `synchedData`: non-default entries from `SynchedEntityData`.
   - `packets`: captured outbound packet byte buffers emitted by `ServerEntity`.

### 9.2 Twenty Concrete Parity Scenarios

| # | Scenario Name | Entity Type | Initial Setup & Environmental Conditions | Expected Observable Behavior & Parity Assertion |
|---|---|---|---|---|
| 1 | `cow_flat_stand` | Cow | Adult Cow on flat bedrock at $(0.5, 4.0, 0.5)$, NoAI. | $\Delta Y = 0$, $onGround = true$, zero displacement over 20 ticks. Bit-exact position. |
| 2 | `cow_free_fall` | Cow | Adult Cow spawned in mid-air at $(0.5, 20.0, 0.5)$, NoAI. | Accelerates at $0.08$ blocks/tick$^2$, terminal velocity capped by $0.98$ air drag. Lands on bedrock at tick 20, $onGround$ flips to true. |
| 3 | `cow_step_up_slab` | Cow | Cow moving forward (+X) toward a smooth stone slab ($Y=4.5$). | Hits slab horizontally, step-up pass succeeds with candidate height 0.5, elevates onto slab smoothly without velocity loss. |
| 4 | `cow_step_up_stair` | Cow | Cow moving toward oak stairs facing east ($Y=4.5$ step to $Y=5.0$). | Ascends two consecutive 0.5m step increments via `KestrelCollide`. |
| 5 | `cow_fall_damage` | Cow | Cow dropped from $Y=10.0$ to $Y=4.0$ (6 block drop). | Safe threshold 3 blocks exceeded; takes 3 damage on landing ($10 - 4 - 3 = 3$), health drops from 10.0 to 7.0, emits hurt packet. |
| 6 | `cow_water_buoyancy` | Cow | Cow placed at bottom of 3-deep water pool ($Y=1.0$). | Floats upward at $0.005$ to $0.01$ blocks/tick, velocity dampened by $0.8$, reaches surface and bobs. |
| 7 | `cow_lava_viscosity` | Cow | Cow placed in lava pool at $Y=4.0$. | Velocity dampened by $0.5$ per tick, takes fire damage every 20 ticks, sinks slowly. |
| 8 | `cow_random_stroll` | Cow | Cow with AI enabled on 16x16 grass field, seeded RNG. | `WaterAvoidingRandomStrollGoal` selects target position within 10 blocks, navigates path, `MoveControl` rotates heading and steps forward. |
| 9 | `cow_panic_flee` | Cow | Cow damaged with 1 damage source from player at $(0, 4, -2)$. | `PanicGoal` triggers at prio 1, speed multiplies to 2.0x, flees away from damage origin for exactly 100 ticks. |
| 10 | `cow_tempt_wheat` | Cow | Simulated player holds `Items.WHEAT` within 8 blocks. | `TemptGoal` triggers at prio 3, cow turns head toward player and walks toward player until distance $\le 2.5$ blocks. |
| 11 | `cow_breeding` | Cow (x2) | Two adult cows placed 3 blocks apart, both fed wheat. | Both enter `inLove = 600`, emit heart event 18, walk together, spawn baby cow ($age = -24000$), parents set $age = 6000$, 1-7 XP spawned. |
| 12 | `cow_baby_growth` | Cow | Baby cow ($age = -24000$) on bedrock. | Age increments +1 per tick. At tick 200, fed wheat, age advances by 2400 ticks (10%), reaches adulthood exactly 2400 ticks faster. |
| 13 | `sheep_eat_grass` | Sheep | Adult sheep on grass block with sheared wool. | `EatBlockGoal` triggers, head drops, grass block converts to dirt at tick 40, wool regrows (`sheared = false`), emits event 10. |
| 14 | `sheep_shearing` | Sheep | Adult unsheared sheep right-clicked with shears. | Drops 1-3 white wool items, plays shear sound, sets `sheared = true`, client receives synced data bit flag. |
| 15 | `cow_milking` | Cow | Adult cow right-clicked with empty bucket. | Bucket converts to milk bucket, milk sound plays, cow health and state untouched. |
| 16 | `zombie_player_chase` | Zombie | Zombie at $(0, 4, 0)$, player at $(10, 4, 0)$. | `NearestAttackableTargetGoal` acquires player, `ZombieAttackGoal` sets path navigation, zombie moves at $0.23$ speed toward player. |
| 17 | `skeleton_bow_strafe` | Skeleton | Skeleton with bow at $(0, 4, 0)$, player at $(8, 4, 0)$. | `RangedBowAttackGoal` triggers, skeleton strafes sideways, charges bow for 20 ticks, fires arrow projectile at tick 20. |
| 18 | `creeper_swell_explode` | Creeper | Creeper at $(0, 4, 0)$, player at $(1.5, 4, 0)$. | `SwellGoal` triggers, fuse increments $0 \to 30$, emits flash data flag, explodes at tick 30 destroying blocks and dealing damage. |
| 19 | `spider_climb_wall` | Spider | Spider facing 4-block vertical stone wall, moving +X. | Hits wall, $horizontalCollision$ becomes true, `setClimbing(true)`, ascends wall vertically at $0.15$ blocks/tick. |
| 20 | `monster_despawn_dist` | Zombie | Zombie spawned at $(200, 64, 200)$, player at $(0, 64, 0)$. | $distSqr = 80000 > 16384$, `removeWhenFarAway` is true; entity calls `this.discard()` on tick 1 during `checkDespawn`. |

---

## 10. Risks, Open Questions, and Vertical Slices

### 10.1 Risks and Open Questions (Ordered by Severity)

1. **Level and Entity RNG Desynchronization (CRITICAL):**
   - *Risk:* Vanilla relies on `level.random` for natural spawn chunk shuffling, try jittering, spawn data picking, sky light tests, and despawn checks. A single omitted or extra draw desynchronizes every subsequent draw in the world. Furthermore, `Entity.random` is unseeded in vanilla, meaning AI movement diverges between runs unless explicitly controlled.
   - *Mitigation:* Transcribe the exact draw sequence modeled in `SpawnDrawModel.java`. In parity testing, inject a seeded Xoroshiro generator into `entity.random` and verify level draws via stack-walking delegation.
2. **Delta Encoding Overflow and Position Desync (HIGH):**
   - *Risk:* 26.2 clients strictly track entity position based on delta packets (`0x35`, `0x36`, `0x38`) using 4096.0 scale factor. If a mob moves $> 8.0$ blocks in an update window (e.g. explosion knockback or falling) without emitting `ClientboundEntityPositionSyncPacket` (`0x23`), the short integer overflows and the entity snaps wildly or disappears on client screens.
   - *Mitigation:* Transcribe `VecDeltaCodec` exactly and enforce the fallback triggers (`deltaTooBig`, `teleportDelay > 400`, `onGround` flip).
3. **Section Visibility vs TickList Synchronization (HIGH):**
   - *Risk:* Entities in vanilla exist in multiple collections: `PersistentEntitySectionManager`, `EntitySectionStorage`, `EntityLookup`, `EntityTickList`, and `ChunkMap.TrackedEntity`. If an entity is added to the world but omitted from `EntityTickList`, it stands frozen. If omitted from `TrackedEntity`, it is invisible to clients.
   - *Mitigation:* Bind entity lifecycle strictly through `LevelCallback` listeners (`onTickingStart`, `onTrackingStart`) identical to `ServerLevel.EntityCallbacks` (`ServerLevel.java:1908-1950`).
4. **Pathfinding Collision Shape Overhead (MEDIUM):**
   - *Risk:* `PathFinder` performs hundreds of VoxelShape collision checks per search. Doing this synchronously on the main thread for multiple mobs can induce severe MSPT spikes.
   - *Mitigation:* Leverage Kestrel's existing `ShapeBoxCache.java` inside `WalkNodeEvaluator` block queries, and enforce vanilla's `maxVisitedNodes = 200` search abort limit.
5. **Entity DataTracker (`SynchedEntityData`) Index Drift (MEDIUM):**
   - *Risk:* Data values are identified by byte indices defined statically per class. If Kestrel misses an accessor (e.g. `DATA_FLAGS_ID` or `DATA_BABY_ID`), the client deserializes corrupted data types and crashes with `DecoderException`.
   - *Mitigation:* Directly mirror vanilla's static `defineId` registrations and test against vanilla packet fixtures.

### 10.2 Recommended Vertical Implementation Slices

#### Slice 1: Passive Entity Existence, DataTracker, and Client Visibility
- **Objective:** Spawn a Cow in the world that stands still, persists in memory, is sent to real 26.2 clients, and renders with correct model and rotation.
- **Components to Implement:**
  - Entity packet serializers in `Outbound.java`: `AddEntity` (`0x01`), `SetEntityData` (`0x63`), `UpdateAttributes` (`0x83`), `RotateHead` (`0x53`), `RemoveEntities` (`0x4D`), `Bundle` (`0x00`).
  - Base `Entity`, `LivingEntity`, `Mob`, `Animal`, `Cow` class hierarchy.
  - Tracking pipeline: track players in range (10 chunks), send pairing bundle on entry, remove packet on exit.
- **Gating Scenarios:** Scenario 1 (`cow_flat_stand`). Client displays stationary cow, no console packet errors.

#### Slice 2: Gravity, Swept Block Collision, and Step-Up Physics
- **Objective:** Enable gravity, velocity integration, and block collisions. Cow falls when dropped, lands on ground, and steps up onto slabs.
- **Components to Implement:**
  - `travelInAir` physics loop in `LivingEntity`: gravity (-0.08 blocks/tick$^2$), friction (0.6F), vertical drag (0.98F), horizontal air drag (0.91F).
  - Wire `Entity.move` to `KestrelCollide.collide` (resolving swept block boxes and 0.6m step-up).
  - Outbound movement packets: `MoveEntity.PosRot` (`0x36`) and `EntityPositionSync` (`0x23`) with `VecDeltaCodec`.
- **Gating Scenarios:** Scenario 2 (`cow_free_fall`), Scenario 3 (`cow_step_up_slab`), Scenario 4 (`cow_step_up_stair`).

#### Slice 3: GoalSelector Engine and Random Strolling
- **Objective:** Mob autonomously wanders on land with seeded random draws.
- **Components to Implement:**
  - `GoalSelector` engine with priority update, flag locking, and alternating tick schedule.
  - `FloatGoal`, `RandomLookAroundGoal`, `LookAtPlayerGoal`, `WaterAvoidingRandomStrollGoal`.
  - `MoveControl` and `LookControl` motors.
- **Gating Scenarios:** Scenario 8 (`cow_random_stroll`). Cow smoothly wanders, rotates head, and stops.

#### Slice 4: Pathfinding Stack
- **Objective:** Goal paths navigate around walls and obstacles rather than walking straight into blocks.
- **Components to Implement:**
  - `PathNavigation` and `GroundPathNavigation`.
  - `PathFinder` (A* algorithm on `BinaryHeap`).
  - `WalkNodeEvaluator` evaluating `PathType` using `ShapeBoxCache`.
- **Gating Scenarios:** Cow navigates around a 3-block fence obstacle to reach wheat held by player.

#### Slice 5: Damage Pipeline, Health, Fall Damage, and Death
- **Objective:** Mobs take damage from attacks and falling, show hurt animations, die, and drop loot.
- **Components to Implement:**
  - `LivingEntity.hurtServer` and knockback math.
  - Fall distance tracking and damage calculation.
  - Death pipeline: `ClientboundDamageEventPacket` (`0x19`), `ClientboundEntityEventPacket` (`0x22`, byte 3), death timer.
  - Datapack loot table evaluation for mob drops.
- **Gating Scenarios:** Scenario 5 (`cow_fall_damage`), Scenario 9 (`cow_panic_flee`). Punching cow causes panic flee; killing cow drops leather and raw beef.

#### Slice 6: Hostile Monster AI (Zombie, Skeleton, Creeper, Spider)
- **Objective:** Implement hostile behaviors: targeting players, melee chase, bow shooting, creeper swelling, spider climbing.
- **Components to Implement:**
  - `targetSelector` and `NearestAttackableTargetGoal`, `HurtByTargetGoal`.
  - `ZombieAttackGoal`, `RangedBowAttackGoal` (projectile spawning), `SwellGoal` (fuse timer and explosion), `WallClimberNavigation`.
  - Monster light level spawn gating.
- **Gating Scenarios:** Scenario 16 (`zombie_player_chase`), Scenario 17 (`skeleton_bow_strafe`), Scenario 18 (`creeper_swell_explode`), Scenario 19 (`spider_climb_wall`).

#### Slice 7: Animal Interactions (Breeding, Shearing, Milking)
- **Objective:** Full animal interaction lifecycle.
- **Components to Implement:**
  - `BreedGoal`, `TemptGoal`, `FollowParentGoal`.
  - Sheep `EatBlockGoal` and shearing state.
  - Cow milking interaction.
- **Gating Scenarios:** Scenario 10 (`cow_tempt_wheat`), Scenario 11 (`cow_breeding`), Scenario 12 (`cow_baby_growth`), Scenario 13 (`sheep_eat_grass`), Scenario 14 (`sheep_shearing`), Scenario 15 (`cow_milking`).

#### Slice 8: Natural Spawning & Persistence
- **Objective:** Mobs spawn naturally in loaded chunks and during chunk generation, and save/load to disk.
- **Components to Implement:**
  - `NaturalSpawner` chunk attempt loop using existing `SpawnCaps` and `SpawnPotential`.
  - `spawnMobsForChunkGeneration` during chunk creation.
  - NBT serialization and region loading/saving under `entities/r.<rx>.<rz>.mca`.
- **Gating Scenarios:** Scenario 20 (`monster_despawn_dist`). Natural spawns populate around player according to biome spawn tables; reload reproduces exact entity state.
