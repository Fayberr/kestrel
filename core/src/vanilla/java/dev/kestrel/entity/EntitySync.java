package dev.kestrel.entity;

import dev.kestrel.net.EntityOutbound;
import dev.kestrel.net.Outbound;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Kestrel's counterpart of vanilla's {@code ServerEntity}
 * ({@code net/minecraft/server/level/ServerEntity.java}, 26.2): decides,
 * once per tracker tick, which movement, motion, head rotation, passenger,
 * data and attribute packets the tracking players are told, and builds the
 * pairing bundle for a player that starts tracking.
 *
 * <p>The position quantisation is vanilla's own {@link VecDeltaCodec} (a
 * leaf, reused under the reuse rule); every decision around it (the
 * interval gate, the full-sync triggers, the short-overflow test, the
 * packet ladder, the motion and head thresholds, when the base moves) is
 * this class, line for line against the source cited on each block.
 *
 * <p>Branches vanilla keys off concrete classes that no {@link KestrelEntity}
 * can be today are omitted and named where they sit: the item frame map
 * refresh, the new minecart path, the arrow exclusion from the split
 * packets, the hurting projectile power bundle, and fall flying as a motion
 * trigger. The "and self" sends (dirty data, attributes, the hurt motion)
 * also reach the entity's own connection when it is a player
 * ({@link KestrelEntity#selfObserver}).
 *
 * <p>Thread-safety: tick thread only.
 */
public final class EntitySync {

    /** {@code ServerEntity.TOLERANCE_LEVEL_ROTATION}, in packed byte steps. */
    static final int TOLERANCE_LEVEL_ROTATION = 1;
    /** {@code ServerEntity.TOLERANCE_LEVEL_POSITION}, a float literal widened to double. */
    static final double TOLERANCE_LEVEL_POSITION = 7.6293945E-6F;
    /** {@code ServerEntity.FORCED_POS_UPDATE_PERIOD}. */
    public static final int FORCED_POS_UPDATE_PERIOD = 60;
    /** {@code ServerEntity.FORCED_TELEPORT_PERIOD}. */
    static final int FORCED_TELEPORT_PERIOD = 400;

    /** Where this entity's packets go: {@code ServerEntity.Synchronizer}. */
    public interface Sink {
        void sendToTrackingPlayers(Outbound.Packet packet);

        /**
         * The tracking players, then the entity itself when it is a player.
         * A sink for an entity that is never a player has no self to add.
         */
        default void sendToTrackingPlayersAndSelf(Outbound.Packet packet) {
            sendToTrackingPlayers(packet);
        }
    }

    private final KestrelEntity entity;
    private final int updateInterval;
    private final boolean trackDelta;
    private final Sink sink;
    private final VecDeltaCodec positionCodec = new VecDeltaCodec();
    private byte lastSentYRot;
    private byte lastSentXRot;
    private byte lastSentYHeadRot;
    private Vec3 lastSentMovement;
    private int tickCount;
    private int teleportDelay;
    private List<KestrelEntity> lastPassengers = Collections.emptyList();
    private boolean wasRiding;
    private boolean wasOnGround;
    private @Nullable List<SynchedEntityData.DataValue<?>> trackedDataValues;

    /** {@code ServerEntity.java:74-89}. */
    public EntitySync(KestrelEntity entity, int updateInterval, boolean trackDelta, Sink sink) {
        this.entity = entity;
        this.updateInterval = updateInterval;
        this.trackDelta = trackDelta;
        this.sink = sink;
        this.positionCodec.setBase(entity.trackingPosition());
        this.lastSentMovement = entity.getDeltaMovement();
        this.lastSentYRot = Mth.packDegrees(entity.getYRot());
        this.lastSentXRot = Mth.packDegrees(entity.getXRot());
        this.lastSentYHeadRot = Mth.packDegrees(entity.getYHeadRot());
        this.wasOnGround = entity.onGround();
        this.trackedDataValues = entity.getEntityData().getNonDefaultValues();
    }

    /** True when an encoded delta does not fit the signed short a move packet carries. */
    static boolean overflowsShort(long encoded) {
        return encoded < Short.MIN_VALUE || encoded > Short.MAX_VALUE;
    }

    /** {@code ServerEntity.sendChanges}, {@code ServerEntity.java:91-230}. */
    public void sendChanges() {
        // :92
        entity.updateDataBeforeSync();
        // :93-100. Vanilla filters out a player whose own riding state
        // flipped (it learns that from its own packets). A Kestrel observer
        // is never a KestrelEntity, so it is in neither list and the filter
        // always passes; revisit when players become entities (G15).
        List<KestrelEntity> passengers = entity.getPassengers();
        if (!passengers.equals(lastPassengers)) {
            sink.sendToTrackingPlayers(passengersPacket(entity));
            lastPassengers = passengers;
        }

        // :102-119 item frame map refresh: no Kestrel entity is an item frame.

        // :121-124
        if (entity.syncPosition) {
            tickCount = tickCount / updateInterval * updateInterval + updateInterval;
            entity.syncPosition = false;
        }

        // :126
        if (tickCount % updateInterval == 0 || entity.needsSync || entity.getEntityData().isDirty()) {
            byte yRotn = Mth.packDegrees(entity.getYRot());
            byte xRotn = Mth.packDegrees(entity.getXRot());
            boolean shouldSendRotation = Math.abs(yRotn - lastSentYRot) >= TOLERANCE_LEVEL_ROTATION
                    || Math.abs(xRotn - lastSentXRot) >= TOLERANCE_LEVEL_ROTATION;
            if (entity.isPassenger()) {
                // :130-139
                if (shouldSendRotation) {
                    sink.sendToTrackingPlayers(EntityOutbound.moveEntityRot(entity.getId(), yRotn, xRotn, entity.onGround()));
                    lastSentYRot = yRotn;
                    lastSentXRot = xRotn;
                }
                positionCodec.setBase(entity.trackingPosition());
                sendDirtyEntityData();
                wasRiding = true;
            } else {
                // :140-141 new minecart behaviour: not expressible, falls through here.
                // :143-213
                teleportDelay++;
                Vec3 currentPosition = entity.trackingPosition();
                boolean positionChanged = positionCodec.delta(currentPosition).lengthSqr() >= TOLERANCE_LEVEL_POSITION;
                Outbound.Packet packet = null;
                boolean pos = positionChanged || tickCount % FORCED_POS_UPDATE_PERIOD == 0;
                boolean sentPosition = false;
                boolean sentRotation = false;
                long xa = positionCodec.encodeX(currentPosition);
                long ya = positionCodec.encodeY(currentPosition);
                long za = positionCodec.encodeZ(currentPosition);
                // :153 the encoding-range test, not a block distance.
                boolean deltaTooBig = overflowsShort(xa) || overflowsShort(ya) || overflowsShort(za);
                if (entity.getRequiresPrecisePosition()
                        || deltaTooBig
                        || teleportDelay > FORCED_TELEPORT_PERIOD
                        || wasRiding
                        || wasOnGround != entity.onGround()) {
                    wasOnGround = entity.onGround();
                    teleportDelay = 0;
                    packet = EntityOutbound.entityPositionSync(entity.getId(), entity.trackingPosition(),
                            entity.getDeltaMovement(), entity.getYRot(), entity.getXRot(), entity.onGround());
                    sentPosition = true;
                    sentRotation = true;
                } else if (!pos || !shouldSendRotation) {
                    // :164 also requires "not an AbstractArrow"; no Kestrel entity is one.
                    if (pos) {
                        packet = EntityOutbound.moveEntityPos(entity.getId(), (short) xa, (short) ya, (short) za, entity.onGround());
                        sentPosition = true;
                    } else if (shouldSendRotation) {
                        packet = EntityOutbound.moveEntityRot(entity.getId(), yRotn, xRotn, entity.onGround());
                        sentRotation = true;
                    }
                } else {
                    packet = EntityOutbound.moveEntityPosRot(entity.getId(), (short) xa, (short) ya, (short) za,
                            yRotn, xRotn, entity.onGround());
                    sentPosition = true;
                    sentRotation = true;
                }

                // :178-197, without the fall-flying and hurting-projectile cases.
                if (entity.needsSync || trackDelta) {
                    Vec3 movement = entity.getDeltaMovement();
                    double diff = movement.distanceToSqr(lastSentMovement);
                    if (diff > 1.0E-7 || diff > 0.0 && movement.lengthSqr() == 0.0) {
                        lastSentMovement = movement;
                        sink.sendToTrackingPlayers(EntityOutbound.setEntityMotion(entity.getId(), lastSentMovement));
                    }
                }

                if (packet != null) {
                    sink.sendToTrackingPlayers(packet);
                }

                sendDirtyEntityData();
                if (sentPosition) {
                    positionCodec.setBase(currentPosition);
                }
                if (sentRotation) {
                    lastSentYRot = yRotn;
                    lastSentXRot = xRotn;
                }
                wasRiding = false;
            }

            // :216-220
            byte yHeadRot = Mth.packDegrees(entity.getYHeadRot());
            if (Math.abs(yHeadRot - lastSentYHeadRot) >= TOLERANCE_LEVEL_ROTATION) {
                sink.sendToTrackingPlayers(EntityOutbound.rotateHead(entity.getId(), yHeadRot));
                lastSentYHeadRot = yHeadRot;
            }

            entity.needsSync = false;
        }

        // :225-229
        tickCount++;
        if (entity.hurtMarked) {
            entity.hurtMarked = false;
            sink.sendToTrackingPlayersAndSelf(EntityOutbound.setEntityMotion(entity.getId(), entity.getDeltaMovement()));
        }
    }

    /**
     * {@code ServerEntity.sendPairingData}, {@code ServerEntity.java:275-320}:
     * the members of the bundle a player receives when it starts tracking.
     * Equipment and leash members are not expressible yet (see
     * {@link KestrelEntity}); everything else is emitted in vanilla's order.
     */
    public void sendPairingData(Consumer<Outbound.Packet> broadcast) {
        // :276
        entity.updateDataBeforeSync();
        // Entity.getAddEntityPacket -> ClientboundAddEntityPacket(entity, serverEntity):
        // the last *sent* base, rotations and motion, not the live values.
        // The rotations go through unpack then pack, as the packet's
        // float constructor does.
        Vec3 base = positionCodec.getBase();
        broadcast.accept(EntityOutbound.addEntity(entity.getId(), entity.getUUID(), entity.getType(),
                base.x(), base.y(), base.z(), lastSentMovement,
                Mth.packDegrees(Mth.unpackDegrees(lastSentXRot)),
                Mth.packDegrees(Mth.unpackDegrees(lastSentYRot)),
                Mth.packDegrees(Mth.unpackDegrees(lastSentYHeadRot)),
                entity.addEntityData()));
        if (trackedDataValues != null) {
            broadcast.accept(EntityOutbound.setEntityData(entity.getId(), trackedDataValues));
        }

        AttributeMap attributes = entity.getAttributes();
        if (attributes != null) {
            Collection<AttributeInstance> syncable = attributes.getSyncableAttributes();
            if (!syncable.isEmpty()) {
                broadcast.accept(EntityOutbound.updateAttributes(entity.getId(), syncable));
            }
            List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot,
                    net.minecraft.world.item.ItemStack>> equipment = entity.pairingEquipment();
            if (!equipment.isEmpty()) {
                broadcast.accept(EntityOutbound.setEquipment(entity.getId(), equipment));
            }
        }

        if (!entity.getPassengers().isEmpty()) {
            broadcast.accept(passengersPacket(entity));
        }
        KestrelEntity vehicle = entity.getVehicle();
        if (vehicle != null) {
            broadcast.accept(passengersPacket(vehicle));
        }
        // :317-319 leash: not expressible yet.
    }

    /** The pairing members, bracketed as a bundle ({@code ServerEntity.addPairing}). */
    public List<Outbound.Packet> pairingBundle() {
        List<Outbound.Packet> members = new ArrayList<>();
        sendPairingData(members::add);
        return EntityOutbound.bundle(members);
    }

    /** {@code ServerEntity.sendDirtyEntityData}, {@code ServerEntity.java:342-358}. */
    private void sendDirtyEntityData() {
        SynchedEntityData entityData = entity.getEntityData();
        List<SynchedEntityData.DataValue<?>> packedValues = entityData.packDirty();
        if (packedValues != null) {
            trackedDataValues = entityData.getNonDefaultValues();
            sink.sendToTrackingPlayersAndSelf(EntityOutbound.setEntityData(entity.getId(), packedValues));
        }

        AttributeMap attributes = entity.getAttributes();
        if (attributes != null) {
            Set<AttributeInstance> toSync = attributes.getAttributesToSync();
            if (!toSync.isEmpty()) {
                sink.sendToTrackingPlayersAndSelf(EntityOutbound.updateAttributes(entity.getId(), toSync));
            }
            toSync.clear();
        }
    }

    private static Outbound.Packet passengersPacket(KestrelEntity vehicle) {
        List<KestrelEntity> list = vehicle.getPassengers();
        int[] ids = new int[list.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = list.get(i).getId();
        }
        return EntityOutbound.setPassengers(vehicle.getId(), ids);
    }

    // ---- read-only views, for the tracker and the gate ----

    public KestrelEntity entity() {
        return entity;
    }

    /** {@code ServerEntity.getPositionBase}. */
    public Vec3 getPositionBase() {
        return positionCodec.getBase();
    }

    /** The live codec, so the gate can diff it against a fresh vanilla one. */
    VecDeltaCodec positionCodec() {
        return positionCodec;
    }
}
