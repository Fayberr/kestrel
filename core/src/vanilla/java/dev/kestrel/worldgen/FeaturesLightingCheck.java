package dev.kestrel.worldgen;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Regression for the exact Vanilla 26.2 FEATURES-lighting storage boundary:
 * an unregistered sky column reads 15, while INITIALIZE_LIGHT on a neighboring
 * non-empty section registers a light-only DataLayer that reads 0 before the
 * LIGHT propagation stage.
 */
public final class FeaturesLightingCheck {
    private static int checks;

    private FeaturesLightingCheck() {
    }

    public static void main(String[] args) throws Exception {
        HeadlessNoise.Driver driver = HeadlessNoise.load(3527749958874813416L);

        Map<Long, ChunkAccess> grid = new HashMap<>();
        ProtoChunk source = (ProtoChunk) HeadlessNoise.noise(driver, 0, 0);
        ProtoChunk neighbor = (ProtoChunk) HeadlessNoise.noise(driver, 1, 0);
        source.setPersistedStatus(ChunkStatus.FEATURES);
        neighbor.setPersistedStatus(ChunkStatus.CARVERS);
        grid.put(ChunkPos.pack(source.getPos().x(), source.getPos().z()), source);
        grid.put(ChunkPos.pack(neighbor.getPos().x(), neighbor.getPos().z()), neighbor);

        WorldgenLightLifecycle lighting = new WorldgenLightLifecycle(grid, driver);

        // Section 0 (Y 0..15) is safely below normal overworld terrain in the
        // chosen seed/chunks, so source has real block data there.
        BlockPos neighborPos = new BlockPos(16, 0, 0);
        SectionPos neighborSection = SectionPos.of(new ChunkPos(1, 0), 0);

        eq(15, lighting.engine().getRawBrightness(neighborPos, 0),
                "unregistered column defaults to skylight 15");
        isNull(lighting.engine().getLayerListener(LightLayer.SKY)
                        .getDataLayerData(neighborSection),
                "unregistered neighbor has no visible sky DataLayer");

        lighting.initialize(source);

        notNull(lighting.engine().getLayerListener(LightLayer.SKY)
                        .getDataLayerData(neighborSection),
                "neighbor section registered by source INITIALIZE_LIGHT");
        eq(0, lighting.engine().getRawBrightness(neighborPos, 0),
                "registered light-only neighbor reads dark before LIGHT propagation");
        eq(ChunkStatus.CARVERS.getIndex(), neighbor.getPersistedStatus().getIndex(),
                "neighbor lifecycle is not artificially advanced");

        lighting.light(source);
        ok(source.isLightCorrect(), "source marked light-correct after LIGHT");
        ok(lighting.engine().getLayerListener(LightLayer.SKY)
                        .getDataLayerData(neighborSection) != null,
                "neighbor DataLayer survives source LIGHT propagation");

        System.out.println("FeaturesLightingCheck: PASSED (" + checks + " checks)");
    }

    private static void eq(int expected, int actual, String label) {
        checks++;
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private static void isNull(Object value, String label) {
        checks++;
        if (value != null) {
            throw new AssertionError(label + ": expected null, got " + value);
        }
    }

    private static void notNull(Object value, String label) {
        checks++;
        if (value == null) {
            throw new AssertionError(label + ": expected non-null");
        }
    }
}
