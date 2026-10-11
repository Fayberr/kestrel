# Kestrel Mechanics Capability Matrix

## Status Summary
- Total mechanics evaluated: 96
- Exact (runs live in server and parity-gated against vanilla): 32 (33.3%)
- Partial (some live behavior, with material gaps): 9 (9.4%)
- Component proof (not live, parity-gated isolated proof): 14 (14.6%)
- Approximated (runs live or partial in server, unverified by parity gate): 11 (11.5%)
- Missing (no runtime implementation or rejected by server): 30 (31.2%)

## How to Keep This Current
- When a mechanic is implemented, updated, or wired into the live server, update its row in the corresponding table below.
- Use status "exact" ONLY when the mechanic both runs in the live server (`Live in server: yes`) AND is validated against vanilla Minecraft 26.2 classes or captured oracle fixtures via a gating check.
- Isolated proofs that verify algorithms or data structures headlessly but are not wired into the live server tick loop or networking pipeline must be labeled "component proof (not live)" with `Live in server: no`.
- A mechanic that is on the live path but has no live subject yet (the engine ticks, but nothing creates the thing it acts on) is also labeled "component proof (not live)"; its Notes must say so explicitly rather than implying the proof was a live observation.
- Mechanics that are executed by the live server but lack a differential vanilla parity gate must be labeled "approximated".
- When functionality is stubbed, rejected, or deferred, document the concrete limitation in the Notes column.
- Audit this matrix alongside major roadmap completions (such as G2, G4, G11, G12) and Stage A regression runs.
- **Naming a gating check:** a check can only be run by its short name if it is registered in `core/build.gradle`'s `checkClassMap`. As of 2026-10-08 there are 65 registered checks and 18 further check classes that are not registered (`AsyncChunkGeneratorCheck`, `AsyncChunkSenderCheck`, `AsyncLiveOrderParityCheck`, `BeardifierCheck`, `BiomeHandoffCheck`, `EntityNbtNormalizationCheck`, `FeatureOrderCheck`, `GeneratedMetadataReloadCheck`, `LightHandoffCheck`, `LightSnapshotStabilityCheck`, `LiveStructuresTraceCheck`, `ParallelCarversCheck`, `SectionReadCheck`, `StructureContentParityCheck`, `StructureReferenceOrderCheck`, `StructuresPrefixCorpusCheck`, `StructuresPrefixStageCheck`, `VanillaSelfParityCheck`). Those must be named by fully qualified class, or registered first. One registered name, `VanillaLinkCheck` (a compile-time proof), has no `main` method, so running it by name fails (`PlayShapeCheck` has its own main). The CI check-list validator (`scripts/ci-validate-checks.sh`) only accepts registered names.

---

## 1. World Generation & Terrain

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Overworld Biomes & Terrain Noise | exact | yes | `core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java`, `core/src/vanilla/java/dev/kestrel/worldgen/LiveGenerator.java` | `BatchParityGate` | G2 Step 8/9/10: 1,418/1,418 LARGE chunks exact against vanilla with 0 cell diffs. Multi-threaded chunk generator runs at Paper speed producing byte-identical Vanilla terrain. |
| Overworld Surface & Carvers | exact | yes | `core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java`, `core/src/vanilla/java/dev/kestrel/worldgen/LiveGenerator.java` | `dev.kestrel.worldgen.ParallelCarversCheck` | Step 10 pooled carver pipeline matches inline vanilla generation with 0 diffs across 51,642 chunks. Named by fully qualified class because it is not registered in `core/build.gradle`'s `checkClassMap`, so the short name would not resolve. |
| Overworld Placed Features | exact | yes | `core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java`, `core/src/vanilla/java/dev/kestrel/worldgen/LiveGenerator.java` | `FeaturesCheck` | Placed features execute via vanilla placed-feature definitions; Step 8 LARGE 1,418/1,418 chunks match vanilla. Blocks place with vanilla exactness, but tile entity NBT and entities are dropped at live world handoff (`docs/SKIPPED-FEATURES.md`). |
| Overworld Structures & Templates | exact | yes | `core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java`, `core/src/vanilla/java/dev/kestrel/worldgen/LiveGenerator.java` | `StructuresCheck` | Structures-on canonical gate matches 1,418/1,418 chunks; integrates real `StructureTemplateManager` for fossils and structure pieces. Structure blocks place accurately, but structure entities are dropped at live handoff (`docs/SKIPPED-FEATURES.md`). |
| The Nether World Generation | component proof (not live) | no | `core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java`, `core/src/vanilla/java/dev/kestrel/worldgen/LiveGenerator.java` | `FeaturesTraceReplay --dimension=nether` over the captured traces (not a registered check) | Nether noise, biomes, carvers, features and structures run through the shared pipeline (`HeadlessNoise` dimension selection). The replay against the captured vanilla trace is exact: 324/324 chunks, 21233664 cells, 0 light diffs. Merged 2026-09-26. Not live: the live server runs the Overworld only, and nobody can travel there until G43. |
| The End World Generation | component proof (not live) | no | `core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java`, `core/src/vanilla/java/dev/kestrel/worldgen/LiveGenerator.java` | `FeaturesTraceReplay --dimension=end` over the captured traces (not a registered check) | End terrain, features and structures run through the shared pipeline. The replay against the captured vanilla trace is exact: 324/324 chunks, 21233664 cells, 0 light diffs. Merged 2026-09-26. Not live: the live server runs the Overworld only, and nobody can travel there until G43. The dragon fight is runtime logic, not generation, and is not implemented. |

---

## 2. Lighting Engine

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Worldgen Lighting Lifecycle | exact | yes | `core/src/vanilla/java/dev/kestrel/worldgen/WorldgenLightLifecycle.java`, `core/src/vanilla/java/dev/kestrel/net/ChunkSender.java` | `LiveFeaturesLightingCheck` | FEATURES -> INITIALIZE_LIGHT / LIGHT lifecycle explicitly modeled; authoritative generated light wire packets match vanilla across all 65 scored chunks on live route. |
| Live Block Light & Sky Light Updates | missing | no | none | none | No live `LevelLightEngine` exists in `TickLoop`. Placing or breaking light sources (such as torches) does not recalculate light levels or broadcast `ClientboundLightUpdatePacket`. |

---

## 3. Block Placing & Breaking

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Player Digging (Block Breaking) | partial | yes | `core/src/vanilla/java/dev/kestrel/net/BlockBreaking.java`, `core/src/vanilla/java/dev/kestrel/net/NetServer.java` | `BlockBreakingCheck` | Survival break state machine transcribed from vanilla `ServerPlayerGameMode` (2026-10-02): reach and height checks, spawn protection and world border, instant break on START (grass, flowers), multi-tick progress from vanilla's own `getDestroyProgress` (hardness, tool, correct-tool divisor, airborne penalty), the 0.7 early finish, delayed destroy, abort, the old block re-sent on a switch, and the fluid left behind (seagrass becomes water). Every dig is acked with its sequence. Other players within 32 blocks see the crack animation (`BlockDestructionBroadcast`, vanilla's `ServerLevel.destroyBlockProgress`). Other players hear and see the break (level event 2001 from vanilla's `playerWillDestroy`, the breaker left out). Tools wear through vanilla's own `ItemStack.mineBlock` (damage, `ITEM_USED`, the tool breaking with `ITEM_BROKEN` and entity event 47), and the client's slot shows it; exhaustion and `BLOCK_MINED` come from vanilla's `playerDestroy`. A START inside spawn protection gets vanilla's red overlay; spawn protection follows vanilla's operator rule, so with no ops list (S36) nothing is protected, as on a vanilla server with an empty `ops.json`. G18 slice 4 (2026-10-08): START runs vanilla's own `EnchantmentHelper.onHitBlock` and `BlockState.attack` on the stand-in player and level, so redstone ore lights (and vanilla's `randomTick` unlights it), a dragon egg teleports by vanilla's 1000-offset search over the level random, and a note block queues its block event; nothing is hit out of reach or inside spawn protection. `onHitBlock` is a no-op on vanilla's own data for a player (only Channeling has a `hit_block` effect, and it needs a thrown trident). Missing: the note block's sound (G67) and the block-event packet that would reach the client (G69), creative instant break (players are always survival, G31). |
| Player Block Placement | partial | yes | `core/src/vanilla/java/dev/kestrel/net/BlockPlacing.java`, `core/src/vanilla/java/dev/kestrel/net/NetServer.java` (`applyPlace`), `core/src/vanilla/java/dev/kestrel/entity/VanillaEntityLevel.java` | `BlockPlacingCheck` | G20 slice 1 (2026-10-08): vanilla's `handleUseItemOn` and the item half of `ServerPlayerGameMode.useItemOn` run on the player's stand-in `ServerPlayer`, so the held item's own `BlockItem.place` decides the state (`getStateForPlacement`, `canSurvive`, `Level.isUnobstructed`, `setPlacedBy`, `PLACED_BLOCK`, `consume`). Gated for stairs, logs, slabs, doors, beds, torches, hoppers, barrels, repeaters, tripwire, scaffolding, bone meal, an offhand click, a replaceable block, another player or a falling block in the cell, reach, the cursor limit, the build limits, spawn protection, the world border, a pending teleport, a client that has not loaded, out-of-range face and hand ordinals, cooldowns and the maximum-sequence ack. The stand-in level now answers bounds like vanilla (void air out of range), forwards `scheduleTick` and `updateNeighborsAt`, and returns players from `getEntities`. Owed: the clicked block's own interaction (G21: doors, chests, levers, beds), the place sound (G67), game events (G12), and the block entities `BlockEntityStore` does not create (only comparators and plain containers exist, so an item's block-entity data and components for any other type are dropped; G28, G47). Players are always survival, so the adventure `canPlaceOn` and creative branches are not transcribed (G31). |
| Block Shape Updates | partial | yes | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java`, `core/src/vanilla/java/dev/kestrel/redstone/VanillaRedstone.java` | falling-block entity tick, uncovered `onPlace` overrides | The live write path uses one `Level.setBlock` transcription and one collecting update stack. `BlockUpdateParityCheck` runs vanilla's real `Level.setBlock` on a constructor-free `ServerLevel` and compares raw writes, states, ticks and events over 28 scenarios and 4,632 checks. The widened post-process oracle remains exact with 65 block ticks. Falling ticks use each block's vanilla delay, but their entity-spawning body remains slice 2 on main. Branch `g66-falling-blocks` (not merged) adds the falling block entity, gated by `FallingBlockParityCheck` (36 live-path scenarios against a real vanilla `FallingBlockEntity`). |
| Neighbor Updates & Cascade Dispatch | exact | yes | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java` | `ScheduledTickRevisionCheck` | Transcribes vanilla `CollectingNeighborUpdater` LIFO stack and 6-direction update orders. |
| Block Drops & Experience Drops | partial | yes | `core/src/vanilla/java/dev/kestrel/net/BlockBreaking.java`, `core/src/vanilla/java/dev/kestrel/entity/EntityWorld.java`, `core/src/vanilla/java/dev/kestrel/net/LiveRandomTicks.java` | `BlockLootParityCheck`, `BlockBreakingCheck` | G19 slice 1. Breaking a block with the right tool drops vanilla's loot (Fortune, Silk Touch, shears, `block_drops`) as tracked item entities, rolled from the per-table random sequences on the world seed. The items fall, rest, merge and despawn after 5 minutes as vanilla's do (slice 2), and players pick them up (slice 3). XP is computed but orbs are not spawned until G26; infested blocks do not spawn silverfish (G44). |

---

## 4. Block Ticks & Random Ticks

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Scheduled Block Ticks (Priority & Deduplication) | exact | yes | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java` | `DuplicateScheduleCheck` | Stage A gate H4. Matches vanilla `LevelChunkTicks`/`LevelTicks`: first schedule wins per (pos, block), due ticks collected before run, and all 7 `TickPriority` values preserved. Tested side-by-side with vanilla `LevelTicks`. |
| Random Block Ticks (Crops, Leaves, Saplings, Copper) | partial | yes | `core/src/vanilla/java/dev/kestrel/loop/RandomTicks.java`, `core/src/vanilla/java/dev/kestrel/loop/RandomTickLevel.java` | `RandomTickParityCheck`, `RandomTickSpawnDrawsCheck` | G30 slice 1 runs random block and fluid ticks in vanilla's chunk and draw order and gates crops, grass, mycelium and leaf decay. Saplings, ice and snow, cactus, sugar cane, copper and other families remain ungated. Mob spawning's interleaved level-random draws are not modelled (G44), so exact draw parity with `doMobSpawning` true is still absent. Slice 2 makes both the natural-spawn and custom-spawner gaps loud through `Unanswered` instead of silently diverging. |
| Fluid Ticks Queue | exact | yes | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java`, `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java` | `FluidParityCheck` | G11 fluid ticks queue, drained after block ticks in vanilla order. |

---

## 5. Fluids (Water & Lava)

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Water & Lava Flow Simulation | exact | yes | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java`, `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java` | `FluidParityCheck` | G11 fluid simulation; 25/25 exact scenarios matching vanilla dedicated server oracle fixtures line for line (commit `aaf7d1f` added campfire/candle dousing and comparator front fan-out). Transcribes FlowingFluid, WaterFluid, LavaFluid, slope finding, spread delays, and random 4x spread delay. |
| Fluid Conversion (Obsidian, Cobblestone, Basalt) | exact | yes | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java` | `FluidParityCheck` | Replicates `LiquidBlock.shouldSpreadLiquid` for water meeting lava; tested in `FluidParityCheck` (25/25). |
| Water & Lava Source Conversion | exact | yes | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java` | `FluidParityCheck` | Replicates infinite water creation, governed by `waterSourceConversion` and `lavaSourceConversion` gamerule toggles; tested in `FluidParityCheck` (25/25). |
| Waterlogging | exact | yes | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java` | `FluidParityCheck` | Replicates `SimpleWaterloggedBlock.placeLiquid`; tested in `FluidParityCheck` (25/25). |
| Bubble Columns | approximated | yes | `core/src/vanilla/java/dev/kestrel/loop/PostProcess.java`, `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java` | `PostProcessParityCheck` (worldgen columns only) | Columns over worldgen magma and soul sand are raised when their chunk activates, by vanilla's own `BubbleColumnBlock.updateColumn` (39 natural columns exact in the gate). The 20-tick block tick a water source over magma or soul sand schedules is queued as in vanilla but runs nothing, so columns do not form, grow or change when blocks change later, and no column drags entities. |
| Fluid Fizz & Extinguish Level Events | missing | no | none | none | Level event 1501 is a no-op; no fizz sounds or smoke particles are emitted (`core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java`). |
| Worldgen Spring Ingestion | missing | no | none | none | Fluid ticks scheduled during world generation are discarded at handoff; natural springs do not flow until disturbed by a neighbor update (`docs/SKIPPED-FEATURES.md`). |

---

## 6. Redstone Logic & Components

G12 status: the engine and the primary components are DONE, merged and live.
`RedstoneParityCheck` replays 29 scenarios against fixtures captured from a real
vanilla 26.2 dedicated server and requires an exact line-for-line match, with a
mutation test proving each new fixture can fail. The section 1.3 secondary
components are NOT built, and most of them need entities or containers first.

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Redstone Wire / Dust | exact | yes | `core/src/vanilla/java/dev/kestrel/redstone/WireLogic.java`, `core/src/vanilla/java/dev/kestrel/redstone/WireConnectionCache.java`, `core/src/vanilla/java/dev/kestrel/redstone/WireUpdateFilter.java` | `RedstoneParityCheck` | G12 slice 1 (merge `024ed43`). Transcribes vanilla's wire power algebra and its update order; exact against the `wire_*` oracle fixtures. |
| Redstone Torch (standing and wall) | exact | yes | `core/src/vanilla/java/dev/kestrel/redstone/BlockLogic.java`, `core/src/vanilla/java/dev/kestrel/redstone/RedstoneHost.java` | `RedstoneParityCheck` | G12 slices 2-4 (merge `3f0f7a3`). Lit toggle, the delay-2 schedule and torch burnout (`RECENT_TOGGLES`) are all modeled; fixtures `torch_*`. |
| Redstone Repeater | exact | yes | `core/src/vanilla/java/dev/kestrel/redstone/DiodeLogic.java` | `RedstoneParityCheck` | G12 slices 2-4. Delays 2, 4, 6 and 8 ticks plus side-diode locking; fixtures `repeater_*`. |
| Redstone Comparator | exact | yes | `core/src/vanilla/java/dev/kestrel/redstone/DiodeLogic.java`, `core/src/vanilla/java/dev/kestrel/redstone/AnalogOutput.java` | `RedstoneParityCheck` | G12 slices 2-4. Compare and subtract modes and container reading through `ComparatorBlockEntity`; fixtures `comparator_*`. |
| Observer | exact | yes | `core/src/vanilla/java/dev/kestrel/redstone/BlockLogic.java` | `RedstoneParityCheck` | G12 slices 2-4. `updateShape` start signal and the 2-tick pulse; fixtures `observer_*`. |
| Lever | exact | yes | `core/src/vanilla/java/dev/kestrel/redstone/BlockLogic.java` | `RedstoneParityCheck` | G12 slices 2-4. Face and direction attached mapping; fixture `lever_power_types`. |
| Buttons (stone and wood) | exact | yes | `core/src/vanilla/java/dev/kestrel/redstone/BlockLogic.java` | `RedstoneParityCheck` | G12 slices 2-4. Per-material press durations via `ButtonBlock.ticksToStayPressed`; fixtures `button_stone_duration`, `button_wood_duration`. |
| Redstone Lamp | exact | yes | `core/src/vanilla/java/dev/kestrel/redstone/BlockLogic.java` | `RedstoneParityCheck` | G12 slices 2-4. Lit state and timing; fixture `lamp_timings`. |
| Pistons & Sticky Pistons | exact | yes | `core/src/vanilla/java/dev/kestrel/redstone/PistonLogic.java`, `core/src/vanilla/java/dev/kestrel/redstone/MovingPiston.java` | `RedstoneParityCheck` | G12 slice 5 (merge `db4aff8`). Extension, retraction, the 12-block push limit, slime and honey stickiness, block dropping and quasi-connectivity (BUD); fixtures `piston_*`. |
| Block-Event Queue & Moving Pistons | exact | yes | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java` (`runBlockEvents`), `core/src/vanilla/java/dev/kestrel/loop/BlockEntityStore.java` | `RedstoneParityCheck` | G12 slice 5 and the coverage-gap fix (merge `8091127`). Vanilla `BlockEventData` enqueue and LIFO drain order, and the `handlingTick` lifetime (vanilla clears it at `ServerLevel.java:408`, straight after `runBlockEvents`); fixtures `piston_block_events_fifo`, `piston_block_events_contention`, `piston_settle_power_cut`. Server side only: no `ClientboundBlockEventPacket` is sent to any client (roadmap G69), and `BlockLogic.triggerEvent` handles pistons only, so a hit note block queues its event and the drain finds nothing to do. |
| Alternate Current Wire Engine | component proof (not live) | no | `core/src/vanilla/java/dev/kestrel/redstone/AcWireNetwork.java`, `core/src/vanilla/java/dev/kestrel/redstone/AcFlag.java` | `AcWireProofCheck`, `AcFlagCheck` | Standalone implementation of Alternate Current, verified by internal unit checks. Opt-in engine, not active by default in the live server, and not compared against a vanilla oracle (`preresearch/g12-redstone-preresearch.md` section 5.1). |
| Unbuilt Redstone Components | missing | no | none | none | The section 1.3 secondary components: pressure plates, tripwire, target block, daylight detector, note block, bells, doors, trapdoors, fence gates, dispenser, dropper, crafter, copper bulb, sculk sensor, calibrated sculk sensor, lightning rod, TNT and rails. Most need entities or containers (S14, G17, G28). |

## 7. Entities & Mobs

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Entity Simulation Loop (TickLoop entity ticking) | component proof (not live) | partial | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java` | `ItemEntityTickCheck` | S14 slice 3 added the entity tick phase in vanilla's `entities` slot, after the block events and before the block entities, inside the `emptyTime < 300` guard, with `checkDespawn`, the ticking-range test, vehicle gating and the crash guard in vanilla's per-entity order. The phase runs on the live path, but nothing in normal play creates an entity, so no entity ticks in practice. The gate proves the slot by observing the order across 40 real `TickLoop` ticks rather than by a label. |
| Entity Storage & Indexing | component proof (not live) | no | `core/src/vanilla/java/dev/kestrel/entity/EntitySectionManager.java`, `core/src/vanilla/java/dev/kestrel/entity/EntitySectionStorage.java`, `core/src/vanilla/java/dev/kestrel/entity/EntitySection.java`, `core/src/vanilla/java/dev/kestrel/entity/EntityLookup.java`, `core/src/vanilla/java/dev/kestrel/entity/EntityVisibility.java` | `EntityStorageCheck` | S14 slice 1. Per-chunk 16x16x16 sections, id and UUID lookup with known-UUID rejection, section crossing and reaping, and the (accessible, ticking) visibility mapping. The gate drives Kestrel's manager and a real vanilla `PersistentEntitySectionManager` over the same probes and compares query results and section-visit order, plus 8 mutations that each make it fail. Not wired into `KestrelLevel`, so nothing loads, saves or ticks an entity yet. |
| Entity Tracking & Entity Packet Stream | component proof (not live) | partial | `core/src/vanilla/java/dev/kestrel/entity/EntitySync.java`, `core/src/vanilla/java/dev/kestrel/entity/EntityTracker.java`, `core/src/vanilla/java/dev/kestrel/net/EntityOutbound.java` | `EntityTrackingCheck` | S14 slice 2. Mirrors `ServerEntity` (delta encoding over vanilla's `VecDeltaCodec`, full-sync triggers, the 60-tick refresh, motion, head rotation, dirty data, attributes, the pairing bundle) and `ChunkMap`'s private `TrackedEntity` visibility rule. Ticked from `TickLoop` in vanilla's `chunkSource` slot with observers registered by `NetServer`, so the engine is on the live path, but nothing creates an entity in normal play, which is why this is a proof and not a live observation. The gate compares packet streams byte for byte, ids included, against a real `ServerEntity` over 47 scripted runs and 16,225 ticks, plus 503,100 visibility decisions against a brute-force rule, plus 22 mutations of which 21 failed the gate at once. Cannot yet express equipment, leashes, item-frame maps, the minecart path, projectile power or per-type add-entity data; the living data layout is slice 6. |
| Item Entity (tick, terrain resting, lifetime) | component proof (not live) | partial | `core/src/vanilla/java/dev/kestrel/entity/KestrelItemEntity.java`, `core/src/vanilla/java/dev/kestrel/entity/EntityWorld.java`, `core/src/vanilla/java/dev/kestrel/entity/EntityIds.java` | `ItemEntityTickCheck` | S14 slice 3. Mirrors `ItemEntity.tick` step by step: gravity, the float drag and ground friction read from the item's own position, terrain collision delegated to `KestrelCollide`, resting, and despawn exactly at `LIFETIME`. Players and entities mint ids from one counter (`EntityIds`, mirroring `ServerLevel.getNextEntityId`), which closes a real collision bug: a player and an entity could previously be handed the same id. The gate runs a real vanilla `ItemEntity` in a headless level shell over the same block store and compares every tick in raw bits with no epsilon: 4,842,392 comparisons, 105 named and 260 seeded terrain scenarios, the lifetime boundary from `LIFETIME-3` to `LIFETIME+1`, 400 interleaved ids, 18 pairing bundles and a 1,537-packet stream byte for byte, plus 31 mutations of which 30 fail the gate and the survivor is provably harmless. Since G19 slice 1, breaking a block creates them (row Block Drops above); the developer flag `-Dkestrel.dev.dropItemsOnJoin=true` remains. Merging, pickup and the Q drop landed in G19 slices 2 and 3; inventory-screen throws and outside drops landed in G17 slice 4. |
| Collision Fast Path | component proof (not live) | no | `core/src/vanilla/java/dev/kestrel/collision/KestrelCollide.java`, `core/src/vanilla/java/dev/kestrel/collision/ShapeBoxCache.java` | `KestrelCollideCheck` | Component proof against vanilla `Entity.collideBoundingBox` measuring 6.6x speedup. Not wired into live server movement, entities, or physics. |
| Mob AI Spatial Sensors & Schedules | component proof (not live) | no | `core/src/vanilla/java/dev/kestrel/ai/BehaviorSchedule.java`, `core/src/vanilla/java/dev/kestrel/ai/LosVerdictCache.java`, `core/src/vanilla/java/dev/kestrel/ai/PlayerScan.java`, `core/src/vanilla/java/dev/kestrel/ai/VisibleEntityScan.java` | `MobAiProofCheck` | Component proof verified bit-identical to vanilla distance math, sorting, and memory requirements. Not integrated into live server ticking. Kestrel has no GoalSelector, Goal, PathNavigation, or PathFinder code (`preresearch/g7-mobs-preresearch.md` section 8). |
| Mob Natural Spawning Algebra | component proof (not live) | no | `core/src/vanilla/java/dev/kestrel/spawn/SpawnCaps.java`, `core/src/vanilla/java/dev/kestrel/spawn/SpawnPotential.java`, `core/src/vanilla/java/dev/kestrel/spawn/SpawnDrawModel.java` | `SpawnCapsCheck` | Component proof for density caps and potential math. No active spawner loop runs during live gameplay (`preresearch/g7-mobs-preresearch.md` section 8). |
| Live Mob Spawning & Despawning | missing | no | none | none | Live server does not execute mob spawning passes or spawn algorithms during play (`docs/SKIPPED-FEATURES.md`). The tracking engine that would send them landed as S14 slice 2 and the entity tick phase with item entities landed as S14 slice 3, and since G19 slice 1 block drops create them; death drops do too (G22). |
| Living Entity Behaviors & Interactions | missing | no | none | none | Breeding, taming, shearing, mob attacking, panic, fleeing, and goals are absent. The tracking engine that would send them landed as S14 slice 2 and the entity tick phase with item entities landed as S14 slice 3, and since G19 slice 1 block drops create them; death drops do too (G22). |

---

## 8. Combat & Health

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Player Attack & Damage Calculation | missing | no | none | none | Inbound attack packets are absorbed without action (`core/src/vanilla/java/dev/kestrel/net/NetServer.java`). No damage calculation, weapon damage, or critical hits. |
| Health, Damage Sources & Invulnerability Frames | partial | yes | `core/src/vanilla/java/dev/kestrel/entity/KestrelServerPlayer.java`, `core/src/vanilla/java/dev/kestrel/net/VanillaPacketBridge.java`, `core/src/vanilla/java/dev/kestrel/net/PlayerBodies.java`, `core/src/vanilla/java/dev/kestrel/net/EntityOutbound.java` | `PlayerSelfPacketsCheck` | G22 slice 1. Each live player now runs vanilla's health, invulnerability window, delta-damage rule and packet send sites behind a real `ServerGamePacketListenerImpl`. The gate compares live socket bytes and state against a real vanilla player for fresh, refused, larger and lethal hits. Environmental damage-source triggers are not yet wired. |
| Projectiles (Arrows, Tridents, Snowballs) | missing | no | none | none | Bows cannot shoot, tridents cannot be thrown, and projectile physics do not exist. The tracking engine that would send their packets landed as S14 slice 2 and the entity tick phase landed as S14 slice 3, and since G19 slice 1 block drops create them; death drops do too (G22). |
| Death & Respawn | partial | yes | `core/src/vanilla/java/dev/kestrel/net/PlayerBodies.java`, `core/src/vanilla/java/dev/kestrel/entity/KestrelServerPlayer.java` | `PlayerDeathParityCheck` | G22 slice 2. Vanilla `ServerPlayer.die` runs: death screen and message, death animation for others, body removed at deathTime 20, inventory dropped as items unless `keepInventory`, respawn at the world spawn. Bed and anchor respawn, end credits and dimension changes are not built; health is not saved. |

---

## 9. Player Mechanics & Movement

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Movement Validation & Anti-Cheat | approximated | yes | `core/src/vanilla/java/dev/kestrel/net/NetServer.java` | none | Tested in `NetCheck.java`. Clamps horizontal speed (0.60 m/tick), vertical rise (0.50 m/tick), jump apex (1.2523 m, vanilla's 1.2522033 apex; 1.25 rejected the top of every jump until G15 slice 1), teleport clamp (64 blocks), and rubber-bands on 20 violations (`NetServer.java`). |
| Seeing Other Players (tab list, bodies, movement, pose, swing, equipment) | exact | yes | `core/src/vanilla/java/dev/kestrel/net/PlayerBodies.java`, `core/src/vanilla/java/dev/kestrel/entity/KestrelPlayerEntity.java`, `core/src/vanilla/java/dev/kestrel/entity/KestrelServerPlayer.java`, `core/src/vanilla/java/dev/kestrel/net/PlayerOutbound.java`, `core/src/vanilla/java/dev/kestrel/net/EntityOutbound.java` | `PlayerTrackingParityCheck` | G15 slices 1 and 2 plus G22 slice 1. Each joined player has one real vanilla `ServerPlayer` that owns inventory, menu, health, synched data, attributes, pose, movement and equipment state. Tab rows, pairing bundles, movement, head rotation, sneaking, sprinting, swings, damage events, non-empty equipment pairing, per-tick equipment changes and clean hand swaps as entity event 55 are compared byte for byte per observer and tick. Swimming, gliding, sleeping and riding are not modelled, and tab list latency updates are not sent (G24, G31). |
| Full Player Physics Simulation | missing | no | none | none | Terrain collision, slipperiness, swimming buoyancy physics, and elytra flight simulation are not implemented (`core/src/vanilla/java/dev/kestrel/net/NetServer.java`). |
| Hunger, Saturation & Starvation | missing | no | none | none | Food level, exhaustion, starvation, and natural healing are not implemented. |
| Experience, Levels & Orbs | missing | no | none | none | Experience points, levels, and experience orbs do not exist. |
| Status Effects & Potions | missing | no | none | none | Mob effects (such as Speed, Poison, Regeneration) are not tracked or ticked. |
| Beds & Sleep | missing | no | none | none | Right-clicking beds does not set spawn points or allow sleeping to skip the night. |

---

## 10. Items & Inventory

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Player Inventory Management | partial | yes | `core/src/vanilla/java/dev/kestrel/item/PlayerInventory.java`, `core/src/vanilla/java/dev/kestrel/entity/KestrelServerPlayer.java`, `core/src/vanilla/java/dev/kestrel/net/NetServer.java` | `InventoryParityCheck`, `InventoryClickParityCheck`, `BlockBreakingCheck` | One real vanilla inventory and menu per player: main inventory, hotbar, armour, offhand and cursor. G17 slice 4 routes pickup, quick move, hotbar/offhand swap, drag, double-click collection, slot throws, outside drops and close through vanilla menu logic. Correct client hashes suppress repairs; wrong hashes repair slots or cursor, and stale state ids resync the full menu. The oracle drives real vanilla click/close handlers and compares state and packet bytes. Creative inventory, pick-block, crafting recipes and playerdata persistence remain deferred. |
| Item Stack Wire Codec | component proof (not live) | no | `core/src/vanilla/java/dev/kestrel/item/ItemStackCodec.java`, `core/src/vanilla/java/dev/kestrel/item/KestrelItemStacks.java` | `ItemStackCodecCheck` | G17 slice 1. Walks vanilla's stack wire format on Kestrel's own dependency-free buffers, delegating every leaf to vanilla's codecs, because Kestrel's networking has no Netty and cannot hand a raw buffer to vanilla's `RegistryFriendlyByteBuf` codecs. The gate asserts byte-identical output against vanilla's `STREAM_CODEC` for an empty stack, a plain stack and a component-bearing stack, round trips both ways, and checks count rules by asking vanilla. Nothing in the live server calls it yet. |
| Item Entities & Item Drops | partial | partial | `core/src/vanilla/java/dev/kestrel/entity/KestrelItemEntity.java` | `ItemEntityMergeCheck`, `ItemPickupAndDropCheck` | Item merging is in (G19 slice 2). Q and Ctrl+Q drop the held item and players pick items up as vanilla's do (G19 slice 3); throwing from the inventory screen waits for container clicks (G17). Before those, the item entity itself already ticked, rested on terrain and despawned at its lifetime (see the item entity row above). The tracking engine that would send them landed as S14 slice 2 and the entity tick phase with item entities landed as S14 slice 3, and since G19 slice 1 block drops create them; death drops do too (G22). Item stacks themselves now have a gated wire codec, see the row above. |
| Item Usage & Consumption | missing | no | none | none | Consuming food/potions, bucket placement/pickup, bow charging, and shield blocking are absent. |
| Tool & Armor Durability | missing | no | none | none | Items have no durability degradation or breaking mechanics. |

---

## 11. Crafting & Workstations

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| 2x2 and 3x3 Crafting | missing | no | none | none | Player crafting grid and crafting tables cannot craft; `core/src/vanilla/java/dev/kestrel/net/Outbound.java` sends empty recipe list. |
| Recipe Book & Datapack Recipes | missing | no | none | none | Recipe unlocks, recipe book syncing, and datapack recipe parsing are absent. |
| Workstation Mechanics (Furnace smelting, stonecutter, smithing table, anvil, grindstone, loom, cartography table) | missing | no | none | none | Workstation GUIs and conversion recipes are completely unimplemented. |

---

## 12. Containers & Block Entities

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Block Entity Sleeping Framework | component proof (not live) | no | `core/src/vanilla/java/dev/kestrel/blockentity/SleepSet.java`, `core/src/vanilla/java/dev/kestrel/blockentity/SleepLoop.java`, `core/src/vanilla/java/dev/kestrel/blockentity/SleeperRegistry.java` | `SleeperCheck` | Proven via component checks. Algorithmic proof for 10 block entity sleep predicates; not wired into functional live server block entity ticking. |
| Hopper Transfers & Cooldowns | component proof (not live) | no | `core/src/vanilla/java/dev/kestrel/hopper/HopperTransfer.java`, `core/src/vanilla/java/dev/kestrel/hopper/HopperState.java` | `HopperTransferCheck` | Component proof verified bit-identical to vanilla `HopperBlockEntity.addItem` and chain cooldown logic. Not wired into live server block entities. |
| Live Block Entity Storage & Metadata | approximated | partial | `core/src/vanilla/java/dev/kestrel/loop/KestrelLevel.java` | none | Live server stores block entities only as `BlockEntityRecord(pos, type)` without NBT (`docs/SKIPPED-FEATURES.md`). Chest loot tables, spawner mobs, and shrieker flags are stripped at handoff. |
| Container GUIs & Player Access (Chests, Barrels, Shulkers, Furnaces) | missing | no | none | none | Container opening packets (`ClientboundOpenScreenPacket`) are not implemented; clicking containers does not open an interface. |
| Specialized Block Entities (Signs, Brewing Stands, Enchanting Tables, Beacons, Conduits, Lodestones, Crafter, Trial Spawner, Vault) | missing | no | none | none | Interactive behaviors and tile entity processing are unimplemented (only passive sleep predicates exist in `SleepPredicates.java`). |

---

## 13. Environment & World State

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Day / Night Cycle & Time | exact | yes | `core/src/vanilla/java/dev/kestrel/net/WorldTime.java`, `core/src/vanilla/java/dev/kestrel/net/NetServer.java` | `ClockParityCheck` | Monotonic totalTicks advances 1 tick per tick without wrapping, matching vanilla ServerClockManager.ClockInstance.totalTicks across 100,000 ticks. Moon phase (totalTicks / 24000) % 8 progresses across all 8 phases. Wire packet Outbound.setTime byte-identical to ClientboundSetTimePacket.STREAM_CODEC across boundary conditions and 10^12. Gated by ClockParityCheck and tested in NetCheck. |
| Weather (Rain Cycle) | approximated | yes | `core/src/vanilla/java/dev/kestrel/net/WorldTime.java` | none | Replicates rain interval delays (12,000-180,000 clear, 12,000-24,000 raining) and 0.01 easing per tick (`WorldTime.java`). Tested in `NetCheck.java`. |
| Weather (Thunder & Lightning) | missing | no | none | none | Explicitly omitted (`core/src/vanilla/java/dev/kestrel/net/WorldTime.java`: `THUNDER_STUB: thunder is not simulated`). No lightning strikes or thunder sounds occur. |
| Game Rules | missing | no | none | none | No game rule manager or command interface exists; only two hardcoded fluid flags exist in `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java`. |

---

## 14. Chat & Commands

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Chat Receive & Broadcast | approximated | yes | `core/src/vanilla/java/dev/kestrel/net/NetServer.java`, `core/src/vanilla/java/dev/kestrel/net/Outbound.java` | none | Inbound player chat is formatted as `<Player> message` and broadcast to all players via `ClientboundSystemChatPacket` (`NetCheck.java`). Empty and >256 char messages absorbed. |
| Cryptographic Chat Signatures | missing | no | none | none | Message signatures and signed chat commands are absorbed or ignored (`core/src/vanilla/java/dev/kestrel/net/NetServer.java`). |
| Server Console Commands | approximated | yes | `core/src/vanilla/java/dev/kestrel/net/KestrelServer.java` | none | Only `save-all` and `stop` are implemented via stdin (`KestrelServer.java`). |
| In-Game Chat Commands | missing | no | none | none | Chat commands (`/tp`, `/gamemode`, `/give`, etc.) are absorbed without execution (`core/src/vanilla/java/dev/kestrel/net/NetServer.java`). |

---

## 15. Saving & Persistence

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Anvil MCA Chunk Storage | exact | yes | `core/src/vanilla/java/dev/kestrel/persist/RegionFile.java`, `core/src/vanilla/java/dev/kestrel/persist/ChunkCodec.java`, `core/src/vanilla/java/dev/kestrel/persist/SectionCodec.java` | `PersistCheck` | Parsed and validated under vanilla `RegionFileStorage.read` and `NbtIo`; passes round-trip equality. |
| KEX Sidecar Persistence (KEX1-KEX5) | exact | yes | `core/src/vanilla/java/dev/kestrel/persist/Sidecar.java` | `PersistCheck` | Stage A gates C1 and H1. Persists cell mutation provenance, generated-center completion, biome/light wire bytes, and pending fluid ticks (KEX5). |
| World Storage & Autosave Engine | exact | yes | `core/src/vanilla/java/dev/kestrel/persist/WorldStore.java`, `core/src/vanilla/java/dev/kestrel/persist/AsyncSaver.java`, `core/src/vanilla/java/dev/kestrel/persist/WorldSnapshot.java` | `PersistCheck` | Verified in `PersistCheck` async save and round-trip tests. Runs periodically and on shutdown in live server. |
| Player State Persistence | approximated | yes | `core/src/vanilla/java/dev/kestrel/net/StateStore.java` | none | Custom properties store saving x/y/z/yaw/pitch and `heldSlot` (`KestrelPlayer.java`). Full inventory, health, gamemode, and stats are not persisted. |
| Level.dat Persistence | approximated | yes | `core/src/vanilla/java/dev/kestrel/net/StateStore.java` | none | Saves time and weather fields in custom text properties. Standard vanilla `level.dat` compound NBT is not written. |

---

## 16. Networking & Protocol

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Non-Blocking TCP Socket Server | exact | yes | `core/src/vanilla/java/dev/kestrel/net/NetServer.java`, `core/src/vanilla/java/dev/kestrel/net/Wire.java` | `NetCheck` | Covered by `NetCheck` loopback suite with fault injection (`README.md`). Non-blocking NIO selectors, packet framing, and VarInt codecs. |
| server.properties (S32) | approximated | yes | `core/src/vanilla/java/dev/kestrel/net/ServerProperties.java`, `core/src/vanilla/java/dev/kestrel/net/KestrelServer.java` | `ServerPropertiesCheck`, `PauseWhenEmptyCheck` | The file is vanilla-exact: created through vanilla's own `DedicatedServerSettings` when missing (69 keys, defaults diffed against an independent vanilla write), and comments, blank lines, unknown keys and colon delimiters all survive a load/set/save. Command line arguments override the file where Kestrel exposes them. Honoured today: `server-port`, `online-mode`, `motd`, `max-players`, `view-distance`, `simulation-distance`, `allow-flight`, `spawn-protection` (which, as in vanilla, protects nothing while there are no operators; Kestrel has no ops list until S36), and `pause-when-empty-seconds`. The pause default is 60 seconds, zero disables it, and its stopped phases plus connection-only wake-up path are parity-gated. Every other vanilla key parses and round-trips but is not yet honoured, which is why this row is `approximated` even though the file format itself is parity-gated. |
| Server Status Ping (Server List Ping) | exact | yes | `core/src/vanilla/java/dev/kestrel/net/NetServer.java`, `core/src/vanilla/java/dev/kestrel/net/PacketIds.java`, `core/src/vanilla/java/dev/kestrel/net/Outbound.java` | `StatusPingCheck` | Full modern status intent (handshake next-state 1, status request 0x00, status response 0x00, ping/pong 0x01 exchange and clean disconnect) plus legacy ping (0xFE, 0xFE 0x01, and 1.6 MC|PingHost custom payload). Wire JSON (version, players, MOTD description, 64x64 favicon, enforcesSecureChat) parity-gated differentially against vanilla 26.2 ServerStatus and ClientboundStatusResponsePacket codecs. |
| Login Authentication & Encryption | exact | partial | `core/src/vanilla/java/dev/kestrel/net/NetServer.java`, `core/src/vanilla/java/dev/kestrel/net/SessionCipher.java` | `NetCheck` | RSA challenge, AES/CFB8 packet encryption, and Mojang skin property codecs verified in `NetCheck` (`README.md`). The live server runs in offline mode, so the encryption path is not exercised there. |
| Configuration Phase Negotiation | exact | yes | `core/src/vanilla/java/dev/kestrel/net/NetServer.java`, `core/src/vanilla/java/dev/kestrel/net/SyncedRegistries.java`, `core/src/vanilla/java/dev/kestrel/net/Datapack.java` | `NetCheck` | Validated against vanilla codecs: brand, features, known packs, registry sync, and update tags decode through `NbtIo.readAnyTag` (`README.md`). |
| Chunk Streaming & Monotonic Revisions | exact | yes | `core/src/vanilla/java/dev/kestrel/net/ChunkSender.java` | `ScheduledTickRevisionCheck` | Stage A gate H3. Single revision authority in `ChunkSender`, dirty tracking, paletted container serialization decoded through vanilla codec. |

---

## 17. Chunk Loading & World Management

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| View Distance Chunk Streaming | approximated | yes | `core/src/vanilla/java/dev/kestrel/net/ChunkSender.java` | none | Chunks are streamed in spiral order around player up to negotiated view distance (`player.viewDistance`), but no ticket levels (inaccessible, border, ticking) or simulation distance decoupling exists. |
| Server Chunk Unloading | missing | no | none | none | Chunks in `LiveGenerator` and `SectionStore` are never evicted or unloaded from server memory (`docs/ARCHITECTURE-AUDIT.md`). |
| Chunk Activation Post-Processing | approximated | yes | `core/src/vanilla/java/dev/kestrel/loop/PostProcess.java`, `core/src/vanilla/java/dev/kestrel/loop/ChunkActivation.java` | `PostProcessParityCheck` | Vanilla's `LevelChunk.postProcessGeneration` when a chunk becomes ticking: mushrooms in daylight are removed, worldgen water is ticked, bubble columns are raised and marked shapes are fixed. The pass is exact against vanilla's own method on the gate's region (1323 checks), and the marks persist byte-equal to vanilla's `PostProcessing` tag. Approximated for two reasons. The trigger is not vanilla's: there are no tickets, so every chunk whose 5x5 is generated activates, and a chunk can be sent before its pass (debts listed in `ChunkActivation`, item S16). And water the pass sets does not schedule falling-block ticks (G66). |

---

## 18. Portals & Dimensions

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Nether Portals | missing | no | none | none | Obsidian frames cannot be ignited, portal blocks do not teleport players, coordinate scaling (8:1) is absent (`core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java`). |
| End Portals & Gateways | missing | no | none | none | End portal frames cannot be filled with Eyes of Ender, portal blocks do not teleport players (`core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java`). |
| Dimension Switching | missing | no | none | none | Only Overworld is instantiated; respawn or change dimension packets are not sent. |

---

## 19. Villagers & Trading

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Villager POI Detection & Schedule | component proof (not live) | no | `core/src/vanilla/java/dev/kestrel/ai/PoiTopK.java`, `core/src/vanilla/java/dev/kestrel/ai/SecondaryPoiScan.java`, `core/src/vanilla/java/dev/kestrel/ai/BehaviorSchedule.java` | `MobAiProofCheck` | Component proof for secondary POI scanning, top-K search, and schedule parsing. Not integrated into live server. |
| Villager Trading, Restocking & Gossip | missing | no | none | none | Villager trade generation, trading GUI, emerald transactions, restock schedules, and gossip are absent. |

---

## 20. Raids & World Events

| Mechanic | Status | Live in server | Kestrel file(s) | Gating check | Notes |
|---|---|---|---|---|---|
| Raids & Bad Omen | missing | no | none | none | Bad Omen, raid captains, raid waves, raid horns, and hero of the village are unimplemented. |

Last audited against the code and the gates: 2026-10-02, branch `6f95253` (G17 inventory and G18 digging/spawn protection rows; 64 registered checks; tally recomputed from all 96 rows).

