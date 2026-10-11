# Is the 350 MiB whole-server target reachable without losing mechanics or vanilla parity?

Status: investigation complete, 2026-09-17.
Question as asked: can Kestrel reach 350 MiB RSS *without* "leaving out
mechanics" or "not retaining vanilla parity".

Short answer: **yes for an idle / lightly loaded server, today, by
configuration alone. Not yet for a 441-chunk (view distance 10) world**,
where the current measured floor is 435 MiB. The remaining 85 MiB is not
world data. It is generation scaffolding the live generator never releases,
and releasing it is parity-neutral by construction.

## 1. Why every previous number was misleading

Two measurement faults were found and corrected during this investigation.

**The baselines used a collector production does not run.** The W1/W2 table
(`harness/tools/parity/mem-compact-w1w2/`) and the README's "W1 load RSS
495.05 MiB still exceeds the 350 MiB target" were measured under
`-Xms128M -Xmx1G -XX:+UseSerialGC`. The documented production launch
(`docs/RUNNING.md`) passes only `-Xmx4G` and lets the JVM choose, and on
this host (4 cores, 24 GiB) the ergonomic default is already **G1**.
`jcmd <prod pid> VM.flags` confirms `-XX:+UseG1GC` on the running server.
So the headline gap was never a production measurement.

**The memory probe flattered the collector.** `MemProbe.sample` calls
`System.gc()` before sampling. A full GC is exactly the event that lets G1
uncommit, and no production server issues one. An earlier result that "G1
returns 84 MiB" was an artifact of the probe, not a property of G1. Every
number in this document comes from runs with the probe disabled.

## 2. What the server is actually made of

Measured with NMT summary plus `GC.class_histogram` after two forced full
GCs, at two world sizes (`harness` scripts in `~/kestrel-run/membench`).
Committed MiB:

| category | empty (1 chunk) | vd10 (441 chunks) |
| --- | --- | --- |
| Java heap (committed) | 220.2 | 435.6 |
| Metaspace + Class + Symbol + shared class space | 100.8 | 101.9 |
| Code cache | 26.5 | 34.3 |
| Arena chunk | 18.5 | 9.1 |
| Thread + GC + internal + other | ~6 | ~6 |
| *(NMT's own overhead, a measurement artifact)* | *4.9* | *5.0* |

Live heap from the histograms: **88.1 MiB empty, 174.2 MiB at vd10**.

Two structural facts follow.

**The non-heap floor is about 152 MiB and two thirds of it is class
metadata.** That 100.8 MiB is the cost of linking against the real vanilla
server jar and loading its registry and class graph. It can only be reduced
directly by loading fewer vanilla classes, which is precisely the
mechanics/parity tradeoff that is ruled out. The parity-safe lever here is
AppCDS, which is currently unused (the JDK's default `classes.jsa` is
mapped, but no application archive exists).

**RSS is not dominated by file-backed mappings.** `smaps` shows only ~34 MB
of file-backed RSS (libjvm.so, the CDS archive). 343.6 MiB of the empty
server is anonymous JVM memory. There is no "just mmap it" win available.

## 3. Configuration alone: the largest single lever, and it is free

Settled RSS after generation plus 180 s idle, probe disabled:

| config | empty | vd10 (441 chunks) |
| --- | --- | --- |
| production today (`-Xmx4G`, default G1) | 530.6 | 620.4 |
| tuned (`-Xmx256M` + G1 uncommit recipe) | **321.6** | **435.2** |

The tuned recipe is:

```
-XX:+UseG1GC
-XX:G1PeriodicGCInterval=10000
-XX:G1PeriodicGCSystemLoadThreshold=0
-XX:-G1PeriodicGCInvokesConcurrent
-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25
```

`-XX:-G1PeriodicGCInvokesConcurrent` is the load-bearing flag and is easy to
get wrong. It defaults to **true**, which makes the periodic trigger run a
*concurrent* cycle, and concurrent cycles do not uncommit. Three variants
tested before this was understood (`serial`, plain `g1`, and `g1periodic`
with the default `InvokesConcurrent`) all returned exactly zero: idle RSS
stayed flat at its peak for the full 150 s sample in every repeat. The live
production server shows the same signature, `VmRSS == VmHWM` to three
decimals after three hours of uptime.

This costs no mechanic and no parity. It is a launch line.

A heap-cap ladder confirmed there is no hidden price. Every cap from 768M
down to **192M** completed a full 441-chunk generation with no OOM, and
`genMs` stayed flat at 76-79 s across the whole ladder, so there is no GC
thrash penalty. Live heap was identical at 177.1 MiB regardless of the cap:
the server needs what it needs, and offering it more only inflates
committed heap.

## 4. What is left, and why it is also parity-safe

At vd10 the tuned config settles at 435.2 MiB, which is 113.6 MiB above the
empty tuned server for 441 chunks, or **264 KiB per chunk**. Actual block
storage is about **19 KiB per chunk** (the step-2b compact model, B = 12.79
MiB for 441 chunks, and the W1 to W2 slope independently confirms 18.9
KiB/chunk). So roughly 93% of the per-chunk cost is not block data.

The histogram identifies it. At vd10 there are **529 live `NoiseChunk`
objects**, and 529 is exactly the 23x23 footprint that a 21x21 generated
square touches with its radius-1 margin. One is retained per chunk ever
generated, each pinning a full density-function graph:

| retained per chunk | count |
| --- | --- |
| `DensityFunctions$Spline$Coordinate` | 119 |
| `DensityFunctions$MulOrAdd` | 65 |
| `FlatCache` / `CacheOnce` / `Cache2D` | 10 / 7 / 7 |
| `NoiseInterpolator` (owns the `double[]` slices) | 8 |

That accounts for nearly all of the 86,137 `double[]` (20.9 MiB, against 2.3
MiB on an empty server) and roughly 30 MiB of live heap.

The cause is `LiveGenerator.grid`, a `HashMap<Long, ChunkAccess>` whose own
Javadoc says "grows, never evicted". It must keep the `ProtoChunk` so a
neighbour's later FEATURES pass can still write into it (vanilla's own
order-dependent accumulation), but the `NoiseChunk` memoized on that
`ProtoChunk` is read only by the noise, surface and carve passes. Vanilla
never pays this cost because it discards the entire `ProtoChunk` after
FEATURES.

There is a second, larger instance of the same problem: the retained
`ProtoChunk`s hold their own `PalettedContainer`s, so the world's block data
is stored **twice**, once compact in `SectionStore` and again in the grid.
That is what the 39,819 `long[]` / 37.9 MiB histogram row is.

Neither is a mechanic and neither is a parity property. They are generation
scratch that outlives its purpose.

## 5. Verdict

| target | status |
| --- | --- |
| Empty / idle server under 350 MiB | **Memory-only result: 321.6 MiB; STW tuning rejected for production** |
| vd10 (441 chunks) under 350 MiB | **Not met**, now 410.4 MiB after the NoiseChunk fix below, but the remaining gap is scratch, not world |

The Step-9 player-impact gate settled the open GC question on 2026-09-21.
With `-XX:-G1PeriodicGCInvokesConcurrent`, three periodic Full GCs during a
connected-player route paused for 90.614, 101.844 and 106.254 ms. The same
route had tick-work p50 0.0649 ms and p95 4.2604 ms, but a 103.6276 ms maximum
that coincided with the stop-the-world regime. The forced-Full-GC tuning is
therefore rejected as a production default. Kestrel keeps the parity-neutral
retention fixes and uses concurrent periodic G1 for the production/live gate;
the 350 MiB target remains a memory-optimization goal rather than being bought
with regular ~100 ms stalls.

Measured, two repeats each, tuned flags, probe disabled:

| build | settled RSS | peak RSS |
| --- | --- | --- |
| HEAD | 460.8 / 456.3 | 459.3 / 454.7 |
| with NoiseChunk release | **410.1 / 410.7** | 417.7 / 416.3 |

The patched series steps down at sample 8 (418 to 410) while HEAD stays flat
at its peak for the whole 180 s window, which is the uncommit finally biting.

No mechanic has to be dropped and no vanilla behaviour has to be
approximated to close the remaining gap. The work is, in descending order of
payoff:

1. Release the memoized `NoiseChunk` once CARVERS is done. Implemented and
   parity-gated in `harness/tools/parity/noisechunk-release/`: **about 48
   MiB**, taking vd10 from 458.6 to 410.4 MiB settled. It also unblocks the
   uncommit flags, which could not return anything while the scratch was
   genuinely live.
2. Evict grid entries once every chunk within Chebyshev distance **2** has
   completed its own FEATURES pass. This is the one that removes the
   duplicated block storage, roughly 38 MiB at this world size. Not yet
   implemented, and the radius is the subtle part: a center writes into its
   3x3, so 3x3 is enough to bound *writes*, but `ensureChunk` also calls
   `settleLeaves(fiveByFiveWindow(...))`, so a chunk is still **read** by any
   center within distance 2. Evicting on the 3x3 rule would silently
   downgrade those reads to `settleLeaves`'s missing-entry edge
   approximation on interior chunks and drift from vanilla. Distance 2 is
   safe because centers run at most once (`SectionStore.isChunkGenerated`),
   so once all 25 have run, no future center can reach the chunk again.
3. Keep production periodic G1 concurrent. The forced-Full-GC variant is
   retained only as historical memory evidence, not as a recommended launch
   configuration.
4. AppCDS, against the ~101 MiB of class metadata.

## 6. Caveats

- All figures are from `primary-vnic`, not bench PC. Absolute RSS here runs
  roughly 50 MiB higher than bench PC for the same build, so these numbers must
  not be pasted into a bench PC evidence table. The comparisons within this
  document are all same-host and therefore valid.
- `SectionStore.promote` (compact to dense on first write) has no inverse,
  so every section a player edits costs 20,614 bytes instead of about 2,214
  for the process lifetime. It does not affect the cold-start figures above,
  but it is unbounded growth on a server where people build. Filed here
  rather than fixed.
