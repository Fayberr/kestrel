package dev.kestrel.hopper;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Random;

/**
 * Microbenchmark for the hopper transfer path: vanilla's
 * {@code addItem(from, container, stack, direction)} shape vs
 * {@link HopperTransfer#addItem}. Both sides run the identical merge math
 * over the identical containers; the difference measured is the code shape
 * (static port vs vanilla's static methods - expected to be near 1x, since
 * the port is line-for-line) plus the {@link HopperState} gating that
 * surrounds it in production.
 *
 * <p>The gating rows are the ones that matter. {@code gateIdle} models the
 * common idle case: a hopper pointing into a FULL chest. Vanilla re-proves
 * it every wake (attach resolution + full scan + failed move loop);
 * Kestrel's {@code HopperState.shouldAttemptEject} answers from a valid
 * flag. {@code gateFull} models the suck side with a full hopper. These
 * rows use {@code SimpleContainer} fullness scans as the vanilla shape
 * (the attach-resolution half needs a live level and is not in this
 * number, same caveat as the E3 secpoi row).
 */
public final class HopperBench {
    private HopperBench() {
    }

    private static final int WARMUP_ITERS = 2_000;
    private static final int MEASURE_ITERS = 20_000;
    private static final int REPEATS = 3;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HopperStateCheckBinderHelper.bind();

        System.out.println("HopperBench (hopper transfer primitives, vanilla vs kestrel)");
        benchAddItem(5);
        benchAddItem(27);
        benchGateIdle();
        benchGateFull();
    }

    // ---- transfer shape: identical math both sides, expect ~1x ----

    private static void benchAddItem(int size) {
        SimpleContainer template = new SimpleContainer(size);
        Random rng = new Random(0x4070E21L & 0xFFFFFFFFL);
        for (int s = 0; s < size; s++) {
            if (rng.nextInt(2) == 0) {
                template.setItem(s, new ItemStack(Items.COBBLESTONE, 1 + rng.nextInt(64)));
            }
        }
        ItemStack incoming = new ItemStack(Items.COBBLESTONE, 32);

        double bestVanilla = Double.MAX_VALUE;
        double bestKestrel = Double.MAX_VALUE;
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            addVanilla(template, incoming, WARMUP_ITERS);
            addKestrel(template, incoming, WARMUP_ITERS);
            bestVanilla = Math.min(bestVanilla, addVanilla(template, incoming, MEASURE_ITERS) / 1.0e6);
            bestKestrel = Math.min(bestKestrel, addKestrel(template, incoming, MEASURE_ITERS) / 1.0e6);
        }
        report("hopadd", size, bestVanilla, bestKestrel);
    }

    /** Vanilla's shape, transcribed (same code as the proof transcription). */
    private static SimpleContainer freshCopy(SimpleContainer template) {
        SimpleContainer dest = new SimpleContainer(template.getContainerSize());
        for (int s = 0; s < template.getContainerSize(); s++) {
            dest.setItem(s, template.getItem(s).copy());
        }
        return dest;
    }

    private static long addVanilla(SimpleContainer template, ItemStack incoming, int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            SimpleContainer dest = freshCopy(template);
            ItemStack stack = incoming.copy();
            int size = dest.getContainerSize();
            for (int s = 0; s < size && !stack.isEmpty(); s++) {
                stack = vanillaTryMove(dest, stack, s);
            }
            guard += stack.isEmpty() ? 1 : 0;
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    private static ItemStack vanillaTryMove(SimpleContainer dest, ItemStack stack, int slot) {
        ItemStack current = dest.getItem(slot);
        // Vanilla gates every slot on canPlaceItemInContainer and, once
        // past the gate, reads container.isEmpty() for the cooldown tail.
        // Both were missing from this transcription originally, which made
        // the "vanilla" side strictly cheaper than the port it was being
        // compared against and turned the hopadd row into a measurement of
        // the omission rather than of the port.
        if (!HopperTransfer.canPlaceItemInContainer(dest, stack, slot, null)) {
            return stack;
        }
        boolean success = false;
        boolean wasEmpty = dest.isEmpty();
        if (current.isEmpty()) {
            dest.setItem(slot, stack);
            stack = ItemStack.EMPTY;
            success = true;
        } else if (current.getCount() <= current.getMaxStackSize()
                && ItemStack.isSameItemSameComponents(current, stack)) {
            int space = stack.getMaxStackSize() - current.getCount();
            int count = Math.min(stack.getCount(), space);
            stack.shrink(count);
            current.grow(count);
            success = count > 0;
        }
        if (success) {
            if (wasEmpty) {
                // Vanilla's cooldown tail reads wasEmpty here. There is no
                // block entity in this fixture, so nothing is set; the read
                // itself is the cost being kept on this side of the scale.
                guardWasEmpty++;
            }
            dest.setChanged();
        }
        return stack;
    }

    /** Sink for the benched wasEmpty read so the JIT cannot elide it. */
    private static int guardWasEmpty;

    private static long addKestrel(SimpleContainer template, ItemStack incoming, int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            SimpleContainer dest = freshCopy(template);
            ItemStack remainder = HopperTransfer.addItem(null, dest, incoming.copy(), null);
            guard += remainder.isEmpty() ? 1 : 0;
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    // ---- gating: the rows that matter ----

    /**
     * Idle eject into a full chest. Vanilla's shape: full-container scan
     * over 27 slots + the failed per-slot move loop (5 remove/add
     * attempts). Kestrel's shape: one flag read via
     * {@code shouldAttemptEject} (flag valid from a prior proof), skip.
     */
    private static void benchGateIdle() {
        SimpleContainer chest = new SimpleContainer(27);
        for (int i = 0; i < 27; i++) {
            chest.setItem(i, new ItemStack(Items.STONE, 64));
        }
        SimpleContainer hopper = new SimpleContainer(5);
        hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
        int[] chestSlots = HopperState.flatSlots(27);

        HopperState state = new HopperState();
        HopperState.Record r = state.recordFor(new net.minecraft.core.BlockPos(0, 1, 0));
        state.reportEject(r, false, true, false); // target known full

        double bestVanilla = Double.MAX_VALUE;
        double bestKestrel = Double.MAX_VALUE;
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            gateIdleVanilla(chest, chestSlots, hopper, WARMUP_ITERS);
            gateIdleKestrel(state, r, chest, chestSlots, hopper, WARMUP_ITERS);
            bestVanilla = Math.min(bestVanilla,
                    gateIdleVanilla(chest, chestSlots, hopper, MEASURE_ITERS) / 1.0e6);
            bestKestrel = Math.min(bestKestrel,
                    gateIdleKestrel(state, r, chest, chestSlots, hopper, MEASURE_ITERS) / 1.0e6);
        }
        report("hopidle", 27, bestVanilla, bestKestrel);
    }

    /** Vanilla's shape: isFullContainer scan, then the eject move loop that fails. */
    private static long gateIdleVanilla(SimpleContainer chest, int[] chestSlots,
                                        SimpleContainer hopper, int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            boolean full = true;
            for (int slot : chestSlots) {
                ItemStack stack = chest.getItem(slot);
                if (stack.getCount() < stack.getMaxStackSize()) {
                    full = false;
                    break;
                }
            }
            if (!full) {
                guard += 1;
                continue;
            }
            // Full: eject loop still attempts each hopper slot and fails.
            for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
                ItemStack stack = hopper.getItem(slot);
                if (!stack.isEmpty()) {
                    guard += stack.getCount();
                }
            }
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    private static long gateIdleKestrel(HopperState state, HopperState.Record r,
                                        SimpleContainer chest, int[] chestSlots,
                                        SimpleContainer hopper, int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            if (!state.shouldAttemptEject(r, hopper.isEmpty())) {
                guard += 0;
                continue;
            }
            guard += 1;
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        // Touch the containers so the loop is not pure dead code.
        if (chest.isEmpty() && hopper.isEmpty()) {
            System.out.println("unreachable containers");
        }
        return elapsed;
    }

    /**
     * Suck side with a full hopper. Vanilla's shape: inventoryFull scan
     * over 5 slots every wake. Kestrel's shape: one flag read.
     */
    private static void benchGateFull() {
        SimpleContainer hopper = new SimpleContainer(5);
        for (int i = 0; i < 5; i++) {
            hopper.setItem(i, new ItemStack(Items.DIRT, 64));
        }
        HopperState state = new HopperState();
        HopperState.Record r = state.recordFor(new net.minecraft.core.BlockPos(3, 1, 0));
        state.reportSuck(r, true, false); // hopper known full

        double bestVanilla = Double.MAX_VALUE;
        double bestKestrel = Double.MAX_VALUE;
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            gateFullVanilla(hopper, WARMUP_ITERS);
            gateFullKestrel(state, r, hopper, WARMUP_ITERS);
            bestVanilla = Math.min(bestVanilla, gateFullVanilla(hopper, MEASURE_ITERS) / 1.0e6);
            bestKestrel = Math.min(bestKestrel, gateFullKestrel(state, r, hopper, MEASURE_ITERS) / 1.0e6);
        }
        report("hopfull", 5, bestVanilla, bestKestrel);
    }

    private static long gateFullVanilla(SimpleContainer hopper, int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            boolean full = HopperState.isHopperFull(java.util.List.of(
                    hopper.getItem(0), hopper.getItem(1), hopper.getItem(2),
                    hopper.getItem(3), hopper.getItem(4)));
            guard += full ? 1 : 0;
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        return elapsed;
    }

    private static long gateFullKestrel(HopperState state, HopperState.Record r,
                                        SimpleContainer hopper, int iters) {
        long start = System.nanoTime();
        int guard = 0;
        for (int i = 0; i < iters; i++) {
            if (!state.shouldAttemptSuck(r)) {
                guard += 0;
                continue;
            }
            guard += 1;
        }
        long elapsed = System.nanoTime() - start;
        if (guard == Integer.MIN_VALUE) {
            System.out.println("unreachable " + guard);
        }
        if (hopper.isEmpty()) {
            System.out.println("unreachable hopper");
        }
        return elapsed;
    }

    private static void report(String label, int n, double vanillaMs, double kestrelMs) {
        double speedup = vanillaMs / Math.max(kestrelMs, 1e-9);
        System.out.printf("  %-9s n=%-4d vanilla=%9.2f ms kestrel=%9.2f ms speedup=%.2fx%n",
                label, n, vanillaMs, kestrelMs, speedup);
    }
}
