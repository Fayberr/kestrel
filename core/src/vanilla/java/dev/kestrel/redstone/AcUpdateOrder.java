package dev.kestrel.redstone;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Neighbor traversal orders for the Alternate Current port.
 *
 * <p>Transcribed from {@code alternate.current.wire.UpdateOrder}
 * (SpaceWalkerRS, MIT): four orders (horizontal-first / vertical-first,
 * each outward / inward). The {@code forEachNeighbor} bodies in AC walk live
 * world nodes; this port exposes the same visitation as pure position lists
 * so the order is provable headlessly without a world.
 *
 * <p>Positions are encoded as int[3] offsets from the source. The visitation
 * rule: neighbors identified by steps from the source, iterated in
 * opposite-side pairs, grouped by distance (direct 6, diagonal 12, far 6),
 * ordered within each group by a basic relative order ({front, back, right,
 * left, down, up} for horizontal-first; down/up moved to front for
 * vertical-first). See AC {@code UpdateOrder} javadoc per constant.
 */
public enum AcUpdateOrder {
    HORIZONTAL_FIRST_OUTWARD(true, false),
    HORIZONTAL_FIRST_INWARD(true, true),
    VERTICAL_FIRST_OUTWARD(false, false),
    VERTICAL_FIRST_INWARD(false, true);

    private final boolean horizontalFirst;
    private final boolean inward;

    AcUpdateOrder(boolean horizontalFirst, boolean inward) {
        this.horizontalFirst = horizontalFirst;
        this.inward = inward;
    }

    /**
     * Visit neighbor offsets in this order's sequence. {@code step} maps a
     * (from-offset, direction-index) pair to the resulting offset, so callers
     * supply world geometry (or a test lattice) without this class touching
     * any level.
     */
    public List<int[]> neighborOffsets(int forward, BiFunction<int[], Integer, int[]> step) {
        int rightward = (forward + 1) & 0b11;
        int backward = (forward + 2) & 0b11;
        int leftward = (forward + 3) & 0b11;
        int downward = AcDirections.DOWN;
        int upward = AcDirections.UP;

        int[] origin = new int[] {0, 0, 0};
        int[] front = step.apply(origin, forward);
        int[] right = step.apply(origin, rightward);
        int[] back = step.apply(origin, backward);
        int[] left = step.apply(origin, leftward);
        int[] below = step.apply(origin, downward);
        int[] above = step.apply(origin, upward);

        List<int[]> direct = new ArrayList<>(6);
        List<int[]> diagonal = new ArrayList<>(12);
        List<int[]> far = new ArrayList<>(6);

        if (horizontalFirst) {
            direct.add(front);
            direct.add(back);
            direct.add(right);
            direct.add(left);
            direct.add(below);
            direct.add(above);

            diagonal.add(step.apply(front, rightward));
            diagonal.add(step.apply(back, leftward));
            diagonal.add(step.apply(front, leftward));
            diagonal.add(step.apply(back, rightward));
            diagonal.add(step.apply(front, downward));
            diagonal.add(step.apply(back, upward));
            diagonal.add(step.apply(front, upward));
            diagonal.add(step.apply(back, downward));
            diagonal.add(step.apply(right, downward));
            diagonal.add(step.apply(left, upward));
            diagonal.add(step.apply(right, upward));
            diagonal.add(step.apply(left, downward));
        } else {
            direct.add(below);
            direct.add(above);
            direct.add(front);
            direct.add(back);
            direct.add(right);
            direct.add(left);

            diagonal.add(step.apply(below, forward));
            diagonal.add(step.apply(above, backward));
            diagonal.add(step.apply(below, backward));
            diagonal.add(step.apply(above, forward));
            diagonal.add(step.apply(below, rightward));
            diagonal.add(step.apply(above, leftward));
            diagonal.add(step.apply(below, leftward));
            diagonal.add(step.apply(above, rightward));
            diagonal.add(step.apply(front, rightward));
            diagonal.add(step.apply(back, leftward));
            diagonal.add(step.apply(front, leftward));
            diagonal.add(step.apply(back, rightward));
        }

        far.add(step.apply(horizontalFirst ? front : below, horizontalFirst ? forward : downward));
        far.add(step.apply(horizontalFirst ? back : above, horizontalFirst ? backward : upward));
        far.add(step.apply(horizontalFirst ? right : front, horizontalFirst ? rightward : forward));
        far.add(step.apply(horizontalFirst ? left : back, horizontalFirst ? leftward : backward));
        far.add(step.apply(horizontalFirst ? below : right, horizontalFirst ? downward : rightward));
        far.add(step.apply(horizontalFirst ? above : left, horizontalFirst ? upward : leftward));

        List<int[]> out = new ArrayList<>(24);
        if (inward) {
            out.addAll(far);
            out.addAll(diagonal);
            out.addAll(direct);
        } else {
            out.addAll(direct);
            out.addAll(diagonal);
            out.addAll(far);
        }
        return out;
    }

    /** Cardinal-only neighbor directions for a flow-forward, in visit order. */
    public int[] cardinalNeighbors(int forward) {
        if (horizontalFirst) {
            switch (forward) {
                case AcDirections.WEST:
                    return new int[] {AcDirections.WEST, AcDirections.EAST, AcDirections.NORTH, AcDirections.SOUTH};
                case AcDirections.NORTH:
                    return new int[] {AcDirections.NORTH, AcDirections.SOUTH, AcDirections.EAST, AcDirections.WEST};
                case AcDirections.EAST:
                    return new int[] {AcDirections.EAST, AcDirections.WEST, AcDirections.SOUTH, AcDirections.NORTH};
                default:
                    return new int[] {AcDirections.SOUTH, AcDirections.NORTH, AcDirections.WEST, AcDirections.EAST};
            }
        }
        return new int[] {AcDirections.WEST, AcDirections.EAST, AcDirections.NORTH, AcDirections.SOUTH};
    }

    /** All six direct-neighbor directions for a flow-forward, in visit order. */
    public int[] directNeighbors(int forward) {
        int[] card = cardinalNeighbors(forward);
        if (horizontalFirst) {
            return new int[] {card[0], card[1], card[2], card[3], AcDirections.DOWN, AcDirections.UP};
        }
        return new int[] {AcDirections.DOWN, AcDirections.UP, card[0], card[1], card[2], card[3]};
    }
}
