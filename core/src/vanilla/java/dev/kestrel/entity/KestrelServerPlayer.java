package dev.kestrel.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.players.PlayerList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.TextFilter;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The one constructor-backed vanilla player owned by a Kestrel session.
 * Game mode uses ServerPlayer.java:1799-1800; RuntimeServer defaults to survival.
 */
public final class KestrelServerPlayer extends ServerPlayer {
    private static final Unsafe UNSAFE = unsafe();
    private final @Nullable KestrelPlayerEntity owner;
    private final ServerGamePacketListenerImpl liveConnection;

    KestrelServerPlayer(VanillaEntityLevel level, GameProfile profile, ClientInformation information,
            @Nullable KestrelPlayerEntity owner) {
        this(level.runtime(), profile, information, owner, null);
    }

    private KestrelServerPlayer(Runtime runtime, GameProfile profile, ClientInformation information,
            @Nullable KestrelPlayerEntity owner, java.util.function.@Nullable Consumer<Packet<?>> capture) {
        super(runtime.server, runtime.level, profile, information);
        this.owner = owner;
        this.liveConnection = new ServerGamePacketListenerImpl(runtime.server, new OutboundConnection(owner, capture), this,
                new CommonListenerCookie(profile, 0, information, false));
        this.connection = this.liveConnection;
    }

    public boolean hasOwner() {
        return owner != null;
    }

    public KestrelPlayerEntity owner() {
        if (owner == null) throw new IllegalStateException("inventory-only player has no tracked owner");
        return owner;
    }

    void markClientLoaded() {
        while (!liveConnection.hasClientLoaded()) {
            liveConnection.tickClientLoadTimeout();
        }
    }

    boolean hasClientLoadedState() { return liveConnection.hasClientLoaded(); }

    void restartAfterRespawn() {
        unsetRemoved();
        try {
            Field waiting = ServerGamePacketListenerImpl.class.getDeclaredField("waitingForRespawn");
            Field timer = ServerGamePacketListenerImpl.class.getDeclaredField("clientLoadedTimeoutTimer");
            waiting.setAccessible(true);
            timer.setAccessible(true);
            waiting.setBoolean(liveConnection, false);
            timer.setInt(liveConnection, 60);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot restart client load timer", e);
        }
    }


    /** Constructor-backed owner for the isolated G17 inventory oracle. */
    public static ServerPlayer inventoryOwner() {
        EntityLevel target = new EntityLevel() {
            private final BlockGetter blocks = new BlockGetter() {
                @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
                @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
                @Override public FluidState getFluidState(BlockPos pos) { return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState(); }
                @Override public int getMinY() { return -64; }
                @Override public int getHeight() { return 384; }
            };
            @Override public BlockGetter blocks() { return blocks; }
            @Override public dev.kestrel.collision.ShapeBoxCache shapeCache() { return null; }
            @Override public int getMinY() { return -64; }
            @Override public net.minecraft.world.level.LevelReader levelReader() { throw new UnsupportedOperationException(); }
            @Override public boolean setBlock(BlockPos pos, BlockState state, int flags) { throw new UnsupportedOperationException(); }
            @Override public void levelEvent(int type, BlockPos pos, int data) { throw new UnsupportedOperationException(); }
            @Override public void landingBlockUpdate(KestrelEntity entity, BlockPos pos, BlockState state) { throw new UnsupportedOperationException(); }
            @Override public net.minecraft.util.RandomSource random() { return net.minecraft.util.RandomSource.create(0L); }
            @Override public net.minecraft.world.level.gamerules.GameRules gameRules() { return new net.minecraft.world.level.gamerules.GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS); }
            @Override public void playSound(double x, double y, double z, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound, net.minecraft.sounds.SoundSource source, float volume, float pitch) { }
            @Override public void entityEvent(KestrelEntity entity, byte event) { throw new UnsupportedOperationException(); }
            @Override public boolean addFreshVanillaEntity(net.minecraft.world.entity.Entity entity) { return true; }
        };
        return new KestrelServerPlayer(VanillaEntityLevel.of(target),
                new GameProfile(java.util.UUID.randomUUID(), "inventory-oracle"),
                ClientInformation.createDefault(), null);
    }

    /** Constructor-backed player over a supplied level for inventory click testing. */
    public static ServerPlayer clickOwner(EntityLevel target) {
        return new KestrelServerPlayer(VanillaEntityLevel.of(target),
                new GameProfile(java.util.UUID.randomUUID(), "click-oracle"),
                ClientInformation.createDefault(), null);
    }

    /**
     * A player whose own connection hands every packet sent to it to
     * {@code capture}, so a gate can drive vanilla's
     * {@code ServerGamePacketListenerImpl} handlers and read what they send.
     */
    public static ServerPlayer capturingOwner(EntityLevel target, java.util.function.Consumer<Packet<?>> capture) {
        return new KestrelServerPlayer(VanillaEntityLevel.of(target).runtime(),
                new GameProfile(java.util.UUID.randomUUID(), "click-capture"),
                ClientInformation.createDefault(), null, capture);
    }

    /** Constructor-backed player over a supplied level, for integration gates. */
    public static ServerPlayer lootOwner(EntityLevel target) {
        return new KestrelServerPlayer(VanillaEntityLevel.of(target),
                new GameProfile(java.util.UUID.randomUUID(), "loot-oracle"),
                ClientInformation.createDefault(), null);
    }

    /** Vanilla LivingEntity jump physics; ServerPlayer's stats and food tail is outside this slice. */
    @Override
    public void jumpFromGround() {
        float jumpPower = getJumpPower();
        if (jumpPower <= 1.0E-5F) return;
        net.minecraft.world.phys.Vec3 movement = getDeltaMovement();
        setDeltaMovement(movement.x, Math.max((double) jumpPower, movement.y), movement.z);
        if (isSprinting()) {
            float angle = getYRot() * (float) (Math.PI / 180.0);
            addDeltaMovement(new net.minecraft.world.phys.Vec3(
                    -net.minecraft.util.Mth.sin(angle) * 0.2, 0.0,
                    net.minecraft.util.Mth.cos(angle) * 0.2));
        }
        needsSync = true;
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel level, DamageSource source, float damage) {
        return super.hurtServer(level, source, damage);
    }

    /** Player has no death loot table or custom death loot. */
    @Override
    protected void dropAllDeathLoot(net.minecraft.server.level.ServerLevel level, DamageSource source) {
        dropEquipment(level);
    }

    /** Player.dropEquipment, Player.java:550-556. */
    @Override
    protected void dropEquipment(net.minecraft.server.level.ServerLevel level) {
        if (level.getGameRules().get(GameRules.KEEP_INVENTORY)) return;
        destroyVanishingCursedItems();
        for (int i = 0; i < getInventory().getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = getInventory().removeItemNoUpdate(i);
            if (!stack.isEmpty()) owner().spawnDeathItem(stack);
        }
    }

    /** The vanilla listener's output side, forwarded into the existing per-player queue. */
    private static final class OutboundConnection extends net.minecraft.network.Connection {
        private final @Nullable KestrelPlayerEntity owner;

        private final java.util.function.@Nullable Consumer<Packet<?>> capture;

        OutboundConnection(@Nullable KestrelPlayerEntity owner, java.util.function.@Nullable Consumer<Packet<?>> capture) {
            super(PacketFlow.SERVERBOUND);
            this.owner = owner;
            this.capture = capture;
        }

        @Override
        public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
            if (capture != null) {
                capture.accept(packet);
                return;
            }
            if (owner == null) return;
            for (dev.kestrel.net.Outbound.Packet encoded : dev.kestrel.net.VanillaPacketBridge.encode(packet)) {
                owner.selfObserver().send(encoded);
            }
        }
    }

    void poseUpdate() {
        updatePlayerPose();
    }

    void updateOptionsSynched(int modelCustomisation, net.minecraft.world.entity.HumanoidArm mainHand) {
        getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) modelCustomisation);
        getEntityData().set(DATA_PLAYER_MAIN_HAND, mainHand);
    }

    private static final Field[] THROTTLERS = throttlerFields();

    private static Field[] throttlerFields() {
        try {
            Field[] fields = new Field[3];
            String[] names = {"chatSpamThrottler", "commandSpamThrottler", "dropSpamThrottler"};
            for (int i = 0; i < names.length; i++) {
                fields[i] = ServerGamePacketListenerImpl.class.getDeclaredField(names[i]);
                fields[i].setAccessible(true);
            }
            return fields;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * {@code ServerGamePacketListenerImpl.tick} (:300-306): the spam throttlers decay once per
     * connection tick, so a command count that is never ticked down cannot disconnect a player.
     */
    void tickThrottlers() {
        try {
            for (Field field : THROTTLERS) ((net.minecraft.util.TickThrottler) field.get(liveConnection)).tick();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    void freezingStep() {
        if (!isInPowderSnow || !canFreeze()) {
            setTicksFrozen(Math.max(0, getTicksFrozen() - 2));
        }
        removeFrost();
        tryAddFrost();
    }

    void swingTimeUpdate() {
        updateSwingTime();
    }

    /** Invoke vanilla's private LivingEntity equipment detection at its tick position. */
    void detectEquipmentUpdatesVanilla() {
        try {
            EquipmentDetection.METHOD.invoke(this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("vanilla equipment detection failed", e);
        }
    }

    private static final class EquipmentDetection {
        static final Method METHOD;
        static {
            try {
                METHOD = net.minecraft.world.entity.LivingEntity.class
                        .getDeclaredMethod("detectEquipmentUpdates");
                METHOD.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    /**
     * The one server and player list of a level. Vanilla has one
     * {@code MinecraftServer} and one {@code PlayerList} however many players join, so
     * every stand-in of a level shares these: the scoreboard, the stats and advancements
     * maps (keyed by player id, as {@code PlayerList.stats} is), the packet processor
     * (bound to the thread that first creates a stand-in, the tick thread on a live
     * server) and the tick rate manager.
     */
    record Runtime(RuntimeServer server, VanillaEntityLevel level) {
        static Runtime build(VanillaEntityLevel level) {
            try {
                RuntimeServer server = (RuntimeServer) UNSAFE.allocateInstance(RuntimeServer.class);
                RuntimePlayerList players = (RuntimePlayerList) UNSAFE.allocateInstance(RuntimePlayerList.class);
                server.target = level.target();
                server.players = players;
                players.server = server;
                players.stats = new java.util.HashMap<>();
                players.advancements = new java.util.HashMap<>();
                // PlayerList.java:112-113: the real lists vanilla's own code iterates.
                put(players, PlayerList.class, "server", server);
                // MinecraftServer.java:1227 and others read the field itself, not getPlayerList().
                put(server, MinecraftServer.class, "playerList", players);
                put(players, PlayerList.class, "players", new java.util.ArrayList<ServerPlayer>());
                put(players, PlayerList.class, "playersByUUID", new java.util.HashMap<java.util.UUID, ServerPlayer>());
                StandInServer.Config config = StandInServer.config(level.target());
                // Gates that install no list: nobody is an operator, and nothing is ever written.
                put(players, PlayerList.class, "ops", config.ops != null ? config.ops
                        : new net.minecraft.server.players.ServerOpList(new java.io.File("/nonexistent/ops.json"),
                                new net.minecraft.server.notifications.EmptyNotificationService()));
                // MinecraftServer.java:367, PacketUtils.java:18-22: handler thread ownership.
                put(server, MinecraftServer.class, "packetProcessor",
                        new net.minecraft.network.PacketProcessor(Thread.currentThread()));
                put(server, MinecraftServer.class, "tickRateManager", new ServerTickRateManager(server));
                put(server, MinecraftServer.class, "debugSubscribers",
                        new net.minecraft.util.debug.ServerDebugSubscribers(null));
                server.scoreboard = new ServerScoreboard(server);
                level.attachServer(server);
                return new Runtime(server, level);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot construct player runtime", e);
            }
        }

        /** {@code PlayerList.players}: vanilla's own list, filled by {@link StandInServer}. */
        @SuppressWarnings("unchecked")
        List<ServerPlayer> list() {
            return (List<ServerPlayer>) get(server.players, PlayerList.class, "players");
        }

        @SuppressWarnings("unchecked")
        Map<java.util.UUID, ServerPlayer> byUuid() {
            return (Map<java.util.UUID, ServerPlayer>) get(server.players, PlayerList.class, "playersByUUID");
        }
    }

    static final class RuntimeServer extends DedicatedServer {
        EntityLevel target;
        RuntimePlayerList players;
        ServerScoreboard scoreboard;

        private StandInServer.Config config() { return StandInServer.config(target); }

        private RuntimeServer() {
            super(null, null, null, null, null, null, null, null, null, null);
            throw new AssertionError("constructor must not run");
        }

        @Override public TextFilter createTextFilterForPlayer(ServerPlayer player) { return TextFilter.DUMMY; }
        /** {@code DedicatedServer.getForcedGameType} (:767-769): the {@code gamemode} property when forced. */
        @Override public @Nullable GameType getForcedGameType() {
            StandInServer.Settings settings = config().settings;
            return settings != null && settings.forceGameMode() ? settings.gameMode() : null;
        }
        /** {@code MinecraftServer.getDefaultGameType} (:587-589): the {@code gamemode} property. */
        @Override public GameType getDefaultGameType() {
            StandInServer.Settings settings = config().settings;
            return settings != null ? settings.gameMode() : GameType.SURVIVAL;
        }
        @Override public DedicatedPlayerList getPlayerList() { return players; }
        @Override public int getCommandSpamThresholdSeconds() { return 10; }
        @Override public int getChatSpamThresholdSeconds() { return 10; }
        @Override public boolean enforceSecureProfile() { return false; }
        @Override public boolean isSameThread() { return true; }
        /** Read live: a gate may install the registries after the first stand-in exists. */
        @Override public RegistryAccess.Frozen registryAccess() { return (RegistryAccess.Frozen) target.registryAccess(); }
        @Override public ServerScoreboard getScoreboard() { return scoreboard; }
        /** {@code DedicatedServer.getMaxPlayers} (:480): {@code max-players}. */
        @Override public int getMaxPlayers() {
            StandInServer.Settings settings = config().settings;
            return settings != null ? settings.maxPlayers() : 20;
        }
        /** {@code DedicatedServer.operatorUserPermissions} (:561-563): {@code op-permission-level}. */
        @Override public net.minecraft.server.permissions.LevelBasedPermissionSet operatorUserPermissions() {
            StandInServer.Settings settings = config().settings;
            return net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(
                    net.minecraft.server.permissions.PermissionLevel.byId(
                            settings != null ? settings.opPermissionLevel() : 4));
        }
        /** {@code MinecraftServer.getCommands} (:1702-1704), without a resource manager behind it. */
        @Override public net.minecraft.commands.Commands getCommands() {
            return config().commands();
        }

        /** The target level's holder, loaded from the same resources as its dynamic registries. */
        @Override public net.minecraft.server.ReloadableServerRegistries.Holder reloadableRegistries() {
            return target.reloadableRegistries();
        }

        /** {@code MinecraftServer.getRandomSequence}: the level's shared sequences (loot tables). */
        @Override public net.minecraft.util.RandomSource getRandomSequence(net.minecraft.resources.Identifier key) {
            return target.randomSequence(key);
        }
    }

    static final class RuntimePlayerList extends DedicatedPlayerList {
        MinecraftServer server;
        java.util.Map<java.util.UUID, ServerStatsCounter> stats;
        java.util.Map<java.util.UUID, PlayerAdvancements> advancements;

        private RuntimePlayerList() {
            super(null, null, null);
            throw new AssertionError("constructor must not run");
        }

        @Override public int getMaxPlayers() { return server.getMaxPlayers(); }

        @Override
        public ServerStatsCounter getPlayerStats(Player player) {
            return stats.computeIfAbsent(player.getUUID(), ignored ->
                    new ServerStatsCounter(server, Path.of("/nonexistent/kestrel-player-stats.json")));
        }

        @Override
        public PlayerAdvancements getPlayerAdvancements(ServerPlayer player) {
            return advancements.computeIfAbsent(player.getUUID(), ignored -> {
                try {
                    PlayerAdvancements value =
                            (PlayerAdvancements) UNSAFE.allocateInstance(RuntimeAdvancements.class);
                    put(value, PlayerAdvancements.class, "activeTriggers", new java.util.IdentityHashMap<>());
                    return value;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private static final class RuntimeAdvancements extends PlayerAdvancements {
        private RuntimeAdvancements() {
            super(null, null, null, null, null);
            throw new AssertionError("constructor must not run");
        }

        @Override public void flushDirty(ServerPlayer player, boolean showAdvancements) { }
        @Override public void clearTriggers() { }
        @Override public void setPlayer(ServerPlayer player) { }
    }

    static void put(Object target, Class<?> owner, String name, Object value)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    static Object get(Object target, Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
