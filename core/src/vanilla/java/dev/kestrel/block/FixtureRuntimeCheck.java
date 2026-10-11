package dev.kestrel.block;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Standalone runtime proof (not just compile-time) that
 * {@link CollisionStressFixture} + {@link KestrelBlockGetter} actually
 * work against real vanilla shape-computation code, run without a full
 * vanilla server bootstrap. Prints a short report; exits non-zero on any
 * unexpected exception so this is CI/scriptable, not just eyeballed.
 *
 * <p>Run manually (after {@code ./gradlew build -PvanillaJar=...}):
 * <pre>
 * java -cp build\libs\kestrel-core-0.0.1.jar;VANILLA_JAR dev.kestrel.block.FixtureRuntimeCheck
 * </pre>
 */
public final class FixtureRuntimeCheck {
    private FixtureRuntimeCheck() {
    }

    public static void main(String[] args) {
        // Real vanilla requires this before touching Blocks/registries -
        // confirmed the hard way: without it, Blocks.<clinit> throws
        // IllegalArgumentException("Not bootstrapped") from
        // BuiltInRegistries.internalRegister. Matches vanilla's own
        // Main.main() startup sequence.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        System.out.println("FixtureRuntimeCheck: building CollisionStressFixture...");
        KestrelBlockGetter world = CollisionStressFixture.build();
        System.out.println("FixtureRuntimeCheck: fixture built OK.");

        // Neighbor resolution must have actually connected the contiguous
        // fence/wall runs (this is the whole point of the resolver: raw
        // defaultBlockState() leaves every connection false).
        BlockState fenceMid = world.getBlockState(new BlockPos(2, 0, 0));
        BlockState wallMid = world.getBlockState(new BlockPos(10, 0, 0));
        System.out.println("  fence @ (2,0,0): " + fenceMid);
        System.out.println("  wall  @ (10,0,0): " + wallMid);
        boolean connected = fenceMid.toString().contains("=true")
                && wallMid.toString().contains("=low");
        if (!connected) {
            System.out.println("FixtureRuntimeCheck: FAIL (neighbor resolution left fence/wall unconnected)");
            System.exit(1);
        }

        // A handful of positions spanning every stripe + the hopper/cauldron
        // pocket the corpus's init.mcfunction builds, so a real exception in
        // vanilla's own getCollisionShape (context-dependent lookups
        // included) surfaces here, not silently later.
        int[][] samples = {
                {-13, 0, 0},   // oak_stairs[facing=north]
                {-13, 1, 0},   // oak_slab[type=top]
                {-5, 0, 0},    // oak_slab[type=bottom]
                {2, 0, 0},     // oak_fence (neighbor-dependent shape)
                {10, 0, 0},    // cobblestone_wall (neighbor-dependent shape)
                {16, 0, 0},    // snow[layers=2]
                {-1, 1, -1},   // hopper[facing=down]
                {0, 1, 0},     // powder_snow_cauldron
                {0, -1, 0},    // stone floor
                {-18, 1, 0},   // perimeter cobblestone_wall
                {0, 5, 0},     // air (above the field)
        };

        int failures = 0;
        for (int[] p : samples) {
            BlockPos pos = new BlockPos(p[0], p[1], p[2]);
            BlockState state = world.getBlockState(pos);
            try {
                VoxelShape shape = state.getCollisionShape(world, pos);
                int boxCount = shape.toAabbs().size();
                System.out.printf("  %s at %s -> %d box(es)%n", state, pos, boxCount);
            } catch (RuntimeException e) {
                failures++;
                System.out.printf("  %s at %s -> EXCEPTION: %s%n", state, pos, e);
            }
        }

        if (failures > 0) {
            System.out.println("FixtureRuntimeCheck: FAIL (" + failures + " exceptions)");
            System.exit(1);
        } else {
            System.out.println("FixtureRuntimeCheck: PASS (" + samples.length + " samples, zero exceptions)");
        }
    }
}
