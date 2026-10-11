package dev.kestrel.worldgen;

import java.util.function.LongSupplier;

/**
 * The game time worldgen observes through {@code level.getGameTime()}.
 *
 * <p>Vanilla generation reads the live server clock in a few places while a
 * structure places entities. {@code Camel.finalizeSpawn} stores
 * {@code max(0, gameTime - 53)} as the camel's {@code LastPoseTick}, so a
 * desert village placed at game time 619 carries {@code 566L} in its saved
 * NBT. The headless levels used to answer 0 unconditionally, which matched
 * only chunks decorated in the server's first 53 ticks.
 *
 * <p>A server wires {@link #follow} to its level clock. The strict replay
 * gate sets the game time the capture recorded for each structure call.
 * The default is 0, the previous behaviour, for checks with no clock.
 */
public final class WorldgenClock {
    private volatile LongSupplier source = () -> 0L;

    /** Read the live clock on every call (a server's level game time). */
    public void follow(LongSupplier clock) {
        this.source = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** Pin the observed game time to a captured value. */
    public void set(long gameTime) {
        this.source = () -> gameTime;
    }

    public long gameTime() {
        return source.getAsLong();
    }
}
