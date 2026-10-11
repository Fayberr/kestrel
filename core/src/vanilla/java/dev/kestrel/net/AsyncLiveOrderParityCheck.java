package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.SectionStore;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.persist.BlockHash;
import dev.kestrel.worldgen.AsyncChunkGenerator;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.LiveGenerator;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Merge gate for #160: same structures-on live center order, synchronous vs
 * single-worker async, then exact final SectionStore block hashes.
 *
 * <p>The async side intentionally waits until every worker job has completed
 * before publishing anything. That maximizes the old shadow-store race window:
 * all later jobs have mutated the persistent worker state before Result 1 is
 * consumed. Only immutable per-result snapshots can pass.
 *
 * <p>Usage: AsyncLiveOrderParityCheck [radius] [asyncCarverThreads]. The async
 * side may run the carver pool; the synchronous side always runs inline and
 * writes every section, so the async worker's skip-unchanged-sections writes
 * into its exclusive shadow store are checked against full writes.
 */
public final class AsyncLiveOrderParityCheck {
    private AsyncLiveOrderParityCheck() {}

    private static final long SEED = 4242424242L;
    private static final int CENTER_X = 88;
    private static final int CENTER_Z = 77;
    private static final int DEFAULT_RADIUS = 2;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        int radius = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_RADIUS;
        int asyncThreads = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        List<int[]> order = spiralSquare(CENTER_X, CENTER_Z, radius);
        KestrelLevel syncLevel = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        SectionStore syncStore = syncLevel.store();
        LiveGenerator syncGen = new LiveGenerator(HeadlessNoise.loadStructural(SEED));
        try {
            for (int[] c : order) {
                List<int[]> touched = syncGen.ensureChunk(syncStore, c[0], c[1]);
                for (var light : syncGen.snapshotNewlyReadyLightWire(syncStore, touched).entrySet()) {
                    syncLevel.putLightWireChunk(light.getKey(), light.getValue());
                }
            }
        } finally {
            syncGen.close();
        }

        KestrelLevel asyncLevel = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop asyncLoop = new TickLoop(asyncLevel);
        SectionStore asyncStore = asyncLoop.store();
        LiveGenerator asyncLive = new LiveGenerator(HeadlessNoise.loadStructural(SEED));
        ChunkSender sender = new ChunkSender(asyncLoop, 1);
        sender.setGenerator(asyncLive);
        asyncLive.enableParallelCarvers(asyncThreads);
        AsyncChunkGenerator async = new AsyncChunkGenerator(asyncLive, asyncStore, order.size() + 4);
        sender.setAsyncGenerator(async);

        List<Long> publishedOrder = new ArrayList<>();
        try {
            for (int[] c : order) {
                if (!async.requestGeneration(c[0], c[1])) {
                    throw new AssertionError("async admission refused " + c[0] + "," + c[1]);
                }
            }

            long deadline = System.nanoTime() + 120_000_000_000L + order.size() * 2_000_000_000L;
            while (async.completedDepth() < order.size() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            if (async.completedDepth() != order.size()) {
                throw new AssertionError(
                        "async worker completed " + async.completedDepth() + "/" + order.size()
                                + " before timeout");
            }

            ChunkSender.AsyncPublishBatch batch = sender.publishAsyncResults();
            if (batch.results().size() != order.size()) {
                throw new AssertionError(
                        "publication count " + batch.results().size() + " != " + order.size());
            }
            for (ChunkSender.AsyncPublication p : batch.results()) {
                if (p.failed()) {
                    throw new AssertionError(
                            "async generation failed at " + p.cx() + "," + p.cz(), p.failure());
                }
                publishedOrder.add(ChunkPos.pack(p.cx(), p.cz()));
            }
        } finally {
            sender.closeAsyncGenerator();
            asyncLive.close();
        }

        List<Long> expectedOrder = order.stream()
                .map(c -> ChunkPos.pack(c[0], c[1]))
                .toList();
        if (!publishedOrder.equals(expectedOrder)) {
            throw new AssertionError(
                    "async center order differs\nexpected=" + expectedOrder + "\nactual=" + publishedOrder);
        }

        if (!syncStore.generatedChunkKeys().equals(asyncStore.generatedChunkKeys())) {
            throw new AssertionError(
                    "generated center markers differ sync=" + syncStore.generatedChunkKeys()
                            + " async=" + asyncStore.generatedChunkKeys());
        }

        int hashComparisons = 0;
        int hashDiffs = 0;
        String first = null;
        int compareRadius = radius + 1;
        for (int x = CENTER_X - compareRadius; x <= CENTER_X + compareRadius; x++) {
            for (int z = CENTER_Z - compareRadius; z <= CENTER_Z + compareRadius; z++) {
                long a = BlockHash.chunk(syncStore, x, z);
                long b = BlockHash.chunk(asyncStore, x, z);
                hashComparisons++;
                if (a != b) {
                    hashDiffs++;
                    if (first == null) {
                        first = x + "," + z + " sync=" + Long.toHexString(a)
                                + " async=" + Long.toHexString(b);
                    }
                }
            }
        }

        int lightComparisons = 0;
        for (int x = CENTER_X - 1; x <= CENTER_X + 1; x++) {
            for (int z = CENTER_Z - 1; z <= CENTER_Z + 1; z++) {
                long key = ChunkPos.pack(x, z);
                byte[] a = syncLevel.lightWireChunk(key);
                byte[] b = asyncLevel.lightWireChunk(key);
                if (a == null || b == null || !Arrays.equals(a, b)) {
                    throw new AssertionError(
                            "async exact-light parity failed chunk=" + x + "," + z
                                    + " sync=" + (a == null ? "null" : a.length)
                                    + " async=" + (b == null ? "null" : b.length));
                }
                lightComparisons++;
            }
        }

        System.out.println("ASYNC_LIVE_ORDER centers=" + order.size()
                + " orderMatch=true generatedMarkers=true hashChunks=" + hashComparisons
                + " hashDiffs=" + hashDiffs
                + " lightChunks=" + lightComparisons
                + (first == null ? "" : " first=" + first));
        if (hashDiffs != 0) {
            throw new AssertionError("async live final-store parity failed: " + first);
        }
        System.out.println("AsyncLiveOrderParityCheck: PASS");
    }

    private static List<int[]> spiralSquare(int cx, int cz, int radius) {
        List<int[]> out = new ArrayList<>();
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
                        out.add(new int[]{cx + dx, cz + dz});
                    }
                }
            }
        }
        return out;
    }
}
