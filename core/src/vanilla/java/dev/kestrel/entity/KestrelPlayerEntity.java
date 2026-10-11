package dev.kestrel.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * A joined player's body (G15): the tracked entity other players see. It is
 * a {@link KestrelEntity} for the tracker, and every value the tracker reads
 * comes from the session's real vanilla {@link KestrelServerPlayer}: the
 * synched data and its layout, the attributes, the position, rotations,
 * head rotation, motion, ground state and the {@code needsSync} /
 * {@code syncPosition} / {@code hurtMarked} flags.
 *
 * <p>The player's server-side life is transcribed from vanilla at the three
 * points it happens, each calling vanilla's own methods on the stand-in:
 * <ul>
 *   <li>{@link #handleMove}: the accepted branch of
 *       {@code ServerGamePacketListenerImpl.handleMovePlayer}
 *       ({@code :1068-1196});</li>
 *   <li>{@link #handleInput}, {@link #handleCommand}, {@link #swing}: the
 *       sneak, sprint and swing handlers ({@code :425-432},
 *       {@code :1712-1722}, {@code :1705-1709});</li>
 *   <li>{@link #serverPlayerTick}: the level phase's full
 *       {@code ServerPlayer.tick};</li>
 *   <li>{@link #tickPlayer}: {@code ServerGamePacketListenerImpl.tickPlayer}
 *       ({@code :317-324}) around the full {@code ServerPlayer.doTick}.</li>
 * </ul>
 *
 * <p>Thread-safety: tick thread only.
 */
public final class KestrelPlayerEntity extends KestrelEntity {

    private final KestrelServerPlayer standIn;
    private final TrackingObserver self;
    private final BiConsumer<KestrelPlayerEntity, SentPacket> sender;
    private final EntityLevel level;

    /** Install the dynamic registries which back vanilla's level services. */
    public static void setRegistryAccess(EntityLevel level, net.minecraft.core.RegistryAccess access) {
        VanillaEntityLevel.of(level).setRegistryAccess(access);
    }

    /** Loaded-chunk summary used by vanilla's exact fluid scan. */
    public static net.minecraft.world.level.chunk.ChunkAccess fluidScanChunk(
            net.minecraft.world.level.LevelHeightAccessor height) {
        return VanillaEntityLevel.fluidScanChunk(height);
    }

    /** A vanilla packet the stand-in sent through its chunk source, and to whom. */
    public record SentPacket(Packet<?> packet, boolean andSelf) {
    }

    /**
     * @param level the live level the stand-in's vanilla code reads
     * @param self the player's own connection, as the tracker sees it
     * @param sender where the stand-in's own broadcasts go (its swing)
     */
    public KestrelPlayerEntity(int id, UUID uuid, String name, EntityLevel level, TrackingObserver self,
            BiConsumer<KestrelPlayerEntity, SentPacket> sender) {
        super(id, uuid, EntityTypes.PLAYER);
        this.self = self;
        this.sender = sender;
        this.level = level;
        this.standIn = new KestrelServerPlayer(VanillaEntityLevel.of(level), new GameProfile(uuid, name),
                net.minecraft.server.level.ClientInformation.createDefault(), this);
        this.standIn.setId(id);
    }

    // ---- joining ----

    /** {@code PrepareSpawnTask.spawn}: {@code snapTo} the spawn position ({@code :192}). */
    public void spawnAt(double x, double y, double z, float yRot, float xRot) {
        standIn.snapTo(x, y, z, yRot, xRot);
    }

    /**
     * {@code ServerPlayer.updateOptions} ({@code :1874-1884}), the two values
     * it keeps in the synched data: the skin layers and the main hand.
     */
    public void updateOptions(int modelCustomisation, HumanoidArm mainHand) {
        standIn.updateOptionsSynched(modelCustomisation, mainHand);
    }

    /** {@code Player.isModelPartShown(HAT)}, for the tab list entry. */
    public boolean showsHat() {
        return standIn.isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart.HAT);
    }

    /**
     * {@code ServerGamePacketListenerImpl.teleport} ({@code :1254-1267}):
     * {@code Entity.teleportSetPosition}, which also sets the head to the
     * new yaw and the motion to zero.
     */
    public void teleport(double x, double y, double z, float yRot, float xRot) {
        standIn.teleportSetPosition(new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yRot, xRot), Set.of());
    }

    // ---- inbound, on the tick thread ----

    /**
     * The accepted branch of {@code handleMovePlayer}
     * ({@code ServerGamePacketListenerImpl.java:1080-1190}) for a player that
     * is not riding, not sleeping and not awaiting a teleport. Kestrel's own
     * movement checks (S8) have already accepted the move and stand in for
     * vanilla's "moved too quickly" and "moved wrongly" tests. Absent
     * coordinates or rotations are the current ones, as the packet's
     * getters default.
     *
     * @param chunkMove {@code ServerChunkCache.move}: the tracker's
     *     {@code ChunkMap.move} for this player
     */
    public void handleMove(Double x, Double y, Double z, Float yRot, Float xRot, boolean onGround,
            boolean horizontalCollision, Runnable chunkMove) {
        float targetYRot = Mth.wrapDegrees(yRot != null ? yRot : standIn.getYRot());
        float targetXRot = Mth.wrapDegrees(xRot != null ? xRot : standIn.getXRot());
        double targetX = Mth.clamp(x != null ? x : standIn.getX(), -3.0E7, 3.0E7);
        double targetY = Mth.clamp(y != null ? y : standIn.getY(), -2.0E7, 2.0E7);
        double targetZ = Mth.clamp(z != null ? z : standIn.getZ(), -3.0E7, 3.0E7);
        double startX = standIn.getX();
        double startY = standIn.getY();
        double startZ = standIn.getZ();
        // lastGood is the current position: it is reset each tick and after
        // each accepted move, and Kestrel keeps no failed move.
        double xDist = targetX - startX;
        double yDist = targetY - startY;
        double zDist = targetZ - startZ;
        boolean movedUpwards = yDist > 0.0;
        if (standIn.onGround() && !onGround && movedUpwards) {
            standIn.jumpFromGround();
        }
        standIn.move(MoverType.PLAYER, new Vec3(xDist, yDist, zDist));
        standIn.absSnapTo(targetX, targetY, targetZ, targetYRot, targetXRot);
        chunkMove.run();
        Vec3 clientDeltaMovement = new Vec3(standIn.getX() - startX, standIn.getY() - startY, standIn.getZ() - startZ);
        standIn.setOnGroundWithMovement(onGround, horizontalCollision, clientDeltaMovement);
        // doCheckFallDamage: fall damage is G22.
        if (movedUpwards) {
            standIn.resetFallDistance();
        }
    }

    /**
     * {@code handleMovePlayer} while a teleport is awaited
     * ({@code :1082-1083}): only the rotation, wrapped, is taken.
     */
    public void snapRotation(Float yRot, Float xRot) {
        float targetYRot = Mth.wrapDegrees(yRot != null ? yRot : standIn.getYRot());
        float targetXRot = Mth.wrapDegrees(xRot != null ? xRot : standIn.getXRot());
        standIn.absSnapRotationTo(targetYRot, targetXRot);
    }

    /** {@code handlePlayerInput} ({@code :425-432}) once the client has loaded: the shift key. */
    public void handleInput(boolean shift) {
        standIn.setShiftKeyDown(shift);
    }

    /** {@code handlePlayerCommand} ({@code :1712-1722}): start or stop sprinting. */
    public void handleCommand(boolean sprinting) {
        standIn.setSprinting(sprinting);
    }

    /**
     * {@code handleAnimate} ({@code :1705-1709}): {@code ServerPlayer.swing},
     * vanilla's {@code LivingEntity.swing}, which decides whether the swing
     * restarts and sends the animation to the tracking players only.
     */
    public void swing(InteractionHand hand) {
        standIn.swing(hand);
        standIn.resetAttackStrengthTicker();
    }

    /** The stand-in's chunk source call: forward the packet, converted, to whoever tracks this body. */
    void broadcast(Packet<?> packet, boolean andSelf) {
        if (!(packet instanceof ClientboundAnimatePacket)
                && !(packet instanceof net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket)
                && !(packet instanceof net.minecraft.network.protocol.game.ClientboundEntityEventPacket)
                && !(packet instanceof net.minecraft.network.protocol.game.ClientboundDamageEventPacket)) {
            throw new IllegalStateException("a player stand-in sent " + packet.type());
        }
        sender.accept(this, new SentPacket(packet, andSelf));
    }

    /** The session's single vanilla player, including its inventory and menu. */
    public KestrelServerPlayer vanillaPlayer() { return standIn; }

    void spawnDeathItem(net.minecraft.world.item.ItemStack stack) {
        level.spawnItem(standIn.getX(), standIn.getY(), standIn.getZ(), stack);
    }

    public boolean vanillaRemoved() { return standIn.isRemoved(); }
    public boolean hasClientLoadedState() { return standIn.hasClientLoadedState(); }

    public void respawnAt(double x, double y, double z, float yRot, float xRot) {
        standIn.restartAfterRespawn();
        standIn.setHealth(standIn.getMaxHealth());
        standIn.getFoodData().setFoodLevel(20);
        standIn.getFoodData().setSaturation(5.0F);
        standIn.experienceProgress = 0.0F;
        standIn.experienceLevel = 0;
        standIn.totalExperience = 0;
        standIn.snapTo(x, y, z, yRot, xRot);
        standIn.setDeltaMovement(Vec3.ZERO);
        setRemovedState(false);
    }

    /** The client acknowledged its post-join load, matching the vanilla listener state. */
    public void markClientLoaded() { standIn.markClientLoaded(); }

    @Override
    public java.util.List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot,
            net.minecraft.world.item.ItemStack>> pairingEquipment() {
        java.util.List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot,
                net.minecraft.world.item.ItemStack>> result = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.VALUES) {
            net.minecraft.world.item.ItemStack stack = standIn.getItemBySlot(slot);
            if (!stack.isEmpty()) result.add(com.mojang.datafixers.util.Pair.of(slot, stack.copy()));
        }
        return result;
    }

    // ---- the level's entity phase ----

    private static final net.minecraft.world.entity.ai.attributes.AttributeModifier CREATIVE_BLOCK_INTERACTION_RANGE_MODIFIER =
            new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    net.minecraft.resources.Identifier.withDefaultNamespace("creative_mode_block_range"), 0.5,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
    private static final net.minecraft.world.entity.ai.attributes.AttributeModifier CREATIVE_ENTITY_INTERACTION_RANGE_MODIFIER =
            new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    net.minecraft.resources.Identifier.withDefaultNamespace("creative_mode_entity_range"), 2.0,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
    private static final net.minecraft.world.entity.ai.attributes.AttributeModifier WAYPOINT_TRANSMIT_RANGE_CROUCH_MODIFIER =
            new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    net.minecraft.resources.Identifier.withDefaultNamespace("waypoint_transmit_range_crouch"), -1.0,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    /**
     * The level phase subset already owned by G15, plus vanilla's
     * invulnerability countdown at {@code ServerPlayer.java:614-616}.
     * Menu synchronization remains in the established inventory path.
     */
    public void serverPlayerTick() {
        if (standIn.invulnerableTime > 0) standIn.invulnerableTime--;
        net.minecraft.world.entity.ai.attributes.AttributeInstance block = standIn.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
        if (block != null) {
            if (standIn.isCreative()) block.addOrUpdateTransientModifier(CREATIVE_BLOCK_INTERACTION_RANGE_MODIFIER);
            else block.removeModifier(CREATIVE_BLOCK_INTERACTION_RANGE_MODIFIER);
        }
        net.minecraft.world.entity.ai.attributes.AttributeInstance entity = standIn.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE);
        if (entity != null) {
            if (standIn.isCreative()) entity.addOrUpdateTransientModifier(CREATIVE_ENTITY_INTERACTION_RANGE_MODIFIER);
            else entity.removeModifier(CREATIVE_ENTITY_INTERACTION_RANGE_MODIFIER);
        }
        net.minecraft.world.entity.ai.attributes.AttributeInstance waypoint = standIn.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.WAYPOINT_TRANSMIT_RANGE);
        if (waypoint != null) {
            if (standIn.isCrouching()) waypoint.addOrUpdateTransientModifier(WAYPOINT_TRANSMIT_RANGE_CROUCH_MODIFIER);
            else waypoint.removeModifier(WAYPOINT_TRANSMIT_RANGE_CROUCH_MODIFIER);
        }
    }

    // ---- the connection tick ----

    /**
     * {@code ServerGamePacketListenerImpl.tickPlayer} ({@code :317-324}): the
     * position the tick starts at is restored after {@code doTick}. In
     * {@code doTick} ({@code ServerPlayer.java:677}), {@code Player.tick}
     * then {@code LivingEntity.tick} reach the tracker through
     * {@code aiStep} ({@code LivingEntity.java:3040-3150},
     * {@code Player.java:437-455}): the small-motion clamp, {@code travel}
     * with the server's zero input (vanilla's, colliding with the live
     * world), the freezing step, the swing timer, and the head following the
     * body's yaw; then
     * {@code Player.tick}'s {@code updatePlayerPose}. The rest is named in
     * the class comment.
     */
    public void tickPlayer() {
        double firstGoodX = standIn.getX();
        double firstGoodY = standIn.getY();
        double firstGoodZ = standIn.getZ();
        standIn.xo = firstGoodX;
        standIn.yo = firstGoodY;
        standIn.zo = firstGoodZ;
        standIn.doTick();
        // tickPlayer :323
        standIn.absSnapTo(firstGoodX, firstGoodY, firstGoodZ, standIn.getYRot(), standIn.getXRot());
    }

    // ---- what the tracker reads ----

    @Override
    public void updateDataBeforeSync() {
        standIn.updateDataBeforeSync();
        // The flags vanilla's own code sets on the stand-in (a jump's
        // needsSync, a bounce's syncPosition) are the tracker's to consume.
        if (standIn.needsSync) {
            needsSync = true;
            standIn.needsSync = false;
        }
        if (standIn.syncPosition) {
            syncPosition = true;
            standIn.syncPosition = false;
        }
        if (standIn.hurtMarked) {
            hurtMarked = true;
            standIn.hurtMarked = false;
        }
    }

    @Override
    public TrackingObserver selfObserver() {
        return self;
    }

    @Override public Vec3 position() { return standIn.position(); }
    @Override public Vec3 trackingPosition() { return standIn.trackingPosition(); }
    @Override public float getYRot() { return standIn.getYRot(); }
    @Override public float getXRot() { return standIn.getXRot(); }
    @Override public float getYHeadRot() { return standIn.getYHeadRot(); }
    @Override public Vec3 getDeltaMovement() { return standIn.getDeltaMovement(); }
    @Override public boolean onGround() { return standIn.onGround(); }
    @Override public boolean getRequiresPrecisePosition() { return standIn.getRequiresPrecisePosition(); }
    @Override public SynchedEntityData getEntityData() { return standIn.getEntityData(); }
    @Override public AttributeMap getAttributes() { return standIn.getAttributes(); }
    @Override public boolean isLiving() { return true; }
    @Override public AABB getBoundingBox() { return standIn.getBoundingBox(); }

    public boolean isShiftKeyDown() {
        return standIn.isShiftKeyDown();
    }

    public boolean isSprinting() {
        return standIn.isSprinting();
    }

    public net.minecraft.world.entity.Pose getPose() {
        return standIn.getPose();
    }

    @Override
    public String toString() {
        return "KestrelPlayerEntity[" + getId() + " " + standIn.getGameProfile().name() + " " + position() + "]";
    }
}
