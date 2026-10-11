package dev.kestrel.entity;

import com.google.common.collect.ImmutableList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The entity seam the tracker (S14 slice 2) syncs to clients: the state a
 * vanilla {@code Entity} exposes to {@code ServerEntity} and
 * {@code ChunkMap.TrackedEntity}, and nothing that moves it. The caller sets
 * position, rotation, motion and ground state; slice 3 brings ticking.
 *
 * <p>What it can express: identity, a vanilla {@link EntityType} (tracking
 * range, update interval, delta tracking, registry id), position and
 * rotation, head rotation (only when living, as vanilla's base
 * {@code Entity.getYHeadRot} is always 0), motion, ground state, the
 * {@code needsSync} / {@code hurtMarked} / {@code syncPosition} /
 * {@code requiresPrecisePosition} flags, the base {@code Entity} synched
 * data, a vanilla {@link AttributeMap} when living, and vehicle and
 * passenger links. Subclasses append their own synched data through
 * {@link #defineSynchedData} ({@link KestrelItemEntity} carries its stack
 * that way, as vanilla's {@code ItemEntity} does). What it cannot express
 * yet: equipment, leashes, a non-zero add-entity data field, and the
 * special tracker branches vanilla keys off concrete classes (item frame
 * maps, new minecart behaviour, arrows, hurting projectiles, fall flying).
 *
 * <p>Since slice 3 an entity can also tick: {@link EntityWorld} runs
 * {@link #tick(EntityLevel)} in the entity phase of the tick, after
 * {@code setOldPosAndRot} and the {@code tickCount} increment, as
 * {@code ServerLevel.tickNonPassenger} does. The base entity's tick does
 * nothing; the types that move override it.
 *
 * <p>The synched data is vanilla's own {@link SynchedEntityData}. Its ids
 * come from {@link SynchedEntityData#defineId}, which numbers per class
 * tree, so this class defines the base layout in the order vanilla's
 * {@code Entity.java:262-278} does; {@code EntityTrackingCheck} compares the
 * ids, serializers and defaults against a real vanilla entity.
 *
 * <p>Thread-safety: owned by the tick thread.
 */
public class KestrelEntity implements EntityAccess, SyncedDataHolder {

    /** Equipment exposed to ServerEntity pairing, empty for non-living Kestrel entities. */
    public List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot,
            net.minecraft.world.item.ItemStack>> pairingEquipment() { return List.of(); }

    // Entity.java:262-278, same declaration order, same serializers.
    protected static final EntityDataAccessor<Byte> DATA_SHARED_FLAGS_ID =
            SynchedEntityData.defineId(KestrelEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_AIR_SUPPLY_ID =
            SynchedEntityData.defineId(KestrelEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<Component>> DATA_CUSTOM_NAME =
            SynchedEntityData.defineId(KestrelEntity.class, EntityDataSerializers.OPTIONAL_COMPONENT);
    private static final EntityDataAccessor<Boolean> DATA_CUSTOM_NAME_VISIBLE =
            SynchedEntityData.defineId(KestrelEntity.class, EntityDataSerializers.BOOLEAN);
    protected static final EntityDataAccessor<Boolean> DATA_SILENT =
            SynchedEntityData.defineId(KestrelEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_NO_GRAVITY =
            SynchedEntityData.defineId(KestrelEntity.class, EntityDataSerializers.BOOLEAN);
    protected static final EntityDataAccessor<Pose> DATA_POSE =
            SynchedEntityData.defineId(KestrelEntity.class, EntityDataSerializers.POSE);
    private static final EntityDataAccessor<Integer> DATA_TICKS_FROZEN =
            SynchedEntityData.defineId(KestrelEntity.class, EntityDataSerializers.INT);

    /** {@code Entity.FLAG_GLOWING}. */
    public static final int FLAG_GLOWING = 6;

    private final int id;
    private final UUID uuid;
    private final EntityType<?> type;
    private final SynchedEntityData entityData;
    private final @Nullable AttributeMap attributes;

    private Vec3 position = Vec3.ZERO;
    private float yRot;
    private float xRot;
    private float yHeadRot;
    private Vec3 deltaMovement = Vec3.ZERO;
    private boolean onGround;
    private boolean requiresPrecisePosition;
    private boolean removed;
    private Entity.@Nullable RemovalReason removalReason;

    /** Reuse a player wrapper for vanilla's replacement-player respawn. */
    protected final void setRemovedState(boolean value) {
        removed = value;
        if (!value) removalReason = null;
    }

    /** Vanilla's public {@code Entity.xo/yo/zo}: the position at the start of this tick. */
    public double xo;
    public double yo;
    public double zo;
    /** Vanilla's public {@code Entity.yRotO/xRotO}. */
    public float yRotO;
    public float xRotO;
    /** Vanilla's public {@code Entity.tickCount}: incremented before each tick. */
    public int tickCount;

    /** Vanilla's public {@code Entity.needsSync}: force a sync this tick. */
    public boolean needsSync;
    /** Vanilla's public {@code Entity.hurtMarked}: resend motion to trackers and self. */
    public boolean hurtMarked;
    /** Vanilla's public {@code Entity.syncPosition}: snap the tick counter to the next interval. */
    public boolean syncPosition;

    private List<KestrelEntity> passengers = ImmutableList.of();
    private @Nullable KestrelEntity vehicle;
    private EntityInLevelCallback levelCallback = EntityInLevelCallback.NULL;
    private java.util.function.@Nullable Consumer<KestrelEntity> removalHook;

    /** A non-living entity: no attributes, head rotation fixed at 0. */
    public KestrelEntity(int id, UUID uuid, EntityType<?> type) {
        this(id, uuid, type, null);
    }

    /**
     * @param attributes the type's default supplier when the entity is
     *     living (vanilla {@code DefaultAttributes.getSupplier}), else null
     */
    public KestrelEntity(int id, UUID uuid, EntityType<?> type, @Nullable AttributeSupplier attributes) {
        this.id = id;
        this.uuid = uuid;
        this.type = type;
        this.attributes = attributes == null ? null : new AttributeMap(attributes);
        // Entity.java:322-332: base values in the order the constructor defines them.
        SynchedEntityData.Builder builder = new SynchedEntityData.Builder(this);
        builder.define(DATA_SHARED_FLAGS_ID, (byte) 0);
        builder.define(DATA_AIR_SUPPLY_ID, getMaxAirSupply());
        builder.define(DATA_CUSTOM_NAME_VISIBLE, false);
        builder.define(DATA_CUSTOM_NAME, Optional.empty());
        builder.define(DATA_SILENT, false);
        builder.define(DATA_NO_GRAVITY, false);
        builder.define(DATA_POSE, Pose.STANDING);
        builder.define(DATA_TICKS_FROZEN, 0);
        defineSynchedData(builder);
        this.entityData = builder.build();
    }

    /** Subclasses append their own synched values here, as vanilla subclasses do. */
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    /** {@code Entity.getMaxAirSupply}. */
    protected int getMaxAirSupply() {
        return 300;
    }

    // ---- identity ----

    @Override
    public int getId() {
        return id;
    }

    @Override
    public UUID getUUID() {
        return uuid;
    }

    public EntityType<?> getType() {
        return type;
    }

    public boolean isLiving() {
        return attributes != null;
    }

    public @Nullable AttributeMap getAttributes() {
        return attributes;
    }

    public SynchedEntityData getEntityData() {
        return entityData;
    }

    // ---- position and rotation, set by the caller ----

    public Vec3 position() {
        return position;
    }

    public double getX() {
        return position.x;
    }

    public double getY() {
        return position.y;
    }

    public double getZ() {
        return position.z;
    }

    /** {@code Entity.trackingPosition}: the position the tracker syncs. */
    public Vec3 trackingPosition() {
        return position;
    }

    public void setPos(double x, double y, double z) {
        if (position.x != x || position.y != y || position.z != z) {
            position = new Vec3(x, y, z);
            levelCallback.onMove();
        }
    }

    public float getYRot() {
        return yRot;
    }

    /** {@code Entity.setYRot}: non-finite values are dropped. */
    public void setYRot(float yRot) {
        if (Float.isFinite(yRot)) {
            this.yRot = yRot;
        }
    }

    public float getXRot() {
        return xRot;
    }

    /** {@code Entity.setXRot}: non-finite values are dropped, the rest clamped to +-90. */
    public void setXRot(float xRot) {
        if (Float.isFinite(xRot)) {
            this.xRot = Math.clamp(xRot % 360.0F, -90.0F, 90.0F);
        }
    }

    /** Vanilla's base {@code Entity} always reports 0; {@code LivingEntity} stores it. */
    public float getYHeadRot() {
        return isLiving() ? yHeadRot : 0.0F;
    }

    public void setYHeadRot(float yHeadRot) {
        if (isLiving()) {
            this.yHeadRot = yHeadRot;
        }
    }

    public Vec3 getDeltaMovement() {
        return deltaMovement;
    }

    /** {@code Entity.setDeltaMovement}: non-finite vectors are dropped. */
    public void setDeltaMovement(Vec3 deltaMovement) {
        if (deltaMovement.isFinite()) {
            this.deltaMovement = deltaMovement;
        }
    }

    public boolean onGround() {
        return onGround;
    }

    public void setOnGround(boolean onGround) {
        this.onGround = onGround;
    }

    public boolean getRequiresPrecisePosition() {
        return requiresPrecisePosition;
    }

    public void setRequiresPrecisePosition(boolean requiresPrecisePosition) {
        this.requiresPrecisePosition = requiresPrecisePosition;
    }

    public int chunkX() {
        return SectionPos.blockToSectionCoord(Mth.floor(position().x));
    }

    public int chunkZ() {
        return SectionPos.blockToSectionCoord(Mth.floor(position().z));
    }

    /** {@code SectionPos.of(entity)}, packed. */
    public long sectionKey() {
        return SectionPos.asLong(blockPosition());
    }

    // ---- synched data helpers (Entity's own setters) ----

    public void setSharedFlag(int flag, boolean value) {
        byte current = entityData.get(DATA_SHARED_FLAGS_ID);
        entityData.set(DATA_SHARED_FLAGS_ID, (byte) (value ? current | 1 << flag : current & ~(1 << flag)));
    }

    public void setAirSupply(int air) {
        entityData.set(DATA_AIR_SUPPLY_ID, air);
    }

    public void setCustomName(@Nullable Component name) {
        entityData.set(DATA_CUSTOM_NAME, Optional.ofNullable(name));
    }

    public void setCustomNameVisible(boolean visible) {
        entityData.set(DATA_CUSTOM_NAME_VISIBLE, visible);
    }

    public void setSilent(boolean silent) {
        entityData.set(DATA_SILENT, silent);
    }

    public boolean isNoGravity() {
        return entityData.get(DATA_NO_GRAVITY);
    }

    public void setNoGravity(boolean noGravity) {
        entityData.set(DATA_NO_GRAVITY, noGravity);
    }

    public void setPose(Pose pose) {
        entityData.set(DATA_POSE, pose);
    }

    public void setTicksFrozen(int ticks) {
        entityData.set(DATA_TICKS_FROZEN, ticks);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
    }

    @Override
    public void onSyncedDataUpdated(List<SynchedEntityData.DataValue<?>> updatedItems) {
    }

    // ---- per-type hooks the tracker consults ----

    /** {@code Entity.broadcastToPlayer}: true unless a type hides itself. */
    public boolean broadcastToPlayer(TrackingObserver observer) {
        return true;
    }

    /** The data field of the add-entity packet; 0 for the base {@code Entity}. */
    public int addEntityData() {
        return 0;
    }

    /**
     * {@code Entity.updateDataBeforeSync}: what an entity refreshes before
     * {@code ServerEntity} reads it ({@code sendChanges} and
     * {@code sendPairingData} both start with it). Nothing for the base
     * entity.
     */
    public void updateDataBeforeSync() {
    }

    /**
     * The connection {@code ServerEntity.Synchronizer.sendToTrackingPlayersAndSelf}
     * also sends to: the player's own, when this entity is a player
     * ({@code ChunkMap.TrackedEntity.sendToTrackingPlayersAndSelf}); null
     * for every other entity.
     */
    public @Nullable TrackingObserver selfObserver() {
        return null;
    }

    // ---- riding ----

    public List<KestrelEntity> getPassengers() {
        return passengers;
    }

    public @Nullable KestrelEntity getVehicle() {
        return vehicle;
    }

    public boolean isPassenger() {
        return vehicle != null;
    }

    /** {@code Entity.hasPassenger(Entity)}. */
    public boolean hasPassenger(KestrelEntity entity) {
        return passengers.contains(entity);
    }

    /**
     * Link this entity onto {@code newVehicle}, appending to its passenger
     * list the way {@code Entity.addPassenger} does for a non-player
     * ({@code Entity.java:2503-2517}). No ride rules are applied; the caller
     * decides who rides what.
     */
    public void startRiding(KestrelEntity newVehicle) {
        if (vehicle != null) {
            stopRiding();
        }
        vehicle = newVehicle;
        List<KestrelEntity> next = new ArrayList<>(newVehicle.passengers);
        next.add(this);
        newVehicle.passengers = ImmutableList.copyOf(next);
    }

    /** {@code Entity.removePassenger}: drop this entity from its vehicle's list. */
    public void stopRiding() {
        KestrelEntity old = vehicle;
        if (old == null) {
            return;
        }
        vehicle = null;
        List<KestrelEntity> next = new ArrayList<>(old.passengers);
        next.remove(this);
        old.passengers = ImmutableList.copyOf(next);
    }

    /**
     * {@code Entity.getIndirectPassengers}: every passenger, depth first,
     * each followed by its own passengers ({@code Entity.java:3558-3574}).
     */
    public List<KestrelEntity> getIndirectPassengers() {
        List<KestrelEntity> out = new ArrayList<>();
        for (KestrelEntity p : passengers) {
            p.collectSelfAndPassengers(out);
        }
        return out;
    }

    private void collectSelfAndPassengers(List<KestrelEntity> out) {
        out.add(this);
        for (KestrelEntity p : passengers) {
            p.collectSelfAndPassengers(out);
        }
    }

    // ---- ticking (slice 3) ----

    /** {@code Entity.setOldPosAndRot} ({@code Entity.java:1830-1852}). */
    public void setOldPosAndRot() {
        xo = position.x;
        yo = position.y;
        zo = position.z;
        yRotO = yRot;
        xRotO = xRot;
    }

    /** {@code Entity.checkDespawn}: nothing for the base entity. */
    public void checkDespawn() {
    }

    /**
     * {@code Entity.tick}. The base entity has no tick behaviour Kestrel
     * models; a subclass overrides this with its type's sequence.
     */
    public void tick(EntityLevel level) {
    }

    // ---- lifecycle ----

    /** {@code Entity.isAlive} ({@code Entity.java:3975-3977}): true while not removed. */
    public boolean isAlive() {
        return !removed;
    }

    public boolean isRemoved() {
        return removed;
    }

    public Entity.@Nullable RemovalReason getRemovalReason() {
        return removalReason;
    }

    /** Marked by the owner when the entity leaves the world. */
    public void setRemoved() {
        removed = true;
    }

    /**
     * {@code Entity.setRemoved(RemovalReason)} ({@code Entity.java:3961-3973}):
     * record the first reason, drop riding links, then tell the level
     * callback so the storage forgets the entity. {@link EntityWorld}
     * finishes the removal (tick list, tracker, id) when it sees the flag.
     */
    public void setRemoved(Entity.RemovalReason reason) {
        if (removalReason == null) {
            removalReason = reason;
        }
        removed = true;
        if (removalReason.shouldDestroy()) {
            stopRiding();
        }
        for (KestrelEntity passenger : List.copyOf(passengers)) {
            passenger.stopRiding();
        }
        levelCallback.onRemove(reason);
        java.util.function.Consumer<KestrelEntity> hook = removalHook;
        if (hook != null) {
            // Like the level callback, it fires once: the first removal.
            removalHook = null;
            hook.accept(this);
        }
    }

    /** Set by {@link EntityWorld} on add: finishes the removal outside the storage. */
    void setRemovalHook(java.util.function.Consumer<KestrelEntity> hook) {
        this.removalHook = hook;
    }

    /** {@code Entity.discard}. */
    public void discard() {
        setRemoved(Entity.RemovalReason.DISCARDED);
    }

    // ---- EntityAccess (slice 1 storage) ----

    @Override
    public BlockPos blockPosition() {
        return BlockPos.containing(position());
    }

    @Override
    public AABB getBoundingBox() {
        return type.getDimensions().makeBoundingBox(position());
    }

    @Override
    public boolean isAlwaysTicking() {
        return false;
    }

    @Override
    public void setLevelCallback(EntityInLevelCallback callback) {
        this.levelCallback = callback;
    }

    @Override
    public String toString() {
        return "KestrelEntity[" + id + " " + type + " " + position + "]";
    }
}
