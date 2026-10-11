package dev.kestrel.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

/**
 * Flat per-section wire layout: a dense section-local (16x16x16) store for
 * the per-wire data touched during propagation (power byte + connection
 * nibble per wire cell), populated once per propagation from real vanilla
 * blockstates and written back through real {@code setBlock} calls.
 *
 * <p>Why this exists: during propagation the per-wire data (power, 4
 * connection sides, neighbor refs) is pointer-chased across
 * {@code Level.getBlockState} (chunk lookup + section + palette decode) per
 * read (see {@code preresearch/redstone-path-analysis.md} target 4). The
 * layout replaces the repeated lookups with flat array indexing for the
 * changing case, without changing traversal order or the code path: the
 * integration layer still walks wires in vanilla's order and still commits
 * every power change through a real blockstate swap.
 *
 * <p>Parity constraints honored:
 * <ul>
 *   <li>POWER is a blockstate (0-15): power can never be "updated in place".
 *       This layout stores power BYTES as a propagation scratch copy only;
 *       commit goes through real {@code setBlock} (or a proof that the
 *       bypass is tick-identical, which is the harness's job).</li>
 *   <li>Connection sides here are a COPY of what
 *       {@link WireConnectionCache} holds (which itself only stores
 *       vanilla-resolved values). The canonical connection truth stays in
 *       the blockstates + the cache; this layout is scratch, rebuilt per
 *       propagation, never read back as authority.</li>
 *   <li>Section-local indexing (x/y/z masked to 0-15, packed
 *       {@code (y << 8) | (z << 4) | x}): wires spanning sections use one
 *       layout per section, keyed by section key. Cross-section neighbor
 *       reads fall back to the live world lookup - the layout never
 *       fabricates out-of-section data.</li>
 * </ul>
 *
 * <p>Thread-safety: single tick-thread only. One instance is scratch per
 * propagation (or one per section per propagation); no sharing, no
 * synchronization.
 */
public final class WireSectionLayout {

    /** Cells per section (16x16x16). */
    public static final int CELLS = 4096;

    /** Bit mask for the section-local coordinate (coord & 15). */
    public static final int MASK = 15;

    /** Connection nibble: bit per horizontal side that is connected (N,E,S,W). */
    public static final int SIDE_NORTH = 1;
    public static final int SIDE_EAST = 2;
    public static final int SIDE_SOUTH = 4;
    public static final int SIDE_WEST = 8;

    /** Value marking "no wire here" in the power array. */
    public static final byte NO_WIRE = -1;

    private final long sectionKey;
    private final byte[] power = new byte[CELLS];
    private final byte[] sides = new byte[CELLS];
    private int wireCount = 0;

    public WireSectionLayout(long sectionKey) {
        this.sectionKey = sectionKey;
        java.util.Arrays.fill(power, NO_WIRE);
    }

    /** Section key this layout covers (see {@code SectionPos.asLong}). */
    public long sectionKey() {
        return sectionKey;
    }

    /** Section key for a block position. */
    public static long keyFor(BlockPos pos) {
        return SectionPos.asLong(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** Flat index for a section-local cell (each coord already 0-15). */
    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /** Flat index for a world position known to be in this section. */
    public static int indexFor(BlockPos pos) {
        return index(pos.getX() & MASK, pos.getY() & MASK, pos.getZ() & MASK);
    }

    /** True when the position falls inside this layout's section. */
    public boolean contains(BlockPos pos) {
        return keyFor(pos) == sectionKey;
    }

    /**
     * Record one wire cell. Power is 0-15; sides are the 4
     * {@link RedstoneSide} values in NORTH/EAST/SOUTH/WEST order. Cells for
     * non-wire blocks are never stored (leave them {@link #NO_WIRE}).
     */
    public void put(BlockPos pos, int powerLevel, RedstoneSide[] connectionSides) {
        int i = indexFor(pos);
        if (power[i] == NO_WIRE) {
            wireCount++;
        }
        power[i] = (byte) powerLevel;
        sides[i] = packSides(connectionSides);
    }

    /** Scratch power at a cell, or {@link #NO_WIRE} when no wire recorded. */
    public byte powerAt(int x, int y, int z) {
        return power[index(x, y, z)];
    }

    /** Scratch power at a world position in this section. */
    public byte powerAt(BlockPos pos) {
        return power[indexFor(pos)];
    }

    /** Packed connection nibble at a cell. */
    public byte sidesAt(int x, int y, int z) {
        return sides[index(x, y, z)];
    }

    /** Packed connection nibble at a world position in this section. */
    public byte sidesAt(BlockPos pos) {
        return sides[indexFor(pos)];
    }

    /** True when a wire was recorded at this cell. */
    public boolean hasWire(int x, int y, int z) {
        return power[index(x, y, z)] != NO_WIRE;
    }

    /** True when a wire was recorded at this world position. */
    public boolean hasWire(BlockPos pos) {
        return contains(pos) && power[indexFor(pos)] != NO_WIRE;
    }

    /** Number of wire cells recorded. */
    public int wireCount() {
        return wireCount;
    }

    /** Update the scratch power (propagation working copy, not a commit). */
    public void setScratchPower(BlockPos pos, int powerLevel) {
        power[indexFor(pos)] = (byte) powerLevel;
    }

    /** Pack 4 RedstoneSides (N/E/S/W order) into a connection nibble. */
    public static byte packSides(RedstoneSide[] s) {
        int bits = 0;
        if (s != null) {
            if (s.length > 0 && s[0] != null && s[0].isConnected()) {
                bits |= SIDE_NORTH;
            }
            if (s.length > 1 && s[1] != null && s[1].isConnected()) {
                bits |= SIDE_EAST;
            }
            if (s.length > 2 && s[2] != null && s[2].isConnected()) {
                bits |= SIDE_SOUTH;
            }
            if (s.length > 3 && s[3] != null && s[3].isConnected()) {
                bits |= SIDE_WEST;
            }
        }
        return (byte) bits;
    }

    /** Unpack a connection nibble back to 4 connected flags (N/E/S/W order). */
    public static boolean[] unpackSides(byte nibble) {
        return new boolean[]{
                (nibble & SIDE_NORTH) != 0,
                (nibble & SIDE_EAST) != 0,
                (nibble & SIDE_SOUTH) != 0,
                (nibble & SIDE_WEST) != 0,
        };
    }
}
