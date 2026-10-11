package dev.kestrel.persist;

import dev.kestrel.loop.SectionStore;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/**
 * Blocks-only per-chunk hash for the G2 step-2 HD gate
 * ({@code g2-build-order.md} step 2 exit 1: Kestrel block hash per
 * imported chunk == P3).
 *
 * <p>Bit-for-bit the block half of the harness probe
 * ({@code harness/agent/.../HashProbe.hashChunk}), which the probe now
 * also emits on its own as the {@code blocks_hash} column. Same FNV-1a
 * byte-wise mixing, same walk: sections bottom-up; a section with no
 * non-air cell (vanilla {@code hasOnlyAir}, which counts
 * {@code !isAir()}) mixes the long {@code 0xA1A1A1A1 ^ sectionY}; other
 * sections mix {@code (wx, y, wz, Block.getId(state))} for every cell
 * with a non-zero id, x outer, z middle, y inner. A section absent from
 * the store reads as all air, the same as an air-only vanilla section.
 *
 * <p>Never use {@code WorldSnapshot.hashLive} for this gate: it mixes
 * the Kestrel written flag, which has no vanilla counterpart and whose
 * meaning for imported cells is due to change
 * ({@code g2-memory-design.md} 2.4, parity risk 2). The probe's
 * combined {@code hash} column also mixes heightmaps, which Kestrel does
 * not keep; compare against {@code blocks_hash}.
 *
 * <p>Thread-safety: reads the store; tick thread (or no live loop).
 */
public final class BlockHash {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private BlockHash() {
    }

    /** Blocks-only hash of one chunk column, as the probe's 16-hex string. */
    public static String chunkHex(SectionStore store, int chunkX, int chunkZ) {
        return String.format(Locale.ROOT, "%016x", chunk(store, chunkX, chunkZ));
    }

    /** Blocks-only hash of one chunk column. */
    public static long chunk(SectionStore store, int chunkX, int chunkZ) {
        int minSectionY = store.getMinY() >> 4;
        int count = store.getHeight() >> 4;
        long acc = FNV_OFFSET;
        for (int si = 0; si < count; si++) {
            int sectionY = minSectionY + si;
            long key = SectionPos.asLong(chunkX, sectionY, chunkZ);
            if (onlyAir(store, key)) {
                acc = mixLong(acc, 0xA1A1A1A1L ^ sectionY);
                continue;
            }
            int baseY = sectionY << 4;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = chunkX * 16 + lx;
                    int wz = chunkZ * 16 + lz;
                    for (int ly = 0; ly < 16; ly++) {
                        BlockState st = store.readCell(key, SectionStore.index(lx, ly, lz));
                        int id = st == null ? 0 : Block.getId(st);
                        if (id == 0) {
                            continue;
                        }
                        acc = mixInt(acc, wx);
                        acc = mixInt(acc, baseY + ly);
                        acc = mixInt(acc, wz);
                        acc = mixInt(acc, id);
                    }
                }
            }
        }
        return acc;
    }

    private static boolean onlyAir(SectionStore store, long key) {
        if (!store.hasSection(key)) {
            return true;
        }
        for (int i = 0; i < SectionStore.CELLS; i++) {
            BlockState st = store.readCell(key, i);
            if (st != null && !st.isAir()) {
                return false;
            }
        }
        return true;
    }

    private static long mixLong(long acc, long val) {
        for (int shift = 0; shift < 64; shift += 8) {
            acc ^= (val >>> shift) & 0xFF;
            acc *= FNV_PRIME;
        }
        return acc;
    }

    private static long mixInt(long acc, int val) {
        for (int shift = 0; shift < 32; shift += 8) {
            acc ^= (val >>> shift) & 0xFF;
            acc *= FNV_PRIME;
        }
        return acc;
    }
}
