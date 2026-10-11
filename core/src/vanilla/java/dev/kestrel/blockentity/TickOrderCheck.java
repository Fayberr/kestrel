package dev.kestrel.blockentity;

import java.util.ArrayList;
import java.util.List;

/**
 * Failing-case-first proof for step 5's parity trap, driven against
 * {@link TickOrderModel}.
 *
 * <p><b>The trap.</b> Lithium's {@code SleepingBlockEntity} carries a
 * method called {@code sleepOnlyCurrentTick} with a comment that is worth
 * quoting in full: when block entity A wakes block entity B, whether B
 * then ticks <em>this</em> tick or <em>next</em> tick depends on their
 * relative order in the walked list, and getting it wrong shifts hopper
 * cooldown timing by a tick. A sleeping implementation that re-arms B and
 * runs it immediately is correct when B sits after A and wrong when B
 * sits before A. Per the project rule this is reproduced here as a failing
 * case <em>before</em> anything is built to avoid it.
 *
 * <p><b>What runs here.</b> A two-hopper chain in miniature. B is the
 * downstream hopper: each tick it decrements a cooldown counter and emits
 * an observable effect when the counter reaches zero. A is the upstream
 * hopper: once, it "pushes an item" by setting B's cooldown to 8 (the
 * vanilla chain value) and emitting its own effect. The cooldown counter
 * is shared mutable state standing in for the block-entity fields; the
 * bodies draw no RNG, matching vanilla's hopper path, which draws none in
 * the transfer core.
 *
 * <p><b>Checks:</b>
 *
 * <ol>
 *   <li>Order is observable: B-before-A fires at tick 8, A-before-B fires
 *       at tick 7, traces otherwise identical. This is the foundation the
 *       whole step rests on.</li>
 *   <li>The naive wake diverges: B asleep, A wakes it mid-walk and runs it
 *       inline. The trace fires at tick 7, one tick early versus the
 *       B-before-A vanilla reference. The trap has teeth, and the check
 *       pins down exactly how: naive wake is equivalent to B having sat
 *       after A all along.</li>
 *   <li>The deferred wake matches: same setup, but waking goes through the
 *       pending list (skip the rest of the current tick, exactly Lithium's
 *       fix). The trace is identical to the vanilla reference.</li>
 *   <li>Structural locks on the model itself: mid-walk registration
 *       defers, retire-during-walk suppresses a not-yet-visited body but
 *       leaves an already-visited entry as a one-pass zombie, and
 *       vanilla-style wake appends a fresh-generation wrapper.</li>
 *   <li>Negative control: two identical runs produce identical traces.</li>
 * </ol>
 *
 * <p><b>How to read the verdict.</b> PASS means the trap is live (naive !=
 * vanilla) and the known fix reproduces vanilla exactly (deferred ==
 * vanilla). If a future change to the model makes the naive strategy match
 * vanilla, this check FAILs, which is the correct response: it would mean
 * the transcription lost the property the whole step depends on.
 *
 * <p>No Minecraft classes are touched, so this runs on plain
 * {@code javac}/{@code java} with no vanilla jar, on any machine.
 * Exits non-zero on any mismatch.
 */
public final class TickOrderCheck {
    private TickOrderCheck() {
    }

    private static final int B_ID = 7;
    private static final int A_ID = 3;

    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        checkOrderIsObservable(failures);
        checkNaiveWakeDiverges(failures);
        checkDeferredWakeMatches(failures);
        checkPendingDeferral(failures);
        checkTwoPhaseRetire(failures);
        checkWakeAppends(failures);
        checkDeterminism(failures);

        System.out.println("TickOrderCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    /** Shared hopper-pair state. {@code bCd} is B's cooldown counter. */
    private static final class Pair {
        final int[] bCd = {0};
        final boolean[] aPushed = {false};

        TickOrderModel.Body bBody() {
            return (level, self, t) -> {
                if (bCd[0] > 0) {
                    bCd[0]--;
                    if (bCd[0] == 0) {
                        level.emit("B-fire");
                    }
                }
            };
        }

        TickOrderModel.Body aBodyOnce() {
            return (level, self, t) -> {
                if (!aPushed[0]) {
                    aPushed[0] = true;
                    bCd[0] = 8;
                    level.emit("A-push");
                }
            };
        }
    }

    /** Vanilla reference: both hoppers awake from tick 0, single push, 10 ticks. */
    private static List<String> vanillaTrace(boolean bFirst) {
        TickOrderModel level = new TickOrderModel();
        Pair p = new Pair();
        if (bFirst) {
            level.register(B_ID, p.bBody());
            level.register(A_ID, p.aBodyOnce());
        } else {
            level.register(A_ID, p.aBodyOnce());
            level.register(B_ID, p.bBody());
        }
        for (int i = 0; i < 10; i++) {
            level.tickBlockEntities();
        }
        return level.trace();
    }

    private static void checkOrderIsObservable(List<String> failures) {
        List<String> bFirst = vanillaTrace(true);
        List<String> aFirst = vanillaTrace(false);
        List<String> expectBFirst = List.of("0:A-push", "8:B-fire");
        List<String> expectAFirst = List.of("0:A-push", "7:B-fire");
        if (!bFirst.equals(expectBFirst)) {
            failures.add("order: B-first trace " + bFirst + ", expected " + expectBFirst);
        }
        if (!aFirst.equals(expectAFirst)) {
            failures.add("order: A-first trace " + aFirst + ", expected " + expectAFirst);
        }
        if (bFirst.equals(aFirst)) {
            failures.add("order: B-first and A-first traces identical, order would be unobservable");
        }
    }

    private static void checkNaiveWakeDiverges(List<String> failures) {
        // B starts retired (asleep, cooldown 0). A wakes it mid-walk and,
        // naively, runs its body inline the same tick.
        TickOrderModel level = new TickOrderModel();
        Pair p = new Pair();
        TickOrderModel.Body bBody = p.bBody();
        final TickOrderModel.Entry[] bEntry = {null};
        level.register(A_ID, (l, self, t) -> {
            if (!p.aPushed[0]) {
                p.aPushed[0] = true;
                p.bCd[0] = 8;
                l.emit("A-push");
                bEntry[0] = l.register(B_ID, bBody);
                bBody.tick(l, bEntry[0], t);
            }
        });
        for (int i = 0; i < 10; i++) {
            level.tickBlockEntities();
        }
        List<String> naive = level.trace();
        List<String> reference = vanillaTrace(true);
        if (naive.equals(reference)) {
            failures.add("trap: naive inline wake matched vanilla, the trap is not reproduced");
        }
        if (!naive.equals(List.of("0:A-push", "7:B-fire"))) {
            failures.add("trap: naive trace " + naive + ", expected [0:A-push, 7:B-fire]");
        }
        if (!naive.equals(vanillaTrace(false))) {
            failures.add("trap: naive wake is not equivalent to B-after-A, shape of divergence changed");
        }
    }

    private static void checkDeferredWakeMatches(List<String> failures) {
        // Same setup, but the wake goes through the pending list and B does
        // not run for the rest of the current tick. Lithium's fix.
        TickOrderModel level = new TickOrderModel();
        Pair p = new Pair();
        level.register(A_ID, (l, self, t) -> {
            if (!p.aPushed[0]) {
                p.aPushed[0] = true;
                p.bCd[0] = 8;
                l.emit("A-push");
                l.register(B_ID, p.bBody());
            }
        });
        for (int i = 0; i < 10; i++) {
            level.tickBlockEntities();
        }
        List<String> deferred = level.trace();
        List<String> reference = vanillaTrace(true);
        if (!deferred.equals(reference)) {
            failures.add("fix: deferred wake trace " + deferred + " != vanilla " + reference);
        }
    }

    private static void checkPendingDeferral(List<String> failures) {
        TickOrderModel level = new TickOrderModel();
        final boolean[] added = {false};
        level.register(A_ID, (l, self, t) -> {
            if (!added[0]) {
                added[0] = true;
                l.register(9, (l2, s2, t2) -> l2.emit("C-tick"));
            }
        });
        level.tickBlockEntities();
        if (level.trace().stream().anyMatch(s -> s.equals("0:C-tick"))) {
            failures.add("pending: entry registered mid-walk ticked the same tick");
        }
        level.tickBlockEntities();
        if (level.trace().stream().noneMatch(s -> s.equals("1:C-tick"))) {
            failures.add("pending: deferred entry did not tick the next tick, trace " + level.trace());
        }
    }

    private static void checkTwoPhaseRetire(List<String> failures) {
        // Case 1: B after A. A retires B mid-walk before the walk reaches
        // it: B must not tick this tick, and must be gone after the pass.
        {
            TickOrderModel level = new TickOrderModel();
            final TickOrderModel.Entry[] bEntry = {null};
            final boolean[] bTicked = {false};
            level.register(A_ID, (l, self, t) -> l.retire(bEntry[0]));
            bEntry[0] = level.register(B_ID, (l, self, t) -> {
                bTicked[0] = true;
                l.emit("B-tick");
            });
            level.tickBlockEntities();
            if (bTicked[0]) {
                failures.add("retire: entry after the retire point still ticked");
            }
            if (level.tickers().stream().anyMatch(e -> e.id == B_ID)) {
                failures.add("retire: removed entry still in the walked list after the pass");
            }
        }
        // Case 2: B before A. The walk already ran B this tick, so the
        // retire leaves a zombie: still listed after this pass, gone after
        // the next, and never ticked again.
        {
            TickOrderModel level = new TickOrderModel();
            final TickOrderModel.Entry[] bEntry = {null};
            final int[] bTicks = {0};
            bEntry[0] = level.register(B_ID, (l, self, t) -> {
                bTicks[0]++;
                l.emit("B-tick");
            });
            level.register(A_ID, (l, self, t) -> l.retire(bEntry[0]));
            level.tickBlockEntities();
            if (bTicks[0] != 1) {
                failures.add("retire: already-visited entry ticked " + bTicks[0] + "x, expected 1");
            }
            if (level.tickers().stream().noneMatch(e -> e.id == B_ID)) {
                failures.add("retire: already-visited entry left the list the same pass");
            }
            level.tickBlockEntities();
            if (bTicks[0] != 1) {
                failures.add("retire: zombie entry ticked again");
            }
            if (level.tickers().stream().anyMatch(e -> e.id == B_ID)) {
                failures.add("retire: zombie entry survived two passes");
            }
        }
    }

    private static void checkWakeAppends(List<String> failures) {
        TickOrderModel level = new TickOrderModel();
        level.register(A_ID, (l, self, t) -> {
        });
        TickOrderModel.Entry first = level.register(B_ID, (l, self, t) -> {
        });
        level.retire(first);
        level.tickBlockEntities();
        TickOrderModel.Entry second = level.wakeVanillaStyle(B_ID, (l, self, t) -> {
        });
        List<TickOrderModel.Entry> tickers = level.tickers();
        if (tickers.size() != 2 || tickers.get(0).id != A_ID || tickers.get(1).id != B_ID) {
            failures.add("wake: expected order [A, B], got " + tickers);
        }
        if (second.generation == first.generation) {
            failures.add("wake: expected a fresh wrapper generation on wake");
        }
    }

    private static void checkDeterminism(List<String> failures) {
        if (!vanillaTrace(true).equals(vanillaTrace(true))) {
            failures.add("determinism: two identical runs diverged");
        }
    }
}
