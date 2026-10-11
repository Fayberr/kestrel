package dev.kestrel.harness;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.SectionStore;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.persist.BlockHash;
import dev.kestrel.persist.WorldStore;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.ticks.ProtoChunkTicks;
import java.nio.file.Path;

/**
 * Parity proof: Kestrel's {@code persist.BlockHash} == the harness probe's
 * {@code blocks_hash} ({@code HashProbe.hashChunkBoth}[1]) for every chunk of
 * an imported world. Each chunk's store cells are copied into a real vanilla
 * {@code ProtoChunk} (LevelChunkSection over a PalettedContainer, so
 * {@code hasOnlyAir} and {@code getBlockState} are vanilla's) and hashed by the
 * probe's reflection path.
 *
 * <p>Lives in the harness package because {@code hashChunkBoth} is
 * package-private. Not part of either Gradle build; run it by hand (Pi recipe,
 * ~20 s; see kestrel-toolchain-gotchas "The Pi CAN run the Kestrel headless
 * proofs" for the bundler classpath):
 * <pre>
 *   javac -d out -cp "core-classes:$CP" BlockHashParity.java .../HashProbe.java
 *   java -cp "out:core-classes:$CP" dev.kestrel.harness.BlockHashParity WORLD HALF
 * </pre>
 * WORLD is a square of (2*HALF+1)^2 chunks around 0,0, e.g. the output of
 * {@code g2-region.py synth WORLD --half 2}. 2026-09-13 on the synthetic
 * 25-chunk world: ok=25 bad=0.
 */
public class BlockHashParity {
    public static void main(String[] a) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        WorldStore.importWorld(loop, Path.of(a[0]));
        SectionStore store = loop.store();
        LevelHeightAccessor h = new LevelHeightAccessor() {
            public int getHeight() { return 384; }
            public int getMinY() { return -64; }
        };
        int half = Integer.parseInt(a[1]);
        int ok = 0, bad = 0;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int cx = -half; cx <= half; cx++) for (int cz = -half; cz <= half; cz++) {
            LevelChunkSection[] secs = new LevelChunkSection[24];
            for (int si = 0; si < 24; si++) {
                secs[si] = new LevelChunkSection(new PalettedContainer<>(air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
                long key = SectionPos.asLong(cx, -4 + si, cz);
                for (int i = 0; i < 4096; i++) {
                    BlockState st = store.readCell(key, i);
                    if (st != null && st != air) secs[si].setBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15, st, false);
                }
            }
            ProtoChunk pc = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, secs, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), h, null, null);
            long[] both = HashProbe.hashChunkBoth(pc);
            long mine = BlockHash.chunk(store, cx, cz);
            if (both[1] == mine && mine != 0) ok++; else { bad++; System.out.printf("MISMATCH %d,%d probe=%016x kestrel=%016x%n", cx, cz, both[1], mine); }
        }
        System.out.println("BlockHashParity ok=" + ok + " bad=" + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
