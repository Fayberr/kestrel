package dev.kestrel.loop;

import dev.kestrel.blockentity.SleeperRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 1 single world layer: section-keyed dense storage, a chunk
 * abstraction over 16x16x16 sections, a block-entity storage map
 * (positions reserved, bodies still empty), and per-dimension scoping.
 *
 * <p>Facade rule (VISION.md constraint 1): public signatures take Kestrel
 * types ({@link KestrelPos}, {@link KestrelState}); vanilla
 * {@code BlockPos}/{@code BlockState} never cross the seam. Conversion
 * happens exactly once per call, inside this class. Mechanics internals
 * (vanilla {@code updateShape}/{@code getSignal} calls) use the unwrapped
 * vanilla types via package-visible accessors; that is the seam, not a
 * leak.
 *
 * <p>Per-dimension scoping: one {@code KestrelLevel} instance per
 * dimension, carrying a dimension key. Packed sleeper ids are scoped by
 * (dimension, pos), so two dimensions never share ticker identity even
 * for the same coordinates. This instance owns overworld-shape defaults
 * (minY/height passed by the caller); Nether/End arrive with Stage 2+
 * callers constructing further instances with their own keys.
 *
 * <p>Thread-safety: single tick-thread only. No synchronization.
 */
public final class KestrelLevel {

    /** Dimension key: which world this level instance scopes. */
    public enum Dimension {
        OVERWORLD,
        NETHER,
        END
    }

    /**
     * Block-entity record: position reserved, body still empty. Real
     * per-type bodies arrive with the block-entity owners; Stage 1 pins
     * the storage shape and the sleeper-id lifecycle.
     */
    public record BlockEntityRecord(KestrelPos pos, SleeperRegistry.Type type) {
    }

    private final Dimension dimension;
    private final SectionStore store;
    private final SleeperRegistry sleepers = new SleeperRegistry();
    private final Map<Long, BlockEntityRecord> blockEntities = new HashMap<>();
    /** Exact Vanilla PalettedContainerRO biome wire bytes by SectionPos key. */
    private final Map<Long, byte[]> biomeWireSections = new HashMap<>();
    /** Exact Vanilla ClientboundLightUpdatePacketData payload by ChunkPos key. */
    private final Map<Long, byte[]> lightWireChunks = new HashMap<>();
    /**
     * Pending chunk-activation post-processing, the {@code ChunkAccess.postProcessing}
     * stand-in: per {@code ChunkPos.pack} key, one packed-offset list per section
     * index ({@code ProtoChunk.packOffsetCoordinates}), null where the section has
     * none. Only chunks with at least one pending entry are kept. See
     * {@link PostProcess}.
     */
    private final Map<Long, it.unimi.dsi.fastutil.shorts.ShortList[]> postProcessing = new HashMap<>();

    public KestrelLevel(Dimension dimension, int minY, int height) {
        this(dimension, new SectionStore(minY, height));
    }

    /** Primary constructor: takes ownership of a live store (back-reference set). */
    public KestrelLevel(Dimension dimension, SectionStore store) {
        this.dimension = dimension;
        this.store = store;
        store.bindLevel(this);
    }

    public Dimension dimension() {
        return dimension;
    }

    /** Owning store (seam use: chunk views, loop mechanics). */
    public SectionStore store() {
        return store;
    }

    /** Sleeper registry (seam use: loop pass discipline). */
    public SleeperRegistry sleepers() {
        return sleepers;
    }

    // ---- facade world access (Kestrel types only) ----

    /** Read the state at a position (unset cells read as air). */
    public KestrelState getBlockState(KestrelPos pos) {
        return KestrelState.wrap(store.get(pos.toVanilla()));
    }

    /**
     * Write a state, returning the previous state (air when unset).
     * Shape updates are the loop's job, not the level's: this is storage.
     */
    public KestrelState setBlock(KestrelPos pos, KestrelState state) {
        return KestrelState.wrap(store.set(pos.toVanilla(), state.unwrap()));
    }

    /** True when the position was explicitly written. */
    public boolean isWritten(KestrelPos pos) {
        return store.isWritten(pos.toVanilla());
    }

    /** Chunk view over the 16x16 columns at the given chunk coords. */
    public KestrelChunk chunk(int chunkX, int chunkZ) {
        return new KestrelChunk(this, chunkX, chunkZ);
    }

    /** All written positions (snapshot list, facade typed). */
    public List<KestrelPos> writtenPositions() {
        List<KestrelPos> out = new ArrayList<>();
        for (net.minecraft.core.BlockPos pos : store.writtenPositions()) {
            out.add(KestrelPos.fromVanilla(pos));
        }
        return out;
    }

    public int sectionCount() {
        return store.sectionCount();
    }

    public int getMinY() {
        return store.getMinY();
    }

    public int getHeight() {
        return store.getHeight();
    }

    public void putBiomeWireSection(long sectionKey, byte[] wire) {
        if (wire == null || wire.length == 0) {
            throw new IllegalArgumentException("biome wire payload must be non-empty");
        }
        biomeWireSections.put(sectionKey, wire.clone());
    }

    public byte[] biomeWireSection(long sectionKey) {
        byte[] wire = biomeWireSections.get(sectionKey);
        return wire == null ? null : wire.clone();
    }

    public int biomeWireSectionCount() {
        return biomeWireSections.size();
    }

    public void putLightWireChunk(long chunkKey, byte[] wire) {
        if (wire == null || wire.length == 0) {
            throw new IllegalArgumentException("light wire payload must be non-empty");
        }
        lightWireChunks.put(chunkKey, wire.clone());
    }

    public byte[] lightWireChunk(long chunkKey) {
        byte[] wire = lightWireChunks.get(chunkKey);
        return wire == null ? null : wire.clone();
    }

    public int lightWireChunkCount() {
        return lightWireChunks.size();
    }

    /** Deep immutable copy for persistence snapshots. */
    public Map<Long, byte[]> biomeWireSectionsSnapshot() {
        return freezeWireMap(biomeWireSections);
    }

    /** Deep immutable copy for persistence snapshots. */
    public Map<Long, byte[]> lightWireChunksSnapshot() {
        return freezeWireMap(lightWireChunks);
    }

    /** Clean-reload boundary: metadata never merges across worlds. */
    public void clearGeneratedMetadata() {
        biomeWireSections.clear();
        lightWireChunks.clear();
        postProcessing.clear();
    }

    /**
     * Replace one chunk's pending post-processing with a deep copy of
     * {@code sections} (index = section index, null or empty = none). A chunk
     * with no entry left is dropped, which is also how the pass clears it.
     */
    public void putPostProcessing(long chunkKey, it.unimi.dsi.fastutil.shorts.ShortList[] sections) {
        int count = store.getHeight() >> 4;
        if (sections.length != count) {
            throw new IllegalArgumentException("post-processing needs " + count
                    + " section lists, got " + sections.length);
        }
        it.unimi.dsi.fastutil.shorts.ShortList[] copy = new it.unimi.dsi.fastutil.shorts.ShortList[count];
        boolean any = false;
        for (int i = 0; i < count; i++) {
            if (sections[i] != null && !sections[i].isEmpty()) {
                copy[i] = new it.unimi.dsi.fastutil.shorts.ShortArrayList(sections[i]);
                any = true;
            }
        }
        if (any) {
            postProcessing.put(chunkKey, copy);
        } else {
            postProcessing.remove(chunkKey);
        }
    }

    /** The live pending lists of one chunk (not a copy), or null when it has none. */
    public it.unimi.dsi.fastutil.shorts.ShortList[] postProcessing(long chunkKey) {
        return postProcessing.get(chunkKey);
    }

    /** Keys of every chunk with pending post-processing. */
    public java.util.Set<Long> postProcessingKeys() {
        return java.util.Set.copyOf(postProcessing.keySet());
    }

    /** Deep immutable copy for persistence snapshots, section lists as arrays. */
    public Map<Long, short[][]> postProcessingSnapshot() {
        Map<Long, short[][]> copy = new HashMap<>();
        for (Map.Entry<Long, it.unimi.dsi.fastutil.shorts.ShortList[]> entry : postProcessing.entrySet()) {
            it.unimi.dsi.fastutil.shorts.ShortList[] lists = entry.getValue();
            short[][] sections = new short[lists.length][];
            for (int i = 0; i < lists.length; i++) {
                sections[i] = lists[i] == null ? new short[0] : lists[i].toShortArray();
            }
            copy.put(entry.getKey(), sections);
        }
        return Map.copyOf(copy);
    }

    private static Map<Long, byte[]> freezeWireMap(Map<Long, byte[]> source) {
        Map<Long, byte[]> copy = new HashMap<>();
        for (Map.Entry<Long, byte[]> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Map.copyOf(copy);
    }

    // ---- seam accessors (package-visible: vanilla types legal here) ----

    net.minecraft.world.level.block.state.BlockState getVanilla(KestrelPos pos) {
        return store.get(pos.toVanilla());
    }

    net.minecraft.world.level.block.state.BlockState setVanilla(
            KestrelPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return store.set(pos.toVanilla(), state);
    }

    net.minecraft.world.level.block.state.BlockState getVanillaAt(
            net.minecraft.core.BlockPos pos) {
        return store.get(pos);
    }

    net.minecraft.world.level.block.state.BlockState setVanillaAt(
            net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return store.set(pos, state);
    }

    // ---- block-entity storage map (positions reserved, bodies empty) ----

    /**
     * Register a block-entity id with its sleep-set type, reserving the
     * position record. Bodies stay empty until the block-entity owners
     * land; the sleeper registry owns the tick lifecycle.
     */
    public void registerBlockEntity(KestrelPos pos, SleeperRegistry.Type type) {
        sleepers.register(pos.asLong(), type);
        blockEntities.put(pos.asLong(), new BlockEntityRecord(pos, type));
    }

    /** Reserved block-entity record at the position, or null. */
    public BlockEntityRecord blockEntityAt(KestrelPos pos) {
        return blockEntities.get(pos.asLong());
    }

    /** Wake hook fan-in through the sleeper registry. */
    public void wakeBlockEntity(KestrelPos pos, SleeperRegistry.WakeSignal signal) {
        sleepers.wake(pos.asLong(), signal);
    }

    /** True when the packed id currently sleeps. */
    public boolean isAsleep(KestrelPos pos) {
        return sleepers.isAsleep(pos.asLong());
    }

    /**
     * Stage 2 seam: dump the block-entity record map sorted by packed id.
     * Bodies are still empty in this stage, so the sleeper registry's
     * dump is the state of record; this dump pins the registration set.
     */
    public List<BlockEntityRecord> dumpBlockEntities() {
        List<BlockEntityRecord> out = new ArrayList<>(blockEntities.values());
        out.sort((a, b) -> Long.compare(a.pos().asLong(), b.pos().asLong()));
        return out;
    }

    /**
     * Stage 2 seam: restore one block-entity record: reserve the position
     * and re-register the sleeper with its saved asleep flag.
     */
    public void restoreBlockEntity(KestrelPos pos, SleeperRegistry.Type type, boolean asleep) {
        blockEntities.put(pos.asLong(), new BlockEntityRecord(pos, type));
        sleepers.restoreSleeper(pos.asLong(), type, asleep);
    }

    /** Stage 2 seam: drop every block-entity record plus sleeper state. */
    public void clearBlockEntities() {
        blockEntities.clear();
        sleepers.clearAll();
    }
}
