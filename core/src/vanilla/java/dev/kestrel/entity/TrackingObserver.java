package dev.kestrel.entity;

import dev.kestrel.net.Outbound;

/**
 * A player as the entity tracker sees it: the part of vanilla's
 * {@code ServerPlayer} that {@code ChunkMap.TrackedEntity.updatePlayer} and
 * the {@code ServerEntity.Synchronizer} sends read, and no more.
 *
 * <p>Identity is the object: the tracker keeps observers in identity sets,
 * as vanilla keeps {@code ServerPlayerConnection}s.
 */
public interface TrackingObserver {

    /** The player's own network entity id (for the passenger filter). */
    int entityId();

    double x();

    double y();

    double z();

    /**
     * {@code ChunkMap.getPlayerViewDistance}: the client's requested view
     * distance clamped to {@code [2, server view distance]}.
     */
    int viewDistance();

    /**
     * {@code ChunkMap.isChunkTracked}: the chunk is inside the player's
     * tracking view and has actually been sent (not still pending).
     */
    boolean isChunkTracked(int chunkX, int chunkZ);

    /** Queue one packet for this player's connection. */
    void send(Outbound.Packet packet);

    /**
     * {@code ChunkMap.updateChunkTracking}: recentre the chunk tracking view
     * that {@link #isChunkTracked} tests on the player's current chunk.
     * Vanilla does it when a player joins, when a move changes its section
     * (after the move has re-decided every entity), and at the start of each
     * tracker tick; the tracker calls this at those points for players with a
     * body. An observer whose view is always current needs nothing.
     */
    default void updateChunkTracking() {
    }
}
