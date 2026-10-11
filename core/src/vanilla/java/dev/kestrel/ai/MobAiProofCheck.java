package dev.kestrel.ai;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import net.minecraft.world.entity.ai.sensing.PlayerSensor;
import net.minecraft.world.entity.ai.sensing.SecondaryPoiSensor;
import net.minecraft.world.entity.ai.sensing.Sensing;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Correctness proof for the mob-AI scan classes ({@link ScanOrder},
 * {@link LosVerdictCache}, {@link PlayerScan}, {@link VisibleEntityScan}),
 * in the same spirit as {@code RedstoneProofCheck}: prove the building blocks
 * are internally exact and that every claim made against vanilla is checked
 * against the REAL 26.2 classes, so a harness result is interpretable.
 *
 * <p>What is proven against real vanilla classes:
 * <ul>
 *   <li>the distance arithmetic Kestrel shares across filter, sort key and
 *       targeting range is bit-identical to vanilla's ({@code Vec3});</li>
 *   <li>the sort permutation is identical to
 *       {@code Comparator.comparingDouble} + {@code List.sort};</li>
 *   <li>{@code requires()} sets match the real {@code PlayerSensor} and
 *       {@code NearestLivingEntitySensor} exactly (these sets are load-bearing:
 *       {@code Brain.getMemory} throws on an unregistered type);</li>
 *   <li>the structural preconditions that make the single-pass player scan
 *       sound (targeting-condition flags) hold on the real classes;</li>
 *   <li>vanilla's own LOS memos still exist ({@code Sensing}'s per-tick
 *       seen/unseen sets, {@code NearestVisibleLivingEntities}' per-scan memo),
 *       which is why Kestrel does NOT add another cache layer;</li>
 *   <li>the empty-scan behavior matches the real
 *       {@code NearestVisibleLivingEntities.empty()}.</li>
 * </ul>
 *
 * <p>What is proven without live entities: an {@code Entity} cannot be
 * constructed headless ({@code Entity.<init>} calls
 * {@code level.getNextEntityId()} and NPEs on a null level), so list algebra
 * and cache lifetime are proven against a literal transcription of vanilla's
 * decompiled formulation driven by controlled oracles. The transcription's
 * fidelity is not assumed: the decompiled sources were re-decompiled from
 * {@code preresearch/vanilla-26.2/server.jar} and diffed byte-for-byte against
 * the copies read during grounding.
 *
 * <p>Exits non-zero on any mismatch.
 */
public final class MobAiProofCheck {
    private MobAiProofCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();

        run("distance-arithmetic-bit-exact", MobAiProofCheck::checkDistanceArithmetic, failures);
        run("E1 sort-order-identity-vs-vanilla-comparator", MobAiProofCheck::checkSortIdentity, failures);
        run("E1 sort-stability-adversarial-keys", MobAiProofCheck::checkSortStabilityAdversarial, failures);
        run("E4 projection-identity-exhaustive", MobAiProofCheck::checkProjectionIdentity, failures);
        run("E4 projection-edge-cases", MobAiProofCheck::checkProjectionEdgeCases, failures);
        run("E1 verdict-cache-vs-direct-oracle", MobAiProofCheck::checkVerdictCacheAgainstOracle, failures);
        run("E1 verdict-cache-one-scan-lifetime", MobAiProofCheck::checkVerdictCacheScanLifetime, failures);
        run("parity requires()-sets-vs-real-sensors", MobAiProofCheck::checkRequiresSets, failures);
        run("parity targeting-condition-invariants", MobAiProofCheck::checkTargetingConditionInvariants, failures);
        run("grounding vanilla-memos-still-exist", MobAiProofCheck::checkVanillaMemosStillExist, failures);
        run("E1 empty-scan-differential-vs-NVLE.empty", MobAiProofCheck::checkEmptyScanDifferential, failures);
        run("E2 topK-identity-vs-sorted-limit5", MobAiProofCheck::checkTopKIdentity, failures);
        run("E2 topK-stability-adversarial-keys", MobAiProofCheck::checkTopKStabilityAdversarial, failures);
        run("E2 topK-key-count-and-edge-cases", MobAiProofCheck::checkTopKKeysAndEdges, failures);
        run("E3 secondary-poi-volume-order-identity", MobAiProofCheck::checkSecondaryPoiOrderIdentity, failures);
        run("E3 secondary-poi-probe-discipline-and-empty-skip", MobAiProofCheck::checkSecondaryPoiSkip, failures);
        run("E3 secondary-poi-grounding-vs-real-registry", MobAiProofCheck::checkSecondaryPoiGrounding, failures);
        run("E5 behavior-start-walk-identity-vs-real-Brain", MobAiProofCheck::checkBehaviorStartWalkIdentity, failures);
        run("E5 behavior-tick-walk-identity-plus-fused-negative-control", MobAiProofCheck::checkBehaviorTickWalkIdentity, failures);
        run("E5 running-behavior-in-inactive-activity-still-ticks", MobAiProofCheck::checkRunningBehaviorInInactiveActivity, failures);
        run("E5 snapshot-buffer-hygiene", MobAiProofCheck::checkSnapshotBufferHygiene, failures);
        run("E5 behavior-walk-grounding-vs-real-Behavior-and-Brain", MobAiProofCheck::checkBehaviorScheduleGrounding, failures);
        run("parity no-RandomSource-draws-in-scan-path", MobAiProofCheck::checkNoRandomDraws, failures);

        System.out.println("MobAiProofCheck: " + (failures.isEmpty() ? "PASS" : "FAIL")
                + " (" + CHECKS_RUN + " checks)");
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    private static int CHECKS_RUN;

    /**
     * Runs one check and prints its verdict, so a PASS run is self-documenting
     * evidence (which checks actually executed) rather than a bare word.
     */
    private static void run(String name, java.util.function.Consumer<List<String>> check, List<String> failures) {
        int before = failures.size();
        check.accept(failures);
        CHECKS_RUN++;
        System.out.println((failures.size() == before ? "  [ok]   " : "  [FAIL] ") + name);
    }

    // ---- 1. Distance arithmetic: one computation may serve three uses ----

    /**
     * {@code PlayerScan} computes {@code body.distanceToSqr(player)} once and
     * reuses it for vanilla's {@code closerThan} range filter, for the sort
     * key, and for {@code TargetingConditions}' range check. That is only
     * legitimate if all three observe the identical double.
     *
     * <p>{@code Entity.closerThan(e, d)} is
     * {@code position().closerThan(e.position(), d)}, which is
     * {@code Vec3.distanceToSqr(x,y,z) < d*d} with {@code xd = other.x - this.x}.
     * {@code Entity.distanceToSqr(Entity)} computes {@code xd = this.x - other.x}.
     * The operands are negated, so each squared term is exactly equal under
     * IEEE-754 and the sums (same order) are bit-identical. Proven here against
     * the real {@code Vec3}.
     */
    private static void checkDistanceArithmetic(List<String> failures) {
        Random rng = new Random(0x5CA1AB1EL);
        for (int i = 0; i < 200_000; i++) {
            Vec3 a = randomVec(rng);
            Vec3 b = randomVec(rng);

            double vanillaCloserThanTerm = a.distanceToSqr(b.x, b.y, b.z);
            double kestrelShared = entityStyleDistanceToSqr(a, b);
            if (Double.doubleToRawLongBits(vanillaCloserThanTerm)
                    != Double.doubleToRawLongBits(kestrelShared)) {
                failures.add("distance: shared distanceToSqr not bit-identical to Vec3.distanceToSqr at i=" + i
                        + " (" + vanillaCloserThanTerm + " vs " + kestrelShared + ")");
                return;
            }

            double d = rng.nextDouble() * 64.0;
            boolean vanillaCloserThan = a.closerThan(b, d);
            boolean kestrelFilter = kestrelShared < d * d;
            if (vanillaCloserThan != kestrelFilter) {
                failures.add("distance: closerThan disagrees with shared-distance filter at i=" + i);
                return;
            }
        }
    }

    private static Vec3 randomVec(Random rng) {
        // Mix ordinary build coordinates with far-lands magnitudes so any
        // cancellation-sensitive difference would surface.
        double scale = rng.nextInt(4) == 0 ? 3.0e7 : 64.0;
        return new Vec3((rng.nextDouble() - 0.5) * scale,
                (rng.nextDouble() - 0.5) * scale,
                (rng.nextDouble() - 0.5) * scale);
    }

    /** Exactly {@code Entity.distanceToSqr(Vec3)}'s body. */
    private static double entityStyleDistanceToSqr(Vec3 self, Vec3 pos) {
        double xd = self.x - pos.x;
        double yd = self.y - pos.y;
        double zd = self.z - pos.z;
        return xd * xd + yd * yd + zd * zd;
    }

    // ---- 2. Sort permutation identity vs the real comparator ----

    private static void checkSortIdentity(List<String> failures) {
        Random rng = new Random(0xD15EA5EL);
        for (int trial = 0; trial < 400; trial++) {
            int n = rng.nextInt(65);
            Vec3 origin = randomVec(rng);
            List<Vec3> source = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                // Deliberately duplicate positions so ties are common and
                // stability is actually exercised.
                source.add(i > 0 && rng.nextInt(3) == 0 ? source.get(rng.nextInt(i)) : randomVec(rng));
            }

            AtomicInteger vanillaKeyCalls = new AtomicInteger();
            List<Vec3> vanillaSorted = new ArrayList<>(source);
            vanillaSorted.sort(Comparator.comparingDouble(v -> {
                vanillaKeyCalls.incrementAndGet();
                return origin.distanceToSqr(v);
            }));

            AtomicInteger kestrelKeyCalls = new AtomicInteger();
            List<Vec3> kestrelSorted = new ArrayList<>(source);
            ScanOrder.sortInPlaceByKey(kestrelSorted, (ToDoubleFunction<Vec3>) v -> {
                kestrelKeyCalls.incrementAndGet();
                return origin.distanceToSqr(v);
            });

            if (!sameOrderByIdentity(vanillaSorted, kestrelSorted)) {
                failures.add("sort: permutation differs from Comparator.comparingDouble at trial=" + trial
                        + " n=" + n);
                return;
            }
            int expectedKeyCalls = n < 2 ? 0 : n;
            if (kestrelKeyCalls.get() != expectedKeyCalls) {
                failures.add("sort: expected " + expectedKeyCalls + " key computations, got "
                        + kestrelKeyCalls.get() + " at trial=" + trial);
                return;
            }
            if (n >= 8 && vanillaKeyCalls.get() <= kestrelKeyCalls.get()) {
                failures.add("sort: vanilla comparator should recompute keys more than once each (n=" + n
                        + ", vanilla=" + vanillaKeyCalls.get() + ", kestrel=" + kestrelKeyCalls.get() + ")");
                return;
            }
        }
    }

    /**
     * Same as above but over hand-picked adversarial key values rather than
     * real distances: heavy ties, both zeros, infinities and NaN, to prove the
     * merge sort matches {@link Double#compare} ordering in every corner
     * {@code Comparator.comparingDouble} would hit.
     */
    private static void checkSortStabilityAdversarial(List<String> failures) {
        double[] palette = {0.0, -0.0, 1.0, -1.0, 4.0, Double.MIN_VALUE, Double.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN};
        Random rng = new Random(0xBADC0FFEEL);
        for (int trial = 0; trial < 2000; trial++) {
            int n = rng.nextInt(33);
            double[] keys = new double[n];
            List<Integer> source = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                keys[i] = palette[rng.nextInt(palette.length)];
                source.add(i);
            }
            ToDoubleFunction<Integer> keyFn = i -> keys[i];

            List<Integer> vanillaSorted = new ArrayList<>(source);
            vanillaSorted.sort(Comparator.comparingDouble(keyFn));

            List<Integer> kestrelSorted = new ArrayList<>(source);
            ScanOrder.sortInPlaceByKey(kestrelSorted, keyFn);

            if (!vanillaSorted.equals(kestrelSorted)) {
                failures.add("sort(adversarial): order differs at trial=" + trial + " n=" + n
                        + " vanilla=" + vanillaSorted + " kestrel=" + kestrelSorted);
                return;
            }
        }
    }

    private static <T> boolean sameOrderByIdentity(List<T> a, List<T> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    // ---- 3. PlayerSensor projection identity (E4) ----

    /**
     * Vanilla's formulation, transcribed literally from the decompiled
     * {@code PlayerSensor.doTick}: three chained stream pipelines. Kestrel's
     * single pass must produce the same four values AND consult the same
     * predicates on the same elements.
     */
    private static <T> Object[] vanillaProjection(List<T> players,
                                                  Predicate<? super T> targetable,
                                                  Predicate<? super T> attackable) {
        List<T> visiblePlayers = players.stream().filter(targetable).collect(Collectors.toList());
        T nearestVisible = visiblePlayers.isEmpty() ? null : visiblePlayers.get(0);
        List<T> visibleAttackablePlayers = visiblePlayers.stream().filter(attackable).toList();
        T nearestVisibleAttackable = visibleAttackablePlayers.isEmpty() ? null : visibleAttackablePlayers.get(0);
        return new Object[]{players, nearestVisible, visibleAttackablePlayers, nearestVisibleAttackable};
    }

    /** Exhaustive over every (targetable, attackable) combination for n = 0..6. */
    private static void checkProjectionIdentity(List<String> failures) {
        for (int n = 0; n <= 6; n++) {
            int combos = 1 << (2 * n);
            for (int mask = 0; mask < combos; mask++) {
                List<String> players = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    players.add("p" + i);
                }
                final int fixedMask = mask;
                Map<String, Boolean> targetableFlags = new HashMap<>();
                Map<String, Boolean> attackableFlags = new HashMap<>();
                for (int i = 0; i < n; i++) {
                    targetableFlags.put("p" + i, ((fixedMask >>> (2 * i)) & 1) != 0);
                    attackableFlags.put("p" + i, ((fixedMask >>> (2 * i + 1)) & 1) != 0);
                }

                List<String> vanillaTargetableCalls = new ArrayList<>();
                List<String> vanillaAttackableCalls = new ArrayList<>();
                Object[] vanilla = vanillaProjection(players,
                        p -> {
                            vanillaTargetableCalls.add(p);
                            return targetableFlags.get(p);
                        },
                        p -> {
                            vanillaAttackableCalls.add(p);
                            return attackableFlags.get(p);
                        });

                List<String> kestrelTargetableCalls = new ArrayList<>();
                List<String> kestrelAttackableCalls = new ArrayList<>();
                PlayerScan.Projection<String> kestrel = PlayerScan.project(players,
                        p -> {
                            kestrelTargetableCalls.add(p);
                            return targetableFlags.get(p);
                        },
                        p -> {
                            kestrelAttackableCalls.add(p);
                            return attackableFlags.get(p);
                        });

                String where = "n=" + n + " mask=" + mask;
                if (kestrel.nearestPlayers != players) {
                    failures.add("projection: NEAREST_PLAYERS must alias the sorted list, " + where);
                    return;
                }
                if (!java.util.Objects.equals(vanilla[1], kestrel.nearestVisiblePlayer)) {
                    failures.add("projection: NEAREST_VISIBLE_PLAYER differs, " + where
                            + " vanilla=" + vanilla[1] + " kestrel=" + kestrel.nearestVisiblePlayer);
                    return;
                }
                if (!vanilla[2].equals(kestrel.nearestVisibleAttackablePlayers)) {
                    failures.add("projection: NEAREST_VISIBLE_ATTACKABLE_PLAYERS differs, " + where
                            + " vanilla=" + vanilla[2] + " kestrel=" + kestrel.nearestVisibleAttackablePlayers);
                    return;
                }
                if (!java.util.Objects.equals(vanilla[3], kestrel.nearestVisibleAttackablePlayer)) {
                    failures.add("projection: NEAREST_VISIBLE_ATTACKABLE_PLAYER differs, " + where);
                    return;
                }
                if (!vanillaTargetableCalls.equals(kestrelTargetableCalls)) {
                    failures.add("projection: targetable evaluation sequence differs, " + where
                            + " vanilla=" + vanillaTargetableCalls + " kestrel=" + kestrelTargetableCalls);
                    return;
                }
                if (!vanillaAttackableCalls.equals(kestrelAttackableCalls)) {
                    failures.add("projection: attackable evaluation sequence differs, " + where
                            + " vanilla=" + vanillaAttackableCalls + " kestrel=" + kestrelAttackableCalls);
                    return;
                }
                if (!kestrel.nearestVisibleAttackablePlayers.isEmpty()
                        && kestrel.nearestVisibleAttackablePlayer
                        != kestrel.nearestVisibleAttackablePlayers.get(0)) {
                    failures.add("projection: attackable head must be element 0, " + where);
                    return;
                }
            }
        }
    }

    /** The named edge cases from the task: empty, single, and nobody visible. */
    private static void checkProjectionEdgeCases(List<String> failures) {
        PlayerScan.Projection<String> empty =
                PlayerScan.project(List.of(), p -> true, p -> true);
        if (empty.nearestVisiblePlayer != null
                || empty.nearestVisibleAttackablePlayer != null
                || !empty.nearestVisibleAttackablePlayers.isEmpty()
                || !empty.nearestPlayers.isEmpty()) {
            failures.add("projection(empty): expected all-empty projection");
        }

        List<String> single = new ArrayList<>(List.of("solo"));
        PlayerScan.Projection<String> visibleSingle =
                PlayerScan.project(single, p -> true, p -> true);
        if (!"solo".equals(visibleSingle.nearestVisiblePlayer)
                || !"solo".equals(visibleSingle.nearestVisibleAttackablePlayer)
                || visibleSingle.nearestVisibleAttackablePlayers.size() != 1) {
            failures.add("projection(single visible): expected the one player in all three slots");
        }

        PlayerScan.Projection<String> invisibleSingle =
                PlayerScan.project(single, p -> false, p -> true);
        if (invisibleSingle.nearestVisiblePlayer != null
                || invisibleSingle.nearestVisibleAttackablePlayer != null
                || !invisibleSingle.nearestVisibleAttackablePlayers.isEmpty()
                || invisibleSingle.nearestPlayers.size() != 1) {
            failures.add("projection(single invisible): NEAREST_PLAYERS keeps the player, "
                    + "the visible slots must be empty");
        }

        List<String> crowd = new ArrayList<>(List.of("a", "b", "c", "d"));
        PlayerScan.Projection<String> allInvisible =
                PlayerScan.project(crowd, p -> false, p -> true);
        if (allInvisible.nearestVisiblePlayer != null
                || !allInvisible.nearestVisibleAttackablePlayers.isEmpty()) {
            failures.add("projection(all invisible): visible slots must be empty");
        }

        // Visible but not attackable: the nearest-visible slot fills, the
        // attackable slots stay empty. This is the case a naive single pass
        // that conflates the two flags would break.
        PlayerScan.Projection<String> visibleNotAttackable =
                PlayerScan.project(crowd, p -> true, p -> false);
        if (!"a".equals(visibleNotAttackable.nearestVisiblePlayer)
                || !visibleNotAttackable.nearestVisibleAttackablePlayers.isEmpty()
                || visibleNotAttackable.nearestVisibleAttackablePlayer != null) {
            failures.add("projection(visible, not attackable): expected nearest visible set, attackable empty");
        }
    }

    // ---- 4. Verdict cache ----

    private static void checkVerdictCacheAgainstOracle(List<String> failures) {
        Random rng = new Random(0xC0FFEEL);
        for (int trial = 0; trial < 300; trial++) {
            LosVerdictCache cache = new LosVerdictCache();
            cache.beginScan();
            Map<Integer, Boolean> truth = new HashMap<>();
            Map<Integer, Integer> evaluationsPerKey = new HashMap<>();

            int queries = 1 + rng.nextInt(400);
            for (int q = 0; q < queries; q++) {
                // Ids deliberately collide in the low bits and go negative,
                // so probing, growth and hashing are all exercised.
                int id = rng.nextInt(64) * (1 << rng.nextInt(8)) * (rng.nextBoolean() ? 1 : -1);
                boolean expected = truth.computeIfAbsent(id, k -> rng.nextBoolean());
                boolean got = cache.verdict(id, id, key -> {
                    evaluationsPerKey.merge(key, 1, Integer::sum);
                    return expected;
                });
                if (got != expected) {
                    failures.add("cache: wrong verdict for id=" + id + " at trial=" + trial);
                    return;
                }
            }
            for (Map.Entry<Integer, Integer> e : evaluationsPerKey.entrySet()) {
                if (e.getValue() != 1) {
                    failures.add("cache: id=" + e.getKey() + " evaluated " + e.getValue()
                            + " times in one scan (must be exactly 1) at trial=" + trial);
                    return;
                }
            }
            if (cache.evaluations() != truth.size()) {
                failures.add("cache: evaluation count " + cache.evaluations()
                        + " should equal distinct ids " + truth.size() + " at trial=" + trial);
                return;
            }
        }
    }

    /**
     * The parity constraint in one test: within a scan the verdicts are a
     * frozen snapshot, and a change between scans (a pillar built after this
     * scan sampled the world) is observed by the NEXT scan, never
     * retroactively by this one.
     */
    private static void checkVerdictCacheScanLifetime(List<String> failures) {
        LosVerdictCache cache = new LosVerdictCache();
        boolean[] worldSaysVisible = {true};
        Predicate<Integer> oracle = id -> worldSaysVisible[0];

        cache.beginScan();
        if (!cache.verdict(7, 7, oracle)) {
            failures.add("cache(lifetime): first scan should see visible");
        }
        // A pillar goes up mid-scan. The scan already sampled: it must not see it.
        worldSaysVisible[0] = false;
        if (!cache.verdict(7, 7, oracle)) {
            failures.add("cache(lifetime): mid-scan change must not be visible to the scan in progress");
        }
        if (!cache.isCached(7)) {
            failures.add("cache(lifetime): id should be cached inside the scan");
        }

        // Next scan: the pillar must now blind it.
        cache.beginScan();
        if (cache.isCached(7)) {
            failures.add("cache(lifetime): verdicts must not survive into the next scan");
        }
        if (cache.verdict(7, 7, oracle)) {
            failures.add("cache(lifetime): next scan must observe the pillar");
        }

        // And the reverse direction: pillar removed between scans.
        worldSaysVisible[0] = true;
        if (cache.verdict(7, 7, oracle)) {
            failures.add("cache(lifetime): removal mid-scan must not be visible to the scan in progress");
        }
        cache.beginScan();
        if (!cache.verdict(7, 7, oracle)) {
            failures.add("cache(lifetime): scan after removal must see visible again");
        }

        // endScan must invalidate just as hard as beginScan.
        cache.endScan();
        if (cache.isCached(7)) {
            failures.add("cache(lifetime): endScan must drop verdicts");
        }

        // Growth must not resurrect a stale generation's entries.
        LosVerdictCache growing = new LosVerdictCache();
        growing.beginScan();
        for (int i = 0; i < 500; i++) {
            final boolean value = (i % 3) == 0;
            growing.verdict(i, i, id -> value);
        }
        growing.beginScan();
        for (int i = 0; i < 500; i++) {
            if (growing.isCached(i)) {
                failures.add("cache(lifetime): stale entry id=" + i + " survived growth plus rollover");
                return;
            }
        }
    }

    // ---- 5. requires() sets against the real sensors ----

    private static void checkRequiresSets(List<String> failures) {
        Set<MemoryModuleType<?>> vanillaPlayerRequires = new PlayerSensor().requires();
        Set<MemoryModuleType<?>> kestrelPlayerRequires = new LinkedHashSet<>(List.of(
                MemoryModuleType.NEAREST_PLAYERS,
                MemoryModuleType.NEAREST_VISIBLE_PLAYER,
                MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER,
                MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYERS));
        if (!vanillaPlayerRequires.equals(kestrelPlayerRequires)) {
            failures.add("requires: PlayerScan memory set differs from the real PlayerSensor "
                    + "(vanilla=" + vanillaPlayerRequires + " kestrel=" + kestrelPlayerRequires + ")");
        }

        Set<MemoryModuleType<?>> vanillaNearestRequires = new NearestLivingEntitySensor<net.minecraft.world.entity.LivingEntity>().requires();
        Set<MemoryModuleType<?>> kestrelNearestRequires = new LinkedHashSet<>(List.of(
                MemoryModuleType.NEAREST_LIVING_ENTITIES,
                MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES));
        if (!vanillaNearestRequires.equals(kestrelNearestRequires)) {
            failures.add("requires: VisibleEntityScan memory set differs from the real "
                    + "NearestLivingEntitySensor (vanilla=" + vanillaNearestRequires
                    + " kestrel=" + kestrelNearestRequires + ")");
        }
    }

    // ---- 6. Targeting-condition preconditions for the single-pass scan ----

    /**
     * {@link PlayerScan} shares one distance and one visibility percentage
     * between the targetable and attackable tests. That is only sound while
     * the real static conditions agree on range, invisibility testing and
     * line-of-sight checking, and carry no extra selector. Assert it on the
     * real objects so a version bump that changes any of it fails here rather
     * than diverging silently in a live world.
     */
    private static void checkTargetingConditionInvariants(List<String> failures) {
        try {
            Object nonCombat = readStatic(Sensor.class, "TARGET_CONDITIONS");
            Object combat = readStatic(Sensor.class, "ATTACK_TARGET_CONDITIONS");
            if (nonCombat == null || combat == null) {
                failures.add("conditions: could not read Sensor's static targeting conditions");
                return;
            }
            assertConditionFlag(failures, nonCombat, "isCombat", false);
            assertConditionFlag(failures, combat, "isCombat", true);
            for (Object conditions : List.of(nonCombat, combat)) {
                assertConditionFlag(failures, conditions, "checkLineOfSight", true);
                assertConditionFlag(failures, conditions, "testInvisible", true);
                if (readField(conditions, "selector") != null) {
                    failures.add("conditions: expected no selector on Sensor's static conditions; "
                            + "PlayerScan's inline form does not apply one");
                }
            }
            double nonCombatRange = (Double) readField(nonCombat, "range");
            double combatRange = (Double) readField(combat, "range");
            if (nonCombatRange != combatRange) {
                failures.add("conditions: targetable and attackable ranges differ ("
                        + nonCombatRange + " vs " + combatRange
                        + "); PlayerScan shares one range for both");
            }

            // The ignore-invisibility variants must differ from the base ones
            // in exactly the invisibility flag, since PlayerScan models that
            // branch as "testInvisible = !isAttackTarget".
            Object nonCombatIgnoreInvis = readStatic(Sensor.class, "TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING");
            Object combatIgnoreInvis = readStatic(Sensor.class, "ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING");
            assertConditionFlag(failures, nonCombatIgnoreInvis, "testInvisible", false);
            assertConditionFlag(failures, combatIgnoreInvis, "testInvisible", false);
            assertConditionFlag(failures, nonCombatIgnoreInvis, "checkLineOfSight", true);
            assertConditionFlag(failures, combatIgnoreInvis, "checkLineOfSight", true);

            // And the real class still gates invisibility behind a range check
            // shaped the way PlayerScan reproduces it.
            for (String fieldName : List.of("isCombat", "range", "checkLineOfSight", "testInvisible", "selector")) {
                TargetingConditions.class.getDeclaredField(fieldName);
            }
        } catch (ReflectiveOperationException e) {
            failures.add("conditions: reflective inspection failed: " + e);
        }
    }

    private static void assertConditionFlag(List<String> failures, Object conditions,
                                            String fieldName, boolean expected) {
        if (conditions == null) {
            failures.add("conditions: missing targeting-condition instance for " + fieldName);
            return;
        }
        try {
            Object value = readField(conditions, fieldName);
            if (!(value instanceof Boolean b) || b != expected) {
                failures.add("conditions: " + conditions.getClass().getSimpleName() + "." + fieldName
                        + " expected " + expected + " but was " + value);
            }
        } catch (ReflectiveOperationException e) {
            failures.add("conditions: could not read " + fieldName + ": " + e);
        }
    }

    private static Object readStatic(Class<?> owner, String fieldName) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object readField(Object target, String fieldName) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    // ---- 7. Vanilla's own memos must still exist ----

    /**
     * The reason Kestrel does NOT add an extra LOS cache layer. If a future
     * version drops either memo, this fails and the E1 conclusion has to be
     * revisited rather than silently inherited.
     */
    private static void checkVanillaMemosStillExist(List<String> failures) {
        try {
            Field seen = Sensing.class.getDeclaredField("seen");
            Field unseen = Sensing.class.getDeclaredField("unseen");
            if (!it.unimi.dsi.fastutil.ints.IntSet.class.isAssignableFrom(seen.getType())
                    || !it.unimi.dsi.fastutil.ints.IntSet.class.isAssignableFrom(unseen.getType())) {
                failures.add("memos: Sensing.seen/unseen are no longer IntSets; "
                        + "the per-tick LOS memo may be gone");
            }
            Sensing.class.getDeclaredMethod("tick");
            Sensing.class.getDeclaredMethod("hasLineOfSight", net.minecraft.world.entity.Entity.class);
        } catch (ReflectiveOperationException e) {
            failures.add("memos: Sensing no longer has the per-tick seen/unseen memo: " + e);
        }

        try {
            Field losTest = NearestVisibleLivingEntities.class.getDeclaredField("lineOfSightTest");
            if (!Predicate.class.isAssignableFrom(losTest.getType())) {
                failures.add("memos: NearestVisibleLivingEntities.lineOfSightTest is no longer a Predicate");
            }
            NearestVisibleLivingEntities.class.getDeclaredField("nearbyEntities");
        } catch (ReflectiveOperationException e) {
            failures.add("memos: NearestVisibleLivingEntities lost its memoized LOS predicate: " + e);
        }
    }

    // ---- 8. Empty-scan differential against the real class ----

    private static void checkEmptyScanDifferential(List<String> failures) {
        NearestVisibleLivingEntities vanillaEmpty = NearestVisibleLivingEntities.empty();
        VisibleEntityScan kestrelEmpty = VisibleEntityScan.empty();

        Optional<?> vanillaClosest = vanillaEmpty.findClosest(e -> true);
        Optional<?> kestrelClosest = kestrelEmpty.findClosest(e -> true);
        if (vanillaClosest.isPresent() != kestrelClosest.isPresent()) {
            failures.add("empty: findClosest differs from the real NearestVisibleLivingEntities.empty()");
        }
        if (vanillaEmpty.contains(e -> true) != kestrelEmpty.contains(e -> true)) {
            failures.add("empty: contains(Predicate) differs from the real empty view");
        }
        if (vanillaEmpty.nearbyEntities().size() != kestrelEmpty.nearbyEntities().size()) {
            failures.add("empty: nearbyEntities differs from the real empty view");
        }
        if (vanillaEmpty.findAll(e -> true).iterator().hasNext()
                != kestrelEmpty.findAll(e -> true).iterator().hasNext()) {
            failures.add("empty: findAll differs from the real empty view");
        }
        if (vanillaEmpty.find(e -> true).findFirst().isPresent()
                != kestrelEmpty.find(e -> true).findFirst().isPresent()) {
            failures.add("empty: find differs from the real empty view");
        }
    }

    // ---- 9. Scan cadence and jitter are untouched ----

    /**
     * The hardest parity rule to test and the easiest to break by accident:
     * Kestrel must never draw from any RNG on the scan path, because
     * {@code Sensor.randomlyDelayStart} and the level RNG draws inside
     * behaviors are part of the observable sequence. Scanning the compiled
     * class constants catches a draw that a signature check would miss.
     */
    private static void checkNoRandomDraws(List<String> failures) {
        for (Class<?> cls : List.of(ScanOrder.class, LosVerdictCache.class,
                PlayerScan.class, VisibleEntityScan.class, PoiTopK.class,
                SecondaryPoiScan.class, BehaviorSchedule.class)) {
            byte[] bytes = readClassBytes(cls);
            if (bytes == null) {
                failures.add("rng: could not read class bytes for " + cls.getSimpleName());
                continue;
            }
            String constants = new String(bytes, StandardCharsets.ISO_8859_1);
            for (String forbidden : List.of("RandomSource", "getRandom", "nextInt", "nextDouble",
                    "nextFloat", "java/util/Random")) {
                if (constants.contains(forbidden)) {
                    failures.add("rng: " + cls.getSimpleName() + " references '" + forbidden
                            + "'; the scan path must draw no randomness (cadence and jitter are parity)");
                }
            }
        }
    }

    // ---- E2. POI top-K selection identity (sorted-then-limit-5) ----

    /**
     * Vanilla's formulation, transcribed literally from the decompiled
     * {@code PoiManager.findAllClosestFirstWithType} plus the
     * {@code AcquirePoi} call site: sort all matches by distSqr, limit 5,
     * collect to set. Kestrel's selection must yield the same SET with the
     * same per-element key count discipline.
     */
    private static <T> Set<T> vanillaTopK(List<T> candidates, ToDoubleFunction<T> keyFn, int k) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(keyFn))
                .limit(k)
                .collect(Collectors.toSet());
    }

    /** Randomized trials over realistic distance keys, incl. tie-heavy inputs. */
    private static void checkTopKIdentity(List<String> failures) {
        Random rng = new Random(0xE2A01L);
        for (int trial = 0; trial < 500; trial++) {
            int n = rng.nextInt(65);
            Vec3 origin = randomVec(rng);
            List<Vec3> candidates = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                candidates.add(i > 0 && rng.nextInt(3) == 0 ? candidates.get(rng.nextInt(i)) : randomVec(rng));
            }
            ToDoubleFunction<Vec3> keyFn = v -> origin.distanceToSqr(v);

            Set<Vec3> vanilla = vanillaTopK(candidates, keyFn, 5);
            PoiTopK.resetKeyEvaluations();
            List<Vec3> kestrel = PoiTopK.selectNearest(candidates, keyFn, 5);

            if (!new java.util.HashSet<>(kestrel).equals(vanilla)) {
                failures.add("topK: selected set differs from sorted-limit-5 at trial=" + trial + " n=" + n);
                return;
            }
            if (kestrel.size() != Math.min(5, n)) {
                failures.add("topK: expected " + Math.min(5, n) + " survivors, got "
                        + kestrel.size() + " at trial=" + trial);
                return;
            }
            // Output order: nearest-first, ties in encounter order.
            for (int i = 1; i < kestrel.size(); i++) {
                double prev = origin.distanceToSqr(kestrel.get(i - 1));
                double cur = origin.distanceToSqr(kestrel.get(i));
                if (Double.compare(prev, cur) > 0) {
                    failures.add("topK: output not nearest-first at trial=" + trial);
                    return;
                }
            }
        }
    }

    /**
     * Same as the E1 adversarial sort check but through the top-K path:
     * heavy ties, both zeros, infinities and NaN must resolve exactly as
     * stable-sort-then-limit does, including WHICH tied elements survive.
     */
    private static void checkTopKStabilityAdversarial(List<String> failures) {
        double[] palette = {0.0, -0.0, 1.0, -1.0, 4.0, Double.MIN_VALUE, Double.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN};
        Random rng = new Random(0xE2B02L);
        for (int trial = 0; trial < 2000; trial++) {
            int n = rng.nextInt(33);
            double[] keys = new double[n];
            List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                keys[i] = palette[rng.nextInt(palette.length)];
                candidates.add(i);
            }
            ToDoubleFunction<Integer> keyFn = i -> keys[i];

            Set<Integer> vanilla = vanillaTopK(candidates, keyFn, 5);
            List<Integer> kestrel = PoiTopK.selectNearest(candidates, keyFn, 5);

            if (!new java.util.HashSet<>(kestrel).equals(vanilla)) {
                failures.add("topK(adversarial): set differs at trial=" + trial + " n=" + n
                        + " vanilla=" + vanilla + " kestrel=" + kestrel);
                return;
            }
            // Same multiset of keys in the same sorted order as vanilla's list form.
            List<Integer> vanillaList = candidates.stream()
                    .sorted(Comparator.comparingDouble(keyFn)).limit(5).collect(Collectors.toList());
            List<Double> vanillaKeys = new ArrayList<>();
            for (int i : vanillaList) {
                vanillaKeys.add(keys[i]);
            }
            List<Double> kestrelKeys = new ArrayList<>();
            for (int i : kestrel) {
                kestrelKeys.add(keys[i]);
            }
            if (!vanillaKeys.equals(kestrelKeys)) {
                failures.add("topK(adversarial): key sequence differs at trial=" + trial);
                return;
            }
        }
    }

    /** Key-count contract plus k<=0 / k>=n / empty edges. */
    private static void checkTopKKeysAndEdges(List<String> failures) {
        // Exactly n evaluations on the heap path.
        Random rng = new Random(0xE2C03L);
        for (int trial = 0; trial < 100; trial++) {
            int n = 6 + rng.nextInt(60);
            List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                candidates.add(i);
            }
            AtomicInteger calls = new AtomicInteger();
            PoiTopK.resetKeyEvaluations();
            PoiTopK.selectNearest(candidates, (ToDoubleFunction<Integer>) i -> {
                calls.incrementAndGet();
                return (double) (i * i % 97);
            }, 5);
            if (calls.get() != n || PoiTopK.keyEvaluations() != n) {
                failures.add("topK: expected exactly " + n + " key evaluations, got oracle="
                        + calls.get() + " counter=" + PoiTopK.keyEvaluations());
                return;
            }
        }
        // Edges: empty, k=0, k>=n.
        if (!PoiTopK.<String>selectNearest(List.of(), s -> 1.0, 5).isEmpty()) {
            failures.add("topK: empty input must yield empty output");
            return;
        }
        AtomicInteger zeroCalls = new AtomicInteger();
        if (!PoiTopK.selectNearest(List.of("a", "b"), (ToDoubleFunction<String>) s -> {
            zeroCalls.incrementAndGet();
            return 1.0;
        }, 0).isEmpty() || zeroCalls.get() != 0) {
            failures.add("topK: k=0 must yield empty without evaluating keys");
            return;
        }
        List<String> three = List.of("a", "b", "c");
        List<String> all = PoiTopK.selectNearest(three, s -> (double) s.charAt(0), 5);
        if (!new java.util.HashSet<>(all).equals(new java.util.HashSet<>(three))) {
            failures.add("topK: k>=n must keep every element");
            return;
        }
        // k>=n orders nearest-first like ScanOrder (same contract).
        List<String> rev = new ArrayList<>(three);
        java.util.Collections.reverse(rev);
        List<String> ordered = PoiTopK.selectNearest(rev, s -> (double) s.charAt(0), 99);
        if (!ordered.equals(List.of("a", "b", "c"))) {
            failures.add("topK: k>=n must order nearest-first, got " + ordered);
            return;
        }
        // Negative control guard: limit-5 of 6 distinct keys drops the farthest.
        List<Integer> six = List.of(0, 1, 2, 3, 4, 5);
        Set<Integer> kept = new java.util.HashSet<>(
                PoiTopK.selectNearest(six, (ToDoubleFunction<Integer>) i -> (double) i, 5));
        if (!kept.equals(new java.util.HashSet<>(List.of(0, 1, 2, 3, 4)))) {
            failures.add("topK: must drop the farthest element, kept " + kept);
            return;
        }
    }


    // ---- E3. Secondary-POI volume scan: order identity + empty-set skip ----

    /**
     * Eight pseudo blocks, so a probe set drawn from them tunes hit density
     * from "nothing matches" to "everything matches".
     */
    private static final String[] E3_PALETTE = {
            "air", "dirt", "farmland", "grass", "stone", "water", "wheat", "path"
    };

    /** A pure deterministic world: the same coordinate always yields the same block. */
    private static String e3BlockAt(int x, int y, int z, long salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ (y + salt) * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return E3_PALETTE[(int) Math.floorMod(h, (long) E3_PALETTE.length)];
    }

    /** Records every probe, so both the sequence and the count can be compared. */
    private static final class E3Probe implements SecondaryPoiScan.BlockLookup<String> {
        private final List<long[]> visited = new ArrayList<>();
        private final long salt;
        private int calls;

        E3Probe(long salt) {
            this.salt = salt;
        }

        @Override
        public String blockAt(int x, int y, int z) {
            this.calls++;
            this.visited.add(new long[]{x, y, z});
            return e3BlockAt(x, y, z, this.salt);
        }
    }

    /**
     * Vanilla's formulation, transcribed literally from the decompiled
     * {@code SecondaryPoiSensor.doTick}, including the real {@code BlockPos}
     * and its real {@code offset} arithmetic (so the coordinate identity is
     * proven against the shipped class, int wraparound included).
     */
    private static List<long[]> vanillaSecondaryScan(Set<?> probeSet, BlockPos center,
                                                     SecondaryPoiScan.BlockLookup<String> lookup) {
        List<long[]> jobSites = new ArrayList<>();
        int horizontalSearch = 4;
        for (int x = -4; x <= 4; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos testPos = center.offset(x, y, z);
                    if (probeSet.contains(lookup.blockAt(testPos.getX(), testPos.getY(), testPos.getZ()))) {
                        jobSites.add(new long[]{testPos.getX(), testPos.getY(), testPos.getZ()});
                    }
                }
            }
        }
        return jobSites;
    }

    private static boolean e3SameSequence(List<long[]> a, List<long[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!java.util.Arrays.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Ordinary coordinates plus the int extremes where {@code offset} wraps. */
    private static BlockPos e3Center(Random rng, int trial) {
        switch (trial % 7) {
            case 0:
                return new BlockPos(Integer.MAX_VALUE - 2, 3, 7);
            case 1:
                return new BlockPos(-5, 64, Integer.MAX_VALUE);
            case 2:
                return new BlockPos(Integer.MIN_VALUE, 0, Integer.MIN_VALUE + 2);
            default:
                return new BlockPos(rng.nextInt(4001) - 2000, rng.nextInt(384) - 64, rng.nextInt(4001) - 2000);
        }
    }

    /**
     * The hit list Kestrel reports must be vanilla's, element for element and
     * in vanilla's traversal order, and the set/erase decision must agree.
     */
    private static void checkSecondaryPoiOrderIdentity(List<String> failures) {
        Random rng = new Random(0xE3A01L);
        for (int trial = 0; trial < 600; trial++) {
            List<String> pool = new ArrayList<>(List.of(E3_PALETTE));
            java.util.Collections.shuffle(pool, rng);
            Set<String> probeSet = new LinkedHashSet<>(pool.subList(0, rng.nextInt(E3_PALETTE.length + 1)));

            BlockPos center = e3Center(rng, trial);
            long salt = rng.nextLong();

            List<long[]> expected = vanillaSecondaryScan(probeSet, center, new E3Probe(salt));

            List<long[]> actual = new ArrayList<>();
            boolean any = SecondaryPoiScan.scan(probeSet, center.getX(), center.getY(), center.getZ(),
                    new E3Probe(salt), (x, y, z) -> actual.add(new long[]{x, y, z}));

            if (any != !expected.isEmpty()) {
                failures.add("E3: any-hit flag disagrees with vanilla at trial " + trial
                        + " (kestrel=" + any + " vanillaHits=" + expected.size() + ")");
                return;
            }
            if (!e3SameSequence(expected, actual)) {
                failures.add("E3: hit sequence differs from vanilla at trial " + trial
                        + " (center=" + center + " probeSet=" + probeSet
                        + " vanilla=" + expected.size() + " kestrel=" + actual.size() + ")");
                return;
            }
        }
    }

    /**
     * Probe discipline: for a non-empty probe set Kestrel must read exactly the
     * 405 positions vanilla reads, in vanilla's order, and no more. For an
     * empty probe set it must read NOTHING, which is the whole E3 win: the
     * lookup here throws, so any read at all fails the check rather than merely
     * costing time.
     */
    private static void checkSecondaryPoiSkip(List<String> failures) {
        Random rng = new Random(0xE3B02L);

        if (SecondaryPoiScan.VOLUME != 9 * 5 * 9) {
            failures.add("E3: scan volume is " + SecondaryPoiScan.VOLUME + ", vanilla probes 405 positions");
            return;
        }

        for (int trial = 0; trial < 200; trial++) {
            BlockPos center = e3Center(rng, trial);
            long salt = rng.nextLong();
            Set<String> probeSet = Set.of(E3_PALETTE[rng.nextInt(E3_PALETTE.length)]);

            E3Probe vanillaProbe = new E3Probe(salt);
            vanillaSecondaryScan(probeSet, center, vanillaProbe);

            E3Probe kestrelProbe = new E3Probe(salt);
            SecondaryPoiScan.scan(probeSet, center.getX(), center.getY(), center.getZ(),
                    kestrelProbe, (x, y, z) -> { });

            if (kestrelProbe.calls != SecondaryPoiScan.VOLUME) {
                failures.add("E3: non-empty scan read " + kestrelProbe.calls
                        + " positions, vanilla reads " + SecondaryPoiScan.VOLUME);
                return;
            }
            if (!e3SameSequence(vanillaProbe.visited, kestrelProbe.visited)) {
                failures.add("E3: probe ORDER differs from vanilla at trial " + trial
                        + " (center=" + center + ")");
                return;
            }
        }

        SecondaryPoiScan.BlockLookup<String> poisoned = (x, y, z) -> {
            throw new AssertionError("E3: the empty-probe-set path must not read any block state");
        };
        List<long[]> hits = new ArrayList<>();
        boolean any;
        try {
            any = SecondaryPoiScan.scan(Set.of(), 12, 64, -7, poisoned, (x, y, z) -> hits.add(new long[]{x, y, z}));
        } catch (AssertionError e) {
            failures.add(e.getMessage());
            return;
        }
        if (any || !hits.isEmpty()) {
            failures.add("E3: empty probe set must produce no hits (any=" + any + " hits=" + hits.size() + ")");
            return;
        }

        // The skip is only sound because an empty set really does match nothing,
        // so assert that against the real transcription rather than assuming it.
        E3Probe vanillaEmpty = new E3Probe(1234L);
        List<long[]> vanillaHits = vanillaSecondaryScan(Set.of(), new BlockPos(12, 64, -7), vanillaEmpty);
        if (!vanillaHits.isEmpty()) {
            failures.add("E3: vanilla with an empty probe set produced " + vanillaHits.size() + " hits");
            return;
        }
        if (vanillaEmpty.calls != SecondaryPoiScan.VOLUME) {
            failures.add("E3: transcription read " + vanillaEmpty.calls
                    + " positions for an empty probe set, expected " + SecondaryPoiScan.VOLUME
                    + " (the reads Kestrel elides)");
        }
    }

    /**
     * Grounding for the skip's VALUE, checked against the real registry rather
     * than asserted in prose: the sensor still runs at rate 40 on every
     * villager, and only one registered profession declares a secondary POI, so
     * every other villager is paying 405 block reads per 40 ticks for a
     * guaranteed-empty answer.
     */
    private static void checkSecondaryPoiGrounding(List<String> failures) {
        try {
            SecondaryPoiSensor sensor = new SecondaryPoiSensor();
            Set<MemoryModuleType<?>> vanillaRequires = sensor.requires();
            if (!vanillaRequires.equals(Set.of(MemoryModuleType.SECONDARY_JOB_SITE))) {
                failures.add("E3: SecondaryPoiSensor.requires() is " + vanillaRequires
                        + ", SecondaryPoiScan writes only SECONDARY_JOB_SITE");
            }
            Field scanRate = Sensor.class.getDeclaredField("scanRate");
            scanRate.setAccessible(true);
            int rate = scanRate.getInt(sensor);
            if (rate != 40) {
                failures.add("E3: SecondaryPoiSensor scan rate is " + rate + ", expected 40");
            }
        } catch (ReflectiveOperationException e) {
            failures.add("E3: could not inspect the real SecondaryPoiSensor: " + e);
        }

        byte[] villagerBytes = readClassBytes(net.minecraft.world.entity.npc.villager.Villager.class);
        if (villagerBytes == null
                || !new String(villagerBytes, StandardCharsets.ISO_8859_1).contains("SECONDARY_POIS")) {
            failures.add("E3: Villager no longer references SensorType.SECONDARY_POIS; "
                    + "the 'every villager pays this' premise no longer holds");
        }

        int total = 0;
        List<String> withSecondary = new ArrayList<>();
        for (VillagerProfession profession : BuiltInRegistries.VILLAGER_PROFESSION) {
            total++;
            if (!profession.secondaryPoi().isEmpty()) {
                withSecondary.add(String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession)));
            }
        }
        if (total < 15) {
            failures.add("E3: villager profession registry holds " + total
                    + " entries, expected the 15 vanilla professions (registry not bootstrapped?)");
            return;
        }
        if (withSecondary.size() != 1 || !withSecondary.contains("minecraft:farmer")) {
            failures.add("E3: professions declaring a secondary POI are " + withSecondary
                    + ", expected exactly [minecraft:farmer]");
            return;
        }
        System.out.println("          professions=" + total + " with-secondary-poi=" + withSecondary
                + " -> " + (total - withSecondary.size()) + "/" + total
                + " villager professions skip the 405-read scan entirely");
    }

    // ---- 8. E5: the two per-tick behavior walks ----

    /**
     * Real vanilla {@code Activity} constants, so the inner {@code HashMap}
     * that {@code Brain.addActivity} builds is keyed by the real objects with
     * the real hash codes. Using stand-in keys would make the iteration order
     * under test an artifact of the test.
     */
    /**
     * Method, not a static field, on purpose: {@code Activity}'s constants
     * need the registry bootstrap, which only runs inside {@code main}, while
     * a static initializer would run first and crash with "Not bootstrapped".
     */
    private static Activity[] e5Activities() {
        return new Activity[]{
                Activity.CORE, Activity.IDLE, Activity.WORK, Activity.PLAY,
                Activity.REST, Activity.MEET, Activity.PANIC, Activity.FIGHT,
        };
    }

    /**
     * A scripted {@code BehaviorControl} that records every call made to it.
     *
     * <p>It implements the interface rather than extending {@code Behavior}
     * because {@code Behavior.tryStart} needs a live {@code ServerLevel} for
     * its RNG draw, which is unavailable headless. The real class is exercised
     * separately in {@link #checkBehaviorScheduleGrounding} which proves the
     * two semantics this stand-in encodes: that {@code tryStart} draws on every
     * success, and that {@code tickOrStop} has no {@code RUNNING} guard.
     */
    private static final class E5Behavior implements BehaviorControl<LivingEntity> {
        private final String name;
        private final List<String> log;
        private Behavior.Status status = Behavior.Status.STOPPED;
        /** Whether tryStart succeeds, and therefore whether it draws. */
        private boolean startSucceeds;
        /** Shared-memory side effect at start time: force these to STOPPED. */
        private List<E5Behavior> stopOnStart = List.of();
        /** Shared-memory side effect at tick time: force these to STOPPED. */
        private List<E5Behavior> stopOnTick = List.of();

        E5Behavior(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public Behavior.Status getStatus() {
            return this.status;
        }

        @Override
        public Set<MemoryModuleType<?>> getRequiredMemories() {
            return Set.of();
        }

        @Override
        public boolean tryStart(ServerLevel level, LivingEntity body, long timestamp) {
            this.log.add("tryStart " + this.name + " @" + timestamp);
            if (!this.startSucceeds) {
                return false;
            }
            this.status = Behavior.Status.RUNNING;
            // Vanilla draws level.getRandom().nextInt(max + 1 - min) here, on
            // every success. Record it: the draw SEQUENCE is the parity claim.
            this.log.add("draw " + this.name);
            for (E5Behavior other : this.stopOnStart) {
                other.status = Behavior.Status.STOPPED;
            }
            return true;
        }

        @Override
        public void tickOrStop(ServerLevel level, LivingEntity body, long timestamp) {
            // The status is logged, not just the identity: a behavior stopped
            // part way through the walk must STILL be called, and vanilla will
            // then route it to doStop a second time.
            this.log.add("tickOrStop " + this.name + " status=" + this.status);
            for (E5Behavior other : this.stopOnTick) {
                other.status = Behavior.Status.STOPPED;
            }
        }

        @Override
        public void doStop(ServerLevel level, LivingEntity body, long timestamp) {
            this.status = Behavior.Status.STOPPED;
            this.log.add("doStop " + this.name);
        }

        @Override
        public String debugString() {
            return this.name;
        }
    }

    /** One built trial: a real Brain, its real internal map, and the flattened schedule. */
    private static final class E5Fixture {
        Brain<LivingEntity> brain;
        Map<Integer, Map<Activity, Set<E5Behavior>>> byPriority;
        BehaviorSchedule<E5Behavior, Activity> schedule;
        List<E5Behavior> all = new ArrayList<>();
        List<String> log = new ArrayList<>();
    }

    /**
     * Builds a real {@code Brain} via the real {@code addActivity}, then reads
     * its real {@code availableBehaviorsByPriority} back out and flattens THAT.
     * Nothing about the container types, the key hashing or the insertion order
     * is reconstructed by the test.
     */
    @SuppressWarnings("unchecked")
    private static E5Fixture buildE5Fixture(Random rng, int activityCount, int maxPerActivity,
                                            List<String> failures) {
        E5Fixture fixture = new E5Fixture();
        fixture.brain = new Brain<>();
        int counter = 0;
        for (int a = 0; a < activityCount; a++) {
            Activity activity = e5Activities()[rng.nextInt(8)];
            int priority = rng.nextInt(5);
            int n = 1 + rng.nextInt(maxPerActivity);
            List<Pair<Integer, ? extends BehaviorControl<? super LivingEntity>>> pairs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                E5Behavior behavior = new E5Behavior("b" + (counter++), fixture.log);
                fixture.all.add(behavior);
                pairs.add(Pair.of(priority, behavior));
            }
            fixture.brain.addActivity(activity, ImmutableList.copyOf(pairs), Set.of(), Set.of());
        }
        try {
            Field field = Brain.class.getDeclaredField("availableBehaviorsByPriority");
            field.setAccessible(true);
            fixture.byPriority = (Map<Integer, Map<Activity, Set<E5Behavior>>>) (Map<?, ?>) field.get(fixture.brain);
        } catch (ReflectiveOperationException e) {
            failures.add("E5: could not read Brain.availableBehaviorsByPriority: " + e);
            return null;
        }
        fixture.schedule = BehaviorSchedule.flatten(fixture.byPriority);
        return fixture;
    }

    /** Overwrites the real Brain's active-activity set, which {@code Brain.isActive} reads. */
    @SuppressWarnings("unchecked")
    private static boolean setE5ActiveActivities(Brain<LivingEntity> brain, Set<Activity> active,
                                                 List<String> failures) {
        try {
            Field field = Brain.class.getDeclaredField("activeActivities");
            field.setAccessible(true);
            Set<Activity> live = (Set<Activity>) field.get(brain);
            live.clear();
            live.addAll(active);
            return true;
        } catch (ReflectiveOperationException e) {
            failures.add("E5: could not read Brain.activeActivities: " + e);
            return false;
        }
    }

    /** Literal transcription of 26.2 {@code Brain.startEachNonRunningBehavior}. */
    private static void vanillaStartEachNonRunning(Map<Integer, Map<Activity, Set<E5Behavior>>> byPriority,
                                                   Set<Activity> activeActivities, long time) {
        for (Map<Activity, Set<E5Behavior>> behavioursByActivities : byPriority.values()) {
            for (Map.Entry<Activity, Set<E5Behavior>> behavioursForActivity : behavioursByActivities.entrySet()) {
                Activity activity = behavioursForActivity.getKey();
                if (activeActivities.contains(activity)) {
                    for (E5Behavior behavior : behavioursForActivity.getValue()) {
                        if (behavior.getStatus() == Behavior.Status.STOPPED) {
                            behavior.tryStart(null, null, time);
                        }
                    }
                }
            }
        }
    }

    /** Literal transcription of 26.2 {@code Brain.getRunningBehaviors}, fastutil list included. */
    private static List<E5Behavior> vanillaGetRunningBehaviors(
            Map<Integer, Map<Activity, Set<E5Behavior>>> byPriority) {
        List<E5Behavior> runningBehaviours = new ObjectArrayList<>();
        for (Map<Activity, Set<E5Behavior>> behavioursByActivities : byPriority.values()) {
            for (Set<E5Behavior> behaviors : behavioursByActivities.values()) {
                for (E5Behavior behavior : behaviors) {
                    if (behavior.getStatus() == Behavior.Status.RUNNING) {
                        runningBehaviours.add(behavior);
                    }
                }
            }
        }
        return runningBehaviours;
    }

    /** Literal transcription of 26.2 {@code Brain.tickEachRunningBehavior}. */
    private static void vanillaTickEachRunning(Map<Integer, Map<Activity, Set<E5Behavior>>> byPriority,
                                               long timestamp) {
        for (E5Behavior behavior : vanillaGetRunningBehaviors(byPriority)) {
            behavior.tickOrStop(null, null, timestamp);
        }
    }

    /**
     * The tempting wrong version: walk once and re-check the status inline,
     * which is what a maintained dirty-flagged running-set amounts to. Kept as
     * a NEGATIVE CONTROL. If this ever stops differing from vanilla, the
     * adversarial script has lost its teeth and the checks below prove nothing.
     */
    private static void naiveFusedTickEachRunning(Map<Integer, Map<Activity, Set<E5Behavior>>> byPriority,
                                                  long timestamp) {
        for (Map<Activity, Set<E5Behavior>> behavioursByActivities : byPriority.values()) {
            for (Set<E5Behavior> behaviors : behavioursByActivities.values()) {
                for (E5Behavior behavior : behaviors) {
                    if (behavior.getStatus() == Behavior.Status.RUNNING) {
                        behavior.tickOrStop(null, null, timestamp);
                    }
                }
            }
        }
    }

    private static Behavior.Status[] snapshotStatuses(List<E5Behavior> all) {
        Behavior.Status[] statuses = new Behavior.Status[all.size()];
        for (int i = 0; i < all.size(); i++) {
            statuses[i] = all.get(i).status;
        }
        return statuses;
    }

    private static void restoreStatuses(List<E5Behavior> all, Behavior.Status[] statuses) {
        for (int i = 0; i < all.size(); i++) {
            all.get(i).status = statuses[i];
        }
    }

    /**
     * The start walk, differentially: identical {@code tryStart} call sequence,
     * identical simulated RNG draw sequence, identical resulting statuses.
     *
     * <p>The draw sequence is the strict claim. {@code Behavior.tryStart} draws
     * {@code level.getRandom().nextInt(...)} on every success, so reordering
     * two starts, or starting one extra behavior, desynchronises the level RNG
     * for the rest of the tick and everything downstream of it.
     */
    private static void checkBehaviorStartWalkIdentity(List<String> failures) {
        Random rng = new Random(0xE5A11L);
        for (int trial = 0; trial < 400; trial++) {
            E5Fixture fixture = buildE5Fixture(rng, 1 + rng.nextInt(6), 4, failures);
            if (fixture == null) {
                return;
            }
            Set<Activity> active = new LinkedHashSet<>();
            for (Activity activity : e5Activities()) {
                if (rng.nextBoolean()) {
                    active.add(activity);
                }
            }
            if (!setE5ActiveActivities(fixture.brain, active, failures)) {
                return;
            }
            for (E5Behavior behavior : fixture.all) {
                behavior.status = rng.nextInt(3) == 0 ? Behavior.Status.RUNNING : Behavior.Status.STOPPED;
                behavior.startSucceeds = rng.nextBoolean();
                if (rng.nextInt(4) == 0 && !fixture.all.isEmpty()) {
                    behavior.stopOnStart = List.of(fixture.all.get(rng.nextInt(fixture.all.size())));
                }
            }
            Behavior.Status[] initial = snapshotStatuses(fixture.all);
            long time = rng.nextInt(100000);

            vanillaStartEachNonRunning(fixture.byPriority, active, time);
            List<String> vanillaLog = new ArrayList<>(fixture.log);
            Behavior.Status[] vanillaFinal = snapshotStatuses(fixture.all);

            fixture.log.clear();
            restoreStatuses(fixture.all, initial);
            fixture.schedule.startEachNonRunningBehavior(
                    fixture.brain::isActive,
                    b -> b.getStatus() == Behavior.Status.STOPPED,
                    b -> b.tryStart(null, null, time));
            List<String> kestrelLog = new ArrayList<>(fixture.log);
            Behavior.Status[] kestrelFinal = snapshotStatuses(fixture.all);

            if (!vanillaLog.equals(kestrelLog)) {
                failures.add("E5 start walk trial " + trial + ": call/draw sequence differs\n"
                        + "   vanilla=" + vanillaLog + "\n   kestrel=" + kestrelLog);
                return;
            }
            if (!java.util.Arrays.equals(vanillaFinal, kestrelFinal)) {
                failures.add("E5 start walk trial " + trial + ": resulting statuses differ");
                return;
            }
        }
    }

    /**
     * The tick walk, differentially, with an adversarial script: some behaviors
     * stop OTHER behaviors when ticked, which is exactly the case where a
     * re-checking single walk diverges.
     *
     * <p>Also asserts that {@code runningBehaviors()} reproduces the REAL
     * {@code Brain.getRunningBehaviors()} element for element, by identity.
     */
    private static void checkBehaviorTickWalkIdentity(List<String> failures) {
        Random rng = new Random(0xE5B22L);
        int divergences = 0;
        for (int trial = 0; trial < 400; trial++) {
            E5Fixture fixture = buildE5Fixture(rng, 1 + rng.nextInt(6), 4, failures);
            if (fixture == null) {
                return;
            }
            for (E5Behavior behavior : fixture.all) {
                behavior.status = rng.nextBoolean() ? Behavior.Status.RUNNING : Behavior.Status.STOPPED;
                if (rng.nextInt(2) == 0 && !fixture.all.isEmpty()) {
                    List<E5Behavior> victims = new ArrayList<>();
                    int n = 1 + rng.nextInt(3);
                    for (int i = 0; i < n; i++) {
                        victims.add(fixture.all.get(rng.nextInt(fixture.all.size())));
                    }
                    behavior.stopOnTick = victims;
                }
            }
            Behavior.Status[] initial = snapshotStatuses(fixture.all);
            long timestamp = rng.nextInt(100000);

            // The running snapshot itself, against the real Brain method.
            List<BehaviorControl<? super LivingEntity>> realRunning = fixture.brain.getRunningBehaviors();
            List<E5Behavior> kestrelRunning =
                    fixture.schedule.runningBehaviors(b -> b.getStatus() == Behavior.Status.RUNNING);
            if (realRunning.size() != kestrelRunning.size()) {
                failures.add("E5 tick walk trial " + trial + ": running set size "
                        + kestrelRunning.size() + " vs real Brain " + realRunning.size());
                return;
            }
            for (int i = 0; i < realRunning.size(); i++) {
                if (realRunning.get(i) != kestrelRunning.get(i)) {
                    failures.add("E5 tick walk trial " + trial + ": running set order differs at " + i
                            + " (real Brain.getRunningBehaviors vs BehaviorSchedule.runningBehaviors)");
                    return;
                }
            }

            vanillaTickEachRunning(fixture.byPriority, timestamp);
            List<String> vanillaLog = new ArrayList<>(fixture.log);
            Behavior.Status[] vanillaFinal = snapshotStatuses(fixture.all);

            fixture.log.clear();
            restoreStatuses(fixture.all, initial);
            fixture.schedule.tickEachRunningBehavior(
                    b -> b.getStatus() == Behavior.Status.RUNNING,
                    b -> b.tickOrStop(null, null, timestamp));
            List<String> kestrelLog = new ArrayList<>(fixture.log);
            Behavior.Status[] kestrelFinal = snapshotStatuses(fixture.all);

            if (!vanillaLog.equals(kestrelLog)) {
                failures.add("E5 tick walk trial " + trial + ": tickOrStop sequence differs\n"
                        + "   vanilla=" + vanillaLog + "\n   kestrel=" + kestrelLog);
                return;
            }
            if (!java.util.Arrays.equals(vanillaFinal, kestrelFinal)) {
                failures.add("E5 tick walk trial " + trial + ": resulting statuses differ");
                return;
            }

            // Negative control: the inline-recheck version on the same input.
            fixture.log.clear();
            restoreStatuses(fixture.all, initial);
            naiveFusedTickEachRunning(fixture.byPriority, timestamp);
            if (!fixture.log.equals(vanillaLog)) {
                divergences++;
            }
        }
        if (divergences == 0) {
            failures.add("E5: the inline-recheck negative control never diverged from vanilla in 400 "
                    + "adversarial trials; the test has no teeth, so the snapshot claim is unproven");
            return;
        }
        System.out.println("          E5 negative control: inline-recheck diverged from vanilla in "
                + divergences + "/400 trials (the materialized snapshot is load-bearing)");
    }

    /**
     * The strict-subset claim, proven rather than asserted: a RUNNING behavior
     * inside an INACTIVE activity is skipped by the start walk and must still
     * be visited by the tick walk. That is why the start walk cannot collect
     * the running set as a by-product.
     */
    @SuppressWarnings("unchecked")
    private static void checkRunningBehaviorInInactiveActivity(List<String> failures) {
        List<String> log = new ArrayList<>();
        Brain<LivingEntity> brain = new Brain<>();
        E5Behavior inWork = new E5Behavior("work", log);
        E5Behavior inIdle = new E5Behavior("idle", log);
        brain.addActivity(Activity.WORK, ImmutableList.of(Pair.of(0, inWork)), Set.of(), Set.of());
        brain.addActivity(Activity.IDLE, ImmutableList.of(Pair.of(1, inIdle)), Set.of(), Set.of());

        Map<Integer, Map<Activity, Set<E5Behavior>>> byPriority;
        try {
            Field field = Brain.class.getDeclaredField("availableBehaviorsByPriority");
            field.setAccessible(true);
            byPriority = (Map<Integer, Map<Activity, Set<E5Behavior>>>) (Map<?, ?>) field.get(brain);
        } catch (ReflectiveOperationException e) {
            failures.add("E5: could not read Brain.availableBehaviorsByPriority: " + e);
            return;
        }
        BehaviorSchedule<E5Behavior, Activity> schedule = BehaviorSchedule.flatten(byPriority);

        // WORK is running; then the activity is deactivated the vanilla way.
        inWork.status = Behavior.Status.RUNNING;
        if (!setE5ActiveActivities(brain, Set.of(Activity.WORK), failures)) {
            return;
        }
        brain.setDefaultActivity(Activity.IDLE);
        brain.useDefaultActivity();
        if (brain.isActive(Activity.WORK)) {
            failures.add("E5: useDefaultActivity did not deactivate WORK");
            return;
        }
        if (inWork.status != Behavior.Status.RUNNING) {
            failures.add("E5: deactivating an activity now stops its behaviors; the premise that "
                    + "walk 1's visited set is a strict subset of walk 2's no longer holds");
            return;
        }

        log.clear();
        schedule.startEachNonRunningBehavior(brain::isActive,
                b -> b.getStatus() == Behavior.Status.STOPPED,
                b -> b.tryStart(null, null, 7L));
        if (log.contains("tryStart work @7")) {
            failures.add("E5: the start walk descended into an inactive activity");
            return;
        }

        log.clear();
        schedule.tickEachRunningBehavior(b -> b.getStatus() == Behavior.Status.RUNNING,
                b -> b.tickOrStop(null, null, 7L));
        if (!log.equals(List.of("tickOrStop work status=RUNNING"))) {
            failures.add("E5: the tick walk skipped a RUNNING behavior in an inactive activity; got " + log);
        }
    }

    /**
     * The reusable snapshot buffer must never escape and must never leak.
     */
    @SuppressWarnings("unchecked")
    private static void checkSnapshotBufferHygiene(List<String> failures) {
        List<String> log = new ArrayList<>();
        Brain<LivingEntity> brain = new Brain<>();
        List<E5Behavior> all = new ArrayList<>();
        List<Pair<Integer, ? extends BehaviorControl<? super LivingEntity>>> pairs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            E5Behavior behavior = new E5Behavior("h" + i, log);
            behavior.status = i % 2 == 0 ? Behavior.Status.RUNNING : Behavior.Status.STOPPED;
            all.add(behavior);
            pairs.add(Pair.of(0, behavior));
        }
        brain.addActivity(Activity.IDLE, ImmutableList.copyOf(pairs), Set.of(), Set.of());
        Map<Integer, Map<Activity, Set<E5Behavior>>> byPriority;
        Object[] buffer;
        BehaviorSchedule<E5Behavior, Activity> schedule;
        try {
            Field field = Brain.class.getDeclaredField("availableBehaviorsByPriority");
            field.setAccessible(true);
            byPriority = (Map<Integer, Map<Activity, Set<E5Behavior>>>) (Map<?, ?>) field.get(brain);
            schedule = BehaviorSchedule.flatten(byPriority);
            Field snapshotField = BehaviorSchedule.class.getDeclaredField("snapshot");
            snapshotField.setAccessible(true);
            buffer = (Object[]) snapshotField.get(schedule);
        } catch (ReflectiveOperationException e) {
            failures.add("E5: could not inspect the snapshot buffer: " + e);
            return;
        }

        List<E5Behavior> first = schedule.runningBehaviors(b -> b.getStatus() == Behavior.Status.RUNNING);
        List<E5Behavior> second = schedule.runningBehaviors(b -> b.getStatus() == Behavior.Status.RUNNING);
        if (first == second) {
            failures.add("E5: runningBehaviors returned the same list twice; "
                    + "vanilla's getRunningBehaviors is public and hands back a fresh ObjectArrayList");
            return;
        }
        first.clear();
        if (schedule.runningBehaviors(b -> b.getStatus() == Behavior.Status.RUNNING).size() != 3) {
            failures.add("E5: clearing a returned list changed the schedule; the buffer escaped");
            return;
        }

        BehaviorSchedule.resetCounters();
        schedule.tickEachRunningBehavior(b -> b.getStatus() == Behavior.Status.RUNNING,
                b -> b.tickOrStop(null, null, 1L));
        schedule.tickEachRunningBehavior(b -> b.getStatus() == Behavior.Status.RUNNING,
                b -> b.tickOrStop(null, null, 2L));
        if (BehaviorSchedule.snapshotFills() != 2) {
            failures.add("E5: snapshotFills=" + BehaviorSchedule.snapshotFills() + " after two ticks");
        }
        Object[] bufferAfter;
        try {
            Field snapshotField = BehaviorSchedule.class.getDeclaredField("snapshot");
            snapshotField.setAccessible(true);
            bufferAfter = (Object[]) snapshotField.get(schedule);
        } catch (ReflectiveOperationException e) {
            failures.add("E5: could not re-read the snapshot buffer: " + e);
            return;
        }
        if (bufferAfter != buffer) {
            failures.add("E5: the snapshot buffer was reallocated; the whole point is that it is not");
            return;
        }
        for (int i = 0; i < bufferAfter.length; i++) {
            if (bufferAfter[i] != null) {
                failures.add("E5: snapshot buffer slot " + i + " still pins " + bufferAfter[i]
                        + " after the tick walk");
                return;
            }
        }
    }

    /**
     * Grounding for the two semantics the stand-in encodes, checked against the
     * REAL {@code Behavior} and {@code Brain} classes rather than against the
     * decompiled text.
     */
    private static void checkBehaviorScheduleGrounding(List<String> failures) {
        // 1. The interface shape the schedule is written against.
        Set<String> declared = new java.util.TreeSet<>();
        for (java.lang.reflect.Method m : BehaviorControl.class.getDeclaredMethods()) {
            declared.add(m.getName());
        }
        Set<String> expected = new java.util.TreeSet<>(List.of(
                "getStatus", "getRequiredMemories", "tryStart", "tickOrStop", "doStop", "debugString"));
        if (!declared.equals(expected)) {
            failures.add("E5: BehaviorControl declares " + declared + ", expected " + expected);
        }

        // 2. tryStart/tickOrStop/doStop are final on Behavior, so no subclass
        //    can change the semantics proven below.
        try {
            for (String name : List.of("tryStart", "tickOrStop", "doStop")) {
                java.lang.reflect.Method m = Behavior.class.getDeclaredMethod(
                        name, ServerLevel.class, LivingEntity.class, long.class);
                if (!Modifier.isFinal(m.getModifiers())) {
                    failures.add("E5: Behavior." + name + " is no longer final");
                }
            }
        } catch (ReflectiveOperationException e) {
            failures.add("E5: could not reflect on Behavior: " + e);
        }

        // 3. tickOrStop has NO status guard: a STOPPED behavior still reaches
        //    doStop and fires stop() again. This is why the snapshot must be
        //    materialized before it is walked.
        int[] stops = {0};
        Behavior<LivingEntity> noGuard = new Behavior<LivingEntity>(Map.of(), 60, 60) {
            @Override
            protected boolean canStillUse(ServerLevel level, LivingEntity body, long timestamp) {
                return false;
            }

            @Override
            protected void stop(ServerLevel level, LivingEntity body, long timestamp) {
                stops[0]++;
            }
        };
        if (noGuard.getStatus() != Behavior.Status.STOPPED) {
            failures.add("E5: a fresh Behavior is not STOPPED");
        }
        noGuard.tickOrStop(null, null, 0L);
        if (stops[0] != 1) {
            failures.add("E5: tickOrStop on a STOPPED behavior fired stop() " + stops[0]
                    + " times, expected 1; vanilla has no RUNNING guard there and Kestrel relies on it");
        }

        // 4. tryStart draws from the level RNG on EVERY success, including when
        //    minDuration == maxDuration (nextInt(1) still advances nothing
        //    visible but is still a call). Proven by where it NPEs on a null
        //    level: the draw happens before start() is ever reached.
        boolean[] started = {false};
        Behavior<LivingEntity> drawer = new Behavior<LivingEntity>(Map.of(), 60, 60) {
            @Override
            protected void start(ServerLevel level, LivingEntity body, long timestamp) {
                started[0] = true;
            }
        };
        try {
            drawer.tryStart(null, null, 0L);
            failures.add("E5: Behavior.tryStart succeeded without touching the level; "
                    + "the RNG-draw-per-start parity constraint no longer holds");
        } catch (NullPointerException e) {
            StackTraceElement top = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
            if (top == null || !top.getMethodName().equals("tryStart")
                    || !top.getClassName().equals(Behavior.class.getName())) {
                failures.add("E5: tryStart NPE came from " + top + ", expected Behavior.tryStart");
            }
            String message = e.getMessage();
            if (message != null && !message.contains("getRandom")) {
                failures.add("E5: tryStart NPE was '" + message + "', expected the level.getRandom() call site");
            }
            if (started[0]) {
                failures.add("E5: start() ran before the RNG draw; the draw ordering assumption is wrong");
            }
            if (drawer.getStatus() != Behavior.Status.RUNNING) {
                failures.add("E5: tryStart did not set RUNNING before drawing");
            }
        }

        // 5. Brain still has the two walks, and still uses the container types
        //    whose iteration order flatten() snapshots.
        try {
            Brain.class.getDeclaredMethod("startEachNonRunningBehavior", ServerLevel.class, LivingEntity.class);
            Brain.class.getDeclaredMethod("tickEachRunningBehavior", ServerLevel.class, LivingEntity.class);
            Brain.class.getDeclaredMethod("getRunningBehaviors");
        } catch (ReflectiveOperationException e) {
            failures.add("E5: Brain no longer declares the expected walk methods: " + e);
        }
        byte[] brainBytes = readClassBytes(Brain.class);
        if (brainBytes == null) {
            failures.add("E5: could not read Brain class bytes");
        } else {
            String constants = new String(brainBytes, StandardCharsets.ISO_8859_1);
            for (String factory : List.of("newTreeMap", "newHashMap", "newLinkedHashSet")) {
                if (!constants.contains(factory)) {
                    failures.add("E5: Brain no longer references Maps/Sets." + factory
                            + "; the behavior structure's iteration order may have changed");
                }
            }
        }

        // 6. The structure really is build-once. Guava's @VisibleForTesting has
        //    CLASS retention so it is invisible to reflection; check that the
        //    only two mutators are still the two known ones, and that the
        //    test-only marker is still present in the class file at all.
        try {
            Brain.class.getDeclaredMethod("removeAllBehaviors");
        } catch (ReflectiveOperationException e) {
            failures.add("E5: Brain.removeAllBehaviors is gone: " + e);
        }
        if (brainBytes != null
                && !new String(brainBytes, StandardCharsets.ISO_8859_1).contains("VisibleForTesting")) {
            failures.add("E5: Brain no longer carries a @VisibleForTesting marker; "
                    + "removeAllBehaviors may have become production API, which would make "
                    + "flattening once at construction unsafe");
        }
    }

    private static byte[] readClassBytes(Class<?> cls) {
        String resource = "/" + cls.getName().replace('.', '/') + ".class";
        try (InputStream in = MobAiProofCheck.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }
}
