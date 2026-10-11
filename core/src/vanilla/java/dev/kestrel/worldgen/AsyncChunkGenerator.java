package dev.kestrel.worldgen;

import dev.kestrel.loop.SectionStore;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Bounded single-worker live chunk generation for G2 Step 9.
 *
 * <p>The LiveGenerator and its persistent shadow SectionStore are owned only by
 * the worker. A completed Result contains a deep immutable compact snapshot of
 * the touched section columns. The tick thread never receives or reads the
 * mutable shadow store, so job N+1 may start immediately without racing
 * publication of job N.
 *
 * <p>One worker preserves the caller's admitted center order across FEATURES,
 * while maxQueued bounds queued + in-flight + completed-but-not-drained work.
 */
public final class AsyncChunkGenerator implements AutoCloseable {

    /**
     * Bounds how much generation work may be ahead of the player. Step 9
     * measured on its 121-center benchmark that cap 4 cut request->published
     * p95 from ~11.93s at cap 64 to ~0.95s. Step 10 raised it to 8: the cap
     * also counts in-flight work and completed work waiting for the next
     * tick's drain, so at 4 the carver pool saw only about two centers
     * ahead and the owner thread waited on it. 32x32 pipeline benchmark:
     * cap 4 gave 21.48 chunks/s, cap 6 26.43, cap 8 28.47 and 27.24, cap 12
     * 26.53. Queueing delay at cap 8 is about 8 / 28 = 0.3 s, below Step 9's
     * cap-4 figure. Order is unchanged (single worker, FIFO).
     */
    public static final int DEFAULT_MAX_QUEUED = 8;

    @FunctionalInterface
    interface GenerationJob {
        List<int[]> run(SectionStore store, int cx, int cz) throws Exception;
    }

    @FunctionalInterface
    interface BiomeSnapshotJob {
        Map<Long, byte[]> snapshot(List<int[]> touched) throws Exception;
    }

    @FunctionalInterface
    interface LightSnapshotJob {
        Map<Long, byte[]> snapshot(SectionStore store, List<int[]> touched) throws Exception;
    }

    /** Immutable touched-chunk coordinate. */
    public record ChunkRef(int x, int z) {}

    /** One finished (or failed) generation job, drained on the tick thread. */
    public static final class Result {
        public final int cx;
        public final int cz;
        /** Immutable touched chunk list; null on failure. */
        public final List<ChunkRef> touched;
        /** Immutable section snapshots keyed by SectionPos long; absent means all-air. */
        public final Map<Long, SectionStore.CompactData> sections;
        /** Exact Vanilla biome PalettedContainerRO wire bytes by SectionPos key. */
        public final Map<Long, byte[]> biomes;
        /** Exact Vanilla ClientboundLightUpdatePacketData payloads by ChunkPos key. */
        public final Map<Long, byte[]> lights;
        /**
         * Pending post-processing lists of the touched chunks by ChunkPos key
         * ({@link LiveGenerator#snapshotPostProcessing}), worker-private copies.
         */
        public final Map<Long, it.unimi.dsi.fastutil.shorts.ShortList[]> postProcessing;
        /** Time spent in LiveGenerator.ensureChunk itself. */
        public final long genNanos;
        /** Time spent freezing the worker shadow into this Result. */
        public final long snapshotNanos;
        public final Throwable failure;

        private Result(
                int cx,
                int cz,
                List<ChunkRef> touched,
                Map<Long, SectionStore.CompactData> sections,
                Map<Long, byte[]> biomes,
                Map<Long, byte[]> lights,
                Map<Long, it.unimi.dsi.fastutil.shorts.ShortList[]> postProcessing,
                long genNanos,
                long snapshotNanos,
                Throwable failure) {
            this.cx = cx;
            this.cz = cz;
            this.touched = touched;
            this.sections = sections;
            this.biomes = biomes;
            this.lights = lights;
            this.postProcessing = postProcessing;
            this.genNanos = genNanos;
            this.snapshotNanos = snapshotNanos;
            this.failure = failure;
        }

        public boolean failed() {
            return failure != null;
        }
    }

    private static final ThreadFactory DAEMON = r -> {
        Thread t = new Thread(r, "kestrel-chunkgen-async");
        t.setDaemon(true);
        return t;
    };

    private final GenerationJob job;
    private final BiomeSnapshotJob biomeSnapshotJob;
    private final LightSnapshotJob lightSnapshotJob;
    /** Production generator used to rehydrate persisted FULL dependencies. */
    private final LiveGenerator liveGenerator;
    /** Tick-thread live store; read only while capturing immutable request dependencies. */
    private final SectionStore liveStore;
    /** Worker-exclusive persistent cache/store. Never exposed to the tick thread. */
    private final SectionStore shadow;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(DAEMON);
    private final BlockingQueue<Result> completed = new LinkedBlockingQueue<>();
    /** Light payloads refreshed by chunk-activation writes, drained on the tick thread. */
    private final BlockingQueue<Map<Long, byte[]>> activationLights = new LinkedBlockingQueue<>();
    private final int maxQueued;

    /** Tick-thread only: admitted chunks not yet drained, including completed results. */
    private final Set<Long> queued = new LinkedHashSet<>();
    /** Tick-thread only: chunks that failed once and are never silently retried. */
    private final Set<Long> failedOnce = new LinkedHashSet<>();

    private volatile boolean closed;

    /**
     * Admitted centers the worker has not started, in admission order. Only
     * a hint for {@link LiveGenerator#prefetchCenters}; it never decides what
     * is generated or in which order FEATURES runs.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> upcoming =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public AsyncChunkGenerator(LiveGenerator generator, SectionStore liveStoreForDims) {
        this(generator, liveStoreForDims, DEFAULT_MAX_QUEUED);
    }

    public AsyncChunkGenerator(LiveGenerator generator, SectionStore liveStoreForDims, int maxQueued) {
        this(generator::ensureChunk,
                generator::snapshotBiomeWire,
                generator::snapshotNewlyReadyLightWire,
                handoffShadow(generator, liveStoreForDims),
                maxQueued,
                generator,
                liveStoreForDims);
    }

    /**
     * Preserve generator-owned completion across the synchronous-to-async
     * ownership handoff.
     *
     * <p>A fresh boot may synchronously generate spawn/eager centers before
     * the worker is attached. The shared {@link LiveGenerator} already owns
     * those centers' ProtoChunk/INITIALIZE_LIGHT state, so their completion
     * markers must exist in the worker store for radius-dependent LIGHT
     * readiness.
     *
     * <p>Do not copy arbitrary live-store completion markers: a reload/import
     * can contain persisted generated centers whose ProtoChunks are not owned
     * by this newly-created LiveGenerator. Only centers initialized by this
     * exact generator instance are valid handoff state.
     */
    private static SectionStore handoffShadow(
            LiveGenerator generator, SectionStore liveStore) {
        SectionStore shadow = new SectionStore(liveStore.getMinY(), liveStore.getHeight());
        for (long key : generator.initializedChunkKeys()) {
            if (liveStore.isChunkGenerated(key)) {
                shadow.markChunkGenerated(key);
            }
        }
        return shadow;
    }

    AsyncChunkGenerator(GenerationJob job, SectionStore shadow, int maxQueued) {
        this(job, null, null, shadow, maxQueued, null, null);
    }

    AsyncChunkGenerator(
            GenerationJob job,
            BiomeSnapshotJob biomeSnapshotJob,
            LightSnapshotJob lightSnapshotJob,
            SectionStore shadow,
            int maxQueued) {
        this(job, biomeSnapshotJob, lightSnapshotJob, shadow, maxQueued, null, null);
    }

    private AsyncChunkGenerator(
            GenerationJob job,
            BiomeSnapshotJob biomeSnapshotJob,
            LightSnapshotJob lightSnapshotJob,
            SectionStore shadow,
            int maxQueued,
            LiveGenerator liveGenerator,
            SectionStore liveStore) {
        if (maxQueued <= 0) {
            throw new IllegalArgumentException("maxQueued must be positive");
        }
        this.job = job;
        this.biomeSnapshotJob = biomeSnapshotJob;
        this.lightSnapshotJob = lightSnapshotJob;
        this.liveGenerator = liveGenerator;
        this.liveStore = liveStore;
        this.shadow = shadow;
        this.maxQueued = maxQueued;
    }

    public boolean requestGeneration(int cx, int cz) {
        if (closed) {
            return false;
        }
        long key = pack(cx, cz);
        if (queued.contains(key) || failedOnce.contains(key) || queued.size() >= maxQueued) {
            return false;
        }

        // Production calls this on the tick thread. Freeze already-generated
        // radius-2 dependencies before handing work to the background thread.
        // Generation itself observes radius 1, but a persisted radius-1 chunk
        // that still needs LIGHT requires its own radius-1 INITIALIZE_LIGHT
        // closure, which reaches one additional chunk away from this request.
        // The worker therefore never reads the mutable live store, while the
        // complete persisted/new LIGHT frontier remains satisfiable.
        SectionStore completedDependencies = liveStore == null
                ? null : snapshotCompletedDependencies(liveStore, cx, cz);

        queued.add(key);
        upcoming.add(key);
        executor.submit(() -> runOne(cx, cz, completedDependencies));
        return true;
    }

    private static SectionStore snapshotCompletedDependencies(
            SectionStore liveStore, int cx, int cz) {
        SectionStore snapshot = new SectionStore(liveStore.getMinY(), liveStore.getHeight());
        int minSectionY = liveStore.getMinY() >> 4;
        int sectionCount = liveStore.getHeight() >> 4;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int nx = cx + dx;
                int nz = cz + dz;
                long chunkKey = ChunkPos.pack(nx, nz);
                if (!liveStore.isChunkGenerated(chunkKey)) {
                    continue;
                }
                snapshot.markChunkGenerated(chunkKey);

                for (int slot = 0; slot < sectionCount; slot++) {
                    long sectionKey = SectionPos.asLong(nx, minSectionY + slot, nz);
                    if (!liveStore.hasSection(sectionKey)) {
                        continue;
                    }
                    SectionStore.CompactData compact =
                            liveStore.compactSnapshot(sectionKey);
                    if (compact == null) {
                        throw new IllegalStateException(
                                "present live section has no immutable snapshot " + sectionKey);
                    }
                    snapshot.putCompact(
                            sectionKey,
                            compact.palette().clone(),
                            compact.data() == null ? null : compact.data().clone(),
                            compact.bits());
                }
            }
        }
        return snapshot;
    }

    private void runOne(int cx, int cz, SectionStore completedDependencies) {
        long genStart = System.nanoTime();
        long genNanos = 0L;
        try {
            upcoming.remove(pack(cx, cz));
            if (liveGenerator != null) {
                // The shadow store is private to this worker (the tick thread
                // only sees frozen copies), so unchanged sections need not be
                // republished into it.
                liveGenerator.setExclusiveStore(shadow);
            }
            if (liveGenerator != null && completedDependencies != null) {
                liveGenerator.hydrateCompletedDependencies(shadow, completedDependencies);
            }
            if (liveGenerator != null && liveGenerator.parallelCarverThreads() > 0) {
                List<int[]> ahead = new ArrayList<>();
                ahead.add(new int[]{cx, cz});
                for (long next : upcoming) {
                    ahead.add(new int[]{(int) (next >> 32), (int) next});
                }
                liveGenerator.prefetchCenters(ahead);
            }
            List<int[]> touched = job.run(shadow, cx, cz);
            genNanos = System.nanoTime() - genStart;
            long snapshotStart = System.nanoTime();
            Result result = snapshotResult(cx, cz, touched, genNanos, snapshotStart);
            completed.add(result);
        } catch (Throwable t) {
            if (genNanos == 0L) {
                genNanos = System.nanoTime() - genStart;
            }
            completed.add(new Result(cx, cz, null, null, null, null, null, genNanos, 0L, t));
        }
    }

    private Result snapshotResult(
            int cx, int cz, List<int[]> rawTouched, long genNanos, long snapshotStart) {
        if (rawTouched == null) {
            throw new IllegalStateException("successful generation returned null touched list");
        }
        List<ChunkRef> touched = new ArrayList<>(rawTouched.size());
        Set<Long> seen = new LinkedHashSet<>();
        Map<Long, SectionStore.CompactData> sections = new LinkedHashMap<>();
        int minSectionY = shadow.getMinY() >> 4;
        int sectionCount = shadow.getHeight() >> 4;

        for (int[] pair : rawTouched) {
            if (pair == null || pair.length < 2) {
                throw new IllegalStateException("invalid touched chunk coordinate");
            }
            long chunkKey = pack(pair[0], pair[1]);
            if (!seen.add(chunkKey)) {
                continue;
            }
            ChunkRef ref = new ChunkRef(pair[0], pair[1]);
            touched.add(ref);
            for (int slot = 0; slot < sectionCount; slot++) {
                long sectionKey = SectionPos.asLong(ref.x(), minSectionY + slot, ref.z());
                if (!shadow.hasSection(sectionKey)) {
                    continue;
                }
                SectionStore.CompactData data = shadow.compactData(sectionKey);
                if (data == null) {
                    throw new IllegalStateException(
                            "async worker shadow contains mutable dense section " + sectionKey);
                }
                sections.put(sectionKey, freeze(data));
            }
        }

        Map<Long, byte[]> biomes = Map.of();
        if (biomeSnapshotJob != null && !rawTouched.isEmpty()) {
            Map<Long, byte[]> rawBiomes;
            try {
                rawBiomes = biomeSnapshotJob.snapshot(rawTouched);
            } catch (Exception e) {
                throw new IllegalStateException("failed to snapshot async biome metadata", e);
            }
            Map<Long, byte[]> frozen = new LinkedHashMap<>();
            for (Map.Entry<Long, byte[]> entry : rawBiomes.entrySet()) {
                byte[] bytes = entry.getValue();
                if (bytes == null || bytes.length == 0) {
                    throw new IllegalStateException(
                            "invalid async biome payload section=" + entry.getKey());
                }
                frozen.put(entry.getKey(), bytes.clone());
            }
            biomes = Collections.unmodifiableMap(frozen);
        }

        Map<Long, byte[]> lights = Map.of();
        if (lightSnapshotJob != null && !rawTouched.isEmpty()) {
            Map<Long, byte[]> rawLights;
            try {
                rawLights = lightSnapshotJob.snapshot(shadow, rawTouched);
            } catch (Exception e) {
                throw new IllegalStateException("failed to snapshot async light metadata", e);
            }
            Map<Long, byte[]> frozen = new LinkedHashMap<>();
            for (Map.Entry<Long, byte[]> entry : rawLights.entrySet()) {
                byte[] bytes = entry.getValue();
                if (bytes == null || bytes.length == 0) {
                    throw new IllegalStateException(
                            "invalid async light payload chunk=" + entry.getKey());
                }
                frozen.put(entry.getKey(), bytes.clone());
            }
            lights = Collections.unmodifiableMap(frozen);
        }

        Map<Long, it.unimi.dsi.fastutil.shorts.ShortList[]> postProcessing =
                liveGenerator == null || rawTouched.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(liveGenerator.snapshotPostProcessing(rawTouched));

        return new Result(
                cx,
                cz,
                List.copyOf(touched),
                Collections.unmodifiableMap(sections),
                biomes,
                lights,
                postProcessing,
                genNanos,
                System.nanoTime() - snapshotStart,
                null);
    }

    /** Deep copy so a Result owns no arrays reachable from the mutable worker store. */
    private static SectionStore.CompactData freeze(SectionStore.CompactData data) {
        return new SectionStore.CompactData(
                data.palette().clone(),
                data.data() == null ? null : data.data().clone(),
                data.bits());
    }

    /**
     * Hand a chunk-activation pass's writes to the worker, which owns the
     * generator's grid and light engine; see
     * {@link LiveGenerator#applyActivationWrites}. The single-thread executor
     * orders this after every generation job already submitted, the way the
     * threaded light engine orders a main-thread check after earlier tasks.
     */
    public void submitActivationWrites(List<dev.kestrel.loop.PostProcess.Write> writes) {
        if (closed || liveGenerator == null || writes.isEmpty()) {
            return;
        }
        List<dev.kestrel.loop.PostProcess.Write> copy = List.copyOf(writes);
        executor.submit(() -> {
            try {
                activationLights.add(liveGenerator.applyActivationWrites(copy));
            } catch (Throwable t) {
                System.out.println("[kestrel] activation light refresh failed: " + t);
            }
        });
    }

    /** Refreshed light payloads from finished activation jobs, in completion order. */
    public List<Map<Long, byte[]>> drainActivationLights() {
        List<Map<Long, byte[]>> out = new ArrayList<>();
        activationLights.drainTo(out);
        return out;
    }

    /** The generator's engine for the activation pass's brightness reads, or null. */
    public net.minecraft.world.level.lighting.LevelLightEngine activationLightEngine() {
        return liveGenerator == null ? null : liveGenerator.activationLightEngine();
    }

    public boolean isQueued(int cx, int cz) {
        return queued.contains(pack(cx, cz));
    }

    /** Queued/in-flight/completed-not-drained count; this is the bounded admission count. */
    public int queueDepth() {
        return queued.size();
    }

    /** Thread-safe diagnostic used by the parity gate to force worst-case delayed publication. */
    public int completedDepth() {
        return completed.size();
    }

    public int failedCount() {
        return failedOnce.size();
    }

    /** True when this center failed once and will not be silently retried. */
    public boolean hasFailed(int cx, int cz) {
        return failedOnce.contains(pack(cx, cz));
    }

    public List<Result> drainCompleted() {
        List<Result> out = new ArrayList<>();
        Result r;
        while ((r = completed.poll()) != null) {
            long key = pack(r.cx, r.cz);
            queued.remove(key);
            if (r.failed()) {
                failedOnce.add(key);
            }
            out.add(r);
        }
        return out;
    }

    static long pack(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Stop admission and wait for the single in-flight job before its
     * LiveGenerator owner is closed. Shutdown must never race structural state.
     */
    @Override
    public void close() {
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.out.println("[kestrel] WARNING: async chunk generator did not terminate cleanly");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
