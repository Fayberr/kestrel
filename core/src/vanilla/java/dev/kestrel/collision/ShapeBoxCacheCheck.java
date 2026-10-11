package dev.kestrel.collision;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Differential proof for {@link ShapeBoxCache}: for every registered
 * blockstate, compares the fast-path verdict (HIT/MISS) against real
 * vanilla {@code Shapes.joinIsNotEmpty} over a battery of entity boxes, and
 * requires FALLBACK exactly where the cache claims fallback. Exits non-zero
 * on any disagreement, so this is CI/scriptable.
 *
 * <p>Run manually (after {@code ./gradlew build -PvanillaJar=...}):
 * <pre>
 * java -cp build\libs\kestrel-core-0.0.1.jar;VANILLA_JAR dev.kestrel.collision.ShapeBoxCacheCheck
 * </pre>
 */
public final class ShapeBoxCacheCheck {
    private ShapeBoxCacheCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        ShapeBoxCache cache = new ShapeBoxCache();
        List<String> failures = new ArrayList<>();
        int tested = 0;
        int fastHits = 0;
        int fallbacks = 0;

        // Entity boxes spanning the cases that matter: full overlap, edge
        // graze, near miss, tall entity, flat entity, offset positions.
        AABB[] probes = {
                new AABB(0.1, 0.1, 0.1, 0.9, 0.9, 0.9),
                new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5),
                new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
                new AABB(0.99, 0.99, 0.99, 1.5, 1.5, 1.5),
                new AABB(1.5, 1.5, 1.5, 2.0, 2.0, 2.0),
                new AABB(0.2, 0.0, 0.2, 0.8, 2.5, 0.8),
                new AABB(0.0, 0.0, 0.0, 1.0, 0.2, 1.0),
        };

        @SuppressWarnings("unchecked")
        Iterable<BlockState> states =
                (Iterable<BlockState>) net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY;
        for (BlockState state : states) {
            VoxelShape vanillaShape = state.getCollisionShape(
                    net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                    BlockPos.ZERO,
                    CollisionContext.empty());
            boolean expectFallback = vanillaShape == Shapes.block()
                    || vanillaShape.isEmpty()
                    || vanillaShape.toAabbs().size() > ShapeBoxCache.MAX_FAST_BOXES;
            // Full-cube and empty are served fast (vanilla's own fast path /
            // trivial miss), so only >MAX boxes expect FALLBACK here.
            if (vanillaShape == Shapes.block() || vanillaShape.isEmpty()) {
                expectFallback = false;
            }
            for (AABB probe : probes) {
                tested++;
                ShapeBoxCache.Verdict verdict = cache.test(state, probe, 0, 0, 0);
                if (expectFallback) {
                    if (verdict != ShapeBoxCache.Verdict.FALLBACK) {
                        failures.add(state + " probed " + probe
                                + ": expected FALLBACK, got " + verdict);
                    } else {
                        fallbacks++;
                    }
                    continue;
                }
                if (verdict == ShapeBoxCache.Verdict.FALLBACK) {
                    failures.add(state + " probed " + probe + ": unexpected FALLBACK");
                    continue;
                }
                fastHits++;
                VoxelShape moved = vanillaShape.move(0, 0, 0);
                VoxelShape entityShape = Shapes.create(probe);
                boolean vanillaHit = !moved.isEmpty()
                        && Shapes.joinIsNotEmpty(moved, entityShape, BooleanOp.AND);
                boolean fastHit = verdict == ShapeBoxCache.Verdict.HIT;
                if (vanillaHit != fastHit) {
                    failures.add(state + " probed " + probe
                            + ": fast=" + verdict + " vanilla=" + (vanillaHit ? "HIT" : "MISS"));
                }
            }
            if (failures.size() > 20) {
                break;
            }
        }

        System.out.println("ShapeBoxCacheCheck: " + tested + " state/probe pairs, "
                + fastHits + " fast-path, " + fallbacks + " fallback, "
                + cache.size() + " states cached.");
        if (!failures.isEmpty()) {
            System.out.println("FAILURES (" + failures.size() + "):");
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.out.println("ShapeBoxCacheCheck: FAIL");
            System.exit(1);
        }
        System.out.println("ShapeBoxCacheCheck: PASS");
    }
}
