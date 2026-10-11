package dev.kestrel.persist;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2 minimal NBT writer/reader: the exact tag shapes Kestrel's Anvil
 * writer emits and its reader accepts. Big-endian, TAG_End-terminated
 * compounds, Java-modified-UTF8 strings, unnamed root compound.
 *
 * <p>Grounded in the vanilla NBT framing ({@code NbtIo.java},
 * decompile: length-prefixed strings, explicit type ids, root is an
 * unnamed compound): the byte layout below is written so vanilla's
 * {@code RegionFileStorage.read} (which calls {@code NbtIo.read}) parses
 * Kestrel-written chunks. Tag ids: End 0, Byte 1, Short 2, Int 3, Long 4,
 * Float 5, Double 6, ByteArray 7, String 8, List 9, Compound 10, IntArray
 * 11, LongArray 12.
 *
 * <p>Scope: writer covers Byte, Short, Int, Long, Float, Double,
 * String, ByteArray, IntArray, LongArray, List, Compound. Reader covers
 * the same set. Float/Double exist for the registry path (datapack
 * JSON numbers); chunk code never emits them. ShortArray tags are
 * rejected on read: Kestrel never writes them, and a chunk that
 * contains them did not come from this writer.
 *
 * <p>Thread-safety: the tag tree is plain data, confined to one thread
 * at a time like the rest of the loop package. No synchronization.
 */
public final class Nbt {

    /** Tag type ids, matching vanilla's NBT spec order. */
    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    /** Sealed tag family: one record per type this writer emits. */
    public sealed interface Tag permits Tag.TByte, Tag.TShort, Tag.TInt, Tag.TLong,
            Tag.TFloat, Tag.TDouble,
            Tag.TString, Tag.TByteArray, Tag.TIntArray, Tag.TLongArray,
            Tag.TList, Tag.TCompound {
        int id();

        record TByte(byte value) implements Tag {
            public int id() {
                return TAG_BYTE;
            }
        }

        record TShort(short value) implements Tag {
            public int id() {
                return TAG_SHORT;
            }
        }

        record TInt(int value) implements Tag {
            public int id() {
                return TAG_INT;
            }
        }

        record TLong(long value) implements Tag {
            public int id() {
                return TAG_LONG;
            }
        }

        record TFloat(float value) implements Tag {
            public int id() {
                return TAG_FLOAT;
            }
        }

        record TDouble(double value) implements Tag {
            public int id() {
                return TAG_DOUBLE;
            }
        }

        record TString(String value) implements Tag {
            public int id() {
                return TAG_STRING;
            }
        }

        record TByteArray(byte[] value) implements Tag {
            public int id() {
                return TAG_BYTE_ARRAY;
            }
        }

        record TIntArray(int[] value) implements Tag {
            public int id() {
                return TAG_INT_ARRAY;
            }
        }

        record TLongArray(long[] value) implements Tag {
            public int id() {
                return TAG_LONG_ARRAY;
            }
        }

        record TList(int elementId, List<Tag> items) implements Tag {
            public int id() {
                return TAG_LIST;
            }
        }

        record TCompound(Map<String, Tag> entries) implements Tag {
            public int id() {
                return TAG_COMPOUND;
            }
        }
    }

    private Nbt() {
    }

    // ---- builders ----

    public static Tag.TCompound compound() {
        return new Tag.TCompound(new LinkedHashMap<>());
    }

    public static void put(Tag.TCompound c, String name, Tag t) {
        c.entries().put(name, t);
    }

    public static void putByte(Tag.TCompound c, String name, byte v) {
        put(c, name, new Tag.TByte(v));
    }

    public static void putShort(Tag.TCompound c, String name, short v) {
        put(c, name, new Tag.TShort(v));
    }

    public static void putInt(Tag.TCompound c, String name, int v) {
        put(c, name, new Tag.TInt(v));
    }

    public static void putLong(Tag.TCompound c, String name, long v) {
        put(c, name, new Tag.TLong(v));
    }

    public static void putFloat(Tag.TCompound c, String name, float v) {
        put(c, name, new Tag.TFloat(v));
    }

    public static void putDouble(Tag.TCompound c, String name, double v) {
        put(c, name, new Tag.TDouble(v));
    }

    public static void putString(Tag.TCompound c, String name, String v) {
        put(c, name, new Tag.TString(v));
    }

    public static void putByteArray(Tag.TCompound c, String name, byte[] v) {
        put(c, name, new Tag.TByteArray(v.clone()));
    }

    public static void putIntArray(Tag.TCompound c, String name, int[] v) {
        put(c, name, new Tag.TIntArray(v.clone()));
    }

    public static void putLongArray(Tag.TCompound c, String name, long[] v) {
        put(c, name, new Tag.TLongArray(v.clone()));
    }

    public static void putBool(Tag.TCompound c, String name, boolean v) {
        putByte(c, name, (byte) (v ? 1 : 0));
    }

    public static Tag.TList strList(List<String> items) {
        List<Tag> tags = new ArrayList<>(items.size());
        for (String s : items) {
            tags.add(new Tag.TString(s));
        }
        return new Tag.TList(TAG_STRING, tags);
    }

    // ---- typed readers (throw NbtException on missing/wrong type) ----

    /** NBT shape mismatch on read. */
    public static final class NbtException extends IOException {
        NbtException(String message) {
            super(message);
        }
    }

    public static Tag get(Tag.TCompound c, String name) throws NbtException {
        Tag t = c.entries().get(name);
        if (t == null) {
            throw new NbtException("missing tag: " + name);
        }
        return t;
    }

    public static Tag getOrNull(Tag.TCompound c, String name) {
        return c.entries().get(name);
    }

    private static Tag expect(Tag.TCompound c, String name, int id) throws NbtException {
        Tag t = get(c, name);
        if (t.id() != id) {
            throw new NbtException("tag " + name + " is type " + t.id() + ", expected " + id);
        }
        return t;
    }

    public static byte getByte(Tag.TCompound c, String name) throws NbtException {
        return ((Tag.TByte) expect(c, name, TAG_BYTE)).value();
    }

    public static short getShort(Tag.TCompound c, String name) throws NbtException {
        return ((Tag.TShort) expect(c, name, TAG_SHORT)).value();
    }

    public static int getInt(Tag.TCompound c, String name) throws NbtException {
        return ((Tag.TInt) expect(c, name, TAG_INT)).value();
    }

    public static long getLong(Tag.TCompound c, String name) throws NbtException {
        return ((Tag.TLong) expect(c, name, TAG_LONG)).value();
    }

    public static String getString(Tag.TCompound c, String name) throws NbtException {
        return ((Tag.TString) expect(c, name, TAG_STRING)).value();
    }

    public static byte[] getByteArray(Tag.TCompound c, String name) throws NbtException {
        return ((Tag.TByteArray) expect(c, name, TAG_BYTE_ARRAY)).value().clone();
    }

    public static int[] getIntArray(Tag.TCompound c, String name) throws NbtException {
        return ((Tag.TIntArray) expect(c, name, TAG_INT_ARRAY)).value().clone();
    }

    public static long[] getLongArray(Tag.TCompound c, String name) throws NbtException {
        return ((Tag.TLongArray) expect(c, name, TAG_LONG_ARRAY)).value().clone();
    }

    public static boolean getBool(Tag.TCompound c, String name) throws NbtException {
        return getByte(c, name) != 0;
    }

    public static Tag.TCompound getCompound(Tag.TCompound c, String name) throws NbtException {
        return (Tag.TCompound) expect(c, name, TAG_COMPOUND);
    }

    public static Tag.TList getList(Tag.TCompound c, String name) throws NbtException {
        return (Tag.TList) expect(c, name, TAG_LIST);
    }

    public static byte getByteOr(Tag.TCompound c, String name, byte def) throws NbtException {
        Tag t = getOrNull(c, name);
        if (t == null) {
            return def;
        }
        if (t.id() != TAG_BYTE) {
            throw new NbtException("tag " + name + " is type " + t.id() + ", expected " + TAG_BYTE);
        }
        return ((Tag.TByte) t).value();
    }

    public static int getIntOr(Tag.TCompound c, String name, int def) throws NbtException {
        Tag t = getOrNull(c, name);
        if (t == null) {
            return def;
        }
        if (t.id() != TAG_INT) {
            throw new NbtException("tag " + name + " is type " + t.id() + ", expected " + TAG_INT);
        }
        return ((Tag.TInt) t).value();
    }

    public static long getLongOr(Tag.TCompound c, String name, long def) throws NbtException {
        Tag t = getOrNull(c, name);
        if (t == null) {
            return def;
        }
        if (t.id() != TAG_LONG) {
            throw new NbtException("tag " + name + " is type " + t.id() + ", expected " + TAG_LONG);
        }
        return ((Tag.TLong) t).value();
    }

    public static boolean getBoolOr(Tag.TCompound c, String name, boolean def) throws NbtException {
        Tag t = getOrNull(c, name);
        if (t == null) {
            return def;
        }
        if (t.id() != TAG_BYTE) {
            throw new NbtException("tag " + name + " is type " + t.id() + ", expected " + TAG_BYTE);
        }
        return ((Tag.TByte) t).value() != 0;
    }

    // ---- binary encode/decode ----

    /** Encode one unnamed root compound to bytes (no compression here). */
    public static byte[] encodeRoot(Tag.TCompound root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(TAG_COMPOUND);
        out.writeUTF("");
        writeCompoundBody(root, out);
        out.flush();
        return bytes.toByteArray();
    }

    /**
     * Encode one network entry value: the NBT any-tag framing used on
     * the wire ({@code NbtIo.java:160-165}): single type-id byte, then
     * the payload with NO name ({@code FriendlyByteBuf.java:528-538}
     * writes entries via {@code writeNbt}).
     */
    public static byte[] encodeAnyTag(Tag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(tag.id());
        writePayload(tag, out);
        out.flush();
        return bytes.toByteArray();
    }

    private static void writeTag(String name, Tag tag, DataOutput out) throws IOException {
        out.writeByte(tag.id());
        out.writeUTF(name);
        writePayload(tag, out);
    }

    private static void writePayload(Tag tag, DataOutput out) throws IOException {
        switch (tag) {
            case Tag.TByte b -> out.writeByte(b.value());
            case Tag.TShort s -> out.writeShort(s.value());
            case Tag.TInt i -> out.writeInt(i.value());
            case Tag.TLong l -> out.writeLong(l.value());
            case Tag.TFloat f -> out.writeFloat(f.value());
            case Tag.TDouble d -> out.writeDouble(d.value());
            case Tag.TString s -> out.writeUTF(s.value());
            case Tag.TByteArray a -> {
                out.writeInt(a.value().length);
                out.write(a.value());
            }
            case Tag.TIntArray a -> {
                out.writeInt(a.value().length);
                for (int v : a.value()) {
                    out.writeInt(v);
                }
            }
            case Tag.TLongArray a -> {
                out.writeInt(a.value().length);
                for (long v : a.value()) {
                    out.writeLong(v);
                }
            }
            case Tag.TList l -> {
                out.writeByte(l.elementId());
                out.writeInt(l.items().size());
                for (Tag item : l.items()) {
                    if (item.id() != l.elementId()) {
                        throw new IOException("heterogeneous list: item " + item.id()
                                + " in list of " + l.elementId());
                    }
                    writePayload(item, out);
                }
            }
            case Tag.TCompound c -> writeCompoundBody(c, out);
        }
    }

    private static void writeCompoundBody(Tag.TCompound c, DataOutput out) throws IOException {
        for (Map.Entry<String, Tag> e : c.entries().entrySet()) {
            writeTag(e.getKey(), e.getValue(), out);
        }
        out.writeByte(TAG_END);
    }

    /** Decode one unnamed root compound from the stream. */
    public static Tag.TCompound decodeRoot(DataInput in) throws IOException {
        int id = in.readUnsignedByte();
        if (id != TAG_COMPOUND) {
            throw new NbtException("root tag is type " + id + ", expected compound");
        }
        in.readUTF(); // root name, ignored
        return readCompoundBody(in);
    }

    private static Tag readPayload(int id, DataInput in) throws IOException {
        return switch (id) {
            case TAG_BYTE -> new Tag.TByte(in.readByte());
            case TAG_SHORT -> new Tag.TShort(in.readShort());
            case TAG_INT -> new Tag.TInt(in.readInt());
            case TAG_LONG -> new Tag.TLong(in.readLong());
            case TAG_FLOAT -> new Tag.TFloat(in.readFloat());
            case TAG_DOUBLE -> new Tag.TDouble(in.readDouble());
            case TAG_STRING -> new Tag.TString(in.readUTF());
            case TAG_BYTE_ARRAY -> {
                int n = in.readInt();
                checkArray(n, 1 << 24, "byte array");
                byte[] v = new byte[n];
                in.readFully(v);
                yield new Tag.TByteArray(v);
            }
            case TAG_INT_ARRAY -> {
                int n = in.readInt();
                checkArray(n, 1 << 24, "int array");
                int[] v = new int[n];
                for (int i = 0; i < n; i++) {
                    v[i] = in.readInt();
                }
                yield new Tag.TIntArray(v);
            }
            case TAG_LONG_ARRAY -> {
                int n = in.readInt();
                checkArray(n, 1 << 24, "long array");
                long[] v = new long[n];
                for (int i = 0; i < n; i++) {
                    v[i] = in.readLong();
                }
                yield new Tag.TLongArray(v);
            }
            case TAG_LIST -> {
                int elem = in.readUnsignedByte();
                int n = in.readInt();
                checkArray(n, 1 << 20, "list");
                List<Tag> items = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) {
                    items.add(readPayload(elem, in));
                }
                yield new Tag.TList(elem, items);
            }
            case TAG_COMPOUND -> readCompoundBody(in);
            default -> throw new NbtException("unsupported tag id " + id);
        };
    }

    private static Tag.TCompound readCompoundBody(DataInput in) throws IOException {
        Map<String, Tag> entries = new LinkedHashMap<>();
        while (true) {
            int id = in.readUnsignedByte();
            if (id == TAG_END) {
                return new Tag.TCompound(entries);
            }
            String name = in.readUTF();
            entries.put(name, readPayload(id, in));
        }
    }

    private static void checkArray(int n, int max, String what) throws NbtException {
        if (n < 0 || n > max) {
            throw new NbtException("bad " + what + " length " + n);
        }
    }
}
