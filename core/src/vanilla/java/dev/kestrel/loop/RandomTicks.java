package dev.kestrel.loop;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkTracker;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.SimulationChunkTracker;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * G30: random ticks, the chunk half of {@code ServerChunkCache.tickChunks}
 * ({@code ServerChunkCache.java:374-415}) and {@code ServerLevel.tickChunk}
 * ({@code ServerLevel.java:496-539}), run in vanilla's slot: after the fluid
 * ticks, before the block publication and the entity tracker.
 *
 * <p><b>Which chunks.</b> Vanilla ticks the chunks of
 * {@code DistanceManager.simulationChunkTracker} at ENTITY_TICKING level,
 * walking its {@code Long2ByteOpenHashMap} in fastutil's iteration order.
 * That order depends on the map's history (capacity, collisions, removals),
 * not just its keys, so Kestrel keeps vanilla's own objects: a
 * {@link TicketStorage} and a {@link SimulationChunkTracker}, fed the same
 * {@code PLAYER_SIMULATION} ticket operations {@code DistanceManager.addPlayer}
 * and {@code removePlayer} make when a player joins, crosses a section
 * boundary ({@code ChunkMap.move}) or leaves. Players are the only simulation
 * tickets a Kestrel world has (no forced chunks, ender pearls or portals). The
 * owner says which chunks are loaded and ticking ({@link Environment#isTickingChunk}),
 * the {@code holder.getTickingChunk() != null} half of
 * {@code ChunkMap.forEachBlockTickingChunk}.
 *
 * <p><b>The level random.</b> Every draw is from the loop's level random
 * ({@link TickLoop#levelRandom}), in vanilla's order: the spawning chunk
 * shuffle ({@code Util.shuffle}, one {@code nextInt} per chunk after the
 * first), thunder's roll per spawning chunk in range (only while it
 * thunders), then per ticking chunk the precipitation rolls and the block and
 * fluid random ticks. Positions come from {@code Level.getBlockRandomPos}'s
 * separate {@code int} LCG, transcribed here.
 *
 * <p><b>Vanilla's block logic.</b> {@code BlockState.randomTick},
 * {@code FluidState.randomTick} and {@code ServerLevel.tickPrecipitation} are
 * vanilla's own methods, run on {@link RandomTickLevel}, whose reads come
 * from Kestrel's world and whose writes go through the loop's live
 * {@code setBlock}. What it cannot answer throws {@link Unanswered}.
 *
 * <p>Thread-safety: tick thread only.
 */
public final class RandomTicks {

    /** {@code DistanceManager.PLAYER_TICKET_LEVEL}'s base: ENTITY_TICKING, 31. */
    private static final int ENTITY_TICKING_LEVEL = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
    /** {@code DistanceManager.naturalSpawnChunkCounter}'s range, in chunks. */
    private static final int SPAWN_CHUNK_RANGE = 8;
    /** {@code ChunkMap.playerIsCloseEnoughForSpawning}: 128 blocks, squared. */
    private static final double SPAWN_DISTANCE_SQR = 16384.0;

    /**
     * What the world's owner answers for random ticks. The defaults that
     * throw are the reads a gate or server must wire before a block reaches
     * them.
     */
    public interface Environment {
        /** {@code ChunkHolder.getTickingChunk() != null}: loaded and ticking. */
        default boolean isTickingChunk(long chunkKey) {
            return true;
        }

        /** {@code ServerChunkCache.hasChunk}: loaded at FULL. */
        default boolean isLoadedChunk(long chunkKey) {
            return isTickingChunk(chunkKey);
        }

        /** The engine brightness reads go to ({@code Level.getLightEngine}). */
        default LevelLightEngine lightEngine() {
            throw new Unanswered("light", "S6");
        }

        /** {@code Level.skyDarken}, as {@code updateSkyBrightness} left it this tick. */
        default int skyDarken() {
            throw new Unanswered("sky darkening", "G3");
        }

        /** {@code Level.rainLevel} (after this tick's weather step). */
        default float rainLevel() {
            throw new Unanswered("rain level", "G3");
        }

        /** {@code Level.thunderLevel} before the rain factor. */
        default float thunderLevel() {
            throw new Unanswered("thunder level", "G50");
        }

        /** {@code LevelReader.getNoiseBiome} at quart coordinates. */
        default Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
            throw new Unanswered("biomes", "G30");
        }

        /** {@code BiomeManager.obfuscateSeed(worldSeed)}. */
        default long biomeZoomSeed() {
            throw new Unanswered("biome zoom seed", "G30");
        }

        default RegistryAccess registryAccess() {
            return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        }

        /** {@code ChunkGenerator.getSeaLevel}. */
        default int seaLevel() {
            return 63;
        }

        /** {@code ServerLevel.environmentAttributes}. */
        default EnvironmentAttributeSystem environmentAttributes() {
            throw new Unanswered("environment attributes", "G3");
        }

        /** {@code MinecraftServer.reloadableRegistries}: the loot tables. */
        default ReloadableServerRegistries.Holder lootTables() {
            throw new Unanswered("loot tables", "G19");
        }

        /** {@code MinecraftServer.getRandomSequence}. */
        default RandomSource randomSequence(Identifier key) {
            throw new Unanswered("random sequences", "G19");
        }
    }

    /** One player as the distance manager and the spawn check see it. */
    private static final class Tracked {
        final Object id;
        double x;
        double y;
        double z;
        boolean spectator;
        /** {@code PlayerMap}'s ignored flag: a spectator that does not generate chunks. */
        boolean ignored;
        /** {@code ServerPlayer.getLastSectionPos}. */
        long lastSection;

        Tracked(Object id) {
            this.id = id;
        }
    }

    /** {@code SimulationChunkTracker} with its map in reach. */
    private static final class Simulation extends SimulationChunkTracker {
        Simulation(TicketStorage tickets) {
            super(tickets);
        }

        Long2ByteMap chunks() {
            return chunks;
        }

        int levelOf(long chunkKey) {
            return getLevel(chunkKey);
        }
    }

    /**
     * {@code DistanceManager.FixedPlayerDistanceChunkTracker(8)}, the
     * natural-spawn chunk counter ({@code DistanceManager.java:207-250}): a
     * private inner class, so transcribed.
     */
    private final class SpawnChunkCounter extends ChunkTracker {
        final Long2ByteMap chunks = new Long2ByteOpenHashMap();

        SpawnChunkCounter() {
            super(SPAWN_CHUNK_RANGE + 2, 16, 256);
            chunks.defaultReturnValue((byte) (SPAWN_CHUNK_RANGE + 2));
        }

        @Override
        protected int getLevel(long node) {
            return chunks.get(node);
        }

        @Override
        protected void setLevel(long node, int level) {
            if (level > SPAWN_CHUNK_RANGE) {
                chunks.remove(node);
            } else {
                chunks.put(node, (byte) level);
            }
        }

        @Override
        protected int getLevelFromSource(long to) {
            ObjectOpenHashSet<Object> here = playersPerChunk.get(to);
            return here != null && !here.isEmpty() ? 0 : Integer.MAX_VALUE;
        }

        void runAllUpdates() {
            runUpdates(Integer.MAX_VALUE);
        }
    }

    private final TickLoop loop;
    private final TicketStorage tickets = new TicketStorage();
    private final Simulation simulation = new Simulation(tickets);
    private final Long2ObjectOpenHashMap<ObjectOpenHashSet<Object>> playersPerChunk = new Long2ObjectOpenHashMap<>();
    private final SpawnChunkCounter spawnChunks = new SpawnChunkCounter();
    private final Map<Object, Tracked> players = new LinkedHashMap<>();
    private final List<Long> spawningChunks = new ArrayList<>();
    private int simulationDistance = 10;
    /** {@code Level.randValue}: seeded like vanilla's field initializer. */
    private int randValue = RandomSource.createThreadLocalInstance().nextInt();
    private Environment environment = new Environment() {
    };
    private RandomTickLevel level;
    /** Where an {@link Unanswered} goes; the default rethrows (gates). */
    private Consumer<Unanswered> unanswered = e -> {
        throw e;
    };

    /** Totals for diagnostics: chunks ticked, block and fluid random ticks run. */
    private long chunksTicked;
    private long randomTicksRun;
    private Consumer<String> statsListener;

    /** Report the totals every 200 ticks while chunks tick (the live server's log). */
    public void setStatsListener(Consumer<String> listener) {
        this.statsListener = listener;
    }

    public long chunksTicked() {
        return chunksTicked;
    }

    public long randomTicksRun() {
        return randomTicksRun;
    }

    RandomTicks(TickLoop loop) {
        this.loop = loop;
    }

    public void setEnvironment(Environment environment) {
        this.environment = java.util.Objects.requireNonNull(environment);
    }

    public Environment environment() {
        return environment;
    }

    /** Route {@link Unanswered} failures (the live server logs them); the default rethrows. */
    public void setUnansweredHandler(Consumer<Unanswered> handler) {
        this.unanswered = java.util.Objects.requireNonNull(handler);
    }

    /** The vanilla {@code ServerLevel} stand-in the block logic runs on. */
    public RandomTickLevel level() {
        if (level == null) {
            level = RandomTickLevel.create(loop, this);
        }
        return level;
    }

    // =====================================================================
    // The distance manager's simulation half
    // =====================================================================

    /** {@code DistanceManager.getPlayerTicketLevel}. */
    private int playerTicketLevel() {
        return Math.max(0, ENTITY_TICKING_LEVEL - simulationDistance);
    }

    /**
     * {@code DistanceManager.updateSimulationDistance}: the server's
     * {@code simulation-distance}. Vanilla starts at 10.
     */
    public void setSimulationDistance(int distance) {
        if (distance != simulationDistance) {
            simulationDistance = distance;
            tickets.replaceTicketLevelOfType(playerTicketLevel(), TicketType.PLAYER_SIMULATION);
            runAllUpdates();
        }
    }

    public int simulationDistance() {
        return simulationDistance;
    }

    /** {@code ChunkMap.updatePlayerStatus(player, true)} at the player's position. */
    public void addPlayer(Object id, double x, double y, double z, boolean spectator) {
        if (players.containsKey(id)) {
            throw new IllegalStateException("player already added: " + id);
        }
        Tracked player = new Tracked(id);
        players.put(id, player);
        place(player, x, y, z, spectator);
        player.ignored = skipPlayer(player);
        player.lastSection = sectionOf(player);
        if (!player.ignored) {
            distanceAddPlayer(player.lastSection, id);
        }
        runAllUpdates();
    }

    /**
     * {@code ChunkMap.move}: a position update. The tickets move only when the
     * player's section changed or its ignored state flipped.
     */
    public void movePlayer(Object id, double x, double y, double z, boolean spectator) {
        Tracked player = players.get(id);
        if (player == null) {
            return;
        }
        place(player, x, y, z, spectator);
        long oldSection = player.lastSection;
        long newSection = sectionOf(player);
        boolean wasIgnored = player.ignored;
        boolean ignored = skipPlayer(player);
        if (oldSection != newSection || wasIgnored != ignored) {
            player.lastSection = newSection;
            if (!wasIgnored) {
                distanceRemovePlayer(oldSection, id);
            }
            if (!ignored) {
                distanceAddPlayer(newSection, id);
            }
            player.ignored = ignored;
            runAllUpdates();
        }
    }

    /** {@code ChunkMap.updatePlayerStatus(player, false)}. */
    public void removePlayer(Object id) {
        Tracked player = players.remove(id);
        if (player == null) {
            return;
        }
        if (!player.ignored) {
            distanceRemovePlayer(player.lastSection, id);
        }
        runAllUpdates();
    }

    private static void place(Tracked player, double x, double y, double z, boolean spectator) {
        player.x = x;
        player.y = y;
        player.z = z;
        player.spectator = spectator;
    }

    /** {@code SectionPos.of(entity)}: the section of {@code blockPosition()}. */
    private static long sectionOf(Tracked player) {
        return SectionPos.asLong(
                SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(player.x)),
                SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(player.y)),
                SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(player.z)));
    }

    /** {@code ChunkMap.skipPlayer}. */
    private boolean skipPlayer(Tracked player) {
        return player.spectator && !loop.gameRules().get(GameRules.SPECTATORS_GENERATE_CHUNKS);
    }

    /** {@code DistanceManager.addPlayer}, the simulation and spawn-count half. */
    private void distanceAddPlayer(long section, Object id) {
        long chunk = ChunkPos.pack(SectionPos.x(section), SectionPos.z(section));
        playersPerChunk.computeIfAbsent(chunk, k -> new ObjectOpenHashSet<>()).add(id);
        spawnChunks.update(chunk, 0, true);
        tickets.addTicket(chunk, new Ticket(TicketType.PLAYER_SIMULATION, playerTicketLevel()));
    }

    /** {@code DistanceManager.removePlayer}, the same half. */
    private void distanceRemovePlayer(long section, Object id) {
        long chunk = ChunkPos.pack(SectionPos.x(section), SectionPos.z(section));
        ObjectOpenHashSet<Object> here = playersPerChunk.get(chunk);
        here.remove(id);
        if (here.isEmpty()) {
            playersPerChunk.remove(chunk);
            spawnChunks.update(chunk, Integer.MAX_VALUE, false);
            tickets.removeTicket(chunk, new Ticket(TicketType.PLAYER_SIMULATION, playerTicketLevel()));
        }
    }

    /**
     * {@code DistanceManager.runAllUpdates}, the two trackers that matter
     * here, in its order. Vanilla runs it at the top of every
     * {@code ServerChunkCache.tick} and, on an idle server, right after each
     * queued task (a movement packet) through {@code pollTask}; Kestrel runs
     * it after every player operation and again before ticking.
     */
    private void runAllUpdates() {
        spawnChunks.runAllUpdates();
        simulation.runAllUpdates();
    }

    /** {@code DistanceManager.inEntityTickingRange}. */
    public boolean inEntityTickingRange(long chunkKey) {
        return ChunkLevel.isEntityTicking(simulation.levelOf(chunkKey));
    }

    /** The entity-ticking chunks in vanilla's iteration order (gates and diagnostics). */
    public List<Long> entityTickingChunks() {
        List<Long> out = new ArrayList<>();
        ObjectIterator<Long2ByteMap.Entry> iterator = Long2ByteMaps.fastIterable(simulation.chunks()).iterator();
        while (iterator.hasNext()) {
            Long2ByteMap.Entry entry = iterator.next();
            if (ChunkLevel.isEntityTicking(entry.getByteValue())) {
                out.add(entry.getLongKey());
            }
        }
        return out;
    }

    /** {@code ChunkMap.anyPlayerCloseEnoughTo}: the fire-spread radius around players. */
    boolean anyPlayerCloseEnoughTo(BlockPos pos, int maxDistance) {
        for (Tracked player : players.values()) {
            if (player.spectator) {
                continue;
            }
            double dx = player.x - pos.getX();
            double dy = player.y - pos.getY();
            double dz = player.z - pos.getZ();
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < maxDistance) {
                return true;
            }
        }
        return false;
    }

    /** Players' bounding boxes that intersect {@code box}: the entity query's player half. */
    boolean anyPlayerIn(net.minecraft.world.phys.AABB box) {
        for (Tracked player : players.values()) {
            // EntityType.PLAYER's standing dimensions, 0.6 by 1.8.
            net.minecraft.world.phys.AABB body = new net.minecraft.world.phys.AABB(
                    player.x - 0.3, player.y, player.z - 0.3, player.x + 0.3, player.y + 1.8, player.z + 0.3);
            if (body.intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /** {@code ChunkMap.anyPlayerCloseEnoughForSpawningInternal}. */
    private boolean anyPlayerCloseEnoughForSpawning(long chunkKey) {
        double centerX = SectionPos.sectionToBlockCoord(ChunkPos.getX(chunkKey), 8);
        double centerZ = SectionPos.sectionToBlockCoord(ChunkPos.getZ(chunkKey), 8);
        for (Tracked player : players.values()) {
            if (player.spectator) {
                continue;
            }
            double dx = centerX - player.x;
            double dz = centerZ - player.z;
            if (dx * dx + dz * dz < SPAWN_DISTANCE_SQR) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // The tick
    // =====================================================================

    /**
     * {@code ServerChunkCache.tick}'s distance-manager update, then the
     * chunk half of {@code tickChunks(profiler, timeDiff)}. Natural spawning
     * and the custom spawners draw nothing while {@code spawnMobs} is off.
     * With it on, their unknown draw counts are reported as unanswered G44
     * work at the two positions where vanilla would consume them.
     */
    void tick() {
        RandomSource random = loop.levelRandom();
        RandomTickLevel standIn = level();
        standIn.syncRandom();
        runAllUpdates();
        boolean spawnMobs = loop.gameRules().get(GameRules.SPAWN_MOBS);
        int tickSpeed = loop.gameRules().get(GameRules.RANDOM_TICK_SPEED);

        // ChunkMap.collectSpawningChunks: the spawn counter's keys, in its
        // map's order, that are ticking and near a non-spectator player.
        spawningChunks.clear();
        LongIterator candidates = spawnChunks.chunks.keySet().iterator();
        while (candidates.hasNext()) {
            long key = candidates.nextLong();
            if (environment.isTickingChunk(key) && anyPlayerCloseEnoughForSpawning(key)) {
                spawningChunks.add(key);
            }
        }
        Util.shuffle(spawningChunks, random);
        boolean naturalSpawnDrawsReported = false;
        for (long key : spawningChunks) {
            // tickSpawningChunk: incrementInhabitedTime (Kestrel keeps no
            // inhabited time), then thunder for chunks in entity range.
            if (inEntityTickingRange(key)) {
                tickThunder(key, random);
            }
            if (spawnMobs && !naturalSpawnDrawsReported) {
                unanswered.accept(new Unanswered(
                        "natural mob spawning level-random draws, unknown draw count", "G44"));
                naturalSpawnDrawsReported = true;
            }
        }
        spawningChunks.clear();

        // ChunkMap.forEachBlockTickingChunk -> DistanceManager.forEachEntityTickingChunk.
        ObjectIterator<Long2ByteMap.Entry> iterator = Long2ByteMaps.fastIterable(simulation.chunks()).iterator();
        while (iterator.hasNext()) {
            Long2ByteMap.Entry entry = iterator.next();
            long key = entry.getLongKey();
            if (ChunkLevel.isEntityTicking(entry.getByteValue()) && environment.isTickingChunk(key)) {
                tickChunk(key, tickSpeed, random, standIn);
            }
        }
        if (spawnMobs) {
            unanswered.accept(new Unanswered("custom spawner level-random draws, unknown draw count", "G44"));
        }
        if (statsListener != null && chunksTicked > 0 && loop.gameTime() % 200 == 0) {
            statsListener.accept("random ticks: chunks=" + chunksTicked + " blockOrFluidTicks=" + randomTicksRun
                    + " players=" + players.size() + " tickSpeed=" + tickSpeed);
        }
    }

    /**
     * {@code ServerLevel.tickThunder}'s draw: {@code nextInt(100000)} only
     * while it rains and thunders. Kestrel never thunders (G50), so this
     * draws nothing; a strike would need lightning.
     */
    private void tickThunder(long chunkKey, RandomSource random) {
        RandomTickLevel standIn = level();
        if (standIn.isRaining() && standIn.isThundering() && random.nextInt(100000) == 0) {
            unanswered.accept(new Unanswered("lightning strike", "G50"));
        }
    }

    /** {@code ServerLevel.tickChunk}. */
    void tickChunk(long chunkKey, int tickSpeed, RandomSource random, RandomTickLevel standIn) {
        int chunkX = ChunkPos.getX(chunkKey);
        int chunkZ = ChunkPos.getZ(chunkKey);
        int minX = SectionPos.sectionToBlockCoord(chunkX);
        int minZ = SectionPos.sectionToBlockCoord(chunkZ);
        chunksTicked++;

        for (int i = 0; i < tickSpeed; i++) {
            if (random.nextInt(48) == 0) {
                BlockPos pos = getBlockRandomPos(minX, 0, minZ, 15);
                try {
                    standIn.tickPrecipitation(pos);
                } catch (Unanswered e) {
                    unanswered.accept(e);
                }
            }
        }

        if (tickSpeed > 0) {
            SectionStore store = loop.store();
            int minSection = SectionPos.blockToSectionCoord(store.getMinY());
            int sectionCount = store.getHeight() >> 4;
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                int sectionY = minSection + sectionIndex;
                if (!store.isRandomlyTicking(SectionPos.asLong(chunkX, sectionY, chunkZ))) {
                    continue;
                }
                int minYInSection = SectionPos.sectionToBlockCoord(sectionY);
                for (int ix = 0; ix < tickSpeed; ix++) {
                    BlockPos pos = getBlockRandomPos(minX, minYInSection, minZ, 15);
                    BlockState blockState = store.get(pos);
                    try {
                        if (blockState.isRandomlyTicking()) {
                            randomTicksRun++;
                            blockState.randomTick(standIn, pos, random);
                        }
                        FluidState fluidState = blockState.getFluidState();
                        if (fluidState.isRandomlyTicking()) {
                            randomTicksRun++;
                            fluidState.randomTick(standIn, pos, random);
                        }
                    } catch (Unanswered e) {
                        unanswered.accept(e);
                    }
                }
            }
        }
    }

    /** {@code Level.getBlockRandomPos} ({@code Level.java:1067-1071}). */
    BlockPos getBlockRandomPos(int xo, int yo, int zo, int yMask) {
        randValue = randValue * 3 + 1013904223;
        int val = randValue >> 2;
        return new BlockPos(xo + (val & 15), yo + (val >> 16 & yMask), zo + (val >> 8 & 15));
    }

    /** {@code Level.randValue}, for gates. */
    public int randValue() {
        return randValue;
    }

    public void setRandValue(int randValue) {
        this.randValue = randValue;
    }

    // =====================================================================
    // Scheduled ticks that run vanilla's own body
    // =====================================================================

    /**
     * True for the blocks whose scheduled tick runs vanilla's own
     * {@code tick} on the stand-in: leaves, whose distance update is what
     * turns a cut tree's leaves into decaying ones.
     */
    static boolean runsVanillaScheduledTick(BlockState state) {
        return state.getBlock() instanceof LeavesBlock;
    }

    /** {@code ServerLevel.tickBlock}'s body for those: {@code state.tick(level, pos, random)}. */
    void scheduledTick(BlockState state, BlockPos pos) {
        RandomTickLevel standIn = level();
        standIn.syncRandom();
        try {
            state.tick(standIn, pos, loop.levelRandom());
        } catch (Unanswered e) {
            unanswered.accept(e);
        }
    }
}
