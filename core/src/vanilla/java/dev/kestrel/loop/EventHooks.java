package dev.kestrel.loop;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 0 subscriber-bitset hook scaffolding (decided in
 * {@code docs/EVENTS.md}): the tick thread exposes exactly two hook points,
 * {@code BlockChange} (from {@code TickLoop.setBlock}, post-commit,
 * pre-dispatch) and {@code BlockTick} (from the scheduled-tick drain,
 * pre-body). No listeners, no plugin API, no loader yet: registration exists
 * so Stage 3 networking code and the future loader have a seam to call.
 *
 * <p>Facade rule (Stage 1): public signatures take Kestrel types
 * ({@link KestrelPos}, {@link KestrelState}); vanilla types cross the seam
 * only inside the fire methods, which unwrap once per call. Handler code
 * names no vanilla type.
 *
 * <p>Zero-cost rule: every fire checks one int-mask branch first. With zero
 * subscribers the seam costs one predictable branch and zero allocation
 * (no event object touched, no list walk, no boxing).
 *
 * <p>Ordering: handlers run on the tick thread in registration order per
 * point (first-registered fires first), all handlers run even after a
 * cancel (veto is observable, not short-circuit). Events are pooled per
 * point and per nesting depth: a handler whose own write fires the same
 * point again (the compensating {@code setBlock} EVENTS.md allows) gets a
 * separate frame, so the outer event's position, states and cancellation
 * survive the inner fire (High Finding H9). Steady state allocates nothing;
 * a new frame is allocated only the first time a deeper nesting level is
 * reached. Handlers must not retain the event beyond the call.
 *
 * <p>Thread-safety: single tick-thread only. No synchronization.
 */
public final class EventHooks {

    /** Bit for the {@code BlockChange} point in the subscriber mask. */
    public static final int BLOCK_CHANGE_BIT = 1;

    /** Bit for the {@code BlockTick} point in the subscriber mask. */
    public static final int BLOCK_TICK_BIT = 2;

    /**
     * Mutable per-fire {@code BlockChange} event. Cancel means veto
     * propagation: the committed write stands, downstream dispatch the write
     * would have caused does not run.
     */
    public static final class BlockChangeEvent {
        private KestrelPos pos;
        private KestrelState oldState;
        private KestrelState newState;
        private int flags;
        private boolean cancelled;

        private BlockChangeEvent() {
        }

        /** Reset for reuse; called by the fire path, not by handlers. */
        void reset(KestrelPos pos, KestrelState oldState, KestrelState newState, int flags) {
            this.pos = pos;
            this.oldState = oldState;
            this.newState = newState;
            this.flags = flags;
            this.cancelled = false;
        }

        public KestrelPos pos() {
            return pos;
        }

        public KestrelState oldState() {
            return oldState;
        }

        public KestrelState newState() {
            return newState;
        }

        public int flags() {
            return flags;
        }

        /** Veto downstream propagation of this write (the store is unchanged). */
        public void cancel() {
            this.cancelled = true;
        }

        public boolean cancelled() {
            return cancelled;
        }
    }

    /**
     * Mutable per-fire {@code BlockTick} event. Cancel means skip the
     * scheduled body for this firing only; the entry is still consumed, never
     * rescheduled by the cancel itself.
     */
    public static final class BlockTickEvent {
        private KestrelPos pos;
        private KestrelTickTarget target;
        private boolean cancelled;

        private BlockTickEvent() {
        }

        /** Reset for reuse; called by the fire path, not by handlers. */
        void reset(KestrelPos pos, KestrelTickTarget target) {
            this.pos = pos;
            this.target = target;
            this.cancelled = false;
        }

        public KestrelPos pos() {
            return pos;
        }

        public KestrelTickTarget target() {
            return target;
        }

        /** Skip this firing's body (the queue entry is still consumed). */
        public void cancel() {
            this.cancelled = true;
        }

        public boolean cancelled() {
            return cancelled;
        }
    }

    /** Handler for {@code BlockChange}; receives the loop plus the reusable event. */
    public interface BlockChangeHandler {
        void onBlockChange(TickLoop loop, BlockChangeEvent event);
    }

    /** Handler for {@code BlockTick}; receives the loop plus the reusable event. */
    public interface BlockTickHandler {
        void onBlockTick(TickLoop loop, BlockTickEvent event);
    }

    private int mask;
    private final List<BlockChangeHandler> blockChangeHandlers = new ArrayList<>();
    private final List<BlockTickHandler> blockTickHandlers = new ArrayList<>();
    /** Event frames by nesting depth; index 0 is the outermost fire. */
    private final List<BlockChangeEvent> blockChangeFrames = new ArrayList<>();
    private final List<BlockTickEvent> blockTickFrames = new ArrayList<>();
    private int blockChangeDepth;
    private int blockTickDepth;

    /** Subscriber mask: bit per point, set on first registration. */
    public int mask() {
        return mask;
    }

    public boolean hasBlockChange() {
        return (mask & BLOCK_CHANGE_BIT) != 0;
    }

    public boolean hasBlockTick() {
        return (mask & BLOCK_TICK_BIT) != 0;
    }

    /** Register a {@code BlockChange} handler (fires in registration order). */
    public void onBlockChange(BlockChangeHandler handler) {
        blockChangeHandlers.add(handler);
        mask |= BLOCK_CHANGE_BIT;
    }

    /** Register a {@code BlockTick} handler (fires in registration order). */
    public void onBlockTick(BlockTickHandler handler) {
        blockTickHandlers.add(handler);
        mask |= BLOCK_TICK_BIT;
    }

    /**
     * Fire {@code BlockChange} after the store swap and shape updates,
     * before the dispatch the write causes. Returns true when a handler
     * cancelled (caller skips downstream dispatch). Zero subscribers: one
     * mask branch, zero allocation. Seam: wraps vanilla states once per
     * fire; handlers see only the facade handles.
     */
    public boolean fireBlockChange(TickLoop loop, KestrelPos pos,
            net.minecraft.world.level.block.state.BlockState oldVanilla,
            net.minecraft.world.level.block.state.BlockState newVanilla, int flags) {
        if ((mask & BLOCK_CHANGE_BIT) == 0) {
            return false;
        }
        int depth = blockChangeDepth;
        if (depth == blockChangeFrames.size()) {
            blockChangeFrames.add(new BlockChangeEvent());
        }
        BlockChangeEvent event = blockChangeFrames.get(depth);
        event.reset(pos, KestrelState.wrap(oldVanilla), KestrelState.wrap(newVanilla), flags);
        blockChangeDepth = depth + 1;
        try {
            for (int i = 0, n = blockChangeHandlers.size(); i < n; i++) {
                blockChangeHandlers.get(i).onBlockChange(loop, event);
            }
            return event.cancelled();
        } finally {
            blockChangeDepth = depth;
        }
    }

    /**
     * Fire {@code BlockTick} before the scheduled body runs. Returns true
     * when a handler cancelled (caller consumes the entry without running
     * the body). Zero subscribers: one mask branch, zero allocation.
     */
    public boolean fireBlockTick(TickLoop loop, KestrelPos pos, KestrelTickTarget target) {
        if ((mask & BLOCK_TICK_BIT) == 0) {
            return false;
        }
        int depth = blockTickDepth;
        if (depth == blockTickFrames.size()) {
            blockTickFrames.add(new BlockTickEvent());
        }
        BlockTickEvent event = blockTickFrames.get(depth);
        event.reset(pos, target);
        blockTickDepth = depth + 1;
        try {
            for (int i = 0, n = blockTickHandlers.size(); i < n; i++) {
                blockTickHandlers.get(i).onBlockTick(loop, event);
            }
            return event.cancelled();
        } finally {
            blockTickDepth = depth;
        }
    }
}
