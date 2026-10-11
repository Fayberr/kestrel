package dev.kestrel.worldgen;

import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * Proves the concrete mechanism behind a structure start shrinking when its
 * chunk is reloaded, the start half of the Vanilla partial-chunk round trip
 * that {@link StructureReferenceRoundTripCheck} covers for reference sets.
 *
 * <p>Vanilla saves a chunk's starts with
 * {@code SerializableChunkData.packStructureData} (javap offsets 103-108:
 * {@code StructureStart.createTag(ctx, savedChunkPos)}) and restores them with
 * {@code unpackStructureStart} -> {@code StructureStart.loadStaticStart}
 * (javap offset 113). {@code loadStaticStart} rebuilds the PiecesContainer
 * through {@code PiecesContainer.load}, and for jigsaw pieces the
 * deserialization constructor of {@code PoolElementStructurePiece} (javap
 * offsets 123-143) throws the persisted {@code BB} away and recomputes the
 * piece box from {@code element.getBoundingBox(templates, position, rotation)}.
 * A start whose live pieces carried boxes that the element no longer
 * reproduces therefore comes back with a different, usually smaller, box, and
 * {@code StructureStart.getBoundingBox} recomputes its cache from those pieces.
 *
 * <p>Default coordinates are the w00 trace's witness: seed
 * 7272240032024010793, chunk -30,3, whose JigsawStructure start is recorded
 * with box maxY=90 before the reload at afterEventSeq=5232 and maxY=84 for
 * every origin after it.
 */
public final class StructureStartRoundTripCheck {
    private StructureStartRoundTripCheck() {}

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 7272240032024010793L;
        int chunkX = args.length > 1 ? Integer.parseInt(args[1]) : -30;
        int chunkZ = args.length > 2 ? Integer.parseInt(args[2]) : 3;

        try (HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(seed)) {
            ChunkAccess chunk = HeadlessNoise.structures(structural, chunkX, chunkZ);
            StructurePieceSerializationContext ctx =
                    new StructurePieceSerializationContext(
                            structural.resources(),
                            structural.driver().access(),
                            structural.templates());
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);

            System.out.println("STRUCTURE_START_ROUNDTRIP seed=" + seed
                    + " chunk=" + chunkX + "," + chunkZ);
            boolean changed = false;
            for (Map.Entry<Structure, StructureStart> entry
                    : chunk.getAllStarts().entrySet()) {
                StructureStart before = entry.getValue();
                String beforeBox = String.valueOf(before.getBoundingBox());
                StructureStart after = StructureStart.loadStaticStart(
                        ctx, before.createTag(ctx, pos), seed);
                String afterBox = after == null
                        ? "<null>" : String.valueOf(after.getBoundingBox());

                System.out.println("  " + entry.getKey().getClass().getName());
                System.out.println("    before box=" + beforeBox
                        + " pieces=" + before.getPieces().size()
                        + " refs=" + before.getReferences());
                System.out.println("    after  box=" + afterBox
                        + " pieces=" + (after == null ? -1 : after.getPieces().size())
                        + " refs=" + (after == null ? -1 : after.getReferences()));
                if (!beforeBox.equals(afterBox)) {
                    changed = true;
                    dumpPieceDiff(before, after);
                }
            }
            System.out.println("STRUCTURE_START_ROUNDTRIP changed=" + changed);
        }
    }

    private static void dumpPieceDiff(StructureStart before, StructureStart after) {
        int n = Math.max(before.getPieces().size(),
                after == null ? 0 : after.getPieces().size());
        for (int i = 0; i < n; i++) {
            StructurePiece b = i < before.getPieces().size()
                    ? before.getPieces().get(i) : null;
            StructurePiece a = after != null && i < after.getPieces().size()
                    ? after.getPieces().get(i) : null;
            String bb = b == null ? "<none>" : String.valueOf(b.getBoundingBox());
            String ab = a == null ? "<none>" : String.valueOf(a.getBoundingBox());
            if (!bb.equals(ab)) {
                System.out.println("    piece[" + i + "] "
                        + (b == null ? "?" : b.getClass().getSimpleName()));
                System.out.println("      before=" + bb);
                System.out.println("      after =" + ab);
            }
        }
    }
}
