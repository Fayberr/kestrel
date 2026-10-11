package dev.kestrel.worldgen;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Diagnostic for a structures-on CARVERS mismatch captured at a FEATURES ENTER cut.
 *
 * <p>The Vanilla snapshot is CARVERS state. Kestrel is sampled at NOISE, after
 * SURFACE, and after CARVERS. For every final differing cell this prints the
 * four-state transition so a pre-FEATURES mismatch can be assigned to the first
 * Kestrel stage at which the final wrong value already exists. The capture is
 * assertion data only; it is never written back into Kestrel.
 *
 * <p>Args: traceDir [chunkX chunkZ]. Defaults to the known Q10 witness -2,2.
 */
public final class StructuresPrefixStageCheck {
    private StructuresPrefixStageCheck() {}

    private record VanillaSnapshot(int x, int z, int minY, int height, int[] ids) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException(
                    "StructuresPrefixStageCheck <trace-directory> [chunkX chunkZ]");
        }
        Path root = Path.of(args[0]);
        int cx = args.length == 3 ? Integer.parseInt(args[1]) : -2;
        int cz = args.length == 3 ? Integer.parseInt(args[2]) : 2;

        List<String> eventLines = Files.readAllLines(root.resolve("events.tsv"));
        if (eventLines.size() < 2) throw new IllegalStateException("empty events.tsv");
        String[] first = eventLines.get(1).split("\\t", -1);
        if (first.length != 11 || !"ENTER".equals(first[1])) {
            throw new IllegalStateException("first trace row is not a valid ENTER");
        }
        long seed = Long.parseLong(first[5]);
        String hash = null;
        String capturedStatus = null;
        for (String raw : first[10].split(";")) {
            String[] p = raw.split(",", 4);
            if (p.length == 4 && Integer.parseInt(p[0]) == cx && Integer.parseInt(p[1]) == cz) {
                capturedStatus = p[2];
                hash = p[3];
                break;
            }
        }
        if (hash == null) {
            throw new IllegalArgumentException(
                    "chunk " + cx + "," + cz + " is not in first ENTER dependency window");
        }
        if (!"minecraft:carvers".equals(capturedStatus)) {
            throw new IllegalStateException(
                    "witness must be captured at CARVERS, got " + capturedStatus);
        }

        VanillaSnapshot vanilla = read(root, hash);
        if (vanilla.x != cx || vanilla.z != cz) {
            throw new IllegalStateException("snapshot coordinate mismatch");
        }

        try (HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(seed)) {
            Map<Long, ChunkAccess> starts = new HashMap<>();
            Map<Long, ChunkAccess> real = new LinkedHashMap<>();

            // Materialize the target's entire 3x3 biome neighbourhood through
            // structures-on NOISE with one shared STARTS/REFERENCES cache.
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = cx + dx;
                    int z = cz + dz;
                    ChunkAccess c = HeadlessNoise.noiseWithStructures(structural, x, z, starts);
                    real.put(ChunkPos.pack(x, z), c);
                }
            }

            ChunkAccess target = real.get(ChunkPos.pack(cx, cz));
            int[] noise = snapshotIds(target, cx, cz);

            Map<ChunkPos, ChunkAccess> margin = new HashMap<>();
            Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(
                    structural.driver(), cx, cz,
                    (x, z) -> real.get(ChunkPos.pack(x, z)), margin);
            if (biomeGrid.get(new ChunkPos(cx, cz)) != target) {
                throw new AssertionError("surface biome grid replaced the real target");
            }
            HeadlessNoise.surfaceVanilla(structural.driver(), target, biomeGrid);
            int[] surface = snapshotIds(target, cx, cz);

            HeadlessNoise.carveVanilla(structural.driver(), target);
            ((ProtoChunk) target).setPersistedStatus(ChunkStatus.CARVERS);
            int[] carvers = snapshotIds(target, cx, cz);

            Map<String, Integer> transitions = new TreeMap<>();
            List<String> examples = new ArrayList<>();
            int finalDiffs = 0;
            int unchangedSinceNoise = 0;
            int changedBySurface = 0;
            int changedByCarvers = 0;

            for (int i = 0; i < vanilla.ids.length; i++) {
                if (carvers[i] == vanilla.ids[i]) continue;
                finalDiffs++;
                if (noise[i] == carvers[i]) unchangedSinceNoise++;
                if (noise[i] != surface[i]) changedBySurface++;
                if (surface[i] != carvers[i]) changedByCarvers++;

                String key = state(vanilla.ids[i]) + " | noise=" + state(noise[i])
                        + " | surface=" + state(surface[i])
                        + " | carvers=" + state(carvers[i]);
                transitions.merge(key, 1, Integer::sum);
                if (examples.size() < 40) {
                    BlockPos p = pos(i, cx, cz);
                    examples.add(p + " vanilla=" + state(vanilla.ids[i])
                            + " noise=" + state(noise[i])
                            + " surface=" + state(surface[i])
                            + " carvers=" + state(carvers[i]));
                }
            }

            System.out.println("STRUCTURES_PREFIX_STAGE witness=" + cx + "," + cz
                    + " seed=" + seed
                    + " capturedStatus=" + capturedStatus
                    + " finalDiffs=" + finalDiffs
                    + " unchangedSinceNoise=" + unchangedSinceNoise
                    + " changedBySurface=" + changedBySurface
                    + " changedByCarvers=" + changedByCarvers);
            for (Map.Entry<String, Integer> e : transitions.entrySet()) {
                System.out.println("TRANSITION " + e.getValue() + "x " + e.getKey());
            }
            for (String line : examples) {
                System.out.println("DIFF " + line);
            }
            if (finalDiffs == 0) {
                throw new AssertionError(
                        "known CARVERS witness unexpectedly became exact; refresh diagnosis");
            }
        }
    }

    private static VanillaSnapshot read(Path root, String hash) throws Exception {
        byte[] bytes;
        try (var in = new GZIPInputStream(
                Files.newInputStream(root.resolve("chunks/" + hash + ".bin.gz")))) {
            bytes = in.readAllBytes();
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4b465431) throw new IllegalStateException("snapshot format");
            int x = in.readInt();
            int z = in.readInt();
            int minY = in.readInt();
            int height = in.readInt();
            int[] ids = new int[height * 256];
            for (int i = 0; i < ids.length; i++) ids[i] = in.readInt();
            if (in.read() != -1) throw new IllegalStateException("trailing snapshot bytes");
            return new VanillaSnapshot(x, z, minY, height, ids);
        }
    }

    private static int[] snapshotIds(ChunkAccess chunk, int cx, int cz) {
        int[] out = new int[HeadlessNoise.HEIGHT * 256];
        for (int i = 0; i < out.length; i++) {
            out[i] = Block.getId(FeaturesTraceReplay.capturedState(chunk, pos(i, cx, cz)));
        }
        return out;
    }

    private static BlockPos pos(int i, int cx, int cz) {
        return new BlockPos(
                cx * 16 + (i & 15),
                HeadlessNoise.MIN_Y + (i >> 8),
                cz * 16 + ((i >> 4) & 15));
    }

    private static BlockState state(int id) {
        return Block.stateById(id);
    }
}
