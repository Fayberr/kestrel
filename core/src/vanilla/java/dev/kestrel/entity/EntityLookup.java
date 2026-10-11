package dev.kestrel.entity;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Level-wide lookup of accessible entities by network id and by UUID, mirroring
 * vanilla's {@code EntityLookup}. The id map is linked so iteration follows
 * insertion order, as vanilla's does.
 */
public final class EntityLookup<T extends EntityAccess> {
    private final Int2ObjectMap<T> byId = new Int2ObjectLinkedOpenHashMap<>();
    private final Map<UUID, T> byUuid = new HashMap<>();

    /** False, and nothing indexed, when the UUID is already present (vanilla logs and skips). */
    boolean add(T entity) {
        UUID uuid = entity.getUUID();
        if (byUuid.containsKey(uuid)) {
            return false;
        }
        byUuid.put(uuid, entity);
        byId.put(entity.getId(), entity);
        return true;
    }

    void remove(T entity) {
        byUuid.remove(entity.getUUID());
        byId.remove(entity.getId());
    }

    public T getEntity(int id) {
        return byId.get(id);
    }

    public T getEntity(UUID uuid) {
        return byUuid.get(uuid);
    }

    public Iterable<T> getAllEntities() {
        return java.util.Collections.unmodifiableCollection(byId.values());
    }

    public int countById() {
        return byId.size();
    }

    public int count() {
        return byUuid.size();
    }
}
