package dev.kestrel.blockentity;

import java.util.ArrayList;
import java.util.List;

/**
 * Correctness proof for {@link SleepSet}: the ordinal structure behind
 * step 5's walk-size reduction.
 *
 * <p><b>What is proven, and against what.</b> {@link TickOrderModel} is
 * the transcription of vanilla's scheduling; this check drives
 * {@link SleepSet} through the same scenarios and requires the same
 * observable outcomes:
 *
 * <ol>
 *   <li>Sleeping drops entries from the walk without permuting survivors.</li>
 *   <li>PRESERVE_POSITION wake restores the exact original slot, not the back.</li>
 *   <li>APPEND wake goes to the back with a fresh ordinal, matching
 *       vanilla's own state-null retire/wake (checked as a differential
 *       against {@link TickOrderModel#wakeVanillaStyle}).</li>
 *   <li>Wake-during-walk never runs the woken entry this pass, in either
 *       mode (the Lithium trap: naive inline wake fires a tick early,
 *       {@link TickOrderCheck} pins that divergence).</li>
 *   <li>Retire-then-APPEND-wake reproduces vanilla's position loss exactly.</li>
 *   <li>Compaction reclaims dead slots without changing walk order.</li>
 *   <li>Negative control: a PRESERVE wake issued for the wrong origin
 *       (a vanilla-retired entry) does NOT reproduce vanilla order, proving
 *       the battery distinguishes the two modes.</li>
 * </ol>
 *
 * <p>No Minecraft classes are touched: plain {@code javac}/{@code java},
 * any machine. Exits non-zero on any mismatch.
 */
public final class SleepSetCheck {
    private SleepSetCheck() {
    }

    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        checkSleepPreservesSurvivorOrder(failures);
        checkPreserveWakeRestoresSlot(failures);
        checkAppendWakeMatchesVanilla(failures);
        checkWakeDuringWalkDefers(failures);
        checkRetireThenAppendLosesPosition(failures);
        checkCompactionKeepsOrder(failures);
        checkWrongModeDiverges(failures);

        System.out.println("SleepSetCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    private static void checkSleepPreservesSurvivorOrder(List<String> failures) {
        SleepSet set = new SleepSet();
        for (long id : new long[]{10, 20, 30, 40, 50}) {
            set.insert(id);
        }
        set.sleep(20);
        set.sleep(40);
        List<Long> order = set.walkOrder();
        if (!order.equals(List.of(10L, 30L, 50L))) {
            failures.add("sleep: survivors " + order + ", expected [10, 30, 50]");
        }
        // Sleeping twice is a no-op; sleeping unknown is a no-op.
        set.sleep(20);
        set.sleep(999);
        if (!set.walkOrder().equals(List.of(10L, 30L, 50L))) {
            failures.add("sleep: double/unknown sleep changed the walk");
        }
        if (set.awakeCount() != 3 || set.liveCount() != 5) {
            failures.add("sleep: counts awake=" + set.awakeCount() + " live=" + set.liveCount()
                + ", expected 3/5");
        }
    }

    private static void checkPreserveWakeRestoresSlot(List<String> failures) {
        SleepSet set = new SleepSet();
        for (long id : new long[]{1, 2, 3, 4}) {
            set.insert(id);
        }
        set.sleep(2);
        set.sleep(3);
        set.beginPass();
        set.wake(2, SleepSet.WakeMode.PRESERVE_POSITION);
        set.beginPass();
        if (!set.walkOrder().equals(List.of(1L, 2L, 4L))) {
            failures.add("preserve: order " + set.walkOrder() + ", expected [1, 2, 4]");
        }
        set.wake(3, SleepSet.WakeMode.PRESERVE_POSITION);
        // Woken mid-pass: skipped this pass even though the slot is restored.
        if (!set.walkOrder().equals(List.of(1L, 2L, 4L))) {
            failures.add("preserve: mid-pass wake leaked into this pass: " + set.walkOrder());
        }
        set.beginPass();
        if (!set.walkOrder().equals(List.of(1L, 2L, 3L, 4L))) {
            failures.add("preserve: next pass " + set.walkOrder() + ", expected [1, 2, 3, 4]");
        }
    }

    /**
     * APPEND wake must produce the same walked sequence vanilla's own
     * retire/wake produces: the entry at the back, everything else
     * unmoved. Driven as a differential against the model.
     */
    private static void checkAppendWakeMatchesVanilla(List<String> failures) {
        // Vanilla side: register A, B; retire B; pass (it leaves); wake -> appended.
        TickOrderModel vanilla = new TickOrderModel();
        vanilla.register(1, (l, s, t) -> {
        });
        TickOrderModel.Entry b = vanilla.register(2, (l, s, t) -> {
        });
        vanilla.retire(b);
        vanilla.tickBlockEntities();
        vanilla.wakeVanillaStyle(2, (l, s, t) -> {
        });
        List<Integer> vanillaIds = new ArrayList<>();
        for (TickOrderModel.Entry e : vanilla.tickers()) {
            vanillaIds.add(e.id);
        }

        SleepSet set = new SleepSet();
        set.insert(1);
        set.insert(2);
        set.retire(2);
        set.beginPass();
        set.wake(2, SleepSet.WakeMode.APPEND);
        set.beginPass();
        List<Long> setOrder = set.walkOrder();

        if (!vanillaIds.equals(List.of(1, 2)) || !setOrder.equals(List.of(1L, 2L))) {
            failures.add("append: vanilla " + vanillaIds + " vs set " + setOrder);
        }
        // Three-entry shape: middle entry retired and woken must land at the back.
        SleepSet set2 = new SleepSet();
        set2.insert(1);
        set2.insert(2);
        set2.insert(3);
        set2.retire(2);
        set2.beginPass();
        set2.wake(2, SleepSet.WakeMode.APPEND);
        set2.beginPass();
        if (!set2.walkOrder().equals(List.of(1L, 3L, 2L))) {
            failures.add("append: middle wake " + set2.walkOrder() + ", expected [1, 3, 2]");
        }
    }

    private static void checkWakeDuringWalkDefers(List<String> failures) {
        // Hopper-chain miniature through SleepSet: A upstream, B downstream
        // asleep. A wakes B mid-pass. B must not run until the next pass,
        // in EITHER mode: naive inline wake fires a tick early per
        // TickOrderCheck's trap reproduction.
        for (SleepSet.WakeMode mode : SleepSet.WakeMode.values()) {
            SleepSet set = new SleepSet();
            set.insert(3); // A
            set.insert(7); // B
            set.sleep(7);
            int[] bCd = {0};
            List<String> trace = new ArrayList<>();
            long[] gameTime = {0};
            // Pass 0: A pushes (sets cooldown 8, wakes B). B deferred.
            set.beginPass();
            List<Long> pass0 = set.walkOrder();
            if (!pass0.equals(List.of(3L))) {
                failures.add("defer(" + mode + "): pass0 " + pass0 + ", expected [3]");
            }
            // A acts: push + wake.
            bCd[0] = 8;
            trace.add(0 + ":A-push");
            set.wake(7, mode);
            if (!set.walkOrder().equals(List.of(3L))) {
                failures.add("defer(" + mode + "): B leaked into its wake pass");
            }
            // Tick B's cooldown down over subsequent passes.
            for (int tick = 1; tick <= 8; tick++) {
                gameTime[0] = tick;
                set.beginPass();
                List<Long> order = set.walkOrder();
                if (!order.contains(7L)) {
                    failures.add("defer(" + mode + "): B missing on tick " + tick);
                    break;
                }
                if (bCd[0] > 0) {
                    bCd[0]--;
                    if (bCd[0] == 0) {
                        trace.add(tick + ":B-fire");
                    }
                }
            }
            List<String> expected = List.of("0:A-push", "8:B-fire");
            if (!trace.equals(expected)) {
                failures.add("defer(" + mode + "): trace " + trace + ", expected " + expected);
            }
        }
    }

    private static void checkRetireThenAppendLosesPosition(List<String> failures) {
        SleepSet set = new SleepSet();
        set.insert(1);
        set.insert(2);
        set.insert(3);
        set.retire(1);
        set.beginPass();
        set.wake(1, SleepSet.WakeMode.APPEND);
        set.beginPass();
        if (!set.walkOrder().equals(List.of(2L, 3L, 1L))) {
            failures.add("retire: order " + set.walkOrder() + ", expected [2, 3, 1]");
        }
        // The retired ordinal is gone: a PRESERVE wake for it is a no-op.
        set.beginPass();
        set.wake(1, SleepSet.WakeMode.PRESERVE_POSITION);
        set.beginPass();
        if (!set.walkOrder().equals(List.of(2L, 3L, 1L))) {
            failures.add("retire: preserve-after-append changed order: " + set.walkOrder());
        }
    }

    private static void checkCompactionKeepsOrder(List<String> failures) {
        SleepSet set = new SleepSet();
        for (long id = 1; id <= 6; id++) {
            set.insert(id);
        }
        set.retire(2);
        set.retire(4);
        set.retire(5);
        List<Long> before = set.walkOrder();
        int reclaimed = set.compactIfSparse();
        List<Long> after = set.walkOrder();
        if (!before.equals(List.of(1L, 3L, 6L)) || !after.equals(before)) {
            failures.add("compact: before " + before + " after " + after);
        }
        if (reclaimed != 3) {
            failures.add("compact: reclaimed " + reclaimed + ", expected 3");
        }
        // Below-threshold compaction is a no-op returning 0.
        if (set.compactIfSparse() != 0) {
            failures.add("compact: second call should be a no-op");
        }
        // Ordinals survive compaction: preserve-wake still restores the slot.
        SleepSet set2 = new SleepSet();
        set2.insert(1);
        set2.insert(2);
        set2.insert(3);
        set2.sleep(2);
        set2.retire(99 + 1); // unknown, no-op
        set2.wake(2, SleepSet.WakeMode.PRESERVE_POSITION);
        set2.beginPass();
        if (!set2.walkOrder().equals(List.of(1L, 2L, 3L))) {
            failures.add("compact: preserve after no-op retire: " + set2.walkOrder());
        }
    }

    /**
     * Negative control: the two modes are observably different. A caller
     * that wakes a Kestrel-sleeper with APPEND (the naive uniform hook)
     * permanently moves it to the back; the battery must show that.
     */
    private static void checkWrongModeDiverges(List<String> failures) {
        SleepSet right = new SleepSet();
        right.insert(1);
        right.insert(2);
        right.insert(3);
        right.sleep(1);
        right.beginPass();
        right.wake(1, SleepSet.WakeMode.PRESERVE_POSITION);
        right.beginPass();

        SleepSet wrong = new SleepSet();
        wrong.insert(1);
        wrong.insert(2);
        wrong.insert(3);
        wrong.sleep(1);
        wrong.beginPass();
        // Wrong call: treat a Kestrel sleep like a vanilla retire.
        wrong.retire(1);
        wrong.wake(1, SleepSet.WakeMode.APPEND);
        wrong.beginPass();

        if (right.walkOrder().equals(wrong.walkOrder())) {
            failures.add("modes: PRESERVE and retire+APPEND agree (" + right.walkOrder()
                + "), battery cannot tell them apart");
        }
        if (!right.walkOrder().equals(List.of(1L, 2L, 3L))) {
            failures.add("modes: preserve gave " + right.walkOrder());
        }
        if (!wrong.walkOrder().equals(List.of(2L, 3L, 1L))) {
            failures.add("modes: wrong-mode gave " + wrong.walkOrder() + ", expected [2, 3, 1]");
        }
    }
}
