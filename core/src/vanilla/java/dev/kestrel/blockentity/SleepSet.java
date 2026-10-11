package dev.kestrel.blockentity;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 5's ordinal structure: the walked sequence for one tick pass, with
 * sleepers dropped out and both wake modes supported in the same list.
 *
 * <p><b>Why this exists.</b> Vanilla walks {@code blockEntityTickers} in
 * insertion order every tick, and order is observable state (hopper-chain
 * throughput changes when relative tick positions move). Dropping sleepers
 * out of the walk must therefore not permute the survivors, and waking
 * must restore the right position. "Right" has two answers in the same
 * list, and getting them crossed is a parity break in one direction or the
 * other:
 *
 * <ul>
 *   <li>Vanilla's own sleeps (empty jukebox, uprooted creaking heart,
 *       skyless daylight detector) retire the entry via
 *       {@code removeBlockEntityTicker} and wake it with a FRESH wrapper
 *       through {@code addBlockEntityTicker}, which appends. Position is
 *       lost; append IS parity for these.</li>
 *   <li>Sleeps Kestrel adds (data-granular idleness: cold furnace, empty
 *       brewing stand, unedited sign, ...) never removed the entry in
 *       vanilla, so the wake must restore the ORIGINAL ordinal position.
 *       Appending here would be a divergence.</li>
 * </ul>
 *
 * <p>So each entry carries the stable ordinal key vanilla's insertion
 * order implies (assigned on first insertion, never reused), the awake set
 * is walked in ordinal order, and wake takes a mode:
 * {@link WakeMode#PRESERVE_POSITION} re-inserts at the ordinal slot,
 * {@link WakeMode#APPEND} takes a fresh key at the back. See
 * {@code preresearch/block-entity-tick-analysis.md} ("Vanilla already
 * sleeps" and the parity crux).
 *
 * <p><b>Wake-during-walk.</b> Waking while a walk is in progress never
 * runs the woken body this tick, regardless of mode. If the woken ordinal
 * lies ahead of the cursor, running it would double-visit relative to
 * vanilla's pending-list deferral; if it lies behind, running it would
 * execute out of order. Deferred-to-next-tick is exactly Lithium's
 * {@code sleepOnlyCurrentTick} fix, and {@link TickOrderCheck} pins the
 * naive variant as a divergence. The {@code wokenThisPass} set enforces
 * it: entries woken mid-pass are skipped if the walk reaches them before
 * the pass ends.
 *
 * <p><b>What this is NOT.</b> It is not the sleeper registry (idle
 * predicates and wake hooks live with the per-type code) and it is not
 * the tick loop. It is the sequence the loop walks: sleep drops an entry
 * out, wake puts it back in the parity-correct slot, iteration yields
 * awake entries in vanilla order. No Minecraft classes are touched, so it
 * runs on plain {@code javac}/{@code java}.
 *
 * <p>Thread-safety: single tick-thread only, same as vanilla's ticker
 * loop. No synchronization.
 */
public final class SleepSet {

    /** Which parity rule a wake follows. Chosen by the caller per sleep origin. */
    public enum WakeMode {
        /**
         * Kestrel-added sleep: the entry never left vanilla's list, so the
         * wake restores the original ordinal position.
         */
        PRESERVE_POSITION,
        /**
         * Vanilla's own state-null sleep: vanilla retired the wrapper and
         * wakes with a fresh one through {@code addBlockEntityTicker},
         * which appends. Reproduced exactly, position loss included.
         */
        APPEND
    }

    /** One sequence entry. The id is the caller's (a BlockPos in production). */
    public static final class Entry {
        public final long id;
        /** Stable ordinal key: insertion order, never reused, never moves. */
        public final long ordinal;
        private boolean asleep;

        private Entry(long id, long ordinal) {
            this.id = id;
            this.ordinal = ordinal;
        }

        public boolean isAsleep() {
            return asleep;
        }

        @Override
        public String toString() {
            return "id=" + id + " ord=" + ordinal + (asleep ? " asleep" : "");
        }
    }

    /** Dense awake sequence in ordinal order. Dead slots (null) compacted lazily. */
    private final List<Entry> sequence = new ArrayList<>();
    /** Live entries by id, including sleepers. */
    private final java.util.Map<Long, Entry> byId = new java.util.HashMap<>();
    /** Ordinals woken during the current pass: skip if the walk reaches them. */
    private final java.util.Set<Long> wokenThisPass = new java.util.HashSet<>();
    private long nextOrdinal;
    /** Dead (retired) slot count, for the compaction ledger. */
    private int deadSlots;

    /** First insertion, e.g. chunk load or placement. Assigns the ordinal key. */
    public Entry insert(long id) {
        Entry existing = byId.get(id);
        if (existing != null) {
            return existing;
        }
        Entry entry = new Entry(id, nextOrdinal++);
        byId.put(id, entry);
        sequence.add(entry);
        return entry;
    }

    /**
     * Kestrel-added sleep: drop the entry out of the walked sequence. The
     * ordinal key is retained, so a later {@code PRESERVE_POSITION} wake
     * restores the exact slot. Awake relative order is unchanged.
     */
    public void sleep(long id) {
        Entry entry = byId.get(id);
        if (entry == null || entry.asleep) {
            return;
        }
        entry.asleep = true;
    }

    /**
     * Vanilla's own retire (state-null ticker): the entry leaves the walked
     * list AND loses its ordinal, exactly like
     * {@code removeBlockEntityTicker} dropping the wrapper from
     * {@code tickersInLevel}. A later wake must use {@link WakeMode#APPEND}.
     */
    public void retire(long id) {
        Entry entry = byId.remove(id);
        if (entry == null) {
            return;
        }
        entry.asleep = true;
        int idx = indexOf(entry);
        if (idx >= 0) {
            sequence.set(idx, null);
            deadSlots++;
        }
    }

    /**
     * Wake a sleeper. PRESERVE_POSITION restores the ordinal slot (Kestrel
     * sleeps); APPEND assigns a fresh ordinal at the back (vanilla
     * state-null sleeps). A wake issued mid-pass marks the ordinal so the
     * walk skips it this pass (deferred-to-next-tick).
     */
    public void wake(long id, WakeMode mode) {
        if (mode == WakeMode.APPEND) {
            Entry entry = byId.get(id);
            if (entry != null && !entry.asleep) {
                return;
            }
            if (entry != null) {
                int idx = indexOf(entry);
                if (idx >= 0) {
                    sequence.set(idx, null);
                    deadSlots++;
                }
            }
            Entry fresh = new Entry(id, nextOrdinal++);
            fresh.asleep = false;
            byId.put(id, fresh);
            sequence.add(fresh);
            wokenThisPass.add(fresh.ordinal);
            return;
        }
        Entry entry = byId.get(id);
        if (entry == null || !entry.asleep) {
            return;
        }
        entry.asleep = false;
        wokenThisPass.add(entry.ordinal);
    }

    /** Begin a walk pass. Clears the woken-this-pass set. */
    public void beginPass() {
        wokenThisPass.clear();
    }

    /**
     * The awake ids in walk order, skipping sleepers and entries woken
     * mid-pass. Dead (retired) slots are skipped. Callers that need index
     * stability across a pass should snapshot this once per pass.
     */
    public List<Long> walkOrder() {
        List<Long> out = new ArrayList<>(sequence.size());
        for (Entry e : sequence) {
            if (e == null || e.asleep || wokenThisPass.contains(e.ordinal)) {
                continue;
            }
            out.add(e.id);
        }
        return out;
    }

    /**
     * Compact retired slots when they exceed half the sequence. Pure
     * layout: relative order of live entries never changes, ordinals are
     * untouched. Returns the number of slots reclaimed.
     */
    public int compactIfSparse() {
        if (deadSlots * 2 < sequence.size()) {
            return 0;
        }
        int reclaimed = deadSlots;
        sequence.removeIf(e -> e == null);
        deadSlots = 0;
        return reclaimed;
    }

    /** Live entry count (awake + asleep), for assertions. */
    public int liveCount() {
        return byId.size();
    }

    /** Awake entry count, for assertions. */
    public int awakeCount() {
        int n = 0;
        for (Entry e : byId.values()) {
            if (!e.asleep) {
                n++;
            }
        }
        return n;
    }

    Entry entryFor(long id) {
        return byId.get(id);
    }

    private int indexOf(Entry entry) {
        // Ordinal order is insertion order, so an entry's index is stable
        // modulo compaction; linear scan keeps this class dependency-free.
        for (int i = 0; i < sequence.size(); i++) {
            if (sequence.get(i) == entry) {
                return i;
            }
        }
        return -1;
    }
}
