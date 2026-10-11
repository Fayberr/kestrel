package dev.kestrel.loop;

import dev.kestrel.collision.ShapeBoxCache;
import dev.kestrel.entity.EntityLevel;
import dev.kestrel.entity.EntityWorld;
import dev.kestrel.entity.KestrelEntity;
import dev.kestrel.entity.KestrelItemEntity;
import dev.kestrel.net.BlockChangePackets;
import dev.kestrel.net.Outbound;
import dev.kestrel.worldgen.HeadlessNoise;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * G30 slice 1 gate: Kestrel's live random ticks against vanilla's own
 * {@code ServerChunkCache.tickChunks} and {@code ServerLevel.tickChunk}.
 *
 * <p><b>The oracle.</b> A constructor-less vanilla {@code ServerLevel} over
 * real {@code LevelChunk}s (real sections, counters, heightmaps and tick
 * containers, a real light engine), a vanilla {@code ServerChunkCache} whose
 * private {@code tickChunks(profiler, timeDiff)} and
 * {@code broadcastChangedChunks} run as written, a vanilla {@code ChunkMap}
 * holding real {@code ChunkHolder}s and a real {@code PlayerMap}, and the
 * real {@code ChunkMap.DistanceManager} (its {@code TicketStorage},
 * simulation tracker and natural-spawn counter) fed through
 * {@code DistanceManager.addPlayer} and {@code removePlayer}. Players are
 * constructor-less {@code ServerPlayer}s whose connection records what
 * {@code ChunkHolder.broadcastChanges} sends them. Each game tick runs, in
 * {@code ServerLevel.tick}'s order: the clock, the block ticks, the fluid
 * ticks ({@code tickBlock}, {@code tickFluid}), the distance-manager update,
 * {@code tickChunks}, the broadcast, the block events, then the light
 * engine's queued work (vanilla's light thread runs between ticks).
 *
 * <p><b>Kestrel.</b> A {@link TickLoop} over the same world, with its
 * {@link RandomTicks} fed the same player operations and its
 * {@link RandomTicks.Environment} answering from the arena, ticked with
 * {@link TickLoop#tick}; the block publication slot collects
 * {@link BlockChangePackets} for each player.
 *
 * <p><b>Compared every tick, no tolerance:</b> the entity-ticking chunks in
 * iteration order; the level random's seed; {@code Level.randValue}; every
 * cell of every non-empty section of every loaded chunk; every section's
 * ticking block and fluid counts; the block packets each player receives,
 * raw, per chunk in order (vanilla visits the changed chunks in identity-hash
 * order, so chunks are matched by position); the items dropped; the level
 * events; and both pending block tick lists. Light packets are left out:
 * Kestrel sends none (S6).
 *
 * <p>Both worlds read light from an engine lit over the start state. The
 * oracle's then follows every write, Kestrel's stays as lit (Kestrel has no
 * live relighting, S6); the scenarios keep light-reading blocks away from
 * the writes that change light, and a divergence there would show up here.
 */
public final class RandomTickParityCheck {
    private static final long LEVEL_SEED = 0x6A30_5EED_0001L;
    private static final long WORLD_SEED = 1L;
    private static final int RAND_VALUE = 0x2F6B_1D35;
    private static final long START_TIME = 1000L;
    private static final int RAW = 2 | 16 | 512;
    private static final int FLOOR_Y = 63;

    private static final List<String> failures = new ArrayList<>();
    private static long checks;
    private static int scenarios;
    private static long ticksRun;
    private static HeadlessNoise.Driver driver;
    private static ReloadableServerRegistries.Holder loot;
    private static Holder<Biome> plains;
    private static net.minecraft.network.ProtocolInfo<ClientGamePacketListener> protocol;

    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private static Method tickBlock;
    private static Method tickFluid;
    private static Method tickChunks;
    private static Method broadcastChangedChunks;
    private static Method runBlockEvents;
    private static Method updatePlayerTickets;
    private static Field levelRandom;
    private static Field randValue;
    private static Field skyDarken;
    private static Field biomeManager;
    private static Field handlingTick;
    private static Field legacySeed;
    private static Field tickingBlockCount;
    private static Field tickingFluidCount;

    private RandomTickParityCheck() {
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(WORLD_SEED);
        driver = structural.driver();
        loot = LootTables.load(driver.access(), structural.resources());
        plains = driver.access().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(driver.access()));
        reflect();

        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState mycelium = Blocks.MYCELIUM.defaultBlockState();

        // ---- crops on hydrated and on dry farmland, until every crop is grown ----
        scenario("crops hydrated and dry", 1, 20, 150_000, a -> {
            a.floor(stone);
            field(a, -12, -12, true);
            field(a, 4, 4, false);
            a.player("p", 0.5, FLOOR_Y + 1, 0.5);
            a.until(RandomTickParityCheck::cropsGrown);
            a.after(arena -> arena.expect(cropsGrown(arena), "crops: not every crop grew to its last age"));
        });

        // ---- grass and mycelium spreading onto dirt ----
        scenario("grass and mycelium spread", 1, 3, 12_000, a -> {
            a.floor(stone);
            for (int x = -6; x <= 6; x++) {
                for (int z = -6; z <= 6; z++) {
                    a.set(x, FLOOR_Y, z, dirt);
                    a.set(x + 16, FLOOR_Y, z, dirt);
                }
            }
            a.set(0, FLOOR_Y, 0, grass);
            a.set(16, FLOOR_Y, 0, mycelium);
            a.player("p", 0.5, FLOOR_Y + 1, 0.5);
            a.after(arena -> {
                arena.expect(arena.count(Blocks.GRASS_BLOCK) > 5, "grass: spread onto fewer than 5 dirt blocks");
                arena.expect(arena.count(Blocks.MYCELIUM) > 5, "mycelium: spread onto fewer than 5 dirt blocks");
            });
        });

        // ---- grass and mycelium dying under an opaque block ----
        scenario("grass dies under stone", 1, 3, 12_000, a -> {
            a.floor(stone);
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    a.set(x, FLOOR_Y, z, x < 0 ? mycelium : grass);
                    a.set(x, FLOOR_Y + 1, z, stone);
                }
            }
            a.player("p", 0.5, FLOOR_Y + 2, 0.5);
            a.after(arena -> arena.expect(arena.count(Blocks.GRASS_BLOCK) == 0
                    && arena.count(Blocks.MYCELIUM) == 0, "grass under stone: some grass or mycelium survived"));
        });

        // ---- an oak tree with its log removed, until its leaves decay ----
        scenario("oak leaves decay", 1, 3, 30_000, a -> {
            a.floor(stone);
            tree(a, 0, 0);
            a.player("p", 8.5, FLOOR_Y + 1, 8.5);
            a.at(20, arena -> {
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 5; y++) {
                    arena.place(0, y, 0, Blocks.AIR.defaultBlockState());
                }
            });
            a.until(arena -> arena.tick > 30 && arena.count(Blocks.OAK_LEAVES) == 0);
            a.after(arena -> {
                arena.expect(arena.count(Blocks.OAK_LEAVES) == 0, "leaves: some leaves never decayed");
                arena.expect(!arena.kItems.isEmpty(), "leaves: no leaf dropped anything, the drop path is untested");
            });
        });

        // ---- two players whose simulation distances overlap, one walking away ----
        scenario("two players, one walks away", 7, 3, 700, a -> {
            a.simulationDistance(2);
            a.floor(stone);
            for (int cx = -7; cx <= 7; cx++) {
                for (int cz = -7; cz <= 7; cz++) {
                    int x = cx * 16 + 4;
                    int z = cz * 16 + 4;
                    for (int dx = 0; dx < 4; dx++) {
                        for (int dz = 0; dz < 4; dz++) {
                            a.set(x + dx, FLOOR_Y, z + dz, dz == 0 && dx == 0 ? grass : dirt);
                        }
                    }
                }
            }
            a.player("a", 8.5, FLOOR_Y + 1, 8.5);
            a.player("b", 56.5, FLOOR_Y + 1, 8.5);
            for (int t = 50; t < 650; t++) {
                double x = 56.5 + (t - 50) * 0.1;
                a.at(t, arena -> arena.move("b", x, FLOOR_Y + 1, 8.5 + (x - 56.5) * 0.5));
            }
            a.at(660, arena -> arena.remove("a"));
        });

        // ---- the game rule: 0, 3 and 20 ----
        for (int speed : new int[] {0, 3, 20}) {
            scenario("randomTickSpeed " + speed, 1, speed, 2_000, a -> {
                a.floor(stone);
                for (int x = -3; x <= 3; x++) {
                    for (int z = -3; z <= 3; z++) {
                        a.set(x, FLOOR_Y, z, x == 0 && z == 0 ? grass : dirt);
                    }
                }
                field(a, 6, 6, true);
                a.set(-8, FLOOR_Y + 1, -8, Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(LeavesBlock.DISTANCE, 7));
                a.player("p", 0.5, FLOOR_Y + 1, 0.5);
                if (speed == 0) {
                    a.after(arena -> arena.expect(arena.count(Blocks.GRASS_BLOCK) == 1
                            && arena.count(Blocks.OAK_LEAVES) == 1, "speed 0: something ticked"));
                }
            });
        }

        // ---- one randomly ticking section; a section whose only ticking block goes ----
        scenario("section counters", 1, 20, 3_000, a -> {
            a.floor(stone);
            // chunk (0, 0): one ticking section, high up.
            a.set(3, 100, 3, Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
            a.set(3, 101, 3, Blocks.WHEAT.defaultBlockState());
            // chunk (1, 0): a lone grass block under stone dies, the count reaches 0.
            a.set(20, 90, 3, grass);
            a.set(20, 91, 3, stone);
            // chunk (0, 1): a lone crop and its farmland removed by a live write.
            a.set(4, 110, 20, Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
            a.set(4, 111, 20, Blocks.WHEAT.defaultBlockState());
            a.at(200, arena -> {
                arena.place(4, 111, 20, Blocks.AIR.defaultBlockState());
                arena.place(4, 110, 20, stone);
            });
            a.player("p", 8.5, FLOOR_Y + 1, 8.5);
            a.after(arena -> {
                arena.expect(arena.loop.store().tickingBlockCount(SectionPos.asLong(1, 5, 0)) == 0,
                        "counters: the dead grass's section still counts a ticking block");
                arena.expect(arena.loop.store().tickingBlockCount(SectionPos.asLong(0, 6, 1)) == 0,
                        "counters: the removed crop's section still counts a ticking block");
                arena.expect(!arena.loop.store().isRandomlyTicking(SectionPos.asLong(0, 6, 1)),
                        "counters: the removed crop's section still randomly ticks");
            });
        });

        if (!failures.isEmpty()) {
            System.out.println("RandomTickParityCheck FAILED with " + failures.size() + " failure(s):");
            failures.stream().limit(40).forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
        System.out.println("RandomTickParityCheck: PASSED (" + checks + " checks, " + scenarios + " scenarios, "
                + ticksRun + " ticks)");
    }

    // =====================================================================
    // Scenario building blocks
    // =====================================================================

    /** A 5 by 5 farmland field with wheat; hydrated ones have a water source in the middle. */
    private static void field(Arena a, int x0, int z0, boolean hydrated) {
        for (int x = x0; x < x0 + 5; x++) {
            for (int z = z0; z < z0 + 5; z++) {
                if (hydrated && x == x0 + 2 && z == z0 + 2) {
                    a.set(x, FLOOR_Y, z, Blocks.WATER.defaultBlockState());
                    continue;
                }
                a.set(x, FLOOR_Y, z, Blocks.FARMLAND.defaultBlockState()
                        .setValue(FarmlandBlock.MOISTURE, hydrated ? 7 : 0));
                a.set(x, FLOOR_Y + 1, z, Blocks.WHEAT.defaultBlockState());
            }
        }
    }

    private static boolean cropsGrown(Arena a) {
        return a.allCrops(a.kestrelCells()) && a.allCrops(a.vanillaCells());
    }

    /** A small oak: a five-high log and leaves whose distances are already settled. */
    private static void tree(Arena a, int x0, int z0) {
        List<BlockPos> leaves = new ArrayList<>();
        for (int y = FLOOR_Y + 3; y <= FLOOR_Y + 6; y++) {
            int r = y >= FLOOR_Y + 5 ? 1 : 2;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0 && y <= FLOOR_Y + 5) {
                        continue;
                    }
                    if (Math.abs(dx) == r && Math.abs(dz) == r && r == 2 && y == FLOOR_Y + 4) {
                        continue;
                    }
                    leaves.add(new BlockPos(x0 + dx, y, z0 + dz));
                }
            }
        }
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 5; y++) {
            a.set(x0, y, z0, Blocks.OAK_LOG.defaultBlockState());
        }
        for (BlockPos pos : leaves) {
            int distance = Math.min(7, Math.abs(pos.getX() - x0) + Math.abs(pos.getZ() - z0)
                    + Math.max(0, pos.getY() - (FLOOR_Y + 5)));
            a.set(pos.getX(), pos.getY(), pos.getZ(), Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.DISTANCE, Math.max(1, distance)));
        }
    }

    interface Setup {
        void apply(Arena arena) throws Exception;
    }

    private static void scenario(String name, int radius, int tickSpeed, int maxTicks, Setup setup) throws Exception {
        scenarios++;
        long started = System.nanoTime();
        Arena a = new Arena(name, radius, tickSpeed);
        setup.apply(a);
        a.run(maxTicks);
        System.out.println("  " + name + ": " + a.tick + " ticks, " + a.kWrites + " writes, "
                + a.kItems.size() + " drops, " + (System.nanoTime() - started) / 1_000_000 + " ms");
    }

    // =====================================================================
    // The arena: one Kestrel loop and one vanilla oracle over the same world
    // =====================================================================

    static final class Arena {
        final String name;
        final int radius;
        final int loaded;
        final int tickSpeed;
        final int minY = driver.height().getMinY();
        final int height = driver.height().getHeight();
        final int sections = height >> 4;

        // vanilla
        final OracleWorld oracle;
        final Map<Long, LevelChunk> chunks = new LinkedHashMap<>();
        final OracleMap chunkMap;
        final OracleCache cache;
        final DistanceManager distance;
        final TicketStorage tickets = new TicketStorage();
        final Map<String, OraclePlayer> vPlayers = new LinkedHashMap<>();
        final Map<String, List<String>> vPackets = new LinkedHashMap<>();
        final List<String> vItems = new ArrayList<>();
        final List<String> vLevelEvents = new ArrayList<>();
        final RandomSequences vSequences = new RandomSequences();
        LevelLightEngine vLight;

        // Kestrel
        final SectionStore store;
        final TickLoop loop;
        final EntityWorld world;
        final Map<String, double[]> kPlayers = new LinkedHashMap<>();
        final Map<String, List<String>> kPackets = new LinkedHashMap<>();
        final List<String> kItems = new ArrayList<>();
        final RandomSequences kSequences = new RandomSequences();
        LevelLightEngine kLight;
        long kWrites;

        final Map<Integer, List<Consumer<Arena>>> triggers = new LinkedHashMap<>();
        final List<Consumer<Arena>> afterChecks = new ArrayList<>();
        Predicate<Arena> until;
        int simulationDistance = 1;
        int tick;
        final BlockState[] kCells = new BlockState[SectionStore.CELLS];

        Arena(String name, int radius, int tickSpeed) throws Exception {
            this.name = name;
            this.radius = radius;
            this.loaded = radius + 1;
            this.tickSpeed = tickSpeed;

            // ---- vanilla ----
            oracle = OracleWorld.create(this);
            chunkMap = OracleMap.create(oracle);
            Constructor<?> ctor = Class.forName("net.minecraft.server.level.ChunkMap$DistanceManager")
                    .getDeclaredConstructor(ChunkMap.class, TicketStorage.class, Executor.class, Executor.class);
            ctor.setAccessible(true);
            Executor direct = Runnable::run;
            distance = (DistanceManager) ctor.newInstance(chunkMap, tickets, direct, direct);
            // No PLAYER_LOADING tickets: loading is not what this gate compares.
            updatePlayerTickets.invoke(distance, -1);
            chunkMap.bind(distance);
            cache = OracleCache.create(oracle, chunkMap, distance);
            for (int cz = -loaded; cz <= loaded; cz++) {
                for (int cx = -loaded; cx <= loaded; cx++) {
                    ProtoChunk proto = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY,
                            driver.height(), HeadlessNoise.factory(driver.access()), null);
                    proto.setPersistedStatus(ChunkStatus.FULL);
                    LevelChunk chunk = new LevelChunk(oracle, proto, null);
                    chunk.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
                    Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR,
                            Heightmap.Types.WORLD_SURFACE));
                    chunks.put(chunk.getPos().pack(), chunk);
                    oracle.blockTicks.addContainer(chunk.getPos(), (LevelChunkTicks<Block>) chunk.getBlockTicks());
                    oracle.fluidTicks.addContainer(chunk.getPos(), (LevelChunkTicks<Fluid>) chunk.getFluidTicks());
                    if (Math.abs(cx) <= radius && Math.abs(cz) <= radius) {
                        chunkMap.addHolder(this, chunk);
                    }
                }
            }
            GameRules vRules = oracle.getGameRules();
            vRules.set(GameRules.RANDOM_TICK_SPEED, tickSpeed, null);
            vRules.set(GameRules.SPAWN_MOBS, false, null);
            oracle.setGameTime(START_TIME);

            // ---- Kestrel ----
            store = new SectionStore(minY, height);
            loop = new TickLoop(store);
            loop.setGameTime(START_TIME);
            loop.gameRules().set(GameRules.RANDOM_TICK_SPEED, tickSpeed, null);
            loop.gameRules().set(GameRules.SPAWN_MOBS, false, null);
            loop.captureLevelEvents();
            world = new EntityWorld(new ArenaEntities(this), null);
            loop.setEntityWorld(world);
            loop.setDetailedWriteListener((pos, oldState, newState, flags) -> kWrites++);
            loop.setBeforeEntityTracker(this::publishKestrel);
        }

        // ---- building ----

        /** A raw cell on both sides: flags 2|16|512, no reaction. */
        void set(int x, int y, int z, BlockState state) {
            BlockPos pos = new BlockPos(x, y, z);
            oracle.setBlock(pos, state, RAW);
            loop.setBlock(pos, state, RAW);
        }

        /** A live write on both sides, {@code setBlock(pos, state, 3)}. */
        void place(int x, int y, int z, BlockState state) {
            BlockPos pos = new BlockPos(x, y, z);
            oracle.setBlock(pos, state, 3);
            loop.setBlock(pos, state, 3);
        }

        void floor(BlockState state) {
            int extent = loaded * 16 + 15;
            for (int x = -loaded * 16; x <= extent; x++) {
                for (int z = -loaded * 16; z <= extent; z++) {
                    set(x, FLOOR_Y - 1, z, state);
                    set(x, FLOOR_Y, z, state);
                }
            }
        }

        void simulationDistance(int distance) {
            this.simulationDistance = distance;
        }

        void at(int tick, Consumer<Arena> action) {
            triggers.computeIfAbsent(tick, t -> new ArrayList<>()).add(action);
        }

        void after(Consumer<Arena> check) {
            afterChecks.add(check);
        }

        void until(Predicate<Arena> done) {
            this.until = done;
        }

        // ---- players, the same operations on both sides ----

        private final Map<String, double[]> pendingPlayers = new LinkedHashMap<>();

        void player(String id, double x, double y, double z) {
            pendingPlayers.put(id, new double[] {x, y, z});
        }

        private void addNow(String id, double x, double y, double z) throws Exception {
            OraclePlayer player = OraclePlayer.create(this, id, x, y, z);
            vPlayers.put(id, player);
            vPackets.put(id, new ArrayList<>());
            chunkMap.playerMap.addPlayer(player, false);
            player.setLastSectionPos(SectionPos.of(player));
            distance.addPlayer(SectionPos.of(player), player);
            distance.runAllUpdates(chunkMap);

            kPlayers.put(id, new double[] {x, y, z});
            kPackets.put(id, new ArrayList<>());
            loop.randomTicks().addPlayer(id, x, y, z, false);
        }

        void move(String id, double x, double y, double z) {
            OraclePlayer player = vPlayers.get(id);
            player.place(x, y, z);
            SectionPos old = player.getLastSectionPos();
            SectionPos now = SectionPos.of(player);
            if (old.asLong() != now.asLong()) {
                player.setLastSectionPos(now);
                distance.removePlayer(old, player);
                distance.addPlayer(now, player);
                distance.runAllUpdates(chunkMap);
            }
            kPlayers.put(id, new double[] {x, y, z});
            loop.randomTicks().movePlayer(id, x, y, z, false);
        }

        void remove(String id) {
            OraclePlayer player = vPlayers.remove(id);
            distance.removePlayer(player.getLastSectionPos(), player);
            chunkMap.playerMap.removePlayer(player);
            distance.runAllUpdates(chunkMap);
            kPlayers.remove(id);
            loop.randomTicks().removePlayer(id);
        }

        // ---- running ----

        void run(int maxTicks) throws Exception {
            start();
            while (tick < maxTicks) {
                tick++;
                for (Consumer<Arena> trigger : triggers.getOrDefault(tick, List.of())) {
                    trigger.accept(this);
                }
                vanillaTick();
                loop.tick();
                ticksRun++;
                if (!compare()) {
                    return;
                }
                if (until != null && tick % 50 == 0 && until.test(this)) {
                    break;
                }
            }
            for (Consumer<Arena> check : afterChecks) {
                check.accept(this);
            }
        }

        /** The worlds are built: light them, seed the randoms, add the players. */
        private void start() throws Exception {
            vLight = litEngine(chunks);
            oracle.light = vLight;
            cache.lightShell.target = vLight;
            Map<Long, ProtoChunk> mirror = mirror();
            kLight = litEngine(mirror);
            UNSAFE.putObject(oracle, UNSAFE.objectFieldOffset(levelRandom), RandomSource.create(LEVEL_SEED));
            loop.setLevelRandom(RandomSource.create(LEVEL_SEED));
            randValue.setInt(oracle, RAND_VALUE);
            loop.randomTicks().setRandValue(RAND_VALUE);
            UNSAFE.putObject(oracle, UNSAFE.objectFieldOffset(biomeManager),
                    new BiomeManager(oracle, BiomeManager.obfuscateSeed(WORLD_SEED)));
            skyDarken.setInt(oracle, 0);
            oracle.setRainLevel(0.0F);
            expect(oracle.getNoiseBiome(0, 16, 0).is(Biomes.PLAINS), "setup: the oracle's biome is not plains");

            Arena arena = this;
            loop.randomTicks().setEnvironment(new RandomTicks.Environment() {
                @Override public boolean isTickingChunk(long key) { return arena.within(key, radius); }
                @Override public boolean isLoadedChunk(long key) { return arena.within(key, loaded); }
                @Override public LevelLightEngine lightEngine() { return kLight; }
                @Override public int skyDarken() { return 0; }
                @Override public float rainLevel() { return 0.0F; }
                @Override public float thunderLevel() { return 0.0F; }
                @Override public Holder<Biome> noiseBiome(int qx, int qy, int qz) { return plains; }
                @Override public long biomeZoomSeed() { return BiomeManager.obfuscateSeed(WORLD_SEED); }
                @Override public RegistryAccess registryAccess() { return driver.access(); }
                @Override public int seaLevel() { return driver.generator().getSeaLevel(); }
                @Override public net.minecraft.world.attribute.EnvironmentAttributeSystem environmentAttributes() {
                    return oracle.environmentAttributes();
                }
                @Override public ReloadableServerRegistries.Holder lootTables() { return loot; }
                @Override public RandomSource randomSequence(Identifier key) {
                    return kSequences.get(key, WORLD_SEED);
                }
            });
            loop.randomTicks().setSimulationDistance(simulationDistance);
            distance.updateSimulationDistance(simulationDistance);

            // Forget the setup's writes on both sides before the first tick.
            broadcastChangedChunks.invoke(cache, InactiveProfiler.INSTANCE);
            BlockChangePackets.drain(loop);
            loop.drainBlockChanges(p -> { });
            for (Map.Entry<String, double[]> p : pendingPlayers.entrySet()) {
                addNow(p.getKey(), p.getValue()[0], p.getValue()[1], p.getValue()[2]);
            }
            for (List<String> packets : vPackets.values()) {
                packets.clear();
            }
            loop.clearLevelEvents();
            kWrites = 0;
        }

        boolean within(long key, int r) {
            return Math.abs(ChunkPos.getX(key)) <= r && Math.abs(ChunkPos.getZ(key)) <= r;
        }

        /** {@code ServerLevel.tick}, the parts this world has, in order. */
        private void vanillaTick() throws Exception {
            oracle.gameTime++;
            handlingTick.setBoolean(oracle, true);
            long time = oracle.gameTime;
            oracle.blockTicks.tick(time, 65536, (pos, block) -> invoke(tickBlock, oracle, pos, block));
            oracle.fluidTicks.tick(time, 65536, (pos, fluid) -> invoke(tickFluid, oracle, pos, fluid));
            distance.runAllUpdates(chunkMap);
            tickChunks.invoke(cache, InactiveProfiler.INSTANCE, 1L);
            broadcastChangedChunks.invoke(cache, InactiveProfiler.INSTANCE);
            runBlockEvents.invoke(oracle);
            handlingTick.setBoolean(oracle, false);
            vLight.runLightUpdates();
        }

        /** Kestrel's publication slot: each player's block packets, per chunk it tracks. */
        private void publishKestrel() {
            Map<Long, List<Outbound.Packet>> packets = BlockChangePackets.drain(loop);
            loop.drainBlockChanges(p -> { });
            for (Map.Entry<String, double[]> player : kPlayers.entrySet()) {
                List<String> out = kPackets.get(player.getKey());
                ChunkPos center = ChunkPos.containing(BlockPos.containing(player.getValue()[0],
                        player.getValue()[1], player.getValue()[2]));
                for (Map.Entry<Long, List<Outbound.Packet>> chunk : packets.entrySet()) {
                    if (tracks(center, chunk.getKey())) {
                        for (Outbound.Packet packet : chunk.getValue()) {
                            out.add(chunk.getKey() + "|" + fmt(packet.id(), packet.payload()));
                        }
                    }
                }
            }
        }

        /** The chunks a player at {@code center} tracks: vanilla's view of the view distance. */
        boolean tracks(ChunkPos center, long chunkKey) {
            return net.minecraft.server.level.ChunkTrackingView.of(center, VIEW_DISTANCE)
                    .contains(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
        }

        // ---- comparing ----

        boolean expect(boolean ok, String message) {
            checks++;
            if (!ok) {
                failures.add(name + " tick " + tick + ": " + message);
            }
            return ok;
        }

        /** Everything, this tick. False once something differs (the scenario stops there). */
        private boolean compare() throws Exception {
            int before = failures.size();
            List<Long> vOrder = new ArrayList<>();
            distance.forEachEntityTickingChunk(vOrder::add);
            List<Long> kOrder = loop.randomTicks().entityTickingChunks();
            expect(vOrder.equals(kOrder), "entity-ticking chunks differ: kestrel " + keys(kOrder)
                    + " vanilla " + keys(vOrder));
            long vSeed = seed(oracle.getRandom());
            long kSeed = seed(loop.levelRandom());
            expect(vSeed == kSeed, "level random differs: kestrel " + Long.toHexString(kSeed)
                    + " vanilla " + Long.toHexString(vSeed));
            int vRand = randValue.getInt(oracle);
            int kRand = loop.randomTicks().randValue();
            expect(vRand == kRand, "randValue differs: kestrel " + Integer.toHexString(kRand)
                    + " vanilla " + Integer.toHexString(vRand));
            compareCells();
            comparePackets();
            List<String> kEvents = new ArrayList<>();
            for (TickLoop.LevelEvent e : loop.dumpLevelEvents()) {
                kEvents.add(e.type() + ":" + e.pos().asLong() + ":" + e.data());
            }
            loop.clearLevelEvents();
            expect(kEvents.equals(vLevelEvents), "level events differ: kestrel " + kEvents + " vanilla " + vLevelEvents);
            vLevelEvents.clear();
            expect(kItems.equals(vItems), "drops differ: kestrel " + kItems + " vanilla " + vItems);
            expect(pendingKestrel().equals(pendingVanilla()), "pending block ticks differ: kestrel "
                    + pendingKestrel() + " vanilla " + pendingVanilla());
            return failures.size() == before;
        }

        private void compareCells() throws Exception {
            for (LevelChunk chunk : chunks.values()) {
                LevelChunkSection[] vSections = chunk.getSections();
                for (int i = 0; i < sections; i++) {
                    long key = SectionPos.asLong(chunk.getPos().x(), chunk.getSectionYFromSectionIndex(i),
                            chunk.getPos().z());
                    LevelChunkSection section = vSections[i];
                    boolean kPresent = store.copyCells(key, kCells);
                    if (!kPresent && section.hasOnlyAir()) {
                        continue;
                    }
                    for (int c = 0; c < SectionStore.CELLS; c++) {
                        BlockState k = kPresent ? kCells[c] : Blocks.AIR.defaultBlockState();
                        BlockState v = section.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15);
                        if (k != v) {
                            expect(false, "cell " + SectionPos.of(key).origin().offset(c & 15, (c >> 8) & 15,
                                    (c >> 4) & 15).toShortString() + ": kestrel " + k + " vanilla " + v);
                            return;
                        }
                    }
                    checks++;
                    int vBlocks = tickingBlockCount.getShort(section);
                    int vFluids = tickingFluidCount.getShort(section);
                    expect(store.tickingBlockCount(key) == vBlocks && store.tickingFluidCount(key) == vFluids,
                            "section " + SectionPos.of(key) + " ticking counts: kestrel "
                                    + store.tickingBlockCount(key) + "/" + store.tickingFluidCount(key)
                                    + " vanilla " + vBlocks + "/" + vFluids);
                }
            }
        }

        private void comparePackets() {
            for (String id : vPackets.keySet()) {
                List<String> v = byChunk(vPackets.get(id));
                List<String> k = byChunk(kPackets.getOrDefault(id, List.of()));
                expect(v.equals(k), "packets to " + id + " differ: kestrel " + k + " vanilla " + v);
                vPackets.get(id).clear();
            }
            for (List<String> k : kPackets.values()) {
                k.clear();
            }
        }

        /** Vanilla visits changed chunks in identity-hash order: sort by chunk, keep each chunk's order. */
        private static List<String> byChunk(List<String> packets) {
            List<String> sorted = new ArrayList<>(packets);
            sorted.sort(java.util.Comparator.comparingLong(p -> Long.parseLong(p.substring(0, p.indexOf('|')))));
            return sorted;
        }

        private List<String> pendingKestrel() {
            List<String> out = new ArrayList<>();
            for (TickLoop.PendingBlockTick t : loop.dumpBlockTicks()) {
                out.add(t.pos().toShortString() + " " + t.block() + " at " + t.triggerTick() + " p" + t.priority());
            }
            return out;
        }

        private List<String> pendingVanilla() {
            List<ScheduledTick<Block>> all = new ArrayList<>();
            for (LevelChunk chunk : chunks.values()) {
                ((LevelChunkTicks<Block>) chunk.getBlockTicks()).getAll().forEach(all::add);
            }
            all.sort(ScheduledTick.DRAIN_ORDER);
            List<String> out = new ArrayList<>();
            for (ScheduledTick<Block> t : all) {
                out.add(t.pos().toShortString() + " " + t.type() + " at " + t.triggerTick() + " p" + t.priority());
            }
            return out;
        }

        // ---- reading the worlds ----

        List<BlockState> kestrelCells() {
            List<BlockState> out = new ArrayList<>();
            for (long key : store.sectionKeys()) {
                if (store.copyCells(key, kCells)) {
                    out.addAll(List.of(kCells));
                }
            }
            return out;
        }

        List<BlockState> vanillaCells() {
            List<BlockState> out = new ArrayList<>();
            for (LevelChunk chunk : chunks.values()) {
                for (LevelChunkSection section : chunk.getSections()) {
                    if (!section.hasOnlyAir()) {
                        for (int c = 0; c < SectionStore.CELLS; c++) {
                            out.add(section.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15));
                        }
                    }
                }
            }
            return out;
        }

        boolean allCrops(List<BlockState> cells) {
            for (BlockState state : cells) {
                if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                    return false;
                }
            }
            return true;
        }

        /** Kestrel's count of a block (the gate's own assertions). */
        int count(Block block) {
            int n = 0;
            for (long key : store.sectionKeys()) {
                if (store.copyCells(key, kCells)) {
                    for (BlockState state : kCells) {
                        if (state.is(block)) {
                            n++;
                        }
                    }
                }
            }
            return n;
        }

        /** Copies of Kestrel's chunks, for its light engine. */
        private Map<Long, ProtoChunk> mirror() {
            Map<Long, ProtoChunk> out = new LinkedHashMap<>();
            for (int cz = -loaded; cz <= loaded; cz++) {
                for (int cx = -loaded; cx <= loaded; cx++) {
                    ProtoChunk proto = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY,
                            driver.height(), HeadlessNoise.factory(driver.access()), null);
                    for (int i = 0; i < sections; i++) {
                        int sectionY = (minY >> 4) + i;
                        if (!store.copyCells(SectionPos.asLong(cx, sectionY, cz), kCells)) {
                            continue;
                        }
                        for (int c = 0; c < SectionStore.CELLS; c++) {
                            if (!kCells[c].isAir()) {
                                proto.setBlockState(new BlockPos((cx << 4) + (c & 15), (sectionY << 4) + ((c >> 8) & 15),
                                        (cz << 4) + ((c >> 4) & 15)), kCells[c], 0);
                            }
                        }
                    }
                    out.put(ChunkPos.pack(cx, cz), proto);
                }
            }
            return out;
        }
    }

    private static final int VIEW_DISTANCE = 10;

    // =====================================================================
    // Kestrel's side: the entity level items fall in
    // =====================================================================

    private static final class ArenaEntities implements EntityLevel {
        private final Arena arena;
        private final ShapeBoxCache cache = new ShapeBoxCache();

        ArenaEntities(Arena arena) {
            this.arena = arena;
        }

        @Override public BlockGetter blocks() { return arena.loop.blockGetter(); }
        @Override public ShapeBoxCache shapeCache() { return cache; }
        @Override public int getMinY() { return arena.minY; }
        @Override public LevelReader levelReader() { return arena.loop.entityLevelReader(); }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags) { return arena.loop.setBlock(pos, state, flags); }
        @Override public void levelEvent(int type, BlockPos pos, int data) { arena.loop.emitLevelEvent(type, pos, data); }
        @Override public void landingBlockUpdate(KestrelEntity entity, BlockPos pos, BlockState state) { }
        @Override public RandomSource random() { return arena.loop.levelRandom(); }
        @Override public GameRules gameRules() { return arena.loop.gameRules(); }

        @Override
        public void playSound(double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
                float volume, float pitch) {
            arena.expect(false, "an item played a sound");
        }

        @Override
        public void entityEvent(KestrelEntity entity, byte event) {
            arena.expect(false, "an item raised an entity event");
        }

        @Override
        public void entityAdded(KestrelEntity entity) {
            if (entity instanceof KestrelItemEntity item) {
                arena.kItems.add(itemRecord(item.position(), item.getDeltaMovement(), item.getItem()));
            }
        }
    }

    /**
     * A drop: the stack and the position {@code Block.popResource} drew from
     * the level random. The motion is left out: {@code new ItemEntity} draws
     * it from the entity's own unseeded random, on both sides.
     */
    static String itemRecord(Vec3 pos, Vec3 motion, net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() + "x" + stack.getCount() + "@" + Double.doubleToRawLongBits(pos.x) + ","
                + Double.doubleToRawLongBits(pos.y) + "," + Double.doubleToRawLongBits(pos.z);
    }

    // =====================================================================
    // The oracle
    // =====================================================================

    /** A vanilla {@code ServerLevel} with no constructor run. */
    static final class OracleWorld extends ServerLevel {
        Arena arena;
        LevelLightEngine light;
        GameRules gameRules;
        long gameTime;
        LevelTicks<Block> blockTicks;
        LevelTicks<Fluid> fluidTicks;
        OracleServer server;
        WorldBorder border;
        LevelData levelData;
        net.minecraft.world.attribute.EnvironmentAttributeSystem environment;

        private OracleWorld() {
            super(null, Runnable::run, null, null, Level.OVERWORLD, null, false, 0L, List.of(), false);
            throw new AssertionError("constructor must not run");
        }

        static OracleWorld create(Arena arena) throws Exception {
            OracleWorld level = (OracleWorld) UNSAFE.allocateInstance(OracleWorld.class);
            level.arena = arena;
            level.gameRules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
            level.blockTicks = new LevelTicks<>(pos -> true);
            level.fluidTicks = new LevelTicks<>(pos -> true);
            level.server = OracleServer.create(arena);
            level.border = new WorldBorder();
            level.environment = net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().build();
            level.levelData = new LevelData() {
                @Override public RespawnData getRespawnData() { return RespawnData.DEFAULT; }
                @Override public long getGameTime() { return level.gameTime; }
                @Override public boolean isHardcore() { return false; }
                @Override public net.minecraft.world.Difficulty getDifficulty() { return net.minecraft.world.Difficulty.NORMAL; }
                @Override public boolean isDifficultyLocked() { return false; }
            };
            put(level, Level.class, "neighborUpdater",
                    new net.minecraft.world.level.redstone.CollectingNeighborUpdater(level, 1_000_000));
            put(level, ServerLevel.class, "blockEvents", new it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<>());
            put(level, ServerLevel.class, "blockEventsToReschedule", new ArrayList<>(64));
            return level;
        }

        void setGameTime(long time) {
            this.gameTime = time;
        }

        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean load) {
            LevelChunk chunk = arena.chunks.get(ChunkPos.pack(x, z));
            if (chunk == null && load) {
                throw new IllegalStateException("vanilla read outside the arena at chunk " + x + "," + z);
            }
            return chunk;
        }

        @Override public ServerChunkCache getChunkSource() { return arena.cache; }
        @Override public LevelLightEngine getLightEngine() { return light; }
        @Override public LevelTicks<Block> getBlockTicks() { return blockTicks; }
        @Override public LevelTicks<Fluid> getFluidTicks() { return fluidTicks; }
        @Override public GameRules getGameRules() { return gameRules; }
        @Override public LevelData getLevelData() { return levelData; }
        @Override public long getGameTime() { return gameTime; }
        @Override public RegistryAccess registryAccess() { return driver.access(); }
        @Override public net.minecraft.world.level.chunk.PalettedContainerFactory palettedContainerFactory() {
            return HeadlessNoise.factory(driver.access());
        }
        @Override public FeatureFlagSet enabledFeatures() { return FeatureFlags.DEFAULT_FLAGS; }
        @Override public DimensionType dimensionType() { return driver.dimensionType().value(); }
        @Override public net.minecraft.resources.ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public int getMinY() { return driver.height().getMinY(); }
        @Override public int getHeight() { return driver.height().getHeight(); }
        @Override public int getSeaLevel() { return driver.generator().getSeaLevel(); }
        @Override public net.minecraft.server.MinecraftServer getServer() { return server; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public net.minecraft.world.attribute.EnvironmentAttributeSystem environmentAttributes() {
            return environment;
        }
        @Override public Iterable<Entity> getAllEntities() { return List.of(); }
        private int nextEntityId;
        @Override public int getNextEntityId() { return ++nextEntityId; }
        @Override public List<ServerPlayer> players() { return List.of(); }

        /** {@code ServerLevel.sendBlockUpdated} with no navigating mobs: the chunk holder hears it. */
        @Override
        public void sendBlockUpdated(BlockPos pos, BlockState old, BlockState current, int updateFlags) {
            getChunkSource().blockChanged(pos);
        }

        /** No point-of-interest manager here, and nothing in these scenarios is a point of interest. */
        @Override
        public void updatePOIOnBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState) {
        }

        @Override
        public void levelEvent(Entity source, int type, BlockPos pos, int data) {
            arena.vLevelEvents.add(type + ":" + pos.asLong() + ":" + data);
        }

        @Override
        public void playSound(Entity except, double x, double y, double z, SoundEvent sound, SoundSource source,
                float volume, float pitch) {
            arena.expect(false, "vanilla played a sound " + sound);
        }

        @Override
        public void playSound(Entity except, double x, double y, double z, Holder<SoundEvent> sound,
                SoundSource source, float volume, float pitch) {
            arena.expect(false, "vanilla played a sound " + sound);
        }

        @Override
        public boolean addFreshEntity(Entity entity) {
            if (entity instanceof ItemEntity item) {
                arena.vItems.add(itemRecord(item.position(), item.getDeltaMovement(), item.getItem()));
                return true;
            }
            arena.expect(false, "vanilla spawned " + entity.getType());
            return false;
        }

        @Override
        public List<Entity> getEntities(Entity except, AABB box, Predicate<? super Entity> predicate) {
            return new ArrayList<>();
        }

        @Override
        public <T extends Entity> List<T> getEntities(net.minecraft.world.level.entity.EntityTypeTest<Entity, T> type,
                AABB box, Predicate<? super T> predicate) {
            return new ArrayList<>();
        }

        /** No listener exists in these worlds. */
        @Override
        public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) {
        }
    }

    /** The server a loot context reaches: the loot tables and the random sequences. */
    static final class OracleServer extends net.minecraft.server.dedicated.DedicatedServer {
        private Arena arena;

        private OracleServer() {
            super(null, null, null, null, null, null, null, null, null, null);
            throw new AssertionError("constructor must not run");
        }

        static OracleServer create(Arena arena) throws InstantiationException {
            OracleServer server = (OracleServer) UNSAFE.allocateInstance(OracleServer.class);
            server.arena = arena;
            return server;
        }

        @Override
        public ReloadableServerRegistries.Holder reloadableRegistries() {
            return loot;
        }

        @Override
        public RandomSource getRandomSequence(Identifier key) {
            return arena.vSequences.get(key, WORLD_SEED);
        }
    }

    /** {@code ServerChunkCache} with the fields {@code tickChunks} and the broadcast read. */
    static final class OracleCache extends ServerChunkCache {
        LightShell lightShell;

        private OracleCache() {
            super(null, null, null, null, Runnable::run, null, 0, 0, false, null, null);
            throw new AssertionError("constructor must not run");
        }

        static OracleCache create(OracleWorld level, OracleMap chunkMap, DistanceManager distance) throws Exception {
            OracleCache cache = (OracleCache) UNSAFE.allocateInstance(OracleCache.class);
            cache.lightShell = LightShell.forward(null);
            put(cache, ServerChunkCache.class, "level", level);
            put(cache, ServerChunkCache.class, "chunkMap", chunkMap);
            put(cache, ServerChunkCache.class, "distanceManager", distance);
            put(cache, ServerChunkCache.class, "spawningChunks", new it.unimi.dsi.fastutil.objects.ObjectArrayList<>());
            put(cache, ServerChunkCache.class, "chunkHoldersToBroadcast",
                    new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>());
            Field spawnEnemies = ServerChunkCache.class.getDeclaredField("spawnEnemies");
            spawnEnemies.setAccessible(true);
            spawnEnemies.setBoolean(cache, true);
            return cache;
        }

        @Override
        public ThreadedLevelLightEngine getLightEngine() {
            return lightShell;
        }

        @Override
        public void onSectionEmptinessChanged(int sectionX, int sectionY, int sectionZ, boolean empty) {
        }

        /** Light packets are not compared (Kestrel sends none, S6). */
        @Override
        public void onLightUpdate(LightLayer layer, SectionPos pos) {
        }
    }

    /** {@code ChunkMap} with the holders, the player map and the distance manager. */
    static final class OracleMap extends ChunkMap {
        PlayerMap playerMap;
        it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkHolder> holders;

        private OracleMap() {
            super(null, null, null, null, Runnable::run, null, null, null, null, null, null, 0, false);
            throw new AssertionError("constructor must not run");
        }

        static OracleMap create(OracleWorld level) throws Exception {
            OracleMap map = (OracleMap) UNSAFE.allocateInstance(OracleMap.class);
            map.holders = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
            map.playerMap = new PlayerMap();
            put(map, ChunkMap.class, "level", level);
            put(map, ChunkMap.class, "visibleChunkMap", map.holders);
            put(map, ChunkMap.class, "updatingChunkMap", new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>());
            put(map, ChunkMap.class, "playerMap", map.playerMap);
            put(map, ChunkMap.class, "toDrop", new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
            return map;
        }

        void bind(DistanceManager distance) throws Exception {
            put(this, ChunkMap.class, "distanceManager", distance);
        }

        /** A holder whose ticking future is complete: the chunk ticks and broadcasts. */
        void addHolder(Arena arena, LevelChunk chunk) throws Exception {
            ChunkHolder.PlayerProvider players = (pos, borderOnly) -> {
                if (borderOnly) {
                    return List.of();
                }
                List<ServerPlayer> out = new ArrayList<>();
                for (OraclePlayer player : arena.vPlayers.values()) {
                    if (arena.tracks(ChunkPos.containing(player.blockPosition()), pos.pack())) {
                        out.add(player);
                    }
                }
                return out;
            };
            ChunkHolder holder = new ChunkHolder(chunk.getPos(), 31, driver.height(), null, (p, a, b, c) -> { },
                    players);
            CompletableFuture<ChunkResult<LevelChunk>> done = CompletableFuture.completedFuture(ChunkResult.of(chunk));
            put(holder, ChunkHolder.class, "fullChunkFuture", done);
            put(holder, ChunkHolder.class, "tickingChunkFuture", done);
            put(holder, ChunkHolder.class, "entityTickingChunkFuture", done);
            holders.put(chunk.getPos().pack(), holder);
        }
    }

    /** A {@code ServerPlayer} with a position and a connection that records what it is sent. */
    static final class OraclePlayer extends ServerPlayer {
        String id;
        Arena arena;

        private OraclePlayer() {
            super(null, null, null, null);
            throw new AssertionError("constructor must not run");
        }

        static OraclePlayer create(Arena arena, String id, double x, double y, double z) throws Exception {
            OraclePlayer player = (OraclePlayer) UNSAFE.allocateInstance(OraclePlayer.class);
            player.id = id;
            player.arena = arena;
            Field entityId = Entity.class.getDeclaredField("id");
            entityId.setAccessible(true);
            entityId.setInt(player, 1_000_000 + arena.vPlayers.size());
            player.place(x, y, z);
            Recorder connection = (Recorder) UNSAFE.allocateInstance(Recorder.class);
            connection.player = player;
            player.connection = connection;
            return player;
        }

        void place(double x, double y, double z) {
            try {
                put(this, Entity.class, "position", new Vec3(x, y, z));
                put(this, Entity.class, "blockPosition", BlockPos.containing(x, y, z));
                put(this, Entity.class, "chunkPosition", ChunkPos.containing(BlockPos.containing(x, y, z)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean isSpectator() {
            return false;
        }
    }

    /** The connection: every packet the chunk holders send, encoded. */
    static final class Recorder extends ServerGamePacketListenerImpl {
        OraclePlayer player;

        private Recorder() {
            super(null, null, null, null);
            throw new AssertionError("constructor must not run");
        }

        @Override
        public void send(Packet<?> packet) {
            String encoded = encode(packet);
            int id = Integer.parseInt(encoded.substring(0, encoded.indexOf(':')));
            long first = Long.parseUnsignedLong(encoded.substring(encoded.indexOf(':') + 1,
                    encoded.indexOf(':') + 17), 16);
            long chunk;
            if (id == dev.kestrel.net.PacketIds.PLAY_BLOCK_UPDATE) {
                chunk = ChunkPos.pack(BlockPos.getX(first) >> 4, BlockPos.getZ(first) >> 4);
            } else if (id == dev.kestrel.net.PacketIds.PLAY_SECTION_BLOCKS_UPDATE) {
                chunk = ChunkPos.pack(SectionPos.x(first), SectionPos.z(first));
            } else {
                player.arena.expect(false, "vanilla sent an unexpected packet " + encoded);
                return;
            }
            player.arena.vPackets.get(player.id).add(chunk + "|" + encoded);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** {@code ThreadedLevelLightEngine} shell: the chunk's light calls go to the plain engine. */
    static final class LightShell extends ThreadedLevelLightEngine {
        LevelLightEngine target;

        private LightShell() {
            super(null, null, false, null, null);
            throw new AssertionError("constructor must not run");
        }

        static LightShell forward(LevelLightEngine target) throws InstantiationException {
            LightShell shell = (LightShell) UNSAFE.allocateInstance(LightShell.class);
            shell.target = target;
            return shell;
        }

        @Override
        public void checkBlock(BlockPos pos) {
            if (target != null) {
                target.checkBlock(pos);
            }
        }

        /** Before the arena is lit (the setup writes) there is nothing to tell: lighting starts from scratch. */
        @Override
        public void updateSectionStatus(SectionPos pos, boolean sectionEmpty) {
            if (target != null) {
                target.updateSectionStatus(pos, sectionEmpty);
            }
        }
    }

    /** A light engine lit over {@code chunks}, the way the generator lights them. */
    private static LevelLightEngine litEngine(Map<Long, ? extends ChunkAccess> chunks) {
        net.minecraft.world.level.chunk.LightChunkGetter getter = new net.minecraft.world.level.chunk.LightChunkGetter() {
            private final BlockGetter world = new BlockGetter() {
                @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) { return null; }
                @Override public BlockState getBlockState(BlockPos pos) {
                    ChunkAccess chunk = chunks.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
                    return chunk == null ? Blocks.BEDROCK.defaultBlockState() : chunk.getBlockState(pos);
                }
                @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
                    return getBlockState(pos).getFluidState();
                }
                @Override public int getHeight() { return driver.height().getHeight(); }
                @Override public int getMinY() { return driver.height().getMinY(); }
            };

            @Override
            public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x, int z) {
                return chunks.get(ChunkPos.pack(x, z));
            }

            @Override
            public BlockGetter getLevel() {
                return world;
            }
        };
        LevelLightEngine engine = new LevelLightEngine(getter, true, driver.dimensionType().value().hasSkyLight());
        for (ChunkAccess chunk : chunks.values()) {
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                if (!sections[i].hasOnlyAir()) {
                    engine.updateSectionStatus(SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(i)), false);
                }
            }
        }
        engine.runLightUpdates();
        for (ChunkAccess chunk : chunks.values()) {
            engine.setLightEnabled(chunk.getPos(), false);
            engine.retainData(chunk.getPos(), false);
        }
        for (ChunkAccess chunk : chunks.values()) {
            engine.propagateLightSources(chunk.getPos());
        }
        engine.runLightUpdates();
        return engine;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String encode(Packet<?> packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            protocol.codec().encode(buf, (Packet) packet);
            FriendlyByteBuf fb = new FriendlyByteBuf(buf);
            int id = fb.readVarInt();
            byte[] payload = new byte[fb.readableBytes()];
            fb.readBytes(payload);
            return fmt(id, payload);
        } finally {
            buf.release();
        }
    }

    static String fmt(int id, byte[] payload) {
        return id + ":" + HexFormat.of().formatHex(payload);
    }

    private static String keys(List<Long> keys) {
        List<String> out = new ArrayList<>();
        for (long key : keys) {
            out.add(ChunkPos.getX(key) + "," + ChunkPos.getZ(key));
        }
        return out.toString();
    }

    private static long seed(RandomSource random) throws IllegalAccessException {
        return ((AtomicLong) legacySeed.get((LegacyRandomSource) random)).get();
    }

    private static void invoke(Method method, Object target, Object... args) {
        try {
            method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reflect() throws Exception {
        tickBlock = declared(ServerLevel.class, "tickBlock", BlockPos.class, Block.class);
        tickFluid = declared(ServerLevel.class, "tickFluid", BlockPos.class, Fluid.class);
        tickChunks = declared(ServerChunkCache.class, "tickChunks", ProfilerFiller.class, long.class);
        broadcastChangedChunks = declared(ServerChunkCache.class, "broadcastChangedChunks", ProfilerFiller.class);
        runBlockEvents = declared(ServerLevel.class, "runBlockEvents");
        updatePlayerTickets = declared(DistanceManager.class, "updatePlayerTickets", int.class);
        levelRandom = field(Level.class, "random");
        randValue = field(Level.class, "randValue");
        skyDarken = field(Level.class, "skyDarken");
        biomeManager = field(Level.class, "biomeManager");
        handlingTick = field(ServerLevel.class, "handlingTick");
        legacySeed = field(LegacyRandomSource.class, "seed");
        tickingBlockCount = field(LevelChunkSection.class, "tickingBlockCount");
        tickingFluidCount = field(LevelChunkSection.class, "tickingFluidCount");
    }

    private static Method declared(Class<?> owner, String name, Class<?>... types) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static void put(Object target, Class<?> owner, String name, Object value) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
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
