package dev.kestrel.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A level's in-memory entity storage: the UUID registry of every entity in the
 * level, the id/UUID lookup of the accessible ones, and the spatial section
 * index. Mirrors the in-memory half of vanilla's
 * {@code PersistentEntitySectionManager}: add, section crossing on move,
 * removal, and chunk status propagation between accessible and hidden.
 *
 * <p>Not here yet (later S14 slices): ticking start/stop (there is no entity
 * tick list), level callbacks for tracking and destruction, disk loads and
 * unloads.
 */
public final class EntitySectionManager<T extends EntityAccess> {
    private final Set<UUID> knownUuids = new HashSet<>();
    private final EntityLookup<T> visibleEntities = new EntityLookup<>();
    private final Long2ObjectMap<EntityVisibility> chunkVisibility = new Long2ObjectOpenHashMap<>();
    private final EntitySectionStorage<T> sections;

    public EntitySectionManager(Class<T> entityClass) {
        chunkVisibility.defaultReturnValue(EntityVisibility.HIDDEN);
        sections = new EntitySectionStorage<>(entityClass, chunkVisibility);
    }

    /**
     * Adds a new entity. Returns false, touching nothing, when its UUID is
     * already known to the level (vanilla's duplicate-UUID rejection).
     */
    public boolean addNewEntity(T entity) {
        if (!knownUuids.add(entity.getUUID())) {
            return false;
        }
        long sectionKey = SectionPos.asLong(entity.blockPosition());
        EntitySection<T> section = sections.getOrCreateSection(sectionKey);
        section.add(entity);
        entity.setLevelCallback(new Callback(entity, sectionKey, section));
        if (effectiveStatus(entity, section.getStatus()).isAccessible()) {
            visibleEntities.add(entity);
        }
        return true;
    }

    private static EntityVisibility effectiveStatus(EntityAccess entity, EntityVisibility status) {
        return entity.isAlwaysTicking() ? EntityVisibility.TICKING : status;
    }

    public void updateChunkStatus(long chunkKey, FullChunkStatus status) {
        updateChunkStatus(chunkKey, EntityVisibility.fromFullChunkStatus(status));
    }

    /** Sets a chunk's status and moves its entities into or out of the lookup. */
    public void updateChunkStatus(long chunkKey, EntityVisibility status) {
        if (status == EntityVisibility.HIDDEN) {
            chunkVisibility.remove(chunkKey);
        } else {
            chunkVisibility.put(chunkKey, status);
        }
        LongIterator it = sections.getChunkSections(chunkKey).iterator();
        while (it.hasNext()) {
            EntitySection<T> section = sections.getSection(it.nextLong());
            if (section == null) {
                continue;
            }
            EntityVisibility prev = section.updateChunkStatus(status);
            boolean was = prev.isAccessible();
            boolean is = status.isAccessible();
            if (was == is) {
                continue;
            }
            for (T e : section.entities()) {
                if (e.isAlwaysTicking()) {
                    continue;
                }
                if (is) {
                    visibleEntities.add(e);
                } else {
                    visibleEntities.remove(e);
                }
            }
        }
    }

    public EntityVisibility chunkStatus(long chunkKey) {
        return chunkVisibility.get(chunkKey);
    }

    /** Accessible entity by network id, or null. */
    public T getEntity(int id) {
        return visibleEntities.getEntity(id);
    }

    /** Accessible entity by UUID, or null. */
    public T getEntity(UUID uuid) {
        return visibleEntities.getEntity(uuid);
    }

    /** True while any entity with this UUID is in the level, accessible or not. */
    public boolean isLoaded(UUID uuid) {
        return knownUuids.contains(uuid);
    }

    /** Entities in accessible sections whose box intersects {@code bb}, in vanilla's order. */
    public void getEntities(AABB bb, Consumer<T> out) {
        sections.getEntities(bb, AbortableIterationConsumer.forConsumer(out));
    }

    public EntitySectionStorage<T> sections() {
        return sections;
    }

    public EntityLookup<T> lookup() {
        return visibleEntities;
    }

    public int knownCount() {
        return knownUuids.size();
    }

    private void removeSectionIfEmpty(long sectionKey, EntitySection<T> section) {
        if (section.isEmpty()) {
            sections.remove(sectionKey);
        }
    }

    private final class Callback implements EntityInLevelCallback {
        private final T entity;
        private long currentSectionKey;
        private EntitySection<T> currentSection;

        Callback(T entity, long sectionKey, EntitySection<T> section) {
            this.entity = entity;
            this.currentSectionKey = sectionKey;
            this.currentSection = section;
        }

        @Override
        public void onMove() {
            long newKey = SectionPos.asLong(entity.blockPosition());
            if (newKey == currentSectionKey) {
                return;
            }
            EntityVisibility previous = currentSection.getStatus();
            // Vanilla only logs a miss here and carries on; so does Kestrel.
            currentSection.remove(entity);
            removeSectionIfEmpty(currentSectionKey, currentSection);
            EntitySection<T> next = sections.getOrCreateSection(newKey);
            next.add(entity);
            currentSection = next;
            currentSectionKey = newKey;
            boolean was = effectiveStatus(entity, previous).isAccessible();
            boolean is = effectiveStatus(entity, next.getStatus()).isAccessible();
            if (was && !is) {
                visibleEntities.remove(entity);
            } else if (!was && is) {
                visibleEntities.add(entity);
            }
        }

        @Override
        public void onRemove(Entity.RemovalReason reason) {
            currentSection.remove(entity);
            if (effectiveStatus(entity, currentSection.getStatus()).isAccessible()) {
                visibleEntities.remove(entity);
            }
            knownUuids.remove(entity.getUUID());
            entity.setLevelCallback(EntityInLevelCallback.NULL);
            removeSectionIfEmpty(currentSectionKey, currentSection);
        }
    }
}
