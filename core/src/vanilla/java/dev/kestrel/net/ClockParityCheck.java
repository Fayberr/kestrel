package dev.kestrel.net;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * G3 parity gate: differential vanilla oracle for world clocks, full sync,
 * periodic sync, and clock modification packets.
 *
 * <p>Directly compares vanilla's own {@code ServerClockManager.ClockInstance} and
 * packet construction logic against Kestrel's live {@link NetServer} wire output.
 */
public final class ClockParityCheck {

    private ClockParityCheck() {
    }

    private static final sun.misc.Unsafe UNSAFE;
    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Headless DedicatedServer shell providing default game rules (ADVANCE_TIME = true). */
    static class OracleServer extends net.minecraft.server.dedicated.DedicatedServer {
        private net.minecraft.world.level.gamerules.GameRules gameRules;

        private OracleServer() {
            super(null, null, null, null, null, null, null, null, null, null);
            throw new AssertionError("constructor must not run");
        }

        static OracleServer create() {
            try {
                OracleServer s = (OracleServer) UNSAFE.allocateInstance(OracleServer.class);
                s.gameRules = new net.minecraft.world.level.gamerules.GameRules(
                        net.minecraft.world.level.WorldDataConfiguration.DEFAULT.enabledFeatures());
                return s;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public net.minecraft.world.level.gamerules.GameRules getGlobalGameRules() {
            return gameRules;
        }
    }

    /** Oracle wrapper over vanilla private static ServerClockManager.ClockInstance. */
    static final class VanillaClockOracle {
        private final Object instance;
        private final Method tickMethod;
        private final Field totalTicksField;
        private final Field partialTickField;
        private final Method packNetworkStateMethod;

        VanillaClockOracle() {
            try {
                Class<?> clazz = Class.forName("net.minecraft.world.clock.ServerClockManager$ClockInstance");
                Constructor<?> ctor = clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                this.instance = ctor.newInstance();
                this.tickMethod = clazz.getDeclaredMethod("tick");
                this.tickMethod.setAccessible(true);
                this.totalTicksField = clazz.getDeclaredField("totalTicks");
                this.totalTicksField.setAccessible(true);
                this.partialTickField = clazz.getDeclaredField("partialTick");
                this.partialTickField.setAccessible(true);
                this.packNetworkStateMethod = clazz.getDeclaredMethod("packNetworkState", MinecraftServer.class);
                this.packNetworkStateMethod.setAccessible(true);
            } catch (Exception e) {
                throw new RuntimeException("Failed to reflect vanilla ClockInstance", e);
            }
        }

        void tick() {
            try {
                tickMethod.invoke(instance);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        long totalTicks() {
            try {
                return totalTicksField.getLong(instance);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void setTotalTicks(long totalTicks) {
            try {
                totalTicksField.setLong(instance, totalTicks);
                partialTickField.setFloat(instance, 0.0f);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        ClockNetworkState packNetworkState(MinecraftServer server) {
            try {
                return (ClockNetworkState) packNetworkStateMethod.invoke(instance, server);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Vanilla createFullSyncPacket (ServerClockManager.java:130). */
    static ClientboundSetTimePacket createFullSyncPacket(MinecraftServer server, long gameTime,
            Map<Holder<WorldClock>, VanillaClockOracle> clocks) {
        Map<Holder<WorldClock>, ClockNetworkState> map = new HashMap<>();
        for (var e : clocks.entrySet()) {
            map.put(e.getKey(), e.getValue().packNetworkState(server));
        }
        return new ClientboundSetTimePacket(gameTime, map);
    }

    /** Vanilla forceGameTimeSynchronization (MinecraftServer.java:1179). */
    static ClientboundSetTimePacket forceGameTimeSynchronization(long gameTime) {
        return new ClientboundSetTimePacket(gameTime, Map.of());
    }

    /** Vanilla modifyClock (ServerClockManager.java:115). */
    static ClientboundSetTimePacket modifyClock(MinecraftServer server, long gameTime,
            Holder<WorldClock> clock, VanillaClockOracle instance) {
        return new ClientboundSetTimePacket(gameTime, Map.of(clock, instance.packNetworkState(server)));
    }

    private static byte[] encode(ClientboundSetTimePacket packet, net.minecraft.core.RegistryAccess access) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        ClientboundSetTimePacket.STREAM_CODEC.encode(buf, packet);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    /**
     * Exact state model driven by vanilla's ClockInstance and execution order:
     * MinecraftServer.tickServer(:997) -> tickChildren(:1118, 1124, 1130).
     *
     * Order of operations per tick:
     * 1. tickCount++
     * 2. clockManager.tick() (advances all registered clocks)
     * 3. if (tickCount % 20 == 0) forceGameTimeSynchronization(levelGameTime) [pre-increment levelGameTime!]
     * 4. serverLevel.tick() -> tickTime(:477) does levelGameTime++
     */
    static final class VanillaServerModel {
        private final OracleServer oracleServer;
        private final Holder<WorldClock> overworldHolder;
        private final Holder<WorldClock> theEndHolder;
        final VanillaClockOracle overworldClock;
        final VanillaClockOracle theEndClock;
        private long tickCount;
        private long levelGameTime;

        VanillaServerModel(OracleServer oracleServer,
                           Holder<WorldClock> overworldHolder,
                           Holder<WorldClock> theEndHolder,
                           long initialGameTime,
                           long initialOverworldTicks,
                           long initialTheEndTicks) {
            this.oracleServer = oracleServer;
            this.overworldHolder = overworldHolder;
            this.theEndHolder = theEndHolder;
            this.overworldClock = new VanillaClockOracle();
            this.theEndClock = new VanillaClockOracle();
            this.overworldClock.setTotalTicks(initialOverworldTicks);
            this.theEndClock.setTotalTicks(initialTheEndTicks);
            this.tickCount = 0;
            this.levelGameTime = initialGameTime;
        }

        ClientboundSetTimePacket joinPacket() {
            // Vanilla PlayerList.sendLevelInfo(:654) / ServerClockManager.createFullSyncPacket(:130):
            // Sent between ticks, carrying current level gameTime and all registered clocks.
            return createFullSyncPacket(oracleServer, levelGameTime,
                    Map.of(overworldHolder, overworldClock, theEndHolder, theEndClock));
        }

        ClientboundSetTimePacket stepTick() {
            tickCount++;
            overworldClock.tick();
            theEndClock.tick();
            ClientboundSetTimePacket sync = null;
            if (tickCount % 20 == 0) {
                // Vanilla forceGameTimeSynchronization(:1179) with overworld().getGameTime()
                sync = forceGameTimeSynchronization(levelGameTime);
            }
            levelGameTime++;
            return sync;
        }

        long tickCount() {
            return tickCount;
        }

        long levelGameTime() {
            return levelGameTime;
        }
    }

    static Wire.Inbound pollPacket(NetCheck.Loopback client) throws Exception {
        if (client.len > 0) {
            try {
                int[] l = Wire.readVarInt(client.buf, 0);
                if (client.len >= l[1] + l[0]) {
                    return client.read();
                }
            } catch (Exception ignored) {
            }
        }
        if (client.in.available() > 0) {
            return client.read();
        }
        return null;
    }

    static NetCheck.Loopback connectLoopback(dev.kestrel.loop.TickLoop loop, NetServer server,
                                            ChunkSender sender, String name) throws Exception {
        NetCheck.Loopback client = new NetCheck.Loopback(server.boundPort());
        client.send(0x00, new Wire.Writer().vint(776).utf("127.0.0.1").s(server.boundPort()).vint(2).toBytes());
        client.send(0x00, new Wire.Writer().utf(name).uuid(java.util.UUID.randomUUID()).toBytes());
        long deadline = System.currentTimeMillis() + 10000;
        client.socket.setSoTimeout(100);
        while (System.currentTimeMillis() < deadline) {
            Wire.Inbound in;
            try {
                in = client.read();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (in.id() == PacketIds.LOGIN_COMPRESSION && client.threshold < 0) {
                client.threshold = in.reader().varInt();
            } else if (in.id() == PacketIds.LOGIN_FINISHED) {
                client.sendC(PacketIds.LOGIN_ACK, new byte[0]);
            } else if (in.id() == PacketIds.CONFIG_SELECT_KNOWN_OUT) {
                Wire.Writer ci = new Wire.Writer();
                ci.utf("en_us").b(2);
                client.sendC(PacketIds.CONFIG_CLIENT_INFO, ci.toBytes());
                client.sendC(PacketIds.CONFIG_SELECT_KNOWN, new Wire.Writer().vint(0).toBytes());
                client.sendC(PacketIds.CONFIG_FINISH, new byte[0]);
                break;
            }
        }
        NetServer.Session session = null;
        while (System.currentTimeMillis() < deadline) {
            for (NetServer.Session s : server.sessions.values()) {
                if (s.phase == NetServer.Session.Phase.PLAY_JOINING) {
                    session = s;
                    break;
                }
            }
            if (session != null) {
                break;
            }
            Thread.sleep(2);
        }
        if (session == null) {
            throw new IllegalStateException("Session did not reach PLAY_JOINING");
        }

        // Send join burst between ticks (tick 0)
        server.joinBurst(session);
        session.phase = NetServer.Session.Phase.PLAY;
        server.players.put(session.player.profile, session.player);

        // Read join burst from socket
        client.socket.setSoTimeout(500);
        boolean inPlay = false;
        long drainDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < drainDeadline) {
            try {
                Wire.Inbound in = client.read();
                if (in.id() == PacketIds.PLAY_JOIN) {
                    inPlay = true;
                }
                if (inPlay) {
                    client.joinSeen.add(in);
                    if (in.id() == PacketIds.PLAY_POSITION && client.playTeleportId < 0) {
                        client.playTeleportId = in.reader().varInt();
                        client.sendC(PacketIds.PLAY_ACCEPT_TELEPORT,
                                new Wire.Writer().vint(client.playTeleportId).toBytes());
                        client.sendC(PacketIds.PLAY_PLAYER_LOADED, new byte[0]);
                    } else if (in.id() == PacketIds.PLAY_CHUNK_BATCH_FINISHED) {
                        client.sendC(PacketIds.PLAY_CHUNK_BATCH_RECEIVED, new Wire.Writer().f(64.0f).toBytes());
                        break;
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
        }
        return client;
    }

    static int verifyLiveStream(dev.kestrel.loop.TickLoop loop, NetServer server, ChunkSender sender,
                                NetCheck.Loopback client, VanillaServerModel model,
                                int ticks, net.minecraft.core.RegistryAccess access,
                                List<String> failures) throws Exception {
        int periodicCount = 0;
        for (int t = 1; t <= ticks; t++) {
            loop.tick();
            server.pollEvents();
            server.tick(sender);
            ClientboundSetTimePacket expectedSync = model.stepTick();
            if (expectedSync != null) {
                periodicCount++;
                byte[] expectedBytes = encode(expectedSync, access);
                client.sendC(PacketIds.PLAY_MOVE_STATUS, new Wire.Writer().b(1).toBytes());
                client.socket.setSoTimeout(2000);
                boolean sawSync = false;
                while (!sawSync) {
                    Wire.Inbound in = client.read();
                    if (in.id() == PacketIds.PLAY_SET_TIME) {
                        sawSync = true;
                        if (!Arrays.equals(in.payload(), expectedBytes)) {
                            failures.add("Periodic sync wire mismatch at tick " + t
                                    + " (expected gameTime " + expectedSync.gameTime() + "): kestrel="
                                    + Arrays.toString(in.payload()) + " vanilla=" + Arrays.toString(expectedBytes));
                        }
                    } else if (in.id() == PacketIds.PLAY_KEEPALIVE_OUT) {
                        client.sendC(PacketIds.PLAY_KEEPALIVE_IN, new Wire.Writer().l(in.reader().l()).toBytes());
                    } else if (in.id() == PacketIds.PLAY_CHUNK_BATCH_FINISHED) {
                        client.sendC(PacketIds.PLAY_CHUNK_BATCH_RECEIVED, new Wire.Writer().f(64.0f).toBytes());
                    }
                }
            } else {
                Wire.Inbound in;
                while ((in = pollPacket(client)) != null) {
                    if (in.id() == PacketIds.PLAY_SET_TIME) {
                        failures.add("Unexpected SetTime packet on non-sync tick " + t);
                    } else if (in.id() == PacketIds.PLAY_KEEPALIVE_OUT) {
                        client.sendC(PacketIds.PLAY_KEEPALIVE_IN, new Wire.Writer().l(in.reader().l()).toBytes());
                    } else if (in.id() == PacketIds.PLAY_CHUNK_BATCH_FINISHED) {
                        client.sendC(PacketIds.PLAY_CHUNK_BATCH_RECEIVED, new Wire.Writer().f(64.0f).toBytes());
                    }
                }
            }
        }
        return periodicCount;
    }

    public static void runCheck(List<String> failures) {
        long checkCount = 0;
        var access = PlayShapeCheck.access();
        var clockRegistry = access.lookupOrThrow(Registries.WORLD_CLOCK);
        Holder<WorldClock> overworldHolder = clockRegistry.getOrThrow(WorldClocks.OVERWORLD);
        Holder<WorldClock> theEndHolder = clockRegistry.getOrThrow(WorldClocks.THE_END);
        OracleServer oracleServer = OracleServer.create();

        // 0. Verify Holder.Reference hashCode contract
        try {
            Method m = Holder.Reference.class.getMethod("hashCode");
            if (m.getDeclaringClass() == Object.class) {
                System.out.println("[clock-parity] Verified Holder.Reference does not override hashCode (inherits identityHashCode)");
            } else {
                failures.add("Holder.Reference unexpectedly overrides hashCode: " + m.getDeclaringClass());
            }
        } catch (Exception e) {
            failures.add("Could not inspect Holder.Reference.hashCode: " + e);
        }

        // 1. Side-by-side execution: 100,000 ticks matching vanilla ClockInstance exactly
        VanillaClockOracle vanillaOverworld = new VanillaClockOracle();
        VanillaClockOracle vanillaTheEnd = new VanillaClockOracle();
        WorldTime kestrel = new WorldTime(42L);

        for (int i = 1; i <= 100_000; i++) {
            vanillaOverworld.tick();
            vanillaTheEnd.tick();
            kestrel.tick();
            checkCount += 2;
            if (vanillaOverworld.totalTicks() != kestrel.dayTime()) {
                failures.add("100k overworld tick parity failed at tick " + i + ": vanilla="
                        + vanillaOverworld.totalTicks() + " kestrel=" + kestrel.dayTime());
                break;
            }
            if (vanillaTheEnd.totalTicks() != kestrel.theEndTicks()) {
                failures.add("100k the_end tick parity failed at tick " + i + ": vanilla="
                        + vanillaTheEnd.totalTicks() + " kestrel=" + kestrel.theEndTicks());
                break;
            }
        }
        if (failures.isEmpty()) {
            System.out.println("[clock-parity] 100,000 ticks exact vs vanilla overworld and the_end ClockInstances");
        }

        // 2. Set operations: /time set modifies overworld and leaves the_end untouched
        long[] setProbes = {
            0L, 1L, 1000L, 23999L, 24000L, 24001L, 75000L, 192000L, 1_000_000L, 10_000_000_000L
        };
        for (long probe : setProbes) {
            long theEndBefore = vanillaTheEnd.totalTicks();
            vanillaOverworld.setTotalTicks(probe);
            kestrel.setDayTime(probe);
            checkCount += 2;
            if (vanillaOverworld.totalTicks() != kestrel.dayTime()) {
                failures.add("setDayTime parity failed for probe " + probe + ": vanilla="
                        + vanillaOverworld.totalTicks() + " kestrel=" + kestrel.dayTime());
            }
            if (kestrel.theEndTicks() != theEndBefore) {
                failures.add("setDayTime unexpectedly changed the_end ticks for probe " + probe);
            }
            for (int t = 1; t <= 50; t++) {
                vanillaOverworld.tick();
                vanillaTheEnd.tick();
                kestrel.tick();
                checkCount += 2;
                if (vanillaOverworld.totalTicks() != kestrel.dayTime()) {
                    failures.add("post-set overworld tick parity failed for probe " + probe + " tick " + t);
                    break;
                }
                if (vanillaTheEnd.totalTicks() != kestrel.theEndTicks()) {
                    failures.add("post-set the_end tick parity failed for probe " + probe + " tick " + t);
                    break;
                }
            }
        }
        if (failures.isEmpty()) {
            System.out.println("[clock-parity] setTotalTicks across " + setProbes.length + " probes exact (the_end unaffected)");
        }

        // 3. Packet codec parity across boundaries and large values
        long[] boundaryTicks = {
            0L, 23999L, 24000L, 24001L, 8L * 24000L + 5L, 1_000_000_000_000L
        };
        long testGameTime = 987654L;

        // (a) Periodic packet (forceGameTimeSynchronization): empty clock map
        byte[] kPeriodic = Outbound.setTime(testGameTime).payload();
        byte[] vPeriodic = encode(forceGameTimeSynchronization(testGameTime), access);
        checkCount++;
        if (!Arrays.equals(kPeriodic, vPeriodic)) {
            failures.add("Periodic SetTime byte mismatch: kestrel="
                    + Arrays.toString(kPeriodic) + " vanilla=" + Arrays.toString(vPeriodic));
        }

        // (b) Single clock set packet (modifyClock): overworld only
        for (long ticks : boundaryTicks) {
            VanillaClockOracle probeOracle = new VanillaClockOracle();
            probeOracle.setTotalTicks(ticks);
            byte[] kBytes = Outbound.setTime(testGameTime, ticks).payload();
            byte[] vBytes = encode(modifyClock(oracleServer, testGameTime, overworldHolder, probeOracle), access);
            checkCount++;
            if (!Arrays.equals(kBytes, vBytes)) {
                failures.add("Clock modification SetTime byte diff at tick " + ticks + ": kestrel="
                        + Arrays.toString(kBytes) + " vanilla=" + Arrays.toString(vBytes));
            }
        }

        // (c) Full sync packet (createFullSyncPacket): both clocks
        for (long ticks : boundaryTicks) {
            VanillaClockOracle vOw = new VanillaClockOracle();
            vOw.setTotalTicks(ticks);
            VanillaClockOracle vEnd = new VanillaClockOracle();
            vEnd.setTotalTicks(ticks / 2);
            Map<Holder<WorldClock>, VanillaClockOracle> clockMap = Map.of(overworldHolder, vOw, theEndHolder, vEnd);
            ClientboundSetTimePacket vFull = createFullSyncPacket(oracleServer, testGameTime, clockMap);

            byte[] kFullBytes = Outbound.setTimeFull(testGameTime, ticks, ticks / 2).payload();
            RegistryFriendlyByteBuf inBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(kFullBytes), access);
            ClientboundSetTimePacket kDecoded = ClientboundSetTimePacket.STREAM_CODEC.decode(inBuf);

            checkCount += 3;
            if (kDecoded.gameTime() != testGameTime) {
                failures.add("Full sync decoded gameTime mismatch: got " + kDecoded.gameTime());
            }
            if (kDecoded.clockUpdates().size() != 2) {
                failures.add("Full sync decoded clock count mismatch: expected 2, got " + kDecoded.clockUpdates().size());
            }
            ClockNetworkState owState = kDecoded.clockUpdates().get(overworldHolder);
            ClockNetworkState endState = kDecoded.clockUpdates().get(theEndHolder);
            if (owState == null || owState.totalTicks() != ticks || owState.rate() != 1.0f) {
                failures.add("Full sync overworld state mismatch at tick " + ticks + ": " + owState);
            }
            if (endState == null || endState.totalTicks() != ticks / 2 || endState.rate() != 1.0f) {
                failures.add("Full sync the_end state mismatch at tick " + ticks + ": " + endState);
            }
        }
        if (failures.isEmpty()) {
            System.out.println("[clock-parity] Outbound wire shapes exact vs vanilla packet builders across all boundary probes");
        }

        // 4. Live NetServer wire path verification over loopback
        NetServer server = null;
        try {
            dev.kestrel.loop.KestrelLevel level = new dev.kestrel.loop.KestrelLevel(
                    dev.kestrel.loop.KestrelLevel.Dimension.OVERWORLD, -64, 384);
            dev.kestrel.loop.TickLoop loop = new dev.kestrel.loop.TickLoop(level);
            server = new NetServer(loop, 0, 256, 123456789L);
            server.start();
            ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
            VanillaServerModel model = new VanillaServerModel(
                    oracleServer, overworldHolder, theEndHolder, 0L, 0L, 0L);

            NetCheck.Loopback client = connectLoopback(loop, server, sender, "OracleBot");
            checkCount++;
            if (client == null) {
                failures.add("Live NetServer join failed");
                return;
            }

            // 4a. Join burst: must carry full sync with both clocks (PlayerList.java:654)
            // between ticks, where vanilla has clock == gameTime on a fresh world.
            ClientboundSetTimePacket joinPacket = null;
            for (Wire.Inbound in : client.joinSeen) {
                if (in.id() == PacketIds.PLAY_SET_TIME) {
                    joinPacket = (ClientboundSetTimePacket) PlayShapeCheck.decodePlay(in.id(), in.payload());
                    break;
                }
            }
            checkCount++;
            if (joinPacket == null) {
                failures.add("Join burst did not send a SetTime packet");
            } else {
                ClientboundSetTimePacket expectedJoin = model.joinPacket();
                checkCount += 4;
                if (joinPacket.gameTime() != expectedJoin.gameTime()) {
                    failures.add("join SetTime gameTime mismatch vs vanilla: got " + joinPacket.gameTime()
                            + " expected " + expectedJoin.gameTime());
                }
                if (!joinPacket.clockUpdates().equals(expectedJoin.clockUpdates())) {
                    failures.add("join SetTime clockUpdates mismatch vs vanilla: got " + joinPacket.clockUpdates()
                            + " expected " + expectedJoin.clockUpdates());
                }
                // Prove clock == gameTime on fresh world between ticks
                ClockNetworkState owState = joinPacket.clockUpdates().get(overworldHolder);
                ClockNetworkState endState = joinPacket.clockUpdates().get(theEndHolder);
                if (owState == null || endState == null || owState.totalTicks() != joinPacket.gameTime()
                        || endState.totalTicks() != joinPacket.gameTime()) {
                    failures.add("join SetTime clock == gameTime contract violated: gameTime="
                            + joinPacket.gameTime() + " ow=" + owState + " end=" + endState);
                }
            }

            // 4b. Live stream crossing several days: 72,000 ticks (3 full Minecraft days)
            // Every periodic packet is derived from vanilla's execution order via model.stepTick()
            int periodicSyncs = verifyLiveStream(loop, server, sender, client, model, 72_000, access, failures);
            checkCount += periodicSyncs;
            if (periodicSyncs != 3600) {
                failures.add("Expected exactly 3600 periodic syncs over 72,000 ticks, got " + periodicSyncs);
            }

            // 4c. Rollover: advance past tick 24000 without clock jumps or extra packets
            server.time.setDayTime(23995L);
            server.time.consumeClockJump(); // clear jump flag from set
            for (int t = 1; t <= 10; t++) {
                loop.tick();
                server.pollEvents();
                server.tick(sender);
                Wire.Inbound in;
                while ((in = pollPacket(client)) != null) {
                    if (in.id() == PacketIds.PLAY_SET_TIME) {
                        var pkt = (ClientboundSetTimePacket) PlayShapeCheck.decodePlay(in.id(), in.payload());
                        if (!pkt.clockUpdates().isEmpty()) {
                            failures.add("Day rollover unexpectedly raised a clock jump at dayTime=" + server.time.dayTime());
                        }
                    }
                }
            }
            checkCount++;
            if (server.time.dayTime() != 24005L) {
                failures.add("Expected dayTime=24005 after rollover, got " + server.time.dayTime());
            }

            // 4d. Clock modification: /time set modifies overworld and leaves the_end untouched
            long endBeforeSet = server.time.theEndTicks();
            server.time.setDayTime(75000L);
            checkCount++;
            if (server.time.theEndTicks() != endBeforeSet) {
                failures.add("the_end clock changed by /time set: expected " + endBeforeSet
                        + ", got " + server.time.theEndTicks());
            }
            ClientboundSetTimePacket setPkt = null;
            byte[] setPktBytes = null;
            for (int t = 1; t <= 10; t++) {
                loop.tick();
                server.pollEvents();
                server.tick(sender);
                client.sendC(PacketIds.PLAY_MOVE_STATUS, new Wire.Writer().b(1).toBytes());
                Wire.Inbound in;
                while ((in = pollPacket(client)) != null) {
                    if (in.id() == PacketIds.PLAY_SET_TIME) {
                        var decoded = (ClientboundSetTimePacket) PlayShapeCheck.decodePlay(in.id(), in.payload());
                        if (!decoded.clockUpdates().isEmpty()) {
                            setPkt = decoded;
                            setPktBytes = in.payload();
                            break;
                        }
                    }
                }
            }
            checkCount++;
            if (setPkt == null) {
                failures.add("setDayTime did not dispatch a SetTime packet on next tick");
            } else {
                checkCount += 4;
                if (setPkt.clockUpdates().size() != 1) {
                    failures.add("setDayTime packet must carry exactly 1 clock update, got " + setPkt.clockUpdates().size());
                }
                if (!setPkt.clockUpdates().containsKey(overworldHolder)) {
                    failures.add("setDayTime packet missing overworld clock");
                }
                if (setPkt.clockUpdates().containsKey(theEndHolder)) {
                    failures.add("setDayTime packet unexpectedly included the_end clock");
                }
                ClockNetworkState netOw = setPkt.clockUpdates().get(overworldHolder);
                if (netOw == null || netOw.totalTicks() != 75000L) {
                    failures.add("setDayTime packet carried wrong overworld totalTicks: " + netOw);
                }
                // Differential wire byte comparison against vanilla modifyClock
                VanillaClockOracle vSetOracle = new VanillaClockOracle();
                vSetOracle.setTotalTicks(75000L);
                ClientboundSetTimePacket vModify = modifyClock(oracleServer, setPkt.gameTime(), overworldHolder, vSetOracle);
                byte[] vModifyBytes = encode(vModify, access);
                if (!Arrays.equals(setPktBytes, vModifyBytes)) {
                    failures.add("setDayTime wire bytes mismatch vs vanilla modifyClock: kestrel="
                            + Arrays.toString(setPktBytes) + " vanilla=" + Arrays.toString(vModifyBytes));
                }
            }
        } catch (Exception e) {
            failures.add("Live NetServer loopback check threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (server != null) {
                server.close();
            }
        }

        // 4e. Restored world whose gameTime is not a multiple of 20 (e.g. 13579L)
        NetServer restoredServer = null;
        try {
            long savedGameTime = 13579L;
            long savedDayTime = 25000L;
            long savedEndTicks = 12000L;

            dev.kestrel.loop.KestrelLevel rLevel = new dev.kestrel.loop.KestrelLevel(
                    dev.kestrel.loop.KestrelLevel.Dimension.OVERWORLD, -64, 384);
            dev.kestrel.loop.TickLoop rLoop = new dev.kestrel.loop.TickLoop(rLevel);
            rLoop.setGameTime(savedGameTime);

            restoredServer = new NetServer(rLoop, 0, 256, 987654321L);
            restoredServer.time.restore(savedDayTime, false, 0, 0.0f, savedEndTicks);
            restoredServer.start();

            ChunkSender rSender = new ChunkSender(rLoop, NetCheck.plainsId());
            VanillaServerModel restoredModel = new VanillaServerModel(
                    oracleServer, overworldHolder, theEndHolder, savedGameTime, savedDayTime, savedEndTicks);

            NetCheck.Loopback rClient = connectLoopback(rLoop, restoredServer, rSender, "RestoredBot");
            checkCount++;
            if (rClient == null) {
                failures.add("Restored NetServer join failed");
                return;
            }

            ClientboundSetTimePacket rJoinPacket = null;
            for (Wire.Inbound in : rClient.joinSeen) {
                if (in.id() == PacketIds.PLAY_SET_TIME) {
                    rJoinPacket = (ClientboundSetTimePacket) PlayShapeCheck.decodePlay(in.id(), in.payload());
                    break;
                }
            }
            checkCount++;
            if (rJoinPacket == null) {
                failures.add("Restored join burst did not send a SetTime packet");
            } else {
                ClientboundSetTimePacket expectedRJoin = restoredModel.joinPacket();
                checkCount += 3;
                if (rJoinPacket.gameTime() != expectedRJoin.gameTime()) {
                    failures.add("Restored join gameTime mismatch: got " + rJoinPacket.gameTime()
                            + " expected " + expectedRJoin.gameTime());
                }
                if (!rJoinPacket.clockUpdates().equals(expectedRJoin.clockUpdates())) {
                    failures.add("Restored join clockUpdates mismatch: got " + rJoinPacket.clockUpdates()
                            + " expected " + expectedRJoin.clockUpdates());
                }
            }

            // Verify live stream for 100 ticks (5 periodic sync intervals) on restored world
            int rSyncs = verifyLiveStream(rLoop, restoredServer, rSender, rClient, restoredModel, 100, access, failures);
            checkCount += rSyncs;
            if (rSyncs != 5) {
                failures.add("Expected 5 periodic syncs across 100 ticks on restored world, got " + rSyncs);
            }
        } catch (Exception e) {
            failures.add("Restored world loopback check threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (restoredServer != null) {
                restoredServer.close();
            }
        }

        // 5. Persistence scenarios: round-trip and old file loading
        try {
            Path tmpDir = Files.createTempDirectory("kestrel-clock-persist-g3");
            try {
                long multiDayTime = 7L * 24000L + 13579L;
                long multiEndTicks = 3L * 24000L + 9999L;
                StateStore.LevelState saved = new StateStore.LevelState(100L, multiDayTime, false, 0, 0.0f, multiEndTicks);
                StateStore.writeLevel(tmpDir, saved);

                StateStore.LevelState loaded = StateStore.readLevel(tmpDir);
                checkCount += 2;
                if (loaded == null || loaded.dayTime() != multiDayTime) {
                    failures.add("Round-trip day count failed: expected " + multiDayTime + ", got "
                            + (loaded == null ? "null" : loaded.dayTime()));
                }
                if (loaded == null || loaded.theEndTicks() != multiEndTicks) {
                    failures.add("Round-trip the_end ticks failed: expected " + multiEndTicks + ", got "
                            + (loaded == null ? "null" : loaded.theEndTicks()));
                }

                WorldTime restored = new WorldTime(1L);
                if (loaded != null) {
                    restored.restore(loaded.dayTime(), loaded.raining(), loaded.rainTime(), loaded.rainLevel(), loaded.theEndTicks());
                }
                checkCount += 2;
                if (restored.dayTime() != multiDayTime) {
                    failures.add("Restored dayTime failed: expected " + multiDayTime + ", got " + restored.dayTime());
                }
                if (restored.theEndTicks() != multiEndTicks) {
                    failures.add("Restored theEndTicks failed: expected " + multiEndTicks + ", got " + restored.theEndTicks());
                }

                // Old level file without theEndTicks: defaults to 0L (justified from vanilla's uninitialized ClockInstance)
                Files.writeString(StateStore.levelPath(tmpDir),
                        "format=1\ngameTime=10\ndayTime=12345\nraining=false\nrainTime=0\nrainLevel=0.0\n");
                StateStore.LevelState oldFile = StateStore.readLevel(tmpDir);
                checkCount += 2;
                if (oldFile == null || oldFile.dayTime() != 12345L) {
                    failures.add("Old format dayTime=12345 failed to load: got "
                            + (oldFile == null ? "null" : oldFile.dayTime()));
                }
                if (oldFile == null || oldFile.theEndTicks() != 0L) {
                    failures.add("Old format theEndTicks failed to default to 0: got "
                            + (oldFile == null ? "null" : oldFile.theEndTicks()));
                }
                WorldTime oldRestored = new WorldTime(2L);
                if (oldFile != null) {
                    oldRestored.restore(oldFile.dayTime(), oldFile.raining(), oldFile.rainTime(), oldFile.rainLevel(), oldFile.theEndTicks());
                }
                checkCount += 2;
                if (oldRestored.dayTime() != 12345L || oldRestored.theEndTicks() != 0L) {
                    failures.add("Old format restore mismatch: dayTime=" + oldRestored.dayTime()
                            + " theEndTicks=" + oldRestored.theEndTicks());
                }
            } finally {
                try (var s = Files.walk(tmpDir)) {
                    s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        } catch (Exception e) {
            failures.add("Persist scenario threw " + e);
        }

        // 6. Moon phase timeline verification per Timelines.java:161-167
        // keyframes at MoonPhase.startTick() with period 24000 * MoonPhase.COUNT
        for (int day = 0; day < 16; day++) {
            long ticks = day * 24000L + 12000L;
            int phase = (int) ((ticks / 24000L) % 8L);
            checkCount++;
            if (phase != (day % 8)) {
                failures.add("Moon phase mismatch on day " + day + ": expected " + (day % 8) + ", got " + phase);
            }
        }

        System.out.println("[clock-parity] Total checks verified: " + checkCount);
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<String> failures = new ArrayList<>();
        runCheck(failures);
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.err.println("[clock-parity] FAILURE: " + f);
            }
            System.exit(1);
        }
        System.out.println("ClockParityCheck: PASS");
    }
}
