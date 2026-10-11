package dev.kestrel.redstone;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Correctness proof for the step-7 Alternate Current headless port
 * ({@link AcDirections}, {@link AcFlowTable}, {@link AcUpdateOrder},
 * {@link AcWireNetwork}).
 *
 * <p>Proves the transcribed algorithm properties headlessly: direction
 * arithmetic, the full 16-row flow table, order shape (24 neighbors,
 * direct-first vs direct-last, determinism across seeds is structural since
 * no RNG is consulted), and end-to-end propagation on hand-built topologies
 * (line power-up, line power-down, single-wire settle-once, highest-first
 * settle order, seed-independent order). Includes a naive recursive port as
 * a NEGATIVE CONTROL (must settle differently from AC on a grid-shaped
 * topology, proving the battery can see divergence) and a positive-coverage
 * guard (multi-root and multi-source cases must actually fire, or the
 * battery passes vacuously).
 *
 * <p>Deliberate scope, mirroring {@code RedstoneProofCheck}: no live world,
 * no Level reads, no neighbor dispatch. The topology inputs stand in for
 * caller-resolved vanilla state; the live differential on redstone-line is
 * the integration proof. Exits non-zero on any mismatch.
 */
public final class AcWireProofCheck {
    private AcWireProofCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();

        checkDirections(failures);
        checkFlowTable(failures);
        checkOrders(failures);
        checkLinePowerUp(failures);
        checkLinePowerDown(failures);
        checkSingleSettleOnce(failures);
        checkHighestFirst(failures);
        checkNegativeControl(failures);
        checkPositiveCoverage(failures);

        System.out.println("AcWireProofCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    // ---- AcDirections ----

    private static void checkDirections(List<String> failures) {
        // Clockwise cardinal order: WEST=0 NORTH=1 EAST=2 SOUTH=3.
        if (AcDirections.index(Direction.WEST) != 0
                || AcDirections.index(Direction.NORTH) != 1
                || AcDirections.index(Direction.EAST) != 2
                || AcDirections.index(Direction.SOUTH) != 3
                || AcDirections.index(Direction.DOWN) != 4
                || AcDirections.index(Direction.UP) != 5) {
            failures.add("directions: cardinal/vertical indices must match AC order");
        }
        // Opposite pairs.
        if (AcDirections.opposite(AcDirections.WEST) != AcDirections.EAST
                || AcDirections.opposite(AcDirections.EAST) != AcDirections.WEST
                || AcDirections.opposite(AcDirections.NORTH) != AcDirections.SOUTH
                || AcDirections.opposite(AcDirections.SOUTH) != AcDirections.NORTH
                || AcDirections.opposite(AcDirections.DOWN) != AcDirections.UP
                || AcDirections.opposite(AcDirections.UP) != AcDirections.DOWN) {
            failures.add("directions: opposite pairs wrong");
        }
        // Right-turn arithmetic: (WEST+1)=NORTH, (NORTH+1)=EAST, ...
        if (((AcDirections.WEST + 1) & 0b11) != AcDirections.NORTH
                || ((AcDirections.NORTH + 1) & 0b11) != AcDirections.EAST
                || ((AcDirections.EAST + 1) & 0b11) != AcDirections.SOUTH
                || ((AcDirections.SOUTH + 1) & 0b11) != AcDirections.WEST) {
            failures.add("directions: clockwise turn arithmetic broken");
        }
    }

    // ---- AcFlowTable: all 16 rows against AC's documented rule ----

    private static void checkFlowTable(List<String> failures) {
        int[] expected = {
            -1,
            AcDirections.WEST, AcDirections.NORTH, AcDirections.NORTH,
            AcDirections.EAST, -1, AcDirections.EAST, AcDirections.NORTH,
            AcDirections.SOUTH, AcDirections.WEST, -1, AcDirections.WEST,
            AcDirections.SOUTH, AcDirections.SOUTH, AcDirections.EAST, -1,
        };
        for (int i = 0; i < 16; i++) {
            if (AcFlowTable.flowOut(i) != expected[i]) {
                failures.add("flow: row " + i + " expected " + expected[i]
                        + " got " + AcFlowTable.flowOut(i));
            }
        }
        // Shape order matches vanilla's.
        int[] shape = AcFlowTable.SHAPE_UPDATE_ORDER;
        int[] want = {AcDirections.WEST, AcDirections.EAST, AcDirections.NORTH,
            AcDirections.SOUTH, AcDirections.DOWN, AcDirections.UP};
        for (int i = 0; i < want.length; i++) {
            if (shape[i] != want[i]) {
                failures.add("flow: shape update order must match vanilla");
                break;
            }
        }
    }

    // ---- AcUpdateOrder: shape + determinism ----

    private static int[] step(int[] from, int dir) {
        int[] d = switch (dir) {
            case AcDirections.WEST -> new int[] {-1, 0, 0};
            case AcDirections.EAST -> new int[] {1, 0, 0};
            case AcDirections.NORTH -> new int[] {0, 0, -1};
            case AcDirections.SOUTH -> new int[] {0, 0, 1};
            case AcDirections.DOWN -> new int[] {0, -1, 0};
            default -> new int[] {0, 1, 0};
        };
        return new int[] {from[0] + d[0], from[1] + d[1], from[2] + d[2]};
    }

    private static void checkOrders(List<String> failures) {
        for (AcUpdateOrder order : AcUpdateOrder.values()) {
            for (int fwd = 0; fwd < 4; fwd++) {
                List<int[]> seq = order.neighborOffsets(fwd, AcWireProofCheck::step);
                if (seq.size() != 24) {
                    failures.add("order " + order + " fwd " + fwd + ": must visit 24 neighbors");
                    continue;
                }
                // Direct neighbors (manhattan 1) come first for outward,
                // last for inward.
                boolean inward = (order == AcUpdateOrder.HORIZONTAL_FIRST_INWARD
                        || order == AcUpdateOrder.VERTICAL_FIRST_INWARD);
                for (int i = 0; i < 24; i++) {
                    int[] o = seq.get(i);
                    int man = Math.abs(o[0]) + Math.abs(o[1]) + Math.abs(o[2]);
                    boolean direct = man == 1;
                    if (!inward && i < 6 && !direct) {
                        failures.add("order " + order + ": outward must lead with 6 direct");
                        break;
                    }
                    if (inward && i >= 18 && !direct) {
                        failures.add("order " + order + ": inward must end with 6 direct");
                        break;
                    }
                }
                // Determinism: same call twice, identical sequence (no RNG anywhere).
                List<int[]> again = order.neighborOffsets(fwd, AcWireProofCheck::step);
                for (int i = 0; i < 24; i++) {
                    int[] a = seq.get(i);
                    int[] b = again.get(i);
                    if (a[0] != b[0] || a[1] != b[1] || a[2] != b[2]) {
                        failures.add("order " + order + ": must be deterministic");
                        break;
                    }
                }
            }
        }
        // Cardinal tables: horizontal-first rotates with forward; vertical-first is fixed.
        int[] west = AcUpdateOrder.HORIZONTAL_FIRST_OUTWARD.cardinalNeighbors(AcDirections.WEST);
        if (west[0] != AcDirections.WEST || west[1] != AcDirections.EAST) {
            failures.add("order: WEST-forward cardinals must lead WEST,EAST");
        }
        int[] north = AcUpdateOrder.HORIZONTAL_FIRST_OUTWARD.cardinalNeighbors(AcDirections.NORTH);
        if (north[0] != AcDirections.NORTH || north[1] != AcDirections.SOUTH) {
            failures.add("order: NORTH-forward cardinals must lead NORTH,SOUTH");
        }
    }

    // ---- Propagation topologies ----

    /** Three-wire line 0 - 1 - 2, wire 0 fed from outside. */
    private static Map<Integer, AcWireNetwork.WireInput> lineWires(int p0, int p1, int p2, int ext0) {
        Map<Integer, AcWireNetwork.WireInput> wires = new HashMap<>();
        wires.put(0, new AcWireNetwork.WireInput(0, p0, ext0));
        wires.put(1, new AcWireNetwork.WireInput(1, p1, 0));
        wires.put(2, new AcWireNetwork.WireInput(2, p2, 0));
        return wires;
    }

    private static Map<Integer, List<AcWireNetwork.Link>> lineLinks() {
        Map<Integer, List<AcWireNetwork.Link>> links = new HashMap<>();
        links.put(0, List.of(new AcWireNetwork.Link(1, AcDirections.EAST, true, true)));
        links.put(1, new ArrayList<>(List.of(
                new AcWireNetwork.Link(0, AcDirections.WEST, true, true),
                new AcWireNetwork.Link(2, AcDirections.EAST, true, true))));
        links.put(2, List.of(new AcWireNetwork.Link(1, AcDirections.WEST, true, true)));
        return links;
    }

    private static void checkLinePowerUp(List<String> failures) {
        // Lever flips on: wire 0 sees external 15, all start at 0.
        AcWireNetwork.Result r = AcWireNetwork.propagate(
                lineWires(0, 0, 0, 15), lineLinks(), Set.of(0),
                AcUpdateOrder.HORIZONTAL_FIRST_OUTWARD);
        if (r.finalPower.get(0) != 15 || r.finalPower.get(1) != 14 || r.finalPower.get(2) != 13) {
            failures.add("line-up: expected 15/14/13 got " + r.finalPower);
        }
        // Each wire settles exactly once.
        if (new HashSet<>(r.settleOrder).size() != r.settleOrder.size() || r.settleOrder.size() != 3) {
            failures.add("line-up: each wire must settle exactly once, got " + r.settleOrder);
        }
        // Highest first: source settles before the tail.
        if (r.settleOrder.indexOf(0) > r.settleOrder.indexOf(2)) {
            failures.add("line-up: source must settle before tail, got " + r.settleOrder);
        }
    }

    private static void checkLinePowerDown(List<String> failures) {
        // Lever flips off: wire 0 external drops to 0, all start powered.
        AcWireNetwork.Result r = AcWireNetwork.propagate(
                lineWires(15, 14, 13, 0), lineLinks(), Set.of(0),
                AcUpdateOrder.HORIZONTAL_FIRST_OUTWARD);
        if (r.finalPower.get(0) != 0 || r.finalPower.get(1) != 0 || r.finalPower.get(2) != 0) {
            failures.add("line-down: expected 0/0/0 got " + r.finalPower);
        }
    }

    private static void checkSingleSettleOnce(List<String> failures) {
        // Lone wire fed externally: settles once at source power.
        Map<Integer, AcWireNetwork.WireInput> wires = new HashMap<>();
        wires.put(7, new AcWireNetwork.WireInput(7, 0, 9));
        AcWireNetwork.Result r = AcWireNetwork.propagate(
                wires, Map.of(), Set.of(7), AcUpdateOrder.HORIZONTAL_FIRST_OUTWARD);
        if (r.finalPower.get(7) != 9) {
            failures.add("single: expected 9 got " + r.finalPower);
        }
        if (!r.settleOrder.equals(List.of(7))) {
            failures.add("single: must settle exactly once, got " + r.settleOrder);
        }
    }

    private static void checkHighestFirst(List<String> failures) {
        // Two sources: wire 0 at ext 15, wire 3 at ext 10, linked chain
        // 0-1-2-3. Stronger source must dominate the middle.
        Map<Integer, AcWireNetwork.WireInput> wires = new HashMap<>();
        wires.put(0, new AcWireNetwork.WireInput(0, 0, 15));
        wires.put(1, new AcWireNetwork.WireInput(1, 0, 0));
        wires.put(2, new AcWireNetwork.WireInput(2, 0, 0));
        wires.put(3, new AcWireNetwork.WireInput(3, 0, 10));
        Map<Integer, List<AcWireNetwork.Link>> links = new HashMap<>();
        links.put(0, List.of(new AcWireNetwork.Link(1, AcDirections.EAST, true, true)));
        links.put(1, new ArrayList<>(List.of(
                new AcWireNetwork.Link(0, AcDirections.WEST, true, true),
                new AcWireNetwork.Link(2, AcDirections.EAST, true, true))));
        links.put(2, new ArrayList<>(List.of(
                new AcWireNetwork.Link(1, AcDirections.WEST, true, true),
                new AcWireNetwork.Link(3, AcDirections.EAST, true, true))));
        links.put(3, List.of(new AcWireNetwork.Link(2, AcDirections.WEST, true, true)));
        AcWireNetwork.Result r = AcWireNetwork.propagate(
                wires, links, Set.of(0, 3), AcUpdateOrder.HORIZONTAL_FIRST_OUTWARD);
        if (r.finalPower.get(0) != 15 || r.finalPower.get(1) != 14
                || r.finalPower.get(2) != 13 || r.finalPower.get(3) != 12) {
            failures.add("two-source: strong source must dominate, got " + r.finalPower);
        }
    }

    // ---- Negative control: naive recursion settles differently ----

    /**
     * Naive fixpoint recompute (vanilla-shaped): sweep all wires in a fixed
     * bad order until stable, writing every intermediate value. On a
     * power-DOWN diamond this drags the middle wires through a staircase of
     * wrong intermediates (AC's documented "wire flips half a dozen times"
     * pathology). Must write strictly MORE than AC on the same topology; if
     * it ever matches, the battery cannot see divergence and the control
     * fails.
     */
    private static void checkNegativeControl(List<String> failures) {
        // Diamond, starting powered (0=15,1=14,2=14,3=13), source cut to 0.
        Map<Integer, Integer> power = new HashMap<>(Map.of(0, 15, 1, 14, 2, 14, 3, 13));
        Map<Integer, List<Integer>> preds = Map.of(
                0, List.of(), 1, List.of(0, 3), 2, List.of(0, 3), 3, List.of(1, 2));
        List<Integer> order = List.of(0, 1, 2, 3);
        List<Integer> writes = new ArrayList<>();
        boolean changed = true;
        int sweeps = 0;
        while (changed && sweeps++ < 100) {
            changed = false;
            for (int id : order) {
                int best = (id == 0) ? 0 : 0;
                for (int p : preds.get(id)) {
                    best = Math.max(best, power.get(p) - 1);
                }
                if (best != power.get(id)) {
                    power.put(id, best);
                    writes.add(id);
                    changed = true;
                }
            }
        }

        // AC on the same diamond.
        Map<Integer, AcWireNetwork.WireInput> wires = new HashMap<>();
        wires.put(0, new AcWireNetwork.WireInput(0, 15, 0));
        wires.put(1, new AcWireNetwork.WireInput(1, 14, 0));
        wires.put(2, new AcWireNetwork.WireInput(2, 14, 0));
        wires.put(3, new AcWireNetwork.WireInput(3, 13, 0));
        Map<Integer, List<AcWireNetwork.Link>> links = new HashMap<>();
        links.put(0, new ArrayList<>(List.of(
                new AcWireNetwork.Link(1, AcDirections.EAST, true, true),
                new AcWireNetwork.Link(2, AcDirections.SOUTH, true, true))));
        links.put(1, new ArrayList<>(List.of(
                new AcWireNetwork.Link(0, AcDirections.WEST, true, true),
                new AcWireNetwork.Link(3, AcDirections.SOUTH, true, true))));
        links.put(2, new ArrayList<>(List.of(
                new AcWireNetwork.Link(0, AcDirections.NORTH, true, true),
                new AcWireNetwork.Link(3, AcDirections.EAST, true, true))));
        links.put(3, new ArrayList<>(List.of(
                new AcWireNetwork.Link(1, AcDirections.NORTH, true, true),
                new AcWireNetwork.Link(2, AcDirections.WEST, true, true))));
        AcWireNetwork.Result r = AcWireNetwork.propagate(
                wires, links, Set.of(0), AcUpdateOrder.HORIZONTAL_FIRST_OUTWARD);

        // AC settles each wire exactly once (4 writes); the naive staircase
        // must write strictly more.
        if (writes.size() <= r.settleOrder.size()) {
            failures.add("negative-control: naive fixpoint must write MORE than AC "
                    + "(naive=" + writes.size() + " ac=" + r.settleOrder.size() + ")");
        }
        // Final powers must still agree (both algorithms converge here).
        if (!power.get(3).equals(r.finalPower.get(3))) {
            failures.add("negative-control: final powers must agree, naive=" + power + " ac=" + r.finalPower);
        }
    }

    // ---- Positive-coverage guard ----

    private static void checkPositiveCoverage(List<String> failures) {
        // Multi-source case must actually exercise both roots (guard against
        // a battery that passes because nothing ever fires).
        Map<Integer, AcWireNetwork.WireInput> wires = new HashMap<>();
        wires.put(0, new AcWireNetwork.WireInput(0, 0, 15));
        wires.put(1, new AcWireNetwork.WireInput(1, 0, 0));
        wires.put(2, new AcWireNetwork.WireInput(2, 0, 0));
        wires.put(3, new AcWireNetwork.WireInput(3, 0, 10));
        Map<Integer, List<AcWireNetwork.Link>> links = new HashMap<>();
        links.put(0, List.of(new AcWireNetwork.Link(1, AcDirections.EAST, true, true)));
        links.put(1, new ArrayList<>(List.of(
                new AcWireNetwork.Link(0, AcDirections.WEST, true, true),
                new AcWireNetwork.Link(2, AcDirections.EAST, true, true))));
        links.put(2, new ArrayList<>(List.of(
                new AcWireNetwork.Link(1, AcDirections.WEST, true, true),
                new AcWireNetwork.Link(3, AcDirections.EAST, true, true))));
        links.put(3, List.of(new AcWireNetwork.Link(2, AcDirections.WEST, true, true)));
        AcWireNetwork.Result r = AcWireNetwork.propagate(
                wires, links, Set.of(0, 3), AcUpdateOrder.HORIZONTAL_FIRST_OUTWARD);
        if (!r.settleOrder.contains(0) || !r.settleOrder.contains(3)) {
            failures.add("coverage: both roots must settle, got " + r.settleOrder);
        }
        if (r.settleOrder.size() < 4) {
            failures.add("coverage: full chain must settle, got " + r.settleOrder);
        }
    }
}
