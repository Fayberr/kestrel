# camp2's strict aborts: three signatures the live gate does not reach

Status (2026-09-24): class A fixed (w05 block entities in `98bca9e`, w03 camel game
time in `6cffddf`), class B open, class C fixed in `e6a51b4` and confirmed by a full
w03 live rerun. Recorded from the bench PC logs (`C:\Users\bench\bench\camp2`).
camp2 ran six windows (w00 to w05, budget 3.6h, 20:27 to 23:54 on 2026-09-23). The
strict gate aborted on four of them; the live gate PASSED five of six. The verdicts were
unread until 2026-09-24 06:40, so the ledger previously showed only w00 and w01.

## The four strict aborts (verbatim)

```
w00  java.io.IOException: cannot resolve structure-reference source at -18,21 index=1
w03  java.lang.AssertionError: structure-call after content mismatch index=1 target=19,1
w04  java.io.IOException: cannot linearize intra-FEATURES INITIALIZE_LIGHT:
       light-relevant block change origin=16681,-2644 chunk=16680,-2644
       pos=266895,87,-42293 before=Block{minecraft:air}
       after=Block{minecraft:acacia_leaves}[distance=5,persistent=false,waterlogged=false]
w05  java.lang.AssertionError: structure-call before content mismatch index=1
       target=231793,484165
```

w00 has its own note: `preresearch/camp2-w00-unresolved-reference-source.md`.

## Class A: structure-call content mismatches (w03, w05)

Both are at `index=1`, i.e. the second call of that origin's captured state.
They are not one defect: the checks compare content around structure calls,
and the first unequal field decides the failure.

For w03, the live block snapshots are exact at every FEATURES boundary. A
bounded diff of the two entity lists in `w03.strict.log` at origin `19,1`
shows one unequal span: a camel's `LastPoseTick` is `566L` in the capture and
`0L` in Kestrel. The entity count, types, positions and all other serialized
fields match. camp2's trace does not carry the world time for this
structure call, so the gate cannot reproduce the camel's time state from it.
Keep the assertion strict. Do not normalize it away.

Mechanism (bytecode of `Camel` in server-26.2, read 2026-09-24):
`finalizeSpawn` calls `resetLastPoseChangeTickToFullStand(level.getGameTime())`,
which stores `max(0, gameTime - 52 - 1)` into `LAST_POSE_CHANGE_TICK`, and
`addAdditionalSaveData` writes it as `LastPoseTick`. So `566L` means the
capturing server was at game time 619 when the village placed the camel.
Kestrel reads 0 because every headless level stubs game time to `0L`:
`HeadlessEntityServerLevel.getGameTime()`, its `LevelData.getGameTime()`, and
the `getGameTime` case of the `HeadlessNoise` level proxy. Any 0 to 53 game time
gives 0, which is why the startup windows that place camels early never hit it.
A `javap` scan of all 1131 `net.minecraft.world.entity` classes finds
`Camel.finalizeSpawn` as the only `finalizeSpawn`, constructor or
`addAdditionalSaveData` that reads `getGameTime` directly, so the class is
narrow (desert-village camels).

This is a real generator input, not a gate artefact: in production the
generator must see the server's live game time when a structure spawns
entities. No camp1 to camp3 trace file records game time (checked every
header of a camp1 trace).

Fixed in `6cffddf` (2026-09-24):

- `WorldgenClock` hangs off `HeadlessNoise.Driver`, forked worker drivers
  share it, and the region proxy plus both `HeadlessEntityServerLevel` game-time
  reads return it. The default stays 0.
- `KestrelServer` makes the clock follow `TickLoop.gameTime()`.
- The capture agent writes `structure-call-times.tsv`: origin, index, and the
  game time at placement enter and exit.
- The strict replay pins the clock to the enter value for each call when the
  file is present and prints `STRUCTURE_CALL_TIME_TRACE rows=N straddles=M`.
  A straddle is a call during which the server ticked, so the camel may have
  read either value.
- Traces without the file replay exactly as before.
- Worldgen scheduled ticks cannot move: `ProtoChunkTicks.schedule` stores
  every tick with delay 0 (bytecode), and full chunks use a blackhole
  container.

Verification:

- VPS camp1 w00 strict still passes without the file (4324 chunks, 1927/1927
  structure calls).
- A new-agent smoke capture (seed `-5186516791654869197`, 16x16 startup at
  `16,-30`) passes strict 324/324 with 433 time rows and 4 straddles. Its
  camel spawned at game time 18, so it does not test the fix itself.
- The positive test is camp3 w03 with a two-row times file giving the camel
  call time 264, inferred from Vanilla's `LastPoseTick` 211. The call that
  used to abort now prints `STRUCTURE_CALL_MATCH origin=24,-22 index=2`.
  Because the time is inferred, this tests the mechanism, not the capture.
- camp1 to camp3 traces cannot supply the time, so a legacy window with a
  late camel stays unverifiable by the strict gate. From camp4 on, the
  capture records it.

For w05, origin `231793,484165` at ENTER event 7061 has equal empty entity
lists. At block position `(3708700,-6,7746640)`, Vanilla still has a pending
block entity while Kestrel has already promoted it to a real block entity.
The real and pending position sets are otherwise empty, and the expected real
block-state set is empty. The pending tag is a simple-dungeon chest with a
loot-table seed. The earlier pass at origin `231793,484164` queried it at event
4947, making the object real. A captured reload of its target chunk at event
6708, status CARVERS, then serialized it back to pending NBT in Vanilla.
Kestrel retained the real object, so the gate aborts at event 7061. This is a
missing block-entity half of the ProtoChunk reload, not a missing structure
start. The fix round-trips both real and pending tags on every captured
ProtoChunk reload (`HeadlessNoise.roundTripBlockEntities`, clearing through
`ProtoChunk.removeBlockEntity` because `getBlockEntityNbts()` is an
unmodifiable view). The full camp2 w05 strict replay on the candidate classes
finished on the bench PC at 12:10 local on 2026-09-24 with `TIER_R VERDICT: PASS`,
4314/4314 chunks exact, 424083456 cells, 0 diffs.

## Class B: the gate refuses to linearize an intra-FEATURES INITIALIZE_LIGHT (w04)

Inside the INITIALIZE_LIGHT interval there is a light-relevant block change (air to
`acacia_leaves`, distance 5, so the block attenuates). The completion and the block change
must both be placed inside that interval and their relative order is not recoverable from
the trace, so no placement is an order vanilla could have produced and the gate refuses
instead of guessing. This is the honest-refusal sibling of camp1 w07: w07 picked the
before-edge and could prove it legal from `events.tsv` alone (the three west neighbours
complete FEATURES at 8216/8218/8224, before T's ENTER at 8229), while a block change inside
the interval makes both edges illegal. The fix direction is the exact per-placement timeline
replay, not a cheaper edge choice; the hook inventory is in
`preresearch/w07-intra-features-light-ordering.md`.

### What w04's timeline pins, and what it does not (2026-09-24)

camp2 w04's trace carries `intra-features-timeline.tsv`, read on the bench PC with
`harness/tools/intra-pass-timeline.py <trace> 16681 -2644`. Origin O = `16681,-2644` makes 53 feature placements
(F001 to F053). Target T = `16680,-2644` is its west neighbour, and T's own
FEATURES completed at event 7370, before O's ENTER at 7405.

- T's `INITIALIZE_LIGHT_TASK` ran at timeline order 388345, before O's pass
  starts at 390185. That task runs `initializeLightSources()`, so T's sky
  sources were computed before any of O's writes.
- T's `INITIALIZE_LIGHT_PRE`, the light thread's section scan, ran at 390223.
  It was part of a burst of 32 PRE tasks (390195 to 390258) that fell between
  F005 (step 3) and F006 (step 6). In that gap O's pass places its structures,
  and O has one: a `JigsawStructure` started in O itself (box x 266810 to
  266931, y 51 to 122). The block change W = `266895,87,-42293` (air to
  `acacia_leaves`) lies inside that box. So W came either from the structure,
  in the same gap as the PRE burst, or from a step-9 tree (F039 to F052).
  Structure placement has no timeline rows, and the ENTER and COMPLETE
  snapshots cannot tell the two apart.
- T's completion (the POST task, then the status change to INITIALIZE_LIGHT)
  is stamped inside O's pass (afterEventSeq 7405, lighting order 4324). The
  timeline has no row for it, so its position among O's placements is unknown.

What the order changes: T's section 5 was already non-empty, because the gate's
section check passed. So W's only light effect is `ProtoChunk.setBlockState`'s
tracking once T's status is INITIALIZE_LIGHT: `skyLightSources.update` and
`checkBlock`. If W came before T's status change, Vanilla did neither, and T's
sky sources in W's column stayed as computed at order 388345, without the
leaves. The before-pass placement tracks W instead. The trace cannot say which
happened, so the refusal is correct for this trace. The live gate matched all
4318 chunks with its own placement, so any difference that exists is in light
data, which neither gate compares, not in blocks.

Pinning it needs capture changes, so it would apply from camp5 on:

- timeline rows for the status change (`ProtoChunk.setPersistedStatus` or the
  POST task);
- rows for the light thread's `runLightUpdates`, the point where registered
  state becomes visible to worldgen reads;
- rows around structure placement per piece, to bracket W.

The replay would then run each INITIALIZE_LIGHT sub-step (sources, section
scan, visibility, status) at its recorded position. It would fail closed only
when a sub-step overlaps a placement that changes the target's light-relevant
blocks.

## Class C: camp2 w03's live diff (4032 cells, 30 chunks)

Diagnosed 2026-09-24: the live log reports zero diffs across all 4865
FEATURES windows and 4304240640 checked cells. All 93 printed final-state
witnesses are `vanilla=cave_air kestrel=air`, three per affected chunk.
`SectionStore.putCompact` used to drop any section whose states all counted
as air, losing `cave_air` identities after the exact FEATURES boundary. The
same publication defect caused camp1 w07's final three cells and is fixed in
`e6a51b4`. The full w03 live rerun on the reload-fix candidate classes (which
include `e6a51b4`) finished on the bench PC at 12:24 local on 2026-09-24:
`LiveStructuresTraceCheck: PASSED (4317 chunks, 424378368 cells)`, so all 4032
cells are gone.

```
java.lang.AssertionError: live structures exact parity failed: 4032 cells
(-15,-11=21 -31,6=287 -30,6=231 -29,6=50 -33,7=259 -32,7=235 -31,7=132 -30,7=230
 -32,21=118 11,22=97 12,22=36 13,22=19 -32,22=91 12,23=366 13,23=196 -32,23=39
 -31,23=185 11,24=155 12,24=225 13,24=79 14,24=31 15,25=183 16,25=38 -30,25=115
 -30,26=152 -27,26=3 -26,26=26 -24,27=134 -26,29=196 -25,29=82 -24,29=21);
leaf-distance subset=0
```

The cells cluster in three regions: around `-31,6` to `-30,7`, around
`-32,21` to `-32,23`, and around `11,22` to `16,25`. The trailing
`leaf-distance subset=0` is the gate's own classifier output and did not
excuse any of the 4032 cells.

## Why it matters for the campaign

An abort makes a window unverifiable rather than mis-compared, so camp2's abort rate (4 of 6)
is a coverage problem: on most startup windows the strict gate cannot produce a verdict at
all, and the campaign's evidence comes only from the windows where both gates finish.
Diagnosing classes A and B, or classifying the windows, is needed before a large run can be
read as evidence. Do **not** silence these checks to make windows count: the standing rule is
that the gate must not be left weakened in a committed state, so the options are a real fix
or an explicit per-window classification.
