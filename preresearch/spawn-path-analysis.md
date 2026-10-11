---
title: Step 6 grounding - spawn-calculation offload
status: grounding written before any implementation, legs 2-3 open
source: decompiled vanilla 26.2 (Vineflower 1.11.1 over the nested server-26.2.jar)
date: 2026-09-11
---

# Step 6 grounding: spawn-calculation offload

Written before any implementation, per the project's standing rule. Every
claim below is read off the real 26.2 decompile; prior-art claims are read
off Mica's actual source notes in `preresearch/mica-findings.md`, not its
changelog. Hypotheses are marked as such.

**The premise check, seventh time running: the step exists, but it is
smaller and stranger than its name.** There is no single "spawn calculation"
hotspot to lift out. What `ServerChunkCache.tickChunks` does every tick is
five different jobs sharing one profiler section, and only two of them have
the shape this project can port (RNG-free algebra over snapshots). The
RNG-driven attempt loop is a draw-sequence preservation problem, not a
compute-reduction problem. And the harness itself runs every existing corpus
with natural spawning switched OFF, so the attempt loop has literally never
been measured by any baseline in this repo. The step survives, but its win
is concentrated in work that runs even when spawning is disabled.

The open question is therefore not "how to thread the spawner" but "which
of the five jobs repay a port, and can the attempt loop be skipped exactly".

## The real per-tick path

`ServerChunkCache.tickChunks` (`ServerChunkCache.java:369`):

> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

Facts that matter:

- `createState` runs BEFORE the `SPAWN_MOBS` branch. The full entity walk
  plus per-mob biome/cost/cap accounting happens every tick even on a server
  with mobs disabled. Every harness corpus in this repo measures this cost
  today without knowing it.
- `lastSpawnState` is retained on the chunk cache and read by the debug
  stats path (`ServerLevel.java:1561`, `spawning_chunks` + `spawn_count.<cat>`
  lines). A port that stops building the state must still feed that readout
  or it is an observable change.
- `tickChunk` (random-tick + ice/snow, `ServerLevel.java:499`) and
  `tickCustomSpawners` (`ServerLevel.java:488`) are separate jobs sharing the
  tick, not part of spawn orchestration. They are out of scope for this step
  (see "Explicitly not in step 6" below), but their profiler adjacency is
  why a naive "spawn section MSPT" attribution would mislead.

### The five jobs in tick order

1. `naturalSpawnCount`: `getNaturalSpawnChunkCount`
   (`DistanceManager.java:162`, drains the chunk counter) then
   `NaturalSpawner.createState` (`NaturalSpawner.java:70`): walks
   `level.getAllEntities()` once. Non-persistent mobs and non-mobs count by
   category (`mobCounts.addTo`); persistent mobs are skipped entirely
   (`isPersistenceRequired() || requiresCustomPersistence()` inverts the
   gate). Each counted entity does `chunkGetter.query` (visible-chunk lookup
   via `getFullChunk`, `ServerChunkCache.java:419`), `getRoughBiome`
   (noise-biome quart lookup, `NaturalSpawner.java:103`), `getMobSpawnCost`,
   conditional `addCharge`, plus `localMobCapCalculator.addMob` for mobs
   (per-player fan-out through `getPlayersCloseForSpawning`,
   `ChunkMap.java:1000`). **Hypothesis:** this is the largest cost center,
   scaling with total entity count on every tick regardless of gamerules.
2. Category filter: `getFilteredSpawningCategories`
   (`NaturalSpawner.java:107`): all `SPAWNING_CATEGORIES` (every `MobCategory`
   except MISC, `NaturalSpawner.java:65`) filtered on `spawnEnemies ||
   isFriendly`, `spawnPersistent || !isPersistent`, and
   `canSpawnForCategoryGlobal`. Gamerule-off short-circuits to `List.of()`.
3. Chunk collection + shuffle: `collectSpawningChunks`
   (`ChunkMap.java:947`, spawn-candidate chunks with a ticking chunk AND
   `anyPlayerCloseEnoughForSpawningInternal`) then
   `Util.shuffle(spawningChunks, level.getRandom())` (`Util.java:991`,
   Fisher-Yates, one `nextInt` per position). The shuffle draws shared
   level RNG every tick whenever the gamerule is on.
4. Per-chunk attempts: `tickSpawningChunk`
   (`ServerChunkCache.java:407`): `incrementInhabitedTime`, thunder if in
   entity-ticking range, then `NaturalSpawner.spawnForChunk`
   (`NaturalSpawner.java:121`) if categories are non-empty and
   `canSpawnEntitiesInChunk`. Per category: `canSpawnForCategoryLocal` gate,
   then `spawnCategoryForChunk` (`:136`): `getRandomPosWithin` (`:342`, three
   level-RNG draws: x, z, `randomBetweenInclusive(minY, topEmptyY)` where
   topEmptyY is a WORLD_SURFACE heightmap read; skip if `y < minY + 1`), then
   `spawnCategoryForPosition` (`:154`): up to 3 groups x up to
   `ceil(random*4)` tries, each try wandering x/z by `nextInt(6)-nextInt(6)`,
   a `getNearestPlayer` full scan, the 24-block gate (`:232`, 576.0 squared),
   the world-spawn gate (same dimension + 24 blocks of respawn pos), the
   same-chunk gate (`Objects.equals(chunkPos, chunk.getPos()) ||
   canSpawnEntitiesInChunk`), `getRandomSpawnMobAt` (biome lookup +
   weighted-list pick on level RNG, with the WATER_AMBIENT 98% veto at
   `:302`), `isValidSpawnPostitionForType` (category match, far-from-player
   despawn-distance veto, `canSummon`, `canSpawnMobAt` list-contains,
   `SpawnPlacements.isSpawnPositionOk`, `checkSpawnRules` on level RNG,
   `noCollision` AABB test), entity creation, `snapTo` with
   `random.nextFloat()*360` yaw, `isValidPositionForMob`, `finalizeSpawn`,
   `addFreshEntityWithPassengers`, `afterSpawn` callback.
5. Custom spawners + block ticks (out of scope, see below).

### Cost centers, in estimated size order

- (a) `createState` entity walk: O(all entities) per tick with per-mob
  chunk/biome/cost/cap work. Runs gamerule-independent. **Hypothesis: the
  largest center.**
- (b) `PotentialCalculator.getPotentialEnergyChange`
  (`PotentialCalculator.java`): O(charges) per candidate test in `canSpawn`
  (`SpawnState`, `NaturalSpawner.java:494`), i.e. quadratic in charged mobs.
  `addCharge` skips zero charge, so only types with non-null `MobSpawnCost`
  contribute rows. Pure algebra, no RNG, no world reads.
- (c) `LocalMobCapCalculator` (`LocalMobCapCalculator.java`): per-chunk
  `getPlayersCloseForSpawning` (player-list scan with the 128-block chunk
  proximity rule, `ChunkMap.java:1017`, memoized per tick in
  `playersNearChunk`), per-player counts vs `getMaxInstancesPerChunk`.
  Pure counting once the player sets are known.
- (d) The per-try validation chain (biome, weighted pick, placement rules,
  collision). Frequency-gated behind both cap gates; dominant only on
  spawn-heavy servers, not on typical SMPs.
- (e) `Util.shuffle` on level RNG, O(chunks) draws per tick.

Global cap rule (`NaturalSpawner.java:539`): count < maxPerChunk x
spawnableChunkCount / 289 (`MAGIC_NUMBER` = 17^2). Caps
(`MobCategory.java`): MONSTER 70, CREATURE 10, AMBIENT 15, AXOLOTLS 5,
UNDERGROUND_WATER 5, WATER_CREATURE 5, WATER_AMBIENT 20, MISC -1 (excluded
from `SPAWNING_CATEGORIES`).

Local proximity rule (`ChunkMap.java:1017`): non-spectator player whose
distance-squared to chunk center block (center +8, `euclideanDistanceSquared`,
`ChunkMap.java:1035`) is under 16384.0 (128 blocks).

## Vanilla already gates, twice

Two gates precede all attempt work, and both are cheap integer compares a
port must preserve exactly rather than "improve":

- `canSpawnForCategoryGlobal` (`NaturalSpawner.java:538`): single compare
  against the scaled cap. When the mob count is over cap the whole category
  is skipped for every chunk that tick.
- `canSpawnForCategoryLocal` (`NaturalSpawner.java:543`):
  `localMobCapCalculator.canSpawn(mobCategory, chunkPos) ||
  SharedConstants.DEBUG_IGNORE_LOCAL_MOB_CAP`. Per-player counts, any-player
  semantics (one under-cap player suffices).

The consequence for the step: on a server at cap (typical for monsters on a
populated server), the attempt loop (d) never runs and the tick cost IS (a) +
(b) + (c) + (e). Porting the gates is not the win; porting what runs before
and around them is.

## What is spawnable at all

Seven categories in `SPAWNING_CATEGORIES` (all but MISC). Per-category
admission notes read off the decompile:

| Category | Cap | Friendly | Persistent | Note |
|---|---|---|---|---|
| MONSTER | 70 | no | no | gated by `spawnEnemies` + `SPAWN_MONSTERS` (`ServerLevel.java:1761`) |
| CREATURE | 10 | yes | yes | persistent leg runs every 400 ticks (`gameTime % 400 == 0`) |
| AMBIENT | 15 | yes | no | |
| AXOLOTLS | 5 | yes | no | |
| UNDERGROUND_WATER_CREATURE | 5 | yes | no | |
| WATER_CREATURE | 5 | yes | no | |
| WATER_AMBIENT | 20 | yes | no | 98% spawn veto in reduced-spawns biomes (`:302`) |

`getFilteredSpawningCategories` order is `SPAWNING_CATEGORIES` order
(enum declaration order minus MISC), and `spawnForChunk` iterates that
order. Category order determines `afterSpawn` charge accumulation order,
so it is observable through later `energyChange <= energyBudget` decisions.

## Parity traps

1. **The level-RNG draw sequence is load-bearing.** The try loop draws from
   `level.random` interleaved with early-outs (null player, distance fail,
   empty spawn-data break). A skipped tick that would have drawn renumbers
   every later draw level-wide. Under this project's parity contract that is
   a total failure. Offload must reproduce the exact draw sequence,
   including draws on failed attempts, or prove the skipped path draws
   nothing.
2. **The shuffle consumes RNG before any attempt.** `Util.shuffle` draws one
   `nextInt` per chunk-list position (`Util.java:991`). Chunk iteration order
   after shuffle determines spawn order, charge accumulation, and cap hits.
   Any change to the candidate set (or its order before shuffle) shifts every
   draw after it.
3. **`lastCheckedPos/lastCheckedType/lastCharge` aliasing.**
   `SpawnState.afterSpawn` (`NaturalSpawner.java:509`) reuses the `canSpawn`
   charge only on pos-`equals` AND type-reference-equality; otherwise it
   re-reads the biome cost. A port that caches charges differently (e.g. by
   value-typed keys) diverges on accumulation, which feeds later energy
   decisions. Reproduce the aliasing exactly.
4. **`getRandomPosWithin` reads a heightmap.** y comes from
   `randomBetweenInclusive(minY, topEmptyY)` (`Mth.java:690`, one `nextInt`)
   where topEmptyY is the WORLD_SURFACE heightmap + 1. Heightmap reads are
   part of the cost, not just the RNG.
5. **Per-try player scans.** `getNearestPlayer(xx, y, zz, -1.0, false)` is a
   full player-list scan per try, plus the respawn-dimension/position gate.
   On playerless harness runs the scan always returns null and every try
   early-outs, which is why spawn-on measurement needs players (real or
   simulated) to exercise anything past the gates.
6. **Harness collision: spawning has never been ON.** `run-harness.ps1`
   bakes `spawn-monsters=false / spawn-animals=false` into every run because
   `Entity.random` is unseeded per entity and playerless runs never despawn.
   A step-6 corpus is the first that must re-enable natural spawning, which
   collides head-on with the determinism strategy. The mob-ai precedent
   (NoAI for bit-exact legs, live-AI soaks gated statistically) suggests the
   same split. This is the biggest open risk in the step.

## Prior art

- Mica: `OperationType.MOB_SPAWN` exists only as a deferred-effect threading
  enum (buffer `(entity, spawnReason)`, replay on main thread). Mica findings
  line 102: no spawn-ordering work beyond the `shouldSignal` fix. Nothing to
  borrow for calculation reduction.
- Lithium: no spawn-path work exists in its mixin set (its sleeping work
  covers block entities, covered by step 5). Nothing to borrow.
- Consequence: step 6 has no portable prior art. The (b)/(c) algebra ports
  are first-principles work, and the (a) walk reduction must be designed
  against the `getAllEntities` + `chunkGetter.query` structure, not adapted.

## The portability split

RNG-free algebra (port candidates, Leg 2): `canSpawnForCategoryGlobal`
arithmetic, `PotentialCalculator` charge math (O(charges) scan to indexed or
charge-grid bucketing), `LocalMobCapCalculator` counting,
`getFilteredSpawningCategories` filtering. All are pure functions over
snapshots with no RNG, no world reads, deterministic outputs.

RNG-driven sequence (transcribe, do not reorder): `Util.shuffle` position
order, `getRandomPosWithin` draws, the try-loop draw interleave
(`max = ceil(random*4)`, wander, spawn-data pick, yaw, group-size reroll
`minCount + nextInt(1 + maxCount - minCount)`), `finalizeSpawn` difficulty +
group-data threading. The model (in the `TickOrderModel` role) asserts the
draw sequence per path, including failed-attempt draws; any port of (d) must
replay it exactly.

Structural observation (not yet a design): `createState` rebuilds
`mobCounts`, `spawnPotential`, and `LocalMobCapCalculator` from scratch every
tick from `getAllEntities`. The inputs change only on entity add/remove/move
(chunk change) or cost-affecting change. An incremental structure (in the
step-17 family: cross-tick incremental membership) would turn (a) into an
event-driven update, but that is step-17 work with a step-6 measurement
motive, not a step-6 implementation claim. Stated here so the measurement
leg records the right inputs.

## Explicitly not in step 6

- Spawner block entities (`BaseSpawner.serverTick`): step 5 ruled them out,
  the countdown IS the work.
- Chunkgen-time `spawnMobsForChunkGeneration` (`NaturalSpawner.java:365`):
  worldgen path with its own `CHUNK_GENERATION` spawn reason, not the tick
  path.
- `tickChunk` block random-tick + ice/snow (`ServerLevel.java:499`) and
  `tickCustomSpawners` (`ServerLevel.java:488`, phantom patrol, wandering
  trader): separate jobs sharing the profiler section, separate groundings
  if ever pursued.
- Alternate Current (step 7) and any threading (step 8 owns parallelism;
  step 6 is calculation reduction within the existing single-threaded order).

## Open items before implementing

1. Write this grounding doc (done, this file).
2. Measurement leg: per-section MSPT attribution of `tickChunks` on a
   spawn-on corpus. No baseline in this repo has spawning enabled; gamerule-off
   runs measure (a) only. Needs a spawn-on corpus (Leg 3 shape) plus players
   (trap 5: playerless runs early-out every try). Open: how to put players on
   a headless harness run (bot protocol client exists as `/tmp` scratch, see
   toolchain gotchas; needs promoting), or gate attempts statistically.
3. Headless port + batteries (`core/src/vanilla/java/dev/kestrel/spawn/`,
   blockentity pattern: model + pure-function layer + `*Check` with negative
   controls, routed through `run-core-check.ps1`). Portable set: (b), (c),
   global-cap arithmetic, category filter. Try loop transcribed with
   draw-sequence assertions.
4. Harness corpus + live proof (`harness/corpus/spawn-census/`,
   `block-entity-sleep` proving shape: entry-point `load.json`, forceload
   baked in, schedule-driven stages, busy + must-never-fire controls,
   `Failed to load function` grep). New agent probe (per-tick per-category
   counts/attempts, TickLogger schema bump) only if the hash alone cannot
   localize. Determinism strategy is its own item: expect the mob-ai split
   (bit-exact NoAI legs + statistical live-spawn soak).
5. Settle the Leg 2/3 order question against item 2's numbers: if (a) dominates
   and is incremental-structure-shaped, Leg 2's algebra ports still land
   first (they are independent of that decision) and the (a) redesign is
   scoped, not started.
