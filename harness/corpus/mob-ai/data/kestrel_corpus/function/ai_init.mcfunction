# Kestrel harness corpus: mob-ai
# Exercises optimization #4 (mob AI/brain/sensors/POI) across its sensor
# paths. Built once on datapack load, relative to invocation origin (same
# convention as the other corpora - see harness/corpus/README.md).
#
# Corpus discipline (see preresearch/mob-ai-path-analysis.md "Harness note"):
# ALL entities spawn with NoAI:1b. Live AI movement draws from the unseeded
# per-entity RNG, so a live-AI corpus can never gate bit-exactly; NoAI
# scripted entities keep positions + block state deterministic, which is
# exactly what the SENSOR/POI paths read. A separate live-AI soak (not this
# corpus) covers behavior statistically.
#
# Layout (all at y=1 unless noted):
#   Village V (x -14..14, z -8..8): beds (HOME POI), workstation blocks
#     (JOB_SITE POI: fletching table, blast furnace, lectern...), bell
#     (MEETING_POINT), 6 NoAI villagers (2 adults w/ professions, 1 baby,
#     3 unemployed) standing on the floor (sensors scan them every tick).
#   Hostiles H (x -14..-10, z 12): 3 NoAI zombies (VILLAGER_HOSTILES path:
#     PANIC activity trigger scan, positions fixed so the scan is stable).
#   Golem G (x 10, z 12): 1 NoAI iron golem (GOLEM_DETECTED path).
#   Items I (x 0, z 12): 5 dropped items (NEAREST_ITEMS path).
#   Job-site volume J: SecondaryPoiSensor's 9x5x9 scan around each villager
#     reads the workstation blocks above - covered by V's workstations.

say [kestrel-corpus] building mob-ai scenario

# force-load the corpus volume (same playerless-unload reason as
# redstone-line: boot tickets expire, FORCED tickets keep chunks ticking)
forceload add ~-32 ~-32 ~32 ~32

# clear the volume first so repeated runs are idempotent
fill ~-20 ~0 ~-6 ~20 ~8 ~18 air

# solid floor
fill ~-20 ~0 ~-6 ~20 ~0 ~18 stone

# --- Village V: POI blocks ---
# beds (HOME): 4 white beds, head+foot
setblock ~-12 ~1 ~-6 white_bed[facing=south,occupied=false,part=head]
setblock ~-12 ~1 ~-7 white_bed[facing=south,occupied=false,part=foot]
setblock ~-8 ~1 ~-6 white_bed[facing=south,occupied=false,part=head]
setblock ~-8 ~1 ~-7 white_bed[facing=south,occupied=false,part=foot]
# workstations (JOB_SITE): one per profession below
setblock ~-4 ~1 ~-6 fletching_table
setblock ~0 ~1 ~-6 blast_furnace[facing=north]
setblock ~4 ~1 ~-6 lectern[facing=north]
setblock ~8 ~1 ~-6 composter
# bell (MEETING_POINT)
setblock ~0 ~1 ~0 bell[attachment=floor,facing=north]
setblock ~0 ~0 ~0 stone

# --- Villagers (NoAI: sensors scan, nothing moves) ---
summon villager ~-12 ~1 ~-2 {NoAI:1b,Age:0,VillagerData:{profession:"minecraft:fletcher",level:1,type:"minecraft:plains"}}
summon villager ~-8 ~1 ~-2 {NoAI:1b,Age:0,VillagerData:{profession:"minecraft:armorer",level:1,type:"minecraft:plains"}}
summon villager ~-4 ~1 ~-2 {NoAI:1b,Age:-24000,VillagerData:{profession:"minecraft:none",level:1,type:"minecraft:plains"}}
summon villager ~0 ~1 ~-2 {NoAI:1b,Age:0,VillagerData:{profession:"minecraft:none",level:1,type:"minecraft:plains"}}
summon villager ~4 ~1 ~-2 {NoAI:1b,Age:0,VillagerData:{profession:"minecraft:none",level:1,type:"minecraft:plains"}}
summon villager ~8 ~1 ~-2 {NoAI:1b,Age:0,VillagerData:{profession:"minecraft:librarian",level:1,type:"minecraft:plains"}}

# --- Hostiles H: NoAI zombies (hostile-scan targets, fixed positions) ---
summon zombie ~-14 ~1 ~12 {NoAI:1b}
summon zombie ~-12 ~1 ~12 {NoAI:1b}
summon zombie ~-10 ~1 ~12 {NoAI:1b}

# --- Golem G: NoAI iron golem ---
summon iron_golem ~10 ~1 ~12 {NoAI:1b}

# --- Items I: dropped cobblestone (item-sensor targets) ---
summon item ~-2 ~2 ~12 {Item:{id:"minecraft:cobblestone",count:3}}
summon item ~0 ~2 ~12 {Item:{id:"minecraft:cobblestone",count:3}}
summon item ~2 ~2 ~12 {Item:{id:"minecraft:cobblestone",count:3}}
summon item ~4 ~2 ~12 {Item:{id:"minecraft:cobblestone",count:3}}
summon item ~6 ~2 ~12 {Item:{id:"minecraft:cobblestone",count:3}}

say [kestrel-corpus] mob-ai scenario ready
