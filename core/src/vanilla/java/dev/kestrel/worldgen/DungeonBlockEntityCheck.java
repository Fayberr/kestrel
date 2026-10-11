package dev.kestrel.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Regression check for the RegionView.getBlockEntity fix: before the fix,
 * WorldGenRegion.getBlockEntity never promoted a just-placed dungeon
 * spawner/chest's DUMMY nbt to a real BlockEntity, so vanilla's
 * RNG-consuming block-entity configuration (spawner entity-id roll, chest
 * loot-table seed roll) was silently skipped, desynchronizing the
 * WorldgenRandom stream for the rest of that dungeon's own placement and
 * producing wrong downstream geometry/inventory decisions.
 *
 * <p>This check is fully self-contained (builds its own radius-1 chunk grid
 * from scratch via the same noise/surfaceVanilla/carveVanilla pipeline
 * FeaturesCheck uses, then runs FEATURES only on the center chunk -
 * FeaturesCheck's "center only" variant) so it has no dependency on the
 * ephemeral /tmp trace capture. It targets two real dungeons found in the
 * seed 3527749958874813416 Tier R trace and asserts the exact vanilla
 * ground-truth block states confirmed against the trace's COMPLETE
 * snapshots:
 *
 * <ul>
 *   <li>Chunk (-13,-13): a dungeon whose spawner sits at (-202,-14,-196).
 *   Before the fix, Kestrel's RNG desync caused an unauthorized second
 *   "room" to be carved nearby; this asserts (-205,-14,-200) is undisturbed
 *   deepslate (not a spawner/room) and three cave_air control cells are
 *   still cave_air.</li>
 *   <li>Chunk (18,-14): a dungeon whose spawner sits at (292,-53,-210) with
 *   two chests at (294,-53,-210) [north] and (295,-53,-210) [west]. Before
 *   the fix, the desync shifted a chest to (289,-53,-209); this asserts
 *   that cell is cave_air (no shifted chest) and the two real chests are at
 *   their correct positions with correct facings.</li>
 * </ul>
 *
 * <p>Both dungeons' spawner positions are additionally asserted to resolve
 * to a real (non-null) BlockEntity via the same RegionView.getBlockEntity
 * path the feature placement itself used, proving the fix's lazy-promotion
 * path actually executed rather than the assertions coincidentally passing
 * on block state alone.
 */
public final class DungeonBlockEntityCheck {

    private static final long SEED = 3527749958874813416L;

    private static boolean failed = false;

    public static void main(String[] args) throws Exception {
        HeadlessNoise.Driver driver = HeadlessNoise.load(SEED);

        // --- Dungeon 1: geometry-class (chunk -13,-13) ---
        {
            int cx = -13, cz = -13;
            Map<Long, ChunkAccess> grid = buildAndDecorateCenterOnly(driver, cx, cz);
            ChunkAccess chunk = grid.get(ChunkPos.pack(cx, cz));
            WorldGenLevel level = new HeadlessNoise.RegionView(driver, new ChunkPos(cx, cz), grid).level();

            expectBlock("geometry dungeon spawner", chunk, new BlockPos(-202, -14, -196), "minecraft:spawner");
            expectBlockEntity("geometry dungeon spawner block entity", level, new BlockPos(-202, -14, -196));
            expectBlock("geometry dungeon: no 2nd room spawner", chunk, new BlockPos(-205, -14, -200),
                    "minecraft:deepslate");
            expectBlock("geometry dungeon: no 2nd room spawner (2)", chunk, new BlockPos(-206, -14, -201),
                    "minecraft:deepslate");
            expectBlock("geometry dungeon cave_air control 1", chunk, new BlockPos(-201, -14, -195),
                    "minecraft:cave_air");
            expectBlock("geometry dungeon cave_air control 2", chunk, new BlockPos(-203, -14, -197),
                    "minecraft:cave_air");
            expectBlock("geometry dungeon cave_air control 3", chunk, new BlockPos(-204, -14, -198),
                    "minecraft:cave_air");
        }

        // --- Dungeon 2: chest-shift-class (chunk 18,-14) ---
        {
            int cx = 18, cz = -14;
            Map<Long, ChunkAccess> grid = buildAndDecorateCenterOnly(driver, cx, cz);
            ChunkAccess chunk = grid.get(ChunkPos.pack(cx, cz));
            WorldGenLevel level = new HeadlessNoise.RegionView(driver, new ChunkPos(cx, cz), grid).level();

            expectBlock("chest dungeon spawner", chunk, new BlockPos(292, -53, -210), "minecraft:spawner");
            expectBlockEntity("chest dungeon spawner block entity", level, new BlockPos(292, -53, -210));
            expectBlock("chest dungeon chest (north)", chunk, new BlockPos(294, -53, -210), "minecraft:chest");
            expectProperty("chest dungeon chest (north) facing", chunk, new BlockPos(294, -53, -210),
                    "facing", "north");
            expectBlock("chest dungeon chest (west)", chunk, new BlockPos(295, -53, -210), "minecraft:chest");
            expectProperty("chest dungeon chest (west) facing", chunk, new BlockPos(295, -53, -210),
                    "facing", "west");
            expectBlock("chest dungeon: no shifted chest artifact", chunk, new BlockPos(289, -53, -209),
                    "minecraft:cave_air");
        }

        if (failed) {
            System.out.println("DungeonBlockEntityCheck: FAILED");
            System.exit(1);
        }
        System.out.println("DungeonBlockEntityCheck: PASSED");
    }

    /**
     * FeaturesCheck's "center only" pattern: build a from-scratch radius-1
     * (3x3) chunk grid through noise/surfaceVanilla/carveVanilla, then run
     * FEATURES on only the center chunk (neighbours stay at CARVERS). This
     * isolates the dungeon's own decoration call without depending on any
     * captured trace state.
     */
    private static Map<Long, ChunkAccess> buildAndDecorateCenterOnly(HeadlessNoise.Driver driver, int cx, int cz) {
        Map<Long, ChunkAccess> out = new LinkedHashMap<>();
        Map<net.minecraft.world.level.ChunkPos, ChunkAccess> noiseMargin = new HashMap<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                Map<ChunkPos, ChunkAccess> biomeGrid = HeadlessNoise.prepareBiomeGrid(driver, x, z,
                        (bx, bz) -> out.get(ChunkPos.pack(bx, bz)), noiseMargin);
                ChunkAccess c = biomeGrid.get(new ChunkPos(x, z));
                HeadlessNoise.surfaceVanilla(driver, c, biomeGrid);
                HeadlessNoise.carveVanilla(driver, c);
                ((ProtoChunk) c).setPersistedStatus(ChunkStatus.CARVERS);
                HeadlessNoise.releaseNoiseChunk(c);
                out.put(ChunkPos.pack(x, z), c);
                noiseMargin.remove(new ChunkPos(x, z));
            }
        }
        ChunkPos center = new ChunkPos(cx, cz);
        ChunkAccess centerChunk = out.get(center.pack());
        HeadlessNoise.RegionView view = new HeadlessNoise.RegionView(driver, center, out);
        HeadlessNoise.features(driver, centerChunk, view);
        ((ProtoChunk) centerChunk).setPersistedStatus(ChunkStatus.FEATURES);
        return out;
    }

    private static void expectBlock(String label, ChunkAccess chunk, BlockPos pos, String expectedId) {
        BlockState state = chunk.getBlockState(pos);
        String actualId = state.getBlock().builtInRegistryHolder().key().identifier().toString();
        if (!actualId.equals(expectedId)) {
            failed = true;
            System.out.println("FAIL " + label + " at " + pos + ": expected " + expectedId
                    + " got " + actualId + " (" + state + ")");
        } else {
            System.out.println("ok   " + label + " at " + pos + ": " + actualId);
        }
    }

    private static void expectProperty(String label, ChunkAccess chunk, BlockPos pos, String propName,
            String expectedValue) {
        BlockState state = chunk.getBlockState(pos);
        for (var prop : state.getProperties()) {
            if (prop.getName().equals(propName)) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                String actual = ((Enum) state.getValue(prop)).name().toLowerCase(java.util.Locale.ROOT);
                if (!actual.equals(expectedValue)) {
                    failed = true;
                    System.out.println("FAIL " + label + " at " + pos + ": expected " + propName + "="
                            + expectedValue + " got " + actual);
                } else {
                    System.out.println("ok   " + label + " at " + pos + ": " + propName + "=" + actual);
                }
                return;
            }
        }
        failed = true;
        System.out.println("FAIL " + label + " at " + pos + ": no property named " + propName
                + " on " + state);
    }

    private static void expectBlockEntity(String label, WorldGenLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            failed = true;
            System.out.println("FAIL " + label + " at " + pos + ": getBlockEntity returned null"
                    + " (fix not exercising real BlockEntity lazy-promotion path)");
        } else {
            System.out.println("ok   " + label + " at " + pos + ": " + be.getClass().getName());
        }
    }
}
