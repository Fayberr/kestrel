package dev.kestrel.blockentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Conformance battery for {@link SleepLoop}: the Leg-3 pass discipline,
 * proven as a differential against {@link TickOrderModel}.
 *
 * <p><b>What is proven.</b>
 *
 * <ol>
 *   <li>Sleep/wake trace identity: an always-idle sleeper beside an
 *       always-busy body produces exactly the vanilla trace, while running
 *       the sleeper's body exactly once (first pass evaluates, then it
 *       sleeps).</li>
 *   <li>Furnace wake scenario: a sleeper woken mid-run by a container
 *       mutation (the {@code FURNACE_SLOT_OTHER} fuel-only path) resumes
 *       work on the next pass and its trace matches vanilla tick for
 *       tick.</li>
 *   <li>Hopper-chain timing through the loop: a downstream body woken by an
 *       upstream push mid-pass fires on the vanilla tick (the Lithium trap,
 *       now through the registry fan-in rather than the raw set).</li>
 *   <li>Both wake modes side by side: a Kestrel PRESERVE wake and a vanilla
 *       retire/APPEND wake in the same pass each reproduce their own
 *       parity rule.</li>
 *   <li>Walk-size reduction: sleepers stop consuming body runs after the
 *       evaluation pass; the battery asserts the exact run counts, so a
 *       future edit that silently re-walks sleepers fails loudly.</li>
 * </ol>
 *
 * <p>No Minecraft classes are touched: plain {@code javac}/{@code java},
 * any machine. Exits non-zero on any mismatch.
 */
public final class SleepLoopCheck {
    private SleepLoopCheck() {
    }

    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        checkIdleSleeperMatchesVanilla(failures);
        checkFurnaceWakeMatchesVanilla(failures);
        checkChainTimingThroughLoop(failures);
        checkBothModesSideBySide(failures);

        System.out.println("SleepLoopCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    /** Shared fixture: busy body emits every pass; idle body emits once then sleeps. */
    private static SleepLoop.State busyState(String effect) {
        SleepLoop.State s = new SleepLoop.State();
        s.busy = true;
        s.effect = effect;
        return s;
    }

    private static void checkIdleSleeperMatchesVanilla(List<String> failures) {
        // Vanilla side: both bodies run every pass for 6 passes.
        TickOrderModel vanilla = new TickOrderModel();
        vanilla.register(1, (l, self, t) -> l.emit("busy"));
        vanilla.register(2, (l, self, t) -> {
        });
        for (int i = 0; i < 6; i++) {
            vanilla.tickBlockEntities();
        }

        // Sleep side: id 2 goes idle after its first evaluation pass.
        SleepLoop loop = new SleepLoop();
        loop.register(1, SleeperRegistry.Type.FURNACE,
            (l, id, t) -> l.emit("busy"), busyState("busy"));
        SleepLoop.State idle = new SleepLoop.State();
        idle.busy = false;
        loop.register(2, SleeperRegistry.Type.SIGN, (l, id, t) -> {
        }, idle);
        for (int i = 0; i < 6; i++) {
            loop.tick();
        }

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            expected.add(i + ":busy");
        }
        if (!vanilla.trace().equals(expected)) {
            failures.add("idle: vanilla trace " + vanilla.trace() + ", expected " + expected);
        }
        if (!loop.trace().equals(vanilla.trace())) {
            failures.add("idle: sleep trace " + loop.trace() + " != vanilla " + vanilla.trace());
        }
        Map<Integer, Integer> runs = loop.bodyRuns();
        if (runs.get(2) != 1) {
            failures.add("idle: sleeper body ran " + runs.get(2) + "x, expected 1");
        }
        if (runs.get(1) != 6) {
            failures.add("idle: busy body ran " + runs.get(1) + "x, expected 6");
        }
    }

    private static void checkFurnaceWakeMatchesVanilla(List<String> failures) {
        // Both sides: a furnace that starts idle, gets fuel at tick 2
        // (external mutation between passes), works ticks 3-4, goes idle.
        // Vanilla runs the body every pass and the body no-ops when idle;
        // the sleep side skips the idle passes. Traces must agree exactly.
        TickOrderModel vanilla = new TickOrderModel();
        final boolean[] vFuel = {false};
        final int[] vWork = {0};
        vanilla.register(5, (l, self, t) -> {
            if (vFuel[0] && vWork[0] > 0) {
                vWork[0]--;
                l.emit("smelt");
                if (vWork[0] == 0) {
                    vFuel[0] = false;
                }
            }
        });
        for (int i = 0; i < 6; i++) {
            if (i == 2) {
                vFuel[0] = true;
                vWork[0] = 2;
            }
            vanilla.tickBlockEntities();
        }

        SleepLoop loop = new SleepLoop();
        SleepLoop.State fur = new SleepLoop.State();
        fur.busy = false;
        final int[] work = {0};
        loop.register(5, SleeperRegistry.Type.FURNACE, (l, id, t) -> {
            SleepLoop.State s = l.state(id);
            if (s.busy && work[0] > 0) {
                work[0]--;
                l.emit("smelt");
                if (work[0] == 0) {
                    s.busy = false;
                }
            }
        }, fur);
        for (int i = 0; i < 6; i++) {
            if (i == 2) {
                fur.busy = true;
                work[0] = 2;
                // The fuel-only insert path: no setChanged, still wakes.
                loop.wake(5, SleeperRegistry.WakeSignal.FURNACE_SLOT_OTHER);
            }
            loop.tick();
        }

        if (!loop.trace().equals(vanilla.trace())) {
            failures.add("furnace-wake: sleep trace " + loop.trace()
                + " != vanilla " + vanilla.trace());
        }
        if (!vanilla.trace().equals(List.of("2:smelt", "3:smelt"))) {
            failures.add("furnace-wake: vanilla trace " + vanilla.trace()
                + ", expected [2:smelt, 3:smelt]");
        }
    }

    private static void checkChainTimingThroughLoop(List<String> failures) {
        // Upstream A pushes once at tick 0, setting B's cooldown to 2 and
        // waking B mid-pass. B must fire at tick 2 (B-before-A reference),
        // not tick 1 (naive inline wake). Mirrors TickOrderCheck's trap
        // through the registry fan-in.
        TickOrderModel vanilla = new TickOrderModel();
        final int[] vCd = {0};
        final boolean[] vPushed = {false};
        vanilla.register(7, (l, self, t) -> {
            if (vCd[0] > 0) {
                vCd[0]--;
                if (vCd[0] == 0) {
                    l.emit("B-fire");
                }
            }
        });
        vanilla.register(3, (l, self, t) -> {
            if (!vPushed[0]) {
                vPushed[0] = true;
                vCd[0] = 2;
                l.emit("A-push");
            }
        });
        for (int i = 0; i < 5; i++) {
            vanilla.tickBlockEntities();
        }

        SleepLoop loop = new SleepLoop();
        SleepLoop.State bState = new SleepLoop.State();
        bState.busy = false;
        final int[] cd = {0};
        final boolean[] pushed = {false};
        loop.register(7, SleeperRegistry.Type.SHULKER, (l, id, t) -> {
            SleepLoop.State s = l.state(id);
            if (cd[0] > 0) {
                cd[0]--;
                if (cd[0] == 0) {
                    l.emit("B-fire");
                    s.busy = false;
                }
            }
        }, bState);
        SleepLoop.State aState = new SleepLoop.State();
        aState.busy = true;
        loop.register(3, SleeperRegistry.Type.FURNACE, (l, id, t) -> {
            if (!pushed[0]) {
                pushed[0] = true;
                cd[0] = 2;
                l.emit("A-push");
                l.state(7).busy = true;
                // B sits BEFORE A in insertion order; this mid-pass wake
                // must defer to next pass or B fires a tick early.
                l.wake(7, SleeperRegistry.WakeSignal.SHULKER_TRIGGER_EVENT);
                l.state(id).busy = false;
            }
        }, aState);
        for (int i = 0; i < 5; i++) {
            loop.tick();
        }

        List<String> expected = List.of("0:A-push", "2:B-fire");
        if (!vanilla.trace().equals(expected)) {
            failures.add("chain: vanilla trace " + vanilla.trace() + ", expected " + expected);
        }
        if (!loop.trace().equals(vanilla.trace())) {
            failures.add("chain: sleep trace " + loop.trace()
                + " != vanilla " + vanilla.trace());
        }
    }

    private static void checkBothModesSideBySide(List<String> failures) {
        // One pass shape: Kestrel sleeper (PRESERVE) and vanilla-retired
        // entry (APPEND) wake in the same run; each follows its own rule.
        // Asserted through the owned sequences directly: registry order
        // restores the slot, raw-set APPEND order goes to the back.
        SleeperRegistry reg = new SleeperRegistry();
        reg.register(1, SleeperRegistry.Type.FURNACE);
        reg.register(2, SleeperRegistry.Type.SIGN);
        reg.register(3, SleeperRegistry.Type.SHULKER);
        reg.reportIdle(1, true);
        reg.beginPass();
        reg.wake(1, SleeperRegistry.WakeSignal.FURNACE_SLOT0);
        reg.beginPass();

        SleepSet set = new SleepSet();
        set.insert(1);
        set.insert(2);
        set.insert(3);
        set.retire(2);
        set.beginPass();
        set.wake(2, SleepSet.WakeMode.APPEND);
        set.beginPass();

        if (!reg.walkOrder().equals(List.of(1L, 2L, 3L))) {
            failures.add("modes: registry preserve gave " + reg.walkOrder());
        }
        if (!set.walkOrder().equals(List.of(1L, 3L, 2L))) {
            failures.add("modes: raw-set append gave " + set.walkOrder()
                + ", expected [1, 3, 2]");
        }
    }
}
