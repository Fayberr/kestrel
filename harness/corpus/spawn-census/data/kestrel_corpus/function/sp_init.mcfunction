# Kestrel harness corpus: spawn-census, stage 0 (build + schedule)
# Step-6 measurement leg. Unlike every other corpus in this repo, this one
# needs natural spawning ON (run-harness -SpawnMobs true) and a player-like
# presence so spawn attempts execute past the getNearestPlayer null check.
#
# Player story (no real client exists on a headless run): the corpus summons
# NoAI marker entities that keep the createState entity walk populated and
# stabilize the per-tick mob counts, while the attempt loop's player gates
# are exercised by whatever player presence the harness provides. Two legs:
#   leg A (bit-exact): spawn gamerules OFF, NoAI summons only. Measures the
#     createState walk (cost center a) with zero attempt RNG. Determinism
#     holds by construction (same argument as the mob-ai NoAI corpus).
#   leg B (statistical soak): spawn gamerules ON via -SpawnMobs true.
#     Attempt RNG draws from level.random (seeded); entity RNG is unseeded
#     per entity, so hashes gate statistically, never bit-exact.
# Which leg runs is decided by the run-harness flags, not by this file: the
# build below is identical for both.
#
# Timing: load runs at world spawn before tick 0. Stage 1 fires at 10s
# (200 ticks), then each stage schedules the next 5s later. Gaps are wide on
# purpose: spawn events must land on settled, post-SkipTicks ticks so the
# measurement compares steady-state behavior, not boot transients.
# Chain: sp_init (this file) -> sp_stage1 -> sp_stage2 -> sp_done.
#
# Layout: build plane y=1 on a stone floor y=0, x spread so nothing
# interacts. Flat world expected (run-harness -LevelType minecraft:flat):
# no structures intrude, and the WORLD_SURFACE heightmap reads in
# getRandomPosWithin see a uniform topEmptyY.

say [kestrel-corpus] building spawn-census scenario

# keep this corpus volume force-loaded: the playerless harness run has
# no player driving chunk visibility, and spawn tickets expire seconds
# after boot. FORCED tickets tick the chunk and run the spawner.
# load.json runs at world spawn, so this rect follows the same origin.
forceload add ~-32 ~-32 ~32 ~32

# air-clear stays well above the flat bedrock floor (y=-60 would dig
# through into the void: learned 2026-09-11).
fill ~-34 ~1 ~-6 ~8 ~8 ~6 air

# solid floor at y=0 (build plane y=1 sits on it). Stone floor keeps
# isRedstoneConductor true at the start pos so spawnCategoryForPosition
# proceeds (it returns early on non-conductors: NaturalSpawner.java:166).
fill ~-34 ~0 ~-6 ~8 ~0 ~6 stone

# NB: bare `gamerule` lines are NOT valid inside a function file (the
# parser rejects them at datapack load, which fails the whole function
# with "Failed to load function" and leaves an empty world: learned
# 2026-09-11). Spawn gamerules are set via run-harness flags instead.

# --- NoAI marker entities: populate the createState walk deterministically.
# Same summon shape as the mob-ai corpus (NoAI:1b, no movement, no brain).
# 6 villagers (CREATURE x2 professions irrelevant, counts only) + 3 zombies
# (MONSTER) + 1 wandering-trader-like llama stand-in (CREATURE). Counts are
# fixed, so mobCounts and the global-cap verdicts are stable tick to tick.
summon villager ~-20 ~1 ~0 {NoAI:1b, VillagerData:{profession:"minecraft:farmer"}}
summon villager ~-18 ~1 ~0 {NoAI:1b, VillagerData:{profession:"minecraft:librarian"}}
summon zombie ~-16 ~1 ~0 {NoAI:1b}
summon zombie ~-14 ~1 ~0 {NoAI:1b}
summon zombie ~-12 ~1 ~0 {NoAI:1b}
summon cow ~-10 ~1 ~0 {NoAI:1b}
summon sheep ~-8 ~1 ~0 {NoAI:1b}

# --- busy control: one persistent mob (nametagged, never counts).
# Persistence-required entities are skipped by createState entirely
# (NaturalSpawner.java:80), so this must NEVER move any cap verdict.
summon zombie ~-6 ~1 ~0 {NoAI:1b, PersistenceRequired:1b, CustomName:'"control"'}

# --- dark pocket for monster attempts (leg B): a roofed volume where
# hostile placement rules can pass. Roof at y=6 keeps sky light out.
fill ~6 ~1 ~-4 ~14 ~1 ~4 stone
fill ~6 ~2 ~-4 ~14 ~5 ~4 air
fill ~6 ~6 ~-4 ~14 ~6 ~4 stone
fill ~6 ~2 ~-4 ~6 ~5 ~4 stone
fill ~14 ~2 ~-4 ~14 ~5 ~4 stone
fill ~6 ~2 ~-4 ~14 ~5 ~-4 stone
fill ~6 ~2 ~4 ~14 ~5 ~4 stone

# start the deterministic stage chain (server tick loop, no player needed)
schedule function kestrel_corpus:sp_stage1 10s

say [kestrel-corpus] spawn-census scenario ready
