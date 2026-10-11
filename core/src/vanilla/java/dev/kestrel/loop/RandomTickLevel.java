package dev.kestrel.loop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkCatalystBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The vanilla {@code ServerLevel} random ticks and leaf ticks run on, in the
 * style of {@code VanillaEntityLevel}: allocated without running a
 * constructor, it answers the calls vanilla's block and fluid logic makes,
 * reading from the Kestrel world ({@link TickLoop}'s store, and the
 * {@link RandomTicks.Environment} for light, weather, time, biomes and loot)
 * and writing through the loop's live {@code setBlock}, so the neighbour
 * updates, scheduled ticks and client packets are the ones every other write
 * gets.
 *
 * <p>What it cannot answer throws {@link Unanswered} rather than returning a
 * default: sounds (G67), particles, spawning anything but a dropped item
 * (G44), players (G15), a game event with a possible listener in range
 * (sculk, G12), the chunk source and chunks (S16), and every read the
 * environment has not been given. A method not overridden here reaches a
 * field the missing constructor never set and fails with a
 * {@code NullPointerException}, which is loud too.
 *
 * <p>Thread-safety: tick thread only.
 */
public final class RandomTickLevel extends ServerLevel {
    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private static final long RANDOM_OFFSET = offset(Level.class, "random");

    private TickLoop loop;
    private RandomTicks ticks;
    private WorldBorder border;
    private LootServer server;
    private BiomeManager biomeManager;

    private RandomTickLevel() {
        super(null, Runnable::run, null, null, Level.OVERWORLD, null, false, 0L, List.of(), false);
        throw new AssertionError("constructor must not run");
    }

    static RandomTickLevel create(TickLoop loop, RandomTicks ticks) {
        try {
            RandomTickLevel level = (RandomTickLevel) UNSAFE.allocateInstance(RandomTickLevel.class);
            level.loop = loop;
            level.ticks = ticks;
            level.border = new WorldBorder();
            level.syncRandom();
            return level;
        } catch (InstantiationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code Level.random}: the loop's level random (a gate may swap it). */
    void syncRandom() {
        RandomSource random = loop.levelRandom();
        if (UNSAFE.getObject(this, RANDOM_OFFSET) != random) {
            UNSAFE.putObject(this, RANDOM_OFFSET, random);
        }
    }

    private RandomTicks.Environment env() {
        return ticks.environment();
    }

    // ---- blocks ----

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return isInValidBounds(pos) ? loop.store().get(pos) : Blocks.VOID_AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return isInValidBounds(pos) ? loop.store().get(pos).getFluidState() : Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return loop.blockEntities().get(pos);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int updateLimit) {
        return loop.setBlock(pos, state, flags, updateLimit);
    }

    @Override
    public int getMinY() {
        return loop.store().getMinY();
    }

    @Override
    public int getHeight() {
        return loop.store().getHeight();
    }

    @Override
    public int getSeaLevel() {
        return env().seaLevel();
    }

    /**
     * {@code Level.getHeight(type, x, z)}: one above the highest cell the
     * heightmap's predicate accepts, the column's minimum when there is none.
     * Kestrel keeps no heightmaps; this scans the column, which is what a
     * vanilla heightmap equals by definition.
     */
    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000) {
            return getSeaLevel() + 1;
        }
        if (!hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
            return getMinY();
        }
        Predicate<BlockState> opaque = type.isOpaque();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = getMinY() + getHeight() - 1; y >= getMinY(); y--) {
            if (opaque.test(loop.store().get(pos.setY(y)))) {
                return y + 1;
            }
        }
        return getMinY();
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return env().isLoadedChunk(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return isInValidBounds(pos) && hasChunk(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }

    @Override
    public ServerChunkCache getChunkSource() {
        throw new Unanswered("the chunk source", "S16");
    }

    @Override
    public @Nullable ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus status, boolean loadOrGenerate) {
        throw new Unanswered("chunk objects", "S16");
    }

    // ---- ticks and neighbour updates: the loop's own ----

    @Override
    public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int tickDelay, TickPriority priority) {
        return loop.loopTicks().createTick(pos, type, tickDelay, priority);
    }

    @Override
    public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int tickDelay) {
        return loop.loopTicks().createTick(pos, type, tickDelay);
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int tickDelay, TickPriority priority) {
        loop.loopTicks().scheduleTick(pos, block, tickDelay, priority);
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int tickDelay) {
        loop.loopTicks().scheduleTick(pos, block, tickDelay);
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int tickDelay, TickPriority priority) {
        loop.loopTicks().scheduleTick(pos, fluid, tickDelay, priority);
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int tickDelay) {
        loop.loopTicks().scheduleTick(pos, fluid, tickDelay);
    }

    /** {@code ServerLevel.blockTicks} is a vanilla {@code LevelTicks}; Kestrel's queue is not one. */
    @Override
    public net.minecraft.world.ticks.LevelTicks<Block> getBlockTicks() {
        throw new Unanswered("the block tick list object", "G30");
    }

    @Override
    public net.minecraft.world.ticks.LevelTicks<Fluid> getFluidTicks() {
        throw new Unanswered("the fluid tick list object", "G30");
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block sourceBlock) {
        loop.redstone().updateNeighborsAt(pos, sourceBlock);
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block sourceBlock, @Nullable Orientation orientation) {
        loop.redstone().updateNeighborsAt(pos, sourceBlock);
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, Direction skip,
            @Nullable Orientation orientation) {
        loop.redstone().updateNeighborsAtExceptFromFacing(pos, block, skip);
    }

    @Override
    public void neighborChanged(BlockPos pos, Block changedBlock, @Nullable Orientation orientation) {
        loop.redstone().neighborChanged(pos, changedBlock);
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block changedBlock,
            @Nullable Orientation orientation, boolean movedByPiston) {
        loop.redstone().neighborChanged(state, pos, changedBlock, movedByPiston);
    }

    @Override
    public void blockEvent(BlockPos pos, Block block, int b0, int b1) {
        loop.redstone().blockEvent(pos, block, b0, b1);
    }

    @Override
    public boolean isHandlingTick() {
        return loop.redstone().isHandlingTick();
    }

    // ---- world state ----

    @Override
    public RandomSource getRandom() {
        return loop.levelRandom();
    }

    @Override
    public GameRules getGameRules() {
        return loop.gameRules();
    }

    @Override
    public long getGameTime() {
        return loop.gameTime();
    }

    @Override
    public LevelData getLevelData() {
        throw new Unanswered("level data", "S35");
    }

    @Override
    public RegistryAccess registryAccess() {
        return env().registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return FeatureFlags.DEFAULT_FLAGS;
    }

    @Override
    public DimensionType dimensionType() {
        return registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
                .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value();
    }

    @Override
    public net.minecraft.resources.ResourceKey<Level> dimension() {
        return Level.OVERWORLD;
    }

    @Override
    public WorldBorder getWorldBorder() {
        return border;
    }

    @Override
    public EnvironmentAttributeSystem environmentAttributes() {
        return env().environmentAttributes();
    }

    @Override
    public float getRainLevel(float a) {
        return env().rainLevel();
    }

    @Override
    public float getThunderLevel(float a) {
        return env().thunderLevel() * getRainLevel(a);
    }

    @Override
    public int getSkyDarken() {
        return env().skyDarken();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return env().lightEngine();
    }

    @Override
    public Difficulty getDifficulty() {
        throw new Unanswered("difficulty", "S32");
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        throw new Unanswered("local difficulty", "S32");
    }

    // ---- biomes ----

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
        return env().noiseBiome(quartX, quartY, quartZ);
    }

    @Override
    public BiomeManager getBiomeManager() {
        if (biomeManager == null) {
            biomeManager = new BiomeManager(this, env().biomeZoomSeed());
        }
        return biomeManager;
    }

    @Override
    public Holder<Biome> getBiome(BlockPos pos) {
        return getBiomeManager().getBiome(pos);
    }

    // ---- players, fire ----

    /** {@code ServerLevel.canSpreadFireAround}. */
    @Override
    public boolean canSpreadFireAround(BlockPos pos) {
        int spreadRadius = getGameRules().get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER);
        return spreadRadius == -1 || ticks.anyPlayerCloseEnoughTo(pos, spreadRadius);
    }

    @Override
    public List<ServerPlayer> players() {
        throw new Unanswered("the player list", "G15");
    }

    // ---- entities ----

    /**
     * {@code Level.getEntities}: Kestrel's entities and players are not
     * vanilla objects, so any hit is unanswered (the callers push or hurt
     * them). No hit is the exact answer, an empty list.
     */
    @Override
    public List<Entity> getEntities(@Nullable Entity except, AABB box, Predicate<? super Entity> selector) {
        requireNoEntityIn(box);
        return new ArrayList<>();
    }

    @Override
    public <T extends Entity> List<T> getEntities(net.minecraft.world.level.entity.EntityTypeTest<Entity, T> type,
            AABB box, Predicate<? super T> selector) {
        requireNoEntityIn(box);
        return new ArrayList<>();
    }

    private void requireNoEntityIn(AABB box) {
        if (ticks.anyPlayerIn(box)) {
            throw new Unanswered("an entity query that finds a player", "G15");
        }
        dev.kestrel.entity.EntityWorld world = loop.entityWorld();
        if (world != null) {
            boolean[] hit = {false};
            world.sections().getEntities(box, entity -> {
                if (entity.getBoundingBox().intersects(box)) {
                    hit[0] = true;
                }
            });
            if (hit[0]) {
                throw new Unanswered("an entity query that finds an entity", "S14");
            }
        }
    }

    /**
     * {@code ServerLevel.addFreshEntity}. A dropped item
     * ({@code Block.popResource}) becomes a Kestrel item entity with the
     * vanilla entity's position, motion and pickup delay; its UUID and bob
     * offset come from the Kestrel entity's own random, as a fresh vanilla
     * entity's would. Anything else is unanswered.
     */
    @Override
    public boolean addFreshEntity(Entity entity) {
        if (entity instanceof ItemEntity item) {
            dev.kestrel.entity.EntityWorld world = loop.entityWorld();
            if (world == null) {
                throw new Unanswered("item entities on this loop", "S14");
            }
            Vec3 motion = item.getDeltaMovement();
            dev.kestrel.entity.KestrelItemEntity spawned = world.spawnItem(item.getX(), item.getY(), item.getZ(),
                    item.getItem(), motion.x, motion.y, motion.z);
            if (spawned != null) {
                spawned.setPickUpDelay(pickUpDelay(item));
            }
            return spawned != null;
        }
        throw new Unanswered("spawning " + entity.getType(), "G44");
    }

    private static int pickUpDelay(ItemEntity item) {
        try {
            Field field = ItemEntity.class.getDeclaredField("pickupDelay");
            field.setAccessible(true);
            return field.getInt(item);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private int nextEntityId;

    /**
     * Ids for the vanilla entity a block builds before handing it to
     * {@link #addFreshEntity}; that entity is discarded there (a dropped item
     * becomes a Kestrel item with an id of its own), so its id never leaves.
     */
    @Override
    public int getNextEntityId() {
        return ++nextEntityId;
    }

    // ---- effects ----

    /** {@code ServerLevel.levelEvent} with no source: delivered like every other level event. */
    @Override
    public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) {
        if (source != null) {
            throw new Unanswered("a level event with a source entity", "S14");
        }
        loop.emitLevelEvent(type, pos, data);
    }

    @Override
    public void globalLevelEvent(int type, BlockPos pos, int data) {
        throw new Unanswered("global level events", "G67");
    }

    @Override
    public void playSound(@Nullable Entity except, double x, double y, double z, SoundEvent sound,
            SoundSource source, float volume, float pitch) {
        throw new Unanswered("sounds", "G67");
    }

    @Override
    public void playSound(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch) {
        throw new Unanswered("sounds", "G67");
    }

    @Override
    public void playSound(@Nullable Entity except, BlockPos pos, SoundEvent sound, SoundSource source,
            float volume, float pitch) {
        throw new Unanswered("sounds", "G67");
    }

    @Override
    public void playSeededSound(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch, long seed) {
        throw new Unanswered("sounds", "G67");
    }

    @Override
    public void playSeededSound(@Nullable Entity except, Entity sourceEntity, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch, long seed) {
        throw new Unanswered("sounds", "G67");
    }

    @Override
    public <T extends ParticleOptions> int sendParticles(T particle, double x, double y, double z, int count,
            double xDist, double yDist, double zDist, double speed) {
        throw new Unanswered("particles", "G67");
    }

    @Override
    public <T extends ParticleOptions> int sendParticles(T particle, boolean overrideLimiter, boolean alwaysShow,
            double x, double y, double z, int count, double xDist, double yDist, double zDist, double speed) {
        throw new Unanswered("particles", "G67");
    }

    @Override
    public void broadcastEntityEvent(Entity entity, byte event) {
        throw new Unanswered("entity events", "G67");
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState old, BlockState current, int updateFlags) {
        throw new Unanswered("sendBlockUpdated", "G66");
    }

    /**
     * {@code ServerLevel.gameEvent} posts to the listeners registered in the
     * sections the event's radius reaches. Kestrel entities are never
     * listeners, and the only listening blocks are the sculk family, which
     * have no block entity here (G12). With none of them in reach the post
     * reaches nobody, which is the answer; with one in reach it is
     * unanswered.
     */
    @Override
    public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) {
        int radius = gameEvent.value().notificationRadius();
        BlockPos center = BlockPos.containing(position);
        int x0 = SectionPos.blockToSectionCoord(center.getX() - radius);
        int y0 = SectionPos.blockToSectionCoord(center.getY() - radius);
        int z0 = SectionPos.blockToSectionCoord(center.getZ() - radius);
        int x1 = SectionPos.blockToSectionCoord(center.getX() + radius);
        int y1 = SectionPos.blockToSectionCoord(center.getY() + radius);
        int z1 = SectionPos.blockToSectionCoord(center.getZ() + radius);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int sx = x0; sx <= x1; sx++) {
            for (int sz = z0; sz <= z1; sz++) {
                for (int sy = y0; sy <= y1; sy++) {
                    if (!loop.store().hasSection(SectionPos.asLong(sx, sy, sz))) {
                        continue;
                    }
                    for (int i = 0; i < SectionStore.CELLS; i++) {
                        pos.set((sx << 4) + (i & 15), (sy << 4) + ((i >> 8) & 15), (sz << 4) + ((i >> 4) & 15));
                        Block block = loop.store().get(pos).getBlock();
                        if (block instanceof SculkSensorBlock || block instanceof SculkShriekerBlock
                                || block instanceof SculkCatalystBlock) {
                            throw new Unanswered("a game event with a sculk listener in range", "G12");
                        }
                    }
                }
            }
        }
    }

    // ---- loot: the server the loot context asks ----

    @Override
    public net.minecraft.server.MinecraftServer getServer() {
        if (server == null) {
            server = LootServer.create(this);
        }
        return server;
    }

    /**
     * The server a loot context reaches through {@code level.getServer()}:
     * the loot tables and the random sequences, from the environment.
     * Constructor-less like the level; nothing else on it is answered.
     */
    static final class LootServer extends net.minecraft.server.dedicated.DedicatedServer {
        private RandomTickLevel level;

        private LootServer() {
            super(null, null, null, null, null, null, null, null, null, null);
            throw new AssertionError("constructor must not run");
        }

        static LootServer create(RandomTickLevel level) {
            try {
                LootServer server = (LootServer) UNSAFE.allocateInstance(LootServer.class);
                server.level = level;
                return server;
            } catch (InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public ReloadableServerRegistries.Holder reloadableRegistries() {
            return level.env().lootTables();
        }

        @Override
        public RandomSource getRandomSequence(Identifier key) {
            return level.env().randomSequence(key);
        }
    }

    private static long offset(Class<?> owner, String name) {
        try {
            return UNSAFE.objectFieldOffset(owner.getDeclaredField(name));
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

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
