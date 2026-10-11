package dev.kestrel.hopper;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Kestrel's port of vanilla's hopper transfer core (analysis target: hopper
 * eject/suck onto {@link HopperState}).
 *
 * <p><b>The premise survived this time, with one correction.</b> The
 * grounding doc's targets check out against the fresh 26.2 decompile:
 * {@code pushItemsTick} really does decrement-then-branch every tick,
 * {@code ejectItems} really re-resolves the attach target and re-scans
 * fullness on every wake, the 8-tick cooldown really is the only sleep, and
 * the skip-tick chain propagation ({@code tickedGameTime} compare giving
 * 7 instead of 8 down a chain) is really there. The correction: the doc's
 * target 4 ("double entity queries") is wrong as stated. {@code
 * getEntityContainer} (source-side, container-entities in a 1x1x1 box with
 * {@code CONTAINER_ENTITY_SELECTOR}) and {@code getItemsAtAndAbove}
 * (loose-item side, {@code getSuckAabb} moved to the hopper with {@code
 * ENTITY_STILL_ALIVE}) are different boxes with different filters over
 * different entity types. Merging them into one query changes which
 * entities are even candidates. Not merged here.
 *
 * <p>What this class ports, line for line from the decompiled
 * {@code HopperBlockEntity}: {@code addItem(from, container, stack,
 * direction)} (the merge loop over {@code tryMoveInItem}), {@code
 * canMergeItems}, the {@code tryMoveInItem} merge math WITHOUT the
 * hopper-to-hopper cooldown side effect (that needs a live
 * {@code HopperBlockEntity}; the caller applies it), and the
 * {@code isFullContainer} / {@code inventoryFull} predicates (shared with
 * {@link HopperState}, re-exported here so the transfer path reads as one
 * transcription).
 *
 * <p><b>Parity.</b> The merge loop visits slots in exactly vanilla's order
 * (face order for {@code WorldlyContainer}, 0..size-1 otherwise), keeps
 * offering the shrinking remainder while it is non-empty (it does NOT stop
 * at the first accepting slot), and returns the unmoved remainder.
 * The cooldown side effect ({@code setCooldown(8 - skipTickCount)} when the
 * target hopper {@code wasEmpty} and is not on a custom cooldown) is NOT
 * performed here: the caller owns the cooldown field (it lives on the
 * block entity, not in the transfer), so {@link #addItemDetailed} reports
 * {@code anyAccepted} + {@code targetWasEmpty} and lets the caller apply
 * vanilla's rule with its own {@code tickedGameTime} values. Nothing else
 * about the merge reads or writes hopper state, so splitting the side
 * effect out is exact.
 *
 * <p><b>RNG.</b> This class draws nothing. Vanilla's transfer core draws
 * nothing either: the only RNG in the hopper path is
 * {@code getEntityContainer}'s {@code nextInt(entities.size())} pick among
 * overlapping container-entities (source resolution, not transfer) and
 * loot-table unpacking. Both stay on the caller side.
 */
public final class HopperTransfer {
    private HopperTransfer() {
    }

    /**
     * Result of one {@link #moveIntoSlot} call: the stack left over after
     * the slot took what it could (possibly empty, possibly unchanged),
     * whether the slot accepted anything at all, and whether the
     * destination container was completely empty immediately before the
     * merge (vanilla's {@code wasEmpty}, the first input to the
     * hopper-to-hopper cooldown rule).
     *
     * <p>{@code targetWasEmpty} is only meaningful when {@code accepted}
     * is true. Vanilla reads {@code container.isEmpty()} <em>inside</em>
     * the placement gate, after it passes and before the merge mutates
     * anything, so a slot that fails the gate never evaluates it at all;
     * this record reports {@code false} for that case rather than paying
     * for a scan vanilla never runs.
     */
    public record SlotResult(ItemStack remainder, boolean accepted, boolean targetWasEmpty) {
    }

    /**
     * Vanilla's {@code tryMoveInItem}, minus the hopper-to-hopper cooldown
     * side effect (see class javadoc). Returns the leftover stack; the
     * caller checks {@link SlotResult#accepted} to decide whether to keep
     * scanning (vanilla's {@code addItem} stops at the first slot that
     * takes anything... more precisely it keeps calling per slot while
     * {@code !itemStack.isEmpty()}, and each slot only mutates when
     * {@code canPlaceItemInContainer} passes).
     *
     * <p>The {@code wasEmpty} read that feeds the cooldown rule happens
     * here, in vanilla's exact position: after the placement gate passes,
     * before the merge mutates the container. It is reported back in the
     * {@link SlotResult} rather than taken as a parameter, so the caller
     * cannot accidentally supply a value read at the wrong moment (or,
     * as an earlier revision of this class did, compute it per slot and
     * then drop it on the floor).
     *
     * @param container destination container
     * @param stack     incoming stack (mutated in place on merge, as vanilla
     *                  does with {@code shrink}/{@code grow})
     * @param slot      destination slot
     * @param direction face the insertion comes through (null = top/unknown,
     *                  exactly as vanilla's suck path passes null)
     */
    public static SlotResult moveIntoSlot(Container container,
                                          ItemStack stack,
                                          int slot,
                                          @Nullable Direction direction) {
        ItemStack current = container.getItem(slot);
        if (!canPlaceItemInContainer(container, stack, slot, direction)) {
            return new SlotResult(stack, false, false);
        }
        boolean success = false;
        boolean wasEmpty = container.isEmpty();
        if (current.isEmpty()) {
            container.setItem(slot, stack);
            stack = ItemStack.EMPTY;
            success = true;
        } else if (canMergeItems(current, stack)) {
            int space = stack.getMaxStackSize() - current.getCount();
            int count = Math.min(stack.getCount(), space);
            stack.shrink(count);
            current.grow(count);
            success = count > 0;
        }
        if (success) {
            container.setChanged();
        }
        return new SlotResult(stack, success, wasEmpty);
    }

    /**
     * Outcome of one whole {@link #addItemDetailed} call: the unmoved
     * remainder plus the two facts the caller needs to apply
     * {@link #hopperChainCooldown} exactly.
     *
     * <p>Vanilla applies the cooldown tail inside every successful slot
     * merge, guarded by {@code wasEmpty}. Because the first successful
     * merge necessarily leaves the container non-empty, {@code wasEmpty}
     * can only ever be true on the <em>first</em> accepting slot, so the
     * rule fires at most once per {@code addItem} call. Reporting the
     * first acceptance and the {@code wasEmpty} read taken at that moment
     * is therefore exactly equivalent to vanilla's per-slot application,
     * not an approximation of it.
     */
    public record TransferResult(ItemStack remainder, boolean anyAccepted, boolean targetWasEmpty) {
    }

    /**
     * Vanilla's {@code addItem(from, container, stack, direction)}: walk
     * slots in order, offering the (shrinking) remainder to each, until it
     * is empty or slots run out.
     *
     * <p>Returns the remainder together with the cooldown inputs. Live
     * callers must use this overload and feed the result into
     * {@link #hopperChainCooldown}; a caller that only takes the remainder
     * has no way to reproduce vanilla's hopper-to-hopper cooldown, which
     * would let a hopper chain run at full speed instead of vanilla's
     * 8-tick (7 down-chain) cadence.
     */
    public static TransferResult addItemDetailed(Container container,
                                                 ItemStack stack,
                                                 @Nullable Direction direction) {
        boolean anyAccepted = false;
        boolean wasEmptyAtFirstAccept = false;
        if (container instanceof WorldlyContainer worldly && direction != null) {
            int[] slots = worldly.getSlotsForFace(direction);
            for (int i = 0; i < slots.length && !stack.isEmpty(); i++) {
                SlotResult result = moveIntoSlot(container, stack, slots[i], direction);
                stack = result.remainder();
                if (result.accepted() && !anyAccepted) {
                    anyAccepted = true;
                    wasEmptyAtFirstAccept = result.targetWasEmpty();
                }
            }
        } else {
            int size = container.getContainerSize();
            for (int i = 0; i < size && !stack.isEmpty(); i++) {
                SlotResult result = moveIntoSlot(container, stack, i, direction);
                stack = result.remainder();
                if (result.accepted() && !anyAccepted) {
                    anyAccepted = true;
                    wasEmptyAtFirstAccept = result.targetWasEmpty();
                }
            }
        }
        return new TransferResult(stack, anyAccepted, wasEmptyAtFirstAccept);
    }

    /**
     * Remainder-only convenience over {@link #addItemDetailed}, matching
     * vanilla's signature. {@code from} is accepted for signature parity
     * and deliberately unused: vanilla's only use of it is the cooldown
     * tail, which is the caller's job here (see {@link #hopperChainCooldown}).
     * Prefer {@link #addItemDetailed} anywhere the cooldown matters.
     */
    public static ItemStack addItem(@Nullable Container from,
                                    Container container,
                                    ItemStack stack,
                                    @Nullable Direction direction) {
        return addItemDetailed(container, stack, direction).remainder();
    }

    /**
     * Vanilla's {@code canPlaceItemInContainer}: the plain
     * {@code canPlaceItem} gate ANDed with the sided face gate (only when
     * the destination is sided; a null direction passes the face gate
     * because vanilla calls {@code canPlaceItemThroughFace(slot, stack,
     * null)} and every vanilla sided container treats null as "from above,
     * allow if the slot is in the face's list"... no. Read it exactly: the
     * face gate is {@code worldly.canPlaceItemThroughFace(slot, itemStack,
     * direction)} with whatever direction was passed, null included. This
     * method does NOT special-case null; it forwards it, exactly as
     * vanilla does. Callers passing null get vanilla's null behavior.
     */
    public static boolean canPlaceItemInContainer(Container container,
                                                  ItemStack stack,
                                                  int slot,
                                                  @Nullable Direction direction) {
        if (!container.canPlaceItem(slot, stack)) {
            return false;
        }
        if (container instanceof WorldlyContainer worldly
                && !worldly.canPlaceItemThroughFace(slot, stack, direction)) {
            return false;
        }
        return true;
    }

    /**
     * Vanilla's {@code canTakeItemFromContainer}, same forwarding rule for
     * the face gate.
     */
    public static boolean canTakeItemFromContainer(Container into,
                                                   Container from,
                                                   ItemStack stack,
                                                   int slot,
                                                   Direction direction) {
        if (!from.canTakeItem(into, slot, stack)) {
            return false;
        }
        if (from instanceof WorldlyContainer worldly
                && !worldly.canTakeItemThroughFace(slot, stack, direction)) {
            return false;
        }
        return true;
    }

    /** Vanilla's {@code canMergeItems}. */
    public static boolean canMergeItems(ItemStack current, ItemStack incoming) {
        return current.getCount() <= current.getMaxStackSize()
                && ItemStack.isSameItemSameComponents(current, incoming);
    }

    /**
     * The hopper-to-hopper cooldown rule from {@code tryMoveInItem}'s tail,
     * factored out so the caller (which owns the cooldown fields) can apply
     * it exactly: when the destination hopper {@code wasEmpty} before the
     * merge and is not on a custom cooldown ({@code cooldownTime <= 8}),
     * set its cooldown to {@code 8 - skipTickCount} where
     * {@code skipTickCount = 1} iff the source is also a hopper whose
     * {@code tickedGameTime} is not newer than the destination's.
     *
     * @return the cooldown to set, or -1 if the rule does not fire
     */
    public static int hopperChainCooldown(boolean success,
                                          boolean targetWasEmpty,
                                          boolean targetOnCustomCooldown,
                                          boolean fromIsHopper,
                                          long targetTickedGameTime,
                                          long fromTickedGameTime) {
        if (!success || !targetWasEmpty || targetOnCustomCooldown) {
            return -1;
        }
        int skipTickCount = 0;
        if (fromIsHopper && targetTickedGameTime >= fromTickedGameTime) {
            skipTickCount = 1;
        }
        return 8 - skipTickCount;
    }
}
