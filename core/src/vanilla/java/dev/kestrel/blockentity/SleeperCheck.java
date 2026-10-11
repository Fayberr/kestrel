package dev.kestrel.blockentity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Correctness proof for {@link SleepPredicates} and {@link SleeperRegistry}:
 * the per-type half of step 5's furnace/sign/shulker first slice.
 *
 * <p><b>What is proven.</b>
 *
 * <ol>
 *   <li>Predicate truth tables: idle fixtures sleep, every non-idle shape
 *       stays awake, including the load-bearing edges (furnace with a
 *       nonzero cooldown timer that the {@code else-if} clamp branch would
 *       still write; shulker settling tick with stale nonzero
 *       {@code progressOld}; pending loot table vetoing both container
 *       types).</li>
 *   <li>Wake-signal coverage: every {@link SleeperRegistry.WakeSignal} wakes
 *       an asleep entry of its type. Wrong-type signals and unknown ids are
 *       ignored (negative control).</li>
 *   <li>Coverage ledger: a registry that observed all signals reports full
 *       coverage; a fresh registry reports none. Per the hopper
 *       {@code hopadd} lesson, the battery must fail if a wake path never
 *       fires, so the ledger check is asserted, not advisory.</li>
 *   <li>Registry/walk integration: sleeping through the registry drops the
 *       entry from the walked sequence without permuting survivors;
 *       PRESERVE wake restores the slot; mid-pass wake defers to next pass
 *       (the Lithium trap, both directions already pinned in
 *       {@link SleepSetCheck}/{@link TickOrderCheck}, here proven through
 *       the registry fan-in).</li>
 *   <li>Wrong-mode analogue: a Kestrel sleeper woken through the vanilla
 *       APPEND path lands at the back, observably diverged from PRESERVE.
 *       Proves the caller must pick the mode per sleep origin.</li>
 * </ol>
 *
 * <p><b>What is NOT proven here.</b> Predicate-vs-live-state agreement is
 * not re-proven headlessly: the predicates are scalar snapshots, and their
 * agreement with real vanilla block entities was already measured live by
 * {@code BlockEntityCensus} on bench PC (87.5% idle, CENSUS_COMPARE PASS over
 * 1578 ticks, commit 7dc57be). Rebuilding live block entities in a bare
 * bootstrap just to re-read the same fields would prove the fixture wiring,
 * not the predicate. The harness differential (plan Leg 4) is where live
 * agreement gets re-checked under wake events.
 *
 * <p>No Minecraft classes are touched: plain {@code javac}/{@code java},
 * any machine. Exits non-zero on any mismatch.
 */
public final class SleeperCheck {
    private SleeperCheck() {
    }

    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        checkFurnacePredicate(failures);
        checkSignPredicate(failures);
        checkShulkerPredicate(failures);
        checkBrewingStandPredicate(failures);
        checkBeehivePredicate(failures);
        checkCampfirePredicate(failures);
        checkCrafterPredicate(failures);
        checkSculkCatalystPredicate(failures);
        checkVibrationPredicate(failures);
        checkWakeCoverage(failures);
        checkNegativeControls(failures);
        checkCoverageLedger(failures);
        checkRegistryWalkIntegration(failures);
        checkWrongModeDiverges(failures);

        System.out.println("SleeperCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    private static void checkFurnacePredicate(List<String> failures) {
        // Idle shapes: cold, timer zero, at least one slot empty.
        if (!SleepPredicates.furnaceIdle(0, 0, true, true, false)) {
            failures.add("furnace: empty cold furnace not idle");
        }
        if (!SleepPredicates.furnaceIdle(0, 0, true, false, false)) {
            failures.add("furnace: fuel-only cold furnace not idle");
        }
        if (!SleepPredicates.furnaceIdle(0, 0, false, true, false)) {
            failures.add("furnace: ingredient-only cold furnace not idle");
        }
        // Non-idle: burning (decrement writes), smelting (timer work).
        if (SleepPredicates.furnaceIdle(5, 0, true, true, false)) {
            failures.add("furnace: lit furnace counted idle");
        }
        if (SleepPredicates.furnaceIdle(1, 40, false, false, false)) {
            failures.add("furnace: active smelt counted idle");
        }
        // Load-bearing edge: cold with a nonzero timer. The else-if clamp
        // branch (Mth.clamp(timer - 2, ...)) still writes, so this is work.
        if (SleepPredicates.furnaceIdle(0, 3, true, true, false)) {
            failures.add("furnace: cold furnace with nonzero timer counted idle (clamp branch writes)");
        }
        // Both slots filled, cold: predicate stays conservative (awake) so
        // no recipe decision is cached across the sleep. The recipe gap is
        // deliberate, not an oversight.
        if (SleepPredicates.furnaceIdle(0, 0, false, false, false)) {
            failures.add("furnace: both-slots-full counted idle (recipe gap must stay awake)");
        }
        // Loot-table guard: pending table vetoes sleep.
        if (SleepPredicates.furnaceIdle(0, 0, true, true, true)) {
            failures.add("furnace: pending loot table did not veto idle");
        }
    }

    private static void checkSignPredicate(List<String> failures) {
        if (!SleepPredicates.signIdle(true)) {
            failures.add("sign: unedited sign not idle");
        }
        if (SleepPredicates.signIdle(false)) {
            failures.add("sign: sign open for edit counted idle");
        }
    }

    private static void checkShulkerPredicate(List<String> failures) {
        if (!SleepPredicates.shulkerIdle(true, 0.0F, 0.0F, false)) {
            failures.add("shulker: closed settled box not idle");
        }
        // Settling tick: status already CLOSED but progressOld still stale.
        // That tick has a real write to do.
        if (SleepPredicates.shulkerIdle(true, 0.0F, 0.1F, false)) {
            failures.add("shulker: settling tick counted idle (progressOld still stale)");
        }
        if (SleepPredicates.shulkerIdle(false, 0.5F, 0.4F, false)) {
            failures.add("shulker: opening box counted idle");
        }
        if (SleepPredicates.shulkerIdle(false, 1.0F, 1.0F, false)) {
            failures.add("shulker: OPENED steady state counted idle (conservative: stays awake)");
        }
        if (SleepPredicates.shulkerIdle(true, 0.0F, 0.0F, true)) {
            failures.add("shulker: pending loot table did not veto idle");
        }
    }

    private static void expectWake(List<String> failures, SleeperRegistry.Type type,
            SleeperRegistry.WakeSignal signal, long id) {
        SleeperRegistry reg = new SleeperRegistry();
        reg.register(id, type);
        reg.reportIdle(id, true);
        if (!reg.isAsleep(id)) {
            failures.add("wake(" + signal + "): entry did not fall asleep; cannot test the wake");
            return;
        }
        reg.wake(id, signal);
        if (reg.isAsleep(id)) {
            failures.add("wake(" + signal + "): asleep entry stayed asleep");
        }
    }

    private static void checkWakeCoverage(List<String> failures) {
        expectWake(failures, SleeperRegistry.Type.FURNACE,
            SleeperRegistry.WakeSignal.FURNACE_SLOT0, 11);
        expectWake(failures, SleeperRegistry.Type.FURNACE,
            SleeperRegistry.WakeSignal.FURNACE_SLOT_OTHER, 12);
        expectWake(failures, SleeperRegistry.Type.FURNACE,
            SleeperRegistry.WakeSignal.FURNACE_SET_CHANGED, 13);
        expectWake(failures, SleeperRegistry.Type.FURNACE,
            SleeperRegistry.WakeSignal.FURNACE_SET_BLOCK, 14);
        expectWake(failures, SleeperRegistry.Type.SIGN,
            SleeperRegistry.WakeSignal.SIGN_EDITOR_WRITE, 21);
        expectWake(failures, SleeperRegistry.Type.SIGN,
            SleeperRegistry.WakeSignal.SIGN_MARK_UPDATED, 22);
        expectWake(failures, SleeperRegistry.Type.SHULKER,
            SleeperRegistry.WakeSignal.SHULKER_CONTAINER, 31);
        expectWake(failures, SleeperRegistry.Type.SHULKER,
            SleeperRegistry.WakeSignal.SHULKER_TRIGGER_EVENT, 32);
        expectWake(failures, SleeperRegistry.Type.BREWING_STAND,
            SleeperRegistry.WakeSignal.BREW_CONTAINER, 41);
        expectWake(failures, SleeperRegistry.Type.BREWING_STAND,
            SleeperRegistry.WakeSignal.BREW_SET_BLOCK, 42);
        expectWake(failures, SleeperRegistry.Type.BEEHIVE,
            SleeperRegistry.WakeSignal.BEE_OCCUPANT, 51);
        expectWake(failures, SleeperRegistry.Type.BEEHIVE,
            SleeperRegistry.WakeSignal.BEE_STORE_SILENT, 52);
        expectWake(failures, SleeperRegistry.Type.BEEHIVE,
            SleeperRegistry.WakeSignal.BEE_SET_CHANGED, 53);
        expectWake(failures, SleeperRegistry.Type.CAMPFIRE,
            SleeperRegistry.WakeSignal.CAMPFIRE_PLACE_FOOD, 61);
        expectWake(failures, SleeperRegistry.Type.CAMPFIRE,
            SleeperRegistry.WakeSignal.CAMPFIRE_CLEAR, 62);
        expectWake(failures, SleeperRegistry.Type.CAMPFIRE,
            SleeperRegistry.WakeSignal.CAMPFIRE_SET_BLOCK, 63);
        expectWake(failures, SleeperRegistry.Type.CRAFTER,
            SleeperRegistry.WakeSignal.CRAFTER_CONTAINER, 71);
        expectWake(failures, SleeperRegistry.Type.CRAFTER,
            SleeperRegistry.WakeSignal.CRAFTER_DISPENSE, 72);
        expectWake(failures, SleeperRegistry.Type.CRAFTER,
            SleeperRegistry.WakeSignal.CRAFTER_SET_BLOCK, 73);
        expectWake(failures, SleeperRegistry.Type.SCULK_CATALYST,
            SleeperRegistry.WakeSignal.CATALYST_GAME_EVENT, 81);
        expectWake(failures, SleeperRegistry.Type.SCULK_CATALYST,
            SleeperRegistry.WakeSignal.CATALYST_SET_BLOCK, 82);
        expectWake(failures, SleeperRegistry.Type.SCULK_SENSOR,
            SleeperRegistry.WakeSignal.VIBRATION_ARRIVAL, 91);
        expectWake(failures, SleeperRegistry.Type.SCULK_SENSOR,
            SleeperRegistry.WakeSignal.VIBRATION_DATA_CHANGED, 92);
        expectWake(failures, SleeperRegistry.Type.SCULK_SHRIEKER,
            SleeperRegistry.WakeSignal.VIBRATION_ARRIVAL, 93);
        expectWake(failures, SleeperRegistry.Type.SCULK_SHRIEKER,
            SleeperRegistry.WakeSignal.VIBRATION_DATA_CHANGED, 94);
    }

    private static void checkBrewingStandPredicate(List<String> failures) {
        if (!SleepPredicates.brewingStandIdle(0, 5, false, false)) {
            failures.add("brew: empty cold stand not idle");
        }
        if (SleepPredicates.brewingStandIdle(200, 5, false, false)) {
            failures.add("brew: brewing stand counted idle");
        }
        // Refuel write: empty fuel plus brewing fuel in slot 4 writes.
        if (SleepPredicates.brewingStandIdle(0, 0, true, false)) {
            failures.add("brew: refuel write counted idle");
        }
        // Brewable with fuel: the start-brewing branch writes next tick.
        if (SleepPredicates.brewingStandIdle(0, 5, false, true)) {
            failures.add("brew: brew-start pending counted idle");
        }
        // Cheap-prefix conservatism: brewable false is required; the bits
        // clause is deliberately not evaluated here.
        if (!SleepPredicates.brewingStandIdle(0, 0, false, false)) {
            failures.add("brew: unfueled non-brewable stand not idle");
        }
    }

    private static void checkBeehivePredicate(List<String> failures) {
        if (!SleepPredicates.beehiveIdle(true)) {
            failures.add("beehive: empty hive not idle");
        }
        if (SleepPredicates.beehiveIdle(false)) {
            failures.add("beehive: occupied hive counted idle");
        }
    }

    private static void checkCampfirePredicate(List<String> failures) {
        if (!SleepPredicates.campfireIdle(true, true, false)) {
            failures.add("campfire: lit empty fire not idle");
        }
        if (SleepPredicates.campfireIdle(true, false, false)) {
            failures.add("campfire: lit cooking fire counted idle");
        }
        if (!SleepPredicates.campfireIdle(false, false, true)) {
            failures.add("campfire: unlit settled fire not idle");
        }
        // Load-bearing edge: unlit with nonzero progress. The cooldown
        // branch still decrements, so this is work.
        if (SleepPredicates.campfireIdle(false, false, false)) {
            failures.add("campfire: unlit cooling fire counted idle (clamp branch writes)");
        }
    }

    private static void checkCrafterPredicate(List<String> failures) {
        if (!SleepPredicates.crafterIdle(0, false)) {
            failures.add("crafter: never-triggered crafter not idle");
        }
        if (!SleepPredicates.crafterIdle(-3, false)) {
            failures.add("crafter: post-craft negative counter not idle");
        }
        if (SleepPredicates.crafterIdle(6, false)) {
            failures.add("crafter: mid-craft crafter counted idle");
        }
        if (SleepPredicates.crafterIdle(0, true)) {
            failures.add("crafter: pending loot table did not veto idle");
        }
    }

    private static void checkSculkCatalystPredicate(List<String> failures) {
        if (!SleepPredicates.sculkCatalystIdle(true)) {
            failures.add("catalyst: cursor-free spreader not idle");
        }
        if (SleepPredicates.sculkCatalystIdle(false)) {
            failures.add("catalyst: spreading cursors counted idle");
        }
    }

    private static void checkVibrationPredicate(List<String> failures) {
        if (!SleepPredicates.vibrationIdle(true, true)) {
            failures.add("vibration: quiet sensor not idle");
        }
        if (SleepPredicates.vibrationIdle(false, true)) {
            failures.add("vibration: in-flight vibration counted idle");
        }
        // Queued candidate: schedules next tick even with nothing in flight.
        if (SleepPredicates.vibrationIdle(true, false)) {
            failures.add("vibration: queued candidate counted idle");
        }
    }

    private static void checkNegativeControls(List<String> failures) {
        SleeperRegistry reg = new SleeperRegistry();
        reg.register(1, SleeperRegistry.Type.FURNACE);
        reg.register(2, SleeperRegistry.Type.SIGN);
        reg.reportIdle(1, true);
        reg.reportIdle(2, true);
        // Wrong-type signal: a shulker signal naming a furnace id is a
        // caller bug; the registry must not wake, so the ledger stays honest.
        reg.wake(1, SleeperRegistry.WakeSignal.SHULKER_TRIGGER_EVENT);
        if (!reg.isAsleep(1)) {
            failures.add("negative: wrong-type signal woke a furnace");
        }
        // Unknown id: no-op.
        reg.wake(999, SleeperRegistry.WakeSignal.FURNACE_SLOT0);
        // Non-idle report wakes defensively (wake signals should have caught
        // it first, but a sleeper that stops being idle must not stay asleep).
        reg.reportIdle(2, false);
        if (reg.isAsleep(2)) {
            failures.add("negative: non-idle report left a sleeper asleep");
        }
        if (!reg.isAsleep(1)) {
            failures.add("negative: control furnace should still be asleep");
        }
        // reportIdle on unknown id: no-op, no throw.
        reg.reportIdle(999, true);
    }

    private static void checkCoverageLedger(List<String> failures) {
        // Fresh registry: nothing fired. Proves the ledger starts empty, so
        // the full-coverage assertion below is not vacuous.
        SleeperRegistry fresh = new SleeperRegistry();
        for (SleeperRegistry.Type t : SleeperRegistry.Type.values()) {
            if (!fresh.firedSignals(t).isEmpty()) {
                failures.add("ledger: fresh registry already reports fired signals for " + t);
            }
        }
        // Drive every valid signal, then require full coverage per type.
        SleeperRegistry reg = new SleeperRegistry();
        long id = 100;
        for (SleeperRegistry.Type t : SleeperRegistry.Type.values()) {
            for (SleeperRegistry.WakeSignal s : SleeperRegistry.validSignals(t)) {
                reg.register(id, t);
                reg.reportIdle(id, true);
                reg.wake(id, s);
                id++;
            }
        }
        for (SleeperRegistry.Type t : SleeperRegistry.Type.values()) {
            Set<SleeperRegistry.WakeSignal> want =
                EnumSet.copyOf(SleeperRegistry.validSignals(t));
            Set<SleeperRegistry.WakeSignal> got = reg.firedSignals(t);
            if (!got.equals(want)) {
                failures.add("ledger: " + t + " fired " + got + ", expected " + want);
            }
        }
    }

    private static void checkRegistryWalkIntegration(List<String> failures) {
        SleeperRegistry reg = new SleeperRegistry();
        reg.register(1, SleeperRegistry.Type.FURNACE);
        reg.register(2, SleeperRegistry.Type.SIGN);
        reg.register(3, SleeperRegistry.Type.SHULKER);
        reg.register(4, SleeperRegistry.Type.FURNACE);
        reg.reportIdle(2, true);
        reg.reportIdle(3, true);
        if (!reg.walkOrder().equals(List.of(1L, 4L))) {
            failures.add("walk: sleepers not dropped, order " + reg.walkOrder());
        }
        // PRESERVE wake restores the slot, but a mid-pass wake defers.
        reg.beginPass();
        reg.wake(2, SleeperRegistry.WakeSignal.SIGN_EDITOR_WRITE);
        if (!reg.walkOrder().equals(List.of(1L, 4L))) {
            failures.add("walk: mid-pass wake leaked into this pass: " + reg.walkOrder());
        }
        reg.beginPass();
        if (!reg.walkOrder().equals(List.of(1L, 2L, 4L))) {
            failures.add("walk: preserve wake did not restore slot: " + reg.walkOrder());
        }
    }

    private static void checkWrongModeDiverges(List<String> failures) {
        // Same three-entry shape twice: once through the registry
        // (PRESERVE), once through the raw SleepSet APPEND path. The orders
        // must observably differ, proving the caller has to pick the mode.
        SleeperRegistry right = new SleeperRegistry();
        right.register(1, SleeperRegistry.Type.FURNACE);
        right.register(2, SleeperRegistry.Type.SIGN);
        right.register(3, SleeperRegistry.Type.SHULKER);
        right.reportIdle(1, true);
        right.beginPass();
        right.wake(1, SleeperRegistry.WakeSignal.FURNACE_SLOT0);
        right.beginPass();

        SleepSet wrong = new SleepSet();
        wrong.insert(1);
        wrong.insert(2);
        wrong.insert(3);
        wrong.sleep(1);
        wrong.beginPass();
        wrong.wake(1, SleepSet.WakeMode.APPEND);
        wrong.beginPass();

        if (!right.walkOrder().equals(List.of(1L, 2L, 3L))) {
            failures.add("modes: preserve gave " + right.walkOrder());
        }
        if (!wrong.walkOrder().equals(List.of(2L, 3L, 1L))) {
            failures.add("modes: append gave " + wrong.walkOrder() + ", expected [2, 3, 1]");
        }
        if (right.walkOrder().equals(wrong.walkOrder())) {
            failures.add("modes: PRESERVE and APPEND agree, battery cannot tell them apart");
        }
    }
}
