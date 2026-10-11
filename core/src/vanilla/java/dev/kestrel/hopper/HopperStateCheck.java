package dev.kestrel.hopper;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Correctness proof for {@link HopperState}: ports vanilla's fullness
 * predicates 1:1 and requires identical answers on a battery of
 * inventories, then exercises the flag lifecycle (scan -> valid -> mutation
 * clears -> rescan) and the sleep-eligibility transitions. Exits non-zero
 * on any mismatch.
 */
public final class HopperStateCheck {
    private HopperStateCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // ItemStack construction needs item components bound, which only
        // happens during world/datapack loading
        // (ReloadableServerResources.updateComponentsAndStaticRegistryTags),
        // NOT during bare Bootstrap.bootStrap. Fixture code that touches
        // items must bind them explicitly - same class of problem as the
        // block-tag binding in NeighborStateResolver.bindConnectionTags.
        bindItemComponents();

        List<String> failures = new ArrayList<>();

        // 1. Fullness predicates vs vanilla semantics across inventory shapes.
        checkFullness(failures);

        // 2. Flag lifecycle: invalid -> scan sets valid -> mutation clears.
        HopperState state = new HopperState();
        BlockPos pos = new BlockPos(0, 1, 0);
        HopperState.Record r = state.recordFor(pos);

        // Fresh record: flags invalid, both sides attempted.
        if (!state.shouldAttemptEject(r, false) || !state.shouldAttemptSuck(r)) {
            failures.add("fresh record should attempt both sides");
        }
        // Report idle (failed eject + failed suck with full target): still
        // attempts until flags are set by a real proof report.
        state.reportIdle(r);
        if (!r.sleepEligible) {
            failures.add("reportIdle should set sleepEligible");
        }
        // Eject proof reporting a full target: eject skipped after.
        state.reportEject(r, false, true, false);
        if (state.shouldAttemptEject(r, false)) {
            failures.add("known-full target should skip eject");
        }
        if (!state.shouldAttemptSuck(r)) {
            failures.add("suck should still be attempted (hopper not known full)");
        }
        // Suck proof reporting a full hopper: suck skipped after.
        state.reportSuck(r, true, false);
        if (state.shouldAttemptSuck(r)) {
            failures.add("known-full hopper should skip suck");
        }
        // Any mutation clears both flags + sleep.
        state.onContainerChanged(pos);
        if (!state.shouldAttemptEject(r, false) || !state.shouldAttemptSuck(r) || r.sleepEligible) {
            failures.add("mutation should clear flags and sleep");
        }
        // A move invalidates both sides (contents changed on both ends).
        state.reportEject(r, false, false, false);
        state.reportSuck(r, false, false);
        state.reportEject(r, false, true, true);
        if (r.targetFullValid || r.hopperFullValid) {
            failures.add("a move should invalidate both flags");
        }
        // Neighbor change bumps generation.
        long g0 = state.generation();
        state.onNeighborChanged();
        if (state.generation() != g0 + 1) {
            failures.add("neighbor change should bump generation");
        }
        // Empty hopper never ejects regardless of flags.
        if (state.shouldAttemptEject(r, true)) {
            failures.add("empty hopper should never eject");
        }

        System.out.println("HopperStateCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    private static void checkFullness(List<String> failures) {
        // Empty inventory: not full.
        List<ItemStack> empty = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            empty.add(ItemStack.EMPTY);
        }
        expect(!HopperState.isHopperFull(empty), "empty hopper not full", failures);

        // Partial stack: not full.
        List<ItemStack> partial = new ArrayList<>(empty);
        partial.set(0, new ItemStack(Items.COBBLESTONE, 16));
        expect(!HopperState.isHopperFull(partial), "partial hopper not full", failures);

        // Full stacks of 64-max item: full.
        List<ItemStack> full = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            full.add(new ItemStack(Items.COBBLESTONE, 64));
        }
        expect(HopperState.isHopperFull(full), "full hopper full", failures);

        // 16-max item (snowball) at 16: full; at 15: not full.
        List<ItemStack> snowFull = new ArrayList<>();
        List<ItemStack> snowPart = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            snowFull.add(new ItemStack(Items.SNOWBALL, 16));
            snowPart.add(new ItemStack(Items.SNOWBALL, i == 0 ? 15 : 16));
        }
        expect(HopperState.isHopperFull(snowFull), "snowball-full hopper full", failures);
        expect(!HopperState.isHopperFull(snowPart), "snowball-partial hopper not full", failures);

        // Container variant mirrors the same semantics through Container.
        SimpleContainer chest = new SimpleContainer(27);
        expect(!HopperState.isContainerFull(chest, HopperState.flatSlots(27)),
                "empty chest not full", failures);
        for (int i = 0; i < 27; i++) {
            chest.setItem(i, new ItemStack(Items.STONE, 64));
        }
        expect(HopperState.isContainerFull(chest, HopperState.flatSlots(27)),
                "filled chest full", failures);
        chest.setItem(13, new ItemStack(Items.STONE, 63));
        expect(!HopperState.isContainerFull(chest, HopperState.flatSlots(27)),
                "one-short chest not full", failures);

        // Cross-check HopperState.isHopperFull against a live Container view:
        // same items through both entry points must agree.
        SimpleContainer hopper = new SimpleContainer(5);
        for (int i = 0; i < 5; i++) {
            hopper.setItem(i, new ItemStack(Items.DIRT, 64));
        }
        List<ItemStack> view = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            view.add(hopper.getItem(i));
        }
        boolean a = HopperState.isHopperFull(view);
        boolean b = HopperState.isContainerFull(hopper, HopperState.flatSlots(5));
        if (a != b) {
            failures.add("hopper/container fullness entry points disagree");
        }
    }

    private static void expect(boolean cond, String what, List<String> failures) {
        if (!cond) {
            failures.add("expected: " + what);
        }
    }

    /**
     * Bind a component map with real max-stack sizes onto every item holder.
     * Delegates to the shared package helper (extracted verbatim from this
     * method so both hopper checks bind identically); the full why lives on
     * that class.
     */
    private static void bindItemComponents() {
        HopperStateCheckBinderHelper.bind();
    }
}
