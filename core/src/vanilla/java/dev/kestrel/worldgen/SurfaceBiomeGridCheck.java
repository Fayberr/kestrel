package dev.kestrel.worldgen;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Fail-closed regression for the SURFACE BiomeManager fix: promotes the
 * evidence gathered in {@code .diagnostics/TestSurfaceWithGridBiomes.java}
 * (the diagnostic that first proved the defect and its fix) into a durable
 * check. Not a new oracle: same seed, same trace, same six representative
 * chunks, same "diffs vs oracle" measurement.
 *
 * <p>For each chunk this runs SURFACE two ways against the exact same NOISE
 * output:
 * <ul>
 *   <li>OLD - a raw {@code BiomeSource}-backed {@link BiomeManager}, the
 *       shape {@link HeadlessNoise#surfaceVanilla} used before this fix
 *       (reconstructed here since production no longer exposes it: removing
 *       the old code path is the point of the fix, not something to keep
 *       around as a callable method). This is the negative control: it must
 *       still reproduce nonzero diffs, or the check would be vacuous.</li>
 *   <li>NEW - the actual production path,
 *       {@link HeadlessNoise#prepareBiomeGrid} + the grid-taking
 *       {@link HeadlessNoise#surfaceVanilla}. Must match the oracle
 *       exactly.</li>
 * </ul>
 *
 * <p>Covers the family split from the Tier R residual diagnosis
 * ({@code docs/TIER-R-RESIDUAL-DIAGNOSIS.md}): coastal beach, sea-floor
 * gravel/sand, and dirt/stone slope columns.
 */
public final class SurfaceBiomeGridCheck {
    private static final long SEED = 3527749958874813416L;
    private static final Path TRACE = Path.of("/tmp/kestrel-features-trace-large-36-v2/trace");

    private record Snapshot(int x, int z, int minY, int height, int[] ids) {}

    private record Case(int cx, int cz, String family, int knownOldDiffs) {}

    private static final Case[] CASES = {
        new Case(-11, 15, "coastal beach", 98),
        new Case(-2, 9, "coastal beach", 71),
        new Case(5, 18, "coastal beach", 35),
        new Case(-2, 16, "dirt/stone slope", 39),
        new Case(7, -3, "sea-floor gravel/sand", 11),
        new Case(-2, -1, "sea-floor gravel/sand", 8),
    };

    private static Snapshot read(String ref) throws Exception {
        String sha = ref.split(",")[3];
        byte[] bytes;
        try (var in = new GZIPInputStream(Files.newInputStream(TRACE.resolve("chunks/" + sha + ".bin.gz")))) {
            bytes = in.readAllBytes();
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            in.readInt();
            int x = in.readInt(), z = in.readInt(), minY = in.readInt(), height = in.readInt();
            int[] ids = new int[height * 256];
            for (int i = 0; i < ids.length; i++) ids[i] = in.readInt();
            return new Snapshot(x, z, minY, height, ids);
        }
    }

    /** The OLD, buggy shape: raw source-backed BiomeManager (removed from production). */
    private static BiomeManager rawSourceBiomes(HeadlessNoise.Driver driver) {
        return new BiomeManager(
                (qx, qy, qz) -> driver.generator().getBiomeSource().getNoiseBiome(
                        qx, qy, qz, driver.randomState().sampler()),
                BiomeManager.obfuscateSeed(driver.seed()));
    }

    private static int diffAgainst(Snapshot oracle, ChunkAccess chunk) {
        int diffs = 0;
        for (int i = 0; i < oracle.ids.length; i++) {
            BlockPos p = new BlockPos(oracle.x * 16 + (i & 15), oracle.minY + (i >> 8),
                    oracle.z * 16 + ((i >> 4) & 15));
            if (Block.getId(FeaturesTraceReplay.capturedState(chunk, p)) != oracle.ids[i]) {
                diffs++;
            }
        }
        return diffs;
    }

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(TRACE)) {
            throw new IllegalStateException("Tier R trace missing, refusing to check against nothing: " + TRACE);
        }
        var driver = HeadlessNoise.load(SEED);

        Map<ChunkPos, String> carverRefs = new HashMap<>();
        var lines = Files.readAllLines(TRACE.resolve("events.tsv"));
        for (String line : lines.subList(1, lines.size())) {
            for (String ref : line.split("\t")[10].split(";")) {
                String[] p = ref.split(",");
                int cx = Integer.parseInt(p[0]), cz = Integer.parseInt(p[1]);
                if (p[2].equals("minecraft:carvers") && !carverRefs.containsKey(new ChunkPos(cx, cz))) {
                    carverRefs.put(new ChunkPos(cx, cz), ref);
                }
            }
        }

        int failures = 0;
        for (Case tc : CASES) {
            ChunkPos cp = new ChunkPos(tc.cx, tc.cz);
            String ref = carverRefs.get(cp);
            if (ref == null) {
                throw new IllegalStateException("no CARVERS oracle snapshot for " + cp + " in this trace");
            }
            Snapshot oracle = read(ref);

            // OLD: single chunk, raw source-backed BiomeManager - the exact
            // shape the fix removes.
            ChunkAccess cOld = HeadlessNoise.noise(driver, tc.cx, tc.cz);
            WorldGenerationContext ctx = new WorldGenerationContext(driver.generator(), driver.height());
            Set<Holder<Biome>> possible = HeadlessNoise.collectPossibleBiomes(driver, cOld);
            driver.generator().buildSurface(cOld, ctx, driver.randomState(), driver.structures(),
                    rawSourceBiomes(driver), Blender.empty(), possible);
            HeadlessNoise.carveVanilla(driver, cOld);
            int diffOld = diffAgainst(oracle, cOld);

            // NEW: production path, palette-backed BiomeManager over a
            // prepared 3x3 grid.
            Map<Long, ChunkAccess> empty = Map.of();
            Map<ChunkPos, ChunkAccess> margin = new HashMap<>();
            Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(driver, tc.cx, tc.cz,
                    (bx, bz) -> empty.get(ChunkPos.pack(bx, bz)), margin);
            ChunkAccess cNew = biomeGrid.get(cp);
            HeadlessNoise.surfaceVanilla(driver, cNew, biomeGrid);
            HeadlessNoise.carveVanilla(driver, cNew);
            int diffNew = diffAgainst(oracle, cNew);

            System.out.printf("chunk=(%d,%d) family=%s oldDiffs=%d (expected>=%d) newDiffs=%d (expected 0)%n",
                    tc.cx, tc.cz, tc.family, diffOld, tc.knownOldDiffs, diffNew);

            if (diffOld == 0) {
                System.out.println("FAIL: OLD path produced 0 diffs, negative control is vacuous for " + cp);
                failures++;
            }
            if (diffNew != 0) {
                System.out.println("FAIL: NEW path produced " + diffNew + " diffs, expected exact oracle match for " + cp);
                failures++;
            }
        }

        if (failures != 0) {
            throw new AssertionError("SurfaceBiomeGridCheck: " + failures + " case(s) failed");
        }
        System.out.println("RESULT SurfaceBiomeGridCheck: all " + CASES.length + " cases pass (old buggy, new exact)");
    }
}
