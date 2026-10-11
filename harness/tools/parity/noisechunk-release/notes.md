# Releasing the memoized NoiseChunk after CARVERS

Date: 2026-09-17. Change: `HeadlessNoise.releaseNoiseChunk`, called from
`LiveGenerator.ensureCarvers` right after the chunk is marked CARVERS.

## What was wrong

`LiveGenerator.grid` is a `HashMap<Long, ChunkAccess>` whose Javadoc says
"grows, never evicted". It has to keep the `ProtoChunk`, because a
neighbour's later FEATURES pass still writes into it (vanilla's own
order-dependent accumulation). But each `ProtoChunk` also memoizes the
`NoiseChunk` built during generation, and that memo pins an entire
density-function graph.

A `GC.class_histogram` after two forced full GCs on a 441-chunk (view
distance 10) world found **529 live `NoiseChunk` objects**. 529 is exactly
23x23, the footprint a 21x21 generated square touches with its radius-1
margin, so the count is one per chunk ever generated, with nothing ever
released. Per retained chunk:

| class | count |
| --- | --- |
| `DensityFunctions$Spline$Coordinate` | 119 |
| `DensityFunctions$MulOrAdd` | 65 |
| `FlatCache` / `CacheOnce` / `Cache2D` | 10 / 7 / 7 |
| `NoiseInterpolator` (owns the `double[]` slices) | 8 |

That is most of the 86,137 `double[]` on the heap (20.9 MiB, against 2.3 MiB
on an empty server), about 57 KiB per chunk.

Vanilla never pays this, because it discards the whole `ProtoChunk` after
FEATURES and the memo goes with it.

## Why it cannot change output

`ChunkAccess.noiseChunk` (a protected field, populated by
`getOrCreateNoiseChunk`) is read only by the noise, surface and carve
passes. The only two `getOrCreateNoiseChunk` call sites in the tree are
inside `carve` and `carveVanilla`. FEATURES, `settleLeaves` and
`writeStoreCompact` never ask for it. No block, biome or fluid state lives
on the `NoiseChunk`; it is sampling scratch.

Failure mode if that reasoning were wrong is loud, not silent: a later
`getOrCreateNoiseChunk` would re-run the caller's factory, and both
factories in `HeadlessNoise` throw a named `IllegalStateException`
("carve needs the fillFromNoise NoiseChunk; run noise() first") rather than
quietly building a second, differently seeded `NoiseChunk`.

The release is best effort. If the reflective field lookup fails the memo
stays in place, which costs memory and changes no behaviour.

## Parity evidence

`NoiseChunkParity` generates a square through the live path and prints a
blocks-only FNV hash over every chunk. Built HEAD into `/tmp/kv-old` and the
patched tree into `/tmp/kv-classes`, same JDK, same libraries:

| square | seed | build | sections | worldHash |
| --- | --- | --- | --- | --- |
| 5x5 (25 chunks) | 4242424242 | HEAD | 584 | `3eba5e0aee5aa877` |
| 5x5 (25 chunks) | 4242424242 | patched | 584 | `3eba5e0aee5aa877` |
| 11x11 (121 chunks) | 4242424242 | HEAD | 2018 | `1c72c6e6c831b24d` |
| 11x11 (121 chunks) | 4242424242 | patched | 2018 | `1c72c6e6c831b24d` |
| 11x11 (121 chunks) | 987654321 | HEAD | 1524 | `d4ad78a6803b2082` |
| 11x11 (121 chunks) | 987654321 | patched | 1524 | `d4ad78a6803b2082` |

Identical in every case, including the per-chunk hashes the tool prints for
the first five columns, and the compact/dense section split.

`NetCheck: PASS` and `[live-gen] PASS` on the gradle build. The latter is
the relevant one: it asserts that neighbour rewrites keep a player-mutated
section dense and preserve player blocks in generated air, which is exactly
the accumulation this change had to leave alone.

## Memory result

Settled RSS at vd10, `-Xmx256M` plus the G1 uncommit recipe, memory probe
disabled, two repeats:

| build | settled | peak |
| --- | --- | --- |
| HEAD | 460.8 / 456.3 | 459.3 / 454.7 |
| patched | 410.1 / 410.7 | 417.7 / 416.3 |

Mean reduction **48.2 MiB**, against roughly 30 MiB predicted from the
histogram. The extra comes from second-order effect: the patched run's RSS
series steps down at sample 8 (418 to 410), while HEAD stays flat at its
peak for the full 180 s. The retained scratch had been holding the live set
above G1's shrink band, so the uncommit flags could not return anything.
Removing genuinely dead objects is what let the collector act.

## Caveat

Measured on `primary-vnic`, where absolute RSS runs about 50 MiB higher than
Bench PC for the same build. Do not paste these absolutes into a bench PC evidence
table. The A/B comparison is same-host and valid.

## Not fixed here

The retained `ProtoChunk`s still hold their own `PalettedContainer`s, so
block data is stored twice, once compact in `SectionStore` and again in the
grid (the 39,819 `long[]` / 37.9 MiB histogram row). Removing that needs
grid eviction, which is designed but not implemented; see
`preresearch/g2-memory-350-feasibility.md` section 5 item 2, including why
the eviction radius has to be 2 and not 1.
