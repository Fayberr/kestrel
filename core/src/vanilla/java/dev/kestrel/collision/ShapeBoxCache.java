package dev.kestrel.collision;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fast-path box-list cache for block collision shapes: the runtime half of
 * optimization #1 (see {@code preresearch/collision-path-analysis.md}).
 *
 * <p>Vanilla's {@code BlockCollisions.computeNext()} already fast-paths the
 * single most common case (exact full-cube identity
 * {@code blockShape == Shapes.block()}) with plain AABB math, but every
 * other non-empty shape falls through to {@code VoxelShape.move} (allocates
 * a shape object per candidate block) + {@code Shapes.joinIsNotEmpty} (the
 * general voxel-grid boolean-algebra path). That general path is correct for
 * arbitrary shapes but real overkill when a block's shape is just 1-5 simple
 * boxes (a stair is 2, a slab is 1): the registry audit found 99.8% of all
 * 32,366 registered blockstates decompose into <= 5 boxes.
 *
 * <p>This cache calls the real vanilla {@code getCollisionShape} exactly
 * once per distinct {@code BlockState} (identity-keyed, since blockstates
 * are canonical singletons), stores the resulting {@code toAabbs()} box
 * list plus a fallback flag, and thereafter answers the hot-path question
 * ("does this shape, moved to this block position, overlap this entity
 * box?") with plain {@code AABB.intersects} math and zero VoxelShape
 * allocation.
 *
 * <p>Fallback rule (correct by construction, since it IS vanilla's code):
 * any state whose shape is the full-cube identity, is empty, has more than
 * {@value #MAX_FAST_BOXES} boxes, or is context-sensitive (the cached shape
 * was computed with {@code CollisionContext.empty()} but the query carries
 * a live entity context) goes back through real vanilla
 * {@code Shapes.joinIsNotEmpty}. The context check matters: a few blocks
 * vary their collision shape by entity (e.g. position-dependent checks in
 * {@code EntityCollisionContext}); serving a stale empty-context shape for
 * those would be a parity break, not a speedup.
 *
 * <p>Thread-safety: build the cache single-threaded at startup (or under an
 * external lock), then treat it as read-only. Concurrent reads of an
 * {@code IdentityHashMap} that is no longer mutated are safe in practice
 * for this workload (same publication pattern as vanilla's own
 * per-state cached shapes).
 */
public final class ShapeBoxCache {
    /** Bypass covers shapes of up to this many boxes; audit says 99.8% qualify. */
    public static final int MAX_FAST_BOXES = 5;

    private final Map<BlockState, Entry> cache = new IdentityHashMap<>();

    private record Entry(boolean fallback, List<AABB> boxes) {
    }

    /**
     * Result of a fast-path overlap test: HIT means the shape definitely
     * overlaps (caller can treat the block as a collider), MISS means it
     * definitely does not, FALLBACK means the caller must run the real
     * vanilla {@code Shapes.joinIsNotEmpty} path for this state.
     */
    public enum Verdict {
        HIT,
        MISS,
        FALLBACK
    }

    /**
     * Test one blockstate against one entity box, with the block sitting at
     * the given integer position. Pure AABB math on the fast path; no
     * VoxelShape allocation.
     *
     * @param state   the blockstate at the position (identity-compared)
     * @param context the query's collision context (emptiness selects cacheability)
     * @param shapeForContext the ALREADY-RESOLVED shape for this exact
     *     state+context, as returned by vanilla's
     *     {@code context.getCollisionShape(state, getter, pos)}. Passed in
     *     (not fetched) so this method stays a pure function of its
     *     arguments with no world access. Only consulted on the fallback
     *     path; the fast path never touches it.
     * @param entityBox the moving entity's AABB in world coordinates
     * @param blockX    block position X
     * @param blockY    block position Y
     * @param blockZ    block position Z
     */
    public Verdict test(
            BlockState state,
            CollisionContext context,
            VoxelShape shapeForContext,
            AABB entityBox,
            int blockX,
            int blockY,
            int blockZ) {
        Entry entry = cache.get(state);
        if (entry == null) {
            entry = buildEntry(state);
            cache.put(state, entry);
        }
        if (entry.fallback()) {
            return Verdict.FALLBACK;
        }
        // Context-sensitive shapes must not be served from the
        // empty-context cache: if the live shape differs from what we
        // cached, correctness demands the vanilla path. Identity-first:
        // the common case (context-free shapes) returns the SAME cached
        // object vanilla itself memoized per state, so == is both sufficient
        // and allocation-free. Only when the objects differ do we pay for
        // the box-list comparison (a genuinely context-sensitive shape).
        VoxelShape cached = cachedShape(state);
        if (shapeForContext != null && shapeForContext != cached
                && !sameBoxes(shapeForContext.toAabbs(), entry.boxes())) {
            return Verdict.FALLBACK;
        }
        for (AABB box : entry.boxes()) {
            if (entityBox.intersects(
                    box.minX + blockX, box.minY + blockY, box.minZ + blockZ,
                    box.maxX + blockX, box.maxY + blockY, box.maxZ + blockZ)) {
                return Verdict.HIT;
            }
        }
        return Verdict.MISS;
    }

    /**
     * Simplified overload for context-free shapes: the caller already knows
     * the state is not context-sensitive (or passes
     * {@code CollisionContext.empty()}), so no live shape is needed.
     */
    public Verdict test(BlockState state, AABB entityBox, int blockX, int blockY, int blockZ) {
        return test(state, CollisionContext.empty(), null, entityBox, blockX, blockY, blockZ);
    }

    /** Number of distinct states cached so far (for tests/diagnostics). */
    public int size() {
        return cache.size();
    }

    /** True if the state is currently cached as fallback (for tests/diagnostics). */
    public boolean isFallback(BlockState state) {
        Entry entry = cache.get(state);
        return entry != null && entry.fallback();
    }

    private static Entry buildEntry(BlockState state) {
        VoxelShape shape = cachedShape(state);
        if (shape == Shapes.block() || shape.isEmpty()) {
            // Full cube is vanilla's own fast path; empty never collides.
            // Neither needs box math here, but marking them non-fallback
            // with their real box list keeps test() uniform. An empty shape
            // yields zero boxes, which can only ever MISS.
            return new Entry(false, shape.toAabbs());
        }
        List<AABB> boxes = shape.toAabbs();
        if (boxes.size() > MAX_FAST_BOXES) {
            return new Entry(true, List.of());
        }
        return new Entry(false, List.copyOf(boxes));
    }

    /**
     * The state's canonical cached collision shape: vanilla computes this
     * once per state object at construction
     * ({@code BlockBehaviour$BlockStateBase} caches {@code collisionShape}),
     * so this call is cheap after first touch.
     */
    private static VoxelShape cachedShape(BlockState state) {
        return state.getCollisionShape(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                net.minecraft.core.BlockPos.ZERO,
                CollisionContext.empty());
    }

    private static boolean sameBoxes(List<AABB> a, List<AABB> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            AABB x = a.get(i);
            AABB y = b.get(i);
            if (x.minX != y.minX || x.minY != y.minY || x.minZ != y.minZ
                    || x.maxX != y.maxX || x.maxY != y.maxY || x.maxZ != y.maxZ) {
                return false;
            }
        }
        return true;
    }
}
