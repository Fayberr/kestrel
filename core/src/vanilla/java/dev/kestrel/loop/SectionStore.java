package dev.kestrel.loop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writable section storage with dense hot sections and immutable compact
 * palettes for cold imported sections behind one section-keyed map.
 *
 * <p>Grounded in vanilla's layout ({@code LevelChunkSection}, 16x16x16 cells,
 * section key via {@code SectionPos.asLong}): one {@code BlockState[4096]}
 * array per live section plus a bitset of which cells were explicitly
 * written. Unwritten cells read as air (matches {@code KestrelBlockGetter}'s
 * existing contract that unset positions read as air), and sections materialize
 * on first write. No persistence, no networking, no dimensions (Phase B/C
 * non-goals): one volume, in memory.
 *
 * <p><b>Mutation provenance (Critical Finding C1).</b> Every cell carries
 * an owner. Cells written through {@link #set} belong to the live layer -
 * a player edit, a redstone update, a scheduled tick - and world
 * generation may never write them again. Cells produced by generation
 * carry no claim, so a later generation pass may replace them with its
 * current output, air included. Generation therefore writes through
 * {@link #putGeneratedCells} / {@link #writeGenerated} rather than
 * {@link #set}, provenance is per cell rather than per section, it
 * survives save and reload, and it is independent of whether a section
 * rests in compact or dense form: storage representation is not
 * ownership. Adopted foreign storage (a vanilla world import) is sealed,
 * meaning every cell is claimed at once.
 *
 * <p>Indexing matches {@link dev.kestrel.redstone.WireSectionLayout#index}
 * ({@code (y << 8) | (z << 4) | x}, each coord masked to 0-15) so propagation
 * scratch and storage agree on cell addressing.
 *
 * <p>Thread-safety: single tick-thread only. No synchronization.
 */
public final class SectionStore {

    /** Immutable compact payload safe to share with an async snapshot. */
    public record CompactData(BlockState[] palette, long[] data, int bits) {
    }

    /** Cells per section (16x16x16). */
    public static final int CELLS = 4096;

    /** Section key for a block position (see {@code SectionPos.asLong}). */
    public static long keyFor(BlockPos pos) {
        return SectionPos.asLong(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** Flat index for section-local coords (each already 0-15). */
    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /** Flat index for a world position inside its section. */
    public static int indexFor(BlockPos pos) {
        return index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    private static final class Section {
        BlockState[] cells;
        boolean[] written;
        BlockState[] palette;
        long[] data;
        byte bits;
        int writtenCount;
        /**
         * Mutation provenance (C1): cells claimed by the live layer, i.e.
         * written through {@link SectionStore#set}. Lazily allocated, null
         * until the first live write, so purely generated sections keep
         * their old footprint. Generation must never write a claimed cell.
         */
        boolean[] edited;
        int editedCount;
        /**
         * Whole-section claim: every cell counts as edited without a
         * bitset. Used for storage adopted from a foreign world (a vanilla
         * import), where the whole volume is somebody's build and none of
         * it is this generator's output to refresh.
         */
        boolean sealed;
        /**
         * {@code LevelChunkSection.tickingBlockCount} and
         * {@code tickingFluidCount}: cells whose block state, and whose
         * fluid state, randomly ticks. Kept per write like
         * {@code LevelChunkSection.setBlockState}; -1 when a bulk write
         * (compact adoption, a generated merge) left them unknown, and then
         * recounted on first query like {@code recalcBlockCounts}.
         */
        int tickingBlocks;
        int tickingFluids;

        Section() {
            cells = new BlockState[CELLS];
            written = new boolean[CELLS];
        }

        Section(BlockState[] palette, long[] data, int bits, int nonAir) {
            this.palette = palette;
            this.data = data;
            this.bits = (byte) bits;
            this.writtenCount = nonAir;
            this.tickingBlocks = -1;
            this.tickingFluids = -1;
        }

        boolean compact() {
            return palette != null;
        }
    }

    private final Map<Long, Section> sections = new HashMap<>();
    private final BlockState air = Blocks.AIR.defaultBlockState();
    private final int minY;
    private final int height;
    private KestrelLevel owningLevel;

    /**
     * G2 live-wiring chunk cache: packed keys ({@code
     * net.minecraft.world.level.ChunkPos.pack}, opaque here) of chunks that
     * have completed their own headless generation pass (own FEATURES +
     * settleLeaves, not merely received a neighbour's radius-1 writes).
     * Backs {@code dev.kestrel.worldgen.LiveGenerator} so a chunk already
     * generated is never regenerated when the chunk sender asks for it
     * again; import/load never touch this set, so persisted worlds are
     * unaffected (see preresearch/g2-livewiring-design.md).
     */
    private final Set<Long> generatedChunks = new HashSet<>();

    /** True once {@code chunkKey} finished its own generation pass. */
    public boolean isChunkGenerated(long chunkKey) {
        return generatedChunks.contains(chunkKey);
    }

    /** Record that {@code chunkKey} finished its own generation pass. */
    public void markChunkGenerated(long chunkKey) {
        generatedChunks.add(chunkKey);
    }

    /** Completed chunk keys for persistence; returned as an isolated copy. */
    public Set<Long> generatedChunkKeys() {
        return new HashSet<>(generatedChunks);
    }

    public SectionStore(int minY, int height) {
        this.minY = minY;
        this.height = height;
    }

    /** Back-reference to the owning level (set once by the level constructor). */
    void bindLevel(KestrelLevel level) {
        this.owningLevel = level;
    }

    /** Owning level, or null when the store stands alone (legacy path). */
    KestrelLevel owningLevel() {
        return owningLevel;
    }

    /** Read the state at a position; unset cells read as air. */
    public BlockState get(BlockPos pos) {
        Section s = sections.get(keyFor(pos));
        if (s == null) {
            return air;
        }
        BlockState st = stateAt(s, indexFor(pos));
        return st != null ? st : air;
    }

    /**
     * Write a state, returning the previous state (air when unset).
     * Materializes the section on first write.
     */
    public BlockState set(BlockPos pos, BlockState state) {
        long key = keyFor(pos);
        Section s = sections.computeIfAbsent(key, k -> new Section());
        if (s.compact()) {
            s = promote(key, s);
        }
        int i = indexFor(pos);
        BlockState prev = s.cells[i] != null ? s.cells[i] : air;
        if (!s.written[i]) {
            s.written[i] = true;
            s.writtenCount++;
        }
        s.cells[i] = state;
        countTicking(s, prev, state);
        claim(s, i);
        return prev;
    }

    /**
     * The counter half of {@code LevelChunkSection.setBlockState}
     * ({@code LevelChunkSection.java:62-101}): the previous state's ticking
     * block and ticking fluid leave the counts, the new state's join them.
     * Air neither ticks nor holds a fluid, so vanilla's {@code isAir} guards
     * change nothing here. A section whose counts are unknown stays unknown.
     */
    private static void countTicking(Section s, BlockState prev, BlockState next) {
        if (s.tickingBlocks < 0) {
            return;
        }
        if (prev.isRandomlyTicking()) {
            s.tickingBlocks--;
        }
        if (prev.getFluidState().isRandomlyTicking()) {
            s.tickingFluids--;
        }
        if (next.isRandomlyTicking()) {
            s.tickingBlocks++;
        }
        if (next.getFluidState().isRandomlyTicking()) {
            s.tickingFluids++;
        }
    }

    /** {@code LevelChunkSection.recalcBlockCounts}, the ticking half. */
    private static void recountTicking(Section s) {
        int blocks = 0;
        int fluids = 0;
        for (int i = 0; i < CELLS; i++) {
            BlockState state = stateAt(s, i);
            if (state == null) {
                continue;
            }
            if (state.isRandomlyTicking()) {
                blocks++;
            }
            if (state.getFluidState().isRandomlyTicking()) {
                fluids++;
            }
        }
        s.tickingBlocks = blocks;
        s.tickingFluids = fluids;
    }

    private Section countedSection(long sectionKey) {
        Section s = sections.get(sectionKey);
        if (s != null && s.tickingBlocks < 0) {
            recountTicking(s);
        }
        return s;
    }

    /**
     * {@code LevelChunkSection.isRandomlyTicking}: some cell's block state or
     * fluid state randomly ticks. An absent section is all air.
     */
    public boolean isRandomlyTicking(long sectionKey) {
        Section s = countedSection(sectionKey);
        return s != null && (s.tickingBlocks > 0 || s.tickingFluids > 0);
    }

    /** {@code LevelChunkSection.tickingBlockCount}, for gates. */
    public int tickingBlockCount(long sectionKey) {
        Section s = countedSection(sectionKey);
        return s == null ? 0 : s.tickingBlocks;
    }

    /** {@code LevelChunkSection.tickingFluidCount}, for gates. */
    public int tickingFluidCount(long sectionKey) {
        Section s = countedSection(sectionKey);
        return s == null ? 0 : s.tickingFluids;
    }

    /** Mark one cell of a dense section as live-layer owned (C1 provenance). */
    private static void claim(Section s, int index) {
        setClaim(s, index, true);
    }

    /**
     * Set or clear one cell's live-layer claim. A sealed section claims
     * every cell at once and has no per-cell bitset to clear, so releasing
     * a single cell of one is not expressible and is ignored.
     */
    private static void setClaim(Section s, int index, boolean claimed) {
        if (s.sealed) {
            return;
        }
        if (s.edited == null) {
            if (!claimed) {
                return;
            }
            s.edited = new boolean[CELLS];
        }
        if (claimed && !s.edited[index]) {
            s.edited[index] = true;
            s.editedCount++;
        } else if (!claimed && s.edited[index]) {
            s.edited[index] = false;
            s.editedCount--;
        }
    }

    /** True when the live layer owns this cell and generation must not write it. */
    private static boolean editedAt(Section s, int index) {
        return s.sealed || (s.edited != null && s.edited[index]);
    }

    /** True when any cell of the section is live-layer owned. */
    private static boolean hasEdits(Section s) {
        return s.sealed || s.editedCount > 0;
    }

    /** True when the position was explicitly written. */
    public boolean isWritten(BlockPos pos) {
        Section s = sections.get(keyFor(pos));
        return s != null && writtenAt(s, indexFor(pos));
    }

    /**
     * C1 provenance query: true when this cell was written by the live
     * layer (a player edit, a redstone update, a scheduled tick) rather
     * than produced by world generation. Generation writes skip these.
     */
    public boolean isCellEdited(long sectionKey, int cellIndex) {
        Section s = sections.get(sectionKey);
        return s != null && editedAt(s, cellIndex);
    }

    /** Number of live-layer owned cells across all sections (diagnostic). */
    public int editedCells() {
        int n = 0;
        for (Section s : sections.values()) {
            n += s.sealed ? CELLS : s.editedCount;
        }
        return n;
    }

    /**
     * Persistence seam: a copy of the section's edited bitset, or null when
     * no cell is claimed. Sealed sections report an all-true bitset, so the
     * on-disk format needs only the one representation.
     */
    public boolean[] editedBitset(long sectionKey) {
        Section s = sections.get(sectionKey);
        if (s == null || !hasEdits(s)) {
            return null;
        }
        boolean[] out = new boolean[CELLS];
        if (s.sealed) {
            java.util.Arrays.fill(out, true);
            return out;
        }
        System.arraycopy(s.edited, 0, out, 0, CELLS);
        return out;
    }

    /**
     * Stage 2 seam: read one cell of a live section, or null when the
     * section is absent or the cell was never set. The persistence writer
     * walks this instead of materializing sections.
     */
    public BlockState readCell(long sectionKey, int cellIndex) {
        Section s = sections.get(sectionKey);
        return s == null ? null : stateAt(s, cellIndex);
    }

    /**
     * Stage 2 seam: read a dense section's written bitset. Returns null for
     * absent and compact sections; use {@link #hasSection(long)} to
     * distinguish them and {@link #isCellWritten(long, int)} for a
     * representation-independent query. Callers must never mutate it.
     */
    public boolean[] writtenBitset(long sectionKey) {
        Section s = sections.get(sectionKey);
        return s == null || s.compact() ? null : s.written;
    }

    /** True when a section entry exists, regardless of its storage kind. */
    public boolean hasSection(long sectionKey) {
        return sections.containsKey(sectionKey);
    }

    /** True when the section rests in immutable paletted form. */
    public boolean isCompact(long sectionKey) {
        Section s = sections.get(sectionKey);
        return s != null && s.compact();
    }

    /** Compact payload for snapshot/save, or null for absent/dense sections. */
    public CompactData compactData(long sectionKey) {
        Section s = sections.get(sectionKey);
        if (s == null || !s.compact()) {
            return null;
        }
        return new CompactData(s.palette, s.data, Byte.toUnsignedInt(s.bits));
    }

    /**
     * Immutable read snapshot of any present section without mutating its
     * storage representation. Compact sections share their already-immutable
     * palette/storage arrays; dense sections are encoded into fresh compact
     * arrays. This is safe to hand to an async read-only owner.
     */
    public CompactData compactSnapshot(long sectionKey) {
        Section s = sections.get(sectionKey);
        if (s == null) {
            return null;
        }
        if (s.compact()) {
            return new CompactData(s.palette, s.data, Byte.toUnsignedInt(s.bits));
        }
        return compactFromCells(s.cells);
    }

    /**
     * Every cell of a section into {@code out} (4096, air for unset cells),
     * for whole-section comparisons. Returns false, leaving {@code out}
     * untouched, when the section is absent (all air).
     */
    public boolean copyCells(long sectionKey, BlockState[] out) {
        Section s = sections.get(sectionKey);
        if (s == null) {
            return false;
        }
        for (int i = 0; i < CELLS; i++) {
            BlockState state = stateAt(s, i);
            out[i] = state != null ? state : air;
        }
        return true;
    }

    /** Written-state query that works for both dense and compact sections. */
    public boolean isCellWritten(long sectionKey, int cellIndex) {
        Section s = sections.get(sectionKey);
        return s != null && writtenAt(s, cellIndex);
    }

    /**
     * Adopt an immutable SimpleBitStorage-shaped section. Imported compact
     * cells are considered written exactly when non-air. The first mutation
     * promotes the section to the normal dense representation.
     */
    public void putCompact(long sectionKey, BlockState[] palette, long[] data, int bits) {
        putCompact(sectionKey, palette, data, bits, false);
    }

    /**
     * Adopt an immutable section, optionally sealing it: a sealed section
     * counts as entirely live-layer owned, so world generation may never
     * write into it (C1). Adopted foreign storage - a vanilla world import -
     * is sealed, because its contents are somebody's build rather than this
     * generator's own output to refresh.
     */
    public void putCompact(long sectionKey, BlockState[] palette, long[] data, int bits,
            boolean sealed) {
        if (palette == null || palette.length == 0) {
            throw new IllegalArgumentException("compact palette is empty");
        }
        int expectedBits = bitsFor(palette.length);
        if (bits != expectedBits) {
            throw new IllegalArgumentException(
                    "compact bits " + bits + " != expected " + expectedBits);
        }
        if (bits > 0) {
            int perLong = 64 / bits;
            int required = (CELLS + perLong - 1) / perLong;
            if (data == null || data.length < required) {
                throw new IllegalArgumentException("compact data is truncated");
            }
        }
        int nonAir = 0;
        boolean canonicalAirOnly = true;
        Section candidate = new Section(palette, data, bits, 0);
        for (int i = 0; i < CELLS; i++) {
            BlockState state = stateAt(candidate, i);
            if (state == null) {
                throw new IllegalArgumentException("compact palette id out of range");
            }
            if (!state.isAir()) {
                nonAir++;
            }
            if (state != air) {
                canonicalAirOnly = false;
            }
        }
        // Cave air and void air are air for occupancy, but their block-state
        // identities survive generation and must survive compact publication.
        if (canonicalAirOnly) {
            sections.remove(sectionKey);
            return;
        }
        candidate.writtenCount = nonAir;
        candidate.sealed = sealed;
        sections.put(sectionKey, candidate);
    }

    /**
     * Publish one generated section, respecting mutation provenance (C1).
     *
     * <p>The ownership rule: generation output is subordinate to the live
     * layer. A cell the live layer has written is never written again by
     * generation; every other cell carries no claim, so a later generation
     * pass replaces it with its current output - air included, which is
     * what makes this a full generation delta rather than a non-air-only
     * merge (feature-generated air can now clear a stale cell).
     *
     * <p>Storage form is not provenance: an unclaimed section is published
     * wholesale into compact resting form regardless of whether it was
     * compact or dense before, while a section with any claim stays dense
     * and is merged cell by cell.
     */
    public void putGeneratedCells(long sectionKey, BlockState[] cells) {
        if (cells.length != CELLS) {
            throw new IllegalArgumentException("generated source must have 4096 cells");
        }
        Section s = sections.get(sectionKey);
        if (s == null || !hasEdits(s)) {
            putCompactCells(sectionKey, cells);
            return;
        }
        if (s.sealed) {
            // Every cell is claimed: nothing to publish, and no reason to
            // pay the promote-to-dense cost to discover that cell by cell.
            return;
        }
        if (s.compact()) {
            // Defensive: a live write promotes, so a claimed section is
            // normally already dense.
            s = promote(sectionKey, s);
        }
        s.tickingBlocks = -1;
        s.tickingFluids = -1;
        for (int i = 0; i < CELLS; i++) {
            if (editedAt(s, i)) {
                continue;
            }
            BlockState state = cells[i] != null ? cells[i] : air;
            s.cells[i] = state;
            boolean nonAir = !state.isAir();
            if (nonAir && !s.written[i]) {
                s.written[i] = true;
                s.writtenCount++;
            } else if (!nonAir && s.written[i]) {
                s.written[i] = false;
                s.writtenCount--;
            }
        }
    }

    /**
     * Publish one immutable compact generation snapshot, respecting the same
     * C1 live-edit provenance as {@link #putGeneratedCells}. A null snapshot
     * means the worker observed an all-air/absent generated section and is a
     * real replacement, not "leave whatever is there".
     *
     * <p>This is the async handoff seam: the worker snapshots its private
     * SectionStore before enqueueing a result, so the tick thread never reads
     * the mutable worker store. CompactData arrays are treated as immutable.
     */
    public void putGeneratedCompact(long sectionKey, CompactData generated) {
        Section s = sections.get(sectionKey);
        if (s == null || !hasEdits(s)) {
            if (generated == null) {
                sections.remove(sectionKey);
            } else {
                putCompact(sectionKey, generated.palette(), generated.data(), generated.bits());
            }
            return;
        }
        if (s.sealed) {
            return;
        }
        if (s.compact()) {
            s = promote(sectionKey, s);
        }
        s.tickingBlocks = -1;
        s.tickingFluids = -1;
        for (int i = 0; i < CELLS; i++) {
            if (editedAt(s, i)) {
                continue;
            }
            BlockState state = generated == null ? air : compactStateAt(generated, i);
            if (state == null) {
                state = air;
            }
            s.cells[i] = state;
            boolean nonAir = !state.isAir();
            if (nonAir && !s.written[i]) {
                s.written[i] = true;
                s.writtenCount++;
            } else if (!nonAir && s.written[i]) {
                s.written[i] = false;
                s.writtenCount--;
            }
        }
    }

    /** Decode one cell from an immutable compact snapshot. */
    public static BlockState compactStateAt(CompactData compact, int index) {
        if (compact.bits() == 0) {
            return compact.palette()[0];
        }
        int bits = compact.bits();
        int perLong = 64 / bits;
        int word = index / perLong;
        int shift = (index % perLong) * bits;
        int id = (int) ((compact.data()[word] >>> shift) & ((1L << bits) - 1L));
        return id < compact.palette().length ? compact.palette()[id] : null;
    }

    /**
     * Write one generated cell, respecting mutation provenance (C1): a
     * no-op when the live layer already owns the cell. Returns true when
     * the write landed.
     */
    public boolean writeGenerated(BlockPos pos, BlockState state) {
        long key = keyFor(pos);
        Section s = sections.get(key);
        int i = indexFor(pos);
        if (s != null && editedAt(s, i)) {
            return false;
        }
        if (s == null) {
            s = new Section();
            sections.put(key, s);
        } else if (s.compact()) {
            s = promote(key, s);
        }
        countTicking(s, s.cells[i] != null ? s.cells[i] : air, state);
        s.cells[i] = state;
        if (!s.written[i]) {
            s.written[i] = true;
            s.writtenCount++;
        }
        return true;
    }

    /**
     * Pack a completed generated section directly into its compact resting
     * form, replacing whatever was there. Private on purpose: a wholesale
     * replace is blind to mutation provenance, which is how C1 happened,
     * so generation reaches it only through {@link #putGeneratedCells},
     * which first establishes that no cell of the section is claimed.
     */
    private void putCompactCells(long sectionKey, BlockState[] cells) {
        CompactData compact = compactFromCells(cells);
        // Same outcome as putCompact(sectionKey, palette, data, bits), minus
        // its validation and its decode of every cell: this encoding was just
        // built from cells, so its bits, length and ids are valid by
        // construction, and the palette holds exactly the states present.
        // So "canonical air only" is a one-entry air palette, and the non-air
        // count can be read from the source cells.
        BlockState[] palette = compact.palette();
        if (palette.length == 1 && palette[0] == air) {
            sections.remove(sectionKey);
            return;
        }
        int nonAir = 0;
        for (int i = 0; i < CELLS; i++) {
            BlockState state = cells[i] != null ? cells[i] : air;
            if (!state.isAir()) {
                nonAir++;
            }
        }
        Section candidate = new Section(palette, compact.data(), compact.bits(), nonAir);
        candidate.sealed = false;
        sections.put(sectionKey, candidate);
    }

    /** Palette sizes up to this are resolved by a linear identity scan. */
    private static final int SCAN_LIMIT = 24;

    /** Encode one 4096-cell array without touching the owning store. */
    private CompactData compactFromCells(BlockState[] cells) {
        if (cells.length != CELLS) {
            throw new IllegalArgumentException("compact source must have 4096 cells");
        }
        int[] raw = new int[CELLS];
        // Runs of one state are the common case (stone, air, water); the
        // previous cell's id is reused without a lookup. Ids are assigned in
        // first-occurrence order. BlockState equality is identity
        // (StateHolder.equals/hashCode are Object's), so a linear identity
        // scan over the few states a section usually holds gives the same
        // ids as a map; past SCAN_LIMIT states an IdentityHashMap takes over.
        BlockState[] seen = new BlockState[16];
        int count = 0;
        java.util.IdentityHashMap<BlockState, Integer> big = null;
        BlockState last = null;
        int lastId = -1;
        for (int i = 0; i < CELLS; i++) {
            BlockState state = cells[i] != null ? cells[i] : air;
            if (state != last) {
                int id = -1;
                if (big != null) {
                    Integer found = big.get(state);
                    if (found != null) {
                        id = found;
                    }
                } else {
                    for (int k = 0; k < count; k++) {
                        if (seen[k] == state) {
                            id = k;
                            break;
                        }
                    }
                }
                if (id < 0) {
                    id = count;
                    if (count == seen.length) {
                        seen = Arrays.copyOf(seen, count * 2);
                    }
                    seen[count++] = state;
                    if (big != null) {
                        big.put(state, id);
                    } else if (count > SCAN_LIMIT) {
                        big = new java.util.IdentityHashMap<>(count * 4);
                        for (int k = 0; k < count; k++) {
                            big.put(seen[k], k);
                        }
                    }
                }
                last = state;
                lastId = id;
            }
            raw[i] = lastId;
        }
        BlockState[] palette = Arrays.copyOf(seen, count);
        int bits = bitsFor(palette.length);
        long[] data = null;
        if (bits > 0) {
            int perLong = 64 / bits;
            data = new long[(CELLS + perLong - 1) / perLong];
            long mask = (1L << bits) - 1L;
            for (int i = 0; i < CELLS; i++) {
                data[i / perLong] |= (raw[i] & mask) << ((i % perLong) * bits);
            }
        }
        return new CompactData(palette, data, bits);
    }

    /**
     * Stage 2 seam: overwrite-or-create one cell plus its written flag.
     * Used by the persistence restore path so reload never materializes
     * then re-writes through the public set.
     */
    public void writeCell(long sectionKey, int cellIndex, BlockState state, boolean written) {
        writeCell(sectionKey, cellIndex, state, written, false);
    }

    /**
     * Restore seam carrying C1 provenance: {@code edited} restores the
     * live-layer claim recorded at save time, so a reloaded player edit is
     * still protected from a later generation pass.
     */
    public void writeCell(long sectionKey, int cellIndex, BlockState state, boolean written,
            boolean edited) {
        Section s = sections.computeIfAbsent(sectionKey, k -> new Section());
        if (s.compact()) {
            s = promote(sectionKey, s);
        }
        BlockState prev = s.cells[cellIndex];
        boolean wasSet = prev != null;
        countTicking(s, prev != null ? prev : air, state != null ? state : air);
        s.cells[cellIndex] = state;
        if (written && !s.written[cellIndex]) {
            s.written[cellIndex] = true;
            s.writtenCount++;
        } else if (!written && s.written[cellIndex]) {
            s.written[cellIndex] = false;
            s.writtenCount--;
        }
        setClaim(s, cellIndex, edited);
        if (!wasSet && state == null) {
            // Clearing an already-unset cell: nothing further to do.
        }
    }

    /**
     * Stage 2 seam: drop every live section. Restore-into-empty calls this
     * first so reload is a clean replace, not a merge.
     */
    public void clearAll() {
        sections.clear();
        generatedChunks.clear();
    }

    /** Number of live sections. */
    public int sectionCount() {
        return sections.size();
    }

    /** Live section keys (snapshot copy). */
    public Set<Long> sectionKeys() {
        return new HashSet<>(sections.keySet());
    }

    /** Number of explicitly written cells across all sections. */
    public int writtenCells() {
        int n = 0;
        for (Section s : sections.values()) {
            n += s.writtenCount;
        }
        return n;
    }

    /** All written positions (snapshot list, for loop walks and traces). */
    public List<BlockPos> writtenPositions() {
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<Long, Section> e : sections.entrySet()) {
            long key = e.getKey();
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            Section s = e.getValue();
            for (int i = 0; i < CELLS; i++) {
                if (!writtenAt(s, i)) {
                    continue;
                }
                int x = (i & 15) + (sx << 4);
                int y = ((i >> 8) & 15) + (sy << 4);
                int z = ((i >> 4) & 15) + (sz << 4);
                out.add(new BlockPos(x, y, z));
            }
        }
        return out;
    }

    public int getMinY() {
        return minY;
    }

    public int getHeight() {
        return height;
    }

    /** Number of compact resting sections. */
    public int compactSectionCount() {
        int count = 0;
        for (Section section : sections.values()) {
            if (section.compact()) {
                count++;
            }
        }
        return count;
    }

    /** Number of dense writable sections. */
    public int denseSectionCount() {
        return sectionCount() - compactSectionCount();
    }

    /** Modelled block-storage bytes including one map entry per section. */
    public long estimatedStorageBytes() {
        long bytes = 0;
        for (Section section : sections.values()) {
            if (!section.compact()) {
                bytes += 20_614L;
                // The C1 provenance bitset is allocated only once a section
                // actually carries a live-layer write.
                bytes += section.edited == null ? 0L : align8(16L + CELLS);
                continue;
            }
            long paletteBytes = align8(16L + 4L * section.palette.length);
            long dataBytes = section.data == null ? 0 : align8(16L + 8L * section.data.length);
            bytes += 40L + 62L + paletteBytes + dataBytes;
        }
        return bytes;
    }

    private Section promote(long key, Section compact) {
        Section dense = new Section();
        dense.sealed = compact.sealed;
        dense.tickingBlocks = compact.tickingBlocks;
        dense.tickingFluids = compact.tickingFluids;
        for (int i = 0; i < CELLS; i++) {
            BlockState state = stateAt(compact, i);
            dense.cells[i] = state;
            if (state != null && !state.isAir()) {
                dense.written[i] = true;
                dense.writtenCount++;
            }
        }
        sections.put(key, dense);
        return dense;
    }

    private static BlockState stateAt(Section section, int index) {
        if (!section.compact()) {
            return section.cells[index];
        }
        if (section.bits == 0) {
            return section.palette[0];
        }
        int bits = Byte.toUnsignedInt(section.bits);
        int perLong = 64 / bits;
        int word = index / perLong;
        int shift = (index % perLong) * bits;
        int id = (int) ((section.data[word] >>> shift) & ((1L << bits) - 1L));
        return id < section.palette.length ? section.palette[id] : null;
    }

    private static boolean writtenAt(Section section, int index) {
        if (!section.compact()) {
            return section.written[index];
        }
        BlockState state = stateAt(section, index);
        return state != null && !state.isAir();
    }

    private static int bitsFor(int paletteSize) {
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

    private static long align8(long value) {
        return (value + 7L) & ~7L;
    }
}
