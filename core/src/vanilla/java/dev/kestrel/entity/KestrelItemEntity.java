package dev.kestrel.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A dropped item stack in the world: Kestrel's own tick for vanilla's
 * {@code net/minecraft/world/entity/item/ItemEntity.java} (26.2), owned
 * independently and mirrored step for step, so {@code ItemEntityTickCheck}
 * and {@code ItemEntityMergeCheck} can diff it tick for tick, in raw bits,
 * against a real vanilla {@code ItemEntity}.
 *
 * <p>Owned here: the decision sequence of {@code ItemEntity.tick} and the
 * parts of {@code Entity.baseTick} an item on terrain goes through. The
 * movement ({@code Entity.move} and what it calls, fall distance included)
 * is the shared {@link KestrelMovingEntity}, which delegates collision,
 * shapes, block friction, speed and bounce values and vector arithmetic to
 * vanilla.
 *
 * <p>Deliberately not modelled, each named where vanilla does it: water
 * and lava movement and fluid interaction (G24), fire, pickup (G19 slice 3),
 * {@code checkInsideBlocks} (G68: cobwebs, berry bushes, honey, powder snow,
 * fire, cactus, magma; G12: pressure plates), entity colliders (no boats
 * or shulkers exist) and the world border collider. {@code bobOffs} is drawn
 * only to keep the random sequence vanilla's; it is a client render value.
 *
 * <p>Thread-safety: tick thread only.
 */
public final class KestrelItemEntity extends KestrelMovingEntity {

    // ItemEntity.java:40, defined after the base Entity layout: id 8.
    private static final EntityDataAccessor<ItemStack> DATA_ITEM =
            SynchedEntityData.defineId(KestrelItemEntity.class, EntityDataSerializers.ITEM_STACK);

    /** {@code ItemEntity.LIFETIME} ({@code :43}). */
    public static final int LIFETIME = 6000;
    /** {@code ItemEntity.INFINITE_PICKUP_DELAY} ({@code :44}). */
    public static final int INFINITE_PICKUP_DELAY = 32767;
    /** {@code ItemEntity.INFINITE_LIFETIME} ({@code :45}). */
    public static final int INFINITE_LIFETIME = -32768;
    /** {@code ItemEntity.getDefaultGravity} ({@code :115-118}); the base {@code Entity} has 0.0. */
    static final double DEFAULT_GRAVITY = 0.04;

    /** {@code ItemEntity.bobOffs}: client render phase, drawn to keep the random sequence. */
    public final float bobOffs;
    private int age;
    private int pickupDelay;
    private @Nullable UUID target;
    private @Nullable UUID thrower;
    private @Nullable EntityWorld world;

    /**
     * {@code ItemEntity(EntityType, Level)} ({@code :58-61}) after
     * {@code Entity.<init>}: {@code bobOffs} is a field initialiser, then
     * the constructor draws the yaw. The caller has already drawn the UUID
     * from {@code random} ({@code Entity.java:287}), as vanilla's field
     * initialiser does, so the draw order is vanilla's.
     */
    KestrelItemEntity(int id, UUID uuid, RandomSource random) {
        super(id, uuid, EntityTypes.ITEM, random);
        this.bobOffs = random.nextFloat() * (float) Math.PI * 2.0F;
        setYRot(random.nextFloat() * 360.0F);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // ItemEntity.java:111-113
        builder.define(DATA_ITEM, ItemStack.EMPTY);
    }

    // ---- the item ----

    public ItemStack getItem() {
        return getEntityData().get(DATA_ITEM);
    }

    public void setItem(ItemStack stack) {
        getEntityData().set(DATA_ITEM, stack);
    }

    public int getAge() {
        return age;
    }

    /** For the gate's lifetime boundary scenarios; vanilla restores it from the save's "Age". */
    void setAge(int age) {
        this.age = age;
    }

    public int getPickupDelay() {
        return pickupDelay;
    }

    public void setPickUpDelay(int ticks) {
        this.pickupDelay = ticks;
    }

    /** {@code ItemEntity.setDefaultPickUpDelay}. */
    public void setDefaultPickUpDelay() {
        this.pickupDelay = 10;
    }

    /** {@code ItemEntity.setNoPickUpDelay} ({@code :404-406}). */
    public void setNoPickUpDelay() {
        this.pickupDelay = 0;
    }

    /** {@code ItemEntity.setNeverPickUp} ({@code :408-410}). */
    public void setNeverPickUp() {
        this.pickupDelay = INFINITE_PICKUP_DELAY;
    }

    /** {@code ItemEntity.hasPickUpDelay} ({@code :416-418}). */
    public boolean hasPickUpDelay() {
        return this.pickupDelay > 0;
    }

    /** {@code ItemEntity.setUnlimitedLifetime} ({@code :420-422}). */
    public void setUnlimitedLifetime() {
        this.age = INFINITE_LIFETIME;
    }

    /** {@code ItemEntity.setExtendedLifetime} ({@code :424-426}). */
    public void setExtendedLifetime() {
        this.age = -6000;
    }

    public @Nullable UUID getTarget() {
        return target;
    }

    public void setTarget(@Nullable UUID target) {
        this.target = target;
    }

    public @Nullable UUID getThrower() {
        return thrower;
    }

    public void setThrower(@Nullable UUID thrower) {
        this.thrower = thrower;
    }

    public @Nullable EntityWorld world() {
        return world;
    }

    void attach(EntityWorld world) {
        this.world = world;
    }

    /** {@code ItemEntity.playerTouch}, {@code ItemEntity.java:340-356}. */
    public void playerTouch(Player player) {
        if (player.level().isClientSide()) {
            return;
        }
        ItemStack itemStack = getItem();
        Item item = itemStack.getItem();
        int orgCount = itemStack.getCount();
        if (pickupDelay == 0 && (target == null || target.equals(player.getUUID())) && player.getInventory().add(itemStack)) {
            EntityLevel el = player.level() instanceof VanillaEntityLevel vLevel
                    ? vLevel.target()
                    : (this.world != null ? this.world.level() : null);
            ItemEntity proxy = el != null ? (ItemEntity) proxy(el) : null;
            if (proxy != null) {
                player.take(proxy, orgCount);
            }
            if (itemStack.isEmpty()) {
                discard();
                itemStack.setCount(orgCount);
            }
            // A partial pickup shrinks the synced stack in place, as vanilla does:
            // no setItem, so the entity data is not marked dirty and no
            // SetEntityData goes out for the remainder.

            player.awardStat(Stats.ITEM_PICKED_UP.get(item), orgCount);
            if (proxy != null) {
                player.onItemPickup(proxy);
            }
        }
    }

    // ---- the tick ----

    /** {@code ItemEntity.tick}, {@code ItemEntity.java:121-195}. */
    @Override
    public void tick(EntityLevel level) {
        if (getItem().isEmpty()) {
            discard();
            return;
        }
        baseTick(level);
        // :126-128
        if (pickupDelay > 0 && pickupDelay != INFINITE_PICKUP_DELAY) {
            pickupDelay--;
        }

        // :130-133
        xo = position().x;
        yo = position().y;
        zo = position().z;
        Vec3 oldMovement = getDeltaMovement();
        // :134-140. Water and lava movement are not modelled (G24): gravity.
        applyGravity();

        // :142-149, the server branch.
        noPhysics = !noCollision(level, getBoundingBox().deflate(1.0E-7));
        if (noPhysics) {
            AABB box = getBoundingBox();
            moveTowardsClosestSpace(level, position().x, (box.minY + box.maxY) / 2.0, position().z);
        }

        // :151-169
        if (onGround() && !(getDeltaMovement().horizontalDistanceSqr() > 1.0E-5F)
                && (tickCount + getId()) % 4 != 0) {
            applyEffectsFromBlocks(level);
        } else {
            move(level, getDeltaMovement());
            applyEffectsFromBlocks(level);
            float airDrag = AIR_DRAG;
            float groundFriction = airDrag;
            if (onGround()) {
                groundFriction = airDrag * level.blocks()
                        .getBlockState(getBlockPosBelowThatAffectsMyMovement(level)).getBlock().getFriction();
            }
            setDeltaMovement(getDeltaMovement().multiply(groundFriction, airDrag, groundFriction));
            if (onGround()) {
                Vec3 movement = getDeltaMovement();
                if (movement.y < 0.0) {
                    setDeltaMovement(movement.multiply(1.0, -0.5, 1.0));
                }
            }
        }

        // :171-177 mergeWithNeighbours
        boolean moved = Mth.floor(xo) != Mth.floor(position().x)
                || Mth.floor(yo) != Mth.floor(position().y)
                || Mth.floor(zo) != Mth.floor(position().z);
        int rate = moved ? 2 : 40;
        if (tickCount % rate == 0 && isMergable()) {
            mergeWithNeighbours(level);
        }

        // :179-181
        if (age != INFINITE_LIFETIME) {
            age++;
        }

        // :183 fluid interaction is not modelled; :184-189
        double value = getDeltaMovement().subtract(oldMovement).lengthSqr();
        if (value > 0.01) {
            needsSync = true;
        }

        // :191-193
        if (age >= LIFETIME) {
            discard();
        }
    }

    /**
     * The parts of {@code Entity.baseTick} ({@code Entity.java:521-575})
     * that reach an item on dry land: the dismount of a removed vehicle and
     * {@code checkBelowWorld}. Fluids, fire, portals and freezing are not
     * modelled.
     */
    private void baseTick(EntityLevel level) {
        KestrelEntity vehicle = getVehicle();
        if (vehicle != null && vehicle.isRemoved()) {
            stopRiding();
        }
        // Entity.checkBelowWorld (:585-589) -> onBelowWorld -> discard.
        if (position().y < level.getMinY() - 64) {
            discard();
        }
    }

    @Override
    EntityProxy createProxy(VanillaEntityLevel level) {
        return new EntityProxy.Item(level, this);
    }

    @Override
    protected double getDefaultGravity() {
        return DEFAULT_GRAVITY;
    }

    /**
     * {@code wasTouchingWater}: water is not modelled for items (G24), so
     * an item on terrain is never in it.
     */
    @Override
    protected boolean isInWater() {
        return false;
    }

    /** {@code ItemEntity.getBlockPosBelowThatAffectsMyMovement} ({@code :197-200}): 0.999999F, not the base 0.500001F. */
    @Override
    protected BlockPos getBlockPosBelowThatAffectsMyMovement(EntityLevel level) {
        return getOnPos(level, 0.999999F);
    }

    // ---- merging (G19 slice 2) ----

    /** {@code ItemEntity.mergeWithNeighbours} ({@code ItemEntity.java:215-227}). */
    private void mergeWithNeighbours(EntityLevel level) {
        if (!isMergable()) {
            return;
        }
        EntityWorld w = this.world != null ? this.world : level.world();
        if (w == null) {
            return;
        }
        List<KestrelItemEntity> candidates = new ArrayList<>();
        AABB searchBox = getBoundingBox().inflate(0.5, 0.0, 0.5);
        w.sections().getEntities(searchBox, entity -> {
            if (entity instanceof KestrelItemEntity other && other != this && other.isMergable()) {
                candidates.add(other);
            }
        });
        for (KestrelItemEntity entity : candidates) {
            if (entity.isMergable()) {
                tryToMerge(entity);
                if (isRemoved()) {
                    break;
                }
            }
        }
    }

    /** {@code ItemEntity.isMergable} ({@code ItemEntity.java:229-232}). */
    public boolean isMergable() {
        ItemStack item = getItem();
        return isAlive()
                && pickupDelay != INFINITE_PICKUP_DELAY
                && age != INFINITE_LIFETIME
                && age < LIFETIME
                && item.getCount() < item.getMaxStackSize();
    }

    /** {@code ItemEntity.tryToMerge} ({@code ItemEntity.java:234-244}). */
    public void tryToMerge(KestrelItemEntity other) {
        ItemStack thisItemStack = getItem();
        ItemStack otherItemStack = other.getItem();
        if (Objects.equals(this.target, other.target) && areMergable(thisItemStack, otherItemStack)) {
            if (otherItemStack.getCount() < thisItemStack.getCount()) {
                merge(this, thisItemStack, other, otherItemStack);
            } else {
                merge(other, otherItemStack, this, thisItemStack);
            }
        }
    }

    /** {@code ItemEntity.areMergable} ({@code ItemEntity.java:246-250}). */
    public static boolean areMergable(ItemStack thisItemStack, ItemStack otherItemStack) {
        return otherItemStack.getCount() + thisItemStack.getCount() > otherItemStack.getMaxStackSize()
                ? false
                : ItemStack.isSameItemSameComponents(thisItemStack, otherItemStack);
    }

    /** {@code ItemEntity.merge(ItemStack, ItemStack, int)} ({@code ItemEntity.java:252-257}). */
    public static ItemStack merge(ItemStack toStack, ItemStack fromStack, int maxCount) {
        int delta = Math.min(Math.min(toStack.getMaxStackSize(), maxCount) - toStack.getCount(), fromStack.getCount());
        ItemStack newToStack = toStack.copyWithCount(toStack.getCount() + delta);
        fromStack.shrink(delta);
        return newToStack;
    }

    /** {@code ItemEntity.merge(ItemEntity, ItemStack, ItemStack)} ({@code ItemEntity.java:259-262}). */
    private static void merge(KestrelItemEntity toItem, ItemStack toStack, ItemStack fromStack) {
        ItemStack newToStack = merge(toStack, fromStack, 64);
        toItem.setItem(newToStack);
    }

    /** {@code ItemEntity.merge(ItemEntity, ItemStack, ItemEntity, ItemStack)} ({@code ItemEntity.java:264-271}). */
    private static void merge(KestrelItemEntity toItem, ItemStack toStack, KestrelItemEntity fromItem, ItemStack fromStack) {
        merge(toItem, toStack, fromStack);
        toItem.pickupDelay = Math.max(toItem.pickupDelay, fromItem.pickupDelay);
        toItem.age = Math.min(toItem.age, fromItem.age);
        if (fromStack.isEmpty()) {
            fromItem.discard();
        }
    }
}
