# Chunk-send air-slice fast path

Measured 2026-09-17 on `primary-vnic` against `345cd15`.

## What changed

`ChunkSender.sectionBytes` walked every section slot of a column, even the
absent ones. For an absent slot it allocated a `BlockState[4096]`, called
`SectionStore.readCell` 4096 times, then overwrote all 4096 entries with air
and handed the result to `Outbound.sectionBytes`, which derived a one-entry
palette from it. The output of that work is a constant.

`Outbound.airSectionBytes` now emits that constant directly (8 bytes:
two zero counts, bits 0, air's global id, biome bits 0, biome id), and the
sender takes it whenever `hasSection` is false. Present slots now reuse one
`scratchCells` buffer instead of allocating a fresh array per slot.

Absent slots are not a corner case. A 441-chunk overworld column set is
441 x 24 = 10,584 slots against 5,881 materialised sections, so 4,703 slots
(44.4%) took the discarded-work path.

## Correctness

- `AirParity` (ad hoc, not committed): `airSectionBytes` is byte-identical to
  `sectionBytes` over an all-air array for biome ids 0, 1, 7, 127, 128, 4242
  and for a null-filled array. 7 checked, 0 failures. The constant is
  `[0, 0, 0, 0, 0, 0, 0, 1]`.
- `NetCheck: PASS` on the patched build, including `checkSectionRoundTrip`.
- `scratchCells` is safe to share: both `sectionBytes` call sites
  (`streamBurst`, `streamAround`) are reached only from
  `NetServer.tick(ChunkSender)`, so they are serialised on the tick thread,
  and `Outbound.sectionBytes` reads the array into its own palette and data
  arrays without retaining it. All 4096 entries are overwritten per use.
- The cached constant is cloned on return, so a consumer that edits the
  buffer cannot poison later sends. `air` and `plainsBiomeId` are both final,
  so the cache cannot go stale.

## Measurement

Workload: seed 4242424242, `--generate=0,0,10 --no-save`, world fully
generated before the client connects, then one idle bot streams all 441
chunks at view distance 10. `-Xms128M -Xmx1G -XX:+UseSerialGC`, `-Xlog:gc`.
Four repeats, interleaved old/new so machine drift cancels.

| Build | Young GCs during stream | Peak RSS MiB |
|---|---|---|
| `345cd15` | 14, 14, 14, 14 | 561.2, 561.9, 557.8, 548.0 |
| patched | 8, 8, 8, 8 | 564.9, 556.2, 561.7, 550.3 |

Young collections during the stream drop from 14 to 8, a 43% reduction, with
zero variance across four repeats. The young generation is a fixed size under
these flags, so collection count is proportional to bytes allocated: the
stream allocates roughly 43% less.

Peak RSS is unchanged, and that is expected. Peak is set during world
generation, before the client connects; this change only touches the send
path. It lowers steady-state churn, not the generation high-water mark, and
under SerialGC RSS never falls back below that mark.

## Caveat on absolute numbers

Measured on `primary-vnic`, not the bench PC where the W1/W2 campaign ran.
Heap figures agree closely (176.4 MiB here vs 176.8 MiB there for the same
chunk count) but absolute RSS runs about 50 MiB higher on this box. The
old/new comparison is valid; the absolute RSS values should not be pasted
into the bench PC evidence tables.
