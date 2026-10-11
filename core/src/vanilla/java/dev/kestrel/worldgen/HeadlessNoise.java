package dev.kestrel.worldgen;

import dev.kestrel.loop.SectionStore;
import dev.kestrel.persist.BlockHash;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.storage.LevelDataAndDimensions;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * G2 step 3: headless vanilla NOISE driver (preresearch/g2-step3-design.md).
 *
 * <p>Load path mirrors the dedicated server ({@code Main#createNewWorldData}
 * + {@code Main#lambda$main$1}, verified by javap against
 * {@code preresearch/vanilla-26.2/server.jar} inner game jar): vanilla pack
 * resources from the server jar, WorldLoader pack config, normal world
 * dimensions for a fresh seed over the datapack worldgen provider, then
 * RandomState + NoiseBasedChunkGenerator from the baked dimensions access.
 * Structures are off: the StructureManager stub answers
 * shouldGenerateStructures() false with no starts.
 *
 * <p>Output lands in a Kestrel SectionStore (DENSE, same as the step-2
 * import path) so the P1 oracle gate compares through BlockHash.
 */
public final class HeadlessNoise {

    /** Overworld height: minY -64, 384 high (24 sections). */
    public static final int MIN_Y = -64;
    /** Overworld build height. */
    public static final int HEIGHT = 384;

    /** Vanilla dimension selection for the shared terrain pipeline. */
    public enum Dimension {
        OVERWORLD("minecraft:overworld"),
        NETHER("minecraft:the_nether"),
        END("minecraft:the_end");

        private final String levelId;

        Dimension(String levelId) { this.levelId = levelId; }

        public String levelId() { return levelId; }
        public ResourceKey<LevelStem> stem() {
            return switch (this) {
                case OVERWORLD -> LevelStem.OVERWORLD;
                case NETHER -> LevelStem.NETHER;
                case END -> LevelStem.END;
            };
        }
        public ResourceKey<NoiseGeneratorSettings> settings() {
            return switch (this) {
                case OVERWORLD -> NoiseGeneratorSettings.OVERWORLD;
                case NETHER -> NoiseGeneratorSettings.NETHER;
                case END -> NoiseGeneratorSettings.END;
            };
        }
        public ResourceKey<net.minecraft.world.level.Level> level() {
            return switch (this) {
                case OVERWORLD -> net.minecraft.world.level.Level.OVERWORLD;
                case NETHER -> net.minecraft.world.level.Level.NETHER;
                case END -> net.minecraft.world.level.Level.END;
            };
        }
    }

    private HeadlessNoise() {
    }

    /** Loaded driver: generator + random state + height accessor, one seed. */
    public record Driver(NoiseBasedChunkGenerator generator, RandomState randomState,
            LevelHeightAccessor height, StructureManager structures,
            net.minecraft.core.RegistryAccess access, long seed,
            WorldgenClock clock, Dimension dimension,
            Holder<DimensionType> dimensionType) {
        public Driver(NoiseBasedChunkGenerator generator, RandomState randomState,
                LevelHeightAccessor height, StructureManager structures,
                net.minecraft.core.RegistryAccess access, long seed) {
            this(generator, randomState, height, structures, access, seed,
                    new WorldgenClock(), Dimension.OVERWORLD,
                    access.lookupOrThrow(Registries.DIMENSION_TYPE)
                            .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD));
        }
    }

    /**
     * Boot vanilla registries headless and build the driver for a seed.
     * Uses the server jar's own datapack (no external data dir needed).
     */
    public static Driver load(long seed) throws ExecutionException, InterruptedException {
        return load(seed, Dimension.OVERWORLD);
    }

    /** Load the selected vanilla dimension with its own stem, noise and height. */
    public static Driver load(long seed, Dimension dimension)
            throws ExecutionException, InterruptedException {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // Vanilla's own path (javap ServerPacksSource ctor body): the source
        // builds its own VanillaPackResources internally. Hand-rolling
        // Pack.readMetaAndCreate returned null (NPE on getId in
        // PackRepository.discoverAvailable), so use the server's source.
        net.minecraft.world.level.validation.DirectoryValidator validator =
                new net.minecraft.world.level.validation.DirectoryValidator(path -> true);
        // ServerPacksSource's only ctor takes the validator; vanilla pack
        // resources come from its own factory internally (javap ctor body).
        // Our prebuilt vanillaPack is therefore unused; drop it.
        PackRepository repo = new PackRepository(new ServerPacksSource(validator));
        WorldDataConfiguration config = new WorldDataConfiguration(
                new DataPackConfig(List.of("vanilla"), List.of()),
                net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(repo, config, false, false);
        WorldLoader.InitConfig init = new WorldLoader.InitConfig(packConfig,
                Commands.CommandSelection.DEDICATED, PermissionSet.NO_PERMISSIONS);

        // Supplier mirrors Main#createNewWorldData: normal dimensions for a
        // fresh seed over the datapack worldgen provider, baked to a
        // Complete whose dimensionsRegistryAccess is the oracle access.
        WorldLoader.WorldDataSupplier<WorldGenSettings> supplier =
                (WorldLoader.DataLoadContext ctx) -> {
                    HolderLookup.Provider worldgen = ctx.datapackWorldgen();
                    WorldDimensions dims = WorldPresets.createNormalWorldDimensions(worldgen);
                    WorldGenSettings genSettings =
                            new WorldGenSettings(new WorldOptions(seed, false, false), dims);
                    Registry<LevelStem> stems = ctx.datapackDimensions()
                            .lookupOrThrow(Registries.LEVEL_STEM);
                    WorldDimensions.Complete complete = dims.bake(stems);
                    return new WorldLoader.DataLoadOutput<>(genSettings,
                            complete.dimensionsRegistryAccess());
                };

        // Factory: overworld LevelStem generator from the baked access is
        // already a NoiseBasedChunkGenerator; RandomState.create over the
        // composite provider with the same seed the dimensions were made for.
        WorldLoader.ResultFactory<WorldGenSettings, Driver> factory =
                (manager, dataPackResources, layeredRegistries, genSettings) -> {
                    @SuppressWarnings("unchecked")
                    HolderLookup.Provider provider =
                            (HolderLookup.Provider) (Object) layeredRegistries
                                    .compositeAccess();
                    RandomState randomState =
                            RandomState.create(provider, dimension.settings(), seed);
                    LevelStem stem = layeredRegistries.compositeAccess()
                            .lookupOrThrow(Registries.LEVEL_STEM)
                            .getOrThrow(dimension.stem()).value();
                    NoiseBasedChunkGenerator generator =
                            (NoiseBasedChunkGenerator) stem.generator();
                    BiomeSource biomes = generator.getBiomeSource();
                    NoiseBasedChunkGenerator owned =
                            new NoiseBasedChunkGenerator(biomes, generator.generatorSettings());
                    DimensionType dimensionType = stem.type().value();
                    LevelHeightAccessor height = LevelHeightAccessor.create(
                            dimensionType.minY(), dimensionType.height());
                    return new Driver(owned, randomState, height,
                            structuresOff(layeredRegistries.compositeAccess(), dimension, height),
                            layeredRegistries.compositeAccess(), seed, new WorldgenClock(),
                            dimension, stem.type());
                };

        return WorldLoader.load(init, supplier, factory,
                Runnable::run, Runnable::run).get();
    }

    /** StructureManager stub: structures off, no starts, never a region. */
    private static StructureManager structuresOff(
            net.minecraft.core.RegistryAccess access, Dimension dimension,
            LevelHeightAccessor height) {
        // Beardifier.forStructuresInChunk calls
        // manager.startsForStructure(pos, pred), which does
        // level.getChunk(x, z, STRUCTURE_REFERENCES).getAllReferences().
        // Answer an empty-references chunk instead of null (verified live:
        // NPE in startsForStructure:50 otherwise). Empty map means the
        // Beardifier is EMPTY, exact for structures-off fresh chunks.
        // Empty-refs chunk factory comes from the live access
        // (PalettedContainerFactory.create mirrors the javap body: block
        // strategy + AIR, biome strategy + PLAINS). Non-null or
        // replaceMissingSections NPEs (verified live, LevelChunkSection:41).
        PalettedContainerFactory stubFactory = PalettedContainerFactory.create(access);
        net.minecraft.world.level.chunk.ChunkAccess emptyRefs =
                new net.minecraft.world.level.chunk.ProtoChunk(
                        new ChunkPos(0, 0), UpgradeData.EMPTY,
                        height,
                        stubFactory, null);
        LevelAccessor fake = (LevelAccessor) java.lang.reflect.Proxy.newProxyInstance(
                HeadlessNoise.class.getClassLoader(),
                new Class<?>[]{LevelAccessor.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getChunk")) {
                        return emptyRefs;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class || rt == long.class) {
                        return 0;
                    }
                    return null;
                });
        WorldOptions options = new WorldOptions(0L, false, false);
        // Structures are off: the check never runs (shouldGenerateStructures
        // is false on the stub below). StructureCheck's real 10-arg ctor
        // needs a storage access for its template manager; pass a temp dir
        // scope instead of nulls (NPE on getLevelPath otherwise, verified
        // live: StructureTemplateManager ctor reads GENERATED_DIR).
        net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess storage;
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory(
                    "kestrel-headless");
            storage = net.minecraft.world.level.storage.LevelStorageSource
                    .createDefault(tmp).validateAndCreateAccess("headless");
        } catch (Exception e) {
            throw new IllegalStateException("headless storage", e);
        }
        StructureCheck check = new StructureCheck(
                (a, b) -> null,
                net.minecraft.core.RegistryAccess.EMPTY,
                new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager(
                        new net.minecraft.server.packs.resources.FallbackResourceManager(
                                net.minecraft.server.packs.PackType.SERVER_DATA, "headless"),
                        storage, null, net.minecraft.core.registries.BuiltInRegistries.BLOCK),
                dimension.level(),
                null, null, null, null, 0L, null);
        return new StructureManager(fake, options, check);
    }

    /**
     * G2 step 8: everything a real (non-stub) STRUCTURE_STARTS placement
     * needs, headless (preresearch/g2-step8-design.md). A second
     * {@link WorldLoader#load} pass, structures ON: same boot path as
     * {@link #load}, but the factory keeps the real {@code manager}
     * (structure NBT templates live under {@code data/minecraft/structures/}
     * in that resource manager, javap {@code StructureTemplateManager}'s
     * only ctor) instead of dropping it, and builds a real
     * {@link net.minecraft.world.level.chunk.ChunkGeneratorStructureState}
     * (javap {@code ChunkGenerator.createState}) plus a real
     * {@link StructureManager} ({@code WorldOptions.generateStructures()}
     * true) instead of {@link #structuresOff}'s empty stub. Close it when
     * done: the resource manager backs lazy template loads for as long as
     * {@link #structures} may still be called.
     */
    public record Structural(Driver driver, StructureManager structures,
            net.minecraft.world.level.chunk.ChunkGeneratorStructureState structureState,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates,
            net.minecraft.world.level.levelgen.structure.StructureCheck structureCheck,
            WorldOptions options,
            net.minecraft.server.packs.resources.CloseableResourceManager resources)
            implements AutoCloseable {
        @Override
        public void close() {
            resources.close();
        }
    }

    /**
     * Boot vanilla registries headless (structures ON) and build a
     * {@link Structural} for one seed. Mirrors {@link #load} exactly up to
     * the factory; see that method's javadoc for the shared boot-path
     * citation.
     */
    public static Structural loadStructural(long seed) throws ExecutionException, InterruptedException {
        return loadStructural(seed, Dimension.OVERWORLD, true);
    }

    public static Structural loadStructural(long seed, Dimension dimension)
            throws ExecutionException, InterruptedException {
        return loadStructural(seed, dimension, true);
    }

    /**
     * Boot vanilla registries headless and build a {@link Structural} for one seed,
     * with optional structure generation.
     */
    public static Structural loadStructural(long seed, boolean generateStructures)
            throws ExecutionException, InterruptedException {
        return loadStructural(seed, Dimension.OVERWORLD, generateStructures);
    }

    public static Structural loadStructural(long seed, Dimension dimension, boolean generateStructures)
            throws ExecutionException, InterruptedException {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        net.minecraft.world.level.validation.DirectoryValidator validator =
                new net.minecraft.world.level.validation.DirectoryValidator(path -> true);
        PackRepository repo = new PackRepository(new ServerPacksSource(validator));
        WorldDataConfiguration config = new WorldDataConfiguration(
                new DataPackConfig(List.of("vanilla"), List.of()),
                net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(repo, config, false, false);
        WorldLoader.InitConfig init = new WorldLoader.InitConfig(packConfig,
                Commands.CommandSelection.DEDICATED, PermissionSet.NO_PERMISSIONS);

        WorldLoader.WorldDataSupplier<WorldGenSettings> supplier =
                (WorldLoader.DataLoadContext ctx) -> {
                    HolderLookup.Provider worldgen = ctx.datapackWorldgen();
                    WorldDimensions dims = WorldPresets.createNormalWorldDimensions(worldgen);
                    WorldGenSettings genSettings =
                            new WorldGenSettings(new WorldOptions(seed, generateStructures, false), dims);
                    Registry<LevelStem> stems = ctx.datapackDimensions()
                            .lookupOrThrow(Registries.LEVEL_STEM);
                    WorldDimensions.Complete complete = dims.bake(stems);
                    return new WorldLoader.DataLoadOutput<>(genSettings,
                            complete.dimensionsRegistryAccess());
                };

        WorldLoader.ResultFactory<WorldGenSettings, Structural> factory =
                (manager, dataPackResources, layeredRegistries, genSettings) -> {
                    @SuppressWarnings("unchecked")
                    HolderLookup.Provider provider =
                            (HolderLookup.Provider) (Object) layeredRegistries
                                    .compositeAccess();
                    RandomState randomState =
                            RandomState.create(provider, dimension.settings(), seed);
                    net.minecraft.core.RegistryAccess access = layeredRegistries.compositeAccess();
                    LevelStem stem = access.lookupOrThrow(Registries.LEVEL_STEM)
                            .getOrThrow(dimension.stem()).value();
                    NoiseBasedChunkGenerator generator =
                            (NoiseBasedChunkGenerator) stem.generator();
                    BiomeSource biomes = generator.getBiomeSource();
                    NoiseBasedChunkGenerator owned =
                            new NoiseBasedChunkGenerator(biomes, generator.generatorSettings());
                    DimensionType dimensionType = stem.type().value();
                    LevelHeightAccessor height = LevelHeightAccessor.create(
                            dimensionType.minY(), dimensionType.height());
                    // structuresOff() driver stub travels along unused: Driver.structures()
                    // itself is never read by structures(), only driver.generator()/
                    // .randomState()/.access()/.height()/.seed() are (kept for shape
                    // parity with load()'s Driver, so a caller holding a Structural can
                    // still call noise()/surface()/carve()/features() on driver()).
                    Driver driver = new Driver(owned, randomState, height,
                            structuresOff(access, dimension, height), access, seed,
                            new WorldgenClock(), dimension, stem.type());

                    // Real StructureGeneratorState: createState(HolderLookup<StructureSet>,
                    // RandomState, seed) -> ChunkGeneratorStructureState.createForNormal
                    // (javap ChunkGenerator.createState body).
                    net.minecraft.core.HolderLookup<net.minecraft.world.level.levelgen.structure.StructureSet>
                            structureSets = access.lookupOrThrow(Registries.STRUCTURE_SET);
                    net.minecraft.world.level.chunk.ChunkGeneratorStructureState structureState =
                            owned.createState(structureSets, randomState, seed);

                    // Real StructureTemplateManager: the live `manager` (not a
                    // FallbackResourceManager) so data/minecraft/structures/*.nbt
                    // resolve for jigsaw/template pieces. LevelStorageAccess is a
                    // throwaway temp dir (ctor reads GENERATED_DIR; NPE otherwise,
                    // same gotcha as structuresOff()).
                    net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess storage;
                    try {
                        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory(
                                "kestrel-headless-structures");
                        storage = net.minecraft.world.level.storage.LevelStorageSource
                                .createDefault(tmp).validateAndCreateAccess("headless-structures");
                    } catch (Exception e) {
                        throw new IllegalStateException("headless structural storage", e);
                    }
                    net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates =
                            new ConcurrentTemplates(
                                    manager, storage, net.minecraft.util.datafix.DataFixers.getDataFixer(),
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK);

                    // StructureManager.level/.structureCheck are never read on the
                    // STARTS path: getStartForStructure/setStartForStructure/
                    // fetchReferences all go through the StructureAccess (=ChunkAccess)
                    // argument, never this.level or this.structureCheck (javap
                    // StructureManager, confirmed against ChunkGenerator's
                    // lambda$createStructures$0 and tryGenerateStructure bodies: no
                    // call reaches StructureManager.level or .structureCheck). The
                    // permissive proxy and throwaway StructureCheck below are wired
                    // with real values anyway (cheap, and correct if that ever
                    // changes), but WorldOptions.generateStructures()=true is the
                    // only field this step's code path actually reads.
                    LevelAccessor fake = (LevelAccessor) java.lang.reflect.Proxy.newProxyInstance(
                            HeadlessNoise.class.getClassLoader(),
                            new Class<?>[]{LevelAccessor.class},
                            (proxy, method, args) -> {
                                Class<?> rt = method.getReturnType();
                                if (rt == boolean.class) {
                                    return false;
                                }
                                if (rt == int.class || rt == long.class) {
                                    return 0;
                                }
                                return null;
                            });
                    net.minecraft.world.level.levelgen.structure.StructureCheck check =
                            new net.minecraft.world.level.levelgen.structure.StructureCheck(
                                    (a, b) -> null, access, templates,
                                    dimension.level(), owned, randomState,
                                    height, biomes, seed,
                                    net.minecraft.util.datafix.DataFixers.getDataFixer());
                    WorldOptions structureOptions = new WorldOptions(seed, true, false);
                    StructureManager structures = new StructureManager(
                            fake, structureOptions, check);

                    return new Structural(
                            driver, structures, structureState, templates,
                            check, structureOptions, manager);
                };

        return WorldLoader.load(init, supplier, factory, Runnable::run, Runnable::run).get();
    }

    /**
     * G2 step 8: vanilla STRUCTURE_REFERENCES for one center chunk.
     *
     * <p>Builds the exact radius-8 STARTS dependency window required by the
     * generation pyramid, binds a real structures-on manager to RegionView,
     * and delegates the reference scan to ChunkGenerator.createReferences.
     * The returned center chunk owns both its starts and generated reference
     * sets; no structure blocks are placed here.
     */
    private record StructureWindow(
            java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> chunks,
            net.minecraft.world.level.chunk.ChunkAccess center,
            RegionView view,
            StructureManager manager) {
    }

    private static StructureWindow structureWindow(
            Structural structural, int chunkX, int chunkZ) {
        return structureWindow(
                structural, chunkX, chunkZ, new java.util.HashMap<>());
    }

    /**
     * Cached variant used by multi-chunk structures-on generation. STARTS is
     * a pure per-chunk stage, so one bare start chunk can safely back every
     * radius-8 reference scan that reaches it. Centers are then advanced in
     * place through REFERENCES/BIOMES/NOISE exactly once by the caller.
     */
    private static StructureWindow structureWindow(
            Structural structural,
            int chunkX,
            int chunkZ,
            java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> starts) {
        return structureWindow(structural, chunkX, chunkZ, starts, null);
    }

    /**
     * Where a structure window gets a STARTS chunk it does not hold yet.
     * Every answer must equal {@code structures(structural, x, z)}: a fresh
     * chunk whose starts came from createStructures alone. LiveGenerator
     * answers with a result computed ahead of time on its STARTS thread.
     */
    @FunctionalInterface
    interface StartsSource {
        net.minecraft.world.level.chunk.ChunkAccess starts(int chunkX, int chunkZ);
    }

    private static StructureWindow structureWindow(
            Structural structural,
            int chunkX,
            int chunkZ,
            java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> starts,
            StartsSource source) {
        Driver driver = structural.driver();

        // Match ChunkGenerator.createReferences' exact dependency traversal:
        // X is the outer loop, Z the inner loop (javap 26.2). STARTS should
        // be pure per chunk, but preserving the scheduler-visible creation
        // order also keeps any lazy structure-state/template caches aligned.
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                int x = chunkX + dx;
                int z = chunkZ + dz;
                long key = ChunkPos.pack(x, z);
                starts.computeIfAbsent(
                        key,
                        ignored -> source == null
                                ? structures(structural, x, z)
                                : source.starts(x, z));
            }
        }

        ChunkPos centerPos = new ChunkPos(chunkX, chunkZ);
        net.minecraft.world.level.chunk.ChunkAccess center =
                starts.get(centerPos.pack());
        RegionView view = new RegionView(driver, centerPos, starts);
        StructureManager manager = new StructureManager(
                view.level(), structural.options(), structural.structureCheck());

        driver.generator().createReferences(view.level(), manager, center);

        // Do not canonicalize/trim the reference sets here. Vanilla FEATURES
        // can observe different LongOpenHashSet table layouts for identical
        // memberships depending on the chunk's save/reload lifecycle. The
        // captured seed-4242424242 pair {0,6;3,7}, for example, iterates
        // 0,6 -> 3,7 at target 0,3 but 3,7 -> 0,6 at several neighbouring
        // targets. A global trim therefore fixes one call site by corrupting
        // others. Exact replay must model the lifecycle that caused a rehash,
        // not invent one uniform reference ordering.
        if (center instanceof ProtoChunk proto) {
            proto.setPersistedStatus(
                    net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_REFERENCES);
        }
        return new StructureWindow(starts, center, view, manager);
    }

    public static net.minecraft.world.level.chunk.ChunkAccess structureReferences(
            Structural structural, int chunkX, int chunkZ) {
        return structureWindow(structural, chunkX, chunkZ).center();
    }

    /**
     * Rebuild structure-reference sets exactly as Vanilla's chunk
     * serialization round-trip does: pack each non-empty LongSet through
     * toLongArray(), then unpack it into a size-derived LongOpenHashSet.
     *
     * <p>This is lifecycle, not canonical ordering. Fastutil iteration depends
     * on the backing-table layout, so this operation can change iteration
     * order while preserving reference membership. Do not replace it with
     * sorting or {@code trim()}.
     */
    static void roundTripStructureReferences(
            net.minecraft.world.level.chunk.ChunkAccess chunk) {
        java.util.Map<
                net.minecraft.world.level.levelgen.structure.Structure,
                it.unimi.dsi.fastutil.longs.LongSet> reloaded =
                new java.util.HashMap<>();
        ChunkPos pos = chunk.getPos();
        for (var entry : chunk.getAllReferences().entrySet()) {
            it.unimi.dsi.fastutil.longs.LongSet refs = entry.getValue();
            if (refs.isEmpty()) {
                continue; // packStructureData omits empty reference arrays.
            }
            long[] serialized = refs.toLongArray();
            long[] valid = java.util.Arrays.stream(serialized)
                    .filter(packed -> {
                        int x = ChunkPos.getX(packed);
                        int z = ChunkPos.getZ(packed);
                        return Math.max(
                                Math.abs(x - pos.x()),
                                Math.abs(z - pos.z())) <= 8;
                    })
                    .toArray();
            reloaded.put(
                    entry.getKey(),
                    new it.unimi.dsi.fastutil.longs.LongOpenHashSet(valid));
        }
        chunk.setAllReferences(reloaded);
    }

    /**
     * Rebuild structure starts exactly as Vanilla's chunk serialization round
     * trip does: write each start with {@code StructureStart.createTag} and
     * read it back with {@code StructureStart.loadStaticStart}.
     *
     * <p>This is the start half of the same partial-chunk reload whose
     * reference half is {@link #roundTripStructureReferences}. Vanilla's
     * {@code SerializableChunkData.packStructureData} writes the starts with
     * {@code createTag(ctx, savedChunkPos)} (javap offsets 103-108) and
     * {@code unpackStructureStart} restores them with {@code loadStaticStart},
     * keeping every non-null result in a fresh HashMap (javap offsets
     * 104-134). A reload therefore replaces the start objects; it does not
     * merely rehash their reference tables.
     *
     * <p>The replacement is not the identity. {@code loadStaticStart} rebuilds
     * the PiecesContainer through {@code PiecesContainer.load}, and the
     * deserialization constructor of {@code PoolElementStructurePiece}
     * discards the persisted {@code BB} and recomputes the piece box from
     * {@code element.getBoundingBox(templates, position, rotation)} (javap
     * offsets 123-143). Jigsaw pieces that were placed with a taller live box
     * than their element reproduces therefore come back shorter, and
     * {@code StructureStart.getBoundingBox} recomputes its cache from them.
     * {@link StructureStartRoundTripCheck} pins this on the w00 witness: the
     * JigsawStructure start in chunk -30,3 of seed 7272240032024010793 goes
     * from box maxY=90 to maxY=84 across one round trip, which is exactly
     * what that trace records across the reload at afterEventSeq=5232.
     *
     * <p>Modelling only the reference half leaves Kestrel holding a
     * pre-reload start, which keeps placing pieces Vanilla no longer has.
     */
    static void roundTripStructureStarts(
            Structural structural,
            net.minecraft.world.level.chunk.ChunkAccess chunk) {
        java.util.Map<
                net.minecraft.world.level.levelgen.structure.Structure,
                net.minecraft.world.level.levelgen.structure.StructureStart> starts =
                chunk.getAllStarts();
        if (starts.isEmpty()) {
            return;
        }
        net.minecraft.world.level.levelgen.structure.pieces
                .StructurePieceSerializationContext ctx =
                new net.minecraft.world.level.levelgen.structure.pieces
                        .StructurePieceSerializationContext(
                        structural.resources(),
                        structural.driver().access(),
                        structural.templates());
        ChunkPos pos = chunk.getPos();
        long seed = structural.driver().seed();
        java.util.Map<
                net.minecraft.world.level.levelgen.structure.Structure,
                net.minecraft.world.level.levelgen.structure.StructureStart> reloaded =
                new java.util.HashMap<>();
        for (var entry : starts.entrySet()) {
            net.minecraft.world.level.levelgen.structure.StructureStart loaded =
                    net.minecraft.world.level.levelgen.structure.StructureStart
                            .loadStaticStart(
                                    ctx,
                                    entry.getValue().createTag(ctx, pos),
                                    seed);
            if (loaded != null) {
                reloaded.put(entry.getKey(), loaded);
            }
        }
        chunk.setAllStarts(reloaded);
    }

    /**
     * Both halves of one Vanilla partial-chunk structure reload. A reload
     * rebuilds the starts and the reference tables together; modelling only
     * one of them is what let a pre-reload jigsaw start survive into later
     * FEATURES passes.
     */
    static void roundTripStructureData(
            Structural structural,
            net.minecraft.world.level.chunk.ChunkAccess chunk) {
        roundTripStructureStarts(structural, chunk);
        roundTripStructureReferences(chunk);
    }

    /**
     * Mirror the block-entity half of a serialized ProtoChunk reload. Pack
     * writes both real and pending block entities as tags; read puts every tag
     * into the new ProtoChunk's pending map. A real chest created by an earlier
     * FEATURES pass therefore becomes pending again across a captured reload.
     */
    static void roundTripBlockEntities(
            Structural structural,
            net.minecraft.world.level.chunk.ChunkAccess chunk) {
        if (!(chunk instanceof ProtoChunk proto)
                || proto.getBlockEntitiesPos().isEmpty()) {
            return;
        }
        java.util.List<BlockPos> positions =
                new java.util.ArrayList<>(proto.getBlockEntitiesPos());
        java.util.List<CompoundTag> saved = new java.util.ArrayList<>();
        for (BlockPos pos : positions) {
            CompoundTag tag = proto.getBlockEntityNbtForSaving(
                    pos, structural.driver().access());
            if (tag != null) {
                saved.add(tag.copy());
            }
        }
        // getBlockEntityNbts() is an unmodifiable view; ProtoChunk's
        // removeBlockEntity drops a position from both the real and the
        // pending map and has no other side effect.
        for (BlockPos pos : positions) {
            proto.removeBlockEntity(pos);
        }
        for (CompoundTag tag : saved) {
            proto.setBlockEntityNbt(tag);
        }
    }

    private static java.lang.reflect.Field chunkHeightmapsField;

    /**
     * The heightmap half of a Vanilla 26.2 partial-chunk save/reload.
     *
     * <p>{@code SerializableChunkData.copyOf} persists only the maps in
     * {@code getPersistedStatus().heightmapsAfter()}. {@code read} builds a
     * fresh chunk, restores exactly those types and eagerly primes any of
     * them that were not saved (javap offsets 509-599). Every other map is
     * gone. From CARVERS on (ChunkStatus static init registers CARVERS with
     * FINAL_HEIGHTMAPS), heightmapsAfter() is the final type set, so a reload
     * drops OCEAN_FLOOR_WG / WORLD_SURFACE_WG; a later WG read re-primes them
     * lazily in {@code ChunkAccess.getHeight} from the current blocks.
     * Without this, a WG map that went stale (ProtoChunk.setBlockState only
     * maintains heightmapsAfter() types) survives a reload that Vanilla
     * performed, and WG-heightmap placements read an old surface.
     *
     * <p>Restoring a persisted map from its own raw data is the identity, so
     * the in-place form below is equivalent to the fresh-object round trip.
     * Kestrel's own status decides the kept set; no captured value is used.
     */
    static void roundTripHeightmaps(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        java.util.EnumSet<net.minecraft.world.level.levelgen.Heightmap.Types> persisted =
                chunk.getPersistedStatus().heightmapsAfter();
        java.util.Map<net.minecraft.world.level.levelgen.Heightmap.Types,
                net.minecraft.world.level.levelgen.Heightmap> maps;
        try {
            if (chunkHeightmapsField == null) {
                java.lang.reflect.Field field =
                        net.minecraft.world.level.chunk.ChunkAccess.class
                                .getDeclaredField("heightmaps");
                field.setAccessible(true);
                chunkHeightmapsField = field;
            }
            @SuppressWarnings("unchecked")
            var raw = (java.util.Map<net.minecraft.world.level.levelgen.Heightmap.Types,
                    net.minecraft.world.level.levelgen.Heightmap>)
                    chunkHeightmapsField.get(chunk);
            maps = raw;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ChunkAccess.heightmaps is not accessible", e);
        }
        maps.keySet().retainAll(persisted);
        java.util.EnumSet<net.minecraft.world.level.levelgen.Heightmap.Types> missing =
                java.util.EnumSet.noneOf(net.minecraft.world.level.levelgen.Heightmap.Types.class);
        for (var type : persisted) {
            if (!maps.containsKey(type)) {
                missing.add(type);
            }
        }
        if (!missing.isEmpty()) {
            net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk, missing);
        }
    }

    /**
     * Structures-on BIOMES + NOISE. The center carries real starts and
     * references, and the StructureManager is backed by the same radius-8
     * STARTS window, so NoiseBasedChunkGenerator constructs the exact Vanilla
     * Beardifier through Beardifier.forStructuresInChunk(...).
     */
    public static net.minecraft.world.level.chunk.ChunkAccess noiseWithStructures(
            Structural structural, int chunkX, int chunkZ) {
        return noiseWithStructures(
                structural, chunkX, chunkZ, new java.util.HashMap<>());
    }

    static net.minecraft.world.level.chunk.ChunkAccess noiseWithStructures(
            Structural structural,
            int chunkX,
            int chunkZ,
            java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> startsCache) {
        return noiseWithStructures(
                structural, chunkX, chunkZ, startsCache, null);
    }

    /**
     * Exact-lifecycle variant with a hook at the real
     * STRUCTURE_REFERENCES -> BIOMES boundary. Production passes no hook.
     * Replay gates use it to model a captured partial-chunk deserialize
     * before BIOMES/NOISE, so Beardifier and every downstream consumer see
     * the same reference-table layout rather than a post-NOISE approximation.
     */
    static net.minecraft.world.level.chunk.ChunkAccess noiseWithStructures(
            Structural structural,
            int chunkX,
            int chunkZ,
            java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> startsCache,
            java.util.function.Consumer<
                    net.minecraft.world.level.chunk.ChunkAccess> afterReferences) {
        return noiseWithStructures(structural, chunkX, chunkZ, startsCache, afterReferences, null);
    }

    static net.minecraft.world.level.chunk.ChunkAccess noiseWithStructures(
            Structural structural,
            int chunkX,
            int chunkZ,
            java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> startsCache,
            java.util.function.Consumer<
                    net.minecraft.world.level.chunk.ChunkAccess> afterReferences,
            StartsSource source) {
        StructureWindow window =
                structureWindow(structural, chunkX, chunkZ, startsCache, source);
        if (afterReferences != null) {
            afterReferences.accept(window.center());
        }
        Driver driver = structural.driver();
        Blender blender = Blender.empty();

        net.minecraft.world.level.chunk.ChunkAccess withBiomes =
                driver.generator()
                        .createBiomes(
                                driver.randomState(),
                                blender,
                                window.manager(),
                                window.center())
                        .join();

        net.minecraft.world.level.chunk.ChunkAccess filled =
                driver.generator()
                        .fillFromNoise(
                                blender,
                                driver.randomState(),
                                window.manager(),
                                withBiomes)
                        .join();

        if (filled instanceof ProtoChunk proto) {
            proto.setPersistedStatus(
                    net.minecraft.world.level.chunk.status.ChunkStatus.NOISE);
        }
        return filled;
    }

    /**
     * A structures-on chunk after STRUCTURE_STARTS/REFERENCES, ready for the
     * chunk-local BIOMES + NOISE of {@link #fillPrepared}. {@code manager}
     * reads a private copy of the radius-8 STARTS window, so the fill can run
     * on another thread while the owner keeps extending the shared starts map.
     */
    record PreparedNoise(
            net.minecraft.world.level.chunk.ChunkAccess center,
            StructureManager manager) {
    }

    /**
     * The serial half of {@link #noiseWithStructures}: STARTS for the radius-8
     * window (into the shared {@code startsCache}), REFERENCES for the center,
     * then the optional reference hook. Must run on the thread that owns
     * {@code startsCache}.
     *
     * <p>The returned manager backs BIOMES/NOISE with a copy of exactly the
     * 17x17 window. NOISE reads structures only through
     * StructureManager.startsForStructure (the Beardifier): the center's
     * references, then each referenced chunk's start, and a reference is
     * only ever created for a start within radius 8, so the copy answers every
     * read the live map would. The chunk objects are shared, not copied: STARTS
     * data is never written again after this point outside gate-only reload
     * replays, which refuse a prefetched chunk.
     */
    static PreparedNoise prepareStructural(
            Structural structural,
            int chunkX,
            int chunkZ,
            java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> startsCache,
            java.util.function.Consumer<
                    net.minecraft.world.level.chunk.ChunkAccess> afterReferences,
            StartsSource source) {
        StructureWindow window =
                structureWindow(structural, chunkX, chunkZ, startsCache, source);
        if (afterReferences != null) {
            afterReferences.accept(window.center());
        }
        java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> copy =
                new java.util.HashMap<>(17 * 17 * 2);
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                long key = ChunkPos.pack(chunkX + dx, chunkZ + dz);
                copy.put(key, startsCache.get(key));
            }
        }
        RegionView view = new RegionView(
                structural.driver(), new ChunkPos(chunkX, chunkZ), copy);
        StructureManager manager = new StructureManager(
                view.level(), structural.options(), structural.structureCheck());
        return new PreparedNoise(window.center(), manager);
    }

    /**
     * The chunk-local half of {@link #noiseWithStructures}: BIOMES + NOISE on
     * a {@link #prepareStructural} result. Touches only the prepared center and
     * thread-safe Vanilla generator state (the same work Vanilla runs on its
     * background worker pool), so distinct chunks may run concurrently.
     */
    static net.minecraft.world.level.chunk.ChunkAccess fillPrepared(
            Structural structural, PreparedNoise prepared) {
        return fillPrepared(structural, prepared, null);
    }

    /**
     * As above. {@code biomeDonor}, when non-null, is a chunk at the same
     * position whose BIOMES already ran (a biomes-only margin chunk, see
     * {@link #biomesOnly}); BIOMES then reads each quart's biome from it
     * instead of sampling the climate again (see {@link #createBiomesFrom}).
     */
    static net.minecraft.world.level.chunk.ChunkAccess fillPrepared(
            Structural structural, PreparedNoise prepared,
            net.minecraft.world.level.chunk.ChunkAccess biomeDonor) {
        Driver driver = structural.driver();
        Blender blender = Blender.empty();
        net.minecraft.world.level.chunk.ChunkAccess withBiomes;
        if (biomeDonor != null && createBiomesFrom(
                driver, blender, prepared.manager(), prepared.center(), biomeDonor)) {
            withBiomes = prepared.center();
        } else {
            withBiomes = driver.generator()
                    .createBiomes(driver.randomState(), blender,
                            prepared.manager(), prepared.center())
                    .join();
        }
        net.minecraft.world.level.chunk.ChunkAccess filled =
                driver.generator()
                        .fillFromNoise(blender, driver.randomState(),
                                prepared.manager(), withBiomes)
                        .join();
        if (filled instanceof ProtoChunk proto) {
            proto.setPersistedStatus(
                    net.minecraft.world.level.chunk.status.ChunkStatus.NOISE);
        }
        return filled;
    }

    /**
     * G2 step 8: vanilla structure-start placement (STRUCTURE_STARTS,
     * {@code ChunkGenerator.createStructures}, javap-cited
     * preresearch/chunkgen-path-analysis.md §1.1) into one bare chunk, no
     * server level. Requires nothing upstream (trap doc: "Requires:
     * nothing (only parent EMPTY)"), so unlike {@link #noise} the chunk
     * here is never filled: STARTS writes only {@code chunk.structureStarts},
     * no block states. Fresh {@link ProtoChunk} each call, independent of
     * any {@link #noise}/{@link #surfaceVanilla}/{@link #carveVanilla}
     * result for the same coordinates.
     *
     * @return the chunk, with any valid starts in {@code chunk.getAllStarts()}
     */
    /**
     * A LevelChunkSection that remembers whether its block states changed
     * since {@link #writeStoreCompact} last published it to the generator's
     * exclusive store. Only LevelChunkSection itself writes its block
     * container (javap: the setBlockState overloads and read), and the
     * four-argument setBlockState delegates to the five-argument one, so
     * these two overrides see every change. A write that stores the state
     * already there changes nothing a reader can see and leaves the flag
     * alone.
     */
    static final class TrackedSection extends LevelChunkSection {
        boolean dirty = true;

        TrackedSection(net.minecraft.world.level.chunk.PalettedContainerFactory factory) {
            super(factory);
        }

        @Override
        public BlockState setBlockState(int x, int y, int z, BlockState state, boolean useLocks) {
            BlockState old = super.setBlockState(x, y, z, state, useLocks);
            if (old != state) {
                dirty = true;
            }
            return old;
        }

        @Override
        public void read(net.minecraft.network.FriendlyByteBuf buf) {
            super.read(buf);
            dirty = true;
        }
    }

    /**
     * new ProtoChunk(pos, EMPTY, height, factory, null), with the sections
     * ChunkAccess.replaceMissingSections would create (same constructor,
     * same factory) as {@link TrackedSection}s.
     */
    static ProtoChunk newTrackedProtoChunk(Driver driver, int chunkX, int chunkZ) {
        var factory = factory(driver.access());
        LevelChunkSection[] sections = new LevelChunkSection[driver.height().getSectionsCount()];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new TrackedSection(factory);
        }
        return new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, sections,
                new net.minecraft.world.ticks.ProtoChunkTicks<>(),
                new net.minecraft.world.ticks.ProtoChunkTicks<>(),
                driver.height(), factory, null);
    }

    /**
     * Held for every createStructures call in this JVM, so two STARTS never
     * run at once, as in Vanilla, where one level's STARTS all run on one
     * consecutive executor. STARTS code keeps generation state in statics
     * (StrongholdPieces.currentPieces, imposedPiece, totalWeight, and the
     * PieceWeight counters) and lazily fills
     * ChunkGeneratorStructureState.ringPositions, neither of which is safe to
     * share between two concurrent STARTS.
     */
    private static final Object STARTS_LOCK = new Object();

    public static net.minecraft.world.level.chunk.ChunkAccess structures(
            Structural structural, int chunkX, int chunkZ) {
        Driver driver = structural.driver();
        ProtoChunk proto = newTrackedProtoChunk(driver, chunkX, chunkZ);
        synchronized (STARTS_LOCK) {
            driver.generator().createStructures(driver.access(), structural.structureState(),
                    structural.structures(), proto, structural.templates(),
                    driver.dimension().level());
        }
        return proto;
    }

    /**
     * The real StructureTemplateManager, except that every template it hands
     * out has thread-safe palette caches. {@code StructureTemplate.Palette}
     * memoizes {@code blocks(Block)} in a plain HashMap (javap: Maps.newHashMap
     * plus computeIfAbsent), and STARTS on the STARTS thread can use a template
     * while FEATURES on the owner thread uses the same one. The cached lists
     * are the same either way (a filter of the palette's immutable block list,
     * and callers copy them), so a ConcurrentHashMap changes no result.
     * {@code getOrCreate} reaches the repository only through {@code get}
     * (javap), so no caller gets a template before it has been converted.
     * {@code cachedJigsaws} is a benign race: every writer stores an equal
     * immutable list.
     */
    static final class ConcurrentTemplates
            extends net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager {
        private static final sun.misc.Unsafe UNSAFE;
        private static final long PALETTES;
        private static final long CACHE;

        static {
            try {
                java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                UNSAFE = (sun.misc.Unsafe) f.get(null);
                PALETTES = UNSAFE.objectFieldOffset(
                        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.class
                                .getDeclaredField("palettes"));
                CACHE = UNSAFE.objectFieldOffset(
                        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.Palette.class
                                .getDeclaredField("cache"));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final java.util.Set<
                net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate> converted =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        ConcurrentTemplates(net.minecraft.server.packs.resources.ResourceManager resources,
                net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess storage,
                com.mojang.datafixers.DataFixer fixer,
                net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> blocks) {
            super(resources, storage, fixer, blocks);
        }

        @Override
        public java.util.Optional<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate> get(
                Identifier id) {
            var template = super.get(id);
            template.ifPresent(this::convert);
            return template;
        }

        @SuppressWarnings("unchecked")
        private void convert(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template) {
            if (converted.contains(template)) {
                return;
            }
            synchronized (template) {
                if (converted.contains(template)) {
                    return;
                }
                var palettes = (java.util.List<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.Palette>)
                        UNSAFE.getObject(template, PALETTES);
                for (var palette : palettes) {
                    var cache = (java.util.Map<Object, Object>) UNSAFE.getObject(palette, CACHE);
                    if (!(cache instanceof java.util.concurrent.ConcurrentHashMap)) {
                        UNSAFE.putObject(palette, CACHE, new java.util.concurrent.ConcurrentHashMap<>(cache));
                    }
                }
                // Added last: a thread that sees the template here also sees
                // the swapped caches (ConcurrentHashMap add/contains order).
                converted.add(template);
            }
        }
    }

    /**
     * Run BIOMES + NOISE on one chunk, returning the filled ProtoChunk.
     * Kept separate from the store write so SURFACE (step 6 slice) runs on
     * the same chunk before anything lands in the store.
     */
    public static net.minecraft.world.level.chunk.ChunkAccess noise(
            Driver driver, int chunkX, int chunkZ) {
        ProtoChunk proto = newTrackedProtoChunk(driver, chunkX, chunkZ);
        Blender blender = Blender.empty();
        net.minecraft.world.level.chunk.ChunkAccess withBiomes = driver.generator()
                .createBiomes(driver.randomState(), blender, driver.structures(), proto)
                .join();
        return driver.generator()
                .fillFromNoise(blender, driver.randomState(), driver.structures(), withBiomes)
                .join();
    }

    private static final java.lang.invoke.MethodHandle CREATE_NOISE_CHUNK;
    private static final java.lang.invoke.MethodHandle CACHED_CLIMATE_SAMPLER;

    static {
        try {
            CREATE_NOISE_CHUNK = java.lang.invoke.MethodHandles
                    .privateLookupIn(NoiseBasedChunkGenerator.class, java.lang.invoke.MethodHandles.lookup())
                    .findVirtual(NoiseBasedChunkGenerator.class, "createNoiseChunk",
                            java.lang.invoke.MethodType.methodType(
                                    net.minecraft.world.level.levelgen.NoiseChunk.class,
                                    net.minecraft.world.level.chunk.ChunkAccess.class,
                                    StructureManager.class, Blender.class, RandomState.class));
            CACHED_CLIMATE_SAMPLER = java.lang.invoke.MethodHandles
                    .privateLookupIn(net.minecraft.world.level.levelgen.NoiseChunk.class,
                            java.lang.invoke.MethodHandles.lookup())
                    .findVirtual(net.minecraft.world.level.levelgen.NoiseChunk.class, "cachedClimateSampler",
                            java.lang.invoke.MethodType.methodType(
                                    net.minecraft.world.level.biome.Climate.Sampler.class,
                                    net.minecraft.world.level.levelgen.NoiseRouter.class, java.util.List.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Diagnostic: chunks whose BIOMES read a donor instead of sampling. */
    static final java.util.concurrent.atomic.AtomicLong BIOMES_FROM_DONOR =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * BIOMES for {@code chunk} as NoiseBasedChunkGenerator.doCreateBiomes runs
     * it (javap 26.2), step by step:
     * <ol>
     *   <li>{@code chunk.getOrCreateNoiseChunk(c -> createNoiseChunk(c,
     *       manager, blender, randomState))}, done here the same way;</li>
     *   <li>resolver = {@code BelowZeroRetrogen.getBiomeResolver(
     *       blender.getBiomeResolver(biomeSource), chunk)}, which is the
     *       biome source itself for {@code Blender.empty()} and a chunk that
     *       is not upgrading;</li>
     *   <li>{@code chunk.fillBiomesFromNoise(resolver,
     *       noiseChunk.cachedClimateSampler(router, spawnTarget))}.</li>
     * </ol>
     * The one change is the resolver in step 3: it returns the biome
     * {@code donor} holds at the same quart. The donor ran exactly this
     * BIOMES at the same position with the same inputs (Blender.empty(), not
     * upgrading, the same biome source and router), and the biome source's
     * answer depends only on the quart position and the seed. So it is the
     * value the resolver would have returned. fillBiomesFromNoise still
     * builds every section's container itself, in its own order, so the
     * palettes come out bit for bit the same. Returns false, doing nothing,
     * when those preconditions do not hold; the caller then runs Vanilla's
     * createBiomes.
     */
    static boolean createBiomesFrom(Driver driver, Blender blender, StructureManager manager,
            net.minecraft.world.level.chunk.ChunkAccess chunk,
            net.minecraft.world.level.chunk.ChunkAccess donor) {
        if (blender != Blender.empty()
                || !(driver.generator() instanceof NoiseBasedChunkGenerator generator)
                || chunk.isUpgrading() || donor.isUpgrading()
                || !chunk.getPos().equals(donor.getPos())
                || chunk.getMinSectionY() != donor.getMinSectionY()
                || chunk.getMaxSectionY() != donor.getMaxSectionY()) {
            return false;
        }
        RandomState randomState = driver.randomState();
        net.minecraft.world.level.levelgen.NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(c -> {
            try {
                return (net.minecraft.world.level.levelgen.NoiseChunk)
                        CREATE_NOISE_CHUNK.invoke(generator, c, manager, blender, randomState);
            } catch (Throwable t) {
                throw new IllegalStateException("createNoiseChunk failed", t);
            }
        });
        net.minecraft.world.level.biome.Climate.Sampler sampler;
        try {
            sampler = (net.minecraft.world.level.biome.Climate.Sampler) CACHED_CLIMATE_SAMPLER.invoke(
                    noiseChunk, randomState.router(), generator.generatorSettings().value().spawnTarget());
        } catch (Throwable t) {
            throw new IllegalStateException("cachedClimateSampler failed", t);
        }
        chunk.fillBiomesFromNoise((qx, qy, qz, ignored) -> donor
                .getSection(donor.getSectionIndexFromSectionY(qy >> 2))
                .getNoiseBiome(qx & 3, qy & 3, qz & 3), sampler);
        BIOMES_FROM_DONOR.incrementAndGet();
        return true;
    }

    /**
     * BIOMES only: {@link #noise} without fillFromNoise. The chunk's blocks
     * stay empty; use it only where nothing but the biomes is read.
     */
    public static net.minecraft.world.level.chunk.ChunkAccess biomesOnly(
            Driver driver, int chunkX, int chunkZ) {
        ProtoChunk proto = new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY,
                driver.height(), factory(driver.access()), null);
        return driver.generator()
                .createBiomes(driver.randomState(), Blender.empty(), driver.structures(), proto)
                .join();
    }

    /**
     * Headless SURFACE via the pure-context overload (step 6 slice that
     * avoids the WorldGenRegion trap 21): needs the BiomeManager over the
     * driver's sampler, the possible-biome set (3x3 quart neighbourhood,
     * mirroring collectPossibleBiomes), and the same Blender.empty().
     * Mutates the chunk in place; returns it for chaining.
     */
    public static net.minecraft.world.level.chunk.ChunkAccess surface(
            Driver driver, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        net.minecraft.world.level.biome.BiomeManager biomes =
                new net.minecraft.world.level.biome.BiomeManager(
                        (qx, qy, qz) -> driver.generator().getBiomeSource().getNoiseBiome(
                                qx, qy, qz, driver.randomState().sampler()),
                        0L);
        net.minecraft.world.level.levelgen.WorldGenerationContext context =
                new net.minecraft.world.level.levelgen.WorldGenerationContext(
                        driver.generator(), driver.height());
        java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> possible =
                collectPossibleBiomes(driver, chunk);
        driver.generator().buildSurface(chunk, context, driver.randomState(),
                driver.structures(), biomes, Blender.empty(), possible);
        return chunk;
    }

    /**
     * Headless CARVERS, direct (step 6 remainder without the WorldGenRegion
     * trap 21): mirrors applyCarvers' loop shape (javap
     * NoiseBasedChunkGenerator.applyCarvers) minus the region. Per chunk:
     * CarvingContext over the chunk's own NoiseChunk, shared CarvingMask
     * (first carver wins, trap 16), Legacy/WorldgenRandom with the
     * setLargeFeatureSeed sequence (traps 7-8), per-biome carver lists from
     * carverBiome, dx-outer/dz-inner/index-inner order. Needs the 3x3
     * neighbourhood chunks surfaced (carvers read neighbour columns);
     * pass a chunk provider or null for single-chunk mode (neighbours read
     * as air, reachability cull still consumes draws per trap 6).
     * Mutates the chunk in place; returns true if any carver rewrote a cell.
     */
    public static boolean carve(Driver driver,
            net.minecraft.world.level.chunk.ChunkAccess chunk,
            java.util.function.BiFunction<Integer, Integer,
                    net.minecraft.world.level.chunk.ChunkAccess> neighbours) {
        return carve(driver, chunk, neighbours, driver.seed());
    }

    public static boolean carve(Driver driver,
            net.minecraft.world.level.chunk.ChunkAccess chunk,
            java.util.function.BiFunction<Integer, Integer,
                    net.minecraft.world.level.chunk.ChunkAccess> neighbours,
            long seed) {
        net.minecraft.world.level.ChunkPos pos = chunk.getPos();
        // NoiseChunk: reuse the chunk's memoized one (getOrCreateNoiseChunk
        // reuses the fillFromNoise instance when present; createNoiseChunk
        // is private, so the lambda never runs on a filled chunk). Trap 17:
        // same cached aquifer through CARVERS.
        // getOrCreateNoiseChunk: ifnonnull skips the function, else apply +
        // putfield + return. A throwing lambda fails javac (no valid return
        // type), so use an anonymous Function whose apply throws. After
        // noise() the memo is present and apply never runs.
        net.minecraft.world.level.levelgen.NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(
                new java.util.function.Function<
                        net.minecraft.world.level.chunk.ChunkAccess,
                        net.minecraft.world.level.levelgen.NoiseChunk>() {
                    @Override
                    public net.minecraft.world.level.levelgen.NoiseChunk apply(
                            net.minecraft.world.level.chunk.ChunkAccess nc) {
                        throw new IllegalStateException(
                                "carve needs the fillFromNoise NoiseChunk; run noise() first");
                    }
                });
        net.minecraft.world.level.levelgen.carver.CarvingContext context =
                new net.minecraft.world.level.levelgen.carver.CarvingContext(
                        driver.generator(), driver.access(), driver.height(), noiseChunk,
                        driver.randomState(),
                        driver.generator().generatorSettings().value().surfaceRule());
        net.minecraft.world.level.chunk.CarvingMask mask = ((net.minecraft.world.level.chunk.ProtoChunk) chunk)
                .getOrCreateCarvingMask();
        net.minecraft.world.level.levelgen.Aquifer aquifer = noiseChunk.aquifer();
        net.minecraft.world.level.biome.BiomeManager biomes =
                new net.minecraft.world.level.biome.BiomeManager(
                        (qx, qy, qz) -> driver.generator().getBiomeSource().getNoiseBiome(
                                qx, qy, qz, driver.randomState().sampler()),
                        0L);
        java.util.function.Function<net.minecraft.core.BlockPos,
                net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomePos =
                biomes::getBiome;
        net.minecraft.util.RandomSource base = new net.minecraft.world.level.levelgen.LegacyRandomSource(
                net.minecraft.world.level.levelgen.RandomSupport.generateUniqueSeed());
        net.minecraft.world.level.levelgen.WorldgenRandom random =
                new net.minecraft.world.level.levelgen.WorldgenRandom(base);
        boolean rewrote = false;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                net.minecraft.world.level.chunk.ChunkAccess target = (dx == 0 && dz == 0) ? chunk
                        : (neighbours == null ? null
                                : neighbours.apply(pos.x() + dx, pos.z() + dz));
                if (target == null) {
                    continue;
                }
                net.minecraft.world.level.biome.BiomeGenerationSettings genSettings =
                        target.carverBiome(
                                new java.util.function.Supplier<
                                        net.minecraft.world.level.biome.BiomeGenerationSettings>() {
                                    @Override
                                    public net.minecraft.world.level.biome.BiomeGenerationSettings get() {
                                        return driver.generator()
                                                .getBiomeGenerationSettings(
                                                        biomes.getBiome(
                                                                new net.minecraft.core.BlockPos(
                                                                        target.getPos().getMinBlockX(),
                                                                        0,
                                                                        target.getPos()
                                                                                .getMinBlockZ())));
                                    }
                                });
                int index = 0;
                for (net.minecraft.core.Holder<net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver<?>> holder : genSettings
                        .getCarvers()) {
                    net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver<?> configured =
                            holder.value();
                    // Seed chain mirrors applyCarvers bytecode: the WorldgenRandom
                    // is constructed on a unique seed, then setLargeFeatureSeed
                    // per (chunk, index). Trap 7: seed is seed + index (carver
                    // position in the source biome list), not a hash. Trap 6:
                    // isStartChunk draws before reachability, keep the call.
                    random.setLargeFeatureSeed(seed + index, pos.x() + dx, pos.z() + dz);
                    if (configured.isStartChunk(random)) {
                        // Bytecode order (aload 7,8 then dup/requireNonNull):
                        // the carved chunk is the TARGET (neighbour), not the
                        // center; mask stays the center's shared CarvingMask
                        // (trap 16: first carver to claim a block wins).
                        rewrote |= configured.carve(context, target, biomePos, random, aquifer,
                                new net.minecraft.world.level.ChunkPos(pos.x() + dx,
                                        pos.z() + dz),
                                mask);
                    }
                    index++;
                }
            }
        }
        return rewrote;
    }

    /**
     * SURFACE with the region's BiomeManager seed (step 7 parity fix,
     * additive), via a palette-backed BiomeManager over a prepared chunk
     * grid (WorldGenRegion semantics, javap-confirmed): {@code buildSurface}
     * resolves biomes through {@code BiomeManager.getBiome}, whose fuzzy
     * quart-zoom interpolation ({@code obfuscateSeed(worldSeed)}) can
     * resolve to a quart owned by a neighbouring chunk for any column near
     * a chunk edge - the fiddle is bounded to +-1 quart, so it never
     * reaches past the immediate 3x3 neighbourhood. The manager must
     * therefore read the neighbourhood's actual generated biome palettes
     * ({@code ChunkAccess.getNoiseBiome}), not a fresh raw
     * {@code BiomeSource} sample: confirmed empirically in
     * {@code .diagnostics/TestSurfaceWithGridBiomes.java}, which eliminated
     * 304 of the 575 Tier R residual cells this way.
     *
     * @param biomeGrid the chunk's own 3x3 neighbourhood, each entry already
     *                  at BIOMES status or later (see {@link #prepareBiomeGrid}).
     *                  A missing owning chunk is a lifecycle bug upstream:
     *                  this fails loudly instead of silently falling back to
     *                  the raw source, which would quietly reproduce the
     *                  defect this method fixes.
     */
    public static net.minecraft.world.level.chunk.ChunkAccess surfaceVanilla(
            Driver driver, net.minecraft.world.level.chunk.ChunkAccess chunk,
            java.util.Map<ChunkPos, net.minecraft.world.level.chunk.ChunkAccess> biomeGrid) {
        net.minecraft.world.level.levelgen.WorldGenerationContext context =
                new net.minecraft.world.level.levelgen.WorldGenerationContext(
                        driver.generator(), driver.height());
        driver.generator().buildSurface(chunk, context, driver.randomState(),
                driver.structures(), gridBiomes(driver, biomeGrid), Blender.empty(),
                collectPossibleBiomes(biomeGrid, chunk.getPos()));
        return chunk;
    }

    /** Exact WorldGenRegion collectPossibleBiomes(radius=1) shape for SURFACE. */
    private static java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>
            collectPossibleBiomes(
                    java.util.Map<ChunkPos, net.minecraft.world.level.chunk.ChunkAccess> biomeGrid,
                    ChunkPos center) {
        java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> set =
                new java.util.HashSet<>();
        ChunkPos.rangeClosed(center, 1).forEach(pos -> {
            net.minecraft.world.level.chunk.ChunkAccess candidate = biomeGrid.get(pos);
            if (candidate == null) {
                throw new IllegalStateException(
                        "surfaceVanilla: possible-biome grid missing chunk " + pos);
            }
            candidate.collectBiomesInPalette(set);
        });
        return set;
    }

    /**
     * The palette-backed BiomeManager {@link #surfaceVanilla} needs: same
     * seed shape as {@link #regionBiomes} (obfuscateSeed(worldSeed)), but
     * the noise-biome source resolves each quart through the chunk that
     * owns it in {@code biomeGrid} rather than a fresh sampler call.
     */
    private static net.minecraft.world.level.biome.BiomeManager gridBiomes(Driver driver,
            java.util.Map<ChunkPos, net.minecraft.world.level.chunk.ChunkAccess> biomeGrid) {
        return new net.minecraft.world.level.biome.BiomeManager(
                (qx, qy, qz) -> {
                    ChunkPos owner = new ChunkPos(qx >> 2, qz >> 2);
                    net.minecraft.world.level.chunk.ChunkAccess owning = biomeGrid.get(owner);
                    if (owning == null) {
                        throw new IllegalStateException(
                                "surfaceVanilla: biome grid missing chunk " + owner
                                        + " needed for quart (" + qx + "," + qy + "," + qz + ")");
                    }
                    return owning.getNoiseBiome(qx, qy, qz);
                },
                net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(driver.seed()));
    }

    /**
     * Build the 3x3 biome grid {@link #surfaceVanilla} needs for chunk
     * (cx, cz). Each neighbour is resolved through {@code primary} first
     * (e.g. an already CARVERS-ready chunk a caller's grid holds under a
     * different key shape - hence a lookup function, not a Map), and only
     * when absent there materialised NOISE-only into {@code margin}.
     *
     * <p>{@code margin} entries are never CARVERS-ready and exist solely to
     * answer biome-palette reads for a neighbour SURFACE never otherwise
     * needed to run; callers must not publish them to a store, a
     * {@link RegionView}, or any other structure that implies FEATURES or
     * CARVERS completion. A caller than later needs that same chunk as an
     * actual center should keep reusing this same {@code margin} map (or
     * promote the chunk into {@code primary}) rather than regenerating it,
     * since {@link #noise} is pure and the object is already correct.
     */
    public static java.util.Map<ChunkPos, net.minecraft.world.level.chunk.ChunkAccess> prepareBiomeGrid(
            Driver driver, int cx, int cz,
            java.util.function.BiFunction<Integer, Integer, net.minecraft.world.level.chunk.ChunkAccess> primary,
            java.util.Map<ChunkPos, net.minecraft.world.level.chunk.ChunkAccess> margin) {
        return prepareBiomeGrid(driver, cx, cz, primary, margin, false);
    }

    /**
     * As above. With {@code biomesOnlyMargin}, a missing margin chunk runs
     * only BIOMES ({@link #biomesOnly}), not NOISE: for callers that never
     * promote a margin chunk into a CARVERS chunk (the structures-on live
     * generator builds every CARVERS chunk afresh through
     * {@link #noiseWithStructures}), the margin is read only for its biomes,
     * and fillFromNoise never writes biomes, so the biome values SURFACE reads
     * are the same while one full NOISE per chunk is saved.
     */
    public static java.util.Map<ChunkPos, net.minecraft.world.level.chunk.ChunkAccess> prepareBiomeGrid(
            Driver driver, int cx, int cz,
            java.util.function.BiFunction<Integer, Integer, net.minecraft.world.level.chunk.ChunkAccess> primary,
            java.util.Map<ChunkPos, net.minecraft.world.level.chunk.ChunkAccess> margin,
            boolean biomesOnlyMargin) {
        java.util.Map<ChunkPos, net.minecraft.world.level.chunk.ChunkAccess> grid = new java.util.HashMap<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int nx = cx + dx;
                int nz = cz + dz;
                net.minecraft.world.level.chunk.ChunkAccess c = primary.apply(nx, nz);
                if (c == null) {
                    ChunkPos pos = new ChunkPos(nx, nz);
                    c = margin.get(pos);
                    if (c == null) {
                        net.minecraft.world.level.chunk.ChunkAccess fresh = biomesOnlyMargin
                                ? biomesOnly(driver, nx, nz) : noise(driver, nx, nz);
                        ((ProtoChunk) fresh).setPersistedStatus(
                                net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
                        margin.put(pos, fresh);
                        c = fresh;
                    }
                }
                grid.put(new ChunkPos(nx, nz), c);
            }
        }
        return grid;
    }

    /**
     * CARVERS exactly as NoiseBasedChunkGenerator.applyCarvers (javap,
     * additive next to {@link #carve}). Three differences from carve():
     * <ul>
     *   <li>the carved chunk is always the CENTER (applyCarvers passes
     *       local 7, the chunk argument; the neighbour at local 19 is only
     *       read for carverBiome);</li>
     *   <li>the neighbour carver biome is lambda$applyCarvers$2: the biome
     *       SOURCE at quart (minBlockX>>2, 0, minBlockZ>>2), no zoom
     *       fiddle, so no neighbour chunk is needed at all;</li>
     *   <li>the biome function is region.getBiomeManager()
     *       .withDifferentSource(noise), seed obfuscateSeed(worldSeed).</li>
     * </ul>
     * Fresh neighbours have no cached carverBiome, so computing it from
     * the source equals ChunkAccess.carverBiome's memo.
     */
    public static boolean carveVanilla(Driver driver,
            net.minecraft.world.level.chunk.ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        net.minecraft.world.level.levelgen.NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(
                new java.util.function.Function<
                        net.minecraft.world.level.chunk.ChunkAccess,
                        net.minecraft.world.level.levelgen.NoiseChunk>() {
                    @Override
                    public net.minecraft.world.level.levelgen.NoiseChunk apply(
                            net.minecraft.world.level.chunk.ChunkAccess nc) {
                        throw new IllegalStateException(
                                "carveVanilla needs the fillFromNoise NoiseChunk; run noise() first");
                    }
                });
        net.minecraft.world.level.levelgen.carver.CarvingContext context =
                new net.minecraft.world.level.levelgen.carver.CarvingContext(
                        driver.generator(), driver.access(), driver.height(), noiseChunk,
                        driver.randomState(),
                        driver.generator().generatorSettings().value().surfaceRule());
        net.minecraft.world.level.chunk.CarvingMask mask =
                ((ProtoChunk) chunk).getOrCreateCarvingMask();
        net.minecraft.world.level.levelgen.Aquifer aquifer = noiseChunk.aquifer();
        net.minecraft.world.level.biome.BiomeManager biomes = regionBiomes(driver);
        net.minecraft.world.level.levelgen.WorldgenRandom random =
                new net.minecraft.world.level.levelgen.WorldgenRandom(
                        new net.minecraft.world.level.levelgen.LegacyRandomSource(
                                net.minecraft.world.level.levelgen.RandomSupport
                                        .generateUniqueSeed()));
        net.minecraft.world.level.biome.BiomeSource source = driver.generator().getBiomeSource();
        boolean rewrote = false;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                ChunkPos start = new ChunkPos(pos.x() + dx, pos.z() + dz);
                net.minecraft.world.level.biome.BiomeGenerationSettings genSettings =
                        driver.generator().getBiomeGenerationSettings(source.getNoiseBiome(
                                net.minecraft.core.QuartPos.fromBlock(start.getMinBlockX()), 0,
                                net.minecraft.core.QuartPos.fromBlock(start.getMinBlockZ()),
                                driver.randomState().sampler()));
                int index = 0;
                for (Holder<net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver<?>> holder
                        : genSettings.getCarvers()) {
                    net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver<?> configured =
                            holder.value();
                    random.setLargeFeatureSeed(driver.seed() + index, start.x(), start.z());
                    if (configured.isStartChunk(random)) {
                        rewrote |= configured.carve(context, chunk, biomes::getBiome, random,
                                aquifer, start, mask);
                    }
                    index++;
                }
            }
        }
        return rewrote;
    }

    /**
     * Drop the NoiseChunk a ChunkAccess memoized during noise/surface/carve.
     *
     * <p>{@code ChunkAccess.noiseChunk} (javap: protected field) is populated
     * by {@code getOrCreateNoiseChunk} and read only by the noise, surface and
     * carve passes. FEATURES, settleLeaves and writeStoreCompact never ask for
     * it, so once CARVERS is done the memo is dead weight that still pins the
     * chunk's whole density-function graph: interpolators and their double[]
     * slices, FlatCache/Cache2D/CacheOnce, the aquifer and its FluidStatus[],
     * and the spline graph. Measured at ~57 KiB per chunk, and vanilla never
     * pays it because it discards the entire ProtoChunk after FEATURES, while
     * {@link LiveGenerator} must keep the ProtoChunk to accumulate a
     * neighbour's later FEATURES writes.
     *
     * <p>Parity: clears generation scratch only. No block, biome or fluid
     * state lives on the NoiseChunk, so reads after this call are unchanged.
     * Should anything later ask for the memo again, {@code getOrCreateNoiseChunk}
     * re-runs the caller's factory, and both factories here throw a named
     * IllegalStateException rather than silently generating a second,
     * differently-seeded NoiseChunk - a loud failure, not a parity drift.
     *
     * <p>Best effort: a reflection failure leaves the memo in place, which
     * costs memory but changes no behaviour.
     */
    public static void releaseNoiseChunk(
            net.minecraft.world.level.chunk.ChunkAccess chunk) {
        if (chunk == null || NOISE_CHUNK_FIELD == null) {
            return;
        }
        try {
            NOISE_CHUNK_FIELD.set(chunk, null);
        } catch (IllegalAccessException ignored) {
            // Best effort: keeping the memo only costs memory.
        }
    }

    /** {@code ChunkAccess.noiseChunk}, or null when it cannot be reached. */
    private static final java.lang.reflect.Field NOISE_CHUNK_FIELD = noiseChunkField();

    private static java.lang.reflect.Field noiseChunkField() {
        try {
            java.lang.reflect.Field f =
                    net.minecraft.world.level.chunk.ChunkAccess.class
                            .getDeclaredField("noiseChunk");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The region BiomeManager shape: noise-source backed, seed
     * obfuscateSeed(worldSeed) (WorldGenRegion ctor, javap). Only used by
     * {@link #carveVanilla} (per-block biome checks inside an already-carved
     * cell). This is NOT exact: {@code BiomeManager.getBiome}'s fuzzy
     * quart-zoom lookup can resolve into a neighbouring chunk's biome
     * palette near a chunk edge, and a raw source sample does not see that
     * palette (see {@link #surfaceVanilla}, which fixes this for SURFACE via
     * {@link #gridBiomes}/{@link #prepareBiomeGrid}). Carve's use of this
     * source-backed manager is an existing, separately-scoped approximation,
     * not touched by the SURFACE fix.
     */
    private static net.minecraft.world.level.biome.BiomeManager regionBiomes(Driver driver) {
        return new net.minecraft.world.level.biome.BiomeManager(
                (qx, qy, qz) -> driver.generator().getBiomeSource().getNoiseBiome(
                        qx, qy, qz, driver.randomState().sampler()),
                net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(driver.seed()));
    }

    /**
     * G2 step 7: FEATURES for one chunk, serial, structures off
     * (preresearch/g2-step7-design.md). Mirrors ChunkStatusTasks
     * .generateFeatures + ChunkGenerator.applyBiomeDecoration (javap 26.2):
     * <ol>
     *   <li>prime MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR,
     *       WORLD_SURFACE on the center (trap 14), after carvers;</li>
     *   <li>one WorldgenRandom(Xoroshiro) (trap 8), decoration seed from the
     *       section ORIGIN block coords (trap 9);</li>
     *   <li>possible biomes = biome palettes of the 3x3 chunks intersected
     *       with the source's possible set;</li>
     *   <li>for stepIndex in 0..max(11, featureSteps): structures block
     *       (skipped: shouldGenerateStructures false, trap 3), then the union
     *       of per-biome global feature indices sorted ascending (trap 12),
     *       each reseeded setFeatureSeed(decorationSeed, index, step) and
     *       placed via vanilla placeWithBiomeCheck (trap 1: the lazy
     *       placement stream is vanilla code, never ported).</li>
     * </ol>
     * Blender.generateBorderTicks is ticks only (no blocks) and is skipped.
     *
     * @param view   radius-1 write view centred on {@code chunk}
     * @param filter placed-feature keys to run (null = all); a skipped
     *               feature does not shift later seeds because every
     *               feature reseeds from its own global index
     */
    public static void features(Driver driver, net.minecraft.world.level.chunk.ChunkAccess chunk,
            RegionView view) {
        features(driver, chunk, view, null);
    }

    public static void features(Driver driver, net.minecraft.world.level.chunk.ChunkAccess chunk,
            RegionView view,
            java.util.function.Predicate<ResourceKey<net.minecraft.world.level.levelgen.placement.PlacedFeature>> filter) {
        if (driver.structures().shouldGenerateStructures()) {
            throw new UnsupportedOperationException("step 7 is structures-off (step 8)");
        }
        net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR,
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE));
        net.minecraft.world.level.WorldGenLevel level = view.level();
        ChunkPos pos = chunk.getPos();
        BlockPos origin = net.minecraft.core.SectionPos.of(pos, level.getMinSectionY()).origin();
        List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData> featureList =
                featuresPerStep(driver.generator());
        net.minecraft.world.level.levelgen.WorldgenRandom random =
                new net.minecraft.world.level.levelgen.WorldgenRandom(
                        new net.minecraft.world.level.levelgen.XoroshiroRandomSource(
                                net.minecraft.world.level.levelgen.RandomSupport
                                        .generateUniqueSeed()));
        long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
        java.util.Set<Holder<net.minecraft.world.level.biome.Biome>> possible =
                new it.unimi.dsi.fastutil.objects.ObjectArraySet<>();
        ChunkPos.rangeClosed(pos, 1).forEach(p -> {
            for (LevelChunkSection section : level.getChunk(p.x(), p.z()).getSections()) {
                section.getBiomes().getAll(possible::add);
            }
        });
        possible.retainAll(driver.generator().getBiomeSource().possibleBiomes());
        int featureSteps = featureList.size();
        Registry<net.minecraft.world.level.levelgen.placement.PlacedFeature> placed =
                level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        int steps = Math.max(
                net.minecraft.world.level.levelgen.GenerationStep.Decoration.values().length,
                featureSteps);
        for (int stepIndex = 0; stepIndex < steps; stepIndex++) {
            // Structure block (ChunkGenerator bytecode 227-396) goes here in
            // step 8, BEFORE features (trap 11), counting absent structures.
            if (stepIndex >= featureSteps) {
                continue;
            }
            it.unimi.dsi.fastutil.ints.IntSet indices = new it.unimi.dsi.fastutil.ints.IntArraySet();
            net.minecraft.world.level.biome.FeatureSorter.StepFeatureData stepData =
                    featureList.get(stepIndex);
            for (Holder<net.minecraft.world.level.biome.Biome> biome : possible) {
                List<net.minecraft.core.HolderSet<net.minecraft.world.level.levelgen.placement.PlacedFeature>> perStep =
                        driver.generator().getBiomeGenerationSettings(biome).features();
                if (stepIndex >= perStep.size()) {
                    continue;
                }
                perStep.get(stepIndex).stream().map(Holder::value)
                        .forEach(f -> indices.add(stepData.indexMapping().applyAsInt(f)));
            }
            int[] sorted = indices.toIntArray();
            java.util.Arrays.sort(sorted);
            for (int globalIndex : sorted) {
                net.minecraft.world.level.levelgen.placement.PlacedFeature feature =
                        stepData.features().get(globalIndex);
                // Seed before the filter: vanilla reseeds per feature, so a
                // filtered-out feature leaves no trace on later seeds.
                random.setFeatureSeed(decorationSeed, globalIndex, stepIndex);
                ResourceKey<net.minecraft.world.level.levelgen.placement.PlacedFeature> key =
                        placed.getResourceKey(feature).orElse(null);
                if (view.beforeFeatureDebug != null) {
                    view.beforeFeatureDebug.accept("key=" + key
                            + " decorationSeed=" + decorationSeed
                            + " globalIndex=" + globalIndex
                            + " stepIndex=" + stepIndex);
                }
                if (view.featureSeedDebug != null) {
                    view.featureSeedDebug.accept(pos, stepIndex + "\t" + globalIndex
                            + "\t" + decorationSeed + "\t" + key);
                }
                if (filter != null && (key == null || !filter.test(key))) {
                    continue;
                }
                try {
                    feature.placeWithBiomeCheck(level, driver.generator(), random, origin);
                } catch (RuntimeException e) {
                    if (view.featureFailure != null) {
                        view.featureFailure.accept(key, e);
                    }
                    // A single feature failing to place (e.g. FossilFeature hitting a
                    // WorldGenLevel that our headless context can't fully satisfy) must
                    // not take the whole server down: this loop runs on the sole main
                    // thread inside the per-tick streaming path (ChunkSender ->
                    // NetServer.tick), so an uncaught exception here kills the process.
                    // Log and skip this one feature instead.
                    System.err.println("[kestrel] feature placement failed, skipping: key="
                            + key + " chunk=" + pos + " cause=" + e);
                }
                if (view.afterFeature != null) {
                    view.afterFeature.accept(key);
                }
            }
        }
    }

    /**
     * G2 step 8: structures-on FEATURES for one center chunk.
     *
     * <p>This deliberately keeps the already Tier-R-proven Kestrel feature
     * loop instead of calling ChunkGenerator.applyBiomeDecoration wholesale:
     * some ordinary template/fossil features reach ServerLevelAccessor
     * .getLevel(), which a headless RegionView cannot provide. The structure
     * block itself is inserted verbatim in Vanilla's ordering: per decoration
     * step, every structure is reseeded and placed before normal features in
     * that step. This preserves structure/feature RNG semantics while keeping
     * the structures-off feature behavior unchanged.
     *
     * <p>The caller must supply a RegionView whose backing map contains the
     * center's 3x3 writable/read neighbourhood and every referenced
     * STRUCTURE_STARTS chunk reachable from the center's reference sets.
     */
    public static void featuresWithStructures(
            Structural structural,
            net.minecraft.world.level.chunk.ChunkAccess chunk,
            RegionView view) {
        StructureManager manager = new StructureManager(
                view.level(), structural.options(), structural.structureCheck());
        // Structure-spawn entity hooks receive WorldGenLevel.getLevel(), i.e.
        // the headless ServerLevel shell. Bind the same manager used by this
        // FEATURES pass so entity finalizeSpawn queries (village cats are a
        // concrete 26.2 case) observe the real structure-reference state.
        view.entityLevel.bindStructureManager(manager);

        net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(
                chunk,
                java.util.EnumSet.of(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR,
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE));

        Driver driver = structural.driver();
        net.minecraft.world.level.WorldGenLevel level = view.level();
        ChunkPos pos = chunk.getPos();
        net.minecraft.core.SectionPos sectionPos =
                net.minecraft.core.SectionPos.of(pos, level.getMinSectionY());
        BlockPos origin = sectionPos.origin();

        Registry<net.minecraft.world.level.levelgen.structure.Structure> structureRegistry =
                level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        java.util.Map<Integer, java.util.List<
                net.minecraft.world.level.levelgen.structure.Structure>> structuresByStep =
                structureRegistry.stream().collect(
                        java.util.stream.Collectors.groupingBy(
                                structure -> structure.step().ordinal()));

        List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData> featureList =
                featuresPerStep(driver.generator());
        net.minecraft.world.level.levelgen.WorldgenRandom random =
                new net.minecraft.world.level.levelgen.WorldgenRandom(
                        new net.minecraft.world.level.levelgen.XoroshiroRandomSource(
                                net.minecraft.world.level.levelgen.RandomSupport
                                        .generateUniqueSeed()));
        long decorationSeed =
                random.setDecorationSeed(
                        level.getSeed(), origin.getX(), origin.getZ());

        java.util.Set<Holder<net.minecraft.world.level.biome.Biome>> possible =
                new it.unimi.dsi.fastutil.objects.ObjectArraySet<>();
        ChunkPos.rangeClosed(pos, 1).forEach(p -> {
            for (LevelChunkSection section
                    : level.getChunk(p.x(), p.z()).getSections()) {
                section.getBiomes().getAll(possible::add);
            }
        });
        possible.retainAll(
                driver.generator().getBiomeSource().possibleBiomes());

        Registry<net.minecraft.world.level.levelgen.placement.PlacedFeature> placed =
                level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        int featureSteps = featureList.size();
        int steps = Math.max(
                net.minecraft.world.level.levelgen.GenerationStep.Decoration
                        .values().length,
                featureSteps);

        net.minecraft.world.level.levelgen.structure.BoundingBox writable =
                new net.minecraft.world.level.levelgen.structure.BoundingBox(
                        pos.getMinBlockX(),
                        chunk.getMinY() + 1,
                        pos.getMinBlockZ(),
                        pos.getMinBlockX() + 15,
                        chunk.getMaxY(),
                        pos.getMinBlockZ() + 15);

        for (int stepIndex = 0; stepIndex < steps; stepIndex++) {
            int structureIndex = 0;
            for (net.minecraft.world.level.levelgen.structure.Structure structure
                    : structuresByStep.getOrDefault(
                            stepIndex, java.util.Collections.emptyList())) {
                random.setFeatureSeed(
                        decorationSeed, structureIndex, stepIndex);
                ResourceKey<net.minecraft.world.level.levelgen.structure.Structure> structureKey =
                        structureRegistry.getResourceKey(structure).orElse(null);
                manager.startsForStructure(sectionPos, structure)
                        .forEach(start -> {
                            if (view.beforeStructure != null) {
                                view.beforeStructure.accept(structureKey, start);
                            }
                            start.placeInChunk(
                                    level,
                                    manager,
                                    driver.generator(),
                                    random,
                                    writable,
                                    pos);
                            if (view.afterStructure != null) {
                                view.afterStructure.accept(structureKey, start);
                            }
                        });
                structureIndex++;
            }

            if (stepIndex >= featureSteps) {
                continue;
            }

            it.unimi.dsi.fastutil.ints.IntSet indices =
                    new it.unimi.dsi.fastutil.ints.IntArraySet();
            net.minecraft.world.level.biome.FeatureSorter.StepFeatureData stepData =
                    featureList.get(stepIndex);
            for (Holder<net.minecraft.world.level.biome.Biome> biome : possible) {
                List<net.minecraft.core.HolderSet<
                        net.minecraft.world.level.levelgen.placement.PlacedFeature>> perStep =
                        driver.generator()
                                .getBiomeGenerationSettings(biome)
                                .features();
                if (stepIndex < perStep.size()) {
                    perStep.get(stepIndex)
                            .stream()
                            .map(Holder::value)
                            .forEach(feature -> indices.add(
                                    stepData.indexMapping()
                                            .applyAsInt(feature)));
                }
            }

            int[] sorted = indices.toIntArray();
            java.util.Arrays.sort(sorted);
            for (int globalIndex : sorted) {
                net.minecraft.world.level.levelgen.placement.PlacedFeature feature =
                        stepData.features().get(globalIndex);
                random.setFeatureSeed(
                        decorationSeed, globalIndex, stepIndex);
                ResourceKey<
                        net.minecraft.world.level.levelgen.placement.PlacedFeature> key =
                        placed.getResourceKey(feature).orElse(null);
                if (view.beforeFeatureDebug != null) {
                    view.beforeFeatureDebug.accept("key=" + key
                            + " decorationSeed=" + decorationSeed
                            + " globalIndex=" + globalIndex
                            + " stepIndex=" + stepIndex);
                }
                if (view.featureSeedDebug != null) {
                    view.featureSeedDebug.accept(pos, stepIndex + "\t" + globalIndex
                            + "\t" + decorationSeed + "\t" + key);
                }
                try {
                    feature.placeWithBiomeCheck(
                            level, driver.generator(), random, origin);
                } catch (RuntimeException e) {
                    if (view.featureFailure != null) {
                        view.featureFailure.accept(key, e);
                    }
                    System.err.println(
                            "[kestrel] feature placement failed, skipping: key="
                                    + key + " chunk=" + pos + " cause=" + e);
                }
                if (view.afterFeature != null) {
                    view.afterFeature.accept(key);
                }
            }
        }

        if (chunk instanceof ProtoChunk proto) {
            proto.setPersistedStatus(
                    net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES);
        }
    }

    /**
     * Vanilla 26.2 NoiseBasedChunkGenerator.spawnOriginalMobs expressed
     * against RegionView. Keep the two RNG streams distinct: the decoration
     * WorldgenRandom below and WorldGenRegion/RegionView.getRandom().
     */
    static void spawnOriginalMobs(
            Driver driver, Structural structural, RegionView view) {
        if (driver.generator().generatorSettings().value().disableMobGeneration()) {
            return;
        }
        if (structural != null) {
            view.entityLevel.bindStructureManager(
                    new StructureManager(
                            view.level(),
                            structural.options(),
                            structural.structureCheck()));
        }
        net.minecraft.world.level.WorldGenLevel level = view.level();
        net.minecraft.world.level.ChunkPos pos = view.center;
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome =
                level.getBiome(pos.getWorldPosition().atY(level.getMaxY()));
        net.minecraft.world.level.levelgen.WorldgenRandom random =
                new net.minecraft.world.level.levelgen.WorldgenRandom(
                        new net.minecraft.world.level.levelgen.LegacyRandomSource(
                                net.minecraft.world.level.levelgen.RandomSupport
                                        .generateUniqueSeed()));
        random.setDecorationSeed(
                level.getSeed(), pos.getMinBlockX(), pos.getMinBlockZ());
        net.minecraft.world.level.NaturalSpawner.spawnMobsForChunkGeneration(
                level, biome, pos, random);
    }

    /**
     * Jacobi fixpoint of LeavesBlock.updateDistance over the 5x5 chunks:
     * distance = min(7, min over 6 neighbours of (log 0 | leaves distance | 7) + 1).
     * Promoted verbatim from FeaturesCheck.settleLeaves (g2-step9-tick-design.md
     * section 4): LeavesBlock.tick is a pure function of the 6 neighbours, and
     * every stale leaf already has a scheduled tick from updateShape, so this
     * replays exactly what those ticks converge to. Reads use the 5x5 ring
     * around {@code (cx, cz)}; only the center 3x3's changed cells are counted.
     *
     * @return number of changed cells in the center chunk, for the log
     */
    public static int settleLeaves(java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> chunks,
            int cx, int cz) {
        int changed = 0;
        boolean again = true;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        net.minecraft.world.level.block.state.properties.IntegerProperty dist =
                net.minecraft.world.level.block.LeavesBlock.DISTANCE;
        while (again) {
            again = false;
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    net.minecraft.world.level.chunk.ChunkAccess c = chunks.get(ChunkPos.pack(cx + ox, cz + oz));
                    for (int i = 0; i < HEIGHT * 256; i++) {
                        cellPos(m, i, cx + ox, cz + oz);
                        net.minecraft.world.level.block.state.BlockState st = c.getBlockState(m);
                        if (!st.hasProperty(dist)) {
                            continue;
                        }
                        int best = 7;
                        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                            n.setWithOffset(m, d);
                            net.minecraft.world.level.chunk.ChunkAccess nc =
                                    chunks.get(ChunkPos.pack(n.getX() >> 4, n.getZ() >> 4));
                            if (nc == null) {
                                continue;
                            }
                            net.minecraft.world.level.block.state.BlockState ns = nc.getBlockState(n);
                            int v = ns.is(net.minecraft.tags.BlockTags.LOGS) ? 0
                                    : ns.hasProperty(dist) ? ns.getValue(dist) : 7;
                            best = Math.min(best, v + 1);
                        }
                        if (best != st.getValue(dist)) {
                            c.setBlockState(m, st.setValue(dist, best), 0);
                            again = true;
                            if (ox == 0 && oz == 0) {
                                changed++;
                            }
                        }
                    }
                }
            }
        }
        return changed;
    }

    private static void cellPos(BlockPos.MutableBlockPos m, int i, int cx, int cz) {
        int y = MIN_Y + ((i >> 12) << 4) + ((i >> 8) & 15);
        m.set(cx * 16 + (i & 15), y, cz * 16 + ((i >> 4) & 15));
    }

    /** ChunkGenerator.featuresPerStep (private memoized Supplier, javap field). */
    @SuppressWarnings("unchecked")
    private static List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData> featuresPerStep(
            net.minecraft.world.level.chunk.ChunkGenerator generator) {
        try {
            java.lang.reflect.Field f = net.minecraft.world.level.chunk.ChunkGenerator.class
                    .getDeclaredField("featuresPerStep");
            f.setAccessible(true);
            return (List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData>)
                    ((java.util.function.Supplier<?>) f.get(generator)).get();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("featuresPerStep", e);
        }
    }

    /**
     * Radius-1 FEATURES view, a WorldGenRegion stand-in without ServerLevel
     * (trap 21). Shape per WorldGenRegion javap: reads resolve through the
     * chunk map (3x3 minimum, a larger map is allowed for reads), writes go
     * through ensureCanWrite = chessboard distance &lt;= 1 from the center,
     * dropped silently otherwise (D8). Block entities become DUMMY nbt on
     * proto chunks, post-process positions are marked like setBlock.
     * Biomes: BiomeManager over the view, seed obfuscateSeed(worldSeed);
     * chunks outside the map fall back to the biome source
     * (getUncachedNoiseBiome). Unimplemented abstract methods throw with
     * their name so a feature needing more of the level fails loudly.
     */
    public static final class RegionView {
        private final Driver driver;
        private final ChunkPos center;
        private final java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> chunks;
        /** The same map when it is a fastutil long map: lookups without boxing. */
        private final it.unimi.dsi.fastutil.longs.Long2ObjectMap<net.minecraft.world.level.chunk.ChunkAccess>
                primitiveChunks;
        private final net.minecraft.world.level.WorldGenLevel level;
        private final HeadlessEntityServerLevel entityLevel;
        private final net.minecraft.world.level.biome.BiomeManager biomeManager;
        private final net.minecraft.world.level.lighting.LevelLightEngine lightEngine;
        // WorldGenRegion exposes the live world's normal border. Spawn
        // placement only needs isWithinBounds; a fresh default border has
        // Vanilla's normal world bounds and is stable for this RegionView.
        private final net.minecraft.world.level.border.WorldBorder worldBorder =
                new net.minecraft.world.level.border.WorldBorder();
        /** One stable Imposter-equivalent view per backing FULL chunk for this WorldGenRegion. */
        private final java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess>
                readOnlyFullViews = new java.util.HashMap<>();
        private final net.minecraft.util.RandomSource random;
        private final net.minecraft.world.ticks.WorldGenTickAccess<net.minecraft.world.level.block.Block> blockTicks;
        private final net.minecraft.world.ticks.WorldGenTickAccess<net.minecraft.world.level.material.Fluid> fluidTicks;
        private final java.util.concurrent.atomic.AtomicLong subTick = new java.util.concurrent.atomic.AtomicLong();
        /**
         * Optional hook after each placed feature (key). Ores write through
         * BulkSectionAccess straight into sections, bypassing setBlock, so
         * attribution is by cell diff after each feature.
         */
        public java.util.function.Consumer<ResourceKey<net.minecraft.world.level.levelgen.placement.PlacedFeature>> afterFeature;
        /** Optional diagnostic hook with the exact feature reseed inputs. */
        public java.util.function.Consumer<String> beforeFeatureDebug;
        /**
         * Diagnostic-only hook fired once per placed-feature reseed, with the
         * chunk and "stepIndex TAB globalIndex TAB decorationSeed TAB key".
         * It exists so a gate can dump the per-origin feature index set
         * Kestrel derives and diff it against a trace's feature-places.tsv.
         * Vanilla's own set is exactly the rows that trace records for the
         * origin, so a mismatch localizes a possible-biome or index-mapping
         * divergence without a second capture. Inert unless set.
         */
        public java.util.function.BiConsumer<ChunkPos, String> featureSeedDebug;
        /**
         * Optional fail-closed hook for a feature that production would skip
         * after a RuntimeException. Parity gates install this hook so an
         * unsupported headless dependency can never be hidden by catch-and-continue.
         */
        public java.util.function.BiConsumer<
                ResourceKey<net.minecraft.world.level.levelgen.placement.PlacedFeature>,
                RuntimeException> featureFailure;
        /** Optional diagnostic hook for WorldGenLevel.getBiome positions/results. */
        public java.util.function.BiConsumer<BlockPos,
                net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomeQueryDebug;
        /** Optional diagnostic hook for WorldGenRegion.getBlockEntity results. */
        public java.util.function.BiConsumer<BlockPos,
                net.minecraft.world.level.block.entity.BlockEntity> blockEntityQueryDebug;
        /** Optional hooks bracketing each StructureStart.placeInChunk call. */
        public java.util.function.BiConsumer<
                ResourceKey<net.minecraft.world.level.levelgen.structure.Structure>,
                net.minecraft.world.level.levelgen.structure.StructureStart> beforeStructure;
        public java.util.function.BiConsumer<
                ResourceKey<net.minecraft.world.level.levelgen.structure.Structure>,
                net.minecraft.world.level.levelgen.structure.StructureStart> afterStructure;

        /**
         * Diagnostic-only deterministic override for WorldGenRegion.getHeight.
         * Vanilla may swap CARVERS futures to ImposterProtoChunk while FEATURES
         * is running; this hook can replay captured results while investigating
         * lifecycle mismatches. Exact parity gates must use
         * {@link HeightQueryObserver} instead so Kestrel computes the result
         * natively and the capture is assertion data only.
         */
        @FunctionalInterface
        public interface HeightQueryOverride {
            int get(net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z);
        }
        public HeightQueryOverride heightQueryOverride;

        /**
         * Optional observation hook for the native RegionView getHeight result.
         * Unlike {@link #heightQueryOverride}, this cannot change generation:
         * it sees the value only after Kestrel has computed it.
         */
        @FunctionalInterface
        public interface HeightQueryObserver {
            void accept(net.minecraft.world.level.levelgen.Heightmap.Types type,
                    int x, int z, int result);
        }
        public HeightQueryObserver heightQueryObserver;

        public RegionView(Driver driver, ChunkPos center,
                java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> chunks) {
            this(driver, center, chunks, net.minecraft.world.level.lighting.LevelLightEngine.EMPTY);
        }

        public RegionView(Driver driver, ChunkPos center,
                java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> chunks,
                net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates) {
            this(driver, center, chunks,
                    net.minecraft.world.level.lighting.LevelLightEngine.EMPTY, templates);
        }

        public RegionView(Driver driver, ChunkPos center,
                java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> chunks,
                net.minecraft.world.level.lighting.LevelLightEngine lightEngine) {
            this(driver, center, chunks, lightEngine, null);
        }

        public RegionView(Driver driver, ChunkPos center,
                java.util.Map<Long, net.minecraft.world.level.chunk.ChunkAccess> chunks,
                net.minecraft.world.level.lighting.LevelLightEngine lightEngine,
                net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates) {
            this.driver = driver;
            this.center = center;
            this.chunks = chunks;
            this.primitiveChunks = chunks instanceof it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>
                    ? (it.unimi.dsi.fastutil.longs.Long2ObjectMap<net.minecraft.world.level.chunk.ChunkAccess>) chunks
                    : null;
            this.lightEngine = java.util.Objects.requireNonNull(lightEngine, "lightEngine");
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!chunks.containsKey(ChunkPos.pack(center.x() + dx, center.z() + dz))) {
                        throw new IllegalArgumentException("radius-1 chunk missing at "
                                + (center.x() + dx) + "," + (center.z() + dz));
                    }
                }
            }
            this.random = driver.randomState().getOrCreateRandomFactory(
                    Identifier.withDefaultNamespace("worldgen_region_random"))
                    .at(center.getWorldPosition());
            this.blockTicks = new net.minecraft.world.ticks.WorldGenTickAccess<>(
                    p -> chunkAt(p).getBlockTicks());
            this.fluidTicks = new net.minecraft.world.ticks.WorldGenTickAccess<>(
                    p -> chunkAt(p).getFluidTicks());
            this.level = new RegionLevel();
            this.entityLevel = HeadlessEntityServerLevel.create(driver, level, templates);
            this.biomeManager = new net.minecraft.world.level.biome.BiomeManager(
                    level, net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(driver.seed()));
        }

        public net.minecraft.world.level.WorldGenLevel level() {
            return level;
        }

        private net.minecraft.world.level.chunk.ChunkAccess chunkOrNull(int cx, int cz) {
            long key = ChunkPos.pack(cx, cz);
            net.minecraft.world.level.chunk.ChunkAccess raw = primitiveChunks != null
                    ? primitiveChunks.get(key) : chunks.get(key);
            if (raw == null) {
                return null;
            }
            if (raw instanceof ReadOnlyFullChunk) {
                return raw;
            }
            boolean fullByStatus =
                    raw.getPersistedStatus().getChunkType()
                            == net.minecraft.world.level.chunk.status.ChunkType.LEVELCHUNK;
            if (!fullByStatus) {
                return raw;
            }
            return readOnlyFullViews.computeIfAbsent(
                    key,
                    ignored -> new ReadOnlyFullChunk(
                            raw, driver.height(), factory(driver.access())));
        }

        private net.minecraft.world.level.chunk.ChunkAccess chunkAt(int cx, int cz) {
            net.minecraft.world.level.chunk.ChunkAccess c = chunkOrNull(cx, cz);
            if (c == null) {
                throw new IllegalStateException("Requested chunk unavailable during world generation: "
                        + cx + "," + cz + " (center " + center + ")");
            }
            return c;
        }

        private net.minecraft.world.level.chunk.ChunkAccess chunkAt(BlockPos p) {
            return chunkAt(p.getX() >> 4, p.getZ() >> 4);
        }

        private boolean ensureCanWrite(BlockPos p) {
            return Math.abs(center.x() - (p.getX() >> 4)) <= 1
                    && Math.abs(center.z() - (p.getZ() >> 4)) <= 1;
        }

        /** WorldGenRegion.setBlock(pos, state, flags, recursion), javap body minus POI. */
        private boolean setBlock(BlockPos p, net.minecraft.world.level.block.state.BlockState state,
                int flags) {
            if (!ensureCanWrite(p)) {
                return false;
            }
            net.minecraft.world.level.chunk.ChunkAccess chunk = chunkAt(p);
            net.minecraft.world.level.block.state.BlockState old = chunk.setBlockState(p, state, flags);
            if (state.hasBlockEntity()) {
                if (chunk.getPersistedStatus().getChunkType()
                        == net.minecraft.world.level.chunk.status.ChunkType.LEVELCHUNK) {
                    // Vanilla WorldGenRegion constructs the real block entity
                    // for LEVELCHUNK dependencies. ReadOnlyFullChunk then
                    // discards setBlockEntity exactly like
                    // ImposterProtoChunk(false), but constructor/hook behavior
                    // still occurs.
                    net.minecraft.world.level.block.entity.BlockEntity blockEntity =
                            ((net.minecraft.world.level.block.EntityBlock) state.getBlock())
                                    .newBlockEntity(p, state);
                    if (blockEntity != null) {
                        chunk.setBlockEntity(blockEntity);
                    } else {
                        chunk.removeBlockEntity(p);
                    }
                } else {
                    // Proto chunks store a DUMMY tag until first read.
                    net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                    tag.putInt("x", p.getX());
                    tag.putInt("y", p.getY());
                    tag.putInt("z", p.getZ());
                    tag.putString("id", "DUMMY");
                    chunk.setBlockEntityNbt(tag);
                }
            } else if (old != null && old.hasBlockEntity()) {
                chunk.removeBlockEntity(p);
            }
            if ((flags & 16) == 0) {
                BlockPos post = state.getPostProcessPos(level, p);
                if (post != null) {
                    chunkAt(post).markPosForPostProcessing(post);
                }
            }
            return true;
        }

        /**
         * WorldGenRegion.getBlockEntity(pos), javap body (D8 follow-up): a
         * real block entity created via setBlock only exists as DUMMY nbt on
         * the proto chunk (see setBlock above); vanilla lazily promotes it to
         * a real BlockEntity on first read and caches it on the chunk, so
         * every RNG-consuming configuration hook a feature runs against a
         * just-placed block entity (chest loot-table seed roll, spawner
         * entity-id roll, ...) actually executes instead of being silently
         * skipped. Mirrors WorldGenRegion bytecode: try the chunk's real
         * block-entity map first, then the pending nbt map (DUMMY -&gt;
         * EntityBlock.newBlockEntity, otherwise BlockEntity.loadStatic),
         * caching whatever gets created via chunk.setBlockEntity.
         */
        private net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p) {
            net.minecraft.world.level.chunk.ChunkAccess chunk = chunkAt(p);
            net.minecraft.world.level.block.entity.BlockEntity existing = chunk.getBlockEntity(p);
            if (existing != null) {
                return traceBlockEntityQuery(p, existing);
            }
            net.minecraft.nbt.CompoundTag tag = chunk.getBlockEntityNbt(p);
            net.minecraft.world.level.block.state.BlockState state = chunk.getBlockState(p);
            if (tag != null) {
                net.minecraft.world.level.block.entity.BlockEntity created;
                if ("DUMMY".equals(tag.getStringOr("id", ""))) {
                    created = state.hasBlockEntity()
                            ? ((net.minecraft.world.level.block.EntityBlock) state.getBlock())
                                    .newBlockEntity(p, state)
                            : null;
                } else {
                    created = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                            p, state, tag, driver.access());
                }
                if (created != null) {
                    chunk.setBlockEntity(created);
                    return traceBlockEntityQuery(p, created);
                }
            }
            return traceBlockEntityQuery(p, null);
        }

        private net.minecraft.world.level.block.entity.BlockEntity traceBlockEntityQuery(
                BlockPos p,
                net.minecraft.world.level.block.entity.BlockEntity result) {
            if (blockEntityQueryDebug != null) {
                blockEntityQueryDebug.accept(p, result);
            }
            return result;
        }

        private boolean addGeneratedEntity(
                net.minecraft.world.entity.Entity entity) {
            int cx = net.minecraft.core.SectionPos.blockToSectionCoord(
                    entity.getBlockX());
            int cz = net.minecraft.core.SectionPos.blockToSectionCoord(
                    entity.getBlockZ());
            chunkAt(cx, cz).addEntity(entity);
            return true;
        }

        /**
         * The WorldGenLevel this view hands to features and structures. It
         * used to be a java.lang.reflect.Proxy whose handler switched on the
         * method name; this class is that handler written out, method by
         * method, so calls no longer box arguments or go through
         * Proxy.invokeDefault. The mapping is one to one: every method the
         * handler answered is overridden here with the same body, every
         * default method it passed through stays inherited (so it dispatches
         * back into these overrides exactly as the proxy re-entered its
         * handler), and every abstract method it did not handle throws
         * UnsupportedOperationException as the handler did.
         */
        private final class RegionLevel implements net.minecraft.world.level.WorldGenLevel {
            @Override
            public String toString() {
                return "RegionView[" + center + "]";
            }

            @Override
            public long getSeed() {
                return driver.seed();
            }

            @Override
            public net.minecraft.server.level.ServerLevel getLevel() {
                // WorldGenRegion exposes its backing ServerLevel. Kestrel has
                // no live ServerLevel during headless generation, but structure
                // pieces and a few template/entity hooks still require the
                // concrete return type. Supply the minimal read-only shell so
                // those paths can complete without starting server/chunk-source
                // machinery.
                return entityLevel;
            }

            @Override
            public net.minecraft.world.DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
                return new net.minecraft.world.DifficultyInstance(
                        net.minecraft.world.Difficulty.NORMAL, 0L, 0L, 0.0F);
            }

            @Override
            public void setCurrentlyGenerating(java.util.function.Supplier<String> supplier) {
            }

            @Override
            public void playSound(net.minecraft.world.entity.Entity except, BlockPos pos,
                    net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source,
                    float volume, float pitch) {
            }

            @Override
            public void playSound(net.minecraft.world.entity.Entity except, BlockPos pos,
                    net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source) {
            }

            @Override
            public void addParticle(net.minecraft.core.particles.ParticleOptions particle,
                    double x, double y, double z, double xd, double yd, double zd) {
            }

            @Override
            public void levelEvent(net.minecraft.world.entity.Entity source, int type, BlockPos pos, int data) {
            }

            @Override
            public void levelEvent(int type, BlockPos pos, int data) {
            }

            @Override
            public void gameEvent(net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
                    net.minecraft.world.phys.Vec3 pos,
                    net.minecraft.world.level.gameevent.GameEvent.Context context) {
            }

            @Override
            public void gameEvent(ResourceKey<net.minecraft.world.level.gameevent.GameEvent> event,
                    BlockPos pos, net.minecraft.world.level.gameevent.GameEvent.Context context) {
            }

            @Override
            public void gameEvent(net.minecraft.world.entity.Entity source,
                    net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
                    BlockPos pos) {
            }

            @Override
            public void gameEvent(net.minecraft.world.entity.Entity source,
                    net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
                    net.minecraft.world.phys.Vec3 pos) {
            }

            @Override
            public void gameEvent(net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
                    BlockPos pos, net.minecraft.world.level.gameevent.GameEvent.Context context) {
            }

            @Override
            public net.minecraft.world.level.chunk.ChunkAccess getChunk(int cx, int cz) {
                return chunkAt(cx, cz);
            }

            @Override
            public net.minecraft.world.level.chunk.ChunkAccess getChunk(int cx, int cz,
                    net.minecraft.world.level.chunk.status.ChunkStatus status, boolean required) {
                net.minecraft.world.level.chunk.ChunkAccess c = chunkOrNull(cx, cz);
                if (c == null && required) {
                    chunkAt(cx, cz);
                }
                return c;
            }

            @Override
            public boolean hasChunk(int cx, int cz) {
                return chunkOrNull(cx, cz) != null;
            }

            @Override
            public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
                return chunkAt(pos).getBlockState(pos);
            }

            @Override
            public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
                return chunkAt(pos).getFluidState(pos);
            }

            @Override
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
                return RegionView.this.getBlockEntity(pos);
            }

            @Override
            public net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes() {
                // Mob.finalizeSpawn receives the WorldGenLevel itself, not
                // getLevel(). Structure variants (for example village cats)
                // consult environment attributes before insertion, so expose
                // the same non-null attribute system as the headless
                // ServerLevel shell.
                return entityLevel.environmentAttributes();
            }

            @Override
            public net.minecraft.world.level.biome.BiomeManager getBiomeManager() {
                return biomeManager;
            }

            @Override
            public int getRawBrightness(BlockPos readPos, int amount) {
                boolean watched = LightReadDiag.watching(readPos);
                if (watched) {
                    LightReadDiag.op("read-pre", lightEngine, readPos.getX() >> 4,
                            readPos.getY() >> 4, readPos.getZ() >> 4);
                }
                int brightness = net.minecraft.world.level.WorldGenLevel.super.getRawBrightness(readPos, amount);
                LightReadDiag.raw(lightEngine, readPos, amount, brightness);
                if (watched) {
                    LightReadDiag.op("read-post", lightEngine, readPos.getX() >> 4,
                            readPos.getY() >> 4, readPos.getZ() >> 4);
                }
                return brightness;
            }

            @Override
            public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getBiome(BlockPos p) {
                var biome = biomeManager.getBiome(p);
                if (biomeQueryDebug != null) {
                    biomeQueryDebug.accept(p, biome);
                }
                return biome;
            }

            @Override
            public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(
                    int x, int y, int z) {
                return driver.generator().getBiomeSource().getNoiseBiome(
                        x, y, z, driver.randomState().sampler());
            }

            @Override
            public boolean ensureCanWrite(BlockPos pos) {
                return RegionView.this.ensureCanWrite(pos);
            }

            @Override
            public boolean setBlock(BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
                    int flags, int recursionLeft) {
                return RegionView.this.setBlock(pos, state, flags);
            }

            @Override
            public boolean removeBlock(BlockPos pos, boolean movedByPiston) {
                return RegionView.this.setBlock(pos,
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            }

            @Override
            public boolean destroyBlock(BlockPos p, boolean dropResources,
                    net.minecraft.world.entity.Entity breaker, int updateLimit) {
                net.minecraft.world.level.block.state.BlockState st = chunkAt(p).getBlockState(p);
                if (st.isAir()) {
                    return false;
                }
                return RegionView.this.setBlock(p, chunkAt(p).getFluidState(p).createLegacyBlock(), 3);
            }

            @Override
            public boolean addFreshEntity(net.minecraft.world.entity.Entity entity) {
                return addGeneratedEntity(entity);
            }

            @Override
            public void addFreshEntityWithPassengers(net.minecraft.world.entity.Entity entity) {
                entity.getSelfAndPassengers().forEach(RegionView.this::addGeneratedEntity);
            }

            @Override
            public boolean isClientSide() {
                return false;
            }

            @Override
            public net.minecraft.core.RegistryAccess registryAccess() {
                return driver.access();
            }

            @Override
            public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() {
                return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
            }

            @Override
            public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
                return blockTicks;
            }

            @Override
            public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
                return fluidTicks;
            }

            @Override
            public int getSeaLevel() {
                return driver.generator().getSeaLevel();
            }

            @Override
            public net.minecraft.world.level.border.WorldBorder getWorldBorder() {
                return worldBorder;
            }

            @Override
            public net.minecraft.util.RandomSource getRandom() {
                return random;
            }

            @Override
            public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z) {
                net.minecraft.world.level.chunk.ChunkAccess heightChunk = chunkAt(x >> 4, z >> 4);
                net.minecraft.world.level.levelgen.Heightmap.Types effectiveType = type;
                // Vanilla WorldGenRegion may receive an ImposterProtoChunk when
                // a dependency is already a LEVELCHUNK.
                // ImposterProtoChunk.getHeight() maps the two worldgen-only
                // maps onto their final equivalents. Our headless grid
                // deliberately keeps the same ProtoChunk object through the
                // lifecycle, so mirror that wrapper behavior from the
                // persisted status.
                if (heightChunk.getPersistedStatus().getChunkType()
                        == net.minecraft.world.level.chunk.status.ChunkType.LEVELCHUNK) {
                    if (type == net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG) {
                        effectiveType = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE;
                    } else if (type == net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG) {
                        effectiveType = net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR;
                    }
                }
                int result = heightQueryOverride != null
                        ? heightQueryOverride.get(type, x, z)
                        : heightChunk.getHeight(effectiveType, x & 15, z & 15) + 1;
                if (heightQueryObserver != null) {
                    heightQueryObserver.accept(type, x, z, result);
                }
                return result;
            }

            @Override
            public int getHeight() {
                return driver.height().getHeight();
            }

            @Override
            public int getMinY() {
                return driver.height().getMinY();
            }

            @Override
            public net.minecraft.world.level.dimension.DimensionType dimensionType() {
                return driver.dimensionType().value();
            }

            @Override
            public boolean isStateAtPosition(BlockPos pos,
                    java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> predicate) {
                return predicate.test(chunkAt(pos).getBlockState(pos));
            }

            @Override
            public boolean isFluidAtPosition(BlockPos pos,
                    java.util.function.Predicate<net.minecraft.world.level.material.FluidState> predicate) {
                return predicate.test(chunkAt(pos).getFluidState(pos));
            }

            @Override
            public List<net.minecraft.world.entity.Entity> getEntities(net.minecraft.world.entity.Entity except,
                    net.minecraft.world.phys.AABB box,
                    java.util.function.Predicate<? super net.minecraft.world.entity.Entity> selector) {
                return List.of();
            }

            @Override
            public <T extends net.minecraft.world.entity.Entity> List<T> getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, T> type,
                    net.minecraft.world.phys.AABB box, java.util.function.Predicate<? super T> selector) {
                return List.of();
            }

            @Override
            public List<net.minecraft.world.entity.Entity> getEntities(net.minecraft.world.entity.Entity except,
                    net.minecraft.world.phys.AABB box) {
                return List.of();
            }

            @Override
            public List<? extends net.minecraft.world.entity.player.Player> players() {
                return List.of();
            }

            @Override
            public int getSkyDarken() {
                return 0;
            }

            @Override
            public long nextSubTickCount() {
                return subTick.getAndIncrement();
            }

            @Override
            public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
                return lightEngine;
            }

            @Override
            public long getGameTime() {
                // LevelAccessor.createTick reads getLevelData().getGameTime()
                // for scheduled ticks; worldgen entities read it through
                // getLevel() (see WorldgenClock).
                return driver.clock().gameTime();
            }

            @Override
            public net.minecraft.world.level.chunk.ChunkSource getChunkSource() {
                throw new UnsupportedOperationException("RegionView: getChunkSource");
            }

            @Override
            public net.minecraft.world.level.storage.LevelData getLevelData() {
                throw new UnsupportedOperationException("RegionView: getLevelData");
            }

            @Override
            public net.minecraft.server.MinecraftServer getServer() {
                throw new UnsupportedOperationException("RegionView: getServer");
            }
        }
    }

    /** Possible-biome set over the 3x3 chunk neighbourhood (vanilla shape). */
    static java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> collectPossibleBiomes(
            Driver driver, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> set =
                new java.util.HashSet<>();
        chunk.collectBiomesInPalette(set);
        net.minecraft.world.level.ChunkPos pos = chunk.getPos();
        net.minecraft.world.level.biome.Climate.Sampler sampler = driver.randomState().sampler();
        net.minecraft.world.level.biome.BiomeSource source = driver.generator().getBiomeSource();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int qx = 0; qx < 4; qx++) {
                    for (int qz = 0; qz < 4; qz++) {
                        for (int sy = -4; sy < 20; sy++) {
                            set.add(source.getNoiseBiome(
                                    ((pos.x() + dx) << 2) + qx, sy << 2,
                                    ((pos.z() + dz) << 2) + qz, sampler));
                        }
                    }
                }
            }
        }
        return set;
    }

    /** Run BIOMES + NOISE on one chunk, writing DENSE into the store. */
    public static void generate(Driver driver, SectionStore store, int chunkX, int chunkZ) {
        writeChunk(noise(driver, chunkX, chunkZ), store, chunkX, chunkZ);
    }

    /** Run BIOMES + NOISE + SURFACE on one chunk, writing DENSE into the store. */
    public static void generateSurfaced(Driver driver, SectionStore store, int chunkX, int chunkZ) {
        writeChunk(surface(driver, noise(driver, chunkX, chunkZ)), store, chunkX, chunkZ);
    }

    /** Write a filled chunk into a store (used by generate* and the live server preview). */
    public static void writeStore(Driver driver,
            net.minecraft.world.level.chunk.ChunkAccess filled, SectionStore store,
            int chunkX, int chunkZ) {
        writeChunk(filled, store, chunkX, chunkZ);
    }

    /** Write a FULL live chunk straight into compact resting sections. */
    private static final java.lang.invoke.MethodHandle PALETTED_DATA;
    private static final java.lang.invoke.MethodHandle DATA_STORAGE;
    private static final java.lang.invoke.MethodHandle DATA_PALETTE;

    static {
        try {
            Class<?> dataClass = Class.forName(
                    "net.minecraft.world.level.chunk.PalettedContainer$Data");
            java.lang.invoke.MethodHandles.Lookup container = java.lang.invoke.MethodHandles
                    .privateLookupIn(net.minecraft.world.level.chunk.PalettedContainer.class,
                            java.lang.invoke.MethodHandles.lookup());
            java.lang.invoke.MethodHandles.Lookup data = java.lang.invoke.MethodHandles
                    .privateLookupIn(dataClass, java.lang.invoke.MethodHandles.lookup());
            PALETTED_DATA = container.findGetter(
                    net.minecraft.world.level.chunk.PalettedContainer.class, "data", dataClass)
                    .asType(java.lang.invoke.MethodType.methodType(Object.class,
                            net.minecraft.world.level.chunk.PalettedContainer.class));
            DATA_STORAGE = data.findGetter(dataClass, "storage", net.minecraft.util.BitStorage.class)
                    .asType(java.lang.invoke.MethodType.methodType(
                            net.minecraft.util.BitStorage.class, Object.class));
            DATA_PALETTE = data.findGetter(dataClass, "palette",
                            net.minecraft.world.level.chunk.Palette.class)
                    .asType(java.lang.invoke.MethodType.methodType(
                            net.minecraft.world.level.chunk.Palette.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Every cell of one section in {@link SectionStore#index} order, the same
     * values as {@code section.getBlockState(x, y, z)} per cell. Vanilla's
     * block container indexes cells as {@code (y << 8) | (z << 4) | x}, the
     * store's order, so one unpack of the packed ids plus one palette lookup
     * per distinct id replaces 4096 coordinate lookups.
     */
    @SuppressWarnings("unchecked")
    static void readSectionCells(LevelChunkSection section, BlockState[] out, int[] ids) {
        try {
            Object data = PALETTED_DATA.invokeExact(
                    (net.minecraft.world.level.chunk.PalettedContainer<?>) section.getStates());
            net.minecraft.util.BitStorage storage = (net.minecraft.util.BitStorage) DATA_STORAGE.invokeExact(data);
            net.minecraft.world.level.chunk.Palette<?> rawPalette =
                    (net.minecraft.world.level.chunk.Palette<?>) DATA_PALETTE.invokeExact(data);
            net.minecraft.world.level.chunk.Palette<BlockState> palette =
                    (net.minecraft.world.level.chunk.Palette<BlockState>) rawPalette;
            if (storage.getSize() != SectionStore.CELLS) {
                throw new IllegalStateException("block container size " + storage.getSize());
            }
            storage.unpack(ids);
            int lastId = -1;
            BlockState last = null;
            for (int i = 0; i < SectionStore.CELLS; i++) {
                int id = ids[i];
                if (id != lastId) {
                    last = palette.valueFor(id);
                    lastId = id;
                }
                out[i] = last;
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static void writeStoreCompact(Driver driver,
            net.minecraft.world.level.chunk.ChunkAccess filled, SectionStore store,
            int chunkX, int chunkZ) {
        writeStoreCompact(driver, filled, store, chunkX, chunkZ, false);
    }

    /**
     * @param exclusive the caller guarantees nothing but this path writes
     *     {@code store}, and that every {@link TrackedSection} flag was set
     *     or cleared against this same store. Then a clean section's last
     *     publication is still exactly what publishing it again would leave
     *     there, so it is skipped; published sections are marked clean.
     */
    static void writeStoreCompact(Driver driver,
            net.minecraft.world.level.chunk.ChunkAccess filled, SectionStore store,
            int chunkX, int chunkZ, boolean exclusive) {
        LevelChunkSection[] sections = filled.getSections();
        int[] ids = new int[SectionStore.CELLS];
        for (int si = 0; si < sections.length; si++) {
            TrackedSection tracked = exclusive && sections[si] instanceof TrackedSection t ? t : null;
            if (tracked != null) {
                if (!tracked.dirty) {
                    continue;
                }
                tracked.dirty = false;
            }
            BlockState[] cells = new BlockState[SectionStore.CELLS];
            readSectionCells(sections[si], cells, ids);
            int sectionY = (driver.height().getMinY() >> 4) + si;
            long key = SectionPos.asLong(chunkX, sectionY, chunkZ);
            // Publication is the store's call, not this writer's: it owns
            // the C1 mutation provenance that decides which cells this
            // generated copy may claim. A section nobody has edited is
            // replaced wholesale into compact resting form; a section
            // carrying live-layer writes stays dense and keeps them, taking
            // this pass's output only for the cells it does not own.
            store.putGeneratedCells(key, cells);
        }
    }
    /**
     * G2 step 5: run the step-4 NOISE slice on {@code threads} workers,
     * writing DENSE into the store. Same per-chunk result as
     * {@link #generate} for every chunk; only the schedule differs (P1 is
     * pure through CARVERS, trap 13: nothing here writes a neighbour).
     *
     * @param chunks 2-element {@code {chunkX, chunkZ}} arrays, in the order
     *               the caller wants them submitted. Order does not reach
     *               the output.
     */
    public static void generateParallel(Driver driver, SectionStore store,
            java.util.List<int[]> chunks, int threads) {
        generateParallelStats(driver, store, chunks, threads);
    }

    /**
     * {@link #generateParallel} plus per-worker counters, for the step-5
     * throughput measurement. The equality gate runs through this same
     * code path (the void entry point is a delegate, not a second
     * implementation).
     */
    public static ParallelStats generateParallelStats(Driver driver, SectionStore store,
            java.util.List<int[]> chunks, int threads) {
        if (chunks.isEmpty()) {
            return new ParallelStats(threads, 0L, new long[Math.max(threads, 1)],
                    new int[Math.max(threads, 1)]);
        }
        // 1 thread (or 1 chunk) stays on the calling thread: the exact
        // step-4 path, fork or not, so the 1-thread row of the gate is the
        // already-verified single-threaded driver.
        if (threads <= 1 || chunks.size() == 1) {
            long start = System.nanoTime();
            for (int[] c : chunks) {
                generate(driver, store, c[0], c[1]);
            }
            long elapsed = System.nanoTime() - start;
            return new ParallelStats(1, elapsed, new long[]{elapsed},
                    new int[]{chunks.size()});
        }

        // Forked per-thread state, vanilla's pre-parallel shape
        // (ChunkGeneratorStructureState builds one RandomState; here it is
        // one per worker instead of one shared). RandomState memoizes
        // NormalNoise instances and positional random factories in lazy
        // maps: that is the only mutable state the NOISE slice touches, so
        // a fork per thread keeps every memo thread-confined. The fork is
        // built from the same seed and the same immutable registries, so it
        // is value-identical to the parent, not merely equivalent.
        // ThreadLocal, not a preallocated array: a worker's fork is created
        // on the worker thread itself, before its first chunk.
        ThreadLocal<Driver> forked = ThreadLocal.withInitial(() -> fork(driver));

        // Stats slots: one distinct slot per pool thread, claimed on that
        // thread's first task. Plain arrays are safe here because only the
        // owning thread ever touches its slot, and the join below publishes.
        java.util.concurrent.atomic.AtomicInteger workerSeq =
                new java.util.concurrent.atomic.AtomicInteger();
        ThreadLocal<Integer> slotOf = ThreadLocal.withInitial(workerSeq::getAndIncrement);
        java.util.concurrent.atomic.AtomicReference<Throwable> firstError =
                new java.util.concurrent.atomic.AtomicReference<>();
        long[] busyNanos = new long[threads];
        int[] chunksPerThread = new int[threads];
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
                    Thread t = new Thread(r, "kestrel-noise");
                    t.setDaemon(true);
                    return t;
                });

        long start = System.nanoTime();
        java.util.List<java.util.concurrent.Future<?>> futures =
                new java.util.ArrayList<>(chunks.size());
        for (int[] c : chunks) {
            futures.add(pool.submit(() -> {
                // Eager per-chunk memos on the worker thread: the ProtoChunk
                // and the NoiseChunk/aquifer hanging off it (trap 17) are
                // created and consumed inside noise(), on this thread.
                // Nothing memoized by this chunk is visible to another.
                long t0 = System.nanoTime();
                net.minecraft.world.level.chunk.ChunkAccess filled =
                        noise(forked.get(), c[0], c[1]);
                long noiseNanos = System.nanoTime() - t0;
                int slot = slotOf.get();
                busyNanos[slot] += noiseNanos;
                chunksPerThread[slot]++;
                // SectionStore is single-threaded by contract (no
                // synchronization in the class). Vanilla's own section
                // writes happen inside doFill, on completely private
                // sections of a private ProtoChunk; the store handoff is the
                // only shared sink, so it is the one serial section.
                synchronized (store) {
                    writeChunk(filled, store, c[0], c[1]);
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                firstError.compareAndSet(null, e);
            } catch (ExecutionException e) {
                firstError.compareAndSet(null, e.getCause());
            }
        }
        long wall = System.nanoTime() - start;
        pool.shutdown();
        Throwable err = firstError.get();
        if (err != null) {
            throw new IllegalStateException("parallel noise failed", err);
        }
        return new ParallelStats(threads, wall, busyNanos, chunksPerThread);
    }

    /**
     * Fork a driver for one worker thread: same generator, height accessor,
     * structure stub and registry access, a private {@link RandomState} over
     * the same seed. Only the RandomState is per-thread (see
     * {@link #generateParallelStats}); the rest is immutable after
     * {@link #load}, which is why the generator and the structures-off stub
     * can be shared: with structures off
     * {@code StructureManager.startsForStructure} reads an immutable empty
     * reference map (the stub chunk's {@code getAllReferences}).
     */
    public static Driver fork(Driver driver) {
        return new Driver(driver.generator(),
                RandomState.create(driver.access(), driver.dimension().settings(),
                        driver.seed()),
                driver.height(), driver.structures(), driver.access(), driver.seed(),
                driver.clock(), driver.dimension(), driver.dimensionType());
    }

    /**
     * What one {@link #generateParallelStats} run did: wall time, and per
     * worker thread the summed NOISE time and chunk count. Index i is one
     * pool thread (claimed on its first task), not a stable thread name; a
     * run that uses fewer pool threads than requested leaves the higher
     * slots at zero. Wall and busy differ by the queue wait: busy is the
     * NOISE work, wall includes waiting for a turn.
     */
    public record ParallelStats(int threads, long wallNanos, long[] busyNanos,
            int[] chunksPerThread) {

        /** Chunks completed by this run. */
        public int chunks() {
            int n = 0;
            for (int c : chunksPerThread) {
                n += c;
            }
            return n;
        }

        /** Wall-clock ms per chunk, the throughput number to compare. */
        public double wallMsPerChunk() {
            int n = chunks();
            return n == 0 ? 0.0 : wallNanos / 1e6 / n;
        }

        /** Summed NOISE ms per chunk, i.e. the work without queue wait. */
        public double busyMsPerChunk() {
            int n = chunks();
            if (n == 0) {
                return 0.0;
            }
            long sum = 0L;
            for (long b : busyNanos) {
                sum += b;
            }
            return sum / 1e6 / n;
        }

        /** Chunks per second at this thread count. */
        public double chunksPerSecond() {
            int n = chunks();
            return wallNanos == 0 ? 0.0 : n / (wallNanos / 1e9);
        }

        /** One line for logs: counts + ms/chunk, both ways. */
        public String summary() {
            java.util.Locale root = java.util.Locale.ROOT;
            StringBuilder sb = new StringBuilder();
            sb.append("threads=").append(threads)
                    .append(" chunks=").append(chunks())
                    .append(" wall_ms_per_chunk=")
                    .append(String.format(root, "%.1f", wallMsPerChunk()))
                    .append(" busy_ms_per_chunk=")
                    .append(String.format(root, "%.1f", busyMsPerChunk()))
                    .append(" chunks_per_s=")
                    .append(String.format(root, "%.2f", chunksPerSecond()))
                    .append(" per_thread=");
            for (int i = 0; i < threads; i++) {
                sb.append(i == 0 ? "" : ",")
                        .append(chunksPerThread[i]).append("ck/")
                        .append(String.format(root, "%.1f", busyNanos[i] / 1e6)).append("ms");
            }
            return sb.toString();
        }
    }

    private static void writeChunk(
            net.minecraft.world.level.chunk.ChunkAccess filled, SectionStore store,
            int chunkX, int chunkZ) {
        LevelChunkSection[] sections = filled.getSections();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            // Sections array is bottom-up from minSectionY (ChunkAccess
            // numbers sections from the height accessor; index 0 is the
            // bottom section). No getY() on 26.2 LevelChunkSection.
            int sectionY = (store.getMinY() >> 4) + si;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        net.minecraft.world.level.block.state.BlockState st =
                                section.getBlockState(x, y, z);
                        if (st.isAir()) {
                            continue;
                        }
                        // Generated output, so it must not claim ownership of
                        // the cell (C1) and must not overwrite a claimed one.
                        store.writeGenerated(new BlockPos(chunkX * 16 + x, (sectionY << 4) + y,
                                chunkZ * 16 + z), st);
                    }
                }
            }
        }
    }

    public static PalettedContainerFactory factory(
            net.minecraft.core.RegistryAccess access) {
        // Record ctor wants strategies + codecs; the create(access) factory
        // builds them from the live registries (javap PalettedContainerFactory).
        return PalettedContainerFactory.create(access);
    }

    /** P1 hash of one generated chunk, comparable to the oracle P1 rows. */
    public static String p1(SectionStore store, int chunkX, int chunkZ) {
        return BlockHash.chunkHex(store, chunkX, chunkZ);
    }

    /** Keep the LevelDataAndDimensions import referenced for the doc trail. */
    @SuppressWarnings("unused")
    private static LevelDataAndDimensions.WorldDataAndGenSettings trail(
            LevelDataAndDimensions.WorldDataAndGenSettings in) {
        return in;
    }
}
