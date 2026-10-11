package dev.kestrel.worldgen;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LightEngine;
import dev.kestrel.persist.Nbt;
import dev.kestrel.persist.RegionFile;

/** Diagnostic replay of a validated, nonoverlapping actual vanilla FEATURES trace. */
public final class FeaturesTraceReplay {
    // Read from the trace rather than hardcoded, so this runs on any capture.
    // Every row still has to agree, which is what the old constant enforced.
    private static long SEED = -1L;
    private static int CAPTURE_MIN_Y = Integer.MIN_VALUE;
    private static int CAPTURE_HEIGHT = -1;
    private record Snapshot(int x, int z, int minY, int height, int[] ids) {}
    private record TraceRef(int x, int z, String status, String hash) {}
    private record Event(long sequence, String phase, String thread, int x, int z, TraceRef[] refs) {}
    private record LightData(long order, int x, int z, int minSection, int maxSection,
            String blockHash, String skyHash) {}
    private record LightStage(long order, int x, int z) {}

    private static List<LightStage> readLightStages(Path root) throws IOException {
        List<String> lines = Files.readAllLines(root.resolve("light-stage-events.tsv"));
        List<LightStage> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t", -1);
            if (f.length != 7 || !f[6].equals("OK")) {
                throw new IOException("invalid LIGHT stage event: " + line);
            }
            out.add(new LightStage(Long.parseLong(f[1]), Integer.parseInt(f[4]),
                    Integer.parseInt(f[5])));
        }
        out.sort(Comparator.comparingLong(LightStage::order));
        return out;
    }

    private static String nbtLightDigest(Nbt.Tag.TCompound chunk, String name,
            int min, int max) throws Exception {
        Map<Integer, byte[]> layers = new HashMap<>();
        Nbt.Tag sections = Nbt.getOrNull(chunk, "sections");
        if (sections instanceof Nbt.Tag.TList list) {
            for (Nbt.Tag tag : list.items()) {
                if (!(tag instanceof Nbt.Tag.TCompound section)) continue;
                Nbt.Tag layer = Nbt.getOrNull(section, name);
                if (layer instanceof Nbt.Tag.TByteArray bytes) {
                    layers.put((int) Nbt.getByte(section, "Y"), bytes.value());
                }
            }
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] dark = new byte[2048];
        for (int sy = min; sy < max; sy++) {
            byte[] data = layers.get(sy);
            digest.update(data == null ? dark : data);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<LightData> readLightData(Path root) throws IOException {
        return readLightData(root, "light-data.tsv");
    }

    private static List<LightData> readLightData(Path root, String file) throws IOException {
        Path path = root.resolve(file);
        if (!Files.isRegularFile(path)) return List.of();
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty() || !lines.get(0).equals(
                "order\tx\tz\tminLightSection\tmaxLightSection\tblockSha256\tskySha256")) {
            throw new IOException("unsupported light-data.tsv header");
        }
        List<LightData> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t", -1);
            if (f.length != 7) throw new IOException("invalid light data: " + line);
            out.add(new LightData(Long.parseLong(f[0]), Integer.parseInt(f[1]),
                    Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                    f[5], f[6]));
        }
        out.sort(Comparator.comparingLong(LightData::order));
        return out;
    }

    private static String lightDigest(net.minecraft.world.level.lighting.LevelLightEngine engine,
            net.minecraft.world.level.LightLayer layer, int x, int z, int min, int max)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] dark = new byte[2048];
        for (int sy = min; sy < max; sy++) {
            var data = engine.getLayerListener(layer)
                    .getDataLayerData(SectionPos.of(x, sy, z));
            if (data == null) {
                digest.update(dark);
            } else {
                digest.update(data.getData());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Diagnostic-only dump target for {@code -Dkestrel.featureSeedDump=<path>}.
     * Vanilla's per-origin feature index set is exactly the rows its trace
     * records in feature-places.tsv, so dumping Kestrel's set in the same
     * (chunk, step, index, seed) form localizes a possible-biome or
     * index-mapping divergence without a second capture. Opened lazily and
     * flushed by a shutdown hook, so a later gate failure still leaves a
     * complete file. Inert unless the property is set.
     */
    private static Writer featureSeedDump;

    private static synchronized void writeFeatureSeedDump(ChunkPos chunk, String line) {
        try {
            if (featureSeedDump == null) {
                Writer writer = new BufferedWriter(Files.newBufferedWriter(
                        Path.of(System.getProperty("kestrel.featureSeedDump")),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        writer.close();
                    } catch (IOException ignored) {
                        // Diagnostic dump: a failed close must not mask a verdict.
                    }
                }));
                featureSeedDump = writer;
            }
            featureSeedDump.write(chunk.x() + "\t" + chunk.z() + "\t" + line + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TraceRef parseRef(String raw) throws IOException {
        String[] parts = raw.split(",", 4);
        if (parts.length != 4 || parts[2].isBlank() || parts[3].isBlank()) {
            throw new IOException("invalid dependency ref: " + raw);
        }
        return new TraceRef(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), parts[2], parts[3]);
    }

    private static Snapshot read(Path root, TraceRef ref) throws Exception {
        byte[] bytes;
        try (var in = new GZIPInputStream(Files.newInputStream(root.resolve("chunks/" + ref.hash + ".bin.gz")))) {
            bytes = in.readAllBytes();
        }
        if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(ref.hash))
            throw new IOException("snapshot checksum mismatch " + ref);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4b465431) throw new IOException("snapshot format");
            int x = in.readInt(), z = in.readInt(), minY = in.readInt(), height = in.readInt();
            if (x != ref.x || z != ref.z || height <= 0 || (height & 15) != 0)
                throw new IOException("snapshot coordinates/height " + ref);
            if (CAPTURE_MIN_Y == Integer.MIN_VALUE) {
                CAPTURE_MIN_Y = minY;
                CAPTURE_HEIGHT = height;
            } else if (minY != CAPTURE_MIN_Y || height != CAPTURE_HEIGHT) {
                throw new IOException("inconsistent snapshot height " + ref);
            }
            int[] ids = new int[height * 256];
            for (int i = 0; i < ids.length; i++) ids[i] = in.readInt();
            if (in.read() != -1) throw new IOException("trailing snapshot bytes");
            return new Snapshot(x, z, minY, height, ids);
        }
    }
    private static boolean target(int x, int z) { return x == -6 && z == -6 || (x == -6 || x == -5) && z == 6; }

    /**
     * How far out the trace must cover for a chunk's final state to be fully determined.
     *
     * Write radius is 1: chunk C's content is written by centers in ring 1 of C. But a
     * center D does not write blindly, it reads the world inside its own write footprint
     * to decide placement. So D only decides as vanilla did if ring 1 of D was itself
     * decorated, and the union of ring 1 over every D in ring 1 of C is ring 2 of C.
     *
     * Scoring at ring 1 therefore charges Kestrel for centers that read territory the
     * trace never decorated. Measured on the s24 capture that was all 43 remaining
     * differing cells, every one of them in the two-block strip on the face pointing at
     * the untraced row, which is exactly where this mechanism has to put them.
     */
    private static final int SCORE_RING = 2;

    private static boolean traced(Set<Long> origins, int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                if (!origins.contains(ChunkPos.pack(x + dx, z + dz))) return false;
        return true;
    }
    private static long allDifferences, familyDifferences;
    private static boolean firstAll, firstFamily;
    // Observation counts above double-count a physical cell every time it falls inside a
    // later center's 3x3 dependency window; track distinct wrong cells separately so the
    // final report states an honest bug surface, not a repeated-observation total.
    private static final Set<Long> distinctWrongCells = new HashSet<>();
    private static final Set<Long> distinctFamilyWrongCells = new HashSet<>();
    private static void compare(Path root, Event event, Map<Long, ChunkAccess> grid) throws Exception {
        long count = 0, families = 0;
        for (TraceRef ref : event.refs) {
            Snapshot s = read(root, ref);
            ChunkAccess chunk = grid.get(ChunkPos.pack(s.x, s.z));
            for (int i = 0; i < s.ids.length; i++) {
                BlockPos p = new BlockPos(s.x * 16 + (i & 15), s.minY + (i >> 8), s.z * 16 + ((i >> 4) & 15));
                var actual = capturedState(chunk, p);
                if (Block.getId(actual) == s.ids[i]) continue;
                count++;
                distinctWrongCells.add(p.asLong());
                var expected = Block.stateById(s.ids[i]);
                if (!firstAll) {
                    System.out.println("FIRST_BLOCK_DIFF seq=" + event.sequence + " phase=" + event.phase
                            + " origin=" + event.x + "," + event.z + " cell=" + p + " vanilla=" + expected + " kestrel=" + actual);
                    firstAll = true;
                }
                String pair = actual + " " + expected;
                if (target(s.x, s.z) && (pair.contains("kelp") || pair.contains("seagrass") || pair.contains("sulfur"))) {
                    families++;
                    distinctFamilyWrongCells.add(p.asLong());
                    if (!firstFamily) {
                        System.out.println("FIRST_TARGET_FAMILY_DIFF seq=" + event.sequence + " phase=" + event.phase
                                + " origin=" + event.x + "," + event.z + " cell=" + p + " vanilla=" + expected + " kestrel=" + actual);
                        firstFamily = true;
                    }
                }
            }
        }
        allDifferences += count; familyDifferences += families;
        System.out.println("COMPARE seq=" + event.sequence + " phase=" + event.phase + " origin=" + event.x + "," + event.z
                + " blocks=" + count + " targetFamily=" + families);
    }


    private record StructureDataRef(
            int chunkX,
            int chunkZ,
            String entities,
            Set<String> pendingPositions,
            Set<String> realPositions) {}

    private static long structureDataComparisons;

    static String normalizeEntityNbt(String value) {
        String normalized =
                value.replaceAll("UUID:\\[I;[-0-9,]+\\]", "UUID:[I;UUID]");
        // Vanilla 26.2 Chicken.<init> assigns eggTime from
        // Entity.random.nextInt(6000)+6000. Entity.random is constructed by
        // RandomSource.create()/RandomSupport.generateUniqueSeed, the same
        // proven entity-local entropy class already normalized below.
        normalized = normalized.replaceAll(
                "EggLayTime:[0-9]+", "EggLayTime:ENTITY_LOCAL_ENTROPY");
        return canonicalizeEntityAttributeLists(normalized);
    }

    /**
     * AttributeMap.pack() in Vanilla 26.2 serializes the values of an
     * Object2ObjectOpenHashMap directly into a list. AttributeMap.apply()
     * restores each packed entry by its attribute key, so list order is not
     * entity state; it is only the map's iteration order. Headless entity
     * construction can legitimately produce a different table layout.
     *
     * <p>Canonicalize only that one map-as-list representation. Every entity,
     * field, attribute payload and all other NBT lists remain exact. Malformed
     * or duplicate attribute entries fail closed rather than being tolerated.
     */
    private static String canonicalizeEntityAttributeLists(String value) {
        final String marker = "attributes:[";
        StringBuilder out = new StringBuilder(value.length());
        int cursor = 0;
        while (true) {
            int markerAt = findOutsideQuotes(value, marker, cursor);
            if (markerAt < 0) {
                out.append(value, cursor, value.length());
                return out.toString();
            }

            int open = markerAt + marker.length() - 1;
            int close = matchingListEnd(value, open);
            List<String> entries =
                    splitTopLevelNbtList(value.substring(open + 1, close));

            record AttributeEntry(String id, String nbt) {}
            List<AttributeEntry> keyed = new ArrayList<>(entries.size());
            Set<String> ids = new HashSet<>();
            for (String entry : entries) {
                String id = entityAttributeId(entry);
                if (!ids.add(id)) {
                    throw new IllegalArgumentException(
                            "duplicate entity attribute id: " + id);
                }
                keyed.add(new AttributeEntry(
                        id, canonicalizeVanillaEntityLocalEntropy(id, entry)));
            }
            keyed.sort(Comparator.comparing(AttributeEntry::id));

            out.append(value, cursor, open + 1);
            for (int i = 0; i < keyed.size(); i++) {
                if (i != 0) out.append(',');
                out.append(keyed.get(i).nbt());
            }
            cursor = close;
        }
    }

    private static int findOutsideQuotes(
            String value, String marker, int from) {
        char quote = 0;
        boolean escaped = false;
        for (int i = from; i <= value.length() - marker.length(); i++) {
            char ch = value.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (value.startsWith(marker, i)) {
                return i;
            }
        }
        return -1;
    }

    private static String entityAttributeId(String entry) {
        if (entry.length() < 2
                || entry.charAt(0) != '{'
                || entry.charAt(entry.length() - 1) != '}') {
            throw new IllegalArgumentException(
                    "malformed entity attribute entry: " + entry);
        }

        String id = null;
        for (String field : splitTopLevelNbtList(
                entry.substring(1, entry.length() - 1))) {
            if (!field.startsWith("id:") || field.length() < 5) continue;
            char quote = field.charAt(3);
            if (quote != '"' && quote != '\'') continue;
            int end = field.indexOf(quote, 4);
            if (end < 0 || end != field.length() - 1) {
                throw new IllegalArgumentException(
                        "malformed entity attribute id field: " + field);
            }
            if (id != null) {
                throw new IllegalArgumentException(
                        "entity attribute entry has duplicate id field: " + entry);
            }
            id = field.substring(4, end);
        }
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException(
                    "entity attributes entry has no top-level id: " + entry);
        }
        return id;
    }

    /**
     * Vanilla 26.2 Entity constructs its private RandomSource with
     * RandomSource.create(), which seeds from RandomSupport.generateUniqueSeed()
     * (atomic uniquifier XOR System.nanoTime), not from the world seed.
     *
     * Zombie.handleAttributes() consumes that entity-local source for two
     * serialized values that therefore differ even across two otherwise
     * identical Vanilla entity constructions:
     *  - SPAWN_REINFORCEMENTS_CHANCE base value
     *  - KNOCKBACK_RESISTANCE's minecraft:random_spawn_bonus amount
     *
     * These are entropy artifacts, not reproducible worldgen state. Normalize
     * only those exact fields. All other attributes/modifiers remain byte-for-
     * byte semantic comparator inputs, including FOLLOW_RANGE's different
     * random_spawn_bonus modifier.
     */
    private static String canonicalizeVanillaEntityLocalEntropy(
            String attributeId, String entry) {
        if (attributeId.equals("minecraft:spawn_reinforcements")) {
            return replaceTopLevelCompoundField(
                    entry, "base", "ENTITY_LOCAL_ENTROPY");
        }
        if (!attributeId.equals("minecraft:knockback_resistance")) {
            return entry;
        }

        final String marker = "modifiers:[";
        int markerAt = findOutsideQuotes(entry, marker, 0);
        if (markerAt < 0) return entry;
        int open = markerAt + marker.length() - 1;
        int close = matchingListEnd(entry, open);
        List<String> modifiers =
                splitTopLevelNbtList(entry.substring(open + 1, close));

        boolean changed = false;
        for (int i = 0; i < modifiers.size(); i++) {
            String modifier = modifiers.get(i);
            String id = topLevelCompoundField(modifier, "id");
            String operation = topLevelCompoundField(modifier, "operation");
            if ("\"minecraft:random_spawn_bonus\"".equals(id)
                    && "\"add_value\"".equals(operation)) {
                if (changed) {
                    throw new IllegalArgumentException(
                            "duplicate knockback random_spawn_bonus modifier: " + entry);
                }
                modifiers.set(
                        i,
                        replaceTopLevelCompoundField(
                                modifier, "amount", "ENTITY_LOCAL_ENTROPY"));
                changed = true;
            }
        }
        if (!changed) return entry;

        return entry.substring(0, open + 1)
                + String.join(",", modifiers)
                + entry.substring(close);
    }

    private static String topLevelCompoundField(
            String compound, String fieldName) {
        if (compound.length() < 2
                || compound.charAt(0) != '{'
                || compound.charAt(compound.length() - 1) != '}') {
            throw new IllegalArgumentException(
                    "malformed NBT compound: " + compound);
        }
        String prefix = fieldName + ":";
        String found = null;
        for (String field : splitTopLevelNbtList(
                compound.substring(1, compound.length() - 1))) {
            if (!field.startsWith(prefix)) continue;
            if (found != null) {
                throw new IllegalArgumentException(
                        "duplicate top-level field " + fieldName + ": " + compound);
            }
            found = field.substring(prefix.length());
        }
        return found;
    }

    private static String replaceTopLevelCompoundField(
            String compound, String fieldName, String replacementValue) {
        if (compound.length() < 2
                || compound.charAt(0) != '{'
                || compound.charAt(compound.length() - 1) != '}') {
            throw new IllegalArgumentException(
                    "malformed NBT compound: " + compound);
        }
        String prefix = fieldName + ":";
        List<String> fields = new ArrayList<>(splitTopLevelNbtList(
                compound.substring(1, compound.length() - 1)));
        int found = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).startsWith(prefix)) continue;
            if (found >= 0) {
                throw new IllegalArgumentException(
                        "duplicate top-level field " + fieldName + ": " + compound);
            }
            found = i;
        }
        if (found < 0) {
            throw new IllegalArgumentException(
                    "missing top-level field " + fieldName + ": " + compound);
        }
        fields.set(found, prefix + replacementValue);
        return "{" + String.join(",", fields) + "}";
    }

    private static int matchingListEnd(String value, int open) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = open; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '[') {
                depth++;
            } else if (ch == ']' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException(
                "unterminated entity attributes list");
    }

    private static List<String> splitTopLevelNbtList(String body) {
        if (body.isEmpty()) return List.of();

        List<String> entries = new ArrayList<>();
        int start = 0;
        int square = 0;
        int curly = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '[') {
                square++;
            } else if (ch == ']') {
                square--;
            } else if (ch == '{') {
                curly++;
            } else if (ch == '}') {
                curly--;
            } else if (ch == ',' && square == 0 && curly == 0) {
                entries.add(body.substring(start, i));
                start = i + 1;
            }
            if (square < 0 || curly < 0) {
                throw new IllegalArgumentException(
                        "malformed entity attributes list: " + body);
            }
        }
        if (quote != 0 || square != 0 || curly != 0) {
            throw new IllegalArgumentException(
                    "malformed entity attributes list: " + body);
        }
        entries.add(body.substring(start));
        return entries;
    }

    private static Set<String> blockPositions(String value) {
        var pattern = java.util.regex.Pattern.compile(
                "BlockPos\\{x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\}");
        var matcher = pattern.matcher(value);
        Set<String> positions = new TreeSet<>();
        while (matcher.find()) {
            positions.add(matcher.group(1) + "," + matcher.group(2) + "," + matcher.group(3));
        }
        return positions;
    }

    private static Map<Long, List<StructureDataRef>> readStructureData(Path root)
            throws IOException {
        Path file = root.resolve("structure-data.tsv");
        if (!Files.exists(file)) return Map.of();

        Map<Long, List<StructureDataRef>> rows = new HashMap<>();
        List<String> lines = Files.readAllLines(file);
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 8 || !fields[0].equals("COMPLETE")) {
                throw new IOException("invalid structure-data row: " + line);
            }
            int originX = Integer.parseInt(fields[1]);
            int originZ = Integer.parseInt(fields[2]);
            int chunkX = Integer.parseInt(fields[3]);
            int chunkZ = Integer.parseInt(fields[4]);

            Set<String> pending = blockPositions(fields[6]);
            int dummyCount = fields[6].split("id:\\\"DUMMY\\\"", -1).length - 1;
            if (!fields[6].equals("{}") && dummyCount != pending.size()) {
                throw new IOException(
                        "structure pending-BE trace contains unsupported payload: " + fields[6]);
            }

            Set<String> real = blockPositions(fields[7]);
            rows.computeIfAbsent(
                    ChunkPos.pack(originX, originZ), unused -> new ArrayList<>())
                    .add(new StructureDataRef(
                            chunkX,
                            chunkZ,
                            normalizeEntityNbt(fields[5]),
                            pending,
                            real));
        }
        return rows;
    }

    private static void compareStructureData(
            Event event,
            Map<Long, List<StructureDataRef>> expected,
            Map<Long, ChunkAccess> grid) {
        List<StructureDataRef> rows =
                expected.getOrDefault(ChunkPos.pack(event.x, event.z), List.of());
        if (rows.isEmpty()) return;

        for (StructureDataRef ref : rows) {
            ChunkAccess raw = grid.get(ChunkPos.pack(ref.chunkX, ref.chunkZ));
            if (!(raw instanceof ProtoChunk proto)) {
                throw new AssertionError(
                        "structure-data expected ProtoChunk "
                        + ref.chunkX + "," + ref.chunkZ);
            }

            String entities = normalizeEntityNbt(proto.getEntities().toString());
            Set<String> pending = blockPositions(proto.getBlockEntityNbts().keySet().toString());
            Set<String> real = blockPositions(proto.getBlockEntities().keySet().toString());

            if (!entities.equals(ref.entities)
                    || !pending.equals(ref.pendingPositions)
                    || !real.equals(ref.realPositions)) {
                System.out.println(
                        "STRUCTURE_DATA_DIFF origin=" + event.x + "," + event.z
                        + " chunk=" + ref.chunkX + "," + ref.chunkZ);
                System.out.println("  vanillaEntities=" + ref.entities);
                System.out.println("  kestrelEntities=" + entities);
                System.out.println("  vanillaPending=" + ref.pendingPositions
                        + " kestrelPending=" + pending);
                System.out.println("  vanillaReal=" + ref.realPositions
                        + " kestrelReal=" + real);
                throw new AssertionError(
                        "structure entity/block-entity parity failed at chunk "
                        + ref.chunkX + "," + ref.chunkZ);
            }

            structureDataComparisons++;
            System.out.println(
                    "STRUCTURE_DATA_MATCH origin=" + event.x + "," + event.z
                    + " chunk=" + ref.chunkX + "," + ref.chunkZ
                    + " entities=" + proto.getEntities().size()
                    + " pending=" + pending.size()
                    + " real=" + real.size());
        }
    }


    private record StructureCallRef(
            int index,
            int targetX,
            int targetZ,
            String box,
            String beforeHash,
            String afterHash,
            String beforeEntities,
            String afterEntities,
            Set<String> beforePending,
            Set<String> afterPending,
            Set<String> beforeReal,
            Set<String> afterReal) {}

    private static long structureCallComparisons;

    private record StructureSourceRef(
            int index,
            int targetX,
            int targetZ,
            int sourceX,
            int sourceZ,
            String structureClass,
            String box) {}

    private static long structureSourceComparisons;

    private record StructureReferenceStateRef(
            int index,
            int targetX,
            int targetZ,
            int sourceX,
            int sourceZ,
            String chunkClass,
            String setClass,
            int size,
            int n,
            int minN,
            List<Long> iteratorOrder) {}

    private static long structureReferenceStateComparisons;


    private record ChunkLoadRef(
            long afterEventSeq,
            Integer activeOriginX,
            Integer activeOriginZ,
            int x,
            int z,
            String status,
            String chunkClass,
            boolean loadedStructureReferences) {}

    private static Map<Long, List<ChunkLoadRef>> readChunkLoads(Path root)
            throws IOException {
        Path file = root.resolve("chunk-loads.tsv");
        if (!Files.exists(file)) return Map.of();

        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty()) return Map.of();
        String expectedHeader =
                "afterEventSeq\tactiveOriginX\tactiveOriginZ"
                        + "\tx\tz\tstatus\tchunkClass\treferenceSets";
        if (!lines.get(0).equals(expectedHeader)) {
            // Legacy captures have reload contents but no ordering cutpoint.
            return Map.of();
        }

        Map<Long, List<ChunkLoadRef>> rows = new HashMap<>();
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 8) {
                throw new IOException("invalid chunk-load row: " + line);
            }
            Integer activeX = fields[1].isBlank()
                    ? null : Integer.valueOf(fields[1]);
            Integer activeZ = fields[2].isBlank()
                    ? null : Integer.valueOf(fields[2]);
            if ((activeX == null) != (activeZ == null)) {
                throw new IOException(
                        "partial active origin in chunk-load row: " + line);
            }
            ChunkLoadRef ref = new ChunkLoadRef(
                    Long.parseLong(fields[0]),
                    activeX,
                    activeZ,
                    Integer.parseInt(fields[3]),
                    Integer.parseInt(fields[4]),
                    fields[5],
                    fields[6],
                    !fields[7].isBlank());
            rows.computeIfAbsent(
                    ref.afterEventSeq(), unused -> new ArrayList<>()).add(ref);
        }
        return rows;
    }

    private static long chunkReloadHeightmapReplays;
    private static long chunkReloadHeightmapPrefixNoops;
    private static long chunkReloadHeightmapOutsideCoverage;

    /**
     * Apply the heightmap half of one captured Vanilla reload.
     *
     * <p>From CARVERS on, heightmapsAfter() is the final set, so a reload drops
     * the WG maps, which are the only maps that can go stale (ProtoChunk
     * stops maintaining them at CARVERS). Before CARVERS the persisted set is
     * the WG set itself, so a reload keeps every map that can go stale and
     * is an identity; Kestrel's eager CARVERS prefix may already be ahead of
     * such a chunk, which is not a lifecycle disagreement. Chunks outside
     * the traced 3x3 coverage are never read by a replayed FEATURES origin.
     * Anything else where Kestrel's own status implies a different persisted
     * set fails closed. No captured height, block or status value is copied.
     */
    private static void replayReloadHeightmaps(
            long afterEventSeq, ChunkLoadRef load, ChunkAccess chunk, Set<Long> coverage)
            throws IOException {
        ChunkStatus captured = ChunkStatus.byName(load.status());
        if (captured == null) {
            throw new IOException("unknown captured reload status " + load.status());
        }
        ChunkStatus actual = chunk.getPersistedStatus();
        if (captured.heightmapsAfter().equals(actual.heightmapsAfter())) {
            HeadlessNoise.roundTripHeightmaps(chunk);
            chunkReloadHeightmapReplays++;
        } else if (captured.isBefore(ChunkStatus.CARVERS)) {
            chunkReloadHeightmapPrefixNoops++;
        } else if (!coverage.contains(ChunkPos.pack(load.x(), load.z()))) {
            chunkReloadHeightmapOutsideCoverage++;
        } else {
            throw new AssertionError(
                    "CHUNK_RELOAD_STATUS_MISMATCH afterEventSeq=" + afterEventSeq
                            + " chunk=" + load.x() + "," + load.z()
                            + " captured=" + captured
                            + " actual=" + actual);
        }
    }

    /**
     * Chebyshev radius around a FEATURES origin that the pass for that origin
     * can observe. FEATURES itself reads and writes the radius-1
     * WorldGenRegion, but its StructureManager is backed by the shared
     * radius-8 STRUCTURE_STARTS/REFERENCES window
     * ({@link HeadlessNoise#structureWindow}, whose dx/dz loops run -8..8),
     * and {@code startsForStructure} follows references that
     * {@code ChunkGenerator.createReferences} only ever writes inside that
     * same radius. Radius 8 is therefore the complete set of chunks a single
     * pass can read.
     */
    private static final int FEATURES_OBSERVABLE_RADIUS = 8;

    /**
     * True when a chunk reload that the capture observed inside an active
     * FEATURES pass provably cannot affect that pass.
     *
     * <p>{@code chunk-loads.tsv} records only which origin was in flight, not
     * where inside its several hundred feature placements the reload landed,
     * so a reload the pass can see is not replayable and both gates fail
     * closed on it (preresearch/intra-features-chunk-reload.md). A reload
     * outside {@link #FEATURES_OBSERVABLE_RADIUS} is a different case: the
     * active pass never reads that chunk, so applying the reload at the pass
     * boundary is indistinguishable from applying it at the exact instant it
     * happened, for that pass and for every later one. This is not a
     * weakening of the gate, it is the subset of intra-FEATURES reloads whose
     * position inside the pass is unobservable.
     *
     * <p>Campaign window w03 is entirely of this kind: all 22 intra-FEATURES
     * reload rows are at origin 0,18 with reloaded chunks at x=11..12, a
     * Chebyshev distance of 11 or more, and all of them carry empty
     * reference sets.
     */
    static boolean reloadIsUnobservableByActiveOrigin(
            int activeOriginX, int activeOriginZ, int x, int z) {
        return Math.max(Math.abs(x - activeOriginX), Math.abs(z - activeOriginZ))
                > FEATURES_OBSERVABLE_RADIUS;
    }

    private static long replayChunkLoads(
            long afterEventSeq,
            Map<Long, List<ChunkLoadRef>> loads,
            Map<Long, ChunkAccess> grid,
            Set<Long> coverage,
            HeadlessNoise.Structural structural,
            WorldgenLightLifecycle lighting) throws IOException {
        long applied = 0;
        for (ChunkLoadRef load : loads.getOrDefault(afterEventSeq, List.of())) {
            if (load.activeOriginX() != null
                    && !reloadIsUnobservableByActiveOrigin(
                            load.activeOriginX(), load.activeOriginZ(),
                            load.x(), load.z())) {
                throw new IOException(
                        "chunk reload occurred inside active FEATURES origin="
                                + load.activeOriginX() + "," + load.activeOriginZ()
                                + " afterEventSeq=" + afterEventSeq
                                + " chunk=" + load.x() + "," + load.z()
                                + "; trace needs an intra-FEATURES reload hook");
            }
            // A read of the serialized chunk in the capture means Vanilla had
            // unloaded it first, and ChunkMap.scheduleUnload clears its light
            // storage; the read then brings back only the layers the form
            // actually carried, which is none at all for a form saved below
            // LIGHT. Reproduce both halves whether or not the chunk is in this
            // replay's grid: the layers are keyed by ChunkPos, so a stale one
            // is visible to any grid chunk that reads across the boundary.
            // See WorldgenLightLifecycle.reloadChunkData.
            ChunkStatus capturedStatus = ChunkStatus.byName(load.status());
            if (capturedStatus == null) {
                throw new IOException(
                        "unknown captured reload status " + load.status()
                                + " chunk=" + load.x() + "," + load.z());
            }
            lighting.reloadChunkData(new ChunkPos(load.x(), load.z()),
                    capturedStatus.isOrAfter(ChunkStatus.LIGHT));
            ChunkAccess chunk = grid.get(ChunkPos.pack(load.x(), load.z()));
            if (chunk == null) {
                continue;
            }
            if (load.chunkClass().equals(ProtoChunk.class.getName())) {
                HeadlessNoise.roundTripBlockEntities(structural, chunk);
            }
            // Every read replaces the chunk object, whether or not it carried
            // structure references, and keeps only the heightmaps persisted
            // for its status (HeadlessNoise.roundTripHeightmaps).
            replayReloadHeightmaps(afterEventSeq, load, chunk, coverage);
            // Starts are serialized as soon as STRUCTURE_STARTS completes.
            // A form saved there has no reference sets yet, but loading its
            // jigsaw pieces can still shrink their recomputed bounding boxes.
            // Round-trip that half even when references are absent.
            if (capturedStatus.isOrAfter(ChunkStatus.STRUCTURE_STARTS)) {
                HeadlessNoise.roundTripStructureStarts(structural, chunk);
            }
            // SerializableChunkData.read only rehydrates reference sets that
            // were actually present in the serialized StructureReferences
            // payload. The replay grid is pre-materialized through
            // STRUCTURE_REFERENCES, so blindly round-tripping on every read
            // would incorrectly rehash references that Vanilla had not even
            // generated/stored at that load cutpoint.
            if (!load.loadedStructureReferences()) {
                System.out.println(
                        "CHUNK_RELOAD_NO_REFERENCES afterEventSeq=" + afterEventSeq
                                + " chunk=" + load.x() + "," + load.z()
                                + " capturedStatus=" + load.status()
                                + " capturedClass=" + load.chunkClass());
                continue;
            }
            HeadlessNoise.roundTripStructureReferences(chunk);
            applied++;
            System.out.println(
                    "CHUNK_RELOAD_REPLAY afterEventSeq=" + afterEventSeq
                            + " chunk=" + load.x() + "," + load.z()
                            + " capturedStatus=" + load.status()
                            + " capturedClass=" + load.chunkClass());
        }
        return applied;
    }

    private record WorldgenLightEventRef(
            long afterEventSeq,
            long order,
            String stage,
            Integer activeOriginX,
            Integer activeOriginZ,
            int x,
            int z,
            String outcome,
            long batch) {}

    private record LightExecutorOp(String kind, int x, int y, int z, boolean flag) {}
    private record LightExecutorBatch(long id, long afterEventSeq, List<LightExecutorOp> ops) {}
    private record LightScheduleOp(long afterEventSeq, Integer activeX, Integer activeZ,
                                   int x, int z, int engine) {}

    private static List<LightScheduleOp> readLightScheduleOps(Path root) throws IOException {
        Path file = root.resolve("light-schedule-ops.tsv");
        if (!Files.isRegularFile(file)) return List.of();
        List<String> lines = Files.readAllLines(file);
        String header = "afterEventSeq\tactiveOriginX\tactiveOriginZ\tx\tz\tengine";
        if (lines.isEmpty() || !lines.get(0).equals(header)) {
            throw new IOException("invalid light-schedule-ops.tsv header");
        }
        Map<Integer, Integer> counts = new HashMap<>();
        List<LightScheduleOp> all = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] f = line.split("\t", -1);
            if (f.length != 6) throw new IOException("invalid light schedule row: " + line);
            Integer activeX = f[1].isBlank() ? null : Integer.valueOf(f[1]);
            Integer activeZ = f[2].isBlank() ? null : Integer.valueOf(f[2]);
            int engine = Integer.parseInt(f[5]);
            counts.merge(engine, 1, Integer::sum);
            all.add(new LightScheduleOp(Long.parseLong(f[0]), activeX, activeZ,
                    Integer.parseInt(f[3]), Integer.parseInt(f[4]), engine));
        }
        int selected = counts.entrySet().stream().max(Map.Entry.comparingByValue())
                .orElseThrow(() -> new IOException("empty light schedule trace")).getKey();
        return all.stream().filter(op -> op.engine() == selected).toList();
    }

    private static List<LightExecutorBatch> readLightExecutorBatches(Path root)
            throws IOException {
        Path file = root.resolve("light-executor-ops.tsv");
        if (!Files.isRegularFile(file)) return List.of();
        List<String> lines = Files.readAllLines(file);
        String header = "order\tbatch\tkind\tafterEventSeq\tactiveOriginX\tactiveOriginZ"
                + "\tx\ty\tz\tflag\tengine";
        if (lines.isEmpty() || !lines.get(0).equals(header)) {
            throw new IOException("invalid light-executor-ops.tsv header");
        }
        List<LightExecutorBatch> out = new ArrayList<>();
        List<LightExecutorOp> ops = null;
        long batch = -1L;
        long cut = -1L;
        long expectedOrder = 0L;
        Map<Integer, Integer> initializeCounts = new HashMap<>();
        long finalSnapshotOrder = Long.MAX_VALUE;
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split("\t", -1);
            if (f.length == 11 && f[2].equals("FINAL_SNAPSHOT")) {
                finalSnapshotOrder = Long.parseLong(f[0]);
            }
            if (f.length == 11 && f[2].equals("INITIALIZE_LIGHT")) {
                initializeCounts.merge(Integer.parseInt(f[10]), 1, Integer::sum);
            }
        }
        int selectedEngine = initializeCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow(() -> new IOException("no initialize-light executor operations"))
                .getKey();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] f = line.split("\t", -1);
            if (f.length != 11) {
                throw new IOException("invalid light executor row: " + line);
            }
            if (Long.parseLong(f[0]) >= finalSnapshotOrder) break;
            if (Integer.parseInt(f[10]) != selectedEngine) continue;
            expectedOrder++;
            long rowBatch = Long.parseLong(f[1]);
            long rowCut = Long.parseLong(f[3]);
            if (f[2].equals("BATCH_ENTER")) {
                if (ops != null) throw new IOException("nested light executor batch: " + line);
                batch = rowBatch;
                // An active origin means the worker has emitted its ENTER row
                // but may be racing this light batch. The replay is serial, so
                // place the batch immediately before that FEATURES invocation.
                cut = f[4].isBlank() ? rowCut : rowCut - 1L;
                ops = new ArrayList<>();
            } else if (f[2].equals("BATCH_EXIT")) {
                if (ops == null || rowBatch != batch || rowCut < cut) {
                    throw new IOException("mismatched light executor batch exit: " + line);
                }
                out.add(new LightExecutorBatch(batch, cut, List.copyOf(ops)));
                ops = null;
            } else {
                if (ops == null || rowBatch != batch || rowCut < cut
                        || (!f[2].equals("INITIALIZE_LIGHT") && !f[2].equals("LIGHT")
                                && !f[2].equals("CHECK_BLOCK")
                                && !f[2].equals("SECTION_STATUS")
                                && !f[2].equals("UNLOAD_CHUNK"))) {
                    throw new IOException("invalid light executor operation: " + line);
                }
                ops.add(new LightExecutorOp(f[2], Integer.parseInt(f[6]),
                        f[7].isBlank() ? 0 : Integer.parseInt(f[7]), Integer.parseInt(f[8]),
                        Boolean.parseBoolean(f[9])));
            }
        }
        if (ops != null) throw new IOException("unterminated light executor batch " + batch);
        return List.copyOf(out);
    }

    private static void replayLightExecutorBatch(
            LightExecutorBatch batch,
            Map<Long, ChunkAccess> grid,
            WorldgenLightLifecycle lighting,
            Map<Long, List<WorldgenLightEventRef>> completionsByBatch,
            ReplayActivation activation) throws IOException {
        List<Long> litThisBatch = new ArrayList<>();
        for (LightExecutorOp op : batch.ops()) {
            if (op.kind().equals("CHECK_BLOCK")) {
                probeExecutorOp("CHECK_BLOCK", op.x(), op.y(), op.z(), grid);
                probeLight("before:" + batch.id() + ":" + op.x() + "," + op.y() + "," + op.z(),
                        op.x(), op.y(), op.z(), lighting);
                lighting.checkBlock(new net.minecraft.core.BlockPos(op.x(), op.y(), op.z()));
                continue;
            }
            if (op.kind().equals("SECTION_STATUS")) {
                probeExecutorOp("SECTION_STATUS", op.x(), op.y() << 4, op.z(), grid);
                lighting.updateSectionStatus(
                        net.minecraft.core.SectionPos.of(op.x(), op.y(), op.z()), op.flag());
                continue;
            }
            if (op.kind().equals("UNLOAD_CHUNK")) {
                lighting.applyUnloadPreUpdate(new ChunkPos(op.x(), op.z()));
                continue;
            }
            ChunkAccess raw = grid.get(ChunkPos.pack(op.x(), op.z()));
            if (!(raw instanceof ProtoChunk chunk)) {
                // Vanilla may light dependencies outside the retained replay
                // coverage. They cannot contribute a stored layer to a scored
                // chunk because the trace coverage already includes the full
                // status dependency radius.
                continue;
            }
            if (op.kind().equals("INITIALIZE_LIGHT")) {
                lighting.applyInitializePreUpdate(chunk);
            } else {
                lighting.applyLightPreUpdate(chunk);
                litThisBatch.add(ChunkPos.pack(op.x(), op.z()));
            }
        }
        lighting.drainBatch(batch.id());
        if (System.getProperty("kestrel.probe") != null) {
            for (LightExecutorOp op : batch.ops()) {
                if (op.kind().equals("CHECK_BLOCK")) {
                    probeLight("after:" + batch.id() + ":" + op.x() + "," + op.y() + "," + op.z(),
                            op.x(), op.y(), op.z(), lighting);
                }
            }
        }
        for (WorldgenLightEventRef completion
                : completionsByBatch.getOrDefault(batch.id(), List.of())) {
            if (!completion.outcome().equals("OK")) {
                throw new IOException("captured worldgen LIGHT failed: " + completion);
            }
            ChunkAccess raw = grid.get(ChunkPos.pack(completion.x(), completion.z()));
            if (!(raw instanceof ProtoChunk chunk)) continue;
            if (completion.stage().equals("minecraft:initialize_light")) {
                lighting.applyInitializePostUpdate(chunk);
            } else {
                lighting.applyLightPostUpdate(chunk);
            }
        }
        if (activation != null && !litThisBatch.isEmpty()) {
            activation.afterBatch(litThisBatch);
        }
    }

    private static void publishLightStatus(
            WorldgenLightEventRef completion, Map<Long, ChunkAccess> grid) throws IOException {
        if (!completion.outcome().equals("OK")) {
            throw new IOException("captured worldgen LIGHT failed: " + completion);
        }
        ChunkAccess raw = grid.get(ChunkPos.pack(completion.x(), completion.z()));
        if (!(raw instanceof ProtoChunk chunk)) return;
        ChunkStatus target = completion.stage().equals("minecraft:initialize_light")
                ? ChunkStatus.INITIALIZE_LIGHT : ChunkStatus.LIGHT;
        if (chunk.getPersistedStatus().isBefore(target)) chunk.setPersistedStatus(target);
        WorldgenLightLifecycle.probeSource("publishStatus-" + completion.stage(), chunk);
    }

    /**
     * Diagnostic-only: settle whether Vanilla 26.2 treats a water column as a
     * sky-light-attenuating edge, by walking a column with the exact public
     * BlockState API {@code ChunkSkyLightSources.isEdgeOccluded} uses (level's
     * BlockState, not the fluid's): {@code getLightDampening()},
     * {@code useShapeForLightOcclusion()} and {@code getOcclusionShape()}.
     * Inert unless {@code -Dkestrel.probeWaterColumns=x,z;x,z;...} is set.
     */
    private static void probeWaterColumns(Map<Long, ChunkAccess> grid) {
        String spec = System.getProperty("kestrel.probeWaterColumns");
        if (spec == null || spec.isBlank()) return;
        for (String column : spec.split(";")) {
            String[] parts = column.trim().split(",");
            if (parts.length != 2) continue;
            int x = Integer.parseInt(parts[0].trim());
            int z = Integer.parseInt(parts[1].trim());
            ChunkAccess chunk = grid.get(ChunkPos.pack(x >> 4, z >> 4));
            if (chunk == null) {
                System.out.println("PROBEW @" + x + "," + z + " no chunk in grid");
                continue;
            }
            int lowest = chunk.getSkyLightSources().getLowestSourceY(x & 15, z & 15);
            System.out.println("PROBEW @" + x + "," + z + " lowestSourceY=" + lowest);
            net.minecraft.world.level.block.state.BlockState prevState = null;
            for (int y = 70; y >= 55; y--) {
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                var state = chunk.getBlockState(pos);
                var shape = state.getOcclusionShape();
                boolean edgeOccludedByBelow = prevState != null && isEdgeOccludedLikeVanilla(prevState, state);
                System.out.println("PROBEW @" + x + "," + y + "," + z
                        + " block=" + state.getBlock()
                        + " fluid=" + chunk.getFluidState(pos).getType()
                        + " dampening=" + state.getLightDampening()
                        + " useShapeForLightOcclusion=" + state.useShapeForLightOcclusion()
                        + " occlusionShapeEmpty=" + shape.isEmpty()
                        + " edgeOccludedFromAbove=" + edgeOccludedByBelow);
                prevState = state;
            }
        }
    }

    /** Mirrors the private {@code ChunkSkyLightSources.isEdgeOccluded}, public APIs only. */
    private static boolean isEdgeOccludedLikeVanilla(
            net.minecraft.world.level.block.state.BlockState topState,
            net.minecraft.world.level.block.state.BlockState bottomState) {
        if (bottomState.getLightDampening() != 0) return true;
        var topShape = net.minecraft.world.level.lighting.LightEngine.getOcclusionShape(
                topState, net.minecraft.core.Direction.DOWN);
        var bottomShape = net.minecraft.world.level.lighting.LightEngine.getOcclusionShape(
                bottomState, net.minecraft.core.Direction.UP);
        return net.minecraft.world.phys.shapes.Shapes.faceShapeOccludes(topShape, bottomShape);
    }

    /** Diagnostic-only: report a replayed executor op that touches the probe chunk. */
    private static void probeExecutorOp(String kind, int x, int y, int z, Map<Long, ChunkAccess> grid) {
        if (System.getProperty("kestrel.probe") == null) return;
        if (!WorldgenLightLifecycle.probedChunk(x >> 4, z >> 4)) return;
        ChunkAccess raw = grid.get(ChunkPos.pack(x >> 4, z >> 4));
        if (raw == null) return;
        WorldgenLightLifecycle.probeSource(kind + "@" + x + "," + y + "," + z, raw);
        if (kind.equals("CHECK_BLOCK") && raw instanceof ProtoChunk proto) {
            var sources = proto.getSkyLightSources();
            int before = sources.getLowestSourceY(x & 15, z & 15);
            boolean changed = sources.update(proto, x & 15, y, z & 15);
            int after = sources.getLowestSourceY(x & 15, z & 15);
            System.out.println("PROBEU @" + x + "," + y + "," + z
                    + " before=" + before + " changed=" + changed + " after=" + after
                    + " dampAbove=" + proto.getBlockState(new net.minecraft.core.BlockPos(x, y + 1, z))
                            .getLightDampening()
                    + " dampHere=" + proto.getBlockState(new net.minecraft.core.BlockPos(x, y, z))
                            .getLightDampening()
                    + " dampBelow=" + proto.getBlockState(new net.minecraft.core.BlockPos(x, y - 1, z))
                            .getLightDampening());
        }
    }

    /** Diagnostic-only: report the stored light and layer presence at one cell. */
    private static void probeLight(String tag, int x, int y, int z, WorldgenLightLifecycle lighting) {
        if (System.getProperty("kestrel.probe") == null) return;
        if (!WorldgenLightLifecycle.probedChunk(x >> 4, z >> 4)) return;
        var block = lighting.engine().getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
        var sky = lighting.engine().getLayerListener(net.minecraft.world.level.LightLayer.SKY);
        SectionPos section = SectionPos.of(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        long sectionNode = section.asLong();
        System.out.println("PROBEL " + tag + " @" + x + "," + y + "," + z
                + " block=" + block.getLightValue(new net.minecraft.core.BlockPos(x, y, z))
                + " sky=" + sky.getLightValue(new net.minecraft.core.BlockPos(x, y, z))
                + " blockLayer=" + (block.getDataLayerData(section) != null)
                + " skyLayer=" + (sky.getDataLayerData(section) != null)
                + " blockStoring=" + storingLightForSection(block, sectionNode)
                + " blockLightOn=" + lightOnInSection(block, sectionNode)
                + " skyStoring=" + storingLightForSection(sky, sectionNode)
                + " skyLightOn=" + lightOnInSection(sky, sectionNode));
    }

    /**
     * Diagnostic-only: reflect into {@code LightEngine.storage} to read
     * {@code storingLightForSection}/{@code lightOnInSection}, which vanilla
     * keeps protected. Never used off the {@code -Dkestrel.probe} path.
     */
    static boolean storingLightForSection(Object layerListener, long sectionNode) {
        return invokeStorageBoolean(layerListener, "storingLightForSection", sectionNode);
    }

    static boolean lightOnInSection(Object layerListener, long sectionNode) {
        return invokeStorageBoolean(layerListener, "lightOnInSection", sectionNode);
    }

    private static boolean invokeStorageBoolean(Object layerListener, String methodName, long sectionNode) {
        try {
            java.lang.reflect.Field storageField = null;
            for (Class<?> c = layerListener.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    storageField = c.getDeclaredField("storage");
                    break;
                } catch (NoSuchFieldException ignored) {
                    // keep walking up to LightEngine
                }
            }
            if (storageField == null) return false;
            storageField.setAccessible(true);
            Object storage = storageField.get(layerListener);
            java.lang.reflect.Method method = null;
            for (Class<?> c = storage.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    method = c.getDeclaredMethod(methodName, long.class);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // keep walking up to LayerLightSectionStorage
                }
            }
            if (method == null) return false;
            method.setAccessible(true);
            return (boolean) method.invoke(storage, sectionNode);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private record SpawnEventRef(
            long sequence,
            long afterEventSeq,
            long afterInitializeLightOrder,
            long afterFullLightOrder,
            long afterWorldgenLightOrder,
            Integer activeOriginX,
            Integer activeOriginZ,
            String thread,
            int x,
            int z,
            long seed,
            String dimension,
            int beforeCount,
            int afterCount,
            String deltaEntities) {}

    private record SpawnDeltaRef(
            long spawnSequence,
            int targetX,
            int targetZ,
            int beforeCount,
            int afterCount,
            String deltaEntities) {}

    private static List<WorldgenLightEventRef> readWorldgenLightEvents(Path root)
            throws IOException {
        Path file = root.resolve("worldgen-light-events.tsv");
        if (!Files.isRegularFile(file)) return List.of();
        List<String> lines = Files.readAllLines(file);
        String legacyHeader =
                "afterEventSeq\torder\tstage\tactiveOriginX\tactiveOriginZ"
                        + "\tx\tz\toutcome";
        String expected = legacyHeader + "\tbatch";
        if (lines.isEmpty() || (!lines.get(0).equals(expected)
                && !lines.get(0).equals(legacyHeader))) {
            throw new IOException("invalid worldgen-light-events.tsv header");
        }
        boolean hasBatchColumn = lines.get(0).equals(expected);
        List<WorldgenLightEventRef> out = new ArrayList<>();
        long expectedOrder = 0L;
        long lastCut = -1L;
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] f = line.split("\t", -1);
            if (f.length != (hasBatchColumn ? 9 : 8)) {
                throw new IOException("invalid worldgen-light-event row: " + line);
            }
            long cut = Long.parseLong(f[0]);
            long order = Long.parseLong(f[1]);
            if (cut < lastCut || order != ++expectedOrder) {
                throw new IOException(
                        "non-monotone worldgen light cut/order: " + line);
            }
            lastCut = cut;
            if (!f[2].equals("minecraft:initialize_light")
                    && !f[2].equals("minecraft:light")) {
                throw new IOException("unknown worldgen light stage: " + line);
            }
            Integer activeX = f[3].isBlank() ? null : Integer.valueOf(f[3]);
            Integer activeZ = f[4].isBlank() ? null : Integer.valueOf(f[4]);
            if ((activeX == null) != (activeZ == null)) {
                throw new IOException(
                        "partial active origin in worldgen light row: " + line);
            }
            out.add(new WorldgenLightEventRef(
                    cut, order, f[2], activeX, activeZ,
                    Integer.parseInt(f[5]), Integer.parseInt(f[6]), f[7],
                    hasBatchColumn ? Long.parseLong(f[8]) : 0L));
        }
        return List.copyOf(out);
    }

    private static List<SpawnEventRef> readSpawnEvents(Path root)
            throws IOException {
        Path file = root.resolve("spawn-data.tsv");
        if (!Files.isRegularFile(file)) return List.of();
        List<String> lines = Files.readAllLines(file);
        String expected =
                "seq\tafterEventSeq\tafterInitializeLightOrder\tafterFullLightOrder"
                        + "\tafterWorldgenLightOrder\tactiveOriginX\tactiveOriginZ"
                        + "\tthread\tx\tz\tseed\tdimension"
                        + "\tbeforeCount\tafterCount\tdeltaEntities";
        if (lines.isEmpty() || !lines.get(0).equals(expected)) {
            throw new IOException("invalid spawn-data.tsv header");
        }
        List<SpawnEventRef> out = new ArrayList<>();
        long expectedSeq = 0L;
        long lastFeatureCut = -1L;
        long lastInitCut = -1L;
        long lastFullCut = -1L;
        long lastWorldgenCut = -1L;
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] f = line.split("\t", 15);
            if (f.length != 15) {
                throw new IOException("invalid SPAWN row: " + line);
            }
            long seq = Long.parseLong(f[0]);
            long featureCut = Long.parseLong(f[1]);
            long initCut = Long.parseLong(f[2]);
            long fullCut = Long.parseLong(f[3]);
            long worldgenCut = Long.parseLong(f[4]);
            if (seq != ++expectedSeq
                    || featureCut < lastFeatureCut
                    || initCut < lastInitCut
                    || fullCut < lastFullCut
                    || worldgenCut < lastWorldgenCut) {
                throw new IOException("non-monotone SPAWN sequence/cutpoint: " + line);
            }
            lastFeatureCut = featureCut;
            lastInitCut = initCut;
            lastFullCut = fullCut;
            lastWorldgenCut = worldgenCut;
            Integer activeX = f[5].isBlank() ? null : Integer.valueOf(f[5]);
            Integer activeZ = f[6].isBlank() ? null : Integer.valueOf(f[6]);
            if ((activeX == null) != (activeZ == null)) {
                throw new IOException("partial active origin in SPAWN row: " + line);
            }
            int before = Integer.parseInt(f[12]);
            int after = Integer.parseInt(f[13]);
            if (before < 0 || after < before) {
                throw new IOException("invalid SPAWN entity counts: " + line);
            }
            out.add(new SpawnEventRef(
                    seq, featureCut, initCut, fullCut, worldgenCut,
                    activeX, activeZ, f[7],
                    Integer.parseInt(f[8]), Integer.parseInt(f[9]),
                    Long.parseLong(f[10]), f[11],
                    before, after, f[14]));
        }
        return List.copyOf(out);
    }

    private static Map<Long, List<SpawnDeltaRef>> readSpawnDeltas(Path root)
            throws IOException {
        Path file = root.resolve("spawn-chunk-deltas.tsv");
        if (!Files.isRegularFile(file)) return Map.of();
        List<String> lines = Files.readAllLines(file);
        String expected =
                "spawnSeq\ttargetX\ttargetZ\tbeforeCount\tafterCount\tdeltaEntities";
        if (lines.isEmpty() || !lines.get(0).equals(expected)) {
            throw new IOException("invalid spawn-chunk-deltas.tsv header");
        }
        Map<Long, List<SpawnDeltaRef>> out = new LinkedHashMap<>();
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] f = line.split("\t", 6);
            if (f.length != 6) {
                throw new IOException("invalid SPAWN target-delta row: " + line);
            }
            long seq = Long.parseLong(f[0]);
            int before = Integer.parseInt(f[3]);
            int after = Integer.parseInt(f[4]);
            if (seq <= 0 || before < 0 || after <= before) {
                throw new IOException("invalid SPAWN target-delta counts: " + line);
            }
            SpawnDeltaRef ref = new SpawnDeltaRef(
                    seq, Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                    before, after, f[5]);
            List<SpawnDeltaRef> rows =
                    out.computeIfAbsent(seq, unused -> new ArrayList<>());
            for (SpawnDeltaRef prior : rows) {
                if (prior.targetX() == ref.targetX()
                        && prior.targetZ() == ref.targetZ()) {
                    throw new IOException(
                            "duplicate SPAWN target delta for seq=" + seq
                                    + " chunk=" + ref.targetX() + "," + ref.targetZ());
                }
            }
            rows.add(ref);
        }
        Map<Long, List<SpawnDeltaRef>> frozen = new LinkedHashMap<>();
        for (var e : out.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private static void validateSpawnLightCuts(
            List<SpawnEventRef> spawns,
            List<WorldgenLightEventRef> lights) throws IOException {
        int lightCursor = 0;
        long initCount = 0L;
        long fullCount = 0L;
        for (SpawnEventRef spawn : spawns) {
            if (spawn.afterWorldgenLightOrder() > lights.size()) {
                throw new IOException(
                        "SPAWN references future worldgen-light order "
                                + spawn.afterWorldgenLightOrder()
                                + " > " + lights.size());
            }
            while (lightCursor < spawn.afterWorldgenLightOrder()) {
                WorldgenLightEventRef light = lights.get(lightCursor++);
                if (light.stage().equals("minecraft:initialize_light")) {
                    initCount++;
                } else if (light.stage().equals("minecraft:light")) {
                    fullCount++;
                } else {
                    throw new IOException("unknown worldgen light stage " + light.stage());
                }
            }
            if (spawn.afterInitializeLightOrder() != initCount
                    || spawn.afterFullLightOrder() != fullCount) {
                throw new IOException(
                        "SPAWN light cut disagrees with unified trace seq="
                                + spawn.sequence()
                                + " expected init/full="
                                + spawn.afterInitializeLightOrder() + "/"
                                + spawn.afterFullLightOrder()
                                + " unified=" + initCount + "/" + fullCount);
            }
        }
    }

    private static String normalizedEntityDelta(
            ProtoChunk chunk, int fromIndex) {
        List<?> entities = chunk.getEntities();
        if (fromIndex < 0 || fromIndex > entities.size()) {
            throw new IllegalArgumentException(
                    "invalid entity delta baseline " + fromIndex
                            + " for chunk " + chunk.getPos()
                            + " size=" + entities.size());
        }
        return normalizeEntityNbt(
                new ArrayList<>(entities.subList(fromIndex, entities.size()))
                        .toString());
    }

    private static final class SpawnReplayState {
        private final List<SpawnEventRef> events;
        private final Map<Long, List<SpawnDeltaRef>> deltas;
        private final List<WorldgenLightEventRef> lights;
        private int cursor;
        private int lightCursor;
        private long matched;
        private long insertions;
        private long fullLightReplays;

        SpawnReplayState(
                List<SpawnEventRef> events,
                Map<Long, List<SpawnDeltaRef>> deltas,
                List<WorldgenLightEventRef> lights,
                long maxFeatureEventSeq) throws IOException {
            this.events = List.copyOf(events);
            this.deltas = Map.copyOf(deltas);
            this.lights = List.copyOf(lights);
            if (events.isEmpty()) {
                throw new IOException("SPAWN gate is vacuous: spawn-data.tsv has no rows");
            }
            validateSpawnLightCuts(events, lights);
            long expectedSeq = 0L;
            long expectedInsertions = 0L;
            for (SpawnEventRef event : events) {
                if (event.sequence() != ++expectedSeq) {
                    throw new IOException("non-contiguous SPAWN sequence");
                }
                if (event.afterEventSeq() < 0
                        || event.afterEventSeq() > maxFeatureEventSeq) {
                    throw new IOException(
                            "SPAWN feature cut outside trace seq="
                                    + event.sequence()
                                    + " cut=" + event.afterEventSeq()
                                    + " max=" + maxFeatureEventSeq);
                }
                if (event.activeOriginX() != null
                        || event.activeOriginZ() != null) {
                    throw new IOException(
                            "SPAWN overlaps active FEATURES; finer interleave required seq="
                                    + event.sequence());
                }
                if (event.seed() != SEED
                        || !event.dimension().equals(
                                "ResourceKey[minecraft:dimension / minecraft:overworld]")) {
                    throw new IOException(
                            "SPAWN seed/dimension mismatch seq=" + event.sequence());
                }
                for (SpawnDeltaRef delta :
                        deltas.getOrDefault(event.sequence(), List.of())) {
                    expectedInsertions += delta.afterCount() - delta.beforeCount();
                }
            }
            for (long seq : deltas.keySet()) {
                if (seq <= 0 || seq > events.size()) {
                    throw new IOException(
                            "SPAWN target delta references unknown sequence " + seq);
                }
            }
            if (expectedInsertions <= 0) {
                throw new IOException(
                        "SPAWN gate is vacuous: no captured target entity insertions");
            }
            System.out.println(
                    "SPAWN_TRACE rows=" + events.size()
                            + " targetDeltaRows="
                            + deltas.values().stream().mapToLong(List::size).sum()
                            + " targetInsertions=" + expectedInsertions
                            + " mode=native-assert");
        }

        void replayBoundary(
                long afterEventSeq,
                HeadlessNoise.Driver driver,
                HeadlessNoise.Structural structural,
                Map<Long, ChunkAccess> grid,
                WorldgenLightLifecycle lighting,
                Event previousEvent,
                Event nextEvent) throws Exception {
            if (cursor < events.size()
                    && events.get(cursor).afterEventSeq() < afterEventSeq) {
                throw new IOException(
                        "missed SPAWN cut seq=" + events.get(cursor).sequence()
                                + " capturedAfter="
                                + events.get(cursor).afterEventSeq()
                                + " replayAfter=" + afterEventSeq);
            }
            while (cursor < events.size()
                    && events.get(cursor).afterEventSeq() == afterEventSeq) {
                SpawnEventRef spawn = events.get(cursor);
                replayLightThrough(
                        spawn.afterWorldgenLightOrder(),
                        afterEventSeq,
                        grid,
                        lighting,
                        previousEvent,
                        nextEvent);
                replayOne(
                        events.get(cursor++),
                        driver,
                        structural,
                        grid,
                        lighting);
            }

            // Preserve light-engine state for later FEATURES/SPAWN even when
            // no SPAWN event happened immediately after a light completion.
            long target = lightCursor;
            while (target < lights.size()
                    && lights.get((int) target).afterEventSeq() <= afterEventSeq) {
                target++;
            }
            replayLightThrough(
                    target,
                    afterEventSeq,
                    grid,
                    lighting,
                    previousEvent,
                    nextEvent);
        }

        private void replayLightThrough(
                long targetOrder,
                long boundaryEventSeq,
                Map<Long, ChunkAccess> grid,
                WorldgenLightLifecycle lighting,
                Event previousEvent,
                Event nextEvent) throws IOException {
            if (targetOrder < lightCursor || targetOrder > lights.size()) {
                throw new IOException(
                        "invalid unified light target target=" + targetOrder
                                + " cursor=" + lightCursor
                                + " rows=" + lights.size());
            }
            while (lightCursor < targetOrder) {
                WorldgenLightEventRef event = lights.get(lightCursor);
                long expectedOrder = lightCursor + 1L;
                if (event.order() != expectedOrder) {
                    throw new IOException(
                            "unified worldgen-light order gap expected="
                                    + expectedOrder + " actual=" + event.order());
                }
                if (event.afterEventSeq() > boundaryEventSeq) {
                    throw new IOException(
                            "SPAWN requires future worldgen-light completion order="
                                    + event.order()
                                    + " lightCut=" + event.afterEventSeq()
                                    + " spawnBoundary=" + boundaryEventSeq);
                }
                if (!event.outcome().equals("OK")) {
                    throw new IOException(
                            "captured worldgen-light failure order=" + event.order()
                                    + " outcome=" + event.outcome());
                }

                if (event.activeOriginX() != null) {
                    boolean rightEdge =
                            previousEvent != null
                                    && previousEvent.sequence() == event.afterEventSeq()
                                    && previousEvent.phase().equals("ENTER")
                                    && previousEvent.x() == event.activeOriginX()
                                    && previousEvent.z() == event.activeOriginZ()
                                    && nextEvent != null
                                    && nextEvent.sequence() == event.afterEventSeq() + 1L
                                    && nextEvent.phase().equals("COMPLETE")
                                    && nextEvent.x() == event.activeOriginX()
                                    && nextEvent.z() == event.activeOriginZ();
                    if (!rightEdge) {
                        throw new IOException(
                                "cannot linearize intra-FEATURES worldgen-light completion"
                                        + " order=" + event.order()
                                        + " stage=" + event.stage()
                                        + " cut=" + event.afterEventSeq()
                                        + " active=" + event.activeOriginX()
                                        + "," + event.activeOriginZ()
                                        + " previous=" + previousEvent
                                        + " next=" + nextEvent);
                    }
                }

                ChunkAccess raw = grid.get(ChunkPos.pack(event.x(), event.z()));
                if (!(raw instanceof ProtoChunk proto)) {
                    throw new IOException(
                            "worldgen-light target unavailable order=" + event.order()
                                    + " chunk=" + event.x() + "," + event.z());
                }
                if (event.stage().equals("minecraft:initialize_light")) {
                    // The frozen Step-8 lighting-events.tsv path already
                    // executed this real operation at the same FEATURES cut.
                    // Unified rows only assert that it is visible here.
                    if (proto.getPersistedStatus().isBefore(ChunkStatus.INITIALIZE_LIGHT)) {
                        throw new IOException(
                                "unified INITIALIZE_LIGHT not yet applied order="
                                        + event.order()
                                        + " chunk=" + event.x() + "," + event.z()
                                        + " status=" + proto.getPersistedStatus());
                    }
                } else if (event.stage().equals("minecraft:light")) {
                    if (proto.getPersistedStatus().isBefore(ChunkStatus.INITIALIZE_LIGHT)) {
                        throw new IOException(
                                "LIGHT before INITIALIZE_LIGHT order=" + event.order()
                                        + " chunk=" + event.x() + "," + event.z());
                    }
                    lighting.light(proto);
                    fullLightReplays++;
                } else {
                    throw new IOException(
                            "unknown worldgen-light stage " + event.stage());
                }
                lightCursor++;
            }
        }

        private void replayOne(
                SpawnEventRef expected,
                HeadlessNoise.Driver driver,
                HeadlessNoise.Structural structural,
                Map<Long, ChunkAccess> grid,
                WorldgenLightLifecycle lighting) throws Exception {
            long centerKey = ChunkPos.pack(expected.x(), expected.z());
            ChunkAccess centerRaw = grid.get(centerKey);
            if (!(centerRaw instanceof ProtoChunk center)) {
                throw new AssertionError(
                        "SPAWN center unavailable/not ProtoChunk "
                                + expected.x() + "," + expected.z());
            }

            int centerBefore = center.getEntities().size();
            if (centerBefore != expected.beforeCount()) {
                throw new AssertionError(
                        "SPAWN center baseline mismatch seq=" + expected.sequence()
                                + " center=" + expected.x() + "," + expected.z()
                                + " vanilla=" + expected.beforeCount()
                                + " kestrel=" + centerBefore);
            }

            Map<Long, Integer> before = new HashMap<>();
            for (var entry : grid.entrySet()) {
                if (entry.getValue() instanceof ProtoChunk proto) {
                    before.put(entry.getKey(), proto.getEntities().size());
                }
            }

            HeadlessNoise.RegionView view =
                    new HeadlessNoise.RegionView(
                            driver,
                            new ChunkPos(expected.x(), expected.z()),
                            grid,
                            lighting.engine(),
                            structural == null ? null : structural.templates());
            HeadlessNoise.spawnOriginalMobs(driver, structural, view);

            List<SpawnDeltaRef> expectedTargets =
                    deltas.getOrDefault(expected.sequence(), List.of());
            Map<Long, SpawnDeltaRef> expectedByTarget = new LinkedHashMap<>();
            for (SpawnDeltaRef ref : expectedTargets) {
                long key = ChunkPos.pack(ref.targetX(), ref.targetZ());
                if (expectedByTarget.put(key, ref) != null) {
                    throw new IOException(
                            "duplicate SPAWN target delta seq="
                                    + expected.sequence()
                                    + " target=" + ref.targetX() + "," + ref.targetZ());
                }
            }

            Map<Long, ProtoChunk> actualChanged = new LinkedHashMap<>();
            for (var entry : before.entrySet()) {
                ChunkAccess raw = grid.get(entry.getKey());
                if (!(raw instanceof ProtoChunk proto)) continue;
                int oldCount = entry.getValue();
                int newCount = proto.getEntities().size();
                if (newCount < oldCount) {
                    throw new AssertionError(
                            "SPAWN shrank entity list chunk=" + proto.getPos());
                }
                if (newCount > oldCount) {
                    actualChanged.put(entry.getKey(), proto);
                }
            }
            if (!actualChanged.keySet().equals(expectedByTarget.keySet())) {
                throw new AssertionError(
                        "SPAWN changed-target mismatch seq=" + expected.sequence()
                                + " vanilla=" + expectedByTarget.keySet()
                                + " kestrel=" + actualChanged.keySet());
            }

            long inserted = 0L;
            for (var entry : actualChanged.entrySet()) {
                long key = entry.getKey();
                ProtoChunk proto = entry.getValue();
                SpawnDeltaRef ref = expectedByTarget.get(key);
                int oldCount = before.get(key);
                int newCount = proto.getEntities().size();
                if (oldCount != ref.beforeCount()
                        || newCount != ref.afterCount()) {
                    throw new AssertionError(
                            "SPAWN target count mismatch seq=" + expected.sequence()
                                    + " target=" + ref.targetX() + "," + ref.targetZ()
                                    + " vanilla=" + ref.beforeCount() + "->"
                                    + ref.afterCount()
                                    + " kestrel=" + oldCount + "->" + newCount);
                }
                String actualDelta = normalizedEntityDelta(proto, oldCount);
                String expectedDelta = normalizeEntityNbt(ref.deltaEntities());
                if (!actualDelta.equals(expectedDelta)) {
                    throw new AssertionError(
                            "SPAWN target NBT mismatch seq=" + expected.sequence()
                                    + " target=" + ref.targetX() + "," + ref.targetZ()
                                    + "\n  vanilla=" + expectedDelta
                                    + "\n  kestrel=" + actualDelta);
                }
                inserted += newCount - oldCount;
            }

            int centerAfter = center.getEntities().size();
            String centerDelta = normalizedEntityDelta(center, centerBefore);
            String expectedCenterDelta =
                    normalizeEntityNbt(expected.deltaEntities());
            if (centerAfter != expected.afterCount()
                    || !centerDelta.equals(expectedCenterDelta)) {
                throw new AssertionError(
                        "SPAWN center delta mismatch seq=" + expected.sequence()
                                + " center=" + expected.x() + "," + expected.z()
                                + " vanillaCount=" + expected.beforeCount()
                                + "->" + expected.afterCount()
                                + " kestrelCount=" + centerBefore + "->" + centerAfter
                                + "\n  vanilla=" + expectedCenterDelta
                                + "\n  kestrel=" + centerDelta);
            }

            matched++;
            insertions += inserted;
            if (inserted != 0) {
                System.out.println(
                        "SPAWN_MATCH seq=" + expected.sequence()
                                + " afterEventSeq=" + expected.afterEventSeq()
                                + " center=" + expected.x() + "," + expected.z()
                                + " insertions=" + inserted);
            }
        }

        void finish() {
            if (cursor != events.size()) {
                SpawnEventRef next = events.get(cursor);
                throw new AssertionError(
                        "SPAWN replay coverage incomplete matched=" + matched
                                + " captured=" + events.size()
                                + " nextSeq=" + next.sequence()
                                + " nextCut=" + next.afterEventSeq());
            }
            if (lightCursor != lights.size()) {
                throw new AssertionError(
                        "unified worldgen-light replay incomplete replayed="
                                + lightCursor + " captured=" + lights.size());
            }
            if (insertions <= 0) {
                throw new AssertionError(
                        "SPAWN replay was vacuous despite nonempty oracle");
            }
            System.out.println(
                    "SPAWN_RESULT matched=" + matched
                            + " captured=" + events.size()
                            + " insertions=" + insertions
                            + " fullLightReplays=" + fullLightReplays
                            + " PASS");
        }
    }

    private record LightingEventRef(
            long afterEventSeq,
            long order,
            Integer activeOriginX,
            Integer activeOriginZ,
            int x,
            int z,
            String outcome) {}

    private static Map<Long, List<LightingEventRef>> readLightingEvents(Path root)
            throws IOException {
        Path file = root.resolve("lighting-events.tsv");
        if (!Files.exists(file)) return Map.of();

        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty()) return Map.of();
        String expectedHeader =
                "afterEventSeq\torder\tactiveOriginX\tactiveOriginZ"
                        + "\tx\tz\toutcome";
        if (!lines.get(0).equals(expectedHeader)) return Map.of();

        Map<Long, List<LightingEventRef>> rows = new HashMap<>();
        long expectedOrder = 0L;
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 7) {
                throw new IOException("invalid lighting-event row: " + line);
            }
            long afterEventSeq = Long.parseLong(fields[0]);
            long order = Long.parseLong(fields[1]);
            if (afterEventSeq < 0L || order != ++expectedOrder) {
                throw new IOException(
                        "non-contiguous lighting-event order/cutpoint: " + line);
            }
            Integer activeX = fields[2].isBlank()
                    ? null : Integer.valueOf(fields[2]);
            Integer activeZ = fields[3].isBlank()
                    ? null : Integer.valueOf(fields[3]);
            if ((activeX == null) != (activeZ == null)) {
                throw new IOException(
                        "partial active origin in lighting-event row: " + line);
            }
            LightingEventRef ref = new LightingEventRef(
                    afterEventSeq,
                    order,
                    activeX,
                    activeZ,
                    Integer.parseInt(fields[4]),
                    Integer.parseInt(fields[5]),
                    fields[6]);
            rows.computeIfAbsent(
                    afterEventSeq, unused -> new ArrayList<>()).add(ref);
        }
        return rows;
    }

    private static TraceRef traceRefFor(Event event, int x, int z) {
        for (TraceRef ref : event.refs()) {
            if (ref.x() == x && ref.z() == z) {
                return ref;
            }
        }
        return null;
    }

    /**
     * The legacy lighting trace records an INITIALIZE_LIGHT completion only
     * as occurring somewhere inside an active FEATURES ENTER->COMPLETE
     * interval. Replaying it at the right edge is valid only when moving the
     * initialization across the remainder of that interval cannot alter
     * Vanilla light bookkeeping.
     *
     * <p>For targets outside the active center's radius-1 write window, the
     * active FEATURES invocation cannot mutate the chunk, so the move is
     * trivially safe. For a target inside that window, prove equivalence from
     * the captured ENTER/COMPLETE block snapshots using the same two
     * conditions ProtoChunk.setBlockState uses after INITIALIZE_LIGHT:
     * section empty/non-empty state must not change, and every changed block
     * pair must have identical light properties according to Vanilla's
     * LightEngine.hasDifferentLightProperties. Captured blocks are assertion
     * data only; none are copied into Kestrel.
     */
    private static void assertIntraLightingBoundaryEquivalent(
            Path root,
            LightingEventRef lightingEvent,
            Event enter,
            Event complete) throws Exception {
        int dx = Math.abs(lightingEvent.x() - lightingEvent.activeOriginX());
        int dz = Math.abs(lightingEvent.z() - lightingEvent.activeOriginZ());
        if (Math.max(dx, dz) > 1) {
            return;
        }

        TraceRef beforeRef =
                traceRefFor(enter, lightingEvent.x(), lightingEvent.z());
        TraceRef afterRef =
                traceRefFor(complete, lightingEvent.x(), lightingEvent.z());
        if (beforeRef == null || afterRef == null) {
            throw new IOException(
                    "intra-FEATURES lighting target inside write radius is absent "
                            + "from interval snapshots: origin="
                            + lightingEvent.activeOriginX() + ","
                            + lightingEvent.activeOriginZ()
                            + " chunk=" + lightingEvent.x() + ","
                            + lightingEvent.z());
        }

        Snapshot before = read(root, beforeRef);
        Snapshot after = read(root, afterRef);
        if (before.x() != after.x()
                || before.z() != after.z()
                || before.minY() != after.minY()
                || before.height() != after.height()
                || before.ids().length != after.ids().length) {
            throw new IOException(
                    "intra-FEATURES lighting snapshots are incompatible for chunk "
                            + lightingEvent.x() + "," + lightingEvent.z());
        }

        int changedCells = 0;
        int cellsPerSection = 16 * 16 * 16;
        int sectionCount = before.ids().length / cellsPerSection;
        for (int section = 0; section < sectionCount; section++) {
            int from = section * cellsPerSection;
            int to = from + cellsPerSection;
            boolean beforeNonAir = false;
            boolean afterNonAir = false;
            for (int i = from; i < to; i++) {
                if (!Block.stateById(before.ids()[i]).isAir()) beforeNonAir = true;
                if (!Block.stateById(after.ids()[i]).isAir()) afterNonAir = true;
                if (beforeNonAir && afterNonAir) break;
            }
            if (beforeNonAir != afterNonAir) {
                throw new IOException(
                        "cannot linearize intra-FEATURES INITIALIZE_LIGHT: "
                                + "section empty-state changed origin="
                                + lightingEvent.activeOriginX() + ","
                                + lightingEvent.activeOriginZ()
                                + " chunk=" + lightingEvent.x() + ","
                                + lightingEvent.z()
                                + " section=" + section
                                + " beforeNonAir=" + beforeNonAir
                                + " afterNonAir=" + afterNonAir);
            }
        }

        for (int i = 0; i < before.ids().length; i++) {
            int beforeId = before.ids()[i];
            int afterId = after.ids()[i];
            if (beforeId == afterId) continue;
            changedCells++;
            var beforeState = Block.stateById(beforeId);
            var afterState = Block.stateById(afterId);
            if (LightEngine.hasDifferentLightProperties(beforeState, afterState)) {
                int y = before.minY() + (i >> 8);
                int x = before.x() * 16 + (i & 15);
                int z = before.z() * 16 + ((i >> 4) & 15);
                throw new IOException(
                        "cannot linearize intra-FEATURES INITIALIZE_LIGHT: "
                                + "light-relevant block change origin="
                                + lightingEvent.activeOriginX() + ","
                                + lightingEvent.activeOriginZ()
                                + " chunk=" + lightingEvent.x() + ","
                                + lightingEvent.z()
                                + " pos=" + x + "," + y + "," + z
                                + " before=" + beforeState
                                + " after=" + afterState);
            }
        }

        System.out.println(
                "LIGHTING_INTERVAL_EQUIVALENT origin="
                        + lightingEvent.activeOriginX() + ","
                        + lightingEvent.activeOriginZ()
                        + " chunk=" + lightingEvent.x() + ","
                        + lightingEvent.z()
                        + " changedCells=" + changedCells);
    }

    private static long replayLightingEvents(
            Path root,
            long afterEventSeq,
            Map<Long, List<LightingEventRef>> events,
            Map<Long, ChunkAccess> grid,
            WorldgenLightLifecycle lighting,
            Event previousEvent,
            Event nextEvent,
            java.util.Set<Long> preAppliedIntraSeqs) throws Exception {
        long applied = 0L;
        for (LightingEventRef event :
                events.getOrDefault(afterEventSeq, List.of())) {
            boolean intraFeatures = event.activeOriginX() != null;
            if (intraFeatures && preAppliedIntraSeqs.contains(afterEventSeq)) {
                // Already applied before the pass this interval belongs to; see
                // replayIntraPassLightingEvents.
                continue;
            }
            if (intraFeatures) {
                // The legacy lifecycle trace records this completion in the
                // open interval after a FEATURES ENTER row and before that
                // invocation's immediately following COMPLETE row. It does
                // not claim a finer ordering against individual feature
                // calls. Kestrel executes FEATURES atomically, so use the
                // right edge only after proving that this linearization is
                // semantically equivalent for Vanilla's light bookkeeping.
                //
                // Fail closed unless both interval boundaries are exact. No
                // completion may move across an origin boundary or another
                // recorded FEATURES event.
                if (previousEvent == null
                        || previousEvent.sequence() != afterEventSeq
                        || !previousEvent.phase().equals("ENTER")
                        || previousEvent.x() != event.activeOriginX()
                        || previousEvent.z() != event.activeOriginZ()
                        || nextEvent == null
                        || nextEvent.sequence() != afterEventSeq + 1L
                        || !nextEvent.phase().equals("COMPLETE")
                        || nextEvent.x() != event.activeOriginX()
                        || nextEvent.z() != event.activeOriginZ()) {
                    throw new IOException(
                            "cannot place intra-FEATURES INITIALIZE_LIGHT completion"
                                    + " at its recorded interval boundary: origin="
                                    + event.activeOriginX() + ","
                                    + event.activeOriginZ()
                                    + " afterEventSeq=" + afterEventSeq
                                    + " nextEvent=" + nextEvent
                                    + " chunk=" + event.x() + "," + event.z());
                }
                assertIntraLightingBoundaryEquivalent(
                        root, event, previousEvent, nextEvent);
            }
            if (applyLightingEventRef(
                    afterEventSeq, event, intraFeatures, grid, lighting)) {
                applied++;
            }
        }
        return applied;
    }

    /**
     * Execute one captured light completion against the replay grid. Returns
     * false when the target is outside the replay grid, which is reported but
     * not applied.
     */
    private static boolean applyLightingEventRef(
            long afterEventSeq,
            LightingEventRef event,
            boolean intraFeatures,
            Map<Long, ChunkAccess> grid,
            WorldgenLightLifecycle lighting) throws Exception {
            if (!event.outcome().equals("OK")) {
                throw new IOException(
                        "captured INITIALIZE_LIGHT completed exceptionally: "
                                + event.outcome()
                                + " chunk=" + event.x() + "," + event.z());
            }
            ChunkAccess chunk = grid.get(ChunkPos.pack(event.x(), event.z()));
            if (chunk == null) {
                System.out.println(
                        "LIGHTING_EVENT_OUTSIDE_REPLAY afterEventSeq="
                                + afterEventSeq
                                + " order=" + event.order()
                                + " chunk=" + event.x() + "," + event.z());
                return false;
            }
            if (!(chunk instanceof ProtoChunk proto)) {
                throw new IOException(
                        "lighting replay target is not ProtoChunk: "
                                + event.x() + "," + event.z()
                                + " class=" + chunk.getClass().getName());
            }
            lighting.initialize(proto);
            System.out.println(
                    (intraFeatures
                            ? "LIGHTING_EVENT_REPLAY_INTRA_INTERVAL"
                            : "LIGHTING_EVENT_REPLAY")
                            + " afterEventSeq=" + afterEventSeq
                            + " order=" + event.order()
                            + " chunk=" + event.x() + "," + event.z());
            return true;
    }

    /**
     * Apply the light completions of an open FEATURES interval before the pass
     * itself, for the case where the completion's effect on the pass is
     * observable.
     *
     * <p>The capture stamps a completion with the last FEATURES event it had
     * recorded, so a completion stamped with a pass's own ENTER sequence was
     * emitted by Vanilla's light executor while that pass was running. The
     * worldgen thread does not wait for the light executor, so the real
     * position is somewhere inside the pass; the interval boundary is all the
     * legacy trace can express. Kestrel executes FEATURES atomically, and the
     * two edges are not equivalent when the completion registers light state
     * that the pass itself reads (a neighbour's INITIALIZE_LIGHT bumping a
     * zero-filled sky layer over the origin, which turns a light-dependent
     * scatter placement from 15 into below 13).
     *
     * <p>Applying before the pass is a schedule Vanilla could have produced:
     * the completion's target must already have completed FEATURES, which is
     * exactly what the light engine's chunk getter requires, and the target's
     * FEATURES COMPLETE is an event of its own, so it precedes this pass's
     * ENTER in the recorded stream. That is asserted here; a capture that
     * violates it fails closed instead of being placed on a guess.
     */
    private static long replayIntraPassLightingEvents(
            Path root,
            Event enter,
            Event complete,
            Map<Long, List<LightingEventRef>> events,
            Map<Long, ChunkAccess> grid,
            WorldgenLightLifecycle lighting,
            Map<Long, Event> completeByOrigin) throws Exception {
        List<LightingEventRef> rows =
                events.getOrDefault(enter.sequence(), List.of());
        if (rows.isEmpty()) {
            return 0L;
        }
        List<LightingEventRef> intra = new ArrayList<>();
        for (LightingEventRef row : rows) {
            if (row.activeOriginX() == null) {
                // A row outside any pass cannot be placed inside one; leave it
                // to the interval path.
                continue;
            }
            if (row.activeOriginX() != enter.x() || row.activeOriginZ() != enter.z()) {
                throw new IOException(
                        "intra-FEATURES light completion stamped with a pass it "
                                + "does not belong to: origin="
                                + row.activeOriginX() + "," + row.activeOriginZ()
                                + " afterEventSeq=" + enter.sequence()
                                + " enter=" + enter.x() + "," + enter.z());
            }
            Event targetComplete =
                    completeByOrigin.get(ChunkPos.pack(row.x(), row.z()));
            if (targetComplete == null) {
                throw new IOException(
                        "intra-FEATURES light completion for " + row.x() + "," + row.z()
                                + " has no captured FEATURES COMPLETE, so Vanilla"
                                + " cannot have had it ready before pass "
                                + enter.x() + "," + enter.z());
            }
            if (targetComplete.sequence() >= enter.sequence()) {
                throw new IOException(
                        "intra-FEATURES light completion for " + row.x() + "," + row.z()
                                + " targets a chunk whose FEATURES COMPLETE is "
                                + targetComplete.sequence()
                                + ", not before pass " + enter.x() + "," + enter.z()
                                + " ENTER at " + enter.sequence()
                                + "; placing it before the pass is not a schedule"
                                + " Vanilla could have produced");
            }
            if (Math.max(Math.abs(row.x() - enter.x()),
                    Math.abs(row.z() - enter.z())) <= 1) {
                assertIntraLightingBoundaryEquivalent(root, row, enter, complete);
            }
            intra.add(row);
        }
        long applied = 0L;
        for (LightingEventRef row : intra) {
            if (applyLightingEventRef(
                    enter.sequence(), row, true, grid, lighting)) {
                applied++;
            }
        }
        return applied;
    }

    private static List<Long> parseStructureReferenceOrder(String raw)
            throws IOException {
        if (raw.isBlank()) return List.of();
        List<Long> order = new ArrayList<>();
        for (String entry : raw.split(";")) {
            int open = entry.lastIndexOf('(');
            int close = entry.lastIndexOf(')');
            if (open < 0 || close != entry.length() - 1 || open >= close) {
                throw new IOException(
                        "invalid structure-reference iterator entry: " + entry);
            }
            order.add(Long.parseLong(entry.substring(open + 1, close)));
        }
        return List.copyOf(order);
    }

    private static Map<Long, List<StructureReferenceStateRef>>
            readStructureReferenceState(Path root) throws IOException {
        Path file = root.resolve("structure-reference-state.tsv");
        if (!Files.exists(file)) return Map.of();

        Map<Long, List<StructureReferenceStateRef>> rows = new HashMap<>();
        List<String> lines = Files.readAllLines(file);
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 13) {
                throw new IOException(
                        "invalid structure-reference-state row: " + line);
            }
            int originX = Integer.parseInt(fields[0]);
            int originZ = Integer.parseInt(fields[1]);
            int index = Integer.parseInt(fields[2]);
            StructureReferenceStateRef ref = new StructureReferenceStateRef(
                    index,
                    Integer.parseInt(fields[3]),
                    Integer.parseInt(fields[4]),
                    Integer.parseInt(fields[5]),
                    Integer.parseInt(fields[6]),
                    fields[7],
                    fields[8],
                    Integer.parseInt(fields[9]),
                    Integer.parseInt(fields[10]),
                    Integer.parseInt(fields[11]),
                    parseStructureReferenceOrder(fields[12]));
            List<StructureReferenceStateRef> list = rows.computeIfAbsent(
                    ChunkPos.pack(originX, originZ),
                    unused -> new ArrayList<>());
            if (index != list.size() + 1) {
                throw new IOException(
                        "non-contiguous structure-reference-state trace for origin "
                                + originX + "," + originZ
                                + ": expected index " + (list.size() + 1)
                                + " got " + index);
            }
            list.add(ref);
        }
        return rows;
    }

    private static int structureReferenceIntField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return -1;
            }
        }
        return -1;
    }

    private static List<Long> structureReferenceOrder(
            it.unimi.dsi.fastutil.longs.LongSet refs) {
        List<Long> order = new ArrayList<>();
        for (long packed : refs) order.add(packed);
        return List.copyOf(order);
    }

    private static net.minecraft.world.level.levelgen.structure.Structure
            resolveStructureForReferenceState(
                    StructureReferenceStateRef state,
                    StructureSourceRef source,
                    Map<Long, ChunkAccess> grid) throws IOException {
        if (state.index() != source.index()
                || state.targetX() != source.targetX()
                || state.targetZ() != source.targetZ()
                || state.sourceX() != source.sourceX()
                || state.sourceZ() != source.sourceZ()) {
            throw new IOException(
                    "structure-reference/source trace disagreement at index "
                            + state.index());
        }
        ChunkAccess sourceChunk = grid.get(
                ChunkPos.pack(source.sourceX(), source.sourceZ()));
        if (sourceChunk == null) {
            throw new IOException(
                    "structure-reference source chunk unavailable "
                            + source.sourceX() + "," + source.sourceZ());
        }

        net.minecraft.world.level.levelgen.structure.Structure matched = null;
        for (var start : sourceChunk.getAllStarts().values()) {
            ChunkPos startPos = start.getChunkPos();
            if (startPos.x() != source.sourceX()
                    || startPos.z() != source.sourceZ()
                    || !start.getStructure().getClass().getName()
                            .equals(source.structureClass())
                    || !start.getBoundingBox().toString().equals(source.box())) {
                continue;
            }
            if (matched != null && matched != start.getStructure()) {
                throw new IOException(
                        "ambiguous structure-reference source at "
                                + source.sourceX() + "," + source.sourceZ()
                                + " index=" + source.index());
            }
            matched = start.getStructure();
        }
        if (matched == null) {
            throw new IOException(
                    "cannot resolve structure-reference source at "
                            + source.sourceX() + "," + source.sourceZ()
                            + " index=" + source.index());
        }
        return matched;
    }

    private static void syncStructureReferenceLifecycle(
            ChunkPos center,
            List<StructureReferenceStateRef> expected,
            List<StructureSourceRef> sources,
            Map<Long, ChunkAccess> grid,
            boolean replayStructureOrder) throws IOException {
        if (expected.isEmpty()) return;
        if (expected.size() != sources.size()) {
            throw new IOException(
                    "structure-reference/source trace size mismatch origin="
                            + center.x() + "," + center.z()
                            + " states=" + expected.size()
                            + " sources=" + sources.size());
        }

        ChunkAccess target = grid.get(center.pack());
        if (target == null) {
            throw new IOException(
                    "structure-reference target unavailable " + center);
        }

        IdentityHashMap<
                net.minecraft.world.level.levelgen.structure.Structure,
                StructureReferenceStateRef> unique = new IdentityHashMap<>();
        for (int i = 0; i < expected.size(); i++) {
            StructureReferenceStateRef state = expected.get(i);
            StructureSourceRef source = sources.get(i);
            if (state.index() != i + 1
                    || state.targetX() != center.x()
                    || state.targetZ() != center.z()) {
                throw new IOException(
                        "invalid structure-reference-state origin="
                                + center.x() + "," + center.z()
                                + " index=" + state.index());
            }
            var structure =
                    resolveStructureForReferenceState(state, source, grid);
            StructureReferenceStateRef prior = unique.putIfAbsent(
                    structure, state);
            if (prior != null
                    && (prior.size() != state.size()
                            || prior.n() != state.n()
                            || prior.minN() != state.minN()
                            || !prior.iteratorOrder().equals(
                                    state.iteratorOrder()))) {
                throw new IOException(
                        "structure-reference state changed during one FEATURES "
                                + "call origin=" + center.x() + "," + center.z());
            }
        }

        boolean needsRoundTrip = false;
        for (var entry : unique.entrySet()) {
            var refs = target.getReferencesForStructure(entry.getKey());
            StructureReferenceStateRef state = entry.getValue();
            if (refs.size() != state.size()
                    || !new HashSet<>(structureReferenceOrder(refs))
                            .equals(new HashSet<>(state.iteratorOrder()))) {
                throw new IOException(
                        "structure-reference membership mismatch origin="
                                + center.x() + "," + center.z()
                                + " expected=" + state.iteratorOrder()
                                + " actual=" + structureReferenceOrder(refs));
            }
            int n = structureReferenceIntField(refs, "n");
            int minN = structureReferenceIntField(refs, "minN");
            List<Long> order = structureReferenceOrder(refs);
            if (n != state.n()
                    || minN != state.minN()
                    || !order.equals(state.iteratorOrder())) {
                needsRoundTrip = true;
            }
        }

        // Captured reference-table state is an assertion in the real gate.
        // Rebuilding Kestrel's set because Vanilla says its n/order differed
        // would inject the oracle into generation. Keep that mutation behind
        // the explicit diagnostic flag only.
        if (needsRoundTrip && replayStructureOrder) {
            HeadlessNoise.roundTripStructureReferences(target);
        }

        for (var entry : unique.entrySet()) {
            var refs = target.getReferencesForStructure(entry.getKey());
            StructureReferenceStateRef state = entry.getValue();
            int n = structureReferenceIntField(refs, "n");
            int minN = structureReferenceIntField(refs, "minN");
            List<Long> order = structureReferenceOrder(refs);
            if (refs.size() != state.size()
                    || !refs.getClass().getName().equals(state.setClass())
                    || n != state.n()
                    || minN != state.minN()
                    || !order.equals(state.iteratorOrder())) {
                throw new IOException(
                        "structure-reference lifecycle mismatch origin="
                                + center.x() + "," + center.z()
                                + " expectedClass=" + state.chunkClass()
                                + " expectedSet=" + state.setClass()
                                + " expectedSize=" + state.size()
                                + " expectedN=" + state.n()
                                + " expectedMinN=" + state.minN()
                                + " expectedOrder=" + state.iteratorOrder()
                                + " actualChunk=" + target.getClass().getName()
                                + " actualSet=" + refs.getClass().getName()
                                + " actualSize=" + refs.size()
                                + " actualN=" + n
                                + " actualMinN=" + minN
                                + " actualOrder=" + order);
            }
        }

        structureReferenceStateComparisons += expected.size();
        System.out.println(
                "STRUCTURE_REFERENCE_LIFECYCLE_MATCH origin="
                        + center.x() + "," + center.z()
                        + " rows=" + expected.size()
                        + " roundTrip="
                        + (needsRoundTrip && replayStructureOrder)
                        + " mode="
                        + (replayStructureOrder ? "oracle-replay" : "native-assert"));
    }

    private static Map<Long, List<StructureSourceRef>> readStructureSources(Path root)
            throws IOException {
        Path file = root.resolve("structure-sources.tsv");
        if (!Files.exists(file)) return Map.of();

        Map<Long, List<StructureSourceRef>> rows = new HashMap<>();
        List<String> lines = Files.readAllLines(file);
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 9) {
                throw new IOException("invalid structure-source row: " + line);
            }
            int originX = Integer.parseInt(fields[0]);
            int originZ = Integer.parseInt(fields[1]);
            int index = Integer.parseInt(fields[2]);
            StructureSourceRef ref = new StructureSourceRef(
                    index,
                    Integer.parseInt(fields[3]),
                    Integer.parseInt(fields[4]),
                    Integer.parseInt(fields[5]),
                    Integer.parseInt(fields[6]),
                    fields[7],
                    fields[8]);
            List<StructureSourceRef> list = rows.computeIfAbsent(
                    ChunkPos.pack(originX, originZ), unused -> new ArrayList<>());
            if (index != list.size() + 1) {
                throw new IOException(
                        "non-contiguous structure-source trace for origin "
                                + originX + "," + originZ
                                + ": expected index " + (list.size() + 1)
                                + " got " + index);
            }
            list.add(ref);
        }
        return rows;
    }

    private static Map<Long, List<StructureCallRef>> readStructureCalls(Path root)
            throws IOException {
        Path file = root.resolve("structure-calls.tsv");
        if (!Files.exists(file)) return Map.of();

        Map<Long, List<StructureCallRef>> rows = new HashMap<>();
        List<String> lines = Files.readAllLines(file);
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 14) {
                throw new IOException("invalid structure-call row: " + line);
            }

            int originX = Integer.parseInt(fields[0]);
            int originZ = Integer.parseInt(fields[1]);
            int index = Integer.parseInt(fields[2]);
            StructureCallRef ref = new StructureCallRef(
                    index,
                    Integer.parseInt(fields[3]),
                    Integer.parseInt(fields[4]),
                    fields[5],
                    fields[6],
                    fields[7],
                    normalizeEntityNbt(fields[8]),
                    normalizeEntityNbt(fields[9]),
                    blockPositions(fields[10]),
                    blockPositions(fields[11]),
                    blockPositions(fields[12]),
                    blockPositions(fields[13]));

            List<StructureCallRef> list = rows.computeIfAbsent(
                    ChunkPos.pack(originX, originZ), unused -> new ArrayList<>());
            if (index != list.size() + 1) {
                throw new IOException(
                        "non-contiguous structure-call trace for origin "
                                + originX + "," + originZ
                                + ": expected index " + (list.size() + 1)
                                + " got " + index);
            }
            list.add(ref);
        }
        return rows;
    }

    /**
     * Game time around each captured structure placement, keyed by origin and holding
     * {@code {gameTimeEnter, gameTimeExit}} in call order. Optional: traces from the
     * frozen camp1 to camp3 capture stack lack the file, and the clock then stays at 0,
     * which is the behaviour those traces were verified with.
     */
    private static Map<Long, List<long[]>> readStructureCallTimes(Path root)
            throws IOException {
        Path file = root.resolve("structure-call-times.tsv");
        if (!Files.exists(file)) return Map.of();

        Map<Long, List<long[]>> rows = new HashMap<>();
        List<String> lines = Files.readAllLines(file);
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 5) {
                throw new IOException("invalid structure-call-times row: " + line);
            }
            int originX = Integer.parseInt(fields[0]);
            int originZ = Integer.parseInt(fields[1]);
            int index = Integer.parseInt(fields[2]);
            List<long[]> list = rows.computeIfAbsent(
                    ChunkPos.pack(originX, originZ), unused -> new ArrayList<>());
            if (index != list.size() + 1) {
                throw new IOException(
                        "non-contiguous structure-call-times trace for origin "
                                + originX + "," + originZ
                                + ": expected index " + (list.size() + 1)
                                + " got " + index);
            }
            list.add(new long[] {Long.parseLong(fields[3]), Long.parseLong(fields[4])});
        }
        return rows;
    }

    private static String describeStructureReferenceOrder(
            ChunkPos center,
            net.minecraft.world.level.levelgen.structure.Structure structure,
            Map<Long, ChunkAccess> grid) {
        ChunkAccess target = grid.get(center.pack());
        if (target == null) return "<missing-target>";
        List<String> order = new ArrayList<>();
        for (long packed : target.getReferencesForStructure(structure)) {
            int sourceX = ChunkPos.getX(packed);
            int sourceZ = ChunkPos.getZ(packed);
            ChunkAccess sourceChunk = grid.get(packed);
            if (sourceChunk == null) {
                order.add(sourceX + "," + sourceZ + "=<missing-source>");
                continue;
            }
            var sourceStart = sourceChunk.getStartForStructure(structure);
            order.add(sourceX + "," + sourceZ + "="
                    + (sourceStart == null ? "<no-start>"
                            : sourceStart.getBoundingBox().toString()));
        }
        return order.toString();
    }

    /**
     * The block state exactly as the capture records it. The harness snapshots
     * {@code LevelChunkSection.getBlockState} for every section, while
     * {@code ProtoChunk.getBlockState} answers AIR for any section whose
     * {@code hasOnlyAir()} is true, and cave_air and void_air count as air
     * there. Reading through the chunk therefore hides a cave_air written into
     * an otherwise empty section (smoke1: a mineshaft corridor's top layer at
     * y=-16, the bottom of an all-air section -1). Every oracle comparison
     * must read the raw section so both sides use one semantics.
     */
    static net.minecraft.world.level.block.state.BlockState capturedState(
            ChunkAccess chunk, BlockPos p) {
        int y = p.getY();
        if (chunk.isOutsideBuildHeight(y)) {
            return chunk.getBlockState(p);
        }
        return chunk.getSection(chunk.getSectionIndex(y))
                .getBlockState(p.getX() & 15, y & 15, p.getZ() & 15);
    }

    private static void assertStructureCallSnapshot(
            Path root,
            StructureCallRef ref,
            boolean after,
            Map<Long, ChunkAccess> grid) throws Exception {
        String hash = after ? ref.afterHash() : ref.beforeHash();
        TraceRef traceRef = new TraceRef(
                ref.targetX(), ref.targetZ(), "structure-call", hash);
        Snapshot expected = read(root, traceRef);
        ChunkAccess actual = grid.get(
                ChunkPos.pack(ref.targetX(), ref.targetZ()));
        if (actual == null) {
            throw new AssertionError(
                    "structure-call target chunk unavailable "
                            + ref.targetX() + "," + ref.targetZ());
        }

        for (int i = 0; i < expected.ids.length; i++) {
            BlockPos p = new BlockPos(
                    expected.x * 16 + (i & 15),
                    expected.minY + (i >> 8),
                    expected.z * 16 + ((i >> 4) & 15));
            int actualId = Block.getId(capturedState(actual, p));
            if (actualId != expected.ids[i]) {
                throw new AssertionError(
                        "structure-call " + (after ? "after" : "before")
                                + " block mismatch index=" + ref.index()
                                + " target=" + ref.targetX() + "," + ref.targetZ()
                                + " pos=" + p
                                + " vanilla=" + Block.stateById(expected.ids[i])
                                + " kestrel=" + capturedState(actual, p));
            }
        }
    }

    private static void assertStructureCallContent(
            StructureCallRef ref,
            boolean after,
            Map<Long, ChunkAccess> grid) {
        ChunkAccess raw = grid.get(
                ChunkPos.pack(ref.targetX(), ref.targetZ()));
        if (!(raw instanceof ProtoChunk proto)) {
            throw new AssertionError(
                    "structure-call expected ProtoChunk "
                            + ref.targetX() + "," + ref.targetZ());
        }

        String actualEntities =
                normalizeEntityNbt(proto.getEntities().toString());
        Set<String> actualPending =
                blockPositions(proto.getBlockEntityNbts().keySet().toString());
        Set<String> actualReal =
                blockPositions(proto.getBlockEntities().keySet().toString());

        String expectedEntities =
                after ? ref.afterEntities() : ref.beforeEntities();
        Set<String> expectedPending =
                after ? ref.afterPending() : ref.beforePending();
        Set<String> expectedReal =
                after ? ref.afterReal() : ref.beforeReal();

        if (!actualEntities.equals(expectedEntities)
                || !actualPending.equals(expectedPending)
                || !actualReal.equals(expectedReal)) {
            List<String> expectedRealStates = new ArrayList<>();
            for (String encoded : expectedReal) {
                String[] xyz = encoded.split(",", -1);
                if (xyz.length == 3) {
                    BlockPos p = new BlockPos(
                            Integer.parseInt(xyz[0]),
                            Integer.parseInt(xyz[1]),
                            Integer.parseInt(xyz[2]));
                    expectedRealStates.add(p + "=" + raw.getBlockState(p));
                }
            }
            throw new AssertionError(
                    "structure-call " + (after ? "after" : "before")
                            + " content mismatch index=" + ref.index()
                            + " target=" + ref.targetX() + "," + ref.targetZ()
                            + "\n  vanillaEntities=" + expectedEntities
                            + "\n  kestrelEntities=" + actualEntities
                            + "\n  vanillaPending=" + expectedPending
                            + " kestrelPending=" + actualPending
                            + "\n  vanillaReal=" + expectedReal
                            + " kestrelReal=" + actualReal
                            + "\n  expectedRealBlockStates=" + expectedRealStates);
        }
    }

    private record HiddenRef(int x, int z, String biomeHash, String oceanFloorWgHash) {}

    private static Map<Long, HiddenRef[]> readHidden(Path root) throws IOException {
        Path file = root.resolve("hidden.tsv");
        if (!Files.exists(file)) return Map.of();
        List<String> lines = Files.readAllLines(file);
        Map<Long, HiddenRef[]> rows = new HashMap<>();
        for (int li = 1; li < lines.size(); li++) {
            String[] fields = lines.get(li).split("\\t", -1);
            if (fields.length != 5) throw new IOException("invalid hidden trace row: " + lines.get(li));
            long seq = Long.parseLong(fields[0]);
            String[] raw = fields[4].split(";");
            HiddenRef[] refs = new HiddenRef[raw.length];
            for (int i = 0; i < raw.length; i++) {
                String[] p = raw[i].split(",", 4);
                if (p.length != 4) throw new IOException("invalid hidden ref: " + raw[i]);
                refs[i] = new HiddenRef(Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[2], p[3]);
            }
            rows.put(seq, refs);
        }
        return rows;
    }

    private record HeightQuery(int index, String type, int x, int z, int result) {}

    /**
     * Corpus-scale height-query assertion reader.
     *
     * <p>The harness writes height queries while FEATURES is serialized, so all
     * rows for one origin are contiguous and origin groups follow FEATURES
     * entry order. Keep only one origin in memory instead of materializing a
     * multi-million-row trace as Java objects.
     */
    private static final class HeightQueryTrace implements AutoCloseable {
        private static final String HEADER =
                "originX\toriginZ\tindex\ttype\tx\tz\tresult";

        private final BufferedReader reader;
        private final Map<Long, Integer> originOrder;
        private String pending;
        private int lastRequestedOrder = -1;
        private long queriesRead;
        private int originsRead;

        HeightQueryTrace(Path file, Map<Long, Integer> originOrder) throws IOException {
            this.reader = Files.newBufferedReader(file);
            this.originOrder = originOrder;
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new IOException("invalid height-query trace header: " + header);
            }
            this.pending = nextNonBlank();
        }

        List<HeightQuery> readOrigin(ChunkPos origin) throws IOException {
            long key = origin.pack();
            Integer requestedOrder = originOrder.get(key);
            if (requestedOrder == null) {
                throw new IOException("height-query replay requested unknown origin " + origin);
            }
            if (requestedOrder <= lastRequestedOrder) {
                throw new IOException(
                        "height-query origins requested out of order: " + origin
                                + " order=" + requestedOrder
                                + " previous=" + lastRequestedOrder);
            }
            lastRequestedOrder = requestedOrder;

            if (pending == null) return List.of();

            String[] first = fields(pending);
            long pendingKey = ChunkPos.pack(
                    Integer.parseInt(first[0]), Integer.parseInt(first[1]));
            Integer pendingOrder = originOrder.get(pendingKey);
            if (pendingOrder == null) {
                throw new IOException(
                        "height-query trace contains origin absent from FEATURES events: "
                                + first[0] + "," + first[1]);
            }
            if (pendingOrder < requestedOrder) {
                throw new IOException(
                        "unconsumed height-query origin before " + origin
                                + ": " + first[0] + "," + first[1]);
            }
            if (pendingOrder > requestedOrder) {
                // This FEATURES origin legitimately issued no height queries.
                return List.of();
            }

            List<HeightQuery> out = new ArrayList<>();
            int expectedIndex = 1;
            while (pending != null) {
                String[] row = fields(pending);
                long rowKey = ChunkPos.pack(
                        Integer.parseInt(row[0]), Integer.parseInt(row[1]));
                if (rowKey != key) break;
                int index = Integer.parseInt(row[2]);
                if (index != expectedIndex) {
                    throw new IOException(
                            "non-contiguous height-query trace for origin "
                                    + origin.x() + "," + origin.z()
                                    + ": expected index " + expectedIndex
                                    + " got " + index);
                }
                out.add(new HeightQuery(
                        index,
                        row[3],
                        Integer.parseInt(row[4]),
                        Integer.parseInt(row[5]),
                        Integer.parseInt(row[6])));
                expectedIndex++;
                queriesRead++;
                pending = nextNonBlank();
            }
            originsRead++;
            return out;
        }

        void finish() throws IOException {
            if (pending != null) {
                String[] row = fields(pending);
                throw new IOException(
                        "unconsumed height-query trace at origin "
                                + row[0] + "," + row[1] + " index=" + row[2]);
            }
        }

        long queriesRead() {
            return queriesRead;
        }

        int originsRead() {
            return originsRead;
        }

        private String nextNonBlank() throws IOException {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) return line;
            }
            return null;
        }

        private static String[] fields(String line) throws IOException {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 7) {
                throw new IOException("invalid height-query trace row: " + line);
            }
            return fields;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private static String digestStrings(Collection<String> values) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String value : values) {
            digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String biomeSetHash(ChunkAccess chunk) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (net.minecraft.world.level.chunk.LevelChunkSection section : chunk.getSections()) {
            var biomes = section.getBiomes();
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        var holder = biomes.get(x, y, z);
                        digest.update(holder.unwrapKey().toString()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String allHeightmapSummary(ChunkAccess chunk) throws Exception {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<net.minecraft.world.level.levelgen.Heightmap.Types,
                net.minecraft.world.level.levelgen.Heightmap> entry : chunk.getHeightmaps()) {
            long[] raw = entry.getValue().getRawData();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length * Long.BYTES);
            DataOutputStream out = new DataOutputStream(bytes);
            for (long value : raw) out.writeLong(value);
            out.flush();
            String hash = HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
            parts.add(entry.getKey() + "=" + hash.substring(0, 12));
        }
        Collections.sort(parts);
        return String.join(",", parts);
    }

    private static String traceWindowHeightmaps(ChunkPos center,
            Map<Long, ChunkAccess> grid) throws Exception {
        List<String> chunks = new ArrayList<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = center.x() + dx;
                int z = center.z() + dz;
                ChunkAccess chunk = grid.get(ChunkPos.pack(x, z));
                chunks.add(x + ":" + z + "[" + allHeightmapSummary(chunk) + "]");
            }
        }
        return String.join(";", chunks);
    }

    private static String traceWindowStatuses(ChunkPos center,
            Map<Long, ChunkAccess> grid) {
        List<String> chunks = new ArrayList<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = center.x() + dx;
                int z = center.z() + dz;
                ChunkAccess chunk = grid.get(ChunkPos.pack(x, z));
                chunks.add(x + ":" + z + "=" + chunk.getPersistedStatus());
            }
        }
        return String.join(";", chunks);
    }

    private static String oceanFloorWgHash(ChunkAccess chunk) throws Exception {
        for (Map.Entry<net.minecraft.world.level.levelgen.Heightmap.Types,
                net.minecraft.world.level.levelgen.Heightmap> entry : chunk.getHeightmaps()) {
            if (entry.getKey() != net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG) continue;
            long[] raw = entry.getValue().getRawData();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length * Long.BYTES);
            DataOutputStream out = new DataOutputStream(bytes);
            for (long value : raw) out.writeLong(value);
            out.flush();
            return HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        }
        return "MISSING";
    }

    private static long hiddenComparisons, hiddenBiomeDiffs, hiddenHeightDiffs;
    private static boolean firstHiddenDiff;
    private static java.lang.reflect.Field heightmapsField;

    @SuppressWarnings("unchecked")
    private static Map<net.minecraft.world.level.levelgen.Heightmap.Types,
            net.minecraft.world.level.levelgen.Heightmap> mutableHeightmaps(
                    ChunkAccess chunk) throws ReflectiveOperationException {
        if (heightmapsField == null) {
            heightmapsField = ChunkAccess.class.getDeclaredField("heightmaps");
            heightmapsField.setAccessible(true);
        }
        return (Map<net.minecraft.world.level.levelgen.Heightmap.Types,
                net.minecraft.world.level.levelgen.Heightmap>) heightmapsField.get(chunk);
    }

    /**
     * Restore the captured lazy-presence state of OCEAN_FLOOR_WG.
     *
     * <p>The replay materializes every coverage chunk through NOISE/CARVERS up
     * front, which eagerly leaves WG heightmaps on chunks that Vanilla may not
     * have touched yet at a particular FEATURES cut. That is observably wrong:
     * ChunkAccess.getHeight lazily primes a missing heightmap from the blocks
     * that exist at the time of the first read. OreFeature reads
     * OCEAN_FLOOR_WG before doPlace, so preserving an eagerly-created stale map
     * changes its control flow and therefore its random-stream consumption.
     *
     * <p>The trace records presence explicitly as MISSING. Remove only entries
     * that Vanilla did not have at this cut; when the replay later performs the
     * corresponding getHeight call, Minecraft's own lazy priming recreates the
     * map from the correct current block state.
     */
    private static String describeHeightQueryState(
            Map<Long, ChunkAccess> grid,
            net.minecraft.world.level.levelgen.Heightmap.Types type,
            int x,
            int z) {
        ChunkAccess chunk = grid.get(ChunkPos.pack(x >> 4, z >> 4));
        if (chunk == null) {
            return " chunk=MISSING";
        }
        try {
            Map<net.minecraft.world.level.levelgen.Heightmap.Types,
                    net.minecraft.world.level.levelgen.Heightmap> maps =
                    mutableHeightmaps(chunk);
            var finalType = switch (type) {
                case OCEAN_FLOOR_WG ->
                        net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR;
                case WORLD_SURFACE_WG ->
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE;
                default -> type;
            };
            int lx = x & 15;
            int lz = z & 15;
            boolean wgPresent = maps.containsKey(type);
            boolean finalPresent = maps.containsKey(finalType);
            int direct = chunk.getHeight(type, lx, lz) + 1;
            int mapped = chunk.getHeight(finalType, lx, lz) + 1;
            return " chunkClass=" + chunk.getClass().getSimpleName()
                    + " status=" + chunk.getPersistedStatus()
                    + " wgPresentBeforeDiag=" + wgPresent
                    + " finalPresentBeforeDiag=" + finalPresent
                    + " direct=" + direct
                    + " imposterMapped=" + mapped;
        } catch (Throwable t) {
            return " heightStateError=" + t;
        }
    }

    private static void syncHiddenHeightmapPresence(Event event,
            Map<Long, HiddenRef[]> expected,
            Map<Long, ChunkAccess> grid) throws Exception {
        HiddenRef[] refs = expected.get(event.sequence);
        if (refs == null) return;
        for (HiddenRef ref : refs) {
            if (!"MISSING".equals(ref.oceanFloorWgHash)) continue;
            ChunkAccess chunk = grid.get(ChunkPos.pack(ref.x, ref.z));
            if (chunk == null) {
                throw new IOException("missing replay chunk for hidden ref "
                        + ref.x + "," + ref.z);
            }
            mutableHeightmaps(chunk).remove(
                    net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG);
        }
    }

    private static void compareHidden(Event event, Map<Long, HiddenRef[]> expected,
            Map<Long, ChunkAccess> grid) throws Exception {
        HiddenRef[] refs = expected.get(event.sequence);
        if (refs == null) return;
        for (HiddenRef ref : refs) {
            ChunkAccess chunk = grid.get(ChunkPos.pack(ref.x, ref.z));
            String biome = biomeSetHash(chunk);
            String height = oceanFloorWgHash(chunk);
            hiddenComparisons++;
            boolean biomeDiff = !biome.equals(ref.biomeHash);
            boolean heightDiff = !height.equals(ref.oceanFloorWgHash);
            if (biomeDiff) hiddenBiomeDiffs++;
            if (heightDiff) hiddenHeightDiffs++;
            if ((biomeDiff || heightDiff) && !firstHiddenDiff) {
                System.out.println("FIRST_HIDDEN_DIFF seq=" + event.sequence
                        + " phase=" + event.phase + " origin=" + event.x + "," + event.z
                        + " chunk=" + ref.x + "," + ref.z
                        + " biomeDiff=" + biomeDiff
                        + " heightDiff=" + heightDiff
                        + " vanillaBiome=" + ref.biomeHash + " kestrelBiome=" + biome
                        + " vanillaOceanFloorWG=" + ref.oceanFloorWgHash
                        + " kestrelOceanFloorWG=" + height);
                firstHiddenDiff = true;
            }
            if (event.x == 0 && event.z == 2 && event.phase.equals("ENTER")
                    && (biomeDiff || heightDiff)) {
                System.out.println("ORIGIN_0_2_HIDDEN_DIFF chunk=" + ref.x + "," + ref.z
                        + " biomeDiff=" + biomeDiff + " heightDiff=" + heightDiff
                        + " vanillaBiome=" + ref.biomeHash + " kestrelBiome=" + biome
                        + " vanillaOceanFloorWG=" + ref.oceanFloorWgHash
                        + " kestrelOceanFloorWG=" + height);
            }
        }
    }

    private record CoalGap(long missing, long extra) {}

    private static void compareOreInputs(Path root, Map<Long, ChunkAccess> grid) throws Exception {
        Path file = root.resolve("ore-can-place.tsv");
        if (!Files.isRegularFile(file)) {
            System.out.println("ORE_INPUT_COMPARE missing=" + file);
            return;
        }
        long rows = 0;
        long mismatches = 0;
        int dumped = 0;
        for (String row : Files.readAllLines(file)) {
            if (row.startsWith("thread\t") || row.isBlank()) continue;
            String[] fields = row.split("\\t", -1);
            if (fields.length < 11) {
                throw new IOException("malformed ore-can-place row: " + row);
            }
            int x = Integer.parseInt(fields[6]);
            int y = Integer.parseInt(fields[7]);
            int z = Integer.parseInt(fields[8]);
            String vanillaState = fields[9];
            BlockPos pos = new BlockPos(x, y, z);
            ChunkAccess chunk = grid.get(ChunkPos.pack(x >> 4, z >> 4));
            if (chunk == null) {
                throw new IOException("missing replay chunk for ore target " + pos);
            }
            String kestrelState = String.valueOf(chunk.getBlockState(pos));
            rows++;
            if (!vanillaState.equals(kestrelState)) {
                mismatches++;
                if (dumped++ < 20) {
                    System.out.println("ORE_INPUT_DIFF pos=" + pos
                            + " vanilla=" + vanillaState
                            + " kestrel=" + kestrelState
                            + " vanillaCanPlace=" + fields[10]);
                }
            }
        }
        System.out.println("ORE_INPUT_COMPARE rows=" + rows + " mismatches=" + mismatches);
    }

    private static CoalGap coalGap(Path root, Event expectedComplete,
            Map<Long, ChunkAccess> grid) throws Exception {
        long missing = 0, extra = 0;
        for (TraceRef ref : expectedComplete.refs) {
            Snapshot snapshot = read(root, ref);
            ChunkAccess chunk = grid.get(ChunkPos.pack(snapshot.x, snapshot.z));
            for (int i = 0; i < snapshot.ids.length; i++) {
                BlockPos p = new BlockPos(
                        snapshot.x * 16 + (i & 15),
                        snapshot.minY + (i >> 8),
                        snapshot.z * 16 + ((i >> 4) & 15));
                var expected = Block.stateById(snapshot.ids[i]);
                var actual = capturedState(chunk, p);
                boolean expectedCoal = expected.is(net.minecraft.world.level.block.Blocks.COAL_ORE)
                        || expected.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_COAL_ORE);
                boolean actualCoal = actual.is(net.minecraft.world.level.block.Blocks.COAL_ORE)
                        || actual.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_COAL_ORE);
                if (expectedCoal && !actualCoal) missing++;
                if (!expectedCoal && actualCoal) extra++;
            }
        }
        return new CoalGap(missing, extra);
    }

    private static void assertLifecycleStatuses(
            Event event, Map<Long, ChunkAccess> grid) throws IOException {
        for (TraceRef ref : event.refs) {
            ChunkStatus expected = ChunkStatus.byName(ref.status);
            if (expected == null) {
                throw new IOException("unknown captured chunk status " + ref.status);
            }
            ChunkAccess actualChunk = grid.get(ChunkPos.pack(ref.x, ref.z));
            if (actualChunk == null) {
                throw new AssertionError(
                        "LIFECYCLE_STATUS_MISSING seq=" + event.sequence
                                + " phase=" + event.phase
                                + " origin=" + event.x + "," + event.z
                                + " chunk=" + ref.x + "," + ref.z);
            }
            ChunkStatus actual = actualChunk.getPersistedStatus();
            if (actual != expected) {
                throw new AssertionError(
                        "LIFECYCLE_STATUS_MISMATCH seq=" + event.sequence
                                + " phase=" + event.phase
                                + " origin=" + event.x + "," + event.z
                                + " chunk=" + ref.x + "," + ref.z
                                + " expected=" + expected
                                + " actual=" + actual
                                + " chunkClass=" + actualChunk.getClass().getName());
            }
        }
    }

    private static void syncLighting(WorldgenLightLifecycle lighting, TraceRef[] refs,
            Map<Long, ChunkAccess> grid) throws IOException {
        List<ProtoChunk> toInitialize = new ArrayList<>();
        List<ProtoChunk> toLight = new ArrayList<>();

        for (TraceRef ref : refs) {
            ChunkStatus observed = ChunkStatus.byName(ref.status);
            if (observed == null) {
                throw new IOException("unknown captured chunk status " + ref.status);
            }
            ChunkAccess raw = grid.get(ChunkPos.pack(ref.x, ref.z));
            if (!(raw instanceof ProtoChunk proto)) {
                throw new IOException("expected ProtoChunk for lighting ref " + ref);
            }

            if (observed.isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) {
                toInitialize.add(proto);
            }
            if (observed.isOrAfter(ChunkStatus.LIGHT)) {
                toLight.add(proto);
            }
        }

        // Preserve the task boundary from the proven replay: all PRE_UPDATE
        // registration for this observation is visible to one light-engine
        // drain before any POST_UPDATE status publication.
        lighting.initializeAll(toInitialize);
        lighting.lightAll(toLight);

        // Do not copy the observed status into Kestrel. The trace supplies
        // only the cross-executor scheduling cutpoint; initializeAll/lightAll
        // above must produce the resulting status and light-storage state by
        // executing Kestrel's real Vanilla-backed operations.
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 13)
            throw new IllegalArgumentException(
                    "FeaturesTraceReplay <trace-directory> [--dimension=overworld|nether|end] [--report-only] [--structures] [--spawn-gate] [--trace-features=x,z] [--skip-structure-origin=x,z] [--replay-height-queries] [--replay-structure-order] [--replay-hidden-heightmaps] [--replay-chunk-loads] [--replay-lighting-lifecycle]");
        Path root = Path.of(args[0]);
        // Fails by default. A gate that prints FAIL and exits 0 is the same
        // fail-open pattern that produced C1, the fabricated parity oracle and
        // the air oracle in FeaturesCheck. Opt out explicitly to diagnose.
        boolean reportOnly = false;
        boolean structuresOn = false;
        boolean replayHeightQueries = false;
        boolean replayStructureOrder = false;
        boolean replayHiddenHeightmaps = false;
        boolean replayChunkLoads = false;
        boolean replayLightingLifecycle = false;
        boolean spawnGate = false;
        ChunkPos featureTraceOrigin = null;
        ChunkPos skipStructureOrigin = null;
        HeadlessNoise.Dimension dimension = HeadlessNoise.Dimension.OVERWORLD;
        Path vanillaRegion = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--report-only")) {
                reportOnly = true;
            } else if (args[i].equals("--structures")) {
                structuresOn = true;
            } else if (args[i].equals("--replay-height-queries")) {
                replayHeightQueries = true;
            } else if (args[i].equals("--replay-structure-order")) {
                replayStructureOrder = true;
            } else if (args[i].equals("--replay-hidden-heightmaps")) {
                replayHiddenHeightmaps = true;
            } else if (args[i].equals("--replay-chunk-loads")) {
                replayChunkLoads = true;
            } else if (args[i].equals("--replay-lighting-lifecycle")) {
                replayLightingLifecycle = true;
            } else if (args[i].equals("--spawn-gate")) {
                spawnGate = true;
            } else if (args[i].startsWith("--trace-features=")) {
                String[] parts = args[i].substring("--trace-features=".length()).split(",", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("invalid --trace-features origin: " + args[i]);
                }
                featureTraceOrigin = new ChunkPos(
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } else if (args[i].startsWith("--skip-structure-origin=")) {
                String[] parts = args[i].substring("--skip-structure-origin=".length()).split(",", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("invalid --skip-structure-origin: " + args[i]);
                }
                skipStructureOrigin = new ChunkPos(
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } else if (args[i].startsWith("--dimension=")) {
                dimension = HeadlessNoise.Dimension.valueOf(
                        args[i].substring("--dimension=".length()).toUpperCase(java.util.Locale.ROOT));
            } else if (args[i].startsWith("--vanilla-region=")) {
                vanillaRegion = Path.of(args[i].substring("--vanilla-region=".length()));
            } else {
                throw new IllegalArgumentException("unknown flag: " + args[i]);
            }
        }
        if (replayHeightQueries && !reportOnly) {
            throw new IllegalArgumentException(
                    "--replay-height-queries is diagnostic-only and requires --report-only");
        }
        if (replayStructureOrder && (!reportOnly || !structuresOn)) {
            throw new IllegalArgumentException(
                    "--replay-structure-order is diagnostic-only and requires --report-only --structures");
        }
        if (replayHiddenHeightmaps && !reportOnly) {
            throw new IllegalArgumentException(
                    "--replay-hidden-heightmaps is diagnostic-only and requires --report-only");
        }
        if (replayChunkLoads && (!reportOnly || !structuresOn)) {
            throw new IllegalArgumentException(
                    "--replay-chunk-loads is only needed for report-only diagnostics; "
                            + "strict --structures replay applies timestamped chunk loads automatically");
        }
        if (replayLightingLifecycle && !reportOnly) {
            throw new IllegalArgumentException(
                    "--replay-lighting-lifecycle is diagnostic-only and requires --report-only");
        }
        if (spawnGate && (reportOnly || !structuresOn)) {
            throw new IllegalArgumentException(
                    "--spawn-gate is a strict structures-on gate and requires --structures without --report-only");
        }
        if (spawnGate && dimension != HeadlessNoise.Dimension.OVERWORLD) {
            throw new IllegalArgumentException("--spawn-gate is Overworld-only");
        }
        String expectedDimension = "ResourceKey[minecraft:dimension / "
                + dimension.levelId() + "]";
        // capture-features-order.py writes the disposable server properties
        // beside trace/. When they are still present, reject an accidental
        // structures-on/off replay mismatch instead of producing a plausible
        // but meaningless parity number. Older/copied trace directories remain
        // supported when server.properties is absent.
        Path capturedProperties = root.getParent() == null
                ? null : root.getParent().resolve("server.properties");
        if (capturedProperties != null && Files.exists(capturedProperties)) {
            boolean capturedStructures = Files.readAllLines(capturedProperties).stream()
                    .anyMatch(line -> line.trim().equals("generate-structures=true"));
            if (capturedStructures != structuresOn) {
                throw new IOException("trace capture/replay structure mode mismatch: captured="
                        + (capturedStructures ? "on" : "off") + " replay="
                        + (structuresOn ? "on" : "off"));
            }
        }
        List<Event> events = new ArrayList<>();
        Set<Long> origins = new HashSet<>(), coverage = new HashSet<>();
        Map<Long, TraceRef> lastRef = new HashMap<>();
        Event open = null;
        long sequence = 0;
        for (String line : Files.readAllLines(root.resolve("events.tsv")).subList(1,
                Files.readAllLines(root.resolve("events.tsv")).size())) {
            String[] fields = line.split("\t", -1);
            long rowSeed = Long.parseLong(fields[5]);
            if (SEED == -1L) {
                SEED = rowSeed;
            }
            if (fields.length != 11 || Long.parseLong(fields[0]) != ++sequence || rowSeed != SEED
                    || !fields[6].equals(expectedDimension)
                    || !fields[8].equals("1")) throw new IOException("unsupported trace metadata: " + line);
            String[] rawRefs = fields[10].split(";");
            TraceRef[] refs = new TraceRef[rawRefs.length];
            for (int i = 0; i < rawRefs.length; i++) refs[i] = parseRef(rawRefs[i]);
            Event e = new Event(sequence, fields[1], fields[2], Integer.parseInt(fields[3]), Integer.parseInt(fields[4]), refs);
            if (e.refs.length != 9) throw new IOException("incomplete dependency window");
            Set<Long> window = new HashSet<>();
            for (TraceRef ref : e.refs) {
                Snapshot s = read(root, ref);
                long key = ChunkPos.pack(s.x, s.z);
                if (Math.abs(s.x-e.x)>1 || Math.abs(s.z-e.z)>1 || !window.add(key)) throw new IOException("invalid dependency window");
                coverage.add(key);
                lastRef.put(key, ref); // last-write-wins: trace order means this ends up vanilla's final state
            }
            if (e.phase.equals("ENTER")) {
                if (open != null || !fields[7].equals("1") || !origins.add(ChunkPos.pack(e.x, e.z)))
                    throw new IOException("overlap or repeated center; sequential replay invalid");
                open = e;
            } else if (e.phase.equals("COMPLETE")) {
                if (open == null || open.x != e.x || open.z != e.z || !open.thread.equals(e.thread) || !fields[7].equals("0"))
                    throw new IOException("unmatched completion");
                open = null;
            } else throw new IOException("incomplete future or unsupported phase");
            events.add(e);
        }
        if (open != null || events.isEmpty()) throw new IOException("incomplete trace");
        // The three chunks below were the subject of the original kelp/seagrass
        // investigation. Keep them as a regression guard when a trace happens to
        // cover them, but do not demand them of every trace.
        boolean guard = true;
        for (int[] t : new int[][]{{-6,-6},{-6,6},{-5,6}})
            for (int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
                if (!origins.contains(ChunkPos.pack(t[0]+dx,t[1]+dz))) guard = false;
        System.out.println("VALIDATED seed=" + SEED + " centers=" + origins.size()
                + " coverage=" + coverage.size() + " familyGuard=" + guard
                + " structures=" + (structuresOn ? "on" : "off"));

        List<SpawnEventRef> spawnEvents = List.of();
        Map<Long, List<SpawnDeltaRef>> spawnDeltaRows = Map.of();
        if (spawnGate) {
            for (String required : List.of(
                    "spawn-data.tsv",
                    "spawn-chunk-deltas.tsv",
                    "worldgen-light-events.tsv")) {
                if (!Files.isRegularFile(root.resolve(required))) {
                    throw new IOException(
                            "--spawn-gate requires " + required);
                }
            }
            List<WorldgenLightEventRef> worldgenLightEvents =
                    readWorldgenLightEvents(root);
            spawnEvents = readSpawnEvents(root);
            spawnDeltaRows = readSpawnDeltas(root);
            validateSpawnLightCuts(spawnEvents, worldgenLightEvents);
        }

        Map<Long, HiddenRef[]> hiddenRows = readHidden(root);
        if (!hiddenRows.isEmpty()) {
            System.out.println("HIDDEN_TRACE rows=" + hiddenRows.size());
        }
        Map<Long, List<StructureDataRef>> structureDataRows = readStructureData(root);
        if (!structureDataRows.isEmpty()) {
            long rowCount = structureDataRows.values().stream().mapToLong(List::size).sum();
            System.out.println("STRUCTURE_DATA_TRACE origins=" + structureDataRows.size()
                    + " rows=" + rowCount);
        }

        Map<Long, List<StructureCallRef>> structureCallRows =
                readStructureCalls(root);
        if (!structureCallRows.isEmpty()) {
            long callCount =
                    structureCallRows.values().stream().mapToLong(List::size).sum();
            System.out.println(
                    "STRUCTURE_CALL_TRACE origins=" + structureCallRows.size()
                            + " calls=" + callCount);
        }

        Map<Long, List<long[]>> structureCallTimeRows = readStructureCallTimes(root);
        if (!structureCallTimeRows.isEmpty()) {
            long timeRows = 0;
            long straddles = 0;
            for (List<long[]> list : structureCallTimeRows.values()) {
                for (long[] t : list) {
                    timeRows++;
                    if (t[0] != t[1]) straddles++;
                }
            }
            // A straddle means the server clock ticked while the placement ran, so the
            // entity may have read either value. The enter value is used; an entity
            // content mismatch at a straddled call is then a known limit, not a guess.
            System.out.println("STRUCTURE_CALL_TIME_TRACE rows=" + timeRows
                    + " straddles=" + straddles);
        }

        Map<Long, List<StructureSourceRef>> structureSourceRows =
                readStructureSources(root);
        if (!structureSourceRows.isEmpty()) {
            long sourceCount =
                    structureSourceRows.values().stream().mapToLong(List::size).sum();
            System.out.println(
                    "STRUCTURE_SOURCE_TRACE origins=" + structureSourceRows.size()
                            + " calls=" + sourceCount);
        }

        Map<Long, List<StructureReferenceStateRef>> structureReferenceStateRows =
                readStructureReferenceState(root);
        Path chunkLoadsFile = root.resolve("chunk-loads.tsv");
        Map<Long, List<ChunkLoadRef>> chunkLoadRows = readChunkLoads(root);
        boolean hasReplayableChunkLoadTrace = false;
        if (Files.isRegularFile(chunkLoadsFile)) {
            List<String> chunkLoadLines = Files.readAllLines(chunkLoadsFile);
            hasReplayableChunkLoadTrace =
                    !chunkLoadLines.isEmpty()
                            && chunkLoadLines.get(0).equals(
                                    "afterEventSeq\tactiveOriginX\tactiveOriginZ"
                                            + "\tx\tz\tstatus\tchunkClass\treferenceSets");
        }
        if (hasReplayableChunkLoadTrace) {
            long loadCount =
                    chunkLoadRows.values().stream().mapToLong(List::size).sum();
            System.out.println(
                    "CHUNK_LOAD_TRACE cutpoints=" + chunkLoadRows.size()
                            + " loads=" + loadCount);
        }
        Path lightingEventsFile = root.resolve("lighting-events.tsv");
        Map<Long, List<LightingEventRef>> lightingEventRows =
                readLightingEvents(root);
        boolean hasReplayableLightingTrace = false;
        if (Files.isRegularFile(lightingEventsFile)) {
            List<String> lightingLines = Files.readAllLines(lightingEventsFile);
            hasReplayableLightingTrace =
                    !lightingLines.isEmpty()
                            && lightingLines.get(0).equals(
                                    "afterEventSeq\torder\tactiveOriginX\tactiveOriginZ"
                                            + "\tx\tz\toutcome");
        }
        if (hasReplayableLightingTrace) {
            long lightingCount = lightingEventRows.values().stream()
                    .mapToLong(List::size).sum();
            System.out.println(
                    "LIGHTING_EVENT_TRACE cutpoints=" + lightingEventRows.size()
                            + " completions=" + lightingCount);
        }
        if (!structureReferenceStateRows.isEmpty()) {
            long stateCount = structureReferenceStateRows.values().stream()
                    .mapToLong(List::size).sum();
            System.out.println(
                    "STRUCTURE_REFERENCE_STATE_TRACE origins="
                            + structureReferenceStateRows.size()
                            + " rows=" + stateCount);
        }
        if (structuresOn && !reportOnly) {
            java.util.List<String> missingStructureTraces = new java.util.ArrayList<>();
            for (String required : java.util.List.of(
                    "structure-calls.tsv",
                    "structure-sources.tsv",
                    "structure-reference-state.tsv",
                    "chunk-loads.tsv",
                    "lighting-events.tsv")) {
                if (!Files.isRegularFile(root.resolve(required))) {
                    missingStructureTraces.add(required);
                }
            }
            if (!missingStructureTraces.isEmpty()) {
                throw new IOException(
                        "strict structures replay requires complete lifecycle traces; missing "
                                + String.join(", ", missingStructureTraces)
                                + ". Use --report-only for legacy captures.");
            }
            if (!hasReplayableChunkLoadTrace) {
                throw new IOException(
                        "strict structures replay requires timestamped chunk-loads.tsv; "
                                + "legacy reload traces lack an execution cutpoint. "
                                + "Use --report-only for legacy captures.");
            }
            if (!hasReplayableLightingTrace) {
                throw new IOException(
                        "strict structures replay requires timestamped lighting-events.tsv; "
                                + "captured lifecycle statuses are assertion-only. "
                                + "Use --report-only for legacy captures.");
            }
            long strictCalls = structureCallRows.values().stream()
                    .mapToLong(java.util.List::size).sum();
            long strictSources = structureSourceRows.values().stream()
                    .mapToLong(java.util.List::size).sum();
            long strictReferenceStates = structureReferenceStateRows.values().stream()
                    .mapToLong(java.util.List::size).sum();
            if (strictCalls != strictSources
                    || strictSources != strictReferenceStates) {
                throw new IOException(
                        "strict structures replay has incomplete lifecycle rows: calls="
                                + strictCalls
                                + " sources=" + strictSources
                                + " referenceStates=" + strictReferenceStates);
            }
        }

        Path heightQueryFile = root.resolve("height-queries.tsv");
        boolean hasHeightQueryTrace = Files.exists(heightQueryFile);
        if (!hasHeightQueryTrace && !reportOnly) {
            throw new IOException(
                    "strict FEATURES replay requires height-queries.tsv; "
                            + "legacy hidden-heightmap synchronization is diagnostic-only");
        }
        Map<Long, Integer> featureOriginOrder = new HashMap<>();
        int featureOrder = 0;
        for (Event event : events) {
            if (event.phase().equals("ENTER")) {
                featureOriginOrder.put(
                        ChunkPos.pack(event.x(), event.z()), featureOrder++);
            }
        }
        HeightQueryTrace heightQueryTrace =
                hasHeightQueryTrace
                        ? new HeightQueryTrace(heightQueryFile, featureOriginOrder)
                        : null;
        if (hasHeightQueryTrace) {
            System.out.println("HEIGHT_QUERY_TRACE streaming=true"
                    + " mode=" + (replayHeightQueries ? "oracle-replay" : "native-assert"));
        }

        HeadlessNoise.Structural structural =
                structuresOn ? HeadlessNoise.loadStructural(SEED, dimension) : null;
        HeadlessNoise.Driver driver =
                structural == null ? HeadlessNoise.load(SEED, dimension) : structural.driver();
        if (driver.height().getMinY() != CAPTURE_MIN_Y
                || driver.height().getHeight() != CAPTURE_HEIGHT) {
            throw new IOException("trace/driver height mismatch: trace=" + CAPTURE_MIN_Y
                    + ".." + (CAPTURE_MIN_Y + CAPTURE_HEIGHT)
                    + " driver=" + driver.height().getMinY() + ".."
                    + (driver.height().getMinY() + driver.height().getHeight()));
        }
        Map<Long, ChunkAccess> grid;
        Map<Long, ChunkAccess> lightingGrid;
        // Diagnostic-only whole-grid two-phase setup: SURFACE needs each
        // covered chunk's own 3x3 biome neighbourhood at BIOMES status.
        // `coverage` is a union of 3x3 dependency windows, so it is not
        // guaranteed to include the outer ring around its own boundary;
        // prepareBiomeGrid's margin map supplies that ring on demand.
        Map<ChunkPos, ChunkAccess> noiseMargin = new HashMap<>();

        if (structural == null) {
            Map<Long, ChunkAccess> plainGrid = new HashMap<>();
            for (long key : new TreeSet<>(coverage)) {
                int x = (int) key, z = (int) (key >> 32);
                Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(
                        driver, x, z,
                        (bx, bz) -> plainGrid.get(ChunkPos.pack(bx, bz)),
                        noiseMargin);
                ChunkAccess chunk = biomeGrid.get(new ChunkPos(x, z));
                HeadlessNoise.surfaceVanilla(driver, chunk, biomeGrid);
                HeadlessNoise.carveVanilla(driver, chunk);
                ((ProtoChunk) chunk).setPersistedStatus(ChunkStatus.CARVERS);
                HeadlessNoise.releaseNoiseChunk(chunk);
                plainGrid.put(key, chunk);
                noiseMargin.remove(new ChunkPos(x, z));
            }
            grid = plainGrid;
            lightingGrid = plainGrid;
        } else {
            // First materialize every traced chunk through real
            // STRUCTURE_STARTS -> STRUCTURE_REFERENCES -> BIOMES -> NOISE.
            // One shared starts cache is essential: references in a FEATURES
            // center can point to a start outside the trace's 3x3 block-write
            // window, and RegionView must still be able to resolve that exact
            // StructureStart when placeInChunk runs.
            Map<Long, ChunkAccess> structureGrid = new HashMap<>();
            for (long key : new TreeSet<>(coverage)) {
                int x = (int) key, z = (int) (key >> 32);
                ChunkAccess chunk = HeadlessNoise.noiseWithStructures(
                        structural, x, z, structureGrid);
                structureGrid.put(key, chunk);
            }

            // Only traced coverage chunks advance through SURFACE/CARVERS.
            // Bare radius-8 STRUCTURE_STARTS dependencies remain in
            // structureGrid solely for StructureManager reference resolution.
            // They must not leak into biome-margin or lighting lookups as empty
            // chunks, so those consumers are explicitly coverage-scoped.
            for (long key : new TreeSet<>(coverage)) {
                int x = (int) key, z = (int) (key >> 32);
                Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(
                        driver, x, z,
                        (bx, bz) -> {
                            long neighbourKey = ChunkPos.pack(bx, bz);
                            return coverage.contains(neighbourKey)
                                    ? structureGrid.get(neighbourKey) : null;
                        },
                        noiseMargin);
                ChunkAccess chunk = structureGrid.get(key);
                HeadlessNoise.surfaceVanilla(driver, chunk, biomeGrid);
                HeadlessNoise.carveVanilla(driver, chunk);
                ((ProtoChunk) chunk).setPersistedStatus(ChunkStatus.CARVERS);
                HeadlessNoise.releaseNoiseChunk(chunk);
                noiseMargin.remove(new ChunkPos(x, z));
            }
            grid = structureGrid;
            Map<Long, ChunkAccess> tracedOnly = new HashMap<>();
            for (long key : coverage) {
                tracedOnly.put(key, structureGrid.get(key));
            }
            lightingGrid = tracedOnly;
        }
        Map<Long, Event> completeByOrigin = new HashMap<>();
        for (Event candidate : events) {
            if (candidate.phase.equals("COMPLETE")) {
                completeByOrigin.put(ChunkPos.pack(candidate.x, candidate.z), candidate);
            }
        }

        WorldgenLightLifecycle lighting = new WorldgenLightLifecycle(lightingGrid, driver);
        List<WorldgenLightEventRef> terrainLightEvents = readWorldgenLightEvents(root);
        boolean hasExecutorBatches = terrainLightEvents.stream().anyMatch(event -> event.batch() > 0L);
        List<LightExecutorBatch> lightExecutorBatches = readLightExecutorBatches(root);
        List<LightScheduleOp> lightScheduleOps = readLightScheduleOps(root);
        int lightScheduleCursor = 0;
        boolean hasExecutorOps = !lightExecutorBatches.isEmpty();
        // Keep ProtoChunk's persisted status so feature writes still maintain
        // skylight-source heights. Only callbacks that Vanilla queues on its
        // light executor are replaced by the captured operation stream.
        lighting.replayExecutorCallbacks(hasExecutorOps);
        // Chunk activation (LevelChunk.postProcessGeneration when a chunk
        // becomes ticking) runs between light batches; see ReplayActivation.
        ReplayActivation activation = hasExecutorOps
                ? new ReplayActivation(driver, lightingGrid, lighting) : null;
        Map<Long, List<WorldgenLightEventRef>> lightCompletionsByBatch = new HashMap<>();
        for (WorldgenLightEventRef event : terrainLightEvents) {
            if (event.batch() > 0L) {
                lightCompletionsByBatch.computeIfAbsent(event.batch(), ignored -> new ArrayList<>())
                        .add(event);
            }
        }
        int lightExecutorBatchCursor = 0;
        List<LightData> capturedLightData = readLightData(root);
        List<LightData> capturedFinalLightData = readLightData(root, "final-light-data.tsv");
        int terrainLightCursor = 0;
        int capturedLightCursor = 0;
        int capturedLightCompared = 0;
        int capturedLightDiffs = 0;
        SpawnReplayState spawnReplay =
                spawnGate
                        ? new SpawnReplayState(
                                spawnEvents,
                                spawnDeltaRows,
                                readWorldgenLightEvents(root),
                                sequence)
                        : null;
        long chunkReloadReplays = 0L;
        long lightingEventReplays = 0L;
        long previousEventSeq = 0L;
        Event previousEvent = null;
        java.util.Set<Long> preAppliedIntraSeqs = new java.util.HashSet<>();
        for (Event e : events) {
            if (e.sequence() != previousEventSeq + 1L) {
                throw new IOException(
                        "non-contiguous FEATURES event sequence: previous="
                                + previousEventSeq + " current=" + e.sequence());
            }
            boolean applyChunkLoadReplay =
                    structuresOn
                            && hasReplayableChunkLoadTrace
                            && (!reportOnly || replayChunkLoads);
            if (applyChunkLoadReplay) {
                // This is part of the exact replay key, not a Vanilla answer:
                // reproduce the observed deserialize operation at the same
                // event cutpoint. Kestrel deterministically rebuilds the
                // reference sets from their own memberships; the captured
                // n/minN/iterator order remains assertion-only below.
                chunkReloadReplays += replayChunkLoads(
                        previousEventSeq, chunkLoadRows, grid, coverage,
                        structural, lighting);
            }
            boolean applyLightingEventReplay =
                    !reportOnly && hasReplayableLightingTrace && !hasExecutorOps;
            if (applyLightingEventReplay) {
                // Exact lifecycle input: replay the real completion operations
                // at their captured scheduler cutpoints. No captured status,
                // light value or block answer is copied into Kestrel.
                lightingEventReplays += replayLightingEvents(
                        root, previousEventSeq, lightingEventRows, grid, lighting,
                        previousEvent, e, preAppliedIntraSeqs);
            }
            while (lightScheduleCursor < lightScheduleOps.size()) {
                LightScheduleOp op = lightScheduleOps.get(lightScheduleCursor);
                long cut = op.activeX() == null ? op.afterEventSeq() : op.afterEventSeq() - 1L;
                if (cut != previousEventSeq) break;
                ChunkAccess raw = grid.get(ChunkPos.pack(op.x(), op.z()));
                if (raw instanceof ProtoChunk chunk) lighting.prepareInitialize(chunk);
                lightScheduleCursor++;
            }
            while (lightExecutorBatchCursor < lightExecutorBatches.size()
                    && lightExecutorBatches.get(lightExecutorBatchCursor).afterEventSeq()
                            == previousEventSeq) {
                replayLightExecutorBatch(
                        lightExecutorBatches.get(lightExecutorBatchCursor++), grid, lighting,
                        lightCompletionsByBatch, activation);
            }
            if (lightExecutorBatchCursor < lightExecutorBatches.size()
                    && lightExecutorBatches.get(lightExecutorBatchCursor).afterEventSeq()
                            < previousEventSeq) {
                throw new IOException("missed light executor batch at FEATURES cut "
                        + lightExecutorBatches.get(lightExecutorBatchCursor).afterEventSeq());
            }
            if (hasExecutorOps) {
                while (terrainLightCursor < terrainLightEvents.size()
                        && terrainLightEvents.get(terrainLightCursor).afterEventSeq() == previousEventSeq) {
                    publishLightStatus(terrainLightEvents.get(terrainLightCursor++), grid);
                }
            }
            if (!hasExecutorOps) {
            List<WorldgenLightEventRef> initializeBatch = new ArrayList<>();
            List<WorldgenLightEventRef> fullLightBatch = new ArrayList<>();
            long lightBatch = terrainLightCursor < terrainLightEvents.size()
                    && terrainLightEvents.get(terrainLightCursor).afterEventSeq() == previousEventSeq
                    ? (hasExecutorBatches
                            ? terrainLightEvents.get(terrainLightCursor).batch()
                            : terrainLightEvents.get(terrainLightCursor).afterEventSeq())
                    : Long.MIN_VALUE;
            while (terrainLightCursor < terrainLightEvents.size()
                    && (hasExecutorBatches
                            ? terrainLightEvents.get(terrainLightCursor).batch()
                            : terrainLightEvents.get(terrainLightCursor).afterEventSeq()) == lightBatch) {
                WorldgenLightEventRef lightEvent = terrainLightEvents.get(terrainLightCursor++);
                if (!lightEvent.outcome().equals("OK")) {
                    throw new IOException("captured worldgen LIGHT failed: " + lightEvent);
                }
                if (lightEvent.stage().equals("minecraft:initialize_light")) {
                    initializeBatch.add(lightEvent);
                } else if (lightEvent.stage().equals("minecraft:light")) {
                    fullLightBatch.add(lightEvent);
                }
            }
            if (!initializeBatch.isEmpty()) {
                List<ProtoChunk> batchChunks = new ArrayList<>();
                for (WorldgenLightEventRef lightEvent : initializeBatch) {
                    ChunkAccess raw = grid.get(ChunkPos.pack(lightEvent.x(), lightEvent.z()));
                    if (!(raw instanceof ProtoChunk proto)) {
                        throw new IOException("INITIALIZE_LIGHT target unavailable: " + lightEvent);
                    }
                    batchChunks.add(proto);
                }
                lighting.initializeAll(batchChunks);
            }
            if (!fullLightBatch.isEmpty()) {
                List<ProtoChunk> batchChunks = new ArrayList<>();
                for (WorldgenLightEventRef lightEvent : fullLightBatch) {
                    ChunkAccess raw = grid.get(ChunkPos.pack(lightEvent.x(), lightEvent.z()));
                    if (!(raw instanceof ProtoChunk proto)) {
                        throw new IOException("LIGHT target unavailable: " + lightEvent);
                    }
                    batchChunks.add(proto);
                }
                lighting.lightAll(batchChunks);
                for (WorldgenLightEventRef lightEvent : fullLightBatch) {
                    if (!capturedLightData.isEmpty() && capturedFinalLightData.isEmpty()) {
                        LightData expected = capturedLightData.get(capturedLightCursor++);
                        if (expected.x() != lightEvent.x() || expected.z() != lightEvent.z()) {
                            throw new IOException("LIGHT data/event order mismatch: " + expected
                                    + " vs " + lightEvent);
                        }
                        if (traced(origins, expected.x(), expected.z(), SCORE_RING)) {
                            String block = lightDigest(lighting.engine(),
                                    net.minecraft.world.level.LightLayer.BLOCK,
                                    expected.x(), expected.z(), expected.minSection(), expected.maxSection());
                            String sky = lightDigest(lighting.engine(),
                                    net.minecraft.world.level.LightLayer.SKY,
                                    expected.x(), expected.z(), expected.minSection(), expected.maxSection());
                            capturedLightCompared++;
                            if (!block.equals(expected.blockHash()) || !sky.equals(expected.skyHash())) {
                                capturedLightDiffs++;
                            }
                        }
                    }
                }
            }
            }
            if (replayLightingLifecycle) {
                // Diagnostic oracle only: infer operations from captured
                // lifecycle status snapshots. Never used by strict parity.
                syncLighting(lighting, e.refs, grid);
            }
            if (spawnReplay != null) {
                spawnReplay.replayBoundary(
                        previousEventSeq, driver, structural, grid, lighting,
                        previousEvent, e);
            }
            // ENTER is the observable input boundary for a FEATURES
            // invocation. The harness' COMPLETE hook runs at the return of
            // ChunkStatusTasks.generateFeatures, before ChunkStep.apply's
            // post-return status publication, while Kestrel folds that
            // publication into its stage helper. Comparing statuses at
            // COMPLETE would therefore compare two different cutpoints.
            if (e.phase.equals("ENTER")) {
                assertLifecycleStatuses(e, grid);
            }
            if (replayHiddenHeightmaps) {
                // Diagnostic-only lifecycle replay. A real parity gate must
                // not delete Kestrel heightmaps merely because the Vanilla
                // capture says they were absent at this cut.
                syncHiddenHeightmapPresence(e, hiddenRows, grid);
            }
            compareHidden(e, hiddenRows, grid);
            if (e.phase.equals("ENTER")) {
                compare(root, e, grid);
                if (hasExecutorOps) {
                    // ENTER observes the pre-pass status. Completions stamped
                    // with that ENTER belong after the snapshot, before the
                    // next observable boundary, not before the ENTER assertion.
                    while (terrainLightCursor < terrainLightEvents.size()
                            && terrainLightEvents.get(terrainLightCursor).afterEventSeq() == e.sequence()) {
                        WorldgenLightEventRef completion = terrainLightEvents.get(terrainLightCursor++);
                        if (completion.activeOriginX() == null
                                || completion.activeOriginX() != e.x()
                                || completion.activeOriginZ() != e.z()) {
                            throw new IOException("light completion outside its active FEATURES interval: "
                                    + completion);
                        }
                        publishLightStatus(completion, grid);
                    }
                }
                var center = new ChunkPos(e.x, e.z);
                List<StructureSourceRef> expectedStructureSources =
                        structureSourceRows.getOrDefault(center.pack(), List.of());
                List<StructureReferenceStateRef> expectedStructureReferenceStates =
                        structureReferenceStateRows.getOrDefault(
                                center.pack(), List.of());
                if (!expectedStructureReferenceStates.isEmpty()) {
                    syncStructureReferenceLifecycle(
                            center,
                            expectedStructureReferenceStates,
                            expectedStructureSources,
                            grid,
                            replayStructureOrder);
                }
                HeadlessNoise.RegionView view =
                        new HeadlessNoise.RegionView(
                                driver, center, grid, lighting.engine(),
                                structural == null ? null : structural.templates());
                if (System.getProperty("kestrel.featureSeedDump") != null) {
                    view.featureSeedDebug = FeaturesTraceReplay::writeFeatureSeedDump;
                }
                view.featureFailure = (key, failure) -> {
                    throw new AssertionError(
                            "FEATURE_FAILURE origin=" + center.x() + "," + center.z()
                                    + " key=" + key,
                            failure);
                };

                List<HeightQuery> expectedHeightQueries = null;
                int[] heightQueryCursor = {0};
                if (hasHeightQueryTrace) {
                    expectedHeightQueries = heightQueryTrace.readOrigin(center);
                    final List<HeightQuery> expected = expectedHeightQueries;
                    if (replayHeightQueries) {
                        // Diagnostic-only mode. This deliberately feeds the
                        // captured Vanilla result back into Kestrel and must
                        // never be used as the normal Tier-R parity proof.
                        view.heightQueryOverride = (type, x, z) -> {
                            int cursor = heightQueryCursor[0];
                            if (cursor >= expected.size()) {
                                throw new AssertionError(
                                        "HEIGHT_QUERY_EXTRA origin=" + center.x() + "," + center.z()
                                        + " index=" + (cursor + 1)
                                        + " actual=" + type + "," + x + "," + z);
                            }
                            HeightQuery query = expected.get(cursor);
                            int actualIndex = cursor + 1;
                            if (query.index() != actualIndex
                                    || !query.type().equals(type.toString())
                                    || query.x() != x
                                    || query.z() != z) {
                                throw new AssertionError(
                                        "HEIGHT_QUERY_MISMATCH origin=" + center.x() + "," + center.z()
                                        + " index=" + actualIndex
                                        + " expected=" + query.type() + "," + query.x() + "," + query.z()
                                        + " actual=" + type + "," + x + "," + z);
                            }
                            heightQueryCursor[0] = actualIndex;
                            return query.result();
                        };
                    } else {
                        // Gate mode: Kestrel computes the value itself. The
                        // capture is assertion data only, never an input.
                        view.heightQueryObserver = (type, x, z, result) -> {
                            int cursor = heightQueryCursor[0];
                            if (cursor >= expected.size()) {
                                throw new AssertionError(
                                        "HEIGHT_QUERY_EXTRA origin=" + center.x() + "," + center.z()
                                        + " index=" + (cursor + 1)
                                        + " actual=" + type + "," + x + "," + z + "=" + result);
                            }
                            HeightQuery query = expected.get(cursor);
                            int actualIndex = cursor + 1;
                            if (query.index() != actualIndex
                                    || !query.type().equals(type.toString())
                                    || query.x() != x
                                    || query.z() != z
                                    || query.result() != result) {
                                throw new AssertionError(
                                        "HEIGHT_QUERY_MISMATCH origin=" + center.x() + "," + center.z()
                                        + " index=" + actualIndex
                                        + " expected=" + query.type() + "," + query.x() + "," + query.z()
                                        + "=" + query.result()
                                        + " actual=" + type + "," + x + "," + z + "=" + result
                                        + describeHeightQueryState(grid, type, x, z));
                            }
                            heightQueryCursor[0] = actualIndex;
                        };
                    }
                }

                List<StructureCallRef> expectedStructureCalls =
                        structureCallRows.getOrDefault(center.pack(), List.of());
                int[] structureCallCursor = {0};
                int[] structureSourceCursor = {0};
                StructureCallRef[] activeStructureCall = {null};
                List<long[]> expectedStructureTimes =
                        structureCallTimeRows.getOrDefault(center.pack(), List.of());
                int[] structureTimeCursor = {0};

                if (!expectedStructureCalls.isEmpty()
                        || !expectedStructureSources.isEmpty()
                        || !expectedStructureTimes.isEmpty()) {
                    view.beforeStructure = (key, start) -> {
                        try {
                            if (!expectedStructureTimes.isEmpty()) {
                                int timeCursor = structureTimeCursor[0];
                                if (timeCursor >= expectedStructureTimes.size()) {
                                    throw new AssertionError(
                                            "extra structure placement call without a "
                                                    + "captured game time origin="
                                                    + center.x() + "," + center.z()
                                                    + " key=" + key);
                                }
                                driver.clock().set(expectedStructureTimes.get(timeCursor)[0]);
                            }
                            String actualBox = start.getBoundingBox().toString();

                            if (!expectedStructureSources.isEmpty()) {
                                int sourceCursor = structureSourceCursor[0];
                                if (sourceCursor >= expectedStructureSources.size()) {
                                    throw new AssertionError(
                                            "extra structure source origin="
                                                    + center.x() + "," + center.z()
                                                    + " key=" + key
                                                    + " box=" + actualBox);
                                }
                                StructureSourceRef sourceRef =
                                        expectedStructureSources.get(sourceCursor);
                                int actualIndex = sourceCursor + 1;
                                ChunkPos sourcePos = start.getChunkPos();
                                String structureClass =
                                        start.getStructure().getClass().getName();
                                if (sourceRef.index() != actualIndex
                                        || sourceRef.targetX() != center.x()
                                        || sourceRef.targetZ() != center.z()
                                        || sourceRef.sourceX() != sourcePos.x()
                                        || sourceRef.sourceZ() != sourcePos.z()
                                        || !sourceRef.structureClass().equals(structureClass)
                                        || !sourceRef.box().equals(actualBox)) {
                                    throw new AssertionError(
                                            "structure-source mismatch origin="
                                                    + center.x() + "," + center.z()
                                                    + " index=" + actualIndex
                                                    + "\n  expected target="
                                                    + sourceRef.targetX() + "," + sourceRef.targetZ()
                                                    + " source=" + sourceRef.sourceX() + "," + sourceRef.sourceZ()
                                                    + " class=" + sourceRef.structureClass()
                                                    + " box=" + sourceRef.box()
                                                    + "\n  actual target="
                                                    + center.x() + "," + center.z()
                                                    + " source=" + sourcePos.x() + "," + sourcePos.z()
                                                    + " class=" + structureClass
                                                    + " key=" + key
                                                    + " box=" + actualBox
                                                    + "\n  references="
                                                    + describeStructureReferenceOrder(
                                                            center, start.getStructure(), grid));
                                }
                                structureSourceCursor[0] = actualIndex;
                                structureSourceComparisons++;
                            }

                            if (!expectedStructureCalls.isEmpty()) {
                                int cursor = structureCallCursor[0];
                                if (cursor >= expectedStructureCalls.size()) {
                                    throw new AssertionError(
                                            "extra structure placement call origin="
                                                    + center.x() + "," + center.z()
                                                    + " key=" + key);
                                }
                                StructureCallRef ref =
                                        expectedStructureCalls.get(cursor);
                                int actualIndex = cursor + 1;
                                if (ref.index() != actualIndex) {
                                    throw new AssertionError(
                                            "structure-call index mismatch origin="
                                                    + center.x() + "," + center.z()
                                                    + " expected=" + ref.index()
                                                    + " actual=" + actualIndex);
                                }
                                if (ref.targetX() != center.x()
                                        || ref.targetZ() != center.z()) {
                                    throw new AssertionError(
                                            "structure-call target mismatch origin="
                                                    + center.x() + "," + center.z()
                                                    + " expected=" + ref.targetX()
                                                    + "," + ref.targetZ());
                                }
                                if (!ref.box().equals(actualBox)) {
                                    throw new AssertionError(
                                            "structure-call bounding box mismatch origin="
                                                    + center.x() + "," + center.z()
                                                    + " index=" + actualIndex
                                                    + " key=" + key
                                                    + " expected=" + ref.box()
                                                    + " actual=" + actualBox
                                                    + " references="
                                                    + describeStructureReferenceOrder(
                                                            center, start.getStructure(), grid));
                                }
                                assertStructureCallSnapshot(
                                        root, ref, false, grid);
                                assertStructureCallContent(
                                        ref, false, grid);
                                activeStructureCall[0] = ref;
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException(
                                    "structure-call before parity", ex);
                        }
                    };
                    view.afterStructure = (key, start) -> {
                        try {
                            if (!expectedStructureTimes.isEmpty()) {
                                structureTimeCursor[0]++;
                                driver.clock().set(0L);
                            }
                            if (expectedStructureCalls.isEmpty()) {
                                return;
                            }
                            StructureCallRef ref = activeStructureCall[0];
                            if (ref == null) {
                                throw new AssertionError(
                                        "structure placement completed without "
                                                + "matching before hook origin="
                                                + center.x() + "," + center.z());
                            }
                            assertStructureCallSnapshot(
                                    root, ref, true, grid);
                            assertStructureCallContent(
                                    ref, true, grid);
                            structureCallCursor[0]++;
                            structureCallComparisons++;
                            activeStructureCall[0] = null;
                            System.out.println(
                                    "STRUCTURE_CALL_MATCH origin="
                                            + center.x() + "," + center.z()
                                            + " index=" + ref.index()
                                            + " key=" + key
                                            + " box=" + ref.box());
                        } catch (Exception ex) {
                            throw new RuntimeException(
                                    "structure-call after parity", ex);
                        }
                    };
                }

                if (featureTraceOrigin != null && center.equals(featureTraceOrigin)) {
                    Event expectedComplete = completeByOrigin.get(center.pack());
                    if (expectedComplete == null) {
                        throw new IOException("missing COMPLETE event for feature trace origin " + center);
                    }
                    CoalGap initialGap = coalGap(root, expectedComplete, grid);
                    long[] previousMissing = { initialGap.missing };
                    long[] previousExtra = { initialGap.extra };
                    System.out.println("FEATURE_TRACE_BEGIN origin=" + center.x() + "," + center.z()
                            + " missingVanillaCoal=" + initialGap.missing
                            + " extraKestrelCoal=" + initialGap.extra);
                    System.out.println("FEATURE_TRACE_HEIGHTMAPS origin=" + center.x() + "," + center.z()
                            + " " + traceWindowHeightmaps(center, grid));
                    System.out.println("FEATURE_TRACE_STATUSES origin=" + center.x() + "," + center.z()
                            + " " + traceWindowStatuses(center, grid));
                    java.util.concurrent.atomic.AtomicBoolean traceCoalLower =
                            new java.util.concurrent.atomic.AtomicBoolean();
                    java.util.concurrent.atomic.AtomicInteger biomeQueryIndex =
                            new java.util.concurrent.atomic.AtomicInteger();
                    view.beforeFeatureDebug = line -> {
                        boolean coalLower = line.contains("ore_coal_lower")
                                || (line.contains("globalIndex=10") && line.contains("stepIndex=6"));
                        traceCoalLower.set(coalLower);
                        if (coalLower) {
                            try {
                                compareOreInputs(root, grid);
                            } catch (Exception ex) {
                                throw new RuntimeException("ore input comparison", ex);
                            }
                        }
                        if (line.contains("ore_coal_")) {
                            System.out.println("FEATURE_SEED origin=" + center.x() + "," + center.z()
                                    + " " + line);
                        }
                    };
                    view.biomeQueryDebug = (p, biome) -> {
                        if (!traceCoalLower.get()) return;
                        int index = biomeQueryIndex.incrementAndGet();
                        System.out.println("KESTREL_BIOME_FILTER index=" + index
                                + " pos=" + p.getX() + "," + p.getY() + "," + p.getZ()
                                + " biome=" + biome.unwrapKey());
                    };
                    view.afterFeature = key -> {
                        try {
                            CoalGap gap = coalGap(root, expectedComplete, grid);
                            String name = String.valueOf(key);
                            if (gap.missing != previousMissing[0]
                                    || gap.extra != previousExtra[0]
                                    || name.contains("coal")) {
                                System.out.println("FEATURE_TRACE origin=" + center.x() + "," + center.z()
                                        + " key=" + name
                                        + " missingVanillaCoal=" + gap.missing
                                        + " deltaMissing=" + (gap.missing - previousMissing[0])
                                        + " extraKestrelCoal=" + gap.extra
                                        + " deltaExtra=" + (gap.extra - previousExtra[0]));
                            }
                            previousMissing[0] = gap.missing;
                            previousExtra[0] = gap.extra;
                            traceCoalLower.set(false);
                        } catch (Exception ex) {
                            throw new RuntimeException("feature trace probe", ex);
                        }
                    };
                }

                if (applyLightingEventReplay
                        && lightingEventRows.containsKey(e.sequence())) {
                    // Completions stamped with this pass's own ENTER sequence
                    // landed while the pass was running. They are applied
                    // before it rather than at the interval boundary, so the
                    // placements that read brightness see the same light state
                    // the capture did. See replayIntraPassLightingEvents.
                    Event passComplete =
                            completeByOrigin.get(ChunkPos.pack(e.x(), e.z()));
                    if (passComplete == null) {
                        throw new IOException(
                                "missing COMPLETE snapshot for FEATURES ENTER "
                                        + e.x() + "," + e.z());
                    }
                    lightingEventReplays += replayIntraPassLightingEvents(
                            root, e, passComplete, lightingEventRows, grid, lighting,
                            completeByOrigin);
                    preAppliedIntraSeqs.add(e.sequence());
                }
                if (structural == null) {
                    HeadlessNoise.features(driver, grid.get(center.pack()), view);
                } else if (skipStructureOrigin != null && center.equals(skipStructureOrigin)) {
                    System.out.println("STRUCTURE_PIECES_SKIPPED origin="
                            + center.x() + "," + center.z());
                    HeadlessNoise.features(driver, grid.get(center.pack()), view);
                } else {
                    HeadlessNoise.featuresWithStructures(
                            structural, grid.get(center.pack()), view);
                }

                if (!expectedStructureCalls.isEmpty()) {
                    if (activeStructureCall[0] != null
                            || structureCallCursor[0]
                                    != expectedStructureCalls.size()) {
                        throw new AssertionError(
                                "structure-call replay coverage mismatch origin="
                                        + center.x() + "," + center.z()
                                        + " expected="
                                        + expectedStructureCalls.size()
                                        + " matched="
                                        + structureCallCursor[0]);
                    }
                }
                if (!expectedStructureSources.isEmpty()
                        && structureSourceCursor[0]
                                != expectedStructureSources.size()) {
                    throw new AssertionError(
                            "structure-source replay coverage mismatch origin="
                                    + center.x() + "," + center.z()
                                    + " expected="
                                    + expectedStructureSources.size()
                                    + " matched="
                                    + structureSourceCursor[0]);
                }

                if (hasHeightQueryTrace
                        && heightQueryCursor[0] != expectedHeightQueries.size()) {
                    HeightQuery next = expectedHeightQueries.get(heightQueryCursor[0]);
                    throw new IllegalStateException(
                            "HEIGHT_QUERY_MISSING origin=" + center.x() + "," + center.z()
                            + " consumed=" + heightQueryCursor[0]
                            + " expected=" + expectedHeightQueries.size()
                            + " next=" + next.type() + "," + next.x() + "," + next.z());
                }

                if (featureTraceOrigin != null && center.equals(featureTraceOrigin)) {
                    Event expectedComplete = completeByOrigin.get(center.pack());
                    CoalGap finalGap = coalGap(root, expectedComplete, grid);
                    System.out.println("FEATURE_TRACE_END origin=" + center.x() + "," + center.z()
                            + " missingVanillaCoal=" + finalGap.missing
                            + " extraKestrelCoal=" + finalGap.extra);
                }
            } else {
                compare(root, e, grid);
                compareStructureData(e, structureDataRows, grid);
                ProtoChunk center = (ProtoChunk)grid.get(ChunkPos.pack(e.x,e.z));
                if (center.getPersistedStatus().isBefore(ChunkStatus.FEATURES)) {
                    center.setPersistedStatus(ChunkStatus.FEATURES);
                }
                if (!applyLightingEventReplay && !hasExecutorBatches && !replayLightingLifecycle) {
                    // Native diagnostic path. Strict structures-on uses the
                    // exact completion-event stream instead.
                    lighting.initialize(center);
                }
            }
            previousEventSeq = e.sequence();
            previousEvent = e;
        }
        while (lightExecutorBatchCursor < lightExecutorBatches.size()) {
            LightExecutorBatch batch = lightExecutorBatches.get(lightExecutorBatchCursor++);
            if (batch.afterEventSeq() != previousEventSeq) {
                throw new IOException("light executor batch after unknown FEATURES cut "
                        + batch.afterEventSeq());
            }
            replayLightExecutorBatch(batch, grid, lighting, lightCompletionsByBatch, activation);
        }
        while (lightScheduleCursor < lightScheduleOps.size()) {
            LightScheduleOp op = lightScheduleOps.get(lightScheduleCursor++);
            long cut = op.activeX() == null ? op.afterEventSeq() : op.afterEventSeq() - 1L;
            if (cut != previousEventSeq) {
                throw new IOException("light schedule operation after unknown FEATURES cut " + cut);
            }
            ChunkAccess raw = grid.get(ChunkPos.pack(op.x(), op.z()));
            if (raw instanceof ProtoChunk chunk) lighting.prepareInitialize(chunk);
        }
        if (hasExecutorOps) {
            while (terrainLightCursor < terrainLightEvents.size()) {
                WorldgenLightEventRef completion = terrainLightEvents.get(terrainLightCursor++);
                if (completion.afterEventSeq() != previousEventSeq) {
                    throw new IOException("light completion after unknown FEATURES cut " + completion);
                }
                publishLightStatus(completion, grid);
            }
        }
        while (!hasExecutorOps && terrainLightCursor < terrainLightEvents.size()) {
            long batch = hasExecutorBatches
                    ? terrainLightEvents.get(terrainLightCursor).batch()
                    : terrainLightEvents.get(terrainLightCursor).afterEventSeq();
            List<ProtoChunk> initializeChunks = new ArrayList<>();
            List<ProtoChunk> lightChunks = new ArrayList<>();
            while (terrainLightCursor < terrainLightEvents.size()
                    && (hasExecutorBatches
                            ? terrainLightEvents.get(terrainLightCursor).batch()
                            : terrainLightEvents.get(terrainLightCursor).afterEventSeq()) == batch) {
                WorldgenLightEventRef lightEvent = terrainLightEvents.get(terrainLightCursor++);
                if (!lightEvent.outcome().equals("OK")) {
                    throw new IOException("captured worldgen LIGHT failed: " + lightEvent);
                }
                ChunkAccess raw = grid.get(ChunkPos.pack(lightEvent.x(), lightEvent.z()));
                if (!(raw instanceof ProtoChunk proto)) continue;
                if (lightEvent.stage().equals("minecraft:initialize_light")) {
                    initializeChunks.add(proto);
                } else {
                    lightChunks.add(proto);
                    if (!capturedLightData.isEmpty() && capturedFinalLightData.isEmpty()) {
                        capturedLightCursor++;
                    }
                }
            }
            if (!initializeChunks.isEmpty()) {
                lighting.initializeAll(initializeChunks);
            }
            if (!lightChunks.isEmpty()) lighting.lightAll(lightChunks);
        }
        if (!reportOnly && hasReplayableLightingTrace && !hasExecutorOps) {
            lightingEventReplays += replayLightingEvents(
                    root, previousEventSeq, lightingEventRows, grid, lighting,
                    previousEvent, null, preAppliedIntraSeqs);
        }
        if (spawnReplay != null) {
            spawnReplay.replayBoundary(
                    previousEventSeq, driver, structural, grid, lighting,
                    previousEvent, null);
            spawnReplay.finish();
        }
        if (heightQueryTrace != null) {
            heightQueryTrace.finish();
            System.out.println("HEIGHT_QUERY_RESULT origins="
                    + heightQueryTrace.originsRead()
                    + " queries=" + heightQueryTrace.queriesRead()
                    + " PASS");
            heightQueryTrace.close();
        }
        lighting.probeExtraDrain();
        probeWaterColumns(lightingGrid);
        if (!capturedFinalLightData.isEmpty() && vanillaRegion == null) {
            capturedLightCompared = 0;
            capturedLightDiffs = 0;
            for (LightData expected : capturedFinalLightData) {
                if (!traced(origins, expected.x(), expected.z(), SCORE_RING)) continue;
                String block = lightDigest(lighting.engine(),
                        net.minecraft.world.level.LightLayer.BLOCK,
                        expected.x(), expected.z(), expected.minSection(), expected.maxSection());
                String sky = lightDigest(lighting.engine(),
                        net.minecraft.world.level.LightLayer.SKY,
                        expected.x(), expected.z(), expected.minSection(), expected.maxSection());
                capturedLightCompared++;
                if (!block.equals(expected.blockHash()) || !sky.equals(expected.skyHash())) {
                    capturedLightDiffs++;
                    String dump = System.getProperty("kestrel.finalLightDump");
                    if (dump != null) {
                        Path directory = Path.of(dump);
                        Files.createDirectories(directory);
                        for (var layer : net.minecraft.world.level.LightLayer.values()) {
                            try (var out = Files.newOutputStream(directory.resolve(
                                    expected.x() + "_" + expected.z() + "_" + layer + ".bin"))) {
                                for (int sy = expected.minSection(); sy < expected.maxSection(); sy++) {
                                    var data = lighting.engine().getLayerListener(layer)
                                            .getDataLayerData(SectionPos.of(expected.x(), sy, expected.z()));
                                    out.write(data == null ? new byte[2048] : data.getData());
                                }
                            }
                        }
                    }
                    System.out.println("FINAL_LIGHT_DIFF chunk=" + expected.x() + "," + expected.z()
                            + " block=" + block + " expectedBlock=" + expected.blockHash()
                            + " sky=" + sky + " expectedSky=" + expected.skyHash());
                }
            }
            if (activation != null) {
                System.out.println(activation.summary());
            }
            System.out.println("FINAL_LIGHT_DATA_RESULT compared=" + capturedLightCompared
                    + " diffs=" + capturedLightDiffs);
            if (capturedLightCompared == 0 || capturedLightDiffs != 0) {
                throw new AssertionError("final light parity failed: captured="
                        + capturedFinalLightData.size() + " compared=" + capturedLightCompared
                        + " diffs=" + capturedLightDiffs);
            }
        } else if (!capturedLightData.isEmpty() && vanillaRegion == null) {
            System.out.println("LIGHT_DATA_RESULT compared=" + capturedLightCompared
                    + " diffs=" + capturedLightDiffs);
            if (capturedLightCursor != capturedLightData.size()
                    || capturedLightCompared == 0 || capturedLightDiffs != 0) {
                throw new AssertionError("light parity failed: captured="
                        + capturedLightData.size() + " replayed=" + capturedLightCursor
                        + " compared=" + capturedLightCompared
                        + " diffs=" + capturedLightDiffs);
            }
        }
        if (vanillaRegion != null) {
            for (LightStage stage : readLightStages(root)) {
                ChunkAccess chunk = grid.get(ChunkPos.pack(stage.x(), stage.z()));
                if (chunk instanceof ProtoChunk proto) lighting.light(proto);
            }
            int compared = 0;
            int diffs = 0;
            int blockDiffs = 0;
            int skyDiffs = 0;
            Map<Long, RegionFile> regions = new HashMap<>();
            try {
                int minLight = lighting.engine().getMinLightSection();
                int maxLight = lighting.engine().getMaxLightSection();
                for (long key : new TreeSet<>(origins)) {
                    int x = (int) key;
                    int z = (int) (key >> 32);
                    if (!traced(origins, x, z, SCORE_RING)) continue;
                    long regionKey = ChunkPos.pack(x >> 5, z >> 5);
                    RegionFile region = regions.get(regionKey);
                    if (region == null) {
                        Path path = vanillaRegion.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
                        region = RegionFile.open(path);
                        regions.put(regionKey, region);
                    }
                    Nbt.Tag.TCompound nbt = region.readChunk(x & 31, z & 31);
                    // The trace includes the generation halo, while Vanilla
                    // only guarantees that explicitly force-loaded centers
                    // are persisted. Compare every saved center and ignore
                    // transient halo chunks absent from the region file.
                    if (nbt == null) continue;
                    String actualBlock = lightDigest(lighting.engine(),
                            net.minecraft.world.level.LightLayer.BLOCK, x, z, minLight, maxLight);
                    String actualSky = lightDigest(lighting.engine(),
                            net.minecraft.world.level.LightLayer.SKY, x, z, minLight, maxLight);
                    String expectedBlock = nbtLightDigest(nbt, "BlockLight", minLight, maxLight);
                    String expectedSky = nbtLightDigest(nbt, "SkyLight", minLight, maxLight);
                    compared++;
                    boolean blockSame = actualBlock.equals(expectedBlock);
                    boolean skySame = actualSky.equals(expectedSky);
                    if (!blockSame) blockDiffs++;
                    if (!skySame) skyDiffs++;
                    if (!blockSame || !skySame) {
                        diffs++;
                        System.out.println("VANILLA_LIGHT_DIFF chunk=" + x + "," + z
                                + " block=" + blockSame + " sky=" + skySame
                                + " actualBlock=" + actualBlock
                                + " expectedBlock=" + expectedBlock);
                    }
                }
            } finally {
                for (RegionFile region : regions.values()) region.close();
            }
            System.out.println("VANILLA_LIGHT_RESULT compared=" + compared + " diffs=" + diffs
                    + " blockDiffs=" + blockDiffs + " skyDiffs=" + skyDiffs);
            if (compared == 0 || diffs != 0) {
                throw new AssertionError("Vanilla light parity failed: compared=" + compared
                        + " diffs=" + diffs);
            }
        }
        System.out.println("CHUNK_RELOAD_RESULT captured="
                + chunkLoadRows.values().stream().mapToLong(List::size).sum()
                + " replays=" + chunkReloadReplays
                + " heightmapReplays=" + chunkReloadHeightmapReplays
                + " heightmapPrefixNoops=" + chunkReloadHeightmapPrefixNoops
                + " heightmapOutsideCoverage=" + chunkReloadHeightmapOutsideCoverage
                + " mode="
                + (!reportOnly && structuresOn && hasReplayableChunkLoadTrace
                        ? "exact-replay"
                        : (replayChunkLoads ? "diagnostic-replay" : "native-assert")));
        long capturedLightingEvents =
                lightingEventRows.values().stream().mapToLong(List::size).sum();
        long capturedIntraLightingEvents =
                lightingEventRows.values().stream()
                        .flatMap(List::stream)
                        .filter(event -> event.activeOriginX() != null)
                        .count();
        if (!reportOnly
                && hasReplayableLightingTrace
                && !hasExecutorOps
                && lightingEventReplays != capturedLightingEvents) {
            throw new AssertionError(
                    "lighting-event replay coverage mismatch: captured="
                            + capturedLightingEvents
                            + " replayed=" + lightingEventReplays);
        }
        System.out.println("LIGHTING_EVENT_RESULT captured="
                + capturedLightingEvents
                + " replays=" + lightingEventReplays
                + " mode="
                + (hasExecutorOps
                        ? "executor-pre-stream"
                        : !reportOnly && hasReplayableLightingTrace
                        ? (capturedIntraLightingEvents == 0
                                ? "exact-cutpoint-replay"
                                : "partial-order+proven-equivalent-interval")
                        : (replayLightingLifecycle
                                ? "diagnostic-status-oracle"
                                : "native")));
        System.out.println("LIGHTING_REPLAY initialized=" + lighting.initializedCount()
                + " lit=" + lighting.litCount());
        if (!structureDataRows.isEmpty()) {
            long expectedStructureRows =
                    structureDataRows.values().stream().mapToLong(List::size).sum();
            if (structureDataComparisons != expectedStructureRows) {
                throw new AssertionError(
                        "structure-data replay coverage mismatch: expected "
                        + expectedStructureRows + " compared " + structureDataComparisons);
            }
            System.out.println("STRUCTURE_DATA_RESULT comparisons="
                    + structureDataComparisons + " PASS");
        }

        if (!structureCallRows.isEmpty()) {
            long expectedStructureCalls =
                    structureCallRows.values().stream().mapToLong(List::size).sum();
            if (structureCallComparisons != expectedStructureCalls) {
                throw new AssertionError(
                        "structure-call replay coverage mismatch: expected "
                                + expectedStructureCalls
                                + " compared " + structureCallComparisons);
            }
            System.out.println(
                    "STRUCTURE_CALL_RESULT comparisons="
                            + structureCallComparisons + " PASS");
        }
        if (!structureSourceRows.isEmpty()) {
            long expectedStructureSources =
                    structureSourceRows.values().stream().mapToLong(List::size).sum();
            if (structureSourceComparisons != expectedStructureSources) {
                throw new AssertionError(
                        "structure-source replay coverage mismatch: expected "
                                + expectedStructureSources
                                + " compared " + structureSourceComparisons);
            }
            System.out.println(
                    "STRUCTURE_SOURCE_RESULT comparisons="
                            + structureSourceComparisons + " PASS");
        }
        if (!structureReferenceStateRows.isEmpty()) {
            long expectedStructureReferenceStates =
                    structureReferenceStateRows.values().stream()
                            .mapToLong(List::size).sum();
            if (structureReferenceStateComparisons
                    != expectedStructureReferenceStates) {
                throw new AssertionError(
                        "structure-reference-state replay coverage mismatch: expected "
                                + expectedStructureReferenceStates
                                + " compared "
                                + structureReferenceStateComparisons);
            }
            System.out.println(
                    "STRUCTURE_REFERENCE_STATE_RESULT comparisons="
                            + structureReferenceStateComparisons + " PASS");
        }
        if (!hiddenRows.isEmpty()) {
            System.out.println("HIDDEN_RESULT comparisons=" + hiddenComparisons
                    + " biomeDiffs=" + hiddenBiomeDiffs
                    + " oceanFloorWgDiffs=" + hiddenHeightDiffs
                    + " mode="
                    + (replayHiddenHeightmaps ? "oracle-replay" : "native-assert"));
            if (!reportOnly && (hiddenBiomeDiffs != 0 || hiddenHeightDiffs != 0)) {
                throw new AssertionError(
                        "hidden lifecycle parity failed: biomeDiffs="
                                + hiddenBiomeDiffs
                                + " oceanFloorWgDiffs=" + hiddenHeightDiffs);
            }
        }
        System.out.println("RESULT blockObservationDifferences=" + allDifferences + " targetFamilyObservationDifferences=" + familyDifferences
                + " distinctWrongCells=" + distinctWrongCells.size() + " distinctFamilyWrongCells=" + distinctFamilyWrongCells.size());

        // One-shot final-state comparison per chunk (vanilla's last-observed snapshot vs Kestrel's
        // settled grid), so per-chunk magnitude isn't inflated by how many neighboring events
        // happened to include that chunk in their 3x3 dependency window.
        // Only chunks whose whole ring-2 neighbourhood decorated inside this trace are
        // scoreable. Ring 1 supplies all direct writers, but those writers make placement
        // decisions by reading their own ring-1 footprints. Missing that second ring gives
        // Kestrel different inputs even though every direct writer was replayed.
        long finalTotal = 0, scoredTotal = 0, scoredCells = 0, scoredChunks = 0, scoredExact = 0;
        long cascadeTotal = 0, cascadeChunks = 0;
        List<String> perChunk = new ArrayList<>();
        List<String> scoredPerChunk = new ArrayList<>();
        // Under --report-only, dump every scored differing cell. 43 lines beats
        // guessing, and the vanilla/kestrel state pair usually names the feature.
        List<String> cellDump = new ArrayList<>();
        for (long key : new TreeSet<>(coverage)) {
            Snapshot s = read(root, lastRef.get(key));
            ChunkAccess chunk = grid.get(key);
            boolean scoreable = traced(origins, s.x, s.z, SCORE_RING);
            // Receives every write, but its writers read untraced ground. Reported, not gated.
            boolean cascade = !scoreable && traced(origins, s.x, s.z, 1);
            long diffs = 0;
            for (int i = 0; i < s.ids.length; i++) {
                BlockPos p = new BlockPos(s.x * 16 + (i & 15), s.minY + (i >> 8), s.z * 16 + ((i >> 4) & 15));
                var actual = capturedState(chunk, p);
                if (activation != null) {
                    // The snapshot predates chunk activation; compare the
                    // generation output, see ReplayActivation.generationState.
                    actual = activation.generationState(p, actual);
                }
                if (Block.getId(actual) == s.ids[i]) continue;
                diffs++;
                if (reportOnly && (scoreable || cascade))
                    cellDump.add((scoreable ? "SCORED_DIFF" : "CASCADE_DIFF") + " chunk=" + s.x + "," + s.z
                            + " cell=" + p.getX() + "," + p.getY() + "," + p.getZ()
                            + " vanilla=" + Block.stateById(s.ids[i]) + " kestrel=" + actual);
            }
            finalTotal += diffs;
            if (diffs > 0) perChunk.add(s.x + "," + s.z + "=" + diffs);
            if (cascade) { cascadeChunks++; cascadeTotal += diffs; }
            if (scoreable) {
                scoredChunks++;
                scoredCells += s.ids.length;
                scoredTotal += diffs;
                if (diffs == 0) scoredExact++;
                else scoredPerChunk.add(s.x + "," + s.z + "=" + diffs);
            }
        }
        System.out.println("FINAL_STATE_DISTINCT_DIFFS total=" + finalTotal + " chunksAffected=" + perChunk.size()
                + " perChunk=" + String.join(" ", perChunk));
        System.out.println("UNSCOREABLE_EDGE_DIFFS total=" + (finalTotal - scoredTotal)
                + "  (chunks outside the fully traced interior, not Kestrel's to answer for)");
        System.out.println("BOUNDARY_CASCADE chunks=" + cascadeChunks + " diffs=" + cascadeTotal
                + "  (ring 1 complete, ring " + SCORE_RING + " not: writers read undecorated ground)");
        if (scoredCells == 0) {
            throw new IOException("no chunk in this trace has a fully traced ring-" + SCORE_RING
                    + " neighbourhood, nothing to score; record a wider window before using this as a gate");
        }
        System.out.printf("TIER_R ring=%d scoredChunks=%d exact=%d (%.2f%%) cells=%d diffs=%d parity=%.6f%%%n",
                SCORE_RING, scoredChunks, scoredExact, 100.0 * scoredExact / scoredChunks,
                scoredCells, scoredTotal, 100.0 * (scoredCells - scoredTotal) / scoredCells);
        if (!scoredPerChunk.isEmpty()) {
            System.out.println("TIER_R failing chunks: " + String.join(" ", scoredPerChunk));
        }
        for (String line : cellDump) System.out.println(line);
        System.out.println(scoredTotal == 0
                ? "TIER_R VERDICT: PASS, exact under the replayed order"
                : "TIER_R VERDICT: FAIL, " + scoredTotal + " cells across "
                        + scoredPerChunk.size() + " chunks diverge under the replayed order");
        if (scoredTotal != 0 && !reportOnly) {
            throw new AssertionError("tier R is not exact: " + scoredTotal + " cells across "
                    + scoredPerChunk.size() + " fully traced chunks (" + String.join(" ", scoredPerChunk)
                    + "). Rerun with --report-only to diagnose without failing.");
        }

        if (structural != null) {
            structural.close();
        }

        if (guard && !distinctFamilyWrongCells.isEmpty())
            throw new AssertionError("matched-order divergence in the originally reported kelp/seagrass/sulfur seam families");
    }
}
