package dev.kestrel.redstone;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Correctness proof for the step-3 default-path classes
 * ({@link WireConnectionCache}, {@link WireUpdateFilter},
 * {@link WireSectionLayout}): exercises the cache lifecycle
 * (resolve -> snapshot -> invalidate/generation-bump -> miss), asserts every
 * filter skip rule against hand-computed vanilla semantics, checks the
 * section layout's pack/unpack + indexing round-trips, and cross-checks the
 * filter's dispatch domain against vanilla's real 7-target dispatch set.
 * Exits non-zero on any mismatch.
 *
 * <p>What this proof does NOT do (deliberate scope): it does not run a live
 * world or touch {@code Level.updateNeighborsAt} - the tick-hash harness on
 * the redstone-line corpus is the live proof. This check proves the building
 * blocks are internally exact so the harness result is interpretable: a
 * harness PASS means the integration is right, not that a lucky filter
 * masked a broken unit.
 */
public final class RedstoneProofCheck {
    private RedstoneProofCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();

        checkCache(failures);
        checkFilter(failures);
        checkLayout(failures);
        checkVanillaDomain(failures);

        System.out.println("RedstoneProofCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    // ---- WireConnectionCache ----

    private static void checkCache(List<String> failures) {
        WireConnectionCache cache = new WireConnectionCache();
        BlockPos pos = new BlockPos(10, 64, -3);
        WireConnectionCache.Record r = cache.recordFor(pos);

        // Fresh record: no snapshot (miss), not fresh.
        if (cache.snapshot(r) != null) {
            failures.add("cache: fresh record should miss");
        }
        if (cache.isFresh(r)) {
            failures.add("cache: fresh record should not be fresh");
        }
        // Resolve: snapshot returns exactly the reported sides.
        cache.reportResolved(r,
                RedstoneSide.SIDE, RedstoneSide.NONE, RedstoneSide.UP, RedstoneSide.SIDE);
        RedstoneSide[] snap = cache.snapshot(r);
        if (snap == null
                || snap[0] != RedstoneSide.SIDE
                || snap[1] != RedstoneSide.NONE
                || snap[2] != RedstoneSide.UP
                || snap[3] != RedstoneSide.SIDE) {
            failures.add("cache: snapshot should return reported sides in N/E/S/W order");
        }
        if (!cache.isFresh(r)) {
            failures.add("cache: resolved record should be fresh");
        }
        // Surgical invalidate: miss until re-resolved.
        cache.invalidate(pos);
        if (cache.snapshot(r) != null) {
            failures.add("cache: invalidated record should miss");
        }
        cache.reportResolved(r,
                RedstoneSide.NONE, RedstoneSide.NONE, RedstoneSide.NONE, RedstoneSide.NONE);
        if (cache.snapshot(r) == null) {
            failures.add("cache: re-resolved record should hit");
        }
        // Generation bump stales everything: miss until re-resolved.
        long g0 = cache.generation();
        cache.onNeighborChanged();
        if (cache.generation() != g0 + 1) {
            failures.add("cache: neighbor change should bump generation");
        }
        if (cache.snapshot(r) != null) {
            failures.add("cache: post-bump record should miss (stale generation)");
        }
        cache.reportResolved(r,
                RedstoneSide.SIDE, RedstoneSide.SIDE, RedstoneSide.SIDE, RedstoneSide.SIDE);
        if (cache.snapshot(r) == null) {
            failures.add("cache: post-bump re-resolve should hit");
        }
        // recordFor returns the same record for equal positions.
        if (cache.recordFor(new BlockPos(10, 64, -3)) != r) {
            failures.add("cache: recordFor should return the same record per position");
        }
        // Invalidating an unknown position is a safe no-op.
        try {
            cache.invalidate(new BlockPos(9999, 1, 9999));
        } catch (Exception e) {
            failures.add("cache: invalidate of unknown pos should not throw");
        }
    }

    // ---- WireUpdateFilter ----

    private static WireUpdateFilter.NeighborInfo wire(int power, boolean connected) {
        WireUpdateFilter.NeighborInfo n = new WireUpdateFilter.NeighborInfo();
        n.kind = WireUpdateFilter.NeighborKind.WIRE;
        n.neighborWirePower = power;
        n.connectedTowardWire = connected;
        return n;
    }

    private static WireUpdateFilter.NeighborInfo binary(boolean on) {
        WireUpdateFilter.NeighborInfo n = new WireUpdateFilter.NeighborInfo();
        n.kind = WireUpdateFilter.NeighborKind.BINARY;
        n.deviceOn = on;
        return n;
    }

    private static WireUpdateFilter.NeighborInfo analog() {
        WireUpdateFilter.NeighborInfo n = new WireUpdateFilter.NeighborInfo();
        n.kind = WireUpdateFilter.NeighborKind.ANALOG;
        return n;
    }

    private static WireUpdateFilter.NeighborInfo[] all(
            WireUpdateFilter.NeighborInfo down, WireUpdateFilter.NeighborInfo up,
            WireUpdateFilter.NeighborInfo north, WireUpdateFilter.NeighborInfo south,
            WireUpdateFilter.NeighborInfo west, WireUpdateFilter.NeighborInfo east) {
        return new WireUpdateFilter.NeighborInfo[]{down, up, north, south, west, east};
    }

    private static void expect(boolean cond, String what, List<String> failures) {
        if (!cond) {
            failures.add("filter: " + what);
        }
    }

    private static void checkFilter(List<String> failures) {
        BlockPos wire = new BlockPos(0, 64, 0);

        // R4: self dispatch always kept on a real change...
        List<WireUpdateFilter.Decision> plan = WireUpdateFilter.plan(
                wire, 15, 14, all(null, null, null, null, null, null));
        expect(plan.size() == 7, "plan has 7 decisions", failures);
        expect(plan.get(0).keep && plan.get(0).target.equals(wire),
                "self dispatch kept on change", failures);
        // ...unknown neighbors fail open (kept).
        for (int i = 1; i < 7; i++) {
            expect(plan.get(i).keep, "unknown neighbor kept (slot " + i + ")", failures);
        }
        // Order matches vanilla: self, DOWN, UP, NORTH, SOUTH, WEST, EAST.
        Direction[] order = {Direction.DOWN, Direction.UP, Direction.NORTH,
                Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (int i = 0; i < 6; i++) {
            expect(plan.get(i + 1).target.equals(wire.relative(order[i])),
                    "slot order matches vanilla (" + order[i] + ")", failures);
        }

        // No-change input: nothing kept (mirrors vanilla's outer skip).
        List<WireUpdateFilter.Decision> noop = WireUpdateFilter.plan(
                wire, 10, 10, all(null, null, null, null, null, null));
        expect(WireUpdateFilter.keptCount(noop) == 0, "equal powers keep nothing", failures);

        // R1: neighbor wire already at/above both candidate inputs -> skip.
        // Wire drops 15->14: candidates are 14 and 13. Neighbor at 14 skips.
        List<WireUpdateFilter.Decision> r1 = WireUpdateFilter.plan(
                wire, 15, 14,
                all(null, null, wire(14, true), wire(0, true), null, null));
        expect(!r1.get(3).keep, "R1: north wire at 14 skips on 15->14", failures);
        expect(r1.get(4).keep, "R1: south wire at 0 kept on 15->14", failures);

        // R1 rising: wire 5->12, candidates 4 and 11. Neighbor at 12 skips;
        // neighbor at 10 is lifted (11 > 10) so kept.
        List<WireUpdateFilter.Decision> r1r = WireUpdateFilter.plan(
                wire, 5, 12,
                all(null, null, wire(12, true), wire(10, true), null, null));
        expect(!r1r.get(3).keep, "R1: north wire at 12 skips on 5->12", failures);
        expect(r1r.get(4).keep, "R1: south wire at 10 kept on 5->12 (lifted)", failures);

        // R1 boundary: rise to 15 from 0, neighbor at 14 IS lifted to 14
        // (14 >= 14) so skips; neighbor at 13 is lifted (14 > 13) so kept.
        List<WireUpdateFilter.Decision> r1b = WireUpdateFilter.plan(
                wire, 0, 15,
                all(null, null, wire(14, true), wire(13, true), null, null));
        expect(!r1b.get(3).keep, "R1: north wire at 14 skips on 0->15", failures);
        expect(r1b.get(4).keep, "R1: south wire at 13 kept on 0->15", failures);

        // R3: disconnected-toward-us wire skips regardless of power.
        List<WireUpdateFilter.Decision> r3 = WireUpdateFilter.plan(
                wire, 0, 15,
                all(null, null, wire(0, false), null, null, null));
        expect(!r3.get(3).keep, "R3: disconnected north wire skips", failures);

        // R2: rising into ON device skips; rising into OFF device kept;
        // falling into OFF device skips; falling into ON device kept.
        List<WireUpdateFilter.Decision> r2 = WireUpdateFilter.plan(
                wire, 0, 15,
                all(binary(true), binary(false), null, null, null, null));
        expect(!r2.get(1).keep, "R2: rising into ON device skips", failures);
        expect(r2.get(2).keep, "R2: rising into OFF device kept", failures);
        List<WireUpdateFilter.Decision> r2f = WireUpdateFilter.plan(
                wire, 15, 0,
                all(binary(false), binary(true), null, null, null, null));
        expect(!r2f.get(1).keep, "R2: falling into OFF device skips", failures);
        expect(r2f.get(2).keep, "R2: falling into ON device kept", failures);

        // ANALOG never skipped by edge rules.
        List<WireUpdateFilter.Decision> an = WireUpdateFilter.plan(
                wire, 0, 15, all(analog(), analog(), analog(), analog(), analog(), analog()));
        expect(WireUpdateFilter.keptCount(an) == 7, "analog neighbors all kept", failures);

        // Settled-line case: middle wire flickers 15->14, both wire flanks
        // already at max from the other side -> both skip, self kept only.
        List<WireUpdateFilter.Decision> settled = WireUpdateFilter.plan(
                wire, 15, 14,
                all(null, null, wire(15, true), wire(15, true), null, null));
        expect(WireUpdateFilter.keptCount(settled) == 5,
                "settled line: 5 kept (self + 4 unknowns), both flanks skip", failures);
    }

    // ---- WireSectionLayout ----

    private static void checkLayout(List<String> failures) {
        BlockPos p0 = new BlockPos(16, 64, -32);
        long key = WireSectionLayout.keyFor(p0);
        WireSectionLayout layout = new WireSectionLayout(key);

        if (!layout.contains(p0)) {
            failures.add("layout: should contain its seed position");
        }
        if (!layout.contains(new BlockPos(31, 79, -17))) {
            failures.add("layout: should contain same-section corner");
        }
        if (layout.contains(new BlockPos(32, 64, -32))) {
            failures.add("layout: should not contain next-section x");
        }
        if (layout.hasWire(p0)) {
            failures.add("layout: empty cell should read no-wire");
        }
        if (layout.powerAt(p0) != WireSectionLayout.NO_WIRE) {
            failures.add("layout: empty cell power should be NO_WIRE");
        }

        // Index math: x fastest, then z, then y; corners pack correctly.
        if (WireSectionLayout.index(0, 0, 0) != 0) {
            failures.add("layout: index(0,0,0) should be 0");
        }
        if (WireSectionLayout.index(1, 0, 0) != 1) {
            failures.add("layout: index(1,0,0) should be 1");
        }
        if (WireSectionLayout.index(0, 0, 1) != 16) {
            failures.add("layout: index(0,0,1) should be 16");
        }
        if (WireSectionLayout.index(0, 1, 0) != 256) {
            failures.add("layout: index(0,1,0) should be 256");
        }
        if (WireSectionLayout.index(15, 15, 15) != WireSectionLayout.CELLS - 1) {
            failures.add("layout: max corner should be CELLS-1");
        }

        // Put + read round-trip, incl. negative coords (mask to 0-15).
        RedstoneSide[] sides = {RedstoneSide.SIDE, RedstoneSide.NONE, RedstoneSide.UP, RedstoneSide.SIDE};
        layout.put(p0, 14, sides);
        if (!layout.hasWire(p0)) {
            failures.add("layout: stored cell should read has-wire");
        }
        if (layout.powerAt(p0) != 14) {
            failures.add("layout: stored power should round-trip");
        }
        byte nibble = layout.sidesAt(p0);
        boolean[] bits = WireSectionLayout.unpackSides(nibble);
        if (!bits[0] || bits[1] || !bits[2] || !bits[3]) {
            failures.add("layout: sides nibble should round-trip N=1,E=0,S=1,W=1");
        }
        if (layout.wireCount() != 1) {
            failures.add("layout: wireCount should be 1 after one put");
        }
        // Re-put same cell: count stays (update, not new wire).
        layout.put(p0, 13, sides);
        if (layout.wireCount() != 1 || layout.powerAt(p0) != 13) {
            failures.add("layout: re-put should update power without growing count");
        }
        layout.setScratchPower(p0, 7);
        if (layout.powerAt(p0) != 7) {
            failures.add("layout: scratch power write should stick");
        }

        // packSides null-safety + all-NONE packs to 0.
        if (WireSectionLayout.packSides(null) != 0) {
            failures.add("layout: null sides should pack to 0");
        }
        RedstoneSide[] none = {
                RedstoneSide.NONE, RedstoneSide.NONE, RedstoneSide.NONE, RedstoneSide.NONE};
        if (WireSectionLayout.packSides(none) != 0) {
            failures.add("layout: all-NONE should pack to 0");
        }
    }

    // ---- Vanilla dispatch domain ----

    private static void checkVanillaDomain(List<String> failures) {
        // The filter plans exactly vanilla's dispatch domain: self + 6.
        BlockPos wire = new BlockPos(5, 64, 5);
        Set<BlockPos> targets = WireUpdateFilter.vanillaTargets(wire);
        if (targets.size() != 7) {
            failures.add("domain: vanilla target set should have 7 entries");
        }
        if (!targets.contains(wire)) {
            failures.add("domain: target set should contain self");
        }
        for (Direction d : Direction.values()) {
            if (!targets.contains(wire.relative(d))) {
                failures.add("domain: target set should contain " + d);
            }
        }
        // isWire agrees with vanilla identity on real states.
        BlockState wireState = Blocks.REDSTONE_WIRE.defaultBlockState();
        BlockState stoneState = Blocks.STONE.defaultBlockState();
        if (!WireUpdateFilter.isWire(wireState, Blocks.REDSTONE_WIRE)) {
            failures.add("domain: redstone wire state should classify as wire");
        }
        if (WireUpdateFilter.isWire(stoneState, Blocks.REDSTONE_WIRE)) {
            failures.add("domain: stone state should not classify as wire");
        }
        // POWER round-trips on the real property (the value the filter
        // reasons about is the value vanilla stores).
        BlockState powered = wireState.setValue(
                net.minecraft.world.level.block.RedStoneWireBlock.POWER, 14);
        if ((Integer) powered.getValue(
                net.minecraft.world.level.block.RedStoneWireBlock.POWER) != 14) {
            failures.add("domain: POWER setValue/getValue should round-trip 14");
        }
    }
}
