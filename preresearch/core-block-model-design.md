---
status: design decision, not yet fully implemented
updated: 2026-09-10
---

# core/ block model design: what optimization #1 actually needs to exist

`collision-path-analysis.md` flagged the real remaining blocker before any
optimization #1 code can land: `core/` has no block/chunk/entity model at
all. This doc resolves the architecture question that blocker raises,
before writing that code, matching how every prior step in this project
has worked (ground the decision first, then build).

## The question

Optimization #1's fast path only covers 94.8% of blockstates (the
context-free, <=4-box ones). The remaining ~5.2%, plus every
neighbor-dependent shape (fences, glass panes, walls, redstone dust, and
others not captured by the context-free audit in
`collision-path-analysis.md`'s "Block-shape registry audit" section),
still need vanilla's real shape computation to stay parity-exact. So the
real question is not "how does Kestrel store block shapes", it's: **does
`core/` get its own copy of that computation, or does it call vanilla's
real code for it?**

Two options:

1. **Reimplement.** Precompute a static per-blockstate shape table (data
   dump, no vanilla code at runtime) and hand-write Kestrel's own
   neighbor-aware shape logic for the context-sensitive blocks (fences,
   walls, redstone dust, etc.).
2. **Embed.** Compile `core/` directly against the real deobfuscated
   vanilla 26.2 server classes (`BlockState`, `Block`, `Shapes`,
   `VoxelShape`, ...) and call them directly, same as `javap`/Vineflower
   already confirmed is possible with zero mapping toolchain.

## Decision: embed (option 2)

This is not really a new decision, it is what the project's own README
architecture section already commits to ("vanilla logic embedded as the
mechanics source of truth for mechanics"). Reimplementing shape
computation by hand for every context-sensitive block would be exactly
the kind of "should be equivalent" guesswork the parity contract rejects,
and it is strictly more total work than calling the real code. Concretely:

- `core/build.gradle` adds a `compileOnly` + `runtimeOnly` dependency on
  the real, locally-extracted vanilla `server-26.2.jar` classes, resolved
  from a Gradle property (e.g. `-PvanillaJar=/path/to/server-26.2.jar`),
  never committed (matches the existing "large jars stay local-only,
  gitignored" rule). Anyone building `core/` needs a real vanilla server
  jar on disk first, same as `harness/agent` already assumes at runtime.
- Kestrel does **not** re-implement `Block.BLOCK_STATE_REGISTRY` or
  `Shapes`/`VoxelShape`. It reuses vanilla's real classes directly for
  anything shape-related, at both the fast-path and fallback layer:
  - **Fast path** (94.8% of states): still calls `BlockState.getShape(...)`
    exactly once per distinct blockstate to build a cached box list (this
    is what `BlockShapeAudit` already does today, just moved from a
    one-shot analysis tool into part of Kestrel's own runtime cache), then
    does direct AABB-vs-AABB math against that cached list. No vanilla
    `Shapes.joinIsNotEmpty` call on the hot path for these states.
  - **Fallback path** (context-sensitive + >5-box states): calls
    vanilla's real `BlockState.getCollisionShape(BlockGetter, BlockPos)`
    directly, unmodified, every time. Slower, but correct by
    construction (it *is* vanilla's code), and rare (bounded by the
    audit's own numbers).
- The only genuinely new thing Kestrel has to build is a `BlockGetter`
  implementation (vanilla's own interface: "give me the BlockState at
  this position") backed by Kestrel's own chunk storage, so the fallback
  path's neighbor lookups (`level.getBlockState(pos.relative(...))`) work
  against real data instead of nothing. `EmptyBlockGetter` (used in
  `BlockShapeAudit`'s one-shot audit) is not sufficient for this; that
  audit deliberately only measured context-free shapes.

## Minimal chunk/block storage to prove optimization #1 (not full storage)

The "storage" subsystem bucket (README architecture section) is real,
separate, future work (region files, persistence, multi-dimension). None
of that is needed to *prove* optimization #1 works. The minimal viable
slice:

- A single in-memory `BlockGetter` backed by a flat 3D array (or a single
  16x16xN chunk section array, vanilla's own layout), bounded to the
  volume `harness/corpus/collision-stress` actually uses (a walled field,
  not an open world). No persistence, no multi-chunk paging, no NBT
  reader yet.
- Populated by a small Kestrel-side "test fixture" that hardcodes the same
  block layout `collision-stress`'s `init.mcfunction` builds (same
  stairs/slabs/fences/walls/snow-layer stripes, same hopper pocket, same
  cauldron), so the very first proof run doesn't need to parse a real
  chunk file at all. Chunk-file I/O (NBT parsing, region file format) is
  deliberately deferred; it is not on optimization #1's critical path,
  it belongs to the "storage" bucket whenever that starts.
- An entity representation minimal enough to build a bounding box and feed
  it through `Entity.move()`'s ported logic: does not need full entity
  behavior (AI, networking, persistence), just position + velocity + AABB,
  enough to replay the same 40-zombie movement scenario deterministically
  (no live AI needed for the proof; a scripted movement path is fine and
  sidesteps the already-documented non-seeded-AI-RNG issue entirely).

This scope is intentionally the smallest slice that can produce a real
apples-to-apples comparison against the harness's `collision-stress`
capture (now a trusted oracle once ticks 0-5 are skipped, see
`collision-path-analysis.md`), without pulling in unrelated future work
(chunk persistence, networking, multi-dimension) prematurely.

## What comes after this doc

1. ~~`core/build.gradle`: add the `vanillaJar` Gradle property + dependency
   wiring~~ DONE (2026-09-10), verified building both with and without a
   real local vanilla jar path on bench PC. Commit `ed26ab6`.
2. ~~Minimal `BlockGetter` implementation + the hardcoded
   `collision-stress`-matching test fixture~~ DONE (2026-09-10):
   `KestrelBlockGetter` + `CollisionStressFixture` in
   `core/src/vanilla/java/dev/kestrel/block/`. See "Runtime verification"
   below for what running it against real vanilla actually found.
3. Port the fast-path box-list cache (reusing `BlockShapeAudit`'s
   reflection-derived logic, now as real runtime code, not a one-shot
   tool) plus the fallback call into real vanilla `getCollisionShape`.
4. Port `Entity.move()`/`collide()`'s exact algorithm (axis order,
   step-up second pass) per the full read-through in
   `collision-path-analysis.md`.
5. Only then: differential comparison against the harness's
   `collision-stress` capture, skipping ticks 0-5.

## Runtime verification (2026-09-10): what actually happened, not just compiled

`FixtureRuntimeCheck.java` builds the fixture and calls real vanilla
`BlockState.getCollisionShape(BlockGetter, BlockPos)` against it, outside
any running server. First run failed twice before passing, both times
with real, useful information (not guesses):

- **`IllegalArgumentException: Not bootstrapped`**: touching `Blocks`
  (and therefore `BuiltInRegistries`) without first calling
  `net.minecraft.server.Bootstrap.bootStrap()` (plus
  `SharedConstants.tryDetectVersion()` first) throws. Matches vanilla's
  own `Main.main()` startup sequence; any future Kestrel entry point that
  touches vanilla classes needs to call this once, first.
- **`does not exist in Block{minecraft:hopper}`**: `HopperBlock` declares
  its *own* `FACING` property (`DOWN` + the four horizontals, no `UP`,
  since a hopper can't point up), not the generic 6-direction
  `BlockStateProperties.FACING` used elsewhere. Several vanilla blocks do
  this (their own restricted-domain property with the same name);
  `BlockStateProperties.X` is a convenience default, not guaranteed to be
  what any given block actually uses. Check the block's own class first.
- Runtime classpath also needs every jar the bundler's own launcher
  extracts to a `libraries/` folder (fastutil, brigadier, joml, ...), not
  just the extracted `server-26.2.jar` alone - same "bundler" shape
  documented in `harness/README.md`, now confirmed to matter for a
  standalone `core/` process too, not just the harness agent.

After both fixes: **PASS**, 11 sample positions across every stripe in
the fixture (stairs, both slab orientations, fence, wall, snow layers,
hopper, cauldron, floor, perimeter wall, air), zero exceptions, real box
counts matching the earlier audit's numbers exactly (hopper 13 boxes,
cauldron 15 boxes).

**However**, this run also surfaced a real accuracy gap, found by reading
the actual printed BlockStates, not assumed: the fence and wall samples
came back with **no active connections**
(`oak_fence[east=false,north=false,south=false,west=false]`) even though
they were placed in a contiguous run where real vanilla's `/fill` would
connect them to their neighbors. **Root cause**: `CollisionStressFixture`
only calls `Block.defaultBlockState()` + `setValue(...)` for the
properties it explicitly sets; it never runs vanilla's real
neighbor-aware state resolution (`updateShape`, which real `/fill` and
block placement trigger automatically). So the *code path* is proven
(real vanilla shape computation genuinely runs against Kestrel's own
`BlockGetter`), but the *fixture's block states* are not yet a faithful
match for what `harness/corpus/collision-stress` actually contains for
any connection-dependent block (fences, walls, glass panes, iron bars,
and - later - redstone dust). Full-cube and non-connection-dependent
shapes (stairs, slabs, snow layers, hopper, cauldron) are unaffected;
those don't read neighbor state for their shape.

## Follow-up: neighbor-state resolution gap is FIXED (2026-09-10)

`NeighborStateResolver.java` (in `core/src/vanilla/java/dev/kestrel/block/`)
runs real vanilla `updateShape` iteratively over the fixture until a full
sweep changes nothing (5 sweeps for `collision-stress`), via a dynamic-proxy
`LevelReader` backed by the `KestrelBlockGetter` plus a no-op
`ScheduledTickAccess`. Verified live on bench PC: `FixtureRuntimeCheck` PASS,
11 samples, zero exceptions, fence mid-run `east/north/south/west=true`,
wall mid-run all `low`, hopper 13 boxes, cauldron 15 boxes.

Three real findings from that verification, kept here so nobody re-learns them:

- `Bootstrap.bootStrap()` binds NO tags (that happens during datapack/world
  loading). Every `state.is(BlockTags.FENCES)` check returns false until tags
  are bound, so same-type connections never form. Fix: bind the fixture's
  tags explicitly (`bindConnectionTags()`, FENCES / WOODEN_FENCES / WALLS).
- The block registry is frozen post-bootstrap: the public
  `MappedRegistry.bindTags` path throws `Registry is already frozen`. Fix:
  bind per-holder via `Holder.Reference.bindTags` (package-private,
  reflection) instead, which is what the frozen path does internally.
- `Registry.getValue(Identifier)` is unusable from fixture code: it touches
  the `Keyable` supertype (DFU lib), which is a runtime-only jar, not a
  compile-time one. Fix: resolve holders via `getValue(ResourceKey)` +
  `wrapAsHolder` (reflection, no new imports), and add the bundler's
  `libraries/` dir to the compile classpath via the new `-PvanillaLibs`
  property in `core/build.gradle` (needed for any `Registry`/`Block`-level
  symbol: `Keyable`, brigadier, fastutil, etc.).

## What comes after this doc (continued)

Steps 1-2 above are done AND the neighbor-state gap is closed. The immediate
next piece of real, scoped work is step 3: the fast-path box-list cache
(reusing `BlockShapeAudit`'s reflection-derived logic as real runtime code)
plus the fallback call into real vanilla `getCollisionShape`.
