package dev.kestrel.worldgen;

import dev.kestrel.persist.ChunkCodec;
import dev.kestrel.persist.RegionFile;

import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Control experiment for the cross-order parity question: diff two VANILLA
 * region directories generated from the same seed in different chunk orders.
 *
 * <p>Why this exists. BatchParityGate measures Kestrel's cross-order
 * stability (two shuffled FEATURES orders over the same grid) and reports
 * ~99.66% of cells stable, and we had been reading the 0.34% shortfall as a
 * Kestrel defect: "Kestrel disagrees with itself, therefore Kestrel is
 * nondeterministic and can never be block-exact against a fixed oracle."
 * That inference silently assumes vanilla scores 100% on the same
 * measurement. Nobody had checked. And there is concrete reason to doubt
 * it: preresearch/g2-feature-order-notes.md section 3 recorded vanilla
 * decorating a bulk-loaded area across three Worker-Main threads, with the
 * merged order set by thread scheduling, i.e. vanilla's own chunk order is
 * not fixed between runs either.
 *
 * <p>So this compares vanilla against vanilla. Feed it two region dirs from
 * {@code harness/tools/vanilla-order-determinism.py} runs that used one seed
 * and different forceload orders. If vanilla is order-independent this
 * prints 100.0000% cell parity and 100% exact chunks, and the Kestrel
 * shortfall is genuinely Kestrel's. If it prints less than 100%, then part
 * of the "gap to vanilla" is not a gap at all: it is a property of the
 * generator that vanilla also has, and chasing it to zero is chasing
 * something vanilla itself does not achieve.
 *
 * <p>Compares only chunks present in BOTH directories, since forceload's
 * ticket reach reaches past the forced rectangle by a varying margin
 * (notes section 4) and the outermost chunks differ in how far they got.
 *
 * <p>Args: regionDirA regionDirB [coreHalf]. With coreHalf, additionally
 * reports the subset within chunk square [-coreHalf, coreHalf-1]^2, which
 * is the fully-decorated interior and the honest headline number.
 */
public final class VanillaSelfParityCheck {

    private VanillaSelfParityCheck() {
    }

    private static final int CELLS = HeadlessNoise.HEIGHT * 256;

    /** Only chunks vanilla considers finished are comparable. */
    private static final String STATUS_FULL = "minecraft:full";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "usage: VanillaSelfParityCheck <regionDirA> <regionDirB> [coreHalf]");
        }
        Path dirA = Path.of(args[0]);
        Path dirB = Path.of(args[1]);
        int coreHalf = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        requireDir(dirA);
        requireDir(dirB);

        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Set<Long> inA = listChunks(dirA);
        Set<Long> inB = listChunks(dirB);
        System.out.println("=== Vanilla self-parity (same seed, different chunk order) ===");
        System.out.println("A: " + dirA + "  full chunks=" + inA.size());
        System.out.println("B: " + dirB + "  full chunks=" + inB.size());

        Set<Long> both = new TreeSet<>(inA);
        both.retainAll(inB);
        if (both.isEmpty()) {
            throw new IllegalStateException("No chunk present in both region dirs, nothing to "
                    + "compare. Refusing to report a vacuous 100%.");
        }
        System.out.println("Chunks present in both: " + both.size()
                + "  (A-only " + (inA.size() - both.size())
                + ", B-only " + (inB.size() - both.size()) + ")");

        Stats all = new Stats();
        // Depth bands, an independent cross-check on the tick confound.
        // Forceloaded chunks tick even with nobody online, so unless the run
        // froze randomTickSpeed, surface differences could be leaf decay or
        // grass rather than generation order. Nothing that random-ticks turns
        // stone into coal ore, tuff into sculk, or andesite into granite, so
        // a divergence in the DEEP band (below y=0) cannot be explained by
        // ticks and is generation order or nothing.
        long[] bandSame = new long[3];
        long[] bandTotal = new long[3];
        String[] bandName = {"DEEP    y[-64,0)", "MID     y[0,64)", "SURFACE y[64,320)"};
        long[] bdTotal = new long[8];
        long[] bdDiff = new long[8];
        long[] bdOreTotal = new long[8];
        long[] bdOreDiff = new long[8];
        // A ladder of nested interiors, not one box. A chunk can be
        // minecraft:full while a radius-1 neighbour is still at carvers, and
        // a feature rooted in that neighbour spills into it only once the
        // neighbour decorates. Chunks near the edge of what a run generated
        // can therefore legitimately differ just because one run stopped
        // earlier, which is an artifact of the probe, not order dependence.
        // If the mismatch rate holds up as the box shrinks well inside the
        // generated margin, the effect is real; if it collapses to zero, it
        // was edge contamination and this whole experiment says nothing.
        java.util.List<Integer> halves = new ArrayList<>();
        for (int h : new int[]{1, 2, 3, 4, 6, 8}) {
            if (coreHalf > 0 && h <= coreHalf) {
                halves.add(h);
            }
        }
        Map<Integer, Stats> cores = new java.util.LinkedHashMap<>();
        for (int h : halves) {
            cores.put(h, new Stats());
        }
        List<String> diffChunks = new ArrayList<>();

        for (long key : both) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            BlockState[] a = read(dirA, cx, cz);
            BlockState[] b = read(dirB, cx, cz);
            int same = 0;
            for (int i = 0; i < CELLS; i++) {
                int x = i & 15;
                int z = (i >> 4) & 15;
                int bd = Math.min(Math.min(x, 15 - x), Math.min(z, 15 - z));
                int y = HeadlessNoise.MIN_Y + ((i >> 12) << 4) + ((i >> 8) & 15);
                int band = y < 0 ? 0 : (y < 64 ? 1 : 2);
                bandTotal[band]++;
                bdTotal[bd]++;
                if (a[i] == b[i]) {
                    same++;
                    bandSame[band]++;
                    if (name(a[i]).contains("_ore")) {
                        bdOreTotal[bd]++;
                    }
                } else {
                    String nameA = name(a[i]);
                    String nameB = name(b[i]);
                    all.diffs.merge(nameA + " -> " + nameB, 1, Integer::sum);
                    bdDiff[bd]++;
                    if (nameA.contains("_ore") || nameB.contains("_ore")) {
                        bdOreTotal[bd]++;
                        bdOreDiff[bd]++;
                    }
                }
            }
            boolean exact = same == CELLS;
            all.add(same, exact);
            boolean isCore = coreHalf > 0 && cx >= -coreHalf && cx < coreHalf
                    && cz >= -coreHalf && cz < coreHalf;
            for (Map.Entry<Integer, Stats> e : cores.entrySet()) {
                int h = e.getKey();
                if (cx >= -h && cx < h && cz >= -h && cz < h) {
                    e.getValue().add(same, exact);
                }
            }
            if (!exact) {
                diffChunks.add("(" + cx + "," + cz + ")" + (isCore ? "*" : "")
                        + " " + (CELLS - same) + " cells");
            }
        }

        report("ALL overlapping full chunks", all);
        System.out.println("\nBy depth band (all overlapping full chunks):");
        for (int b = 0; b < 3; b++) {
            if (bandTotal[b] == 0) {
                continue;
            }
            System.out.printf("  %s : %d/%d = %.4f%%  (%d cells differ)%n", bandName[b],
                    bandSame[b], bandTotal[b], 100.0 * bandSame[b] / bandTotal[b],
                    bandTotal[b] - bandSame[b]);
        }

        int[] uniformCells = {60, 52, 44, 36, 28, 20, 12, 4};
        long totalBdDiff = 0;
        long totalOreDiff = 0;
        for (int bd = 0; bd < 8; bd++) {
            totalBdDiff += bdDiff[bd];
            totalOreDiff += bdOreDiff[bd];
        }

        System.out.println("\n  Border-distance histogram (all differing cells):");
        for (int bd = 0; bd < 8; bd++) {
            double pct = bdTotal[bd] > 0 ? (100.0 * bdDiff[bd] / bdTotal[bd]) : 0.0;
            double shareObs = totalBdDiff > 0 ? (100.0 * bdDiff[bd] / totalBdDiff) : 0.0;
            double shareExp = 100.0 * uniformCells[bd] / 256.0;
            double ratio = shareExp > 0 ? (shareObs / shareExp) : 0.0;
            System.out.printf("    bd=%d : %d differ / %d cells = %.4f%%   share of all diffs %.2f%%   uniform %.2f%%   ratio %.2fx%n",
                    bd, bdDiff[bd], bdTotal[bd], pct, shareObs, shareExp, ratio);
        }

        System.out.println("\n  Border-distance histogram (cells where either side is an ore):");
        for (int bd = 0; bd < 8; bd++) {
            double pct = bdOreTotal[bd] > 0 ? (100.0 * bdOreDiff[bd] / bdOreTotal[bd]) : 0.0;
            double shareObs = totalOreDiff > 0 ? (100.0 * bdOreDiff[bd] / totalOreDiff) : 0.0;
            double shareExp = 100.0 * uniformCells[bd] / 256.0;
            double ratio = shareExp > 0 ? (shareObs / shareExp) : 0.0;
            System.out.printf("    bd=%d : %d differ / %d cells = %.4f%%   share of all diffs %.2f%%   uniform %.2f%%   ratio %.2fx%n",
                    bd, bdOreDiff[bd], bdOreTotal[bd], pct, shareObs, shareExp, ratio);
        }

        double ratioAll0 = (100.0 * uniformCells[0] / 256.0) > 0 && totalBdDiff > 0
                ? (100.0 * bdDiff[0] / totalBdDiff) / (100.0 * uniformCells[0] / 256.0) : 0.0;
        double ratioAll1 = (100.0 * uniformCells[1] / 256.0) > 0 && totalBdDiff > 0
                ? (100.0 * bdDiff[1] / totalBdDiff) / (100.0 * uniformCells[1] / 256.0) : 0.0;
        double ratioAll6 = (100.0 * uniformCells[6] / 256.0) > 0 && totalBdDiff > 0
                ? (100.0 * bdDiff[6] / totalBdDiff) / (100.0 * uniformCells[6] / 256.0) : 0.0;
        double ratioAll7 = (100.0 * uniformCells[7] / 256.0) > 0 && totalBdDiff > 0
                ? (100.0 * bdDiff[7] / totalBdDiff) / (100.0 * uniformCells[7] / 256.0) : 0.0;

        // Report the gradient, do not editorialize it. The first version of
        // this block printed "diffs cluster at low border distance" whenever
        // the near-edge ratio merely exceeded the deep-interior one, which a
        // 1.07x vs 0.88x spread passes while being nearly flat. State the
        // spread and let the threshold decide the word.
        double edge = (ratioAll0 + ratioAll1) / 2.0;
        double core = (ratioAll6 + ratioAll7) / 2.0;
        double spread = core > 0 ? edge / core : 0.0;
        String strength = spread >= 2.0 ? "STRONG edge clustering"
                : spread >= 1.3 ? "moderate edge bias"
                : spread >= 1.05 ? "near-uniform, mild edge bias"
                : "uniform, no edge structure";
        System.out.println();
        System.out.printf("  VERDICT: %s. edge(bd 0-1)=%.2fx vs core(bd 6-7)=%.2fx, spread %.2fx%n",
                strength, edge, core, spread);
        System.out.println("  Note: neighbour feature writes reach a full chunk (radius 1 = 16");
        System.out.println("  blocks) while the deepest a cell sits from a border is 7, so no");
        System.out.println("  part of a chunk is out of spill range. A flat histogram is the");
        System.out.println("  expected shape; the mild edge bias just tracks how many of the 8");
        System.out.println("  neighbours can reach a given cell.");
        for (Map.Entry<Integer, Stats> e : cores.entrySet()) {
            int h = e.getKey();
            report("INTERIOR [-" + h + "," + (h - 1) + "]^2", e.getValue());
        }

        if (!diffChunks.isEmpty()) {
            System.out.println("\nChunks that differ (* = core), first 40 of " + diffChunks.size() + ":");
            for (int i = 0; i < Math.min(40, diffChunks.size()); i++) {
                System.out.println("  " + diffChunks.get(i));
            }
            List<Map.Entry<String, Integer>> top = new ArrayList<>(all.diffs.entrySet());
            top.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
            System.out.println("\nTop mismatch kinds (A -> B):");
            for (int i = 0; i < Math.min(15, top.size()); i++) {
                System.out.println("  " + top.get(i).getKey() + ": " + top.get(i).getValue());
            }
        }

        System.out.println();
        if (all.exact == all.chunks) {
            System.out.println("VERDICT: vanilla is order-independent on this seed and area. "
                    + "Kestrel's cross-order shortfall is Kestrel's own.");
        } else {
            System.out.println("VERDICT: vanilla is NOT order-independent. It disagrees with "
                    + "itself on " + (all.chunks - all.exact) + "/" + all.chunks + " chunks "
                    + "from the same seed. A cross-order shortfall in Kestrel is therefore not "
                    + "by itself evidence of a Kestrel defect.");
        }
    }

    private static void report(String label, Stats s) {
        if (s.chunks == 0) {
            System.out.println("\n" + label + ": no chunks");
            return;
        }
        System.out.println("\n" + label + ":");
        System.out.printf("  chunks compared   : %d%n", s.chunks);
        System.out.printf("  exact chunk match : %d/%d = %.2f%%%n",
                s.exact, s.chunks, 100.0 * s.exact / s.chunks);
        System.out.printf("  cell parity       : %d/%d = %.4f%%%n",
                s.sameCells, s.totalCells, 100.0 * s.sameCells / s.totalCells);
    }

    private static final class Stats {
        int chunks;
        int exact;
        long sameCells;
        long totalCells;
        final Map<String, Integer> diffs = new HashMap<>();

        void add(int same, boolean isExact) {
            chunks++;
            if (isExact) {
                exact++;
            }
            sameCells += same;
            totalCells += CELLS;
        }
    }

    private static String name(BlockState s) {
        return s.getBlock().builtInRegistryHolder().key().identifier().getPath();
    }

    private static void requireDir(Path p) {
        if (!Files.isDirectory(p)) {
            throw new IllegalStateException("Region directory missing, refusing to compare against "
                    + "a fabricated empty world: " + p);
        }
    }

    /**
     * Every FULLY GENERATED chunk under a region dir, as packed (x<<32)|z.
     *
     * <p>Status filtering is not optional here. Stopping the server flushes
     * every chunk the generator had in flight, not just the finished ones: a
     * 16x16 forceload left 1,764 chunks on disk of which only a few hundred
     * were minecraft:full, the rest sitting at structure_starts, biomes,
     * carvers or initialize_light. Those partial chunks legitimately differ
     * between two runs because generation stopped at a different point, so
     * comparing them would manufacture a divergence that says nothing about
     * order independence. Only minecraft:full means "vanilla is done with
     * this chunk and this is its final content".
     */
    private static Set<Long> listChunks(Path dir) throws Exception {
        Set<Long> out = new HashSet<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                String n = p.getFileName().toString();
                if (!n.startsWith("r.") || !n.endsWith(".mca")) {
                    continue;
                }
                String[] parts = n.split("\\.");
                if (parts.length != 4) {
                    continue;
                }
                int rx = Integer.parseInt(parts[1]);
                int rz = Integer.parseInt(parts[2]);
                RegionFile rf = RegionFile.open(p);
                try {
                    for (int lx = 0; lx < 32; lx++) {
                        for (int lz = 0; lz < 32; lz++) {
                            if (!rf.hasChunk(lx, lz)) {
                                continue;
                            }
                            String status;
                            try {
                                status = dev.kestrel.persist.Nbt.getString(
                                        rf.readChunk(lx, lz), "Status");
                            } catch (Exception e) {
                                continue;
                            }
                            if (!STATUS_FULL.equals(status)) {
                                continue;
                            }
                            long cx = rx * 32L + lx;
                            long cz = rz * 32L + lz;
                            out.add((cx << 32) | (cz & 0xFFFFFFFFL));
                        }
                    }
                } finally {
                    rf.close();
                }
            }
        }
        return out;
    }

    private static BlockState[] read(Path dir, int cx, int cz) throws Exception {
        BlockState[] cells = new BlockState[CELLS];
        java.util.Arrays.fill(cells, Blocks.AIR.defaultBlockState());
        Path region = dir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        if (!Files.exists(region)) {
            throw new IllegalStateException("Region file missing for chunk (" + cx + "," + cz
                    + "), refusing to invent it: " + region);
        }
        RegionFile rf = RegionFile.open(region);
        try {
            if (!rf.hasChunk(cx & 31, cz & 31)) {
                throw new IllegalStateException("Region file has no chunk (" + cx + "," + cz
                        + "), refusing to invent it: " + region);
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
}
