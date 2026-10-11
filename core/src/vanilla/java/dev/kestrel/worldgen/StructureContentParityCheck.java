package dev.kestrel.worldgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;

/**
 * G2 structure-content parity proof against a real Vanilla FEATURES capture.
 *
 * <p>The Vanilla harness records entity and block-entity state for selected
 * FEATURES origins. This check compares only each origin's center chunk, so
 * content written earlier by neighboring FEATURES centers cannot contaminate
 * the proof.
 *
 * <p>Entity UUID is excluded because Entity construction assigns a process
 * UUID outside the deterministic worldgen random stream. Every other
 * serialized field remains exact, including id, Pos, LootTable and
 * LootTableSeed.
 */
public final class StructureContentParityCheck {
    private static final Pattern BLOCK_POS = Pattern.compile(
            "BlockPos\\{x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\}");
    private static final Pattern UUID_FIELD = Pattern.compile(
            "UUID:\\[I;[^\\]]+\\],?");
    private static int checks;

    private StructureContentParityCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException(
                    "StructureContentParityCheck <structure-data.tsv> [seed]");
        }
        Path capture = Path.of(args[0]);
        long seed = args.length == 2 ? Long.parseLong(args[1]) : 4242424242L;
        Map<Long, Expected> expected = readExpected(capture);

        int[][] origins = {{0, 6}, {-1, 6}};
        try (HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(seed)) {
            for (int[] origin : origins) {
                int x = origin[0];
                int z = origin[1];
                Expected row = expected.get(ChunkPos.pack(x, z));
                if (row == null) {
                    throw new AssertionError(
                            "missing Vanilla structure-content row for origin/chunk "
                                    + x + "," + z);
                }

                Map<Long, ChunkAccess> grid =
                        StructurePlacementCheck.prefix(structural, x, z);
                ChunkPos centerPos = new ChunkPos(x, z);
                ChunkAccess center = grid.get(centerPos.pack());
                if (!(center instanceof ProtoChunk proto)) {
                    throw new AssertionError("center is not ProtoChunk " + centerPos);
                }

                HeadlessNoise.RegionView view =
                        new HeadlessNoise.RegionView(
                                structural.driver(), centerPos, grid);
                HeadlessNoise.featuresWithStructures(
                        structural, center, view);

                List<String> actualEntities =
                        canonicalEntities(proto.getEntities().toString());
                eq(row.entities(), actualEntities,
                        "entity NBT " + x + "," + z);

                Set<String> actualPending =
                        positions(proto.getBlockEntityNbts().keySet());
                eq(row.pendingPositions(), actualPending,
                        "pending block-entity positions " + x + "," + z);
                for (Map.Entry<BlockPos, CompoundTag> entry
                        : proto.getBlockEntityNbts().entrySet()) {
                    CompoundTag tag = entry.getValue();
                    eq("DUMMY", tag.getStringOr("id", ""),
                            "pending block-entity id " + entry.getKey());
                    eq(entry.getKey().getX(),
                            tag.getIntOr("x", Integer.MIN_VALUE),
                            "pending block-entity x " + entry.getKey());
                    eq(entry.getKey().getY(),
                            tag.getIntOr("y", Integer.MIN_VALUE),
                            "pending block-entity y " + entry.getKey());
                    eq(entry.getKey().getZ(),
                            tag.getIntOr("z", Integer.MIN_VALUE),
                            "pending block-entity z " + entry.getKey());
                }

                Set<String> actualReal =
                        positions(proto.getBlockEntities().keySet());
                eq(row.realPositions(), actualReal,
                        "real block-entity positions " + x + "," + z);

                System.out.println(
                        "STRUCTURE_CONTENT origin=" + x + "," + z
                                + " entities=" + actualEntities.size()
                                + " pendingBlockEntities=" + actualPending.size()
                                + " realBlockEntities=" + actualReal.size());
            }
        }

        System.out.println(
                "StructureContentParityCheck: PASSED (" + checks + " checks)");
    }

    private static Map<Long, Expected> readExpected(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty()) {
            throw new IOException("empty structure-data trace " + file);
        }
        Map<Long, Expected> out = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] f = line.split("\\t", -1);
            if (f.length != 8) {
                throw new IOException("invalid structure-data row: " + line);
            }
            int originX = Integer.parseInt(f[1]);
            int originZ = Integer.parseInt(f[2]);
            int chunkX = Integer.parseInt(f[3]);
            int chunkZ = Integer.parseInt(f[4]);
            if (originX != chunkX || originZ != chunkZ) {
                continue;
            }
            Expected expected = new Expected(
                    canonicalEntities(f[5]),
                    positions(f[6]),
                    positions(f[7]));
            Expected previous =
                    out.put(ChunkPos.pack(originX, originZ), expected);
            if (previous != null) {
                throw new IOException(
                        "duplicate center structure-data row for "
                                + originX + "," + originZ);
            }
        }
        return out;
    }

    static List<String> canonicalEntities(String text) {
        String s = text.trim();
        if (s.equals("[]")) {
            return List.of();
        }
        if (!s.startsWith("[") || !s.endsWith("]")) {
            throw new IllegalArgumentException("invalid entity list: " + text);
        }

        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 1; i < s.length() - 1; i++) {
            char ch = s.charAt(i);
            if (ch == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String entity = s.substring(start, i + 1);
                    entity = UUID_FIELD.matcher(entity).replaceAll("");
                    entity = entity.replace(",}", "}").replace("{,", "{");
                    result.add(entity);
                    start = -1;
                }
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException(
                    "unbalanced entity list: " + text);
        }
        Collections.sort(result);
        return List.copyOf(result);
    }

    static Set<String> positions(String text) {
        Set<String> out = new HashSet<>();
        Matcher matcher = BLOCK_POS.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group(1) + "," + matcher.group(2) + "," + matcher.group(3));
        }
        return Set.copyOf(out);
    }

    static Set<String> positions(Iterable<BlockPos> values) {
        Set<String> out = new HashSet<>();
        for (BlockPos pos : values) {
            out.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
        return Set.copyOf(out);
    }

    private record Expected(
            List<String> entities,
            Set<String> pendingPositions,
            Set<String> realPositions) {
    }

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + " got " + actual);
        }
    }
}
