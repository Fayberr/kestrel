# Stage 2 (t=15s): death event next to the quiet sculk catalyst.
# Exercises the CATALYST_GAME_EVENT wake path: the CatalystListener is the
# only writer that type has (addCursors + direct setBlock(PULSE), never
# setChanged). A summoned ravager killed by a summoned iron golem drops XP
# deterministically with no player and no RNG-sensitive combat loop to
# disturb the tick hash beyond the event itself. Chain continues to stage 3.
say [kestrel-corpus] sleep wake stage 2: catalyst death event
summon iron_golem ~-14 ~1 ~2 {NoAI:1b}
summon ravager ~-14 ~1 ~-2 {NoAI:1b}
schedule function kestrel_corpus:be_wake3 5s
