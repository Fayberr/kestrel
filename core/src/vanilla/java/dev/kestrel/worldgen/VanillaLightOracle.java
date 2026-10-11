package dev.kestrel.worldgen;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluids;

/**
 * Diagnostic oracle: what vanilla's own light engine computes over a block
 * snapshot, with no Kestrel replay in the path.
 *
 * <p>Every chunk in the window is rebuilt from the trace's last recorded
 * snapshot of it (vanilla's own post-stage block state), handed to a fresh
 * {@link LevelLightEngine} taken from the vanilla jar on the classpath, and
 * lit the way {@code ThreadedLevelLightEngine} does it: INITIALIZE_LIGHT for
 * every chunk (section status, then light disabled and data not retained),
 * then LIGHT for every chunk ({@code propagateLightSources}), draining after
 * each stage. The engine is not subclassed or modified.
 *
 * <p>For each requested cell and its six neighbours it prints the block
 * state, the engine's block and sky value, and the capture's final value
 * from {@code expected/<x>_<z>_<Layer>Light-expected.bin} when present.
 * Only cells at least one chunk inside the window have a complete
 * neighbourhood; pick the window accordingly.
 *
 * <p>Args: traceDir expectedDir minCx minCz maxCx maxCz x:y:z[,x:y:z...]
 * [cx:cz[,cx:cz...]]. The optional last argument compares every cell of the
 * listed chunks against the capture and prints each disagreement.
 */
public final class VanillaLightOracle {

    private VanillaLightOracle() {
    }

    public static void main(String[] args) throws Exception {
        Path trace = Path.of(args[0]);
        Path expected = Path.of(args[1]);
        int minCx = Integer.parseInt(args[2]);
        int minCz = Integer.parseInt(args[3]);
        int maxCx = Integer.parseInt(args[4]);
        int maxCz = Integer.parseInt(args[5]);
        List<BlockPos> cells = new ArrayList<>();
        for (String part : args[6].split(",")) {
            String[] t = part.split(":");
            cells.add(new BlockPos(Integer.parseInt(t[0]), Integer.parseInt(t[1]),
                    Integer.parseInt(t[2])));
        }

        // Registries and the overworld height only; the seed is irrelevant.
        HeadlessNoise.Driver driver = HeadlessNoise.load(0L);
        Map<Long, String> last = lastSnapshots(trace, minCx, minCz, maxCx, maxCz);

        Map<Long, ProtoChunk> chunks = new LinkedHashMap<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                String hash = last.get(ChunkPos.pack(cx, cz));
                if (hash == null) {
                    throw new IOException("no snapshot for chunk " + cx + "," + cz);
                }
                chunks.put(ChunkPos.pack(cx, cz), build(driver, trace, cx, cz, hash));
                System.out.println("ORACLE chunk " + cx + "," + cz + " snapshot "
                        + hash.substring(0, 8));
            }
        }

        LevelLightEngine engine = new LevelLightEngine(getter(driver, chunks), true,
                driver.dimensionType().value().hasSkyLight());

        // INITIALIZE_LIGHT, as ThreadedLevelLightEngine.initializeLight(chunk, false).
        for (ProtoChunk chunk : chunks.values()) {
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                if (!sections[i].hasOnlyAir()) {
                    engine.updateSectionStatus(SectionPos.of(chunk.getPos(),
                            chunk.getSectionYFromSectionIndex(i)), false);
                }
            }
        }
        drain(engine);
        for (ProtoChunk chunk : chunks.values()) {
            engine.setLightEnabled(chunk.getPos(), false);
            engine.retainData(chunk.getPos(), false);
        }

        // LIGHT, as ThreadedLevelLightEngine.lightChunk(chunk, false).
        for (ProtoChunk chunk : chunks.values()) {
            engine.propagateLightSources(chunk.getPos());
        }
        drain(engine);

        for (BlockPos cell : cells) {
            report("CELL", engine, chunks, expected, cell);
            for (Direction direction : Direction.values()) {
                report("  NB " + direction.getName(), engine, chunks, expected,
                        cell.relative(direction));
            }
        }

        if (args.length > 7) {
            for (String part : args[7].split(",")) {
                String[] t = part.split(":");
                compareChunk(engine, chunks, expected, Integer.parseInt(t[0]),
                        Integer.parseInt(t[1]));
            }
        }
    }

    /**
     * Every in-world cell of one chunk, engine against capture, both layers.
     * A section the engine stores no layer for is skipped and listed: the
     * expected arrays encode an absent layer as zeros, while the engine reads
     * an absent sky layer as the default 15, so comparing values there would
     * report the encoding, not the light.
     */
    private static void compareChunk(LevelLightEngine engine, Map<Long, ProtoChunk> chunks,
            Path expected, int cx, int cz) throws IOException {
        ProtoChunk chunk = chunks.get(ChunkPos.pack(cx, cz));
        int compared = 0;
        int differing = 0;
        for (String layer : new String[]{"Block", "Sky"}) {
            LightLayer lightLayer = layer.equals("Block") ? LightLayer.BLOCK : LightLayer.SKY;
            List<Integer> absent = new ArrayList<>();
            for (int sy = chunk.getMinSectionY(); sy <= chunk.getMaxSectionY(); sy++) {
                if (engine.getLayerListener(lightLayer)
                        .getDataLayerData(SectionPos.of(cx, sy, cz)) == null) {
                    absent.add(sy);
                }
            }
            System.out.println("ORACLE CHUNK " + cx + "," + cz + " " + layer
                    + " sections without an engine layer, skipped: " + absent);
        }
        for (int y = chunk.getMinY(); y < chunk.getMinY() + chunk.getHeight(); y++) {
            for (int z = cz * 16; z < cz * 16 + 16; z++) {
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    for (String layer : new String[]{"Block", "Sky"}) {
                        String capture = captured(expected, pos, layer);
                        if (capture.equals("-")) {
                            continue;
                        }
                        LightLayer lightLayer = layer.equals("Block") ? LightLayer.BLOCK
                                : LightLayer.SKY;
                        if (engine.getLayerListener(lightLayer).getDataLayerData(
                                SectionPos.of(pos)) == null) {
                            continue;
                        }
                        int value = engine.getLayerListener(lightLayer).getLightValue(pos);
                        compared++;
                        if (value != Integer.parseInt(capture)) {
                            differing++;
                            System.out.println("ORACLE DIFF " + layer + " " + x + "," + y + ","
                                    + z + " state=" + chunk.getBlockState(pos)
                                    + " vanillaEngine=" + value + " capture=" + capture);
                        }
                    }
                }
            }
        }
        System.out.println("ORACLE CHUNK " + cx + "," + cz + " compared=" + compared
                + " differing=" + differing);
    }

    private static void drain(LevelLightEngine engine) {
        do {
            engine.runLightUpdates();
        } while (engine.hasLightWork());
    }

    private static void report(String tag, LevelLightEngine engine, Map<Long, ProtoChunk> chunks,
            Path expected, BlockPos pos) throws IOException {
        ProtoChunk chunk = chunks.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        BlockState state = chunk == null ? null : chunk.getBlockState(pos);
        int block = engine.getLayerListener(LightLayer.BLOCK).getLightValue(pos);
        int sky = engine.getLayerListener(LightLayer.SKY).getLightValue(pos);
        System.out.println("ORACLE " + tag + " " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + " state=" + (state == null ? "<outside>" : state)
                + " emission=" + (state == null ? "?" : state.getLightEmission())
                + " vanillaEngine block=" + block + " sky=" + sky
                + " capture block=" + captured(expected, pos, "Block")
                + " sky=" + captured(expected, pos, "Sky"));
    }

    private static final Map<Path, byte[]> EXPECTED = new HashMap<>();

    /** The capture's final value, same layout the replay's light dump uses (minY -80). */
    private static String captured(Path expected, BlockPos pos, String layer) throws IOException {
        Path file = expected.resolve((pos.getX() >> 4) + "_" + (pos.getZ() >> 4) + "_"
                + layer + "Light-expected.bin");
        if (!Files.isRegularFile(file)) {
            return "-";
        }
        byte[] bytes = EXPECTED.computeIfAbsent(file, f -> {
            try {
                return Files.readAllBytes(f);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        int index = ((pos.getY() + 80) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
        return Integer.toString((bytes[index >> 1] >> ((index & 1) * 4)) & 15);
    }

    /** Last snapshot hash recorded for each chunk of the window, by event sequence. */
    private static Map<Long, String> lastSnapshots(Path trace, int minCx, int minCz, int maxCx,
            int maxCz) throws IOException {
        Map<Long, String> out = new HashMap<>();
        Map<Long, Long> seqs = new HashMap<>();
        List<String> lines = Files.readAllLines(trace.resolve("events.tsv"));
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split("\t");
            if (f.length < 11) {
                continue;
            }
            long seq = Long.parseLong(f[0]);
            for (String ref : f[10].split(";")) {
                String[] parts = ref.split(",");
                if (parts.length < 4) {
                    continue;
                }
                int cx = Integer.parseInt(parts[0]);
                int cz = Integer.parseInt(parts[1]);
                if (cx < minCx || cx > maxCx || cz < minCz || cz > maxCz) {
                    continue;
                }
                long key = ChunkPos.pack(cx, cz);
                if (seq >= seqs.getOrDefault(key, Long.MIN_VALUE)) {
                    seqs.put(key, seq);
                    out.put(key, parts[3]);
                }
            }
        }
        return out;
    }

    private static ProtoChunk build(HeadlessNoise.Driver driver, Path trace, int cx, int cz,
            String hash) throws IOException {
        byte[] bytes;
        try (var in = new GZIPInputStream(Files.newInputStream(
                trace.resolve("chunks/" + hash + ".bin.gz")))) {
            bytes = in.readAllBytes();
        }
        ProtoChunk chunk = HeadlessNoise.newTrackedProtoChunk(driver, cx, cz);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4b465431) {
                throw new IOException("snapshot format " + hash);
            }
            if (in.readInt() != cx || in.readInt() != cz) {
                throw new IOException("snapshot coordinates " + hash);
            }
            int minY = in.readInt();
            int height = in.readInt();
            if (minY != driver.height().getMinY() || height != driver.height().getHeight()) {
                throw new IOException("snapshot height " + hash);
            }
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < height * 256; i++) {
                BlockState state = Block.stateById(in.readInt());
                if (!state.isAir()) {
                    int y = minY + (i >> 8);
                    sections[chunk.getSectionIndex(y)].setBlockState(i & 15, y & 15,
                            (i >> 4) & 15, state, false);
                }
            }
        }
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        chunk.initializeLightSources();
        return chunk;
    }

    private static LightChunkGetter getter(HeadlessNoise.Driver driver, Map<Long, ProtoChunk> chunks) {
        BlockGetter world = new BlockGetter() {
            @Override
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                ProtoChunk chunk = chunks.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
                return chunk == null ? Blocks.BEDROCK.defaultBlockState() : chunk.getBlockState(pos);
            }

            @Override
            public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
                ProtoChunk chunk = chunks.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
                return chunk == null ? Fluids.EMPTY.defaultFluidState() : chunk.getFluidState(pos);
            }

            @Override
            public int getHeight() {
                return driver.height().getHeight();
            }

            @Override
            public int getMinY() {
                return driver.height().getMinY();
            }
        };
        return new LightChunkGetter() {
            @Override
            public LightChunk getChunkForLighting(int x, int z) {
                return chunks.get(ChunkPos.pack(x, z));
            }

            @Override
            public BlockGetter getLevel() {
                return world;
            }
        };
    }
}
