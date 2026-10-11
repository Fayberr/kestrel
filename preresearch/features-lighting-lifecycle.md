# Tier R FEATURES lighting lifecycle: measured model

Status: measured against the Vanilla 26.2 runtime oracle. The previous binary chunk-status hypothesis is rejected.

## Facts established

1. Vanilla 26.2 uses the real shared `ThreadedLevelLightEngine` during `WorldGenRegion` FEATURES calls.
2. `ChunkStatus.FEATURES` precedes `INITIALIZE_LIGHT` and `LIGHT`.
3. Ordinary pre-light FEATURES block writes do not themselves update the normal light engine.
4. The light engine nevertheless already contains sky-light storage for some chunk columns while other columns are still absent.
5. `persistedStatus` alone is NOT the discriminator.

## Runtime oracle correlation

Captured WorldGenRegion mushroom-survival queries:

| count | sky | raw | persistedStatus | lightCorrect | columnRegistered | DataLayer |
|---:|---:|---:|---|---|---|---|
| 295 | 0 | 0 | CARVERS | false | true | true |
| 285 | 15 | 15 | CARVERS | false | false | false |
| 72 | 0 | 0 | FEATURES | false | true | true |
| 29 | 0 | 0 | INITIALIZE_LIGHT | false | true | true |
| 3 | 15 | 15 | FEATURES | false | false | false |
| 2 | 1 | 1 | INITIALIZE_LIGHT | false | true | true |

This disproves a direct rule such as `CARVERS -> 15` / `INITIALIZE_LIGHT -> actual light`. Both bright and dark queries occur while the queried chunk still reports CARVERS.

The meaningful state is the shared sky-light storage itself.

## Exact residual oracle

Original false-positive Kestrel mushrooms:

`(103,64,288)`
- origin: `(6,17)`
- queried chunk: `(6,18)`
- status: CARVERS
- sky/raw: 15/15
- canSeeSky: true
- topSection: -5
- currentLowestY: -5
- columnRegistered: false
- DataLayer present: false
- Vanilla survival: false

`(107,64,291)`
- origin: `(6,17)`
- queried chunk: `(6,18)`
- status: CARVERS
- sky/raw: 15/15
- canSeeSky: true
- topSection: -5
- currentLowestY: -5
- columnRegistered: false
- DataLayer present: false
- Vanilla survival: false

Representative legitimate dark mushrooms broken by the rejected constant-15 experiment:

`(98,63,124)`, origin `(6,7)`
- status: CARVERS
- sky/raw: 0/0
- canSeeSky: false
- topSection: 6
- currentLowestY: -5
- columnRegistered: true
- DataLayer present: true
- Vanilla survival: true

`(98,64,128)`, origin `(6,7)`
- status: CARVERS
- sky/raw: 0/0
- canSeeSky: false
- topSection: 6
- currentLowestY: -5
- columnRegistered: true
- DataLayer present: true
- Vanilla survival: true

`(98,64,284)`, origin `(6,17)`
- status: CARVERS
- sky/raw: 0/0
- canSeeSky: false
- topSection: 6
- currentLowestY: -5
- columnRegistered: true
- DataLayer present: true
- Vanilla survival: true

The complete failed constant-15 residual family is 27 cells: chunk `(6,7)` = 6, `(6,8)` = 8, `(6,17)` = 13.

## Correct interpretation

`SkyLightSectionStorage.getLightValue` distinguishes an absent/unregistered sky column from a registered column with materialized light data.

For the measured Y≈64 cases:
- unregistered column (`topSection == currentLowestY`, no DataLayer) -> sky 15;
- registered column with a DataLayer -> read the stored nibble value (0 in the mushroom cases, occasionally 1 in other measured INITIALIZE_LIGHT rows).

Therefore the rejected constant-15 engine was wrong because it erased the global sky-storage registration frontier.

The remaining architectural question is not “what brightness belongs to CARVERS?” but which chunk/section columns have been registered in the shared Vanilla light storage by the time a particular FEATURES query executes, and what nibble data has propagated into them.

## Why CARVERS columns can already be registered

The runtime oracle proves this state exists, so registration cannot be inferred from the queried chunk's own persisted status. The likely mechanism is shared light-engine initialization/propagation from other chunks that have advanced further: section storage can exist for neighbouring columns even while the queried ProtoChunk itself remains at CARVERS.

This must be reproduced by lifecycle/storage behavior, not by a status lookup table.

## Trace capability

`harness/agent/FeaturesTrace.java` already stores each dependency as `chunkX,chunkZ,persistedStatus,snapshotHash` at every FEATURES ENTER/COMPLETE.

`FeaturesTraceReplay` currently discards the status component while parsing the reference.

Those statuses are still useful as lifecycle observations/triggers, but the oracle proves they are not sufficient as direct brightness values.

## Implementation direction

1. Preserve the captured dependency status in the replay instead of discarding it.
2. Maintain one shared lighting state across the replay.
3. Use lifecycle observations to advance that state; do not create an independent per-RegionView constant engine.
4. Registration of neighbouring light sections must emerge from the light-storage/propagation model.
5. `RegionView.getLightEngine()` should query that shared state.
6. Use the same Kestrel `LightSection` / future propagation core intended for the production LIGHT stage where possible.
7. Build the corrected `FeaturesLightingCheck` from the measured oracle above so both `EMPTY` and constant-15 fail.
8. Only claim completion after normal fail-closed Tier R reaches exactly zero.

## ScalableLux relationship

ScalableLux 26.2 remains a performance/architecture reference, not the FEATURES correctness oracle. Vanilla 26.2 runtime measurements remain authoritative for intermediate worldgen lighting semantics.