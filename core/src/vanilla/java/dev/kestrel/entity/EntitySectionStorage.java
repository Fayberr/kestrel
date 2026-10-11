package dev.kestrel.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.function.LongConsumer;

/**
 * The spatial index: every non-reaped 16x16x16 section keyed by
 * {@link SectionPos#asLong}, plus a sorted key set so an AABB query walks only
 * sections that exist. Mirrors vanilla's {@code EntitySectionStorage}.
 *
 * <p>Sections are bucketed by the entity's feet block, but its box can reach
 * out of that section, so a query widens its section range. Vanilla's
 * envelope ({@code EntitySectionStorage.java:38-43}) is asymmetric: X and Z
 * widen by {@link #SEARCH_GRACE} blocks each way (an entity's half width), Y
 * widens by the full {@link #MAX_ENTITY_SIZE} downward (a box grows up from the
 * feet) and not at all upward. Entities bigger than that can be missed, exactly
 * as in vanilla.
 */
public final class EntitySectionStorage<T extends EntityAccess> {
    /** Vanilla {@code CHONKY_ENTITY_SEARCH_GRACE}, applied in blocks. */
    public static final int SEARCH_GRACE = 2;
    /** Vanilla {@code MAX_NON_CHONKY_ENTITY_SIZE}, in blocks. */
    public static final int MAX_ENTITY_SIZE = 4;

    private final Class<T> entityClass;
    private final Long2ObjectFunction<EntityVisibility> initialVisibility;
    private final Long2ObjectMap<EntitySection<T>> sections = new Long2ObjectOpenHashMap<>();
    private final LongSortedSet sectionIds = new LongAVLTreeSet();

    EntitySectionStorage(Class<T> entityClass, Long2ObjectFunction<EntityVisibility> initialVisibility) {
        this.entityClass = entityClass;
        this.initialVisibility = initialVisibility;
    }

    /** Section X, Y and Z ranges an AABB query visits: {minX, minY, minZ, maxX, maxY, maxZ}. */
    public static int[] searchEnvelope(AABB bb) {
        return new int[] {
            SectionPos.posToSectionCoord(bb.minX - SEARCH_GRACE),
            SectionPos.posToSectionCoord(bb.minY - MAX_ENTITY_SIZE),
            SectionPos.posToSectionCoord(bb.minZ - SEARCH_GRACE),
            SectionPos.posToSectionCoord(bb.maxX + SEARCH_GRACE),
            SectionPos.posToSectionCoord(bb.maxY + 0.0),
            SectionPos.posToSectionCoord(bb.maxZ + SEARCH_GRACE)
        };
    }

    /**
     * Visits, in vanilla's order (X ascending, then section key ascending), every
     * existing non-empty section inside the envelope whose status is accessible.
     */
    public void forEachAccessibleNonEmptySection(AABB bb, AbortableIterationConsumer<EntitySection<T>> out) {
        walk(bb, (key, section) -> out.accept(section));
    }

    /** Same walk as {@link #forEachAccessibleNonEmptySection}, reporting section keys. */
    public void forEachAccessibleNonEmptySectionKey(AABB bb, LongConsumer out) {
        walk(bb, (key, section) -> {
            out.accept(key);
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
    }

    private interface SectionVisitor<T extends EntityAccess> {
        AbortableIterationConsumer.Continuation visit(long key, EntitySection<T> section);
    }

    private void walk(AABB bb, SectionVisitor<T> out) {
        int[] e = searchEnvelope(bb);
        for (int x = e[0]; x <= e[3]; x++) {
            long lowest = SectionPos.asLong(x, 0, 0);
            long highest = SectionPos.asLong(x, -1, -1);
            LongIterator it = sectionIds.subSet(lowest, highest + 1L).iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                int y = SectionPos.y(key);
                int z = SectionPos.z(key);
                if (y >= e[1] && y <= e[4] && z >= e[2] && z <= e[5]) {
                    EntitySection<T> section = sections.get(key);
                    if (section != null && !section.isEmpty() && section.getStatus().isAccessible()
                            && out.visit(key, section).shouldAbort()) {
                        return;
                    }
                }
            }
        }
    }

    public void getEntities(AABB bb, AbortableIterationConsumer<T> out) {
        forEachAccessibleNonEmptySection(bb, section -> section.getEntities(bb, out));
    }

    /** Existing section keys in one chunk column, ascending. */
    LongSortedSet getChunkSections(long chunkKey) {
        int x = ChunkPos.getX(chunkKey);
        int z = ChunkPos.getZ(chunkKey);
        return sectionIds.subSet(SectionPos.asLong(x, 0, z), SectionPos.asLong(x, -1, z) + 1L);
    }

    EntitySection<T> getOrCreateSection(long key) {
        return sections.computeIfAbsent(key, this::createSection);
    }

    public EntitySection<T> getSection(long key) {
        return sections.get(key);
    }

    private EntitySection<T> createSection(long sectionKey) {
        long chunkKey = ChunkPos.pack(SectionPos.x(sectionKey), SectionPos.z(sectionKey));
        sectionIds.add(sectionKey);
        return new EntitySection<>(entityClass, initialVisibility.get(chunkKey));
    }

    void remove(long sectionKey) {
        sections.remove(sectionKey);
        sectionIds.remove(sectionKey);
    }

    /** Number of live sections, from the sorted key set. */
    public int count() {
        return sectionIds.size();
    }

    /** Number of live sections, from the section map (must equal {@link #count()}). */
    public int mapCount() {
        return sections.size();
    }
}
