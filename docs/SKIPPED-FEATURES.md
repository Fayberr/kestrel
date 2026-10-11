# Skipped Features & Worldgen Inventory (Finding H2 & Stage E)

> **Reference**: Finding H2 (`docs/ARCHITECTURE-AUDIT.md`, section 4, finding H2) & Stage E Remediation (section 6, Stage E)  
> **Defect Summary**: *"Block-only handoff discards metadata and treats failed generation as success"*

> **Status as of 2026-09-25.** This file is the original
> pre-research inventory and describes a RegionView that no longer exists in
> that form. Sections 2 and 3 retain the audited line numbers and links as of
> the original inventory; the code has moved since, so read them as history
> with the per-section status notes below. Most of the inventory is resolved
> **inside generation**:
>
> - `RegionView` gets a real `StructureTemplateManager` with structures on, so
>   fossils and structure templates place (Step 8, LARGE 1,418/1,418 exact).
> - The `DUMMY` block-entity tag now mirrors Vanilla's own `WorldGenRegion`:
>   Vanilla writes the same `DUMMY` tag on proto chunks and promotes it lazily
>   on first read. `RegionView.getBlockEntity` does the same, so chest loot
>   seeds, spawner types and shrieker flags are rolled exactly as in Vanilla.
> - Generation uses a real light engine. Light that Vanilla computes during
>   generation is reproduced and handed to the wire exactly (Step 9).
> - Entities placed during generation are buffered by
>   `HeadlessEntityServerLevel`. The native SPAWN gate matches 169/169 SPAWN
>   stages and 36 entity insertions.
> - In the parity gates, a feature exception fails the chunk
>   (`LIVE_FEATURE_FAILURE`). No gated window has hit one with structures on.
>   The production server still logs and skips a failing feature so one
>   exception cannot take the server down, so H2.8 is only partly closed.
>
> What is still open is the **handoff into the live world**. The live server
> receives blocks, biomes and light, and records each block entity only as a
> position and a type (`KestrelLevel.BlockEntityRecord`). It does not yet
> receive block-entity NBT (loot tables, spawner configuration, bee
> occupants, shrieker `can_summon`), generated entities, or scheduled
> block/fluid ticks, and the live generator does not run the SPAWN stage.
> These items depend on live block entities, entities and ticking, so they
> belong to the later engine stages rather than to G2 worldgen parity.
> The checklist in section 5 is kept as written for history.
>
> Later work that touches these items: G11 water/lava is live and
> `FluidParityCheck` reports 25/25 exact, but worldgen spring tick ingestion is
> still missing (section 3.7, H2.7). G12 redstone landed for the core and
> primary components (`RedstoneParityCheck` 29/29 exact); its secondary
> components (target block, daylight detector, tripwire, note block, doors,
> trapdoors, fence gates, dispenser, dropper, TNT, rails) are unbuilt and mostly
> need entities or containers. The live block-entity, entity and item work these
> handoff items depend on now has pre-research specs in `preresearch/`
> (`s14-entity-system-preresearch.md`, `g17-inventory-preresearch.md`,
> `s16-chunk-lifecycle-preresearch.md`).

---

## 1. Executive Summary

Kestrel's world generation pipeline in [`HeadlessNoise.java`](file:///core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java) 
executes vanilla generation stages (`NOISE`, `SURFACE`, `CARVERS`, and `FEATURES`) without a full `ServerLevel`. 
While this design enables headless high-throughput generation and low memory consumption, the current implementation 
suffers from critical architectural defects identified in **Finding H2**:

1. **Unsupported World Access in `RegionView`**: `RegionView` implements `WorldGenLevel` via dynamic proxy, 
   returning `null` for `getLevel()`, `true` for `addFreshEntity` while discarding the entity, and dropping sound, particle, and game events.
2. **Silent Catch-and-Continue**: At `HeadlessNoise.java:806-815`, any `RuntimeException` during feature placement 
   (such as `NullPointerException` when `FossilFeature` dereferences `level.getLevel().getStructureManager()`) 
   is swallowed with a stderr log line, skipping the feature entirely.
3. **Block-Only Handoff (`writeStoreCompact`)**: At `HeadlessNoise.java:1217-1228`, only raw `BlockState` cells are copied to `SectionStore`. 
   All tile entity state (loot tables for dungeon chests, mob spawner configurations, sculk shrieker summon tags), 
   scheduled block/fluid ticks, and entities are completely discarded.
4. **Premature Completion Marking**: In `LiveGenerator.java:114`, `store.markChunkGenerated(centerKey)` 
   permanently records the chunk as complete despite skipped features and stripped metadata.

This inventory provides the technical grounding for **Stage E**, detailing exact methods, affected feature categories, 
and concrete remediation requirements.

---

## 2. Part A: Exact Methods in RegionView Returning Null, No-Op, or Dropping State

The table below catalogs every method in `RegionView` where unsupported vanilla world access 
returns `null`, no-ops, drops state, or throws exceptions.

| Method | Signature | Category | Line | Behavior & Downstream Consequence |
|---|---|---|---|---|
| `getLevel` | `ServerLevel getLevel()` | `NULL_RETURN` | L1034 | **Behavior**: Returns null unconditionally. ServerLevelAccessor.getLevel() is invoked by FossilFeature, TemplateFeature, and structure decorators to reach StructureTemplateManager.<br>**Consequence**: NullPointerException when level.getLevel().getStructureManager() is invoked; caught at HeadlessNoise.java:806-815 and feature is silently skipped. |
| `setCurrentlyGenerating` | `void setCurrentlyGenerating(Supplier<String>)` | `NO_OP_VOID` | L1049 | **Behavior**: Returns null when return type is void; completely silent no-op.<br>**Consequence**: Drops feature context tracking and crash report breadcrumbs. |
| `playSound` | `void playSound(Player, BlockPos, SoundEvent, SoundSource, float, float)` | `NO_OP_VOID` | L1050 | **Behavior**: Returns null when return type is void; completely silent no-op.<br>**Consequence**: Drops sound events triggered during world generation. |
| `addParticle` | `void addParticle(ParticleOptions, double, double, double, double, double, double)` | `NO_OP_VOID` | L1051 | **Behavior**: Returns null when return type is void; completely silent no-op.<br>**Consequence**: Drops particle effects during generation. |
| `levelEvent` | `void levelEvent(Player, int, BlockPos, int)` | `NO_OP_VOID` | L1052 | **Behavior**: Returns null when return type is void; completely silent no-op.<br>**Consequence**: Drops world level events (bonemeal effects, block break effects). |
| `gameEvent` | `void gameEvent(Holder<GameEvent>, Vec3, GameEvent.Context)` | `NO_OP_VOID` | L1053 | **Behavior**: Returns null when return type is void; completely silent no-op.<br>**Consequence**: Drops game events (sculk vibrations, entity vibration listeners). |
| `addFreshEntity` | `boolean addFreshEntity(Entity entity)` | `STATE_DROPPED` | L1112 | **Behavior**: Returns true (faking success) without registering, storing, or spawning the entity.<br>**Consequence**: Entity state is permanently dropped. Mobs (e.g. bees from bee nests in BeehiveDecorator, allays in cages, armor stands, paintings) are silently discarded. |
| `getEntities / players` | `List<Entity> getEntities(...) / List<ServerPlayer> players()` | `EMPTY_STUB` | L1154 | **Behavior**: Returns immutable List.of() (empty list).<br>**Consequence**: Features querying surrounding entity density or player proximity receive empty collections. |
| `getLightEngine` | `LevelLightEngine getLightEngine()` | `EMPTY_STUB` | L1161 | **Behavior**: Returns LevelLightEngine.EMPTY.<br>**Consequence**: Reports 0 sky light and 0 block light everywhere. Features requiring ambient light (certain trees, mushrooms, vegetation survival checks) observe unlit world conditions. |
| `getGameTime` | `long getGameTime()` | `EMPTY_STUB` | L1163 | **Behavior**: Returns constant 0L.<br>**Consequence**: Scheduled tick creation and time-dependent feature logic observe fixed tick 0. |
| `getSkyDarken` | `int getSkyDarken()` | `EMPTY_STUB` | L1157 | **Behavior**: Returns constant 0.<br>**Consequence**: Sky darkness is permanently 0. |
| `isClientSide / isOldChunkAround` | `boolean isClientSide() / boolean isOldChunkAround(ChunkPos, int)` | `EMPTY_STUB` | L1114 | **Behavior**: Returns false unconditionally.<br>**Consequence**: Hardcoded server-side fresh world generation flags. |
| `default (unhandled non-default interface methods)` | `UnsupportedOperationException on any unhandled non-default method` | `EXCEPTION` | L1174 | **Behavior**: Throws UnsupportedOperationException('RegionView: ' + m).<br>**Consequence**: Any unhandled method (getLevelData, getChunkSource, getScoreboard, getRecipeManager, etc.) throws UnsupportedOperationException, caught by the feature loop and skipped. |

### Pipeline State Drops & Discard Boundaries

In addition to dynamic proxy intercepts, the worldgen pipeline drops metadata at the following architectural boundaries:

- **RegionView.setBlock (HeadlessNoise.java:991-998)** (`BLOCK_ENTITY_NBT_STUBBED`):
  When state.hasBlockEntity() is true, writes a dummy CompoundTag with only coordinates and id='DUMMY'. Real block entity configuration (loot tables, spawner mob types, sign text, container inventories, sculk shrieker can_summon) is completely overwritten.

- **RegionView.setBlock (HeadlessNoise.java:986-988)** (`OUT_OF_BOUNDS_DROP`):
  Writes outside chessboard radius 1 from center chunk (!ensureCanWrite) silently return false and drop block placement.

- **RegionView.setBlock (HeadlessNoise.java:1002-1006)** (`POST_PROCESS_DROPPED`):
  chunkAt(post).markPosForPostProcessing(post) records positions on ProtoChunk, but post-processing positions are never transferred to SectionStore or resolved.

- **RegionView.destroyBlock (HeadlessNoise.java:1101-1111)** (`BLOCK_DROPS_IGNORED`):
  Replaces block with fluid/air without spawning block drop items or exp.

- **HeadlessNoise.features (HeadlessNoise.java:806-815)** (`SILENT_EXCEPTION_SWALLOWING`):
  Catches java.lang.RuntimeException during feature.placeWithBiomeCheck, logs a stderr line, and skips the feature. The chunk generation proceeds as if successful.

- **HeadlessNoise.writeStoreCompact / writeChunk (HeadlessNoise.java:1217-1228)** (`BLOCK_ONLY_HANDOFF`):
  Handoff to SectionStore copies ONLY block states (section.getBlockState(x,y,z)). ProtoChunk block entities (blockEntityNbts), scheduled ticks (blockTicks, fluidTicks), entities, and primed heightmaps are completely discarded.

- **LiveGenerator.ensureChunk (LiveGenerator.java:114)** (`PREMATURE_COMPLETION_MARKER`):
  store.markChunkGenerated(centerKey) marks the chunk complete in SectionStore, even if features threw exceptions or metadata was dropped, preventing future repair.

---

## 3. Part B: Vanilla Feature Categories That Fail or Get Skipped

Evaluation of vanilla Minecraft Overworld placed feature steps (`GenerationStep.Decoration` steps 0 through 10) 
shows that while `SurfaceRules` (evaluated during `SURFACE`) completes without touching `ServerLevel`, multiple placed 
feature categories fail silently or produce corrupted, empty world artifacts.

### Summary Matrix

| # | Category | Step | Placed Features | Feature Class | Required Access | Failure Mode | Severity |
|---|---|---|---|---|---|---|---|
| 1 | **FOSSIL (Fossil Coal & Fossil Diamonds)** | Step 3: `UNDERGROUND_STRUCTURES` | `fossil_coal, fossil_diamonds` | `net.minecraft.world.level.levelgen.feature.FossilFeature` | ServerLevelAccessor.getLevel(), StructureTemplateManager | `CRASH_CAUGHT_AND_SKIPPED` | **CRITICAL** |
| 2 | **DUNGEONS / MONSTER_ROOM** | Step 3: `UNDERGROUND_STRUCTURES` | `monster_room` | `net.minecraft.world.level.levelgen.feature.MonsterRoomFeature` | SpawnerBlockEntity configuration, RandomizableContainerBlockEntity.setLootTable, Block Entity NBT retention | `METADATA_DISCARDED` | **CRITICAL** |
| 3 | **BONUS_CHEST** | Step 4: `SURFACE_STRUCTURES` | `bonus_chest` | `net.minecraft.world.level.levelgen.feature.BonusChestFeature` | LootTable resolution, Block Entity NBT retention, ServerLevelAccessor.getLevel() | `METADATA_DISCARDED_OR_SKIPPED` | **HIGH** |
| 4 | **ARCHAEOLOGY / DESERT_WELL** | Step 4: `SURFACE_STRUCTURES` | `desert_well` | `net.minecraft.world.level.levelgen.feature.DesertWellFeature` | BrushableBlockEntity NBT, Archaeology Loot Table | `METADATA_DISCARDED` | **HIGH** |
| 5 | **BEES_AND_BEEHIVES (Vegetal Trees)** | Step 9: `VEGETAL_DECORATION` | `oak_checked, birch_checked, super_birch_bees_0002 (+1 more)` | `net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator` | WorldGenLevel.addFreshEntity(), BeehiveBlockEntity NBT retention | `ENTITIES_DROPPED` | **HIGH** |
| 6 | **DEEP_DARK_SCULK (Shriekers & Catalysts)** | Step 7: `UNDERGROUND_DECORATION` | `sculk_patch_deep, sculk_patch_ancient_city, sculk_vein` | `net.minecraft.world.level.levelgen.feature.SculkPatchFeature` | SculkShriekerBlockEntity.can_summon, SculkCatalystBlockEntity charge state | `METADATA_DISCARDED` | **HIGH** |
| 7 | **FLUID_SPRINGS (Water & Lava)** | Step 8: `FLUID_SPRINGS` | `spring_water, spring_lava` | `net.minecraft.world.level.levelgen.feature.SpringFeature` | WorldGenTickAccess<Fluid>, ProtoChunk scheduled tick ingestion | `SCHEDULED_TICKS_DROPPED` | **MEDIUM** |
| 8 | **STRUCTURE_PIECES_AND_TEMPLATES** | Step 4: `SURFACE_STRUCTURES / UNDERGROUND_STRUCTURES` | `structure_piece_generation` | `net.minecraft.world.level.levelgen.structure.StructurePiece` | ServerLevelAccessor.getLevel(), StructureTemplateManager, Entity placement | `CRASH_OR_METADATA_DISCARDED` | **CRITICAL** |
| 9 | **LIGHT_SENSITIVE_VEGETATION** | Step 9: `VEGETAL_DECORATION` | `trees, mushrooms, flowers` | `net.minecraft.world.level.levelgen.feature.TreeFeature` | LevelLightEngine (real block / sky light values) | `EMPTY_LIGHT_STUBBED` | **LOW** |

### Deep Dive by Feature Category

### 3.1. FOSSIL (Fossil Coal & Fossil Diamonds) (Severity: CRITICAL)

> **Status 2026-09-25: RESOLVED inside generation** (Step 8, `d29b252`, `3702265`). `RegionView` gets a real `StructureTemplateManager` with structures on, so fossil bones and skulls place (LARGE 1,418/1,418 exact). The `CRASH_CAUGHT_AND_SKIPPED` mode and the "100% of Overworld fossils fail" line below describe the pre-Step-8 code.

- **Decoration Step**: Step 3 (`UNDERGROUND_STRUCTURES`)
- **Feature Class**: `net.minecraft.world.level.levelgen.feature.FossilFeature`
- **Associated Placed Features**: `fossil_coal, fossil_diamonds`
- **Required Vanilla Access**: ServerLevelAccessor.getLevel(), StructureTemplateManager
- **Failure Mode in Kestrel**: `CRASH_CAUGHT_AND_SKIPPED`
- **Detailed Impact**: FossilFeature.place() calls level.getLevel().getStructureManager() to load data/minecraft/structures/fossil/spine_*.nbt and skull_*.nbt templates. RegionView.getLevel() returns null, causing an immediate NullPointerException. Caught at HeadlessNoise.java:806-815 and skipped. 100% of Overworld fossils fail to generate.

### 3.2. DUNGEONS / MONSTER_ROOM (Severity: CRITICAL)

> **Status 2026-09-25: RESOLVED inside generation.** `RegionView.getBlockEntity` now promotes the `DUMMY` tag to a real block entity exactly as `WorldGenRegion` does (`HeadlessNoise.java:2243`), so the spawner entity-type roll and the chest loot-table seed roll both execute and the intra-feature RNG stream stays synchronized with vanilla. Gate: `DungeonBlockEntityCheck`, which asserts two real Tier R dungeons. The live-world handoff still carries no block-entity NBT (header), so a live chest still has no loot table and a live spawner no configured mob.

- **Decoration Step**: Step 3 (`UNDERGROUND_STRUCTURES`)
- **Feature Class**: `net.minecraft.world.level.levelgen.feature.MonsterRoomFeature`
- **Associated Placed Features**: `monster_room`
- **Required Vanilla Access**: SpawnerBlockEntity configuration, RandomizableContainerBlockEntity.setLootTable, Block Entity NBT retention
- **Failure Mode in Kestrel**: `METADATA_DISCARDED`
- **Detailed Impact**: MonsterRoomFeature generates dungeon cobblestone, 1 mob spawner, and 1-2 chests. Spawner entity data (skeleton/zombie/spider) and chest loot tables (minecraft:chests/simple_dungeon) are overwritten with dummy NBT in setBlock and discarded during writeStoreCompact handoff. Result: Dungeons contain completely empty chests and unconfigured/dummy spawners.

### 3.3. BONUS_CHEST (Severity: HIGH)

> **Status 2026-09-25: PARTIALLY RESOLVED.** The feature no longer NPEs and the loot table is rolled onto the proto chunk's block entity as vanilla does (DUMMY promotion, header); the live-world handoff still transfers blocks only, so a live bonus chest is still empty.

- **Decoration Step**: Step 4 (`SURFACE_STRUCTURES`)
- **Feature Class**: `net.minecraft.world.level.levelgen.feature.BonusChestFeature`
- **Associated Placed Features**: `bonus_chest`
- **Required Vanilla Access**: LootTable resolution, Block Entity NBT retention, ServerLevelAccessor.getLevel()
- **Failure Mode in Kestrel**: `METADATA_DISCARDED_OR_SKIPPED`
- **Detailed Impact**: Starter bonus chest placed near spawn requires loot table minecraft:chests/spawn_bonus_chest. Dummy NBT in RegionView.setBlock and block-only handoff strip the loot table, leaving chest empty.

### 3.4. ARCHAEOLOGY / DESERT_WELL (Severity: HIGH)

> **Status 2026-09-25: PARTIALLY RESOLVED.** The `BrushableBlockEntity` loot table is rolled onto the proto chunk as vanilla does (DUMMY promotion, header); the live-world handoff still transfers blocks only, so live suspicious sand still carries no loot table.

- **Decoration Step**: Step 4 (`SURFACE_STRUCTURES`)
- **Feature Class**: `net.minecraft.world.level.levelgen.feature.DesertWellFeature`
- **Associated Placed Features**: `desert_well`
- **Required Vanilla Access**: BrushableBlockEntity NBT, Archaeology Loot Table
- **Failure Mode in Kestrel**: `METADATA_DISCARDED`
- **Detailed Impact**: DesertWellFeature places suspicious sand with BrushableBlockEntity loot table BuiltInLootTables.DESERT_WELL_ARCHAEOLOGY. Block-only handoff discards block entity state, leaving suspicious sand with no loot table. Brushing it produces nothing.

### 3.5. BEES_AND_BEEHIVES (Vegetal Trees) (Severity: HIGH)

> **Status 2026-09-25: PARTIALLY RESOLVED.** Generated entities are no longer discarded: `HeadlessEntityServerLevel` buffers them and the native SPAWN gate matches 169/169 SPAWN stages and 36 entity insertions (`a5e51e6`). Transferring those entities into the live world remains open (header).

- **Decoration Step**: Step 9 (`VEGETAL_DECORATION`)
- **Feature Class**: `net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator`
- **Associated Placed Features**: `oak_checked, birch_checked, super_birch_bees_0002, fancy_oak_checked`
- **Required Vanilla Access**: WorldGenLevel.addFreshEntity(), BeehiveBlockEntity NBT retention
- **Failure Mode in Kestrel**: `ENTITIES_DROPPED`
- **Detailed Impact**: When trees place bee nests via BeehiveDecorator, 1-3 EntityType.BEE instances are spawned via level.addFreshEntity(bee) or attached to BeehiveBlockEntity. RegionView.addFreshEntity() returns true and silently drops the entity, and setBlock writes DUMMY NBT. Result: All naturally generated bee nests are completely empty; no bees spawn in the world.

### 3.6. DEEP_DARK_SCULK (Shriekers & Catalysts) (Severity: HIGH)

> **Status 2026-09-25: RESOLVED inside generation.** The `DUMMY` tag is promoted exactly as `WorldGenRegion` does, so shrieker configuration (including `can_summon`) is produced as vanilla produces it. The live world still receives no block-entity NBT, so that state cannot yet reach it (header).

- **Decoration Step**: Step 7 (`UNDERGROUND_DECORATION`)
- **Feature Class**: `net.minecraft.world.level.levelgen.feature.SculkPatchFeature`
- **Associated Placed Features**: `sculk_patch_deep, sculk_patch_ancient_city, sculk_vein`
- **Required Vanilla Access**: SculkShriekerBlockEntity.can_summon, SculkCatalystBlockEntity charge state
- **Failure Mode in Kestrel**: `METADATA_DISCARDED`
- **Detailed Impact**: Naturally placed sculk shriekers must have can_summon: true to summon the Warden. Dummy NBT in RegionView and block-only handoff discard shrieker block entity NBT, preventing naturally generated shriekers from ever summoning the Warden.

### 3.7. FLUID_SPRINGS (Water & Lava) (Severity: MEDIUM)

> **Status 2026-09-25:** Live fluid simulation and fluid ticks landed in G11 (`15ac05f`, `048056c`, `aabbaa2`) with KEX5 persistence; worldgen spring tick ingestion into the live loop remains open.

- **Decoration Step**: Step 8 (`FLUID_SPRINGS`)
- **Feature Class**: `net.minecraft.world.level.levelgen.feature.SpringFeature`
- **Associated Placed Features**: `spring_water, spring_lava`
- **Required Vanilla Access**: WorldGenTickAccess<Fluid>, ProtoChunk scheduled tick ingestion
- **Failure Mode in Kestrel**: `SCHEDULED_TICKS_DROPPED`
- **Detailed Impact**: SpringFeature schedules fluid ticks in RegionView.fluidTicks. ProtoChunk retains them, but writeStoreCompact does not transfer scheduled fluid ticks to Kestrel's SectionStore/TickLoop. Water and lava springs remain static and unflowing until an external neighbor update occurs.

### 3.8. STRUCTURE_PIECES_AND_TEMPLATES (Severity: CRITICAL)

> **Status 2026-09-25: RESOLVED inside generation and on the live path.** `loadStructural` runs real STRUCTURE_STARTS, references and piece placement, structure data is round-tripped through generation, and the live generator is built from the structural driver (`net/KestrelServer.java:178-190`, logging `live generator structures=on`). Structure entities and container loot still depend on the live handoff (sections 3.2 and 3.5).

- **Decoration Step**: Step 4 (`SURFACE_STRUCTURES / UNDERGROUND_STRUCTURES`)
- **Feature Class**: `net.minecraft.world.level.levelgen.structure.StructurePiece`
- **Associated Placed Features**: `structure_piece_generation`
- **Required Vanilla Access**: ServerLevelAccessor.getLevel(), StructureTemplateManager, Entity placement
- **Failure Mode in Kestrel**: `CRASH_OR_METADATA_DISCARDED`
- **Detailed Impact**: Step 8 generates structure starts, but piece placement (the FEATURES structure block) requires StructureTemplateManager via level.getLevel().getStructureManager() for NBT templates (Villages, Ancient Cities, Trail Ruins, Trial Chambers). Furthermore, structure entities (villagers, allays, iron golems) are dropped by addFreshEntity, and chests lose loot tables.

### 3.9. LIGHT_SENSITIVE_VEGETATION (Severity: LOW)

> **Status 2026-09-25: RESOLVED.** Generation uses a real light engine (Step 9, `a90ea59`) and reproduces the light vanilla computes during generation: the live features path and the lighting gates construct `RegionView` with `lighting.engine()` (`worldgen/LiveGenerator.java:922-926`), so light-dependent features no longer read `LevelLightEngine.EMPTY`. The bare 3-argument `RegionView` constructor still defaults to `EMPTY` for older standalone checks.

- **Decoration Step**: Step 9 (`VEGETAL_DECORATION`)
- **Feature Class**: `net.minecraft.world.level.levelgen.feature.TreeFeature`
- **Associated Placed Features**: `trees, mushrooms, flowers`
- **Required Vanilla Access**: LevelLightEngine (real block / sky light values)
- **Failure Mode in Kestrel**: `EMPTY_LIGHT_STUBBED`
- **Detailed Impact**: RegionView.getLightEngine() returns LevelLightEngine.EMPTY (light level 0 everywhere). Features evaluating sky or block light for tree canopy clearance or vegetation survival execute under dark conditions, causing subtle placement deviations from vanilla.

---

## 4. Part C: Concrete Requirements for Stage E Remediation

To resolve Finding H2 and achieve production-grade world generation parity without crashing or sacrificing performance, 
**Stage E** must implement the following concrete engineering requirements:

### STAGE_E_REQ_1: Headless ServerLevel / StructureTemplateManager Bridge

Implement a lightweight ServerLevel facade or proxy in RegionView.getLevel() that exposes getStructureManager(). Wire this to HeadlessNoise.Structural's real StructureTemplateManager (which already loads data/minecraft/structures/*.nbt from the vanilla server pack). This prevents NullPointerException in FossilFeature and TemplateFeature, allowing fossil bones, skulls, and structure templates to generate.

**Status 2026-09-25: IMPLEMENTED** inside generation (Step 8, `d29b252`, `3702265`; fossils and templates place, LARGE 1,418/1,418 exact).

### STAGE_E_REQ_2: Entity Buffering and Retention Pipeline

Replace RegionView.addFreshEntity's silent return true with an entity collection sink (e.g. List<Entity> per chunk). Modify the generation handoff in LiveGenerator to transfer collected entities to Kestrel's entity storage / spawn queues, ensuring bees, mobs, and structure entities persist.

**Status 2026-09-25: IMPLEMENTED inside generation** (`HeadlessEntityServerLevel`, native SPAWN gate 169/169 with 36 entity insertions, `a5e51e6`). The live transfer half is open, and it depends on S14 (`preresearch/s14-entity-system-preresearch.md`).

### STAGE_E_REQ_3: Rich Block Entity Storage and State NBT Preservation

Eliminate the {"id": "DUMMY"} stub in RegionView.setBlock. Retain authentic block entity NBT on ProtoChunk (including loot table identifiers, seeds, mob spawner configurations, and sculk shrieker can_summon tags). Extend writeStoreCompact and SectionStore/WorldStore to serialize and persist block entity NBT into a sidecar or dedicated block entity map.

**Status 2026-09-25: PARTIALLY IMPLEMENTED.** The proto side is settled: the `DUMMY` tag mirrors Vanilla's own `WorldGenRegion` and is promoted to a real block entity on first read, so the configuration hooks (and their RNG draws) run as vanilla runs them. The live side is open: `KestrelLevel.BlockEntityRecord` holds only position and type, and `writeStoreCompact`/`SectionStore`/`WorldStore` still persist no block-entity NBT.

### STAGE_E_REQ_4: Scheduled Tick Ingestion (BlockTicks & FluidTicks)

Ingest ProtoChunk.getBlockTicks() and ProtoChunk.getFluidTicks() during generation handoff. Insert scheduled ticks into Kestrel's TickLoop schedule so natural water/lava springs flow and falling sand / redstone ticks initialize properly without requiring manual player triggers.

**Status 2026-09-25: STILL OPEN.** The live handoff transfers no scheduled block or fluid ticks. Redstone and fluid simulation themselves are now live (G11 `FluidParityCheck` 25/25 exact; G12 `RedstoneParityCheck` 29/29 exact), so what is missing is the ingestion of worldgen-produced schedules, not the schedulers. See section 3.7 and H2.7.

### STAGE_E_REQ_5: Robust LightEngine & Heightmap Evaluation

Upgrade RegionView light handling from LevelLightEngine.EMPTY to a functional light accessor or heightmap-based skylight proxy, ensuring light-dependent vegetation features place with 100% vanilla fidelity.

**Status 2026-09-25: IMPLEMENTED for light** (real `LevelLightEngine` lifecycle, Step 9, `a90ea59`; see the summary at the top). Remaining network/disk heightmaps are tracked under H2 and Stage E, not here.

### STAGE_E_REQ_6: Failure Classification, Diagnostics, and Honest Completion State

Refactor HeadlessNoise.java:806-815. Distinguish between expected benign placement skips and fatal infrastructure crashes (NPE, UOE). When unexpected failures occur, record the error in chunk metadata or quarantine the chunk instead of prematurely executing store.markChunkGenerated(centerKey) and treating failed generation as success.

**Status 2026-09-25: PARTIALLY IMPLEMENTED.** The parity gates install a `featureFailure` hook so a feature exception fails the chunk instead of being counted as a successful generation (`LIVE_FEATURE_FAILURE`). Production still catches `RuntimeException`, logs `[kestrel] feature placement failed, skipping` and completes the chunk (`worldgen/HeadlessNoise.java:1676-1690`), because that loop runs on the sole main thread; H2.8 therefore stays partly open.

---

## 5. Verification and Audit Checklist

- [ ] **H2.1**: `RegionView.getLevel()` returns non-null facade with valid `StructureTemplateManager`.
- [ ] **H2.2**: `FossilFeature` places bone/skull structures successfully without NPE.
- [ ] **H2.3**: `RegionView.addFreshEntity` buffers spawned entities into a chunk entity queue.
- [ ] **H2.4**: Beehive decorators place nests with persistent bee entities.
- [ ] **H2.5**: Monster room dungeons retain mob spawner entity type and chest loot tables.
- [ ] **H2.6**: Sculk shriekers retain `can_summon: true` flag upon generation.
- [ ] **H2.7**: Scheduled fluid/block ticks are ingested into `TickLoop` upon chunk handoff.
- [ ] **H2.8**: Uncaught feature generation exceptions halt premature completion marking and flag chunk generation status.

**Status 2026-09-25** (the boxes above are kept as written): H2.1, H2.2, H2.6 and the gate half of H2.8 are closed inside generation; H2.3 and H2.4 are closed inside generation only (`HeadlessEntityServerLevel`, with live entity transfer still open); H2.5 is closed inside generation (`DungeonBlockEntityCheck`) but the live handoff still drops the NBT; H2.7 is still open.

*(Report generated automatically by `harness/tools/feature-census.py`)*

Documentation sweep 2026-09-28, main `198a6b2`: this file was reviewed for claims
the code now contradicts and none were found. The dated "Status 2026-09-25" line
above was not independently re-derived in that sweep; it is kept as written and
still needs a per-item re-check against the current gates before it is quoted.
