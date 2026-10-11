package dev.kestrel.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * What the entity storage layer needs from an entity. Mirrors the part of
 * vanilla's {@code net.minecraft.world.level.entity.EntityAccess} that slice 1
 * uses: identity, the block position that picks the section, the bounding box
 * that AABB queries test, the always-ticking override, and the callback seam.
 * Passengers, removal state and save flags arrive with the slices that use them.
 */
public interface EntityAccess {
    int getId();

    UUID getUUID();

    /** Feet block position; {@code SectionPos.asLong} of it is the section key. */
    BlockPos blockPosition();

    AABB getBoundingBox();

    /** Vanilla forces the effective status to TICKING for these (players). */
    boolean isAlwaysTicking();

    void setLevelCallback(EntityInLevelCallback callback);
}
