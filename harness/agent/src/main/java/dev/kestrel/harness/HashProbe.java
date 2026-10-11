package dev.kestrel.harness;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-chunk block hash probe sink for Kestrel G2 differential testing.
 *
 * <p>Captures deterministic 64-bit FNV-1a block-state and primed heightmap hashes
 * at the three G2 cut points defined in {@code preresearch/g2-build-order.md}:
 * <ul>
 *   <li><b>P1</b>: NOISE complete (chained off {@code fillFromNoise}'s CompletableFuture completion)</li>
 *   <li><b>P2</b>: CARVERS exit (end of pure prefix)</li>
 *   <li><b>P3</b>: LIGHT/FULL exit (features and lighting complete)</li>
 * </ul>
 *
 * <p>Bootstrap-loaded (ships in {@code kestrel-harness-boot.jar} alongside {@link ChunkgenWatch}):
 * must stay dependency-free (standard library only) and public. All methods are thread-safe;
 * chunk-gen runs asynchronously across worker pools and worldgen threads.
 *
 * <p>Output: {@code hash-probe.csv} next to the tick log:
 * {@code chunk_x,chunk_z,cut,hash,blocks_hash}. {@code hash} is blocks plus
 * primed heightmaps; {@code blocks_hash} is the same accumulator stopped
 * before the heightmaps, which is what Kestrel's G2 step-2 import can
 * reproduce ({@code core/.../persist/BlockHash.java}, it keeps no
 * heightmaps). Rows written before this column existed have 4 fields.
 */
public final class HashProbe {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static final Object LOCK = new Object();
    private static volatile BufferedWriter out;
    private static volatile Handles handles;

    private static final ConcurrentMap<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private static final AtomicLong totalCaptures = new AtomicLong();

    private HashProbe() {
    }

    /**
     * Initializes the CSV sink writer. Called from {@link HarnessAgent#premain}.
     */
    public static void init(String csvPath) {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(csvPath, StandardCharsets.UTF_8));
            bw.write("chunk_x,chunk_z,cut,hash,blocks_hash");
            bw.newLine();
            bw.flush();
            synchronized (LOCK) {
                out = bw;
            }
            System.out.println("[kestrel-harness] HashProbe initialized, output: " + csvPath);
        } catch (Exception e) {
            System.err.println("[kestrel-harness] HashProbe disabled: " + e);
            synchronized (LOCK) {
                out = null;
            }
        }
    }

    /**
     * Records one capture row and flushes it immediately to {@code hash-probe.csv}.
     *
     * @param chunkPos packed chunk position (via {@code ChunkPos.pack})
     * @param cutPoint cut point identifier (e.g. "P1", "P2", "P3")
     * @param hashHex  16-character lowercase hexadecimal hash string
     * @param blocksHex same, blocks only (no heightmaps)
     */
    public static void record(long chunkPos, String cutPoint, String hashHex, String blocksHex) {
        if (out == null) {
            return;
        }
        int cx = (int) (chunkPos & 0xFFFFFFFFL);
        int cz = (int) (chunkPos >> 32);
        String line = cx + "," + cz + "," + cutPoint + "," + hashHex + "," + blocksHex;
        synchronized (LOCK) {
            if (out != null) {
                try {
                    out.write(line);
                    out.newLine();
                    out.flush();
                } catch (Exception e) {
                    System.err.println("[kestrel-harness] HashProbe write failed: " + e);
                }
            }
        }
        counts.computeIfAbsent(cutPoint, k -> new AtomicLong()).incrementAndGet();
        totalCaptures.incrementAndGet();
    }

    /**
     * Extracts chunk coordinates, computes the block+heightmap hash, and records the row.
     *
     * @param chunk    vanilla {@code ChunkAccess} instance
     * @param cutPoint cut point identifier ("P1", "P2", "P3")
     */
    public static void recordChunk(Object chunk, String cutPoint) {
        if (chunk == null) {
            return;
        }
        long pos = getChunkPos(chunk);
        long[] both = hashChunkBoth(chunk);
        record(pos, cutPoint, hex(both[0]), hex(both[1]));
    }

    /**
     * Hooks the asynchronous {@code fillFromNoise} future so the P1 hash is captured
     * upon actual noise generation completion, rather than method return.
     *
     * @param future CompletableFuture returned by {@code fillFromNoise}
     * @param chunk  ChunkAccess method argument (fallback / context)
     * @return chained CompletableFuture passing through the chunk
     */
    public static CompletableFuture<?> hookP1Future(CompletableFuture<?> future, Object chunk) {
        if (future == null) {
            if (chunk != null) {
                recordChunk(chunk, "P1");
            }
            return null;
        }
        return future.thenApply(result -> {
            try {
                Object target = result != null ? result : chunk;
                recordChunk(target, "P1");
            } catch (Throwable t) {
                System.err.println("[kestrel-harness] HashProbe P1 hook failed: " + t);
            }
            return result;
        });
    }

    /**
     * 1-argument overload for {@link #hookP1Future(CompletableFuture, Object)}.
     */
    public static CompletableFuture<?> hookP1Future(CompletableFuture<?> future) {
        return hookP1Future(future, null);
    }

    /**
     * Computes the 64-bit FNV-1a block-state + primed heightmaps hash of a chunk.
     */
    public static String hashChunk(Object chunk) {
        return hex(hashChunkBoth(chunk)[0]);
    }

    private static String hex(long v) {
        return String.format(java.util.Locale.ROOT, "%016x", v);
    }

    /**
     * {@code [blocks + heightmaps, blocks only]}; both 0 when the chunk is
     * null or reflection fails (same sentinel as before).
     */
    static long[] hashChunkBoth(Object chunk) {
        if (chunk == null) {
            return new long[2];
        }
        try {
            Handles h = handles(chunk);
            long acc = FNV_OFFSET;

            Object posObj = h.chunkGetPos.invoke(chunk);
            long packedPos = (Long) h.chunkPosPack.invoke(posObj);
            int cx = (int) (packedPos & 0xFFFFFFFFL);
            int cz = (int) (packedPos >> 32);

            int minY = (Integer) h.getMinSectionY.invoke(chunk);
            Object[] sections = (Object[]) h.chunkGetSections.invoke(chunk);

            if (sections != null) {
                for (int si = 0; si < sections.length; si++) {
                    Object section = sections[si];
                    if (section == null) {
                        continue;
                    }
                    int sectionY = minY + si;
                    if ((Boolean) h.sectionHasOnlyAir.invoke(section)) {
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
                                Object state = h.sectionGetBlockState.invoke(section, lx, ly, lz);
                                int id;
                                if (h.blockGetId != null) {
                                    id = (Integer) h.blockGetId.invoke(null, state);
                                } else {
                                    id = state != null ? state.toString().hashCode() : 0;
                                }
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

            long blocksOnly = acc;

            // Primed heightmap values (cheap unmodifiableSet view over internal EnumMap)
            if (h.chunkGetHeightmaps != null && h.heightmapGetRawData != null) {
                try {
                    Iterable<?> entries = (Iterable<?>) h.chunkGetHeightmaps.invoke(chunk);
                    if (entries != null) {
                        for (Object entryObj : entries) {
                            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObj;
                            Object key = entry.getKey();
                            Object hm = entry.getValue();
                            if (key != null && hm != null) {
                                acc = mixString(acc, key.toString());
                                long[] rawData = (long[]) h.heightmapGetRawData.invoke(hm);
                                if (rawData != null) {
                                    for (long l : rawData) {
                                        acc = mixLong(acc, l);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    // Heightmaps are best-effort; block hash remains intact
                }
            }

            return new long[] {acc, blocksOnly};
        } catch (Throwable t) {
            System.err.println("[kestrel-harness] HashProbe hashChunk failed: " + t);
            return new long[2];
        }
    }

    /**
     * Retrieves packed chunk pos via reflection.
     */
    public static long getChunkPos(Object chunk) {
        if (chunk == null) {
            return 0L;
        }
        try {
            Handles h = handles(chunk);
            Object pos = h.chunkGetPos.invoke(chunk);
            return (Long) h.chunkPosPack.invoke(pos);
        } catch (Throwable t) {
            System.err.println("[kestrel-harness] HashProbe getChunkPos failed: " + t);
            return 0L;
        }
    }

    /**
     * Summary string for log tail / inspection (mirrors ChunkgenWatch.summary()).
     */
    public static String summary() {
        StringBuilder sb = new StringBuilder("hash-probe captures (total=");
        sb.append(totalCaptures.get()).append("):");
        for (String cut : new String[] {"P1", "P2", "P3"}) {
            AtomicLong c = counts.get(cut);
            sb.append(' ').append(cut).append('=').append(c != null ? c.get() : 0);
        }
        for (Map.Entry<String, AtomicLong> entry : counts.entrySet()) {
            String k = entry.getKey();
            if (!"P1".equals(k) && !"P2".equals(k) && !"P3".equals(k)) {
                sb.append(' ').append(k).append('=').append(entry.getValue().get());
            }
        }
        return sb.toString();
    }

    private static Handles handles(Object chunk) throws ReflectiveOperationException {
        Handles h = handles;
        if (h == null) {
            synchronized (HashProbe.class) {
                h = handles;
                if (h == null) {
                    h = new Handles(chunk.getClass().getClassLoader());
                    handles = h;
                }
            }
        }
        return h;
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

    private static long mixString(long acc, String s) {
        if (s == null) {
            return acc;
        }
        for (int i = 0; i < s.length(); i++) {
            acc = mixInt(acc, s.charAt(i));
        }
        return acc;
    }

    static final class Handles {
        final Method chunkGetPos;
        final Method chunkPosPack;
        final Method chunkGetSections;
        final Method getMinSectionY;
        final Method sectionHasOnlyAir;
        final Method sectionGetBlockState;
        final Method blockGetId;
        final Method chunkGetHeightmaps;
        final Method heightmapGetRawData;

        Handles(ClassLoader cl) throws ReflectiveOperationException {
            Class<?> chunkAccessClass = cls(cl, "net.minecraft.world.level.chunk.ChunkAccess");
            Class<?> chunkPosClass = cls(cl, "net.minecraft.world.level.ChunkPos");
            Class<?> heightAccessorClass = cls(cl, "net.minecraft.world.level.LevelHeightAccessor");
            Class<?> sectionClass = cls(cl, "net.minecraft.world.level.chunk.LevelChunkSection");
            Class<?> blockClass = cls(cl, "net.minecraft.world.level.block.Block");
            Class<?> blockStateClass = cls(cl, "net.minecraft.world.level.block.state.BlockState");

            chunkGetPos = method(chunkAccessClass, "getPos");
            chunkPosPack = method(chunkPosClass, "pack");
            chunkGetSections = method(chunkAccessClass, "getSections");
            getMinSectionY = method(heightAccessorClass, "getMinSectionY");
            sectionHasOnlyAir = method(sectionClass, "hasOnlyAir");
            sectionGetBlockState = method(sectionClass, "getBlockState", int.class, int.class, int.class);
            blockGetId = method(blockClass, "getId", blockStateClass);

            Method getHm = null;
            Method getRaw = null;
            try {
                getHm = method(chunkAccessClass, "getHeightmaps");
                Class<?> hmClass = cls(cl, "net.minecraft.world.level.levelgen.Heightmap");
                getRaw = method(hmClass, "getRawData");
            } catch (Throwable ignored) {
            }
            chunkGetHeightmaps = getHm;
            heightmapGetRawData = getRaw;
        }

        private static Class<?> cls(ClassLoader cl, String name) throws ClassNotFoundException {
            return Class.forName(name, true, cl);
        }

        private static Method method(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
            Method m = c.getMethod(name, params);
            m.setAccessible(true);
            return m;
        }
    }
}
