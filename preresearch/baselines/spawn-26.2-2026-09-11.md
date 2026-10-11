# Step-6 measurement leg: spawn-calculation baselines

Date: 2026-09-11
Machine: Bench PC (AMD Ryzen 7 5700X3D, Windows 11 Pro), Temurin OpenJDK 25.0.4.1+1 LTS
Server: vanilla 26.2 bundler jar (`C:\Users\bench\kestrel\vanilla-boot-test\server.jar`), TickLogger schema_version=4
Corpus: `harness/corpus/spawn-census/` (new this leg), flat world, fixed seed `4242424242`, `-ViewDistance 3`, `-SkipTicks 5`

**Caveat up front, stated plainly: these numbers are machine-specific to this
Bench PC. They do not transfer to the Pi or any other host. Every number below
is read from a real `ticks.log`; nothing here is estimated or interpolated.**

## Method

- Runner: `harness/tools/run-harness.ps1` with new `-SpawnMobs`/`-SpawnAnimals`
  switches (default OFF, matching all historical runs; leg B passes both).
- Leg A (bit-exact, spawn OFF, 60s): measures the `createState` walk (cost
  center a) with zero attempt RNG. NoAI summons only (2 villagers, 3 zombies,
  1 cow, 1 sheep, 1 persistent nametag control), plus schedule-driven stage
  adds at 10s (+4 zombies) and 15s (+2 cows).
- Leg B (statistical soak, spawn ON, 90s): same corpus with
  `-SpawnMobs -SpawnAnimals`. Attempt RNG draws from seeded level.random;
  entity RNG is unseeded per entity, so hashes gate statistically, never
  bit-exact.
- Verified before trusting: `Failed to load function` grep empty on both
  runs; every `[kestrel-corpus]` stage line present in order (build, ready,
  stage 1, stage 1 done, stage 2, stage 2 done, chain complete).
- MSPT proxy: `exit(seq k+1).nanos - enter(seq k).nanos`, SkipTicks 5,
  p99-trimmed median/p95 with raw values alongside (baseline-doc discipline).

## Results (trimmed, GC stalls at p99 removed)

| Window | Ticks | Median MSPT | p95 MSPT | Raw p99 / max |
|---|---|---|---|---|
| Leg A stable (ticks 400-782, spawn OFF) | 383 | 0.648 ms | 1.414 ms | raw med 0.654 / p95 1.429 |
| Leg B active (ticks 400-1198, spawn ON, churning) | 799 | 0.605 ms | 1.304 ms | raw med 0.608 / p95 1.348 |
| Leg B settled (ticks 1199+, spawn ON, hash-stable) | 263 | 0.053 ms | 0.103 ms | raw med 0.053 / p95 0.106 |
| Leg C pre-bot (ticks 400-600, spawn ON, no player) | 200 | 0.635 ms | 1.190 ms | raw med 0.637 / p95 1.199, max 2.702 |
| Leg C with-bot (ticks 700-2114, spawn ON, 1 AFK player) | 1400 | 1.772 ms | 3.165 ms | raw med 1.775 / p95 3.318, max 18.283 |

## Reading

1. **The attempt loop costs nothing measurable on this corpus.** Leg B active
   (spawn ON, hash churning on 799 ticks) is not slower than leg A stable
   (spawn OFF): 0.605 vs 0.648 ms median. Playerless runs early-out every try
   at the null `getNearestPlayer` check (`EntityGetter.java:75`, linear scan
   over zero players returns null), so the per-try chain past the wander
   draws never executes. Cost centers (d) and (e) are confirmed negligible
   here; (e) shuffle over a handful of spawn-candidate chunks is noise.
2. **The settled leg-B window (0.053 ms) is NOT a spawn win.** At tick 1199
   the world hash locks onto one value for 264 unbroken ticks: the entity
   set stopped changing (no players, so nothing despawns, nothing new spawns
   once caps fill, and the NoAI markers stand still). The tick body has
   almost nothing left to do. Comparing 0.053 against 0.648 as "spawn
   overhead" would be backwards: the difference is entity-set activity, not
   spawn computation.
3. **The honest baseline for Leg 2 ports is leg A stable: 0.648/1.414 ms.**
   That window holds the `createState` walk over a fixed ~15-entity set with
   zero attempt work. Ports of (b)/(c)/cap arithmetic prove against it.
4. **Playerless measurement cannot size the attempt loop.** Trap 5 in the
   analysis doc holds: sizing (d) needs a player story (protocol bot or
   equivalent) so tries execute past the player gates. Until then, the
   attempt-loop port stays transcribed-but-unmeasured, which is exactly what
   `SpawnDrawModel` is for.
5. **Leg C (2026-09-12): one AFK bot turns the attempt loop on.**
   The bot script (a stdlib-only protocol client, since superseded by `harness/tools/load-bot.py`; ground-truth
   packet IDs from codec round-trips) logged in (entity id 19) and stayed
   the full 75s window (BOT_EXIT=0, `Failed to load function`-clean, full
   stage chain). Pre-bot window (400-600) reproduces leg A/B at 0.635 ms;
   with-bot (700-2114, 1400 ticks, hash churning every tick, zero repeats)
   runs at **1.772/3.165 ms**. Delta vs the no-player windows: **~1.1 ms
   median attributable to a live player presence** (attempt execution +
   player ticking + chunk visibility, not separated here). The join window
   (600-700) spikes to 77.9 ms max (login + initial chunk send, excluded
   from the with-bot window). This sizes the prize: the RNG-free algebra
   ports (b)/(c)/caps attack the sub-ms floor; the attempt loop itself is
   now measurable and stays transcribed until a port replays its draw
   sequence exactly.

## Raw artifacts (bench PC)

- `C:\Users\bench\kestrel\spawn-legA\` (1568-line ticks.log, ticks 6-782)
- `C:\Users\bench\kestrel\spawn-legB\` (2926-line ticks.log, ticks 6-1199+)
- `C:\Users\bench\kestrel\spawn-legC\` (4233-line ticks.log, ticks 6-2114, bot ticks ~620+)

## Corpus notes

- New `harness/corpus/spawn-census/`: `sp_init` (build + forceload +
  schedule), `sp_stage1` (+4 zombies at 10s), `sp_stage2` (+2 cows at 15s),
  `sp_done` (terminus marker at 20s, no cleanup: the population IS the
  measurement). Stone floor (keeps `isRedstoneConductor` true so
  `spawnCategoryForPosition` proceeds), dark pocket for monster attempts,
  persistent nametag control (must never move any cap verdict).
- `run-harness.ps1` gains `-SpawnMobs`/`-SpawnAnimals` switches; defaults
  unchanged (OFF) so all historical corpora are unaffected.
- Gotcha found live: the control zombie burned to death (daylight, flat
  plains, no roof over the marker row). It died at 23:26:05 in leg B,
  which is inside the active window, not the settled tail, so no window
  above is polluted; but a re-run of this corpus should roof the marker
  row or run at night. Recorded, not fixed in this leg.
