# S14 Pre-Research: Entity System Architecture and Parity Specification

Status: PRE-RESEARCH, grounded in the decompiled vanilla 26.2 server jar (`server-26.2.jar`) in `~/kestrel-run/vanilla-src-26.2`, datapack game data, and existing Kestrel implementations in `core/src/vanilla/java/dev/kestrel/`.
Target: Ground truth specification and architecture roadmap for roadmap item S14 (entity system) in Kestrel.
Scope: Architectural analysis, vanilla code audits with exact file:line citations, networking definitions, NBT storage specifications, overlap analysis with G7, and vertical implementation slices. No Java source changes, no builds, no servers.

---

## 1. Executive Summary and Scope

### 1.1 Context and Scope Boundary
Roadmap item S14 defines the core entity system infrastructure for Kestrel:
- Entity storage per chunk and level (`PersistentEntitySectionManager`, `EntitySectionStorage`, `EntitySection`, `EntityLookup`).
- Entity ticking phase integrated into Kestrel's `TickLoop` in exact vanilla order relative to block ticks, fluid ticks, block events, and block entities.
- Entity IDs (`AtomicInteger`), UUIDs, and network tracking ranges (`ChunkMap.TrackedEntity`).
- Outbound client networking packets (`AddEntity`, `RemoveEntities`, `SetEntityData`, `MoveEntity`, `EntityPositionSync`, `RotateHead`, `SetEntityMotion`, `Bundle`) and delta versus full-sync criteria.
- Entity persistence in `entities/r.<rx>.<rz>.mca` region files (chunk NBT container format, recursive passenger trees, and subclass payload formats).
- Integration with Kestrel's existing swept block collision kernel (`KestrelCollide`).

### 1.2 Relationship and Overlaps with G7 Pre-Research
A detailed pre-research document already exists for mob AI and animals in `preresearch/g7-mobs-preresearch.md` (G7). S14 is the foundational entity engine upon which G7 (mobs), G15 (player bodies), G19 (dropped items), G22 (health/damage), G53 (projectiles), and G54 (vehicles) all depend.

The boundary between S14 and G7 is strictly partitioned as follows:
- **G7 (`preresearch/g7-mobs-preresearch.md`) specializes in:** Mob-specific AI (`GoalSelector`, goal priorities, flag exclusion), brain architectures (`makeBrain`), the eight initial animals and monsters (Cow, Pig, Sheep, Chicken, Zombie, Skeleton, Creeper, Spider), pathfinding algorithms (`PathFinder`, `WalkNodeEvaluator`), animal breeding, shearing, milking, daylight burning, and natural mob spawn caps (`NaturalSpawner`).
- **S14 (`preresearch/s14-entity-system-preresearch.md`) specializes in:** The generic server entity engine. This includes spatial chunk section storage, level-wide ID/UUID indexing, ticket-driven chunk loading/unloading lifecycles, main-thread tick ordering in `ServerLevel.tick`, client network tracking and delta encoding via `VecDeltaCodec` and `LpVec3`, and region file serialization in `entities/`.

Where topics intersect (such as the 16 entity packet inventory or general tick ordering), this document cites the specific mechanics required by the server entity infrastructure and avoids duplicating the mob AI and behavioral proofs detailed in G7.

---

## 2. Architecture Part 1: Entity Storage per Chunk and Level

In vanilla 26.2, entities are managed across three complementary indexing dimensions:
1. **Spatial 16x16x16 Section Storage:** Fast local queries for collision, raycasting, and player interaction.
2. **Global Level ID and UUID Storage:** O(1) random access by network entity ID (int) and persistence identifier (UUID).
3. **Chunk Ticket Visibility Lifecycle:** Automated activation, deactivation, disk loading, and disk unloading synchronized with chunk ticket status.

### 2.1 PersistentEntitySectionManager
The top-level coordinator for entity storage in a server level is `PersistentEntitySectionManager<T extends EntityAccess>` (`net/minecraft/world/level/entity/PersistentEntitySectionManager.java:34-464`). It is instantiated directly in `ServerLevel.java:258`:

```java
// ServerLevel.java:258
this.entityManager = new PersistentEntitySectionManager<>(Entity.class, new ServerLevel.EntityCallbacks(), entityStorage);
```

#### Core State Fields (`PersistentEntitySectionManager.java:36-45`)
- `knownUuids` (`Set<UUID>`, `Sets.newHashSet()`): Tracks every entity UUID currently registered in the manager. Prevents duplicate entity insertion across all loaded sections (lines 36, 63-70).
- `visibleEntityStorage` (`EntityLookup<T>`, line 39): Stores entities that are in accessible chunks (`Visibility.isAccessible() == true`). Serves global queries by entity ID or UUID.
- `sectionStorage` (`EntitySectionStorage<T>`, line 40): Spatial index bucketing entities into 16x16x16 `EntitySection` volumes.
- `chunkVisibility` (`Long2ObjectMap<Visibility>`, line 42): Maps packed chunk positions (`ChunkPos.pack()`) to current `Visibility` status (`HIDDEN`, `TRACKED`, or `TICKING`). Default return value is `Visibility.HIDDEN` (line 50).
- `chunkLoadStatuses` (`Long2ObjectMap<ChunkLoadStatus>`, line 43): Maps packed chunk positions to load states: `FRESH`, `PENDING`, or `LOADED`. Default return value is `FRESH` (line 51, 458-462).
- `chunksToUnload` (`LongSet`, `LongOpenHashSet`, line 44): Tracks chunk positions whose visibility transitioned to `HIDDEN` and are awaiting disk write and unloading.
- `loadingInbox` (`Queue<ChunkEntities<T>>`, `Queues.newConcurrentLinkedQueue()`, line 45): Thread-safe queue where asynchronous chunk reads from `EntityStorage` are deposited before being integrated into the main level thread.
- `callbacks` (`LevelCallback<T>`, line 37): Dispatches entity lifecycle events to the level (`ServerLevel.EntityCallbacks`, `ServerLevel.java:1908-1980`).
- `permanentStorage` (`EntityPersistentStorage<T>`, line 38): Disk storage backend (`EntityStorage`).
- `entityGetter` (`LevelEntityGetterAdapter<T>`, line 54): Combines `visibleEntityStorage` and `sectionStorage` behind the public `LevelEntityGetter` interface.

#### Adding Entities (`PersistentEntitySectionManager.java:72-98`)
When an entity is added to the level:
1. `addEntityUuid(entity)` (line 77) tests `this.knownUuids.add(entity.getUUID())`. If the UUID is already present, a warning is logged and insertion fails (lines 64-69).
2. Calculates 16x16x16 section key: `long sectionKey = SectionPos.asLong(entity.blockPosition())` (line 80).
3. Obtains or creates the section: `EntitySection<T> entitySection = this.sectionStorage.getOrCreateSection(sectionKey)` (line 81).
4. Inserts entity: `entitySection.add(entity)` (line 82).
5. Installs level callback hook: `entity.setLevelCallback(new PersistentEntitySectionManager.Callback(entity, sectionKey, entitySection))` (line 83).
6. If the entity is freshly spawned (not loaded from disk, `!loaded`), fires `this.callbacks.onCreated(entity)` (line 85).
7. Evaluates effective status: `Visibility status = getEffectiveStatus(entity, entitySection.getStatus())` (line 88).
   - If `entity.isAlwaysTicking()` is true, effective status is forced to `Visibility.TICKING` regardless of chunk status (`PersistentEntitySectionManager.java:101-103`).
   - If `status.isAccessible()` is true: executes `startTracking(entity)` (line 90), which adds the entity to `visibleEntityStorage` (`EntityLookup`) and fires `callbacks.onTrackingStart(entity)` (`PersistentEntitySectionManager.java:125-128`).
   - If `status.isTicking()` is true: executes `startTicking(entity)` (line 94), which fires `callbacks.onTickingStart(entity)` (adding it to `ServerLevel.this.entityTickList`).

#### Section Hopping and Entity Movement (`PersistentEntitySectionManager.java:383-430`)
When an entity moves, its movement pipeline invokes `this.levelCallback.onMove()` (`Entity.java:809`), which enters `PersistentEntitySectionManager.Callback.onMove()`:
1. Reads current block position: `BlockPos pos = this.entity.blockPosition()`.
2. Computes `long newSectionPos = SectionPos.asLong(pos)`.
3. If `newSectionPos != this.currentSectionKey` (line 386):
   - Removes entity from old section: `this.currentSection.remove(this.entity)` (line 388).
   - Reaps empty section: `removeSectionIfEmpty(this.currentSectionKey, this.currentSection)` (lines 57-61, 393).
   - Obtains or creates new section: `EntitySection<T> newSection = PersistentEntitySectionManager.this.sectionStorage.getOrCreateSection(newSectionPos)` (line 394).
   - Adds entity to new section: `newSection.add(this.entity)` (line 395).
   - Updates internal section references (lines 396-397).
   - Evaluates status transitions via `updateStatus(previousStatus, newSection.getStatus())` (lines 402-430):
     - If crossing between accessible and non-accessible sections: calls `startTracking` or `stopTracking`.
     - If crossing between ticking and non-ticking sections: calls `startTicking` or `stopTicking`.
     - If accessible before and after: fires `callbacks.onSectionChange(this.entity)` (lines 407, 427).

#### Entity Removal (`PersistentEntitySectionManager.java:433-455`)
When `entity.remove(RemovalReason)` is called, `Callback.onRemove(reason)` executes:
1. Removes entity from `this.currentSection` (line 434).
2. If effective status was ticking, invokes `stopTicking(entity)` (line 441).
3. If effective status was accessible, invokes `stopTracking(entity)` (line 445), removing it from `visibleEntityStorage` and calling `callbacks.onTrackingEnd(entity)`.
4. If `reason.shouldDestroy()` is true: fires `callbacks.onDestroyed(entity)` (line 449), untracking waypoints and removing scoreboard entries (`ServerLevel.java:1920-1926`).
5. Removes entity UUID from `knownUuids` (line 452).
6. Resets callback: `entity.setLevelCallback(EntityInLevelCallback.NULL)` (line 453).
7. Reaps old section if empty: `removeSectionIfEmpty(this.currentSectionKey, this.currentSection)` (line 454).

### 2.2 EntitySectionStorage
`EntitySectionStorage<T extends EntityAccess>` (`net/minecraft/world/level/entity/EntitySectionStorage.java:24-129`) manages all active 16x16x16 sections in the level:
- Backed by `sections` (`Long2ObjectMap<EntitySection<T>>`, line 29) and `sectionIds` (`LongSortedSet`, `LongAVLTreeSet`, line 30).
- `sectionIds` enables fast range-based lookups using `LongSortedSet.subSet(lowestKey, highestKey + 1L)` without iterating all possible 3D coordinates.

#### Spatial Search Grace and AABB Expansion (`EntitySectionStorage.java:25-26, 37-62`)
When querying entities inside an `AABB`:
- `CHONKY_ENTITY_SEARCH_GRACE = 2` sections (32 blocks).
- `MAX_NON_CHONKY_ENTITY_SIZE = 4` blocks.
- Bounding box search range calculation (`EntitySectionStorage.java:38-43`):
  - `xMin = SectionPos.posToSectionCoord(bb.minX - 2.0)`
  - `yMin = SectionPos.posToSectionCoord(bb.minY - 4.0)`
  - `zMin = SectionPos.posToSectionCoord(bb.minZ - 2.0)`
  - `xMax = SectionPos.posToSectionCoord(bb.maxX + 2.0)`
  - `yMax = SectionPos.posToSectionCoord(bb.maxY + 0.0)`
  - `zMax = SectionPos.posToSectionCoord(bb.maxZ + 2.0)`
- For each coordinate, iterates `sectionIds.subSet` (line 48) to query only sections that currently contain entities.
- Filters: `entitySection != null && !entitySection.isEmpty() && entitySection.getStatus().isAccessible()` (line 56).

### 2.3 EntitySection
`EntitySection<T extends EntityAccess>` (`net/minecraft/world/level/entity/EntitySection.java:12-81`) represents a single 16x16x16 block cube:
- Storage container: `ClassInstanceMultiMap<T> storage` (`EntitySection.java:14, 19`). Automatically partitions contained entities by their concrete Java class, accelerating type-specific searches (e.g. searching only for `ItemEntity` or `Player` within a volume via `storage.find(type.getBaseClass())`, line 43).
- Status: Maintains its local `Visibility chunkStatus` (line 15).
- `getEntities(AABB, consumer)` (lines 30-38): Iterates stored entities, tests `entity.getBoundingBox().intersects(bb)`, and yields to the abortable consumer.

### 2.4 EntityLookup
`EntityLookup<T extends EntityAccess>` (`net/minecraft/world/level/entity/EntityLookup.java:15-65`) provides flat, level-wide entity lookup:
- `byId` (`Int2ObjectMap<T>`, `Int2ObjectLinkedOpenHashMap`, line 17): Direct array-indexed mapping from integer network ID to entity.
- `byUuid` (`Map<UUID, T>`, `HashMap`, line 18): Hash map from 128-bit UUID to entity.
- Lookups `getEntity(int id)` (line 52) and `getEntity(UUID id)` (line 57) run in O(1) time.
- Iteration: `getAllEntities()` returns an unmodifiable iterable directly over `this.byId.values()` (line 33).

### 2.5 Chunk Ticket Transitions and Load/Unload Synchronization
Entity loading and unloading is strictly driven by chunk ticket level updates:

#### Ticket Levels to Visibility Mapping (`Visibility.java:5-33`)
`FullChunkStatus` from `ChunkHolder` maps directly to entity `Visibility`:
- `FullChunkStatus.ENTITY_TICKING` (ticket level <= 31): Maps to `Visibility.TICKING` (`accessible = true`, `ticking = true`).
- `FullChunkStatus.BLOCK_TICKING` (ticket level 32) and `FullChunkStatus.FULL` (ticket level 33): Maps to `Visibility.TRACKED` (`accessible = true`, `ticking = false`).
- `FullChunkStatus.INACCESSIBLE` (ticket level >= 34): Maps to `Visibility.HIDDEN` (`accessible = false`, `ticking = false`).

#### Status Update Trigger (`PersistentEntitySectionManager.updateChunkStatus`, lines 135-171)
When `ChunkMap.onFullChunkStatusChange` (`ChunkMap.java:1305`) fires:
1. **Transition to Non-HIDDEN (`TRACKED` or `TICKING`):**
   - `this.chunkVisibility.put(chunkPosKey, chunkStatus)` (line 146).
   - `this.chunksToUnload.remove(chunkPosKey)` (line 147).
   - `ensureChunkQueuedForLoad(chunkPosKey)` (line 148):
     - If load status is `FRESH`: calls `requestChunkLoad(chunkPosKey)` (lines 175-176, 206-213).
     - Marks load status as `PENDING` (line 207).
     - Dispatches asynchronous disk read: `this.permanentStorage.loadEntities(pos).thenAccept(this.loadingInbox::add)` (line 209).
2. **Transition to HIDDEN:**
   - `this.chunkVisibility.remove(chunkPosKey)` (line 143).
   - `this.chunksToUnload.add(chunkPosKey)` (line 144).
3. **Propagating Visibility to Existing Sections (lines 151-170):**
   - For all allocated sections in that chunk:
     - `Visibility previousStatus = section.updateChunkStatus(chunkStatus);`
     - If transitioning into `TRACKED`: calls `startTracking` on all non-always-ticking entities (line 164).
     - If transitioning out of `TRACKED`: calls `stopTracking` (line 162).
     - If transitioning into `TICKING`: calls `startTicking` (line 168).
     - If transitioning out of `TICKING`: calls `stopTicking` (line 158).

#### Main Thread Tick Drain (`PersistentEntitySectionManager.tick`, lines 242-245)
Every tick, `entityManager.tick()` runs on the main server thread (`ServerLevel.java:456`):
1. **`processPendingLoads()` (lines 234-240):**
   - Polls loaded chunks from `loadingInbox`.
   - For each entity in the loaded chunk: invokes `addEntity(e, loaded = true)` (line 237).
   - Sets load status to `LOADED`: `this.chunkLoadStatuses.put(chunkPos, ChunkLoadStatus.LOADED)` (line 238).
2. **`processUnloads()` (lines 230-232, 215-223):**
   - Iterates `chunksToUnload`. If chunk is still `HIDDEN`, executes `processChunkUnload(chunkKey)`:
   - Invokes `storeChunkSections` with visitor `entity -> entity.getPassengersAndSelf().forEach(this::unloadEntity)`.
   - `unloadEntity(e)` (lines 225-228):
     - Sets removal reason: `e.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK)`.
     - Resets callback: `e.setLevelCallback(EntityInLevelCallback.NULL)`.
     - This removal triggers `Callback.onRemove`, cleanly stripping the entity from sections, tick lists, tracking lists, and UUID registries.
   - If write completes successfully: removes `chunkKey` from `chunkLoadStatuses` (line 220).

---

## 3. Architecture Part 2: Server Tick Order and Entity Ticking

### 3.1 Strict Subsystem Order in `ServerLevel.tick`
In `ServerLevel.tick(BooleanSupplier haveTime)` (`net/minecraft/server/level/ServerLevel.java:354-468`), all world subsystems execute in an unvarying sequence:

```
ServerLevel.tick(BooleanSupplier haveTime)
├── 1. World border: getWorldBorder().tick()                       (ServerLevel.java:362)
├── 2. Weather cycle: advanceWeatherCycle()                         (ServerLevel.java:364)
├── 3. Player sleep check & world clocks                            (ServerLevel.java:368-379)
├── 4. Sky brightness: updateSkyBrightness()                        (ServerLevel.java:381)
├── 5. Game time advancement: tickTime()                            (ServerLevel.java:383)
├── 6. Scheduled block ticks: blockTicks.tick(...)                  (ServerLevel.java:390)
├── 7. Scheduled fluid ticks: fluidTicks.tick(...)                  (ServerLevel.java:392)
├── 8. Raids: raids.tick(this)                                      (ServerLevel.java:398)
├── 9. Chunk source tick: getChunkSource().tick(haveTime, true)     (ServerLevel.java:402)
├── 10. Block events (pistons, chests): runBlockEvents()            (ServerLevel.java:405)
├── [Ticket activity check: emptyTime counter evaluation]           (ServerLevel.java:410-417)
├── 11. Gated by (this.emptyTime < 300):                            (ServerLevel.java:419)
│   ├── Entities: entityTickList.forEach(...)                       (ServerLevel.java:427-449)
│   └── Block entities: tickBlockEntities()                         (ServerLevel.java:451)
├── 12. Entity manager queue flush: entityManager.tick()            (ServerLevel.java:456)
└── 13. Debug synchronizers: debugSynchronizers.tick(...)           (ServerLevel.java:466)
```

#### Placement Relative to Block Ticks, Fluids, Events, and Block Entities
1. **Scheduled Block Ticks (`ServerLevel.java:390`)** execute first.
2. **Scheduled Fluid Ticks (`ServerLevel.java:392`)** execute second.
3. **Block Events (`ServerLevel.java:405`)** execute third, resolving delayed block actions like piston extensions and chest open/close latches.
4. **Entities (`ServerLevel.java:427-449`)** execute fourth.
5. **Block Entities (`ServerLevel.java:451`)** execute fifth, strictly after entities have finished moving and colliding.
6. **Entity Management Queue (`ServerLevel.java:456`)** executes sixth, flushing pending loads and unloads.

#### Dimension Sleep Gate (`emptyTime < 300`, line 419)
If a dimension has no active chunk tickets (`!this.chunkSource.hasActiveTickets()`, line 410), `emptyTime` increments each tick (line 416). Once `emptyTime >= 300` (15 seconds with no players or tickets):
- Both **Entities** and **Block Entities** cease ticking entirely!
- The server skips lines 420-453 until an active ticket resets `emptyTime = 0` (line 412).

### 3.2 `EntityTickList` Architecture
`EntityTickList` (`net/minecraft/world/level/entity/EntityTickList.java:12-66`) holds every entity eligible to tick:
- Uses two `Int2ObjectLinkedOpenHashMap` instances: `active` (line 13) and `passive` (line 14).
- Safe iteration without allocation: When `forEach` begins (line 48), `this.iterated = this.active`.
- If an entity is added (`add`, line 34) or removed (`remove`, line 39) while `iterated != null`, `ensureActiveIsNotIterated()` (lines 18-32) copies `active` into `passive` and swaps the references:
  > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
  This copy-on-write swap prevents `ConcurrentModificationException` and index corruption while allowing entities to spawn other entities mid-tick.

### 3.3 Entity Tick Execution and Passenger Delegation
Inside the `entityTickList.forEach` loop (`ServerLevel.java:427-449`):

1. **Removal Gate (line 428):** `if (!entity.isRemoved())`
2. **Tick Rate Manager Gate (line 429):** `if (!tickRateManager.isEntityFrozen(entity))`
3. **Despawn Check Precedes Everything (lines 430-432):**
   `entity.checkDespawn();`
   Despawn is evaluated on every unfrozen entity in the tick list, even if the entity is outside the ticking range!
4. **Ticking Range Gate (line 433):**
   `if (entity instanceof ServerPlayer || this.chunkSource.chunkMap.getDistanceManager().inEntityTickingRange(entity.chunkPosition().pack()))`
   Only entities in chunks with ticket level <= 31 execute movement and physics.
5. **Passenger Inversion (lines 434-441):**
   ```java
   Entity vehicle = entity.getVehicle();
   if (vehicle != null) {
      if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
         return; // Passenger does not tick directly!
      }
      entity.stopRiding();
   }
   ```
   If an entity is riding a valid vehicle, its direct tick iteration is skipped (`return;`). The vehicle ticks all its passengers.
6. **Guard Entity Tick (`ServerLevel.java:444`, `Level.java:556-565`):**
   `this.guardEntityTick(this::tickNonPassenger, entity);`
   Catches `Throwable`, populates crash report with entity category, and throws `ReportedException`.
7. **`tickNonPassenger` Pipeline (`ServerLevel.java:824-836`):**
   > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
8. **`tickPassenger` Pipeline (`ServerLevel.java:838-853`):**
   - If passenger is removed or vehicle mismatch: calls `entity.stopRiding()`.
   - Else if `entity instanceof Player || this.entityTickList.contains(entity)`:
     - Sets old pos and rot, increments `tickCount`.
     - Calls `entity.rideTick()` (updates position relative to vehicle mount attachment).
     - Recursively calls `tickPassenger` on any nested passengers.

### 3.4 Integration with Kestrel's `TickLoop`
Kestrel's current `TickLoop.java` (`core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1065-1080`) executes:
```java
public void tick() {
    gameTime++;
    drainScheduled();   // Block ticks
    drainFluidTicks();  // Fluid ticks
    level.sleepers().beginPass(); // Block entities
    ...
}
```
To achieve exact vanilla parity under S14, `TickLoop` must insert the entity tick phase strictly after fluid ticks and block events, and strictly before `level.sleepers().beginPass()`:
1. `gameTime++`
2. `drainScheduled()` (block ticks)
3. `drainFluidTicks()` (fluid ticks)
4. `runBlockEvents()` (block events)
5. `drainEntityTicks()` (iterate entity tick list, evaluate despawn, in-range test, passenger gating, and `tickNonPassenger`)
6. `level.sleepers().beginPass()` (block entities)
7. `drainEntityManagement()` (flush loading inbox and process unloads)

---

## 4. Architecture Part 3: Entity IDs, UUIDs, Tracking Range & Client Networking

### 4.1 Entity IDs and UUIDs

#### Entity IDs (`AtomicInteger`)
- Defined in `ServerLevel.java:195`:
  `private static final AtomicInteger ENTITY_COUNTER = new AtomicInteger();`
- Allocated in `ServerLevel.getNextEntityId()` (`ServerLevel.java:312-320`):
  > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
- Every entity assigned an ID in `Entity.<init>` (`Entity.java:317`): `this.id = level.getNextEntityId();`.
- IDs are ephemeral runtime integers sent in network packets. They are never saved to disk.

#### Entity UUIDs
- Initialized in `Entity.java:287`:
  `protected UUID uuid = Mth.createInsecureUUID(this.random);`
- Persistent 128-bit identifier stored in disk NBT (`UUIDUtil.CODEC`, `[I; int0, int1, int2, int3` format). Preserved across world restarts.

### 4.2 Tracking Ranges and Update Intervals
Client tracking parameters are configured via `EntityType.Builder` (`net/minecraft/world/entity/EntityType.java:482-501, 594-602`):
- `clientTrackingRange`: Radius in 16-block chunks. Default is 5 chunks (80 blocks) (`EntityType.java:490`).
- `updateInterval`: Frequency of network sync in game ticks. Default is 3 ticks (`EntityType.java:491`).

#### Canonical Values in 26.2 (`EntityTypes.java`)
| Entity Group | Entity Types | Tracking Range (Chunks / Blocks) | Update Interval (Ticks) | Source Registration in `EntityTypes.java` |
|---|---|---|---|---|
| **Players** | `PLAYER` | 32 chunks (512 blocks) | 2 ticks | `EntityTypes.java:1144-1145` |
| **Bosses** | `ENDER_DRAGON`, `WITHER` | 16 chunks (256 blocks) | 3 ticks | `EntityTypes.java:612, 1065` |
| **Special Mobs** | `WARDEN`, `LIGHTNING_BOLT` | 16 chunks (256 blocks) | 3 ticks / 2147483647 | `EntityTypes.java:1026, 612` |
| **Passive Animals** | `COW`, `PIG`, `SHEEP`, `CHICKEN`, `HORSE`, `VILLAGER`, `IRON_GOLEM`, `ALLAY`, `FROG` | 10 chunks (160 blocks) | 3 ticks | `EntityTypes.java:308, 754, 825, 294, 531, 1005, 556` |
| **Hostile Monsters** | `ZOMBIE`, `SKELETON`, `CREEPER`, `SPIDER`, `ENDERMAN`, `BLAZE`, `WITCH` | 8 chunks (128 blocks) | 3 ticks | `EntityTypes.java:1096, 845, 315, 908` |
| **Vehicles** | `BOAT`, `MINECART`, `CHEST_MINECART`, `HOPPER_MINECART` | 8 chunks (128 blocks) | 3 ticks | `EntityTypes.java:667, 527` |
| **Dropped Items** | `ITEM` (`ItemEntity`) | 6 chunks (96 blocks) | 20 ticks (1 second) | `EntityTypes.java:564-565` |
| **Projectiles** | `ARROW`, `SNOWBALL`, `SMALL_FIREBALL`, `EGG`, `POTION`, `TRIDENT` | 4 chunks (64 blocks) | 10 ticks (4 for arrows) | `EntityTypes.java:883, 625` |
| **Small Water Mobs** | `COD`, `SALMON`, `PUFFERFISH`, `TROPICAL_FISH`, `TADPOLE` | 4 chunks (64 blocks) | 3 ticks | `EntityTypes.java:806, 820, 987` |
| **Displays** | `ITEM_DISPLAY`, `BLOCK_DISPLAY`, `TEXT_DISPLAY` | 10 chunks (160 blocks) | 1 tick | `EntityTypes.java:569, 952` |
| **Markers** | `MARKER` | 0 chunks (not tracked) | 3 ticks | `EntityTypes.java:663` |

#### Passenger Range Expansion (`ChunkMap.TrackedEntity.getEffectiveRange`, `ChunkMap.java:1450-1461`)
When an entity carries passengers:
> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
If a boat (range 8 chunks) carries a player (range 32 chunks), the boat's effective tracking range expands to 32 chunks!

#### Player Visibility Evaluation (`ChunkMap.TrackedEntity.updatePlayer`, `ChunkMap.java:1421-1444`)
Visibility requires three conditions:
1. Horizontal 2D distance squared: $dx^2 + dz^2 \le \min(\text{effectiveRange}, \text{playerViewDistance} \times 16)^2$.
2. `entity.broadcastToPlayer(player)` is true.
3. `ChunkMap.isChunkTracked(player, entityChunkX, entityChunkZ)` is true. The chunk containing the entity must already be sent to the player.

### 4.3 Network Packets and Wire Formats

#### 1. `ClientboundAddEntityPacket` (`0x01` / 1, `net/minecraft/network/protocol/game/ClientboundAddEntityPacket.java:109-121`)
Spawns any entity on the client. Wire layout:
- `id`: VarInt (entity ID)
- `uuid`: UUID (16 bytes: 2 longs)
- `type`: VarInt (registry ID in `minecraft:entity_type`)
- `x`, `y`, `z`: 3 Doubles (24 bytes)
- `movement`: `Vec3.LP_STREAM_CODEC` (`LpVec3`)
- `xRot`: Byte (pitch packed: `(byte)(pitch * 256.0F / 360.0F)`)
- `yRot`: Byte (yaw packed: `(byte)(yaw * 256.0F / 360.0F)`)
- `yHeadRot`: Byte (head yaw packed)
- `data`: VarInt (entity-specific data, e.g. direction for item frames, thrower ID for projectiles)

#### 2. `LpVec3` Low-Precision Velocity (`net/minecraft/network/LpVec3.java:1-77`)
In Minecraft 26.2, spawn velocity and motion sync packets use `LpVec3`:
- If $\max(|x|, |y|, |z|) < 3.051944 \times 10^{-5}$: writes a single zero byte: `output.writeByte(0)` (`LpVec3.java:46-47`).
- Otherwise: packs into a bit-field with scale flags:
  - 15 bits each for X, Y, Z coordinates quantized against scale.
  - Scale encoded in lower bits with optional continuation VarInt (`LpVec3.java:49-62`).

#### 3. `ClientboundSetEntityDataPacket` (`0x63` / 99, `ClientboundSetEntityDataPacket.java:1-54`)
Synchronizes watched data values (`SynchedEntityData`):
- `id`: VarInt (entity ID)
- Repeated data items:
  - `id`: Unsigned byte (0 to 254)
  - `serializer_id`: VarInt
  - Serialized payload
- Terminator: Byte `255` (`0xFF`, `EOF_MARKER`, line 15, 26).

#### 4. `ClientboundRemoveEntitiesPacket` (`0x4D` / 77, `ClientboundRemoveEntitiesPacket.java:1-45`)
Despawns entities on the client:
- `count`: VarInt
- `entityIds`: Array of VarInt IDs

#### 5. `ClientboundBundlePacket` (`0x00` / 0)
When a player starts tracking an entity, `ServerEntity.addPairing` (`ServerEntity.java:268-273`) bundles spawn packets:
- `ClientboundAddEntityPacket`
- `ClientboundSetEntityDataPacket` (if initial non-default values exist)
- `ClientboundUpdateAttributesPacket` (for `LivingEntity`)
- `ClientboundSetEquipmentPacket` (for `LivingEntity` equipment)
- `ClientboundSetPassengersPacket` (if vehicle or passenger)
- `ClientboundSetEntityLinkPacket` (if leashed)
All wrapped in a delimiter-bracketed bundle packet.

### 4.4 Position Deltas vs Full Sync (`ServerEntity.java:143-176`)
Relative coordinate deltas are encoded via `VecDeltaCodec` (`net/minecraft/network/protocol/game/VecDeltaCodec.java`):
- **Scale factor:** $4096.0$ units per block.
- Quantization: $\text{encoded}(v) = \text{round}(v \times 4096.0)$.
- Delta: $\Delta = \text{encoded}(pos) - \text{encoded}(base)$.
- Short integer range: $[-32768, 32767]$, corresponding to $[-8.0, +7.999755859375]$ blocks per update.

#### Full Sync: `ClientboundEntityPositionSyncPacket` (`0x23` / 35)
Dispatched when ANY of the following five conditions occur (`ServerEntity.java:154-158`):
1. **Precise Position Requirement:** `this.entity.getRequiresPrecisePosition()` is true (line 154).
2. **Delta Overflow (`deltaTooBig`):** $|xa| > 32767$ or $|ya| > 32767$ or $|za| > 32767$ (moved $> 8.0$ blocks in any axis, line 153, 155).
3. **Forced Periodic Resync:** `this.teleportDelay > 400` ticks (forced absolute resync every 20 seconds, line 156).
4. **Dismount Event:** `this.wasRiding` is true (entity just dismounted a vehicle, line 157).
5. **Ground State Transition:** `this.wasOnGround != this.entity.onGround()` (`onGround` flag flipped, line 158).

Payload of `ClientboundEntityPositionSyncPacket` (`ClientboundEntityPositionSyncPacket.java:11-20`):
- `id`: VarInt
- `values`: `PositionMoveRotation` (Double x, y, z; Double vx, vy, vz; Float yaw; Float pitch)
- `onGround`: Boolean
Resets `positionCodec.setBase(currentPosition)` and `teleportDelay = 0`.

#### Relative Movement: `ClientboundMoveEntityPacket`
If none of the full-sync conditions are met:
- **Position Changed:** $\Delta^2 \ge 7.6293945 \times 10^{-6}$ or `tickCount % 60 == 0` (forced pos update every 3 seconds, line 145, 147).
- **Rotation Changed:** $|\Delta yRot| \ge 1$ or $|\Delta xRot| \ge 1$ packed degree (line 129).
- Packet selection:
  - Both position and rotation changed: `ClientboundMoveEntityPacket.PosRot` (`0x36` / 54) (short xa, ya, za, byte yaw, byte pitch, boolean onGround).
  - Only position changed: `ClientboundMoveEntityPacket.Pos` (`0x35` / 53) (short xa, ya, za, boolean onGround).
  - Only rotation changed: `ClientboundMoveEntityPacket.Rot` (`0x38` / 56) (byte yaw, byte pitch, boolean onGround).
  - Neither changed: No movement packet sent.
- Passenger special case: If `entity.isPassenger()` is true, only `MoveEntityPacket.Rot` is ever sent; position is omitted because the vehicle moves the passenger.

---

## 5. Architecture Part 4: Entity Saving in `entities/` Region Files (NBT Layout)

### 5.1 Region File Storage Container
Entities are stored in dedicated MCA files in the world folder under `entities/r.<rx>.<rz>.mca` (`ServerLevel.java:250`, `EntityStorage.java:30-43`):
- Managed by `SimpleRegionStorage` (`net/minecraft/world/level/chunk/storage/SimpleRegionStorage.java`).
- Each chunk payload is a compressed NBT compound tag.

#### Chunk Root NBT Structure (`EntityStorage.java:105-107`)
```
CompoundTag {
   "DataVersion": IntTag,       // e.g. 4440+ in 26.2
   "Position": IntArrayTag,     // [I; chunkX, chunkZ]
   "Entities": ListTag [        // List of CompoundTags (tag type 10)
      CompoundTag { ... },      // Entity 0
      CompoundTag { ... }       // Entity 1
   ]
}
```
If a chunk has no entities, `simpleRegionStorage.write(pos, IOWorker.STORE_EMPTY)` writes an empty marker or deletes the sector (`EntityStorage.java:93`).

### 5.2 Recursive Passenger Tree vs Root Entities
In `Entity.save(ValueOutput)` (`net/minecraft/world/entity/Entity.java:2079-2081`):
```java
public boolean save(final ValueOutput output) {
   return this.isPassenger() ? false : this.saveAsPassenger(output);
}
```
- **Fundamental Rule:** Any entity that has a vehicle returns `false` from `save(output)`. Therefore, **only root entities are stored directly in the chunk's `Entities` list**.
- If a root entity has passengers (`this.isVehicle()` is true), it writes a `Passengers: ListTag<CompoundTag>` tag (`Entity.java:2135-2148`).
- Each passenger recursively saves its data inside `Passengers`. This preserves deep passenger hierarchies (e.g. a Chicken jockey riding a Zombie in a Minecart).

### 5.3 Field-by-Field NBT Layout

#### Base Entity Fields (`Entity.java:2083-2134`)
Every entity serialized to disk contains these base fields:
- `id`: String (`getEncodeId()`, e.g. `"minecraft:item"`, `"minecraft:cow"`, `"minecraft:zombie"`). If null, entity is not serializable.
- `Pos`: ListTag of 3 DoubleTags `[x, y, z]`. If riding a vehicle, stores `[vehicle.getX(), entity.getY(), vehicle.getZ()]`.
- `Motion`: ListTag of 3 DoubleTags `[vx, vy, vz]`.
- `Rotation`: ListTag of 2 FloatTags `[yaw, pitch]`.
- `fall_distance`: DoubleTag (accumulated fall distance).
- `Fire`: ShortTag (remaining fire ticks).
- `Air`: ShortTag (air supply ticks).
- `OnGround`: ByteTag (`0` or `1`).
- `Invulnerable`: ByteTag (`0` or `1`).
- `PortalCooldown`: IntTag.
- `UUID`: IntArrayTag of 4 Ints (`[I; i0, i1, i2, i3]`).
- `CustomName`: StringTag (JSON text component, optional).
- `CustomNameVisible`: ByteTag (optional).
- `Silent`: ByteTag (optional).
- `NoGravity`: ByteTag (optional).
- `Glowing`: ByteTag (optional).
- `TicksFrozen`: IntTag (optional).
- `HasVisualFire`: ByteTag (optional).
- `Tags`: ListTag of StringTags (scoreboard tags).
- `data`: CompoundTag (custom entity data, optional).
- `Passengers`: ListTag of CompoundTags (present only if `isVehicle() == true`).

#### ItemEntity Subclass Fields (`ItemEntity.java:315-324`)
- `Health`: ShortTag (entity health, default 5).
- `Age`: ShortTag (ticks alive, 0 to 6000; at 6000 ticks = 5 minutes it despawns; -32768 indicates permanent lifespan).
- `PickupDelay`: ShortTag (ticks until player pickup allowed, 0 to 40).
- `Thrower`: UUID / EntityReference (thrower identifier).
- `Owner`: UUID (optional target player UUID).
- `Item`: CompoundTag (`ItemStack.CODEC`: `id` string, `count` int, `components` compound).

#### LivingEntity Subclass Fields (`LivingEntity.java:760-792`)
- `Health`: FloatTag (current health).
- `HurtTime`: ShortTag.
- `DeathTime`: ShortTag (ticks since death, 0 to 20).
- `AbsorptionAmount`: FloatTag.
- `attributes`: ListTag of CompoundTags (`AttributeInstance.Packed.LIST_CODEC`):
  - `id`: StringTag (e.g. `"minecraft:max_health"`)
  - `base`: DoubleTag
  - `modifiers`: ListTag of CompoundTags (modifier UUID/id, amount, operation)
- `active_effects`: ListTag of CompoundTags (potion effects).
- `FallFlying`: ByteTag (elytra flight flag).
- `sleeping_pos`: IntArrayTag `[I; x, y, z]` (optional).
- `Brain`: CompoundTag (packed brain memories and schedules).
- `equipment`: CompoundTag (`EntityEquipment.CODEC`: held items and armor).

#### Mob Subclass Fields (`Mob.java:380-399`)
- `CanPickUpLoot`: ByteTag (`0` or `1`).
- `PersistenceRequired`: ByteTag (`0` or `1`). Prevents natural despawn.
- `drop_chances`: CompoundTag (slot equipment drop chances).
- `leash`: CompoundTag (leash link details).
- `home_radius`: IntTag, `home_pos`: IntArrayTag `[I; x, y, z]`.
- `LeftHanded`: ByteTag (`0` or `1`).
- `DeathLootTable`: StringTag, `DeathLootTableSeed`: LongTag (optional).
- `NoAI`: ByteTag (`0` or `1`).

### 5.4 Loading Pipeline (`EntityType.java:382-418`, `EntityStorage.java:71-76`)
1. `EntityStorage.loadEntities` loads the chunk tag and extracts `ValueInput.ValueInputList entities = chunkRoot.childrenListOrEmpty("Entities")`.
2. Calls `EntityType.loadEntitiesRecursive(entities, level, EntitySpawnReason.LOAD)`:
   - Reads `id` string, looks up `EntityType` in registry, and calls `create(input, level, request)` (`EntityType.java:422`).
   - Invokes `entity.load(input)` (`Entity.java:2157-2205`) to populate all fields.
   - Calls `loadPassengersRecursive` (`EntityType.java:400-411`): for each compound in `Passengers`, recursively loads the entity and invokes `passenger.startRiding(entity, true, false)`.
3. Returns `ChunkEntities<Entity>` which is posted to `loadingInbox`.

---

## 6. Suggested Build Order in Small Vertical Slices

To implement S14 with deterministic verification at every step, the work is organized into six vertical slices. Each slice is testable in isolation before moving to the next.

```
┌─────────────────────────────────────────────────────────────┐
│ Slice 1: Spatial Entity Storage & In-Memory Indexing        │
│ (EntitySection, EntitySectionStorage, EntityLookup)         │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Slice 2: Entity Tracking Engine & Client Networking         │
│ (Outbound serializers, ServerEntity, VecDeltaCodec, LpVec3) │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Slice 3: Item Entities & Server Tick Loop Integration       │
│ (ItemEntity, TickLoop entity phase, KestrelCollide gravity) │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Slice 4: Region File Persistence in entities/               │
│ (SimpleRegionStorage, EntityStorage, NBT passenger trees)   │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Slice 5: Dynamic Chunk Visibility & Load/Unload Lifecycle   │
│ (Visibility transitions, loadingInbox, processUnloads)      │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Slice 6: Simple Living Mob Shell (Stationary Cow / Zombie)  │
│ (LivingEntity attributes, SynchedEntityData, pose sync)     │
└─────────────────────────────────────────────────────────────┘
```

### Slice 1: Spatial Entity Storage and In-Memory Indexing
- **Objective:** Implement the core in-memory entity data structures independent of ticking and networking.
- **Classes to Implement:**
  - `EntityAccess` and `EntityInLevelCallback` interfaces.
  - `EntityLookup` with dual `Int2ObjectLinkedOpenHashMap` (by ID) and `HashMap<UUID>` (by UUID).
  - `EntitySection` wrapping `ClassInstanceMultiMap` with local `Visibility`.
  - `EntitySectionStorage` with `sectionIds` AVL-tree and $2$-section search grace for `AABB` overlap queries.
  - `PersistentEntitySectionManager` skeleton managing `knownUuids`, adding entities, and handling section-crossing callbacks (`onMove`, `onRemove`).
- **Testable Parity Check (`EntityStorageCheck.java`):**
  - Allocate 100 dummy entities across multiple chunk and section boundaries.
  - Assert that querying an `AABB` straddling 4 sections returns the exact expected subset of entities.
  - Assert that `getEntity(id)` and `getEntity(uuid)` return in O(1) time.
  - Move an entity across a section boundary; assert it is removed from the old section and present in the new section without memory leaks.
  - Attempt to insert an entity with an existing UUID; assert insertion is rejected.

### Slice 2: Entity Tracking Engine and Client Networking
- **Objective:** Enable server-to-client network synchronization for stationary and moving entities.
- **Classes to Implement:**
  - Add packet IDs to `core/src/vanilla/java/dev/kestrel/net/PacketIds.java`:
    - `ADD_ENTITY` (`0x01`), `REMOVE_ENTITIES` (`0x4D`), `SET_ENTITY_DATA` (`0x63`), `MOVE_ENTITY_POS` (`0x35`), `MOVE_ENTITY_ROT` (`0x38`), `MOVE_ENTITY_POS_ROT` (`0x36`), `ENTITY_POSITION_SYNC` (`0x23`), `ROTATE_HEAD` (`0x53`), `SET_ENTITY_MOTION` (`0x65`), `BUNDLE` (`0x00`).
  - Outbound binary serializers in `Outbound.java` including `LpVec3` encoding.
  - `VecDeltaCodec` (4096.0 scale factor).
  - `ServerEntity` tracking logic:
    - Delta versus full-sync gating (5 triggers: precise position, delta $> 8.0$ blocks, 400-tick resync, dismount, onGround transition).
    - Periodic 60-tick position refresh.
    - Dirty data tracker flushing with byte 255 terminator.
  - `ChunkMap.TrackedEntity` equivalent managing player visibility sets based on horizontal distance and chunk tracking.
- **Testable Parity Check (`TrackedEntityParityCheck.java`):**
  - Replay a recorded sequence of vanilla entity movements (standing still, walking within 8 blocks, teleporting 20 blocks, jumping to change onGround).
  - Assert that Kestrel emits byte-identical packets: `PosRot` on small movements, `EntityPositionSync` on $> 8$-block jumps, `onGround` flips, and 400-tick intervals.
  - Verify `BundlePacket` wire format on initial player tracking.

### Slice 3: Item Entities and Server Tick Loop Integration
- **Objective:** Implement the simplest physical entity (`ItemEntity`), integrate entity ticking into Kestrel's `TickLoop`, and verify collision with terrain.
- **Classes to Implement:**
  - `EntityTickList` with copy-on-write double buffering.
  - Integrate entity tick phase into `TickLoop.java` strictly after `drainFluidTicks()` and strictly before `level.sleepers().beginPass()`.
  - Base `Entity` physics: `xo/yo/zo`, `deltaMovement`, gravity (-0.04 blocks/tick$^2$ for items), air drag (0.98), ground friction (0.6).
  - Wire `Entity.move` to `KestrelCollide.collide` for swept block collision resolution and floor resting.
  - `ItemEntity` subclass: `age++`, 6000-tick despawn timer, pickup delay countdown, ItemStack payload.
- **Testable Parity Check (`ItemEntityTickCheck.java`):**
  - Drop an item at $(0.5, 70.0, 0.5)$ in a flat world.
  - Advance `TickLoop` for 40 ticks.
  - Assert that sub-tick execution matches vanilla order (block ticks -> fluid ticks -> entity ticks -> block entities).
  - Assert that the item accelerates downward, hits the floor via `KestrelCollide`, rests at $Y=4.0$ with $onGround=true$, and stops moving.
  - Advance 6000 ticks and assert that the entity discards itself.

### Slice 4: Region File Persistence in `entities/`
- **Objective:** Save and load entity chunks in `entities/r.<rx>.<rz>.mca` with full NBT fidelity.
- **Classes to Implement:**
  - `EntityStorage` implementing `EntityPersistentStorage<Entity>`.
  - Wire `SimpleRegionStorage` to the `entities/` directory.
  - Chunk NBT codec: `DataVersion`, `Position: [I; x, z]`, `Entities: ListTag`.
  - Recursive passenger serialization (`saveWithoutId`, `Passengers: []`).
  - Entity NBT loaders: `EntityType.loadEntitiesRecursive` and `Entity.load`.
- **Testable Parity Check (`EntityRegionRoundTripCheck.java`):**
  - Populate a chunk with 10 item entities and a stacked passenger pair (e.g. item riding an entity).
  - Serialize to MCA via `EntityStorage.storeEntities`.
  - Read back via `EntityStorage.loadEntities`.
  - Assert that:
    - Root entities are properly restored with bit-exact coordinates, velocities, rotations, and item data.
    - Passengers are reconstructed in the riding hierarchy.
    - `knownUuids` contains all loaded UUIDs.
    - An empty chunk writes `IOWorker.STORE_EMPTY`.

### Slice 5: Dynamic Chunk Visibility and Load/Unload Lifecycle
- **Objective:** Synchronize entity storage with chunk ticket levels (`Visibility`).
- **Classes to Implement:**
  - `Visibility` transitions in `PersistentEntitySectionManager.updateChunkStatus`.
  - Asynchronous loading via `loadingInbox` drained during `entityManager.tick()`.
  - `chunksToUnload` processing via `processChunkUnload`, flushing entities to disk with `RemovalReason.UNLOADED_TO_CHUNK`.
  - Status demotions (`TICKING` -> `TRACKED` strips entity from `EntityTickList`; `TRACKED` -> `HIDDEN` unregisters from `visibleEntityStorage`).
- **Testable Parity Check (`ChunkEntityLifecycleCheck.java`):**
  - Transition a chunk: `HIDDEN` -> `TRACKED` -> `TICKING` -> `TRACKED` -> `HIDDEN`.
  - Assert that when `TRACKED`, entities exist in `EntityLookup` but do not tick in `TickLoop`.
  - Assert that when `TICKING`, entities execute physics and increment `tickCount`.
  - Assert that when demoted to `HIDDEN`, entities are saved to disk, removed from memory, and their UUIDs released from `knownUuids`.

### Slice 6: Simple Living Mob Shell (Stationary Cow / Zombie)
- **Objective:** Add `LivingEntity` attributes, `SynchedEntityData`, and pose tracking, establishing the foundation for G7 mob AI.
- **Classes to Implement:**
  - `LivingEntity` base class:
    - `AttributeMap` and syncable attributes (`max_health`, `movement_speed`).
    - Health tracking, hurt timer, death timer.
  - `SynchedEntityData` data tracker (flags, air supply, custom name, pose, silent, no gravity).
  - Basic `Mob` subclass shell: `persistenceRequired`, `noAi`.
  - Spawning via `/summon` or command API, sending the full `BundlePacket` to nearby players.
- **Testable Parity Check (`SimpleMobSyncCheck.java`):**
  - Summon a stationary `Cow` with `NoAI = true`.
  - Verify that a connected client receives the `BundlePacket` without `DecoderException`.
  - Verify that the client displays the stationary cow model with correct bounding box, health attribute, and rotation.
  - Punch the cow and verify that `ClientboundDamageEventPacket` (`0x19`) and health sync packets are emitted.

---

## 7. Summary of Gaps in Kestrel Today

Auditing Kestrel's current repository (`core/src/vanilla/java/dev/kestrel/`):

| Subsystem | Existing Kestrel Code | What is Missing for S14 |
|---|---|---|
| **Entity Storage** | None (`core/src/vanilla/java/dev/kestrel/worldgen/HeadlessEntityServerLevel.java` is a dummy Unsafe shell). | Complete implementation of `EntitySectionStorage`, `EntitySection`, `EntityLookup`, and `PersistentEntitySectionManager`. |
| **Server Tick Loop** | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java` ticks blocks and fluids. | Entity tick phase (`drainEntityTicks`), `EntityTickList` double-buffering, passenger gating, and 300-tick `emptyTime` sleep check. |
| **Collision Integration** | `core/src/vanilla/java/dev/kestrel/collision/KestrelCollide.java` implements swept block boxes and 0.6m step-up. | Wiring `Entity.move` to call `KestrelCollide.collide` and applying gravity/friction/drag equations. |
| **Networking Packets** | `core/src/vanilla/java/dev/kestrel/net/PacketIds.java` and `Outbound.java` contain zero entity packets. | 10 entity packet IDs, binary serializers in `Outbound.java`, `LpVec3` codec, and `VecDeltaCodec`. |
| **Entity Tracking** | None. | `ServerEntity` delta encoding state machine and `TrackedEntity` player range filtering. |
| **Persistence** | `core/src/vanilla/java/dev/kestrel/persist/WorldStore.java` handles block chunks. | `EntityStorage` reading and writing `entities/r.<rx>.<rz>.mca` region files with NBT passenger trees. |
