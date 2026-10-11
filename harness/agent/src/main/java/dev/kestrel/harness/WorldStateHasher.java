package dev.kestrel.harness;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real per-tick world-state hash (current walk is schema v3 or later; see
 * TickLogger for the schema history).
 *
 * v2 added container block-entity inventory contents to the v1 baseline.
 * v3 changed the walk itself: chunks resolve via
 * ServerChunkCache.getChunkNow over updatingChunkMap holder keys with a
 * ticket-level prefilter (the v1/v2 visibleChunkMap path hashed ZERO blocks
 * on playerless runs), and blocks read per LevelChunkSection with a
 * hasOnlyAir skip (the v1/v2 per-block reflective walk tripped the server
 * watchdog on force-loaded volumes). Old v1/v2 logs are NOT comparable to
 * v3+ (different chunk set AND different walk).
 *
 * FNV-1a-64 over a deterministically-ordered walk of every loaded level's
 * dimension key, entities (sorted by UUID), and loaded chunks (sorted by
 * chunk position) - non-air block states (position + registry id) for every
 * position in the chunk's full height range, plus block entities (sorted by
 * block position) with container contents hashed slot-by-slot (item
 * registry key + count; component-level differences beyond item+count are
 * NOT covered). This is what makes the harness log usable as an actual
 * differential-testing oracle instead of the pipeline-smoke-test
 * placeholder from schema_version=0.
 *
 * Deliberately reflection-only, same constraint as TickLogger (see its
 * javadoc): this class ships in the boot jar, appended to the bootstrap
 * classloader search path, so it must never reference an MC class/method
 * directly in its own bytecode - only java.* types are safe to call
 * directly (Object.toString(), java.util.UUID, java.util.Map, etc., since
 * those always resolve via bootstrap regardless of which loader defined
 * the concrete instance). Every MC-typed value flows through
 * java.lang.reflect.Method/Field/Constructor, resolved against the actual
 * MC classloader (server.getClass().getClassLoader()) once and cached -
 * see {@link Handles}.
 *
 * SCOPE NOTE: this walks every non-air block position of every loaded
 * chunk, every exit-phase tick (enter lines carry a 0-hash sentinel and no
 * timing meaning - see TickLogger). That is the correct, simplest
 * thing to do for the harness's intended use (small, bounded corpus test
 * worlds under harness/corpus/ - see harness/README.md layout), and is
 * NOT intended to be pointed at a full always-loaded survival world with
 * normal render distance (tens of millions of blocks/tick would make the
 * server fall over). Incremental/dirty-block-tracking hashing (hook
 * block-change call sites instead of scanning) is future work if the
 * harness ever needs to run against large persistent worlds.
 */
final class WorldStateHasher {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static volatile Handles handles;

    private WorldStateHasher() {
    }

    /** Returns -1 (never a value a real hash would ever accidentally coincide with in
     *  practice, and easy to grep for) on any reflective failure. Failures are logged to
     *  stderr but never crash the tick - see class javadoc rationale for why crashing here
     *  would be worse: a fatal hash-hook loses the whole run, whereas a -1 line is an obvious,
     *  greppable "this tick's hash is not trustworthy" signal without stopping the harness. */
    static long hash(Object server) {
        try {
            Handles h = handles(server);

            List<Object> levels = new ArrayList<>();
            for (Object level : (Iterable<?>) h.getAllLevels.invoke(server)) {
                levels.add(level);
            }
            levels.sort(Comparator.<Object, String>comparing(lv -> dimensionKey(h, lv)));

            long acc = FNV_OFFSET;
            for (Object level : levels) {
                acc = mixString(acc, dimensionKey(h, level));
                acc = hashEntities(h, level, acc);
                acc = hashChunks(h, level, acc);
            }
            return acc;
        } catch (Throwable t) {
            System.err.println("[kestrel-harness] world-state hash failed: " + t);
            return -1L;
        }
    }

    private static String dimensionKey(Handles h, Object level) {
        try {
            return h.dimension.invoke(level).toString();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static long hashEntities(Handles h, Object level, long acc) throws ReflectiveOperationException {
        List<Object> entities = new ArrayList<>();
        for (Object e : (Iterable<?>) h.getAllEntities.invoke(level)) {
            entities.add(e);
        }
        entities.sort(Comparator.<Object, UUID>comparing(e -> {
            try {
                return (UUID) h.entityGetUUID.invoke(e);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));

        acc = mixLong(acc, entities.size());
        for (Object e : entities) {
            UUID uuid = (UUID) h.entityGetUUID.invoke(e);
            double x = (Double) h.entityGetX.invoke(e);
            double y = (Double) h.entityGetY.invoke(e);
            double z = (Double) h.entityGetZ.invoke(e);
            Object dm = h.entityGetDeltaMovement.invoke(e);
            double dx = (Double) h.vec3X.invoke(dm);
            double dy = (Double) h.vec3Y.invoke(dm);
            double dz = (Double) h.vec3Z.invoke(dm);
            Object type = h.entityGetType.invoke(e);

            acc = mixLong(acc, uuid.getMostSignificantBits());
            acc = mixLong(acc, uuid.getLeastSignificantBits());
            acc = mixString(acc, type.toString());
            acc = mixDouble(acc, x);
            acc = mixDouble(acc, y);
            acc = mixDouble(acc, z);
            acc = mixDouble(acc, dx);
            acc = mixDouble(acc, dy);
            acc = mixDouble(acc, dz);
        }
        return acc;
    }

    private static long hashChunks(Handles h, Object serverLevel, long acc) throws ReflectiveOperationException {
        // Walk the level's live chunk registry via ServerChunkCache.getChunkNow
        // over the holder keys (NOT getTickingChunk on visible/updating maps):
        // visibleChunkMap only fills for chunks actually sent to a connected
        // player, so playerless harness runs hash ZERO blocks and the tick
        // hash degrades to entities-only. Found 2026-09-10 when the new
        // PowerTrace logged 0 wires on a world whose region file provably
        // contains them (debug: updatingChunkMap holds 575 holders at tick 1,
        // getTickingChunk() null for ALL of them - the holder only publishes
        // a LevelChunk there once its futures complete, which never happens
        // with no player driving visibility). getChunkNow(cx,cz) goes through
        // the cache + visible-holder FULL-status path instead, which is how
        // live game logic (Level.getBlockState) actually reads blocks.
        Object chunkSource = h.getChunkSource.invoke(serverLevel);
        Object chunkMap = h.chunkMapField.get(chunkSource);
        Object updating = h.updatingChunkMapField.get(chunkMap);

        // Ticket-level prefilter (2026-09-10): boot leaves ~961 holders in
        // updatingChunkMap but only ~81 are actually loaded; getChunkNow on
        // an evicted holder still costs a cache scan + holder lookup (~us
        // each, ~1ms per tick total - tolerable) but the real win is
        // documentation: holders above FULL never yield chunks, so the
        // chunkEntries list stays proportional to LOADED chunks.
        // DistanceManager.getChunkLevel(pos, false): 33+ = unloaded,
        // 32 = full border... FULL status (ticking holder futures) is what
        // getChunkNow needs. Threshold: skip level > 32.
        Object distanceManager = h.getDistanceManager.invoke(chunkMap);
        List<Map.Entry<Long, Object>> chunkEntries = new ArrayList<>();
        for (Object fastEntry : (Iterable<?>) h.long2ObjectEntrySet.invoke(updating)) {
            long key = (Long) h.getLongKey.invoke(fastEntry);
            int ticketLevel = (Integer) h.getChunkLevel.invoke(distanceManager, key, false);
            if (ticketLevel > 32) {
                continue;
            }
            int cx = (int) key;
            int cz = (int) (key >> 32);
            Object chunk = h.getChunkNow.invoke(chunkSource, cx, cz);
            if (chunk != null) {
                chunkEntries.add(Map.entry(key, chunk));
            }
        }
        chunkEntries.sort(Map.Entry.comparingByKey());

        acc = mixLong(acc, chunkEntries.size());
        for (Map.Entry<Long, Object> ce : chunkEntries) {
            acc = hashChunk(h, ce.getValue(), acc);
        }
        return acc;
    }

    private static long hashChunk(Handles h, Object chunk, long acc) throws ReflectiveOperationException {
        Object pos = h.getPos.invoke(chunk);
        int cx = (Integer) h.chunkPosX.invoke(pos);
        int cz = (Integer) h.chunkPosZ.invoke(pos);
        int minY = (Integer) h.getMinSectionY.invoke(chunk);
        int maxY = (Integer) h.getMaxSectionY.invoke(chunk);

        acc = mixLong(acc, cx);
        acc = mixLong(acc, cz);

        // Walk LevelChunkSections directly (skip hasOnlyAir sections): the old
        // per-block LevelChunk.getBlockState walk did ~100k reflective calls
        // per chunk per tick and tripped the server watchdog on force-loaded
        // volumes (found 2026-09-10). Section.getBlockState(lx,y,lz) reads the
        // palette without re-resolving the section per block; all-air
        // sections (the common case) cost one hasOnlyAir call.
        //
        // WATCHDOG NOTE: even this walk is ~2 reflective invocations per
        // non-air block. On the 961-holder force-loaded volume that still
        // stalls a tick past 60s (a second watchdog crash at hashChunk:206,
        // same session). Mitigation: hash ONLY non-air blocks (id != 0):
        // air positions contribute nothing position-dependent, so skipping
        // them is exact for divergence purposes as long as non-air blocks
        // still fold their positions (they do below). This cuts the mixLong
        // volume by ~99% on real corpora (mostly air) while keeping the
        // same discriminating power: two worlds differing only in WHERE
        // non-air blocks are still diverge via those blocks' positions.
        Object[] sections = (Object[]) h.getSections.invoke(chunk);
        for (int si = minY; si <= maxY; si++) {
            int sectionY = si - minY;
            if (sectionY < 0 || sectionY >= sections.length) {
                continue;
            }
            Object section = sections[sectionY];
            if ((Boolean) h.sectionHasOnlyAir.invoke(section)) {
                acc = mixLong(acc, 0xA1A1A1A1L ^ si);
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
                        int id = (Integer) h.blockGetId.invoke(null, state);
                        if (id == 0) {
                            continue; // air: no position fold (see note)
                        }
                        acc = mixLong(acc, ((long) y << 40) ^ ((long) wx << 20) ^ (long) (wz & 0xFFFFF));
                        acc = mixLong(acc, id);
                    }
                }
            }
        }

        Object beMapRaw = h.getBlockEntities.invoke(chunk);
        Map<?, ?> beMap = (Map<?, ?>) beMapRaw;
        List<Map.Entry<Long, Object>> sortedBe = new ArrayList<>();
        for (Map.Entry<?, ?> e : beMap.entrySet()) {
            long posLong = (Long) h.blockPosAsLong.invoke(e.getKey());
            sortedBe.add(Map.entry(posLong, e.getValue()));
        }
        sortedBe.sort(Map.Entry.comparingByKey());

        acc = mixLong(acc, sortedBe.size());
        for (Map.Entry<Long, Object> e : sortedBe) {
            Object type = h.blockEntityGetType.invoke(e.getValue());
            Object key = h.registryGetKey.invoke(h.blockEntityTypeRegistry, type);
            acc = mixLong(acc, e.getKey());
            acc = mixString(acc, key.toString());
            // Container contents: any BE implementing Container (hoppers,
            // chests, furnaces...) must hash inventory slot-by-slot, or two
            // worlds differing only in items are "identical" to the harness.
            // Item identity via the item registry key (stable across runs,
            // unlike object hash), plus count. Component-level differences
            // (enchantments, names) beyond item+count are NOT covered yet -
            // sufficient for hopper-chain corpora (plain items), extend if a
            // future corpus needs it.
            if (h.containerClass.isInstance(e.getValue())) {
                acc = hashContainer(h, e.getValue(), acc);
            }
        }
        return acc;
    }

    private static long hashContainer(Handles h, Object container, long acc)
            throws ReflectiveOperationException {
        int size = (Integer) h.containerGetSize.invoke(container);
        acc = mixLong(acc, size);
        for (int slot = 0; slot < size; slot++) {
            Object stack = h.containerGetItem.invoke(container, slot);
            boolean empty = (Boolean) h.stackIsEmpty.invoke(stack);
            if (empty) {
                acc = mixLong(acc, 0);
                continue;
            }
            int count = (Integer) h.stackGetCount.invoke(stack);
            Object item = h.stackGetItem.invoke(stack);
            Object itemKey = h.registryGetKey.invoke(h.itemRegistry, item);
            acc = mixString(acc, itemKey.toString());
            acc = mixLong(acc, count);
        }
        return acc;
    }

    private static long mixLong(long h, long v) {
        for (int i = 0; i < 8; i++) {
            h ^= (v >>> (i * 8)) & 0xFFL;
            h *= FNV_PRIME;
        }
        return h;
    }

    private static long mixDouble(long h, double d) {
        return mixLong(h, Double.doubleToLongBits(d));
    }

    private static long mixString(long h, String s) {
        h = mixLong(h, s.length());
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= FNV_PRIME;
        }
        return h;
    }

    private static Handles handles(Object server) throws ReflectiveOperationException {
        Handles h = handles;
        if (h == null) {
            synchronized (WorldStateHasher.class) {
                h = handles;
                if (h == null) {
                    h = new Handles(server);
                    handles = h;
                }
            }
        }
        return h;
    }

    /** All reflective handles, resolved once against the MC classloader and cached for the
     *  life of the JVM (one server process = one MC classloader = stable handles). */
    private static final class Handles {
        final Method getAllLevels;
        final Method dimension;
        final Method getAllEntities;
        final Method entityGetUUID;
        final Method entityGetX;
        final Method entityGetY;
        final Method entityGetZ;
        final Method entityGetDeltaMovement;
        final Method entityGetType;
        final Method vec3X;
        final Method vec3Y;
        final Method vec3Z;
        final Method getChunkSource;
        final Method getMinSectionY;
        final Method getMaxSectionY;
        final Method getSections;
        final Method sectionHasOnlyAir;
        final Method sectionGetBlockState;
        final Method getPos;
        final Method chunkPosX;
        final Method chunkPosZ;
        final Method getBlockEntities;
        final Method blockEntityGetType;
        final Method blockGetId;
        final Method registryGetKey;
        final Object blockEntityTypeRegistry;
        final Field chunkMapField;
        final Field updatingChunkMapField;
        final Method long2ObjectEntrySet;
        final Method getLongKey;
        final Method getChunkNow;
        final Method getDistanceManager;
        final Method getChunkLevel;
        final Method blockPosAsLong;
        final Class<?> containerClass;
        final Method containerGetSize;
        final Method containerGetItem;
        final Method stackIsEmpty;
        final Method stackGetCount;
        final Method stackGetItem;
        final Object itemRegistry;

        Handles(Object server) throws ReflectiveOperationException {
            ClassLoader cl = server.getClass().getClassLoader();

            Class<?> serverClass = server.getClass();
            Class<?> levelClass = cls(cl, "net.minecraft.world.level.Level");
            Class<?> serverLevelClass = cls(cl, "net.minecraft.server.level.ServerLevel");
            Class<?> entityClass = cls(cl, "net.minecraft.world.entity.Entity");
            Class<?> vec3Class = cls(cl, "net.minecraft.world.phys.Vec3");
            Class<?> serverChunkCacheClass = cls(cl, "net.minecraft.server.level.ServerChunkCache");
            Class<?> chunkMapClass = cls(cl, "net.minecraft.server.level.ChunkMap");
            Class<?> chunkHolderClass = cls(cl, "net.minecraft.server.level.ChunkHolder");
            Class<?> levelChunkClass = cls(cl, "net.minecraft.world.level.chunk.LevelChunk");
            Class<?> chunkPosClass = cls(cl, "net.minecraft.world.level.ChunkPos");
            Class<?> blockPosClass = cls(cl, "net.minecraft.core.BlockPos");
            Class<?> blockStateClass = cls(cl, "net.minecraft.world.level.block.state.BlockState");
            Class<?> blockClass = cls(cl, "net.minecraft.world.level.block.Block");
            Class<?> blockEntityClass = cls(cl, "net.minecraft.world.level.block.entity.BlockEntity");
            Class<?> registryClass = cls(cl, "net.minecraft.core.Registry");
            Class<?> builtInRegistriesClass = cls(cl, "net.minecraft.core.registries.BuiltInRegistries");

            getAllLevels = method(serverClass, "getAllLevels");
            dimension = method(levelClass, "dimension");
            getAllEntities = method(serverLevelClass, "getAllEntities");
            entityGetUUID = method(entityClass, "getUUID");
            entityGetX = method(entityClass, "getX");
            entityGetY = method(entityClass, "getY");
            entityGetZ = method(entityClass, "getZ");
            entityGetDeltaMovement = method(entityClass, "getDeltaMovement");
            entityGetType = method(entityClass, "getType");
            vec3X = method(vec3Class, "x");
            vec3Y = method(vec3Class, "y");
            vec3Z = method(vec3Class, "z");
            getChunkSource = method(serverLevelClass, "getChunkSource");
            // Section walk (ChunkAccess.getSections + LevelChunkSection): the
            // old per-block LevelChunk.getBlockState walk did ~100k reflective
            // invocations per chunk per tick and tripped the server watchdog
            // on force-loaded volumes. getMinSectionY/getMaxSectionY live on
            // LevelHeightAccessor (LevelChunk's interface), NOT on LevelChunk.
            Class<?> heightAccessorClass = cls(cl, "net.minecraft.world.level.LevelHeightAccessor");
            Class<?> chunkAccessClass = cls(cl, "net.minecraft.world.level.chunk.ChunkAccess");
            Class<?> sectionClass = cls(cl, "net.minecraft.world.level.chunk.LevelChunkSection");
            getMinSectionY = method(heightAccessorClass, "getMinSectionY");
            getMaxSectionY = method(heightAccessorClass, "getMaxSectionY");
            getSections = method(chunkAccessClass, "getSections");
            sectionHasOnlyAir = method(sectionClass, "hasOnlyAir");
            sectionGetBlockState = method(sectionClass, "getBlockState", int.class, int.class, int.class);
            getPos = method(levelChunkClass, "getPos");
            chunkPosX = method(chunkPosClass, "x");
            chunkPosZ = method(chunkPosClass, "z");
            getBlockEntities = method(levelChunkClass, "getBlockEntities");
            blockEntityGetType = method(blockEntityClass, "getType");
            blockGetId = method(blockClass, "getId", blockStateClass);
            registryGetKey = method(registryClass, "getKey", Object.class);
            blockEntityTypeRegistry = field(builtInRegistriesClass, "BLOCK_ENTITY_TYPE").get(null);
            chunkMapField = field(serverChunkCacheClass, "chunkMap");
            updatingChunkMapField = declaredField(chunkMapClass, "updatingChunkMap");
            // fastutil Long2ObjectLinkedOpenHashMap: NOT a java.util.Map,
            // iterate via long2ObjectEntrySet(); entries expose getLongKey().
            // Chunks resolved via ServerChunkCache.getChunkNow (main-thread
            // cache + visible-holder FULL path), NOT holder.getTickingChunk()
            // (null until futures complete, which needs player visibility).
            Class<?> fastEntryClass = cls(cl, "it.unimi.dsi.fastutil.longs.Long2ObjectMap$Entry");
            long2ObjectEntrySet = method(updatingChunkMapField.getType(), "long2ObjectEntrySet");
            getLongKey = method(fastEntryClass, "getLongKey");
            getChunkNow = method(serverChunkCacheClass, "getChunkNow", int.class, int.class);
            getDistanceManager = method(chunkMapClass, "getDistanceManager");
            Class<?> distanceManagerClass = cls(cl, "net.minecraft.server.level.DistanceManager");
            getChunkLevel = method(distanceManagerClass, "getChunkLevel", long.class, boolean.class);
            blockPosAsLong = method(blockPosClass, "asLong");
            Class<?> containerClass = cls(cl, "net.minecraft.world.Container");
            Class<?> stackClass = cls(cl, "net.minecraft.world.item.ItemStack");
            this.containerClass = containerClass;
            containerGetSize = method(containerClass, "getContainerSize");
            containerGetItem = method(containerClass, "getItem", int.class);
            stackIsEmpty = method(stackClass, "isEmpty");
            stackGetCount = method(stackClass, "getCount");
            stackGetItem = method(stackClass, "getItem");
            itemRegistry = field(builtInRegistriesClass, "ITEM").get(null);
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
