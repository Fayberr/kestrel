package dev.kestrel.loop;

/**
 * Stage 1 facade block state: an opaque handle over a vanilla
 * {@code BlockState}. Callers name only this type; the wrapped vanilla
 * state crosses the seam exclusively inside {@code KestrelLevel} and the
 * loop mechanics internals, which must keep calling real vanilla
 * {@code updateShape}/{@code getSignal} logic.
 *
 * <p>The handle deliberately exposes no block identity, no properties, no
 * equality beyond the wrapped state. Identity reads (is-air, is-wire,
 * power level) stay inside the level/loop seam where vanilla types are
 * legal; the future plugin API adds typed accessors here, not raw casts.
 *
 * <p>Thread-safety: immutable handle. Single tick-thread use like the rest
 * of the loop package.
 */
public final class KestrelState {

    private final net.minecraft.world.level.block.state.BlockState vanilla;

    private KestrelState(net.minecraft.world.level.block.state.BlockState vanilla) {
        this.vanilla = vanilla;
    }

    /**
     * Seam wrap: vanilla state to facade handle. Public so the
     * persistence battery (same package family) can build facade states
     * without naming vanilla types at its call sites more than needed.
     */
    public static KestrelState wrap(net.minecraft.world.level.block.state.BlockState vanilla) {
        return new KestrelState(vanilla);
    }

    /** Seam unwrap: facade handle to vanilla state (level/loop internals only). */
    net.minecraft.world.level.block.state.BlockState unwrap() {
        return vanilla;
    }

    @Override
    public String toString() {
        return "KestrelState(" + vanilla + ")";
    }
}
