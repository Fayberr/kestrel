package dev.kestrel.loop;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 chunk abstraction over 16x16x16 sections: a 16x16 column of
 * block positions spanning the level's full height, addressed by chunk
 * coordinates. Reads and writes route through the owning
 * {@link KestrelLevel}; this view holds no storage of its own, so section
 * materialization and the written bitset stay in exactly one place.
 *
 * <p>Facade rule: Kestrel types only on the public path.
 *
 * <p>Thread-safety: single tick-thread only. No synchronization.
 */
public final class KestrelChunk {

    private final KestrelLevel level;
    private final int chunkX;
    private final int chunkZ;

    KestrelChunk(KestrelLevel level, int chunkX, int chunkZ) {
        this.level = level;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public KestrelLevel.Dimension dimension() {
        return level.dimension();
    }

    /** Read the state at chunk-local (0-15) x/z plus absolute y. */
    public KestrelState get(int localX, int y, int localZ) {
        return level.getBlockState(worldPos(localX, y, localZ));
    }

    /** Write a state at chunk-local (0-15) x/z plus absolute y. */
    public KestrelState set(int localX, int y, int localZ, KestrelState state) {
        return level.setBlock(worldPos(localX, y, localZ), state);
    }

    /** Written positions inside this chunk (snapshot list). */
    public List<KestrelPos> writtenPositions() {
        List<KestrelPos> out = new ArrayList<>();
        for (KestrelPos pos : level.writtenPositions()) {
            if ((pos.x() >> 4) == chunkX && (pos.z() >> 4) == chunkZ) {
                out.add(pos);
            }
        }
        return out;
    }

    private KestrelPos worldPos(int localX, int y, int localZ) {
        return new KestrelPos((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
    }
}
