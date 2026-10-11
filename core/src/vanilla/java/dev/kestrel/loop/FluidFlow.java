package dev.kestrel.loop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.TickPriority;

import it.unimi.dsi.fastutil.shorts.Short2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;

import java.util.EnumMap;
import java.util.Map;

/**
 * G11 water and lava: a transcription of vanilla 26.2 fluid flow over the
 * TickLoop store. Sources (Vineflower decompile of the 26.2 server jar):
 * {@code FlowingFluid} (tick, spread, spreadToSides, getNewLiquid,
 * canPassThroughWall, getSlopeDistance, SpreadContext, isWaterHole,
 * canMaybePassThrough, canHoldAnyFluid, canHoldSpecificFluid,
 * sourceNeighborCount, getSpread, spreadTo), {@code WaterFluid},
 * {@code LavaFluid} (fast lava, random 4x spread delay, spreadTo DOWN into
 * water, canBeReplacedWith), {@code LiquidBlock} (onPlace, neighborChanged,
 * updateShape, shouldSpreadLiquid with the obsidian/cobblestone/basalt
 * conversions), {@code SimpleWaterloggedBlock.placeLiquid}, and the
 * fluid callbacks reached from the live level's collecting neighbour updater.
 *
 * <p>Tag-free equivalents (the live server binds no tags):
 * {@code FluidTags.WATER} is {@code water|flowing_water},
 * {@code FluidTags.LAVA} is {@code lava|flowing_lava},
 * {@code BlockTags.SIGNS} is {@code StandingSignBlock|WallSignBlock}.
 *
 * <p>Scope and documented gaps:
 * <ul>
 *   <li>Only fluid-relevant update targets are modeled. A flag-1 neighbor
 *       update reaches {@code LiquidBlock.neighborChanged}; a shape update
 *       reaches {@code LiquidBlock.updateShape} (transcribed) and, for other
 *       fluid-bearing states (waterlogged blocks, kelp, seagrass), the
 *       vanilla {@code updateShape} with a capturing tick sink whose fluid
 *       schedules are kept and whose returned state is discarded (Kestrel
 *       does not model neighbor shape changes of non-fluid blocks, same as
 *       before G11). Redstone keeps its own dispatch model.</li>
 *   <li>Drops ({@code WaterFluid.beforeDestroyingBlock}) and fizz level
 *       events (1501) are no-ops: Kestrel has no item entities and no
 *       level-event channel yet.</li>
 *   <li>Bubble columns: the 20-tick block tick a water source schedules
 *       over magma or soul sand ({@code tryScheduleBubbleBlockColumn}) is
 *       scheduled, so the pending list matches vanilla's, but running it
 *       (the column update) and the column block itself are not modeled.
 *       Chunk-activation post-processing places columns through vanilla's
 *       own {@code BubbleColumnBlock.updateColumn}, see {@link PostProcess}.</li>
 *   <li>Non-waterlogged {@code LiquidBlockContainer}s (kelp, seagrass)
 *       refuse every fluid in vanilla ({@code canPlaceLiquid} and
 *       {@code placeLiquid} return false); they are treated the same.</li>
 *   <li>The level random that lava's spread delay draws from is a seeded
 *       per-loop source ({@link #setRandom}); it is not persisted.</li>
 * </ul>
 */
final class FluidFlow {
    /** {@code Direction.Plane.HORIZONTAL} iteration order. */
    static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final TickLoop loop;
    private final TickLoop.LoopGetter getter;
    /** {@code EnvironmentAttributes.FAST_LAVA} for this dimension (the Nether). */
    private boolean fastLava;
    /** Gamerule {@code water_source_conversion} (vanilla default true). */
    private boolean waterSourceConversion = true;
    /** Gamerule {@code lava_source_conversion} (vanilla default false). */
    private boolean lavaSourceConversion = false;
    /** Stand-in for {@code Level.getRandom()} on lava's spread-delay draw. */
    private RandomSource random = RandomSource.create(0x6b657374L);
    FluidFlow(TickLoop loop, boolean fastLava) {
        this.loop = loop;
        this.getter = loop.loopGetter();
        this.fastLava = fastLava;
    }

    void setFastLava(boolean fastLava) {
        this.fastLava = fastLava;
    }

    void setSourceConversion(boolean water, boolean lava) {
        this.waterSourceConversion = water;
        this.lavaSourceConversion = lava;
    }

    void setRandom(RandomSource random) {
        this.random = random;
    }

    /** The {@code Level.getRandom()} stand-in, shared with {@link PostProcess}. */
    RandomSource random() {
        return random;
    }

    // ---- fluid kinds (WaterFluid / LavaFluid parameters) ----

    enum Kind {
        WATER, LAVA;

        Fluid source() {
            return this == WATER ? Fluids.WATER : Fluids.LAVA;
        }

        Fluid flowing() {
            return this == WATER ? Fluids.FLOWING_WATER : Fluids.FLOWING_LAVA;
        }

        /** {@code isSame}: source or flowing of this kind. */
        boolean isSame(Fluid f) {
            return this == WATER ? isWater(f) : isLava(f);
        }
    }

    static boolean isWater(Fluid f) {
        return f == Fluids.WATER || f == Fluids.FLOWING_WATER;
    }

    static boolean isLava(Fluid f) {
        return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
    }

    static Kind kindOf(Fluid f) {
        if (isWater(f)) {
            return Kind.WATER;
        }
        if (isLava(f)) {
            return Kind.LAVA;
        }
        return null;
    }

    int tickDelay(Kind k) {
        return k == Kind.WATER ? 5 : (fastLava ? 10 : 30);
    }

    private int dropOff(Kind k) {
        return k == Kind.WATER ? 1 : (fastLava ? 1 : 2);
    }

    private int slopeFindDistance(Kind k) {
        return k == Kind.WATER ? 4 : (fastLava ? 4 : 2);
    }

    private boolean canConvertToSource(Kind k) {
        return k == Kind.WATER ? waterSourceConversion : lavaSourceConversion;
    }

    private static FluidState flowingState(Kind k, int amount, boolean falling) {
        return k.flowing().defaultFluidState()
                .setValue(FlowingFluid.LEVEL, amount)
                .setValue(FlowingFluid.FALLING, falling);
    }

    private static FluidState sourceState(Kind k, boolean falling) {
        return k.source().defaultFluidState().setValue(FlowingFluid.FALLING, falling);
    }

    private BlockState state(BlockPos pos) {
        return loop.getBlockState(pos);
    }

    private void schedule(BlockPos pos, Fluid fluid, int delay) {
        loop.scheduleFluidTick(pos, fluid, delay, TickPriority.NORMAL);
    }

    // ---- ServerLevel.tickFluid + FlowingFluid.tick ----

    /** {@code ServerLevel.tickFluid}: run only if the fluid there is still this exact type. */
    void tickFluid(BlockPos pos, Fluid fluid) {
        BlockState blockState = state(pos);
        FluidState fluidState = blockState.getFluidState();
        if (fluidState.getType() != fluid) {
            return;
        }
        Kind k = kindOf(fluid);
        if (k == null) {
            return;
        }
        if (!fluidState.isSource()) {
            FluidState newFluidState = getNewLiquid(k, pos, state(pos));
            int tickDelay = spreadDelay(k, pos, fluidState, newFluidState);
            if (newFluidState.isEmpty()) {
                fluidState = newFluidState;
                blockState = Blocks.AIR.defaultBlockState();
                loop.setBlock(pos, blockState, 3);
            } else if (newFluidState != fluidState) {
                fluidState = newFluidState;
                blockState = newFluidState.createLegacyBlock();
                loop.setBlock(pos, blockState, 3);
                schedule(pos, newFluidState.getType(), tickDelay);
            }
        }
        spread(k, pos, blockState, fluidState);
    }

    /** {@code getSpreadDelay}: water plain; lava x4 on a random 3-in-4 when rising. */
    private int spreadDelay(Kind k, BlockPos pos, FluidState oldFluid, FluidState newFluid) {
        int result = tickDelay(k);
        if (k == Kind.LAVA
                && !oldFluid.isEmpty()
                && !newFluid.isEmpty()
                && !oldFluid.getValue(FlowingFluid.FALLING)
                && !newFluid.getValue(FlowingFluid.FALLING)
                && newFluid.getHeight(getter, pos) > oldFluid.getHeight(getter, pos)
                && random.nextInt(4) != 0) {
            result *= 4;
        }
        return result;
    }

    private void spread(Kind k, BlockPos pos, BlockState state, FluidState fluidState) {
        if (fluidState.isEmpty()) {
            return;
        }
        BlockPos belowPos = pos.below();
        BlockState belowState = state(belowPos);
        FluidState belowFluid = belowState.getFluidState();
        if (canMaybePassThrough(k, pos, state, Direction.DOWN, belowPos, belowState, belowFluid)) {
            FluidState newBelowFluid = getNewLiquid(k, belowPos, belowState);
            Fluid newBelowType = newBelowFluid.getType();
            if (canBeReplacedWith(belowFluid, belowPos, newBelowType, Direction.DOWN)
                    && canHoldSpecificFluid(belowPos, belowState, newBelowType)) {
                spreadTo(k, belowPos, belowState, Direction.DOWN, newBelowFluid);
                if (sourceNeighborCount(k, pos) >= 3) {
                    spreadToSides(k, pos, fluidState, state);
                }
                return;
            }
        }
        if (fluidState.isSource() || !isWaterHole(k, pos, state, belowPos, belowState)) {
            spreadToSides(k, pos, fluidState, state);
        }
    }

    private void spreadToSides(Kind k, BlockPos pos, FluidState fluidState, BlockState state) {
        int neighbor = fluidState.getAmount() - dropOff(k);
        if (fluidState.getValue(FlowingFluid.FALLING)) {
            neighbor = 7;
        }
        if (neighbor > 0) {
            Map<Direction, FluidState> spreads = getSpread(k, pos, state);
            for (Map.Entry<Direction, FluidState> entry : spreads.entrySet()) {
                Direction dir = entry.getKey();
                BlockPos neighborPos = pos.relative(dir);
                spreadTo(k, neighborPos, state(neighborPos), dir, entry.getValue());
            }
        }
    }

    private FluidState getNewLiquid(Kind k, BlockPos pos, BlockState state) {
        int highestNeighbor = 0;
        int neighbourSources = 0;
        for (Direction dir : HORIZONTAL) {
            BlockPos rel = pos.relative(dir);
            BlockState bs = state(rel);
            FluidState fs = bs.getFluidState();
            if (k.isSame(fs.getType()) && canPassThroughWall(dir, pos, state, rel, bs)) {
                if (fs.isSource()) {
                    neighbourSources++;
                }
                highestNeighbor = Math.max(highestNeighbor, fs.getAmount());
            }
        }
        if (neighbourSources >= 2 && canConvertToSource(k)) {
            BlockState belowState = state(pos.below());
            FluidState belowFluid = belowState.getFluidState();
            if (belowState.isSolid() || isSourceOf(k, belowFluid)) {
                return sourceState(k, false);
            }
        }
        BlockPos abovePos = pos.above();
        BlockState aboveState = state(abovePos);
        FluidState aboveFluid = aboveState.getFluidState();
        if (!aboveFluid.isEmpty() && k.isSame(aboveFluid.getType())
                && canPassThroughWall(Direction.UP, pos, state, abovePos, aboveState)) {
            return flowingState(k, 8, true);
        }
        int amount = highestNeighbor - dropOff(k);
        return amount <= 0 ? Fluids.EMPTY.defaultFluidState() : flowingState(k, amount, false);
    }

    /**
     * {@code canPassThroughWall}. The vanilla occlusion cache is a pure memo
     * of {@code Shapes.mergedFaceOccludes} and is not reproduced.
     */
    private boolean canPassThroughWall(Direction dir, BlockPos sourcePos, BlockState sourceState,
            BlockPos targetPos, BlockState targetState) {
        VoxelShape targetShape = targetState.getCollisionShape(getter, targetPos);
        if (targetShape == Shapes.block()) {
            return false;
        }
        VoxelShape sourceShape = sourceState.getCollisionShape(getter, sourcePos);
        if (sourceShape == Shapes.block()) {
            return false;
        }
        if (sourceShape == Shapes.empty() && targetShape == Shapes.empty()) {
            return true;
        }
        return !Shapes.mergedFaceOccludes(sourceShape, targetShape, dir);
    }

    /** {@code spreadTo}, with {@code LavaFluid}'s DOWN-into-water override. */
    private void spreadTo(Kind k, BlockPos pos, BlockState state, Direction dir, FluidState target) {
        if (k == Kind.LAVA && dir == Direction.DOWN) {
            FluidState there = state(pos).getFluidState();
            if (isWater(there.getType())) {
                if (state.getBlock() instanceof LiquidBlock) {
                    loop.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                }
                // fizz (level event 1501): no-op.
                return;
            }
        }
        if (state.getBlock() instanceof LiquidBlockContainer container) {
            placeLiquid(container, pos, state, target);
        } else {
            // beforeDestroyingBlock: water drops resources, lava fizzes; both no-ops here.
            loop.setBlock(pos, target.createLegacyBlock(), 3);
        }
    }

    /**
     * {@code SimpleWaterloggedBlock.placeLiquid}; other containers refuse.
     * {@code CampfireBlock.placeLiquid} and {@code CandleBlock.placeLiquid}
     * override this to also clear {@code LIT} (dowsing the fire and
     * extinguishing the candle); both are otherwise identical to the base
     * body, so a lit block is doused here whenever it has that property.
     */
    private void placeLiquid(LiquidBlockContainer container, BlockPos pos, BlockState state, FluidState fluid) {
        if (!(container instanceof SimpleWaterloggedBlock)) {
            return;
        }
        if (!state.getValue(BlockStateProperties.WATERLOGGED) && fluid.getType() == Fluids.WATER) {
            BlockState newState = state.setValue(BlockStateProperties.WATERLOGGED, true);
            if (newState.hasProperty(BlockStateProperties.LIT)) {
                newState = newState.setValue(BlockStateProperties.LIT, false);
            }
            loop.setBlock(pos, newState, 3);
            schedule(pos, fluid.getType(), tickDelay(Kind.WATER));
        }
    }

    private int getSlopeDistance(Kind k, BlockPos pos, int pass, Direction from, BlockState state,
            SpreadContext context) {
        int lowest = 1000;
        for (Direction dir : HORIZONTAL) {
            if (dir == from) {
                continue;
            }
            BlockPos testPos = pos.relative(dir);
            BlockState testState = context.getBlockState(testPos);
            FluidState testFluid = testState.getFluidState();
            if (canPassThrough(k, k.flowing(), pos, state, dir, testPos, testState, testFluid)) {
                if (context.isHole(testPos)) {
                    return pass;
                }
                if (pass < slopeFindDistance(k)) {
                    int v = getSlopeDistance(k, testPos, pass + 1, dir.getOpposite(), testState, context);
                    if (v < lowest) {
                        lowest = v;
                    }
                }
            }
        }
        return lowest;
    }

    private boolean isWaterHole(Kind k, BlockPos topPos, BlockState topState, BlockPos bottomPos,
            BlockState bottomState) {
        if (!canPassThroughWall(Direction.DOWN, topPos, topState, bottomPos, bottomState)) {
            return false;
        }
        return k.isSame(bottomState.getFluidState().getType())
                || canHoldFluid(bottomPos, bottomState, k.flowing());
    }

    private boolean canPassThrough(Kind k, Fluid fluid, BlockPos sourcePos, BlockState sourceState,
            Direction dir, BlockPos testPos, BlockState testState, FluidState testFluid) {
        return canMaybePassThrough(k, sourcePos, sourceState, dir, testPos, testState, testFluid)
                && canHoldSpecificFluid(testPos, testState, fluid);
    }

    private boolean canMaybePassThrough(Kind k, BlockPos sourcePos, BlockState sourceState, Direction dir,
            BlockPos testPos, BlockState testState, FluidState testFluid) {
        return !isSourceOf(k, testFluid)
                && canHoldAnyFluid(testState)
                && canPassThroughWall(dir, sourcePos, sourceState, testPos, testState);
    }

    private static boolean isSourceOf(Kind k, FluidState state) {
        return k.isSame(state.getType()) && state.isSource();
    }

    private int sourceNeighborCount(Kind k, BlockPos pos) {
        int n = 0;
        for (Direction dir : HORIZONTAL) {
            if (isSourceOf(k, state(pos.relative(dir)).getFluidState())) {
                n++;
            }
        }
        return n;
    }

    /**
     * {@code getSpread}: computed in HORIZONTAL order, returned as an
     * {@code EnumMap} so the caller iterates in ordinal order (N, S, W, E).
     */
    private Map<Direction, FluidState> getSpread(Kind k, BlockPos pos, BlockState state) {
        int lowest = 1000;
        Map<Direction, FluidState> result = new EnumMap<>(Direction.class);
        SpreadContext context = null;
        for (Direction dir : HORIZONTAL) {
            BlockPos testPos = pos.relative(dir);
            BlockState testState = state(testPos);
            FluidState testFluid = testState.getFluidState();
            if (canMaybePassThrough(k, pos, state, dir, testPos, testState, testFluid)) {
                FluidState newFluid = getNewLiquid(k, testPos, testState);
                if (canHoldSpecificFluid(testPos, testState, newFluid.getType())) {
                    if (context == null) {
                        context = new SpreadContext(k, pos);
                    }
                    int distance = context.isHole(testPos)
                            ? 0
                            : getSlopeDistance(k, testPos, 1, dir.getOpposite(), testState, context);
                    if (distance < lowest) {
                        result.clear();
                    }
                    if (distance <= lowest) {
                        if (canBeReplacedWith(testFluid, testPos, newFluid.getType(), dir)) {
                            result.put(dir, newFluid);
                        }
                        lowest = distance;
                    }
                }
            }
        }
        return result;
    }

    private static boolean canHoldAnyFluid(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof LiquidBlockContainer) {
            return true;
        }
        if (state.blocksMotion()) {
            return false;
        }
        return !(block instanceof DoorBlock)
                && !(block instanceof StandingSignBlock) && !(block instanceof WallSignBlock)
                && block != Blocks.LADDER
                && block != Blocks.SUGAR_CANE
                && block != Blocks.BUBBLE_COLUMN
                && block != Blocks.NETHER_PORTAL
                && block != Blocks.END_PORTAL
                && block != Blocks.END_GATEWAY
                && block != Blocks.STRUCTURE_VOID;
    }

    private boolean canHoldFluid(BlockPos pos, BlockState state, Fluid fluid) {
        return canHoldAnyFluid(state) && canHoldSpecificFluid(pos, state, fluid);
    }

    private boolean canHoldSpecificFluid(BlockPos pos, BlockState state, Fluid fluid) {
        return !(state.getBlock() instanceof LiquidBlockContainer container)
                || container.canPlaceLiquid(null, getter, pos, state, fluid);
    }

    /** {@code FluidState.canBeReplacedWith}, per fluid type (tag-free). */
    private boolean canBeReplacedWith(FluidState state, BlockPos pos, Fluid other, Direction dir) {
        Fluid type = state.getType();
        if (isWater(type)) {
            return dir == Direction.DOWN && !isWater(other);
        }
        if (isLava(type)) {
            return state.getHeight(getter, pos) >= 0.44444445F && isWater(other);
        }
        return true;
    }

    /** {@code FlowingFluid.SpreadContext}: per-spread state and hole memo keyed by (dx, dz). */
    private final class SpreadContext {
        private final Kind k;
        private final BlockPos origin;
        private final Short2ObjectOpenHashMap<BlockState> stateCache = new Short2ObjectOpenHashMap<>();
        private final Short2BooleanOpenHashMap holeCache = new Short2BooleanOpenHashMap();

        SpreadContext(Kind k, BlockPos origin) {
            this.k = k;
            this.origin = origin;
        }

        BlockState getBlockState(BlockPos pos) {
            return getBlockState(pos, key(pos));
        }

        private BlockState getBlockState(BlockPos pos, short key) {
            BlockState s = stateCache.get(key);
            if (s == null) {
                s = state(pos);
                stateCache.put(key, s);
            }
            return s;
        }

        boolean isHole(BlockPos pos) {
            short key = key(pos);
            if (holeCache.containsKey(key)) {
                return holeCache.get(key);
            }
            BlockState s = getBlockState(pos, key);
            BlockPos below = pos.below();
            boolean hole = isWaterHole(k, pos, s, below, state(below));
            holeCache.put(key, hole);
            return hole;
        }

        private short key(BlockPos pos) {
            int rx = pos.getX() - origin.getX();
            int rz = pos.getZ() - origin.getZ();
            return (short) ((rx + 128 & 0xFF) << 8 | rz + 128 & 0xFF);
        }
    }

    // ---- LiquidBlock ----

    private static Kind liquidKind(BlockState state) {
        return state.getBlock() == Blocks.LAVA ? Kind.LAVA : Kind.WATER;
    }

    /** {@code LiquidBlock.onPlace} / {@code neighborChanged}: same body. */
    private void liquidPlacedOrNeighborChanged(BlockPos pos, BlockState state) {
        if (shouldSpreadLiquid(pos, state)) {
            schedule(pos, state.getFluidState().getType(), tickDelay(liquidKind(state)));
        }
        if (shouldBubbleColumnOccupy(state)) {
            tryScheduleBubbleBlockColumn(pos, state, state(pos.below()));
        }
    }

    /**
     * {@code LiquidBlock.updateShape}: reschedule when either side is a
     * source, and the bubble-column block tick when the change is below.
     */
    private void liquidUpdateShape(BlockPos pos, BlockState state, Direction directionToNeighbour,
            BlockState neighbourState) {
        if (state.getFluidState().isSource() || neighbourState.getFluidState().isSource()) {
            schedule(pos, state.getFluidState().getType(), tickDelay(liquidKind(state)));
        }
        if (directionToNeighbour == Direction.DOWN && shouldBubbleColumnOccupy(state)) {
            tryScheduleBubbleBlockColumn(pos, state, neighbourState);
        }
    }

    /**
     * {@code LiquidBlock.shouldBubbleColumnOccupy}, tag-free: the fluid tag
     * {@code bubble_column_can_occupy} holds only {@code minecraft:water},
     * so a full water source.
     */
    private static boolean shouldBubbleColumnOccupy(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.getType() == Fluids.WATER && fluid.isSource() && fluid.isFull();
    }

    /**
     * {@code LiquidBlock.tryScheduleBubbleBlockColumn}, tag-free: the block
     * tags {@code enables_bubble_column_drag_down} and {@code _push_up} hold
     * only {@code magma_block} and {@code soul_sand}. The tick is scheduled
     * for the liquid block itself, 20 ticks out. Running it (the column
     * update in {@code LiquidBlock.tick}) is not modeled by the loop; the
     * schedule is, so the pending tick list matches vanilla's.
     */
    private void tryScheduleBubbleBlockColumn(BlockPos pos, BlockState state, BlockState stateBelow) {
        if (stateBelow.getBlock() == Blocks.MAGMA_BLOCK || stateBelow.getBlock() == Blocks.SOUL_SAND) {
            loop.scheduleTick(pos, state.getBlock(), 20, TickPriority.NORMAL);
        }
    }

    /** {@code LiquidBlock.shouldSpreadLiquid}: lava meets water or blue ice over soul soil. */
    private boolean shouldSpreadLiquid(BlockPos pos, BlockState state) {
        if (liquidKind(state) != Kind.LAVA) {
            return true;
        }
        boolean overSoulSoil = state(pos.below()).getBlock() == Blocks.SOUL_SOIL;
        for (Direction dir : LiquidBlock.POSSIBLE_FLOW_DIRECTIONS) {
            BlockPos neighbourPos = pos.relative(dir.getOpposite());
            if (isWater(state(neighbourPos).getFluidState().getType())) {
                Block convert = state(pos).getFluidState().isSource() ? Blocks.OBSIDIAN : Blocks.COBBLESTONE;
                loop.setBlock(pos, convert.defaultBlockState(), 3);
                return false;
            }
            if (overSoulSoil && state(neighbourPos).getBlock() == Blocks.BLUE_ICE) {
                loop.setBlock(pos, Blocks.BASALT.defaultBlockState(), 3);
                return false;
            }
        }
        return true;
    }

    /** LiquidBlock.onPlace through the live level's single update stack. */
    void onPlace(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) {
            liquidPlacedOrNeighborChanged(pos, state);
        }
    }

    /** LiquidBlock.neighborChanged through the live level's single update stack. */
    void neighborChanged(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) {
            liquidPlacedOrNeighborChanged(pos, state);
        }
    }

    /** LiquidBlock.updateShape without requiring a concrete vanilla Level. */
    void shapeChanged(BlockPos pos, BlockState state, Direction direction, BlockState neighborState) {
        liquidUpdateShape(pos, state, direction, neighborState);
    }

}
