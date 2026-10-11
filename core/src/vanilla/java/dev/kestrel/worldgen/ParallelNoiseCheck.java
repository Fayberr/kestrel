package dev.kestrel.worldgen;

import dev.kestrel.loop.SectionStore;
import dev.kestrel.persist.BlockHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * G2 step-5 gate ({@code preresearch/g2-build-order.md} step 5 exit 1): P1
 * must be identical at 1, 2 and 4 threads on a fixed chunk set generated in
 * a shuffled order, and a second shuffled order at the same thread count
 * must also match. P1 is the pure prefix through NOISE (trap 13: only
 * FEATURES writes neighbours), so a difference here is a driver bug, never
 * a tolerance.
 *
 * <p>Also prints the step-5 throughput row per thread count: wall ms per
 * chunk, summed-NOISE (busy) ms per chunk, chunks/s, and the per-worker
 * slot counts. Exits non-zero on any hash mismatch, so it is scriptable.
 *
 * <p>Run (Pi, JDK 25, after {@code ./gradlew build -PvanillaJar=... -PvanillaLibs=...}):
 * <pre>
 * java -cp build/classes/java/main:VANILLA_JAR:VANILLA_LIBS/* \
 *     dev.kestrel.worldgen.ParallelNoiseCheck [1,2,4]
 * </pre>
 */
public final class ParallelNoiseCheck {
    private ParallelNoiseCheck() {
    }

    /** Corpus seed (g2-build-order step 1 / MSPT-ref). */
    public static final long SEED = 4242424242L;

    /**
     * Fixed 8-chunk set: the origin 2x2, plus one chunk per direction so the
     * sample is not a single plateau (the step-2 import census keeps a
     * 49x49 square, so all of these are inside corpus territory).
     */
    private static final int[][] CHUNKS = {
            {0, 0}, {1, 0}, {0, 1}, {1, 1}, {2, 0}, {-1, 2}, {3, -1}, {-2, -2},
    };

    public static void main(String[] args) throws Exception {
        int[] counts = args.length > 0 ? parseCounts(args[0]) : new int[]{1, 2, 4};
        System.out.println("[parallel-noise] cpus=" + Runtime.getRuntime().availableProcessors()
                + " vanilla_bg_pool=" + net.minecraft.util.Util.maxAllowedExecutorThreads()
                + " chunks=" + CHUNKS.length + " seed=" + SEED);
        long loadStart = System.nanoTime();
        HeadlessNoise.Driver driver = HeadlessNoise.load(SEED);
        System.out.printf("[parallel-noise] driver loaded in %.1f s%n",
                (System.nanoTime() - loadStart) / 1e9);

        // Warmup: one discarded single-threaded pass so the measured legs
        // run JIT-compiled noise code. Without it the first legs of a launch
        // are 2-3x slower and the thread-count rows are not comparable.
        HeadlessNoise.generateParallel(driver, new SectionStore(
                HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT), shuffled(CHUNKS, 7), 1);

        // Leg 1: shuffled order at the same thread count. Isolates input
        // order from thread count (step 5's shuffle requirement).
        Map<Long, String> orderA = run(driver, shuffled(CHUNKS, 11), 1, "1-thread order A");
        Map<Long, String> orderB = run(driver, shuffled(CHUNKS, 22), 1, "1-thread order B");
        boolean orderSame = orderA.equals(orderB);
        System.out.println("[parallel-noise] order-independence (1 thread, two shuffles): "
                + (orderSame ? "EQUAL" : "DIFFER"));

        // Leg 2: the thread-count sweep. Count 1 is the step-4 path exactly.
        Map<Long, String> reference = null;
        boolean allEqual = orderSame;
        for (int threads : counts) {
            Map<Long, String> hashes =
                    run(driver, shuffled(CHUNKS, 1000 + threads), threads, threads + "-thread");
            if (reference == null) {
                reference = hashes;
            } else if (!reference.equals(hashes)) {
                allEqual = false;
                for (Map.Entry<Long, String> e : reference.entrySet()) {
                    String got = hashes.get(e.getKey());
                    if (!e.getValue().equals(got)) {
                        System.out.println("[parallel-noise] MISMATCH chunk "
                                + keyToString(e.getKey()) + " " + counts[0] + "-thread="
                                + e.getValue() + " " + threads + "-thread=" + got);
                    }
                }
            }
        }
        for (Map.Entry<Long, String> e : reference.entrySet()) {
            System.out.println("[parallel-noise] P1 chunk " + keyToString(e.getKey())
                    + " " + e.getValue());
        }
        System.out.println("[parallel-noise] threads " + java.util.Arrays.toString(counts)
                + " P1 equality: " + (allEqual ? "EQUAL" : "DIFFER"));
        System.out.println("[parallel-noise] "
                + (allEqual ? "PASS" : "FAIL"));
        if (!allEqual) {
            System.exit(1);
        }
    }

    /** One full generate+hash pass, logged with its throughput row. */
    private static Map<Long, String> run(HeadlessNoise.Driver driver, List<int[]> chunks,
            int threads, String label) {
        SectionStore store = new SectionStore(HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT);
        HeadlessNoise.ParallelStats stats =
                HeadlessNoise.generateParallelStats(driver, store, chunks, threads);
        Map<Long, String> hashes = new LinkedHashMap<>();
        for (int[] c : CHUNKS) {
            hashes.put(key(c[0], c[1]), HeadlessNoise.p1(store, c[0], c[1]));
        }
        System.out.println("[parallel-noise] " + label + " " + stats.summary());
        return hashes;
    }

    /** Fisher-Yates over a copy, deterministic per seed. */
    private static List<int[]> shuffled(int[][] chunks, long seed) {
        List<int[]> order = new ArrayList<>();
        for (int[] c : chunks) {
            order.add(new int[]{c[0], c[1]});
        }
        Random rnd = new Random(seed);
        for (int i = order.size() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int[] tmp = order.get(i);
            order.set(i, order.get(j));
            order.set(j, tmp);
        }
        return order;
    }

    private static int[] parseCounts(String arg) {
        String[] parts = arg.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static String keyToString(long key) {
        return "(" + (int) (key >> 32) + "," + (int) key + ")";
    }
}
