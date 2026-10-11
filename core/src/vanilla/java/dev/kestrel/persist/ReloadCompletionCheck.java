package dev.kestrel.persist;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.SectionStore;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.worldgen.HeadlessNoise;
import dev.kestrel.worldgen.LiveGenerator;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Standalone reproduction check for High Finding H1
 * (docs/ARCHITECTURE-AUDIT.md:184-197):
 * 'Chunk completion and world identity are not durable'
 *
 * <p><b>The defect:</b>
 * <ol>
 *   <li>In {@link LiveGenerator#ensureChunk}, generating center chunk A (0,0) runs
 *       {@code FEATURES} and {@code settleLeaves} only for center chunk A, but publishes
 *       the entire 3x3 margin (including neighbor chunk B at (1,0), which only reached
 *       the {@code CARVERS} stage) to {@link SectionStore}.</li>
 *   <li>In {@link WorldSnapshot#capture} and {@link WorldStore#save}, sections are serialized
 *       without their generation stage. {@link ChunkCodec#encodeChunk} writes
 *       {@code Status: "minecraft:full"} unconditionally for every chunk column that has
 *       sections in the snapshot.</li>
 *   <li>When the world is loaded/reloaded ({@code KestrelServer.java:160-167}), Kestrel marks
 *       EVERY restored chunk column as GENERATED/FULL ({@code markChunkGenerated}) simply
 *       because sections exist on disk:
 *       <pre>
 *         for (long key : loop.store().sectionKeys()) {
 *             long chunkKey = SectionPos.sectionToChunk(key);
 *             loop.store().markChunkGenerated(chunkKey);
 *         }
 *       </pre>
 *   </li>
 *   <li>Consequently, neighbor chunk B (1,0), which only ever had CARVERS executed - is
 *       permanently treated as FULL upon reload. When chunk B is subsequently requested,
 *       {@link LiveGenerator#ensureChunk} checks {@code store.isChunkGenerated(centerKey)},
 *       sees {@code true}, and immediately returns empty without ever executing chunk B's
 *       own FEATURES pass. Chunk B is permanently missing all its natural features (ores,
 *       vegetation, decorations, trees).</li>
 * </ol>
 *
 * <p><b>How to read the verdict:</b>
 * Following the pattern of reproduction checks in this repo (such as
 * {@link dev.kestrel.blockentity.TickOrderCheck} and {@link dev.kestrel.loop.EventReentrancyCheck}),
 * H1 is fixed, so by default the check asserts durability and completion integrity (PASS
 * means the fix holds; {@code --assert-clean} is accepted as an alias). Passing
 * {@code --demonstrate-defect} selects the old reproduction mode, where PASS meant the defect
 * reproduced; it now fails.
 *
 * <p>Run (Pi, JDK 25):
 * <pre>
 * java -cp "$(cat ~/kestrel-run/cp.txt)" dev.kestrel.persist.ReloadCompletionCheck
 * </pre>
 */
public final class ReloadCompletionCheck {
    private ReloadCompletionCheck() {
    }

    private static final long SEED = 4242424242L;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // H1 is fixed (2026-09-19): integrity is the default, so a bare
        // checkRepro run gates it. --demonstrate-defect keeps the old
        // reproduction mode, which now fails; --assert-clean is accepted.
        boolean assertClean = !(args.length > 0 && args[0].equals("--demonstrate-defect"));

        System.out.println("=== ReloadCompletionCheck: High Finding H1 ===");
        System.out.println("Finding: 'Chunk completion and world identity are not durable'");
        System.out.println("Mode: " + (assertClean
                ? "ASSERT_CLEAN (fail if defect present)"
                : "DEMONSTRATE_DEFECT (pass if defect reproduced)"));
        System.out.println();

        List<String> failures = new ArrayList<>();
        Path tempWorldDir = null;

        try {
            int chunkAX = 0, chunkAZ = 0;
            int chunkBX = 1, chunkBZ = 0;
            long keyA = ChunkPos.pack(chunkAX, chunkAZ);
            long keyB = ChunkPos.pack(chunkBX, chunkBZ);

            // -----------------------------------------------------------------
            // Step 1: Initial generation of center chunk A (0,0)
            // -----------------------------------------------------------------
            System.out.println("[reload-completion] Step 1: Generating center chunk A at ("
                    + chunkAX + "," + chunkAZ + ") via LiveGenerator...");
            HeadlessNoise.Driver driverA = HeadlessNoise.load(SEED);
            LiveGenerator genA = new LiveGenerator(driverA);
            KestrelLevel levelA = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loopA = new TickLoop(levelA);

            List<int[]> touchedA = genA.ensureChunk(loopA.store(), chunkAX, chunkAZ);

            boolean chunkAGeneratedPreSave = loopA.store().isChunkGenerated(keyA);
            boolean chunkBGeneratedPreSave = loopA.store().isChunkGenerated(keyB);
            int centersPreSave = genA.centersCount();
            int carversPreSave = genA.carversCount();

            // Count sections in store for chunk B (margin chunk)
            int chunkBSectionCountPreSave = 0;
            for (long sk : loopA.store().sectionKeys()) {
                if (SectionPos.sectionToChunk(sk) == keyB) {
                    chunkBSectionCountPreSave++;
                }
            }

            System.out.println("[reload-completion]   Touched chunks from ensureChunk(0,0): " + touchedA.size());
            System.out.println("[reload-completion]   Centers count (FEATURES passes run):  " + centersPreSave);
            System.out.println("[reload-completion]   Carvers count (CARVERS passes run):  " + carversPreSave);
            System.out.println("[reload-completion]   Chunk A (0,0) store.isChunkGenerated: " + chunkAGeneratedPreSave);
            System.out.println("[reload-completion]   Chunk B (1,0) store.isChunkGenerated: " + chunkBGeneratedPreSave);
            System.out.println("[reload-completion]   Chunk B (1,0) sections in store:      " + chunkBSectionCountPreSave);

            if (!chunkAGeneratedPreSave) {
                failures.add("setup: center chunk A (0,0) was not marked generated in store");
                printResultsAndExit(failures);
                return;
            }
            if (chunkBGeneratedPreSave) {
                failures.add("setup: neighbor chunk B (1,0) should NOT be marked generated before save");
                printResultsAndExit(failures);
                return;
            }
            if (chunkBSectionCountPreSave == 0) {
                failures.add("setup: neighbor chunk B (1,0) should have margin sections published to store");
                printResultsAndExit(failures);
                return;
            }

            // -----------------------------------------------------------------
            // Step 2: Save the level to a temporary world directory
            // -----------------------------------------------------------------
            tempWorldDir = Files.createTempDirectory("kestrel-h1-world");
            System.out.println("[reload-completion] Step 2: Saving world to " + tempWorldDir + " via WorldStore.save...");
            WorldStore.save(loopA, tempWorldDir);

            // Inspect on-disk region file to check chunk B's serialized status tag
            Path regionFile = WorldStore.regionDir(tempWorldDir).resolve("r.0.0.mca");
            String chunkBSerializedStatus = null;
            if (Files.isRegularFile(regionFile)) {
                try (RegionFile rf = RegionFile.open(regionFile)) {
                    if (rf.hasChunk(chunkBX & 31, chunkBZ & 31)) {
                        Nbt.Tag.TCompound chunkBTag = rf.readChunk(chunkBX & 31, chunkBZ & 31);
                        if (chunkBTag != null) {
                            chunkBSerializedStatus = Nbt.getString(chunkBTag, "Status");
                        }
                    }
                }
            }
            System.out.println("[reload-completion]   Chunk B (1,0) on-disk MCA serialized Status tag: "
                    + chunkBSerializedStatus);

            // -----------------------------------------------------------------
            // Step 3: Load the level into a fresh KestrelLevel / SectionStore
            // -----------------------------------------------------------------
            System.out.println("[reload-completion] Step 3: Loading world into fresh KestrelLevel via WorldStore.load...");
            KestrelLevel reloadedLevel = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop reloadedLoop = new TickLoop(reloadedLevel);
            WorldStore.load(reloadedLoop, tempWorldDir);

            boolean chunkAGeneratedPostReload = reloadedLoop.store().isChunkGenerated(keyA);
            boolean chunkBGeneratedPostReload = reloadedLoop.store().isChunkGenerated(keyB);

            System.out.println("[reload-completion]   After WorldStore.load:");
            System.out.println("[reload-completion]     Chunk A (0,0) store.isChunkGenerated: " + chunkAGeneratedPostReload);
            System.out.println("[reload-completion]     Chunk B (1,0) store.isChunkGenerated: " + chunkBGeneratedPostReload);

            // -----------------------------------------------------------------
            // Step 4: Demonstrate premature upgrade and skipped FEATURES pass
            // -----------------------------------------------------------------
            System.out.println("[reload-completion] Step 4: Requesting chunk B (1,0) generation in reloaded server...");
            HeadlessNoise.Driver reloadedDriver = HeadlessNoise.load(SEED);
            LiveGenerator reloadedGen = new LiveGenerator(reloadedDriver);

            int centersBeforeRequestB = reloadedGen.centersCount();
            List<int[]> touchedOnReloadB = reloadedGen.ensureChunk(reloadedLoop.store(), chunkBX, chunkBZ);
            int centersAfterRequestB = reloadedGen.centersCount();

            System.out.println("[reload-completion]   ensureChunk(1,0) returned touched chunks: "
                    + touchedOnReloadB.size());
            System.out.println("[reload-completion]   reloadedGen centers count before:         "
                    + centersBeforeRequestB);
            System.out.println("[reload-completion]   reloadedGen centers count after:          "
                    + centersAfterRequestB);

            boolean chunkBSkipped = touchedOnReloadB.isEmpty() && (centersAfterRequestB == centersBeforeRequestB);

            // -----------------------------------------------------------------
            // Step 5: Compare against reference where chunk B actually ran FEATURES
            // -----------------------------------------------------------------
            System.out.println("[reload-completion] Step 5: Generating reference world where chunk B runs FEATURES...");
            HeadlessNoise.Driver refDriver = HeadlessNoise.load(SEED);
            LiveGenerator refGen = new LiveGenerator(refDriver);
            KestrelLevel refLevel = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop refLoop = new TickLoop(refLevel);

            refGen.ensureChunk(refLoop.store(), chunkAX, chunkAZ); // Chunk A
            List<int[]> refTouchedB = refGen.ensureChunk(refLoop.store(), chunkBX, chunkBZ); // Chunk B
            int refCenters = refGen.centersCount();

            System.out.println("[reload-completion]   Reference ensureChunk(1,0) touched chunks: " + refTouchedB.size());
            System.out.println("[reload-completion]   Reference centers count:                   " + refCenters);

            // Compare blocks in chunk column B (1, 0)
            int minX = chunkBX << 4;
            int maxX = minX + 15;
            int minZ = chunkBZ << 4;
            int maxZ = minZ + 15;
            int minY = levelA.getMinY();
            int maxY = minY + levelA.getHeight() - 1;

            int blockMismatches = 0;
            BlockPos sampleDiffPos = null;
            BlockState sampleReloadedState = null;
            BlockState sampleRefState = null;

            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState sReloaded = reloadedLoop.store().get(p);
                        BlockState sRef = refLoop.store().get(p);
                        if (!sReloaded.equals(sRef)) {
                            blockMismatches++;
                            if (sampleDiffPos == null) {
                                sampleDiffPos = p;
                                sampleReloadedState = sReloaded;
                                sampleRefState = sRef;
                            }
                        }
                    }
                }
            }

            System.out.println("[reload-completion]   Block state comparison for chunk B (1,0):");
            System.out.println("[reload-completion]     Mismatched blocks in chunk column B: " + blockMismatches);
            if (sampleDiffPos != null) {
                System.out.println("[reload-completion]     Sample difference at pos " + sampleDiffPos + ":");
                System.out.println("[reload-completion]       Reloaded state (FEATURES skipped): " + sampleReloadedState);
                System.out.println("[reload-completion]       Reference state (FEATURES executed): " + sampleRefState);
            }

            // -----------------------------------------------------------------
            // Defect Analysis & Assertions
            // -----------------------------------------------------------------
            boolean defectPrematureUpgrade = chunkBGeneratedPostReload;
            boolean defectSerializedAsFull = ChunkCodec.STATUS_FULL.equals(chunkBSerializedStatus);
            boolean defectFeaturesSkipped = chunkBSkipped;
            boolean defectBlockStatesDiverged = blockMismatches > 0;

            System.out.println();
            System.out.println("Defect Checklist for High Finding H1:");
            System.out.println("  1. Chunk B only reached CARVERS before save (store.isChunkGenerated=false): "
                    + (!chunkBGeneratedPreSave ? "CONFIRMED" : "NOT REPRODUCED"));
            System.out.println("  2. Chunk B serialized as 'minecraft:full' on disk without generation stage:  "
                    + (defectSerializedAsFull ? "CONFIRMED" : "NOT REPRODUCED"));
            System.out.println("  3. Chunk B prematurely upgraded to isChunkGenerated=true on server startup:  "
                    + (defectPrematureUpgrade ? "CONFIRMED" : "NOT REPRODUCED"));
            System.out.println("  4. Chunk B FEATURES pass permanently skipped on reloaded world:              "
                    + (defectFeaturesSkipped ? "CONFIRMED" : "NOT REPRODUCED"));
            System.out.println("  5. Chunk B block states diverged from full FEATURES generation (" + blockMismatches + " diffs): "
                    + (defectBlockStatesDiverged ? "CONFIRMED" : "NOT REPRODUCED"));

            if (assertClean) {
                expect(chunkAGeneratedPostReload,
                        "Chunk A (0,0) completion marker must survive save/reload",
                        failures);
                expect(!defectPrematureUpgrade,
                        "Chunk B (1,0) must NOT be marked generated on reload before running FEATURES",
                        failures);
                expect(!defectFeaturesSkipped,
                        "Chunk B (1,0) ensureChunk must execute its FEATURES pass after reload",
                        failures);
            } else {
                // Defect demonstration mode: assert that defect H1 was deterministically reproduced
                expect(!chunkBGeneratedPreSave,
                        "Pre-save: chunk B should NOT be marked generated in SectionStore",
                        failures);
                expect(defectSerializedAsFull,
                        "Persistence: chunk B should be serialized as 'minecraft:full' despite being at CARVERS",
                        failures);
                expect(defectPrematureUpgrade,
                        "Startup: chunk B was NOT prematurely upgraded to isChunkGenerated=true (defect H1 not reproduced)",
                        failures);
                expect(defectFeaturesSkipped,
                        "Worldgen: chunk B FEATURES pass was NOT skipped (defect H1 not reproduced)",
                        failures);
                expect(defectBlockStatesDiverged,
                        "Worldgen: chunk B blocks did not diverge from reference (defect H1 not reproduced)",
                        failures);
            }

        } finally {
            if (tempWorldDir != null) {
                deleteDirectory(tempWorldDir);
            }
        }

        printResultsAndExit(failures);
    }

    private static void expect(boolean cond, String what, List<String> failures) {
        if (!cond) {
            failures.add(what);
        }
    }

    private static void printResultsAndExit(List<String> failures) {
        boolean passed = failures.isEmpty();
        System.out.println();
        System.out.println("ReloadCompletionCheck: " + (passed ? "PASS" : "FAIL"));
        if (!passed) {
            for (String f : failures) {
                System.out.println("  FAIL: " + f);
            }
            System.exit(1);
        }
    }

    private static void deleteDirectory(Path path) {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
