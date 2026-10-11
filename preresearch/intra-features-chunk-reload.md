# Intra-FEATURES chunk reloads: why w03 cannot be replayed today

## The failure

Campaign window w03 fails both Tier-R gates with

```
chunk reload occurred inside active FEATURES origin=0,18 afterEventSeq=6407 chunk=11,-6;
... needs an intra-FEATURES reload hook
```

raised by `FeaturesTraceReplay.replayChunkLoads` and the matching guard in
`LiveStructuresTraceCheck`. Both guards fail closed on a `chunk-loads.tsv` row
whose `activeOriginX` column is non-empty.

## What the capture records

`FeaturesTrace.chunkRead` (harness/agent) writes one row per
`SerializableChunkData.read` with the columns

```
afterEventSeq  activeOriginX  activeOriginZ  x  z  status  chunkClass  referenceSets
```

`afterEventSeq` is the FEATURES ENTER/COMPLETE sequence counter at the moment of
the read, and `activeOrigin` is the global origin of a FEATURES pass that was in
flight at the same instant. The comment on that method is explicit about why the
global is needed: `SerializableChunkData.read` can run on a different worker from
`generateFeatures`, so a ThreadLocal origin would not see it.

An empty `activeOrigin` therefore means "this reload happened at a cutpoint
between two FEATURES passes", and the replay can apply it at that cutpoint. A
non-empty `activeOrigin` means "this reload happened at some point strictly
inside the pass for that origin", and the row says nothing more than that.

## What w03 actually contains

w03 has 22 intra-FEATURES reload rows, all at `afterEventSeq=6407` with active
origin `0,18`:

```
11,-6   minecraft:full              ImposterProtoChunk   refs=[]
11,-2   minecraft:full              ImposterProtoChunk   refs=[]
...
12,3    minecraft:carvers           ProtoChunk           refs=[]
12,5    minecraft:structure_starts  ProtoChunk           refs=[]
```

Every one of them is at `x=11` or `x=12`, a Chebyshev distance of 11 or more
from the active origin `0,18`, and every one carries an empty reference set.
These are chunks of an unrelated region that a background worker loaded while
the pass at `0,18` happened to be in flight on another thread.

## Two different cases

A FEATURES pass at origin `O` reads and writes its radius-1 WorldGenRegion, and
its StructureManager is backed by the shared radius-8 STRUCTURE_STARTS /
REFERENCES window (`HeadlessNoise.structureWindow`, dx/dz loops over -8..8).
`startsForStructure` follows references, and `ChunkGenerator.createReferences`
only ever writes references inside that same radius. Radius 8 is therefore the
complete set of chunks one pass can observe.

That splits intra-FEATURES reloads in two:

**Outside radius 8 (w03's case).** The active pass never reads the reloaded
chunk, so applying the reload at the pass boundary is indistinguishable from
applying it at the exact instant it happened, both for that pass and for every
later one. This is now admitted by
`FeaturesTraceReplay.reloadIsUnobservableByActiveOrigin`, used by both gates.
This is not a weakening: it is exactly the subset whose position inside the pass
is unobservable.

**Inside radius 8.** Not replayable from the trace as captured, and both gates
still fail closed. A reload rebuilds the chunk's structure starts and its
fastutil reference tables (`HeadlessNoise.roundTripStructureData`), so features
placed before and after it can see different structure state. w00's
`intra-features-timeline.tsv` has 520,876 `FEATURE` rows across 9,736 passes,
about 53 features per pass, and `chunk-loads.tsv` says only which pass was in
flight, not which of those 53 points the reload fell between. Choosing a
boundary would be a guess, not a replay.

## What the capture must add for the remaining case

The trace already has the mechanism, and already uses it for the other events
that can land inside a FEATURES pass. `intraFeaturesTimelineSequence` is a
global `AtomicLong` stamped onto every `FEATURE` ENTER/EXIT row and onto every
`INITIALIZE_LIGHT_PRE` / `INITIALIZE_LIGHT_TASK` row of
`intra-features-timeline.tsv`. `lighting-events.tsv`, `light-stage-events.tsv`
and `worldgen-light-events.tsv` all carry that stamp as an `order` column
alongside `afterEventSeq`, which is what lets the live gate place a light
completion between two features.

`chunk-loads.tsv` has no `order` column. To make an inside-radius reload
replayable the capture must:

1. Stamp `FeaturesTrace.chunkRead` with
   `intraFeaturesTimelineSequence.incrementAndGet()` and add it to
   `chunk-loads.tsv` as an `order` column next to `afterEventSeq`, matching the
   three lighting files.
2. Emit a matching row into `intra-features-timeline.tsv` under a new `kind`
   value (`CHUNK_LOAD`), so the reload is totally ordered against the `FEATURE`
   ENTER/EXIT rows of the pass it falls inside rather than merely attributed to
   it.

Both stamps must be taken under the monitor that `chunkRead` and `record`
already share, so the order value and `activeOrigin` stay one coherent
observation.

The replay would then also need a per-feature stepping callback in the FEATURES
driver to apply `roundTripStructureData` and `roundTripHeightmaps` at the
resolved point. Today the replay invokes vanilla's `applyBiomeDecoration` as a
single call, so there is no seam to interpose at. That is a real change to how
the replay drives FEATURES, not just a new trace column.

## Status

The unobservable case is implemented and unblocks w03. The inside-radius case
is not implemented and both gates still fail closed on it, because the capture
cannot currently produce a row that says where inside the pass the reload
landed. A campaign window that produces such a row must be reported as
unreplayable rather than passed or skipped.
