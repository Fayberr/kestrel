# Mica Findings: SCMC Parallel Ticking Prototype

Source: https://github.com/oberhauserg/Mica ,  cloned to `$HOME/projects/mc-light/preresearch/Mica`
Date read: 2026-09-10. All file refs below are patch files under `paper-server/patches/features/` unless noted.

## 1. Repo health

- Last commit: **2026-03-12** ("Mica: Configs, water fixes, etc", f05d2a9). Active burst 2026-03-07..12, then silent (~6 months stale at time of reading).
- Upstream base: **Paper on MC 1.21.11**, JDK 21, Paperweight 2.0.0-beta.19 (`gradle.properties`: `mcVersion=1.21.11`).
- Issues: **disabled** on the repo (`gh issue list` → "repository has disabled issues"). PR list empty. No community feedback channel visible.
- Build: did not run (heavy Paperweight build, needs JDK 21 + network + long patch-apply). CI (`.github/workflows/build.yml`) runs `./gradlew applyPatches` then `createMojmapBundlerJar`, and the last commits are literally "fix the build I hope" / "Automating build" / "Mica: Configs, water fixes, etc", which suggests the build was green-ish at HEAD but the author was fighting it. Assess as **probably builds, not verified, single-author experimental**.
- Authorship: single author (Grant Oberhauser), ~6 Mica patch files, ~5.6k added lines total. No tests for Mica code paths (only Paper's pre-existing Bukkit unit tests).

## 2. Architecture map

New package: `net.minecraft.server.level.mica/` (all new files, in patch 0033 unless noted):

| File | Role (one line) |
|---|---|
| `WorldSnapshot.java` | Immutable tick-start view: copies chunk-section *array references* per entity-ticking chunk, reads fall through to snapshot sections or live world |
| `TrackedOverlay.java` | Per-thread read-write layer: overlay-first reads, overlay-only writes, provenance maps (write→op, read→ops), read/write counters, `touchedPositions` cascade set (added 0034) |
| `OperationId.java` | Record `(OperationType, sourcePos long, threadIndex)` identifying one tick op for provenance |
| `OperationType.java` | Enum: `BLOCK_TICK, FLUID_TICK, RANDOM_TICK, ENTITY_TICK, BLOCK_ENTITY_TICK, CHUNK_TICK, MOB_SPAWN` |
| `TickOperation.java` | Op wrappers (`BlockTickOp`, `EntityTickOp`, …) + per-chunk tick-record list used for serial re-run |
| `OperationExecutor.java` | Functional interface `execute(op, overlay)`; the early generic engine (superseded per-subsystem by 0038) |
| `MicaTickEngine.java` | Original generic SCMC orchestrator (ForkJoinPool, batch dispatch, merge, serial re-run, apply); largely superseded by per-subsystem drivers in `ServerChunkCache`/`ServerLevel` |
| `OverlayMerger.java` | Conflict detection: write-write collisions + write-vs-read + touched-vs-read, then BFS over provenance graph to taint transitively affected ops (0037 adds pre-built position indexes) |
| `TickGrouper.java` | Union-Find over position overlap → connected components; clean groups keep results, tainted groups re-run (added 0036, optimized 0037) |
| `DeferredEffects.java` | Per-thread buffers: entity spawns, sync Bukkit events, generic `Runnable` tasks (block-entity remove, POI), deferred `scheduleTick` calls; `apply(level)` replays on main thread |
| `MicaConfig.java` | `mica.yml` config: enabled, threadCount (auto = cores−2, min 2), per-subsystem toggles, log level, stats interval (added 0038, wired in `Services.java`) |

Integration points (modified vanilla/Paper files):

| File | Role |
|---|---|
| `world/level/Level.java` | ThreadLocal `micaOverlay`/`micaDeferredStatic`/`micaNeighborUpdater`/`micaWireHandler`; intercepts in `setBlock`, `getBlockState`, `getFluidState`, `neighborShapeChanged`, `notifyAndUpdatePhysics` (packet/POI/event suppression) |
| `world/level/chunk/LevelChunk.java` | `setBlockState` overlay intercept: old-state read + write go to overlay, heightmap/lighting/POI/block-entity work skipped or deferred; `onPlace` still runs (ordering fix, see §7) |
| `server/level/ServerChunkCache.java` | Random-tick parallel driver (`tickChunk` fan-out across overlays, merge, re-run, apply, stats logging) |
| `server/level/ServerLevel.java` | Entity-tick + scheduled block/fluid tick parallel drivers, `micaRunScheduledTicks`, entity spawn deferral in `addEntity`, `currentlyTickingEntity` ThreadLocal |
| `world/ticks/LevelTicks.java` | `collectForParallelDispatch`/`finishParallelDispatch`, `markTickExecuted`, deferred `scheduleTick` tagging |
| `world/entity/Entity.java` | Position/BB/blockpos/inBlockState snapshots + self-vs-other read routing |
| Moonrise `EntityLookup.java` | Chunk-move and entity-remove deferral during parallel entity ticks |
| `server/level/ChunkMap.java` | Null-guard for stale chunk data from deferred moves |
| Moonrise `TickThread.java` | `micaParallelPhase` ThreadLocal; `isTickThread()` returns true during parallel phase (applied source, not a patch) |
| `org/spigotmc/AsyncCatcher.java` | Blanket bypass for all `ForkJoinWorkerThread` (applied source) |
| `plugin/manager/PaperEventManager.java` | Sync events from worker threads buffered to `DeferredEffects`, never executed inline (applied source) |
| Alternate Current `LevelHelper.java` | `setWireState` overlay intercept (bypasses `LevelChunk.setBlockState`, needed its own) |
| `RedStoneWireBlock.java` + Paper `RedstoneWireTurbo.java` | Shared `shouldSignal` boolean → `ThreadLocal<Boolean>` (patch 0035) |
| `world/level/redstone/CollectingNeighborUpdater.java` | Per-thread instances (0034) |
| `server/Services.java` | Loads `mica.yml` (0038) |

## 3. Intercept surface (biggest risk: completeness)

Mechanism is **not mixins**: Paper is source-patched, so interception = direct edits + `ThreadLocal` routing. For our from-scratch server the equivalent is wrapper/decorator or explicit phase gates at the same call sites. Full enumeration:

**Reads (overlay-first, snapshot fallback):**
- `Level.getBlockState(pos)` → `overlay.getBlockState` (checks overlay writes, else `WorldSnapshot`)
- `Level.getFluidState(pos)` → derived from overlay block state
- `WorldSnapshot.getBlockState` reads snapshotted `LevelChunkSection` arrays by chunk key; unknown chunk → falls back to **live world** via `getChunkAtIfLoadedImmediately` (a hole: not snapshot-isolated, but bounded to out-of-radius reads)
- `WorldSnapshot.getBlockEntity` → **live world, NOT snapshotted** (acknowledged hole; low risk for random/entity ticks, blocks future block-entity ticking)
- `TrackedOverlay.getBlockStateIfLoaded/getFluidIfLoaded` → same overlay path
- Alternate Current path: `LevelHelper.setWireState` writes directly to section palettes, bypassing `LevelChunk.setBlockState` → Mica added a **separate intercept in `LevelHelper`** (this is the canonical example of why intercept completeness is the core risk: at least one known bypass existed and needed its own patch)
- Entity cross-reads: `Entity.getX/Y/Z`, `position()`, `getBoundingBox()`, `blockPosition()`, `getBlockX/Y/Z`, `getX/Y/Z(scale)`, `getEyeY()`, `getInBlockState()` ,  each branches on `micaSnapshotValid && currentlyTickingEntity != this` (~5ns overhead claimed). Self-reads are live. Note the refactor hazard: internal vanilla code using fields (`this.position`, `this.bb`, `this.blockPosition`) directly had to be rewritten to go through getters.
- Vanilla redstone evaluator: `RedStoneWireBlock.shouldSignal` shared boolean → `ThreadLocal<Boolean>` (cross-thread signal reads caused wrong power + oscillation). Same for Paper's `RedstoneWireTurbo`.

**Writes (overlay-only, never touch live world during compute):**
- `Level.setBlock` → overlay write + neighbor updates via per-thread `CollectingNeighborUpdater` + shape updates recurse through overlay + POI deferred as task
- `LevelChunk.setBlockState` → overlay write; skips heightmaps, lighting, section-emptiness, block-entity create/remove (deferred via `DeferredEffects.addDeferredTask`); `onPlace` **still executes** (critical ordering: an earlier version early-returned before `onPlace`, which silently killed water/lava continuation ticks)
- `Level.neighborShapeChanged` → routed to thread-local updater
- `ServerLevel.addEntity` → buffered as `(entity, spawnReason)` in `DeferredEffects`
- `LevelTicks.scheduleTick` during parallel phase → buffered with source `OperationId`; replayed only for clean ops (tainted ops' deferred ticks discarded, serial re-run regenerates them)
- `EntityLookup` chunk-slice moves + entity removals → deferred as tasks (removal deferral fixed a `Reference2ObjectOpenHashMap` crash on chunk unload)

**NOT intercepted (known gaps, from HARDENING.md):**
- `playSound`, `sendParticles`, `levelEvent`, `gameEvent`, `broadcastEntityEvent`, `broadcastDamageEvent` ,  only `sendBlockUpdated`/`setBlocksDirty` are suppressed. Concurrent iteration of player tracking lists = crash/data-race risk.
- Profiler (`Profiler.get()`) ,  no protection, non-thread-safe accumulation.
- `Mth.createInsecureUUID()` shared non-thread-safe random → observed UUID collisions dropping entities (see §5).
- Shared `world.random` passed straight into `randomTick` on workers ,  no per-thread RNG (see §5).

## 4. Side-effect handling

| Side effect | Treatment |
|---|---|
| Block state writes | Contained in overlay; committed to live world in apply phase (`micaRawSetBlock` path / `applyToWorld`) |
| Packets (`sendBlockUpdated`, `setBlocksDirty`) | Suppressed when `micaOverlay != null`; other packet paths NOT suppressed (gap) |
| `BlockPhysicsEvent` | Suppressed during parallel (`notifyAndUpdatePhysics` checks `micaOverlay`) |
| Sync Bukkit events (BlockSpread, BlockGrow, BlockRedstone, …) | Buffered in `DeferredEffects.deferredEvents`, replayed via `callEvent` on main thread in `apply()`. **Cancellable events cannot actually cancel**: buffering means the block change already happened; plugin cancellation is silently ignored (Medium severity per author) |
| Entity spawns (incl. item drops from leaf decay) | Buffered `(entity, reason)`, spawned via `addFreshEntity` on main thread |
| Entity chunk moves / removals | Deferred tasks; stale-chunk failures swallowed, self-correct in 1–2 ticks (brief invisibility, sensing misses, off-by-one despawn distance) |
| Block-entity create/remove | Deferred `Runnable`s to main thread |
| Heightmap / lighting / POI / section-emptiness | Skipped during compute, applied at commit |
| `scheduleTick` during compute | Tagged with source op; clean-path replay + same-tick cascade drain loop; tainted-path discard + serial regeneration |
| Neighbor/shape cascades | Run inline on workers through per-thread updaters/overlays (reads/writes tracked, incl. `touchedPositions`) |

## 5. RNG / event ordering

**No deterministic ordering mechanism exists.** Findings:
- Workers receive the shared `world.random` directly (`state.randomTick(world, pos, world.random)`). Concurrent draws from one `RandomSource` = nondeterministic consumption order across threads. No per-thread seeding, no op-indexed RNG.
- `Mth.createInsecureUUID()` (shared, non-thread-safe) produced **duplicate UUIDs for two falling-gravel entities on different threads**, silently dropping one. Author's proposed fix (not implemented): `UUID.randomUUID()` or per-thread random during parallel phase.
- Merge commit order: `cleanWrites` is a `Long2ObjectOpenHashMap` iterated for apply ,  commit order is hash order, not tick order. For non-conflicting positions this is semantics-preserving; for any missed-conflict case it silently picks a winner.
- Serial re-run preserves original op order, so the fallback path is deterministic; the parallel fast path is not bit-reproducible run-to-run.
- No loot-table, mob-spawn, or redstone evaluation ordering work beyond the ThreadLocal `shouldSignal` fix.

## 6. Reported numbers (all UNVERIFIED, self-reported, no independent benchmark)

Sources: `README.md` ("How To" log excerpts, comparison table), `HARDENING.md` "Performance Data" + "Stress Testing". No vs-Paper A/B flame graphs exist (listed as future work). Issues are disabled so no corroborating discussion.

- Random ticks: "98M reads, 8 threads, ~36ms, ~4x faster" (HARDENING.md:229). README log sample: `441 chunks | 98700246 reads, 80 writes | 0 conflicts` with `compute 36.14ms`.
- Entity ticks: 800 villagers/4 threads/5ms ~4x; 1,800/4 threads/17ms ~4x; 3,900/4 threads/53ms ~4x; 3,900/8 threads/20ms ~8x; 5,100/8 threads/34ms ~8x (HARDENING.md:230-234, README:46). README comparison table claims Paper does "~200 villagers/tick".
- Scheduled ticks: redstone circuit <1ms "correctness validated"; 5000+ fluid ticks (chunk gen) <2ms w/ parallel group re-run; merge of 5000 ticks "0.23% of tick time, 7x faster after index optimization".
- Zero-conflict claims: 9.5M reads at `random_tick_speed 100000` stable, 0% conflicts; 10 players / 2700 chunks / 750 entities, zero conflicts, ~1ms entity + ~0.6ms random.
- Correctness spot-checks: 4-bit adder 5+3=8 verified; redstone clocks on all three evaluators; 4 bots clustered at 5-block spacing for 5 min, zero conflicts.

Treat scaling numbers as author-logged, single-machine, unspecified-hardware results with no control runs.

## 7. Top 5 lessons for our implementation

1. **Steal: intercept at the lowest choke point that still runs game logic above it.** Mica's winning move (patch 0034) was moving the write intercept down to `LevelChunk.setBlockState` so `onPlace`/`onRemove`/cascades execute naturally on workers. The pre-0034 design suppressed too much and broke water flow; the post-0034 bug was suppressing `onPlace` via early-return ordering. Rule: contain *effects* (packets, lighting, heightmaps, events), never skip *logic*.
2. **Steal: provenance + Union-Find with a fast path.** Full SSI-style conflict detection (write-write, write-read, touched-read) plus transitive taint BFS, then Union-Find grouping so independent tainted components re-run in parallel. And the fast path matters most: skip merge/grouper entirely when tainted set is empty (their measured 99%+ case). Pre-index position→readers maps; the naive scan version cost 86% of scheduled-tick time.
3. **Avoid: ThreadLocal-gated behavior scattered across game code.** Mica's `micaOverlay`/`micaDeferredStatic`/`micaNeighborUpdater`/`micaWireHandler`/`micaParallelPhase`/`currentlyTickingEntity` checks are smeared over ~15 files. Every new subsystem needs auditing for direct field access (`this.position` vs `position()`) and palette-bypass writes (`LevelHelper`). For a fresh server, prefer an explicit phase/capability object passed through tick context over ambient ThreadLocals ,  same interceptions, one place to audit.
4. **Avoid: deferring cancellable events.** Buffer-and-replay breaks the Bukkit contract (cancellation ignored). Either run event-raising ops on the main thread, or gate commit on event outcome (speculative commit). Decide this before parallelizing anything that fires cancellable events.
5. **Mica got wrong / left unfinished:** shared `world.random` on workers (nondeterminism + real UUID-collision entity loss); unsuppressed sound/particle/game-event packet paths; un-snapshotted block entities; no profiler isolation; block-entity ticking and pistons still serial (the hard half of the problem is untouched); no bit-reproducibility story; zero plugin-compat testing. Also process-level: issues disabled, no A/B benchmarks, 6 months stale ,  adopt the design, not the dependency.

## 8. Portability notes (Paper 1.21.11 → from-scratch 26.2 server)

Paper-only constructs and replacements:

- **Patch-file architecture**: Mica edits deobfuscated Mojang+Paper+Moonrise sources in place. We have no upstream to patch; the same interceptions become explicit seams: a `WorldAccess` interface (read/write/neighbor/schedule/spawn/event/sound) with a `SnapshotOverlay` implementation selected per phase. The `LevelHelper.setWireState` bypass lesson becomes: route ALL palette writes through the seam, no direct section access from game logic.
- **Moonrise chunk system** (`EntityLookup`, `ChunkEntitySlices`, `TickThread`, `moonrise$getEntityTickingChunks`): Paper-specific async chunk infra. Replace with our own chunk registry + explicit ticking-set iteration; snapshot = reference-copy of whatever our section storage is (same O(chunks) trick works with any palette-compressed sections).
- **Bukkit/CraftBukkit layer** (`PaperEventManager`, `BlockPhysicsEvent`, `CreatureSpawnEvent.SpawnReason`, `captureBlockStates`, `CraftBlockData`): does not exist for us. The deferred-events problem collapses into our own event/dispatch design ,  decide cancellability semantics up front (lesson 4).
- **`CollectingNeighborUpdater` / `ExperimentalRedstoneUtils` / redstone evaluators (Vanilla/Eigencraft/Alternate Current)**: Mojang+Paper redstone stack. Our redstone equivalent needs the same treatment Mica converged on: per-worker evaluator state (the `shouldSignal` ThreadLocal generalizes to "no shared mutable evaluator state") and tracked cascade reads (`touchedPositions`).
- **Light engine / heightmap / POI / `ChunkMap` tracker**: Paper/Mojang specifics, but the pattern ports directly: skip derived-data maintenance during compute, rebuild/apply at commit; defer tracker updates.
- **Fastutil collections** (`Long2ObjectOpenHashMap`, `LongOpenHashSet`): implementation detail, keep or replace freely.
- **ForkJoinPool + ThreadLocal worker routing**: portable as-is, but see lesson 3 ,  prefer explicit context over ThreadLocals.
- **Version skew (1.21.11 → 26.2)**: chunk section layout, `LevelChunk.setBlockState` internals, `LevelTicks` scheduling, entity movement, and the entire redstone block set may differ. Mica's value is the SCMC design + the enumerated intercept checklist (§3), not any patch hunk ,  expect every call site to need re-derivation against 26.2 mappings.
