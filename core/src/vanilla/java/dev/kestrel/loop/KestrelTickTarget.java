package dev.kestrel.loop;

/**
 * Stage 1 facade handle for a scheduled-tick target: which block kind the
 * queued entry fires for, without naming the vanilla {@code Block} or
 * {@code TickPriority} types on the public path. The loop keeps the real
 * vanilla queue entries internally; this handle is what hook handlers see.
 *
 * <p>Only the two scheduled bodies the loop models exist (torch LIT flip,
 * repeater POWERED flip); anything else is {@code OTHER}. Priority rides
 * along as a facade enum so handlers can read scheduling intent without
 * touching vanilla types.
 *
 * <p>Thread-safety: immutable. Single tick-thread use like the rest of the
 * loop package.
 */
public record KestrelTickTarget(Kind kind, Priority priority) {

    /** Block kinds the loop can schedule. */
    public enum Kind {
        REDSTONE_TORCH,
        REPEATER,
        OTHER
    }

    /**
     * Facade mirror of all seven vanilla tick priorities, one to one, with
     * vanilla's int value ({@code TickPriority.java}: -3..3). Lossless in
     * both directions, so a save/reload or a hook round trip cannot change
     * the drain order of an equal-time tick.
     *
     * <p>Declaration order is NOT priority order: the KEX sidecar persists
     * {@code ordinal()}, and the first four constants keep the ordinals
     * they had when the facade only had four values, so existing saves
     * read back unchanged. Compare by {@link #value()}, never by ordinal.
     */
    public enum Priority {
        VERY_HIGH(-2),
        HIGH(-1),
        NORMAL(0),
        LOW(1),
        EXTREMELY_HIGH(-3),
        VERY_LOW(2),
        EXTREMELY_LOW(3);

        private final int value;

        Priority(int value) {
            this.value = value;
        }

        /** Vanilla's int value ({@code TickPriority.getValue}). */
        public int value() {
            return value;
        }

        /**
         * Priority for a saved int value, clamped like vanilla's
         * {@code TickPriority.byValue}: below -3 is EXTREMELY_HIGH, above 3
         * is EXTREMELY_LOW.
         */
        public static Priority byValue(int value) {
            for (Priority p : values()) {
                if (p.value == value) {
                    return p;
                }
            }
            return value < EXTREMELY_HIGH.value ? EXTREMELY_HIGH : EXTREMELY_LOW;
        }
    }

    /** Seam mapping: vanilla block to facade kind. */
    static Kind kindOf(net.minecraft.world.level.block.Block block) {
        if (block == net.minecraft.world.level.block.Blocks.REDSTONE_TORCH) {
            return Kind.REDSTONE_TORCH;
        }
        if (block == net.minecraft.world.level.block.Blocks.REPEATER) {
            return Kind.REPEATER;
        }
        return Kind.OTHER;
    }

    /** Seam mapping: vanilla priority to facade priority. */
    static Priority priorityOf(net.minecraft.world.ticks.TickPriority priority) {
        return Priority.byValue(priority.getValue());
    }

    /** Seam mapping: facade priority back to vanilla priority. */
    static net.minecraft.world.ticks.TickPriority vanillaPriority(Priority priority) {
        return net.minecraft.world.ticks.TickPriority.byValue(priority.value());
    }
}
