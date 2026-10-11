package dev.kestrel.entity;

import net.minecraft.server.level.FullChunkStatus;

/**
 * Per-chunk entity status, mirroring vanilla's {@code Visibility}: accessible
 * entities are indexed for lookups and AABB queries, ticking ones also tick.
 */
public enum EntityVisibility {
    HIDDEN(false, false),
    TRACKED(true, false),
    TICKING(true, true);

    private final boolean accessible;
    private final boolean ticking;

    EntityVisibility(boolean accessible, boolean ticking) {
        this.accessible = accessible;
        this.ticking = ticking;
    }

    public boolean isAccessible() {
        return accessible;
    }

    public boolean isTicking() {
        return ticking;
    }

    /**
     * Only ENTITY_TICKING ticks entities; FULL and BLOCK_TICKING keep them
     * tracked but frozen; INACCESSIBLE hides them.
     */
    public static EntityVisibility fromFullChunkStatus(FullChunkStatus status) {
        return switch (status) {
            case ENTITY_TICKING -> TICKING;
            case BLOCK_TICKING, FULL -> TRACKED;
            case INACCESSIBLE -> HIDDEN;
        };
    }
}
