package dev.kestrel.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * A block in flight: Kestrel's tick for vanilla's
 * {@code net/minecraft/world/entity/item/FallingBlockEntity.java} (26.2),
 * mirrored step for step so {@code FallingBlockParityCheck} can diff it
 * against a real vanilla {@code FallingBlockEntity}.
 *
 * <p>Owned here: the decision sequence of {@code FallingBlockEntity.tick}
 * and {@code causeFallDamage}. The movement is the shared
 * {@link KestrelMovingEntity}. Delegated to vanilla: shapes,
 * {@code canSurvive}, {@code canBeReplaced}, the {@code Fallable} hooks
 * (through {@link VanillaEntityLevel}), {@code AnvilBlock.damage} and
 * {@code Vec3} arithmetic. Every block write goes through the live
 * {@code TickLoop.setBlock}.
 *
 * <p>Not modelled: hurting entities under the block (no living entities
 * yet, G15 and G22), {@code blockData} (only commands and structure
 * templates fill it), {@code handlePortal} (G43), and persistence (S14
 * slice 4).
 *
 * <p>Thread-safety: tick thread only.
 */
public final class KestrelFallingBlockEntity extends KestrelMovingEntity {
    // FallingBlockEntity.java:75, defined after the base Entity layout: id 8.
    private static final EntityDataAccessor<BlockPos> DATA_START_POS =
            SynchedEntityData.defineId(KestrelFallingBlockEntity.class, EntityDataSerializers.BLOCK_POS);
    /** {@code FallingBlockEntity.getDefaultGravity} ({@code :143-146}). */
    static final double GRAVITY = 0.04;

    private BlockState blockState;
    private int time;
    private boolean dropItem = true;
    private boolean cancelDrop;
    private boolean hurtEntities;
    private int fallDamageMax = 40;
    private float fallDamagePerDistance;
    private EntityWorld world;

    private KestrelFallingBlockEntity(int id, UUID uuid, RandomSource random, BlockState state) {
        super(id, uuid, EntityTypes.FALLING_BLOCK, random);
        this.blockState = state;
    }

    /**
     * The constructor half of {@code FallingBlockEntity.fall}
     * ({@code :93-100}, {@code :81-91}): the UUID drawn from the entity's
     * random, the block centre at the block's bottom, {@code WATERLOGGED}
     * cleared, the start position.
     */
    static KestrelFallingBlockEntity create(BlockPos pos, BlockState state, RandomSource random) {
        BlockState carried = state.hasProperty(BlockStateProperties.WATERLOGGED)
                ? state.setValue(BlockStateProperties.WATERLOGGED, false) : state;
        KestrelFallingBlockEntity entity = new KestrelFallingBlockEntity(
                EntityIds.claim(), Mth.createInsecureUUID(random), random, carried);
        entity.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        entity.xo = entity.position().x;
        entity.yo = entity.position().y;
        entity.zo = entity.position().z;
        entity.setStartPos(entity.blockPosition());
        return entity;
    }

    void attach(EntityWorld world) {
        this.world = world;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_START_POS, BlockPos.ZERO);
    }

    public BlockState getBlockState() {
        return blockState;
    }

    public int getTime() {
        return time;
    }

    public BlockPos getStartPos() {
        return getEntityData().get(DATA_START_POS);
    }

    public void setStartPos(BlockPos pos) {
        getEntityData().set(DATA_START_POS, pos);
    }

    /** {@code FallingBlockEntity.setHurtsEntities} ({@code :310-314}). */
    public void setHurtsEntities(float perDistance, int maximum) {
        hurtEntities = true;
        fallDamagePerDistance = perDistance;
        fallDamageMax = maximum;
    }

    /** {@code FallingBlock.falling}, protected: resolved once, dispatched virtually (so {@code AnvilBlock}'s override runs). */
    private static final java.lang.invoke.MethodHandle FALLING = fallingHandle();

    private static java.lang.invoke.MethodHandle fallingHandle() {
        try {
            return java.lang.invoke.MethodHandles.privateLookupIn(FallingBlock.class, java.lang.invoke.MethodHandles.lookup())
                    .findVirtual(FallingBlock.class, "falling",
                            java.lang.invoke.MethodType.methodType(void.class, FallingBlockEntity.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The {@code this.falling(entity)} call in {@code FallingBlock.tick}
     * ({@code FallingBlock.java:49-53}): vanilla's own {@code falling} for
     * the carried block, handed this entity's proxy, whose
     * {@code setHurtsEntities} lands here. Today only {@code AnvilBlock}
     * overrides it ({@code AnvilBlock.java:83-85}).
     */
    void falling(EntityLevel level, Block block) {
        if (block instanceof FallingBlock fallingBlock) {
            try {
                FALLING.invoke(fallingBlock, hookEntity(level));
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }

    boolean hurtEntities() {
        return hurtEntities;
    }

    float fallDamagePerDistance() {
        return fallDamagePerDistance;
    }

    int fallDamageMax() {
        return fallDamageMax;
    }

    /** The add-entity packet's data: {@code Block.getId(blockState)} ({@code FallingBlockEntity.getAddEntityPacket}). */
    @Override
    public int addEntityData() {
        return Block.getId(blockState);
    }

    @Override
    EntityProxy createProxy(VanillaEntityLevel level) {
        return new EntityProxy.FallingBlock(level, this);
    }

    @Override
    protected double getDefaultGravity() {
        return GRAVITY;
    }

    /**
     * {@code wasTouchingWater} stays false: {@code FallingBlockEntity.tick}
     * never calls {@code baseTick}, the only place that updates it.
     */
    @Override
    protected boolean isInWater() {
        return false;
    }

    /** {@code FallingBlockEntity.tick} ({@code :148-244}). */
    @Override
    public void tick(EntityLevel level) {
        if (blockState.isAir()) {
            discard();
            return;
        }
        // :153 the block is taken before move: a damage roll in move does not change the drop.
        Block block = blockState.getBlock();
        time++;
        applyGravity();
        move(level, getDeltaMovement());
        applyEffectsFromBlocks(level);
        // :158 handlePortal: G43.
        if (!isRemoved()) {
            BlockPos pos = blockPosition();
            boolean isConcrete = blockState.getBlock() instanceof ConcretePowderBlock;
            boolean isStuckInWater = isConcrete && level.blocks().getFluidState(pos).is(FluidTags.WATER);
            double moveVec = getDeltaMovement().lengthSqr();
            if (isConcrete && moveVec > 1.0) {
                // :165-170
                var clip = level.blocks().clip(new ClipContext(new Vec3(xo, yo, zo), position(),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, collisionContext(level)));
                if (clip.getType() != HitResult.Type.MISS
                        && level.blocks().getFluidState(clip.getBlockPos()).is(FluidTags.WATER)) {
                    pos = clip.getBlockPos();
                    isStuckInWater = true;
                }
            }
            if (!onGround() && !isStuckInWater) {
                // :173-180
                if (time > 100 && (pos.getY() <= level.getMinY() || pos.getY() > level.getMaxY()) || time > 600) {
                    if (dropsItems(level)) {
                        spawnDrop(block);
                    }
                    discard();
                }
            } else {
                land(level, pos, block, isConcrete, isStuckInWater);
            }
        }
        setDeltaMovement(getDeltaMovement().scale(getAirDrag()));
    }

    /** The landing branch, {@code :181-239}. */
    private void land(EntityLevel level, BlockPos pos, Block block, boolean isConcrete, boolean isStuckInWater) {
        BlockState currentState = level.blocks().getBlockState(pos);
        setDeltaMovement(getDeltaMovement().multiply(0.7, -0.5, 0.7));
        if (currentState.is(Blocks.MOVING_PISTON)) {
            return;
        }
        if (cancelDrop) {
            // :234-237
            discard();
            callOnBrokenAfterFall(level, block, pos);
            return;
        }
        boolean mayReplace = currentState.canBeReplaced(new DirectionalPlaceContext(
                vanillaLevel(level), pos, Direction.DOWN, ItemStack.EMPTY, Direction.UP));
        boolean wouldContinueFalling = FallingBlock.isFree(level.blocks().getBlockState(pos.below()))
                && (!isConcrete || !isStuckInWater);
        boolean wouldSurvive = blockState.canSurvive(level.levelReader(), pos) && !wouldContinueFalling;
        if (mayReplace && wouldSurvive) {
            if (blockState.hasProperty(BlockStateProperties.WATERLOGGED)
                    && level.blocks().getFluidState(pos).is(Fluids.WATER)) {
                blockState = blockState.setValue(BlockStateProperties.WATERLOGGED, true);
            }
            if (level.setBlock(pos, blockState, 3)) {
                // :197-199 the extra block update to the players tracking the entity.
                level.landingBlockUpdate(this, pos, level.blocks().getBlockState(pos));
                discard();
                if (block instanceof Fallable fallable) {
                    fallable.onLand(vanillaLevel(level), pos, blockState, currentState, hookEntity(level));
                }
                // :205-221 blockData: never set here.
            } else if (dropsItems(level)) {
                // :222-226: without drops the entity lives on.
                discard();
                callOnBrokenAfterFall(level, block, pos);
                spawnDrop(block);
            }
        } else {
            // :227-233
            discard();
            if (dropsItems(level)) {
                callOnBrokenAfterFall(level, block, pos);
                spawnDrop(block);
            }
        }
    }

    /**
     * {@code FallingBlockEntity.causeFallDamage} ({@code :252-280}) without
     * the living entities to hurt: the anvil roll, from the entity's own
     * random, runs whether or not anyone is under it.
     */
    @Override
    boolean causeFallDamage(EntityLevel level, double distance, float damageModifier) {
        if (!hurtEntities) {
            return false;
        }
        int fallDistanceInt = Mth.ceil(distance - 1.0);
        if (fallDistanceInt < 0) {
            return false;
        }
        float damage = Math.min(Mth.floor(fallDistanceInt * fallDamagePerDistance), fallDamageMax);
        // :266 getEntities(...).forEach(hurt): no living entities exist.
        boolean isAnvil = blockState.is(BlockTags.ANVIL);
        if (isAnvil && damage > 0.0F && random.nextFloat() < 0.05F + fallDistanceInt * 0.05F) {
            BlockState newBlockState = AnvilBlock.damage(blockState);
            if (newBlockState == null) {
                cancelDrop = true;
            } else {
                blockState = newBlockState;
            }
        }
        return false;
    }

    /** {@code FallingBlockEntity.callOnBrokenAfterFall} ({@code :246-250}). */
    private void callOnBrokenAfterFall(EntityLevel level, Block block, BlockPos pos) {
        if (block instanceof Fallable fallable) {
            fallable.onBrokenAfterFall(vanillaLevel(level), pos, hookEntity(level));
        }
    }

    /** {@code this.dropItem && serverLevel.getGameRules().get(GameRules.ENTITY_DROPS)} ({@code :175}, {@code :222}, {@code :229}). */
    private boolean dropsItems(EntityLevel level) {
        return dropItem && level.gameRules().get(GameRules.ENTITY_DROPS);
    }

    /** {@code spawnAtLocation(serverLevel, block)}: the block captured before {@code move} ({@code :153}). */
    private void spawnDrop(Block block) {
        if (world != null) {
            world.spawnFallingDrop(this, block);
        }
    }

    private static VanillaEntityLevel vanillaLevel(EntityLevel level) {
        return VanillaEntityLevel.of(level);
    }

    /** The {@code FallingBlockEntity} the {@code Fallable} hooks receive: this entity's proxy. */
    private FallingBlockEntity hookEntity(EntityLevel level) {
        return (FallingBlockEntity) proxy(level);
    }
}
