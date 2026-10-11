package dev.kestrel.worldgen;

import dev.kestrel.persist.ChunkCodec;
import dev.kestrel.persist.RegionFile;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * G2 step 7 on-disk parity probe (preresearch/g2-step7-design.md): one
 * chunk vs the oracle region file, per stage variant.
 *
 * <p>Args: regionFile [seed] [chunkX chunkZ]. Prefix (noise, surface,
 * carvers) runs on the 5x5 around the target so every chunk of the 3x3
 * FEATURES ring has its own radius-1 view. FEATURES runs the 3x3 ring
 * serially in five candidate orders (vanilla's real order is not recorded
 * here; cross-order effects are the P3 follow-up). The leaf settle pass is
 * a diagnostic for post-FULL leaf ticks, not part of step 7.
 */
public final class FeaturesCheck {

    private FeaturesCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path region = Path.of(args[0]);
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 4242424242L;
        int cx = args.length > 3 ? Integer.parseInt(args[2]) : 0;
        int cz = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        // Driver first: it bootstraps the registries the oracle decode needs.
        HeadlessNoise.Driver driver = HeadlessNoise.load(seed);
        BlockState[] oracle = oracle(region, cx, cz);

        // Baselines: the committed prefix (surface, 0L biome seed) and the
        // vanilla-faithful prefix (region biome seed + applyCarvers shape).
        report("noise+surface (committed)", compare(oracle,
                prefix(driver, cx, cz, 0, false).get(ChunkPos.pack(cx, cz)), cx, cz));
        report("noise+surfaceVanilla", compare(oracle,
                prefix(driver, cx, cz, 0, true).get(ChunkPos.pack(cx, cz)), cx, cz));
        Map<Long, ChunkAccess> carved = prefix(driver, cx, cz, 2, true);
        report("noise+surfaceVanilla+carveVanilla", compare(oracle,
                carved.get(ChunkPos.pack(cx, cz)), cx, cz));

        // All features in row-major ring order, attributing changed cells in
        // the target chunk to the feature that changed them (diff after each
        // feature: ores bypass setBlock via BulkSectionAccess).
        Map<ResourceKey<PlacedFeature>, Integer> writes = new HashMap<>();
        long t0 = System.nanoTime();
        runFeatures(driver, carved, cx, cz, null, writes, ROW_MAJOR);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Result all = compare(oracle, carved.get(ChunkPos.pack(cx, cz)), cx, cz);
        report("+features(all, 3x3 row-major, " + ms + " ms incl. attribution)", all);
        propDetail(oracle, carved.get(ChunkPos.pack(cx, cz)), cx, cz);

        List<ResourceKey<PlacedFeature>> top = writes.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(20).map(Map.Entry::getKey).collect(Collectors.toList());
        System.out.println("top-20 feature keys by changed cells in chunk "
                + cx + "," + cz + " (" + writes.size() + " keys changed any cell):");
        for (ResourceKey<PlacedFeature> k : top) {
            System.out.println("  " + writes.get(k) + "  " + k.identifier());
        }
        Set<ResourceKey<PlacedFeature>> topSet = Set.copyOf(top);
        Map<Long, ChunkAccess> carved2 = prefix(driver, cx, cz, 2, true);
        runFeatures(driver, carved2, cx, cz, topSet::contains, null, ROW_MAJOR);
        report("+features(top-20 only, row-major)",
                compare(oracle, carved2.get(ChunkPos.pack(cx, cz)), cx, cz));

        // Ring-order sensitivity (trap 13): same blocks, different order.
        int[][][] orders = {COL_MAJOR, CENTER_FIRST, CENTER_LAST, REVERSE};
        String[] names = {"col-major", "center-first", "center-last", "reverse row-major"};
        for (int i = 0; i < orders.length; i++) {
            Map<Long, ChunkAccess> c = prefix(driver, cx, cz, 2, true);
            runFeatures(driver, c, cx, cz, null, null, orders[i]);
            report("+features(all, " + names[i] + ")", compare(oracle, c.get(ChunkPos.pack(cx, cz)), cx, cz));
            if (i == 0) {
                // Real stage (g2-step9-tick-design.md section 4): leaf
                // distance fixpoint, the state LeavesBlock ticks converge to
                // after FULL.
                int changed = HeadlessNoise.settleLeaves(c, cx, cz);
                report("  col-major + settleLeaves (" + changed + " cells)",
                        compare(oracle, c.get(ChunkPos.pack(cx, cz)), cx, cz));
            }
        }

        // Center-only features (neighbours stay at CARVERS): isolates what
        // the ring's cross-chunk writes contribute.
        Map<Long, ChunkAccess> carved3 = prefix(driver, cx, cz, 1, true);
        ChunkAccess c3 = carved3.get(ChunkPos.pack(cx, cz));
        HeadlessNoise.features(driver, c3, new HeadlessNoise.RegionView(driver,
                new ChunkPos(cx, cz), carved3));
        report("+features(all, center only)", compare(oracle, c3, cx, cz));
    }

    private static final int[][] ROW_MAJOR = ring(false, 0);
    private static final int[][] COL_MAJOR = ring(true, 0);
    private static final int[][] CENTER_FIRST = ring(false, 1);
    private static final int[][] CENTER_LAST = ring(false, 2);
    private static final int[][] REVERSE = ring(false, 3);

    /** 3x3 offsets: mode 0 plain, 1 center first, 2 center last, 3 reversed. */
    private static int[][] ring(boolean colMajor, int mode) {
        List<int[]> out = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                out.add(colMajor ? new int[]{a, b} : new int[]{b, a});
            }
        }
        int[] center = out.remove(4);
        if (mode == 1) {
            out.add(0, center);
        } else if (mode == 2) {
            out.add(center);
        } else {
            out.add(4, center);
            if (mode == 3) {
                java.util.Collections.reverse(out);
            }
        }
        return out.toArray(new int[0][]);
    }

    /** First few property-only diffs, with both states, to classify them. */
    private static void propDetail(BlockState[] oracle, ChunkAccess chunk, int cx, int cz) {
        int shown = 0;
        Map<String, Integer> byProp = new HashMap<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < oracle.length; i++) {
            int y = HeadlessNoise.MIN_Y + ((i >> 12) << 4) + ((i >> 8) & 15);
            m.set(cx * 16 + (i & 15), y, cz * 16 + ((i >> 4) & 15));
            BlockState got = chunk.getBlockState(m);
            if (got != oracle[i] && got.getBlock() == oracle[i].getBlock()) {
                for (var prop : got.getProperties()) {
                    if (!got.getValue(prop).equals(oracle[i].getValue(prop))) {
                        byProp.merge(prop.getName(), 1, Integer::sum);
                    }
                }
                if (shown++ < 4) {
                    System.out.println("    prop diff at " + m + ": oracle " + oracle[i] + " ours " + got);
                }
            } else if (got != oracle[i] && shown < 8 && oracle[i].is(Blocks.DEEPSLATE)) {
                shown++;
                System.out.println("    diff at " + m + ": oracle " + oracle[i] + " ours " + got);
            }
        }
        System.out.println("    property diff counts: " + byProp);
    }

    /** Prefix on the (2r+1)^2 square; carvers only when r > 0 needs them. */
    private static Map<Long, ChunkAccess> prefix(HeadlessNoise.Driver driver, int cx, int cz,
            int r, boolean vanilla) {
        Map<Long, ChunkAccess> out = new LinkedHashMap<>();
        // Diagnostic-only whole-grid two-phase setup: the vanilla path's
        // SURFACE needs each chunk's own 3x3 biome neighbourhood at BIOMES
        // status, one ring wider than the (2r+1)^2 square itself, so
        // prepareBiomeGrid's margin map supplies that outer ring on demand.
        Map<ChunkPos, ChunkAccess> noiseMargin = new HashMap<>();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                ChunkAccess c;
                if (vanilla) {
                    Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(driver, x, z,
                            (bx, bz) -> out.get(ChunkPos.pack(bx, bz)), noiseMargin);
                    c = biomeGrid.get(new ChunkPos(x, z));
                    HeadlessNoise.surfaceVanilla(driver, c, biomeGrid);
                    if (r > 0) {
                        HeadlessNoise.carveVanilla(driver, c);
                    }
                } else {
                    c = HeadlessNoise.noise(driver, x, z);
                    HeadlessNoise.surface(driver, c);
                }
                ((ProtoChunk) c).setPersistedStatus(ChunkStatus.CARVERS);
                out.put(ChunkPos.pack(x, z), c);
                noiseMargin.remove(new ChunkPos(x, z));
            }
        }
        return out;
    }

    private static void runFeatures(HeadlessNoise.Driver driver, Map<Long, ChunkAccess> chunks,
            int cx, int cz, Predicate<ResourceKey<PlacedFeature>> filter,
            Map<ResourceKey<PlacedFeature>, Integer> writes, int[][] order) {
        ChunkAccess target = chunks.get(ChunkPos.pack(cx, cz));
        BlockState[] snap = writes == null ? null : snapshot(target, cx, cz);
        for (int[] o : order) {
            ChunkPos p = new ChunkPos(cx + o[0], cz + o[1]);
            HeadlessNoise.RegionView view = new HeadlessNoise.RegionView(driver, p, chunks);
            if (writes != null) {
                view.afterFeature = key -> {
                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                    int changed = 0;
                    for (int i = 0; i < snap.length; i++) {
                        cell(m, i, cx, cz);
                        BlockState now = target.getBlockState(m);
                        if (now != snap[i]) {
                            snap[i] = now;
                            changed++;
                        }
                    }
                    if (changed > 0) {
                        writes.merge(key, changed, Integer::sum);
                    }
                };
            }
            ChunkAccess c = chunks.get(p.pack());
            HeadlessNoise.features(driver, c, view, filter);
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
            int slot = i >> 12;
            int y = HeadlessNoise.MIN_Y + (slot << 4) + ((i >> 8) & 15);
            m.set(cx * 16 + (i & 15), y, cz * 16 + ((i >> 4) & 15));
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
        System.out.printf("%-48s same %d/%d = %.2f%%%n", label, r.same(), r.total(),
                100.0 * r.same() / r.total());
        List<Map.Entry<String, Integer>> top = new ArrayList<>(r.diffs().entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            System.out.println("    oracle->ours " + top.get(i).getKey() + ": " + top.get(i).getValue());
        }
    }
}
