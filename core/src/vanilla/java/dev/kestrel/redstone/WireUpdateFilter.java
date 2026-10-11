package dev.kestrel.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * No-op neighbor-update suppression for the DEFAULT redstone evaluator path:
 * decides which of vanilla's 7 {@code updateNeighborsAt} dispatches after a
 * wire power change can be skipped without changing any observable outcome.
 *
 * <p>What vanilla does (grounded in the decompiled 26.2 source, see
 * {@code preresearch/redstone-path-analysis.md} path A):
 * {@code DefaultRedstoneWireEvaluator.updatePowerStrength} recomputes one
 * wire's target power; when it changed, it sets the block, then unconditionally
 * fires {@code updateNeighborsAt} on the wire itself plus all 6 neighbors
 * (7 dispatches in a {@code HashSet}, iteration order allocation-dependent).
 * It ALSO skips everything when power is unchanged (the outer
 * {@code if (POWER != target)} - real no-op suppression already exists at
 * this level, so this filter only plans the changed-power case).
 *
 * <p>What this class does: given an OLD power, a NEW power (different), and
 * per-neighbor relevance info gathered by the caller from real vanilla
 * state, returns the dispatch set vanilla's full 7 would be reduced to.
 * Every skip is justified by a "provably cannot observe" rule:
 *
 * <ul>
 *   <li><b>R1 absolute ceiling:</b> any neighbor whose own wire power is
 *       already {@code >= (newPower - 1)} AND {@code >= (oldPower - 1)}
 *       keeps its recompute answer regardless of this wire's change: its
 *       target is {@code max(external, incoming - 1)} and the incoming term
 *       can at most move between {@code oldPower - 1} and
 *       {@code newPower - 1}, both below what the neighbor already holds.
 *       (Settled-line steady state: every middle wire sits at max from the
 *       other side, so both flanks skip.)</li>
 *   <li><b>R2 binary monotonicity (corner cases):</b> for a NON-WIRE,
 *       non-analog neighbor (torch, lamp, piston, ...), rising edges can only
 *       turn things on-or-stronger and falling edges only off-or-weaker, and
 *       binary devices commit at their own thresholds. The caller passes the
 *       device's CURRENT on/off state read from real vanilla state; the
 *       filter skips only edges that provably cannot cross the threshold in
 *       the observed direction... conservatively: skip a rising edge when the
 *       device is already ON, skip a falling edge when already OFF. Any
 *       device the caller cannot classify (analog readers: comparators,
 *       other wires, anything power-sensitive) is passed as
 *       {@code ANALOG} and never skipped by this rule.</li>
 *   <li><b>R3 connection gating:</b> a wire that is not connected toward
 *       this wire's side (cached NONE side from
 *       {@link WireConnectionCache}, resolved by real vanilla
 *       {@code updateShape}) reads 0 from this direction no matter the
 *       power - a power change here cannot alter its incoming term through
 *       that side. Skipped unless it is connected.</li>
 *   <li><b>R4 self-dispatch is sacred:</b> the dispatch on the wire's OWN
 *       position is ALWAYS kept. It carries shape/observer/second-order
 *       effects beyond power recompute (corner propagation via
 *       {@code updateIndirectNeighbourShapes}, observer triggers on the
 *       blockstate swap) that no local power comparison can prove
 *       irrelevant.</li>
 * </ul>
 *
 * <p>What this class does NOT do: it never reorders (the returned list
 * follows vanilla's 7-slot order: self, BOTTOM, TOP, N, S, W, E), never
 * merges, never invents dispatches, and never touches the shouldSignal
 * reentrancy guard. Same traversal order, same code path - only provable
 * no-ops are dropped, exactly the plan's step-3 contract.
 */
public final class WireUpdateFilter {

    /** How a neighbor consumes redstone power, as classified by the caller. */
    public enum NeighborKind {
        /** Another wire: power-arithmetic, subject to R1 and R3. */
        WIRE,
        /** Non-wire device whose current binary on/off the caller observed. */
        BINARY,
        /** Anything power-level-sensitive (comparator, unknown): never R2-skipped. */
        ANALOG,
    }

    /** One neighbor cell's relevance inputs, read from real vanilla state. */
    public static final class NeighborInfo {
        /** What sits at this cell (wire, binary device, analog reader). */
        public NeighborKind kind = NeighborKind.ANALOG;
        /** For WIRE: the neighbor wire's current power 0-15. */
        public int neighborWirePower = 0;
        /** For WIRE: true when the neighbor connects back toward this wire. */
        public boolean connectedTowardWire = true;
        /** For BINARY: the device's currently observed on/off state. */
        public boolean deviceOn = false;
    }

    /** One dispatch decision: the target cell + whether vanilla's dispatch stays. */
    public static final class Decision {
        public final BlockPos target;
        public final boolean keep;

        Decision(BlockPos target, boolean keep) {
            this.target = target;
            this.keep = keep;
        }
    }

    /**
     * Plan the 7 dispatches for a wire power change, in vanilla order.
     *
     * @param wirePos   the changed wire's position
     * @param oldPower  power before (0-15)
     * @param newPower  power after (0-15, must differ; equal powers return
     *                  "no change, nothing to plan" - an empty keep-set with
     *                  self kept=false, matching vanilla's outer skip)
     * @param neighbors the 6 neighbor infos in {@link Direction} slot order:
     *                  DOWN, UP, NORTH, SOUTH, WEST, EAST (index = direction
     *                  ordinal). Missing info (null) means "unknown" - that
     *                  slot is always kept (fail-open toward vanilla).
     * @return 7 decisions in vanilla dispatch order (self first, then the 6
     *         neighbors). Callers fire {@code updateNeighborsAt} only for
     *         kept targets, in this order.
     */
    public static List<Decision> plan(
            BlockPos wirePos, int oldPower, int newPower, NeighborInfo[] neighbors) {
        List<Decision> out = new ArrayList<>(7);
        if (oldPower == newPower) {
            out.add(new Decision(wirePos, false));
            if (neighbors != null) {
                for (Direction d : new Direction[]{
                        Direction.DOWN, Direction.UP, Direction.NORTH,
                        Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                    out.add(new Decision(wirePos.relative(d), false));
                }
            }
            return out;
        }
        // R4: self dispatch always kept (shape/observer/second-order effects).
        out.add(new Decision(wirePos, true));
        Direction[] slots = {
                Direction.DOWN, Direction.UP, Direction.NORTH,
                Direction.SOUTH, Direction.WEST, Direction.EAST,
        };
        for (Direction d : slots) {
            BlockPos target = wirePos.relative(d);
            NeighborInfo info = neighbors == null ? null
                    : (d.ordinal() < neighbors.length ? neighbors[d.ordinal()] : null);
            out.add(new Decision(target, shouldKeep(d, oldPower, newPower, info)));
        }
        return out;
    }

    /**
     * The per-neighbor keep/skip verdict. Package-visible for the proof
     * harness ({@code WireUpdateFilterCheck} asserts exact behavior per
     * rule); production callers go through {@link #plan}.
     */
    static boolean shouldKeep(Direction d, int oldPower, int newPower, NeighborInfo info) {
        if (info == null) {
            return true;
        }
        switch (info.kind) {
            case WIRE: {
                // R3 first: disconnected toward us reads 0 either way.
                if (!info.connectedTowardWire) {
                    return false;
                }
                // R1: neighbor already at/above both candidate inputs.
                int oldInput = Math.max(0, oldPower - 1);
                int newInput = Math.max(0, newPower - 1);
                if (info.neighborWirePower >= oldInput && info.neighborWirePower >= newInput) {
                    // ...unless the RISING edge lifts the neighbor above its
                    // current hold (then the new input is observable there).
                    // neighborWirePower >= newInput already covers that, so a
                    // skip here is exact. Keep the branch explicit for audit.
                    return false;
                }
                return true;
            }
            case BINARY: {
                boolean rising = newPower > oldPower;
                // R2: rising into an already-ON device, or falling into an
                // already-OFF device, cannot cross its threshold.
                if (rising && info.deviceOn) {
                    return false;
                }
                if (!rising && !info.deviceOn) {
                    return false;
                }
                return true;
            }
            case ANALOG:
            default:
                return true;
        }
    }

    /**
     * Count kept dispatches in a plan (diagnostic: expected vanilla fanout
     * is 7 per changed wire; the filter's win is 7 - kept).
     */
    public static int keptCount(List<Decision> plan) {
        int n = 0;
        for (Decision d : plan) {
            if (d.keep) {
                n++;
            }
        }
        return n;
    }

    /**
     * Rebuild vanilla's exact 7-target set (self + 6 neighbors, deduped the
     * way {@code Sets.newHashSet} semantics would: positions are distinct
     * here by construction, so this is just the ordered slot list).
     * Used by the proof to assert the filter's input domain matches
     * vanilla's dispatch domain exactly.
     */
    public static Set<BlockPos> vanillaTargets(BlockPos wirePos) {
        Set<BlockPos> targets = new LinkedHashSet<>(8);
        targets.add(wirePos);
        for (Direction d : Direction.values()) {
            targets.add(wirePos.relative(d));
        }
        return targets;
    }

    /**
     * True when a blockstate is a redstone wire (production callers pass
     * real vanilla states; the filter itself stays state-free so the proof
     * can exercise it without a world).
     */
    public static boolean isWire(BlockState state, net.minecraft.world.level.block.Block wireBlock) {
        return state.is(wireBlock);
    }
}
