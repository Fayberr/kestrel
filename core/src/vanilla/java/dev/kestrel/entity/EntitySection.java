package dev.kestrel.entity;

import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.phys.AABB;

/**
 * One 16x16x16 bucket of entities plus the status of the chunk it sits in,
 * mirroring vanilla's {@code EntitySection}. Storage is vanilla's own
 * {@link ClassInstanceMultiMap}, so iteration order (insertion order) and the
 * per-class partitions later slices query by type are vanilla's.
 */
public final class EntitySection<T extends EntityAccess> {
    private final ClassInstanceMultiMap<T> storage;
    private EntityVisibility chunkStatus;

    EntitySection(Class<T> entityClass, EntityVisibility chunkStatus) {
        this.storage = new ClassInstanceMultiMap<>(entityClass);
        this.chunkStatus = chunkStatus;
    }

    void add(T entity) {
        storage.add(entity);
    }

    boolean remove(T entity) {
        return storage.remove(entity);
    }

    public boolean contains(T entity) {
        return storage.contains(entity);
    }

    AbortableIterationConsumer.Continuation getEntities(AABB bb, AbortableIterationConsumer<T> out) {
        for (T entity : storage) {
            if (entity.getBoundingBox().intersects(bb) && out.accept(entity).shouldAbort()) {
                return AbortableIterationConsumer.Continuation.ABORT;
            }
        }
        return AbortableIterationConsumer.Continuation.CONTINUE;
    }

    Iterable<T> entities() {
        return storage;
    }

    public boolean isEmpty() {
        return storage.isEmpty();
    }

    public int size() {
        return storage.size();
    }

    public EntityVisibility getStatus() {
        return chunkStatus;
    }

    /** Sets the status and returns the previous one. */
    EntityVisibility updateChunkStatus(EntityVisibility status) {
        EntityVisibility prev = chunkStatus;
        chunkStatus = status;
        return prev;
    }
}
