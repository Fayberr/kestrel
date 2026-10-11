package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.persist.WorldStore;
import dev.kestrel.worldgen.AsyncChunkGenerator;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.LiveGenerator;

import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

/**
 * Step-9 persistence gate: exact generated biome/light network metadata must
 * survive Kestrel save/reload. The gate also proves that a reloaded generated
 * frontier can expand through the real async live path without deadlocking
 * exact radius-dependent LIGHT readiness at the persisted/new boundary.
 */
public final class GeneratedMetadataReloadCheck {
    private static final long SEED = 4242424242L;

    private GeneratedMetadataReloadCheck() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Path world = Files.createTempDirectory("kestrel-generated-metadata-reload-");
        try {
            KestrelLevel beforeLevel =
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop beforeLoop = new TickLoop(beforeLevel);

            int cx = 12;
            int cz = -9;
            long chunkKey = ChunkPos.pack(cx, cz);
            int minSectionY = beforeLevel.getMinY() >> 4;
            long biomeKey = SectionPos.asLong(cx, minSectionY, cz);

            byte[] biomeBefore;
            byte[] lightBefore;
            try (LiveGenerator gen =
                    new LiveGenerator(HeadlessNoise.loadStructural(SEED))) {
                ChunkSender sender = new ChunkSender(beforeLoop, 1);
                sender.setGenerator(gen);

                // Generate the complete radius-1 center set so the center reaches
                // authoritative Vanilla LIGHT, using the real live publication path.
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        sender.sectionBytes(cx + dx, cz + dz);
                    }
                }

                biomeBefore = beforeLevel.biomeWireSection(biomeKey);
                lightBefore = beforeLevel.lightWireChunk(chunkKey);
                require(biomeBefore != null && biomeBefore.length > 0,
                        "fixture owns exact generated biome metadata before save");
                require(lightBefore != null && lightBefore.length > 0,
                        "fixture owns exact generated light metadata before save");
            }

            WorldStore.save(beforeLoop, world);

            KestrelLevel afterLevel =
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop afterLoop = new TickLoop(afterLevel);
            WorldStore.load(afterLoop, world);

            require(afterLoop.store().isChunkGenerated(chunkKey),
                    "generated completion marker survives reload");

            byte[] biomeAfter = afterLevel.biomeWireSection(biomeKey);
            byte[] lightAfter = afterLevel.lightWireChunk(chunkKey);

            require(biomeAfter != null,
                    "exact generated biome metadata survives reload");
            require(lightAfter != null,
                    "exact generated light metadata survives reload");
            require(Arrays.equals(biomeBefore, biomeAfter),
                    "generated biome metadata bytes are exact after reload");
            require(Arrays.equals(lightBefore, lightAfter),
                    "generated light metadata bytes are exact after reload");

            ChunkSender reloadedSender = new ChunkSender(afterLoop, 1);
            var sections = reloadedSender.sectionBytes(cx, cz);
            require(endsWith(sections.get(0), biomeBefore),
                    "reloaded section wire uses persisted generated biome bytes");

            // Mandatory live-reload frontier gate: the saved fixture owns a
            // generated/light-ready 3x3 centered at (cx,cz). Reattach a fresh
            // LiveGenerator + async worker, then join one chunk beyond that
            // generated frontier. The forced join ring straddles persisted
            // centers and genuinely fresh centers, so exact LIGHT can only
            // become ready if the sync/reload -> async ownership handoff
            // handles the persisted/new dependency boundary correctly.
            try (LiveGenerator reloadGen =
                            new LiveGenerator(HeadlessNoise.loadStructural(SEED));
                 AsyncChunkGenerator async =
                            new AsyncChunkGenerator(reloadGen, afterLoop.store(), 4)) {
                ChunkSender frontierSender = new ChunkSender(afterLoop, 1);
                frontierSender.setGenerator(reloadGen);
                frontierSender.setAsyncGenerator(async);

                int frontierCx = cx + 2;
                int frontierCz = cz;
                KestrelPlayer frontierPlayer = new KestrelPlayer(
                        UUID.fromString("00000000-0000-0000-0000-000000000174"),
                        "reload-frontier",
                        frontierCx * 16 + 8,
                        80,
                        frontierCz * 16 + 8);

                boolean ready = frontierSender.prepareJoinBurst(frontierPlayer);
                require(!ready,
                        "reload frontier join defers while fresh async centers are generated");

                long deadline = System.nanoTime() + 90_000_000_000L;
                while (!ready && System.nanoTime() < deadline) {
                    frontierSender.publishAsyncResults();
                    ready = frontierSender.prepareJoinBurst(frontierPlayer);
                    Thread.sleep(2);
                }
                frontierSender.publishAsyncResults();

                require(ready,
                        "reload frontier async join becomes exact-metadata network-ready");
                for (int dx = -ChunkSender.BURST_FORCED_RADIUS;
                        dx <= ChunkSender.BURST_FORCED_RADIUS; dx++) {
                    for (int dz = -ChunkSender.BURST_FORCED_RADIUS;
                            dz <= ChunkSender.BURST_FORCED_RADIUS; dz++) {
                        long key = ChunkPos.pack(frontierCx + dx, frontierCz + dz);
                        require(afterLoop.store().isChunkGenerated(key),
                                "reload frontier forced center generated " + dx + "," + dz);
                        require(afterLevel.lightWireChunk(key) != null,
                                "reload frontier forced center owns exact light " + dx + "," + dz);
                    }
                }
            }

            System.out.println("GeneratedMetadataReloadCheck: PASS"
                    + " biomeBytes=" + biomeAfter.length
                    + " lightBytes=" + lightAfter.length
                    + " reloadFrontier=PASS");
        } finally {
            deleteTree(world);
        }
    }

    private static boolean endsWith(byte[] whole, byte[] suffix) {
        if (whole == null || suffix == null || whole.length < suffix.length) return false;
        int base = whole.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (whole[base + i] != suffix[i]) return false;
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        System.out.println((condition ? "[metadata-reload] OK: " : "[metadata-reload] FAIL: ")
                + message);
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || Files.notExists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
