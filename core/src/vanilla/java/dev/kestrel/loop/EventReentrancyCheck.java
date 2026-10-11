package dev.kestrel.loop;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression gate for High Finding H9 (docs/ARCHITECTURE-AUDIT.md):
 * 'Reentrant plugin events overwrite outer event state'.
 *
 * <p>The defect was a single mutable event object per hook point in
 * {@link EventHooks}. A handler whose own work re-entered the same point (a
 * compensating {@code TickLoop.setBlock}, which EVENTS.md allows) reset the
 * shared object, so later handlers of the outer event saw the inner position,
 * states and flags, and the outer cancel was lost (the outer fire returned
 * false, the outer write reported accepted despite the veto).
 *
 * <p>The fix pools one event frame per nesting depth. This gate asserts, for
 * both points and for a direct fire as well as a nested
 * {@code TickLoop.setBlock}, that every field of the outer event and its
 * cancel result survive the inner fire, and that the inner event is
 * independent (not cancelled by the outer veto). It also checks that a
 * second, non-nested fire reuses the frames cleanly (no cancel leaking from
 * the previous fire).
 *
 * <p>PASS means event integrity holds. {@code --demonstrate-defect} is kept as
 * an alias that now fails, so an old invocation cannot silently read as a
 * reproduction.
 */
public final class EventReentrancyCheck {
    private EventReentrancyCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        System.out.println("=== EventReentrancyCheck: H9 regression gate ===");
        if (args.length > 0 && args[0].equals("--demonstrate-defect")) {
            failures.add("--demonstrate-defect: H9 is fixed; this gate only asserts event integrity now");
        }

        checkDirectBlockChange(failures);
        checkTickLoopSetBlock(failures);
        checkDirectBlockTick(failures);
        checkThreeLevels(failures);

        boolean passed = failures.isEmpty();
        System.out.println("EventReentrancyCheck: " + (passed ? "PASS" : "FAIL"));
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

    /** One observation of a BlockChange event by the recording handler. */
    private record Seen(KestrelPos pos, BlockState oldState, BlockState newState, int flags, boolean cancelled) {
        static Seen of(EventHooks.BlockChangeEvent e) {
            return new Seen(e.pos(), e.oldState().unwrap(), e.newState().unwrap(), e.flags(), e.cancelled());
        }
    }

    /** Test 1: direct fireBlockChange re-entered from the first handler. */
    private static void checkDirectBlockChange(List<String> failures) {
        System.out.println("Test 1: direct BlockChange reentrancy");
        EventHooks hooks = new EventHooks();
        KestrelPos outerPos = new KestrelPos(10, 64, 10);
        KestrelPos innerPos = new KestrelPos(20, 64, 20);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();

        boolean[] innerResult = {false};
        hooks.onBlockChange((loop, event) -> {
            if (event.pos().equals(outerPos)) {
                event.cancel();
                innerResult[0] = hooks.fireBlockChange(loop, innerPos, stone, dirt, 1);
            }
        });
        List<Seen> seen = new ArrayList<>();
        hooks.onBlockChange((loop, event) -> seen.add(Seen.of(event)));

        boolean outerResult = hooks.fireBlockChange(null, outerPos, air, stone, 3);
        System.out.println("  seen=" + seen + " outerResult=" + outerResult + " innerResult=" + innerResult[0]);

        if (seen.size() != 2) {
            failures.add("direct: recording handler expected 2 calls (inner, outer), got " + seen.size());
            return;
        }
        Seen inner = seen.get(0);
        Seen outer = seen.get(1);
        expect(inner.equals(new Seen(innerPos, stone, dirt, 1, false)),
                "direct: inner event wrong: " + inner, failures);
        expect(outer.equals(new Seen(outerPos, air, stone, 3, true)),
                "direct: outer event corrupted by inner fire: " + outer, failures);
        expect(outerResult, "direct: outer fire lost its cancel (returned false)", failures);
        expect(!innerResult[0], "direct: inner fire reported cancelled though nobody cancelled it", failures);

        // A later, non-nested fire must start clean on the reused frame.
        seen.clear();
        boolean later = hooks.fireBlockChange(null, innerPos, dirt, air, 2);
        expect(!later, "direct: later fire inherited a stale cancel", failures);
        expect(seen.size() == 1 && !seen.get(0).cancelled() && seen.get(0).flags() == 2,
                "direct: later fire saw stale state: " + seen, failures);
    }

    /** Test 2: a compensating TickLoop.setBlock from inside a BlockChange handler. */
    private static void checkTickLoopSetBlock(List<String> failures) {
        System.out.println("Test 2: TickLoop.setBlock reentrancy");
        TickLoop loop = new TickLoop(new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
        KestrelPos outerPos = new KestrelPos(0, 64, 0);
        KestrelPos innerPos = new KestrelPos(1, 64, 1);
        KestrelState air = KestrelState.wrap(Blocks.AIR.defaultBlockState());
        KestrelState stone = KestrelState.wrap(Blocks.STONE.defaultBlockState());
        KestrelState dirt = KestrelState.wrap(Blocks.DIRT.defaultBlockState());

        boolean[] innerAccepted = {false};
        loop.hooks().onBlockChange((l, event) -> {
            if (event.pos().equals(outerPos)) {
                event.cancel();
                innerAccepted[0] = l.setBlock(innerPos, dirt, 1);
            }
        });
        List<Seen> seen = new ArrayList<>();
        loop.hooks().onBlockChange((l, event) -> seen.add(Seen.of(event)));

        boolean accepted = loop.setBlock(outerPos, stone, 3);
        System.out.println("  seen=" + seen + " outerAccepted=" + accepted + " innerAccepted=" + innerAccepted[0]);

        if (seen.size() != 2) {
            failures.add("loop: recording handler expected 2 calls (inner, outer), got " + seen.size());
            return;
        }
        Seen inner = seen.get(0);
        Seen outer = seen.get(1);
        expect(inner.pos().equals(innerPos) && inner.newState() == dirt.unwrap() && inner.flags() == 1 && !inner.cancelled(),
                "loop: inner event wrong: " + inner, failures);
        expect(outer.pos().equals(outerPos) && outer.oldState() == air.unwrap() && outer.newState() == stone.unwrap()
                        && outer.flags() == 3 && outer.cancelled(),
                "loop: outer event corrupted by nested setBlock: " + outer, failures);
        expect(!accepted, "loop: outer setBlock reported accepted despite the veto", failures);
        expect(innerAccepted[0], "loop: nested setBlock was not accepted though nobody vetoed it", failures);
        // Cancel is a post-commit veto (EVENTS.md): both writes stand in the store.
        expect(loop.getBlockState(outerPos).unwrap() == stone.unwrap(), "loop: outer write missing from the store", failures);
        expect(loop.getBlockState(innerPos).unwrap() == dirt.unwrap(), "loop: nested write missing from the store", failures);
    }

    /** Test 3: direct fireBlockTick re-entered from the first handler. */
    private static void checkDirectBlockTick(List<String> failures) {
        System.out.println("Test 3: direct BlockTick reentrancy");
        EventHooks hooks = new EventHooks();
        KestrelPos outerPos = new KestrelPos(5, 70, 5);
        KestrelPos innerPos = new KestrelPos(6, 70, 6);
        KestrelTickTarget outerTarget = new KestrelTickTarget(KestrelTickTarget.Kind.REPEATER,
                KestrelTickTarget.Priority.EXTREMELY_HIGH);
        KestrelTickTarget innerTarget = new KestrelTickTarget(KestrelTickTarget.Kind.REDSTONE_TORCH,
                KestrelTickTarget.Priority.NORMAL);

        boolean[] innerResult = {false};
        hooks.onBlockTick((loop, event) -> {
            if (event.pos().equals(outerPos)) {
                event.cancel();
                innerResult[0] = hooks.fireBlockTick(loop, innerPos, innerTarget);
            }
        });
        List<String> seen = new ArrayList<>();
        hooks.onBlockTick((loop, event) -> seen.add(event.pos() + "/" + event.target() + "/" + event.cancelled()));

        boolean outerResult = hooks.fireBlockTick(null, outerPos, outerTarget);
        System.out.println("  seen=" + seen + " outerResult=" + outerResult + " innerResult=" + innerResult[0]);

        List<String> want = List.of(innerPos + "/" + innerTarget + "/false", outerPos + "/" + outerTarget + "/true");
        expect(seen.equals(want), "tick: expected " + want + ", got " + seen, failures);
        expect(outerResult, "tick: outer fire lost its cancel (returned false)", failures);
        expect(!innerResult[0], "tick: inner fire reported cancelled though nobody cancelled it", failures);
    }

    /** Test 4: three nesting levels, each frame independent, cancel only on the middle one. */
    private static void checkThreeLevels(List<String> failures) {
        System.out.println("Test 4: three-level BlockChange nesting");
        EventHooks hooks = new EventHooks();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        KestrelPos p0 = new KestrelPos(0, 1, 0);
        KestrelPos p1 = new KestrelPos(1, 1, 0);
        KestrelPos p2 = new KestrelPos(2, 1, 0);

        boolean[] results = new boolean[3];
        hooks.onBlockChange((loop, event) -> {
            if (event.pos().equals(p0)) {
                results[1] = hooks.fireBlockChange(loop, p1, air, stone, 11);
            } else if (event.pos().equals(p1)) {
                event.cancel();
                results[2] = hooks.fireBlockChange(loop, p2, stone, air, 12);
            }
        });
        List<Seen> seen = new ArrayList<>();
        hooks.onBlockChange((loop, event) -> seen.add(Seen.of(event)));

        results[0] = hooks.fireBlockChange(null, p0, air, stone, 10);
        System.out.println("  seen=" + seen + " results=[" + results[0] + "," + results[1] + "," + results[2] + "]");

        List<String> got = new ArrayList<>();
        for (Seen s : seen) {
            got.add(s.pos() + ":" + s.flags() + ":" + s.cancelled());
        }
        List<String> want = List.of(p2 + ":12:false", p1 + ":11:true", p0 + ":10:false");
        expect(got.equals(want), "nest3: expected " + want + ", got " + got, failures);
        expect(!results[0] && results[1] && !results[2],
                "nest3: fire results expected [false,true,false], got [" + results[0] + "," + results[1] + "," + results[2] + "]",
                failures);
    }
}
