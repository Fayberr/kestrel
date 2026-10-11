package dev.kestrel.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

import java.util.HashSet;
import java.util.Set;

/**
 * Transcription of the server-side half of
 * {@code net.minecraft.world.level.block.RedStoneWireBlock} together with
 * {@code DefaultRedstoneWireEvaluator} and its
 * {@code RedstoneWireEvaluator} base.
 *
 * <p>Vanilla splits these across three classes only because the evaluator is
 * swappable behind the redstone experiments feature flag. With the
 * experimental evaluator out of scope there is one code path, so it lives in
 * one place here; each method still names the vanilla method it transcribes.
 */
final class WireLogic {
    private WireLogic() {
    }

    /** {@code RedStoneWireBlock.crossState}: the default state with all four sides SIDE. */
    private static final BlockState CROSS_STATE = Blocks.REDSTONE_WIRE.defaultBlockState()
            .setValue(RedStoneWireBlock.NORTH, RedstoneSide.SIDE)
            .setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE)
            .setValue(RedStoneWireBlock.SOUTH, RedstoneSide.SIDE)
            .setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);

    // =====================================================================
    // DefaultRedstoneWireEvaluator
    // =====================================================================

    /**
     * {@code DefaultRedstoneWireEvaluator.updatePowerStrength}.
     *
     * <p>Two details that are easy to lose and both matter:
     * the {@code setBlock} is guarded on the world still holding the state
     * the caller was handed, but the seven neighbor updates fire on the
     * target-differs test alone; and the update targets go through a
     * {@code HashSet}, so they fire in {@link BlockPos} hash-bucket order,
     * not in any direction order. That set order is what produces vanilla's
     * locational update behaviour (MC-11193), so it is reproduced here by
     * building the same {@code HashSet} of the same seven positions rather
     * than by picking a fixed direction sequence.
     */
    static void updatePowerStrength(VanillaRedstone level, BlockPos pos, BlockState state) {
        int targetStrength = calculateTargetStrength(level, pos);
        if (state.getValue(RedStoneWireBlock.POWER) != targetStrength) {
            if (level.getBlockState(pos) == state) {
                if (!level.setBlock(pos, state.setValue(RedStoneWireBlock.POWER, targetStrength), 2)) {
                    // Kestrel BlockChange veto: the write stands, the
                    // cascade it would have caused does not.
                    return;
                }
            }
            Set<BlockPos> toUpdate = new HashSet<>();
            toUpdate.add(pos);
            for (Direction direction : Direction.values()) {
                toUpdate.add(pos.relative(direction));
            }
            for (BlockPos blockPos : toUpdate) {
                level.updateNeighborsAt(blockPos, Blocks.REDSTONE_WIRE);
            }
        }
    }

    /** {@code DefaultRedstoneWireEvaluator.calculateTargetStrength}. */
    private static int calculateTargetStrength(VanillaRedstone level, BlockPos pos) {
        int blockSignal = level.getBlockSignal(pos);
        return blockSignal == 15 ? blockSignal : Math.max(blockSignal, getIncomingWireSignal(level, pos));
    }

    /** {@code RedstoneWireEvaluator.getWireSignal}. */
    private static int getWireSignal(BlockState state) {
        return state.is(Blocks.REDSTONE_WIRE) ? state.getValue(RedStoneWireBlock.POWER) : 0;
    }

    /**
     * {@code RedstoneWireEvaluator.getIncomingWireSignal}: best of the four
     * horizontal neighbours, plus a wire one step up over a conductor
     * neighbour (only when this wire is not itself capped by a conductor) or
     * one step down under a non-conductor neighbour, minus one.
     */
    private static int getIncomingWireSignal(VanillaRedstone level, BlockPos pos) {
        int wireSignal = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighborState = level.getBlockState(neighborPos);
            wireSignal = Math.max(wireSignal, getWireSignal(neighborState));
            BlockPos abovePos = pos.above();
            if (neighborState.isRedstoneConductor(level, neighborPos)
                    && !level.getBlockState(abovePos).isRedstoneConductor(level, abovePos)) {
                BlockPos aboveNeighborPos = neighborPos.above();
                wireSignal = Math.max(wireSignal, getWireSignal(level.getBlockState(aboveNeighborPos)));
            } else if (!neighborState.isRedstoneConductor(level, neighborPos)) {
                BlockPos belowNeighborPos = neighborPos.below();
                wireSignal = Math.max(wireSignal, getWireSignal(level.getBlockState(belowNeighborPos)));
            }
        }
        return Math.max(0, wireSignal - 1);
    }

    // =====================================================================
    // RedStoneWireBlock: connection shape
    // =====================================================================

    /** {@code RedStoneWireBlock.isCross}. */
    private static boolean isCross(BlockState state) {
        return state.getValue(RedStoneWireBlock.NORTH).isConnected()
                && state.getValue(RedStoneWireBlock.SOUTH).isConnected()
                && state.getValue(RedStoneWireBlock.EAST).isConnected()
                && state.getValue(RedStoneWireBlock.WEST).isConnected();
    }

    /** {@code RedStoneWireBlock.isDot}. */
    private static boolean isDot(BlockState state) {
        return !state.getValue(RedStoneWireBlock.NORTH).isConnected()
                && !state.getValue(RedStoneWireBlock.SOUTH).isConnected()
                && !state.getValue(RedStoneWireBlock.EAST).isConnected()
                && !state.getValue(RedStoneWireBlock.WEST).isConnected();
    }

    /**
     * {@code RedStoneWireBlock.getConnectionState}: resolve every side, then
     * expand a wire that ended up with no connection on an axis into a
     * straight line across that axis. A wire that was a dot and still
     * resolves to a dot stays a dot.
     */
    static BlockState getConnectionState(BlockGetter level, BlockState state, BlockPos pos) {
        boolean wasDot = isDot(state);
        state = getMissingConnections(level,
                Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedStoneWireBlock.POWER,
                        state.getValue(RedStoneWireBlock.POWER)),
                pos);
        if (wasDot && isDot(state)) {
            return state;
        }
        boolean north = state.getValue(RedStoneWireBlock.NORTH).isConnected();
        boolean south = state.getValue(RedStoneWireBlock.SOUTH).isConnected();
        boolean east = state.getValue(RedStoneWireBlock.EAST).isConnected();
        boolean west = state.getValue(RedStoneWireBlock.WEST).isConnected();
        boolean northSouthEmpty = !north && !south;
        boolean eastWestEmpty = !east && !west;
        if (!west && northSouthEmpty) {
            state = state.setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
        }
        if (!east && northSouthEmpty) {
            state = state.setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE);
        }
        if (!north && eastWestEmpty) {
            state = state.setValue(RedStoneWireBlock.NORTH, RedstoneSide.SIDE);
        }
        if (!south && eastWestEmpty) {
            state = state.setValue(RedStoneWireBlock.SOUTH, RedstoneSide.SIDE);
        }
        return state;
    }

    /** {@code RedStoneWireBlock.getMissingConnections}. */
    private static BlockState getMissingConnections(BlockGetter level, BlockState state, BlockPos pos) {
        boolean canConnectUp = !level.getBlockState(pos.above()).isRedstoneConductor(level, pos);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!state.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction)).isConnected()) {
                RedstoneSide sideConnection = getConnectingSide(level, pos, direction, canConnectUp);
                state = state.setValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction), sideConnection);
            }
        }
        return state;
    }

    /** {@code RedStoneWireBlock.getConnectingSide(level, pos, direction)}. */
    private static RedstoneSide getConnectingSide(BlockGetter level, BlockPos pos, Direction direction) {
        return getConnectingSide(level, pos, direction,
                !level.getBlockState(pos.above()).isRedstoneConductor(level, pos));
    }

    /**
     * {@code RedStoneWireBlock.getConnectingSide(level, pos, direction, canConnectUp)}.
     *
     * <p>{@code canConnectUp} is the conductor test on the block above THIS
     * wire: a solid block overhead occludes every upward link, which is what
     * breaks the climb in the stair-occlusion scenario. The UP-vs-SIDE choice
     * then depends on whether the neighbour's facing side is sturdy.
     */
    private static RedstoneSide getConnectingSide(BlockGetter level, BlockPos pos, Direction direction,
            boolean canConnectUp) {
        BlockPos relativePos = pos.relative(direction);
        BlockState relativeState = level.getBlockState(relativePos);
        if (canConnectUp) {
            boolean isPlaceableAbove = relativeState.getBlock() instanceof TrapDoorBlock
                    || canSurviveOn(level, relativePos, relativeState);
            if (isPlaceableAbove && shouldConnectTo(level.getBlockState(relativePos.above()))) {
                if (relativeState.isFaceSturdy(level, relativePos, direction.getOpposite())) {
                    return RedstoneSide.UP;
                }
                return RedstoneSide.SIDE;
            }
        }
        return !shouldConnectTo(relativeState, direction)
                && (relativeState.isRedstoneConductor(level, relativePos)
                        || !shouldConnectTo(level.getBlockState(relativePos.below())))
                ? RedstoneSide.NONE
                : RedstoneSide.SIDE;
    }

    /** {@code RedStoneWireBlock.shouldConnectTo(state)}. */
    private static boolean shouldConnectTo(BlockState blockState) {
        return shouldConnectTo(blockState, null);
    }

    /** {@code RedStoneWireBlock.shouldConnectTo(state, direction)}. */
    private static boolean shouldConnectTo(BlockState blockState, Direction direction) {
        if (blockState.is(Blocks.REDSTONE_WIRE)) {
            return true;
        }
        if (blockState.is(Blocks.REPEATER)) {
            Direction repeaterDirection = blockState.getValue(RepeaterBlock.FACING);
            return repeaterDirection == direction || repeaterDirection.getOpposite() == direction;
        }
        if (blockState.is(Blocks.OBSERVER)) {
            return direction == blockState.getValue(ObserverBlock.FACING);
        }
        return blockState.isSignalSource() && direction != null;
    }

    /** {@code RedStoneWireBlock.canSurvive}. */
    static boolean canSurvive(BlockGetter level, BlockPos pos) {
        BlockPos below = pos.below();
        return canSurviveOn(level, below, level.getBlockState(below));
    }

    /** {@code RedStoneWireBlock.canSurviveOn}. */
    private static boolean canSurviveOn(BlockGetter level, BlockPos relativePos, BlockState relativeState) {
        return relativeState.isFaceSturdy(level, relativePos, Direction.UP) || relativeState.is(Blocks.HOPPER);
    }

    /** {@code RedStoneWireBlock.updateShape}. */
    static BlockState updateShape(BlockGetter level, BlockState state, BlockPos pos,
            Direction directionToNeighbour, BlockPos neighbourPos, BlockState neighbourState) {
        if (directionToNeighbour == Direction.DOWN) {
            return !canSurviveOn(level, neighbourPos, neighbourState)
                    ? Blocks.AIR.defaultBlockState()
                    : state;
        }
        if (directionToNeighbour == Direction.UP) {
            return getConnectionState(level, state, pos);
        }
        RedstoneSide sideConnection = getConnectingSide(level, pos, directionToNeighbour);
        return sideConnection.isConnected()
                == state.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(directionToNeighbour)).isConnected()
                && !isCross(state)
                ? state.setValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(directionToNeighbour), sideConnection)
                : getConnectionState(level,
                        CROSS_STATE.setValue(RedStoneWireBlock.POWER, state.getValue(RedStoneWireBlock.POWER))
                                .setValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(directionToNeighbour),
                                        sideConnection),
                        pos);
    }

    // =====================================================================
    // RedStoneWireBlock: update fan-out
    // =====================================================================

    /** {@code RedStoneWireBlock.neighborChanged}. */
    static void neighborChanged(VanillaRedstone level, BlockState state, BlockPos pos) {
        if (canSurvive(level, pos)) {
            updatePowerStrength(level, pos, state);
        } else {
            // Vanilla also drops the item; there are no entities here.
            level.removeBlock(pos, false);
        }
    }

    /** {@code RedStoneWireBlock.checkCornerChangeAt}. */
    private static void checkCornerChangeAt(VanillaRedstone level, BlockPos pos) {
        if (level.getBlockState(pos).is(Blocks.REDSTONE_WIRE)) {
            level.updateNeighborsAt(pos, Blocks.REDSTONE_WIRE);
            for (Direction direction : Direction.values()) {
                level.updateNeighborsAt(pos.relative(direction), Blocks.REDSTONE_WIRE);
            }
        }
    }

    /** {@code RedStoneWireBlock.updateNeighborsOfNeighboringWires}. */
    private static void updateNeighborsOfNeighboringWires(VanillaRedstone level, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            checkCornerChangeAt(level, pos.relative(direction));
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos target = pos.relative(direction);
            if (level.getBlockState(target).isRedstoneConductor(level, target)) {
                checkCornerChangeAt(level, target.above());
            } else {
                checkCornerChangeAt(level, target.below());
            }
        }
    }

    /**
     * {@code RedStoneWireBlock.onPlace}. The guard is on the BLOCK, not the
     * state, so a pure POWER change re-entering through setBlock does nothing
     * here; only a wire appearing where there was none fans out.
     */
    static void onPlace(VanillaRedstone level, BlockState state, BlockPos pos, BlockState oldState) {
        if (!oldState.is(state.getBlock())) {
            updatePowerStrength(level, pos, state);
            for (Direction direction : Direction.Plane.VERTICAL) {
                level.updateNeighborsAt(pos.relative(direction), Blocks.REDSTONE_WIRE);
            }
            updateNeighborsOfNeighboringWires(level, pos);
        }
    }

    /** {@code RedStoneWireBlock.affectNeighborsAfterRemoval}. */
    static void affectNeighborsAfterRemoval(VanillaRedstone level, BlockState state, BlockPos pos,
            boolean movedByPiston) {
        if (!movedByPiston) {
            for (Direction direction : Direction.values()) {
                level.updateNeighborsAt(pos.relative(direction), Blocks.REDSTONE_WIRE);
            }
            updatePowerStrength(level, pos, state);
            updateNeighborsOfNeighboringWires(level, pos);
        }
    }

    /**
     * {@code RedStoneWireBlock.updateIndirectNeighbourShapes}: the only
     * override of it in vanilla. Where this wire connects toward a horizontal
     * neighbour that is not itself a wire, the wire one step down and the
     * wire one step up on that side get a shape update, so an ascending or
     * descending run re-resolves its own connection at the same time.
     */
    static void updateIndirectNeighbourShapes(VanillaRedstone level, BlockState state, BlockPos pos,
            int updateFlags, int updateLimit) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            RedstoneSide value = state.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction));
            if (value != RedstoneSide.NONE
                    && !level.getBlockState(cursor.setWithOffset(pos, direction)).is(Blocks.REDSTONE_WIRE)) {
                cursor.move(Direction.DOWN);
                if (level.getBlockState(cursor).is(Blocks.REDSTONE_WIRE)) {
                    BlockPos neighborPos = cursor.relative(direction.getOpposite());
                    level.neighborShapeChanged(direction.getOpposite(), cursor, neighborPos,
                            level.getBlockState(neighborPos), updateFlags, updateLimit);
                }
                cursor.setWithOffset(pos, direction).move(Direction.UP);
                if (level.getBlockState(cursor).is(Blocks.REDSTONE_WIRE)) {
                    BlockPos neighborPos = cursor.relative(direction.getOpposite());
                    level.neighborShapeChanged(direction.getOpposite(), cursor, neighborPos,
                            level.getBlockState(neighborPos), updateFlags, updateLimit);
                }
            }
        }
    }

    /**
     * {@code RedStoneWireBlock.updatesOnShapeChange}: fired when a player
     * right-click toggles a wire between dot and cross. A side that gained or
     * lost its connection re-notifies the conductor it touches, skipping the
     * face pointing back at this wire.
     */
    static void updatesOnShapeChange(VanillaRedstone level, BlockPos pos, BlockState oldState, BlockState newState) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos relativePos = pos.relative(direction);
            if (oldState.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction)).isConnected()
                    != newState.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction)).isConnected()
                    && level.getBlockState(relativePos).isRedstoneConductor(level, relativePos)) {
                level.updateNeighborsAtExceptFromFacing(relativePos, newState.getBlock(), direction.getOpposite());
            }
        }
    }

    /**
     * {@code RedStoneWireBlock.useWithoutItem}: the dot/cross toggle, exposed
     * for the oracle's USE actions. Returns true when the toggle applied.
     */
    static boolean useWithoutItem(VanillaRedstone level, BlockState state, BlockPos pos) {
        if (isCross(state) || isDot(state)) {
            BlockState newState = isCross(state) ? Blocks.REDSTONE_WIRE.defaultBlockState() : CROSS_STATE;
            newState = newState.setValue(RedStoneWireBlock.POWER, state.getValue(RedStoneWireBlock.POWER));
            newState = getConnectionState(level, newState, pos);
            if (newState != state) {
                level.setBlock(pos, newState, 3);
                updatesOnShapeChange(level, pos, state, newState);
                return true;
            }
        }
        return false;
    }

    /** Block identity helper kept next to the logic it belongs to. */
    static boolean isWire(Block block) {
        return block == Blocks.REDSTONE_WIRE;
    }
}
