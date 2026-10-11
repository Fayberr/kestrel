package dev.kestrel.worldgen;

import dev.kestrel.loop.SectionStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Pregeneration throughput of the production structures-on generator, for a
 * like-for-like comparison with Paper's World.getChunkAtAsync (the GenBench
 * plugin): the same seed, the same size x size square, spiral order from its
 * centre.
 *
 * <p>Paper brings the square to FULL, which needs FEATURES one ring further
 * out (LIGHT depends on radius-1 INITIALIZE_LIGHT). This bench therefore runs
 * the production worker job (ensureChunk, then the biome and light wire
 * snapshots AsyncChunkGenerator takes) for every center of the square plus
 * one ring, and checks that every chunk of the square ended up LIGHT-ready.
 *
 * <p>Mode {@code direct} calls the worker job inline. Mode {@code pipeline}
 * drives the production path end to end: {@link AsyncChunkGenerator} (its
 * radius-2 dependency capture, worker job, immutable result freeze) and
 * {@link dev.kestrel.net.ChunkSender#publishAsyncResults} on a simulated
 * 50 ms tick that admits up to CHUNKS_PER_TICK centers, exactly as the
 * server tick does.
 *
 * <p>{@code carverThreads} (default 0) enables
 * {@link LiveGenerator#enableParallelCarvers}; direct mode then hints the next
 * {@link AsyncChunkGenerator#DEFAULT_MAX_QUEUED} centers the way the worker does.
 *
 * <p>{@code queue} (default {@link AsyncChunkGenerator#DEFAULT_MAX_QUEUED}) is
 * the pipeline's queue cap and the direct mode's lookahead.
 *
 * <p>{@code -Dgenspeed.warmup=N} (default 0) first runs the same mode on an
 * N x N square around chunk 0,0 in a separate generator, so the timed run
 * starts with a warm JIT, as Paper's does after preparing its spawn area.
 *
 * <p>Usage: GenSpeedBench seed centreX centreZ size [direct|pipeline] [carverThreads] [queue]
 */
public final class GenSpeedBench {

    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        int cx = Integer.parseInt(args[1]);
        int cz = Integer.parseInt(args[2]);
        int size = Integer.parseInt(args[3]);
        String mode = args.length > 4 ? args[4] : "direct";
        int carverThreads = args.length > 5 ? Integer.parseInt(args[5]) : 0;
        int queue = args.length > 6 ? Integer.parseInt(args[6]) : AsyncChunkGenerator.DEFAULT_MAX_QUEUED;

        long tLoad = System.nanoTime();
        HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(seed);
        System.out.printf("GENSPEED load %.3f s%n", (System.nanoTime() - tLoad) / 1e9);

        int half = size / 2;
        List<int[]> square = spiral(cx, cz, size, half);
        List<int[]> centers = spiral(cx, cz, size + 2, half + 1);

        int warmup = Integer.getInteger("genspeed.warmup", 0);
        if (warmup > 0) {
            // JIT and one-time cache warm-up in a separate generator on a
            // separate square around chunk 0,0 (where Paper prepares its spawn
            // area before its timed run). The timed run below starts from a
            // fresh LiveGenerator, so only JVM state and the structural
            // caches carry over, as they do in a running server.
            long tw = System.nanoTime();
            System.out.printf("GENSPEED warmup begin size=%d%n", warmup);
            run(structural, spiral(0, 0, warmup, warmup / 2), spiral(0, 0, warmup + 2, warmup / 2 + 1),
                    mode, carverThreads, queue);
            System.out.printf("GENSPEED warmup end %.3f s%n", (System.nanoTime() - tw) / 1e9);
            HeadlessNoise.BIOMES_FROM_DONOR.set(0);
        }
        run(structural, square, centers, mode, carverThreads, queue);
    }

    private static void run(HeadlessNoise.Structural structural, List<int[]> square, List<int[]> centers,
            String mode, int carverThreads, int queue) throws Exception {
        if (mode.equals("pipeline")) {
            pipeline(structural, square, centers, carverThreads, queue);
            return;
        }
        try (LiveGenerator generator = new LiveGenerator(structural)) {
            generator.enableParallelCarvers(carverThreads);
            SectionStore store = new SectionStore(HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT);
            generator.setExclusiveStore(store);
            long t0 = System.nanoTime();
            long gen = 0, snap = 0;
            int done = 0;
            for (int i = 0; i < centers.size(); i++) {
                int[] c = centers.get(i);
                long a = System.nanoTime();
                if (carverThreads > 0) {
                    generator.prefetchCenters(centers.subList(i,
                            Math.min(centers.size(), i + queue)));
                }
                List<int[]> touched = generator.ensureChunk(store, c[0], c[1]);
                long b = System.nanoTime();
                generator.snapshotBiomeWire(touched);
                generator.snapshotNewlyReadyLightWire(store, touched);
                long d = System.nanoTime();
                gen += b - a;
                snap += d - b;
                if (++done % 256 == 0) {
                    System.out.printf("GENSPEED progress centers=%d %.1f centers/s%n",
                            done, done / ((System.nanoTime() - t0) / 1e9));
                }
            }
            double s = (System.nanoTime() - t0) / 1e9;
            int lit = 0;
            for (int[] c : square) {
                if (generator.isLitForBench(c[0], c[1])) {
                    lit++;
                }
            }
            if (lit != square.size()) {
                throw new AssertionError("only " + lit + "/" + square.size() + " square chunks reached LIGHT");
            }
            System.out.printf("GENSPEED done mode=direct carverThreads=%d chunks=%d centers=%d seconds=%.3f"
                            + " chunksPerSecond=%.2f genS=%.3f snapshotS=%.3f carvers=%d biomesFromDonor=%d%n",
                    carverThreads, square.size(), centers.size(), s, square.size() / s,
                    gen / 1e9, snap / 1e9, generator.carversCount(), HeadlessNoise.BIOMES_FROM_DONOR.get());
        }
    }

    private static void pipeline(HeadlessNoise.Structural structural,
            List<int[]> square, List<int[]> centers, int carverThreads, int queue) throws Exception {
        dev.kestrel.loop.KestrelLevel level = new dev.kestrel.loop.KestrelLevel(
                dev.kestrel.loop.KestrelLevel.Dimension.OVERWORLD, HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT);
        dev.kestrel.loop.TickLoop loop = new dev.kestrel.loop.TickLoop(level);
        try (LiveGenerator generator = new LiveGenerator(structural)) {
            dev.kestrel.net.ChunkSender sender = new dev.kestrel.net.ChunkSender(loop, 1);
            sender.setGenerator(generator);
            generator.enableParallelCarvers(carverThreads);
            AsyncChunkGenerator async = new AsyncChunkGenerator(generator, loop.store(), queue);
            sender.setAsyncGenerator(async);
            long t0 = System.nanoTime();
            long gen = 0, snap = 0, publish = 0, tickWork = 0;
            int next = 0, done = 0, ticks = 0;
            try {
                long tickAt = t0;
                while (done < centers.size()) {
                    long ts = System.nanoTime();
                    dev.kestrel.net.ChunkSender.AsyncPublishBatch batch = sender.publishAsyncResults();
                    for (dev.kestrel.net.ChunkSender.AsyncPublication p : batch.results()) {
                        if (p.failed()) {
                            throw new IllegalStateException("generation failed at " + p.cx() + "," + p.cz(),
                                    p.failure());
                        }
                        gen += p.genNanos();
                        snap += p.snapshotNanos();
                        done++;
                    }
                    publish += batch.totalNanos();
                    int admitted = 0;
                    while (admitted < dev.kestrel.net.ChunkSender.CHUNKS_PER_TICK && next < centers.size()) {
                        int[] c = centers.get(next);
                        if (!async.requestGeneration(c[0], c[1])) {
                            break;
                        }
                        next++;
                        admitted++;
                    }
                    tickWork += System.nanoTime() - ts;
                    ticks++;
                    tickAt += 50_000_000L;
                    long wait = tickAt - System.nanoTime();
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                    } else {
                        tickAt = System.nanoTime();
                    }
                }
            } finally {
                sender.closeAsyncGenerator();
            }
            double s = (System.nanoTime() - t0) / 1e9;
            int lit = 0;
            for (int[] c : square) {
                if (generator.isLitForBench(c[0], c[1])) {
                    lit++;
                }
            }
            if (lit != square.size()) {
                throw new AssertionError("only " + lit + "/" + square.size() + " square chunks reached LIGHT");
            }
            System.out.printf("GENSPEED done mode=pipeline carverThreads=%d chunks=%d centers=%d seconds=%.3f"
                            + " chunksPerSecond=%.2f genS=%.3f snapshotS=%.3f publishS=%.3f tickWorkS=%.3f ticks=%d"
                            + " carvers=%d%n",
                    carverThreads, square.size(), centers.size(), s, square.size() / s, gen / 1e9, snap / 1e9,
                    publish / 1e9, tickWork / 1e9, ticks, generator.carversCount());
        }
    }

    /** Square spiral from the centre outward, clipped to [-half, size-half). */
    static List<int[]> spiral(int cx, int cz, int size, int half) {
        List<int[]> out = new ArrayList<>(size * size);
        int x = 0, z = 0, dx = 1, dz = 0, leg = 1, steps = 0, turns = 0;
        while (out.size() < size * size) {
            if (x >= -half && x < size - half && z >= -half && z < size - half) {
                out.add(new int[]{cx + x, cz + z});
            }
            x += dx;
            z += dz;
            steps++;
            if (steps == leg) {
                steps = 0;
                int t = dx;
                dx = -dz;
                dz = t;
                turns++;
                if (turns % 2 == 0) {
                    leg++;
                }
            }
        }
        return out;
    }
}
