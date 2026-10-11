package dev.kestrel.hopper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Kestrel-side hopper tick state: the memoization layer optimization #2 adds
 * around vanilla's real transfer logic (see
 * {@code preresearch/hopper-path-analysis.md}).
 *
 * <p>Design: vanilla's {@code HopperBlockEntity.pushItemsTick} runs every
 * tick per hopper and re-proves everything from scratch on each wake
 * (attach-target resolution, fullness scans, source scans). This class
 * tracks, per hopper position, the dirty-flag state that lets an awake tick
 * skip provably-redundant work WITHOUT changing any observable outcome:
 *
 * <ul>
 *   <li><b>Fullness flags</b> ({@link #hopperFull}, {@link #targetFull}):
 *       set by scanning once, cleared by any {@code setChanged} on the
 *       respective container. Vanilla calls {@code setChanged} on every
 *       mutation path, so the flags can only be stale in the
 *       all-changes-reported direction. A set flag means "was full at last
 *       look, nothing reported a change since" - safe to skip the rescan.
 *       An unset flag means "scan now, set if still full".</li>
 *   <li><b>Attach generation</b> ({@link #attachGeneration}): the resolved
 *       attach target (block + BE + chest-merge) only changes on neighbor
 *       block updates, which vanilla already signals via
 *       {@code neighborChanged}. The caller bumps the generation on any
 *       neighbor signal for this position; a mismatch against the cached
 *       generation forces re-resolution. Monotonic long, never wraps in
 *       practice.</li>
 *   <li><b>Sleep eligibility</b>: a hopper is sleep-eligible when it is
 *       BOTH source-idle (empty + nothing retrievable above - reported by
 *       the caller after a failed suck) AND sink-idle (target full or
 *       absent - reported after a failed eject). Sleep does NOT unregister
 *       the ticker (parity constraint: `tickBlockEntities` iterates all
 *       tickers, `pushItemsTick` always decrements cooldown first). It only
 *       lets the body return after the decrement. Wake signals: any
 *       `setChanged` on hopper/source/target, any neighbor change, any
 *       `entityInside` item event, any ENABLED toggle. All of those already
 *       flow through methods the caller observes - sleep is purely "skip
 *       the re-proof", never "miss the wakeup".</li>
 * </ul>
 *
 * <p>Thread-safety: single tick-thread only, same as vanilla's ticker loop.
 * No synchronization.
 */
public final class HopperState {

    /** Per-hopper mutable record, keyed by immutable BlockPos. */
    public static final class Record {
        /** Last-seen full flag for the hopper's own 5 slots. */
        boolean hopperFull = false;
        /** Hopper-full flag is valid (a scan actually ran). */
        boolean hopperFullValid = false;
        /** Last-seen full flag for the attach target. */
        boolean targetFull = false;
        /** Target-full flag is valid. */
        boolean targetFullValid = false;
        /** Attach generation this record's cached target was resolved at. */
        long resolvedGeneration = -1;
        /** True after a fully-idle wake (nothing ejectable, nothing suckable). */
        boolean sleepEligible = false;
        /** Ticks spent in cooldown-skip (diagnostic counter for the bench). */
        long cooldownSkips = 0;
        /** Ticks spent in sleep-skip (diagnostic counter for the bench). */
        long sleepSkips = 0;
        /** Full eject+suck proofs run (diagnostic counter for the bench). */
        long fullProofs = 0;
    }

    private final java.util.Map<BlockPos, Record> records = new java.util.HashMap<>();
    private long generation = 0;

    /** Current attach generation (bumped by neighbor signals). */
    public long generation() {
        return generation;
    }

    /** Signal: neighbor block change near any hopper - invalidate attach caches. */
    public void onNeighborChanged() {
        generation++;
    }

    /** Get-or-create the record for a hopper position (stores immutable copy). */
    public Record recordFor(BlockPos pos) {
        return records.computeIfAbsent(pos.immutable(), k -> new Record());
    }

    /** Signal: a container mutated - clear fullness flags touching it. */
    public void onContainerChanged(BlockPos pos) {
        Record r = records.get(pos);
        if (r != null) {
            r.hopperFullValid = false;
            r.targetFullValid = false;
            r.sleepEligible = false;
        }
    }

    /**
     * Eject-side decision: should this wake attempt an eject proof?
     * Returns false (skip) only when the hopper is known-empty or the
     * target is known-full from a still-valid flag. Both flags are set by
     * {@link #reportEject} after real proofs and cleared by any mutation.
     */
    public boolean shouldAttemptEject(Record r, boolean hopperEmpty) {
        if (hopperEmpty) {
            return false;
        }
        return !(r.targetFullValid && r.targetFull);
    }

    /**
     * Suck-side decision: should this wake attempt a suck proof?
     * Returns false (skip) only when the hopper is known-full from a
     * still-valid flag.
     */
    public boolean shouldAttemptSuck(Record r) {
        return !(r.hopperFullValid && r.hopperFull);
    }

    /**
     * Report the outcome of a real eject proof so flags stay truthful.
     *
     * @param hopperEmptyAfter whether the hopper is empty after the attempt
     * @param targetFullAfter  whether the target is full after the attempt
     * @param moved            whether anything moved
     */
    public void reportEject(Record r, boolean hopperEmptyAfter, boolean targetFullAfter, boolean moved) {
        r.hopperFull = false;
        r.hopperFullValid = !hopperEmptyAfter ? false : true;
        if (hopperEmptyAfter) {
            r.hopperFull = false;
        }
        r.targetFull = targetFullAfter;
        r.targetFullValid = true;
        if (moved) {
            r.targetFullValid = false;
            r.hopperFullValid = false;
        }
        r.fullProofs++;
        r.sleepEligible = false;
    }

    /**
     * Report the outcome of a real suck proof.
     *
     * @param hopperFullAfter whether the hopper is full after the attempt
     * @param moved           whether anything moved
     */
    public void reportSuck(Record r, boolean hopperFullAfter, boolean moved) {
        r.hopperFull = hopperFullAfter;
        r.hopperFullValid = true;
        if (moved) {
            r.hopperFullValid = false;
            r.targetFullValid = false;
        }
        r.fullProofs++;
    }

    /** Report a fully-idle wake (failed eject AND failed suck): sleep-eligible. */
    public void reportIdle(Record r) {
        r.sleepEligible = true;
    }

    /**
     * Scan-based fullness predicates, ported 1:1 from vanilla
     * ({@code inventoryFull} / {@code isFullContainer}): full means every
     * slot non-empty AND at max stack size. Pure functions for testability;
     * production callers only reach them when flags are invalid.
     */
    public static boolean isHopperFull(List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (stack.isEmpty() || stack.getCount() != stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    public static boolean isContainerFull(Container container, int[] slots) {
        for (int slot : slots) {
            ItemStack stack = container.getItem(slot);
            if (stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    /** Flat slot list 0..size-1 (mirrors vanilla's CACHED_SLOTS fast path). */
    public static int[] flatSlots(int size) {
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = i;
        }
        return slots;
    }
}
