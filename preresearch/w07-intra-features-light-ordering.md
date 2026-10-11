# w07's ten missing mushrooms: a cross-thread light completion inside a pass

Status: diagnosed 2026-09-23; both gates now shift an intra-pass completion to
before the pass and check the legality bound. The unchanged w07 live capture
passes 4292 chunks and 421920768 cells with zero diffs on 2026-09-24. The
legacy trace lacks the structure lifecycle files needed for a strict verdict.
Exact placement from `intra-features-timeline.tsv` is not implemented.

## What the capture shows

Chunk T = `-14035,-16914`, mushrooms at y=67/68, placed by vanilla during T's
own FEATURES pass. From the trace:

- every event referencing T: seq 8229 ENTER / 8230 COMPLETE is T's own pass
  (its own ref status is `minecraft:carvers`, the parent status, as expected for
  an origin about to run FEATURES), and the snapshot at 8230 is the first one
  holding the ten mushrooms;
- T's own `initialize_light` is after=8262 and `light` after=8332, both after
  its pass, so T's own light registration cannot be what the scatter saw;
- T's eight neighbours carry no light rows at all until after=8229, where
  `-14036,-16913`, `-14036,-16914` and `-14036,-16915` get their first
  `initialize_light`;
- there are no chunk-load rows for T's 3x3 anywhere in the window, so no reload
  can explain it.

## The timeline shows a real cross-thread interleaving

`intra-features-timeline.tsv` (camp1/w07, 43 MB) is one ordered stream of the
pass's work. The slice covering T's pass (`order` 493269..493418) is:

```
493269..493280  FEATURE ENTER/EXIT      thread=Worker-Main-4  origin=T   steps 1,2,3
493281..493314  INITIALIZE_LIGHT_PRE    thread=Worker-Main-6  origin=T   17 targets
493315..493418  FEATURE ENTER/EXIT      thread=Worker-Main-4  origin=T   steps 6,7,8,9,10
```

Two facts decide the case:

1. The light rows run on **Worker-Main-6** while the pass runs on
   **Worker-Main-4**. Vanilla's worldgen thread does not wait for the light
   executor, so the completion genuinely landed while the pass was executing.
   The `activeOrigin` column on those rows is the worldgen thread's current
   origin, which is why they look like they belong to T.
2. Three of the 17 targets are exactly T's west column (`-14036,-16913/14/15`).
   Those are the completions whose `updateSectionStatus` bump allocates a
   zero-filled sky layer for T's own sections.

`INITIALIZE_LIGHT_TASK` rows are the submissions and they run on
**Worker-Main-4**: `-14036,-16915` at 492521, `-14036,-16914` at 492647,
`-14036,-16913` at 493017, all before T's pass ENTER at 493269. So the light
executor *could* legally have drained them before T's pass started.

## The legality bound needs no timeline

Moving a completion earlier is a schedule Vanilla could have produced exactly
when the target's own FEATURES status, which gates its INITIALIZE_LIGHT task,
was already published. `events.tsv` proves that: the target's FEATURES COMPLETE
sequence must precede the origin's pass ENTER sequence. That is a cheap check
available to both gates, and it fails closed when the completion could only have
happened after the pass started.

## What is still a choice, and how it is settled

The captured completion landed *somewhere inside* the pass. Applying it before
the pass makes it visible to more of the pass than Vanilla saw; applying it
after makes it visible to less. Both are legal orders; only one reproduces the
captured blocks. The gate settles it empirically: the comparison is
block-for-block, so a wrong choice shows up as diffs rather than as a silent
pass. Exact placement needs `intra-features-timeline.tsv`, which records the
position of every light row against every feature placement.


## Why that is enough to explain it

`SkyLightSectionStorage.getLightValue` reads 15 unless a column has a registered
top section, and `updateSectionStatus` (called per non-empty section by
INITIALIZE_LIGHT) also bumps a neighbour count on the 26 surrounding sections,
allocating a zero-filled layer for a neighbour chunk that never ran a light
stage of its own. Kestrel's `FeaturesLightingCheck` asserts exactly this
behaviour. So the three neighbours' INITIALIZE_LIGHT at cutpoint 8229 registers
section 4 of T, which turns every read in T's section 4 from 15 into 0, and
`MushroomBlock.canSurvive` wants a value below 13.

For vanilla to have placed the ten mushrooms, that registration has to be
visible before the scatter ran inside T's pass, i.e. the light executor
completed it *while* T's pass was executing. The trace can only say
"afterEventSeq=8229", which is a cross-thread stamp: the light executor is a
different thread, so the completion landed after the worldgen thread passed
stamp 8229 and before it finished the pass.

## Why Kestrel could not reproduce it

The replay applies light rows at cutpoints *between* feature passes:
`replayLightingEvents(root, previousEventSeq, ...)` runs before the next event's
work, so a row stamped 8229 lands after T's pass has already completed. The
scatter then reads 15 and places nothing.

The existing guard did not catch this case:
`assertIntraLightingBoundaryEquivalent` only rejects an intra-FEATURES
INITIALIZE_LIGHT when the *target* chunk changed in a light-relevant way between
the interval boundary snapshots. Here the target chunk (`-14036,-16914`) changes
nothing relevant; what changes is the *brightness* of the active origin's own
chunk, which no snapshot records. So the case slipped through as "equivalent"
and was applied at the wrong end of the interval.

## The instrument that can express it

`intra-features-timeline.tsv` (camp1/w07 capture on the bench PC,
`C:\Users\bench\bench\camp1\w07\trace`, 43 MB) is a single ordered stream of the
pass's work:

```
order   kind                   phase  thread         activeOrigin  target
493269  FEATURE                ENTER  Worker-Main-4  -14035,-16914
493281  INITIALIZE_LIGHT_PRE   ENTER  Worker-Main-6  -14035,-16914 -14036,-16915
...
493315  FEATURE                ENTER  Worker-Main-4  -14035,-16914
```

Rows: FEATURE (ENTER/EXIT per applied feature, with stepIndex and the
`FeatureSorter` global featureIndex), INITIALIZE_LIGHT_TASK (the submissions,
on the worldgen thread, no activeOrigin) and INITIALIZE_LIGHT_PRE (the
executions, on a light thread, stamped with the worldgen thread's current
origin). So the capture records where inside a pass each completion landed,
relative to the applied features. No re-capture is needed.

## The state of the fix

- Both gates now apply a completion stamped with a pass's own ENTER sequence
  *before* that pass instead of after it, and check the FEATURES-COMPLETE
  legality bound above; an illegal case fails closed with a distinct message
  rather than being applied.
- That is a legal order and is validated block-for-block, but it is not exact:
  it makes the completion visible to the whole pass. Exact placement needs the
  timeline, and the generator already exposes the hook it needs
  (`beforeFeatureDebug` / `afterFeature`, invoked per placement with stepIndex
  and globalIndex), so driving the timeline is a matter of building the
  per-origin order from the file and draining pending light rows at the
  recorded position.
- The first revised live replay had three final `cave_air` versus `air` cells,
  although every FEATURES window was exact. Compact `SectionStore` publication
  discarded a section whose blocks all counted as air and lost those block
  identities. Keeping any section with a noncanonical air state removed the
  final three diffs. This was a store publication defect, separate from the
  intra-pass lighting order.

## Consequences for the gate

- w00 is a different defect (a reload does not clear the light storage). See
  `preresearch/w00-mushroom-light-dependency.md`.
- A window whose only difference is this class can now pass, but a window where
  the completion genuinely landed after an early light-dependent placement will
  still report diffs until the timeline replay lands. Such diffs are this class,
  not a generation defect: check for an INITIALIZE_LIGHT_PRE on a light thread
  inside the failing origin's pass before treating a block mismatch as real.
