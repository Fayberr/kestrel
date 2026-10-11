package dev.kestrel.harness;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

/** Opt-in, bootstrap-visible observer. No Minecraft dependency or world writes. */
public final class FeaturesTrace {
    private static Path root;
    // Optional capture-runner gate. When absent, tracing is armed immediately
    // for backwards compatibility. Fresh server captures set this to a file
    // created only after Vanilla has printed its readiness marker, avoiding
    // expensive spawn-preparation snapshots that are outside the requested
    // replay window.
    private static Path armFile;
    private static BufferedWriter events;
    private static BufferedWriter hidden;
    private static BufferedWriter lifecycle;
    private static BufferedWriter featureSeeds;
    private static BufferedWriter featurePlaces;
    private static BufferedWriter biomeFilters;
    private static BufferedWriter oreHeights;
    private static BufferedWriter oreCanPlace;
    private static BufferedWriter heightQueries;
    private static BufferedWriter structureData;
    private static BufferedWriter structureCalls;
    private static BufferedWriter structureCallTimes;
    private static BufferedWriter structureSources;
    private static BufferedWriter structureReferenceState;
    private static BufferedWriter chunkLoads;
    private static BufferedWriter lightingEvents;
    private static BufferedWriter lightStageEvents;
    private static BufferedWriter worldgenLightEvents;
    private static BufferedWriter lightExecutorOps;
    private static BufferedWriter lightScheduleOps;
    private static BufferedWriter lightData;
    private record FinalLight(long order, int x, int z, int min, int max, Object engine) {}
    private static final Map<Long, FinalLight> finalLights = new LinkedHashMap<>();
    private static boolean finalLightsFlushed;
    private static Path intraFeaturesTimelinePath;
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> intraFeaturesTimelineRows =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicLong intraFeaturesTimelineSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private static BufferedWriter blockEntityQueries;
    private static BufferedWriter spawnData;
    private static BufferedWriter spawnChunkDeltas;
    private static final ThreadLocal<SpawnCapture> currentSpawn = new ThreadLocal<>();
    private static long spawnSequence;
    private record SpawnCapture(
            long afterEventSeq,
            long afterInitializeLightOrder,
            long afterFullLightOrder,
            long afterWorldgenLightOrder,
            Integer activeOriginX,
            Integer activeOriginZ,
            int x,
            int z,
            long seed,
            String dimension,
            int beforeCount,
            java.util.LinkedHashMap<Long, Integer> targetBaselines,
            java.util.LinkedHashMap<Long, Object> targetRegions) {}
    private static final ThreadLocal<int[]> currentOrigin = new ThreadLocal<>();
    private static final ThreadLocal<long[]> currentFeatureSeed = new ThreadLocal<>();
    private static final ThreadLocal<Integer> currentBiomeCandidate = new ThreadLocal<>();
    private static final ThreadLocal<Integer> currentHeightQuery = new ThreadLocal<>();
    private static final ThreadLocal<Integer> currentStructureCall = new ThreadLocal<>();
    private static final ThreadLocal<Object[]> structureCallContext = new ThreadLocal<>();
    private static long sequence;
    private static final java.util.concurrent.atomic.AtomicLong lightingSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong fullLightSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong worldgenLightSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong lightBatchSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong lightExecutorOpSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private static final ThreadLocal<Long> currentLightBatch =
            ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Object> currentLightEngine = new ThreadLocal<>();
    private static volatile Object tracedLightEngine;
    private record LightingCut(long afterEventSeq, Integer activeX, Integer activeZ) {}
    /**
     * Lock-free snapshot used only by async light-completion callbacks.
     * Publish one immutable object after each durable ENTER/COMPLETE row so a
     * callback never waits behind expensive 3x3 snapshot capture before
     * deciding where it actually completed.
     */
    private static volatile LightingCut lightingCut = new LightingCut(0L, null, null);
    /** Guarded by this class' synchronized trace methods. */
    private static int[] globallyActiveOrigin;
    private static final Set<String> active = new HashSet<>();
    private static final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
    private static Method stateId, sectionState, holderChunk;
    private static Object carvers;

    public static synchronized void init(String directory) {
        try {
            root = Path.of(directory);
            String arm = System.getProperty("kestrel.featuresTraceArm");
            armFile = arm == null || arm.isBlank()
                    ? null
                    : Path.of(arm).toAbsolutePath();
            Files.createDirectories(root.resolve("chunks"));
            events = Files.newBufferedWriter(root.resolve("events.tsv"), StandardOpenOption.CREATE_NEW);
            hidden = Files.newBufferedWriter(root.resolve("hidden.tsv"), StandardOpenOption.CREATE_NEW);
            lifecycle = Files.newBufferedWriter(root.resolve("chunk-lifecycle.tsv"), StandardOpenOption.CREATE_NEW);
            featureSeeds = Files.newBufferedWriter(
                    root.resolve("feature-seeds.tsv"), StandardOpenOption.CREATE_NEW);
            featurePlaces = Files.newBufferedWriter(
                    root.resolve("feature-places.tsv"), StandardOpenOption.CREATE_NEW);
            biomeFilters = Files.newBufferedWriter(
                    root.resolve("biome-filter.tsv"), StandardOpenOption.CREATE_NEW);
            oreHeights = Files.newBufferedWriter(
                    root.resolve("ore-height.tsv"), StandardOpenOption.CREATE_NEW);
            oreCanPlace = Files.newBufferedWriter(
                    root.resolve("ore-can-place.tsv"), StandardOpenOption.CREATE_NEW);
            heightQueries = Files.newBufferedWriter(
                    root.resolve("height-queries.tsv"), StandardOpenOption.CREATE_NEW);
            structureData = Files.newBufferedWriter(
                    root.resolve("structure-data.tsv"), StandardOpenOption.CREATE_NEW);
            structureCalls = Files.newBufferedWriter(
                    root.resolve("structure-calls.tsv"), StandardOpenOption.CREATE_NEW);
            structureCallTimes = Files.newBufferedWriter(
                    root.resolve("structure-call-times.tsv"), StandardOpenOption.CREATE_NEW);
            structureSources = Files.newBufferedWriter(
                    root.resolve("structure-sources.tsv"), StandardOpenOption.CREATE_NEW);
            structureReferenceState = Files.newBufferedWriter(
                    root.resolve("structure-reference-state.tsv"), StandardOpenOption.CREATE_NEW);
            chunkLoads = Files.newBufferedWriter(
                    root.resolve("chunk-loads.tsv"), StandardOpenOption.CREATE_NEW);
            lightingEvents = Files.newBufferedWriter(
                    root.resolve("lighting-events.tsv"), StandardOpenOption.CREATE_NEW);
            lightStageEvents = Files.newBufferedWriter(
                    root.resolve("light-stage-events.tsv"), StandardOpenOption.CREATE_NEW);
            worldgenLightEvents = Files.newBufferedWriter(
                    root.resolve("worldgen-light-events.tsv"), StandardOpenOption.CREATE_NEW);
            lightExecutorOps = Files.newBufferedWriter(
                    root.resolve("light-executor-ops.tsv"), StandardOpenOption.CREATE_NEW);
            lightScheduleOps = Files.newBufferedWriter(
                    root.resolve("light-schedule-ops.tsv"), StandardOpenOption.CREATE_NEW);
            lightData = Files.newBufferedWriter(
                    root.resolve("light-data.tsv"), StandardOpenOption.CREATE_NEW);
            intraFeaturesTimelinePath = root.resolve("intra-features-timeline.tsv");
            Files.writeString(
                    intraFeaturesTimelinePath,
                    "order\tkind\tphase\tthread\tactiveOriginX\tactiveOriginZ"
                            + "\ttargetX\ttargetZ\tdecorationSeed\tfeatureIndex\tstepIndex\n",
                    StandardOpenOption.CREATE_NEW);
            Runtime.getRuntime().addShutdownHook(new Thread(
                    FeaturesTrace::flushIntraFeaturesTimeline,
                    "kestrel-intra-features-timeline-flush"));
            Runtime.getRuntime().addShutdownHook(new Thread(
                    FeaturesTrace::flushFinalLightData,
                    "kestrel-final-light-flush"));
            blockEntityQueries = Files.newBufferedWriter(
                    root.resolve("block-entity-queries.tsv"), StandardOpenOption.CREATE_NEW);
            spawnData = Files.newBufferedWriter(
                    root.resolve("spawn-data.tsv"), StandardOpenOption.CREATE_NEW);
            spawnChunkDeltas = Files.newBufferedWriter(
                    root.resolve("spawn-chunk-deltas.tsv"), StandardOpenOption.CREATE_NEW);
            events.write("seq\tphase\tthread\tx\tz\tseed\tdimension\tactive\twriteRadius\tdirectDependencies\tchunks\n");
            hidden.write("seq\tphase\tx\tz\tchunks\n");
            lifecycle.write("seq\tphase\tx\tz\tchunks\n");
            featureSeeds.write("thread\tx\tz\tdecorationSeed\tfeatureIndex\tstepIndex\n");
            featurePlaces.write("thread\tx\tz\tdecorationSeed\tfeatureIndex\tstepIndex\tfeature\n");
            biomeFilters.write("thread\toriginX\toriginZ\tdecorationSeed\tfeatureIndex\tstepIndex\tx\ty\tz\tresult\n");
            oreHeights.write("thread\toriginX\toriginZ\tcandidate\ttype\tx\tz\theight\n");
            oreCanPlace.write("thread\toriginX\toriginZ\tdecorationSeed\tfeatureIndex\tstepIndex\tx\ty\tz\tstate\tresult\n");
            heightQueries.write("originX\toriginZ\tindex\ttype\tx\tz\tresult\n");
            structureData.write(
                    "phase\toriginX\toriginZ\tchunkX\tchunkZ\tentities\tpendingBlockEntities\trealBlockEntityPositions\n");
            structureCalls.write(
                    "originX\toriginZ\tindex\ttargetX\ttargetZ\tbox"
                            + "\tbeforeHash\tafterHash"
                            + "\tbeforeEntities\tafterEntities"
                            + "\tbeforePending\tafterPending"
                            + "\tbeforeReal\tafterReal\n");
            // Game time seen by worldgen entities (Camel.finalizeSpawn reads
            // level.getLevel().getGameTime()). The main thread keeps ticking
            // while a worker places a structure, so both ends are recorded.
            structureCallTimes.write(
                    "originX\toriginZ\tindex\tgameTimeEnter\tgameTimeExit\n");
            structureSources.write(
                    "originX\toriginZ\tindex\ttargetX\ttargetZ\tsourceX\tsourceZ"
                            + "\tstructureClass\tbox\n");
            structureReferenceState.write(
                    "originX\toriginZ\tindex\ttargetX\ttargetZ\tsourceX\tsourceZ"
                            + "\tchunkClass\tsetClass\tsize\tn\tminN\titeratorOrder\n");
            chunkLoads.write(
                    "afterEventSeq\tactiveOriginX\tactiveOriginZ"
                            + "\tx\tz\tstatus\tchunkClass\treferenceSets\n");
            lightingEvents.write(
                    "afterEventSeq\torder\tactiveOriginX\tactiveOriginZ"
                            + "\tx\tz\toutcome\n");
            lightStageEvents.write(
                    "afterEventSeq\torder\tactiveOriginX\tactiveOriginZ"
                            + "\tx\tz\toutcome\n");
            worldgenLightEvents.write(
                    "afterEventSeq\torder\tstage\tactiveOriginX\tactiveOriginZ"
                            + "\tx\tz\toutcome\tbatch\n");
            lightExecutorOps.write(
                    "order\tbatch\tkind\tafterEventSeq\tactiveOriginX\tactiveOriginZ"
                            + "\tx\ty\tz\tflag\tengine\n");
            lightScheduleOps.write(
                    "afterEventSeq\tactiveOriginX\tactiveOriginZ\tx\tz\tengine\n");
            lightData.write("order\tx\tz\tminLightSection\tmaxLightSection"
                    + "\tblockSha256\tskySha256\n");
            blockEntityQueries.write(
                    "originX\toriginZ\tdecorationSeed\tfeatureIndex\tstepIndex"
                            + "\tx\ty\tz\tresultClass\n");
            spawnData.write(
                    "seq\tafterEventSeq"
                            + "\tafterInitializeLightOrder\tafterFullLightOrder"
                            + "\tafterWorldgenLightOrder"
                            + "\tactiveOriginX\tactiveOriginZ"
                            + "\tthread\tx\tz\tseed\tdimension"
                            + "\tbeforeCount\tafterCount\tdeltaEntities\n");
            spawnChunkDeltas.write(
                    "spawnSeq\ttargetX\ttargetZ\tbeforeCount\tafterCount\tdeltaEntities\n");
            events.flush();
            hidden.flush();
            lifecycle.flush();
            featureSeeds.flush();
            featurePlaces.flush();
            biomeFilters.flush();
            oreHeights.flush();
            oreCanPlace.flush();
            heightQueries.flush();
            structureData.flush();
            structureCalls.flush();
            structureCallTimes.flush();
            structureSources.flush();
            structureReferenceState.flush();
            chunkLoads.flush();
            lightingEvents.flush();
            lightStageEvents.flush();
            worldgenLightEvents.flush();
            lightData.flush();
            blockEntityQueries.flush();
            spawnData.flush();
            spawnChunkDeltas.flush();
        } catch (Exception e) { throw new IllegalStateException("Cannot create fresh trace", e); }
    }

    private static boolean armed() {
        return armFile == null || Files.exists(armFile);
    }

    private static Object call(Object target, String name) throws Exception {
        return target.getClass().getMethod(name).invoke(target);
    }

    /**
     * Capture exactly the entity-buffer delta produced by Vanilla's SPAWN
     * chunk-status stage. The cutpoint is serialized with FEATURES record()
     * through the same class monitor, so afterEventSeq and activeOrigin are
     * a single coherent observation rather than two racy reads.
     */
    public static synchronized void spawnEnter(
            Object context, Object step, Object cache, Object chunk) {
        if (!armed()) {
            currentSpawn.remove();
            return;
        }
        try {
            Object level = call(context, "level");
            Object pos = call(chunk, "getPos");
            Object rawEntities = call(chunk, "getEntities");
            if (!(rawEntities instanceof List<?> entities)) {
                throw new IllegalStateException(
                        "SPAWN center getEntities() did not return a List: " + rawEntities);
            }
            Integer activeX = globallyActiveOrigin == null
                    ? null : globallyActiveOrigin[0];
            Integer activeZ = globallyActiveOrigin == null
                    ? null : globallyActiveOrigin[1];
            currentSpawn.set(new SpawnCapture(
                    sequence,
                    lightingSequence.get(),
                    fullLightSequence.get(),
                    worldgenLightSequence.get(),
                    activeX,
                    activeZ,
                    (Integer) call(pos, "x"),
                    (Integer) call(pos, "z"),
                    (Long) call(level, "getSeed"),
                    String.valueOf(call(level, "dimension")),
                    entities.size(),
                    new java.util.LinkedHashMap<>(),
                    new java.util.LinkedHashMap<>()));
        } catch (RuntimeException e) {
            currentSpawn.remove();
            throw e;
        } catch (Exception e) {
            currentSpawn.remove();
            throw new IllegalStateException("cannot establish SPAWN oracle cut", e);
        }
    }

    /**
     * Called after WorldGenRegion.addFreshEntity(Entity). This observes the
     * actual target ProtoChunk after Vanilla has serialized the entity, so a
     * spawn group crossing a chunk edge is captured without assuming all
     * additions belong to the generateSpawn center.
     */
    public static synchronized void spawnEntityAdded(
            boolean added, Object region, Object entity) {
        if (!added) return;
        SpawnCapture capture = currentSpawn.get();
        if (capture == null) return;
        try {
            int blockX = (Integer) call(entity, "getBlockX");
            int blockZ = (Integer) call(entity, "getBlockZ");
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            long key = ((long) chunkZ << 32) | (chunkX & 0xffffffffL);
            Object targetChunk = region.getClass()
                    .getMethod("getChunk", int.class, int.class)
                    .invoke(region, chunkX, chunkZ);
            Object rawEntities = call(targetChunk, "getEntities");
            if (!(rawEntities instanceof List<?> entities) || entities.isEmpty()) {
                throw new IllegalStateException(
                        "successful WorldGenRegion.addFreshEntity did not append entity at "
                                + chunkX + "," + chunkZ);
            }
            capture.targetBaselines().putIfAbsent(key, entities.size() - 1);
            capture.targetRegions().putIfAbsent(key, region);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot capture SPAWN target chunk", e);
        }
    }

    public static synchronized void spawnExit(
            Object future, Object context, Object step, Object cache, Object chunk) {
        SpawnCapture capture = currentSpawn.get();
        if (capture == null) {
            return;
        }
        try {
            CompletableFuture<?> f = (CompletableFuture<?>) future;
            if (!f.isDone() || f.isCompletedExceptionally()) {
                throw new IllegalStateException(
                        "generateSpawn returned an incomplete/failed future at "
                                + capture.x() + "," + capture.z());
            }
            Object rawEntities = call(chunk, "getEntities");
            if (!(rawEntities instanceof List<?> entities)) {
                throw new IllegalStateException(
                        "SPAWN center getEntities() did not return a List: " + rawEntities);
            }
            if (entities.size() < capture.beforeCount()) {
                throw new IllegalStateException(
                        "SPAWN entity buffer shrank at "
                                + capture.x() + "," + capture.z()
                                + " before=" + capture.beforeCount()
                                + " after=" + entities.size());
            }
            List<?> delta = new ArrayList<>(
                    entities.subList(capture.beforeCount(), entities.size()));
            long completedSpawnSeq = ++spawnSequence;
            spawnData.write(completedSpawnSeq
                    + "\t" + capture.afterEventSeq()
                    + "\t" + capture.afterInitializeLightOrder()
                    + "\t" + capture.afterFullLightOrder()
                    + "\t" + capture.afterWorldgenLightOrder()
                    + "\t" + (capture.activeOriginX() == null ? "" : capture.activeOriginX())
                    + "\t" + (capture.activeOriginZ() == null ? "" : capture.activeOriginZ())
                    + "\t" + Thread.currentThread().getName()
                    + "\t" + capture.x()
                    + "\t" + capture.z()
                    + "\t" + capture.seed()
                    + "\t" + capture.dimension()
                    + "\t" + capture.beforeCount()
                    + "\t" + entities.size()
                    + "\t" + oneLine(delta)
                    + "\n");
            spawnData.flush();

            for (var entry : capture.targetBaselines().entrySet()) {
                long key = entry.getKey();
                int chunkX = (int) key;
                int chunkZ = (int) (key >> 32);
                Object region = capture.targetRegions().get(key);
                if (region == null) {
                    throw new IllegalStateException(
                            "SPAWN target has no owning WorldGenRegion at "
                                    + chunkX + "," + chunkZ);
                }
                Object targetChunk = region.getClass()
                        .getMethod("getChunk", int.class, int.class)
                        .invoke(region, chunkX, chunkZ);
                Object rawTargetEntities = call(targetChunk, "getEntities");
                if (!(rawTargetEntities instanceof List<?> targetEntities)) {
                    throw new IllegalStateException(
                            "SPAWN target getEntities() did not return a List at "
                                    + chunkX + "," + chunkZ);
                }
                int targetBefore = entry.getValue();
                if (targetEntities.size() <= targetBefore) {
                    throw new IllegalStateException(
                            "SPAWN target entity list did not retain captured addition at "
                                    + chunkX + "," + chunkZ
                                    + " before=" + targetBefore
                                    + " after=" + targetEntities.size());
                }
                List<?> targetDelta = new ArrayList<>(
                        targetEntities.subList(targetBefore, targetEntities.size()));
                spawnChunkDeltas.write(
                        completedSpawnSeq
                                + "\t" + chunkX
                                + "\t" + chunkZ
                                + "\t" + targetBefore
                                + "\t" + targetEntities.size()
                                + "\t" + oneLine(targetDelta)
                                + "\n");
            }
            spawnChunkDeltas.flush();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot write SPAWN oracle row", e);
        } finally {
            currentSpawn.remove();
        }
    }

    public static void enter(Object context, Object step, Object cache, Object chunk) {
        if (!armed()) {
            currentOrigin.remove();
            currentFeatureSeed.remove();
            currentBiomeCandidate.remove();
            currentHeightQuery.remove();
            currentStructureCall.remove();
            structureCallContext.remove();
            return;
        }
        try {
            Object pos = call(chunk, "getPos");
            currentOrigin.set(new int[]{(Integer) call(pos, "x"), (Integer) call(pos, "z")});
            currentHeightQuery.set(0);
            currentStructureCall.set(0);
            structureCallContext.remove();
            record("ENTER", context, step, cache, chunk);
        } catch (RuntimeException e) {
            currentOrigin.remove();
            throw e;
        } catch (Exception e) {
            currentOrigin.remove();
            throw new IllegalStateException("cannot establish FEATURES origin", e);
        }
    }

    public static void exit(Object future, Object context, Object step, Object cache, Object chunk) {
        if (currentOrigin.get() == null) {
            return;
        }
        try {
            // 26.2 generateFeatures returns completedFuture after all synchronous work.
            // Refuse to call a method return "completion" on a future that is not done.
            CompletableFuture<?> f = (CompletableFuture<?>) future;
            record(f.isDone() && !f.isCompletedExceptionally() ? "COMPLETE" : "INVALID_FUTURE",
                    context, step, cache, chunk);
        } finally {
            currentOrigin.remove();
            currentFeatureSeed.remove();
            currentBiomeCandidate.remove();
            currentHeightQuery.remove();
            currentStructureCall.remove();
            structureCallContext.remove();
        }
    }

    public static synchronized void featureSeed(long decorationSeed, int featureIndex, int stepIndex) {
        int[] origin = currentOrigin.get();
        if (origin == null) return;
        currentFeatureSeed.set(new long[]{decorationSeed, featureIndex, stepIndex});
        currentBiomeCandidate.set(0);
        try {
            featureSeeds.write(Thread.currentThread().getName()
                    + "\t" + origin[0] + "\t" + origin[1]
                    + "\t" + decorationSeed
                    + "\t" + featureIndex
                    + "\t" + stepIndex + "\n");
            featureSeeds.flush();
        } catch (Exception e) {
            throw new IllegalStateException("cannot write feature seed trace", e);
        }
    }

    public static synchronized void featurePlace(Object feature) {
        int[] origin = currentOrigin.get();
        long[] seed = currentFeatureSeed.get();
        if (origin == null || seed == null) return;
        try {
            String description = String.valueOf(feature).replace('\t', ' ').replace('\n', ' ');
            featurePlaces.write(Thread.currentThread().getName()
                    + "\t" + origin[0] + "\t" + origin[1]
                    + "\t" + seed[0]
                    + "\t" + seed[1]
                    + "\t" + seed[2]
                    + "\t" + description + "\n");
            featurePlaces.flush();
        } catch (Exception e) {
            throw new IllegalStateException("cannot write feature placement trace", e);
        }
    }

    public static void featurePlaceTimeline(String phase) {
        if (!armed()) return;
        int[] origin = currentOrigin.get();
        long[] seed = currentFeatureSeed.get();
        if (origin == null || seed == null) return;
        long order = intraFeaturesTimelineSequence.incrementAndGet();
        intraFeaturesTimelineRows.add(
                order + "\tFEATURE\t" + phase
                        + "\t" + Thread.currentThread().getName()
                        + "\t" + origin[0] + "\t" + origin[1]
                        + "\t\t"
                        + "\t" + seed[0]
                        + "\t" + seed[1]
                        + "\t" + seed[2] + "\n");
    }

    public static void initializeLightTaskTimeline(Object chunk, String phase) {
        if (!armed()) return;
        long order = intraFeaturesTimelineSequence.incrementAndGet();
        try {
            Object pos = call(chunk, "getPos");
            int x = (Integer) call(pos, "x");
            int z = (Integer) call(pos, "z");
            int[] origin = currentOrigin.get();
            intraFeaturesTimelineRows.add(
                    order + "\tINITIALIZE_LIGHT_TASK\t" + phase
                            + "\t" + Thread.currentThread().getName()
                            + "\t" + (origin == null ? "" : origin[0])
                            + "\t" + (origin == null ? "" : origin[1])
                            + "\t" + x + "\t" + z
                            + "\t\t\t\n");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot capture INITIALIZE_LIGHT task timeline", e);
        }
    }

    public static synchronized void initializeLightScheduled(Object context, Object chunk) {
        if (!armed()) return;
        LightingCut cut = lightingCut;
        try {
            Object pos = call(chunk, "getPos");
            Object engine = call(context, "lightEngine");
            lightScheduleOps.write(
                    cut.afterEventSeq()
                            + "\t" + (cut.activeX() == null ? "" : cut.activeX())
                            + "\t" + (cut.activeZ() == null ? "" : cut.activeZ())
                            + "\t" + call(pos, "x") + "\t" + call(pos, "z")
                            + "\t" + System.identityHashCode(engine) + "\n");
            lightScheduleOps.flush();
        } catch (Exception e) {
            throw new IllegalStateException("cannot capture initialize-light scheduling", e);
        }
    }

    public static void initializeLightPreTimeline(Object pos, String phase) {
        if (!armed()) return;
        LightingCut cut = lightingCut;
        long order = intraFeaturesTimelineSequence.incrementAndGet();
        try {
            int x = (Integer) call(pos, "x");
            int z = (Integer) call(pos, "z");
            intraFeaturesTimelineRows.add(
                    order + "\tINITIALIZE_LIGHT_PRE\t" + phase
                            + "\t" + Thread.currentThread().getName()
                            + "\t" + (cut.activeX() == null ? "" : cut.activeX())
                            + "\t" + (cut.activeZ() == null ? "" : cut.activeZ())
                            + "\t" + x + "\t" + z
                            + "\t\t\t\n");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot capture INITIALIZE_LIGHT PRE_UPDATE timeline", e);
        }
    }

    private static void flushIntraFeaturesTimeline() {
        Path path = intraFeaturesTimelinePath;
        if (path == null) return;
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardOpenOption.APPEND)) {
            String row;
            while ((row = intraFeaturesTimelineRows.poll()) != null) {
                out.write(row);
            }
        } catch (Exception e) {
            System.err.println(
                    "[kestrel-harness] cannot flush intra-features timeline: " + e);
            e.printStackTrace(System.err);
        }
    }

    public static synchronized void biomeFilter(Object pos, boolean result) {
        int[] origin = currentOrigin.get();
        long[] seed = currentFeatureSeed.get();
        if (origin == null || seed == null) return;
        if (origin[0] != 0 || origin[1] != 2 || seed[1] != 10 || seed[2] != 6) return;
        try {
            int candidate = currentBiomeCandidate.get() == null
                    ? 1 : currentBiomeCandidate.get() + 1;
            currentBiomeCandidate.set(candidate);
            int x = (Integer) call(pos, "getX");
            int y = (Integer) call(pos, "getY");
            int z = (Integer) call(pos, "getZ");
            biomeFilters.write(Thread.currentThread().getName()
                    + "\t" + origin[0] + "\t" + origin[1]
                    + "\t" + seed[0]
                    + "\t" + seed[1]
                    + "\t" + seed[2]
                    + "\t" + x + "\t" + y + "\t" + z
                    + "\t" + result + "\n");
            biomeFilters.flush();
        } catch (Exception e) {
            throw new IllegalStateException("cannot write biome filter trace", e);
        }
    }

    public static synchronized void heightQuery(
            Object region, Object type, int x, int z, int height) {
        int[] origin = currentOrigin.get();
        if (origin == null) return;
        try {
            int index = currentHeightQuery.get() == null
                    ? 1 : currentHeightQuery.get() + 1;
            currentHeightQuery.set(index);
            String typeText = String.valueOf(type).replace('\t', ' ');
            heightQueries.write(origin[0] + "\t" + origin[1]
                    + "\t" + index
                    + "\t" + typeText
                    + "\t" + x + "\t" + z + "\t" + height + "\n");
            heightQueries.flush();

            // height-query-chunks.tsv is deliberately no longer written. It
            // cost roughly 10 GB per capture, because it re-entered the
            // WorldGenRegion reflectively and flushed a line for every single
            // height query, and nothing in this repo ever read it back. The
            // post-read observation it carried (which holder or ProtoChunk
            // satisfied a height call) is reconstructible from the captured
            // chunk-lifecycle and chunk-loads traces, and git history has the
            // writer if that ever stops being true.

            // Keep the focused coal diagnostic alongside the general replay input.
            long[] seed = currentFeatureSeed.get();
            Integer candidate = currentBiomeCandidate.get();
            if (seed != null && candidate != null
                    && origin[0] == 0 && origin[1] == 2
                    && seed[1] == 10 && seed[2] == 6) {
                oreHeights.write(Thread.currentThread().getName()
                        + "\t" + origin[0] + "\t" + origin[1]
                        + "\t" + candidate
                        + "\t" + typeText
                        + "\t" + x + "\t" + z + "\t" + height + "\n");
                oreHeights.flush();
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot write height query trace", e);
        }
    }

    public static synchronized void oreCanPlace(Object state, Object pos, boolean result) {
        int[] origin = currentOrigin.get();
        long[] seed = currentFeatureSeed.get();
        if (origin == null || seed == null) return;
        if (origin[0] != 0 || origin[1] != 2 || seed[1] != 10 || seed[2] != 6) return;
        try {
            int x = (Integer) call(pos, "getX");
            int y = (Integer) call(pos, "getY");
            int z = (Integer) call(pos, "getZ");
            String stateText = String.valueOf(state).replace('\t', ' ').replace('\n', ' ');
            oreCanPlace.write(Thread.currentThread().getName()
                    + "\t" + origin[0] + "\t" + origin[1]
                    + "\t" + seed[0]
                    + "\t" + seed[1]
                    + "\t" + seed[2]
                    + "\t" + x + "\t" + y + "\t" + z
                    + "\t" + stateText
                    + "\t" + result + "\n");
            oreCanPlace.flush();
        } catch (Exception e) {
            throw new IllegalStateException("cannot write ore canPlace trace", e);
        }
    }

    public static synchronized void blockEntityQuery(
            Object pos, Object result) {
        int[] origin = currentOrigin.get();
        if (origin == null) return;
        try {
            long[] seed = currentFeatureSeed.get();
            long decorationSeed = seed == null ? Long.MIN_VALUE : seed[0];
            long featureIndex = seed == null ? Long.MIN_VALUE : seed[1];
            long stepIndex = seed == null ? Long.MIN_VALUE : seed[2];
            int x = (Integer) call(pos, "getX");
            int y = (Integer) call(pos, "getY");
            int z = (Integer) call(pos, "getZ");
            String resultClass =
                    result == null ? "null" : result.getClass().getName();
            blockEntityQueries.write(
                    origin[0] + "\t" + origin[1]
                            + "\t" + decorationSeed
                            + "\t" + featureIndex
                            + "\t" + stepIndex
                            + "\t" + x + "\t" + y + "\t" + z
                            + "\t" + resultClass + "\n");
            blockEntityQueries.flush();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot capture block-entity query", e);
        }
    }

    public static synchronized void structurePlaceEnter(
            Object start, Object level, Object chunkPos) {
        int[] origin = currentOrigin.get();
        if (origin == null) return;
        if (structureCallContext.get() != null) {
            throw new IllegalStateException("nested StructureStart.placeInChunk trace");
        }
        try {
            int index = currentStructureCall.get() == null
                    ? 1 : currentStructureCall.get() + 1;
            currentStructureCall.set(index);
            int targetX = (Integer) call(chunkPos, "x");
            int targetZ = (Integer) call(chunkPos, "z");
            Object chunk = level.getClass()
                    .getMethod("getChunk", int.class, int.class)
                    .invoke(level, targetX, targetZ);
            String box = oneLine(call(start, "getBoundingBox"));
            Object sourcePos = call(start, "getChunkPos");
            int sourceX = (Integer) call(sourcePos, "x");
            int sourceZ = (Integer) call(sourcePos, "z");
            Object structure = call(start, "getStructure");
            structureSources.write(
                    origin[0] + "\t" + origin[1]
                            + "\t" + index
                            + "\t" + targetX + "\t" + targetZ
                            + "\t" + sourceX + "\t" + sourceZ
                            + "\t" + structure.getClass().getName()
                            + "\t" + box + "\n");
            structureSources.flush();
            writeStructureReferenceState(
                    origin, index, targetX, targetZ, sourceX, sourceZ,
                    chunk, structure);
            Object[] content = chunkContent(chunk);
            // Freeze the entry content NOW. ProtoChunk exposes live mutable
            // collections for entities/block entities; retaining those object
            // references until structurePlaceExit() makes the recorded
            // "before" state retroactively include mutations performed by
            // placeInChunk().
            structureCallContext.set(new Object[]{
                    index, targetX, targetZ, chunk, box,
                    snapshot(chunk),
                    oneLine(content[0]),
                    oneLine(content[1]),
                    oneLine(content[2]),
                    gameTime(level),
                    level
            });
        } catch (Exception e) {
            throw new IllegalStateException("cannot capture structure placement entry", e);
        }
    }

    /** WorldGenRegion.getLevel().getGameTime(), the clock worldgen entities read. */
    private static long gameTime(Object worldGenLevel) throws Exception {
        return (Long) call(call(worldGenLevel, "getLevel"), "getGameTime");
    }

    public static synchronized void structurePlaceExit() {
        int[] origin = currentOrigin.get();
        Object[] context = structureCallContext.get();
        if (origin == null || context == null) return;
        try {
            int index = (Integer) context[0];
            int targetX = (Integer) context[1];
            int targetZ = (Integer) context[2];
            Object chunk = context[3];
            String box = (String) context[4];
            String beforeHash = (String) context[5];
            String beforeEntities = (String) context[6];
            String beforePending = (String) context[7];
            String beforeReal = (String) context[8];
            long gameTimeEnter = (Long) context[9];
            Object level = context[10];
            Object[] after = chunkContent(chunk);
            String afterHash = snapshot(chunk);
            structureCalls.write(
                    origin[0] + "\t" + origin[1]
                            + "\t" + index
                            + "\t" + targetX + "\t" + targetZ
                            + "\t" + box
                            + "\t" + beforeHash + "\t" + afterHash
                            + "\t" + beforeEntities + "\t" + oneLine(after[0])
                            + "\t" + beforePending + "\t" + oneLine(after[1])
                            + "\t" + beforeReal + "\t" + oneLine(after[2])
                            + "\n");
            structureCalls.flush();
            structureCallTimes.write(
                    origin[0] + "\t" + origin[1]
                            + "\t" + index
                            + "\t" + gameTimeEnter
                            + "\t" + gameTime(level)
                            + "\n");
            structureCallTimes.flush();
        } catch (Exception e) {
            throw new IllegalStateException("cannot capture structure placement exit", e);
        } finally {
            structureCallContext.remove();
        }
    }

    private static void writeStructureReferenceState(
            int[] origin,
            int index,
            int targetX,
            int targetZ,
            int sourceX,
            int sourceZ,
            Object chunk,
            Object structure) throws Exception {
        Method getter = null;
        for (Method method : chunk.getClass().getMethods()) {
            if (method.getName().equals("getReferencesForStructure")
                    && method.getParameterCount() == 1) {
                getter = method;
                break;
            }
        }
        if (getter == null) {
            throw new NoSuchMethodException(
                    chunk.getClass().getName() + ".getReferencesForStructure");
        }
        Object refs = getter.invoke(chunk, structure);
        int size = ((java.util.Collection<?>) refs).size();
        int n = intField(refs, "n");
        int minN = intField(refs, "minN");
        if (n < 0 || minN < 0) {
            throw new IllegalStateException(
                    "cannot inspect structure reference table layout: class="
                            + refs.getClass().getName()
                            + " n=" + n + " minN=" + minN);
        }
        StringBuilder order = new StringBuilder();
        for (Object boxed : (Iterable<?>) refs) {
            long packed = ((Number) boxed).longValue();
            int x = (int) packed;
            int z = (int) (packed >> 32);
            if (order.length() != 0) order.append(';');
            order.append(x).append(',').append(z)
                    .append('(').append(packed).append(')');
        }
        structureReferenceState.write(
                origin[0] + "\t" + origin[1]
                        + "\t" + index
                        + "\t" + targetX + "\t" + targetZ
                        + "\t" + sourceX + "\t" + sourceZ
                        + "\t" + chunk.getClass().getName()
                        + "\t" + refs.getClass().getName()
                        + "\t" + size
                        + "\t" + n
                        + "\t" + minN
                        + "\t" + order + "\n");
        structureReferenceState.flush();
    }

    /**
     * Observe a ProtoChunk reconstructed by SerializableChunkData.read.
     * This is diagnostic metadata only: it proves whether a structure
     * reference set passed through Vanilla's disk/serialized-chunk path
     * before FEATURES and records the resulting fastutil table layout.
     */
    public static synchronized void chunkRead(Object pos, Object chunk) {
        if (!armed()) return;
        try {
            int x = (Integer) call(pos, "x");
            int z = (Integer) call(pos, "z");
            Object status = call(chunk, "getPersistedStatus");
            Object refsMap = call(chunk, "getAllReferences");

            StringBuilder sets = new StringBuilder();
            for (Object rawEntry : ((java.util.Map<?, ?>) refsMap).entrySet()) {
                java.util.Map.Entry<?, ?> entry =
                        (java.util.Map.Entry<?, ?>) rawEntry;
                Object refs = entry.getValue();
                if (!(refs instanceof Iterable<?> iterable)) continue;
                if (sets.length() != 0) sets.append('|');
                sets.append(oneLine(entry.getKey()))
                        .append(':').append(refs.getClass().getName())
                        .append(":size=").append(((java.util.Collection<?>) refs).size())
                        .append(":n=").append(intField(refs, "n"))
                        .append(":minN=").append(intField(refs, "minN"))
                        .append(":order=");
                boolean first = true;
                for (Object boxed : iterable) {
                    long packed = ((Number) boxed).longValue();
                    int rx = (int) packed;
                    int rz = (int) (packed >> 32);
                    if (!first) sets.append(';');
                    sets.append(rx).append(',').append(rz)
                            .append('(').append(packed).append(')');
                    first = false;
                }
            }

            // SerializableChunkData.read may execute on a different worker
            // from generateFeatures. ThreadLocal currentOrigin is therefore
            // insufficient for deciding whether this reload happened inside
            // an active FEATURES call. chunkRead() and record() synchronize
            // on the same monitor, so this global origin gives an atomic
            // boundary relative to the ENTER/COMPLETE sequence counter.
            int[] origin = globallyActiveOrigin;
            chunkLoads.write(
                    sequence
                            + "\t" + (origin == null ? "" : origin[0])
                            + "\t" + (origin == null ? "" : origin[1])
                            + "\t" + x + "\t" + z
                            + "\t" + oneLine(status)
                            + "\t" + chunk.getClass().getName()
                            + "\t" + sets + "\n");
            chunkLoads.flush();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot capture SerializableChunkData.read", e);
        }
    }

    /**
     * Observe completion of ChunkStep.apply for the INITIALIZE_LIGHT target.
     * ChunkStep chains completeChunkGeneration via thenApply after the raw
     * light-engine future; that continuation is what publishes the persisted
     * INITIALIZE_LIGHT status. Recording the returned apply future therefore
     * captures the real Vanilla lifecycle-completion cutpoint.
     *
     * The row is scheduling input only: no captured block/light/status answer
     * is fed back into Kestrel.
     */
    public static void observeChunkStepCompletion(
            Object future, Object step, Object chunk, Object context) {
        if (!armed()) return;
        final int x;
        final int z;
        final String statusName;
        try {
            Object targetStatus = call(step, "targetStatus");
            statusName = String.valueOf(call(targetStatus, "getName"));
            if (!"minecraft:initialize_light".equals(statusName)
                    && !"minecraft:light".equals(statusName)) {
                return;
            }
            Object pos = call(chunk, "getPos");
            x = (Integer) call(pos, "x");
            z = (Integer) call(pos, "z");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot identify light-related ChunkStep", e);
        }
        ((CompletableFuture<?>) future).whenComplete((ignored, failure) -> {
            // Freeze the FEATURES cutpoint before taking the writer monitor.
            // Completion order is allocated at the actual callback edge, not
            // after potentially expensive trace serialization.
            LightingCut cut = lightingCut;
            long worldgenOrder = worldgenLightSequence.incrementAndGet();
            recordWorldgenLightCompletion(
                    cut, worldgenOrder, statusName, x, z, failure);
            if ("minecraft:initialize_light".equals(statusName)) {
                long order = lightingSequence.incrementAndGet();
                recordInitializeLightCompletion(cut, order, x, z, failure);
            } else {
                long order = fullLightSequence.incrementAndGet();
                recordFullLightCompletion(cut, order, x, z, failure);
                if (failure == null) {
                    recordLightData(order, x, z, context);
                }
            }
        });
    }

    public static void lightBatchEnter(Object engine) {
        currentLightEngine.set(engine);
        currentLightBatch.set(lightBatchSequence.incrementAndGet());
        recordLightExecutorOp("BATCH_ENTER", null, false, null);
    }

    public static void lightBatchExit(Object engine) {
        recordLightExecutorOp("BATCH_EXIT", null, false, null);
        currentLightBatch.remove();
        currentLightEngine.remove();
    }

    public static void lightExecutorChunkOp(String kind, Object pos, boolean flag) {
        recordLightExecutorOp(kind, pos, false, flag);
    }

    public static void lightExecutorInitializeOp(Object engine, Object chunk, Object pos) {
        if (!armed()) return;
        tracedLightEngine = engine;
        recordLightExecutorOp("INITIALIZE_LIGHT", pos, false, false);
    }

    public static void lightExecutorUnloadOp(Object engine, Object pos) {
        if (engine != tracedLightEngine) return;
        recordLightExecutorOp("UNLOAD_CHUNK", pos, false, false);
    }

    public static void lightExecutorBlockOp(String kind, Object pos) {
        recordLightExecutorOp(kind, pos, true, null);
    }

    public static void lightExecutorSectionOp(String kind, Object pos, boolean flag) {
        recordLightExecutorOp(kind, pos, false, flag);
    }

    private static synchronized void recordLightExecutorOp(
            String kind, Object pos, boolean blockPos, Boolean flag) {
        if (!armed()) return;
        LightingCut cut = lightingCut;
        try {
            String x = "";
            String y = "";
            String z = "";
            if (pos != null) {
                x = String.valueOf((Integer) call(pos, blockPos ? "getX" : "x"));
                if (blockPos) {
                    y = String.valueOf((Integer) call(pos, "getY"));
                } else {
                    try {
                        y = String.valueOf((Integer) call(pos, "y"));
                    } catch (NoSuchMethodException ignored) {
                        // ChunkPos has no y component.
                    }
                }
                z = String.valueOf((Integer) call(pos, blockPos ? "getZ" : "z"));
            }
            lightExecutorOps.write(
                    lightExecutorOpSequence.incrementAndGet()
                            + "\t" + currentLightBatch.get()
                            + "\t" + kind
                            + "\t" + cut.afterEventSeq()
                            + "\t" + (cut.activeX() == null ? "" : cut.activeX())
                            + "\t" + (cut.activeZ() == null ? "" : cut.activeZ())
                            + "\t" + x + "\t" + y + "\t" + z
                            + "\t" + (flag == null ? "" : flag)
                            + "\t" + System.identityHashCode(currentLightEngine.get()) + "\n");
            lightExecutorOps.flush();
        } catch (Exception e) {
            throw new IllegalStateException("cannot capture light executor operation", e);
        }
    }

    private static synchronized void recordLightData(
            long order, int x, int z, Object context) {
        if (!armed()) return;
        try {
            Object engine = call(context, "lightEngine");
            int min = (Integer) call(engine, "getMinLightSection");
            int max = (Integer) call(engine, "getMaxLightSection");
            ClassLoader loader = engine.getClass().getClassLoader();
            Class<?> lightLayer = Class.forName("net.minecraft.world.level.LightLayer", true, loader);
            Object block = Enum.valueOf((Class) lightLayer, "BLOCK");
            Object sky = Enum.valueOf((Class) lightLayer, "SKY");
            String blockHash = lightDigest(engine, block, x, z, min, max);
            String skyHash = lightDigest(engine, sky, x, z, min, max);
            finalLights.put(((long) z << 32) | (x & 0xffffffffL),
                    new FinalLight(order, x, z, min, max, engine));
            lightData.write(order + "\t" + x + "\t" + z + "\t" + min + "\t" + max
                    + "\t" + blockHash + "\t" + skyHash + "\n");
            lightData.flush();
        } catch (Exception e) {
            e.printStackTrace(System.err);
            throw new IllegalStateException("cannot capture LIGHT data", e);
        }
    }

    public static synchronized void flushFinalLightData() {
        if (finalLightsFlushed || root == null || finalLights.isEmpty()) return;
        finalLightsFlushed = true;
        recordLightExecutorOp("FINAL_SNAPSHOT", null, false, null);
        try (BufferedWriter out = Files.newBufferedWriter(
                root.resolve("final-light-data.tsv"), StandardOpenOption.CREATE_NEW)) {
            out.write("order\tx\tz\tminLightSection\tmaxLightSection"
                    + "\tblockSha256\tskySha256\n");
            List<FinalLight> rows = new ArrayList<>(finalLights.values());
            rows.sort(Comparator.comparingLong(FinalLight::order));
            for (FinalLight row : rows) {
                ClassLoader loader = row.engine().getClass().getClassLoader();
                Class<?> lightLayer = Class.forName(
                        "net.minecraft.world.level.LightLayer", true, loader);
                Object block = Enum.valueOf((Class) lightLayer, "BLOCK");
                Object sky = Enum.valueOf((Class) lightLayer, "SKY");
                out.write(row.order() + "\t" + row.x() + "\t" + row.z()
                        + "\t" + row.min() + "\t" + row.max()
                        + "\t" + lightDigest(row.engine(), block, row.x(), row.z(),
                                row.min(), row.max())
                        + "\t" + lightDigest(row.engine(), sky, row.x(), row.z(),
                                row.min(), row.max()) + "\n");
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private static String lightDigest(Object engine, Object layer, int x, int z,
            int min, int max) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ClassLoader loader = engine.getClass().getClassLoader();
        Class<?> lightLayer = Class.forName("net.minecraft.world.level.LightLayer", true, loader);
        Object listener = engine.getClass().getMethod("getLayerListener", lightLayer)
                .invoke(engine, layer);
        Class<?> sectionPos = Class.forName("net.minecraft.core.SectionPos", true, loader);
        Class<?> listenerType = Class.forName(
                "net.minecraft.world.level.lighting.LayerLightEventListener", true, loader);
        Method of = sectionPos.getMethod("of", int.class, int.class, int.class);
        Method getData = listenerType.getMethod("getDataLayerData", sectionPos);
        byte[] dark = new byte[2048];
        for (int sy = min; sy < max; sy++) {
            Object pos = of.invoke(null, x, sy, z);
            Object data = getData.invoke(listener, pos);
            if (data == null) {
                digest.update(dark);
            } else {
                digest.update((byte[]) call(data, "getData"));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static synchronized void recordInitializeLightCompletion(
            LightingCut cut, long order, int x, int z, Throwable failure) {
        if (!armed()) return;
        try {
            String outcome = failure == null
                    ? "OK"
                    : "ERROR:" + oneLine(failure);
            lightingEvents.write(
                    cut.afterEventSeq()
                            + "\t" + order
                            + "\t" + (cut.activeX() == null ? "" : cut.activeX())
                            + "\t" + (cut.activeZ() == null ? "" : cut.activeZ())
                            + "\t" + x + "\t" + z
                            + "\t" + outcome + "\n");
            lightingEvents.flush();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot capture initializeLight completion", e);
        }
    }

    private static synchronized void recordFullLightCompletion(
            LightingCut cut, long order, int x, int z, Throwable failure) {
        if (!armed()) return;
        try {
            String outcome = failure == null
                    ? "OK"
                    : "ERROR:" + oneLine(failure);
            lightStageEvents.write(
                    cut.afterEventSeq()
                            + "\t" + order
                            + "\t" + (cut.activeX() == null ? "" : cut.activeX())
                            + "\t" + (cut.activeZ() == null ? "" : cut.activeZ())
                            + "\t" + x + "\t" + z
                            + "\t" + outcome + "\n");
            lightStageEvents.flush();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot capture full LIGHT completion", e);
        }
    }

    private static synchronized void recordWorldgenLightCompletion(
            LightingCut cut,
            long order,
            String stage,
            int x,
            int z,
            Throwable failure) {
        if (!armed()) return;
        try {
            String outcome = failure == null
                    ? "OK"
                    : "ERROR:" + oneLine(failure);
            worldgenLightEvents.write(
                    cut.afterEventSeq()
                            + "\t" + order
                            + "\t" + stage
                            + "\t" + (cut.activeX() == null ? "" : cut.activeX())
                            + "\t" + (cut.activeZ() == null ? "" : cut.activeZ())
                            + "\t" + x + "\t" + z
                            + "\t" + outcome + "\t" + currentLightBatch.get() + "\n");
            worldgenLightEvents.flush();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot capture worldgen light completion", e);
        }
    }

    private static int intField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return -1;
            }
        }
        return -1;
    }

    private static Object[] chunkContent(Object chunk) throws Exception {
        Object entities;
        Object pending;
        Object real;
        try {
            entities = call(chunk, "getEntities");
        } catch (NoSuchMethodException e) {
            entities = List.of();
        }
        try {
            pending = call(chunk, "getBlockEntityNbts");
        } catch (NoSuchMethodException e) {
            pending = Map.of();
        }
        try {
            Map<?, ?> realMap = (Map<?, ?>) call(chunk, "getBlockEntities");
            real = realMap.keySet();
        } catch (NoSuchMethodException e) {
            real = Set.of();
        }
        return new Object[]{entities, pending, real};
    }

    private static synchronized void record(String phase, Object context, Object step, Object cache, Object chunk) {
        try {
            Object level = call(context, "level");
            String dimension = call(level, "dimension").toString();
            long seed = (Long) call(level, "getSeed");
            Object pos = call(chunk, "getPos");
            int cx = (Integer) call(pos, "x"), cz = (Integer) call(pos, "z");
            String key = dimension + ":" + cx + ":" + cz;
            if (phase.equals("ENTER")) {
                if (globallyActiveOrigin != null) {
                    throw new IllegalStateException(
                            "overlapping FEATURES entry while "
                                    + globallyActiveOrigin[0] + ","
                                    + globallyActiveOrigin[1] + " is active");
                }
                if (!active.add(key)) throw new IllegalStateException("duplicate entry " + key);
            } else {
                if (globallyActiveOrigin == null
                        || globallyActiveOrigin[0] != cx
                        || globallyActiveOrigin[1] != cz) {
                    throw new IllegalStateException(
                            "FEATURES completion/global-origin mismatch for " + key);
                }
                if (!active.remove(key)) throw new IllegalStateException("completion without entry " + key);
            }
            int radius = (Integer) call(step, "blockStateWriteRadius");
            StringBuilder refs = new StringBuilder();
            StringBuilder hiddenRefs = new StringBuilder();
            StringBuilder lifecycleRefs = new StringBuilder();
            // Capture every actual center, not just target centers: indirect overlap
            // influences must not disappear from the replay's origin set.
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    Object holder = cache.getClass().getMethod("get", int.class, int.class).invoke(cache, x, z);
                    if (holderChunk == null) {
                        ClassLoader loader = chunk.getClass().getClassLoader();
                        Class<?> status = Class.forName("net.minecraft.world.level.chunk.status.ChunkStatus", true, loader);
                        carvers = status.getField("CARVERS").get(null);
                        holderChunk = Class.forName("net.minecraft.server.level.GenerationChunkHolder", true, loader)
                                .getMethod("getChunkIfPresentUnchecked", status);
                        sectionState = Class.forName("net.minecraft.world.level.chunk.LevelChunkSection", true, loader)
                                .getMethod("getBlockState", int.class, int.class, int.class);
                        stateId = Class.forName("net.minecraft.world.level.block.Block", true, loader).getMethod("getId",
                                Class.forName("net.minecraft.world.level.block.state.BlockState", true, loader));
                    }
                    Object neighbor = holderChunk.invoke(holder, carvers);
                    if (neighbor == null) throw new IllegalStateException("missing CARVERS dependency " + x + "," + z);
                    if (refs.length() > 0) refs.append(';');
                    refs.append(x).append(',').append(z).append(',').append(call(neighbor, "getPersistedStatus"))
                            .append(',').append(snapshot(neighbor));
                    if (hiddenRefs.length() > 0) hiddenRefs.append(';');
                    hiddenRefs.append(x).append(',').append(z).append(',')
                            .append(biomeSetHash(neighbor)).append(',')
                            .append(oceanFloorWgHash(neighbor));
                    if (lifecycleRefs.length() > 0) lifecycleRefs.append(';');
                    lifecycleRefs.append(x).append(',').append(z).append(',')
                            .append(neighbor.getClass().getName()).append(',')
                            .append(call(neighbor, "getPersistedStatus"));
                    if (phase.equals("COMPLETE")
                            && cz == 6
                            && (cx == 0 || cx == -1)) {
                        writeStructureData(cx, cz, x, z, neighbor);
                    }
                }
            }
            long seq = ++sequence;
            events.write(seq + "\t" + phase + "\t" + Thread.currentThread().getName()
                    + "\t" + cx + "\t" + cz + "\t" + seed + "\t" + dimension + "\t" + active.size()
                    + "\t" + radius + "\t" + call(step, "directDependencies") + "\t" + refs + "\n");
            hidden.write(seq + "\t" + phase + "\t" + cx + "\t" + cz + "\t" + hiddenRefs + "\n");
            lifecycle.write(seq + "\t" + phase + "\t" + cx + "\t" + cz
                    + "\t" + lifecycleRefs + "\n");
            events.flush();
            hidden.flush();
            lifecycle.flush();

            // Publish the cross-thread active window only after the ENTER row
            // is durable, and clear it only after the COMPLETE row is durable.
            // Since chunkRead() uses the same monitor, no other-thread reload
            // can be misclassified across either boundary.
            if (phase.equals("ENTER")) {
                globallyActiveOrigin = new int[]{cx, cz};
                lightingCut = new LightingCut(seq, cx, cz);
            } else {
                globallyActiveOrigin = null;
                lightingCut = new LightingCut(seq, null, null);
            }
        } catch (Exception e) {
            // Never let a missing/partial trace be mistaken for a valid capture.
            throw new IllegalStateException("FEATURES trace capture failed", e);
        }
    }

    private static void writeStructureData(
            int originX, int originZ, int chunkX, int chunkZ, Object chunk) throws Exception {
        Object entities;
        Object pending;
        Object real;
        try {
            entities = call(chunk, "getEntities");
        } catch (NoSuchMethodException e) {
            entities = List.of();
        }
        try {
            pending = call(chunk, "getBlockEntityNbts");
        } catch (NoSuchMethodException e) {
            pending = Map.of();
        }
        try {
            Map<?, ?> realMap = (Map<?, ?>) call(chunk, "getBlockEntities");
            real = realMap.keySet();
        } catch (NoSuchMethodException e) {
            real = Set.of();
        }
        structureData.write("COMPLETE\t" + originX + "\t" + originZ
                + "\t" + chunkX + "\t" + chunkZ
                + "\t" + oneLine(entities)
                + "\t" + oneLine(pending)
                + "\t" + oneLine(real) + "\n");
        structureData.flush();
    }

    private static String oneLine(Object value) {
        return String.valueOf(value)
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ');
    }

    private static String digestStrings(Collection<String> values) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String value : values) {
            digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String biomeSetHash(Object chunk) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Object[] sections = (Object[]) call(chunk, "getSections");
        for (Object section : sections) {
            Object biomes = call(section, "getBiomes");
            Method get = null;
            for (Method method : biomes.getClass().getMethods()) {
                if (method.getName().equals("get") && method.getParameterCount() == 3) {
                    get = method;
                    break;
                }
            }
            if (get == null) throw new NoSuchMethodException(biomes.getClass() + ".get(int,int,int)");
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        Object holder = get.invoke(biomes, x, y, z);
                        Object optionalKey = holder.getClass().getMethod("unwrapKey").invoke(holder);
                        digest.update(optionalKey.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String oceanFloorWgHash(Object chunk) throws Exception {
        Collection<?> entries = (Collection<?>) call(chunk, "getHeightmaps");
        for (Object rawEntry : entries) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) rawEntry;
            if (!"OCEAN_FLOOR_WG".equals(entry.getKey().toString())) continue;
            long[] raw = (long[]) call(entry.getValue(), "getRawData");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length * Long.BYTES);
            DataOutputStream out = new DataOutputStream(bytes);
            for (long value : raw) out.writeLong(value);
            out.flush();
            return HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        }
        return "MISSING";
    }

    private static String snapshot(Object chunk) throws Exception {
        Object pos = call(chunk, "getPos");
        Object[] sections = (Object[]) call(chunk, "getSections");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x4b465431); // KFT1: x,z,minY,height, then state IDs in y/z/x order
        out.writeInt((Integer) call(pos, "x")); out.writeInt((Integer) call(pos, "z"));
        out.writeInt((Integer) call(chunk, "getMinY")); out.writeInt(sections.length * 16);
        for (Object section : sections) {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                Object state = sectionState.invoke(section, x, y, z);
                Integer id = ids.get(state);
                if (id == null) { id = (Integer) stateId.invoke(null, state); ids.put(state, id); }
                out.writeInt(id);
            }
        }
        byte[] data = bytes.toByteArray();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        Path path = root.resolve("chunks").resolve(hash + ".bin.gz");
        if (!Files.exists(path)) try (var zip = new GZIPOutputStream(Files.newOutputStream(path))) { zip.write(data); }
        return hash;
    }
}
