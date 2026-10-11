package dev.kestrel.harness;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.SectionStore;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.persist.BlockHash;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.LiveGenerator;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Parity gate for releasing the memoized NoiseChunk after CARVERS
 * (HeadlessNoise.releaseNoiseChunk, wired in LiveGenerator.ensureCarvers).
 *
 * <p>Generates a square through the live path exactly as {@code --generate}
 * does and prints a blocks-only FNV hash over every generated chunk plus the
 * store's section count. The claim under test is that dropping the NoiseChunk
 * memo is generation scratch only, so this hash must be identical with the
 * release wired in and with it disabled.
 *
 * <p>Run with {@code -Dkestrel.noiseparity.half=N} (default 2, a 5x5 square).
 */
public final class NoiseChunkParity {

    public static void main(String[] args) throws Exception {
        int half = Integer.getInteger("kestrel.noiseparity.half", 2);
        long seed = Long.getLong("kestrel.noiseparity.seed", 4242424242L);

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        HeadlessNoise.Driver driver = HeadlessNoise.load(seed);
        LiveGenerator gen = new LiveGenerator(driver);
        SectionStore store = loop.store();

        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                gen.ensureChunk(store, dx, dz);
            }
        }

        // Fold the per-chunk hashes in a fixed order so the digest is
        // order-independent of HashMap iteration.
        long acc = 0xcbf29ce484222325L;
        int chunks = 0;
        StringBuilder per = new StringBuilder();
        for (int cx = -half; cx <= half; cx++) {
            for (int cz = -half; cz <= half; cz++) {
                long h = BlockHash.chunk(store, cx, cz);
                acc ^= h;
                acc *= 0x100000001b3L;
                chunks++;
                if (chunks <= 5) {
                    per.append(' ').append(BlockHash.chunkHex(store, cx, cz));
                }
            }
        }
        System.out.printf("NOISEPARITY half=%d chunks=%d sections=%d compact=%d dense=%d "
                        + "worldHash=%016x first5=%s%n",
                half, chunks, store.sectionCount(), store.compactSectionCount(),
                store.denseSectionCount(), acc, per.toString().trim());
    }
}
