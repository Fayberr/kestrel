package dev.kestrel.redstone;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.material.PushReaction;

import java.util.List;
import java.util.Map;

/**
 * Transcription of vanilla 26.2's piston machinery:
 * {@code PistonBaseBlock} ({@code checkIfExtend}, {@code getNeighborSignal},
 * {@code isPushable}, {@code triggerEvent}, {@code moveBlocks}),
 * {@code PistonStructureResolver}, {@code MovingPistonBlock} and
 * {@code PistonHeadBlock}'s two overrides.
 *
 * <p>Blocks that take a concrete {@code Level} cannot be called on the real
 * vanilla instances from here, so this class routes the piston half of the
 * dispatch like {@link BlockLogic} routes the rest. Everything that can be
 * read off block states and the real vanilla classes is still dispatched into
 * vanilla: {@code BlockState.isPushable} reads, {@code getPistonPushReaction},
 * {@code getDestroySpeed}, {@code PistonHeadBlock.updateShape} (reached
 * through the engine's shape pass), {@code Block.updateFromNeighbourShapes}.
 *
 * <p>The two Phase-6 block events are the only activation path: a powered
 * piston never moves blocks inside the neighbor update, it enqueues event 0,
 * 1 or 2 into the engine's {@code ServerLevel.blockEvents} stand-in, which the
 * loop drains in its own tick phase. Nothing here draws randomness: the two
 * {@code level.getRandom()} reads vanilla makes for the piston sound pitch are
 * skipped together with the {@code playSound} they feed, which is a no-op in
 * this world (no clients) and cannot affect block state.
 */
public final class PistonLogic {
    private PistonLogic() {
    }

    /** {@code PistonBaseBlock.TRIGGER_EXTEND}. */
    static final int TRIGGER_EXTEND = 0;
    /** {@code PistonBaseBlock.TRIGGER_CONTRACT}. */
    static final int TRIGGER_CONTRACT = 1;
    /** {@code PistonBaseBlock.TRIGGER_DROP}. */
    static final int TRIGGER_DROP = 2;

    /** True for the two piston base blocks ({@code PistonBaseBlock} and its sticky subclass). */
    static boolean isPiston(BlockState state) {
        return state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON);
    }

    /** {@code PistonBaseBlock.neighborChanged} (server side only: Kestrel is never the client). */
    static void neighborChanged(VanillaRedstone level, BlockState state, BlockPos pos) {
        checkIfExtend(level, pos, state);
    }

    /** {@code PistonBaseBlock.onPlace}. */
    static void onPlace(VanillaRedstone level, BlockState state, BlockPos pos, BlockState oldState) {
        if (!oldState.is(state.getBlock()) && level.getBlockEntity(pos) == null) {
            checkIfExtend(level, pos, state);
        }
    }

    /**
     * {@code PistonBaseBlock.checkIfExtend}. Extending enqueues event 0 only
     * when the structure resolves; retracting picks between contract (1) and
     * drop (2) by looking at whether the block two cells out is still an
     * in-flight extension of this piston.
     */
    private static void checkIfExtend(VanillaRedstone level, BlockPos pos, BlockState state) {
        Direction direction = state.getValue(PistonBaseBlock.FACING);
        boolean extend = getNeighborSignal(level, pos, direction);
        if (extend && !state.getValue(PistonBaseBlock.EXTENDED)) {
            if (new Resolver(level, pos, direction, true).resolve()) {
                level.blockEvent(pos, state.getBlock(), TRIGGER_EXTEND, direction.get3DDataValue());
            }
        } else if (!extend && state.getValue(PistonBaseBlock.EXTENDED)) {
            BlockPos pushedPos = pos.relative(direction, 2);
            BlockState pushedState = level.getBlockState(pushedPos);
            int event = TRIGGER_CONTRACT;
            MovingPiston pistonEntity = level.getMovingPiston(pushedPos);
            if (pushedState.is(Blocks.MOVING_PISTON)
                    && pushedState.getValue(MovingPistonBlock.FACING) == direction
                    && pistonEntity != null
                    && pistonEntity.isExtending()
                    && (pistonEntity.getProgress(0.0F) < 0.5F
                        || level.getGameTime() == pistonEntity.getLastTicked()
                        || level.isHandlingTick())) {
                event = TRIGGER_DROP;
            }
            level.blockEvent(pos, state.getBlock(), event, direction.get3DDataValue());
        }
    }

    /**
     * {@code PistonBaseBlock.getNeighborSignal}: the five faces other than
     * the push face, then the cell below, then every face of the cell above
     * except the one looking back down at the piston. That last block is
     * quasi-connectivity: the piston reads power there but is not a neighbor
     * of those sources, which is what makes it a block update detector.
     */
    private static boolean getNeighborSignal(VanillaRedstone level, BlockPos pos, Direction pushDirection) {
        for (Direction direction : Direction.values()) {
            if (direction != pushDirection && level.hasSignal(pos.relative(direction), direction)) {
                return true;
            }
        }
        if (level.hasSignal(pos, Direction.DOWN)) {
            return true;
        }
        BlockPos above = pos.above();
        for (Direction direction : Direction.values()) {
            if (direction != Direction.DOWN && level.hasSignal(above.relative(direction), direction)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code PistonBaseBlock.isPushable}. {@code level.getWorldBorder()}
     * is dropped: Kestrel's world has no border, so every position inside the
     * store is within it.
     */
    static boolean isPushable(VanillaRedstone level, BlockState state, BlockPos pos, Direction direction,
            boolean allowDestroyable, Direction connectionDirection) {
        if (pos.getY() < level.minBuildY() || pos.getY() > level.maxBuildY()) {
            return false;
        } else if (state.isAir()) {
            return true;
        } else if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)
                || state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.REINFORCED_DEEPSLATE)) {
            return false;
        } else if (direction == Direction.DOWN && pos.getY() == level.minBuildY()) {
            return false;
        } else if (direction == Direction.UP && pos.getY() == level.maxBuildY()) {
            return false;
        } else {
            if (!state.is(Blocks.PISTON) && !state.is(Blocks.STICKY_PISTON)) {
                if (state.getDestroySpeed(level, pos) == -1.0F) {
                    return false;
                }
                switch (state.getPistonPushReaction()) {
                    case BLOCK:
                        return false;
                    case DESTROY:
                        return allowDestroyable;
                    case PUSH_ONLY:
                        return direction == connectionDirection;
                    default:
                        break;
                }
            } else if (state.getValue(PistonBaseBlock.EXTENDED)) {
                return false;
            }
            return !state.hasBlockEntity();
        }
    }

    /**
     * {@code PistonBaseBlock.triggerEvent}: the Phase-6 body. {@code b0} 0
     * extends, 1 and 2 retract (2 leaves the held block behind). The leading
     * power re-check is what makes a stale event harmless: a retraction
     * arriving while the piston is powered again just resets EXTENDED, and an
     * extension arriving while unpowered is dropped.
     */
    static boolean triggerEvent(VanillaRedstone level, BlockState state, BlockPos pos, int b0, int b1) {
        Direction direction = state.getValue(PistonBaseBlock.FACING);
        BlockState extendedState = state.setValue(PistonBaseBlock.EXTENDED, true);
        boolean isSticky = state.is(Blocks.STICKY_PISTON);
        boolean extend = getNeighborSignal(level, pos, direction);
        if (extend && (b0 == TRIGGER_CONTRACT || b0 == TRIGGER_DROP)) {
            level.setBlock(pos, extendedState, 2);
            return false;
        }
        if (!extend && b0 == TRIGGER_EXTEND) {
            return false;
        }

        if (b0 == TRIGGER_EXTEND) {
            if (!moveBlocks(level, pos, direction, true, isSticky)) {
                return false;
            }
            level.setBlock(pos, extendedState, 67);
            // playSound + gameEvent: no clients and no game-event listeners in this world.
        } else if (b0 == TRIGGER_CONTRACT || b0 == TRIGGER_DROP) {
            MovingPiston armEntity = level.getMovingPiston(pos.relative(direction));
            if (armEntity != null) {
                armEntity.finalTick(level);
            }

            BlockState movingPistonState = Blocks.MOVING_PISTON
                    .defaultBlockState()
                    .setValue(MovingPistonBlock.FACING, direction)
                    .setValue(MovingPistonBlock.TYPE, isSticky ? PistonType.STICKY : PistonType.DEFAULT);
            level.setBlock(pos, movingPistonState, 276);
            // Level.setBlockEntity: the placeholder carries the piston's own state
            // rebuilt from the event's direction nibble, EXTENDED false.
            level.setMovingPiston(new MovingPiston(pos,
                    state.getBlock().defaultBlockState()
                            .setValue(PistonBaseBlock.FACING, Direction.from3DDataValue(b1 & 7)),
                    direction, false, true));
            level.updateNeighborsAt(pos, movingPistonState.getBlock());
            level.updateNeighbourShapes(movingPistonState, pos, 2, 512);
            if (isSticky) {
                BlockPos twoPos = pos.offset(direction.getStepX() * 2, direction.getStepY() * 2,
                        direction.getStepZ() * 2);
                BlockState movingState = level.getBlockState(twoPos);
                MovingPiston twoEntity = level.getMovingPiston(twoPos);
                boolean pistonPiece = false;
                if (movingState.is(Blocks.MOVING_PISTON)
                        && twoEntity != null
                        && twoEntity.getDirection() == direction
                        && twoEntity.isExtending()) {
                    twoEntity.finalTick(level);
                    pistonPiece = true;
                }

                if (!pistonPiece) {
                    if (b0 != TRIGGER_CONTRACT
                            || movingState.isAir()
                            || !isPushable(level, movingState, twoPos, direction.getOpposite(), false, direction)
                            || (movingState.getPistonPushReaction() != PushReaction.NORMAL
                                && !movingState.is(Blocks.PISTON) && !movingState.is(Blocks.STICKY_PISTON))) {
                        level.removeBlock(pos.relative(direction), false);
                    } else {
                        moveBlocks(level, pos, direction, false, isSticky);
                    }
                }
            } else {
                level.removeBlock(pos.relative(direction), false);
            }
            // playSound + gameEvent: no-ops here, as above.
        }
        return true;
    }

    /**
     * {@code PistonBaseBlock.moveBlocks}: resolve, destroy, place the
     * placeholder block entities, clear the vacated cells, then run the three
     * update passes. Two orderings are load-bearing and are preserved:
     * {@code deleteAfterMove} is a vanilla {@code HashMap} whose key order
     * drives the air pass, and the push list is walked back to front so the
     * cells a move vacates are only cleared after every destination has been
     * claimed.
     */
    private static boolean moveBlocks(VanillaRedstone level, BlockPos pistonPos, Direction direction,
            boolean extending, boolean isSticky) {
        BlockPos armPos = pistonPos.relative(direction);
        if (!extending && level.getBlockState(armPos).is(Blocks.PISTON_HEAD)) {
            level.setBlock(armPos, Blocks.AIR.defaultBlockState(), 276);
        }

        Resolver resolver = new Resolver(level, pistonPos, direction, extending);
        if (!resolver.resolve()) {
            return false;
        }
        Map<BlockPos, BlockState> deleteAfterMove = Maps.newHashMap();
        List<BlockPos> toPush = resolver.getToPush();
        List<BlockState> toPushShapes = Lists.newArrayList();

        for (BlockPos pos : toPush) {
            BlockState state = level.getBlockState(pos);
            toPushShapes.add(state);
            deleteAfterMove.put(pos, state);
        }

        List<BlockPos> toDestroy = resolver.getToDestroy();
        BlockState[] toUpdate = new BlockState[toPush.size() + toDestroy.size()];
        Direction pushDirection = extending ? direction : direction.getOpposite();
        int updateIndex = 0;

        for (int i = toDestroy.size() - 1; i >= 0; i--) {
            BlockPos pos = toDestroy.get(i);
            BlockState state = level.getBlockState(pos);
            // dropResources: this world has no item entities, so the destroyed
            // block leaves nothing behind. levelEvent(2001) is client-only.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
            toUpdate[updateIndex++] = state;
        }

        for (int i = toPush.size() - 1; i >= 0; i--) {
            BlockPos pos = toPush.get(i);
            BlockState blockState = level.getBlockState(pos);
            pos = pos.relative(pushDirection);
            deleteAfterMove.remove(pos);
            BlockState actualState = Blocks.MOVING_PISTON.defaultBlockState()
                    .setValue(MovingPistonBlock.FACING, direction);
            level.setBlock(pos, actualState, 324);
            level.setMovingPiston(new MovingPiston(pos, toPushShapes.get(i), direction, extending, false));
            toUpdate[updateIndex++] = blockState;
        }

        if (extending) {
            PistonType type = isSticky ? PistonType.STICKY : PistonType.DEFAULT;
            BlockState headState = Blocks.PISTON_HEAD.defaultBlockState()
                    .setValue(PistonHeadBlock.FACING, direction)
                    .setValue(PistonHeadBlock.TYPE, type);
            BlockState movingState = Blocks.MOVING_PISTON.defaultBlockState()
                    .setValue(MovingPistonBlock.FACING, direction)
                    .setValue(MovingPistonBlock.TYPE, type);
            deleteAfterMove.remove(armPos);
            level.setBlock(armPos, movingState, 324);
            level.setMovingPiston(new MovingPiston(armPos, headState, direction, true, true));
        }

        BlockState air = Blocks.AIR.defaultBlockState();

        for (BlockPos pos : deleteAfterMove.keySet()) {
            level.setBlock(pos, air, 82);
        }

        for (Map.Entry<BlockPos, BlockState> entry : deleteAfterMove.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState oldState = entry.getValue();
            BlockLogic.updateIndirectNeighbourShapes(level, oldState, pos, 2, 512);
            level.updateNeighbourShapes(air, pos, 2, 512);
            BlockLogic.updateIndirectNeighbourShapes(level, air, pos, 2, 512);
        }

        // Null orientation throughout: the default (non-experimental) path.
        updateIndex = 0;

        for (int i = toDestroy.size() - 1; i >= 0; i--) {
            BlockState state = toUpdate[updateIndex++];
            BlockPos pos = toDestroy.get(i);
            // BlockState.affectNeighborsAfterRemoval takes a ServerLevel, which is
            // why it goes through the dispatch table rather than the real instance.
            BlockLogic.affectNeighborsAfterRemoval(level, state, pos, false);
            BlockLogic.updateIndirectNeighbourShapes(level, state, pos, 2, 512);
            level.updateNeighborsAt(pos, state.getBlock());
        }

        for (int i = toPush.size() - 1; i >= 0; i--) {
            level.updateNeighborsAt(toPush.get(i), toUpdate[updateIndex++].getBlock());
        }

        if (extending) {
            level.updateNeighborsAt(armPos, Blocks.PISTON_HEAD);
        }

        return true;
    }

    /** {@code PistonHeadBlock.neighborChanged}: the head forwards to its base. */
    static void headNeighborChanged(VanillaRedstone level, BlockState state, BlockPos pos,
            net.minecraft.world.level.block.Block changedBlock) {
        if (state.canSurvive(level.levelReader(), pos)) {
            level.neighborChanged(pos.relative(state.getValue(PistonHeadBlock.FACING).getOpposite()), changedBlock);
        }
    }

    /**
     * {@code PistonHeadBlock.affectNeighborsAfterRemoval}: a head that is
     * broken in survival takes its base piston with it.
     */
    static void headAffectNeighborsAfterRemoval(VanillaRedstone level, BlockState state, BlockPos pos) {
        BlockPos basePos = pos.relative(state.getValue(PistonHeadBlock.FACING).getOpposite());
        if (isFittingBase(state, level.getBlockState(basePos))) {
            level.destroyBlock(basePos, 512);
        }
    }

    /** {@code PistonHeadBlock.isFittingBase}. */
    private static boolean isFittingBase(BlockState armState, BlockState potentialBase) {
        net.minecraft.world.level.block.Block baseBlock = armState.getValue(PistonHeadBlock.TYPE) == PistonType.DEFAULT
                ? Blocks.PISTON
                : Blocks.STICKY_PISTON;
        return potentialBase.is(baseBlock)
                && potentialBase.getValue(PistonBaseBlock.EXTENDED)
                && potentialBase.getValue(PistonHeadBlock.FACING) == armState.getValue(PistonHeadBlock.FACING);
    }

    /**
     * {@code Block.updateFromNeighbourShapes}: six shape updates in
     * {@code UPDATE_SHAPE_ORDER}, level as both the reader and the tick
     * access. Used by the moving entity's settlement, so a transported block
     * that cannot survive its new place (a rail with nothing under it, a
     * piston head whose base is gone) settles to air instead.
     */
    static BlockState updateFromNeighbourShapes(VanillaRedstone level, BlockState state, BlockPos pos) {
        BlockState newState = state;
        BlockPos.MutableBlockPos neighbourPos = new BlockPos.MutableBlockPos();
        for (Direction direction : VanillaRedstone.UPDATE_SHAPE_ORDER) {
            neighbourPos.setWithOffset(pos, direction);
            newState = newState.updateShape(level.levelAccessor(), level.levelAccessor(),
                    pos, direction, neighbourPos, level.getBlockState(neighbourPos), level.randomSource());
        }
        return newState;
    }

    /**
     * {@code PistonStructureResolver}: the block list a move will carry, with
     * the 12-cell push limit and the slime/honey adhesion rules.
     *
     * <p>Slime and honey stick to every movable block except each other, and
     * a sticky block drags its neighbours along the axis the piston is not
     * pushing on ({@code addBranchingBlocks}). Both rules are transcribed
     * verbatim, including the collision reordering that keeps a merged line
     * in push order.
     */
    static final class Resolver {
        static final int MAX_PUSH_DEPTH = 12;

        private final VanillaRedstone level;
        private final BlockPos pistonPos;
        private final boolean extending;
        private final BlockPos startPos;
        private final Direction pushDirection;
        private final List<BlockPos> toPush = Lists.newArrayList();
        private final List<BlockPos> toDestroy = Lists.newArrayList();
        private final Direction pistonDirection;

        Resolver(VanillaRedstone level, BlockPos pistonPos, Direction direction, boolean extending) {
            this.level = level;
            this.pistonPos = pistonPos;
            this.pistonDirection = direction;
            this.extending = extending;
            if (extending) {
                this.pushDirection = direction;
                this.startPos = pistonPos.relative(direction);
            } else {
                this.pushDirection = direction.getOpposite();
                this.startPos = pistonPos.relative(direction, 2);
            }
        }

        boolean resolve() {
            this.toPush.clear();
            this.toDestroy.clear();
            BlockState nextState = this.level.getBlockState(this.startPos);
            if (!PistonLogic.isPushable(this.level, nextState, this.startPos, this.pushDirection, false,
                    this.pistonDirection)) {
                if (this.extending && nextState.getPistonPushReaction() == PushReaction.DESTROY) {
                    this.toDestroy.add(this.startPos);
                    return true;
                }
                return false;
            } else if (!this.addBlockLine(this.startPos, this.pushDirection)) {
                return false;
            } else {
                for (int i = 0; i < this.toPush.size(); i++) {
                    BlockPos pos = this.toPush.get(i);
                    if (isSticky(this.level.getBlockState(pos)) && !this.addBranchingBlocks(pos)) {
                        return false;
                    }
                }
                return true;
            }
        }

        private static boolean isSticky(BlockState state) {
            return state.is(Blocks.SLIME_BLOCK) || state.is(Blocks.HONEY_BLOCK);
        }

        private static boolean canStickToEachOther(BlockState state1, BlockState state2) {
            if (state1.is(Blocks.HONEY_BLOCK) && state2.is(Blocks.SLIME_BLOCK)) {
                return false;
            }
            return state1.is(Blocks.SLIME_BLOCK) && state2.is(Blocks.HONEY_BLOCK)
                    ? false
                    : isSticky(state1) || isSticky(state2);
        }

        private boolean addBlockLine(BlockPos start, Direction direction) {
            BlockState nextState = this.level.getBlockState(start);
            if (nextState.isAir()) {
                return true;
            } else if (!PistonLogic.isPushable(this.level, nextState, start, this.pushDirection, false, direction)) {
                return true;
            } else if (start.equals(this.pistonPos)) {
                return true;
            } else if (this.toPush.contains(start)) {
                return true;
            } else {
                int blockCount = 1;
                if (blockCount + this.toPush.size() > MAX_PUSH_DEPTH) {
                    return false;
                } else {
                    while (isSticky(nextState)) {
                        BlockPos pos = start.relative(this.pushDirection.getOpposite(), blockCount);
                        BlockState previousState = nextState;
                        nextState = this.level.getBlockState(pos);
                        if (nextState.isAir()
                                || !canStickToEachOther(previousState, nextState)
                                || !PistonLogic.isPushable(this.level, nextState, pos, this.pushDirection, false,
                                        this.pushDirection.getOpposite())
                                || pos.equals(this.pistonPos)) {
                            break;
                        }

                        if (++blockCount + this.toPush.size() > MAX_PUSH_DEPTH) {
                            return false;
                        }
                    }

                    int blocksAdded = 0;

                    for (int i = blockCount - 1; i >= 0; i--) {
                        this.toPush.add(start.relative(this.pushDirection.getOpposite(), i));
                        blocksAdded++;
                    }

                    int i = 1;

                    while (true) {
                        BlockPos posx = start.relative(this.pushDirection, i);
                        int collisionPos = this.toPush.indexOf(posx);
                        if (collisionPos > -1) {
                            this.reorderListAtCollision(blocksAdded, collisionPos);

                            for (int j = 0; j <= collisionPos + blocksAdded; j++) {
                                BlockPos blockPos = this.toPush.get(j);
                                if (isSticky(this.level.getBlockState(blockPos))
                                        && !this.addBranchingBlocks(blockPos)) {
                                    return false;
                                }
                            }
                            return true;
                        }

                        nextState = this.level.getBlockState(posx);
                        if (nextState.isAir()) {
                            return true;
                        }

                        if (!PistonLogic.isPushable(this.level, nextState, posx, this.pushDirection, true,
                                this.pushDirection) || posx.equals(this.pistonPos)) {
                            return false;
                        }

                        if (nextState.getPistonPushReaction() == PushReaction.DESTROY) {
                            this.toDestroy.add(posx);
                            return true;
                        }

                        if (this.toPush.size() >= MAX_PUSH_DEPTH) {
                            return false;
                        }

                        this.toPush.add(posx);
                        blocksAdded++;
                        i++;
                    }
                }
            }
        }

        private void reorderListAtCollision(int blocksAdded, int collisionPos) {
            List<BlockPos> head = Lists.newArrayList();
            List<BlockPos> lastLineAdded = Lists.newArrayList();
            List<BlockPos> collisionToLine = Lists.newArrayList();
            head.addAll(this.toPush.subList(0, collisionPos));
            lastLineAdded.addAll(this.toPush.subList(this.toPush.size() - blocksAdded, this.toPush.size()));
            collisionToLine.addAll(this.toPush.subList(collisionPos, this.toPush.size() - blocksAdded));
            this.toPush.clear();
            this.toPush.addAll(head);
            this.toPush.addAll(lastLineAdded);
            this.toPush.addAll(collisionToLine);
        }

        private boolean addBranchingBlocks(BlockPos fromPos) {
            BlockState fromState = this.level.getBlockState(fromPos);

            for (Direction direction : Direction.values()) {
                if (direction.getAxis() != this.pushDirection.getAxis()) {
                    BlockPos neighbourPos = fromPos.relative(direction);
                    BlockState neighbourState = this.level.getBlockState(neighbourPos);
                    if (canStickToEachOther(neighbourState, fromState)
                            && !this.addBlockLine(neighbourPos, direction)) {
                        return false;
                    }
                }
            }

            return true;
        }

        List<BlockPos> getToPush() {
            return this.toPush;
        }

        List<BlockPos> getToDestroy() {
            return this.toDestroy;
        }
    }
}
