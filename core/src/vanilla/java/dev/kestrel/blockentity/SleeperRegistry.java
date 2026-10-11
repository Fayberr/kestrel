package dev.kestrel.blockentity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 5's sleeper registry for the furnace/sign/shulker first slice: which
 * block entities are asleep, and which wake signal woke them.
 *
 * <p><b>What this is.</b> The per-type bookkeeping around {@link SleepSet}.
 * Each entry carries its sleep-set type; sleeping drops it out of the
 * walked sequence via {@link SleepSet#sleep}, waking restores it via
 * {@link SleepSet#wake} with {@link SleepSet.WakeMode#PRESERVE_POSITION}.
 * PRESERVE is the only mode used here: all ten types are Kestrel-added
 * sleeps whose entries never left vanilla's list, so append-at-end would be
 * a divergence (analysis doc, "Vanilla already sleeps"). Vanilla's own
 * three state-null types go through retire/APPEND on the {@link SleepSet}
 * directly and never touch this registry.
 *
 * <p><b>Wake signals, not a single hook.</b> The item-4 wake table proves a
 * {@code setChanged()}-only hook silently breaks seven of the ten types, a
 * different way per type. So {@link #wake} takes a {@link WakeSignal} naming
 * the observed mutation, and the valid signals per type are exactly the
 * table rows. The three slice types documented first are the shape proof;
 * the remaining seven follow the same fan-in:
 *
 * <ul>
 *   <li>Furnace: {@code setItem} slot 0 refreshes and calls
 *       {@code setChanged}, but a <b>fuel-only insert</b> (slot 1, cold
 *       furnace) calls neither {@code setChanged} nor refreshes
 *       {@code cookingTotalTime} ({@code AbstractFurnaceBlockEntity.setItem},
 *       decompile {@code :247}). {@code FURNACE_SLOT_OTHER} exists so that
 *       path wakes too. Plus {@code setChanged} and {@code setBlock} at
 *       the pos.</li>
 *   <li>Sign: {@code setAllowedPlayerEditor} ({@code :232}) is a bare field
 *       write with no {@code setChanged} on either the open or the edit
 *       path. {@code SIGN_EDITOR_WRITE} is the whole wake set that matters;
 *       {@code SIGN_MARK_UPDATED} covers wax/text.</li>
 *   <li>Shulker: container mutation plus {@code triggerEvent} addressed to
 *       the pos. The open/close path ({@code startOpen}/{@code stopOpen})
 *       only sends {@code blockEvent}; the tick-driving
 *       {@code animationStatus} flips in {@code triggerEvent}
 *       ({@code :142}).</li>
 *   <li>Brewing stand: base container mutation plus {@code HAS_BOTTLE}
 *       {@code setBlock}.</li>
 *   <li>Beehive: {@code addOccupant} (calls {@code setChanged}) plus the
 *       silent {@code storeBee} path (chunk load, NBT restore) plus tick
 *       maturation of resident countdowns; the silent path gets its own
 *       signal so the ledger can require it.</li>
 *   <li>Campfire: {@code placeFood} and {@code clearContent} (bare
 *       {@code items.clear()} on the dowse path) plus empty-slot
 *       {@code setBlock(LIT)} swaps. No container hook exists: the items
 *       list is a plain field.</li>
 *   <li>Crafter: inherited container mutation plus {@code dispenseFrom}
 *       (sets the counter and flips {@code CRAFTING} via {@code setBlock}
 *       directly) plus {@code setBlock} at the pos.</li>
 *   <li>Sculk catalyst: game-event listener arrival ({@code addCursors})
 *       plus direct {@code setBlock(PULSE)}. No {@code setChanged} path
 *       exists at all.</li>
 *   <li>Sculk sensor / shrieker: vibration arrival
 *       ({@code scheduleVibration}/{@code addCandidate} in the listener)
 *       plus in-flight {@code onDataChanged} (which does call
 *       {@code setChanged}).</li>
 * </ul>
 *
 * <p><b>Coverage ledger.</b> Every wake records its signal per type. The
 * proof battery ({@link SleeperCheck}) requires every valid signal to have
 * fired at least once: per the hopper {@code hopadd} lesson, a handoff
 * check over inputs that never trigger passes vacuously. The ledger costs
 * one {@code EnumSet} add per wake; wakes happen on player/container
 * interaction, not per tick, so this is off the hot path.
 *
 * <p>No Minecraft classes are touched (ids are opaque longs;
 * production passes {@code BlockPos.asLong()}), so this runs on plain
 * {@code javac}/{@code java}.
 *
 * <p>Thread-safety: single tick-thread only, same as vanilla's ticker
 * loop. No synchronization.
 */
public final class SleeperRegistry {

    /** Sleep-set types. Hoppers stay with step 2; bell stays deferred. */
    public enum Type {
        FURNACE,
        SIGN,
        SHULKER,
        BREWING_STAND,
        BEEHIVE,
        CAMPFIRE,
        CRAFTER,
        SCULK_CATALYST,
        SCULK_SENSOR,
        SCULK_SHRIEKER
    }

    /**
     * Observable mutations that wake a sleeper. One value per item-4 table
     * row, so the battery can require each row.
     */
    public enum WakeSignal {
        FURNACE_SLOT0,
        FURNACE_SLOT_OTHER,
        FURNACE_SET_CHANGED,
        FURNACE_SET_BLOCK,
        SIGN_EDITOR_WRITE,
        SIGN_MARK_UPDATED,
        SHULKER_CONTAINER,
        SHULKER_TRIGGER_EVENT,
        BREW_CONTAINER,
        BREW_SET_BLOCK,
        BEE_OCCUPANT,
        BEE_STORE_SILENT,
        BEE_SET_CHANGED,
        CAMPFIRE_PLACE_FOOD,
        CAMPFIRE_CLEAR,
        CAMPFIRE_SET_BLOCK,
        CRAFTER_CONTAINER,
        CRAFTER_DISPENSE,
        CRAFTER_SET_BLOCK,
        CATALYST_GAME_EVENT,
        CATALYST_SET_BLOCK,
        VIBRATION_ARRIVAL,
        VIBRATION_DATA_CHANGED
    }

    /** Valid signals per type: the item-4 wake rows, nothing else. */
    private static final Map<Type, Set<WakeSignal>> VALID_SIGNALS = new EnumMap<>(Type.class);

    static {
        VALID_SIGNALS.put(Type.FURNACE, EnumSet.of(
            WakeSignal.FURNACE_SLOT0, WakeSignal.FURNACE_SLOT_OTHER,
            WakeSignal.FURNACE_SET_CHANGED, WakeSignal.FURNACE_SET_BLOCK));
        VALID_SIGNALS.put(Type.SIGN, EnumSet.of(
            WakeSignal.SIGN_EDITOR_WRITE, WakeSignal.SIGN_MARK_UPDATED));
        VALID_SIGNALS.put(Type.SHULKER, EnumSet.of(
            WakeSignal.SHULKER_CONTAINER, WakeSignal.SHULKER_TRIGGER_EVENT));
        VALID_SIGNALS.put(Type.BREWING_STAND, EnumSet.of(
            WakeSignal.BREW_CONTAINER, WakeSignal.BREW_SET_BLOCK));
        VALID_SIGNALS.put(Type.BEEHIVE, EnumSet.of(
            WakeSignal.BEE_OCCUPANT, WakeSignal.BEE_STORE_SILENT,
            WakeSignal.BEE_SET_CHANGED));
        VALID_SIGNALS.put(Type.CAMPFIRE, EnumSet.of(
            WakeSignal.CAMPFIRE_PLACE_FOOD, WakeSignal.CAMPFIRE_CLEAR,
            WakeSignal.CAMPFIRE_SET_BLOCK));
        VALID_SIGNALS.put(Type.CRAFTER, EnumSet.of(
            WakeSignal.CRAFTER_CONTAINER, WakeSignal.CRAFTER_DISPENSE,
            WakeSignal.CRAFTER_SET_BLOCK));
        VALID_SIGNALS.put(Type.SCULK_CATALYST, EnumSet.of(
            WakeSignal.CATALYST_GAME_EVENT, WakeSignal.CATALYST_SET_BLOCK));
        VALID_SIGNALS.put(Type.SCULK_SENSOR, EnumSet.of(
            WakeSignal.VIBRATION_ARRIVAL, WakeSignal.VIBRATION_DATA_CHANGED));
        VALID_SIGNALS.put(Type.SCULK_SHRIEKER, EnumSet.of(
            WakeSignal.VIBRATION_ARRIVAL, WakeSignal.VIBRATION_DATA_CHANGED));
    }

    /** Valid wake signals for a type (unmodifiable view of the item-4 rows). */
    public static Set<WakeSignal> validSignals(Type type) {
        return VALID_SIGNALS.get(type);
    }

    /** Sleep record: type only. Asleep state lives in the SleepSet. */
    private static final class Record {
        final Type type;

        Record(Type type) {
            this.type = type;
        }
    }

    private final SleepSet sleepSet = new SleepSet();
    private final Map<Long, Record> records = new HashMap<>();
    private final Map<Type, EnumSet<WakeSignal>> fired =
        new EnumMap<>(Type.class);

    public SleeperRegistry() {
        for (Type t : Type.values()) {
            fired.put(t, EnumSet.noneOf(WakeSignal.class));
        }
    }

    /** First insertion, e.g. chunk load or placement. Assigns the ordinal key. */
    public void register(long id, Type type) {
        if (records.containsKey(id)) {
            return;
        }
        records.put(id, new Record(type));
        sleepSet.insert(id);
    }

    /**
     * End-of-tick sleep evaluation: the caller has just run the real tick
     * (or the predicate against live state) and reports the verdict.
     * Idle becomes a sleeper; non-idle stays (or returns to) awake.
     * A non-idle report for an asleep entry is defensive: the wake signals
     * should have caught it first, but a sleeper that stops being idle must
     * never stay asleep.
     */
    public void reportIdle(long id, boolean idle) {
        Record r = records.get(id);
        if (r == null) {
            return;
        }
        if (idle) {
            sleepSet.sleep(id);
        } else if (isAsleep(id)) {
            sleepSet.wake(id, SleepSet.WakeMode.PRESERVE_POSITION);
        }
    }

    /**
     * Wake hook fan-in: call from every mutation path in the type's wake
     * set with the matching signal. Always PRESERVE_POSITION (see class
     * javadoc). Signals for the wrong type are ignored: a shulker signal
     * naming a furnace id is a caller bug, and silently waking would hide
     * it from the coverage ledger.
     */
    public void wake(long id, WakeSignal signal) {
        Record r = records.get(id);
        if (r == null || !VALID_SIGNALS.get(r.type).contains(signal)) {
            return;
        }
        fired.get(r.type).add(signal);
        sleepSet.wake(id, SleepSet.WakeMode.PRESERVE_POSITION);
    }

    /** True iff the entry is registered and currently asleep. */
    public boolean isAsleep(long id) {
        if (!records.containsKey(id)) {
            return false;
        }
        SleepSet.Entry e = sleepSet.entryFor(id);
        return e != null && e.isAsleep();
    }

    /** Signals observed so far for a type (copy; for the battery's ledger check). */
    public Set<WakeSignal> firedSignals(Type type) {
        return EnumSet.copyOf(fired.get(type));
    }

    /**
     * Stage 2 seam: dump every registered sleeper sorted by id: each
     * record carries the packed id, the sleep-set type, and the current
     * asleep flag. Ordinal order is intentionally not preserved: ordinals
     * are insertion-sequence artifacts, and restore re-registers in sorted
     * id order so reload converges to one canonical sequence.
     */
    public record SleeperEntry(long id, Type type, boolean asleep) {
    }

    /** Stage 2 seam: all registered sleepers, sorted by id. */
    public List<SleeperEntry> dumpSleepers() {
        List<SleeperEntry> out = new ArrayList<>();
        for (Map.Entry<Long, Record> e : records.entrySet()) {
            long id = e.getKey();
            SleepSet.Entry se = sleepSet.entryFor(id);
            out.add(new SleeperEntry(id, e.getValue().type, se != null && se.isAsleep()));
        }
        out.sort((a, b) -> Long.compare(a.id(), b.id()));
        return out;
    }

    /**
     * Stage 2 seam: restore one sleeper: register (assigns a fresh ordinal
     * in call order) then apply the asleep flag. Callers restore in sorted
     * id order so the post-reload ordinal sequence is canonical.
     */
    public void restoreSleeper(long id, Type type, boolean asleep) {
        register(id, type);
        reportIdle(id, asleep);
    }

    /** Stage 2 seam: drop every registration. Restore calls this first. */
    public void clearAll() {
        records.clear();
        for (Type t : Type.values()) {
            fired.put(t, EnumSet.noneOf(WakeSignal.class));
        }
        // SleepSet has no bulk clear: retire each live id.
        for (long id : new ArrayList<>(sleepSet.walkOrder())) {
            sleepSet.retire(id);
        }
        // Retire misses asleep ids (walkOrder skips sleepers): sweep by id.
        // SleepSet.entryFor is package-visible in the same package.
        for (long id : new ArrayList<>(records.keySet())) {
            if (sleepSet.entryFor(id) != null) {
                sleepSet.retire(id);
            }
        }
        records.clear();
    }

    /** Begin a walk pass. Passthrough for the integration check. */
    public void beginPass() {
        sleepSet.beginPass();
    }

    /** Awake ids in walk order. Passthrough; the loop walks this. */
    public List<Long> walkOrder() {
        return new ArrayList<>(sleepSet.walkOrder());
    }

    /** Owned walk sequence; Leg 3 wires the real loop to this. */
    SleepSet sleepSet() {
        return sleepSet;
    }
}
