# Kestrel harness corpus: redstone-line
# Exercises optimization #3 (redstone dust) across settled + active states.
# Built once on datapack load, relative to invocation origin (same
# convention as the other corpora - see harness/corpus/README.md).
#
# Layout (all at y=1 unless noted):
#   Line S (z=-4, x -20..20): 41-wire settled line, lever-powered at x=-20
#     (the no-op suppression target: settled network, idle after power-on).
#   Clock C (z=0, x -6..6): repeater clock driving a 13-wire line
#     (active worst case: power changes every few ticks).
#   Variants V (z=4): dot wire (single, isolated), cross wire (4-way),
#     L-corner, T-junction (connection-state coverage: NONE/SIDE/UP).
#   Torch T (z=8, x 0..10): redstone-torch-powered line (external-signal
#     path via getBlockSignal, not wire-to-wire).
#   Steps P (z=12, x 0..6): wire climbing 3 steps up (UP-side connections,
#     conductor above/below checks in getIncomingWireSignal).

say [kestrel-corpus] building redstone-line scenario

# Force-load the corpus volume: playerless harness runs unload the spawn area
# seconds after boot (boot-time loading tickets expire, no player tickets
# replace them), which empties updatingChunkMap and blinds the tick hash AND
# the power trace (found 2026-09-10: holders 575 at tick 1, 0 by tick 65).
# FORCED tickets persist with no players and keep chunks ticking, so redstone
# actually fires. Volume x -24..24, z -8..16 sits in chunks x -2..1, z -1..1;
# forceload rect covers that plus margin. Relative to invocation origin, same
# convention as the build below (load.json runs at world spawn... which is
# NOT 0,0, so this rect follows the same origin - correct by construction).
forceload add ~-32 ~-32 ~32 ~32

# clear the volume first so repeated runs are idempotent
fill ~-24 ~0 ~-8 ~24 ~6 ~16 air

# solid floor
fill ~-24 ~0 ~-8 ~24 ~0 ~16 stone

# --- Line S: long settled line, lever power at west end ---
fill ~-20 ~1 ~-4 ~20 ~1 ~-4 redstone_wire
setblock ~-21 ~1 ~-4 lever[face=floor,facing=east,powered=false]
setblock ~-21 ~1 ~-4 lever[face=floor,facing=east,powered=true]

# --- Clock C: repeater clock into a short line ---
setblock ~-8 ~1 ~0 repeater[delay=1,facing=east,locked=false,powered=false]
setblock ~-7 ~1 ~0 repeater[delay=1,facing=west,locked=false,powered=false]
fill ~-6 ~1 ~0 ~6 ~1 ~0 redstone_wire

# --- Variants V: connection-state shapes ---
setblock ~-4 ~1 ~4 redstone_wire
fill ~0 ~1 ~4 ~2 ~1 ~4 redstone_wire
setblock ~1 ~1 ~3 redstone_wire
setblock ~1 ~1 ~5 redstone_wire
fill ~6 ~1 ~4 ~8 ~1 ~4 redstone_wire
setblock ~7 ~1 ~3 redstone_wire
fill ~12 ~1 ~4 ~14 ~1 ~4 redstone_wire
setblock ~13 ~1 ~3 redstone_wire
setblock ~13 ~2 ~3 stone
setblock ~13 ~3 ~3 redstone_wire

# --- Torch T: torch-powered line ---
setblock ~0 ~1 ~8 redstone_torch
fill ~1 ~1 ~8 ~10 ~1 ~8 redstone_wire

# --- Steps P: wire climbing upward ---
setblock ~0 ~1 ~12 redstone_wire
setblock ~1 ~1 ~12 stone
setblock ~1 ~2 ~12 redstone_wire
setblock ~2 ~2 ~12 stone
setblock ~2 ~3 ~12 redstone_wire
setblock ~3 ~3 ~12 stone
setblock ~3 ~4 ~12 redstone_wire
fill ~4 ~4 ~12 ~6 ~4 ~12 redstone_wire

say [kestrel-corpus] redstone-line scenario ready
