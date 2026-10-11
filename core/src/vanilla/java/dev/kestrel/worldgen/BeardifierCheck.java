package dev.kestrel.worldgen;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

/**
 * Isolated G2 Beardifier proof.
 *
 * <p>Finds a real structure whose terrain adaptation is not NONE, then
 * generates an intersecting chunk twice with the same generator/random state:
 * once through the structures-off NOISE path (empty Beardifier) and once
 * through the structures-on NOISE path (real starts + references). A non-zero
 * block delta proves that the real StructureManager is reaching
 * fillFromNoise's Beardifier rather than merely carrying metadata.
 */
public final class BeardifierCheck {
    private static int checks;

    private BeardifierCheck() {
    }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 4242424242L;
        int maxRadius = args.length > 1 ? Integer.parseInt(args[1]) : 48;

        try (HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(seed)) {
            Registry<Structure> registry =
                    structural.driver().access().lookupOrThrow(Registries.STRUCTURE);
            Map<Long, ChunkAccess> startsCache = new HashMap<>();

            Candidate candidate = findCandidate(
                    structural, registry, startsCache, maxRadius);
            Probe probe = findAffectedChunk(
                    structural, candidate, startsCache);

            ok(candidate.structure().terrainAdaptation() != TerrainAdjustment.NONE,
                    "candidate terrain adaptation is active");
            ok(probe.diffs() > 0,
                    "real Beardifier changes NOISE block output");
            eq(ChunkStatus.NOISE, probe.structuresOn().getPersistedStatus(),
                    "structures-on target reaches NOISE");
            ok(!probe.structuresOn().getAllReferences().isEmpty(),
                    "structures-on target carries structure references");

            System.out.println(
                    "BEARDIFIER structure=" + candidate.key().identifier()
                            + " adaptation=" + candidate.structure().terrainAdaptation()
                            + " source=" + candidate.sourceX() + "," + candidate.sourceZ()
                            + " target=" + probe.targetX() + "," + probe.targetZ()
                            + " startBox=" + candidate.start().getBoundingBox()
                            + " cellDiffs=" + probe.diffs());
        }

        System.out.println("BeardifierCheck: PASSED (" + checks + " checks)");
    }

    private static Candidate findCandidate(
            HeadlessNoise.Structural structural,
            Registry<Structure> registry,
            Map<Long, ChunkAccess> startsCache,
            int maxRadius) {
        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                        continue;
                    }
                    long packed = ChunkPos.pack(x, z);
                    ChunkAccess starts = startsCache.get(packed);
                    if (starts == null) {
                        starts = HeadlessNoise.structures(structural, x, z);
                        startsCache.put(packed, starts);
                    }
                    for (Map.Entry<Structure, StructureStart> entry
                            : starts.getAllStarts().entrySet()) {
                        Structure structure = entry.getKey();
                        StructureStart start = entry.getValue();
                        if (!start.isValid()
                                || structure.terrainAdaptation() == TerrainAdjustment.NONE) {
                            continue;
                        }
                        ResourceKey<Structure> key = registry.getResourceKey(structure)
                                .orElseThrow(() -> new AssertionError(
                                        "unregistered adapting structure"));
                        return new Candidate(key, structure, start, x, z);
                    }
                }
            }
        }
        throw new AssertionError(
                "no terrain-adapting structure found within radius " + maxRadius);
    }

    private static Probe findAffectedChunk(
            HeadlessNoise.Structural structural,
            Candidate candidate,
            Map<Long, ChunkAccess> startsCache) {
        BoundingBox box = candidate.start().getBoundingBox();

        // Beardifier influence extends beyond a piece's literal block box.
        // Probe the whole start box plus one chunk of margin, with source
        // chunk first because it normally contains the generation point.
        int minX = Math.floorDiv(box.minX(), 16) - 1;
        int maxX = Math.floorDiv(box.maxX(), 16) + 1;
        int minZ = Math.floorDiv(box.minZ(), 16) - 1;
        int maxZ = Math.floorDiv(box.maxZ(), 16) + 1;

        Probe source = probe(
                structural, candidate.sourceX(), candidate.sourceZ(), startsCache);
        if (source.diffs() > 0) {
            return source;
        }

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (x == candidate.sourceX() && z == candidate.sourceZ()) {
                    continue;
                }
                Probe probe = probe(structural, x, z, startsCache);
                if (probe.diffs() > 0) {
                    return probe;
                }
            }
        }
        throw new AssertionError(
                "terrain-adapting start produced no structures-on/off NOISE delta: "
                        + candidate.key().identifier() + " box=" + box);
    }

    private static Probe probe(
            HeadlessNoise.Structural structural,
            int chunkX,
            int chunkZ,
            Map<Long, ChunkAccess> startsCache) {
        ChunkAccess plain =
                HeadlessNoise.noise(structural.driver(), chunkX, chunkZ);
        ChunkAccess withStructures =
                HeadlessNoise.noiseWithStructures(
                        structural, chunkX, chunkZ, startsCache);
        int diffs = diffCount(plain, withStructures, chunkX, chunkZ);
        return new Probe(chunkX, chunkZ, plain, withStructures, diffs);
    }

    private static int diffCount(
            ChunkAccess a,
            ChunkAccess b,
            int chunkX,
            int chunkZ) {
        int diffs = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = HeadlessNoise.MIN_Y;
                y < HeadlessNoise.MIN_Y + HeadlessNoise.HEIGHT;
                y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set((chunkX << 4) + x, y, (chunkZ << 4) + z);
                    if (Block.getId(a.getBlockState(pos))
                            != Block.getId(b.getBlockState(pos))) {
                        diffs++;
                    }
                }
            }
        }
        return diffs;
    }

    private record Candidate(
            ResourceKey<Structure> key,
            Structure structure,
            StructureStart start,
            int sourceX,
            int sourceZ) {
    }

    private record Probe(
            int targetX,
            int targetZ,
            ChunkAccess plain,
            ChunkAccess structuresOn,
            int diffs) {
    }

    private static void eq(Object expected, Object actual, String label) {
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
