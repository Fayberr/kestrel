package dev.kestrel.harness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Same-tick snapshot differential for step 6's Leg 2 ports (not a cross-run
 * hash compare).
 *
 * <p><b>Why not determinism-check.</b> The spawn-census corpus summons
 * entities, and {@code /summon} assigns fresh random UUIDs per run; UUIDs
 * feed the world hash, so two runs NEVER agree tick-for-tick (mob-ai lesson:
 * entity corpora FAIL determinism BY CONSTRUCTION). Worse, leg B/C runs grow
 * unboundedly (no despawn without players... and even with a bot, spawn
 * timing differs). Cross-run hash equality is the wrong tool here. Instead
 * this probe reads vanilla's OWN per-tick {@code SpawnState} inputs on the
 * SAME live tick and recomputes the RNG-free algebra the Leg 2 ports claim:
 * any divergence between vanilla's verdict and the recomputation is logged
 * as a {@code DIVERGE} line. Zero DIVERGE lines over N live ticks (with the
 * AFK bot, attempts executing) IS the proof. UUIDs never enter: only counts,
 * charges, and category verdicts are compared.
 *
 * <p><b>What it logs</b> (spawn-proof.log, next to ticks.log, exit phase
 * only, one line per tick):
 * <pre>
 * # kestrel-harness spawn proof, schema_version=1
 * # tick_count,spawnableChunks,global cells NAME:count/max:pass|fail,charges:count/total,diverges
 * 412,12,MONSTER:3/70:pass,...,charges=5/1.25,diverges=0
 * </pre>
 * Per-category cells are {@code NAME:count/max:pass|fail} for the global cap
 * (recomputed as {@code count < max*chunks/289}). {@code charges} is the
 * charge-point count + total charge (stability signal, not a recomputation).
 * Any recomputation disagreeing with vanilla's stored verdict increments
 * {@code diverges} and appends a {@code DIVERGE ...} detail line
 * immediately after.
 *
 * <p><b>What is actually recomputed vs observed.</b> Honest accounting:
 * <ul>
 *   <li>Global cap: FULLY recomputed. {@code mobCategoryCounts} (fastutil
 *       Object2IntMap) + {@code spawnableChunkCount} + per-category
 *       {@code getMaxInstancesPerChunk} are read reflectively; the
 *       {@code count < max*chunks/289} compare is redone locally and checked
 *       against {@code canSpawnForCategoryGlobal} invoked reflectively on
 *       the live state. Integer division order (multiply first) is part of
 *       what is proven.</li>
 *   <li>Category filter and local cap: OBSERVED, not recomputed
 *       (downgraded from the earlier claim of a full filter recomputation -
 *       the probe never reimplemented getFilteredSpawningCategories).
 *       {@code LocalMobCapCalculator} holds per-player counts keyed by live
 *       player objects; rebuilding that independently would duplicate the
 *       player-proximity scan (cost center c input, not the port). A future
 *       filter/local-cap port has the per-tick count/chunk trace to prove
 *       against, but the live recomputation here covers the global cap only.
 *       </li>
 *   <li>Potential math: PROVEN HEADLESS, observed live. The charge sum is
 *       already proven bit-exact by {@code SpawnCapsCheck}'s 2000-trial
 *       randomized differential (exact double equality vs an inline oracle).
 *       Live, the probe logs charge-point count + total charge per tick as
 *       a stability signal, not a recomputation.</li>
 * </ul>
 *
 * <p>Source anchors: {@code NaturalSpawner.java:538} (global cap),
 * {@code PotentialCalculator.java} (charges),
 * {@code ServerChunkCache.java:369} (lastSpawnState retention).
 *
 * <p>Deliberately reflection-only, same bootstrap-classloader constraint
 * as {@link WorldStateHasher} - ships in the boot jar alongside it.
 */
public final class SpawnProof {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAGIC_NUMBER = 289;

    private static volatile java.io.BufferedWriter writer;
    private static volatile Handles handles;
    private static volatile boolean headerWritten = false;

    private SpawnProof() {
    }

    public static synchronized void init(String proofPath) {
        try {
            writer = new java.io.BufferedWriter(new java.io.FileWriter(proofPath, /* append= */ false));
            writer.write("# kestrel-harness spawn proof, schema_version=" + SCHEMA_VERSION);
            writer.newLine();
            writer.write("# tick_count,spawnableChunks,global cells NAME:count/max:pass|fail,potential charges:count/total,diverges");
            writer.newLine();
            writer.flush();
            headerWritten = true;
        } catch (java.io.IOException e) {
            throw new RuntimeException("kestrel-harness: could not open spawn proof at " + proofPath, e);
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
        List<String> lines;
        try {
            lines = buildLines(server, tickCount);
        } catch (Throwable t) {
            System.err.println("[kestrel-harness] spawn proof failed at tick " + tickCount + ": " + t);
            return;
        }
        synchronized (SpawnProof.class) {
            try {
                for (String line : lines) {
                    w.write(line);
                    w.newLine();
                }
                // Flush per line like TickLogger: runs end via
                // Stop-Process -Force, so buffered rows would be lost.
                w.flush();
            } catch (java.io.IOException e) {
                System.err.println("[kestrel-harness] spawn proof write failed: " + e);
            }
        }
    }

    // ---- line building ----

    private static List<String> buildLines(Object server, int tickCount) throws ReflectiveOperationException {
        List<String> out = new ArrayList<>();
        Handles h = handles(server);
        Object state = lastSpawnState(server, h);
        if (state == null) {
            // tickChunks has not run yet this tick (or gamerule path skipped
            // state use); log a no-state line rather than failing.
            out.add(tickCount + ",nostate,diverges=0");
            return out;
        }
        int chunks = (Integer) h.spawnStateChunksField.get(state);
        Object counts = h.spawnStateCountsField.get(state);
        // mobCategoryCounts is Object2IntOpenHashMap<MobCategory>: read via
        // its Object2IntMap interface (getInt + object2IntEntrySet).
        Map<String, Integer> countByName = new TreeMap<>();
        for (Object entry : (Iterable<?>) h.entrySetMethod.invoke(counts)) {
            Object cat = h.entryGetKey.invoke(entry);
            int count = (Integer) h.entryGetIntValue.invoke(entry);
            countByName.put(categoryName(h, cat), count);
        }
        // All MobCategory values except MISC, in declaration order.
        List<?> categories = categoriesInOrder(h);
        StringBuilder sb = new StringBuilder();
        sb.append(tickCount).append(',').append(chunks);
        int diverges = 0;
        List<String> divergeDetail = new ArrayList<>();
        for (Object cat : categories) {
            String name = categoryName(h, cat);
            int max = (Integer) h.catMaxMethod.invoke(cat);
            int count = countByName.getOrDefault(name, 0);
            int maxMobCount = max * chunks / MAGIC_NUMBER;
            boolean recomputed = count < maxMobCount;
            boolean vanillaVerdict = (Boolean) h.canSpawnGlobalMethod.invoke(state, cat);
            if (recomputed != vanillaVerdict) {
                diverges++;
                divergeDetail.add("DIVERGE tick=" + tickCount + " cat=" + name
                    + " count=" + count + " max=" + max + " chunks=" + chunks
                    + " recomputed=" + recomputed + " vanilla=" + vanillaVerdict);
            }
            sb.append(',').append(name).append(':').append(count).append('/')
              .append(max).append(':').append(recomputed ? "pass" : "fail");
        }
        // Potential signal: charge-point count + total charge (stability, not
        // a recomputation; the math is proven headless by SpawnCapsCheck).
        Object potential = h.spawnStatePotentialField.get(state);
        List<?> charges = (List<?>) h.potentialChargesField.get(potential);
        double totalCharge = 0.0;
        for (Object point : charges) {
            totalCharge += (Double) h.pointChargeField.get(point);
        }
        sb.append(",charges=").append(charges.size()).append('/')
          .append(totalCharge).append(",diverges=").append(diverges);
        out.add(sb.toString());
        out.addAll(divergeDetail);
        return out;
    }

    private static Object lastSpawnState(Object server, Handles h) throws ReflectiveOperationException {
        // server.getLevel(overworld).getChunkSource().getLastSpawnState()
        Object overworldKey;
        try {
            overworldKey = h.overworldKeyField.get(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        Object level = h.serverGetLevel.invoke(server, overworldKey);
        if (level == null) {
            return null;
        }
        Object chunkSource = h.levelGetChunkSource.invoke(level);
        return h.getLastSpawnStateMethod.invoke(chunkSource);
    }

    private static String categoryName(Handles h, Object cat) throws ReflectiveOperationException {
        return ((String) h.catNameMethod.invoke(cat)).toUpperCase(java.util.Locale.ROOT);
    }

    private static List<?> categoriesInOrder(Handles h) throws ReflectiveOperationException {
        // MobCategory.values() minus MISC, declaration order (MISC is last).
        Object[] values = (Object[]) h.mobCategoryValues.invoke(null);
        List<Object> out = new ArrayList<>();
        for (Object cat : values) {
            if (!"MISC".equals(((Enum<?>) cat).name())) {
                out.add(cat);
            }
        }
        out.sort(Comparator.comparingInt(c -> ((Enum<?>) c).ordinal()));
        return out;
    }

    // ---- reflection handles ----

    private static final class Handles {
        final Method serverGetLevel;
        final Field overworldKeyField;
        final Method levelGetChunkSource;
        final Method getLastSpawnStateMethod;
        final Field spawnStateChunksField;
        final Field spawnStateCountsField;
        final Field spawnStatePotentialField;
        final Method entrySetMethod;
        final Method entryGetKey;
        final Method entryGetIntValue;
        final Method canSpawnGlobalMethod;
        final Method mobCategoryValues;
        final Method catNameMethod;
        final Method catMaxMethod;
        final Field potentialChargesField;
        final Field pointChargeField;

        Handles(Object server) throws ReflectiveOperationException {
            ClassLoader cl = server.getClass().getClassLoader();
            Class<?> serverClass = server.getClass();
            Class<?> levelClass = cls(cl, "net.minecraft.world.level.Level");
            Class<?> resourceKeyClass = cls(cl, "net.minecraft.resources.ResourceKey");
            serverGetLevel = method(serverClass, "getLevel", resourceKeyClass);
            // Level.OVERWORLD is a static ResourceKey<Level> field; keep the
            // Field and read it per tick (same as BlockEntityCensus pattern).
            overworldKeyField = field(levelClass, "OVERWORLD");
            levelGetChunkSource = findMethod(levelClass, "getChunkSource");
            Class<?> chunkCacheClass = cls(cl, "net.minecraft.server.level.ServerChunkCache");
            getLastSpawnStateMethod = findMethod(chunkCacheClass, "getLastSpawnState");
            Class<?> stateClass = cls(cl, "net.minecraft.world.level.NaturalSpawner$SpawnState");
            spawnStateChunksField = declaredField(stateClass, "spawnableChunkCount");
            spawnStateCountsField = declaredField(stateClass, "mobCategoryCounts");
            spawnStatePotentialField = declaredField(stateClass, "spawnPotential");
            // fastutil Object2IntMap entry set: object2IntEntrySet() yields
            // Object2IntMap.Entry with getKey() + getIntValue().
            Class<?> countsClass = spawnStateCountsField.getType();
            entrySetMethod = findMethod(countsClass, "object2IntEntrySet");
            Class<?> entryClass = cls(cl, "it.unimi.dsi.fastutil.objects.Object2IntMap$Entry");
            entryGetKey = findMethod(entryClass, "getKey");
            entryGetIntValue = findMethod(entryClass, "getIntValue");
            canSpawnGlobalMethod = declaredMethod(stateClass, "canSpawnForCategoryGlobal",
                cls(cl, "net.minecraft.world.entity.MobCategory"));
            Class<?> catClass = cls(cl, "net.minecraft.world.entity.MobCategory");
            mobCategoryValues = catClass.getMethod("values");
            mobCategoryValues.setAccessible(true);
            catNameMethod = findMethod(catClass, "getName");
            catMaxMethod = findMethod(catClass, "getMaxInstancesPerChunk");
            Class<?> potentialClass = cls(cl, "net.minecraft.world.level.PotentialCalculator");
            potentialChargesField = declaredField(potentialClass, "charges");
            Class<?> pointClass = cls(cl, "net.minecraft.world.level.PotentialCalculator$PointCharge");
            pointChargeField = declaredField(pointClass, "charge");
        }

        private static Method declaredMethod(Class<?> c, String name, Class<?>... params)
                throws NoSuchMethodException {
            Method m = c.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        }

        @Override
        public String toString() {
            return "SpawnProof.Handles";
        }
    }

    private static volatile Handles handlesCache;

    private static Handles handles(Object server) throws ReflectiveOperationException {
        Handles h = handlesCache;
        if (h == null) {
            synchronized (SpawnProof.class) {
                h = handlesCache;
                if (h == null) {
                    h = new Handles(server);
                    handlesCache = h;
                }
            }
        }
        return h;
    }

    private static Class<?> cls(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, true, cl);
    }

    private static Method method(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        Method m = c.getMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params)
            throws NoSuchMethodException {
        Class<?> cur = c;
        while (cur != null) {
            try {
                Method m = cur.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // fall through to the interface walk below
            }
            for (Class<?> iface : cur.getInterfaces()) {
                Method m = findMethodInInterface(iface, name, params);
                if (m != null) {
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    private static Method findMethodInInterface(Class<?> iface, String name, Class<?>[] params) {
        for (Class<?> cur = iface; cur != null; cur = cur.getSuperclass()) {
            try {
                Method m = cur.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // continue with superinterfaces
            }
            for (Class<?> parent : cur.getInterfaces()) {
                Method m = findMethodInInterface(parent, name, params);
                if (m != null) {
                    return m;
                }
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
