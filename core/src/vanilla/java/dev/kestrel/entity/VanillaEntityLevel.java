package dev.kestrel.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * A vanilla {@code ServerLevel} over an {@link EntityLevel}, for the vanilla
 * block code an entity's tick calls into: {@code Fallable.onLand} and
 * {@code onBrokenAfterFall}, {@code canBeReplaced}'s place context, and the
 * {@link EntityProxy} entities that collision contexts and block hooks see.
 * Allocated without running a constructor; it answers exactly the calls
 * those paths make, from the Kestrel level, and every write goes through
 * {@link EntityLevel#setBlock}.
 *
 * <p>The damage-source table is installed from the exact damage-type registry
 * served during configuration before a player can join (G22).
 *
 * <p>For a player's {@link KestrelServerPlayer} (G15) it also answers vanilla's
 * collision walk ({@code getChunkForCollisions}, the world border, no
 * entity colliders), the locator bar's waypoint manager (tracks nothing:
 * the locator bar is not built) and the chunk source's
 * {@code sendToTrackingPlayers}, which a swing calls.
 *
 * <p>Thread-safety: tick thread only.
 */
final class VanillaEntityLevel extends ServerLevel {
    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private static final Map<EntityLevel, VanillaEntityLevel> ADAPTERS = new WeakHashMap<>();

    private EntityLevel target;
    private net.minecraft.server.MinecraftServer server;
    private DamageSources damageSources;
    private net.minecraft.world.level.chunk.ChunkAccess fluidScanChunk;
    private int nextEntityId;

    private VanillaEntityLevel() {
        super(null, Runnable::run, null, null, Level.OVERWORLD, null, false, 0L, List.of(), false);
        throw new AssertionError("constructor must not run");
    }

    /** The one adapter for {@code target}. */
    static VanillaEntityLevel of(EntityLevel target) {
        return ADAPTERS.computeIfAbsent(target, VanillaEntityLevel::create);
    }

    private static VanillaEntityLevel create(EntityLevel target) {
        try {
            VanillaEntityLevel level = (VanillaEntityLevel) UNSAFE.allocateInstance(VanillaEntityLevel.class);
            level.target = target;
            // Isolated non-player gates can exist before networking installs
            // dynamic registries. PlayerBodies replaces this before any join.
            level.damageSources = (DamageSources) UNSAFE.allocateInstance(DamageSources.class);
            level.chunkSource = (ChunkSource) UNSAFE.allocateInstance(ChunkSource.class);
            level.chunkSource.level = level;
            return level;
        } catch (InstantiationException e) {
            throw new IllegalStateException(e);
        }
    }

    EntityLevel target() {
        return target;
    }

    void attachServer(net.minecraft.server.MinecraftServer server) { this.server = server; }

    void setRegistryAccess(net.minecraft.core.RegistryAccess access) {
        damageSources = new DamageSources(access);
    }

    /**
     * {@code Level.getBlockState} / {@code getFluidState} ({@code Level.java:366-382}): outside the
     * valid bounds the answer is void air and no fluid, whatever the store holds there.
     */
    @Override public BlockState getBlockState(BlockPos pos) {
        if (!isInValidBounds(pos)) {
            return net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState();
        }
        return target.blocks().getBlockState(pos);
    }
    @Override public FluidState getFluidState(BlockPos pos) {
        if (!isInValidBounds(pos)) {
            return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }
        return target.blocks().getFluidState(pos);
    }
    @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return target.blocks().getBlockEntity(pos); }
    @Override public int getMinY() { return target.getMinY(); }
    @Override public int getHeight() { return target.getMaxY() - target.getMinY() + 1; }
    @Override public RandomSource getRandom() { return target.random(); }
    @Override public GameRules getGameRules() { return target.gameRules(); }
    /** Kestrel serves only {@code minecraft:vanilla} ({@code Outbound.enabledFeatures}), vanilla's default set. */
    @Override public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() {
        return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
    }
    /**
     * {@code Level.isLoaded} ({@code Level.java:722-726}) and {@code hasChunk} would reach the
     * stand-in chunk source, which has no chunk map; ask the target level instead.
     */
    @Override public boolean hasChunk(int chunkX, int chunkZ) {
        return target.isLoadedChunk(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
    }
    @Override public boolean isLoaded(BlockPos pos) {
        return isInValidBounds(pos) && hasChunk(net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX()),
                net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ()));
    }
    @Override public DamageSources damageSources() { return damageSources; }
    @Override public net.minecraft.world.Difficulty getDifficulty() { return net.minecraft.world.Difficulty.NORMAL; }
    @Override public long getGameTime() { return 0L; }
    @Override public net.minecraft.resources.ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    @Override public boolean isRainingAt(BlockPos pos) { return false; }

    /** No vanilla chunk object backs the live adapter; block reads use {@link #target}. */
    @Override
    public net.minecraft.world.level.chunk.@Nullable ChunkAccess getChunk(int chunkX, int chunkZ,
            net.minecraft.world.level.chunk.status.ChunkStatus status, boolean loadOrGenerate) {
        if (fluidScanChunk == null) fluidScanChunk = fluidScanChunk(this);
        return fluidScanChunk;
    }

    /**
     * {@code EntityFluidInteraction.hasFluidAndLoaded} needs only the section
     * array and its fluid summary before it performs exact reads through this
     * level. Kestrel's block store is fully loaded, so every section is a
     * conservative candidate and the later exact scan remains authoritative.
     */
    static net.minecraft.world.level.chunk.ChunkAccess fluidScanChunk(
            net.minecraft.world.level.LevelHeightAccessor height) {
        try {
            var chunk = (net.minecraft.world.level.chunk.ProtoChunk) UNSAFE.allocateInstance(
                    net.minecraft.world.level.chunk.ProtoChunk.class);
            var sections = new net.minecraft.world.level.chunk.LevelChunkSection[height.getSectionsCount()];
            Field fluidCount = net.minecraft.world.level.chunk.LevelChunkSection.class.getDeclaredField("fluidCount");
            long fluidOffset = UNSAFE.objectFieldOffset(fluidCount);
            for (int i = 0; i < sections.length; i++) {
                sections[i] = (net.minecraft.world.level.chunk.LevelChunkSection) UNSAFE.allocateInstance(
                        net.minecraft.world.level.chunk.LevelChunkSection.class);
                UNSAFE.putShort(sections[i], fluidOffset, (short) 1);
            }
            put(chunk, net.minecraft.world.level.chunk.ChunkAccess.class, "sections", sections);
            put(chunk, net.minecraft.world.level.chunk.ChunkAccess.class, "levelHeightAccessor", height);
            return chunk;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build fluid scan chunk", e);
        }
    }

    private static void put(Object target, Class<?> owner, String name, Object value)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int limit) {
        if (!isInValidBounds(pos)) {
            return false; // Level.setBlock (:225): nothing is written outside the world
        }
        return target.setBlock(pos, state, flags);
    }

    /**
     * {@code ServerLevel.blockEvent} would add to a queue this stand-in never built; the
     * block-event queue is the loop's ({@code NoteBlock.attack} reaches it).
     */
    @Override
    public void blockEvent(BlockPos pos, net.minecraft.world.level.block.Block block, int b0, int b1) {
        target.blockEvent(pos, block, b0, b1);
    }

    /**
     * {@code ServerLevel} reads {@code blockTicks}, which this stand-in never built: the tick
     * queue is the loop's.
     */
    @Override
    public void scheduleTick(BlockPos pos, net.minecraft.world.level.block.Block block, int delay,
            net.minecraft.world.ticks.TickPriority priority) {
        target.scheduleTick(pos, block, delay, priority);
    }

    /**
     * {@code ScheduledTickAccess.scheduleTick(pos, block, delay)} (:25) is a default method that
     * goes straight to {@code getBlockTicks().schedule(createTick(pos, type, delay))}, never
     * through the four-argument one {@code ServerLevel} overrides, and {@code DiodeBlock.setPlacedBy}
     * (:174) uses it. {@code createTick(pos, type, delay)} is the NORMAL priority.
     */
    @Override
    public void scheduleTick(BlockPos pos, net.minecraft.world.level.block.Block block, int delay) {
        target.scheduleTick(pos, block, delay, net.minecraft.world.ticks.TickPriority.NORMAL);
    }

    /** {@code ServerLevel.updateNeighborsAt} reads {@code neighborUpdater}, which this stand-in never built. */
    @Override
    public void updateNeighborsAt(BlockPos pos, net.minecraft.world.level.block.Block sourceBlock,
            net.minecraft.world.level.redstone.@Nullable Orientation orientation) {
        // Orientation only matters with the redstone experiments enabled; Kestrel serves
        // minecraft:vanilla, where ExperimentalRedstoneUtils.initialOrientation is null.
        target.updateNeighborsAt(pos, sourceBlock, null);
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, net.minecraft.world.level.block.Block blockObject,
            net.minecraft.core.Direction skipDirection,
            net.minecraft.world.level.redstone.@Nullable Orientation orientation) {
        target.updateNeighborsAt(pos, blockObject, skipDirection);
    }

    @Override
    public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) {
        // ServerLevel.java:1114: only a player source is left out.
        target.levelEvent(source instanceof net.minecraft.world.entity.player.Player player ? player.getUUID() : null,
                type, pos, data);
    }

    @Override
    public void playSound(@Nullable Entity except, double x, double y, double z, SoundEvent sound,
            SoundSource source, float volume, float pitch) {
        target.playSound(x, y, z, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source, volume, pitch);
    }

    @Override
    public void playSound(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch) {
        target.playSound(x, y, z, sound, source, volume, pitch);
    }

    /** {@code ServerLevel.broadcastEntityEvent}: the event belongs to the proxy's owner. */
    @Override
    public void broadcastEntityEvent(Entity entity, byte event) {
        KestrelEntity owner = entity instanceof KestrelServerPlayer player
                ? player.owner() : ((EntityProxy) entity).owner();
        target.entityEvent(owner, event);
    }

    /** Game events: nothing listens yet (sculk sensors are G12). */
    @Override
    public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) {
    }

    @Override public net.minecraft.server.MinecraftServer getServer() { return server; }
    @Override public net.minecraft.world.TickRateManager tickRateManager() { return server.tickRateManager(); }
    @Override public net.minecraft.server.ServerScoreboard getScoreboard() { return server.getScoreboard(); }
    @Override public net.minecraft.core.RegistryAccess registryAccess() {
        return target.registryAccess();
    }

    @Override public boolean addFreshEntity(Entity entity) { return target.addFreshVanillaEntity(entity); }

    // ---- a player's stand-in (G15) ----

    /** Vanilla's collision walk reads blocks through this; one getter serves every chunk. */
    @Override public net.minecraft.world.level.BlockGetter getChunkForCollisions(int chunkX, int chunkZ) { return target.blocks(); }
    @Override public net.minecraft.world.level.border.WorldBorder getWorldBorder() { return BORDER; }

    @Override
    public List<Entity> getEntities(@Nullable Entity except, net.minecraft.world.phys.AABB bb,
            java.util.function.Predicate<? super Entity> selector) {
        EntityWorld world = target.world();
        if (world == null) {
            return new java.util.ArrayList<>();
        }
        List<Entity> result = new java.util.ArrayList<>();
        world.sections().getEntities(bb, entity -> {
            Entity vanillaEntity = null;
            if (entity instanceof KestrelMovingEntity moving) {
                vanillaEntity = moving.proxy(this.target);
            } else if (entity instanceof KestrelPlayerEntity player) {
                vanillaEntity = player.vanillaPlayer();
            }
            if (vanillaEntity != null && vanillaEntity != except) {
                if (selector == null || selector.test(vanillaEntity)) {
                    result.add(vanillaEntity);
                }
            }
        });
        for (net.minecraft.server.level.ServerPlayer player : playersIn(world, bb)) {
            if (player != except && !result.contains(player) && (selector == null || selector.test(player))) {
                result.add(player);
            }
        }
        return result;
    }

    /**
     * The joined players whose box meets {@code bb}. Vanilla's entity sections hold players too
     * ({@code PersistentEntitySectionManager.addNewEntity} for a {@code ServerPlayer}), so
     * {@code getEntities} finds them; Kestrel's sections do not, the tracker does. Same overlap
     * test as {@code EntitySection.getEntities}: {@code entity.getBoundingBox().intersects(bb)}.
     */
    private List<net.minecraft.server.level.ServerPlayer> playersIn(EntityWorld world, net.minecraft.world.phys.AABB bb) {
        List<net.minecraft.server.level.ServerPlayer> found = new java.util.ArrayList<>();
        if (world.tracker() == null) {
            return found;
        }
        for (KestrelEntity body : world.tracker().playerBodies()) {
            if (body instanceof KestrelPlayerEntity player) {
                net.minecraft.server.level.ServerPlayer vanilla = player.vanillaPlayer();
                if (!vanilla.isRemoved() && vanilla.getBoundingBox().intersects(bb)) {
                    found.add(vanilla);
                }
            }
        }
        return found;
    }

    @Override
    public <T extends Entity> List<T> getEntities(net.minecraft.world.level.entity.EntityTypeTest<Entity, T> type,
            net.minecraft.world.phys.AABB bb, java.util.function.Predicate<? super T> selector) {
        EntityWorld world = target.world();
        if (world == null) {
            return new java.util.ArrayList<>();
        }
        List<T> result = new java.util.ArrayList<>();
        world.sections().getEntities(bb, entity -> {
            Entity vanillaEntity = null;
            if (entity instanceof KestrelMovingEntity moving) {
                vanillaEntity = moving.proxy(this.target);
            } else if (entity instanceof KestrelPlayerEntity player) {
                vanillaEntity = player.vanillaPlayer();
            }
            if (vanillaEntity != null) {
                T match = type.tryCast(vanillaEntity);
                if (match != null && (selector == null || selector.test(match))) {
                    result.add(match);
                }
            }
        });
        for (net.minecraft.server.level.ServerPlayer player : playersIn(world, bb)) {
            T match = type.tryCast(player);
            if (match != null && !result.contains(match) && (selector == null || selector.test(match))) {
                result.add(match);
            }
        }
        return result;
    }

    @Override
    public @Nullable Player getPlayerByUUID(@Nullable UUID uuid) {
        if (uuid == null) return null;
        EntityWorld world = target.world();
        if (world != null && world.tracker() != null) {
            KestrelEntity entity = world.tracker().entityByUuid(uuid);
            if (entity instanceof KestrelPlayerEntity player) {
                return player.vanillaPlayer();
            }
        }
        return null;
    }

    @Override public net.minecraft.server.waypoints.ServerWaypointManager getWaypointManager() { return NO_WAYPOINTS; }

    @Override public net.minecraft.server.level.ServerChunkCache getChunkSource() { return chunkSource; }

    /** Vanilla's default border: far outside any world Kestrel serves. */
    private static final net.minecraft.world.level.border.WorldBorder BORDER = new net.minecraft.world.level.border.WorldBorder();

    private static final net.minecraft.server.waypoints.ServerWaypointManager NO_WAYPOINTS =
            new net.minecraft.server.waypoints.ServerWaypointManager() {
                @Override public void trackWaypoint(net.minecraft.world.waypoints.WaypointTransmitter w) { }
                @Override public void updateWaypoint(net.minecraft.world.waypoints.WaypointTransmitter w) { }
                @Override public void untrackWaypoint(net.minecraft.world.waypoints.WaypointTransmitter w) { }
                @Override public void addPlayer(net.minecraft.server.level.ServerPlayer player) { }
                @Override public void updatePlayer(net.minecraft.server.level.ServerPlayer player) { }
                @Override public void removePlayer(net.minecraft.server.level.ServerPlayer player) { }
            };

    private ChunkSource chunkSource;

    /**
     * {@code ServerChunkCache.sendToTrackingPlayers(AndSelf)}, the only two
     * calls a stand-in makes on it: to the stand-in's own tracked entry.
     */
    static final class ChunkSource extends net.minecraft.server.level.ServerChunkCache {
        VanillaEntityLevel level;

        private ChunkSource() {
            super(null, null, null, null, null, null, 0, 0, false, null, null);
            throw new AssertionError("constructor must not run");
        }

        @Override
        public void sendToTrackingPlayers(Entity entity,
                net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> packet) {
            if (entity instanceof KestrelServerPlayer player) {
                player.owner().broadcast(packet, false);
            } else if (entity instanceof EntityProxy proxy) {
                EntityWorld world = level != null ? level.target.world() : null;
                if (world != null && world.tracker() != null) {
                    for (dev.kestrel.net.Outbound.Packet p : dev.kestrel.net.VanillaPacketBridge.encode(packet)) {
                        world.tracker().sendToTrackingPlayers(proxy.owner(), p);
                    }
                }
            }
        }

        @Override
        public void sendToTrackingPlayersAndSelf(Entity entity,
                net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> packet) {
            if (entity instanceof KestrelServerPlayer player) {
                player.owner().broadcast(packet, true);
            } else if (entity instanceof EntityProxy proxy) {
                EntityWorld world = level != null ? level.target.world() : null;
                if (world != null && world.tracker() != null) {
                    for (dev.kestrel.net.Outbound.Packet p : dev.kestrel.net.VanillaPacketBridge.encode(packet)) {
                        world.tracker().sendToTrackingPlayersAndSelf(proxy.owner(), p);
                    }
                }
            }
        }
    }

    /** Ids for the proxies' own construction; a proxy answers its owner's id. */
    @Override public int getNextEntityId() { return ++nextEntityId; }

    private static sun.misc.Unsafe unsafe() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (sun.misc.Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
