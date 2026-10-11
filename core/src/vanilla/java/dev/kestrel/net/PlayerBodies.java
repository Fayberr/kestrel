package dev.kestrel.net;

import dev.kestrel.entity.EntityLevel;
import dev.kestrel.entity.EntityTracker;
import dev.kestrel.entity.KestrelPlayerEntity;
import dev.kestrel.entity.KestrelServerPlayer;
import dev.kestrel.entity.TrackingObserver;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Players as other players see them (G15 slice 1): the tab list rows and
 * the tracked bodies. Each joined {@link KestrelPlayer} record gets a
 * {@link KestrelPlayerEntity} in the entity tracker, with its existing
 * entity id, and this class runs the vanilla steps around it:
 * <ul>
 *   <li>join: {@code PlayerList.placeNewPlayer}'s teleport, the tab list of
 *       everyone already in to the new player, the new player's row to
 *       everyone (itself included), then the level add
 *       ({@code PlayerList.java:204-215});</li>
 *   <li>leave: {@code PlayerList.remove}: the level removal, then the tab
 *       list removal to everyone left ({@code :325-338});</li>
 *   <li>inbound: movement, input (sneak), command (sprint), swing and
 *       client information, each as its {@code ServerGamePacketListenerImpl}
 *       handler does it;</li>
 *   <li>{@link #tick}: each connection's {@code tick} in join order.</li>
 * </ul>
 * Records are keyed by identity, not profile: a second login under one
 * profile is a second record with its own body, and the first record's
 * late disconnect must not touch it.
 *
 * <p>Thread-safety: tick thread only.
 */
final class PlayerBodies {

    /** {@code ServerGamePacketListenerImpl.CLIENT_LOADED_TIMEOUT_TIME}. */
    static final int CLIENT_LOADED_TIMEOUT_TIME = 60;

    private final EntityTracker tracker;
    private final EntityLevel level;
    private final java.util.function.BiConsumer<KestrelPlayer, Exception> connectionFault;
    private final java.util.function.Supplier<net.minecraft.core.BlockPos> spawn;
    private final long seed;
    /** {@code PlayerList.players}: joined bodies in join order. */
    private final List<Body> joined = new ArrayList<>();
    private final Map<KestrelPlayer, Body> byRecord = new IdentityHashMap<>();
    private long connectionTicks;
    private Map<String, Integer> damageTypeIds = Map.of();

    PlayerBodies(EntityTracker tracker, EntityLevel level,
            java.util.function.BiConsumer<KestrelPlayer, Exception> connectionFault,
            java.util.function.Supplier<net.minecraft.core.BlockPos> spawn, long seed) {
        this.tracker = tracker;
        this.level = level;
        this.connectionFault = connectionFault;
        this.spawn = spawn;
        this.seed = seed;
    }

    /** Cache the exact raw-id order sent for the synced damage-type registry. */
    void setDatapack(List<Datapack.RegistryEntries> packs) {
        bindFluidTags(packs);
        for (Datapack.RegistryEntries registry : packs) {
            if (!registry.key().equals("minecraft:damage_type")) continue;
            java.util.LinkedHashMap<String, Integer> ids = new java.util.LinkedHashMap<>();
            for (int i = 0; i < registry.entries().size(); i++) {
                ids.put(registry.entries().get(i).getKey(), i);
            }
            damageTypeIds = Map.copyOf(ids);
            KestrelPlayerEntity.setRegistryAccess(level, damageRegistry(registry));
            return;
        }
        damageTypeIds = Map.of();
    }

    private static void bindFluidTags(List<Datapack.RegistryEntries> packs) {
        Map<String, List<Integer>> tags = Datapack.tags(packs).getOrDefault("minecraft:fluid", Map.of());
        if (tags.isEmpty()) return;
        try {
            var registry = net.minecraft.core.registries.BuiltInRegistries.FLUID;
            var perHolder = new java.util.IdentityHashMap<net.minecraft.core.Holder.Reference<net.minecraft.world.level.material.Fluid>,
                    java.util.Set<net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid>>>();
            for (var tag : tags.entrySet()) {
                var key = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.FLUID,
                        net.minecraft.resources.Identifier.parse(tag.getKey()));
                for (int id : tag.getValue()) {
                    registry.get(id).ifPresent(holder -> perHolder.computeIfAbsent(holder,
                            ignored -> new java.util.HashSet<>()).add(key));
                }
            }
            var bind = Class.forName("net.minecraft.core.Holder$Reference")
                    .getDeclaredMethod("bindTags", java.util.Collection.class);
            bind.setAccessible(true);
            for (var entry : perHolder.entrySet()) bind.invoke(entry.getKey(), entry.getValue());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot bind served fluid tags", e);
        }
    }

    /**
     * Build the registry exactly as vanilla's registry loader does: decode the
     * values Kestrel serves and register them in their served raw-id order.
     */
    static net.minecraft.core.RegistryAccess.Frozen damageRegistry(Datapack.RegistryEntries entries) {
        var registry = new net.minecraft.core.MappedRegistry<net.minecraft.world.damagesource.DamageType>(
                net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                com.mojang.serialization.Lifecycle.stable());
        for (var entry : entries.entries()) {
            try {
                var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(entry.getValue()));
                net.minecraft.nbt.Tag tag = net.minecraft.nbt.NbtIo.readAnyTag(
                        input, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                net.minecraft.world.damagesource.DamageType value =
                        net.minecraft.world.damagesource.DamageType.DIRECT_CODEC
                                .parse(net.minecraft.nbt.NbtOps.INSTANCE, tag)
                                .getOrThrow(message -> new IllegalStateException(
                                        "invalid damage type " + entry.getKey() + ": " + message));
                var key = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                        net.minecraft.resources.Identifier.parse(entry.getKey()));
                registry.register(key, value, net.minecraft.core.RegistrationInfo.BUILT_IN);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot decode damage type " + entry.getKey(), e);
            }
        }
        registry.freeze();
        java.util.List<net.minecraft.core.Registry<?>> registries = new java.util.ArrayList<>();
        net.minecraft.core.registries.BuiltInRegistries.REGISTRY.forEach(registries::add);
        registries.add(registry);
        return new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(registries).freeze();
    }

    /** One joined record: its observer (its connection), its body and its handler state. */
    static final class Body {
        final KestrelPlayer player;
        final TrackingObserver observer;
        final KestrelPlayerEntity entity;
        final List<Outbound.ProfileProperty> properties;
        /** {@code clientLoadedTimeoutTimer}: the client has loaded once this is 0. */
        int clientLoadedTimeout = CLIENT_LOADED_TIMEOUT_TIME;
        boolean faulted;
        boolean bodyRemoved;
        boolean injectedConnectionFault;
        long connectionTicks;
        long connectionAttempts;

        Body(KestrelPlayer player, TrackingObserver observer, KestrelPlayerEntity entity,
                List<Outbound.ProfileProperty> properties) {
            this.player = player;
            this.observer = observer;
            this.entity = entity;
            this.properties = properties;
        }

        /** {@code hasClientLoaded} (respawn is not modelled). */
        boolean clientLoaded() {
            return clientLoadedTimeout <= 0;
        }

        PlayerOutbound.Entry entry() {
            return new PlayerOutbound.Entry(player.profile, player.name, properties, 0, 0, entity.showsHat());
        }
    }

    /**
     * The client information a login sent in configuration, or vanilla's
     * default ({@code ClientInformation.createDefault}: no skin layers, right
     * hand) when it sent none or a malformed one.
     */
    record Options(int modelCustomisation, HumanoidArm mainHand) {
        static final Options DEFAULT = new Options(0, HumanoidArm.RIGHT);

        /** {@code ClientInformation(FriendlyByteBuf)} ({@code ClientInformation.java:20-31}). */
        static Options read(byte[] payload) {
            if (payload == null) {
                return DEFAULT;
            }
            try {
                Wire.Reader r = new Wire.Reader(payload);
                r.utf(16); // language
                r.b(); // view distance
                r.varInt(); // chat visibility
                r.bool(); // chat colours
                int model = r.b();
                int hand = r.varInt();
                r.bool(); // text filtering
                r.bool(); // allows listing
                r.varInt(); // particle status
                if (hand < 0 || hand >= HumanoidArm.values().length) {
                    return DEFAULT;
                }
                return new Options(model, HumanoidArm.values()[hand]);
            } catch (IOException e) {
                return DEFAULT;
            }
        }
    }

    // ---- join and leave ----

    /**
     * {@code PlayerList.placeNewPlayer} up to the level info, for a record
     * whose join position is set: build the body as
     * {@code PrepareSpawnTask.spawn} does (the constructor's client options,
     * then {@code snapTo}), apply the join teleport's state
     * ({@code teleportSetPosition}: the head turns to the yaw, the motion is
     * zeroed; the packet itself is Kestrel's join stage 2), then send the tab
     * list rows. {@link #addToLevel} follows the level info. A body still
     * present under the same profile leaves first, as vanilla disconnects a
     * duplicate login before placing the new one.
     */
    void join(KestrelPlayer player, TrackingObserver observer, List<Outbound.ProfileProperty> properties,
            Options options) {
        for (Body other : List.copyOf(joined)) {
            if (other.player.profile.equals(player.profile)) {
                leave(other.player);
            }
        }
        KestrelPlayerEntity entity = new KestrelPlayerEntity(player.entityId, player.profile, player.name, level,
                observer, this::sent);
        player.inventory.attach(entity.vanillaPlayer());
        entity.updateOptions(options.modelCustomisation(), options.mainHand());
        entity.spawnAt(player.x, player.y, player.z, player.yaw, player.pitch);
        entity.teleport(player.x, player.y, player.z, player.yaw, player.pitch);
        Body body = new Body(player, observer, entity, List.copyOf(properties));

        List<PlayerOutbound.Entry> before = new ArrayList<>();
        for (Body b : joined) {
            before.add(b.entry());
        }
        observer.send(PlayerOutbound.playerInfoInitializing(before));
        joined.add(body);
        byRecord.put(player, body);
        Outbound.Packet row = PlayerOutbound.playerInfoInitializing(List.of(body.entry()));
        for (Body b : joined) {
            b.observer.send(row);
        }
    }

    /** {@code level.addNewPlayer} ({@code PlayerList.java:215}): the body enters the tracker. */
    void addToLevel(KestrelPlayer player) {
        Body body = byRecord.get(player);
        if (body != null) {
            tracker.addPlayer(body.entity, body.observer);
        }
    }

    /**
     * {@code PlayerList.remove}: the body leaves the level (every entity it
     * saw is removed for it, everyone who saw it is sent its removal), then
     * everyone left is told to drop its tab list row. A record that never
     * joined (or already left) is a no-op.
     */
    void leave(KestrelPlayer player) {
        Body body = byRecord.remove(player);
        if (body == null) {
            return;
        }
        body.entity.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        tracker.removePlayer(body.observer);
        joined.remove(body);
        Outbound.Packet removal = PlayerOutbound.playerInfoRemove(List.of(player.profile));
        for (Body b : joined) {
            b.observer.send(removal);
        }
    }

    boolean hasBody(KestrelPlayer player) {
        return byRecord.containsKey(player);
    }

    KestrelPlayerEntity body(KestrelPlayer player) {
        Body body = byRecord.get(player);
        return body == null ? null : body.entity;
    }

    long bodyConnectionTicks(KestrelPlayer player) {
        Body body = byRecord.get(player);
        return body == null ? -1 : body.connectionTicks;
    }

    long bodyConnectionAttempts(KestrelPlayer player) {
        Body body = byRecord.get(player);
        return body == null ? -1 : body.connectionAttempts;
    }

    boolean bodyClientLoaded(KestrelPlayer player) {
        Body body = byRecord.get(player);
        return body != null && body.entity.hasClientLoadedState();
    }

    /** Gate-only connection-tick fault, outside the death path. */
    void faultNextConnectionTick(KestrelPlayer player) {
        Body body = byRecord.get(player);
        if (body != null) body.injectedConnectionFault = true;
    }

    /** {@code ServerGamePacketListenerImpl.hasClientLoaded} for this record's body. */
    boolean clientLoaded(KestrelPlayer player) {
        Body body = byRecord.get(player);
        return body != null && body.clientLoaded();
    }

    // ---- inbound ----

    /**
     * A movement packet Kestrel accepted ({@code handleMovePlayer}). Before
     * the client has loaded nothing happens; while a teleport is awaited only
     * the rotation is taken ({@code :1082-1083}); otherwise the accepted
     * branch runs on the body, with {@code ChunkMap.move} at its point.
     */
    void moved(KestrelPlayer player, Double x, Double y, Double z, Float yaw, Float pitch, boolean onGround,
            boolean horizontalCollision) {
        Body body = byRecord.get(player);
        if (body == null || !body.clientLoaded()) {
            return;
        }
        if (player.teleportPending) {
            body.entity.snapRotation(yaw, pitch);
            return;
        }
        body.entity.handleMove(x, y, z, yaw, pitch, onGround, horizontalCollision,
                () -> tracker.movePlayer(body.observer));
    }

    /** {@code handleAcceptPlayerLoad}: {@code markClientLoaded}. */
    void loaded(KestrelPlayer player) {
        Body body = byRecord.get(player);
        if (body != null) {
            body.clientLoadedTimeout = 0;
            body.entity.markClientLoaded();
        }
    }

    /** A server teleport of the player ({@code ServerGamePacketListenerImpl.teleport}): its state. */
    void teleported(KestrelPlayer player) {
        Body body = byRecord.get(player);
        if (body != null) {
            body.entity.teleport(player.x, player.y, player.z, player.yaw, player.pitch);
        }
    }

    /**
     * One of the body's inbound packets, decoded here as vanilla's codec
     * does. Malformed payloads are dropped (Kestrel's play-phase leniency);
     * vanilla would disconnect.
     */
    void packet(KestrelPlayer player, int id, byte[] payload) {
        Body body = byRecord.get(player);
        if (body == null) {
            return;
        }
        try {
            Wire.Reader r = new Wire.Reader(payload);
            switch (id) {
                case PacketIds.PLAY_CLIENT_COMMAND -> {
                    int action = r.varInt();
                    if (action == 0) performRespawn(body);
                    // wonGame/end credits are intentionally outside G22.
                }
                case PacketIds.PLAY_PLAYER_INPUT -> {
                    // handlePlayerInput: the shift key once loaded (Input.STREAM_CODEC, bit 32).
                    int flags = r.b();
                    if (body.clientLoaded()) {
                        body.entity.handleInput((flags & 32) != 0);
                    }
                }
                case PacketIds.PLAY_PLAYER_COMMAND -> {
                    r.varInt(); // the entity id: vanilla acts on the sender, whatever it says
                    int action = r.varInt();
                    r.varInt(); // data
                    if (body.clientLoaded()) {
                        // Action ordinals: START_SPRINTING 1, STOP_SPRINTING 2.
                        // Sleeping, riding jumps, vehicle inventories and gliding are not built.
                        if (action == 1) {
                            body.entity.handleCommand(true);
                        } else if (action == 2) {
                            body.entity.handleCommand(false);
                        }
                    }
                }
                case PacketIds.PLAY_SWING -> {
                    int hand = r.varInt();
                    if (hand == 0 || hand == 1) {
                        body.entity.swing(hand == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
                    }
                }
                case PacketIds.PLAY_CLIENT_INFORMATION -> {
                    // handleClientInformation: updateOptions, and the hat row when it flips.
                    Options options = Options.read(payload);
                    boolean wasHatShown = body.entity.showsHat();
                    body.entity.updateOptions(options.modelCustomisation(), options.mainHand());
                    if (body.entity.showsHat() != wasHatShown) {
                        Outbound.Packet hat = PlayerOutbound.playerInfoHat(player.profile, body.entity.showsHat());
                        for (Body b : joined) {
                            b.observer.send(hat);
                        }
                    }
                }
                default -> {
                }
            }
        } catch (IOException e) {
            // Malformed: absorbed.
        }
    }

    private void performRespawn(Body body) {
        KestrelServerPlayer player = body.entity.vanillaPlayer();
        if (player.getHealth() > 0.0F) return;
        net.minecraft.core.BlockPos spawn = this.spawn.get();
        net.minecraft.core.GlobalPos death = player.getLastDeathLocation().orElse(null);
        body.observer.send(Outbound.respawn(seed, death));
        double x = spawn.getX() + 0.5;
        double y = spawn.getY() + 1.0;
        double z = spawn.getZ() + 0.5;
        body.entity.respawnAt(x, y, z, 0.0F, 0.0F);
        body.player.moveTo(x, y, z, 0.0F, 0.0F, false);
        body.observer.send(Outbound.position(body.player.nextTeleportId(), x, y, z, 0.0F, 0.0F));
        body.observer.send(Outbound.spawnPos(spawn));
        body.observer.send(Outbound.difficulty());
        for (Outbound.Packet packet : VanillaPacketBridge.encode(new net.minecraft.network.protocol.game.ClientboundSetExperiencePacket(0, 0, 0))) {
            body.observer.send(packet);
        }
        tracker.addPlayerBody(body.entity, body.observer);
        body.bodyRemoved = false;
        body.clientLoadedTimeout = CLIENT_LOADED_TIMEOUT_TIME;
    }

    // ---- ticking ----

    /** The level entity phase: each joined player's {@code ServerPlayer.tick}. */
    void tickLevel() {
        List<Body> bodies = List.copyOf(joined);
        for (Body body : bodies) {
            if (body.clientLoadedTimeout > 0) {
                body.clientLoadedTimeout--;
            }
            body.entity.serverPlayerTick();
        }
    }

    /** The connection phase: each listener's {@code tickPlayer}, in join order. */
    void tickConnections() {
        connectionTicks++;
        List<Body> bodies = List.copyOf(joined);
        for (Body body : bodies) {
            if (body.faulted) continue;
            try {
                body.connectionAttempts++;
                if (body.injectedConnectionFault) {
                    body.injectedConnectionFault = false;
                    throw new IllegalStateException("injected non-death connection fault");
                }
                body.entity.tickPlayer();
                body.connectionTicks++;
                if (!body.bodyRemoved && body.entity.vanillaRemoved()) {
                    tracker.removePlayerBody(body.observer);
                    body.bodyRemoved = true;
                }
            } catch (Exception failure) {
                // ServerConnectionListener.tick:207-218 catches one network
                // connection so a bad player cannot skip later connections.
                body.faulted = true;
                connectionFault.accept(body.player, failure);
            }
        }
    }

    long connectionTicks() {
        return connectionTicks;
    }

    /** The stand-in's own broadcast (its swing), converted and sent through the tracker. */
    private void sent(KestrelPlayerEntity entity, KestrelPlayerEntity.SentPacket sent) {
        Outbound.Packet packet;
        if (sent.packet() instanceof ClientboundAnimatePacket animate) {
            packet = PlayerOutbound.animate(animate.getId(), animate.getAction());
        } else if (sent.packet() instanceof ClientboundSetEquipmentPacket equipment) {
            packet = dev.kestrel.net.EntityOutbound.setEquipment(equipment.getEntity(), equipment.getSlots());
        } else if (sent.packet() instanceof ClientboundEntityEventPacket event) {
            packet = dev.kestrel.net.EntityOutbound.entityEvent(entity.getId(), event.getEventId());
        } else if (sent.packet() instanceof ClientboundDamageEventPacket damage) {
            String key = damage.sourceType().unwrapKey()
                    .orElseThrow(() -> new IllegalStateException("unregistered damage type"))
                    .identifier().toString();
            Integer rawId = damageTypeIds.get(key);
            if (rawId == null) {
                throw new IllegalStateException("damage type was not served: " + key);
            }
            packet = dev.kestrel.net.EntityOutbound.damageEvent(damage, rawId);
        } else if (sent.packet() instanceof ClientboundTakeItemEntityPacket take) {
            packet = dev.kestrel.net.EntityOutbound.takeItemEntity(take.getItemId(), take.getPlayerId(), take.getAmount());
        } else {
            throw new IllegalStateException("unsupported player packet " + sent.packet().type());
        }
        if (sent.andSelf()) {
            tracker.sendToTrackingPlayersAndSelf(entity, packet);
        } else {
            tracker.sendToTrackingPlayers(entity, packet);
        }
    }
}
