# Stage 1 (t=10s): fuel-only furnace gets its input slot filled.
# Exercises the FURNACE_SLOT_OTHER wake path through the fuel-only hole:
# slot 1 was filled at build with no setChanged and no cookingTotalTime
# refresh, and this insert must wake the sleeper and start real smelting.
# Chain continues to stage 2.
say [kestrel-corpus] sleep wake stage 1: furnace input fill
item replace block ~6 ~1 ~0 container.0 with raw_iron 16
schedule function kestrel_corpus:be_wake2 5s
