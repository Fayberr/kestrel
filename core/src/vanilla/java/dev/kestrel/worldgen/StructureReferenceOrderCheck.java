package dev.kestrel.worldgen;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic for the iteration order consumed by
 * StructureManager.startsForStructure(...).
 */
public final class StructureReferenceOrderCheck {
    private StructureReferenceOrderCheck() {}

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 4242424242L;
        int targetX = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int targetZ = args.length > 2 ? Integer.parseInt(args[2]) : 3;

        try (HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(seed)) {
            ChunkAccess target =
                    HeadlessNoise.structureReferences(structural, targetX, targetZ);
            Registry<Structure> registry =
                    structural.driver().access().lookupOrThrow(Registries.STRUCTURE);

            System.out.println("STRUCTURE_REFERENCE_ORDER target="
                    + targetX + "," + targetZ);

            for (Map.Entry<Structure, LongSet> entry
                    : target.getAllReferences().entrySet()) {
                Structure structure = entry.getKey();
                ResourceKey<Structure> key =
                        registry.getResourceKey(structure).orElse(null);
                LongSet refs = entry.getValue();
                System.out.println("  refSetClass=" + refs.getClass().getName()
                        + " size=" + refs.size());
                // Exact SerializableChunkData reference round-trip shape:
                // packStructureData writes refs.toLongArray(), then
                // unpackStructureReferences constructs LongOpenHashSet(long[]).
                LongOpenHashSet roundTripped =
                        new LongOpenHashSet(refs.toLongArray());
                List<String> roundTripOrder = new ArrayList<>();
                System.out.print("  roundTripOrder=");
                boolean firstRoundTrip = true;
                for (long packed : roundTripped) {
                    int px = ChunkPos.getX(packed);
                    int pz = ChunkPos.getZ(packed);
                    roundTripOrder.add(px + "," + pz);
                    if (!firstRoundTrip) System.out.print(";");
                    System.out.print(px + "," + pz + "(" + packed + ")");
                    firstRoundTrip = false;
                }
                System.out.println();
                int index = 0;
                List<String> actualOrder = new ArrayList<>();
                for (long packed : refs) {
                    int sourceX = ChunkPos.getX(packed);
                    int sourceZ = ChunkPos.getZ(packed);
                    ChunkAccess source = HeadlessNoise.structures(
                            structural, sourceX, sourceZ);
                    StructureStart start =
                            source.getAllStarts().get(structure);
                    String sourceText = sourceX + "," + sourceZ;
                    actualOrder.add(sourceText);
                    System.out.println("  key=" + key
                            + " index=" + (++index)
                            + " source=" + sourceText
                            + " packed=" + packed
                            + " valid=" + (start != null && start.isValid())
                            + " box=" + (start == null ? null : start.getBoundingBox()));
                }

                // Regression proof for the observed multi-reference run.
                // Fresh createReferences inserts (0,6) then (3,7), but the
                // default n=32 LongOpenHashSet iterates them as (3,7),(0,6).
                // A real chunk save/load serializes that iterator order and
                // reconstructs a size-derived n=4 set, which iterates the same
                // membership as (0,6),(3,7). That is the captured Vanilla
                // split between fresh and rehydrated chunks.
                if (seed == 4242424242L
                        && targetX == 0
                        && targetZ >= 3
                        && targetZ <= 6
                        && key != null
                        && key.identifier().toString().equals("minecraft:mineshaft")) {
                    List<String> expectedFresh =
                            List.of("3,7", "0,6");
                    List<String> expectedRoundTrip =
                            List.of("0,6", "3,7");
                    if (!actualOrder.equals(expectedFresh)) {
                        throw new AssertionError(
                                "fresh mineshaft reference order mismatch target="
                                        + targetX + "," + targetZ
                                        + ": expected=" + expectedFresh
                                        + " actual=" + actualOrder);
                    }
                    if (!roundTripOrder.equals(expectedRoundTrip)) {
                        throw new AssertionError(
                                "round-tripped mineshaft reference order mismatch target="
                                        + targetX + "," + targetZ
                                        + ": expected=" + expectedRoundTrip
                                        + " actual=" + roundTripOrder);
                    }
                    System.out.println(
                            "  referenceLifecycleRegression=PASS fresh="
                                    + actualOrder
                                    + " roundTrip=" + roundTripOrder);
                }
            }
        }
    }
}
