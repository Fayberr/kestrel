# Kestrel core

`core/` contains Kestrel's server implementation and subsystem engines. It compiles directly against vanilla 26.2 classes when `-PvanillaJar` and `-PvanillaLibs` are provided, allowing Kestrel to reuse vanilla's type hierarchy and deobfuscated symbols (`BlockState`, `Shapes`, `VoxelShape`, registries) while replacing the underlying engine: networking, tick loop, compact memory storage, world generation, and persistence.

## Architecture & Modules

The core codebase is organized into live server engine modules and component-level optimization packages:

### Live Integrated Server Engine

- **Networking (`dev.kestrel.net`)**:
  - `NetServer`: Non-blocking TCP socket server handling connection lifecycle, client login, configuration protocol exchanges, play packets, and keep-alive pings.
  - `ChunkSender`: Chunk streaming engine that tracks connected players, view distance envelopes, dirty chunk notifications, and monotonic chunk revisions to push updates to clients.
  - `KestrelServer`: The runnable server entry point that initializes registries, binds TCP listeners, spins the tick loop, manages players, and coordinates auto-saves.
  - Supporting networking classes: `KestrelPlayer`, `SyncedRegistries`, `StateStore`, `SessionCipher`, `Wire`.

- **Loop & Storage (`dev.kestrel.loop`)**:
  - `TickLoop`: Authoritative 20 TPS server tick scheduler and loop coordinator. Executes scheduled block/fluid ticks in vanilla's phase order, drains the block-event queue after the fluid ticks and before the block-entity pass (so piston events settle on the right tick), dispatches block and player events, and drives level updates.
  - `SectionStore`: High-performance 16x16x16 chunk section storage supporting both compact paletted representation and dense arrays, with copy-on-write promotion to dense storage upon the first block modification.
  - `KestrelLevel`: Concrete level representation providing world access, coordinate bounds, block query dispatch, and modification hooks without relying on vanilla `ServerLevel` overhead.
  - Supporting loop classes: `EventHooks`, `KestrelChunk`, `KestrelPos`, `KestrelState`.

- **World Generation (`dev.kestrel.worldgen`)**:
  - `LiveGenerator`: Multi-phase terrain generator driving vanilla worldgen passes (noise, surface, carvers, and features) on demand as chunks are requested by players or generation targets.
  - `HeadlessNoise`: Headless density function and noise router evaluator that drives vanilla noise generation without requiring a full vanilla level hierarchy.
  - Supporting worldgen classes: `BatchParityGate`, `FeaturesCheck`, `StructuresCheck`, `ParallelNoiseCheck`.

- **Persistence (`dev.kestrel.persist`)**:
  - `WorldStore`: World persistence coordinator managing chunk saving and loading, snapshot generation, dirty state tracking, and vanilla Anvil world import.
  - `RegionFile`: High-throughput Anvil/MCA region file reader and writer with custom sector allocation and chunk compression.
  - Supporting persistence classes: `AsyncSaver`, `ChunkCodec`, `SectionCodec`, `StateNbt`, `Sidecar`, `BlockHash`.

- **Gameplay Engines (`dev.kestrel.loop`, `dev.kestrel.redstone`)**:
  - `FluidFlow`: exact vanilla water/lava cellular flow (G11), transcribed from `FlowingFluid`, `WaterFluid`, `LavaFluid` and `LiquidBlock`. Gated by `FluidParityCheck`.
  - `WireLogic`, `DiodeLogic`, `PistonLogic`, `MovingPiston`, `BlockLogic`, `VanillaRedstone`, `RedstoneHost`, `AnalogOutput`: the exact vanilla redstone engine (G12): wire and signal queries, repeaters and comparators, torch burnout, observers, levers, buttons, lamps, quasi-connectivity, the block-event queue, the structure resolver and the moving-piston placeholders. Gated by `RedstoneParityCheck`. Pressure plates and the redstone-adjacent blocks that need entities or containers are not transcribed (`BlockLogic`'s class javadoc is the coverage list).

### Component Proofs & Optimizations

Isolated subsystems verified via standalone proof checks and microbenchmarks:

- **Collision (`dev.kestrel.collision`, `dev.kestrel.block`)**: `KestrelCollide`, `ShapeBoxCache`, `KestrelBlockGetter`. Bypasses expensive `Shapes.joinIsNotEmpty()` boolean algebra using direct AABB-vs-AABB intersection tests against cached shape bounding boxes (6.6x speedup measured).
- **Block Entities (`dev.kestrel.blockentity`)**: `SleepSet`, `SleepLoop`, `SleeperRegistry`. Tracks active vs idle block entities to eliminate tick overhead for quiescent containers and furnaces.
- **Hoppers (`dev.kestrel.hopper`)**: `HopperTransfer`, `HopperState`. Fast-path inventory transfer logic avoiding vanilla container lookup overhead.
- **Redstone (`dev.kestrel.redstone`)**: `WireConnectionCache`, `WireSectionLayout`, `WireUpdateFilter` (generation-scoped connection memoization, a 16x16x16 wire scratch store, and no-op update suppression on the default path), plus the opt-in Alternate Current set (`AcWireNetwork`, `AcFlowTable`, `AcDirections`, `AcUpdateOrder`, `AcFlag`). The default-path engine itself is live, see Gameplay Engines above.
- **Mob AI (`dev.kestrel.ai`)**: `BehaviorSchedule`, `LosVerdictCache`, `SensorScanBench`. Spatial sensor scanning and line-of-sight query caching for entities.
- **Spawn Logic (`dev.kestrel.spawn`)**: `SpawnCaps`, `SpawnDrawModel`. Entity density cap tracking and spawn candidate evaluation.

## Building against real vanilla classes

`core/` compiles directly against vanilla's real, deobfuscated 26.2 classes (`BlockState`, `Shapes`, `VoxelShape`, etc.) rather than re-implementing them. This requires a locally-extracted server jar and extracted libraries (see `docs/RUNNING.md` or `scripts/setup-env.sh`):

```bash
./gradlew compileJava \
  -PvanillaJar=/path/to/server-26.2.jar \
  -PvanillaLibs=/path/to/libraries
```

Building without `-PvanillaJar` still works (falls back to compiling only `src/main/java` with the standalone `Kestrel.java` scaffold, warning on the console) so a fresh checkout builds without requiring vanilla assets. `VanillaLinkCheck.java` is included in `src/vanilla/java` to verify symbol resolution against the vanilla runtime jar.

## Reproduction Checks and Verification (`checkRepro`)

The `checkRepro` Gradle task executes the reproduction check suite or targeted verification checks against vanilla classes. When `-PvanillaJar` is specified, `checkRepro` is automatically wired into Gradle's `check` task.

### Running the default reproduction suite

```bash
./gradlew checkRepro \
  -PvanillaJar=/path/to/server-26.2.jar \
  -PvanillaLibs=/path/to/libraries
```

The default suite runs nine checks:
1. `dev.kestrel.net.TerrainOverwriteCheck` (Critical Finding C1: Adjacent chunk generation overwriting player edits in SectionStore)
2. `dev.kestrel.loop.EventReentrancyCheck` (High Finding H9: Reentrant plugin events corrupting outer event state)
3. `dev.kestrel.loop.DuplicateScheduleCheck` (High Finding H4: Scheduler duplicate tick ordering)
4. `dev.kestrel.net.ScheduledTickRevisionCheck` (High Finding H3: Mutations lacking authoritative ChunkSender revisions)
5. `dev.kestrel.persist.ReloadCompletionCheck` (High Finding H1: Chunk completion durability and missing FEATURES pass on reload)
6. `dev.kestrel.net.LiveGeneratorCheck` (Live generator terrain pipeline validation)
7. `dev.kestrel.loop.FluidParityCheck` (G11: water/lava flow line-for-line against vanilla oracle fixtures in `harness/corpus/fluid-flow/oracle`)
8. `dev.kestrel.net.StatusPingCheck` (S34: the status response JSON and the legacy ping, diffed against vanilla's own `ServerStatus.CODEC` and decoded back through vanilla's `ClientboundStatusResponsePacket.STREAM_CODEC`)
9. `dev.kestrel.net.ServerPropertiesCheck` (S32: `server.properties` defaults against an independent vanilla `DedicatedServerSettings` write, comment and unknown-key round trip, file authority and the command-line-over-file precedence rule)

### Running specific checks via `-Pcheck`

You can run individual checks, parity gates, or microbenchmarks by name via `-Pcheck=<name>` (or `-PcheckClass=<name>`):

```bash
# Run a specific reproduction check
./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=TerrainOverwriteCheck
./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=ReloadCompletionCheck

# Run parity gates
./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=BatchParityGate
# G10: chunk-activation post-processing against vanilla's own LevelChunk.postProcessGeneration
./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=PostProcessParityCheck

# Run component proof checks or benchmarks
./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=KestrelCollideCheck
./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=RedstoneProofCheck
./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=CollisionBench
```

Multiple checks can be executed in sequence using a comma-separated list:
```bash
./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=EventReentrancyCheck,DuplicateScheduleCheck
```

Command-line arguments can be passed to checks using `-PcheckArgs="..."`.

`checkClassMap` in `core/build.gradle` registers 57 named checks (reproduction gates, parity gates, component proofs and worldgen gates); any of them can be passed to `-Pcheck`, except `PlayShapeCheck` (a helper that `NetCheck` and `ScheduledTickRevisionCheck` call) and `VanillaLinkCheck` (a compile-time proof), which have no `main` method and fail when run by name. Count them with `awk '/def checkClassMap = \[/,/^        \]/' core/build.gradle`: two entries (`StructureReferenceRoundTripCheck`, `StructureStartRoundTripCheck`) wrap their class name onto a continuation line, so a count that expects each pair on one line reports two fewer than the real number.

### Continuous integration (`gates.yml`)

`.github/workflows/gates.yml` is manual-dispatch only (the project pushes branches all day, so it never runs on push or on a schedule). It checks out the repo, sets up Temurin 25, restores a `~/kestrel-run` cache keyed on the pinned server jar's sha1, runs `scripts/setup-env.sh`, asserts the extracted server jar hash, then runs `compileJava` and `checkRepro` under a 30 minute job cap.

Its default set is 10 checks (the workflow's `checks` input overrides it, and `scripts/ci-validate-checks.sh` rejects any name not in `checkClassMap`):

1. `TickLoopCheck`
2. `FluidParityCheck`
3. `EventReentrancyCheck`
4. `DuplicateScheduleCheck`
5. `ScheduledTickRevisionCheck`
6. `RedstoneProofCheck`
7. `AcWireProofCheck`
8. `RedstoneParityCheck`
9. `StatusPingCheck`
10. `ServerPropertiesCheck`

Measured at about 49 seconds of checks and under two minutes for the job with warm caches, on the earlier 8-check set; the last two are file and socket only, so they add about a second each. This is deliberately a different set from `defaultReproChecks` in `core/build.gradle` (9 entries), which stays the local `./gradlew checkRepro` default.

## Layout

```
core/
  src/main/java/dev/kestrel/
    Kestrel.java             # Fallback standalone entry point (no vanilla dependencies)
  src/vanilla/java/dev/kestrel/
    ai/                      # Mob AI sensor scans and POI scheduling proofs
    block/                   # Block models, getters, and collision test fixtures
    blockentity/             # Block entity sleeping registry and tick ordering proofs
    collision/               # Fast-path AABB collision engine and shape box cache
    entity/                  # Entity section storage, id/UUID lookup, EntityStorageCheck (S14 slice 1)
    hopper/                  # Hopper transfer and inventory fast path proofs
    loop/                    # TickLoop scheduler, SectionStore, KestrelLevel
    net/                     # NetServer, ChunkSender, KestrelServer, packet handling
    persist/                 # WorldStore, RegionFile MCA I/O, codecs, snapshotting
    redstone/                # Exact vanilla redstone and Alternate Current engine
    spawn/                   # Mob spawn caps and draw models
    worldgen/                # LiveGenerator, HeadlessNoise, BatchParityGate
    VanillaLinkCheck.java    # Compile-time vanilla linkage verification
  build.gradle               # Build configuration and checkRepro task
  settings.gradle
```
