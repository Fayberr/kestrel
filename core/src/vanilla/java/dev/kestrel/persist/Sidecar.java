package dev.kestrel.persist;

import dev.kestrel.blockentity.SleeperRegistry;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Stage 2 Kestrel sidecar: the own-code state vanilla Anvil has no fields
 * for, stored as one small file per region next to the {@code .mca}.
 *
 * <p>Layout (all big-endian): versioned magic (4 bytes), region X/Z
 * (2x int32), then a section table, a completed-chunk table, a sleeper table, a queue table, and
 * a 4-byte CRC32 over everything before it. Section entries carry the
 * full 64-bit store key (section coords, NOT chunk coords: the chunk NBT
 * cannot address sections outside its own column, and sparse worlds may
 * hold sections in columns with no chunk entry at all) plus 512 bytes of
 * raw written bitset. Sleeper entries carry the packed block id, the
 * type ordinal, and the asleep flag. Queue entries carry the block pos,
 * the facade kind/priority ordinals, and the absolute trigger tick.
 *
 * <p>The sidecar is authoritative for three things the chunk NBT only
 * echoes or omits: the written bitset (chunk NBT has no such field),
 * the sleeper id-to-type mapping (chunk stubs carry a vanilla type id,
 * which a hostile edit could change), and queue entries the chunk filter
 * dropped (OTHER-kind entries never serialize to NBT at all). On restore,
 * the sidecar wins every disagreement.
 *
 * <p>KEX5 (G11) appends a fluid-tick table: block pos, the fluid's
 * registry id (UTF), the vanilla priority value, the absolute trigger
 * tick, and the world-wide drain-order index (see
 * {@code WorldSnapshot.QueuedFluidTickData}). KEX1-KEX4 remain readable and restore no pending fluid ticks,
 * which is what those formats persisted.
 *
 * <p>KEX4 appends exact generated biome-container and light-packet wire
 * metadata. KEX1-KEX3 remain readable and restore these maps empty, matching
 * what those formats actually persisted. KEX2 adds authoritative per-chunk
 * generation completion. KEX1 remains
 * readable and conservatively treats its stored columns as complete so an
 * upgrade cannot regenerate over existing player builds. KEX3 adds a
 * second 512-byte bitset per section: the C1 mutation provenance, which
 * cells the live layer owns and world generation may therefore never
 * rewrite. KEX1 and KEX2 carry no such record, so they upgrade
 * conservatively too, treating every written cell as live-layer owned:
 * that can only cost a generation refresh of old terrain, never a lost
 * build. The magic carries the format version. A reader that
 * does not recognize the magic refuses the file loudly rather than
 * half-loading it; future migrations add a new magic plus a converter.
 *
 * <p>Thread-safety: stateless. Runs on the saver thread (encode) or the
 * tick thread (decode).
 */
public final class Sidecar {

    /** Magic for sidecar format version 1 (ASCII KEX1). */
    public static final int MAGIC_V1 = 0x4B455831;
    public static final int MAGIC_V2 = 0x4B455832;
    public static final int MAGIC_V3 = 0x4B455833;
    public static final int MAGIC_V4 = 0x4B455834;
    public static final int MAGIC_V5 = 0x4B455835;

    /** File suffix next to each region file. */
    public static final String SUFFIX = ".kex";

    /** Sidecar path for a region file path ({@code r.x.z.mca} + suffix). */
    public static Path pathFor(Path regionFile) {
        return regionFile.resolveSibling(regionFile.getFileName().toString() + SUFFIX);
    }

    private Sidecar() {
    }

    /** Encode the snapshot's sidecar tables for one region. */
    public static byte[] encode(WorldSnapshot snap, int regionX, int regionZ) throws IOException {
        List<WorldSnapshot.SectionData> sections = new ArrayList<>();
        for (WorldSnapshot.SectionData s : snap.sections) {
            int sx = net.minecraft.core.SectionPos.x(s.sectionKey());
            int sz = net.minecraft.core.SectionPos.z(s.sectionKey());
            if ((sx >> 5) == regionX && (sz >> 5) == regionZ) {
                sections.add(s);
            }
        }
        List<WorldSnapshot.BlockEntityData> bes = new ArrayList<>();
        for (WorldSnapshot.BlockEntityData b : snap.blockEntities) {
            if ((b.x() >> 9) == regionX && (b.z() >> 9) == regionZ) {
                bes.add(b);
            }
        }
        List<WorldSnapshot.QueuedTickData> queue = new ArrayList<>();
        for (WorldSnapshot.QueuedTickData q : snap.queue) {
            if ((q.x() >> 9) == regionX && (q.z() >> 9) == regionZ) {
                queue.add(q);
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC_V5);
        out.writeInt(regionX);
        out.writeInt(regionZ);
        // World geometry + clock: the loader asserts these match the
        // target level before touching storage (fail loudly, not merged).
        out.writeInt(snap.minY);
        out.writeInt(snap.height);
        out.writeLong(snap.gameTime);
        out.writeInt(snap.dimension.ordinal());
        out.writeInt(sections.size());
        for (WorldSnapshot.SectionData s : sections) {
            out.writeLong(s.sectionKey());
            byte[] bits = new byte[512];
            byte[] editedBits = new byte[512];
            for (int i = 0; i < 4096; i++) {
                if (s.isWritten(i)) {
                    bits[i >> 3] |= (byte) (1 << (i & 7));
                }
                if (s.isEdited(i)) {
                    editedBits[i >> 3] |= (byte) (1 << (i & 7));
                }
            }
            out.write(bits);
            out.write(editedBits);
        }
        List<Long> completed = snap.generatedChunks.stream()
                .filter(key -> (net.minecraft.world.level.ChunkPos.getX(key) >> 5) == regionX
                        && (net.minecraft.world.level.ChunkPos.getZ(key) >> 5) == regionZ)
                .sorted().toList();
        out.writeInt(completed.size());
        for (long key : completed) {
            out.writeLong(key);
        }
        out.writeInt(bes.size());
        for (WorldSnapshot.BlockEntityData b : bes) {
            out.writeLong(blockPosAsLong(b.x(), b.y(), b.z()));
            out.writeByte(b.type().ordinal());
            out.writeByte(b.asleep() ? 1 : 0);
        }
        out.writeInt(queue.size());
        for (WorldSnapshot.QueuedTickData q : queue) {
            out.writeInt(q.x());
            out.writeInt(q.y());
            out.writeInt(q.z());
            out.writeByte(q.kind().ordinal());
            out.writeByte(q.priority().ordinal());
            out.writeLong(q.triggerTick());
        }

        List<Map.Entry<Long, byte[]>> biomeWire =
                snap.biomeWireSections.entrySet().stream()
                        .filter(entry ->
                                (net.minecraft.core.SectionPos.x(entry.getKey()) >> 5) == regionX
                                        && (net.minecraft.core.SectionPos.z(entry.getKey()) >> 5)
                                                == regionZ)
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
        writeWireMap(out, biomeWire);

        List<Map.Entry<Long, byte[]>> lightWire =
                snap.lightWireChunks.entrySet().stream()
                        .filter(entry ->
                                (net.minecraft.world.level.ChunkPos.getX(entry.getKey()) >> 5)
                                                == regionX
                                        && (net.minecraft.world.level.ChunkPos.getZ(entry.getKey()) >> 5)
                                                == regionZ)
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
        writeWireMap(out, lightWire);

        List<WorldSnapshot.QueuedFluidTickData> fluids = new ArrayList<>();
        for (WorldSnapshot.QueuedFluidTickData q : snap.fluidQueue) {
            if ((q.x() >> 9) == regionX && (q.z() >> 9) == regionZ) {
                fluids.add(q);
            }
        }
        out.writeInt(fluids.size());
        for (WorldSnapshot.QueuedFluidTickData q : fluids) {
            out.writeInt(q.x());
            out.writeInt(q.y());
            out.writeInt(q.z());
            out.writeUTF(WorldSnapshot.fluidId(q.fluid()));
            out.writeByte(q.priority());
            out.writeLong(q.triggerTick());
            out.writeInt(q.seq());
        }

        out.flush();
        byte[] body = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body);
        ByteBuffer buf = ByteBuffer.allocate(body.length + 4).order(ByteOrder.BIG_ENDIAN);
        buf.put(body);
        buf.putInt((int) crc.getValue());
        return buf.array();
    }

    /** Decoded sidecar tables for one region. */
    public record Decoded(int regionX, int regionZ, int minY, int height, long gameTime,
            int dimensionOrdinal, List<SectionBits> sections,
            Set<Long> generatedChunks,
            List<WorldSnapshot.BlockEntityData> blockEntities,
            List<WorldSnapshot.QueuedTickData> queue,
            Map<Long, byte[]> biomeWireSections,
            Map<Long, byte[]> lightWireChunks,
            List<WorldSnapshot.QueuedFluidTickData> fluidQueue) {
    }

    /**
     * One section's written bitset plus its C1 mutation-provenance bitset,
     * keyed by store key. {@code edited} is never null on decode: a
     * pre-KEX3 sidecar upgrades to the conservative reading that every
     * written cell is live-layer owned.
     */
    public record SectionBits(long sectionKey, boolean[] written, boolean[] edited) {
    }

    /** Decode and CRC-verify one sidecar file. */
    public static Decoded decode(Path path) throws IOException {
        byte[] all = Files.readAllBytes(path);
        if (all.length < 4) {
            throw new IOException("sidecar too short: " + path);
        }
        ByteBuffer buf = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
        int bodyLen = all.length - 4;
        int stored = buf.getInt(bodyLen);
        CRC32 crc = new CRC32();
        crc.update(all, 0, bodyLen);
        if ((int) crc.getValue() != stored) {
            throw new IOException("sidecar CRC mismatch: " + path);
        }
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(all, 0, bodyLen))) {
            int magic = in.readInt();
            if (magic != MAGIC_V1 && magic != MAGIC_V2
                    && magic != MAGIC_V3 && magic != MAGIC_V4 && magic != MAGIC_V5) {
                throw new IOException("unknown sidecar magic 0x"
                        + Integer.toHexString(magic) + ": " + path);
            }
            int regionX = in.readInt();
            int regionZ = in.readInt();
            int minY = in.readInt();
            int height = in.readInt();
            long gameTime = in.readLong();
            int dimension = in.readInt();
            int sectionCount = in.readInt();
            checkCount(sectionCount, 1024 * 64, "sections");
            List<SectionBits> sections = new ArrayList<>(Math.min(sectionCount, 1024));
            for (int i = 0; i < sectionCount; i++) {
                long key = in.readLong();
                byte[] bits = new byte[512];
                in.readFully(bits);
                boolean[] written = new boolean[4096];
                for (int c = 0; c < 4096; c++) {
                    written[c] = (bits[c >> 3] & (1 << (c & 7))) != 0;
                }
                boolean[] edited = new boolean[4096];
                if (magic == MAGIC_V3 || magic == MAGIC_V4 || magic == MAGIC_V5) {
                    byte[] editedBits = new byte[512];
                    in.readFully(editedBits);
                    for (int c = 0; c < 4096; c++) {
                        edited[c] = (editedBits[c >> 3] & (1 << (c & 7))) != 0;
                    }
                } else {
                    // Pre-KEX3 worlds recorded no provenance. Read every
                    // written cell as live-layer owned: the migration may
                    // cost a generation refresh, never a player's build (C1).
                    System.arraycopy(written, 0, edited, 0, 4096);
                }
                sections.add(new SectionBits(key, written, edited));
            }
            Set<Long> generatedChunks = new HashSet<>();
            if (magic != MAGIC_V1) {
                int generatedCount = in.readInt();
                checkCount(generatedCount, 1024, "generated chunks");
                for (int i = 0; i < generatedCount; i++) {
                    generatedChunks.add(in.readLong());
                }
            } else {
                // KEX1 did not record completion. Keep legacy worlds safe from
                // wholesale regeneration over player builds during migration.
                for (SectionBits section : sections) {
                    generatedChunks.add(net.minecraft.core.SectionPos.sectionToChunk(
                            section.sectionKey()));
                }
            }
            int beCount = in.readInt();
            checkCount(beCount, 1 << 20, "block entities");
            List<WorldSnapshot.BlockEntityData> bes = new ArrayList<>(Math.min(beCount, 1024));
            for (int i = 0; i < beCount; i++) {
                long id = in.readLong();
                int typeOrd = in.readUnsignedByte();
                boolean asleep = in.readUnsignedByte() != 0;
                SleeperRegistry.Type[] types = SleeperRegistry.Type.values();
                if (typeOrd < 0 || typeOrd >= types.length) {
                    throw new IOException("bad sleeper type ordinal " + typeOrd);
                }
                bes.add(new WorldSnapshot.BlockEntityData(
                        net.minecraft.core.BlockPos.getX(id),
                        net.minecraft.core.BlockPos.getY(id),
                        net.minecraft.core.BlockPos.getZ(id),
                        types[typeOrd], asleep));
            }
            int queueCount = in.readInt();
            checkCount(queueCount, 1 << 20, "queue");
            List<WorldSnapshot.QueuedTickData> queue = new ArrayList<>(Math.min(queueCount, 1024));
            dev.kestrel.loop.KestrelTickTarget.Kind[] kinds =
                    dev.kestrel.loop.KestrelTickTarget.Kind.values();
            dev.kestrel.loop.KestrelTickTarget.Priority[] pris =
                    dev.kestrel.loop.KestrelTickTarget.Priority.values();
            for (int i = 0; i < queueCount; i++) {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                int kindOrd = in.readUnsignedByte();
                int priOrd = in.readUnsignedByte();
                long trigger = in.readLong();
                if (kindOrd < 0 || kindOrd >= kinds.length
                        || priOrd < 0 || priOrd >= pris.length) {
                    throw new IOException("bad queue ordinals " + kindOrd + "/" + priOrd);
                }
                queue.add(new WorldSnapshot.QueuedTickData(x, y, z,
                        kinds[kindOrd], pris[priOrd], trigger));
            }
            Map<Long, byte[]> biomeWire = Map.of();
            Map<Long, byte[]> lightWire = Map.of();
            if (magic == MAGIC_V4 || magic == MAGIC_V5) {
                biomeWire = readWireMap(in, 1024 * 64, "biome wire sections");
                lightWire = readWireMap(in, 1024, "light wire chunks");
                for (long key : biomeWire.keySet()) {
                    if ((net.minecraft.core.SectionPos.x(key) >> 5) != regionX
                            || (net.minecraft.core.SectionPos.z(key) >> 5) != regionZ) {
                        throw new IOException(
                                "biome metadata key outside sidecar region: " + key);
                    }
                }
                for (long key : lightWire.keySet()) {
                    if ((net.minecraft.world.level.ChunkPos.getX(key) >> 5) != regionX
                            || (net.minecraft.world.level.ChunkPos.getZ(key) >> 5) != regionZ) {
                        throw new IOException(
                                "light metadata key outside sidecar region: " + key);
                    }
                }
            }
            List<WorldSnapshot.QueuedFluidTickData> fluids = new ArrayList<>();
            if (magic == MAGIC_V5) {
                int fluidCount = in.readInt();
                checkCount(fluidCount, 1 << 22, "fluid queue");
                for (int i = 0; i < fluidCount; i++) {
                    int x = in.readInt();
                    int y = in.readInt();
                    int z = in.readInt();
                    String id = in.readUTF();
                    int priority = in.readByte();
                    long trigger = in.readLong();
                    int seq = in.readInt();
                    net.minecraft.world.level.material.Fluid fluid = WorldSnapshot.fluidOf(id);
                    if (fluid == null || priority < -3 || priority > 3) {
                        throw new IOException("bad fluid tick " + id + "/" + priority);
                    }
                    if ((x >> 9) != regionX || (z >> 9) != regionZ) {
                        throw new IOException("fluid tick outside sidecar region: " + x + "," + z);
                    }
                    fluids.add(new WorldSnapshot.QueuedFluidTickData(x, y, z, fluid, priority, trigger, seq));
                }
            }
            if (in.available() != 0) {
                throw new IOException("unexpected trailing sidecar bytes: " + in.available());
            }
            return new Decoded(regionX, regionZ, minY, height, gameTime,
                    dimension, sections, generatedChunks, bes, queue,
                    biomeWire, lightWire, fluids);
        }
    }

    private static void writeWireMap(
            DataOutputStream out, List<Map.Entry<Long, byte[]>> entries) throws IOException {
        out.writeInt(entries.size());
        for (Map.Entry<Long, byte[]> entry : entries) {
            byte[] payload = entry.getValue();
            if (payload == null || payload.length == 0 || payload.length > (1 << 22)) {
                throw new IOException("invalid wire metadata payload length");
            }
            out.writeLong(entry.getKey());
            out.writeInt(payload.length);
            out.write(payload);
        }
    }

    private static Map<Long, byte[]> readWireMap(
            DataInputStream in, int maxCount, String what) throws IOException {
        int count = in.readInt();
        checkCount(count, maxCount, what);
        Map<Long, byte[]> out = new HashMap<>();
        for (int i = 0; i < count; i++) {
            long key = in.readLong();
            int length = in.readInt();
            if (length <= 0 || length > (1 << 22)) {
                throw new IOException("bad sidecar " + what + " payload length " + length);
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            if (out.put(key, payload) != null) {
                throw new IOException("duplicate sidecar " + what + " key " + key);
            }
        }
        return Map.copyOf(out);
    }

    private static void checkCount(int n, int max, String what) throws IOException {
        if (n < 0 || n > max) {
            throw new IOException("bad sidecar " + what + " count " + n);
        }
    }

    private static long blockPosAsLong(int x, int y, int z) {
        return net.minecraft.core.BlockPos.asLong(x, y, z);
    }
}
