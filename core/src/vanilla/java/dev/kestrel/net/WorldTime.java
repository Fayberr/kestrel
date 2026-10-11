package dev.kestrel.net;

import java.util.Random;

/**
 * Overworld day time and weather (tick thread only).
 *
 * <p>Day time is the overworld world clock's totalTicks: +1 per tick
 * at rate 1 like {@code ServerClockManager.ClockInstance.tick}, never
 * wrapping. Vanilla never wraps totalTicks (the moon phase counts days:
 * {@code (totalTicks / 24000) % 8}); Kestrel keeps totalTicks monotonic
 * so the day count and moon phase progress exactly like vanilla.
 * Normal ticks do not raise clock jumps; explicit clock sets (like /time set)
 * raise {@link #consumeClockJump} like vanilla's modifyClock broadcast
 * ({@code ServerClockManager.java:115}).
 *
 * <p>Weather mirrors the rain half of {@code ServerLevel.advanceWeatherCycle}:
 * a rain countdown toggles raining when it hits zero, a zero countdown
 * samples the next one (delay 12000-180000 clear, duration 12000-24000
 * raining, {@code ServerLevel.java:188-189}), and rainLevel eases 0.01
 * per tick toward the state. The sampler is seeded from the world seed
 * so a replay sees the same weather. Clear-weather time (the /weather
 * clear lock) is folded into the rain countdown.
 * THUNDER_STUB: thunder is not simulated (thunderLevel stays 0, no
 * THUNDER_LEVEL_CHANGE is sent).
 */
public final class WorldTime {

    /** Ticks per Minecraft day. */
    public static final int DAY_LENGTH = 24000;

    private static final int RAIN_DELAY_MIN = 12000;
    private static final int RAIN_DELAY_MAX = 180000;
    private static final int RAIN_DURATION_MIN = 12000;
    private static final int RAIN_DURATION_MAX = 24000;

    private final Random random;
    private long dayTime;
    private long theEndTicks;
    private boolean clockJumped;

    private boolean raining;
    private int rainTime;
    private float rainLevel;
    private float oRainLevel;
    private boolean wasRaining;

    public WorldTime(long seed) {
        this.random = new Random(seed);
    }

    /** Advance one tick: clock first, then weather. Clock totalTicks increments and never wraps. */
    public void tick() {
        dayTime++;
        theEndTicks++;
        wasRaining = rainVisible();
        if (rainTime > 0) {
            if (--rainTime == 0) {
                raining = !raining;
            }
        } else if (raining) {
            rainTime = uniform(RAIN_DURATION_MIN, RAIN_DURATION_MAX);
        } else {
            rainTime = uniform(RAIN_DELAY_MIN, RAIN_DELAY_MAX);
        }
        oRainLevel = rainLevel;
        rainLevel = Math.max(0.0f, Math.min(1.0f, rainLevel + (raining ? 0.01f : -0.01f)));
    }

    private int uniform(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    /** Overworld clock totalTicks (monotonic, never wraps; moon phase is (totalTicks / 24000) % 8). */
    public long dayTime() {
        return dayTime;
    }

    /** The End clock totalTicks (monotonic, never wraps). */
    public long theEndTicks() {
        return theEndTicks;
    }

    /** Time of day within current 24000-tick day cycle (0..23999). */
    public long timeOfDay() {
        return dayTime % DAY_LENGTH;
    }

    /** Set the clock (like /time set / setTotalTicks); broadcasts immediately via clock jump. Does not touch the_end. */
    public void setDayTime(long ticks) {
        dayTime = Math.max(0L, ticks);
        clockJumped = true;
    }

    /** True once after a set; the caller broadcasts the new clock. */
    public boolean consumeClockJump() {
        boolean jumped = clockJumped;
        clockJumped = false;
        return jumped;
    }

    /** Force the weather (like /weather): raining for, or clear for, duration ticks. */
    public void setRaining(boolean raining, int durationTicks) {
        this.raining = raining;
        this.rainTime = Math.max(1, durationTicks);
    }

    public boolean raining() {
        return raining;
    }

    /** Ticks until the rain state flips (0 = sample a new countdown next tick). */
    public int rainTime() {
        return rainTime;
    }

    /**
     * G4 restore seam: load saved clocks and weather without raising a
     * clock jump or a rain start/stop edge (the join burst sends both
     * as current state). The sampler is NOT restored: it restarts from
     * the world seed, so the countdown in flight is exact but the one
     * sampled after it differs from an uninterrupted run.
     */
    public void restore(long dayTime, boolean raining, int rainTime, float rainLevel, long theEndTicks) {
        this.dayTime = Math.max(0L, dayTime);
        this.theEndTicks = Math.max(0L, theEndTicks);
        this.clockJumped = false;
        this.raining = raining;
        this.rainTime = Math.max(0, rainTime);
        this.rainLevel = Math.max(0.0f, Math.min(1.0f, rainLevel));
        this.oRainLevel = this.rainLevel;
        this.wasRaining = rainVisible();
    }

    public void restore(long dayTime, boolean raining, int rainTime, float rainLevel) {
        restore(dayTime, raining, rainTime, rainLevel, 0L);
    }

    /** Rain strength 0..1 as the client renders it ({@code Level.getRainLevel}). */
    public float rainLevel() {
        return rainLevel;
    }

    /** Rain level moved this tick (vanilla broadcasts RAIN_LEVEL_CHANGE then). */
    public boolean rainLevelChanged() {
        return oRainLevel != rainLevel;
    }

    /**
     * Client-visible raining flipped on this tick: vanilla compares
     * {@code isRaining()} ({@code rainLevel > 0.2}, {@code Level.java:952})
     * before and after the cycle and broadcasts START_RAINING.
     */
    public boolean rainStarted() {
        return !wasRaining && rainVisible();
    }

    /** Client-visible raining flipped off this tick (vanilla broadcasts STOP_RAINING). */
    public boolean rainStopped() {
        return wasRaining && !rainVisible();
    }

    /** Vanilla isRaining: level above 0.2 (join burst sends START_RAINING when true). */
    public boolean rainVisible() {
        return rainLevel > 0.2;
    }
}
