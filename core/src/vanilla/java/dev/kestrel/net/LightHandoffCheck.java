package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.worldgen.AsyncChunkGenerator;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.LiveGenerator;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;
import java.util.List;

public final class LightHandoffCheck {
    private static final long SEED = 4242424242L;

    private LightHandoffCheck() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        revisionInvalidationPath();

        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        try (LiveGenerator gen = new LiveGenerator(HeadlessNoise.loadStructural(SEED))) {
            ChunkSender sender = new ChunkSender(loop, 1);
            sender.setGenerator(gen);

            int cx = 12;
            int cz = -9;
            long center = ChunkPos.pack(cx, cz);

            // Match production startup exactly: KestrelServer may generate the
            // spawn/eager center synchronously before --async-chunkgen attaches.
            // It is already INITIALIZE_LIGHT/generated, but cannot yet publish
            // network LIGHT until its radius-1 generated-center frontier exists.
            List<int[]> initialTouched = gen.ensureChunk(loop.store(), cx, cz);
            for (var biome : gen.snapshotBiomeWire(initialTouched).entrySet()) {
                level.putBiomeWireSection(biome.getKey(), biome.getValue());
            }
            for (var light : gen.snapshotNewlyReadyLightWire(loop.store(), initialTouched).entrySet()) {
                level.putLightWireChunk(light.getKey(), light.getValue());
            }
            expect(loop.store().isChunkGenerated(center),
                    "pre-async center is already generated in the live store");
            expect(level.lightWireChunk(center) == null,
                    "pre-async center is not prematurely network-lit");

            try (AsyncChunkGenerator async = new AsyncChunkGenerator(gen, loop.store(), 4)) {
                sender.setAsyncGenerator(async);

                // Fill the remaining radius-1 centers through the real bounded
                // worker/publication path. The worker shadow must remember the
                // pre-existing center marker or exact center LIGHT never becomes
                // radius-ready.
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = cx + dx;
                        int z = cz + dz;
                        long key = ChunkPos.pack(x, z);
                        if (loop.store().isChunkGenerated(key)) {
                            continue;
                        }
                        while (!async.requestGeneration(x, z)) {
                            sender.publishAsyncResults();
                            Thread.sleep(2);
                        }
                    }
                }

                long deadline = System.nanoTime() + 60_000_000_000L;
                while (level.lightWireChunk(center) == null && System.nanoTime() < deadline) {
                    sender.publishAsyncResults();
                    Thread.sleep(2);
                }
                sender.publishAsyncResults();

                byte[] owned = level.lightWireChunk(center);
                expect(owned != null,
                        "pre-async center becomes LIGHT-ready after async radius-1 completion");
                byte[] direct = gen.snapshotLightWire(cx, cz);
                expect(direct != null && Arrays.equals(owned, direct),
                        "tick-thread handoff bytes equal Vanilla serialization from live engine");

                List<byte[]> sections = sender.sectionBytes(cx, cz);
                byte[] exactPacket = Outbound.chunk(cx, cz, sections, owned).payload();
                byte[] fabricatedPacket = Outbound.chunk(cx, cz, sections, sections.size() + 2).payload();
                expect(endsWith(exactPacket, owned),
                        "LevelChunkWithLight packet ends with authoritative Vanilla light payload");
                expect(!Arrays.equals(exactPacket, fabricatedPacket),
                        "authoritative terrain light is not the old fabricated full-bright packet");

                System.out.println("LightHandoffCheck: PASS bytes=" + owned.length);
            }
        }
    }

    private static void revisionInvalidationPath() {
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        ChunkSender sender = new ChunkSender(loop, 1);

        int cx = 3;
        int cz = -4;
        long worldKey = ChunkPos.pack(cx, cz);
        long streamKey = ChunkSender.pack(cx, cz);

        long v0 = sender.writeVersion(streamKey);
        sender.publishLightMetadata(java.util.Map.of(worldKey, new byte[]{1, 2, 3}));
        long v1 = sender.writeVersion(streamKey);
        expect(v1 == v0 + 1, "new exact light payload owns one chunk revision");

        sender.publishLightMetadata(java.util.Map.of(worldKey, new byte[]{1, 2, 3}));
        long v2 = sender.writeVersion(streamKey);
        expect(v2 == v1, "identical light reserialization causes no resend revision");

        sender.publishLightMetadata(java.util.Map.of(worldKey, new byte[]{1, 9, 3}));
        long v3 = sender.writeVersion(streamKey);
        expect(v3 == v2 + 1, "changed neighbour-propagated light owns a resend revision");
    }

    private static void awaitPublished(
            ChunkSender sender,
            AsyncChunkGenerator async,
            TickLoop loop,
            long key,
            long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!loop.store().isChunkGenerated(key) && System.nanoTime() < deadline) {
            sender.publishAsyncResults();
            Thread.sleep(2);
        }
        sender.publishAsyncResults();
        expect(loop.store().isChunkGenerated(key), "requested center published before timeout");
    }

    private static boolean endsWith(byte[] whole, byte[] suffix) {
        if (whole == null || suffix == null || whole.length < suffix.length) return false;
        int base = whole.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (whole[base + i] != suffix[i]) return false;
        }
        return true;
    }

    private static void expect(boolean condition, String message) {
        System.out.println((condition ? "[light] OK: " : "[light] FAIL: ") + message);
        if (!condition) throw new AssertionError(message);
    }
}
