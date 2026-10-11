package dev.kestrel.loop;

/**
 * Stage 1 facade position: three int coordinates with no vanilla type on
 * the public path. Converts to {@code BlockPos} exactly once at the seam
 * (inside {@code KestrelLevel}), never in caller code.
 *
 * <p>Thread-safety: immutable. Single tick-thread use like the rest of the
 * loop package.
 */
public record KestrelPos(int x, int y, int z) {

    /** Packed long key, same bit layout as {@code BlockPos.asLong}. */
    public long asLong() {
        return net.minecraft.core.BlockPos.asLong(x, y, z);
    }

    /** Seam conversion: facade position to vanilla position. */
    public net.minecraft.core.BlockPos toVanilla() {
        return new net.minecraft.core.BlockPos(x, y, z);
    }

    /** Seam conversion: vanilla position to facade position. */
    static KestrelPos fromVanilla(net.minecraft.core.BlockPos pos) {
        return new KestrelPos(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Neighbor in a vanilla direction (seam helper, not public API). */
    KestrelPos relative(net.minecraft.core.Direction dir) {
        return new KestrelPos(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
    }
}
