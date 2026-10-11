# G2 step 8: headless STRUCTURES (real StructureManager, starts only)

Code: `core/src/vanilla/java/dev/kestrel/worldgen/HeadlessNoise.java`
(`Structural`, `loadStructural`, `structures`), probe `StructuresCheck.java`.
Everything below is javap-cited against the 26.2 inner server jar
(`ChunkGenerator.createStructures`/`createState`, `StructureManager`,
`ChunkStatusTasks.generateStructureStarts`, `Structure`/`Structure
.GenerationContext`, `ChunkGeneratorStructureState`).

Scope, matched to the task: vanilla `ChunkGenerator.createStructures`
(STRUCTURE_STARTS) into one bare chunk, with a real (non-stub)
`StructureManager`, still headless (no `ServerLevel`/`WorldGenRegion`).
References (§1.2), Beardifier wiring into NOISE, and the FEATURES
structure block (piece placement) are explicitly **not** built this step
(see §5); noise/surface/carve/features keep their step-7 signatures
unchanged, and step 7's own driver/stub (`load`, `structuresOff`) is
untouched.

## 1. Why STARTS needs no server level (trap 21, settled empirically)

`chunkgen-path-analysis.md` trap 21 already classified STRUCTURE_STARTS as
pure ("noise via RandomState, biome source... no writes"). javap confirms
it precisely, two levels down:

- `ChunkGenerator.createStructures(RegistryAccess, ChunkGeneratorStructureState,
  StructureManager, ChunkAccess, StructureTemplateManager, ResourceKey<Level>)`
  has no `ServerLevel`/`WorldGenLevel` parameter at all. Its body (javap
  `-c`) is one `possibleStructureSets().forEach(...)` over a lambda
  (`lambda$createStructures$0`) that only calls: `StructureSet.placement()`,
  `StructurePlacement.isStructureChunk(ChunkGeneratorStructureState, x, z)`
  (grid check, no level), `StructureManager.getStartForStructure(SectionPos,
  Structure, StructureAccess)` (skip if a valid start exists), the weighted
  selection loop (`WorldgenRandom(LegacyRandomSource(0L))` +
  `setLargeFeatureSeed`, draws on failed attempts per trap 5), and
  `tryGenerateStructure` -> `Structure.generate(Holder, ResourceKey<Level>,
  RegistryAccess, ChunkGenerator, BiomeSource, RandomState,
  StructureTemplateManager, seed, ChunkPos, refs, LevelHeightAccessor,
  Predicate<Holder<Biome>>)` -> `StructureManager.setStartForStructure`.
- `StructureManager.getStartForStructure`/`setStartForStructure`/the
  `fetchReferences` helper they're called through all delegate straight to
  the `StructureAccess` parameter (`StructureAccess.getStartForStructure`/
  `setStartForStructure`, i.e. the `ChunkAccess` itself). None of them read
  `StructureManager.level` or `.structureCheck` (javap `-c` on both
  methods: `aload_3`/`aload 4` then an `invokeinterface` on the
  `StructureAccess` arg, no `getfield` on `this.level` or
  `this.structureCheck`). Those two fields exist for
  `checkStructurePresence`/`startsForStructure(ChunkPos, Predicate)` (the
  `/locate`-style paths, and the Beardifier's neighbour-reference lookup in
  `structuresOff`'s comment), never for STARTS on one chunk.
- Height sampling inside `Structure.generate` goes through
  `NoiseBasedChunkGenerator.iterateNoiseColumn`, which takes `(RandomState,
  BeardifierOrMarker, Blender)`, not a region.

Net: `Structural.structures()` needs a real `ChunkGeneratorStructureState`,
`StructureManager`, and `StructureTemplateManager` (for jigsaw/template
pieces), but the `StructureManager`'s `level` and `StructureCheck` can be
the same throwaway stand-ins step 7's `structuresOff` already used - they
are wired to real values anyway (cheap, and correct if a future caller
does reach them), but nothing on today's path reads them. The only field
that matters is `WorldOptions.generateStructures()` (`StructureManager
.shouldGenerateStructures()` javap body: one `getfield` + one
`invokevirtual`), and even that is never read on the STARTS call path
itself (see §3).

## 2. What `loadStructural` builds differently from `load`

A second `WorldLoader.load` pass (`HeadlessNoise.Structural`, not
`Driver`): same boot path as step 3's `load` (`Bootstrap.bootStrap()`,
`ServerPacksSource`, `WorldPresets.createNormalWorldDimensions`,
`RandomState.create`), three differences:

1. `WorldOptions(seed, true, false)` (structures ON) instead of
   `(seed, false, false)`.
2. `ChunkGeneratorStructureState state = generator.createState(access
   .lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed)` (javap
   `ChunkGenerator.createState`: one call to `ChunkGeneratorStructureState
   .createForNormal(RandomState, seed, BiomeSource, HolderLookup<StructureSet>)`).
   `Structural` keeps it so every `structures()` call reuses the same
   world-wide placement state (concentric rings, per `chunkgen-path
   -analysis.md` §1.1, are computed once and memoized there).
3. The factory keeps the `manager` (`CloseableResourceManager`) parameter
   `load()` drops. `StructureTemplateManager`'s only ctor
   (`ResourceManager, LevelStorageAccess, DataFixer, HolderGetter<Block>`)
   needs real jigsaw/template NBT (`data/minecraft/structures/*.nbt`),
   which only exists on the real manager, not `structuresOff`'s
   `FallbackResourceManager(PackType.SERVER_DATA, "headless")` (that one
   has 0 entries, fine for a stub that must never see a real structure).
   `Structural implements AutoCloseable` over this manager: lazy template
   loads need it open for as long as `structures()` may still be called.

`Structural.driver()` carries a `Driver` built the same way `load()`
builds one (own `NoiseBasedChunkGenerator`, own `RandomState`), still
wired to the *step-7* `structuresOff` stub - that field is dead weight on
this path (kept only so a caller holding a `Structural` can still reach
`noise`/`surfaceVanilla`/`carveVanilla`/`features` on `driver()` without a
third loader). Nothing in `structures()` reads `driver.structures()`.

## 3. `HeadlessNoise.structures(Structural, chunkX, chunkZ)`

```
ProtoChunk proto = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory(access), null);
driver.generator().createStructures(access, structureState, structures, proto, templates, Level.OVERWORLD);
return proto;
```

A fresh bare `ProtoChunk` per call, independent of any `noise`/
`surfaceVanilla`/`carveVanilla` result for the same coordinates (STARTS
"Requires: nothing", `chunkgen-path-analysis.md` §1.1) - unlike every
other stage in this file, it never fills the chunk first. `createStructures`
writes only `ChunkAccess.structureStarts` (a `HashMap<Structure,
StructureStart>`); it never calls `setBlockState`.

`ChunkStatusTasks.generateStructureStarts` javap confirms *where* the
world-level `generate-structures=false` switch actually lives, settling
build-order Q12 ("does `generate-structures=false` skip FEATURES'
structure block, or STARTS, or both? not traced"): the gate is at the
**caller**, not inside `createStructures`. Bytecode offset 17-20 of
`generateStructureStarts` is exactly `WorldOptions.generateStructures()`
followed by `ifeq` past the `createStructures` call - `createStructures`
itself has no such check (only `SharedConstants.DEBUG_DISABLE_STRUCTURES`,
a compile-time-style debug flag, offset 0-6). Kestrel's `structures()`
calls `createStructures` directly and unconditionally, so it does not need
to replicate that gate; the *caller* deciding whether to invoke it at all
is the "ON" switch, exactly like step 7's `features()` never had to
special-case `shouldGenerateStructures()` inside itself either.

## 4. Measured (Pi, JDK 25, seed 4242424242, oracle `worldflat/r.0.0.mca`)

Method: reproduce the step-7 baseline (column-major 3x3 FEATURES,
structures off, `HeadlessNoise.load`) on each target chunk, snapshot all
98304 cells, then run `structures()`'s real `createStructures` call
directly on that *same filled chunk* using the second (structures-ON)
driver's generator/state/manager/templates, and diff. Mixing a chunk built
by one `RegistryAccess`/`PalettedContainerFactory` with a second driver's
`ChunkGenerator` is safe here specifically because §1 already showed the
STARTS call path never touches chunk-external identity (biomes, block
palette) - it only reads/writes the `ChunkAccess`'s own structure-start
map.

| Chunk (0,0) | same / 98304 |
|---|---|
| features baseline (step 7, structures off) | 97988 (99.68%) |
| + real `structures()`, center chunk (0 cells changed) | 97988 (99.68%) |

| Chunk (5,7) | same / 98304 |
|---|---|
| features baseline (step 7, structures off) | 98161 (99.85%) |
| + real `structures()`, center chunk (0 cells changed) | 98161 (99.85%) |

Delta is exactly **0 cells** on both chunks, matching §1/§3's prediction
from bytecode, now confirmed by running the real (data-driven, non-stub)
`StructureManager` end to end: real structure sets, real placement grids,
real `StructureTemplateManager` backed by the real resource manager, on
real vanilla registries. Neither target chunk is itself a placement-grid
hit for any structure set on this seed (`chunk.getAllStarts()` is empty
both times).

**17x17 neighbourhood scan** (the STRUCTURE_REFERENCES radius,
`chunkgen-path-analysis.md` §1.2; 288 chunks probed per target, bare
`structures()` calls, no fill needed): one `minecraft:mineshaft` start
reaches into each target chunk's block box (`BoundingBox.intersects`) , 
offset `(0,6)` from chunk (0,0), offset `(-2,0)` from chunk (5,7). Neither
is a coincidence given mineshafts' `RandomSpreadStructurePlacement` grid
(spacing 1, i.e. a candidate check every chunk); it does mean that once
the FEATURES structure block is wired in (not this step, see §5), these
exact two chunks are the first candidates to watch for a real block-level
delta, not a randomly chosen pair.

## 5. Remainder (out of scope this step, matches build-order step 8's
   fuller item list)

- **STRUCTURE_REFERENCES** (`ChunkGenerator.createReferences`, trap doc
  §1.2): not built. The 17x17 scan above only reuses bare `structures()`
  calls to find starts and test bounding-box overlap; it does not populate
  `chunk.structuresRefences`, which real `startsForStructure(ChunkPos,
  Predicate)` reads (`StructureManager.java:65-85`, the path `structuresOff`
  documents as needed for the Beardifier). A future step wiring Beardifier
  into NOISE needs this.
- **FEATURES structure block** (`ChunkGenerator.java:347-366`, traps 2, 3,
  11): not wired. `features()` still throws if
  `driver.structures().shouldGenerateStructures()` is true (step 7's
  guard, unchanged) - it is never called with `Structural`'s manager.
  §4's found mineshaft start is exactly the case this would exercise:
  wiring it in without also fixing the seed/index bookkeeping (trap 2:
  structure index shares `setFeatureSeed` with feature index; trap 3: the
  index counts every structure in the step, present or not) would produce
  a real, measurable regression risk rather than today's guaranteed-zero
  delta.
- **Beardifier** into `noise()`: `structuresOff`'s empty-stub Beardifier
  stays load-bearing for the existing NOISE/SURFACE/CARVERS path; a
  structures-on NOISE run would need `Beardifier.forStructuresInChunk`
  fed from real references (previous bullet), and only matters for
  terrain-adapting structures (`terrainAdaptation() != NONE`, e.g. ocean
  monuments) - mineshafts don't adapt terrain, so §4's finding would not
  change even if this were built.
- **3-launch trap-18 check** (`Beardifier` `HashMap`-of-`Structure`
  iteration order): moot until Beardifier is wired in.
- **Q9** (structure references surviving a reload, trap 4): needs
  references (first bullet) and a save/reload path neither exists yet.
- Only two chunks and one seed verified; `chunk.getAllStarts()` being
  empty on both is a fact about this seed's placement grids on these
  coordinates, not a general property of the STARTS stage.
