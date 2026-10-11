package dev.kestrel.item;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * Roadmap G17 slice 1 reproduction gate: Kestrel's own {@link ItemStackCodec}
 * wire encode/decode diffed byte-for-byte against vanilla's own
 * {@code ItemStack.STREAM_CODEC}, grounded in {@code ItemStack.java:128-195} and
 * {@code DataComponentPatch.java:62-154}.
 *
 * <p>Every case is checked three ways: Kestrel's bytes equal vanilla's bytes
 * exactly, vanilla's codec can decode Kestrel's bytes back to an equal stack,
 * and Kestrel's codec can decode vanilla's bytes back to an equal stack.
 */
public final class ItemStackCodecCheck {

    private ItemStackCodecCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bindItemComponents();

        List<String> failures = new ArrayList<>();

        checkEmptyStack(failures);
        checkPlainStack(failures);
        checkComponentStack(failures);
        checkCountViolations(failures);

        if (!failures.isEmpty()) {
            System.err.println("ItemStackCodecCheck FAILED with " + failures.size() + " failure(s):");
            for (String f : failures) {
                System.err.println("  - " + f);
            }
            System.exit(1);
        }
        System.out.println("ItemStackCodecCheck: PASS");
    }

    /**
     * {@code Bootstrap.bootStrap()} registers items but leaves their
     * {@code Holder.Reference} component maps unbound ({@code Item.java:141}
     * only registers an initializer; {@code ReloadableServerResources.java:88-89,114}
     * is the normal caller, and it needs a full datapack-backed registry
     * context, including tag sets this check has no reason to load). Running
     * the real per-item initializers here throws on a missing damage-type
     * tag ({@code Item.java:231}, fire resistance), so this binds every item
     * holder to vanilla's own {@code DataComponents.COMMON_ITEM_COMPONENTS}
     * instead: the exact default prototype vanilla itself falls back to for
     * any item without an initializer entry ({@code DataComponentInitializers
     * .java:75-81}). This is sufficient for this check because
     * {@code ItemStack.STREAM_CODEC} never reads prototype fields (only the
     * explicit patch this check sets), and the one prototype field the count
     * check reads, {@code MAX_STACK_SIZE}, is 64 in both the common default
     * and vanilla's real per-item value for every item this check uses
     * (stone, diamond sword blocks nothing count-related here). It is NOT a
     * substitute for per-item prototypes such as diamond sword's real
     * MAX_DAMAGE; nothing in this check relies on that value.
     */
    private static void bindItemComponents() {
        for (Item item : BuiltInRegistries.ITEM) {
            Holder.Reference<Item> holder = item.builtInRegistryHolder();
            holder.bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        }
    }

    // ---- Case 1: empty stack ----

    private static void checkEmptyStack(List<String> failures) {
        byte[] kestrelBytes = encodeWithKestrel(ItemStack.EMPTY);
        byte[] vanillaBytes = encodeWithVanilla(ItemStack.EMPTY);

        assertEqual(kestrelBytes.length, 1, "empty stack: byte length", failures);
        if (kestrelBytes.length == 1) {
            assertEqual(kestrelBytes[0], (byte) 0, "empty stack: single VarInt 0", failures);
        }
        assertBytesEqual(kestrelBytes, vanillaBytes, "empty stack: Kestrel bytes vs vanilla bytes", failures);

        ItemStack decodedByKestrel = decodeWithKestrel(vanillaBytes);
        ItemStack decodedByVanilla = decodeWithVanilla(kestrelBytes);
        assertField(decodedByKestrel.isEmpty(), "empty stack: Kestrel decodes vanilla bytes as empty", failures);
        assertField(decodedByVanilla.isEmpty(), "empty stack: vanilla decodes Kestrel bytes as empty", failures);
    }

    // ---- Case 2: plain stack, no components ----

    private static void checkPlainStack(List<String> failures) {
        ItemStack stone64 = new ItemStack(Items.STONE, 64);
        roundTripAndDiff(stone64, "stone x64 (no components)", failures);

        byte[] kestrelBytes = encodeWithKestrel(stone64);
        RegistryFriendlyByteBuf buf = wrap(kestrelBytes);
        int count = buf.readVarInt();
        assertEqual(count, 64, "stone x64: count", failures);
        int itemId = buf.readVarInt();
        int expectedItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(Items.STONE);
        assertEqual(itemId, expectedItemId, "stone x64: item registry id", failures);
        int positiveCount = buf.readVarInt();
        int negativeCount = buf.readVarInt();
        assertEqual(positiveCount, 0, "stone x64: patch positive count", failures);
        assertEqual(negativeCount, 0, "stone x64: patch negative count", failures);
        assertEqual(buf.readableBytes(), 0, "stone x64: no trailing bytes", failures);
    }

    // ---- Case 3: stack with components (int, string/component, nested list type) ----

    private static void checkComponentStack(List<String> failures) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD, 1);
        sword.set(DataComponents.DAMAGE, 42);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Kestrel's Edge"));
        sword.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("A sharp blade"), Component.literal("Forged in tests"))));

        roundTripAndDiff(sword, "damaged diamond sword with custom name and lore", failures);
    }

    // ---- Case 4/5: count violations, verified against vanilla's own rule ----

    private static void checkCountViolations(List<String> failures) {
        ItemStack overfull = new ItemStack(Items.STONE, 64);
        overfull.setCount(200);

        boolean vanillaAcceptsAsValid = ItemStack.validateStrict(overfull).result().isPresent();
        assertField(!vanillaAcceptsAsValid, "stack with count 200 (max 64): vanilla validateStrict rejects it", failures);

        int vanillaMax = overfull.getMaxStackSize();
        assertEqual(vanillaMax, 64, "stone: vanilla max stack size", failures);

        ItemStack clamped = KestrelItemStacks.clampToBoundary(overfull.copy());
        assertEqual(clamped.getCount(), vanillaMax, "Kestrel boundary clamp matches vanilla's own max stack size", failures);
        assertField(ItemStack.validateStrict(clamped).result().isPresent(), "clamped stack passes vanilla validateStrict", failures);

        // An empty stack (count 0) is not silently accepted as a "normal" stack: isEmpty() must be true.
        ItemStack zeroCount = new ItemStack(Items.STONE, 0);
        assertField(zeroCount.isEmpty(), "stack with count 0: vanilla isEmpty() is true", failures);
        byte[] zeroBytes = encodeWithKestrel(zeroCount);
        assertEqual(zeroBytes.length, 1, "stack with count 0: encodes as empty (single VarInt 0)", failures);
        assertEqual(zeroBytes[0], (byte) 0, "stack with count 0: encodes as VarInt 0", failures);

        // A negative count is likewise empty per vanilla's own rule (ItemStack.java:320-322).
        ItemStack negativeCount = new ItemStack(Items.STONE, 1);
        negativeCount.setCount(-5);
        assertField(negativeCount.isEmpty(), "stack with negative count: vanilla isEmpty() is true", failures);
    }

    // ---- Shared round-trip + differential oracle helper ----

    private static void roundTripAndDiff(ItemStack stack, String label, List<String> failures) {
        byte[] kestrelBytes = encodeWithKestrel(stack);
        byte[] vanillaBytes = encodeWithVanilla(stack);
        assertBytesEqual(kestrelBytes, vanillaBytes, label + ": Kestrel bytes vs vanilla bytes", failures);

        ItemStack decodedByVanillaFromKestrelBytes = decodeWithVanilla(kestrelBytes);
        assertField(ItemStack.matches(stack, decodedByVanillaFromKestrelBytes),
                label + ": vanilla decodes Kestrel's bytes back to an equal stack", failures);

        ItemStack decodedByKestrelFromVanillaBytes = decodeWithKestrel(vanillaBytes);
        assertField(ItemStack.matches(stack, decodedByKestrelFromVanillaBytes),
                label + ": Kestrel decodes vanilla's bytes back to an equal stack", failures);

        ItemStack decodedByKestrelFromKestrelBytes = decodeWithKestrel(kestrelBytes);
        assertField(ItemStack.matches(stack, decodedByKestrelFromKestrelBytes),
                label + ": Kestrel round trip is exact", failures);
    }

    // ---- Wire helpers ----

    /**
     * The item and data-component-type registries are static (no datapack
     * content), so the static layer of {@code BuiltInRegistries.REGISTRY} is
     * enough for {@code ByteBufCodecs.holderRegistry}/{@code registry} to
     * resolve ids; {@code RegistryAccess.EMPTY} is not, since those codecs
     * look the registry up by key rather than falling back to the built-in
     * registry directly ({@code ByteBufCodecs.java:598-609}).
     */
    private static RegistryAccess.Frozen registryAccess;

    private static RegistryAccess.Frozen registryAccess() {
        if (registryAccess == null) {
            registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        }
        return registryAccess;
    }

    private static RegistryFriendlyByteBuf wrap(byte[] bytes) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registryAccess());
    }

    private static byte[] encodeWithKestrel(ItemStack stack) {
        ByteBuf buf = Unpooled.buffer();
        RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(buf, registryAccess());
        ItemStackCodec.encode(out, stack);
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }

    private static byte[] encodeWithVanilla(ItemStack stack) {
        ByteBuf buf = Unpooled.buffer();
        RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(buf, registryAccess());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(out, stack);
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }

    private static ItemStack decodeWithKestrel(byte[] bytes) {
        return ItemStackCodec.decode(wrap(bytes));
    }

    private static ItemStack decodeWithVanilla(byte[] bytes) {
        return ItemStack.OPTIONAL_STREAM_CODEC.decode(wrap(bytes));
    }

    // ---- Assertions ----

    private static void assertBytesEqual(byte[] actual, byte[] expected, String message, List<String> failures) {
        if (!java.util.Arrays.equals(actual, expected)) {
            failures.add(message + ": expected " + java.util.Arrays.toString(expected) + ", got " + java.util.Arrays.toString(actual));
        }
    }

    private static void assertEqual(Object actual, Object expected, String message, List<String> failures) {
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(message + ": expected <" + expected + ">, got <" + actual + ">");
        }
    }

    private static void assertField(boolean condition, String message, List<String> failures) {
        if (!condition) {
            failures.add(message);
        }
    }
}
