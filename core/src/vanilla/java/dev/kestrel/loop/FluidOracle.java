package dev.kestrel.loop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * G11 vanilla oracle capture: boots a real 26.2 dedicated server in this
 * JVM (the unmodified {@code net.minecraft.server.Main}, flat world, no
 * structures), freezes the tick clock, and runs every
 * {@link FluidScenarios} scenario one {@code /tick step} at a time,
 * recording the box and the pending fluid-tick queue after each step.
 *
 * <p>Run from an empty scratch directory (the server writes its world and
 * properties into the working directory), with
 * {@code --add-opens java.base/java.lang=ALL-UNNAMED} so the server instance
 * can be read off its registered shutdown hook:
 * <pre>
 * java --add-opens java.base/java.lang=ALL-UNNAMED -cp CP dev.kestrel.loop.FluidOracle OUT_DIR [scenario...]
 * </pre>
 * Output: one {@code <scenario>.txt} per scenario in {@link FluidScenarios}
 * format. The fixtures are committed; {@link FluidParityCheck} replays
 * against them without a vanilla server.
 *
 * <p>Oracle discipline: random ticks off ({@code random_tick_speed 0}), mob
 * spawning off, the scenario box cleared (blocks and both tick queues)
 * before each scenario, setup written raw ({@code 2|16|512}). Everything
 * else is untouched vanilla.
 */
public final class FluidOracle {
    private FluidOracle() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: FluidOracle OUT_DIR [scenario...]");
            System.exit(2);
        }
        Path out = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(out);
        java.util.Set<String> only = new java.util.HashSet<>();
        for (int i = 1; i < args.length; i++) {
            only.add(args[i]);
        }

        Files.writeString(Path.of("eula.txt"), "eula=true\n");
        Files.writeString(Path.of("server.properties"), String.join("\n",
                "level-type=minecraft\\:flat",
                "generate-structures=false",
                "online-mode=false",
                "server-port=25799",
                "spawn-protection=0",
                "max-tick-time=-1",
                "sync-chunk-writes=false",
                "view-distance=3",
                "simulation-distance=3",
                "level-name=oracle-world",
                "pause-when-empty-seconds=0",
                "") + "\n");

        net.minecraft.server.Main.main(new String[]{"--nogui"});
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            Runtime.getRuntime().halt(1);
        });
        MinecraftServer server = findServer();
        long deadline = System.currentTimeMillis() + 300_000;
        while (server.getTickCount() < 40) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("vanilla server did not start");
            }
            Thread.sleep(200);
        }
        System.out.println("[oracle] server up, tick " + server.getTickCount());

        onServer(server, () -> {
            ServerLevel ow = server.overworld();
            ow.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
            ow.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
            server.tickRateManager().setFrozen(true);
            run(server, "forceload add -32 -32 31 31", ow);
            run(server, "forceload add -32 -32 31 31", server.getLevel(Level.NETHER));
            installDrawRecorder(ow);
            installDrawRecorder(server.getLevel(Level.NETHER));
        });
        // Let the forced chunks reach entity-ticking status.
        for (ServerLevel level : List.of(server.overworld(), server.getLevel(Level.NETHER))) {
            deadline = System.currentTimeMillis() + 120_000;
            while (!onServerGet(server, () -> ticking(level))) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("scenario chunks never became ticking in " + level.dimension());
                }
                Thread.sleep(100);
            }
        }
        System.out.println("[oracle] scenario chunks ticking");

        for (FluidScenarios.Scenario s : FluidScenarios.all()) {
            if (!only.isEmpty() && !only.contains(s.name())) {
                continue;
            }
            ServerLevel level = s.nether() ? server.getLevel(Level.NETHER) : server.overworld();
            List<String> lines = capture(server, level, s);
            Files.write(out.resolve(s.name() + ".txt"), lines);
            System.out.println("[oracle] " + s.name() + ": " + lines.size() + " lines");
        }
        server.halt(false);
        System.exit(0);
    }

    private static boolean ticking(ServerLevel level) {
        for (int cx = -1; cx <= 0; cx++) {
            for (int cz = -1; cz <= 0; cz++) {
                if (!level.isPositionTickingWithEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(cx, cz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<String> capture(MinecraftServer server, ServerLevel level, FluidScenarios.Scenario s)
            throws Exception {
        long[] base = new long[1];
        FluidScenarios.Recorder rec = new FluidScenarios.Recorder(s);
        onServer(server, () -> {
            BoundingBox box = new BoundingBox(FluidScenarios.MIN_X - 2, FluidScenarios.MIN_Y - 2, FluidScenarios.MIN_Z - 2,
                    FluidScenarios.MAX_X + 2, FluidScenarios.MAX_Y + 2, FluidScenarios.MAX_Z + 2);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        level.setBlock(m.set(x, y, z), FluidScenarios.AIR, FluidScenarios.RAW_FLAGS);
                    }
                }
            }
            level.getFluidTicks().clearArea(box);
            level.getBlockTicks().clearArea(box);
            for (FluidScenarios.Cell c : s.setup()) {
                level.setBlock(c.pos(), c.state(), FluidScenarios.RAW_FLAGS);
            }
            base[0] = level.getGameTime();
            LavaDraws.DRAWS.clear();
            applyActions(level, s, 0);
            rec.record(0, (x, y, z) -> level.getBlockState(new BlockPos(x, y, z)), queue(level, base[0]),
                    LavaDraws.take());
        });
        for (int tick = 1; tick <= s.ticks(); tick++) {
            step(server);
            final int t = tick;
            onServer(server, () -> {
                if (level.getGameTime() != base[0] + t) {
                    throw new IllegalStateException("step drift: gameTime " + level.getGameTime()
                            + " expected " + (base[0] + t));
                }
                rec.record(t, (x, y, z) -> level.getBlockState(new BlockPos(x, y, z)), queue(level, base[0]),
                        LavaDraws.take());
                applyActions(level, s, t);
            });
        }
        return rec.lines();
    }

    private static void applyActions(ServerLevel level, FluidScenarios.Scenario s, int tick) {
        for (FluidScenarios.Action a : s.actions()) {
            if (a.tick() == tick) {
                level.setBlock(a.pos(), a.state(), a.flags());
            }
        }
    }

    /** Pending fluid ticks inside the box, in vanilla drain order. */
    @SuppressWarnings("unchecked")
    private static List<String> queue(ServerLevel level, long base) {
        try {
            LevelTicks<Fluid> ticks = level.getFluidTicks();
            Field f = LevelTicks.class.getDeclaredField("allContainers");
            f.setAccessible(true);
            Map<Long, LevelChunkTicks<Fluid>> all = (Map<Long, LevelChunkTicks<Fluid>>) f.get(ticks);
            List<ScheduledTick<Fluid>> list = new ArrayList<>();
            for (LevelChunkTicks<Fluid> c : all.values()) {
                c.getAll().filter(t -> FluidScenarios.inBox(t.pos())).forEach(list::add);
            }
            list.sort(ScheduledTick.DRAIN_ORDER);
            List<String> out = new ArrayList<>(list.size());
            for (ScheduledTick<Fluid> t : list) {
                out.add(FluidScenarios.tickLine(t.pos(), t.type(), t.triggerTick() - base, t.priority().getValue()));
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Swap the level random for a delegating one that records every
     * {@code nextInt} drawn from inside {@code LavaFluid.getSpreadDelay}
     * (same underlying sequence, so vanilla behaviour is untouched).
     */
    private static void installDrawRecorder(ServerLevel level) {
        try {
            Field f = Level.class.getDeclaredField("random");
            f.setAccessible(true);
            RandomSource inner = (RandomSource) f.get(level);
            if (!(inner instanceof LavaDraws)) {
                f.set(level, new LavaDraws(inner));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Delegating level random that logs lava spread-delay draws. */
    static final class LavaDraws implements RandomSource {
        static final List<Integer> DRAWS = new ArrayList<>();
        private static final StackWalker WALKER = StackWalker.getInstance();
        private final RandomSource inner;

        LavaDraws(RandomSource inner) {
            this.inner = inner;
        }

        static List<Integer> take() {
            List<Integer> out = List.copyOf(DRAWS);
            DRAWS.clear();
            return out;
        }

        private static boolean fromLavaSpreadDelay() {
            return WALKER.walk(st -> st.limit(4).anyMatch(fr ->
                    fr.getMethodName().equals("getSpreadDelay")
                            && fr.getClassName().startsWith("net.minecraft.world.level.material.LavaFluid")));
        }

        @Override
        public RandomSource fork() {
            return inner.fork();
        }

        @Override
        public net.minecraft.world.level.levelgen.PositionalRandomFactory forkPositional() {
            return inner.forkPositional();
        }

        @Override
        public void setSeed(long seed) {
            inner.setSeed(seed);
        }

        @Override
        public int nextInt() {
            return inner.nextInt();
        }

        @Override
        public int nextInt(int bound) {
            int v = inner.nextInt(bound);
            if (fromLavaSpreadDelay()) {
                DRAWS.add(v);
            }
            return v;
        }

        @Override
        public long nextLong() {
            return inner.nextLong();
        }

        @Override
        public boolean nextBoolean() {
            return inner.nextBoolean();
        }

        @Override
        public float nextFloat() {
            return inner.nextFloat();
        }

        @Override
        public double nextDouble() {
            return inner.nextDouble();
        }

        @Override
        public double nextGaussian() {
            return inner.nextGaussian();
        }
    }

    /** One frozen-clock step, returning once the stepped tick has finished. */
    private static void step(MinecraftServer server) throws Exception {
        onServer(server, () -> {
            if (!server.tickRateManager().stepGameIfPaused(1)) {
                throw new IllegalStateException("step refused (not frozen?)");
            }
        });
        long deadline = System.currentTimeMillis() + 30_000;
        while (onServerGet(server, () -> server.tickRateManager().isSteppingForward())) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("step never completed");
            }
            Thread.sleep(2);
        }
    }

    private static void run(MinecraftServer server, String command, ServerLevel level) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withLevel(level).withSuppressedOutput(), command);
    }

    private static void onServer(MinecraftServer server, Runnable r) throws Exception {
        server.submit(r).get(60, TimeUnit.SECONDS);
    }

    private static <T> T onServerGet(MinecraftServer server, java.util.function.Supplier<T> s) throws Exception {
        return server.submit(s).get(60, TimeUnit.SECONDS);
    }

    /** The DedicatedServer captured by Main's registered shutdown hook. */
    @SuppressWarnings("unchecked")
    private static MinecraftServer findServer() throws Exception {
        Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
        Field hooksField = hooksClass.getDeclaredField("hooks");
        hooksField.setAccessible(true);
        Map<Thread, Thread> hooks = (Map<Thread, Thread>) hooksField.get(null);
        for (Thread t : hooks.keySet()) {
            if (!"Server Shutdown Thread".equals(t.getName())) {
                continue;
            }
            for (Field f : t.getClass().getDeclaredFields()) {
                if (MinecraftServer.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (MinecraftServer) f.get(t);
                }
            }
        }
        throw new IllegalStateException("no vanilla server shutdown hook registered (did Main fail?)");
    }
}
