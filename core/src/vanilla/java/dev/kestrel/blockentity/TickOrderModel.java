package dev.kestrel.blockentity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Executable model of vanilla 26.2's block-entity tick scheduling: the
 * {@code Level.tickBlockEntities()} walk plus the registration/retirement
 * paths in {@code LevelChunk.updateBlockEntityTicker}.
 *
 * <p><b>What this is for.</b> Step 5 (block-entity tick sleeping) turns
 * entirely on tick ORDER, and the failure mode is a timing divergence that
 * only shows up when a block entity is woken while the walk is in progress.
 * Lithium hit this and documented it in
 * {@code SleepingBlockEntity.sleepOnlyCurrentTick()}. Per the project rule,
 * the trap gets reproduced as a failing case before anything is built to
 * avoid it. This class is the reference side of that differential;
 * {@link TickOrderCheck} drives it.
 *
 * <p><b>What this is NOT.</b> It is not an implementation and nothing on
 * the server path calls it. It is also not proven against a running
 * vanilla {@code Level} - there is no headless way to stand one up, so the
 * fidelity argument here is transcription, not measurement. That argument
 * is only acceptable because the modelled code is short enough to quote in
 * full:
 *
 * <pre>{@code
 * // Level.tickBlockEntities(), Level.java:534
 * this.tickingBlockEntities = true;
 * if (!this.pendingBlockEntityTickers.isEmpty()) {
 *    this.blockEntityTickers.addAll(this.pendingBlockEntityTickers);
 *    this.pendingBlockEntityTickers.clear();
 * }
 * Iterator<TickingBlockEntity> iterator = this.blockEntityTickers.iterator();
 * boolean tickBlockEntities = this.tickRateManager().runsNormally();
 * while (iterator.hasNext()) {
 *    TickingBlockEntity ticker = iterator.next();
 *    if (ticker.isRemoved()) {
 *       iterator.remove();
 *    } else if (tickBlockEntities && this.shouldTickBlocksAt(ticker.getPos())) {
 *       ticker.tick();
 *    }
 * }
 * this.tickingBlockEntities = false;
 *
 * // Level.addBlockEntityTicker(), Level.java:530
 * (this.tickingBlockEntities ? this.pendingBlockEntityTickers : this.blockEntityTickers).add(ticker);
 * }</pre>
 *
 * <p><b>Deliberate simplifications</b>, each of which removes a skip that
 * would only ever make the model tick LESS than vanilla, never differently:
 * {@code tickRateManager().runsNormally()} is assumed true (no
 * freeze/step-tick), and {@code shouldTickBlocksAt} is assumed true (every
 * modelled block entity is inside a ticking chunk). Both are properties of
 * the surrounding world, not of the sleeping mechanism under test. The
 * {@code BoundTickingBlockEntity} preamble (isRemoved / hasLevel /
 * isTicking / getBlockState / type validity) is likewise elided: it gates
 * whether a body runs, and the sleeping question is about WHICH TICK a
 * body runs on, not whether the preamble passes.
 *
 * <p><b>Faithfully modelled, because these are the parts that bite:</b>
 *
 * <ul>
 *   <li>The pending-list deferral. A ticker registered mid-walk lands in
 *       {@code pendingBlockEntityTickers} and therefore cannot run until
 *       the NEXT tick, no matter where in the walk it was registered.</li>
 *   <li>Retirement is two-phase. {@code removeBlockEntityTicker} only
 *       rebinds the wrapper to {@code NULL_TICKER} (whose
 *       {@code isRemoved()} is true); the entry physically leaves the list
 *       later, when the walk next reaches it.</li>
 *   <li>Waking a retired block entity builds a FRESH wrapper and appends
 *       it. Vanilla does not restore the old ordinal position. See
 *       {@code preresearch/block-entity-tick-analysis.md}.</li>
 *   <li>The walk is a live {@code ArrayList} iterator, so a body that
 *       causes a structural change to the list mid-walk is a real
 *       {@link java.util.ConcurrentModificationException} in vanilla too.
 *       The model does not paper over that.</li>
 * </ul>
 */
public final class TickOrderModel {

    /** A block entity's tick body. Mirrors {@code BlockEntityTicker.tick}. */
    @FunctionalInterface
    public interface Body {
        void tick(TickOrderModel level, Entry self, long gameTime);
    }

    /**
     * One entry in {@code blockEntityTickers}. Conflates vanilla's
     * {@code RebindableTickingBlockEntityWrapper} and the
     * {@code BoundTickingBlockEntity} it wraps, which is sound here because
     * the only rebind vanilla ever performs on a live entry is
     * ticker-for-ticker at the same position, and the only rebind that
     * changes scheduling is the one to {@code NULL_TICKER}.
     */
    public static final class Entry {
        /** Stable identity of the block entity, standing in for its BlockPos. */
        public final int id;
        /** Distinguishes successive wrappers for the same id (vanilla makes a new one per wake). */
        public final int generation;
        private Body body;
        private boolean removed;

        private Entry(int id, int generation, Body body) {
            this.id = id;
            this.generation = generation;
            this.body = body;
        }

        /** {@code TickingBlockEntity.isRemoved()}. */
        public boolean isRemoved() {
            return removed;
        }

        /** {@code RebindableTickingBlockEntityWrapper.rebind}, ticker-for-ticker. */
        public void rebind(Body newBody) {
            this.body = newBody;
        }

        @Override
        public String toString() {
            return "#" + id + "g" + generation;
        }
    }

    private final List<Entry> blockEntityTickers = new ArrayList<>();
    private final List<Entry> pendingBlockEntityTickers = new ArrayList<>();
    private boolean tickingBlockEntities;
    private long gameTime;

    /**
     * Observable trace: every effect a body chose to publish, in the order
     * it was published, tagged with the game time it happened on. This is
     * the thing parity is judged on, not the tick sequence itself: a body
     * that runs and does nothing is indistinguishable from one that never
     * ran, which is precisely why sleeping can be exact at all.
     */
    private final List<String> trace = new ArrayList<>();

    private int nextGeneration;

    /**
     * {@code LevelChunk.addAndRegisterBlockEntity} for a block whose
     * {@code getTicker} returned non-null: build a wrapper and hand it to
     * {@code Level.addBlockEntityTicker}.
     */
    public Entry register(int id, Body body) {
        Entry entry = new Entry(id, nextGeneration++, body);
        addBlockEntityTicker(entry);
        return entry;
    }

    /** {@code Level.addBlockEntityTicker(TickingBlockEntity)}, verbatim. */
    public void addBlockEntityTicker(Entry ticker) {
        (tickingBlockEntities ? pendingBlockEntityTickers : blockEntityTickers).add(ticker);
    }

    /**
     * {@code LevelChunk.removeBlockEntityTicker}: rebind to
     * {@code NULL_TICKER}. Note what this does NOT do - it does not touch
     * {@code blockEntityTickers}. The entry stays in the walked list,
     * occupying its ordinal position, until the walk next reaches it and
     * the {@code isRemoved()} branch calls {@code iterator.remove()}.
     */
    public void retire(Entry entry) {
        entry.removed = true;
    }

    /**
     * Vanilla's wake path for a block entity whose {@code getTicker} has
     * gone from null back to non-null: {@code tickersInLevel.compute} finds
     * no existing wrapper (it was removed on retire), so it constructs a
     * new one and appends it. The old ordinal position is lost. This is
     * behavior to reproduce, not a bug to fix.
     */
    public Entry wakeVanillaStyle(int id, Body body) {
        return register(id, body);
    }

    /** {@code Level.tickBlockEntities()}, verbatim modulo the elisions in the class javadoc. */
    public void tickBlockEntities() {
        tickingBlockEntities = true;
        if (!pendingBlockEntityTickers.isEmpty()) {
            blockEntityTickers.addAll(pendingBlockEntityTickers);
            pendingBlockEntityTickers.clear();
        }

        Iterator<Entry> iterator = blockEntityTickers.iterator();
        while (iterator.hasNext()) {
            Entry ticker = iterator.next();
            if (ticker.isRemoved()) {
                iterator.remove();
            } else {
                ticker.body.tick(this, ticker, gameTime);
            }
        }

        tickingBlockEntities = false;
        gameTime++;
    }

    /** Publish an observable effect from inside a tick body. */
    public void emit(String effect) {
        trace.add(gameTime + ":" + effect);
    }

    public List<String> trace() {
        return trace;
    }

    public long gameTime() {
        return gameTime;
    }

    /** Read-only view of the walked list, for structural assertions. */
    public List<Entry> tickers() {
        return List.copyOf(blockEntityTickers);
    }
}
