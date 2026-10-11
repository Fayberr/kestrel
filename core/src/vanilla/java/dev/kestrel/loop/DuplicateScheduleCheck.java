package dev.kestrel.loop;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression gate for High Finding H4
 * (docs/ARCHITECTURE-AUDIT.md, "Scheduler and settle assumptions do not
 * establish exact vanilla ordering"), scheduler part.
 *
 * <p><b>The defect (fixed):</b> {@link TickLoop#scheduleTick} used to
 * cancel a pending tick and replace it when a duplicate arrived with an
 * earlier trigger, so "delay 10, then delay 1" fired at tick 1. It also
 * ran each due tick as soon as it was polled, so a delay-0 schedule made
 * by a running tick fired again in the same tick, and the facade priority
 * enum collapsed VERY_LOW into VERY_HIGH.
 *
 * <p><b>Vanilla, which every case here runs side by side:</b>
 * <ul>
 *   <li>{@code LevelChunkTicks.schedule} enqueues only when
 *       {@code ticksPerPosition.add} succeeds: first schedule wins, whatever
 *       the duplicate's delay or priority.</li>
 *   <li>{@code LevelTicks.tick} first collects every due tick (removing it
 *       from {@code ticksPerPosition}), then runs the collected list. A tick
 *       scheduled while running lands in a later tick, and
 *       {@code willTickThisTick} reads the collected-not-yet-run set.</li>
 *   <li>{@code TickPriority} has seven values (-3..3); equal-time ticks
 *       drain by priority value, then by sub-tick order.</li>
 * </ul>
 *
 * <p>Clock alignment: {@link TickLoop#tick} follows {@code ServerLevel.tick}
 * and advances gameTime BEFORE draining, so the k-th {@code tick()} of a
 * fresh loop drains at gameTime k, while the bare {@code LevelTicks}
 * reference here drains at {@code now = k - 1}. The Kestrel side therefore
 * schedules from outside a tick with one extra tick of delay (trigger =
 * first drain time) and reports fire times as {@code gameTime - 1}.
 *
 * <p>Default mode asserts vanilla parity and exits 1 on any difference.
 * {@code --demonstrate-defect} is kept as an alias that now fails, so an
 * old invocation cannot silently report the defect as present.
 */
public final class DuplicateScheduleCheck {
    private DuplicateScheduleCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        System.out.println("=== DuplicateScheduleCheck: High Finding H4 (regression gate) ===");
        if (args.length > 0 && args[0].equals("--demonstrate-defect")) {
            failures.add("--demonstrate-defect: H4 is fixed; this gate only asserts vanilla parity now");
        }

        checkDuplicate(10, 1, failures);
        checkDuplicate(1, 10, failures);
        checkDuplicatePriority(failures);
        checkCollectThenRun(failures);
        checkWillTickThisTick(failures);
        checkPriorityMapping(failures);
        checkEqualTimePriorityOrderAndRestore(failures);

        boolean passed = failures.isEmpty();
        System.out.println();
        System.out.println("DuplicateScheduleCheck: " + (passed ? "PASS" : "FAIL"));
        if (!passed) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    static void expect(boolean cond, String what, List<String> failures) {
        if (!cond) {
            failures.add(what);
        }
    }

    // ---- fixtures ----

    /** A loop with a lit torch on stone at each position (the torch body is then a no-op). */
    private static TickLoop torchLoop(KestrelPos... positions) {
        TickLoop loop = new TickLoop(new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
        for (KestrelPos pos : positions) {
            loop.setBlock(new KestrelPos(pos.x(), pos.y() - 1, pos.z()),
                    KestrelState.wrap(Blocks.STONE.defaultBlockState()), 3);
            loop.setBlock(pos, KestrelState.wrap(Blocks.REDSTONE_TORCH.defaultBlockState()), 3);
        }
        return loop;
    }

    private static KestrelTickTarget torch(KestrelTickTarget.Priority p) {
        return new KestrelTickTarget(KestrelTickTarget.Kind.REDSTONE_TORCH, p);
    }

    /** Vanilla scheduler with one loaded chunk container at (0, 0). */
    private static LevelTicks<Block> vanillaTicks() {
        LevelTicks<Block> ticks = new LevelTicks<>(chunk -> true);
        ticks.addContainer(new ChunkPos(0, 0), new LevelChunkTicks<>());
        return ticks;
    }

    // ---- cases ----

    /** Schedule the same target twice; vanilla keeps the first entry. */
    private static void checkDuplicate(int first, int second, List<String> failures) {
        String name = "duplicate delay " + first + " then " + second;
        KestrelPos pos = new KestrelPos(10, 64, 10);
        BlockPos vpos = pos.toVanilla();

        LevelTicks<Block> vt = vanillaTicks();
        long[] sub = {0};
        vt.schedule(new ScheduledTick<>(Blocks.REDSTONE_TORCH, vpos, first, TickPriority.NORMAL, sub[0]++));
        vt.schedule(new ScheduledTick<>(Blocks.REDSTONE_TORCH, vpos, second, TickPriority.NORMAL, sub[0]++));
        List<Long> vanillaFired = new ArrayList<>();
        for (long t = 0; t <= 12; t++) {
            final long now = t;
            vt.tick(now, 65536, (p, b) -> vanillaFired.add(now));
        }

        TickLoop loop = torchLoop(pos);
        List<Long> kestrelFired = new ArrayList<>();
        loop.hooks().onBlockTick((l, e) -> kestrelFired.add(l.gameTime()));
        loop.scheduleTick(pos, torch(KestrelTickTarget.Priority.NORMAL), first);
        loop.scheduleTick(pos, torch(KestrelTickTarget.Priority.NORMAL), second);
        int queued = loop.dumpQueue().size();
        for (int t = 0; t <= 12; t++) {
            loop.tick();
        }

        System.out.println(name + ": vanilla fired " + vanillaFired + ", Kestrel fired " + kestrelFired
                + ", Kestrel queue size after both " + queued);
        expect(vanillaFired.equals(List.of((long) first)),
                name + ": vanilla reference should fire at " + first + ", got " + vanillaFired, failures);
        expect(kestrelFired.equals(vanillaFired),
                name + ": Kestrel fired " + kestrelFired + ", vanilla " + vanillaFired, failures);
        expect(queued == 1, name + ": Kestrel queue should hold 1 entry, got " + queued, failures);
    }

    /** A duplicate with a higher priority must not replace the pending entry either. */
    private static void checkDuplicatePriority(List<String> failures) {
        KestrelPos pos = new KestrelPos(10, 64, 10);
        TickLoop loop = torchLoop(pos);
        loop.scheduleTick(pos, torch(KestrelTickTarget.Priority.LOW), 5);
        loop.scheduleTick(pos, torch(KestrelTickTarget.Priority.EXTREMELY_HIGH), 5);
        List<TickLoop.QueuedTick> q = loop.dumpQueue();
        System.out.println("duplicate with higher priority: Kestrel queue " + q);
        expect(q.size() == 1 && q.get(0).priority() == KestrelTickTarget.Priority.LOW,
                "duplicate priority: first entry (LOW) must survive, got " + q, failures);
    }

    /**
     * A running tick reschedules itself with delay 0: vanilla runs it again
     * on the NEXT tick, because the collect step already finished.
     */
    private static void checkCollectThenRun(List<String> failures) {
        KestrelPos pos = new KestrelPos(10, 64, 10);
        BlockPos vpos = pos.toVanilla();

        LevelTicks<Block> vt = vanillaTicks();
        long[] sub = {0};
        vt.schedule(new ScheduledTick<>(Blocks.REDSTONE_TORCH, vpos, 0, TickPriority.NORMAL, sub[0]++));
        List<Long> vanillaFired = new ArrayList<>();
        for (long t = 0; t <= 3; t++) {
            final long now = t;
            vt.tick(now, 65536, (p, b) -> {
                vanillaFired.add(now);
                if (vanillaFired.size() == 1) {
                    vt.schedule(new ScheduledTick<>(Blocks.REDSTONE_TORCH, vpos, now, TickPriority.NORMAL, sub[0]++));
                }
            });
        }

        TickLoop loop = torchLoop(pos);
        List<Long> kestrelFired = new ArrayList<>();
        loop.hooks().onBlockTick((l, e) -> {
            kestrelFired.add(l.gameTime() - 1); // drain time k maps to reference now k-1
            if (kestrelFired.size() == 1) {
                l.scheduleTick(pos, torch(KestrelTickTarget.Priority.NORMAL), 0);
            }
        });
        // Trigger = the first drain's gameTime (1), the reference's now = 0.
        loop.scheduleTick(pos, torch(KestrelTickTarget.Priority.NORMAL), 1);
        for (int t = 0; t <= 3; t++) {
            loop.tick();
        }

        System.out.println("delay-0 reschedule from a running tick: vanilla fired " + vanillaFired
                + ", Kestrel fired " + kestrelFired);
        expect(vanillaFired.equals(List.of(0L, 1L)),
                "collect-then-run: vanilla reference should fire [0, 1], got " + vanillaFired, failures);
        expect(kestrelFired.equals(vanillaFired),
                "collect-then-run: Kestrel fired " + kestrelFired + ", vanilla " + vanillaFired, failures);
    }

    /**
     * While A runs, B (collected, not yet run) will tick this tick; A itself
     * (running) and a C due next tick will not.
     */
    private static void checkWillTickThisTick(List<String> failures) {
        KestrelPos a = new KestrelPos(1, 64, 1);
        KestrelPos b = new KestrelPos(2, 64, 1);
        KestrelPos c = new KestrelPos(3, 64, 1);

        LevelTicks<Block> vt = vanillaTicks();
        vt.schedule(new ScheduledTick<>(Blocks.REDSTONE_TORCH, a.toVanilla(), 0, TickPriority.HIGH, 0));
        vt.schedule(new ScheduledTick<>(Blocks.REDSTONE_TORCH, b.toVanilla(), 0, TickPriority.NORMAL, 1));
        vt.schedule(new ScheduledTick<>(Blocks.REDSTONE_TORCH, c.toVanilla(), 1, TickPriority.NORMAL, 2));
        List<String> vanillaSeen = new ArrayList<>();
        vt.tick(0, 65536, (p, blk) -> {
            if (p.equals(a.toVanilla())) {
                vanillaSeen.add("A:" + vt.willTickThisTick(a.toVanilla(), Blocks.REDSTONE_TORCH)
                        + ",B:" + vt.willTickThisTick(b.toVanilla(), Blocks.REDSTONE_TORCH)
                        + ",C:" + vt.willTickThisTick(c.toVanilla(), Blocks.REDSTONE_TORCH)
                        + ",Cpending:" + vt.hasScheduledTick(c.toVanilla(), Blocks.REDSTONE_TORCH));
            }
        });

        TickLoop loop = torchLoop(a, b, c);
        List<String> kestrelSeen = new ArrayList<>();
        loop.hooks().onBlockTick((l, e) -> {
            if (e.pos().equals(a)) {
                kestrelSeen.add("A:" + l.willTickThisTick(a.toVanilla(), Blocks.REDSTONE_TORCH)
                        + ",B:" + l.willTickThisTick(b.toVanilla(), Blocks.REDSTONE_TORCH)
                        + ",C:" + l.willTickThisTick(c.toVanilla(), Blocks.REDSTONE_TORCH)
                        + ",Cpending:" + l.hasScheduledTick(c.toVanilla(), Blocks.REDSTONE_TORCH));
            }
        });
        // Shifted by one (see the class note): A and B due at the first
        // drain (gameTime 1), C one drain later.
        loop.scheduleTick(a, torch(KestrelTickTarget.Priority.HIGH), 1);
        loop.scheduleTick(b, torch(KestrelTickTarget.Priority.NORMAL), 1);
        loop.scheduleTick(c, torch(KestrelTickTarget.Priority.NORMAL), 2);
        loop.tick();

        System.out.println("willTickThisTick while A runs: vanilla " + vanillaSeen + ", Kestrel " + kestrelSeen);
        expect(vanillaSeen.equals(List.of("A:false,B:true,C:false,Cpending:true")),
                "willTickThisTick: unexpected vanilla reference " + vanillaSeen, failures);
        expect(kestrelSeen.equals(vanillaSeen),
                "willTickThisTick: Kestrel " + kestrelSeen + ", vanilla " + vanillaSeen, failures);
    }

    /** All seven priorities map one to one, and old sidecar ordinals 0..3 keep their meaning. */
    private static void checkPriorityMapping(List<String> failures) {
        for (TickPriority vp : TickPriority.values()) {
            KestrelTickTarget.Priority fp = KestrelTickTarget.priorityOf(vp);
            expect(KestrelTickTarget.vanillaPriority(fp) == vp,
                    "priority mapping: " + vp + " -> " + fp + " -> " + KestrelTickTarget.vanillaPriority(fp),
                    failures);
            expect(fp.value() == vp.getValue(),
                    "priority value: " + fp + "=" + fp.value() + " vs " + vp + "=" + vp.getValue(), failures);
            expect(KestrelTickTarget.Priority.byValue(vp.getValue()) == fp,
                    "priority byValue(" + vp.getValue() + ") != " + fp, failures);
        }
        expect(KestrelTickTarget.Priority.byValue(-9) == KestrelTickTarget.Priority.EXTREMELY_HIGH
                        && KestrelTickTarget.Priority.byValue(9) == KestrelTickTarget.Priority.EXTREMELY_LOW,
                "priority byValue must clamp like TickPriority.byValue", failures);
        KestrelTickTarget.Priority[] v = KestrelTickTarget.Priority.values();
        expect(v[0] == KestrelTickTarget.Priority.VERY_HIGH && v[1] == KestrelTickTarget.Priority.HIGH
                        && v[2] == KestrelTickTarget.Priority.NORMAL && v[3] == KestrelTickTarget.Priority.LOW,
                "sidecar ordinals 0..3 must stay VERY_HIGH, HIGH, NORMAL, LOW", failures);
        System.out.println("priority mapping: 7/7 lossless, sidecar ordinals 0..3 unchanged");
    }

    /**
     * Seven equal-time ticks, one per priority, scheduled in reverse: the
     * drain order must follow priority value (vanilla LevelTicks), and a
     * dump/clear/restore round trip must keep it.
     */
    private static void checkEqualTimePriorityOrderAndRestore(List<String> failures) {
        TickPriority[] vps = TickPriority.values();
        KestrelPos[] pos = new KestrelPos[vps.length];
        for (int i = 0; i < vps.length; i++) {
            pos[i] = new KestrelPos(1 + i, 64, 5);
        }

        LevelTicks<Block> vt = vanillaTicks();
        long sub = 0;
        for (int i = vps.length - 1; i >= 0; i--) {
            vt.schedule(new ScheduledTick<>(Blocks.REDSTONE_TORCH, pos[i].toVanilla(), 3, vps[i], sub++));
        }
        List<Integer> vanillaOrder = new ArrayList<>();
        for (long t = 0; t <= 4; t++) {
            vt.tick(t, 65536, (p, b) -> vanillaOrder.add(p.getX()));
        }

        List<List<Integer>> kestrelOrders = new ArrayList<>();
        for (boolean restore : new boolean[] {false, true}) {
            TickLoop loop = torchLoop(pos);
            List<Integer> order = new ArrayList<>();
            loop.hooks().onBlockTick((l, e) -> order.add(e.pos().x()));
            for (int i = vps.length - 1; i >= 0; i--) {
                loop.scheduleTick(pos[i], torch(KestrelTickTarget.priorityOf(vps[i])), 3);
            }
            if (restore) {
                List<TickLoop.QueuedTick> dump = loop.dumpQueue();
                loop.clearQueue();
                for (TickLoop.QueuedTick q : dump) {
                    loop.restoreQueueEntry(q.pos(), q.kind(),
                            KestrelTickTarget.Priority.byValue(q.priority().value()), q.triggerTick());
                }
            }
            for (int t = 0; t <= 4; t++) {
                loop.tick();
            }
            kestrelOrders.add(order);
        }

        System.out.println("equal-time priority order (x = 1..7 is EXTREMELY_HIGH..EXTREMELY_LOW): vanilla "
                + vanillaOrder + ", Kestrel " + kestrelOrders.get(0) + ", Kestrel after restore "
                + kestrelOrders.get(1));
        expect(vanillaOrder.equals(List.of(1, 2, 3, 4, 5, 6, 7)),
                "priority order: unexpected vanilla reference " + vanillaOrder, failures);
        expect(kestrelOrders.get(0).equals(vanillaOrder),
                "priority order: Kestrel " + kestrelOrders.get(0) + ", vanilla " + vanillaOrder, failures);
        expect(kestrelOrders.get(1).equals(vanillaOrder),
                "priority order after restore: Kestrel " + kestrelOrders.get(1) + ", vanilla " + vanillaOrder,
                failures);
    }
}
