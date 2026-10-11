# Kestrel harness corpus: block-entity-sleep, stage 0 (build + schedule)
# Step-5 Leg-4 wake-event scenario. Same idle exemplars as
# block-entity-census (each matches its SleepPredicates predicate), plus a
# deterministic wake chain driven by `schedule function`, which fires on the
# server tick loop with no player, no redstone, no command blocks.
#
# Timing: load runs at world spawn before tick 0. Stage 1 fires at 10s
# (200 ticks), then each stage schedules the next 5s later. Gap is wide on
# purpose: wake events must land on settled, post-SkipTicks ticks so the
# differential compares steady-state behavior, not boot transients.
# Chain: be_wake0 (this file) -> be_wake1 -> be_wake2 -> be_wake3 -> be_wake4.
#
# Layout mirrors be_init.mcfunction: build plane y=1 on a stone floor y=0,
# x spread so nothing interacts. Wake-row deltas vs the census corpus:
#   furnace x6: cold with fuel only (slot 1 = coal, slot 0 empty). Idle per
#     the predicate, and the stage-1 insert into slot 0 exercises the
#     FURNACE_SLOT_OTHER-then-work path through the fuel-only hole.
#   shulker x10: never opened until stage 3 (triggerEvent path).
#   sign x8: never edited (no player exists to edit it; stays asleep as the
#     stay-asleep control for the bare-field-write wake shape).
#   catalyst x-14: a stage-2 death event nearby arrives via the game-event
#     listener (CATALYST_GAME_EVENT), the only writer that type has.
# Busy controls (must never sleep): smelting furnace x0, brewing stand x2.

say [kestrel-corpus] building block-entity-sleep scenario

# keep this corpus volume force-loaded: the playerless harness run has
# no player driving chunk visibility, and spawn tickets expire seconds
# after boot. FORCED tickets tick block entities. load.json runs at world
# spawn, so this rect follows the same origin - correct by construction.
forceload add ~-32 ~-32 ~32 ~32

# flat worlds (run-harness -LevelType minecraft:flat) have no structures
# at all, so nothing intrudes. Air-clear stays well above the flat bedrock
# floor (y=-60 would dig through into the void: learned 2026-09-11).
fill ~-34 ~1 ~-6 ~8 ~8 ~6 air

# solid floor at y=0 (build plane y=1 sits on it)
fill ~-34 ~0 ~-6 ~8 ~0 ~6 stone

# NB: bare `gamerule` lines are NOT valid inside a function file (the
# parser rejects them at datapack load, which fails the whole function
# with "Failed to load function" and leaves an empty world: learned
# 2026-09-11). Random ticks do not move block entities, so those lines
# are simply dropped.

# --- idle row: furnace family (cold, empty) ---
setblock ~-30 ~1 ~0 furnace[facing=east]
setblock ~-28 ~1 ~0 blast_furnace[facing=east]
setblock ~-26 ~1 ~0 smoker[facing=east]

# --- idle: brewing stand (empty) ---
setblock ~-24 ~1 ~0 brewing_stand

# --- idle: beehive (no bees) ---
setblock ~-22 ~1 ~0 beehive

# --- idle: campfires (lit-empty, unlit-fresh) ---
setblock ~-20 ~1 ~0 campfire[lit=true]
setblock ~-18 ~1 ~0 campfire[lit=false]

# --- idle: crafter (never triggered) ---
setblock ~-16 ~1 ~0 crafter

# --- idle: sculk catalyst, sensor, shrieker (quiet) ---
setblock ~-14 ~1 ~0 sculk_catalyst
setblock ~-12 ~1 ~0 sculk_sensor
setblock ~-10 ~1 ~0 sculk_shrieker

# --- idle: sign (never edited), hopper (empty) ---
setblock ~-8 ~1 ~0 oak_sign[rotation=8]
setblock ~-4 ~1 ~0 hopper[facing=down]

# --- wake row ---
# furnace x6: fuel only (cold, idle). Stage 1 fills slot 0.
setblock ~6 ~1 ~0 furnace[facing=east]
item replace block ~6 ~1 ~0 container.1 with coal 16
# shulker x10: closed until stage 3.
setblock ~10 ~1 ~0 shulker_box

# --- busy controls (must tick non-idle throughout) ---
setblock ~0 ~1 ~0 furnace[facing=east]
item replace block ~0 ~1 ~0 container.0 with raw_iron 16
item replace block ~0 ~1 ~0 container.1 with coal 16
setblock ~2 ~1 ~0 brewing_stand
item replace block ~2 ~1 ~0 container.3 with nether_wart 1
item replace block ~2 ~1 ~0 container.4 with blaze_powder 1
item replace block ~2 ~1 ~0 container.0 with potion 1
item replace block ~2 ~1 ~0 container.1 with potion 1
item replace block ~2 ~1 ~0 container.2 with potion 1

# start the deterministic wake chain (server tick loop, no player needed)
schedule function kestrel_corpus:be_wake1 10s

say [kestrel-corpus] block-entity-sleep scenario ready
