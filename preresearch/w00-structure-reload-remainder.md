# w00: what is left after the structure-start rebuild fix

Status: OPEN. Written 2026-09-23 after merging the structure-start rebuild on chunk reload
(`f5d6b91`) and the headless level answering
`getChunkSource().getGenerator()` (`f9a81be`). The pale garden half is verified;
this is the remainder of window w00.

## What is proven

Seed `7272240032024010793`, window `-32..31` with the startup span, trace
`/tmp/traces/w00` (needs `height-queries.tsv` for the strict gate).

1. Before the fix the strict gate died at seq 5233 with
   `IOException: cannot resolve structure-reference source at -30,3 index=1`,
   because the recorded `structure-sources.tsv` box for that start is the
   post-reload one (`BoundingBox{minX=-526, minY=49, minZ=12, maxX=-417,
   maxY=84, maxZ=117}`) while Kestrel held the pre-reload start.
2. After the fix the replay's own log shows it using the rebuilt start:
   `STRUCTURE_CALL_MATCH origin=-32,3 index=1 key=...village_taiga
   box=BoundingBox{minX=-526, minY=49, minZ=12, maxX=-417, maxY=84, maxZ=117}`.
   The box-identity crash is gone and the box now matches the recording.
3. The mechanism is real and reproduced by `StructureStartRoundTripCheck`: one
   real `StructureStart.createTag` + `loadStaticStart` round trip on that
   witness start takes the box from `maxY=90` to `maxY=84`, all 48 pieces
   surviving, 11 of them returning 2 blocks tall instead of 16.
4. The reload that matters is chunk `-30,3` at `afterEventSeq=5232`
   (`CHUNK_RELOAD_REPLAY ... capturedStatus=minecraft:carvers`), exactly one
   event before the failing pass.

## What still fails

The strict gate now reaches seq 5234 and reports:

```
FIRST_BLOCK_DIFF seq=5234 phase=COMPLETE origin=-32,3 cell=BlockPos{x=-513, y=64, z=52} vanilla=Block{minecraft:air} kestrel=Block{minecraft:brown_mushroom}
Exception in thread "main" java.lang.AssertionError: structure-call before block mismatch index=1 target=-33,3 pos=BlockPos{x=-513, y=64, z=52} vanilla=air kestrel=brown_mushroom
  at FeaturesTraceReplay.assertStructureCallSnapshot(FeaturesTraceReplay.java:2041)
```

and the live gate fails on the same two positions (`z=52` and `z=56`, chunk
`-33,3`, origin `-32,3`). Vanilla never has anything but air at both positions
in any of its four recorded snapshots of chunk `-33,3` (`a3234da4` before its
own features, `18915f49` after, `51cf99a0` after light, `049653aa` final), so
this is a real 2-block divergence in the final world, not a snapshot artifact.

## What is ruled out

- Not the start's bounding box: both sides now report `maxY=84`.
- Not snapshot timing: four vanilla lifecycle snapshots agree on air.
- Not the reference-table lifecycle at that origin: the replay prints
  `STRUCTURE_REFERENCE_LIFECYCLE_MATCH origin=-32,3 rows=1 roundTrip=false
  mode=native-assert` at seq 5233 and does not throw.
- Not an intra-FEATURES reload: chunk `-30,3` reloads at `afterEventSeq=5232`,
  one event before the pass, so the reload is at a clean event boundary.

## Where to look next

Both mushroom positions, `(-513,64,52)` and `(-513,64,56)`, fall inside a single
rebuilt piece. `StructureStartRoundTripCheck` on the merged code prints the
per-piece diff for the witness start

```
piece[17] PoolElementStructurePiece
  before=BoundingBox{minX=-514, minY=63, minZ=51, maxX=-504, maxY=78, maxZ=59}
  after =BoundingBox{minX=-514, minY=63, minZ=51, maxX=-504, maxY=64, maxZ=59}
```

and both positions are inside the `after` box: x -514..-504 covers -513, z 51..59
covers 52 and 56, and y 63..64 covers 64, the top layer. Ten other pieces shrink
the same way (piece[1] 63..78 -> 63..64, piece[4] 61..76 -> 61..62, piece[10],
[12], [21], [24], [26], [30], [44] likewise), all of them to 2 blocks tall, which
is the "11 of 48" figure. So the two blocks sit on the top layer of a shrunk
piece, which is why they survive both the fixed and the unfixed build: y=64 is
inside the piece box in both cases, so the box change alone cannot explain them.

Note also that they appear with the *unfixed* build too (the box was then the
stale `maxY=90` and the live gate still reported exactly these two positions), so
whatever writes them is not caused by the rebuild. That points at the write order
or the piece content rather than the start lifecycle.

The trace records no per-piece data, so the decisive oracle is a fresh capture of
**the same seed** with the agent instrumented to dump, for each recorded source
start, its piece list and per-piece boxes at the moment the start is observed.
`structure-sources.tsv` is already written at that point (originX, originZ, index,
targetX, targetZ, sourceX, sourceZ, structureClass, box), so add a sibling file
with one row per piece: phase, originX, originZ, index, sourceX, sourceZ,
pieceIndex, pieceClass, box. A small window that covers chunk `-30,3` within the
radius-8 start reach is enough, and the same-seed capture keeps the comparison
exact. Neither the box nor the harness is the open question any more; Vanilla's
piece geometry is.

Two candidate mechanisms remain, and they need that instrumentation:

1. **The rebuilt piece list differs from Vanilla's.** Both sides rebuild through
   `loadStaticStart`, which discards the persisted `BB` and recomputes each piece
   box from `element.getBoundingBox(templates, position, rotation)`. If Kestrel's
   *live* piece position/rotation differs from Vanilla's for any of the 48 pieces,
   the rebuilt list differs even though the union box agrees.
2. **The shared structure RNG stream.** The recorded reference-set size for this
   start flips from 32 (the `maxY=90` era) to 2 (the `maxY=84` era), and the one
   recorded reference-state row for origin `-32,3` is `size=1 n=2 minN=2` on a
   `LongOpenHashSet` targeting chunk `-32,3`. Vanilla's
   `ChunkAccess.addReferenceForStructure` builds a `LongOpenHashSet` while
   `unpackStructureReferences` rebuilds from the serialized `long[]`, and fastutil
   scans backing slots high to low, so a reload can flip iteration order. That
   order feeds the shared structure RNG, which changes placed blocks. Check
   whether Kestrel's rebuilt table for the affected chunk has Vanilla's iterator
   order at that cutpoint, not just the same membership.

Cheapest first experiment: dump the piece list at the failing pass (mechanism 1)
and the reference iteration order (mechanism 2) side by side, since the trace
already pins the exact seq and chunk for both.

## Exact commands used

```
sed "s|^core/build/classes/java/main|$PWD/core/build/classes/java/main|" ~/kestrel-run/cp.txt > /tmp/cp-m.txt
java -Xmx8G -Dkestrel.datapack=$HOME/kestrel-run/packdump/data -cp "$(cat /tmp/cp-m.txt)" \
  dev.kestrel.worldgen.FeaturesTraceReplay /tmp/traces/w00 --structures
java -Xmx8G -Dkestrel.datapack=$HOME/kestrel-run/packdump/data -cp "$(cat /tmp/cp-m.txt)" \
  dev.kestrel.worldgen.LiveStructuresTraceCheck /tmp/traces/w00 --replay-chunk-loads --replay-lighting
```

## Update, 2026-09-23 evening: the remainder is a feature scatter, not the structure lifecycle

The two blocks are not structure pieces at all. They come from the
`minecraft:brown_mushroom_taiga` placed feature's scatter, and the structure
lifecycle is exonerated.

Evidence, all from the merged build (`f9a81be`) and the w00 trace:

1. **The merged build still fails identically.** The live gate on the merged
   classes reports the same two cells
   (`LIVE_FEATURES_DIFF origin=-32,3 chunk=-33,3 pos=(-513,64,52)` and `z=56`,
   `vanilla=Block{minecraft:air} kestrel=Block{minecraft:brown_mushroom}`,
   `diffs=2`), and the fix's own effect is visible in the same run: the replay
   used the rebuilt start. So the fix is right and orthogonal to this diff.
2. **Vanilla's own snapshots show what it placed at y=64.** Decoding
   `chunks/<hash>.bin.gz` at all four recorded lifecycle points of chunk
   `-33,3` (`a3234da4` pre-own-features, `18915f49` post-own-features,
   `51cf99a0` post-light, `049653aa` final), the y=64 layer carries mushroom
   ids `2248`/`2249` at exactly five cells: `(-513,50)`, `(-515,57)`,
   `(-513,61)`, `(-518,60)`, `(-514,62)`. Kestrel has those same five and two
   more at `(-513,52)` and `(-513,56)`, which is exactly the gate's `diffs=2`.
   The two extra cells were never non-air in vanilla at any of the four points.
3. **The writer is a feature, not a structure.** Diffing vanilla's snapshot
   before and after the failing pass (`seq 5233` ENTER to `seq 5234` COMPLETE,
   both at origin `-32,3`) shows the three `y=64` mushroom cells appearing
   during that pass, and `feature-places.tsv` records a `brown_mushroom` and a
   `red_mushroom` application in step 9 for chunk `-32,3` (decorationSeed
   `3109975894079548153`) and for chunk `-33,3` (decorationSeed
   `2252551414759406729`).
4. **The feature is a 96-attempt scatter.** `brown_mushroom_taiga` is
   `rarity_filter(1/4)` -> `in_square` -> `heightmap MOTION_BLOCKING` ->
   `biome` -> `count 96` -> `random_offset` (xz trapezoid -7..7, y trapezoid
   -3..3) -> `block_predicate_filter(matching_block_tag air)`. One application
   therefore attempts up to 96 placements inside a plus or minus 7 block window
   and keeps the ones that land on air. A two cell difference is exactly the
   signature of a scatter whose inputs or RNG stream differ slightly: the
   predicate means the outcome also depends on what the structure placement and
   the earlier features already wrote into those cells.
5. **The replay computes its own seeds and never reads the recorded ones.**
   `feature-seeds.tsv` is written by the agent and consumed by nothing in
   `core/src` (the same class of unused-capture artifact as the removed
   `height-query-chunks.tsv`). `HeadlessNoise` derives the decoration seed and
   each `setFeatureSeed(decorationSeed, globalIndex, stepIndex)` itself
   (HeadlessNoise lines 1189 and 1229), mirroring vanilla's own sequence, and
   sorts the per-step feature indices before seeding.

So the open question is now: for that one feature application, which of
Kestrel's inputs differs from Vanilla's. The candidates, in the order worth
checking: the per-step feature index assigned to this feature (a shift moves
every later feature's seed, and a shift can come from the possible-biome set
collected from the 3x3), the `MOTION_BLOCKING` heightmap value at the scatter's
base column, the RNG draw sequence inside the offset modifiers, and the air
predicate's view of the chunk at that moment (write order between the structure
block and the features).

None of these can be settled from the trace, because it records the feature
application and not the individual placements. The decisive oracle is a
same-seed capture with the agent instrumented to record every block a feature
writes (position, state, feature index, step), which can then be diffed against
Kestrel's writes for the same window. The structure-piece instrumentation built
for the earlier hypothesis (`diag/struct-pieces`, `structure-pieces.tsv`) is
still useful as a cross-check that the rebuilt pieces are faithful, but it is no
longer the critical path.
