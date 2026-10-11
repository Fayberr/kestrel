# Optimization #4 grounding: mob AI/brain/sensors/POI caches

Status: ANALYSIS, grounded in the real decompiled vanilla 26.2 source (not
secondary-source guesswork). Precedes any Kestrel implementation - per the
plan's own rule, nothing lands until it's differential-tested against this
analysis's baseline via `harness/`.

Method: decompiled the real classes with Vineflower 1.11.1 against
`preresearch/vanilla-26.2/server.jar`'s nested `server-26.2.jar` (the
deobfuscated class files themselves - MC 26.x ships with real names, no
mapping step needed). Read in full: `Sensor` (base, 99 lines), `Brain`
(451 lines), `PoiManager` (262 lines), `NearestBedSensor`,
`SecondaryPoiSensor`, `PlayerSensor`, `VillagerHostilesSensor`,
`NearestLivingEntitySensor`, `GolemSensor`, `NearestVisibleLivingEntities`
(71 lines), `AcquirePoi` (141 lines), `Villager` (brain wiring +
`customServerAiStep`). Surveyed via javap: `Level` entity-query overloads,
`LevelHeightAccessor` section-Y accessors, `TicketType` set.

## What vanilla already does well (don't re-fight this)

1. **Sensors are rate-limited by construction.** `Sensor.tick` is a gate,
   not a scan: `--timeToTick <= 0` else return. Default `scanRate = 20`
   (1/sec), `NearestBedSensor` 20, `SecondaryPoiSensor` 40, `GolemSensor`
   200 (1 per 10 sec). Per-tick `Brain.tick` costs one decrement + branch
   per sensor when idle - the "sleeping" optimization already exists as the
   DEFAULT shape. **Do not build "sensor sleeping" as if it were missing;
   the win is in the scan bodies, not the cadence.**
2. **Sensor start times are jittered.** `Brain` constructor calls
   `randomlyDelayStart(randomSource)` per sensor (`nextInt(scanRate)`), so
   N villagers' 20-tick scans desynchronize instead of landing on the same
   tick. Any Kestrel batching must PRESERVE this desync (parity: scan
   timing is observable via behavior changes) - batch the mechanism, never
   re-phase the schedule.
3. **Entity AABB queries are section-indexed.** `NearestLivingEntitySensor`
   calls `level.getEntitiesOfClass(LivingEntity.class, inflatedBB, ...)`
   which routes through the section-indexed `LevelEntityGetter` (established
   in collision-path-analysis.md) - it visits only overlapping sections,
   not all loaded entities. Same for `PlayerSensor` (`level.players()` is a
   small list, then distance-sort).
4. **POI storage is section-sharded with occupancy tickets.**
   `PoiManager extends SectionStorage<PoiSection, Packed>` (region-file
   backed, same family as chunk storage), `getInChunk` walks only the
   column's section-Y range, `take()` acquires a ticket (`HAS_SPACE`
   occupancy filter), `JitteredLinearRetry` batch-cache in `AcquirePoi`
   (limit 5, retry backoff) + `batchCache`/`triedCount` in
   `NearestBedSensor` (5/batch, 40-tick timeout). The caching layer the plan
   hypothesized ("POI caches") largely EXISTS - verify before duplicating.
5. **Behavior dispatch is priority-ordered and status-gated.**
   `startEachNonRunningBehavior` only `tryStart`s STOPPED behaviors in
   ACTIVE activities; `tickEachRunningBehavior` only ticks RUNNING ones.
   Idle behaviors cost map iteration, not execution. `forgetOutdatedMemories`
   ticks `MemorySlot`s (TTL decrement - cheap).

## The actual expensive paths (in cost order, all confirmed in source)

### E1. `NearestVisibleLivingEntities` line-of-sight clips (the big one)
`NearestLivingEntitySensor.doTick` builds `NEAREST_VISIBLE_LIVING_ENTITIES`
eagerly for EVERY scan (every 20 ticks per mob): `NearestVisibleLivingEntities`
wraps the entity list with a `lineOfSightTest` per entity, and every
downstream consumer (`findClosest`, `contains`, stream filters in
`VillagerHostilesSensor.isMatchingEntity` etc.) fires a clip query
(`Level.clip` raycast through VoxelShapes) per candidate. Cost per scan:
O(candidates x clip-cost), and the clips are the single most expensive
primitive in the sensor set (shape raycasts vs entity AABBs). Worse,
`TARGET_CONDITIONS.test` in `isEntityTargetable`/`isEntityAttackable`
re-runs range + invisibility + LOS checks per candidate AGAIN at behavior
time. Concrete target: cache the LOS verdict per (observer, target) pair
for the scan's lifetime (all consumers in one scan see one verdict), and
skip LOS entirely when the candidate fails the cheap range test first
(order: distance-sort THEN clip only the nearest K, not clip-all-then-sort).

### E1 CORRECTION (2026-09-10, implementation session, source-verified)

The E1 premise above is WRONG on two counts, and both were overturned by
re-reading the real 26.2 source (freshly re-decompiled from
`preresearch/vanilla-26.2/server.jar` and diffed byte-for-byte against the
grounding copies: `PLAYERSENSOR_IDENTICAL`, `NVLE_IDENTICAL`).

1. **"Cache the LOS verdict per (observer, target) for the scan's lifetime"
   already exists, twice.** `Sensing` (`ai/sensing/Sensing.java`) holds
   `seen`/`unseen` `IntOpenHashSet`s keyed by entity id, cleared exactly once
   per tick from `Mob.serverAiStep`. EVERY line-of-sight clip in the game
   funnels through `TargetingConditions.test` ->
   `mob.getSensing().hasLineOfSight(target)`, so the raycast is already at
   most once per (mob, target) per TICK, for sensors and behaviors alike.
   On top of that, `NearestVisibleLivingEntities` wraps its list in an
   `Object2BooleanOpenHashMap` memo of the full `isEntityTargetable` verdict,
   allocated fresh per scan. Adding a third cache layer is pure overhead.
2. **"Distance-sort THEN clip only the nearest K" already exists.**
   `NearestLivingEntitySensor.doTick` sorts the candidate list by
   `distanceToSqr` BEFORE constructing the NVLE, and `findClosest` walks the
   sorted list and short-circuits on the first match. Clip-all-then-sort is
   not what vanilla does.

**Where the real waste is:** the sort itself. Vanilla passes
`Comparator.comparingDouble(body::distanceToSqr)` to `List.sort`, so
`distanceToSqr` (three subtractions, three multiplications, two additions,
plus a virtual dispatch through the entity) is recomputed on EVERY
COMPARISON: O(n log n) evaluations instead of O(n). `ScanOrder` does a
decorate-sort-undecorate with a stable merge sort over precomputed keys:
exactly n key evaluations, and byte-identical output order because the
merge uses `Double.compare(a, b) <= 0` (ties keep left-run-first, which is
what TimSort's stability guarantees for `List.sort`).

Redirecting E1 this way is safe for parity precisely because the shared
distance is bit-exact: `Entity.distanceToSqr` and `Vec3.distanceToSqr`
negate their operands the same way, so the squares and the sum are
IEEE-754 bit-identical to what the comparator would have produced
(proved over 200k random Vec3 pairs at far-lands magnitudes via
`Double.doubleToRawLongBits`, check `distance-arithmetic-bit-exact`).

`LosVerdictCache` is still implemented and still lands, but as the
semantics carrier (Kestrel owns its sensor path and needs a memo with
exactly the one-scan lifetime), NOT as a speed win. Measured, it LOSES to
vanilla's fastutil map on the queried path (0,81x at n=64 and n=256). An
interleaved key+stamp table was tried to fix that and was worse (0,56x to
0,72x); it was reverted. The only honest win is the idle path, where
vanilla allocates a map per scan per mob whether or not anything ever asks.

### E2. `AcquirePoi` pathfinding per acquisition (5 path computations, every 20 ticks)
`AcquirePoi` behavior (JOB_SITE, MEETING_POINT, HOME for beds): every run
(20-tick cadence + jitter) does `findAllClosestFirstWithType` (POI range
scan, radius 48, sorted by distSqr - full sort of all matches, then
`.limit(5)`), then `findPathToPois` -> `navigation.createPath(targets,
maxRange)` - a REAL A* per acquisition attempt, up to 5 targets. For a
village of 20 villagers this is ~1 A* per tick sustained, each over dozens
of chunks. The `JitteredLinearRetry` cache only helps AFTER failures; the
success path pathfinds every time. Concrete targets: (a) memoize
`findAllClosestFirstWithType` per POI-type per section-column per tick
(all villagers in one village query the same POIs); (b) reuse paths for
identical (start-chunk, target) pairs within a tick window; (c) note
`validRange` per POI type bounds the A* - keep it, it is the search cap.

### E3. `SecondaryPoiSensor` 9x5x9 block-state scan (every 40 ticks per villager)
`doTick` iterates x -4..4, y -2..2, z -4..4 (405 positions) calling
`level.getBlockState(testPos)` + profession-set `contains` per position.
`getBlockState` is cheap (cached chunk + palette read) but 405 x per
villager per 40 ticks = ~10 chunk-section reads per villager per tick
sustained, all to answer "is my job-site block still there". Concrete
target: subscribe to block changes in the 9x5x9 volume instead of polling
(event-driven step-12 pattern: invalidate a cached job-site list on
`setBlock` in range, else return the cached list for free). Parity-safe:
same values, just computed on change instead of on schedule.

> **E3 CORRECTION (2026-09-11): the subscription design above is wrong.**
> The volume is centred on a MOVING villager, so a change-subscribed cache
> must also invalidate on movement, and a single walked block costs 45 to
> 171 fresh reads against vanilla's ~10 per tick amortized. See "E3 result"
> below for what actually landed (loop-invariant hoist, empty-probe-set
> skip covering 14 of 15 professions, chunk-column hoist), and note that it
> needs no `setBlock` hook at all.

### E4. `PlayerSensor` full sort per scan (every 20 ticks per brain mob)
`level.players()` filtered by distance, SORTED by `distanceToSqr`, then
THREE derived lists (visible, attackable, attackable-list) each re-filtering
with LOS clips per player. Player counts are small (SMP: 2-5), so this is
NOT the farm-server bottleneck - but the pattern (sort-all then filter-all
three times, clipping per player per list) is O(3 x players x clip). Cheap
fix preserving order: compute (visible, attackable) flags ONCE per player
in a single pass, then project the three lists. Same order, same contents,
one clip per player instead of up to three.

### E5. `Brain.tick` behavior-set iteration (per entity per tick)
`getRunningBehaviors` allocates an `ObjectArrayList` and walks ALL
priorities x activities x behaviors EVERY tick (both in
`startEachNonRunningBehavior` and `tickEachRunningBehavior` - two full
walks). Behavior counts per mob are small (villager: ~10 activities x
~5 behaviors), but the double walk + allocation per entity per tick is
pure overhead. Concrete target: maintain a dirty-flagged running-set
(update on tryStart/stop transitions only), single walk. Order-preserving
(TreeMap priority order kept for starts; running-set order irrelevant
since each behavior ticks independently).

## Parity constraints (what a port must NOT change)

- **Scan cadence + jitter phase** (`scanRate`, `randomlyDelayStart`): behavior
  timing is observable (a villager takes its job site on tick T, not T+1).
  Cache VALUES, never re-phase WHEN scans run. The jitter draws from the
  entity's `random` (unseeded - see gotchas) - run-to-run identical only via
  the harness's SkipTicks + same-corpus discipline, NOT via seed control.
- **`level.getRandom()` draws inside behaviors** (`AcquirePoi.nextScheduledStart`
  uses `random.nextInt(20)`): the level-RNG call SEQUENCE is part of parity.
  A port that skips a draw (or adds one) desyncs every downstream consumer.
  Count draws exactly.
- **POI ticket semantics** (`take`/`release`/`HAS_SPACE`/`IS_OCCUPIED`):
  two villagers racing one job site resolve by ticket order. Any shared
  POI-result cache must preserve first-claimer-wins per tick ordering.
- **`getMemory` THROWS on unregistered type** (`IllegalStateException`):
  `requires()` sets are load-bearing. A ported sensor missing a `requires`
  entry crashes, not degrades - keep the sets identical.
- **LOS verdicts are per-scan snapshots**: caching across scans changes
  behavior (a pillar built between scans must blind next scan, not this
  one). Cache lifetime = one scan, then drop. (E1's cache is per-scan,
  E2's per-tick-window at most.)

## Kestrel targets (step 4 implementation order)

1. **E1 LOS-per-scan cache + range-first ordering** (biggest single win,
   villager-dense workloads).
2. **E2 POI-result memo per type per tick + path reuse window** (A* is the
   per-call heavyweight; measure before/after with the harness MSPT proxy).
3. **E4 single-pass player flags** (trivial, order-preserving, do first as
   a warm-up - smallest blast radius).
4. **E3 job-site change-subscription** (first step-12 event-driven instance;
   needs the setBlock hook - coordinate with step-3's WireUpdateFilter
   hook design, same interception point family).
5. **E5 running-set dirty flag** (allocation + double-walk removal).
6. Explicit NON-targets: sensor cadence/jitter (already optimal), POI
   section storage shape (already sharded), entity AABB indexing (already
   section-indexed), `GolemSensor` (200-tick cadence, memory-only check -
   already free).

## Harness note (applies to the mob-ai corpus, not yet built)

`Entity.random` is unseeded per entity - a live-AI corpus is NOT
bit-reproducible run-to-run for movement decisions. The determinism-check
against a live-AI corpus WILL fail on AI movement; that failure is expected
signal, not harness breakage. Corpus discipline for step 4: `NoAI:1b`
scripted entities for the SENSOR/POI differential (sensors read positions +
block state, both deterministic with NoAI), PLUS a separate live-AI soak
corpus whose PASS criterion is statistical (behavioral spot-checks +
no-crash + MSPT), not bit-exact. The tick hash covers entities (UUID +
pos + delta) so NoAI sensor corpora gate bit-exactly; live-AI corpora gate
on the power-trace PATTERN (scans fire, paths form) without hash equality.

VALIDATION CORRECTION (2026-09-10, live bench PC) - PARTLY SUPERSEDED, see
"Harness finding" below: the empty-world control PASS cited here is
vacuous (constant hash), and `/summon` UUIDs are not the only reason
entity corpora fail. The original text follows unchanged:

the mob-ai corpus FAILS
determinism-check run-to-run even with NoAI - not because of AI movement
but because `/summon` assigns FRESH RANDOM UUIDs per run, and UUIDs feed
the hash. Empty-world control PASSES (1799 ticks), so the harness is sound;
entity-bearing corpora need UUID-normalized comparison (strip/replace UUID
fields before diffing, or compare block-only sub-hashes). Until the
analyzer learns that, mob-ai gates on: (a) single-run structural checks
(corpus builds, sensors tick, no crash), (b) block-state sub-hash equality
across runs, (c) live-AI soak statistically. Do NOT "fix" by removing UUIDs
from the hash - they catch real duplication bugs (Mica's duplicate-UUID
drops); fix the COMPARISON, not the hash.

## Implementation results (2026-09-10, live bench PC, E4 + E1)

Landed under `core/src/vanilla/java/dev/kestrel/ai/`: `ScanOrder`
(decorate-sort-undecorate stable merge sort), `VisibleEntityScan` (E1 port
of `NearestLivingEntitySensor` + `NearestVisibleLivingEntities`),
`PlayerScan` (E4 single-pass player projection), `LosVerdictCache`
(one-scan verdict memo), `PoiTopK` (E2 stable nearest-5 selection, no full
sort), `SecondaryPoiScan` (E3 secondary-POI volume scan),
`MobAiProofCheck` (proof), `SensorScanBench` (measurement),
`BehaviorSchedule` (E5 flattened behavior walks, 2026-09-11).

### Unit proof: `MobAiProofCheck` PASS (15 checks), live on bench PC vs real 26.2

Compiled against the real deobfuscated 26.2 classes and run on the JDK 25
Bench PC, exit code 0. Checks: distance-arithmetic bit-exactness; E1 sort
order identity vs the real `Comparator.comparingDouble` (400 randomized
trials, key-evaluation counting); sort stability over adversarial keys
(2000 trials over `0.0 / -0.0 / +-1.0 / 4.0 / MIN_VALUE / MAX_VALUE /
+-Infinity / NaN`); E4 projection identity, exhaustive for n = 0..6 over
all 4^n visibility/attackability masks, comparing BOTH the three list
contents AND the oracle evaluation sequence against a literal
transcription of vanilla's three stream pipelines; E4 edge cases
(empty / single / all-invisible); verdict cache vs direct oracle; verdict
cache one-scan lifetime (the pillar test: a verdict flip between scans is
visible on the next scan and never retroactively on the current one);
`requires()` set equality against real `new PlayerSensor()` and
`new NearestLivingEntitySensor<>()` instances; `TargetingConditions`
invariants by reflection on the real `Sensor.TARGET_CONDITIONS`;
"vanilla still memoizes" (guards the E1 correction above from silently
rotting); empty-scan differential vs the real
`NearestVisibleLivingEntities.empty()`; and a bytecode scan of the
compiled Kestrel scan classes proving ZERO `RandomSource` / `getRandom` /
`nextInt` references, i.e. the port cannot desync the level RNG draw
sequence.

The proof was validated to have teeth: three negative controls were
injected one at a time (comparator tie-break flip, projection order
swap, cache lifetime extended across scans) and each failed on exactly
the intended check, then all sources were restored and PASS reconfirmed.

### Bench: `SensorScanBench`, bench PC 5700X3D / JDK 25, best-of-3 with warmup

```
  sort     n=4  1,06x   n=16  2,62x   n=64  2,73x   n=256  2,25x
  project  n=4  4,01x   n=16  2,02x   n=64  1,38x   n=256  1,45x
  memo     n=4  1,59x   n=16  1,08x   n=64  0,81x   n=256  0,81x
  idle     n=4  1,33x   n=16  4,54x   n=64  9,13x   n=256  694x
  poitopk  n=4  2,37x   n=16  6,57x   n=64  14,81x  n=256  22,45x
```

(E3's `secpoi` rows are not in this table: they were measured on the Pi,
not on bench PC. See the E3 result section.)

`sort` and `project` are the real wins (E1 and E4 respectively). `memo`
is a measured LOSS on the queried path, reported as-is. `idle` measures
the never-queried scan, where the win is allocation removal, not
arithmetic: read it as "vanilla's per-scan map allocation is gone", not
as a literal 694x, since a never-read allocation is DCE-prone.

### E2 result (2026-09-10, implementation session, source-verified)

The E2 premise was checked the same way E1's was: fresh re-decompile of
`AcquirePoi`, `PoiManager` (query methods byte-identical:
`getInSquare`/`getInRange`/`findAllWithType`/`findAllClosestFirstWithType`/
`getInChunk`), and `NearestBedSensor` (same formulation, only brace-style
and import differences) from `preresearch/vanilla-26.2/server.jar`, plus a
bytecode survey of every `PoiManager` consumer in the AI package and of
`PathNavigation` for a path cache.

Both specified targets closed before implementation:

1. **Per-type-per-tick result memo: already exists in the right layer.**
   `AcquirePoi`'s per-instance `JitteredLinearRetry batchCache` and
   `NearestBedSensor`'s `batchCache`/`triedCount` already suppress exactly
   the failing candidates. A shared cross-villager memo is not a missing
   optimization: the filter predicates are caller-owned
   (`cacheTest` closes over the caller's private retry table, so sharing
   results would desync per-villager backoff state; `validPoi` is bed
   occupancy read from live block state, or a predicate closing over the
   caller's profession). Keying a shared cache on predicate identity would
   either collide unsoundly or never hit.
2. **Path reuse across villagers: unsound at this layer.** `Path` is
   consumed by its owner's navigation (`moveTo(path, speed)`), so sharing
   one instance across navigations aliases mutable traversal state. The
   valid sharing point would be node-level memo inside `PathFinder`, which
   is the pathfinder's layer, not the sensor layer. `PathNavigation`
   exposes no path cache (bytecode survey: only the abstract
   `createPathFinder` factory plus the `createPath` overloads).

**What landed instead (`PoiTopK`):** the same waste shape as E1, one layer
down. Vanilla sorts ALL POI matches by a comparator that recomputes
`distSqr` per comparison and keeps 5 (`.sorted(comparingDouble(...))`
then `.limit(5)`). Kestrel computes each candidate's distance once and
keeps the nearest 5 with a stable top-K pass (worst-of-K index heap with
(key, encounter-index) ordering, insertion-sort of the K survivors on
already-computed keys), never sorting the full match list. Same surviving
SET including tie behavior (stable sort puts ties in encounter order; the
limit takes the first 5; callers collect into a `Set` so downstream order
is unspecified on both sides), same `validRange` cap handling (untouched,
still computed in `findPathToPois`), same RNG draw sequence (the class
draws no randomness; scheduling, `take()` ticket order, and the retry
tables are untouched).

Proof: three new checks in `MobAiProofCheck` (now 15 total, still PASS
live on bench PC, exit 0): set-identity vs literal
sorted-then-limit-5 transcription over 500 randomized trials with
tie-heavy inputs plus nearest-first output-order verification;
adversarial stability over 2000 trials on the
`0.0/-0.0/+-1.0/4.0/MIN_VALUE/MAX_VALUE/+-Infinity/NaN` palette checking
both the surviving set AND the key sequence against vanilla's list form;
key-count contract (exactly n evaluations on the heap path) plus
empty/k=0/k>=n edges and a farthest-drop negative control. `PoiTopK` was
added to the no-`RandomSource`-draws bytecode scan. Bench
(`SensorScanBench` `poitopk` rows, real `BlockPos.distSqr` both sides):
2,37x / 6,57x / 14,81x / 22,45x at n=4/16/64/256. The scaling shape is the
honest story: at n=4 there is almost nothing to skip, at n=256 the full
sort does ~8x the comparisons times a recomputed key per comparison while
the heap does n keys plus O(n log 5) index operations.

### E3 result (2026-09-11, implementation session, source-verified)

**The E3 premise in this document was wrong, and the source says so.** E3
was specified as "subscribe to block changes in the 9x5x9 volume instead
of polling", built on the `setBlock` interception hook shared with step 3.
Re-reading the real `SecondaryPoiSensor` closes that design:

- The volume is centred on `body.blockPosition()`, which moves whenever
  the villager walks a block. A change-subscribed cache must therefore be
  invalidated on MOVEMENT as well as on block change. A one-block
  horizontal step shifts in a 5x9 slab (45 fresh reads), a diagonal step
  up to 171. Vanilla's poll costs 405 reads per 40 ticks, i.e. ~10 per
  tick amortized. The "event-driven" design is a pessimisation for
  exactly the mobs that matter (the walking ones).
- Evaluating lazily at read time instead is a parity break: the memory is
  a snapshot taken at the scan tick, and a villager that moved since
  would report a volume centred on the wrong block.

So E3 needs no interception hook and was never blocked on one. That is
three for three: E1, E2 and E3 were each specified as a cache that either
already existed or could not soundly exist, and in each case the real win
was the inner loop redoing loop-invariant work.

**What the waste actually is, and what landed** (`SecondaryPoiScan`):

1. `body.getVillagerData().profession().value().secondaryPoi()` is
   re-evaluated on all 405 iterations and cannot change mid-scan. Hoisted,
   405 evaluations to 1.
2. **The probe set is empty for every profession but one.** Checked
   against the live registry in the proof, not asserted in prose: 15
   registered professions, and only `minecraft:farmer` declares a
   secondary POI (`ImmutableSet.of(FARMLAND)`). `SECONDARY_POIS` is in
   `Villager`'s sensor list unconditionally, so the other 14 (including
   `NONE`, every unemployed villager, and `NITWIT`) run 405 block reads
   per 40 ticks to compute a guaranteed-empty list. An empty set contains
   nothing, so the whole loop is skippable. Checked at runtime, so a
   datapack-registered profession with a secondary POI still scans.
3. `center.offset(x, y, z)` allocates a fresh immutable `BlockPos` per
   position: 405 allocations per scan, all but the hits immediately
   garbage. Kestrel probes with one `MutableBlockPos` and allocates only
   on a hit.
4. `Level.getBlockState` re-derives `isInValidBounds` and re-enters
   `ServerChunkCache.getChunk` on all 405 positions. `isInValidBounds`
   factors exactly into `isInsideBuildHeight(y) && ChunkPos.isValid(cx, cz)`,
   so the height test is per-y and the validity test plus chunk fetch are
   per chunk column. The 9x9 footprint spans at most four chunks, so 405
   chunk fetches become at most four. The innermost read stays
   `LevelChunk.getBlockState`, preserving the debug-world branch and the
   crash-report wrapper verbatim rather than reimplementing a raw section
   read.

Parity: traversal order, volume, probe predicate and memory write are
unchanged. The chunk memo never evicts, so the SET and FIRST-TOUCH ORDER
of distinct chunks fetched is identical to vanilla's; only repeat fetches
of an already-fetched chunk are removed. Out-of-bounds positions still
resolve to `VOID_AIR`. Hit positions are fresh immutable `BlockPos` with
the coordinates `offset` would have produced, int wraparound included.

One difference stated out loud rather than buried: on the empty-probe-set
path Kestrel performs ZERO `getChunk` calls where vanilla performed up to
four. That is unobservable in game state, because a villager only ticks
its brain from an entity-ticking chunk whose whole 3x3 neighbourhood is
loaded at FULL status, so those fetches neither load nor generate. What
does change is two diagnostics-only profiler counters (`getChunk`,
`getChunkCacheMiss`), which are not world state and are not hashed.

Proof: three new checks in `MobAiProofCheck` (now **18 total, PASS, exit
0**). Hit-sequence identity vs a literal transcription of
`SecondaryPoiSensor.doTick` that uses the REAL `BlockPos.offset` (600
randomized trials over an 8-block palette, probe sets sized 0..8, centres
including `Integer.MAX_VALUE - 2` / `Integer.MIN_VALUE` so the coordinate
identity covers int wraparound), comparing both the hit list element for
element and the set/erase decision; probe discipline (200 trials asserting
exactly 405 reads in exactly vanilla's order for a non-empty set, plus an
empty-set run whose lookup THROWS if called at all, so a single stray read
fails the check rather than merely costing time, plus a positive control
that the transcription really does perform those 405 elided reads);
grounding against the real registry and the real sensor
(`requires() == {SECONDARY_JOB_SITE}`, instance `scanRate == 40`,
`Villager` still references `SensorType.SECONDARY_POIS`, and the
profession walk above). `SecondaryPoiScan` was added to the
no-`RandomSource`-draws bytecode scan.

Bench (`SensorScanBench`, new rows). The block lookup is identical on both
sides, which UNDERSTATES the win: the chunk-column hoist needs a live
level and is not in these numbers.

```
  secpoi   n=405  1,40x     (farmer: allocation + loop overhead only)
  secpoi0  n=405  200,78x   (the other 14/15 professions: scan skipped)
```

### Toolchain result: the Pi can run these proofs

Bench PC was powered off for this session (`No route to host`), and the E3
proof ran anyway. `~/.local/toolchains/jdk-25.0.4.1+1` on the Pi compiles
the whole `dev.kestrel.ai` package against the real 26.2 classes with zero
errors and runs both `MobAiProofCheck` and `SensorScanBench`, using a
classpath built from the bundler jar's own `META-INF/libraries/*.jar` plus
the nested `META-INF/versions/26.2/server-26.2.jar`. "Pi never compiles"
holds for the Gradle build and the live server; it does NOT hold for the
headless differential proofs, which are platform-independent by
construction. The bench numbers above are Pi numbers (Cortex-A76) and are
NOT comparable to the bench PC table; they need a bench PC re-run before going
in the same table.

### MSPT proxy (harness `ticks.log`, tick body = enter -> exit nanos)

Same seed (4242424242), same `RunSeconds` (90), same view/simulation
distance (3), first quarter of each run dropped (boot + corpus build):

| corpus | mean ms | median ms | p95 ms |
|---|---|---|---|
| `harness/corpus/mob-ai` (committed, all NoAI) | 2,846 | 2,444 | 9,874 |
| scratch NoAI + brain probe | 3,144 | 2,675 | 12,226 |
| scratch AI + brain probe (only the villager NoAI flag differs) | 4,441 | 3,793 | 17,541 |

The last two rows differ in exactly one byte of corpus NBT, so the delta
is the price of six villager brains: **+1,30 ms/tick mean (+41%),
+1,12 ms/tick median (+42%), i.e. ~0,19 ms per villager per tick.** That
is the size of the prize for optimization #4 on this corpus, and it is
the number E2/E3 should be measured against.

This is a BASELINE, not a before/after: Kestrel's `core/` classes are not
injected into the live server (same status as collision, hopper and
redstone), so both runs are vanilla. The A/B for the code that landed is
`SensorScanBench` above.

## Corpus defect found (2026-09-10): `harness/corpus/mob-ai` exercises ZERO sensor code

Not fixed here - `harness/**` was being changed in parallel. Reported with
live evidence so it can be fixed at the source.

`Mob.isEffectiveAi()` returns false when `NoAI:1b` is set, and
`LivingEntity.aiStep` gates `serverAiStep()` on it. `Mob.serverAiStep` is
where `sensing.tick()` and `Brain.tick()` are called. So every entity in
the corpus being `NoAI:1b` means the brain never ticks, no sensor ever
scans, and no POI is ever acquired: the corpus that exists to exercise
optimization #4 exercises none of it.

Live differential, two runs of the same corpus differing ONLY in the
villagers' `NoAI` flag (zombies and the golem stayed `NoAI:1b` in both, so
the single variable is "does the villager brain tick"), probed by a
datapack `tick` function that counts serialized brain memories:

```
NoAI villagers  (t=200): VIL=6  NONE=3  HOME=0  JOB=0
AI   villagers  (t=200): VIL=6  NONE=2  HOME=2  JOB=4
```

Zero `minecraft:home` and zero `minecraft:job_site` memories after 45
seconds parked next to beds and workstations, versus HOME=2 / JOB=4 and
one villager acquiring a profession when the flag is cleared. That is
direct proof, not inference.

The corpus discipline note further up this file is what led to the
all-NoAI choice, and it was reasoning from a real constraint (unseeded
per-entity RNG). The fix is not "turn NoAI off everywhere": it is that
the SENSOR corpus needs AI-enabled mobs whose surroundings are scripted,
and it must gate on structural/statistical criteria rather than hash
equality, because (see below) hash equality is not currently available to
this harness at all.

## Harness finding (2026-09-10): the playerless world hash cannot gate anything yet

Not fixed here - `harness/**` was being changed in parallel. This is the
most important thing in this document.

`WorldStateHasher.hashChunks` prefilters holders by
`DistanceManager.getChunkLevel(pos, false) > 32 -> skip`. In a playerless
run nothing holds a ticket at level <= 32 unless the corpus force-loads,
so the chunk walk visits ZERO chunks and the tick hash degenerates to a
constant. Measured on this machine, `2801963083029836459` on every tick of
every playerless run without `forceload`:

| run | exit ticks | distinct hashes | equal to the degenerate constant |
|---|---|---|---|
| empty-world control, 30 s | 202 | **1** | 202 |
| blocks corpus, no forceload, 90 s | 1497 | **1** | 1497 |
| blocks corpus, with forceload | 298 | 298 | 0 |

Two consequences:

1. **The empty-world control's "DETERMINISM_CHECK PASS (404 ticks)" is
   vacuous.** It compares a constant against the same constant. It proves
   the pipeline runs; it proves nothing about determinism. It should not
   be cited as evidence that the harness is sound, and this document's own
   2026-09-10 validation note does exactly that - superseded.
2. **The moment a corpus force-loads (which every corpus must, or its
   blocks are invisible to the hash), the hash stops being stable.**

Cross-run determinism, `RunSeconds=90`, same seed, progressively stripping
away every candidate source of entropy:

| corpus | result |
|---|---|
| blocks + forceload | FAIL, all 298 compared ticks mismatch |
| blocks + forceload + `random_tick_speed 0` | FAIL, all 326 mismatch |
| **forceload ONLY** (no fill, no setblock, no entities, no random ticks) | **FAIL, 10+ of 635** |
| entities (NoAI) + forceload + `random_tick_speed 0` | FAIL (expected: UUIDs) |

The third row is the finding: a datapack whose entire content is
`gamerule random_tick_speed 0` + `forceload add` still fails, and within a
single run the hash changes on EVERY tick and never settles (330 exit
ticks, 330 distinct hashes, last change at tick 330 of 330) in a world
where nothing is changing. So the instability is in the harness's
chunk-set walk under FORCED tickets, not in corpus content, not in
`/summon` UUIDs, and not in unseeded random ticks. The 2026-09-10
summon-UUID diagnosis is real but is NOT the whole story, and it is not
the first thing to fix.

Net: there is currently NO configuration in which this harness produces a
stable, non-degenerate world hash for a playerless run. Until that is
fixed, no corpus can gate bit-exactly, and every "PASS" that is not
accompanied by `distinct hashes > 1` should be treated as vacuous.
Suggested guard for the analyzer: refuse to report PASS when the run's
distinct-hash count is 1.

## Toolchain gotcha found while doing this

26.2 renamed the game rules to snake_case: `gamerule randomTickSpeed 0` is
a PARSE ERROR (`Incorrect argument for command at position 9`), the real
name is `random_tick_speed` (also `advance_weather`, `fire_damage`,
`spawn_monsters`, ... - the identifiers live in
`net/minecraft/world/level/gamerules/GameRules.class`). A datapack
function containing one unparseable line fails to load ENTIRELY
("Failed to load function ... Couldn't load tag minecraft:load as it is
missing following references"), the server starts anyway, and the run
looks healthy. That silently turned a forceloaded corpus into an empty
world and produced a determinism PASS that was pure artifact. Always
grep the run's `stdout.log` for `Failed to load function` before
believing any corpus result.

## What E2 and E3 should do next (superseded 2026-09-11: both landed)

Kept for the record, because the prediction in this section was wrong in
an instructive way. It said E2 and E3 "will actually need the differential
harness to work", on the reasoning that E2 is pathfinding against live
world state and E3 is block-change interception. Both premises dissolved
on re-reading the source: E2's win turned out to be nearest-5 selection
(pure list algebra) and E3's turned out to be a loop-invariant hoist plus
a guaranteed-empty short-circuit (pure traversal algebra plus a documented
level-access hoist). Both were provable headlessly, and were. The general
lesson: "this needs the harness" is itself a premise to check against the
source, not a scheduling fact.

The original text follows.

### Original text

#### E2 and E3 as originally specified

**E2 (`AcquirePoi`), highest remaining value.** The measurement above says
six villagers cost 1,3 ms/tick, and the A* in `findPathToPois` is the
per-call heavyweight in that budget. Two notes from this session that
change the E2 plan: (a) do NOT assume the POI layer is uncached, check
`JitteredLinearRetry` and `NearestBedSensor.batchCache` first - the E1
lesson was that the specified optimization was already in vanilla, and the
same trap is set here; (b) `AcquirePoi.nextScheduledStart` draws
`random.nextInt(20)` from the LEVEL rng, so any memo that changes how
often the behavior runs changes the draw sequence and desyncs everything
downstream. Memoize the QUERY RESULT (`findAllClosestFirstWithType` per
POI type per section-column per tick), never the scheduling.

**E3 (`SecondaryPoiSensor` 9x5x9 poll).** 405 `getBlockState` calls per
villager per 40 ticks is real but cheap per call; the win is real only at
villager density. It needs the `setBlock` interception hook, which is
shared with step 3's `WireUpdateFilter` design - coordinate, do not build
a second interception point. Parity is easy here (same values, computed on
change instead of on schedule), so this is the safest of the remaining
targets.

**Before either lands, the harness needs its hash fixed** (previous
section). E4 and E1 could be proved by a unit proof against real 26.2
classes because they are pure list algebra with injectable oracles. E2 is
pathfinding against live world state, and E3 is block-change interception;
neither reduces to a headless unit proof as cleanly, so both will actually
need the differential harness to work.


## What E5 should do next (2026-09-11)

E5 (`Brain.tick` running-set dirty flag) is the only remaining mob-AI
target, and it is the smallest: `getRunningBehaviors` allocates an
`ObjectArrayList` and walks all priorities x activities x behaviors twice
per entity per tick. It is allocation-and-double-walk removal, not
arithmetic, so expect an `idle`-row-shaped result (a real allocation win
that benchmarks as a suspiciously large ratio because a dead allocation is
DCE-prone) rather than a `sort`-row-shaped one. Two cautions carried over
from this session:

- **Check the premise first.** Three for three, the specified optimization
  was either already in vanilla or unsound as specified. Before writing
  any code, re-decompile `Brain` and confirm the double walk is really
  there and really unguarded.
- **Order is load-bearing for STARTS, not for TICKS.** The `TreeMap`
  priority order governs `startEachNonRunningBehavior`; a dirty-flagged
  running set may only replace the `tickEachRunningBehavior` walk, where
  each behavior ticks independently. Do not let one set serve both.

### E5 result (2026-09-11, commit 0b9f40d)

Four for four: the waste was real but the specified fix was unsound, and
the grounding evidence went further than the "What E5 should do next" note
above anticipated. The double walk is there (a full walk in
`startEachNonRunningBehavior`, then `getRunningBehaviors()` walking the
whole structure a second time inside `tickEachRunningBehavior`, allocating a
fresh `ObjectArrayList` plus one iterator per nested collection per entity
per tick), but flattening to ONE walk, or maintaining a dirty-flagged
running set, is unsound on three independent counts, all from the 26.2
source:

1. The walks cannot be fused: `tryStart` mutates shared brain memories, so
   start-all-before-tick-all is observable through behavior entry
   conditions. Any interleaved start(A)/tick(A)/start(B) ordering is a
   behavior change, not an optimization.
2. The `getRunningBehaviors` snapshot is genuinely load-bearing, not just
   CME avoidance. `Behavior.tickOrStop` has no `status == RUNNING` guard:
   it is `if (!timedOut && canStillUse) tick(); else doStop();`, so a
   behavior that was RUNNING when the list was built and has since been
   stopped still gets `tickOrStop` called (which fires `stop()` a second
   time). Re-checking status inline, which is what a maintained running-set
   amounts to, silently drops that second call.
3. The running set cannot be collected as a by-product of walk 1: walk 1
   only descends into ACTIVE activities, while `getRunningBehaviors` visits
   everything, and `setActiveActivity` does NOT stop the behaviors of the
   activity it deactivates (it only erases memories and swaps the active
   set), so a RUNNING behavior inside a now-inactive activity is normal and
   must still tick.

What lands instead is the cheap part, done honestly. The behavior structure
is immutable after brain construction (`addActivity` is setup-only,
`removeAllBehaviors` is `@VisibleForTesting`), so
`core/src/vanilla/java/dev/kestrel/ai/BehaviorSchedule.java` (generic in
behavior/activity type) flattens it ONCE into arrays in exactly vanilla's
traversal order (TreeMap priority ascending; the inner HashMap's iteration
order stable once the map is never modified again; innermost LinkedHashSet
insertion-ordered) and fills the running snapshot into a reusable buffer
sized once. Per entity per tick that removes: one `ObjectArrayList`
allocation and its growth, every `add` call, and one iterator allocation
per nested collection visited, twice over. What it deliberately does NOT
remove is the status scan itself, because of point 2 above.

The RNG constraint is the hardest parity line in E5: `Behavior.tryStart`
draws `level.getRandom().nextInt(maxDuration + 1 - minDuration)` on every
SUCCESSFUL start (even `min == max` still draws, via `nextInt(1)`), so the
set of behaviors `tryStart` is invoked on, and the invocation order, are
part of the level RNG sequence. The proof checks the draw sequence
directly, not just the outcome.

Proof: `MobAiProofCheck` 23 checks PASS on the Pi against the real 26.2
classes (400 adversarial start trials + 400 adversarial tick trials each
differential against a literal transcription of the two walks over
structures built by the real `Brain.addActivity` and read back out of the
real `availableBehaviorsByPriority` field; a negative control keeps a naive
inline-rechecking single walk, which diverged from vanilla in 188/400 tick
trials, proving the test has teeth; a strict-subset test proving a RUNNING
behavior inside an INACTIVE activity is skipped by start but still ticked;
buffer-hygiene: `runningBehaviors()` returns a fresh list each time,
clearing it never perturbs the schedule, the buffer is never reallocated,
and every slot is null after each tick; grounding against the real
`Behavior` class: `tickOrStop` on a STOPPED instance still fires `stop()`
once, `tryStart` on a null level dies inside `Behavior.tryStart` at the
`getRandom` call site with `RUNNING` already set and `start()` never
reached; `Brain` still declares both walks and still builds on
`newTreeMap`/`newHashMap`/`newLinkedHashSet`). Bench (Pi): `brainwalk`
`n=4: 5.41-6.93x, n=16: 1.42-9.70x, n=64: 0.86-4.25x, n=256: 2.61-3.69x`
across three runs; the `n=64 0.86x` outlier in run 1 did not repeat
(4.21-4.25x in runs 2-3) and is the small-loop JMH-without-JMH noise, not a
regression (one flat array walk cannot lose to two nested-map walks at the
same predicate cost; the predicates are identical lambdas on both sides).

After E5 the mob-AI line is done, and the open item is no longer in this
file: it is the harness, which still cannot gate anything on entity
corpora (playerless world hash constant without forceload, unstable every
tick with it) and whose committed mob-ai corpus is all-NoAI, i.e.
exercises zero sensor code. Both belong to the parallel harness work.

## Still pending on bench PC (2026-09-11)

Bench PC was off this session, so the E3 numbers above are Pi numbers. When
it is back:

1. Re-run `MobAiProofCheck` on bench PC and confirm 23 checks PASS there too
   (expected: it is a deterministic headless differential, so a platform
   difference would itself be a finding).
2. Re-run `SensorScanBench` on bench PC and fold the `secpoi` / `secpoi0` /
   `brainwalk` rows into the main bench table.
3. The harness and MSPT legs stay vacuous for E3 until the corpus and hash
   defects are fixed: an all-NoAI villager corpus runs no sensors at all,
   so it cannot show a sensor change either way.
