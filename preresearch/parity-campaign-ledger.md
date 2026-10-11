# Parity campaign ledger

The campaign is `harness/tools/parity-campaign.py`, run on the bench PC under
`start /belownormal` (the driver itself launches each gate with
`PriorityClass = 16384`). Each window: capture a 64x64 window of chunks from a
real Vanilla server of this pack, then run both gates on the same trace.

- strict: `FeaturesTraceReplay <trace> --structures`, verdict
  `TIER_R VERDICT: PASS` and a per-origin block diff count.
- live: `LiveStructuresTraceCheck <trace> --replay-chunk-loads --replay-lighting`,
  verdict `LiveStructuresTraceCheck: PASSED (N chunks, M cells)`.

A window counts as PASS only when both gates pass. `startup=True` windows
capture the region around spawn with a pre-generated world, so they exercise the
chunk-reload paths far more than the ordinary windows do; every defect found so
far was first seen in one.

## camp1

classes `classes-36f7555`, plan_seed 3421099001, 64x64, budget 9h, started
2026-09-23 15:17 (`C:\Users\bench\bench\camp1`).

| window | window pos | strict | live | outcome | class |
|---|---|---|---|---|---|
| w00 | -32,-32 startup | FAIL | FAIL | FAIL | light storage survives a captured reload (fixed, `preresearch/w00-mushroom-light-dependency.md`) |
| w01 | -8313,-27882 | FAIL | FAIL | FAIL | undiagnosed |
| w02 | -1686501,1375237 | FAIL | PASS 4356/4356 | FAIL | strict-only diff, undiagnosed |
| w03 | -32,-32 startup | FAIL | FAIL | FAIL | undiagnosed |
| w04 | 43503,49335 | FAIL | PASS 4313/4313 | FAIL | strict-only diff, undiagnosed |
| w05 | 1620983,1157422 | PASS 4292/4292 | PASS 4292 | PASS | |
| w06 | 701,-1099 | FAIL | FAIL | FAIL | undiagnosed |
| w07 | -14056,-16978 | - | FAIL | FAIL | intra-FEATURES light completion on a light thread (`preresearch/w07-intra-features-light-ordering.md`) |
| w08 | -546825,1547198 | - | - | not evaluated | |

## camp2

classes `classes-f9a81be`, plan_seed 348082826, 64x64, budget 3.6h, started
2026-09-23 20:27 (`C:\Users\bench\bench\camp2`), stopped when its budget expired at
23:54. Verdicts read from the bench PC on 2026-09-24 06:40. The window positions for
w03 to w05 were not recovered, so the capture seeds are given instead.

| window | seed | strict | live | outcome | class |
|---|---|---|---|---|---|
| w00 | 3428864282687571407 startup | ABORT, then PASS 4309/4309 on the start-reload fix | PASS 4309/4309 | PASS on rerun | was `cannot resolve structure-reference source at -18,21 index=1`; a start-only reload shrank a jigsaw box (`preresearch/camp2-w00-unresolved-reference-source.md`) |
| w01 | -2187541148205472002 | PASS | PASS 4315/4315 | PASS | |
| w02 | 6565943359908612314 | PASS | PASS 4340/4340 | PASS | |
| w03 | 2918855829324095836 startup | ABORT | FAIL 4032 cells, then PASS 4317 on the `e6a51b4` rerun | FAIL | live final-state `cave_air` publication loss (fixed); strict camel `LastPoseTick` 566 versus 0, fixed in `6cffddf` but unverifiable on this trace, which has no game time (`preresearch/camp2-abort-classes.md`) |
| w04 | -261122040565980715 | ABORT | PASS 4318/4318 | FAIL | `cannot linearize intra-FEATURES INITIALIZE_LIGHT` (`preresearch/camp2-abort-classes.md`) |
| w05 | 6224172471721147925 | ABORT, then PASS 4314/4314 on the block-entity reload fix | PASS 4314/4314 | PASS on rerun | was pending versus real block entity at `(3708700,-6,7746640)` (`preresearch/camp2-abort-classes.md`) |

## Local rechecks on 2026-09-24

The composed reload and intra-pass-light changes were replayed against the
unchanged camp1 captures on the VPS. These rechecks do not rewrite camp1's
original results above.

| window | strict | live | evidence |
|---|---|---|---|
| w00 | PASS, 4324/4324 chunks, 0 diffs | PASS, 4324 chunks, 425066496 cells, 0 diffs | `/tmp/fix6-w00strict.log`, `/tmp/fix7-w00live.log` |
| w07 | unavailable: legacy trace lacks three structure lifecycle files | PASS, 4292 chunks, 421920768 cells, 0 diffs | `/tmp/fix5-w07strict.log`, `/tmp/fix7-w07live.log` |
| w01 | not rerun | PASS, 4289 chunks, 421625856 cells, 0 diffs | `/tmp/fix7-w01live.log` |

w07's first revised live run had three `cave_air` versus `air` cells after all
FEATURES windows matched. `SectionStore.putCompact` discarded a section when
every state counted as air, losing the three `cave_air` identities during
publication. The final live run keeps those states and passes. The new
campaign must supply a full w07-like trace before that class can count as a
two-gate pass.

The final live rechecks applied every captured light completion: w00 5557/5557,
w01 5426/5426, and w07 5289/5289, all with zero repeats and zero diffs. An
intermediate build passed the block comparisons but marked LIGHT-or-later
reloaded forms as already initialized, skipping hundreds of later captured
completions. The final live gate accepts a full-form read with no completion at
that cutpoint and still executes the completions that appear later in the
capture.

The solo w01 live replay took 983 seconds against a recorded 484 second
baseline. The per-load light-storage sweep may contribute, but the wall time
alone does not isolate its cost or measure production chunk-generation speed.

## camp3

Launched on the bench PC on 2026-09-24 09:59 local time with frozen
`classes-e6a51b4`, plan seed `1869880575`, 64x64 windows, and a 3.6-hour
budget. The capture agent and boot jars remain the camp1/camp2 versions.
The driver wrote `C:\Users\bench\bench\camp3.out`; process and Java child were
verified with `tasklist`, and all campaign processes reported `BelowNormal`
priority. Its first window is a startup capture with seed
`-4020159154738087921` at `-32,-32`.

| window | seed | strict | live | verdict |
|---|---|---|---|---|
| w00 | -4020159154738087921 startup | PASS 4292/4292, 0 diffs, 1275 s | PASS 4292, 861 s | PASS |
| w01 | -6767596723154519470 | PASS 4284/4284, 0 diffs, 1284 s | PASS 4284, 846 s | PASS |
| w02 | -2229834524224719260 | PASS 4282/4282, 0 diffs, 1472 s | PASS 4282, 835 s | PASS |
| w03 | -5186516791654869197 startup | ABORT `structure-call after content mismatch index=2 target=24,-22`, 743 s; PASS 4317/4317, 0 diffs with the camel call's inferred game time (`6cffddf` mechanism test) | PASS 4317, 987 s | FAIL as captured; PASS with an inferred camel time |
| w04 | -2986440727679193598 | PASS 4292/4292, 0 diffs, 1363 s | PASS 4292, 886 s | PASS |

w04's window is `30125,-31202`. After w04's capture, only 55 GB were free, under
the 60 GB guard, so camp3 started no new capture. It logged `campaign done
windows=5 pass=4 fail=1 scoredChunksPassed=17150` at 13:28 local. camp3 w03's abort is the camel class again: a `village_desert` call at
origin `24,-22` places one camel whose `LastPoseTick` is `211L` in Vanilla (game
time 264) and `0L` in Kestrel; the rest of the six-entity list is identical.
See `preresearch/camp2-abort-classes.md`. Captures take about 34 minutes per window on the frozen stack.

The camel fix (`6cffddf`) was tested on this window. The test used a hard-linked
copy of the trace (`camp3\w03-clocktest`) plus a two-row
`structure-call-times.tsv` giving call 2 at `24,-22` game time 264, inferred
from Vanilla's `LastPoseTick` 211. Classes were the reload-fix candidate plus
the `6cffddf` overlay. Strict printed `STRUCTURE_CALL_MATCH origin=24,-22
index=2` and finished `TIER_R VERDICT: PASS`, 4317/4317 chunks, 424378368 cells,
0 diffs (`tierr\camp3-w03-strict-clock.log`, 13:21 local). Because that one
field's input is derived from the Vanilla output, the window counts as PASS
with an inferred camel time, not as a plain PASS. Every other field and block
is compared normally. The same classes without the times file reproduce the
abort at the same call (`tierr\camp3-w03-strict-candidate.log`), so nothing
diverges earlier.

## Re-verification of retained windows and camp4 (launched 2026-09-24 13:24)

Both run on classes `classes-6cffddf` (built from a worktree at `6cffddf`,
not from the play server's `core/build`).

- `harness/tools/parity-reverify.py` reruns both gates on every retained
  camp1 and camp2 trace. Order: camp2 w05, w00; camp1 w00, w01, w02, w04, w06,
  w08, w03, w07; camp2 w04, w03. Windows that pass are deleted under the
  campaign's policy, which is also how the disk gets back above camp4's 60 GB
  guard (camp3 stopped capturing at 55 GB free). Output: `campN\reverify.tsv`,
  `campN\reverify.log` and `C:\Users\bench\bench\reverify-6cffddf.out`.
- camp4 runs `tierr\camp4-tools`: the new boot jar
  `0c1d97835258d9724694a5813540d84b`, which writes `structure-call-times.tsv`;
  the agent jar is unchanged (`b85de52e91e9692fa34b42bc9fe89f76`). Budget 10 h,
  output `C:\Users\bench\bench\camp4`. `tierr\camp4-start.py` starts it once
  camp3 logs `campaign done` and at least 66 GB are free (`bench\camp4-start.log`).
  It started at 13:28:53 with 75.2 GB free: plan seed `4182766803`, first window
  a startup capture with seed `1964855202722115123` at `-32,-32`. The capture's
  trace dir contains `structure-call-times.tsv`, so the new boot jar is in use.
- A VPS smoke capture with the new agent (seed `-5186516791654869197`, 16x16
  startup at `16,-30`) passed both gates on these classes: strict 324/324
  with 433 time rows and 4 straddles, live 324.

### Results as read at 2026-09-24 15:56 local

Reverify on `classes-6cffddf` (every row: strict and live both rc 0):

| campaign | window | strict | live | verdict |
|---|---|---|---|---|
| camp2 | w05 | PASS 4314/4314, 0 diffs, 1427 s | PASS 4314, 1048 s | PASS |
| camp2 | w00 | PASS 4309/4309, 0 diffs, 1303 s | PASS 4309, 929 s | PASS |
| camp1 | w00 | PASS 4324/4324, 0 diffs, 1302 s | PASS 4324, 942 s | PASS |
| camp1 | w01 | PASS 4289/4289, 0 diffs, 1290 s | PASS 4289, 962 s | PASS |
| camp1 | w02 | PASS 4356/4356, 0 diffs, 1333 s | PASS 4356, 978 s | PASS |
| camp1 | w04 | PASS 4313/4313, 0 diffs, 1501 s | PASS 4313, 982 s | PASS |

camp1 w01, w02 and w04 were strict FAIL on `classes-36f7555`; the fixes since then
close them. camp1 w06 was running.

camp4 (plan seed `4182766803`):

| window | seed | pos | strict | live | verdict |
|---|---|---|---|---|---|
| w00 | 1964855202722115123 | -32,-32 startup | PASS 4321/4321, 0 diffs, 1402 s | PASS 4321, 805 s | PASS |
| w01 | -8083582223332440096 | 49878,-42723 | ABORT class 2 after 738 s: `cannot linearize intra-FEATURES INITIALIZE_LIGHT` (origin 49927,-42711, chunk 49926,-42711, air to `spruce_leaves` at 798830,66,-683368) | PASS 4295, 957 s | FAIL (unverifiable in strict) |
| w02 | 1627665490876644810 | -1175029,1436179 | PASS 4303/4303, 0 diffs, 1319 s | PASS 4303, 955 s | PASS |
| w03 | -5079519872108581226 | -32,-32 startup | PASS 4307/4307, 0 diffs, 1471 s | PASS 4307, 1233 s | PASS |

camp4 w00 and w03 are startup windows captured with `structure-call-times.tsv`,
so they are the first plain PASSes of startup windows under the game-time
clock. camp4 w01 is class 2 again, the strict harness's only recurring open
class; its live gate passes.

### Update as read at 2026-09-24 16:36 local

- Reverify: camp1 w06 PASS (strict 4315/4315, 0 diffs, 1483 s; live 4315,
  857 s) and camp1 w08 PASS (strict 4284/4284, 0 diffs, 1309 s; live 4284,
  859 s). Remaining in order: camp1 w03, w07, then camp2 w04, w03.
- camp4 w04 (seed `-69733920192816777`, `-34143,-47081`) PASS: strict
  4299/4299, 0 diffs, 1299 s; live 4299, 834 s. w05 is in its gates.

### Update as read at 2026-09-24 17:10 local

- camp4 w05 (seed `-9145481145277697320`, `-1545094,210739`) PASS: strict
  4341/4341, 0 diffs, 1414 s; live 4341, 1106 s. camp4 now stands at 5 PASS
  (w00, w02 to w05) and 1 FAIL (w01, strict abort, live PASS). w06 is next.

### Update as read at 2026-09-24 22:30 local

Reverify is done (`reverify done windows=8 pass=7 fail=1` for camp1). Every
reverify row has live rc 0.

| campaign | window | strict | live | verdict |
|---|---|---|---|---|
| camp1 | w03 | PASS 4304/4304, 0 diffs, 1357 s | PASS 4304, 900 s | PASS |
| camp1 | w07 | ABORT class 2 after 1353 s (origin -14035,-16914, chunk -14036,-16914, air to `brown_mushroom` at -224565,67,-270617) | PASS 4292, 884 s | FAIL (unverifiable in strict) |
| camp2 | w04 | ABORT class 2 after 1143 s, same event as before (origin 16681,-2644, air to `acacia_leaves`) | PASS 4318, 930 s | FAIL (unverifiable in strict) |
| camp2 | w03 | ABORT class 4 after 1043 s: `structure-call after content mismatch index=1 target=19,1`, log names `LastPoseTick` | PASS 4317, 893 s | FAIL (trace predates `structure-call-times.tsv`) |

camp1 w07 used to stop earlier, at class 3 (`cannot resolve structure-reference
source at -13996,-16951`). On `6cffddf` it gets past that and stops at class 2.
camp2 w03 cannot pass strict on any classes: its trace has no per-call game
time, which is the input the class 4 fix needs.

camp4, w06 to w21 (campaign finished):

| window | seed | pos | strict | live | verdict |
|---|---|---|---|---|---|
| w06 | 630889669383096103 | 205,-515 | PASS 4290/4290, 0 diffs, 1328 s | PASS 4290, 811 s | PASS |
| w07 | 2868215760399841437 | -29454,-27750 | ABORT after 862 s: `LIFECYCLE_STATUS_MISMATCH seq=3617 phase=ENTER origin=-29438,-27727 chunk=-29439,-27728 expected=features actual=initialize_light`, right after `LIGHTING_EVENT_REPLAY afterEventSeq=3616 order=1795 chunk=-29439,-27728` | PASS 4311, 1003 s | FAIL (unverifiable in strict) |
| w08 | -6653530935619403147 | -44721,-258518 | PASS 4272/4272, 0 diffs, 1334 s | PASS 4272, 993 s | PASS |
| w09 | 3115180844789647503 | -32,-32 startup | ABORT class 2 after 1212 s (origin 34,29, chunk 33,29, air to `birch_leaves`) | PASS 4318, 824 s | FAIL (unverifiable in strict) |
| w10 | 572555954584361641 | 35908,-13357 | ABORT class 2 after 1185 s (origin 35953,-13293, air to `spruce_leaves`) | PASS 4314, 983 s | FAIL (unverifiable in strict) |
| w11 | 2702067291312767499 | 502293,17522 | PASS 4283/4283, 0 diffs, 1310 s | PASS 4283, 1021 s | PASS |
| w12 | 7597977500995701041 | 1884,288 | PASS 4329/4329, 0 diffs, 1421 s | PASS 4329, 939 s | PASS |
| w13 | 4765062782751992015 | -14360,-48896 | PASS 4288/4288, 0 diffs, 1309 s | PASS 4288, 1011 s | PASS |
| w14 | 645760487383495592 | 1708788,1683414 | PASS 4326/4326, 0 diffs, 1303 s | PASS 4326, 817 s | PASS |
| w15 | -1260603193012865046 | -32,-32 startup | ABORT class 2 after 801 s (origin -22,-11, air to `dark_oak_leaves`) | PASS 4271, 1017 s | FAIL (unverifiable in strict) |
| w16 | -920000721555597171 | -33592,22912 | PASS 4296/4296, 0 diffs, 1411 s | PASS 4296, 846 s | PASS |
| w17 | -1867901964490698071 | -454055,-78987 | PASS 4321/4321, 0 diffs, 1472 s | PASS 4321, 856 s | PASS |
| w18 | 4437958104306089301 | 1078,924 | PASS 4292/4292, 0 diffs, 1350 s | PASS 4292, 838 s | PASS |
| w19 | -9132391835971442120 | 7290,27530 | PASS 4343/4343, 0 diffs, 1351 s | PASS 4343, 1006 s | PASS |
| w20 | 5709610089909554354 | -537800,543166 | PASS 4302/4302, 0 diffs, 1474 s | PASS 4302, 829 s | PASS |
| w21 | 4668729895853037663 | -32,-32 startup | PASS 4277/4277, 0 diffs, 1292 s | PASS 4277, 868 s | PASS |

camp4 finished at 17 PASS and 5 FAIL out of 22. The live gate passed all 22
(94,699 chunks). Every FAIL is a strict abort, not a diff: four are
class 2 (w01, w09, w10, w15), and w07 is a new signature, class 6. At w07 the
replay applies a captured INITIALIZE_LIGHT completion for chunk -29439,-27728
after event 3616, but the snapshot taken at event 3617's ENTER still records
that chunk as `features`. Like class 2, it is about where a completion from the
light thread falls relative to the FEATURES thread's events. Class 2 is now the
most frequent strict abort (4 of 18 camp4 windows, plus camp1 w07 and camp2
w04), so it is the main thing keeping the strict gate from covering every
window.

## Step 10 pool in the live gate, and camp5 (2026-09-25 local)

The camp1 to camp4 live gates ran `LiveStructuresTraceCheck` with its default
`--carver-threads=0`, which is inline. So they did not test the Step 10
generation pool. The following reruns fix that.

Pooled live replays on bench PC (`bench\s10pool\run.log`, classes `22ddbe5`,
`--replay-chunk-loads --replay-lighting --carver-threads=3`). These windows
were chosen because they include every window that aborted in strict:

| campaign | window | live (pooled) | cells | seconds |
|---|---|---|---|---|
| camp4 | w07 | PASS 4311 | 423788544 | 526 |
| camp4 | w01 | PASS 4295 | 422215680 | 561 |
| camp4 | w09 | PASS 4318 | 424476672 | 607 |
| camp4 | w10 | PASS 4314 | 424083456 | 633 |
| camp4 | w15 | PASS 4271 | 419856384 | 676 |
| camp1 | w07 | PASS 4292 | 421920768 | 735 |
| camp2 | w03 | PASS 4317 | 424378368 | 807 |
| camp2 | w04 | PASS 4318 | 424476672 | 763 |
| camp3 | w03 | PASS 4317 | 424378368 | 634 |

All 9 PASS (38,753 chunks). The VPS also passed pooled replays of w00 (4324
chunks, 425066496 cells) and w01 (4289, 421625856).

camp5 was launched 2026-09-24 23:57:57:
- plan_seed 1316656063, `--hours 7`, classes `22ddbe5`, `--heap 6G`;
- `--live-arg=--carver-threads=3`, so every camp5 live gate runs the pool;
- tools in `bench\tierr\camp5-tools`.

| window | seed | pos | strict | live (pooled) | verdict |
|---|---|---|---|---|---|
| w00 | 1845304001624733648 | -32,-32 startup | PASS 4276/4276, 0 diffs, 1737 s | PASS 4276, 828 s | PASS |

## Reading the results

- Six classes are known; four are fixed and two are open (2 and 6):
  1. light storage survives a captured reload (camp1 w00, fixed);
  2. an intra-FEATURES light completion emitted mid-pass by another thread (camp1 w07,
     camp2 w04, camp4 w01, w09, w10 and w15, where the strict gate refuses to
     linearize rather than guess an edge; open);
  3. an unresolved structure-reference source (camp2 w00: a start-only
     reload shrank a jigsaw box; fixed, camp2 w00 strict now PASS);
  4. structure-call content mismatches: the camel `LastPoseTick` (camp2 w03,
     camp3 w03: headless game time was stubbed to 0; fixed in `6cffddf`, which
     gives worldgen a clock and makes the capture record game time per structure
     call; camp1 to camp3 traces lack that file) and w05's pending-versus-real block
     entity (ProtoChunk reload round trip, fixed, camp2 w05 strict now PASS);
  5. camp2 w03's live 4032-cell diff across 30 chunks (the final-state
     `cave_air` publication loss, fixed in `e6a51b4`; the full live rerun
     PASSED 4317 chunks, 424378368 cells);
  6. a captured light completion replayed before a FEATURES event whose status
     snapshot still shows the chunk at `features` (camp4 w07,
     `LIFECYCLE_STATUS_MISMATCH`; open).
- A window that aborts in either gate is unverifiable rather than mis-compared, so an
  abort is not evidence of a block difference; the classes above are tracked separately
  for that reason.
- camp2's strict gate aborted on 4 of its 6 windows while its live gate PASSED 5 of 6.
  Both new strict abort classes sit in checks the live gate does not run (structure-call
  ordering, intra-FEATURES light linearization), which is the same asymmetry as camp1
  w02/w04.
- The strict gate can fail where the live gate passes (camp1 w02/w04, camp2
  w00/w04/w05). The strict gate replays more (hidden trace, structure order, spawn
  boundaries), so a strict-only difference is either a defect in the live gate's
  coverage or a real divergence the live comparison does not reach.
- camp3 onwards must run on classes that include the reload fix, or its windows
  will keep re-finding that class.
