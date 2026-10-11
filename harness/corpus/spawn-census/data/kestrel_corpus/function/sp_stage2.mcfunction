# spawn-census stage 2 (t=15s): second entity-count step.
# Adds 2 cows (CREATURE +2). After this stage the population is fixed for
# the rest of the run: the tail measures a stable createState walk.
say [kestrel-corpus] spawn-census stage 2: adding 2 cows
summon cow ~4 ~1 ~2 {NoAI:1b}
summon cow ~6 ~1 ~2 {NoAI:1b}
schedule function kestrel_corpus:sp_done 5s
say [kestrel-corpus] spawn-census stage 2 done
