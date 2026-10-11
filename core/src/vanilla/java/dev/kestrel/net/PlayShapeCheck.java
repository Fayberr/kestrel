package dev.kestrel.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.RegistryLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 play-shape battery: every clientbound play packet Kestrel's
 * join burst sends is decoded through vanilla's OWN clientbound play
 * codec ({@code GameProtocols.CLIENTBOUND_TEMPLATE}). A shape drift
 * (wrong field type, missing world-clock map, varint-vs-int relatives)
 * fails here with the packet name instead of on a player's screen.
 *
 * <p>This is the automated stand-in for a real client join: it cannot
 * replace the real client (no rendering, no registries), but it
 * catches exactly the class of bug that produced the last two
 * disconnects (set_time 7-bytes-extra, player_position decode
 * failure), both grounded in vanilla codec shapes.
 */
public final class PlayShapeCheck {

    private PlayShapeCheck() {
    }

    /**
     * Registry access that mirrors a real server's composite view
     * (STATIC + datapack): static registries from BuiltInRegistries
     * plus the synced datapack registries loaded from disk. Vanilla's
     * play codecs (dimension holder, world-clock holder) resolve
     * through this, exactly like the client resolves through its
     * config-loaded registries.
     */
    /**
     * Registry access mirroring the client's post-config view: the
     * server's live registries (STATIC layer) PLUS the datapack
     * registries Kestrel serves (WORLDGEN layer: dimension_type,
     * world_clock, ...). Built by running the served entries through
     * each registry's own DIRECT codec against a serialization
     * context, exactly how the client builds its view from the
     * network stream. Only dimension_type + world_clock are
     * registered (the two holders play packets reference); the rest
     * resolve through the static layer.
     */
    public static net.minecraft.core.RegistryAccess.Frozen compositeAccess() {
        var staticAccess =
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                        net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        String packDir = System.getProperty("kestrel.datapack", "/tmp/packdump/data");
        try {
            java.nio.file.Path dataDir = java.nio.file.Path.of(packDir);
            var ops = net.minecraft.nbt.NbtOps.INSTANCE;
            // Decode served entries through their DIRECT codecs into
            // fresh MappedRegistries, then layer them over static.
            java.util.Map<
                    net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>,
                    net.minecraft.core.Registry<?>> extra = new java.util.HashMap<>();
            registerFromEntries(extra, ops, dataDir, "minecraft:dimension_type",
                    net.minecraft.core.registries.Registries.DIMENSION_TYPE,
                    net.minecraft.world.level.dimension.DimensionType.DIRECT_CODEC,
                    List.of("minecraft:overworld", "minecraft:overworld_caves",
                            "minecraft:the_end", "minecraft:the_nether"));
            registerFromEntries(extra, ops, dataDir, "minecraft:world_clock",
                    net.minecraft.core.registries.Registries.WORLD_CLOCK,
                    net.minecraft.world.clock.WorldClock.DIRECT_CODEC,
                    List.of("minecraft:overworld", "minecraft:the_end"));
            if (extra.isEmpty()) {
                return staticAccess;
            }
            var worldgen = new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(
                    extra).freeze();
            return new net.minecraft.core.LayeredRegistryAccess<>(
                    java.util.List.of(net.minecraft.server.RegistryLayer.STATIC,
                            net.minecraft.server.RegistryLayer.WORLDGEN))
                    .replaceFrom(net.minecraft.server.RegistryLayer.STATIC, staticAccess)
                    .replaceFrom(net.minecraft.server.RegistryLayer.WORLDGEN, worldgen)
                    .compositeAccess();
        } catch (Exception e) {
            System.out.println("[playshape] composite fell back to static: " + e);
            return staticAccess;
        }
    }

    /**
     * Register placeholder holders for the registries play packets
     * reference (dimension_type id 0, world_clock id 0). The codec
     * only needs the HOLDERS to exist (raw id -> holder); full
     * values are unnecessary because holderRegistry encodes/decodes
     * by raw id alone. Values come from the live static registries
     * where available (dimension_type has none statically, so a
     * default overworld-like DimensionType is built; world_clock is
     * a unit value).
     */
    private static <T> void registerFromEntries(java.util.Map<
                    net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>,
                    net.minecraft.core.Registry<?>> extra,
            com.mojang.serialization.DynamicOps<net.minecraft.nbt.Tag> ops,
            java.nio.file.Path dataDir, String key,
            net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>> regKey,
            com.mojang.serialization.Codec<T> codec, List<String> fallbackIds) throws Exception {
        // Order comes from the served entry lists (same order the
        // tags packet references): ids-only is enough, values are
        // placeholders.
        java.util.List<String> ids = new java.util.ArrayList<>();
        String[] parts = key.split(":", 2);
        java.nio.file.Path dir = dataDir.resolve(parts[0]).resolve(parts[1]);
        if (java.nio.file.Files.isDirectory(dir)) {
            try (var stream = java.nio.file.Files.walk(dir)) {
                stream.filter(f -> f.getFileName().toString().endsWith(".json"))
                        .sorted().forEach(f -> {
                            String rel = dir.relativize(f).toString().replace('\\', '/');
                            ids.add(parts[0] + ":"
                                    + rel.substring(0, rel.length() - 5));
                        });
            }
        }
        if (ids.isEmpty()) {
            // No datapack on disk (run-core-check.ps1 passes no
            // -Dkestrel.datapack, and the default is a Pi path): use the
            // 26.2 datapack's file names in the same sorted order. Only
            // raw ids resolve here, so names are all the codec needs;
            // without this the login and set_time holders do not exist
            // and both packets fail decode for a missing registry, not a
            // shape drift.
            System.out.println("[playshape] no " + dir + ", using built-in " + key + " ids");
            ids.addAll(fallbackIds);
        }
        var reg = new net.minecraft.core.MappedRegistry<T>(regKey,
                com.mojang.serialization.Lifecycle.stable());
        for (String id : ids) {
            @SuppressWarnings("unchecked")
            T value = (T) placeholderValue(regKey, id);
            if (value == null) {
                continue;
            }
            net.minecraft.resources.ResourceKey<T> rkey =
                    net.minecraft.resources.ResourceKey.create(regKey,
                            net.minecraft.resources.Identifier.parse(id));
            if (reg.get(rkey).isEmpty()) {
                reg.register(rkey, value,
                        net.minecraft.core.RegistrationInfo.BUILT_IN);
            }
        }
        reg.freeze();
        extra.put(regKey, reg);
    }

    /** Placeholder value for shape-check registries (holders only). */
    private static Object placeholderValue(
            net.minecraft.resources.ResourceKey<?> regKey, String id) {
        String key = regKey.identifier().toString();
        if (key.equals("minecraft:world_clock")) {
            return new net.minecraft.world.clock.WorldClock();
        }
        if (key.equals("minecraft:dimension_type")) {
            return defaultDimensionType();
        }
        return null;
    }

    /** Minimal overworld-like DimensionType (holders only, never simulated). */
    private static net.minecraft.world.level.dimension.DimensionType defaultDimensionType() {
        return new net.minecraft.world.level.dimension.DimensionType(
                false, true, false, false, 1.0, -64, 384, 384,
                net.minecraft.core.HolderSet.direct(),
                0.0f,
                new net.minecraft.world.level.dimension.DimensionType.MonsterSettings(
                        net.minecraft.util.valueproviders.ConstantInt.of(0), 0),
                net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD,
                net.minecraft.world.level.CardinalLighting.Type.DEFAULT,
                net.minecraft.world.attribute.EnvironmentAttributeMap.EMPTY,
                net.minecraft.core.HolderSet.direct(),
                java.util.Optional.empty());
    }

    private static RegistryFriendlyByteBuf wrap(byte[] payload) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), access());
    }

    /**
     * Decode one Kestrel play packet through vanilla's BOUND protocol
     * codec (id + payload, exactly like the client reads it). The
     * template codec reads the VarInt id first, so the id is
     * prepended; a shape drift fails here with the packet name.
     */
    private static volatile net.minecraft.core.RegistryAccess.Frozen cachedAccess;

    static net.minecraft.core.RegistryAccess.Frozen access() {
        var a = cachedAccess;
        if (a == null) {
            a = compositeAccess();
            cachedAccess = a;
        }
        return a;
    }

    private static net.minecraft.network.ProtocolInfo<net.minecraft.network.protocol.game.ClientGamePacketListener> bound() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(access()));
    }

    private static Object decode(int id, byte[] payload, String what,
            List<String> failures) {
        try {
            Object pkt = decodePlay(id, payload);
            if (pkt == null) {
                failures.add("playshape: " + what + " decoded to null");
            }
            return pkt;
        } catch (Exception e) {
            failures.add("playshape: " + what + " failed vanilla decode: " + e);
            return null;
        }
    }

    /** Decode id + payload through the bound clientbound play codec (throws on drift). */
    static Object decodePlay(int id, byte[] payload) {
        Wire.Writer w = new Wire.Writer();
        w.vint(id);
        w.bytes(payload);
        return bound().codec().decode(wrap(w.toBytes()));
    }

    /** Overworld clock totalTicks from a vanilla-decoded SetTime, or -1 when absent. */
    static long overworldDayTime(net.minecraft.network.protocol.game.ClientboundSetTimePacket pkt) {
        for (var e : pkt.clockUpdates().entrySet()) {
            if (e.getKey().is(net.minecraft.world.clock.WorldClocks.OVERWORLD)) {
                return e.getValue().totalTicks();
            }
        }
        return -1;
    }

    /** The End clock totalTicks from a vanilla-decoded SetTime, or -1 when absent. */
    static long theEndTicks(net.minecraft.network.protocol.game.ClientboundSetTimePacket pkt) {
        for (var e : pkt.clockUpdates().entrySet()) {
            if (e.getKey().is(net.minecraft.world.clock.WorldClocks.THE_END)) {
                return e.getValue().totalTicks();
            }
        }
        return -1;
    }

    /** Vanilla's numeric id for a play packet type (positional, like the client). */
    static int vanillaId(net.minecraft.network.protocol.PacketType<?> type) {
        int[] found = {-1};
        ((net.minecraft.network.ProtocolInfo.DetailsProvider) GameProtocols.CLIENTBOUND_TEMPLATE)
                .details().listPackets((t, id) -> {
                    if (t.equals(type)) {
                        found[0] = id;
                    }
                });
        return found[0];
    }

    /**
     * Time group (a): SetTime and the rain game events decode through
     * vanilla's codec to the values Kestrel encoded, and their ids match
     * the vanilla clientbound table.
     */
    static void checkTimeAndWeather(List<String> failures) {
        NetCheck.expect(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SET_TIME)
                        == PacketIds.PLAY_SET_TIME,
                "time: set_time id matches vanilla, got "
                        + vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SET_TIME),
                failures);
        NetCheck.expect(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_GAME_EVENT)
                        == PacketIds.PLAY_GAME_EVENT,
                "time: game_event id matches vanilla, got "
                        + vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_GAME_EVENT),
                failures);

        // 1. Clock modification SetTime (single overworld clock)
        Object t = decode(PacketIds.PLAY_SET_TIME, Outbound.setTime(123456789L, 23999L).payload(),
                "time set-time", failures);
        if (t instanceof net.minecraft.network.protocol.game.ClientboundSetTimePacket st) {
            NetCheck.expect(st.gameTime() == 123456789L && st.clockUpdates().size() == 1
                            && overworldDayTime(st) == 23999L,
                    "time: set-time decodes gameTime 123456789 + overworld clock 23999, got "
                            + st.gameTime() + " " + st.clockUpdates(), failures);
            var state = st.clockUpdates().values().iterator().next();
            NetCheck.expect(state.rate() == 1.0f && state.partialTick() == 0.0f,
                    "time: clock rate 1 partial 0", failures);
        } else {
            failures.add("time: set-time decoded to " + t);
        }

        // 2. Periodic SetTime (empty clock map per forceGameTimeSynchronization)
        Object p = decode(PacketIds.PLAY_SET_TIME, Outbound.setTime(987654L).payload(),
                "time periodic", failures);
        if (p instanceof net.minecraft.network.protocol.game.ClientboundSetTimePacket stp) {
            NetCheck.expect(stp.gameTime() == 987654L && stp.clockUpdates().isEmpty(),
                    "time: periodic decodes gameTime 987654 with empty clock map, got "
                            + stp.gameTime() + " " + stp.clockUpdates(), failures);
        } else {
            failures.add("time: periodic decoded to " + p);
        }

        // 3. Full sync SetTime (overworld and the_end clocks per createFullSyncPacket)
        Object f = decode(PacketIds.PLAY_SET_TIME, Outbound.setTimeFull(123456789L, 23999L, 42000L).payload(),
                "time full-sync", failures);
        if (f instanceof net.minecraft.network.protocol.game.ClientboundSetTimePacket stf) {
            NetCheck.expect(stf.gameTime() == 123456789L && stf.clockUpdates().size() == 2
                            && overworldDayTime(stf) == 23999L && theEndTicks(stf) == 42000L,
                    "time: full-sync decodes gameTime 123456789 + overworld 23999 + the_end 42000, got "
                            + stf.gameTime() + " " + stf.clockUpdates(), failures);
        } else {
            failures.add("time: full-sync decoded to " + f);
        }
        checkGameEvent(Outbound.startRaining(),
                net.minecraft.network.protocol.game.ClientboundGameEventPacket.START_RAINING, 0.0f,
                "start-raining", failures);
        checkGameEvent(Outbound.stopRaining(),
                net.minecraft.network.protocol.game.ClientboundGameEventPacket.STOP_RAINING, 0.0f,
                "stop-raining", failures);
        checkGameEvent(Outbound.rainLevel(0.37f),
                net.minecraft.network.protocol.game.ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.37f,
                "rain-level", failures);
    }

    private static void checkGameEvent(Outbound.Packet p,
            net.minecraft.network.protocol.game.ClientboundGameEventPacket.Type type, float param,
            String what, List<String> failures) {
        NetCheck.expect(p.id() == PacketIds.PLAY_GAME_EVENT, "time: " + what + " uses game_event id",
                failures);
        Object o = decode(p.id(), p.payload(), "time " + what, failures);
        NetCheck.expect(o instanceof net.minecraft.network.protocol.game.ClientboundGameEventPacket ge
                        && ge.getEvent() == type && ge.getParam() == param,
                "time: " + what + " decodes to vanilla event with param " + param, failures);
    }

    public static void checkPlayShapes(List<String> failures) {
        try {
            Outbound.Packet join = Outbound.join(1, 20, 4242424242L, 16);
            Object joinPkt = decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_LOGIN),
                    join.payload(), "join", failures);
            if (joinPkt instanceof net.minecraft.network.protocol.game.ClientboundLoginPacket login) {
                NetCheck.expect(login.chunkRadius() == 16,
                        "playshape: join chunk radius decodes to 16, got " + login.chunkRadius(), failures);
                NetCheck.expect(login.simulationDistance() == 10,
                        "playshape: join simulation distance stays 10, got "
                                + login.simulationDistance(), failures);
            } else {
                failures.add("join: decoded to " + joinPkt);
            }

            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_CHANGE_DIFFICULTY), Outbound.difficulty().payload(),
                    "difficulty", failures);
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_PLAYER_ABILITIES), Outbound.abilities().payload(),
                    "abilities", failures);
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SET_HELD_SLOT), Outbound.heldSlot().payload(),
                    "held-slot", failures);
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_UPDATE_RECIPES), Outbound.recipes().payload(),
                    "recipes", failures);
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_INITIALIZE_BORDER), Outbound.initBorder().payload(),
                    "world-border", failures);
            Outbound.Packet time = Outbound.setTime(1000L, 0L);
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SET_TIME), time.payload(), "set-time", failures);
            // set_time must be LONG + 1-entry clock map (19 bytes at
            // day time 0: 8 + 1 + 1 + 1 + 4 + 4), not two LONGs (16).
            NetCheck.expect(time.payload().length == 19,
                    "playshape: set-time length 19, got " + time.payload().length,
                    failures);
            checkTimeAndWeather(failures);
            Outbound.Packet pos = Outbound.position(1, 0.5, 2.0, 0.5, 0f, 0f);
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_PLAYER_POSITION), pos.payload(), "player-position", failures);
            // position ends with relatives as fixed INT (4 bytes), not varint.
            byte[] pp = pos.payload();
            NetCheck.expect(pp[pp.length - 1] == 0 && pp[pp.length - 2] == 0
                            && pp[pp.length - 3] == 0 && pp[pp.length - 4] == 0,
                    "playshape: position ends with 4-byte empty relatives", failures);
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SET_DEFAULT_SPAWN_POSITION), Outbound.spawnPos(
                    new net.minecraft.core.BlockPos(0, 1, 0)).payload(),
                    "spawn-pos", failures);
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_GAME_EVENT), Outbound.gameEvent(
                    PacketIds.EVENT_LEVEL_CHUNKS_LOAD_START, 0).payload(),
                    "game-event", failures);
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_CENTER), Outbound.cacheCenter(0, 0).payload(),
                    "cache-center", failures);
            // Cache radius: id must match vanilla's registration order (this
            // is the exact packet/id that a version drift would silently
            // break, producing the render-boundary bug this check exists to
            // catch), and the payload must decode back to the radius sent.
            NetCheck.expect(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_RADIUS)
                            == PacketIds.PLAY_CACHE_RADIUS,
                    "playshape: cache-radius id matches vanilla, got "
                            + vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_RADIUS),
                    failures);
            Object radiusPkt = decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_RADIUS),
                    Outbound.cacheRadius(16).payload(), "cache-radius", failures);
            if (radiusPkt instanceof net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket rp) {
                NetCheck.expect(rp.getRadius() == 16,
                        "playshape: cache-radius decodes to 16, got " + rp.getRadius(), failures);
            } else {
                failures.add("cache-radius: decoded to " + radiusPkt);
            }
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_CHUNK_BATCH_START), Outbound.batchStart().payload(),
                    "batch-start", failures);
            java.util.UUID id = java.util.UUID.randomUUID();
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE),
                    Outbound.playerInfo(id, "Bot").payload(), "player-info", failures);
            // chunk carries full-bright light: decode an all-air column
            // through vanilla's level-chunk codec (28 sections like the
            // real overworld height).
            java.util.List<byte[]> secs = new java.util.ArrayList<>();
            net.minecraft.world.level.block.state.BlockState air =
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            net.minecraft.world.level.block.state.BlockState[] cells =
                    new net.minecraft.world.level.block.state.BlockState[4096];
            java.util.Arrays.fill(cells, air);
            for (int i = 0; i < 28; i++) {
                secs.add(Outbound.sectionBytes(cells, 0, 0, air, 1));
            }
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT),
                    Outbound.chunk(0, 0, secs, secs.size() + 2).payload(),
                    "chunk", failures);
            // block update shape.
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_BLOCK_UPDATE),
                    Outbound.blockUpdate(
                            net.minecraft.core.BlockPos.asLong(0, 1, 0),
                            net.minecraft.world.level.block.Block.getId(air)).payload(),
                    "block-update", failures);
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_BLOCK_CHANGED_ACK),
                    Outbound.blockChangedAck(7).payload(), "block-changed-ack", failures);
            NetCheck.expect(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_BLOCK_CHANGED_ACK)
                            == PacketIds.PLAY_BLOCK_CHANGED_ACK,
                    "playshape: block-changed-ack id matches vanilla", failures);
            decode(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT),
                    Outbound.systemChat("<Bot> hello").payload(), "system-chat", failures);
            NetCheck.expect(vanillaId(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT)
                            == PacketIds.PLAY_SYSTEM_CHAT,
                    "playshape: system-chat id matches vanilla", failures);
        } catch (Exception e) {
            failures.add("playshape: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        List<String> failures = new ArrayList<>();
        checkPlayShapes(failures);
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.err.println("[playshape] FAILURE: " + f);
            }
            System.exit(1);
        }
        System.out.println("[playshape] PASS (all play packets decode through vanilla codecs)");
    }
}
