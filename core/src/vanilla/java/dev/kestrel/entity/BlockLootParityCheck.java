package dev.kestrel.entity;

import dev.kestrel.collision.ShapeBoxCache;
import dev.kestrel.loop.LootTables;
import dev.kestrel.worldgen.HeadlessNoise;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** G19 slice 1 gate: loaded vanilla loot, tool conditions, pop geometry, XP and tracking. */
public final class BlockLootParityCheck {
    private static final BlockPos POS = new BlockPos(3, 70, -5);
    private static final List<String> failures = new ArrayList<>();
    private static long checks;

    private BlockLootParityCheck() { }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(0x19_0001L)) {
            RegistryAccess access = structural.driver().access();
            ReloadableServerRegistries.Holder loot = LootTables.load(access, structural.resources());
            int covered = 0;
            int nonEmpty = 0;
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block == Blocks.AIR || block instanceof InfestedBlock || covered >= 128) continue;
                if (!compare(block.defaultBlockState(), new ItemStack(Items.IRON_PICKAXE), 0x190000L + covered,
                        access, loot, "catalogue/" + BuiltInRegistries.BLOCK.getKey(block)).isEmpty()) {
                    nonEmpty++;
                }
                covered++;
            }
            expect(covered >= 100, "catalogue covered " + covered + " blocks");
            expect(nonEmpty >= 80, "catalogue: only " + nonEmpty + " of " + covered + " blocks dropped anything");
            known(access, loot);

            ItemStack hand = ItemStack.EMPTY;
            ItemStack wrong = new ItemStack(Items.WOODEN_PICKAXE);
            ItemStack iron = new ItemStack(Items.IRON_PICKAXE);
            ItemStack shears = new ItemStack(Items.SHEARS);
            List<Block> ores = List.of(Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.GOLD_ORE,
                    Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE,
                    Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_GOLD_ORE);
            long seed = 0x19F0_0000L;
            for (Block ore : ores) {
                compare(ore.defaultBlockState(), hand, seed++, access, loot, ore + "/hand");
                compare(ore.defaultBlockState(), wrong, seed++, access, loot, ore + "/wrong");
                compare(ore.defaultBlockState(), iron, seed++, access, loot, ore + "/iron");
                for (int level = 1; level <= 3; level++) {
                    ItemStack fortune = new ItemStack(Items.IRON_PICKAXE);
                    fortune.enchant(access.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), level);
                    compare(ore.defaultBlockState(), fortune, seed++, access, loot, ore + "/fortune" + level);
                }
                ItemStack silk = new ItemStack(Items.IRON_PICKAXE);
                silk.enchant(access.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1);
                compare(ore.defaultBlockState(), silk, seed++, access, loot, ore + "/silk");
            }
            compare(Blocks.OAK_LEAVES.defaultBlockState(), shears, seed++, access, loot, "leaves/shears");
            compare(Blocks.COBWEB.defaultBlockState(), shears, seed++, access, loot, "cobweb/shears");
            gamerule(access, loot);
            watcher(access, loot);
            sequenceOnly(access, loot);
            tallGrass(access, loot);
        }
        if (!failures.isEmpty()) {
            System.out.println("BlockLootParityCheck FAILED with " + failures.size() + " failure(s):");
            failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
        System.out.println("BlockLootParityCheck: PASSED (" + checks + " assertions)");
    }

    private static List<String> compare(BlockState state, ItemStack tool, long seed, RegistryAccess access,
            ReloadableServerRegistries.Holder loot, String name) {
        Arena a = new Arena(access, loot, seed, null);
        ServerPlayer player = KestrelServerPlayer.lootOwner(a);
        List<ItemStack> expected = Block.getDrops(state,
                (net.minecraft.server.level.ServerLevel) player.level(), POS, null, player, tool);
        a.reset(seed);
        Block.dropResources(state, player.level(), POS, null, player, tool.copy());
        List<ItemStack> actual = a.added.stream().filter(KestrelItemEntity.class::isInstance)
                .map(KestrelItemEntity.class::cast).map(KestrelItemEntity::getItem).toList();
        expect(stacks(expected).equals(stacks(actual)), name + " drops " + stacks(actual) + " != " + stacks(expected));
        List<KestrelItemEntity> items = a.added.stream().filter(KestrelItemEntity.class::isInstance)
                .map(KestrelItemEntity.class::cast).toList();
        expect(items.size() == a.vanillaItems.size(), name + " bridged item count");
        for (int i = 0; i < Math.min(items.size(), a.vanillaItems.size()); i++) {
            KestrelItemEntity item = items.get(i);
            net.minecraft.world.entity.item.ItemEntity vanilla = a.vanillaItems.get(i);
            expect(item.position().equals(vanilla.position()), name + " pop position differs");
            expect(item.getDeltaMovement().equals(vanilla.getDeltaMovement()), name + " pop motion differs");
        }
        for (KestrelEntity entity : a.added) {
            if (entity instanceof KestrelItemEntity item) {
                expect(item.getPickupDelay() == 10, name + " pickup delay " + item.getPickupDelay());
                expect(item.position().x >= POS.getX() + .25 && item.position().x < POS.getX() + .75,
                        name + " pop x " + item.position().x);
                expect(item.position().y >= POS.getY() + .125 && item.position().y < POS.getY() + .625,
                        name + " pop y " + item.position().y);
            }
        }
        if (tool.getEnchantments().getLevel(access.lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SILK_TOUCH)) > 0) {
            expect(a.orbs.isEmpty(), name + " awarded XP with Silk Touch: " + a.orbs);
        }
        lastXp = a.orbs.stream().mapToInt(Integer::intValue).sum();
        expect(a.added.stream().allMatch(KestrelItemEntity.class::isInstance),
                name + " spawned a non-item Kestrel entity (XP orbs wait for G26)");
        return stacks(actual);
    }

    /** XP awarded by the last {@link #compare} (vanilla orbs, not spawned until G26). */
    private static int lastXp;

    /** Fixed expectations read from vanilla's own loot tables, independent of the code under test. */
    private static void known(RegistryAccess access, ReloadableServerRegistries.Holder loot) {
        ItemStack iron = new ItemStack(Items.IRON_PICKAXE);
        ItemStack silk = new ItemStack(Items.IRON_PICKAXE);
        silk.enchant(access.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1);
        ItemStack shears = new ItemStack(Items.SHEARS);
        long seed = 0x19AA_0000L;
        // blocks/stone.json: cobblestone unless silk touch.
        expectDrops("known/stone", compare(Blocks.STONE.defaultBlockState(), iron, seed++, access, loot, "known/stone"),
                "minecraft:cobblestonex1");
        expectDrops("known/stone-silk", compare(Blocks.STONE.defaultBlockState(), silk, seed++, access, loot,
                "known/stone-silk"), "minecraft:stonex1");
        // blocks/diamond_ore.json: one diamond (no fortune), the ore with silk touch.
        expectDrops("known/diamond", compare(Blocks.DIAMOND_ORE.defaultBlockState(), iron, seed++, access, loot,
                "known/diamond"), "minecraft:diamondx1");
        // Block.java:438-450 + DropExperienceBlock(UniformInt 3..7) for diamond ore.
        expect(lastXp >= 3 && lastXp <= 7, "known/diamond XP " + lastXp + " outside 3..7");
        expectDrops("known/diamond-silk", compare(Blocks.DIAMOND_ORE.defaultBlockState(), silk, seed++, access, loot,
                "known/diamond-silk"), "minecraft:diamond_orex1");
        expect(lastXp == 0, "known/diamond-silk XP " + lastXp);
        expectDrops("known/iron", compare(Blocks.IRON_ORE.defaultBlockState(), iron, seed++, access, loot,
                "known/iron"), "minecraft:raw_ironx1");
        expect(lastXp == 0, "known/iron XP " + lastXp + " (iron ore gives none)");
        expectDrops("known/coal", compare(Blocks.COAL_ORE.defaultBlockState(), iron, seed++, access, loot,
                "known/coal"), "minecraft:coalx1");
        expect(lastXp >= 0 && lastXp <= 2, "known/coal XP " + lastXp + " outside 0..2");
        expectDrops("known/leaves-shears", compare(Blocks.OAK_LEAVES.defaultBlockState(), shears, seed++, access, loot,
                "known/leaves-shears"), "minecraft:oak_leavesx1");
        expectDrops("known/cobweb-shears", compare(Blocks.COBWEB.defaultBlockState(), shears, seed++, access, loot,
                "known/cobweb-shears"), "minecraft:cobwebx1");
        expectDrops("known/dirt", compare(Blocks.DIRT.defaultBlockState(), ItemStack.EMPTY, seed++, access, loot,
                "known/dirt"), "minecraft:dirtx1");
        // Fortune III on diamond ore (ore_drops bonus): over 40 seeds at least one roll above one.
        ItemStack fortune = new ItemStack(Items.IRON_PICKAXE);
        fortune.enchant(access.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), 3);
        int max = 0;
        for (int n = 0; n < 40; n++) {
            for (String stack : compare(Blocks.DIAMOND_ORE.defaultBlockState(), fortune, seed++, access, loot,
                    "known/fortune3-" + n)) {
                max = Math.max(max, Integer.parseInt(stack.substring(stack.indexOf('x') + 1)));
            }
        }
        expect(max > 1 && max <= 4, "known/fortune3 diamond max count " + max + " (vanilla: 1..4)");
    }

    private static void expectDrops(String name, List<String> actual, String... expected) {
        expect(actual.equals(List.of(expected)), name + " drops " + actual + ", vanilla's table gives "
                + List.of(expected));
    }

    /**
     * {@code LootContext.java:143}: a table with a {@code random_sequence} rolls from
     * {@code MinecraftServer.getRandomSequence} (world seed + table id), never the level's
     * random. Two levels with the same seed but different level randoms drop the same.
     */
    private static void sequenceOnly(RegistryAccess access, ReloadableServerRegistries.Holder loot) {
        ItemStack fortune = new ItemStack(Items.IRON_PICKAXE);
        fortune.enchant(access.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), 3);
        int differing = 0;
        java.util.Set<List<String>> seen = new java.util.HashSet<>();
        for (long n = 0; n < 40; n++) {
            List<List<String>> runs = new ArrayList<>();
            for (long levelSeed : new long[] {1L, 2L}) {
                Arena a = new Arena(access, loot, 0x19BB_0000L + n, null);
                a.random = RandomSource.create(levelSeed * 7919L + n);
                ServerPlayer player = KestrelServerPlayer.lootOwner(a);
                Block.dropResources(Blocks.DIAMOND_ORE.defaultBlockState(), player.level(), POS, null, player,
                        fortune.copy());
                runs.add(stacks(a.added.stream().map(e -> ((KestrelItemEntity) e).getItem()).toList()));
            }
            if (!runs.get(0).equals(runs.get(1))) differing++;
            seen.add(runs.get(0));
        }
        expect(differing == 0, "sequence: " + differing + " of 40 drops depended on the level random");
        expect(seen.size() > 1, "sequence: Fortune III diamond rolls never varied with the world seed " + seen);
    }

    /**
     * blocks/tall_grass.json: the lower half drops two short grass to shears only while the
     * {@code location_check} one block up finds the upper half ({@code Level.isLoaded} first).
     */
    private static void tallGrass(RegistryAccess access, ReloadableServerRegistries.Holder loot) {
        BlockState lower = Blocks.TALL_GRASS.defaultBlockState();
        BlockState upper = lower.setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        for (boolean withUpper : new boolean[] {true, false}) {
            Arena a = new Arena(access, loot, 0x19CC_0000L, null);
            a.placed.put(POS, lower);
            if (withUpper) a.placed.put(POS.above(), upper);
            ServerPlayer player = KestrelServerPlayer.lootOwner(a);
            Block.dropResources(lower, player.level(), POS, null, player, new ItemStack(Items.SHEARS));
            List<String> got = stacks(a.added.stream().map(e -> ((KestrelItemEntity) e).getItem()).toList());
            expectDrops("known/tall-grass-shears" + (withUpper ? "" : "-no-upper"), got,
                    withUpper ? new String[] {"minecraft:short_grassx2"} : new String[0]);
        }
    }

    private static void gamerule(RegistryAccess access, ReloadableServerRegistries.Holder loot) {
        Arena a = new Arena(access, loot, 19L, null);
        a.rules.set(GameRules.BLOCK_DROPS, false, null);
        ServerPlayer player = KestrelServerPlayer.lootOwner(a);
        Block.dropResources(Blocks.DIAMOND_ORE.defaultBlockState(), player.level(), POS, null, player,
                new ItemStack(Items.IRON_PICKAXE));
        expect(a.added.isEmpty() && a.orbs.isEmpty(), "block_drops=false spawned " + a.added.size() + " entities, XP " + a.orbs);
    }

    private static void watcher(RegistryAccess access, ReloadableServerRegistries.Holder loot) {
        List<dev.kestrel.net.Outbound.Packet> packets = new ArrayList<>();
        EntityTracker tracker = new EntityTracker(100);
        tracker.addPlayer(new TrackingObserver() {
            public int entityId() { return 99; }
            public double x() { return POS.getX(); }
            public double y() { return POS.getY(); }
            public double z() { return POS.getZ(); }
            public int viewDistance() { return 10; }
            public boolean isChunkTracked(int x, int z) { return true; }
            public void send(dev.kestrel.net.Outbound.Packet packet) { packets.add(packet); }
        });
        Arena a = new Arena(access, loot, 1919L, tracker);
        ServerPlayer player = KestrelServerPlayer.lootOwner(a);
        Block.dropResources(Blocks.STONE.defaultBlockState(), player.level(), POS, null, player,
                new ItemStack(Items.IRON_PICKAXE));
        expect(!packets.isEmpty(), "watcher received no item entity pairing bundle");
    }

    private static List<String> stacks(List<ItemStack> stacks) {
        return stacks.stream().map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()) + "x" + s.getCount())
                .sorted(Comparator.naturalOrder()).toList();
    }
    private static void expect(boolean value, String message) { checks++; if (!value) failures.add(message); }

    private static final class Arena implements EntityLevel {
        final RegistryAccess access;
        final ReloadableServerRegistries.Holder loot;
        final GameRules rules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
        final List<KestrelEntity> added = new ArrayList<>();
        final List<net.minecraft.world.entity.item.ItemEntity> vanillaItems = new ArrayList<>();
        final List<Integer> orbs = new ArrayList<>();
        final java.util.Map<BlockPos, BlockState> placed = new java.util.HashMap<>();
        RandomSource random;
        net.minecraft.world.RandomSequences sequences = new net.minecraft.world.RandomSequences();
        final long seed;
        final EntityWorld world;
        Arena(RegistryAccess access, ReloadableServerRegistries.Holder loot, long seed, @Nullable EntityTracker tracker) {
            this.access = access; this.loot = loot; this.random = RandomSource.create(seed); this.seed = seed;
            this.world = new EntityWorld(this, tracker);
        }
        void reset(long seed) {
            random = RandomSource.create(seed); sequences = new net.minecraft.world.RandomSequences();
            added.clear(); vanillaItems.clear(); orbs.clear();
        }
        public RandomSource randomSequence(net.minecraft.resources.Identifier key) { return sequences.get(key, seed); }
        public BlockGetter blocks() { return new BlockGetter() {
            public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
            public BlockState getBlockState(BlockPos pos) { return placed.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
            public FluidState getFluidState(BlockPos pos) { return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState(); }
            public int getMinY() { return -64; } public int getHeight() { return 384; }
        }; }
        public ShapeBoxCache shapeCache() { return null; }
        public int getMinY() { return -64; }
        public LevelReader levelReader() { return VanillaEntityLevel.of(this); }
        public boolean setBlock(BlockPos pos, BlockState state, int flags) { return true; }
        public void levelEvent(int type, BlockPos pos, int data) { }
        public void landingBlockUpdate(KestrelEntity entity, BlockPos pos, BlockState state) { }
        public RandomSource random() { return random; }
        public GameRules gameRules() { return rules; }
        public void playSound(double x, double y, double z, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound,
                net.minecraft.sounds.SoundSource source, float volume, float pitch) { }
        public void entityEvent(KestrelEntity entity, byte event) { }
        public void entityAdded(KestrelEntity entity) { added.add(entity); }
        public RegistryAccess registryAccess() { return access; }
        public ReloadableServerRegistries.Holder reloadableRegistries() { return loot; }
        public boolean addFreshVanillaEntity(net.minecraft.world.entity.Entity entity) {
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity item) vanillaItems.add(item);
            if (entity instanceof net.minecraft.world.entity.ExperienceOrb orb) orbs.add(orb.getValue());
            return world.addFreshVanillaEntity(entity);
        }
    }
}
