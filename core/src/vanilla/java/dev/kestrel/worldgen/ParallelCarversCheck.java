package dev.kestrel.worldgen;

import dev.kestrel.loop.SectionStore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exactness check for {@link LiveGenerator#enableParallelCarvers}: the same
 * production job sequence (ensureChunk, then the biome and newly-ready light
 * wire snapshots) runs once inline and once with the carver pool and the
 * worker's lookahead hints, over a spiral of centers. Every block of every
 * touched chunk, every biome wire payload and every light wire payload (in
 * emission order) must be identical, as must the final light wire of every
 * square chunk. The inline run is the path the Tier-R gates verify, so equality
 * carries their verdict over to the pooled run for these chunks.
 *
 * <p>A negative control flips one cell of the pooled store and requires the
 * comparison to report exactly that cell.
 *
 * <p>Usage: ParallelCarversCheck seed centreX centreZ size threads [lookahead]
 */
public final class ParallelCarversCheck {

    private record Run(SectionStore store, Map<Long, byte[]> biomes,
            List<String> lightOrder, Map<Long, byte[]> lights,
            Map<Long, byte[]> finalLight, Set<Long> touched, double seconds) {
    }

    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        int cx = Integer.parseInt(args[1]);
        int cz = Integer.parseInt(args[2]);
        int size = Integer.parseInt(args[3]);
        int threads = Integer.parseInt(args[4]);
        int lookahead = args.length > 5 ? Integer.parseInt(args[5])
                : AsyncChunkGenerator.DEFAULT_MAX_QUEUED;
        int half = size / 2;
        List<int[]> square = GenSpeedBench.spiral(cx, cz, size, half);
        List<int[]> centers = GenSpeedBench.spiral(cx, cz, size + 2, half + 1);

        Run serial = run(seed, square, centers, 0, 0);
        Run pooled = run(seed, square, centers, threads, lookahead);
        System.out.printf("PARALLEL_CARVERS serialS=%.3f pooledS=%.3f threads=%d lookahead=%d biomesFromDonor=%d%n",
                serial.seconds, pooled.seconds, threads, lookahead, HeadlessNoise.BIOMES_FROM_DONOR.get());

        List<String> diffs = new ArrayList<>();
        if (!serial.touched.equals(pooled.touched)) {
            diffs.add("touched chunk sets differ: " + serial.touched.size() + " vs " + pooled.touched.size());
        }
        if (!serial.store.generatedChunkKeys().equals(pooled.store.generatedChunkKeys())) {
            diffs.add("generated chunk sets differ");
        }
        long cells = compareBlocks(serial.store, pooled.store, serial.touched, diffs);
        compareBytes("biome", serial.biomes, pooled.biomes, diffs);
        compareBytes("light", serial.lights, pooled.lights, diffs);
        compareBytes("finalLight", serial.finalLight, pooled.finalLight, diffs);
        if (!serial.lightOrder.equals(pooled.lightOrder)) {
            diffs.add("light wire emission order differs");
        }

        // Negative control: one flipped cell must be found, and only it.
        long any = serial.touched.iterator().next();
        BlockPos probe = new BlockPos(ChunkPos.getX(any) * 16 + 3, 70, ChunkPos.getZ(any) * 16 + 5);
        BlockState before = pooled.store.get(probe);
        pooled.store.writeGenerated(probe,
                before.is(Blocks.GOLD_BLOCK) ? Blocks.DIAMOND_BLOCK.defaultBlockState()
                        : Blocks.GOLD_BLOCK.defaultBlockState());
        List<String> control = new ArrayList<>();
        compareBlocks(serial.store, pooled.store, Set.of(any), control);
        if (control.size() != 1) {
            throw new AssertionError("negative control expected 1 diff, got " + control);
        }

        if (!diffs.isEmpty()) {
            diffs.stream().limit(20).forEach(d -> System.out.println("PARALLEL_CARVERS DIFF " + d));
            System.out.println("PARALLEL_CARVERS: FAILED diffs=" + diffs.size());
            System.exit(1);
        }
        System.out.printf("PARALLEL_CARVERS: PASSED chunks=%d cells=%d biomePayloads=%d lightPayloads=%d"
                        + " finalLight=%d control=detected%n",
                serial.touched.size(), cells, serial.biomes.size(), serial.lights.size(),
                serial.finalLight.size());
    }

    private static Run run(long seed, List<int[]> square, List<int[]> centers,
            int threads, int lookahead) throws Exception {
        HeadlessNoise.Structural structural = HeadlessNoise.loadStructural(seed);
        try (LiveGenerator generator = new LiveGenerator(structural)) {
            generator.enableParallelCarvers(threads);
            SectionStore store = new SectionStore(HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT);
            if (threads > 0) {
                // The inline run keeps full writes, so the pooled run also
                // checks the skip-unchanged-sections path against it.
                generator.setExclusiveStore(store);
            }
            Map<Long, byte[]> biomes = new LinkedHashMap<>();
            Map<Long, byte[]> lights = new LinkedHashMap<>();
            List<String> lightOrder = new ArrayList<>();
            Set<Long> touched = new LinkedHashSet<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < centers.size(); i++) {
                int[] c = centers.get(i);
                if (threads > 0) {
                    generator.prefetchCenters(centers.subList(i, Math.min(centers.size(), i + lookahead)));
                }
                List<int[]> t = generator.ensureChunk(store, c[0], c[1]);
                for (int[] p : t) {
                    touched.add(ChunkPos.pack(p[0], p[1]));
                }
                biomes.putAll(generator.snapshotBiomeWire(t));
                Map<Long, byte[]> l = generator.snapshotNewlyReadyLightWire(store, t);
                for (Map.Entry<Long, byte[]> e : l.entrySet()) {
                    lightOrder.add(i + ":" + e.getKey());
                    lights.put(e.getKey(), e.getValue());
                }
            }
            double seconds = (System.nanoTime() - t0) / 1e9;
            System.out.println("PARALLEL_CARVERS threads=" + threads + " starts="
                    + generator.builtStructureStartCounts());
            Map<Long, byte[]> finalLight = new LinkedHashMap<>();
            for (int[] c : square) {
                finalLight.put(ChunkPos.pack(c[0], c[1]), generator.snapshotLightWire(c[0], c[1]));
            }
            if (threads > 0 && generator.parallelCarverThreads() != threads) {
                throw new AssertionError("carver pool not enabled");
            }
            return new Run(store, biomes, lightOrder, lights, finalLight, touched, seconds);
        }
    }

    private static long compareBlocks(SectionStore a, SectionStore b, Set<Long> chunks, List<String> diffs) {
        long cells = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = a.getMinY();
        int maxY = minY + a.getHeight();
        for (long key : chunks) {
            int bx = ChunkPos.getX(key) * 16;
            int bz = ChunkPos.getZ(key) * 16;
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        pos.set(bx + x, y, bz + z);
                        BlockState sa = a.get(pos);
                        BlockState sb = b.get(pos);
                        cells++;
                        if (sa != sb) {
                            diffs.add("block " + pos.toShortString() + " serial=" + sa + " pooled=" + sb);
                        }
                    }
                }
            }
        }
        return cells;
    }

    private static void compareBytes(String what, Map<Long, byte[]> a, Map<Long, byte[]> b, List<String> diffs) {
        if (!a.keySet().equals(b.keySet())) {
            diffs.add(what + " key sets differ: " + a.size() + " vs " + b.size());
            return;
        }
        for (Map.Entry<Long, byte[]> e : a.entrySet()) {
            if (!Arrays.equals(e.getValue(), b.get(e.getKey()))) {
                diffs.add(what + " payload differs at " + e.getKey());
            }
        }
    }
}
