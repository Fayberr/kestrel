package dev.kestrel.item;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.hash.HashCode;
import com.mojang.serialization.DynamicOps;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import dev.kestrel.net.PacketIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.HashOps;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RemoteSlot;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.function.Consumer;

/**
 * One player's live vanilla inventory and menu.
 *
 * <p>The owner is the session's live vanilla player. The stateful parts are
 * vanilla's real {@link Inventory},
 * {@link EntityEquipment}, {@link InventoryMenu}, remote-slot comparison and
 * state-id counter, so Kestrel does not maintain a second slot model.
 */
public final class PlayerInventory {
    public record Packet(int id, byte[] payload) {}

    static final int HOTBAR_MENU_START = 36;

    private static final RegistryAccess ACCESS =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private static final Consumer<Packet> IGNORED = ignored -> {};

    private ServerPlayer owner;
    private Inventory inventory;
    private InventoryMenu menu;
    private int pendingSelectedSlot;
    private Consumer<Packet> sink = IGNORED;
    private boolean synchronizedMenu;

    public PlayerInventory() {
    }

    public PlayerInventory(ServerPlayer owner) {
        attach(owner);
    }

    public void attach(ServerPlayer owner) {
        if (inventory != null) {
            throw new IllegalStateException("inventory owner already attached");
        }
        this.owner = owner;
        this.inventory = owner.getInventory();
        this.menu = owner.inventoryMenu;
        inventory.setSelectedSlot(pendingSelectedSlot);
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (menu.getSlot(HOTBAR_MENU_START + i).getContainerSlot() != i) {
                throw new IllegalStateException("vanilla hotbar menu mapping changed at " + i);
            }
        }
    }

    public Inventory inventory() {
        return requireInventory();
    }

    public ServerPlayer owner() {
        return requireOwner();
    }

    public InventoryMenu menu() {
        return requireMenu();
    }

    public int selectedSlot() {
        return inventory == null ? pendingSelectedSlot : inventory.getSelectedSlot();
    }

    public void setSelectedSlot(int slot) {
        if (slot >= 0 && slot < Inventory.getSelectionSize()) {
            pendingSelectedSlot = slot;
            if (inventory != null) {
                inventory.setSelectedSlot(slot);
            }
        }
    }

    public void swapOffhand() {
        Inventory inventory = requireInventory();
        ItemStack main = inventory.getSelectedItem();
        ItemStack offhand = inventory.getItem(40);
        inventory.setItem(40, main);
        inventory.setSelectedItem(offhand);
    }

    /** Send the same full state vanilla sends from ServerPlayer.initInventoryMenu. */
    public void sendInitial(Consumer<Packet> target) {
        InventoryMenu menu = requireMenu();
        Consumer<Packet> previous = sink;
        sink = target;
        try {
            if (!synchronizedMenu) {
                synchronizedMenu = true;
                menu.setSynchronizer(new Sync());
            } else {
                menu.sendAllDataToRemote();
            }
        } finally {
            sink = previous;
        }
    }

    /** Run vanilla's remote comparison and state-id sequencing. */
    public void broadcastChanges(Consumer<Packet> target) {
        InventoryMenu menu = requireMenu();
        Consumer<Packet> previous = sink;
        sink = target;
        try {
            if (synchronizedMenu) {
                menu.broadcastChanges();
            }
        } finally {
            sink = previous;
        }
    }

    /** Run vanilla's full state resync. */
    public void broadcastFullState(Consumer<Packet> target) {
        InventoryMenu menu = requireMenu();
        Consumer<Packet> previous = sink;
        sink = target;
        try {
            if (synchronizedMenu) {
                menu.broadcastFullState();
            }
        } finally {
            sink = previous;
        }
    }

    public ItemStack carried() {
        return requireMenu().getCarried();
    }

    public void setCarried(ItemStack stack) {
        requireMenu().setCarried(stack);
    }

    /** ServerGamePacketListenerImpl.java:1939-1975, on the session inventory menu. */
    public void handleClick(int containerId, int stateId, short slotNum, byte buttonNum,
            ContainerInput containerInput, Int2ObjectMap<HashedStack> changedSlots,
            HashedStack carriedItem, Consumer<Packet> target) {
        InventoryMenu menu = requireMenu();
        // ServerGamePacketListenerImpl.java:1941: the action time resets
        // before the container id test, so a stale click still counts.
        if (owner != null) {
            owner.resetLastActionTime();
        }
        if (menu.containerId != containerId) {
            return;
        }
        Consumer<Packet> previous = sink;
        sink = target;
        try {
            if (!synchronizedMenu) {
                synchronizedMenu = true;
                menu.setSynchronizer(new Sync());
            }
            if (owner != null && (owner.isSpectator() || owner.isDeadOrDying())) {
                menu.sendAllDataToRemote();
                return;
            }
            if (owner != null && !menu.stillValid(owner)) {
                return;
            }
            int slotIndex = slotNum;
            if (!menu.isValidSlotIndex(slotIndex)) {
                return;
            }
            boolean fullResyncNeeded = stateId != menu.getStateId();
            menu.suppressRemoteUpdates();
            menu.clicked(slotIndex, buttonNum, containerInput, owner);
            for (Int2ObjectMap.Entry<HashedStack> e : Int2ObjectMaps.fastIterable(changedSlots)) {
                menu.setRemoteSlotUnsafe(e.getIntKey(), e.getValue());
            }
            menu.setRemoteCarried(carriedItem);
            menu.resumeRemoteUpdates();
            if (fullResyncNeeded) {
                menu.broadcastFullState();
            } else {
                menu.broadcastChanges();
            }
        } finally {
            sink = previous;
        }
    }

    public void handleClick(ServerboundContainerClickPacket packet, Consumer<Packet> target) {
        handleClick(packet.containerId(), packet.stateId(), packet.slotNum(), packet.buttonNum(),
                packet.containerInput(), packet.changedSlots(), packet.carriedItem(), target);
    }

    public void handleClick(ServerboundContainerClickPacket packet) {
        handleClick(packet, IGNORED);
    }

    public void handleClick(int containerId, int stateId, short slotNum, byte buttonNum,
            ContainerInput containerInput, Int2ObjectMap<HashedStack> changedSlots,
            HashedStack carriedItem) {
        handleClick(containerId, stateId, slotNum, buttonNum, containerInput, changedSlots, carriedItem, IGNORED);
    }

    public void close(Consumer<Packet> target) {
        if (owner == null) return;
        Consumer<Packet> previous = sink;
        sink = target;
        try {
            // ServerGamePacketListenerImpl.java:1933-1936: only doCloseContainer.
            // The returned cursor reaches the client on the player's next
            // tick (ServerPlayer.tick's containerMenu.broadcastChanges).
            owner.doCloseContainer();
        } finally {
            sink = previous;
        }
    }

    public void close() {
        close(IGNORED);
    }

    private ServerPlayer requireOwner() {
        if (owner == null) throw new IllegalStateException("inventory owner not attached");
        return owner;
    }

    private Inventory requireInventory() {
        if (inventory == null) throw new IllegalStateException("inventory owner not attached");
        return inventory;
    }

    private InventoryMenu requireMenu() {
        if (menu == null) throw new IllegalStateException("inventory owner not attached");
        return menu;
    }

    /** ServerPlayer.java:289-347: cached CRC32C hashes and vanilla remote-slot comparison. */
    private final class Sync implements ContainerSynchronizer {
        private final LoadingCache<TypedDataComponent<?>, Integer> cache = CacheBuilder.newBuilder()
                .maximumSize(256L)
                .build(new CacheLoader<TypedDataComponent<?>, Integer>() {
                    private final DynamicOps<HashCode> registryHashOps =
                            (owner != null ? owner.registryAccess() : ACCESS)
                                    .createSerializationContext(HashOps.CRC32C_INSTANCE);

                    @Override
                    public Integer load(TypedDataComponent<?> component) {
                        return ((HashCode) component.encodeValue(registryHashOps)
                                .getOrThrow(msg -> new IllegalArgumentException("Failed to hash " + component + ": " + msg)))
                                .asInt();
                    }
                });

        @Override
        public void sendInitialData(AbstractContainerMenu container, List<ItemStack> items,
                ItemStack carried, int[] dataSlots) {
            ClientboundContainerSetContentPacket packet = new ClientboundContainerSetContentPacket(
                    container.containerId, container.incrementStateId(), items, carried);
            Packet p = new Packet(PacketIds.PLAY_CONTAINER_SET_CONTENT,
                    encode(packet, ClientboundContainerSetContentPacket.STREAM_CODEC));
            if (sink != IGNORED) {
                sink.accept(p);
            } else if (owner instanceof dev.kestrel.entity.KestrelServerPlayer kp && kp.hasOwner()) {
                kp.owner().selfObserver().send(new dev.kestrel.net.Outbound.Packet(p.id(), p.payload()));
            }
        }

        @Override
        public void sendSlotChange(AbstractContainerMenu container, int slot, ItemStack stack) {
            ClientboundContainerSetSlotPacket packet = new ClientboundContainerSetSlotPacket(
                    container.containerId, container.incrementStateId(), slot, stack);
            Packet p = new Packet(PacketIds.PLAY_CONTAINER_SET_SLOT,
                    encode(packet, ClientboundContainerSetSlotPacket.STREAM_CODEC));
            if (sink != IGNORED) {
                sink.accept(p);
            } else if (owner instanceof dev.kestrel.entity.KestrelServerPlayer kp && kp.hasOwner()) {
                kp.owner().selfObserver().send(new dev.kestrel.net.Outbound.Packet(p.id(), p.payload()));
            }
        }

        @Override
        public void sendCarriedChange(AbstractContainerMenu container, ItemStack stack) {
            ClientboundSetCursorItemPacket packet = new ClientboundSetCursorItemPacket(stack);
            Packet p = new Packet(PacketIds.PLAY_SET_CURSOR_ITEM,
                    encode(packet, ClientboundSetCursorItemPacket.STREAM_CODEC));
            if (sink != IGNORED) {
                sink.accept(p);
            } else if (owner instanceof dev.kestrel.entity.KestrelServerPlayer kp && kp.hasOwner()) {
                kp.owner().selfObserver().send(new dev.kestrel.net.Outbound.Packet(p.id(), p.payload()));
            }
        }

        @Override
        public void sendDataChange(AbstractContainerMenu container, int id, int value) {
            // InventoryMenu has no data slots.
        }

        @Override
        public RemoteSlot createSlot() {
            return new RemoteSlot.Synchronized(cache::getUnchecked);
        }
    }

    private static <T> byte[] encode(T packet,
            net.minecraft.network.codec.StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        ByteBuf bytes = Unpooled.buffer();
        RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(bytes, ACCESS);
        codec.encode(out, packet);
        byte[] result = new byte[bytes.readableBytes()];
        bytes.getBytes(bytes.readerIndex(), result);
        bytes.release();
        return result;
    }

}
