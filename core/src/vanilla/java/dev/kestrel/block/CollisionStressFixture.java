package dev.kestrel.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Hand-replicated block geometry of
 * {@code harness/corpus/collision-stress/data/kestrel_corpus/function/init.mcfunction},
 * so the exact same field of stairs/slabs/fences/walls/snow-layers/hopper/
 * cauldron that stress-tests optimization #1 in real vanilla can also be
 * built as a {@link KestrelBlockGetter}, for direct testing of
 * Kestrel-side collision code without booting a whole vanilla server.
 *
 * <p><b>Only blocks, not entities.</b> The corpus's 40 scripted zombies
 * are a separate concern (an entity/movement model, not built yet); this
 * fixture is block-geometry only.
 *
 * <p>Coordinates here are literal absolute coordinates, matching the
 * datapack's {@code ~} (relative-to-origin) offsets under the assumption
 * the {@code minecraft:load} tag runs from world origin (0, 0, 0), which
 * is how the corpus was actually invoked when it was built and verified
 * against real vanilla (see {@code preresearch/collision-path-analysis.md}).
 * If the two ever need to be re-verified against each other, this is the
 * file to check first for a coordinate mismatch.
 */
public final class CollisionStressFixture {
    private CollisionStressFixture() {
    }

    /** Real overworld height bounds (26.x), matches what the corpus's world used. */
    public static final int MIN_Y = -64;
    public static final int HEIGHT = 384;

    public static KestrelBlockGetter build() {
        NeighborStateResolver.bindConnectionTags();
        KestrelBlockGetter world = buildUnresolved();
        int sweeps = NeighborStateResolver.resolveAll(world);
        System.out.println("CollisionStressFixture: neighbor states resolved in " + sweeps + " sweep(s).");
        return world;
    }

    /**
     * Raw block states exactly as placed (no neighbor resolution): connection
     * properties all-false. Prefer {@link #build()}; this exists so tests can
     * prove the resolver actually changes something.
     */
    public static KestrelBlockGetter buildUnresolved() {
        KestrelBlockGetter world = new KestrelBlockGetter(MIN_Y, HEIGHT);

        // solid floor: fill ~-18 ~-1 ~-18 ~18 ~-1 ~18 stone
        fillY(world, -18, 18, -1, -18, 18, Blocks.STONE.defaultBlockState());

        // perimeter wall, four sides: fill ...cobblestone_wall (x4)
        BlockState wall = Blocks.COBBLESTONE_WALL.defaultBlockState();
        fill(world, -18, 18, 0, 3, -18, -18, wall); // z = -18 face
        fill(world, -18, 18, 0, 3, 18, 18, wall);    // z = 18 face
        fill(world, -18, -18, 0, 3, -18, 18, wall);  // x = -18 face
        fill(world, 18, 18, 0, 3, -18, 18, wall);    // x = 18 face

        // dense non-full-cube collision-shape field, striped by block type
        BlockState oakStairsNorth = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        fill(world, -17, -10, 0, 0, -17, 17, oakStairsNorth);

        BlockState oakSlabTop = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        fill(world, -17, -10, 1, 1, -17, 17, oakSlabTop);

        BlockState oakSlabBottom = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        fill(world, -9, -2, 0, 0, -17, 17, oakSlabBottom);

        BlockState oakFence = Blocks.OAK_FENCE.defaultBlockState();
        fill(world, -1, 6, 0, 0, -17, 17, oakFence);

        fill(world, 7, 14, 0, 0, -17, 17, wall);

        BlockState snowTwoLayers = Blocks.SNOW.defaultBlockState()
                .setValue(BlockStateProperties.LAYERS, 2);
        fill(world, 15, 17, 0, 0, -17, 17, snowTwoLayers);

        // hopper pocket, then the cauldron overwrites its center (order
        // matches the datapack: fill hopper, THEN setblock cauldron)
        // Hopper declares its own FACING property (restricted to DOWN +
        // the four horizontals, no UP - a hopper can't point up), not the
        // generic 6-direction BlockStateProperties.FACING. Found the hard
        // way: BlockStateProperties.FACING threw
        // "does not exist in Block{minecraft:hopper}" at runtime.
        BlockState hopperDown = Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, Direction.DOWN);
        fill(world, -2, 2, 1, 1, -2, 2, hopperDown);
        world.setBlock(0, 1, 0, Blocks.POWDER_SNOW_CAULDRON.defaultBlockState());

        return world;
    }

    private static void fillY(KestrelBlockGetter world, int x1, int x2, int y, int z1, int z2, BlockState state) {
        fill(world, x1, x2, y, y, z1, z2, state);
    }

    private static void fill(KestrelBlockGetter world, int x1, int x2, int y1, int y2, int z1, int z2, BlockState state) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    world.setBlock(x, y, z, state);
                }
            }
        }
    }
}
