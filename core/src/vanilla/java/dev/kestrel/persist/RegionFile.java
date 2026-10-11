package dev.kestrel.persist;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Stage 2 minimal region-file container: the byte-level Anvil framing
 * vanilla's {@code RegionFile} defines, implemented over Kestrel's own
 * NBT bytes so the persist package stays dependency-free.
 *
 * <p>Framing, grounded in {@code RegionFile.java} (all line refs are the
 * 26.2 Vineflower decompile): 4096-byte sectors ({@code :28}); an 8192-byte
 * header holding 1024 int32 offsets then 1024 int32 timestamps
 * ({@code :43}, {@code :61-64}); chunk (x,z) maps to header index
 * {@code localX + localZ*32} ({@code :353-354}); each offset packs a
 * 3-byte sector number plus a 1-byte sector count ({@code :205-215});
 * each chunk payload is an int32 length, a 1-byte compression id, then
 * the compressed body ({@code :31}, {@code :129-135}). Kestrel writes
 * compression id 2 (zlib/deflate, vanilla's default:
 * {@code RegionFileVersion.java:45-46}) and reads ids 1 (gzip), 2
 * (deflate), and 3 (uncompressed), matching the ids vanilla assigns
 * ({@code RegionFileVersion.java:26-34}).
 *
 * <p>Deliberate subset: no external {@code .mcc} stub path (Kestrel
 * corpora never approach the 1 MiB threshold at
 * {@code RegionFile.java:36}); overwrite allocates fresh sectors at the
 * file end then frees the old ones, exactly like vanilla's commit path
 * ({@code :312-318}); corrupt header entries are zeroed on open like
 * vanilla's sanitizer ({@code :86-94}). Timestamps are written (current
 * epoch seconds) but never read.
 *
 * <p>Thread-safety: one thread at a time. The saver thread owns the
 * instance during a save; restore owns it during load. No locking.
 */
public final class RegionFile implements Closeable {

    /** Bytes per sector. */
    public static final int SECTOR_BYTES = 4096;

    /** Header sectors (offsets + timestamps). */
    static final int HEADER_SECTORS = 2;

    /** Compression ids Kestrel writes (deflate) and reads (gzip/deflate/none). */
    public static final int COMPRESSION_GZIP = 1;
    public static final int COMPRESSION_DEFLATE = 2;
    public static final int COMPRESSION_NONE = 3;

    private final RandomAccessFile file;
    private final int[] offsets = new int[1024];
    private final BitSet used = new BitSet();

    private RegionFile(RandomAccessFile file) {
        this.file = file;
    }

    /** Open (creating) a region file at the given path. */
    public static RegionFile open(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
        RegionFile region = new RegionFile(file);
        long len = file.length();
        if (len < HEADER_SECTORS * (long) SECTOR_BYTES) {
            file.setLength(HEADER_SECTORS * (long) SECTOR_BYTES);
            region.writeHeader();
        } else {
            byte[] header = new byte[HEADER_SECTORS * SECTOR_BYTES];
            file.seek(0);
            file.readFully(header);
            ByteBuffer buf = ByteBuffer.wrap(header);
            for (int i = 0; i < 1024; i++) {
                int packed = buf.getInt(i * 4);
                int sectors = packed & 0xFF;
                int sector = (packed >> 8) & 0xFFFFFF;
                // Vanilla's open-time sanitizer: zero entries that overlap
                // the header, claim zero sectors, or run past end of file.
                if ((packed != 0) && (sector < HEADER_SECTORS || sectors == 0
                        || (long) sector * SECTOR_BYTES > len)) {
                    packed = 0;
                }
                region.offsets[i] = packed;
                if (packed != 0) {
                    region.used.set(sector, sector + sectors);
                }
            }
            region.used.set(0, HEADER_SECTORS);
        }
        return region;
    }

    /** Header index for region-local chunk coords (each 0-31). */
    public static int index(int localX, int localZ) {
        return (localX & 31) + (localZ & 31) * 32;
    }

    /** True when the chunk slot holds a payload. */
    public boolean hasChunk(int localX, int localZ) {
        return offsets[index(localX, localZ)] != 0;
    }

    /**
     * Read one chunk payload: decompress per its compression id and
     * decode the NBT root. Returns null when the slot is empty.
     */
    public Nbt.Tag.TCompound readChunk(int localX, int localZ) throws IOException {
        int packed = offsets[index(localX, localZ)];
        if (packed == 0) {
            return null;
        }
        int sector = (packed >> 8) & 0xFFFFFF;
        int sectors = packed & 0xFF;
        byte[] raw = new byte[sectors * SECTOR_BYTES];
        synchronized (file) {
            file.seek((long) sector * SECTOR_BYTES);
            file.readFully(raw);
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int length = buf.getInt();
        int version = buf.get() & 0xFF;
        if (length <= 0 || length > raw.length - 4) {
            throw new IOException("bad chunk length " + length);
        }
        byte[] body = new byte[length - 1];
        System.arraycopy(raw, 5, body, 0, body.length);
        byte[] nbt = decompress(version, body);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(nbt))) {
            return Nbt.decodeRoot(in);
        }
    }

    /**
     * Write one chunk payload: NBT-encode, deflate, allocate fresh
     * sectors at file end, commit the header, free the old sectors.
     */
    public void writeChunk(int localX, int localZ, Nbt.Tag.TCompound tag) throws IOException {
        byte[] nbt = Nbt.encodeRoot(tag);
        byte[] body = compress(nbt);
        int total = body.length + 5;
        int sectors = (total + SECTOR_BYTES - 1) / SECTOR_BYTES;
        int slot = index(localX, localZ);
        int old = offsets[slot];
        int oldSector = (old >> 8) & 0xFFFFFF;
        int oldSectors = old & 0xFF;
        int sector = allocate(sectors);
        byte[] raw = new byte[sectors * SECTOR_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(raw);
        buf.putInt(body.length + 1);
        buf.put((byte) COMPRESSION_DEFLATE);
        System.arraycopy(body, 0, raw, 5, body.length);
        synchronized (file) {
            file.seek((long) sector * SECTOR_BYTES);
            file.write(raw);
        }
        offsets[slot] = (sector << 8) | sectors;
        writeHeader();
        if (old != 0) {
            used.clear(oldSector, oldSector + oldSectors);
        }
    }

    /** Flush and close the underlying file. */
    @Override
    public void close() throws IOException {
        synchronized (file) {
            file.getChannel().force(true);
            file.close();
        }
    }

    private int allocate(int sectors) throws IOException {
        int start = used.nextClearBit(HEADER_SECTORS);
        while (true) {
            int end = used.nextSetBit(start);
            if (end < 0 || end - start >= sectors) {
                used.set(start, start + sectors);
                long need = (long) (start + sectors) * SECTOR_BYTES;
                synchronized (file) {
                    if (file.length() < need) {
                        file.setLength(need);
                    }
                }
                return start;
            }
            start = used.nextClearBit(end);
        }
    }

    private void writeHeader() throws IOException {
        byte[] header = new byte[HEADER_SECTORS * SECTOR_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(header);
        for (int i = 0; i < 1024; i++) {
            buf.putInt(i * 4, offsets[i]);
        }
        int now = (int) (System.currentTimeMillis() / 1000L);
        for (int i = 0; i < 1024; i++) {
            if (offsets[i] != 0) {
                buf.putInt(SECTOR_BYTES + i * 4, now);
            }
        }
        synchronized (file) {
            file.seek(0);
            file.write(header);
        }
    }

    private static byte[] compress(byte[] in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(in.length / 2 + 16);
        try (DeflaterOutputStream zip = new DeflaterOutputStream(bytes)) {
            zip.write(in);
        }
        return bytes.toByteArray();
    }

    private static byte[] decompress(int version, byte[] body) throws IOException {
        return switch (version) {
            case COMPRESSION_NONE -> body.clone();
            case COMPRESSION_DEFLATE -> {
                try (InflaterInputStream in = new InflaterInputStream(
                        new ByteArrayInputStream(body))) {
                    yield in.readAllBytes();
                }
            }
            case COMPRESSION_GZIP -> {
                try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                        new ByteArrayInputStream(body))) {
                    yield in.readAllBytes();
                }
            }
            default -> throw new IOException("unknown compression id " + version);
        };
    }
}
