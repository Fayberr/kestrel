package dev.kestrel.worldgen;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Minimal ServerLevel shell used only while Vanilla structure pieces create
 * worldgen entities.
 *
 * <p>WorldGenLevel.getLevel() is typed as ServerLevel, while Kestrel's
 * headless RegionView intentionally has no live MinecraftServer. A handful of
 * structure pieces still call EntityType.create(level.getLevel(), ...).
 * Returning null aborts the remainder of the piece. This shell is allocated
 * without running ServerLevel's constructor and overrides the small read-only
 * surface needed by entity construction/finalizeSpawn, delegating spatial
 * reads to the current RegionView.
 *
 * <p>Entities are never inserted into this shell. RegionView owns
 * addFreshEntity/addFreshEntityWithPassengers and serializes those worldgen
 * entities into the target ProtoChunk exactly like WorldGenRegion.
 */
final class HeadlessEntityServerLevel extends ServerLevel {
    private static final sun.misc.Unsafe UNSAFE = unsafe();

    private WorldGenLevel delegate;
    private HeadlessNoise.Driver driver;
    private GameRules gameRules;
    private LevelData levelData;
    private int nextEntityId;
    private MinecraftServer serverShell;
    private EnvironmentAttributeSystem environmentAttributes;
    private StructureManager structureManager;
    private ServerChunkCache chunkSourceShell;

    /**
     * Never invoked. Java requires an explicit super call, but instances are
     * created with Unsafe.allocateInstance so none of ServerLevel's server,
     * chunk-source or persistence machinery is initialized.
     */
    private HeadlessEntityServerLevel() {
        super(
                null,
                Runnable::run,
                null,
                null,
                Level.OVERWORLD,
                null,
                false,
                0L,
                List.of(),
                false);
        throw new AssertionError("constructor must not run");
    }

    static HeadlessEntityServerLevel create(
            HeadlessNoise.Driver driver,
            WorldGenLevel delegate) {
        return create(driver, delegate, null);
    }

    static HeadlessEntityServerLevel create(
            HeadlessNoise.Driver driver,
            WorldGenLevel delegate,
            StructureTemplateManager templates) {
        try {
            HeadlessEntityServerLevel shell =
                    (HeadlessEntityServerLevel)
                            UNSAFE.allocateInstance(
                                    HeadlessEntityServerLevel.class);
            shell.driver = driver;
            shell.delegate = delegate;
            shell.gameRules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
            shell.serverShell = createServerShell(templates);
            shell.chunkSourceShell = createChunkSourceShell(driver);
            // Entity.<init> immediately asks the Level for a network id.
            // The real ServerLevel implementation checks ServerChunkCache for
            // collisions, but this headless shell intentionally has no chunk
            // source. Worldgen entities are captured straight into ProtoChunks,
            // so a shell-local monotonic allocator is sufficient and keeps the
            // construction path deterministic without inventing a live server.
            shell.nextEntityId = 1;
            // Entity constructors (notably Villager in 26.2) consult the
            // Level environment attribute system before template NBT is
            // loaded. Unsafe allocation bypasses ServerLevel's normal
            // initialization, so provide a real, non-null system. An empty
            // layer set intentionally falls back to each attribute's
            // Vanilla default value; structure-template entity NBT then
            // supplies the persisted entity state exactly as in Vanilla.
            shell.environmentAttributes = EnvironmentAttributeSystem.builder().build();
            shell.levelData = new LevelData() {
                @Override
                public RespawnData getRespawnData() {
                    return RespawnData.DEFAULT;
                }

                @Override
                public long getGameTime() {
                    return driver.clock().gameTime();
                }

                @Override
                public boolean isHardcore() {
                    return false;
                }

                @Override
                public Difficulty getDifficulty() {
                    return Difficulty.NORMAL;
                }

                @Override
                public boolean isDifficultyLocked() {
                    return false;
                }
            };
            return shell;
        } catch (InstantiationException e) {
            throw new IllegalStateException(
                    "allocate headless ServerLevel shell", e);
        }
    }

    /**
     * PaleMossDecorator is the one ordinary 26.2 tree decorator that reaches
     * through WorldGenLevel.getLevel().getChunkSource().getGenerator().
     *
     * <p>javap of PaleMossDecorator.place shows the ground branch (taken with
     * probability groundProbability) resolving
     * VegetationFeatures.PALE_MOSS_PATCH out of the level's CONFIGURED_FEATURE
     * registry and then calling, in lambda$place$1,
     * ConfiguredFeature.place(worldGenLevel,
     * worldGenLevel.getLevel().getChunkSource().getGenerator(), random,
     * pos.above()). The chunk source is read for nothing but that generator,
     * and it is threaded straight into FeaturePlaceContext /
     * PlacementContext; VegetationPatchFeature only forwards it again to the
     * nested pale_moss_vegetation PlacedFeature.place.
     *
     * <p>Vanilla's ServerChunkCache.getGenerator returns chunkMap.generator(),
     * that is the level's own world generator. Headless generation already
     * holds that exact object as driver.generator() and hands it to every
     * other feature in HeadlessNoise.featuresWithStructures, so returning it
     * here is what a live ServerLevel would return, not a substitute.
     *
     * <p>Allocated without running ServerChunkCache's constructor, like the
     * rest of this shell: none of the ticket, light or storage machinery is
     * reachable from this path, and only getGenerator is answered.
     */
    private static ServerChunkCache createChunkSourceShell(
            HeadlessNoise.Driver driver) {
        try {
            HeadlessChunkSource source =
                    (HeadlessChunkSource)
                            UNSAFE.allocateInstance(HeadlessChunkSource.class);
            source.driver = driver;
            return source;
        } catch (InstantiationException e) {
            throw new IllegalStateException(
                    "allocate headless ServerChunkCache shell", e);
        }
    }

    private static final class HeadlessChunkSource extends ServerChunkCache {
        private HeadlessNoise.Driver driver;

        /**
         * Never invoked; see HeadlessEntityServerLevel's own private
         * constructor. Instances come from Unsafe.allocateInstance.
         */
        private HeadlessChunkSource() {
            super(
                    null,
                    null,
                    null,
                    null,
                    Runnable::run,
                    null,
                    0,
                    0,
                    false,
                    null,
                    null);
            throw new AssertionError("constructor must not run");
        }

        @Override
        public ChunkGenerator getGenerator() {
            return driver.generator();
        }
    }

    /**
     * FossilFeature is the one ordinary 26.2 feature that reaches through
     * WorldGenLevel.getLevel().getServer().getStructureManager(). A real
     * MinecraftServer is unnecessary: allocate the concrete dedicated-server
     * subclass without running its constructor and initialize only the
     * StructureTemplateManager field read by MinecraftServer.getStructureManager().
     */
    private static MinecraftServer createServerShell(
            StructureTemplateManager templates) {
        try {
            MinecraftServer server =
                    (MinecraftServer) UNSAFE.allocateInstance(DedicatedServer.class);

            Field debugField = MinecraftServer.class.getDeclaredField("debugSubscribers");
            UNSAFE.putObject(
                    server,
                    UNSAFE.objectFieldOffset(debugField),
                    new net.minecraft.util.debug.ServerDebugSubscribers(server));

            Field structureField =
                    MinecraftServer.class.getDeclaredField("structureTemplateManager");
            UNSAFE.putObject(
                    server,
                    UNSAFE.objectFieldOffset(structureField),
                    templates);
            return server;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("allocate headless MinecraftServer shell", e);
        }
    }

    /**
     * ServerLevel.getSeed() normally reads the seed through its backing
     * MinecraftServer. This Unsafe-allocated shell intentionally has no live
     * ServerLevel server field, but the headless driver already owns the exact
     * world-generation seed used to build this level.
     */
    @Override
    public long getSeed() {
        return driver.seed();
    }

    @Override
    public MinecraftServer getServer() {
        return serverShell;
    }

    /**
     * Read only by PaleMossDecorator's ground branch, for getGenerator().
     * See createChunkSourceShell.
     */
    @Override
    public ServerChunkCache getChunkSource() {
        return chunkSourceShell;
    }

    void bindStructureManager(StructureManager manager) {
        this.structureManager = java.util.Objects.requireNonNull(
                manager, "structureManager");
    }

    @Override
    public StructureManager structureManager() {
        if (structureManager == null) {
            throw new IllegalStateException(
                    "headless ServerLevel structure manager is not bound");
        }
        return structureManager;
    }

    @Override
    public int getNextEntityId() {
        // Zero is Entity.INVALID_ENTITY_ID in 26.2. The shell never owns a live
        // entity index, so there is nothing to probe for collisions here.
        int id = nextEntityId++;
        if (id == 0) {
            id = nextEntityId++;
        }
        return id;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return FeatureFlags.DEFAULT_FLAGS;
    }

    @Override
    public RegistryAccess registryAccess() {
        return driver.access();
    }

    @Override
    public EnvironmentAttributeSystem environmentAttributes() {
        return environmentAttributes;
    }

    @Override
    public RandomSource getRandom() {
        return delegate.getRandom();
    }

    @Override
    public Difficulty getDifficulty() {
        return Difficulty.NORMAL;
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return new DifficultyInstance(
                Difficulty.NORMAL, 0L, 0L, 0.0F);
    }

    @Override
    public long getGameTime() {
        // Camel.finalizeSpawn reads this via WorldGenLevel.getLevel().
        return driver.clock().gameTime();
    }

    @Override
    public long getOverworldClockTime() {
        return 0L;
    }

    @Override
    public float getMoonBrightness(BlockPos pos) {
        return 0.0F;
    }

    @Override
    public GameRules getGameRules() {
        return gameRules;
    }

    @Override
    public LevelData getLevelData() {
        return levelData;
    }

    @Override
    public int getSeaLevel() {
        return driver.generator().getSeaLevel();
    }

    @Override
    public int getMinY() {
        return driver.height().getMinY();
    }

    @Override
    public int getHeight() {
        return driver.height().getHeight();
    }

    @Override
    public DimensionType dimensionType() {
        return delegate.dimensionType();
    }

    @Override
    public net.minecraft.resources.ResourceKey<Level> dimension() {
        return driver.dimension().level();
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return delegate.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return delegate.getFluidState(pos);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return delegate.getBlockEntity(pos);
    }

    @Override
    public ChunkAccess getChunk(
            int chunkX,
            int chunkZ,
            ChunkStatus status,
            boolean loadOrGenerate) {
        return delegate.getChunk(
                chunkX, chunkZ, status, loadOrGenerate);
    }

    private static sun.misc.Unsafe unsafe() {
        try {
            Field field = sun.misc.Unsafe.class
                    .getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (sun.misc.Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
