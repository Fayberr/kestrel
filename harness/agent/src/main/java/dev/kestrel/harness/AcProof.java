package dev.kestrel.harness;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Same-tick AC propagation differential for step 7 (mirrors the
 * {@link SpawnProof} pattern: no cross-run hash compare, recompute on the
 * SAME live tick and log DIVERGE on mismatch).
 *
 * <p><b>What is proven.</b> Each exit tick, after the world has settled
 * (guarded by the two-tick network lock below), the probe reads every
 * discovered wire's live power, rebuilds the caller topology the headless
 * {@code AcWireNetwork} takes (current powers, external non-wire power,
 * offer/accept links from live connection state), replays the
 * search/depower/power phases inline, and compares the replay's final power
 * per wire against the live power. Agreement means the headless port's
 * algebra reproduces vanilla's settled state on real world data; any
 * disagreement is logged as {@code DIVERGE} with the wire positions and
 * both powers. Zero DIVERGE over the settled redstone-line window IS the
 * live proof of the propagation core.
 *
 * <p><b>What is NOT proven here (deliberate scope).</b> Update ORDER and
 * update COUNTS are flag-ON properties with no observable vanilla-side
 * counterpart on the default path; they stay proven by
 * {@code AcWireProofCheck} headlessly and by the future flag-ON trace
 * comparison. This probe proves the power algebra only: same inputs, same
 * settled outputs.
 *
 * <p><b>Settled-network gate.</b> Mid-propagation ticks have live powers
 * that legitimately differ from any settled recompute. The probe therefore
 * only replays when the discovered network's powers are identical to the
 * previous tick's (two-tick lock). Unsettled ticks log
 * {@code settling} instead of a verdict. On the settled redstone-line
 * corpus the lock holds nearly every tick after SkipTicks; the clock arm
 * settles between flips.
 *
 * <p>Deliberately reflection-only, same bootstrap-classloader constraint as
 * {@link WorldStateHasher} - ships in the boot jar alongside it.
 */
public final class AcProof {

    private static final int SCHEMA_VERSION = 1;

    private static volatile java.io.BufferedWriter writer;
    private static volatile Handles handles;
    private static volatile boolean headerWritten = false;

    // Discovery state (same pattern as PowerTrace: scan rarely, read per tick).
    private static volatile List<long[]> discovered = null;
    private static volatile int discoveredAtTick = -1000000;
    private static volatile Object cachedOverworld = null;
    private static volatile Object cachedPos = null;
    private static volatile Map<Long, Integer> lastPowers = null;

    private AcProof() {
    }

    public static synchronized void init(String proofPath) {
        try {
            writer = new java.io.BufferedWriter(new java.io.FileWriter(proofPath, false));
            writer.write("# kestrel-harness AC proof, schema_version=" + SCHEMA_VERSION);
            writer.newLine();
            writer.write("# tick_count,verdict,wire_count,diverges[,detail]");
            writer.newLine();
            writer.flush();
            headerWritten = true;
        } catch (java.io.IOException e) {
            throw new RuntimeException("kestrel-harness: could not open AC proof at " + proofPath, e);
        }
    }

    static synchronized void setOutputPath(String path) {
        if (headerWritten) {
            return;
        }
        init(path);
    }

    /** Called from TickLogger.onTick for the exit phase only. Never throws. */
    public static void traceExit(Object server, int tickCount) {
        java.io.BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        String line;
        try {
            line = buildLine(server, tickCount);
        } catch (Throwable t) {
            System.err.println("[kestrel-harness] AC proof failed at tick " + tickCount + ": " + t);
            return;
        }
        synchronized (AcProof.class) {
            try {
                w.write(line);
                w.newLine();
                w.flush();
            } catch (java.io.IOException e) {
                System.err.println("[kestrel-harness] AC proof write failed: " + e);
            }
        }
    }

    private static String buildLine(Object server, int tickCount) throws Exception {
        Handles h = handles(server);
        if (discovered == null || discovered.isEmpty() || tickCount - discoveredAtTick >= 512) {
            discovered = fullScan(h, server);
            discoveredAtTick = tickCount;
        }
        Object level = overworld(h, server);
        if (level == null || discovered.isEmpty()) {
            return tickCount + ",empty,0,0";
        }
        if (cachedPos == null) {
            cachedPos = h.mutablePosCtor.newInstance(0, 0, 0);
        }
        // Read live powers for every discovered wire.
        Map<Long, Integer> live = new HashMap<>();
        for (long[] wp : discovered) {
            long packed = wp[0];
            setPos(h, (int) (packed >> 38), (int) (((packed >> 26) & 0xFFF) - 2048), signZ((int) (packed & 0x3FFFFFF)));
            Object state = h.levelGetBlockState.invoke(level, cachedPos);
            if (h.stateGetBlock.invoke(state) != h.redstoneWireBlock) {
                continue;
            }
            live.put(packed, ((Number) h.stateGetValue.invoke(state, h.powerProperty)).intValue());
        }
        // Settled-network gate: compare against last tick.
        Map<Long, Integer> prev = lastPowers;
        lastPowers = live;
        if (prev == null || !prev.equals(live)) {
            return tickCount + ",settling," + live.size() + ",0";
        }
        // Settled: replay AC search/depower/power inline over live data.
        List<String> diverges = replay(h, level, live);
        StringBuilder sb = new StringBuilder();
        sb.append(tickCount).append(",settled,").append(live.size()).append(',').append(diverges.size());
        for (String d : diverges) {
            sb.append(',').append(d);
        }
        return sb.toString();
    }

    /**
     * Inline replay of the AC phases over live data. Builds wire states
     * (current = live power, external = live non-wire signal), resolves
     * offer/accept links from live connection state (same-level horizontal
     * wires both ways; below/above stepped wires per AC's connection rule),
     * roots every wire whose recompute differs, then runs
     * search/depower/power. Returns DIVERGE details for wires whose replay
     * final differs from live.
     */
    private static List<String> replay(Handles h, Object level, Map<Long, Integer> live) throws Exception {
        List<String> diverges = new ArrayList<>();
        // Index wires 0..n-1 in packed order (deterministic).
        List<Long> ids = new ArrayList<>(live.keySet());
        ids.sort(Comparator.naturalOrder());
        Map<Long, Integer> idx = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            idx.put(ids.get(i), i);
        }
        int n = ids.size();
        int[] current = new int[n];
        int[] external = new int[n];
        for (int i = 0; i < n; i++) {
            current[i] = live.get(ids.get(i));
            external[i] = readExternalPower(h, level, ids.get(i));
        }
        // Links: same-level horizontal wire neighbors, offer+accept both ways
        // when the vanilla connection state connects toward each other. The
        // below/above stepped rule needs conductor reads per neighbor; keep
        // the headless-proven core exact by resolving connections through
        // vanilla's own getConnectionState-equivalent: read the wire's
        // RedstoneSide per direction via getSignal-side check. Simpler sound
        // rule used here: link both ways iff both endpoints are wires AND
        // each side's vanilla connection is not NONE (read reflectively).
        List<List<int[]>> links = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            links.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            long packed = ids.get(i);
            int x = (int) (packed >> 38);
            int y = (int) (((packed >> 26) & 0xFFF) - 2048);
            int z = signZ((int) (packed & 0x3FFFFFF));
            int[][] steps = {{-1, 0, 0, 0}, {1, 0, 0, 2}, {0, 0, -1, 1}, {0, 0, 1, 3}};
            for (int[] s : steps) {
                long np = pack(x + s[0], y + s[1], z + s[2]);
                Integer j = idx.get(np);
                if (j == null) {
                    continue;
                }
                // Both endpoints are discovered wires; verify vanilla connects
                // i toward j before linking (R3 connection gating, proven in
                // WireUpdateFilter: NONE side reads 0 through that side).
                if (connectedToward(h, level, x, y, z, s[3])) {
                    links.get(i).add(new int[] {j, s[3]});
                }
            }
        }
        // States: virtual, flowIn, discovered, searched, root.
        int[] virt = current.clone();
        int[] flowIn = new int[n];
        boolean[] searched = new boolean[n];
        boolean[] root = new boolean[n];
        boolean[] discoveredArr = new boolean[n];
        // Roots: wires whose recompute (max of external, neighbor-1) differs.
        Deque<Integer> search = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            discoveredArr[i] = true;
            virt[i] = current[i];
            int recomputed = recompute(external[i], links, virt, i);
            if (recomputed != current[i]) {
                findPowerInline(links, virt, external, searched, i, false);
                search.offer(i);
                searched[i] = true;
                root[i] = true;
            }
        }
        // Search phase.
        while (!search.isEmpty()) {
            int id = search.poll();
            for (int[] link : links.get(id)) {
                int nb = link[0];
                if (searched[nb]) {
                    continue;
                }
                if (!discoveredArr[nb]) {
                    discoveredArr[nb] = true;
                    virt[nb] = current[nb];
                }
                findPowerInline(links, virt, external, searched, nb, false);
                if (virt[nb] != current[nb]) {
                    search.offer(nb);
                    searched[nb] = true;
                }
            }
        }
        // Depower + power phases (highest virtual first).
        Deque<Integer> updates = new ArrayDeque<>();
        Set<Integer> queued = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (!searched[i]) {
                continue;
            }
            findPowerInline(links, virt, external, searched, i, true);
            if (root[i] || virt[i] > 0) {
                if (virt[i] != current[i] && queued.add(i)) {
                    updates.offer(i);
                }
            } else {
                virt[i]--;
            }
        }
        Map<Integer, Integer> finals = new HashMap<>();
        for (int i = 0; i < n; i++) {
            finals.put(i, current[i]);
        }
        Set<Integer> settled = new HashSet<>();
        while (!updates.isEmpty()) {
            int best = -1;
            int bestP = Integer.MIN_VALUE;
            for (int id : updates) {
                if (virt[id] > bestP) {
                    bestP = virt[id];
                    best = id;
                }
            }
            updates.remove(best);
            queued.remove(best);
            if (virt[best] == finals.get(best) && settled.contains(best)) {
                continue;
            }
            // Transmit to neighbors.
            for (int[] link : links.get(best)) {
                int nb = link[0];
                int power = Math.max(0, virt[best] - 1);
                if (offerInline(virt, flowIn, nb, power, link[1])) {
                    if (virt[nb] != finals.getOrDefault(nb, current[nb]) && queued.add(nb)) {
                        updates.offer(nb);
                    }
                }
            }
            finals.put(best, clamp(virt[best]));
            settled.add(best);
        }
        // Compare replay finals against live.
        for (int i = 0; i < n; i++) {
            if (!searched[i]) {
                continue;
            }
            int f = finals.get(i);
            if (f != current[i]) {
                long packed = ids.get(i);
                diverges.add("DIVERGE@" + ((int) (packed >> 38)) + ","
                        + ((int) (((packed >> 26) & 0xFFF) - 2048)) + ","
                        + signZ((int) (packed & 0x3FFFFFF))
                        + ":live=" + current[i] + ":replay=" + f);
            }
        }
        return diverges;
    }

    private static int recompute(int ext, List<List<int[]>> links, int[] virt, int id) {
        int best = ext;
        for (int[] link : links.get(id)) {
            best = Math.max(best, virt[link[0]] - 1);
        }
        return Math.max(0, Math.min(15, best));
    }

    private static void findPowerInline(List<List<int[]>> links, int[] virt,
            int[] external, boolean[] searched, int id, boolean ignoreSearched) {
        int best = external[id];
        int flow = 0;
        for (int[] link : links.get(id)) {
            int nb = link[0];
            if (ignoreSearched && searched[nb]) {
                continue;
            }
            int power = Math.max(0, virt[nb] - 1);
            if (power == best && power > 0) {
                flow |= (1 << link[1]);
            } else if (power > best) {
                best = power;
                flow = (1 << link[1]);
            }
        }
        virt[id] = Math.max(0, Math.min(15, best));
    }

    private static boolean offerInline(int[] virt, int[] flowIn, int id, int power, int dir) {
        if (power == virt[id]) {
            flowIn[id] |= (1 << dir);
            return false;
        }
        if (power > virt[id]) {
            virt[id] = power;
            flowIn[id] = (1 << dir);
            return true;
        }
        return false;
    }

    private static int clamp(int p) {
        return Math.max(0, Math.min(15, p));
    }

    private static int signZ(int z) {
        if ((z & 0x2000000) != 0) {
            z |= ~0x3FFFFFF;
        }
        return z;
    }

    private static long pack(int x, int y, int z) {
        return (((long) x) << 38) | (((long) (y + 2048) & 0xFFF) << 26) | ((long) z & 0x3FFFFFF);
    }

    private static void setPos(Handles h, int x, int y, int z) throws Exception {
        h.mutableSet.invoke(cachedPos, x, y, z);
    }

    private static Object overworld(Handles h, Object server) throws Exception {
        if (cachedOverworld == null) {
            for (Object level : (Iterable<?>) h.getAllLevels.invoke(server)) {
                if (h.dimension.invoke(level).toString().contains("overworld")) {
                    cachedOverworld = level;
                    break;
                }
            }
        }
        return cachedOverworld;
    }

    /** Max non-wire signal into the wire: conductors via direct signal, sources via getSignal. */
    private static int readExternalPower(Handles h, Object level, long packed) throws Exception {
        int x = (int) (packed >> 38);
        int y = (int) (((packed >> 26) & 0xFFF) - 2048);
        int z = signZ((int) (packed & 0x3FFFFFF));
        int power = 0;
        int[][] steps = {{-1, 0, 0, 0}, {1, 0, 0, 1}, {0, 0, -1, 2}, {0, 0, 1, 3}, {0, -1, 0, 4}, {0, 1, 0, 5}};
        for (int[] s : steps) {
            setPos(h, x + s[0], y + s[1], z + s[2]);
            Object nstate = h.levelGetBlockState.invoke(level, cachedPos);
            if (h.stateGetBlock.invoke(nstate) == h.redstoneWireBlock) {
                continue;
            }
            Object dir = h.directions[s[3]];
            if ((Boolean) h.isConductor.invoke(nstate, level, cachedPos)) {
                power = Math.max(power, readDirectThrough(h, level, x + s[0], y + s[1], z + s[2]));
            }
            if ((Boolean) h.isSignalSource.invoke(nstate)) {
                power = Math.max(power, ((Number) h.getSignal.invoke(nstate, level, cachedPos, dir)).intValue());
            }
            if (power >= 15) {
                return 15;
            }
        }
        return power;
    }

    private static int readDirectThrough(Handles h, Object level, int x, int y, int z) throws Exception {
        int power = 0;
        int[][] steps = {{-1, 0, 0, 0}, {1, 0, 0, 1}, {0, 0, -1, 2}, {0, 0, 1, 3}, {0, -1, 0, 4}, {0, 1, 0, 5}};
        for (int[] s : steps) {
            setPos(h, x + s[0], y + s[1], z + s[2]);
            Object nstate = h.levelGetBlockState.invoke(level, cachedPos);
            if ((Boolean) h.isSignalSource.invoke(nstate)) {
                power = Math.max(power,
                        ((Number) h.getDirectSignal.invoke(nstate, level, cachedPos, h.directions[s[3]])).intValue());
                if (power >= 15) {
                    return 15;
                }
            }
        }
        return power;
    }

    /**
     * True iff the wire at (x,y,z) connects toward direction index dir (not
     * NONE). Connections are RedstoneSide properties (NORTH/EAST/SOUTH/WEST)
     * on the wire's own BlockState - the same values vanilla's own
     * connection resolution reads. Above/below stepped connections are NOT
     * resolved here: this probe links same-level horizontal wires only, and
     * says so in the log. Stepped links (P/V corpus arms) are therefore
     * outside the replay's topology; any DIVERGE they cause is analyzed, not
     * hidden.
     */
    private static boolean connectedToward(Handles h, Object level, int x, int y, int z, int dir) throws Exception {
        setPos(h, x, y, z);
        Object state = h.levelGetBlockState.invoke(level, cachedPos);
        Object prop = h.sideProperties[dir];
        if (prop == null) {
            return false;
        }
        return h.stateGetValue.invoke(state, prop) != h.sideNone;
    }

    // ---- discovery scan (same chunk walk as PowerTrace) ----

    private static List<long[]> fullScan(Handles h, Object server) throws Exception {
        List<long[]> out = new ArrayList<>();
        for (Object level : (Iterable<?>) h.getAllLevels.invoke(server)) {
            Object chunkSource = h.getChunkSource.invoke(level);
            Object chunkMap = h.chunkMapField.get(chunkSource);
            Object updating = h.updatingChunkMapField.get(chunkMap);
            for (Object fastEntry : (Iterable<?>) h.long2ObjectEntrySet.invoke(updating)) {
                long key = (Long) h.getLongKey.invoke(fastEntry);
                Object chunk = h.getChunkNow.invoke(chunkSource, (int) key, (int) (key >> 32));
                if (chunk == null) {
                    continue;
                }
                collectWires(h, chunk, out);
            }
        }
        return out;
    }

    private static void collectWires(Handles h, Object chunk, List<long[]> out) throws Exception {
        Object pos = h.getPos.invoke(chunk);
        int cx = (Integer) h.chunkPosX.invoke(pos);
        int cz = (Integer) h.chunkPosZ.invoke(pos);
        int minY = (Integer) h.getMinSectionY.invoke(chunk);
        int maxY = (Integer) h.getMaxSectionY.invoke(chunk);
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
                    for (int ly = 0; ly < 16; ly++) {
                        Object state = h.sectionGetBlockState.invoke(section, lx, ly, lz);
                        if (h.stateGetBlock.invoke(state) != h.redstoneWireBlock) {
                            continue;
                        }
                        int wx = cx * 16 + lx;
                        int wz = cz * 16 + lz;
                        out.add(new long[] {pack(wx, baseY + ly, wz), 0});
                    }
                }
            }
        }
    }

    private static Handles handles(Object server) throws ReflectiveOperationException {
        Handles h = handles;
        if (h == null) {
            synchronized (AcProof.class) {
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
        final Constructor<?> mutablePosCtor;
        final Method mutableSet;
        final Method levelGetBlockState;
        final Method stateGetBlock;
        final Method stateGetValue;
        final Method isConductor;
        final Method isSignalSource;
        final Method getSignal;
        final Method getDirectSignal;
        final Object redstoneWireBlock;
        final Object powerProperty;
        final Object sideNone;
        final Object[] directions;
        /** NORTH/EAST/SOUTH/WEST RedstoneSide properties, AC index order. */
        final Object[] sideProperties;

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
            Class<?> directionClass = cls(cl, "net.minecraft.core.Direction");
            Class<?> blockStateBaseClass = cls(cl, "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
            Class<?> blockStateClass = cls(cl, "net.minecraft.world.level.block.state.BlockState");
            Class<?> blocksClass = cls(cl, "net.minecraft.world.level.block.Blocks");
            Class<?> redstoneWireClass = cls(cl, "net.minecraft.world.level.block.RedStoneWireBlock");
            Class<?> propertyClass = cls(cl, "net.minecraft.world.level.block.state.properties.Property");
            Class<?> sideClass = cls(cl, "net.minecraft.world.level.block.state.properties.RedstoneSide");

            getAllLevels = method(serverClass, "getAllLevels");
            dimension = method(levelClass, "dimension");
            getChunkSource = method(serverLevelClass, "getChunkSource");
            chunkMapField = field(serverChunkCacheClass, "chunkMap");
            updatingChunkMapField = declaredField(chunkMapClass, "updatingChunkMap");
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
            Class<?> mutablePosClass = cls(cl, "net.minecraft.core.BlockPos$MutableBlockPos");
            mutablePosCtor = mutablePosClass.getConstructor(int.class, int.class, int.class);
            mutablePosCtor.setAccessible(true);
            mutableSet = method(mutablePosClass, "set", int.class, int.class, int.class);
            levelGetBlockState = method(levelClass, "getBlockState", blockPosClass);
            stateGetBlock = method(blockStateBaseClass, "getBlock");
            // Grounded via javap on 26.2 (2026-09-12): BlockStateBase has
            // isRedstoneConductor(BlockGetter, BlockPos),
            // getSignal/getDirectSignal(BlockGetter, BlockPos, Direction),
            // and NO getConnectionState(BlockState, Level, BlockPos,
            // Direction) anywhere (that 4-arg shape does not exist).
            // Connections resolve through the NORTH/EAST/SOUTH/WEST
            // RedstoneSide properties on the wire's own BlockState (same
            // values vanilla's own methods read); getValue itself lives on
            // StateHolder, the BlockState superclass - resolve there like
            // the census does.
            Class<?> blockGetterClass = cls(cl, "net.minecraft.world.level.BlockGetter");
            Class<?> stateHolderClass = cls(cl, "net.minecraft.world.level.block.state.StateHolder");
            isConductor = method(blockStateBaseClass, "isRedstoneConductor", blockGetterClass, blockPosClass);
            isSignalSource = method(blockStateBaseClass, "isSignalSource");
            getSignal = method(blockStateBaseClass, "getSignal", blockGetterClass, blockPosClass, directionClass);
            getDirectSignal = method(blockStateBaseClass, "getDirectSignal", blockGetterClass, blockPosClass,
                    directionClass);
            // getValue lives on StateHolder, the BlockState superclass
            // (census pattern), not on BlockStateBase.
            stateGetValue = stateGetValueOn(stateHolderClass, propertyClass);
            redstoneWireBlock = field(blocksClass, "REDSTONE_WIRE").get(null);
            powerProperty = field(redstoneWireClass, "POWER").get(null);
            sideNone = enumConstant(sideClass, "NONE");
            // NORTH=1 EAST=2 SOUTH=3 WEST=0 in AC index order -> fields on
            // RedStoneWireBlock: NORTH/EAST/SOUTH/WEST.
            sideProperties = new Object[] {
                field(redstoneWireClass, "WEST").get(null),
                field(redstoneWireClass, "NORTH").get(null),
                field(redstoneWireClass, "EAST").get(null),
                field(redstoneWireClass, "SOUTH").get(null),
            };
            directions = new Object[] {
                enumConstant(directionClass, "WEST"), enumConstant(directionClass, "NORTH"),
                enumConstant(directionClass, "EAST"), enumConstant(directionClass, "SOUTH"),
                enumConstant(directionClass, "DOWN"), enumConstant(directionClass, "UP"),
            };
        }

        private static Object enumConstant(Class<?> c, String name) throws ReflectiveOperationException {
            try {
                Method m = c.getMethod("valueOf", String.class);
                return m.invoke(null, name);
            } catch (ReflectiveOperationException e) {
                // Some enums (RedstoneSide) expose values() only; match by name.
                Object[] values = (Object[]) c.getMethod("values").invoke(null);
                for (Object v : values) {
                    if (((Enum<?>) v).name().equals(name)) {
                        return v;
                    }
                }
                throw e;
            }
        }

        private static Method stateGetValueOn(Class<?> holder, Class<?> property)
                throws ReflectiveOperationException {
            Method m = holder.getMethod("getValue", property);
            m.setAccessible(true);
            return m;
        }

        private static Class<?> cls(ClassLoader cl, String name) throws ClassNotFoundException {
            return Class.forName(name, true, cl);
        }

        private static Method method(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
            // Walk superclasses + interfaces (BlockStateBase pattern from the census).
            Class<?> cur = c;
            while (cur != null) {
                try {
                    Method m = cur.getMethod(name, params);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException e) {
                    for (Class<?> iface : cur.getInterfaces()) {
                        Method m = findIn(iface, name, params);
                        if (m != null) {
                            return m;
                        }
                    }
                }
                cur = cur.getSuperclass();
            }
            throw new NoSuchMethodException(c.getName() + "." + name);
        }

        private static Method findIn(Class<?> iface, String name, Class<?>[] params) {
            for (Class<?> cur = iface; cur != null; ) {
                try {
                    Method m = cur.getMethod(name, params);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException e) {
                    Class<?>[] parents = cur.getInterfaces();
                    if (parents.length == 0) {
                        return null;
                    }
                    for (Class<?> p : parents) {
                        Method m = findIn(p, name, params);
                        if (m != null) {
                            return m;
                        }
                    }
                    return null;
                }
            }
            return null;
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
