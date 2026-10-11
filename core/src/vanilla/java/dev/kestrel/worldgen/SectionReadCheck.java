package dev.kestrel.worldgen;

import dev.kestrel.loop.SectionStore;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * HeadlessNoise.readSectionCells must equal section.getBlockState(x, y, z)
 * for every cell. Generates NOISE+SURFACE chunks (single-value, linear,
 * hashmap and global palettes all occur across the height range) and
 * compares all cells, plus a negative control that a flipped index order
 * is detected. Usage: SectionReadCheck [seed] [chunks]
 */
public final class SectionReadCheck {
    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 4242424242L;
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 16;
        HeadlessNoise.Driver driver = HeadlessNoise.load(seed);
        BlockState[] cells = new BlockState[SectionStore.CELLS];
        int[] ids = new int[SectionStore.CELLS];
        long compared = 0;
        int sections = 0;
        boolean controlCaught = false;
        java.util.Map<Integer, Integer> bitsSeen = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++) {
            int cx = i * 37 - 200;
            int cz = i * -53 + 90;
            ChunkAccess c = HeadlessNoise.surface(driver, HeadlessNoise.noise(driver, cx, cz));
            for (LevelChunkSection section : c.getSections()) {
                HeadlessNoise.readSectionCells(section, cells, ids);
                bitsSeen.merge(section.getStates().bitsPerEntry(), 1, Integer::sum);
                sections++;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            if (cells[SectionStore.index(x, y, z)] != section.getBlockState(x, y, z)) {
                                throw new AssertionError("cell mismatch chunk=" + cx + "," + cz
                                        + " local=" + x + "," + y + "," + z);
                            }
                            if (!controlCaught && x != z
                                    && cells[(y << 8) | (x << 4) | z] != section.getBlockState(x, y, z)) {
                                controlCaught = true;
                            }
                            compared++;
                        }
                    }
                }
            }
        }
        // Generated terrain rarely leaves the single-value and linear
        // palettes, so also fill sections with 3, 20, 200 and 4096 distinct
        // random states (linear, hashmap and global palettes).
        java.util.Random random = new java.util.Random(seed);
        int registrySize = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size();
        for (int distinct : new int[]{3, 20, 200, 4096}) {
            ChunkAccess c = HeadlessNoise.noise(driver, 3000 + distinct, -700);
            LevelChunkSection section = c.getSections()[20];
            BlockState[] pool = new BlockState[distinct];
            for (int k = 0; k < distinct; k++) {
                pool[k] = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(
                        random.nextInt(registrySize));
            }
            for (int i = 0; i < SectionStore.CELLS; i++) {
                section.setBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15,
                        pool[random.nextInt(distinct)], false);
            }
            HeadlessNoise.readSectionCells(section, cells, ids);
            bitsSeen.merge(section.getStates().bitsPerEntry(), 1, Integer::sum);
            sections++;
            for (int i = 0; i < SectionStore.CELLS; i++) {
                if (cells[i] != section.getBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15)) {
                    throw new AssertionError("cell mismatch distinct=" + distinct + " index=" + i);
                }
                compared++;
            }
        }
        if (!controlCaught) {
            throw new AssertionError("negative control: transposed index order was never detected");
        }
        System.out.println("SectionReadCheck: PASSED sections=" + sections + " cells=" + compared
                + " bitsPerEntry=" + bitsSeen);
    }
}
