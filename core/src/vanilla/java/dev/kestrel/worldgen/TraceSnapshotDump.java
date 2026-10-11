package dev.kestrel.worldgen;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Block;

/**
 * Diagnostic: every recorded snapshot of one chunk in a trace, in event order,
 * with the cells that changed between consecutive snapshots.
 *
 * <p>This is what turns "vanilla wrote air here" into "vanilla's cell held X at
 * the moment the feature ran". The snapshots are vanilla's own post-stage
 * states, so the diff between two consecutive ones is exactly what the stage in
 * between wrote into that chunk, whoever its origin was.
 *
 * <p>Args: traceDir chunkX chunkZ [minY] [maxY] [x:z,x:z,...]
 * The optional cells are printed for every snapshot even when unchanged.
 */
public final class TraceSnapshotDump {

    private record Shot(long seq, String phase, int originX, int originZ, String status, String hash) {
    }

    private record Snap(int minY, int height, int[] ids) {
        int at(int x, int y, int z) {
            int index = (y - minY) * 256 + ((z & 15) << 4) + (x & 15);
            return index < 0 || index >= ids.length ? -1 : ids[index];
        }
    }

    private TraceSnapshotDump() {
    }

    private static String name(int id) {
        if (id < 0) {
            return "<oob>";
        }
        try {
            return "id" + id + "=" + Block.stateById(id);
        } catch (RuntimeException e) {
            return "id" + id + "=<unmapped>";
        }
    }

    public static void main(String[] args) throws Exception {
        // Bootstrap the block-state registry so ids can be named; the generated
        // world itself is irrelevant to this dump.
        HeadlessNoise.load(0L);
        Path root = Path.of(args[0]);
        int cx = Integer.parseInt(args[1]);
        int cz = Integer.parseInt(args[2]);
        int minY = args.length > 3 ? Integer.parseInt(args[3]) : -64;
        int maxY = args.length > 4 ? Integer.parseInt(args[4]) : 320;
        List<int[]> cells = new ArrayList<>();
        if (args.length > 5) {
            for (String part : args[5].split(",")) {
                String[] triple = part.split(":");
                cells.add(new int[]{Integer.parseInt(triple[0]), Integer.parseInt(triple[1]),
                        Integer.parseInt(triple[2])});
            }
        }
        String filter = args.length > 6 ? args[6] : null;

        List<Shot> shots = new ArrayList<>();
        List<String> lines = Files.readAllLines(root.resolve("events.tsv"));
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split("\t");
            if (f.length < 11) {
                continue;
            }
            for (String ref : f[10].split(";")) {
                String[] parts = ref.split(",");
                if (parts.length < 4 || Integer.parseInt(parts[0]) != cx
                        || Integer.parseInt(parts[1]) != cz) {
                    continue;
                }
                shots.add(new Shot(Long.parseLong(f[0]), f[1],
                        Integer.parseInt(f[3]), Integer.parseInt(f[4]), parts[2], parts[3]));
            }
        }
        System.out.println("chunk " + cx + "," + cz + " referenced by " + shots.size()
                + " event refs");

        Map<String, Snap> decoded = new LinkedHashMap<>();
        Snap previous = null;
        String previousWhere = null;
        for (Shot shot : shots) {
            Snap snap = decoded.get(shot.hash());
            if (snap == null) {
                if (!Files.exists(root.resolve("chunks/" + shot.hash() + ".bin.gz"))) {
                    System.out.println("SNAP seq=" + shot.seq() + " hash="
                            + shot.hash().substring(0, 8) + " MISSING-FILE");
                    continue;
                }
                snap = read(root, shot.hash());
                decoded.put(shot.hash(), snap);
            }
            boolean changed = previous != null && !shot.hash().equals(previousWhere);
            System.out.println("SNAP seq=" + shot.seq() + " phase=" + shot.phase()
                    + " origin=" + shot.originX() + "," + shot.originZ()
                    + " status=" + shot.status() + " hash=" + shot.hash().substring(0, 8)
                    + (changed ? " CHANGED" : ""));
            for (int[] cell : cells) {
                System.out.println("  CELL " + cell[0] + "," + cell[1] + "," + cell[2] + " -> "
                        + name(snap.at(cell[0], cell[1], cell[2])));
            }
            if (filter != null) {
                int total = 0;
                int shown = 0;
                for (int y = minY; y <= maxY; y++) {
                    for (int dz = 0; dz < 16; dz++) {
                        for (int dx = 0; dx < 16; dx++) {
                            int id = snap.at(cx * 16 + dx, y, cz * 16 + dz);
                            if (id < 0) {
                                continue;
                            }
                            String state = name(id);
                            if (!state.contains(filter)) {
                                continue;
                            }
                            total++;
                            if (shown++ < 40) {
                                System.out.println("  HAS " + (cx * 16 + dx) + "," + y + ","
                                        + (cz * 16 + dz) + " " + state);
                            }
                        }
                    }
                }
                System.out.println("  COUNT " + filter + " = " + total);
            }
            if (changed) {
                int shown = 0;
                for (int y = minY; y <= maxY && shown < 400; y++) {
                    for (int dz = 0; dz < 16 && shown < 400; dz++) {
                        for (int dx = 0; dx < 16 && shown < 400; dx++) {
                            int before = previous.at(cx * 16 + dx, y, cz * 16 + dz);
                            int after = snap.at(cx * 16 + dx, y, cz * 16 + dz);
                            if (before != after) {
                                String beforeName = name(before);
                                String afterName = name(after);
                                if (filter != null && !beforeName.contains(filter)
                                        && !afterName.contains(filter)) {
                                    continue;
                                }
                                System.out.println("  CHG " + (cx * 16 + dx) + "," + y + ","
                                        + (cz * 16 + dz) + " " + beforeName + " -> " + afterName);
                                shown++;
                            }
                        }
                    }
                }
                if (shown == 0) {
                    System.out.println("  (no block change inside y " + minY + ".." + maxY + ")");
                }
            }
            previous = snap;
            previousWhere = shot.hash();
        }
    }

    private static Snap read(Path root, String hash) throws IOException {
        byte[] bytes;
        try (var in = new java.util.zip.GZIPInputStream(
                Files.newInputStream(root.resolve("chunks/" + hash + ".bin.gz")))) {
            bytes = in.readAllBytes();
        }
        try (var in = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4b465431) {
                throw new IOException("snapshot format " + hash);
            }
            in.readInt();
            in.readInt();
            int minY = in.readInt();
            int height = in.readInt();
            int[] ids = new int[height * 256];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = in.readInt();
            }
            return new Snap(minY, height, ids);
        }
    }
}
