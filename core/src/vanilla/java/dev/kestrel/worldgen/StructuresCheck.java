package dev.kestrel.worldgen;

import dev.kestrel.persist.ChunkCodec;
import dev.kestrel.persist.RegionFile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G2 step 8 parity probe (preresearch/g2-step8-design.md): does building a
 * real (structures ON) {@code StructureManager} and calling
 * {@code ChunkGenerator.createStructures} through it regress the step-7
 * FEATURES baseline on chunks (0,0) and (5,7), and does either chunk sit
 * near a real structure start on this seed.
 *
 * <p>Method: reproduce the step-7 baseline (column-major 3x3 FEATURES,
 * structures off) on each target chunk, snapshot it, then run the real
 * STARTS stage on that SAME filled chunk with a second, structures-ON
 * driver ({@code HeadlessNoise.loadStructural}). STRUCTURE_STARTS writes
 * only {@code chunk.structureStarts} (javap {@code ChunkGenerator
 * .createStructures}, {@code StructureManager.setStartForStructure}: never
 * {@code setBlockState}), so the changed-cell count and the oracle table
 * are a real measurement, not a restated claim. A 17x17 neighbourhood scan
 * (the STRUCTURE_REFERENCES radius) then checks whether any start outside
 * the target chunk has a bounding box reaching into it, which would matter
 * once the FEATURES structure block (not built this step) starts writing.
 *
 * <p>Args: regionFile [seed]. Runs both target chunks.
 */
public final class StructuresCheck {

    private StructuresCheck() {
    }

    private static final int[] TARGET_CX = {0, 5};
    private static final int[] TARGET_CZ = {0, 7};

    public static void main(String[] args) throws Exception {
        Path region = Path.of(args[0]);
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 4242424242L;

        // Driver first: it bootstraps the registries the oracle decode needs.
        HeadlessNoise.Driver driver = HeadlessNoise.load(seed);
        try (HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(seed)) {
            ResourceKey<net.minecraft.core.Registry<Structure>> structureRegistryKey = Registries.STRUCTURE;
            net.minecraft.core.Registry<Structure> structures =
                    structural.driver().access().lookupOrThrow(structureRegistryKey);

            for (int i = 0; i < TARGET_CX.length; i++) {
                int cx = TARGET_CX[i];
                int cz = TARGET_CZ[i];
                System.out.println("=== chunk " + cx + "," + cz + " ===");
                BlockState[] oracle = oracle(region, cx, cz);

                // Step 7 baseline: FEATURES, column-major 3x3 ring, structures off.
                Map<Long, ChunkAccess> chunks = prefix(driver, cx, cz);
                runFeatures(driver, chunks, cx, cz);
                ChunkAccess target = chunks.get(ChunkPos.pack(cx, cz));
                report("features baseline (step 7, structures off)", compare(oracle, target, cx, cz));

                BlockState[] before = snapshot(target, cx, cz);

                // Real STARTS on the SAME filled chunk, via the second
                // (structures ON) driver's generator/state/manager/templates.
                structural.driver().generator().createStructures(
                        structural.driver().access(), structural.structureState(),
                        structural.structures(), target, structural.templates(),
                        net.minecraft.world.level.Level.OVERWORLD);

                int changed = diffCount(before, target, cx, cz);
                report("+ real structures() [center chunk, " + changed + " cells changed]",
                        compare(oracle, target, cx, cz));

                Map<Structure, StructureStart> starts = target.getAllStarts();
                System.out.println("  starts in this chunk: " + describe(starts, structures));

                // 17x17 neighbourhood (STRUCTURE_REFERENCES radius, trap doc
                // 1.2): any start elsewhere whose bounding box reaches into
                // this chunk. Bare (unfilled) chunks are fine here: STARTS
                // needs nothing upstream.
                List<String> reaching = new ArrayList<>();
                int probed = 0;
                for (int dz = -8; dz <= 8; dz++) {
                    for (int dx = -8; dx <= 8; dx++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        ChunkAccess probe = HeadlessNoise.structures(structural, cx + dx, cz + dz);
                        probed++;
                        for (Map.Entry<Structure, StructureStart> e : probe.getAllStarts().entrySet()) {
                            BoundingBox box = e.getValue().getBoundingBox();
                            if (box.intersects(cx * 16, cz * 16, cx * 16 + 15, cz * 16 + 15)) {
                                reaching.add(dx + "," + dz + " " + keyOf(e.getKey(), structures));
                            }
                        }
                    }
                }
                System.out.println("  neighbour starts reaching this chunk (" + probed
                        + " chunks probed, radius 8): " + (reaching.isEmpty() ? "none" : reaching));

                // Full step-8 candidate: STARTS + REFERENCES + Beardifier in
                // NOISE + normal SURFACE/CARVERS + structure placement
                // interleaved with FEATURES at Vanilla's decoration step.
                Map<Long, ChunkAccess> structured =
                        prefixWithStructures(structural, cx, cz);
                runFeaturesWithStructures(
                        structural, structured, cx, cz);
                ChunkAccess structuredTarget =
                        structured.get(ChunkPos.pack(cx, cz));
                Result full = compare(
                        oracle, structuredTarget, cx, cz);
                report("full structures-on FEATURES", full);
                if (full.same() != full.total()) {
                    throw new AssertionError(
                            "structures-on parity failed for chunk "
                                    + cx + "," + cz
                                    + ": same=" + full.same()
                                    + "/" + full.total());
                }
            }
        }
    }

    private static String describe(Map<Structure, StructureStart> starts,
            net.minecraft.core.Registry<Structure> registry) {
        if (starts.isEmpty()) {
            return "none";
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<Structure, StructureStart> e : starts.entrySet()) {
            out.add(keyOf(e.getKey(), registry) + " refs=" + e.getValue().getReferences());
        }
        return out.toString();
    }

    private static String keyOf(Structure structure, net.minecraft.core.Registry<Structure> registry) {
        return registry.getResourceKey(structure).map(k -> k.identifier().toString())
                .orElse(structure.toString());
    }

    private static int diffCount(BlockState[] before, ChunkAccess chunk, int cx, int cz) {
        int changed = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < before.length; i++) {
            cell(m, i, cx, cz);
            if (chunk.getBlockState(m) != before[i]) {
                changed++;
            }
        }
        return changed;
    }

    private static final int[][] COLUMN_MAJOR = ring();

    /** 3x3 offsets, column-major (x outer), the best order found in step 7. */
    private static int[][] ring() {
        List<int[]> out = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                out.add(new int[]{a, b});
            }
        }
        return out.toArray(new int[0][]);
    }

    /** Prefix through CARVERS (vanilla-faithful) on the 5x5 around the target. */
    private static Map<Long, ChunkAccess> prefix(HeadlessNoise.Driver driver, int cx, int cz) {
        Map<Long, ChunkAccess> out = new LinkedHashMap<>();
        // Diagnostic-only whole-grid two-phase setup: SURFACE needs each
        // chunk's own 3x3 biome neighbourhood at BIOMES status, one ring
        // wider than this 5x5 itself, so prepareBiomeGrid's margin map
        // supplies that outer ring on demand.
        Map<ChunkPos, ChunkAccess> noiseMargin = new HashMap<>();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(driver, x, z,
                        (bx, bz) -> out.get(ChunkPos.pack(bx, bz)), noiseMargin);
                ChunkAccess c = biomeGrid.get(new ChunkPos(x, z));
                HeadlessNoise.surfaceVanilla(driver, c, biomeGrid);
                HeadlessNoise.carveVanilla(driver, c);
                ((ProtoChunk) c).setPersistedStatus(ChunkStatus.CARVERS);
                out.put(ChunkPos.pack(x, z), c);
                noiseMargin.remove(new ChunkPos(x, z));
            }
        }
        return out;
    }

    /**
     * Full structures-on prefix through CARVERS for a 5x5 around the target.
     * STARTS are shared across all centers so every reference resolves to the
     * same source object, while surface biome margins remain independent
     * BIOMES-only chunks exactly like the structures-off Tier-R path.
     */
    private static Map<Long, ChunkAccess> prefixWithStructures(
            HeadlessNoise.Structural structural,
            int cx,
            int cz) {
        Map<Long, ChunkAccess> startsCache = new HashMap<>();
        Map<Long, ChunkAccess> real = new LinkedHashMap<>();

        // Phase 1: all real 5x5 centers through structures-on NOISE so later
        // surface passes see the actual neighbour biome palettes.
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                ChunkAccess chunk =
                        HeadlessNoise.noiseWithStructures(
                                structural, x, z, startsCache);
                real.put(ChunkPos.pack(x, z), chunk);
            }
        }

        // Phase 2: surface/carve the same real chunks. Outer surface samples
        // need biomes only; terrain adaptation already lives in each real
        // center's memoized NoiseChunk from the structures-on NOISE pass.
        Map<ChunkPos, ChunkAccess> biomeMargin = new HashMap<>();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                ChunkAccess chunk = real.get(ChunkPos.pack(x, z));
                Map<ChunkPos, ChunkAccess> biomeGrid =
                        HeadlessNoise.prepareBiomeGrid(
                                structural.driver(),
                                x,
                                z,
                                (bx, bz) -> real.get(
                                        ChunkPos.pack(bx, bz)),
                                biomeMargin);
                HeadlessNoise.surfaceVanilla(
                        structural.driver(), chunk, biomeGrid);
                HeadlessNoise.carveVanilla(
                        structural.driver(), chunk);
                ((ProtoChunk) chunk).setPersistedStatus(
                        ChunkStatus.CARVERS);
                HeadlessNoise.releaseNoiseChunk(chunk);
            }
        }

        // startsCache already owns the same 25 center objects, advanced
        // in-place through NOISE/CARVERS, plus all bare STARTS chunks needed
        // by their radius-8 reference sets.
        return startsCache;
    }

    private static void runFeaturesWithStructures(
            HeadlessNoise.Structural structural,
            Map<Long, ChunkAccess> chunks,
            int cx,
            int cz) {
        for (int[] o : COLUMN_MAJOR) {
            ChunkPos p = new ChunkPos(cx + o[0], cz + o[1]);
            HeadlessNoise.RegionView view =
                    new HeadlessNoise.RegionView(
                            structural.driver(), p, chunks, structural.templates());
            ChunkAccess chunk = chunks.get(p.pack());
            HeadlessNoise.featuresWithStructures(
                    structural, chunk, view);
        }
    }

    private static void runFeatures(HeadlessNoise.Driver driver, Map<Long, ChunkAccess> chunks,
            int cx, int cz) {
        for (int[] o : COLUMN_MAJOR) {
            ChunkPos p = new ChunkPos(cx + o[0], cz + o[1]);
            HeadlessNoise.RegionView view = new HeadlessNoise.RegionView(driver, p, chunks);
            ChunkAccess c = chunks.get(p.pack());
            HeadlessNoise.features(driver, c, view);
            ((ProtoChunk) c).setPersistedStatus(ChunkStatus.FEATURES);
        }
    }

    private static void cell(BlockPos.MutableBlockPos m, int i, int cx, int cz) {
        int y = HeadlessNoise.MIN_Y + ((i >> 12) << 4) + ((i >> 8) & 15);
        m.set(cx * 16 + (i & 15), y, cz * 16 + ((i >> 4) & 15));
    }

    private static BlockState[] snapshot(ChunkAccess chunk, int cx, int cz) {
        BlockState[] out = new BlockState[HeadlessNoise.HEIGHT * 256];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < out.length; i++) {
            cell(m, i, cx, cz);
            out[i] = chunk.getBlockState(m);
        }
        return out;
    }

    record Result(int same, int total, Map<String, Integer> diffs) {
    }

    private static BlockState[] oracle(Path region, int cx, int cz) throws Exception {
        BlockState[] cells = new BlockState[HeadlessNoise.HEIGHT * 256];
        java.util.Arrays.fill(cells, Blocks.AIR.defaultBlockState());
        // RegionFile.open CREATES an empty region file on a missing path, and
        // cells is pre-filled with air, so an absent oracle would silently
        // become "vanilla generated nothing here" and this check would score
        // the generator against a blank world. That is how BatchParityGate
        // reported a meaningless 0% for four days (see its fullStateHex
        // comment). Refuse to invent an oracle.
        if (!Files.exists(region)) {
            throw new IllegalStateException("Oracle region file missing, refusing to compare against a "
                    + "fabricated empty world: " + region);
        }
        RegionFile rf = RegionFile.open(region);
        try {
            if (!rf.hasChunk(cx & 31, cz & 31)) {
                throw new IllegalStateException("Oracle region file has no chunk (" + cx + ", " + cz
                        + "), refusing to compare against a fabricated empty world: " + region);
            }
            ChunkCodec.DecodedChunk d = ChunkCodec.decodeChunk(
                    rf.readChunk(cx & 31, cz & 31), HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT, 0L);
            for (ChunkCodec.SlotCells s : d.sections()) {
                System.arraycopy(s.cells(), 0, cells, s.slot() * 4096, 4096);
            }
        } finally {
            rf.close();
        }
        return cells;
    }

    private static Result compare(BlockState[] oracle, ChunkAccess chunk, int cx, int cz) {
        int same = 0;
        Map<String, Integer> diffs = new HashMap<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < oracle.length; i++) {
            cell(m, i, cx, cz);
            BlockState got = chunk.getBlockState(m);
            BlockState want = oracle[i];
            if (got == want) {
                same++;
            } else {
                diffs.merge(want.getBlock().builtInRegistryHolder().key().identifier().getPath()
                        + " -> " + got.getBlock().builtInRegistryHolder().key().identifier().getPath()
                        + (got.getBlock() == want.getBlock() ? " (props)" : ""), 1, Integer::sum);
            }
        }
        return new Result(same, oracle.length, diffs);
    }

    private static void report(String label, Result r) {
        System.out.printf("%-56s same %d/%d = %.2f%%%n", label, r.same(), r.total(),
                100.0 * r.same() / r.total());
        List<Map.Entry<String, Integer>> top = new ArrayList<>(r.diffs().entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            System.out.println("    oracle->ours " + top.get(i).getKey() + ": " + top.get(i).getValue());
        }
    }
}
