package dev.kestrel.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.ticks.TickPriority;

/**
 * Transcription of {@code net.minecraft.world.level.block.DiodeBlock} and the
 * {@code RepeaterBlock} and {@code ComparatorBlock} overrides of it. The
 * shared methods branch on the block where vanilla would dispatch virtually.
 *
 * <p>The comparator keeps its output in a {@code ComparatorBlockEntity},
 * served by the loop's live block-entity store through
 * {@link VanillaRedstone#getBlockEntity}. A comparator with no block entity
 * (an imported world cell, say) reads and keeps output 0, exactly as
 * vanilla's {@code instanceof} guards do.
 */
final class DiodeLogic {
    private DiodeLogic() {
    }

    /**
     * {@code RepeaterBlock.getDelay}: DELAY property in redstone ticks,
     * doubled. {@code ComparatorBlock.getDelay}: always 2.
     */
    private static int getDelay(BlockState state) {
        if (state.is(Blocks.COMPARATOR)) {
            return 2;
        }
        return state.getValue(RepeaterBlock.DELAY) * 2;
    }

    /**
     * {@code DiodeBlock.getOutputSignal}: repeaters always output full
     * strength. {@code ComparatorBlock.getOutputSignal}: the block entity's
     * stored output, 0 without one.
     */
    private static int getOutputSignal(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.COMPARATOR)) {
            return level.getBlockEntity(pos) instanceof ComparatorBlockEntity comparatorBlockEntity
                    ? comparatorBlockEntity.getOutputSignal()
                    : 0;
        }
        return 15;
    }

    /** {@code RepeaterBlock.sideInputDiodesOnly}: only diodes lock a repeater. */
    private static boolean sideInputDiodesOnly(BlockState state) {
        return state.is(Blocks.REPEATER);
    }

    /**
     * {@code DiodeBlock.getInputSignal}, or {@code ComparatorBlock.getInputSignal}
     * for a comparator.
     */
    private static int getInputSignal(VanillaRedstone level, BlockPos pos, BlockState state) {
        return state.is(Blocks.COMPARATOR)
                ? comparatorGetInputSignal(level, pos, state)
                : diodeGetInputSignal(level, pos, state);
    }

    /** {@code DiodeBlock.getInputSignal}: the signal arriving at the back face. */
    private static int diodeGetInputSignal(VanillaRedstone level, BlockPos pos, BlockState state) {
        Direction direction = state.getValue(DiodeBlock.FACING);
        BlockPos targetPos = pos.relative(direction);
        int input = level.getSignal(targetPos, direction);
        if (input >= 15) {
            return input;
        }
        BlockState targetBlockState = level.getBlockState(targetPos);
        return Math.max(input, targetBlockState.is(Blocks.REDSTONE_WIRE)
                ? targetBlockState.getValue(RedStoneWireBlock.POWER)
                : 0);
    }

    /** {@code DiodeBlock.getAlternateSignal}: the stronger of the two side inputs. */
    private static int getAlternateSignal(SignalGetter level, BlockPos pos, BlockState state) {
        Direction direction = state.getValue(DiodeBlock.FACING);
        Direction clockWise = direction.getClockWise();
        Direction counterClockWise = direction.getCounterClockWise();
        boolean diodesOnly = sideInputDiodesOnly(state);
        return Math.max(
                level.getControlInputSignal(pos.relative(clockWise), clockWise, diodesOnly),
                level.getControlInputSignal(pos.relative(counterClockWise), counterClockWise, diodesOnly));
    }

    /** {@code RepeaterBlock.isLocked}: a side diode holds the current output. */
    static boolean isLocked(SignalGetter level, BlockPos pos, BlockState state) {
        return state.is(Blocks.REPEATER) && getAlternateSignal(level, pos, state) > 0;
    }

    /** {@code DiodeBlock.shouldTurnOn}, or {@code ComparatorBlock.shouldTurnOn}. */
    private static boolean shouldTurnOn(VanillaRedstone level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.COMPARATOR)) {
            return comparatorShouldTurnOn(level, pos, state);
        }
        return getInputSignal(level, pos, state) > 0;
    }

    /**
     * {@code DiodeBlock.shouldPrioritize}: a diode feeding this one from
     * behind but pointing across it (not straight in) raises the schedule to
     * EXTREMELY_HIGH.
     */
    private static boolean shouldPrioritize(VanillaRedstone level, BlockPos pos, BlockState state) {
        Direction direction = state.getValue(DiodeBlock.FACING).getOpposite();
        BlockState oppositeState = level.getBlockState(pos.relative(direction));
        return DiodeBlock.isDiode(oppositeState)
                && oppositeState.getValue(DiodeBlock.FACING) != direction;
    }

    /**
     * {@code DiodeBlock.checkTickOnNeighbor}: schedule the state flip if the
     * input disagrees with the output and no flip is already collected for
     * this tick.
     *
     * <p>The priority split is the whole point of the tie scenario: turning
     * OFF schedules at VERY_HIGH (-2), turning ON at HIGH (-1), so on a tick
     * where both land, the off-going repeater drains first.
     */
    static void checkTickOnNeighbor(VanillaRedstone level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.COMPARATOR)) {
            comparatorCheckTickOnNeighbor(level, pos, state);
            return;
        }
        if (isLocked(level, pos, state)) {
            return;
        }
        boolean on = state.getValue(DiodeBlock.POWERED);
        boolean shouldTurnOn = shouldTurnOn(level, pos, state);
        if (on != shouldTurnOn && !level.willTickThisTick(pos, state.getBlock())) {
            TickPriority priority = TickPriority.HIGH;
            if (shouldPrioritize(level, pos, state)) {
                priority = TickPriority.EXTREMELY_HIGH;
            } else if (on) {
                priority = TickPriority.VERY_HIGH;
            }
            level.scheduleTick(pos, state.getBlock(), getDelay(state), priority);
        }
    }

    /** {@code DiodeBlock.neighborChanged}. */
    static void neighborChanged(VanillaRedstone level, BlockState state, BlockPos pos) {
        if (!level.getBlockState(pos).is(state.getBlock())) {
            return;
        }
        if (canSurvive(level, pos)) {
            checkTickOnNeighbor(level, pos, state);
        } else {
            level.removeBlock(pos, false);
            for (Direction direction : Direction.values()) {
                level.updateNeighborsAt(pos.relative(direction), state.getBlock());
            }
        }
    }

    /** {@code DiodeBlock.canSurvive} / {@code canSurviveOn}: rigid support below. */
    private static boolean canSurvive(VanillaRedstone level, BlockPos pos) {
        BlockPos belowPos = pos.below();
        return level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP,
                net.minecraft.world.level.block.SupportType.RIGID);
    }

    /**
     * {@code DiodeBlock.tick}: the deferred flip. Turning on while the input
     * has already gone away re-schedules the turn-off at VERY_HIGH, which is
     * what stretches a short pulse to the repeater's minimum width.
     */
    static void tick(VanillaRedstone level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.COMPARATOR)) {
            // ComparatorBlock.tick
            refreshOutputState(level, pos, state);
            return;
        }
        if (isLocked(level, pos, state)) {
            return;
        }
        boolean on = state.getValue(DiodeBlock.POWERED);
        boolean shouldTurnOn = shouldTurnOn(level, pos, state);
        if (on && !shouldTurnOn) {
            level.setBlock(pos, state.setValue(DiodeBlock.POWERED, false), 2);
        } else if (!on) {
            level.setBlock(pos, state.setValue(DiodeBlock.POWERED, true), 2);
            if (!shouldTurnOn) {
                level.scheduleTick(pos, state.getBlock(), getDelay(state), TickPriority.VERY_HIGH);
            }
        }
    }

    /**
     * {@code DiodeBlock.updateNeighborsInFront}: notify the block the diode
     * points into, then that block's other neighbours (skipping the face back
     * toward the diode). This is how a repeater's output reaches its wire,
     * since the POWERED write itself uses flags 2 and carries no updates.
     */
    private static void updateNeighborsInFront(VanillaRedstone level, BlockPos pos, BlockState state) {
        Direction direction = state.getValue(DiodeBlock.FACING);
        BlockPos oppositePos = pos.relative(direction.getOpposite());
        level.neighborChanged(oppositePos, state.getBlock());
        level.updateNeighborsAtExceptFromFacing(oppositePos, state.getBlock(), direction);
    }

    /** {@code DiodeBlock.onPlace}. */
    static void onPlace(VanillaRedstone level, BlockState state, BlockPos pos) {
        updateNeighborsInFront(level, pos, state);
    }

    /** {@code DiodeBlock.affectNeighborsAfterRemoval}. */
    static void affectNeighborsAfterRemoval(VanillaRedstone level, BlockState state, BlockPos pos,
            boolean movedByPiston) {
        if (!movedByPiston) {
            updateNeighborsInFront(level, pos, state);
        }
    }

    /** {@code DiodeBlock.ownSignal}. */
    static int ownSignal(BlockGetter level, BlockPos pos, BlockState state) {
        return state.getValue(DiodeBlock.POWERED) ? getOutputSignal(level, pos, state) : 0;
    }

    // =====================================================================
    // ComparatorBlock
    // =====================================================================

    /** {@code ComparatorBlock.calculateOutputSignal}. */
    private static int calculateOutputSignal(VanillaRedstone level, BlockPos pos, BlockState state) {
        int inputSignal = getInputSignal(level, pos, state);
        if (inputSignal == 0) {
            return 0;
        }
        int alternateSignal = getAlternateSignal(level, pos, state);
        if (alternateSignal > inputSignal) {
            return 0;
        }
        return state.getValue(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT
                ? inputSignal - alternateSignal
                : inputSignal;
    }

    /** {@code ComparatorBlock.shouldTurnOn}. */
    private static boolean comparatorShouldTurnOn(VanillaRedstone level, BlockPos pos, BlockState state) {
        int input = getInputSignal(level, pos, state);
        if (input == 0) {
            return false;
        }
        int sideInput = getAlternateSignal(level, pos, state);
        return input > sideInput
                || input == sideInput && state.getValue(ComparatorBlock.MODE) == ComparatorMode.COMPARE;
    }

    /**
     * {@code ComparatorBlock.getInputSignal}: the diode read, replaced by the
     * analog output of the block behind when it has one, or of the block one
     * further back when the one behind is a conductor.
     *
     * <p>{@code getItemFrame} is an entity query; this world has no entities,
     * so the frame term is always absent ({@code Integer.MIN_VALUE}).
     */
    private static int comparatorGetInputSignal(VanillaRedstone level, BlockPos pos, BlockState state) {
        int resultSignal = diodeGetInputSignal(level, pos, state);
        Direction direction = state.getValue(DiodeBlock.FACING);
        BlockPos targetPos = pos.relative(direction);
        BlockState targetState = level.getBlockState(targetPos);
        if (targetState.hasAnalogOutputSignal()) {
            resultSignal = AnalogOutput.getAnalogOutputSignal(level, targetState, targetPos, direction.getOpposite());
        } else if (resultSignal < 15 && targetState.isRedstoneConductor(level, targetPos)) {
            targetPos = targetPos.relative(direction);
            targetState = level.getBlockState(targetPos);
            int itemFrameSignal = Integer.MIN_VALUE;
            int itemFrameOrBlockSignal = Math.max(
                    itemFrameSignal,
                    targetState.hasAnalogOutputSignal()
                            ? AnalogOutput.getAnalogOutputSignal(level, targetState, targetPos, direction.getOpposite())
                            : Integer.MIN_VALUE);
            if (itemFrameOrBlockSignal != Integer.MIN_VALUE) {
                resultSignal = itemFrameOrBlockSignal;
            }
        }
        return resultSignal;
    }

    /**
     * {@code ComparatorBlock.checkTickOnNeighbor}: schedule a refresh when
     * the computed output or the POWERED state would change. Priority HIGH
     * when {@code shouldPrioritize}, else NORMAL (not the repeater's split).
     */
    private static void comparatorCheckTickOnNeighbor(VanillaRedstone level, BlockPos pos, BlockState state) {
        if (!level.willTickThisTick(pos, state.getBlock())) {
            int outputValue = calculateOutputSignal(level, pos, state);
            int oldValue = level.getBlockEntity(pos) instanceof ComparatorBlockEntity comparatorBlockEntity
                    ? comparatorBlockEntity.getOutputSignal()
                    : 0;
            if (outputValue != oldValue || state.getValue(DiodeBlock.POWERED) != shouldTurnOn(level, pos, state)) {
                TickPriority priority = shouldPrioritize(level, pos, state) ? TickPriority.HIGH : TickPriority.NORMAL;
                level.scheduleTick(pos, state.getBlock(), 2, priority);
            }
        }
    }

    /**
     * {@code ComparatorBlock.refreshOutputState}: store the new output on the
     * block entity, flip POWERED if it disagrees, and notify the front. In
     * COMPARE mode the front is notified even when the output held.
     */
    private static void refreshOutputState(VanillaRedstone level, BlockPos pos, BlockState state) {
        int outputValue = calculateOutputSignal(level, pos, state);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        int oldValue = 0;
        if (blockEntity instanceof ComparatorBlockEntity comparatorBlockEntity) {
            oldValue = comparatorBlockEntity.getOutputSignal();
            comparatorBlockEntity.setOutputSignal(outputValue);
        }
        if (oldValue != outputValue || state.getValue(ComparatorBlock.MODE) == ComparatorMode.COMPARE) {
            boolean sourceOn = shouldTurnOn(level, pos, state);
            boolean isOn = state.getValue(DiodeBlock.POWERED);
            if (isOn && !sourceOn) {
                level.setBlock(pos, state.setValue(DiodeBlock.POWERED, false), 2);
            } else if (!isOn && sourceOn) {
                level.setBlock(pos, state.setValue(DiodeBlock.POWERED, true), 2);
            }
            updateNeighborsInFront(level, pos, state);
        }
    }

    /**
     * {@code ComparatorBlock.useWithoutItem} for a player who may build:
     * cycle MODE with flags 2, then refresh if the comparator survived.
     * The click sound is client-side only and omitted.
     */
    static void comparatorUseWithoutItem(VanillaRedstone level, BlockState state, BlockPos pos) {
        if (!state.is(Blocks.COMPARATOR)) {
            return;
        }
        state = state.cycle(ComparatorBlock.MODE);
        level.setBlock(pos, state, 2);
        if (level.getBlockState(pos).is(state.getBlock())) {
            refreshOutputState(level, pos, state);
        }
    }
}
