# FEATURES order diagnosis, 2026-09-18

Diagnostic-only investigation, based on `main` at
`697f17de2153e3bdb9683e3cd6539e45981a468b`. No production behavior changes.
No services, persistence or networking code touched.

## Outcome (added 2026-09-25)

This document is a point-in-time diagnosis and is kept as written. Where it
leads: the order dependency it documents turned out to be vanilla's own
behaviour, not a Kestrel defect. Two identical uninstrumented vanilla 26.2
player routes differed in 57 of 65 scored chunk payloads, and two identical
vanilla runs reproduce only about 42.75% of chunks. Block-exact parity against
a single vanilla run is therefore not achievable for this workload; the
matched-order Tier-R gate is the exact oracle, and a live vanilla capture is a
sample rather than ground truth. A final live-oracle comparison
confirmed this.

## Evidence-ranked explanation

1. **Confirmed mechanism: state-dependent overlapping FEATURES placements.**
   `FeatureOrderCheck` starts from fresh NOISE/SURFACE/CARVERS for each run,
   executes exactly nine feature origins around an interior target, then compares
   all 98,304 target cells. Forward is x ascending then z ascending; reverse is
   that exact list reversed. The forward run is independently repeated first.
   At `(-96,39,-96)`, forward places seagrass from origin `(-6,-7)` via
   `minecraft:seagrass_deep_warm`; reverse places kelp from `(-6,-6)` via
   `minecraft:kelp_warm`. The existing after-feature hook attributes these writes.
   This is actual competing placement, not evidence of noise drift or an RNG
   seed formula error. The unchanged vanilla KelpFeature checks WATER before
   drawing its height: `place`, bytecode offsets 64-75 then 94-104. Thus earlier
   placements can change both eligibility and later draws within a feature.
   The precise first diverging random draw is not yet traced.

2. **Confirmed sulfur writer in the reduced case: `minecraft:sulfur_pool`.**
   At `(-81,-19,109)`, forward origin `(-5,6)` changes cinnabar to sulfur;
   reverse leaves cinnabar. This locates the differing write in FEATURES,
   not CARVERS. The additional target (-5,6) reproduces the exact exported seam
   cell (-80,-18,105): reverse origin (-5,6), sulfur_pool, changes deepslate
   to sulfur; forward leaves deepslate. This confirms the same placed-feature
   writer for an actual sulfur/deepslate border cell. The captured datapack `configured_feature/sulfur_pool.json`
   lines 1-46 defines a sequence whose first feature is a lake with a sulfur
   barrier, a sulfur-spike exclusion and state-dependent replacement predicates.
   LakeFeature.place reads existing state at bytecode offset 624, tests predicates
   at 678/776/1175, and writes at 817/1201. A neighbor changing eligibility is a
   strong hypothesis; identifying the first predicate/RNG divergence still needs
   a read/draw trace. Do not infer that every sulfur seam has this writer.

3. **Likely production ownership: FEATURES scheduling and completion context.**
   LiveGenerator.ensureChunk runs only the requested center's FEATURES after
   preparing a CARVERS halo, then publishes the 3x3 and marks that center done.
   Later centers can modify it. This is a real incomplete-feature-frontier
   exposure, distinct from final differences after the same origin set completes.
   The diagnostic reproduces the latter without streaming, save/load or packets.
   A fixed global order might remove this test's variation, but is not yet a
   proven vanilla-compatible fix.

4. **Lower confidence: heightmap/context adapter differences.**
   Per-center priming, lazy neighbor heightmap reads, shared block state and
   placement predicates are plausible parity-sensitive seams. No trace here
   proves an incorrect heightmap value or a bad seed. Kestrel invokes the real
   vanilla feature implementations; rewriting kelp/sulfur algorithms is not
   supported by the evidence.

Important limit: the repository's D1/D2 contract explicitly permits vanilla
cross-order overlap differences. An order-invariance assertion is an intentionally
strong diagnostic red gate, not proof by itself of a Kestrel-only defect. The
existing feature-order recording is flat-world seed 4242424242, not this oracle.
Two Kestrel orders agreeing but differing from vanilla also does not establish a
separate algorithmic root cause: vanilla may have used a third order, a wider
feature halo, or later FULL-stage processing.

## Exact ownership citations (base commit above)

- `core/src/vanilla/java/dev/kestrel/worldgen/BatchParityGate.java:155-165`:
  common CARVERS prefix; `269-281` and `410-425`: shuffle seeds 424242/888888,
  cloned maps, caller-owned order; `653-670`: section-copy cloning; `388-389`:
  post-pass order1 snapshots. Final exports do not identify individual writers.
- `core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java:737-746`:
  center heightmap priming; `760-805`: decoration seed, ascending step/global
  feature index, per-feature reseeding and vanilla placeWithBiomeCheck call.
- Same file `912-950`, `971-1005`, `1062-1080`, `1130-1135`: RegionView shares
  the chunk map, radius-one write guard, chunk-backed reads/writes and heights.
  `923-927` and `817-819`: existing afterFeature hook, including direct section
  writes that bypass setBlock. `806-815`: exceptions are logged and skipped;
  no feature-placement failure was logged during this diagnostic.
- `core/src/vanilla/java/dev/kestrel/worldgen/LiveGenerator.java:80-98`,
  `104-120`, `139-166`: caller-request order, halo preparation, center-only
  decoration, publication and generated marker, cached prefix ownership.
- `preresearch/g2-feature-order-notes.md:9-56`: vanilla
  ChunkStatusTasks.generateFeatures is the synchronous per-center leaf;
  ChunkGenerationTask.scheduleLayer owns the x-outer/z-inner margin walk;
  concurrent tasks interleave. Lines 134-141 explicitly limit the global-order
  interpretation. `preresearch/g2-build-order.md:346-360`: D1 replay and D2 gates.
- `preresearch/g2-step7-design.md:12-18`: heightmap and CARVERS status contract.
  The diagnostic retains CARVERS status, matching the live path; BatchParityGate
  advances centers to FEATURES. Both use final heightmap types per this note.

Vanilla bytecode was inspected locally using JDK 25 `javap -c -p` for
`net.minecraft.world.level.levelgen.feature.{KelpFeature,SeagrassFeature,LakeFeature}`.
Jar: `$HOME/kestrel-run/vinner/META-INF/versions/26.2/server-26.2.jar`, SHA256
`183c0499c5f855570ee487dd38e141a53f0121f83a0b07a3bac2d8b6698823e8`.
Datapack root: `$HOME/kestrel-run/packdump/data/minecraft/worldgen/`.

## Existing raw-export evidence, checked afresh

Input: `/tmp/kestrel-raw-export-verify/raw-export/{order1,order2,vanilla}.json`.
SHA256 respectively:

- `2a4316038fc0a1082113d626f50297127df1c7fab6036576bef59dc48f33f474`
- `34a14710700e6bf89080d13e53da3b2d3a45951717d268e514b97f7dc27a34a9`
- `3f0397e4936231a7e0909cca661846bb6e386a7cf0abdd8b0b3eaf5cab815740`

Current analyzer, order1 against order2: 2148 kelp/seagrass seam groups,
563 with at least one differing pair; 33 sulfur/deepslate groups, 14 with
at least one differing pair. These are group counts, not changed-cell counts
or the intersection with vanilla-divergent groups. The analyzer's historical
`exists_in_vanilla` field means matches the supplied comparison, here order2.

Exact independently decoded cells (state properties omitted only where obvious):

| Position | order1 | order2 | vanilla FULL |
|---|---|---|---|
| (-96,39,-96) | kelp_plant | seagrass | seagrass |
| (-86,37,-82) | kelp_plant | water | water |
| (-81,-19,109) | cinnabar | cinnabar | sulfur |
| (-81,-18,105) | deepslate | deepslate | deepslate |
| (-80,-18,105) | sulfur | deepslate | deepslate |
| (-82,2,111) | deepslate | deepslate | deepslate |
| (-82,2,112) | sulfur_spike (frustum,down,false) | air | air |

The last two pairs are actual sulfur/deepslate seam evidence. The changed cell
can be the B-side of a border, not the reported A-side anchor. The reduced
cinnabar case is a different order/coverage experiment: it must not be called a
reproduction of that full export's stable-but-wrong classification.

Corrections to `/tmp/seam-evidence/EVIDENCE-SUMMARY.md`: its group anchors predate
697f17d's divergent-coordinate fix; its claim that no sulfur findings touch the
radius edge is contradicted by its own z=8/x=-8 rows. The CARVERS-only exterior
halo limits vanilla comparisons at those edges. Geographic clustering alone
does not prove a single cave/ore feature instance or a CARVERS defect.

## Reproduction and commands

The new opt-in check is
`core/src/vanilla/java/dev/kestrel/worldgen/FeatureOrderCheck.java`.
It is deliberately not added to the default check suite. It tests three target
chunks with fresh 5x5 CARVERS prefixes and 3x3 decorated origins each. Only the
center is compared, so every direct radius-one contributor to that center ran.
This is a small reproducer, not a proven mathematically minimal origin set;
indirect influences from farther origins remain outside this reduced experiment.

From the isolated worktree:

```bash
export JAVA_HOME=$HOME/.local/toolchains/jdk-25.0.4.1+1
./core/gradlew -p core compileJava --offline \
  -PvanillaJar=$HOME/kestrel-run/vinner/META-INF/versions/26.2/server-26.2.jar \
  -PvanillaLibs=$HOME/kestrel-run/vinner
mkdir -p .diagnostics
python3 - <<'PY'
import pathlib, subprocess
root = pathlib.Path.cwd()
v = pathlib.Path('$HOME/kestrel-run/vinner')
cp = ':'.join([str(root/'core/build/classes/java/main'),
    str(v/'META-INF/versions/26.2/server-26.2.jar')]
    + [str(p) for p in v.glob('META-INF/libraries/**/*.jar')])
p = subprocess.run(['$HOME/.local/toolchains/jdk-25.0.4.1+1/bin/java',
    '-Xmx1500m', '-cp', cp, 'dev.kestrel.worldgen.FeatureOrderCheck'],
    cwd=root/'.diagnostics')
raise SystemExit(p.returncode)
PY
PYTHONPATH=. python3 harness/tools/tests/test_seam_analyzer.py
python3 harness/tools/seam_analyzer.py \
  -k /tmp/kestrel-raw-export-verify/raw-export/order1.json \
  -v /tmp/kestrel-raw-export-verify/raw-export/order2.json \
  -c sulfur_vs_deepslate -f json -o .diagnostics/sulfur-current.json
# Repeat the analyzer command with category kelp_seagrass_vs_water_air.
```

Final measured run (exit 1, intentional AssertionError):

| Target chunk | Forward repeat | Family cells different under reverse |
|---|---|---:|
| (-6,-6) | identical across the entire chunk | 140 |
| (-6,6) | identical across the entire chunk | 18 |
| (-5,6) | identical across the entire chunk | 218 |
| Total | all controls pass | 376 |

These counts include any changed cell involving kelp, seagrass or sulfur, not
just border pairs. The first two counts were also reproduced in two earlier
runs. Final trace excerpts, with unchanged prefixes omitted:

```text
reverse=false origin=[-6,-7] seagrass_deep_warm (-96,39,-96): water -> seagrass
reverse=true  origin=[-6,-6] kelp_warm          (-96,39,-96): water -> kelp_plant
reverse=false origin=[-5,6]  sulfur_pool       (-81,-19,109): cinnabar -> sulfur
reverse=true  origin=[-5,6]  sulfur_pool       (-80,-18,105): deepslate -> sulfur
AssertionError: FEATURES order changed 376 target family cells
```

Compile succeeds. Analyzer tests: 16/16 pass. Initial authoring compile needed
checked exceptions declared; direct Python test invocation needed PYTHONPATH=.;
both corrected before the results above. No production build required.
Local runtime logs, bytecode dumps and analyzer outputs remain uncommitted in
`.diagnostics/`; they are not fixtures or oracle substitutes.

## Smallest safe fix boundary and missing proof

Keep the candidate production change at the FEATURES orchestration/completion
boundary in LiveGenerator, with HeadlessNoise.RegionView or features changed
only if a matched trace proves an adapter error. Do not change noise, carve,
feature RNG algorithms, persistence, networking, or patch exported cells.
Do not force a global sort or immutable neighbor snapshots as a presumed fix:
both change overlapping vanilla placement semantics.

Before choosing an implementation, obtain this seed's vanilla FEATURES entry
order and per-origin completion/dependency coverage, plus CARVERS checkpoints
and per-feature target-region snapshots. Replay the identical order/coverage in
Kestrel, find the first diverging feature, then log its block/heightmap reads,
placement predicate results and RNG draws. A second vanilla order is needed to
measure D2's allowed overlap variation. Existing FULL exports contain neither
that trace nor intermediate stages; the old flat-world order trace is not a
substitute. The deterministic sensitivity regression is established, but a
Kestrel-specific matched-order vanilla parity regression remains blocked on
those artifacts. No production fix is justified or implemented in this task.

## Follow-up, 2026-09-18: matched-order vanilla trace and replay (resolves the open item above)

Continuation of this diagnosis, based on `main` HEAD `66004ff`. An earlier
attempt to build this same instrumentation was abandoned before it ran, so this
follow-up builds it from scratch. No production behavior changed.

**Build note, not a code defect**: `harness/agent`'s Gradle build had never been
run with network access, so its Shadow plugin (`com.gradleup.shadow:8.3.5`) was
never cached; the module's own build script does not force `--offline` anywhere,
that flag only belongs to the `core` module's vanilla-jar-dependent compile.
Building `harness/agent` once online (network confirmed reachable) resolved it
permanently; no source change was needed for this.

**Capture**: `harness/tools/capture-features-order.py` boots a disposable,
throwaway vanilla 26.2 instance (fresh temp world, `online-mode=false`,
`generate-structures=false` matching every other oracle capture in this repo,
`view-distance=2`) with a new opt-in harness-agent mode
(`-Dkestrel.featuresTrace=<dir>`, mutually exclusive with the existing
timing/hash probes) that ASM-instruments vanilla's own
`ChunkStatusTasks.generateFeatures` to record real ENTER/COMPLETE order, thread,
write radius, dependencies and a hashed 3x3-window block snapshot per event.
Same seed `3527749958874813416`, same three target chunks
`(-6,-6)`, `(-6,6)`, `(-5,6)` as the rest of this document. One run produced
228 validated events over 114 unique centers (196-chunk coverage), stored at
`/tmp/kestrel-features-trace-run1` (disposable, reproducible from the script;
not a repository fixture, matching the existing `.diagnostics/` convention).

**Replay**: `FeaturesTraceReplay` (new) deterministically replays that exact
114-center order through Kestrel's own `HeadlessNoise` pipeline (fresh
NOISE/SURFACE/CARVERS, then per-center `features()` in the captured sequence)
and compares every touched cell against the captured vanilla snapshot. The
original draft counted "observations", which double count any physical cell
every time a later center's 3x3 dependency window includes it; this follow-up
added a distinct-cell set and a one-shot final-settled-state comparison so the
reported numbers describe the real bug surface rather than an inflated total.

**Result 1 (closes the open item above): the seam families are fully explained
by order, not by a Kestrel feature-placement bug.** All three original target
chunks match vanilla exactly under the real captured order: 0 distinct wrong
cells, at both interim and final state
(`distinctFamilyWrongCells=0`). No change is needed in `HeadlessNoise.features`,
`RegionView`, or the vanilla `KelpFeature`/`SeagrassFeature`/`LakeFeature`
invocations for this family. The candidate fix boundary already named above
(FEATURES orchestration/completion order in `LiveGenerator`, not the feature
algorithms themselves) is confirmed as the right and *only* boundary for this
specific defect class.

**Result 2 (new, separate, not investigated further): a real, spatially
localized divergence exists elsewhere in the same captured region, unrelated to
feature ordering.** Final-settled-state comparison across all 196 covered
chunks: 77,325 distinct wrong cells, concentrated in 5 contiguous chunks:
`(-6,9)=18141 (-9,10)=11778 (-8,10)=14209 (-7,10)=16628 (-6,10)=16386` (roughly
12-18% of a full chunk column each), plus 9 chunks with negligible (1-72 cell)
noise. The first divergence in the whole trace (`(-90,36,-17)`, vanilla `sand`
vs Kestrel `gravel`) appears at `ENTER`, i.e. before FEATURES runs there at all,
so this is a NOISE/SURFACE/CARVERS-stage issue, not a FEATURES-ordering one, and
it resolved itself by final state at that one cell while the 5-chunk cluster did
not. This is a distinct, previously-unknown defect. No root cause is
identified, no fix is proposed, and no further investigation was done here;
it needs its own scoped task, most likely starting from why chunks
`x=-9..-6, z=9..10` specifically diverge so heavily (a large feature, structure,
or noise/biome-boundary difference is the leading guess, not yet evidence).

**Validation for this follow-up**: `harness/agent` builds clean (`./gradlew
build`); `core` `compileJava --offline --rerun-tasks` against the pinned vanilla
jar succeeds; `checkRepro --offline -Pcheck=NetCheck` still passes (`NetCheck:
PASS`, full bot suite, 4m12s); `git diff --check` clean. The disposable vanilla
instance and its trace never touched `play/world`, the live Pi service, or any
tracked repository state.

### Tier R scoring closure correction

The initial final-state gate treated a chunk as scoreable when every direct
FEATURES writer in its ring-1 neighbourhood appeared in the trace. That is not
enough. A direct writer makes placement decisions by reading its own ring-1
footprint, so reproducing the writer's vanilla inputs requires the union of
those footprints: ring 2 around the scored chunk.

The remaining 43 differences proved this geometry rather than motivating a
coordinate exception. They were all at world `z=142` or `z=143`, on the `+z`
face of chunks `(-7,8)` and `(-6,8)`. Those chunks' only missing ring-2 centers
were in row `z=10`, exactly on that side. With ring-2 closure, the same trace
scores **0 differences across 21 chunks and 2,064,384 cells**. A negative
control that temporarily restored ring-1 scoring reproduced all 43 differences
and exited non-zero, confirming the gate does not pass divergent scored state.

`FeaturesTraceReplay` now reports ring-1-complete/ring-2-incomplete chunks as a
boundary cascade, retains per-cell diagnostics under `--report-only`, refuses a
zero-cell pass, and fails closed on any scored difference. The production rule
is the general dependency closure (`SCORE_RING = 2`), with no coordinate or
block-family special cases. The next exit criterion remains a clean Tier R run
with at least 1,000 scored chunks.

### Large Tier R baseline

A 36 by 36 requested window (`x,z = -18..17`) was captured with the same seed
and structures disabled. `capture-features-order.py --chunk-window` splits the
rectangle into serial tiles of at most 256 forced chunks, waiting for every
requested FEATURES completion before unforcing one tile and starting the next.
The capture contains 3,532 balanced events over 1,766 unique centers, covers
all 1,296 requested centers, has `max active = 1`, and yields 1,433 ring-2
scoreable chunks.

The scale gate correctly failed closed: **154,101 differences across 60 of
1,433 scored chunks**, or 99.890607% cell parity across 140,869,632 cells. The
dominant connected cluster is around `x=-9..-6, z=9..12`; it accounts for nearly
all scored differences. Dominant state pairs are Kestrel sulfur or cinnabar
where vanilla has deepslate or stone. This is not a reason to relax Tier R.

A fresh 5 by 5 requested capture around the cluster reproduced the issue with
152,396 differences across 24 of 49 scored chunks. That is now the cheap
reproduction corpus. In the large trace, chunk `(-9,10)` first becomes visible
to the comparison at FEATURES ENTER with 11,775 differences, before any traced
ring-1 center capable of writing that chunk had completed. A temporary prefix
probe localized the material expansion to Kestrel's SURFACE pass: Kestrel NOISE
was mostly stone there, SURFACE introduced the excess sulfur/cinnabar, and
CARVERS reduced the count from 13,142 to 11,775. The precise SURFACE input or
rule mismatch remains open; likely boundaries include biome context, the
possible-biome set, and surface randomization. No production generator change
was made without a narrower causal test.

Small independent differences also exist, including dungeon chest/spawner
state that the headless view does not retain. Keep those separate from the
dominant SURFACE cluster.
