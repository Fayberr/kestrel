package dev.kestrel.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

import java.util.HashMap;
import java.util.Map;

/**
 * Connection-side cache for redstone wire: stores the resolved 4-side
 * connection state ({@code NORTH/EAST/SOUTH/WEST} as {@link RedstoneSide})
 * per wire position, so signal reads and propagation planning can reuse it
 * instead of re-running the full neighbor-connection resolution.
 *
 * <p>Why this exists: vanilla's {@code RedStoneWireBlock.getSignal} calls
 * {@code getConnectionState} (4x {@code getConnectingSide} with
 * face-sturdiness + conductor checks, each reading several neighbor states)
 * on EVERY signal read, and signal reads happen per neighbor update per wire
 * (see {@code preresearch/redstone-path-analysis.md} path C). Connections
 * only change on block place/remove, which vanilla already signals through
 * {@code updateShape} / {@code neighborChanged} - so the cached sides stay
 * valid until one of those signals arrives, exactly like
 * {@link dev.kestrel.hopper.HopperState}'s attach-generation pattern.
 *
 * <p>Exactness argument (why caching cannot change behavior): this class
 * never resolves connections itself. Sides enter only via
 * {@link #reportResolved}, called by the integration layer with values
 * produced by vanilla's own real {@code updateShape} /
 * {@code getConnectionState}. The cache is a memo, not a reimplementation:
 * a fresh entry is definitionally equal to what vanilla would compute right
 * now, and any world change that could alter the answer first passes through
 * {@link #onNeighborChanged} (generation bump, conservative: invalidates all
 * entries) or {@link #invalidate} (surgical: one position, called from the
 * shape-update path). Stale entries are never returned: {@link #snapshot}
 * yields {@code null} unless the entry is both resolved and at the current
 * generation.
 *
 * <p>Thread-safety: single tick-thread only, same as vanilla's neighbor
 * dispatch. No synchronization.
 */
public final class WireConnectionCache {

    /** Per-wire cached record, keyed by immutable BlockPos. */
    public static final class Record {
        RedstoneSide north = RedstoneSide.NONE;
        RedstoneSide east = RedstoneSide.NONE;
        RedstoneSide south = RedstoneSide.NONE;
        RedstoneSide west = RedstoneSide.NONE;
        /** True after a real resolution was reported and not yet invalidated. */
        boolean valid = false;
        /** Generation this record was resolved at (see {@link #generation}). */
        long resolvedGeneration = -1;
        /** Successful snapshot reads (diagnostic counter). */
        long hits = 0;
        /** reportResolved calls (diagnostic counter). */
        long resolutions = 0;
        /** invalidate calls hitting this record (diagnostic counter). */
        long invalidations = 0;
    }

    private final Map<BlockPos, Record> records = new HashMap<>();
    private long generation = 0;
    private long queryMisses = 0;

    /** Current connection generation (bumped by neighbor signals). */
    public long generation() {
        return generation;
    }

    /**
     * Signal: a neighbor block change near wires. Bumps the generation,
     * which lazily stales every cached record (same coarse-but-safe pattern
     * as {@code HopperState.onNeighborChanged}). The next {@link #snapshot}
     * for any position returns {@code null} until that position is
     * re-resolved. Coarse invalidation costs at most one re-resolution per
     * wire per neighbor-signal burst - still far cheaper than resolving per
     * signal read.
     */
    public void onNeighborChanged() {
        generation++;
    }

    /** Get-or-create the record for a wire position (stores immutable copy). */
    public Record recordFor(BlockPos pos) {
        return records.computeIfAbsent(pos.immutable(), k -> new Record());
    }

    /**
     * Store a freshly resolved connection state. The sides must come from
     * vanilla's own resolution (real {@code updateShape} result), never
     * recomputed here.
     */
    public void reportResolved(
            Record r, RedstoneSide north, RedstoneSide east, RedstoneSide south, RedstoneSide west) {
        r.north = north;
        r.east = east;
        r.south = south;
        r.west = west;
        r.valid = true;
        r.resolvedGeneration = generation;
        r.resolutions++;
    }

    /**
     * Surgical invalidation for one position: called from the shape-update
     * path ({@code updateShape} with a horizontal or UP direction, or block
     * place/remove at an adjacent cell). The next snapshot returns
     * {@code null} until re-resolved.
     */
    public void invalidate(BlockPos pos) {
        Record r = records.get(pos);
        if (r != null && r.valid) {
            r.valid = false;
            r.invalidations++;
        }
    }

    /** True when the record holds sides resolved at the current generation. */
    public boolean isFresh(Record r) {
        return r.valid && r.resolvedGeneration == generation;
    }

    /**
     * Read the cached sides {@code [NORTH, EAST, SOUTH, WEST]}, or
     * {@code null} when unknown/stale (caller falls back to real vanilla
     * resolution and then {@link #reportResolved}). Never returns stale
     * data: generation mismatch and surgical invalidation both yield null.
     */
    public RedstoneSide[] snapshot(Record r) {
        if (!isFresh(r)) {
            queryMisses++;
            return null;
        }
        r.hits++;
        return new RedstoneSide[]{r.north, r.east, r.south, r.west};
    }

    /** Snapshot misses (unknown or stale reads). Diagnostic counter. */
    public long queryMisses() {
        return queryMisses;
    }

    /** Number of wire positions currently tracked. */
    public int size() {
        return records.size();
    }
}
