package dev.kestrel.spawn;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Step 6's RNG-free algebra: the global mob-cap rule, the category filter,
 * and the local mob-cap counting, as pure functions over scalar snapshots.
 *
 * <p><b>Transcribed from vanilla 26.2, not re-derived.</b> Each method quotes
 * the decompile site it ports:
 *
 * <ul>
 *   <li>{@link #canSpawnForCategoryGlobal} ports
 *       {@code SpawnState.canSpawnForCategoryGlobal}
 *       ({@code NaturalSpawner.java:538}):
 *       {@code mobCategoryCounts.getInt(mobCategory) < maxMobCount} where
 *       {@code maxMobCount = getMaxInstancesPerChunk() * spawnableChunkCount /
 *       MAGIC_NUMBER} and {@code MAGIC_NUMBER = 17^2 = 289}
 *       ({@code NaturalSpawner.java:64}). Integer division, evaluated left
 *       to right exactly as written: multiply first, then divide.</li>
 *   <li>{@link #filterSpawningCategories} ports
 *       {@code NaturalSpawner.getFilteredSpawningCategories}
 *       ({@code NaturalSpawner.java:107}): iterate
 *       {@code SPAWNING_CATEGORIES} in declaration order (all
 *       {@code MobCategory} values except MISC), keep entries with
 *       {@code (spawnEnemies || isFriendly) && (spawnPersistent ||
 *       !isPersistent) && canSpawnForCategoryGlobal}. Output order is input
 *       order; {@code spawnForChunk} iterates it directly, so order feeds
 *       {@code afterSpawn} charge accumulation and is observable.</li>
 *   <li>{@link #canSpawnLocal} ports
 *       {@code LocalMobCapCalculator.canSpawn}
 *       ({@code LocalMobCapCalculator.java}): true if ANY tracked player near
 *       the chunk has no count for the category or a count below
 *       {@code getMaxInstancesPerChunk()}; false when no player is near at
 *       all (the loop body never runs). Per-player counts accumulate via
 *       {@code addMob} ({@code LocalMobCapCalculator.java}); the port takes
 *       the finished per-player count lists as input and owns no player
 *       tracking.</li>
 * </ul>
 *
 * <p><b>What this is NOT.</b> It is not the entity walk that builds
 * {@code mobCounts} ({@code createState}, still vanilla's in Leg 2), not the
 * potential-energy charge math ({@link SpawnPotential} owns that), and not
 * the RNG-driven attempt loop (transcribed, never reordered, see
 * {@link SpawnDrawModel}). No Minecraft classes are touched, so it runs on
 * plain {@code javac}/{@code java}.
 *
 * <p>Thread-safety: single tick-thread only, same as vanilla's spawn pass.
 * No synchronization.
 */
public final class SpawnCaps {

    /** {@code NaturalSpawner.MAGIC_NUMBER = (int) Math.pow(17.0, 2.0)}. */
    public static final int MAGIC_NUMBER = 289;

    private SpawnCaps() {
    }

    /**
     * Vanilla's global cap check for one category.
     *
     * @param count               current mob count for the category
     * @param maxInstancesPerChunk {@code MobCategory.getMaxInstancesPerChunk()}
     * @param spawnableChunkCount {@code SpawnState.spawnableChunkCount}
     * @return true iff {@code count < maxInstancesPerChunk *
     *         spawnableChunkCount / MAGIC_NUMBER}
     */
    public static boolean canSpawnForCategoryGlobal(int count, int maxInstancesPerChunk, int spawnableChunkCount) {
        int maxMobCount = maxInstancesPerChunk * spawnableChunkCount / MAGIC_NUMBER;
        return count < maxMobCount;
    }

    /**
     * Per-category filter inputs for {@link #filterSpawningCategories}: the
     * category's own flags plus its precomputed global-cap verdict.
     */
    public record CategoryView(
        String name,
        boolean friendly,
        boolean persistent,
        boolean globalCapPass
    ) {}

    /**
     * Ports {@code getFilteredSpawningCategories}: keeps, in input order,
     * every category with {@code (spawnEnemies || friendly) &&
     * (spawnPersistent || !persistent) && globalCapPass}.
     *
     * @param categories  in {@code SPAWNING_CATEGORIES} order (MISC already excluded)
     * @param spawnEnemies {@code ServerChunkCache.spawnEnemies}
     * @param spawnPersistent {@code gameTime % 400 == 0}
     * @return names of spawning categories, in input order
     */
    public static List<String> filterSpawningCategories(
        List<CategoryView> categories, boolean spawnEnemies, boolean spawnPersistent
    ) {
        List<String> out = new ArrayList<>(categories.size());
        for (CategoryView c : categories) {
            if ((spawnEnemies || c.friendly()) && (spawnPersistent || !c.persistent()) && c.globalCapPass()) {
                out.add(c.name());
            }
        }
        return out;
    }

    /**
     * Ports {@code LocalMobCapCalculator.canSpawn} over finished per-player
     * count snapshots: one entry per player near the chunk, each mapping
     * category name to that player's mob count for it. A missing entry means
     * the player has no mobs of that category (vanilla's
     * {@code mobCounts == null || canSpawn} first disjunct).
     *
     * @param playersNearCounts per-player category counts; empty means no
     *                          player near, which vanilla answers false
     * @param category          category under test
     * @param maxInstancesPerChunk {@code MobCategory.getMaxInstancesPerChunk()}
     * @return true iff any nearby player is under cap for the category
     */
    public static boolean canSpawnLocal(
        List<Map<String, Integer>> playersNearCounts, String category, int maxInstancesPerChunk
    ) {
        for (Map<String, Integer> counts : playersNearCounts) {
            Integer count = counts.get(category);
            if (count == null || count < maxInstancesPerChunk) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ports {@code LocalMobCapCalculator.MobCounts.add}: increment helper for
     * building the per-player snapshots {@link #canSpawnLocal} consumes.
     */
    public static Map<String, Integer> addMobCount(Map<String, Integer> counts, String category) {
        Map<String, Integer> next = new java.util.HashMap<>(counts);
        next.merge(category, 1, Integer::sum);
        return next;
    }

    /** Builds the seven 26.2 spawning categories (all but MISC) in enum order. */
    public static List<CategoryView> vanillaCategories(Map<String, Boolean> globalPass) {
        // Order: MONSTER, CREATURE, AMBIENT, AXOLOTLS, UNDERGROUND_WATER_CREATURE,
        // WATER_CREATURE, WATER_AMBIENT (MobCategory.java declaration order, MISC last).
        String[] names = {"MONSTER", "CREATURE", "AMBIENT", "AXOLOTLS",
            "UNDERGROUND_WATER_CREATURE", "WATER_CREATURE", "WATER_AMBIENT"};
        boolean[] friendly = {false, true, true, true, true, true, true};
        boolean[] persistent = {false, true, false, false, false, false, false};
        List<CategoryView> out = new ArrayList<>(names.length);
        for (int i = 0; i < names.length; i++) {
            out.add(new CategoryView(names[i], friendly[i], persistent[i],
                globalPass.getOrDefault(names[i], false)));
        }
        return out;
    }

    /** Caps read off {@code MobCategory.java}: name to maxInstancesPerChunk. */
    public static Map<String, Integer> vanillaCaps() {
        Map<String, Integer> caps = new java.util.HashMap<>();
        caps.put("MONSTER", 70);
        caps.put("CREATURE", 10);
        caps.put("AMBIENT", 15);
        caps.put("AXOLOTLS", 5);
        caps.put("UNDERGROUND_WATER_CREATURE", 5);
        caps.put("WATER_CREATURE", 5);
        caps.put("WATER_AMBIENT", 20);
        return caps;
    }
}
