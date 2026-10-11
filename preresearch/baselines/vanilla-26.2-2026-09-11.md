# Vanilla 26.2 per-subsystem baseline sweep

Date: 2026-09-11
Machine: Bench PC (AMD Ryzen 7 5700X3D, 8-core / 16-thread, ~64GB RAM, Windows 11 Pro), Temurin OpenJDK 25.0.4.1+1 LTS
Server: vanilla 26.2 bundler jar (`C:\Users\bench\kestrel\vanilla-boot-test\server.jar`, 60,894,273 bytes, confirmed bundler format via `net/minecraft/bundler/Main.class` at zip root, NOT the smaller self-extracted nested jar)
Agent: `harness/agent` build `kestrel-harness-agent-0.1.0.jar` + `kestrel-harness-boot.jar`, TickLogger schema_version=4, source hashes verified identical to the git worktree's `harness/agent/src` (sha256, all 6 files match) before trusting any run

**Caveat up front, stated plainly: these numbers are machine-specific to this bench PC (Ryzen 7 5700X3D). They do not transfer to the Pi or any other host. Every number below is read from a real `ticks.log` produced on this sweep; nothing here is estimated or interpolated.**

## Method

- Runner: `harness/tools/run-harness.ps1`, one bounded (`-RunSeconds 60`), headless, fresh-world run per corpus, sequentially (not parallel, to avoid port contention).
- Seed: `4242424242` (fixed, matches existing harness convention) for every corpus.
- `-ViewDistance 3` / `-SkipTicks 5` (see below) for every corpus, `spawn-monsters=false` / `spawn-animals=false` baked into `run-harness.ps1`'s `server.properties` template for every run.
- `-LevelType minecraft:flat` used **only** for `block-entity-census` (per the corpus's own design note: a flat world with `generate-structures=false` keeps trial chambers/ancient cities/mineshaft spawners out of the census). The other four corpora use the default `minecraft:normal` level type, matching all historical harness runs for those corpora.
- `forceload`: `redstone-line`, `mob-ai`, and `block-entity-census` each `forceload add` their own build volume from inside their `minecraft:load` function (playerless runs otherwise lose their loading tickets seconds after boot). `collision-stress` and `hopper-chain` do not forceload; their scenarios sit inside the persistent world-spawn ticket radius and settled/passed determinism checks without it in prior work. No corpus needed a forceload flag passed on the command line; it is baked into the datapack itself.
- Verified before trusting any result: `Select-String -Pattern 'Failed to load function'` against every run's `stdout.log` - **zero hits across all 5 corpora**. Each run's `[kestrel-corpus] ... scenario ready` line was also confirmed present.
- **MSPT proxy**: for tick *k*, `exit(seq k+1).nanos - enter(seq k).nanos` (`TickLogger` writes one `enter` line then the matching `exit` line per tick as consecutive `seq` values; `exit`'s own `tick_count` field reads one higher than its paired `enter` by construction - that's `readTickCount()` observing the post-increment counter, not a mismatch, so pairing is done by `seq` adjacency, not by tick_count equality).
- `-SkipTicks 5`: any pair whose `enter` `tick_count <= 5` is dropped (fresh-world/spawn-area settling window, same convention as `determinism-check.ps1`).
- GC-stall trimming: after SkipTicks, the raw per-tick delta population's p99 threshold is computed, then every value above it is dropped before taking the reported median/p95 (drops 2-9 outlier ticks per corpus out of several hundred - see per-corpus notes). Raw (untrimmed) p99/max are also reported for transparency.
- Reported medians/p95 are always from the full per-tick population of a single real run, never single-run means, per the task's discipline.

## Results summary (trimmed, GC stalls at p99 removed)

| Subsystem | Corpus | Paired ticks (post SkipTicks 5) | Median MSPT | p95 MSPT | Raw p99 / max (untrimmed) |
|---|---|---|---|---|---|
| Collision | collision-stress | 750 | 0.261 ms | 0.838 ms | 3.278 / 11.141 ms |
| Hopper | hopper-chain | 812 | 0.237 ms | 0.573 ms | 1.529 / 32.280 ms |
| Redstone | redstone-line | 319 | 2.837 ms | 7.378 ms | 20.196 / 46.466 ms |
| Mob AI | mob-ai | 109 | 4.305 ms | 14.800 ms | 39.461 / 47.256 ms |
| Block-entity tick | block-entity-census | 892 | 0.509 ms | 1.362 ms | 2.670 / 23.573 ms |

All five numbers come from one 60-second bounded run each (`RunSeconds=60`, actual per-corpus tick coverage below), fixed seed `4242424242`.

## Per-corpus detail

### collision (`collision-stress`)
- `WorldDir`: `C:\Users\bench\kestrel\baseline-sweep\collision-stress`, level type `minecraft:normal`, no forceload.
- 1512 raw log lines, 750 usable enter/exit pairs after SkipTicks 5, tick_count range 6-755.
- Raw: median 0.264 ms, p95 0.873 ms, p99 3.278 ms, max 11.141 ms. Trimmed (8 of 750 ticks dropped above p99): median 0.261 ms, p95 0.838 ms, max 3.273 ms.
- Real wall-clock cycle (enter-to-enter, includes the harness's own post-tick hashing/logging): ~52.6 ms/tick average - close to vanilla's natural 50 ms/tick pacing, i.e. the harness overhead did **not** push this corpus behind schedule (no "Can't keep up!" warning in `stdout.log`).

### hopper (`hopper-chain`)
- `WorldDir`: `C:\Users\bench\kestrel\baseline-sweep\hopper-chain`, level type `minecraft:normal`, no forceload.
- 1636 raw log lines, 812 usable pairs after SkipTicks 5, tick_count range 6-817.
- Raw: median 0.238 ms, p95 0.599 ms, p99 1.529 ms, max 32.280 ms (one clear GC-stall outlier). Trimmed (9 of 812 dropped): median 0.237 ms, p95 0.573 ms, max 1.406 ms.
- Real wall-clock cycle: ~49.9 ms/tick average, at the natural pace, no "Can't keep up!" warning.

### redstone (`redstone-line`)
- `WorldDir`: `C:\Users\bench\kestrel\baseline-sweep\redstone-line`, level type `minecraft:normal`, forceload'd by the corpus's own `rs_init.mcfunction`.
- 651 raw log lines, 319 usable pairs after SkipTicks 5, tick_count range 6-324.
- Raw: median 2.846 ms, p95 10.258 ms, p99 20.196 ms, max 46.466 ms. Trimmed (4 of 319 dropped): median 2.837 ms, p95 7.378 ms, max 17.450 ms.
- Real wall-clock cycle: ~124.9 ms/tick average, well past the 50 ms budget - `stdout.log` shows two "Can't keep up!" warnings (121 ticks behind, then 344 ticks behind). The tick-body cost itself (the MSPT proxy above) stayed in the low single-digit ms; the overrun is the harness's own per-tick world-state hash walk over the forceloaded volume plus power-trace/census logging running *after* the exit-nanos snapshot (so it is correctly excluded from the MSPT numbers, but it does mean this corpus completed far fewer real ticks in 60s than a bare, uninstrumented vanilla server would).

### mob AI (`mob-ai`)
- `WorldDir`: `C:\Users\bench\kestrel\baseline-sweep\mob-ai`, level type `minecraft:normal`, forceload'd by the corpus's own `ai_init.mcfunction`.
- 231 raw log lines, 109 usable pairs after SkipTicks 5, tick_count range 6-114.
- Raw: median 4.358 ms, p95 18.491 ms, p99 39.461 ms, max 47.256 ms. Trimmed (2 of 109 dropped): median 4.305 ms, p95 14.800 ms, max 24.785 ms.
- Real wall-clock cycle: ~352.1 ms/tick average - the worst overrun of the sweep, `stdout.log` shows one "Can't keep up!" warning (148 ticks behind). Same reasoning as redstone-line: the exit-hook's world-state hash/census walk over the forceloaded volume (6 villagers + 3 zombies + 1 golem + 5 items + POI blocks, all NoAI so positions never destabilize, but still all scanned every tick by the hasher) dominates wall clock without polluting the MSPT proxy. This corpus has by far the fewest ticks in the 60s window (109 usable) of the five - later comparisons against this baseline should account for the smaller sample.

### block-entity tick (`block-entity-census`)
- `WorldDir`: `C:\Users\bench\kestrel\baseline-sweep\block-entity-census`, level type `minecraft:flat` (per corpus design), forceload'd by the corpus's own `be_init.mcfunction`.
- 1797 raw log lines, 892 usable pairs after SkipTicks 5, tick_count range 6-897.
- Raw: median 0.512 ms, p95 1.505 ms, p99 2.670 ms, max 23.573 ms. Trimmed (9 of 892 dropped): median 0.509 ms, p95 1.362 ms, max 2.657 ms.
- Real wall-clock cycle: ~49.9 ms/tick average, natural pace, no "Can't keep up!" warning.

## Caveats

1. **Not a shared-tick percentage decomposition.** Each number above is an absolute MSPT reading from an *isolated*, subsystem-focused stress corpus, not a share of one combined real-world tick that runs all subsystems together. Read these as "cost of a tick dominated by this subsystem's stress scenario," not as a summed-to-100% breakdown. Producing an actual combined-tick percentage share would need a corpus that runs all five stress scenarios simultaneously against the same tick - not attempted here.
2. **Harness instrumentation overhead is real but excluded from the MSPT numbers by construction.** `TickLogger.onTick` captures its `nanos` timestamp before doing the expensive exit-phase work (full world-state hash, block-entity census, power trace, log write+flush). That means the reported enter-to-exit MSPT is a clean read of `tickServer()`'s own body cost, but the *wall-clock* cost of running this harness (visible in the "real wall-clock cycle" figures above) is dominated by that post-tick work on the two forceloaded, wider-volume corpora (redstone-line, mob-ai), which both fell behind the 20 TPS schedule ("Can't keep up!" in `stdout.log`, confirmed, not inferred). Ports being benchmarked later with the same harness will inherit the same overhead structure, so before/after comparisons against these baselines stay apples-to-apples as long as they use the same harness build.
3. **Single run per corpus.** This sweep is one 60-second run per corpus, not a double-run determinism-check. Median/p95 are computed from the full per-tick population within that one run (never a single-run mean), per the task's own discipline, but run-to-run variance across separate invocations was not measured here.
4. **GC-stall trimming.** The trimmed columns drop every tick above the raw p99 threshold before recomputing median/p95 (2-9 ticks dropped per corpus). Raw (untrimmed) p99 and max are reported alongside so the dropped tail is visible, not hidden.
5. **Benign pack-metadata warning, not a load failure.** Every run's `stdout.log` shows `[ServerMain/WARN]: Error reading pack metadata, attempting fallback type` / `JsonParseException: ... Not a number: {"min_format":1,"max_format":999}` for the corpus's own `pack.mcmeta` `supported_formats` object, identical across all 5 corpora. The server falls back and loads the pack correctly regardless - confirmed by the `[kestrel-corpus] ... scenario ready` line following it in every log, and by the explicit `Failed to load function` grep coming back empty everywhere. Noted here in case it looks alarming later; it is not a caveat about correctness, just cosmetic.
6. **Tick-count sample sizes differ sharply across corpora** (892 for block-entity-census down to 109 for mob-ai) because of caveat 2, not because of anything about `-RunSeconds 60` itself (same 60s wall-clock budget for every corpus). If a future comparison run wants matched tick counts rather than matched wall-clock time, `-RunSeconds` will need to be scaled up per corpus (mob-ai in particular).
7. **Machine-specific, does not transfer.** All figures are Ryzen 7 5700X3D / Windows 11 / Temurin JDK 25.0.4.1+1. Do not compare these numbers against Pi runs or any other host; only the *shape* of the result (which subsystem's tick body costs more) travels, not the absolute milliseconds.

## Raw artifacts (bench PC)

- `C:\Users\bench\kestrel\baseline-sweep\collision-stress\` (`ticks.log`, `stdout.log`, `stderr.log`)
- `C:\Users\bench\kestrel\baseline-sweep\hopper-chain\`
- `C:\Users\bench\kestrel\baseline-sweep\redstone-line\`
- `C:\Users\bench\kestrel\baseline-sweep\mob-ai\`
- `C:\Users\bench\kestrel\baseline-sweep\block-entity-census\`

Each `WorldDir` was freshly created by `run-harness.ps1` (prior contents removed if any existed) and left in place after the run for inspection; none were deleted as part of this sweep.
