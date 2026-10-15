package dev.kestrel.net;

import dev.kestrel.entity.KestrelPlayerEntity;
import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.KestrelPos;
import dev.kestrel.loop.TickLoop;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.PngInfo;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.HashedStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stage 3 server: raw-TCP Netty-free listener driving handshake, login
 * (offline), configuration, and play for the overworld.
 *
 * <p>Phase flow per connection, grounded in the 26.2 decompile: handshake
 * intent 2 ({@code ServerHandshakePacketListenerImpl.java:27-31},
 * protocol check {@code :55-69}); login hello then compression (when
 * threshold >= 0, {@code ServerLoginPacketListenerImpl.java:150-156})
 * then finished then acknowledged
 * ({@code :167-170}, {@code :245-253}); configuration brand, features,
 * select-known-packs, registry data, tags, finish
 * ({@code ServerConfigurationPacketListenerImpl.java:86-104},
 * {@code SynchronizeRegistriesTask.java:31-44},
 * {@code JoinWorldTask.java:12-14}); play join burst
 * ({@code PlayerList.java:146-221}) then chunk stream
 * ({@code PlayerChunkSender.java:50-78}) then per-tick keepalive
 * ({@code ServerGamePacketListenerImpl.java:303-304}).
 *
 * <p>Threading: one acceptor thread plus one I/O thread per
 * connection. I/O threads NEVER touch the tick loop: inbound play
 * packets become {@link PlayerEvent}s on a queue the tick thread
 * drains each tick; outbound play packets are queued by the tick
 * thread and flushed by the I/O threads. Login/configuration run
 * fully on the I/O thread (no world state involved).
 *
 * <p>Known limits (Stage 3 scope): offline mode only (no key exchange,
 * skipped like vanilla's {@code !usesAuthentication()} branch);
 * single hardcoded {@code minecraft:core} pack; empty registry entries
 * and empty tags; no-light chunks; no entities (only the player);
 * client-requested view distance clamped to 2..16; movement accepted and tracked but not
 * collision-checked (that is optimization #1's future body, not a
 * Stage 3 proof point).
 */
public final class NetServer implements AutoCloseable {

    /** Server brand sent in configuration. */
    public static final String BRAND = "Kestrel";

    /** Core pack triple Kestrel requests ({@code KnownPack.java:9-11}). */
    public static final String[] CORE_PACK = {"minecraft", "core", "26.2"};

    /** Ticks between keepalives (15s at 20 TPS, {@code ServerCommonPacketListenerImpl.java:35}). */
    public static final int KEEPALIVE_TICKS = 300;

    /** Ticks before a missed keepalive disconnects (second 15s window, {@code :118-133}). */
    public static final int KEEPALIVE_TIMEOUT_TICKS = 600;

    /**
     * Ticks between periodic SetTime broadcasts. Vanilla's cadence
     * ({@code MinecraftServer.java:1113}, {@code tickCount % 20}): the
     * clock carries rate 1, so the client advances day time itself
     * between syncs and a per-tick packet would add 20x the bytes for no
     * visible change. Clock jumps (wrap, set) broadcast immediately.
     */
    public static final int TIME_SYNC_TICKS = 20;

    /**
     * Anti-cheat movement validation constants (Roadmap S8).
     * Server-side rate-limiting on the tick thread; full terrain collision arrives later.
     */
    // (S8 block kept; S13 block appended below)

    /**
     * Max horizontal speed per tick (1.20 m/tick).
     * Vanilla walking is ~4.32 m/s (~0.216 m/tick), sprinting is ~5.61 m/s (~0.281 m/tick),
     * and sprint-jumping reaches ~0.55 m/tick peak velocity. The old 0.60 cap
     * rejected legal sprint-jumps live (283 violations on normal play,
     * 2026-09-14): per-packet tick quantization plus network jitter pushes
     * real peaks past it. 1.20 keeps a 2x margin over the fastest legal
     * ground movement while still catching flight/speed hacks (those exceed
     * several m/tick sustained). Tighten again only with measured cheat data.
     */
    public static final double MAX_HORIZONTAL_SPEED = 1.20;

    /**
     * Max vertical rise per tick (~0.50 m/tick).
     * Vanilla jump initial vertical velocity is 0.42 m/tick (0.52 with Jump Boost I).
     * Any sustained upward movement above 0.50 m/tick represents fly hacks.
     * Water has its own cap ({@link #MAX_SWIM_RISE}): swimming/dolphin
     * diving rises much faster than jumping.
     */
    public static final double MAX_VERTICAL_RISE = 0.50;

    /**
     * Max vertical rise per tick while in water (~7.0 m/tick).
     * Measured live 2026-09-14: ocean swimming/diving at y~50-58 rises
     * +2..+6 m per accepted tick (buoyancy + swim-up + packet
     * quantization); the 0.50 jump cap rubber-banded every dive. 7.0
     * keeps margin over the fastest observed legal swim while still
     * catching flight (tens of m/tick sustained). Applies when either
     * endpoint block is water; the gravity-sanity gate is skipped in
     * water too (buoyancy, not jumping).
     */
    public static final double MAX_SWIM_RISE = 7.0;

    /**
     * Max jump apex height.
     * Vanilla's unassisted jump apex is 1.2522033 blocks above the ground:
     * a jump power of 0.42F, then 0.08 gravity and 0.98F drag a tick
     * ({@code LivingEntity.jumpFromGround}, {@code travelInAir}), summed
     * until the motion turns. The old 1.25 rejected the top of every real
     * jump (found by {@code PlayerTrackingParityCheck}, G15).
     * Rising higher than this while airborne without ground contact indicates fly/air-jump hacks.
     */
    public static final double MAX_JUMP_HEIGHT = 1.2523;

    /**
     * Max teleport distance per single packet (64.0 blocks).
     * Vanilla disconnects when a client teleports beyond ~64 blocks in a single packet.
     * Per Kestrel leniency precedent (same as invalid-face handling),
     * we clamp/reject the move instead of disconnecting the client.
     */
    public static final double MAX_TELEPORT_DISTANCE = 64.0;

    /**
     * Consecutive violation threshold before forcing a position correction (20 ticks = 1.0 second).
     * On N consecutive violations, server re-sends authoritative position via Outbound.position
     * with a fresh teleport ID, rubber-banding the client. Never disconnects per leniency precedent.
     */
    public static final int MAX_CONSECUTIVE_VIOLATIONS = 20;

    // ---- S13 inbound bounds: one weird client must not lag or crash the rest ----

    /**
     * Largest inbound frame buffered, in bytes after the length prefix.
     * Vanilla's {@code Varint21FrameDecoder} caps the prefix at 3 bytes
     * (2^21 - 1). Threat: a huge declared length pinning a multi-MB
     * per-session buffer, or never completing so the session stalls.
     * Larger play frames are skipped byte-for-byte (stream stays in
     * sync) and counted, not buffered.
     */
    static final int MAX_INBOUND_FRAME = (1 << 21) - 1;

    /**
     * Largest declared uncompressed size of an inbound packet. Vanilla
     * allows 8 MiB ({@code CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH});
     * Kestrel caps serverbound at vanilla's compressed-frame cap
     * ({@code MAXIMUM_COMPRESSED_LENGTH}, 2 MiB) since no 26.2 serverbound
     * packet approaches it. Threat: zlib ratio bombs (a few KB of
     * deflated zeros declaring MiBs) burning CPU and heap per packet.
     * Inflation also stops at the declared length ({@code Wire.zlibInflate}).
     */
    static final int MAX_INBOUND_UNCOMPRESSED = 1 << 21;

    /**
     * Play packets one session may queue between two tick drains; the
     * excess is dropped before decompression and parse. Threat: a client
     * flooding packets faster than 20 TPS grows the shared event queue
     * without bound (heap) and inflates the tick thread's drain.
     */
    static final int MAX_PENDING_PACKETS_PER_SESSION = 1024;

    /**
     * Events applied per session per tick; the excess is dropped and
     * counted, the session stays connected. Threat: one session's burst
     * (placements, digs, chat) starving the tick for everyone. 128 covers
     * a laggy client's TCP backlog of ~6 s of 20 Hz movement.
     * KeepAlive and Disconnected are exempt (O(1), and dropping them
     * would time out or leak the session). AcceptTeleport is exempt too:
     * it is one packet per teleport (not a flood vector), and dropping it
     * strands the player unplaced, which drops ALL their later movement.
     */
    static final int MAX_EVENTS_PER_SESSION_PER_TICK = 128;

    /**
     * Known packs accepted in configuration: vanilla's
     * {@code ServerboundSelectKnownPacks} codec is {@code list(64)}.
     * Threat: a varint count of 2^31 - 1 pre-sizing a giant list.
     */
    static final int MAX_KNOWN_PACKS = 64;

    /**
     * Handshake plus login deadline: vanilla's
     * {@code ServerLoginPacketListenerImpl.MAX_TICKS_BEFORE_LOGIN} (600
     * ticks, 30 s) disconnects slow logins. Threat: idle sockets holding
     * an I/O thread forever before any keepalive exists.
     */
    static final long LOGIN_TIMEOUT_MS = 30_000;

    /** Ticks between per-session violation log lines (only when counters moved). */
    static final int VIOLATION_REPORT_TICKS = 100;

    /**
     * Per-player position telemetry ({@code track} lines), off by default.
     * Enable with {@code -Dkestrel.tracklog=true} when diagnosing movement or
     * stream-center bugs. It was unconditional during the 2026-09-14
     * stuck-center hunt and emitted one line per player PER TICK (20/s),
     * which buried every other log line under megabytes of noise.
     */
    static final boolean TRACK_LOG = Boolean.getBoolean("kestrel.tracklog");

    /** Initial per-session read buffer; grows on demand up to one max frame. */
    static final int INITIAL_READ_BUFFER = 16 * 1024;

    /** Inbound play events for the tick thread. */
    public sealed interface PlayerEvent permits PlayerEvent.Move, PlayerEvent.AcceptTeleport,
            PlayerEvent.Loaded, PlayerEvent.KeepAlive, PlayerEvent.BatchReceived,
            PlayerEvent.Dig, PlayerEvent.Place, PlayerEvent.Chat, PlayerEvent.SetCarried,
            PlayerEvent.SwapOffhand, PlayerEvent.DropItem, PlayerEvent.Disconnected, PlayerEvent.BodyPacket,
            PlayerEvent.ContainerClick, PlayerEvent.ContainerClose, PlayerEvent.Vanilla {
        /** Profile the event belongs to (per-session drain cap key). */
        UUID player();

        record Move(UUID player, Double x, Double y, Double z,
                    Float yaw, Float pitch, Boolean onGround, boolean horizontalCollision) implements PlayerEvent {
        }

        record AcceptTeleport(UUID player, int id) implements PlayerEvent {
        }

        record Dig(UUID player, long posLong, int action, int sequence) implements PlayerEvent {
        }

        record SetCarried(UUID player, int slot) implements PlayerEvent {
        }

        record SwapOffhand(UUID player) implements PlayerEvent {
        }

        /** Dropping items via Q (all=false) or Ctrl+Q (all=true). */
        record DropItem(UUID player, boolean all) implements PlayerEvent {
        }

        /**
         * Use-item-on against a block: posLong is the clicked block, face its 3D data value, the
         * cursor is relative to the block ({@code FriendlyByteBuf.readBlockHitResult}).
         */
        record Place(UUID player, long posLong, int face, int hand, int sequence,
                float cursorX, float cursorY, float cursorZ, boolean inside, boolean worldBorderHit)
                implements PlayerEvent {
        }

        /** Chat message sent by a player in play phase. */
        record Chat(UUID player, String message) implements PlayerEvent {
        }

        record Loaded(UUID player) implements PlayerEvent {
        }

        record KeepAlive(UUID player, long id) implements PlayerEvent {
        }

        record BatchReceived(UUID player, float desiredChunksPerTick) implements PlayerEvent {
        }

        /** The session's own record: a later login under the profile has its own. */
        record Disconnected(UUID player, KestrelPlayer record) implements PlayerEvent {
        }

        /** A packet for the player's body (G15): input, command, swing, client information. */
        record BodyPacket(UUID player, int id, byte[] payload) implements PlayerEvent {
        }

        record ContainerClick(UUID player, int containerId, int stateId, short slotNum,
                              byte buttonNum, ContainerInput containerInput,
                              Int2ObjectMap<HashedStack> changedSlots,
                              HashedStack carriedItem) implements PlayerEvent {
        }

        record ContainerClose(UUID player, int containerId) implements PlayerEvent {
        }

        /**
         * A serverbound packet decoded by vanilla's own codec on the network thread, for the
         * stand-in's real listener to handle on the tick thread (chat commands, command
         * suggestions, F3+F4).
         */
        record Vanilla(UUID player, net.minecraft.network.protocol.Packet<
                net.minecraft.network.protocol.game.ServerGamePacketListener> packet) implements PlayerEvent {
        }
    }

    private final TickLoop loop;
    private final int port;
    private final int compressionThreshold;
    private final long seed;
    private final boolean onlineMode;
    private final java.security.KeyPair keyPair;
    /** Pre-encoded datapack registry entries (null = send empty lists). */
    private volatile List<Datapack.RegistryEntries> datapack;
    /**
     * Diagnostic-only reference to the sender, wired once at boot via
     * {@link #setChunkSender} (constructed before {@code NetServer} in
     * {@code KestrelServer.main}, so this is set before the first tick).
     * Used exclusively to distinguish, in the void-fall diagnostic below,
     * a chunk that never finished generating from one that finished but
     * whose latest write was never re-sent to THIS player (a stale client
     * copy) - the two have identical symptoms in-game but different root
     * causes and different fixes.
     */
    private volatile ChunkSender chunkSenderRef;

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread acceptor;

    /** Live sessions by profile uuid (I/O threads add/remove; tick thread reads). */
    public final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Live player records by profile uuid (tick thread owns; I/O threads never touch). */
    public final Map<UUID, KestrelPlayer> players = new ConcurrentHashMap<>();
    /** Pending player admissions from login I/O threads to tick thread. */
    private final java.util.Queue<KestrelPlayer> pendingPlayers = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /**
     * {@code PlayerList.players}: the same records in join order, the order
     * {@code PlayerList.broadcast} walks. Added on the login I/O thread,
     * removed and read on the tick thread.
     */
    private final List<KestrelPlayer> playerList = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Inbound play events (I/O threads offer; tick thread drains). */
    public final BlockingQueue<PlayerEvent> events = new LinkedBlockingQueue<>();
    /** Overworld day time and weather (tick thread owns). */
    public final WorldTime time;
    /** Server tick counter for periodic syncs (vanilla MinecraftServer.tickCount). */
    private long tickCount;

    long tickCount() {
        return tickCount;
    }

    /** Count of active players on the server, matching {@code PlayerList.getPlayerCount()}. */
    public int playerCount() {
        return players.size();
    }

    public int getPlayerCount() {
        return players.size();
    }
    /**
     * G4 saved player state by profile: loaded at boot, refreshed from
     * live records on disconnect and on save, consulted by the join
     * burst. Tick thread plus the shutdown-hook save.
     */
    private final Map<UUID, StateStore.PlayerState> savedPlayers = new ConcurrentHashMap<>();

    public NetServer(TickLoop loop, int port, int compressionThreshold, long seed) {
        this(loop, port, compressionThreshold, seed, false);
    }

    /**
     * Stage 3b: online mode runs the full Mojang login (RSA challenge,
     * AES link encryption, session verify). Offline stays default: same
     * proof path as Stage 3.
     */
    public NetServer(TickLoop loop, int port, int compressionThreshold, long seed,
            boolean onlineMode) {
        this.loop = loop;
        this.port = port;
        this.compressionThreshold = compressionThreshold;
        this.seed = seed;
        this.onlineMode = onlineMode;
        this.time = new WorldTime(seed);
        loop.setEntityTracker(entityTracker);
        // S14 slice 3: the level's entities tick in the loop's entity phase.
        // A connected player stands in for vanilla's dimension-active ticket.
        this.entityLevel = new LoopEntityLevel(loop, entityTracker);
        this.entityWorld = new dev.kestrel.entity.EntityWorld(entityLevel, entityTracker);
        entityLevel.entityWorld = entityWorld;
        this.bodies = new PlayerBodies(entityTracker, entityLevel, this::connectionFault, this::findSpawn, seed);
        loop.setEntityWorld(entityWorld);
        // The stand-ins' server answers from server.properties and the operator list, read live.
        dev.kestrel.entity.StandInServer.setSettings(entityLevel, new dev.kestrel.entity.StandInServer.Settings() {
            @Override public int maxPlayers() { return maxPlayers; }
            @Override public int opPermissionLevel() {
                return properties != null ? properties.opPermissionLevel() : 4;
            }
            @Override public net.minecraft.world.level.GameType gameMode() {
                return properties != null ? properties.gamemode() : net.minecraft.world.level.GameType.SURVIVAL;
            }
            @Override public boolean forceGameMode() { return properties != null && properties.forceGamemode(); }
        });
        dev.kestrel.entity.StandInServer.setOps(entityLevel, ops);
        loop.setActiveTickets(() -> !players.isEmpty());
        // G66: level events (block breaks, anvil sounds, the torch burnout) reach clients.
        loop.setLevelEventSink(new dev.kestrel.loop.TickLoop.LevelEventSink() {
            @Override
            public void levelEvent(int type, BlockPos pos, int data) {
                broadcastLevelEvent(null, type, pos, data);
            }

            @Override
            public void levelEvent(java.util.UUID exceptPlayer, int type, BlockPos pos, int data) {
                broadcastLevelEvent(exceptPlayer, type, pos, data);
            }
        });
        if (DEV_DROP_ITEMS_ON_JOIN) {
            System.out.println("[kestrel] DEV: -D" + DEV_DROP_ITEMS_PROPERTY
                    + "=true is set: every joining player gets test item drops. Not for real servers.");
        }
        if (loop.level().dimension() != KestrelLevel.Dimension.OVERWORLD) {
            throw new IllegalArgumentException("Stage 3 serves the overworld only");
        }
        if (onlineMode) {
            try {
                this.keyPair = SessionCipher.generateKeyPair();
            } catch (IOException e) {
                throw new IllegalStateException("online mode needs an RSA key pair", e);
            }
        } else {
            this.keyPair = null;
        }
    }

    /** Set the datapack entries served during configuration (call before start). */
    public void setDatapack(List<Datapack.RegistryEntries> packs) {
        this.datapack = packs;
        bodies.setDatapack(packs);
        // Vanilla binds its block tags at startup. The live generator's
        // vanilla resource load normally has already (ServedTags
        // .blockTagsBound); without it, bind the served ones so tool rules
        // (block breaking) and every other tag test see vanilla's memberships.
        if (ServedTags.blockTagsBound()) {
            System.out.println("[kestrel] block tags already bound by the vanilla resource load");
        } else {
            System.out.println("[kestrel] bound " + ServedTags.bindBlockTags(packs) + " served block tags");
        }
    }

    public void setChunkSender(ChunkSender sender) {
        this.chunkSenderRef = sender;
    }

    private volatile String motd = "A Minecraft Server";
    private volatile int maxPlayers = 20;
    private volatile Path iconPath = Path.of("server-icon.png");

    public String motd() {
        return motd;
    }

    public void setMotd(String motd) {
        this.motd = motd;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public Path iconPath() {
        return iconPath;
    }

    public void setIconPath(Path iconPath) {
        this.iconPath = iconPath;
    }

    /**
     * Load server favicon from iconPath if present and a valid 64x64 PNG.
     * Matches vanilla MinecraftServer.loadStatusIcon().
     */
    public ServerStatus.Favicon loadFavicon() {
        Path path = iconPath;
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            byte[] contents = Files.readAllBytes(path);
            PngInfo pngInfo = PngInfo.fromBytes(contents);
            if (pngInfo.width() == 64 && pngInfo.height() == 64) {
                return new ServerStatus.Favicon(contents);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Build the 26.2 server status JSON string matching vanilla dedicated server.
     */
    public String buildStatusJson() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<NameAndId> sample = new ArrayList<>();
        for (KestrelPlayer p : players.values()) {
            sample.add(new NameAndId(p.profile, p.name));
            if (sample.size() >= 12) {
                break;
            }
        }
        int onlineCount = sessions.size();
        ServerStatus.Players playerStatus = new ServerStatus.Players(
                maxPlayers,
                onlineCount,
                sample
        );
        ServerStatus.Version version = new ServerStatus.Version(
                SharedConstants.getCurrentVersion().name(),
                SharedConstants.getCurrentVersion().protocolVersion()
        );
        ServerStatus status = new ServerStatus(
                Component.nullToEmpty(motd),
                Optional.of(playerStatus),
                Optional.of(version),
                Optional.ofNullable(loadFavicon()),
                false
        );
        var ops = RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE);
        JsonElement elem = ServerStatus.CODEC.encodeStart(ops, status).getOrThrow();
        return new GsonBuilder().disableHtmlEscaping().create().toJson(elem);
    }

    private ServerProperties properties;

    public void setProperties(ServerProperties properties) {
        this.properties = properties;
        entityTracker.setRangePercentage(properties.entityBroadcastRangePercentage());
    }

    // ---- entity tracking (S14 slice 2) ----

    /**
     * The entity tracker, ticked by {@link TickLoop} in vanilla's
     * chunkSource slot. Joined players are its observers and, since G15,
     * tracked entities themselves (their bodies, {@link PlayerBodies}).
     */
    private final dev.kestrel.entity.EntityTracker entityTracker = new dev.kestrel.entity.EntityTracker(100);
    /** G15: the tab list rows and bodies of joined players (tick thread only). */
    private final PlayerBodies bodies;

    public dev.kestrel.entity.EntityTracker entityTracker() {
        return entityTracker;
    }

    /** The joined players' bodies, for the G15 gate. */
    PlayerBodies bodies() {
        return bodies;
    }

    // ---- entities (S14 slice 3) ----

    /** The level's entities: the spawn entry point and the tick list. */
    private final dev.kestrel.entity.EntityWorld entityWorld;

    public dev.kestrel.entity.EntityWorld entityWorld() {
        return entityWorld;
    }

    /**
     * {@code ServerLevel.levelEvent(null, type, pos, data)}: to every player in
     * join order within 64 blocks. This server serves one dimension, so every
     * player shares the loop's. Only sessions already in PLAY receive it; a
     * joining session has no world to play it in yet.
     */
    private void broadcastLevelEvent(java.util.@org.jspecify.annotations.Nullable UUID exceptPlayer, int type,
            BlockPos pos, int data) {
        List<LevelEventBroadcast.Receiver> receivers = new ArrayList<>(playerList.size());
        LevelEventBroadcast.Receiver except = null;
        Object dimension = loop.level().dimension();
        for (KestrelPlayer player : playerList) {
            Session session = sessions.get(player.profile);
            if (session == null || session.phase != Session.Phase.PLAY) {
                continue;
            }
            LevelEventBroadcast.Receiver receiver = new LevelEventBroadcast.Receiver() {
                @Override public Object dimension() { return dimension; }
                @Override public double x() { return player.x; }
                @Override public double y() { return player.y; }
                @Override public double z() { return player.z; }
                @Override public void send(Outbound.Packet packet) { session.send(packet); }
            };
            if (player.profile.equals(exceptPlayer)) {
                except = receiver;
            }
            receivers.add(receiver);
        }
        LevelEventBroadcast.broadcast(receivers, except, dimension, type, pos, data);
    }

    /** The loop's store as the world a ticking entity collides with. */
    private final LoopEntityLevel entityLevel;

    private static final class LoopEntityLevel implements dev.kestrel.entity.EntityLevel {
        private final TickLoop loop;
        private final dev.kestrel.entity.EntityTracker tracker;
        private final dev.kestrel.collision.ShapeBoxCache shapeCache = new dev.kestrel.collision.ShapeBoxCache();
        private net.minecraft.core.RegistryAccess registryAccess =
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                        net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        private net.minecraft.server.ReloadableServerRegistries.Holder loot =
                new net.minecraft.server.ReloadableServerRegistries.Holder(registryAccess);
        private java.util.function.@org.jspecify.annotations.Nullable Function<net.minecraft.resources.Identifier,
                net.minecraft.util.RandomSource> sequences;
        /** Loaded-chunk answer for {@code Level.isLoaded}; until installed, the store's generated chunks. */
        private java.util.function.@org.jspecify.annotations.Nullable LongPredicate loadedChunk;
        private dev.kestrel.entity.EntityWorld entityWorld;

        LoopEntityLevel(TickLoop loop, dev.kestrel.entity.EntityTracker tracker) {
            this.loop = loop;
            this.tracker = tracker;
        }

        @Override
        public net.minecraft.world.level.BlockGetter blocks() {
            return loop.blockGetter();
        }

        @Override
        public dev.kestrel.collision.ShapeBoxCache shapeCache() {
            return shapeCache;
        }

        @Override
        public int getMinY() {
            return loop.store().getMinY();
        }

        @Override public int getMaxY() { return loop.store().getMinY() + loop.store().getHeight() - 1; }
        @Override public net.minecraft.world.level.LevelReader levelReader() { return loop.entityLevelReader(); }
        @Override public boolean setBlock(net.minecraft.core.BlockPos pos,
                net.minecraft.world.level.block.state.BlockState state, int flags) {
            return loop.setBlock(pos, state, flags);
        }
        @Override public void levelEvent(int type, net.minecraft.core.BlockPos pos, int data) {
            loop.emitLevelEvent(type, pos, data);
        }
        @Override public void blockEvent(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block,
                int b0, int b1) {
            loop.redstone().blockEvent(pos, block, b0, b1);
        }
        @Override public void scheduleTick(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block,
                int delay, net.minecraft.world.ticks.TickPriority priority) {
            loop.redstone().scheduleTick(pos, block, delay, priority);
        }
        @Override public void updateNeighborsAt(net.minecraft.core.BlockPos pos,
                net.minecraft.world.level.block.Block sourceBlock, net.minecraft.core.Direction skipDirection) {
            if (skipDirection == null) {
                loop.redstone().updateNeighborsAt(pos, sourceBlock);
            } else {
                loop.redstone().updateNeighborsAtExceptFromFacing(pos, sourceBlock, skipDirection);
            }
        }
        @Override public void levelEvent(java.util.UUID exceptPlayer, int type, net.minecraft.core.BlockPos pos,
                int data) {
            loop.emitLevelEvent(exceptPlayer, type, pos, data);
        }
        @Override public net.minecraft.util.RandomSource random() { return loop.levelRandom(); }
        @Override public net.minecraft.world.level.gamerules.GameRules gameRules() { return loop.gameRules(); }
        @Override public net.minecraft.core.RegistryAccess registryAccess() { return registryAccess; }
        @Override public net.minecraft.server.ReloadableServerRegistries.Holder reloadableRegistries() { return loot; }
        @Override public net.minecraft.util.RandomSource randomSequence(net.minecraft.resources.Identifier key) {
            if (sequences == null) {
                throw new IllegalStateException("no random sequences installed (" + key + ")");
            }
            return sequences.apply(key);
        }
        @Override public boolean isLoadedChunk(long chunkKey) {
            java.util.function.LongPredicate loaded = loadedChunk;
            return loaded != null ? loaded.test(chunkKey) : loop.store().isChunkGenerated(chunkKey);
        }
        @Override public boolean addFreshVanillaEntity(net.minecraft.world.entity.Entity entity) {
            return loop.entityWorld().addFreshVanillaEntity(entity);
        }
        /** Sound delivery is roadmap G67: raised here, not yet sent. */
        @Override public void playSound(double x, double y, double z,
                net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound,
                net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        }
        @Override public void entityEvent(dev.kestrel.entity.KestrelEntity entity, byte event) {
            tracker.sendToTrackingPlayersAndSelf(entity, EntityOutbound.entityEvent(entity.getId(), event));
        }
        @Override public void spawnItem(double x, double y, double z, net.minecraft.world.item.ItemStack stack) {
            if (entityWorld == null) throw new IllegalStateException("entity world not attached");
            entityWorld.spawnItem(x, y, z, stack);
        }
        @Override public void landingBlockUpdate(dev.kestrel.entity.KestrelEntity entity,
                net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
            tracker.sendToTrackingPlayers(entity,
                    Outbound.blockUpdate(pos.asLong(), net.minecraft.world.level.block.Block.getId(state)));
        }
        @Override public dev.kestrel.entity.@org.jspecify.annotations.Nullable EntityWorld world() {
            return loop.entityWorld();
        }
    }

    /**
     * Developer affordance, off by default: with
     * {@code -Dkestrel.dev.dropItemsOnJoin=true} (exactly "true"; any other
     * value or none leaves it off) every player entering PLAY gets a few
     * item drops spawned beside them, so the entity phase and the item
     * packets can be seen in a real client. There is no command system to
     * do it otherwise. Nothing in the gate depends on it: the gate drives
     * {@link dev.kestrel.entity.EntityWorld#spawnItem} directly.
     */
    static final String DEV_DROP_ITEMS_PROPERTY = "kestrel.dev.dropItemsOnJoin";
    static final boolean DEV_DROP_ITEMS_ON_JOIN = Boolean.getBoolean(DEV_DROP_ITEMS_PROPERTY);

    private void devDropItems(KestrelPlayer player) {
        // The running server binds no item prototypes yet (no item stack
        // existed before this slice; G17 brings the real, datapack-backed
        // ones), and an ItemStack cannot be built without one. Bind any
        // unbound item to vanilla's own default prototype, as the item
        // checks do. An unpatched stack's wire bytes (count, id, empty
        // patch) do not depend on the prototype.
        for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            net.minecraft.core.Holder.Reference<net.minecraft.world.item.Item> holder = item.builtInRegistryHolder();
            if (!holder.areComponentsBound()) {
                holder.bindComponents(net.minecraft.core.component.DataComponents.COMMON_ITEM_COMPONENTS);
            }
        }
        net.minecraft.world.item.Item[] items = {
            net.minecraft.world.item.Items.DIAMOND,
            net.minecraft.world.item.Items.APPLE,
            net.minecraft.world.item.Items.OAK_LOG,
            net.minecraft.world.item.Items.IRON_INGOT,
            net.minecraft.world.item.Items.COBBLESTONE,
        };
        for (int i = 0; i < items.length; i++) {
            double angle = i * (Math.PI * 2.0 / items.length);
            entityWorld.spawnItem(player.x + 2.0 * Math.cos(angle), player.y + 1.5, player.z + 2.0 * Math.sin(angle),
                    new net.minecraft.world.item.ItemStack(items[i], i + 1));
        }
    }

    /**
     * A playing player as the tracker sees it. Explicit approximation of
     * {@code ChunkMap.isChunkTracked} while Kestrel has no chunk ticket
     * lifecycle (slice 5): the chunk must be inside the player's vanilla
     * {@code ChunkTrackingView} at the current view distance and must have
     * been sent to this profile at least once. Kestrel never unloads or
     * forgets a sent chunk, so "sent once" stands in for "not pending".
     * The view is centred where vanilla last recentred it
     * ({@link #updateChunkTracking}), not on the player's current chunk.
     */
    private final class PlayerObserver implements dev.kestrel.entity.TrackingObserver {
        private final KestrelPlayer player;
        private final Session session;
        private int viewX;
        private int viewZ;

        PlayerObserver(KestrelPlayer player, Session session) {
            this.player = player;
            this.session = session;
            updateChunkTracking();
        }

        @Override
        public void updateChunkTracking() {
            viewX = player.chunkX();
            viewZ = player.chunkZ();
        }

        @Override
        public int entityId() {
            return player.entityId;
        }

        @Override
        public double x() {
            return player.x;
        }

        @Override
        public double y() {
            return player.y;
        }

        @Override
        public double z() {
            return player.z;
        }

        @Override
        public int viewDistance() {
            // Already clamped to [2, server view-distance] at ClientInformation.
            return player.viewDistance;
        }

        @Override
        public boolean isChunkTracked(int chunkX, int chunkZ) {
            boolean inView = net.minecraft.server.level.ChunkTrackingView.of(
                            new net.minecraft.world.level.ChunkPos(viewX, viewZ), player.viewDistance)
                    .contains(chunkX, chunkZ);
            ChunkSender cs = chunkSenderRef;
            return inView && cs != null && cs.sentVersion(player.profile, ChunkSender.pack(chunkX, chunkZ)) >= 0;
        }

        @Override
        public void send(Outbound.Packet packet) {
            session.send(packet);
        }
    }

    /** G15: the player joins the level ({@code PlayerList.placeNewPlayer}): tab list rows, then its body. */
    private void startTracking(KestrelPlayer player, Session session) {
        bodies.join(player, new PlayerObserver(player, session), List.copyOf(session.loginProperties),
                PlayerBodies.Options.read(session.clientInformation));
        if (randomTicks != null) {
            // G30, ChunkMap.updatePlayerStatus(player, true): the simulation ticket.
            randomTicks.removePlayer(player.profile);
            randomTicks.addPlayer(player.profile, player.x, player.y, player.z, false);
        }
    }

    /** G15: this record leaves the level ({@code PlayerList.remove}); another record under the profile stays. */
    private void stopTracking(KestrelPlayer player) {
        bodies.leave(player);
        if (randomTicks != null) {
            // G30: the simulation ticket goes with it.
            randomTicks.removePlayer(player.profile);
        }
    }

    /** G30: the random ticks players' simulation tickets feed; null leaves them off. */
    private dev.kestrel.loop.RandomTicks randomTicks;

    public void setRandomTicks(dev.kestrel.loop.RandomTicks randomTicks) {
        this.randomTicks = randomTicks;
    }

    /**
     * Install the boot {@code Commands} (the data pack's, built by the same load as the live
     * generator's registries), restricted to the commands Kestrel can honour
     * ({@link HonouredCommands}). Without it every command is vanilla's unknown command.
     */
    public void setCommands(net.minecraft.commands.Commands commands) {
        HonouredCommands.restrict(commands);
        dev.kestrel.entity.StandInServer.setCommands(entityLevel, commands);
    }

    /**
     * Install the operator list loaded from {@code ops.json} under {@code root}
     * ({@link OpList#load}); call before {@link #start}.
     */
    public void loadOps(java.nio.file.Path root) {
        setOps(OpList.load(root));
    }

    void setOps(net.minecraft.server.players.ServerOpList list) {
        this.ops = java.util.Objects.requireNonNull(list);
        dev.kestrel.entity.StandInServer.setOps(entityLevel, list);
    }

    /** Install the same dynamic and reloadable registries the live generator loaded. */
    public void setLootRegistries(net.minecraft.core.RegistryAccess access,
            net.minecraft.server.ReloadableServerRegistries.Holder loot,
            java.util.function.Function<net.minecraft.resources.Identifier, net.minecraft.util.RandomSource> sequences,
            java.util.function.LongPredicate loadedChunk) {
        entityLevel.registryAccess = java.util.Objects.requireNonNull(access);
        entityLevel.loot = java.util.Objects.requireNonNull(loot);
        entityLevel.sequences = java.util.Objects.requireNonNull(sequences);
        entityLevel.loadedChunk = java.util.Objects.requireNonNull(loadedChunk);
    }

    public ServerProperties properties() {
        return properties;
    }

    /** Start listening (acceptor thread). */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running.set(true);
        acceptor = new Thread(this::acceptLoop, "kestrel-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int boundPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Thread t = new Thread(() -> runSession(socket), "kestrel-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) {
                    e.printStackTrace(System.out);
                }
            }
        }
    }

    // ---- per-session state (I/O thread) ----

    /** One connection: phase, compression, encryption, player, outbound queue. */
    public final class Session {
        enum Phase { HANDSHAKE, STATUS, LOGIN, CONFIGURATION, PLAY_JOINING, PLAY }
        /** Login sub-state for the online key exchange. */
        enum LoginState { HELLO, KEY, AUTHENTICATING, VERIFYING }

        final Socket socket;
        final InputStream in;
        final OutputStream out;
        volatile Phase phase = Phase.HANDSHAKE;
        volatile boolean hasRequestedStatus;
        volatile int threshold = -1;
        volatile boolean loginDone;
        volatile LoginState loginState = LoginState.HELLO;
        volatile String loginName;
        volatile KestrelPlayer player;
        volatile long lastKeepAliveTick;
        /** Highest block-change sequence to ack after this tick's publish, or -1. */
        int pendingAckSequence = -1;
        final BlockingQueue<Outbound.Packet> outbound = new LinkedBlockingQueue<>();
        /**
         * One chunk batch may be in flight at a time. The client acknowledges
         * a batch only after it has incorporated it; without this gate the
         * tick thread can enqueue terrain much faster than the socket/client
         * can consume it, leaving the player's current chunks buried behind
         * stale chunks from old cache centres.
         */
        volatile boolean chunkBatchPending;
        final AtomicBoolean closed = new AtomicBoolean();
        volatile boolean readOnly;
        /** Frame buffer (I/O thread): starts small, grows to one max frame. */
        byte[] readBuf = new byte[INITIAL_READ_BUFFER];
        int readLen;
        /** Bytes of an oversized frame still to discard off the wire (I/O thread). */
        long skipBytes;
        final long createdAt = System.currentTimeMillis();
        // S13 violation counters: I/O thread and tick thread increment,
        // the tick thread reports. Admission resets every drain.
        final AtomicInteger admittedPackets = new AtomicInteger();
        final AtomicLong droppedEvents = new AtomicLong();
        final AtomicLong oversizedFrames = new AtomicLong();
        final AtomicLong malformedPackets = new AtomicLong();
        final AtomicLong faultedEvents = new AtomicLong();
        /** Violation total at the last log line (tick thread). */
        long reportedViolations;
        // Online-mode login crypto (I/O thread only): challenge minted
        // with hello, ciphers armed after the key packet decrypts.
        byte[] loginChallenge;
        javax.crypto.Cipher decryptCipher;
        javax.crypto.Cipher encryptCipher;
        final List<Outbound.ProfileProperty> loginProperties = new ArrayList<>();
        /** Client view distance from ClientInformation (config phase). */
        volatile int pendingViewDistance = 10;
        /** The whole ClientInformation payload from configuration (G15 reads the skin layers and hand), or null. */
        volatile byte[] clientInformation;

        Session(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        void send(Outbound.Packet p) {
            if (!closed.get()) {
                outbound.offer(p);
            }
        }

        boolean startChunkBatch() {
            if (chunkBatchPending) {
                return false;
            }
            chunkBatchPending = true;
            return true;
        }

        void finishChunkBatch() {
            chunkBatchPending = false;
        }

        void flush() throws IOException {
            Outbound.Packet p;
            while ((p = outbound.poll()) != null) {
                byte[] framed = Wire.frame(p.id(), p.payload(), threshold);
                if (encryptCipher != null) {
                    framed = SessionCipher.update(encryptCipher, framed, 0, framed.length);
                }
                out.write(framed);
            }
            out.flush();
        }

        /** Arms link encryption: every byte from here on is AES/CFB8. */
        void enableEncryption(javax.crypto.SecretKey secret) throws IOException {
            SessionCipher.LinkCiphers link = SessionCipher.linkCiphers(secret);
            this.decryptCipher = link.decrypt();
            this.encryptCipher = link.encrypt();
        }

        /** Sum of every S13 violation counter. */
        long violations() {
            return droppedEvents.get() + oversizedFrames.get() + malformedPackets.get()
                    + faultedEvents.get();
        }

        /** Play phases absorb malformed input; earlier phases keep vanilla's disconnect. */
        boolean lenient() {
            return phase == Phase.PLAY || phase == Phase.PLAY_JOINING;
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void runSession(Socket socket) {
        Session session;
        try {
            session = new Session(socket);
        } catch (IOException e) {
            return;
        }
        try {
            socket.setSoTimeout(100);
            while (!session.closed.get() && running.get()) {
                if (session.readOnly) {
                    session.flush();
                    break;
                }
                if ((session.phase == Session.Phase.HANDSHAKE || session.phase == Session.Phase.STATUS
                        || session.phase == Session.Phase.LOGIN)
                        && System.currentTimeMillis() - session.createdAt > LOGIN_TIMEOUT_MS) {
                    if (session.phase == Session.Phase.LOGIN) {
                        session.send(Outbound.loginDisconnect("Took too long to log in"));
                        session.flush();
                    }
                    // phase=HANDSHAKE + loginName=null is almost always a
                    // benign anonymous status-ping connection (server list
                    // refresh) that never sent a login packet, not a stalled
                    // real player session; phase=LOGIN with a name is the
                    // real timeout case worth investigating.
                    System.out.println("[kestrel] login timed out: " + session.loginName
                            + " phase=" + session.phase);
                    break;
                }
                if (readFrame(session)) {
                    handleInbound(session);
                }
                session.flush();
            }
        } catch (Exception e) {
            // Read timeouts and disconnects are normal; log real errors.
            if (!(e instanceof java.net.SocketTimeoutException)
                    && !"client closed".equals(e.getMessage())) {
                System.out.println("[kestrel] session error: " + e);
            }
        } finally {
            session.close();
            if (session.player != null) {
                // Only this session: a later login under the profile may hold the entry now.
                sessions.remove(session.player.profile, session);
                events.offer(new PlayerEvent.Disconnected(session.player.profile, session.player));
            }
        }
    }

    /** Vanilla {@code ServerConnectionListener.tick:207-218}, for one play connection. */
    private void connectionFault(KestrelPlayer player, Exception failure) {
        Session session = sessions.get(player.profile);
        if (session == null || session.player != player) return;
        System.out.println("[kestrel] Failed to handle packet for " + player.name);
        failure.printStackTrace(System.out);
        session.send(Outbound.playDisconnect("Internal server error"));
        session.readOnly = true;
    }

    /** Read raw bytes into the session; false on timeout. */
    private boolean readFrame(Session session) throws IOException {
        // Under encryption every byte on the wire is cipher text, so
        // decrypt the fresh chunk before appending to the frame buffer
        // (vanilla puts decrypt BEFORE the splitter: Connection.java:529).
        byte[] scratch = new byte[8192];
        int avail;
        try {
            avail = session.in.read(scratch, 0, scratch.length);
        } catch (java.net.SocketTimeoutException e) {
            return false;
        }
        if (avail < 0) {
            throw new IOException("client closed");
        }
        byte[] chunk = scratch;
        int off = 0;
        if (session.decryptCipher != null) {
            chunk = SessionCipher.update(session.decryptCipher, scratch, 0, avail);
        }
        if (session.skipBytes > 0) {
            // Discard the rest of an oversized frame (after decrypt: the
            // CFB8 stream state must still see every byte).
            int skip = (int) Math.min(session.skipBytes, avail);
            session.skipBytes -= skip;
            off = skip;
            avail -= skip;
            if (avail == 0) {
                return false;
            }
        }
        int need = session.readLen + avail;
        if (need > session.readBuf.length) {
            // handleInbound drains or skips every frame above the cap, so
            // the buffer never holds more than one max frame plus a read.
            int cap = MAX_INBOUND_FRAME + 5 + scratch.length;
            if (need > cap) {
                throw new IOException("read buffer overflow");
            }
            int grown = Math.min(cap, Math.max(need, session.readBuf.length * 2));
            session.readBuf = java.util.Arrays.copyOf(session.readBuf, grown);
        }
        System.arraycopy(chunk, off, session.readBuf, session.readLen, avail);
        session.readLen += avail;
        return true;
    }

    /**
     * Frame length prefix at the buffer head, reading only the
     * {@code limit} buffered bytes: {length, prefixBytes}, or null when
     * the prefix is still incomplete. Throws when the prefix runs past
     * the 5-byte VarInt limit.
     */
    private static int[] peekFrameLength(byte[] buf, int limit) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (i >= limit) {
                return null;
            }
            int b = buf[i] & 0xFF;
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return new int[]{value, i + 1};
            }
        }
        throw new IOException("frame length varint too big");
    }

    /** Drop the first n buffered bytes. */
    private static void consume(Session session, int n) {
        int rest = session.readLen - n;
        System.arraycopy(session.readBuf, n, session.readBuf, 0, rest);
        session.readLen = rest;
    }

    /**
     * Drain complete frames from the session buffer. In play phases,
     * malformed input is absorbed and counted (Kestrel's leniency
     * precedent; vanilla disconnects via {@code Connection.exceptionCaught}):
     * an unreadable length prefix discards the buffered bytes (the frame
     * boundary is lost), an oversized frame is skipped, a bad envelope or
     * payload drops that one packet. Earlier phases still throw.
     */
    private void handleInbound(Session session) throws IOException {
        if (session.phase == Session.Phase.HANDSHAKE && session.readLen > 0) {
            if ((session.readBuf[0] & 0xFF) == 0xFE) {
                handleLegacyPing(session);
                return;
            }
        }
        while (session.readLen > 0) {
            int[] len;
            try {
                len = peekFrameLength(session.readBuf, session.readLen);
                if (len != null && len[0] < 0) {
                    throw new IOException("negative frame length " + len[0]);
                }
            } catch (IOException e) {
                if (!session.lenient()) {
                    throw e;
                }
                session.malformedPackets.incrementAndGet();
                session.readLen = 0;
                return;
            }
            if (len == null) {
                return; // need more bytes
            }
            int frameLen = len[0];
            int header = len[1];
            if (frameLen > MAX_INBOUND_FRAME) {
                if (!session.lenient()) {
                    throw new IOException("bad frame length " + frameLen);
                }
                session.oversizedFrames.incrementAndGet();
                long total = (long) header + frameLen;
                if (total <= session.readLen) {
                    consume(session, (int) total);
                    continue;
                }
                session.skipBytes = total - session.readLen;
                session.readLen = 0;
                return;
            }
            if (session.readLen < header + frameLen) {
                return; // need more bytes
            }
            byte[] frame = new byte[frameLen];
            System.arraycopy(session.readBuf, header, frame, 0, frameLen);
            consume(session, header + frameLen);
            if (!session.lenient()) {
                dispatch(session, Wire.decode(Wire.unframe(frame, session.threshold)));
                continue;
            }
            if (session.admittedPackets.incrementAndGet() > MAX_PENDING_PACKETS_PER_SESSION) {
                session.droppedEvents.incrementAndGet();
                continue; // dropped before inflate and parse
            }
            try {
                if (session.threshold >= 0 && frameLen > 0
                        && Wire.readVarInt(frame, 0)[0] > MAX_INBOUND_UNCOMPRESSED) {
                    session.oversizedFrames.incrementAndGet();
                    continue;
                }
                dispatch(session, Wire.decode(Wire.unframe(frame, session.threshold)));
            } catch (IOException | RuntimeException e) {
                session.malformedPackets.incrementAndGet();
            }
        }
    }

    // ---- dispatch per phase ----

    private void dispatch(Session session, Wire.Inbound inbound) throws IOException {
        switch (session.phase) {
            case HANDSHAKE -> handleHandshake(session, inbound);
            case STATUS -> handleStatus(session, inbound);
            case LOGIN -> handleLogin(session, inbound);
            case CONFIGURATION -> handleConfig(session, inbound);
            // PLAY_JOINING: the tick thread has not run the join burst yet,
            // but the client already speaks play (teleport acks, movement,
            // keepalive). Route to the play handler so the accept is queued
            // instead of dropped; per-player guards keep early packets safe.
            case PLAY_JOINING -> handlePlay(session, inbound);
            case PLAY -> handlePlay(session, inbound);
        }
    }

    private void handleHandshake(Session session, Wire.Inbound inbound) throws IOException {
        if (inbound.id() != PacketIds.HS_INTENTION) {
            throw new IOException("expected handshake, got " + inbound.id());
        }
        Wire.Reader r = inbound.reader();
        int protocol = r.varInt();
        String host = r.utf(255);
        int port = r.unsignedShort();
        int intent = r.varInt();
        if (intent == PacketIds.INTENT_LOGIN) {
            if (protocol != Wire.PROTOCOL_26_2) {
                session.send(Outbound.loginDisconnect(protocol < 754
                        ? "Outdated client! Please use 26.2"
                        : "Incompatible client! Please use 26.2"));
                session.flush();
                session.close();
                return;
            }
            session.phase = Session.Phase.LOGIN;
        } else if (intent == PacketIds.INTENT_STATUS) {
            session.phase = Session.Phase.STATUS;
        } else {
            throw new IOException("unknown handshake intent " + intent);
        }
    }

    private void handleStatus(Session session, Wire.Inbound inbound) throws IOException {
        if (inbound.id() == PacketIds.STATUS_REQUEST) {
            if (session.hasRequestedStatus) {
                // Vanilla ServerStatusPacketListenerImpl.java:36: duplicate request disconnects
                session.close();
                return;
            }
            session.hasRequestedStatus = true;
            String statusJson = buildStatusJson();
            session.send(Outbound.statusResponse(statusJson));
            session.flush();
        } else if (inbound.id() == PacketIds.STATUS_PING) {
            Wire.Reader r = inbound.reader();
            long time = r.l();
            session.send(Outbound.statusPong(time));
            session.flush();
            // Vanilla ServerStatusPacketListenerImpl.java:46: closes after pong
            session.close();
        } else {
            throw new IOException("unexpected status packet id " + inbound.id());
        }
    }

    /**
     * Legacy server list ping handling (Minecraft 1.6 and earlier).
     *
     * Grounded in vanilla net.minecraft.server.network.LegacyQueryHandler:
     * 1) First byte is 0xFE (254).
     * 2) If length is 1 (only 0xFE): pre-1.3 ping.
     *    Server responds with packet 0xFF and UTF-16BE string: motd§online§max.
     * 3) If next byte is 0x01:
     *    a) If length is 2 (0xFE 0x01): 1.4-1.5 ping.
     *       Server responds with packet 0xFF and UTF-16BE string:
     *       §1\u0000127\u0000<version>\u0000<motd>\u0000<online>\u0000<max>.
     *    b) If length > 2: 1.6 custom payload ping (0xFA MC|PingHost).
     *       Server validates payload and responds with the same §1 format.
     * 4) After responding (or on malformed input), connection is closed.
     */
    private void handleLegacyPing(Session session) throws IOException {
        if (session.readLen == 1 && session.in.available() > 0) {
            readFrame(session);
        }

        int onlineCount = sessions.size();
        String currentVersion = "26.2";

        if (session.readLen == 1) {
            String body = String.format(java.util.Locale.ROOT, "%s§%d§%d", motd, onlineCount, maxPlayers);
            sendLegacyDisconnect(session, body);
            return;
        }

        int secondByte = session.readBuf[1] & 0xFF;
        if (secondByte != 1) {
            session.close();
            return;
        }

        if (session.readLen == 2) {
            String body = String.format(java.util.Locale.ROOT, "§1\u0000%d\u0000%s\u0000%s\u0000%d\u0000%d",
                    127, currentVersion, motd, onlineCount, maxPlayers);
            sendLegacyDisconnect(session, body);
            return;
        }

        // 1.6.x custom payload ping: 0xFE 0x01 0xFA ...
        int packetId = session.readBuf[2] & 0xFF;
        if (packetId != 250) {
            session.close();
            return;
        }

        int pos = 3;
        if (session.readLen < pos + 2) {
            session.close();
            return;
        }
        int chanLen = ((session.readBuf[pos] & 0xFF) << 8) | (session.readBuf[pos + 1] & 0xFF);
        pos += 2;
        int chanByteLen = chanLen * 2;
        if (session.readLen < pos + chanByteLen) {
            session.close();
            return;
        }
        String channel = new String(session.readBuf, pos, chanByteLen, java.nio.charset.StandardCharsets.UTF_16BE);
        pos += chanByteLen;
        if (!"MC|PingHost".equals(channel)) {
            session.close();
            return;
        }

        if (session.readLen < pos + 2) {
            session.close();
            return;
        }
        int payloadSize = ((session.readBuf[pos] & 0xFF) << 8) | (session.readBuf[pos + 1] & 0xFF);
        pos += 2;
        if (session.readLen - pos != payloadSize) {
            session.close();
            return;
        }

        if (session.readLen < pos + 1) {
            session.close();
            return;
        }
        int protocol = session.readBuf[pos++] & 0xFF;
        if (protocol < 73) {
            session.close();
            return;
        }

        if (session.readLen < pos + 2) {
            session.close();
            return;
        }
        int hostLen = ((session.readBuf[pos] & 0xFF) << 8) | (session.readBuf[pos + 1] & 0xFF);
        pos += 2;
        int hostByteLen = hostLen * 2;
        if (session.readLen < pos + hostByteLen) {
            session.close();
            return;
        }
        pos += hostByteLen;

        if (session.readLen < pos + 4) {
            session.close();
            return;
        }
        int port = ((session.readBuf[pos] & 0xFF) << 24)
                | ((session.readBuf[pos + 1] & 0xFF) << 16)
                | ((session.readBuf[pos + 2] & 0xFF) << 8)
                | (session.readBuf[pos + 3] & 0xFF);
        if (port < 0 || port > 65535) {
            session.close();
            return;
        }

        String body = String.format(java.util.Locale.ROOT, "§1\u0000%d\u0000%s\u0000%s\u0000%d\u0000%d",
                127, currentVersion, motd, onlineCount, maxPlayers);
        sendLegacyDisconnect(session, body);
    }

    private void sendLegacyDisconnect(Session session, String reason) throws IOException {
        byte[] bodyBytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        byte[] packet = new byte[1 + 2 + bodyBytes.length];
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) ((reason.length() >> 8) & 0xFF);
        packet[2] = (byte) (reason.length() & 0xFF);
        System.arraycopy(bodyBytes, 0, packet, 3, bodyBytes.length);
        session.out.write(packet);
        session.out.flush();
        session.close();
    }

    private void handleLogin(Session session, Wire.Inbound inbound) throws IOException {
        if (session.loginDone) {
            if (inbound.id() != PacketIds.LOGIN_ACK) {
                // Tolerate pass-through duplicates: Netty's login pipeline
                // can redeliver the hello after LoginFinished (seen live:
                // a second LOGIN_HELLO with id 0x00 arriving post-finish
                // killed the session with "expected login ack, got 31").
                // Anything that is not the ack is absorbed; the ack itself
                // still advances to configuration exactly once.
                return;
            }
            startConfiguration(session, session.loginName);
            return;
        }
        if (session.loginState == Session.LoginState.KEY) {
            handleLoginKey(session, inbound);
            return;
        }
        if (session.loginState == Session.LoginState.AUTHENTICATING
                || session.loginState == Session.LoginState.VERIFYING) {
            // The Mojang verify runs on the auth thread; the client
            // sends nothing until LoginFinished arrives. Anything here
            // is a protocol error.
            throw new IOException("unexpected login packet during authentication");
        }
        if (inbound.id() != PacketIds.LOGIN_HELLO) {
            throw new IOException("expected hello, got " + inbound.id());
        }
        Wire.Reader r = inbound.reader();
        String name = r.utf(16);
        UUID claimed = r.uuid();
        for (char c : name.toCharArray()) {
            if (c < 32 || c > 126 || " <>\"".indexOf(c) >= 0) {
                throw new IOException("bad profile name");
            }
        }
        if (players.size() + pendingPlayers.size() >= maxPlayers) {
            session.send(Outbound.loginDisconnect("The server is full!"));
            session.flush();
            session.close();
            return;
        }
        if (onlineMode) {
            // Online mode: ask for the key exchange (vanilla puts hello
            // before compression: ServerLoginPacketListenerImpl.java:128-130).
            session.loginName = name;
            session.loginChallenge = SessionCipher.freshChallenge();
            session.loginState = Session.LoginState.KEY;
            session.send(Outbound.loginHello(
                    keyPair.getPublic().getEncoded(), session.loginChallenge));
            session.flush();
            return;
        }
        // Offline mode: server-minted uuid (client claim ignored, like
        // vanilla's createOfflineProfile: UUIDUtil.java:90-93).
        UUID profile = Wire.offlineUuid(name);
        finishLogin(session, profile, name, List.of());
    }

    /**
     * Online key exchange: decrypt the secret, verify the challenge,
     * arm link encryption, then verify the session with Mojang on an
     * auth thread ({@code ServerLoginPacketListenerImpl.java:173-237}).
     */
    private void handleLoginKey(Session session, Wire.Inbound inbound) throws IOException {
        if (inbound.id() != PacketIds.LOGIN_KEY) {
            throw new IOException("expected key packet, got " + inbound.id());
        }
        if (session.loginName == null || session.loginChallenge == null) {
            throw new IOException("key packet before hello");
        }
        SessionCipher.KeyPacket key = SessionCipher.KeyPacket.parse(inbound.payload());
        SessionCipher.UnwrappedSecret unwrapped =
                SessionCipher.unwrap(key, keyPair.getPrivate(), session.loginChallenge);
        if (!unwrapped.challengeOk()) {
            throw new IOException("login challenge mismatch");
        }
        javax.crypto.SecretKey secret = unwrapped.secret();
        session.enableEncryption(secret);
        session.loginState = Session.LoginState.AUTHENTICATING;
        final String name = session.loginName;
        String hash;
        try {
            hash = SessionCipher.serverHash(keyPair.getPublic(), secret);
        } catch (IOException e) {
            session.send(Outbound.loginDisconnect("Failed to verify username!"));
            session.flush();
            session.close();
            return;
        }
        final String serverHash = hash;
        Thread auth = new Thread(() -> authenticate(session, name, serverHash),
                "kestrel-authenticator");
        auth.setDaemon(true);
        auth.start();
    }

    /**
     * Mojang session verify on the auth thread. Success continues to
     * VERIFYING then LoginFinished with the real profile; failure
     * disconnects like vanilla's unverified_username branch
     * ({@code ServerLoginPacketListenerImpl.java:209-215}).
     */
    private void authenticate(Session session, String name, String hash) {
        try {
            SessionCipher.VerifiedProfile verified = SessionCipher.verify(name, hash);
            if (session.closed.get()) {
                return;
            }
            if (verified == null) {
                session.loginState = Session.LoginState.HELLO;
                session.send(Outbound.loginDisconnect("Failed to verify username!"));
                session.flush();
                session.close();
                return;
            }
            session.loginProperties.clear();
            session.loginProperties.addAll(verified.properties());
            session.loginState = Session.LoginState.VERIFYING;
            finishLogin(session, verified.id(), verified.name(), session.loginProperties);
        } catch (IOException e) {
            if (!session.closed.get()) {
                session.loginState = Session.LoginState.HELLO;
                session.send(Outbound.loginDisconnect(
                        "Authentication servers are down. Try again later."));
                try {
                    session.flush();
                } catch (IOException ignored) {
                }
                session.close();
            }
            System.out.println("[kestrel] auth verify failed for " + name + ": " + e.getMessage());
        }
    }

    /** Shared tail: compression, LoginFinished, player record. */
    private void finishLogin(Session session, UUID profile, String name,
            List<Outbound.ProfileProperty> props) throws IOException {
        if (compressionThreshold >= 0) {
            session.send(Outbound.loginCompression(compressionThreshold));
            session.flush();
            session.threshold = compressionThreshold;
        }
        session.send(Outbound.loginFinished(profile, name, props));
        session.flush();
        session.loginDone = true;
        session.loginName = name;
        addPlayer(session, profile, name);
    }

    private void handleConfig(Session session, Wire.Inbound inbound) throws IOException {
        switch (inbound.id()) {
            case PacketIds.CONFIG_SELECT_KNOWN -> {
                List<String[]> accepted = readKnownPacks(inbound.payload());
                StringBuilder packs = new StringBuilder();
                for (String[] p : accepted) {
                    if (packs.length() > 0) {
                        packs.append(',');
                    }
                    packs.append(String.join(":", p));
                }
                boolean idsOnly = packsAccepted(accepted);
                System.out.println("[kestrel] packs from " + session.loginName
                        + ": [" + packs + "] -> " + (idsOnly ? "ids-only" : "FULL VALUES"));
                sendRegistries(session, accepted);
                session.send(Outbound.updateTags(datapackTags()));
                session.send(Outbound.finishConfiguration());
            }
            case PacketIds.CONFIG_FINISH -> enterPlay(session);
            case PacketIds.CONFIG_KEEPALIVE_IN -> {
                long id = inbound.reader().l();
                session.send(Outbound.configKeepAlive(id));
            }
            case PacketIds.CONFIG_CLIENT_INFO -> {
                // ClientInformation: UTF language + byte viewDistance +
                // the rest we ignore. Clamp 2..16 like vanilla.
                session.clientInformation = inbound.payload();
                try {
                    Wire.Reader cr = inbound.reader();
                    cr.utf(); // language
                    int vd = cr.b() & 0xFF;
                    // The server's view-distance caps what the client asked
                    // for, which is what vanilla does. A server started with no
                    // properties file keeps Kestrel's historical cap of 16.
                    int viewCap = properties != null ? properties.viewDistance() : 16;
                    session.pendingViewDistance = Math.min(viewCap, Math.max(2, vd));
                    if (session.player != null) {
                        session.player.viewDistance = session.pendingViewDistance;
                    }
                } catch (Exception ignored) {
                    // malformed: keep default 10
                }
            }
            default -> {
                // Absorb unknown config packets (custom payloads, resource packs).
            }
        }
    }

    private void handlePlay(Session session, Wire.Inbound inbound) throws IOException {
        KestrelPlayer player = session.player;
        if (player == null) {
            return;
        }
        Wire.Reader r = inbound.reader();
        switch (inbound.id()) {
            case PacketIds.PLAY_ACCEPT_TELEPORT -> events.offer(
                    new PlayerEvent.AcceptTeleport(player.profile, r.varInt()));
            case PacketIds.PLAY_PLAYER_LOADED -> events.offer(
                    new PlayerEvent.Loaded(player.profile));
            case PacketIds.PLAY_KEEPALIVE_IN -> events.offer(
                    new PlayerEvent.KeepAlive(player.profile, r.l()));
            case PacketIds.PLAY_CHUNK_BATCH_RECEIVED -> {
                r.f(); // client's desired chunks/tick; one-in-flight is the hard cap
                // This is transport flow control, not world state. Release
                // the gate on the I/O thread immediately so acknowledgements
                // neither consume gameplay event admission nor sit behind a
                // movement flood until a later tick.
                session.finishChunkBatch();
            }
            case PacketIds.PLAY_CLIENT_TICK_END -> {
                // Heartbeat; no state.
            }
            case PacketIds.PLAY_MOVE_POS -> {
                double x = r.d(), y = r.d(), z = r.d();
                int flags = r.b();
                events.offer(new PlayerEvent.Move(player.profile, x, y, z,
                        null, null, (flags & 1) != 0, (flags & 2) != 0));
            }
            case PacketIds.PLAY_MOVE_POSROT -> {
                double x = r.d(), y = r.d(), z = r.d();
                float yaw = r.f(), pitch = r.f();
                int flags = r.b();
                events.offer(new PlayerEvent.Move(player.profile, x, y, z,
                        yaw, pitch, (flags & 1) != 0, (flags & 2) != 0));
            }
            case PacketIds.PLAY_MOVE_ROT -> {
                float yaw = r.f(), pitch = r.f();
                int flags = r.b();
                events.offer(new PlayerEvent.Move(player.profile, null, null, null,
                        yaw, pitch, (flags & 1) != 0, (flags & 2) != 0));
            }
            case PacketIds.PLAY_MOVE_STATUS -> {
                int flags = r.b();
                events.offer(new PlayerEvent.Move(player.profile, null, null, null,
                        null, null, (flags & 1) != 0, (flags & 2) != 0));
            }
            case PacketIds.PLAY_PLAYER_ACTION -> {
                // ServerboundPlayerAction: varint action + BlockPos long
                // + face byte + varint sequence. START/ABORT/STOP (0-2)
                // run the block-break state machine on the tick thread
                // (BlockBreaking) and are acked with their sequence.
                int action = r.varInt();
                long posLong = r.l();
                r.b(); // face
                int sequence = r.varInt();
                if (action == 6) {
                    events.offer(new PlayerEvent.SwapOffhand(player.profile));
                } else if (action == 3) {
                    events.offer(new PlayerEvent.DropItem(player.profile, true));
                } else if (action == 4) {
                    events.offer(new PlayerEvent.DropItem(player.profile, false));
                } else {
                    events.offer(new PlayerEvent.Dig(player.profile, posLong, action, sequence));
                }
            }
            case PacketIds.PLAY_SET_CARRIED_ITEM -> events.offer(
                    new PlayerEvent.SetCarried(player.profile, r.s()));
            case PacketIds.PLAY_USE_ITEM_ON -> {
                // ServerboundUseItemOnPacket.STREAM_CODEC: varint hand,
                // then FriendlyByteBuf.writeBlockHitResult (BlockPos
                // long, varint face, three cursor floats relative to the
                // block, inside bool, world-border-hit bool), then
                // varint sequence. Byte layout confirmed by encoding a
                // packet through the 26.2 codec.
                int hand = r.varInt();
                long posLong = r.l();
                int face = r.varInt();
                float cursorX = r.f();
                float cursorY = r.f();
                float cursorZ = r.f();
                boolean inside = r.bool();
                boolean worldBorderHit = r.bool();
                int sequence = r.varInt();
                events.offer(new PlayerEvent.Place(player.profile, posLong, face, hand, sequence,
                        cursorX, cursorY, cursorZ, inside, worldBorderHit));
            }
            case PacketIds.PLAY_CHAT -> {
                // ServerboundChatPacket.STREAM_CODEC: UTF message (max 256 chars),
                // long timestamp, long salt, optional MessageSignature,
                // LastSeenMessages.Update.
                String message = r.utf();
                if (message.isEmpty() || message.length() > 256) {
                    // Reject empty and overlong messages: vanilla disconnects
                    // (multiplayer.disconnect.illegal_characters / string too long),
                    // but Kestrel absorbs and keeps the session connected (same leniency
                    // precedent as the invalid-face handling).
                    return;
                }
                events.offer(new PlayerEvent.Chat(player.profile, message));
            }
            case PacketIds.PLAY_CHAT_COMMAND -> offerVanilla(player, net.minecraft.network.protocol.game.ServerboundChatCommandPacket.STREAM_CODEC, inbound.payload());
            case PacketIds.PLAY_COMMAND_SUGGESTION -> offerVanilla(player, net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket.STREAM_CODEC, inbound.payload());
            case PacketIds.PLAY_CHANGE_GAME_MODE -> offerVanilla(player, net.minecraft.network.protocol.game.ServerboundChangeGameModePacket.STREAM_CODEC, inbound.payload());
            case PacketIds.PLAY_CHAT_COMMAND_SIGNED -> {
                // Dropped on purpose: a client signs only a command with signable arguments
                // (say, msg, me), and none of those exists in the honoured command set
                // (HonouredCommands), so a stock client never sends this packet.
            }
            case PacketIds.PLAY_CONTAINER_CLICK -> {
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                        Unpooled.wrappedBuffer(inbound.payload()),
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
                try {
                    ServerboundContainerClickPacket packet = ServerboundContainerClickPacket.STREAM_CODEC.decode(buf);
                    events.offer(new PlayerEvent.ContainerClick(player.profile, packet.containerId(),
                            packet.stateId(), packet.slotNum(), packet.buttonNum(), packet.containerInput(),
                            packet.changedSlots(), packet.carriedItem()));
                } finally {
                    buf.release();
                }
            }
            case PacketIds.PLAY_CONTAINER_CLOSE -> {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(inbound.payload()));
                try {
                    ServerboundContainerClosePacket packet = ServerboundContainerClosePacket.STREAM_CODEC.decode(buf);
                    events.offer(new PlayerEvent.ContainerClose(player.profile, packet.getContainerId()));
                } finally {
                    buf.release();
                }
            }
            case PacketIds.PLAY_PLAYER_INPUT, PacketIds.PLAY_PLAYER_COMMAND, PacketIds.PLAY_SWING,
                    PacketIds.PLAY_CLIENT_INFORMATION ->
                // G15: the body decodes these on the tick thread (PlayerBodies.packet).
                events.offer(new PlayerEvent.BodyPacket(player.profile, inbound.id(), inbound.payload()));
            default -> {
                // Absorb everything else (inventory, interactions, actions).
            }
        }
    }

    /**
     * Decode one serverbound packet with vanilla's own codec and queue it for the stand-in's
     * listener. A malformed payload is absorbed (Kestrel's play-phase leniency; vanilla
     * disconnects).
     */
    private void offerVanilla(KestrelPlayer player, net.minecraft.network.codec.StreamCodec<
            ? super FriendlyByteBuf, ? extends net.minecraft.network.protocol.Packet<
                    net.minecraft.network.protocol.game.ServerGamePacketListener>> codec, byte[] payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ServerGamePacketListener> packet =
                    codec.decode(buf);
            if (!buf.isReadable()) {
                events.offer(new PlayerEvent.Vanilla(player.profile, packet));
            }
        } catch (RuntimeException malformed) {
            // absorbed
        } finally {
            buf.release();
        }
    }

    // ---- configuration burst ----

    void startConfiguration(Session session, String name) {
        session.phase = Session.Phase.CONFIGURATION;
        session.send(Outbound.brand(BRAND));
        session.send(Outbound.enabledFeatures());
        session.send(Outbound.selectKnownPacks(List.<String[]>of(CORE_PACK)));
    }

    /** Read the client's accepted known packs (namespace/id/version triples). */
    private static List<String[]> readKnownPacks(byte[] payload) throws IOException {
        Wire.Reader r = new Wire.Reader(payload);
        int n = r.varInt();
        if (n < 0 || n > MAX_KNOWN_PACKS) {
            throw new IOException("known pack count " + n + " outside 0.." + MAX_KNOWN_PACKS);
        }
        List<String[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new String[]{r.utf(), r.utf(), r.utf()});
        }
        return out;
    }

    /**
     * Registries whose codecs need live server context (other
     * registries' tags/values) that a bare local pack load cannot
     * supply. The stock client fails "Failed to parse X from pack
     * vanilla" for exactly these three (seen live across four
     * reports): dimension_type (block tags infiniburn_*), enchantment
     * (item/entity tags enchantable/*, sensitive_to_*), and
     * sulfur_cube_archetype (item tags). They always go WITH
     * contents: our NBT bytes are valid (verified through vanilla's
     * own NbtIo with zero trailing bytes), and network values parse
     * against the accumulating registry context instead of the bare
     * local pack.
     */
    static final java.util.Set<String> ALWAYS_FULL = java.util.Set.of(
            "minecraft:sulfur_cube_archetype");

    private void sendRegistries(Session session, List<String[]> accepted) {
        // Vanilla's rule (SynchronizeRegistriesTask.handleResponse):
        // accepted packs equal requested -> entries WITHOUT contents
        // (client loads values from its local pack); otherwise full
        // values. EXCEPTION: ALWAYS_FULL registries always carry
        // contents (see above): the hybrid is protocol-legal (each
        // entry independently chooses present true/false) and keeps
        // every other registry on the small ids-only path.
        List<Datapack.RegistryEntries> packs = datapack;
        if (packs == null) {
            for (String key : SyncedRegistries.KEYS) {
                session.send(Outbound.registryData(key, List.of()));
            }
            return;
        }
        boolean idsOnly = packsAccepted(accepted);
        for (Datapack.RegistryEntries re : packs) {
            // Static tag-only registries are NEVER synchronized (no
            // RegistryData packet): the client owns them locally and
            // vanilla sends no values for them either (live capture:
            // 29 RegistryData packets, zero for block/item/fluid/...).
            // Their tags still ride the UpdateTags packet.
            if (re.staticOrder() != null) {
                continue;
            }
            if (idsOnly && !ALWAYS_FULL.contains(re.key())) {
                session.send(Outbound.registryDataIdsOnly(re.key(), re.entries()));
            } else {
                session.send(Outbound.registryData(re.key(), re.entries()));
            }
        }
    }

    /** True when the client accepted exactly our requested core pack. */
    private static boolean packsAccepted(List<String[]> accepted) {
        if (accepted.size() != 1) {
            return false;
        }
        String[] got = accepted.get(0);
        return got.length == 3 && got[0].equals(CORE_PACK[0])
                && got[1].equals(CORE_PACK[1]) && got[2].equals(CORE_PACK[2]);
    }

    /**
     * Tags payload: registry order ids per tag built from the loaded
     * datapack ({@code data/minecraft/tags/<registry>/<tag>.json}).
     * Only registries with entries get tag sections; empty ones are
     * skipped (vanilla drops empty payloads in
     * {@code TagNetworkSerialization.java:24-27}).
     */
    private Map<String, Map<String, List<Integer>>> datapackTags() {
        return Datapack.tags(datapack);
    }

    // ---- play entry (tick thread calls this via drain) ----

    /** Enter play for a session whose config finished (runs on I/O thread). */
    private void enterPlay(Session session) {
        // The join burst needs tick-thread world state (spawn lookup),
        // so the I/O thread only flips the phase; the tick thread's
        // pollEvents + streamChunks drives the actual burst on next tick.
        session.phase = Session.Phase.PLAY_JOINING;
    }

    // ---- tick-thread API ----

    /** Network-only paused tick. Vanilla accepts connections but skips player listener ticks. */
    public void tickConnection() {
        admitPendingPlayers();
        pollEvents(false);
    }

    /** Admit pending players into active records on the tick thread. */
    public void admitPendingPlayers() {
        KestrelPlayer p;
        while ((p = pendingPlayers.poll()) != null) {
            Session s = sessions.get(p.profile);
            if (s != null && !s.closed.get()) {
                players.put(p.profile, p);
                playerList.add(p);
            }
        }
    }

    /**
     * Drain inbound play events and apply them to player records.
     * Must run on the tick thread, once per tick.
     */
    public void pollEvents() {
        pollEvents(true);
    }

    private void pollEvents(boolean tickPlayers) {
        admitPendingPlayers();
        // Drain only what was queued at tick start: a session flooding
        // mid-drain waits for the next tick instead of live-locking this one.
        drainBudget = events.size();
        drainedThisTick.clear();
        for (Session s : sessions.values()) {
            s.admittedPackets.set(0);
        }
        while (true) {
            try {
                drainEvents();
                break;
            } catch (RuntimeException e) {
                // One bad event must not kill the tick thread for everyone:
                // count it against its session and carry on with the rest.
                UUID owner = drainingEvent == null ? null : drainingEvent.player();
                Session s = owner == null ? null : sessions.get(owner);
                if (s != null) {
                    // Logged, with the stack on a session's first fault: a
                    // silent count hid G18's tall grass loot failure.
                    if (s.faultedEvents.incrementAndGet() == 1) {
                        System.out.println("[kestrel] event fault for " + owner + ": " + e);
                        e.printStackTrace(System.out);
                    }
                } else {
                    System.out.println("[kestrel] event fault: " + e);
                }
            }
        }
        drainingEvent = null;
        // Connection.tick flushes queued inbound work before the listener
        // tick. The body stand-ins model that listener tick here.
        if (tickPlayers) {
            bodies.tickConnections();
        }
        reportViolations();
    }

    /** Events left to drain this tick (tick thread). */
    private int drainBudget;
    /** Events applied per profile this tick (tick thread). */
    private final Map<UUID, int[]> drainedThisTick = new HashMap<>();
    /** Event being applied, for fault attribution (tick thread). */
    private PlayerEvent drainingEvent;

    /**
     * Next event within this tick's budget, dropping (and counting) a
     * session's events past {@link #MAX_EVENTS_PER_SESSION_PER_TICK}.
     */
    private PlayerEvent nextEvent() {
        while (drainBudget > 0) {
            drainBudget--;
            PlayerEvent event = events.poll();
            drainingEvent = event;
            if (event == null) {
                drainBudget = 0;
                return null;
            }
            if (event instanceof PlayerEvent.Disconnected || event instanceof PlayerEvent.KeepAlive
                    || event instanceof PlayerEvent.AcceptTeleport) {
                return event;
            }
            int[] applied = drainedThisTick.computeIfAbsent(event.player(), k -> new int[1]);
            if (++applied[0] <= MAX_EVENTS_PER_SESSION_PER_TICK) {
                return event;
            }
            Session s = sessions.get(event.player());
            if (s != null) {
                s.droppedEvents.incrementAndGet();
            }
        }
        return null;
    }

    /**
     * One log line per session whose violation counters moved, every
     * {@link #VIOLATION_REPORT_TICKS}, plus opt-in {@link #TRACK_LOG} position
     * telemetry on the same interval.
     */
    private void reportViolations() {
        if (loop.gameTime() % VIOLATION_REPORT_TICKS != 0) {
            return;
        }
        // Live movement telemetry (stuck-center diagnosis 2026-09-14): one
        // line per report tick per SESSION player (sessions map covers
        // logins still in LOGIN/CONFIG, which players map does not yet),
        // so the log shows where the server thinks each player is even
        // when nothing moved. Opt-in: see TRACK_LOG.
        if (TRACK_LOG) {
            java.util.HashSet<UUID> seen = new java.util.HashSet<>();
            for (Session sess : sessions.values()) {
                if (sess.player == null || !seen.add(sess.player.profile)) {
                    continue;
                }
                KestrelPlayer p = sess.player;
                System.out.println("[kestrel] track name=" + p.name
                        // Locale.ROOT: the default locale here is de_DE, which
                        // formats decimals with commas and made the comma-separated
                        // triple unreadable ("pos=0,5,1,0,0,5").
                        + " pos=" + String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", p.x, p.y, p.z)
                        + " chunk=" + p.chunkX() + "," + p.chunkZ()
                        + " placed=" + p.placed
                        + " phase=" + String.valueOf(sess.phase)
                        + " viol=" + p.moveViolations + "/" + p.consecutiveMoveViolations
                        + " tick=" + loop.gameTime());
            }
        }
        for (Session s : sessions.values()) {
            long total = s.violations();
            if (total != s.reportedViolations) {
                s.reportedViolations = total;
                System.out.println("[kestrel] inbound bounds " + s.loginName
                        + ": dropped=" + s.droppedEvents.get()
                        + " oversized=" + s.oversizedFrames.get()
                        + " malformed=" + s.malformedPackets.get()
                        + " faulted=" + s.faultedEvents.get());
            }
        }
    }

    private void drainEvents() {
        PlayerEvent event;
        while ((event = nextEvent()) != null) {
            if (event instanceof PlayerEvent.Move m) {
                applyMove(m);
            } else if (event instanceof PlayerEvent.AcceptTeleport a) {
                KestrelPlayer p = players.get(a.player());
                if (p != null) {
                    p.acceptTeleport(a.id());
                }
            } else if (event instanceof PlayerEvent.KeepAlive k) {
                KestrelPlayer p = players.get(k.player());
                if (p != null && p.keepAlivePending && k.id() == p.keepAliveChallenge) {
                    p.keepAlivePending = false;
                }
            } else if (event instanceof PlayerEvent.Dig dig) {
                // Diagnostic dig-probe (trapdoor-rendering investigation):
                // log the server's OWN authoritative
                // BlockState + Block.getId global network id for the exact
                // targeted position, on EVERY dig action (not just
                // STOP_DESTROY_BLOCK=2), so a single left-click on a
                // suspect block is enough - no need to actually break it.
                // Meant to be diffed against the client's own F3 "Looking
                // at:" readout for the same coordinate: if server says
                // leaf_litter and the client's F3 says a copper trapdoor
                // variant for the identical position, that is decisive
                // proof of a block-state-id mismatch between the two
                // JVMs' registries, independent of anything on the wire
                // (already verified byte-correct by NetCheck).
                {
                    KestrelPlayer probePlayer = players.get(dig.player());
                    net.minecraft.core.BlockPos probePos =
                            net.minecraft.core.BlockPos.of(dig.posLong());
                    BlockState probeState = loop.getBlockState(probePos);
                    int probeId = net.minecraft.world.level.block.Block.getId(probeState);
                    var probeName = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(probeState.getBlock());
                    System.out.println("[kestrel] dig-probe player="
                            + (probePlayer != null ? probePlayer.name : dig.player())
                            + " pos=" + probePos.getX() + "," + probePos.getY() + "," + probePos.getZ()
                            + " action=" + dig.action()
                            + " serverBlock=" + probeName + " globalId=" + probeId
                            + " state=" + probeState);
                }
                // START/ABORT/STOP_DESTROY_BLOCK: handlePlayerAction
                // (ServerGamePacketListenerImpl.java:1270-1327). Nothing
                // happens before the client has loaded; otherwise the
                // block-break action runs and its sequence is acked at the
                // next flush (ackBlockChangesUpTo, :1474-1480). Block writes
                // land in the TickLoop mutation journal, which tick()
                // publishes to every client holding the chunk (H3). Other
                // actions (drop, release, stab) are not built: absorb.
                if (dig.action() >= BlockBreaking.START_DESTROY_BLOCK
                        && dig.action() <= BlockBreaking.STOP_DESTROY_BLOCK) {
                    KestrelPlayer p = players.get(dig.player());
                    dev.kestrel.entity.KestrelPlayerEntity body = p == null ? null : bodies.body(p);
                    if (body == null || !bodies.clientLoaded(p)) {
                        continue;
                    }
                    p.lastActionTick = loop.gameTime();
                    Session digger = sessions.get(dig.player());
                    p.blockBreaking.handleBlockBreakAction(body.vanillaPlayer(), breakWorld(p, digger),
                            BlockPos.of(dig.posLong()), dig.action());
                    if (digger != null) {
                        digger.pendingAckSequence = Math.max(digger.pendingAckSequence, dig.sequence());
                    }
                }
            } else if (event instanceof PlayerEvent.SetCarried carried) {
                KestrelPlayer p = players.get(carried.player());
                if (p != null && carried.slot() >= 0
                        && carried.slot() < net.minecraft.world.entity.player.Inventory.getSelectionSize()) {
                    p.inventory.setSelectedSlot(carried.slot());
                    p.lastActionTick = loop.gameTime();
                }
            } else if (event instanceof PlayerEvent.SwapOffhand swap) {
                KestrelPlayer p = players.get(swap.player());
                if (p != null) {
                    p.inventory.swapOffhand();
                    p.lastActionTick = loop.gameTime();
                }
            } else if (event instanceof PlayerEvent.DropItem drop) {
                KestrelPlayer p = players.get(drop.player());
                KestrelPlayerEntity body = p == null ? null : bodies.body(p);
                if (body != null && bodies.clientLoaded(p) && !body.vanillaPlayer().isSpectator()) {
                    p.lastActionTick = loop.gameTime();
                    body.vanillaPlayer().drop(drop.all());
                }
            } else if (event instanceof PlayerEvent.Place place) {
                applyPlace(place);
            } else if (event instanceof PlayerEvent.Chat chat) {
                applyChat(chat);
            } else if (event instanceof PlayerEvent.Disconnected d) {
                // By record: a second login under the same profile has its
                // own record (and map entry, list entry and body), which
                // this first record's late disconnect must not remove.
                KestrelPlayer gone = d.record();
                players.remove(d.player(), gone);
                playerList.remove(gone);
                stopTracking(gone);
                capture(gone);
                // The player left the world: its id may be minted again after a wrap.
                dev.kestrel.entity.EntityIds.release(gone.entityId);
            } else if (event instanceof PlayerEvent.Loaded l) {
                KestrelPlayer p = players.get(l.player());
                if (p != null) {
                    bodies.loaded(p);
                }
            } else if (event instanceof PlayerEvent.BodyPacket b) {
                KestrelPlayer p = players.get(b.player());
                if (p != null) {
                    p.lastActionTick = loop.gameTime();
                    bodies.packet(p, b.id(), b.payload());
                }
            } else if (event instanceof PlayerEvent.ContainerClick click) {
                KestrelPlayer p = players.get(click.player());
                if (p != null) {
                    p.lastActionTick = loop.gameTime();
                    p.inventory.handleClick(click.containerId(), click.stateId(), click.slotNum(),
                            click.buttonNum(), click.containerInput(), click.changedSlots(),
                            click.carriedItem());
                }
            } else if (event instanceof PlayerEvent.Vanilla vanilla) {
                KestrelPlayer p = players.get(vanilla.player());
                if (p != null) {
                    bodies.handle(p, vanilla.packet());
                }
            } else if (event instanceof PlayerEvent.ContainerClose close) {
                KestrelPlayer p = players.get(close.player());
                if (p != null) {
                    p.lastActionTick = loop.gameTime();
                    p.inventory.close();
                }
            } else if (event instanceof PlayerEvent.BatchReceived) {
                // Flow control only; chunk stream is tick-driven anyway.
            }
        }
    }

    /**
     * Movement validation and anti-cheat enforcement (Roadmap S8).
     *
     * <p>Movement packets are validated against per-tick rate limits on the tick thread:
     * <ul>
     *   <li>Max teleport distance: {@link #MAX_TELEPORT_DISTANCE} (64 blocks). Clamped per leniency precedent.</li>
     *   <li>Max horizontal speed: {@link #MAX_HORIZONTAL_SPEED} (0.60 m/tick, sprint ~0.28 m/tick + margin).</li>
     *   <li>Max vertical rise: {@link #MAX_VERTICAL_RISE} (0.50 m/tick, jump ~0.42 m first tick; >0.5 sustained = fly).</li>
     *   <li>Gravity sanity: {@link #MAX_JUMP_HEIGHT} (1.2523 m). If airborne without preceding jump or rising above the jump apex.</li>
     * </ul>
     *
     * <p>Violation policy: reject the move (retain last good pos), increment violation counters.
     * On {@link #MAX_CONSECUTIVE_VIOLATIONS} (20 consecutive ticks = 1 second of cheating), force a position
     * correction via Outbound.position with a fresh teleport ID (rubber-band). Never disconnect.
     * Full physics (step 2 terrain collision) arrives later; this is rate-limiting, not simulation.
     */
    private void applyMove(PlayerEvent.Move m) {
        KestrelPlayer p = players.get(m.player());
        if (p == null || !p.placed) {
            return;
        }
        p.lastActionTick = loop.gameTime();

        // View angles (yaw/pitch) are always accepted so camera rotation is never locked (vanilla behavior).
        if (m.yaw() != null) {
            p.yaw = m.yaw();
        }
        if (m.pitch() != null) {
            p.pitch = m.pitch();
        }

        // Rotation-only or status-only packets (no x/y/z coordinates).
        if (m.x() == null) {
            if (m.onGround() != null) {
                p.onGround = m.onGround();
                if (p.onGround) {
                    p.airborneRise = 0.0;
                    p.jumpEligible = true;
                }
            }
            // G15: vanilla runs these through the same accepted branch, at the current position.
            bodies.moved(p, null, null, null, m.yaw(), m.pitch(), m.onGround() != null && m.onGround(),
                    m.horizontalCollision());
            return;
        }

        double targetX = m.x();
        double targetY = m.y();
        double targetZ = m.z();
        boolean targetOnGround = m.onGround() != null && m.onGround();

        // 1. Sanity check: non-finite coordinates.
        if (Double.isNaN(targetX) || Double.isNaN(targetY) || Double.isNaN(targetZ)
                || Double.isInfinite(targetX) || Double.isInfinite(targetY) || Double.isInfinite(targetZ)) {
            handleMoveViolation(p);
            return;
        }

        double dx = targetX - p.x;
        double dy = targetY - p.y;
        double dz = targetZ - p.z;
        double distSq = dx * dx + dy * dy + dz * dz;

        // 2. Max teleport distance per single packet (vanilla disconnects beyond 64 blocks; we clamp).
        if (distSq > MAX_TELEPORT_DISTANCE * MAX_TELEPORT_DISTANCE) {
            handleMoveViolation(p);
            return;
        }

        // 3. Max horizontal speed per tick (sprint ~0.28 m/tick; allow margin up to 0.60 m/tick).
        double horizontalDist = Math.hypot(dx, dz);
        if (horizontalDist > MAX_HORIZONTAL_SPEED) {
            handleMoveViolation(p);
            return;
        }

        // Water check first: swimming/diving/floating obeys buoyancy,
        // not jump physics. Either endpoint in water selects the swim
        // caps; the gravity-sanity gate is skipped (rising in water is
        // swimming up, not air-jumping).
        boolean inWater = isWater(p.x, p.y, p.z) || isWater(targetX, targetY, targetZ);

        // 4. Max vertical rise per tick (jump ~0.42 m; swim up to ~6 m;
        // >cap sustained = fly).
        double riseCap = inWater ? MAX_SWIM_RISE : MAX_VERTICAL_RISE;
        if (dy > riseCap && (properties == null || !properties.allowFlight())) {
            handleMoveViolation(p);
            return;
        }

        // 5. Gravity sanity (cheap, no full physics), land only:
        // If onGround=false and player rises without a preceding jump or exceeds jump apex (1.2523 m).
        // Full physics (terrain collision) arrives in step 2; this is rate-limiting, not simulation.
        if (!inWater) {
            if (dy > 0) {
                if (p.onGround) {
                    // Preceding tick was on ground: player initiates a jump.
                    p.airborneRise = dy;
                    p.jumpEligible = false;
                } else {
                    // In air: check if rising without a preceding jump or exceeding jump height.
                    if (!p.jumpEligible && p.airborneRise <= 0.0 && (properties == null || !properties.allowFlight())) {
                        handleMoveViolation(p);
                        return;
                    }
                    p.airborneRise += dy;
                    if (p.airborneRise > MAX_JUMP_HEIGHT && (properties == null || !properties.allowFlight())) {
                        handleMoveViolation(p);
                        return;
                    }
                }
            } else {
                // Not rising: if in air, jump is finished/falling.
                if (!p.onGround) {
                    p.jumpEligible = false;
                }
            }
        } else {
            // Swimming: buoyancy owns vertical motion; keep the jump
            // trackers neutral so leaving the water starts clean.
            p.airborneRise = 0.0;
            p.jumpEligible = true;
        }

        // Diagnostic: a large accepted downward move while the column
        // below the target position has no generated chunk is exactly the
        // "fell into the void" symptom (client had no collision because
        // the chunk's own generation pass never actually completed, only
        // an all-air placeholder was sent). Throttled per-player so a real
        // sustained fall doesn't spam one line per tick.
        //
        // Second case (added after a 2026-09-17 report of walking normally
        // for a while, then falling through, that turned out to be a stale
        // JVM rather than a live bug - but exposed that this diagnostic had
        // no way to catch the DIFFERENT defect that symptom would actually
        // look like): the chunk finished generating, but THIS player's
        // client never received the write that finished it - an all-air
        // copy sent before generation completed, never invalidated for this
        // profile (see the "stale-copy invalidation" comments in
        // ChunkSender#sectionBytes/#drainBackfill). That looks identical to
        // "walk fine, then fall through" in-game but is a resend-tracking
        // bug, not a generation-completeness bug, so it needs a different
        // fix. Detected by comparing this chunk's current write version
        // against the version last actually sent to this profile.
        if (dy < VOID_FALL_LOG_THRESHOLD && !targetOnGround) {
            int tcx = (int) Math.floor(targetX) >> 4;
            int tcz = (int) Math.floor(targetZ) >> 4;
            boolean generated = loop.store().isChunkGenerated(
                    net.minecraft.world.level.ChunkPos.pack(tcx, tcz));
            String cause = null;
            if (!generated) {
                cause = "chunkGenerated=false";
            } else {
                ChunkSender cs = chunkSenderRef;
                if (cs != null) {
                    long key = ChunkSender.pack(tcx, tcz);
                    long sentV = cs.sentVersion(p.profile, key);
                    long curV = cs.writeVersion(key);
                    if (sentV != curV) {
                        cause = "chunkGenerated=true staleCopy sentVersion=" + sentV
                                + " currentVersion=" + curV;
                    }
                }
            }
            long nowMs = System.currentTimeMillis();
            Long last = lastVoidFallLog.get(p.profile);
            if (cause != null && (last == null || nowMs - last > VOID_FALL_LOG_COOLDOWN_MS)) {
                lastVoidFallLog.put(p.profile, nowMs);
                System.out.println("[kestrel] void-fall player=" + p.name
                        + " from=" + p.x + "," + p.y + "," + p.z
                        + " to=" + targetX + "," + targetY + "," + targetZ
                        + " dy=" + dy + " chunk=" + tcx + "," + tcz
                        + " " + cause + " tick=" + loop.gameTime());
            }
        }

        // Move is accepted: apply new position and reset consecutive violations.
        p.moveTo(targetX, targetY, targetZ, p.yaw, p.pitch, targetOnGround);
        p.consecutiveMoveViolations = 0;
        if (randomTicks != null) {
            // G30, ChunkMap.move: the simulation ticket follows the player's section.
            randomTicks.movePlayer(p.profile, p.x, p.y, p.z, false);
        }
        // G15: the body takes the move (vanilla's accepted branch), and
        // ChunkMap.move at its point: every tracked entity re-decides for
        // this player, and the player's own body for everyone.
        bodies.moved(p, targetX, targetY, targetZ, m.yaw(), m.pitch(), targetOnGround, m.horizontalCollision());
    }

    /** Downward per-tick move (dy) that, combined with an ungenerated target
     *  column, is logged as a likely void-fall (not itself a hard cap). */
    private static final double VOID_FALL_LOG_THRESHOLD = -1.0;
    /** Minimum gap between void-fall log lines for the same player. */
    private static final long VOID_FALL_LOG_COOLDOWN_MS = 2000L;
    private final Map<UUID, Long> lastVoidFallLog = new ConcurrentHashMap<>();

    /**
     * True when the block at (x, y, z) is water (still or flowing): the
     * player's feet block or the target block. Reads the live store
     * through the tick loop; unset cells read as air (not water).
     */
    private boolean isWater(double x, double y, double z) {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        net.minecraft.world.level.block.state.BlockState st = loop.getBlockState(pos);
        if (st == null) {
            return false;
        }
        // Water block OR any waterlogged/fluid water (flowing water is a
        // separate block from still WATER but shares the fluid).
        return st.is(net.minecraft.world.level.block.Blocks.WATER)
                || !st.getFluidState().isEmpty()
                        && st.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    private void handleMoveViolation(KestrelPlayer p) {
        p.moveViolations++;
        p.consecutiveMoveViolations++;
        if (p.consecutiveMoveViolations >= MAX_CONSECUTIVE_VIOLATIONS) {
            Session session = sessions.get(p.profile);
            if (session != null) {
                int teleportId = p.nextTeleportId();
                session.send(Outbound.position(teleportId, p.x, p.y, p.z, p.yaw, p.pitch));
                bodies.teleported(p);
            }
            p.consecutiveMoveViolations = 0;
        }
    }

    /**
     * Use-item-on (tick thread): {@code ServerGamePacketListenerImpl.handleUseItemOn} on the
     * placer's stand-in player ({@link BlockPlacing}). The click places the block of the item in
     * the clicked hand, in the state that block's own {@code getStateForPlacement} gives, and
     * consumes it; what is refused, what is sent to the placer and what is swung to the others are
     * vanilla's. Nothing happens before the client has loaded, not even the ack; after that every
     * click is acked with its sequence at the next flush, after the block updates. A face or hand
     * ordinal outside the enum is a decode failure and a disconnect in vanilla; Kestrel keeps the
     * session, acks, and does nothing else (the same leniency as the other decoders).
     */
    private void applyPlace(PlayerEvent.Place place) {
        KestrelPlayer p = players.get(place.player());
        Session placer = sessions.get(place.player());
        KestrelPlayerEntity body = p == null ? null : bodies.body(p);
        if (body == null || !bodies.clientLoaded(p)) {
            return;
        }
        if (placer != null) {
            // ackBlockChangesUpTo (ServerGamePacketListenerImpl.java:1474-1480), flushed with the tick.
            placer.pendingAckSequence = Math.max(placer.pendingAckSequence, place.sequence());
        }
        if (place.face() < 0 || place.face() > 5 || place.hand() < 0 || place.hand() > 1) {
            return;
        }
        BlockPos clicked = BlockPos.of(place.posLong());
        net.minecraft.core.Direction face = net.minecraft.core.Direction.from3DDataValue(place.face());
        net.minecraft.world.InteractionHand hand = place.hand() == 0
                ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND;
        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                new net.minecraft.world.phys.Vec3(clicked.getX() + (double) place.cursorX(),
                        clicked.getY() + (double) place.cursorY(), clicked.getZ() + (double) place.cursorZ()),
                face, clicked, place.inside(), place.worldBorderHit());
        BlockPlacing.handleUseItemOn(body.vanillaPlayer(), new BlockPlacing.World() {
            @Override
            public void sendBlockUpdate(BlockPos pos) {
                if (placer != null) {
                    // Through the stand-in's level: Level.getBlockState gives VOID_AIR outside the
                    // build height, which is what vanilla's ClientboundBlockUpdatePacket carries.
                    placer.send(Outbound.blockUpdate(pos.asLong(), net.minecraft.world.level.block.Block.getId(
                            body.vanillaPlayer().level().getBlockState(pos))));
                }
            }

            @Override
            public boolean isUnderSpawnProtection(BlockPos pos) {
                return isSpawnProtected(pos, p.profile);
            }

            @Override
            public boolean awaitingTeleport() {
                return p.teleportPending;
            }

            @Override
            public void resetLastActionTime() {
                p.lastActionTick = loop.gameTime();
                body.vanillaPlayer().resetLastActionTime();
            }
        }, hand, hit);
    }

    /**
     * Chat broadcast (tick thread). Formats message in vanilla format
     * {@code "<Sender> message"} and broadcasts to all play-phase sessions.
     *
     * <p>G5: Player visibility (spawning other player entities or syncing player
     * lists/tab list between peer sessions) is DEFERRED: do not implement
     * player-info/list packets here.
     */
    private void applyChat(PlayerEvent.Chat chat) {
        KestrelPlayer p = players.get(chat.player());
        if (p == null) {
            return;
        }
        Outbound.Packet pkt = Outbound.systemChat("<" + p.name + "> " + chat.message());
        for (Session s : sessions.values()) {
            if (s.phase == Session.Phase.PLAY && s.player != null) {
                s.send(pkt);
            }
        }
    }

    /**
     * Per-tick networking: join bursting for new players, chunk
     * streaming around each player, keepalive cadence. Must run on the
     * tick thread after the world tick.
     */
    /**
     * Chunk keys forced to a full resend next tick. Block writes no longer
     * use this (they are published as deltas from the TickLoop journal, see
     * {@link #publishBlockChanges}); it stays as the explicit full-resend
     * lever. As a send-time backstop, ChunkSender verifies per-chunk
     * revisions: a chunk whose revision did not move since its last send is
     * skipped even if flagged here.
     */
    private final java.util.Set<Long> dirtyChunks = new java.util.HashSet<>();
    /** Wire block publication into TickLoop immediately before its tracker. */
    public void wireWorldTickOrdering(ChunkSender sender) {
        loop.setBeforeEntityTracker(() -> publishBlockChanges(sender));
        loop.setAfterEntityTick(() -> {
            tickBlockBreaking();
            bodies.tickLevel();
            broadcastInventoryChanges();
        });
    }

    /**
     * Each loaded player's {@code ServerPlayerGameMode.tick}, which
     * {@code ServerPlayer.tick} runs first ({@code ServerPlayer.java:610-611})
     * in the level's entity phase. Kestrel runs every player's before the
     * rest of the player ticks; nothing those read depends on the order.
     */
    private void tickBlockBreaking() {
        for (KestrelPlayer p : List.copyOf(players.values())) {
            dev.kestrel.entity.KestrelPlayerEntity body = bodies.body(p);
            if (body != null) {
                p.blockBreaking.tick(body.vanillaPlayer(), breakWorld(p, sessions.get(p.profile)));
            }
        }
    }

    /**
     * The world one player's block breaking reads and writes; corrections go
     * to {@code session}, crack stages to everyone else nearby.
     */
    private BlockBreaking.World breakWorld(KestrelPlayer breaker, Session session) {
        return new BlockBreaking.World() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                return loop.getBlockState(pos);
            }

            @Override
            public boolean removeBlock(BlockPos pos) {
                return loop.setBlock(pos, loop.getBlockState(pos).getFluidState().createLegacyBlock(), 3);
            }

            @Override
            public void sendBlockUpdate(BlockPos pos) {
                if (session != null) {
                    session.send(Outbound.blockUpdate(pos.asLong(),
                            net.minecraft.world.level.block.Block.getId(loop.getBlockState(pos))));
                }
            }

            @Override
            public boolean isUnderSpawnProtection(BlockPos pos) {
                return isSpawnProtected(pos, breaker.profile);
            }

            @Override
            public void destroyBlockProgress(BlockPos pos, int progress) {
                broadcastBlockDestruction(breaker.entityId, pos, progress);
            }
        };
    }

    /**
     * {@code ServerLevel.destroyBlockProgress(id, pos, progress)}: to every
     * player in join order within 32 blocks except the breaker. As with
     * {@link #broadcastLevelEvent}, only sessions in PLAY receive it.
     */
    private void broadcastBlockDestruction(int id, BlockPos pos, int progress) {
        List<BlockDestructionBroadcast.Receiver> receivers = new ArrayList<>(playerList.size());
        Object dimension = loop.level().dimension();
        for (KestrelPlayer player : playerList) {
            Session session = sessions.get(player.profile);
            if (session == null || session.phase != Session.Phase.PLAY) {
                continue;
            }
            receivers.add(new BlockDestructionBroadcast.Receiver() {
                @Override public Object dimension() { return dimension; }
                @Override public int entityId() { return player.entityId; }
                @Override public double x() { return player.x; }
                @Override public double y() { return player.y; }
                @Override public double z() { return player.z; }
                @Override public void send(Outbound.Packet packet) { session.send(packet); }
            });
        }
        BlockDestructionBroadcast.broadcast(receivers, dimension, id, pos, progress);
    }

    private void broadcastInventoryChanges() {
        for (Session session : sessions.values()) {
            if (session.phase == Session.Phase.PLAY && session.player != null) {
                session.player.inventory.broadcastChanges(
                        p -> session.send(new Outbound.Packet(p.id(), p.payload())));
            }
        }
    }

    /**
     * Above this many changed cells in one chunk in one tick, holders get a
     * full chunk resend instead of per-block updates (the resend is smaller
     * than that many block-update packets).
     */
    static final int MAX_BLOCK_UPDATES_PER_CHUNK = 64;

    /** Live dirty-chunks set (package-private for testing). */
    java.util.Set<Long> dirtyChunks() {
        return dirtyChunks;
    }

    public void tick(ChunkSender sender) {
        // Clock and weather advance before join bursts: a session joining
        // this tick gets the post-advance state in its burst and is not
        // yet PLAY, so it never sees the same update twice.
        tickTime();
        tickNetworkTail(sender);
    }

    /**
     * The network tail after inbound connection work. The paused server path
     * runs this without {@link #tickTime()}, matching MinecraftServer's
     * connection-only early return at MinecraftServer.java:988-989.
     */
    void tickNetworkTail(ChunkSender sender) {
        // Publish worker completions before both join preparation and normal
        // streaming. This lets a forced join ring become eligible in the same
        // tick it finishes while remaining a no-op in legacy sync mode.
        sender.publishAsyncResults();
        for (Session session : sessions.values()) {
            if (session.phase == Session.Phase.PLAY_JOINING && session.player != null) {
                // Two-stage join (void-fall fix 2026-09-14): stage 1 sends
                // the join WITHOUT the teleport, then the spawn 5x5, all
                // flushed in one TCP write IN THAT ORDER, so the client's
                // terrain commits before it is ever placed. Stage 2 runs on
                // the NEXT tick (after the I/O loop flushed stage 1): the
                // teleport + LOAD_START gate, then flip to PLAY. A client
                // that physics-ticks on teleport arrival already stands on
                // committed ground instead of falling through the void.
                KestrelPlayer joining = session.player;
                if (!joining.joinHeadSent) {
                    // Async mode may need several worker completions before
                    // the forced 3x3 spawn ring exists. Send no stage-1/play
                    // packets until it is fully published; legacy mode's
                    // prepare call returns true immediately.
                    if (sender.prepareJoinBurst(joining)) {
                        joinHeadless(session);
                        sender.streamBurst(joining, session,
                                () -> joining.inventory.sendInitial(p -> session.send(
                                        new Outbound.Packet(p.id(), p.payload()))));
                        // Stage 1 and its chunks leave on the next I/O flush;
                        // the client must see terrain before the teleport, so
                        // stage 2 waits one full tick after this one.
                        joining.joinHeadSent = true;
                        joining.joinStall = 1;
                    }
                } else if (joining.joinStall > 0) {
                    joining.joinStall--;
                } else {
                    joinTeleport(session);
                    session.phase = Session.Phase.PLAY;
                    if (DEV_DROP_ITEMS_ON_JOIN) {
                        devDropItems(joining);
                    }
                    session.lastKeepAliveTick = loop.gameTime();
                }
            }
        }
        for (Session session : sessions.values()) {
            if (session.pendingAckSequence >= 0) {
                session.send(Outbound.blockChangedAck(session.pendingAckSequence));
                session.pendingAckSequence = -1;
            }
        }
        for (KestrelPlayer player : players.values()) {
            Session session = sessions.get(player.profile);
            if (session == null || session.phase != Session.Phase.PLAY) {
                continue;
            }
            if (!dirtyChunks.isEmpty()) {
                sender.forgetChunks(player.profile, dirtyChunks);
            }
            sender.streamAround(player, session);
            long sinceAlive = loop.gameTime() - session.lastKeepAliveTick;
            if (sinceAlive >= KEEPALIVE_TICKS && !player.keepAlivePending) {
                player.keepAliveChallenge = loop.gameTime();
                player.keepAlivePending = true;
                player.keepAliveSentAt = loop.gameTime();
                session.lastKeepAliveTick = loop.gameTime();
                session.send(Outbound.playKeepAlive(player.keepAliveChallenge));
            } else if (player.keepAlivePending
                    && loop.gameTime() - player.keepAliveSentAt >= KEEPALIVE_TIMEOUT_TICKS) {
                session.send(Outbound.playDisconnect("Timed out"));
                session.close();
            }
        }
        // After every live stream has had its shot at this tick's
        // generation budget, spend any leftover on finishing chunks a
        // fast-moving player abandoned half-generated (island-hole fix).
        // Self-throttling and a no-op whenever the live path generated.
        int playing = 0;
        for (Session s : sessions.values()) {
            if (s.phase == Session.Phase.PLAY && s.player != null) {
                playing++;
            }
        }
        sender.drainBackfill(playing);
        dirtyChunks.clear();
    }

    /**
     * H3: the single notification path for committed block changes. Drains
     * the TickLoop mutation journal (every loop write: dig, place, scheduled
     * ticks, redstone commits, shape side effects), bumps each changed
     * chunk's revision once in ChunkSender (the only revision authority),
     * and sends vanilla's block packets for the chunk
     * ({@link BlockChangePackets}: the cells written with UPDATE_CLIENTS,
     * one packet per changed section) to every session whose client holds
     * that chunk. Sessions that do not hold it get the current
     * state when the chunk is streamed. A chunk with more than
     * {@link #MAX_BLOCK_UPDATES_PER_CHUNK} changes is forgotten instead,
     * so the revision mismatch resends it whole.
     */
    void publishBlockChanges(ChunkSender sender) {
        // G30: vanilla's ChunkHolder packets for the UPDATE_CLIENTS writes.
        java.util.Map<Long, java.util.List<Outbound.Packet>> packets = BlockChangePackets.drain(loop);
        if (!loop.hasBlockChanges()) {
            return;
        }
        java.util.Map<Long, it.unimi.dsi.fastutil.longs.LongArrayList> byChunk =
                new java.util.LinkedHashMap<>();
        loop.drainBlockChanges(p -> byChunk
                .computeIfAbsent(ChunkSender.pack(BlockPos.getX(p) >> 4, BlockPos.getZ(p) >> 4),
                        k -> new it.unimi.dsi.fastutil.longs.LongArrayList())
                .add(p));
        for (java.util.Map.Entry<Long, it.unimi.dsi.fastutil.longs.LongArrayList> e : byChunk.entrySet()) {
            long key = e.getKey();
            it.unimi.dsi.fastutil.longs.LongArrayList cells = e.getValue();
            long revision = sender.markDirty(key);
            for (Session s : sessions.values()) {
                KestrelPlayer holder = s.player;
                if (holder == null || !sender.holdsChunk(holder.profile, key)) {
                    continue;
                }
                if (cells.size() > MAX_BLOCK_UPDATES_PER_CHUNK) {
                    sender.forgetChunks(holder.profile, java.util.Set.of(key));
                    continue;
                }
                // BlockChangePackets keys by ChunkPos.pack; ChunkSender.pack puts x high.
                long chunkPosKey = net.minecraft.world.level.ChunkPos.pack((int) (key >> 32), (int) key);
                for (Outbound.Packet u : packets.getOrDefault(chunkPosKey, java.util.List.of())) {
                    s.send(u);
                }
                sender.markDelivered(holder.profile, key, revision);
            }
        }
    }

    /** Queue a packet for every play-phase session (tick thread). */
    private void broadcastPlay(Outbound.Packet packet) {
        for (Session s : sessions.values()) {
            if (s.phase == Session.Phase.PLAY && s.player != null) {
                s.send(packet);
            }
        }
    }

    /**
     * Advance day time and weather one tick and broadcast what changed,
     * in {@code ServerLevel.advanceWeatherCycle} order: RAIN_LEVEL_CHANGE
     * when the level moved, then START/STOP_RAINING plus the level again
     * when isRaining flipped. SetTime goes out every
     * {@link #TIME_SYNC_TICKS} and on a clock jump.
     */
    void tickTime() {
        tickCount++;
        if (time.consumeClockJump()) {
            broadcastPlay(Outbound.setTime(loop.gameTime(), time.dayTime()));
        }
        time.tick();
        if (time.rainLevelChanged()) {
            broadcastPlay(Outbound.rainLevel(time.rainLevel()));
        }
        if (time.rainStarted() || time.rainStopped()) {
            broadcastPlay(time.rainStarted() ? Outbound.startRaining() : Outbound.stopRaining());
            broadcastPlay(Outbound.rainLevel(time.rainLevel()));
        }
        if (tickCount % TIME_SYNC_TICKS == 0) {
            broadcastPlay(Outbound.setTime(loop.gameTime() - 1));
        }
    }

    /**
     * Join burst for one player (tick thread): packets in vanilla order.
     * Kept as the single-tick form for callers that need the whole burst
     * at once; the live tick path uses the two-stage form below.
     */
    void joinBurst(Session session) {
        joinHeadless(session);
        session.player.placed = true;
        joinTeleport(session);
    }

    /**
     * Join stage 1 (tick N): resolve the position and send everything
     * EXCEPT the teleport. The caller queues the spawn chunks next, so
     * terrain commits before the client is placed. Does NOT set placed:
     * the player stays pre-physics until {@link #joinTeleport} runs.
     */
    void joinHeadless(Session session) {
        KestrelPlayer player = session.player;
        player.viewDistance = session.pendingViewDistance;
        BlockPos spawn = findSpawn();
        StateStore.PlayerState saved = savedPlayers.get(player.profile);
        if (saved != null && saneResume(saved)) {
            // G4: returning player resumes where they saved, before the
            // position packet goes out (in stage 2).
            player.moveTo(saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch(), false);
            player.inventory.setSelectedSlot(saved.heldSlot());
        } else {
            // Fresh spawn: also covers resumes from void/corrupt positions
            // (a previous void-fall persisted y=-250000 must not re-place
            // the player into the void; see saneResume).
            player.moveTo(spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5, 0, 0, false);
        }
        // max-players and the simulation distance come from server.properties.
        // gamemode and hardcore are parsed there but deliberately not applied
        // yet: they belong with the game modes item (G31, size 3, depends on
        // G17 and G22), and telling a client
        // it is in creative or hardcore without the matching server side
        // behaviour would desync it, so both stay at the survival defaults.
        int simDistance = properties != null ? properties.simulationDistance() : 10;
        session.send(Outbound.join(player.entityId, maxPlayers, seed, player.viewDistance,
                simDistance, false, 0));
        session.send(Outbound.difficulty());
        // allow-flight disables the anti-fly check (see the movement checks),
        // it does not grant flight, so the may-fly bit stays clear.
        session.send(Outbound.abilities());
        session.send(Outbound.heldSlot(player.heldSlot()));
        session.send(Outbound.recipes());
        // G15: the tab list rows go out where vanilla sends them, before the
        // level info; the body joins the level after it.
        startTracking(player, session);
        session.send(Outbound.initBorder());
        session.send(Outbound.setTimeFull(loop.gameTime(), time.dayTime(), time.theEndTicks()));
        session.send(Outbound.spawnPos(spawn));
        if (time.rainVisible()) {
            // PlayerList.sendLevelInfo: weather rides the join while raining.
            session.send(Outbound.startRaining());
            session.send(Outbound.rainLevel(time.rainLevel()));
        }
        bodies.addToLevel(player);
    }

    /**
     * A saved resume position is usable only when it sits inside world
     * bounds and has a chance of standing on something: y within
     * [minY - 64, minY + height + 64]. Anything outside (void-fall
     * leftovers at y=-250000, corrupt files) falls back to spawn.
     */
    boolean saneResume(StateStore.PlayerState saved) {
        if (saved == null) {
            return false;
        }
        double y = saved.y();
        int minY = loop.level().getMinY();
        return y >= minY - 64 && y <= minY + loop.level().getHeight() + 64
                && Double.isFinite(saved.x()) && Double.isFinite(y) && Double.isFinite(saved.z());
    }

    /**
     * Join stage 2 (tick N+1, after the spawn 5x5 flushed): the teleport
     * that places the client, then mark placed so movement applies.
     */
    void joinTeleport(Session session) {
        KestrelPlayer player = session.player;
        session.send(Outbound.gameEvent(PacketIds.EVENT_LEVEL_CHUNKS_LOAD_START, 0));
        int teleportId = player.nextTeleportId();
        session.send(Outbound.position(teleportId, player.x, player.y, player.z,
                player.yaw, player.pitch));
        player.placed = true;
    }

    /** Fixed spawn set by {@link #setFixedSpawn}, or null for the scan. */
    private volatile BlockPos fixedSpawn;

    /**
     * Pin the spawn to a fixed block position, bypassing the written-cell
     * scan. Imported worlds need this: the scan walks
     * {@code writtenPositions} once per column per ring, which is
     * quadratic in imported sections and hangs the first join
     * (g2-memory-design.md 0.4 item 4). Call before {@link #start}.
     */
    public void setFixedSpawn(BlockPos pos) {
        this.fixedSpawn = pos;
    }

    /**
     * Spawn lookup: the fixed spawn when set; otherwise highest written
     * block at (0, z) scanning outward, else (0, minY+1, 0).
     * Deterministic, no RNG.
     */
    BlockPos findSpawn() {
        BlockPos fixed = fixedSpawn;
        if (fixed != null) {
            return fixed;
        }
        for (int r = 0; r < 64; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int top = topWrittenY(dx, dz);
                    if (top != Integer.MIN_VALUE) {
                        return new BlockPos(dx, top, dz);
                    }
                }
            }
        }
        return new BlockPos(0, loop.level().getMinY() + 1, 0);
    }

    /**
     * The operator list: vanilla's {@code ServerOpList}, loaded from {@code ops.json}
     * ({@link #loadOps}); empty and never saved until then.
     */
    volatile net.minecraft.server.players.ServerOpList ops = OpList.inMemory();

    /** {@code PlayerList.op} for a gate: the player becomes an operator at {@code op-permission-level}. */
    void op(UUID id, String name) {
        OpList.op(ops, id, name, net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(
                net.minecraft.server.permissions.PermissionLevel.byId(
                        properties != null ? properties.opPermissionLevel() : 4)));
    }

    /**
     * {@code DedicatedServer.isUnderSpawnProtection} (DedicatedServer.java:523-539):
     * nothing is protected while the ops list is empty, operators are never
     * protected against, and otherwise a Chebyshev radius around spawn is.
     * Kestrel has a single dimension, so the respawn-dimension test always
     * passes.
     */
    boolean isSpawnProtected(BlockPos pos, UUID player) {
        if (ops.isEmpty() || OpList.isOp(ops, player)) {
            return false;
        }
        if (properties == null || properties.spawnProtection() <= 0) {
            return false;
        }
        BlockPos spawn = findSpawn();
        int sp = properties.spawnProtection();
        int dx = Math.abs(pos.getX() - spawn.getX());
        int dz = Math.abs(pos.getZ() - spawn.getZ());
        return Math.max(dx, dz) <= sp;
    }

    /**
     * Highest written y in the column, or MIN_VALUE when none. Walks
     * the column's section bitsets top-down. The previous form rebuilt
     * {@code writtenPositions()} (every written cell in the world) once
     * per written cell of the column: on a loaded 594-section world
     * that was ~20s per call, paid twice per join (addPlayer on the I/O
     * thread before config, joinHeadless on the tick thread).
     */
    private int topWrittenY(int x, int z) {
        dev.kestrel.loop.SectionStore store = loop.store();
        int minSection = SectionPos.blockToSectionCoord(store.getMinY());
        int maxSection = SectionPos.blockToSectionCoord(store.getMinY() + store.getHeight() - 1);
        int sx = SectionPos.blockToSectionCoord(x);
        int sz = SectionPos.blockToSectionCoord(z);
        for (int sy = maxSection; sy >= minSection; sy--) {
            long key = SectionPos.asLong(sx, sy, sz);
            if (!store.hasSection(key)) {
                continue;
            }
            for (int ly = 15; ly >= 0; ly--) {
                if (store.isCellWritten(key,
                        dev.kestrel.loop.SectionStore.indexFor(new BlockPos(x, ly, z)))) {
                    return (sy << 4) + ly;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Register a fresh player record (called by login path on I/O thread). */
    void addPlayer(Session session, UUID profile, String name) {
        BlockPos spawn = findSpawn();
        KestrelPlayer player = new KestrelPlayer(profile, name,
                spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5);
        player.viewDistance = session.pendingViewDistance;
        session.player = player;
        sessions.put(profile, session);
        pendingPlayers.offer(player);
    }

    // ---- G4 state persistence ----

    /**
     * Restore saved clock, weather, and player records from the world
     * root. Call after the world's regions loaded (the sidecar owns the
     * loop's gameTime) and before {@link #start}, so the first join
     * burst's SetTime carries the restored clock. Missing or invalid
     * files keep the defaults (clock 0, clear, spawn position); never
     * throws.
     */
    public void loadState(java.nio.file.Path worldRoot) {
        StateStore.LevelState level = StateStore.readLevel(worldRoot);
        if (level == null) {
            StateStore.LevelState d = StateStore.LevelState.DEFAULT;
            time.restore(d.dayTime(), d.raining(), d.rainTime(), d.rainLevel(), d.theEndTicks());
        } else {
            time.restore(level.dayTime(), level.raining(), level.rainTime(), level.rainLevel(), level.theEndTicks());
            if (loop.gameTime() == 0 && level.gameTime() != 0) {
                // No region clock restored (fresh or region-less dir):
                // the level file is the only record of it.
                loop.setGameTime(level.gameTime());
            } else if (loop.gameTime() != level.gameTime()) {
                System.out.println("[kestrel] level gameTime " + level.gameTime()
                        + " differs from region gameTime " + loop.gameTime() + "; keeping region");
            }
        }
        savedPlayers.clear();
        for (StateStore.PlayerState p : StateStore.readAllPlayers(worldRoot)) {
            savedPlayers.put(p.profile(), p);
        }
    }

    /** Current clock and weather as a level state record. */
    public StateStore.LevelState levelState() {
        return new StateStore.LevelState(loop.gameTime(), time.dayTime(), time.raining(),
                time.rainTime(), time.rainLevel(), time.theEndTicks());
    }

    /** Saved (or last captured) state for a profile, null when unknown. */
    public StateStore.PlayerState savedPlayer(UUID profile) {
        return savedPlayers.get(profile);
    }

    /**
     * Write clock, weather, and every known player (live ones captured
     * first) under the world root. Pair with {@code WorldStore.save} for
     * the block diffs.
     */
    public void saveState(java.nio.file.Path worldRoot) throws IOException {
        for (KestrelPlayer p : players.values()) {
            capture(p);
        }
        StateStore.writeLevel(worldRoot, levelState());
        for (StateStore.PlayerState p : savedPlayers.values()) {
            StateStore.writePlayer(worldRoot, p);
        }
    }

    /** Snapshot a placed player into the saved map (unplaced records hold spawn, not a real position). */
    private void capture(KestrelPlayer p) {
        if (p.placed) {
            savedPlayers.put(p.profile, new StateStore.PlayerState(p.profile, p.name,
                    p.x, p.y, p.z, p.yaw, p.pitch, p.heldSlot()));
        }
    }

    /** Shut down: stop accepting, close every session. */
    @Override
    public void close() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        for (Session session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }
}
