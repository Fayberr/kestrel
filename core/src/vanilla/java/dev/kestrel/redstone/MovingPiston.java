package dev.kestrel.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * State of a moving piston block entity: the transcription of
 * {@code PistonMovingBlockEntity}'s state plus its two mutation methods,
 * {@code PistonMovingBlockEntity.tick} and
 * {@code PistonMovingBlockEntity.finalTick}.
 *
 * <p>Vanilla hangs this off a real
 * {@link net.minecraft.world.level.block.entity.BlockEntity} because it has a
 * concrete {@code Level} to attach it to. Kestrel has no {@code Level}, so the
 * fields live in this holder and the loop's block-entity store
 * ({@code dev.kestrel.loop.BlockEntityStore}) owns the instances and hands
 * them to the block-entity tick phase, exactly where
 * {@code ServerLevel.tickBlockEntities} would tick the real entity.
 *
 * <p>Deliberately not transcribed: entity collision, pushing and the slime
 * bounce ({@code moveCollidedEntities}, {@code moveStuckEntities},
 * {@code moveEntityByPiston}, {@code fixEntityWithinPistonBase}). This world
 * has no entities, so those loops have nothing to iterate; their absence
 * cannot change block states. The {@code level.isClientSide()} branch of
 * {@code tick} is likewise absent: Kestrel is a server.
 */
public final class MovingPiston {
    /** {@code PistonMovingBlockEntity.TICKS_TO_EXTEND} is 2; progress steps 0.5 per tick. */
    static final float PROGRESS_PER_TICK = 0.5F;

    private final BlockPos pos;
    private final BlockState movedState;
    private final Direction direction;
    private final boolean extending;
    private final boolean isSourcePiston;
    private float progress = 0.0F;
    private float progressO = 0.0F;
    private long lastTicked;
    private boolean removed;

    public MovingPiston(BlockPos pos, BlockState movedState, Direction direction, boolean extending,
            boolean isSourcePiston) {
        this.pos = pos.immutable();
        this.movedState = movedState;
        this.direction = direction;
        this.extending = extending;
        this.isSourcePiston = isSourcePiston;
    }

    public BlockPos pos() {
        return pos;
    }

    /** {@code PistonMovingBlockEntity.isExtending}. */
    public boolean isExtending() {
        return extending;
    }

    /** {@code PistonMovingBlockEntity.getDirection}. */
    public Direction getDirection() {
        return direction;
    }

    /** {@code PistonMovingBlockEntity.isSourcePiston}. */
    public boolean isSourcePiston() {
        return isSourcePiston;
    }

    /** {@code PistonMovingBlockEntity.getMovedState}. */
    public BlockState getMovedState() {
        return movedState;
    }

    /** {@code PistonMovingBlockEntity.getProgress(a)}. */
    public float getProgress(float a) {
        if (a > 1.0F) {
            a = 1.0F;
        }
        return Mth.lerp(a, progressO, progress);
    }

    /** {@code PistonMovingBlockEntity.getLastTicked}. */
    public long getLastTicked() {
        return lastTicked;
    }

    /** {@code BlockEntity.isRemoved} for this entity's lifecycle. */
    public boolean isRemoved() {
        return removed;
    }

    /** {@code BlockEntity.setRemoved}. */
    public void setRemoved() {
        this.removed = true;
    }

    /**
     * {@code PistonMovingBlockEntity.tick}: on the server the first branch
     * settles the moved block and drops the entity, the second advances the
     * half-block step. Vanilla reads {@code level.getGameTime()} into
     * {@code lastTicked} first, which is what
     * {@code PistonBaseBlock.checkIfExtend} compares to decide between the
     * contract and drop block events.
     */
    public void tick(VanillaRedstone level) {
        this.lastTicked = level.getGameTime();
        this.progressO = this.progress;
        if (this.progressO >= 1.0F) {
            level.removeMovingPiston(this.pos);
            this.setRemoved();
            if (level.getBlockState(this.pos).is(Blocks.MOVING_PISTON)) {
                BlockState newState = PistonLogic.updateFromNeighbourShapes(level, this.movedState, this.pos);
                if (newState.isAir()) {
                    level.setBlock(this.pos, this.movedState, 340);
                    level.updateOrDestroy(this.movedState, newState, this.pos, 3, 512);
                } else {
                    if (newState.hasProperty(BlockStateProperties.WATERLOGGED)
                            && newState.getValue(BlockStateProperties.WATERLOGGED)) {
                        newState = newState.setValue(BlockStateProperties.WATERLOGGED, false);
                    }
                    level.setBlock(this.pos, newState, 67);
                    level.neighborChanged(this.pos, newState.getBlock());
                }
            }
        } else {
            this.progress = this.progress + PROGRESS_PER_TICK;
            if (this.progress >= 1.0F) {
                this.progress = 1.0F;
            }
        }
    }

    /**
     * {@code PistonMovingBlockEntity.finalTick}: force the move to completion
     * now. Called both explicitly (block events 1 and 2, the sticky two-cell
     * arm) and through {@code preRemoveSideEffects} when the placeholder is
     * destroyed early. A completed entity is a no-op, as in vanilla.
     */
    public void finalTick(VanillaRedstone level) {
        if (this.progressO >= 1.0F) {
            return;
        }
        this.progress = 1.0F;
        this.progressO = this.progress;
        level.removeMovingPiston(this.pos);
        this.setRemoved();
        if (level.getBlockState(this.pos).is(Blocks.MOVING_PISTON)) {
            BlockState newState;
            if (this.isSourcePiston) {
                newState = Blocks.AIR.defaultBlockState();
            } else {
                newState = PistonLogic.updateFromNeighbourShapes(level, this.movedState, this.pos);
            }
            level.setBlock(this.pos, newState, 3);
            level.neighborChanged(this.pos, newState.getBlock());
        }
    }
}
