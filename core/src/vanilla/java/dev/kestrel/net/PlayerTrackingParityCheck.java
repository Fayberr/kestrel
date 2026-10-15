package dev.kestrel.net;

import com.mojang.authlib.GameProfile;
import dev.kestrel.entity.KestrelPlayerEntity;
import dev.kestrel.entity.TrackingObserver;
import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * G15 slice 1 gate: other players' bodies, against vanilla 26.2.
 *
 * <p>Kestrel's side is the live path, unchanged: a real {@link NetServer}
 * (not listening on any fixed port: an ephemeral loopback port), its
 * {@link TickLoop} over a small built world and a {@link ChunkSender}, with
 * loopback clients that log in, configure, join and play over real sockets.
 * Each gate tick runs {@link KestrelServer#runIteration} exactly as the live
 * server does, then reads every client's socket up to an
 * in-band tick marker, so what each player received is known tick by tick.
 *
 * <p>The vanilla side is {@link PlayerTrackingOracle}: real
 * {@code ServerPlayer}s with real {@code ServerGamePacketListenerImpl}s,
 * vanilla's {@code doTick}, {@code ServerEntity} and
 * {@code ChunkMap.TrackedEntity}. Every packet a client sends is encoded with
 * vanilla's serverbound codec; Kestrel reads the bytes off its socket, the
 * oracle decodes the same bytes with the same codec and runs its handler, in
 * the same order. The oracle follows vanilla's phase split: the tracker in
 * the {@code chunkSource} slot and each player's {@code ServerPlayer.tick}
 * run in the level phase, then queued packets are handled and each
 * connection ticks in the connection phase. Joins follow that phase.
 *
 * <p>Compared, per observing player and per tick, raw and in order: every
 * clientbound packet of the entity and tab list families (the ids are read
 * off vanilla's clientbound table, see {@link #COMPARED}), bytes included,
 * bundles split into delimiters as on the wire; the only normalisation is
 * the order of attribute snapshots inside an update-attributes packet, which
 * vanilla draws from an identity hash set. Also compared every tick: each
 * body's state against its vanilla player (position, rotations, head, motion,
 * ground, pose, shared flags) and every seen-by set, both directions.
 *
 * <p>Fixed scripts, fixed seed. Exit 0 on PASS, 1 otherwise.
 */
public final class PlayerTrackingParityCheck {
    private PlayerTrackingParityCheck() {
    }

    private static final List<String> failures = new ArrayList<>();
    private static int checks;
    private static int scenarioCount;
    private static RegistryAccess access;
    private static ProtocolInfo<ServerGamePacketListener> serverbound;
    /** In-band end-of-tick marker; outside the play table, never sent by the live server. */
    private static final int MARKER = 0x7F7F;
    private static final boolean VERBOSE = Boolean.getBoolean("kestrel.g15.verbose");

    /** The clientbound families compared, by vanilla type; their ids are read off vanilla's table. */
    private static final List<PacketType<?>> COMPARED_TYPES = List.of(
            GamePacketTypes.CLIENTBOUND_BUNDLE_DELIMITER, GamePacketTypes.CLIENTBOUND_ADD_ENTITY,
            GamePacketTypes.CLIENTBOUND_ANIMATE, GamePacketTypes.CLIENTBOUND_DAMAGE_EVENT,
            GamePacketTypes.CLIENTBOUND_ENTITY_EVENT, GamePacketTypes.CLIENTBOUND_ENTITY_POSITION_SYNC,
            GamePacketTypes.CLIENTBOUND_HURT_ANIMATION, GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS,
            GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS_ROT, GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_ROT,
            GamePacketTypes.CLIENTBOUND_PLAYER_INFO_REMOVE, GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE,
            GamePacketTypes.CLIENTBOUND_REMOVE_ENTITIES, GamePacketTypes.CLIENTBOUND_ROTATE_HEAD,
            GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA, GamePacketTypes.CLIENTBOUND_SET_ENTITY_LINK,
            GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION, GamePacketTypes.CLIENTBOUND_SET_EQUIPMENT,
            GamePacketTypes.CLIENTBOUND_SET_PASSENGERS, GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY,
            GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES, GamePacketTypes.CLIENTBOUND_UPDATE_MOB_EFFECT,
            GamePacketTypes.CLIENTBOUND_REMOVE_MOB_EFFECT, GamePacketTypes.CLIENTBOUND_TAKE_ITEM_ENTITY,
            GamePacketTypes.CLIENTBOUND_SET_HEALTH);
    private static final Set<Integer> COMPARED = new HashSet<>();
    private static final Map<Integer, String> NAMES = new HashMap<>();

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        prepare(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        checkPacketIds();
        String only = args.length > 0 ? String.join(" ", args) : System.getProperty("kestrel.g15.scenario");
        for (Scenario s : scenarios()) {
            if (only != null && !s.name().equals(only)) {
                continue;
            }
            scenarioCount++;
            int before = failures.size();
            try (Harness h = new Harness(s.name(), s.world())) {
                s.script().run(h);
                h.finish();
            } catch (Throwable t) {
                failures.add(s.name() + ": threw " + t);
                t.printStackTrace(System.out);
            }
            System.out.println("  scenario " + s.name() + ": "
                    + (failures.size() == before ? "ok" : (failures.size() - before) + " failure(s)"));
        }

        if (!failures.isEmpty()) {
            System.out.println("PlayerTrackingParityCheck: FAILED with " + failures.size() + " failure(s)");
            for (int i = 0; i < Math.min(failures.size(), 40); i++) {
                System.out.println("  - " + failures.get(i));
            }
            System.exit(1);
        }
        System.out.println("PlayerTrackingParityCheck: PASSED (" + checks + " checks, " + scenarioCount + " scenarios)");
        System.exit(0);
    }

    static void prepare(RegistryAccess registryAccess) {
        for (net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) {
            item.builtInRegistryHolder().bindComponents(
                    net.minecraft.core.component.DataComponents.COMMON_ITEM_COMPONENTS);
        }
        access = registryAccess;
        serverbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access), () -> false);
        COMPARED.clear();
        NAMES.clear();
        for (PacketType<?> type : COMPARED_TYPES) {
            COMPARED.add(clientboundId(type));
            NAMES.put(clientboundId(type), type.id().getPath());
        }
    }

    /** Compare these clientbound families as well, from now on (after {@link #prepare}). */
    static void compareAlso(PacketType<?>... types) {
        for (PacketType<?> type : types) {
            COMPARED.add(clientboundId(type));
            NAMES.put(clientboundId(type), type.id().getPath());
        }
    }

    static int checkCount() {
        return checks;
    }

    static List<String> failureList() {
        return failures;
    }

    static void expect(boolean ok, String message) {
        checks++;
        if (!ok) {
            failures.add(message);
        }
    }

    // =====================================================================
    // Packet ids
    // =====================================================================

    private static void checkPacketIds() {
        expect(PacketIds.PLAY_ANIMATE == clientboundId(GamePacketTypes.CLIENTBOUND_ANIMATE), "id animate");
        expect(PacketIds.PLAY_PLAYER_INFO_REMOVE == clientboundId(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_REMOVE),
                "id player_info_remove");
        expect(PacketIds.PLAY_PLAYER_INFO == clientboundId(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE),
                "id player_info_update");
        expect(PacketIds.PLAY_PLAYER_INPUT == serverboundId(GamePacketTypes.SERVERBOUND_PLAYER_INPUT), "id player_input");
        expect(PacketIds.PLAY_PLAYER_COMMAND == serverboundId(GamePacketTypes.SERVERBOUND_PLAYER_COMMAND),
                "id player_command");
        expect(PacketIds.PLAY_SWING == serverboundId(GamePacketTypes.SERVERBOUND_SWING), "id swing");
        expect(PacketIds.PLAY_CLIENT_INFORMATION
                == serverboundId(net.minecraft.network.protocol.common.CommonPacketTypes.SERVERBOUND_CLIENT_INFORMATION),
                "id client_information");
        expect(PacketIds.PLAY_PLAYER_LOADED == serverboundId(GamePacketTypes.SERVERBOUND_PLAYER_LOADED), "id player_loaded");
        expect(PacketIds.PLAY_MOVE_POS == serverboundId(GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS), "id move_pos");
        expect(PacketIds.PLAY_MOVE_POSROT == serverboundId(GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS_ROT), "id move_pos_rot");
        expect(PacketIds.PLAY_MOVE_ROT == serverboundId(GamePacketTypes.SERVERBOUND_MOVE_PLAYER_ROT), "id move_rot");
        expect(PacketIds.PLAY_MOVE_STATUS == serverboundId(GamePacketTypes.SERVERBOUND_MOVE_PLAYER_STATUS_ONLY),
                "id move_status");
    }

    static int clientboundId(PacketType<?> type) {
        int[] found = {-1};
        ((ProtocolInfo.DetailsProvider) GameProtocols.CLIENTBOUND_TEMPLATE).details().listPackets((t, id) -> {
            if (t.equals(type)) {
                found[0] = id;
            }
        });
        return found[0];
    }

    static int serverboundId(PacketType<?> type) {
        int[] found = {-1};
        ((ProtocolInfo.DetailsProvider) GameProtocols.SERVERBOUND_TEMPLATE).details().listPackets((t, id) -> {
            if (t.equals(type)) {
                found[0] = id;
            }
        });
        return found[0];
    }

    /** One serverbound play packet as a client would put it on the wire: id, then payload. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Encoded encode(Packet<?> packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            serverbound.codec().encode(buf, (Packet) packet);
            FriendlyByteBuf fb = new FriendlyByteBuf(buf);
            int id = fb.readVarInt();
            byte[] payload = new byte[fb.readableBytes()];
            fb.readBytes(payload);
            return new Encoded(id, payload);
        } finally {
            buf.release();
        }
    }

    record Encoded(int id, byte[] payload) {
    }

    // =====================================================================
    // Normalisation
    // =====================================================================

    static String fmt(int id, byte[] payload) {
        return id + ":" + HexFormat.of().formatHex(payload);
    }

    static int idOf(String encoded) {
        return Integer.parseInt(encoded.substring(0, encoded.indexOf(':')));
    }

    /** The compared families, attribute snapshots sorted by attribute id (see the class comment). */
    static List<String> compared(List<String> packets) {
        List<String> out = new ArrayList<>();
        for (String p : packets) {
            int id = idOf(p);
            if (!COMPARED.contains(id)) {
                continue;
            }
            if (id == PacketIds.PLAY_UPDATE_ATTRIBUTES) {
                p = sortedAttributes(p);
            }
            out.add(p);
        }
        return out;
    }

    private static String sortedAttributes(String encoded) {
        byte[] payload = HexFormat.of().parseHex(encoded.substring(encoded.indexOf(':') + 1));
        RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), access);
        var pkt = net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket.STREAM_CODEC.decode(in);
        var values = new ArrayList<>(pkt.getValues());
        values.sort(java.util.Comparator.comparingInt(a -> BuiltInRegistries.ATTRIBUTE.getId(a.attribute().value())));
        RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        out.writeVarInt(pkt.getEntityId());
        out.writeVarInt(values.size());
        for (var snapshot : values) {
            net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket.AttributeSnapshot.STREAM_CODEC
                    .encode(out, snapshot);
        }
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return fmt(PacketIds.PLAY_UPDATE_ATTRIBUTES, bytes);
    }

    // =====================================================================
    // The harness
    // =====================================================================

    /**
     * The world a scenario plays in, where players spawn (the block they
     * stand on), and {@code entity-broadcast-range-percentage} (100 runs with
     * no properties file, as the other gates do).
     */
    record World(Consumer<TickLoop> build, BlockPos spawn, int rangePercentage) {
        World(Consumer<TickLoop> build, BlockPos spawn) {
            this(build, spawn, 100);
        }
    }

    record Scenario(String name, World world, Script script) {
    }

    interface Script {
        void run(Harness h) throws Exception;
    }

    /** One inbound event in the order both sides take it. */
    private record Inbound(Client client, Encoded packet, boolean disconnect) {
    }

    /** Both sides of one scenario, stepped a tick at a time. */
    static final class Harness implements AutoCloseable {
        final String name;
        final TickLoop loop;
        final NetServer server;
        final ChunkSender sender;
        final PlayerTrackingOracle oracle;
        final List<Client> clients = new ArrayList<>();
        final List<Inbound> inbound = new ArrayList<>();
        int tick;
        /** Kestrel's tick streams per client name, and vanilla's, for the stream-order checks. */
        final Map<Client, List<String>> kestrelLog = new LinkedHashMap<>();
        final Map<Client, List<String>> vanillaLog = new LinkedHashMap<>();

        Harness(String name, World world) throws Exception {
            this.name = name;
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            loop = new TickLoop(level);
            world.build().accept(loop);
            server = new NetServer(loop, 0, -1, 4242424242L);
            server.setFixedSpawn(world.spawn());
            // Kestrel caps a client's view distance at 16 with no properties file,
            // at view-distance with one; vanilla's ChunkMap clamps to the same.
            int serverViewDistance = 16;
            if (world.rangePercentage() != 100) {
                java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("kestrel-g15-props");
                java.nio.file.Path file = dir.resolve("server.properties");
                java.nio.file.Files.writeString(file,
                        "entity-broadcast-range-percentage=" + world.rangePercentage() + "\n");
                ServerProperties properties = ServerProperties.load(file);
                server.setProperties(properties);
                serverViewDistance = properties.viewDistance();
            }
            server.start();
            sender = new ChunkSender(loop, 1);
            server.setChunkSender(sender);
            oracle = new PlayerTrackingOracle(access, loop.blockGetter(), serverViewDistance, world.rangePercentage());
        }

        /** Log in and configure a client; it joins on the ticks that follow. */
        Client connect(String name, int viewDistance, int modelCustomisation) throws Exception {
            Client c = new Client(this, name, viewDistance, modelCustomisation);
            c.login();
            clients.add(c);
            kestrelLog.put(c, new ArrayList<>());
            vanillaLog.put(c, new ArrayList<>());
            return c;
        }

        /** Tick until the client is placed, has accepted its teleport and said it loaded. */
        void join(Client c) throws Exception {
            for (int i = 0; i < 100 && c.teleportId < 0; i++) {
                tick();
            }
            if (c.teleportId < 0) {
                throw new IllegalStateException(c.name + " never received its join teleport");
            }
            c.send(new ServerboundAcceptTeleportationPacket(c.teleportId));
            c.send(new ServerboundMovePlayerPacket.PosRot(c.x, c.y, c.z, c.yaw, c.pitch, false, false));
            c.onGround = false;
            tick();
            c.send(new ServerboundPlayerLoadedPacket());
            tick();
        }

        void ticks(int n) throws Exception {
            for (int i = 0; i < n; i++) {
                tick();
            }
        }

        /** Apply the same authoritative server inventory mutation on both sides. */
        void setItem(Client client, int slot, ItemStack stack) {
            KestrelPlayerEntity body = server.bodies().body(client.record);
            if (body == null || client.vp == null) throw new IllegalStateException("player not joined");
            body.vanillaPlayer().getInventory().setItem(slot, stack.copy());
            client.vp.player.getInventory().setItem(slot, stack.copy());
        }

        /** One server tick on both sides, then the comparisons. */
        void tick() throws Exception {
            tick++;
            // What Kestrel has sent each profile as of the end of the last tick: the
            // oracle's "not pending" for every decision this tick.
            Map<UUID, Set<Long>> sent = new HashMap<>();
            for (Client c : clients) {
                sent.put(c.profile, c.sentChunks());
            }

            // Kestrel: exactly what KestrelServer runs each tick.
            KestrelServer.runIteration(server, loop, sender);

            // Each client's stream up to the marker, then chunk acks settle.
            Map<Client, List<String>> kestrel = new LinkedHashMap<>();
            for (Client c : List.copyOf(clients)) {
                if (!c.connected) {
                    continue;
                }
                kestrel.put(c, c.readTick(tick));
            }
            for (Client c : List.copyOf(clients)) {
                if (c.connected) {
                    c.settle();
                }
            }

            // Vanilla: the tick, the packets in Kestrel's order, then the joins Kestrel made.
            for (PlayerTrackingOracle.VPlayer vp : oracle.players) {
                Set<Long> s = sent.getOrDefault(vp.player.getUUID(), Set.of());
                vp.chunks.sent = s::contains;
            }
            oracle.level.gameTime = loop.gameTime();
            oracle.tickLevel();
            for (Inbound in : inbound) {
                if (in.disconnect()) {
                    if (in.client().vp != null) {
                        oracle.remove(in.client().vp);
                    }
                } else if (in.client().vp != null) {
                    in.client().vp.receive(in.packet().id(), in.packet().payload());
                }
            }
            inbound.clear();
            oracle.tickConnections();
            for (Client c : clients) {
                KestrelPlayer record = c.record;
                if (c.vp == null && record != null && record.joinHeadSent) {
                    c.vp = oracle.create(new GameProfile(c.profile, c.name), record.entityId, c.info,
                            record.x, record.y, record.z, record.yaw, record.pitch);
                    Set<Long> s = sent.getOrDefault(c.profile, Set.of());
                    c.vp.chunks.sent = s::contains;
                    oracle.placeNewPlayer(c.vp);
                }
            }
            for (PlayerTrackingOracle.VPlayer vp : oracle.players) {
                vp.answerKeepAlives();
            }

            // Compare.
            for (Client c : List.copyOf(clients)) {
                List<String> k = kestrel.containsKey(c) ? compared(kestrel.get(c)) : List.of();
                List<String> v = c.vp != null && c.vpLive() ? compared(c.vp.drain()) : List.of();
                if (c.vp != null && !c.vpLive()) {
                    c.vp.drain();
                }
                kestrelLog.get(c).addAll(k);
                vanillaLog.get(c).addAll(v);
                expect(k.equals(v), name + " tick " + tick + " to " + c.name + ": kestrel=" + k + " vanilla=" + v);
                if (VERBOSE && (!k.isEmpty() || !v.isEmpty())) {
                    System.out.println("    t" + tick + " " + c.name + " k=" + k + (k.equals(v) ? "" : " v=" + v));
                }
            }
            compareState();
        }

        /** Each body against its vanilla player, and every seen-by set in both directions. */
        private void compareState() {
            for (Client c : clients) {
                if (c.vp == null || !c.vpLive() || c.record == null) {
                    continue;
                }
                KestrelPlayerEntity body = server.bodies().body(c.record);
                if (body == null) {
                    expect(false, name + " tick " + tick + " " + c.name + ": no Kestrel body");
                    continue;
                }
                ServerPlayer v = c.vp.player;
                String where = name + " tick " + tick + " " + c.name;
                expect(body.position().equals(v.position()), where + " position " + body.position() + " vs " + v.position());
                expect(body.getYRot() == v.getYRot() && body.getXRot() == v.getXRot(),
                        where + " rotation " + body.getYRot() + "," + body.getXRot() + " vs " + v.getYRot() + "," + v.getXRot());
                expect(body.getYHeadRot() == v.getYHeadRot(), where + " head " + body.getYHeadRot() + " vs " + v.getYHeadRot());
                expect(body.getDeltaMovement().equals(v.getDeltaMovement()),
                        where + " motion " + body.getDeltaMovement() + " vs " + v.getDeltaMovement());
                expect(body.onGround() == v.onGround(), where + " onGround " + body.onGround() + " vs " + v.onGround());
                expect(body.getPose() == v.getPose(), where + " pose " + body.getPose() + " vs " + v.getPose());
                expect(body.isShiftKeyDown() == v.isShiftKeyDown() && body.isSprinting() == v.isSprinting(),
                        where + " shift/sprint " + body.isShiftKeyDown() + "/" + body.isSprinting()
                                + " vs " + v.isShiftKeyDown() + "/" + v.isSprinting());

                // Who sees this body, by name, both sides.
                Set<String> kSeen = new TreeSet<>();
                for (TrackingObserver o : server.entityTracker().seenBy(body.getId())) {
                    kSeen.add(nameOf(o.entityId()));
                }
                Set<String> vSeen = new TreeSet<>();
                for (Object o : oracle.tracker.seenBy(v.getId())) {
                    vSeen.add(((ServerPlayerConnection) o).getPlayer().getGameProfile().name());
                }
                expect(kSeen.equals(vSeen), where + " seen by kestrel=" + kSeen + " vanilla=" + vSeen);
            }
        }

        private String nameOf(int entityId) {
            for (Client c : clients) {
                if (c.record != null && c.record.entityId == entityId && c.vpLive()) {
                    return c.name;
                }
            }
            return "#" + entityId;
        }

        /** Stream-order checks every scenario ends with: the tab list row before the body, both sides. */
        void finish() {
            // What the scenario exercised: compared packets by name, over every observer.
            Map<String, Integer> census = new java.util.TreeMap<>();
            for (List<String> log : kestrelLog.values()) {
                for (String p : log) {
                    census.merge(NAMES.getOrDefault(idOf(p), String.valueOf(idOf(p))), 1, Integer::sum);
                }
            }
            System.out.println("    " + name + " compared packets: " + census);
            for (Client observer : clients) {
                for (Client other : clients) {
                    if (other == observer || other.record == null) {
                        continue;
                    }
                    checkRowBeforeBody(observer, other, kestrelLog.get(observer), "kestrel");
                    checkRowBeforeBody(observer, other, vanillaLog.get(observer), "vanilla");
                }
            }
        }

        private void checkRowBeforeBody(Client observer, Client other, List<String> log, String side) {
            String uuidHex = uuidHex(other.profile);
            String idHex = varIntHex(other.record.entityId);
            int row = -1;
            int body = -1;
            for (int i = 0; i < log.size(); i++) {
                String p = log.get(i);
                int id = idOf(p);
                String hex = p.substring(p.indexOf(':') + 1);
                if (row < 0 && id == PacketIds.PLAY_PLAYER_INFO && hex.contains(uuidHex)) {
                    row = i;
                }
                if (body < 0 && id == PacketIds.PLAY_ADD_ENTITY && hex.startsWith(idHex + uuidHex)) {
                    body = i;
                }
            }
            if (body >= 0) {
                expect(row >= 0 && row < body, name + " " + side + ": " + observer.name + " got " + other.name
                        + "'s body (packet " + body + ") before its tab list row (packet " + row + ")");
            }
        }

        @Override
        public void close() {
            for (Client c : clients) {
                c.closeQuietly();
            }
            server.close();
        }
    }

    static String uuidHex(UUID id) {
        return String.format("%016x%016x", id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    static String varIntHex(int value) {
        StringBuilder sb = new StringBuilder();
        while ((value & ~0x7F) != 0) {
            sb.append(String.format("%02x", (value & 0x7F) | 0x80));
            value >>>= 7;
        }
        sb.append(String.format("%02x", value));
        return sb.toString();
    }

    // =====================================================================
    // A loopback client
    // =====================================================================

    /** A scripted client over a real socket; it also tracks its own position like a client would. */
    static final class Client {
        final Harness h;
        final String name;
        final UUID profile;
        final ClientInformation info;
        Socket socket;
        InputStream in;
        OutputStream out;
        final byte[] buf = new byte[4 << 20];
        int len;
        boolean connected;
        KestrelPlayer record;
        PlayerTrackingOracle.VPlayer vp;
        int teleportId = -1;
        int batchesToAck;
        final List<Long> keepAlives = new ArrayList<>();
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        boolean onGround;

        Client(Harness h, String name, int viewDistance, int modelCustomisation) {
            this.h = h;
            this.name = name;
            this.profile = Wire.offlineUuid(name);
            this.info = new ClientInformation("en_us", viewDistance, ChatVisiblity.FULL, true, modelCustomisation,
                    HumanoidArm.RIGHT, false, true, ParticleStatus.ALL);
        }

        boolean vpLive() {
            return vp != null && h.oracle.players.contains(vp);
        }

        void login() throws Exception {
            socket = new Socket("127.0.0.1", h.server.boundPort());
            socket.setSoTimeout(10000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            connected = true;
            Wire.Writer hs = new Wire.Writer();
            hs.vint(776).utf("127.0.0.1").s(h.server.boundPort()).vint(2);
            raw(0x00, hs.toBytes());
            Wire.Writer hello = new Wire.Writer();
            hello.utf(name);
            hello.uuid(profile);
            raw(0x00, hello.toBytes());
            expectPacket(PacketIds.LOGIN_FINISHED);
            raw(PacketIds.LOGIN_ACK, new byte[0]);
            ByteBuf ci = Unpooled.buffer();
            info.write(new FriendlyByteBuf(ci));
            byte[] ciBytes = new byte[ci.readableBytes()];
            ci.readBytes(ciBytes);
            ci.release();
            raw(PacketIds.CONFIG_CLIENT_INFO, ciBytes);
            expectPacket(PacketIds.CONFIG_SELECT_KNOWN_OUT);
            raw(PacketIds.CONFIG_SELECT_KNOWN, new Wire.Writer().vint(0).toBytes());
            expectPacket(PacketIds.CONFIG_FINISH_OUT);
            raw(PacketIds.CONFIG_FINISH, new byte[0]);
            // The join runs on the tick thread once the session is PLAY_JOINING.
            long deadline = System.currentTimeMillis() + 10000;
            while (true) {
                NetServer.Session s = h.server.sessions.get(profile);
                if (s != null && s.player != null && s.phase == NetServer.Session.Phase.PLAY_JOINING
                        && (record == null || s.player != record)) {
                    record = s.player;
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException(name + " never reached PLAY_JOINING");
                }
                Thread.sleep(1);
            }
        }

        private void expectPacket(int id) throws Exception {
            for (int i = 0; i < 1000; i++) {
                Wire.Inbound p = read();
                if (p.id() == id) {
                    return;
                }
            }
            throw new IllegalStateException(name + " never got packet " + id);
        }

        NetServer.Session session() {
            NetServer.Session s = h.server.sessions.get(profile);
            return s != null && s.player == record ? s : null;
        }

        /** Chunks Kestrel's sender has sent this profile, around the origin, as vanilla chunk keys. */
        Set<Long> sentChunks() {
            Set<Long> out = new HashSet<>();
            for (int cx = -12; cx <= 12; cx++) {
                for (int cz = -12; cz <= 12; cz++) {
                    if (h.sender.sentVersion(profile, ChunkSender.pack(cx, cz)) >= 0) {
                        out.add(ChunkPos.pack(cx, cz));
                    }
                }
            }
            return out;
        }

        // ---- sending ----

        /** Send one play packet; wait until the server has queued its event, and queue it for the oracle. */
        void send(Packet<?> packet) throws Exception {
            Encoded e = encode(packet);
            int before = h.server.events.size();
            raw(e.id(), e.payload());
            waitEvents(before + 1);
            h.inbound.add(new Inbound(this, e, false));
        }

        void disconnect() throws Exception {
            int before = h.server.events.size();
            socket.close();
            connected = false;
            waitEvents(before + 1);
            h.inbound.add(new Inbound(this, null, true));
        }

        private void waitEvents(int target) throws Exception {
            long deadline = System.currentTimeMillis() + 10000;
            while (h.server.events.size() < target) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException(name + ": the server queued no event (" + h.server.events.size()
                            + " < " + target + ")");
                }
                Thread.sleep(0, 200_000);
            }
        }

        void raw(int id, byte[] payload) throws Exception {
            out.write(Wire.frame(id, payload, -1));
            out.flush();
        }

        // ---- reading ----

        Wire.Inbound read() throws Exception {
            while (true) {
                if (len > 0) {
                    try {
                        int[] l = Wire.readVarInt(buf, 0);
                        if (len >= l[1] + l[0]) {
                            byte[] frame = new byte[l[0]];
                            System.arraycopy(buf, l[1], frame, 0, l[0]);
                            int rest = len - l[1] - l[0];
                            System.arraycopy(buf, l[1] + l[0], buf, 0, rest);
                            len = rest;
                            return Wire.decode(Wire.unframe(frame, -1));
                        }
                    } catch (java.io.IOException e) {
                        // incomplete varint: read more
                    }
                }
                int n = in.read(buf, len, buf.length - len);
                if (n < 0) {
                    throw new java.io.IOException(name + ": server closed");
                }
                len += n;
            }
        }

        /** Everything the server sent this tick, up to the marker the gate queued. */
        List<String> readTick(int tick) throws Exception {
            NetServer.Session s = session();
            if (s == null) {
                return List.of();
            }
            s.send(new Outbound.Packet(MARKER, new Wire.Writer().i(tick).toBytes()));
            // A client's per-tick packet; it also wakes the session's I/O loop to flush.
            Encoded end = encode(ServerboundClientTickEndPacket.INSTANCE);
            raw(end.id(), end.payload());
            List<String> got = new ArrayList<>();
            while (true) {
                Wire.Inbound p = read();
                if (p.id() == MARKER) {
                    break;
                }
                switch (p.id()) {
                    case PacketIds.PLAY_POSITION -> {
                        Wire.Reader r = p.reader();
                        teleportId = r.varInt();
                        x = r.d();
                        y = r.d();
                        z = r.d();
                    }
                    case PacketIds.PLAY_CHUNK_BATCH_FINISHED -> batchesToAck++;
                    case PacketIds.PLAY_KEEPALIVE_OUT -> keepAlives.add(p.reader().l());
                    default -> {
                    }
                }
                got.add(fmt(p.id(), p.payload()));
            }
            return got;
        }

        /** Acknowledge chunk batches and keep-alives, and wait until the server has taken the acks. */
        void settle() throws Exception {
            NetServer.Session s = session();
            while (batchesToAck > 0) {
                batchesToAck--;
                Encoded ack = encode(new ServerboundChunkBatchReceivedPacket(64.0F));
                raw(ack.id(), ack.payload());
            }
            long deadline = System.currentTimeMillis() + 10000;
            while (s != null && s.chunkBatchPending) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException(name + ": chunk batch ack not taken");
                }
                Thread.sleep(0, 200_000);
            }
            for (Long id : keepAlives) {
                send(new ServerboundKeepAlivePacket(id));
            }
            keepAlives.clear();
        }

        void closeQuietly() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (Exception ignored) {
            }
        }

        // ---- movement a client would send ----

        void moveTo(double nx, double ny, double nz, boolean ground) throws Exception {
            x = nx;
            y = ny;
            z = nz;
            onGround = ground;
            send(new ServerboundMovePlayerPacket.Pos(x, y, z, ground, false));
        }

        void moveTo(double nx, double ny, double nz, float nyaw, float npitch, boolean ground) throws Exception {
            x = nx;
            y = ny;
            z = nz;
            yaw = nyaw;
            pitch = npitch;
            onGround = ground;
            send(new ServerboundMovePlayerPacket.PosRot(x, y, z, yaw, pitch, ground, false));
        }

        void look(float nyaw, float npitch) throws Exception {
            yaw = nyaw;
            pitch = npitch;
            send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, false));
        }

        void status(boolean ground) throws Exception {
            onGround = ground;
            send(new ServerboundMovePlayerPacket.StatusOnly(ground, false));
        }

        void input(boolean forward, boolean jump, boolean shift, boolean sprint) throws Exception {
            send(new ServerboundPlayerInputPacket(new Input(forward, false, false, false, jump, shift, sprint)));
        }

        void command(ServerboundPlayerCommandPacket.Action action) throws Exception {
            send(new ServerboundPlayerCommandPacket(vp.player, action));
        }

        void swing(InteractionHand hand) throws Exception {
            send(new ServerboundSwingPacket(hand));
        }

        void clientInfo(int modelCustomisation) throws Exception {
            send(new ServerboundClientInformationPacket(new ClientInformation("en_us", info.viewDistance(),
                    ChatVisiblity.FULL, true, modelCustomisation, HumanoidArm.RIGHT, false, true, ParticleStatus.ALL)));
        }

        /**
         * A standing jump as a client simulates it: jump power 0.42, then
         * gravity 0.08 and drag 0.98 each tick, until it lands at the start
         * height. One packet per tick.
         */
        void jump(double dxPerTick, double dzPerTick) throws Exception {
            double ground = y;
            double vy = 0.42;
            double ny = y;
            for (int i = 0; i < 40; i++) {
                ny += vy;
                vy = (vy - 0.08) * 0.98;
                boolean landed = ny <= ground;
                if (landed) {
                    ny = ground;
                }
                moveTo(x + dxPerTick, ny, z + dzPerTick, landed);
                h.tick();
                if (landed) {
                    return;
                }
            }
        }
    }

    // =====================================================================
    // Worlds
    // =====================================================================

    static void fill(TickLoop loop, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    loop.setBlock(new BlockPos(x, y, z), state, 2);
                }
            }
        }
    }

    /** Stone floor at y 0 over 12 by 12 chunks around the origin; players stand at y 1. */
    static final World FLAT = new World(loop -> fill(loop, -96, 0, -96, 95, 0, 95, Blocks.STONE.defaultBlockState()),
            new BlockPos(0, 0, 0));

    /**
     * The flat floor, a stone pillar to y 20 at the origin where players
     * stand, and a slime pad east of it: a fall onto slime takes no damage.
     */
    static final World PILLAR = new World(loop -> {
        fill(loop, -96, 0, -96, 95, 0, 95, Blocks.STONE.defaultBlockState());
        fill(loop, -1, 1, -1, 1, 20, 1, Blocks.STONE.defaultBlockState());
        fill(loop, 2, 0, -3, 6, 0, 3, Blocks.SLIME_BLOCK.defaultBlockState());
    }, new BlockPos(0, 20, 0));

    // =====================================================================
    // Scenarios
    // =====================================================================

    static List<Scenario> scenarios() {
        List<Scenario> list = new ArrayList<>();
        list.add(new Scenario("join", FLAT, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            h.ticks(10);
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            h.ticks(5);
            // B's first move after its chunks arrived: B now sees A.
            b.status(true);
            h.ticks(20);
        }));
        list.add(new Scenario("walk run jump turn", FLAT, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            h.ticks(5);
            b.status(true);
            h.ticks(3);
            // Walk east, 0.2 a tick.
            for (int i = 0; i < 20; i++) {
                b.moveTo(b.x + 0.2, b.y, b.z, true);
                h.tick();
            }
            h.ticks(3);
            // Run: sprint on, 0.28 a tick, sprint off.
            b.input(true, false, false, true);
            b.command(ServerboundPlayerCommandPacket.Action.START_SPRINTING);
            for (int i = 0; i < 20; i++) {
                b.moveTo(b.x + 0.28, b.y, b.z, true);
                h.tick();
            }
            b.input(false, false, false, false);
            b.command(ServerboundPlayerCommandPacket.Action.STOP_SPRINTING);
            h.ticks(3);
            // A standing jump, then a running jump.
            b.jump(0, 0);
            h.ticks(4);
            b.input(true, true, false, true);
            b.command(ServerboundPlayerCommandPacket.Action.START_SPRINTING);
            b.jump(0.3, 0);
            b.input(false, false, false, false);
            b.command(ServerboundPlayerCommandPacket.Action.STOP_SPRINTING);
            h.ticks(4);
            // The head only: pitch, no yaw.
            for (int i = 0; i < 10; i++) {
                b.look(b.yaw, b.pitch - 7.5f);
                h.tick();
            }
            // The body: yaw, one step a tick, past 180 and round.
            for (int i = 0; i < 14; i++) {
                b.look(b.yaw + 29.0f, b.pitch);
                h.tick();
            }
            // A sub-step turn, then a move with a turn.
            b.look(b.yaw + 0.5f, b.pitch);
            h.tick();
            b.moveTo(b.x - 0.2, b.y, b.z, b.yaw - 90.0f, 12.0f, true);
            h.ticks(10);
        }));
        list.add(new Scenario("teleport fallback", PILLAR, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            a.status(true);
            b.status(true);
            h.ticks(5);
            // Walk east off the pillar (its top ends at x 2, the player is 0.6 wide).
            for (int i = 0; i < 8; i++) {
                b.moveTo(b.x + 0.25, b.y, b.z, true);
                h.tick();
            }
            b.moveTo(b.x + 0.2, b.y - 0.1, b.z, false);
            h.tick();
            // More than 8 blocks down in one packet: past the move packets' short range.
            b.moveTo(b.x, b.y - 9.5, b.z, false);
            h.tick();
            // The rest of the way down, then onto the slime.
            while (b.y - 2.0 > 1.0) {
                b.moveTo(b.x, b.y - 2.0, b.z, false);
                h.tick();
            }
            b.moveTo(b.x, 1.0, b.z, true);
            h.ticks(10);
        }));
        list.add(new Scenario("sneak and sprint", FLAT, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            b.status(true);
            h.ticks(5);
            b.input(false, false, true, false);
            h.ticks(6);
            b.moveTo(b.x + 0.08, b.y, b.z, true);
            h.ticks(3);
            b.input(false, false, false, false);
            h.ticks(6);
            b.command(ServerboundPlayerCommandPacket.Action.START_SPRINTING);
            h.ticks(5);
            b.command(ServerboundPlayerCommandPacket.Action.STOP_SPRINTING);
            h.ticks(5);
            // Both in one tick, and a sneak while sprinting.
            b.command(ServerboundPlayerCommandPacket.Action.START_SPRINTING);
            b.input(false, false, true, true);
            h.ticks(3);
            b.input(false, false, false, false);
            b.command(ServerboundPlayerCommandPacket.Action.STOP_SPRINTING);
            h.ticks(5);
        }));
        list.add(new Scenario("swing", FLAT, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            h.ticks(5);
            b.swing(InteractionHand.MAIN_HAND);
            h.ticks(8);
            b.swing(InteractionHand.OFF_HAND);
            h.ticks(8);
            // Swings closer than half the swing: vanilla restarts only some.
            for (int i = 0; i < 8; i++) {
                b.swing(InteractionHand.MAIN_HAND);
                h.tick();
            }
            b.swing(InteractionHand.MAIN_HAND);
            b.swing(InteractionHand.OFF_HAND);
            h.ticks(8);
        }));
        list.add(new Scenario("range", FLAT, h -> {
            Client a = h.connect("Alpha", 2, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 2, 0x7F);
            h.join(b);
            a.status(true);
            b.status(true);
            h.ticks(5);
            for (int i = 0; i < 100; i++) {
                b.moveTo(b.x + 0.5, b.y, b.z + 0.13, true);
                h.tick();
            }
            h.ticks(5);
            for (int i = 0; i < 100; i++) {
                b.moveTo(b.x - 0.5, b.y, b.z - 0.13, true);
                h.tick();
            }
            h.ticks(5);
        }));
        list.add(new Scenario("broadcast range", new World(FLAT.build(), FLAT.spawn(), 10), h -> {
            // entity-broadcast-range-percentage 10 (vanilla's floor): the player type's
            // 32 chunks scale to 51 blocks, closer than the view distance of 4 (64).
            Client a = h.connect("Alpha", 4, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 4, 0x7F);
            h.join(b);
            a.status(true);
            b.status(true);
            h.ticks(5);
            for (int i = 0; i < 120; i++) {
                b.moveTo(b.x + 0.5, b.y, b.z, true);
                h.tick();
            }
            h.ticks(5);
            for (int i = 0; i < 120; i++) {
                b.moveTo(b.x - 0.5, b.y, b.z, true);
                h.tick();
            }
            h.ticks(5);
        }));
        list.add(new Scenario("leave and rejoin", FLAT, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            b.status(true);
            h.ticks(5);
            b.disconnect();
            h.ticks(5);
            Client b2 = h.connect("Bravo", 10, 0x3F);
            h.join(b2);
            b2.status(true);
            h.ticks(5);
            b2.moveTo(b2.x + 0.2, b2.y, b2.z, true);
            h.ticks(3);
            // The race: the leave and the next login under the profile land
            // before one tick drains the leave.
            b2.disconnect();
            Client b3 = h.connect("Bravo", 10, 0x7F);
            h.join(b3);
            b3.status(true);
            h.ticks(5);
            b3.moveTo(b3.x - 0.2, b3.y, b3.z, true);
            h.ticks(5);
        }));
        list.add(new Scenario("three players", FLAT, h -> {
            Client a = h.connect("Alpha", 2, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 2, 0x7F);
            h.join(b);
            Client c = h.connect("Charlie", 2, 0x7F);
            h.join(c);
            a.status(true);
            b.status(true);
            c.status(true);
            h.ticks(5);
            // C walks east past A's range but stays in B's once B follows half way.
            for (int i = 0; i < 80; i++) {
                c.moveTo(c.x + 0.5, c.y, c.z, true);
                if (i % 2 == 0) {
                    b.moveTo(b.x + 0.5, b.y, b.z, true);
                }
                if (i == 20) {
                    a.swing(InteractionHand.MAIN_HAND);
                }
                h.tick();
            }
            c.swing(InteractionHand.MAIN_HAND);
            b.input(false, false, true, false);
            h.ticks(5);
            for (int i = 0; i < 80; i++) {
                c.moveTo(c.x - 0.5, c.y, c.z, true);
                h.tick();
            }
            h.ticks(5);
        }));
        list.add(new Scenario("client information", FLAT, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            h.ticks(5);
            b.clientInfo(0x3F);
            h.ticks(3);
            b.clientInfo(0x3E);
            h.ticks(3);
            b.clientInfo(0x7F);
            h.ticks(3);
        }));
        list.add(new Scenario("equipment full kit pairing", FLAT, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            h.setItem(a, 0, new ItemStack(Items.DIAMOND_SWORD));
            h.setItem(a, 40, new ItemStack(Items.SHIELD));
            h.setItem(a, 36, new ItemStack(Items.DIAMOND_BOOTS));
            h.setItem(a, 37, new ItemStack(Items.DIAMOND_LEGGINGS));
            h.setItem(a, 38, new ItemStack(Items.DIAMOND_CHESTPLATE));
            h.setItem(a, 39, new ItemStack(Items.DIAMOND_HELMET));
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            b.status(true);
            h.ticks(5);
        }));
        list.add(new Scenario("equipment empty pairing", FLAT, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            b.status(true);
            h.ticks(5);
        }));
        list.add(new Scenario("equipment pickup and slots", FLAT, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            a.status(true);
            b.status(true);
            h.ticks(3);
            h.setItem(b, 0, new ItemStack(Items.IRON_PICKAXE));
            h.tick();
            h.setItem(b, 1, new ItemStack(Items.IRON_PICKAXE));
            h.setItem(b, 0, ItemStack.EMPTY);
            b.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1));
            h.ticks(3);
        }));
        list.add(new Scenario("equipment clean hand swap", FLAT, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            a.status(true);
            b.status(true);
            h.setItem(b, 0, new ItemStack(Items.DIAMOND_SWORD));
            h.setItem(b, 40, new ItemStack(Items.SHIELD));
            h.tick();
            b.send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                    net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ZERO, net.minecraft.core.Direction.DOWN, 0));
            h.ticks(3);
        }));
        list.add(new Scenario("equipment non swap and armour break", FLAT, h -> {
            Client a = h.connect("Alpha", 10, 0x7F);
            h.join(a);
            Client b = h.connect("Bravo", 10, 0x7F);
            h.join(b);
            a.status(true);
            b.status(true);
            h.setItem(b, 0, new ItemStack(Items.IRON_SWORD));
            h.setItem(b, 40, new ItemStack(Items.SHIELD));
            h.setItem(b, 39, new ItemStack(Items.IRON_HELMET));
            h.tick();
            h.setItem(b, 0, new ItemStack(Items.DIAMOND_AXE));
            h.setItem(b, 40, new ItemStack(Items.TORCH));
            h.setItem(b, 39, ItemStack.EMPTY);
            h.ticks(3);
        }));
        return list;
    }
}
