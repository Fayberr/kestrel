# S16 Pre-Research: Chunk Loading, Ticket Levels, and Unloading Architecture

Status: PRE-RESEARCH, grounded in the decompiled vanilla 26.2 server jar (`server-26.2.jar`) in `~/kestrel-run/vanilla-src-26.2`, runtime bytecode verification, and existing Kestrel implementations in `core/src/vanilla/java/dev/kestrel/`.
Target: Ground truth specification and architecture roadmap for roadmap item S16 (chunk loading, ticket levels, simulation distance decoupling, and chunk unloading) in Kestrel.
Scope: Architectural analysis, vanilla code audits with exact file:line citations, mathematical proofs of ticket propagation, networking packet definitions and wire formats, NBT storage specifications, resolution of the live server memory retention leak (H5 in `docs/ARCHITECTURE-AUDIT.md`), and vertical implementation slices. No Java source changes, no builds, no servers. Plain markdown only: no em-dash or en-dash characters.

---

## 1. Executive Summary and Scope Boundary

### 1.1 Context and Scope Boundary
Roadmap item S16 establishes the lifecycle manager for world chunks in Kestrel:
- Discrete ticket levels (Entity Ticking, Block Ticking, Full/Border, and Inaccessible) governing chunk activation and deactivation.
- Decoupling of View Distance (streaming/rendering range) from Simulation Distance (active ticking range).
- Dynamic ticket propagation based on Chebyshev graph distance (`DistanceManager`, `ChunkTracker`, `SimulationChunkTracker`, `LoadingChunkTracker`).
- Clean chunk unloading, block entity teardown, scheduled tick container deregistration, and asynchronous NBT persistence to `region/r.<rx>.<rz>.mca`.
- Client-side chunk cache synchronization via batching flow control (`ChunkBatchStart`, `ChunkBatchFinished`, `ChunkBatchReceived`) and explicit eviction (`ClientboundForgetLevelChunkPacket`).
- Persistence of forced chunks and server tickets in `data/chunk_tickets.dat` (`TicketStorage`).

### 1.2 Relationship to S14 Entity System
S14 (`preresearch/s14-entity-system-preresearch.md`) defined the spatial and level-wide indexing of entities (`PersistentEntitySectionManager`, `EntitySectionStorage`, `EntityLookup`). The boundary between S16 and S14 is strictly unified via `ServerLevel.java:269`:
```java
// ServerLevel.java:269
this.chunkSource = new ServerChunkCache(
   ...,
   this.entityManager::updateChunkStatus,
   ...
);
```
When S16 promotes or demotes a chunk's `FullChunkStatus`, it invokes `entityManager.updateChunkStatus(ChunkPos, FullChunkStatus)`. S14 translates this into entity `Visibility`:
- `FullChunkStatus.INACCESSIBLE`: Entities transition to `Visibility.HIDDEN` (written to `entities/` region files and removed from memory).
- `FullChunkStatus.FULL`: Entities transition to `Visibility.TRACKED` (read from `entities/` region files into memory, tracking network clients, but inactive).
- `FullChunkStatus.BLOCK_TICKING`: Entities remain `Visibility.TRACKED`.
- `FullChunkStatus.ENTITY_TICKING`: Entities transition to `Visibility.TICKING` (actively simulated in `ServerLevel.tick`).

### 1.3 Resolution of Kestrel Architecture Audit Blocker H5
In `docs/ARCHITECTURE-AUDIT.md`, Finding H5 ("Retention follows server history rather than active ownership") identified that Kestrel currently leaks memory monotonically during player exploration:
- `LiveGenerator.grid` never evicts generated `ChunkAccess`/`ProtoChunk` objects.
- `SectionStore` retains all loaded or generated chunks in memory indefinitely.
- `ChunkSender` never emits `ClientboundForgetLevelChunkPacket`, causing client memory bloat.
- Chunks have no ticket-based lease; the server retains all past exploration until Out-Of-Memory (OOM).

S16 provides the authoritative lifecycle state machine that bounds resident chunk memory to active ticket holders (players, spawn chunks, and forced tickets), safely evicting idle chunks to disk.

---

## 2. Architecture Part 1: Vanilla Ticket System and Chunk Level Algebra

### 2.1 Chunk Level Constants and Status Mappings
Chunk status and simulation eligibility are governed by integer levels defined in `net/minecraft/server/level/ChunkLevel.java:11-66`:

```java
// ChunkLevel.java:11-16
private static final int FULL_CHUNK_LEVEL = 33;
private static final int BLOCK_TICKING_LEVEL = 32;
private static final int ENTITY_TICKING_LEVEL = 31;
private static final ChunkStep FULL_CHUNK_STEP = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL);
public static final int RADIUS_AROUND_FULL_CHUNK = FULL_CHUNK_STEP.accumulatedDependencies().getRadius();
public static final int MAX_LEVEL = 33 + RADIUS_AROUND_FULL_CHUNK;
```

#### Ground Truth Constants Verified in Minecraft 26.2:
- `RADIUS_AROUND_FULL_CHUNK = 11` (accumulated dependency radius of world generation steps to reach `ChunkStatus.FULL`).
- `MAX_LEVEL = 44` ($33 + 11$). A chunk with level $> 44$ is considered unloaded (`ChunkLevel.isLoaded(level) == false`).
- `FullChunkStatus.ENTITY_TICKING`: level $\le 31$ (`ChunkLevel.java:42-43`).
- `FullChunkStatus.BLOCK_TICKING`: level $32$ (`ChunkLevel.java:44-45`).
- `FullChunkStatus.FULL`: level $33$ (`ChunkLevel.java:46-47`).
- `FullChunkStatus.INACCESSIBLE`: level $34$ to $44$ (`ChunkLevel.java:47`).

```
Chunk Level Spectrum:
[0 ................. 31] [  32  ] [  33  ] [ 34 ........... 44 ] [ 45+ ]
     ENTITY_TICKING       BLOCK     FULL        INACCESSIBLE      UNLOADED
  (Entities + Blocks +   TICKING   (Border,   (Proto generation   (Evicted
      Random Ticks)      (Blocks)   Loaded)    dependencies)      to Disk)
```

#### Chunk Generation Status Mapping (`ChunkLevel.java:18-35`):
In 26.2, generation step dependencies map directly to chunk levels from 33 to 44:
- Level 33: `ChunkStatus.full`, `ChunkStatus.spawn`, `ChunkStatus.light`
- Level 34: `ChunkStatus.initialize_light`, `ChunkStatus.features`
- Level 35: `ChunkStatus.carvers`, `ChunkStatus.surface`, `ChunkStatus.noise`
- Level 36: `ChunkStatus.biomes`, `ChunkStatus.structure_references`
- Level 44: `ChunkStatus.structure_starts`, `ChunkStatus.empty`

### 2.2 Ticket Data Model in 26.2
In Minecraft 26.2, `Ticket` and `TicketType` have been modernized into immutable record architectures.

#### TicketType Record (`net/minecraft/server/level/TicketType.java:11-57`):
```java
public record TicketType(long timeout, @TicketType.Flags int flags)
```
Ticket behavioral flags (`TicketType.java:13-17`):
- `FLAG_PERSIST = 1`: Saved to disk in `data/chunk_tickets.dat`.
- `FLAG_LOADING = 2`: Causes chunk loading via `LoadingChunkTracker`.
- `FLAG_SIMULATION = 4`: Causes chunk simulation via `SimulationChunkTracker`.
- `FLAG_KEEP_DIMENSION_ACTIVE = 8`: Prevents level time/weather/ticking suspension when empty.
- `FLAG_CAN_EXPIRE_IF_UNLOADED = 16`: Allows ticket countdown even if chunk is not ready for saving.

#### Registered Vanilla Ticket Types (`TicketType.java:18-26`):
| Ticket Name | Timeout (Ticks) | Flags (Bitmask) | Semantic Function |
|---|---|---|---|
| `player_spawn` | 20 | `FLAG_LOADING` (2) | Initial chunk loading around player spawn point. |
| `spawn_search` | 1 | `FLAG_LOADING` (2) | Short-lived single-tick probe for finding safe spawn locations. |
| `dragon` | 0 (Infinite) | `LOADING \| SIMULATION` (6) | Keeps End fight arena loaded and actively simulating. |
| `player_loading` | 0 (Infinite) | `FLAG_LOADING` (2) | View distance streaming ticket managed by `PlayerTicketTracker`. |
| `player_simulation` | 0 (Infinite) | `SIMULATION \| KEEP_DIMENSION_ACTIVE` (12) | Simulation distance ticking ticket around online players. |
| `forced` | 0 (Infinite) | `PERSIST \| LOADING \| SIMULATION \| KEEP_DIMENSION_ACTIVE` (15) | Chunks marked by `/forceload`. |
| `portal` | 300 (15 sec) | `PERSIST \| LOADING \| SIMULATION \| KEEP_DIMENSION_ACTIVE` (15) | Entities passing through nether/end portals keep chunk active. |
| `ender_pearl` | 40 (2 sec) | `LOADING \| SIMULATION \| KEEP_DIMENSION_ACTIVE` (14) | Thrown ender pearl flight chunk retention. |
| `unknown` | 1 | `LOADING \| CAN_EXPIRE_IF_UNLOADED` (18) | Fallback temporary load lease. |

#### Ticket Record (`net/minecraft/server/level/Ticket.java:13-64`):
```java
// Ticket.java:22-24
private final TicketType type;
private final int ticketLevel;
private long ticksLeft;
```
- Tickets are value objects serialized via `Ticket.CODEC` (`Ticket.java:14-21`).
- Equality and deduplication: Two tickets are identical if `t.getType() == ticket.getType() && t.getTicketLevel() == ticket.getTicketLevel()` (`TicketStorage.java:194-196`). Re-adding an existing ticket simply resets its timer: `t.resetTicksLeft()` (`TicketStorage.java:165`).

---

## 3. Architecture Part 2: DistanceManager and Ticket Propagation Engine

### 3.1 DynamicGraphMinFixedPoint Propagation Algorithm
Ticket propagation is implemented in `net/minecraft/server/level/ChunkTracker.java:6-75`, which extends `net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint`.

#### Mathematical Formulation:
- Graph nodes are packed chunk positions (`ChunkPos.pack(x, z)`).
- Neighborhood topology: 8-neighbor Moore neighborhood (Chebyshev metric $L_\infty$):
  $$\mathcal{N}(x, z) = \{(x + dx, z + dz) \mid dx, dz \in \{-1, 0, 1\}, (dx, dz) \neq (0, 0)\}$$
  (`ChunkTracker.java:23-30`, `41-47`).
- Edge transition weight:
  $$\text{cost}(neighbor \to node) = \text{level}(neighbor) + 1$$
  (`ChunkTracker.java:66`).
- Source nodes: Chunks receiving direct tickets from `TicketStorage` evaluate to `getLevelFromSource(node)`.
- Relaxation rule:
  $$\text{level}(node) = \min\left(\text{level}_{\text{source}}(node), \min_{nb \in \mathcal{N}(node)} (\text{level}(nb) + 1)\right)$$

Because edge weight is $+1$ in all 8 directions, a source chunk with level $L$ establishes concentric square rings of radius $r$ with level $L + r$.

### 3.2 Dual-Tracker Architecture
`DistanceManager` (`net/minecraft/server/level/DistanceManager.java:36-331`) maintains two independent graph trackers to decouple streaming from simulation:

```
                          +------------------------+
                          |     TicketStorage      |
                          +-----------+------------+
                                      |
                 +--------------------+--------------------+
                 | (doesLoad == true)                      | (doesSimulate == true)
                 v                                         v
    +-------------------------+               +-------------------------+
    |   LoadingChunkTracker   |               | SimulationChunkTracker  |
    |  (DistanceManager:40)   |               |  (DistanceManager:41)   |
    |  Range: 0 .. 45 (MAX+1) |               |  Range: 0 .. 34 (MAX+1) |
    +------------+------------+               +------------+------------+
                 |                                         |
                 v                                         v
      ChunkHolder TicketLevel                     Chunk Simulation Level
     Governs Worldgen Status                      Governs Entity & Block
      and Chunk Retention                            Tick Eligibility
```

1. **`LoadingChunkTracker` (`LoadingChunkTracker.java:5-50`):**
   - Size limit: `MAX_LEVEL + 1 = 45` levels.
   - Listens to tickets with `ticket.getType().doesLoad() == true`.
   - Propagates chunk generation and memory retention levels up to 44.
   - Triggers `ChunkHolder.setTicketLevel(level)` and schedules generation tasks.

2. **`SimulationChunkTracker` (`SimulationChunkTracker.java:8-47`):**
   - Size limit: `MAX_LEVEL = 33 + 1 = 34` levels. Chunks $\ge 33$ are pruned (`SimulationChunkTracker.java:36`).
   - Listens to tickets with `ticket.getType().doesSimulate() == true`.
   - Propagates simulation status. Chunks with simulation level $\le 31$ tick entities; chunks with level $\le 32$ tick blocks.

### 3.3 Player Simulation Distance vs. View Distance Math

#### Player Simulation Ticket (`DistanceManager.java:113-137`):
When a player enters a chunk, `DistanceManager.addPlayer` submits a `TicketType.PLAYER_SIMULATION` ticket:
```java
// DistanceManager.java:135-137
private int getPlayerTicketLevel() {
   return Math.max(0, ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING) - this.simulationDistance);
}
```
Since `ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING) == 31`:
$$\text{TicketLevel}_{\text{player}} = 31 - S$$
where $S$ is the configured `simulation-distance` (e.g., $S = 10$).

Let $d = \max(|x - x_{\text{player}}|, |z - z_{\text{player}}|)$ be the Chebyshev distance from the player's chunk.
By graph propagation, the level at distance $d$ is:
$$\text{Level}(d) = (31 - S) + d$$

This produces exact vanilla simulation zones:
1. **Entity Ticking Zone:**
   $$\text{Level}(d) \le 31 \iff 31 - S + d \le 31 \iff d \le S$$
   All chunks within radius $S$ (the simulation distance) actively tick entities, mobs, projectiles, and players.
2. **Block Ticking Zone:**
   $$\text{Level}(d) \le 32 \iff 31 - S + d \le 32 \iff d \le S + 1$$
   Chunks at radius $S + 1$ execute scheduled block ticks (fluids, redstone, repeaters, copper oxidation, crops) but do NOT tick entities.
3. **Full / Border Zone:**
   $$\text{Level}(d) \le 33 \iff 31 - S + d \le 33 \iff d \le S + 2$$
   Chunks at radius $S + 2$ are fully loaded in memory to provide collision and neighbor updates for blocks at $S + 1$, but execute no ticks.
4. **Inaccessible Zone:**
   $$\text{Level}(d) \ge 34 \iff d \ge S + 3$$
   Chunks outside $S + 2$ are inaccessible for simulation.

#### Player Loading / View Distance Tracker (`DistanceManager.java:250-330`):
View distance streaming uses `PlayerTicketTracker`, an inner class extending `FixedPlayerDistanceChunkTracker`:
- Source level is $0$ at chunks containing players (`DistanceManager.java:237`).
- Chunks at distance $d \le \text{viewDistance}$ receive `Ticket(TicketType.PLAYER_LOADING, 31)`.
- Tickets are submitted through `ThrottlingChunkTaskDispatcher` to throttle chunk generation requests (`DistanceManager.java:55-56, 286-294`).
- Chunks entering view range are loaded; chunks leaving view range have their tickets removed via `ticketStorage.removeTicket(key, ticket)` (`DistanceManager.java:296`).

---

## 4. Architecture Part 3: Chunk Lifecycle Transitions and Promotion Pipeline

### 4.1 ChunkHolder Promotion and Demotion State Machine
Chunk state transitions occur in `net/minecraft/server/level/ChunkHolder.java:275-328` (`updateFutures`):

```java
// ChunkHolder.java:275-279
FullChunkStatus oldFullStatus = ChunkLevel.fullStatus(this.oldTicketLevel);
FullChunkStatus newFullStatus = ChunkLevel.fullStatus(this.ticketLevel);
boolean wasAccessible = oldFullStatus.isOrAfter(FullChunkStatus.FULL);
boolean isAccessible = newFullStatus.isOrAfter(FullChunkStatus.FULL);
```

#### Promotion Pipeline:
1. **Transition to `FULL` (Border / Accessible):**
   - Condition: `!wasAccessible && isAccessible` (lines 281-285).
   - Action: Invokes `scheduler.prepareAccessibleChunk(this)`.
   - Calls `scheduleFullChunkPromotion(..., FullChunkStatus.FULL)`.
   - Adds `fullChunkFuture` as save dependency (`addSaveDependency`).
   - Callback notifies listener: `entityManager.updateChunkStatus(pos, FullChunkStatus.FULL)`. S14 loads entity storage from `entities/r.<rx>.<rz>.mca` into `Visibility.TRACKED`.
2. **Transition to `BLOCK_TICKING`:**
   - Condition: `!wasTicking && isTicking` (lines 294-298).
   - Action: Invokes `scheduler.prepareTickingChunk(this)`.
   - Adds chunk to `chunkMap.forEachBlockTickingChunk` iteration. Scheduled ticks for blocks and fluids begin running in `ServerLevel.tickChunk`.
3. **Transition to `ENTITY_TICKING`:**
   - Condition: `!wasEntityTicking && isEntityTicking` (lines 307-315).
   - Action: Invokes `scheduler.prepareEntityTickingChunk(this)`.
   - Callback notifies listener: `entityManager.updateChunkStatus(pos, FullChunkStatus.ENTITY_TICKING)`. S14 promotes entity storage to `Visibility.TICKING`. Mob AI, physics, and entity collisions activate.

#### Demotion Pipeline (lines 287-290, 300-303, 317-324):
- Demotions run **synchronously and eagerly**:
  ```java
  // ChunkHolder.java:322-324
  if (!newFullStatus.isOrAfter(oldFullStatus)) {
     this.demoteFullChunk(scheduler, newFullStatus);
  }
  ```
- `demoteFullChunk` immediately invokes `scheduler.onFullChunkStatusChange(this.pos, status)`.
- If demoted below `ENTITY_TICKING`, `entityManager.updateChunkStatus` transitions entities to `Visibility.TRACKED` (entities immediately stop ticking).
- If demoted below `FULL` (to `INACCESSIBLE`), `entityManager.updateChunkStatus` transitions entities to `Visibility.HIDDEN` (entities are flushed to disk in `entities/` region files and evicted from memory).
- Futures for higher statuses are completed with `UNLOADED_LEVEL_CHUNK`:
  ```java
  // ChunkHolder.java:318-319
  this.entityTickingChunkFuture.complete(UNLOADED_LEVEL_CHUNK);
  this.entityTickingChunkFuture = UNLOADED_LEVEL_CHUNK_FUTURE;
  ```

---

## 5. Architecture Part 4: Server Chunk Unloading and Memory Eviction

### 5.1 The Eviction Lifecycle in ChunkMap
When all tickets for a chunk expire or are removed, its level drops to $> 44$ (`MAX_LEVEL`). The chunk is evicted from server memory following the exact lifecycle in `net/minecraft/server/level/ChunkMap.java:380-386, 481-550`:

```
Chunk Level > 44
       |
       v
Add to ChunkMap.toDrop set (ChunkMap.java:382)
       |
       v
ChunkMap.processUnloads (ChunkMap.java:481-491)
       |--> Remove from updatingChunkMap
       |--> Add to pendingUnloads
       |--> scheduleUnload(pos, chunkHolder)
       |
       v
Wait for chunkHolder.getSaveSyncFuture() (ChunkMap.java:523-528)
       |
       v
Execute on unloadQueue (ChunkMap.java:529-545)
       |--> levelChunk.setLoaded(false)
       |--> this.save(chunk) -> Write NBT to region/r.<rx>.<rz>.mca
       |--> level.unload(levelChunk)
       |       |--> clearAllBlockEntities()
       |       \--> unregisterTickContainerFromLevel()
       |--> lightEngine.updateChunkStatus(pos)
       |--> pendingUnloads.remove(pos)
       \--> GC can now reclaim LevelChunk, ProtoChunk, and Block Entities
```

#### Detailed Eviction Steps (`ChunkMap.java:522-545`):
1. **Drop Detection (`ChunkMap.java:380-386`):**
   `updateChunkScheduling` checks `!ChunkLevel.isLoaded(level)`. If true, the packed position is placed in `toDrop`.
2. **Transfer to Pending (`ChunkMap.java:481-491`):**
   At the start of `processUnloads`, entries in `toDrop` are removed from `updatingChunkMap` and added to `pendingUnloads`. `this.modified = true` signals that `promoteChunkMap()` must update `visibleChunkMap`.
3. **Save Dependency Synchronization (`ChunkMap.java:523-528`):**
   `scheduleUnload` inspects `chunkHolder.getSaveSyncFuture()`. If an existing asynchronous write is in-flight, unload waits for it to complete. If the future changes, it reschedules.
4. **Disk Serialization (`ChunkMap.java:535`, `SerializableChunkData.java:417-486`):**
   `this.save(chunk)` checks if the chunk is unsaved (`chunk.isUnsaved()`). If modified, it builds a `SerializableChunkData` snapshot and writes the compressed NBT compound into the world's `region/r.<rx>.<rz>.mca` region file.
5. **Level Cleanup (`ServerLevel.java:1013-1017`):**
   ```java
   public void unload(final LevelChunk levelChunk) {
      levelChunk.clearAllBlockEntities();
      levelChunk.unregisterTickContainerFromLevel(this);
      this.debugSynchronizers.dropChunk(levelChunk.getPos());
   }
   ```
   - `clearAllBlockEntities()`: Block entities have `setRemoved()` called and are detached from the level.
   - `unregisterTickContainerFromLevel()`: Active scheduled tick queues for blocks and fluids unbind from the level's master tick schedulers, preventing orphaned tick callbacks.
6. **Lighting Cleanup (`ChunkMap.java:540-541`):**
   `lightEngine.updateChunkStatus(chunk.getPos())` releases cached light data layers for the evicted chunk.

### 5.2 Concrete Fix for Kestrel H5 Leak
To fix the memory leak in Kestrel (`docs/ARCHITECTURE-AUDIT.md` Finding H5):
1. **Evict `LiveGenerator.grid`:**
   In Kestrel, `LiveGenerator.grid` (`LiveGenerator.java:44`) currently stores every `ChunkAccess` permanently. When a chunk unloads, Kestrel must remove the entry from `LiveGenerator.grid`. If the chunk was modified by player digging or placing, its modifications live in `SectionStore`.
2. **Evict `SectionStore` Sections:**
   When a chunk has no tickets, Kestrel's `SectionStore` must serialize modified block state palettes into `region/r.<rx>.<rz>.mca` via `WorldStore` and remove the chunk's 24 sections from memory.
3. **Bound Reconnection State in `ChunkSender`:**
   When a player disconnects, `ChunkSender.forget(UUID)` must remove all entries from `sent`, `centers`, and `sentWrites`, preventing zombie profile leaks (`NetServer.java:1256-1260`).

---

## 6. Architecture Part 5: Chunk Networking and Client Synchronization Protocol

### 6.1 Chunk Tracking View and Differential Streaming
In 26.2, client chunk streaming is governed by `net/minecraft/server/level/ChunkTrackingView.java:7-121` and `net/minecraft/server/level/ChunkMap.java:1120-1135`.

#### Positioned View Record (`ChunkTrackingView.java:82-120`):
A player's view envelope is defined as `ChunkTrackingView.Positioned(ChunkPos center, int viewDistance)`:
> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

#### Differential View Computation (`ChunkTrackingView.java:23-49`):
When a player moves from `previous` to `next`, `ChunkTrackingView.difference` computes the exact symmetric difference:
```java
// ChunkMap.java:1132
ChunkTrackingView.difference(
   previous,
   next,
   pos -> this.markChunkPendingToSend(player, pos),
   pos -> dropChunk(player, pos)
);
```
- `onEnter` (`markChunkPendingToSend`): Chunks that entered view distance are enqueued into `PlayerChunkSender.pendingChunks`.
- `onLeave` (`dropChunk`): Chunks that exited view distance are immediately dropped from client memory.

### 6.2 The 26.2 Wire Packets for Chunks

#### 1. `ClientboundForgetLevelChunkPacket` (`0x25` / 37)
- Class: `net/minecraft/network/protocol/game/ClientboundForgetLevelChunkPacket.java:9-30`.
- Protocol Direction: Clientbound (Play state).
- Wire ID: `0x25` (37).
- Payload:
  | Field | Type | Description |
  |---|---|---|
  | `pos` | `long` (8 bytes) | Packed chunk position: `ChunkPos.pack()`. Note: 26.2 writes a 64-bit `long`, NOT two `VarInt` coordinates. |

When received by the vanilla client, the client removes the chunk from its `ClientChunkCache`, deletes associated render meshes, and releases entity references.

#### 2. `ClientboundSetChunkCacheCenterPacket` (`0x5E` / 94)
- Class: `net/minecraft/network/protocol/game/ClientboundSetChunkCacheCenterPacket.java:8-46`.
- Protocol Direction: Clientbound (Play state).
- Wire ID: `0x5E` (94).
- Emitted: Whenever `to.center()` differs from `from.center()` (`ChunkMap.java:1128-1130`).
- Payload:
  | Field | Type | Description |
  |---|---|---|
  | `chunkX` | `VarInt` | Chunk X center coordinate. |
  | `chunkZ` | `VarInt` | Chunk Z center coordinate. |

#### 3. `ClientboundSetChunkCacheRadiusPacket` (`0x5F` / 95)
- Class: `net/minecraft/network/protocol/game/ClientboundSetChunkCacheRadiusPacket.java:8-39`.
- Protocol Direction: Clientbound (Play state).
- Wire ID: `0x5F` (95).
- Payload:
  | Field | Type | Description |
  |---|---|---|
  | `radius` | `VarInt` | Negotiated view distance radius. |

#### 4. `ClientboundChunkBatchStartPacket` (`0x0C` / 12)
- Class: `net/minecraft/network/protocol/game/ClientboundChunkBatchStartPacket.java:8-23`.
- Protocol Direction: Clientbound (Play state).
- Wire ID: `0x0C` (12).
- Payload: Empty (Unit codec). Signals to the client that a contiguous batch of chunk packets follows.

#### 5. `ClientboundLevelChunkWithLightPacket` (`0x2D` / 45)
- Class: `net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket.java:1-50`.
- Protocol Direction: Clientbound (Play state).
- Wire ID: `0x2D` (45).
- Payload:
  - `x`: `int` (4 bytes, Big-Endian)
  - `z`: `int` (4 bytes, Big-Endian)
  - `chunkData`: `ClientboundLevelChunkPacketData` (Heightmaps compound, section byte array, block entities)
  - `lightData`: `ClientboundLightUpdatePacketData` (Sky light mask, block light mask, empty sky mask, empty block mask, light byte arrays)

#### 6. `ClientboundChunkBatchFinishedPacket` (`0x0B` / 11)
- Class: `net/minecraft/network/protocol/game/ClientboundChunkBatchFinishedPacket.java:8-30`.
- Protocol Direction: Clientbound (Play state).
- Wire ID: `0x0B` (11).
- Payload:
  | Field | Type | Description |
  |---|---|---|
  | `batchSize` | `VarInt` | Exact count of `LevelChunkWithLightPacket` instances sent in this batch. |

#### 7. `ServerboundChunkBatchReceivedPacket` (`0x0B` / 11)
- Class: `net/minecraft/network/protocol/game/ServerboundChunkBatchReceivedPacket.java:8-30`.
- Protocol Direction: Serverbound (Play state).
- Wire ID: `0x0B` (11).
- Payload:
  | Field | Type | Description |
  |---|---|---|
  | `desiredChunksPerTick` | `float` (4 bytes) | Client's requested streaming bandwidth based on frame time and mesh compilation speed. |

### 6.3 Flow Control and Dynamic Rate Adaptation
In `net/minecraft/server/network/PlayerChunkSender.java:23-132`, chunk transmission is bounded by client feedback:
- Initial bandwidth: `desiredChunksPerTick = 9.0F` (`PlayerChunkSender.java:27, 31`).
- Bandwidth clamps: $\text{desiredChunksPerTick} \in [0.01\text{F}, 64.0\text{F}]$ (`PlayerChunkSender.java:25-26, 120`).
- Unacknowledged batch limit: Maximum 10 in-flight batches (`MAX_UNACKNOWLEDGED_BATCHES = 10`, line 28). If client lags or drops frames, `sendNextChunks` pauses transmission (`line 51`).
- Batch execution:
  > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

---

## 7. Architecture Part 6: Persistence Architecture

### 7.1 Chunk Region File Layout (`region/r.<rx>.<rz>.mca`)
In Minecraft 26.2, chunk terrain, biomes, block entities, and scheduled ticks are serialized via `SerializableChunkData.java:417-486`.

#### Chunk Root CompoundTag Layout:
```
Root (CompoundTag)
|-- DataVersion: Int (Current 26.2 data version)
|-- xPos: Int (Chunk X coordinate)
|-- yPos: Int (-4, min section coordinate)
|-- zPos: Int (Chunk Z coordinate)
|-- LastUpdate: Long (World game time at serialization)
|-- InhabitedTime: Long (Cumulative player-inhabited ticks)
|-- Status: String ("minecraft:full")
|-- isLightOn: Boolean (true if lighting initialized)
|-- sections: ListTag of CompoundTag (Section Y = -4 to 19)
|   |-- Y: Byte (Section Y index, e.g. -4)
|   |-- block_states: CompoundTag (PalettedContainer with palette & data longs)
|   |-- biomes: CompoundTag (PalettedContainer with biome palette & data longs)
|   |-- BlockLight: ByteArray (2048 bytes, optional if empty)
|   \-- SkyLight: ByteArray (2048 bytes, optional if empty)
|-- block_entities: ListTag of CompoundTag
|   \-- [CompoundTag per block entity: id, x, y, z, keepPacked, custom NBT]
|-- block_ticks: ListTag of CompoundTag (BLOCK_TICKS_CODEC)
|   \-- [CompoundTag: i (Block Id), x, y, z, t (Ticks left), p (Priority)]
|-- fluid_ticks: ListTag of CompoundTag (FLUID_TICKS_CODEC)
|   \-- [CompoundTag: i (Fluid Id), x, y, z, t (Ticks left), p (Priority)]
|-- Heightmaps: CompoundTag
|   |-- MOTION_BLOCKING: LongArray (37 longs for 256 9-bit height values)
|   |-- MOTION_BLOCKING_NO_LEAVES: LongArray
|   |-- OCEAN_FLOOR: LongArray
|   \-- WORLD_SURFACE: LongArray
|-- PostProcessing: ListTag of ListTag (Short offsets for tile updates)
\-- structures: CompoundTag (Structure starts and references)
```

Important Parity Rule: Full `LevelChunk` compounds do NOT contain living entity data. Entities are serialized to separate region files in `entities/r.<rx>.<rz>.mca` (as documented in S14 Section 5).

### 7.2 Forced Chunks and Ticket Storage (`data/chunk_tickets.dat`)
Persistent tickets (tickets with `FLAG_PERSIST == 1`, including `TicketType.FORCED` and `TicketType.PORTAL`) are saved in the level's `data/chunk_tickets.dat` file via `net/minecraft/world/level/TicketStorage.java:33-433`.

#### Codec Specification (`TicketStorage.java:36-42`):
> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

#### Lifecycle Hooks:
- On Server Boot (`MinecraftServer.java:561-567`):
  `savedTickets.activateAllDeactivatedTickets()` reads `chunk_tickets.dat` and re-inserts all saved tickets into `TicketStorage.tickets`, triggering `LoadingChunkTracker` to load spawn chunks and forced chunks before accepting players.
- On Server Shutdown (`TicketStorage.java:316-318`):
  `deactivateTicketsOnClosing()` removes non-persistent tickets and prepares the store for serialization.

---

## 8. Suggested Build Order (Vertical Slices for Kestrel)

To implement S16 in Kestrel cleanly without destabilizing existing worldgen or netplay, the implementation should be executed in 5 vertical slices:

### Slice 1: Ticket Level Model and Forget Chunk Wire Packet
- **Scope:**
  - Define `ChunkLevel` constants in Kestrel: `ENTITY_TICKING_LEVEL = 31`, `BLOCK_TICKING_LEVEL = 32`, `FULL_CHUNK_LEVEL = 33`, `MAX_LEVEL = 44`.
  - Implement `Outbound.forgetLevelChunk(long chunkPosKey)` emitting packet ID `0x25` with 8-byte packed coordinate.
- **Testable Check:**
  - Unit test verifying `Outbound.forgetLevelChunk` encodes `0x25` followed by `pos.pack()` matching vanilla byte stream.

### Slice 2: Player Chunk Tracking View and Differential Client Unloading
- **Scope:**
  - Implement `ChunkTrackingView.Positioned(center, viewDistance)` and `difference(from, to, onEnter, onLeave)`.
  - Wire into `ChunkSender.streamAround`: when a player crosses a chunk boundary, compute leaving chunks and emit `forgetLevelChunk` immediately.
  - Fix zombie profile retention on player disconnect in `ChunkSender.forget(UUID)`.
- **Testable Check:**
  - Loopback test: Player walks across chunk boundary $X=15 \to X=16$. Verify that the trailing strip of chunks emits `0x25` packets to the client. Verify F3 chunk count on vanilla client decreases when walking away.

### Slice 3: Simulation Distance Decoupling and Ticking Bounds
- **Scope:**
  - Introduce `simulationDistance` alongside `viewDistance` in `KestrelServer` and `ChunkSender`.
  - In `TickLoop`: Gate scheduled block/fluid ticks to chunks within distance $\le \text{simulationDistance} + 1$ (Level $\le 32$).
  - In S14 Entity System: Gate entity simulation to chunks within distance $\le \text{simulationDistance}$ (Level $\le 31$).
- **Testable Check:**
  - Place water/repeater and spawn a mob at distance $\text{simDistance} + 1$ vs $\text{simDistance} + 3$. Verify repeater ticks at $S+1$ but freezes at $S+3$. Verify mob AI ticks at $S$ but freezes at $S+1$.

### Slice 4: Server Chunk Eviction and Memory Bounds (Closing H5)
- **Scope:**
  - Implement active chunk ticket tracking (`TicketStorage` / `ChunkTracker`).
  - When a chunk has no tickets for $> 200$ ticks:
    1. If modified in `SectionStore`, serialize modified sections to `region/r.<rx>.<rz>.mca`.
    2. Evict chunk from `LiveGenerator.grid`.
    3. Clear sections from `SectionStore`.
- **Testable Check:**
  - Soak test: Run a creative flight over 1,000 chunks. Measure resident JVM heap: memory must plateau once view envelope stabilizes, confirming idle chunks are successfully evicted.

### Slice 5: Ticket Persistence and Forced Chunks
- **Scope:**
  - Implement `data/chunk_tickets.dat` serialization (`TicketStorage.TYPE`).
  - Wire `/forceload add <x> <z>` to add `TicketType.FORCED`.
- **Testable Check:**
  - Add forced chunk, reboot Kestrel server, verify chunk is loaded before any player joins.
