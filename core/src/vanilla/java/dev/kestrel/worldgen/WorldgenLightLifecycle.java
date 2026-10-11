package dev.kestrel.worldgen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluids;

/**
 * Shared vanilla 26.2 light-storage lifecycle used by headless worldgen.
 *
 * <p>This deliberately implements only the generation stages Kestrel needs
 * before the full lighting engine lands:
 *
 * <ul>
 *   <li>FEATURES reads one shared LevelLightEngine.</li>
 *   <li>After a center completes FEATURES, INITIALIZE_LIGHT registers every
 *       non-empty section in that same shared engine.</li>
 *   <li>LIGHT propagation itself is not performed here; the later Kestrel
 *       lighting engine owns that stage.</li>
 * </ul>
 *
 * <p>The ordering matches ChunkPyramid.GENERATION_PYRAMID in Vanilla 26.2:
 * INITIALIZE_LIGHT depends only on the center's own FEATURES status, while
 * LIGHT additionally requires INITIALIZE_LIGHT in radius 1.
 */
final class WorldgenLightLifecycle {
    private final Map<Long, ChunkAccess> chunks;
    private final ReplayLightEngine engine;
    private final int minY;
    private final int height;
    private final Set<Long> initialized = new HashSet<>();
    private final Set<Long> prepared = new HashSet<>();
    private final Set<Long> lit = new HashSet<>();
    private long currentBatch = -1;

    WorldgenLightLifecycle(Map<Long, ChunkAccess> chunks, HeadlessNoise.Driver driver) {
        this.chunks = chunks;
        this.minY = driver.height().getMinY();
        this.height = driver.height().getHeight();

        BlockGetter world = new BlockGetter() {
            @Override
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
                ChunkAccess chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
                return chunk == null ? null : chunk.getBlockEntity(pos);
            }

            @Override
            public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
                ChunkAccess chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
                return chunk == null ? Blocks.BEDROCK.defaultBlockState() : chunk.getBlockState(pos);
            }

            @Override
            public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
                ChunkAccess chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
                return chunk == null ? Fluids.EMPTY.defaultFluidState() : chunk.getFluidState(pos);
            }

            @Override
            public int getHeight() {
                return height;
            }

            @Override
            public int getMinY() {
                return minY;
            }
        };

        LightChunkGetter getter = new LightChunkGetter() {
            @Override
            public LightChunk getChunkForLighting(int x, int z) {
                // Mirrors ServerChunkCache.getChunkForLighting, which resolves
                // through ChunkHolder.getChunkIfPresentUnchecked(
                // ChunkStatus.INITIALIZE_LIGHT.getParent()): a chunk still at
                // CARVERS is invisible to the light engine, so its sections stay
                // unregistered and its columns keep reading the sky default 15.
                // Handing the engine a CARVERS chunk instead lets its section
                // get a light-only layer that reads 0, and light-dependent
                // placement (MushroomBlock.canSurvive wants
                // getRawBrightness < 13) then flips a scatter cell.
                ChunkAccess chunk = chunk(x, z);
                if (chunk == null || !chunk.getPersistedStatus()
                        .isOrAfter(ChunkStatus.INITIALIZE_LIGHT.getParent())) {
                    return null;
                }
                return chunk;
            }

            @Override
            public BlockGetter getLevel() {
                return world;
            }
        };

        this.engine = new ReplayLightEngine(getter, true,
                driver.dimensionType().value().hasSkyLight());
        probe("engine-new");
    }

    /**
     * Diagnostic-only: report the watched section's sky-layer state next to a
     * lifecycle step, so a layer that appears as a side effect of that step is
     * attributable. Inert unless {@code -Dkestrel.lightReadDump} is set.
     */
    private void probe(String tag) {
        LightReadDiag.op(tag, engine, LightReadDiag.probeChunkX(),
                LightReadDiag.probeSectionY(), LightReadDiag.probeChunkZ());
    }

    LevelLightEngine engine() {
        return engine;
    }

    void replayExecutorCallbacks(boolean replay) {
        engine.replayCallbacks = replay;
    }

    int initializedCount() {
        return initialized.size();
    }

    /** Snapshot of centers this lifecycle has actually advanced through INITIALIZE_LIGHT. */
    Set<Long> initializedKeys() {
        return new HashSet<>(initialized);
    }

    boolean isInitialized(long chunkKey) {
        return initialized.contains(chunkKey);
    }

    int litCount() {
        return lit.size();
    }

    boolean isLit(long chunkKey) {
        return lit.contains(chunkKey);
    }

    /**
     * Execute Vanilla's INITIALIZE_LIGHT stage synchronously for one center.
     * Returns false when this chunk was already initialized.
     */
    boolean initialize(ProtoChunk chunk) {
        return initializeAll(List.of(chunk)) != 0;
    }

    /**
     * Batch variant matching ThreadedLevelLightEngine's PRE_UPDATE -> one
     * runLightUpdates -> POST_UPDATE ordering.
     *
     * @return number of chunks newly initialized
     */
    int initializeAll(Collection<? extends ProtoChunk> candidates) {
        List<ProtoChunk> todo = new ArrayList<>();
        for (ProtoChunk chunk : candidates) {
            long key = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
            if (initialized.add(key)) {
                todo.add(chunk);
            }
        }
        if (todo.isEmpty()) {
            return 0;
        }

        for (ProtoChunk chunk : todo) {
            probe("initAll-pre " + chunk.getPos().x() + "," + chunk.getPos().z());
            initializePreUpdate(chunk);
            probe("initAll-post " + chunk.getPos().x() + "," + chunk.getPos().z());
        }
        drain();
        probe("initAll-drained");
        for (ProtoChunk chunk : todo) {
            probe("initAll-disable " + chunk.getPos().x() + "," + chunk.getPos().z());
            engine.setLightEnabled(chunk.getPos(), false);
            engine.retainData(chunk.getPos(), false);
            probe("initAll-disabled " + chunk.getPos().x() + "," + chunk.getPos().z());
            if (chunk.getPersistedStatus().isBefore(ChunkStatus.INITIALIZE_LIGHT)) {
                chunk.setPersistedStatus(ChunkStatus.INITIALIZE_LIGHT);
            }
        }
        return todo.size();
    }

    boolean applyInitializePreUpdate(ProtoChunk chunk) {
        long key = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
        if (!initialized.add(key)) return false;
        initializePreUpdate(chunk);
        return true;
    }

    void prepareInitialize(ProtoChunk chunk) {
        prepared.add(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()));
        chunk.initializeLightSources();
        chunk.setLightEngine(engine);
        probeSource("fillFrom", chunk);
    }

    /**
     * Diagnostic-only: report one chunk's sky-light source state at every
     * stage of its light lifecycle. Inert unless {@code -Dkestrel.probe=dx,dz}.
     */
    static boolean probedChunk(int cx, int cz) {
        String probe = System.getProperty("kestrel.probe");
        if (probe == null) return false;
        String[] parts = probe.split(",");
        for (int i = 0; i + 1 < parts.length; i += 2) {
            if (cx == Integer.parseInt(parts[i].trim())
                    && cz == Integer.parseInt(parts[i + 1].trim())) {
                return true;
            }
        }
        return false;
    }

    static void probeSource(String tag, ChunkAccess chunk) {
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        if (!probedChunk(cx, cz)) return;
        System.out.println("PROBE " + tag
                + " chunk=" + cx + "," + cz
                + " status=" + chunk.getPersistedStatus()
                + " lightCorrect=" + chunk.isLightCorrect()
                + " lowest=" + chunk.getSkyLightSources().getLowestSourceY(10, 9)
                + " highest=" + chunk.getSkyLightSources().getHighestLowestSourceY());
    }

    boolean applyLightPreUpdate(ProtoChunk chunk) {
        long key = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
        if (!lit.add(key)) return false;
        chunk.setLightCorrect(false);
        probeSource("lightPre", chunk);
        engine.propagateLightSources(chunk.getPos());
        probeSource("lightPost-propagate", chunk);
        return true;
    }

    void drainBatch() {
        drain();
    }

    void drainBatch(long batchId) {
        this.currentBatch = batchId;
        drain();
        this.currentBatch = -1;
    }

    void checkBlock(net.minecraft.core.BlockPos pos) {
        engine.directCheckBlock(pos);
    }

    void updateSectionStatus(SectionPos pos, boolean empty) {
        engine.directUpdateSectionStatus(pos, empty);
    }

    // These are the light executor's POST_UPDATE actions. ChunkStep publishes
    // the persisted status in its future continuation, at a separate trace cut.
    void applyInitializePostUpdate(ProtoChunk chunk) {
        engine.setLightEnabled(chunk.getPos(), false);
        engine.retainData(chunk.getPos(), false);
        probeSource("initializePostUpdate", chunk);
    }

    void applyLightPostUpdate(ProtoChunk chunk) {
        chunk.setLightCorrect(true);
        probeSource("lightPostUpdate", chunk);
    }

    /**
     * Execute Vanilla's LIGHT stage synchronously for one center.
     */
    boolean light(ProtoChunk chunk) {
        return lightAll(List.of(chunk)) != 0;
    }

    /**
     * Batch LIGHT variant preserving the threaded engine's task boundary.
     *
     * @return number of chunks newly lit
     */
    int lightAll(Collection<? extends ProtoChunk> candidates) {
        initializeAll(candidates);

        List<ProtoChunk> todo = new ArrayList<>();
        for (ProtoChunk chunk : candidates) {
            long key = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
            if (lit.add(key)) {
                todo.add(chunk);
            }
        }
        if (todo.isEmpty()) {
            return 0;
        }

        for (ProtoChunk chunk : todo) {
            chunk.setLightCorrect(false);
            probe("lightAll-propagate " + chunk.getPos().x() + "," + chunk.getPos().z());
            engine.propagateLightSources(chunk.getPos());
            probe("lightAll-propagated " + chunk.getPos().x() + "," + chunk.getPos().z());
        }
        drain();
        probe("lightAll-drained");
        for (ProtoChunk chunk : todo) {
            chunk.setLightCorrect(true);
            if (chunk.getPersistedStatus().isBefore(ChunkStatus.LIGHT)) {
                chunk.setPersistedStatus(ChunkStatus.LIGHT);
            }
        }
        return todo.size();
    }

    private void initializePreUpdate(ProtoChunk chunk) {
        long key = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
        if (!prepared.remove(key)) {
            chunk.initializeLightSources();
            chunk.setLightEngine(engine);
        }

        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (!section.hasOnlyAir()) {
                int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
                probe("sectionStatus " + chunk.getPos().x() + "," + chunk.getPos().z()
                        + " sectionY=" + sectionY);
                engine.directUpdateSectionStatus(SectionPos.of(chunk.getPos(), sectionY), false);
                probe("sectionStatus-done " + chunk.getPos().x() + "," + chunk.getPos().z()
                        + " sectionY=" + sectionY);
            }
        }
    }

    /**
     * Mirror of {@code ThreadedLevelLightEngine.updateChunkStatus}, which
     * Vanilla calls from {@code ChunkMap.scheduleUnload} when a chunk is
     * unloaded. It drops every light section the chunk held, and because
     * {@code updateSectionStatus(section, true)} also decrements the neighbour
     * counts it is the only operation that removes the light-only layers a
     * neighbour's INITIALIZE_LIGHT registered for a chunk that never ran a
     * light stage of its own.
     *
     * <p>The chunk is also forgotten by the initialized/lit bookkeeping, so a
     * post-reload INITIALIZE_LIGHT or LIGHT completion from the trace is
     * executed again instead of being skipped as already done.
     */
    private void unloadChunkData(ChunkPos pos) {
        applyUnloadPreUpdate(pos);
        drain();
    }

    void applyUnloadPreUpdate(ChunkPos pos) {
        long key = ChunkPos.pack(pos.x(), pos.z());
        initialized.remove(key);
        lit.remove(key);

        engine.setLightEnabled(pos, false);
        engine.retainData(pos, false);
        for (int sectionY = engine.getMinLightSection();
                sectionY < engine.getMaxLightSection(); sectionY++) {
            engine.queueSectionData(LightLayer.BLOCK, SectionPos.of(pos, sectionY), null);
            engine.queueSectionData(LightLayer.SKY, SectionPos.of(pos, sectionY), null);
        }
        int minSectionY = minY >> 4;
        int maxSectionY = ((minY + height) >> 4) - 1;
        for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
            engine.directUpdateSectionStatus(SectionPos.of(pos, sectionY), true);
        }
    }

    /**
     * Reproduce a captured chunk reload: the unload half above, then the part
     * of the serialized form that comes back.
     *
     * <p>A capture that shows a chunk being read back from its serialized form
     * has seen it unloaded first, so {@link #unloadChunkData} is exactly what
     * happened to its light storage. {@code SerializableChunkData.read} then
     * queues back only the sections that carried light:
     * {@code SerializableChunkData.pack} writes a layer only when it is not
     * empty, so an all-zero layer is absent from the form and its section stays
     * unregistered after the reload, and an unregistered section reads the
     * layer's default value (15 for sky) rather than 0. Both halves matter for
     * a light-dependent feature ({@code MushroomBlock.canSurvive} wants a value
     * below 13): the layers a reload really drops are gone, and the layers the
     * read brings back are still there.
     *
     * @param formCarriesLight whether the serialized form was saved at LIGHT or
     *     later, the first status whose form carries light data. A form saved
     *     below it carries none at all, so nothing comes back and every layer
     *     stays dropped, including the replay's own layers for that chunk: they
     *     are values of a generation run Vanilla did not have in hand when it
     *     wrote the form it just read.
     */
    void reloadChunkData(ChunkPos pos, boolean formCarriesLight) {
        List<SectionPos> keptSections = new ArrayList<>();
        List<DataLayer> keptSky = new ArrayList<>();
        List<DataLayer> keptBlock = new ArrayList<>();
        for (int sectionY = engine.getMinLightSection();
                sectionY < engine.getMaxLightSection(); sectionY++) {
            SectionPos sectionPos = SectionPos.of(pos, sectionY);
            DataLayer sky = engine.getLayerListener(LightLayer.SKY)
                    .getDataLayerData(sectionPos);
            DataLayer block = engine.getLayerListener(LightLayer.BLOCK)
                    .getDataLayerData(sectionPos);
            boolean keepSky = formCarriesLight && sky != null && !sky.isEmpty();
            boolean keepBlock = formCarriesLight && block != null && !block.isEmpty();
            if (keepSky || keepBlock) {
                keptSections.add(sectionPos);
                keptSky.add(keepSky ? sky.copy() : null);
                keptBlock.add(keepBlock ? block.copy() : null);
            }
        }

        unloadChunkData(pos);

        boolean retained = false;
        for (int i = 0; i < keptSections.size(); i++) {
            if (!retained) {
                engine.retainData(pos, true);
                retained = true;
            }
            SectionPos sectionPos = keptSections.get(i);
            if (keptBlock.get(i) != null) {
                engine.queueSectionData(LightLayer.BLOCK, sectionPos, keptBlock.get(i));
            }
            if (keptSky.get(i) != null) {
                engine.queueSectionData(LightLayer.SKY, sectionPos, keptSky.get(i));
            }
        }
        if (retained) {
            drain();
        }
    }

    private ChunkAccess chunk(int x, int z) {
        return chunks.get(ChunkPos.pack(x, z));
    }

    private void drain() {
        int iteration = 0;
        do {
            CellProbe.dumpQueued("batch=" + currentBatch + " drain-pre#" + iteration, engine);
            CellProbe.dump("batch=" + currentBatch + " drain-pre#" + iteration, engine);
            probe("drain-pre#" + iteration);
            engine.runLightUpdates();
            probe("drain-post#" + iteration);
            CellProbe.dump("batch=" + currentBatch + " drain-post#" + iteration, engine);
            iteration++;
        } while (engine.hasLightWork());
        CellProbe.dump("batch=" + currentBatch + " drain-done", engine);
    }

    /**
     * Diagnostic-only: force a second, independent drain pass after the
     * lifecycle believes it has no more light work, to check whether a stale
     * value is only cleared by re-running {@code runLightUpdates()} again.
     * Inert unless {@code -Dkestrel.probeExtraDrain} is set.
     */
    void probeExtraDrain() {
        if (System.getProperty("kestrel.probeExtraDrain") == null) return;
        CellProbe.dump("extra-drain-pre", engine);
        engine.runLightUpdates();
        CellProbe.dump("extra-drain-post", engine);
    }

    /** ProtoChunk callbacks are queued by Vanilla; replay their captured executor calls. */
    private static final class ReplayLightEngine extends LevelLightEngine {
        private boolean replayCallbacks;

        ReplayLightEngine(LightChunkGetter getter, boolean block, boolean sky) {
            super(getter, block, sky);
        }

        @Override
        public void checkBlock(BlockPos pos) {
            if (!replayCallbacks) super.checkBlock(pos);
        }

        @Override
        public void updateSectionStatus(SectionPos pos, boolean empty) {
            if (!replayCallbacks) super.updateSectionStatus(pos, empty);
        }

        void directCheckBlock(BlockPos pos) {
            super.checkBlock(pos);
        }

        void directUpdateSectionStatus(SectionPos pos, boolean empty) {
            super.updateSectionStatus(pos, empty);
        }
    }
}
