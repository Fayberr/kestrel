package dev.kestrel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Kestrel's replacement for the per-tick behavior walks in vanilla's
 * {@code Brain} (analysis target E5).
 *
 * <p><b>The premise was right about the waste and wrong about the fix.</b> The
 * grounding doc proposed "maintain a dirty-flagged running-set (update on
 * tryStart/stop transitions only), single walk". The waste is real: every
 * {@code Brain.tick} runs {@code startEachNonRunningBehavior} (a full walk over
 * priorities x activities x behaviors) and then {@code tickEachRunningBehavior},
 * which calls {@code getRunningBehaviors()} and so walks the whole structure a
 * SECOND time, allocating a fresh {@code ObjectArrayList} plus one iterator per
 * nested collection, per entity per tick. But the proposed mechanism is unsound
 * on two independent counts, both found in the 26.2 source:
 *
 * <ol>
 *   <li><b>The two walks cannot be fused.</b> Vanilla starts EVERY eligible
 *       behavior before ticking ANY of them. Behaviors mutate shared brain
 *       memories, so an interleaved start(A), tick(A), start(B) can change
 *       whether B starts at all. The start-all-then-tick-all ordering is
 *       observable, so "single walk" is a behavior change, not an
 *       optimization.</li>
 *   <li><b>The snapshot is load-bearing, not just CME avoidance.</b>
 *       {@code Behavior.tickOrStop} is
 *       {@code if (!timedOut && canStillUse) tick(); else doStop();} with NO
 *       {@code status == RUNNING} guard. So a behavior that was RUNNING when
 *       the list was built and has since been stopped STILL gets
 *       {@code tickOrStop} called on it, which calls {@code doStop} a second
 *       time and fires {@code stop()} again. Re-checking the status inline
 *       while walking, which is what a maintained running-set would effectively
 *       do, silently drops that second call. The list must be materialized
 *       first and then iterated.</li>
 * </ol>
 *
 * <p>A third constraint rules out the other obvious shortcut: collecting the
 * running set during walk 1 as a free by-product. Walk 1 only descends into
 * activities that are currently ACTIVE, while {@code getRunningBehaviors}
 * visits every behavior regardless. {@code setActiveActivity} does not stop the
 * behaviors of the activity it deactivates (it only erases memories and swaps
 * the active set), so a RUNNING behavior in a now-inactive activity is normal,
 * and it must still be ticked. Walk 1's visited set is a strict subset.
 *
 * <p><b>So what actually lands here is the cheap part, done properly.</b> The
 * behavior structure is immutable after brain construction ({@code addActivity}
 * is setup-only, {@code removeAllBehaviors} is {@code @VisibleForTesting}), so
 * it is flattened ONCE into arrays in exactly vanilla's traversal order, and
 * the running snapshot is filled into a reusable buffer sized once. Per entity
 * per tick that removes: one {@code ObjectArrayList} allocation and its growth,
 * every {@code add} call, and one iterator allocation per nested collection
 * visited (a {@code TreeMap} iterator plus one {@code HashMap} iterator per
 * priority plus one {@code LinkedHashSet} iterator per activity, twice over).
 * What it deliberately does NOT remove is the status scan itself, because of
 * point 2 above.
 *
 * <p><b>Parity.</b> Flattening preserves order exactly: vanilla's outer map is
 * a {@code TreeMap} keyed by priority, so {@code values()} is priority
 * ascending; the inner {@code HashMap}'s iteration order is stable for a map
 * that is never modified again, so snapshotting it once reproduces it on every
 * later tick; the innermost {@code LinkedHashSet} is insertion ordered. Both
 * walks use that single order, which is why one flat array serves both.
 *
 * <p><b>RNG.</b> This class draws nothing, and must not: {@code Behavior.tryStart}
 * calls {@code level.getRandom().nextInt(maxDuration + 1 - minDuration)} on
 * every SUCCESSFUL start (note that even {@code min == max} still draws, via
 * {@code nextInt(1)}). So the set of behaviors {@code tryStart} is invoked on,
 * and the order of invocation, are both part of the level RNG draw sequence.
 * That is the single hardest parity constraint in E5 and the proof checks the
 * draw sequence directly, not just the outcome.
 *
 * @param <B> behavior type (vanilla: {@code BehaviorControl<? super E>})
 * @param <A> activity type (vanilla: {@code Activity})
 */
public final class BehaviorSchedule<B, A> {

    /** Activity of each group, in traversal order. */
    private final Object[] groupActivities;
    /** Exclusive end index into {@link #behaviors} for each group. */
    private final int[] groupEnds;
    /** Every behavior, flattened in vanilla's traversal order. */
    private final Object[] behaviors;
    /** Reusable running-behavior snapshot, sized once to the behavior count. */
    private final Object[] snapshot;

    private BehaviorSchedule(Object[] groupActivities, int[] groupEnds, Object[] behaviors) {
        this.groupActivities = groupActivities;
        this.groupEnds = groupEnds;
        this.behaviors = behaviors;
        this.snapshot = new Object[behaviors.length];
    }

    /**
     * Flattens vanilla's {@code availableBehaviorsByPriority} into arrays.
     *
     * <p>Takes the live structure rather than a pre-digested list so that the
     * order this produces is demonstrably the order vanilla's nested loops
     * produce. Call once, after the brain is fully built.
     *
     * @param byPriority vanilla's {@code Maps.newTreeMap()} of
     *                   priority to activity to behaviors. Iterated in its own
     *                   order, which for the TreeMap vanilla uses is priority
     *                   ascending.
     */
    public static <B, A> BehaviorSchedule<B, A> flatten(Map<Integer, Map<A, Set<B>>> byPriority) {
        List<A> activities = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        List<B> flat = new ArrayList<>();
        for (Map<A, Set<B>> byActivity : byPriority.values()) {
            for (Map.Entry<A, Set<B>> entry : byActivity.entrySet()) {
                activities.add(entry.getKey());
                flat.addAll(entry.getValue());
                ends.add(flat.size());
            }
        }
        int[] groupEnds = new int[ends.size()];
        for (int i = 0; i < groupEnds.length; i++) {
            groupEnds[i] = ends.get(i);
        }
        return new BehaviorSchedule<>(activities.toArray(), groupEnds, flat.toArray());
    }

    /** Number of behaviors in the schedule. */
    public int size() {
        return this.behaviors.length;
    }

    /**
     * Vanilla's {@code startEachNonRunningBehavior}: for every group whose
     * activity is active, invoke {@code tryStart} on each STOPPED behavior, in
     * traversal order.
     *
     * <p>The activity test is evaluated once per GROUP, exactly as vanilla
     * evaluates {@code activeActivities.contains(activity)} once per
     * (priority, activity) map entry, not once per behavior.
     */
    @SuppressWarnings("unchecked")
    public void startEachNonRunningBehavior(Predicate<? super A> activityActive,
                                            Predicate<? super B> isStopped,
                                            Consumer<? super B> tryStart) {
        int from = 0;
        for (int group = 0; group < this.groupEnds.length; group++) {
            int to = this.groupEnds[group];
            if (activityActive.test((A) this.groupActivities[group])) {
                for (int i = from; i < to; i++) {
                    B behavior = (B) this.behaviors[i];
                    if (isStopped.test(behavior)) {
                        tryStart.accept(behavior);
                    }
                }
            }
            from = to;
        }
    }

    /**
     * Vanilla's {@code tickEachRunningBehavior}: materialize the running set
     * FIRST, then invoke {@code tickOrStop} on each element of that snapshot.
     *
     * <p>The two phases are not merged, deliberately. See the class javadoc,
     * point 2: {@code tickOrStop} has no status guard, so an element stopped
     * part way through the walk must still receive its call.
     */
    @SuppressWarnings("unchecked")
    public void tickEachRunningBehavior(Predicate<? super B> isRunning,
                                        Consumer<? super B> tickOrStop) {
        int count = 0;
        for (int i = 0; i < this.behaviors.length; i++) {
            B behavior = (B) this.behaviors[i];
            if (isRunning.test(behavior)) {
                this.snapshot[count++] = behavior;
            }
        }
        snapshotFills++;
        for (int i = 0; i < count; i++) {
            tickOrStop.accept((B) this.snapshot[i]);
        }
        // Drop the references so a stale snapshot cannot pin behaviors that a
        // later brain teardown removes. Cheap: count is the running set, not n.
        for (int i = 0; i < count; i++) {
            this.snapshot[i] = null;
        }
    }

    /**
     * Vanilla's {@code getRunningBehaviors()}, which is public and
     * {@code @VisibleForDebug}, so it must hand back a fresh independent list.
     * This deliberately does NOT expose the reusable snapshot buffer.
     */
    @SuppressWarnings("unchecked")
    public List<B> runningBehaviors(Predicate<? super B> isRunning) {
        List<B> running = new ArrayList<>();
        for (int i = 0; i < this.behaviors.length; i++) {
            B behavior = (B) this.behaviors[i];
            if (isRunning.test(behavior)) {
                running.add(behavior);
            }
        }
        return running;
    }

    // ---- counters, for the proof and the bench only ----

    private static long snapshotFills;

    /** Running-set snapshots taken into the reusable buffer. */
    public static long snapshotFills() {
        return snapshotFills;
    }

    public static void resetCounters() {
        snapshotFills = 0L;
    }
}
