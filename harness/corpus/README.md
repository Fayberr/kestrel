# harness/corpus/

Fixed differential-testing fixtures. Most are test-world scenarios: run the
same scenario against vanilla and against Kestrel, and compare `harness/`'s
per-tick world-state hash sequence. Two (`fluid-flow`, `redstone`) are
recorded oracle captures instead, replayed cell-for-cell by the `core/`
parity gates rather than diffed by tick hash.

Each datapack scenario is plain vanilla (zero external tooling - just
`/fill`, `/summon`, a `minecraft:load` function tag) so it runs unmodified
on real vanilla with no extra steps, and needs no new infrastructure to
build or maintain as MC versions change.

Run-harness flags scenarios need (all pass through `run-harness.ps1`,
which forwards them to `determinism-check.ps1` too):

- `-LevelType 'minecraft:flat'` for a clean volume (writes
  `generate-structures=false`; the server reads `generator-settings`, not
  `level-generator-settings`, and level-type colons are backslash-escaped
  in server.properties). Needed by block-entity-census (trial chambers /
  mineshaft spawners tick inside a normal world) and spawn-census.
- `-SpawnMobs` / `-SpawnAnimals` to flip the spawn gamerules for
  spawn-census leg B.
- Always `Select-String 'Failed to load function'` the run's stdout.log
  before believing a corpus result: one bad line (bad blockstate, bare
  `gamerule` line) fails the whole function file and the server boots an
  EMPTY world looking perfectly healthy.

## collision-stress

Built for `preresearch/collision-path-analysis.md` optimization #1
(entity AABB / collision path). Builds a walled 36x36 field on load,
striped with non-full-cube collision shapes (stairs, slabs, fences, walls,
snow layers) plus a pocket of the highest box-count blockstates found by
`BlockShapeAudit` (hoppers, a powder snow cauldron), and spawns 40 zombies
with AI across it so entity-vs-block collision resolution runs against
every stripe, every tick.

Usage: pass `-DatapackDir harness\corpus\collision-stress` to
`harness/tools/run-harness.ps1`. The datapack is copied into the fresh
world's `world/datapacks/` before first launch, so its `minecraft:load`
function tag fires automatically on world creation - no manual `/reload`
or `/function` call needed.

Determinism note: AI-enabled mobs draw from unseeded entity RNG, so this
corpus passes `determinism-check.ps1` only with `-SkipTicks 5`
(fresh-world spawn-area settling in `tick_count<=1`).

## hopper-chain

Built for `preresearch/hopper-path-analysis.md` optimization #2 (hopper
logic). Five hopper groups covering every tick state: active transfer
chain (A, seeded, feeding chest), idle full-target chain (B, chest
pre-filled so eject always fails - the sleep target), idle empty chain (C,
nothing above - the other sleep target), redstone-disabled row (D, torch
powered), double-chest attach (E, chest-merge path), plus dropped item
entities above C (entity-suck path).

Requires TickLogger schema_version=3+ (container inventory in the hash -
v2 added it, v3 kept it through the walk change), or item movement is
invisible to the differential comparison.

Usage: pass `-DatapackDir harness\corpus\hopper-chain` to
`harness/tools/run-harness.ps1`, same as collision-stress.

## redstone-line

Built for `preresearch/redstone-path-analysis.md` optimization #3
(redstone dust). Settled 41-wire lever line (S, the no-op suppression
target), repeater clock into a short line (C, active worst case),
connection-shape variants (V: dot/cross/L/T/step-up), torch-powered line
(T, external-signal path), climbing steps (P, UP-side connections).

POWER is a blockstate so power changes are already hash-visible with the
v1 block hash - no hasher change needed. Also intended for the
experimental-evaluator comparison (same corpus under both evaluators).
Settled-circuit note: the AC proof (`ac-proof.log`) replays propagation
over live wire data on this corpus; DIVERGE on mismatch.

Usage: pass `-DatapackDir harness\corpus\redstone-line` to
`harness/tools/run-harness.ps1`, same as the other corpora.

## block-entity-census

Built for `preresearch/block-entity-tick-analysis.md` item 2 (population
+ idle fraction per sleep-candidate type). One parked exemplar of every
type in the sleep set, each in its idle configuration (empty furnaces x3,
empty brewing stand, bee-less hive, lit-empty + unlit-fresh campfires,
never-triggered crafter, quiet sculk catalyst/sensor/shrieker, unedited
sign, unopened shulker, empty hopper), plus busy controls that must NOT
count idle (smelting furnace, brewing stand, mid-craft crafter).

Needs `-LevelType 'minecraft:flat'` (a normal world ticks nearby trial
chambers / ancient cities / mineshaft spawners inside the census).

Requires the census sibling: TickLogger schema_version=4+ writes
`block-entity-census.log` next to `ticks.log` (per-tick
`tick,tickers,name=total/idle,...` with variable cells in sorted order -
no fixed columns, no `other` bucket). Summarize with
`harness/tools/census-summarize.ps1`, which also diffs two runs
tick-by-tick like `determinism-check.ps1` does for the tick log.
Reference result: two flat-world runs, `-SkipTicks 5`, CENSUS_COMPARE PASS
(1578 ticks), 87.5% idle overall.

Usage: pass `-DatapackDir harness\corpus\block-entity-census` to
`harness/tools/run-harness.ps1` with `-LevelType 'minecraft:flat'`.

## block-entity-sleep

Step-5 Leg-4 wake-event scenario. Same idle exemplars as
block-entity-census (each matches its SleepPredicates predicate), plus a
deterministic wake chain driven by `schedule function` (`be_wake0` through
`be_wake4`, 5s apart starting at 10s): fires on the server tick loop with
no player, no redstone, no command blocks. Gaps are wide on purpose - wake
events must land on settled, post-SkipTicks ticks so the differential
compares steady-state behavior, not boot transients.

Usage: same flags as block-entity-census (flat level type).

## mob-ai

Built for `preresearch/mob-ai-path-analysis.md` optimization #4 (mob
AI/brain/sensors/POI). Village POI blocks (beds/HOME, workstations/JOB_SITE,
bell/MEETING_POINT), 6 NoAI villagers (2 professioned, 1 baby, 3 unemployed),
3 NoAI zombies (hostile-scan targets), 1 NoAI golem, 5 dropped items.
All entities NoAI: sensors read positions + block state (deterministic),
live-AI movement RNG (unseeded) excluded by design - see the analysis
"Harness note". Force-loads its own volume (playerless-unload fix).

Determinism note: NoAI by construction holds bit-exact (same argument as
leg A below). A live-AI variant would FAIL the check by construction
(`Entity.random` is unseeded) - gate it statistically, never bit-exact.

Usage: pass `-DatapackDir harness\corpus\mob-ai` to
`harness/tools/run-harness.ps1`, same as the other corpora.

## spawn-census

Step-6 measurement corpus. Unlike every other corpus here, this one needs
natural spawning ON (`run-harness -SpawnMobs`) and a player-like presence
so spawn attempts execute past the `getNearestPlayer` null check.

Player story (no real client exists on a headless run): the corpus summons
NoAI marker entities that keep the `createState` entity walk populated and
stabilize the per-tick mob counts, while the attempt loop's player gates
are exercised by whatever player presence the harness provides
(`load-bot.py --behavior idle` AFK role - see WI-13 spawn-census recipe).
Two legs, decided by the run-harness flags, not by the datapack (the build
is identical for both):

- Leg A (bit-exact): spawn gamerules OFF, NoAI summons only. Measures the
  `createState` walk with zero attempt RNG. Determinism holds by
  construction (same argument as the mob-ai NoAI corpus).
- Leg B (statistical soak): spawn gamerules ON via `-SpawnMobs`.
  Attempt RNG draws from level.random (seeded); entity RNG is unseeded per
  entity, so hashes gate statistically, never bit-exact.

Stages (`sp_init` build + schedule, `sp_stage1`/`sp_stage2` 5s apart from
10s, `sp_done`): gaps are wide so windows land on settled ticks.

Proving leg B uses `spawn-proof.log`, not the tick hash: SpawnProof
recomputes the global cap (`count < max*chunks/289`) same-tick against
vanilla's verdict (leg-D result: zero DIVERGE over 1074 live ticks with
the bot). Charges are always 0/0.0 here (no MobSpawnCost) - stability
signal only. Watch for the `spawnableChunkCount` 0-to-289 flip after bot
login (counter only fills once player-nearby chunks exist): baselines
comparing pre/post login must account for it. Roof the marker row or run
at night - the persistent nametag zombie burns in daylight on flat plains.

Result rows (docs/BENCHMARKS.md §3): spawn OFF 0.648 ms median /
1.414 ms p95 (createState walk with zero attempts); +1 AFK bot
1.772 ms / 3.165 ms (attempt loop + proximity scan, +1.1 ms delta).

## fluid-flow

The G11 fluid corpus. `oracle/` holds 25 vanilla 26.2 recordings, one per
scenario in `FluidScenarios.all()`, captured by `FluidOracle`.
`core/`'s `FluidParityCheck` replays each scenario on a fresh `TickLoop` and
compares box cells per tick, the pending fluid-tick queue (including
`TickPriority` and `subTickOrder`) and lava's random draws line for line.
Fixtures only, like `redstone` below: there is no datapack here, so it is not
run through `run-harness.ps1`.

Covers flat spread and falloff, slope finding and hole ties, infinite water,
basalt and obsidian/cobblestone conversion, waterlogging and slabs, water and
lava washing redstone components, and the two fixes applied after the first
landing (`campfire_doused`, `comparator_washed`).

## redstone

The G12 redstone corpus. `oracle/` holds 29 vanilla 26.2 recordings, one per
scenario in `RedstoneScenarios.all()`, captured by `RedstoneOracle`.
`core/`'s `RedstoneParityCheck` loads each fixture's `S` snapshot (the
post-settle vanilla-reachable state), replays tick 0 onward on a fresh
`TickLoop`, and compares the recording line for line: cell deltas, the block
events fired, and the scheduled block-tick queue.

Covers wire and signal falloff, torches including burnout, repeaters,
comparators, observers, levers, buttons, lamps, and the pistons: the
block-event queue, the structure resolver, the moving placeholders and
quasi-connectivity, plus the two coverage-gap fixtures added after slice 5.
This directory's `redstone/README.md` documents the fixture format, the
capture command and each scenario's spec match.
