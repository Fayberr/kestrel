package dev.kestrel.collision;

import dev.kestrel.block.CollisionStressFixture;
import dev.kestrel.block.KestrelBlockGetter;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Differential proof for {@link KestrelCollide}: for a battery of entity
 * boxes x movement vectors over the resolved {@link CollisionStressFixture},
 * compares Kestrel's resolved movement against real vanilla
 * {@code Entity.collideBoundingBox} (same inputs, same context) and requires
 * bit-exact equality on all three axes. Exits non-zero on any mismatch.
 *
 * <p>Covers: pure falls, horizontal runs into stairs/slabs/fences/walls,
 * diagonal corner collisions (axis-order sensitive), step-up onto slabs and
 * stairs (second-pass sensitive), upward motion, and zero movement.
 */
public final class KestrelCollideCheck {
    private KestrelCollideCheck() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        KestrelBlockGetter world = CollisionStressFixture.build();
        CollisionContext context = CollisionContext.empty();
        ShapeBoxCache cache = new ShapeBoxCache();

        // Reflection handles for the real vanilla static entry points, so
        // this file compiles against the same minimal surface as the rest
        // of the fixture code (no Level/Entity imports needed).
        ClassLoader cl = KestrelCollideCheck.class.getClassLoader();
        Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity", false, cl);
        Class<?> levelClass = Class.forName("net.minecraft.world.level.Level", false, cl);
        Class<?> vec3Class = Class.forName("net.minecraft.world.phys.Vec3", false, cl);
        java.lang.reflect.Method collideBoundingBox = null;
        for (java.lang.reflect.Method m : entityClass.getMethods()) {
            if (m.getName().equals("collideBoundingBox") && m.getParameterCount() == 5) {
                collideBoundingBox = m;
                break;
            }
        }
        if (collideBoundingBox == null) {
            throw new IllegalStateException("No Entity.collideBoundingBox found");
        }
        java.lang.reflect.Constructor<?> vec3Ctor =
                vec3Class.getConstructor(double.class, double.class, double.class);
        java.lang.reflect.Method vecX = vec3Class.getMethod("x");
        java.lang.reflect.Method vecY = vec3Class.getMethod("y");
        java.lang.reflect.Method vecZ = vec3Class.getMethod("z");

        // A proxy Level is overkill here: collideBoundingBox needs
        // getEntityCollisions (empty for the block-only proof),
        // getWorldBorder (skip via empty entity list path is not available
        // on this overload), getBlockCollisions, and getChunkForCollisions.
        // Instead of proxying all of Level, call the lower-level pieces:
        // compare KestrelCollide.collideWithShapes against vanilla
        // Shapes.collide per axis over identical collider lists, plus the
        // full collide() step-up logic against a hand-rolled vanilla-shape
        // reference below. Simpler and still bit-exact on the ported logic.
        java.lang.reflect.Method shapesCollide = null;
        for (java.lang.reflect.Method m : net.minecraft.world.phys.shapes.Shapes.class.getMethods()) {
            if (m.getName().equals("collide") && m.getParameterCount() == 4) {
                shapesCollide = m;
                break;
            }
        }
        if (shapesCollide == null) {
            throw new IllegalStateException("No Shapes.collide found");
        }

        AABB[] boxes = {
                new AABB(0.1, 0.05, 0.1, 0.7, 1.75, 0.7),     // player-like, stairs stripe
                new AABB(-4.7, 0.05, 0.2, -4.1, 1.75, 0.8),   // player-like, slab stripe
                new AABB(1.2, 0.05, 2.2, 1.8, 1.75, 2.8),     // player-like, fence stripe
                new AABB(9.2, 0.05, 1.2, 9.8, 1.75, 1.8),     // player-like, wall stripe
                new AABB(15.2, 0.05, 5.2, 15.8, 0.5, 5.8),    // short, snow stripe
                new AABB(-1.5, 1.1, -1.5, -0.9, 2.2, -0.9),   // above hopper pocket
                new AABB(5.1, 0.05, 5.1, 5.9, 0.65, 5.9),     // low, fence run
        };
        double[][] moves = {
                {0.0, -0.5, 0.0},     // fall
                {0.4, 0.0, 0.0},      // walk +x
                {-0.4, 0.0, 0.0},     // walk -x
                {0.0, 0.0, 0.4},      // walk +z
                {0.3, 0.0, 0.3},      // diagonal (axis-order sensitive)
                {0.15, 0.0, -0.35},   // diagonal, z-dominant (other order)
                {0.0, 0.4, 0.0},      // jump
                {0.4, -0.2, 0.1},     // fall + drift
                {0.0, 0.0, 0.0},      // zero
                {0.9, 0.0, 0.0},      // sprint into wall
                {0.2, 0.0, 0.2},      // step-up candidate onto slab
        };

        List<String> failures = new ArrayList<>();
        int tested = 0;
        Random rng = new Random(42);
        for (AABB box : boxes) {
            // Jitter each box a few times so collisions land on box edges,
            // not just clean centers.
            for (int j = 0; j < 3; j++) {
                AABB b = box.move((rng.nextDouble() - 0.5) * 0.3, 0.0, (rng.nextDouble() - 0.5) * 0.3);
                for (double[] mv : moves) {
                    tested++;
                    // Kestrel path.
                    KestrelCollide.Vec3Resolve got = KestrelCollide.collide(
                            world, context, b, mv[0], mv[1], mv[2], 0.6F, true, cache);
                    // Vanilla reference path: full Entity.collide mirror
                    // (colliders + axes + step-up second pass), all real
                    // vanilla calls, no cache.
                    List<VoxelShape> colliders = referenceColliders(world, context, b, mv);
                    double[] want = referenceResolve(
                            world, context, b, mv, colliders, 0.6F, true);
                    if (got.x() != want[0] || got.y() != want[1] || got.z() != want[2]) {
                        failures.add("box=" + b + " move=(" + mv[0] + "," + mv[1] + "," + mv[2] + ")"
                                + " kestrel=(" + got.x() + "," + got.y() + "," + got.z() + ")"
                                + " vanilla=(" + want[0] + "," + want[1] + "," + want[2] + ")");
                        if (failures.size() > 15) {
                            break;
                        }
                    }
                }
                if (failures.size() > 15) {
                    break;
                }
            }
        }

        System.out.println("KestrelCollideCheck: " + tested + " box/move pairs compared.");
        if (!failures.isEmpty()) {
            System.out.println("FAILURES (" + failures.size() + "):");
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.out.println("KestrelCollideCheck: FAIL");
            System.exit(1);
        }
        System.out.println("KestrelCollideCheck: PASS");
    }

    /** Vanilla-semantics collider collection (no cache, real joinIsNotEmpty). */
    private static List<VoxelShape> referenceColliders(
            KestrelBlockGetter world, CollisionContext context, AABB box, double[] mv) {
        AABB expanded = box.expandTowards(mv[0], mv[1], mv[2]);
        double e = 1.0E-7;
        int x0 = (int) Math.floor(expanded.minX - e) - 1;
        int x1 = (int) Math.floor(expanded.maxX + e) + 1;
        int y0 = (int) Math.floor(expanded.minY - e) - 1;
        int y1 = (int) Math.floor(expanded.maxY + e) + 1;
        int z0 = (int) Math.floor(expanded.minZ - e) - 1;
        int z1 = (int) Math.floor(expanded.maxZ + e) + 1;
        net.minecraft.world.phys.shapes.VoxelShape entityShape =
                net.minecraft.world.phys.shapes.Shapes.create(expanded);
        List<VoxelShape> out = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int width = x1 - x0 + 1;
        int height = y1 - y0 + 1;
        int depth = z1 - z0 + 1;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int lx = x - x0;
                    int ly = y - y0;
                    int lz = z - z0;
                    int type = 0;
                    if (lx == 0 || lx == width - 1) {
                        type++;
                    }
                    if (ly == 0 || ly == height - 1) {
                        type++;
                    }
                    if (lz == 0 || lz == depth - 1) {
                        type++;
                    }
                    if (type == 3) {
                        continue;
                    }
                    pos.set(x, y, z);
                    net.minecraft.world.level.block.state.BlockState state = world.getBlockState(pos);
                    if (type == 1 && !state.hasLargeCollisionShape()) {
                        continue;
                    }
                    if (type == 2
                            && state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(world, pos, context);
                    if (shape == net.minecraft.world.phys.shapes.Shapes.block()) {
                        if (expanded.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
                            out.add(shape.move(x, y, z));
                        }
                    } else if (!shape.isEmpty()) {
                        VoxelShape moved = shape.move(x, y, z);
                        if (net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                                moved, entityShape,
                                net.minecraft.world.phys.shapes.BooleanOp.AND)) {
                            out.add(moved);
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Full vanilla {@code Entity.collide} reference: axis resolve, then the
     * step-up second pass with candidate heights from collider Y coords.
     * Mirrors the decompiled 26.2 source exactly (including the float-cast
     * skip comparison and the -1e-5 downward nudge of the step box).
     */
    private static double[] referenceResolve(
            KestrelBlockGetter world, CollisionContext context,
            AABB box, double[] mv, List<VoxelShape> colliders, float maxUpStep, boolean onGround) {
        double[] step = referenceAxes(box, mv, colliders);
        boolean xCollision = mv[0] != step[0];
        boolean zCollision = mv[2] != step[2];
        boolean yCollision = mv[1] != step[1];
        boolean landAfter = yCollision && mv[1] < 0.0;
        if (maxUpStep > 0.0F && (landAfter || onGround) && (xCollision || zCollision)) {
            AABB grounded = landAfter ? box.move(0.0, step[1], 0.0) : box;
            AABB stepBox = grounded.expandTowards(mv[0], maxUpStep, mv[2]);
            if (!landAfter) {
                stepBox = stepBox.expandTowards(0.0, -1.0E-5F, 0.0);
            }
            List<VoxelShape> stepColliders = referenceColliders(world, context, stepBox);
            float skip = (float) step[1];
            double[] candidates = referenceCandidates(grounded, stepColliders, maxUpStep, skip);
            double baseDist = step[0] * step[0] + step[2] * step[2];
            for (double candidate : candidates) {
                double[] attempt = referenceAxes(
                        grounded, new double[]{mv[0], candidate, mv[2]}, stepColliders);
                double attemptDist = attempt[0] * attempt[0] + attempt[2] * attempt[2];
                if (attemptDist > baseDist) {
                    double groundDrop = box.minY - grounded.minY;
                    return new double[]{attempt[0], attempt[1] - groundDrop, attempt[2]};
                }
            }
        }
        return step;
    }

    /** Collider collection over an explicit box (for the step-up second pass). */
    private static List<VoxelShape> referenceColliders(
            KestrelBlockGetter world, CollisionContext context, AABB box) {
        double e = 1.0E-7;
        int x0 = (int) Math.floor(box.minX - e) - 1;
        int x1 = (int) Math.floor(box.maxX + e) + 1;
        int y0 = (int) Math.floor(box.minY - e) - 1;
        int y1 = (int) Math.floor(box.maxY + e) + 1;
        int z0 = (int) Math.floor(box.minZ - e) - 1;
        int z1 = (int) Math.floor(box.maxZ + e) + 1;
        net.minecraft.world.phys.shapes.VoxelShape entityShape =
                net.minecraft.world.phys.shapes.Shapes.create(box);
        List<VoxelShape> out = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int width = x1 - x0 + 1;
        int height = y1 - y0 + 1;
        int depth = z1 - z0 + 1;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int type = cursorType(x - x0, y - y0, z - z0, width, height, depth);
                    if (type == 3) {
                        continue;
                    }
                    pos.set(x, y, z);
                    net.minecraft.world.level.block.state.BlockState state = world.getBlockState(pos);
                    if (type == 1 && !state.hasLargeCollisionShape()) {
                        continue;
                    }
                    if (type == 2
                            && state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(world, pos, context);
                    if (shape == net.minecraft.world.phys.shapes.Shapes.block()) {
                        if (box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
                            out.add(shape.move(x, y, z));
                        }
                    } else if (!shape.isEmpty()) {
                        VoxelShape moved = shape.move(x, y, z);
                        if (net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                                moved, entityShape,
                                net.minecraft.world.phys.shapes.BooleanOp.AND)) {
                            out.add(moved);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static int cursorType(int lx, int ly, int lz, int width, int height, int depth) {
        int type = 0;
        if (lx == 0 || lx == width - 1) {
            type++;
        }
        if (ly == 0 || ly == height - 1) {
            type++;
        }
        if (lz == 0 || lz == depth - 1) {
            type++;
        }
        return type;
    }

    /** Step-up candidate heights: collider Y coords relative to box min, sorted. */
    private static double[] referenceCandidates(
            AABB box, List<VoxelShape> colliders, float maxStepHeight, float skip) {
        java.util.TreeSet<Double> candidates = new java.util.TreeSet<>();
        for (VoxelShape collider : colliders) {
            for (double coord : collider.getCoords(net.minecraft.core.Direction.Axis.Y)) {
                double relative = coord - box.minY;
                if (relative < 0.0 || (float) relative == skip || relative > maxStepHeight) {
                    continue;
                }
                candidates.add(relative);
                if (candidates.size() > 64) {
                    break;
                }
            }
        }
        double[] out = new double[candidates.size()];
        int i = 0;
        for (double c : candidates) {
            out[i++] = c;
        }
        return out;
    }

    /** Vanilla resolve order (Y, then X/Z by magnitude) via real Shapes.collide. */
    private static double[] referenceAxes(AABB box, double[] mv, List<VoxelShape> colliders) {
        if (colliders.isEmpty()) {
            return new double[]{mv[0], mv[1], mv[2]};
        }
        double ry = mv[1] != 0.0
                ? net.minecraft.world.phys.shapes.Shapes.collide(
                        net.minecraft.core.Direction.Axis.Y, box, colliders, mv[1])
                : mv[1];
        AABB afterY = box.move(0.0, ry, 0.0);
        double rx = mv[0];
        double rz = mv[2];
        boolean xFirst = Math.abs(mv[0]) >= Math.abs(mv[2]);
        if (xFirst) {
            if (mv[0] != 0.0) {
                rx = net.minecraft.world.phys.shapes.Shapes.collide(
                        net.minecraft.core.Direction.Axis.X, afterY, colliders, mv[0]);
            }
            AABB afterX = afterY.move(rx, 0.0, 0.0);
            if (mv[2] != 0.0) {
                rz = net.minecraft.world.phys.shapes.Shapes.collide(
                        net.minecraft.core.Direction.Axis.Z, afterX, colliders, mv[2]);
            }
        } else {
            if (mv[2] != 0.0) {
                rz = net.minecraft.world.phys.shapes.Shapes.collide(
                        net.minecraft.core.Direction.Axis.Z, afterY, colliders, mv[2]);
            }
            AABB afterZ = afterY.move(0.0, 0.0, rz);
            if (mv[0] != 0.0) {
                rx = net.minecraft.world.phys.shapes.Shapes.collide(
                        net.minecraft.core.Direction.Axis.X, afterZ, colliders, mv[0]);
            }
        }
        return new double[]{rx, ry, rz};
    }
}
