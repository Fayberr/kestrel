package dev.kestrel.hopper;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Correctness proof for {@link HopperTransfer}: a literal transcription of
 * vanilla's {@code HopperBlockEntity.addItem(from, container, stack,
 * direction)} (kept in this file, verified below against the decompiled
 * source by shape) drives one side, {@link HopperTransfer#addItem} drives
 * the other, over randomized containers and stacks. Any divergence in the
 * returned remainder or in any slot of the destination fails the run.
 *
 * <p>Plus: the {@code hopperChainCooldown} rule is checked against the
 * exact branch structure of vanilla's {@code tryMoveInItem} tail, and a
 * negative control (a merge that ignores the face order of a sided
 * container) is shown to diverge, proving the trial battery has teeth.
 *
 * <p>Exits non-zero on any mismatch.
 */
public final class HopperTransferCheck {
    private HopperTransferCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HopperStateCheckBinderHelper.bind();

        List<String> failures = new ArrayList<>();
        checkAddItemIdentity(failures);
        checkChainCooldownRule(failures);
        checkCooldownHandoff(failures);
        checkNegativeControl(failures);
        checkNoRandomDraws(failures);

        System.out.println("HopperTransferCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    // ---- transcription of vanilla's addItem/tryMoveInItem ----

    /**
     * Literal transcription of 26.2
     * {@code HopperBlockEntity.addItem(from, container, stack, direction)}
     * + {@code tryMoveInItem}, minus the block-entity cooldown side effect
     * (which needs live hoppers; the cooldown rule is checked separately in
     * {@link #checkChainCooldownRule}). Fidelity is not assumed: the
     * decompiled sources were re-decompiled from the nested
     * {@code server-26.2.jar} this session and the transcription matches the
     * branch structure line for line (empty-slot take vs merge, success flag,
     * setChanged only on success).
     */
    private static ItemStack vanillaAddItem(Container from, Container container,
                                            ItemStack stack, Direction direction) {
        if (container instanceof WorldlyContainer worldly && direction != null) {
            int[] slots = worldly.getSlotsForFace(direction);
            for (int i = 0; i < slots.length && !stack.isEmpty(); i++) {
                stack = vanillaTryMoveInItem(from, container, stack, slots[i], direction);
            }
        } else {
            int size = container.getContainerSize();
            for (int i = 0; i < size && !stack.isEmpty(); i++) {
                stack = vanillaTryMoveInItem(from, container, stack, i, direction);
            }
        }
        return stack;
    }

    private static ItemStack vanillaTryMoveInItem(Container from, Container container,
                                                  ItemStack stack, int slot, Direction direction) {
        ItemStack current = container.getItem(slot);
        if (!vanillaCanPlace(container, stack, slot, direction)) {
            return stack;
        }
        boolean success = false;
        if (current.isEmpty()) {
            container.setItem(slot, stack);
            stack = ItemStack.EMPTY;
            success = true;
        } else if (vanillaCanMerge(current, stack)) {
            int space = stack.getMaxStackSize() - current.getCount();
            int count = Math.min(stack.getCount(), space);
            stack.shrink(count);
            current.grow(count);
            success = count > 0;
        }
        if (success) {
            container.setChanged();
        }
        return stack;
    }

    private static boolean vanillaCanPlace(Container container, ItemStack stack,
                                            int slot, Direction direction) {
        if (!container.canPlaceItem(slot, stack)) {
            return false;
        }
        if (container instanceof WorldlyContainer worldly
                && !worldly.canPlaceItemThroughFace(slot, stack, direction)) {
            return false;
        }
        return true;
    }

    private static boolean vanillaCanMerge(ItemStack a, ItemStack b) {
        return a.getCount() <= a.getMaxStackSize()
                && ItemStack.isSameItemSameComponents(a, b);
    }

    // ---- differential battery ----

    /** A sided container with a non-trivial face order (reverse) + a blocked slot. */
    private static final class SidedFixture extends SimpleContainer implements WorldlyContainer {
        private final int[] face;
        private final int blockedSlot;

        SidedFixture(int size, int[] face, int blockedSlot) {
            super(size);
            this.face = face;
            this.blockedSlot = blockedSlot;
        }

        @Override
        public int[] getSlotsForFace(Direction direction) {
            return face;
        }

        @Override
        public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
            // Face DOWN exposes all face slots; every other face exposes
            // only slot 0. Null direction behaves like DOWN (vanilla
            // containers that accept null do so by ignoring it; this one
            // documents its own rule and both sides share it).
            if (direction == null) {
                return slot != blockedSlot;
            }
            if (direction == Direction.DOWN) {
                return slot != blockedSlot;
            }
            return slot == 0 && slot != blockedSlot;
        }

        @Override
        public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
            return true;
        }
    }

    private static void checkAddItemIdentity(List<String> failures) {
        ItemStack[] stackPool = {
                new ItemStack(Items.COBBLESTONE, 1),
                new ItemStack(Items.COBBLESTONE, 17),
                new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.DIRT, 32),
                new ItemStack(Items.SNOWBALL, 5),
                new ItemStack(Items.SNOWBALL, 16),
                new ItemStack(Items.STONE, 63),
        };
        Random rng = new Random(0x4077E21L & 0xFFFFFFFFL);
        int trials = 0;
        for (int trial = 0; trial < 3000; trial++) {
            int size = 1 + rng.nextInt(9);
            boolean sided = rng.nextBoolean();
            Container vanillaDest;
            Container kestrelDest;
            if (sided) {
                int[] face = new int[size];
                for (int i = 0; i < size; i++) {
                    face[i] = size - 1 - i;
                }
                int blocked = rng.nextInt(size + 1) - 1; // -1 or size = no block
                if (blocked == size) {
                    blocked = -1;
                }
                vanillaDest = new SidedFixture(size, face, blocked);
                kestrelDest = new SidedFixture(size, face, blocked);
            } else {
                vanillaDest = new SimpleContainer(size);
                kestrelDest = new SimpleContainer(size);
            }
            // Random pre-fill (shares the same Item instances is fine:
            // stacks are copied per side below via copy()).
            for (int s = 0; s < size; s++) {
                if (rng.nextInt(3) == 0) {
                    ItemStack fill = stackPool[rng.nextInt(stackPool.length)].copy();
                    fill.setCount(1 + rng.nextInt(fill.getMaxStackSize()));
                    vanillaDest.setItem(s, fill);
                    kestrelDest.setItem(s, fill.copy());
                }
            }
            ItemStack poolStack = stackPool[rng.nextInt(stackPool.length)];
            ItemStack vanillaIncoming = poolStack.copy();
            ItemStack kestrelIncoming = poolStack.copy();
            Direction direction = rng.nextBoolean() ? Direction.DOWN : null;
            if (sided && rng.nextInt(4) == 0) {
                direction = Direction.NORTH;
            }

            ItemStack vanillaRemainder =
                    vanillaAddItem(null, vanillaDest, vanillaIncoming, direction);
            ItemStack kestrelRemainder =
                    HopperTransfer.addItem(null, kestrelDest, kestrelIncoming, direction);
            trials++;

            if (!stacksEqual(vanillaRemainder, kestrelRemainder)) {
                failures.add("trial " + trial + ": remainder differs vanilla=" + describe(vanillaRemainder)
                        + " kestrel=" + describe(kestrelRemainder));
                return;
            }
            for (int s = 0; s < size; s++) {
                if (!stacksEqual(vanillaDest.getItem(s), kestrelDest.getItem(s))) {
                    failures.add("trial " + trial + " slot " + s + ": vanilla="
                            + describe(vanillaDest.getItem(s)) + " kestrel="
                            + describe(kestrelDest.getItem(s)));
                    return;
                }
            }
        }
        System.out.println("          addItem identity: " + trials + " trials, "
                + "plain + reverse-face sided containers, DOWN/null/NORTH faces");
    }

    /**
     * The cooldown tail of {@code tryMoveInItem}, checked branch by branch:
     * fires only on (success AND wasEmpty AND NOT onCustomCooldown), with
     * skipTickCount=1 iff (from is a hopper AND target.tickedGameTime >=
     * from.tickedGameTime).
     */
    private static void checkChainCooldownRule(List<String> failures) {
        // Rule does not fire without success.
        expect(HopperTransfer.hopperChainCooldown(false, true, false, true, 10, 5) == -1,
                "no fire without success", failures);
        // Rule does not fire when the target was not empty.
        expect(HopperTransfer.hopperChainCooldown(true, false, false, true, 10, 5) == -1,
                "no fire when target was not empty", failures);
        // Rule does not fire on a custom cooldown (cooldownTime > 8).
        expect(HopperTransfer.hopperChainCooldown(true, true, true, true, 10, 5) == -1,
                "no fire on custom cooldown", failures);
        // Plain hopper-to-hopper, target ticked at same-or-newer time: 7.
        expect(HopperTransfer.hopperChainCooldown(true, true, false, true, 10, 5) == 7,
                "chain gives 7 when target time >= source time", failures);
        expect(HopperTransfer.hopperChainCooldown(true, true, false, true, 5, 5) == 7,
                "chain gives 7 on equal tick times", failures);
        // Source ticked newer than target: full 8.
        expect(HopperTransfer.hopperChainCooldown(true, true, false, true, 4, 5) == 8,
                "chain gives 8 when source ticked newer", failures);
        // Non-hopper source (chest, etc.): full 8.
        expect(HopperTransfer.hopperChainCooldown(true, true, false, false, 10, 5) == 8,
                "non-hopper source gives 8", failures);
        // Non-hopper DESTINATION never reaches this code in vanilla (the
        // branch is `container instanceof HopperBlockEntity`), so the caller
        // must not call it there; document by asserting the plain-hopper
        // case is the only one with from=true semantics. (No code to test:
        // this is a caller-discipline note, kept as prose, not a check.)
    }

    /**
     * End-to-end check that {@link HopperTransfer#addItemDetailed} actually
     * SURFACES the two cooldown inputs, not just that
     * {@link HopperTransfer#hopperChainCooldown} computes correctly once it
     * is handed them.
     *
     * <p>{@link #checkChainCooldownRule} only ever feeds the rule
     * hand-written literals, so it passes even if the transfer never
     * reports {@code wasEmpty} at all. That is exactly the gap an earlier
     * revision of {@link HopperTransfer} fell into: {@code addItem}
     * computed {@code container.isEmpty()} once per slot, passed it to
     * {@code moveIntoSlot}, which ignored the parameter, and the value was
     * discarded. Remainder and slot contents stayed bit-identical (so the
     * 3000-trial identity battery passed), but a live caller had no way to
     * fire the cooldown, which would have run hopper chains at full speed
     * instead of vanilla's 8-tick / 7-down-chain cadence.
     *
     * <p>Here the vanilla transcription evaluates the cooldown tail the way
     * vanilla does (inside each successful slot merge, guarded by a
     * {@code wasEmpty} read taken after the placement gate) and records
     * every firing. Kestrel's single reported firing must match. A positive
     * coverage guard rejects a vacuous pass, and a negative control replays
     * the dropped-{@code wasEmpty} shape to prove the check has teeth.
     */
    private static void checkCooldownHandoff(List<String> failures) {
        Random rng = new Random(0xC001D00DL);
        ItemStack[] pool = {
            new ItemStack(Items.COBBLESTONE, 32),
            new ItemStack(Items.STICK, 7),
            new ItemStack(Items.DIAMOND_SWORD, 1),
        };
        int trials = 0;
        int fired = 0;
        int droppedDiverged = 0;
        for (int trial = 0; trial < 2000; trial++) {
            int size = 1 + rng.nextInt(6);
            boolean sided = rng.nextBoolean();
            Container vanillaDest;
            Container kestrelDest;
            if (sided) {
                int[] face = new int[size];
                for (int i = 0; i < size; i++) {
                    face[i] = size - 1 - i;
                }
                int blocked = rng.nextInt(4) == 0 ? rng.nextInt(size) : -1;
                vanillaDest = new SidedFixture(size, face, blocked);
                kestrelDest = new SidedFixture(size, face, blocked);
            } else {
                vanillaDest = new SimpleContainer(size);
                kestrelDest = new SimpleContainer(size);
            }
            // Leave the container fully empty on ~1/3 of trials: wasEmpty
            // can only be true there, so this is what gives the check its
            // positive coverage.
            if (rng.nextInt(3) != 0) {
                for (int s = 0; s < size; s++) {
                    if (rng.nextInt(3) == 0) {
                        ItemStack fill = pool[rng.nextInt(pool.length)].copy();
                        fill.setCount(1 + rng.nextInt(fill.getMaxStackSize()));
                        vanillaDest.setItem(s, fill);
                        kestrelDest.setItem(s, fill.copy());
                    }
                }
            }
            ItemStack poolStack = pool[rng.nextInt(pool.length)];
            Direction direction = rng.nextBoolean() ? Direction.DOWN : null;
            if (sided && rng.nextInt(4) == 0) {
                direction = Direction.NORTH;
            }
            boolean fromIsHopper = rng.nextBoolean();
            boolean onCustomCooldown = rng.nextInt(5) == 0;
            long targetTicked = rng.nextInt(20);
            long fromTicked = rng.nextInt(20);

            List<Integer> fires = new ArrayList<>();
            vanillaAddItemRecording(vanillaDest, poolStack.copy(), direction,
                    fromIsHopper, onCustomCooldown, targetTicked, fromTicked, fires);
            HopperTransfer.TransferResult result =
                    HopperTransfer.addItemDetailed(kestrelDest, poolStack.copy(), direction);
            trials++;

            if (fires.size() > 1) {
                failures.add("trial " + trial + ": vanilla fired the cooldown " + fires.size()
                        + " times in one addItem; the at-most-once premise documented on "
                        + "TransferResult is wrong");
                return;
            }
            int vanillaCooldown = fires.isEmpty() ? -1 : fires.get(0);
            int kestrelCooldown = HopperTransfer.hopperChainCooldown(
                    result.anyAccepted(), result.targetWasEmpty(),
                    onCustomCooldown, fromIsHopper, targetTicked, fromTicked);
            if (kestrelCooldown != vanillaCooldown) {
                failures.add("trial " + trial + ": cooldown vanilla=" + vanillaCooldown
                        + " kestrel=" + kestrelCooldown + " (size=" + size + " sided=" + sided
                        + " dir=" + direction + " fromIsHopper=" + fromIsHopper
                        + " onCustomCooldown=" + onCustomCooldown + ")");
                return;
            }
            if (vanillaCooldown != -1) {
                fired++;
            }
            // Negative control: the pre-fix shape, which never surfaced
            // wasEmpty and so could only ever pass false.
            int droppedCooldown = HopperTransfer.hopperChainCooldown(
                    result.anyAccepted(), false,
                    onCustomCooldown, fromIsHopper, targetTicked, fromTicked);
            if (droppedCooldown != vanillaCooldown) {
                droppedDiverged++;
            }
        }
        if (fired == 0) {
            failures.add("cooldown handoff: the rule never fired in " + trials
                    + " trials, so the check is vacuous");
            return;
        }
        if (droppedDiverged == 0) {
            failures.add("cooldown handoff negative control never diverged: the check "
                    + "cannot tell a surfaced wasEmpty from a dropped one");
            return;
        }
        System.out.println("          cooldown handoff: " + trials + " trials, rule fired in "
                + fired + "; dropping wasEmpty (the pre-fix shape) diverged in "
                + droppedDiverged + " (the handoff is load-bearing)");
    }

    /**
     * Vanilla's {@code addItem} walk, with the cooldown tail evaluated in
     * vanilla's own position and every firing recorded into {@code fires}.
     * The destination is treated as a hopper block entity (vanilla's
     * {@code container instanceof HopperBlockEntity} branch), since that is
     * the only case in which the rule can fire at all.
     */
    private static ItemStack vanillaAddItemRecording(Container container, ItemStack stack,
                                                     Direction direction, boolean fromIsHopper,
                                                     boolean onCustomCooldown, long targetTicked,
                                                     long fromTicked, List<Integer> fires) {
        if (container instanceof WorldlyContainer worldly && direction != null) {
            int[] slots = worldly.getSlotsForFace(direction);
            for (int i = 0; i < slots.length && !stack.isEmpty(); i++) {
                stack = vanillaSlotRecording(container, stack, slots[i], direction, fromIsHopper,
                        onCustomCooldown, targetTicked, fromTicked, fires);
            }
        } else {
            int size = container.getContainerSize();
            for (int i = 0; i < size && !stack.isEmpty(); i++) {
                stack = vanillaSlotRecording(container, stack, i, direction, fromIsHopper,
                        onCustomCooldown, targetTicked, fromTicked, fires);
            }
        }
        return stack;
    }

    private static ItemStack vanillaSlotRecording(Container container, ItemStack stack, int slot,
                                                  Direction direction, boolean fromIsHopper,
                                                  boolean onCustomCooldown, long targetTicked,
                                                  long fromTicked, List<Integer> fires) {
        ItemStack current = container.getItem(slot);
        if (!vanillaCanPlace(container, stack, slot, direction)) {
            return stack;
        }
        boolean success = false;
        boolean wasEmpty = container.isEmpty();
        if (current.isEmpty()) {
            container.setItem(slot, stack);
            stack = ItemStack.EMPTY;
            success = true;
        } else if (vanillaCanMerge(current, stack)) {
            int space = stack.getMaxStackSize() - current.getCount();
            int count = Math.min(stack.getCount(), space);
            stack.shrink(count);
            current.grow(count);
            success = count > 0;
        }
        if (success) {
            if (wasEmpty && !onCustomCooldown) {
                int skipTickCount = 0;
                if (fromIsHopper && targetTicked >= fromTicked) {
                    skipTickCount = 1;
                }
                fires.add(8 - skipTickCount);
            }
            container.setChanged();
        }
        return stack;
    }

    /**
     * Negative control: a merge that walks slots 0..n IGNORING the face
     * order must diverge from vanilla on a reverse-face sided container.
     * If it never diverges, the battery has no teeth.
     */
    private static void checkNegativeControl(List<String> failures) {
        int divergences = 0;
        Random rng = new Random(0xBADFACE7 & 0xFFFFFFFFL);
        for (int trial = 0; trial < 200; trial++) {
            int size = 2 + rng.nextInt(5);
            int[] face = new int[size];
            for (int i = 0; i < size; i++) {
                face[i] = size - 1 - i;
            }
            SidedFixture vanillaDest = new SidedFixture(size, face, -1);
            SidedFixture naiveDest = new SidedFixture(size, face, -1);
            // Fill slot 0 (first in naive order, LAST in face order) with a
            // partial stack the incoming stack could merge into.
            vanillaDest.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
            naiveDest.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
            ItemStack incoming = new ItemStack(Items.COBBLESTONE, 10);

            ItemStack vanillaRemainder =
                    vanillaAddItem(null, vanillaDest, incoming.copy(), Direction.DOWN);
            // Naive: offer to slots 0..n directly, skipping the face order.
            ItemStack naive = incoming.copy();
            for (int s = 0; s < size && !naive.isEmpty(); s++) {
                naive = vanillaTryMoveInItem(null, naiveDest, naive, s, Direction.DOWN);
            }
            boolean sameRemainder = stacksEqual(vanillaRemainder, naive);
            boolean sameSlots = true;
            for (int s = 0; s < size; s++) {
                if (!stacksEqual(vanillaDest.getItem(s), naiveDest.getItem(s))) {
                    sameSlots = false;
                    break;
                }
            }
            if (!sameRemainder || !sameSlots) {
                divergences++;
            }
        }
        if (divergences == 0) {
            failures.add("negative control never diverged: the battery cannot tell "
                    + "face-order from slot-order, so the identity claim is unproven");
            return;
        }
        System.out.println("          negative control: naive slot-order diverged in "
                + divergences + "/200 trials (face order is load-bearing)");
    }

    private static void checkNoRandomDraws(List<String> failures) {
        byte[] bytes = readClassBytes(HopperTransfer.class);
        if (bytes == null) {
            failures.add("rng: could not read HopperTransfer class bytes");
            return;
        }
        String constants = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String forbidden : List.of("RandomSource", "getRandom", "nextInt",
                "nextDouble", "nextFloat", "java/util/Random")) {
            if (constants.contains(forbidden)) {
                failures.add("rng: HopperTransfer references '" + forbidden
                        + "'; the transfer path must draw no randomness");
            }
        }
    }

    // ---- small helpers ----

    private static boolean stacksEqual(ItemStack a, ItemStack b) {
        if (a.isEmpty() && b.isEmpty()) {
            return true;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.getCount() == b.getCount()
                && ItemStack.isSameItemSameComponents(a, b);
    }

    private static String describe(ItemStack s) {
        if (s.isEmpty()) {
            return "EMPTY";
        }
        return s.getItem() + " x" + s.getCount();
    }

    private static void expect(boolean cond, String what, List<String> failures) {
        if (!cond) {
            failures.add("expected: " + what);
        }
    }

    private static byte[] readClassBytes(Class<?> cls) {
        String resource = "/" + cls.getName().replace('.', '/') + ".class";
        try (java.io.InputStream in = HopperTransferCheck.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

}
