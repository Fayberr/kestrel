# Kestrel harness corpus: collision-stress
# Built once on datapack load (minecraft:load function tag), relative to
# wherever this function is invoked from (typically world origin via a
# command block or /function call - see harness/corpus/README.md).

say [kestrel-corpus] building collision-stress scenario

# clear the volume first so repeated runs are idempotent
fill ~-18 ~-1 ~-18 ~18 ~4 ~18 air

# solid floor
fill ~-18 ~-1 ~-18 ~18 ~-1 ~18 stone

# perimeter wall so entities can't wander out of the stress field
fill ~-18 ~0 ~-18 ~18 ~3 ~-18 cobblestone_wall
fill ~-18 ~0 ~18 ~18 ~3 ~18 cobblestone_wall
fill ~-18 ~0 ~-18 ~-18 ~3 ~18 cobblestone_wall
fill ~18 ~0 ~-18 ~18 ~3 ~18 cobblestone_wall

# dense non-full-cube collision-shape field, striped by block type for
# variety (stairs=2 boxes, bottom slab=1, fence=up to 5, wall=up to 5)
fill ~-17 ~0 ~-17 ~-10 ~0 ~17 oak_stairs[facing=north]
fill ~-17 ~1 ~-17 ~-10 ~1 ~17 oak_slab[type=top]
fill ~-9 ~0 ~-17 ~-2 ~0 ~17 oak_slab[type=bottom]
fill ~-1 ~0 ~-17 ~6 ~0 ~17 oak_fence
fill ~7 ~0 ~-17 ~14 ~0 ~17 cobblestone_wall
fill ~15 ~0 ~-17 ~17 ~0 ~17 snow[layers=2]

# a small pocket of the highest-box-count blockstates found by
# BlockShapeAudit (hoppers: 10-13 boxes, powder snow cauldron: 15) so
# the corpus actually exercises the tail the audit flagged, not just
# the common case
fill ~-2 ~1 ~-2 ~2 ~1 ~2 hopper[facing=down]
setblock ~0 ~1 ~0 powder_snow_cauldron

# moving entities spread across the field to exercise entity-vs-block
# collision every tick against every stripe above
summon minecraft:zombie ~12 ~1 ~-9 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-16 ~1 ~-11 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-14 ~1 ~-11 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-10 ~1 ~6 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-1 ~1 ~-15 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-15 ~1 ~-15 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~6 ~1 ~14 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~13 ~1 ~-7 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-11 ~1 ~-5 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-9 ~1 ~-16 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~16 ~1 ~15 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-1 ~1 ~-12 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~13 ~1 ~-12 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-11 ~1 ~16 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-14 ~1 ~1 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-12 ~1 ~3 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~14 ~1 ~6 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~1 ~1 ~-1 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-4 ~1 ~13 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-15 ~1 ~16 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-12 ~1 ~-16 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~5 ~1 ~-7 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~14 ~1 ~8 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-6 ~1 ~-13 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-12 ~1 ~15 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-6 ~1 ~-12 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-3 ~1 ~-7 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-15 ~1 ~6 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-9 ~1 ~-7 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~3 ~1 ~13 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-14 ~1 ~10 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~1 ~1 ~-14 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-5 ~1 ~1 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-7 ~1 ~3 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-7 ~1 ~13 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~-14 ~1 ~8 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~6 ~1 ~15 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~5 ~1 ~13 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~14 ~1 ~-14 {NoAI:0b,PersistenceRequired:1b}
summon minecraft:zombie ~14 ~1 ~-6 {NoAI:0b,PersistenceRequired:1b}

say [kestrel-corpus] collision-stress scenario ready
