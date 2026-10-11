package dev.kestrel.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * S14 slice 1 gate: Kestrel's in-memory entity storage against vanilla.
 *
 * <p>Every entity is a {@link Probe} that implements both Kestrel's and
 * vanilla's {@code EntityAccess}, and is added to a Kestrel
 * {@link EntitySectionManager} and a real vanilla
 * {@link PersistentEntitySectionManager} side by side. Checks:
 * <ol>
 *   <li>The search grace and max entity size equal vanilla's
 *       {@code EntitySectionStorage} constants, read reflectively at runtime.</li>
 *   <li>For every {@link FullChunkStatus}, {@link EntityVisibility} matches
 *       vanilla's {@code Visibility.fromFullChunkStatus}.</li>
 *   <li>A few hundred entities at seeded pseudo-random positions (a third
 *       snapped next to section boundaries) in chunks with random status. For
 *       each of thousands of seeded AABBs, some straddling section boundaries and
 *       some aimed at the downward-only Y grace: Kestrel's result equals a
 *       brute-force scan as a set (no misses, no duplicates, nothing from an
 *       inaccessible section), equals vanilla's result in order, and visits the
 *       same sections in the same order as vanilla's own walk.</li>
 *   <li>Oversized entities: Kestrel misses exactly what vanilla misses.</li>
 *   <li>Lifecycle: section crossing and reaping, always-ticking entities
 *       across chunk status flips, duplicate UUID rejection, destroy forgetting
 *       all indexes, random moves and status flips against vanilla.</li>
 *   <li>No residue after removing everything.</li>
 * </ol>
 * Fixed seed, so a failure reproduces. Exit 0 on PASS, 1 otherwise.
 */
public final class EntityStorageCheck {
    private EntityStorageCheck() {
    }

    private static final long SEED = 0x5140_E5L;
    private static final List<String> failures = new ArrayList<>();
    private static int comparisons;

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        checkConstants();
        checkVisibility();
        World w = new World(new Random(SEED));
        checkIndex(w);
        checkChonky(new Random(SEED + 1));
        checkLifecycle(w);
        checkRandomMoves(w);
        checkResidue(w);

        if (!failures.isEmpty()) {
            System.out.println("EntityStorageCheck FAILED with " + failures.size() + " failure(s):");
            for (int i = 0; i < Math.min(failures.size(), 40); i++) {
                System.out.println("  - " + failures.get(i));
            }
            System.exit(1);
        }
        System.out.println("  " + comparisons + " query comparisons against brute force and vanilla");
        System.out.println("EntityStorageCheck: PASS");
    }

    private static void fail(String msg) {
        failures.add(msg);
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) {
            fail(msg);
        }
    }

    // 1. Constants.

    private static void checkConstants() throws Exception {
        Class<?> vs = net.minecraft.world.level.entity.EntitySectionStorage.class;
        int grace = vs.getField("CHONKY_ENTITY_SEARCH_GRACE").getInt(null);
        int size = vs.getField("MAX_NON_CHONKY_ENTITY_SIZE").getInt(null);
        expect(EntitySectionStorage.SEARCH_GRACE == grace,
                "SEARCH_GRACE " + EntitySectionStorage.SEARCH_GRACE + " != vanilla " + grace);
        expect(EntitySectionStorage.MAX_ENTITY_SIZE == size,
                "MAX_ENTITY_SIZE " + EntitySectionStorage.MAX_ENTITY_SIZE + " != vanilla " + size);
        System.out.println("  constants: grace " + grace + ", max size " + size);
    }

    // 2. Visibility.

    private static void checkVisibility() {
        for (FullChunkStatus s : FullChunkStatus.values()) {
            Visibility v = Visibility.fromFullChunkStatus(s);
            EntityVisibility k = EntityVisibility.fromFullChunkStatus(s);
            expect(k.isAccessible() == v.isAccessible() && k.isTicking() == v.isTicking(),
                    "visibility " + s + ": kestrel " + k + " != vanilla " + v);
            expect(k.name().equals(v.name()), "visibility " + s + ": name " + k + " != " + v);
        }
        System.out.println("  visibility: " + FullChunkStatus.values().length + " statuses match");
    }

    // The probe and the paired managers.

    static final class Probe implements EntityAccess, net.minecraft.world.level.entity.EntityAccess {
        final int id;
        final UUID uuid;
        final double w;
        final double h;
        final boolean alwaysTicking;
        double x;
        double y;
        double z;
        EntityInLevelCallback kCallback = EntityInLevelCallback.NULL;
        net.minecraft.world.level.entity.EntityInLevelCallback vCallback =
                net.minecraft.world.level.entity.EntityInLevelCallback.NULL;

        Probe(int id, UUID uuid, double x, double y, double z, double w, double h, boolean alwaysTicking) {
            this.id = id;
            this.uuid = uuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.h = h;
            this.alwaysTicking = alwaysTicking;
        }

        @Override
        public int getId() {
            return id;
        }

        @Override
        public UUID getUUID() {
            return uuid;
        }

        @Override
        public BlockPos blockPosition() {
            return BlockPos.containing(x, y, z);
        }

        @Override
        public AABB getBoundingBox() {
            return new AABB(x - w / 2, y, z - w / 2, x + w / 2, y + h, z + w / 2);
        }

        @Override
        public boolean isAlwaysTicking() {
            return alwaysTicking;
        }

        @Override
        public void setLevelCallback(EntityInLevelCallback callback) {
            kCallback = callback;
        }

        @Override
        public void setLevelCallback(net.minecraft.world.level.entity.EntityInLevelCallback callback) {
            vCallback = callback;
        }

        @Override
        public Stream<? extends net.minecraft.world.level.entity.EntityAccess> getSelfAndPassengers() {
            return Stream.of(this);
        }

        @Override
        public Stream<? extends net.minecraft.world.level.entity.EntityAccess> getPassengersAndSelf() {
            return Stream.of(this);
        }

        @Override
        public void setRemoved(Entity.RemovalReason reason) {
        }

        @Override
        public boolean shouldBeSaved() {
            return false;
        }

        @Override
        public boolean isRemoved() {
            return false;
        }

        long sectionKey() {
            return SectionPos.asLong(blockPosition());
        }

        long chunkKey() {
            return ChunkPos.pack(blockPosition());
        }

        void moveTo(double nx, double ny, double nz) {
            x = nx;
            y = ny;
            z = nz;
            kCallback.onMove();
            vCallback.onMove();
        }

        void remove(Entity.RemovalReason reason) {
            EntityInLevelCallback k = kCallback;
            net.minecraft.world.level.entity.EntityInLevelCallback v = vCallback;
            k.onRemove(reason);
            v.onRemove(reason);
        }

        @Override
        public String toString() {
            return "Probe#" + id + "(" + x + ", " + y + ", " + z + " w" + w + " h" + h + ")";
        }
    }

    /** Both managers plus the test's own record of what should be where. */
    static final class World {
        final Random rnd;
        final EntitySectionManager<Probe> kestrel = new EntitySectionManager<>(Probe.class);
        final PersistentEntitySectionManager<Probe> vanilla =
                new PersistentEntitySectionManager<>(Probe.class, new NoCallbacks(), new NoStorage());
        final net.minecraft.world.level.entity.EntitySectionStorage<Probe> vanillaSections;
        final Long2ObjectMap<?> vanillaSectionMap;
        final List<Probe> live = new ArrayList<>();
        final Map<Long, FullChunkStatus> status = new HashMap<>();
        int nextId = 1;

        @SuppressWarnings("unchecked")
        World(Random rnd) throws Exception {
            this.rnd = rnd;
            Field f = PersistentEntitySectionManager.class.getDeclaredField("sectionStorage");
            f.setAccessible(true);
            vanillaSections = (net.minecraft.world.level.entity.EntitySectionStorage<Probe>) f.get(vanilla);
            Field m = net.minecraft.world.level.entity.EntitySectionStorage.class.getDeclaredField("sections");
            m.setAccessible(true);
            vanillaSectionMap = (Long2ObjectMap<?>) m.get(vanillaSections);
        }

        void setStatus(long chunkKey, FullChunkStatus s) {
            status.put(chunkKey, s);
            kestrel.updateChunkStatus(chunkKey, s);
            vanilla.updateChunkStatus(ChunkPos.unpack(chunkKey), s);
        }

        /** Independent of Kestrel: vanilla's own mapping of the status the test set. */
        boolean accessible(long chunkKey) {
            FullChunkStatus s = status.getOrDefault(chunkKey, FullChunkStatus.INACCESSIBLE);
            return Visibility.fromFullChunkStatus(s).isAccessible();
        }

        Probe newProbe(double x, double y, double z, double w, double h, boolean always) {
            return new Probe(nextId++, new UUID(rnd.nextLong(), rnd.nextLong()), x, y, z, w, h, always);
        }

        boolean add(Probe p) {
            boolean k = kestrel.addNewEntity(p);
            boolean v = vanilla.addNewEntity(p);
            expect(k == v, "add " + p + ": kestrel " + k + " vanilla " + v);
            if (k) {
                live.add(p);
            }
            return k;
        }

        void remove(Probe p, Entity.RemovalReason reason) {
            p.remove(reason);
            live.remove(p);
        }
    }

    static final class NoCallbacks implements LevelCallback<Probe> {
        @Override
        public void onCreated(Probe e) {
        }

        @Override
        public void onDestroyed(Probe e) {
        }

        @Override
        public void onTickingStart(Probe e) {
        }

        @Override
        public void onTickingEnd(Probe e) {
        }

        @Override
        public void onTrackingStart(Probe e) {
        }

        @Override
        public void onTrackingEnd(Probe e) {
        }

        @Override
        public void onSectionChange(Probe e) {
        }
    }

    /** Loads never complete, so vanilla's inbox stays empty and nothing is read from disk. */
    static final class NoStorage implements EntityPersistentStorage<Probe> {
        @Override
        public CompletableFuture<ChunkEntities<Probe>> loadEntities(ChunkPos pos) {
            return new CompletableFuture<>();
        }

        @Override
        public void storeEntities(ChunkEntities<Probe> chunk) {
        }

        @Override
        public void flush(boolean flushStorage) {
        }
    }

    // 3. Index correctness.

    private static final FullChunkStatus[] STATUSES = FullChunkStatus.values();

    /** Blocks near a section boundary about a third of the time. */
    private static double coord(Random r, int lo, int hi) {
        if (r.nextInt(3) == 0) {
            int b = Math.floorDiv(lo, 16) * 16 + 16 * r.nextInt((hi - lo) / 16);
            return b + (r.nextDouble() * 2 - 1) * 0.75;
        }
        return lo + r.nextDouble() * (hi - lo);
    }

    private static void checkIndex(World w) {
        Random r = w.rnd;
        // 8x8 chunks at blocks [-64, 64), Y in [-64, 128). Half the chunks get a
        // status before entities arrive, the rest after, so both the initial
        // section status and the propagation path are exercised.
        List<Long> chunks = new ArrayList<>();
        for (int cx = -4; cx < 4; cx++) {
            for (int cz = -4; cz < 4; cz++) {
                chunks.add(ChunkPos.pack(cx, cz));
            }
        }
        for (int i = 0; i < chunks.size(); i += 2) {
            w.setStatus(chunks.get(i), STATUSES[r.nextInt(STATUSES.length)]);
        }
        for (int i = 0; i < 400; i++) {
            Probe p = w.newProbe(coord(r, -64, 64), coord(r, -64, 128), coord(r, -64, 64),
                    0.1 + r.nextDouble() * 3.9, 0.1 + r.nextDouble() * 3.9, r.nextInt(20) == 0);
            expect(w.add(p), "fresh probe rejected: " + p);
        }
        // Y-grace targets: feet just below a section floor, box reaching 3+ blocks into it.
        for (int i = 0; i < 24; i++) {
            int sy = r.nextInt(10) - 3;
            Probe p = w.newProbe(-60 + r.nextDouble() * 120, sy * 16 - 0.2 - r.nextDouble() * 0.6,
                    -60 + r.nextDouble() * 120, 0.6, 3.99, false);
            w.add(p);
        }
        for (int i = 1; i < chunks.size(); i += 2) {
            w.setStatus(chunks.get(i), STATUSES[r.nextInt(STATUSES.length)]);
        }
        // Re-status a few already-populated chunks.
        for (int i = 0; i < 16; i++) {
            w.setStatus(chunks.get(r.nextInt(chunks.size())), STATUSES[r.nextInt(STATUSES.length)]);
        }
        compareIndexes(w, "index");
        int before = comparisons;
        runQueries(w, 3000, "index");
        yGraceQueries(w);
        System.out.println("  index: " + w.live.size() + " entities, " + w.kestrel.sections().count()
                + " sections, " + (comparisons - before) + " queries");
    }

    private static AABB randomBox(Random r) {
        double[] lo = new double[3];
        double[] hi = new double[3];
        int[][] range = {{-72, 72}, {-72, 136}, {-72, 72}};
        for (int a = 0; a < 3; a++) {
            double ext = r.nextInt(4) == 0 ? r.nextDouble() * 40 : r.nextDouble() * 6;
            double c;
            if (r.nextInt(3) == 0) {
                // Straddle a section boundary on this axis.
                int b = Math.floorDiv(range[a][0], 16) * 16 + 16 * r.nextInt((range[a][1] - range[a][0]) / 16);
                c = b;
                ext = Math.max(ext, 0.25);
            } else {
                c = range[a][0] + r.nextDouble() * (range[a][1] - range[a][0]);
            }
            lo[a] = c - ext * r.nextDouble();
            hi[a] = c + ext * r.nextDouble();
            if (hi[a] <= lo[a]) {
                hi[a] = lo[a] + 0.01;
            }
        }
        return new AABB(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }

    private static void runQueries(World w, int n, String tag) {
        for (int i = 0; i < n; i++) {
            compareQuery(w, randomBox(w.rnd), tag + " q" + i, true);
        }
    }

    /**
     * Thin boxes 2.2 blocks above a section floor, over probes whose feet sit
     * just below it. Only the full 4 block downward reach finds them: a grace of
     * 2 (the X/Z value) would stop at the query's own section.
     */
    private static void yGraceQueries(World w) {
        int hits = 0;
        for (Probe p : new ArrayList<>(w.live)) {
            if (p.h != 3.99 || !w.accessible(p.chunkKey())) {
                continue;
            }
            double floor = Math.floor(p.y / 16.0) * 16.0 + 16.0;
            AABB q = new AABB(p.x - 0.1, floor + 2.2, p.z - 0.1, p.x + 0.1, floor + 2.5, p.z + 0.1);
            expect(SectionPos.posToSectionCoord(p.y) == SectionPos.posToSectionCoord(q.minY) - 1
                            && SectionPos.posToSectionCoord(q.minY - 2) == SectionPos.posToSectionCoord(q.minY),
                    "y-grace setup: " + p + " not reachable only by the full grace from " + q);
            List<Probe> got = new ArrayList<>();
            w.kestrel.getEntities(q, got::add);
            expect(got.contains(p), "y-grace: " + p + " missed by " + q);
            compareQuery(w, q, "ygrace " + p.id, true);
            hits++;
        }
        expect(hits >= 5, "y-grace: only " + hits + " accessible targets, test too weak");
        System.out.println("  y-grace: " + hits + " downward-reach targets found");
    }

    private static int compareQuery(World w, AABB q, String tag, boolean bruteForce) {
        comparisons++;
        List<Probe> k = new ArrayList<>();
        w.kestrel.getEntities(q, k::add);
        List<Probe> v = new ArrayList<>();
        w.vanilla.getEntityGetter().get(q, v::add);
        if (!k.equals(v)) {
            fail(tag + " " + q + ": kestrel " + ids(k) + " != vanilla " + ids(v));
        }
        List<Long> kKeys = new ArrayList<>();
        w.kestrel.sections().forEachAccessibleNonEmptySectionKey(q, kKeys::add);
        List<Long> vKeys = vanillaVisitedKeys(w, q);
        if (!kKeys.equals(vKeys)) {
            fail(tag + " " + q + ": visited sections " + kKeys + " != vanilla " + vKeys);
        }
        Set<Probe> kSet = new HashSet<>(k);
        if (kSet.size() != k.size()) {
            fail(tag + ": duplicates in " + ids(k));
        }
        for (Probe p : k) {
            if (!w.accessible(p.chunkKey())) {
                fail(tag + ": " + p + " returned from inaccessible chunk");
            }
        }
        int misses = 0;
        Set<Probe> expected = new HashSet<>();
        for (Probe p : w.live) {
            if (p.getBoundingBox().intersects(q) && w.accessible(p.chunkKey())) {
                expected.add(p);
            }
        }
        for (Probe p : expected) {
            if (!kSet.contains(p)) {
                misses++;
            }
        }
        for (Probe p : kSet) {
            if (!expected.contains(p)) {
                fail(tag + ": " + p + " returned but brute force says no");
            }
        }
        if (bruteForce && misses > 0) {
            fail(tag + " " + q + ": " + misses + " brute-force hits missed; kestrel " + ids(k));
        }
        return misses;
    }

    private static List<Long> vanillaVisitedKeys(World w, AABB q) {
        Map<Object, Long> keyOf = new IdentityHashMap<>();
        for (Long2ObjectMap.Entry<?> e : w.vanillaSectionMap.long2ObjectEntrySet()) {
            keyOf.put(e.getValue(), e.getLongKey());
        }
        List<Long> keys = new ArrayList<>();
        w.vanillaSections.forEachAccessibleNonEmptySection(q, s -> {
            keys.add(keyOf.get(s));
            return net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE;
        });
        return keys;
    }

    private static List<Integer> ids(List<Probe> ps) {
        List<Integer> out = new ArrayList<>();
        for (Probe p : ps) {
            out.add(p.id);
        }
        return out;
    }

    /**
     * Section index, lookups and UUID registry against the test's own record and
     * against vanilla.
     */
    private static void compareIndexes(World w, String tag) {
        LongOpenHashSet expectedSections = new LongOpenHashSet();
        int visible = 0;
        for (Probe p : w.live) {
            long key = p.sectionKey();
            expectedSections.add(key);
            EntitySection<Probe> s = w.kestrel.sections().getSection(key);
            expect(s != null && s.contains(p), tag + ": " + p + " not in its section " + SectionPos.of(key));
            boolean vis = p.alwaysTicking || w.accessible(p.chunkKey());
            if (vis) {
                visible++;
            }
            Probe byId = w.kestrel.getEntity(p.id);
            Probe byUuid = w.kestrel.getEntity(p.uuid);
            expect(byId == (vis ? p : null), tag + ": id lookup of " + p + " gave " + byId);
            expect(byUuid == (vis ? p : null), tag + ": uuid lookup of " + p + " gave " + byUuid);
            expect(byId == w.vanilla.getEntityGetter().get(p.id), tag + ": id lookup differs from vanilla for " + p);
            expect(byUuid == w.vanilla.getEntityGetter().get(p.uuid),
                    tag + ": uuid lookup differs from vanilla for " + p);
            expect(w.kestrel.isLoaded(p.uuid) && w.vanilla.isLoaded(p.uuid), tag + ": " + p + " not loaded");
        }
        int sectionTotal = 0;
        for (long key : expectedSections) {
            sectionTotal += w.kestrel.sections().getSection(key).size();
        }
        expect(sectionTotal == w.live.size(),
                tag + ": sections hold " + sectionTotal + " entities, expected " + w.live.size());
        expect(w.kestrel.sections().count() == expectedSections.size()
                        && w.kestrel.sections().mapCount() == expectedSections.size(),
                tag + ": " + w.kestrel.sections().count() + "/" + w.kestrel.sections().mapCount()
                        + " sections, expected " + expectedSections.size() + " (empty sections not reaped?)");
        expect(w.kestrel.lookup().count() == visible && w.kestrel.lookup().countById() == visible,
                tag + ": lookup holds " + w.kestrel.lookup().count() + "/" + w.kestrel.lookup().countById()
                        + ", expected " + visible);
        expect(w.kestrel.knownCount() == w.live.size(),
                tag + ": " + w.kestrel.knownCount() + " known UUIDs, expected " + w.live.size());
        String kStats = w.kestrel.knownCount() + "," + w.kestrel.lookup().count() + ","
                + w.kestrel.sections().count();
        String vStats = w.vanilla.gatherStats();
        expect(vStats.startsWith(kStats + ","), tag + ": stats " + kStats + " vs vanilla " + vStats);
    }

    // 4. Oversized entities: the envelope must miss exactly what vanilla misses.

    private static void checkChonky(Random r) throws Exception {
        World w = new World(r);
        for (int cx = -4; cx < 4; cx++) {
            for (int cz = -4; cz < 4; cz++) {
                w.setStatus(ChunkPos.pack(cx, cz), FullChunkStatus.ENTITY_TICKING);
            }
        }
        for (int i = 0; i < 120; i++) {
            w.add(w.newProbe(coord(r, -64, 64), coord(r, -64, 128), coord(r, -64, 64),
                    0.5 + r.nextDouble() * 12, 0.5 + r.nextDouble() * 10, false));
        }
        int misses = 0;
        int before = comparisons;
        for (int i = 0; i < 2000; i++) {
            misses += compareQuery(w, randomBox(r), "chonky q" + i, false);
        }
        expect(misses > 0, "chonky: no envelope misses at all, test does not reach the grace limit");
        System.out.println("  chonky: " + (comparisons - before) + " queries, " + misses
                + " envelope misses shared with vanilla");
    }

    // 5. Lifecycle.

    private static void checkLifecycle(World w) {
        // Two empty ENTITY_TICKING chunks far from the random field, and a hidden one.
        long c62 = ChunkPos.pack(62, 62);
        long c63 = ChunkPos.pack(63, 62);
        long hidden = ChunkPos.pack(64, 62);
        w.setStatus(c62, FullChunkStatus.ENTITY_TICKING);
        w.setStatus(c63, FullChunkStatus.FULL);
        w.setStatus(hidden, FullChunkStatus.INACCESSIBLE);

        Probe p = w.newProbe(1007.5, 70, 1000.5, 0.6, 1.8, false);
        expect(w.add(p), "lifecycle: add failed");
        long k0 = p.sectionKey();
        expect(w.kestrel.sections().getSection(k0) != null, "lifecycle: no section after add");

        // X crossing into the next chunk.
        p.moveTo(1008.5, 70, 1000.5);
        long k1 = p.sectionKey();
        expect(k1 != k0, "lifecycle: setup did not cross a section");
        expect(w.kestrel.sections().getSection(k0) == null, "lifecycle: old section not reaped");
        EntitySection<Probe> s1 = w.kestrel.sections().getSection(k1);
        expect(s1 != null && s1.contains(p) && s1.size() == 1, "lifecycle: not in new section");
        expect(w.kestrel.getEntity(p.id) == p && w.kestrel.getEntity(p.uuid) == p,
                "lifecycle: lookups lost the entity after crossing");

        // Y crossing inside the same chunk.
        p.moveTo(1008.5, 64 + 15.9 + 0.2, 1000.5);
        long k2 = p.sectionKey();
        expect(SectionPos.y(k2) == SectionPos.y(k1) + 1, "lifecycle: setup did not cross in Y");
        expect(w.kestrel.sections().getSection(k1) == null, "lifecycle: Y old section not reaped");
        expect(w.kestrel.sections().getSection(k2).contains(p), "lifecycle: not in Y new section");

        // A move inside the section changes nothing.
        p.moveTo(1009.5, 81, 1001.5);
        expect(p.sectionKey() == k2 && w.kestrel.sections().getSection(k2).size() == 1,
                "lifecycle: in-section move re-bucketed");

        // Into a hidden chunk: stays indexed spatially, leaves the lookups, stays known.
        p.moveTo(1024.5, 81, 1001.5);
        expect(w.kestrel.sections().getSection(k2) == null, "lifecycle: section not reaped leaving for hidden");
        expect(w.kestrel.sections().getSection(p.sectionKey()).contains(p), "lifecycle: not in hidden section");
        expect(w.kestrel.getEntity(p.id) == null && w.kestrel.getEntity(p.uuid) == null,
                "lifecycle: hidden entity still in lookups");
        expect(w.kestrel.isLoaded(p.uuid), "lifecycle: hidden entity forgot its UUID");
        List<Probe> seen = new ArrayList<>();
        w.kestrel.getEntities(p.getBoundingBox().inflate(1), seen::add);
        expect(seen.isEmpty(), "lifecycle: AABB query returned an entity in a hidden chunk");
        compareIndexes(w, "lifecycle hidden");

        // And back.
        p.moveTo(1010.5, 70, 1001.5);
        expect(w.kestrel.getEntity(p.id) == p && w.kestrel.getEntity(p.uuid) == p,
                "lifecycle: lookups did not return after leaving hidden chunk");
        compareIndexes(w, "lifecycle back");

        // Duplicate UUID: rejected everywhere, original untouched.
        int sectionsBefore = w.kestrel.sections().count();
        Probe dup = new Probe(w.nextId++, p.uuid, 1011.5, 90, 1001.5, 0.6, 1.8, false);
        expect(!w.add(dup), "lifecycle: duplicate UUID accepted");
        expect(w.kestrel.getEntity(dup.id) == null, "lifecycle: duplicate indexed by id");
        expect(w.kestrel.getEntity(p.uuid) == p, "lifecycle: duplicate replaced the original");
        expect(w.kestrel.sections().getSection(dup.sectionKey()) == null
                        && w.kestrel.sections().count() == sectionsBefore,
                "lifecycle: duplicate created a section");
        expect(dup.kCallback == EntityInLevelCallback.NULL, "lifecycle: duplicate got a callback");

        // Destroy: forgotten by the section index, both lookups and the UUID registry.
        long last = p.sectionKey();
        w.remove(p, Entity.RemovalReason.KILLED);
        expect(w.kestrel.getEntity(p.id) == null, "lifecycle: destroyed entity still found by id");
        expect(w.kestrel.getEntity(p.uuid) == null, "lifecycle: destroyed entity still found by UUID");
        expect(!w.kestrel.isLoaded(p.uuid), "lifecycle: destroyed entity's UUID still known");
        expect(w.kestrel.sections().getSection(last) == null, "lifecycle: destroyed entity's section kept");
        expect(p.kCallback == EntityInLevelCallback.NULL, "lifecycle: callback not reset on removal");

        // Always-ticking entities stay in the lookups through chunk status flips.
        long flip = ChunkPos.pack(62, 70);
        w.setStatus(flip, FullChunkStatus.ENTITY_TICKING);
        Probe player = w.newProbe(62 * 16 + 4.5, 70, 70 * 16 + 4.5, 0.6, 1.8, true);
        Probe plain = w.newProbe(62 * 16 + 5.5, 70, 70 * 16 + 4.5, 0.6, 1.8, false);
        w.add(player);
        w.add(plain);
        for (FullChunkStatus s : new FullChunkStatus[] {FullChunkStatus.INACCESSIBLE, FullChunkStatus.FULL,
                FullChunkStatus.INACCESSIBLE, FullChunkStatus.BLOCK_TICKING}) {
            w.setStatus(flip, s);
            boolean acc = w.accessible(flip);
            expect(w.kestrel.getEntity(player.id) == player && w.kestrel.getEntity(player.uuid) == player,
                    "lifecycle: always-ticking entity left the lookups at " + s);
            expect((w.kestrel.getEntity(plain.id) == plain) == acc, "lifecycle: plain entity lookup wrong at " + s);
            compareIndexes(w, "lifecycle flip " + s);
        }
        w.remove(player, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        w.remove(plain, Entity.RemovalReason.DISCARDED);

        // The UUID is free again.
        Probe again = new Probe(w.nextId++, p.uuid, 1008.5, 70, 1000.5, 0.6, 1.8, false);
        expect(w.add(again), "lifecycle: freed UUID rejected");
        compareIndexes(w, "lifecycle readd");
        w.remove(again, Entity.RemovalReason.DISCARDED);
        compareIndexes(w, "lifecycle end");
        System.out.println("  lifecycle: crossing, reaping, hidden chunk, always-ticking flips, duplicate UUID, destroy");
    }

    /** Random moves (a third crossing sections) and removals, diffed against vanilla throughout. */
    private static void checkRandomMoves(World w) {
        Random r = w.rnd;
        Entity.RemovalReason[] reasons = Entity.RemovalReason.values();
        for (int i = 0; i < 1500; i++) {
            Probe p = w.live.get(r.nextInt(w.live.size()));
            double step = r.nextInt(3) == 0 ? 20 : 1.5;
            p.moveTo(p.x + (r.nextDouble() * 2 - 1) * step,
                    Math.max(-64, Math.min(127, p.y + (r.nextDouble() * 2 - 1) * step)),
                    p.z + (r.nextDouble() * 2 - 1) * step);
            if (i % 25 == 0) {
                long c = ChunkPos.pack(r.nextInt(8) - 4, r.nextInt(8) - 4);
                w.setStatus(c, STATUSES[r.nextInt(STATUSES.length)]);
            }
            if (i % 10 == 0) {
                w.remove(w.live.get(r.nextInt(w.live.size())), reasons[r.nextInt(reasons.length)]);
            }
            if (i % 100 == 0) {
                compareIndexes(w, "moves " + i);
                runQueries(w, 50, "moves " + i);
            }
        }
        compareIndexes(w, "moves end");
        runQueries(w, 500, "moves end");
        System.out.println("  moves: 1500 moves, 60 status flips, " + w.live.size() + " entities left");
    }

    // 6. Residue.

    private static void checkResidue(World w) {
        Entity.RemovalReason[] reasons = Entity.RemovalReason.values();
        int i = 0;
        for (Probe p : new ArrayList<>(w.live)) {
            w.remove(p, reasons[i++ % reasons.length]);
        }
        expect(w.kestrel.sections().count() == 0 && w.kestrel.sections().mapCount() == 0,
                "residue: " + w.kestrel.sections().count() + "/" + w.kestrel.sections().mapCount() + " sections left");
        expect(w.kestrel.lookup().count() == 0 && w.kestrel.lookup().countById() == 0,
                "residue: lookup holds " + w.kestrel.lookup().count() + "/" + w.kestrel.lookup().countById());
        expect(w.kestrel.knownCount() == 0, "residue: " + w.kestrel.knownCount() + " UUIDs left");
        expect(w.vanilla.gatherStats().startsWith("0,0,0,"), "residue: vanilla " + w.vanilla.gatherStats());
        System.out.println("  residue: empty after removing " + i);
    }
}
