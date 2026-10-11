package dev.kestrel.worldgen;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Opt-in diagnostic red gate for the requested order-invariance property.
 * This is stronger than the D2 parity contract: vanilla overlap writes can also
 * depend on order. A failure proves sensitivity, not a Kestrel-only defect.
 */
public final class FeatureOrderCheck {
    private static final long SEED = 3527749958874813416L;

    private static String[] run(int cx, int cz, boolean reverse) throws Exception {
        var driver = HeadlessNoise.load(SEED);
        Map<Long, ChunkAccess> grid = new HashMap<>();
        // Diagnostic-only whole-grid two-phase setup: SURFACE needs each
        // chunk's own 3x3 biome neighbourhood at BIOMES status, one ring
        // wider than this 5x5 itself, so prepareBiomeGrid's margin map
        // supplies that outer ring on demand.
        Map<ChunkPos, ChunkAccess> noiseMargin = new HashMap<>();
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(driver, x, z,
                        (bx, bz) -> grid.get(ChunkPos.pack(bx, bz)), noiseMargin);
                var chunk = biomeGrid.get(new ChunkPos(x, z));
                HeadlessNoise.surfaceVanilla(driver, chunk, biomeGrid);
                HeadlessNoise.carveVanilla(driver, chunk);
                ((ProtoChunk) chunk).setPersistedStatus(ChunkStatus.CARVERS);
                HeadlessNoise.releaseNoiseChunk(chunk);
                grid.put(ChunkPos.pack(x, z), chunk);
                noiseMargin.remove(new ChunkPos(x, z));
            }
        }
        List<ChunkPos> origins = new ArrayList<>();
        for (int x = cx - 1; x <= cx + 1; x++)
            for (int z = cz - 1; z <= cz + 1; z++) origins.add(new ChunkPos(x, z));
        if (reverse) Collections.reverse(origins);
        // Existing production hook observes writes, including direct section writes by ores.
        BlockPos watch = cx == -6 && cz == -6 ? new BlockPos(-96, 39, -96)
                : cx == -6 ? new BlockPos(-81, -19, 109) : new BlockPos(-80, -18, 105);
        String[] previous = {grid.get(ChunkPos.pack(cx, cz)).getBlockState(watch).toString()};
        for (var origin : origins) {
            var view = new HeadlessNoise.RegionView(driver, origin, grid);
            view.afterFeature = key -> {
                String now = grid.get(ChunkPos.pack(cx, cz)).getBlockState(watch).toString();
                if (!now.equals(previous[0])) {
                    System.out.println("TRACE reverse=" + reverse + " origin=" + origin
                            + " feature=" + key + " cell=" + watch + " " + previous[0] + " -> " + now);
                    previous[0] = now;
                }
            };
            HeadlessNoise.features(driver, grid.get(origin.pack()), view);
        }
        String[] result = new String[384 * 256];
        var target = grid.get(ChunkPos.pack(cx, cz));
        for (int i = 0; i < result.length; i++)
            result[i] = target.getBlockState(new BlockPos(cx * 16 + (i & 15),
                    -64 + (i >> 8), cz * 16 + ((i >> 4) & 15))).toString();
        return result;
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        for (int[] center : new int[][]{{-6, -6}, {-6, 6}, {-5, 6}}) {
            int cx = center[0], cz = center[1];
            String[] a = run(cx, cz, false), repeat = run(cx, cz, false), b = run(cx, cz, true);
            if (!Arrays.equals(a, repeat)) throw new AssertionError("same-order repeat is unstable");
            int differences = 0;
            for (int i = 0; i < a.length; i++) {
                if (!a[i].equals(b[i]) && (a[i] + b[i]).matches(".*(kelp|seagrass|sulfur).*")) {
                    if (differences++ < 5) System.out.println("DIFF cell="
                            + new BlockPos(cx * 16 + (i & 15), -64 + (i >> 8), cz * 16 + ((i >> 4) & 15))
                            + " forward=" + a[i] + " reverse=" + b[i]);
                }
            }
            System.out.println("RESULT target=" + cx + "," + cz + " repeat=identical familyDifferences=" + differences);
            failures += differences;
        }
        if (failures != 0) throw new AssertionError("FEATURES order changed " + failures + " target family cells");
    }
}
