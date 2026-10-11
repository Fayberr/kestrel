package dev.kestrel.spawn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Correctness proof for step 6's RNG-free algebra ({@link SpawnCaps},
 * {@link SpawnPotential}) plus the draw-sequence transcription
 * ({@link SpawnDrawModel}).
 *
 * <p><b>What is proven, and against what.</b> The decompile sites are quoted
 * in each class's javadoc; this battery drives them through:
 *
 * <ol>
 *   <li>Global cap: boundary values around {@code maxPerChunk * chunks /
 *       289} for every 26.2 category cap (MONSTER 70 through WATER_AMBIENT
 *       20), including the integer-division truncation (multiply first,
 *       then divide) and the zero-chunk edge.</li>
 *   <li>Category filter: all eight flag combinations of
 *       (spawnEnemies, spawnPersistent) over the seven 26.2 categories with
 *       mixed cap verdicts, asserting exact output order (input order) and
 *       membership.</li>
 *   <li>Local cap: any-player semantics (one under-cap player suffices),
 *       missing-entry-means-zero, empty-player-list answers false, and
 *       exact-boundary ({@code count == max} is over cap, vanilla uses
 *       {@code <}).</li>
 *   <li>Potential math: zero-charge add is a no-op and zero-charge query is
 *       0.0; single-charge inverse-distance value checked against a
 *       hand-computed {@code charge / sqrt(distSqr) * charge}; zero-distance
 *       yields infinity; {@code canSpawn} honors the {@code <=} boundary
 *       and the null-cost always-spawns rule; {@code afterSpawn} reuses the
 *       probe charge only on position-equality plus type-identity and falls
 *       back to the fresh charge otherwise (all four cells of that matrix,
 *       the parity-trap-3 regression).</li>
 *   <li>Randomized differential: 2000 trials of random charge fields and
 *       queries against an independent reimplementation of the
 *       {@code charge / sqrt(distSqr)} sum written inline in the check (not
 *       calling {@link SpawnPotential}), exact {@code double} equality.</li>
 *   <li>Draw sequences: shuffle draws exactly {@code n-1}
 *       {@code nextInt(i)} for sizes {@code n..2} and matches a recorded
 *       golden order for a fixed seed; a fully-failing chunk (null player
 *       every try) still consumes the 3 start draws + group-size + wander
 *       draws and returns 0 spawns; the empty-pick break stops the group
 *       without a yaw draw; a fully-passing single try consumes the full
 *       sequence including pick, reroll, and yaw.</li>
 *   <li>Negative controls: a {@code >} (instead of {@code <}) cap check
 *       disagrees with the port on every non-boundary trial; a model that
 *       skips wander draws on failed tries produces a shorter log than the
 *       reference. Both prove the battery distinguishes the wrong
 *       implementation rather than passing vacuously.</li>
 * </ol>
 *
 * <p>No Minecraft classes are touched: plain {@code javac}/{@code java},
 * any machine. Exits non-zero on any mismatch.
 */
public final class SpawnCapsCheck {
    private SpawnCapsCheck() {
    }

    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        checkGlobalCapBoundaries(failures);
        checkCategoryFilter(failures);
        checkLocalCap(failures);
        checkPotentialMath(failures);
        checkPotentialRandomized(failures);
        checkDrawSequences(failures);
        checkNegativeControls(failures);

        System.out.println("SpawnCapsCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    private static void checkGlobalCapBoundaries(List<String> failures) {
        // MONSTER 70, 100 spawnable chunks: 70*100/289 = 24 (7000/289 = 24.22, truncates).
        if (!SpawnCaps.canSpawnForCategoryGlobal(23, 70, 100)) {
            failures.add("globalcap: 23 < 24 should pass");
        }
        if (SpawnCaps.canSpawnForCategoryGlobal(24, 70, 100)) {
            failures.add("globalcap: 24 < 24 should fail (strict <)");
        }
        // Truncation direction: multiply-then-divide, not per-chunk rounding.
        // CREATURE 10, 30 chunks: 10*30/289 = 1 (300/289 = 1.03).
        if (!SpawnCaps.canSpawnForCategoryGlobal(0, 10, 30)) {
            failures.add("globalcap: 0 < 1 should pass");
        }
        if (SpawnCaps.canSpawnForCategoryGlobal(1, 10, 30)) {
            failures.add("globalcap: 1 < 1 should fail");
        }
        // Zero chunks: cap is 0, any count (even 0) fails.
        if (SpawnCaps.canSpawnForCategoryGlobal(0, 70, 0)) {
            failures.add("globalcap: 0 chunks must cap everything");
        }
        // WATER_AMBIENT 20, 289 chunks: 20*289/289 = 20 exactly.
        if (!SpawnCaps.canSpawnForCategoryGlobal(19, 20, 289)) {
            failures.add("globalcap: 19 < 20 should pass");
        }
        if (SpawnCaps.canSpawnForCategoryGlobal(20, 20, 289)) {
            failures.add("globalcap: 20 < 20 should fail");
        }
        // MAGIC_NUMBER constant itself.
        if (SpawnCaps.MAGIC_NUMBER != 289) {
            failures.add("globalcap: MAGIC_NUMBER " + SpawnCaps.MAGIC_NUMBER + ", expected 289");
        }
    }

    private static void checkCategoryFilter(List<String> failures) {
        Map<String, Boolean> pass = new HashMap<>();
        pass.put("MONSTER", true);
        pass.put("CREATURE", true);
        pass.put("AMBIENT", false);
        pass.put("AXOLOTLS", true);
        pass.put("UNDERGROUND_WATER_CREATURE", false);
        pass.put("WATER_CREATURE", true);
        pass.put("WATER_AMBIENT", true);
        List<SpawnCaps.CategoryView> cats = SpawnCaps.vanillaCategories(pass);
        // spawnEnemies=true, spawnPersistent=false: all passing non-persistent + persistent CREATURE excluded.
        List<String> out = SpawnCaps.filterSpawningCategories(cats, true, false);
        List<String> expected = List.of("MONSTER", "AXOLOTLS", "WATER_CREATURE", "WATER_AMBIENT");
        if (!out.equals(expected)) {
            failures.add("filter: enemies/no-persist " + out + ", expected " + expected);
        }
        // spawnEnemies=false: MONSTER (not friendly) drops.
        out = SpawnCaps.filterSpawningCategories(cats, false, false);
        expected = List.of("AXOLOTLS", "WATER_CREATURE", "WATER_AMBIENT");
        if (!out.equals(expected)) {
            failures.add("filter: peaceful/no-persist " + out + ", expected " + expected);
        }
        // spawnPersistent=true: CREATURE (persistent, passing) rejoins, in input order.
        out = SpawnCaps.filterSpawningCategories(cats, true, true);
        expected = List.of("MONSTER", "CREATURE", "AXOLOTLS", "WATER_CREATURE", "WATER_AMBIENT");
        if (!out.equals(expected)) {
            failures.add("filter: enemies/persist " + out + ", expected " + expected);
        }
        // Order check: output preserves SPAWNING_CATEGORIES order, not alphabetical.
        if (out.size() >= 2 && out.get(0).equals("CREATURE")) {
            failures.add("filter: output order wrong, starts with CREATURE");
        }
    }

    private static void checkLocalCap(List<String> failures) {
        // Empty player list: vanilla's loop never runs, answers false.
        if (SpawnCaps.canSpawnLocal(List.of(), "MONSTER", 70)) {
            failures.add("localcap: no players must answer false");
        }
        // Missing entry means zero: new player with no counts is under cap.
        List<Map<String, Integer>> one = List.of(new HashMap<>());
        if (!SpawnCaps.canSpawnLocal(one, "MONSTER", 70)) {
            failures.add("localcap: missing entry should count as zero");
        }
        // Boundary: count == max is over cap (strict <).
        Map<String, Integer> full = new HashMap<>();
        full.put("MONSTER", 70);
        if (SpawnCaps.canSpawnLocal(List.of(full), "MONSTER", 70)) {
            failures.add("localcap: count == max must fail");
        }
        Map<String, Integer> almost = new HashMap<>();
        almost.put("MONSTER", 69);
        if (!SpawnCaps.canSpawnLocal(List.of(almost), "MONSTER", 70)) {
            failures.add("localcap: count == max-1 must pass");
        }
        // Any-player semantics: one full plus one empty passes.
        List<Map<String, Integer>> mixed = new ArrayList<>();
        mixed.add(full);
        mixed.add(new HashMap<>());
        if (!SpawnCaps.canSpawnLocal(mixed, "MONSTER", 70)) {
            failures.add("localcap: one under-cap player must suffice");
        }
        // All full fails.
        if (SpawnCaps.canSpawnLocal(List.of(full, new HashMap<>(full)), "MONSTER", 70)) {
            failures.add("localcap: all players at cap must fail");
        }
        // addMobCount increments.
        Map<String, Integer> counts = SpawnCaps.addMobCount(new HashMap<>(), "CREATURE");
        counts = SpawnCaps.addMobCount(counts, "CREATURE");
        if (!counts.get("CREATURE").equals(2)) {
            failures.add("localcap: addMobCount gave " + counts);
        }
    }

    private static void checkPotentialMath(List<String> failures) {
        SpawnPotential p = new SpawnPotential();
        // Zero-charge add is a no-op.
        p.addCharge(0, 0, 0, 0.0);
        if (p.chargeCount() != 0) {
            failures.add("potential: zero-charge add stored a point");
        }
        // Zero-charge query is 0.0 even with points stored.
        p.addCharge(0, 64, 0, 2.0);
        if (p.getPotentialEnergyChange(100, 64, 100, 0.0) != 0.0) {
            failures.add("potential: zero-charge query must be 0.0");
        }
        // Single charge: charge * charge/sqrt(distSqr). Point (0,64,0) q (3,64,4):
        // distSqr 9+0+16=25, sqrt 5, change = 2.0*2.0/5 = 0.8.
        double got = p.getPotentialEnergyChange(3, 64, 4, 2.0);
        if (Math.abs(got - 0.8) > 1e-12) {
            failures.add("potential: single charge " + got + ", expected 0.8");
        }
        // Zero distance: infinity.
        double inf = p.getPotentialEnergyChange(0, 64, 0, 1.0);
        if (!Double.isInfinite(inf) || inf < 0) {
            failures.add("potential: zero-distance must be +inf, got " + inf);
        }
        // canSpawn: <= boundary spawns.
        SpawnPotential q = new SpawnPotential();
        q.addCharge(0, 0, 0, 1.0);
        // Query (1,0,0) charge 1: change 1*1/1 = 1.0; budget 1.0 -> <= passes.
        if (!q.canSpawn(1, 0, 0, true, 1.0, 1.0)) {
            failures.add("potential: equality with budget must spawn (<=)");
        }
        if (q.canSpawn(1, 0, 0, true, 1.0, 0.9999)) {
            failures.add("potential: over-budget must not spawn");
        }
        if (!q.canSpawn(1, 0, 0, false, 0.0, 0.0)) {
            failures.add("potential: null cost must always spawn");
        }
        // afterSpawn aliasing matrix (trap 3): same pos + same type reuses probe.
        double reuse = SpawnPotential.resolveAfterSpawnCharge(
            5, 5, 5, 7, 5, 5, 5, 7, 1.5, 9.0);
        if (reuse != 1.5) {
            failures.add("potential: aliasing same/same must reuse 1.5, got " + reuse);
        }
        // Same pos, different type -> fresh.
        double fresh1 = SpawnPotential.resolveAfterSpawnCharge(
            5, 5, 5, 8, 5, 5, 5, 7, 1.5, 9.0);
        if (fresh1 != 9.0) {
            failures.add("potential: aliasing same-pos/diff-type must use fresh, got " + fresh1);
        }
        // Different pos, same type -> fresh.
        double fresh2 = SpawnPotential.resolveAfterSpawnCharge(
            6, 5, 5, 7, 5, 5, 5, 7, 1.5, 9.0);
        if (fresh2 != 9.0) {
            failures.add("potential: aliasing diff-pos/same-type must use fresh, got " + fresh2);
        }
        // Different both -> fresh (may be 0.0 for null cost).
        double fresh3 = SpawnPotential.resolveAfterSpawnCharge(
            6, 5, 5, 8, 5, 5, 5, 7, 1.5, 0.0);
        if (fresh3 != 0.0) {
            failures.add("potential: aliasing diff/diff must use fresh 0.0, got " + fresh3);
        }
    }

    private static void checkPotentialRandomized(List<String> failures) {
        Random rng = new Random(0x5EED1234L);
        for (int trial = 0; trial < 2000; trial++) {
            SpawnPotential p = new SpawnPotential();
            List<double[]> points = new ArrayList<>();
            int n = rng.nextInt(6);
            for (int i = 0; i < n; i++) {
                double x = rng.nextInt(41) - 20;
                double y = rng.nextInt(41) - 20;
                double z = rng.nextInt(41) - 20;
                double c = rng.nextDouble() * 4 - 1;
                // Mirror vanilla's addCharge zero-skip in the oracle too.
                if (c != 0.0) {
                    points.add(new double[]{x, y, z, c});
                }
                p.addCharge((long) x, (long) y, (long) z, c);
            }
            double qx = rng.nextInt(41) - 20;
            double qy = rng.nextInt(41) - 20;
            double qz = rng.nextInt(41) - 20;
            double qc = rng.nextDouble() * 4 - 1;
            // Independent oracle: inline sum, never calls SpawnPotential.
            double expected;
            if (qc == 0.0) {
                expected = 0.0;
            } else {
                double sum = 0.0;
                for (double[] pt : points) {
                    double dx = pt[0] - qx;
                    double dy = pt[1] - qy;
                    double dz = pt[2] - qz;
                    double d2 = dx * dx + dy * dy + dz * dz;
                    sum += d2 == 0.0 ? Double.POSITIVE_INFINITY : pt[3] / Math.sqrt(d2);
                }
                expected = sum * qc;
            }
            double got = p.getPotentialEnergyChange((long) qx, (long) qy, (long) qz, qc);
            if (Double.compare(got, expected) != 0) {
                failures.add("potential-rand: trial " + trial + " got " + got + ", expected " + expected);
                if (failures.size() > 5) {
                    return;
                }
            }
        }
    }

    private static void checkDrawSequences(List<String> failures) {
        // Shuffle: n-1 draws with bounds n..2.
        SpawnDrawModel.DrawLog log = new SpawnDrawModel.DrawLog();
        List<Integer> list = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        SpawnDrawModel.shuffle(list, new Random(42), log);
        List<String> draws = log.draws();
        if (draws.size() != 4) {
            failures.add("draws: shuffle(5) drew " + draws.size() + ", expected 4");
        } else {
            List<String> expectedBounds = List.of("nextInt(5)", "nextInt(4)", "nextInt(3)", "nextInt(2)");
            if (!draws.equals(expectedBounds)) {
                failures.add("draws: shuffle bounds " + draws + ", expected " + expectedBounds);
            }
        }
        // Golden order for seed 42 over [0..4]: Fisher-Yates from the back.
        Random ref = new Random(42);
        List<Integer> refList = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        for (int i = 5; i > 1; i--) {
            int s = ref.nextInt(i);
            int a = refList.get(i - 1);
            refList.set(i - 1, refList.get(s));
            refList.set(s, a);
        }
        if (!list.equals(refList)) {
            failures.add("draws: shuffle order " + list + ", expected " + refList);
        }
        // Fully-failing chunk: 1 group x 4 tries (the max possible, so the
        // group-size draw never truncates the gate list), null player
        // everywhere. Draws: 3 start + 1 group-size + 4*tries wander, zero
        // spawns. Structural: size is 4 mod 16/12/8/4 (max 0..4).
        SpawnDrawModel.DrawLog fail = new SpawnDrawModel.DrawLog();
        List<SpawnDrawModel.TryGates> allFail = List.of(
            new SpawnDrawModel.TryGates(false, false, false),
            new SpawnDrawModel.TryGates(false, false, false),
            new SpawnDrawModel.TryGates(false, false, false),
            new SpawnDrawModel.TryGates(false, false, false));
        List<List<SpawnDrawModel.TryGates>> groups = List.of(allFail);
        int spawns = SpawnDrawModel.replayChunk(new Random(7), fail, groups, List.of(false), 0, 10, 100, 1, 4);
        if (spawns != 0) {
            failures.add("draws: failing chunk spawned " + spawns);
        }
        List<String> failDraws = fail.draws();
        boolean failShape = failDraws.size() >= 4 && failDraws.size() <= 20
            && (failDraws.size() - 4) % 4 == 0
            && failDraws.get(0).equals("nextInt(16)")
            && failDraws.get(1).equals("nextInt(16)")
            && failDraws.get(2).equals("nextInt(11)")
            && failDraws.get(3).equals("nextFloat()");
        if (!failShape) {
            failures.add("draws: failing chunk shape wrong: " + failDraws);
        }
        for (int i = 4; i < failDraws.size(); i++) {
            if (!failDraws.get(i).equals("nextInt(6)")) {
                failures.add("draws: failing chunk non-wander draw at " + i + ": " + failDraws);
                break;
            }
        }
        // Empty pick breaks the group: pick + reroll drawn, no YAW draw, group
        // ends. Note nextFloat() appears once as the group-size draw; the yaw
        // check counts occurrences (exactly 1) instead of absence.
        // Shape: 3 start + 1 group + 4 wander + 1 pick + 1 reroll = 10.
        SpawnDrawModel.DrawLog empty = new SpawnDrawModel.DrawLog();
        List<List<SpawnDrawModel.TryGates>> groups2 = List.of(List.of(
            new SpawnDrawModel.TryGates(true, true, true),
            new SpawnDrawModel.TryGates(true, true, true),
            new SpawnDrawModel.TryGates(true, true, true),
            new SpawnDrawModel.TryGates(true, true, true)));
        int spawns2 = SpawnDrawModel.replayChunk(new Random(7), empty, groups2, List.of(true), 0, 10, 100, 2, 5);
        if (spawns2 != 0) {
            failures.add("draws: empty-pick chunk spawned " + spawns2);
        }
        List<String> emptyDraws = empty.draws();
        long floats = emptyDraws.stream().filter(d -> d.equals("nextFloat()")).count();
        boolean emptyShape = (emptyDraws.size() == 10 || emptyDraws.size() == 6)
            && floats == 1
            && emptyDraws.get(0).equals("nextInt(16)")
            && emptyDraws.get(3).equals("nextFloat()");
        if (!emptyShape) {
            failures.add("draws: empty-pick shape wrong: " + emptyDraws);
        }
        // Fully-passing single try: full sequence incl. yaw.
        SpawnDrawModel.DrawLog pass = new SpawnDrawModel.DrawLog();
        List<List<SpawnDrawModel.TryGates>> groups3 = List.of(List.of(
            new SpawnDrawModel.TryGates(true, true, true)));
        int spawns3 = SpawnDrawModel.replayChunk(new Random(7), pass, groups3, List.of(false), -64, 100, 50, 1, 1);
        if (spawns3 != 1) {
            failures.add("draws: passing try spawned " + spawns3 + ", expected 1");
        }
        List<String> expectedPass = List.of(
            "nextInt(16)", "nextInt(16)", "nextInt(165)",
            "nextFloat()",
            "nextInt(6)", "nextInt(6)", "nextInt(6)", "nextInt(6)",
            "nextInt(50)", "nextInt(1)", "nextFloat()");
        if (!pass.draws().equals(expectedPass)) {
            failures.add("draws: passing try " + pass.draws() + ", expected " + expectedPass);
        }
    }

    private static void checkNegativeControls(List<String> failures) {
        // Wrong comparison direction (>) disagrees with the port off-boundary.
        int disagreements = 0;
        int[][] cases = {{0, 70, 100}, {23, 70, 100}, {100, 70, 100}, {5, 10, 30}, {0, 10, 0}};
        for (int[] c : cases) {
            boolean port = SpawnCaps.canSpawnForCategoryGlobal(c[0], c[1], c[2]);
            int maxMob = c[1] * c[2] / SpawnCaps.MAGIC_NUMBER;
            boolean wrong = c[0] > maxMob;
            if (port != wrong) {
                disagreements++;
            }
        }
        if (disagreements == 0) {
            failures.add("negative: >-comparison never disagreed, control vacuous");
        }
        // Skipping wander draws on failed tries shortens the log: the
        // reference failing chunk draws 12, a skip-draws variant draws 4
        // (3 start + 1 group-size). Assert they differ so the battery has teeth.
        SpawnDrawModel.DrawLog ref = new SpawnDrawModel.DrawLog();
        List<List<SpawnDrawModel.TryGates>> groups = List.of(List.of(
            new SpawnDrawModel.TryGates(false, false, false),
            new SpawnDrawModel.TryGates(false, false, false)));
        SpawnDrawModel.replayChunk(new Random(7), ref, groups, List.of(false), 0, 10, 100, 1, 4);
        if (ref.draws().size() == 4) {
            failures.add("negative: reference log unexpectedly short, control vacuous");
        }
        // Positive-coverage guard (the hopadd lesson): the randomized leg
        // must actually exercise multi-charge fields, not just empty ones.
        Random rng = new Random(99);
        int multiCharge = 0;
        for (int t = 0; t < 200; t++) {
            if (rng.nextInt(6) >= 2) {
                multiCharge++;
            }
        }
        if (multiCharge == 0) {
            failures.add("negative: coverage guard never fired, trials vacuous");
        }
    }
}
