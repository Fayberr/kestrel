package dev.kestrel.entity;

import net.minecraft.world.entity.Entity;

/**
 * The hook an entity calls when it moves or is removed, mirroring vanilla's
 * {@code EntityInLevelCallback}. The manager installs one per entity on add and
 * resets it to {@link #NULL} on removal.
 */
public interface EntityInLevelCallback {
    EntityInLevelCallback NULL = new EntityInLevelCallback() {
        @Override
        public void onMove() {
        }

        @Override
        public void onRemove(Entity.RemovalReason reason) {
        }
    };

    /** Called after the entity's position changed; re-buckets it if its section changed. */
    void onMove();

    /** Called once when the entity leaves the level; forgets it in every index. */
    void onRemove(Entity.RemovalReason reason);
}
