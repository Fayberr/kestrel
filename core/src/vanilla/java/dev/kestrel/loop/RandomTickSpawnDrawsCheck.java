package dev.kestrel.loop;

import dev.kestrel.worldgen.HeadlessNoise;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;

/** G30 slice 2 gate for loud, phase-correct reporting of G44's missing draws. */
public final class RandomTickSpawnDrawsCheck {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static final int PRECIPITATION_PROBES = 4096;
    private static final long RANDOM_SEED = 0x30_44_0002L;

    private static int checks;
    private static int scenarios;
    private static final List<String> failures = new ArrayList<>();
    private static HeadlessNoise.Driver driver;

    private RandomTickSpawnDrawsCheck() {
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        driver = HeadlessNoise.loadStructural(1L).driver();

        ruleFalseIsUnchanged();
        ruleTrueReportsAtVanillaPhases();
        liveRuleFlipsAreReadEveryTick();
        leavingPlayerEmptiesNaturalSpawnSet();
        absentListenerRethrowsWithoutBlockWrites();

        if (!failures.isEmpty()) {
            System.out.println("RandomTickSpawnDrawsCheck FAILED with " + failures.size() + " failure(s):");
            failures.forEach(failure -> System.out.println("  - " + failure));
            System.exit(1);
        }
        System.out.println("RandomTickSpawnDrawsCheck: PASSED (" + checks + " checks, " + scenarios
                + " scenarios)");
    }

    private static void ruleFalseIsUnchanged() {
        scenarios++;
        Arena arena = new Arena(false, true);
        arena.tick();
        expect(arena.unanswered.isEmpty(), "rule false: reported " + arena.unanswered);
        expect(arena.writes == 0, "rule false: changed block output by " + arena.writes + " writes");
        expect(arena.events.contains("precipitation"), "rule false: the populated arena did not tick chunks");
    }

    private static void ruleTrueReportsAtVanillaPhases() {
        scenarios++;
        Arena arena = new Arena(true, true);
        arena.tick();
        expect(arena.unanswered.equals(List.of(
                "natural mob spawning level-random draws, unknown draw count|G44",
                "custom spawner level-random draws, unknown draw count|G44")),
                "rule true: wrong reports " + arena.unanswered);
        int natural = arena.events.indexOf("unanswered:natural");
        int precipitation = arena.events.indexOf("precipitation");
        int custom = arena.events.indexOf("unanswered:custom");
        int publication = arena.events.indexOf("publication");
        expect(natural >= 0 && natural < precipitation,
                "rule true: natural-spawn report was not before block ticking: " + arena.events);
        expect(precipitation < custom && custom < publication,
                "rule true: custom-spawner report was not after block ticking and before publication: "
                        + arena.events);
        expect(arena.writes == 0, "rule true: reporting changed block output by " + arena.writes + " writes");
    }

    private static void liveRuleFlipsAreReadEveryTick() {
        scenarios++;
        Arena arena = new Arena(true, true);
        arena.tick();
        int firstTickReports = arena.unanswered.size();
        arena.loop.gameRules().set(GameRules.SPAWN_MOBS, false, null);
        arena.tick();
        expect(firstTickReports == 2, "rule flip: true tick had " + firstTickReports + " reports");
        expect(arena.unanswered.size() == firstTickReports,
                "rule flip: false tick still reported " + arena.unanswered.subList(firstTickReports,
                        arena.unanswered.size()));

        Arena startsFalse = new Arena(false, true);
        startsFalse.tick();
        startsFalse.loop.gameRules().set(GameRules.SPAWN_MOBS, true, null);
        startsFalse.tick();
        expect(startsFalse.unanswered.size() == 2,
                "rule flip: false-to-true tick did not use the live rule: " + startsFalse.unanswered);
    }

    private static void leavingPlayerEmptiesNaturalSpawnSet() {
        scenarios++;
        Arena arena = new Arena(true, true);
        arena.tick();
        arena.unanswered.clear();
        arena.events.clear();
        arena.ticks.removePlayer("p");
        arena.tick();
        expect(arena.unanswered.equals(List.of("custom spawner level-random draws, unknown draw count|G44")),
                "player left: wrong reports " + arena.unanswered);
        expect(!arena.events.contains("unanswered:natural"),
                "player left: natural-spawn report remained with no eligible chunks");
    }

    private static void absentListenerRethrowsWithoutBlockWrites() {
        scenarios++;
        Arena arena = new Arena(true, false);
        boolean threw = false;
        try {
            arena.tick();
        } catch (Unanswered unanswered) {
            threw = true;
            expect(unanswered.owner().equals("G44"), "absent listener: wrong owner " + unanswered.owner());
            expect(unanswered.what().contains("unknown draw count"),
                    "absent listener: message invented a draw count: " + unanswered.what());
        }
        expect(threw, "absent listener: missing draws were silently swallowed");
        expect(arena.writes == 0, "absent listener: changed block output by " + arena.writes + " writes");
    }

    private static void expect(boolean condition, String failure) {
        checks++;
        if (!condition) {
            failures.add(failure);
        }
    }

    private static final class Arena {
        final TickLoop loop = new TickLoop(new SectionStore(MIN_Y, HEIGHT));
        final RandomTicks ticks = loop.randomTicks();
        final List<String> unanswered = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        long writes;

        Arena(boolean spawnMobs, boolean installListener) {
            loop.setLevelRandom(RandomSource.create(RANDOM_SEED));
            loop.gameRules().set(GameRules.RANDOM_TICK_SPEED, PRECIPITATION_PROBES, null);
            loop.gameRules().set(GameRules.SPAWN_MOBS, spawnMobs, null);
            loop.setBlock(new BlockPos(0, 63, 0), Blocks.STONE.defaultBlockState(), 2);
            loop.drainBlockChanges(pos -> { });
            loop.setDetailedWriteListener((pos, oldState, newState, flags) -> writes++);
            loop.setBeforeEntityTracker(() -> events.add("publication"));
            ticks.setSimulationDistance(0);
            ticks.setEnvironment(new RandomTicks.Environment() {
                @Override
                public RegistryAccess registryAccess() {
                    return driver.access();
                }

                @Override
                public long biomeZoomSeed() {
                    return BiomeManager.obfuscateSeed(1L);
                }

                @Override
                public Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
                    return driver.generator().getBiomeSource().getNoiseBiome(quartX, quartY, quartZ,
                            driver.randomState().sampler());
                }

                @Override
                public float rainLevel() {
                    events.add("precipitation");
                    return 0.0F;
                }
            });
            ticks.addPlayer("p", 8.5, 64.0, 8.5, false);
            if (installListener) {
                ticks.setUnansweredHandler(this::record);
            }
        }

        void tick() {
            loop.tick();
        }

        void record(Unanswered failure) {
            unanswered.add(failure.what() + "|" + failure.owner());
            if (failure.what().startsWith("natural")) {
                events.add("unanswered:natural");
            } else if (failure.what().startsWith("custom")) {
                events.add("unanswered:custom");
            }
        }
    }
}
