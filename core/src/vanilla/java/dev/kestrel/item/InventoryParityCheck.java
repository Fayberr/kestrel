package dev.kestrel.item;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import dev.kestrel.net.PacketIds;
import dev.kestrel.net.NetCheck;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Oracle gate for G17 slices 2 and 3. */
public final class InventoryParityCheck {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;
    private static int scenarios;

    private InventoryParityCheck() {}

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        for (net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) {
            item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        }
        packetIds();

        joinEmpty();
        populated();
        carriedSlots();
        swaps();
        slotChanges();
        changeAndSwap();
        liveOrdering();

        if (!failures.isEmpty()) {
            System.out.println("InventoryParityCheck FAILED with " + failures.size() + " failure(s):");
            failures.forEach(s -> System.out.println("  - " + s));
            System.exit(1);
        }
        System.out.println("  " + checks + " checks, " + scenarios + " scenarios");
        System.out.println("InventoryParityCheck: PASS");
    }

    private static void joinEmpty() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        equalPackets("empty join", p);
        check(p.kPackets.size() == 1, "empty join packet count " + p.kPackets.size());
        ClientboundContainerSetContentPacket content = content(p.kPackets.getFirst());
        check(content.containerId() == 0, "join container id " + content.containerId());
        check(content.stateId() == 1, "join state id " + content.stateId());
        check(content.items().size() == 46, "join slot count " + content.items().size());
        check(content.items().stream().allMatch(ItemStack::isEmpty), "join inventory is not empty");
        check(content.carriedItem().isEmpty(), "join cursor is not empty");
    }

    private static void packetIds() {
        check(clientboundId(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT)
                        == PacketIds.PLAY_CONTAINER_SET_CONTENT,
                "container content packet id disagrees with GameProtocols: vanilla="
                        + clientboundId(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT)
                        + " kestrel=" + PacketIds.PLAY_CONTAINER_SET_CONTENT);
        check(clientboundId(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_SLOT)
                        == PacketIds.PLAY_CONTAINER_SET_SLOT,
                "container slot packet id disagrees with GameProtocols: vanilla="
                        + clientboundId(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_SLOT)
                        + " kestrel=" + PacketIds.PLAY_CONTAINER_SET_SLOT);
        check(serverboundId(GamePacketTypes.SERVERBOUND_SET_CARRIED_ITEM)
                        == PacketIds.PLAY_SET_CARRIED_ITEM,
                "set carried item packet id disagrees with GameProtocols: vanilla="
                        + serverboundId(GamePacketTypes.SERVERBOUND_SET_CARRIED_ITEM)
                        + " kestrel=" + PacketIds.PLAY_SET_CARRIED_ITEM);
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

    private static void populated() {
        scenarios++;
        Pair p = new Pair();
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("g17"));
        setBoth(p, 0, new ItemStack(Items.STONE, 64));
        setBoth(p, 4, named);
        setBoth(p, 17, new ItemStack(Items.OAK_LOG, 12));
        setBoth(p, 39, new ItemStack(Items.DIAMOND_HELMET));
        setBoth(p, 40, new ItemStack(Items.SHIELD));
        p.initial();
        equalInventory("populated", p);
        equalPackets("populated", p);
        ClientboundContainerSetContentPacket content = content(p.kPackets.getFirst());
        check(content.items().get(36).getCount() == 64, "hotbar 0 did not map to menu 36");
        check(content.items().get(40).has(DataComponents.CUSTOM_NAME), "component stack lost at menu 40");
        check(content.items().get(17).is(Items.OAK_LOG), "main slot mapping changed");
        check(content.items().get(5).is(Items.DIAMOND_HELMET), "helmet did not map to menu 5");
        check(content.items().get(45).is(Items.SHIELD), "offhand did not map to menu 45");
    }

    private static void carriedSlots() {
        scenarios++;
        Pair p = new Pair();
        for (int slot : new int[]{4, -1, 9, 100}) {
            ServerboundSetCarriedItemPacket decoded = decodeCarried(slot);
            applyCarried(p.k, decoded.getSlot());
            applyCarried(p.v, decoded.getSlot());
            equalInventory("carried " + slot, p);
        }
        check(p.k.selectedSlot() == 4, "invalid carried item changed selection to " + p.k.selectedSlot());
    }

    private static void swaps() {
        swapCase("both full", new ItemStack(Items.STONE, 3), new ItemStack(Items.SHIELD));
        swapCase("main empty", ItemStack.EMPTY, new ItemStack(Items.SHIELD));
        swapCase("offhand empty", new ItemStack(Items.STONE, 3), ItemStack.EMPTY);
        swapCase("both empty", ItemStack.EMPTY, ItemStack.EMPTY);
    }

    private static void swapCase(String name, ItemStack main, ItemStack offhand) {
        scenarios++;
        Pair p = new Pair();
        setBoth(p, 0, main);
        setBoth(p, 40, offhand);
        ServerboundPlayerActionPacket action = decodeSwap();
        check(action.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                name + " inbound action decoded incorrectly");
        p.k.swapOffhand();
        p.v.swapOffhand();
        equalInventory(name, p);
    }

    private static void slotChanges() {
        scenarios++;
        Pair p = new Pair();
        p.initial();
        p.clearPackets();
        setBoth(p, 11, new ItemStack(Items.DIRT, 7));
        p.broadcast();
        equalPackets("single change", p);
        check(p.kPackets.size() == 1, "single change emitted " + p.kPackets.size());
        check(slot(p.kPackets.getFirst()).getSlot() == 11, "main slot emitted wrong menu slot");
        check(slot(p.kPackets.getFirst()).getStateId() == 2, "single change state id did not increment");

        p.clearPackets();
        setBoth(p, 0, new ItemStack(Items.STONE));
        setBoth(p, 1, new ItemStack(Items.DIRT));
        setBoth(p, 40, new ItemStack(Items.SHIELD));
        p.broadcast();
        equalPackets("several changes", p);
        check(slotIds(p.kPackets).equals(List.of(36, 37, 45)),
                "several changes order " + slotIds(p.kPackets));
        check(stateIds(p.kPackets).equals(List.of(3, 4, 5)),
                "several changes state ids " + stateIds(p.kPackets));
    }

    private static void changeAndSwap() {
        scenarios++;
        Pair p = new Pair();
        setBoth(p, 4, new ItemStack(Items.STONE));
        setBoth(p, 40, new ItemStack(Items.SHIELD));
        p.k.setSelectedSlot(4);
        p.v.setSelectedSlot(4);
        p.initial();
        p.clearPackets();
        setBoth(p, 2, new ItemStack(Items.DIRT));
        p.k.swapOffhand();
        p.v.swapOffhand();
        p.broadcast();
        equalPackets("change and swap", p);
        check(slotIds(p.kPackets).equals(List.of(38, 40, 45)),
                "change and swap order " + slotIds(p.kPackets));
    }

    private static void liveOrdering() {
        scenarios++;
        try {
            NetCheck.InventoryOrderProbe probe = NetCheck.inventoryOrderProbe();
            int held = probe.joinPackets().indexOf(PacketIds.PLAY_HELD_SLOT);
            int center = probe.joinPackets().indexOf(PacketIds.PLAY_CACHE_CENTER);
            int content = probe.joinPackets().indexOf(PacketIds.PLAY_CONTAINER_SET_CONTENT);
            check(held >= 0 && center > held && content > center,
                    "real join order is not SetHeldSlot, cache center, container content: "
                            + probe.joinPackets());

            // MinecraftServer:1119-1141 runs the level before Connection.tick.
            // The handling iteration therefore emits tracker/menu level work,
            // then handleUseItemOn's two immediate block updates (the clicked block and the
            // block on the clicked face, ServerGamePacketListenerImpl:1396-1397) and the ack.
            // A placed block returns InteractionResult.SUCCESS (client swing source), so the
            // server sends no animate packet.
            List<Integer> handlingOrder = List.of(
                    PacketIds.PLAY_ENTITY_POSITION_SYNC,
                    PacketIds.PLAY_CONTAINER_SET_SLOT,
                    PacketIds.PLAY_CONTAINER_SET_SLOT,
                    PacketIds.PLAY_BLOCK_UPDATE,
                    PacketIds.PLAY_BLOCK_UPDATE,
                    PacketIds.PLAY_BLOCK_CHANGED_ACK);
            check(probe.handlingIteration().equals(handlingOrder),
                    "handling iteration differs from vanilla " + handlingOrder
                            + ": " + probe.handlingIteration());
            // ServerLevel.sendBlockUpdated:1134-1140 only marks the holder,
            // so its deferred broadcast enters the next level phase. The swap
            // handled last iteration is then observed by ServerPlayer.tick.
            List<Integer> deferredOrder = List.of(
                    PacketIds.PLAY_BLOCK_UPDATE,
                    PacketIds.PLAY_CONTAINER_SET_SLOT,
                    PacketIds.PLAY_CONTAINER_SET_SLOT);
            check(probe.deferredIteration().equals(deferredOrder),
                    "deferred iteration differs from vanilla " + deferredOrder
                            + ": " + probe.deferredIteration());
        } catch (Exception e) {
            failures.add("could not verify live ordering: " + e);
        }
    }

    private static void setBoth(Pair p, int inventorySlot, ItemStack stack) {
        p.k.inventory().setItem(inventorySlot, stack.copy());
        p.v.inventory().setItem(inventorySlot, stack.copy());
    }

    private static void applyCarried(PlayerInventory inventory, int slot) {
        inventory.setSelectedSlot(slot);
    }

    private static ServerboundSetCarriedItemPacket decodeCarried(int slot) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ServerboundSetCarriedItemPacket.STREAM_CODEC.encode(buf, new ServerboundSetCarriedItemPacket(slot));
        return ServerboundSetCarriedItemPacket.STREAM_CODEC.decode(buf);
    }

    private static ServerboundPlayerActionPacket decodeSwap() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ServerboundPlayerActionPacket packet = new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO, Direction.DOWN, 0);
        ServerboundPlayerActionPacket.STREAM_CODEC.encode(buf, packet);
        return ServerboundPlayerActionPacket.STREAM_CODEC.decode(buf);
    }

    private static ClientboundContainerSetContentPacket content(PlayerInventory.Packet packet) {
        check(packet.id() == PacketIds.PLAY_CONTAINER_SET_CONTENT, "content packet id " + packet.id());
        return ClientboundContainerSetContentPacket.STREAM_CODEC.decode(registryBuf(packet.payload()));
    }

    private static ClientboundContainerSetSlotPacket slot(PlayerInventory.Packet packet) {
        check(packet.id() == PacketIds.PLAY_CONTAINER_SET_SLOT, "slot packet id " + packet.id());
        return ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(registryBuf(packet.payload()));
    }

    private static RegistryFriendlyByteBuf registryBuf(byte[] bytes) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes),
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    private static List<Integer> slotIds(List<PlayerInventory.Packet> packets) {
        return packets.stream().map(InventoryParityCheck::slot)
                .map(ClientboundContainerSetSlotPacket::getSlot).toList();
    }

    private static List<Integer> stateIds(List<PlayerInventory.Packet> packets) {
        return packets.stream().map(InventoryParityCheck::slot)
                .map(ClientboundContainerSetSlotPacket::getStateId).toList();
    }

    private static void equalPackets(String where, Pair p) {
        check(p.kPackets.size() == p.vPackets.size(), where + " packet count differs");
        for (int i = 0; i < Math.min(p.kPackets.size(), p.vPackets.size()); i++) {
            PlayerInventory.Packet a = p.kPackets.get(i);
            PlayerInventory.Packet b = p.vPackets.get(i);
            check(a.id() == b.id() && Arrays.equals(a.payload(), b.payload()),
                    where + " packet " + i + " differs byte for byte");
        }
    }

    private static void equalInventory(String where, Pair p) {
        check(p.k.selectedSlot() == p.v.selectedSlot(), where + " selected slot differs");
        for (int i = 0; i < 43; i++) {
            check(ItemStack.matches(p.k.inventory().getItem(i), p.v.inventory().getItem(i)),
                    where + " inventory slot " + i + " differs");
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) failures.add(message);
    }

    private static final class Pair {
        final PlayerInventory k = new PlayerInventory(dev.kestrel.entity.KestrelServerPlayer.inventoryOwner());
        final PlayerInventory v = new PlayerInventory(dev.kestrel.entity.KestrelServerPlayer.inventoryOwner());
        final List<PlayerInventory.Packet> kPackets = new ArrayList<>();
        final List<PlayerInventory.Packet> vPackets = new ArrayList<>();

        void initial() {
            k.sendInitial(kPackets::add);
            v.sendInitial(vPackets::add);
        }

        void broadcast() {
            k.broadcastChanges(kPackets::add);
            v.broadcastChanges(vPackets::add);
        }

        void clearPackets() {
            kPackets.clear();
            vPackets.clear();
        }
    }
}
