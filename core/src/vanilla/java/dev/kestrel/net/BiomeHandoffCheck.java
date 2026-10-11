package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.worldgen.AsyncChunkGenerator;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.LiveGenerator;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

public final class BiomeHandoffCheck {
    private static final long SEED = 4242424242L;

    private BiomeHandoffCheck() {}

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        boolean ok = syncPath() & asyncPath();
        if (!ok) throw new AssertionError("BiomeHandoffCheck failed");
        System.out.println("BiomeHandoffCheck: PASS");
    }

    private static boolean syncPath() throws Exception {
        boolean ok = true;
        try (LiveGenerator gen = new LiveGenerator(HeadlessNoise.loadStructural(SEED))) {
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            ChunkSender sender = new ChunkSender(loop, 1);
            sender.setGenerator(gen);

            int cx = 5, cz = 7;
            List<byte[]> encoded = sender.sectionBytes(cx, cz);
            Map<Long, byte[]> expected = gen.snapshotBiomeWire(List.of(new int[]{cx, cz}));
            int minSectionY = level.getMinY() >> 4;
            for (int slot = 0; slot < encoded.size(); slot++) {
                long key = SectionPos.asLong(cx, minSectionY + slot, cz);
                byte[] vanilla = expected.get(key);
                byte[] owned = level.biomeWireSection(key);
                ok &= expect(vanilla != null && Arrays.equals(vanilla, owned),
                        "sync exact biome handoff slot=" + slot);
                ok &= expect(endsWith(encoded.get(slot), vanilla),
                        "sync section wire ends with exact Vanilla biome container slot=" + slot);
            }
        }
        return ok;
    }

    private static boolean asyncPath() throws Exception {
        boolean ok = true;
        try (LiveGenerator gen = new LiveGenerator(HeadlessNoise.loadStructural(SEED))) {
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            try (AsyncChunkGenerator async = new AsyncChunkGenerator(gen, loop.store(), 4)) {
                ChunkSender sender = new ChunkSender(loop, 1);
                sender.setGenerator(gen);
                sender.setAsyncGenerator(async);

                int cx = -6, cz = 6;
                ok &= expect(async.requestGeneration(cx, cz), "async center admitted");
                long deadline = System.nanoTime() + 30_000_000_000L;
                while (!loop.store().isChunkGenerated(ChunkPos.pack(cx, cz))
                        && System.nanoTime() < deadline) {
                    sender.publishAsyncResults();
                    Thread.sleep(2);
                }
                sender.publishAsyncResults();
                ok &= expect(loop.store().isChunkGenerated(ChunkPos.pack(cx, cz)),
                        "async center published");

                List<byte[]> encoded = sender.sectionBytes(cx, cz);
                int minSectionY = level.getMinY() >> 4;
                for (int slot = 0; slot < encoded.size(); slot++) {
                    long key = SectionPos.asLong(cx, minSectionY + slot, cz);
                    byte[] owned = level.biomeWireSection(key);
                    ok &= expect(owned != null, "async biome metadata present slot=" + slot);
                    ok &= expect(endsWith(encoded.get(slot), owned),
                            "async section wire ends with published Vanilla biome container slot=" + slot);
                }
            }
        }
        return ok;
    }

    private static boolean endsWith(byte[] whole, byte[] suffix) {
        if (whole == null || suffix == null || whole.length < suffix.length) return false;
        int base = whole.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (whole[base + i] != suffix[i]) return false;
        }
        return true;
    }

    private static boolean expect(boolean condition, String message) {
        System.out.println((condition ? "[biome] OK: " : "[biome] FAIL: ") + message);
        return condition;
    }
}
