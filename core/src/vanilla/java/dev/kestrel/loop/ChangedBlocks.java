package dev.kestrel.loop;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/**
 * The block half of {@code ChunkHolder}'s broadcast state for every chunk:
 * {@code changedBlocksPerSection}, filled by {@code ChunkHolder.blockChanged}
 * ({@code ChunkHolder.java:125-142}) and emptied by {@code broadcastChanges}
 * ({@code :176-220}). Each section keeps vanilla's own
 * {@code ShortOpenHashSet} of {@code SectionPos.sectionRelativePos} values,
 * filled in the same order, so its iteration order (the order of a
 * {@code ClientboundSectionBlocksUpdatePacket}) is vanilla's too. Bounded by
 * the distinct cells changed between two broadcasts.
 *
 * <p>Thread-safety: tick thread only.
 */
public final class ChangedBlocks {

    /** One chunk's sections, visited by {@link #drain}. */
    public interface Visitor {
        /** A section with changes: {@code changed} holds section-relative positions, in set order. */
        void section(long chunkKey, int sectionY, ShortSet changed);
    }

    private final int minSection;
    private final int sectionCount;
    /** Chunk key to per-section sets, in the order chunks first changed. */
    private final Long2ObjectLinkedOpenHashMap<ShortSet[]> chunks = new Long2ObjectLinkedOpenHashMap<>();

    ChangedBlocks(int minY, int height) {
        this.minSection = SectionPos.blockToSectionCoord(minY);
        this.sectionCount = height >> 4;
    }

    /** {@code ChunkHolder.blockChanged}. */
    void blockChanged(BlockPos pos) {
        int index = SectionPos.blockToSectionCoord(pos.getY()) - minSection;
        if (index < 0 || index >= sectionCount) {
            return;
        }
        long key = ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        ShortSet[] sections = chunks.get(key);
        if (sections == null) {
            sections = new ShortSet[sectionCount];
            chunks.put(key, sections);
        }
        if (sections[index] == null) {
            sections[index] = new ShortOpenHashSet();
        }
        sections[index].add(SectionPos.sectionRelativePos(pos));
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    /**
     * {@code broadcastChanges}' walk: every chunk in first-change order, its
     * changed sections by ascending index, then forget them all.
     */
    public void drain(Visitor visitor) {
        ObjectIterator<Long2ObjectLinkedOpenHashMap.Entry<ShortSet[]>> iterator =
                chunks.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2ObjectLinkedOpenHashMap.Entry<ShortSet[]> entry = iterator.next();
            ShortSet[] sections = entry.getValue();
            for (int index = 0; index < sections.length; index++) {
                if (sections[index] != null) {
                    visitor.section(entry.getLongKey(), minSection + index, sections[index]);
                }
            }
        }
        chunks.clear();
    }
}
