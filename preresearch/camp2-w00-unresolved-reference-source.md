# camp2 w00: a captured structure reference names a start the replay grid does not have

Status: fixed 2026-09-24 (start-only reload round trip); camp2 w00 strict now PASS.
First seen 2026-09-23 in
camp2's w00 window (`C:\Users\bench\bench\camp2\w00`), strict gate only; the
same window's live gate PASSED.

## The failure

```
COMPARE seq=7843 phase=ENTER origin=-23,25 blocks=0 targetFamily=0
java.io.IOException: cannot resolve structure-reference source at -18,21 index=1
  at FeaturesTraceReplay.resolveStructureForReferenceState(FeaturesTraceReplay.java:1756)
  at FeaturesTraceReplay.syncStructureReferenceLifecycle(FeaturesTraceReplay.java:1797)
```

It aborts the whole window: the strict gate cannot produce a verdict, so the
window is reported FAIL even though nothing was shown to differ in the blocks.

## What is known

- `resolveStructureForReferenceState` matches a captured
  `structure-reference-state` row against the replay grid chunk at the
  reference's source position, requiring an `allStarts()` entry in that chunk
  with the same structure class and the same `getBoundingBox().toString()`
  (plus `getChunkPos()` equal to the source position). Here nothing matched.
- `chunk-loads.tsv` for that window has exactly one row for `-18,21`: cutpoint
  7690, status `minecraft:structure_starts`, class `ProtoChunk`. That is 153
  events before the abort.
- A `structure_starts` form carries no reference sets, so the strict path's
  reload handling prints `CHUNK_RELOAD_NO_REFERENCES` and skips the structure
  round-trip for it. That incorrectly leaves the live jigsaw start's old box
  in the replay grid; `roundTripStructureStarts` would rebuild it from its
  serialized form without dropping the start.
- The failing reference is index=1, i.e. the second reference of that origin's
  captured state, so it is not an ordering artifact of the first entry.

## Cause identified from the captured source sequence

The 132 source rows for `-18,21` keep the jigsaw start's box at maxY 99
through origin `-23,15` at event 5701, then show maxY 93 at origin `-23,25`
at event 7843, the failing origin. The single captured load of `-18,21` is
at event 7690 with status `minecraft:structure_starts`, exactly between those
two observations. Deserializing a jigsaw start recomputes its piece boxes;
that shorter box is the same mechanism measured in camp1 w00.

The strict replay's `replayChunkLoads` returned early whenever a form lacked
structure references, so it skipped `roundTripStructureStarts` as well as
`roundTripStructureReferences`. A `structure_starts` form has starts but no
references. The candidate fix always round-trips starts for forms at or past
STRUCTURE_STARTS and round-trips references only when the capture says the
form carried them. The full camp2 w00 strict replay on the starts-only
candidate classes finished on the bench PC at 10:27 local on 2026-09-24 with
`TIER_R VERDICT: PASS`: 4309/4309 chunks exact, 423591936 cells, 0 diffs
(`C:\Users\bench\bench\tierr\camp2-w00-starts-candidate.log`). With the
live gate's earlier PASS this window is green on the fixed code.

The isolated `StructureStartRoundTripCheck 3428864282687571407 -18 21`
reproduces the captured maxY 99 to 93 change exactly, with 75 pieces before
and after. This confirms the start half of the reload is sufficient to explain
the unresolved source; the full replay still decides whether other diffs
remain in the window.

## Earlier hypotheses

1. The replay's grid chunk `-18,21` has no start for that structure at all, i.e.
   a real placement divergence (Kestrel did not place a structure Vanilla did).
   Test: dump the replay's `allStarts()` keys and boxes for `-18,21` at that
   cutpoint next to the captured `structure-reference-state` row.
2. The start is there but its bounding box or class differs, e.g. Kestrel
   stored the start under a different placement chunk. Test: same dump, compare
   boxes rather than keys.
3. The captured reference names a source chunk that Vanilla had generated
   *later* than the replay's snapshot of it, so the grid chunk is at an earlier
   status. Test: the chunk's status in the grid at that cutpoint.

A cheap first step is a `TraceSnapshotDump`/`allStarts` dump for `-18,21` in
that window's trace, which exists on the bench PC only
(`camp2/w00/trace`); the Pi has camp1's traces, not camp2's.

## Why it matters for the campaign

The campaign's verdict requires both gates. A hard abort like this makes the
window unverifiable rather than mis-compared, so it inflates the failure count
without evidence of a parity difference. Until it is diagnosed, a window that
aborts here should be reported as this class, not as a block mismatch.
