package dev.kestrel.harness;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tick redstone-wire power trace for the experimental-vs-default
 * differential (preresearch/redstone-experiments-eval.md, "Kestrel
 * consequences" item 4).
 *
 * Why this exists alongside {@link WorldStateHasher}: the tick hash covers
 * every block's numeric id, but the spec's comparison needs per-wire POWER
 * values (a), transient sequences (b), setBlock counts (c), notification
 * counts (d), and throw-or-not on the clock corpus (e). POWER is a
 * blockstate property, not a separate block id... in practice the id DOES
 * change with power (each power level is a distinct BlockState with its own
 * id), so the tick hash already sees power changes. But the hash cannot say
 * WHICH wire changed or count setBlock/neighborChanged calls - this tracer
 * records exactly that, in a greppable per-tick line format.
 *
 * What it logs (power-trace.log, next to ticks.log):
 * <pre>
 * # kestrel-harness power trace, schema_version=1
 * # tick_count,phase,wire_count,wires=x,y,z:power;...
 * 12,exit,57,-20,1,-4:15;-19,1,-4:15;...
 * </pre>
 * Coordinates are world coords. Only positions whose block is the vanilla
 * redstone wire block are listed (identity check against
 * {@code Blocks.REDSTONE_WIRE}, resolved once). POWER read via the real
 * {@code RedStoneWireBlock.POWER} IntegerProperty. Sorted by packed
 * (x,y,z) so the line is deterministic.
 *
 * Cost: one chunk-walk per tick over the same visible-chunk set the hasher
 * already walks (reuses its own handle resolution; does NOT share the
 * Handles instance - separate class, separate cache, no coupling). Only
 * logs the exit phase (post-tick settled state); enter-phase lines would
 * double the volume for no new information (enter(t+1) == exit(t) modulo
 * async chunk-gen, which the SkipTicks window already excludes).
 *
 * Deliberately reflection-only, same bootstrap-classloader constraint as
 * {@link WorldStateHasher} - ships in the boot jar alongside it.
 */
public final class PowerTrace {

    private static final int SCHEMA_VERSION = 1;


    private static volatile BufferedWriter writer;
    private static final AtomicLong SEQ = new AtomicLong();
    private static volatile Handles handles;
    private static volatile boolean headerWritten = false;

    private PowerTrace() {
    }

    public static synchronized void init(String tracePath) {
        try {
            writer = new BufferedWriter(new FileWriter(tracePath, /* append= */ false));
            writer.write("# kestrel-harness power trace, schema_version=" + SCHEMA_VERSION);
            writer.newLine();
            writer.write("# tick_count,phase,wire_count,wires=x,y,z:power;...");
            writer.newLine();
            writer.flush();
            headerWritten = true;
        } catch (IOException e) {
            throw new RuntimeException("kestrel-harness: could not open power-trace log at " + tracePath, e);
        }
    }

    static synchronized void setOutputPath(String path) {
        if (headerWritten) {
            return; // init() already ran with an explicit path; keep it
        }
        init(path);
    }

    /** Called from TickLogger.onTick for the exit phase only. Never throws. */
    public static void traceExit(Object server, int tickCount) {
        BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        String line;
        try {
            line = buildLine(server, tickCount);
        } catch (Throwable t) {
            System.err.println("[kestrel-harness] power trace failed at tick " + tickCount + ": " + t);
            return;
        }
        synchronized (PowerTrace.class) {
            try {
                w.write(line);
                w.newLine();
                w.flush();
            } catch (IOException e) {
                System.err.println("[kestrel-harness] power trace write failed: " + e);
            }
        }
    }

    // Discovered wire positions (world coords packed + level identity), found
    // by ONE full scan and re-validated periodically. Per-tick reads go
    // through Level.getBlockState (vanilla's own cached path, ~ns per call)
    // instead of re-scanning every chunk's sections every tick: the full
    // section scan stalled single ticks past the 60s watchdog on the
    // force-loaded volume (found 2026-09-10, three consecutive watchdog
    // crashes inside collectWires sectionGetBlockState).
    private static volatile List<long[]> discovered = null;
    private static volatile int discoveredAtTick = -1000000;
    private static volatile Object cachedOverworld = null;
    private static volatile Object cachedPos = null;

    private static String buildLine(Object server, int tickCount) throws Exception {
        Handles h = handles(server);
        List<long[]> wires = discovered;
        // Re-scan when empty (first run OR discovery raced the corpus
        // build: load.json fill runs seconds after tick 1, so the first
        // scans legitimately find nothing - keep re-scanning every tick
        // until wires appear) or every 512 ticks (catches layout changes;
        // the corpus has no pistons so positions are near-static, but a
        // stale list would blind the differential silently - periodic
        // re-discovery bounds that failure to one window).
        if (wires == null || wires.isEmpty() || tickCount - discoveredAtTick >= 512) {
            wires = fullScan(h, server);
            discovered = wires;
            discoveredAtTick = tickCount;
            if ((tickCount & 7) == 1 || wires.isEmpty()) {
                System.out.println("[kestrel-harness] powertrace scan tick=" + tickCount
                        + " holders=" + lastScanHolders + " chunks=" + lastScanChunks
                        + " wires=" + wires.size());
            }
        }

        // Cache the overworld + ONE reusable BlockPos across ticks (both
        // stable for the life of the server). Allocating per-wire-per-tick
        // BlockPos + re-resolving the level each tick cost ~200ms/exit-tick
        // on the 83-wire corpus (found 2026-09-10: avg 199ms, "Can't keep
        // up ... 886 ticks behind").
        if (cachedOverworld == null) {
            for (Object level : (Iterable<?>) h.getAllLevels.invoke(server)) {
                String key = h.dimension.invoke(level).toString();
                if (key.contains("overworld")) {
                    cachedOverworld = level;
                    break;
                }
            }
        }
        Object level = cachedOverworld;
        if (level == null) {
            return tickCount + ",exit,0,";
        }
        if (cachedPos == null) {
            cachedPos = h.mutablePosCtor.newInstance(0, 0, 0);
        }
        List<long[]> live = new ArrayList<>(wires.size());
        for (long[] wp : wires) {
            long packed = wp[0];
            int x = (int) (packed >> 38);
            int y = (int) ((packed >> 26) & 0xFFF) - 2048;
            int z = (int) (packed & 0x3FFFFFF);
            if ((z & 0x2000000) != 0) {
                z |= ~0x3FFFFFF;
            }
            h.mutableSet.invoke(cachedPos, x, y, z);
            Object state = h.levelGetBlockState.invoke(level, cachedPos);
            Object block = h.stateGetBlock.invoke(state);
            if (block != h.redstoneWireBlock) {
                // Wire removed since discovery (or discovery raced a build);
                // record power -1 so the line stays greppable and the removal
                // is visible instead of silently dropping the position.
                live.add(new long[]{packed, -1});
                continue;
            }
            Object powerVal = h.stateGetValue.invoke(state, h.powerProperty);
            live.add(new long[]{packed, ((Number) powerVal).intValue()});
        }

        live.sort(Comparator.comparingLong(a -> a[0]));
        StringBuilder sb = new StringBuilder();
        sb.append(tickCount).append(",exit,").append(live.size()).append(',');
        for (long[] wp : live) {
            long packed = wp[0];
            int x = (int) (packed >> 38);
            int y = (int) ((packed >> 26) & 0xFFF) - 2048;
            int z = (int) (packed & 0x3FFFFFF);
            // sign-extend 26-bit z
            if ((z & 0x2000000) != 0) {
                z |= ~0x3FFFFFF;
            }
            sb.append(x).append(',').append(y).append(',').append(z).append(':').append(wp[1]).append(';');
        }
        return sb.toString();
    }

    private static volatile int lastScanHolders = -1;
    private static volatile int lastScanChunks = -1;

    /** Full chunk scan for wire positions (runs rarely - see buildLine). */
    private static List<long[]> fullScan(Handles h, Object server) throws Exception {
        List<long[]> out = new ArrayList<>();
        int holders = 0;
        int chunks = 0;
        for (Object level : (Iterable<?>) h.getAllLevels.invoke(server)) {
            Object chunkSource = h.getChunkSource.invoke(level);
            Object chunkMap = h.chunkMapField.get(chunkSource);
            Object updating = h.updatingChunkMapField.get(chunkMap);
            for (Object fastEntry : (Iterable<?>) h.long2ObjectEntrySet.invoke(updating)) {
                // NO chunk-coord bbox: the corpus builds at world spawn,
                // which is seed-dependent and nowhere near 0,0 (a -4..4
                // bbox found 0 wires on seed 4242424242). Discovery runs
                // once per 512 ticks so scanning all holders is amortized.
                long key = (Long) h.getLongKey.invoke(fastEntry);
                int cx = (int) key;
                int cz = (int) (key >> 32);
                holders++;
                Object chunk = h.getChunkNow.invoke(chunkSource, cx, cz);
                if (chunk == null) {
                    continue;
                }
                chunks++;
                collectWires(h, (String) h.dimension.invoke(level).toString(), chunk, out);
            }
        }
        lastScanHolders = holders;
        lastScanChunks = chunks;
        return out;
    }

    /** Resolves the stored level identity back to the live level object. */
    private static Object levelFor(Handles h, Object server, long[] wp) throws Exception {
        // Level identity is the dimension key string (stored in wp? no -
        // positions are per-world unique in practice for the corpus: single
        // overworld volume. Keep the parameter for the multi-level future;
        // today every discovered wire is read from the overworld.)
        for (Object level : (Iterable<?>) h.getAllLevels.invoke(server)) {
            String key = h.dimension.invoke(level).toString();
            if (key.contains("overworld")) {
                return level;
            }
        }
        return null;
    }

    private static void collectWires(Handles h, String levelKey, Object chunk, List<long[]> out)
            throws Exception {
        Object pos = h.getPos.invoke(chunk);
        int cx = (Integer) h.chunkPosX.invoke(pos);
        int cz = (Integer) h.chunkPosZ.invoke(pos);
        int minY = (Integer) h.getMinSectionY.invoke(chunk);
        int maxY = (Integer) h.getMaxSectionY.invoke(chunk);

        // Section walk with hasOnlyAir skip (same rationale as
        // WorldStateHasher.hashChunk: the per-block reflective walk tripped
        // the server watchdog on force-loaded volumes). Only non-air
        // sections are scanned block-by-block for wires.
        Object[] sections = (Object[]) h.getSections.invoke(chunk);
        for (int si = minY; si <= maxY; si++) {
            int sectionY = si - minY;
            if (sectionY < 0 || sectionY >= sections.length) {
                continue;
            }
            Object section = sections[sectionY];
            if ((Boolean) h.sectionHasOnlyAir.invoke(section)) {
                continue;
            }
            int baseY = si << 4;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = cx * 16 + lx;
                    int wz = cz * 16 + lz;
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        Object state = h.sectionGetBlockState.invoke(section, lx, ly, lz);
                        Object block = h.stateGetBlock.invoke(state);
                        if (block != h.redstoneWireBlock) {
                            continue;
                        }
                        Object powerVal = h.stateGetValue.invoke(state, h.powerProperty);
                        int power = ((Number) powerVal).intValue();
                        long packed = (((long) wx) << 38) | (((long) (y + 2048) & 0xFFF) << 26) | ((long) wz & 0x3FFFFFF);
                        out.add(new long[]{packed, power});
                    }
                }
            }
        }
    }

    private static Handles handles(Object server) throws ReflectiveOperationException {
        Handles h = handles;
        if (h == null) {
            synchronized (PowerTrace.class) {
                h = handles;
                if (h == null) {
                    h = new Handles(server);
                    handles = h;
                }
            }
        }
        return h;
    }

    private static final class Handles {
        final Method getAllLevels;
        final Method dimension;
        final Method getChunkSource;
        final Field chunkMapField;
        final Field updatingChunkMapField;
        final Method long2ObjectEntrySet;
        final Method getLongKey;
        final Method getChunkNow;
        final Method getPos;
        final Method chunkPosX;
        final Method chunkPosZ;
        final Method getMinSectionY;
        final Method getMaxSectionY;
        final Method getSections;
        final Method sectionHasOnlyAir;
        final Method sectionGetBlockState;
        final Constructor<?> blockPosCtor;
        final Constructor<?> mutablePosCtor;
        final Method mutableSet;
        final Method levelGetBlockState;
        final Method stateGetBlock;
        final Method stateGetValue;
        final Object redstoneWireBlock;
        final Object powerProperty;

        Handles(Object server) throws ReflectiveOperationException {
            ClassLoader cl = server.getClass().getClassLoader();
            Class<?> serverClass = server.getClass();
            Class<?> levelClass = cls(cl, "net.minecraft.world.level.Level");
            Class<?> serverLevelClass = cls(cl, "net.minecraft.server.level.ServerLevel");
            Class<?> serverChunkCacheClass = cls(cl, "net.minecraft.server.level.ServerChunkCache");
            Class<?> chunkMapClass = cls(cl, "net.minecraft.server.level.ChunkMap");
            Class<?> levelChunkClass = cls(cl, "net.minecraft.world.level.chunk.LevelChunk");
            Class<?> chunkPosClass = cls(cl, "net.minecraft.world.level.ChunkPos");
            Class<?> blockPosClass = cls(cl, "net.minecraft.core.BlockPos");
            Class<?> blockStateBaseClass = cls(cl, "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
            Class<?> blockClass = cls(cl, "net.minecraft.world.level.block.Block");
            Class<?> blocksClass = cls(cl, "net.minecraft.world.level.block.Blocks");
            Class<?> redstoneWireClass = cls(cl, "net.minecraft.world.level.block.RedStoneWireBlock");
            Class<?> propertyClass = cls(cl, "net.minecraft.world.level.block.state.properties.Property");

            getAllLevels = method(serverClass, "getAllLevels");
            dimension = method(levelClass, "dimension");
            getChunkSource = method(serverLevelClass, "getChunkSource");
            chunkMapField = field(serverChunkCacheClass, "chunkMap");
            updatingChunkMapField = declaredField(chunkMapClass, "updatingChunkMap");
            // fastutil primitive-keyed map: NOT a java.util.Map, iterate via
            // long2ObjectEntrySet(), entries expose getLongKey()/getValue().
            Class<?> fastEntryClass = cls(cl, "it.unimi.dsi.fastutil.longs.Long2ObjectMap$Entry");
            long2ObjectEntrySet = method(updatingChunkMapField.getType(), "long2ObjectEntrySet");
            getLongKey = method(fastEntryClass, "getLongKey");
            getChunkNow = method(serverChunkCacheClass, "getChunkNow", int.class, int.class);
            getPos = method(levelChunkClass, "getPos");
            chunkPosX = method(chunkPosClass, "x");
            chunkPosZ = method(chunkPosClass, "z");
            Class<?> heightAccessorClass = cls(cl, "net.minecraft.world.level.LevelHeightAccessor");
            Class<?> chunkAccessClass = cls(cl, "net.minecraft.world.level.chunk.ChunkAccess");
            Class<?> sectionClass = cls(cl, "net.minecraft.world.level.chunk.LevelChunkSection");
            getMinSectionY = method(heightAccessorClass, "getMinSectionY");
            getMaxSectionY = method(heightAccessorClass, "getMaxSectionY");
            getSections = method(chunkAccessClass, "getSections");
            sectionHasOnlyAir = method(sectionClass, "hasOnlyAir");
            sectionGetBlockState = method(sectionClass, "getBlockState", int.class, int.class, int.class);
            blockPosCtor = blockPosClass.getConstructor(int.class, int.class, int.class);
            blockPosCtor.setAccessible(true);
            Class<?> mutablePosClass = cls(cl, "net.minecraft.core.BlockPos$MutableBlockPos");
            mutablePosCtor = mutablePosClass.getConstructor(int.class, int.class, int.class);
            mutablePosCtor.setAccessible(true);
            Class<?> mutableClass = cls(cl, "net.minecraft.core.BlockPos$MutableBlockPos");
            mutableSet = method(mutableClass, "set", int.class, int.class, int.class);
            // Level.getBlockState (BlockPos) - vanilla's own cached read path,
            // used for the per-tick discovered-position reads.
            levelGetBlockState = method(levelClass, "getBlockState", blockPosClass);
            // getBlock/getValue live on the BlockStateBase superclass, not the
            // BlockState subclass - resolve against the superclass explicitly.
            stateGetBlock = method(blockStateBaseClass, "getBlock");
            stateGetValue = method(blockStateBaseClass, "getValue", propertyClass);
            redstoneWireBlock = field(blocksClass, "REDSTONE_WIRE").get(null);
            powerProperty = field(redstoneWireClass, "POWER").get(null);
        }

        private static Class<?> cls(ClassLoader cl, String name) throws ClassNotFoundException {
            return Class.forName(name, true, cl);
        }

        private static Method method(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
            Method m = c.getMethod(name, params);
            m.setAccessible(true);
            return m;
        }

        private static Field field(Class<?> c, String name) throws NoSuchFieldException {
            Field f = c.getField(name);
            f.setAccessible(true);
            return f;
        }

        private static Field declaredField(Class<?> c, String name) throws NoSuchFieldException {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
    }
}
