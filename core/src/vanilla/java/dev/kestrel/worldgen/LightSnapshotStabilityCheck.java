package dev.kestrel.worldgen;

import dev.kestrel.loop.SectionStore;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

/**
 * Regression for the Step-9 light handoff: a later adjacent LIGHT stage can
 * change an already-lit center. snapshotNewlyReadyLightWire must therefore
 * return a refreshed center payload when a neighbour transitions.
 */
public final class LightSnapshotStabilityCheck {
    private LightSnapshotStabilityCheck() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        try (LiveGenerator generator = new LiveGenerator(HeadlessNoise.load(4242424242L))) {
            SectionStore store = new SectionStore(-64, 384);
            long centerKey = ChunkPos.pack(0, 0);

            // Build a 5x5 INITIALIZE_LIGHT closure first. Advance only center.
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    generator.ensureChunk(store, x, z);
                }
            }

            Map<Long, byte[]> firstUpdate =
                    generator.snapshotNewlyReadyLightWire(store, List.of(new int[]{0, 0}));
            byte[] first = firstUpdate.get(centerKey);
            expect(first != null, "center enters LIGHT and gets an initial payload");

            boolean centerRepublished = false;
            byte[] latestFromUpdates = first;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) {
                        continue;
                    }
                    Map<Long, byte[]> update =
                            generator.snapshotNewlyReadyLightWire(
                                    store, List.of(new int[]{x, z}));
                    byte[] refreshed = update.get(centerKey);
                    if (refreshed != null) {
                        centerRepublished = true;
                        latestFromUpdates = refreshed;
                    }
                }
            }

            byte[] current = generator.snapshotLightWire(0, 0);
            expect(centerRepublished,
                    "adjacent LIGHT stages reserialize the already-lit center");
            expect(current != null && Arrays.equals(latestFromUpdates, current),
                    "last republished center payload equals current Vanilla engine state");
            expect(!Arrays.equals(first, current),
                    "fixture proves neighbour LIGHT actually changes center payload");

            System.out.println("LightSnapshotStabilityCheck: PASS first="
                    + first.length + " current=" + current.length);
        }
    }

    private static void expect(boolean condition, String message) {
        System.out.println((condition ? "[light-stability] OK: " : "[light-stability] FAIL: ")
                + message);
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
