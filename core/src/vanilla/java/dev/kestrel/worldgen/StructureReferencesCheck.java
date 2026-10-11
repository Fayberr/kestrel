package dev.kestrel.worldgen;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * G2 step-8 STRUCTURE_REFERENCES proof.
 *
 * <p>For two deterministic target chunks, recomputes the exact Vanilla
 * radius-8 bounding-box intersection relation from STRUCTURE_STARTS and
 * compares it with HeadlessNoise.structureReferences(), which itself
 * delegates reference generation to ChunkGenerator.createReferences().
 */
public final class StructureReferencesCheck {
    private static int checks;

    private StructureReferencesCheck() {
    }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 4242424242L;

        try (HeadlessNoise.Structural structural =
                HeadlessNoise.loadStructural(seed)) {
            verify(structural, 0, 0);
            verify(structural, 5, 7);
        }

        System.out.println(
                "StructureReferencesCheck: PASSED (" + checks + " checks)");
    }

    private static void verify(
            HeadlessNoise.Structural structural,
            int chunkX,
            int chunkZ) {
        Map<Structure, Set<Long>> expected = expected(
                structural, chunkX, chunkZ);

        ChunkAccess center =
                HeadlessNoise.structureReferences(
                        structural, chunkX, chunkZ);

        eq(ChunkStatus.STRUCTURE_REFERENCES,
                center.getPersistedStatus(),
                "persisted status " + chunkX + "," + chunkZ);

        Map<Structure, LongSet> actual = center.getAllReferences();

        int expectedCount = 0;
        for (Set<Long> refs : expected.values()) {
            expectedCount += refs.size();
        }
        int actualCount = 0;
        for (LongSet refs : actual.values()) {
            actualCount += refs.size();
        }
        eq(expectedCount, actualCount,
                "reference count " + chunkX + "," + chunkZ);

        for (Map.Entry<Structure, Set<Long>> entry : expected.entrySet()) {
            LongSet got = actual.get(entry.getKey());
            ok(got != null,
                    "structure reference set exists " + chunkX + "," + chunkZ);
            for (long source : entry.getValue()) {
                ok(got.contains(source),
                        "expected source reference "
                                + ChunkPos.unpack(source)
                                + " -> "
                                + new ChunkPos(chunkX, chunkZ));
            }
        }

        for (Map.Entry<Structure, LongSet> entry : actual.entrySet()) {
            Set<Long> want = expected.get(entry.getKey());
            ok(want != null,
                    "actual structure is expected "
                            + chunkX + "," + chunkZ);
            for (long source : entry.getValue()) {
                ok(want.contains(source),
                        "no extra source reference "
                                + ChunkPos.unpack(source)
                                + " -> "
                                + new ChunkPos(chunkX, chunkZ));
            }
        }

        System.out.println(
                "STRUCTURE_REFERENCES chunk="
                        + chunkX + "," + chunkZ
                        + " refs=" + actualCount
                        + " structures=" + actual.size());
    }

    private static Map<Structure, Set<Long>> expected(
            HeadlessNoise.Structural structural,
            int targetX,
            int targetZ) {
        Map<Structure, Set<Long>> out = new HashMap<>();
        int minX = targetX << 4;
        int minZ = targetZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        for (int dz = -8; dz <= 8; dz++) {
            for (int dx = -8; dx <= 8; dx++) {
                int sourceX = targetX + dx;
                int sourceZ = targetZ + dz;
                ChunkAccess source =
                        HeadlessNoise.structures(
                                structural, sourceX, sourceZ);
                long sourceKey = ChunkPos.pack(sourceX, sourceZ);

                for (Map.Entry<Structure, StructureStart> entry
                        : source.getAllStarts().entrySet()) {
                    StructureStart start = entry.getValue();
                    if (!start.isValid()) {
                        continue;
                    }
                    if (!start.getBoundingBox().intersects(
                            minX, minZ, maxX, maxZ)) {
                        continue;
                    }
                    out.computeIfAbsent(
                                    entry.getKey(),
                                    ignored -> new HashSet<>())
                            .add(sourceKey);
                }
            }
        }
        return out;
    }

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + " got " + actual);
        }
    }

    private static void eq(int expected, int actual, String label) {
        checks++;
        if (expected != actual) {
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
