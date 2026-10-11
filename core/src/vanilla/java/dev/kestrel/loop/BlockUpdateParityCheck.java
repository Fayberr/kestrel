package dev.kestrel.loop;

import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.PostProcessParityCheck;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.ScheduledTick;

/** Raw live {@code TickLoop.setBlock} parity against vanilla's real {@code Level.setBlock}. */
public final class BlockUpdateParityCheck {
    private static final long RANDOM_SEED = 0x47363631L;
    private static final int SEED_FLAGS = 2 | 16 | 512;
    private static int checks;

    private BlockUpdateParityCheck() { }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        HeadlessNoise.Driver driver = HeadlessNoise.loadStructural(1L).driver();
        PostProcessParityCheck.OracleLevel oracle = PostProcessParityCheck.emptyOracle(driver, RANDOM_SEED);
        TickLoop loop = new TickLoop(new KestrelLevel(KestrelLevel.Dimension.OVERWORLD,
                driver.height().getMinY(), driver.height().getHeight()));
        loop.setGameTime(1L);
        loop.setLevelRandom(RandomSource.create(RANDOM_SEED));
        loop.captureLevelEvents();
        List<String> liveWrites = new ArrayList<>();
        loop.hooks().onBlockChange((ignored, event) -> liveWrites.add(event.pos().toVanilla().asLong() + ":"
                + Block.getId(event.oldState().unwrap()) + ">" + Block.getId(event.newState().unwrap())
                + ":" + event.flags()));
        List<Scenario> scenarios = scenarios();
        for (Scenario scenario : scenarios) {
            for (Cell cell : scenario.seed()) {
                oracle.setBlock(cell.pos(), cell.state(), SEED_FLAGS);
                loop.setBlock(cell.pos(), cell.state(), SEED_FLAGS);
            }
            oracle.clearObservations();
            loop.clearLevelEvents();
            liveWrites.clear();
            for (Write write : scenario.writes()) {
                if (write.orDestroy()) {
                    // Block.updateOrDestroy called directly, as PistonMovingBlockEntity.finalTick
                    // does with flags 3: the only live caller whose flags still carry bit 1.
                    Block.updateOrDestroy(oracle.getBlockState(write.pos()), write.state(), oracle,
                            write.pos(), write.flags(), write.limit());
                    loop.redstone().updateOrDestroy(loop.getBlockState(write.pos()), write.state(),
                            write.pos(), write.flags(), write.limit());
                } else {
                    oracle.setBlock(write.pos(), write.state(), write.flags(), write.limit());
                    loop.setBlock(write.pos(), write.state(), write.flags(), write.limit());
                }
            }
            same(scenario.name() + " writes", oracle.detailedLog(), liveWrites);
            for (BlockPos pos : scenario.compare()) {
                same(scenario.name() + " cell " + pos, Block.getId(oracle.getBlockState(pos)),
                        Block.getId(loop.getBlockState(pos)));
            }
            same(scenario.name() + " block ticks", tickStrings(oracle.pendingBlockTicks()), liveBlockTicks(loop));
            same(scenario.name() + " fluid ticks", fluidTickStrings(oracle.pendingFluidTicks()), liveFluidTicks(loop));
            same(scenario.name() + " events", oracle.levelEvents(), liveEvents(loop));
        }
        System.out.println("BlockUpdateParityCheck: PASSED (" + checks + " checks, "
                + scenarios.size() + " scenarios)");
    }

    private static List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();
        int x = -12;
        out.add(support("floor_torch", x++, Blocks.TORCH.defaultBlockState()));
        out.add(support("snow", x++, Blocks.SNOW.defaultBlockState()));
        BlockPos wall = p(x++, 70);
        out.add(new Scenario("wall_torch", List.of(c(wall, Blocks.STONE.defaultBlockState()),
                c(wall.east(), Blocks.WALL_TORCH.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST))),
                List.of(w(wall, Blocks.AIR.defaultBlockState())), around(wall, wall.east())));
        for (var entry : List.of(java.util.Map.entry("fence", Blocks.OAK_FENCE),
                java.util.Map.entry("wall", Blocks.COBBLESTONE_WALL),
                java.util.Map.entry("glass_pane", Blocks.GLASS_PANE),
                java.util.Map.entry("iron_bars", Blocks.IRON_BARS))) {
            BlockPos pos = p(x++, 70);
            out.add(new Scenario(entry.getKey() + "_connect_disconnect",
                    List.of(c(pos, entry.getValue().defaultBlockState())),
                    List.of(w(pos.east(), Blocks.STONE.defaultBlockState()),
                            w(pos.east(), Blocks.AIR.defaultBlockState())), around(pos, pos.east())));
        }
        BlockPos stairs = p(x++, 70);
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT);
        out.add(new Scenario("stair_shapes", List.of(c(stairs, stair)),
                List.of(w(stairs.east(), stair.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)),
                        w(stairs.east(), Blocks.AIR.defaultBlockState())), around(stairs, stairs.east())));
        BlockPos door = p(x++, 70);
        BlockState doorLower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState doorUpper = doorLower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        out.add(new Scenario("door_both_halves", List.of(c(door.below(), Blocks.STONE.defaultBlockState()),
                c(door, doorLower), c(door.above(), doorUpper)),
                List.of(w(door.below(), Blocks.AIR.defaultBlockState())), around(door.below(), door.above())));
        BlockPos plant = p(x++, 70);
        BlockState plantLower = Blocks.SUNFLOWER.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState plantUpper = plantLower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        out.add(new Scenario("tall_plant_both_halves", List.of(c(plant.below(), Blocks.DIRT.defaultBlockState()),
                c(plant, plantLower), c(plant.above(), plantUpper)),
                List.of(w(plant.below(), Blocks.AIR.defaultBlockState())), around(plant.below(), plant.above())));
        for (Block block : List.of(Blocks.SAND, Blocks.GRAVEL)) {
            for (boolean flowing : List.of(false, true)) {
                for (boolean sandBelow : List.of(false, true)) {
                    BlockPos pos = p(x++, 80);
                    BlockState water = flowing ? Fluids.FLOWING_WATER.getFlowing(4, false).createLegacyBlock()
                            : Blocks.WATER.defaultBlockState();
                    List<Cell> seed = new ArrayList<>();
                    seed.add(c(pos, block.defaultBlockState()));
                    if (sandBelow) seed.add(c(pos.below(), Blocks.SAND.defaultBlockState()));
                    out.add(new Scenario(Block.getId(block.defaultBlockState()) + "_under_"
                            + (flowing ? "flowing" : "source") + "_water_" + (sandBelow ? "sand" : "air"),
                            seed, List.of(w(pos.above(), water)), around(pos.below(), pos.above())));
                }
            }
        }
        BlockPos dragon = p(x++, 80);
        out.add(new Scenario("dragon_egg_delay", List.of(),
                List.of(new Write(dragon, Blocks.DRAGON_EGG.defaultBlockState(), 3, 512)), around(dragon)));
        BlockPos fire = p(x++, 70);
        out.add(new Scenario("fire_destroy_no_2001", List.of(c(fire.below(), Blocks.STONE.defaultBlockState()),
                c(fire, Blocks.FIRE.defaultBlockState())),
                List.of(w(fire.below(), Blocks.AIR.defaultBlockState())), around(fire.below(), fire)));
        BlockPos wet = p(x++, 70);
        BlockState wetLantern = Blocks.LANTERN.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);
        out.add(new Scenario("waterlogged_destroy_legacy_fluid_and_2001",
                List.of(c(wet.below(), Blocks.STONE.defaultBlockState()), c(wet, wetLantern)),
                List.of(w(wet.below(), Blocks.AIR.defaultBlockState())), around(wet.below(), wet)));
        BlockPos limited = p(x++, 70);
        out.add(new Scenario("update_limit_zero", List.of(c(limited, Blocks.STONE.defaultBlockState()),
                c(limited.above(), Blocks.TORCH.defaultBlockState())),
                List.of(new Write(limited, Blocks.AIR.defaultBlockState(), 3, 0)), around(limited, limited.above())));
        BlockPos limitedDoor = p(x++, 70);
        BlockState limitedLower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState limitedUpper = limitedLower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        out.add(new Scenario("update_limit_expires_mid_cascade",
                List.of(c(limitedDoor.below(), Blocks.STONE.defaultBlockState()), c(limitedDoor, limitedLower),
                        c(limitedDoor.above(), limitedUpper)),
                List.of(new Write(limitedDoor.below(), Blocks.AIR.defaultBlockState(), 3, 1)),
                around(limitedDoor.below(), limitedDoor.above())));
        BlockPos mixed = p(x++, 80);
        BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
        out.add(new Scenario("fluid_redstone_interleave",
                List.of(c(mixed, Blocks.SAND.defaultBlockState()),
                        c(mixed.east().below(), Blocks.STONE.defaultBlockState()), c(mixed.east(), wire)),
                List.of(w(mixed.above(), Blocks.WATER.defaultBlockState())),
                around(mixed.below(), mixed.above(), mixed.east())));
        BlockPos indirect = p(x++, 70);
        BlockState eastConnectedWire = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(
                net.minecraft.world.level.block.RedStoneWireBlock.EAST,
                net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE);
        out.add(new Scenario("wire_indirect_neighbour_shapes",
                List.of(c(indirect.below(), Blocks.STONE.defaultBlockState()),
                        c(indirect.east().below().below(), Blocks.STONE.defaultBlockState()),
                        c(indirect.east().below(), Blocks.REDSTONE_WIRE.defaultBlockState())),
                List.of(w(indirect, eastConnectedWire)),
                around(indirect.below(), indirect.east())));
        BlockPos pistonFlag = p(x++, 70);
        out.add(new Scenario("shape_flag_masks",
                List.of(c(pistonFlag, Blocks.OAK_FENCE.defaultBlockState())),
                List.of(new Write(pistonFlag.east(), Blocks.STONE.defaultBlockState(), 3 | 32, 512)),
                around(pistonFlag, pistonFlag.east())));
        BlockPos coral = p(x++, 70);
        out.add(new Scenario("shape_uses_level_random",
                List.of(c(coral, Blocks.FIRE_CORAL_BLOCK.defaultBlockState())),
                List.of(w(coral.east(), Blocks.STONE.defaultBlockState())),
                around(coral, coral.east())));
        BlockPos rawFence = p(x++, 70);
        out.add(new Scenario("written_block_has_no_self_update",
                List.of(c(rawFence.east(), Blocks.STONE.defaultBlockState())),
                List.of(w(rawFence, Blocks.OAK_FENCE.defaultBlockState())),
                around(rawFence, rawFence.east())));
        BlockPos direct = p(x++, 90);
        BlockState flowing = Fluids.FLOWING_WATER.getFlowing(4, false).createLegacyBlock();
        out.add(new Scenario("update_or_destroy_flags_3_keeps_neighbour_bit",
                List.of(c(direct.east(), Blocks.STONE.defaultBlockState()),
                        c(direct, Blocks.OAK_FENCE.defaultBlockState()), c(direct.west(), flowing)),
                List.of(new Write(direct, Blocks.OAK_FENCE.defaultBlockState()
                        .setValue(BlockStateProperties.EAST, true), 3, 512, true)),
                around(direct.west(), direct.east())));
        return out;
    }

    private static Scenario support(String name, int x, BlockState dependent) {
        BlockPos pos = p(x, 70);
        return new Scenario(name, List.of(c(pos, Blocks.STONE.defaultBlockState()), c(pos.above(), dependent)),
                List.of(w(pos, Blocks.AIR.defaultBlockState())), around(pos, pos.above()));
    }

    private static List<String> tickStrings(List<? extends ScheduledTick<Block>> ticks) {
        return ticks.stream().map(t -> Block.getId(t.type().defaultBlockState()) + ":" + t.pos().asLong() + ":"
                + t.triggerTick() + ":" + t.priority().getValue() + ":" + t.subTickOrder()).toList();
    }

    private static List<String> fluidTickStrings(List<? extends ScheduledTick<net.minecraft.world.level.material.Fluid>> ticks) {
        return ticks.stream().map(t -> net.minecraft.core.registries.BuiltInRegistries.FLUID.getId(t.type()) + ":"
                + t.pos().asLong() + ":" + t.triggerTick() + ":" + t.priority().getValue()).toList();
    }

    private static List<String> liveBlockTicks(TickLoop loop) {
        return loop.dumpBlockTicks().stream().map(t -> Block.getId(t.block().defaultBlockState()) + ":"
                + t.pos().asLong() + ":" + t.triggerTick() + ":" + t.priority().getValue() + ":"
                + t.subTickOrder()).toList();
    }

    private static List<String> liveFluidTicks(TickLoop loop) {
        return loop.dumpFluidQueue().stream().map(t -> net.minecraft.core.registries.BuiltInRegistries.FLUID
                .getId(t.fluid()) + ":" + t.pos().toVanilla().asLong() + ":" + t.triggerTick() + ":"
                + t.priority()).toList();
    }

    private static List<String> liveEvents(TickLoop loop) {
        return loop.dumpLevelEvents().stream()
                .map(e -> e.type() + ":" + e.pos().asLong() + ":" + e.data()).toList();
    }

    private static List<BlockPos> around(BlockPos... anchors) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos anchor : anchors) {
            for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                BlockPos pos = anchor.offset(dx, dy, dz);
                if (!out.contains(pos)) out.add(pos);
            }
        }
        return out;
    }

    private static BlockPos p(int x, int y) { return new BlockPos(x, y, 0); }
    private static Cell c(BlockPos pos, BlockState state) { return new Cell(pos, state); }
    private static Write w(BlockPos pos, BlockState state) { return new Write(pos, state, 3, 512, false); }
    private static void same(String label, Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + "\nvanilla=" + expected + "\nkestrel=" + actual);
    }
    private record Cell(BlockPos pos, BlockState state) { }
    private record Write(BlockPos pos, BlockState state, int flags, int limit, boolean orDestroy) {
        Write(BlockPos pos, BlockState state, int flags, int limit) { this(pos, state, flags, limit, false); }
    }
    private record Scenario(String name, List<Cell> seed, List<Write> writes, List<BlockPos> compare) { }
}
