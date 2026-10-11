package dev.kestrel.net;

import dev.kestrel.loop.TickLoop;
import it.unimi.dsi.fastutil.shorts.ShortIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The block packets of {@code ChunkHolder.broadcastChanges}
 * ({@code ChunkHolder.java:192-217}) for every chunk that changed since the
 * last broadcast: per changed section, by ascending section index, a
 * {@code ClientboundBlockUpdatePacket} when one cell changed and a
 * {@code ClientboundSectionBlocksUpdatePacket} in the section set's order
 * otherwise, each carrying the cells' current states. Block entity data
 * packets ({@code broadcastBlockEntityIfNeeded}) are not built: no block
 * entity Kestrel keeps sends one.
 *
 * <p>Vanilla walks the changed chunks in a {@code ReferenceOpenHashSet}'s
 * identity-hash order, which differs between runs; here they come in the
 * order they first changed.
 *
 * <p>Thread-safety: tick thread only.
 */
public final class BlockChangePackets {
    private BlockChangePackets() {
    }

    /** Drain the loop's changed cells into each chunk's packets ({@code ChunkPos.pack} keys). */
    public static Map<Long, List<Outbound.Packet>> drain(TickLoop loop) {
        Map<Long, List<Outbound.Packet>> out = new LinkedHashMap<>();
        loop.changedBlocks().drain((chunkKey, sectionY, changed) -> {
            List<Outbound.Packet> packets = out.computeIfAbsent(chunkKey, k -> new ArrayList<>());
            SectionPos section = SectionPos.of(ChunkPos.getX(chunkKey), sectionY, ChunkPos.getZ(chunkKey));
            if (changed.size() == 1) {
                BlockPos pos = section.relativeToBlockPos(changed.iterator().nextShort());
                packets.add(Outbound.blockUpdate(pos.asLong(), Block.getId(loop.getBlockState(pos))));
                return;
            }
            short[] positions = new short[changed.size()];
            int[] states = new int[positions.length];
            int i = 0;
            for (ShortIterator it = changed.iterator(); it.hasNext(); i++) {
                short relative = it.nextShort();
                positions[i] = relative;
                states[i] = Block.getId(loop.getBlockState(section.relativeToBlockPos(relative)));
            }
            packets.add(Outbound.sectionBlocksUpdate(section.asLong(), positions, states));
        });
        return out;
    }
}
