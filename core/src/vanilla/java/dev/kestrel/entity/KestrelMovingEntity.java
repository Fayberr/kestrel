package dev.kestrel.entity;

import dev.kestrel.collision.KestrelCollide;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Optional;
import java.util.UUID;

/**
 * The part of vanilla's {@code Entity} (26.2) that moves a non-living entity
 * through blocks, shared by {@link KestrelItemEntity} and
 * {@link KestrelFallingBlockEntity}: {@code Entity.move(MoverType.SELF, delta)}
 * ({@code Entity.java:722-811}) and everything it calls, the supporting-block
 * bookkeeping, {@code checkFallDamage}, bounce restitution, the block speed
 * factor and the on-ground block effects. Owned here and mirrored step for
 * step; the shapes, the collision walk ({@link KestrelCollide}), block
 * friction, speed and bounce values and the vector arithmetic stay vanilla's.
 *
 * <p>Server-side facts this relies on, each from the source: nothing controls
 * these entities, so {@code isLocalInstanceAuthoritative} and
 * {@code canSimulateMovement} are true; the mover is always
 * {@code MoverType.SELF}, so the piston limit never runs; the base
 * {@code maybeBackOffFromEdge} returns its argument (only players override
 * it); {@code isHorizontalCollisionMinor} is the base {@code false}; and
 * both entity types emit {@code MovementEmission.NONE}, so the step sounds
 * and vibrations never run.
 *
 * <p>Not modelled, named where vanilla does it: the per-tick movement list
 * ({@code addMovementThisTick}) and {@code checkInsideBlocks}, which only
 * feed the inside-block effects (G68, G12, G43); the {@code HIT_GROUND}
 * and {@code BOUNCE} game events, which nothing listens to yet; the world
 * border and entity colliders.
 *
 * <p>Thread-safety: tick thread only.
 */
public abstract class KestrelMovingEntity extends KestrelEntity {

    /** {@code Entity.getAirDrag} ({@code Entity.java:1548-1550}), a float. */
    static final float AIR_DRAG = 0.98F;
    /** The flag {@code Entity.isShiftKeyDown} reads. */
    private static final int FLAG_SHIFT_KEY_DOWN = 1;

    /** {@code Entity.random}: the entity's own random. */
    protected final RandomSource random;

    // Entity movement state (Entity.java fields of the same names).
    protected boolean noPhysics;
    private boolean horizontalCollision;
    private boolean minorHorizontalCollision;
    private boolean verticalCollision;
    private boolean verticalCollisionBelow;
    private boolean onGroundNoBlocks;
    private Optional<BlockPos> mainSupportingBlockPos = Optional.empty();
    /** {@code Entity.fallDistance}. */
    protected double fallDistance;
    /** {@code Entity.stuckSpeedMultiplier}: only {@code makeStuckInBlock} (G68) sets it. */
    private Vec3 stuckSpeedMultiplier = Vec3.ZERO;
    /** The vanilla stand-in, built on first use. */
    private EntityProxy proxy;

    protected KestrelMovingEntity(int id, UUID uuid, EntityType<?> type, RandomSource random) {
        super(id, uuid, type);
        this.random = random;
    }

    public RandomSource getRandom() {
        return random;
    }

    public boolean isNoPhysics() {
        return noPhysics;
    }

    public boolean horizontalCollision() {
        return horizontalCollision;
    }

    public Optional<BlockPos> mainSupportingBlockPos() {
        return mainSupportingBlockPos;
    }

    public double getFallDistance() {
        return fallDistance;
    }

    /** This entity's {@link EntityProxy}, its position and fall distance brought up to date. */
    final net.minecraft.world.entity.Entity proxy(EntityLevel level) {
        if (proxy == null) {
            proxy = createProxy(VanillaEntityLevel.of(level));
        }
        proxy.sync();
        return (net.minecraft.world.entity.Entity) proxy;
    }

    /** The vanilla class this type is, on {@code level}. */
    abstract EntityProxy createProxy(VanillaEntityLevel level);

    /** {@code Entity.isSilent}. */
    public boolean isSilent() {
        return getEntityData().get(DATA_SILENT);
    }

    // ---- per-type values ----

    /** {@code Entity.getDefaultGravity}. */
    protected abstract double getDefaultGravity();

    /** {@code Entity.getGravity}: 0 under NoGravity, else the type's default. */
    final double getGravity() {
        return isNoGravity() ? 0.0 : getDefaultGravity();
    }

    /** {@code Entity.getAirDrag}. */
    protected float getAirDrag() {
        return AIR_DRAG;
    }

    /**
     * {@code Entity.isInWater}: {@code wasTouchingWater}, which only
     * {@code baseTick} updates. Each type says what it is.
     */
    protected abstract boolean isInWater();

    /** {@code Entity.getBlockPosBelowThatAffectsMyMovement}: 0.500001 below the feet. */
    protected BlockPos getBlockPosBelowThatAffectsMyMovement(EntityLevel level) {
        return getOnPos(level, 0.500001F);
    }

    /** {@code Entity.getEntityBounciness}: the base 0 for both types. */
    protected double getEntityBounciness() {
        return 0.0;
    }

    boolean isShiftKeyDown() {
        return (getEntityData().get(DATA_SHARED_FLAGS_ID) & 1 << FLAG_SHIFT_KEY_DOWN) != 0;
    }

    /** {@code Entity.isSuppressingBounce}, {@code isSteppingCarefully} and {@code isDescending}. */
    boolean isSuppressingBounce() {
        return isShiftKeyDown();
    }

    /**
     * {@code Entity.causeFallDamage} ({@code Entity.java:1593-1600}): an
     * entity type that is not fall-damage immune passes the fall on to its
     * passengers. Returns whether damage was dealt, which the base never does.
     */
    boolean causeFallDamage(EntityLevel level, double distance, float damageModifier) {
        if (getType().builtInRegistryHolder().is(EntityTypeTags.FALL_DAMAGE_IMMUNE)) {
            return false;
        }
        for (KestrelEntity passenger : getPassengers()) {
            if (passenger instanceof KestrelMovingEntity moving) {
                moving.causeFallDamage(level, distance, damageModifier);
            }
        }
        return false;
    }

    /**
     * {@code onState.getBlock().fallOn(level, onState, pos, this, fallDistance)}
     * from {@code checkFallDamage}: vanilla's own override for the block,
     * handed the adapter level and this entity's proxy, so no copied list of
     * overrides can go stale. What they do to a non-living entity: the base
     * and hay, slime and upward dripstone tips call
     * {@link #causeFallDamage} (dripstone adds 2.5); a bed halves the
     * distance first; farmland draws from the level random before its
     * living-entity test; honey plays its slide sound and raises entity event
     * 54; powder snow and turtle eggs do nothing to one.
     */
    final void fallOn(EntityLevel level, BlockState onState, BlockPos pos, double distance) {
        onState.getBlock().fallOn(VanillaEntityLevel.of(level), onState, pos, proxy(level), distance);
    }

    // ---- Entity.move ----

    /** {@code Entity.applyGravity} ({@code :1541-1546}). */
    final void applyGravity() {
        double gravity = getGravity();
        if (gravity != 0.0) {
            setDeltaMovement(getDeltaMovement().add(0.0, -gravity, 0.0));
        }
    }

    /** {@code Entity.move(MoverType.SELF, delta)} ({@code Entity.java:722-811}). */
    final void move(EntityLevel level, Vec3 delta) {
        if (noPhysics) {
            // :723-728
            setPos(position().x + delta.x, position().y + delta.y, position().z + delta.z);
            horizontalCollision = false;
            verticalCollision = false;
            verticalCollisionBelow = false;
            minorHorizontalCollision = false;
            return;
        }
        // :739-746
        if (stuckSpeedMultiplier.lengthSqr() > 1.0E-7) {
            delta = delta.multiply(stuckSpeedMultiplier);
            stuckSpeedMultiplier = Vec3.ZERO;
            setDeltaMovement(Vec3.ZERO);
        }
        // :748 maybeBackOffFromEdge: the base returns delta.
        Vec3 movement = collide(level, delta);
        double movementLength = movement.lengthSqr();
        if (movementLength > 1.0E-7 || delta.lengthSqr() - movementLength < 1.0E-7) {
            // :752-760
            if (fallDistance != 0.0 && movementLength >= 1.0) {
                double checkDistance = Math.min(movement.length(), 8.0);
                Vec3 checkTo = position().add(movement.normalize().scale(checkDistance));
                HitResult hitResult = level.blocks().clip(new ClipContext(position(), checkTo,
                        ClipContext.Block.FALLDAMAGE_RESETTING, ClipContext.Fluid.WATER, collisionContext(level)));
                if (hitResult.getType() != HitResult.Type.MISS) {
                    resetFallDistance();
                }
            }
            // :762-765; addMovementThisTick only feeds checkInsideBlocks.
            Vec3 newPosition = position().add(movement);
            setPos(newPosition.x, newPosition.y, newPosition.z);
        }

        // :770-784
        boolean xCollision = !Mth.equal(delta.x, movement.x);
        boolean zCollision = !Mth.equal(delta.z, movement.z);
        horizontalCollision = xCollision || zCollision;
        boolean movedVertically = Math.abs(delta.y) > 0.0;
        verticalCollision = delta.y != movement.y;
        verticalCollisionBelow = verticalCollision && delta.y < 0.0;
        setOnGroundWithMovement(level, verticalCollisionBelow, movement);
        minorHorizontalCollision = false;

        // :786-790
        BlockPos effectPos = getOnPosLegacy(level);
        BlockState effectState = level.blocks().getBlockState(effectPos);
        checkFallDamage(level, movement.y, onGround(), effectState, effectPos);
        if (isRemoved()) {
            return;
        }
        // :795-797
        if (movedVertically && verticalCollision || horizontalCollision) {
            restituteMovementAfterCollisions(effectState, xCollision, zCollision, movement);
        }
        // :799-804: MovementEmission.NONE for both types.
        // :806-807
        float blockSpeedFactor = getBlockSpeedFactor(level);
        setDeltaMovement(getDeltaMovement().multiply(blockSpeedFactor, 1.0, blockSpeedFactor));
    }

    /**
     * {@code Entity.collide} ({@code Entity.java:1150-1178}), delegated to
     * {@link KestrelCollide#collide}. Both types have {@code maxUpStep} 0,
     * so the step-up pass never runs.
     */
    private Vec3 collide(EntityLevel level, Vec3 movement) {
        KestrelCollide.Vec3Resolve r = KestrelCollide.collide(level.blocks(), collisionContext(level),
                getBoundingBox(), movement.x, movement.y, movement.z, 0.0F, onGround(), level.shapeCache());
        return new Vec3(r.x(), r.y(), r.z());
    }

    /** {@code Entity.checkFallDamage} ({@code Entity.java:1569-1587}). */
    private void checkFallDamage(EntityLevel level, double ya, boolean onGround, BlockState onState, BlockPos pos) {
        if (!isInWater() && ya < 0.0) {
            fallDistance -= (float) ya;
        }
        if (onGround) {
            if (fallDistance > 0.0) {
                fallOn(level, onState, pos, fallDistance);
                // gameEvent(HIT_GROUND): no game event listeners exist.
            }
            resetFallDistance();
        }
    }

    /** {@code Entity.resetFallDistance}. */
    final void resetFallDistance() {
        fallDistance = 0.0;
    }

    /** {@code Entity.setOnGroundWithMovement} plus {@code checkSupportingBlock} ({@code :686-716}). */
    private void setOnGroundWithMovement(EntityLevel level, boolean onGround, Vec3 movement) {
        setOnGround(onGround);
        if (onGround) {
            AABB boundingBox = getBoundingBox();
            AABB testArea = new AABB(boundingBox.minX, boundingBox.minY - 1.0E-6, boundingBox.minZ,
                    boundingBox.maxX, boundingBox.minY, boundingBox.maxZ);
            Optional<BlockPos> supportingBlock = findSupportingBlock(level, testArea);
            if (supportingBlock.isPresent() || onGroundNoBlocks) {
                mainSupportingBlockPos = supportingBlock;
            } else if (movement != null) {
                AABB onGroundCollisionTestArea = testArea.move(-movement.x, 0.0, -movement.z);
                supportingBlock = findSupportingBlock(level, onGroundCollisionTestArea);
                mainSupportingBlockPos = supportingBlock;
            }
            onGroundNoBlocks = supportingBlock.isEmpty();
        } else {
            onGroundNoBlocks = false;
            if (mainSupportingBlockPos.isPresent()) {
                mainSupportingBlockPos = Optional.empty();
            }
        }
    }

    /** {@code Entity.restituteMovementAfterCollisions} ({@code :813-854}). */
    private void restituteMovementAfterCollisions(BlockState effectState, boolean xCollision, boolean zCollision,
            Vec3 movement) {
        double restitution = isSuppressingBounce() ? 0.0 : getEntityBounciness();
        Vec3 currentMovement = getDeltaMovement();
        Vec3 movementAfterBounce = currentMovement;
        if (xCollision) {
            movementAfterBounce = currentMovement.with(Direction.Axis.X, -currentMovement.x * restitution);
        }
        if (zCollision) {
            movementAfterBounce = movementAfterBounce.with(Direction.Axis.Z, -currentMovement.z * restitution);
        }
        boolean bounced = restitution > 0.0 && (xCollision || zCollision);
        if (verticalCollision) {
            if (verticalCollisionBelow) {
                // getEffectiveGravity is getGravity for both types.
                restitution = !(-currentMovement.y < getGravity()) && !isSuppressingBounce()
                        && !effectState.is(BlockTags.SUPPRESSES_BOUNCE)
                        ? Math.max(restitution, getBlockBounciness(effectState.getBlock()))
                        : 0.0;
            }
            double gravityCompensation;
            double effectiveDrag;
            if (restitution > 0.0) {
                double portionWithMovement = movement.y / currentMovement.y;
                gravityCompensation = portionWithMovement * getGravity();
                effectiveDrag = Mth.lerp(portionWithMovement, 1.0, (double) getAirDrag());
                bounced = true;
            } else {
                gravityCompensation = 0.0;
                effectiveDrag = 1.0;
            }
            movementAfterBounce = movementAfterBounce.with(Direction.Axis.Y,
                    (gravityCompensation - currentMovement.y) * effectiveDrag * restitution);
        }
        if (bounced) {
            // gameEvent(BOUNCE): no game event listeners exist.
            syncPosition = true;
        }
        setDeltaMovement(movementAfterBounce);
    }

    /** {@code Entity.getBlockBounciness} ({@code :856-863}): a non-living entity takes 0.8 of it. */
    private static double getBlockBounciness(Block block) {
        float blockBounciness = block.getBounceRestitution();
        blockBounciness *= 0.8F;
        return blockBounciness;
    }

    /** {@code Entity.getBlockSpeedFactor} ({@code :1096-1104}). */
    private float getBlockSpeedFactor(EntityLevel level) {
        BlockState state = level.blocks().getBlockState(blockPosition());
        float speedFactorHere = state.getBlock().getSpeedFactor();
        if (!state.is(Blocks.WATER) && !state.is(Blocks.BUBBLE_COLUMN)) {
            return speedFactorHere == 1.0
                    ? level.blocks().getBlockState(getBlockPosBelowThatAffectsMyMovement(level)).getBlock().getSpeedFactor()
                    : speedFactorHere;
        }
        return speedFactorHere;
    }

    /** {@code Entity.getOnPosLegacy} ({@code :1057-1059}). */
    final BlockPos getOnPosLegacy(EntityLevel level) {
        return getOnPos(level, 0.2F);
    }

    /** {@code Entity.getOnPos(float)} ({@code :1069-1086}). */
    final BlockPos getOnPos(EntityLevel level, float offset) {
        if (mainSupportingBlockPos.isPresent()) {
            BlockPos getOnPos = mainSupportingBlockPos.get();
            if (!(offset > 1.0E-5F)) {
                return getOnPos;
            }
            BlockState belowState = level.blocks().getBlockState(getOnPos);
            return (!(offset <= 0.5) || !belowState.is(BlockTags.FENCES))
                    && !belowState.is(BlockTags.WALLS)
                    && !(belowState.getBlock() instanceof FenceGateBlock)
                    ? getOnPos.atY(Mth.floor(position().y - offset))
                    : getOnPos;
        }
        int xTruncated = Mth.floor(position().x);
        int yTruncatedBelow = Mth.floor(position().y - offset);
        int zTruncated = Mth.floor(position().z);
        return new BlockPos(xTruncated, yTruncatedBelow, zTruncated);
    }

    /**
     * {@code Entity.applyEffectsFromBlocks} ({@code :914-925}, {@code :960-986})
     * as far as it is modelled: when affected by blocks and on the ground,
     * vanilla's own {@code stepOn} for the block at {@code getOnPosLegacy},
     * through the adapter level and the proxy. For a non-living entity that
     * is the slime block's damping ({@code SlimeBlock.java:29-38}) and
     * redstone ore lighting up, whose server-side particle loop still draws
     * from the level random ({@code RedStoneOreBlock.java:45-51},
     * {@code :108-120}). The inside-block effects and the fire bookkeeping
     * are not modelled (G68, G12, G43).
     */
    final void applyEffectsFromBlocks(EntityLevel level) {
        if (isRemoved() || noPhysics) {
            return;
        }
        if (onGround()) {
            BlockPos effectPos = getOnPosLegacy(level);
            BlockState effectState = level.blocks().getBlockState(effectPos);
            effectState.getBlock().stepOn(VanillaEntityLevel.of(level), effectPos, effectState, proxy(level));
        }
    }

    // ---- collision queries ----

    /**
     * {@code CollisionContext.of(this)}: vanilla's own context over this
     * entity's {@link EntityProxy}, so a shape that looks through the
     * entity (powder snow reads its class and {@code fallDistance},
     * {@code PowderSnowBlock.java:116-132}) sees what it sees in vanilla.
     */
    final CollisionContext collisionContext(EntityLevel level) {
        return CollisionContext.of(proxy(level));
    }

    /**
     * {@code CollisionGetter.noCollision(entity, box)}: no block collider
     * intersects {@code box}. There are no entity colliders and the border
     * is vanilla's default.
     */
    final boolean noCollision(EntityLevel level, AABB box) {
        boolean[] hit = {false};
        KestrelCollide.forEachBlockCollider(level.blocks(), collisionContext(level), box, level.shapeCache(),
                (x, y, z, shape) -> hit[0] = true);
        return !hit[0];
    }

    /** {@code CollisionGetter.findSupportingBlock} ({@code CollisionGetter.java:140-155}). */
    private Optional<BlockPos> findSupportingBlock(EntityLevel level, AABB box) {
        BlockPos[] mainSupport = {null};
        double[] mainSupportDistance = {Double.MAX_VALUE};
        Vec3 from = position();
        KestrelCollide.forEachBlockCollider(level.blocks(), collisionContext(level), box, level.shapeCache(),
                (x, y, z, shape) -> {
                    BlockPos pos = new BlockPos(x, y, z);
                    double distance = pos.distToCenterSqr(from);
                    if (distance < mainSupportDistance[0]
                            || distance == mainSupportDistance[0]
                                    && (mainSupport[0] == null || mainSupport[0].compareTo(pos) < 0)) {
                        mainSupport[0] = pos;
                        mainSupportDistance[0] = distance;
                    }
                });
        return Optional.ofNullable(mainSupport[0]);
    }

    /** {@code Entity.moveTowardsClosestSpace} ({@code Entity.java:2920-2949}). */
    final void moveTowardsClosestSpace(EntityLevel level, double x, double y, double z) {
        BlockGetter blocks = level.blocks();
        BlockPos pos = BlockPos.containing(x, y, z);
        Vec3 delta = new Vec3(x - pos.getX(), y - pos.getY(), z - pos.getZ());
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        Direction closestDirection = Direction.UP;
        double closest = Double.MAX_VALUE;
        for (Direction direction : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP}) {
            neighborPos.setWithOffset(pos, direction);
            if (!blocks.getBlockState(neighborPos).isCollisionShapeFullBlock(blocks, neighborPos)) {
                double d = delta.get(direction.getAxis());
                double orientedDelta = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 - d : d;
                if (orientedDelta < closest) {
                    closest = orientedDelta;
                    closestDirection = direction;
                }
            }
        }
        float speed = random.nextFloat() * 0.2F + 0.1F;
        float step = closestDirection.getAxisDirection().getStep();
        Vec3 scaledMovement = getDeltaMovement().scale(0.75);
        if (closestDirection.getAxis() == Direction.Axis.X) {
            setDeltaMovement(new Vec3(step * speed, scaledMovement.y, scaledMovement.z));
        } else if (closestDirection.getAxis() == Direction.Axis.Y) {
            setDeltaMovement(new Vec3(scaledMovement.x, step * speed, scaledMovement.z));
        } else if (closestDirection.getAxis() == Direction.Axis.Z) {
            setDeltaMovement(new Vec3(scaledMovement.x, scaledMovement.y, step * speed));
        }
    }
}
