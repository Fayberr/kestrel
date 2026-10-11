package dev.kestrel.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Step 6's transcription model (the {@code TickOrderModel} role): the exact
 * level-RNG draw sequence of vanilla's spawn attempt path, quoted against
 * the 26.2 decompile, executable with a controllable RNG.
 *
 * <p><b>What is transcribed, and from where.</b>
 *
 * <ol>
 *   <li>Shuffle: {@code Util.shuffle(spawningChunks, level.getRandom())}
 *       ({@code Util.java:991}) is Fisher-Yates from the back:
 *       {@code for (i = size; i > 1; i--) swap(i - 1, nextInt(i))}. One
 *       {@code nextInt} per position, sizes {@code n, n-1, ..., 2}.</li>
 *   <li>Chunk start: {@code getRandomPosWithin}
 *       ({@code NaturalSpawner.java:342}): {@code nextInt(16)} for x,
 *       {@code nextInt(16)} for z, then
 *       {@code randomBetweenInclusive(minY, topEmptyY)} =
 *       {@code nextInt(topEmptyY - minY + 1) + minY}
 *       ({@code Mth.java:690}). Three draws; skipped entirely when the
 *       chunk is not attempted (empty categories or gate failure).</li>
 *   <li>Group size: {@code max = ceil(nextFloat() * 4.0)}
 *       ({@code NaturalSpawner.java:176}), drawn once per attempt group
 *       (up to 3 groups), BEFORE the inner try loop. Reroll on a picked
 *       spawn entry: {@code minCount + nextInt(1 + maxCount - minCount)}
 *       ({@code :196}).</li>
 *   <li>Wander: per try, {@code x += nextInt(6) - nextInt(6)} and
 *       {@code z += nextInt(6) - nextInt(6)} ({@code :180-181}): four
 *       {@code nextInt(6)} draws per try, consumed even when the try then
 *       early-outs on null player or distance.</li>
 *   <li>Spawn-data pick: {@code WeightedList.getRandom}
 *       ({@code WeightedList.java:77}): one {@code nextInt(totalWeight)}.
 *       Only reached when {@code currentSpawnData == null} for the group;
 *       later tries in the same group reuse the entry without drawing.</li>
 *   <li>Yaw: {@code nextFloat() * 360.0} at {@code snapTo} ({@code :206}),
 *       only on a try that passes all validation.</li>
 * </ol>
 *
 * <p>The model does not decide spawns (no biome, no placement rules, no
 * entities). It replays caller-supplied gate outcomes
 * ({@code hasPlayer}, {@code distancePass}, {@code validSpawn}) and records
 * every RNG draw in order, so the battery can assert the exact draw sequence
 * per path, including draws on failed attempts. A port of the attempt loop
 * must reproduce these sequences; any skip or reorder renumbers every later
 * level-RNG draw (parity trap 1 in
 * {@code preresearch/spawn-path-analysis.md}).
 *
 * <p>Deterministic {@link Random} with a fixed seed stands in for
 * {@code RandomSource}; only {@code nextInt}/{@code nextFloat} ordering is
 * modeled, not the underlying generator.
 */
public final class SpawnDrawModel {

    /** Gate outcomes for one try, in evaluation order. */
    public record TryGates(boolean hasPlayer, boolean distancePass, boolean validSpawn) {}

    /** Ordered draw log: entries like {@code "nextInt(16)"}. */
    public static final class DrawLog {
        private final List<String> draws = new ArrayList<>();

        void add(String draw) {
            draws.add(draw);
        }

        public List<String> draws() {
            return List.copyOf(draws);
        }

        public int size() {
            return draws.size();
        }
    }

    private SpawnDrawModel() {
    }

    /**
     * Ports {@code Util.shuffle}: Fisher-Yates over list indices, one
     * {@code nextInt(i)} per position from {@code size} down to 2.
     */
    public static void shuffle(List<Integer> list, Random random, DrawLog log) {
        int size = list.size();
        for (int i = size; i > 1; i--) {
            int swapTo = nextInt(random, log, i);
            int a = list.get(i - 1);
            list.set(i - 1, list.get(swapTo));
            list.set(swapTo, a);
        }
    }

    /**
     * Replays one chunk attempt: the 3 start draws, then up to 3 groups each
     * opening with the {@code ceil(nextFloat()*4)} group-size draw, then per
     * try the 4 wander draws followed by gate evaluation. A null
     * {@code currentSpawnData} (first try of a group reaching the pick, or
     * any try after an empty pick broke the group) costs one
     * {@code nextInt(totalWeight)} pick draw; an empty pick
     * ({@code pickEmpty}) breaks the group like vanilla's
     * {@code nextSpawnData.isEmpty() -> break}. A fully passing try costs
     * one {@code nextFloat()} yaw draw. Group-size reroll is modeled as one
     * {@code nextInt(1 + maxCount - minCount)} draw on the picking try.
     *
     * @param groups    gate outcomes per group (up to 3 honored)
     * @param pickEmpty per group: whether the spawn-data pick comes back empty
     * @return number of fully passing tries (would-be spawns)
     */
    public static int replayChunk(
        Random random, DrawLog log,
        List<List<TryGates>> groups, List<Boolean> pickEmpty,
        int minY, int topEmptyY, int totalWeight, int minCount, int maxCount
    ) {
        nextInt(random, log, 16);
        nextInt(random, log, 16);
        nextInt(random, log, topEmptyY - minY + 1);
        int spawns = 0;
        int groupCount = Math.min(3, groups.size());
        for (int g = 0; g < groupCount; g++) {
            float f = nextFloat(random, log);
            int max = (int) Math.ceil(f * 4.0F);
            boolean hasEntry = false;
            for (int t = 0; t < max && t < groups.get(g).size(); t++) {
                nextInt(random, log, 6);
                nextInt(random, log, 6);
                nextInt(random, log, 6);
                nextInt(random, log, 6);
                TryGates gates = groups.get(g).get(t);
                if (!gates.hasPlayer() || !gates.distancePass()) {
                    continue;
                }
                if (!hasEntry) {
                    nextInt(random, log, totalWeight);
                    nextInt(random, log, 1 + maxCount - minCount);
                    if (pickEmpty.get(g)) {
                        break;
                    }
                    hasEntry = true;
                }
                if (gates.validSpawn()) {
                    nextFloat(random, log);
                    spawns++;
                }
            }
        }
        return spawns;
    }

    private static int nextInt(Random random, DrawLog log, int bound) {
        log.add("nextInt(" + bound + ")");
        return random.nextInt(bound);
    }

    private static float nextFloat(Random random, DrawLog log) {
        log.add("nextFloat()");
        return random.nextFloat();
    }
}
