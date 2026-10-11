package dev.kestrel.item;

import net.minecraft.world.item.ItemStack;

/**
 * The one genuinely Kestrel-owned piece of the slice 1 item stack model: count
 * enforcement at the boundaries Kestrel itself owns (its own containers, its own
 * network decode path). Vanilla's {@link ItemStack#setCount} does not clamp
 * (that is left to call sites such as {@code ItemStack.limitSize}), and vanilla's
 * own decode path ({@code ItemStack.java:174-183}) accepts any positive VarInt
 * count without a max-stack check, trusting the caller to validate. Kestrel's
 * boundary is stricter: nothing enters a Kestrel-owned container or session over
 * the item's max stack size.
 */
public final class KestrelItemStacks {

    private KestrelItemStacks() {
    }

    /**
     * Clamps {@code stack} to its own {@code getMaxStackSize()}, matching
     * vanilla's {@code ItemStack.limitSize} rule (only shrinks, never grows an
     * under-full stack). A count of 0 or below is treated as empty, matching
     * {@code ItemStack.isEmpty()} ({@code ItemStack.java:320-322}).
     */
    public static ItemStack clampToBoundary(final ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int max = stack.getMaxStackSize();
        if (stack.getCount() > max) {
            stack.setCount(max);
        }
        return stack;
    }
}
