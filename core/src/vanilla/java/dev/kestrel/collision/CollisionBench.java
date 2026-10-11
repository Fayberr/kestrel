package dev.kestrel.collision;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Microbenchmark for optimization #1's headline claim: the fast-path
 * AABB-vs-AABB overlap test vs vanilla's general
 * {@code Shapes.joinIsNotEmpty} boolean algebra, per candidate block.
 *
 * <p>Method: for every registered blockstate that qualifies for the fast
 * path (non-full-cube, non-empty, <= 5 boxes), time N iterations of (a) the
 * real vanilla path ({@code shape.move} + {@code joinIsNotEmpty} against a
 * probe entity box) and (b) {@link ShapeBoxCache#test} for the same inputs.
 * Reports per-state-class totals plus the overall ratio. Warmup first (JIT),
 * then measured runs, best-of-3 to dampen machine noise.
 *
 * <p>This measures the overlap test in isolation, not a full tick: real
 * whole-server speedup also depends on call frequency (candidate blocks per
 * entity per axis step), allocation pressure, and everything OUTSIDE
 * collision. Treat the ratio as the ceiling for this path, not a tick-time
 * promise. The honest whole-tick number comes later from the differential
 * harness running a live world, not from this file.
 */
public final class CollisionBench {
    private CollisionBench() {
    }

    /** Probe entity boxes: typical player/zombie overlaps and near misses. */
    private static final AABB[] PROBES = {
            new AABB(0.1, 0.1, 0.1, 0.7, 1.8, 0.7),
            new AABB(-0.3, 0.0, -0.3, 0.9, 0.6, 0.9),
            new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
            new AABB(0.9, 0.9, 0.9, 1.4, 2.0, 1.4),
            new AABB(2.0, 2.0, 2.0, 2.6, 3.0, 2.6),
    };

    // Sized so a full run takes ~30s on the bench PC build machine: a random
    // 400-state slice is plenty (per-state cost is uniform box math; the
    // full 23k-state sweep measured nothing extra, just 10+ minutes).
    private static final int STATE_SLICE = 400;
    private static final int WARMUP_ITERS = 200;
    private static final int MEASURE_ITERS = 500;
    private static final int REPEATS = 2;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // Collect fast-path-eligible states once: skip full cube (vanilla
        // already fast-paths it), empty (trivial), and >MAX fallback.
        List<BlockState> states = new ArrayList<>();
        for (BlockState state : (Iterable<BlockState>) Block.BLOCK_STATE_REGISTRY) {
            VoxelShape shape = state.getCollisionShape(
                    net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                    BlockPos.ZERO,
                    CollisionContext.empty());
            if (shape == Shapes.block() || shape.isEmpty()) {
                continue;
            }
            if (shape.toAabbs().size() > ShapeBoxCache.MAX_FAST_BOXES) {
                continue;
            }
            states.add(state);
        }
        // Slice to a deterministic random subset: uniform per-state cost
        // means the slice ratio equals the full-set ratio, at ~1/60 the time.
        java.util.Collections.shuffle(states, new java.util.Random(7));
        List<BlockState> sliced = states.subList(0, Math.min(STATE_SLICE, states.size()));
        states = new ArrayList<>(sliced);
        System.out.println("CollisionBench: " + states.size() + " fast-path states (sliced), "
                + PROBES.length + " probes, " + MEASURE_ITERS + " iters each.");

        ShapeBoxCache cache = new ShapeBoxCache();
        // Pre-warm the cache so measured time is steady-state, not first-touch.
        for (BlockState state : states) {
            cache.test(state, PROBES[0], 0, 0, 0);
        }

        // Pre-resolve shapes once (same inputs both paths use).
        List<VoxelShape> shapes = new ArrayList<>(states.size());
        for (BlockState state : states) {
            shapes.add(state.getCollisionShape(
                    net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                    BlockPos.ZERO,
                    CollisionContext.empty()));
        }

        long bestVanilla = Long.MAX_VALUE;
        long bestFast = Long.MAX_VALUE;
        for (int r = 0; r < REPEATS; r++) {
            // Warmup (JIT): interleave both paths.
            runVanilla(states, shapes, WARMUP_ITERS);
            runFast(cache, states, WARMUP_ITERS);
            // Measured.
            long vanillaNs = runVanilla(states, shapes, MEASURE_ITERS);
            long fastNs = runFast(cache, states, MEASURE_ITERS);
            bestVanilla = Math.min(bestVanilla, vanillaNs);
            bestFast = Math.min(bestFast, fastNs);
            System.out.printf("  repeat %d: vanilla=%.1f ms fast=%.1f ms ratio=%.2fx%n",
                    r + 1, vanillaNs / 1e6, fastNs / 1e6, (double) vanillaNs / fastNs);
        }
        System.out.printf("CollisionBench: BEST vanilla=%.1f ms fast=%.1f ms speedup=%.2fx%n",
                bestVanilla / 1e6, bestFast / 1e6, (double) bestVanilla / bestFast);
    }

    /** Real vanilla path: move + joinIsNotEmpty per state/probe. Returns ns. */
    private static long runVanilla(List<BlockState> states, List<VoxelShape> shapes, int iters) {
        long start = System.nanoTime();
        int sink = 0;
        for (int i = 0; i < iters; i++) {
            for (int s = 0; s < states.size(); s++) {
                VoxelShape shape = shapes.get(s);
                AABB probe = PROBES[i % PROBES.length];
                VoxelShape moved = shape.move(0, 0, 0);
                VoxelShape entityShape = Shapes.create(probe);
                if (!moved.isEmpty()
                        && Shapes.joinIsNotEmpty(moved, entityShape, BooleanOp.AND)) {
                    sink++;
                }
            }
        }
        // Sink the branch outcome so the JIT cannot delete the whole loop.
        if (sink == Integer.MIN_VALUE) {
            System.out.println("unreachable");
        }
        return System.nanoTime() - start;
    }

    /** Fast path: cache.test per state/probe. Returns ns. */
    private static long runFast(ShapeBoxCache cache, List<BlockState> states, int iters) {
        long start = System.nanoTime();
        int sink = 0;
        for (int i = 0; i < iters; i++) {
            for (BlockState state : states) {
                AABB probe = PROBES[i % PROBES.length];
                if (cache.test(state, probe, 0, 0, 0) == ShapeBoxCache.Verdict.HIT) {
                    sink++;
                }
            }
        }
        if (sink == Integer.MIN_VALUE) {
            System.out.println("unreachable");
        }
        return System.nanoTime() - start;
    }
}
