package dev.kestrel.blockentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 5 Leg 3: the tick-loop integration contract for block-entity tick
 * sleeping, stated as an executable model the real loop must satisfy.
 *
 * <p><b>Why a model, not the loop.</b> Kestrel has no tick loop yet: there
 * is no networking, world storage, or scheduler, so there is nothing to
 * wire the {@link SleeperRegistry} into. What Leg 3 can pin down now is the
 * exact pass discipline the future loop must follow, proven as a
 * differential against {@link TickOrderModel} (vanilla's transcribed
 * scheduling). When the loop lands, it reuses this discipline verbatim; the
 * battery ({@link SleepLoopCheck}) then reads as its conformance suite.
 *
 * <p><b>The discipline</b>, one pass:
 *
 * <ol>
 *   <li>{@code beginPass}: open the pass on both sides (vanilla drains its
 *       pending list; the sleep side clears {@code wokenThisPass}).</li>
 *   <li>Walk the sleep side's awake ids in ordinal order. For each id, run
 *       the SAME body the vanilla side runs for that id.</li>
 *   <li>After each body, evaluate that id's sleep predicate against the
 *       post-tick state and {@code reportIdle}: idle drops out of later
 *       passes, non-idle stays. Predicate state is per-id mutable fixture
 *       state here; production reads live block-entity fields.</li>
 *   <li>Wake signals arriving mid-pass (from a body, or from an external
 *       mutation the test injects between bodies) go through
 *       {@link SleeperRegistry#wake} and therefore defer to next pass via
 *       the {@code wokenThisPass} set. This is the Lithium
 *       {@code sleepOnlyCurrentTick} fix, enforced structurally rather than
 *       by caller discipline.</li>
 *   <li>Vanilla's own state-null retire/APPEND path stays outside the
 *       registry: {@code retire} drops the entry, {@code APPEND} wake takes
 *       a fresh ordinal at the back. The model exposes both so the battery
 *       can run them side by side with Kestrel sleeps in the same pass.</li>
 * </ol>
 *
 * <p><b>Parity judgment.</b> Like {@link TickOrderModel}, this model judges
 * by observable trace: bodies publish effects tagged with game time, and
 * the battery requires the sleep side's trace to equal the vanilla side's
 * trace tick for tick. A body that runs and does nothing is
 * indistinguishable from one that never ran, which is precisely why
 * sleeping can be exact at all.
 *
 * <p>No Minecraft classes are touched: ids are ints, predicate state is a
 * caller-owned {@link State} record. Plain {@code javac}/{@code java}.
 *
 * <p>Thread-safety: single tick-thread only. No synchronization.
 */
public final class SleepLoop {

    /**
     * Per-id mutable fixture state standing in for the live block-entity
     * fields the production predicate reads. The body mutates it; the
     * predicate reads it after the body runs.
     */
    public static final class State {
        /** Body runs while true; set false by whatever the body models. */
        public boolean busy;
        /** Effect string emitted when the body does observable work. */
        public String effect;
        /** Counts body invocations (the walk-size reduction is measured here). */
        public int bodyRuns;
    }

    /** A tick body: may mutate state, wake other ids, and emit effects. */
    @FunctionalInterface
    public interface Body {
        void tick(SleepLoop loop, int id, long gameTime);
    }

    private final SleeperRegistry registry = new SleeperRegistry();
    private final Map<Integer, Body> bodies = new HashMap<>();
    private final Map<Integer, State> states = new HashMap<>();
    private final Map<Integer, SleeperRegistry.Type> types = new HashMap<>();
    private final List<String> trace = new ArrayList<>();
    private long gameTime;

    /** Register an id with its sleep-set type, body, and initial state. */
    public void register(int id, SleeperRegistry.Type type, Body body, State initial) {
        registry.register(id, type);
        bodies.put(id, body);
        states.put(id, initial);
        types.put(id, type);
    }

    /** Read-only access to an id's fixture state, for bodies. */
    public State state(int id) {
        return states.get(id);
    }

    /** Publish an observable effect from inside a tick body. */
    public void emit(String effect) {
        trace.add(gameTime + ":" + effect);
    }

    /** Wake hook fan-in (production: called from the mutation paths). */
    public void wake(int id, SleeperRegistry.WakeSignal signal) {
        registry.wake(id, signal);
    }

    /** Sleep predicate used by the pass: busy state means the body has work. */
    public boolean isIdle(int id) {
        State s = states.get(id);
        return s != null && !s.busy;
    }

    /** One full pass: walk awake ids in order, run bodies, evaluate sleep. */
    public void tick() {
        registry.beginPass();
        List<Long> order = registry.walkOrder();
        for (long id : order) {
            Body b = bodies.get((int) id);
            if (b == null) {
                continue;
            }
            State s = states.get((int) id);
            s.bodyRuns++;
            b.tick(this, (int) id, gameTime);
            registry.reportIdle(id, isIdle((int) id));
        }
        gameTime++;
    }

    public List<String> trace() {
        return trace;
    }

    public long gameTime() {
        return gameTime;
    }

    /** Body-run counts per id, for the walk-size assertion. */
    public Map<Integer, Integer> bodyRuns() {
        Map<Integer, Integer> out = new HashMap<>();
        for (Map.Entry<Integer, State> e : states.entrySet()) {
            out.put(e.getKey(), e.getValue().bodyRuns);
        }
        return out;
    }

    SleeperRegistry registry() {
        return registry;
    }
}
