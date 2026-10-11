package dev.kestrel.worldgen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Diagnostic-only: print the stored light value, layer presence and storage
 * flags for an explicit list of cells at every drain iteration, across every
 * batch, so the timeline of a specific stale cell can be reconstructed.
 *
 * <p>Inert unless {@code -Dkestrel.probeCells=x,y,z;x,y,z;...} is set.
 */
final class CellProbe {
    private static boolean initialized;
    private static List<long[]> cells;

    private CellProbe() {
    }

    private static synchronized void init() {
        initialized = true;
        String spec = System.getProperty("kestrel.probeCells");
        cells = new ArrayList<>();
        if (spec == null || spec.isBlank()) return;
        for (String cell : spec.split(";")) {
            String[] parts = cell.trim().split(",");
            if (parts.length != 3) continue;
            cells.add(new long[]{
                    Long.parseLong(parts[0].trim()),
                    Long.parseLong(parts[1].trim()),
                    Long.parseLong(parts[2].trim())
            });
        }
    }

    /**
     * Diagnostic-only: reflect into the private {@code blockEngine}/
     * {@code skyEngine} fields on {@code LevelLightEngine}, then into the
     * private {@code blockNodesToCheck} set on {@code LightEngine}, to answer
     * whether a given cell is actually queued for {@code checkNode} before a
     * drain, independent of the storage flags.
     */
    static void dumpQueued(String tag, LevelLightEngine engine) {
        if (!initialized) init();
        if (cells.isEmpty()) return;
        try {
            java.lang.reflect.Field blockField = LevelLightEngine.class.getDeclaredField("blockEngine");
            blockField.setAccessible(true);
            java.lang.reflect.Field skyField = LevelLightEngine.class.getDeclaredField("skyEngine");
            skyField.setAccessible(true);
            Object blockEngine = blockField.get(engine);
            Object skyEngine = skyField.get(engine);
            for (long[] cell : cells) {
                long node = BlockPos.asLong((int) cell[0], (int) cell[1], (int) cell[2]);
                System.out.println("QUEUED " + tag + " @" + cell[0] + "," + cell[1] + "," + cell[2]
                        + " blockQueued=" + isQueued(blockEngine, node)
                        + " skyQueued=" + isQueued(skyEngine, node));
            }
        } catch (ReflectiveOperationException e) {
            System.out.println("QUEUED " + tag + " reflection failed: " + e);
        }
    }

    private static boolean isQueued(Object lightEngine, long node) {
        if (lightEngine == null) return false;
        try {
            for (Class<?> c = lightEngine.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = c.getDeclaredField("blockNodesToCheck");
                    field.setAccessible(true);
                    Object set = field.get(lightEngine);
                    java.lang.reflect.Method contains = set.getClass().getMethod("contains", long.class);
                    return (boolean) contains.invoke(set, node);
                } catch (NoSuchFieldException ignored) {
                    // keep walking up to LightEngine
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Diagnostic only.
        }
        return false;
    }

    static void dump(String tag, LevelLightEngine engine) {
        if (!initialized) init();
        if (cells.isEmpty()) return;
        var block = engine.getLayerListener(LightLayer.BLOCK);
        var sky = engine.getLayerListener(LightLayer.SKY);
        for (long[] cell : cells) {
            int x = (int) cell[0];
            int y = (int) cell[1];
            int z = (int) cell[2];
            BlockPos pos = new BlockPos(x, y, z);
            SectionPos section = SectionPos.of(pos);
            long sectionNode = section.asLong();
            System.out.println("CELL " + tag + " @" + x + "," + y + "," + z
                    + " block=" + block.getLightValue(pos)
                    + " sky=" + sky.getLightValue(pos)
                    + " blockLayer=" + (block.getDataLayerData(section) != null)
                    + " skyLayer=" + (sky.getDataLayerData(section) != null)
                    + " blockStoring=" + FeaturesTraceReplay.storingLightForSection(block, sectionNode)
                    + " blockLightOn=" + FeaturesTraceReplay.lightOnInSection(block, sectionNode)
                    + " skyStoring=" + FeaturesTraceReplay.storingLightForSection(sky, sectionNode)
                    + " skyLightOn=" + FeaturesTraceReplay.lightOnInSection(sky, sectionNode));
        }
    }
}
