package dev.kestrel.persist;

import dev.kestrel.blockentity.SleeperRegistry;
import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.KestrelPos;
import dev.kestrel.loop.TickLoop;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 2 overworld persistence: snapshot to Anvil region files plus a
 * Kestrel sidecar per region, and back.
 *
 * <p>Folder layout mirrors vanilla's overworld
 * ({@code ChunkMap.java:173-175}: dimension path + {@code region/}):
 * {@code <world>/region/r.<rx>.<rz>.mca} plus
 * {@code r.<rx>.<rz>.mca.kex} sidecars. Only the OVERWORLD dimension is
 * accepted: Nether/End arrive with their own callers and folders.
 *
 * <p>Save covers every chunk column touched by the snapshot (any live
 * section) plus every column holding block entities or queue entries.
 * Restore is a clean replace into an empty loop: storage, queue, block
 * entities, and clock are cleared first, so a reload never merges with
 * leftover state.
 *
 * <p>Sidecar authority: after chunk NBT decodes, the sidecar's written
 * bitsets overwrite the NBT-derived flags (chunk NBT has no written
 * field); the sidecar's sleeper list is the ONLY source of block-entity
 * registration (chunk stubs are vanilla-compat echoes); the sidecar's
 * queue list REPLACES the NBT-decoded queue (it also holds OTHER-kind
 * entries the chunk filter dropped). When a region has chunk data but
 * no sidecar, the load fails loudly: half a world is worse than none.
 * Sidecar-less roots (vanilla corpus worlds) go through
 * {@link #importWorld} instead, which reads chunk NBT only.
 *
 * <p>Thread-safety: save splits in two phases. {@link #save} captures
 * the snapshot on the calling (tick) thread, then serializes and writes
 * on the caller's thread; use {@link AsyncSaver} for the background
 * variant. Restore runs fully on the tick thread of the target loop.
 */
public final class WorldStore {

    private WorldStore() {
    }

    /** Region folder for an overworld world root. */
    public static Path regionDir(Path worldRoot) {
        return worldRoot.resolve("region");
    }

    /**
     * Save the live loop to the world root: capture on this thread, then
     * write every touched region file plus its sidecar. Returns the
     * snapshot hash so callers can compare pre/post without re-walking.
     */
    public static long save(TickLoop loop, Path worldRoot) throws IOException {
        requireOverworld(loop);
        WorldSnapshot snap = WorldSnapshot.capture(loop);
        writeSnapshot(snap, worldRoot);
        return snap.hash();
    }

    /** Saver-thread half: serialize a captured snapshot to the world root. */
    public static void writeSnapshot(WorldSnapshot snap, Path worldRoot) throws IOException {
        if (snap.dimension != KestrelLevel.Dimension.OVERWORLD) {
            throw new IOException("only OVERWORLD persists in Stage 2, got " + snap.dimension);
        }
        Path dir = regionDir(worldRoot);
        Files.createDirectories(dir);
        Map<Long, List<ChunkCodec.DecodedChunk>> byRegion = new HashMap<>();
        Set<ChunkPos> chunks = touchedChunks(snap);
        Map<ChunkPos, List<WorldSnapshot.SectionData>> sectionsByChunk = new HashMap<>();
        for (WorldSnapshot.SectionData s : snap.sections) {
            ChunkPos cp = new ChunkPos(SectionPos.x(s.sectionKey()), SectionPos.z(s.sectionKey()));
            sectionsByChunk.computeIfAbsent(cp, k -> new ArrayList<>()).add(s);
        }
        for (ChunkPos cp : chunks) {
            Nbt.Tag.TCompound tag = ChunkCodec.encodeChunk(snap, cp.x(), cp.z());
            Path regionFile = dir.resolve("r." + (cp.x() >> 5) + "." + (cp.z() >> 5) + ".mca");
            try (RegionFile region = RegionFile.open(regionFile)) {
                region.writeChunk(cp.x() & 31, cp.z() & 31, tag);
            }
        }
        Set<Long> regions = new HashSet<>();
        for (ChunkPos cp : chunks) {
            regions.add((((long) (cp.x() >> 5)) << 32) | ((cp.z() >> 5) & 0xFFFFFFFFL));
        }
        for (long packed : regions) {
            int rx = (int) (packed >> 32);
            int rz = (int) packed;
            Path regionFile = dir.resolve("r." + rx + "." + rz + ".mca");
            Files.write(Sidecar.pathFor(regionFile), Sidecar.encode(snap, rx, rz));
        }
    }

    /**
     * Restore a world root into an empty loop: read every region file
     * plus its sidecar, rebuild the snapshot, restore it. The target
     * loop's level must be OVERWORLD with matching minY/height.
     */
    public static void load(TickLoop loop, Path worldRoot) throws IOException {
        requireOverworld(loop);
        Path dir = regionDir(worldRoot);
        if (!Files.isDirectory(dir)) {
            throw new IOException("no region dir: " + dir);
        }
        List<ChunkCodec.DecodedChunk> decoded = new ArrayList<>();
        Map<Long, boolean[]> writtenBySection = new HashMap<>();
        Map<Long, boolean[]> editedBySection = new HashMap<>();
        List<WorldSnapshot.BlockEntityData> bes = new ArrayList<>();
        List<WorldSnapshot.QueuedTickData> queue = new ArrayList<>();
        List<WorldSnapshot.QueuedFluidTickData> fluidQueue = new ArrayList<>();
        Set<Long> generatedChunks = new HashSet<>();
        Map<Long, byte[]> biomeWireSections = new HashMap<>();
        Map<Long, byte[]> lightWireChunks = new HashMap<>();
        int minY = loop.level().getMinY();
        int height = loop.level().getHeight();
        long gameTime = 0;
        boolean sawGameTime = false;
        boolean anyRegion = false;
        try (var stream = Files.list(dir)) {
            List<Path> regions = stream
                    .filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .sorted()
                    .toList();
            for (Path regionFile : regions) {
                Path sidePath = Sidecar.pathFor(regionFile);
                if (!Files.isRegularFile(sidePath)) {
                    throw new IOException("region without sidecar: " + regionFile.getFileName());
                }
                Sidecar.Decoded side = Sidecar.decode(sidePath);
                assertGeometry(side, loop, regionFile);
                if (!sawGameTime) {
                    gameTime = side.gameTime();
                    sawGameTime = true;
                } else if (gameTime != side.gameTime()) {
                    throw new IOException("sidecar gameTime mismatch in " + regionFile.getFileName());
                }
                for (Sidecar.SectionBits sb : side.sections()) {
                    writtenBySection.put(sb.sectionKey(), sb.written());
                    editedBySection.put(sb.sectionKey(), sb.edited());
                }
                bes.addAll(side.blockEntities());
                queue.addAll(side.queue());
                fluidQueue.addAll(side.fluidQueue());
                generatedChunks.addAll(side.generatedChunks());
                mergeWireMetadata(
                        biomeWireSections, side.biomeWireSections(), "biome", regionFile);
                mergeWireMetadata(
                        lightWireChunks, side.lightWireChunks(), "light", regionFile);
                try (RegionFile region = RegionFile.open(regionFile)) {
                    for (int lx = 0; lx < 32; lx++) {
                        for (int lz = 0; lz < 32; lz++) {
                            if (!region.hasChunk(lx, lz)) {
                                continue;
                            }
                            anyRegion = true;
                            Nbt.Tag.TCompound tag = region.readChunk(lx, lz);
                            if (tag == null) {
                                continue;
                            }
                            decoded.add(ChunkCodec.decodeChunk(tag, minY, height, gameTime));
                        }
                    }
                }
            }
        }
        if (!anyRegion) {
            throw new IOException("no chunk data in " + dir);
        }
        int minSectionY = minY >> 4;
        List<WorldSnapshot.SectionData> sections = new ArrayList<>();
        for (ChunkCodec.DecodedChunk c : decoded) {
            for (ChunkCodec.SlotCells slot : c.sections()) {
                long key = ChunkCodec.storeKey(c.chunkX(), c.chunkZ(), minSectionY, slot.slot());
                boolean[] written = writtenBySection.get(key);
                if (written == null) {
                    throw new IOException("chunk section without sidecar bits: chunk "
                            + c.chunkX() + "," + c.chunkZ() + " slot " + slot.slot());
                }
                sections.add(new WorldSnapshot.SectionData(key, slot.cells(), written,
                        editedBySection.get(key)));
            }
        }
        // Sections the sidecar knows but no chunk entry covered (sparse
        // worlds): the chunk writer is dense so this should not happen;
        // fail loudly rather than dropping storage.
        for (Map.Entry<Long, boolean[]> e : writtenBySection.entrySet()) {
            boolean found = false;
            for (WorldSnapshot.SectionData s : sections) {
                if (s.sectionKey() == e.getKey()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IOException("sidecar section without chunk data: key " + e.getKey());
            }
        }
        WorldSnapshot snap = WorldSnapshot.fromDecoded(minY, height, gameTime,
                KestrelLevel.Dimension.OVERWORLD, sections, dedupeBes(bes), queue,
                fluidQueue, generatedChunks, biomeWireSections, lightWireChunks,
                postProcessingOf(decoded));
        snap.restoreInto(loop);
    }

    /**
     * Import a world root that may have no Kestrel sidecars (a vanilla
     * corpus world) into an empty loop. Separate from {@link #load}, which
     * keeps failing loudly on a missing sidecar.
     *
     * <p>Only chunk NBT is read; any sidecar present is ignored. Every
     * decoded section stays in immutable compact storage, where non-air
     * cells define the written set, with no block entities, an empty queue (chunk
     * {@code block_ticks} are not rehydrated), and gameTime 0. Import and
     * {@link #load} expose identical block cells, but their snapshot hashes
     * may differ because import intentionally reconstructs written state from
     * non-air cells instead of reading Kestrel sidecars.
     */
    public static void importWorld(TickLoop loop, Path worldRoot) throws IOException {
        importWorld(loop, worldRoot, null, null);
    }

    /** Chunk filter for {@link #importWorld}: true keeps the chunk. */
    @FunctionalInterface
    public interface ChunkFilter {
        boolean keep(int chunkX, int chunkZ);
    }

    /**
     * Square filter centered on a chunk: {@code (2 * half + 1)^2} chunks.
     * G2 step 2 imports W1 (half 10, 441) and W2 (half 15, 961) from one
     * larger corpus (g2-memory-design.md 1.3).
     */
    public static ChunkFilter square(int centerX, int centerZ, int half) {
        return (x, z) -> Math.abs(x - centerX) <= half && Math.abs(z - centerZ) <= half;
    }

    /**
     * {@link #importWorld(TickLoop, Path)} with an optional chunk filter
     * (null keeps every chunk) and an optional census (null skips
     * counting). Filtered-out chunks are still read far enough to learn
     * their position, then dropped before any section decodes.
     */
    public static void importWorld(TickLoop loop, Path worldRoot, ChunkFilter filter,
            ImportCensus census) throws IOException {
        requireOverworld(loop);
        Path dir = regionDir(worldRoot);
        if (!Files.isDirectory(dir)) {
            throw new IOException("no region dir: " + dir);
        }
        int minY = loop.level().getMinY();
        int height = loop.level().getHeight();
        int minSectionY = minY >> 4;
        long gameTime = 0;
        List<CompactImport> sections = new ArrayList<>();
        Map<Long, short[][]> postProcessing = new HashMap<>();
        boolean anyRegion = false;
        int kept = 0;
        try (var stream = Files.list(dir)) {
            List<Path> regions = stream
                    .filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .sorted()
                    .toList();
            for (Path regionFile : regions) {
                if (census != null) {
                    census.regions++;
                }
                try (RegionFile region = RegionFile.open(regionFile)) {
                    for (int lx = 0; lx < 32; lx++) {
                        for (int lz = 0; lz < 32; lz++) {
                            if (!region.hasChunk(lx, lz)) {
                                continue;
                            }
                            anyRegion = true;
                            Nbt.Tag.TCompound tag = region.readChunk(lx, lz);
                            if (tag == null) {
                                continue;
                            }
                            if (filter != null && !filter.keep(Nbt.getInt(tag, "xPos"),
                                    Nbt.getInt(tag, "zPos"))) {
                                if (census != null) {
                                    census.chunksSkipped++;
                                }
                                continue;
                            }
                            kept++;
                            if (census != null) {
                                census.chunks++;
                                if (!(Nbt.getOrNull(tag, "Status") instanceof Nbt.Tag.TString st)
                                        || !st.value().equals("minecraft:full")) {
                                    census.chunksNotFull++;
                                }
                            }
                            ChunkCodec.CompactChunk c =
                                    ChunkCodec.decodeCompactChunk(tag, minY, height, census);
                            for (ChunkCodec.SlotCompact slot : c.sections()) {
                                long key = ChunkCodec.storeKey(c.chunkX(), c.chunkZ(),
                                        minSectionY, slot.slot());
                                sections.add(new CompactImport(key, slot.section()));
                            }
                            if (c.postProcessing() != null) {
                                postProcessing.put(ChunkPos.pack(c.chunkX(), c.chunkZ()),
                                        c.postProcessing());
                            }
                        }
                    }
                }
            }
        }
        if (!anyRegion) {
            throw new IOException("no chunk data in " + dir);
        }
        if (kept == 0) {
            throw new IOException("import filter kept no chunks in " + dir);
        }
        loop.level().store().clearAll();
        loop.clearQueue();
        loop.level().clearBlockEntities();
        loop.level().clearGeneratedMetadata();
        loop.setGameTime(gameTime);
        for (CompactImport section : sections) {
            SectionCodec.CompactSection compact = section.section();
            // Sealed: an imported world's blocks are somebody's build, not
            // this generator's output, so no later generation pass may
            // rewrite them (C1). Imports record no completed-chunk keys, so
            // without the seal a neighbouring chunk's FEATURES pass would
            // republish generated terrain straight over the import.
            loop.level().store().putCompact(section.key(), compact.palette(),
                    compact.data(), compact.bits(), true);
        }
        // A vanilla edge chunk keeps its pending post-processing on disc
        // (it never became ticking); carry it so it still runs on activation.
        for (Map.Entry<Long, short[][]> entry : postProcessing.entrySet()) {
            short[][] offsets = entry.getValue();
            it.unimi.dsi.fastutil.shorts.ShortList[] lists =
                    new it.unimi.dsi.fastutil.shorts.ShortList[offsets.length];
            for (int i = 0; i < offsets.length; i++) {
                lists[i] = offsets[i].length == 0 ? null
                        : new it.unimi.dsi.fastutil.shorts.ShortArrayList(offsets[i]);
            }
            loop.level().putPostProcessing(entry.getKey(), lists);
        }
        if (census != null) {
            census.sectionsMaterialized = loop.level().store().sectionCount();
        }
    }

    /** Read back a world root's snapshot hash without touching the loop. */
    public static long hashStored(Path worldRoot, int minY, int height) throws IOException {
        Path dir = regionDir(worldRoot);
        List<ChunkCodec.DecodedChunk> decoded = new ArrayList<>();
        Map<Long, boolean[]> writtenBySection = new HashMap<>();
        Map<Long, boolean[]> editedBySection = new HashMap<>();
        List<WorldSnapshot.BlockEntityData> bes = new ArrayList<>();
        List<WorldSnapshot.QueuedTickData> queue = new ArrayList<>();
        List<WorldSnapshot.QueuedFluidTickData> fluidQueue = new ArrayList<>();
        Set<Long> generatedChunks = new HashSet<>();
        Map<Long, byte[]> biomeWireSections = new HashMap<>();
        Map<Long, byte[]> lightWireChunks = new HashMap<>();
        long gameTime = 0;
        boolean sawGameTime = false;
        try (var stream = Files.list(dir)) {
            List<Path> regions = stream
                    .filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .sorted()
                    .toList();
            for (Path regionFile : regions) {
                Sidecar.Decoded side = Sidecar.decode(Sidecar.pathFor(regionFile));
                if (!sawGameTime) {
                    gameTime = side.gameTime();
                    sawGameTime = true;
                }
                for (Sidecar.SectionBits sb : side.sections()) {
                    writtenBySection.put(sb.sectionKey(), sb.written());
                    editedBySection.put(sb.sectionKey(), sb.edited());
                }
                bes.addAll(side.blockEntities());
                queue.addAll(side.queue());
                fluidQueue.addAll(side.fluidQueue());
                generatedChunks.addAll(side.generatedChunks());
                mergeWireMetadata(
                        biomeWireSections, side.biomeWireSections(), "biome", regionFile);
                mergeWireMetadata(
                        lightWireChunks, side.lightWireChunks(), "light", regionFile);
                try (RegionFile region = RegionFile.open(regionFile)) {
                    for (int lx = 0; lx < 32; lx++) {
                        for (int lz = 0; lz < 32; lz++) {
                            if (!region.hasChunk(lx, lz)) {
                                continue;
                            }
                            Nbt.Tag.TCompound tag = region.readChunk(lx, lz);
                            if (tag != null) {
                                decoded.add(ChunkCodec.decodeChunk(tag, minY, height, gameTime));
                            }
                        }
                    }
                }
            }
        }
        int minSectionY = minY >> 4;
        List<WorldSnapshot.SectionData> sections = new ArrayList<>();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (ChunkCodec.DecodedChunk c : decoded) {
            for (ChunkCodec.SlotCells slot : c.sections()) {
                long key = ChunkCodec.storeKey(c.chunkX(), c.chunkZ(), minSectionY, slot.slot());
                boolean[] written = writtenBySection.get(key);
                if (written == null) {
                    throw new IOException("chunk section without sidecar bits");
                }
                sections.add(new WorldSnapshot.SectionData(key, slot.cells(), written,
                        editedBySection.get(key)));
            }
        }
        return WorldSnapshot.fromDecoded(minY, height, gameTime,
                KestrelLevel.Dimension.OVERWORLD, sections, dedupeBes(bes), queue,
                fluidQueue, generatedChunks, biomeWireSections, lightWireChunks,
                postProcessingOf(decoded)).hash();
    }

    /** Pending post-processing of every decoded chunk that has some, by ChunkPos key. */
    private static Map<Long, short[][]> postProcessingOf(List<ChunkCodec.DecodedChunk> decoded) {
        Map<Long, short[][]> out = new HashMap<>();
        for (ChunkCodec.DecodedChunk c : decoded) {
            if (c.postProcessing() != null) {
                out.put(ChunkPos.pack(c.chunkX(), c.chunkZ()), c.postProcessing());
            }
        }
        return out;
    }

    private static Set<ChunkPos> touchedChunks(WorldSnapshot snap) {
        Set<ChunkPos> out = new HashSet<>();
        for (WorldSnapshot.SectionData s : snap.sections) {
            out.add(new ChunkPos(SectionPos.x(s.sectionKey()), SectionPos.z(s.sectionKey())));
        }
        for (WorldSnapshot.BlockEntityData b : snap.blockEntities) {
            out.add(new ChunkPos(b.x() >> 4, b.z() >> 4));
        }
        for (WorldSnapshot.QueuedTickData q : snap.queue) {
            out.add(new ChunkPos(q.x() >> 4, q.z() >> 4));
        }
        for (WorldSnapshot.QueuedFluidTickData q : snap.fluidQueue) {
            out.add(new ChunkPos(q.x() >> 4, q.z() >> 4));
        }
        for (long key : snap.generatedChunks) {
            out.add(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)));
        }
        for (long key : snap.biomeWireSections.keySet()) {
            out.add(new ChunkPos(SectionPos.x(key), SectionPos.z(key)));
        }
        for (long key : snap.lightWireChunks.keySet()) {
            out.add(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)));
        }
        for (long key : snap.postProcessing.keySet()) {
            out.add(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)));
        }
        return out;
    }

    private static void mergeWireMetadata(
            Map<Long, byte[]> into, Map<Long, byte[]> from, String what, Path regionFile)
            throws IOException {
        for (Map.Entry<Long, byte[]> entry : from.entrySet()) {
            if (into.putIfAbsent(entry.getKey(), entry.getValue().clone()) != null) {
                throw new IOException(
                        "duplicate " + what + " metadata key " + entry.getKey()
                                + " across sidecars at " + regionFile.getFileName());
            }
        }
    }

    private record CompactImport(long key, SectionCodec.CompactSection section) {
    }

    private static void requireOverworld(TickLoop loop) {
        if (loop.level().dimension() != KestrelLevel.Dimension.OVERWORLD) {
            throw new IllegalArgumentException(
                    "only OVERWORLD persists in Stage 2, got " + loop.level().dimension());
        }
    }

    private static void assertGeometry(Sidecar.Decoded side, TickLoop loop, Path regionFile)
            throws IOException {
        if (side.minY() != loop.level().getMinY() || side.height() != loop.level().getHeight()
                || side.dimensionOrdinal() != KestrelLevel.Dimension.OVERWORLD.ordinal()) {
            throw new IOException("sidecar geometry mismatch in " + regionFile.getFileName()
                    + ": minY=" + side.minY() + " height=" + side.height()
                    + " dim=" + side.dimensionOrdinal());
        }
    }

    private static List<WorldSnapshot.BlockEntityData> dedupeBes(
            List<WorldSnapshot.BlockEntityData> bes) {
        Map<Long, WorldSnapshot.BlockEntityData> byId = new HashMap<>();
        for (WorldSnapshot.BlockEntityData b : bes) {
            byId.put(new KestrelPos(b.x(), b.y(), b.z()).asLong(), b);
        }
        return new ArrayList<>(byId.values());
    }
}
