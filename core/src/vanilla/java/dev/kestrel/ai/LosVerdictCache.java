package dev.kestrel.ai;

import java.util.function.Predicate;

/**
 * Per-scan verdict memo for sensor line-of-sight / targetability tests.
 *
 * <p><b>Read this before assuming it is the E1 "win".</b> Vanilla ALREADY
 * memoizes these verdicts, in two independent layers (both verified against
 * the real decompiled 26.2 source, see preresearch/mob-ai-path-analysis.md):
 *
 * <ol>
 *   <li>{@code Sensing} (net.minecraft.world.entity.ai.sensing.Sensing) holds
 *       {@code seen}/{@code unseen} IntSets keyed by entity id and is cleared
 *       exactly once per tick from {@code Mob.serverAiStep}. Every LOS clip in
 *       the game funnels through {@code TargetingConditions.test} ->
 *       {@code mob.getSensing().hasLineOfSight(target)}, so the raycast itself
 *       is already at most once per (mob, target) per tick, for sensors AND
 *       behaviors alike.</li>
 *   <li>{@code NearestVisibleLivingEntities} additionally wraps its entity
 *       list in an {@code Object2BooleanOpenHashMap} memo of the FULL
 *       {@code Sensor.isEntityTargetable} verdict, allocated fresh per scan.</li>
 * </ol>
 *
 * <p>So this class is NOT a new cache layer bolted on top of vanilla. It is
 * Kestrel's own implementation of the same per-scan snapshot semantics, which
 * Kestrel needs because it owns its sensor path. Its one measured advantage is
 * narrow: it allocates on first ask and rolls over in O(1) via a generation
 * stamp, where vanilla allocates a fresh hash map per scan per mob even when
 * no consumer ever asks a question. On the QUERIED path it is SLOWER than
 * fastutil (see the field comment below for numbers). It is kept because
 * Kestrel needs a memo it owns with exactly these lifetime semantics, not
 * because the memo itself is a speed win.
 *
 * <p><b>Lifetime is exactly one scan.</b> {@link #beginScan()} invalidates
 * every verdict. This is a parity requirement, not a preference: a pillar
 * built between two scans must blind the NEXT scan and must not retroactively
 * change the verdicts of the scan already in progress. Within a scan the
 * verdicts are a frozen snapshot; across scans nothing survives.
 *
 * <p>Implementation: open-addressed linear-probe table, power-of-two capacity.
 * A slot counts as occupied only when its generation stamp equals the current
 * generation. Insert and lookup probe with the identical rule (stop at the
 * first slot that is not current, or that is current and holds the key), so a
 * key is always found at exactly the slot an insert would have claimed. Stale
 * slots are simply overwritten, which is why rollover needs no clearing pass.
 */
public final class LosVerdictCache {
    private static final int INITIAL_CAPACITY = 16;

    // MEASURED, not assumed (SensorScanBench "memo" rows, bench PC 5700X3D, JDK 25):
    // on the QUERIED path this table loses to vanilla's fastutil
    // Object2BooleanOpenHashMap, 0,76-0,81x. Interleaving key+stamp into one
    // array to halve the cache lines per probe was tried and made it WORSE
    // (0,56-0,72x), so the parallel-array layout below is the one that stands.
    // The honest win is the IDLE path: vanilla allocates its map per scan per
    // mob whether or not any consumer ever asks a question, this allocates on
    // first ask and rolls over in O(1). Do not re-litigate the layout without
    // a new measurement.
    private int[] keys = new int[0];
    private int[] stamps = new int[0];
    private boolean[] values = new boolean[0];
    private int capacity;
    private int mask;
    private int size;
    // Must never be 0: a freshly allocated stamps[] is all zeros, and a slot
    // counts as occupied only when its stamp equals the current generation.
    // Generation 0 would make every empty slot look occupied and spin probe().
    private int generation = 1;
    private long evaluations;
    private long hits;

    /**
     * Drops every cached verdict and opens a new scan. O(1): the generation
     * bump alone invalidates the table.
     */
    public void beginScan() {
        generation++;
        size = 0;
        if (generation == Integer.MAX_VALUE) {
            // Wrap defensively so a stamp can never collide with a live one.
            generation = 1;
            java.util.Arrays.fill(stamps, 0);
        }
    }

    /** Ends the current scan. Equivalent to {@link #beginScan()} for invalidation purposes. */
    public void endScan() {
        generation++;
        size = 0;
    }

    /**
     * Returns the memoized verdict for {@code key}, computing it via
     * {@code oracle} on first ask within this scan.
     *
     * @param key     entity id (stable and unique within a level for the scan's duration)
     * @param subject the entity the oracle should evaluate
     * @param oracle  the real vanilla test, invoked at most once per key per scan
     */
    public <T> boolean verdict(int key, T subject, Predicate<? super T> oracle) {
        if (capacity == 0) {
            allocate(INITIAL_CAPACITY);
        }
        int slot = probe(key);
        if (stamps[slot] == generation && keys[slot] == key) {
            hits++;
            return values[slot];
        }
        boolean value = oracle.test(subject);
        evaluations++;
        keys[slot] = key;
        stamps[slot] = generation;
        values[slot] = value;
        size++;
        if (size > (capacity >> 1)) {
            grow();
        }
        return value;
    }

    /** True if {@code key} already has a verdict in the current scan. */
    public boolean isCached(int key) {
        if (capacity == 0) {
            return false;
        }
        int slot = probe(key);
        return stamps[slot] == generation && keys[slot] == key;
    }

    /** Oracle invocations so far (the real clip/condition work actually done). */
    public long evaluations() {
        return evaluations;
    }

    /** Memo hits so far (the work avoided). */
    public long hits() {
        return hits;
    }

    public void resetCounters() {
        evaluations = 0L;
        hits = 0L;
    }

    /** Live entries in the current scan. */
    public int size() {
        return size;
    }

    private int probe(int key) {
        int slot = hash(key) & mask;
        while (stamps[slot] == generation && keys[slot] != key) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private static int hash(int key) {
        int h = key * 0x9E3779B1;
        return h ^ (h >>> 16);
    }

    private void allocate(int newCapacity) {
        keys = new int[newCapacity];
        stamps = new int[newCapacity];
        values = new boolean[newCapacity];
        capacity = newCapacity;
        mask = newCapacity - 1;
    }

    private void grow() {
        int[] oldKeys = keys;
        int[] oldStamps = stamps;
        boolean[] oldValues = values;
        int oldCapacity = capacity;
        int oldGeneration = generation;
        allocate(oldCapacity << 1);
        int live = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (oldStamps[i] == oldGeneration) {
                int key = oldKeys[i];
                int slot = probe(key);
                keys[slot] = key;
                stamps[slot] = oldGeneration;
                values[slot] = oldValues[i];
                live++;
            }
        }
        size = live;
    }
}
