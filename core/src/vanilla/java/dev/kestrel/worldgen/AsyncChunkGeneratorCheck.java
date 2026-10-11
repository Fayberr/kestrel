package dev.kestrel.worldgen;

import dev.kestrel.loop.SectionStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Focused concurrency/backpressure regression gate for AsyncChunkGenerator. */
public final class AsyncChunkGeneratorCheck {
    private AsyncChunkGeneratorCheck() {}

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        boolean ok = true;
        ok &= dedup();
        ok &= boundedness();
        ok &= failurePropagation();
        ok &= singlePublication();
        ok &= immutableSnapshotIsolation();
        System.out.println(ok ? "AsyncChunkGeneratorCheck: PASS" : "AsyncChunkGeneratorCheck: FAIL");
        if (!ok) System.exit(1);
    }

    private static boolean dedup() throws Exception {
        boolean ok = true;
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AsyncChunkGenerator gen = new AsyncChunkGenerator((store, cx, cz) -> {
            invocations.incrementAndGet();
            gate.await();
            return List.of(new int[]{cx, cz});
        }, shadow(), 10);
        try {
            ok &= expect("dedup", gen.requestGeneration(5, 5), "first request admitted");
            ok &= expect("dedup", !gen.requestGeneration(5, 5), "duplicate pending request refused");
            ok &= expect("dedup", gen.queueDepth() == 1, "bounded set contains one center");
            gate.countDown();
            List<AsyncChunkGenerator.Result> results = awaitDrain(gen, 1, 2000);
            ok &= expect("dedup", results.size() == 1, "one result delivered");
            ok &= expect("dedup", invocations.get() == 1, "job body executed once");
        } finally {
            gate.countDown();
            gen.close();
        }
        return ok;
    }

    private static boolean boundedness() throws Exception {
        boolean ok = true;
        CountDownLatch gate = new CountDownLatch(1);
        AsyncChunkGenerator gen = new AsyncChunkGenerator((store, cx, cz) -> {
            gate.await();
            return List.of(new int[]{cx, cz});
        }, shadow(), 2);
        try {
            ok &= expect("bounded", gen.requestGeneration(1, 1), "first admitted");
            ok &= expect("bounded", gen.requestGeneration(2, 2), "second admitted at cap");
            ok &= expect("bounded", !gen.requestGeneration(3, 3), "third refused at cap");
            ok &= expect("bounded", gen.queueDepth() == 2, "depth stays at cap");
            gate.countDown();
            ok &= expect("bounded", awaitDrain(gen, 2, 2000).size() == 2, "both admitted jobs finish");
            ok &= expect("bounded", gen.queueDepth() == 0, "drain frees admission slots");
            ok &= expect("bounded", gen.requestGeneration(3, 3), "previously refused center can retry");
        } finally {
            gate.countDown();
            gen.close();
        }
        return ok;
    }

    private static boolean failurePropagation() throws Exception {
        boolean ok = true;
        RuntimeException boom = new RuntimeException("simulated generation failure");
        AsyncChunkGenerator gen = new AsyncChunkGenerator((store, cx, cz) -> {
            throw boom;
        }, shadow(), 10);
        try {
            ok &= expect("failure", gen.requestGeneration(7, 7), "failing center admitted");
            List<AsyncChunkGenerator.Result> results = awaitDrain(gen, 1, 2000);
            ok &= expect("failure", results.size() == 1, "failure delivered once");
            if (results.size() == 1) {
                var r = results.get(0);
                ok &= expect("failure", r.failed(), "result reports failure");
                ok &= expect("failure", r.failure == boom, "original exception preserved");
                ok &= expect("failure", r.touched == null && r.sections == null,
                        "failed result exposes no publishable snapshot");
            }
            ok &= expect("failure", gen.failedCount() == 1, "failed center remembered");
            ok &= expect("failure", !gen.requestGeneration(7, 7), "failed center not silently retried");
        } finally {
            gen.close();
        }
        return ok;
    }

    private static boolean singlePublication() throws Exception {
        boolean ok = true;
        AsyncChunkGenerator gen = new AsyncChunkGenerator(
                (store, cx, cz) -> List.of(new int[]{cx, cz}), shadow(), 10);
        try {
            ok &= expect("single", gen.requestGeneration(2, 3), "center admitted");
            ok &= expect("single", awaitDrain(gen, 1, 2000).size() == 1, "first drain gets result");
            ok &= expect("single", gen.drainCompleted().isEmpty(), "second drain cannot redeliver it");
        } finally {
            gen.close();
        }
        return ok;
    }

    /**
     * Reproduces the reviewed N/N+1 race shape deliberately: job 2 overwrites
     * the same worker section before the tick thread drains either result.
     * Result 1 must still publish STONE, not job 2's DIRT.
     */
    private static boolean immutableSnapshotIsolation() throws Exception {
        boolean ok = true;
        long sectionKey = SectionPos.asLong(0, 0, 0);
        AsyncChunkGenerator gen = new AsyncChunkGenerator((store, cx, cz) -> {
            BlockState[] cells = new BlockState[SectionStore.CELLS];
            Arrays.fill(cells, Blocks.AIR.defaultBlockState());
            cells[0] = cx == 1 ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            store.putGeneratedCells(sectionKey, cells);
            return List.of(new int[]{0, 0});
        }, shadow(), 4);
        try {
            ok &= expect("snapshot", gen.requestGeneration(1, 0), "job 1 admitted");
            ok &= expect("snapshot", gen.requestGeneration(2, 0), "job 2 admitted");
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (gen.completedDepth() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            ok &= expect("snapshot", gen.completedDepth() == 2,
                    "both jobs completed before publication (worst-case overlap)");
            List<AsyncChunkGenerator.Result> results = gen.drainCompleted();
            ok &= expect("snapshot", results.size() == 2, "two snapshots drain in FIFO order");
            if (results.size() == 2) {
                SectionStore firstLive = shadow();
                firstLive.putGeneratedCompact(sectionKey, results.get(0).sections.get(sectionKey));
                SectionStore secondLive = shadow();
                secondLive.putGeneratedCompact(sectionKey, results.get(1).sections.get(sectionKey));
                BlockPos p = new BlockPos(0, 0, 0);
                ok &= expect("snapshot", firstLive.get(p).is(Blocks.STONE),
                        "job 1 snapshot remains STONE after job 2 mutated worker shadow");
                ok &= expect("snapshot", secondLive.get(p).is(Blocks.DIRT),
                        "job 2 snapshot captures DIRT");
            }
        } finally {
            gen.close();
        }
        return ok;
    }

    private static SectionStore shadow() {
        return new SectionStore(-64, 384);
    }

    private static List<AsyncChunkGenerator.Result> awaitDrain(
            AsyncChunkGenerator gen, int minSize, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        List<AsyncChunkGenerator.Result> all = new ArrayList<>();
        while (all.size() < minSize && System.nanoTime() < deadline) {
            all.addAll(gen.drainCompleted());
            if (all.size() < minSize) Thread.sleep(2);
        }
        return all;
    }

    private static boolean expect(String tag, boolean condition, String message) {
        System.out.println((condition ? "[" + tag + "] OK: " : "[" + tag + "] FAIL: ") + message);
        return condition;
    }
}
