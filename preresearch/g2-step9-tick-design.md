# G2 step 9: post-FULL tick settle (leaf distance + fluid)

Covers the remainder items from step 7 (`g2-step7-design.md` section 6):
316 leaf-`distance` cells on chunk (0,0) and 33 `water -> air` cells on
chunk (5,7). Both come from ticks vanilla runs after the chunk reaches
FULL, not from FEATURES. All javap citations below are against the 26.2
inner server jar (`META-INF/versions/26.2/server-26.2.jar`).

## 1. Leaf distance: `LeavesBlock.tick` is a one-line settle

Bytecode (`LeavesBlock`): `tick(state, level, pos, random)` does exactly
one thing: `level.setBlock(pos, updateDistance(state, level, pos), 3)`.
No RNG use, no decay check, no drops. So the scheduled tick is a pure
function of the 6 neighbours at tick time.

`updateDistance(state, level, pos)` (private static): allocates one
`MutableBlockPos`, loops `Direction.values()` (6), reads each neighbour
via `LevelAccessor.getBlockState`, folds `min(getDistanceAt(n))`, and
returns `state.setValue(DISTANCE, min(7, best + 1))`. `getDistanceAt`
delegates to `getOptionalDistanceAt(...).orElse(7)`; that returns
`OptionalInt.of(0)` for `PREVENTS_NEARBY_LEAF_DECAY`-tagged states
(logs), the leaf's own DISTANCE when it has the property, else empty
(7). Net: `distance = min(7, min over 6 neighbours (log 0 | leaf dist
| 7) + 1)`.

Consequence: the existing `FeaturesCheck.settleLeaves` Jacobi fixpoint
computes exactly what repeated `tick` calls converge to, provided the
neighbour reads see settled values. Jacobi (all reads from the previous
sweep) reaches the same fixpoint as vanilla's scheduled order because
the function is monotone decreasing toward the least fixpoint; order
only changes how many sweeps it takes. Keep the fixpoint, but move it
out of the probe into a real stage: `HeadlessNoise.settleLeaves(grid,
cx, cz)` over the 3x3 (reads need the 5x5 ring for edge leaves, same
as the probe does today).

What ticks do NOT do (so we skip them): `randomTick` only handles
decay (`decaying` = not persistent and distance 7: drop + remove). The
oracle corpus shows no decayed-away leaves in the diff set (all 316
are distance-only), so decay ran nowhere on these chunks. Do not run
random ticks: they need live RNG and would risk removing blocks the
oracle kept.

## 2. Leaf `updateShape`: scheduled ticks on placement

`updateShape` (waterlog branch aside) ends with: if
`getDistanceAt(self) != DISTANCE value`, `scheduleTick(pos, thisBlock,
1)`. So every leaf whose placement left a stale distance already has
a tick scheduled at delay 1. The settle stage therefore replays exactly
the ticks vanilla would fire first after FULL: one pass per leaf is
not enough (neighbours change under each other), hence fixpoint, not
a single sweep. Directed neighbours outside the 5x5 read ring keep
their FEATURES values, same approximation the probe already makes
(100.00% on (0,0) says the ring is wide enough there).

## 3. Fluid: `water -> air` is NOT a worldgen stage

The FULL-stage lambdas (`ChunkStatusTasks.lambda$full$0/1`) do
post-load bookkeeping only: wrap ProtoChunk in LevelChunk, set full
status, run post-load, register block entities and tick containers,
regrid bedrock. No fluid tick call appears in either lambda. So the
33 `water -> air` cells on (5,7) come from live fluid ticks after the
chunk loaded, not from generation. Do not replay fluid in the
generation path: fluid ticks need a live level, game time, and
neighbour tick ordering that headless generation cannot reproduce
cheaply. Record them as a known live-only delta (33/98304 = 0.03%).

## 4. Build slice

- `HeadlessNoise.settleLeaves(Map<Long,ChunkAccess> grid, int cx, int
  cz)`: promote `FeaturesCheck.settleLeaves` verbatim (Jacobi over
  DISTANCE via `LeavesBlock.DISTANCE`, 5x5 reads, center writes),
  return changed-cell count for the log. No signature changes
  elsewhere; call it after `features()` in the probe and later in
  `--generate`.
- Expected result: chunk (0,0) 99.68% to 100.00%, chunk (5,7) 99.85%
  to 99.96% with only the live-fluid 33 left.
- Out of scope: decay random ticks, fluid ticks, `Blender`
  border ticks (ticks only, skipped per step-7 D7), block-entity
  NBT (still DUMMY placeholders until step 8 lands).
