package dev.kestrel.persist;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2 single-section palette serializer: 4096 BlockStates to
 * {@code {palette:[{Name,Properties}...], data:[L...]}} and back.
 *
 * <p>Bits rules mirror vanilla's block-state strategy
 * ({@code Strategy.java:33-48}, createForBlockStates): 1 distinct state
 * writes 0 bits (single value, {@code data} omitted); 2-16 states write 4
 * bits; 17-32 write 5; 33-64 write 6; 65-128 write 7; 129-256 write 8;
 * more than 256 write ceiling(log2(n)) global bits. Packing follows
 * {@code SimpleBitStorage}: values-per-long is 64/bits, cell index
 * {@code (y<<4|z)<<4|x}, low indices in the low bits of long 0, no
 * cross-long straddling ({@code SimpleBitStorage.java:211-254},
 * {@code Strategy.getIndex} at {@code Strategy.java:69-71}).
 *
 * <p>The section-local index uses the same {@code (y<<8)|(z<<4)|x}
 * addressing as {@code SectionStore} and {@code WireSectionLayout}, so
 * cell i of the store maps to palette index i directly.
 *
 * <p>Palette order is first-seen over the 4096-cell scan (index 0..4095),
 * which keeps the common single/bi-state sections canonical. Vanilla's
 * own repack ({@code PalettedContainer.pack}) may order differently; the
 * container is semantically identical either way (ids resolve through
 * the palette), so cross-implementation diffs compare decoded states,
 * not raw longs.
 *
 * <p>Thread-safety: stateless. Single tick-thread use like the rest of
 * the package.
 */
public final class SectionCodec {

    /** Cells per section, matching SectionStore.CELLS. */
    public static final int CELLS = 4096;

    private SectionCodec() {
    }

    /** Paletted section payload in SimpleBitStorage layout. */
    public record CompactSection(BlockState[] palette, long[] data, int bits) {
    }

    /** Encode 4096 states (null means air) to the section compound. */
    public static Nbt.Tag.TCompound encode(BlockState[] cells, BlockState air) {
        Map<BlockState, Integer> ids = new LinkedHashMap<>();
        List<Nbt.Tag.TCompound> palette = new ArrayList<>();
        int[] raw = new int[CELLS];
        for (int i = 0; i < CELLS; i++) {
            BlockState st = cells[i] != null ? cells[i] : air;
            Integer id = ids.get(st);
            if (id == null) {
                id = palette.size();
                ids.put(st, id);
                palette.add(StateNbt.encode(st));
            }
            raw[i] = id;
        }
        Nbt.Tag.TCompound out = Nbt.compound();
        List<Nbt.Tag> entries = new ArrayList<>(palette);
        Nbt.put(out, "palette", new Nbt.Tag.TList(Nbt.TAG_COMPOUND, entries));
        int bits = bitsFor(palette.size());
        if (bits > 0) {
            Nbt.putLongArray(out, "data", pack(raw, bits));
        }
        return out;
    }

    /** Emit an already-packed compact section without scanning or repacking its cells. */
    public static Nbt.Tag.TCompound encodeCompact(BlockState[] palette, long[] data, int bits) {
        if (palette.length == 0 || bits != bitsFor(palette.length)) {
            throw new IllegalArgumentException("invalid compact palette/bits");
        }
        List<Nbt.Tag> entries = new ArrayList<>(palette.length);
        for (BlockState state : palette) {
            entries.add(StateNbt.encode(state));
        }
        Nbt.Tag.TCompound out = Nbt.compound();
        Nbt.put(out, "palette", new Nbt.Tag.TList(Nbt.TAG_COMPOUND, entries));
        if (bits > 0) {
            Nbt.putLongArray(out, "data", data);
        }
        return out;
    }

    /** Bits on disc for a palette of the given size (vanilla strategy). */
    static int bitsFor(int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }
        if (paletteSize <= 16) {
            return 4;
        }
        if (paletteSize <= 32) {
            return 5;
        }
        if (paletteSize <= 64) {
            return 6;
        }
        if (paletteSize <= 128) {
            return 7;
        }
        if (paletteSize <= 256) {
            return 8;
        }
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    /** Pack 4096 indices into longs, SimpleBitStorage order. */
    static long[] pack(int[] raw, int bits) {
        int perLong = 64 / bits;
        int longs = (CELLS + perLong - 1) / perLong;
        long[] data = new long[longs];
        long mask = bits == 64 ? -1L : (1L << bits) - 1;
        for (int i = 0; i < CELLS; i++) {
            int li = i / perLong;
            int bit = (i % perLong) * bits;
            data[li] |= (raw[i] & mask) << bit;
        }
        return data;
    }

    /** Decode the section compound back to 4096 states (never null). */
    public static BlockState[] decode(Nbt.Tag.TCompound tag, BlockState air) throws Nbt.NbtException {
        Nbt.Tag.TList paletteTag = Nbt.getList(tag, "palette");
        List<BlockState> palette = new ArrayList<>(paletteTag.items().size());
        for (Nbt.Tag t : paletteTag.items()) {
            if (!(t instanceof Nbt.Tag.TCompound c)) {
                throw new Nbt.NbtException("palette entry is not a compound");
            }
            palette.add(StateNbt.decode(c));
        }
        if (palette.isEmpty()) {
            throw new Nbt.NbtException("empty palette");
        }
        BlockState[] cells = new BlockState[CELLS];
        int bits = bitsFor(palette.size());
        if (bits == 0) {
            BlockState only = palette.get(0);
            for (int i = 0; i < CELLS; i++) {
                cells[i] = only;
            }
            return cells;
        }
        Nbt.Tag dataTag = Nbt.getOrNull(tag, "data");
        if (!(dataTag instanceof Nbt.Tag.TLongArray la)) {
            throw new Nbt.NbtException("missing data for multi-state palette");
        }
        int perLong = 64 / bits;
        long mask = bits == 64 ? -1L : (1L << bits) - 1;
        long[] data = la.value();
        for (int i = 0; i < CELLS; i++) {
            int li = i / perLong;
            int bit = (i % perLong) * bits;
            if (li >= data.length) {
                throw new Nbt.NbtException("truncated data array");
            }
            int id = (int) ((data[li] >>> bit) & mask);
            if (id < 0 || id >= palette.size()) {
                throw new Nbt.NbtException("palette id out of range: " + id);
            }
            cells[i] = palette.get(id);
        }
        return cells;
    }

    /**
     * Decode only the palette and packed storage, validating every cell id
     * without allocating a 4096-reference dense array.
     */
    public static CompactSection decodeCompact(Nbt.Tag.TCompound tag) throws Nbt.NbtException {
        Nbt.Tag.TList paletteTag = Nbt.getList(tag, "palette");
        BlockState[] palette = new BlockState[paletteTag.items().size()];
        for (int i = 0; i < palette.length; i++) {
            Nbt.Tag entry = paletteTag.items().get(i);
            if (!(entry instanceof Nbt.Tag.TCompound compound)) {
                throw new Nbt.NbtException("palette entry is not a compound");
            }
            palette[i] = StateNbt.decode(compound);
        }
        if (palette.length == 0) {
            throw new Nbt.NbtException("empty palette");
        }
        int bits = bitsFor(palette.length);
        if (bits == 0) {
            return new CompactSection(palette, null, 0);
        }
        Nbt.Tag dataTag = Nbt.getOrNull(tag, "data");
        if (!(dataTag instanceof Nbt.Tag.TLongArray packed)) {
            throw new Nbt.NbtException("missing data for multi-state palette");
        }
        long[] data = packed.value();
        int perLong = 64 / bits;
        int required = (CELLS + perLong - 1) / perLong;
        if (data.length < required) {
            throw new Nbt.NbtException("truncated data array");
        }
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < CELLS; i++) {
            int id = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            if (id >= palette.length) {
                throw new Nbt.NbtException("palette id out of range: " + id);
            }
        }
        return new CompactSection(palette, data, bits);
    }

    /** Read one cell from a validated compact section. */
    public static BlockState compactCell(CompactSection section, int index) {
        return compactCell(section.palette(), section.data(), section.bits(), index);
    }

    /** Read one cell from compact arrays without allocating a payload wrapper. */
    public static BlockState compactCell(BlockState[] palette, long[] data, int bits, int index) {
        if (bits == 0) {
            return palette[0];
        }
        int perLong = 64 / bits;
        int id = (int) ((data[index / perLong]
                >>> ((index % perLong) * bits)) & ((1L << bits) - 1L));
        return palette[id];
    }
}
