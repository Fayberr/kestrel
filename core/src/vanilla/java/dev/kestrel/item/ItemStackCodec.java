package dev.kestrel.item;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;

/**
 * Kestrel's own encode/decode path for {@link ItemStack} over the wire, grounded
 * in {@code ItemStack.java:170-195} (the optional stream codec that backs
 * {@code ItemStack.STREAM_CODEC}) and {@code DataComponentPatch.java:80-154}
 * (the positive/negative patch framing).
 *
 * <p>Kestrel does not reimplement {@link ItemStack}, {@link DataComponentPatch},
 * or any component type: those are vanilla's real classes on the classpath, used
 * directly. What is Kestrel code here is the wire-structure walk itself (item id,
 * patch counts, the positive/negative loops) written independently of vanilla's
 * {@code createOptionalStreamCodec}/{@code createStreamCodec} closures, so that a
 * differential check can prove the two independent implementations agree
 * byte-for-byte. Leaf work (item registry ids, per-component payload
 * serialization) is delegated to vanilla's own {@link Item#STREAM_CODEC} and
 * {@link DataComponentType#streamCodec()}, since reimplementing every component's
 * payload format would be exactly the kind of vanilla-class reimplementation
 * Kestrel's reuse policy forbids.
 */
public final class ItemStackCodec {

    private ItemStackCodec() {
    }

    /** Mirrors {@code ItemStack.java:187}: an empty stack is a single VarInt 0. */
    public static void encode(final RegistryFriendlyByteBuf out, final ItemStack stack) {
        if (stack.isEmpty()) {
            out.writeVarInt(0);
            return;
        }

        out.writeVarInt(stack.getCount());
        encodeItemId(out, stack.typeHolder());
        encodePatch(out, stack.getComponentsPatch());
    }

    /** Mirrors {@code ItemStack.java:174-183}. */
    public static ItemStack decode(final RegistryFriendlyByteBuf in) {
        int count = in.readVarInt();
        if (count <= 0) {
            return ItemStack.EMPTY;
        }

        Holder<Item> item = decodeItemId(in);
        DataComponentPatch patch = decodePatch(in);
        return new ItemStack(item, count, patch);
    }

    private static void encodeItemId(final RegistryFriendlyByteBuf out, final Holder<Item> item) {
        int id = BuiltInRegistries.ITEM.getId(item.value());
        out.writeVarInt(id);
    }

    private static Holder<Item> decodeItemId(final RegistryFriendlyByteBuf in) {
        int id = in.readVarInt();
        Item item = BuiltInRegistries.ITEM.byId(id);
        return item.builtInRegistryHolder();
    }

    /** Mirrors {@code DataComponentPatch.java:82-153}. */
    private static void encodePatch(final RegistryFriendlyByteBuf out, final DataComponentPatch patch) {
        if (patch.isEmpty()) {
            out.writeVarInt(0);
            out.writeVarInt(0);
            return;
        }

        int positiveCount = 0;
        int negativeCount = 0;
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
            if (entry.getValue().isPresent()) {
                positiveCount++;
            } else {
                negativeCount++;
            }
        }

        out.writeVarInt(positiveCount);
        out.writeVarInt(negativeCount);

        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
            if (entry.getValue().isPresent()) {
                encodeComponentEntry(out, entry.getKey(), entry.getValue().get());
            }
        }

        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
            if (entry.getValue().isEmpty()) {
                DataComponentType.STREAM_CODEC.encode(out, entry.getKey());
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void encodeComponentEntry(final RegistryFriendlyByteBuf out, final DataComponentType<T> type, final Object value) {
        DataComponentType.STREAM_CODEC.encode(out, type);
        ((net.minecraft.network.codec.StreamCodec) type.streamCodec()).encode(out, value);
    }

    private static DataComponentPatch decodePatch(final RegistryFriendlyByteBuf in) {
        int positiveCount = in.readVarInt();
        int negativeCount = in.readVarInt();
        if (positiveCount == 0 && negativeCount == 0) {
            return DataComponentPatch.EMPTY;
        }

        DataComponentPatch.Builder builder = DataComponentPatch.builder();
        for (int i = 0; i < positiveCount; i++) {
            DataComponentType<?> type = DataComponentType.STREAM_CODEC.decode(in);
            Object value = type.streamCodec().decode(in);
            setUnchecked(builder, type, value);
        }
        for (int i = 0; i < negativeCount; i++) {
            DataComponentType<?> type = DataComponentType.STREAM_CODEC.decode(in);
            builder.remove(type);
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static <T> void setUnchecked(final DataComponentPatch.Builder builder, final DataComponentType<T> type, final Object value) {
        builder.set(type, (T) value);
    }
}
