# Kestrel

A Minecraft Java server (26.x) that aims to play exactly like vanilla,
including its quirks, while using a fraction of the memory (about 340 MB
idle and 410-470 MB with a player online, instead of ~1.5 GB) and running
technical and redstone-heavy worlds several times faster. It already
generates chunks as fast as Paper while producing byte-identical vanilla
terrain.

Kestrel is not a fork or patch of the vanilla server, and not a
Paper/Spigot/Fabric plugin. It compiles and runs against Mojang's official
26.2 server jar, which is **not** part of this repository (the setup script
downloads the pinned version and checks its hash). Where that keeps
behavior exact, it calls vanilla's own classes. Elsewhere it replaces the
engine underneath: storage, tick scheduling, memory layout, networking,
chunk sending. Some source files are direct ports of vanilla logic, written
to reproduce its behavior line for line; they say so in their comments.

## What it aims to be

- **Same game.** Same blocks, same redstone, same farms, same chaotic
  edge cases (update-order quirks, 0-tick, locational behavior). Anything
  that works in vanilla should work here, down to the weird stuff technical
  players rely on.
- **Much faster where it hurts.** Collision, hoppers, redstone dust, mob
  AI, block entities, spawn logic, chunk sending: each rebuilt for speed
  while producing identical results. Target whole-server gain: 3-5x.
- **Small-SMP friendly.** Built for owners running on hardware vanilla was
  never designed for, as well as heavy technical servers.
- **Optional speed mode.** Alternate Current redstone (deterministic,
  11-21x on active redstone) as an opt-in flag, default off. Exact by
  default, fast by choice.
- **A home for plugins and content.** A zero-cost Kestrel-native plugin
  API (no Fabric/Forge compat), real content add-ons with a Kestrel
  loader on both sides, and Modrinth distribution as an explicit
  post-launch goal. See `docs/VISION.md`.

## Status

In development and available as an experimental 26.2 server. The core server
(`dev.kestrel.net.KestrelServer`) boots, accepts vanilla clients, persists worlds,
streams chunks, and generates real terrain through vanilla's world-generation classes.

**It is not yet playable as a survival game.** A click places the block of the
item in the clicked hand through vanilla's own placement code and consumes it
(`BlockPlacing`), but there is no way to obtain one yet except by breaking
blocks: crafting, containers, interacting with doors and chests, eating,
bows and mobs are still out of reach even where the engine beneath them already
exists. Today it walks, chats, streams chunks, breaks and places blocks and
ticks the world.

What is left to reach a fully playable vanilla server, in build order, is in
`docs/ROADMAP.md`; per-mechanic status is in `docs/CAPABILITIES.md`.

The repository has two tiers:
1. **Live integrated server engine:** Networking (`NetServer`, `ChunkSender`),
   authoritative 20 TPS simulation loop and level storage (`TickLoop`, `SectionStore`,
   `KestrelLevel`), on-demand live world generation (`LiveGenerator`, `HeadlessNoise`),
   and region-file persistence (`WorldStore`, `RegionFile`).
2. **Component proofs and subsystems:** Algorithmic fast paths for collision,
   hoppers, mob AI, block-entity sleeping, and spawn logic that have been
   proven bit-identical or bounded against vanilla or corpus captures in
   isolated microbenchmarks and proof checks, prior to complete live-loop
   integration. Alternate Current redstone is a proof in this tier; vanilla
   redstone itself has graduated out of it and is live (see the table below).

Correctness is enforced by verification gates, most of them differential
checks against a real vanilla 26.2 server:
- **Stage A gates:** run through Gradle's `checkRepro` task and check
  architectural defects deterministically. All five are fixed and held by
  regression gates: C1 margin overwrites, H1 chunk completion durability,
  H3 single mutation notification path, H4 vanilla scheduler ordering, and
  H9 event reentrancy.
- **Storage and memory gates:** compact section storage passes the W1/W2
  block-storage memory gate (W1 12.79 MiB, five-player projection 40.5 MiB).
  The final connected-player gate measured 338.1-428.6 MiB RSS (p50
  396.7 MiB). Concurrent periodic G1 produced zero periodic Full-GC pauses;
  the older stop-the-world periodic-GC tuning is rejected.
- **Worldgen parity:** the structures-on LARGE gate is exact (1,418/1,418
  chunks, 139,395,072 cells, 0 diffs). Generation is bounded and
  asynchronous, with real generated biome and light packets, a native
  169/169 spawn gate and save/reload durability. Generation is as fast as
  Paper (warm, 4-core VPS: 34.1 vs 33.5 chunks/s and 39.7 vs 37.1 chunks/s
  on two areas) with no opt-in fast mode: FEATURES and light stay serialized
  in vanilla's order, and the extra speed comes from running the chunk-local
  stages on a pool of worker threads and doing less work per chunk. Every
  change passes both strict and live Tier-R gates. See `docs/BENCHMARKS.md`.
- **Continuous integration:** `.github/workflows/gates.yml` proves a branch
  on a GitHub runner, by hand only (`workflow_dispatch`, 30 minute cap). By
  default it runs a fast set of ten checks: `TickLoopCheck`,
  `FluidParityCheck`, `EventReentrancyCheck`, `DuplicateScheduleCheck`,
  `ScheduledTickRevisionCheck`, `RedstoneProofCheck`, `AcWireProofCheck`,
  `RedstoneParityCheck`, `StatusPingCheck` and `ServerPropertiesCheck`. A
  `checks` input overrides the set, validated against `core/build.gradle`'s
  `checkClassMap` by `scripts/ci-validate-checks.sh`.

| Area | Type | State |
|---|---|---|
| Networking & chunk streaming (`NetServer`, `ChunkSender`) | Live Integrated | Working, covered by `NetCheck` |
| Tick loop & level storage (`TickLoop`, `SectionStore`, `KestrelLevel`) | Live Integrated | Working, covered by `TickLoopCheck`, `PersistCheck` |
| Compact section storage | Live Integrated | Done; W1 12.79 MiB, five-player projection 40.5 MiB |
| Terrain generation (`LiveGenerator`, `HeadlessNoise`) | Live Integrated | Done; exact live generation at Paper speed, strict and live Tier-R gates green |
| Persistence & MCA I/O (`WorldStore`, `RegionFile`) | Live Integrated | KEX4 generated completion + exact biome/light metadata durable; KEX1-KEX3 backward reads; covered by `PersistCheck` and `dev.kestrel.net.GeneratedMetadataReloadCheck` (the latter is not registered in `checkClassMap`, so it needs the fully qualified name) |
| Collision fast path (`KestrelCollide`) | Component Proof | Done, 6.6x measured (`KestrelCollideCheck`) |
| Hoppers (`HopperTransfer`) | Component Proof | Done (`HopperTransferCheck`, `HopperStateCheck`) |
| Redstone (`VanillaRedstone`, `WireLogic`, `DiodeLogic`, `PistonLogic`) | Live Integrated | **G12 core and primary components done, exact and live**; 29/29 scenarios against fixtures captured from a real vanilla 26.2 server (`RedstoneParityCheck`). Wire, torches with burnout, repeaters, comparators, observers, levers, buttons, lamps, pistons, the block-event queue and moving pistons. The section 1.3 secondary components are NOT built; see `docs/ROADMAP.md` |
| Water and lava flow (`FluidFlow`) | Live Integrated | **G11 done, exact and live**; 25/25 scenarios against vanilla 26.2 fixtures (`FluidParityCheck`). No drops, fizz events, bubble columns or worldgen springs yet |
| Differential oracle harness (`RedstoneOracle`, `FluidOracle`) | Verification Gate | Boots a real 26.2 dedicated server in-process, steps a scenario one tick at a time, records every cell, queued tick and block event, then replays the same scenario through Kestrel and compares line for line. Fixtures live under `harness/corpus/` |
| Redstone dust internals (`WireConnectionCache`, `WireUpdateFilter`) | Component Proof | Internal cache and filter rules covered by `RedstoneProofCheck`; the end-to-end behaviour is now gated by `RedstoneParityCheck` |
| Alternate Current flag (`AcWireNetwork`, `AcFlag`) | Component Proof | Done, opt-in deterministic engine (`AcWireProofCheck`, `AcFlagCheck`) |
| Mob AI (`BehaviorSchedule`) | Component Proof | Done (`MobAiProofCheck`, `SensorScanBench`) |
| Block-entity sleeping (`SleepSet`) | Component Proof | Done (`SleeperCheck`, `SleepSetCheck`, `SleepLoopCheck`) |
| Spawn logic (`SpawnCaps`) | Component Proof | Done (`SpawnCapsCheck`) |
| Stage A gates (C1, H1, H3, H4, H9) | Verification Gate | All five fixed and held by regression gates in `checkRepro` |
| CI gates (`.github/workflows/gates.yml`) | Verification Gate | Manual dispatch only, 30 minute cap, fast set of 10 checks by default |
| Release builds (`.github/workflows/release.yml`) | Verification Gate | Runs on every push to main that can change the binary, docs-only pushes ignored. Runs the same 10-check fast set first, then publishes a launchable zip to the rolling `latest` release. The zip holds no Mojang binary: its first run downloads the pinned JDK and server jar and verifies both |
| Whole-server memory | System Gate | Idle 337-343 MiB; one player at view distance 2: 412-469 MiB p50 (VPS); connected-player gate green |
| Real biome and light packets | Live Integrated | Done; live route 65/65 chunks with 0 block, biome-wire and light-wire diffs; KEX4 save/reload durable |
| Random ticks (`RandomTicks`) | Live Integrated | Vanilla's random-tick order on vanilla's own ticket system (`TicketStorage`, `SimulationChunkTracker`), covered by `RandomTickParityCheck` with the three light replays at diffs=0. The mob spawning vanilla interleaves with it is not modelled at all (G44) |
| Entities (`EntityWorld`, `EntityTracker`, `KestrelItemEntity`) | Live Integrated | The entity tick phase runs inside the live loop, and tracking plus the entity packet stream are live (`EntityTracker`, `NetCheck`). An item entity can tick, rest on terrain and despawn end to end, but only behind the developer flag `-Dkestrel.dev.dropItemsOnJoin=true` (off unless set), so it stays a `component proof (not live)` in `docs/CAPABILITIES.md`: nothing in normal play creates a non-player entity yet (G19) |
| Players as entities (`KestrelServerPlayer`, `PlayerBodies`, `PlayerOutbound`) | Live Integrated | Each player is one real vanilla `ServerPlayer` owning its body, inventory, menu, attributes and remembered equipment, so players see each other's held items and armour; covered by `PlayerTrackingParityCheck` |
| Inventory and menu (`PlayerInventory`) | Live Integrated | Every player owns vanilla's real `Inventory`, `EntityEquipment` and `InventoryMenu`; the hotbar selection and offhand swap work, and the 46-slot menu state is sent on join (`InventoryParityCheck`, 460 checks over 10 scenarios). Inventory-screen clicks, cursor prediction recovery and close are covered by `InventoryClickParityCheck`; socket clicks and tracked outside drops are covered by `BlockBreakingCheck`. Dropping and pickup are live (G19); inventory persistence, creative inventory and pick-block remain deferred |
| World clock (`WorldTime`) | Live Integrated | Day count and moon phase on a monotonic tick counter, covered by `ClockParityCheck`. Thunder and lightning are still absent |
| Level events | Live Integrated | Block-break particles, torch burnout and anvil sounds reach players within 64 blocks (G66 slice 2b) |
| Parallel ticking | Engine | Planned |
| Plugin API / loader / Modrinth | Ecosystem | Planned, see `docs/VISION.md` |

## Quick start: build and verification

### 0. Prebuilt server

When a release has been published, each push to `main` that can change the
binary updates the rolling `latest` release:
`gh release download latest --pattern kestrel-main.zip`. Extract it and run
`start.sh` on Linux or `start.bat` on Windows. The first run downloads the
pinned JDK and Mojang's pinned 26.2 server jar, verifies both, and launches;
nothing else is required. See `docs/RUNNING.md`.

### 1. Environment setup
Install JDK 25 and extract the vanilla server jar and libraries:
```bash
scripts/setup-env.sh
```
This sets up the toolchain and extracts the required 26.2 server jar and libraries into `$HOME/kestrel-run/vinner`.

### 2. Compiling the server
Compile Kestrel against the extracted vanilla classes:
```bash
cd core && ./gradlew compileJava \
  -PvanillaJar=$HOME/kestrel-run/vinner/META-INF/versions/26.2/server-26.2.jar \
  -PvanillaLibs=$HOME/kestrel-run/vinner
```
*(Or from the repository root: `./core/gradlew -p core compileJava -PvanillaJar=... -PvanillaLibs=...`)*

### 3. Running reproduction checks and parity gates (`checkRepro`)
With no `-Pcheck`, `checkRepro` runs a default set of nine checks and every one
must PASS: `TerrainOverwriteCheck` (C1), `EventReentrancyCheck` (H9),
`DuplicateScheduleCheck` (H4), `ScheduledTickRevisionCheck`,
`ReloadCompletionCheck`, `LiveGeneratorCheck`, `FluidParityCheck`,
`StatusPingCheck` (S34) and `ServerPropertiesCheck` (S32). The
manual CI workflow runs a different, faster default set; see the Status section.
```bash
cd core && ./gradlew checkRepro \
  -PvanillaJar=$HOME/kestrel-run/vinner/META-INF/versions/26.2/server-26.2.jar \
  -PvanillaLibs=$HOME/kestrel-run/vinner
```

Run a specific reproduction check, proof check, or parity gate via `-Pcheck=<name>`:
```bash
# Verify the Critical Finding C1 fix (generation never overwrites player edits)
cd core && ./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=TerrainOverwriteCheck

# Run the 1,024-chunk worldgen parity gate
cd core && ./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... -Pcheck=BatchParityGate

# Run the oracle differential gates for redstone and fluids
cd core && ./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... \
  -Pcheck=RedstoneParityCheck,FluidParityCheck
```

Any of the 65 checks registered in `core/build.gradle`'s `checkClassMap` can be
run by name. A name that is not registered fails the build rather than being
skipped silently.

### 4. Running the live server
See `docs/RUNNING.md` for launch commands, world directory configuration, and tuned G1 GC options.

## Scope

- 26.x only. Old worlds upgrade once in vanilla first.
- First playable target: a small SMP.

## License

Copyright (C) 2026 Fayber. Kestrel is licensed under the GNU General Public
License, version 3 only (GPL-3.0-only); see `LICENSE`. Third-party notices
are in `NOTICE`. The licence covers Kestrel's original code only, not
anything belonging to Mojang or Microsoft.

## Legal

Kestrel is an independent project. It is not an official Minecraft product
and is not approved by or associated with Mojang or Microsoft. "Minecraft"
is a trademark of Mojang AB. This repository does not contain or
redistribute Mojang's server jar; you download it from Mojang yourself, and
its use is subject to the Minecraft EULA.
