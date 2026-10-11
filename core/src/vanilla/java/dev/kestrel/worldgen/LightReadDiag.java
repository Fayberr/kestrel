package dev.kestrel.worldgen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Diagnostic-only sink for {@code WorldGenLevel.getRawBrightness} reads.
 *
 * <p>Mushroom survival is light-dependent ({@code MushroomBlock.canSurvive}
 * requires {@code getRawBrightness(pos, 0) < 13}), so a single wrong brightness
 * read flips a scatter placement. This records the reads, the value returned,
 * and whether the section's sky DataLayer exists at all, which is what
 * separates "unregistered column, 15" from "registered light-only layer, 0".
 *
 * <p>Enabled with {@code -Dkestrel.lightReadDump=<path>}, optionally narrowed
 * by {@code -Dkestrel.lightReadChunkX=<cx> -Dkestrel.lightReadChunkZ=<cz>} and
 * {@code -Dkestrel.lightReadYMin=<y> -Dkestrel.lightReadYMax=<y>}. Inert
 * otherwise, so it can stay in the tree without changing a normal run.
 */
final class LightReadDiag {

    private static boolean initialized;
    private static BufferedWriter out;
    private static int chunkX = Integer.MIN_VALUE;
    private static int chunkZ = Integer.MIN_VALUE;
    private static int minY = Integer.MIN_VALUE;
    private static int maxY = Integer.MAX_VALUE;
    private static int written;

    private LightReadDiag() {
    }

    private static synchronized void init() {
        initialized = true;
        String path = System.getProperty("kestrel.lightReadDump");
        if (path == null || path.isBlank()) {
            return;
        }
        chunkX = Integer.getInteger("kestrel.lightReadChunkX", Integer.MIN_VALUE);
        chunkZ = Integer.getInteger("kestrel.lightReadChunkZ", Integer.MIN_VALUE);
        minY = Integer.getInteger("kestrel.lightReadYMin", Integer.MIN_VALUE);
        maxY = Integer.getInteger("kestrel.lightReadYMax", Integer.MAX_VALUE);
        try {
            out = Files.newBufferedWriter(Path.of(path), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Diagnostic only: a failed close must not mask a verdict.
                }
            }));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Diagnostic-only: one light-storage observation, not filtered by position
     * so the operation stream around a watched read stays complete. Used to
     * attribute a sky-layer creation to the exact lifecycle step that caused
     * it, which is invisible from the read alone.
     */
    static void op(String tag, LevelLightEngine engine, int sectionX, int sectionY,
            int sectionZ) {
        if (!initialized) {
            init();
        }
        if (out == null) {
            return;
        }
        try {
            // SectionPos.of(int,int,int) takes section coordinates directly, so
            // the caller's chunk x/z and section y are already the right triple.
            SectionPos section = SectionPos.of(sectionX, sectionY, sectionZ);
            var layer = engine.getLayerListener(LightLayer.SKY);
            var data = layer == null ? null : layer.getDataLayerData(section);
            out.write("OP " + tag + " @" + Integer.toHexString(System.identityHashCode(engine))
                    + " " + sectionX + "," + sectionY + "," + sectionZ
                    + " skyLayer=" + (data == null ? "ABSENT"
                            : (data.isEmpty() ? "EMPTY"
                                    : "present(" + data.get(section.minBlockX(),
                                            section.minBlockY(), section.minBlockZ()) + ")"))
                    + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int probeChunkX() {
        return Integer.getInteger("kestrel.lightReadChunkX", 0);
    }

    static int probeChunkZ() {
        return Integer.getInteger("kestrel.lightReadChunkZ", 0);
    }

    static int probeSectionY() {
        return Integer.getInteger("kestrel.lightProbeSectionY", 4);
    }

    /** True for positions inside the configured watch window. */
    static boolean watching(BlockPos pos) {
        if (!initialized) {
            init();
        }
        return out != null
                && chunkX != Integer.MIN_VALUE
                && pos.getX() >> 4 == chunkX
                && pos.getZ() >> 4 == chunkZ
                && pos.getY() >= minY
                && pos.getY() <= maxY;
    }

    static void raw(LevelLightEngine engine, BlockPos pos, int amount, int value) {
        if (!initialized) {
            init();
        }
        if (out == null) {
            return;
        }
        if (chunkX != Integer.MIN_VALUE
                && (pos.getX() >> 4 != chunkX || pos.getZ() >> 4 != chunkZ)) {
            return;
        }
        if (pos.getY() < minY || pos.getY() > maxY) {
            return;
        }
        try {
            SectionPos section = SectionPos.of(pos);
            var layer = engine.getLayerListener(LightLayer.SKY);
            var data = layer == null ? null : layer.getDataLayerData(section);
            out.write(pos.getX() + "\t" + pos.getY() + "\t" + pos.getZ()
                    + "\tamount=" + amount + "\tvalue=" + value
                    + "\tskyLayer=" + (data == null ? "ABSENT"
                            : (data.isEmpty() ? "EMPTY"
                                    : "present(" + data.get(section.minBlockX(),
                                            section.minBlockY(), section.minBlockZ()) + ")"))
                    + "\n");
            written++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
