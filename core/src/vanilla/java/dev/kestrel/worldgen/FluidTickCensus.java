package dev.kestrel.worldgen;

import dev.kestrel.persist.ChunkCodec;
import dev.kestrel.persist.Nbt;
import dev.kestrel.persist.RegionFile;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FluidTickCensus {

    private static final int CELLS = HeadlessNoise.HEIGHT * 256;

    private FluidTickCensus() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: FluidTickCensus <regionDir>");
        }

        Path regionDir = Path.of(args[0]);
        if (!Files.isDirectory(regionDir)) {
            throw new IllegalArgumentException("Region directory missing or not a directory: " + regionDir);
        }

        List<Path> mcaFiles = new ArrayList<>();
        try (var stream = Files.list(regionDir)) {
            for (Path p : stream.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith("r.") && n.endsWith(".mca") && n.split("\\.").length == 4) {
                    mcaFiles.add(p);
                }
            }
        }
        Collections.sort(mcaFiles);
        if (mcaFiles.isEmpty()) {
            throw new IllegalStateException("No .mca files found in region directory: " + regionDir);
        }

        int fullChunksScanned = 0;
        int chunksWithFluidTicks = 0;
        long totalFluidTicks = 0;
        int chunksWithBlockTicks = 0;
        long totalBlockTicks = 0;
        int maxFluidTicksInChunk = 0;

        Map<String, Long> fluidCounts = new HashMap<>();
        Map<String, Long> waterCounts = new HashMap<>();
        Map<String, Long> lavaCounts = new HashMap<>();

        BlockState[] cells = new BlockState[CELLS];
        BlockState air = Blocks.AIR.defaultBlockState();

        for (Path mcaPath : mcaFiles) {
            RegionFile rf = RegionFile.open(mcaPath);
            try {
                for (int lx = 0; lx < 32; lx++) {
                    for (int lz = 0; lz < 32; lz++) {
                        if (!rf.hasChunk(lx, lz)) {
                            continue;
                        }
                        Nbt.Tag.TCompound tag;
                        try {
                            tag = rf.readChunk(lx, lz);
                            if (tag == null) {
                                continue;
                            }
                            String status = Nbt.getString(tag, "Status");
                            if (!"minecraft:full".equals(status)) {
                                continue;
                            }
                        } catch (Exception e) {
                            continue;
                        }

                        fullChunksScanned++;

                        // Pending ticks
                        int fluidTicks = 0;
                        Nbt.Tag ftTag = Nbt.getOrNull(tag, "fluid_ticks");
                        if (ftTag instanceof Nbt.Tag.TList list) {
                            fluidTicks = list.items().size();
                        }
                        if (fluidTicks >= 1) {
                            chunksWithFluidTicks++;
                        }
                        totalFluidTicks += fluidTicks;
                        if (fluidTicks > maxFluidTicksInChunk) {
                            maxFluidTicksInChunk = fluidTicks;
                        }

                        int blockTicks = 0;
                        Nbt.Tag btTag = Nbt.getOrNull(tag, "block_ticks");
                        if (btTag instanceof Nbt.Tag.TList list) {
                            blockTicks = list.items().size();
                        }
                        if (blockTicks >= 1) {
                            chunksWithBlockTicks++;
                        }
                        totalBlockTicks += blockTicks;

                        // Fluid block census
                        try {
                            ChunkCodec.DecodedChunk d = ChunkCodec.decodeChunk(tag,
                                    HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT, 0L);
                            Arrays.fill(cells, air);
                            for (ChunkCodec.SlotCells s : d.sections()) {
                                System.arraycopy(s.cells(), 0, cells, s.slot() * 4096, 4096);
                            }
                            for (int i = 0; i < CELLS; i++) {
                                BlockState st = cells[i];
                                if (st.is(Blocks.WATER)) {
                                    String key = st.toString();
                                    fluidCounts.merge(key, 1L, Long::sum);
                                    waterCounts.merge(key, 1L, Long::sum);
                                } else if (st.is(Blocks.LAVA)) {
                                    String key = st.toString();
                                    fluidCounts.merge(key, 1L, Long::sum);
                                    lavaCounts.merge(key, 1L, Long::sum);
                                }
                            }
                        } catch (Exception e) {
                            // Skip corrupted chunk during decode
                        }
                    }
                }
            } finally {
                rf.close();
            }
        }

        if (fullChunksScanned == 0) {
            throw new IllegalStateException("Region directory contains no full chunks: " + regionDir);
        }

        System.out.println("=== Fluid and Tick Census ===");
        System.out.println("Region directory: " + regionDir);
        System.out.println("Full chunks scanned: " + fullChunksScanned);

        System.out.println("\nPending ticks:");
        System.out.printf("  Chunks with >= 1 pending fluid tick: %d (total pending fluid ticks: %d)%n",
                chunksWithFluidTicks, totalFluidTicks);
        System.out.printf("  Chunks with >= 1 pending block tick: %d (total pending block ticks: %d)%n",
                chunksWithBlockTicks, totalBlockTicks);
        System.out.printf("  Max pending fluid ticks in any single chunk: %d%n",
                maxFluidTicksInChunk);

        System.out.println("\nFluid block census (distinct states by count descending):");
        if (fluidCounts.isEmpty()) {
            System.out.println("  (none)");
        } else {
            List<Map.Entry<String, Long>> sortedFluids = new ArrayList<>(fluidCounts.entrySet());
            sortedFluids.sort((a, b) -> {
                int cmp = Long.compare(b.getValue(), a.getValue());
                return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
            });
            for (Map.Entry<String, Long> e : sortedFluids) {
                System.out.printf("  %s : %d%n", e.getKey(), e.getValue());
            }
        }

        long totalWater = 0;
        for (long count : waterCounts.values()) {
            totalWater += count;
        }
        List<Map.Entry<String, Long>> sortedWater = new ArrayList<>(waterCounts.entrySet());
        sortedWater.sort((a, b) -> {
            int cmp = Long.compare(b.getValue(), a.getValue());
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });
        long waterMostCommonCount = sortedWater.isEmpty() ? 0L : sortedWater.get(0).getValue();
        String waterMostCommonState = sortedWater.isEmpty() ? "none" : sortedWater.get(0).getKey();
        long waterNonCommon = totalWater - waterMostCommonCount;
        double waterNonCommonFraction = totalWater > 0 ? (double) waterNonCommon / totalWater : 0.0;

        long totalLava = 0;
        for (long count : lavaCounts.values()) {
            totalLava += count;
        }
        List<Map.Entry<String, Long>> sortedLava = new ArrayList<>(lavaCounts.entrySet());
        sortedLava.sort((a, b) -> {
            int cmp = Long.compare(b.getValue(), a.getValue());
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });
        long lavaMostCommonCount = sortedLava.isEmpty() ? 0L : sortedLava.get(0).getValue();
        String lavaMostCommonState = sortedLava.isEmpty() ? "none" : sortedLava.get(0).getKey();
        long lavaNonCommon = totalLava - lavaMostCommonCount;
        double lavaNonCommonFraction = totalLava > 0 ? (double) lavaNonCommon / totalLava : 0.0;

        System.out.println("\nFluid summaries:");
        System.out.printf("  Water: total cells = %d, most common state = %d (%s), non-most-common = %d (fraction: %.4f%%)%n",
                totalWater, waterMostCommonCount, waterMostCommonState, waterNonCommon, 100.0 * waterNonCommonFraction);
        System.out.printf("  Lava : total cells = %d, most common state = %d (%s), non-most-common = %d (fraction: %.4f%%)%n",
                totalLava, lavaMostCommonCount, lavaMostCommonState, lavaNonCommon, 100.0 * lavaNonCommonFraction);

        System.out.println();
        if (totalFluidTicks > 0) {
            System.out.printf("VERDICT: H1 SUPPORTED: oracle saved with fluids still in motion (total pending fluid ticks = %d)%n",
                    totalFluidTicks);
        } else if (waterNonCommonFraction > 0.05) {
            System.out.printf("VERDICT: H1 LIKELY: oracle water is largely non-source (total pending fluid ticks = %d, non-most-common water = %d/%d = %.4f%% > 5%%)%n",
                    totalFluidTicks, waterNonCommon, totalWater, 100.0 * waterNonCommonFraction);
        } else {
            System.out.printf("VERDICT: H2 SUPPORTED: oracle fluids are settled, so a Kestrel fluid gap is a real defect (total pending fluid ticks = %d, non-most-common water = %d/%d = %.4f%% <= 5%%)%n",
                    totalFluidTicks, waterNonCommon, totalWater, 100.0 * waterNonCommonFraction);
        }
    }
}
