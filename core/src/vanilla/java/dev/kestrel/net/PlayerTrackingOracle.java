package dev.kestrel.net;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.TextFilter;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.debug.LevelDebugSynchronizers;
import net.minecraft.util.debug.ServerDebugSubscribers;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The vanilla side of {@link PlayerTrackingParityCheck}: real
 * {@link ServerPlayer}s, each with a real {@link ServerGamePacketListenerImpl}
 * whose handlers receive the gate's inbound packets through vanilla's own
 * serverbound codec, ticking by vanilla's own {@code doTick}, tracked by
 * vanilla's own {@code ChunkMap.TrackedEntity} (a private inner class,
 * constructed and driven by reflection) and its {@code ServerEntity}.
 *
 * <p>What is not vanilla's own code, and why:
 * <ul>
 *   <li>The server, level, player list, chunk cache and advancements are
 *       vanilla subclasses allocated without running a constructor
 *       ({@code Unsafe.allocateInstance}); each answers exactly the calls the
 *       paths above make, listed on each override.</li>
 *   <li>{@code ChunkMap} itself cannot run (distance manager, chunk holders,
 *       ticket system). Its {@code addEntity}, {@code removeEntity},
 *       {@code move} and {@code tick} are thin loops over the entity map;
 *       {@link Tracker} transcribes them line for line
 *       ({@code ChunkMap.java:1074-1235}) and calls the real
 *       {@code TrackedEntity} for every decision.</li>
 *   <li>{@code PlayerList.placeNewPlayer} and {@code remove} are
 *       transcribed for the parts this slice covers: the listener, the
 *       join teleport, the two tab list packets, the level add, and on
 *       leave the level removal then the tab list removal.</li>
 *   <li>Which chunks a player has been sent is the gate's input: Kestrel's
 *       {@code ChunkSender} decides it and the gate copies it into each
 *       vanilla player's {@code PlayerChunkSender} pending set, so both
 *       sides answer {@code isChunkTracked} from the same chunk stream.</li>
 * </ul>
 */
final class PlayerTrackingOracle {

    private static final sun.misc.Unsafe UNSAFE = unsafe();

    final RegistryAccess access;
    final ProtocolInfo<ClientGamePacketListener> clientbound;
    final OracleServer server;
    final OracleLevel level;
    final OraclePlayerList playerList;
    final Tracker tracker;
    /** {@code PlayerList.players}: join order. */
    final List<VPlayer> players = new ArrayList<>();
    /** {@code PlayerList.ops}: the gate fills it directly, as {@code PlayerList.op} would. */
    final net.minecraft.server.players.ServerOpList ops;

    /** A command set with no command: what a server without a data pack answers with. */
    static net.minecraft.commands.Commands emptyCommands() {
        try {
            net.minecraft.commands.Commands commands =
                    (net.minecraft.commands.Commands) UNSAFE.allocateInstance(net.minecraft.commands.Commands.class);
            put(commands, net.minecraft.commands.Commands.class, "dispatcher",
                    new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>());
            return commands;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ServerPlayer> vanillaPlayers() {
        try {
            Field f = PlayerList.class.getDeclaredField("players");
            f.setAccessible(true);
            return (List<ServerPlayer>) f.get(playerList);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<java.util.UUID, ServerPlayer> vanillaPlayersByUuid() {
        try {
            Field f = PlayerList.class.getDeclaredField("playersByUUID");
            f.setAccessible(true);
            return (java.util.Map<java.util.UUID, ServerPlayer>) f.get(playerList);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code PlayerList.sendPlayerPermissionLevel}, as vanilla's join and respawn call it. */
    void sendPermissionLevel(VPlayer player) {
        playerList.sendPlayerPermissionLevel(player.player);
    }

    PlayerTrackingOracle(RegistryAccess access, BlockGetter blocks, int serverViewDistance, int rangePercentage) {
        this.access = access;
        this.clientbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access));
        try {
            server = (OracleServer) UNSAFE.allocateInstance(OracleServer.class);
            server.access = access;
            server.rangePercentage = rangePercentage;
            put(server, MinecraftServer.class, "debugSubscribers", new ServerDebugSubscribers(null));
            put(server, MinecraftServer.class, "tickRateManager", new ServerTickRateManager(server));
            // Handlers check they run on the server thread: this one.
            put(server, MinecraftServer.class, "packetProcessor", new net.minecraft.network.PacketProcessor(Thread.currentThread()));
            playerList = (OraclePlayerList) UNSAFE.allocateInstance(OraclePlayerList.class);
            playerList.server = server;
            playerList.oracle = this;
            server.playerList = playerList;
            // PlayerList.java:112-117: vanilla's own lists, so its own broadcasts and selectors run.
            put(playerList, PlayerList.class, "server", server);
            put(playerList, PlayerList.class, "players", new ArrayList<ServerPlayer>());
            put(playerList, PlayerList.class, "playersByUUID", new java.util.HashMap<java.util.UUID, ServerPlayer>());
            ops = new net.minecraft.server.players.ServerOpList(new java.io.File("/nonexistent/ops.json"),
                    new net.minecraft.server.notifications.EmptyNotificationService()) {
                @Override
                public void save() {
                }
            };
            put(playerList, PlayerList.class, "ops", ops);
            server.commands = emptyCommands();
            level = OracleLevel.create(server, blocks);
            put(level, ServerLevel.class, "debugSynchronizers", new LevelDebugSynchronizers(level));
            server.scoreboard = new ServerScoreboard(server);
            tracker = new Tracker(this, serverViewDistance);
            level.chunkCache = OracleChunkCache.create(tracker);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // =====================================================================
    // Players
    // =====================================================================

    /** One vanilla player: the ServerPlayer, its listener, and what it was sent. */
    final class VPlayer {
        final ServerPlayer player;
        final ServerGamePacketListenerImpl listener;
        final CaptureConnection connection;
        final SentChunks chunks;
        final ProtocolInfo<ServerGamePacketListener> serverbound;

        VPlayer(ServerPlayer player, ServerGamePacketListenerImpl listener, CaptureConnection connection,
                SentChunks chunks) {
            this.player = player;
            this.listener = listener;
            this.connection = connection;
            this.chunks = chunks;
            this.serverbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access), listener);
        }

        /** Decode one serverbound play packet with vanilla's codec and run its handler. */
        @SuppressWarnings("unchecked")
        void receive(int id, byte[] payload) {
            ByteBuf buf = Unpooled.buffer();
            try {
                FriendlyByteBuf fb = new FriendlyByteBuf(buf);
                fb.writeVarInt(id);
                fb.writeBytes(payload);
                Packet<? super ServerGamePacketListener> packet =
                        (Packet<? super ServerGamePacketListener>) serverbound.codec().decode(buf);
                if (buf.readableBytes() != 0) {
                    throw new IllegalStateException("packet " + id + " left " + buf.readableBytes() + " bytes");
                }
                packet.handle(listener);
            } finally {
                buf.release();
            }
        }

        /**
         * Answer every keep-alive the listener sent at once, as a client
         * would: the listener's own wall-clock timeout would otherwise
         * disconnect a long run.
         */
        void answerKeepAlives() {
            for (Packet<?> p : List.copyOf(connection.sent)) {
                if (p instanceof net.minecraft.network.protocol.common.ClientboundKeepAlivePacket k) {
                    listener.handleKeepAlive(new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(k.getId()));
                }
            }
        }

        /** Every packet sent to this player since the last drain, encoded, bundles split. */
        List<String> drain() {
            List<String> out = new ArrayList<>();
            for (Packet<?> p : connection.sent) {
                out.addAll(encode(p));
            }
            connection.sent.clear();
            return out;
        }

        @Override
        public String toString() {
            return player.getGameProfile().name();
        }
    }

    /**
     * Construct a player the way {@code PrepareSpawnTask.spawn} does: a fresh
     * ServerPlayer, then {@code snapTo} the spawn. The entity id is set to
     * Kestrel's, so the byte streams can be compared.
     */
    VPlayer create(GameProfile profile, int entityId, ClientInformation info, double x, double y, double z,
            float yRot, float xRot) {
        level.nextId = entityId;
        ServerPlayer player = new ServerPlayer(server, level, profile, info);
        player.snapTo(x, y, z, yRot, xRot);
        CaptureConnection connection = new CaptureConnection();
        ServerGamePacketListenerImpl listener = new ServerGamePacketListenerImpl(server, connection, player,
                new CommonListenerCookie(profile, 0, info, false));
        SentChunks chunks = new SentChunks();
        try {
            put(listener, ServerGamePacketListenerImpl.class, "chunkSender", chunks);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return new VPlayer(player, listener, connection, chunks);
    }

    /**
     * {@code PlayerList.placeNewPlayer} ({@code PlayerList.java:146-216}), the
     * parts this slice covers, in its order: the join teleport, the tab list
     * of everyone already in (to the new player), the new player to everyone
     * (itself included), then {@code level.addNewPlayer}.
     */
    void placeNewPlayer(VPlayer joining) {
        // PlayerList.java:191, before the teleport and the tab list.
        sendPermissionLevel(joining);
        joining.listener.teleport(joining.player.getX(), joining.player.getY(), joining.player.getZ(),
                joining.player.getYRot(), joining.player.getXRot());
        List<ServerPlayer> before = new ArrayList<>();
        for (VPlayer p : players) {
            before.add(p.player);
        }
        joining.player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(before));
        players.add(joining);
        vanillaPlayers().add(joining.player);
        vanillaPlayersByUuid().put(joining.player.getUUID(), joining.player);
        playerList.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(joining.player)));
        // ServerLevel.addNewPlayer -> addPlayer -> entity manager -> onTrackingStart:
        // chunk source addEntity first, then the level's player list.
        tracker.addEntity(joining.player);
        level.players.add(joining.player);
    }

    /**
     * {@code PlayerList.remove} ({@code PlayerList.java:306-339}), the parts
     * this slice covers: {@code removePlayerImmediately} (the tracker's
     * {@code removeEntity}, then the level's list), then the tab list
     * removal to everyone left.
     */
    void remove(VPlayer leaving) {
        leaving.player.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        tracker.removeEntity(leaving.player);
        level.players.remove(leaving.player);
        players.remove(leaving);
        vanillaPlayers().remove(leaving.player);
        if (vanillaPlayersByUuid().get(leaving.player.getUUID()) == leaving.player) {
            vanillaPlayersByUuid().remove(leaving.player.getUUID());
        }
        playerList.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(leaving.player.getUUID())));
    }

    /** Level phase: ChunkMap tracking, then each ServerPlayer entity tick. */
    void tickLevel() {
        tracker.tick();
        for (VPlayer p : players) {
            if (!p.player.isRemoved()) {
                p.player.tick();
            }
        }
    }

    /** Connection phase, after queued packets were handled, as in MinecraftServer.tickChildren. */
    void tickConnections() {
        for (VPlayer p : players) {
            p.listener.tick();
        }
    }

    // =====================================================================
    // Encoding
    // =====================================================================

    @SuppressWarnings({"unchecked", "rawtypes"})
    List<String> encode(Packet<?> packet) {
        List<String> out = new ArrayList<>();
        clientbound.bundlerInfo().unbundlePacket(packet, p -> {
            ByteBuf buf = Unpooled.buffer();
            try {
                clientbound.codec().encode(buf, (Packet) p);
                FriendlyByteBuf fb = new FriendlyByteBuf(buf);
                int id = fb.readVarInt();
                byte[] payload = new byte[fb.readableBytes()];
                fb.readBytes(payload);
                out.add(id + ":" + HexFormat.of().formatHex(payload));
            } finally {
                buf.release();
            }
        });
        return out;
    }

    // =====================================================================
    // ChunkMap's entity loops over the real TrackedEntity
    // =====================================================================

    /**
     * {@code ChunkMap}'s entity map and the four loops that walk it,
     * transcribed; every per-entity decision is the real private
     * {@code ChunkMap.TrackedEntity}, reached by reflection.
     */
    static final class Tracker {
        private static final Class<?> TRACKED;
        private static final Constructor<?> TRACKED_NEW;
        private static final Method UPDATE_PLAYER;
        private static final Method UPDATE_PLAYERS;
        private static final Method REMOVE_PLAYER;
        private static final Method BROADCAST_REMOVED;
        private static final Field TRACKED_ENTITY;
        private static final Field TRACKED_SERVER_ENTITY;
        private static final Field TRACKED_LAST_SECTION;
        private static final Field TRACKED_SEEN_BY;

        static {
            try {
                TRACKED = Class.forName("net.minecraft.server.level.ChunkMap$TrackedEntity");
                TRACKED_NEW = TRACKED.getDeclaredConstructor(ChunkMap.class, Entity.class, int.class, int.class, boolean.class);
                TRACKED_NEW.setAccessible(true);
                UPDATE_PLAYER = TRACKED.getDeclaredMethod("updatePlayer", ServerPlayer.class);
                UPDATE_PLAYERS = TRACKED.getDeclaredMethod("updatePlayers", List.class);
                REMOVE_PLAYER = TRACKED.getDeclaredMethod("removePlayer", ServerPlayer.class);
                BROADCAST_REMOVED = TRACKED.getDeclaredMethod("broadcastRemoved");
                TRACKED_ENTITY = TRACKED.getDeclaredField("entity");
                TRACKED_SERVER_ENTITY = TRACKED.getDeclaredField("serverEntity");
                TRACKED_LAST_SECTION = TRACKED.getDeclaredField("lastSectionPos");
                TRACKED_SEEN_BY = TRACKED.getDeclaredField("seenBy");
                for (java.lang.reflect.AccessibleObject o : new java.lang.reflect.AccessibleObject[] {
                        UPDATE_PLAYER, UPDATE_PLAYERS, REMOVE_PLAYER, BROADCAST_REMOVED,
                        TRACKED_ENTITY, TRACKED_SERVER_ENTITY, TRACKED_LAST_SECTION, TRACKED_SEEN_BY}) {
                    o.setAccessible(true);
                }
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final PlayerTrackingOracle oracle;
        final ChunkMap chunkMap;
        /** {@code ChunkMap.entityMap}: the same fastutil map, so iteration order matches. */
        final Int2ObjectOpenHashMap<Object> entityMap = new Int2ObjectOpenHashMap<>();

        Tracker(PlayerTrackingOracle oracle, int serverViewDistance) throws ReflectiveOperationException {
            this.oracle = oracle;
            this.chunkMap = (ChunkMap) UNSAFE.allocateInstance(ChunkMap.class);
            put(chunkMap, ChunkMap.class, "level", oracle.level);
            Field vd = ChunkMap.class.getDeclaredField("serverViewDistance");
            UNSAFE.putInt(chunkMap, UNSAFE.objectFieldOffset(vd), serverViewDistance);
        }

        /** {@code ChunkMap.addEntity} ({@code :1155-1181}). */
        void addEntity(Entity entity) {
            EntityType<?> type = entity.getType();
            int range = type.clientTrackingRange() * 16;
            if (range == 0) {
                return;
            }
            int updateInterval = type.updateInterval();
            if (entityMap.containsKey(entity.getId())) {
                throw new IllegalStateException("Entity is already tracked!");
            }
            Object tracked = newTracked(entity, range, updateInterval, type.trackDeltas());
            entityMap.put(entity.getId(), tracked);
            updatePlayers(tracked, oracle.level.players());
            if (entity instanceof ServerPlayer player) {
                // updatePlayerStatus(player, true): the section, then the chunk tracking view.
                player.setLastSectionPos(SectionPos.of(player));
                updateChunkTracking(player);
                for (Object e : entityMap.values()) {
                    if (entityOf(e) != player) {
                        updatePlayer(e, player);
                    }
                }
            }
        }

        /** {@code ChunkMap.removeEntity} ({@code :1183-1198}). */
        void removeEntity(Entity entity) {
            if (entity instanceof ServerPlayer player) {
                for (Object tracked : entityMap.values()) {
                    invoke(REMOVE_PLAYER, tracked, player);
                }
            }
            Object tracked = entityMap.remove(entity.getId());
            if (tracked != null) {
                invoke(BROADCAST_REMOVED, tracked);
            }
        }

        /**
         * {@code ChunkMap.move} ({@code :1074-1111}); the distance manager and
         * the player map are not modelled, the chunk tracking view is.
         */
        void move(ServerPlayer player) {
            for (Object tracked : entityMap.values()) {
                if (entityOf(tracked) == player) {
                    updatePlayers(tracked, oracle.level.players());
                } else {
                    updatePlayer(tracked, player);
                }
            }
            SectionPos oldSection = player.getLastSectionPos();
            SectionPos newSection = SectionPos.of(player);
            if (oldSection.asLong() != newSection.asLong()) {
                player.setLastSectionPos(newSection);
                updateChunkTracking(player);
            }
        }

        /** {@code ChunkMap.tick()} ({@code :1200-1235}), every entity in ticking range. */
        void tick() {
            for (ServerPlayer player : oracle.level.players()) {
                updateChunkTracking(player);
            }
            List<ServerPlayer> movedPlayers = new ArrayList<>();
            List<ServerPlayer> players = oracle.level.players();
            for (Object tracked : entityMap.values()) {
                SectionPos oldPos = (SectionPos) get(TRACKED_LAST_SECTION, tracked);
                Entity entity = entityOf(tracked);
                SectionPos newPos = SectionPos.of(entity);
                boolean sectionPosChanged = !Objects.equals(oldPos, newPos);
                if (sectionPosChanged) {
                    updatePlayers(tracked, players);
                    if (entity instanceof ServerPlayer serverPlayer) {
                        movedPlayers.add(serverPlayer);
                    }
                    set(TRACKED_LAST_SECTION, tracked, newPos);
                }
                // inEntityTickingRange: every scenario stays inside simulation distance.
                serverEntity(tracked).sendChanges();
            }
            if (!movedPlayers.isEmpty()) {
                for (Object tracked : entityMap.values()) {
                    updatePlayers(tracked, movedPlayers);
                }
            }
        }

        /**
         * {@code ChunkMap.updateChunkTracking} ({@code :1113-1123}) without the
         * chunk send: the view is recentred; which chunks are pending comes
         * from the gate.
         */
        void updateChunkTracking(ServerPlayer player) {
            ChunkPos chunkPos = player.chunkPosition();
            int playerViewDistance = Math.clamp(player.requestedViewDistance(), 2, serverViewDistance());
            if (!(player.getChunkTrackingView() instanceof ChunkTrackingView.Positioned view
                    && view.center().equals(chunkPos)
                    && view.viewDistance() == playerViewDistance)) {
                player.setChunkTrackingView(ChunkTrackingView.of(chunkPos, playerViewDistance));
            }
        }

        private int serverViewDistance() {
            try {
                Field vd = ChunkMap.class.getDeclaredField("serverViewDistance");
                return UNSAFE.getInt(chunkMap, UNSAFE.objectFieldOffset(vd));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        /** {@code ChunkMap.sendToTrackingPlayers(AndSelf)}: the real entry's own method. */
        void sendToTracking(Entity entity, Packet<? super ClientGamePacketListener> packet, boolean andSelf) {
            Object tracked = entityMap.get(entity.getId());
            if (tracked != null) {
                ServerEntity.Synchronizer sync = (ServerEntity.Synchronizer) tracked;
                if (andSelf) {
                    sync.sendToTrackingPlayersAndSelf(packet);
                } else {
                    sync.sendToTrackingPlayers(packet);
                }
            }
        }

        net.minecraft.server.level.ServerEntity serverEntityOf(int id) {
            Object tracked = entityMap.get(id);
            return tracked == null ? null : serverEntity(tracked);
        }

        @SuppressWarnings("unchecked")
        java.util.Set<Object> seenBy(int id) {
            Object tracked = entityMap.get(id);
            return tracked == null ? java.util.Set.of() : (java.util.Set<Object>) get(TRACKED_SEEN_BY, tracked);
        }

        private Object newTracked(Entity entity, int range, int interval, boolean trackDelta) {
            try {
                return TRACKED_NEW.newInstance(chunkMap, entity, range, interval, trackDelta);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        private void updatePlayer(Object tracked, ServerPlayer player) {
            invoke(UPDATE_PLAYER, tracked, player);
        }

        private void updatePlayers(Object tracked, List<ServerPlayer> players) {
            invoke(UPDATE_PLAYERS, tracked, players);
        }

        private static Entity entityOf(Object tracked) {
            return (Entity) get(TRACKED_ENTITY, tracked);
        }

        private static net.minecraft.server.level.ServerEntity serverEntity(Object tracked) {
            return (net.minecraft.server.level.ServerEntity) get(TRACKED_SERVER_ENTITY, tracked);
        }

        private static Object get(Field f, Object o) {
            try {
                return f.get(o);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void set(Field f, Object o, Object v) {
            try {
                f.set(o, v);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void invoke(Method m, Object target, Object... args) {
            try {
                m.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException r) {
                    throw r;
                }
                throw new IllegalStateException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // =====================================================================
    // Vanilla stand-ins with no constructor run
    // =====================================================================

    /** A dedicated server that answers what the player, listener and tracker paths ask. */
    static final class OracleServer extends DedicatedServer {
        RegistryAccess access;
        int rangePercentage;
        OraclePlayerList playerList;
        ServerScoreboard scoreboard;
        net.minecraft.commands.Commands commands;
        int maxPlayers = 20;
        int opLevel = 4;

        private OracleServer() {
            super(null, null, null, null, null, null, null, null, null, null);
            throw new AssertionError("constructor must not run");
        }

        @Override public TextFilter createTextFilterForPlayer(ServerPlayer player) { return TextFilter.DUMMY; }
        @Override public @Nullable GameType getForcedGameType() { return null; }
        @Override public GameType getDefaultGameType() { return GameType.SURVIVAL; }
        @Override public net.minecraft.server.dedicated.DedicatedPlayerList getPlayerList() { return playerList; }
        /** {@code DedicatedServer.getScaledTrackingDistance}: the property Kestrel reads too. */
        @Override public int getScaledTrackingDistance(int range) { return rangePercentage * range / 100; }
        @Override public boolean isSameThread() { return true; }
        @Override public boolean allowFlight() { return false; }
        @Override public boolean isPaused() { return false; }
        @Override public int playerIdleTimeout() { return 0; }
        @Override public boolean enforceSecureProfile() { return false; }
        @Override public int getCommandSpamThresholdSeconds() { return 10; }
        @Override public int getChatSpamThresholdSeconds() { return 10; }
        @Override public RegistryAccess.Frozen registryAccess() { return (RegistryAccess.Frozen) access; }
        @Override public ServerScoreboard getScoreboard() { return scoreboard; }
        @Override public boolean isSingleplayerOwner(net.minecraft.server.players.NameAndId nameAndId) { return false; }
        @Override public net.minecraft.commands.Commands getCommands() { return commands; }
        @Override public int getMaxPlayers() { return maxPlayers; }
        @Override public net.minecraft.server.permissions.LevelBasedPermissionSet operatorUserPermissions() {
            return net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(
                    net.minecraft.server.permissions.PermissionLevel.byId(opLevel));
        }
    }

    /** The player list's two per-player lookups the ServerPlayer constructor makes. */
    static final class OraclePlayerList extends net.minecraft.server.dedicated.DedicatedPlayerList {
        MinecraftServer server;
        PlayerTrackingOracle oracle;

        private OraclePlayerList() {
            super(null, null, null);
            throw new AssertionError("constructor must not run");
        }

        @Override
        public ServerStatsCounter getPlayerStats(Player player) {
            return new ServerStatsCounter(server, Path.of("/nonexistent/kestrel-oracle-stats.json"));
        }

        @Override
        public PlayerAdvancements getPlayerAdvancements(ServerPlayer player) {
            try {
                PlayerAdvancements advancements = (PlayerAdvancements) UNSAFE.allocateInstance(OracleAdvancements.class);
                // No criteria are listened for: every trigger finds an empty map.
                put(advancements, PlayerAdvancements.class, "activeTriggers", new java.util.IdentityHashMap<>());
                return advancements;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** No advancements: nothing to flush or clear. */
    static final class OracleAdvancements extends PlayerAdvancements {
        private OracleAdvancements() {
            super(null, null, null, null, null);
            throw new AssertionError("constructor must not run");
        }

        @Override public void flushDirty(ServerPlayer player, boolean showAdvancements) { }
        @Override public void clearTriggers() { }
        @Override public void setPlayer(ServerPlayer player) { }
    }

    /** {@code ServerChunkCache.move}: into the tracker's {@code ChunkMap.move}. */
    static final class OracleChunkCache extends ServerChunkCache {
        Tracker tracker;

        private OracleChunkCache() {
            super(null, null, null, null, null, null, 0, 0, false, null, null);
            throw new AssertionError("constructor must not run");
        }

        static OracleChunkCache create(Tracker tracker) throws InstantiationException {
            OracleChunkCache cache = (OracleChunkCache) UNSAFE.allocateInstance(OracleChunkCache.class);
            cache.tracker = tracker;
            return cache;
        }

        /** {@code ServerChunkCache.move}; waypoints are out of this slice's scope. */
        @Override
        public void move(ServerPlayer player) {
            if (!player.isRemoved()) {
                tracker.move(player);
            }
        }

        /** Every chunk of the gate's world is loaded (the fall-damage check asks). */
        @Override
        public boolean hasChunk(int x, int z) {
            return true;
        }

        /** {@code ChunkMap.sendToTrackingPlayers}: the entity's tracked entry (a swing). */
        @Override
        public void sendToTrackingPlayers(Entity entity, Packet<? super ClientGamePacketListener> packet) {
            tracker.sendToTracking(entity, packet, false);
        }

        @Override
        public void sendToTrackingPlayersAndSelf(Entity entity, Packet<? super ClientGamePacketListener> packet) {
            tracker.sendToTracking(entity, packet, true);
        }
    }

    /**
     * The gate's answer to {@code ChunkMap.isChunkTracked}'s "not pending":
     * a chunk is pending until Kestrel's {@code ChunkSender} has sent it
     * to the same profile (the snapshot the gate takes before each tick).
     */
    static final class SentChunks extends PlayerChunkSender {
        java.util.function.LongPredicate sent = pos -> false;

        SentChunks() {
            super(false);
        }

        @Override
        public boolean isPending(long pos) {
            return !sent.test(pos);
        }
    }

    /** A connection that records what the listener sends. */
    static final class CaptureConnection extends Connection {
        final List<Packet<?>> sent = new ArrayList<>();

        CaptureConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
            sent.add(packet);
        }
    }

    static final net.minecraft.server.waypoints.ServerWaypointManager NO_WAYPOINTS =
            new net.minecraft.server.waypoints.ServerWaypointManager() {
                @Override public void trackWaypoint(net.minecraft.world.waypoints.WaypointTransmitter w) { }
                @Override public void updateWaypoint(net.minecraft.world.waypoints.WaypointTransmitter w) { }
                @Override public void untrackWaypoint(net.minecraft.world.waypoints.WaypointTransmitter w) { }
                @Override public void addPlayer(ServerPlayer player) { }
                @Override public void updatePlayer(ServerPlayer player) { }
                @Override public void removePlayer(ServerPlayer player) { }
            };

    /** A ServerLevel with no constructor run, over the gate's block world. */
    static final class OracleLevel extends ServerLevel {
        int nextId;
        OracleServer server;
        BlockGetter blocks;
        WorldBorder border;
        DamageSources damage;
        GameRules rules;
        OracleChunkCache chunkCache;
        RandomSource random;
        net.minecraft.world.level.chunk.ChunkAccess fluidScanChunk;
        final List<ServerPlayer> players = new ArrayList<>();

        private OracleLevel() {
            super(null, Runnable::run, null, null, Level.OVERWORLD, null, false, 0L, List.of(), false);
            throw new AssertionError("constructor must not run");
        }

        static OracleLevel create(OracleServer server, BlockGetter blocks) throws ReflectiveOperationException {
            OracleLevel level = (OracleLevel) UNSAFE.allocateInstance(OracleLevel.class);
            List<ServerPlayer> players = new ArrayList<>();
            put(level, OracleLevel.class, "players", players);
            // ServerLevel's own methods (sendParticles, for a landing) read its private field.
            put(level, ServerLevel.class, "players", players);
            level.server = server;
            level.blocks = blocks;
            level.border = new WorldBorder();
            try {
                level.damage = new DamageSources(server.access);
            } catch (IllegalStateException missingDamageRegistry) {
                level.damage = (DamageSources) UNSAFE.allocateInstance(DamageSources.class);
            }
            level.rules = new GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
            level.random = RandomSource.create(0L);
            return level;
        }

        long gameTime;

        @Override public int getNextEntityId() { return nextId; }
        /** Kestrel's clock, which the gate copies in each tick. */
        @Override public long getGameTime() { return gameTime; }
        @Override public MinecraftServer getServer() { return server; }
        @Override public List<ServerPlayer> players() { return players; }
        @Override public ServerChunkCache getChunkSource() { return chunkCache; }
        @Override public GameRules getGameRules() { return rules; }
        @Override public net.minecraft.world.TickRateManager tickRateManager() { return server.tickRateManager(); }
        @Override public ServerScoreboard getScoreboard() { return server.scoreboard; }
        @Override public RegistryAccess registryAccess() { return server.access; }
        @Override public DamageSources damageSources() { return damage; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public RandomSource getRandom() { return random; }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return blocks.getFluidState(pos); }
        @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) { return blocks; }
        @Override public int getMinY() { return blocks.getMinY(); }
        @Override public int getHeight() { return blocks.getHeight(); }
        @Override public boolean isRainingAt(BlockPos pos) { return false; }
        /** The locator bar (waypoints) is out of this slice's scope: a manager that tracks nothing. */
        @Override public net.minecraft.server.waypoints.ServerWaypointManager getWaypointManager() { return NO_WAYPOINTS; }
        /** Kestrel's join says normal ({@code Outbound.difficulty()}). */
        @Override public net.minecraft.world.Difficulty getDifficulty() { return net.minecraft.world.Difficulty.NORMAL; }

        @Override
        public net.minecraft.world.level.chunk.@Nullable ChunkAccess getChunk(int chunkX, int chunkZ,
                net.minecraft.world.level.chunk.status.ChunkStatus status, boolean loadOrGenerate) {
            if (fluidScanChunk == null) {
                fluidScanChunk = dev.kestrel.entity.KestrelPlayerEntity.fluidScanChunk(this);
            }
            return fluidScanChunk;
        }

        /**
         * No entities besides the players. Vanilla's sections hold the players too, so a box
         * query finds each one whose box meets it ({@code EntitySection.getEntities}), and
         * {@code LivingEntity.pushEntities} shoves two overlapping players apart.
         */
        @Override
        public List<Entity> getEntities(@Nullable Entity except, AABB bb, Predicate<? super Entity> selector) {
            List<Entity> found = new ArrayList<>();
            for (ServerPlayer player : players) {
                if (player != except && !player.isRemoved() && player.getBoundingBox().intersects(bb) && selector.test(player)) {
                    found.add(player);
                }
            }
            return found;
        }

        @Override
        public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB bb, Predicate<? super T> selector) {
            List<T> found = new ArrayList<>();
            for (ServerPlayer player : players) {
                T match = type.tryCast(player);
                if (match != null && !player.isRemoved() && player.getBoundingBox().intersects(bb) && selector.test(match)) {
                    found.add(match);
                }
            }
            return found;
        }

        @Override
        public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) {
        }

        /** Sounds (a player's steps) reach clients through G67, not this slice: both funnels do nothing. */
        @Override
        public void playSound(@Nullable Entity except, double x, double y, double z,
                net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        }

        @Override
        public void playSound(@Nullable Entity except, double x, double y, double z,
                Holder<net.minecraft.sounds.SoundEvent> sound, net.minecraft.sounds.SoundSource source, float volume,
                float pitch) {
        }
    }

    // =====================================================================

    static void put(Object target, Class<?> owner, String name, Object value) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(f), value);
    }

    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
