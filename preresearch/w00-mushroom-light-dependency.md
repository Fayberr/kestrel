# w00's two extra blocks: an all-zero sky layer, registered too early

Status: **resolved and fixed** 2026-09-24. The layer came from a neighbour's
INITIALIZE_LIGHT bump, and Vanilla had already torn it down on the unload that
preceded the reload the capture records at seq 5232. The replay now reproduces
that reload. Updated 2026-09-24 (candidate fix 1 below is disproven; the
reload model is measured from the decompiled `SerializableChunkData` and
`ThreadedLevelLightEngine`).

## The read semantics (decompiled 26.2)

`SkyLightSectionStorage.getLightValue(blockNode, updating)`:

```java
int topSection = sections.topSections.get(zeroNode); // default = currentLowestY
if (topSection != sections.currentLowestY && sectionY < topSection) {
    DataLayer layer = getDataLayer(sections, sectionNode);
    // ... layer.get(x, y, z)  -> an all-zero layer reads 0
} else {
    return updating && !lightOnInSection(sectionNode) ? 0 : 15;
}
```

So for an ordinary read (`updating == false`) a column reads **15** when it has
no registered top section, and **0** when it has a registered but all-zero data
layer. `MushroomBlock.canSurvive` wants `getRawBrightness(pos, 0) < 13`, so 15
rejects every candidate and 0 accepts them. There is no third answer.

## Where an all-zero layer comes from

`LayerLightSectionStorage.putSectionState(sectionNode, state != 0)` calls
`initializeSection(sectionNode)`, which allocates the layer via the subclass
hook `SkyLightSectionStorage.createDataLayer(sectionNode)` and then
`onNodeAdded(sectionNode)` (the latter is what registers the `topSections`
entry). `createDataLayer` is:

```java
return this.lightOnInSection(sectionNode) ? new DataLayer(15) : new DataLayer();
```

`lightOnInSection` is `columnsWithSources.contains(zeroNode)`, populated only by
`setLightEnabled(zeroNode, true)`. Kestrel's lifecycle finishes a chunk's
INITIALIZE_LIGHT with `engine.setLightEnabled(pos, false)`, so every layer
registered for a Kestrel-initialized chunk is all zeros: from that moment every
read inside that chunk below its top section returns 0.

The entry point is `LevelLightEngine.updateSectionStatus(sectionPos, false)`,
which `WorldgenLightLifecycle.initializePreUpdate` issues once per non-empty
section of the chunk it is initializing. Net effect:

- chunk that has been through INITIALIZE_LIGHT: reads 0 below its top section;
- chunk that has not: reads 15.

## The divergence, measured

`getRawBrightness` reads captured through the RegionView proxy while replaying
w00's -32,3 FEATURES pass (seq 5233 ENTER, 5234 COMPLETE), filtered to chunk
-33,3, y 60..70, all 17 reads inside chunk -33,3:

- reads 1-12: `skyLayer=ABSENT`, `value=15`;
- reads 13-17: `skyLayer=EMPTY`, `value=0`, including the two cells both gates
  report, `(-513,64,52)` and `(-513,64,56)`.

So section 4's sky layer for chunk -33,3 appears *during* the -32,3 pass, and
from that moment the `brown_mushroom_taiga` scatter accepts candidates.

What chunk -33,3 should be at that moment:

- its own FEATURES event is seq 5235/5236, immediately *after* the -32,3 pass;
- its recorded `initialize_light` is afterEventSeq 5246 and `light` 5288;
- every captured ref for it before seq 5233 carries `minecraft:carvers`.

So vanilla cannot have registered a layer for it yet, and its columns read 15.
Kestrel has one, and reads 0.

## Resolution: the registration is a neighbour's bump, and the unload takes it away

Chunk `-33,3` never ran a light stage before the failing pass, so the layer is
not its own. It is a side effect of `updateSectionStatus` bumping the 26
sections around every section a *neighbour* registers, which
`FeaturesLightingCheck` asserts as intended behaviour. The neighbour is
`-33,2`: it completes INITIALIZE_LIGHT at cutpoint 3346 (recorded for `-33,2`,
`-32,2` and `-34,2` together), and from then on `-33,3`'s section 4 carries an
all-zero layer.

What makes it *not* visible to the failing pass is a reload. `chunk-loads.tsv`
records the whole 3x3 around the origin being read back at cutpoint 5232, one
event before the `-32,3` pass at 5233, with the saved statuses
`initialize_light` for z=2, `carvers` for z=3 and `biomes` for z=4, and
`worldgen-light-events.tsv` shows those three chunks being initialised again
only at 5246. Vanilla's unload is what cleared the layer:

```java
// ThreadedLevelLightEngine.updateChunkStatus(pos), called by ChunkMap.scheduleUnload
super.retainData(pos, false);
super.setLightEnabled(pos, false);
for (sectionY in [getMinLightSection(), getMaxLightSection())) {
   queueSectionData(BLOCK, SectionPos.of(pos, sectionY), null);
   queueSectionData(SKY,   SectionPos.of(pos, sectionY), null);
}
for (sectionY in [levelMinSectionY, levelMaxSectionY]) {
   updateSectionStatus(SectionPos.of(pos, sectionY), true);
}
```

Kestrel's replay restored heightmaps and structure data for a captured reload but
never touched the light storage, so the bumped layer survived and every read in
`-33,3` kept returning 0 instead of the unregistered 15.

The read-back half matters too: `SerializableChunkData.pack` writes a layer only
when it is not empty, so an all-zero layer is absent from the form and stays
unregistered after the reload, while a form saved below LIGHT carries no light at
all. `WorldgenLightLifecycle.reloadChunkData(pos, formCarriesLight)` reproduces
both halves, and both gates call it per captured load.

## Candidate fix 1 (disproven): mirror ServerChunkCache.getChunkForLighting

Implemented, compiled, and tested: `LightChunkGetter.getChunkForLighting` gated
on `ChunkStatus.INITIALIZE_LIGHT.getParent()` exactly as
`ServerChunkCache.getChunkForLighting` does (a CARVERS chunk is invisible).

Verdict: the strict gate still fails at the same two cells, byte for byte the
same diff. That is expected in hindsight: the layer is registered by
`updateSectionStatus`, which never consults the chunk getter, so gating the
getter cannot remove it. Whether the gate is still worth keeping for vanilla
fidelity is a separate question from this bug.

## Instrumentation

`LightReadDiag.op(tag, engine, sectionX, sectionY, sectionZ)` writes `OP` lines
into the same dump as the reads, and `WorldgenLightLifecycle` now probes around
every lifecycle step: `engine-new`, per-section `sectionStatus`, `initAll-pre`
and `initAll-post` per candidate, `initAll-drained`, `initAll-disable` and
`initAll-disabled`, `lightAll-propagate` and `lightAll-propagated`, and
`drain-pre#n` / `drain-post#n` for each `runLightUpdates` iteration. The proxy
also emits `read-pre` / `read-post` around a watched read, so a layer created by
the read itself is distinguishable from one created between reads.

Knobs: `-Dkestrel.lightReadDump=<path>`, `-Dkestrel.lightReadChunkX/-ChunkZ`,
`-Dkestrel.lightReadYMin/-YMax`, `-Dkestrel.lightProbeSectionY`.

## What is already excluded

`FeatureIndexSetDump` compares Kestrel's per-origin feature index set and
decoration seed against the trace's `feature-places.tsv` rows, which are exactly
vanilla's per-origin set. Over the whole w00 replay up to the failure: 19,949
`(origin, step)` pairs, zero set mismatches and zero seed mismatches, including
origin `-32,3` (index 80 = `brown_mushroom_taiga`, decoration seed
3109975894079548153) and origin `-33,3` (index 80, decoration seed
2252551414759406729). The possible-biome set, the index mapping, the seeding and
the height queries are all exact. The biome filter is excluded as well: every
`getBiome` read in the -32,3 pass is `minecraft:taiga`, and taiga lists
`brown_mushroom_taiga`.

## Tools built for this

- `FeatureIndexSetDump <seed> <cx> <cz> [steps]`: per-origin index set, biome
  attribution, biome reads.
- `TraceSnapshotDump <traceDir> <cx> <cz> [minY] [maxY] [x:y:z,...] [filter]`:
  every recorded snapshot of a chunk with the cells that changed between them,
  id to state names, optional substring filter.
- `LightReadDiag` + the `getRawBrightness` case in the RegionView proxy: reads,
  layer state, and the lifecycle op stream.
- `view.featureSeedDebug` plus `-Dkestrel.featureSeedDump` in
  FeaturesTraceReplay: per-origin applied-feature (step, index, seed, key).

## Follow-ups

- On the unchanged w00 capture, the final strict gate passes 4324/4324 scored
  chunks with zero diffs, and the live gate passes 4324 chunks and 425066496
  cells with zero diffs in the final recheck. A later full-form load
  at event 3812 has no light completion at that cutpoint: the read restores
  its saved layers, and the next captured completion must still be replayed.
- The per-load `unloadChunkData` costs a full section sweep plus a drain; if the
  campaign's throughput matters, skip it when the chunk has no light state at
  all (see the perf note in the commit).
- w07 (camp1) is a different defect: an intra-FEATURES light completion on a
  light thread. See `preresearch/w07-intra-features-light-ordering.md`.
- Audit which other configured features in this pack have a light-dependent
  `canSurvive`, since each one inherits this sensitivity.
