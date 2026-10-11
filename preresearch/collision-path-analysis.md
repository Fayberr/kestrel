# Optimization #1 grounding: entity AABB lookup + collision/push path

Status: ANALYSIS, grounded in the real decompiled vanilla 26.2 source (not
secondary-source guesswork). Precedes any Kestrel implementation - per the
plan's own rule, nothing lands until it's differential-tested against this
analysis's baseline via `harness/`.

Method: decompiled the real classes with Vineflower 1.11.1 against
`preresearch/vanilla-26.2/server.jar`'s nested `server-26.2.jar` (the
deobfuscated class files themselves - MC 26.x ships with real names, no
mapping step needed). Read `Entity`, `Level`, `CollisionGetter`,
`BlockCollisions`, `EntityGetter`, `LevelEntityGetter` in full or in
relevant part.

## What vanilla already does well (don't re-fight this)

1. **Entity spatial lookup is already section-indexed.** `LevelEntityGetter`
   (`get(AABB, Consumer)`) is backed by a per-chunk-section entity index
   (`EntitySectionStorage`, wired through `ServerLevel`/`PersistentEntitySectionManager`
   since the 1.17 entity-storage rewrite). `Level.getEntities(AABB, ...)`
   does NOT scan every loaded entity - it only visits sections the query
   AABB actually overlaps. Confirmed via `LevelEntityGetter`'s method set
   (no full-iteration variant exposed for AABB queries) and `Level.java`
   `getEntities` methods delegating through the section-indexed getter.
   **This means "replace the naive O(n) entity scan with a spatial index"
   is not the win here - vanilla already has one.** Any writeup elsewhere
   describing that as the target is wrong for 26.x; do not build a
   redundant spatial index expecting a win from this alone.

2. **Block collision lookup is already a bounded, lazy, chunk-cached sweep.**
   `BlockCollisions` (implements `AbstractIterator`, used by
   `CollisionGetter.getBlockCollisionsFromContext`) walks only the
   expanded bounding cuboid of the query AABB (`Cursor3D` over
   `floor(min)-1 .. floor(max)+1` per axis - NOT the whole loaded world),
   caches the current chunk (`getChunkForCollisions`) across consecutive
   positions in the same chunk, and is lazy (an `AbstractIterator` -
   callers like `noBlockCollision` can and do short-circuit on the first
   non-empty shape). This is already close to the theoretical minimum
   number of block reads for a swept-AABB query.

## The actual expensive path: VoxelShape boolean algebra for non-cube blocks

`BlockCollisions.computeNext()` (see file for full logic) already
fast-paths the single most common case - a full-cube block
(`blockShape == Shapes.block()`) - with plain `AABB.intersects(x, y, z, x+1, y+1, z+1)`
math, no VoxelShape work at all. But **any block whose collision shape is
not a full cube** (stairs, slabs, fences, walls, carpets, trapdoors, snow
layers, farmland, pressure plates, and every partial-height/partial-width
block - a large fraction of any real base, and effectively 100% of
redstone/farm/decorative-heavy technical builds) falls through to the
general path:

```java
VoxelShape shape = blockShape.move(this.pos);              // allocates
if (!shape.isEmpty() && Shapes.joinIsNotEmpty(shape, this.entityShape, BooleanOp.AND)) {
   return this.resultProvider.apply(this.pos, shape);
}
```

`VoxelShape.move` allocates a new shape object per call (once per
candidate block position, every axis-resolution step, every tick, per
entity in motion near that block). `Shapes.joinIsNotEmpty` runs the
general voxel-grid boolean-algebra path: it merges the two shapes' per-axis
coordinate lists (`DoubleList`) and walks a 3D bitset of the merged grid -
correct for arbitrary shapes, but real overkill for the overwhelmingly
common case where a block's actual shape is just 1-3 simple boxes (a stair
is 2 boxes, a slab is 1, a fence is a vertical post + arms as 2-5 boxes).
`VoxelShape.toAabbs()` already exposes exactly that "list of simple boxes"
representation - it is not used on this hot path at all.

## Concrete target for the Kestrel replacement

Bypass `Shapes.joinIsNotEmpty`'s general voxel-grid algebra for any block
whose collision shape decomposes into a small constant number of boxes
(true for the vast majority of non-full-cube blocks in vanilla): test the
query AABB directly against each of that shape's `toAabbs()` boxes with
plain `AABB.intersects()` math, moved by the block position with no
VoxelShape allocation at all. Only fall back to real `VoxelShape`
boolean algebra for the rare genuinely-complex shapes (if any exist that
don't decompose cleanly - needs a one-time audit of every registered
block's collision shape at startup, cacheable since block shapes are
static per blockstate).

This matches the class of fix documented industry-wide as "collision
rewrites 3-6x" -
it is a real, previously-shipped technique (Lithium's fast block-collision
mixins), not a novel guess, and it is now grounded against this specific
version's actual bytecode rather than assumed to still apply unchanged.

## What this does NOT cover yet (follow-up analysis needed before implementing)

- `Entity.move()`'s per-axis collision resolution order (X/Y/Z stepping,
  where redundant re-checks could also compound cost) - not yet read in
  full; `Entity.java` is 152KB decompiled, only the collision-adjacent
  interfaces were read for this pass.
- Entity-vs-entity push resolution (`getPushableEntities`,
  `Entity.push`) - separate from block collision, not yet analyzed.
- The actual shape data needed to replicate vanilla's per-blockstate
  `toAabbs()` output in Kestrel's own code: per the plan's "vanilla logic
  embedded as the mechanics source" architecture, Kestrel should read
  this from the embedded vanilla classes at startup/build time (reflection
  or codegen), not hand-transcribe per-block shape tables that would drift
  from vanilla on every version bump.

## Next steps (in order, before any Kestrel collision code ships)

1. ~~Read `Entity.move()` in full (per-axis stepping) and `Entity.push`/
   `getPushableEntities` - complete the picture above.~~ DONE (2026-09-10),
   see "Entity.move()/collide()/push, read in full" below.
2. ~~Audit `Block.BLOCK_STATE_REGISTRY` at runtime...~~ DONE (2026-09-10),
   real data below - the bypass covers the registry.
3. ~~Add a `harness/corpus/` scenario built specifically to exercise this
   path hard...~~ DONE (2026-09-10): `harness/corpus/collision-stress/`.
   Loads and ticks correctly against real vanilla, but exposed a real
   determinism caveat (below) that must be resolved before this specific
   corpus can gate a real implementation comparison.
4. Only then: implement the replacement in `core/` (scaffolded, currently
   empty - see `core/README.md`). **Not started.** Also note: `core/` has
   no block/chunk/entity model of any kind yet, so "implement the
   collision replacement" isn't just porting the algorithm above - it
   first needs *something* to hook it into (at minimum a `BlockState`
   registry + per-blockstate shape data, sourced from the embedded
   vanilla classes per the plan's architecture, not hand-transcribed).
   That is real, larger scope beyond this analysis and hasn't been
   scoped yet.

## Entity.move()/collide()/push, read in full (2026-09-10)

Read `Entity.move()`, `Entity.collide()`, `Entity.collideWithShapes()`,
`Shapes.collide()`, `VoxelShape.collide()`/`collideX()`, `Direction.axisStepOrder()`,
and `LivingEntity.pushEntities()`/`doPush()`/`Entity.push()` in full
(decompiled, not guessed). Findings, all of which any Kestrel
implementation must replicate exactly to stay bit-identical:

- **Axis resolution order is movement-dependent, not fixed.** `Entity.collide()`
  resolves Y first always, then X/Z in an order picked by
  `Direction.axisStepOrder(movement)`: `Math.abs(movement.x) < Math.abs(movement.z)
  ? YZX : YXZ` - i.e. whichever horizontal axis has the *smaller* magnitude
  of movement gets resolved second. Getting this order wrong changes the
  final clamped movement vector in diagonal-collision cases (classic corner
  behavior), so this is a real per-bit-parity requirement, not a minor detail.
- **Step-up (auto-step) is a second full collision pass, not a shortcut.**
  If a horizontal collision occurred and the entity is (or would land) on
  the ground, vanilla collects Y-coordinate candidates from every collider
  shape overlapping the "step up" AABB (bounded by `maxUpStep()`), tries
  each candidate height's full `collideWithShapes()` result, and keeps it
  only if it beats the flat-movement horizontal distance. This means step-up
  can run `collideWithShapes()` (and therefore the block-collection path
  below) multiple times in a single `move()` call, not once - a naive
  "always call the collision resolver exactly once per axis" reimplementation
  would silently break auto-stepping (stairs would feel wrong even though
  block collision itself is correct).
- **`Shapes.collide()` / `VoxelShape.collide()` (the actual movement-clamp
  step) is NOT the expensive path - it's already efficient.** It walks each
  candidate shape's own voxel grid within an index range bounded by the
  moving AABB (`findIndex` on each axis, not the whole shape), and is a
  simple distance-clamp, not the general `joinIsNotEmpty` boolean-algebra
  path. **This corrects/sharpens the earlier framing**: the expensive path
  identified previously (`BlockCollisions.computeNext()`'s
  `Shapes.joinIsNotEmpty` call) is specifically in *candidate collection*
  (deciding which blocks' shapes even enter the `List<VoxelShape>` passed
  to `collideWithShapes`), not in the final movement-clamp math. The
  concrete target from the previous section stands unchanged; this just
  confirms precisely where the win is (and, symmetrically, where it is
  **not** - don't touch `Shapes.collide`/`VoxelShape.collide`, they're fine).
- **Per-blockstate collision shapes are already cached, not recomputed.**
  `BlockBehaviour$BlockStateBase` computes `this.collisionShape` once (via
  `block.getCollisionShape(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO,
  CollisionContext.empty())`) when the state object is built and returns it
  from cache on every `getCollisionShape(BlockGetter, BlockPos)` call
  thereafter. So the expensive part is genuinely only the per-query boolean
  join against the (already-cheap-to-fetch) cached shape, not shape
  computation itself.
- **Entity-vs-entity push is already efficient too - not a rewrite target.**
  `LivingEntity.pushEntities()` calls `Level.getPushableEntities(this,
  boundingBox)` (backed by the same section-indexed `LevelEntityGetter` as
  everything else - bounded to nearby entities, not a full scan), then for
  each candidate calls the symmetric O(1) impulse math in `Entity.push()`.
  No spatial-index or algorithmic rewrite is justified here; confirms the
  entity-push half of "optimization #1" was never actually the target.

## Block-shape registry audit: real data (2026-09-10)

Built `BlockShapeAudit` (ships in `harness/`'s boot jar, one-shot at server
start, reflection-only like `WorldStateHasher`) to answer step 2 above with
real numbers instead of the N=4 guess. Ran against the real vanilla 26.2
registry on bench PC. For every registered `BlockState` (32,366 total), calls
the cached `getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos(0,0,0))`
and tallies `toAabbs().size()`:

```
box_count,state_count
0,5430    (no collision at all - air, flowers, torches, etc.)
1,10585   (3,287 of these are the exact full-cube fast path Shapes.block())
2,5681
3,6729
4,2250
5,1625
7,48
10,6
12,2
13,2
15,8
```

**94.8% of all registered blockstates (30,675 / 32,366) decompose into <= 4
boxes; 99.8% (32,300 / 32,366) into <= 5.** Only 66 states (0.2%) exceed
that - all hoppers (10-13 boxes, `minecraft:hopper` with different
`facing`/`enabled` combinations) and `minecraft:powder_snow_cauldron` (15
boxes). **This confirms the N=4-5 bypass threshold from the previous
section covers the real registry almost entirely** - the general
`VoxelShape` boolean-algebra path only needs to remain as a fallback for a
literal handful of block types, not as a commonly-hit case.

KNOWN GAP (see `BlockShapeAudit`'s javadoc): this only audits the
context-free shape. Neighbor-dependent shapes (fences/walls/panes
connecting to neighbors, redstone dust) can vary their real in-world box
count beyond what's reported here - this is a lower bound on complexity,
not an exact ceiling. Good enough to confirm the bypass generally applies;
not a substitute for real differential testing once an implementation
exists.

## harness/corpus/collision-stress: built, and a real determinism caveat found

`harness/corpus/collision-stress/` (plain vanilla datapack, `minecraft:load`
function tag - see `harness/corpus/README.md`) builds a walled field
striped with stairs/slabs/fences/walls/snow-layers plus a pocket of the
highest-box-count states the audit above found (hoppers, powder snow
cauldron), and spawns 40 AI-enabled zombies across it. Confirmed working
against real vanilla on bench PC: loads correctly (`[kestrel-corpus] building
collision-stress scenario` -> `... ready` in the server log), and the
harness keeps producing a valid tick/hash sequence throughout (zero `-1`
hash-failure sentinels).

**However**, running `determinism-check.ps1` against this corpus (as
opposed to the plain empty-world corpus that passed cleanly at
360/360 ticks earlier the same day) surfaced a real FAIL: 2 hash mismatches
in the first few ticks (`tick_count=1`, both the `exit` and following
`enter` hash), with every tick after that matching perfectly for the
remaining ~410 ticks compared. That pattern - mismatch only in a narrow
early window, not a permanent divergence - does not look like the
"AI movement decisions are non-deterministic" hypothesis this corpus was
partly built to test (`Entity.random = RandomSource.create()` with no
level-seed argument, confirmed in `Entity.java` - genuinely not seeded
reproducibly, so that hypothesis is still plausible and worth ruling in/out
separately), because real AI-driven divergence should compound and stay
different, not resolve back to identical hashes by tick 2. More likely
candidates, **not yet root-caused**: a transient race between async chunk
generation/lighting completion and the hash walk's `visibleChunkMap` read
during the first couple of ticks after a fresh world's spawn-area
preparation, or ordering nondeterminism in something not fully sorted by
`WorldStateHasher` that only matters while spawn-area chunks are still
settling. Follow-up options were, cheapest first: (a) have
`determinism-check.ps1` skip the first ~5 ticks before comparing (fastest
to try, would confirm/refute "settling window" without explaining it),
(b) rebuild the corpus with `NoAI:1b` scripted entities instead of live AI
to eliminate the RNG-seed hypothesis entirely, (c) add per-chunk sub-hashes
to the log (not just one merged per-tick hash) to localize exactly which
chunk/entity diverges instead of guessing from a single opaque number.

**Option (a) tried and confirmed the settling-window hypothesis (2026-09-10).**
Added a `-SkipTicks` parameter to `determinism-check.ps1` (skips log lines
whose `tick_count` is `<=` the given value before comparing). Two paired
runs against `collision-stress` at `ViewDistance=1`, same server/agent, on
Bench PC:

- `-SkipTicks 0` (control, fresh run): `DETERMINISM_CHECK FAIL (2
  mismatches out of 330)` - both mismatches again at `tick_count=1`
  (`exit` then `enter`), reproducing the exact same pattern as the
  original finding.
- `-SkipTicks 5` (same corpus, same settings, different fresh run):
  `DETERMINISM_CHECK PASS (288 ticks compared)` - zero mismatches once
  the first 5 ticks are excluded.

This confirms the divergence is confined to a narrow window right after
fresh-world spawn-area preparation (chunk generation/lighting still
settling, or a related early-tick nondeterminism), not a permanent or
compounding divergence, and further weakens the AI-RNG hypothesis (which
would not self-resolve by tick 2 either way). It does **not** identify the
exact root cause (that's still options (b)/(c) if ever needed), but it is
now understood well enough to use safely: **`collision-stress` is
promoted to a trusted oracle for differential comparisons, provided the
comparison skips ticks 0-5** (i.e. only compare from `tick_count > 5`
onward, matching `determinism-check.ps1 -SkipTicks 5`). Do not compare raw
tick 0-5 hashes against this corpus for a parity decision; do compare
everything after that.

## Implementation: ShapeBoxCache fast-path (2026-09-10)

`core/src/vanilla/java/dev/kestrel/collision/ShapeBoxCache.java` ports the
concrete target above into real runtime code: per-state identity-keyed cache
of `toAabbs()` box lists (built from the real vanilla cached shape, one call
per distinct state), answering overlap with plain `AABB.intersects` math at
the block's integer position, zero VoxelShape allocation on the fast path.
Fallback (real vanilla `Shapes.joinIsNotEmpty`) for >5-box states; full-cube
identity and empty shapes served fast (vanilla's own fast path / trivial
miss). Context-sensitive guard: if the live context-resolved shape's boxes
differ from the empty-context cached boxes, fall back rather than serve
stale geometry.

`ShapeBoxCacheCheck.java` is the differential proof: every registered
blockstate x 7 entity probes (full overlap, graze, near miss, tall, flat,
offset) compared against real `Shapes.joinIsNotEmpty`. Verified live on
Bench PC: **226,562 pairs, 226,100 fast-path, 462 fallback, zero mismatches -
PASS**. Fallbacks are exactly the 66 >5-box states x 7 probes, as predicted
by the registry audit.

## Implementation: KestrelCollide movement port (2026-09-10)

`core/src/vanilla/java/dev/kestrel/collision/KestrelCollide.java` ports the
full movement-resolution pipeline: sweep bounds (`floor(min)-1 ..
floor(max)+1`, 1e-7 epsilon) + `Cursor3D` face-type gates (corner skip,
edge `hasLargeCollisionShape`, face-diagonal piston veto) + collider
collection with the `ShapeBoxCache` fast path wired in and vanilla
`joinIsNotEmpty` fallback + Y-first-then-X/Z-by-magnitude axis resolution
via real vanilla `Shapes.collide` + the step-up second pass (candidate
heights from collider Y coords, float-cast skip, -1e-5 box nudge,
keep-if-beats-horizontal-distance).

`KestrelCollideCheck.java` is the differential proof: 7 entity boxes x 11
movement vectors x 3 jitters over the resolved fixture (falls, runs into
every stripe, both diagonal orders, jumps, step-ups, zero move), each
compared bit-exact against a from-scratch vanilla reference (real
`BlockCollisions`-semantics collection + real `Shapes.collide` + real
step-up logic, no cache anywhere in the reference path). Verified live on
Bench PC: **231 pairs, zero mismatches - PASS**.

Two real bugs caught by the check before passing, kept here so nobody
re-learns them:

- The `Cursor3D` face-type gates are NOT optional trimming: without them the
  port collected extra edge colliders vanilla never sees, clamping movement
  vanilla leaves free (e.g. 0.4 walk resolved to 0.139 only in the port).
  `computeNext`'s type-1/2/3 gates are parity, not performance.
- `BlockState.getCollisionShape` caches per state object and returns the
  SAME object on repeat calls for context-free shapes. A reference-inequality
  (`!=`) context check therefore false-fires on every call (fresh
  `toAabbs()` list each time); the check must be identity-first, box-compare
  only on identity miss. Fixed in `ShapeBoxCache.test`.

## Measured speedup: 7.7x on the overlap test (2026-09-10)

`CollisionBench.java` times the isolated per-candidate overlap test: real
vanilla `shape.move + Shapes.joinIsNotEmpty` vs `ShapeBoxCache.test`, over a
deterministic 400-state slice x 5 probes x 500 iters, best-of-2, live on
Bench PC (Ryzen 7 5700X3D): **vanilla 267.6 ms vs fast 34.8 ms = 7.68x**.
Above the 3-6x estimate from secondary sources - the isolated test benefits
extra from zero allocation (no `ArrayVoxelShape` + `OffsetDoubleList` per
call), which a full tick will partly dilute with surrounding work.

Scope honesty: this is the overlap test only, not a tick. Whole-server gain
needs the live differential harness on a real world, which comes with the
server skeleton (Phase B). The first attempt at this bench swept all 23,583
fast-path states x 20,000 iters and ran 10+ minutes before being killed;
the 400-state slice measures the same uniform per-state cost at ~1/60 the
time.
