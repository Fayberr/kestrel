# G2 step-2 support: dense-section memory, measurement and compact design

Status: IMPLEMENTED, MEMORY GATE PASSED. Compact import, copy-on-write, live
FULL handoff, and compact snapshot/save landed in `ec03d79` and `dd399c8`.
The compact W1/W2 rerun measures W1 B=12.79 MiB and projects 40.5 MiB of block
storage for five disjoint VD10 players, down from 358.2 MiB dense. Step 2b is
closed. Whole-server W1 load RSS remains 495.05 MiB against the 350 MiB target,
so step 9 memory work remains open. See
`harness/tools/parity/mem-compact-w1w2/notes.md`.

Inputs:

- `core/src/vanilla/java/dev/kestrel/loop/SectionStore.java` (dense store).
- `core/src/vanilla/java/dev/kestrel/persist/ChunkCodec.java` and
  `SectionCodec.java` (the paletted codec already in the repo).
- `persist/WorldStore.java`, `persist/WorldSnapshot.java`,
  `net/ChunkSender.java`, `net/NetServer.java` (who allocates what around
  the store).
- `harness/tools/run-bench.py` + `harness/bench.yaml` (how RSS is sampled).
- **javap-26.2**: `javap -p -c` on the inner jar of
  `preresearch/vanilla-26.2/server.jar`
  (`META-INF/versions/26.2/server-26.2.jar`). Cited as class + bytecode
  offset. Used for §0.2, §0.3 and the class member lists in §2-3; everything else from vanilla is
  still unverified.

Size model (the same one rev 2 used): 64-bit HotSpot, compressed oops and
compressed class pointers, 12-byte object header, 16-byte array header,
8-byte alignment. **KB/MB = 1000-based** in arithmetic; the bench reports
`WorkingSet64 / 1MB` = **MiB** (`run-bench.py:106, 134`). The budget line is
converted once and used in MiB from then on.

## 0. Findings that change the Q5 estimate before anything is measured

### 0.1 The per-section cost, re-derived

`SectionStore.Section` (`SectionStore.java:57-61`) plus its map entry
(`SectionStore.java:63`):

| Part | Layout | Bytes |
|---|---|---|
| `BlockState[4096]` (`:58`) | 16 + 4096 x 4 | 16,400 |
| `boolean[4096]` (`:59`) | 16 + 4096 x 1 | 4,112 |
| `Section` object (`:57-60`) | 12 header + 2 refs x 4 + int 4 = 24 | 24 |
| boxed `Long` key (`:63`) | 12 + 8, aligned | 24 |
| `HashMap.Node` | 12 + int hash + 3 refs = 28, aligned | 32 |
| `HashMap` table slot | 4-byte ref at load <= 0.75, power-of-2 table | ~6 |
| **Total** | | **20,598 (~20.6 KB)** |

This matches rev 2's 20.6 KB. The `BlockState` objects themselves are the
vanilla registry singletons and cost nothing per section.

### 0.2 Vanilla region files contain every section, including all-air ones (CONFIRMED)

javap-26.2, `SerializableChunkData.copyOf(ServerLevel, ChunkAccess)`,
offsets 52-249: the loop runs `getMinLightSection() .. getMaxLightSection()`.
For each Y it sets `inRange = 0 <= getSectionIndexFromSectionY(y) <
sections.length` (offsets 69-95). It then adds a `SectionData` whenever
`inRange || blockLight != null || skyLight != null` (offsets 187-240), and
copies the `LevelChunkSection` whenever `inRange` (offsets 205-215).
`hasOnlyAir()` is never consulted. So a FULL overworld chunk on disk carries
**all 24 in-range section tags**, the air-only ones as a one-entry
`[minecraft:air]` palette, plus light-only tags just outside the range.

Consequences for the current decode path:

- `SectionCodec.decode` never returns null cells (`SectionCodec.java:121-148`,
  "never null" at `:108`).
- `WorldSnapshot.restoreInto` skips a cell only when it is `null` AND
  unwritten (`WorldSnapshot.java:237-241`), so every decoded cell goes
  through `writeCell` and all 24 sections materialize
  (`SectionStore.java:143`).
- Rev 2's "sparse import" row (~100 MB) is **not what the existing code
  would do**. The existing code gives the "air written" row: 24 x 20,598 =
  **494 KB per chunk**, **~218 MB** at view distance 10.

### 0.3 ChunkCodec reads section `Y` as a slot, vanilla writes a world section Y (CONFIRMED, parity bug)

- javap-26.2, `SerializableChunkData.write()` offsets 305-314: `putByte("Y",
  sectionData.y)`. `y` is the light-loop variable from §0.2, which is a world
  section Y (-4..19 for the overworld, plus -5 and 20 for light-only tags).
- javap-26.2, `SerializableChunkData.parse` offsets 514-542: `getByteOr("Y",
  0)` is compared against `getMinSectionY()` and `getMaxSectionY()`, so it is
  a world section Y on read too.
- `ChunkCodec.decodeChunk` keeps only `0 <= Y < 24` as a chunk-local slot
  (`ChunkCodec.java:233-236`) and then adds `minSectionY` again
  (`ChunkCodec.java:303-305`). The Kestrel writer is self-consistent
  (`ChunkCodec.java:156` writes the slot), so round-trip tests pass.

On a real vanilla file, world Y -4..-1 (blocks y -64..-1) is dropped, and
Y 0..19 lands 4 sections too low. Step 2 exit 1 (HD hash == P3) would catch
this, but it also changes the census: only 20 of 24 sections would
materialize. This is not a memory issue, but step 2 has to fix it before any
number from the import path means anything. It is recorded here because
every measurement below assumes the fix.

### 0.4 Other things on the import path that distort a memory reading

These are not the store itself, but each one would land in RSS during the
step-2 run:

1. **Load peak is roughly 2x the store.** `WorldStore.load` keeps every
   decoded `BlockState[4096]` in the `decoded` list (`WorldStore.java:161`).
   It wraps them into `SectionData` (`:170-181`) and restores into the store
   while the list is still reachable (`:198-199`). Peak live data is the
   store (20.6 KB/section) plus decoded cells (16.4 KB/section). The JVM
   commits heap for that peak, and with `-Xmx2G` (`bench.yaml:28`) it has no
   reason to give it back, so `rssIdle` shows the load peak, not the steady
   state.
2. **Autosave doubles it again, for a moment.** `WorldSnapshot.capture` copies
   a fresh `BlockState[4096]` + `boolean[4096]` per live section
   (`WorldSnapshot.java:83-91`), called from `AsyncSaver.java:64` and
   `WorldStore.java:69`.
3. **Streaming garbage.** `ChunkSender.sectionBytes` allocates a
   `BlockState[4096]` per section per send (`ChunkSender.java:211`), and
   `Outbound.sectionBytes` an `int[4096]` plus a `LinkedHashMap`
   (`Outbound.java:576-578`). That is 24 x (16,400 + 16,400) ~ 787 KB per
   chunk sent. At `CHUNKS_PER_TICK = 4` (`ChunkSender.java:44`) it is
   ~3.1 MB/tick, ~63 MB/s at 20 TPS, for the ~111 ticks (441 / 4) it takes to
   stream view distance 10. This sizes the young generation, so it shows up
   in `rssLoad`.
4. **`writtenPositions` walks explode.** `SectionStore.writtenPositions`
   builds one `BlockPos` per written cell (`SectionStore.java:187-205`). With
   dense import that is 441 x 24 x 4096 = 43.4 M objects x 24 B ~ 1.04 GB,
   more than `-Xmx1G` (`bench.yaml:30`). `NetServer.findSpawn` ->
   `topWritten` -> `topY` calls it inside a loop over matches
   (`NetServer.java:1542-1575`), so it is quadratic as well. It runs on every
   join (`NetServer.java:1582-1583`). `TickLoop.discoverWires` walks it too
   (`TickLoop.java:1000`). Step 2 has to keep imported worlds off these
   paths (fixed spawn), or the run OOMs or hangs before any sample.
5. **Sidecar requirement.** `WorldStore.load` rejects a region without a
   Kestrel sidecar (`WorldStore.java:133-136`) and a section without sidecar
   written-bits (`:175-179`). Vanilla corpus files have neither, so step 2
   needs a sidecar-less import path anyway. §2 reuses that seam.

## 1. Measurement protocol for step 2

### 1.1 What is being decided

**B** = live heap bytes owned by block storage (the `SectionStore` arrays,
`Section` objects and map entries) at steady state, with a single player at
view distance 10.

**Decision rule:** budget = 350 MiB (`README.md:5`, taken in bench units).
If **B > 350 / 3 = 116.7 MiB (122.4 MB)**, a compact section format becomes
its own step (**2b**) between step 2 and step 3.

The rule is on B, not on an RSS delta, because of §0.4 items 1-3. RSS is
recorded as a cross-check and for step 9, not used to decide.

Translated into sections: 122,368,000 B / 20,598 B = **5,941 dense
sections**. At 441 chunks that is **13.5 live sections per chunk**. So the
rule fires exactly when more than ~13-14 of the 24 sections stay
materialized. That makes the air-section share the number that decides it.

Predictions (HYPOTHESIS, derived from §0.1):

| Import mode | Sections/chunk | B at 441 chunks | Rule |
|---|---|---|---|
| Current code after the §0.3 fix (all 24 dense) | 24 | 218.0 MB = 207.9 MiB | fires |
| Skip air-only palettes at import | ~11 (surface y 64-100) | 99.9 MB = 95.3 MiB | does not fire, 18% margin |
| Skip air-only + `long[64]` written bits (§2.5) | ~11 | 441 x 11 x 17,014 = 82.5 MB = 78.7 MiB | does not fire |

The middle rows sit close enough to the line that terrain height matters.
Mountain biomes push the surface to Y ~ 200+ (section index ~16) and so push
it over. That is why the census (1.4) is required, not optional.

### 1.2 Scenario

- **Server:** kestrel only for the decision. Add a vanilla row on the same
  corpus world for reference (vanilla keeps paletted `LevelChunkSection`s,
  javap-26.2 `LevelChunkSection` fields `states: PalettedContainer`,
  `biomes: PalettedContainerRO`).
- **Bench shape:** same as the existing `single` scenario
  (`bench.yaml:84-90`: 1 bot, `behavior: idle`). Changes: `stay_s: 120`,
  `sampling: quality` (4 RSS samples, `bench.yaml:62-67`), `repeats: 3`,
  median reported (`run-bench.py:190-194` already takes medians).
- **Flag set:** `tuned` (`-Xms128M -Xmx1G -XX:+UseSerialGC`,
  `bench.yaml:29-30`) is the gating one, because SerialGC committed heap
  follows the live set more closely and it is the low-memory target. Run
  `default` once for reference. Add `-XX:NativeMemoryTracking=summary` to
  both (jcmd output in 1.3).
- **View distance:** 10. Bots send no `ClientInformation`, so
  `pendingViewDistance` stays at its default 10 (`NetServer.java:366`,
  `KestrelPlayer.java:54`) and `ChunkSender` uses it as the radius
  (`ChunkSender.java:104`). For vanilla, `view-distance=10` in
  `server.properties`. Log the effective radius once per run to prove it.
- **Spawn:** fixed at the center of the imported square, never via
  `findSpawn` (§0.4 item 4).
- **Autosave:** off for the run, or its timestamps logged and any sample
  window that overlaps a save thrown away (§0.4 item 2).

### 1.3 World variants (why three)

`WorldStore.load` is eager: it loads every region file at boot
(`WorldStore.java:132-164`), and `SectionStore` never evicts (there is no
remove besides `clearAll`, `SectionStore.java:163-165`). So B depends on
**how many chunks were imported**, not on what the player sees. The corpus
has N >= 1000 chunks (step 1 exit), which is more than 441. So:

| Variant | Content | Purpose |
|---|---|---|
| W0 | the existing fresh flat world (17 x 17 stone, `KestrelServer.java:75-81`) | baseline, fixed JVM + server overhead |
| W1 | corpus 21 x 21 = 441 chunks centered on spawn | **the gated number** |
| W2 | corpus 31 x 31 = 961 chunks, same center | slope: bytes per chunk, checks linearity |

From W1 and W2: `perChunk = (B2 - B1) / 520` and `fixed = B1 - 441 x
perChunk`. If `fixed` is not ~0 (within 5% of B1), something besides
sections grows with imported chunks, and that has to be named before
deciding.

Also report, without gating: `perChunk x 2205` (5 players with disjoint
view-distance-10 squares). Dense at 24 sections would be 2205 x 494 KB =
1.09 GB, more than `-Xmx1G`. That is the scale at which the rule's
1-player framing understates the problem.

### 1.4 What to record

Per run, after the load window (so streaming in 0.4 item 3 has finished;
441 / 4 chunks per tick ~ 5.5 s, well inside `stay_s`):

1. **Bench RSS:** `rssIdle`, `rssLoad` as today (`run-bench.py:106-107,
   134-135`), MiB, 4 samples each.
2. **Live heap histogram:** `jcmd <pid> GC.class_histogram`. It runs a full
   GC first, so it reads the live set. Rows to keep, count and bytes:
   `[Lnet.minecraft.world.level.block.state.BlockState;`, `[Z`,
   `dev.kestrel.loop.SectionStore$Section`, `java.util.HashMap$Node`,
   `java.lang.Long`. B = the `Section` count x 20,598 B checked against the
   array rows: `[L...BlockState;` bytes / 16,400 and `[Z` bytes / 4,112
   should both come out equal to the `Section` count, give or take the few
   non-store arrays in W0. If they differ by > 5%, §0.1 is wrong: re-ground
   before deciding.
3. **Heap vs native:** `jcmd <pid> VM.native_memory summary` (Java heap
   committed, class, thread, code, GC, other) and `jcmd <pid> GC.heap_info`
   (committed vs used). This splits RSS into heap headroom and everything
   outside the heap, so step 9 can budget the non-block part.
4. **Section census,** logged by the import path at boot, one line per run:
   - chunks imported, section tags read, sections materialized;
   - **air-only share:** sections whose palette is exactly `[air]`;
   - **single-value non-air** sections (palette size 1, not air);
   - palette-size histogram in the codec's own buckets
     (`SectionCodec.bitsFor`, `SectionCodec.java:72-92`): 1, 2-16, 17-32,
     33-64, 65-128, 129-256, >256;
   - non-air cell count total.

   The palette size is known for free inside `SectionCodec.decode`
   (`SectionCodec.java:111-117`). The same census computed offline straight
   from the corpus region files, counting the same tags, is the
   cross-check. The two have to agree exactly, which also re-proves the §0.3
   fix.
5. **Load-peak marker:** `GC.heap_info` once right after the "loaded world"
   line (`KestrelServer.java:72-73`). This shows the §0.4 item 1 peak, which
   step 2b's read path (§2.2) should remove.

If `jcmd` is not usable against the bench PC JVM (the bench matches process
names `kjava|java`, `run-bench.py:72`, so the runtime may be a trimmed image
without the tool; HYPOTHESIS), fall back to `-Xlog:gc*:file=...` and a
server log line after `System.gc()` with `MemoryMXBean` heap used. B then
comes from census x 20,598 B, and the fallback is noted in the results.

### 1.5 Outcome table (what each result means)

| Census says | B at W1 | Decision |
|---|---|---|
| ~24 sections/chunk materialized | ~208 MiB | Step 2b compact, as specified in §2 |
| <= 13 sections/chunk (air-only skipped) and `perChunk x 2205` <= 350 MiB | < 117 MiB | No 2b now. Do §2.5's cheap wins inside step 2. Re-check at step 9 |
| <= 13/chunk but `perChunk x 2205` > 350 MiB | < 117 MiB | Rule does not fire. Record it as a step-9 risk and schedule 2b before step 9 exit 3 |
| histogram disagrees with census x 20,598 | any | stop, re-ground §0.1 |

**MEASURED 2026-09-16** (real 49x49 corpus, seed 4242424242, W1 VD10 + W2 VD15, 3 repeats
each): W1 11.35 sections/chunk (B=115.64 MiB, 0.991x the 116.7 MiB line), W2 10.6
sections/chunk (B=200.11 MiB). `perChunk x 2205 = 358.2 MiB`, over the 350 MiB budget on
its own. **Row 2 fires**: record as step-9 risk, schedule 2b before step 9 exit 3. Full
evidence: `harness/tools/parity/mem-w1w2/mem-w1w2-notes.md`.

## 2. The compact alternative for imported chunks

### 2.1 Shape

One more section kind next to the dense one, behind the same `SectionStore`
API (`get`, `set`, `readCell`, `writeCell`, `writtenBitset`,
`SectionStore.java:85-157`):

- **COMPACT** (immutable): `BlockState[] palette` + `long[] data` + `byte
  bits` + cached `short nonAir`, `short fluid` (the same counts vanilla
  caches, javap-26.2 `LevelChunkSection.nonEmptyBlockCount`, `fluidCount`).
  The layout is exactly what is on disk: `SimpleBitStorage` packing,
  `64 / bits` values per long, index `(y<<8)|(z<<4)|x`, no straddling
  (`SectionCodec.java:14-26, 94-106`). `data` is the `TLongArray` taken from
  the NBT as-is, not re-packed.
- **DENSE**: today's `Section` (`SectionStore.java:57-61`), unchanged.
- **Absent**: no entry. It reads as air (`SectionStore.java:86-88`).

In-memory sizes, same model as §0.1 (object 12 header + 2 refs + byte +
2 shorts ~ 24 B; add ~86 B of map entry to each):

| Section content | bits | data `long[]` | palette | Total incl. map |
|---|---|---|---|---|
| air only | - | absent, 0 | - | **0** |
| single value, non-air | 0 | none | `[1]` = 24 | 24 + 24 + 86 = **134** |
| 2-16 states | 4 | 256 longs = 2,064 | <= 80 | **2,254** |
| 17-32 | 5 | 342 longs = 2,752 | <= 144 | **3,006** |
| 33-64 | 6 | 410 longs = 3,296 | <= 272 | **3,678** |
| 65-128 | 7 | 456 longs = 3,664 | <= 528 | **4,302** |
| 129-256 | 8 | 512 longs = 4,112 | <= 1,040 | **5,262** |
| > 256 (disc uses ceil(log2 n), `SectionCodec.java:91`) | 9-12 | 12 bits: 5 per long, 820 longs = 6,576 | <= 16 + 4 x 4096 = 16,400 | **<= 23,086 (n = 4096, worse than dense's array alone; census bucket expected ~0)** |

Per chunk (HYPOTHESIS mix: 13 air-only, 9 at 4 bits, 2 surface sections at
5 bits for grass/flower/leaf variants): 9 x 2,254 + 2 x 3,006 =
**26,298 B ~ 26.3 KB**, against **494 KB** dense. At 441 chunks:
**11.6 MB = 11.1 MiB**. Pessimistic bound, all 24 sections at 8 bits:
24 x 5,262 = 126 KB/chunk, 55.7 MB = 53.1 MiB at 441, still under the
line and still ~3.9x smaller than dense. The census (1.4 item 4) turns this
table into a measured number with no second run: sum over buckets.

### 2.2 Read path (import)

1. Region file -> chunk compound (unchanged).
2. Per section tag, with the world-Y fix from §0.3: decode the palette
   through `StateNbt.decode` exactly as today (`SectionCodec.java:111-117`).
   Do **not** fill a `BlockState[4096]` (`SectionCodec.java:121-148` is the
   part that gets skipped).
3. Palette `[air]` -> no entry. Palette size 1, not air -> COMPACT with
   `bits = 0`. Otherwise COMPACT holding the palette and the adopted
   `data`. Validate like today: data length >= `ceil(4096 / (64 / bits))`
   (`:140-142`), every id < palette size (`:143-146`). The id check is one
   pass over 4096 values with no allocation.
4. Count `nonAir` / `fluid` in the same pass.
5. `get(pos)` / `readCell`: `bits == 0 ? palette[0] :
   palette[(int) ((data[i / per] >>> ((i % per) * bits)) & mask)]`. That is
   what vanilla does (`SimpleBitStorage` read, cited in
   `SectionCodec.java:20-22`; vanilla replaces the division with a magic
   multiply, which is a speed detail).

This also removes the §0.4 item 1 load peak: nothing 16.4 KB/section is
ever allocated for a COMPACT section.

### 2.3 Write path

**Live writes (`set`, `writeCell`): copy-on-write promotion.** The first
write to a COMPACT section decodes it into a new DENSE `Section`, sets
written bits (2.4), replaces the map value, then applies the write through
today's code (`SectionStore.java:98-109, 142-157`). After that it is a
normal dense section. There is no in-place palette resize at all.

Why promote instead of writing into the palette: players touch a tiny share
of loaded sections. The sections that do get written a lot (redstone,
farms) are exactly the ones where dense `cells[i]` is the right layout: the
redstone scratch index is the same (`SectionStore.java:28-30`). In-place
paletted writes are vanilla's `PalettedContainer.onResize` /
`reencodeContents` path (javap-26.2 member list) and would cost ~3 complexity
points for a memory gain only on already-hot sections.

**Save (`WorldSnapshot.capture` -> `ChunkCodec.encodeChunk`).**
- COMPACT is immutable, so `capture` keeps a reference instead of copying
  4096 cells (today `WorldSnapshot.java:84-91`). That removes §0.4 item 2
  for every cold section.
- `encodeChunk` emits `palette` + `data` straight from a COMPACT section
  instead of `SectionCodec.encode`'s 4096-entry scan
  (`ChunkCodec.java:146-151`, `SectionCodec.java:47-69`). The output is
  byte-identical to what was read, so a vanilla -> Kestrel -> disk round
  trip preserves the raw longs, not just the decoded states.
- DENSE sections keep today's path.
- The chunk writer currently skips absent sections (`ChunkCodec.java:138-145`).
  After §0.2, a vanilla-read-back file from Kestrel would lack air-only
  tags. Vanilla's parse tolerates that (the range guard at javap-26.2
  `parse` offsets 523-542 only filters tags that exist), so this is not a
  blocker. Note it for byte-diff tooling.

**Sending (`ChunkSender.sectionBytes`).** Leave v1 on `readCell` over 4096
indices (`ChunkSender.java:210-227`): it works on any kind, and the wire
palette rules (`Outbound.java:589-611`, global ids, direct mode above
8 bits) differ from disc rules, so emitting the disc palette directly on
the wire is a separate change with its own wire-parity test. The
`BlockState[4096]` scratch there (`ChunkSender.java:211`) should become one
reused buffer, which is independent of this design.

### 2.4 The written bitset for imported sections

`written` is not a block property. It is Kestrel bookkeeping read by the
sidecar (`WorldStore.java:145-147`), the live hash
(`WorldSnapshot.java:177-182`), `writtenPositions` (§0.4 item 4) and the
tests (`TickLoopCheck.java:211, 474, 552`).

Decision: a COMPACT section stores **no** bitset. Its written set is
defined as "cell is not air". `isWritten` answers from the palette with no
allocation. `writtenBitset(key)` currently returns the live `boolean[]`
(`SectionStore.java:132-135`) and is used by `ChunkSender.java:212` and
`WorldSnapshot.java:86`. For COMPACT it returns null plus a new "kind"
query, and those two callers branch on the kind. Promotion (2.3)
materializes the bits as "not air" at that moment.

Why "not air" and not "all 4096 written": all-written makes `topWritten`
pick air at y=319 as the spawn column top (`NetServer.java:1559-1567`), and
it makes the `writtenPositions` walk cover every cell. "Not air" matches the
store's existing contract that unset reads as air
(`SectionStore.java:22-25`).

### 2.5 What stays dense, and cheap wins that apply either way

Stays dense: every section written since load (promoted), the fresh flat
world (`KestrelServer.java:75-81`), and redstone propagation scratch
(`WireSectionLayout`). Scratch buffers in the send and encode paths are
dense but transient.

Cheap wins that do not need the compact kind (complexity **2**, fine inside
step 2 when §1.5 says no 2b):

- Skip air-only palettes at import (§0.2): 24 -> ~11 sections/chunk.
- `boolean[4096]` -> `long[64]`: 4,112 -> 528 B, saving 3,584 B/section
  (17.4% of 20,598). Only `Section` touches the array directly, but
  `writtenBitset` exposes it (`SectionStore.java:132-135`), so both callers
  above change.

### 2.6 Complexity and parity risk

Complexity (1-10):

| Variant | Score | Why |
|---|---|---|
| §2.5 cheap wins only | **2** | two local changes, callers of `writtenBitset` |
| **Step 2b: COMPACT import + COW promotion (§2.1-2.4)** | **4** | new kind behind an existing API; read path is disc layout; no resize logic; save path simplifies |
| In-place paletted writes (no promotion) | 7 | resize, palette growth/shrink, re-pack, bits thresholds, order-dependent palette ids, hot-path cost for redstone |

Parity risks for step 2b, ordered by likelihood:

1. **Section Y (§0.3).** Pre-existing and independent of format, but
   COMPACT would carry it forward silently. Gate: census tag counts ==
   offline counts, HD hash == P3.
2. **`written` semantics move for imported cells** (2.4). `hashLive` mixes
   the written flag (`WorldSnapshot.java:177-182`), so the live hash of an
   imported world changes meaning. Step 2's HD gate must use a blocks-only
   hash, never `hashLive`. Nothing in vanilla has this bit, so there is no
   gameplay parity risk, only test-oracle risk.
3. **Promotion misses a write entry point.** Any path that writes
   `cells[i]` without going through `set` / `writeCell` would write into a
   COMPACT section's non-existent array. Today the array is private to
   `SectionStore` (`SectionStore.java:57-61`), so the risk is contained if
   it stays private. The one leak is `writtenBitset` returning the live
   array (`:128-135`, "callers must copy, never mutate").
4. **Disc bits above 256 states.** `SectionCodec.bitsFor` uses
   `ceil(log2(paletteSize))` (`SectionCodec.java:91`). If vanilla's
   serialization strategy picks differently for > 256 states, adopting
   `data` as-is would misread. HYPOTHESIS, not checked in javap. The
   census's >256 bucket will be ~0 on fresh terrain. Guard: if the data
   length does not match the computed bits, fall back to dense decode for
   that section, and count those fallbacks.
5. **`nonAir` / `fluid` counts.** They must equal what `ChunkSender` counts
   today (`ChunkSender.java:220-225`), or the client's
   `nonEmptyBlockCount` (vanilla's short at javap-26.2
   `LevelChunkSection`) differs. It is computed in the same pass with the
   same predicates, so the risk is low. LT exit 2 compares.
6. **Unknown states in palettes.** `StateNbt.decode` failure behaves the
   same as today for both kinds, so this is not a new risk.

No tick-order or RNG risk: COMPACT changes only how the same `BlockState`
references are stored, and reads return the same singletons.

## 3. Does compact also serve live generation (steps 4-9)?

**Decision: yes.** Use one resting format for every chunk that reached FULL
by any route, and choose DENSE by **write activity**, not by provenance.
"Import-compact + live-dense" is rejected.

Reasons:

1. **Generated output is already paletted.** Steps 4-6 run vanilla's own
   `fillFromNoise` / surface / carvers on a `ProtoChunk` (build-order
   step 4), whose sections are vanilla `LevelChunkSection`s over
   `PalettedContainer` (javap-26.2 field list). At FULL, Kestrel has to move
   those blocks into its store. `PalettedContainer.pack(Strategy)` returns
   `PackedData` (javap-26.2 member list), which is palette + packed
   storage, the same shape as a disc section. Handing that to a COMPACT
   section is one adoption. Going live-dense instead means inflating a
   container vanilla just built compactly into 16.4 KB of refs: the extra
   copy costs more work and more memory. (HYPOTHESIS until step 4 shows the
   `PackedData` layout matches `SimpleBitStorage` packing, which is a javap
   check.)
2. **Generation is where memory grows.** The imported corpus is a one-time
   set. On a real server, every chunk a player explores is generated.
   Live-dense puts the 494 KB/chunk cost on exactly the chunks that keep
   growing, so it fails the 5-player figure in §1.3 by the same factor that
   dense import does.
3. **Provenance is the wrong key.** An imported chunk and a generated chunk
   are the same thing at FULL: vanilla-produced blocks nobody has touched.
   Keying the format on where a chunk came from would make every reader and
   the save path branch on history forever. Keying it on "has this section
   been written since it was loaded" (COW promotion, §2.3) matches what
   dense is actually good for, and it needs no new rule for generated
   chunks.
4. **The in-flight generation working set is not Kestrel's to shrink.**
   While a chunk is below FULL, it lives in vanilla's `ProtoChunk`, with
   NoiseChunk caches kept alive through CARVERS (build-order step 4,
   trap 17) and neighbors held for the FEATURES radius. That memory is
   vanilla's layout, and changing it is a parity risk. It is budgeted
   separately in step 5 / step 9 (RSS exit 3), not by this design.
5. **Dense would be a regression vs vanilla, not a Kestrel saving.**
   Vanilla already keeps FULL chunks paletted (`LevelChunkSection.states`).
   Kestrel's memory advantage has to come from what it does not keep (no
   per-section biome containers beyond plains, no light arrays while
   full-bright, no `LevelChunk` object graph), not from a denser block
   layout.

What this adds to the plan: step 2b's COMPACT kind is also the FULL-handoff
target in step 9. Step 9 then needs one extra exit item: sections promoted
to DENSE / total sections after the tp-bot spiral. That number should stay
small. If it does not, look for a write path that touches every generated
section (for example heightmap or light priming through `set`).

## 4. Summary for the build order

- Step 2 must first fix the section-Y decode (§0.3) and keep imported worlds
  off `findSpawn` / `writtenPositions` (§0.4). Without those, it produces no
  valid memory number.
- Step 2 records B with the §1 protocol (W0/W1/W2, `tuned` flags, jcmd
  histogram + NMT + census).
- Expected (HYPOTHESIS): current decode keeps all 24 sections -> B ~ 208 MiB
  -> the rule fires -> **step 2b = COMPACT import + COW promotion,
  complexity 4**. Expected result: ~11 MiB at W1.
- If the census shows air-only sections are skipped and B < 117 MiB, do the
  §2.5 cheap wins inside step 2. 2b still lands before step 9, because §3
  makes COMPACT the FULL-handoff format for generated chunks too.
