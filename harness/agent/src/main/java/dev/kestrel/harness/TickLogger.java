package dev.kestrel.harness;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Vanilla-side tick logger (exit-only hashing; enter lines carry a 0-hash
 * sentinel with tick_count/nanos so line counts stay comparable).
 *
 * Called (via injected bytecode, see HarnessAgent) at the entry and exit of
 * {@code MinecraftServer.tickServer}. Deliberately reflection-only: this
 * agent does not compile against Minecraft classes at all, so it has zero
 * coupling to any mapping toolchain and zero jar dependency at build time.
 * That is a real constraint the harness keeps going forward.
 *
 * Per-tick world-state hashing lives in {@link WorldStateHasher} (current
 * walk is schema v3+: entities + loaded-chunk non-air block states + block
 * entities + container contents, deterministically ordered). See that
 * class's javadoc for exact scope and the corpus-world-sized performance
 * caveat. It is reflection-only for the same bootstrap-classloader reason
 * as this class - keep it that way.
 *
 * Determinism self-check exists: tools/determinism-check.ps1 runs a corpus
 * twice and diffs the tick logs (exit-phase lines only, tick_count
 * equality asserted; use -SkipTicks to exclude the early settling window).
 * Before any world_state_hash log is trusted for a parity decision, that
 * check must PASS for the corpus. Known non-reproducible input: entity UUIDs
 * (Entity.random is unseeded; /summon assigns fresh UUIDs per run), so
 * entity-bearing corpora FAIL the check BY CONSTRUCTION - use empty-world
 * controls or the same-tick proof logs (spawn-proof.log, ac-proof.log)
 * instead.
 *
 * IMPORTANT - what this still does NOT do:
 *  - No lock-free ring buffer (conflict-rate-design.md section 2.4 requires
 *    one for the *real* conflict-rate experiment, to avoid perturbing MSPT).
 *    This logger uses a plain synchronized BufferedWriter flushed per line
 *    (required: runs end via kill, so buffered rows would be lost), which
 *    WILL perturb timing. Fine for differential validation; not fine for
 *    any wall-clock-sensitive measurement. Do not reuse this logger
 *    for the conflict-rate experiment without replacing this part.
 */
public final class TickLogger {

    /** Bump this if the line format changes; keeps old logs from being
     *  silently misread by a newer analyzer.
     *  v0: placeholder hash (identity-hashcode-derived, not a real state hash).
     *  v1: real per-tick world-state hash via WorldStateHasher.
     *  v2: v1 + container block-entity inventory contents (item + count
     *  per slot) - required before hopper corpora can gate anything.
     *  v3: v2 + chunk walk via getChunkNow/updatingChunkMap (visibleChunkMap
     *  is empty with no players - the v1/v2 walk hashed ZERO blocks) +
     *  section-level walk with hasOnlyAir skip (the per-block reflective walk
     *  tripped the server watchdog on force-loaded volumes). Old v1/v2 logs
     *  are NOT comparable to v3 (different chunk set AND different walk).
     *  v4: v3 + block-entity-census.log sibling (BlockEntityCensus).
     *  v5: v4 + spawn-proof.log sibling (SpawnProof, step 6 Leg 3 same-tick
     *  snapshot differential of the spawn cap/filter recomputation).
     *  v6: v5 + ac-proof.log sibling (AcProof, step 7 same-tick AC
     *  propagation replay over live wire data; DIVERGE on mismatch). */
    private static final int SCHEMA_VERSION = 6;

    private static volatile BufferedWriter writer;
    private static final AtomicLong SEQ = new AtomicLong();
    private static volatile Method getTickCountMethod;

    /**
     * Opt-out for the per-tick proof traces (PowerTrace, BlockEntityCensus,
     * SpawnProof, AcProof). Those four are step-N proof instruments for the
     * redstone/spawn/AC investigations; none of them feed the G2 oracle
     * corpus, but all of them do a full reflective walk of every loaded
     * chunk on every tick exit. On a force-loaded corpus volume that walk
     * grows with the chunk set and eventually exceeds the 60 s server
     * watchdog, which HARD-CRASHES the server mid-generation and loses the
     * region files that are the entire point of a corpus run. Observed live
     * 2026-09-13: watchdog "Watching Server" crash with the server thread
     * parked in PowerTrace.collectWires, at tick 211 after 82 s of wall
     * clock (a healthy server does ~1600 ticks in that window).
     *
     * <p>Default true = unchanged behaviour for every existing caller
     * (run-harness.ps1, chunkgen-watch.ps1, the jfr/asm comparison runs).
     * Corpus runs pass {@code -Dkestrel.harness.perTickTraces=false}.
     */
    private static final boolean PER_TICK_TRACES =
            Boolean.parseBoolean(System.getProperty("kestrel.harness.perTickTraces", "true"));

    /**
     * Opt-out for the per-tick world-state hash (WorldStateHasher). The hash
     * is what makes ticks.log a determinism oracle, but it too walks every
     * loaded chunk each tick and its cost scales with the corpus volume.
     * Disabling it writes the same 0-hash sentinel the enter-phase lines
     * already use (see onTick), so line counts and tick coverage stay
     * readable. Corpus runs, which gate on region files + the per-chunk
     * P1/P2/P3 probes rather than on the tick hash, pass
     * {@code -Dkestrel.harness.tickHash=false}.
     */
    private static final boolean TICK_HASH =
            Boolean.parseBoolean(System.getProperty("kestrel.harness.tickHash", "true"));

    private TickLogger() {
    }

    public static synchronized void init(String path) {
        noteLogPath(path);
        try {
            writer = new BufferedWriter(new FileWriter(path, /* append= */ false));
            writer.write("# kestrel-harness tick log, schema_version=" + SCHEMA_VERSION);
            writer.newLine();
            writer.write("# seq,phase,tick_count,nanos,world_state_hash");
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException("kestrel-harness: could not open tick log at " + path, e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(TickLogger::close, "kestrel-harness-log-flush"));
    }

    /** Invoked from injected bytecode inside MinecraftServer. Keep this
     *  method's signature stable - HarnessAgent hardcodes it. */
    public static void onTick(Object server, String phase) {
        BufferedWriter w = writer;
        if (w == null) {
            return; // agent not initialized; never happens outside manual testing
        }
        long seq = SEQ.getAndIncrement();
        long nanos = System.nanoTime();
        BlockShapeAudit.auditOnce(server); // no-op after the first call, see its javadoc
        PowerTrace.setOutputPath(siblingPath()); // no-op after init, see PowerTrace
        BlockEntityCensus.setOutputPath(censusSiblingPath()); // no-op after init, see BlockEntityCensus
        SpawnProof.setOutputPath(spawnProofSiblingPath()); // no-op after init, see SpawnProof
        AcProof.setOutputPath(acProofSiblingPath()); // no-op after init, see AcProof
        int tickCount = readTickCount(server);
        // EXIT phase only (2026-09-10): hashing both enter and exit doubled
        // the per-tick walk cost for zero new information (enter(t+1) equals
        // exit(t) modulo async chunk-gen, which the SkipTicks window already
        // excludes). Enter lines keep tick_count/nanos with a 0 hash sentinel
        // so line counts and tick coverage stay comparable.
        boolean isExit = "exit".equals(phase);
        long hash = (isExit && TICK_HASH) ? WorldStateHasher.hash(server) : 0L;
        if (isExit && PER_TICK_TRACES) {
            // Per-wire POWER trace for the experimental-vs-default
            // differential (spec item 4): exit-phase only, see PowerTrace.
            PowerTrace.traceExit(server, tickCount);
            // Block-entity census for step 5 item 2 (population + idle
            // fraction per type): exit-phase only, see BlockEntityCensus.
            BlockEntityCensus.traceExit(server, tickCount);
            // Spawn proof for step 6 Leg 3 (same-tick cap/filter
            // recomputation): exit-phase only, see SpawnProof.
            SpawnProof.traceExit(server, tickCount);
            // AC proof for step 7 (same-tick propagation replay over live
            // wire data): exit-phase only, see AcProof.
            AcProof.traceExit(server, tickCount);
        }
        String line = seq + "," + phase + "," + tickCount + "," + nanos + "," + hash;
        synchronized (TickLogger.class) {
            try {
                w.write(line);
                w.newLine();
                // Flushing every line is deliberately conservative for the
                // skeleton (correctness over speed); the real experiment
                // logger must NOT do this (see class javadoc).
                w.flush();
            } catch (IOException e) {
                System.err.println("[kestrel-harness] tick log write failed: " + e);
            }
        }
    }

    private static volatile String logPath;

    /** Remembers the tick-log path so the power-trace sibling can be derived. */
    static void noteLogPath(String path) {
        logPath = path;
    }

    private static String siblingPath() {
        return siblingNamed("power-trace.log");
    }

    private static String censusSiblingPath() {
        return siblingNamed("block-entity-census.log");
    }

    private static String spawnProofSiblingPath() {
        return siblingNamed("spawn-proof.log");
    }

    private static String acProofSiblingPath() {
        return siblingNamed("ac-proof.log");
    }

    private static String siblingNamed(String name) {
        String base = logPath;
        if (base == null) {
            return name;
        }
        java.io.File logFile = new java.io.File(base).getAbsoluteFile();
        return new java.io.File(logFile.getParentFile(), name).getPath();
    }

    private static int readTickCount(Object server) {
        try {
            Method m = getTickCountMethod;
            if (m == null) {
                m = server.getClass().getMethod("getTickCount");
                m.setAccessible(true);
                getTickCountMethod = m;
            }
            return (Integer) m.invoke(server);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static synchronized void close() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
            }
        }
    }
}
