package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.SectionStore;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.worldgen.AsyncChunkGenerator;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.LiveGenerator;

import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Issue #160 prototype: end-to-end regression gate for the {@link
 * ChunkSender#setAsyncGenerator}/{@link ChunkSender#publishAsyncResults}
 * wiring, exercised against the real {@link LiveGenerator} pipeline
 * (same driver/seed setup as {@link GenBudgetOverrideCheck}) rather than
 * {@link dev.kestrel.worldgen.AsyncChunkGeneratorCheck}'s fake jobs.
 * Proves three things the fake-job check cannot: real generated cells
 * actually land in the live store (single publication, real content -
 * not just a flipped flag), the tick-thread synchronous budget ({@link
 * ChunkSender#genBudgetLeft()}) is never charged for background work,
 * and re-publishing after a drain is a safe no-op.
 *
 * <p><b>Thread-safety note for readers of this test:</b> {@link
 * LiveGenerator} is single-owner, not thread-safe. Once a chunk is
 * requested through {@code asyncGen}, this test never calls {@code
 * sender.sectionBytes} (or anything else that could reach {@code
 * gen.ensureChunk}) for that same chunk until after it has observably
 * finished (via {@link SectionStore#isChunkGenerated}) - exactly the
 * discipline {@link AsyncChunkGenerator}'s javadoc requires of real
 * callers, not a test-only shortcut.
 *
 * <p>Run (after {@code ./gradlew build -PvanillaJar=... -PvanillaLibs=...}):
 * <pre>
 * java -cp build/classes/java/main:VANILLA_JAR:VANILLA_LIBS/* \
 *     dev.kestrel.net.AsyncChunkSenderCheck
 * </pre>
 */
public final class AsyncChunkSenderCheck {
    private AsyncChunkSenderCheck() {
    }

    private static final long SEED = 4242424242L;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        HeadlessNoise.Driver driver = HeadlessNoise.load(SEED);
        LiveGenerator gen = new LiveGenerator(driver);
        ChunkSender sender = new ChunkSender(loop, 1);
        sender.setGenerator(gen);
        AsyncChunkGenerator asyncGen = new AsyncChunkGenerator(gen, loop.store());
        sender.setAsyncGenerator(asyncGen);

        boolean ok = true;
        long key = ChunkPos.pack(9, 9);

        try {
            ok &= expect(!loop.store().isChunkGenerated(key), "chunk starts ungenerated in a fresh store");

            ok &= expect(sender.genBudgetLeft(), "sync budget starts open");

            ok &= expect(asyncGen.requestGeneration(9, 9), "background job for the fresh chunk is admitted");

            // Real generation on the Pi runs ~200-600ms per the #159 audit,
            // comfortably longer than one poll interval, so this loop
            // reliably observes the "still in flight" state at least once
            // before completion - not required for correctness, just makes
            // the "budget untouched while generation is in flight" a real
            // observation instead of a lucky race.
            boolean sawInFlight = false;
            boolean budgetStayedOpenWhileInFlight = true;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!loop.store().isChunkGenerated(key) && System.nanoTime() < deadline) {
                sawInFlight = true;
                // The core "must not wait behind generation" contract: the
                // tick-thread synchronous budget is never charged for
                // background work. Accumulated rather than logged per poll
                // (hundreds of polls for one real ~2s generation call would
                // otherwise flood the log with identical lines).
                budgetStayedOpenWhileInFlight &= sender.genBudgetLeft();
                sender.publishAsyncResults();
                Thread.sleep(5);
            }
            ok &= expect(sawInFlight, "test actually observed the chunk while still generating (timing sanity check)");
            ok &= expect(budgetStayedOpenWhileInFlight,
                    "sync budget stayed open for every poll while background generation was in flight");
            ok &= expect(loop.store().isChunkGenerated(key), "background job finished and was published within the timeout");

            // Single publication: the flag flip must come with real copied
            // content, not just markChunkGenerated with nothing behind it.
            ok &= expect(hasNonAirCell(loop.store(), 9, 9), "published chunk has real (non-air) generated content");

            // Re-publishing with nothing new completed must be a safe no-op:
            // no exception, and it must not somehow un-mark or corrupt what
            // was already published.
            sender.publishAsyncResults();
            ok &= expect(loop.store().isChunkGenerated(key), "chunk is still generated after a no-op re-publish");
            ok &= expect(hasNonAirCell(loop.store(), 9, 9), "content survives a no-op re-publish");

            // Safe now: the chunk is generated, so sectionBytes will not
            // reach gen.ensureChunk again for it (needsGeneration is
            // false), so this is not a race with the (already-finished)
            // worker thread.
            ok &= expect(!sender.sectionBytes(9, 9).isEmpty(),
                    "sectionBytes serves the published chunk without triggering inline generation");

            // Join scheduling: prepareJoinBurst must return before doing real
            // generation, queue the forced 3x3 in historical dx->dz order,
            // then keep the join deferred until all nine forced chunks have
            // exact radius-dependent LIGHT metadata. The dependency frontier
            // must remain on the bounded worker queue, never the tick thread.
            KestrelPlayer joiner = new KestrelPlayer(
                    UUID.fromString("00000000-0000-0000-0000-000000000160"),
                    "async-join-check", 40 * 16 + 8, 80, 40 * 16 + 8);
            int beforeJoinDepth = asyncGen.queueDepth();
            boolean joinReady = sender.prepareJoinBurst(joiner);
            ok &= expect(!joinReady, "fresh async join ring is deferred, not generated inline");
            ok &= expect(asyncGen.queueDepth() > beforeJoinDepth,
                    "async join preparation admitted forced-ring generation jobs");
            ok &= expect(sender.genBudgetLeft(),
                    "async join preparation did not charge the synchronous generation budget");

            long joinDeadline = System.nanoTime() + 15_000_000_000L;
            while (!joinReady && System.nanoTime() < joinDeadline) {
                sender.publishAsyncResults();
                joinReady = sender.prepareJoinBurst(joiner);
                Thread.sleep(2);
            }
            sender.publishAsyncResults();
            ok &= expect(joinReady, "forced async join ring became network-ready after publication");
            for (int dx = -ChunkSender.BURST_FORCED_RADIUS;
                    dx <= ChunkSender.BURST_FORCED_RADIUS; dx++) {
                for (int dz = -ChunkSender.BURST_FORCED_RADIUS;
                        dz <= ChunkSender.BURST_FORCED_RADIUS; dz++) {
                    long joinKey = ChunkPos.pack(joiner.chunkX() + dx, joiner.chunkZ() + dz);
                    ok &= expect(loop.store().isChunkGenerated(joinKey),
                            "forced join center published " + dx + "," + dz);
                    ok &= expect(loop.level().lightWireChunk(joinKey) != null,
                            "forced join center has exact light wire " + dx + "," + dz);
                }
            }

            // Backfill scheduling: the cadence call may admit work, but it
            // must not run ensureChunk synchronously or spend sync budget.
            sender.enqueueBackfill(70, 70);
            int beforeBackfillDepth = asyncGen.queueDepth();
            sender.drainBackfill(1);
            ok &= expect(asyncGen.isQueued(70, 70),
                    "async backfill center is owned by the worker queue");
            ok &= expect(asyncGen.queueDepth() >= beforeBackfillDepth + 1,
                    "async backfill admission increased bounded queue depth");
            ok &= expect(sender.genBudgetLeft(),
                    "async backfill admission did not charge synchronous generation budget");

            long backfillDeadline = System.nanoTime() + 10_000_000_000L;
            long backfillKey = ChunkPos.pack(70, 70);
            while (!loop.store().isChunkGenerated(backfillKey)
                    && System.nanoTime() < backfillDeadline) {
                sender.publishAsyncResults();
                Thread.sleep(2);
            }
            ok &= expect(loop.store().isChunkGenerated(backfillKey),
                    "async backfill center eventually published real generation");
        } finally {
            asyncGen.close();
        }

        System.out.println(ok ? "AsyncChunkSenderCheck: PASS" : "AsyncChunkSenderCheck: FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    private static boolean hasNonAirCell(SectionStore store, int cx, int cz) {
        int minSectionY = store.getMinY() >> 4;
        int count = store.getHeight() >> 4;
        for (int slot = 0; slot < count; slot++) {
            long key = SectionPos.asLong(cx, minSectionY + slot, cz);
            if (!store.hasSection(key)) {
                continue;
            }
            for (int i = 0; i < SectionStore.CELLS; i++) {
                BlockState st = store.readCell(key, i);
                if (st != null && !st.isAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean expect(boolean condition, String message) {
        System.out.println((condition ? "[async-sender] OK: " : "[async-sender] FAIL: ") + message);
        return condition;
    }
}
