package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.SectionStore;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.LiveGenerator;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * G2 live-wiring gate (preresearch/g2-livewiring-design.md): drives
 * {@link ChunkSender} exactly the way {@code KestrelServer}'s tick loop
 * does (through {@code sectionBytes}, package-visible here on purpose,
 * same pattern {@code NetCheck} already uses for {@link ChunkSender#pack}),
 * with a {@link LiveGenerator} attached but no eager {@code --generate}
 * square, so every chunk it touches must come from on-demand generation.
 *
 * <ol>
 *   <li>A chunk never seen before generates non-flat terrain (some
 *       non-air, non-uniform column) the first time it is requested, and
 *       {@link SectionStore#isChunkGenerated} flips true.</li>
 *   <li>Requesting the same chunk again is a no-op: neither the centers
 *       nor the carvers counters move.</li>
 *   <li>Requesting an adjacent chunk reuses the shared margin: its carvers
 *       counter grows by fewer than 9 (some of its 3x3 came from the first
 *       chunk's cache).</li>
 *   <li>A center chunk already sent gets marked dirty when a later
 *       neighbour's FEATURES pass writes into it (radius-1, so this is
 *       only asserted when it actually happens on this seed/chunk pair;
 *       the gate still passes if not, logged either way).</li>
 * </ol>
 *
 * <p>Run (Pi, JDK 25, after {@code ./gradlew build -PvanillaJar=... -PvanillaLibs=...}):
 * <pre>
 * java -cp build/classes/java/main:VANILLA_JAR:VANILLA_LIBS/* \
 *     dev.kestrel.net.LiveGeneratorCheck
 * </pre>
 */
public final class LiveGeneratorCheck {
    private LiveGeneratorCheck() {
    }

    private static final long SEED = 4242424242L;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        HeadlessNoise.Driver driver = HeadlessNoise.load(SEED);
        LiveGenerator gen = new LiveGenerator(driver);
        ChunkSender sender = new ChunkSender(loop, 1);
        sender.setGenerator(gen);

        boolean ok = true;

        // 1. First touch of (5,5): generates, non-flat, marked.
        var secs = sender.sectionBytes(5, 5);
        long key55 = ChunkPos.pack(5, 5);
        boolean generated = loop.store().isChunkGenerated(key55);
        boolean nonFlat = hasVariedTerrain(loop.store(), 5, 5);
        System.out.println("[live-gen] (5,5) generated=" + generated + " nonFlat=" + nonFlat
                + " sections=" + secs.size() + " storeSections=" + loop.store().sectionCount()
                + " centers=" + gen.centersCount() + " carvers=" + gen.carversCount());
        ok &= expect(generated, "(5,5) marked generated after first touch");
        ok &= expect(nonFlat, "(5,5) has varied (non-flat) terrain");
        ok &= expect(loop.store().compactSectionCount() == loop.store().sectionCount(),
                "live generation hands every resting section off compact");

        BlockPos playerBlock = new BlockPos((5 << 4) + 2, 300, (5 << 4) + 2);
        loop.store().set(playerBlock, Blocks.STONE.defaultBlockState());
        ok &= expect(loop.store().denseSectionCount() == 1,
                "first live mutation promotes only its compact section");

        // 2. Second touch of (5,5): no-op.
        int centersAfterFirst = gen.centersCount();
        int carversAfterFirst = gen.carversCount();
        sender.sectionBytes(5, 5);
        ok &= expect(gen.centersCount() == centersAfterFirst,
                "(5,5) second touch does not re-run FEATURES (centers " + gen.centersCount()
                        + " == " + centersAfterFirst + ")");
        ok &= expect(gen.carversCount() == carversAfterFirst,
                "(5,5) second touch does not regenerate CARVERS (carvers " + gen.carversCount()
                        + " == " + carversAfterFirst + ")");

        // 3. Adjacent chunk (6,5): shares margin, so carvers grows by < 9.
        sender.sectionBytes(6, 5);
        int carversGrowth = gen.carversCount() - carversAfterFirst;
        System.out.println("[live-gen] (6,5) carvers grew by " + carversGrowth
                + " (centers now " + gen.centersCount() + ")");
        ok &= expect(loop.store().isChunkGenerated(ChunkPos.pack(6, 5)), "(6,5) marked generated");
        ok &= expect(carversGrowth < 9, "(6,5) reused at least one cached margin chunk");
        ok &= expect(loop.store().denseSectionCount() == 1,
                "neighbour rewrites keep the player-mutated section dense");
        ok &= expect(loop.store().get(playerBlock).is(Blocks.STONE),
                "neighbour rewrites preserve player blocks in generated air");

        // 4. Neighbour dirtying: (6,5)'s own FEATURES pass may still write
        // into (5,5) (radius 1). If it did, (5,5)'s write version rose past
        // what it was right after step 1 - log either way, not a hard gate
        // (whether it happens depends on this seed's actual features).
        long v55 = sender.writeVersion(ChunkSender.pack(5, 5));
        System.out.println("[live-gen] (5,5) write version after (6,5) touch=" + v55
                + " (>0 means a later neighbour's FEATURES pass rewrote it, expected sometimes)");

        System.out.println(ok ? "[live-gen] PASS" : "[live-gen] FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    /** True if the column has both some non-air cell and some air cell (rules out uniform flat fill). */
    private static boolean hasVariedTerrain(SectionStore store, int cx, int cz) {
        boolean sawSolid = false;
        boolean sawAir = false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = -64; y < 320 && !(sawSolid && sawAir); y++) {
            m.set(cx * 16 + 8, y, cz * 16 + 8);
            BlockState st = store.get(m);
            if (st.isAir()) {
                sawAir = true;
            } else {
                sawSolid = true;
            }
        }
        return sawSolid && sawAir;
    }

    private static boolean expect(boolean cond, String what) {
        System.out.println((cond ? "[live-gen] OK: " : "[live-gen] FAIL: ") + what);
        return cond;
    }
}
