package dev.kestrel.worldgen;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * G2 step-8 end-to-end structure placement proof.
 *
 * <p>Finds a deterministic valid structure piece, chooses a chunk actually
 * intersected by that piece, builds two identical structures-on prefixes
 * through CARVERS, then runs normal FEATURES in the control and
 * structures-on FEATURES in the experiment. Any cell difference therefore
 * comes from the structure block inserted into the otherwise identical,
 * per-feature-reseeded decoration loop.
 */
public final class StructurePlacementCheck {
    private static int checks;

    private StructurePlacementCheck() {
    }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 4242424242L;

        try (HeadlessNoise.Structural structural =
                HeadlessNoise.loadStructural(seed)) {
            Target target = findTarget(structural);

            Map<Long, ChunkAccess> control =
                    prefix(structural, target.chunkX(), target.chunkZ());
            Map<Long, ChunkAccess> experiment =
                    prefix(structural, target.chunkX(), target.chunkZ());

            ChunkPos centerPos =
                    new ChunkPos(target.chunkX(), target.chunkZ());
            ChunkAccess controlCenter =
                    control.get(centerPos.pack());
            ChunkAccess experimentCenter =
                    experiment.get(centerPos.pack());

            HeadlessNoise.RegionView controlView =
                    new HeadlessNoise.RegionView(
                            structural.driver(), centerPos, control, structural.templates());
            HeadlessNoise.RegionView experimentView =
                    new HeadlessNoise.RegionView(
                            structural.driver(), centerPos, experiment, structural.templates());

            HeadlessNoise.features(
                    structural.driver(), controlCenter, controlView);
            HeadlessNoise.featuresWithStructures(
                    structural, experimentCenter, experimentView);

            int diffs = diffCount(
                    controlCenter,
                    experimentCenter,
                    target.chunkX(),
                    target.chunkZ());

            ok(!experimentCenter.getAllReferences().isEmpty(),
                    "target has structure references");
            ok(diffs > 0,
                    "structure placement changes target chunk blocks");

            int controlEntities =
                    ((ProtoChunk) controlCenter).getEntities().size();
            ProtoChunk experimentProto = (ProtoChunk) experimentCenter;
            int structureEntities = experimentProto.getEntities().size();
            ok(structureEntities > controlEntities,
                    "structure placement preserves generated entities");

            System.out.println("STRUCTURE_ENTITY_NBT "
                    + experimentProto.getEntities());
            System.out.println("STRUCTURE_PENDING_BLOCK_ENTITY_NBT "
                    + experimentProto.getBlockEntityNbts());
            System.out.println("STRUCTURE_REAL_BLOCK_ENTITIES "
                    + experimentProto.getBlockEntities().keySet());

            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int wx = target.chunkX() + dx;
                    int wz = target.chunkZ() + dz;
                    ChunkAccess raw = experiment.get(ChunkPos.pack(wx, wz));
                    if (!(raw instanceof ProtoChunk proto)) {
                        throw new AssertionError(
                                "missing ProtoChunk in structure window " + wx + "," + wz);
                    }
                    System.out.println(
                            "STRUCTURE_WINDOW_DATA chunk=" + wx + "," + wz
                            + " entities=" + proto.getEntities()
                            + " pendingBlockEntities=" + proto.getBlockEntityNbts()
                            + " realBlockEntities=" + proto.getBlockEntities().keySet());
                }
            }

            eq(ChunkStatus.FEATURES,
                    experimentCenter.getPersistedStatus(),
                    "structures-on center reaches FEATURES");

            System.out.println(
                    "STRUCTURE_PLACEMENT target="
                            + target.chunkX() + "," + target.chunkZ()
                            + " source="
                            + target.sourceX() + "," + target.sourceZ()
                            + " pieceBox="
                            + target.pieceBox()
                            + " cellDiffs="
                            + diffs
                            + " controlEntities="
                            + controlEntities
                            + " structureEntities="
                            + structureEntities);
        }

        System.out.println(
                "StructurePlacementCheck: PASSED (" + checks + " checks)");
    }

    private static Target findTarget(
            HeadlessNoise.Structural structural) {
        // Small deterministic scan; structure starts are sparse, but a 33x33
        // window is ample for the fixed regression seed.
        for (int radius = 0; radius <= 16; radius++) {
            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                        continue;
                    }

                    ChunkAccess starts =
                            HeadlessNoise.structures(structural, x, z);
                    for (StructureStart start
                            : starts.getAllStarts().values()) {
                        if (!start.isValid()) {
                            continue;
                        }
                        for (StructurePiece piece : start.getPieces()) {
                            BoundingBox box = piece.getBoundingBox();
                            int targetX = Math.floorDiv(box.minX(), 16);
                            int targetZ = Math.floorDiv(box.minZ(), 16);
                            return new Target(
                                    x, z, targetX, targetZ, box);
                        }
                    }
                }
            }
        }
        throw new AssertionError(
                "no valid structure piece found in scan window");
    }

    static Map<Long, ChunkAccess> prefix(
            HeadlessNoise.Structural structural,
            int centerX,
            int centerZ) {
        Map<Long, ChunkAccess> chunks = new HashMap<>();
        Map<Long, ChunkAccess> startsCache = new HashMap<>();

        // First materialize the complete structures-on 3x3 through NOISE.
        // Surface biome interpolation can read across a chunk edge, so every
        // center must see the already-generated biome palettes of its 3x3
        // neighbours rather than regenerating them ad hoc.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                ChunkAccess chunk =
                        HeadlessNoise.noiseWithStructures(
                                structural, x, z, startsCache);
                chunks.put(ChunkPos.pack(x, z), chunk);
            }
        }

        // Surface each real 3x3 chunk with the same palette-backed biome-grid
        // path used by the Tier-R generator. The outer radius-2 entries are
        // BIOMES-only margin chunks: Beardifier changes density/blocks, not
        // biome palettes, so structures-off NOISE is sufficient for those
        // read-only surface samples.
        Map<ChunkPos, ChunkAccess> biomeMargin = new HashMap<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                ChunkAccess chunk = chunks.get(ChunkPos.pack(x, z));
                Map<ChunkPos, ChunkAccess> biomeGrid =
                        HeadlessNoise.prepareBiomeGrid(
                                structural.driver(),
                                x,
                                z,
                                (nx, nz) -> chunks.get(ChunkPos.pack(nx, nz)),
                                biomeMargin);
                HeadlessNoise.surfaceVanilla(
                        structural.driver(), chunk, biomeGrid);
                HeadlessNoise.carveVanilla(
                        structural.driver(), chunk);
                ((ProtoChunk) chunk).setPersistedStatus(
                        ChunkStatus.CARVERS);
                HeadlessNoise.releaseNoiseChunk(chunk);
            }
        }

        // noiseWithStructures() advanced the real 3x3 centers in-place
        // inside startsCache. The same cache also contains every bare
        // STRUCTURE_STARTS source reachable from their reference sets, which
        // is exactly what StructureManager.startsForStructure needs later.
        return startsCache;
    }

    private static int diffCount(
            ChunkAccess a,
            ChunkAccess b,
            int chunkX,
            int chunkZ) {
        int diffs = 0;
        BlockPos.MutableBlockPos pos =
                new BlockPos.MutableBlockPos();

        for (int y = HeadlessNoise.MIN_Y;
                y < HeadlessNoise.MIN_Y + HeadlessNoise.HEIGHT;
                y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(
                            (chunkX << 4) + x,
                            y,
                            (chunkZ << 4) + z);
                    BlockState left = a.getBlockState(pos);
                    BlockState right = b.getBlockState(pos);
                    if (left != right) {
                        diffs++;
                    }
                }
            }
        }

        return diffs;
    }

    private record Target(
            int sourceX,
            int sourceZ,
            int chunkX,
            int chunkZ,
            BoundingBox pieceBox) {
    }

    private static void eq(
            Object expected,
            Object actual,
            String label) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + " got " + actual);
        }
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) {
            throw new AssertionError(label);
        }
    }
}
