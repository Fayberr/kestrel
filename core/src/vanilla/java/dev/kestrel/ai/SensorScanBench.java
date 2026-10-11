package dev.kestrel.ai;

import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Microbenchmark for the mob-AI scan path, in the same spirit as
 * {@code CollisionBench}: measure the replaced primitives in isolation, since
 * the whole-tick number depends on how often sensors actually scan.
 *
 * <p>Four comparisons, all against vanilla's real formulation:
 * <ol>
 *   <li><b>Scan ordering</b>: {@code list.sort(Comparator.comparingDouble(body::distanceToSqr))}
 *       (vanilla, key recomputed per comparison) vs {@link ScanOrder}
 *       (key computed once). Real {@code Vec3} distance math on both sides.</li>
 *   <li><b>Player projection</b>: vanilla's three chained stream pipelines vs
 *       {@link PlayerScan#project} single pass.</li>
 *   <li><b>Per-scan verdict memo</b>: vanilla's fresh
 *       {@code Object2BooleanOpenHashMap} per scan vs {@link LosVerdictCache}'s
 *       lazily allocated, generation-stamped table.</li>
 *   <li><b>POI nearest-5 selection</b>: vanilla's sort-all-matches then
 *       limit-5 stream vs {@link PoiTopK#selectNearest} (one key per
 *       candidate, stable top-K, no full sort).</li>
 * </ol>
 *
 * <p>This does NOT measure the line-of-sight raycast, because Kestrel does not
 * change how often it runs: vanilla's {@code Sensing} already memoizes it per
 * (mob, target) per tick. Anything claiming a clip-count win would be measuring
 * a saving that vanilla already banked.
 */
public final class SensorScanBench {
    private SensorScanBench() {
    }

    private static final int[] SIZES = {4, 16, 64, 256};
    private static final int WARMUP_ITERS = 2_000;
    private static final int MEASURE_ITERS = 20_000;
    private static final int REPEATS = 3;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        System.out.println("SensorScanBench (mob-AI scan primitives, vanilla vs kestrel)");
        for (int n : SIZES) {
            benchSort(n);
        }
        for (int n : SIZES) {
            benchProjection(n);
        }
        for (int n : SIZES) {
            benchVerdictMemo(n);
        }
        for (int n : SIZES) {
            benchIdleScan(n);
        }
        for (int n : SIZES) {
            benchPoiTopK(n);
        }
        benchSecondaryPoi();
        for (int n : SIZES) {
            benchBehaviorSchedule(n);
        }
    }

    // ---- 7. Behavior walks (E5) ----

    /**
     * What E5 removes and what it cannot. Vanilla pays per entity per tick:
     * two full walks over the priority/activity/behavior structure, one fresh
     * {@code ObjectArrayList} from {@code getRunningBehaviors} plus its growth
     * and {@code add} calls, and one iterator per nested collection visited on
     * both walks. {@link BehaviorSchedule} flattens the structure once and
     * fills a reusable snapshot instead, but deliberately still scans every
     * behavior's status, because the snapshot is load-bearing (see
     * {@code BehaviorSchedule}'s javadoc). So this row measures allocation and
     * iteration overhead only, with the START predicates configured so nothing
     * ever starts and the TICK predicates reusing the snapshot.
     *
     * <p>Two regimes, because the allocation mix differs: "brainwalk" has a
     * typical villager-shaped structure (a few activities across priorities,
     * most behaviors idle), while "brainwalkR" sets a third of the behaviors
     * RUNNING so the snapshot loop does real fill work.
     */
    private static void benchBehaviorSchedule(int n) {
        // Villager-shaped: 3 priorities, 2 activities each, n behaviors total.
        java.util.Map<Integer, java.util.Map<Integer, java.util.Set<Integer>>> structure =
                new java.util.TreeMap<>();
        for (int p = 0; p < 3; p++) {
            java.util.Map<Integer, java.util.Set<Integer>> byActivity = new java.util.HashMap<>();
            for (int a = 0; a < 2; a++) {
                byActivity.put(p * 2 + a, new java.util.LinkedHashSet<>());
            }
            structure.put(p, byActivity);
        }
        int per = Math.max(1, n / 6);
        int id = 0;
        for (java.util.Map<Integer, java.util.Set<Integer>> byActivity : structure.values()) {
            for (java.util.Set<Integer> set : byActivity.values()) {
                for (int i = 0; i < per && id < n; i++) {
                    set.add(id++);
                }
            }
        }
        while (id < n) {
            structure.get(0).get(0).add(id++);
        }

        BehaviorSchedule<Integer, Integer> schedule = BehaviorSchedule.flatten(structure);
        boolean[] running = new boolean[n];
        Random rng = new Random(0xE5BEC4L);
        for (int i = 0; i < n; i++) {
            running[i] = rng.nextInt(3) == 0;
        }
        java.util.function.Predicate<Integer> isStopped = b -> !running[b];
        java.util.function.Predicate<Integer> isRunning = b -> running[b];
        java.util.function.Predicate<Integer> active = a -> (a & 1) == 0;

        double bestVanilla = Double.MAX_VALUE;
        double bestKestrel = Double.MAX_VALUE;
        int sink = 0;
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            sink += brainwalkVanilla(structure, active, isStopped, isRunning, WARMUP_ITERS);
            sink += brainwalkKestrel(schedule, active, isStopped, isRunning, WARMUP_ITERS);
            bestVanilla = Math.min(bestVanilla,
                    brainwalkVanilla(structure, active, isStopped, isRunning, MEASURE_ITERS) / 1.0e6);
            bestKestrel = Math.min(bestKestrel,
                    brainwalkKestrel(schedule, active, isStopped, isRunning, MEASURE_ITERS) / 1.0e6);
        }
        if (sink == Integer.MIN_VALUE) {
            System.out.println("unreachable " + sink);
        }
        report("brainwalk", n, bestVanilla, bestKestrel);
    }

    /**
     * Vanilla's shape, transcribed: the start walk over the nested maps and
     * sets (with the activity test once per group), then
     * {@code getRunningBehaviors} which allocates a fresh list and walks the
     * structure a second time. The tryStart/tickOrStop bodies are no-ops here
     * by design: the point is the walk and the allocation, not the behavior.
     */
    private static long brainwalkVanilla(java.util.Map<Integer, java.util.Map<Integer, java.util.Set<Integer>>> byPriority,
                                         java.util.function.Predicate<Integer> activityActive,
                                         java.util.function.Predicate<Integer> isStopped,
                                         java.util.function.Predicate<Integer> isRunning,
                                         int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            for (java.util.Map<Integer, java.util.Set<Integer>> byActivity : byPriority.values()) {
                for (java.util.Map.Entry<Integer, java.util.Set<Integer>> entry : byActivity.entrySet()) {
                    if (activityActive.test(entry.getKey())) {
                        for (Integer behavior : entry.getValue()) {
                            if (isStopped.test(behavior)) {
                                guard += 1;
                            }
                        }
                    }
                }
            }
            List<Integer> runningBehaviours = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
            for (java.util.Map<Integer, java.util.Set<Integer>> byActivity : byPriority.values()) {
                for (java.util.Set<Integer> behaviors : byActivity.values()) {
                    for (Integer behavior : behaviors) {
                        if (isRunning.test(behavior)) {
                            runningBehaviours.add(behavior);
                        }
                    }
                }
            }
            for (Integer ignored : runningBehaviours) {
                guard += 1;
            }
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    /** Kestrel's shape: the same two phases over the flattened schedule. */
    private static long brainwalkKestrel(BehaviorSchedule<Integer, Integer> schedule,
                                         java.util.function.Predicate<Integer> activityActive,
                                         java.util.function.Predicate<Integer> isStopped,
                                         java.util.function.Predicate<Integer> isRunning,
                                         int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            final int[] started = {0};
            schedule.startEachNonRunningBehavior(activityActive, isStopped, b -> started[0]++);
            guard += started[0];
            final int[] ticked = {0};
            schedule.tickEachRunningBehavior(isRunning, b -> ticked[0]++);
            guard += ticked[0];
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    // ---- 6. Secondary-POI volume scan (E3) ----

    /**
     * Vanilla's shape: a fresh immutable {@code BlockPos} per probed position
     * (405 allocations), an eagerly allocated result list, and the profession's
     * secondary-POI set re-derived per position. Kestrel's shape: one hoisted
     * probe set, no per-position allocation, and for a profession that declares
     * no secondary POI (14 of the 15 vanilla professions) no probe at all.
     *
     * <p>The block lookup is IDENTICAL on both sides here, which understates
     * the real win: the chunk-column hoist that turns 405
     * {@code Level.getBlockState} entries into at most four {@code getChunk}
     * fetches needs a live level, so it is not in this number. What this row
     * measures is the allocation and loop overhead only.
     */
    private static void benchSecondaryPoi() {
        final net.minecraft.world.level.block.Block[] palette = {
                net.minecraft.world.level.block.Blocks.AIR,
                net.minecraft.world.level.block.Blocks.DIRT,
                net.minecraft.world.level.block.Blocks.FARMLAND,
                net.minecraft.world.level.block.Blocks.STONE
        };
        final byte[] world = new byte[64 * 64 * 64];
        Random rng = new Random(0xE3B0L);
        for (int i = 0; i < world.length; i++) {
            world[i] = (byte) rng.nextInt(palette.length);
        }
        SecondaryPoiScan.BlockLookup<net.minecraft.world.level.block.Block> lookup =
                (x, y, z) -> palette[world[((x & 63) << 12) | ((y & 63) << 6) | (z & 63)]];

        net.minecraft.core.BlockPos center = new net.minecraft.core.BlockPos(31, 31, 31);
        benchOneSecondaryPoi("secpoi", com.google.common.collect.ImmutableSet.of(
                net.minecraft.world.level.block.Blocks.FARMLAND), center, lookup);
        benchOneSecondaryPoi("secpoi0", com.google.common.collect.ImmutableSet.of(), center, lookup);
    }

    private static void benchOneSecondaryPoi(String label,
                                             java.util.Set<net.minecraft.world.level.block.Block> probeSet,
                                             net.minecraft.core.BlockPos center,
                                             SecondaryPoiScan.BlockLookup<net.minecraft.world.level.block.Block> lookup) {
        double bestVanilla = Double.MAX_VALUE;
        double bestKestrel = Double.MAX_VALUE;
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            secpoiVanilla(probeSet, center, lookup, WARMUP_ITERS);
            secpoiKestrel(probeSet, center, lookup, WARMUP_ITERS);
            bestVanilla = Math.min(bestVanilla, secpoiVanilla(probeSet, center, lookup, MEASURE_ITERS) / 1.0e6);
            bestKestrel = Math.min(bestKestrel, secpoiKestrel(probeSet, center, lookup, MEASURE_ITERS) / 1.0e6);
        }
        report(label, SecondaryPoiScan.VOLUME, bestVanilla, bestKestrel);
    }

    /** Transcribed from the decompiled {@code SecondaryPoiSensor.doTick}. */
    private static long secpoiVanilla(java.util.Set<net.minecraft.world.level.block.Block> probeSet,
                                      net.minecraft.core.BlockPos center,
                                      SecondaryPoiScan.BlockLookup<net.minecraft.world.level.block.Block> lookup,
                                      int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            List<net.minecraft.core.BlockPos> jobSites = new ArrayList<>();
            for (int x = -4; x <= 4; x++) {
                for (int y = -2; y <= 2; y++) {
                    for (int z = -4; z <= 4; z++) {
                        net.minecraft.core.BlockPos testPos = center.offset(x, y, z);
                        if (probeSet.contains(lookup.blockAt(testPos.getX(), testPos.getY(), testPos.getZ()))) {
                            jobSites.add(testPos);
                        }
                    }
                }
            }
            guard += jobSites.size();
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    /** Kestrel's shape: skip when the probe set is empty, allocate only on hits. */
    private static long secpoiKestrel(java.util.Set<net.minecraft.world.level.block.Block> probeSet,
                                      net.minecraft.core.BlockPos center,
                                      SecondaryPoiScan.BlockLookup<net.minecraft.world.level.block.Block> lookup,
                                      int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            if (probeSet.isEmpty()) {
                guard += SecondaryPoiScan.scan(probeSet, center.getX(), center.getY(), center.getZ(),
                        lookup, (x, y, z) -> { }) ? 1 : 0;
                continue;
            }
            List<net.minecraft.core.BlockPos> jobSites = new ArrayList<>();
            SecondaryPoiScan.scan(probeSet, center.getX(), center.getY(), center.getZ(), lookup,
                    (x, y, z) -> jobSites.add(new net.minecraft.core.BlockPos(x, y, z)));
            guard += jobSites.size();
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    // ---- 5. POI nearest-5 selection (E2) ----

    /**
     * Vanilla's shape: {@code findAllClosestFirstWithType} sorts ALL matches
     * by a comparator that recomputes {@code distSqr} per comparison, then
     * {@code .limit(5)}. Kestrel's {@link PoiTopK#selectNearest} computes each
     * key once and keeps the nearest 5 without sorting the full list. Real
     * {@code BlockPos} distance math on both sides.
     */
    private static void benchPoiTopK(int n) {
        Random rng = new Random(4242L + n);
        net.minecraft.core.BlockPos center = new net.minecraft.core.BlockPos(0, 64, 0);
        List<net.minecraft.core.BlockPos> template = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            template.add(new net.minecraft.core.BlockPos(
                    (int) ((rng.nextDouble() - 0.5) * 96.0),
                    64 + (int) ((rng.nextDouble() - 0.5) * 20.0),
                    (int) ((rng.nextDouble() - 0.5) * 96.0)));
        }

        double bestVanilla = Double.MAX_VALUE;
        double bestKestrel = Double.MAX_VALUE;
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            topKVanilla(template, center, WARMUP_ITERS);
            topKKestrel(template, center, WARMUP_ITERS);
            bestVanilla = Math.min(bestVanilla, topKVanilla(template, center, MEASURE_ITERS) / 1.0e6);
            bestKestrel = Math.min(bestKestrel, topKKestrel(template, center, MEASURE_ITERS) / 1.0e6);
        }
        report("poitopk", n, bestVanilla, bestKestrel);
    }

    /** Vanilla's shape: full stream sort by recomputed distSqr, limit 5, to set. */
    private static long topKVanilla(List<net.minecraft.core.BlockPos> template,
                                    net.minecraft.core.BlockPos center, int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            java.util.Set<net.minecraft.core.BlockPos> kept = template.stream()
                    .sorted(Comparator.comparingDouble((net.minecraft.core.BlockPos p) -> p.distSqr(center)))
                    .limit(5)
                    .collect(Collectors.toSet());
            guard += kept.size();
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    /** Kestrel's shape: one key per candidate, stable top-5, no full sort. */
    private static long topKKestrel(List<net.minecraft.core.BlockPos> template,
                                    net.minecraft.core.BlockPos center, int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            List<net.minecraft.core.BlockPos> kept =
                    PoiTopK.selectNearest(template, (ToDoubleFunction<net.minecraft.core.BlockPos>) p -> p.distSqr(center), 5);
            guard += kept.size();
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    // ---- 4. Scans that build a view and never query it ----

    /**
     * The case lazy allocation actually targets, and the one the memo
     * benchmark above cannot see. Vanilla constructs a
     * {@code NearestVisibleLivingEntities} on EVERY scan for EVERY brain mob,
     * which allocates and sizes an {@code Object2BooleanOpenHashMap} up front,
     * whether or not any behavior asks a visibility question that tick. A mob
     * whose behaviors are all idle pays the allocation for nothing.
     */
    private static void benchIdleScan(int n) {
        Integer[] ids = new Integer[n];
        for (int i = 0; i < n; i++) {
            ids[i] = 1000 + i;
        }
        double bestVanilla = Double.MAX_VALUE;
        double bestKestrel = Double.MAX_VALUE;
        LosVerdictCache cache = new LosVerdictCache();
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            idleVanilla(ids, WARMUP_ITERS);
            idleKestrel(cache, WARMUP_ITERS);
            bestVanilla = Math.min(bestVanilla, idleVanilla(ids, MEASURE_ITERS) / 1.0e6);
            bestKestrel = Math.min(bestKestrel, idleKestrel(cache, MEASURE_ITERS) / 1.0e6);
        }
        report("idle", n, bestVanilla, bestKestrel);
    }

    private static long idleVanilla(Integer[] ids, int scans) {
        long start = System.nanoTime();
        int guard = 0;
        for (int s = 0; s < scans; s++) {
            Object2BooleanOpenHashMap<Integer> map = new Object2BooleanOpenHashMap<>(ids.length);
            guard += map.size();
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    private static long idleKestrel(LosVerdictCache cache, int scans) {
        long start = System.nanoTime();
        int guard = 0;
        for (int s = 0; s < scans; s++) {
            cache.beginScan();
            guard += cache.size();
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    // ---- 1. Scan ordering ----

    private static void benchSort(int n) {
        Random rng = new Random(1234L + n);
        Vec3 origin = new Vec3(0.5, 64.0, 0.5);
        List<Vec3> template = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            template.add(new Vec3((rng.nextDouble() - 0.5) * 32.0,
                    64.0 + (rng.nextDouble() - 0.5) * 8.0,
                    (rng.nextDouble() - 0.5) * 32.0));
        }

        double bestVanilla = Double.MAX_VALUE;
        double bestKestrel = Double.MAX_VALUE;
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            sortVanilla(template, origin, WARMUP_ITERS);
            sortKestrel(template, origin, WARMUP_ITERS);
            bestVanilla = Math.min(bestVanilla, sortVanilla(template, origin, MEASURE_ITERS) / 1.0e6);
            bestKestrel = Math.min(bestKestrel, sortKestrel(template, origin, MEASURE_ITERS) / 1.0e6);
        }
        report("sort", n, bestVanilla, bestKestrel);
    }

    private static long sortVanilla(List<Vec3> template, Vec3 origin, int iters) {
        long start = System.nanoTime();
        long guard = 0;
        for (int i = 0; i < iters; i++) {
            List<Vec3> copy = new ArrayList<>(template);
            copy.sort(Comparator.comparingDouble(origin::distanceToSqr));
            guard += copy.size();
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Long.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    private static long sortKestrel(List<Vec3> template, Vec3 origin, int iters) {
        long start = System.nanoTime();
        long guard = 0;
        for (int i = 0; i < iters; i++) {
            List<Vec3> copy = new ArrayList<>(template);
            ScanOrder.sortInPlaceByKey(copy, origin::distanceToSqr);
            guard += copy.size();
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Long.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    // ---- 2. Player projection ----

    private static void benchProjection(int n) {
        Random rng = new Random(5678L + n);
        List<String> players = new ArrayList<>();
        boolean[] targetable = new boolean[n];
        boolean[] attackable = new boolean[n];
        for (int i = 0; i < n; i++) {
            players.add("p" + i);
            targetable[i] = rng.nextInt(4) != 0;
            attackable[i] = rng.nextInt(3) != 0;
        }
        Predicate<String> isTargetable = p -> targetable[Integer.parseInt(p.substring(1))];
        Predicate<String> isAttackable = p -> attackable[Integer.parseInt(p.substring(1))];

        double bestVanilla = Double.MAX_VALUE;
        double bestKestrel = Double.MAX_VALUE;
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            projectVanilla(players, isTargetable, isAttackable, WARMUP_ITERS);
            projectKestrel(players, isTargetable, isAttackable, WARMUP_ITERS);
            bestVanilla = Math.min(bestVanilla,
                    projectVanilla(players, isTargetable, isAttackable, MEASURE_ITERS) / 1.0e6);
            bestKestrel = Math.min(bestKestrel,
                    projectKestrel(players, isTargetable, isAttackable, MEASURE_ITERS) / 1.0e6);
        }
        report("project", n, bestVanilla, bestKestrel);
    }

    private static long projectVanilla(List<String> players, Predicate<String> targetable,
                                       Predicate<String> attackable, int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            List<String> visible = players.stream().filter(targetable).collect(Collectors.toList());
            String nearestVisible = visible.isEmpty() ? null : visible.get(0);
            List<String> visibleAttackable = visible.stream().filter(attackable).toList();
            String nearestVisibleAttackable = visibleAttackable.isEmpty() ? null : visibleAttackable.get(0);
            guard += (nearestVisible == null ? 0 : 1) + (nearestVisibleAttackable == null ? 0 : 1)
                    + visibleAttackable.size();
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    private static long projectKestrel(List<String> players, Predicate<String> targetable,
                                       Predicate<String> attackable, int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            PlayerScan.Projection<String> p = PlayerScan.project(players, targetable, attackable);
            guard += (p.nearestVisiblePlayer == null ? 0 : 1)
                    + (p.nearestVisibleAttackablePlayer == null ? 0 : 1)
                    + p.nearestVisibleAttackablePlayers.size();
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    // ---- 3. Per-scan verdict memo ----

    private static void benchVerdictMemo(int n) {
        Random rng = new Random(9012L + n);
        Integer[] ids = new Integer[n];
        boolean[] verdicts = new boolean[n];
        for (int i = 0; i < n; i++) {
            ids[i] = 1000 + i;
            verdicts[i] = rng.nextBoolean();
        }
        // Two asks per entity per scan is the realistic shape: the sensor
        // builds the view, then a behavior queries it in the same scan.
        int queriesPerScan = n * 2;

        double bestVanilla = Double.MAX_VALUE;
        double bestKestrel = Double.MAX_VALUE;
        LosVerdictCache cache = new LosVerdictCache();
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            memoVanilla(ids, verdicts, queriesPerScan, WARMUP_ITERS);
            memoKestrel(cache, ids, verdicts, queriesPerScan, WARMUP_ITERS);
            bestVanilla = Math.min(bestVanilla, memoVanilla(ids, verdicts, queriesPerScan, MEASURE_ITERS) / 1.0e6);
            bestKestrel = Math.min(bestKestrel,
                    memoKestrel(cache, ids, verdicts, queriesPerScan, MEASURE_ITERS) / 1.0e6);
        }
        report("memo", n, bestVanilla, bestKestrel);
    }

    /** Vanilla's shape: a fresh Object2BooleanOpenHashMap per scan, boxed keys. */
    private static long memoVanilla(Integer[] ids, boolean[] verdicts, int queriesPerScan, int scans) {
        long start = System.nanoTime();
        int guard = 0;
        for (int s = 0; s < scans; s++) {
            Object2BooleanOpenHashMap<Integer> map = new Object2BooleanOpenHashMap<>(ids.length);
            Predicate<Integer> oracle = id -> verdicts[id - 1000];
            for (int q = 0; q < queriesPerScan; q++) {
                Integer id = ids[q % ids.length];
                if (map.computeIfAbsent(id, oracle)) {
                    guard++;
                }
            }
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    /** Kestrel's shape: one table reused across scans, O(1) generation rollover. */
    private static long memoKestrel(LosVerdictCache cache, Integer[] ids, boolean[] verdicts,
                                    int queriesPerScan, int scans) {
        long start = System.nanoTime();
        int guard = 0;
        for (int s = 0; s < scans; s++) {
            cache.beginScan();
            Predicate<Integer> oracle = id -> verdicts[id - 1000];
            for (int q = 0; q < queriesPerScan; q++) {
                Integer id = ids[q % ids.length];
                if (cache.verdict(id, id, oracle)) {
                    guard++;
                }
            }
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    private static void report(String label, int n, double vanillaMs, double kestrelMs) {
        System.out.printf("  %-8s n=%-4d vanilla=%8.2f ms kestrel=%8.2f ms speedup=%.2fx%n",
                label, n, vanillaMs, kestrelMs, vanillaMs / kestrelMs);
    }
}
