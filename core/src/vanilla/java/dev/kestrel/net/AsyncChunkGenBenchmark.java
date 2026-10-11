package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.worldgen.AsyncChunkGenerator;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.LiveGenerator;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Same 121-center fresh-terrain benchmark as #160, with production publication
 * included. Completion is observed only through ChunkSender.publishAsyncResults;
 * the benchmark never steals AsyncChunkGenerator's completion queue.
 */
public final class AsyncChunkGenBenchmark {
    private AsyncChunkGenBenchmark() {}

    private static final long SEED = 4242424242L;
    private static final int RADIUS = 5;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<int[]> chunks = spiralSquare(0, 0, RADIUS);
        System.out.println("[bench] scenario radius=" + RADIUS + " chunks=" + chunks.size());
        runLegacy(chunks);
        runPrototype(chunks);
    }

    private static void runLegacy(List<int[]> chunks) throws Exception {
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        LiveGenerator gen = new LiveGenerator(HeadlessNoise.load(SEED));
        ChunkSender sender = new ChunkSender(loop, 1);
        sender.setGenerator(gen);
        sender.setGenBudgetNanos(Long.MAX_VALUE / 2);

        List<Long> stalls = new ArrayList<>(chunks.size());
        long wallStart = System.nanoTime();
        for (int[] c : chunks) {
            long t0 = System.nanoTime();
            sender.sectionBytes(c[0], c[1]);
            stalls.add(System.nanoTime() - t0);
        }
        long wall = System.nanoTime() - wallStart;
        gen.close();

        System.out.println("\n=== LEGACY synchronous ===");
        report("tick-thread stall per chunk", stalls);
        System.out.printf("[bench] wall all %d chunks: %.3fs%n", chunks.size(), wall / 1e9);
    }

    private static void runPrototype(List<int[]> chunks) throws Exception {
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        LiveGenerator gen = new LiveGenerator(HeadlessNoise.load(SEED));
        ChunkSender sender = new ChunkSender(loop, 1);
        sender.setGenerator(gen);
        AsyncChunkGenerator async = new AsyncChunkGenerator(gen, loop.store());
        sender.setAsyncGenerator(async);

        List<Long> tickStall = new ArrayList<>();
        List<Long> completionLatency = new ArrayList<>();
        List<Long> generation = new ArrayList<>();
        List<Long> snapshot = new ArrayList<>();
        List<Long> publishPerResult = new ArrayList<>();
        List<Long> publishBatch = new ArrayList<>();
        List<Integer> queueDepth = new ArrayList<>();
        Map<Long, Long> requestedAt = new HashMap<>();

        int next = 0;
        int completed = 0;
        long wallStart = System.nanoTime();
        long deadline = wallStart + 120_000_000_000L;
        try {
            while (completed < chunks.size() && System.nanoTime() < deadline) {
                long tickStart = System.nanoTime();

                ChunkSender.AsyncPublishBatch batch = sender.publishAsyncResults();
                long observedAt = System.nanoTime();
                if (!batch.isEmpty()) {
                    publishBatch.add(batch.totalNanos());
                }
                for (ChunkSender.AsyncPublication p : batch.results()) {
                    if (p.failed()) {
                        throw new IllegalStateException(
                                "async benchmark generation failed at " + p.cx() + "," + p.cz(),
                                p.failure());
                    }
                    Long requested = requestedAt.remove(pack(p.cx(), p.cz()));
                    if (requested == null) {
                        throw new IllegalStateException(
                                "published center was never requested: " + p.cx() + "," + p.cz());
                    }
                    completionLatency.add(observedAt - requested);
                    generation.add(p.genNanos());
                    snapshot.add(p.snapshotNanos());
                    publishPerResult.add(p.publishNanos());
                    completed++;
                }

                int admitted = 0;
                while (admitted < ChunkSender.CHUNKS_PER_TICK && next < chunks.size()) {
                    int[] c = chunks.get(next);
                    if (!async.requestGeneration(c[0], c[1])) {
                        break;
                    }
                    requestedAt.put(pack(c[0], c[1]), System.nanoTime());
                    next++;
                    admitted++;
                }

                tickStall.add(System.nanoTime() - tickStart);
                queueDepth.add(async.queueDepth());
                Thread.sleep(2);
            }
        } finally {
            sender.closeAsyncGenerator();
            gen.close();
        }
        long wall = System.nanoTime() - wallStart;

        System.out.println("\n=== ASYNC production publication ===");
        if (completed != chunks.size()) {
            throw new AssertionError("only " + completed + "/" + chunks.size() + " completed");
        }
        report("tick-thread work per simulated tick INCLUDING publication", tickStall);
        report("request -> published completion latency", completionLatency);
        report("worker ensureChunk cost", generation);
        report("worker immutable snapshot cost", snapshot);
        report("tick publication cost per completed result", publishPerResult);
        report("tick publication batch cost", publishBatch);
        IntSummary q = summarizeInt(queueDepth);
        System.out.printf("[bench] queue depth mean=%.1f p50=%d p95=%d max=%d cap=%d%n",
                q.mean, q.p50, q.p95, q.max, AsyncChunkGenerator.DEFAULT_MAX_QUEUED);
        System.out.printf("[bench] wall all %d chunks: %.3fs%n", chunks.size(), wall / 1e9);
    }

    private static List<int[]> spiralSquare(int pcx, int pcz, int radius) {
        List<int[]> out = new ArrayList<>();
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
                        out.add(new int[]{pcx + dx, pcz + dz});
                    }
                }
            }
        }
        return out;
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    private static void report(String label, List<Long> nanos) {
        if (nanos.isEmpty()) {
            System.out.println("[bench] " + label + ": no samples");
            return;
        }
        long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        double mean = Arrays.stream(sorted).average().orElse(0) / 1e6;
        double p50 = sorted[sorted.length * 50 / 100] / 1e6;
        double p95 = sorted[Math.min(sorted.length - 1, sorted.length * 95 / 100)] / 1e6;
        double max = sorted[sorted.length - 1] / 1e6;
        System.out.printf("[bench] %s n=%d mean=%.3fms p50=%.3fms p95=%.3fms max=%.3fms%n",
                label, sorted.length, mean, p50, p95, max);
    }

    private record IntSummary(double mean, int p50, int p95, int max) {}

    private static IntSummary summarizeInt(List<Integer> values) {
        int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
        if (sorted.length == 0) return new IntSummary(0, 0, 0, 0);
        return new IntSummary(
                Arrays.stream(sorted).average().orElse(0),
                sorted[sorted.length * 50 / 100],
                sorted[Math.min(sorted.length - 1, sorted.length * 95 / 100)],
                sorted[sorted.length - 1]);
    }
}
