package dev.kestrel.worldgen;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;

/**
 * Live-generator regression for the Vanilla 26.2 FEATURES -> INITIALIZE_LIGHT
 * frontier. This is intentionally separate from the trace replay: it proves
 * the actual on-demand generator carries one shared light-storage state from
 * one generated center into later neighboring FEATURES calls.
 */
public final class LiveFeaturesLightingCheck {
    private static int checks;

    private LiveFeaturesLightingCheck() {
    }

    public static void main(String[] args) throws Exception {
        long seed = 3527749958874813416L;
        HeadlessNoise.Driver driver = HeadlessNoise.load(seed);
        LiveGenerator gen = new LiveGenerator(driver);

        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);

        BlockPos neighborPos = new BlockPos(16, 0, 0);
        SectionPos neighborSection = SectionPos.of(new ChunkPos(1, 0), 0);

        eq(15, gen.lightingEngine().getRawBrightness(neighborPos, 0),
                "fresh unregistered neighbor defaults to sky 15");
        isNull(gen.lightingEngine().getLayerListener(LightLayer.SKY)
                        .getDataLayerData(neighborSection),
                "fresh neighbor has no visible sky DataLayer");

        gen.ensureChunk(loop.store(), 0, 0);

        eq(1, gen.centersCount(), "first center completed FEATURES");
        eq(1, gen.initializedLightCount(), "first center completed INITIALIZE_LIGHT");
        notNull(gen.lightingEngine().getLayerListener(LightLayer.SKY)
                        .getDataLayerData(neighborSection),
                "first center registers adjacent light-only section");
        eq(0, gen.lightingEngine().getRawBrightness(neighborPos, 0),
                "registered adjacent section reads dark before LIGHT propagation");

        gen.ensureChunk(loop.store(), 0, 0);
        eq(1, gen.centersCount(), "same center is not regenerated");
        eq(1, gen.initializedLightCount(), "same center is not reinitialized");

        gen.ensureChunk(loop.store(), 1, 0);
        eq(2, gen.centersCount(), "adjacent center completed FEATURES");
        eq(2, gen.initializedLightCount(), "adjacent center completed INITIALIZE_LIGHT");

        System.out.println("LiveFeaturesLightingCheck: PASSED (" + checks + " checks)");
    }

    private static void eq(int expected, int actual, String label) {
        checks++;
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
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
