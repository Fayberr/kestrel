package dev.kestrel.entity;

import dev.kestrel.net.EntityOutbound;
import dev.kestrel.net.Outbound;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Kestrel's entity tracker: the entity-map half of vanilla's
 * {@code ChunkMap} ({@code addEntity}/{@code removeEntity}
 * {@code ChunkMap.java:1155-1198}, {@code tick} {@code :1200-1235},
 * {@code move} {@code :1074-1083}) and its private inner
 * {@code TrackedEntity} ({@code :1355-1468}), which cannot be reused
 * because it is private. Per entity, an {@link EntitySync} decides the
 * packets; this class decides who receives them.
 *
 * <p>Explicit approximations, both because Kestrel has no chunk ticket or
 * {@code Visibility} lifecycle yet (S14 slice 5) and never unloads a chunk:
 * <ul>
 *   <li>{@code distanceManager.inEntityTickingRange} is taken as true for
 *       every tracked entity, so {@code sendChanges} runs every tick for
 *       each of them, as it does in vanilla for an entity inside simulation
 *       distance.</li>
 *   <li>"The chunk is tracked" is whatever the {@link TrackingObserver}
 *       answers; the live server answers from the chunks it has actually
 *       sent that player, not from a ticket system.</li>
 * </ul>
 * A joined player is both an observer and a tracked entity (its body, G15),
 * added with {@link #addPlayer(KestrelEntity, TrackingObserver)}, and the
 * "moved players" pass is driven off each body's tracked section as in
 * vanilla. An observer added without a body (the tracker gates' test
 * observers) keeps the slice 2 approximation: its own section, checked
 * after the bodies.
 *
 * <p>Thread-safety: tick thread only.
 */
public final class EntityTracker {

    /** Same fastutil map as vanilla, so multi-entity iteration order matches. */
    private final Int2ObjectMap<Tracked> entityMap = new Int2ObjectOpenHashMap<>();
    /** {@code ServerLevel.players()}: join order. */
    private final List<TrackingObserver> players = new ArrayList<>();
    /** Observers without a body: the section they were last seen in. */
    private final Map<TrackingObserver, Long> lastPlayerSection = new IdentityHashMap<>();
    /** Each body's own tracked entry, by its observer. */
    private final Map<TrackingObserver, Tracked> bodies = new IdentityHashMap<>();
    /** {@code ServerPlayer.lastSectionPos}: a body's section as of its last move. */
    private final Map<TrackingObserver, Long> moveSection = new IdentityHashMap<>();
    private int rangePercentage;

    /**
     * @param rangePercentage {@code entity-broadcast-range-percentage} from
     *     server.properties (vanilla default 100)
     */
    public EntityTracker(int rangePercentage) {
        this.rangePercentage = rangePercentage;
    }

    /** Vanilla reads the property live on every range computation; so does this. */
    public void setRangePercentage(int rangePercentage) {
        this.rangePercentage = rangePercentage;
    }

    /** {@code DedicatedServer.getScaledTrackingDistance}, integer arithmetic as vanilla. */
    int scaledTrackingDistance(int range) {
        return rangePercentage * range / 100;
    }

    // ---- entities ----

    /** {@code ChunkMap.addEntity} for a non-player entity. */
    public void addEntity(KestrelEntity entity) {
        int range = entity.getType().clientTrackingRange() * 16;
        if (range == 0) {
            return;
        }
        if (entityMap.containsKey(entity.getId())) {
            throw new IllegalStateException("Entity is already tracked!");
        }
        Tracked tracked = new Tracked(entity, range, entity.getType().updateInterval(), entity.getType().trackDeltas());
        entityMap.put(entity.getId(), tracked);
        tracked.updatePlayers(players);
    }

    /** {@code ChunkMap.removeEntity} for a non-player entity. */
    public void removeEntity(KestrelEntity entity) {
        Tracked tracked = entityMap.remove(entity.getId());
        if (tracked != null) {
            tracked.broadcastRemoved();
        }
    }

    // ---- players ----

    /**
     * The bodies of the joined players, in join order. Vanilla keeps players in the same entity
     * sections as everything else, so a box query finds them; here they live in the tracker
     * only, and {@code VanillaEntityLevel.getEntities} adds them from this list.
     */
    public List<KestrelEntity> playerBodies() {
        List<KestrelEntity> out = new ArrayList<>(bodies.size());
        for (TrackingObserver observer : players) {
            Tracked tracked = bodies.get(observer);
            if (tracked != null) {
                out.add(tracked.entity);
            }
        }
        return out;
    }

    /**
     * A player enters the level: {@code ChunkMap.addEntity} for a
     * {@code ServerPlayer} ({@code :1155-1181}), then the level's player list
     * ({@code ServerLevel.EntityCallbacks.onTrackingStart}, {@code :1936-1939}:
     * the chunk source first, the list second). The body's own entry is
     * created and offered to everyone already in; then every other tracked
     * entity is offered to the new player.
     */
    public void addPlayer(KestrelEntity body, TrackingObserver player) {
        if (lastPlayerSection.containsKey(player) || bodies.containsKey(player)) {
            return;
        }
        if (body.getId() != player.entityId()) {
            throw new IllegalArgumentException("body " + body.getId() + " is not observer " + player.entityId());
        }
        int range = body.getType().clientTrackingRange() * 16;
        if (entityMap.containsKey(body.getId())) {
            throw new IllegalStateException("Entity is already tracked!");
        }
        Tracked own = new Tracked(body, range, body.getType().updateInterval(), body.getType().trackDeltas());
        entityMap.put(body.getId(), own);
        bodies.put(player, own);
        own.updatePlayers(players);
        // updatePlayerStatus(player, true): the section, then the view.
        moveSection.put(player, body.sectionKey());
        player.updateChunkTracking();
        for (Tracked tracked : entityMap.values()) {
            if (tracked != own) {
                tracked.updatePlayer(player);
            }
        }
        players.add(player);
    }

    /**
     * An observer with no body (the tracker gates' test observers): the
     * player half of {@code ChunkMap.addEntity}, every tracked entity
     * re-evaluated for it.
     */
    public void addPlayer(TrackingObserver player) {
        if (lastPlayerSection.containsKey(player) || bodies.containsKey(player)) {
            return;
        }
        players.add(player);
        lastPlayerSection.put(player, sectionOf(player));
        for (Tracked tracked : entityMap.values()) {
            tracked.updatePlayer(player);
        }
    }

    /**
     * A player leaves: {@code ChunkMap.removeEntity} ({@code :1183-1198}),
     * then the level's list ({@code onTrackingEnd}, {@code :1971-1974}).
     * Each entity it saw sends it a remove; then its body's entry is
     * dropped and everyone who saw the body is sent its remove.
     */
    public void removePlayer(TrackingObserver player) {
        for (Tracked tracked : entityMap.values()) {
            tracked.removePlayer(player);
        }
        Tracked own = bodies.remove(player);
        moveSection.remove(player);
        if (own != null) {
            entityMap.remove(own.entity.getId());
            own.broadcastRemoved();
        }
        players.remove(player);
        lastPlayerSection.remove(player);
    }

    /** Remove only the killed body. Its connection remains an observer until respawn. */
    public void removePlayerBody(TrackingObserver player) {
        Tracked own = bodies.remove(player);
        moveSection.remove(player);
        if (own != null) {
            entityMap.remove(own.entity.getId());
            own.broadcastRemoved();
        }
    }

    /** Re-add an in-place respawned body while preserving its observer. */
    public void addPlayerBody(KestrelEntity body, TrackingObserver player) {
        int range = body.getType().clientTrackingRange() * 16;
        Tracked tracked = new Tracked(body, range, body.getType().updateInterval(), body.getType().trackDeltas());
        entityMap.put(body.getId(), tracked);
        bodies.put(player, tracked);
        moveSection.put(player, body.sectionKey());
        tracked.updatePlayers(players);
    }

    /**
     * {@code ChunkMap.move} ({@code :1074-1083}): a player's position
     * changed (movement packet or teleport). The player's own body is
     * re-offered to everyone; every other entity re-decides for the player.
     */
    public void movePlayer(TrackingObserver player) {
        Tracked own = bodies.get(player);
        for (Tracked tracked : entityMap.values()) {
            if (tracked == own) {
                tracked.updatePlayers(players);
            } else {
                tracked.updatePlayer(player);
            }
        }
        // :1085-1110: the view follows only a section change, after the loop.
        if (own != null) {
            long now = own.entity.sectionKey();
            Long before = moveSection.put(player, now);
            if (before == null || before != now) {
                player.updateChunkTracking();
            }
        }
    }

    // ---- tick ----

    /**
     * {@code ChunkMap.tick()} ({@code :1200-1235}), called from
     * {@code ServerChunkCache.tick} in the {@code chunkSource} phase of
     * {@code ServerLevel.tick}: after block and fluid ticks, before block
     * events and entity ticking.
     */
    public void tick() {
        // :1201-1203: every player's view is recentred first.
        for (TrackingObserver player : players) {
            if (bodies.containsKey(player)) {
                player.updateChunkTracking();
            }
        }
        List<TrackingObserver> movedPlayers = new ArrayList<>();
        for (Tracked tracked : entityMap.values()) {
            long newPos = tracked.entity.sectionKey();
            boolean sectionPosChanged = tracked.lastSectionPos != newPos;
            if (sectionPosChanged) {
                tracked.updatePlayers(players);
                if (tracked.self != null) {
                    movedPlayers.add(tracked.self);
                }
                tracked.lastSectionPos = newPos;
            }
            // inEntityTickingRange: see the class comment.
            tracked.sync.sendChanges();
        }

        // Observers without a body: their own section, after the bodies.
        for (TrackingObserver player : players) {
            if (bodies.containsKey(player)) {
                continue;
            }
            long now = sectionOf(player);
            Long before = lastPlayerSection.put(player, now);
            if (before == null || before != now) {
                movedPlayers.add(player);
            }
        }
        if (!movedPlayers.isEmpty()) {
            for (Tracked tracked : entityMap.values()) {
                tracked.updatePlayers(movedPlayers);
            }
        }
    }

    private static long sectionOf(TrackingObserver player) {
        return SectionPos.asLong(
                SectionPos.blockToSectionCoord(Mth.floor(player.x())),
                SectionPos.blockToSectionCoord(Mth.floor(player.y())),
                SectionPos.blockToSectionCoord(Mth.floor(player.z())));
    }

    // ---- views for the gate ----

    public int trackedCount() {
        return entityMap.size();
    }

    public int playerCount() {
        return players.size();
    }

    public boolean isTracked(int entityId) {
        return entityMap.containsKey(entityId);
    }

    public @Nullable KestrelEntity entityByUuid(UUID uuid) {
        for (Tracked tracked : entityMap.values()) {
            if (tracked.entity.getUUID().equals(uuid)) {
                return tracked.entity;
            }
        }
        return null;
    }

    /** Who currently sees {@code entityId}; empty when untracked. */
    public Set<TrackingObserver> seenBy(int entityId) {
        Tracked tracked = entityMap.get(entityId);
        return tracked == null ? Set.of() : Collections.unmodifiableSet(tracked.seenBy);
    }

    /** The entity's sync state, for the gate; null when untracked. */
    EntitySync syncOf(int entityId) {
        Tracked tracked = entityMap.get(entityId);
        return tracked == null ? null : tracked.sync;
    }

    /**
     * {@code ChunkMap.sendToTrackingPlayers}: the falling block's landing
     * write, a player's swing.
     */
    public void sendToTrackingPlayers(KestrelEntity entity, Outbound.Packet packet) {
        Tracked tracked = entityMap.get(entity.getId());
        if (tracked != null) {
            tracked.sendToTrackingPlayers(packet);
        }
    }

    /** {@code ChunkMap.sendToTrackingPlayersAndSelf}. */
    public void sendToTrackingPlayersAndSelf(KestrelEntity entity, Outbound.Packet packet) {
        Tracked tracked = entityMap.get(entity.getId());
        if (tracked != null) {
            tracked.sendToTrackingPlayersAndSelf(packet);
        }
    }

    /** {@code TrackedEntity.getEffectiveRange}, for the gate; -1 when untracked. */
    int effectiveRangeOf(int entityId) {
        Tracked tracked = entityMap.get(entityId);
        return tracked == null ? -1 : tracked.getEffectiveRange();
    }

    /** {@code ChunkMap.TrackedEntity}: one entity, its sync state and who sees it. */
    private final class Tracked implements EntitySync.Sink {
        final KestrelEntity entity;
        /** The body's own connection when the entity is a player, else null. */
        final @Nullable TrackingObserver self;
        final EntitySync sync;
        final int range;
        long lastSectionPos;
        /** Vanilla uses an identity set; insertion order here keeps sends deterministic. */
        final Set<TrackingObserver> seenBy = new LinkedHashSet<>();

        Tracked(KestrelEntity entity, int range, int updateInterval, boolean trackDelta) {
            this.entity = entity;
            this.self = entity.selfObserver();
            this.range = range;
            this.sync = new EntitySync(entity, updateInterval, trackDelta, this);
            this.lastSectionPos = entity.sectionKey();
        }

        @Override
        public void sendToTrackingPlayers(Outbound.Packet packet) {
            for (TrackingObserver player : seenBy) {
                player.send(packet);
            }
        }

        /** {@code TrackedEntity.sendToTrackingPlayersAndSelf}. */
        @Override
        public void sendToTrackingPlayersAndSelf(Outbound.Packet packet) {
            sendToTrackingPlayers(packet);
            if (self != null) {
                self.send(packet);
            }
        }

        /** {@code TrackedEntity.broadcastRemoved}; the set is then emptied, the entry is gone. */
        void broadcastRemoved() {
            for (TrackingObserver player : seenBy) {
                removePairing(player);
            }
            seenBy.clear();
        }

        /** {@code TrackedEntity.removePlayer}. */
        void removePlayer(TrackingObserver player) {
            if (seenBy.remove(player)) {
                removePairing(player);
            }
        }

        /** {@code ServerEntity.removePairing}. */
        private void removePairing(TrackingObserver player) {
            player.send(EntityOutbound.removeEntities(entity.getId()));
        }

        /** {@code ServerEntity.addPairing}: the pairing bundle, then seen. */
        private void addPairing(TrackingObserver player) {
            for (Outbound.Packet p : sync.pairingBundle()) {
                player.send(p);
            }
        }

        /** {@code TrackedEntity.updatePlayer}, {@code ChunkMap.java:1421-1444}. */
        void updatePlayer(TrackingObserver player) {
            if (player.entityId() == entity.getId()) {
                // The entity is this player itself.
                return;
            }
            double dx = player.x() - entity.position().x;
            double dz = player.z() - entity.position().z;
            int playerViewDistance = player.viewDistance();
            double visibleRange = Math.min(getEffectiveRange(), playerViewDistance * 16);
            double distanceSquared = dx * dx + dz * dz;
            double rangeSquared = visibleRange * visibleRange;
            boolean visibleToPlayer = distanceSquared <= rangeSquared
                    && entity.broadcastToPlayer(player)
                    && player.isChunkTracked(entity.chunkX(), entity.chunkZ());
            if (visibleToPlayer) {
                if (seenBy.add(player)) {
                    addPairing(player);
                }
            } else {
                removePlayer(player);
            }
        }

        /** {@code TrackedEntity.getEffectiveRange}, {@code ChunkMap.java:1450-1461}. */
        int getEffectiveRange() {
            int effectiveRange = range;
            for (KestrelEntity passenger : entity.getIndirectPassengers()) {
                int passengerRange = passenger.getType().clientTrackingRange() * 16;
                if (passengerRange > effectiveRange) {
                    effectiveRange = passengerRange;
                }
            }
            return scaledTrackingDistance(effectiveRange);
        }

        void updatePlayers(List<TrackingObserver> list) {
            for (TrackingObserver player : list) {
                updatePlayer(player);
            }
        }
    }
}
