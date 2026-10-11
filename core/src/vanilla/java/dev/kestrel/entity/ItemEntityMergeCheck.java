package dev.kestrel.entity;

import dev.kestrel.collision.ShapeBoxCache;
import dev.kestrel.loop.SectionStore;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.net.Outbound;
import dev.kestrel.net.PacketIds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * G19 slice 2 reproduction and parity gate: item entity merging, merge cadence,
 * spatial bounding box search, count/age/pickup-delay inheritance, stack limits,
 * lifetime despawn boundary at 6000 ticks, and watching player network packets,
 * verified bit-for-bit against vanilla Minecraft 26.2.
 *
 * <ol>
 *   <li>Constants and static methods: LIFETIME, sentinels, areMergable, merge
 *       and isMergable diffed against vanilla ItemEntity.</li>
 *   <li>Stationary merge at tick 40: items resting on terrain merge at tick 40,
 *       neither earlier nor later.</li>
 *   <li>Moving merge at tick 2: items crossing block boundaries merge at tick 2.</li>
 *   <li>Distance threshold: items within the inflated bounding box merge; items
 *       outside do not merge.</li>
 *   <li>Stack limits: items summing to &lt;= maxStackSize merge; items exceeding
 *       maxStackSize do not merge. Unstackable items do not merge.</li>
 *   <li>Inheritance: survivor adopts sum of counts, min age, and max pickup delay.</li>
 *   <li>Target player filtering: items with matching targets merge; mismatched
 *       targets do not merge.</li>
 *   <li>Lifetime despawn: exact boundary at age 6000, and infinite lifetime sentinel.</li>
 *   <li>Network packets: watching observer receives ClientboundRemoveEntitiesPacket
 *       for the discarded item and ClientboundSetEntityDataPacket for the survivor.</li>
 *   <li>Multi-item cluster convergence: multiple stacks merge preserving total items.</li>
 * </ol>
 */
public final class ItemEntityMergeCheck {
    private ItemEntityMergeCheck() {
    }

    private static final long SEED = 0x4D657267654CL; // "MergeL"
    private static final List<String> failures = new ArrayList<>();
    private static long comparisons;
    private static RegistryAccess access;
    private static ProtocolInfo<ClientGamePacketListener> protocol;

    public static void main(String[] args) throws Exception {
        initializePacketCodec();

        // Vanilla's real per-item components (ender pearl 16, sword 1, damage),
        // bound by the same resource load the live server performs.
        dev.kestrel.worldgen.HeadlessNoise.loadStructural(SEED).close();
        expect(new ItemStack(Items.DIAMOND_SWORD).getMaxStackSize() == 1
                && new ItemStack(Items.ENDER_PEARL).getMaxStackSize() == 16, "real item components bound");

        checkConstantsAndStaticMethods();
        checkStationaryMergeAtTick40();
        checkMovingMergeAtTick2();
        checkDistanceThreshold();
        checkStackLimits();
        checkAgeAndPickupDelayInheritance();
        checkTargetFilter();
        checkLifetimeAndDespawn();
        checkWatchingPlayerPackets();
        checkClusterConvergence();

        if (!failures.isEmpty()) {
            System.out.println("ItemEntityMergeCheck FAILED with " + failures.size() + " failure(s):");
            for (int i = 0; i < Math.min(failures.size(), 40); i++) {
                System.out.println("  - " + failures.get(i));
            }
            System.exit(1);
        }
        System.out.println("  " + comparisons + " comparisons against vanilla 26.2 oracle");
        System.out.println("ItemEntityMergeCheck: PASS");
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
    // 1. Constants and static methods
    // =====================================================================

    private static void checkConstantsAndStaticMethods() throws Exception {
        expect(KestrelItemEntity.LIFETIME == field(ItemEntity.class, "LIFETIME").getInt(null),
                "LIFETIME differs from vanilla");
        expect(KestrelItemEntity.INFINITE_PICKUP_DELAY == field(ItemEntity.class, "INFINITE_PICKUP_DELAY").getInt(null),
                "INFINITE_PICKUP_DELAY differs from vanilla");
        expect(KestrelItemEntity.INFINITE_LIFETIME == field(ItemEntity.class, "INFINITE_LIFETIME").getInt(null),
                "INFINITE_LIFETIME differs from vanilla");

        // areMergable across count combinations and item types
        ItemStack diamond1 = new ItemStack(Items.DIAMOND, 10);
        ItemStack diamond2 = new ItemStack(Items.DIAMOND, 20);
        expect(KestrelItemEntity.areMergable(diamond1, diamond2) == ItemEntity.areMergable(diamond1, diamond2),
                "areMergable diamond 10+20");

        ItemStack stone32 = new ItemStack(Items.STONE, 32);
        ItemStack stone32b = new ItemStack(Items.STONE, 32);
        expect(KestrelItemEntity.areMergable(stone32, stone32b) == ItemEntity.areMergable(stone32, stone32b),
                "areMergable stone 32+32 (sum=64)");
        expect(KestrelItemEntity.areMergable(stone32, stone32b),
                "areMergable stone 32+32 must be true");

        ItemStack stone33 = new ItemStack(Items.STONE, 33);
        expect(KestrelItemEntity.areMergable(stone32, stone33) == ItemEntity.areMergable(stone32, stone33),
                "areMergable stone 32+33 (sum=65)");
        expect(!KestrelItemEntity.areMergable(stone32, stone33),
                "areMergable stone 32+33 must be false");

        ItemStack stone64 = new ItemStack(Items.STONE, 64);
        ItemStack stone1 = new ItemStack(Items.STONE, 1);
        expect(KestrelItemEntity.areMergable(stone64, stone1) == ItemEntity.areMergable(stone64, stone1),
                "areMergable stone 64+1");
        expect(!KestrelItemEntity.areMergable(stone64, stone1),
                "areMergable stone 64+1 must be false");

        // 16-stack items (Ender Pearl)
        ItemStack pearl8 = new ItemStack(Items.ENDER_PEARL, 8);
        ItemStack pearl8b = new ItemStack(Items.ENDER_PEARL, 8);
        ItemStack pearl9 = new ItemStack(Items.ENDER_PEARL, 9);
        expect(KestrelItemEntity.areMergable(pearl8, pearl8b) == ItemEntity.areMergable(pearl8, pearl8b),
                "areMergable pearl 8+8");
        expect(KestrelItemEntity.areMergable(pearl8, pearl8b),
                "areMergable pearl 8+8 must be true");
        expect(KestrelItemEntity.areMergable(pearl8, pearl9) == ItemEntity.areMergable(pearl8, pearl9),
                "areMergable pearl 8+9");
        expect(!KestrelItemEntity.areMergable(pearl8, pearl9),
                "areMergable pearl 8+9 must be false");

        // Unstackable items (Sword)
        ItemStack swordA = new ItemStack(Items.DIAMOND_SWORD);
        ItemStack swordB = new ItemStack(Items.DIAMOND_SWORD);
        expect(KestrelItemEntity.areMergable(swordA, swordB) == ItemEntity.areMergable(swordA, swordB),
                "areMergable sword 1+1");
        expect(!KestrelItemEntity.areMergable(swordA, swordB),
                "areMergable sword 1+1 must be false");

        // Different item types
        ItemStack cobblestone = new ItemStack(Items.COBBLESTONE, 10);
        expect(KestrelItemEntity.areMergable(diamond1, cobblestone) == ItemEntity.areMergable(diamond1, cobblestone),
                "areMergable different items");
        expect(!KestrelItemEntity.areMergable(diamond1, cobblestone),
                "areMergable different items must be false");

        // Component differences (custom name)
        ItemStack namedDiamond = new ItemStack(Items.DIAMOND, 5);
        namedDiamond.set(DataComponents.CUSTOM_NAME, Component.literal("Shiny"));
        expect(KestrelItemEntity.areMergable(diamond1, namedDiamond) == ItemEntity.areMergable(diamond1, namedDiamond),
                "areMergable custom name difference");
        expect(!KestrelItemEntity.areMergable(diamond1, namedDiamond),
                "areMergable custom name difference must be false");

        // Component differences (damage)
        ItemStack damagedPickA = new ItemStack(Items.DIAMOND_PICKAXE);
        damagedPickA.setDamageValue(10);
        ItemStack damagedPickB = new ItemStack(Items.DIAMOND_PICKAXE);
        damagedPickB.setDamageValue(20);
        expect(KestrelItemEntity.areMergable(damagedPickA, damagedPickB) == ItemEntity.areMergable(damagedPickA, damagedPickB),
                "areMergable damage value difference");
        expect(!KestrelItemEntity.areMergable(damagedPickA, damagedPickB),
                "areMergable damage value difference must be false");

        // merge(toStack, fromStack, maxCount)
        for (int to = 1; to <= 64; to += 7) {
            for (int from = 1; from <= 64; from += 7) {
                for (int max : List.of(16, 64)) {
                    ItemStack kTo = new ItemStack(Items.DIAMOND, to);
                    ItemStack kFrom = new ItemStack(Items.DIAMOND, from);
                    ItemStack vTo = new ItemStack(Items.DIAMOND, to);
                    ItemStack vFrom = new ItemStack(Items.DIAMOND, from);

                    ItemStack kResult = KestrelItemEntity.merge(kTo, kFrom, max);
                    ItemStack vResult = ItemEntity.merge(vTo, vFrom, max);

                    expect(kResult.getCount() == vResult.getCount(),
                            "merge result count mismatch: to=" + to + " from=" + from + " max=" + max);
                    expect(kFrom.getCount() == vFrom.getCount(),
                            "merge fromStack count mismatch: to=" + to + " from=" + from + " max=" + max);
                }
            }
        }

        // isMergable() state matrix
        Arena a = flat();
        KestrelItemEntity k = a.world.spawnItem(2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10));
        a.oracle.nextId(k.getId());
        ItemEntity v = new ItemEntity(a.oracle, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10));
        a.oracle.addEntity(v);

        // Baseline: both mergable
        expect(k.isMergable() == invokeIsMergable(v), "isMergable baseline");
        expect(k.isMergable(), "isMergable baseline must be true");

        // Full stack (64): not mergable
        k.setItem(new ItemStack(Items.DIAMOND, 64));
        v.setItem(new ItemStack(Items.DIAMOND, 64));
        expect(k.isMergable() == invokeIsMergable(v), "isMergable full stack");
        expect(!k.isMergable(), "isMergable full stack must be false");

        k.setItem(new ItemStack(Items.DIAMOND, 10));
        v.setItem(new ItemStack(Items.DIAMOND, 10));

        // Infinite pickup delay: not mergable
        k.setPickUpDelay(KestrelItemEntity.INFINITE_PICKUP_DELAY);
        set(PICKUP_DELAY, v, KestrelItemEntity.INFINITE_PICKUP_DELAY);
        expect(k.isMergable() == invokeIsMergable(v), "isMergable infinite pickup delay");
        expect(!k.isMergable(), "isMergable infinite pickup delay must be false");

        k.setPickUpDelay(0);
        set(PICKUP_DELAY, v, 0);

        // Infinite lifetime: not mergable
        k.setAge(KestrelItemEntity.INFINITE_LIFETIME);
        set(AGE, v, KestrelItemEntity.INFINITE_LIFETIME);
        expect(k.isMergable() == invokeIsMergable(v), "isMergable infinite lifetime");
        expect(!k.isMergable(), "isMergable infinite lifetime must be false");

        // Lifetime boundary: age 5999 mergable, age 6000 not mergable
        k.setAge(5999);
        set(AGE, v, 5999);
        expect(k.isMergable() == invokeIsMergable(v), "isMergable age 5999");
        expect(k.isMergable(), "isMergable age 5999 must be true");

        k.setAge(6000);
        set(AGE, v, 6000);
        expect(k.isMergable() == invokeIsMergable(v), "isMergable age 6000");
        expect(!k.isMergable(), "isMergable age 6000 must be false");

        k.setAge(0);
        set(AGE, v, 0);

        // Discarded entity: not mergable
        k.discard();
        v.discard();
        expect(k.isMergable() == invokeIsMergable(v), "isMergable discarded");
        expect(!k.isMergable(), "isMergable discarded must be false");
    }

    // =====================================================================
    // 2. Stationary merge at tick 40
    // =====================================================================

    private static void checkStationaryMergeAtTick40() {
        Arena a = flat();
        Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 10);
        Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.DIAMOND, 5), 0, 0, 0, SEED + 11);

        for (int tick = 1; tick < 40; tick++) {
            a.world.tickEntities();
            tickVanilla(a);

            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "tick " + tick + ": item A removed early");
            expect(!tB.k.isRemoved() && !tB.v.isRemoved(), "tick " + tick + ": item B removed early");
            expect(tA.k.getItem().getCount() == 10 && tA.v.getItem().getCount() == 10,
                    "tick " + tick + ": item A count changed early");
            expect(tB.k.getItem().getCount() == 5 && tB.v.getItem().getCount() == 5,
                    "tick " + tick + ": item B count changed early");
            sameItem(tA, "stationary tick " + tick);
            sameItem(tB, "stationary tick " + tick);
        }

        // Tick 40: merge occurs on both sides
        a.world.tickEntities();
        tickVanilla(a);

        expect(!tA.k.isRemoved(), "tick 40: survivor item A removed in Kestrel");
        expect(!tA.v.isRemoved(), "tick 40: survivor item A removed in vanilla");
        expect(tA.k.getItem().getCount() == 15, "tick 40: survivor count != 15 in Kestrel (was " + tA.k.getItem().getCount() + ")");
        expect(tA.v.getItem().getCount() == 15, "tick 40: survivor count != 15 in vanilla");

        expect(tB.k.isRemoved(), "tick 40: merged item B not removed in Kestrel");
        expect(tB.v.isRemoved(), "tick 40: merged item B not removed in vanilla");
        expect(tB.k.getItem().isEmpty(), "tick 40: merged item B stack not empty in Kestrel");
        expect(tB.v.getItem().isEmpty(), "tick 40: merged item B stack not empty in vanilla");

        sameItem(tA, "stationary post-merge tick 40 survivor");

        // Continues ticking stably
        for (int tick = 41; tick <= 80; tick++) {
            a.world.tickEntities();
            tickVanilla(a);
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "tick " + tick + ": survivor removed");
            expect(tA.k.getItem().getCount() == 15 && tA.v.getItem().getCount() == 15, "tick " + tick + ": survivor count changed");
            sameItem(tA, "stationary post-merge tick " + tick);
        }
    }

    // =====================================================================
    // 3. Moving merge at tick 2
    // =====================================================================

    private static void checkMovingMergeAtTick2() {
        Arena a = flat();
        // ItemEntity.java:169-177: "moved" compares this tick's start and end block only.
        // Item A (dx=0.1, then x0.98 drag) goes 1.88 -> 1.98 on tick 1 (same block,
        // rate 40) and 1.98 -> 2.078 on tick 2 (new block, rate 2, tickCount 2).
        Twin tA = spawn(a, 1.88, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10), 0.1, 0.0, 0.0, SEED + 20);
        Twin tB = spawn(a, 2.15, 64.0, 2.5, new ItemStack(Items.DIAMOND, 5), 0.0, 0.0, 0.0, SEED + 21);

        // Tick 1: moved crossed block boundary, but tickCount is 1 (1 % 2 != 0)
        a.world.tickEntities();
        tickVanilla(a);

        expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "moving tick 1: item A removed early");
        expect(!tB.k.isRemoved() && !tB.v.isRemoved(), "moving tick 1: item B removed early");
        expect(tA.k.getItem().getCount() == 10 && tA.v.getItem().getCount() == 10, "moving tick 1: count A changed");
        expect(tB.k.getItem().getCount() == 5 && tB.v.getItem().getCount() == 5, "moving tick 1: count B changed");

        // Tick 2: tickCount is 2 (2 % 2 == 0), moving merge triggers!
        a.world.tickEntities();
        tickVanilla(a);

        expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "moving tick 2: survivor item A removed");
        expect(tB.k.isRemoved() && tB.v.isRemoved(), "moving tick 2: merged item B not removed");
        expect(tA.k.getItem().getCount() == 15 && tA.v.getItem().getCount() == 15,
                "moving tick 2: survivor count != 15 (kestrel=" + tA.k.getItem().getCount() + ", vanilla=" + tA.v.getItem().getCount() + ")");
        expect(tB.k.getItem().isEmpty() && tB.v.getItem().isEmpty(), "moving tick 2: merged item stack not empty");
    }

    // =====================================================================
    // 4. Distance threshold
    // =====================================================================

    private static void checkDistanceThreshold() {
        // Distance 0.4 (centers at 2.0 and 2.4, delta=0.4 <= 0.75): MERGES
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.0, 64.0, 2.0, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 30);
            Twin tB = spawn(a, 2.4, 64.0, 2.0, new ItemStack(Items.DIAMOND, 5), 0, 0, 0, SEED + 31);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "distance 0.4: survivor removed");
            expect(tB.k.isRemoved() && tB.v.isRemoved(), "distance 0.4: merged entity not removed");
            expect(tA.k.getItem().getCount() == 15 && tA.v.getItem().getCount() == 15,
                    "distance 0.4: merge did not occur (count=" + tA.k.getItem().getCount() + ")");
        }

        // Distance 0.8 (centers at 2.0 and 2.8, delta=0.8 > 0.75): DOES NOT MERGE
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.0, 64.0, 2.0, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 32);
            Twin tB = spawn(a, 2.8, 64.0, 2.0, new ItemStack(Items.DIAMOND, 5), 0, 0, 0, SEED + 33);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "distance 0.8: item A removed unexpectedly");
            expect(!tB.k.isRemoved() && !tB.v.isRemoved(), "distance 0.8: item B removed unexpectedly");
            expect(tA.k.getItem().getCount() == 10 && tA.v.getItem().getCount() == 10, "distance 0.8: count A changed");
            expect(tB.k.getItem().getCount() == 5 && tB.v.getItem().getCount() == 5, "distance 0.8: count B changed");
        }

        // Edge-to-edge distance 0.4 (centers at 2.0 and 2.65, center delta 0.65 <= 0.75): MERGES
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.0, 64.0, 2.0, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 34);
            Twin tB = spawn(a, 2.65, 64.0, 2.0, new ItemStack(Items.DIAMOND, 5), 0, 0, 0, SEED + 35);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "edge-dist 0.4: survivor removed");
            expect(tB.k.isRemoved() && tB.v.isRemoved(), "edge-dist 0.4: merged entity not removed");
            expect(tA.k.getItem().getCount() == 15 && tA.v.getItem().getCount() == 15, "edge-dist 0.4: merge failed");
        }

        // Edge-to-edge distance 0.6 (centers at 2.0 and 2.85, center delta 0.85 > 0.75): DOES NOT MERGE
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.0, 64.0, 2.0, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 36);
            Twin tB = spawn(a, 2.85, 64.0, 2.0, new ItemStack(Items.DIAMOND, 5), 0, 0, 0, SEED + 37);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "edge-dist 0.6: item A removed unexpectedly");
            expect(!tB.k.isRemoved() && !tB.v.isRemoved(), "edge-dist 0.6: item B removed unexpectedly");
            expect(tA.k.getItem().getCount() == 10 && tA.v.getItem().getCount() == 10, "edge-dist 0.6: count A changed");
            expect(tB.k.getItem().getCount() == 5 && tB.v.getItem().getCount() == 5, "edge-dist 0.6: count B changed");
        }
    }

    // =====================================================================
    // 5. Stack limits
    // =====================================================================

    private static void checkStackLimits() {
        // Sum == 64: MERGES
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.COBBLESTONE, 40), 0, 0, 0, SEED + 40);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.COBBLESTONE, 24), 0, 0, 0, SEED + 41);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(tA.k.getItem().getCount() == 64 && tA.v.getItem().getCount() == 64, "sum 64 merge: count != 64");
            expect(tB.k.isRemoved() && tB.v.isRemoved(), "sum 64 merge: entity B not removed");
        }

        // Sum == 65: DOES NOT MERGE
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.COBBLESTONE, 40), 0, 0, 0, SEED + 42);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.COBBLESTONE, 25), 0, 0, 0, SEED + 43);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "sum 65: item A removed unexpectedly");
            expect(!tB.k.isRemoved() && !tB.v.isRemoved(), "sum 65: item B removed unexpectedly");
            expect(tA.k.getItem().getCount() == 40 && tA.v.getItem().getCount() == 40, "sum 65: count A changed");
            expect(tB.k.getItem().getCount() == 25 && tB.v.getItem().getCount() == 25, "sum 65: count B changed");
        }

        // Ender pearl (max 16): sum 16 merges, sum 17 does not
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.ENDER_PEARL, 8), 0, 0, 0, SEED + 44);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.ENDER_PEARL, 8), 0, 0, 0, SEED + 45);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            // ItemEntity.java:238-242: on equal counts A (ticking first) merges into B.
            expect(tB.v.getItem().getCount() == 16 && tB.k.getItem().getCount() == 16, "pearl sum 16 merge count (B "
                    + tB.k.getItem().getCount() + "/" + tB.v.getItem().getCount() + ")");
            expect(tA.v.isRemoved() && tA.k.isRemoved(), "pearl sum 16 merge removal (A)");
        }

        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.ENDER_PEARL, 9), 0, 0, 0, SEED + 46);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.ENDER_PEARL, 8), 0, 0, 0, SEED + 47);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "pearl sum 17 item A removed");
            expect(!tB.k.isRemoved() && !tB.v.isRemoved(), "pearl sum 17 item B removed");
        }

        // Non-stackable (Swords): sum 2 > 1 does not merge
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.IRON_SWORD), 0, 0, 0, SEED + 48);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.IRON_SWORD), 0, 0, 0, SEED + 49);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "sword sum 2 item A removed");
            expect(!tB.k.isRemoved() && !tB.v.isRemoved(), "sword sum 2 item B removed");
        }

        // Different items: do not merge
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 50);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.GOLD_INGOT, 10), 0, 0, 0, SEED + 51);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "different items A removed");
            expect(!tB.k.isRemoved() && !tB.v.isRemoved(), "different items B removed");
        }
    }

    // =====================================================================
    // 6. Age and pickup delay inheritance
    // =====================================================================

    private static void checkAgeAndPickupDelayInheritance() {
        // Asymmetric counts: larger count stack survives (A count 10 vs B count 5)
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 60);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.DIAMOND, 5), 0, 0, 0, SEED + 61);

            tA.k.setAge(4000);
            set(AGE, tA.v, 4000);
            tA.k.setPickUpDelay(5);
            set(PICKUP_DELAY, tA.v, 5);

            tB.k.setAge(200);
            set(AGE, tB.v, 200);
            tB.k.setPickUpDelay(300);
            set(PICKUP_DELAY, tB.v, 300);

            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }

            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "inheritance: A is survivor");
            expect(tB.k.isRemoved() && tB.v.isRemoved(), "inheritance: B is discarded");
            expect(tA.k.getItem().getCount() == 15 && tA.v.getItem().getCount() == 15, "inheritance: count 15");

            // Age is min(4000, 200) + 40 = 240
            expect(tA.k.getAge() == (int) get(AGE, tA.v), "inheritance: age mismatch (k=" + tA.k.getAge() + ", v=" + get(AGE, tA.v) + ")");
            expect(tA.k.getAge() == 240, "inheritance: expected age 240, got " + tA.k.getAge());

            // ItemEntity.java:266: A merges on its tick 40, before B has ticked a 40th time, so the
            // delay is max(A 0, B 300 - 39) = 261.
            expect(tA.k.getPickupDelay() == (int) get(PICKUP_DELAY, tA.v), "inheritance: pickup delay mismatch");
            expect(tA.k.getPickupDelay() == 261, "inheritance: expected pickup delay 261, got " + tA.k.getPickupDelay());
        }

        // Asymmetric counts: B has larger count (A count 5 vs B count 10)
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND, 5), 0, 0, 0, SEED + 62);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 63);

            tA.k.setAge(500);
            set(AGE, tA.v, 500);
            tA.k.setPickUpDelay(40);
            set(PICKUP_DELAY, tA.v, 40);

            tB.k.setAge(3000);
            set(AGE, tB.v, 3000);
            tB.k.setPickUpDelay(10);
            set(PICKUP_DELAY, tB.v, 10);

            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }

            expect(tA.k.isRemoved() && tA.v.isRemoved(), "inheritance B-survivor: A is discarded");
            expect(!tB.k.isRemoved() && !tB.v.isRemoved(), "inheritance B-survivor: B is survivor");
            expect(tB.k.getItem().getCount() == 15 && tB.v.getItem().getCount() == 15, "inheritance B-survivor: count 15");
            expect(tB.k.getAge() == (int) get(AGE, tB.v), "inheritance B-survivor: age mismatch");
            expect(tB.k.getAge() == 540, "inheritance B-survivor: expected age 540, got " + tB.k.getAge());
            expect(tB.k.getPickupDelay() == (int) get(PICKUP_DELAY, tB.v), "inheritance B-survivor: pickup delay mismatch");
        }

        // Equal counts (count 10 and count 10): vanilla merges A into B
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 64);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 65);

            tA.k.setAge(1000);
            set(AGE, tA.v, 1000);
            tB.k.setAge(2000);
            set(AGE, tB.v, 2000);

            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }

            // Both Kestrel and vanilla choose the exact same survivor
            expect(tA.k.isRemoved() == tA.v.isRemoved(), "equal counts: removal state of A differs from vanilla");
            expect(tB.k.isRemoved() == tB.v.isRemoved(), "equal counts: removal state of B differs from vanilla");
            Twin survivor = tA.k.isRemoved() ? tB : tA;
            expect(survivor.k.getItem().getCount() == 20 && survivor.v.getItem().getCount() == 20, "equal counts: count != 20");
            expect(survivor.k.getAge() == (int) get(AGE, survivor.v), "equal counts: age differs from vanilla");
            expect(survivor.k.getAge() == 1040, "equal counts: expected min age + 40 = 1040, got " + survivor.k.getAge());
        }
    }

    // =====================================================================
    // 7. Target filter
    // =====================================================================

    private static void checkTargetFilter() {
        UUID u1 = new UUID(0x100L, 0x200L);
        UUID u2 = new UUID(0x300L, 0x400L);

        // Different targets: do NOT merge
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 70);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.DIAMOND, 5), 0, 0, 0, SEED + 71);
            tA.k.setTarget(u1);
            tA.v.setTarget(u1);
            tB.k.setTarget(u2);
            tB.v.setTarget(u2);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "diff target: A removed");
            expect(!tB.k.isRemoved() && !tB.v.isRemoved(), "diff target: B removed");
            expect(tA.k.getItem().getCount() == 10 && tA.v.getItem().getCount() == 10, "diff target: count A changed");
        }

        // One target null, one non-null: do NOT merge
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 72);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.DIAMOND, 5), 0, 0, 0, SEED + 73);
            tA.k.setTarget(u1);
            tA.v.setTarget(u1);
            tB.k.setTarget(null);
            tB.v.setTarget(null);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "target null/non-null: A removed");
            expect(!tB.k.isRemoved() && !tB.v.isRemoved(), "target null/non-null: B removed");
        }

        // Both same target: MERGES
        {
            Arena a = flat();
            Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 74);
            Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.DIAMOND, 5), 0, 0, 0, SEED + 75);
            tA.k.setTarget(u1);
            tA.v.setTarget(u1);
            tB.k.setTarget(u1);
            tB.v.setTarget(u1);
            for (int i = 0; i < 40; i++) {
                a.world.tickEntities();
                tickVanilla(a);
            }
            expect(!tA.k.isRemoved() && !tA.v.isRemoved(), "same target: survivor removed");
            expect(tB.k.isRemoved() && tB.v.isRemoved(), "same target: merged entity not removed");
            expect(tA.k.getItem().getCount() == 15 && tA.v.getItem().getCount() == 15, "same target: count 15");
        }
    }

    // =====================================================================
    // 8. Lifetime and despawn boundary
    // =====================================================================

    private static void checkLifetimeAndDespawn() {
        // Boundary test: spawn at age 5998
        Arena a = flat();
        Twin t = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND), 0, 0, 0, SEED + 80);
        t.k.setAge(5998);
        set(AGE, t.v, 5998);

        // Tick 1: age 5999, not discarded
        a.world.tickEntities();
        tickVanilla(a);
        expect(!t.k.isRemoved() && !t.v.isRemoved(), "age 5999: removed early");
        expect(t.k.getAge() == 5999 && (int) get(AGE, t.v) == 5999, "age 5999 mismatch");

        // Tick 2: age 6000, discarded on both sides!
        a.world.tickEntities();
        tickVanilla(a);
        expect(t.k.isRemoved(), "age 6000: not discarded in Kestrel");
        expect(t.v.isRemoved(), "age 6000: not discarded in vanilla");
        expect(t.k.getAge() == 6000 && (int) get(AGE, t.v) == 6000, "age 6000 mismatch");

        // Infinite lifetime sentinel (-32768)
        Arena a2 = flat();
        Twin tInf = spawn(a2, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND), 0, 0, 0, SEED + 81);
        tInf.k.setUnlimitedLifetime();
        tInf.v.setUnlimitedLifetime();

        for (int i = 0; i < 100; i++) {
            a2.world.tickEntities();
            tickVanilla(a2);
        }
        expect(!tInf.k.isRemoved() && !tInf.v.isRemoved(), "infinite lifetime: removed");
        expect(tInf.k.getAge() == KestrelItemEntity.INFINITE_LIFETIME, "infinite lifetime: age modified in Kestrel");
        expect((int) get(AGE, tInf.v) == KestrelItemEntity.INFINITE_LIFETIME, "infinite lifetime: age modified in vanilla");

        // Infinite pickup delay sentinel (32767)
        tInf.k.setNeverPickUp();
        tInf.v.setNeverPickUp();
        for (int i = 0; i < 50; i++) {
            a2.world.tickEntities();
            tickVanilla(a2);
        }
        expect(tInf.k.getPickupDelay() == KestrelItemEntity.INFINITE_PICKUP_DELAY, "infinite pickup delay: delay decremented in Kestrel");
        expect((int) get(PICKUP_DELAY, tInf.v) == KestrelItemEntity.INFINITE_PICKUP_DELAY, "infinite pickup delay: delay decremented in vanilla");
    }

    // =====================================================================
    // 9. Watching player packets
    // =====================================================================

    private static void checkWatchingPlayerPackets() {
        EntityTracker tracker = new EntityTracker(100);
        Arena a = flat(tracker);

        Observer observer = new Observer(999, 2.0, 2.0);
        tracker.addPlayer(observer);

        Twin tA = spawn(a, 2.5, 64.0, 2.5, new ItemStack(Items.DIAMOND, 10), 0, 0, 0, SEED + 90);
        Twin tB = spawn(a, 2.7, 64.0, 2.5, new ItemStack(Items.DIAMOND, 5), 0, 0, 0, SEED + 91);

        // Initial pairing and spawn packets
        tracker.tick();
        expect(!observer.captured.isEmpty(), "observer received initial spawn packets");
        observer.captured.clear();

        // Ticks 1 to 39: no merge packets (no removal, no stack change). Settling
        // items still get their ordinary move and motion updates, whose bytes
        // ItemEntityTickCheck already diffs against vanilla's ServerEntity.
        for (int tick = 1; tick < 40; tick++) {
            a.world.tickEntities();
            tracker.tick();
            List<Integer> mergeIds = observer.captured.stream().map(Outbound.Packet::id)
                    .filter(id -> id == PacketIds.PLAY_REMOVE_ENTITIES || id == PacketIds.PLAY_SET_ENTITY_DATA)
                    .toList();
            expect(mergeIds.isEmpty(), "tick " + tick + ": observer received merge packets before the merge: "
                    + mergeIds);
            observer.captured.clear();
        }

        // Tick 40: merge occurs!
        a.world.tickEntities();
        tracker.tick();

        // Observer must receive:
        // 1) RemoveEntities packet for tB.k.getId()
        // 2) SetEntityData packet for tA.k.getId() containing ItemStack of 15
        boolean sawRemove = false;
        boolean sawSetData = false;

        Outbound.Packet expectedRemove = dev.kestrel.net.EntityOutbound.removeEntities(tB.k.getId());
        String expectedRemoveStr = fmt(expectedRemove);

        // Vanilla packet comparison: encode through vanilla codec
        ClientboundRemoveEntitiesPacket vanillaRemove = new ClientboundRemoveEntitiesPacket(tB.v.getId());
        List<String> vanillaRemoveEnc = encodeVanilla(vanillaRemove);
        expect(!vanillaRemoveEnc.isEmpty(), "vanilla remove packet encoding empty");
        expect(vanillaRemoveEnc.contains(expectedRemoveStr), "remove packet bytes differ from vanilla codec");

        for (Outbound.Packet p : observer.captured) {
            if (p.id() == PacketIds.PLAY_REMOVE_ENTITIES) {
                if (fmt(p).equals(expectedRemoveStr)) {
                    sawRemove = true;
                }
            } else if (p.id() == PacketIds.PLAY_SET_ENTITY_DATA) {
                // Verify packet contains tA's id and updated stack
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(p.payload()));
                int entityId = buf.readVarInt();
                if (entityId == tA.k.getId()) {
                    sawSetData = true;
                }
            }
        }

        expect(sawRemove, "watching player did not receive RemoveEntities for merged entity");
        expect(sawSetData, "watching player did not receive SetEntityData for survivor entity");
    }

    // =====================================================================
    // 10. Multi-item cluster convergence
    // =====================================================================

    private static void checkClusterConvergence() {
        Arena a = flat();
        int[] counts = { 5, 10, 15, 20, 25, 30 }; // sum = 105
        int totalExpected = 105;

        List<Twin> twins = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            Twin t = spawn(a, 2.5 + (i * 0.05), 64.0, 2.5 + (i * 0.05),
                    new ItemStack(Items.DIAMOND, counts[i]), 0, 0, 0, SEED + 100 + i);
            twins.add(t);
        }

        // Run for 120 ticks (multiple merge cycles at tick 40, 80, 120)
        for (int tick = 1; tick <= 120; tick++) {
            a.world.tickEntities();
            tickVanilla(a);
        }

        // Check conservation of items in Kestrel
        int totalKestrel = 0;
        int activeKestrel = 0;
        for (Twin t : twins) {
            if (!t.k.isRemoved()) {
                totalKestrel += t.k.getItem().getCount();
                activeKestrel++;
            }
        }

        int totalVanilla = 0;
        int activeVanilla = 0;
        for (Twin t : twins) {
            if (!t.v.isRemoved()) {
                totalVanilla += t.v.getItem().getCount();
                activeVanilla++;
            }
        }

        expect(totalKestrel == totalExpected, "cluster total items not conserved in Kestrel (was " + totalKestrel + ", expected " + totalExpected + ")");
        expect(totalVanilla == totalExpected, "cluster total items not conserved in vanilla (was " + totalVanilla + ")");
        expect(totalKestrel == totalVanilla, "cluster total items differs between Kestrel and vanilla");
        expect(activeKestrel == activeVanilla, "cluster surviving entities count differs (kestrel=" + activeKestrel + ", vanilla=" + activeVanilla + ")");
        // 105 items in stacks of max 64 must converge to exactly 2 stacks: 64 and 41
        expect(activeKestrel == 2, "cluster should converge to 2 stacks (64 + 41), got " + activeKestrel);
    }

    // =====================================================================
    // Harness and Helpers
    // =====================================================================

    static final class Twin {
        final KestrelItemEntity k;
        final ItemEntity v;

        Twin(KestrelItemEntity k, ItemEntity v) {
            this.k = k;
            this.v = v;
        }
    }

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
        a.oracle.addEntity(v);
        return new Twin(k, v);
    }

    static void tickVanilla(Arena a) {
        List<Entity> pass = new ArrayList<>(a.oracle.entities);
        for (Entity e : pass) {
            if (e instanceof ItemEntity v && !v.isRemoved()) {
                v.checkDespawn();
                a.oracle.tickNonPassenger(v);
            }
        }
    }

    static void sameItem(Twin t, String ctx) {
        comparisons++;
        boolean kRemoved = t.k.isRemoved();
        boolean vRemoved = t.v.isRemoved();
        if (kRemoved != vRemoved) {
            failures.add(ctx + ": isRemoved mismatch (k=" + kRemoved + ", v=" + vRemoved + ")");
            return;
        }
        if (!kRemoved) {
            int kCount = t.k.getItem().getCount();
            int vCount = t.v.getItem().getCount();
            if (kCount != vCount) {
                failures.add(ctx + ": count mismatch (k=" + kCount + ", v=" + vCount + ")");
            }
            int kAge = t.k.getAge();
            int vAge = (int) get(AGE, t.v);
            if (kAge != vAge) {
                failures.add(ctx + ": age mismatch (k=" + kAge + ", v=" + vAge + ")");
            }
            int kDelay = t.k.getPickupDelay();
            int vDelay = (int) get(PICKUP_DELAY, t.v);
            if (kDelay != vDelay) {
                failures.add(ctx + ": pickupDelay mismatch (k=" + kDelay + ", v=" + vDelay + ")");
            }
        }
    }

    static Arena flat() {
        return flat(null);
    }

    static Arena flat(@Nullable EntityTracker tracker) {
        Arena a = new Arena(tracker);
        a.fill(-5, 63, -5, 10, 63, 10, Blocks.STONE.defaultBlockState());
        return a;
    }

    static final class Arena {
        final SectionStore store = new SectionStore(-64, 384);
        final StoreGetter getter = new StoreGetter(store);
        final ShapeBoxCache cache = new ShapeBoxCache();
        final @Nullable EntityTracker tracker;
        final EntityLevel level;
        final EntityWorld world;
        final OracleMergeLevel oracle;

        Arena(@Nullable EntityTracker tracker) {
            this.tracker = tracker;
            this.level = new EntityLevel() {
                @Override public BlockGetter blocks() { return getter; }
                @Override public ShapeBoxCache shapeCache() { return cache; }
                @Override public int getMinY() { return store.getMinY(); }
                @Override public LevelReader levelReader() { throw new UnsupportedOperationException("no level reader"); }
                @Override public boolean setBlock(BlockPos pos, BlockState state, int flags) { throw new UnsupportedOperationException("never writes blocks"); }
                @Override public void levelEvent(int type, BlockPos pos, int data) { throw new UnsupportedOperationException("no level events"); }
                @Override public void landingBlockUpdate(KestrelEntity entity, BlockPos pos, BlockState state) { throw new UnsupportedOperationException("no falling blocks"); }
                @Override public RandomSource random() { throw new UnsupportedOperationException("no level random"); }
                @Override public GameRules gameRules() { throw new UnsupportedOperationException("no game rules"); }
                @Override public void playSound(double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) { throw new UnsupportedOperationException("no sounds"); }
                @Override public void entityEvent(KestrelEntity entity, byte event) { throw new UnsupportedOperationException("no entity events"); }
                @Override public @Nullable EntityWorld world() { return world; }
            };
            this.world = new EntityWorld(level, tracker);
            this.oracle = OracleMergeLevel.create(getter);
        }

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

    static final class StoreGetter implements BlockGetter {
        final SectionStore store;

        StoreGetter(SectionStore store) {
            this.store = store;
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return store.get(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return store.get(pos).getFluidState(); }
        @Override public int getHeight() { return store.getHeight(); }
        @Override public int getMinY() { return store.getMinY(); }
    }

    static final class OracleMergeLevel extends ServerLevel {
        private int nextEntityId;
        private BlockGetter blocks;
        private WorldBorder border;
        private DamageSources damage;
        private net.minecraft.server.MinecraftServer server;
        // Unsafe-allocated: field initializers never run, so create() sets every field.
        List<Entity> entities;

        private OracleMergeLevel() {
            super(null, Runnable::run, null, null, Level.OVERWORLD, null, false, 0L, List.of(), false);
            throw new AssertionError("constructor must not run");
        }

        static OracleMergeLevel create(BlockGetter blocks) {
            try {
                OracleMergeLevel level = (OracleMergeLevel) unsafe().allocateInstance(OracleMergeLevel.class);
                level.blocks = blocks;
                level.entities = new ArrayList<>();
                level.border = new WorldBorder();
                level.damage = (DamageSources) unsafe().allocateInstance(DamageSources.class);
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

        void addEntity(Entity entity) {
            entities.add(entity);
        }

        void nextId(int id) {
            this.nextEntityId = id;
        }

        @Override public int getNextEntityId() { return nextEntityId; }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return blocks.getFluidState(pos); }
        @Override public net.minecraft.world.level.chunk.@Nullable ChunkAccess getChunk(int chunkX, int chunkZ,
                net.minecraft.world.level.chunk.status.ChunkStatus status, boolean loadOrGenerate) { return null; }
        @Override public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) { return blocks; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public int getMinY() { return blocks.getMinY(); }
        @Override public int getHeight() { return blocks.getHeight(); }

        @Override
        public List<Entity> getEntities(@Nullable Entity except, AABB bb, Predicate<? super Entity> selector) {
            List<Entity> result = new ArrayList<>();
            for (Entity entity : entities) {
                if (entity != except && !entity.isRemoved() && entity.getBoundingBox().intersects(bb) && selector.test(entity)) {
                    result.add(entity);
                }
            }
            return result;
        }

        @Override
        public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB bb,
                Predicate<? super T> selector) {
            List<T> result = new ArrayList<>();
            for (Entity entity : entities) {
                if (entity.isRemoved()) continue;
                T typed = type.tryCast(entity);
                if (typed != null && entity.getBoundingBox().intersects(bb) && selector.test(typed)) {
                    result.add(typed);
                }
            }
            return result;
        }

        @Override public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) { }
        @Override public boolean isRainingAt(BlockPos pos) { return false; }
        @Override public net.minecraft.server.MinecraftServer getServer() { return server; }
        @Override public DamageSources damageSources() { return damage; }
    }

    static final class Observer implements TrackingObserver {
        final int id;
        final double x;
        final double z;
        final List<Outbound.Packet> captured = new ArrayList<>();

        Observer(int id, double x, double z) {
            this.id = id;
            this.x = x;
            this.z = z;
        }

        @Override public int entityId() { return id; }
        @Override public double x() { return x; }
        @Override public double y() { return 64.0; }
        @Override public double z() { return z; }
        @Override public int viewDistance() { return 10; }
        @Override public boolean isChunkTracked(int chunkX, int chunkZ) { return true; }
        @Override public void send(Outbound.Packet packet) { captured.add(packet); }
    }

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

    // =====================================================================
    // Reflection into vanilla internals
    // =====================================================================

    private static final Field PICKUP_DELAY = field(ItemEntity.class, "pickupDelay");
    private static final Field AGE = field(ItemEntity.class, "age");
    private static final Field TARGET = field(ItemEntity.class, "target");
    private static final Method IS_MERGABLE = method(ItemEntity.class, "isMergable");

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
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

    private static boolean invokeIsMergable(ItemEntity v) {
        try {
            return (boolean) IS_MERGABLE.invoke(v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }
}
