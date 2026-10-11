package dev.kestrel.entity;

import dev.kestrel.net.EntityOutbound;
import dev.kestrel.net.Outbound;
import dev.kestrel.net.PacketIds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * S14 slice 2 gate: Kestrel's entity tracker ({@link EntityTracker},
 * {@link EntitySync}, {@link EntityOutbound}) against vanilla 26.2.
 *
 * <p>The oracle is vanilla's real {@link ServerEntity}, one per entity,
 * driven by a {@link ServerEntity.Synchronizer} that captures instead of
 * sending, over real vanilla {@link Entity} objects. There is no live
 * {@code ServerLevel} to hang them on, so the entities live in an
 * {@link OracleLevel}: a {@code ServerLevel} subclass allocated with
 * {@code Unsafe.allocateInstance} (no constructor runs) that answers the
 * three calls the entity paths make: a network id for
 * {@code Entity.<init>}, "no supporting block" for {@code setOnGround}, and
 * a no-op game event for dismounting. Plain entities are an
 * {@link OracleEntity} (a concrete subclass of vanilla's abstract
 * {@code Entity} that adds nothing, so every tracker path is the base one);
 * living entities are a real vanilla {@link ArmorStand}. Every vanilla
 * packet is encoded through the bound 26.2 clientbound play protocol (with
 * bundles split by vanilla's own {@code BundlerInfo}) and compared byte for
 * byte, id included, with what Kestrel's observer received.
 *
 * <ol>
 *   <li>Packet ids: every id Kestrel added, against vanilla's registered
 *       {@link PacketType} position in the clientbound play table.</li>
 *   <li>Base synched data layout: ids and serializers against vanilla
 *       {@code Entity}'s own accessors, read reflectively.</li>
 *   <li>Delta codec: the tracker's live codec against a fresh vanilla
 *       {@link VecDeltaCodec} on thousands of seeded pairs, the round trip,
 *       and the short-overflow threshold at +-32767/32768/-32769 steps,
 *       each decided by vanilla's real {@code ServerEntity}.</li>
 *   <li>Scripted scenarios, packet for packet and tick for tick, over
 *       several entity types (update intervals 1, 2, 3, 20 and never, with
 *       and without delta tracking): standing still, walking past the
 *       forced teleport period, creeping below the position tolerance,
 *       teleport, rotation, sub-step rotation, ground flips, data changes
 *       on off-interval ticks, motion, needsSync, hurtMarked, syncPosition,
 *       precise position, riding and dismounting, head rotation and
 *       attributes on a living entity.</li>
 *   <li>Pairing bundles: fresh, with non-default data, mid-life (the last
 *       sent base, not the live position), riding, living.</li>
 *   <li>Visibility: seeded grids of observers and entities against a
 *       brute-force reimplementation of {@code TrackedEntity.updatePlayer},
 *       with passenger range expansion, range scaling, view distance, the
 *       chunk-tracked test, broadcastToPlayer, exact boundaries and the
 *       entity being the player itself.</li>
 *   <li>Lifecycle and residue.</li>
 * </ol>
 * Fixed seed. Exit 0 on PASS, 1 otherwise.
 */
public final class EntityTrackingCheck {
    private EntityTrackingCheck() {
    }

    private static final long SEED = 0x5140_2_7AL;
    private static final List<String> failures = new ArrayList<>();
    private static int comparisons;
    private static RegistryAccess access;
    private static ProtocolInfo<ClientGamePacketListener> protocol;

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access));

        checkPacketIds();
        checkDataLayout();
        checkDeltaCodec();
        checkScenarios();
        checkPairing();
        checkVisibility();
        checkLifecycle();
        checkTickSlot();

        if (!failures.isEmpty()) {
            System.out.println("EntityTrackingCheck FAILED with " + failures.size() + " failure(s):");
            for (int i = 0; i < Math.min(failures.size(), 40); i++) {
                System.out.println("  - " + failures.get(i));
            }
            System.exit(1);
        }
        System.out.println("  " + comparisons + " comparisons against vanilla or the reference");
        System.out.println("EntityTrackingCheck: PASS");
    }

    private static void expect(boolean ok, String msg) {
        comparisons++;
        if (!ok) {
            failures.add(msg);
        }
    }

    // ---------------------------------------------------------------------
    // Vanilla oracle
    // ---------------------------------------------------------------------

    /** A ServerLevel with no constructor run; see the class comment. */
    static final class OracleLevel extends ServerLevel {
        private int nextEntityId;

        private OracleLevel() {
            super(null, Runnable::run, null, null, Level.OVERWORLD, null, false, 0L, List.of(), false);
            throw new AssertionError("constructor must not run");
        }

        static OracleLevel create() {
            try {
                OracleLevel level = (OracleLevel) unsafe().allocateInstance(OracleLevel.class);
                level.nextEntityId = 1;
                return level;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public int getNextEntityId() {
            return nextEntityId++;
        }

        /** setOnGround(true) asks for the supporting block; there are no blocks. */
        @Override
        public Optional<BlockPos> findSupportingBlock(Entity entity, AABB box) {
            return Optional.empty();
        }

        /** Dismounting emits a game event; nothing listens here. */
        @Override
        public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) {
        }
    }

    /** Vanilla's abstract Entity with nothing added: every tracker path is the base one. */
    static final class OracleEntity extends Entity {
        OracleEntity(EntityType<?> type, Level level) {
            super(type, level);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
            return false;
        }

        @Override
        protected void readAdditionalSaveData(ValueInput input) {
        }

        @Override
        protected void addAdditionalSaveData(ValueOutput output) {
        }

        void flag(int flag, boolean value) {
            setSharedFlag(flag, value);
        }
    }

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    /** Encode one vanilla packet the way the connection would: bundles split, id then payload. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List<String> encodeVanilla(Packet<?> packet) {
        List<String> out = new ArrayList<>();
        protocol.bundlerInfo().unbundlePacket(packet, p -> {
            ByteBuf buf = Unpooled.buffer();
            try {
                protocol.codec().encode(buf, (Packet) p);
                FriendlyByteBuf fb = new FriendlyByteBuf(buf);
                int id = fb.readVarInt();
                byte[] payload = new byte[fb.readableBytes()];
                fb.readBytes(payload);
                out.add(fmt(id, payload));
            } finally {
                buf.release();
            }
        });
        return out;
    }

    /**
     * Normalise what vanilla leaves to identity hashing. The attributes to
     * sync are an {@code ObjectOpenHashSet} of {@code AttributeInstance}s,
     * which do not override hashCode, so the snapshot order inside an
     * UpdateAttributes packet differs from run to run even in vanilla.
     * Snapshots are sorted by attribute id; everything else is untouched.
     */
    static List<String> canonical(List<String> packets, boolean dropData) {
        List<String> out = new ArrayList<>();
        for (String p : packets) {
            int id = idOf(p);
            if (dropData && id == PacketIds.PLAY_SET_ENTITY_DATA) {
                continue;
            }
            if (id == PacketIds.PLAY_UPDATE_ATTRIBUTES) {
                p = sortedAttributes(p);
            }
            out.add(p);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
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

    static String fmt(int id, byte[] payload) {
        return id + ":" + HexFormat.of().formatHex(payload);
    }

    static String fmt(Outbound.Packet p) {
        return fmt(p.id(), p.payload());
    }

    static int idOf(String encoded) {
        return Integer.parseInt(encoded.substring(0, encoded.indexOf(':')));
    }

    /** Vanilla's numeric id for a clientbound play packet type, by table position. */
    static int vanillaId(PacketType<?> type) {
        int[] found = {-1};
        ((ProtocolInfo.DetailsProvider) GameProtocols.CLIENTBOUND_TEMPLATE).details().listPackets((t, id) -> {
            if (t.equals(type)) {
                found[0] = id;
            }
        });
        return found[0];
    }

    /** A test observer that records every packet it is sent, encoded. */
    static final class Capture implements TrackingObserver {
        final int entityId;
        double x;
        double y;
        double z;
        int viewDistance;
        Predicate<Long> chunkTracked = key -> true;
        final List<String> log = new ArrayList<>();

        Capture(int entityId, double x, double y, double z, int viewDistance) {
            this.entityId = entityId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.viewDistance = viewDistance;
        }

        @Override
        public int entityId() {
            return entityId;
        }

        @Override
        public double x() {
            return x;
        }

        @Override
        public double y() {
            return y;
        }

        @Override
        public double z() {
            return z;
        }

        @Override
        public int viewDistance() {
            return viewDistance;
        }

        @Override
        public boolean isChunkTracked(int chunkX, int chunkZ) {
            return chunkTracked.test(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
        }

        @Override
        public void send(Outbound.Packet packet) {
            log.add(fmt(packet));
        }

        List<String> drain() {
            List<String> out = new ArrayList<>(log);
            log.clear();
            return out;
        }
    }

    /** One entity on both sides: a vanilla Entity and a KestrelEntity with the same id and uuid. */
    static final class Twin {
        final Entity v;
        final KestrelEntity k;

        Twin(Entity v, KestrelEntity k) {
            this.v = v;
            this.k = k;
        }

        void pos(double x, double y, double z) {
            v.setPos(x, y, z);
            k.setPos(x, y, z);
        }

        void rot(float yRot, float xRot) {
            v.setYRot(yRot);
            v.setXRot(xRot);
            k.setYRot(yRot);
            k.setXRot(xRot);
        }

        void head(float yHeadRot) {
            v.setYHeadRot(yHeadRot);
            k.setYHeadRot(yHeadRot);
        }

        void motion(double x, double y, double z) {
            v.setDeltaMovement(new Vec3(x, y, z));
            k.setDeltaMovement(new Vec3(x, y, z));
        }

        void ground(boolean onGround) {
            v.setOnGround(onGround);
            k.setOnGround(onGround);
        }

        void glowing(boolean on) {
            ((OracleEntity) v).flag(KestrelEntity.FLAG_GLOWING, on);
            k.setSharedFlag(KestrelEntity.FLAG_GLOWING, on);
        }

        void name(Component name) {
            v.setCustomName(name);
            k.setCustomName(name);
        }

        void nameVisible(boolean on) {
            v.setCustomNameVisible(on);
            k.setCustomNameVisible(on);
        }

        void silent(boolean on) {
            v.setSilent(on);
            k.setSilent(on);
        }

        void noGravity(boolean on) {
            v.setNoGravity(on);
            k.setNoGravity(on);
        }

        void pose(Pose pose) {
            v.setPose(pose);
            k.setPose(pose);
        }

        void air(int air) {
            v.setAirSupply(air);
            k.setAirSupply(air);
        }

        void frozen(int ticks) {
            v.setTicksFrozen(ticks);
            k.setTicksFrozen(ticks);
        }

        void needsSync() {
            v.needsSync = true;
            k.needsSync = true;
        }

        void hurtMarked() {
            v.hurtMarked = true;
            k.hurtMarked = true;
        }

        void syncPosition() {
            v.syncPosition = true;
            k.syncPosition = true;
        }

        void precise(boolean on) {
            v.setRequiresPrecisePosition(on);
            k.setRequiresPrecisePosition(on);
        }

        void ride(Twin vehicle) {
            if (!v.startRiding(vehicle.v, true, false)) {
                throw new IllegalStateException("vanilla refused to ride");
            }
            k.startRiding(vehicle.k);
        }

        void dismount() {
            v.stopRiding();
            k.stopRiding();
        }

        AttributeInstance[] attribute(Holder<Attribute> attribute) {
            AttributeInstance vi = ((LivingEntity) v).getAttributes().getInstance(attribute);
            AttributeInstance ki = k.getAttributes().getInstance(attribute);
            return new AttributeInstance[] {vi, ki};
        }

        /** The two sides must agree on every value the tracker reads. */
        void assertMirrors(String where) {
            expect(v.position().equals(k.position()) && v.trackingPosition().equals(k.trackingPosition()),
                    where + ": position " + v.position() + " vs " + k.position());
            expect(v.getYRot() == k.getYRot() && v.getXRot() == k.getXRot() && v.getYHeadRot() == k.getYHeadRot(),
                    where + ": rotation " + v.getYRot() + "/" + v.getXRot() + "/" + v.getYHeadRot() + " vs "
                            + k.getYRot() + "/" + k.getXRot() + "/" + k.getYHeadRot());
            expect(v.getDeltaMovement().equals(k.getDeltaMovement()), where + ": motion");
            expect(v.onGround() == k.onGround(), where + ": onGround");
            expect(v.isPassenger() == k.isPassenger() && v.getPassengers().size() == k.getPassengers().size(),
                    where + ": riding");
            expect(v.chunkPosition().x() == k.chunkX() && v.chunkPosition().z() == k.chunkZ(),
                    where + ": chunk " + v.chunkPosition() + " vs " + k.chunkX() + "," + k.chunkZ());
            expect(net.minecraft.core.SectionPos.of(v).asLong() == k.sectionKey(), where + ": section");
        }
    }

    /**
     * Both worlds side by side: vanilla ServerEntities in a fastutil map
     * keyed by id (the same map type and iteration order as
     * {@code ChunkMap.entityMap}), and Kestrel's real tracker with one
     * observer that always sees every entity.
     */
    static final class Harness {
        final String name;
        final OracleLevel level = OracleLevel.create();
        final EntityTracker tracker = new EntityTracker(100);
        final Capture observer;
        final Int2ObjectOpenHashMap<ServerEntity> vanilla = new Int2ObjectOpenHashMap<>();
        final List<String> vanillaLog = new ArrayList<>();
        final List<Twin> twins = new ArrayList<>();
        /** Per tick, the packets each side emitted, for scenario assertions. */
        final List<List<String>> ticks = new ArrayList<>();
        int mismatches;
        /** Living twins: the living data layout is slice 6, so data packets are not compared. */
        boolean ignoreData;

        Harness(String name) {
            this.name = name;
            // An observer id no entity will ever get, far view distance.
            this.observer = new Capture(-1000, 0.0, 64.0, 0.0, 32);
            tracker.addPlayer(observer);
        }

        ServerEntity.Synchronizer synchronizer() {
            return new ServerEntity.Synchronizer() {
                @Override
                public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
                    vanillaLog.addAll(encodeVanilla(packet));
                }

                @Override
                public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
                    vanillaLog.addAll(encodeVanilla(packet));
                }

                @Override
                public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet,
                        Predicate<ServerPlayer> predicate) {
                    // The only filtered send is SetPassengers; the one observer
                    // is never a passenger, so the predicate holds for it.
                    vanillaLog.addAll(encodeVanilla(packet));
                }
            };
        }

        Twin spawn(EntityType<?> type, double x, double y, double z, Consumer<Twin> init) {
            OracleEntity v = new OracleEntity(type, level);
            KestrelEntity k = new KestrelEntity(v.getId(), v.getUUID(), type);
            return add(new Twin(v, k), x, y, z, init);
        }

        Twin spawnLiving(double x, double y, double z, Consumer<Twin> init) {
            ArmorStand v = new ArmorStand(EntityTypes.ARMOR_STAND, level);
            KestrelEntity k = new KestrelEntity(v.getId(), v.getUUID(), EntityTypes.ARMOR_STAND,
                    DefaultAttributes.getSupplier(EntityTypes.ARMOR_STAND));
            // ArmorStand's constructor already touched attributes and set its
            // health (living data layout is slice 6). Give the Kestrel map the
            // same syncable instances and start both sides clean.
            for (AttributeInstance vi : v.getAttributes().getSyncableAttributes()) {
                k.getAttributes().getInstance(vi.getAttribute()).setBaseValue(vi.getBaseValue());
            }
            v.getAttributes().getAttributesToSync().clear();
            k.getAttributes().getAttributesToSync().clear();
            v.getEntityData().packDirty();
            ignoreData = true;
            Twin t = new Twin(v, k);
            // LivingEntity's constructor rolls a random yaw; start both at 0.
            t.rot(0.0F, 0.0F);
            t.head(0.0F);
            return add(t, x, y, z, init);
        }

        private Twin add(Twin t, double x, double y, double z, Consumer<Twin> init) {
            t.pos(x, y, z);
            if (twins.isEmpty()) {
                observer.x = x + 0.25;
                observer.z = z + 0.25;
            }
            init.accept(t);
            t.assertMirrors(name + " spawn");
            // ChunkMap.addEntity: TrackedEntity (and its ServerEntity) first, then pair.
            ServerEntity se = new ServerEntity(level, t.v, t.v.getType().updateInterval(),
                    t.v.getType().trackDeltas(), synchronizer());
            vanilla.put(t.v.getId(), se);
            List<Packet<? super ClientGamePacketListener>> members = new ArrayList<>();
            se.sendPairingData(null, members::add);
            vanillaLog.addAll(encodeVanilla(new ClientboundBundlePacket(members)));
            tracker.addEntity(t.k);
            twins.add(t);
            compare("spawn " + t.v.getId());
            return t;
        }

        /** One server tick: vanilla's ChunkMap.tick sendChanges loop, and Kestrel's tracker. */
        void tick(int tick) {
            for (Twin t : twins) {
                t.assertMirrors(name + " tick " + tick);
            }
            for (ServerEntity se : vanilla.values()) {
                se.sendChanges();
            }
            // Keep the observer next to the first entity so it never unpairs.
            Twin first = twins.get(0);
            observer.x = first.k.position().x + 0.25;
            observer.z = first.k.position().z + 0.25;
            tracker.tick();
            for (Twin t : twins) {
                expect(tracker.seenBy(t.k.getId()).contains(observer), name + " tick " + tick + ": observer unpaired");
            }
            ticks.add(compare("tick " + tick));
        }

        List<String> compare(String where) {
            List<String> k = canonical(observer.drain(), ignoreData);
            List<String> v = canonical(new ArrayList<>(vanillaLog), ignoreData);
            vanillaLog.clear();
            boolean same = k.equals(v);
            expect(same || mismatches >= 3, name + " " + where + ": kestrel " + k + " != vanilla " + v);
            if (!same) {
                mismatches++;
            }
            return v;
        }

        long count(int packetId) {
            return ticks.stream().flatMap(List::stream).filter(p -> idOf(p) == packetId).count();
        }

        List<Integer> ticksWith(int packetId) {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < ticks.size(); i++) {
                if (ticks.get(i).stream().anyMatch(p -> idOf(p) == packetId)) {
                    out.add(i);
                }
            }
            return out;
        }
    }

    // ---------------------------------------------------------------------
    // 1. Packet ids
    // ---------------------------------------------------------------------

    private static void checkPacketIds() {
        Map<String, int[]> ids = new HashMap<>();
        ids.put("bundle_delimiter", new int[] {PacketIds.PLAY_BUNDLE_DELIMITER, vanillaId(GamePacketTypes.CLIENTBOUND_BUNDLE_DELIMITER)});
        ids.put("add_entity", new int[] {PacketIds.PLAY_ADD_ENTITY, vanillaId(GamePacketTypes.CLIENTBOUND_ADD_ENTITY)});
        ids.put("entity_position_sync", new int[] {PacketIds.PLAY_ENTITY_POSITION_SYNC, vanillaId(GamePacketTypes.CLIENTBOUND_ENTITY_POSITION_SYNC)});
        ids.put("move_entity_pos", new int[] {PacketIds.PLAY_MOVE_ENTITY_POS, vanillaId(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS)});
        ids.put("move_entity_pos_rot", new int[] {PacketIds.PLAY_MOVE_ENTITY_POS_ROT, vanillaId(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS_ROT)});
        ids.put("move_entity_rot", new int[] {PacketIds.PLAY_MOVE_ENTITY_ROT, vanillaId(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_ROT)});
        ids.put("remove_entities", new int[] {PacketIds.PLAY_REMOVE_ENTITIES, vanillaId(GamePacketTypes.CLIENTBOUND_REMOVE_ENTITIES)});
        ids.put("rotate_head", new int[] {PacketIds.PLAY_ROTATE_HEAD, vanillaId(GamePacketTypes.CLIENTBOUND_ROTATE_HEAD)});
        ids.put("set_entity_data", new int[] {PacketIds.PLAY_SET_ENTITY_DATA, vanillaId(GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA)});
        ids.put("set_entity_motion", new int[] {PacketIds.PLAY_SET_ENTITY_MOTION, vanillaId(GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION)});
        ids.put("set_passengers", new int[] {PacketIds.PLAY_SET_PASSENGERS, vanillaId(GamePacketTypes.CLIENTBOUND_SET_PASSENGERS)});
        ids.put("update_attributes", new int[] {PacketIds.PLAY_UPDATE_ATTRIBUTES, vanillaId(GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES)});
        for (Map.Entry<String, int[]> e : ids.entrySet()) {
            int kestrel = e.getValue()[0];
            int vanilla = e.getValue()[1];
            expect(vanilla >= 0 && kestrel == vanilla,
                    "packet id " + e.getKey() + ": kestrel " + kestrel + " != vanilla " + vanilla);
        }
        System.out.println("  packet ids: " + ids.size() + " cross-checked against the vanilla clientbound table");
    }

    // ---------------------------------------------------------------------
    // 2. Base synched data layout
    // ---------------------------------------------------------------------

    private static void checkDataLayout() throws Exception {
        String[] names = {"DATA_SHARED_FLAGS_ID", "DATA_AIR_SUPPLY_ID", "DATA_CUSTOM_NAME", "DATA_CUSTOM_NAME_VISIBLE",
                "DATA_SILENT", "DATA_NO_GRAVITY", "DATA_POSE", "DATA_TICKS_FROZEN"};
        int n = 0;
        for (String name : names) {
            Field vf = Entity.class.getDeclaredField(name);
            Field kf = KestrelEntity.class.getDeclaredField(name);
            vf.setAccessible(true);
            kf.setAccessible(true);
            EntityDataAccessor<?> va = (EntityDataAccessor<?>) vf.get(null);
            EntityDataAccessor<?> ka = (EntityDataAccessor<?>) kf.get(null);
            expect(va.id() == ka.id() && va.serializer() == ka.serializer(),
                    "data layout " + name + ": kestrel " + ka + " != vanilla " + va);
            n++;
        }
        // Vanilla Entity defines exactly these; nothing more, nothing less.
        int vanillaCount = 0;
        for (Field f : Entity.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == EntityDataAccessor.class) {
                vanillaCount++;
            }
        }
        expect(vanillaCount == names.length, "vanilla Entity has " + vanillaCount + " accessors, expected " + names.length);
        // Fresh entities: both report no non-default values and nothing dirty.
        OracleLevel level = OracleLevel.create();
        OracleEntity v = new OracleEntity(EntityTypes.INTERACTION, level);
        KestrelEntity k = new KestrelEntity(v.getId(), v.getUUID(), EntityTypes.INTERACTION);
        expect(v.getEntityData().getNonDefaultValues() == null && k.getEntityData().getNonDefaultValues() == null,
                "fresh entities carry non-default data");
        expect(!v.getEntityData().isDirty() && !k.getEntityData().isDirty(), "fresh entities are dirty");
        System.out.println("  data layout: " + n + " base accessors match vanilla Entity");
    }

    // ---------------------------------------------------------------------
    // 3. Delta codec
    // ---------------------------------------------------------------------

    private static void checkDeltaCodec() throws Exception {
        Random rnd = new Random(SEED);
        EntityTracker tracker = new EntityTracker(100);
        KestrelEntity k = new KestrelEntity(1, new UUID(1, 1), EntityTypes.INTERACTION);
        tracker.addEntity(k);
        VecDeltaCodec kestrel = tracker.syncOf(1).positionCodec();
        VecDeltaCodec vanilla = new VecDeltaCodec();
        int pairs = 5000;
        for (int i = 0; i < pairs; i++) {
            double scale = i % 3 == 0 ? 30_000_000.0 : i % 3 == 1 ? 1000.0 : 10.0;
            Vec3 base = new Vec3((rnd.nextDouble() - 0.5) * scale, (rnd.nextDouble() - 0.5) * 640.0,
                    (rnd.nextDouble() - 0.5) * scale);
            double reach = i % 2 == 0 ? 8.5 : 0.01;
            Vec3 pos = base.add((rnd.nextDouble() - 0.5) * 2 * reach, (rnd.nextDouble() - 0.5) * 2 * reach,
                    (rnd.nextDouble() - 0.5) * 2 * reach);
            kestrel.setBase(base);
            vanilla.setBase(base);
            long kx = kestrel.encodeX(pos);
            long ky = kestrel.encodeY(pos);
            long kz = kestrel.encodeZ(pos);
            expect(kx == vanilla.encodeX(pos) && ky == vanilla.encodeY(pos) && kz == vanilla.encodeZ(pos),
                    "codec encode differs at " + base + " -> " + pos);
            expect(kestrel.delta(pos).equals(vanilla.delta(pos)), "codec delta differs at " + base);
            expect(kestrel.getBase().equals(vanilla.getBase()), "codec base differs");
            // Decode round trip: what a client rebuilds from the deltas.
            Vec3 kd = kestrel.decode(kx, ky, kz);
            Vec3 vd = vanilla.decode(kx, ky, kz);
            expect(kd.equals(vd), "codec decode differs at " + base);
            // The decision the tracker takes on this delta equals vanilla's inline test.
            boolean vanillaTooBig = kx < -32768L || kx > 32767L || ky < -32768L || ky > 32767L
                    || kz < -32768L || kz > 32767L;
            boolean kestrelTooBig = EntitySync.overflowsShort(kx) || EntitySync.overflowsShort(ky)
                    || EntitySync.overflowsShort(kz);
            expect(vanillaTooBig == kestrelTooBig, "overflow decision differs for " + kx + "," + ky + "," + kz);
            if (!vanillaTooBig) {
                // A fitting delta survives the short cast; the client rebuilds
                // the quantised position per axis, or keeps the base on an
                // axis whose delta is zero (VecDeltaCodec.decode).
                Vec3 back = vanilla.decode((short) kx, (short) ky, (short) kz);
                double ex = kx == 0 ? base.x : Math.round(pos.x * 4096.0) / 4096.0;
                double ey = ky == 0 ? base.y : Math.round(pos.y * 4096.0) / 4096.0;
                double ez = kz == 0 ? base.z : Math.round(pos.z * 4096.0) / 4096.0;
                expect(back.x == ex && back.y == ey && back.z == ez,
                        "round trip off at " + base + " -> " + pos + ": " + back);
            }
        }
        tracker.removeEntity(k);

        // The threshold, decided by vanilla's real ServerEntity, per axis and sign.
        long[] steps = {32767L, 32768L, -32768L, -32769L, 32766L, -32767L};
        int boundaryCases = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (long step : steps) {
                Harness h = new Harness("boundary axis " + axis + " step " + step);
                Twin t = h.spawn(EntityTypes.INTERACTION, 100.0, 64.0, 100.0, tw -> tw.ground(true));
                h.tick(0);
                double d = step / 4096.0;
                t.pos(100.0 + (axis == 0 ? d : 0), 64.0 + (axis == 1 ? d : 0), 100.0 + (axis == 2 ? d : 0));
                for (int tick = 1; tick <= 3; tick++) {
                    h.tick(tick);
                }
                boolean fits = step >= Short.MIN_VALUE && step <= Short.MAX_VALUE;
                List<String> at3 = h.ticks.get(3);
                int expected = fits ? PacketIds.PLAY_MOVE_ENTITY_POS : PacketIds.PLAY_ENTITY_POSITION_SYNC;
                expect(at3.size() == 1 && idOf(at3.get(0)) == expected,
                        "boundary axis " + axis + " step " + step + ": vanilla sent " + at3);
                boundaryCases++;
            }
        }
        System.out.println("  delta codec: " + pairs + " seeded pairs, " + boundaryCases
                + " short-range boundary cases decided by vanilla ServerEntity");
    }

    // ---------------------------------------------------------------------
    // 4. Scripted scenarios
    // ---------------------------------------------------------------------

    interface Script {
        void step(int tick, List<Twin> twins);
    }

    private static Harness run(String name, EntityType<?> type, int ticks, Consumer<Twin> init, Script script) {
        Harness h = new Harness(name + " [" + BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath() + "]");
        h.spawn(type, 8.5, 64.0, 8.5, init);
        for (int tick = 0; tick < ticks; tick++) {
            script.step(tick, h.twins);
            h.tick(tick);
        }
        return h;
    }

    private static void checkScenarios() {
        int scenarios = 0;
        int ticksRun = 0;
        // Built here, not in a static field: EntityTypes needs the bootstrap first.
        EntityType<?>[] scenarioTypes = {
            EntityTypes.INTERACTION,   // interval 3, deltas tracked
            EntityTypes.BLOCK_DISPLAY, // interval 1
            EntityTypes.PLAYER,        // interval 2, deltas not tracked
            EntityTypes.ITEM,          // interval 20
            EntityTypes.ITEM_FRAME,    // interval Integer.MAX_VALUE, deltas not tracked
        };
        for (EntityType<?> type : scenarioTypes) {
            boolean primary = type == EntityTypes.INTERACTION;

            // Standing still: only the periodic zero-delta refresh.
            Harness still = run("still", type, 100, t -> t.ground(true), (tick, tw) -> { });
            for (List<String> tick : still.ticks) {
                for (String p : tick) {
                    expect(idOf(p) == PacketIds.PLAY_MOVE_ENTITY_POS && p.endsWith("00000000000001"),
                            "still: unexpected packet " + p);
                }
            }
            if (primary) {
                expect(still.ticksWith(PacketIds.PLAY_MOVE_ENTITY_POS).equals(List.of(0, 60)),
                        "still: refresh ticks " + still.ticksWith(PacketIds.PLAY_MOVE_ENTITY_POS) + " != [0, 60]");
            }
            scenarios++;
            ticksRun += 100;

            // Walking: small steps for 2500 ticks, past the forced teleport period twice.
            Harness walk = run("walk", type, 2500, t -> t.ground(true), (tick, tw) -> {
                Twin t = tw.get(0);
                t.pos(8.5 + 0.03 * tick, 64.0, 8.5 + 2.0 * Math.sin(tick / 50.0));
            });
            if (primary) {
                expect(walk.count(PacketIds.PLAY_ENTITY_POSITION_SYNC) >= 2,
                        "walk: forced teleport fired " + walk.count(PacketIds.PLAY_ENTITY_POSITION_SYNC) + " times");
                expect(walk.count(PacketIds.PLAY_MOVE_ENTITY_POS) > 700, "walk: small-delta branch too rare");
            }
            scenarios++;
            ticksRun += 2500;

            // Creeping below the position tolerance: the %60 refresh carries it.
            Harness creep = run("creep", type, 400, t -> t.ground(true), (tick, tw) -> {
                Twin t = tw.get(0);
                t.pos(8.5 + 0.00002 * tick, 64.0, 8.5);
            });
            if (primary) {
                // Never over the tolerance between refreshes: every move rides the %60 refresh.
                expect(creep.ticksWith(PacketIds.PLAY_MOVE_ENTITY_POS).equals(List.of(0, 60, 120, 180, 240, 300, 360)),
                        "creep: pos ticks " + creep.ticksWith(PacketIds.PLAY_MOVE_ENTITY_POS));
            }
            scenarios++;
            ticksRun += 400;

            // Teleport well past the delta range.
            Harness teleport = run("teleport", type, 30, t -> t.ground(true), (tick, tw) -> {
                if (tick == 10) {
                    tw.get(0).pos(28.5, 70.0, -11.5);
                }
            });
            if (primary) {
                expect(teleport.ticksWith(PacketIds.PLAY_ENTITY_POSITION_SYNC).equals(List.of(12)),
                        "teleport: sync ticks " + teleport.ticksWith(PacketIds.PLAY_ENTITY_POSITION_SYNC));
            }
            scenarios++;
            ticksRun += 30;

            // Rotation: a full step sends, sub-step changes do not, pos+rot together.
            Harness rotation = run("rotation", type, 50, t -> t.ground(true), (tick, tw) -> {
                Twin t = tw.get(0);
                switch (tick) {
                    case 9 -> t.rot(10.0F, 0.0F);   // packs to 7: sends
                    case 18 -> t.rot(10.5F, 0.0F);  // still packs to 7: silent
                    case 27 -> t.rot(10.5F, 0.9F);  // pitch packs to 0: silent
                    case 36 -> t.rot(10.5F, 1.5F);  // pitch packs to 1, one step: sends
                    case 42 -> {
                        t.rot(-170.0F, -45.0F);
                        t.pos(9.0, 64.0, 8.5);      // both change: PosRot
                    }
                    default -> { }
                }
            });
            if (primary) {
                expect(rotation.ticksWith(PacketIds.PLAY_MOVE_ENTITY_ROT).equals(List.of(9, 36)),
                        "rotation: rot ticks " + rotation.ticksWith(PacketIds.PLAY_MOVE_ENTITY_ROT));
                expect(rotation.ticksWith(PacketIds.PLAY_MOVE_ENTITY_POS_ROT).equals(List.of(42)),
                        "rotation: posrot ticks " + rotation.ticksWith(PacketIds.PLAY_MOVE_ENTITY_POS_ROT));
            }
            scenarios++;
            ticksRun += 50;

            // Ground-state flips force a full sync.
            Harness ground = run("ground", type, 40, t -> t.ground(false), (tick, tw) -> {
                if (tick == 12) {
                    tw.get(0).ground(true);
                } else if (tick == 25) {
                    tw.get(0).ground(false);
                }
            });
            if (primary) {
                expect(ground.ticksWith(PacketIds.PLAY_ENTITY_POSITION_SYNC).equals(List.of(12, 27)),
                        "ground: sync ticks " + ground.ticksWith(PacketIds.PLAY_ENTITY_POSITION_SYNC));
            }
            scenarios++;
            ticksRun += 40;

            // Data changes on off-interval ticks force the sync branch.
            Harness data = run("data", type, 30, t -> t.ground(true), (tick, tw) -> {
                Twin t = tw.get(0);
                switch (tick) {
                    case 4 -> t.glowing(true);
                    case 7 -> {
                        t.name(Component.literal("Kestrel"));
                        t.pose(Pose.CROUCHING);
                    }
                    case 13 -> {
                        t.silent(true);
                        t.noGravity(true);
                        t.air(120);
                        t.frozen(40);
                        t.nameVisible(true);
                        t.pos(8.6, 64.0, 8.5);
                    }
                    case 20 -> {
                        t.glowing(false);
                        t.name(null);
                    }
                    default -> { }
                }
            });
            if (primary) {
                expect(data.ticksWith(PacketIds.PLAY_SET_ENTITY_DATA).equals(List.of(4, 7, 13, 20)),
                        "data: data ticks " + data.ticksWith(PacketIds.PLAY_SET_ENTITY_DATA));
                expect(data.ticksWith(PacketIds.PLAY_MOVE_ENTITY_POS).contains(13),
                        "data: off-interval move not carried by the data sync");
            }
            scenarios++;
            ticksRun += 30;

            // Motion: tracked deltas, tiny changes, vertical only, zeroing, needsSync, hurtMarked.
            Harness motion = run("motion", type, 45, t -> t.ground(true), (tick, tw) -> {
                Twin t = tw.get(0);
                switch (tick) {
                    case 3 -> t.motion(0.1, 0.0, 0.0);
                    case 6 -> t.motion(0.1, 0.0, 0.0001);   // diff^2 1e-8: silent
                    case 9 -> t.motion(0.1, 0.2, 0.0001);   // vertical only
                    case 12 -> t.motion(0.0, 0.0, 0.0);     // to zero
                    case 16 -> {
                        t.motion(0.0, -0.3, 0.0);
                        t.needsSync();                        // off-interval, even untracked deltas
                    }
                    case 22 -> {
                        t.motion(0.5, 0.4, -0.5);
                        t.hurtMarked();
                    }
                    case 29 -> t.syncPosition();
                    case 30 -> t.pos(8.7, 64.0, 8.5);
                    default -> { }
                }
            });
            if (primary) {
                expect(motion.ticksWith(PacketIds.PLAY_SET_ENTITY_MOTION).equals(List.of(3, 9, 12, 16, 22, 24)),
                        "motion: motion ticks " + motion.ticksWith(PacketIds.PLAY_SET_ENTITY_MOTION));
            }
            scenarios++;
            ticksRun += 45;

            // Precise position forces full syncs while set.
            Harness precise = run("precise", type, 30, t -> t.ground(true), (tick, tw) -> {
                Twin t = tw.get(0);
                if (tick == 6) {
                    t.precise(true);
                } else if (tick == 15) {
                    t.precise(false);
                }
                if (tick >= 6) {
                    t.pos(8.5 + 0.01 * tick, 64.0, 8.5);
                }
            });
            if (primary) {
                expect(precise.ticksWith(PacketIds.PLAY_ENTITY_POSITION_SYNC).equals(List.of(6, 9, 12)),
                        "precise: sync ticks " + precise.ticksWith(PacketIds.PLAY_ENTITY_POSITION_SYNC));
            }
            scenarios++;
            ticksRun += 30;
        }

        // Riding: two entities, the passenger's rotation-only branch, dismount sync.
        {
            Harness h = new Harness("riding");
            Twin vehicle = h.spawn(EntityTypes.INTERACTION, 8.5, 64.0, 8.5, t -> t.ground(true));
            Twin rider = h.spawn(EntityTypes.BLOCK_DISPLAY, 9.5, 64.0, 8.5, t -> t.ground(true));
            for (int tick = 0; tick < 60; tick++) {
                if (tick == 5) {
                    rider.ride(vehicle);
                }
                if (tick >= 5 && tick < 30) {
                    vehicle.pos(8.5 + 0.1 * tick, 64.0, 8.5);
                    rider.pos(8.5 + 0.1 * tick, 65.0, 8.5);
                    rider.rot(tick * 3.0F, 0.0F);
                }
                if (tick == 30) {
                    rider.dismount();
                    rider.pos(12.0, 64.0, 9.5);
                }
                h.tick(tick);
            }
            expect(h.ticksWith(PacketIds.PLAY_SET_PASSENGERS).equals(List.of(5, 30)),
                    "riding: passengers ticks " + h.ticksWith(PacketIds.PLAY_SET_PASSENGERS));
            expect(h.ticksWith(PacketIds.PLAY_ENTITY_POSITION_SYNC).contains(30),
                    "riding: dismount did not force a full sync");
            scenarios++;
            ticksRun += 60;
        }

        // Living: head rotation and attribute sync on a vanilla ArmorStand.
        {
            Harness h = new Harness("living");
            Twin t = h.spawnLiving(8.5, 64.0, 8.5, tw -> tw.ground(true));
            Identifier modifierId = Identifier.withDefaultNamespace("kestrel_check");
            for (int tick = 0; tick < 40; tick++) {
                switch (tick) {
                    case 3 -> t.head(45.0F);
                    case 6 -> t.head(45.5F); // same packed step: silent
                    case 7 -> {
                        for (AttributeInstance i : t.attribute(Attributes.SCALE)) {
                            i.setBaseValue(1.5);
                        }
                    }
                    case 13 -> {
                        for (AttributeInstance i : t.attribute(Attributes.MOVEMENT_SPEED)) {
                            i.addTransientModifier(new AttributeModifier(modifierId, 0.25,
                                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                        }
                        for (AttributeInstance i : t.attribute(Attributes.GRAVITY)) {
                            i.setBaseValue(0.04);
                        }
                    }
                    case 20 -> {
                        t.head(-90.0F);
                        t.rot(30.0F, 10.0F);
                        t.pos(9.0, 64.0, 9.0);
                    }
                    default -> { }
                }
                h.tick(tick);
            }
            expect(h.ticksWith(PacketIds.PLAY_ROTATE_HEAD).equals(List.of(3, 21)),
                    "living: head ticks " + h.ticksWith(PacketIds.PLAY_ROTATE_HEAD));
            expect(h.ticksWith(PacketIds.PLAY_UPDATE_ATTRIBUTES).equals(List.of(9, 15)),
                    "living: attribute ticks " + h.ticksWith(PacketIds.PLAY_UPDATE_ATTRIBUTES));
            scenarios++;
            ticksRun += 40;
        }
        System.out.println("  scenarios: " + scenarios + " scripted runs, " + ticksRun
                + " ticks, streams byte-identical to vanilla ServerEntity");
    }

    // ---------------------------------------------------------------------
    // 5. Pairing bundles
    // ---------------------------------------------------------------------

    private static void checkPairing() {
        int cases = 0;
        // Fresh plain entity with rotation and motion, and one with every base value non-default.
        for (boolean nonDefault : new boolean[] {false, true}) {
            Harness h = new Harness("pairing " + (nonDefault ? "non-default" : "plain"));
            h.spawn(EntityTypes.INTERACTION, -3.25, 70.5, 1000.75, t -> {
                t.rot(123.0F, -33.0F);
                t.motion(0.25, -0.5, 0.125);
                t.ground(true);
                if (nonDefault) {
                    t.glowing(true);
                    t.name(Component.literal("pairing"));
                    t.nameVisible(true);
                    t.silent(true);
                    t.noGravity(true);
                    t.pose(Pose.SLEEPING);
                    t.air(7);
                    t.frozen(140);
                }
            });
            cases++;
            // Mid-life pairing: after movement below the tolerance the last
            // sent base differs from the live position; a new player must get
            // the base.
            for (int tick = 0; tick < 50; tick++) {
                Twin t = h.twins.get(0);
                t.pos(-3.25 + 0.0004 * tick, 70.5, 1000.75);
                t.rot(123.0F + tick * 0.1F, -33.0F);
                h.tick(tick);
            }
            expect(!h.vanilla.values().iterator().next().getPositionBase().equals(h.twins.get(0).v.position()),
                    "pairing: mid-life base equals live position, the case is not exercised");
            Capture late = new Capture(-2000, -3.0, 70.0, 1000.0, 10);
            h.tracker.addPlayer(late);
            List<String> vanilla = new ArrayList<>();
            for (ServerEntity se : h.vanilla.values()) {
                List<Packet<? super ClientGamePacketListener>> members = new ArrayList<>();
                se.sendPairingData(null, members::add);
                vanilla.addAll(encodeVanilla(new ClientboundBundlePacket(members)));
            }
            expect(late.drain().equals(vanilla), h.name + " mid-life: bundle differs from vanilla " + vanilla);
            cases++;
        }

        // Riding: both sides of the link appear in the bundle.
        {
            Harness h = new Harness("pairing riding");
            Twin vehicle = h.spawn(EntityTypes.INTERACTION, 0.5, 64.0, 0.5, t -> t.ground(true));
            Twin a = h.spawn(EntityTypes.BLOCK_DISPLAY, 0.5, 65.0, 0.5, t -> { });
            Twin b = h.spawn(EntityTypes.ITEM, 0.5, 66.0, 0.5, t -> { });
            a.ride(vehicle);
            b.ride(vehicle);
            h.tick(0);
            Capture late = new Capture(-2000, 1.0, 64.0, 1.0, 10);
            h.tracker.addPlayer(late);
            List<String> vanilla = new ArrayList<>();
            for (ServerEntity se : h.vanilla.values()) {
                List<Packet<? super ClientGamePacketListener>> members = new ArrayList<>();
                se.sendPairingData(null, members::add);
                vanilla.addAll(encodeVanilla(new ClientboundBundlePacket(members)));
            }
            List<String> kestrel = late.drain();
            expect(kestrel.equals(vanilla), "pairing riding: " + kestrel + " != vanilla " + vanilla);
            expect(kestrel.stream().filter(p -> idOf(p) == PacketIds.PLAY_SET_PASSENGERS).count() == 3,
                    "pairing riding: expected three passenger members");
            cases++;
        }

        // Living: the attribute member (the living data layout is slice 6, so
        // the data member is compared only for presence and position).
        {
            Harness h = new Harness("pairing living");
            Twin t = h.spawnLiving(0.5, 64.0, 0.5, tw -> tw.head(77.0F));
            for (AttributeInstance i : t.attribute(Attributes.SCALE)) {
                i.setBaseValue(0.5);
            }
            h.tick(0);
            Capture late = new Capture(-2000, 1.0, 64.0, 1.0, 10);
            h.tracker.addPlayer(late);
            List<Packet<? super ClientGamePacketListener>> members = new ArrayList<>();
            h.vanilla.values().iterator().next().sendPairingData(null, members::add);
            List<String> vanilla = encodeVanilla(new ClientboundBundlePacket(members));
            List<String> kestrel = late.drain();
            List<String> vNoData = vanilla.stream().filter(p -> idOf(p) != PacketIds.PLAY_SET_ENTITY_DATA).toList();
            List<String> kNoData = kestrel.stream().filter(p -> idOf(p) != PacketIds.PLAY_SET_ENTITY_DATA).toList();
            expect(kNoData.equals(vNoData), "pairing living: " + kNoData + " != vanilla " + vNoData);
            expect(kNoData.stream().anyMatch(p -> idOf(p) == PacketIds.PLAY_UPDATE_ATTRIBUTES),
                    "pairing living: no attribute member");
            cases++;
        }

        // Every packed rotation byte survives the add-entity unpack/pack exactly as vanilla's does.
        for (int b = -128; b < 128; b++) {
            byte packed = (byte) b;
            List<String> v = encodeVanilla(new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(5,
                    new UUID(0, 5), 0, 0, 0, Mth.unpackDegrees(packed), Mth.unpackDegrees(packed),
                    EntityTypes.INTERACTION, 0, Vec3.ZERO, Mth.unpackDegrees(packed)));
            Outbound.Packet k2 = EntityOutbound.addEntity(5, new UUID(0, 5), EntityTypes.INTERACTION, 0, 0, 0,
                    Vec3.ZERO, Mth.packDegrees(Mth.unpackDegrees(packed)), Mth.packDegrees(Mth.unpackDegrees(packed)),
                    Mth.packDegrees(Mth.unpackDegrees(packed)), 0);
            expect(v.size() == 1 && v.get(0).equals(fmt(k2)), "add entity rotation byte " + b);
        }
        cases++;
        System.out.println("  pairing: " + cases + " bundle cases byte-identical to vanilla sendPairingData");
    }

    // ---------------------------------------------------------------------
    // 6. Visibility against a brute-force reference
    // ---------------------------------------------------------------------

    /** Hides itself from observers with an odd entity id (broadcastToPlayer). */
    static final class ShyEntity extends KestrelEntity {
        ShyEntity(int id, EntityType<?> type) {
            super(id, new UUID(7, id), type);
        }

        @Override
        public boolean broadcastToPlayer(TrackingObserver observer) {
            return (observer.entityId() & 1) == 0;
        }
    }

    /** ChunkMap.TrackedEntity.updatePlayer's rule, written out independently. */
    static boolean reference(KestrelEntity e, Capture p, int percentage) {
        if (p.entityId == e.getId()) {
            return false;
        }
        int own = e.getType().clientTrackingRange() * 16;
        int effective = own;
        List<KestrelEntity> stack = new ArrayList<>(e.getPassengers());
        while (!stack.isEmpty()) {
            KestrelEntity q = stack.remove(stack.size() - 1);
            effective = Math.max(effective, q.getType().clientTrackingRange() * 16);
            stack.addAll(q.getPassengers());
        }
        int scaled = percentage * effective / 100;
        double visible = Math.min(scaled, p.viewDistance * 16);
        double dx = p.x - e.position().x;
        double dz = p.z - e.position().z;
        int cx = Math.floorDiv((int) Math.floor(e.position().x), 16);
        int cz = Math.floorDiv((int) Math.floor(e.position().z), 16);
        return dx * dx + dz * dz <= visible * visible
                && e.broadcastToPlayer(p)
                && p.isChunkTracked(cx, cz);
    }

    private static void checkVisibility() {
        Random rnd = new Random(SEED ^ 0x7777);
        EntityType<?>[] types = {EntityTypes.INTERACTION, EntityTypes.ITEM, EntityTypes.SNOWBALL, EntityTypes.PLAYER,
                EntityTypes.ARMOR_STAND, EntityTypes.BLOCK_DISPLAY};
        int decisions = 0;
        for (int percentage : new int[] {100, 50, 133}) {
            EntityTracker tracker = new EntityTracker(percentage);
            List<KestrelEntity> entities = new ArrayList<>();
            int nextId = 1;
            for (int i = 0; i < 40; i++) {
                EntityType<?> type = types[rnd.nextInt(types.length)];
                KestrelEntity e = i % 7 == 3 ? new ShyEntity(nextId++, type) : new KestrelEntity(nextId++, new UUID(3, nextId), type);
                e.setPos((rnd.nextDouble() - 0.5) * 900, 40 + rnd.nextDouble() * 60, (rnd.nextDouble() - 0.5) * 900);
                entities.add(e);
            }
            // Passenger chains: a short range vehicle carrying long range riders.
            KestrelEntity boat = new KestrelEntity(nextId++, new UUID(4, 1), EntityTypes.SNOWBALL);
            KestrelEntity mid = new KestrelEntity(nextId++, new UUID(4, 2), EntityTypes.ITEM);
            KestrelEntity top = new KestrelEntity(nextId++, new UUID(4, 3), EntityTypes.PLAYER);
            boat.setPos(10.5, 64, 10.5);
            mid.setPos(10.5, 65, 10.5);
            top.setPos(10.5, 66, 10.5);
            mid.startRiding(boat);
            top.startRiding(mid);
            entities.add(boat);
            entities.add(mid);
            entities.add(top);
            for (KestrelEntity e : entities) {
                tracker.addEntity(e);
            }
            expect(tracker.effectiveRangeOf(boat.getId()) == percentage * 512 / 100,
                    "effective range of the chain " + tracker.effectiveRangeOf(boat.getId()));

            List<Capture> players = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Capture p = new Capture(10_000 + i, 0, 64, 0, 2 + rnd.nextInt(31));
                LongSet pending = new LongOpenHashSet();
                for (int j = 0; j < 200; j++) {
                    pending.add(net.minecraft.world.level.ChunkPos.pack(rnd.nextInt(60) - 30, rnd.nextInt(60) - 30));
                }
                p.chunkTracked = key -> !pending.contains(key);
                players.add(p);
            }
            // The entity is the player itself: same id, same place.
            Capture self = new Capture(entities.get(0).getId(), entities.get(0).position().x,
                    entities.get(0).position().y, entities.get(0).position().z, 32);
            players.add(self);
            for (Capture p : players) {
                tracker.addPlayer(p);
            }

            // Joining pairs whatever is already in view; check that against the reference too.
            Map<Capture, Set<Integer>> seen = new HashMap<>();
            for (Capture p : players) {
                seen.put(p, new HashSet<>());
                for (KestrelEntity e : entities) {
                    boolean want = reference(e, p, percentage);
                    expect(tracker.seenBy(e.getId()).contains(p) == want,
                            "visibility pct " + percentage + " join: entity " + e.getId() + " player " + p.entityId);
                    if (want) {
                        seen.get(p).add(e.getId());
                        p.log.add("expect pair " + e.getId());
                    }
                }
                checkTransitions(p, "visibility pct " + percentage + " join");
            }
            for (int round = 0; round < 300; round++) {
                for (Capture p : players) {
                    if (p == self) {
                        continue;
                    }
                    KestrelEntity target = entities.get(rnd.nextInt(entities.size()));
                    int mode = rnd.nextInt(4);
                    if (mode == 0) {
                        p.x = (rnd.nextDouble() - 0.5) * 1100;
                        p.z = (rnd.nextDouble() - 0.5) * 1100;
                    } else {
                        // On, just inside, or just outside the visible range of some entity.
                        double r = Math.min(visibleRange(tracker, target), p.viewDistance * 16);
                        double angle = rnd.nextInt(8) * Math.PI / 4;
                        double off = mode == 1 ? 0.0 : mode == 2 ? -1.0E-7 : 1.0E-7;
                        p.x = target.position().x + Math.cos(angle) * (r + off);
                        p.z = target.position().z + Math.sin(angle) * (r + off);
                        if (mode == 1 && rnd.nextBoolean()) {
                            // An exact 3-4-5 triangle lands on the boundary with no rounding.
                            double s = r / 5.0;
                            p.x = target.position().x + 3 * s;
                            p.z = target.position().z - 4 * s;
                        }
                    }
                    // Vertical offsets never matter: the rule is horizontal.
                    p.y = target.position().y + (rnd.nextDouble() - 0.5) * 400;
                    tracker.movePlayer(p);
                }
                for (KestrelEntity e : entities) {
                    Set<TrackingObserver> seenBy = tracker.seenBy(e.getId());
                    for (Capture p : players) {
                        boolean want = reference(e, p, percentage);
                        expect(seenBy.contains(p) == want, "visibility pct " + percentage + " entity " + e.getId()
                                + " player " + p.entityId + " at " + p.x + "," + p.z + ": kestrel "
                                + seenBy.contains(p) + " reference " + want);
                        decisions++;
                        // Packet accounting: one bundle on entering, one remove on leaving.
                        boolean before = seen.get(p).contains(e.getId());
                        if (want) {
                            seen.get(p).add(e.getId());
                        } else {
                            seen.get(p).remove(e.getId());
                        }
                        if (before != want) {
                            p.log.add("expect " + (want ? "pair " : "unpair ") + e.getId());
                        }
                    }
                }
                for (Capture p : players) {
                    checkTransitions(p, "visibility pct " + percentage + " round " + round);
                }
            }
            // The chain rider's range carries the whole chain: a player 400 blocks out sees the boat.
            Capture far = new Capture(20_000, 10.5 + 400, 64, 10.5, 32);
            tracker.addPlayer(far);
            expect(tracker.seenBy(boat.getId()).contains(far) == (percentage * 512 / 100 >= 400),
                    "passenger expansion pct " + percentage);
            expect(!tracker.seenBy(entities.get(0).getId()).contains(self), "an entity paired with itself");
        }
        System.out.println("  visibility: " + decisions + " pair/unpair decisions equal the brute-force rule");
    }

    private static double visibleRange(EntityTracker tracker, KestrelEntity e) {
        return tracker.effectiveRangeOf(e.getId());
    }

    /** Entity id at the head of an encoded payload (a VarInt). */
    private static int idFromAdd(String encoded) {
        byte[] payload = HexFormat.of().parseHex(encoded.substring(encoded.indexOf(':') + 1));
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(payload)).readVarInt();
    }

    /** Each expected pair shows up as exactly one bundle, each unpair as exactly one remove. */
    private static void checkTransitions(Capture p, String where) {
        List<String> log = p.drain();
        List<String> got = new ArrayList<>();
        List<String> want = new ArrayList<>();
        int depth = 0;
        for (String s : log) {
            if (s.startsWith("expect ")) {
                want.add(s.substring(7));
                continue;
            }
            int id = idOf(s);
            if (id == PacketIds.PLAY_BUNDLE_DELIMITER) {
                depth ^= 1;
            } else if (id == PacketIds.PLAY_ADD_ENTITY && depth == 1) {
                got.add("pair " + idFromAdd(s));
            } else if (id == PacketIds.PLAY_REMOVE_ENTITIES) {
                byte[] payload = HexFormat.of().parseHex(s.substring(s.indexOf(':') + 1));
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
                int n = buf.readVarInt();
                for (int i = 0; i < n; i++) {
                    got.add("unpair " + buf.readVarInt());
                }
            }
        }
        got.sort(null);
        want.sort(null);
        expect(depth == 0 && got.equals(want), where + " player " + p.entityId + ": packets " + got + " != " + want);
    }

    // ---------------------------------------------------------------------
    // 7. Lifecycle and residue
    // ---------------------------------------------------------------------

    private static void checkLifecycle() {
        EntityTracker tracker = new EntityTracker(100);
        KestrelEntity a = new KestrelEntity(1, new UUID(9, 1), EntityTypes.INTERACTION);
        a.setPos(0.5, 64, 0.5);
        Capture near = new Capture(100, 5, 64, 5, 10);
        Capture far = new Capture(101, 5000, 64, 5000, 10);
        tracker.addPlayer(near);
        tracker.addPlayer(far);
        tracker.addEntity(a);
        expect(ids(near.drain()).equals(List.of("0", "1", "0")), "lifecycle: pair bundle once");
        expect(far.drain().isEmpty(), "lifecycle: far player got packets");
        expect(tracker.seenBy(1).equals(Set.of(near)), "lifecycle: seen-by after pair");
        // Double add is refused like vanilla.
        boolean threw = false;
        try {
            tracker.addEntity(a);
        } catch (IllegalStateException e) {
            threw = true;
        }
        expect(threw, "lifecycle: duplicate add accepted");
        near.drain();

        // Ticks while standing still: only the periodic refresh, never to far.
        for (int i = 0; i < 70; i++) {
            tracker.tick();
        }
        expect(ids(near.drain()).equals(List.of("53", "53")), "lifecycle: refreshes while still");
        expect(far.drain().isEmpty(), "lifecycle: far player got tick packets");

        // Unpair by moving away, then re-pair.
        near.x = 400;
        tracker.movePlayer(near);
        expect(ids(near.drain()).equals(List.of("77")), "lifecycle: unpair once");
        tracker.movePlayer(near);
        tracker.tick();
        expect(near.drain().isEmpty(), "lifecycle: packets after unpair");
        expect(tracker.seenBy(1).isEmpty(), "lifecycle: seen-by after unpair");
        near.x = 5;
        tracker.movePlayer(near);
        tracker.movePlayer(near);
        expect(ids(near.drain()).equals(List.of("0", "1", "0")), "lifecycle: re-pair once");

        // The entity moves across a section into the far player's range: the tick pairs it.
        a.setPos(4990.5, 64, 4990.5);
        tracker.tick();
        List<String> farLog = ids(far.drain());
        expect(farLog.equals(List.of("0", "1", "0")), "lifecycle: section move pairs far " + farLog);
        List<String> nearLog = ids(near.drain());
        expect(nearLog.equals(List.of("77")), "lifecycle: section move unpairs near " + nearLog);

        // Remove while paired: one remove to the viewer, nothing after. The
        // live seen-by view taken before removal must be empty afterwards
        // too: nothing may keep a removed entity's viewers.
        Set<TrackingObserver> liveView = tracker.seenBy(1);
        expect(liveView.contains(far), "lifecycle: live seen-by view before remove");
        tracker.removeEntity(a);
        expect(ids(far.drain()).equals(List.of("77")), "lifecycle: remove while paired");
        expect(near.drain().isEmpty(), "lifecycle: remove sent to non-viewer");
        expect(tracker.seenBy(1).isEmpty() && !tracker.isTracked(1), "lifecycle: residue after remove");
        expect(liveView.isEmpty(), "lifecycle: removed entity still holds its seen-by set " + liveView.size());
        tracker.tick();
        tracker.removeEntity(a);
        expect(far.drain().isEmpty() && near.drain().isEmpty(), "lifecycle: packets after remove");

        // Remove a player while tracking.
        KestrelEntity b = new KestrelEntity(2, new UUID(9, 2), EntityTypes.INTERACTION);
        b.setPos(6, 64, 6);
        tracker.addEntity(b);
        expect(ids(near.drain()).equals(List.of("0", "1", "0")), "lifecycle: second entity pairs");
        tracker.removePlayer(near);
        expect(ids(near.drain()).equals(List.of("77")), "lifecycle: leaving player told once");
        expect(tracker.seenBy(2).isEmpty(), "lifecycle: seen-by keeps a departed player");
        b.setPos(7, 64, 7);
        for (int i = 0; i < 10; i++) {
            tracker.tick();
        }
        tracker.movePlayer(far);
        expect(near.drain().isEmpty(), "lifecycle: departed player still receives");
        tracker.removePlayer(near);
        expect(near.drain().isEmpty(), "lifecycle: double player removal sent packets");

        // Last removals: nothing held.
        tracker.removeEntity(b);
        tracker.removePlayer(far);
        expect(tracker.trackedCount() == 0 && tracker.playerCount() == 0,
                "lifecycle: tracker holds " + tracker.trackedCount() + " entities, " + tracker.playerCount() + " players");
        // Untrackable types (range 0) are never held.
        KestrelEntity marker = new KestrelEntity(3, new UUID(9, 3), EntityTypes.MARKER);
        tracker.addEntity(marker);
        expect(!tracker.isTracked(3), "lifecycle: a range-0 entity was tracked");
        System.out.println("  lifecycle: pair, unpair, re-pair, remove while paired, player leave, residue");
    }

    /** The live TickLoop drives the tracker once per tick (the slot is ServerChunkCache.tick's). */
    private static void checkTickSlot() {
        dev.kestrel.loop.TickLoop loop = new dev.kestrel.loop.TickLoop(new dev.kestrel.loop.SectionStore(-64, 384));
        EntityTracker tracker = new EntityTracker(100);
        loop.setEntityTracker(tracker);
        KestrelEntity e = new KestrelEntity(1, new UUID(8, 1), EntityTypes.INTERACTION);
        e.setPos(0.5, 64, 0.5);
        Capture c = new Capture(100, 2, 64, 2, 10);
        tracker.addPlayer(c);
        tracker.addEntity(e);
        c.drain();
        for (int i = 0; i < 61; i++) {
            loop.tick();
        }
        expect(ids(c.drain()).equals(List.of("53", "53")), "tick slot: TickLoop did not drive the tracker once per tick");
        System.out.println("  tick slot: TickLoop.tick drives the tracker once per tick");
    }

    private static List<String> ids(List<String> log) {
        List<String> out = new ArrayList<>();
        for (String s : log) {
            out.add(Integer.toString(idOf(s)));
        }
        return out;
    }
}
