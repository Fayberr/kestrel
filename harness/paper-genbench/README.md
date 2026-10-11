# Paper chunk-generation benchmark

Measures how fast a stock Paper server generates chunks to FULL, as the
reference for Kestrel's Step 10 target (at least Paper's speed with exact
Vanilla output).

Status: Step 10 is done and on main, and this benchmark is the Paper side of
the comparison (`22ddbe5` is the measured build, `3c1d73f` closed the step
out). On the Oracle VPS (4 Ampere cores, seed 4242424242, 32x32 chunks around
1000,1000), the controlled interleaved rerun (Paper and Kestrel back to back
on the same area, same frozen classes, `worker-threads: 3`) gave Paper a mean
33.54 chunks/s against Kestrel's warm production pipeline at 34.07 (+1.6%),
and 37.11 against 39.65 (+6.8%) at chunk centre -2500,1800. Run-to-run spread
is larger than the gap, so the honest reading is "the same speed". Full tables
and method: docs/BENCHMARKS.md, "G2 Step 10 chunk generation speed" and its
interleaved rerun subsection.

`GenBench` is a plugin. On enable it requests every chunk of a size x size
square, in spiral order from the centre, through `World.getChunkAtAsync(x, z, true)`
with a bounded number of requests in flight. When all have completed it logs

    GENBENCH done chunks= seconds= chunksPerSecond= cpuSeconds= cores=

plus CPU seconds per thread group, and stops the server.

Setup (Paper 26.2, `server.properties`: `level-seed=4242424242`,
`generate-structures=true`, `view-distance=4`, a free port):

    ./build.sh ~/bench/paper
    cp run.sh ~/bench/paper/
    ~/bench/paper/run.sh 1000 1000 32 64    # centre x z, size, in flight

`run.sh` deletes the world first, so every run generates fresh terrain.

Kestrel's side of the comparison is `dev.kestrel.worldgen.GenSpeedBench` with
the same seed, centre and size; it brings the same square to LIGHT, which
needs FEATURES on the square plus one ring, just as Paper's FULL does.
