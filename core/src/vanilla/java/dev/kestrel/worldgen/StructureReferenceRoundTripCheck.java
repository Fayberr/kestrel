package dev.kestrel.worldgen;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Proves the concrete fastutil lifecycle mechanism behind Q9 without changing
 * production ordering: a Vanilla-style structure-reference serialize/reload
 * round-trip may change LongOpenHashSet iteration order while preserving the
 * exact reference membership.
 */
public final class StructureReferenceRoundTripCheck {
    private StructureReferenceRoundTripCheck() {}

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 4242424242L;
        int targetX = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int targetZ = args.length > 2 ? Integer.parseInt(args[2]) : 3;

        try (HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(seed)) {
            ChunkAccess target =
                    HeadlessNoise.structureReferences(structural, targetX, targetZ);
            Registry<Structure> registry =
                    structural.driver().access().lookupOrThrow(Registries.STRUCTURE);

            Structure mineshaft = null;
            ResourceKey<Structure> mineshaftKey = null;
            for (Map.Entry<Structure, LongSet> entry
                    : target.getAllReferences().entrySet()) {
                ResourceKey<Structure> key =
                        registry.getResourceKey(entry.getKey()).orElse(null);
                if (key != null
                        && key.identifier().toString().equals("minecraft:mineshaft")
                        && entry.getValue().size() > 1) {
                    mineshaft = entry.getKey();
                    mineshaftKey = key;
                    break;
                }
            }
            if (mineshaft == null) {
                throw new AssertionError(
                        "no multi-reference mineshaft at target="
                                + targetX + "," + targetZ);
            }

            List<Long> beforeMembership =
                    sorted(target.getReferencesForStructure(mineshaft));
            List<String> beforeOrder =
                    order(target.getReferencesForStructure(mineshaft));

            HeadlessNoise.roundTripStructureReferences(target);

            List<Long> afterMembership =
                    sorted(target.getReferencesForStructure(mineshaft));
            List<String> afterOrder =
                    order(target.getReferencesForStructure(mineshaft));

            System.out.println(
                    "STRUCTURE_REFERENCE_ROUNDTRIP target="
                            + targetX + "," + targetZ
                            + " key=" + mineshaftKey);
            System.out.println("  before=" + beforeOrder);
            System.out.println("  after=" + afterOrder);

            if (!beforeMembership.equals(afterMembership)) {
                throw new AssertionError(
                        "reference membership changed across roundtrip: before="
                                + beforeMembership + " after=" + afterMembership);
            }
            if (beforeOrder.equals(afterOrder)) {
                throw new AssertionError(
                        "reference iteration order did not change across roundtrip: "
                                + beforeOrder);
            }

            if (seed == 4242424242L && targetX == 0 && targetZ == 3) {
                // Fresh createReferences inserts (0,6) then (3,7), but
                // LongOpenHashSet's default n=32 table places them at slots
                // 0 and 16 and iterates high-to-low: (3,7),(0,6). The
                // serializer writes exactly that iterator order. Reloading
                // the two-long array creates n=4; both hash to slot 0, so
                // insertion puts (3,7) at 0 and (0,6) at 1, making the
                // high-to-low iterator return (0,6),(3,7).
                List<String> expectedBefore = List.of("3,7", "0,6");
                List<String> expectedAfter = List.of("0,6", "3,7");
                if (!beforeOrder.equals(expectedBefore)
                        || !afterOrder.equals(expectedAfter)) {
                    throw new AssertionError(
                            "known Q9 witness changed: before="
                                    + beforeOrder + " after=" + afterOrder
                                    + " expectedBefore=" + expectedBefore
                                    + " expectedAfter=" + expectedAfter);
                }
            }

            System.out.println(
                    "StructureReferenceRoundTripCheck: PASSED"
                            + " membershipPreserved=true orderChanged=true");
        }
    }

    private static List<String> order(LongSet refs) {
        List<String> out = new ArrayList<>();
        for (long packed : refs) {
            int x = (int) packed;
            int z = (int) (packed >> 32);
            out.add(x + "," + z);
        }
        return out;
    }

    private static List<Long> sorted(LongSet refs) {
        List<Long> out = new ArrayList<>();
        for (long packed : refs) out.add(packed);
        out.sort(Long::compare);
        return out;
    }
}
