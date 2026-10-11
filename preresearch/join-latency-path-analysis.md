# Join-latency investigation: the 30-38s vanilla-client wait

Status: ANALYSIS ONLY, no production code touched. `NetServer.java` was being
actively patched in parallel, so this doc treats it as
read-only evidence, not an edit target. Everything below is either (a) a
direct read of the current source, or (b) a real, timed measurement run
outside the repo tree (`/tmp/kestrel-taganalysis`, not committed) against the
repo's already-compiled classes, so the numbers are not guesses.

## Verdict up front

**The config-phase registry/tag burst is not the cause.** Measured directly
(method below), it costs ~75-115ms per join on this Pi. That is real and
worth trimming eventually, but it is two orders of magnitude too small to
explain a 30-38s wait.

**The actual cause is synchronous, on-demand chunk generation running
directly inside the shared tick thread's `NetServer.tick()` call**, with no
async offload and (for the join burst specifically) no per-tick budget at
all. On a fresh world - the normal state for a manual dev-client test - every
chunk the joining player's view needs beyond the single eagerly-generated
spawn chunk gets generated one at a time, synchronously, at a measured
~231ms mean / 479ms p95 / 1047ms max per chunk (this repo's own G2 speed
benchmark, `harness/tools/parity/fullrun/speed-notes.md`). The join burst
alone (5x5 = 25 chunks, uncapped, one `streamBurst` call) accounts for
~5.5s of blocking by itself; the ordinary post-join stream then continues
generating the rest of view-distance-10 (441 chunks total) at only 4
chunks/tick, each batch still blocking the *entire* tick loop - not just this
player's connection - for ~4x the per-chunk cost. 30-38s corresponds to
roughly 130-160 total chunks needing fresh generation before the client
considers itself loaded, which is well inside the 416 chunks still owed
after the burst. This matches the order of magnitude exactly; the registry
burst does not.

## Why "Hypixel joins in 2s" doesn't disprove this

Hypixel serves a lobby world where every chunk near spawn has existed on
disk for years - zero generation cost, pure disk-cache reads. That is not a
counter-example to the generation-cost theory; it is the control condition
predicted by it. The cheap way to confirm this without more instrumentation:
**join the same Kestrel world a second time.** `LiveGenerator.ensureChunk`
checks `SectionStore.isChunkGenerated` and no-ops for chunks already in the
store (`LiveGenerator.java:82-84`), so a second join into the same
already-explored area should complete in ~1-2s, not 30s+. If it does, that
is a live, zero-code-change confirmation of this finding.

## Evidence

### 1. Registry/tags burst: measured, ruled out

`NetServer.handleConfig` (`CONFIG_SELECT_KNOWN` case, `NetServer.java:788-803`)
does, per join, on the connection's own I/O thread:

```
sendRegistries(session, accepted);                     // NetServer.java:800
session.send(Outbound.updateTags(datapackTags()));     // NetServer.java:801
session.send(Outbound.finishConfiguration());           // NetServer.java:802
```

`sendRegistries` (`NetServer.java:969-1000`) just re-serves pre-encoded
`byte[]` entries from the `datapack` field, which is loaded once at server
boot (`KestrelServer.java:267`, `Datapack.load`) - that part is already a
memcpy, as the class doc for `Datapack` promises.

`datapackTags()` (`NetServer.java:1019-1021`) is the one part that is
*not* cached: it calls `Datapack.tags(datapack)` fresh on every single join
(`NetServer.java:1020`), which walks every registry's tag directory
(`Files.walk`), reads and JSON-parses every tag file, and recursively
expands `#`-references (including cross-registry lookups that rescan every
other registry's tag dir with `Files.isRegularFile`, `Datapack.java:574-582,
616-649`) - all from scratch, every time.

Measured directly: extracted the real datapack tree from
`preresearch/vanilla-26.2/server.jar`'s bundled
`META-INF/versions/26.2/server-26.2.jar` (`data/` = 9,023 files, 837 of them
tag files: 267 block, 227 item), then ran the actual `Datapack.load` +
`Datapack.tags` methods (unmodified, straight from the repo's compiled
classes) against that tree with JDK 25 + the real `BuiltInRegistries`
bootstrap (same `staticOrders` construction as
`KestrelServer.java:223-266`):

```
Datapack.load (startup, once): 1756.8 ms
join #1: Datapack.tags() = 114.9 ms (38 registries, 697 tags)
join #2: Datapack.tags() =  89.3 ms
join #3: Datapack.tags() =  95.0 ms
join #4: Datapack.tags() =  74.7 ms
join #5: Datapack.tags() =  77.6 ms
```

(`Datapack.load` itself is a one-time startup cost, before the listener
ever opens - irrelevant to per-join latency.) ~75-115ms per join is real
waste (see Fix 3 below) but not a 30s bottleneck.

### 2. Chunk generation: synchronous, on the shared tick thread, no cap on the burst

`KestrelServer.java:120-183`: for a fresh world (no `.mca` files yet - the
normal state for a dev/test client join), a `LiveGenerator` is built and
handed to the `ChunkSender`. Only a single chunk (or whatever
`--generate=cx,cz,half` covers, default `half=0` i.e. one chunk) is
eagerly generated at boot (`KestrelServer.java:164-174`). Everything else
generates on demand, the first time the network layer asks for it.

`ChunkSender.sectionBytes` (`ChunkSender.java:247-256`), called for every
chunk about to be sent to a client, does this inline:

```java
if (generator != null) {
    for (int[] touched : generator.ensureChunk(loop.store(), chunkX, chunkZ)) {
        markDirty(pack(touched[0], touched[1]));
    }
}
```

`LiveGenerator.ensureChunk` (`LiveGenerator.java:80-100`) runs the full
NOISE+SURFACE+CARVERS+FEATURES+settleLeaves pipeline for the requested
chunk's 3x3 carver window plus its own FEATURES pass plus a 5x5
settleLeaves sweep - the exact pipeline `speed-notes.md` benchmarked.

This all runs on the single tick thread, inside `NetServer.tick()`
(`KestrelServer.java:327-329`, the game loop: `loop.tick(); pollEvents();
netServer.tick(sender);`), which is called from a plain
`while (!stop.get())` loop with **no watchdog and no catch-up**
(`KestrelServer.java:324-349`): `next += 50; sleep = next - now; if (sleep
<= 0) next = now;`. A tick that takes 5 seconds does not skip, crash, or
disconnect anyone - it just runs 5 seconds late and the loop carries on.
That is exactly the "eventually joins, just very slowly" symptom reported,
as opposed to a timeout/disconnect.

Two call sites hit this, with different (and in one case absent) throttling:

- **Join burst, uncapped.** `NetServer.tick` calls
  `sender.streamBurst(joining, session)` (`NetServer.java:1450`) once per
  joining player. `ChunkSender.streamBurst` (`ChunkSender.java:188-217`)
  builds the full 5x5 = 25-chunk list and sends **all of them in one
  call**, no `CHUNKS_PER_TICK` cap - that cap only exists in
  `streamAround`. Of those 25, only the one chunk covered by
  `--generate` (typically none, if that flag wasn't passed) is free; the
  other ~24 each pay the full generation cost. At the benchmark's 231ms
  mean, that's ~5.5s of blocking in a single tick, before the client is
  even teleported into the world (stage 2 of the two-stage join waits for
  this tick to finish first).
- **Post-join stream, capped at 4/tick but each one still blocks.**
  `ChunkSender.streamAround` (`ChunkSender.java:122-180`) sends up to
  `CHUNKS_PER_TICK = 4` (`ChunkSender.java:44`) chunks per player per tick
  toward the full view distance (default 10 -> 441 chunks total,
  `NetServer.java:60`, clamped 2..16 at `NetServer.java:816`). The cap
  bounds how many chunks queue per tick, not how long generating them
  takes: 4 fresh chunks at ~231ms each is ~920ms of tick-thread blocking
  crammed into what should be a 50ms tick, repeated every tick until the
  view distance fills. This also stalls every *other* connected player's
  movement, chunk stream, and keepalive, since it is the same shared tick
  thread (`NetServer.tick` iterates all sessions in one call).

### 3. Reconciling with the observed 30-38s

Burst alone: ~24 chunks x ~231ms ~= 5.5s.
Remaining budget to reach 38s: ~32.5s more, at roughly one chunk's cost per
chunk (the `CHUNKS_PER_TICK=4` batching doesn't parallelize the work, it
just decides how many chunks worth of generation happen per tick) implies
another ~140 chunks generated before the client's own loading-screen logic
considered itself done. That is well inside the 416 chunks still owed after
the burst to reach the full view-distance-10 ring (441 total), and it lines
up with `p95 = 479ms` and `max = 1047ms` outliers (tree-heavy chunks hitting
the documented `SETTLE_LEAVES` and `FEATURES` bottlenecks in
`speed-notes.md`) pulling the average up over a run of ~150+ chunks.

This is consistent with, not exact - the client's own "enough chunks to stop
showing the loading screen" heuristic isn't something this analysis measured
directly - but it is the right order of magnitude from first principles,
where the registry-burst theory (~100ms) is not.

## Contrast with vanilla's own architecture

The benchmark doc already shows vanilla pays a *comparable* per-chunk cost
(207.87ms mean, close to Kestrel's 231.07ms) - vanilla is not fundamentally
faster at generating a chunk. The difference is architectural, not raw
speed: vanilla's `noise_work_ms`/`biomes_work_ms` rows in the same benchmark
are explicitly worker-pool numbers - vanilla dispatches generation to a
bounded background executor (`ChunkTaskDispatcher` /
`GenerationChunkHolder`, per `chunkgen-path-analysis.md`) and the main tick
thread only ever *sends* chunks whose generation future has already
completed. It never blocks the tick loop on generation. Kestrel's
`ChunkSender.sectionBytes` calling `generator.ensureChunk` inline is the one
piece of that pipeline still wired synchronously onto the tick thread.

## Fix proposals (ranked by effort/impact)

1. **Cap the join burst through the same per-tick budget streamAround
   already has** (smallest change, real impact). `streamBurst` currently
   sends all 25 chunks in one shot with no throttle
   (`ChunkSender.java:188-217`). Splitting it into repeated `CHUNKS_PER_TICK`
   -sized slices - or just routing the burst through the same throttled
   path `streamAround` uses - won't reduce total generation work, but it
   stops one player's join from stalling every other connected player's
   tick in a single multi-second freeze, and keeps each individual tick
   closer to bounded (still not bounded to 50ms while any chunk generates
   inline, but no longer 24x that in one call).

2. **Move `LiveGenerator.ensureChunk` off the tick thread onto a bounded
   worker pool** (the real fix, matches vanilla's own architecture).
   `ChunkSender.sectionBytes` would submit missing chunks to the pool and
   either skip sending that chunk this tick (retry next tick once ready) or
   hold the whole batch until its members resolve; either way the tick
   thread stops blocking on `HeadlessNoise` work entirely, same as vanilla's
   `ChunkTaskDispatcher`/`GenerationChunkHolder` future-based handoff. This
   is the change that actually fixes multiplayer scaling too (right now the
   whole server stalls whenever any one player explores new terrain, not
   just the joining player).

3. **Cache `Datapack.tags(datapack)` once at boot instead of recomputing it
   per join** (small, unrelated to the 30s bug, but free to fix while in the
   area). `datapack` is loaded once and never mutated after boot
   (`Datapack`'s own class doc: "loaded once at startup... read-only
   afterwards"), so `NetServer.datapackTags()` recomputing the full tag
   graph from disk on every single join (`NetServer.java:1019-1021`) has no
   reason to vary between joins. Compute it once in `setDatapack` or lazily
   memoize it, and it drops from ~75-115ms/join to ~0.

4. **Interim mitigation if (2) is too large to land immediately**: give the
   tick loop a per-tick generation time budget (e.g. stop starting new
   `ensureChunk` calls once N ms have been spent on generation this tick,
   deferring the rest to the next tick). This does not fix the underlying
   synchronous-on-tick-thread problem - a chunk already in progress still
   can't be preempted mid-pipeline - but it caps the *worst case* per tick
   to roughly one chunk's cost instead of four or twenty-five, which is a
   meaningfully smaller stall while (2) is built.

## Reproduction notes (for whoever picks this up)

The `Datapack.tags()` timing above was produced with a standalone harness
(`TagBench.java`, not part of the repo - written to `/tmp`, not committed)
that: (a) extracts `data/` from
`preresearch/vanilla-26.2/server.jar`'s inner
`META-INF/versions/26.2/server-26.2.jar`, (b) bootstraps
`SharedConstants`/`Bootstrap` and builds `staticOrders` exactly like
`KestrelServer.java:223-266`, (c) calls `Datapack.load` once then
`Datapack.tags` five times back to back, against the repo's own compiled
`dev.kestrel.net.Datapack`/`SyncedRegistries` classes (no repo files were
modified; classes were read from an existing build output directory).
Needs JDK 25 (class file version 69) to link against the bundled
`server-26.2.jar`, not the system JDK 17.

The chunk-generation cost numbers are not new measurements - they are
this repo's own committed benchmark
(`harness/tools/parity/fullrun/speed-notes.md`,
2026-09-14, same Pi hardware), read directly rather than re-run, since the
question here is architectural (sync vs. async placement of a known cost),
not "how fast is HeadlessNoise" again.

## Addendum (2026-09-14): the real remaining 40s was the spawn scan

Measured, not inferred. The live server log for the user's 15:20 join
(client `Connecting` 15:20:31, no clock skew between Pi and bench PC):

- 15:20:31 to 15:20:53 (22s): nothing reached config. `finishLogin`
  calls `addPlayer`, which calls `findSpawn()` on the I/O thread.
- tick 381010 to 381011 (20s, a single tick): `joinHeadless` calls
  `findSpawn()` again on the tick thread.
- tick 381012 to 381013 (6s): first stream tick generating fresh edge
  terrain (generation throughput, a separate issue).

`findSpawn` -> `topWritten` rebuilt `SectionStore.writtenPositions()` (a
`BlockPos` for every written cell in the world) and called `topY`, which
rebuilt it again, once per written cell in the column. 594 loaded
sections (~2.4M cells) cost ~20s per call. The world has since saved at
12,808 sections: the pre-fix build hits `OutOfMemoryError` in the login
thread 24s into the join (`-Xmx1500m`), so the next live restart would
not have let anyone join at all.

Fix: `topWrittenY` walks the column's section bitsets top-down
(<= 24 section lookups x 16 cells). Loopback probe (a throwaway script,
real protocol, offline mode) on a copy of the live world:

| case | teleport (loading gate) | full view |
|---|---|---|
| pre-fix, 12,808 sections | never (OOM at 24s) | never |
| fixed, new player, vd 10 | 0.55s | 441 chunks at 6.7s |
| fixed, saved player, vd 16 | 0.19s | 1089 chunks at 13.9s |
| fixed, fresh empty world, vd 10 | 4.5s (3x3 generated, cold JIT) | 441 at 43s (streamed in-world) |

The client's `Timed out while waiting for the client to load chunks` 30s
fallback appeared once in the bench PC client log (10:34, pre-burst-fix) and
not in any later session, so it is not part of the current 40s.
