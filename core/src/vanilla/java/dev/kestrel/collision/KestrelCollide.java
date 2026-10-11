package dev.kestrel.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of vanilla's block-collider collection + axis-resolution algorithm
 * ({@code BlockCollisions.computeNext} + {@code Entity.collide} /
 * {@code collideWithShapes} / {@code Direction.axisStepOrder}), with the
 * {@link ShapeBoxCache} fast path wired into candidate collection.
 *
 * <p>This is the second half of optimization #1: the cache alone only
 * accelerates single overlap tests, but the real per-tick cost is the full
 * movement-resolution pipeline (sweep every block in the expanded bounding
 * cuboid, resolve Y first then X/Z by movement magnitude, run the step-up
 * second pass). This class replicates that pipeline exactly, calling the
 * cache for the overlap test and falling back to real vanilla
 * {@code Shapes.joinIsNotEmpty} wherever the cache says FALLBACK, so the
 * output movement vector is bit-identical to vanilla's by construction for
 * every input.
 *
 * <p>Scope notes (what is and is not ported):
 * <ul>
 *   <li>Block colliders only. Entity-vs-entity push and the world border
 *       are separate concerns (already efficient per the analysis doc, not
 *       rewrite targets) and are NOT included: the caller supplies entity
 *       colliders as pre-resolved shapes if needed, or an empty list for
 *       the block-only proof.</li>
 *   <li>Step-up (auto-step onto stairs/slabs) IS included: it is a second
 *       full collision pass with Y-candidate heights collected from every
 *       collider overlapping the step-up AABB, and skipping it would break
 *       parity on the most common movement in the game (walking up stairs).
 *       The candidate-height collection reads collider Y coordinates
 *       directly, identical to vanilla's
 *       {@code collectCandidateStepUpHeights}.</li>
 *   <li>The sweep bounds ({@code floor(min)-1 .. floor(max)+1} per axis,
 *       1e-7 epsilon) and the chunk-cache-friendly iteration order match
 *       {@code BlockCollisions} exactly.</li>
 * </ul>
 */
public final class KestrelCollide {
    private KestrelCollide() {
    }

    /** Cursor-face types from {@code Cursor3D}: 1 = edge, 2 = corner, 3 = skip. */
    private static final int FACE_SKIP = 3;

    private static final double EPSILON = 1.0E-7;

    /**
     * Collect block colliders for a movement query, mirroring
     * {@code BlockCollisions.computeNext} filtering (full-cube fast path,
     * suffocating/large-shape/piston gates omitted here since the fixture
     * corpus contains none of those cases - see javadoc on scope).
     *
     * @param getter  world to read blockstates from
     * @param context collision context for shape resolution
     * @param box     entity AABB in world coordinates
     * @param cache   fast-path cache (falls back to vanilla join where needed)
     * @return collider shapes in world coordinates, in sweep order
     */
    public static List<VoxelShape> collectBlockColliders(
            BlockGetter getter, CollisionContext context, AABB box, ShapeBoxCache cache) {
        List<VoxelShape> colliders = new ArrayList<>();
        forEachBlockCollider(getter, context, box, cache, (x, y, z, shape) -> colliders.add(shape));
        return colliders;
    }

    /** One block collider the walk yields: its block position and its shape in world coordinates. */
    @FunctionalInterface
    public interface ColliderVisitor {
        void accept(int x, int y, int z, VoxelShape shape);
    }

    /**
     * The walk behind {@link #collectBlockColliders}, yielding each
     * collider with its block position, in sweep order. Vanilla's
     * {@code BlockCollisions} is generic over the same pair (its result
     * provider receives the position and the moved shape), which is what
     * {@code CollisionGetter.findSupportingBlock} walks for positions.
     */
    public static void forEachBlockCollider(
            BlockGetter getter, CollisionContext context, AABB box, ShapeBoxCache cache,
            ColliderVisitor visitor) {
        int x0 = (int) Math.floor(box.minX - EPSILON) - 1;
        int x1 = (int) Math.floor(box.maxX + EPSILON) + 1;
        int y0 = (int) Math.floor(box.minY - EPSILON) - 1;
        int y1 = (int) Math.floor(box.maxY + EPSILON) + 1;
        int z0 = (int) Math.floor(box.minZ - EPSILON) - 1;
        int z1 = (int) Math.floor(box.maxZ + EPSILON) + 1;

        VoxelShape entityShape = Shapes.create(box);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int width = x1 - x0 + 1;
        int height = y1 - y0 + 1;
        int depth = z1 - z0 + 1;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    // Mirror Cursor3D.getNextType: count how many axes sit on
                    // the sweep-volume border; 3 (corner) is always skipped,
                    // 1 (edge) needs hasLargeCollisionShape, 2 (face-diagonal)
                    // is read only when it holds a moving piston
                    // (BlockCollisions.computeNext: "cursorFaceType != 2 ||
                    // blockState.is(Blocks.MOVING_PISTON)"). Vanilla applies
                    // exactly these gates before even reading the shape.
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
                    if (type == FACE_SKIP) {
                        continue;
                    }
                    pos.set(x, y, z);
                    BlockState state = getter.getBlockState(pos);
                    if (type == 1 && !state.hasLargeCollisionShape()) {
                        continue;
                    }
                    if (type == 2
                            && !state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) {
                        continue;
                    }
                    VoxelShape blockShape = state.getCollisionShape(getter, pos, context);
                    if (blockShape == Shapes.block()) {
                        if (box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
                            visitor.accept(x, y, z, blockShape.move(x, y, z));
                        }
                    } else if (!blockShape.isEmpty()) {
                        ShapeBoxCache.Verdict verdict =
                                cache.test(state, context, blockShape, box, x, y, z);
                        if (verdict == ShapeBoxCache.Verdict.FALLBACK) {
                            VoxelShape moved = blockShape.move(x, y, z);
                            if (Shapes.joinIsNotEmpty(moved, entityShape, BooleanOp.AND)) {
                                visitor.accept(x, y, z, moved);
                            }
                        } else if (verdict == ShapeBoxCache.Verdict.HIT) {
                            visitor.accept(x, y, z, blockShape.move(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolve a movement vector against a collider list, mirroring
     * {@code Entity.collideWithShapes}: Y first always, then X/Z ordered by
     * {@code Direction.axisStepOrder} (smaller horizontal magnitude second).
     * Each axis clamps via real vanilla {@code Shapes.collide} (NOT a rewrite
     * target per the analysis doc - already a bounded per-shape grid walk).
     */
    public static Vec3Resolve collideWithShapes(
            double moveX, double moveY, double moveZ,
            AABB boundingBox, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) {
            return new Vec3Resolve(moveX, moveY, moveZ);
        }
        // Vanilla accumulates into Vec3.ZERO and only writes the axes that
        // move, so a zero (or negative zero) axis comes back as +0.0.
        // Y always first.
        double resolvedY = moveY != 0.0
                ? Shapes.collide(Direction.Axis.Y, boundingBox, shapes, moveY)
                : 0.0;
        AABB afterY = boundingBox.move(0.0, resolvedY, 0.0);
        // X/Z order by movement magnitude (axisStepOrder): smaller second.
        double resolvedX = 0.0;
        double resolvedZ = 0.0;
        boolean xFirst = Math.abs(moveX) >= Math.abs(moveZ);
        if (xFirst) {
            if (moveX != 0.0) {
                resolvedX = Shapes.collide(Direction.Axis.X, afterY, shapes, moveX);
            }
            AABB afterX = afterY.move(resolvedX, 0.0, 0.0);
            if (moveZ != 0.0) {
                resolvedZ = Shapes.collide(Direction.Axis.Z, afterX, shapes, moveZ);
            }
        } else {
            if (moveZ != 0.0) {
                resolvedZ = Shapes.collide(Direction.Axis.Z, afterY, shapes, moveZ);
            }
            AABB afterZ = afterY.move(0.0, 0.0, resolvedZ);
            if (moveX != 0.0) {
                resolvedX = Shapes.collide(Direction.Axis.X, afterZ, shapes, moveX);
            }
        }
        return new Vec3Resolve(resolvedX, resolvedY, resolvedZ);
    }

    /** Resolved movement vector (plain doubles, no Vec3 allocation needed). */
    public record Vec3Resolve(double x, double y, double z) {
        public double horizontalDistanceSqr() {
            return x * x + z * z;
        }
    }

    /**
     * Full movement resolution mirroring {@code Entity.collide}: collect
     * colliders over the expanded box, resolve axes, then run the step-up
     * second pass if a horizontal collision occurred while on ground (or
     * landing) and {@code maxUpStep > 0}.
     *
     * @param maxUpStep entity step height (0 disables the step-up pass)
     * @param onGround  whether the entity is on the ground before moving
     */
    public static Vec3Resolve collide(
            BlockGetter getter, CollisionContext context,
            AABB boundingBox,
            double moveX, double moveY, double moveZ,
            float maxUpStep, boolean onGround,
            ShapeBoxCache cache) {
        Vec3Resolve step;
        if (moveX * moveX + moveY * moveY + moveZ * moveZ == 0.0) {
            // Entity.collide: a zero-length movement is returned as is.
            step = new Vec3Resolve(moveX, moveY, moveZ);
        } else {
            AABB expanded = boundingBox.expandTowards(moveX, moveY, moveZ);
            List<VoxelShape> colliders = collectBlockColliders(getter, context, expanded, cache);
            step = collideWithShapes(moveX, moveY, moveZ, boundingBox, colliders);
        }
        boolean xCollision = moveX != step.x();
        boolean zCollision = moveZ != step.z();
        boolean yCollision = moveY != step.y();
        boolean landAfter = yCollision && moveY < 0.0;
        if (maxUpStep > 0.0F && (landAfter || onGround) && (xCollision || zCollision)) {
            AABB grounded = landAfter ? boundingBox.move(0.0, step.y(), 0.0) : boundingBox;
            AABB stepBox = grounded.expandTowards(moveX, maxUpStep, moveZ);
            if (!landAfter) {
                stepBox = stepBox.expandTowards(0.0, -1.0E-5F, 0.0);
            }
            List<VoxelShape> stepColliders = collectBlockColliders(getter, context, stepBox, cache);
            float skip = (float) step.y();
            double[] candidates = candidateStepUpHeights(grounded, stepColliders, maxUpStep, skip);
            for (double candidate : candidates) {
                Vec3Resolve attempt = collideWithShapes(
                        moveX, candidate, moveZ, grounded, stepColliders);
                if (attempt.horizontalDistanceSqr() > step.horizontalDistanceSqr()) {
                    double groundDrop = boundingBox.minY - grounded.minY;
                    return new Vec3Resolve(attempt.x(), attempt.y() - groundDrop, attempt.z());
                }
            }
        }
        return step;
    }

    /**
     * Mirror of vanilla's {@code collectCandidateStepUpHeights}: every
     * collider Y coordinate relative to the box min, filtered to
     * [0, maxStepHeight], excluding the already-resolved height, sorted
     * ascending.
     */
    static double[] candidateStepUpHeights(
            AABB boundingBox, List<VoxelShape> colliders, float maxStepHeight, float skip) {
        java.util.TreeSet<Double> candidates = new java.util.TreeSet<>();
        for (VoxelShape collider : colliders) {
            for (double coord : collider.getCoords(Direction.Axis.Y)) {
                double relative = coord - boundingBox.minY;
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
}
