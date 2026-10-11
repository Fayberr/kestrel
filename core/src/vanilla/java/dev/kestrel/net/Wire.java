package dev.kestrel.net;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Stage 3 wire primitives: VarInt framing, big-endian primitives,
 * compression, and the packet envelope. Dependency-free (no Netty):
 * the server speaks raw TCP with its own frame codec.
 *
 * <p>Framing, grounded in the 26.2 decompile: each packet on the wire
 * is {@code [VarInt length][length bytes]} where the body starts with
 * a {@code VarInt packet-id} then the payload
 * ({@code Varint21FrameDecoder.java:41-60},
 * {@code Varint21LengthFieldPrepender.java:13-23},
 * {@code IdDispatchCodec.java:25-27}). VarInt is 32-bit, max 5 bytes
 * ({@code VarInt.java:6}, {@code :25-49}).
 *
 * <p>Compression, grounded in {@code CompressionEncoder.java:18-41} and
 * {@code CompressionDecoder.java:26-36}: when a threshold is active,
 * each framed body is prefixed with a VarInt uncompressed length (0
 * means already-raw) and zlib-deflated when the uncompressed length is
 * at or above the threshold (strict {@code <} comparison: equal
 * compresses). The length frame covers the compressed form
 * ({@code Connection.java:558-569}).
 *
 * <p>Offline mode: no encryption (no {@code ServerboundKeyPacket}
 * handling ever runs: {@code ServerLoginPacketListenerImpl.java:173-237}
 * is skipped when {@code !usesAuthentication()}).
 *
 * <p>Thread-safety: a {@link Session} is owned by one I/O thread at a
 * time. The {@code write*} helpers are static and stateless.
 */
public final class Wire {

    /** 26.2 release network protocol version ({@code SharedConstants.java:22}). */
    public static final int PROTOCOL_26_2 = 776;

    /** Vanilla default compression threshold ({@code DedicatedServerProperties.java:108}). */
    public static final int DEFAULT_COMPRESSION_THRESHOLD = 256;

    /** Max string bytes Kestrel accepts (vanilla default 32767, {@code FriendlyByteBuf.java:73}). */
    public static final int MAX_STRING = 32767;

    /** Compression hard cap ({@code CompressionEncoder.java:20-21}). */
    public static final int MAX_COMPRESSED = 8_388_608;

    private Wire() {
    }

    // ---- VarInt ----

    /** Encode a 32-bit VarInt (unsigned shift: negatives take 5 bytes). */
    public static byte[] varInt(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(5);
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
        return out.toByteArray();
    }

    /** Decode a VarInt from buf at pos; returns [value, nextPos]. */
    public static int[] readVarInt(byte[] buf, int pos) throws IOException {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            if (pos >= buf.length) {
                throw new IOException("truncated varint");
            }
            int b = buf[pos++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new int[]{value, pos};
            }
            shift += 7;
        }
        throw new IOException("varint too big");
    }

    /** VarLong encode (for border packets). */
    public static byte[] varLong(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        while ((value & 0xFFFFFFFFFFFFFF80L) != 0) {
            out.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value & 0x7F);
        return out.toByteArray();
    }

    // ---- buffer writer (big-endian, Netty order) ----

    /** Growing big-endian writer mirroring FriendlyByteBuf write order. */
    public static final class Writer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(bytes);

        public Writer vint(int v) {
            write(Wire.varInt(v));
            return this;
        }

        public Writer vlong(long v) {
            write(Wire.varLong(v));
            return this;
        }

        public Writer bool(boolean v) {
            bytes.write(v ? 1 : 0);
            return this;
        }

        public Writer b(int v) {
            bytes.write(v & 0xFF);
            return this;
        }

        public Writer s(int v) {
            try {
                out.writeShort(v);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        public Writer i(int v) {
            try {
                out.writeInt(v);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        public Writer l(long v) {
            try {
                out.writeLong(v);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        public Writer f(float v) {
            try {
                out.writeFloat(v);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        public Writer d(double v) {
            try {
                out.writeDouble(v);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        public Writer utf(String s) {
            byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            vint(raw.length);
            write(raw);
            return this;
        }

        public Writer bytes(byte[] raw) {
            write(raw);
            return this;
        }

        public Writer byteArray(byte[] raw) {
            vint(raw.length);
            write(raw);
            return this;
        }

        public Writer uuid(UUID id) {
            l(id.getMostSignificantBits());
            l(id.getLeastSignificantBits());
            return this;
        }

        /**
         * Vanilla BitSet: VarInt long-count + big-endian longs
         * ({@code FriendlyByteBuf.writeBitSet} -> writeLongArray).
         * An empty set is a single zero byte.
         */
        public Writer bitSet(java.util.BitSet v) {
            long[] longs = v.toLongArray();
            vint(longs.length);
            for (long lv : longs) {
                l(lv);
            }
            return this;
        }

        private void write(byte[] raw) {
            try {
                out.write(raw);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        public byte[] toBytes() {
            return bytes.toByteArray();
        }
    }

    // ---- buffer reader ----

    /** Cursor reader over a packet payload (id already consumed). */
    public static final class Reader {
        private final byte[] buf;
        private int pos;

        public Reader(byte[] payload) {
            this.buf = payload;
        }

        public int varInt() throws IOException {
            int[] r = readVarInt(buf, pos);
            pos = r[1];
            return r[0];
        }

        public boolean bool() throws IOException {
            need(1);
            return buf[pos++] != 0;
        }

        public int b() throws IOException {
            need(1);
            return buf[pos++] & 0xFF;
        }

        public int s() throws IOException {
            need(2);
            int v = ByteBuffer.wrap(buf, pos, 2).getShort();
            pos += 2;
            return v;
        }

        public int unsignedShort() throws IOException {
            return s() & 0xFFFF;
        }

        public int i() throws IOException {
            need(4);
            int v = ByteBuffer.wrap(buf, pos, 4).getInt();
            pos += 4;
            return v;
        }

        public long l() throws IOException {
            need(8);
            long v = ByteBuffer.wrap(buf, pos, 8).getLong();
            pos += 8;
            return v;
        }

        public float f() throws IOException {
            need(4);
            float v = ByteBuffer.wrap(buf, pos, 4).getFloat();
            pos += 4;
            return v;
        }

        public double d() throws IOException {
            need(8);
            double v = ByteBuffer.wrap(buf, pos, 8).getDouble();
            pos += 8;
            return v;
        }

        public String utf() throws IOException {
            return utf(MAX_STRING);
        }

        public String utf(int max) throws IOException {
            int len = varInt();
            if (len < 0 || len > max * 4) {
                throw new IOException("bad string length " + len);
            }
            need(len);
            String s = new String(buf, pos, len, StandardCharsets.UTF_8);
            pos += len;
            if (s.length() > max) {
                throw new IOException("string too long");
            }
            return s;
        }

        public UUID uuid() throws IOException {
            return new UUID(l(), l());
        }

        public byte[] byteArray() throws IOException {
            int len = varInt();
            if (len < 0 || len > MAX_COMPRESSED) {
                throw new IOException("bad byte array length " + len);
            }
            need(len);
            byte[] out = new byte[len];
            System.arraycopy(buf, pos, out, 0, len);
            pos += len;
            return out;
        }

        public boolean hasMore() {
            return pos < buf.length;
        }

        /** Bytes read so far, so a caller can assert it consumed the whole payload. */
        public int consumed() {
            return pos;
        }

        private void need(int n) throws IOException {
            if (pos + n > buf.length) {
                throw new IOException("truncated packet");
            }
            if (n == 2) {
                // short peek handled by caller advance
            }
        }
    }

    // ---- packets ----

    /** One decoded inbound packet: numeric id plus payload reader. */
    public record Inbound(int id, byte[] payload) {
        public Reader reader() {
            return new Reader(payload);
        }
    }

    /**
     * Frame one outbound packet: {@code [VarInt length][VarInt id][payload]},
     * with the compression envelope when a threshold is active.
     */
    public static byte[] frame(int id, byte[] payload, int threshold) throws IOException {
        byte[] body = concat(varInt(id), payload);
        byte[] framed;
        if (threshold >= 0) {
            if (body.length > MAX_COMPRESSED) {
                throw new IOException("packet too large: " + body.length);
            }
            if (body.length < threshold) {
                framed = concat(new byte[]{0}, body);
            } else {
                framed = concat(varInt(body.length), zlibCompress(body));
            }
        } else {
            framed = body;
        }
        return concat(varInt(framed.length), framed);
    }

    /**
     * Strip the compression envelope off a framed body (length already
     * consumed): reads the uncompressed-length prefix when compression
     * is active, inflates when nonzero.
     */
    public static byte[] unframe(byte[] body, int threshold) throws IOException {
        if (threshold < 0) {
            return body;
        }
        int[] r = readVarInt(body, 0);
        int declared = r[0];
        byte[] rest = slice(body, r[1]);
        if (declared == 0) {
            return rest;
        }
        if (declared < 0 || declared > MAX_COMPRESSED) {
            throw new IOException("bad declared length " + declared);
        }
        byte[] out = zlibInflate(rest, declared);
        if (out.length != declared) {
            throw new IOException("decompressed size mismatch");
        }
        return out;
    }

    /** Split a de-enveloped body into id + payload. */
    public static Inbound decode(byte[] body) throws IOException {
        int[] r = readVarInt(body, 0);
        return new Inbound(r[0], slice(body, r[1]));
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] slice(byte[] a, int from) {
        byte[] out = new byte[a.length - from];
        System.arraycopy(a, from, out, 0, out.length);
        return out;
    }

    static byte[] zlibCompress(byte[] in) throws IOException {
        Deflater def = new Deflater();
        def.setInput(in);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length / 2 + 16);
        byte[] tmp = new byte[8192];
        while (!def.finished()) {
            int n = def.deflate(tmp);
            out.write(tmp, 0, n);
        }
        def.end();
        return out.toByteArray();
    }

    /**
     * Inflate at most {@code expect + 1} bytes: one byte past the
     * declared length is enough for the caller's size check, so a ratio
     * bomb (tiny input, huge output) costs no more than its declared
     * length. The buffer grows with real output instead of trusting the
     * declared size up front. A stream stalled on a preset dictionary
     * (FDICT) or on missing input ends the loop instead of spinning.
     */
    static byte[] zlibInflate(byte[] in, int expect) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(expect, 1 << 16));
        byte[] tmp = new byte[8192];
        try {
            while (!inf.finished() && out.size() <= expect) {
                int n = inf.inflate(tmp, 0, Math.min(tmp.length, expect + 1 - out.size()));
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                out.write(tmp, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("bad compressed data", e);
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    /** Offline-mode UUID for a name ({@code UUIDUtil.java:90-93}). */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Login disconnect payload: JSON text component as a VarInt-prefixed
     * UTF-8 string ({@code ClientboundLoginDisconnectPacket.java:18-21}).
     * JSON-escapes the reason; the client parses leniently (plain string
     * or component object both accepted).
     */
    public static byte[] disconnectJson(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 2);
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return new Writer().utf(sb.toString()).toBytes();
    }

    /** Minimal NBT StringTag for a plain-text disconnect reason. */
    public static byte[] disconnectNbt(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + raw.length);
        buf.put((byte) 8);
        buf.putShort((short) raw.length);
        buf.put(raw);
        return buf.array();
    }
}
