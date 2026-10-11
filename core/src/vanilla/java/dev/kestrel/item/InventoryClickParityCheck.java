package dev.kestrel.item;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.HashOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import dev.kestrel.collision.ShapeBoxCache;
import dev.kestrel.entity.EntityLevel;
import dev.kestrel.entity.KestrelEntity;
import dev.kestrel.entity.KestrelServerPlayer;
import dev.kestrel.net.PacketIds;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parity check for the container click protocol (G17 slice 4).
 *
 * <p>Each scenario drives two players with the same serverbound packets. The
 * Kestrel player goes through {@link PlayerInventory#handleClick} and
 * {@link PlayerInventory#close} with Kestrel's synchronizer; the vanilla
 * player goes through vanilla's own {@code handleContainerClick} and
 * {@code handleContainerClose} with vanilla's own container synchronizer.
 * Packets (byte for byte), slots, carried stack, state id and drops must
 * match, alongside fixed expectations written from vanilla's logic.
 */
public final class InventoryClickParityCheck {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;
    private static int scenarios;

    private static RegistryAccess ACCESS;

    private static final HashedPatchMap.HashGenerator HASHER = component -> {
        com.mojang.serialization.DynamicOps<com.google.common.hash.HashCode> ops =
                ACCESS.createSerializationContext(HashOps.CRC32C_INSTANCE);
        return ((com.google.common.hash.HashCode) component.encodeValue(ops)
                .getOrThrow(msg -> new IllegalArgumentException("Failed to hash " + component + ": " + msg)))
                .asInt();
    };

    private InventoryClickParityCheck() {}

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        ACCESS = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        dev.kestrel.worldgen.HeadlessNoise.loadStructural(4242424242L).close();

        checkPinnedPacketIds();
        checkScenario1Pickup();
        checkScenario2OutsideDrop();
        checkScenario3QuickMove();
        checkScenario4Swap();
        checkScenario5Throw();
        checkScenario6QuickCraft();
        checkScenario7PickupAll();
        checkScenario8StateIdDesync();
        checkScenario9HashedStackPrediction();
        checkComponentPrediction();
        checkReentrantBroadcast();
        checkScenario10ContainerClose();
        checkScenario11SpectatorAndDying();
        checkScenario12InvalidContainerAndSlot();

        if (!failures.isEmpty()) {
            System.out.println("InventoryClickParityCheck FAILED with " + failures.size() + " failure(s):");
            failures.forEach(s -> System.out.println("  - " + s));
            System.exit(1);
        }
        System.out.println("  " + checks + " checks, " + scenarios + " scenarios");
        System.out.println("InventoryClickParityCheck: PASS");
    }

    private static void checkPinnedPacketIds() {
        scenarios++;
        // Literal constants pinned from GameProtocols
        final int EXPECTED_CONTAINER_CLICK = 0x12;
        final int EXPECTED_CONTAINER_CLOSE = 0x13;
        final int EXPECTED_SET_CONTENT = 0x12;
        final int EXPECTED_SET_SLOT = 0x14;
        final int EXPECTED_SET_CURSOR = 0x60;

        check(PacketIds.PLAY_CONTAINER_CLICK == EXPECTED_CONTAINER_CLICK,
                "PLAY_CONTAINER_CLICK literal mismatch: " + PacketIds.PLAY_CONTAINER_CLICK);
        check(PacketIds.PLAY_CONTAINER_CLOSE == EXPECTED_CONTAINER_CLOSE,
                "PLAY_CONTAINER_CLOSE literal mismatch: " + PacketIds.PLAY_CONTAINER_CLOSE);
        check(PacketIds.PLAY_CONTAINER_SET_CONTENT == EXPECTED_SET_CONTENT,
                "PLAY_CONTAINER_SET_CONTENT literal mismatch: " + PacketIds.PLAY_CONTAINER_SET_CONTENT);
        check(PacketIds.PLAY_CONTAINER_SET_SLOT == EXPECTED_SET_SLOT,
                "PLAY_CONTAINER_SET_SLOT literal mismatch: " + PacketIds.PLAY_CONTAINER_SET_SLOT);
        check(PacketIds.PLAY_SET_CURSOR_ITEM == EXPECTED_SET_CURSOR,
                "PLAY_SET_CURSOR_ITEM literal mismatch: " + PacketIds.PLAY_SET_CURSOR_ITEM);

        check(serverboundId(GamePacketTypes.SERVERBOUND_CONTAINER_CLICK) == EXPECTED_CONTAINER_CLICK,
                "GameProtocols container click ID != 0x12");
        check(serverboundId(GamePacketTypes.SERVERBOUND_CONTAINER_CLOSE) == EXPECTED_CONTAINER_CLOSE,
                "GameProtocols container close ID != 0x13");
        check(clientboundId(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT) == EXPECTED_SET_CONTENT,
                "GameProtocols container set content ID != 0x12");
        check(clientboundId(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_SLOT) == EXPECTED_SET_SLOT,
                "GameProtocols container set slot ID != 0x14");
        check(clientboundId(GamePacketTypes.CLIENTBOUND_SET_CURSOR_ITEM) == EXPECTED_SET_CURSOR,
                "GameProtocols set cursor ID != 0x60");
    }

    private static void checkScenario1Pickup() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // 1. Left-click empty slot 36 with carried stone x64 -> places full stack
        setCarriedBoth(p, new ItemStack(Items.STONE, 64));
        int state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 36, (byte) 0, ContainerInput.PICKUP, null, null);
        equalBoth("pickup: place full stack", p);
        check(p.k.menu().getSlot(36).getItem().is(Items.STONE)
                && p.k.menu().getSlot(36).getItem().getCount() == 64, "expected stone x64 in slot 36");
        check(p.k.carried().isEmpty(), "expected carried empty after full place");

        // 2. Right-click empty slot 37 with carried stone x64 -> places 1 item
        p.clearPackets();
        setCarriedBoth(p, new ItemStack(Items.STONE, 64));
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 37, (byte) 1, ContainerInput.PICKUP, null, null);
        equalBoth("pickup: place 1 item", p);
        check(p.k.menu().getSlot(37).getItem().is(Items.STONE)
                && p.k.menu().getSlot(37).getItem().getCount() == 1, "expected stone x1 in slot 37");
        check(p.k.carried().is(Items.STONE)
                && p.k.carried().getCount() == 63, "expected stone x63 carried");

        // 3. Left-click slot 36 with empty cursor -> picks up full stack
        p.clearPackets();
        setCarriedBoth(p, ItemStack.EMPTY);
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 36, (byte) 0, ContainerInput.PICKUP, null, null);
        equalBoth("pickup: take full stack", p);
        check(p.k.menu().getSlot(36).getItem().isEmpty(), "expected slot 36 empty");
        check(p.k.carried().is(Items.STONE) && p.k.carried().getCount() == 64, "expected carried stone x64");

        // 4. Right-click slot 37 (count 63) with empty cursor -> takes (63+1)/2 = 32
        p.clearPackets();
        setBoth(p, 1, new ItemStack(Items.STONE, 63));
        setCarriedBoth(p, ItemStack.EMPTY);
        p.broadcast();
        p.clearPackets();
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 37, (byte) 1, ContainerInput.PICKUP, null, null);
        equalBoth("pickup: take half stack", p);
        check(p.k.carried().is(Items.STONE) && p.k.carried().getCount() == 32, "expected carried stone x32");
        check(p.k.menu().getSlot(37).getItem().is(Items.STONE)
                && p.k.menu().getSlot(37).getItem().getCount() == 31, "expected slot 37 stone x31");

        // 5. Left-click matching stack: cursor stone x32 onto slot 37 stone x31 -> merges to 63
        p.clearPackets();
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 37, (byte) 0, ContainerInput.PICKUP, null, null);
        equalBoth("pickup: merge stack", p);
        check(p.k.menu().getSlot(37).getItem().is(Items.STONE)
                && p.k.menu().getSlot(37).getItem().getCount() == 63, "expected slot 37 stone x63");
        check(p.k.carried().isEmpty(), "expected carried empty after merge");

        // 6. Left-click different stack: cursor dirt x10 onto slot 37 stone x63 -> swaps stacks
        p.clearPackets();
        setCarriedBoth(p, new ItemStack(Items.DIRT, 10));
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 37, (byte) 0, ContainerInput.PICKUP, null, null);
        equalBoth("pickup: swap different stacks", p);
        check(p.k.carried().is(Items.STONE) && p.k.carried().getCount() == 63, "expected carried stone x63 after swap");
        check(p.k.menu().getSlot(37).getItem().is(Items.DIRT)
                && p.k.menu().getSlot(37).getItem().getCount() == 10, "expected slot 37 dirt x10 after swap");
    }

    private static void checkScenario2OutsideDrop() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // 1. Left-click outside (-999, button 0) with carried stone x64 -> drops full stack, carried becomes empty
        setCarriedBoth(p, new ItemStack(Items.STONE, 64));
        int state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) -999, (byte) 0, ContainerInput.PICKUP, null, null);
        equalBoth("outside: drop full stack", p);
        check(p.k.carried().isEmpty(), "expected carried empty after outside left click");
        check(p.kLevel.dropped.size() == 1
                && p.kLevel.dropped.get(0).getItem().is(Items.STONE)
                && p.kLevel.dropped.get(0).getItem().getCount() == 64, "expected 1 dropped entity stone x64");

        // 2. Right-click outside (-999, button 1) with carried dirt x10 -> drops 1 item, carried becomes 9
        p.clearPackets();
        setCarriedBoth(p, new ItemStack(Items.DIRT, 10));
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) -999, (byte) 1, ContainerInput.PICKUP, null, null);
        equalBoth("outside: drop 1 item", p);
        check(p.k.carried().is(Items.DIRT) && p.k.carried().getCount() == 9, "expected carried dirt x9");
        check(p.kLevel.dropped.size() == 1
                && p.kLevel.dropped.get(0).getItem().is(Items.DIRT)
                && p.kLevel.dropped.get(0).getItem().getCount() == 1, "expected 1 dropped entity dirt x1");
    }

    private static void checkScenario3QuickMove() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // 1. Shift-click hotbar stack (slot 36) -> moves to main inventory slots 9..35
        setBoth(p, 0, new ItemStack(Items.STONE, 64));
        p.broadcast();
        p.clearPackets();
        int state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 36, (byte) 0, ContainerInput.QUICK_MOVE, null, null);
        equalBoth("quickmove: hotbar to main", p);
        check(p.k.menu().getSlot(36).getItem().isEmpty(), "expected slot 36 empty after shift-click");
        check(p.k.menu().getSlot(9).getItem().is(Items.STONE)
                && p.k.menu().getSlot(9).getItem().getCount() == 64, "expected slot 9 stone x64");

        // 2. Shift-click main inventory stack (slot 9) -> moves to hotbar slots 36..44
        p.clearPackets();
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 9, (byte) 0, ContainerInput.QUICK_MOVE, null, null);
        equalBoth("quickmove: main to hotbar", p);
        check(p.k.menu().getSlot(9).getItem().isEmpty(), "expected slot 9 empty after shift-click");
        check(p.k.menu().getSlot(36).getItem().is(Items.STONE)
                && p.k.menu().getSlot(36).getItem().getCount() == 64, "expected slot 36 stone x64");

        // 3. Shift-click diamond helmet in hotbar -> equips into helmet slot 5
        p.clearPackets();
        setBoth(p, 1, new ItemStack(Items.DIAMOND_HELMET));
        p.broadcast();
        p.clearPackets();
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 37, (byte) 0, ContainerInput.QUICK_MOVE, null, null);
        equalBoth("quickmove: equip helmet", p);
        check(p.k.menu().getSlot(37).getItem().isEmpty(), "expected hotbar slot 37 empty");
        check(p.k.menu().getSlot(5).getItem().is(Items.DIAMOND_HELMET), "expected helmet slot 5 equipped");

        // 4. Shift-click shield in hotbar -> equips into offhand slot 45
        p.clearPackets();
        setBoth(p, 2, new ItemStack(Items.SHIELD));
        p.broadcast();
        p.clearPackets();
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 38, (byte) 0, ContainerInput.QUICK_MOVE, null, null);
        equalBoth("quickmove: equip shield", p);
        check(p.k.menu().getSlot(38).getItem().isEmpty(), "expected hotbar slot 38 empty");
        check(p.k.menu().getSlot(45).getItem().is(Items.SHIELD), "expected offhand slot 45 equipped");
    }

    private static void checkScenario4Swap() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // 1. Hover main slot 9 (has iron sword), press hotbar key 1 (buttonNum = 0, hotbar slot 36 has oak log x12)
        setBoth(p, 9, new ItemStack(Items.IRON_SWORD));
        setBoth(p, 0, new ItemStack(Items.OAK_LOG, 12));
        p.broadcast();
        p.clearPackets();
        int state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 9, (byte) 0, ContainerInput.SWAP, null, null);
        equalBoth("swap: number key 1", p);
        check(p.k.menu().getSlot(9).getItem().is(Items.OAK_LOG)
                && p.k.menu().getSlot(9).getItem().getCount() == 12, "expected slot 9 oak log x12");
        check(p.k.menu().getSlot(36).getItem().is(Items.IRON_SWORD), "expected hotbar slot 36 iron sword");

        // 2. Hover slot 36 (has iron sword), press offhand swap key (buttonNum = 40, offhand slot 45 has shield)
        p.clearPackets();
        setBoth(p, 40, new ItemStack(Items.SHIELD));
        p.broadcast();
        p.clearPackets();
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 36, (byte) 40, ContainerInput.SWAP, null, null);
        equalBoth("swap: offhand key 40", p);
        check(p.k.menu().getSlot(36).getItem().is(Items.SHIELD), "expected slot 36 shield");
        check(p.k.menu().getSlot(45).getItem().is(Items.IRON_SWORD), "expected offhand slot 45 iron sword");
    }

    private static void checkScenario5Throw() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // 1. Press drop key (buttonNum == 0) over slot 36 (stone x64) -> drops 1 item
        setBoth(p, 0, new ItemStack(Items.STONE, 64));
        p.broadcast();
        p.clearPackets();
        int state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 36, (byte) 0, ContainerInput.THROW, null, null);
        equalBoth("throw: drop 1 item", p);
        check(p.k.menu().getSlot(36).getItem().is(Items.STONE)
                && p.k.menu().getSlot(36).getItem().getCount() == 63, "expected slot 36 stone x63");
        check(p.kLevel.dropped.size() == 1
                && p.kLevel.dropped.get(0).getItem().is(Items.STONE)
                && p.kLevel.dropped.get(0).getItem().getCount() == 1, "expected 1 dropped entity count 1");

        // 2. Press drop stack key (buttonNum == 1) over slot 36 (stone x63) -> drops entire stack
        p.clearPackets();
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 36, (byte) 1, ContainerInput.THROW, null, null);
        equalBoth("throw: drop stack", p);
        check(p.k.menu().getSlot(36).getItem().isEmpty(), "expected slot 36 empty");
        check(p.kLevel.dropped.size() == 1
                && p.kLevel.dropped.get(0).getItem().is(Items.STONE)
                && p.kLevel.dropped.get(0).getItem().getCount() == 63, "expected 1 dropped entity count 63");
    }

    private static void checkScenario6QuickCraft() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // 1. Multi-slot drag: charitable drag (type 0) with iron ingot x15 across slots 36, 37, 38
        setCarriedBoth(p, new ItemStack(Items.IRON_INGOT, 15));
        int state = p.k.menu().getStateId();
        // Header 0 (Start): mask = (0 << 2) | 0 = 0, slot -999
        p.clickBoth(0, state, (short) -999, (byte) 0, ContainerInput.QUICK_CRAFT, null, null);
        // Header 1 (Continue): mask = (0 << 2) | 1 = 1, slots 36, 37, 38
        p.clickBoth(0, state, (short) 36, (byte) 1, ContainerInput.QUICK_CRAFT, null, null);
        p.clickBoth(0, state, (short) 37, (byte) 1, ContainerInput.QUICK_CRAFT, null, null);
        p.clickBoth(0, state, (short) 38, (byte) 1, ContainerInput.QUICK_CRAFT, null, null);
        // Header 2 (End): mask = (0 << 2) | 2 = 2, slot -999
        p.clickBoth(0, state, (short) -999, (byte) 2, ContainerInput.QUICK_CRAFT, null, null);
        equalBoth("quickcraft: 3-slot drag", p);
        check(p.k.menu().getSlot(36).getItem().is(Items.IRON_INGOT)
                && p.k.menu().getSlot(36).getItem().getCount() == 5, "slot 36 expected 5 ingots");
        check(p.k.menu().getSlot(37).getItem().is(Items.IRON_INGOT)
                && p.k.menu().getSlot(37).getItem().getCount() == 5, "slot 37 expected 5 ingots");
        check(p.k.menu().getSlot(38).getItem().is(Items.IRON_INGOT)
                && p.k.menu().getSlot(38).getItem().getCount() == 5, "slot 38 expected 5 ingots");
        check(p.k.carried().isEmpty(), "expected carried empty after exact drag");

        // 2. Single-slot drag fallback to normal PICKUP
        p.clearPackets();
        setCarriedBoth(p, new ItemStack(Items.IRON_INGOT, 10));
        state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) -999, (byte) 0, ContainerInput.QUICK_CRAFT, null, null);
        p.clickBoth(0, state, (short) 39, (byte) 1, ContainerInput.QUICK_CRAFT, null, null);
        p.clickBoth(0, state, (short) -999, (byte) 2, ContainerInput.QUICK_CRAFT, null, null);
        equalBoth("quickcraft: single-slot fallback to pickup", p);
        check(p.k.menu().getSlot(39).getItem().is(Items.IRON_INGOT)
                && p.k.menu().getSlot(39).getItem().getCount() == 10, "slot 39 expected 10 ingots");
        check(p.k.carried().isEmpty(), "carried expected empty");
    }

    private static void checkScenario7PickupAll() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // Setup: slot 36 has 10 ingots, slot 37 has 64 ingots, slot 38 has 20 ingots
        setBoth(p, 0, new ItemStack(Items.IRON_INGOT, 10));
        setBoth(p, 1, new ItemStack(Items.IRON_INGOT, 64));
        setBoth(p, 2, new ItemStack(Items.IRON_INGOT, 20));
        setCarriedBoth(p, new ItemStack(Items.IRON_INGOT, 10));
        p.broadcast();
        p.clearPackets();

        // Double-click slot 36 with buttonNum = 0 (forward traversal).
        // First click picked up slot 36 so slot 36 is empty.
        setBoth(p, 0, ItemStack.EMPTY);
        p.broadcast();
        p.clearPackets();

        int state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 36, (byte) 0, ContainerInput.PICKUP_ALL, null, null);
        equalBoth("pickup_all: double click collect", p);
        // Pass 0 collects partial stack from slot 38 first: 10 + 20 = 30 ingots.
        // Pass 1 collects from slot 37 to reach 64: 30 + 34 = 64 ingots.
        // Slot 37 retains 64 - 34 = 30 ingots. Slot 38 becomes empty.
        check(p.k.carried().is(Items.IRON_INGOT) && p.k.carried().getCount() == 64,
                "expected carried 64 iron ingots, got " + p.k.carried().getCount());
        check(p.k.menu().getSlot(38).getItem().isEmpty(), "expected slot 38 empty");
        check(p.k.menu().getSlot(37).getItem().is(Items.IRON_INGOT)
                && p.k.menu().getSlot(37).getItem().getCount() == 30,
                "expected slot 37 count 30, got " + p.k.menu().getSlot(37).getItem().getCount());
    }

    private static void checkScenario8StateIdDesync() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // Client sends click with stateId = 999 (mismatched)
        int staleStateId = 999;
        p.clickBoth(0, staleStateId, (short) 36, (byte) 0, ContainerInput.PICKUP, null, null);
        equalBoth("desync: stateId mismatch triggers full resync", p);
        check(p.kPackets.size() == 1, "expected exactly 1 packet on desync, got " + p.kPackets.size());
        check(!p.kPackets.isEmpty() && p.kPackets.get(0).id() == PacketIds.PLAY_CONTAINER_SET_CONTENT,
                "expected ClientboundContainerSetContentPacket (0x12) on desync");
        if (!p.kPackets.isEmpty() && p.kPackets.getFirst().id() == PacketIds.PLAY_CONTAINER_SET_CONTENT) {
            ClientboundContainerSetContentPacket content = decodeContent(p.kPackets.get(0));
            check(content.items().size() == 46, "expected 46 slots resynced");
        }
    }

    private static void checkScenario9HashedStackPrediction() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // 1. Packet with matching HashedStack prediction -> zero packets emitted
        setBoth(p, 0, new ItemStack(Items.STONE, 64));
        setBoth(p, 1, ItemStack.EMPTY);
        setCarriedBoth(p, ItemStack.EMPTY);
        p.broadcast();
        p.clearPackets();

        int state = p.k.menu().getStateId();
        Int2ObjectMap<HashedStack> matchMap = new Int2ObjectOpenHashMap<>();
        matchMap.put(36, HashedStack.EMPTY);
        HashedStack matchCarried = HashedStack.create(new ItemStack(Items.STONE, 64), HASHER);
        p.clickBoth(0, state, (short) 36, (byte) 0, ContainerInput.PICKUP, matchMap, matchCarried);
        equalBoth("prediction: matching hashes", p);
        check(p.kPackets.isEmpty(), "expected 0 packets on correct prediction, got " + p.kPackets.size());

        // 2. Packet with corrupted slot HashedStack prediction -> emits ClientboundContainerSetSlotPacket (0x14)
        p.clearPackets();
        state = p.k.menu().getStateId();
        Int2ObjectMap<HashedStack> wrongSlotMap = new Int2ObjectOpenHashMap<>();
        // Predict dirt x64 instead of stone x64 placed in slot 37
        wrongSlotMap.put(37, HashedStack.create(new ItemStack(Items.DIRT, 64), HASHER));
        p.clickBoth(0, state, (short) 37, (byte) 0, ContainerInput.PICKUP, wrongSlotMap, HashedStack.EMPTY);
        equalBoth("prediction: corrupted slot hash", p);
        check(p.kPackets.size() == 1 && p.kPackets.get(0).id() == PacketIds.PLAY_CONTAINER_SET_SLOT,
                "expected set slot packet (0x14) on corrupted slot prediction");
        if (!p.kPackets.isEmpty() && p.kPackets.getFirst().id() == PacketIds.PLAY_CONTAINER_SET_SLOT) {
            ClientboundContainerSetSlotPacket slotPkt = decodeSlot(p.kPackets.get(0));
            check(slotPkt.getSlot() == 37 && slotPkt.getItem().is(Items.STONE) && slotPkt.getItem().getCount() == 64,
                    "set slot packet repaired slot 37 to stone x64");
        }

        // 3. Packet with corrupted cursor HashedStack prediction -> emits ClientboundSetCursorItemPacket (0x60)
        p.clearPackets();
        setBoth(p, 0, new ItemStack(Items.EMERALD, 5));
        p.broadcast();
        p.clearPackets();
        state = p.k.menu().getStateId();
        Int2ObjectMap<HashedStack> cursorSlotMap = new Int2ObjectOpenHashMap<>();
        cursorSlotMap.put(36, HashedStack.EMPTY);
        // Predict diamond cursor instead of emerald cursor
        HashedStack wrongCursor = HashedStack.create(new ItemStack(Items.DIAMOND, 5), HASHER);
        p.clickBoth(0, state, (short) 36, (byte) 0, ContainerInput.PICKUP, cursorSlotMap, wrongCursor);
        equalBoth("prediction: corrupted cursor hash", p);
        check(p.kPackets.size() == 1 && p.kPackets.get(0).id() == PacketIds.PLAY_SET_CURSOR_ITEM,
                "expected set cursor packet (0x60) on corrupted cursor prediction");
        if (!p.kPackets.isEmpty() && p.kPackets.getFirst().id() == PacketIds.PLAY_SET_CURSOR_ITEM) {
            ClientboundSetCursorItemPacket cursorPkt = decodeCursor(p.kPackets.get(0));
            check(cursorPkt.contents().is(Items.EMERALD) && cursorPkt.contents().getCount() == 5,
                    "set cursor packet repaired cursor to emerald x5");
        }
    }

    private static void checkComponentPrediction() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        ItemStack named = new ItemStack(Items.STONE, 3);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("oracle stone"));
        setCarriedBoth(p, named);
        p.broadcast();
        p.clearPackets();
        Int2ObjectMap<HashedStack> prediction = new Int2ObjectOpenHashMap<>();
        prediction.put(37, HashedStack.create(named, HASHER));
        p.clickBoth(0, p.k.menu().getStateId(), (short) 37, (byte) 0,
                ContainerInput.PICKUP, prediction, HashedStack.EMPTY);
        equalBoth("component prediction: matching custom-name hash", p);
        check(p.kPackets.isEmpty(), "correct component hash suppresses repairs");

        ItemStack wrongName = named.copy();
        wrongName.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("wrong stone"));
        p.clearPackets();
        prediction.clear();
        prediction.put(37, HashedStack.EMPTY);
        p.clickBoth(0, p.k.menu().getStateId(), (short) 37, (byte) 0,
                ContainerInput.PICKUP, prediction, HashedStack.create(wrongName, HASHER));
        equalBoth("component prediction: wrong custom-name hash", p);
        check(p.kPackets.size() == 1 && p.kPackets.getFirst().id() == PacketIds.PLAY_SET_CURSOR_ITEM,
                "same item and count with wrong component hash repairs cursor");
        if (!p.kPackets.isEmpty() && p.kPackets.getFirst().id() == PacketIds.PLAY_SET_CURSOR_ITEM) {
            check(ItemStack.matches(decodeCursor(p.kPackets.getFirst()).contents(), named),
                    "component repair preserves the authoritative custom name");
        }
    }

    /**
     * Transaction isolation under a reentrant container callback. The callback
     * only requests vanilla broadcastChanges; vanilla's handler and menu decide
     * whether anything can be sent before the client hashes are adopted
     * (ServerGamePacketListenerImpl.java:1956-1968,
     * AbstractContainerMenu.java:252-286). This is an injected callback fixture,
     * not a claim that the normal inventory currently requests such a broadcast.
     */
    private static void checkReentrantBroadcast() {
        scenarios++;
        Pair p = new Pair();
        installBroadcastingSlot(p.k.menu());
        installBroadcastingSlot(p.v.menu());
        p.initial();
        p.clearPackets();
        Int2ObjectMap<HashedStack> prediction = new Int2ObjectOpenHashMap<>();
        prediction.put(36, HashedStack.EMPTY);
        p.clickBoth(0, p.k.menu().getStateId(), (short) 36, (byte) 0,
                ContainerInput.PICKUP, prediction,
                HashedStack.create(new ItemStack(Items.STONE, 8), HASHER));
        equalBoth("reentrant broadcast: prediction adopted before packets", p);
        check(p.kPackets.isEmpty(), "reentrant broadcast emits no intermediate repair");
        check(p.k.carried().is(Items.STONE) && p.k.carried().getCount() == 8,
                "reentrant fixture picked up eight stone");
    }

    private static void installBroadcastingSlot(InventoryMenu menu) {
        net.minecraft.world.SimpleContainer container = new net.minecraft.world.SimpleContainer(1) {
            @Override public void setChanged() {
                super.setChanged();
                menu.broadcastChanges();
            }
        };
        net.minecraft.world.inventory.Slot slot = new net.minecraft.world.inventory.Slot(container, 0, 0, 0);
        slot.index = 36;
        menu.slots.set(36, slot);
        container.setItem(0, new ItemStack(Items.STONE, 8));
    }

    private static void checkScenario10ContainerClose() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // Player holds gold ingot x16 on the cursor; container close returns it to inventory
        setCarriedBoth(p, new ItemStack(Items.GOLD_INGOT, 16));
        p.broadcast();
        p.clearPackets();

        p.closeBoth();
        equalBoth("close: immediate inventory update", p);
        // Inventory.java:306-327 returns the carried stack with an immediate
        // SetPlayerInventory packet, while the cursor diff waits for the tick.
        check(p.kPackets.size() == 1 && p.kPackets.getFirst().id() == 0x6C,
                "close immediately sends SetPlayerInventory (0x6C), not a menu-close packet");
        check(p.kPackets.stream().noneMatch(pkt -> pkt.id() == PacketIds.PLAY_SET_CURSOR_ITEM),
                "close cursor repair waits for the broadcast tick");
        p.clearPackets();
        // Vanilla's close only runs doCloseContainer; the returned cursor
        // reaches the client on the next tick's broadcastChanges.
        p.broadcast();
        equalBoth("close: return carried stack to inventory", p);
        check(p.k.carried().isEmpty(), "expected cursor empty after container close");
        check(p.k.inventory().contains(new ItemStack(Items.GOLD_INGOT, 16)),
                "expected gold ingot x16 placed back into player inventory");
        check(p.kPackets.stream().anyMatch(pkt -> pkt.id() == PacketIds.PLAY_SET_CURSOR_ITEM),
                "expected cursor packet (0x60) emitted on container close");
    }

    private static void checkScenario11SpectatorAndDying() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // Spectator player attempt
        setFixtureMode(p.kPlayer, GameType.SPECTATOR);
        setFixtureMode(p.vPlayer, GameType.SPECTATOR);
        p.clearPackets();

        int state = p.k.menu().getStateId();
        p.clickBoth(0, state, (short) 36, (byte) 0, ContainerInput.PICKUP, null, null);
        equalBoth("spectator: click rejected with full resync", p);
        check(p.kPackets.size() == 1 && p.kPackets.get(0).id() == PacketIds.PLAY_CONTAINER_SET_CONTENT,
                "spectator rejection expected 0x12 content packet");

        // ServerGamePacketListenerImpl.java:1943: dead players reject real mutations too.
        Pair dead = new Pair();
        dead.initial();
        setBoth(dead, 0, new ItemStack(Items.DIAMOND, 3));
        dead.broadcast();
        dead.kPlayer.setHealth(0.0F);
        dead.vPlayer.setHealth(0.0F);
        dead.clearPackets();
        dead.clickBoth(0, dead.k.menu().getStateId(), (short) 36, (byte) 0,
                ContainerInput.PICKUP, null, null);
        equalBoth("dying: click rejected with full resync", dead);
        check(dead.k.carried().isEmpty() && dead.k.inventory().getItem(0).getCount() == 3,
                "dying rejection preserves slot and empty cursor");
        check(dead.kPackets.size() == 1 && dead.kPackets.get(0).id() == PacketIds.PLAY_CONTAINER_SET_CONTENT,
                "dying rejection expected 0x12 content packet");
    }

    /** Set only the fixture state, using ServerPlayerGameMode.java:84-89. */
    private static void setFixtureMode(ServerPlayer player, GameType mode) {
        try {
            var method = net.minecraft.server.level.ServerPlayerGameMode.class.getDeclaredMethod(
                    "setGameModeForPlayer", GameType.class, GameType.class);
            method.setAccessible(true);
            method.invoke(player.gameMode, mode, player.gameMode.getGameModeForPlayer());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set oracle game-mode state", e);
        }
    }

    private static void checkScenario12InvalidContainerAndSlot() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();

        // Wrong container ID (e.g. 99)
        int state = p.k.menu().getStateId();
        p.clickBoth(99, state, (short) 36, (byte) 0, ContainerInput.PICKUP, null, null);
        equalBoth("invalid: wrong container ID", p);
        check(p.kPackets.isEmpty(), "expected 0 packets for wrong container ID");

        // Invalid slot index (e.g. 500)
        p.clearPackets();
        try {
            p.clickBoth(0, state, (short) 500, (byte) 0, ContainerInput.PICKUP, null, null);
        } catch (RuntimeException e) {
            check(false, "invalid slot must be rejected before clicked, handler threw " + e);
        }
        equalBoth("invalid: out of bounds slot index", p);
        check(p.kPackets.isEmpty(), "expected 0 packets for invalid slot index");
    }

    private static void setBoth(Pair p, int inventorySlot, ItemStack stack) {
        p.k.inventory().setItem(inventorySlot, stack.copy());
        p.v.inventory().setItem(inventorySlot, stack.copy());
    }

    private static void setCarriedBoth(Pair p, ItemStack stack) {
        p.k.setCarried(stack.copy());
        p.v.setCarried(stack.copy());
    }

    private static void equalBoth(String where, Pair p) {
        equalInventory(where, p);
        equalPackets(where, p);
        equalCarried(where, p);
        equalStateId(where, p);
        equalDrops(where, p);
    }

    private static void equalInventory(String where, Pair p) {
        for (int i = 0; i < 46; i++) {
            ItemStack a = p.k.menu().getSlot(i).getItem();
            ItemStack b = p.v.menu().getSlot(i).getItem();
            check(ItemStack.matches(a, b), where + " slot " + i + " differs: k=" + a + " v=" + b);
        }
    }

    private static void equalPackets(String where, Pair p) {
        check(p.kPackets.size() == p.vPackets.size(),
                where + " packet count differs: k=" + p.kPackets.size() + " v=" + p.vPackets.size());
        for (int i = 0; i < Math.min(p.kPackets.size(), p.vPackets.size()); i++) {
            PlayerInventory.Packet a = p.kPackets.get(i);
            PlayerInventory.Packet b = p.vPackets.get(i);
            check(a.id() == b.id(), where + " packet " + i + " id differs: k=" + a.id() + " v=" + b.id());
            check(Arrays.equals(a.payload(), b.payload()), where + " packet " + i + " payload differs byte for byte");
        }
    }

    private static void equalCarried(String where, Pair p) {
        check(ItemStack.matches(p.k.carried(), p.v.carried()),
                where + " carried differs: k=" + p.k.carried() + " v=" + p.v.carried());
    }

    private static void equalStateId(String where, Pair p) {
        check(p.k.menu().getStateId() == p.v.menu().getStateId(),
                where + " stateId differs: k=" + p.k.menu().getStateId() + " v=" + p.v.menu().getStateId());
    }

    private static void equalDrops(String where, Pair p) {
        check(p.kLevel.dropped.size() == p.vLevel.dropped.size(),
                where + " dropped count differs: k=" + p.kLevel.dropped.size() + " v=" + p.vLevel.dropped.size());
        for (int i = 0; i < Math.min(p.kLevel.dropped.size(), p.vLevel.dropped.size()); i++) {
            ItemEntity a = p.kLevel.dropped.get(i);
            ItemEntity b = p.vLevel.dropped.get(i);
            check(ItemStack.matches(a.getItem(), b.getItem()),
                    where + " dropped " + i + " item differs: k=" + a.getItem() + " v=" + b.getItem());
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) failures.add(message);
    }

    private static int clientboundId(net.minecraft.network.protocol.PacketType<?> type) {
        return protocolId(GameProtocols.CLIENTBOUND_TEMPLATE, type);
    }

    private static int serverboundId(net.minecraft.network.protocol.PacketType<?> type) {
        return protocolId(GameProtocols.SERVERBOUND_TEMPLATE, type);
    }

    private static int protocolId(Object template, net.minecraft.network.protocol.PacketType<?> type) {
        int[] result = {-1};
        ((net.minecraft.network.ProtocolInfo.DetailsProvider) template).details()
                .listPackets((candidate, id) -> {
                    if (candidate.equals(type)) result[0] = id;
                });
        return result[0];
    }

    private static ClientboundContainerSetContentPacket decodeContent(PlayerInventory.Packet packet) {
        check(packet.id() == PacketIds.PLAY_CONTAINER_SET_CONTENT, "content packet id " + packet.id());
        return ClientboundContainerSetContentPacket.STREAM_CODEC.decode(registryBuf(packet.payload()));
    }

    private static ClientboundContainerSetSlotPacket decodeSlot(PlayerInventory.Packet packet) {
        check(packet.id() == PacketIds.PLAY_CONTAINER_SET_SLOT, "slot packet id " + packet.id());
        return ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(registryBuf(packet.payload()));
    }

    private static ClientboundSetCursorItemPacket decodeCursor(PlayerInventory.Packet packet) {
        check(packet.id() == PacketIds.PLAY_SET_CURSOR_ITEM, "cursor packet id " + packet.id());
        return ClientboundSetCursorItemPacket.STREAM_CODEC.decode(registryBuf(packet.payload()));
    }

    private static RegistryFriendlyByteBuf registryBuf(byte[] bytes) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), ACCESS);
    }

    private static final class TestLevel implements EntityLevel {
        final List<ItemEntity> dropped = new ArrayList<>();
        private final BlockGetter blocks = new BlockGetter() {
            @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
            @Override public FluidState getFluidState(BlockPos pos) { return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState(); }
            @Override public int getMinY() { return -64; }
            @Override public int getHeight() { return 384; }
        };

        @Override public BlockGetter blocks() { return blocks; }
        @Override public ShapeBoxCache shapeCache() { return null; }
        @Override public int getMinY() { return -64; }
        @Override public LevelReader levelReader() { throw new UnsupportedOperationException(); }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags) { return false; }
        @Override public void levelEvent(int type, BlockPos pos, int data) { }
        @Override public void landingBlockUpdate(KestrelEntity entity, BlockPos pos, BlockState state) { }
        @Override public RandomSource random() { return RandomSource.create(0L); }
        @Override public GameRules gameRules() { return new GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS); }
        @Override public void playSound(double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) { }
        @Override public void entityEvent(KestrelEntity entity, byte event) { throw new UnsupportedOperationException(); }

        @Override
        public boolean addFreshVanillaEntity(Entity entity) {
            if (entity instanceof ItemEntity item) {
                dropped.add(item);
                return true;
            }
            return false;
        }
    }

    /**
     * Both players capture every packet their own connection sends. The
     * Kestrel side is driven through {@link PlayerInventory}'s seam with its
     * own synchronizer; the vanilla side is driven through vanilla's real
     * {@code ServerGamePacketListenerImpl.handleContainerClick} and
     * {@code handleContainerClose} with vanilla's own
     * {@code ServerPlayer.containerSynchronizer} (installed by
     * {@code initInventoryMenu}), its packets encoded by vanilla's codecs.
     */
    private static final class Pair {
        final TestLevel kLevel = new TestLevel();
        final TestLevel vLevel = new TestLevel();
        final List<PlayerInventory.Packet> kPackets = new ArrayList<>();
        final List<PlayerInventory.Packet> vPackets = new ArrayList<>();
        final ServerPlayer kPlayer = KestrelServerPlayer.capturingOwner(kLevel, pkt -> capture(pkt, kPackets));
        final ServerPlayer vPlayer = KestrelServerPlayer.capturingOwner(vLevel, pkt -> capture(pkt, vPackets));
        final PlayerInventory k = new PlayerInventory(kPlayer);
        /** Read and set access to the vanilla player's own inventory and menu; its seam is never used. */
        final PlayerInventory v = new PlayerInventory(vPlayer);

        private static void capture(net.minecraft.network.protocol.Packet<?> packet, List<PlayerInventory.Packet> into) {
            for (dev.kestrel.net.Outbound.Packet encoded : dev.kestrel.net.VanillaPacketBridge.encode(packet)) {
                into.add(new PlayerInventory.Packet(encoded.id(), encoded.payload()));
            }
        }

        void initial() {
            k.sendInitial(kPackets::add);
            vPlayer.initInventoryMenu();
        }

        void broadcast() {
            k.broadcastChanges(kPackets::add);
            vPlayer.containerMenu.broadcastChanges();
        }

        void clearPackets() {
            kPackets.clear();
            vPackets.clear();
            kLevel.dropped.clear();
            vLevel.dropped.clear();
        }

        void clickBoth(int containerId, int stateId, short slotNum, byte buttonNum,
                ContainerInput containerInput, @Nullable Int2ObjectMap<HashedStack> changedSlots,
                @Nullable HashedStack carriedItem) {
            // A real client always sends both; null in a scenario means "no prediction".
            ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(containerId, stateId,
                    slotNum, buttonNum, containerInput,
                    changedSlots == null ? Int2ObjectMaps.emptyMap() : changedSlots,
                    carriedItem == null ? HashedStack.EMPTY : carriedItem);
            k.handleClick(packet, kPackets::add);
            vPlayer.connection.handleContainerClick(packet);
        }

        void closeBoth() {
            k.close(kPackets::add);
            vPlayer.connection.handleContainerClose(new ServerboundContainerClosePacket(0));
        }
    }
}
