package dev.kestrel.entity;

import dev.kestrel.collision.ShapeBoxCache;
import dev.kestrel.loop.SectionStore;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.net.Outbound;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.PostProcessParityCheck;
import dev.kestrel.worldgen.PostProcessParityCheck.OracleLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.ScheduledTick;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * G66 slice 2 gate: Kestrel's live falling-block path against a real
 * vanilla {@code FallingBlockEntity} on an oracle {@code ServerLevel}.
 *
 * <p>Every scenario runs through the live path on both sides. Blocks are
 * placed and supports removed with {@code setBlock(pos, state, 3)}
 * ({@code TickLoop.setBlock} and the oracle's real {@code Level.setBlock}),
 * which schedules the {@code FallingBlock} tick through {@code onPlace} or
 * {@code updateShape}. Each game tick then runs {@code TickLoop.tick} on
 * Kestrel's side and, on vanilla's, what {@code ServerLevel.tick}
 * ({@code ServerLevel.java:383-449}) runs for these: the clock, the due block
 * ticks ({@code state.tick(level, pos, random)} through the private
 * {@code tickBlock}), the fluid ticks, then the entity pass over the entities
 * present when it began. A handful of carriers that are not a
 * {@code FallingBlock} (a waterlogged lantern, a one-layer snow state) and
 * two boundary cases (the {@code time > 600} drop, a concrete clip at speed 2)
 * spawn through {@code FallingBlockEntity.fall} and {@code EntityWorld.spawnFalling}
 * directly, and then run through the same ticks.
 *
 * <p>Compared raw, no tolerance:
 * <ul>
 *   <li>every entity both sides created, paired in creation order, every
 *       tick: falling blocks (id, UUID, position, motion, fall distance,
 *       onGround, time, carried state, start position, removal) and items
 *       (the same plus yaw, {@code bobOffs}, age, pickup delay and stack);</li>
 *   <li>each entity's packets from its tracker, pairing bundle and changes,
 *       byte for byte against vanilla's {@code ServerEntity};</li>
 *   <li>at the end: the ordered block writes (position, old, new, flags), the
 *       write and spawn order, the level events, the landing block updates,
 *       the sounds and entity events, both pending tick lists, the final
 *       cells, and one draw from each level random (both sides made the same
 *       number of draws).</li>
 * </ul>
 *
 * <p>Randoms. Vanilla's entities draw from their own unseeded
 * {@code RandomSource.create()}. When the oracle adds one, the gate reads its
 * {@code LegacyRandomSource} state, rewinds it over the constructor's draws
 * (4 for a falling block's UUID; 10 for an item: UUID, {@code bobOffs}, yaw
 * and two motion doubles) and hands that state to Kestrel's matching entity
 * through {@code EntityWorld.setEntityRandoms}. A falling block's random is
 * then reseeded on both sides to a fixed per-scenario value before its first
 * draw, so the anvil rolls are the same on every run.
 *
 * <p>Also here: vanilla {@code FallingBlock.falling} against Kestrel's for every
 * registered falling block, and the level-event packet delivery.
 */
public final class FallingBlockParityCheck {
    private static final long SEED = 0xF411_B10CL;
    private static final long START_TIME = 1000L;
    private static final int RAW = 2 | 16 | 512;
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long MULTIPLIER_INVERSE = 0xDFE05BCB1365L;
    private static final long MASK = (1L << 48) - 1;

    private static final List<String> failures = new ArrayList<>();
    private static long checks;
    private static int scenarios;
    private static HeadlessNoise.Driver driver;
    private static Method tickBlock;
    private static Method tickFluid;

    private FallingBlockParityCheck() {
    }

    public static void main(String[] args) throws Exception {
        ItemEntityTickCheck.initializePacketCodec();
        driver = HeadlessNoise.loadStructural(1L).driver();
        tickBlock = ServerLevel.class.getDeclaredMethod("tickBlock", BlockPos.class, Block.class);
        tickBlock.setAccessible(true);
        tickFluid = ServerLevel.class.getDeclaredMethod("tickFluid", BlockPos.class, Fluid.class);
        tickFluid.setAccessible(true);

        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState sand = Blocks.SAND.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState anvil = Blocks.ANVIL.defaultBlockState();

        // ---- the slice 2 list ----
        scenario("sand one up", 40, a -> { a.floor(63, stone); a.place(0, 65, 0, sand); });
        scenario("sand ten up", 60, a -> { a.floor(63, stone); a.place(0, 74, 0, sand); });
        scenario("still water", 60, a -> { a.floor(63, stone); a.set(0, 64, 0, water); a.place(0, 69, 0, sand); });
        scenario("flowing water", 60, a -> {
            a.floor(63, stone);
            a.set(0, 64, 0, water.setValue(LiquidBlock.LEVEL, 4));
            a.place(0, 69, 0, sand);
        });
        BlockState powder = Blocks.CONCRETE_POWDER.asList().get(14).defaultBlockState();
        scenario("powder hardens in water", 60, a -> { a.floor(63, stone); a.set(0, 64, 0, water); a.place(0, 69, 0, powder); });
        scenario("powder clip from height", 120, a -> { a.floor(63, stone); a.set(0, 64, 0, water); a.place(0, 140, 0, powder); });
        scenario("powder clip at speed 2", 30, a -> {
            a.floor(63, stone);
            a.set(0, 66, 0, water);
            a.spawnDirect(0, 75, 0, Blocks.CONCRETE_POWDER.asList().get(4).defaultBlockState(),
                    new Vec3(0.0, -2.0, 0.0), 0);
        });
        scenario("waterlogged carrier", 60, a -> {
            a.floor(63, stone);
            a.set(0, 64, 0, water);
            a.spawnDirect(0, 69, 0, Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                    null, 0);
        });
        scenario("torch drop", 60, a -> { a.floor(63, stone); a.set(0, 64, 0, Blocks.TORCH.defaultBlockState()); a.place(0, 68, 0, sand); });
        scenario("slab", 60, a -> { a.floor(63, stone); a.set(0, 64, 0, Blocks.STONE_SLAB.defaultBlockState()); a.place(0, 68, 0, sand); });
        scenario("carpet", 60, a -> { a.floor(63, stone); a.set(0, 64, 0, Blocks.CARPET.asList().get(0).defaultBlockState()); a.place(0, 68, 0, sand); });
        scenario("snow one layer", 60, a -> { a.floor(63, stone); a.set(0, 64, 0, Blocks.SNOW.defaultBlockState()); a.place(0, 68, 0, sand); });
        scenario("snow three layers", 60, a -> {
            a.floor(63, stone);
            a.set(0, 64, 0, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
            a.place(0, 68, 0, sand);
        });
        scenario("anvil from height", 80, a -> { a.floor(63, stone); a.place(0, 90, 0, anvil); });
        scenario("anvil low roll", 40, a -> { a.floor(63, stone); a.place(0, 67, 0, anvil); });
        scenario("damaged anvil breaks", 80, a -> { a.floor(63, stone); a.place(0, 90, 0, Blocks.DAMAGED_ANVIL.defaultBlockState()); });
        scenario("dragon egg", 60, a -> { a.floor(63, stone); a.place(0, 70, 0, Blocks.DRAGON_EGG.defaultBlockState()); });
        scenario("through the void", 130, a -> {
            a.place(0, driver.height().getMinY(), 0, sand);
            a.after(arena -> arena.expect(arena.kEntities.size() == 2
                            && arena.kEntities.get(0) instanceof KestrelFallingBlockEntity f && f.getTime() == 101
                            && arena.kEntities.get(1) instanceof KestrelItemEntity item
                            && f.isRemoved() && item.isRemoved()
                            && item.position().y < driver.height().getMinY() - 64,
                    "through the void: expected the drop at time 101 below the world and both discarded"));
        });
        scenario("time 600", 4, a -> a.spawnDirect(0, 200, 0, sand, null, 600));
        scenario("same cell, same tick", 80, a -> {
            a.floor(63, stone);
            a.place(0, 90, 0, sand);
            a.at(SAME_CELL_DELAY, () -> a.place(0, 70, 0, Blocks.GRAVEL.defaultBlockState()));
            a.after(FallingBlockParityCheck::checkSameCell);
        });
        scenario("support removal chain", 80, a -> {
            a.floor(63, stone);
            a.set(0, 65, 0, stone);
            for (int y = 66; y <= 68; y++) {
                a.set(0, y, 0, Blocks.GRAVEL.defaultBlockState());
            }
            a.set(1, 66, 0, Blocks.OAK_FENCE.defaultBlockState().setValue(FenceBlock.WEST, true));
            a.place(0, 65, 0, Blocks.AIR.defaultBlockState());
            a.after(arena -> arena.expect(arena.loop.getBlockState(new BlockPos(0, 66, 0)).is(Blocks.GRAVEL)
                    && arena.loop.getBlockState(new BlockPos(1, 66, 0)).getValue(FenceBlock.WEST),
                    "support removal chain: the column did not settle two high with the fence reconnected"));
        });

        // ---- slice 2b: defects 1 to 4 ----
        scenario("anvil torch roll", 80, a -> { a.floor(63, stone); a.set(0, 64, 0, Blocks.TORCH.defaultBlockState()); a.place(0, 84, 0, anvil); });
        scenario("torch no drops", 60, a -> {
            a.entityDrops(false);
            a.floor(63, stone);
            a.set(0, 64, 0, Blocks.TORCH.defaultBlockState());
            a.place(0, 68, 0, sand);
        });
        scenario("failed setBlock drops", 60, a -> {
            a.floor(63, stone);
            a.set(0, 64, 0, Blocks.SNOW.defaultBlockState());
            a.spawnDirect(0, 68, 0, Blocks.SNOW.defaultBlockState(), null, 0);
        });
        scenario("failed setBlock no drops", 60, a -> {
            a.entityDrops(false);
            a.floor(63, stone);
            a.set(0, 64, 0, Blocks.SNOW.defaultBlockState());
            a.spawnDirect(0, 68, 0, Blocks.SNOW.defaultBlockState(), null, 0);
        });
        scenario("slime bounce", 60, a -> { a.floor(63, Blocks.SLIME_BLOCK.defaultBlockState()); a.place(0, 70, 0, sand); });
        scenario("powder snow from 2", 40, a -> { a.floor(63, stone); a.set(0, 64, 0, Blocks.POWDER_SNOW.defaultBlockState()); a.place(0, 67, 0, sand); });
        // Ends on the landing tick: the drop spawns inside the powder snow cell, where vanilla's
        // PowderSnowBlock.entityInside makes it stuck from its first tick (G68). The tick before,
        // the sand already overlaps the cell and entityInside's FREEZE raises its TicksFrozen to 1.
        scenario("powder snow from 10", POWDER_SNOW_LANDING, a -> { a.freezeG68 = true; a.floor(63, stone); a.set(0, 64, 0, Blocks.POWDER_SNOW.defaultBlockState()); a.place(0, 75, 0, sand); });

        // ---- slice 2b: fallOn and stepOn (defect 5) ----
        BlockState bed = Blocks.BED.asList().get(14).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        scenario("anvil onto bed", 60, a -> { a.floor(63, stone); a.set(0, 64, 0, bed); a.rollSeed(BED_ROLL_SEED); a.place(0, 74, 0, anvil); });
        scenario("sand onto farmland", 40, a -> { a.floor(63, stone); a.set(0, 64, 0, Blocks.FARMLAND.defaultBlockState()); a.place(0, 68, 0, sand); });
        scenario("item onto farmland", 60, a -> {
            a.floor(63, stone);
            a.set(0, 64, 0, Blocks.FARMLAND.defaultBlockState());
            a.dropItem(0.5, 69.0, 0.5, new ItemStack(Items.DIAMOND));
        });
        scenario("sand onto honey", 40, a -> { a.floor(63, stone); a.set(0, 64, 0, Blocks.HONEY_BLOCK.defaultBlockState()); a.place(0, 68, 0, sand); });
        scenario("item onto honey", 60, a -> {
            a.floor(63, stone);
            a.set(0, 64, 0, Blocks.HONEY_BLOCK.defaultBlockState());
            a.dropItem(0.5, 69.0, 0.5, new ItemStack(Items.DIAMOND));
        });
        BlockState tip = Blocks.POINTED_DRIPSTONE.defaultBlockState()
                .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.UP)
                .setValue(PointedDripstoneBlock.THICKNESS, SpeleothemThickness.TIP);
        scenario("anvil onto dripstone tip", 40, a -> { a.floor(63, stone); a.set(0, 64, 0, tip); a.rollSeed(TIP_ROLL_SEED); a.place(0, 67, 0, anvil); });
        scenario("sand onto redstone ore", 40, a -> { a.floor(63, stone); a.set(0, 64, 0, Blocks.REDSTONE_ORE.defaultBlockState()); a.place(0, 68, 0, sand); });
        scenario("sand onto hay", 40, a -> { a.floor(63, stone); a.set(0, 64, 0, Blocks.HAY_BLOCK.defaultBlockState()); a.place(0, 70, 0, sand); });

        checkFallingHooks();
        checkLevelEventDelivery();

        if (!failures.isEmpty()) {
            System.out.println("FallingBlockParityCheck FAILED with " + failures.size() + " failure(s):");
            failures.stream().limit(60).forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
        System.out.println("FallingBlockParityCheck: PASSED (" + checks + " checks, " + scenarios + " scenarios)");
    }

    /** Ticks between the high sand's placement and the gravel's, so both land on one cell in one tick. */
    static int SAME_CELL_DELAY = 23;
    /** The tick the sand dropped from 10 blocks lands on the powder snow's falling shape. */
    static int POWDER_SNOW_LANDING = 25;
    /**
     * Roll seeds whose first {@code nextFloat} separates the outcomes. Bed: the anvil falls about
     * 9.4, halved to 4.7, so vanilla rolls against 0.25 and a direct roll against 0.5; the seed
     * draws 0.413. Dripstone tip: about 2.3, raised by 2.5, so 0.25 against a direct 0.15; the
     * seed draws 0.216.
     */
    static long BED_ROLL_SEED = 0x5EED00001379L;
    static long TIP_ROLL_SEED = 0x5EED000026B6L;

    interface Setup {
        void apply(Arena arena) throws Exception;
    }

    static void scenario(String name, int ticks, Setup setup) throws Exception {
        scenarios++;
        Arena a = new Arena(name);
        setup.apply(a);
        a.run(ticks);
        a.finish();
    }

    /** Both the high sand and the gravel end in the same cell on the same tick. */
    private static void checkSameCell(Arena a) {
        List<KestrelMovingEntity> falling = a.kEntities.stream()
                .filter(KestrelFallingBlockEntity.class::isInstance).toList();
        a.expect(falling.size() == 2 && a.removedAt.get(falling.get(0)) != null
                        && Objects.equals(a.removedAt.get(falling.get(0)), a.removedAt.get(falling.get(1)))
                        && falling.get(0).blockPosition().equals(falling.get(1).blockPosition()),
                "same cell, same tick: the two blocks did not end in one cell on one tick " + a.removedAt);
    }

    // =====================================================================
    // The arena: one Kestrel world and one vanilla oracle, run side by side
    // =====================================================================

    static final class Arena {
        final String name;
        final SectionStore store = new SectionStore(driver.height().getMinY(), driver.height().getHeight());
        final TickLoop loop = new TickLoop(store);
        final ShapeBoxCache cache = new ShapeBoxCache();
        final OracleLevel oracle;
        final EntityLevel level;
        final EntityWorld world;
        final List<String> kWrites = new ArrayList<>();
        final List<String> kActions = new ArrayList<>();
        final List<String> kLanding = new ArrayList<>();
        final List<String> kEffects = new ArrayList<>();
        final List<KestrelMovingEntity> kEntities = new ArrayList<>();
        final List<Twin> twins = new ArrayList<>();
        final Map<KestrelMovingEntity, Integer> removedAt = new HashMap<>();
        final ArrayDeque<RandomSource> pendingRandoms = new ArrayDeque<>();
        final ArrayDeque<Long> pendingReseeds = new ArrayDeque<>();
        final Map<Integer, List<Runnable>> triggers = new HashMap<>();
        final List<Consumer<Arena>> afterChecks = new ArrayList<>();
        long rollSeed;
        int reseeds;
        /** Constructor draws of the next vanilla item: 10 for a drop, 6 when the motion is given. */
        int itemDraws = 10;
        /**
         * G68 is not modelled: powder snow's FREEZE raises a falling block's TicksFrozen once, and
         * the dirty data forces a full sync (ServerEntity.java:126). When set, the gate copies that
         * one value to the Kestrel twin before encoding, and asserts it is the only such copy.
         */
        boolean freezeG68;
        int freezeCopies;
        int tick;

        Arena(String name) throws Exception {
            this.name = name;
            this.rollSeed = SEED ^ name.hashCode();
            oracle = PostProcessParityCheck.emptyOracle(driver, SEED);
            // ServerLevel.tickBlock reads the Level.random field, not getRandom().
            Field random = Level.class.getDeclaredField("random");
            unsafe().putObject(oracle, unsafe().objectFieldOffset(random), oracle.getRandom());
            oracle.setGameTime(START_TIME);
            oracle.clearObservations();
            oracle.setAddListener(this::vanillaAdded);
            loop.setGameTime(START_TIME);
            loop.setLevelRandom(RandomSource.create(SEED));
            loop.captureLevelEvents();
            level = new ArenaLevel();
            world = new EntityWorld(level, null);
            world.setEntityRandoms(() -> pendingRandoms.isEmpty() ? RandomSource.create() : pendingRandoms.poll());
            loop.setEntityWorld(world);
            loop.setDetailedWriteListener((pos, oldState, newState, flags) -> {
                kWrites.add(pos.asLong() + ":" + Block.getId(oldState) + ">" + Block.getId(newState) + ":" + flags);
                kActions.add("W:" + pos.asLong() + ":" + Block.getId(newState));
            });
        }

        /** Kestrel's side of the level: the loop's store, writes, events and randoms, recorded. */
        private final class ArenaLevel implements EntityLevel {
            @Override public BlockGetter blocks() { return loop.blockGetter(); }
            @Override public ShapeBoxCache shapeCache() { return cache; }
            @Override public int getMinY() { return store.getMinY(); }
            @Override public int getMaxY() { return store.getMinY() + store.getHeight() - 1; }
            @Override public LevelReader levelReader() { return loop.entityLevelReader(); }
            @Override public boolean setBlock(BlockPos pos, BlockState state, int flags) { return loop.setBlock(pos, state, flags); }
            @Override public void levelEvent(int type, BlockPos pos, int data) { loop.emitLevelEvent(type, pos, data); }
            @Override public RandomSource random() { return loop.levelRandom(); }
            @Override public GameRules gameRules() { return loop.gameRules(); }

            @Override
            public void landingBlockUpdate(KestrelEntity entity, BlockPos pos, BlockState state) {
                kLanding.add(fmt(Outbound.blockUpdate(pos.asLong(), Block.getId(state))));
            }

            @Override
            public void playSound(double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
                    float volume, float pitch) {
                kEffects.add(OracleLevel.soundRecord(x, y, z, sound, source, volume, pitch));
            }

            @Override
            public void entityEvent(KestrelEntity entity, byte event) {
                kEffects.add("E:" + entity.getId() + ":" + event);
            }

            @Override
            public void entityAdded(KestrelEntity entity) {
                kActions.add("A:" + entity.getType());
                kEntities.add((KestrelMovingEntity) entity);
            }
        }

        // ---- building the world ----

        /** A raw cell on both sides: flags 2|16|512, no neighbour reaction, no onPlace. */
        void set(int x, int y, int z, BlockState state) {
            BlockPos pos = new BlockPos(x, y, z);
            oracle.setBlock(pos, state, RAW);
            loop.setBlock(pos, state, RAW);
        }

        void floor(int y, BlockState state) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    set(x, y, z, state);
                }
            }
        }

        /** A live write on both sides, {@code setBlock(pos, state, 3)}: onPlace and shape updates schedule the falls. */
        void place(int x, int y, int z, BlockState state) {
            BlockPos pos = new BlockPos(x, y, z);
            oracle.setBlock(pos, state, 3);
            loop.setBlock(pos, state, 3);
        }

        void at(int beforeTick, Runnable trigger) {
            triggers.computeIfAbsent(beforeTick, t -> new ArrayList<>()).add(trigger);
        }

        void after(Consumer<Arena> check) {
            afterChecks.add(check);
        }

        void entityDrops(boolean on) {
            loop.gameRules().set(GameRules.ENTITY_DROPS, on, null);
            oracle.getGameRules().set(GameRules.ENTITY_DROPS, on, null);
        }

        void rollSeed(long seed) {
            this.rollSeed = seed;
        }

        /** {@code FallingBlockEntity.fall} and Kestrel's spawn, for carriers the block tick cannot produce. */
        void spawnDirect(int x, int y, int z, BlockState state, Vec3 motion, int time) {
            BlockPos pos = new BlockPos(x, y, z);
            oracle.nextEntityId(EntityIds.peek());
            FallingBlockEntity v = FallingBlockEntity.fall(oracle, pos, state);
            KestrelFallingBlockEntity k = world.spawnFalling(pos, state);
            reseedNew();
            if (motion != null) {
                v.setDeltaMovement(motion);
                k.setDeltaMovement(motion);
            }
            if (time != 0) {
                v.time = time;
                FallingBlockParityCheck.set(field(KestrelFallingBlockEntity.class, "time"), k, time);
            }
        }

        /** An item dropped with no motion: {@code new ItemEntity(level, x, y, z, stack, 0, 0, 0)} and Kestrel's spawn. */
        void dropItem(double x, double y, double z, ItemStack stack) {
            oracle.nextEntityId(EntityIds.peek());
            itemDraws = 6;
            oracle.addFreshEntity(new ItemEntity(oracle, x, y, z, stack.copy(), 0.0, 0.0, 0.0));
            itemDraws = 10;
            world.spawnItem(x, y, z, stack.copy(), 0.0, 0.0, 0.0);
        }

        /** The oracle added an entity: hand Kestrel the random state its twin will start from. */
        private void vanillaAdded(Entity entity) {
            int draws = entity instanceof ItemEntity ? itemDraws : 4;
            pendingRandoms.add(rewound(entity.getRandom(), draws));
            if (entity instanceof FallingBlockEntity) {
                long seed = rollSeed + reseeds++;
                entity.getRandom().setSeed(seed);
                pendingReseeds.add(seed);
            }
        }

        /** Give Kestrel's newest falling blocks the fixed seeds their twins took; neither has drawn yet. */
        private void reseedNew() {
            for (KestrelMovingEntity k : kEntities) {
                if (k instanceof KestrelFallingBlockEntity && !reseeded.contains(k) && !pendingReseeds.isEmpty()) {
                    k.getRandom().setSeed(pendingReseeds.poll());
                    reseeded.add(k);
                }
            }
        }

        private final java.util.Set<KestrelMovingEntity> reseeded = new java.util.HashSet<>();

        // ---- running ----

        void run(int ticks) throws Exception {
            fire(0);
            for (tick = 1; tick <= ticks; tick++) {
                vanillaTick();
                loop.tick();
                reseedNew();
                compareEntities();
                fire(tick);
            }
        }

        private void fire(int afterTick) {
            for (Runnable trigger : triggers.getOrDefault(afterTick, List.of())) {
                trigger.run();
                reseedNew();
            }
        }

        /** {@code ServerLevel.tick} for what these scenarios reach: clock, block ticks, fluid ticks, entities. */
        private void vanillaTick() throws Exception {
            long time = START_TIME + tick;
            oracle.setGameTime(time);
            oracle.nextEntityId(EntityIds.peek());
            oracle.getBlockTicks().tick(time, 65536, (pos, block) -> invoke(tickBlock, pos, block));
            oracle.getFluidTicks().tick(time, 65536, (pos, fluid) -> invoke(tickFluid, pos, fluid));
            for (Entity v : oracle.addedEntities()) {
                if (!v.isRemoved()) {
                    v.checkDespawn();
                    oracle.tickNonPassenger(v);
                }
            }
        }

        private void invoke(Method method, Object pos, Object type) {
            try {
                method.invoke(oracle, pos, type);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        // ---- comparing ----

        /** One twin: Kestrel's entity, vanilla's, and their trackers' packet streams. */
        final class Twin {
            final KestrelMovingEntity k;
            final Entity v;
            final List<String> kPackets = new ArrayList<>();
            final List<String> vPackets = new ArrayList<>();
            EntitySync kSync;
            ServerEntity vSync;

            Twin(KestrelMovingEntity k, Entity v) {
                this.k = k;
                this.v = v;
            }

            /** The tracker that starts watching a live entity: its pairing bundle, then its changes. */
            void track() {
                kSync = new EntitySync(k, k.getType().updateInterval(), k.getType().trackDeltas(),
                        p -> kPackets.add(fmt(p)));
                vSync = new ServerEntity(oracle, v, v.getType().updateInterval(), v.getType().trackDeltas(),
                        ItemEntityTickCheck.capture(vPackets));
                List<Packet<? super ClientGamePacketListener>> pairing = new ArrayList<>();
                vSync.sendPairingData(null, pairing::add);
                List<String> vanilla = new ArrayList<>();
                pairing.forEach(p -> vanilla.addAll(ItemEntityTickCheck.encodeVanilla(p)));
                List<String> kestrel = kSync.pairingBundle().stream().map(FallingBlockParityCheck::fmt).toList();
                expect(kestrel.subList(1, kestrel.size() - 1).equals(vanilla),
                        "pairing packets of entity " + k.getId() + " kestrel=" + kestrel + " vanilla=" + vanilla);
            }
        }

        private void compareEntities() {
            List<Entity> vanilla = oracle.addedEntities();
            if (vanilla.size() != kEntities.size()) {
                expect(false, "tick " + tick + " entity count kestrel=" + kEntities.size() + " vanilla=" + vanilla.size()
                        + " kestrel=" + kEntities.stream().map(e -> e.getType().toString()).toList());
            }
            while (twins.size() < Math.min(vanilla.size(), kEntities.size())) {
                Twin twin = new Twin(kEntities.get(twins.size()), vanilla.get(twins.size()));
                twins.add(twin);
                if (!twin.k.isRemoved()) {
                    twin.track();
                }
            }
            for (int i = 0; i < twins.size(); i++) {
                Twin t = twins.get(i);
                if (t.k.isRemoved() && t.v.isRemoved() && removedAt.containsKey(t.k)) {
                    continue;
                }
                compare("tick " + tick + " entity " + i, t.k, t.v);
                if (t.k.isRemoved() && !removedAt.containsKey(t.k)) {
                    removedAt.put(t.k, tick);
                }
                // The tracker's next pass (ServerLevel.java:402) comes before the next entity pass;
                // a removed entity is no longer tracked.
                if (freezeG68 && t.v.getTicksFrozen() > 0 && t.k instanceof KestrelFallingBlockEntity) {
                    freezeCopies++;
                    t.k.setTicksFrozen(t.v.getTicksFrozen());
                }
                if (t.kSync != null && !t.k.isRemoved()) {
                    t.kSync.sendChanges();
                }
                if (t.vSync != null && !t.v.isRemoved()) {
                    t.vSync.sendChanges();
                }
            }
        }

        private void compare(String where, KestrelMovingEntity k, Entity v) {
            List<String> d = new ArrayList<>();
            eq(d, "type", k.getType(), v.getType());
            eq(d, "id", k.getId(), v.getId());
            eq(d, "uuid", k.getUUID(), v.getUUID());
            bits(d, "x", k.position().x, v.getX());
            bits(d, "y", k.position().y, v.getY());
            bits(d, "z", k.position().z, v.getZ());
            bits(d, "dx", k.getDeltaMovement().x, v.getDeltaMovement().x);
            bits(d, "dy", k.getDeltaMovement().y, v.getDeltaMovement().y);
            bits(d, "dz", k.getDeltaMovement().z, v.getDeltaMovement().z);
            bits(d, "fall", k.getFallDistance(), v.fallDistance);
            eq(d, "ground", k.onGround(), v.onGround());
            eq(d, "removed", k.isRemoved(), v.isRemoved());
            if (k instanceof KestrelFallingBlockEntity kf && v instanceof FallingBlockEntity vf) {
                eq(d, "time", kf.getTime(), vf.time);
                eq(d, "state", Block.getId(kf.getBlockState()), Block.getId(vf.getBlockState()));
                eq(d, "start", kf.getStartPos(), vf.getStartPos());
            } else if (k instanceof KestrelItemEntity ki && v instanceof ItemEntity vi) {
                bits(d, "yRot", ki.getYRot(), vi.getYRot());
                bits(d, "bobOffs", ki.bobOffs, vi.bobOffs);
                eq(d, "age", ki.getAge(), vi.getAge());
                eq(d, "pickupDelay", ki.getPickupDelay(), get(field(ItemEntity.class, "pickupDelay"), vi));
                eq(d, "stack", ki.getItem().toString(), vi.getItem().toString());
            }
            checks += 17;
            if (!d.isEmpty()) {
                failures.add(name + " " + where + ": " + String.join(", ", d));
            }
        }

        void finish() {
            for (Twin t : twins) {
                expect(t.kPackets.equals(t.vPackets), "packets of entity " + t.k.getId() + " kestrel=" + t.kPackets
                        + " vanilla=" + t.vPackets);
            }
            same("writes", kWrites, oracle.detailedLog());
            same("write and spawn order", kActions, oracle.entityActions());
            same("level events", loop.dumpLevelEvents().stream()
                    .map(e -> e.type() + ":" + e.pos().asLong() + ":" + e.data()).toList(), oracle.levelEvents());
            same("landing packets", kLanding, oracle.trackingPackets().stream()
                    .flatMap(p -> ItemEntityTickCheck.encodeVanilla(p).stream()).toList());
            same("sounds and entity events", kEffects, oracle.effects());
            same("pending block ticks", loop.dumpBlockTicks().stream()
                    .map(t -> Block.getId(t.block().defaultBlockState()) + ":" + t.pos().asLong() + ":"
                            + t.triggerTick() + ":" + t.priority().getValue() + ":" + t.subTickOrder()).toList(),
                    oracle.pendingBlockTicks().stream().map(t -> Block.getId(t.type().defaultBlockState()) + ":"
                            + t.pos().asLong() + ":" + t.triggerTick() + ":" + t.priority().getValue() + ":"
                            + t.subTickOrder()).toList());
            same("pending fluid ticks", loop.dumpFluidQueue().stream()
                    .map(t -> BuiltInRegistries.FLUID.getId(t.fluid()) + ":" + t.pos().toVanilla().asLong() + ":"
                            + t.triggerTick()).toList(),
                    oracle.pendingFluidTicks().stream().map(t -> BuiltInRegistries.FLUID.getId(t.type()) + ":"
                            + t.pos().asLong() + ":" + t.triggerTick()).toList());
            List<String> cells = new ArrayList<>();
            for (int y = 55; y <= 145; y++) {
                for (int x = -4; x <= 4; x++) {
                    for (int z = -4; z <= 4; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (store.get(pos) != oracle.getBlockState(pos)) {
                            cells.add(pos.toShortString() + " kestrel=" + store.get(pos) + " vanilla="
                                    + oracle.getBlockState(pos));
                        }
                    }
                }
            }
            checks += 9 * 9 * 91 - 1;
            expect(cells.isEmpty(), "final cells " + cells);
            long kDraw = loop.levelRandom().nextLong();
            long vDraw = oracle.getRandom().nextLong();
            expect(kDraw == vDraw, "level random out of step: kestrel drew " + kDraw + ", vanilla " + vDraw);
            if (freezeG68) {
                expect(freezeCopies == 1, "G68 freeze was copied " + freezeCopies + " times, expected once");
            }
            for (Consumer<Arena> check : afterChecks) {
                check.accept(this);
            }
        }

        void same(String what, Object kestrel, Object vanilla) {
            expect(kestrel.equals(vanilla), what + " kestrel=" + kestrel + " vanilla=" + vanilla);
        }

        void expect(boolean ok, String message) {
            checks++;
            if (!ok) {
                failures.add(name + ": " + message);
            }
        }
    }

    /** The state {@code random} was in {@code draws} {@code next} calls ago, as a fresh source. */
    static RandomSource rewound(RandomSource random, int draws) {
        long state = ((AtomicLong) get(field(LegacyRandomSource.class, "seed"), random)).get();
        for (int i = 0; i < draws; i++) {
            state = (state - 0xBL) * MULTIPLIER_INVERSE & MASK;
        }
        return new LegacyRandomSource(state ^ MULTIPLIER);
    }

    // =====================================================================
    // FallingBlock.falling (defect 8) and level-event delivery (defect 6)
    // =====================================================================

    /**
     * Vanilla {@code FallingBlock.falling} on a real {@code FallingBlockEntity}
     * against Kestrel's, for every registered block that is a
     * {@code FallingBlock}: the three hurt fields must agree.
     */
    static void checkFallingHooks() throws Exception {
        Arena a = new Arena("falling hooks");
        Method falling = net.minecraft.world.level.block.FallingBlock.class
                .getDeclaredMethod("falling", FallingBlockEntity.class);
        falling.setAccessible(true);
        int blocks = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof net.minecraft.world.level.block.FallingBlock)) {
                continue;
            }
            blocks++;
            BlockState state = block.defaultBlockState();
            FallingBlockEntity v = new FallingBlockEntity(net.minecraft.world.entity.EntityTypes.FALLING_BLOCK, a.oracle);
            falling.invoke(block, v);
            KestrelFallingBlockEntity k = KestrelFallingBlockEntity.create(BlockPos.ZERO, state, RandomSource.create(0L));
            k.falling(a.level, block);
            String name = BuiltInRegistries.BLOCK.getKey(block).toString();
            expect(k.hurtEntities() == (Boolean) get(field(FallingBlockEntity.class, "hurtEntities"), v)
                    && k.fallDamagePerDistance() == (Float) get(field(FallingBlockEntity.class, "fallDamagePerDistance"), v)
                    && k.fallDamageMax() == (Integer) get(field(FallingBlockEntity.class, "fallDamageMax"), v),
                    "falling hook " + name + ": kestrel hurts=" + k.hurtEntities() + " per=" + k.fallDamagePerDistance()
                            + " max=" + k.fallDamageMax() + ", vanilla hurts="
                            + get(field(FallingBlockEntity.class, "hurtEntities"), v) + " per="
                            + get(field(FallingBlockEntity.class, "fallDamagePerDistance"), v) + " max="
                            + get(field(FallingBlockEntity.class, "fallDamageMax"), v));
            EntityIds.release(k.getId());
        }
        // ColoredFallingBlock (sand, red sand, gravel), 16 ConcretePowderBlock, three AnvilBlock, DragonEggBlock.
        // Suspicious sand and gravel are BrushableBlock, not FallingBlock.
        expect(blocks == 23, "expected 23 registered FallingBlock blocks, found " + blocks);
        System.out.println("  falling hooks: " + blocks + " registered FallingBlock blocks agree with vanilla's falling()");
    }

    /**
     * {@code ServerLevel.levelEvent} over {@code PlayerList.broadcast}
     * ({@code ServerLevel.java:1109-1122}, {@code PlayerList.java:604-624}):
     * the event at (10, 64, 10); players, in list order: one 63.9 blocks away
     * (receives), one exactly 64 away (the test is {@code <}, so not), one
     * beside the event in another dimension (not), the source player (never),
     * and one more in range (receives, after the first). The bytes are
     * vanilla's own {@code ClientboundLevelEventPacket} codec's.
     */
    static void checkLevelEventDelivery() {
        record Player(String name, Object dimension, double x, double y, double z, List<String> log)
                implements dev.kestrel.net.LevelEventBroadcast.Receiver {
            @Override public void send(Outbound.Packet packet) { log.add(name + "=" + fmt(packet)); }
        }
        List<String> log = new ArrayList<>();
        Object overworld = "overworld";
        Object nether = "the_nether";
        Player near = new Player("near", overworld, 10.0 + 63.9, 64.0, 10.0, log);
        Player edge = new Player("edge", overworld, 10.0, 64.0, 10.0 + 64.0, log);
        Player elsewhere = new Player("elsewhere", nether, 10.0, 64.0, 10.0, log);
        Player source = new Player("source", overworld, 10.0, 65.0, 10.0, log);
        Player last = new Player("last", overworld, 12.0, 64.0, 12.0, log);
        BlockPos pos = new BlockPos(10, 64, 10);
        dev.kestrel.net.LevelEventBroadcast.broadcast(List.of(near, edge, elsewhere, source, last), source, overworld,
                1031, pos, 0);
        List<String> packet = ItemEntityTickCheck.encodeVanilla(
                new net.minecraft.network.protocol.game.ClientboundLevelEventPacket(1031, pos, 0, false));
        expect(packet.size() == 1, "level event: vanilla encodes one packet");
        List<String> expected = List.of("near=" + packet.get(0), "last=" + packet.get(0));
        expect(log.equals(expected), "level event delivery kestrel=" + log + " expected=" + expected);
        // The live loop keeps nothing: with a sink and no gate capture, events are delivered, not stored.
        TickLoop live = new TickLoop(new SectionStore(driver.height().getMinY(), driver.height().getHeight()));
        List<String> delivered = new ArrayList<>();
        live.setLevelEventSink((type, at, data) -> delivered.add(type + ":" + at.asLong()));
        for (int i = 0; i < 1000; i++) {
            live.emitLevelEvent(2001, pos, i);
        }
        expect(delivered.size() == 1000 && get(field(TickLoop.class, "levelEvents"), live) == null,
                "level events: the live loop stored events without a gate listener");
        System.out.println("  level events: " + log);
    }

    // ---- helpers ----

    static void expect(boolean ok, String message) {
        checks++;
        if (!ok) {
            failures.add(message);
        }
    }

    static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void set(Field f, Object target, Object value) {
        try {
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object get(Field f, Object target) {
        try {
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static String fmt(Outbound.Packet p) {
        return p.id() + ":" + HexFormat.of().formatHex(p.payload());
    }

    static void bits(List<String> d, String name, double k, double v) {
        if (Double.doubleToRawLongBits(k) != Double.doubleToRawLongBits(v)) {
            d.add(name + " " + k + " vs " + v);
        }
    }

    static void eq(List<String> d, String name, Object k, Object v) {
        if (!Objects.equals(k, v)) {
            d.add(name + " " + k + " vs " + v);
        }
    }

    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
