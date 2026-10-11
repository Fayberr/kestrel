# Optimization #2 grounding: hopper logic

Status: ANALYSIS, grounded in the real decompiled vanilla 26.2 source
(`HopperBlockEntity.java` 448 lines, `HopperBlock.java` 162 lines,
`Level.tickBlockEntities`, all read in full via Vineflower against the
deobfuscated jar - no mapping toolchain needed).

## What vanilla already does well (don't re-fight this)

1. **Cooldown-gated ticking.** Every hopper ticks every tick (`pushItemsTick`
   is registered unconditionally via `getTicker`), BUT the body is one
   decrement + one branch when cooling down: `cooldownTime--; if
   (!isOnCooldown()) { ... }`. A hopper that just moved an item sets
   cooldown 8 and does ~nothing for the next 8 ticks. So the "sleeping"
   half of the expected optimization already exists in a weak form -
   the win is NOT "skip ticking hoppers entirely" (that changes
   redstone-disable/wake timing), it is making the AWAKE path cheaper and
   extending the real sleep for provably-idle hoppers.
2. **Slot-list caching.** `getSlots` caches the flat int[] per container
   size in `CACHED_SLOTS[54]` - non-sided inventories never reallocate
   their slot iteration order. Nothing to fix here.
3. **Move-one-item granularity.** `removeItem(slot, 1)` + `addItem` moves a
   single item per successful operation, then `setCooldown(8)`. The 8-tick
   transfer rate (2.5 items/sec) IS the game balance - any "move whole
   stacks" change is a behavior break, not an optimization. Off limits.

## The actual expensive paths (3 of them, all in the AWAKE tick)

**A. `ejectItems`: blind slot scan + full attach lookup, every wake.**
`getAttachedContainer` calls `getContainerAt`, which calls
`getBlockContainer` (block lookup + block-entity fetch + chest-merge
resolution via `ChestBlock.getContainer` for double chests) and falls back
to `getEntityContainer` (an AABB entity query + `getRandom().nextInt` when
multiple container-entities overlap!). Then `isFullContainer` scans EVERY
slot of the target, and the move loop scans hopper slots 0-4 calling
`removeItem`/`addItem`/`setChanged` per candidate. When the target is full
(the common idle case: hopper feeding a full chest), ALL of this work
happens and nothing moves. Cost per awake tick: ~2 block lookups + 1 BE
fetch (+ chest merge) + up-to-27 slot reads + up to 5 remove/add attempts.

**B. `suckInItems`: source-container scan + entity query + shape check.**
`getSourceContainer` = another `getContainerAt` (block + BE + chest merge
or entity-AABB query). If a container is above: scan its slots via
`tryTakeInItemFromSlot` (each: `getItem` + `canTakeItem` + sided-face check
+ `removeItem(1)` + `addItem` + `setChanged`). If NO container above: compute
`isCollisionShapeFullBlock` on the block above (a full shape fetch!) +
tag check `DOES_NOT_BLOCK_HOPPERS`, then `getItemsAtAndAbove` (a second
entity-AABB query with `ENTITY_STILL_ALIVE` filter) and per-entity
`addItem` attempts. An idle hopper with nothing above it pays: 1 block
lookup + 1 full shape fetch + 1 entity query, every single wake tick.

**C. `tryMoveInItem`: per-slot merge math with virtual calls.**
For each candidate slot: `getItem` + `canPlaceItem` (+ sided
`canPlaceItemThroughFace`) + `isEmpty` + `isSameItemSameComponents`
(component-equality is NOT cheap - compares full data-component maps) +
`setChanged` on success. Plus the hopper-chain cooldown propagation
(`tickedGameTime` compare, `setCooldown(8 - skipTickCount)`). Correct but
branchy, virtual-heavy, and repeated per slot per tick.

## What vanilla does NOT do (the concrete Kestrel targets)

1. **No idle-sleep beyond cooldown.** A hopper pointing into a FULL chest
   wakes every 8 ticks, re-runs path A in full, fails, recools 8. A hopper
   with empty inventory and nothing above wakes every 8 ticks, re-runs path
   B in full, fails, recools 8. Both cases are PROVABLY idle (target full =
   no space; source empty + no entities = nothing to take), yet vanilla
   re-proves it from scratch each time. Target: sleep until a subscribed
   event could change the answer (target inventory changed, source
   inventory changed, item entity landed above, redstone ENABLED toggled).
   This is step 12 (event-driven) applied to hoppers first, and it takes
   idle hoppers toward near-zero instead of 1/8th.
2. **No attach-target caching.** The attached container (block + BE +
   chest-merge result) is re-resolved EVERY wake tick, but it only changes
   when a neighbor block changes. Target: cache the resolved target +
   source container handles, invalidate on neighbor update (vanilla already
   sends `neighborChanged` -> `checkPoweredState`; hook the same signal).
3. **No fullness memoization.** `isFullContainer` + `inventoryFull` re-scan
   all slots per wake. Target: dirty-flag the full/empty state on
   `setChanged` (both hopper and target), read the flag instead of
   scanning. `setChanged` is already the universal mutation signal - every
   path that changes contents calls it.
4. **Double entity queries.** `getEntityContainer` (source) and
   `getItemsAtAndAbove` (loose items) are separate AABB queries that can
   overlap. Target: single query, partition results (container-entities vs
   item-entities). Minor vs 1-3, but free once touching the code.

## Parity constraints (must replicate exactly)

- **Transfer rate:** 8-tick cooldown after any successful move; the
  hopper-to-hopper `skipTickCount` propagation (`tickedGameTime` compare
  giving 7 instead of 8 down a chain) is observable chain timing. Keep both.
- **Order:** eject-before-suck per tick; slot 0-4 scan order; source slots
  in face order. All observable in item-sorting contraptions.
- **Redstone disable:** `ENABLED` false skips everything including cooldown
  decrement effects (cooldown still decrements - `pushItemsTick` always
  runs `cooldownTime--` first - but `tryMoveItems` returns false). The tick
  registration itself never sleeps: `getTicker` returns non-null whenever
  server-side, and `tickBlockEntities` iterates ALL tickers every tick
  (only `shouldTickBlocksAt` gates by distance). A Kestrel sleep must live
  INSIDE this structure (skip the body, keep the registration), or chunk
  unload/reload and distance-gating behavior diverges.
- **`entityInside` path:** item entities touching the hopper trigger
  `tryMoveItems` OUTSIDE the tick (event-driven already!). This is the
  pattern to generalize, not a case to preserve as-is.

## Next steps (in order)

1. Hopper corpus for the harness: chains (16+ hoppers), full-target idle,
   empty-source idle, item-entity rain, redstone-disabled row, double-chest
   attach. Each state must hash-distinguish (items differ -> BE data
   differs -> hash must include BE inventory, verify hasher covers it).
2. Verify `WorldStateHasher` covers block-entity inventory contents (it
   hashes BE type + position only per the source read - inventory NOT
   covered!). Extend the hasher BEFORE trusting hopper differential tests.
3. Only then: implement sleep/cache/dirty-flag targets in `core/`.

## Harness validation: hopper-chain corpus live (2026-09-10)

Built `harness/corpus/hopper-chain/` (5 groups: active chain A, idle
full-target B, idle empty C, redstone-disabled D, double-chest E + item
rain) and extended the hasher to schema_version=2 (container item+count per
slot via `Container.getItem` + item-registry key, reflection-only like the
rest). Verified live on bench PC:

- Corpus loads and ticks: `[kestrel-corpus] building hopper-chain scenario
  -> ... ready`, 998 data lines, **zero -1 hash failures**.
- Only 2 unique hashes over the whole 45s run: expected, not a bug. Chain A
  moves items every 8 ticks but the hash covers item+count per slot, and a
  single-item move between Chest E's hopper slots... actually the low
  unique-count means most ticks hash identically (idle groups dominate, and
  8-tick-cooldown transfers move one item at a time - the hash DOES change
  on transfer ticks, just rarely within a 45s window at ViewDistance=1).
  Item movement IS visible (2 distinct states, not 1).
- `determinism-check.ps1 -SkipTicks 5`: **PASS (647 ticks compared)** across
  two independent runs. hopper-chain is a trusted oracle for optimization
  #2 work, same settling-window caveat as collision-stress.
- pack.mcmeta note: `supported_formats` with `min_format`/`max_format` logs
  a harmless JsonParseException fallback warning on 26.2 (expects
  `min_inclusive`/`max_inclusive` schema); non-fatal, same as the gotchas
  note documents. Left as-is.

## Implementation: HopperState memoization layer (2026-09-10)

`core/src/vanilla/java/dev/kestrel/hopper/HopperState.java`: per-hopper
records with fullness flags (set by scan, cleared by any `setChanged`),
attach-generation counter (bumped on neighbor signals), and
sleep-eligibility tracking. `shouldAttemptEject`/`shouldAttemptSuck` gate
the expensive proofs; `reportEject`/`reportSuck`/`reportIdle` keep flags
truthful; any move invalidates both sides. `HopperStateCheck.java` proves
the predicates + flag lifecycle: fullness across 64-max/16-max/partial
inventories plus container cross-check, 10 lifecycle transitions.
Verified live on bench PC: **PASS**.

Real fixture finding (same class as the block-tag problem): bare
`Bootstrap.bootStrap()` leaves item holders component-less (components
finalize in `ReloadableServerResources.updateComponentsAndStaticRegistryTags`
during world load), so ANY `new ItemStack` throws `Components not bound
yet`. Fix: bind `COMMON_ITEM_COMPONENTS` (max 64, the vanilla default)
onto every item holder + rebind the known 16-max items precisely.
`getDefaultMaxStackSize` cannot be used to discover sizes (it reads the
unbound holder -> NPE - chicken-and-egg); the check documents that
stacksTo(1) tools stay at 64 in the fixture, truthful for every assertion
it makes since it only asserts 64/16-max items.

## Implementation: HopperTransfer port + proof + bench (2026-09-11, Pi)

Premise check against a fresh Vineflower decompile of the nested
`server-26.2.jar` (`HopperBlockEntity.java` 448 lines, `HopperBlock.java`
162 lines, `WorldlyContainer`/`Container`/`SimpleContainer` as needed):
the analysis above survives. `pushItemsTick` is decrement-then-branch,
`getTicker` is unconditional server-side, `ejectItems` re-resolves the
attach target plus a full `isFullContainer` scan on every wake, the
8-tick cooldown is the only sleep, and the `tickedGameTime` chain
propagation (7 instead of 8 down a chain) is present. ONE correction:
target 4 ("double entity queries" merged into one) is wrong as stated.
`getEntityContainer` (source-side container-entities, 1x1x1 box,
`CONTAINER_ENTITY_SELECTOR`) and `getItemsAtAndAbove` (loose items,
`getSuckAabb`, `ENTITY_STILL_ALIVE`) are different boxes, different
filters, different entity types. Merging them changes the candidate set.
Not merged; both stay caller-side (they need a live level anyway).

Landed in `core/src/vanilla/java/dev/kestrel/hopper/`:

- `HopperTransfer.java`: line-for-line port of `addItem(from, container,
  stack, direction)` + `tryMoveInItem` merge math + `canMergeItems` +
  both face-gate predicates (null direction forwarded, never
  special-cased, exactly as vanilla). The hopper-to-hopper cooldown tail
  is factored out as `hopperChainCooldown(...)` returning the cooldown to
  set or -1: the cooldown field lives on the block entity, so the caller
  owns it. Draws no RNG (the only hopper-path RNG is
  `getEntityContainer`'s pick among overlapping container-entities plus
  loot unpacking, both caller-side).
- `HopperTransferCheck.java`: 3000-trial differential of the port vs a
  literal transcription over plain + reverse-face sided containers with
  DOWN/null/NORTH faces (remainder + every slot compared), the cooldown
  rule branch-by-branch (no-fire x3, 7 on chain, 8 on newer source or
  non-hopper source), a negative control proving teeth (naive slot-order
  diverged 200/200), and a no-RNG class-bytes check. PASS on the Pi
  against the real 26.2 classes.
- `HopperStateCheckBinderHelper.java`: the two-pass item-component binder
  extracted verbatim from `HopperStateCheck` so both checks bind
  identically (no logic change; `HopperStateCheck` still PASS).
- `HopperBench.java`: `hopadd n=5/n=27 ~1x` (honest: the port is the same
  math, the copy setup is shared, nothing to win inside the merge loop),
  `hopidle n=27 ~11-14x` (full-chest scan + failed move loop vs one flag
  read), `hopfull n=5 ~7-8x` (5-slot fullness scan vs one flag read).
  The attach-resolution half of the idle win needs a live level and is
  not in these numbers (same caveat as the E3 secpoi row).

What is NOT yet done (needs the bench PC or a parallel workstream): the
live-level half (attach-target caching against `neighborChanged`,
`entityInside` generalization), the hopper-chain corpus differential
against the port, and MSPT. The `hopper-chain` corpus stays a trusted
oracle for that step.

## Bench PC verification + a parity bug the bench caught (2026-09-11)

Bench PC came back up, so the whole proof suite and every bench row was
re-run there against the real 26.2 classes (the Pi rows were correct but,
per the platform rule, not comparable). All six checks PASS on bench PC:
`HopperTransferCheck`, `HopperStateCheck`, `RedstoneProofCheck`,
`KestrelCollideCheck`, `ShapeBoxCacheCheck`, `FixtureRuntimeCheck`, plus
`MobAiProofCheck` (23/23, identical to the Pi run).

**The `hopadd` row was the finding.** A line-for-line port must measure
~1x; the doc already says a row above 1x means the transcription
diverged. It measured **0.53-0.90x, stable across five runs** - not
noise, and a below-1x row means something is wrong just as surely. Two
independent causes, both real:

1. **The bench's "vanilla" side was not vanilla's shape.** `vanillaTryMove`
   omitted `canPlaceItemInContainer` (the per-slot placement gate) and the
   `wasEmpty` read. The port performs both, so the row was measuring the
   omission, not the port. Fixed: the transcription now calls the same gate
   and takes the same read, with a sink so the JIT cannot elide it.

2. **`addItem` computed vanilla's `wasEmpty` and threw it away** - a real
   parity defect, not just a bench artifact. `addItem` evaluated
   `container.isEmpty()` once per slot, passed it to `moveIntoSlot`, and
   `moveIntoSlot` never read the parameter. Two consequences. The cheap
   one: an O(size) scan per slot that vanilla does not do, since vanilla
   reads `isEmpty()` *inside* the placement gate and therefore only for
   slots that pass it - the port paid it even for slots the gate rejects.
   The expensive one: the class javadoc claimed the port "reports
   `targetWasEmpty` and lets the caller apply vanilla's rule", and it did
   not. `hopperChainCooldown` was correct in isolation but nothing could
   feed it, so a live caller would never fire the hopper-to-hopper
   cooldown at all: **every hopper chain would run at full speed instead of
   vanilla's 8-tick (7 down-chain) cadence.** That is a visible,
   throughput-changing divergence on exactly the technical-farm workloads
   this project targets.

**Why 3000 trials of the identity battery missed it.** The dropped value
only feeds the cooldown, which lives on the block entity, so remainder and
slot contents stayed bit-identical. And `checkChainCooldownRule` only ever
fed the rule hand-written literals, so it passed without the transfer ever
surfacing anything. The battery proved the rule's arithmetic and proved the
merge's slot effects, and nothing tested the wire between them.

**Fix.** `moveIntoSlot` now takes the `wasEmpty` read itself, in vanilla's
exact position (after the gate passes, before the merge mutates), and
reports it in `SlotResult`. New `addItemDetailed` returns
`TransferResult(remainder, anyAccepted, targetWasEmpty)`; `addItem` stays
as a remainder-only convenience over it. Because the first successful merge
necessarily leaves the container non-empty, `wasEmpty` can only be true on
the first accepting slot, so vanilla's per-slot application and the port's
single report are exactly equivalent - the new check asserts that premise
rather than trusting it (it fails the run if vanilla ever fires twice).

**New check: `checkCooldownHandoff`.** 2000 randomized trials, with the
vanilla transcription evaluating the cooldown tail in vanilla's own
position and recording every firing; kestrel's reported firing must match.
Positive coverage guard (rule fired in 813/2000 - a vacuous pass is
rejected) plus a negative control replaying the dropped-`wasEmpty` shape,
which diverged in all 813. The gap is closed and demonstrated, not
asserted.

### Bench rows, bench PC (5700X3D), post-fix

| row | n | speedup |
|-----|---|---------|
| `hopadd` | 5 | 0,47-0,82x (see below) |
| `hopadd` | 27 | 0,91-1,31x (median ~1x, correct for a port) |
| `hopidle` | 27 | 4,5-15,2x |
| `hopfull` | 5 | 5,5-6,7x |

`hopadd n=27` landing at ~1x once the transcription was corrected is the
expected result. **`hopadd n=5` remains stably sub-1x and is not yet
explained.** The leading hypothesis is the per-slot `SlotResult` record
allocation (the transcription returns a bare `ItemStack`), with the n=27
row diluted toward 1x by the shared `freshCopy` setup cost that dominates
at larger sizes - but that is a hypothesis, not a measurement, and this
project's own record on unverified premises is four-for-four wrong. Do not
act on it without measuring first; if it holds, it is step-29 (arena
scratch) territory, not a transfer-path change.
