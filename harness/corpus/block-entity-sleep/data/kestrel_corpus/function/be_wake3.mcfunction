# Stage 3 (t=20s): shulker open/close cycle.
# Exercises the SHULKER_TRIGGER_EVENT wake path: container open/close goes
# through startOpen/stopOpen (blockEvent only) and the tick-driving
# animationStatus flips in triggerEvent, which no setChanged observer sees.
# The opener is a summoned allay (grabs nothing, despawns nothing, opens
# the box by container interaction, then is discarded). Chain ends at
# stage 4, which only logs completion.
say [kestrel-corpus] sleep wake stage 3: shulker open/close
summon allay ~10 ~1 ~1 {NoAI:1b}
schedule function kestrel_corpus:be_wake4 5s
