package dev.kestrel.net;

import dev.kestrel.loop.ChunkActivation;
import dev.kestrel.loop.LootTables;
import dev.kestrel.loop.RandomTicks;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.loop.Unanswered;
import dev.kestrel.worldgen.HeadlessNoise;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.WeatherAttributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.HashMap;
import java.util.Map;

/**
 * G30 on the live server: the {@link RandomTicks.Environment} answered from
 * the running world, and the players' simulation tickets fed by
 * {@link NetServer}.
 *
 * <ul>
 *   <li>Ticking chunks: the activated ones ({@link ChunkActivation}, the
 *       post-processing trigger standing in for BLOCK_TICKING until S16).</li>
 *   <li>Light: the engine that lit them, frozen apart from the activation
 *       pass's writes (no live relighting, S6).</li>
 *   <li>Sky darkening: vanilla's own {@link EnvironmentAttributeSystem} over
 *       the overworld's dimension attributes, its timelines on the world
 *       clock and the weather layers, as {@code Level.updateSkyBrightness}
 *       reads it.</li>
 *   <li>Weather: {@link WorldTime}'s rain level; thunder is 0 (G50).</li>
 *   <li>Biomes: the generator's biome source, which is what generation
 *       stored for every generated chunk.</li>
 *   <li>Loot: the vanilla data pack's loot registries and a fresh
 *       {@code RandomSequences} on the world seed (not persisted yet).</li>
 *   <li>The level random: unseeded, like {@code Level.random}.</li>
 * </ul>
 *
 * <p>An {@link Unanswered} is logged, with its stack, the first time each
 * kind is reached and counted after that, so a missing mechanism is loud
 * without stopping the server.
 */
final class LiveRandomTicks {
    private LiveRandomTicks() {
    }

    static void install(TickLoop loop, NetServer netServer, ChunkActivation activation,
            HeadlessNoise.Structural structural, long seed, int simulationDistance) {
        if (structural == null) {
            System.out.println("[kestrel] random ticks off: no world generator to answer biomes and loot");
            return;
        }
        HeadlessNoise.Driver driver = structural.driver();
        RegistryAccess access = driver.access();
        long started = System.nanoTime();
        ReloadableServerRegistries.Holder loot = LootTables.load(access, structural.resources());
        // One RandomSequences per server (MinecraftServer.java:351), shared by
        // random-tick loot and block drops.
        RandomSequences sequences = new RandomSequences();
        // Block-drop location checks see the same loaded chunks as random ticks.
        netServer.setLootRegistries(access, loot, key -> sequences.get(key, seed),
                key -> loop.store().isChunkGenerated(key)
                        || activation.isActivated(ChunkPos.getX(key), ChunkPos.getZ(key)));
        // The data pack's own command set, built by the same load as the registries above.
        netServer.setCommands(structural.commands());
        DimensionType dimension = driver.dimensionType().value();
        WorldTime time = netServer.time;
        EnvironmentAttributeSystem.Builder builder = EnvironmentAttributeSystem.builder();
        builder.addConstantLayer(dimension.attributes());
        dimension.timelines().forEach(timeline -> builder.addTimelineLayer(timeline, clock -> time.dayTime()));
        WeatherAttributes.addBuiltinLayers(builder, new WeatherAttributes.WeatherAccess() {
            @Override
            public float rainLevel() {
                return time.rainLevel();
            }

            @Override
            public float thunderLevel() {
                return 0.0F;
            }
        });
        EnvironmentAttributeSystem attributes = builder.build();
        long zoomSeed = BiomeManager.obfuscateSeed(seed);

        RandomTicks ticks = loop.randomTicks();
        loop.setLevelRandom(RandomSource.create());
        ticks.setEnvironment(new RandomTicks.Environment() {
            @Override
            public boolean isTickingChunk(long key) {
                return activation.isActivated(ChunkPos.getX(key), ChunkPos.getZ(key));
            }

            @Override
            public boolean isLoadedChunk(long key) {
                return loop.store().isChunkGenerated(key) || isTickingChunk(key);
            }

            @Override
            public LevelLightEngine lightEngine() {
                LevelLightEngine engine = activation.lightEngine();
                if (engine == null) {
                    throw new Unanswered("light", "S6");
                }
                return engine;
            }

            @Override
            public int skyDarken() {
                attributes.invalidateTickCache();
                return (int) (15.0F - attributes.getDimensionValue(EnvironmentAttributes.SKY_LIGHT_LEVEL));
            }

            @Override
            public float rainLevel() {
                return time.rainLevel();
            }

            @Override
            public float thunderLevel() {
                return 0.0F;
            }

            @Override
            public Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
                return driver.generator().getBiomeSource().getNoiseBiome(quartX, quartY, quartZ,
                        driver.randomState().sampler());
            }

            @Override
            public long biomeZoomSeed() {
                return zoomSeed;
            }

            @Override
            public RegistryAccess registryAccess() {
                return access;
            }

            @Override
            public int seaLevel() {
                return driver.generator().getSeaLevel();
            }

            @Override
            public EnvironmentAttributeSystem environmentAttributes() {
                return attributes;
            }

            @Override
            public ReloadableServerRegistries.Holder lootTables() {
                return loot;
            }

            @Override
            public RandomSource randomSequence(Identifier key) {
                return sequences.get(key, seed);
            }
        });
        Map<String, Integer> seen = new HashMap<>();
        ticks.setUnansweredHandler(e -> {
            int count = seen.merge(e.what(), 1, Integer::sum);
            if (count == 1) {
                System.out.println("[kestrel] RANDOM TICK UNANSWERED: " + e.getMessage());
                e.printStackTrace(System.out);
            } else if (Integer.bitCount(count) == 1) {
                System.out.println("[kestrel] RANDOM TICK UNANSWERED x" + count + ": " + e.getMessage());
            }
        });
        ticks.setStatsListener(line -> System.out.println("[kestrel] " + line));
        ticks.setSimulationDistance(simulationDistance);
        netServer.setRandomTicks(ticks);
        System.out.println("[kestrel] random ticks on simulation-distance=" + simulationDistance
                + " lootMs=" + (System.nanoTime() - started) / 1_000_000);
    }
}
