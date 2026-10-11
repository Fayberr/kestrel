package dev.kestrel.net;

import dev.kestrel.loop.KestrelPos;

import java.util.UUID;

/**
 * Stage 3 player record: identity plus authoritative position, owned by
 * the tick thread. Facade types only (no vanilla types cross into the
 * net package's public surface except inside Outbound builders).
 *
 * <p>Position fields mirror what vanilla tracks per player
 * ({@code ServerGamePacketListenerImpl.java:247-252}): last accepted
 * x/y/z/yaw/pitch plus onGround. Movement packets update them on the
 * tick thread (I/O only enqueues); the teleport id counter matches
 * vanilla's wrapping increment
 * ({@code ServerGamePacketListenerImpl.java:1254-1267}).
 *
 * <p>Thread-safety: mutated on the tick thread only. The I/O thread
 * posts movement/teleport-ack events into a queue the tick thread
 * drains. No synchronization on the record itself.
 */
public final class KestrelPlayer {

    public final UUID profile;
    public final String name;
    /**
     * Network entity id, minted once at construction from the one minter
     * players and entities share ({@link dev.kestrel.entity.EntityIds}) and
     * stable for the session: it goes out in the join packet.
     */
    public final int entityId;

    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public boolean onGround;

    /** Last accepted position (vanilla's lastGood). */
    public double lastX;
    public double lastY;
    public double lastZ;

    /** Pending teleport id awaiting client accept (vanilla's awaitingTeleport). */
    public int awaitingTeleport;
    public boolean teleportPending;
    /** Ticks stage 2 of the two-stage join still waits (server-side only). */
    public int joinStall;
    /** Stage 1 (join + spawn chunks) already sent; stage 2 pending. Set false only for a fresh join. */
    public boolean joinHeadSent;

    /** Keepalive challenge pending client echo. */
    public long keepAliveChallenge;
    public boolean keepAlivePending;
    public long keepAliveSentAt;

    /** View distance negotiated for chunk streaming. */
    public int viewDistance = 10;

    /** Vanilla inventory and player inventory menu owned by this session. */
    public final dev.kestrel.item.PlayerInventory inventory = new dev.kestrel.item.PlayerInventory();

    /** Persisted compatibility view. Inventory contents are slice 6. */
    public int heldSlot() {
        return inventory.selectedSlot();
    }

    /** Block-breaking state ({@code ServerPlayerGameMode}'s break half), tick thread only. */
    final BlockBreaking blockBreaking = new BlockBreaking();

    /** True once the join burst placed the player; only placed players persist their position. */
    public boolean placed;

    /** Game time of the last received movement, block, or chat packet (idle timeout). */
    public long lastActionTick;

    /** Total movement violations recorded for this player. */
    public int moveViolations;

    /** Consecutive movement violations without an accepted move. */
    public int consecutiveMoveViolations;

    /** Cumulative vertical rise while airborne without ground contact. */
    public double airborneRise;

    /** True if player was on ground and eligible to initiate a jump. */
    public boolean jumpEligible = true;

    public KestrelPlayer(UUID profile, String name, double x, double y, double z) {
        this.profile = profile;
        this.name = name;
        this.entityId = dev.kestrel.entity.EntityIds.claim();
        this.x = this.lastX = x;
        this.y = this.lastY = y;
        this.z = this.lastZ = z;
    }

    /** Next teleport id (wraps like vanilla's increment). */
    public int nextTeleportId() {
        awaitingTeleport++;
        teleportPending = true;
        return awaitingTeleport;
    }

    /** Accept a teleport ack: clears pending when the id matches. */
    public boolean acceptTeleport(int id) {
        if (teleportPending && id == awaitingTeleport) {
            teleportPending = false;
            lastX = x;
            lastY = y;
            lastZ = z;
            airborneRise = 0.0;
            jumpEligible = true;
            consecutiveMoveViolations = 0;
            return true;
        }
        return false;
    }

    /** Apply an accepted movement update. */
    public void moveTo(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        if (onGround) {
            this.airborneRise = 0.0;
            this.jumpEligible = true;
        }
    }

    public KestrelPos blockPos() {
        return new KestrelPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public int chunkX() {
        return (int) Math.floor(x) >> 4;
    }

    public int chunkZ() {
        return (int) Math.floor(z) >> 4;
    }
}
