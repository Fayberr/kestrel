package dev.kestrel.entity;

import dev.kestrel.collision.ShapeBoxCache;
import dev.kestrel.loop.SectionStore;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.net.KestrelPlayer;
import dev.kestrel.net.Outbound;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.TickPriority;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * S14 slice 3 gate: the entity tick phase, the shared id minter, Kestrel's
 * item entity and its packets, against vanilla 26.2.
 *
 * <p>The oracle is a real vanilla {@link ItemEntity}, ticked by vanilla's
 * own {@code ServerLevel.tickNonPassenger}, living in an {@link OracleLevel}:
 * a {@code ServerLevel} subclass allocated with {@code Unsafe.allocateInstance}
 * (no constructor runs), as slice 2's check does, that answers the calls
 * an item's tick makes. It serves block and fluid states from the same
 * {@link SectionStore} the Kestrel side collides with, a network id, no
 * entities (so no entity colliders and nothing to merge with), vanilla's
 * default world border, no-op game events and a bare damage-source table
 * (bed and hay {@code fallOn} ask for one; an item takes no fall damage).
 * {@code findSupportingBlock} is not overridden: the oracle runs vanilla's
 * real one.
 *
 * <ol>
 *   <li>Constants and layout: lifetime, gravity (from the class that
 *       declares it), air drag, pickup-delay sentinels, the stack's data id
 *       and serializer, read from vanilla.</li>
 *   <li>Physics differential, every tick, in raw bits, no epsilon:
 *       position, motion, xo/yo/zo, onGround, the supporting block, the
 *       collision and noPhysics flags, needsSync, syncPosition, age, pickup
 *       delay, tick count, yaw and removal. Named scenarios (flat drop,
 *       settling then provably at rest, stairs and slabs, sliding along a
 *       wall, inside a solid block, chunk boundaries, falling out of the
 *       world, slime and bed bounces, ice, soul sand, fences, ceilings,
 *       NoGravity, an empty stack) plus seeded random terrain runs, all at
 *       least 400 ticks.</li>
 *   <li>Lifetime boundary: age LIFETIME-1, LIFETIME and LIFETIME+1, and a
 *       full 6000-tick life.</li>
 *   <li>The tick slot, by observed effects across one real
 *       {@link TickLoop} tick.</li>
 *   <li>The entity pass rules: emptyTime, ticking range, checkDespawn,
 *       vehicle gating, removal and addition during the pass, the crash
 *       guard.</li>
 *   <li>Identity: one minter, interleaved players and items, wrap and
 *       live-id skipping, and the join packet carrying the player's id.</li>
 *   <li>Packets: the pairing bundle of an item entity, stack included, and
 *       the full tick-by-tick packet stream of a falling item, byte for
 *       byte against vanilla's {@link ServerEntity}, encoded through the
 *       bound 26.2 play protocol with vanilla's bundle splitting.</li>
 * </ol>
 * Fixed seeds. Exit 0 on PASS, 1 otherwise.
 */
public final class ItemEntityTickCheck {
    private ItemEntityTickCheck() {
    }

    private static final long SEED = 0x1731_4E_5L;
    private static final List<String> failures = new ArrayList<>();
    private static long comparisons;
    private static RegistryAccess access;
    private static ProtocolInfo<ClientGamePacketListener> protocol;

    public static void main(String[] args) throws Exception {
        initializePacketCodec();
        // Items get vanilla's default prototype, as ItemStackCodecCheck binds
        // them (no datapack context here). Both sides share the stacks, so
        // the comparison does not depend on which prototype it is.
        for (net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) {
            item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        }

        checkConstants();
        checkScenarios();
        checkRandomTerrain();
        checkLifetime();
        checkTickSlot();
        checkPassRules();
        checkIdentity();
        checkPairing();
        checkPacketStream();

        if (!failures.isEmpty()) {
            System.out.println("ItemEntityTickCheck FAILED with " + failures.size() + " failure(s):");
            for (int i = 0; i < Math.min(failures.size(), 40); i++) {
                System.out.println("  - " + failures.get(i));
            }
            System.exit(1);
        }
        System.out.println("  " + comparisons + " comparisons against vanilla or the reference");
        System.out.println("ItemEntityTickCheck: PASS");
    }

    static void initializePacketCodec() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access));
    }

    private static boolean expect(boolean ok, String msg) {
        comparisons++;
        if (!ok) {
            failures.add(msg);
        }
        return ok;
    }

    // =====================================================================
    // Vanilla oracle
    // =====================================================================

    /** A ServerLevel with no constructor run; see the class comment. */
    static final class OracleLevel extends ServerLevel {
        private int nextEntityId;
        private BlockGetter blocks;
        private WorldBorder border;
        private DamageSources damage;
        private net.minecraft.server.MinecraftServer server;

        private OracleLevel() {
            super(null, Runnable::run, null, null, Level.OVERWORLD, null, false, 0L, List.of(), false);
            throw new AssertionError("constructor must not run");
        }

        static OracleLevel create(BlockGetter blocks) {
            try {
                OracleLevel level = (OracleLevel) unsafe().allocateInstance(OracleLevel.class);
                level.blocks = blocks;
                level.border = new WorldBorder();
                level.damage = (DamageSources) unsafe().allocateInstance(DamageSources.class);
                // Entity.checkInsideBlocks asks the server whether anyone
                // subscribed to block-intersection debug events: nobody has.
                net.minecraft.server.MinecraftServer server = (net.minecraft.server.MinecraftServer)
                        unsafe().allocateInstance(net.minecraft.server.dedicated.DedicatedServer.class);
                Field subscribers = net.minecraft.server.MinecraftServer.class.getDeclaredField("debugSubscribers");
                unsafe().putObject(server, unsafe().objectFieldOffset(subscribers),
                        new net.minecraft.util.debug.ServerDebugSubscribers(null));
                level.server = server;
                return level;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The id the next vanilla entity takes: the gate hands it Kestrel's. */
        void nextId(int id) {
            this.nextEntityId = id;
        }

        @Override
        public int getNextEntityId() {
            return nextEntityId;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return blocks.getFluidState(pos);
        }

        /**
         * The fluid scan ({@code EntityFluidInteraction.hasFluidAndLoaded})
         * asks for chunks; no scenario holds a fluid, so "not loaded" gives
         * the answer a loaded, fluid-free chunk gives: no fluid.
         */
        @Override
        public net.minecraft.world.level.chunk.@Nullable ChunkAccess getChunk(int chunkX, int chunkZ,
                net.minecraft.world.level.chunk.status.ChunkStatus status, boolean loadOrGenerate) {
            return null;
        }

        @Override
        public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
            return blocks;
        }

        @Override
        public WorldBorder getWorldBorder() {
            return border;
        }

        @Override
        public int getMinY() {
            return blocks.getMinY();
        }

        @Override
        public int getHeight() {
            return blocks.getHeight();
        }

        /** No entities: nothing to collide with (entity colliders) or merge with. */
        @Override
        public List<Entity> getEntities(@Nullable Entity except, AABB bb, Predicate<? super Entity> selector) {
            return new ArrayList<>();
        }

        @Override
        public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB bb,
                Predicate<? super T> selector) {
            return new ArrayList<>();
        }

        @Override
        public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) {
        }

        /** Clear weather: rain only puts fires out, and fire is not modelled. */
        @Override
        public boolean isRainingAt(BlockPos pos) {
            return false;
        }

        @Override
        public net.minecraft.server.MinecraftServer getServer() {
            return server;
        }

        @Override
        public DamageSources damageSources() {
            return damage;
        }
    }

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    /** A vanilla BlockGetter over a SectionStore: states, fluids, bounds. */
    static final class StoreGetter implements BlockGetter {
        final SectionStore store;

        StoreGetter(SectionStore store) {
            this.store = store;
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return store.get(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return store.getHeight();
        }

        @Override
        public int getMinY() {
            return store.getMinY();
        }
    }

    /** One world both sides share: the store, Kestrel's entity world over it, and the oracle over it. */
    static final class Arena {
        final SectionStore store = new SectionStore(-64, 384);
        final StoreGetter getter = new StoreGetter(store);
        final ShapeBoxCache cache = new ShapeBoxCache();
        final EntityLevel level = new EntityLevel() {
            @Override
            public BlockGetter blocks() {
                return getter;
            }

            @Override
            public ShapeBoxCache shapeCache() {
                return cache;
            }

            @Override
            public int getMinY() {
                return store.getMinY();
            }

            // No scenario here writes a block, raises an event or rolls the
            // level random: each says so loudly if one ever does.
            @Override
            public net.minecraft.world.level.LevelReader levelReader() {
                throw new UnsupportedOperationException("item arena has no level reader");
            }

            @Override
            public boolean setBlock(BlockPos pos, BlockState state, int flags) {
                throw new UnsupportedOperationException("item arena never writes blocks");
            }

            @Override
            public void levelEvent(int type, BlockPos pos, int data) {
                throw new UnsupportedOperationException("item arena raises no level events");
            }

            @Override
            public void landingBlockUpdate(KestrelEntity entity, BlockPos pos, BlockState state) {
                throw new UnsupportedOperationException("item arena has no falling blocks");
            }

            @Override
            public RandomSource random() {
                throw new UnsupportedOperationException("item arena has no level random");
            }

            @Override
            public net.minecraft.world.level.gamerules.GameRules gameRules() {
                throw new UnsupportedOperationException("item arena has no game rules");
            }

            @Override
            public void playSound(double x, double y, double z,
                    net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound,
                    net.minecraft.sounds.SoundSource source, float volume, float pitch) {
                throw new UnsupportedOperationException("item arena plays no sounds");
            }

            @Override
            public void entityEvent(KestrelEntity entity, byte event) {
                throw new UnsupportedOperationException("item arena raises no entity events");
            }
        };
        final EntityWorld world = new EntityWorld(level, null);
        final OracleLevel oracle = OracleLevel.create(getter);

        void set(int x, int y, int z, BlockState state) {
            store.set(new BlockPos(x, y, z), state);
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z, state);
                    }
                }
            }
        }
    }

    /** One item on both sides. */
    static final class Twin {
        final KestrelItemEntity k;
        final ItemEntity v;

        Twin(KestrelItemEntity k, ItemEntity v) {
            this.k = k;
            this.v = v;
        }
    }

    /**
     * Spawn through Kestrel's spawn entry point, then build vanilla's twin
     * with the same id, UUID and yaw, and seed both randoms alike.
     */
    static Twin spawn(Arena a, double x, double y, double z, ItemStack stack,
            double dx, double dy, double dz, long seed) {
        KestrelItemEntity k = a.world.spawnItem(x, y, z, stack.copy(), dx, dy, dz);
        if (k == null) {
            throw new IllegalStateException("spawn refused");
        }
        a.oracle.nextId(k.getId());
        ItemEntity v = new ItemEntity(a.oracle, x, y, z, stack.copy(), dx, dy, dz);
        v.setUUID(k.getUUID());
        v.setYRot(k.getYRot());
        k.getRandom().setSeed(seed);
        v.getRandom().setSeed(seed);
        return new Twin(k, v);
    }

    /** Vanilla's side of one pass for one entity: ServerLevel.java:430-446 with nothing riding. */
    static void tickVanilla(Arena a, ItemEntity v) {
        tickVanilla(a.oracle, v);
    }

    static void tickVanilla(OracleLevel oracle, ItemEntity v) {
        if (!v.isRemoved()) {
            v.checkDespawn();
            oracle.tickNonPassenger(v);
        }
    }

    // ---- reflection into vanilla's private state ----

    private static final Field PICKUP_DELAY = field(ItemEntity.class, "pickupDelay");
    private static final Field AGE = field(ItemEntity.class, "age");
    private static final Field ON_GROUND_NO_BLOCKS = field(Entity.class, "onGroundNoBlocks");

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object get(Field f, Object target) {
        try {
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Field f, Object target, Object value) {
        try {
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Field K_ON_GROUND_NO_BLOCKS = field(KestrelMovingEntity.class, "onGroundNoBlocks");

    /**
     * Compare every tracked field, bit for bit. Returns false on the first
     * difference (reported once per scenario by the caller).
     */
    static boolean same(Twin t, String where) {
        KestrelItemEntity k = t.k;
        ItemEntity v = t.v;
        List<String> diffs = new ArrayList<>();
        bits(diffs, "x", k.position().x, v.getX());
        bits(diffs, "y", k.position().y, v.getY());
        bits(diffs, "z", k.position().z, v.getZ());
        bits(diffs, "dx", k.getDeltaMovement().x, v.getDeltaMovement().x);
        bits(diffs, "dy", k.getDeltaMovement().y, v.getDeltaMovement().y);
        bits(diffs, "dz", k.getDeltaMovement().z, v.getDeltaMovement().z);
        bits(diffs, "xo", k.xo, v.xo);
        bits(diffs, "yo", k.yo, v.yo);
        bits(diffs, "zo", k.zo, v.zo);
        bits(diffs, "yRot", k.getYRot(), v.getYRot());
        bits(diffs, "fallDistance", k.getFallDistance(), v.fallDistance);
        eq(diffs, "onGround", k.onGround(), v.onGround());
        eq(diffs, "support", k.mainSupportingBlockPos(), v.mainSupportingBlockPos);
        eq(diffs, "onGroundNoBlocks", get(K_ON_GROUND_NO_BLOCKS, k), get(ON_GROUND_NO_BLOCKS, v));
        eq(diffs, "horizontalCollision", k.horizontalCollision(), v.horizontalCollision);
        eq(diffs, "noPhysics", k.isNoPhysics(), v.noPhysics);
        eq(diffs, "needsSync", k.needsSync, v.needsSync);
        eq(diffs, "syncPosition", k.syncPosition, v.syncPosition);
        eq(diffs, "age", k.getAge(), v.getAge());
        eq(diffs, "pickupDelay", k.getPickupDelay(), get(PICKUP_DELAY, v));
        eq(diffs, "tickCount", k.tickCount, v.tickCount);
        eq(diffs, "removed", k.isRemoved(), v.isRemoved());
        eq(diffs, "bbox", k.getBoundingBox(), v.getBoundingBox());
        comparisons += 23;
        if (!diffs.isEmpty()) {
            failures.add(where + ": " + String.join(", ", diffs));
            return false;
        }
        return true;
    }

    private static void bits(List<String> diffs, String name, double k, double v) {
        if (Double.doubleToRawLongBits(k) != Double.doubleToRawLongBits(v)) {
            diffs.add(name + " kestrel=" + k + " vanilla=" + v);
        }
    }

    private static void bits(List<String> diffs, String name, float k, float v) {
        if (Float.floatToRawIntBits(k) != Float.floatToRawIntBits(v)) {
            diffs.add(name + " kestrel=" + k + " vanilla=" + v);
        }
    }

    private static void eq(List<String> diffs, String name, Object k, Object v) {
        if (!java.util.Objects.equals(k, v)) {
            diffs.add(name + " kestrel=" + k + " vanilla=" + v);
        }
    }

    /**
     * Run a twin for up to {@code ticks} ticks, comparing after every one.
     * Kestrel's side is the real entity pass ({@link EntityWorld#tickEntities}).
     * Stops at the first mismatch or once both are removed.
     *
     * @return the tick on which both sides removed the entity, or -1
     */
    static int run(Arena a, Twin t, int ticks, String name, @Nullable Consumer<Integer> perTick) {
        if (!same(t, name + " spawn")) {
            return -1;
        }
        for (int i = 1; i <= ticks; i++) {
            a.world.tickEntities();
            tickVanilla(a, t.v);
            if (!same(t, name + " tick " + i)) {
                return -1;
            }
            if (perTick != null) {
                perTick.accept(i);
            }
            if (t.k.isRemoved()) {
                return i;
            }
        }
        return -1;
    }

    // =====================================================================
    // 1. Constants and layout
    // =====================================================================

    private static void checkConstants() throws Exception {
        Field lifetime = field(ItemEntity.class, "LIFETIME");
        expect(KestrelItemEntity.LIFETIME == lifetime.getInt(null), "LIFETIME differs from vanilla");
        expect(KestrelItemEntity.INFINITE_PICKUP_DELAY == field(ItemEntity.class, "INFINITE_PICKUP_DELAY").getInt(null),
                "INFINITE_PICKUP_DELAY differs");
        expect(KestrelItemEntity.INFINITE_LIFETIME == field(ItemEntity.class, "INFINITE_LIFETIME").getInt(null),
                "INFINITE_LIFETIME differs");

        Arena a = new Arena();
        Twin t = spawn(a, 0.5, 100.0, 0.5, new ItemStack(Items.STONE), 0.0, 0.0, 0.0, 1L);
        // Gravity: ItemEntity.getDefaultGravity declares it; Entity.getGravity consumes it.
        Method declared = ItemEntity.class.getDeclaredMethod("getDefaultGravity");
        declared.setAccessible(true);
        expect(Double.doubleToRawLongBits((double) declared.invoke(t.v))
                        == Double.doubleToRawLongBits(KestrelItemEntity.DEFAULT_GRAVITY),
                "gravity differs from ItemEntity.getDefaultGravity");
        expect(Double.doubleToRawLongBits(t.v.getGravity()) == Double.doubleToRawLongBits(t.k.getGravity()),
                "getGravity differs");
        Method drag = Entity.class.getDeclaredMethod("getAirDrag");
        drag.setAccessible(true);
        expect(Float.floatToRawIntBits((float) drag.invoke(t.v)) == Float.floatToRawIntBits(KestrelItemEntity.AIR_DRAG),
                "air drag differs from Entity.getAirDrag");
        t.v.setNoGravity(true);
        t.k.setNoGravity(true);
        expect(t.v.getGravity() == t.k.getGravity(), "NoGravity gravity differs");

        // The stack's synched data: same id, same serializer.
        Field dataItem = field(ItemEntity.class, "DATA_ITEM");
        EntityDataAccessor<?> vanillaItem = (EntityDataAccessor<?>) dataItem.get(null);
        EntityDataAccessor<?> kestrelItem = (EntityDataAccessor<?>) field(KestrelItemEntity.class, "DATA_ITEM").get(null);
        expect(vanillaItem.id() == kestrelItem.id(), "DATA_ITEM id differs: " + kestrelItem.id() + " vs " + vanillaItem.id());
        expect(vanillaItem.serializer() == kestrelItem.serializer(), "DATA_ITEM serializer differs");
        // Dimensions and type.
        expect(t.k.getType() == t.v.getType(), "entity type differs");
        System.out.println("  constants: lifetime, gravity, drag, sentinels and the stack's data slot match vanilla");
    }

    // =====================================================================
    // 2. Named physics scenarios
    // =====================================================================


    /** Block constants, loaded on first use (after the bootstrap in main). */
    static final class B {
        private B() {
        }

        static final BlockState STONE = Blocks.STONE.defaultBlockState();
        static final Block[] FLOORS = {Blocks.STONE, Blocks.GRASS_BLOCK, Blocks.ICE, Blocks.PACKED_ICE,
                Blocks.BLUE_ICE, Blocks.SOUL_SAND, Blocks.SLIME_BLOCK, Blocks.HAY_BLOCK, Blocks.MUD, Blocks.SAND,
                Blocks.SOUL_SOIL, Blocks.GLASS};
        static final Block[] SHAPES = {Blocks.OAK_STAIRS, Blocks.STONE_SLAB, Blocks.OAK_FENCE,
                Blocks.COBBLESTONE_WALL, Blocks.OAK_FENCE_GATE, Blocks.GLASS_PANE, Blocks.IRON_BARS, Blocks.IRON_CHAIN,
                Blocks.LANTERN, Blocks.ANVIL, Blocks.OAK_TRAPDOOR, Blocks.OAK_DOOR, Blocks.CARPET.asList().get(0), Blocks.SNOW,
                Blocks.SCAFFOLDING, Blocks.LADDER, Blocks.END_ROD, Blocks.BED.asList().get(14), Blocks.CHEST, Blocks.ENCHANTING_TABLE,
                Blocks.DAYLIGHT_DETECTOR, Blocks.COMPOSTER, Blocks.BREWING_STAND, Blocks.GRINDSTONE, Blocks.STONECUTTER,
                Blocks.CAKE, Blocks.LECTERN, Blocks.BELL, Blocks.FLOWER_POT, Blocks.CANDLE, Blocks.SEA_PICKLE,
                Blocks.STONE, Blocks.SLIME_BLOCK, Blocks.ICE};
    }

    /** Flat stone floor, top face at y=64, over [-12, 28] on both axes. */
    static Arena flat() {
        Arena a = new Arena();
        a.fill(-12, 63, -12, 28, 63, 28, B.STONE);
        return a;
    }

    private static int scenarioCount;

    private static void scenario(String name, Arena a, double x, double y, double z, ItemStack stack,
            double dx, double dy, double dz, int ticks, @Nullable Consumer<Twin> init,
            @Nullable Predicate<Twin> after) {
        Twin t = spawn(a, x, y, z, stack, dx, dy, dz, SEED + scenarioCount);
        scenarioCount++;
        if (init != null) {
            init.accept(t);
        }
        run(a, t, ticks, name, null);
        if (after != null) {
            expect(after.test(t), name + ": post-condition failed");
        }
    }

    private static void checkScenarios() {
        long before = comparisons;
        ItemStack diamond = new ItemStack(Items.DIAMOND);

        // Drop from height onto flat ground, then it must be provably at rest.
        {
            Arena a = flat();
            Twin t = spawn(a, 3.5, 80.0, 3.5, diamond, 0.0, 0.0, 0.0, SEED);
            long[] restBits = new long[3];
            int[] restSince = {-1};
            boolean[] moved = {false};
            run(a, t, 600, "flat drop", i -> {
                long[] now = {Double.doubleToRawLongBits(t.k.position().x),
                        Double.doubleToRawLongBits(t.k.position().y),
                        Double.doubleToRawLongBits(t.k.position().z)};
                if (i == 200) {
                    System.arraycopy(now, 0, restBits, 0, 3);
                    restSince[0] = i;
                } else if (i > 200 && !java.util.Arrays.equals(now, restBits)) {
                    moved[0] = true;
                }
            });
            expect(t.k.onGround() && !moved[0] && restSince[0] == 200,
                    "flat drop: the item did not come to rest by tick 200 and stay there for 400 ticks");
            expect(t.k.position().y == 64.0, "flat drop: resting height is not the floor top (64.0): " + t.k.position().y);
        }

        // Landing with horizontal speed: slides, friction, settles.
        scenario("slide to rest", flat(), 0.5, 64.5, 0.5, diamond, 0.35, 0.1, 0.21, 600, null,
                t -> t.k.onGround() && t.k.getDeltaMovement().horizontalDistanceSqr() < 1.0E-10);

        // The random initial motion vanilla gives a drop (spawnItem without a velocity).
        {
            Arena a = flat();
            KestrelItemEntity k = a.world.spawnItem(2.5, 70.0, 2.5, diamond.copy());
            expect(k != null && k.getDeltaMovement().y == 0.2
                            && Math.abs(k.getDeltaMovement().x) <= 0.1 && Math.abs(k.getDeltaMovement().z) <= 0.1,
                    "random drop motion is not vanilla's (x,z in [-0.1,0.1), y 0.2)");
        }

        // Stairs and slabs, every orientation, dropped from several offsets.
        {
            List<BlockState> shapes = new ArrayList<>();
            shapes.addAll(Blocks.OAK_STAIRS.getStateDefinition().getPossibleStates());
            shapes.addAll(Blocks.STONE_SLAB.getStateDefinition().getPossibleStates());
            int n = 0;
            for (BlockState s : shapes) {
                if (!s.getFluidState().isEmpty()) {
                    continue;
                }
                Arena a = flat();
                a.set(4, 64, 4, s);
                a.set(5, 64, 4, s);
                double ox = 4.1 + (n % 7) * 0.13;
                double oz = 4.05 + (n % 5) * 0.19;
                scenario("stairs/slab " + s, a, ox, 67.0, oz, diamond, 0.02 * ((n % 3) - 1), 0.0,
                        0.03 * ((n % 4) - 1.5), 420, null, null);
                n++;
            }
        }

        // Against a wall: diagonal motion into a wall slides along it.
        {
            Arena a = flat();
            a.fill(8, 64, -4, 8, 67, 12, B.STONE);
            scenario("wall slide +x", a, 6.5, 64.5, 1.5, diamond, 0.4, 0.15, 0.25, 500, null,
                    t -> t.k.position().x <= 8.0 - 0.125);
            Arena b = flat();
            b.fill(-4, 64, 8, 12, 67, 8, B.STONE);
            scenario("wall slide +z", b, 1.5, 64.2, 6.8, diamond, -0.3, 0.2, 0.5, 500, null, null);
            Arena c = flat();
            c.fill(2, 64, 2, 2, 66, 2, B.STONE);
            scenario("corner hit", c, 1.2, 64.0, 1.2, diamond, 0.3, 0.3, 0.3, 500, null, null);
        }

        // Inside a solid block: noPhysics and the push toward the nearest free face.
        {
            Arena a = flat();
            a.fill(0, 64, 0, 3, 66, 3, B.STONE);
            scenario("inside solid", a, 1.5, 64.3, 1.5, diamond, 0.0, 0.0, 0.0, 500, null,
                    t -> !t.k.isNoPhysics());
            Arena b = flat();
            b.fill(0, 64, 0, 3, 64, 3, B.STONE);
            scenario("inside solid near edge", b, 0.1, 64.4, 2.7, diamond, 0.05, 0.0, -0.02, 500, null, null);
            Arena c = flat();
            scenario("inside the floor", c, 5.5, 63.5, 5.5, diamond, 0.0, 0.0, 0.0, 500, null, null);
        }

        // Exact ties in the closest-free-face search: one free block around a
        // solid cell, the item's box centred on the cell, so several faces are
        // equally near and vanilla's N, S, W, E, UP order decides. Then every
        // subset of free faces, and positions on an eighth-block grid.
        {
            int n = 0;
            for (int mask = 0; mask < 32; mask++) {
                Arena a = flat();
                a.fill(0, 64, 0, 6, 66, 6, B.STONE);
                int[][] faces = {{3, 64, 2}, {3, 64, 4}, {2, 64, 3}, {4, 64, 3}, {3, 65, 3}};
                for (int f = 0; f < 5; f++) {
                    if ((mask & 1 << f) != 0) {
                        a.set(faces[f][0], faces[f][1], faces[f][2], Blocks.AIR.defaultBlockState());
                    }
                }
                double ox = 3.0 + (n % 3 == 0 ? 0.5 : (n % 8) / 8.0);
                double oz = 3.0 + (n % 3 == 0 ? 0.5 : ((n * 3) % 8) / 8.0);
                // Box centre y = y + 0.125; 64.375 puts it at the cell's middle.
                double oy = n % 3 == 0 ? 64.375 : 64.0 + ((n * 5) % 7) / 8.0;
                scenario("closest face tie mask " + mask, a, ox, oy, oz, diamond, 0.0, 0.0, 0.0, 400, null, null);
                n++;
            }
        }

        // A block placed into a resting item: noPhysics while onGround is still
        // true from the last real move, the one path that reaches ItemEntity's
        // "on the ground and falling: y times -0.5" branch.
        {
            int n = 0;
            for (BlockState placed : new BlockState[]{B.STONE, Blocks.OAK_PLANKS.defaultBlockState(),
                    Blocks.STONE_SLAB.defaultBlockState(), Blocks.GLASS.defaultBlockState()}) {
                for (int open = 0; open < 3; open++) {
                    Arena a = flat();
                    if (open > 0) {
                        // Walls on some sides so the push goes up or sideways.
                        a.set(2, 64, 3, B.STONE);
                        a.set(4, 64, 3, B.STONE);
                    }
                    if (open > 1) {
                        a.set(3, 64, 2, B.STONE);
                        a.set(3, 65, 3, B.STONE);
                    }
                    Twin t = spawn(a, 3.3 + 0.05 * (n % 8), 64.5, 3.6, diamond, 0.0, 0.0, 0.0, SEED + 500 + n);
                    boolean[] fellOnGround = {false};
                    run(a, t, 500, "buried " + placed + " open " + open, i -> {
                        if (i == 60) {
                            a.set(3, 64, 3, placed);
                        }
                        if (t.k.isNoPhysics() && t.k.onGround()) {
                            fellOnGround[0] = true;
                        }
                    });
                    expect(fellOnGround[0], "buried " + placed + ": never took the noPhysics-on-ground path");
                    n++;
                }
            }
        }

        // Negative zero on the chunk boundary: -0.0 + -0.0 stays -0.0, while
        // vanilla's collision result writes +0.0 on an unmoved axis.
        scenario("negative zero boundary", flat(), -0.0, 64.0, -0.0, diamond, -0.0, 0.0, -0.0, 420, null,
                t -> Double.doubleToRawLongBits(t.k.position().x) == Double.doubleToRawLongBits(t.v.getX()));
        scenario("negative zero boundary, dropped", flat(), -0.0, 66.5, -0.0, diamond, -0.0, 0.1, -0.0, 420, null,
                null);
        // Gravity's add(0, -g, 0) turns a -0.0 motion component into +0.0, so
        // only a NoGravity item carries -0.0 into the collision resolution.
        scenario("negative zero boundary, no gravity", flat(), -0.0, 64.4, -0.0, diamond, -0.0, -0.05, -0.0, 420,
                t -> {
                    t.k.setNoGravity(true);
                    t.v.setNoGravity(true);
                },
                t -> Double.doubleToRawLongBits(t.k.position().x) == Double.doubleToRawLongBits(t.v.getX()));

        // Exactly on chunk boundaries, positive and negative.
        scenario("chunk boundary x=16", flat(), 16.0, 66.0, 7.0, diamond, 0.0, 0.0, 0.0, 420, null, null);
        scenario("chunk boundary x=0 z=0", flat(), 0.0, 66.0, 0.0, diamond, -0.1, 0.0, -0.1, 420, null, null);
        scenario("chunk boundary x=-16", flat(), -16.0, 66.0, -16.0, diamond, 0.05, 0.1, 0.05, 420, null, null);

        // A fall with no ground under it: out of the world, discarded below minY - 64.
        {
            Arena a = new Arena();
            Twin t = spawn(a, 0.5, 70.0, 0.5, diamond, 0.0, 0.0, 0.0, SEED + 77);
            int removedAt = run(a, t, 2000, "void fall", null);
            expect(removedAt > 0 && t.k.getRemovalReason() == Entity.RemovalReason.DISCARDED,
                    "void fall: not discarded below the world");
            expect(t.k.yo >= a.store.getMinY() - 64 || t.k.position().y < a.store.getMinY() - 64,
                    "void fall: discarded at the wrong height");
        }

        // Bounces: slime and bed (restitution), hay (fallOn), and the slime stepOn damping.
        {
            Arena a = flat();
            a.fill(0, 63, 0, 6, 63, 6, Blocks.SLIME_BLOCK.defaultBlockState());
            boolean[] bounced = {false};
            Twin t = spawn(a, 3.5, 75.0, 3.5, diamond, 0.05, 0.0, 0.03, SEED + 3);
            run(a, t, 600, "slime bounce", i -> {
                if (t.k.getDeltaMovement().y > 0.05 && t.k.position().y < 66.0) {
                    bounced[0] = true;
                }
            });
            expect(bounced[0], "slime bounce: the item never bounced");
            Arena b = flat();
            for (BlockState bed : Blocks.BED.asList().get(14).getStateDefinition().getPossibleStates()) {
                b.set(3, 64, 3, bed);
                break;
            }
            scenario("bed bounce", b, 3.5, 72.0, 3.5, diamond, 0.0, 0.0, 0.0, 500, null, null);
            Arena c = flat();
            c.set(3, 64, 3, Blocks.HAY_BLOCK.defaultBlockState());
            scenario("hay landing", c, 3.5, 72.0, 3.5, diamond, 0.0, 0.0, 0.0, 450, null, null);
            Arena d = flat();
            d.set(3, 64, 3, Blocks.POWDER_SNOW.defaultBlockState());
            // Lands on FALLING_COLLISION_SHAPE on tick 15 (fall distance above 2.5). From tick 16
            // vanilla's PowderSnowBlock.entityInside (G68) makes it stuck, so the run stops there.
            scenario("powder snow from 4", d, 3.5, 69.0, 3.5, diamond, 0.0, 0.0, 0.0, 15, null,
                    landed -> landed.k.onGround() && landed.k.position().y > 64.5);
        }

        // Friction and speed factor blocks.
        {
            for (Block floor : new Block[]{Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SOUL_SAND,
                    Blocks.SLIME_BLOCK, Blocks.MUD, Blocks.SOUL_SOIL}) {
                Arena a = new Arena();
                a.fill(-12, 63, -12, 40, 63, 28, floor.defaultBlockState());
                scenario("floor " + BuiltInRegistries.BLOCK.getKey(floor), a, 0.5, 64.5, 0.5, diamond,
                        0.4, 0.05, 0.1, 600, null, null);
            }
        }

        // Fences, walls and gates: the supporting block and getOnPos special cases.
        {
            Arena a = flat();
            a.set(3, 64, 3, Blocks.OAK_FENCE.defaultBlockState());
            scenario("fence top", a, 3.5, 68.0, 3.5, diamond, 0.0, 0.0, 0.0, 450, null, null);
            Arena b = flat();
            b.set(3, 64, 3, Blocks.COBBLESTONE_WALL.defaultBlockState());
            scenario("wall top", b, 3.5, 68.0, 3.5, diamond, 0.01, 0.0, 0.0, 450, null, null);
            Arena c = flat();
            c.set(3, 64, 3, Blocks.OAK_FENCE_GATE.defaultBlockState());
            scenario("gate top", c, 3.5, 68.0, 3.5, diamond, 0.0, 0.0, 0.01, 450, null, null);
            Arena d = flat();
            d.set(3, 64, 3, Blocks.ICE.defaultBlockState());
            d.set(4, 64, 3, Blocks.STONE.defaultBlockState());
            scenario("block edge", d, 3.98, 66.0, 3.5, diamond, 0.02, 0.0, 0.0, 450, null, null);
        }

        // A ceiling: thrown up into it.
        {
            Arena a = flat();
            a.fill(-4, 67, -4, 8, 67, 8, B.STONE);
            scenario("ceiling", a, 2.5, 64.0, 2.5, diamond, 0.1, 0.6, 0.0, 450, null, null);
        }

        // NoGravity, pickup delays, an empty stack.
        scenario("no gravity", flat(), 2.5, 70.0, 2.5, diamond, 0.1, -0.05, 0.02, 450,
                t -> {
                    t.k.setNoGravity(true);
                    t.v.setNoGravity(true);
                }, null);
        scenario("pickup delay", flat(), 2.5, 66.0, 2.5, diamond, 0.0, 0.0, 0.0, 420,
                t -> {
                    t.k.setDefaultPickUpDelay();
                    t.v.setDefaultPickUpDelay();
                }, t -> t.k.getPickupDelay() == 0);
        scenario("never pickup", flat(), 2.5, 66.0, 2.5, diamond, 0.0, 0.0, 0.0, 420,
                t -> {
                    t.k.setPickUpDelay(KestrelItemEntity.INFINITE_PICKUP_DELAY);
                    t.v.setNeverPickUp();
                }, t -> t.k.getPickupDelay() == KestrelItemEntity.INFINITE_PICKUP_DELAY);
        {
            Arena a = flat();
            Twin t = spawn(a, 2.5, 66.0, 2.5, ItemStack.EMPTY, 0.0, 0.0, 0.0, SEED + 9);
            int removedAt = run(a, t, 5, "empty stack", null);
            expect(removedAt == 1, "empty stack: not discarded on its first tick");
            expect(!a.world.isTicking(t.k.getId()) && a.world.getEntity(t.k.getId()) == null,
                    "empty stack: removal did not reach the tick list and storage");
        }

        System.out.println("  scenarios: " + scenarioCount + " named runs, " + (comparisons - before)
                + " comparisons, raw bits, every tick");
    }

    // =====================================================================
    // 2b. Seeded random terrain
    // =====================================================================

    private static void checkRandomTerrain() {
        long before = comparisons;
        Random rng = new Random(SEED ^ 0xBEEF);
        int runs = 260;
        int landed = 0;
        for (int r = 0; r < runs; r++) {
            Arena a = new Arena();
            Block floor = B.FLOORS[rng.nextInt(B.FLOORS.length)];
            a.fill(-10, 63, -10, 26, 63, 26, floor.defaultBlockState());
            for (int x = -2; x < 18; x++) {
                for (int z = -2; z < 18; z++) {
                    if (rng.nextInt(4) == 0) {
                        Block b = B.SHAPES[rng.nextInt(B.SHAPES.length)];
                        List<BlockState> states = b.getStateDefinition().getPossibleStates();
                        BlockState s = states.get(rng.nextInt(states.size()));
                        if (s.getFluidState().isEmpty()) {
                            a.set(x, 64 + (rng.nextInt(5) == 0 ? 1 : 0), z, s);
                        }
                    }
                }
            }
            double x = rng.nextDouble() * 16.0;
            double z = rng.nextDouble() * 16.0;
            double y = rng.nextInt(6) == 0 ? 63.0 + rng.nextDouble() * 2.0 : 64.0 + rng.nextDouble() * 8.0;
            double dx = (rng.nextDouble() - 0.5) * 0.8;
            double dy = (rng.nextDouble() - 0.4) * 0.8;
            double dz = (rng.nextDouble() - 0.5) * 0.8;
            ItemStack stack = new ItemStack(rng.nextBoolean() ? Items.DIAMOND : Items.OAK_LOG, 1 + rng.nextInt(64));
            Twin t = spawn(a, x, y, z, stack, dx, dy, dz, rng.nextLong());
            if (rng.nextInt(20) == 0) {
                t.k.setNoGravity(true);
                t.v.setNoGravity(true);
            }
            int ticks = 400 + rng.nextInt(300);
            run(a, t, ticks, "terrain#" + r + " " + BuiltInRegistries.BLOCK.getKey(floor), null);
            if (t.k.onGround()) {
                landed++;
            }
        }
        expect(landed > runs / 2, "random terrain: fewer than half the items ended on the ground (" + landed + ")");
        System.out.println("  random terrain: " + runs + " seeded runs (" + landed + " landed), "
                + (comparisons - before) + " comparisons");
    }

    // =====================================================================
    // 3. Lifetime
    // =====================================================================

    private static void checkLifetime() {
        int life = KestrelItemEntity.LIFETIME;
        // Start at ages just below the boundary: the discard must come on the
        // tick whose increment reaches LIFETIME, not one earlier or later.
        for (int startAge : new int[]{life - 3, life - 2, life - 1}) {
            Arena a = flat();
            Twin t = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND), 0.0, 0.0, 0.0, SEED + startAge);
            t.k.setAge(startAge);
            set(AGE, t.v, startAge);
            int removedAt = run(a, t, 10, "lifetime from " + startAge, null);
            expect(removedAt == life - startAge, "lifetime from age " + startAge + ": removed on tick " + removedAt
                    + ", expected " + (life - startAge));
            expect(t.k.getAge() == life, "lifetime: age at discard is " + t.k.getAge() + ", expected " + life);
        }
        // Age exactly LIFETIME (or past it) at the start: discarded on the first tick.
        for (int startAge : new int[]{life, life + 1}) {
            Arena a = flat();
            Twin t = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND), 0.0, 0.0, 0.0, SEED + startAge);
            t.k.setAge(startAge);
            set(AGE, t.v, startAge);
            int removedAt = run(a, t, 10, "lifetime from " + startAge, null);
            expect(removedAt == 1, "lifetime from age " + startAge + ": removed on tick " + removedAt + ", expected 1");
        }
        // The unlimited-lifetime sentinel never ages.
        {
            Arena a = flat();
            Twin t = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND), 0.0, 0.0, 0.0, SEED + 5);
            t.k.setAge(KestrelItemEntity.INFINITE_LIFETIME);
            t.v.setUnlimitedLifetime();
            int removedAt = run(a, t, 50, "unlimited lifetime", null);
            expect(removedAt == -1 && t.k.getAge() == KestrelItemEntity.INFINITE_LIFETIME,
                    "unlimited lifetime: aged or removed");
        }
        // A whole life from spawn: alive after tick LIFETIME-1, removed on tick LIFETIME,
        // and not ticked on LIFETIME+1.
        {
            Arena a = flat();
            Twin t = spawn(a, 2.5, 66.0, 2.5, new ItemStack(Items.DIAMOND), 0.02, 0.1, -0.01, SEED + 6000);
            boolean[] aliveAtBoundary = {false};
            int removedAt = run(a, t, life + 5, "full life", i -> {
                if (i == life - 1) {
                    aliveAtBoundary[0] = !t.k.isRemoved() && t.k.getAge() == life - 1;
                }
            });
            expect(aliveAtBoundary[0], "full life: not alive at age LIFETIME-1");
            expect(removedAt == life, "full life: removed on tick " + removedAt + ", expected " + life);
            int ticksBefore = t.k.tickCount;
            a.world.tickEntities();
            expect(t.k.tickCount == ticksBefore && !a.world.isTicking(t.k.getId()),
                    "full life: a removed item was ticked again at LIFETIME+1");
        }
        System.out.println("  lifetime: discard exactly at age LIFETIME, from LIFETIME-3..LIFETIME+1 and over a full life");
    }

    // =====================================================================
    // 4. The tick slot, by observed effects
    // =====================================================================

    /** A test entity whose tick runs a probe; untracked (marker range 0). */
    static final class Probe extends KestrelEntity {
        final List<String> log;
        final String name;
        Runnable onTick = () -> { };
        int ticks;
        int despawnChecks;

        Probe(List<String> log, String name) {
            super(EntityIds.claim(), UUID.randomUUID(), EntityTypes.MARKER);
            this.log = log;
            this.name = name;
        }

        @Override
        public void checkDespawn() {
            despawnChecks++;
        }

        @Override
        public void tick(EntityLevel level) {
            ticks++;
            log.add(name);
            onTick.run();
        }
    }

    /** A tracking observer that logs a marker the first time it is sent something in a tick. */
    static final class Observer implements TrackingObserver {
        final int id;
        final List<String> order;
        final List<String> packets = new ArrayList<>();
        double x;
        double z;

        Observer(int id, List<String> order, double x, double z) {
            this.id = id;
            this.order = order;
            this.x = x;
            this.z = z;
        }

        @Override
        public int entityId() {
            return id;
        }

        @Override
        public double x() {
            return x;
        }

        @Override
        public double y() {
            return 64.0;
        }

        @Override
        public double z() {
            return z;
        }

        @Override
        public int viewDistance() {
            return 10;
        }

        @Override
        public boolean isChunkTracked(int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public void send(Outbound.Packet packet) {
            if (order != null) {
                order.add("tracker");
            }
            packets.add(fmt(packet));
        }
    }

    private static void checkTickSlot() {
        List<String> order = new ArrayList<>();
        TickLoop loop = new TickLoop(new SectionStore(-64, 384));
        EntityTracker tracker = new EntityTracker(100);
        loop.setEntityTracker(tracker);
        EntityLevel level = loopLevel(loop);
        EntityWorld world = new EntityWorld(level, tracker);
        loop.setEntityWorld(world);

        // A tracked entity whose data changes every tick, so the tracker sends every tick.
        KestrelEntity watched = new KestrelEntity(EntityIds.claim(), UUID.randomUUID(), EntityTypes.INTERACTION);
        watched.setPos(0.5, 64.0, 0.5);
        Observer observer = new Observer(-5, null, 1.0, 1.0);
        tracker.addPlayer(observer);
        world.addFreshEntity(watched);

        BlockPos tickPos = new BlockPos(4, 64, 4);
        loop.store().set(tickPos, B.STONE);
        loop.hooks().onBlockTick((l, event) -> {
            order.add("blockTicks");
            event.cancel();
        });
        BlockPos fluidPos = new BlockPos(6, 64, 6);
        BlockPos eventPos = new BlockPos(8, 64, 8);
        loop.store().set(eventPos, B.STONE);
        BlockPos sleeperPos = new BlockPos(10, 64, 10);
        loop.registerBlockEntity(sleeperPos, dev.kestrel.blockentity.SleeperRegistry.Type.FURNACE);
        long sleeperId = sleeperPos.asLong();

        boolean[][] seen = new boolean[1][];
        Probe probe = new Probe(order, "entities");
        probe.setPos(12.5, 64.0, 12.5);
        probe.onTick = () -> seen[0] = new boolean[]{
            !loop.hasScheduledTick(tickPos, Blocks.STONE),
            !loop.hasScheduledFluidTick(fluidPos, Fluids.WATER),
            loop.redstone().pendingBlockEventCount() == 0,
            !loop.redstone().isHandlingTick(),
            !loop.sleepers().isAsleep(sleeperId),
            order.contains("tracker"),
        };
        world.addFreshEntity(probe);

        int good = 0;
        int rounds = 40;
        for (int round = 0; round < rounds; round++) {
            order.clear();
            seen[0] = null;
            // Arm every phase for the next tick.
            loop.scheduleTick(tickPos, Blocks.STONE, 1, TickPriority.NORMAL);
            loop.scheduleFluidTick(fluidPos, Fluids.WATER, 1, TickPriority.NORMAL);
            loop.redstone().blockEvent(eventPos, Blocks.STONE, 0, 0);
            loop.wakeBlockEntity(sleeperPos, dev.kestrel.blockentity.SleeperRegistry.WakeSignal.FURNACE_SLOT0);
            watched.setCustomNameVisible(round % 2 == 0);
            // The Observer only logs when an order list is attached.
            Observer logging = new Observer(-6 - round, order, 1.0, 1.0);
            tracker.removePlayer(observer);
            tracker.addPlayer(logging);
            order.clear();
            observer = logging;

            loop.tick();

            boolean asleepAfter = loop.sleepers().isAsleep(sleeperId);
            // Collapse repeats: the tracker may send several packets.
            List<String> phases = new ArrayList<>();
            for (String s : order) {
                if (phases.isEmpty() || !phases.getLast().equals(s)) {
                    phases.add(s);
                }
            }
            boolean ok = expect(phases.equals(List.of("blockTicks", "tracker", "entities")),
                    "tick slot round " + round + ": phase order " + phases);
            ok &= expect(seen[0] != null, "tick slot round " + round + ": the entity phase did not run");
            if (seen[0] != null) {
                String[] names = {"block ticks drained", "fluid ticks drained", "block events drained",
                    "handlingTick lowered", "block-entity pass not yet run", "tracker already synced"};
                for (int i = 0; i < names.length; i++) {
                    ok &= expect(seen[0][i], "tick slot round " + round + ": in the entity phase, expected " + names[i]);
                }
            }
            ok &= expect(asleepAfter, "tick slot round " + round + ": the block-entity pass did not run after entities");
            if (ok) {
                good++;
            }
        }
        System.out.println("  tick slot: " + good + "/" + rounds + " ticks observed block ticks, fluid ticks, tracker,"
                + " block events, then entities, then block entities");
    }

    static EntityLevel loopLevel(TickLoop loop) {
        ShapeBoxCache cache = new ShapeBoxCache();
        return new EntityLevel() {
            @Override
            public BlockGetter blocks() {
                return loop.blockGetter();
            }

            @Override
            public ShapeBoxCache shapeCache() {
                return cache;
            }

            @Override
            public int getMinY() {
                return loop.store().getMinY();
            }

            @Override
            public net.minecraft.world.level.LevelReader levelReader() {
                return loop.entityLevelReader();
            }

            @Override
            public boolean setBlock(BlockPos pos, BlockState state, int flags) {
                return loop.setBlock(pos, state, flags);
            }

            @Override
            public void levelEvent(int type, BlockPos pos, int data) {
                loop.emitLevelEvent(type, pos, data);
            }

            @Override
            public void landingBlockUpdate(KestrelEntity entity, BlockPos pos, BlockState state) {
                throw new UnsupportedOperationException("the tick-slot loop has no falling blocks");
            }

            @Override
            public RandomSource random() {
                return loop.levelRandom();
            }

            @Override
            public net.minecraft.world.level.gamerules.GameRules gameRules() {
                return loop.gameRules();
            }

            @Override
            public void playSound(double x, double y, double z,
                    net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound,
                    net.minecraft.sounds.SoundSource source, float volume, float pitch) {
                throw new UnsupportedOperationException("the tick-slot loop plays no sounds");
            }

            @Override
            public void entityEvent(KestrelEntity entity, byte event) {
                throw new UnsupportedOperationException("the tick-slot loop raises no entity events");
            }

            @Override
            public @Nullable EntityWorld world() {
                return loop.entityWorld();
            }
        };
    }

    // =====================================================================
    // 5. The pass rules
    // =====================================================================

    private static void checkPassRules() {
        // emptyTime: with no active ticket, entities tick while emptyTime < 300.
        {
            TickLoop loop = new TickLoop(new SectionStore(-64, 384));
            EntityWorld world = new EntityWorld(loopLevel(loop), null);
            loop.setEntityWorld(world);
            List<String> log = new ArrayList<>();
            Probe p = new Probe(log, "p");
            world.addFreshEntity(p);
            boolean[] active = {false};
            loop.setActiveTickets(() -> active[0]);
            for (int i = 0; i < 310; i++) {
                loop.tick();
            }
            expect(p.ticks == 299, "emptyTime: " + p.ticks + " entity ticks with no active ticket, vanilla gives 299");
            active[0] = true;
            loop.tick();
            expect(p.ticks == 300 && loop.emptyTime() == 1,
                    "emptyTime: an active ticket did not resume ticking (ticks " + p.ticks + ", emptyTime "
                            + loop.emptyTime() + ")");
            for (int i = 0; i < 500; i++) {
                loop.tick();
            }
            expect(p.ticks == 800, "emptyTime: an active dimension stopped ticking entities");
        }

        // Ticking range: out of range, checkDespawn still runs, the tick does not.
        {
            Arena a = new Arena();
            List<String> log = new ArrayList<>();
            Probe in = new Probe(log, "in");
            in.setPos(0.5, 64, 0.5);
            Probe out = new Probe(log, "out");
            out.setPos(100.5, 64, 0.5);
            a.world.addFreshEntity(in);
            a.world.addFreshEntity(out);
            long outChunk = net.minecraft.world.level.ChunkPos.pack(6, 0);
            a.world.setInEntityTickingRange(chunk -> chunk != outChunk);
            a.world.tickEntities();
            expect(in.ticks == 1 && out.ticks == 0, "ticking range: an entity out of range ticked, or one in range did not");
            expect(in.despawnChecks == 1 && out.despawnChecks == 1,
                    "ticking range: checkDespawn must run before the range test");
        }

        // Order, removal and addition during the pass.
        {
            Arena a = new Arena();
            List<String> log = new ArrayList<>();
            Probe first = new Probe(log, "first");
            Probe second = new Probe(log, "second");
            Probe third = new Probe(log, "third");
            Probe[] late = new Probe[1];
            first.onTick = () -> {
                second.discard();
                if (late[0] == null) {
                    late[0] = new Probe(log, "late");
                    a.world.addFreshEntity(late[0]);
                }
            };
            a.world.addFreshEntity(first);
            a.world.addFreshEntity(second);
            a.world.addFreshEntity(third);
            a.world.tickEntities();
            expect(log.equals(List.of("first", "third")),
                    "pass: removed-mid-pass or added-mid-pass entity handled wrongly, order " + log);
            expect(!a.world.isTicking(second.getId()) && a.world.getEntity(second.getId()) == null
                            && !EntityIds.isLive(second.getId()),
                    "pass: a discarded entity is still in the tick list, storage or id set");
            log.clear();
            a.world.tickEntities();
            expect(log.equals(List.of("first", "third", "late")), "pass: next pass order " + log);
        }

        // Vehicle gating.
        {
            Arena a = new Arena();
            List<String> log = new ArrayList<>();
            Probe vehicle = new Probe(log, "vehicle");
            Probe rider = new Probe(log, "rider");
            a.world.addFreshEntity(rider);
            a.world.addFreshEntity(vehicle);
            rider.startRiding(vehicle);
            a.world.tickEntities();
            // The rider is before the vehicle in the list, is skipped there, then
            // ticks once as the vehicle's passenger.
            expect(log.equals(List.of("vehicle", "rider")) && rider.ticks == 1 && rider.tickCount == 1,
                    "vehicle gating: a carried passenger ticked on its own, order " + log);
            log.clear();
            vehicle.discard();
            a.world.tickEntities();
            expect(log.equals(List.of("rider")) && !rider.isPassenger(),
                    "vehicle gating: a passenger of a removed vehicle was not dismounted and ticked, order " + log);
        }

        // The crash guard.
        {
            Arena a = new Arena();
            Probe p = new Probe(new ArrayList<>(), "boom");
            p.onTick = () -> {
                throw new IllegalStateException("boom");
            };
            a.world.addFreshEntity(p);
            boolean reported = false;
            try {
                a.world.tickEntities();
            } catch (ReportedException e) {
                CrashReport report = e.getReport();
                reported = report.getTitle().equals("Ticking entity")
                        && e.getCause() instanceof IllegalStateException;
            }
            expect(reported, "crash guard: a throwing tick was not reported as \"Ticking entity\"");
        }

        // Refusals: a removed entity, a duplicate UUID.
        {
            Arena a = new Arena();
            Probe p = new Probe(new ArrayList<>(), "p");
            p.discard();
            expect(!a.world.addFreshEntity(p), "addFreshEntity accepted a removed entity");
            KestrelItemEntity one = a.world.spawnItem(0.5, 64, 0.5, new ItemStack(Items.STONE), 0, 0, 0);
            KestrelItemEntity dup = new KestrelItemEntity(EntityIds.claim(), one.getUUID(),
                    net.minecraft.util.RandomSource.create());
            expect(!a.world.addFreshEntity(dup) && !a.world.isTicking(dup.getId()),
                    "addFreshEntity accepted a duplicate UUID");
        }
        System.out.println("  pass rules: emptyTime, ticking range, checkDespawn, removal and addition mid-pass,"
                + " vehicle gating, crash guard, refusals");
    }

    // =====================================================================
    // 6. Identity
    // =====================================================================

    private static void checkIdentity() {
        Arena a = flat();
        Random rng = new Random(SEED ^ 0x1D);
        List<Integer> ids = new ArrayList<>();
        List<KestrelPlayer> players = new ArrayList<>();
        List<KestrelItemEntity> items = new ArrayList<>();
        int previous = EntityIds.peek();
        boolean consecutive = true;
        for (int i = 0; i < 400; i++) {
            int id;
            if (rng.nextBoolean()) {
                KestrelPlayer p = new KestrelPlayer(new UUID(0x9, i), "p" + i, 0.5, 64.0, 0.5);
                players.add(p);
                id = p.entityId;
            } else {
                KestrelItemEntity e = a.world.spawnItem(rng.nextDouble() * 8, 65.0, rng.nextDouble() * 8,
                        new ItemStack(Items.APPLE));
                items.add(e);
                id = e.getId();
            }
            // One counter: every allocation, from either path, is the next value.
            consecutive &= id == previous + 1;
            previous = id;
            ids.add(id);
        }
        expect(consecutive, "identity: player and entity ids are not drawn from one counter");
        expect(new HashSet<>(ids).size() == ids.size(), "identity: two live owners share an id");
        boolean allLive = true;
        for (int id : ids) {
            allLive &= EntityIds.isLive(id);
        }
        expect(allLive, "identity: a minted id is not recorded live");
        // Players and items interleave, so both paths really were exercised.
        expect(players.size() > 100 && items.size() > 100, "identity: interleave too one-sided");

        // Removal releases the id; players keep theirs.
        KestrelItemEntity gone = items.get(0);
        a.world.removeEntity(gone, Entity.RemovalReason.DISCARDED);
        expect(!EntityIds.isLive(gone.getId()), "identity: a removed entity's id is still live");
        expect(EntityIds.isLive(players.get(0).entityId), "identity: a player's id was released");

        // A wrapped counter never hands out a live id, nor 0.
        int top = EntityIds.peek();
        int livePlayer = players.get(3).entityId;
        EntityIds.setCounterForTest(livePlayer - 1);
        int next = EntityIds.claim();
        Set<Integer> liveIds = new HashSet<>(ids);
        liveIds.remove(gone.getId());
        expect(next != livePlayer && !liveIds.contains(next),
                "identity: the minter reused a live id (" + next + ")");
        EntityIds.release(next);
        EntityIds.setCounterForTest(-1);
        int afterZero = EntityIds.claim();
        expect(afterZero != 0, "identity: the minter handed out id 0");
        EntityIds.release(afterZero);
        // Re-mint over the whole live range: every id handed out must be free.
        EntityIds.setCounterForTest(ids.get(0) - 1);
        Set<Integer> liveNow = new HashSet<>(ids);
        liveNow.remove(gone.getId());
        boolean skipped = true;
        List<Integer> reminted = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int id = EntityIds.claim();
            skipped &= !liveNow.contains(id);
            reminted.add(id);
        }
        expect(skipped, "identity: re-minting over live ids returned a live one");
        expect(reminted.get(0) == gone.getId(), "identity: the released id was not the first free one");
        for (int id : reminted) {
            EntityIds.release(id);
        }
        EntityIds.setCounterForTest(Math.max(top, EntityIds.peek()));

        // The join packet carries the player's minted id, and the id is final.
        KestrelPlayer joiner = new KestrelPlayer(new UUID(0x10, 1), "joiner", 0.5, 64.0, 0.5);
        Outbound.Packet join = Outbound.join(joiner.entityId, 20, 0L, 10, 10, false, 0);
        int sent = java.nio.ByteBuffer.wrap(join.payload(), 0, 4).getInt();
        expect(sent == joiner.entityId, "identity: the join packet does not carry the minted id");
        try {
            expect(java.lang.reflect.Modifier.isFinal(KestrelPlayer.class.getField("entityId").getModifiers()),
                    "identity: KestrelPlayer.entityId is not final, it could change after the join packet");
        } catch (NoSuchFieldException e) {
            expect(false, "identity: KestrelPlayer.entityId missing");
        }
        System.out.println("  identity: " + ids.size() + " interleaved player and entity ids from one counter,"
                + " no live id reused across a wrap, the join packet carries the minted id");
    }

    // =====================================================================
    // 7. Packets
    // =====================================================================

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

    static String fmt(int id, byte[] payload) {
        return id + ":" + HexFormat.of().formatHex(payload);
    }

    static String fmt(Outbound.Packet p) {
        return fmt(p.id(), p.payload());
    }

    static ServerEntity.Synchronizer capture(List<String> log) {
        return new ServerEntity.Synchronizer() {
            @Override
            public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
                log.addAll(encodeVanilla(packet));
            }

            @Override
            public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
                log.addAll(encodeVanilla(packet));
            }

            @Override
            public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet,
                    Predicate<ServerPlayer> predicate) {
                log.addAll(encodeVanilla(packet));
            }
        };
    }

    static List<ItemStack> stacks() {
        List<ItemStack> out = new ArrayList<>();
        out.add(new ItemStack(Items.DIAMOND));
        out.add(new ItemStack(Items.COBBLESTONE, 64));
        out.add(new ItemStack(Items.OAK_LOG, 17));
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamageValue(123);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Kestrel"));
        out.add(sword);
        ItemStack apple = new ItemStack(Items.APPLE, 3);
        apple.remove(DataComponents.FOOD);
        out.add(apple);
        ItemStack enchanted = new ItemStack(Items.ENCHANTED_BOOK);
        enchanted.set(DataComponents.REPAIR_COST, 7);
        out.add(enchanted);
        return out;
    }

    private static void checkPairing() {
        int cases = 0;
        for (ItemStack stack : stacks()) {
            for (int variant = 0; variant < 3; variant++) {
                Arena a = flat();
                Twin t = spawn(a, 2.5 + variant, 66.0, 2.5, stack, 0.01 * variant, 0.2, -0.02, SEED + variant);
                if (variant == 1) {
                    t.k.setCustomName(Component.literal("drop"));
                    t.v.setCustomName(Component.literal("drop"));
                    t.k.setSharedFlag(KestrelEntity.FLAG_GLOWING, true);
                    t.v.setGlowingTag(true);
                }
                List<String> vanillaLog = new ArrayList<>();
                ServerEntity se = new ServerEntity(a.oracle, t.v, t.v.getType().updateInterval(),
                        t.v.getType().trackDeltas(), capture(vanillaLog));
                EntitySync sync = new EntitySync(t.k, t.k.getType().updateInterval(), t.k.getType().trackDeltas(),
                        p -> { });
                if (variant == 2) {
                    // Mid-life: after some ticks of motion, pairing uses the last sent state.
                    for (int i = 0; i < 37; i++) {
                        sync.sendChanges();
                        se.sendChanges();
                        a.world.tickEntities();
                        tickVanilla(a, t.v);
                    }
                    same(t, "pairing mid-life state");
                }
                List<Packet<? super ClientGamePacketListener>> members = new ArrayList<>();
                se.sendPairingData(null, members::add);
                List<String> vanillaBundle = encodeVanilla(new ClientboundBundlePacket(members));
                List<String> kestrelBundle = new ArrayList<>();
                for (Outbound.Packet p : sync.pairingBundle()) {
                    kestrelBundle.add(fmt(p));
                }
                expect(kestrelBundle.equals(vanillaBundle), "pairing " + stack + " variant " + variant
                        + ":\n    kestrel " + kestrelBundle + "\n    vanilla " + vanillaBundle);
                // The bundle must actually carry the stack: a SetEntityData member.
                expect(vanillaBundle.size() == 4, "pairing " + stack + ": vanilla bundle is not delimiter, add, data,"
                        + " delimiter: " + vanillaBundle.size());
                cases++;
            }
        }
        System.out.println("  pairing: " + cases + " item pairing bundles (stack in SetEntityData) byte-identical to"
                + " vanilla sendPairingData");
    }

    /**
     * A falling, landing, sliding item watched by one player, through the
     * real TickLoop (tracker slot, then the entity slot) against vanilla's
     * ServerEntity.sendChanges then tickNonPassenger, in that order, as
     * ServerLevel.tick runs them. Removal sends RemoveEntities on both.
     */
    private static void checkPacketStream() {
        int streams = 0;
        long packets = 0;
        Random rng = new Random(SEED ^ 0x57);
        for (int s = 0; s < 24; s++) {
            TickLoop loop = new TickLoop(new SectionStore(-64, 384));
            for (int x = -12; x <= 28; x++) {
                for (int z = -12; z <= 28; z++) {
                    loop.store().set(new BlockPos(x, 63, z), s % 3 == 0 ? Blocks.ICE.defaultBlockState() : B.STONE);
                }
            }
            if (s % 4 == 1) {
                loop.store().set(new BlockPos(5, 64, 5), Blocks.SLIME_BLOCK.defaultBlockState());
            }
            EntityTracker tracker = new EntityTracker(100);
            loop.setEntityTracker(tracker);
            EntityWorld world = new EntityWorld(loopLevel(loop), tracker);
            loop.setEntityWorld(world);
            Observer observer = new Observer(-900, null, 6.0, 6.0);
            tracker.addPlayer(observer);
            OracleLevel oracle = OracleLevel.create(loop.blockGetter());

            double x = 2.0 + rng.nextDouble() * 6.0;
            double z = 2.0 + rng.nextDouble() * 6.0;
            double y = 64.0 + rng.nextDouble() * 10.0;
            double dx = (rng.nextDouble() - 0.5) * 0.5;
            double dy = rng.nextDouble() * 0.4;
            double dz = (rng.nextDouble() - 0.5) * 0.5;
            ItemStack stack = stacks().get(s % stacks().size());
            KestrelItemEntity k = world.spawnItem(x, y, z, stack.copy(), dx, dy, dz);
            oracle.nextId(k.getId());
            ItemEntity v = new ItemEntity(oracle, x, y, z, stack.copy(), dx, dy, dz);
            v.setUUID(k.getUUID());
            v.setYRot(k.getYRot());
            long seed = rng.nextLong();
            k.getRandom().setSeed(seed);
            v.getRandom().setSeed(seed);
            int lifetimeLeft = s % 2 == 0 ? 300 + s : -1;
            if (lifetimeLeft > 0) {
                k.setAge(KestrelItemEntity.LIFETIME - lifetimeLeft);
                set(AGE, v, KestrelItemEntity.LIFETIME - lifetimeLeft);
            }

            // Vanilla: ChunkMap.addEntity pairs the new entity at once.
            List<String> vanillaLog = new ArrayList<>();
            ServerEntity se = new ServerEntity(oracle, v, v.getType().updateInterval(), v.getType().trackDeltas(),
                    capture(vanillaLog));
            List<Packet<? super ClientGamePacketListener>> members = new ArrayList<>();
            se.sendPairingData(null, members::add);
            vanillaLog.addAll(encodeVanilla(new ClientboundBundlePacket(members)));
            boolean ok = expect(observer.packets.equals(vanillaLog), "stream " + s + " spawn pairing:\n    kestrel "
                    + observer.packets + "\n    vanilla " + vanillaLog);
            Twin t = new Twin(k, v);
            boolean vanillaRemovedSent = false;
            for (int tick = 1; tick <= 700 && ok; tick++) {
                observer.packets.clear();
                vanillaLog.clear();
                loop.tick();
                if (!vanillaRemovedSent) {
                    se.sendChanges();
                    tickVanilla(oracle, v);
                    if (v.isRemoved()) {
                        // ChunkMap.removeEntity -> broadcastRemoved -> removePairing.
                        vanillaLog.addAll(encodeVanilla(new ClientboundRemoveEntitiesPacket(v.getId())));
                        vanillaRemovedSent = true;
                    }
                }
                ok = expect(observer.packets.equals(vanillaLog), "stream " + s + " tick " + tick + ":\n    kestrel "
                        + observer.packets + "\n    vanilla " + vanillaLog);
                ok &= same(t, "stream " + s + " tick " + tick);
                packets += vanillaLog.size();
            }
            if (lifetimeLeft > 0) {
                expect(vanillaRemovedSent && k.isRemoved() && !tracker.isTracked(k.getId()),
                        "stream " + s + ": the lifetime discard did not untrack the item");
            }
            streams++;
        }
        expect(packets > 500, "packet stream: too few packets compared (" + packets + ")");
        System.out.println("  packet stream: " + streams + " items through TickLoop, " + packets
                + " packets byte-identical to vanilla ServerEntity, tick for tick, removal included");
    }
}
