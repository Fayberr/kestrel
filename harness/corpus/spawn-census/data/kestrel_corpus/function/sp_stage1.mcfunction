# spawn-census stage 1 (t=10s): entity-count step.
# Adds 4 more zombies (MONSTER +4) so the global-cap input visibly steps
# between steady states. Ticks 200+ are post-SkipTicks settled ground.
say [kestrel-corpus] spawn-census stage 1: adding 4 zombies
summon zombie ~-4 ~1 ~2 {NoAI:1b}
summon zombie ~-2 ~1 ~2 {NoAI:1b}
summon zombie ~0 ~1 ~2 {NoAI:1b}
summon zombie ~2 ~1 ~2 {NoAI:1b}
schedule function kestrel_corpus:sp_stage2 5s
say [kestrel-corpus] spawn-census stage 1 done
