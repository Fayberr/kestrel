package dev.kestrel.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Speed and latency measurement probe for Kestrel G2 HeadlessNoise pipeline.
 *
 * <p>Measures per-chunk wall time across the full pipeline:
 * {@code noise + surfaceVanilla + carveVanilla + features + settleLeaves}
 * over a 11x11 = 121 chunk target area ([-5..5] x [-5..5]) on seed 4242424242L (structures OFF).
 *
 * <p>Profiles per-stage latency (mean, p50, p95, max) vs the 50ms server tick budget,
 * and calculates sections-per-second (24 sections/chunk in 1.18+ Overworld).
 */
public final class SpeedProbe {

    public static final long SEED = 4242424242L;

    public record ChunkMetrics(
            int x, int z,
            double noiseMs,
            double surfaceMs,
            double carverMs,
            double featuresMs,
            double settleMs,
            double totalMs,
            int changedLeaves
    ) {}

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : SEED;
        int targetRadius = args.length > 1 ? Integer.parseInt(args[1]) : 5; // 5 -> [-5..5] = 11x11 = 121 chunks
        int borderRadius = targetRadius + 2; // radius 7 -> 15x15 = 225 chunks prefix
        Path outCsv = Path.of(args.length > 2 ? args[2] : "harness/tools/parity/fullrun/speed-headless.csv");

        System.out.println("=== Kestrel G2 HeadlessNoise Pipeline Speed Benchmark ===");
        System.out.printf("Seed:             %d%n", seed);
        System.out.printf("Target Radius:    %d ([-%d..%d] x [-%d..%d], total %d chunks)%n",
                targetRadius, targetRadius, targetRadius, targetRadius, targetRadius, (2 * targetRadius + 1) * (2 * targetRadius + 1));
        System.out.printf("Border Radius:    %d ([-%d..%d] x [-%d..%d], total %d prefix chunks)%n",
                borderRadius, borderRadius, borderRadius, borderRadius, borderRadius, (2 * borderRadius + 1) * (2 * borderRadius + 1));
        System.out.printf("Output CSV:       %s%n", outCsv);

        long tLoad0 = System.nanoTime();
        HeadlessNoise.Driver driver = HeadlessNoise.load(seed);
        long tLoadMs = (System.nanoTime() - tLoad0) / 1_000_000;
        System.out.printf("HeadlessNoise driver loaded in %d ms%n", tLoadMs);

        // Warmup JIT on a few chunks outside target area
        System.out.println("JIT Warmup on 4 chunks...");
        Map<Long, ChunkAccess> warmupMap = new HashMap<>();
        Map<ChunkPos, ChunkAccess> warmupMargin = new HashMap<>();
        for (int wx = 10; wx <= 11; wx++) {
            for (int wz = 10; wz <= 11; wz++) {
                Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(driver, wx, wz,
                        (bx, bz) -> warmupMap.get(ChunkPos.pack(bx, bz)), warmupMargin);
                ChunkAccess wc = biomeGrid.get(new ChunkPos(wx, wz));
                HeadlessNoise.surfaceVanilla(driver, wc, biomeGrid);
                HeadlessNoise.carveVanilla(driver, wc);
                ((ProtoChunk) wc).setPersistedStatus(ChunkStatus.CARVERS);
                warmupMap.put(ChunkPos.pack(wx, wz), wc);
                warmupMargin.remove(new ChunkPos(wx, wz));
            }
        }
        for (int wx = 10; wx <= 11; wx++) {
            for (int wz = 10; wz <= 11; wz++) {
                ChunkPos wp = new ChunkPos(wx, wz);
                ChunkAccess wc = warmupMap.get(wp.pack());
                try {
                    HeadlessNoise.RegionView view = new HeadlessNoise.RegionView(driver, wp, warmupMap);
                    HeadlessNoise.features(driver, wc, view);
                } catch (Exception ignored) {
                    // edge warmup might miss radius 1 chunks, fine
                }
            }
        }
        warmupMap.clear();
        System.out.println("Warmup complete.\n");

        // Target coordinates list
        List<ChunkPos> targets = new ArrayList<>();
        for (int x = -targetRadius; x <= targetRadius; x++) {
            for (int z = -targetRadius; z <= targetRadius; z++) {
                targets.add(new ChunkPos(x, z));
            }
        }
        Set<Long> targetSet = new HashSet<>();
        for (ChunkPos p : targets) {
            targetSet.add(p.pack());
        }

        Map<Long, ChunkAccess> grid = new HashMap<>();
        Map<Long, Double> noiseTimes = new HashMap<>();
        Map<Long, Double> surfaceTimes = new HashMap<>();
        Map<Long, Double> carverTimes = new HashMap<>();

        // 1. PREFIX: NOISE + SURFACE + CARVERS on border grid
        System.out.printf("[Stage 1-3] Running P2 Prefix (noise + surfaceVanilla + carveVanilla) on %d chunks...%n",
                (2 * borderRadius + 1) * (2 * borderRadius + 1));
        long tPrefixStart = System.nanoTime();
        int p2Count = 0;
        // Diagnostic-only whole-grid two-phase setup: SURFACE needs each
        // chunk's own 3x3 biome neighbourhood at BIOMES status, one ring
        // wider than the border grid itself, so prepareBiomeGrid's margin
        // map supplies that outer ring on demand.
        Map<ChunkPos, ChunkAccess> noiseMargin = new HashMap<>();
        for (int x = -borderRadius; x <= borderRadius; x++) {
            for (int z = -borderRadius; z <= borderRadius; z++) {
                long k = ChunkPos.pack(x, z);

                long t0 = System.nanoTime();
                Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(driver, x, z,
                        (bx, bz) -> grid.get(ChunkPos.pack(bx, bz)), noiseMargin);
                ChunkAccess c = biomeGrid.get(new ChunkPos(x, z));
                long t1 = System.nanoTime();

                HeadlessNoise.surfaceVanilla(driver, c, biomeGrid);
                noiseMargin.remove(new ChunkPos(x, z));
                long t2 = System.nanoTime();

                HeadlessNoise.carveVanilla(driver, c);
                long t3 = System.nanoTime();

                ((ProtoChunk) c).setPersistedStatus(ChunkStatus.CARVERS);
                grid.put(k, c);

                if (targetSet.contains(k)) {
                    noiseTimes.put(k, (t1 - t0) / 1_000_000.0);
                    surfaceTimes.put(k, (t2 - t1) / 1_000_000.0);
                    carverTimes.put(k, (t3 - t2) / 1_000_000.0);
                }
                p2Count++;
            }
        }
        long tPrefixEnd = System.nanoTime();
        System.out.printf("P2 Prefix completed in %.2f s (%.2f ms/chunk over %d chunks)%n",
                (tPrefixEnd - tPrefixStart) / 1e9,
                (tPrefixEnd - tPrefixStart) / 1e6 / p2Count,
                p2Count);

        // 2. FEATURES on target chunks (row-major order)
        System.out.printf("[Stage 4] Running FEATURES on %d target chunks...%n", targets.size());
        Map<Long, Double> featuresTimes = new HashMap<>();
        long tFeatStart = System.nanoTime();
        int featCount = 0;
        for (ChunkPos p : targets) {
            long k = p.pack();
            ChunkAccess c = grid.get(k);
            HeadlessNoise.RegionView view = new HeadlessNoise.RegionView(driver, p, grid);

            long t0 = System.nanoTime();
            HeadlessNoise.features(driver, c, view);
            long t1 = System.nanoTime();

            ((ProtoChunk) c).setPersistedStatus(ChunkStatus.FEATURES);
            featuresTimes.put(k, (t1 - t0) / 1_000_000.0);
            featCount++;
            if (featCount % 25 == 0 || featCount == targets.size()) {
                System.out.printf("  Features progress: %d/%d (%.1f%%)...%n",
                        featCount, targets.size(), 100.0 * featCount / targets.size());
            }
        }
        long tFeatEnd = System.nanoTime();
        System.out.printf("FEATURES completed in %.2f s (%.2f ms/chunk)%n",
                (tFeatEnd - tFeatStart) / 1e9,
                (tFeatEnd - tFeatStart) / 1e6 / targets.size());

        // 3. SETTLE LEAVES on target chunks
        System.out.printf("[Stage 5] Running settleLeaves on %d target chunks...%n", targets.size());
        Map<Long, Double> settleTimes = new HashMap<>();
        Map<Long, Integer> settleChanged = new HashMap<>();
        long tSettleStart = System.nanoTime();
        int settleCount = 0;
        for (ChunkPos p : targets) {
            long k = p.pack();
            long t0 = System.nanoTime();
            int changed = HeadlessNoise.settleLeaves(grid, p.x(), p.z());
            long t1 = System.nanoTime();

            settleTimes.put(k, (t1 - t0) / 1_000_000.0);
            settleChanged.put(k, changed);
            settleCount++;
            if (settleCount % 25 == 0 || settleCount == targets.size()) {
                System.out.printf("  settleLeaves progress: %d/%d (%.1f%%)...%n",
                        settleCount, targets.size(), 100.0 * settleCount / targets.size());
            }
        }
        long tSettleEnd = System.nanoTime();
        System.out.printf("settleLeaves completed in %.2f s (%.2f ms/chunk)%n",
                (tSettleEnd - tSettleStart) / 1e9,
                (tSettleEnd - tSettleStart) / 1e6 / targets.size());

        // Collect metrics
        List<ChunkMetrics> metrics = new ArrayList<>();
        List<Double> allTotal = new ArrayList<>();
        List<Double> allNoise = new ArrayList<>();
        List<Double> allSurface = new ArrayList<>();
        List<Double> allCarver = new ArrayList<>();
        List<Double> allFeatures = new ArrayList<>();
        List<Double> allSettle = new ArrayList<>();

        for (ChunkPos p : targets) {
            long k = p.pack();
            double n = noiseTimes.get(k);
            double s = surfaceTimes.get(k);
            double c = carverTimes.get(k);
            double f = featuresTimes.get(k);
            double l = settleTimes.get(k);
            double tot = n + s + c + f + l;
            int ch = settleChanged.get(k);

            metrics.add(new ChunkMetrics(p.x(), p.z(), n, s, c, f, l, tot, ch));
            allTotal.add(tot);
            allNoise.add(n);
            allSurface.add(s);
            allCarver.add(c);
            allFeatures.add(f);
            allSettle.add(l);
        }

        // Save CSV
        if (outCsv.getParent() != null) {
            Files.createDirectories(outCsv.getParent());
        }
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outCsv.toFile()))) {
            bw.write("chunk_x,chunk_z,noise_ms,surface_ms,carver_ms,features_ms,settle_ms,total_ms,changed_leaves\n");
            for (ChunkMetrics m : metrics) {
                bw.write(String.format(Locale.US, "%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d\n",
                        m.x, m.z, m.noiseMs, m.surfaceMs, m.carverMs, m.featuresMs, m.settleMs, m.totalMs, m.changedLeaves));
            }
        }
        System.out.printf("Wrote CSV to %s%n", outCsv);

        // Print Summary Stats
        System.out.println("\n=======================================================");
        System.out.println("           KESTREL G2 SPEED BENCHMARK RESULTS           ");
        System.out.println("=======================================================");
        printStat("NOISE", allNoise);
        printStat("SURFACE", allSurface);
        printStat("CARVERS", allCarver);
        printStat("FEATURES", allFeatures);
        printStat("SETTLE_LEAVES", allSettle);
        System.out.println("-------------------------------------------------------");
        printStat("TOTAL (per chunk)", allTotal);
        System.out.println("=======================================================");

        double meanTot = mean(allTotal);
        double p95Tot = percentile(allTotal, 0.95);
        double maxTot = max(allTotal);
        double totalWallSeconds = (tPrefixEnd - tPrefixStart + tFeatEnd - tFeatStart + tSettleEnd - tSettleStart) / 1e9;
        double sectionsPerSec = (targets.size() * 24.0) / (meanTot * targets.size() / 1000.0);

        System.out.printf("Target Chunks:       %d%n", targets.size());
        System.out.printf("Mean ms-per-chunk:   %.2f ms%n", meanTot);
        System.out.printf("p95 ms-per-chunk:    %.2f ms%n", p95Tot);
        System.out.printf("Max ms-per-chunk:    %.2f ms%n", maxTot);
        System.out.printf("Sections per second: %.2f sec/s (24 sections/chunk)%n", sectionsPerSec);
        System.out.printf("Vs 50ms Tick Budget: %.2fx tick budget (Mean = %.1f ticks, p95 = %.1f ticks)%n",
                meanTot / 50.0, meanTot / 50.0, p95Tot / 50.0);
        System.out.printf("Stage Dominance:     %s%n",
                dominantStage(allNoise, allSurface, allCarver, allFeatures, allSettle, meanTot));
    }

    private static String dominantStage(List<Double> n, List<Double> s, List<Double> c, List<Double> f, List<Double> l, double tot) {
        double mn = mean(n), ms = mean(s), mc = mean(c), mf = mean(f), ml = mean(l);
        double maxVal = Math.max(mn, Math.max(ms, Math.max(mc, Math.max(mf, ml))));
        String name = "NOISE";
        if (maxVal == ms) name = "SURFACE";
        else if (maxVal == mc) name = "CARVERS";
        else if (maxVal == mf) name = "FEATURES";
        else if (maxVal == ml) name = "SETTLE_LEAVES";
        return String.format("%s (mean %.2f ms, %.1f%%)", name, maxVal, 100.0 * maxVal / tot);
    }

    private static void printStat(String name, List<Double> vals) {
        Collections.sort(vals);
        double mean = mean(vals);
        double p50 = percentile(vals, 0.50);
        double p95 = percentile(vals, 0.95);
        double max = max(vals);
        double min = vals.get(0);
        System.out.printf("%-18s: mean=%7.2f ms | p50=%7.2f ms | p95=%7.2f ms | max=%7.2f ms | min=%7.2f ms%n",
                name, mean, p50, p95, max, min);
    }

    private static double mean(List<Double> vals) {
        double sum = 0;
        for (double d : vals) sum += d;
        return sum / vals.size();
    }

    private static double percentile(List<Double> sorted, double p) {
        List<Double> copy = new ArrayList<>(sorted);
        Collections.sort(copy);
        int idx = (int) Math.ceil(p * copy.size()) - 1;
        return copy.get(Math.max(0, Math.min(copy.size() - 1, idx)));
    }

    private static double max(List<Double> vals) {
        double m = vals.get(0);
        for (double d : vals) if (d > m) m = d;
        return m;
    }
}
