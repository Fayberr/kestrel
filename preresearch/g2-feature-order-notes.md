# G2: vanilla FEATURES decoration order (step 7 remainder item, tier R/D1)

Follow-up to `preresearch/g2-step7-design.md` section 6: "Recorded vanilla
FEATURES order... column-major is best of five guesses on two chunks, not
evidence of vanilla's order." This note replaces the guess with (1) the
javap-confirmed mechanism that actually produces the order and (2) a live
26.2 recording that matches it. Notes only, no production code changes.

## 1. javap: what actually determines the order

`ChunkStatusTasks.generateFeatures` (javap `-c` against the 26.2 inner
server jar, `net/minecraft/world/level/chunk/status/ChunkStatusTasks`) is a
**single-chunk, synchronous leaf**: it takes one `ChunkAccess` (arg 3),
primes heightmaps, builds one `WorldGenRegion` centered on that chunk, calls
`ChunkGenerator.applyBiomeDecoration` once, then `Blender.generateBorderTicks`,
then returns `CompletableFuture.completedFuture(chunk)`. It does not loop
over neighbours and does not decide what order chunks are decorated in -
that confirms step 7 section 1 item 5 (the per-biome/per-step feature loop
is *inside* one chunk's decoration pass, orthogonal to *which chunk* runs
next).

The actual cross-chunk order comes from **`ChunkGenerationTask.scheduleLayer`**
(`net/minecraft/server/level/ChunkGenerationTask`, javap `-c`). Decompiled
control flow (locals renamed):

> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

(`getRadiusForLayer` -> `ChunkPyramid.{GENERATION,LOADING}_PYRAMID.getStepTo(targetStatus)
.getAccumulatedRadiusOf(status)`; for FEATURES this is the radius-1 margin
already described in step 7 section 2, "Radius-1 write view".)

This is a plain nested loop, **x outer ascending, z inner ascending**, over
one `ChunkGenerationTask`'s own dependency margin. `ChunkMap.pendingGenerationTasks`
is an insertion-ordered `List`, so the sequence `ChunkStatusTasks.generateFeatures`
actually runs in, for one task's margin, is exactly this loop's iteration
order - not a `ChunkPos.pack()`-sorted priority queue. (Checked and ruled
out: `ChunkPos.pack(x,z) = (x & 0xFFFFFFFF) | ((long) z << 32)`, i.e. z is
the packed value's high word: sorting ascending by `pack()` would give a
**z-outer** ("row-major") order, which step 7 already measured as *worse*
(98.7-99.3%) than x-outer. The live recording below confirms x-outer is the
real vanilla behaviour, not the pack-sorted one.)

Each newly-ticketed chunk position gets its **own** `ChunkGenerationTask`,
so a bulk load (many chunks ticketed at once, e.g. `forceload` over an area)
runs many of these x-outer/z-inner scans concurrently across worker threads,
interleaved - see section 3.

## 2. Live run: setup

- Server: `preresearch/vanilla-26.2/server.jar` (bundler), nested
  `server-26.2.jar`, run under `~/.local/toolchains/jdk-25.0.4.1+1` (Temurin
  25.0.4.1+1, matches the `JDK 25` requirement already documented in
  `vanilla-26.2-fetch.md`).
- World: fresh `level-seed=4242424242`, `level-type=minecraft:flat`,
  `generate-structures=false` - flat so generation is fast; the
  scheduling mechanism in section 1 is generator-agnostic (it lives in
  `ChunkGenerationTask`/`ChunkMap`, above `ChunkGenerator`).
- `--port 25577` (the default 25565 was already bound by another server;
  confirmed via `ss -ltnp` that it wasn't ours before picking a different
  port - did not touch that process).
- Instrumentation: a throwaway ASM javaagent (`agent.jar` +
  bootstrap-appended `boot.jar`, per the pattern in `harness/README.md`
  "Boot jar: 12 classes, not 2") that wraps
  `ChunkStatusTasks.generateFeatures` with an `AdviceAdapter`: on enter/exit
  it reads `chunkAccess.getPos().x()/.z()` and logs
  `phase,x,z,nanoTime,threadName`. Not part of the Kestrel build, not
  committed, lives only under `/tmp` on the Pi.
- Procedure: boot the server (stdin wired to a FIFO), let it settle
  ("Done" + spawn area prepared), then send
  `forceload add -32 -32 32 32` over the server console (block
  coordinates - `ForceLoadCommand` takes `ColumnPos`, converted to chunk
  coords via `SectionPos.blockToSectionCoord`, i.e. `>>4`; this maps to
  chunks `(-2,-2)` to `(2,2)`, confirmed by the server's own log line
  `Marked 25 chunks in minecraft:overworld from [-2, -2] to [2, 2] to be
  force loaded`), wait for the log to stop growing, `stop`.

## 3. Recorded order (25 chunks, chunks -2..2 in both axes)

`enter` timestamps (first arrival at `generateFeatures`), sorted, filtered to
the 5x5 window (a few chunks further out also got decorated as a side effect
of `forceload`'s ticket propagation - see caveat below - and are excluded
from this table):

| # | chunk (x,z) | +ms from #1 | worker thread |
|---|---|---|---|
| 1 | (-1,-1) | 0.000 | Worker-Main-3 |
| 2 | (-1,0) | 32.1 | Worker-Main-3 |
| 3 | (-1,1) | 42.2 | Worker-Main-3 |
| 4 | (0,-1) | 45.3 | Worker-Main-3 |
| 5 | (0,0) | 48.1 | Worker-Main-3 |
| 6 | (0,1) | 50.7 | Worker-Main-3 |
| 7 | (1,-1) | 53.3 | Worker-Main-3 |
| 8 | (1,0) | 55.8 | Worker-Main-3 |
| 9 | (1,1) | 58.3 | Worker-Main-3 |
| 10 | (-2,-2) | 12738.4 | Worker-Main-3 |
| 11 | (-2,-1) | 12739.3 | Worker-Main-3 |
| 12 | (-1,-2) | 12740.7 | Worker-Main-3 |
| 13 | (-2,0) | 12757.9 | Worker-Main-2 |
| 14 | (0,-2) | 12762.7 | Worker-Main-2 |
| 15 | (-2,1) | 12798.0 | Worker-Main-2 |
| 16 | (-2,2) | 12832.9 | Worker-Main-2 |
| 17 | (-1,2) | 12833.6 | Worker-Main-2 |
| 18 | (0,2) | 12848.2 | Worker-Main-1 |
| 19 | (1,-2) | 12868.0 | Worker-Main-1 |
| 20 | (1,2) | 12899.6 | Worker-Main-1 |
| 21 | (2,-2) | 12934.0 | Worker-Main-3 |
| 22 | (2,-1) | 12938.5 | Worker-Main-1 |
| 23 | (2,0) | 12942.0 | Worker-Main-2 |
| 24 | (2,1) | 12943.9 | Worker-Main-3 |
| 25 | (2,2) | 12964.8 | Worker-Main-2 |

(The ~12.7s gap between #9 and #10 is wall-clock time I spent polling the
server before typing the `forceload` command at the console - an artifact
of the manual test, not a generation-time signal.)

**Rows 1-9** are the chunks vanilla keeps loaded around world spawn by
default (before any `forceload`), all at chessboard distance <= 1 from
(0,0) - a single `ChunkGenerationTask` margin walk. Order:
`x=-1{z=-1,0,1}, x=0{z=-1,0,1}, x=1{z=-1,0,1}` - **exactly** the
x-outer-ascending/z-inner-ascending loop from section 1, and exactly
step 7's "column-major (x outer)" hypothesis. This is not a coincidence:
it is the same mechanism, now confirmed from bytecode instead of inferred
from a five-way score comparison.

**Rows 10-25** are the additional ring at chessboard distance 2, reached
only after the `forceload` command. Their order is *not* a single clean
x-outer/z-inner scan across the whole 5x5 - it's several interleaved
per-chunk `ChunkGenerationTask` margin walks running on three worker
threads at once (`Worker-Main-1/2/3`, visible in the table). `forceload`
tickets many positions at once, so many small x-outer/z-inner scans (one
per newly-ticketed `ChunkGenerationTask`) race each other; the merged
wall-clock order reflects thread scheduling as much as the algorithm.

## 4. Caveat: forceload's ticket reach is wider than the forced rectangle

`forceload add -32 -32 32 32` was meant to bound the probe to exactly the
5x5 window, and the server log agrees at the ticket-add level ("Marked 25
chunks... to be force loaded"). But `generateFeatures` entries kept
appearing well past that window - out to chessboard distance 5 (e.g.
`(-5,-5)`, `(5,5)`) before the log stopped growing. This is a real
property of vanilla's `FORCED` ticket level (it grants enough ticket level
for chunks several rings out to still reach FEATURES, not just the two
literal chunks in `pos +/- 0`), not a bug in the probe. It matters for
future bulk-pregeneration work but not for HeadlessNoise (section 5): a
single test/oracle chunk's decoration only ever needs its own radius-1
margin, never a bulk-loaded halo.

## 5. How `HeadlessNoise.features` should replay this

No code change needed - `HeadlessNoise.features`'s committed "3x3
column-major (x outer)" order (step 7 section 5, 99.68% on chunk (0,0)) is
already the order this note now confirms mechanistically:

- Iterate the write-margin as `for x in [center.x-1, center.x+1] ascending
  { for z in [center.z-1, center.z+1] ascending { decorate(x,z) } }` -
  matching `ChunkGenerationTask.scheduleLayer`'s own loop for a single
  target chunk's FEATURES-radius (1) margin.
- Do **not** switch to a `ChunkPos.pack()`-sorted ("z outer") order - that
  was checked and is the *worse*-scoring row-major variant from step 7.
- Do **not** try to replay the wider forceload halo order from section 3/4
  (rows 10-25) - that pattern is an artifact of concurrent multi-task
  scheduling across worker threads for a *bulk* ticket add, which
  HeadlessNoise never does (it only ever decorates one oracle/test chunk's
  own margin, i.e. always in the "rows 1-9" regime of this recording).
- Remaining step 7 open item ("cross-order P3 rate at N >= 1000... verified
  on two chunks") is unaffected by this note - still future work.

## 6. Artifacts (not committed)

- The agent/probe sources, jars and the raw log (schema: `phase,x,z,nanoTime,thread`) were temporary files and are not kept.
- Server working dir: a throwaway directory (port 25577, flat world, seed
  4242424242).

All of the above are scratch/tmp and gitignored by location; only this
notes file is committed.

## 7. Follow-up: vanilla is not order-independent either

Section 3 above recorded vanilla decorating a bulk-loaded area across three
`Worker-Main` threads, with the merged order set by thread scheduling. That
raises a question this note did not answer: if vanilla's own chunk order
varies run to run, does vanilla's *output* vary too?

It does. Measured in `g2-vanilla-order-determinism.md`: two procedurally
identical vanilla runs on one seed agree on 99.85% of cells and only
42.75% of chunks, and under a deliberately different request order that
falls to 99.67% / 14.50%. Kestrel's cross-order stability (99.6564%) is
vanilla's, to within 0.01 percentage points.

This retires the reading that a cross-order shortfall in Kestrel is by
itself evidence of a Kestrel defect, and it means a fixed oracle capture is
one sample from a distribution rather than ground truth.
