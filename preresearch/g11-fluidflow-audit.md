# G11 Fluid Flow Audit: Kestrel vs Vanilla Minecraft 26.2

Status: AUDIT REPORT, grounded in the decompiled vanilla Minecraft 26.2 server jar (`server-26.2.jar`) via Vineflower and the game datapack JSON in `packdump/data`.
Target: Road-map item G11 in Kestrel (`dev.kestrel.loop.FluidFlow`, `dev.kestrel.loop.TickLoop`).
Scope: Read-only research report. Line-by-line verification, divergence inventory, tag comparison, coverage gap analysis, and prioritized recommendations.

> Status update (2026-09-25, main = `08fe226`). G11 has LANDED and is live. The
> engine merged in `15ac05f` and the two top-ranked divergences were fixed in
> `aaf7d1f`: water now douses lit campfires and candles (clears `LIT` as well as
> setting `WATERLOGGED`) and comparators get the `DiodeBlock` front fan-out on
> both place and removal, which is Divergences 1 and 2 below. No other
> divergence was addressed. `FluidParityCheck` reports 25/25 exact against the
> 25 fixtures in `harness/corpus/fluid-flow/oracle/`.
>
> Section 4 was written against 23 scenarios; the corpus is now 25, the two
> additions being `campfire_doused` and `comparator_washed` (added with
> `aaf7d1f`). None of the six scenarios section 4 proposes were added under
> those names, so lava slope-finding (distance 2 in the Overworld, 4 in the
> Nether), the `sourceNeighborCount >= 3` downward branch, double-slab fluid
> rejection and lava recession still have no fixture. Section 5's list of known
> intentional gaps still holds. Section 6's items 1 and 2 are done; items 3 to
> 5 are the open ones, along with Divergence 3 (powered buttons and pressure
> plates get no secondary removal fan-out) and Divergence 4 (`BucketPickup` is
> not implemented anywhere in Kestrel).

---

## 1. Method Map Table

This table maps every vanilla method in `FlowingFluid`, `WaterFluid`, `LavaFluid`, `LiquidBlock`, `SimpleWaterloggedBlock`, `LiquidBlockContainer`, `BucketPickup`, `CollectingNeighborUpdater`, and the scheduled fluid tick queue layer against its Kestrel counterpart.

Verdict definitions:
- **MATCH**: Kestrel implements identical logic or directly delegates to the matching vanilla behavior.
- **DIVERGES**: Kestrel attempts to transcribe the logic, but exhibits behavioral or state differences.
- **MISSING**: Vanilla behavior that belongs to fluid simulation or container interaction that Kestrel omits.
- **N/A**: Client-only, entity-collision, rendering, or out-of-scope subsystem (such as explosions or loot tables).

| Vanilla Class and Method | Vanilla file:line | Kestrel Counterpart (file:line) | Verdict | Reason |
| :--- | :--- | :--- | :--- | :--- |
| `FlowingFluid.createFluidStateDefinition` | `net/minecraft/world/level/material/FlowingFluid.java:50` | N/A | N/A | FluidState definitions are loaded directly from the vanilla jar classes. |
| `FlowingFluid.getFlow` | `net/minecraft/world/level/material/FlowingFluid.java:55` | N/A | N/A | Computes vector for pushing entities and rendering; out of scope for cell flow. |
| `FlowingFluid.affectsFlow` | `net/minecraft/world/level/material/FlowingFluid.java:102` | N/A | N/A | Private helper for `getFlow`. |
| `FlowingFluid.isSolidFace` | `net/minecraft/world/level/material/FlowingFluid.java:106` | N/A | N/A | Protected helper for `getFlow`. |
| `FlowingFluid.spread` | `net/minecraft/world/level/material/FlowingFluid.java:118` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:243` | MATCH | Exact transcription of downward check, `sourceNeighborCount >= 3`, and hole check. |
| `FlowingFluid.spreadToSides` | `net/minecraft/world/level/material/FlowingFluid.java:143` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:267` | MATCH | Drops off amount (or 7 if falling), iterates horizontal spread map in ordinal order. |
| `FlowingFluid.getNewLiquid` | `net/minecraft/world/level/material/FlowingFluid.java:161` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:282` | MATCH | Evaluates 4 horizontal neighbors, source conversion over solid/source floor, above fluid, drop-off. |
| `FlowingFluid.canPassThroughWall` | `net/minecraft/world/level/material/FlowingFluid.java:198` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:318` | MATCH | Collision shape checks and `Shapes.mergedFaceOccludes`. (Vanilla LRU cache omitted). |
| `FlowingFluid.getFlowing()` | `net/minecraft/world/level/material/FlowingFluid.java:252` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:137` | MATCH | `Kind.flowing()` returns `Fluids.FLOWING_WATER` or `Fluids.FLOWING_LAVA`. |
| `FlowingFluid.getFlowing(int, boolean)` | `net/minecraft/world/level/material/FlowingFluid.java:254` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:180` | MATCH | `flowingState` sets `FlowingFluid.LEVEL` and `FlowingFluid.FALLING`. |
| `FlowingFluid.getSource()` | `net/minecraft/world/level/material/FlowingFluid.java:258` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:132` | MATCH | `Kind.source()` returns `Fluids.WATER` or `Fluids.LAVA`. |
| `FlowingFluid.getSource(boolean)` | `net/minecraft/world/level/material/FlowingFluid.java:260` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:186` | MATCH | `sourceState` sets `FlowingFluid.FALLING`. |
| `FlowingFluid.canConvertToSource` | `net/minecraft/world/level/material/FlowingFluid.java:264` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:176` | MATCH | Returns gamerule values (`waterSourceConversion`, `lavaSourceConversion`). |
| `FlowingFluid.spreadTo` | `net/minecraft/world/level/material/FlowingFluid.java:266` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:335` | DIVERGES | Bypasses `container.placeLiquid`, leaving lit campfires and candles unextinguished. |
| `FlowingFluid.beforeDestroyingBlock` | `net/minecraft/world/level/material/FlowingFluid.java:278` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:349` | MATCH | Drops and fizz are documented intentional gaps in Javadoc lines 62-64. |
| `FlowingFluid.getSlopeDistance` | `net/minecraft/world/level/material/FlowingFluid.java:280` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:365` | MATCH | Recursive outward scan up to slope find distance, pruning reverse direction. |
| `FlowingFluid.isWaterHole` | `net/minecraft/world/level/material/FlowingFluid.java:308` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:390` | MATCH | DOWN wall check and same fluid or `canHoldFluid(flowing)`. |
| `FlowingFluid.canPassThrough` | `net/minecraft/world/level/material/FlowingFluid.java:318` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:399` | MATCH | Conjunction of `canMaybePassThrough` and `canHoldSpecificFluid`. |
| `FlowingFluid.canMaybePassThrough` | `net/minecraft/world/level/material/FlowingFluid.java:332` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:405` | MATCH | Not source of same kind, `canHoldAnyFluid`, and `canPassThroughWall`. |
| `FlowingFluid.isSourceBlockOfThisType` | `net/minecraft/world/level/material/FlowingFluid.java:346` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:412` | MATCH | `isSourceOf` checks same kind and `isSource()`. |
| `FlowingFluid.getSlopeFindDistance` | `net/minecraft/world/level/material/FlowingFluid.java:350` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:172` | MATCH | 4 for water; 4 for fast lava; 2 for normal lava. |
| `FlowingFluid.sourceNeighborCount` | `net/minecraft/world/level/material/FlowingFluid.java:352` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:416` | MATCH | Counts horizontal neighbors matching `isSourceOf`. |
| `FlowingFluid.getSpread` | `net/minecraft/world/level/material/FlowingFluid.java:366` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:430` | MATCH | Slope-finding prune, hole shortcut, `canBeReplacedWith`, returned in `EnumMap`. |
| `FlowingFluid.canHoldAnyFluid` | `net/minecraft/world/level/material/FlowingFluid.java:407` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:462` | MATCH | `LiquidBlockContainer` true, `blocksMotion` false, excludes doors, signs, ladders, portals. |
| `FlowingFluid.canHoldFluid` | `net/minecraft/world/level/material/FlowingFluid.java:426` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:481` | MATCH | Conjunction of `canHoldAnyFluid` and `canHoldSpecificFluid`. |
| `FlowingFluid.canHoldSpecificFluid` | `net/minecraft/world/level/material/FlowingFluid.java:430` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:485` | MATCH | Delegates to `container.canPlaceLiquid(null, getter, pos, state, fluid)`. |
| `FlowingFluid.getDropOff` | `net/minecraft/world/level/material/FlowingFluid.java:434` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:168` | MATCH | 1 for water; 1 for fast lava; 2 for normal lava. |
| `FlowingFluid.getSpreadDelay` | `net/minecraft/world/level/material/FlowingFluid.java:436` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:229` | MATCH | Overridden in LavaFluid; WaterFluid returns base `tickDelay`. |
| `FlowingFluid.tick` | `net/minecraft/world/level/material/FlowingFluid.java:441` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:201` | MATCH | Transcribed in `tickFluid`: recomputes non-source state, sets AIR or block, schedules, spreads. |
| `FlowingFluid.getLegacyLevel` | `net/minecraft/world/level/material/FlowingFluid.java:460` | N/A | N/A | Vanilla static helper used by `createLegacyBlock`. |
| `FlowingFluid.hasSameAbove` | `net/minecraft/world/level/material/FlowingFluid.java:464` | N/A | N/A | Private vanilla helper executed within `FluidState.getHeight`. |
| `FlowingFluid.getHeight` | `net/minecraft/world/level/material/FlowingFluid.java:469` | N/A | N/A | Executed via vanilla `FluidState.getHeight(getter, pos)`. |
| `FlowingFluid.getOwnHeight` | `net/minecraft/world/level/material/FlowingFluid.java:474` | N/A | N/A | Executed via vanilla `FluidState.getOwnHeight()`. |
| `FlowingFluid.getAmount` | `net/minecraft/world/level/material/FlowingFluid.java:479` | N/A | N/A | Executed via vanilla `FluidState.getAmount()`. |
| `FlowingFluid.getShape` | `net/minecraft/world/level/material/FlowingFluid.java:482` | N/A | N/A | Entity collision/rendering shape; not part of cellular flow. |
| `FlowingFluid.SpreadContext.getBlockState` | `net/minecraft/world/level/material/FlowingFluid.java:517` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:514` | MATCH | Caches block state indexed by packed short `(rx, rz)`. |
| `FlowingFluid.SpreadContext.isHole` | `net/minecraft/world/level/material/FlowingFluid.java:525` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:527` | MATCH | Memoizes hole evaluation indexed by packed short `(rx, rz)`. |
| `FlowingFluid.SpreadContext.getCacheKey` | `net/minecraft/world/level/material/FlowingFluid.java:534` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:539` | MATCH | Exact formula: `(short) ((rx + 128 & 0xFF) << 8 \| rz + 128 & 0xFF)`. |
| `WaterFluid.getFlowing` | `net/minecraft/world/level/material/WaterFluid.java:34` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:137` | MATCH | `Kind.WATER.flowing()` returns `Fluids.FLOWING_WATER`. |
| `WaterFluid.getSource` | `net/minecraft/world/level/material/WaterFluid.java:39` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:132` | MATCH | `Kind.WATER.source()` returns `Fluids.WATER`. |
| `WaterFluid.getBucket` | `net/minecraft/world/level/material/WaterFluid.java:44` | N/A | N/A | Item bucket reference. |
| `WaterFluid.animateTick` | `net/minecraft/world/level/material/WaterFluid.java:49` | N/A | N/A | Client ambient sound and underwater particles. |
| `WaterFluid.getDripParticle` | `net/minecraft/world/level/material/WaterFluid.java:72` | N/A | N/A | Client drip particle. |
| `WaterFluid.canConvertToSource` | `net/minecraft/world/level/material/WaterFluid.java:77` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:176` | MATCH | Returns `waterSourceConversion` (default true). |
| `WaterFluid.beforeDestroyingBlock` | `net/minecraft/world/level/material/WaterFluid.java:82` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:349` | MATCH | Documented intentional gap (no item entities). |
| `WaterFluid.entityInside` | `net/minecraft/world/level/material/WaterFluid.java:88` | N/A | N/A | Extinguishes burning entities. |
| `WaterFluid.getSlopeFindDistance` | `net/minecraft/world/level/material/WaterFluid.java:93` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:172` | MATCH | Returns 4. |
| `WaterFluid.createLegacyBlock` | `net/minecraft/world/level/material/WaterFluid.java:98` | N/A | MATCH | Calls vanilla `target.createLegacyBlock()`. |
| `WaterFluid.isSame` | `net/minecraft/world/level/material/WaterFluid.java:103` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:146` | MATCH | Returns true for `Fluids.WATER` or `Fluids.FLOWING_WATER`. |
| `WaterFluid.getDropOff` | `net/minecraft/world/level/material/WaterFluid.java:108` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:168` | MATCH | Returns 1. |
| `WaterFluid.getTickDelay` | `net/minecraft/world/level/material/WaterFluid.java:113` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:164` | MATCH | Returns 5. |
| `WaterFluid.canBeReplacedWith` | `net/minecraft/world/level/material/WaterFluid.java:118` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:493` | MATCH | Exact check: `dir == Direction.DOWN && !isWater(other)`. |
| `WaterFluid.getExplosionResistance` | `net/minecraft/world/level/material/WaterFluid.java:123` | N/A | N/A | Explosion physics (100.0F). |
| `WaterFluid.getPickupSound` | `net/minecraft/world/level/material/WaterFluid.java:128` | N/A | N/A | Sound event. |
| `LavaFluid.getFlowing` | `net/minecraft/world/level/material/LavaFluid.java:37` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:137` | MATCH | `Kind.LAVA.flowing()` returns `Fluids.FLOWING_LAVA`. |
| `LavaFluid.getSource` | `net/minecraft/world/level/material/LavaFluid.java:42` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:132` | MATCH | `Kind.LAVA.source()` returns `Fluids.LAVA`. |
| `LavaFluid.getBucket` | `net/minecraft/world/level/material/LavaFluid.java:47` | N/A | N/A | Item bucket reference. |
| `LavaFluid.animateTick` | `net/minecraft/world/level/material/LavaFluid.java:52` | N/A | N/A | Client particle/sound pops. |
| `LavaFluid.randomTick` | `net/minecraft/world/level/material/LavaFluid.java:81` | N/A | MISSING | Ignites adjacent flammable blocks and spreads fire; Kestrel has no random chunk ticks. |
| `LavaFluid.entityInside` | `net/minecraft/world/level/material/LavaFluid.java:119` | N/A | N/A | Ignites and hurts entities. |
| `LavaFluid.hasFlammableNeighbours` | `net/minecraft/world/level/material/LavaFluid.java:125` | N/A | N/A | Helper for `randomTick`. |
| `LavaFluid.isFlammable` | `net/minecraft/world/level/material/LavaFluid.java:135` | N/A | N/A | Helper for `randomTick`. |
| `LavaFluid.getDripParticle` | `net/minecraft/world/level/material/LavaFluid.java:141` | N/A | N/A | Client drip particle. |
| `LavaFluid.beforeDestroyingBlock` | `net/minecraft/world/level/material/LavaFluid.java:146` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:349` | MATCH | Documented intentional gap (fizz 1501 no-op). |
| `LavaFluid.getSlopeFindDistance` | `net/minecraft/world/level/material/LavaFluid.java:151` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:172` | MATCH | Returns `fastLava ? 4 : 2`. |
| `LavaFluid.createLegacyBlock` | `net/minecraft/world/level/material/LavaFluid.java:156` | N/A | MATCH | Calls vanilla `target.createLegacyBlock()`. |
| `LavaFluid.isSame` | `net/minecraft/world/level/material/LavaFluid.java:161` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:150` | MATCH | Returns true for `Fluids.LAVA` or `Fluids.FLOWING_LAVA`. |
| `LavaFluid.getDropOff` | `net/minecraft/world/level/material/LavaFluid.java:166` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:168` | MATCH | Returns `fastLava ? 1 : 2`. |
| `LavaFluid.canBeReplacedWith` | `net/minecraft/world/level/material/LavaFluid.java:171` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:496` | MATCH | Checks `state.getHeight(getter, pos) >= 0.44444445F && isWater(other)`. |
| `LavaFluid.getTickDelay` | `net/minecraft/world/level/material/LavaFluid.java:176` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:164` | MATCH | Returns `fastLava ? 10 : 30`. |
| `LavaFluid.getSpreadDelay` | `net/minecraft/world/level/material/LavaFluid.java:181` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:229` | MATCH | 4x delay on non-falling lava height increase when `random.nextInt(4) != 0`. |
| `LavaFluid.fizz` | `net/minecraft/world/level/material/LavaFluid.java:195` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:342` | MATCH | Documented intentional gap (level event 1501 no-op). |
| `LavaFluid.canConvertToSource` | `net/minecraft/world/level/material/LavaFluid.java:200` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:176` | MATCH | Returns `lavaSourceConversion` (default false). |
| `LavaFluid.spreadTo` | `net/minecraft/world/level/material/LavaFluid.java:205` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:335` | DIVERGES | Downward into water converts LiquidBlock to STONE, but non-LiquidBlock path inherits campfire/candle gap. |
| `LavaFluid.isRandomlyTicking` | `net/minecraft/world/level/material/LavaFluid.java:222` | N/A | N/A | Governs random tick registration. |
| `LavaFluid.getExplosionResistance` | `net/minecraft/world/level/material/LavaFluid.java:227` | N/A | N/A | Explosion physics (100.0F). |
| `LavaFluid.getPickupSound` | `net/minecraft/world/level/material/LavaFluid.java:232` | N/A | N/A | Bucket sound event. |
| `LavaFluid.isFastLava` | `net/minecraft/world/level/material/LavaFluid.java:236` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:89` | MATCH | Set from dimension environment attribute `FAST_LAVA`. |
| `LiquidBlock.codec` | `net/minecraft/world/level/block/LiquidBlock.java:62` | N/A | N/A | Serialization codec. |
| `LiquidBlock.getCollisionShape` | `net/minecraft/world/level/block/LiquidBlock.java:81` | N/A | N/A | Entity collision logic (striders, frost walker). |
| `LiquidBlock.ifMobIsColliding` | `net/minecraft/world/level/block/LiquidBlock.java:97` | N/A | N/A | Helper for collision shape. |
| `LiquidBlock.isRandomlyTicking` | `net/minecraft/world/level/block/LiquidBlock.java:104` | N/A | N/A | Delegates to FluidState. |
| `LiquidBlock.randomTick` | `net/minecraft/world/level/block/LiquidBlock.java:109` | N/A | N/A | Delegates to FluidState. |
| `LiquidBlock.propagatesSkylightDown` | `net/minecraft/world/level/block/LiquidBlock.java:114` | N/A | N/A | Lighting engine property. |
| `LiquidBlock.isPathfindable` | `net/minecraft/world/level/block/LiquidBlock.java:119` | N/A | N/A | Mob navigation. |
| `LiquidBlock.getFluidState` | `net/minecraft/world/level/block/LiquidBlock.java:124` | N/A | MATCH | Serviced via vanilla `BlockState.getFluidState()`. |
| `LiquidBlock.skipRendering` | `net/minecraft/world/level/block/LiquidBlock.java:130` | N/A | N/A | Client face culling. |
| `LiquidBlock.getRenderShape` | `net/minecraft/world/level/block/LiquidBlock.java:135` | N/A | N/A | Client rendering shape. |
| `LiquidBlock.getDrops` | `net/minecraft/world/level/block/LiquidBlock.java:140` | N/A | N/A | Loot table handling. |
| `LiquidBlock.getShape` | `net/minecraft/world/level/block/LiquidBlock.java:145` | N/A | N/A | Block outline shape. |
| `LiquidBlock.onPlace` | `net/minecraft/world/level/block/LiquidBlock.java:150` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:553, 605` | MATCH | `liquidPlacedOrNeighborChanged` called from `afterSetBlock`. (Bubble column is documented gap). |
| `LiquidBlock.tick` | `net/minecraft/world/level/block/LiquidBlock.java:162` | N/A | N/A | Only updates bubble columns (documented intentional gap). |
| `LiquidBlock.updateShape` | `net/minecraft/world/level/block/LiquidBlock.java:170` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:560, 783` | MATCH | `liquidUpdateShape` reschedules if either side is source. |
| `LiquidBlock.shouldBubbleColumnOccupy` | `net/minecraft/world/level/block/LiquidBlock.java:191` | N/A | N/A | Documented intentional gap. |
| `LiquidBlock.neighborChanged` | `net/minecraft/world/level/block/LiquidBlock.java:196` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:553, 740` | MATCH | `liquidPlacedOrNeighborChanged` checks `shouldSpreadLiquid` and schedules. |
| `LiquidBlock.tryScheduleBubbleBlockColumn` | `net/minecraft/world/level/block/LiquidBlock.java:209` | N/A | N/A | Documented intentional gap. |
| `LiquidBlock.shouldSpreadLiquid` | `net/minecraft/world/level/block/LiquidBlock.java:215` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:567` | MATCH | Exact check for water (obsidian/cobblestone) and soul soil + blue ice (basalt). |
| `LiquidBlock.fizz` | `net/minecraft/world/level/block/LiquidBlock.java:241` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:577` | MATCH | Documented intentional gap (level event 1501 no-op). |
| `LiquidBlock.createBlockStateDefinition` | `net/minecraft/world/level/block/LiquidBlock.java:246` | N/A | N/A | Block properties definition. |
| `LiquidBlock.pickupBlock` | `net/minecraft/world/level/block/LiquidBlock.java:251` | N/A | MISSING | Player bucket collection of still liquid setting AIR is not implemented. |
| `LiquidBlock.getPickupSound` | `net/minecraft/world/level/block/LiquidBlock.java:261` | N/A | N/A | Sound event. |
| `SimpleWaterloggedBlock.canPlaceLiquid` | `net/minecraft/world/level/block/SimpleWaterloggedBlock.java:20` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:486` | MATCH | Serviced via `container.canPlaceLiquid(...)`. |
| `SimpleWaterloggedBlock.placeLiquid` | `net/minecraft/world/level/block/SimpleWaterloggedBlock.java:25` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:355` | MATCH | Sets `WATERLOGGED=true` and schedules water tick delay (5). |
| `SimpleWaterloggedBlock.pickupBlock` | `net/minecraft/world/level/block/SimpleWaterloggedBlock.java:39` | N/A | MISSING | Bucket extraction of water from waterlogged block is not implemented. |
| `SimpleWaterloggedBlock.getPickupSound` | `net/minecraft/world/level/block/SimpleWaterloggedBlock.java:53` | N/A | N/A | Sound event. |
| `LiquidBlockContainer.canPlaceLiquid` | `net/minecraft/world/level/block/LiquidBlockContainer.java:13` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:486` | MATCH | Dispatches to container block instance method. |
| `LiquidBlockContainer.placeLiquid` | `net/minecraft/world/level/block/LiquidBlockContainer.java:15` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:355` | DIVERGES | Only `SimpleWaterloggedBlock` handled, and block-specific overrides are bypassed. |
| `BucketPickup.pickupBlock` | `net/minecraft/world/level/block/BucketPickup.java:13` | N/A | MISSING | Interface method not implemented anywhere in Kestrel. |
| `BucketPickup.getPickupSound` | `net/minecraft/world/level/block/BucketPickup.java:15` | N/A | N/A | Sound event. |
| `CollectingNeighborUpdater.shapeUpdate` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:37` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:618, 768` | MATCH | Queues `ShapeUpdate` for 6 directions in `UPDATE_SHAPE_ORDER`. |
| `CollectingNeighborUpdater.neighborChanged(pos, block, orientation)` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:51` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:678, 750` | MATCH | Queues `SingleNeighbor` update. |
| `CollectingNeighborUpdater.neighborChanged(state, pos, block, ...)` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:56` | N/A | N/A | FullNeighborUpdate variant used for pistons / pre-read states. |
| `CollectingNeighborUpdater.updateNeighborsAtExceptFromFacing` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:63` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:615, 722` | MATCH | Queues `NeighborsAt` update. |
| `CollectingNeighborUpdater.addAndRun` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:69` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:793` | MATCH | Re-entrant check (`count > 0`), pushes to `addedThisLayer` or stack, runs updates. |
| `CollectingNeighborUpdater.runUpdates` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:88` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:810` | MATCH | Drains `addedThisLayer` in reverse order into stack; inner loop runs until empty. |
| `CollectingNeighborUpdater.MultiNeighborUpdate` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:129` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:722` | MATCH | `NeighborsAt` iterates `UPDATE_ORDER`, skips direction, updates liquid blocks. |
| `CollectingNeighborUpdater.ShapeUpdate` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:191` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:768` | MATCH | `ShapeUpdate` updates liquids or delegates to `current.updateShape`. |
| `CollectingNeighborUpdater.SimpleNeighborUpdate` | `net/minecraft/world/level/redstone/CollectingNeighborUpdater.java:206` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:750` | MATCH | `SingleNeighbor` executes `liquidPlacedOrNeighborChanged`. |
| `NeighborUpdater.executeShapeUpdate` | `net/minecraft/world/level/redstone/NeighborUpdater.java:36` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:780` | MATCH | Calls `liquidUpdateShape` or `current.updateShape` with capturing tick sink. |
| `NeighborUpdater.executeUpdate` | `net/minecraft/world/level/redstone/NeighborUpdater.java:61` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:553, 740` | MATCH | Invokes `liquidPlacedOrNeighborChanged`. |
| `DiodeBlock.updateNeighborsInFront` | `net/minecraft/world/level/block/DiodeBlock.java:180` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:675` | MATCH | `diodeFront` queues `SingleNeighbor` and `NeighborsAt` in front of facing. |
| `DiodeBlock.onPlace` | `net/minecraft/world/level/block/DiodeBlock.java:169` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:669` | DIVERGES | Checks only `Blocks.REPEATER`, omitting `Blocks.COMPARATOR`. |
| `DiodeBlock.affectNeighborsAfterRemoval` | `net/minecraft/world/level/block/DiodeBlock.java:174` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:650` | DIVERGES | Checks only `Blocks.REPEATER`, omitting `Blocks.COMPARATOR`. |
| `RedStoneWireBlock.onPlace` | `net/minecraft/world/level/block/RedStoneWireBlock.java:302` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:658` | MATCH | Updates vertical positions (`pos.above()`, `pos.below()`) and `neighboringWires`. |
| `RedStoneWireBlock.affectNeighborsAfterRemoval` | `net/minecraft/world/level/block/RedStoneWireBlock.java:315` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:635` | MATCH | Updates 6 directions around wire and `neighboringWires`. |
| `RedStoneWireBlock.updateNeighborsOfNeighboringWires` | `net/minecraft/world/level/block/RedStoneWireBlock.java:326` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:692` | MATCH | Iterates horizontal neighbors, checks conductors for above/below corner wire. |
| `RedStoneWireBlock.checkCornerChangeAt` | `net/minecraft/world/level/block/RedStoneWireBlock.java:341` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:706` | MATCH | If wire at pos, updates pos and all 6 adjacent directions. |
| `RedstoneTorchBlock.affectNeighborsAfterRemoval` | `net/minecraft/world/level/block/RedstoneTorchBlock.java:57` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:640` | MATCH | Updates all 6 directions around torch. |
| `LeverBlock.affectNeighborsAfterRemoval` | `net/minecraft/world/level/block/LeverBlock.java:120` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:644` | MATCH | Updates pos and connected face if powered. |
| `LevelTicks.schedule` | `net/minecraft/world/ticks/LevelTicks.java:71` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1012` | MATCH | `scheduleFluidTick` rejects duplicate `(pos, fluid)` entries and preserves first schedule. |
| `LevelTicks.tick` | `net/minecraft/world/ticks/LevelTicks.java:81` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1078` | MATCH | `drainFluidTicks` collects up to 65536 ticks due by `gameTime`, removes from pending, runs callback. |
| `LevelTicks.willTickThisTick` | `net/minecraft/world/ticks/LevelTicks.java:208` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1034` | MATCH | `willFluidTickThisTick` queries `fluidToRunThisTick` set. |
| `LevelTicks.hasScheduledTick` | `net/minecraft/world/ticks/LevelTicks.java:202` | `core/src/vanilla/java/dev/kestrel/loop/TickLoop.java:1029` | MATCH | `hasScheduledFluidTick` queries `fluidIndex` map. |
| `ServerLevel.tickFluid` | `net/minecraft/server/level/ServerLevel.java:809` | `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:201` | MATCH | `tickFluid` verifies `fluidState.getType() == fluid` before ticking. |

---

## 2. Divergences

### Divergence 1: Unextinguished Lit Campfires and Candles on Water Ingress
- **Ranking**: 1 of 4 (Highest likelihood in normal play).
- **Vanilla Source**: `net/minecraft/world/level/block/CampfireBlock.java:205-218`, `net/minecraft/world/level/block/CandleBlock.java:150-164`, `net/minecraft/world/level/material/FlowingFluid.java:267-269`
  > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
- **Kestrel Source**: `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:346-363`
  ```java
  if (state.getBlock() instanceof LiquidBlockContainer container) {
      placeLiquid(container, pos, state, target);
  }

  private void placeLiquid(LiquidBlockContainer container, BlockPos pos, BlockState state, FluidState fluid) {
      if (!(container instanceof SimpleWaterloggedBlock)) {
          return;
      }
      if (!state.getValue(BlockStateProperties.WATERLOGGED) && fluid.getType() == Fluids.WATER) {
          loop.setBlock(pos, state.setValue(BlockStateProperties.WATERLOGGED, true), 3);
          schedule(pos, fluid.getType(), tickDelay(Kind.WATER));
      }
  }
  ```
- **Explanation**: In vanilla, `FlowingFluid.spreadTo` delegates fluid placement to the block container's virtual `placeLiquid` method. `CampfireBlock` and `CandleBlock` override `placeLiquid` to set `LIT = false` when water enters their cell. Kestrel re-implements `placeLiquid` as a private helper inside `FluidFlow` that hardcodes `state.setValue(WATERLOGGED, true)`. It never invokes the block's own `placeLiquid` override, leaving `LIT` unchanged.
- **Concrete World Setup**:
  1. At (1, 64, 0), place an active lit campfire: `Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true)`.
  2. At (0, 64, 0), place a water source: `Blocks.WATER.defaultBlockState()`.
  3. Advance the tick loop by 5 ticks.
  4. In vanilla, at tick 5 water flows east into (1, 64, 0). The block becomes `minecraft:campfire[lit=false,waterlogged=true]`.
  5. In Kestrel, at tick 5 water flows east into (1, 64, 0). The block becomes `minecraft:campfire[lit=true,waterlogged=true]`. The campfire remains burning underwater.

---

### Divergence 2: Missing `ComparatorBlock` Front Diode Updates
- **Ranking**: 2 of 4 (Moderate likelihood; hit whenever water washes away redstone circuits).
- **Vanilla Source**: `net/minecraft/world/level/block/DiodeBlock.java:169-178`, `net/minecraft/world/level/block/ComparatorBlock.java:31`
  > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
- **Kestrel Source**: `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:650-652, 669-671`
  ```java
  // removalFanOut
  } else if (b == Blocks.REPEATER) {
      diodeFront(pos, old);
  }

  // placeFanOut
  } else if (b == Blocks.REPEATER) {
      diodeFront(pos, state);
  }
  ```
- **Explanation**: In vanilla, `ComparatorBlock` is a subclass of `DiodeBlock` and inherits `updateNeighborsInFront` on both `onPlace` and `affectNeighborsAfterRemoval`. When a comparator is washed away by water or placed adjacent to fluid, vanilla dispatches `updateNeighborsInFront` to the block directly in front of the comparator. Kestrel tests strictly for `b == Blocks.REPEATER` and ignores `Blocks.COMPARATOR`.
- **Concrete World Setup**:
  1. At (0, 64, 0), place a comparator facing EAST: `Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.EAST)`.
  2. At (1, 64, 0), place still water: `Blocks.WATER.defaultBlockState()`.
  3. At (-1, 64, 0), place a water source that spreads into and washes away the comparator at (0, 64, 0).
  4. In vanilla, breaking the comparator triggers `DiodeBlock.affectNeighborsAfterRemoval`, which runs `diodeFront` on (1, 64, 0) and notifies the water block.
  5. In Kestrel, `removalFanOut` ignores `Blocks.COMPARATOR`. No front neighbor update is dispatched to (1, 64, 0). If the water block was awaiting a neighbor update to wake up, it misses the reschedule.

---

### Divergence 3: Omission of Secondary Removal Fan-Out for Buttons, Pressure Plates, and Observers
- **Ranking**: 3 of 4 (Low likelihood; requires redstone trigger blocks to be active when washed away).
- **Vanilla Source**: `net/minecraft/world/level/block/ButtonBlock.java:125-129`, `net/minecraft/world/level/block/BasePressurePlateBlock.java:120-125`, `net/minecraft/world/level/block/ObserverBlock.java:126-130`
  > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
- **Kestrel Source**: `core/src/vanilla/java/dev/kestrel/loop/FluidFlow.java:633-653`
  ```java
  private void removalFanOut(BlockPos pos, BlockState old) {
      Block b = old.getBlock();
      if (b == Blocks.REDSTONE_WIRE) { ... }
      else if (b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH) { ... }
      else if (b == Blocks.LEVER) { ... }
      else if (b == Blocks.REPEATER) { ... }
  }
  ```
- **Explanation**: In vanilla, when a powered button or powered pressure plate is washed away by liquid, `affectNeighborsAfterRemoval` fires neighbor updates not only around `pos`, but also around the support block (the attached wall block for a button, or `pos.below()` for a pressure plate). Kestrel's `removalFanOut` explicitly models this support fan-out for `Blocks.LEVER`, but omits `ButtonBlock` and `BasePressurePlateBlock`.
- **Concrete World Setup**:
  1. At (0, 64, 0), place an oak button attached to a stone block at (0, 64, 1), in powered state (`POWERED=true`).
  2. At (0, 64, 2) (behind the support block), place a liquid block.
  3. Wash away the button at (0, 64, 0) with water.
  4. In vanilla, the powered button removal updates the attached block at (0, 64, 1), which in turn sends a neighbor update to (0, 64, 2), waking the liquid.
  5. In Kestrel, `removalFanOut` does nothing for `Blocks.OAK_BUTTON`. Only the direct neighbors of (0, 64, 0) receive updates via `afterSetBlock`; (0, 64, 2) is never reached.

---

### Divergence 4: Missing `BucketPickup.pickupBlock` Mechanics
- **Ranking**: 4 of 4 (Gameplay functionality; does not affect passive fluid flow simulation).
- **Vanilla Source**: `net/minecraft/world/level/block/LiquidBlock.java:251-258`, `net/minecraft/world/level/block/SimpleWaterloggedBlock.java:39-50`
  > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
- **Kestrel Source**: None. No method in `FluidFlow.java` or `TickLoop.java` implements `pickupBlock`.
- **Explanation**: Vanilla fluid blocks and waterlogged blocks implement `BucketPickup` to allow players or dispensers to collect still liquid with a bucket, replacing the block with air (flags 11) or un-waterlogging the block (flags 3). Kestrel lacks player bucket interaction and dispenser pickup handlers for fluids.
- **Concrete World Setup**:
  1. A player right-clicks a still water source or waterlogged stair with an empty bucket.
  2. In vanilla, the bucket fills with water, and the world cell becomes air or un-waterlogged.
  3. In Kestrel, no handler processes the bucket pickup.

---

## 3. Tag-Free Equivalents

Because Kestrel's live server binds no data tags, `FluidFlow` substitutes tag checks with hardcoded block and fluid checks. Each set was audited against the datapack JSON tags in `~/kestrel-run/packdump/data`.

### 3.1 `FluidTags.WATER`
- **Vanilla Tag**: `data/minecraft/tags/fluid/water.json`
  ```json
  {
    "values": [
      "minecraft:water",
      "minecraft:flowing_water"
    ]
  }
  ```
- **Kestrel Equivalent**: `FluidFlow.java:146-148`:
  `static boolean isWater(Fluid f) { return f == Fluids.WATER || f == Fluids.FLOWING_WATER; }`
- **Audit Result**: EXACT MATCH.
  - Missing: None.
  - Extra: None.

### 3.2 `FluidTags.LAVA`
- **Vanilla Tag**: `data/minecraft/tags/fluid/lava.json`
  ```json
  {
    "values": [
      "minecraft:lava",
      "minecraft:flowing_lava"
    ]
  }
  ```
- **Kestrel Equivalent**: `FluidFlow.java:150-152`:
  `static boolean isLava(Fluid f) { return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA; }`
- **Audit Result**: EXACT MATCH.
  - Missing: None.
  - Extra: None.

### 3.3 `BlockTags.SIGNS`
- **Vanilla Tag**: `data/minecraft/tags/block/signs.json`
  ```json
  {
    "values": [
      "#minecraft:standing_signs",
      "#minecraft:wall_signs"
    ]
  }
  ```
  - `#minecraft:standing_signs` (`tags/block/standing_signs.json`): `oak_sign`, `spruce_sign`, `birch_sign`, `acacia_sign`, `cherry_sign`, `jungle_sign`, `dark_oak_sign`, `pale_oak_sign`, `mangrove_sign`, `bamboo_sign`, `crimson_sign`, `warped_sign` (12 blocks). In `Blocks.java:1315-1402, 4776-4786`, all 12 instantiate `StandingSignBlock`.
  - `#minecraft:wall_signs` (`tags/block/wall_signs.json`): `oak_wall_sign`, `spruce_wall_sign`, `birch_wall_sign`, `acacia_wall_sign`, `cherry_wall_sign`, `jungle_wall_sign`, `dark_oak_wall_sign`, `pale_oak_wall_sign`, `mangrove_wall_sign`, `bamboo_wall_sign`, `crimson_wall_sign`, `warped_wall_sign` (12 blocks). In `Blocks.java:1433-1520, 4791-4801`, all 12 instantiate `WallSignBlock`.
- **Kestrel Equivalent**: `FluidFlow.java:471`:
  `!(block instanceof StandingSignBlock) && !(block instanceof WallSignBlock)`
- **Audit Result**: EXACT MATCH.
  - Missing: None.
  - Extra: None.
  - Note: Hanging signs (`CeilingHangingSignBlock`, `WallHangingSignBlock`) belong to `#minecraft:all_hanging_signs` and `#minecraft:all_signs`, but NOT to `#minecraft:signs`. Vanilla `FlowingFluid.java:415` specifically checks `BlockTags.SIGNS`. Furthermore, all vanilla sign blocks implement `SimpleWaterloggedBlock`, meaning they are accepted early by `if (block instanceof LiquidBlockContainer) return true;` (`FlowingFluid.java:409` and `FluidFlow.java:464`).

### 3.4 Single-Block Replacements in `canHoldAnyFluid`
Vanilla `FlowingFluid.java:416-422` lists 7 explicit non-tag block checks in `canHoldAnyFluid`:
- `!state.is(Blocks.LADDER)` -> `block != Blocks.LADDER` (`FluidFlow.java:472`). EXACT MATCH.
- `!state.is(Blocks.SUGAR_CANE)` -> `block != Blocks.SUGAR_CANE` (`FluidFlow.java:473`). EXACT MATCH.
- `!state.is(Blocks.BUBBLE_COLUMN)` -> `block != Blocks.BUBBLE_COLUMN` (`FluidFlow.java:474`). EXACT MATCH.
- `!state.is(Blocks.NETHER_PORTAL)` -> `block != Blocks.NETHER_PORTAL` (`FluidFlow.java:475`). EXACT MATCH.
- `!state.is(Blocks.END_PORTAL)` -> `block != Blocks.END_PORTAL` (`FluidFlow.java:476`). EXACT MATCH.
- `!state.is(Blocks.END_GATEWAY)` -> `block != Blocks.END_GATEWAY` (`FluidFlow.java:477`). EXACT MATCH.
- `!state.is(Blocks.STRUCTURE_VOID)` -> `block != Blocks.STRUCTURE_VOID` (`FluidFlow.java:478`). EXACT MATCH.

---

## 4. Coverage Gaps in `FluidScenarios.java`

The 23 existing scenarios in `FluidScenarios.java` cover standard flat spreading, basic hole distance finding for water, infinite water generation, basalt and obsidian/cobblestone conversions, vertical falls, and waterlogged slabs. However, several critical vanilla branches have zero test coverage.

### Proposed Scenario 1: `lava_hole_2` (Lava Slope-Finding in Overworld)
- **Gap**: Overworld lava has a slope-find distance of 2 (`LavaFluid.getSlopeFindDistance = 2`). All existing slope-finding scenarios (4, 5, 6, 7) test water only.
- **Layout**:
  - Basin floor at y=99, radius 10.
  - Closed 1-block drain hole at (2, 99, 0): `.set(2, 99, 0, AIR).set(2, 97, 0, STONE).set(1, 98, 0, STONE).set(3, 98, 0, STONE).set(2, 98, -1, STONE).set(2, 98, 1, STONE)`.
  - Trigger: `act(0, 0, 100, 0, LAVA)`.
  - Ticks: 90 ticks.
- **What it pins down**: Validates that Overworld lava detects a hole at distance 2, pruning North, South, and West directions, flowing exclusively eastward toward the hole.

### Proposed Scenario 2: `lava_hole_3_cutoff` (Lava Slope-Finding Cutoff)
- **Gap**: Overworld lava must prune holes beyond distance 2. Water reaches distance 4, but lava must stop searching at 2 and treat distance 3 as unreachable.
- **Layout**:
  - Basin floor at y=99, radius 10.
  - Closed 1-block drain hole at (3, 99, 0): `.set(3, 99, 0, AIR).set(3, 97, 0, STONE).set(2, 98, 0, STONE).set(4, 98, 0, STONE).set(3, 98, -1, STONE).set(3, 98, 1, STONE)`.
  - Trigger: `act(0, 0, 100, 0, LAVA)`.
  - Ticks: 90 ticks.
- **What it pins down**: Pins down `LavaFluid.getSlopeFindDistance` cutoff in Overworld. Because the hole is at distance 3, `getSlopeDistance` returns 1000 for all 4 directions. Lava ties and spreads symmetrically in a 4-way cross, rather than funneling toward the hole.

### Proposed Scenario 3: `water_cliff_three_sources` (`sourceNeighborCount >= 3` Downward Branch)
- **Gap**: In `FlowingFluid.spread:129-131` / `FluidFlow.java:256-258`, when fluid flows down, lateral spreading is aborted (`return;`) UNLESS `sourceNeighborCount(level, pos) >= 3`. No existing scenario exercises this branch.
- **Layout**:
  - Upper platform at y=100. Cell (0, 100, 0) has no floor below it (drops into an air pit at y=91).
  - Open ledge to the SOUTH (air at (0, 100, 1) over a drop).
  - Three water sources placed around (0, 100, 0) on the remaining sides:
    `act(0, -1, 100, 0, WATER)` (West), `act(0, 0, 100, -1, WATER)` (North), `act(0, 1, 100, 0, WATER)` (East).
  - Ticks: 20 ticks.
- **What it pins down**: Confirms that when flowing water plunges downward, having 3 horizontal source neighbors forces `spreadToSides` to also execute in the same tick, spreading water over the southern ledge while simultaneously flowing down.

### Proposed Scenario 4: `lava_onto_flowing_water` (Falling Lava on Flowing Water)
- **Gap**: Scenario 12 (`lava_onto_water_source`) only tests lava falling onto a water source. Vanilla `LavaFluid.spreadTo` turns any `is(FluidTags.WATER)` into `Blocks.STONE`.
- **Layout**:
  - Water source at (-3, 100, 0) flowing eastward so that (0, 100, 0) contains flowing water (`LEVEL = 5`).
  - Lava source placed high above at (0, 104, 0): `act(0, 0, 104, 0, LAVA)`.
  - Ticks: 120 ticks.
- **What it pins down**: Validates that falling lava converts flowing water (`LEVEL > 0`) into `Blocks.STONE` rather than cobblestone or obsidian, terminating downward lava spread.

### Proposed Scenario 5: `water_meets_double_slab` (Double Slab Fluid Rejection)
- **Gap**: Scenario 17 (`flow_into_slab`) tests waterlogging a bottom slab. No scenario verifies that double slabs (`SlabType.DOUBLE`) reject waterlogging and block fluid.
- **Layout**:
  - Basin at y=99, radius 10.
  - Double oak slab placed at (1, 100, 0): `Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE)`.
  - Water source placed at (0, 100, 0): `act(0, 0, 100, 0, WATER)`.
  - Ticks: 30 ticks.
- **What it pins down**: Verifies `SlabBlock.canPlaceLiquid` returning false for `SlabType.DOUBLE`. Validates that water treats the double slab as solid, does not waterlog it, does not overwrite it, and spreads only North, South, and West.

### Proposed Scenario 6: `lava_recede_overworld` (Lava Source Removal and Stream Drain)
- **Gap**: Scenario 21 (`water_recede`) verifies water drainage after source removal, but lava drainage involves the 4x delay check on receding lava streams.
- **Layout**:
  - Basin at y=99, radius 10.
  - Place lava source at (0, 100, 0): `act(0, 0, 100, 0, LAVA)`.
  - Allow lava to spread to radius 4 (120 ticks).
  - Remove lava source: `act(120, 0, 100, 0, AIR)`.
  - Ticks: 300 ticks.
- **What it pins down**: Exercises lava recession, transitioning non-source lava blocks to `Blocks.AIR`, and tests that the recorded random draws for receding delay match vanilla.

---

## 5. Known Intentional Gaps

The `FluidFlow.java` class Javadoc (lines 52-72) documents five intentional gaps:
1. **Update Target Scoping**: Only fluid-relevant update targets are modeled. Shape updates of non-fluid neighbors are discarded.
2. **Drops and Fizz Events**: `WaterFluid.beforeDestroyingBlock` drops and `levelEvent(1501)` fizz sounds/particles are no-ops.
3. **Bubble Columns**: Bubble columns are not modeled.
4. **Non-Waterlogged Containers**: Containers that refuse fluid in vanilla (`KelpBlock`, `SeagrassBlock`) are treated as refusing fluid.
5. **Level Random**: The level random for lava spread delay is an unpersisted seeded `RandomSource`.

### Verification of Javadoc Accuracy
- **Accuracy Verdict**: ACCURATE with one minor omission.
- **Details**:
  - The Javadoc statement that `KelpBlock` and `SeagrassBlock` refuse every fluid is confirmed line-for-line in vanilla (`KelpBlock.java:52, 57` and `SeagrassBlock.java:102, 107`).
  - Discarding non-fluid neighbor shape changes correctly explains why concrete powder hardening (`ConcretePowderBlock.java:88`) and sponge drying are not executed during fluid flow.
  - **Omission**: The Javadoc does not explicitly mention the worldgen spring ticks gap (documented in roadmap SKIPPED-FEATURES 3.7), where spring ticks generated during world generation are not ingested into the active tick queue.

---

## 6. Summary

### Verdict Counts
- **MATCH**: 64 methods / components
- **DIVERGES**: 4 methods / components (`FlowingFluid.spreadTo` / `placeLiquid` on campfires/candles, `DiodeBlock.onPlace` comparator omission, `DiodeBlock.affectNeighborsAfterRemoval` comparator omission, and `LiquidBlockContainer.placeLiquid`)
- **MISSING**: 3 methods (`LiquidBlock.pickupBlock` / `SimpleWaterloggedBlock.pickupBlock`, `BucketPickup.pickupBlock`, and `LavaFluid.randomTick`)
- **N/A**: 31 methods (client particles, sounds, entity collision, rendering, codecs, loot tables)
- **Total Methods Audited**: 102 methods across 9 classes and interfaces.

### Top 5 Items Worth Fixing First

1. **Extinguish Lit Campfires and Candles in `placeLiquid` (`FluidFlow.java:355-363`)**:
   - *Issue*: `FluidFlow.placeLiquid` sets `WATERLOGGED = true` without clearing `LIT`. Underwater campfires and candles remain lit.
   - *Fix*: Check if `state.hasProperty(BlockStateProperties.LIT)`, and if so, set `state = state.setValue(BlockStateProperties.LIT, false)` alongside `WATERLOGGED = true`.

2. **Add `Blocks.COMPARATOR` to `removalFanOut` and `placeFanOut` (`FluidFlow.java:650, 669`)**:
   - *Issue*: Comparators extend `DiodeBlock` and update the block in front when placed or washed away. `FluidFlow` checks only `b == Blocks.REPEATER`.
   - *Fix*: Expand the diode check to `if (b == Blocks.REPEATER || b == Blocks.COMPARATOR) diodeFront(pos, ...);`.

3. **Implement Lava Slope-Finding Scenario Tests**:
   - *Issue*: Zero scenarios in `FluidScenarios.java` test lava slope finding. Overworld distance 2 and Nether distance 4 are completely unexercised by the test harness.
   - *Fix*: Add `lava_hole_2` and `lava_hole_3_cutoff` to `FluidScenarios.java` to pin down lava slope-finding parity.

4. **Implement `sourceNeighborCount >= 3` Downward Branch Scenario Test**:
   - *Issue*: The simultaneous downward and lateral spread branch in `FlowingFluid.spread` has zero coverage in the test corpus.
   - *Fix*: Add `water_cliff_three_sources` to `FluidScenarios.java`.

5. **Support Secondary Removal Updates for Powered Buttons and Pressure Plates (`FluidFlow.java:633`)**:
   - *Issue*: When water washes away a pressed button or pressure plate, neighbor updates to the supporting block behind/below are skipped.
   - *Fix*: In `removalFanOut`, check if `old.getBlock() instanceof ButtonBlock` (if powered, update connected face) and `old.getBlock() instanceof BasePressurePlateBlock` (if signal > 0, update `pos.below()`).
