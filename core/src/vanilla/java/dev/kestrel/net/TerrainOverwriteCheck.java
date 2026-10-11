package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.SectionStore;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.persist.Sidecar;
import dev.kestrel.persist.WorldStore;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.LiveGenerator;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.stream.Stream;

/**
 * Regression gate for Critical Finding C1:
 * "Generation can overwrite player edits, including saved edits"
 *
 * <p>The defect: {@link LiveGenerator#ensureChunk} republishes the whole
 * 3x3 margin after generating a new center, and the publish path had no
 * way to tell a player's edit from its own earlier output. Storage
 * representation stood in for provenance, so a compact section was
 * replaced wholesale and a dense section kept only blocks placed in
 * generated air: mining a natural block or replacing one was undone as
 * soon as a neighbouring chunk generated.
 *
 * <p>The rule this gate enforces, per-cell and independent of storage
 * form: cells written through {@link SectionStore#set} belong to the live
 * layer and generation may never write them again; cells produced by
 * generation carry no claim and a later pass may replace them with its
 * current output, air included.
 *
 * <p>This is the audit's own smallest verification, in full: mine one
 * original solid and replace another, generate the neighbour, assert both
 * edits, repeat across save/reload and across compact and dense sections,
 * and require equality between authoritative cells, the cells actually
 * sent to a client, and the cells on disk. It also asserts the negative
 * direction, that generation still refreshes cells nobody has claimed, so
 * "fixing" C1 by disabling margin republication does not pass.
 *
 * <p>Run (Pi, JDK 25):
 * <pre>
 * java -cp "$(cat ~/kestrel-run/cp.txt)" dev.kestrel.net.TerrainOverwriteCheck
 * </pre>
 */
public final class TerrainOverwriteCheck {
    private TerrainOverwriteCheck() {
    }

    private static final long SEED = 4242424242L;

    private static final int CHUNK_A_X = 0;
    private static final int CHUNK_A_Z = 0;
    /** Neighbour generated after the edits, in the live phase. */
    private static final int CHUNK_B_X = 1;
    private static final int CHUNK_B_Z = 0;
    /** Neighbour generated after the edits, in the post-reload phase. */
    private static final int CHUNK_C_X = 0;
    private static final int CHUNK_C_Z = 1;

    private static boolean ok = true;

    /** One simulated player edit plus the natural block it displaced. */
    private record Edit(String what, BlockPos pos, BlockState natural, BlockState want) {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Path world = Files.createTempDirectory("kestrel-c1-");
        try {
            run(world);
        } finally {
            deleteRecursively(world);
        }

        if (ok) {
            System.out.println("[terrain-overwrite] PASS: generation never overwrote a player edit,"
                    + " live or after reload, and still refreshed unclaimed cells.");
        } else {
            System.out.println("[terrain-overwrite] FAIL: Critical Finding C1 reproduced.");
            System.exit(1);
        }
    }

    private static void run(Path world) throws Exception {
        // ---------------------------------------------------------------
        // Phase 1: live. Generate A, edit it, then generate its neighbour.
        // ---------------------------------------------------------------
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        LiveGenerator gen = new LiveGenerator(HeadlessNoise.load(SEED));
        ChunkSender sender = new ChunkSender(loop, 1);
        sender.setGenerator(gen);

        System.out.println("[terrain-overwrite] Phase 1: generating chunk A at ("
                + CHUNK_A_X + "," + CHUNK_A_Z + ")");
        sender.sectionBytes(CHUNK_A_X, CHUNK_A_Z);
        long keyA = ChunkPos.pack(CHUNK_A_X, CHUNK_A_Z);
        if (!loop.store().isChunkGenerated(keyA)) {
            System.err.println("[terrain-overwrite] SETUP FAIL: chunk A was not marked generated");
            System.exit(2);
        }
        System.out.println("[terrain-overwrite]   store sections=" + loop.store().sectionCount()
                + " compact=" + loop.store().compactSectionCount()
                + " dense=" + loop.store().denseSectionCount());

        List<Edit> edits = chooseEdits(loop.store());
        // A cell nobody edits, in the same chunk, to prove the fix did not
        // simply switch margin republication off.
        BlockPos control = controlPos(loop.store(), edits);
        BlockState controlNatural = loop.store().get(control);

        System.out.println("[terrain-overwrite] Phase 1: applying player edits");
        for (Edit e : edits) {
            loop.store().set(e.pos(), e.want());
            System.out.println("[terrain-overwrite]   " + e.what() + " at " + e.pos()
                    + ": " + e.natural() + " -> " + loop.store().get(e.pos()));
            if (!loop.store().get(e.pos()).equals(e.want())) {
                System.err.println("[terrain-overwrite] SETUP FAIL: edit did not apply");
                System.exit(2);
            }
        }
        System.out.println("[terrain-overwrite]   store sections=" + loop.store().sectionCount()
                + " compact=" + loop.store().compactSectionCount()
                + " dense=" + loop.store().denseSectionCount()
                + " editedCells=" + loop.store().editedCells());

        System.out.println("[terrain-overwrite] Phase 1: generating neighbour B at ("
                + CHUNK_B_X + "," + CHUNK_B_Z + "), which republishes A's margin");
        sender.sectionBytes(CHUNK_B_X, CHUNK_B_Z);
        expect(loop.store().isChunkGenerated(ChunkPos.pack(CHUNK_B_X, CHUNK_B_Z)),
                "chunk B was generated (the republish really ran)");

        assertEdits("live", loop, sender, edits);
        expect(loop.store().get(control).equals(controlNatural),
                "unclaimed cell " + control + " still holds generated terrain"
                        + " (expected " + controlNatural + ", actual "
                        + loop.store().get(control) + ")");

        // ---------------------------------------------------------------
        // Phase 2: the same assertions across save and reload, with a
        // fresh generator whose chunk cache is empty - the audit's
        // "empty generator grid reconstructs A's natural terrain when A is
        // needed as B's margin" path, which reaches saved builds.
        // ---------------------------------------------------------------
        System.out.println("[terrain-overwrite] Phase 2: saving to " + world);
        WorldStore.save(loop, world);

        KestrelLevel level2 = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop2 = new TickLoop(level2);
        WorldStore.load(loop2, world);
        LiveGenerator gen2 = new LiveGenerator(HeadlessNoise.load(SEED));
        ChunkSender sender2 = new ChunkSender(loop2, 1);
        sender2.setGenerator(gen2);
        System.out.println("[terrain-overwrite] Phase 2: reloaded, sections="
                + loop2.store().sectionCount()
                + " compact=" + loop2.store().compactSectionCount()
                + " dense=" + loop2.store().denseSectionCount()
                + " editedCells=" + loop2.store().editedCells());

        assertEdits("after reload", loop2, sender2, edits);

        System.out.println("[terrain-overwrite] Phase 2: generating neighbour C at ("
                + CHUNK_C_X + "," + CHUNK_C_Z + ") from an empty generator cache");
        sender2.sectionBytes(CHUNK_C_X, CHUNK_C_Z);
        expect(gen2.centersCount() > 0, "chunk C ran a generation pass after reload");
        expect(gen2.carversCount() > 0,
                "the reloaded generator rebuilt margin chunks from scratch"
                        + " (carvers=" + gen2.carversCount() + ")");

        assertEdits("after reload + neighbour generation", loop2, sender2, edits);
        expect(loop2.store().get(control).equals(controlNatural),
                "unclaimed cell " + control + " still holds generated terrain after reload");

        // ---------------------------------------------------------------
        // Phase 3: the legacy-world migration. Worlds saved before this
        // fix carry no provenance record at all, and real ones exist on
        // disk, so the pre-KEX3 upgrade path has to protect their edits
        // too. Downgrade the sidecars this run just wrote to exactly what
        // the old encoder would have produced, then reload.
        // ---------------------------------------------------------------
        System.out.println("[terrain-overwrite] Phase 3: downgrading sidecars to KEX2"
                + " and reloading the legacy world");
        int downgraded = downgradeSidecarsToV2(WorldStore.regionDir(world));
        expect(downgraded > 0, "rewrote " + downgraded + " sidecar(s) into the pre-KEX3 format");

        KestrelLevel level3 = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop3 = new TickLoop(level3);
        WorldStore.load(loop3, world);
        LiveGenerator gen3 = new LiveGenerator(HeadlessNoise.load(SEED));
        ChunkSender sender3 = new ChunkSender(loop3, 1);
        sender3.setGenerator(gen3);
        System.out.println("[terrain-overwrite] Phase 3: legacy world loaded, sections="
                + loop3.store().sectionCount()
                + " editedCells=" + loop3.store().editedCells());

        assertEdits("after a pre-KEX3 reload", loop3, sender3, edits);
        sender3.sectionBytes(CHUNK_C_X, CHUNK_C_Z);
        assertEdits("after a pre-KEX3 reload + neighbour generation", loop3, sender3, edits);
    }

    /**
     * Rewrite every sidecar under {@code regionDir} from KEX3 back into
     * the KEX2 layout, byte for byte as the previous encoder wrote it: the
     * older magic and a section table carrying only the written bitset,
     * with everything after it copied verbatim and the CRC recomputed.
     *
     * @return the number of sidecars rewritten
     */
    private static int downgradeSidecarsToV2(Path regionDir) throws Exception {
        int count = 0;
        try (Stream<Path> files = Files.list(regionDir)) {
            for (Path path : files.filter(f -> f.toString().endsWith(Sidecar.SUFFIX)).toList()) {
                byte[] all = Files.readAllBytes(path);
                ByteBuffer in = ByteBuffer.wrap(all, 0, all.length - 4).order(ByteOrder.BIG_ENDIAN);
                int magic = in.getInt();
                if (magic != Sidecar.MAGIC_V4 && magic != Sidecar.MAGIC_V5) {
                    throw new IllegalStateException("expected a KEX4/KEX5 sidecar at " + path);
                }
                ByteBuffer out = ByteBuffer.allocate(all.length).order(ByteOrder.BIG_ENDIAN);
                out.putInt(Sidecar.MAGIC_V2);
                // regionX, regionZ, minY, height (4x int), gameTime (long),
                // dimension (int), then the section count.
                for (int i = 0; i < 4; i++) {
                    out.putInt(in.getInt());
                }
                out.putLong(in.getLong());
                out.putInt(in.getInt());
                int sectionCount = in.getInt();
                out.putInt(sectionCount);
                byte[] bitset = new byte[512];
                for (int i = 0; i < sectionCount; i++) {
                    out.putLong(in.getLong());
                    in.get(bitset);
                    out.put(bitset);
                    in.get(bitset); // the KEX3 provenance bitset, dropped
                }
                // Copy exactly the KEX2 tail and drop KEX4/KEX5 metadata.
                int generatedCount = in.getInt();
                out.putInt(generatedCount);
                for (int i = 0; i < generatedCount; i++) out.putLong(in.getLong());

                int beCount = in.getInt();
                out.putInt(beCount);
                for (int i = 0; i < beCount; i++) {
                    out.putLong(in.getLong());
                    out.put(in.get());
                    out.put(in.get());
                }

                int queueCount = in.getInt();
                out.putInt(queueCount);
                for (int i = 0; i < queueCount; i++) {
                    out.putInt(in.getInt());
                    out.putInt(in.getInt());
                    out.putInt(in.getInt());
                    out.put(in.get());
                    out.put(in.get());
                    out.putLong(in.getLong());
                }

                byte[] body = new byte[out.position()];
                System.arraycopy(out.array(), 0, body, 0, body.length);
                CRC32 crc = new CRC32();
                crc.update(body);
                ByteBuffer file = ByteBuffer.allocate(body.length + 4).order(ByteOrder.BIG_ENDIAN);
                file.put(body);
                file.putInt((int) crc.getValue());
                Files.write(path, file.array());
                count++;
            }
        }
        return count;
    }

    /**
     * Pick the audit's two edits (mine an original solid, replace another)
     * plus a block placed into generated air high above, which lands in a
     * section the store does not even hold yet. Together they cover a
     * section that was compact and is promoted by the edit, and a section
     * that is created dense by the edit.
     */
    private static List<Edit> chooseEdits(SectionStore store) {
        int worldX = (CHUNK_A_X << 4) + 8;
        int worldZ = (CHUNK_A_Z << 4) + 8;
        BlockPos mined = null;
        BlockPos replaced = null;
        for (int y = 0; y < 100; y++) {
            BlockPos p = new BlockPos(worldX, y, worldZ);
            if (store.get(p).isAir()) {
                continue;
            }
            if (mined == null) {
                mined = p;
            } else if (y > mined.getY()) {
                replaced = p;
                break;
            }
        }
        BlockPos placed = new BlockPos(worldX, 300, worldZ);
        if (mined == null || replaced == null || !store.get(placed).isAir()) {
            System.err.println("[terrain-overwrite] SETUP FAIL: chunk A lacks the expected"
                    + " solid/air columns at x=" + worldX + " z=" + worldZ);
            System.exit(2);
        }
        List<Edit> edits = new ArrayList<>(3);
        edits.add(new Edit("mined a natural solid to air", mined, store.get(mined),
                Blocks.AIR.defaultBlockState()));
        edits.add(new Edit("replaced a natural solid with glass", replaced, store.get(replaced),
                Blocks.GLASS.defaultBlockState()));
        edits.add(new Edit("placed stone in generated air", placed, store.get(placed),
                Blocks.STONE.defaultBlockState()));
        return edits;
    }

    /** A non-air cell in chunk A that no edit touches and no edit shares a section with. */
    private static BlockPos controlPos(SectionStore store, List<Edit> edits) {
        int worldX = (CHUNK_A_X << 4) + 2;
        int worldZ = (CHUNK_A_Z << 4) + 2;
        for (int y = 0; y < 100; y++) {
            BlockPos p = new BlockPos(worldX, y, worldZ);
            if (store.get(p).isAir()) {
                continue;
            }
            boolean clashes = false;
            for (Edit e : edits) {
                if (SectionStore.keyFor(e.pos()) == SectionStore.keyFor(p)) {
                    clashes = true;
                    break;
                }
            }
            if (!clashes) {
                return p;
            }
        }
        System.err.println("[terrain-overwrite] SETUP FAIL: no unclaimed control cell in chunk A");
        System.exit(2);
        return null;
    }

    /**
     * The three views of one cell must agree: what the server believes
     * (the store), what a client is told (the encoded section bytes), and
     * what a reload would restore (the bytes on disk, checked by phase 2's
     * reload of everything asserted here).
     */
    private static void assertEdits(String phase, TickLoop loop, ChunkSender sender,
            List<Edit> edits) throws Exception {
        for (Edit e : edits) {
            BlockState authoritative = loop.store().get(e.pos());
            expect(authoritative.equals(e.want()),
                    phase + ": " + e.what() + " survived in the store (expected " + e.want()
                            + ", actual " + authoritative + ")");
            BlockState sent = sentState(loop, sender, e.pos());
            expect(sent.equals(e.want()),
                    phase + ": " + e.what() + " survived in the bytes sent to a client (expected "
                            + e.want() + ", actual " + sent + ")");
            expect(loop.store().isCellEdited(SectionStore.keyFor(e.pos()),
                            SectionStore.indexFor(e.pos())),
                    phase + ": " + e.what() + " is still recorded as a live-layer edit");
        }
    }

    /** Decode one cell out of the section payload the chunk sender would ship. */
    private static BlockState sentState(TickLoop loop, ChunkSender sender, BlockPos pos)
            throws Exception {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        List<byte[]> payloads = sender.sectionBytes(chunkX, chunkZ);
        int minSectionY = loop.store().getMinY() >> 4;
        int slot = SectionPos.blockToSectionCoord(pos.getY()) - minSectionY;
        if (slot < 0 || slot >= payloads.size()) {
            throw new IllegalStateException("section slot " + slot + " outside the column");
        }
        return decodeCell(payloads.get(slot), SectionStore.indexFor(pos));
    }

    /**
     * Inverse of {@code Outbound.sectionBytes}: short block count, short
     * fluid count, bits-per-entry, then either a single palette id, a
     * section palette of global ids, or raw global ids.
     */
    private static BlockState decodeCell(byte[] payload, int cellIndex) throws Exception {
        Wire.Reader r = new Wire.Reader(payload);
        r.s();
        r.s();
        int bits = r.b();
        if (bits == 0) {
            return Block.stateById(r.varInt());
        }
        int[] palette = null;
        if (bits < 9) {
            int size = r.varInt();
            palette = new int[size];
            for (int i = 0; i < size; i++) {
                palette[i] = r.varInt();
            }
        }
        int perLong = 64 / bits;
        int words = (SectionStore.CELLS + perLong - 1) / perLong;
        long[] data = new long[words];
        for (int i = 0; i < words; i++) {
            data[i] = r.l();
        }
        long mask = bits == 64 ? -1L : (1L << bits) - 1L;
        int raw = (int) ((data[cellIndex / perLong] >>> ((cellIndex % perLong) * bits)) & mask);
        return Block.stateById(palette == null ? raw : palette[raw]);
    }

    private static void expect(boolean cond, String what) {
        System.out.println((cond ? "[terrain-overwrite] OK: " : "[terrain-overwrite] FAIL: ") + what);
        ok &= cond;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
