package dev.kestrel.worldgen;

import dev.kestrel.loop.SectionStore;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * G2 step 9 live wiring (preresearch/g2-livewiring-design.md): generate a
 * chunk the tick loop / chunk sender needs but the store does not have,
 * through the same headless pipeline the {@code --generate} preview and
 * {@code BatchParityGate} already use (NOISE + SURFACE + CARVERS +
 * FEATURES), instead of serving flat/empty sections. Post-FULL scheduled
 * transitions (including leaf-distance ticks) are deliberately not folded
 * into this generation stage: doing so would mutate the shared generation
 * grid before later neighbouring FEATURES centers run.
 *
 * <p>Shape: to run a chunk's own FEATURES pass it needs the 3x3 around it
 * at CARVERS ({@link HeadlessNoise.RegionView}'s radius-1 requirement), so
 * {@link #ensureChunk} generates that margin first, reusing any chunk
 * already produced for an earlier center (the {@code grid} cache below;
 * chunk generation off a fresh boot spirals outward one center at a time,
 * so most of a new center's 3x3 is typically already cached from when a
 * neighbour was the center). A chunk's FEATURES pass writes into that same
 * 3x3 (radius 1, same as vanilla), so an already-generated neighbour can
 * still receive new blocks the first time each of its neighbours becomes a
 * center in turn - exactly vanilla's own order-dependent accumulation
 * (g2-build-order.md D1/D2). Every {@link #ensureChunk} call therefore
 * rewrites the whole 3x3 window into {@code store}, and returns the
 * touched chunk coordinates so the caller can mark already-sent chunks
 * dirty for resend.
 *
 * <p>{@link SectionStore#isChunkGenerated} is the completed-chunk marker:
 * a chunk is only ever run as a center once, never re-run because a
 * neighbour again requests it. Not thread-safe (single tick-thread caller,
 * same contract as {@link SectionStore}).
 */
public final class LiveGenerator implements AutoCloseable {

    private final HeadlessNoise.Driver driver;
    /** Non-null only for the production structures-on path. */
    private final HeadlessNoise.Structural structural;

    /**
     * Shared radius-8 STRUCTURE_STARTS/REFERENCES backing. Promoted centers
     * stay the same ProtoChunk objects when they advance through NOISE and
     * later FEATURES, while distant reference targets can remain STARTS-only.
     */
    // fastutil map: ChunkPos.pack keys hash as x^z under Long.hashCode, which
    // piles a generated square's diagonals into HashMap tree bins.
    private final Map<Long, ChunkAccess> structureStarts =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    /**
     * Exact-replay-only pending counts for Vanilla partial-chunk reloads.
     * Production never populates this map. Counts, rather than a set, are
     * required because repeated serialize/reload round-trips of the same
     * fastutil set can change its table layout again. Pending events survive
     * until the chunk has reached STRUCTURE_REFERENCES.
     */
    private final java.util.Map<Long, Integer> pendingReplayStructureReloads =
            new java.util.HashMap<>();

    /**
     * Gate-only: when set, ensureChunk does not initialize the center's light
     * right after FEATURES. Vanilla submits INITIALIZE_LIGHT immediately but
     * completes it on the light executor, often batched well after later
     * neighbouring FEATURES passes, and brightness-sensitive placements (for
     * example mushroom survival) read that frontier. An exact trace gate then
     * drives completions from the captured schedule through
     * {@link #initializeLightForGate}. Production never sets this.
     */
    private boolean deferLightInitializationForGate;

    /**
     * Exact-replay-only: chunks Vanilla reloaded at CARVERS before this
     * generator built them. Their heightmap round trip is applied as soon as
     * ensureCarvers() builds them. Production never populates this set.
     */
    private final java.util.Set<Long> pendingReplayHeightmapReloads =
            new java.util.HashSet<>();

    /**
     * Exact-replay-only: light-initialized chunks that Vanilla reloaded since
     * their last INITIALIZE_LIGHT. Vanilla re-runs INITIALIZE_LIGHT on such a
     * reload; only these may legally see a repeated captured completion.
     * Production never populates this set.
     */
    private final java.util.Set<Long> reloadedSinceLightInitialization =
            new java.util.HashSet<>();

    /** Post-CARVERS chunks, keyed by {@code ChunkPos.pack}; grows, never evicted. */
    // fastutil map: ChunkPos.pack keys hash as x^z under Long.hashCode, which
    // piles a generated square's diagonals into HashMap tree bins.
    private final Map<Long, ChunkAccess> grid =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    /** Shared Vanilla-compatible FEATURES/INITIALIZE_LIGHT storage frontier. */
    private final WorldgenLightLifecycle lighting;

    /**
     * NOISE-only chunks (BIOMES status, no SURFACE/CARVERS run yet), keyed by
     * {@link ChunkPos}: scratch margin for
     * {@link HeadlessNoise#prepareBiomeGrid} so a SURFACE call's 3x3 biome
     * neighbourhood always has a populated palette to read, even one chunk
     * outside the CARVERS-ready {@link #grid}. Never exposed to a
     * {@link HeadlessNoise.RegionView}, never written to the store, never
     * treated as CARVERS/FEATURES-ready: promoting an entry here into
     * {@code grid} happens only inside {@link #ensureCarvers}, the single
     * place that actually runs SURFACE+CARVERS on it. Grows, never evicted,
     * same lifetime contract as {@code grid}.
     */
    private final Map<ChunkPos, ChunkAccess> noiseMargin =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Read-only mirror of {@link #grid} for the carver pool: pool threads
     * resolve SURFACE's biome neighbourhood here, never through the
     * worker-owned {@code grid} HashMap.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, ChunkAccess> biomeReady =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Optional pool for the chunk-local BIOMES/NOISE/SURFACE/CARVERS work of
     * structures-on chunks (Vanilla runs NOISE and BIOMES on its background
     * pool too). Null: everything runs on the calling thread, as the parity
     * gates do. REFERENCES, FEATURES, light and every write into
     * {@link #grid} or {@link #structureStarts} stay on the owner thread;
     * STARTS may be computed ahead on {@link #startsPool}.
     */
    private java.util.concurrent.ExecutorService carverPool;
    private int carverThreads;

    /** Owner-thread only: chunks whose chunk-local stages were handed to the pool. */
    private final Map<Long, java.util.concurrent.Future<ChunkAccess>> prefetched =
            new java.util.LinkedHashMap<>();

    /** Upper bound on pool-owned chunks, which bounds the memory ahead of FEATURES. */
    static final int MAX_PREFETCHED = 24;

    /**
     * One thread that runs STRUCTURE_STARTS for lookahead chunks ahead of
     * the owner (null when the pool is off). STARTS is pure per chunk: its
     * result is a fresh chunk whose starts come from createStructures alone,
     * reading only the seed, the position and the registries. So computing
     * it earlier changes no result. The owner still inserts every result
     * into {@link #structureStarts} itself, inside structureWindow's
     * computeIfAbsent, so the map evolves exactly as it did inline.
     * HeadlessNoise.STARTS_LOCK keeps two STARTS from ever running at once,
     * as in Vanilla, and HeadlessNoise.ConcurrentTemplates makes the template
     * caches that STARTS shares with FEATURES safe to share.
     */
    private java.util.concurrent.ExecutorService startsPool;

    /** Owner-thread only: STARTS results computed or queued ahead, by chunk key. */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<
            java.util.concurrent.FutureTask<ChunkAccess>> startsAhead =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    /** Upper bound on queued or unconsumed STARTS results. */
    static final int MAX_STARTS_AHEAD = 1024;

    /** Null inline, so the gates' inline runs keep the original code path. */
    private HeadlessNoise.StartsSource startsSource;

    /** Chunks run through noise+surfaceVanilla+carveVanilla so far (diagnostic). */
    private int carversCount;
    /** Chunks run through their own FEATURES pass so far (diagnostic). */
    private int centersCount;

    public LiveGenerator(HeadlessNoise.Driver driver) {
        this.driver = driver;
        this.structural = null;
        this.lighting = new WorldgenLightLifecycle(grid, driver);
    }

    /**
     * Structures-on live generator. Ownership of {@code structural} transfers
     * to this instance and is released by {@link #close()}.
     */
    public LiveGenerator(HeadlessNoise.Structural structural) {
        this.structural = java.util.Objects.requireNonNull(structural, "structural");
        this.driver = structural.driver();
        this.lighting = new WorldgenLightLifecycle(grid, driver);
    }

    /**
     * The one store this generator may treat as its own: nothing else writes
     * it, so ensureChunk republishes only sections whose blocks changed since
     * they were last written there (see HeadlessNoise.TrackedSection). Any
     * other store gets every section, as before. Owner thread only.
     */
    private SectionStore exclusiveStore;

    /**
     * Declare {@code store} as written by this generator alone (the async
     * worker's shadow store, a gate's or benchmark's private store). Switching
     * to a different store marks every built section dirty, because the
     * flags described the previous store.
     */
    public void setExclusiveStore(SectionStore store) {
        if (store == exclusiveStore) {
            return;
        }
        exclusiveStore = store;
        for (ChunkAccess c : grid.values()) {
            for (var section : c.getSections()) {
                if (section instanceof HeadlessNoise.TrackedSection t) {
                    t.dirty = true;
                }
            }
        }
    }

    /**
     * Run the chunk-local stages of structures-on chunks on {@code threads}
     * pool threads. Output is unchanged: each chunk's BIOMES/NOISE/SURFACE/
     * CARVERS result depends only on its own prepared STARTS/REFERENCES state
     * and seeded sources (see {@link #prefetchCenters}). Call before the first
     * generation, on the owner thread; {@code threads <= 0} is a no-op.
     */
    public void enableParallelCarvers(int threads) {
        if (threads <= 0 || structural == null || carverPool != null) {
            return;
        }
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        carverThreads = threads;
        carverPool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "kestrel-carvers-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        startsPool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kestrel-starts");
            t.setDaemon(true);
            return t;
        });
        startsSource = this::takeStarts;
    }

    /**
     * The STARTS chunk for x,z: the one computed ahead if there is one, else
     * computed here. {@code FutureTask.run} runs a task the STARTS thread has
     * not started yet on this thread and does nothing if it has, so the owner
     * never waits behind a queue of work it does not need yet.
     */
    private ChunkAccess takeStarts(int x, int z) {
        java.util.concurrent.FutureTask<ChunkAccess> f = startsAhead.remove(ChunkPos.pack(x, z));
        if (f == null) {
            return HeadlessNoise.structures(structural, x, z);
        }
        f.run();
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for STARTS " + x + "," + z, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("STARTS failed at " + x + "," + z, cause);
        }
    }

    /**
     * Drop queued or computed STARTS results far from x,z. Hints for centers
     * that never run (already generated, say) would otherwise hold
     * startsAhead full for good. Dropping is always safe: STARTS is pure, and
     * a dropped chunk is simply computed again if some window needs it.
     */
    private void evictFarStarts(int cx, int cz) {
        var it = startsAhead.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var e = it.next();
            long key = e.getLongKey();
            if (Math.max(Math.abs(ChunkPos.getX(key) - cx), Math.abs(ChunkPos.getZ(key) - cz)) > 32) {
                e.getValue().cancel(false);
                it.remove();
            }
        }
    }

    /** Queue STARTS for every chunk a center's 3x3 structure windows will read. */
    private void scheduleStarts(int cx, int cz) {
        if (startsAhead.size() >= MAX_STARTS_AHEAD) {
            evictFarStarts(cx, cz);
        }
        // X outer, Z inner, as structureWindow consumes them.
        for (int x = cx - 9; x <= cx + 9; x++) {
            for (int z = cz - 9; z <= cz + 9; z++) {
                if (startsAhead.size() >= MAX_STARTS_AHEAD) {
                    return;
                }
                long key = ChunkPos.pack(x, z);
                if (structureStarts.containsKey(key) || startsAhead.containsKey(key)) {
                    continue;
                }
                int sx = x;
                int sz = z;
                java.util.concurrent.FutureTask<ChunkAccess> task =
                        new java.util.concurrent.FutureTask<>(
                                () -> HeadlessNoise.structures(structural, sx, sz));
                startsAhead.put(key, task);
                startsPool.execute(task);
            }
        }
    }

    /** True when every STARTS chunk x,z's structure window reads is ready now. */
    private boolean startsWindowReady(int cx, int cz) {
        for (int x = cx - 8; x <= cx + 8; x++) {
            for (int z = cz - 8; z <= cz + 8; z++) {
                long key = ChunkPos.pack(x, z);
                if (structureStarts.containsKey(key)) {
                    continue;
                }
                java.util.concurrent.FutureTask<ChunkAccess> f = startsAhead.get(key);
                if (f == null || !f.isDone()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Diagnostic: valid structure starts by structure id among chunks built so far. */
    Map<String, Integer> builtStructureStartCounts() {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        var registry = driver.access().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        for (long key : grid.keySet()) {
            ChunkAccess c = structureStarts.get(key);
            if (c == null) {
                continue;
            }
            for (var e : c.getAllStarts().entrySet()) {
                if (e.getValue().isValid()) {
                    counts.merge(String.valueOf(registry.getKey(e.getKey())), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** Pool size, 0 when the chunk-local stages run inline. */
    public int parallelCarverThreads() {
        return carverThreads;
    }

    /**
     * Owner thread: hand the chunk-local stages of every not-yet-built chunk
     * in these centers' 3x3 windows to the carver pool, in the order
     * {@link #ensureChunk} would build them. First it queues STARTS for the
     * chunks their structure windows read on the STARTS thread. Then
     * STARTS/REFERENCES for each chunk are completed here, taking the
     * precomputed STARTS results, so every write into the shared structure
     * state keeps the owner thread's order; only pure work is concurrent.
     *
     * <p>Moving a chunk's BIOMES..CARVERS ahead of an unrelated center's
     * FEATURES is an order Vanilla also produces (its NOISE runs on a
     * background pool whenever the dependency is requested). A chunk is
     * never handed out while it sits in a running FEATURES window, because
     * {@link #ensureChunk} joins its whole 3x3 before FEATURES starts.
     */
    public void prefetchCenters(List<int[]> centers) {
        prefetchCenters(centers, true);
    }

    /**
     * {@code onlyReady}: skip a chunk whose STARTS window is not complete
     * yet, so a lookahead hint never makes the owner wait for (or run)
     * STARTS work that the STARTS thread will finish by itself. It is retried
     * on the next call. ensureChunk passes false for its own 3x3, which it
     * needs now anyway.
     */
    private void prefetchCenters(List<int[]> centers, boolean onlyReady) {
        if (carverPool == null) {
            return;
        }
        for (int[] center : centers) {
            scheduleStarts(center[0], center[1]);
        }
        for (int[] center : centers) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (prefetched.size() >= MAX_PREFETCHED) {
                        return;
                    }
                    prefetchCarvers(center[0] + dx, center[1] + dz, onlyReady);
                }
            }
        }
    }

    private void prefetchCarvers(int cx, int cz, boolean onlyReady) {
        long key = ChunkPos.pack(cx, cz);
        if (grid.containsKey(key) || prefetched.containsKey(key)) {
            return;
        }
        if (onlyReady && !startsWindowReady(cx, cz)) {
            return;
        }
        HeadlessNoise.PreparedNoise prepared = prepareStructural(cx, cz, key);
        prefetched.put(key, carverPool.submit(() -> buildCarvers(
                cx, cz, HeadlessNoise.fillPrepared(structural, prepared,
                        noiseMargin.get(new ChunkPos(cx, cz))),
                (x, z) -> biomeReady.get(ChunkPos.pack(x, z)))));
    }

    /** STARTS/REFERENCES plus any gate-only pending structure reload, owner thread. */
    private HeadlessNoise.PreparedNoise prepareStructural(int cx, int cz, long key) {
        int pendingReloads = pendingReplayStructureReloads.getOrDefault(key, 0);
        HeadlessNoise.PreparedNoise prepared = HeadlessNoise.prepareStructural(
                structural, cx, cz, structureStarts,
                pendingReloads == 0 ? null : referenced -> {
                    for (int i = 0; i < pendingReloads; i++) {
                        HeadlessNoise.roundTripStructureData(structural, referenced);
                    }
                },
                startsSource);
        if (pendingReloads != 0) {
            pendingReplayStructureReloads.remove(key);
        }
        return prepared;
    }

    /** Gate-only replay hooks cannot model a reload of a chunk the pool owns. */
    private void refusePrefetched(int cx, int cz, String what) {
        if (prefetched.containsKey(ChunkPos.pack(cx, cz))) {
            throw new IllegalStateException(
                    what + " for a chunk the carver pool owns: " + cx + "," + cz);
        }
    }

    /** True when this live generator runs real STRUCTURE_STARTS/REFERENCES/pieces. */
    public boolean structuresEnabled() {
        return structural != null;
    }

    /** The driver backing this generator (shared seed/registries). */
    public HeadlessNoise.Driver driver() {
        return driver;
    }

    /** Chunks that reached CARVERS so far (noise+surfaceVanilla+carveVanilla runs). */
    public int carversCount() {
        return carversCount;
    }

    /** Chunks that ran their own FEATURES pass so far. */
    public int centersCount() {
        return centersCount;
    }

    /** Chunks advanced through Vanilla INITIALIZE_LIGHT so far. */
    public int initializedLightCount() {
        return lighting.initializedCount();
    }

    /**
     * Centers this exact generator instance has advanced through
     * INITIALIZE_LIGHT. Used only for the synchronous-to-async ownership
     * handoff; persisted/imported completion markers intentionally do not
     * appear here because this generator does not own their ProtoChunks.
     */
    java.util.Set<Long> initializedChunkKeys() {
        return lighting.initializedKeys();
    }

    /** Bench/diagnostic: whether this chunk has reached LIGHT. */
    boolean isLitForBench(int cx, int cz) {
        return lighting.isLit(ChunkPos.pack(cx, cz));
    }

    LevelLightEngine lightingEngine() {
        return lighting.engine();
    }

    /**
     * Rehydrate already-generated neighbours as worker-owned FULL dependencies.
     *
     * <p>The source is an immutable radius-1 snapshot captured by the tick
     * thread in {@link AsyncChunkGenerator#requestGeneration}; this method never
     * reads the mutable live store. Persisted/reloaded chunks are reconstructed
     * through the ordinary seed-derived CARVERS/biome/structure path, then
     * their authoritative saved block cells replace that scratch terrain before
     * INITIALIZE_LIGHT. Marking the dependency FULL gives later FEATURES the
     * read-only ImposterProtoChunk semantics Vanilla uses for completed chunks.
     *
     * <p>Centers already initialized by this generator (notably synchronous
     * spawn work before async attachment) need only their completion marker
     * mirrored into the worker store and are never reconstructed.
     */
    void hydrateCompletedDependencies(SectionStore workerStore, SectionStore completed) {
        if (workerStore == null || completed == null) {
            return;
        }
        int minSectionY = workerStore.getMinY() >> 4;
        int sectionCount = workerStore.getHeight() >> 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (long key : completed.generatedChunkKeys()) {
            workerStore.markChunkGenerated(key);
            if (lighting.isInitialized(key)) {
                continue;
            }

            int cx = (int) key;
            int cz = (int) (key >> 32);
            ensureCarvers(cx, cz);
            ChunkAccess raw = grid.get(key);
            if (!(raw instanceof ProtoChunk proto)) {
                throw new IllegalStateException(
                        "rehydrated FULL dependency missing ProtoChunk " + cx + "," + cz);
            }

            for (int slot = 0; slot < sectionCount; slot++) {
                long sectionKey = SectionPos.asLong(cx, minSectionY + slot, cz);
                int baseY = (minSectionY + slot) << 4;
                for (int i = 0; i < SectionStore.CELLS; i++) {
                    int x = i & 15;
                    int z = (i >> 4) & 15;
                    int y = (i >> 8) & 15;
                    var state = completed.readCell(sectionKey, i);
                    if (state == null) {
                        state = Blocks.AIR.defaultBlockState();
                    }
                    pos.set((cx << 4) + x, baseY + y, (cz << 4) + z);
                    if (proto.getBlockState(pos) != state) {
                        proto.setBlockState(pos, state, 0);
                    }
                }
            }

            // Save/reload rebuilds the structure starts and the fastutil
            // reference tables together. Model that whole lifecycle before
            // exposing this dependency to later structures-on FEATURES.
            if (structural != null) {
                HeadlessNoise.roundTripStructureData(structural, proto);
            }

            lighting.initialize(proto);
            proto.setPersistedStatus(ChunkStatus.FULL);
        }
    }

    /**
     * Freeze exact Vanilla biome-container wire bytes for touched chunks.
     *
     * <p>Do not reconstruct palettes from final 64 values: Vanilla's
     * PalettedContainer can retain palette/storage shape that is observable
     * on the wire. This method must run on the LiveGenerator owner thread.
     */
    public Map<Long, byte[]> snapshotBiomeWire(List<int[]> touched) {
        Map<Long, byte[]> out = new java.util.LinkedHashMap<>();
        int minSectionY = driver.height().getMinY() >> 4;
        for (int[] pair : touched) {
            if (pair == null || pair.length < 2) {
                throw new IllegalArgumentException("invalid touched chunk coordinate");
            }
            ChunkAccess chunk = grid.get(ChunkPos.pack(pair[0], pair[1]));
            if (chunk == null) {
                throw new IllegalStateException(
                        "biome snapshot missing generated chunk " + pair[0] + "," + pair[1]);
            }
            var sections = chunk.getSections();
            for (int slot = 0; slot < sections.length; slot++) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    sections[slot].getBiomes().write(buf);
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.getBytes(0, bytes);
                    out.put(SectionPos.asLong(
                            pair[0], minSectionY + slot, pair[1]), bytes);
                } finally {
                    buf.release();
                }
            }
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * Deep copies of the pending post-processing lists
     * ({@code ChunkAccess.getPostProcessing}) of every touched chunk, for the
     * live level to keep until the chunk is activated. Vanilla marks these
     * during generation ({@code WorldGenRegion.setBlock} and the noise, surface,
     * carver and structure paths) on the ProtoChunk itself; this generator's
     * grid ProtoChunks are those objects, so their lists are the marks.
     *
     * <p>A dependency rehydrated from saved cells ({@link
     * #hydrateCompletedDependencies}, status FULL) holds no marks of its own:
     * the saved chunk owns its lists, so it is skipped rather than published
     * as empty over them.
     */
    public Map<Long, ShortList[]> snapshotPostProcessing(List<int[]> touched) {
        java.util.LinkedHashMap<Long, ShortList[]> out = new java.util.LinkedHashMap<>();
        if (touched == null) {
            return out;
        }
        for (int[] pair : touched) {
            long key = ChunkPos.pack(pair[0], pair[1]);
            ChunkAccess raw = grid.get(key);
            if (!(raw instanceof ProtoChunk proto)
                    || proto.getPersistedStatus() == ChunkStatus.FULL) {
                continue;
            }
            ShortList[] lists = proto.getPostProcessing();
            ShortList[] copy = new ShortList[lists.length];
            for (int i = 0; i < lists.length; i++) {
                if (lists[i] != null && !lists[i].isEmpty()) {
                    copy[i] = new it.unimi.dsi.fastutil.shorts.ShortArrayList(lists[i]);
                }
            }
            out.put(key, copy);
        }
        return out;
    }

    /**
     * Advance newly radius-1-ready generated centers through Vanilla LIGHT and
     * freeze the exact ClientboundLightUpdatePacketData payload bytes.
     *
     * <p>LIGHT depends on INITIALIZE_LIGHT in radius 1. A center is therefore
     * eligible only after all nine generated-center markers exist in the same
     * SectionStore. Every generated center already ran INITIALIZE_LIGHT inside
     * ensureChunk, so this is the exact dependency boundary rather than an
     * eager approximation. The shared engine is then advanced in one batch,
     * matching WorldgenLightLifecycle's threaded-engine task boundary.
     */
    public Map<Long, byte[]> snapshotNewlyReadyLightWire(
            SectionStore store, List<int[]> touched) {
        if (store == null) {
            throw new IllegalArgumentException("light snapshot requires a store");
        }
        if (touched == null || touched.isEmpty()) {
            return Map.of();
        }

        java.util.LinkedHashMap<Long, ProtoChunk> ready = new java.util.LinkedHashMap<>();
        for (int[] pair : touched) {
            if (pair == null || pair.length < 2) {
                throw new IllegalArgumentException("invalid touched chunk coordinate");
            }
            int cx = pair[0];
            int cz = pair[1];
            long key = ChunkPos.pack(cx, cz);
            if (lighting.isLit(key) || !store.isChunkGenerated(key)) {
                continue;
            }

            boolean radiusReady = true;
            for (int dx = -1; dx <= 1 && radiusReady; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!store.isChunkGenerated(ChunkPos.pack(cx + dx, cz + dz))) {
                        radiusReady = false;
                        break;
                    }
                }
            }
            if (!radiusReady) {
                continue;
            }

            ChunkAccess raw = grid.get(key);
            if (!(raw instanceof ProtoChunk proto)) {
                throw new IllegalStateException(
                        "LIGHT-ready center missing ProtoChunk " + cx + "," + cz);
            }
            ready.put(key, proto);
        }

        if (ready.isEmpty()) {
            return Map.of();
        }

        lighting.lightAll(ready.values());

        // LIGHT propagation from a newly-lit chunk may mutate data owned by an
        // already-lit adjacent chunk. Re-freeze the entire lit radius-1 impact
        // set, not only the chunks that transitioned in this call. Minecraft
        // light has max level 15, so a source cannot cross two whole 16-block
        // chunk widths; radius 1 is the complete cross-chunk impact boundary.
        java.util.LinkedHashSet<Long> affected = new java.util.LinkedHashSet<>();
        for (long readyKey : ready.keySet()) {
            int rx = (int) readyKey;
            int rz = (int) (readyKey >> 32);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long neighbourKey = ChunkPos.pack(rx + dx, rz + dz);
                    if (lighting.isLit(neighbourKey)) {
                        affected.add(neighbourKey);
                    }
                }
            }
        }

        java.util.LinkedHashMap<Long, byte[]> out = new java.util.LinkedHashMap<>();
        for (long key : affected) {
            int cx = (int) key;
            int cz = (int) (key >> 32);
            // ChunkPos.pack stores x in the low 32 bits and z in the high 32 bits.
            ChunkPos pos = new ChunkPos(cx, cz);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ClientboundLightUpdatePacketData light =
                        new ClientboundLightUpdatePacketData(pos, lighting.engine(), null, null);
                light.write(buf);
                byte[] bytes = new byte[buf.readableBytes()];
                buf.getBytes(0, bytes);
                if (bytes.length == 0) {
                    throw new IllegalStateException(
                            "Vanilla light payload unexpectedly empty for " + cx + "," + cz);
                }
                out.put(key, bytes);
            } finally {
                buf.release();
            }
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * The engine that lit this generator's chunks, for the chunk-activation
     * pass's brightness reads. Callers on another thread read only its
     * visible storage, as vanilla's main thread reads the threaded engine's.
     */
    public LevelLightEngine activationLightEngine() {
        return lighting.engine();
    }

    /**
     * Apply a chunk-activation pass's writes to the light engine and return
     * the refreshed light payloads.
     *
     * <p>Each write, in commit order, goes into the grid ProtoChunk that owns
     * it through {@code ProtoChunk.setBlockState}, which at LIGHT status does
     * what {@code LevelChunk.setBlockState} does for light: update the
     * section's emptiness, the column's sky-light sources and queue
     * {@code checkBlock} when the light properties differ. The engine then
     * runs once, as the threaded engine would run the queued checks, and the
     * payload of every lit chunk within one chunk of a write is re-frozen.
     * Writes outside the grid (none are possible: the pass writes only inside
     * its lit 3x3) are an error.
     */
    public Map<Long, byte[]> applyActivationWrites(List<dev.kestrel.loop.PostProcess.Write> writes) {
        java.util.Set<Long> touchedChunks = mirrorActivationWrites(grid::get, writes);
        lighting.drainBatch();
        java.util.LinkedHashMap<Long, byte[]> out = new java.util.LinkedHashMap<>();
        for (long key : touchedChunks) {
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long neighbour = ChunkPos.pack(x + dx, z + dz);
                    if (!out.containsKey(neighbour)) {
                        byte[] wire = snapshotLightWire(x + dx, z + dz);
                        if (wire != null) {
                            out.put(neighbour, wire);
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * The light-queueing half of {@link #applyActivationWrites}, shared with
     * the post-processing gate: each write, in commit order, through
     * {@code ProtoChunk.setBlockState} on the chunk that owns it.
     *
     * @return the keys of the chunks written, in first-write order
     */
    static java.util.Set<Long> mirrorActivationWrites(
            java.util.function.LongFunction<ChunkAccess> chunks,
            List<dev.kestrel.loop.PostProcess.Write> writes) {
        java.util.LinkedHashSet<Long> touchedChunks = new java.util.LinkedHashSet<>();
        for (dev.kestrel.loop.PostProcess.Write w : writes) {
            long key = ChunkPos.pack(w.pos().getX() >> 4, w.pos().getZ() >> 4);
            if (!(chunks.apply(key) instanceof ProtoChunk proto)) {
                throw new IllegalStateException("activation write outside the generator grid at "
                        + w.pos().toShortString());
            }
            proto.setBlockState(w.pos(), w.newState(), dev.kestrel.loop.PostProcess.FLAGS);
            touchedChunks.add(key);
        }
        return touchedChunks;
    }

    /** Gate seam: the generator's own chunk for (cx, cz), or null. */
    ChunkAccess gridChunkForGate(int cx, int cz) {
        return grid.get(ChunkPos.pack(cx, cz));
    }

    /** Exact current Vanilla light packet payload for a center already at LIGHT. */
    public byte[] snapshotLightWire(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        if (!lighting.isLit(key)) {
            return null;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundLightUpdatePacketData light =
                    new ClientboundLightUpdatePacketData(
                            new ChunkPos(cx, cz), lighting.engine(), null, null);
            light.write(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /**
     * Package-private replay hook used only by exact Vanilla trace gates.
     * The input is a captured reload event, not a captured reference order.
     * If the dependency already exists, reproduce the serialization
     * round-trip immediately; otherwise remember the event until
     * ensureCarvers() has built STRUCTURE_REFERENCES for that chunk.
     */
    void replayChunkReloadForGate(int cx, int cz) {
        refusePrefetched(cx, cz, "structure reload replay");
        if (structural == null) {
            throw new IllegalStateException(
                    "structure reload replay requires structures-on LiveGenerator");
        }
        long key = ChunkPos.pack(cx, cz);
        ChunkAccess existing = structureStarts.get(key);
        if (existing != null
                && !existing.getPersistedStatus().isBefore(
                        net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_REFERENCES)) {
            HeadlessNoise.roundTripStructureData(structural, existing);
            return;
        }
        pendingReplayStructureReloads.merge(key, 1, Integer::sum);
    }

    /**
     * Package-private gate hook used only by exact Vanilla trace gates: the
     * light-storage half of a captured reload. Vanilla unloads the chunk
     * through {@code ChunkMap.scheduleUnload} (which clears its light storage,
     * including the light-only layer a neighbour's INITIALIZE_LIGHT registered
     * for a chunk that never ran a light stage of its own) and then reads the
     * serialized form back, which restores only the layers that form carried.
     * Without this every read in the reloaded chunk keeps returning the
     * registered 0 instead of the unregistered sky default 15, and a
     * light-dependent feature (MushroomBlock.canSurvive wants a value below 13)
     * flips a placement.
     * See {@link WorldgenLightLifecycle#reloadChunkData}.
     */
    void reloadChunkLightForGate(int cx, int cz, boolean formCarriesLight) {
        lighting.reloadChunkData(new ChunkPos(cx, cz), formCarriesLight);
    }

    /** Gate-only block-entity half of a captured ProtoChunk reload. */
    void reloadChunkBlockEntitiesForGate(int cx, int cz) {
        refusePrefetched(cx, cz, "block-entity reload replay");
        ChunkAccess chunk = grid.get(ChunkPos.pack(cx, cz));
        if (structural != null && chunk != null) {
            HeadlessNoise.roundTripBlockEntities(structural, chunk);
        }
    }

    /** Outcome of {@link #replayChunkReloadHeightmapsForGate}. */
    enum ReloadHeightmapReplay { ROUND_TRIP, PREFIX_NOOP, PENDING_CARVERS }

    /**
     * Package-private replay hook used only by exact Vanilla trace gates: the
     * heightmap half of a captured partial-chunk reload (see
     * {@link HeadlessNoise#roundTripHeightmaps}). Before CARVERS the persisted
     * set is the WG set, so the reload keeps every map that can go stale and
     * is an identity; this also covers Kestrel's eager CARVERS prefix being
     * ahead of a reload Vanilla captured at an earlier status. From CARVERS on
     * the reload drops the WG maps. A CARVERS reload of a chunk this generator
     * has not built yet is applied right after ensureCarvers() builds it; a
     * later captured status for an unbuilt chunk means the lifecycles
     * disagree and fails closed.
     */
    ReloadHeightmapReplay replayChunkReloadHeightmapsForGate(
            int cx, int cz, ChunkStatus captured) {
        refusePrefetched(cx, cz, "heightmap reload replay");
        if (captured.isBefore(ChunkStatus.CARVERS)) {
            return ReloadHeightmapReplay.PREFIX_NOOP;
        }
        long key = ChunkPos.pack(cx, cz);
        ChunkAccess chunk = grid.get(key);
        if (chunk != null) {
            if (!captured.heightmapsAfter().equals(
                    chunk.getPersistedStatus().heightmapsAfter())) {
                throw new IllegalStateException(
                        "CHUNK_RELOAD_STATUS_MISMATCH chunk=" + cx + "," + cz
                                + " captured=" + captured
                                + " actual=" + chunk.getPersistedStatus());
            }
            HeadlessNoise.roundTripHeightmaps(chunk);
            if (lighting.isInitialized(key)) {
                reloadedSinceLightInitialization.add(key);
            }
            return ReloadHeightmapReplay.ROUND_TRIP;
        }
        if (captured != ChunkStatus.CARVERS) {
            throw new IllegalStateException(
                    "CHUNK_RELOAD_STATUS_MISMATCH chunk=" + cx + "," + cz
                            + " captured=" + captured + " actual=unbuilt");
        }
        pendingReplayHeightmapReloads.add(key);
        return ReloadHeightmapReplay.PENDING_CARVERS;
    }

    /** Package-private gate hook; see {@link #deferLightInitializationForGate}. */
    void deferLightInitializationForGate() {
        deferLightInitializationForGate = true;
    }

    /** Package-private gate hook: whether this generator has lit (cx,cz). */
    boolean isLightInitializedForGate(int cx, int cz) {
        return lighting.isInitialized(ChunkPos.pack(cx, cz));
    }

    /** Outcome of {@link #initializeLightForGate}. */
    enum LightInitializationReplay { APPLIED, REPEAT_AFTER_RELOAD, OUTSIDE_GRID }

    /**
     * Package-private gate hook: apply one captured INITIALIZE_LIGHT
     * completion. OUTSIDE_GRID when the chunk is not in the CARVERS+ grid
     * (outside what this generator has built). Vanilla re-runs
     * INITIALIZE_LIGHT for a chunk it reloads after light initialization;
     * the storage is already registered here, so that repeat is a no-op, the
     * same as FeaturesTraceReplay's idempotent lighting.initialize. Any other
     * completion for a chunk before its own FEATURES, or for an already
     * initialized chunk, means the captured schedule and this generator
     * disagree, so fail closed.
     */
    LightInitializationReplay initializeLightForGate(int cx, int cz) {
        if (!deferLightInitializationForGate) {
            throw new IllegalStateException(
                    "captured light replay requires deferLightInitializationForGate()");
        }
        long key = ChunkPos.pack(cx, cz);
        ChunkAccess chunk = grid.get(key);
        if (chunk == null) {
            return LightInitializationReplay.OUTSIDE_GRID;
        }
        if (!(chunk instanceof ProtoChunk proto)
                || proto.getPersistedStatus().isBefore(ChunkStatus.FEATURES)) {
            throw new IllegalStateException(
                    "captured INITIALIZE_LIGHT before FEATURES for " + cx + "," + cz
                            + " status=" + chunk.getPersistedStatus());
        }
        if (lighting.initialize(proto)) {
            return LightInitializationReplay.APPLIED;
        }
        if (reloadedSinceLightInitialization.remove(key)) {
            return LightInitializationReplay.REPEAT_AFTER_RELOAD;
        }
        throw new IllegalStateException(
                "captured INITIALIZE_LIGHT for already-initialized " + cx + "," + cz
                        + " without a captured reload since its initialization"
                        + " (repeats need --replay-chunk-loads)");
    }

    /**
     * Test-only observation point at the real production boundary immediately
     * after the center's FEATURES pass and before INITIALIZE_LIGHT / leaf
     * post-processing / store publication. The map is the live CARVERS+
     * FEATURES grid used by this generator; observers must treat it read-only.
     */
    @FunctionalInterface
    interface FeaturesObserver {
        void afterFeatures(int cx, int cz, Map<Long, ChunkAccess> grid);
    }

    /**
     * Ensure chunk (cx, cz) has completed its own generation pass into
     * {@code store}. No-op (empty list, no work) if it already has.
     *
     * @return the {@code {x, z}} pairs of every chunk in the 3x3 window
     *         whose store contents this call wrote (possibly changed),
     *         for the caller to mark dirty; empty when this call was a
     *         no-op
     */
    public List<int[]> ensureChunk(SectionStore store, int cx, int cz) {
        return ensureChunk(store, cx, cz, null);
    }

    /**
     * Package-private exact-parity hook used only by worldgen gates. Production
     * callers use the public overload above, so this cannot change live output.
     */
    List<int[]> ensureChunk(
            SectionStore store, int cx, int cz, FeaturesObserver observer) {
        long centerKey = ChunkPos.pack(cx, cz);
        if (store.isChunkGenerated(centerKey)) {
            return List.of();
        }
        long t0 = System.nanoTime();
        int cachedBefore = grid.size();
        prefetchCenters(List.of(new int[]{cx, cz}), false);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ensureCarvers(cx + dx, cz + dz);
            }
        }
        long tCarvers = System.nanoTime();
        int freshCarvers = grid.size() - cachedBefore;
        // Structures-on FEATURES may resolve a referenced start up to eight
        // chunks away. The shared starts map contains those bare dependencies
        // as well as the exact promoted ProtoChunks in grid. Structures-off
        // keeps the smaller CARVERS-ready map used by the existing live path.
        Map<Long, ChunkAccess> featureGrid =
                structural == null ? grid : structureStarts;
        HeadlessNoise.RegionView view = new HeadlessNoise.RegionView(
                driver, new net.minecraft.world.level.ChunkPos(cx, cz),
                featureGrid, lighting.engine(),
                structural == null ? null : structural.templates());
        if (observer != null) {
            // Exact-parity observers must never accept production's
            // catch-and-continue feature fallback as a valid generation result.
            view.featureFailure = (key, failure) -> {
                throw new AssertionError(
                        "LIVE_FEATURE_FAILURE origin=" + cx + "," + cz
                                + " key=" + key,
                        failure);
            };
        }
        ChunkAccess center = grid.get(centerKey);
        if (structural == null) {
            HeadlessNoise.features(driver, center, view);
        } else {
            HeadlessNoise.featuresWithStructures(structural, center, view);
        }
        ProtoChunk protoCenter = (ProtoChunk) center;
        if (protoCenter.getPersistedStatus().isBefore(ChunkStatus.FEATURES)) {
            protoCenter.setPersistedStatus(ChunkStatus.FEATURES);
        }
        centersCount++;

        if (observer != null) {
            observer.afterFeatures(cx, cz, java.util.Collections.unmodifiableMap(grid));
        }

        // Vanilla 26.2 immediately advances the center to INITIALIZE_LIGHT
        // after FEATURES (radius 0 dependency). This shared registration
        // frontier is observable by later neighbouring FEATURES calls.
        if (!deferLightInitializationForGate) {
            lighting.initialize(protoCenter);
        }
        long tFeatures = System.nanoTime();
        // Do not run HeadlessNoise.settleLeaves here. LeavesBlock distance
        // updates are scheduled post-FULL transitions, not part of FEATURES.
        // Mutating the shared ProtoChunk grid here leaks postprocess state into
        // later neighbouring FEATURES passes and has been observed to diverge
        // from Vanilla's captured FEATURES lifecycle. Keep generation state at
        // the exact FEATURES boundary; post-FULL tick replay is a separate stage.

        List<int[]> touched = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int wx = cx + dx;
                int wz = cz + dz;
                ChunkAccess c = grid.get(ChunkPos.pack(wx, wz));
                if (c == null) {
                    continue;
                }
                HeadlessNoise.writeStoreCompact(driver, c, store, wx, wz, store == exclusiveStore);
                touched.add(new int[]{wx, wz});
            }
        }
        store.markChunkGenerated(centerKey);
        long tDone = System.nanoTime();
        // Throttled-by-nature: this only prints once per chunk ever
        // generated (grid cache dedupes repeats via ensureCarvers'
        // containsKey check / the isChunkGenerated guard above), so it is
        // safe to always log, not just on slow outliers. Useful to
        // correlate against ChunkSender's 40ms/tick generation budget and
        // the "231ms mean per fresh chunk" figure in ChunkSender's javadoc.
        System.out.println("[kestrel] ensureChunk chunk=" + cx + "," + cz
                + " freshCarvers=" + freshCarvers
                + " carversMs=" + (tCarvers - t0) / 1_000_000.0
                + " featuresMs=" + (tFeatures - tCarvers) / 1_000_000.0
                + " writeMs=" + (tDone - tFeatures) / 1_000_000.0
                + " totalMs=" + (tDone - t0) / 1_000_000.0
                + " gridSize=" + grid.size());
        return touched;
    }

    /**
     * NOISE + SURFACE + CARVERS for one chunk, cached: a no-op if already
     * run. SURFACE needs its own 3x3 biome neighbourhood already at BIOMES
     * status (see {@link HeadlessNoise#surfaceVanilla}), one ring wider than
     * the CARVERS-ready {@code grid} this method itself builds outward from
     * - {@link HeadlessNoise#prepareBiomeGrid} supplies that ring from
     * {@code grid} where already CARVERS-ready, else from {@link #noiseMargin}.
     */
    private void ensureCarvers(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        if (grid.containsKey(key)) {
            return;
        }
        ChunkAccess c;
        java.util.concurrent.Future<ChunkAccess> pending = prefetched.remove(key);
        if (pending != null) {
            try {
                c = pending.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for CARVERS " + cx + "," + cz, e);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new IllegalStateException("CARVERS failed at " + cx + "," + cz, cause);
            }
        } else {
            // For structures-on, generate the CENTER through real
            // STARTS->REFERENCES->BIOMES->NOISE first so fillFromNoise receives
            // the Vanilla Beardifier. The surrounding biome-only margin can stay
            // on the ordinary noise path: structure adaptation changes density
            // and blocks, not biome palettes.
            ChunkAccess structuralCenter = null;
            if (structural != null) {
                int pendingReloads = pendingReplayStructureReloads.getOrDefault(key, 0);
                structuralCenter = HeadlessNoise.noiseWithStructures(
                        structural,
                        cx,
                        cz,
                        structureStarts,
                        pendingReloads == 0 ? null : referenced -> {
                            for (int i = 0; i < pendingReloads; i++) {
                                HeadlessNoise.roundTripStructureData(
                                        structural, referenced);
                            }
                        },
                        startsSource);
                if (pendingReloads != 0) {
                    pendingReplayStructureReloads.remove(key);
                }
            }
            c = buildCarvers(cx, cz, structuralCenter,
                    (x, z) -> grid.get(ChunkPos.pack(x, z)));
        }
        if (pendingReplayHeightmapReloads.remove(key)) {
            HeadlessNoise.roundTripHeightmaps(c);
        }
        grid.put(key, c);
        biomeReady.put(key, c);
        // Promoted to CARVERS-ready: drop the NOISE-only copy so noiseMargin
        // only ever holds chunks that have not run SURFACE/CARVERS yet.
        noiseMargin.remove(new ChunkPos(cx, cz));
        carversCount++;
    }

    /**
     * SURFACE + CARVERS for one chunk (and, structures-off, its NOISE via the
     * margin). {@code structuralCenter} is the structures-on chunk after
     * NOISE, or null. {@code built} resolves already CARVERS-ready neighbours
     * for SURFACE's biome grid; any other neighbour comes from the biome-only
     * margin. Biomes are pure per chunk, so which source answers does not
     * change a value. Touches no owner-thread state, so it may run on the
     * carver pool.
     */
    private ChunkAccess buildCarvers(int cx, int cz, ChunkAccess structuralCenter,
            java.util.function.BiFunction<Integer, Integer, ChunkAccess> built) {
        Map<ChunkPos, ChunkAccess> biomeGrid =
                HeadlessNoise.prepareBiomeGrid(driver, cx, cz,
                        (x, z) -> {
                            if (structuralCenter != null && x == cx && z == cz) {
                                return structuralCenter;
                            }
                            return built.apply(x, z);
                        }, noiseMargin, structural != null);
        ChunkAccess c = biomeGrid.get(new ChunkPos(cx, cz));
        HeadlessNoise.surfaceVanilla(driver, c, biomeGrid);
        HeadlessNoise.carveVanilla(driver, c);
        // ProtoChunk.getNoiseBiome throws "Asking for biomes before we have
        // biomes" unless highest-generated status is BIOMES or later (javap
        // ProtoChunk.getNoiseBiome): the BiomeFilter in features() reads
        // biomes through LevelReader.getNoiseBiome -> chunk, and noise()
        // leaves the fresh ProtoChunk below that bar. The probes set CARVERS
        // explicitly; the live path must do the same.
        ((ProtoChunk) c).setPersistedStatus(ChunkStatus.CARVERS);
        // CARVERS is the last pass that reads the memoized NoiseChunk, and
        // this grid never evicts, so without this the density-function graph
        // of every chunk ever generated stays reachable for the process
        // lifetime (~57 KiB each). Generation scratch only - see
        // HeadlessNoise.releaseNoiseChunk for why this cannot change output.
        HeadlessNoise.releaseNoiseChunk(c);
        return c;
    }

    @Override
    public void close() {
        if (startsPool != null) {
            startsPool.shutdownNow();
            try {
                startsPool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (carverPool != null) {
            carverPool.shutdownNow();
            try {
                carverPool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (structural != null) {
            structural.close();
        }
    }

}
