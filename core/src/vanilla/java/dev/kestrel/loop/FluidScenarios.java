package dev.kestrel.loop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/**
 * G11 fluid parity corpus: the scenarios both sides run, the vanilla oracle
 * ({@link FluidOracle}, a real 26.2 dedicated server stepped with the tick
 * freeze) and the Kestrel replay ({@link FluidParityCheck}).
 *
 * <p>Every scenario lives in one box around the origin at y=100. Setup cells
 * are written raw (vanilla flags {@code 2|16|512}: no neighbor updates, no
 * shape updates, no onPlace), so nothing is scheduled by the setup itself.
 * Actions are real writes with their own flags ({@code 3} unless stated),
 * applied between ticks: an action with {@code tick = k} runs after k steps.
 * The box is cleared to air (and its pending ticks dropped) before setup, so
 * the only world either side sees is the one defined here.
 *
 * <p>Snapshot format (shared so both sides format identically): cells by
 * {@code BlockState.toString()}, fluid ticks as
 * {@code x y z fluid relTrigger priority} in vanilla drain order, where
 * relTrigger is the absolute trigger tick minus the game time at which the
 * tick-0 actions ran. An {@code R} line after a tick header lists the
 * {@code nextInt(4)} draws lava's {@code getSpreadDelay} took from the level
 * random during that tick (the only randomness fluid flow consumes); the
 * Kestrel replay feeds the same draws back so the 4x lava delay is compared
 * deterministically.
 */
public final class FluidScenarios {
    private FluidScenarios() {
    }

    public static final int MIN_X = -14;
    public static final int MAX_X = 14;
    public static final int MIN_Y = 94;
    public static final int MAX_Y = 114;
    public static final int MIN_Z = -14;
    public static final int MAX_Z = 14;

    /** Raw setup flags: clients only, known shape, skip onPlace. */
    public static final int RAW_FLAGS = 2 | 16 | 512;

    public record Cell(int x, int y, int z, BlockState state) {
        BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public record Action(int tick, int x, int y, int z, BlockState state, int flags) {
        BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public record Scenario(String name, boolean nether, int ticks, List<Cell> setup, List<Action> actions) {
    }

    static final BlockState AIR = Blocks.AIR.defaultBlockState();
    static final BlockState STONE = Blocks.STONE.defaultBlockState();
    static final BlockState WATER = Blocks.WATER.defaultBlockState();
    static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    /** Small builder so the scenario list reads like the research table. */
    static final class B {
        final String name;
        final boolean nether;
        final int ticks;
        final List<Cell> setup = new ArrayList<>();
        final List<Action> actions = new ArrayList<>();

        B(String name, boolean nether, int ticks) {
            this.name = name;
            this.nether = nether;
            this.ticks = ticks;
        }

        B floor(int y, int radius, BlockState state) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    setup.add(new Cell(x, y, z, state));
                }
            }
            return this;
        }

        /** Basin: floor at y plus a one-high rim at y+1 around it. */
        B basin(int y, int radius) {
            floor(y, radius, STONE);
            for (int i = -radius - 1; i <= radius + 1; i++) {
                setup.add(new Cell(i, y + 1, -radius - 1, STONE));
                setup.add(new Cell(i, y + 1, radius + 1, STONE));
                setup.add(new Cell(-radius - 1, y + 1, i, STONE));
                setup.add(new Cell(radius + 1, y + 1, i, STONE));
            }
            return this;
        }

        B set(int x, int y, int z, BlockState state) {
            setup.add(new Cell(x, y, z, state));
            return this;
        }

        B act(int tick, int x, int y, int z, BlockState state) {
            actions.add(new Action(tick, x, y, z, state, 3));
            return this;
        }

        Scenario build() {
            return new Scenario(name, nether, ticks, List.copyOf(setup), List.copyOf(actions));
        }
    }

    public static List<Scenario> all() {
        List<Scenario> out = new ArrayList<>();

        // 1-3: single sources on a flat floor (drop-off, reach, tick delay).
        out.add(new B("water_flat", false, 50).basin(99, 10).act(0, 0, 100, 0, WATER).build());
        out.add(new B("lava_flat", false, 150).basin(99, 10).act(0, 0, 100, 0, LAVA).build());
        out.add(new B("lava_flat_nether", true, 100).basin(99, 10).act(0, 0, 100, 0, LAVA).build());

        // 4-7: slope finding toward holes (a hole drains into a closed pit).
        out.add(new B("water_hole_3", false, 60).basin(99, 10)
                .set(3, 99, 0, AIR).set(3, 97, 0, STONE)
                .set(2, 98, 0, STONE).set(4, 98, 0, STONE).set(3, 98, -1, STONE).set(3, 98, 1, STONE)
                .act(0, 0, 100, 0, WATER).build());
        out.add(new B("water_hole_tie", false, 60).basin(99, 10)
                .set(2, 99, 0, AIR).set(2, 97, 0, STONE)
                .set(1, 98, 0, STONE).set(3, 98, 0, STONE).set(2, 98, -1, STONE).set(2, 98, 1, STONE)
                .set(-2, 99, 0, AIR).set(-2, 97, 0, STONE)
                .set(-1, 98, 0, STONE).set(-3, 98, 0, STONE).set(-2, 98, -1, STONE).set(-2, 98, 1, STONE)
                .act(0, 0, 100, 0, WATER).build());
        out.add(new B("water_hole_5", false, 70).basin(99, 10)
                .set(5, 99, 0, AIR).set(5, 97, 0, STONE)
                .set(4, 98, 0, STONE).set(6, 98, 0, STONE).set(5, 98, -1, STONE).set(5, 98, 1, STONE)
                .act(0, 0, 100, 0, WATER).build());
        out.add(new B("water_hole_6", false, 70).basin(99, 10)
                .set(6, 99, 0, AIR).set(6, 97, 0, STONE)
                .set(5, 98, 0, STONE).set(7, 98, 0, STONE).set(6, 98, -1, STONE).set(6, 98, 1, STONE)
                .act(0, 0, 100, 0, WATER).build());

        // 8: vertical fall then impact spread.
        out.add(new B("water_fall", false, 110).basin(99, 10)
                .act(0, 0, 110, 0, WATER).build());

        // 9-10: infinite source (needs a solid floor) and its floating failure.
        out.add(new B("water_infinite", false, 40).basin(99, 10)
                .act(0, 0, 100, 0, WATER).act(0, 2, 100, 0, WATER).build());
        out.add(new B("water_infinite_diag", false, 40).basin(99, 10)
                .act(0, 0, 100, 0, WATER).act(0, 1, 100, 1, WATER).build());
        out.add(new B("water_infinite_floating", false, 60).basin(99, 10)
                .set(1, 99, 0, AIR).set(1, 97, 0, STONE)
                .set(0, 98, 0, STONE).set(2, 98, 0, STONE).set(1, 98, -1, STONE).set(1, 98, 1, STONE)
                .act(0, 0, 100, 0, WATER).act(0, 2, 100, 0, WATER).build());

        // 11-15: lava/water conversions.
        out.add(new B("lava_onto_water_source", false, 80).basin(99, 10)
                .set(0, 100, 0, WATER)
                .act(0, 0, 103, 0, LAVA).build());
        out.add(new B("water_meets_lava_source", false, 60).basin(99, 10)
                .set(3, 100, 0, LAVA)
                .act(0, 0, 100, 0, WATER).build());
        out.add(new B("water_meets_flowing_lava", false, 160).basin(99, 10)
                .act(0, 0, 100, 0, WATER).act(0, 7, 100, 0, LAVA).build());
        // A second source dropped into flowing lava raises its neighbours:
        // exercises lava's random 4x spread delay (R lines in the fixture).
        out.add(new B("lava_raise", false, 260).basin(99, 10)
                .act(0, 0, 100, 0, LAVA).act(100, 2, 100, 0, LAVA).act(100, 0, 100, 2, LAVA).build());
        out.add(new B("basalt", false, 20).basin(99, 10)
                .set(0, 99, 0, Blocks.SOUL_SOIL.defaultBlockState())
                .set(1, 100, 0, Blocks.BLUE_ICE.defaultBlockState())
                .act(0, 0, 100, 0, LAVA).build());

        // 16-17: waterlogging.
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        out.add(new B("flow_into_slab", false, 60).basin(99, 10)
                .set(2, 100, 0, slab)
                .act(0, 0, 100, 0, WATER).build());
        out.add(new B("waterlogged_slab_wakes", false, 60).basin(99, 10)
                .set(0, 100, 0, slab.setValue(BlockStateProperties.WATERLOGGED, true))
                .act(0, 0, 100, 1, STONE).build());

        // 18-19: fluids destroying blocks.
        out.add(new B("water_washes_torch", false, 40).basin(99, 10)
                .set(2, 100, 0, Blocks.TORCH.defaultBlockState())
                .set(-2, 100, 0, Blocks.REDSTONE_WIRE.defaultBlockState())
                .act(0, 0, 100, 0, WATER).build());
        out.add(new B("lava_burns_torch", false, 80).basin(99, 10)
                .set(1, 100, 0, Blocks.TORCH.defaultBlockState())
                .act(0, 0, 100, 0, LAVA).build());

        // 20-21: recession after the source goes, and a wall dropped into a flow.
        out.add(new B("water_recede", false, 100).basin(99, 10)
                .act(0, 0, 100, 0, WATER).act(40, 0, 100, 0, AIR).build());
        out.add(new B("water_wall_cut", false, 100).basin(99, 10)
                .act(0, 0, 100, 0, WATER).act(40, 2, 100, 0, STONE).build());

        // 22: a block broken next to still water (the dig path).
        out.add(new B("dig_next_to_water", false, 60).basin(99, 10)
                .set(0, 100, 0, WATER).set(1, 100, 0, STONE)
                .act(0, 1, 100, 0, AIR).build());

        // 23: a lit campfire and a lit candle each flanked by 2 water
        // sources douse. LiquidBlockContainer.canPlaceLiquid only accepts
        // the literal source fluid (Fluids.WATER, not flowing_water), so a
        // single adjacent source only ever offers flowing water and is
        // rejected; 2 source neighbors over a solid floor make
        // getNewLiquid compute a genuine new source there instead (the
        // same neighbourSources >= 2 rule that grows infinite water),
        // which does get placed.
        out.add(new B("campfire_doused", false, 10).basin(99, 10)
                .set(0, 100, 0, Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT, true))
                .set(5, 100, 0, Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.LIT, true))
                .act(0, -1, 100, 0, WATER).act(0, 0, 100, -1, WATER)
                .act(0, 4, 100, 0, WATER).act(0, 5, 100, -1, WATER).build());

        // 24: washing away a comparator fires DiodeBlock.updateNeighborsInFront
        // just as a repeater does; the resulting front-neighbor updates reach
        // a water source two cells away that only that fan-out can wake.
        BlockState comparator = Blocks.COMPARATOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        out.add(new B("comparator_washed", false, 20).basin(99, 10)
                .set(0, 100, 0, comparator)
                .set(1, 100, 1, WATER)
                .act(0, -1, 100, 0, WATER).build());
        return out;
    }

    // ---- shared snapshot formatting ----

    static String fluidId(Fluid fluid) {
        if (fluid == Fluids.WATER) {
            return "water";
        }
        if (fluid == Fluids.FLOWING_WATER) {
            return "flowing_water";
        }
        if (fluid == Fluids.LAVA) {
            return "lava";
        }
        if (fluid == Fluids.FLOWING_LAVA) {
            return "flowing_lava";
        }
        return "other:" + fluid;
    }

    static boolean inBox(BlockPos p) {
        return p.getX() >= MIN_X && p.getX() <= MAX_X
                && p.getY() >= MIN_Y && p.getY() <= MAX_Y
                && p.getZ() >= MIN_Z && p.getZ() <= MAX_Z;
    }

    /** One queued fluid tick, in the shared line format. */
    static String tickLine(BlockPos pos, Fluid fluid, long relTrigger, int priority) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + fluidId(fluid)
                + " " + relTrigger + " " + priority;
    }

    /** One cell, in the shared line format. */
    static String cellLine(int x, int y, int z, BlockState state) {
        return x + " " + y + " " + z + " " + state;
    }

    /** Reads one cell of the box (the side's own world). */
    interface CellReader {
        BlockState get(int x, int y, int z);
    }

    /**
     * Delta recorder, identical on both sides: tick 0 writes every non-air
     * cell, later ticks write only cells whose state changed; the queue block
     * is written when its content differs from the previous tick's.
     */
    static final class Recorder {
        private final List<String> lines = new ArrayList<>();
        private final java.util.Map<Long, BlockState> last = new java.util.HashMap<>();
        private List<String> lastQueue = null;

        Recorder(Scenario s) {
            lines.add("# kestrel fluid oracle v1 scenario=" + s.name() + " dim="
                    + (s.nether() ? "nether" : "overworld") + " ticks=" + s.ticks());
        }

        void record(int tick, CellReader reader, List<String> queue, List<Integer> lavaDraws) {
            lines.add("T " + tick);
            if (!lavaDraws.isEmpty()) {
                StringBuilder sb = new StringBuilder("R");
                for (int d : lavaDraws) {
                    sb.append(' ').append(d);
                }
                lines.add(sb.toString());
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

        List<String> lines() {
            return lines;
        }
    }
}
