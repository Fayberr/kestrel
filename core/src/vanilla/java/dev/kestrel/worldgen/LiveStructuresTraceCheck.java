package dev.kestrel.worldgen;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.SectionStore;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Fail-closed proof that the production LiveGenerator structures-on path
 * reproduces a captured Vanilla FEATURES total order in the authoritative
 * SectionStore, not merely in its internal ProtoChunks.
 */
public final class LiveStructuresTraceCheck {
    private LiveStructuresTraceCheck() {}

    private record Ref(int x, int z, String hash) {}
    private record Event(long sequence, int x, int z, boolean enter, Ref[] refs) {}
    private record Snapshot(int x, int z, int minY, int height, int[] ids) {}
    private record ChunkLoad(
            long afterEventSeq,
            Integer activeOriginX,
            Integer activeOriginZ,
            int x,
            int z,
            String status,
            String chunkClass,
            boolean loadedStructureReferences) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 4) {
            throw new IllegalArgumentException(
                    "LiveStructuresTraceCheck <trace-directory>"
                            + " [--replay-chunk-loads] [--replay-lighting] [--carver-threads=N]");
        }
        boolean replayChunkLoads = false;
        boolean replayLighting = false;
        int carverThreads = 0;
        for (int ai = 1; ai < args.length; ai++) {
            if (args[ai].startsWith("--carver-threads=")) {
                // Production's parallel chunk-local stages. Without cross-center
                // hints the pool only builds chunks the same ensureChunk call
                // consumes, so no replayed reload can fall in between.
                carverThreads = Integer.parseInt(args[ai].substring("--carver-threads=".length()));
                continue;
            }
            switch (args[ai]) {
                case "--replay-chunk-loads" -> replayChunkLoads = true;
                // Oracle-schedule diagnostic: complete INITIALIZE_LIGHT at the
                // captured light-executor cutpoints instead of right after each
                // center's FEATURES. The operation is Kestrel's own; only the
                // timing comes from the trace, as with --replay-chunk-loads.
                case "--replay-lighting" -> replayLighting = true;
                default -> throw new IllegalArgumentException(
                        "unknown flag: " + args[ai]);
            }
        }
        Path root = Path.of(args[0]);
        List<String> lines = Files.readAllLines(root.resolve("events.tsv"));
        if (lines.size() < 2) throw new IOException("empty trace");

        long seed = Long.MIN_VALUE;
        List<Event> events = new ArrayList<>();
        Set<Long> origins = new HashSet<>();
        Set<Long> coverage = new HashSet<>();
        Map<Long, Ref> lastRef = new HashMap<>();
        Map<Long, Event> completeByOrigin = new HashMap<>();

        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] f = line.split("\t", -1);
            if (f.length != 11) throw new IOException("invalid event row: " + line);
            long rowSeed = Long.parseLong(f[5]);
            if (seed == Long.MIN_VALUE) seed = rowSeed;
            if (rowSeed != seed) throw new IOException("mixed seeds");

            String[] raw = f[10].split(";");
            if (raw.length != 9) throw new IOException("expected 3x3 refs");
            Ref[] refs = new Ref[raw.length];
            for (int i = 0; i < raw.length; i++) {
                String[] p = raw[i].split(",", 4);
                if (p.length != 4) throw new IOException("invalid ref " + raw[i]);
                Ref ref = new Ref(
                        Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[3]);
                refs[i] = ref;
                long key = ChunkPos.pack(ref.x, ref.z);
                coverage.add(key);
                lastRef.put(key, ref);
            }

            int x = Integer.parseInt(f[3]);
            int z = Integer.parseInt(f[4]);
            boolean enter = f[1].equals("ENTER");
            if (!enter && !f[1].equals("COMPLETE")) {
                throw new IOException("unsupported phase " + f[1]);
            }
            if (enter && !origins.add(ChunkPos.pack(x, z))) {
                throw new IOException("repeated FEATURES center " + x + "," + z);
            }
            long sequence = Long.parseLong(f[0]);
            Event event = new Event(sequence, x, z, enter, refs);
            events.add(event);
            if (!enter) {
                Event previous = completeByOrigin.put(ChunkPos.pack(x, z), event);
                if (previous != null) {
                    throw new IOException("repeated FEATURES COMPLETE " + x + "," + z);
                }
            }
        }
        if (completeByOrigin.size() != origins.size()) {
            throw new IOException(
                    "ENTER/COMPLETE count mismatch enters=" + origins.size()
                            + " completes=" + completeByOrigin.size());
        }

        Map<Long, List<ChunkLoad>> chunkLoads = readChunkLoads(root);
        if (!Files.isRegularFile(root.resolve("chunk-loads.tsv"))
                || chunkLoads == null) {
            throw new IOException(
                    "LiveStructuresTraceCheck requires timestamped chunk-loads.tsv");
        }

        Map<Long, List<long[]>> lightingEvents =
                replayLighting ? readLightingEvents(root) : Map.of();

        long start = System.nanoTime();
        try (LiveGenerator live =
                new LiveGenerator(HeadlessNoise.loadStructural(seed))) {
            if (replayLighting) {
                live.deferLightInitializationForGate();
            }
            live.enableParallelCarvers(carverThreads);
            System.out.println("LIVE_CARVER_THREADS " + live.parallelCarverThreads());
            KestrelLevel level = new KestrelLevel(
                    KestrelLevel.Dimension.OVERWORLD,
                    HeadlessNoise.MIN_Y, HeadlessNoise.HEIGHT);
            SectionStore store = level.store();
            // This gate only reads the store; the generator is its only writer.
            live.setExclusiveStore(store);

            if (!live.structuresEnabled()) {
                throw new AssertionError("LiveGenerator unexpectedly structures-off");
            }
            int enters = 0;
            long[] featureWindows = {0};
            long[] featureCells = {0};
            long reloadReplays = 0;
            long heightmapReplays = 0;
            long heightmapPrefixNoops = 0;
            long heightmapPending = 0;
            // {applied, repeatsAfterReload, outsideGrid}
            long[] lightCounters = new long[3];
            long previousEventSeq = 0;
            long preAppliedPassSeq = -1;
            for (Event event : events) {
                if (event.sequence != previousEventSeq + 1) {
                    throw new IOException(
                            "non-contiguous FEATURES sequence previous="
                                    + previousEventSeq
                                    + " current=" + event.sequence);
                }
                java.util.ArrayDeque<long[]> pendingLights = new java.util.ArrayDeque<>(
                        previousEventSeq == preAppliedPassSeq
                                ? List.of()
                                : lightingEvents.getOrDefault(previousEventSeq, List.of()));
                if (replayChunkLoads) {
                    for (ChunkLoad load
                            : chunkLoads.getOrDefault(previousEventSeq, List.of())) {
                        if (load.activeOriginX != null
                                && !FeaturesTraceReplay
                                        .reloadIsUnobservableByActiveOrigin(
                                                load.activeOriginX,
                                                load.activeOriginZ,
                                                load.x, load.z)) {
                            throw new IOException(
                                    "chunk reload occurred inside active FEATURES origin="
                                            + load.activeOriginX + "," + load.activeOriginZ
                                            + " afterEventSeq=" + previousEventSeq
                                            + " chunk=" + load.x + "," + load.z
                                            + "; live diagnostic needs an intra-FEATURES hook");
                        }
                        net.minecraft.world.level.chunk.status.ChunkStatus captured =
                                net.minecraft.world.level.chunk.status.ChunkStatus
                                        .byName(load.status);
                        if (captured == null) {
                            throw new IOException(
                                    "unknown captured reload status " + load.status
                                            + " chunk=" + load.x + "," + load.z);
                        }
                        if (replayLighting
                                && captured.isOrAfter(
                                        net.minecraft.world.level.chunk.status.ChunkStatus
                                                .LIGHT)
                                && !live.isLightInitializedForGate(load.x, load.z)
                                && pendingLights.stream().anyMatch(light ->
                                        light[0] == load.x && light[1] == load.z)) {
                            // Vanilla publishes LIGHT only after the light
                            // completion, so a chunk saved at or past it
                            // carries the layer data its next read restores.
                            // When this cutpoint also has the chunk's captured
                            // completion, apply it before reading the form.
                            // Other full-form loads restore saved layers but
                            // need no completion at this cutpoint; a later
                            // captured completion must remain free to run.
                            applyLightsThrough(live, pendingLights, load.x, load.z,
                                    previousEventSeq, lightCounters);
                        }
                        // Vanilla lost this chunk's serialized form to an
                        // unload and then read it back: the unload cleared its
                        // light storage, and the read restored only the layers
                        // the form carried, which is none at all below LIGHT.
                        // Reproduce both, so the layer a neighbour's
                        // INITIALIZE_LIGHT registered for this chunk is gone
                        // exactly when Vanilla's is.
                        // See WorldgenLightLifecycle.reloadChunkData.
                        live.reloadChunkLightForGate(load.x, load.z,
                                captured.isOrAfter(
                                        net.minecraft.world.level.chunk.status.ChunkStatus
                                                .LIGHT));
                        if (load.chunkClass.equals(
                                net.minecraft.world.level.chunk.ProtoChunk.class.getName())) {
                            live.reloadChunkBlockEntitiesForGate(load.x, load.z);
                        }
                        switch (live.replayChunkReloadHeightmapsForGate(
                                load.x, load.z, captured)) {
                            case ROUND_TRIP -> heightmapReplays++;
                            case PREFIX_NOOP -> heightmapPrefixNoops++;
                            case PENDING_CARVERS -> heightmapPending++;
                        }
                        if (!load.loadedStructureReferences) {
                            continue;
                        }
                        live.replayChunkReloadForGate(load.x, load.z);
                        reloadReplays++;
                    }
                }
                while (!pendingLights.isEmpty()) {
                    applyLight(live, pendingLights.removeFirst(), lightCounters);
                }

                if (event.enter) {
                    Event complete =
                            completeByOrigin.get(ChunkPos.pack(event.x, event.z));
                    if (complete == null) {
                        throw new AssertionError(
                                "missing COMPLETE snapshot for "
                                        + event.x + "," + event.z);
                    }
                    // Light completions stamped with this pass's own ENTER
                    // sequence were emitted by Vanilla's light executor while
                    // the pass was running: the worldgen thread does not wait
                    // for them, so the completion lands somewhere inside the
                    // pass. Apply them before the pass instead of after it, so
                    // the placements that read brightness see the registered
                    // layer and reproduce the captured outcome.
                    //
                    // Moving one earlier is only a schedule Vanilla could have
                    // produced when the target's own FEATURES status, which is
                    // what gates its INITIALIZE_LIGHT task, was already
                    // published before this pass began. events.tsv is the
                    // proof: the target's FEATURES COMPLETE sequence has to
                    // precede this pass's ENTER sequence, otherwise the
                    // executor could not legally have run it yet and the case
                    // fails closed here.
                    java.util.ArrayDeque<long[]> intraPassLights =
                            new java.util.ArrayDeque<>(
                                    lightingEvents.getOrDefault(
                                            event.sequence, List.of()));
                    for (long[] light : intraPassLights) {
                        Event targetComplete = completeByOrigin.get(
                                ChunkPos.pack((int) light[0], (int) light[1]));
                        if (targetComplete == null
                                || targetComplete.sequence >= event.sequence) {
                            throw new IOException(
                                    "intra-FEATURES light completion for "
                                            + (int) light[0] + "," + (int) light[1]
                                            + " stamped afterEventSeq=" + event.sequence
                                            + " while pass " + event.x + "," + event.z
                                            + " was running, but the target's FEATURES"
                                            + " COMPLETE is "
                                            + (targetComplete == null
                                                    ? "absent" : targetComplete.sequence)
                                            + "; moving it before the pass is not a"
                                            + " schedule Vanilla could have produced");
                        }
                    }
                    while (!intraPassLights.isEmpty()) {
                        applyLight(live, intraPassLights.removeFirst(), lightCounters);
                    }
                    preAppliedPassSeq = event.sequence;
                    live.ensureChunk(store, event.x, event.z, (cx, cz, grid) -> {
                        try {
                            featureCells[0] +=
                                    compareFeaturesWindow(root, complete, grid);
                            featureWindows[0]++;
                        } catch (IOException e) {
                            throw new IllegalStateException(
                                    "cannot read exact FEATURES oracle for "
                                            + cx + "," + cz,
                                    e);
                        } catch (java.security.NoSuchAlgorithmException e) {
                            throw new IllegalStateException(
                                    "cannot hash exact FEATURES oracle for "
                                            + cx + "," + cz,
                                    e);
                        }
                    });
                    enters++;
                }
                previousEventSeq = event.sequence;
            }
            long capturedReloads = chunkLoads.values().stream()
                    .mapToLong(List::size).sum();
            System.out.println(
                    "LIVE_CHUNK_RELOAD_RESULT captured=" + capturedReloads
                            + " replays=" + reloadReplays
                            + " heightmapReplays=" + heightmapReplays
                            + " heightmapPrefixNoops=" + heightmapPrefixNoops
                            + " heightmapPendingCarvers=" + heightmapPending
                            + " mode="
                            + (replayChunkLoads ? "oracle-replay" : "native-assert"));
            // Completions captured after the final FEATURES event have no
            // following loop iteration; apply them so the count below is exact.
            // A final ENTER that already drained its own intra-pass rows must
            // not be applied twice.
            if (previousEventSeq != preAppliedPassSeq) {
                for (long[] light
                        : lightingEvents.getOrDefault(previousEventSeq, List.of())) {
                    applyLight(live, light, lightCounters);
                }
            }
            long lightingReplays = lightCounters[0];
            long lightingRepeats = lightCounters[1];
            long lightingOutside = lightCounters[2];
            long capturedLighting = lightingEvents.values().stream()
                    .mapToLong(List::size).sum();
            if (lightingReplays + lightingRepeats + lightingOutside != capturedLighting) {
                throw new AssertionError(
                        "captured light completions not all at FEATURES cutpoints: captured="
                                + capturedLighting + " applied="
                                + (lightingReplays + lightingRepeats + lightingOutside));
            }
            System.out.println(
                    "LIVE_LIGHTING_RESULT captured=" + capturedLighting
                            + " replays=" + lightingReplays
                            + " repeatsAfterReload=" + lightingRepeats
                            + " outsideGrid=" + lightingOutside
                            + " mode="
                            + (replayLighting ? "oracle-schedule" : "native-immediate"));
            if (enters != origins.size() || live.centersCount() != origins.size()) {
                throw new AssertionError(
                        "FEATURES center count mismatch enters=" + enters
                        + " origins=" + origins.size()
                        + " live=" + live.centersCount());
            }

            long scoredChunks = 0;
            long scoredCells = 0;
            long diffs = 0;
            long leafDistanceDiffs = 0;
            List<String> bad = new ArrayList<>();

            for (long key : new TreeSet<>(coverage)) {
                int x = (int) key;
                int z = (int) (key >> 32);
                if (!traced(origins, x, z, 2)) continue;

                Ref ref = lastRef.get(key);
                Snapshot expected = read(root, ref);
                long chunkDiffs = 0;
                for (int i = 0; i < expected.ids.length; i++) {
                    BlockPos p = new BlockPos(
                            expected.x * 16 + (i & 15),
                            expected.minY + (i >> 8),
                            expected.z * 16 + ((i >> 4) & 15));
                    var actualState = store.get(p);
                    int actual = Block.getId(actualState);
                    if (actual != expected.ids[i]) {
                        var expectedState = Block.stateById(expected.ids[i]);
                        if (leafDistanceOnly(expectedState, actualState)) {
                            leafDistanceDiffs++;
                        }
                        chunkDiffs++;
                        if (chunkDiffs <= 3) {
                            System.out.println(
                                    "LIVE_STRUCTURE_DIFF chunk=" + x + "," + z
                                    + " pos=" + p
                                    + " vanilla=" + expectedState
                                    + " kestrel=" + actualState);
                        }
                    }
                }
                scoredChunks++;
                scoredCells += expected.ids.length;
                diffs += chunkDiffs;
                if (chunkDiffs != 0) bad.add(x + "," + z + "=" + chunkDiffs);
            }

            if (scoredChunks == 0) throw new AssertionError("no ring-2 chunks");
            System.out.println(
                    "LIVE_FEATURES_EXACT windows=" + featureWindows[0]
                    + " cells=" + featureCells[0]
                    + " diffs=0");
            System.out.println(
                    "LIVE_STRUCTURES_TRACE centers=" + origins.size()
                    + " carvers=" + live.carversCount()
                    + " scoredChunks=" + scoredChunks
                    + " cells=" + scoredCells
                    + " exactDiffs=" + diffs
                    + " leafDistanceDiffs=" + leafDistanceDiffs
                    + " elapsedMs=" + (System.nanoTime() - start) / 1_000_000L);
            if (diffs != 0) {
                throw new AssertionError(
                        "live structures exact parity failed: " + diffs
                        + " cells (" + String.join(" ", bad) + ")"
                        + "; leaf-distance subset=" + leafDistanceDiffs);
            }
            System.out.println(
                    "LiveStructuresTraceCheck: PASSED ("
                    + scoredChunks + " chunks, " + scoredCells + " cells)");
        }
    }

    /**
     * Exact stage-aligned check at LiveGenerator's production FEATURES
     * boundary. No tolerances and no captured values are fed back into
     * generation: COMPLETE snapshots are assertions only.
     */
    private static long compareFeaturesWindow(
            Path root, Event complete, Map<Long, ChunkAccess> grid)
            throws IOException, java.security.NoSuchAlgorithmException {
        long cells = 0;
        for (Ref ref : complete.refs) {
            Snapshot expected;
            try {
                expected = read(root, ref);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw e;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("cannot read snapshot " + ref.hash, e);
            }
            ChunkAccess actualChunk = grid.get(ChunkPos.pack(ref.x, ref.z));
            if (actualChunk == null) {
                throw new AssertionError(
                        "live FEATURES chunk unavailable origin="
                                + complete.x + "," + complete.z
                                + " chunk=" + ref.x + "," + ref.z);
            }
            long chunkDiffs = 0;
            for (int i = 0; i < expected.ids.length; i++) {
                BlockPos p = new BlockPos(
                        expected.x * 16 + (i & 15),
                        expected.minY + (i >> 8),
                        expected.z * 16 + ((i >> 4) & 15));
                var actualState = FeaturesTraceReplay.capturedState(actualChunk, p);
                int actual = Block.getId(actualState);
                if (actual != expected.ids[i]) {
                    chunkDiffs++;
                    if (chunkDiffs <= 3) {
                        System.out.println(
                                "LIVE_FEATURES_DIFF origin="
                                        + complete.x + "," + complete.z
                                        + " chunk=" + ref.x + "," + ref.z
                                        + " pos=" + p
                                        + " vanilla=" + Block.stateById(expected.ids[i])
                                        + " kestrel=" + actualState);
                    }
                }
            }
            cells += expected.ids.length;
            if (chunkDiffs != 0) {
                throw new AssertionError(
                        "live FEATURES exact mismatch origin="
                                + complete.x + "," + complete.z
                                + " chunk=" + ref.x + "," + ref.z
                                + " diffs=" + chunkDiffs);
            }
        }
        return cells;
    }

    private static void applyLight(LiveGenerator live, long[] light, long[] counters) {
        switch (live.initializeLightForGate((int) light[0], (int) light[1])) {
            case APPLIED -> counters[0]++;
            case REPEAT_AFTER_RELOAD -> counters[1]++;
            case OUTSIDE_GRID -> counters[2]++;
        }
    }

    /**
     * Apply pending captured completions in order, up to and including the
     * first one for chunk (x,z). Fails closed if the cutpoint has none: a
     * chunk saved at or past INITIALIZE_LIGHT must have been lit first.
     */
    private static void applyLightsThrough(
            LiveGenerator live, java.util.ArrayDeque<long[]> pending,
            int x, int z, long afterEventSeq, long[] counters) throws IOException {
        boolean found = false;
        for (long[] light : pending) {
            if (light[0] == x && light[1] == z) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IOException(
                    "captured reload at or past INITIALIZE_LIGHT for unlit chunk "
                            + x + "," + z + " afterEventSeq=" + afterEventSeq
                            + " with no captured light completion at that cutpoint");
        }
        while (true) {
            long[] light = pending.removeFirst();
            applyLight(live, light, counters);
            if (light[0] == x && light[1] == z) return;
        }
    }

    /**
     * Captured INITIALIZE_LIGHT completions keyed by afterEventSeq, each as
     * {x, z}, in capture order. Fails closed on anything but a plain OK row.
     */
    private static Map<Long, List<long[]>> readLightingEvents(Path root)
            throws IOException {
        Path file = root.resolve("lighting-events.tsv");
        List<String> lines = Files.readAllLines(file);
        String header = "afterEventSeq\torder\tactiveOriginX\tactiveOriginZ\tx\tz\toutcome";
        if (lines.isEmpty() || !lines.get(0).equals(header)) {
            throw new IOException("invalid lighting-events.tsv header in " + root);
        }
        Map<Long, List<long[]>> rows = new HashMap<>();
        long expectedOrder = 0;
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] f = line.split("\t", -1);
            if (f.length != 7 || Long.parseLong(f[1]) != ++expectedOrder) {
                throw new IOException("invalid lighting-event row: " + line);
            }
            if (!f[6].equals("OK")) {
                throw new IOException("captured INITIALIZE_LIGHT not OK: " + line);
            }
            rows.computeIfAbsent(Long.parseLong(f[0]), unused -> new ArrayList<>())
                    .add(new long[] {Integer.parseInt(f[4]), Integer.parseInt(f[5])});
        }
        return rows;
    }

    private static Map<Long, List<ChunkLoad>> readChunkLoads(Path root)
            throws IOException {
        Path file = root.resolve("chunk-loads.tsv");
        if (!Files.isRegularFile(file)) return null;
        List<String> lines = Files.readAllLines(file);
        String expectedHeader =
                "afterEventSeq\tactiveOriginX\tactiveOriginZ"
                        + "\tx\tz\tstatus\tchunkClass\treferenceSets";
        if (lines.isEmpty() || !lines.get(0).equals(expectedHeader)) {
            return null;
        }
        Map<Long, List<ChunkLoad>> out = new HashMap<>();
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] f = line.split("\t", -1);
            if (f.length != 8) {
                throw new IOException("invalid chunk-load row: " + line);
            }
            Integer activeX = f[1].isBlank() ? null : Integer.valueOf(f[1]);
            Integer activeZ = f[2].isBlank() ? null : Integer.valueOf(f[2]);
            if ((activeX == null) != (activeZ == null)) {
                throw new IOException("partial active origin in chunk-load row: " + line);
            }
            ChunkLoad load = new ChunkLoad(
                    Long.parseLong(f[0]),
                    activeX,
                    activeZ,
                    Integer.parseInt(f[3]),
                    Integer.parseInt(f[4]),
                    f[5],
                    f[6],
                    !f[7].isBlank());
            out.computeIfAbsent(load.afterEventSeq, unused -> new ArrayList<>())
                    .add(load);
        }
        return out;
    }

    private static boolean leafDistanceOnly(
            net.minecraft.world.level.block.state.BlockState expected,
            net.minecraft.world.level.block.state.BlockState actual) {
        var distance = net.minecraft.world.level.block.state.properties.BlockStateProperties.DISTANCE;
        if (expected.getBlock() != actual.getBlock()
                || !expected.hasProperty(distance)
                || !actual.hasProperty(distance)) {
            return false;
        }
        return expected.setValue(distance, actual.getValue(distance)).equals(actual);
    }

    private static boolean traced(Set<Long> origins, int x, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!origins.contains(ChunkPos.pack(x + dx, z + dz))) return false;
            }
        }
        return true;
    }

    private static Snapshot read(Path root, Ref ref) throws Exception {
        byte[] bytes;
        Path file = root.resolve("chunks/" + ref.hash + ".bin.gz");
        try (var in = new GZIPInputStream(Files.newInputStream(file))) {
            bytes = in.readAllBytes();
        }
        String actualHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!actualHash.equals(ref.hash)) {
            throw new IOException("snapshot checksum mismatch " + ref.hash);
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4b465431) throw new IOException("snapshot format");
            int x = in.readInt();
            int z = in.readInt();
            int minY = in.readInt();
            int height = in.readInt();
            int[] ids = new int[height * 256];
            for (int i = 0; i < ids.length; i++) ids[i] = in.readInt();
            if (in.read() != -1) throw new IOException("trailing snapshot data");
            return new Snapshot(x, z, minY, height, ids);
        }
    }
}
