package dev.kestrel.loop;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * G12 vanilla redstone oracle capture: boots a real 26.2 dedicated server
 * in this JVM (flat world, no structures), freezes the tick clock, and runs
 * every {@link RedstoneScenarios} scenario one {@code /tick step} at a time,
 * recording cell deltas, scheduled tick queues, and block events.
 */
public final class RedstoneOracle {
    private RedstoneOracle() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: RedstoneOracle OUT_DIR [scenario...]");
            System.exit(2);
        }
        Path out = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(out);
        Set<String> only = new HashSet<>();
        for (int i = 1; i < args.length; i++) {
            only.add(args[i]);
        }

        Files.writeString(Path.of("eula.txt"), "eula=true\n");
        Files.writeString(Path.of("server.properties"), String.join("\n",
                "level-type=minecraft\\:flat",
                "generate-structures=false",
                "online-mode=false",
                "server-port=25798",
                "spawn-protection=0",
                "max-tick-time=-1",
                "sync-chunk-writes=false",
                "view-distance=3",
                "simulation-distance=3",
                "level-name=redstone-oracle-world",
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
        System.out.println("[redstone-oracle] server up, tick " + server.getTickCount());

        onServer(server, () -> {
            ServerLevel ow = server.overworld();
            ow.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
            ow.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
            server.tickRateManager().setFrozen(true);
            run(server, "forceload add -32 -32 31 31", ow);
            installEventLogger(ow);
        });

        // Let the forced chunks reach entity-ticking status.
        deadline = System.currentTimeMillis() + 120_000;
        while (!onServerGet(server, () -> ticking(server.overworld()))) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("scenario chunks never became ticking in overworld");
            }
            Thread.sleep(100);
        }
        System.out.println("[redstone-oracle] scenario chunks ticking");

        for (RedstoneScenarios.Scenario s : RedstoneScenarios.all()) {
            if (!only.isEmpty() && !only.contains(s.name())) {
                continue;
            }
            ServerLevel level = server.overworld();
            List<String> lines = capture(server, level, s);
            Files.write(out.resolve(s.name() + ".txt"), lines);
            System.out.println("[redstone-oracle] " + s.name() + ": " + lines.size() + " lines");
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

    private static List<String> capture(MinecraftServer server, ServerLevel level, RedstoneScenarios.Scenario s)
            throws Exception {
        long[] base = new long[1];
        RedstoneScenarios.Recorder rec = new RedstoneScenarios.Recorder(s);
        EventLoggingSet eventLogger = installEventLogger(level);

        onServer(server, () -> {
            BoundingBox box = new BoundingBox(RedstoneScenarios.MIN_X - 2, RedstoneScenarios.MIN_Y - 2, RedstoneScenarios.MIN_Z - 2,
                    RedstoneScenarios.MAX_X + 2, RedstoneScenarios.MAX_Y + 2, RedstoneScenarios.MAX_Z + 2);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        m.set(x, y, z);
                        if (!level.getBlockState(m).isAir()) {
                            level.setBlock(m, RedstoneScenarios.AIR, RedstoneScenarios.RAW_FLAGS);
                        }
                    }
                }
            }
            level.getFluidTicks().clearArea(box);
            level.getBlockTicks().clearArea(box);
            eventLogger.reset();
            clearRecentToggles();

            for (RedstoneScenarios.Cell c : s.setup()) {
                level.setBlock(c.pos(), c.state(), 3);
                if (c.containerSlot() >= 0 && !c.containerItem().isEmpty()) {
                    if (level.getBlockEntity(c.pos()) instanceof Container container) {
                        container.setItem(c.containerSlot(), c.containerItem().copy());
                        level.updateNeighbourForOutputSignal(c.pos(), c.state().getBlock());
                    }
                }
            }
        });

        // Settle: step unrecorded until the box has no pending ticks and no
        // cell has changed over the last 2 steps. Setups must contain no
        // clocks, or this never converges.
        Map<Long, BlockState> prev1 = onServerGet(server, () -> snapshot(level));
        Map<Long, BlockState> prev2 = null;
        boolean settled = false;
        for (int i = 0; i < 400 && !settled; i++) {
            step(server);
            Map<Long, BlockState> cur = onServerGet(server, () -> snapshot(level));
            List<String> pending = onServerGet(server, () -> queue(level, level.getGameTime()));
            if (pending.isEmpty() && cur.equals(prev1) && (prev2 == null || cur.equals(prev2))) {
                settled = true;
            }
            prev2 = prev1;
            prev1 = cur;
        }
        if (!settled) {
            throw new IllegalStateException("scenario " + s.name() + " never settled after setup (400 step cap)");
        }

        onServer(server, () -> {
            eventLogger.reset();
            Map<Long, BlockState> snap = snapshot(level);
            List<String> beLines = blockEntityLines(level, snap);
            rec.recordSettled(snap, beLines);

            base[0] = level.getGameTime();
            eventLogger.setBaseTime(base[0]);
            applyActions(level, s, 0);
            rec.record(0, (x, y, z) -> level.getBlockState(new BlockPos(x, y, z)), queue(level, base[0]),
                    eventLogger.takeEvents());
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
                        eventLogger.takeEvents());
                applyActions(level, s, t);
            });
        }
        return rec.lines();
    }

    private static void applyActions(ServerLevel level, RedstoneScenarios.Scenario s, int tick) {
        for (RedstoneScenarios.Action a : s.actions()) {
            if (a.tick() == tick) {
                a.apply(level);
            }
        }
    }

    /** Non-air cells inside the recorded box, keyed like {@code Recorder.last}. */
    private static Map<Long, BlockState> snapshot(ServerLevel level) {
        Map<Long, BlockState> out = new HashMap<>();
        for (int x = RedstoneScenarios.MIN_X; x <= RedstoneScenarios.MAX_X; x++) {
            for (int y = RedstoneScenarios.MIN_Y; y <= RedstoneScenarios.MAX_Y; y++) {
                for (int z = RedstoneScenarios.MIN_Z; z <= RedstoneScenarios.MAX_Z; z++) {
                    BlockState st = level.getBlockState(new BlockPos(x, y, z));
                    if (!st.isAir()) {
                        out.put(BlockPos.asLong(x, y, z), st);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Block-entity state that matters to redstone but cannot be inferred from
     * the block state: comparator output signal, and container contents.
     */
    private static List<String> blockEntityLines(ServerLevel level, Map<Long, BlockState> snap) {
        List<String> out = new ArrayList<>();
        for (Long key : snap.keySet()) {
            BlockPos pos = BlockPos.of(key);
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ComparatorBlockEntity comparator) {
                out.add("BE " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + " output=" + comparator.getOutputSignal());
            } else if (be instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        out.add("BE " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                                + " slot" + slot + "=" + id + ":" + stack.getCount());
                    }
                }
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * {@code RedstoneTorchBlock.RECENT_TOGGLES} is a static per-level map
     * (WeakHashMap&lt;BlockGetter, List&lt;Toggle&gt;&gt;) that otherwise leaks
     * burnout-clock toggle history between scenarios sharing the same level.
     */
    private static void clearRecentToggles() {
        try {
            Field f = net.minecraft.world.level.block.RedstoneTorchBlock.class.getDeclaredField("RECENT_TOGGLES");
            f.setAccessible(true);
            ((Map<?, ?>) f.get(null)).clear();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to clear RedstoneTorchBlock.RECENT_TOGGLES", e);
        }
    }

    /** Pending block and fluid ticks inside the box, in vanilla drain order. */
    @SuppressWarnings("unchecked")
    private static List<String> queue(ServerLevel level, long base) {
        try {
            Field f = LevelTicks.class.getDeclaredField("allContainers");
            f.setAccessible(true);

            LevelTicks<Block> blockTicks = level.getBlockTicks();
            Map<Long, LevelChunkTicks<Block>> allBlock = (Map<Long, LevelChunkTicks<Block>>) f.get(blockTicks);
            List<ScheduledTick<Block>> blockList = new ArrayList<>();
            for (LevelChunkTicks<Block> c : allBlock.values()) {
                c.getAll().filter(t -> RedstoneScenarios.inBox(t.pos())).forEach(blockList::add);
            }
            blockList.sort(ScheduledTick.DRAIN_ORDER);

            LevelTicks<Fluid> fluidTicks = level.getFluidTicks();
            Map<Long, LevelChunkTicks<Fluid>> allFluid = (Map<Long, LevelChunkTicks<Fluid>>) f.get(fluidTicks);
            List<ScheduledTick<Fluid>> fluidList = new ArrayList<>();
            for (LevelChunkTicks<Fluid> c : allFluid.values()) {
                c.getAll().filter(t -> RedstoneScenarios.inBox(t.pos())).forEach(fluidList::add);
            }
            fluidList.sort(ScheduledTick.DRAIN_ORDER);

            List<String> out = new ArrayList<>(blockList.size() + fluidList.size());
            for (ScheduledTick<Block> t : blockList) {
                out.add(RedstoneScenarios.blockTickLine(t.pos(), t.type(), t.triggerTick() - base,
                        t.priority().getValue(), t.subTickOrder()));
            }
            for (ScheduledTick<Fluid> t : fluidList) {
                out.add(RedstoneScenarios.fluidTickLine(t.pos(), t.type(), t.triggerTick() - base,
                        t.priority().getValue(), t.subTickOrder()));
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EventLoggingSet installEventLogger(ServerLevel level) {
        try {
            Field f = ServerLevel.class.getDeclaredField("blockEvents");
            f.setAccessible(true);
            Object current = f.get(level);
            if (current instanceof EventLoggingSet els) {
                return els;
            }
            EventLoggingSet els = new EventLoggingSet(level);
            f.set(level, els);
            return els;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to install blockEvents logger", e);
        }
    }

    public static final class EventLoggingSet extends ObjectLinkedOpenHashSet<BlockEventData> {
        private final ServerLevel level;
        private long baseTime = 0;
        private final List<String> currentTickEvents = new ArrayList<>();

        public EventLoggingSet(ServerLevel level) {
            super();
            this.level = level;
        }

        public void setBaseTime(long baseTime) {
            this.baseTime = baseTime;
        }

        public void reset() {
            super.clear();
            currentTickEvents.clear();
        }

        public List<String> takeEvents() {
            List<String> out = List.copyOf(currentTickEvents);
            currentTickEvents.clear();
            return out;
        }

        @Override
        public boolean add(BlockEventData e) {
            boolean added = super.add(e);
            if (added) {
                long tick = level.getGameTime() - baseTime;
                String id = BuiltInRegistries.BLOCK.getKey(e.block()).getPath();
                currentTickEvents.add("E " + tick + " " + e.pos().getX() + " " + e.pos().getY() + " " + e.pos().getZ()
                        + " " + id + " " + e.paramA() + " " + e.paramB());
            }
            return added;
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
