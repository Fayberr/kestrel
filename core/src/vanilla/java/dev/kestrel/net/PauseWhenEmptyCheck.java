package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.entity.EntityIds;
import dev.kestrel.entity.KestrelEntity;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Gate check for S32 slice: pause-when-empty-seconds.
 *
 * <p>Validates that Kestrel's main server loop ({@link KestrelServer.ServerLoop})
 * matches vanilla {@code MinecraftServer.tickServer} (:972-1000) tick by tick,
 * stops world ticking when empty, preserves world observables while paused,
 * serves status pings and logins while paused, and resumes ticking on player join.
 *
 * <p>Vanilla line citations:
 * <ul>
 *   <li>{@code DedicatedServerProperties.java:129}: {@code pause-when-empty-seconds}, default 60.</li>
 *   <li>{@code MinecraftServer.java:2245}: {@code pauseWhenEmptySeconds()} returns 0 in base class.</li>
 *   <li>{@code DedicatedServer.java:826-828}: overrides with property value.</li>
 *   <li>{@code MinecraftServer.java:974}: {@code emptyTickThreshold = pauseWhenEmptySeconds() * 20}.</li>
 *   <li>{@code MinecraftServer.java:975}: {@code if (emptyTickThreshold > 0)}.</li>
 *   <li>{@code MinecraftServer.java:976-980}: {@code emptyTicks++} when {@code playerCount == 0}, else {@code emptyTicks = 0}.</li>
 *   <li>{@code MinecraftServer.java:982-986}: once {@code emptyTicks >= emptyTickThreshold}, logs message and calls {@code autoSave()} on first tick.</li>
 *   <li>{@code MinecraftServer.java:988-990}: calls {@code tickConnection()} and returns early.</li>
 *   <li>{@code MinecraftServer.java:993}: {@code tickCount++} (skipped while paused).</li>
 *   <li>{@code MinecraftServer.java:995}: {@code tickChildren(haveTime)} (skipped while paused).</li>
 * </ul>
 */
public final class PauseWhenEmptyCheck {
    private PauseWhenEmptyCheck() {
    }

    /**
     * Exact reference model of vanilla MinecraftServer.tickServer (:972-1000).
     */
    public static final class VanillaPauseOracle {
        private final int pauseWhenEmptySeconds;
        private int emptyTicks;
        private long tickCount;
        private boolean justPaused;

        public VanillaPauseOracle(int pauseWhenEmptySeconds) {
            this.pauseWhenEmptySeconds = pauseWhenEmptySeconds;
            this.emptyTicks = 0;
            this.tickCount = 0;
            this.justPaused = false;
        }

        /**
         * Simulates one tick per vanilla MinecraftServer.tickServer (:972-1000).
         *
         * @param playerCount count of online players (vanilla: playerList.getPlayerCount())
         * @return true if early return (paused), false if normal tick execution
         */
        public boolean tick(int playerCount) {
            justPaused = false;
            // MinecraftServer.java:974: int emptyTickThreshold = this.pauseWhenEmptySeconds() * 20;
            int emptyTickThreshold = pauseWhenEmptySeconds * 20;
            // MinecraftServer.java:975: if (emptyTickThreshold > 0)
            if (emptyTickThreshold > 0) {
                // MinecraftServer.java:976-980:
                // if (this.playerList.getPlayerCount() == 0 && !this.tickRateManager.isSprinting())
                //    this.emptyTicks++;
                // else
                //    this.emptyTicks = 0;
                if (playerCount == 0) {
                    emptyTicks++;
                } else {
                    emptyTicks = 0;
                }

                // MinecraftServer.java:982: if (this.emptyTicks >= emptyTickThreshold)
                if (emptyTicks >= emptyTickThreshold) {
                    // MinecraftServer.java:983: if (this.emptyTicks == emptyTickThreshold)
                    if (emptyTicks == emptyTickThreshold) {
                        justPaused = true;
                        // MinecraftServer.java:984-985: LOGGER.info(...); this.autoSave();
                    }
                    // MinecraftServer.java:988: this.tickConnection();
                    // MinecraftServer.java:989: return;
                    return true;
                }
            }

            // MinecraftServer.java:993: this.tickCount++;
            tickCount++;
            // MinecraftServer.java:995: this.tickChildren(haveTime);
            return false;
        }

        public int emptyTicks() {
            return emptyTicks;
        }

        public long tickCount() {
            return tickCount;
        }

        public boolean justPaused() {
            return justPaused;
        }

        public boolean isPaused() {
            int threshold = pauseWhenEmptySeconds * 20;
            return threshold > 0 && emptyTicks >= threshold;
        }
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        int checksRun = 0;

        System.out.println("[PauseWhenEmptyCheck] Starting S32 pause-when-empty gate checks...");

        // 1. Tick-by-tick parity with VanillaPauseOracle across scenarios
        checksRun += checkOracleParity(failures);

        // 2. Observables unchanged while paused (game time, day time, weather, scheduled ticks)
        checksRun += checkObservablesWhilePaused(failures);

        // 3. Status ping answered while paused
        checksRun += checkStatusPingWhilePaused(failures);

        // 4. Loopback login while paused unpauses and resumes world
        checksRun += checkLoginWhilePaused(failures);

        // 5. Player disconnect and re-pause cycle
        checksRun += checkDisconnectAndRepause(failures);

        // 6. Player connected the whole time never pauses
        checksRun += checkConnectedNeverPauses(failures);

        // 7. pause-when-empty-seconds=0 never pauses
        checksRun += checkZeroSecondsNeverPauses(failures);

        // 8. --no-save flag honored when pausing (writes nothing)
        checksRun += checkNoSaveHonored(failures);

        if (!failures.isEmpty()) {
            System.err.println("PauseWhenEmptyCheck FAILED with " + failures.size() + " failure(s):");
            for (String f : failures) {
                System.err.println("  - " + f);
            }
            System.exit(1);
        }

        System.out.println("PauseWhenEmptyCheck: PASS (" + checksRun + " assertions verified)");
    }

    private static int checkOracleParity(List<String> failures) {
        int checks = 0;
        // Scenario A: threshold 1 second (20 ticks) empty from boot
        {
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            try (NetServer net = new NetServer(loop, 0, Wire.DEFAULT_COMPRESSION_THRESHOLD, 4242424242L, false)) {
                ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
                KestrelServer.ServerLoop serverLoop = new KestrelServer.ServerLoop(loop, net, sender, 1);
                VanillaPauseOracle oracle = new VanillaPauseOracle(1);

                for (int t = 1; t <= 30; t++) {
                    boolean oraclePaused = oracle.tick(net.playerCount());
                    serverLoop.tick();
                    boolean kestrelPaused = serverLoop.isPaused();

                    assertEqual(kestrelPaused, oraclePaused,
                            "1s threshold tick " + t + " isPaused parity", failures);
                    assertEqual(serverLoop.emptyTicks(), oracle.emptyTicks(),
                            "1s threshold tick " + t + " emptyTicks parity", failures);
                    assertEqual(serverLoop.tickCount(), oracle.tickCount(),
                            "1s threshold tick " + t + " tickCount parity", failures);
                    checks += 3;

                    if (t == 20) {
                        assertField(kestrelPaused, "tick 20 must be paused (threshold reached)", failures);
                        assertField(oracle.justPaused(), "oracle marks tick 20 as justPaused", failures);
                        checks += 2;
                    } else if (t < 20) {
                        assertField(!kestrelPaused, "tick " + t + " must not be paused", failures);
                        checks++;
                    }
                }
            }
        }

        // Scenario B: default 60 seconds (1200 ticks) empty from boot
        {
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            try (NetServer net = new NetServer(loop, 0, Wire.DEFAULT_COMPRESSION_THRESHOLD, 4242424242L, false)) {
                ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
                KestrelServer.ServerLoop serverLoop = new KestrelServer.ServerLoop(loop, net, sender, 60);
                VanillaPauseOracle oracle = new VanillaPauseOracle(60);

                for (int t = 1; t <= 1250; t++) {
                    boolean oraclePaused = oracle.tick(net.playerCount());
                    serverLoop.tick();
                    boolean kestrelPaused = serverLoop.isPaused();

                    if (t % 100 == 0 || t >= 1195) {
                        assertEqual(kestrelPaused, oraclePaused,
                                "60s threshold tick " + t + " isPaused parity", failures);
                        assertEqual(serverLoop.emptyTicks(), oracle.emptyTicks(),
                                "60s threshold tick " + t + " emptyTicks parity", failures);
                        assertEqual(serverLoop.tickCount(), oracle.tickCount(),
                                "60s threshold tick " + t + " tickCount parity", failures);
                        checks += 3;
                    }
                    if (t == 1200) {
                        assertField(kestrelPaused, "tick 1200 must be paused for 60s default", failures);
                        checks++;
                    }
                }
            }
        }

        return checks;
    }

    private static int checkObservablesWhilePaused(List<String> failures) {
        int checks = 0;
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        try (NetServer net = new NetServer(loop, 0, Wire.DEFAULT_COMPRESSION_THRESHOLD, 4242424242L, false)) {
            ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
            KestrelServer.ServerLoop serverLoop = new KestrelServer.ServerLoop(loop, net, sender, 1);

            // Plant stone and unlit redstone torch with suppressed neighbor updates (2 | 16 | 512)
            BlockPos stonePos = new BlockPos(0, 64, 0);
            BlockPos torchPos = new BlockPos(0, 65, 0);
            loop.setBlock(stonePos, Blocks.STONE.defaultBlockState(), 2 | 16 | 512);
            BlockState unlitTorch = Blocks.REDSTONE_TORCH.defaultBlockState().setValue(RedstoneTorchBlock.LIT, false);
            loop.setBlock(torchPos, unlitTorch, 2 | 16 | 512);

            // Tick up to tick 19 (not yet paused)
            for (int t = 1; t <= 19; t++) {
                serverLoop.tick();
            }
            assertField(!serverLoop.isPaused(), "tick 19 is not paused", failures);
            checks++;

            // Plant scheduled tick just before pause (delay 1 triggers at tick 20)
            loop.scheduleTick(torchPos, Blocks.REDSTONE_TORCH, 1, TickPriority.NORMAL);

            // Plant one entity and one awake block entity immediately before
            // the pause. Their counters/state prove that the entity and
            // block-entity phases are skipped, not merely the clock phase.
            KestrelEntity entity = new KestrelEntity(
                    EntityIds.claim(), UUID.randomUUID(), EntityTypes.MARKER);
            entity.setPos(2.5, 64.0, 2.5);
            net.entityWorld().addFreshEntity(entity);
            BlockPos blockEntityPos = new BlockPos(3, 64, 3);
            loop.registerBlockEntity(blockEntityPos,
                    dev.kestrel.blockentity.SleeperRegistry.Type.FURNACE);
            long blockEntityId = blockEntityPos.asLong();
            assertField(!loop.sleepers().isAsleep(blockEntityId),
                    "block entity is awake immediately before pause", failures);
            checks++;

            // Tick 20 reaches threshold and pauses
            serverLoop.tick();
            assertField(serverLoop.isPaused(), "tick 20 is paused", failures);
            checks++;

            long pausedGameTime = loop.gameTime();
            long pausedDayTime = net.time.dayTime();
            boolean pausedRaining = net.time.raining();
            int pausedEntityTicks = entity.tickCount;

            // Run 2000 paused ticks
            for (int t = 0; t < 2000; t++) {
                serverLoop.tick();
            }

            // Assert observables completely unchanged over thousands of paused ticks
            assertEqual(loop.gameTime(), pausedGameTime,
                    "gameTime unchanged over 2000 paused ticks", failures);
            assertEqual(net.time.dayTime(), pausedDayTime,
                    "dayTime unchanged over 2000 paused ticks", failures);
            assertEqual(net.time.raining(), pausedRaining,
                    "weather raining unchanged over 2000 paused ticks", failures);
            assertEqual(entity.tickCount, pausedEntityTicks,
                    "entity tick count unchanged over 2000 paused ticks", failures);
            assertField(!loop.sleepers().isAsleep(blockEntityId),
                    "block entity pass did not run over 2000 paused ticks", failures);
            checks += 5;

            // Assert scheduled block tick did NOT fire while paused
            BlockState currentTorchState = loop.getBlockState(torchPos);
            assertEqual(currentTorchState.getValue(RedstoneTorchBlock.LIT), false,
                    "scheduled block tick must not fire while paused", failures);
            checks++;
        }
        return checks;
    }

    private static int checkStatusPingWhilePaused(List<String> failures) throws Exception {
        int checks = 0;
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        try (NetServer net = new NetServer(loop, 0, Wire.DEFAULT_COMPRESSION_THRESHOLD, 4242424242L, false)) {
            net.setMotd("PausePingTest");
            net.start();
            int port = net.boundPort();
            ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
            KestrelServer.ServerLoop serverLoop = new KestrelServer.ServerLoop(loop, net, sender, 1);

            // Tick 25 ticks to enter pause
            for (int i = 0; i < 25; i++) {
                serverLoop.tick();
            }
            assertField(serverLoop.isPaused(), "server is paused before ping test", failures);
            checks++;

            // Send Modern Status Ping while server is paused
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                sendHandshake(out, port, Wire.PROTOCOL_26_2, PacketIds.INTENT_STATUS);
                sendStatusRequest(out);
                readStatusResponse(in);

                long pingTime = 1234567890123L;
                sendPing(out, pingTime);

                long pongTime = readPong(in);
                assertEqual(pongTime, pingTime, "status ping answered while paused with matching timestamp", failures);
                checks++;
            } catch (Exception e) {
                failures.add("status ping while paused threw: " + e);
            }
        }
        return checks;
    }

    private static int checkLoginWhilePaused(List<String> failures) throws Exception {
        int checks = 0;
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        try (NetServer net = new NetServer(loop, 0, Wire.DEFAULT_COMPRESSION_THRESHOLD, 4242424242L, false)) {
            net.start();
            int port = net.boundPort();
            ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
            KestrelServer.ServerLoop serverLoop = new KestrelServer.ServerLoop(loop, net, sender, 1);

            // Advance to tick 19 (not yet paused)
            for (int i = 0; i < 19; i++) {
                serverLoop.tick();
            }
            assertField(!serverLoop.isPaused(), "server is not paused at tick 19", failures);
            checks++;

            // Plant unlit torch with suppressed neighbor updates (2 | 16 | 512)
            BlockPos torchPos = new BlockPos(1, 65, 1);
            loop.setBlock(torchPos.below(), Blocks.STONE.defaultBlockState(), 2 | 16 | 512);
            loop.setBlock(torchPos, Blocks.REDSTONE_TORCH.defaultBlockState().setValue(RedstoneTorchBlock.LIT, false), 2 | 16 | 512);
            // Schedule tick just before pause (delay 1 triggers at tick 20)
            loop.scheduleTick(torchPos, Blocks.REDSTONE_TORCH, 1, TickPriority.NORMAL);

            // Advance into pause (ticks 20 to 25)
            for (int i = 0; i < 6; i++) {
                serverLoop.tick();
            }
            assertField(serverLoop.isPaused(), "server is paused before join", failures);
            assertEqual(loop.getBlockState(torchPos).getValue(RedstoneTorchBlock.LIT), false,
                    "torch unlit before join", failures);
            checks += 2;

            // Connect a loopback client while paused
            try {
                NetCheck.Loopback client = new NetCheck.Loopback(port);
                client.send(0x00, new Wire.Writer().vint(776).utf("127.0.0.1").s(port).vint(2).toBytes());
                client.send(0x00, new Wire.Writer().utf("PauseBot").uuid(UUID.randomUUID()).toBytes());

                long deadline = System.currentTimeMillis() + 4000;
                boolean joined = false;
                long pausedPlayerTicks = net.bodies().connectionTicks();
                while (System.currentTimeMillis() < deadline && !joined) {
                    serverLoop.tick();
                    if (serverLoop.isPaused()) {
                        assertEqual(net.bodies().connectionTicks(), pausedPlayerTicks,
                                "paused server does not tick player connections", failures);
                        checks++;
                    }

                    Wire.Inbound in;
                    try {
                        client.socket.setSoTimeout(50);
                        in = client.read();
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    }
                    if (in.id() == PacketIds.LOGIN_COMPRESSION && client.threshold < 0) {
                        client.threshold = in.reader().varInt();
                    } else if (in.id() == PacketIds.LOGIN_FINISHED) {
                        client.sendC(PacketIds.LOGIN_ACK, new byte[0]);
                    } else if (in.id() == PacketIds.CONFIG_SELECT_KNOWN_OUT) {
                        client.sendC(PacketIds.CONFIG_SELECT_KNOWN, new Wire.Writer().vint(0).toBytes());
                        client.sendC(PacketIds.CONFIG_FINISH, new byte[0]);
                    } else if (in.id() == PacketIds.PLAY_JOIN) {
                        joined = true;
                    }
                }

                assertField(joined, "loopback client joined while paused", failures);
                assertField(!serverLoop.isPaused(), "server unpaused on join", failures);
                assertEqual(serverLoop.emptyTicks(), 0, "emptyTicks reset to 0 on join", failures);
                checks += 3;

                // Drive 2 more ticks to process scheduled block tick and initial placement
                for (int i = 0; i < 2; i++) {
                    serverLoop.tick();
                }

                // Assert scheduled block tick fired after join resumed the world
                BlockState litTorch = loop.getBlockState(torchPos);
                assertEqual(litTorch.getValue(RedstoneTorchBlock.LIT), true,
                        "scheduled block tick fired after join unpaused the world", failures);
                checks++;

                client.socket.close();
            } catch (Exception e) {
                failures.add("login while paused threw: " + e);
            }
        }
        return checks;
    }

    private static int checkDisconnectAndRepause(List<String> failures) throws Exception {
        int checks = 0;
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        try (NetServer net = new NetServer(loop, 0, Wire.DEFAULT_COMPRESSION_THRESHOLD, 4242424242L, false)) {
            net.start();
            int port = net.boundPort();
            ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
            KestrelServer.ServerLoop serverLoop = new KestrelServer.ServerLoop(loop, net, sender, 1);

            // Connect a player
            try {
                NetCheck.Loopback client = NetCheck.joinLoopbackPort(loop, net, sender, port, "CycleBot");
                assertField(client != null, "CycleBot connected", failures);
                assertEqual(net.playerCount(), 1, "playerCount is 1", failures);
                checks += 2;

                // Tick 10 ticks while connected: never pauses
                for (int i = 0; i < 10; i++) {
                    serverLoop.tick();
                    assertField(!serverLoop.isPaused(), "server not paused while player online", failures);
                    assertEqual(serverLoop.emptyTicks(), 0, "emptyTicks 0 while player online", failures);
                    checks += 2;
                }

                // Disconnect player
                client.socket.close();
                // Pump events so disconnect is processed
                for (int i = 0; i < 5; i++) {
                    net.pollEvents();
                    if (net.playerCount() == 0) {
                        break;
                    }
                    Thread.sleep(10);
                }
                assertEqual(net.playerCount(), 0, "playerCount returned to 0 after disconnect", failures);
                checks++;

                // Now tick 19 ticks: emptyTicks increments, not yet paused
                for (int t = 1; t <= 19; t++) {
                    serverLoop.tick();
                    assertEqual(serverLoop.emptyTicks(), t, "emptyTicks is " + t + " after disconnect", failures);
                    assertField(!serverLoop.isPaused(), "not paused at tick " + t, failures);
                    checks += 2;
                }

                // Tick 20: reaches threshold and pauses again
                serverLoop.tick();
                assertField(serverLoop.isPaused(), "server re-paused at tick 20 after disconnect", failures);
                assertEqual(serverLoop.emptyTicks(), 20, "emptyTicks is 20 on re-pause", failures);
                checks += 2;
            } catch (Exception e) {
                failures.add("disconnect and repause test threw: " + e);
            }
        }
        return checks;
    }

    private static int checkConnectedNeverPauses(List<String> failures) throws Exception {
        int checks = 0;
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        try (NetServer net = new NetServer(loop, 0, Wire.DEFAULT_COMPRESSION_THRESHOLD, 4242424242L, false)) {
            net.start();
            int port = net.boundPort();
            ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
            KestrelServer.ServerLoop serverLoop = new KestrelServer.ServerLoop(loop, net, sender, 1);

            try {
                NetCheck.Loopback client = NetCheck.joinLoopbackPort(loop, net, sender, port, "AlwaysOnline");
                assertField(client != null, "AlwaysOnline connected", failures);
                checks++;

                // Tick 100 ticks (well past 20 threshold)
                for (int i = 0; i < 100; i++) {
                    serverLoop.tick();
                }
                assertField(!serverLoop.isPaused(), "server never pauses when player online", failures);
                assertEqual(serverLoop.emptyTicks(), 0, "emptyTicks stays 0 when player online", failures);
                assertEqual(serverLoop.tickCount(), 100L, "all 100 ticks executed", failures);
                checks += 3;

                client.socket.close();
            } catch (Exception e) {
                failures.add("connected never pauses threw: " + e);
            }
        }
        return checks;
    }

    private static int checkZeroSecondsNeverPauses(List<String> failures) {
        int checks = 0;
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        try (NetServer net = new NetServer(loop, 0, Wire.DEFAULT_COMPRESSION_THRESHOLD, 4242424242L, false)) {
            ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
            // pauseWhenEmptySeconds = 0 disables pause
            KestrelServer.ServerLoop serverLoop = new KestrelServer.ServerLoop(loop, net, sender, 0);
            VanillaPauseOracle oracle = new VanillaPauseOracle(0);

            for (int t = 1; t <= 100; t++) {
                oracle.tick(net.playerCount());
                serverLoop.tick();

                assertEqual(serverLoop.isPaused(), false, "pauseWhenEmptySeconds=0 is never paused", failures);
                assertEqual(serverLoop.emptyTicks(), 0, "emptyTicks is 0 when pause disabled", failures);
                assertEqual(serverLoop.tickCount(), (long) t, "tickCount advances every tick", failures);
                assertEqual(serverLoop.isPaused(), oracle.isPaused(), "oracle parity for seconds=0", failures);
                checks += 4;
            }
        }
        return checks;
    }

    private static int checkNoSaveHonored(List<String> failures) throws IOException {
        int checks = 0;
        Path tempWorld = Files.createTempDirectory("kestrel-pause-nosave-");
        try {
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            try (NetServer net = new NetServer(loop, 0, Wire.DEFAULT_COMPRESSION_THRESHOLD, 4242424242L, false)) {
                ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
                // saving = false (--no-save)
                KestrelServer.ServerLoop serverLoop = new KestrelServer.ServerLoop(
                        loop, net, sender, tempWorld, false, 1);

                // Run past pause threshold (tick 20 triggers autoSave)
                for (int t = 1; t <= 25; t++) {
                    serverLoop.tick();
                }
                assertField(serverLoop.isPaused(), "server is paused", failures);
                checks++;

                // Verify nothing was written to tempWorld
                try (var stream = Files.list(tempWorld)) {
                    long fileCount = stream.count();
                    assertEqual(fileCount, 0L, "--no-save world directory contains 0 files after pause autosave", failures);
                    checks++;
                }
            }
        } finally {
            deleteTree(tempWorld);
        }
        return checks;
    }

    // ---- Network helpers for Status Ping ----

    private static void sendHandshake(OutputStream out, int port, int protocol, int intent) throws IOException {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        writeVarInt(body, PacketIds.HS_INTENTION);
        writeVarInt(body, protocol);
        byte[] hostBytes = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
        writeVarInt(body, hostBytes.length);
        body.write(hostBytes);
        body.write((port >> 8) & 0xFF);
        body.write(port & 0xFF);
        writeVarInt(body, intent);

        byte[] bytes = body.toByteArray();
        writeVarInt(out, bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static void sendStatusRequest(OutputStream out) throws IOException {
        int payloadLen = varIntSize(PacketIds.STATUS_REQUEST);
        writeVarInt(out, payloadLen);
        writeVarInt(out, PacketIds.STATUS_REQUEST);
        out.flush();
    }

    private static String readStatusResponse(InputStream in) throws IOException {
        readVarInt(in); // frameLen
        int packetId = readVarInt(in);
        if (packetId != PacketIds.STATUS_RESPONSE) {
            throw new IOException("expected packet id " + PacketIds.STATUS_RESPONSE + ", got " + packetId);
        }
        int jsonLen = readVarInt(in);
        byte[] jsonBytes = in.readNBytes(jsonLen);
        return new String(jsonBytes, StandardCharsets.UTF_8);
    }

    private static void sendPing(OutputStream out, long time) throws IOException {
        int payloadLen = varIntSize(PacketIds.STATUS_PING) + 8;
        writeVarInt(out, payloadLen);
        writeVarInt(out, PacketIds.STATUS_PING);
        out.write(java.nio.ByteBuffer.allocate(8).putLong(time).array());
        out.flush();
    }

    private static long readPong(InputStream in) throws IOException {
        readVarInt(in); // frameLen
        int packetId = readVarInt(in);
        if (packetId != PacketIds.STATUS_PONG) {
            throw new IOException("expected packet id " + PacketIds.STATUS_PONG + ", got " + packetId);
        }
        DataInputStream dis = new DataInputStream(in);
        return dis.readLong();
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.read();
            if (b == -1) {
                throw new EOFException("unexpected end of stream reading varint");
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("varint too big");
    }

    private static int varIntSize(int value) {
        int size = 0;
        do {
            size++;
            value >>>= 7;
        } while (value != 0);
        return size;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private static void assertEqual(Object actual, Object expected, String message, List<String> failures) {
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(message + ": expected <" + expected + ">, got <" + actual + ">");
        }
    }

    private static void assertField(boolean condition, String message, List<String> failures) {
        if (!condition) {
            failures.add(message);
        }
    }
}
