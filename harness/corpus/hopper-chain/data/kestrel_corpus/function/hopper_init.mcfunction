# Kestrel harness corpus: hopper-chain
# Exercises optimization #2 (hopper logic) across all its idle/active
# states. Built once on datapack load, relative to invocation origin
# (same convention as collision-stress - see harness/corpus/README.md).
#
# Layout (all at y=1, x spread so chains don't interact):
#   Chain A (x -16..-12): 5-hopper horizontal chain, seeded with items,
#     feeding a chest (active transfer every tick window).
#   Chain B (x -8..-4): 5-hopper chain feeding a FULL chest (idle: target
#     full, hoppers keep waking and failing - the sleep target).
#   Chain C (x 0..4): 5 empty hoppers, nothing above, stone below
#     (idle: empty source, the other sleep target).
#   Row D (x 8..12): 5 hoppers disabled by redstone torch power
#     (redstone-disable path; torch on stone above each hopper).
#   Chest E (x 16): double chest (two adjacent chests) fed by 1 hopper
#     (chest-merge attach path: ChestBlock.getContainer double-chest).
#   Item rain (x 0..4, y=5): dropped item entities above chain C
#     (entity-suck path: getItemsAtAndAbove + entityInside).

say [kestrel-corpus] building hopper-chain scenario

# clear the volume first so repeated runs are idempotent
fill ~-20 ~0 ~-6 ~20 ~8 ~6 air

# solid floor
fill ~-20 ~0 ~-6 ~20 ~0 ~6 stone

# --- Chain A: active transfer chain into chest ---
fill ~-16 ~1 ~0 ~-12 ~1 ~0 hopper[facing=east]
setblock ~-11 ~1 ~0 chest[facing=west]
# seed each hopper with a stackable item so transfers actually fire
item replace block ~-16 ~1 ~0 container.0 with cobblestone 16
item replace block ~-15 ~1 ~0 container.0 with cobblestone 16
item replace block ~-14 ~1 ~0 container.0 with cobblestone 16
item replace block ~-13 ~1 ~0 container.0 with cobblestone 16
item replace block ~-12 ~1 ~0 container.0 with cobblestone 16

# --- Chain B: idle, target chest pre-filled full ---
fill ~-8 ~1 ~0 ~-4 ~1 ~0 hopper[facing=east]
setblock ~-3 ~1 ~0 chest[facing=west]
# fill the target chest completely (27 slots) so eject always fails
fill ~-3 ~1 ~0 ~-3 ~1 ~0 chest[facing=west]
item replace block ~-3 ~1 ~0 container.0 with stone 64
item replace block ~-3 ~1 ~0 container.1 with stone 64
item replace block ~-3 ~1 ~0 container.2 with stone 64
# seed the hoppers too (they hold items but cannot eject)
item replace block ~-8 ~1 ~0 container.0 with dirt 16
item replace block ~-7 ~1 ~0 container.0 with dirt 16
item replace block ~-6 ~1 ~0 container.0 with dirt 16
item replace block ~-5 ~1 ~0 container.0 with dirt 16
item replace block ~-4 ~1 ~0 container.0 with dirt 16

# --- Chain C: idle, empty hoppers, nothing above ---
fill ~0 ~1 ~0 ~4 ~1 ~0 hopper[facing=east]

# --- Row D: redstone-disabled hoppers ---
fill ~8 ~1 ~0 ~12 ~1 ~0 hopper[facing=east]
# power each hopper with a torch on stone above (hopper ENABLED=false)
fill ~8 ~2 ~0 ~12 ~2 ~0 stone
setblock ~8 ~3 ~0 redstone_torch
setblock ~9 ~3 ~0 redstone_torch
setblock ~10 ~3 ~0 redstone_torch
setblock ~11 ~3 ~0 redstone_torch
setblock ~12 ~3 ~0 redstone_torch
item replace block ~8 ~1 ~0 container.0 with sand 16
item replace block ~9 ~1 ~0 container.0 with sand 16

# --- Chest E: double chest fed by one hopper ---
setblock ~16 ~1 ~-1 chest[facing=west]
setblock ~16 ~1 ~0 chest[facing=west]
setblock ~16 ~1 ~1 hopper[facing=west]
item replace block ~16 ~1 ~1 container.0 with gravel 16

# --- Item rain above chain C (entity-suck path) ---
summon minecraft:item ~1 ~5 ~0 {Item:{id:"minecraft:cobblestone",count:8}}
summon minecraft:item ~2 ~5 ~0 {Item:{id:"minecraft:dirt",count:8}}
summon minecraft:item ~3 ~5 ~0 {Item:{id:"minecraft:sand",count:8}}

say [kestrel-corpus] hopper-chain scenario ready
