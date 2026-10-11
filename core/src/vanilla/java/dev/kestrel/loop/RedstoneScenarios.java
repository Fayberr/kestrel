package dev.kestrel.loop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * G12 redstone parity corpus: the 27 scenarios specified in section 6 of
 * preresearch/g12-redstone-preresearch.md, plus the two coverage-gap
 * scenarios added on top of them (28: the block-event drain order, 29: the
 * {@code handlingTick} lifetime across the block-entity pass).
 */
public final class RedstoneScenarios {
    private RedstoneScenarios() {
    }

    public static final int MIN_X = -20;
    public static final int MAX_X = 20;
    public static final int MIN_Y = 90;
    public static final int MAX_Y = 115;
    public static final int MIN_Z = -20;
    public static final int MAX_Z = 20;

    /** Raw setup flags: clients only, known shape, skip onPlace. */
    public static final int RAW_FLAGS = 2 | 16 | 512;

    public record Cell(int x, int y, int z, BlockState state, int containerSlot, ItemStack containerItem) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public sealed interface Action permits SetBlockAction, UseAction, ItemAction {
        int tick();
        void apply(ServerLevel level);
    }

    public record SetBlockAction(int tick, int x, int y, int z, BlockState state, int flags) implements Action {
        @Override
        public void apply(ServerLevel level) {
            level.setBlock(new BlockPos(x, y, z), state, flags);
        }
    }

    public record UseAction(int tick, int x, int y, int z) implements Action {
        @Override
        public void apply(ServerLevel level) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState st = level.getBlockState(pos);
            if (st.getBlock() instanceof LeverBlock lever) {
                lever.pull(st, level, pos, null);
            } else if (st.getBlock() instanceof ButtonBlock button) {
                button.press(st, level, pos, null);
            } else {
                throw new IllegalStateException("Cannot USE block at " + pos + ": " + st);
            }
        }
    }

    public record ItemAction(int tick, int x, int y, int z, int slot, ItemStack stack) implements Action {
        @Override
        public void apply(ServerLevel level) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockEntity(pos) instanceof Container container) {
                container.setItem(slot, stack);
                level.updateNeighbourForOutputSignal(pos, level.getBlockState(pos).getBlock());
            }
        }
    }

    public record Scenario(String name, int ticks, List<Cell> setup, List<Action> actions) {
    }

    static final BlockState AIR = Blocks.AIR.defaultBlockState();
    static final BlockState STONE = Blocks.STONE.defaultBlockState();
    static final BlockState REDSTONE_WIRE = Blocks.REDSTONE_WIRE.defaultBlockState();

    public static final class B {
        final String name;
        final int ticks;
        final List<Cell> setup = new ArrayList<>();
        final List<Action> actions = new ArrayList<>();

        B(String name, int ticks) {
            this.name = name;
            this.ticks = ticks;
        }

        public B floor(int y, int minX, int maxX, int minZ, int maxZ, BlockState state) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    setup.add(new Cell(x, y, z, state, -1, ItemStack.EMPTY));
                }
            }
            return this;
        }

        public B set(int x, int y, int z, BlockState state) {
            setup.add(new Cell(x, y, z, state, -1, ItemStack.EMPTY));
            return this;
        }

        public B setContainer(int x, int y, int z, BlockState state, int slot, ItemStack item) {
            setup.add(new Cell(x, y, z, state, slot, item));
            return this;
        }

        public B actSet(int tick, int x, int y, int z, BlockState state, int flags) {
            actions.add(new SetBlockAction(tick, x, y, z, state, flags));
            return this;
        }

        public B actSet(int tick, int x, int y, int z, BlockState state) {
            return actSet(tick, x, y, z, state, 3);
        }

        public B actUse(int tick, int x, int y, int z) {
            actions.add(new UseAction(tick, x, y, z));
            return this;
        }

        public B actItem(int tick, int x, int y, int z, int slot, ItemStack stack) {
            actions.add(new ItemAction(tick, x, y, z, slot, stack));
            return this;
        }

        public Scenario build() {
            return new Scenario(name, ticks, List.copyOf(setup), List.copyOf(actions));
        }
    }

    public static List<Scenario> all() {
        List<Scenario> out = new ArrayList<>();

        // 1. wire_falloff_16: 16-block stone floor with redstone wire. Lever at (0,100,0) powered on at tick 0.
        // Choice: floor lever on stone at y=99, wires x=1..16 at y=100.
        B s1 = new B("wire_falloff_16", 5).floor(99, 0, 16, 0, 0, STONE)
                .set(0, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST));
        for (int x = 1; x <= 16; x++) {
            s1.set(x, 100, 0, REDSTONE_WIRE);
        }
        s1.actUse(0, 0, 100, 0);
        out.add(s1.build());

        // 2. wire_branching_t: T-junction of wire (stem 5 blocks, left/right arms 5 blocks each) powered from stem base.
        // Choice: lever at (0,100,-6), stem z=-5..-1, T-junction at (0,100,0), arms x=-5..-1 and x=1..5 at z=0.
        B s2 = new B("wire_branching_t", 5)
                .floor(99, -5, 5, 0, 0, STONE)
                .floor(99, 0, 0, -6, 0, STONE)
                .set(0, 100, -6, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.SOUTH));
        for (int z = -5; z <= 0; z++) {
            s2.set(0, 100, z, REDSTONE_WIRE);
        }
        for (int x = -5; x <= 5; x++) {
            if (x != 0) {
                s2.set(x, 100, 0, REDSTONE_WIRE);
            }
        }
        s2.actUse(0, 0, 100, -6);
        out.add(s2.build());

        // 3. wire_stair_climb: Wire climbing a 3-step solid stone staircase.
        // Choice: lever at (0,100,0) on floor (0,99,0), step 1 (1,100,0), step 2 (2,101,0), step 3 (3,102,0), top (4,102,0).
        out.add(new B("wire_stair_climb", 5)
                .set(0, 99, 0, STONE).set(0, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .set(1, 99, 0, STONE).set(1, 100, 0, REDSTONE_WIRE)
                .set(2, 100, 0, STONE).set(2, 101, 0, REDSTONE_WIRE)
                .set(3, 101, 0, STONE).set(3, 102, 0, REDSTONE_WIRE)
                .set(4, 101, 0, STONE).set(4, 102, 0, REDSTONE_WIRE)
                .actUse(0, 0, 100, 0)
                .build());

        // 4. wire_stair_occlusion: Same as #3, but with a solid stone block placed directly above the middle step.
        // Choice: solid stone at (2,102,0) directly above wire on step 2 occludes connection to step 3.
        out.add(new B("wire_stair_occlusion", 5)
                .set(0, 99, 0, STONE).set(0, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .set(1, 99, 0, STONE).set(1, 100, 0, REDSTONE_WIRE)
                .set(2, 100, 0, STONE).set(2, 101, 0, REDSTONE_WIRE)
                .set(2, 102, 0, STONE) // Occlusion conductor
                .set(3, 101, 0, STONE).set(3, 102, 0, REDSTONE_WIRE)
                .set(4, 101, 0, STONE).set(4, 102, 0, REDSTONE_WIRE)
                .actUse(0, 0, 100, 0)
                .build());

        // 5. torch_inversion: Solid block with redstone torch on top. Wire powers the solid block at tick 0.
        // Choice: solid block at (0,100,0), torch on top at (0,101,0), wire at (-1,100,0) powered by lever at (-2,100,0) at t=0, unpowered at t=4.
        out.add(new B("torch_inversion", 8)
                .set(0, 100, 0, STONE)
                .set(0, 101, 0, Blocks.REDSTONE_TORCH.defaultBlockState())
                .set(-1, 99, 0, STONE).set(-1, 100, 0, REDSTONE_WIRE)
                .set(-2, 99, 0, STONE).set(-2, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .actUse(0, -2, 100, 0)
                .actUse(4, -2, 100, 0)
                .build());

        // 6. torch_tower: Vertical tower of 5 alternating solid blocks and redstone torches.
        // Choice: base stone (0,99,0) powered by lever at (-1,99,0), torches initialized to alternating steady state (T1 lit, T2 unlit, T3 lit, T4 unlit, T5 lit).
        out.add(new B("torch_tower", 15)
                .set(0, 99, 0, STONE)
                .set(-1, 99, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.WEST))
                .set(0, 100, 0, Blocks.REDSTONE_TORCH.defaultBlockState().setValue(RedstoneTorchBlock.LIT, true))
                .set(0, 101, 0, STONE)
                .set(0, 102, 0, Blocks.REDSTONE_TORCH.defaultBlockState().setValue(RedstoneTorchBlock.LIT, false))
                .set(0, 103, 0, STONE)
                .set(0, 104, 0, Blocks.REDSTONE_TORCH.defaultBlockState().setValue(RedstoneTorchBlock.LIT, true))
                .set(0, 105, 0, STONE)
                .set(0, 106, 0, Blocks.REDSTONE_TORCH.defaultBlockState().setValue(RedstoneTorchBlock.LIT, false))
                .set(0, 107, 0, STONE)
                .set(0, 108, 0, Blocks.REDSTONE_TORCH.defaultBlockState().setValue(RedstoneTorchBlock.LIT, true))
                .set(0, 109, 0, STONE)
                .actUse(0, -1, 99, 0)
                .build());

        // 7. torch_burnout_clock: Torch on side of solid block, wire on top leading back into the solid block.
        // Choice: wall torch at (1,100,0) attached to stone at (0,100,0), stone at (1,101,0), wire placed at (0,101,0) with flag 3 at tick 0 (not in setup).
        out.add(new B("torch_burnout_clock", 200)
                .set(0, 99, 0, STONE)
                .set(0, 100, 0, STONE)
                .set(1, 100, 0, Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(RedstoneWallTorchBlock.FACING, Direction.EAST).setValue(RedstoneWallTorchBlock.LIT, true))
                .set(1, 101, 0, STONE)
                // The wire is NOT in the setup: placing it with flag 3 at tick 0 is what starts
                // the clock (re-setting an identical state is a no-op that fires no updates).
                .actSet(0, 0, 101, 0, REDSTONE_WIRE, 3)
                .build());

        // 8. repeater_delays: 4 parallel lines with repeaters set to delays 1, 2, 3, 4 (delays 2, 4, 6, 8 ticks).
        // Choice: repeaters at x=0, delays 1..4 at z=0,2,4,6, common power bus at x=-1 powered by lever at (-2,100,0).
        B s8 = new B("repeater_delays", 15)
                .floor(99, -2, 1, 0, 6, STONE)
                .set(-2, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST));
        for (int z = 0; z <= 6; z++) {
            s8.set(-1, 100, z, REDSTONE_WIRE);
        }
        for (int d = 1; d <= 4; d++) {
            int z = (d - 1) * 2;
            s8.set(0, 100, z, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, d));
            s8.set(1, 100, z, REDSTONE_WIRE);
        }
        s8.actUse(0, -2, 100, 0);
        out.add(s8.build());

        // 9. repeater_locking: Repeater A powered on. Repeater B faces into side of A, powering on at tick 2. Input to A cut at tick 4.
        // Choice: Repeater A at (0,100,0) facing WEST, Repeater B at (0,100,1) facing SOUTH into A.
        out.add(new B("repeater_locking", 15)
                .floor(99, -1, 1, 0, 2, STONE)
                .set(-1, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .set(0, 100, 0, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST))
                .set(1, 100, 0, REDSTONE_WIRE)
                .set(0, 100, 1, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.SOUTH))
                .set(0, 100, 2, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH))
                .actUse(0, -1, 100, 0) // Turn on A
                .actUse(2, 0, 100, 2)  // Turn on B (locks A)
                .actUse(4, -1, 100, 0) // Cut input to A
                .actUse(8, 0, 100, 2)  // Cut input to B (unlocks A)
                .build());

        // 10. repeater_pulse_stretch: 1-tick pulse input (lever on/off in same tick) fed into 4-delay repeater.
        // Choice: lever at (-1,100,0) toggled on and off at tick 0 into delay-4 repeater at (0,100,0).
        out.add(new B("repeater_pulse_stretch", 16)
                .floor(99, -1, 1, 0, 0, STONE)
                .set(-1, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .set(0, 100, 0, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, 4))
                .set(1, 100, 0, REDSTONE_WIRE)
                .actUse(0, -1, 100, 0)
                .actUse(0, -1, 100, 0)
                .build());

        // 11. repeater_priority_drain: Two repeaters with different priorities (HIGH vs VERY_HIGH) scheduled for the same tick.
        // Choice: Repeater A at (0,100,0) fed by a lever that starts ON (so it settles powered=true), turned off at
        // tick 0 for a genuine falling edge (VERY_HIGH). Repeater B at (0,100,2) fed by a lever that starts OFF,
        // turned on at tick 0 for a genuine rising edge (HIGH). Both edges land on the same drain tick.
        out.add(new B("repeater_priority_drain", 5)
                .floor(99, -2, 1, 0, 2, STONE)
                .set(-2, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST).setValue(LeverBlock.POWERED, true))
                .set(-1, 100, 0, REDSTONE_WIRE)
                .set(0, 100, 0, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST))
                .set(1, 100, 0, REDSTONE_WIRE)
                .set(-1, 100, 2, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .set(0, 100, 2, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST))
                .set(1, 100, 2, REDSTONE_WIRE)
                .actUse(0, -2, 100, 0) // Turns lever off: falling edge on A (VERY_HIGH)
                .actUse(0, -1, 100, 2) // Turns lever on: rising edge on B (HIGH)
                .build());

        // 12. comparator_compare: Comparator in compare mode. Rear signal 10. Side signal 8 at tick 0, then side signal 12 at tick 4.
        // Choice: comparator at (0,100,0) facing WEST, rear powered by redstone block at (-7,100,0) (power 10 at rear:
        // wire power(z) = 16 - Zsource + z, 6 wire cells away -> 16-6=10). Side chain z=1..9, redstone block at
        // (0,100,9) (power(1) = 17-9 = 8) then moved to (0,100,5) at tick 4 (power(1) = 17-5 = 12).
        B s12 = new B("comparator_compare", 10)
                .floor(99, -7, 1, 0, 9, STONE)
                .set(-7, 100, 0, Blocks.REDSTONE_BLOCK.defaultBlockState())
                .set(0, 100, 0, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST).setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE))
                .set(1, 100, 0, REDSTONE_WIRE);
        for (int x = -6; x <= -1; x++) {
            s12.set(x, 100, 0, REDSTONE_WIRE);
        }
        for (int z = 1; z <= 9; z++) {
            s12.set(0, 100, z, REDSTONE_WIRE);
        }
        s12.actSet(0, 0, 100, 9, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        s12.actSet(4, 0, 100, 5, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        out.add(s12.build());

        // 13. comparator_subtract: Comparator in subtract mode. Rear signal 13, side signal 5 at tick 0. Side increased to 9 at tick 4.
        // Choice: comparator at (0,100,0) facing WEST (SUBTRACT), rear redstone block at (-4,100,0) (power(x) with
        // 3 wire cells away -> 16-3=13). Side chain z=1..12, redstone block at (0,100,12) (power(1) = 17-12 = 5)
        // then moved to (0,100,8) at tick 4 (power(1) = 17-8 = 9).
        B s13 = new B("comparator_subtract", 10)
                .floor(99, -4, 1, 0, 12, STONE)
                .set(-4, 100, 0, Blocks.REDSTONE_BLOCK.defaultBlockState())
                .set(0, 100, 0, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST).setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT))
                .set(1, 100, 0, REDSTONE_WIRE);
        for (int x = -3; x <= -1; x++) {
            s13.set(x, 100, 0, REDSTONE_WIRE);
        }
        for (int z = 1; z <= 12; z++) {
            s13.set(0, 100, z, REDSTONE_WIRE);
        }
        s13.actSet(0, 0, 100, 12, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        s13.actSet(4, 0, 100, 8, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        out.add(s13.build());

        // 14. comparator_container: Barrel behind comparator filled with 1 stack (signal 1) and 9 stacks (signal 5).
        // Choice: barrel at (-1,100,0), comparator at (0,100,0) facing WEST, 1 stack of 64 stone at t=0 (signal 1), slots 1..8 filled with 64 stone at t=2 (signal 5).
        B s14 = new B("comparator_container", 6)
                .floor(99, -1, 1, 0, 0, STONE)
                .set(0, 100, 0, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST))
                .set(1, 100, 0, REDSTONE_WIRE)
                .set(-1, 100, 0, Blocks.BARREL.defaultBlockState())
                .actItem(0, -1, 100, 0, 0, new ItemStack(Items.STONE, 64));
        for (int slot = 1; slot <= 8; slot++) {
            s14.actItem(2, -1, 100, 0, slot, new ItemStack(Items.STONE, 64));
        }
        out.add(s14.build());

        // 15. comparator_through_block: Barrel separated from comparator by a solid stone conductor block.
        // Choice: barrel at (-2,100,0), solid stone at (-1,100,0), comparator at (0,100,0) facing WEST. Barrel populated with 9 stacks of 64 stone at t=0.
        B s15 = new B("comparator_through_block", 6)
                .floor(99, -2, 1, 0, 0, STONE)
                .set(-2, 100, 0, Blocks.BARREL.defaultBlockState())
                .set(-1, 100, 0, STONE)
                .set(0, 100, 0, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST))
                .set(1, 100, 0, REDSTONE_WIRE);
        for (int slot = 0; slot <= 8; slot++) {
            s15.actItem(0, -2, 100, 0, slot, new ItemStack(Items.STONE, 64));
        }
        out.add(s15.build());

        // 16. observer_single_pulse: Observer facing an air block. Stone placed in front at tick 0.
        // Choice: observer at (0,100,0) facing WEST, stone placed at (-1,100,0) at tick 0.
        out.add(new B("observer_single_pulse", 8)
                .floor(99, -1, 1, 0, 0, STONE)
                .set(0, 100, 0, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST))
                .set(1, 100, 0, REDSTONE_WIRE)
                .actSet(0, -1, 100, 0, STONE, 3)
                .build());

        // 17. observer_clock: Two observers facing directly into each other. Placed at tick 0.
        // Choice: Observer 1 at (0,100,0) facing EAST, Observer 2 at (1,100,0) facing WEST placed at tick 0.
        out.add(new B("observer_clock", 20)
                .floor(99, -1, 2, 0, 0, STONE)
                .set(-1, 100, 0, REDSTONE_WIRE)
                .set(0, 100, 0, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST))
                .set(2, 100, 0, REDSTONE_WIRE)
                .actSet(0, 1, 100, 0, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST), 3)
                .build());

        // 18. lever_power_types: Lever on stone block surrounded by wire on floor and wire on stone side.
        // Choice: lever at (-1,100,0) attached to stone at (0,100,0). Strong power wire at (1,100,0), weak power wire at (-1,100,1).
        out.add(new B("lever_power_types", 4)
                .set(0, 99, 0, STONE).set(0, 100, 0, STONE)
                .set(1, 99, 0, STONE).set(1, 100, 0, REDSTONE_WIRE)
                .set(-1, 99, 1, STONE).set(-1, 100, 1, REDSTONE_WIRE)
                .set(-2, 99, 0, STONE).set(-2, 100, 0, REDSTONE_WIRE)
                .set(-1, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.WEST))
                .actUse(0, -1, 100, 0)
                .build());

        // 19. button_stone_duration: Stone button pressed at tick 0. Output wire observed.
        // Choice: stone button at (-1,100,0) on stone at (0,100,0), wire at (1,100,0). 20-tick press duration.
        out.add(new B("button_stone_duration", 25)
                .set(0, 99, 0, STONE).set(0, 100, 0, STONE)
                .set(1, 99, 0, STONE).set(1, 100, 0, REDSTONE_WIRE)
                .set(-1, 100, 0, Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.WEST))
                .actUse(0, -1, 100, 0)
                .build());

        // 20. button_wood_duration: Oak button pressed at tick 0. Output wire observed.
        // Choice: oak button at (-1,100,0) on stone at (0,100,0), wire at (1,100,0). 30-tick press duration.
        out.add(new B("button_wood_duration", 35)
                .set(0, 99, 0, STONE).set(0, 100, 0, STONE)
                .set(1, 99, 0, STONE).set(1, 100, 0, REDSTONE_WIRE)
                .set(-1, 100, 0, Blocks.OAK_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.WEST))
                .actUse(0, -1, 100, 0)
                .build());

        // 21. lamp_timings: Redstone lamp powered at tick 0, depowered at tick 4.
        // Choice: redstone lamp at (0,100,0), lever at (-1,100,0) turned on at tick 0, turned off at tick 4.
        out.add(new B("lamp_timings", 12)
                .set(0, 99, 0, STONE).set(0, 100, 0, Blocks.REDSTONE_LAMP.defaultBlockState())
                .set(-1, 99, 0, STONE).set(-1, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .actUse(0, -1, 100, 0)
                .actUse(4, -1, 100, 0)
                .build());

        // 22. piston_qc_bud: Piston facing up. Redstone block placed at pos.above().north() at tick 0. Neighbor updated at tick 4.
        // Choice: upward piston at (0,100,0), redstone block placed at (0,101,-1) at tick 0, neighbor update at (1,100,0) at tick 4.
        out.add(new B("piston_qc_bud", 10)
                .set(0, 99, 0, STONE).set(0, 100, 0, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP))
                .actSet(0, 0, 101, -1, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3)
                .actSet(4, 1, 100, 0, STONE, 3)
                .build());

        // 23. piston_push_limit_12: Regular piston pushing 12 stone blocks vs 13 stone blocks.
        // Choice: Piston A at (0,100,0) pushing 12 stone blocks (1..12), Piston B at (0,100,3) with 13 stone blocks (1..13).
        B s23 = new B("piston_push_limit_12", 10)
                .floor(99, -1, 14, 0, 3, STONE)
                .set(-1, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .set(0, 100, 0, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST))
                .set(-1, 100, 3, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .set(0, 100, 3, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        for (int x = 1; x <= 12; x++) {
            s23.set(x, 100, 0, STONE);
        }
        for (int x = 1; x <= 13; x++) {
            s23.set(x, 100, 3, STONE);
        }
        s23.actUse(0, -1, 100, 0);
        s23.actUse(0, -1, 100, 3);
        out.add(s23.build());

        // 24. piston_slime_honey_split: Sticky piston pushing alternating slime and honey columns.
        // Choice: sticky piston at (0,100,0) pushes slime at (1,100,0) and stone at (2,100,0), adjacent honey at (1,100,1) remains stationary.
        out.add(new B("piston_slime_honey_split", 10)
                .floor(99, -1, 3, 0, 1, STONE)
                .set(-1, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .set(0, 100, 0, Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST))
                .set(1, 100, 0, Blocks.SLIME_BLOCK.defaultBlockState())
                .set(2, 100, 0, STONE)
                .set(1, 100, 1, Blocks.HONEY_BLOCK.defaultBlockState())
                .set(0, 100, 1, STONE)
                .set(2, 100, 1, STONE)
                .actUse(0, -1, 100, 0)
                .build());

        // 25. piston_block_drop: Sticky piston holding stone block receives 2-tick pulse (extending into immediate retract).
        // Choice: sticky piston at (0,100,0) with stone at (1,100,0), lever toggled on at tick 0 and off at tick 2.
        out.add(new B("piston_block_drop", 10)
                .floor(99, -1, 3, 0, 0, STONE)
                .set(-1, 100, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .set(0, 100, 0, Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST))
                .set(1, 100, 0, STONE)
                .actUse(0, -1, 100, 0)
                .actUse(2, -1, 100, 0)
                .build());

        // 26. piston_block_events_fifo: Two pistons triggered on the exact same tick.
        // Choice: Piston A at (0,100,0) and Piston B at (0,100,2) powered simultaneously through solid backing blocks at (-1,100,0) and (-1,100,2) with wire on top.
        out.add(new B("piston_block_events_fifo", 8)
                .floor(99, -2, 2, 0, 2, STONE)
                .set(-1, 100, 0, STONE).set(-1, 101, 0, REDSTONE_WIRE)
                .set(-1, 100, 1, STONE).set(-1, 101, 1, REDSTONE_WIRE)
                .set(-1, 100, 2, STONE).set(-1, 101, 2, REDSTONE_WIRE)
                .set(-2, 100, 1, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.WEST))
                .set(0, 100, 0, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST))
                .set(1, 100, 0, STONE)
                .set(0, 100, 2, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST))
                .set(1, 100, 2, STONE)
                .actUse(0, -2, 100, 1)
                .build());

        // 27. update_order_tie: Two repeaters pointing toward the same wire line, powered simultaneously.
        // Choice: Repeater A at (-1,100,0) facing EAST and Repeater B at (0,100,1) facing NORTH pointing into wire at (0,100,0).
        out.add(new B("update_order_tie", 6)
                .floor(99, -2, 2, -1, 2, STONE)
                .set(-2, 100, 2, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.EAST))
                .set(-2, 100, 1, REDSTONE_WIRE)
                .set(-2, 100, 0, REDSTONE_WIRE)
                .set(-1, 100, 2, REDSTONE_WIRE)
                .set(0, 100, 2, REDSTONE_WIRE)
                .set(-1, 100, 0, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST))
                .set(0, 100, 1, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.SOUTH))
                .set(0, 100, 0, REDSTONE_WIRE)
                .set(1, 100, 0, REDSTONE_WIRE)
                .set(0, 100, -1, REDSTONE_WIRE)
                .actUse(0, -2, 100, 2)
                .build());

        // 28. piston_block_events_contention: Two pistons driven on the same tick whose
        // extensions race for one destination cell. Both structures resolve when their
        // events are enqueued, but at drain time only the piston whose event runs first can
        // move its block: the second resolver finds the winner's MOVING_PISTON sitting in the
        // destination and fails. Which piston ends up extended is therefore a function of the
        // drain order alone, which piston_block_events_fifo (two independent pistons that
        // extend identically in either order) does not pin.
        // Choice: piston A at (0,100,0) facing EAST pushing stone (1,100,0) into (2,100,0),
        // piston B at (4,100,0) facing WEST pushing stone (3,100,0) into the same (2,100,0);
        // both powered at tick 0 by a redstone block placed behind them, A's first, so the
        // enqueue order is A then B.
        out.add(new B("piston_block_events_contention", 10)
                .floor(99, -1, 5, 0, 0, STONE)
                .set(0, 100, 0, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST))
                .set(1, 100, 0, STONE)
                .set(4, 100, 0, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST))
                .set(3, 100, 0, STONE)
                .actSet(0, -1, 100, 0, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3)
                .actSet(0, 5, 100, 0, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3)
                .build());

        // 29. piston_settle_power_cut: A sticky piston is mid-extension when the block-entity
        // pass of its third tick settles a second, earlier-started piston's placeholder onto
        // the wire that feeds it. The landed stone occludes that wire's climb, so the power
        // drop reaches the still-in-flight piston from inside the block-entity pass, where
        // vanilla has already cleared ServerLevel.handlingTick (ServerLevel.java:408, after
        // runBlockEvents and before tickBlockEntities). PistonBaseBlock.checkIfExtend then
        // finds progress 0.5 (not below 0.5) and lastTicked one gameTime behind, so the third
        // disjunct decides: with handlingTick false the retraction is TRIGGER_CONTRACT (1),
        // with it still true TRIGGER_DROP (2). Contract pulls the held stone back one cell;
        // drop removes the head in front and leaves the stone out there.
        // Choice, all in one chunk: sticky piston Q at (4,100,4) facing EAST onto stone
        // (5,100,4); its stone backing (3,100,4) carries wire (3,101,4), which climbs over the
        // step stone (2,101,4) to the upper wire (2,102,4); piston X at (3,102,2) facing SOUTH
        // pushes the stone (3,102,3) into (3,102,4), the cell directly above that wire.
        // X is powered first at tick 0 so its placeholders are created (and thus ticked, and
        // settled) before Q's.
        out.add(new B("piston_settle_power_cut", 10)
                .floor(99, 0, 6, 0, 6, STONE)
                .set(4, 100, 4, Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST))
                .set(5, 100, 4, STONE)
                .set(3, 100, 4, STONE)
                .set(3, 101, 4, REDSTONE_WIRE)
                .set(2, 101, 4, STONE)
                .set(2, 102, 4, REDSTONE_WIRE)
                .set(3, 102, 2, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.SOUTH))
                .set(3, 102, 3, STONE)
                .actSet(0, 3, 102, 1, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3)
                .actSet(0, 1, 102, 4, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3)
                .build());

        return out;
    }

    public static boolean inBox(BlockPos p) {
        return p.getX() >= MIN_X && p.getX() <= MAX_X
                && p.getY() >= MIN_Y && p.getY() <= MAX_Y
                && p.getZ() >= MIN_Z && p.getZ() <= MAX_Z;
    }

    public static String blockTickLine(BlockPos pos, Block block, long relTrigger, int priority, long subTickOrder) {
        String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + id
                + " " + relTrigger + " " + priority + " " + subTickOrder;
    }

    public static String fluidTickLine(BlockPos pos, Fluid fluid, long relTrigger, int priority, long subTickOrder) {
        String id = BuiltInRegistries.FLUID.getKey(fluid).getPath();
        return pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + id
                + " " + relTrigger + " " + priority + " " + subTickOrder;
    }

    public static String cellLine(int x, int y, int z, BlockState state) {
        return x + " " + y + " " + z + " " + state;
    }

    public interface CellReader {
        BlockState get(int x, int y, int z);
    }

    public static final class Recorder {
        private final List<String> lines = new ArrayList<>();
        private final Map<Long, BlockState> last = new HashMap<>();
        private List<String> lastQueue = null;

        public Recorder(Scenario s) {
            lines.add("# kestrel redstone oracle v2 scenario=" + s.name() + " ticks=" + s.ticks());
        }

        /**
         * Records the settled post-setup state as a vanilla-reachable snapshot:
         * an "S" line, then every non-air cell in the box, then any BE lines.
         * Seeds the delta baseline so the following {@code record(0, ...)}
         * reports T 0 relative to this snapshot instead of relative to air.
         */
        public void recordSettled(Map<Long, BlockState> snapshot, List<String> beLines) {
            lines.add("S");
            for (int x = MIN_X; x <= MAX_X; x++) {
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    for (int z = MIN_Z; z <= MAX_Z; z++) {
                        long key = BlockPos.asLong(x, y, z);
                        BlockState st = snapshot.get(key);
                        if (st != null) {
                            lines.add("C " + cellLine(x, y, z, st));
                            last.put(key, st);
                        }
                    }
                }
            }
            for (String be : beLines) {
                lines.add(be);
            }
        }

        public void record(int tick, CellReader reader, List<String> queue, List<String> events) {
            lines.add("T " + tick);
            if (events != null) {
                for (String ev : events) {
                    lines.add(ev);
                }
            }
            for (int x = MIN_X; x <= MAX_X; x++) {
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    for (int z = MIN_Z; z <= MAX_Z; z++) {
                        BlockState st = reader.get(x, y, z);
                        long key = BlockPos.asLong(x, y, z);
                        BlockState prev = last.get(key);
                        if (prev == null) {
                            prev = AIR;
                        }
                        if (st != prev) {
                            lines.add("C " + cellLine(x, y, z, st));
                            if (st.isAir()) {
                                last.remove(key);
                            } else {
                                last.put(key, st);
                            }
                        }
                    }
                }
            }
            if (!queue.equals(lastQueue)) {
                lines.add("Q " + queue.size());
                for (String q : queue) {
                    lines.add("q " + q);
                }
                lastQueue = List.copyOf(queue);
            }
        }

        public List<String> lines() {
            return lines;
        }
    }
}
