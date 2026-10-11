package dev.kestrel.harness;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot audit of every registered {@code BlockState}'s collision shape
 * box-count, run once at server start (guarded by {@link #DONE}).
 *
 * This exists to answer step 2 of preresearch/collision-path-analysis.md's
 * "Next steps": does the "bypass VoxelShape boolean algebra with direct
 * AABB-vs-AABB math against toAabbs()" plan actually cover the real block
 * registry, or does a meaningful fraction of blocks have collision shapes
 * that decompose into "too many" boxes to make that worthwhile?
 *
 * Deliberately reflection-only, same bootstrap-classloader constraint as
 * {@link WorldStateHasher} - see that class's javadoc for the full
 * rationale. Ships in the boot jar alongside it.
 *
 * For every BlockState in {@code Block.BLOCK_STATE_REGISTRY}, reads its
 * (already-cached, per BlockBehaviour$BlockStateBase - see
 * collision-path-analysis.md) collision shape via the 2-arg
 * {@code getCollisionShape(BlockGetter, BlockPos)} overload against
 * {@code EmptyBlockGetter.INSTANCE} / {@code BlockPos(0,0,0)} (the
 * context-free shape - matches what a static startup audit can see;
 * context-dependent shapes, e.g. leaves with a scaffolding-distance
 * property, are a documented known-gap below), and tallies:
 *   - how many box-count buckets (0, 1, 2, 3, 4, 5+) the registry falls into
 *   - how many states are exactly the fast-pathed full unit cube
 *     ({@code Shapes.block()} identity - already has zero work needed,
 *     vanilla's own BlockCollisions fast-paths this case already)
 *
 * Output: a plain text histogram written next to the tick log (see
 * {@link HarnessAgent#premain}), NOT part of the per-tick hashed state.
 *
 * KNOWN GAP: this only audits the context-free shape. Some blocks return a
 * different shape depending on {@code CollisionContext} (entity dimensions/
 * position) or neighbor BlockGetter state (e.g. fences/walls/panes connect
 * to neighbors, redstone dust connects to neighbors) - those are NOT
 * captured by this static per-state audit and could have a different (or
 * per-neighbor-configuration-varying) box count than what's reported here.
 * Good enough to answer "does the bypass generally apply", not a substitute
 * for the harness's real differential per-tick hash once an implementation
 * exists.
 */
public final class BlockShapeAudit {

    private static final AtomicBoolean DONE = new AtomicBoolean(false);
    private static volatile String outputPath = "block-shape-audit.txt";

    private BlockShapeAudit() {
    }

    /** Called directly from HarnessAgent (app classloader) - unlike
     *  WorldStateHasher (only ever called from TickLogger, which is
     *  itself bootstrap-loaded, so package-private is fine there), this
     *  class must be public: a package-private class is only accessible
     *  within the same *runtime* package, which is scoped per classloader,
     *  so HarnessAgent (app loader) calling a package-private bootstrap-
     *  loaded class throws IllegalAccessError even though the textual
     *  package name matches. Confirmed empirically 2026-09-10. */
    public static void setOutputPath(String path) {
        outputPath = path;
    }

    /** Safe to call every tick - only does real work once. */
    public static void auditOnce(Object server) {
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        try {
            runAudit(server);
        } catch (Throwable t) {
            System.err.println("[kestrel-harness] block-shape audit failed: " + t);
            t.printStackTrace();
        }
    }

    private static void runAudit(Object server) throws Exception {
        ClassLoader cl = server.getClass().getClassLoader();

        Class<?> blockClass = cls(cl, "net.minecraft.world.level.block.Block");
        Class<?> blockStateClass = cls(cl, "net.minecraft.world.level.block.state.BlockState");
        Class<?> blockGetterClass = cls(cl, "net.minecraft.world.level.BlockGetter");
        Class<?> blockPosClass = cls(cl, "net.minecraft.core.BlockPos");
        Class<?> emptyBlockGetterClass = cls(cl, "net.minecraft.world.level.EmptyBlockGetter");
        Class<?> voxelShapeClass = cls(cl, "net.minecraft.world.phys.shapes.VoxelShape");
        Class<?> shapesClass = cls(cl, "net.minecraft.world.phys.shapes.Shapes");
        Class<?> registryClass = cls(cl, "net.minecraft.core.Registry");
        Class<?> builtInRegistriesClass = cls(cl, "net.minecraft.core.registries.BuiltInRegistries");

        Object registry = field(blockClass, "BLOCK_STATE_REGISTRY").get(null);
        Method getCollisionShape = method(blockStateClass, "getCollisionShape", blockGetterClass, blockPosClass);
        Method toAabbs = method(voxelShapeClass, "toAabbs");
        Method shapesBlock = method(shapesClass, "block");
        Method getBlock = method(blockStateClass, "getBlock");
        Method registryGetKey = method(registryClass, "getKey", Object.class);
        Object blockRegistry = field(builtInRegistriesClass, "BLOCK").get(null);

        Constructor<?> blockPosCtor = blockPosClass.getConstructor(int.class, int.class, int.class);
        blockPosCtor.setAccessible(true);
        Object zeroPos = blockPosCtor.newInstance(0, 0, 0);
        Object emptyGetter = field(emptyBlockGetterClass, "INSTANCE").get(null);
        Object fullCubeShape = shapesBlock.invoke(null);

        TreeMap<Integer, Integer> histogram = new TreeMap<>();
        int total = 0;
        int fullCubeCount = 0;
        int failures = 0;
        TreeMap<Integer, String> examplesOverFour = new TreeMap<>();

        for (Object state : (Iterable<?>) registry) {
            total++;
            try {
                Object shape = getCollisionShape.invoke(state, emptyGetter, zeroPos);
                if (shape == fullCubeShape) {
                    fullCubeCount++;
                }
                List<?> boxes = (List<?>) toAabbs.invoke(shape);
                int count = boxes.size();
                histogram.merge(count, 1, Integer::sum);
                if (count > 4 && examplesOverFour.size() < 25) {
                    Object block = getBlock.invoke(state);
                    Object key = registryGetKey.invoke(blockRegistry, block);
                    examplesOverFour.put(count, String.valueOf(key));
                }
            } catch (Throwable t) {
                failures++;
            }
        }

        try (BufferedWriter w = new BufferedWriter(new FileWriter(outputPath, false))) {
            w.write("# kestrel-harness block-shape audit (one-shot, server start)");
            w.newLine();
            w.write("# total_block_states=" + total + " full_cube_fast_path=" + fullCubeCount
                    + " reflection_failures=" + failures);
            w.newLine();
            w.write("# box_count,state_count");
            w.newLine();
            for (var e : histogram.entrySet()) {
                w.write(e.getKey() + "," + e.getValue());
                w.newLine();
            }
            w.write("# examples with >4 boxes (box_count,block_id):");
            w.newLine();
            for (var e : examplesOverFour.entrySet()) {
                w.write("# " + e.getKey() + "," + e.getValue());
                w.newLine();
            }
        }
        System.out.println("[kestrel-harness] block-shape audit written to " + outputPath
                + " (" + total + " states, " + fullCubeCount + " full-cube fast-path)");
    }

    private static Class<?> cls(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, true, cl);
    }

    private static Method method(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        Method m = c.getMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getField(name);
        f.setAccessible(true);
        return f;
    }
}
