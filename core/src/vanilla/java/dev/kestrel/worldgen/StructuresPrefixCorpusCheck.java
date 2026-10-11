package dev.kestrel.worldgen;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Corpus-scale Step-8 Tier-P gate for structures-on generation.
 *
 * <p>Consumes Vanilla HashProbe P1/P2 rows captured in the same disposable
 * structures-on run as the FEATURES trace. Kestrel regenerates the requested
 * rectangle through the real structures pipeline and compares the exact
 * full-state FNV hash at NOISE (P1) and CARVERS (P2). The oracle hashes are
 * assertions only; no captured state is written into Kestrel.
 *
 * <p>Args:
 * {@code <prefix-hash-probe.csv> <seed> <minX> <minZ> <width> <height> [orderSeed]}.
 * Without orderSeed the target order is row-major. With one it is deterministically
 * shuffled. Run this check with multiple seeds to prove order independence.
 */
public final class StructuresPrefixCorpusCheck {
    private StructuresPrefixCorpusCheck() {}

    private record Oracle(String p1Full, String p1Blocks, String p2Full, String p2Blocks) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 6 || args.length > 7) {
            throw new IllegalArgumentException(
                    "StructuresPrefixCorpusCheck <prefix-hash-probe.csv> <seed> "
                            + "<minX> <minZ> <width> <height> [orderSeed]");
        }

        Path csv = Path.of(args[0]);
        long seed = Long.parseLong(args[1]);
        int minX = Integer.parseInt(args[2]);
        int minZ = Integer.parseInt(args[3]);
        int width = Integer.parseInt(args[4]);
        int height = Integer.parseInt(args[5]);
        Long orderSeed = args.length == 7 ? Long.valueOf(args[6]) : null;

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height must be positive");
        }
        int targetCount = Math.multiplyExact(width, height);
        if (targetCount < 1000) {
            throw new IllegalArgumentException(
                    "Step-8 Tier-P corpus must contain >=1000 chunks, got " + targetCount);
        }

        List<ChunkPos> targets = new ArrayList<>(targetCount);
        Set<Long> targetKeys = new HashSet<>(targetCount * 2);
        for (int z = minZ; z < minZ + height; z++) {
            for (int x = minX; x < minX + width; x++) {
                ChunkPos pos = new ChunkPos(x, z);
                targets.add(pos);
                targetKeys.add(pos.pack());
            }
        }

        Map<Long, Oracle> oracle = readOracle(csv, targetKeys);
        if (oracle.size() != targetCount) {
            throw new AssertionError(
                    "P1/P2 oracle coverage mismatch: expected=" + targetCount
                            + " actual=" + oracle.size());
        }

        List<ChunkPos> order = new ArrayList<>(targets);
        String orderName = "row-major";
        if (orderSeed != null) {
            Collections.shuffle(order, new Random(orderSeed));
            orderName = "shuffle-" + orderSeed;
        }

        int p1Matched = 0;
        int p2Matched = 0;
        List<String> p1Diffs = new ArrayList<>();
        List<String> p2Diffs = new ArrayList<>();

        try (HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(seed)) {
            Map<Long, ChunkAccess> structureGrid = new HashMap<>();
            Map<ChunkPos, ChunkAccess> biomeMargin = new HashMap<>();

            // P1: every target through real STARTS/REFERENCES + Beardifier-backed NOISE.
            // A single shared starts cache mirrors Vanilla's structure dependency graph.
            for (ChunkPos pos : order) {
                ChunkAccess chunk = HeadlessNoise.noiseWithStructures(
                        structural, pos.x(), pos.z(), structureGrid);
                structureGrid.put(pos.pack(), chunk);

                String actual = BatchParityGate.fullStateHex(chunk);
                String expected = oracle.get(pos.pack()).p1Full();
                if (actual.equalsIgnoreCase(expected)) {
                    p1Matched++;
                } else if (p1Diffs.size() < 40) {
                    p1Diffs.add(pos.x() + "," + pos.z()
                            + " expected=" + expected + " actual=" + actual);
                }
            }

            // P2: SURFACE/CARVERS only read per-chunk/biome-prefix state. Bare
            // radius-8 STARTS dependencies must not leak into biome lookups.
            for (ChunkPos pos : order) {
                Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(
                        structural.driver(),
                        pos.x(),
                        pos.z(),
                        (x, z) -> {
                            long key = ChunkPos.pack(x, z);
                            return targetKeys.contains(key) ? structureGrid.get(key) : null;
                        },
                        biomeMargin);
                ChunkAccess chunk = structureGrid.get(pos.pack());
                if (chunk == null) {
                    throw new AssertionError("missing P1 target " + pos);
                }
                HeadlessNoise.surfaceVanilla(structural.driver(), chunk, biomeGrid);
                HeadlessNoise.carveVanilla(structural.driver(), chunk);
                ((ProtoChunk) chunk).setPersistedStatus(ChunkStatus.CARVERS);

                String actual = BatchParityGate.fullStateHex(chunk);
                String expected = oracle.get(pos.pack()).p2Full();
                if (actual.equalsIgnoreCase(expected)) {
                    p2Matched++;
                } else if (p2Diffs.size() < 40) {
                    p2Diffs.add(pos.x() + "," + pos.z()
                            + " expected=" + expected + " actual=" + actual);
                }

                HeadlessNoise.releaseNoiseChunk(chunk);
                biomeMargin.remove(pos);
            }
        }

        System.out.printf(
                Locale.ROOT,
                "STRUCTURES_PREFIX_CORPUS order=%s targets=%d "
                        + "P1=%d/%d (%.6f%%) P2=%d/%d (%.6f%%)%n",
                orderName,
                targetCount,
                p1Matched,
                targetCount,
                100.0 * p1Matched / targetCount,
                p2Matched,
                targetCount,
                100.0 * p2Matched / targetCount);

        for (String diff : p1Diffs) {
            System.out.println("P1_DIFF " + diff);
        }
        for (String diff : p2Diffs) {
            System.out.println("P2_DIFF " + diff);
        }

        if (p1Matched != targetCount || p2Matched != targetCount) {
            throw new AssertionError(
                    "Step-8 Tier-P mismatch order=" + orderName
                            + " P1=" + p1Matched + "/" + targetCount
                            + " P2=" + p2Matched + "/" + targetCount);
        }
        System.out.println("StructuresPrefixCorpusCheck: PASSED");
    }

    private static Map<Long, Oracle> readOracle(Path csv, Set<Long> targets) throws Exception {
        if (!Files.isRegularFile(csv)) {
            throw new IllegalArgumentException("missing P1/P2 oracle: " + csv);
        }

        record Mutable(String p1Full, String p1Blocks, String p2Full, String p2Blocks) {}
        Map<Long, String[]> rows = new HashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(csv)) {
            String header = reader.readLine();
            if (!"chunk_x,chunk_z,cut,hash,blocks_hash".equals(header)) {
                throw new IllegalArgumentException("unexpected HashProbe header: " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length != 5) {
                    throw new IllegalArgumentException("malformed HashProbe row: " + line);
                }
                int x = Integer.parseInt(f[0]);
                int z = Integer.parseInt(f[1]);
                long key = ChunkPos.pack(x, z);
                if (!targets.contains(key)) continue;
                if (!f[2].equals("P1") && !f[2].equals("P2")) continue;
                if (f[3].length() != 16 || f[4].length() != 16) {
                    throw new IllegalArgumentException("invalid HashProbe hash: " + line);
                }

                String[] slots = rows.computeIfAbsent(key, unused -> new String[4]);
                int base = f[2].equals("P1") ? 0 : 2;
                if (slots[base] != null) {
                    throw new IllegalArgumentException(
                            "duplicate " + f[2] + " oracle for chunk " + x + "," + z);
                }
                slots[base] = f[3];
                slots[base + 1] = f[4];
            }
        }

        Map<Long, Oracle> out = new HashMap<>();
        for (long key : targets) {
            String[] slots = rows.get(key);
            int x = (int) key;
            int z = (int) (key >> 32);
            if (slots == null
                    || slots[0] == null
                    || slots[1] == null
                    || slots[2] == null
                    || slots[3] == null) {
                throw new IllegalArgumentException(
                        "missing complete P1/P2 oracle for chunk " + x + "," + z);
            }
            out.put(key, new Oracle(slots[0], slots[1], slots[2], slots[3]));
        }
        return out;
    }
}
