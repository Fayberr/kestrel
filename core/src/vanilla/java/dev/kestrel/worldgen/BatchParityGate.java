package dev.kestrel.worldgen;

import dev.kestrel.loop.SectionStore;
import dev.kestrel.persist.BlockHash;
import dev.kestrel.persist.ChunkCodec;
import dev.kestrel.persist.Nbt;
import dev.kestrel.persist.RegionFile;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.ticks.ProtoChunkTicks;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Kestrel G2 P2/P3 formal gate at scale: cross-order parity over 1000+ chunks.
 *
 * <p>Generates a 32x32 = 1,024 target chunk grid with a 1-chunk border (34x34 = 1,156 prefix chunks).
 * Evaluates:
 * 1. P2 (post-surface/carvers) match rate against oracle probe captures.
 * 2. P3 (post-features) match rate against oracle region files (worldflat) and probe captures.
 * 3. Cross-order stability across 2 shuffled orders (Order 1 vs Order 2).
 * 4. First-diff and aggregate breakdown by block kind for mismatches.
 */
public final class BatchParityGate {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 4242424242L;
        Path regionDir = Path.of(args.length > 1 ? args[1]
                : System.getProperty("user.home") + "/kestrel-corpus/fullrun/worldflat/region");
        Path probe5colCsv = Path.of(args.length > 2 ? args[2]
                : System.getProperty("user.home") + "/kestrel-corpus/probe5col/hash-probe.csv");
        Path winpcProbeCsv = Path.of(args.length > 3 ? args[3] : "harness/tools/parity/fullrun/hash-probe-winpc.csv");
        Path outCsv = Path.of(args.length > 4 ? args[4] : "harness/tools/parity/fullrun/batch-parity-1024.csv");
        int targetHalf = args.length > 5 ? Integer.parseInt(args[5]) : 16;
        // Sole raw-export switch: the 7th positional argument. No system
        // property or "--export-raw=" alias: one way in avoids silently
        // acting on two conflicting sources.
        Path rawExportDir = args.length > 6 && !args[6].isBlank() ? Path.of(args[6]) : null;
        Path rawExportTmpDir = null;

        System.out.println("=== Kestrel G2 P2/P3 Formal Gate at Scale (1000+ chunks) ===");
        System.out.println("Seed:          " + seed);
        System.out.println("Region dir:    " + regionDir);
        System.out.println("Probe5col CSV: " + probe5colCsv);
        System.out.println("bench PC CSV:     " + winpcProbeCsv);
        System.out.println("Output CSV:    " + outCsv);
        if (rawExportDir != null) {
            System.out.println("Raw export:    " + rawExportDir);
            // Fail closed on a stale/partial prior run rather than silently
            // mixing old and new files under the same directory.
            if (Files.exists(rawExportDir)) {
                throw new IllegalStateException(
                        "Raw export directory already exists, refusing to mix stale output: " + rawExportDir);
            }
            rawExportTmpDir = rawExportDir.resolveSibling(rawExportDir.getFileName() + ".tmp-" + ProcessHandle.current().pid());
            Files.createDirectories(rawExportTmpDir);
            // Any abnormal exit (validation failure, exception, kill)
            // between here and the final Files.move must not leave a
            // half-written temp directory behind; the hook is a no-op once
            // the move has renamed it away.
            Path cleanupTarget = rawExportTmpDir;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursiveQuietly(cleanupTarget)));
        }

        HeadlessNoise.Driver driver = HeadlessNoise.load(seed);
        PalettedContainerFactory factory = PalettedContainerFactory.create(driver.access());

        // Target grid: 32x32 = 1,024 chunks: -16 <= x <= 15, -16 <= z <= 15
        int minTargetX = -targetHalf, maxTargetX = targetHalf;
        int minTargetZ = -targetHalf, maxTargetZ = targetHalf;
        int targetCount = (targetHalf * 2 + 1) * (targetHalf * 2 + 1);
        List<ChunkPos> targets = new ArrayList<>(targetCount);
        for (int x = minTargetX; x <= maxTargetX; x++) {
            for (int z = minTargetZ; z <= maxTargetZ; z++) {
                targets.add(new ChunkPos(x, z));
            }
        }
        System.out.println("Target chunks count: " + targets.size() + " (range [" + minTargetX + ".." + maxTargetX + "] x [" + minTargetZ + ".." + maxTargetZ + "])");

        // Border grid: 34x34 = 1,156 chunks: -17 <= x <= 16, -17 <= z <= 16
        int minBorderX = minTargetX - 1, maxBorderX = maxTargetX + 1;
        int minBorderZ = minTargetZ - 1, maxBorderZ = maxTargetZ + 1;
        int totalPrefix = (maxBorderX - minBorderX + 1) * (maxBorderZ - minBorderZ + 1);
        System.out.println("Prefix grid count:   " + totalPrefix + " (range [" + minBorderX + ".." + maxBorderX + "] x [" + minBorderZ + ".." + maxBorderZ + "])");

        // Load Oracle probe data
        Map<Long, String> p5P2Blocks = new HashMap<>();
        Map<Long, String> p5P2Full = new HashMap<>();
        Map<Long, String> p5P3Blocks = new HashMap<>();
        Map<Long, String> p5P3Full = new HashMap<>();
        if (Files.exists(probe5colCsv)) {
            try (BufferedReader br = new BufferedReader(new FileReader(probe5colCsv.toFile()))) {
                String line = br.readLine(); // header
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length >= 5) {
                        int cx = Integer.parseInt(parts[0]);
                        int cz = Integer.parseInt(parts[1]);
                        String cut = parts[2];
                        String full = parts[3];
                        String blocks = parts[4];
                        long k = ChunkPos.pack(cx, cz);
                        if ("P2".equals(cut)) {
                            p5P2Full.put(k, full);
                            p5P2Blocks.put(k, blocks);
                        } else if ("P3".equals(cut)) {
                            p5P3Full.put(k, full);
                            p5P3Blocks.put(k, blocks);
                        }
                    }
                }
            }
            System.out.println("Loaded probe5col oracle rows: P2=" + p5P2Blocks.size() + ", P3=" + p5P3Blocks.size());
        }

        Map<Long, String> winP2Full = new HashMap<>();
        Map<Long, String> winP3Full = new HashMap<>();
        if (Files.exists(winpcProbeCsv)) {
            try (BufferedReader br = new BufferedReader(new FileReader(winpcProbeCsv.toFile()))) {
                String line = br.readLine(); // header
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length >= 4) {
                        int cx = Integer.parseInt(parts[0]);
                        int cz = Integer.parseInt(parts[1]);
                        String cut = parts[2];
                        String full = parts[3];
                        long k = ChunkPos.pack(cx, cz);
                        if ("P2".equals(cut)) {
                            winP2Full.put(k, full);
                        } else if ("P3".equals(cut)) {
                            winP3Full.put(k, full);
                        }
                    }
                }
            }
            System.out.println("Loaded bench PC full-state oracle rows: P2=" + winP2Full.size() + ", P3=" + winP3Full.size());
        }

        // Preflight: a parity gate with no oracle is not a passing gate, it is a
        // broken measurement. Mojang's RegionFile.open CREATES an empty 8 KB
        // region file on a missing path, so an absent corpus silently becomes
        // "every chunk mismatches" complete with a confident-looking report.
        // That masked a hash defect for four days (2026-09-14 to 2026-09-18).
        // Fail loudly and early instead. The corpus is not in git; on a fresh
        // machine fetch it from the Pi:
        //   rsync -a -e "ssh -p 2222" --exclude logs \
        //     <user>@<host>:~/kestrel-corpus/ ~/kestrel-corpus/
        if (!Files.isDirectory(regionDir)) {
            throw new IllegalStateException("Oracle region dir missing, refusing to score against nothing: "
                    + regionDir);
        }
        for (ChunkPos p : targets) {
            Path rPath = regionDir.resolve("r." + (p.x() >> 5) + "." + (p.z() >> 5) + ".mca");
            if (!Files.exists(rPath)) {
                throw new IllegalStateException("Oracle region file missing, refusing to score against nothing: "
                        + rPath);
            }
            if (Files.size(rPath) <= 8192L) {
                throw new IllegalStateException("Oracle region file holds no chunks (header-only, "
                        + Files.size(rPath) + " bytes), refusing to score against nothing: " + rPath);
            }
        }
        if (winP2Full.isEmpty()) {
            throw new IllegalStateException("bench PC full-state oracle loaded 0 rows, refusing to report a "
                    + "0% full-state match that only means the file is missing: " + winpcProbeCsv);
        }
        if (p5P2Blocks.isEmpty()) {
            throw new IllegalStateException("probe5col blocks-only oracle loaded 0 rows, refusing to report a "
                    + "0% blocks-only match that only means the file is missing: " + probe5colCsv);
        }

        // 1. PREFIX GENERATION (P2)
        System.out.println("\n[1/5] Generating P2 prefix (noise + surfaceVanilla + carveVanilla) on " + totalPrefix + " chunks...");
        long tPrefixStart = System.currentTimeMillis();
        Map<Long, ChunkAccess> p2BaseMap = new HashMap<>(totalPrefix);
        // Diagnostic-only whole-grid two-phase setup: SURFACE needs each
        // prefix chunk's own 3x3 biome neighbourhood at BIOMES status, one
        // ring wider than the prefix border itself, so prepareBiomeGrid's
        // margin map supplies that outer ring on demand (never promoted
        // into p2BaseMap: those chunks never run SURFACE/CARVERS here).
        // p2BaseMap is reassigned (nulled) below once cloned, so the
        // lambda below closes over a separate final reference instead.
        Map<Long, ChunkAccess> p2Building = p2BaseMap;
        Map<ChunkPos, ChunkAccess> noiseMargin = new HashMap<>();
        int prefixDone = 0;
        for (int x = minBorderX; x <= maxBorderX; x++) {
            for (int z = minBorderZ; z <= maxBorderZ; z++) {
                Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(driver, x, z,
                        (bx, bz) -> p2Building.get(ChunkPos.pack(bx, bz)), noiseMargin);
                ChunkAccess c = biomeGrid.get(new ChunkPos(x, z));
                HeadlessNoise.surfaceVanilla(driver, c, biomeGrid);
                HeadlessNoise.carveVanilla(driver, c);
                ((ProtoChunk) c).setPersistedStatus(ChunkStatus.CARVERS);
                p2BaseMap.put(ChunkPos.pack(x, z), c);
                noiseMargin.remove(new ChunkPos(x, z));
                prefixDone++;
                if (prefixDone % 250 == 0 || prefixDone == totalPrefix) {
                    long el = System.currentTimeMillis() - tPrefixStart;
                    System.out.printf("  Prefix progress: %d/%d (%.1f%%) in %d ms (%.1f ms/chunk)%n",
                            prefixDone, totalPrefix, 100.0 * prefixDone / totalPrefix, el, (double) el / prefixDone);
                }
            }
        }
        long tPrefixEnd = System.currentTimeMillis();
        System.out.printf("P2 Prefix completed in %.2f s (%.1f ms/chunk)%n", (tPrefixEnd - tPrefixStart) / 1000.0,
                (double) (tPrefixEnd - tPrefixStart) / totalPrefix);

        // 2. EVALUATE P2 PARITY AGAINST ORACLE
        System.out.println("\n[2/5] Evaluating P2 match rate against Oracle across target chunks...");
        int p2BlocksChecked = 0, p2BlocksMatched = 0;
        int p2FullWinChecked = 0, p2FullWinMatched = 0;
        Map<Long, String> kestrelP2Blocks = new HashMap<>(targets.size());
        Map<Long, String> kestrelP2Full = new HashMap<>(targets.size());

        for (ChunkPos p : targets) {
            long k = p.pack();
            ChunkAccess c = p2BaseMap.get(k);

            // Compute blocks-only hash via dedicated SectionStore
            SectionStore p2Store = new SectionStore(HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT);
            HeadlessNoise.writeStore(driver, c, p2Store, p.x(), p.z());
            String blocksHex = BlockHash.chunkHex(p2Store, p.x(), p.z());
            kestrelP2Blocks.put(k, blocksHex);

            // Compute full-state hash (blocks + heightmaps, same FNV-1a
            // fold as the harness HashProbe but direct, no reflection: the
            // core source set cannot see harness/agent classes).
            String fullHex = fullStateHex(c);
            kestrelP2Full.put(k, fullHex);

            // Verify vs probe5col oracle blocks_hash if present
            if (p5P2Blocks.containsKey(k)) {
                p2BlocksChecked++;
                if (blocksHex.equalsIgnoreCase(p5P2Blocks.get(k))) {
                    p2BlocksMatched++;
                }
            }

            // Verify vs bench PC full-state hash
            if (winP2Full.containsKey(k)) {
                p2FullWinChecked++;
                if (fullHex.equalsIgnoreCase(winP2Full.get(k))) {
                    p2FullWinMatched++;
                }
            }
        }

        System.out.printf("P2 Blocks-Only Match (vs probe5col):      %d/%d = %.2f%%%n",
                p2BlocksMatched, p2BlocksChecked, p2BlocksChecked == 0 ? 0.0 : 100.0 * p2BlocksMatched / p2BlocksChecked);
        System.out.printf("P2 Full-State Match (vs bench PC fullrun):    %d/%d = %.2f%%%n",
                p2FullWinMatched, p2FullWinChecked, p2FullWinChecked == 0 ? 0.0 : 100.0 * p2FullWinMatched / p2FullWinChecked);

        // Open RegionFiles for worldflat
        Map<String, RegionFile> regionFiles = new HashMap<>();

        if (rawExportDir != null) {
            System.out.println("\n[Raw export] Validating oracle coverage for " + targets.size()
                    + " target chunks before generation/export...");
            for (ChunkPos p : targets) {
                int cx = p.x();
                int cz = p.z();
                int rx = cx >> 5;
                int rz = cz >> 5;
                String rfKey = rx + "." + rz;
                Path rPath = regionDir.resolve("r." + rx + "." + rz + ".mca");
                // Files.exists first: RegionFile.open creates a fresh empty
                // region file on a missing path, which would fabricate
                // source oracle data rather than reporting it absent.
                if (!Files.exists(rPath)) {
                    throw new IllegalStateException(
                            "Missing oracle region file, required for raw export: " + rPath);
                }
                RegionFile rf = regionFiles.computeIfAbsent(rfKey, key -> {
                    try {
                        return RegionFile.open(rPath);
                    } catch (Exception e) {
                        throw new RuntimeException("Cannot open region file: " + rPath, e);
                    }
                });
                if (!rf.hasChunk(cx & 31, cz & 31)) {
                    throw new IllegalStateException(
                            "Missing oracle chunk slot: (" + cx + ", " + cz + ") in region file " + rfKey);
                }
                Nbt.Tag.TCompound tag = rf.readChunk(cx & 31, cz & 31);
                String status = Nbt.getString(tag, "Status");
                if (!"minecraft:full".equals(status)) {
                    throw new IllegalStateException("Oracle chunk (" + cx + ", " + cz
                            + ") is not minecraft:full (raw export requires a fully-generated oracle): " + status);
                }
                int nbtX = Nbt.getInt(tag, "xPos");
                int nbtZ = Nbt.getInt(tag, "zPos");
                if (nbtX != cx || nbtZ != cz) {
                    throw new IllegalStateException("Oracle chunk coordinate mismatch: expected (" + cx + ", " + cz
                            + ") but NBT declares (" + nbtX + ", " + nbtZ + ") in region file " + rfKey);
                }
            }
            System.out.println("[Raw export] Oracle validation passed for all " + targets.size() + " target chunks.");
        }

        // 3. ORDER 1: SHUFFLED A (Seed 424242L)
        System.out.println("\n[3/5] Running FEATURES for Order 1 (Shuffled A, seed 424242L)...");
        List<ChunkPos> order1 = new ArrayList<>(targets);
        Collections.shuffle(order1, new Random(424242L));
        Map<Long, ChunkAccess> map1 = cloneMap(p2BaseMap, driver, factory);

        long tOrder1Start = System.currentTimeMillis();
        int feat1Done = 0;
        for (ChunkPos p : order1) {
            HeadlessNoise.RegionView view = new HeadlessNoise.RegionView(driver, p, map1);
            ChunkAccess c = map1.get(p.pack());
            HeadlessNoise.features(driver, c, view);
            ((ProtoChunk) c).setPersistedStatus(ChunkStatus.FEATURES);
            feat1Done++;
            if (feat1Done % 250 == 0 || feat1Done == targets.size()) {
                long el = System.currentTimeMillis() - tOrder1Start;
                System.out.printf("  Order 1 features: %d/%d (%.1f%%) in %d ms (%.1f ms/chunk)%n",
                        feat1Done, targets.size(), 100.0 * feat1Done / targets.size(), el, (double) el / feat1Done);
            }
        }
        long tOrder1End = System.currentTimeMillis();
        System.out.printf("Order 1 FEATURES completed in %.2f s (%.1f ms/chunk)%n",
                (tOrder1End - tOrder1Start) / 1000.0, (double) (tOrder1End - tOrder1Start) / targets.size());

        Map<Long, String> order1Blocks = new HashMap<>(targets.size());
        Map<Long, Integer> order1CellsMatch = new HashMap<>(targets.size());
        Map<Long, String> order1FirstDiff = new HashMap<>(targets.size());
        Map<Long, String> oracleBlocksMap = new HashMap<>(targets.size());
        Map<Long, BlockState[]> order1Snapshots = new HashMap<>();
        Map<Long, BlockState[]> oracleSnapshots = rawExportDir != null ? new HashMap<>() : null;
        Map<String, Integer> globalDiffsOrder1 = new HashMap<>();

        BlockState[] oracleCells = new BlockState[HeadlessNoise.HEIGHT * 256];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        int p3ExactHashMatchesO1 = 0;
        long totalOracleCells = 0;
        long totalMatchingCellsOrder1 = 0;

        for (ChunkPos p : targets) {
            int cx = p.x();
            int cz = p.z();
            long k = p.pack();

            ChunkAccess c1 = map1.get(k);
            SectionStore s1 = new SectionStore(HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT);
            HeadlessNoise.writeStore(driver, c1, s1, cx, cz);
            String o1Hash = BlockHash.chunkHex(s1, cx, cz);
            order1Blocks.put(k, o1Hash);

            // Read oracle chunk from region file
            int rx = cx >> 5;
            int rz = cz >> 5;
            String rfKey = rx + "." + rz;
            RegionFile rf = regionFiles.computeIfAbsent(rfKey, key -> {
                Path rPath = regionDir.resolve("r." + rx + "." + rz + ".mca");
                try {
                    return RegionFile.open(rPath);
                } catch (Exception e) {
                    throw new RuntimeException("Cannot open region file: " + rPath, e);
                }
            });

            Arrays.fill(oracleCells, Blocks.AIR.defaultBlockState());
            var byteBuf = rf.readChunk(cx & 31, cz & 31);
            if (byteBuf != null) {
                ChunkCodec.DecodedChunk d = ChunkCodec.decodeChunk(byteBuf, HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT, 0L);
                for (ChunkCodec.SlotCells s : d.sections()) {
                    System.arraycopy(s.cells(), 0, oracleCells, s.slot() * 4096, 4096);
                }
            } else if (rawExportDir != null) {
                throw new IllegalStateException("Missing oracle chunk: (" + cx + ", " + cz + ") in region file " + rfKey);
            }
            if (oracleSnapshots != null) {
                BlockState[] oCopy = new BlockState[oracleCells.length];
                System.arraycopy(oracleCells, 0, oCopy, 0, oracleCells.length);
                oracleSnapshots.put(k, oCopy);
            }

            // Compute oracle blocks-only hash
            SectionStore oStore = new SectionStore(HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT);
            for (int i = 0; i < oracleCells.length; i++) {
                int y = HeadlessNoise.MIN_Y + ((i >> 12) << 4) + ((i >> 8) & 15);
                m.set(cx * 16 + (i & 15), y, cz * 16 + ((i >> 4) & 15));
                oStore.set(m, oracleCells[i]);
            }
            String oracleBlocksHash = BlockHash.chunkHex(oStore, cx, cz);
            oracleBlocksMap.put(k, oracleBlocksHash);

            if (o1Hash.equalsIgnoreCase(oracleBlocksHash)) {
                p3ExactHashMatchesO1++;
            }

            int match1 = 0;
            String firstDiff1 = null;
            for (int i = 0; i < oracleCells.length; i++) {
                int y = HeadlessNoise.MIN_Y + ((i >> 12) << 4) + ((i >> 8) & 15);
                m.set(cx * 16 + (i & 15), y, cz * 16 + ((i >> 4) & 15));
                BlockState want = oracleCells[i];
                BlockState got1 = c1.getBlockState(m);

                if (got1 == want) {
                    match1++;
                } else {
                    String diffKey = want.getBlock().builtInRegistryHolder().key().identifier().getPath()
                            + " -> " + got1.getBlock().builtInRegistryHolder().key().identifier().getPath()
                            + (got1.getBlock() == want.getBlock() ? " (props)" : "");
                    globalDiffsOrder1.merge(diffKey, 1, Integer::sum);
                    if (firstDiff1 == null) {
                        firstDiff1 = diffKey;
                    }
                }
            }

            totalOracleCells += oracleCells.length;
            totalMatchingCellsOrder1 += match1;
            order1CellsMatch.put(k, match1);
            order1FirstDiff.put(k, firstDiff1 != null ? firstDiff1 : "none");

            // Save snapshot of order1 blocks for cross-order cell diffing
            order1Snapshots.put(k, snapshot(c1, cx, cz));
        }

        if (rawExportDir != null) {
            exportRawDataset(rawExportTmpDir.resolve("order1.json"), seed, "order1", "FEATURES", 424242L,
                    minTargetX, maxTargetX, minTargetZ, maxTargetZ, minBorderX, maxBorderX, minBorderZ, maxBorderZ,
                    targets, order1Snapshots);
            // The oracle is decoded straight from the vanilla-written region
            // files (validated minecraft:full above), never regenerated by
            // Kestrel: its true stage is FULL, not FEATURES.
            exportRawDataset(rawExportTmpDir.resolve("vanilla.json"), seed, "vanilla", "FULL", null,
                    minTargetX, maxTargetX, minTargetZ, maxTargetZ, minBorderX, maxBorderX, minBorderZ, maxBorderZ,
                    targets, oracleSnapshots);
        }

        // Release map1 memory
        map1.clear();
        map1 = null;
        System.gc();

        // 4. ORDER 2: SHUFFLED B (Seed 888888L)
        System.out.println("\n[4/5] Running FEATURES for Order 2 (Shuffled B, seed 888888L)...");
        List<ChunkPos> order2 = new ArrayList<>(targets);
        Collections.shuffle(order2, new Random(888888L));
        Map<Long, ChunkAccess> map2 = cloneMap(p2BaseMap, driver, factory);

        // Release p2BaseMap as it is no longer needed
        p2BaseMap.clear();
        p2BaseMap = null;
        System.gc();

        long tOrder2Start = System.currentTimeMillis();
        int feat2Done = 0;
        for (ChunkPos p : order2) {
            HeadlessNoise.RegionView view = new HeadlessNoise.RegionView(driver, p, map2);
            ChunkAccess c = map2.get(p.pack());
            HeadlessNoise.features(driver, c, view);
            ((ProtoChunk) c).setPersistedStatus(ChunkStatus.FEATURES);
            feat2Done++;
            if (feat2Done % 250 == 0 || feat2Done == targets.size()) {
                long el = System.currentTimeMillis() - tOrder2Start;
                System.out.printf("  Order 2 features: %d/%d (%.1f%%) in %d ms (%.1f ms/chunk)%n",
                        feat2Done, targets.size(), 100.0 * feat2Done / targets.size(), el, (double) el / feat2Done);
            }
        }
        long tOrder2End = System.currentTimeMillis();
        System.out.printf("Order 2 FEATURES completed in %.2f s (%.1f ms/chunk)%n",
                (tOrder2End - tOrder2Start) / 1000.0, (double) (tOrder2End - tOrder2Start) / targets.size());

        // 5. EVALUATE ORDER 2 VS ORACLE & CROSS-ORDER STABILITY
        System.out.println("\n[5/5] Evaluating Order 2 and Cross-Order Stability (Order 1 vs Order 2)...");
        Map<Long, String> order2Blocks = new HashMap<>(targets.size());
        Map<Long, Integer> order2CellsMatch = new HashMap<>(targets.size());
        Map<Long, Integer> crossOrderCellsMatch = new HashMap<>(targets.size());
        Map<Long, BlockState[]> order2Snapshots = rawExportDir != null ? new HashMap<>() : null;
        Map<String, Integer> globalDiffsCrossOrder = new HashMap<>();

        int p3ExactHashMatchesO2 = 0;
        long totalMatchingCellsOrder2 = 0;
        int crossOrderExactHashMatches = 0;
        long crossOrderTotalMatchingCells = 0;

        List<PerChunkResult> results = new ArrayList<>(targets.size());

        for (ChunkPos p : targets) {
            int cx = p.x();
            int cz = p.z();
            long k = p.pack();

            ChunkAccess c2 = map2.get(k);
            SectionStore s2 = new SectionStore(HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT);
            HeadlessNoise.writeStore(driver, c2, s2, cx, cz);
            String o2Hash = BlockHash.chunkHex(s2, cx, cz);
            order2Blocks.put(k, o2Hash);

            if (order2Snapshots != null) {
                order2Snapshots.put(k, snapshot(c2, cx, cz));
            }

            String oracleBlocksHash = oracleBlocksMap.get(k);
            if (o2Hash.equalsIgnoreCase(oracleBlocksHash)) {
                p3ExactHashMatchesO2++;
            }

            // Decode oracle chunk from region file again for order 2 cell comparison
            int rx = cx >> 5;
            int rz = cz >> 5;
            String rfKey = rx + "." + rz;
            RegionFile rf = regionFiles.get(rfKey);
            Arrays.fill(oracleCells, Blocks.AIR.defaultBlockState());
            var byteBuf = rf.readChunk(cx & 31, cz & 31);
            if (byteBuf != null) {
                ChunkCodec.DecodedChunk d = ChunkCodec.decodeChunk(byteBuf, HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT, 0L);
                for (ChunkCodec.SlotCells s : d.sections()) {
                    System.arraycopy(s.cells(), 0, oracleCells, s.slot() * 4096, 4096);
                }
            }

            int match2 = 0;
            for (int i = 0; i < oracleCells.length; i++) {
                int y = HeadlessNoise.MIN_Y + ((i >> 12) << 4) + ((i >> 8) & 15);
                m.set(cx * 16 + (i & 15), y, cz * 16 + ((i >> 4) & 15));
                BlockState want = oracleCells[i];
                BlockState got2 = c2.getBlockState(m);
                if (got2 == want) match2++;
            }
            totalMatchingCellsOrder2 += match2;
            order2CellsMatch.put(k, match2);

            // Cross-Order comparison (Order 1 vs Order 2)
            String o1Hash = order1Blocks.get(k);
            boolean crossOrderMatch = o1Hash.equalsIgnoreCase(o2Hash);
            int crossMatch;

            if (crossOrderMatch) {
                crossOrderExactHashMatches++;
                crossMatch = oracleCells.length; // 98,304
            } else {
                crossMatch = 0;
                BlockState[] o1Snap = order1Snapshots.get(k);
                for (int i = 0; i < oracleCells.length; i++) {
                    int y = HeadlessNoise.MIN_Y + ((i >> 12) << 4) + ((i >> 8) & 15);
                    m.set(cx * 16 + (i & 15), y, cz * 16 + ((i >> 4) & 15));
                    BlockState got1 = o1Snap[i];
                    BlockState got2 = c2.getBlockState(m);
                    if (got1 == got2) {
                        crossMatch++;
                    } else {
                        String crossDiffKey = got1.getBlock().builtInRegistryHolder().key().identifier().getPath()
                                + " <-> " + got2.getBlock().builtInRegistryHolder().key().identifier().getPath()
                                + (got1.getBlock() == got2.getBlock() ? " (props)" : "");
                        globalDiffsCrossOrder.merge(crossDiffKey, 1, Integer::sum);
                    }
                }
            }

            crossOrderTotalMatchingCells += crossMatch;
            crossOrderCellsMatch.put(k, crossMatch);

            int match1 = order1CellsMatch.get(k);
            results.add(new PerChunkResult(
                    cx, cz,
                    kestrelP2Blocks.get(k), p5P2Blocks.get(k), kestrelP2Full.get(k), winP2Full.get(k),
                    oracleBlocksHash, o1Hash, o2Hash,
                    match1, (double) match1 / oracleCells.length * 100.0,
                    match2, (double) match2 / oracleCells.length * 100.0,
                    crossMatch, (double) crossMatch / oracleCells.length * 100.0,
                    crossOrderMatch, order1FirstDiff.get(k)
            ));
        }

        if (rawExportDir != null) {
            exportRawDataset(rawExportTmpDir.resolve("order2.json"), seed, "order2", "FEATURES", 888888L,
                    minTargetX, maxTargetX, minTargetZ, maxTargetZ, minBorderX, maxBorderX, minBorderZ, maxBorderZ,
                    targets, order2Snapshots);
        }

        // Close region files
        for (RegionFile rf : regionFiles.values()) {
            rf.close();
        }

        if (rawExportDir != null) {
            // Publish atomically: only after all three files validated and
            // written does the export directory itself appear, so a reader
            // never observes a partially-written run.
            Files.move(rawExportTmpDir, rawExportDir);
            System.out.println("\n[Raw export] Published " + rawExportDir);
        }

        // Summary Calculations
        double p3CellMatchRateO1 = totalOracleCells == 0 ? 0.0 : (double) totalMatchingCellsOrder1 / totalOracleCells * 100.0;
        double p3CellMatchRateO2 = totalOracleCells == 0 ? 0.0 : (double) totalMatchingCellsOrder2 / totalOracleCells * 100.0;
        double crossOrderCellStability = totalOracleCells == 0 ? 0.0 : (double) crossOrderTotalMatchingCells / totalOracleCells * 100.0;
        double crossOrderHashStability = targets.isEmpty() ? 0.0 : (double) crossOrderExactHashMatches / targets.size() * 100.0;
        double p2BlocksRate = p2BlocksChecked == 0 ? 0.0 : (double) p2BlocksMatched / p2BlocksChecked * 100.0;
        double p2FullRate = p2FullWinChecked == 0 ? 0.0 : (double) p2FullWinMatched / p2FullWinChecked * 100.0;

        System.out.println("\n==========================================================================");
        System.out.println("            KESTREL G2 P2/P3 FORMAL GATE REPORT AT SCALE (1,024 CHUNKS)     ");
        System.out.println("==========================================================================");
        System.out.printf("P2 Match Rate (Blocks-Only vs probe5col 289 chunks):       %d/%d = %.2f%%%n",
                p2BlocksMatched, p2BlocksChecked, p2BlocksRate);
        System.out.printf("P2 Match Rate (Full-State vs bench PC fullrun 1,024 chunks):   %d/%d = %.2f%%%n",
                p2FullWinMatched, p2FullWinChecked, p2FullRate);
        System.out.printf("P3 Cell Parity Rate (Order 1 Shuffled A vs Oracle):        %.4f%%%n", p3CellMatchRateO1);
        System.out.printf("P3 Cell Parity Rate (Order 2 Shuffled B vs Oracle):        %.4f%%%n", p3CellMatchRateO2);
        System.out.printf("P3 Exact Hash Matches (Order 1 vs Oracle):                 %d/%d (%.2f%%)%n",
                p3ExactHashMatchesO1, targets.size(), 100.0 * p3ExactHashMatchesO1 / targets.size());
        System.out.printf("P3 Exact Hash Matches (Order 2 vs Oracle):                 %d/%d (%.2f%%)%n",
                p3ExactHashMatchesO2, targets.size(), 100.0 * p3ExactHashMatchesO2 / targets.size());
        System.out.printf("Cross-Order Cell Stability (Order 1 vs Order 2):           %.4f%%%n", crossOrderCellStability);
        System.out.printf("Cross-Order Chunk Hash Stability (Order 1 vs Order 2):     %d/%d = %.2f%%%n",
                crossOrderExactHashMatches, targets.size(), crossOrderHashStability);

        System.out.println("\nTop 15 Mismatch Kinds (Order 1 vs Oracle):");
        List<Map.Entry<String, Integer>> sortedDiffs = new ArrayList<>(globalDiffsOrder1.entrySet());
        sortedDiffs.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(15, sortedDiffs.size()); i++) {
            Map.Entry<String, Integer> e = sortedDiffs.get(i);
            System.out.printf("  %-48s : %8d cells (%.4f%% of all cells)%n",
                    e.getKey(), e.getValue(), (double) e.getValue() / totalOracleCells * 100.0);
        }

        System.out.println("\nTop 15 Diffs Between Orders (Order 1 vs Order 2 Shuffles):");
        List<Map.Entry<String, Integer>> sortedCrossDiffs = new ArrayList<>(globalDiffsCrossOrder.entrySet());
        sortedCrossDiffs.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(15, sortedCrossDiffs.size()); i++) {
            Map.Entry<String, Integer> e = sortedCrossDiffs.get(i);
            System.out.printf("  %-48s : %8d cells%n", e.getKey(), e.getValue());
        }

        // Write CSV
        Files.createDirectories(outCsv.getParent());
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outCsv.toFile()))) {
            bw.write("chunk_x,chunk_z,p2_blocks,p2_oracle_blocks,p2_blocks_match,p2_full,p2_oracle_full,p2_full_match,p3_oracle_blocks,p3_order1_blocks,p3_order1_match,p3_order1_cells_match,p3_order1_pct,p3_order2_blocks,p3_order2_cells_match,p3_order2_pct,cross_order_match,cross_order_cells_match,cross_order_pct,first_diff\n");
            for (PerChunkResult r : results) {
                boolean p2BMatch = r.p2OracleBlocks != null && r.p2Blocks.equalsIgnoreCase(r.p2OracleBlocks);
                boolean p2FMatch = r.p2OracleFull != null && r.p2Full.equalsIgnoreCase(r.p2OracleFull);
                boolean p3Match = r.p3OracleBlocks.equalsIgnoreCase(r.p3Order1Blocks);
                bw.write(String.format(Locale.ROOT, "%d,%d,%s,%s,%b,%s,%s,%b,%s,%s,%b,%d,%.4f,%s,%d,%.4f,%b,%d,%.4f,%s\n",
                        r.cx, r.cz,
                        r.p2Blocks, r.p2OracleBlocks != null ? r.p2OracleBlocks : "", p2BMatch,
                        r.p2Full, r.p2OracleFull != null ? r.p2OracleFull : "", p2FMatch,
                        r.p3OracleBlocks, r.p3Order1Blocks, p3Match,
                        r.order1CellsMatch, r.order1Pct,
                        r.p3Order2Blocks, r.order2CellsMatch, r.order2Pct,
                        r.crossOrderMatch, r.crossOrderCellsMatch, r.crossOrderPct,
                        r.firstDiff));
            }
        }
        System.out.println("\nResults CSV successfully written to: " + outCsv + " (" + results.size() + " rows)");
        long totalEvaluated = (long) p2BlocksChecked + p2FullWinChecked + (totalOracleCells > 0 ? totalOracleCells : 0);
        boolean hasMismatches = (p2BlocksChecked > p2BlocksMatched)
                || (p2FullWinChecked > p2FullWinMatched)
                || (totalOracleCells > 0 && totalMatchingCellsOrder1 < totalOracleCells)
                || (totalOracleCells > 0 && totalMatchingCellsOrder2 < totalOracleCells)
                || (totalOracleCells > 0 && crossOrderTotalMatchingCells < totalOracleCells)
                || (!targets.isEmpty() && (p3ExactHashMatchesO1 < targets.size()
                        || p3ExactHashMatchesO2 < targets.size()
                        || crossOrderExactHashMatches < targets.size()));

        if (totalEvaluated == 0) {
            System.err.println("\n[BatchParityGate] FAILED: 0 comparisons evaluated.");
            System.exit(1);
        } else if (hasMismatches) {
            System.err.println("\n[BatchParityGate] FAILED: Parity mismatches detected.");
            System.exit(1);
        } else {
            System.out.println("\n[BatchParityGate] PASSED: All evaluated comparisons matched.");
        }
    }

    private static BlockState[] snapshot(ChunkAccess chunk, int cx, int cz) {
        BlockState[] out = new BlockState[HeadlessNoise.HEIGHT * 256];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < out.length; i++) {
            int y = HeadlessNoise.MIN_Y + ((i >> 12) << 4) + ((i >> 8) & 15);
            m.set(cx * 16 + (i & 15), y, cz * 16 + ((i >> 4) & 15));
            out[i] = chunk.getBlockState(m);
        }
        return out;
    }

    private static Map<Long, ChunkAccess> cloneMap(Map<Long, ChunkAccess> source, HeadlessNoise.Driver driver, PalettedContainerFactory factory) {
        Map<Long, ChunkAccess> out = new HashMap<>(source.size());
        for (Map.Entry<Long, ChunkAccess> e : source.entrySet()) {
            ChunkAccess orig = e.getValue();
            LevelChunkSection[] oSecs = orig.getSections();
            LevelChunkSection[] cSecs = new LevelChunkSection[oSecs.length];
            for (int i = 0; i < oSecs.length; i++) {
                cSecs[i] = oSecs[i].copy();
            }
            ProtoChunk clone = new ProtoChunk(orig.getPos(), UpgradeData.EMPTY, cSecs,
                    new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), driver.height(), factory, null);
            clone.setPersistedStatus(ChunkStatus.CARVERS);
            out.put(e.getKey(), clone);
        }
        return out;
    }

    /**
     * Full-state chunk hash (blocks + primed heightmaps), direct port of the
     * harness {@code HashProbe} FNV-1a fold without reflection: FNV over
     * (x, z, per-cell block id or air-marker ^ sectionY, heightmap values).
     * Air-only sections fold a constant so empty chunks still hash stably.
     */
    // FNV-1a constants and byte-at-a-time mixers, ported verbatim from
    // dev.kestrel.harness.HashProbe. THE PORT MUST STAY BIT-EXACT: the bench PC
    // full-state oracle CSV was captured with HashProbe, so any divergence
    // here silently scores every chunk as a mismatch instead of reporting
    // real parity. That is exactly what happened between 759256b and the
    // 2026-09-18 repair - see the header comment on fullStateHex.
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static long mixInt(long acc, int val) {
        acc ^= (val & 0xFF);
        acc *= FNV_PRIME;
        acc ^= ((val >>> 8) & 0xFF);
        acc *= FNV_PRIME;
        acc ^= ((val >>> 16) & 0xFF);
        acc *= FNV_PRIME;
        acc ^= ((val >>> 24) & 0xFF);
        acc *= FNV_PRIME;
        return acc;
    }

    private static long mixLong(long acc, long val) {
        acc ^= (val & 0xFF);
        acc *= FNV_PRIME;
        acc ^= ((val >>> 8) & 0xFF);
        acc *= FNV_PRIME;
        acc ^= ((val >>> 16) & 0xFF);
        acc *= FNV_PRIME;
        acc ^= ((val >>> 24) & 0xFF);
        acc *= FNV_PRIME;
        acc ^= ((val >>> 32) & 0xFF);
        acc *= FNV_PRIME;
        acc ^= ((val >>> 40) & 0xFF);
        acc *= FNV_PRIME;
        acc ^= ((val >>> 48) & 0xFF);
        acc *= FNV_PRIME;
        acc ^= ((val >>> 56) & 0xFF);
        acc *= FNV_PRIME;
        return acc;
    }

    private static long mixString(long acc, String s) {
        if (s == null) {
            return acc;
        }
        for (int i = 0; i < s.length(); i++) {
            acc = mixInt(acc, s.charAt(i));
        }
        return acc;
    }

    /**
     * Full-state chunk hash, bit-exact with {@code dev.kestrel.harness.HashProbe.hashChunk}.
     *
     * <p>History worth keeping: commit {@code 759256b} (2026-09-14, six minutes after the
     * reference parity run was committed) dropped the {@code HashProbe} import because core
     * cannot see {@code harness/agent}, and inlined a *different* function in its place. That
     * replacement hashed {@code BuiltInRegistries.BLOCK.getId(state.getBlock())}, i.e. the
     * block id with every block-state property discarded, mixed position terms that omitted
     * lx/lz, folded air cells in rather than skipping them, and primed four fixed heightmaps
     * instead of reading the chunk's own. Against a HashProbe-captured oracle it could only
     * ever score 0%. It did, from 2026-09-14 until 2026-09-18, and nobody noticed because the
     * oracle corpus was absent locally so the gate had nothing to compare against anyway.
     *
     * <p>Core still cannot import the harness class, so this is a hand port. Keep it bit-exact.
     */
    static String fullStateHex(ChunkAccess chunk) {
        long acc = FNV_OFFSET;
        ChunkPos pos = chunk.getPos();
        int cx = pos.x();
        int cz = pos.z();
        int minY = chunk.getMinSectionY();
        LevelChunkSection[] sections = chunk.getSections();
        if (sections != null) {
            for (int si = 0; si < sections.length; si++) {
                LevelChunkSection s = sections[si];
                if (s == null) {
                    continue;
                }
                int sectionY = minY + si;
                if (s.hasOnlyAir()) {
                    acc = mixLong(acc, 0xA1A1A1A1L ^ sectionY);
                    continue;
                }
                int baseY = sectionY << 4;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int wx = cx * 16 + lx;
                        int wz = cz * 16 + lz;
                        for (int ly = 0; ly < 16; ly++) {
                            int y = baseY + ly;
                            BlockState st = s.getBlockState(lx, ly, lz);
                            int id = net.minecraft.world.level.block.Block.getId(st);
                            if (id == 0) {
                                continue; // Skip air blocks (sparse folding)
                            }
                            acc = mixInt(acc, wx);
                            acc = mixInt(acc, y);
                            acc = mixInt(acc, wz);
                            acc = mixInt(acc, id);
                        }
                    }
                }
            }
        }
        for (java.util.Map.Entry<net.minecraft.world.level.levelgen.Heightmap.Types,
                net.minecraft.world.level.levelgen.Heightmap> e : chunk.getHeightmaps()) {
            acc = mixString(acc, e.getKey().toString());
            long[] raw = e.getValue().getRawData();
            if (raw != null) {
                for (long l : raw) {
                    acc = mixLong(acc, l);
                }
            }
        }
        return String.format(java.util.Locale.ROOT, "%016x", acc);
    }

    private static void deleteRecursiveQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (Exception ignored) {
            // best-effort temp cleanup
        }
    }

    public static void exportRawDataset(
            Path outPath,
            long seed,
            String orderName,
            String stage,
            Long orderSeed,
            int minX, int maxX, int minZ, int maxZ,
            int marginMinX, int marginMaxX, int marginMinZ, int marginMaxZ,
            List<ChunkPos> targets,
            Map<Long, BlockState[]> snapshots
    ) throws Exception {
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        try (BufferedWriter bw = Files.newBufferedWriter(outPath)) {
            bw.write("{\n");
            bw.write("  \"format\": \"kestrel-raw-block-export-v1\",\n");
            bw.write("  \"seed\": \"" + seed + "\",\n");
            bw.write("  \"stage\": \"" + stage + "\",\n");
            bw.write("  \"order\": \"" + orderName + "\",\n");
            bw.write("  \"order_seed\": " + (orderSeed != null ? orderSeed : "null") + ",\n");
            bw.write("  \"min_y\": " + HeadlessNoise.MIN_Y + ",\n");
            bw.write("  \"height\": " + HeadlessNoise.HEIGHT + ",\n");
            bw.write("  \"coverage\": {\n");
            bw.write("    \"min_x\": " + minX + ",\n");
            bw.write("    \"max_x\": " + maxX + ",\n");
            bw.write("    \"min_z\": " + minZ + ",\n");
            bw.write("    \"max_z\": " + maxZ + ",\n");
            bw.write("    \"chunk_count\": " + targets.size() + "\n");
            bw.write("  },\n");
            // The 1-chunk prefix ring around coverage is generated only to
            // CARVERS (never run through FEATURES, see HeadlessNoise calls
            // in main()) and is never itself exported as a chunk entry;
            // recorded here so a consumer knows target-edge cells can be
            // missing a feature that would have originated in that ring.
            bw.write("  \"margin\": {\n");
            bw.write("    \"min_x\": " + marginMinX + ",\n");
            bw.write("    \"max_x\": " + marginMaxX + ",\n");
            bw.write("    \"min_z\": " + marginMinZ + ",\n");
            bw.write("    \"max_z\": " + marginMaxZ + ",\n");
            bw.write("    \"stage\": \"CARVERS\",\n");
            bw.write("    \"exported\": false\n");
            bw.write("  },\n");
            bw.write("  \"chunks\": [\n");

            List<ChunkPos> sortedTargets = new ArrayList<>(targets);
            sortedTargets.sort(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));

            int minSectionY = HeadlessNoise.MIN_Y >> 4;
            int numSections = HeadlessNoise.HEIGHT >> 4;

            for (int ci = 0; ci < sortedTargets.size(); ci++) {
                ChunkPos cp = sortedTargets.get(ci);
                int cx = cp.x();
                int cz = cp.z();
                BlockState[] cells = snapshots.get(cp.pack());
                if (cells == null) {
                    throw new IllegalStateException("Missing snapshot for chunk (" + cx + ", " + cz + ")");
                }

                bw.write("    {\n");
                bw.write("      \"x\": " + cx + ",\n");
                bw.write("      \"z\": " + cz + ",\n");
                bw.write("      \"xPos\": " + cx + ",\n");
                bw.write("      \"zPos\": " + cz + ",\n");
                bw.write("      \"default_block\": \"minecraft:air\",\n");
                bw.write("      \"sections\": [\n");

                for (int secIdx = 0; secIdx < numSections; secIdx++) {
                    int sectionY = minSectionY + secIdx;
                    int secOffset = secIdx * 4096;

                    Map<BlockState, Integer> paletteMap = new LinkedHashMap<>();
                    List<String> paletteNames = new ArrayList<>();
                    int[] indices = new int[4096];

                    for (int intra = 0; intra < 4096; intra++) {
                        BlockState st = cells[secOffset + intra];
                        if (st == null) {
                            st = Blocks.AIR.defaultBlockState();
                        }
                        Integer id = paletteMap.get(st);
                        if (id == null) {
                            id = paletteNames.size();
                            paletteMap.put(st, id);
                            paletteNames.add(formatBlockState(st));
                        }
                        indices[intra] = id;
                    }

                    bw.write("        {\n");
                    bw.write("          \"Y\": " + sectionY + ",\n");
                    bw.write("          \"palette\": [");
                    for (int pi = 0; pi < paletteNames.size(); pi++) {
                        if (pi > 0) bw.write(", ");
                        bw.write("\"" + paletteNames.get(pi) + "\"");
                    }
                    bw.write("]");

                    if (paletteNames.size() > 1) {
                        bw.write(",\n          \"data\": [");
                        for (int ii = 0; ii < 4096; ii++) {
                            if (ii > 0) bw.write(",");
                            bw.write(Integer.toString(indices[ii]));
                        }
                        bw.write("]\n");
                    } else {
                        bw.write("\n");
                    }

                    bw.write("        }" + (secIdx < numSections - 1 ? "," : "") + "\n");
                }

                bw.write("      ]\n");
                bw.write("    }" + (ci < sortedTargets.size() - 1 ? "," : "") + "\n");
            }

            bw.write("  ]\n");
            bw.write("}\n");
        }
    }

    public static String formatBlockState(BlockState state) {
        if (state == null) {
            return "unknown";
        }
        net.minecraft.world.level.block.Block block = state.getBlock();
        net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        String name = id == null ? "minecraft:air" : id.toString();
        java.util.Collection<net.minecraft.world.level.block.state.properties.Property<?>> props = state.getProperties();
        if (props == null || props.isEmpty()) {
            return name;
        }
        List<net.minecraft.world.level.block.state.properties.Property<?>> sorted = new ArrayList<>(props);
        sorted.sort(Comparator.comparing(net.minecraft.world.level.block.state.properties.Property::getName));
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (net.minecraft.world.level.block.state.properties.Property<?> p : sorted) {
            if (!first) sb.append(',');
            first = false;
            sb.append(p.getName()).append('=').append(serializeProperty(state, p));
        }
        sb.append(']');
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String serializeProperty(BlockState state, net.minecraft.world.level.block.state.properties.Property<?> prop) {
        net.minecraft.world.level.block.state.properties.Property raw = prop;
        return raw.getName(state.getValue(raw));
    }

    record PerChunkResult(
            int cx, int cz,
            String p2Blocks, String p2OracleBlocks, String p2Full, String p2OracleFull,
            String p3OracleBlocks, String p3Order1Blocks, String p3Order2Blocks,
            int order1CellsMatch, double order1Pct,
            int order2CellsMatch, double order2Pct,
            int crossOrderCellsMatch, double crossOrderPct,
            boolean crossOrderMatch, String firstDiff
    ) {}
}
