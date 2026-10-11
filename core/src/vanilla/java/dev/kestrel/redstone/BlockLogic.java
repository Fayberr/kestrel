package dev.kestrel.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dispatch table standing in for vanilla's virtual calls
 * ({@code BlockState.handleNeighborChanged}, {@code onPlace},
 * {@code affectNeighborsAfterRemoval}, {@code updateIndirectNeighbourShapes}).
 *
 * <p>Those all take a concrete {@code Level}, so they cannot be invoked on
 * the real block instances from here; this class routes by block identity to
 * the transcribed logic instead. Blocks with no entry are inert on the
 * redstone path, exactly as a vanilla block that overrides none of these is.
 *
 * <p>Coverage is deliberately slice-scoped. Wire, repeater, comparator,
 * lever, button, redstone torch (with burnout), observer, redstone lamp and
 * the pistons (base, head, moving placeholder) are transcribed; pressure
 * plates and the rest of the redstone-adjacent blocks are not, and are left
 * absent rather than approximated so a parity run fails loudly instead of
 * quietly drifting.
 */
final class BlockLogic {
    private BlockLogic() {
    }

    /** {@code BlockState.handleNeighborChanged} -> {@code Block.neighborChanged}. */
    static void neighborChanged(VanillaRedstone level, BlockState state, BlockPos pos,
            Block changedBlock, boolean movedByPiston) {
        Block block = state.getBlock();
        if (block == Blocks.REDSTONE_WIRE) {
            WireLogic.neighborChanged(level, state, pos);
        } else if (block == Blocks.REPEATER || block == Blocks.COMPARATOR) {
            DiodeLogic.neighborChanged(level, state, pos);
        } else if (isRedstoneTorch(block)) {
            torchNeighborChanged(level, state, pos);
        } else if (block == Blocks.REDSTONE_LAMP) {
            lampNeighborChanged(level, state, pos);
        } else if (PistonLogic.isPiston(state)) {
            // PistonBaseBlock.neighborChanged
            PistonLogic.neighborChanged(level, state, pos);
        } else if (block == Blocks.PISTON_HEAD) {
            // PistonHeadBlock.neighborChanged
            PistonLogic.headNeighborChanged(level, state, pos, changedBlock);
        }
    }

    /** {@code BlockState.onPlace} -> {@code Block.onPlace}. */
    static void onPlace(VanillaRedstone level, BlockState state, BlockPos pos, BlockState oldState,
            boolean movedByPiston) {
        Block block = state.getBlock();
        if (block == Blocks.REDSTONE_WIRE) {
            WireLogic.onPlace(level, state, pos, oldState);
        } else if (block == Blocks.REPEATER || block == Blocks.COMPARATOR) {
            DiodeLogic.onPlace(level, state, pos);
        } else if (isRedstoneTorch(block)) {
            // RedstoneTorchBlock.onPlace
            torchNotifyNeighbors(level, pos, block);
        } else if (block == Blocks.OBSERVER) {
            observerOnPlace(level, state, pos, oldState);
        } else if (PistonLogic.isPiston(state)) {
            // PistonBaseBlock.onPlace
            PistonLogic.onPlace(level, state, pos, oldState);
        }
    }

    /** {@code BlockState.affectNeighborsAfterRemoval}. */
    static void affectNeighborsAfterRemoval(VanillaRedstone level, BlockState state, BlockPos pos,
            boolean movedByPiston) {
        Block block = state.getBlock();
        if (block == Blocks.REDSTONE_WIRE) {
            WireLogic.affectNeighborsAfterRemoval(level, state, pos, movedByPiston);
        } else if (block == Blocks.REPEATER || block == Blocks.COMPARATOR) {
            DiodeLogic.affectNeighborsAfterRemoval(level, state, pos, movedByPiston);
        } else if (isRedstoneTorch(block)) {
            // RedstoneTorchBlock.affectNeighborsAfterRemoval
            if (!movedByPiston) {
                torchNotifyNeighbors(level, pos, block);
            }
        } else if (block == Blocks.OBSERVER) {
            // ObserverBlock.affectNeighborsAfterRemoval
            if (state.getValue(ObserverBlock.POWERED) && level.hasScheduledTick(pos, block)) {
                observerUpdateNeighborsInFront(level, pos, state.setValue(ObserverBlock.POWERED, false));
            }
        } else if (block instanceof LeverBlock) {
            // LeverBlock.affectNeighborsAfterRemoval
            if (!movedByPiston && state.getValue(LeverBlock.POWERED)) {
                updateNeighbours(level, state, pos, block);
            }
        } else if (block instanceof ButtonBlock) {
            // ButtonBlock.affectNeighborsAfterRemoval
            if (!movedByPiston && state.getValue(ButtonBlock.POWERED)) {
                updateNeighbours(level, state, pos, block);
            }
        } else if (block == Blocks.PISTON_HEAD) {
            // PistonHeadBlock.affectNeighborsAfterRemoval
            PistonLogic.headAffectNeighborsAfterRemoval(level, state, pos);
        }
    }

    /**
     * {@code BlockState.triggerEvent} -> {@code Block.triggerEvent}: the
     * Phase-6 body dispatch. Only the piston has one here; every other block
     * answers false, as vanilla's default does.
     */
    static boolean triggerEvent(VanillaRedstone level, BlockState state, BlockPos pos, int b0, int b1) {
        if (PistonLogic.isPiston(state)) {
            return PistonLogic.triggerEvent(level, state, pos, b0, b1);
        }
        return false;
    }

    /** {@code BlockState.updateIndirectNeighbourShapes}: wire is the only override. */
    static void updateIndirectNeighbourShapes(VanillaRedstone level, BlockState state, BlockPos pos,
            int updateFlags, int updateLimit) {
        if (state.is(Blocks.REDSTONE_WIRE)) {
            WireLogic.updateIndirectNeighbourShapes(level, state, pos, updateFlags, updateLimit);
        }
    }

    /** {@code BlockState.tick} -> {@code Block.tick}, for the loop's scheduled ticks. */
    static void tick(VanillaRedstone level, BlockState state, BlockPos pos) {
        Block block = state.getBlock();
        if (block instanceof net.minecraft.world.level.block.FallingBlock) {
            if (net.minecraft.world.level.block.FallingBlock.isFree(level.getBlockState(pos.below()))
                    && pos.getY() >= level.minBuildY()) level.fallingTick(pos, state);
        } else if (block == Blocks.REPEATER || block == Blocks.COMPARATOR) {
            DiodeLogic.tick(level, pos, state);
        } else if (isRedstoneTorch(block)) {
            torchTick(level, state, pos);
        } else if (block == Blocks.OBSERVER) {
            observerTick(level, state, pos);
        } else if (block == Blocks.REDSTONE_LAMP) {
            // RedstoneLampBlock.tick: the delayed turn-off.
            if (state.getValue(RedstoneLampBlock.LIT) && !level.hasNeighborSignal(pos)) {
                level.setBlock(pos, state.cycle(RedstoneLampBlock.LIT), 2);
            }
        } else if (block instanceof ButtonBlock) {
            // ButtonBlock.tick -> checkPressed: release after the press window.
            if (state.getValue(ButtonBlock.POWERED)) {
                level.setBlock(pos, state.setValue(ButtonBlock.POWERED, false), 3);
                updateNeighbours(level, state, pos, block);
            }
        }
    }

    // =====================================================================
    // RedstoneTorchBlock
    // =====================================================================

    /** Standing or wall redstone torch ({@code RedstoneWallTorchBlock} extends the torch). */
    private static boolean isRedstoneTorch(Block block) {
        return block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH;
    }

    /**
     * {@code RedstoneTorchBlock.notifyNeighbors}: a full neighbor update
     * around each of the six adjacent cells, in {@code Direction.values()}
     * order (DOWN, UP, NORTH, SOUTH, WEST, EAST). This is how a torch's
     * change reaches blocks two away through the block it powers.
     */
    private static void torchNotifyNeighbors(VanillaRedstone level, BlockPos pos, Block block) {
        for (Direction direction : Direction.values()) {
            level.updateNeighborsAt(pos.relative(direction), block);
        }
    }

    /**
     * {@code RedstoneTorchBlock.tick}: prune toggles older than 60 ticks,
     * then turn off when powered (recording the toggle and burning out on the
     * 8th inside the window: level event 1502 and a 160-tick restart), or
     * turn back on when unpowered and not burnt out.
     *
     * <p>The 160-tick restart goes through the ordinary first-schedule-wins
     * admission, so if a 2-tick flip is already pending for this torch the
     * restart is dropped, exactly as vanilla drops it.
     */
    private static void torchTick(VanillaRedstone level, BlockState state, BlockPos pos) {
        boolean neighborSignal = torchHasNeighborSignal(level, pos, state);
        java.util.List<VanillaRedstone.Toggle> toggles = level.recentToggles();
        while (!toggles.isEmpty() && level.getGameTime() - toggles.get(0).when() > 60L) {
            toggles.remove(0);
        }
        if (state.getValue(RedstoneTorchBlock.LIT)) {
            if (neighborSignal) {
                level.setBlock(pos, state.setValue(RedstoneTorchBlock.LIT, false), 3);
                if (isToggledTooFrequently(level, pos, true)) {
                    level.levelEvent(1502, pos, 0);
                    level.scheduleTick(pos, level.getBlockState(pos).getBlock(), 160);
                }
            }
        } else if (!neighborSignal && !isToggledTooFrequently(level, pos, false)) {
            level.setBlock(pos, state.setValue(RedstoneTorchBlock.LIT, true), 3);
        }
    }

    /** {@code RedstoneTorchBlock.isToggledTooFrequently}: 8 toggles of this torch in the window. */
    private static boolean isToggledTooFrequently(VanillaRedstone level, BlockPos pos, boolean add) {
        java.util.List<VanillaRedstone.Toggle> toggles = level.recentToggles();
        if (add) {
            toggles.add(new VanillaRedstone.Toggle(pos.immutable(), level.getGameTime()));
        }
        int count = 0;
        for (VanillaRedstone.Toggle toggle : toggles) {
            if (toggle.pos().equals(pos)) {
                if (++count >= 8) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code RedstoneTorchBlock.hasNeighborSignal}. */
    static boolean torchHasNeighborSignal(VanillaRedstone level, BlockPos pos, BlockState state) {
        // RedstoneWallTorchBlock overrides this to read the block it is
        // attached to instead of the one below.
        if (state.is(Blocks.REDSTONE_WALL_TORCH)) {
            Direction facing = state.getValue(net.minecraft.world.level.block.RedstoneWallTorchBlock.FACING);
            return level.hasSignal(pos.relative(facing.getOpposite()), facing);
        }
        return level.hasSignal(pos.below(), Direction.DOWN);
    }

    /**
     * {@code RedstoneTorchBlock.neighborChanged}: schedule the 2-tick flip
     * when the torch's lit state agrees with its input (meaning it is about
     * to disagree), unless a flip is already collected for this tick.
     */
    private static void torchNeighborChanged(VanillaRedstone level, BlockState state, BlockPos pos) {
        if (state.getValue(RedstoneTorchBlock.LIT) == torchHasNeighborSignal(level, pos, state)
                && !level.willTickThisTick(pos, state.getBlock())) {
            level.scheduleTick(pos, state.getBlock(), 2);
        }
    }

    // =====================================================================
    // ObserverBlock
    // =====================================================================

    /**
     * {@code ObserverBlock.tick}: a pulse is two ticks. Off to on schedules
     * the turn-off 2 ticks out; either edge notifies the block behind.
     * {@code updateShape} (which starts the pulse) is vanilla's own, reached
     * through the engine's shape pass with the loop's real tick access.
     */
    private static void observerTick(VanillaRedstone level, BlockState state, BlockPos pos) {
        if (state.getValue(ObserverBlock.POWERED)) {
            level.setBlock(pos, state.setValue(ObserverBlock.POWERED, false), 2);
        } else {
            level.setBlock(pos, state.setValue(ObserverBlock.POWERED, true), 2);
            level.scheduleTick(pos, state.getBlock(), 2);
        }
        observerUpdateNeighborsInFront(level, pos, state);
    }

    /**
     * {@code ObserverBlock.updateNeighborsInFront}: the cell behind the
     * observer (its output side), then that cell's other neighbors.
     */
    private static void observerUpdateNeighborsInFront(VanillaRedstone level, BlockPos pos, BlockState state) {
        Direction direction = state.getValue(ObserverBlock.FACING);
        BlockPos oppositePos = pos.relative(direction.getOpposite());
        level.neighborChanged(oppositePos, state.getBlock());
        level.updateNeighborsAtExceptFromFacing(oppositePos, state.getBlock(), direction);
    }

    /**
     * {@code ObserverBlock.onPlace}: an observer that arrives already powered
     * with no turn-off pending is reset to unpowered (flags 18) and notifies.
     */
    private static void observerOnPlace(VanillaRedstone level, BlockState state, BlockPos pos, BlockState oldState) {
        if (!state.is(oldState.getBlock())) {
            if (state.getValue(ObserverBlock.POWERED) && !level.hasScheduledTick(pos, state.getBlock())) {
                BlockState newState = state.setValue(ObserverBlock.POWERED, false);
                level.setBlock(pos, newState, 18);
                observerUpdateNeighborsInFront(level, pos, newState);
            }
        }
    }

    // =====================================================================
    // RedstoneLampBlock
    // =====================================================================

    /**
     * {@code RedstoneLampBlock.neighborChanged}: light immediately (flags 2),
     * but go dark only through a 4-tick scheduled tick.
     */
    private static void lampNeighborChanged(VanillaRedstone level, BlockState state, BlockPos pos) {
        boolean isLit = state.getValue(RedstoneLampBlock.LIT);
        if (isLit != level.hasNeighborSignal(pos)) {
            if (isLit) {
                level.scheduleTick(pos, state.getBlock(), 4);
            } else {
                level.setBlock(pos, state.cycle(RedstoneLampBlock.LIT), 2);
            }
        }
    }

    // =====================================================================
    // LeverBlock / ButtonBlock
    // =====================================================================

    /** {@code FaceAttachedHorizontalDirectionalBlock.getConnectedDirection}. */
    static Direction getConnectedDirection(BlockState state) {
        return switch (state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)) {
            case CEILING -> Direction.DOWN;
            case FLOOR -> Direction.UP;
            default -> state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
        };
    }

    /**
     * {@code LeverBlock.updateNeighbours} / {@code ButtonBlock.updateNeighbours}:
     * the switch's own position, then the block it is attached to.
     */
    static void updateNeighbours(VanillaRedstone level, BlockState state, BlockPos pos, Block block) {
        Direction front = getConnectedDirection(state).getOpposite();
        level.updateNeighborsAt(pos, block);
        level.updateNeighborsAt(pos.relative(front), block);
    }

    /** {@code LeverBlock.pull}: flip POWERED with full updates, then notify. */
    static void leverPull(VanillaRedstone level, BlockState state, BlockPos pos) {
        BlockState flipped = state.cycle(LeverBlock.POWERED);
        level.setBlock(pos, flipped, 3);
        updateNeighbours(level, flipped, pos, flipped.getBlock());
    }

    /** {@code ButtonBlock.press}: POWERED with full updates, then the release tick. */
    static void buttonPress(VanillaRedstone level, BlockState state, BlockPos pos, int ticksToStayPressed) {
        level.setBlock(pos, state.setValue(ButtonBlock.POWERED, true), 3);
        updateNeighbours(level, state, pos, state.getBlock());
        level.scheduleTick(pos, state.getBlock(), ticksToStayPressed);
    }
}
