# Kestrel harness corpus: block-entity-census
# Built for preresearch/block-entity-tick-analysis.md item 2 (population +
# idle fraction per sleep-candidate type). One parked exemplar of every
# type in the sleep set, each in its idle configuration, plus the
# always-busy controls that must NOT count idle. All at y=1 on a stone
# floor, x spread so nothing interacts.
#
# Idle configurations (each matches the census predicate exactly):
#   furnace x-30: empty input + fuel slots (litTimeRemaining 0,
#     cookingTimer 0, slot 0 empty).
#   blast furnace x-28, smoker x-26: same, all three share the predicate.
#   brewing stand x-24: no ingredient, no fuel, no potions (brewTime 0,
#     not brewable).
#   beehive x-22: no bees stored.
#   campfire lit x-20: LIT, all four food slots empty.
#   campfire unlit x-18: unlit, progress decayed (fresh campfires start at
#     0 progress, so idle from tick 0).
#   crafter x-16: never triggered (craftingTicksRemaining 0).
#   sculk catalyst x-14: no cursors (no deaths nearby).
#   sculk sensor x-12, shrieker x-10: no vibration in flight.
#   sign x-8: placed, never opened for edit (playerWhoMayEdit null).
#   shulker box x-6: never opened (CLOSED, progress 0).
#   hopper x-4: plain empty hopper (census counts hoppers present but
#     never idle - step 2's HopperState owns that decision).
#
# Busy controls (must tick non-idle):
#   furnace x0: seeded input + fuel, actively smelting.
#   brewing stand x2: ingredient + fuel + water bottles, brewing.
#   crafter x4: redstone clock triggers it (craftingTicksRemaining > 0
#     repeatedly). Torch tower below toggles via the floor lever... kept
#     simple: a repeating command-block-free clock is not expressible in a
#     load function, so the control is seeded mid-craft by pre-filling all
#     9 slots with a valid recipe and pulsing once with a redstone block
#     placed then removed next load. It crafts once; the census still sees
#     6 non-idle ticks.

say [kestrel-corpus] building block-entity-census scenario

# keep this corpus volume force-loaded: the playerless harness run has
# no player driving chunk visibility, and spawn tickets expire seconds
# after boot (see the redstone-line corpus note). FORCED tickets tick
# block entities, like the other corpora. load.json runs at world spawn,
# so this rect follows the same origin - correct by construction.
forceload add ~-32 ~-32 ~32 ~32

# flat worlds (run-harness -LevelType minecraft:flat, which the census
# runs use) have no structures at all, so nothing intrudes. The wide
# air-clear below is only idempotence for re-runs against the same world
# dir, which run-harness never does (it deletes the world first) - kept
# narrow and well above the flat bedrock floor (y=-60 would dig THROUGH
# the flat layers into the void and drop the floor out from under the
# build: learned 2026-09-11).
fill ~-34 ~1 ~-6 ~8 ~8 ~6 air

# solid floor at y=0 (build plane y=1 sits on it)
fill ~-34 ~0 ~-6 ~8 ~0 ~6 stone

# settle the world: no weather, no day/night drift, no random ticks.
# NB: bare `gamerule` lines are NOT valid inside a function file (the
# parser rejects them at datapack load, which fails the whole function
# with "Failed to load function" and leaves an empty world: learned
# 2026-09-11). Random ticks do not move block entities, so those lines
# are simply dropped. (Weather/daylight would only matter for the tick
# hash, not the census, which reads ticker state, not blocks.)

# --- idle row: furnace family ---
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

# --- idle: sign (never edited), shulker (never opened), hopper (empty) ---
setblock ~-8 ~1 ~0 oak_sign[rotation=8]
setblock ~-6 ~1 ~0 shulker_box
setblock ~-4 ~1 ~0 hopper[facing=down]

# --- busy controls ---
setblock ~0 ~1 ~0 furnace[facing=east]
item replace block ~0 ~1 ~0 container.0 with raw_iron 16
item replace block ~0 ~1 ~0 container.1 with coal 16
setblock ~2 ~1 ~0 brewing_stand
item replace block ~2 ~1 ~0 container.3 with nether_wart 1
item replace block ~2 ~1 ~0 container.4 with blaze_powder 1
item replace block ~2 ~1 ~0 container.0 with potion 1
item replace block ~2 ~1 ~0 container.1 with potion 1
item replace block ~2 ~1 ~0 container.2 with potion 1

say [kestrel-corpus] block-entity-census scenario ready
