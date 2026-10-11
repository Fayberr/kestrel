package dev.kestrel.harness;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunk-generation stopwatch sink (G2 de-risking).
 *
 * <p>Called from ASM-injected enter/exit probes on the eight
 * {@code ChunkStatusTasks.generate*} methods (see HarnessAgent): each
 * probe passes the chunk's packed pos (long) plus the stage name; enter
 * records a nano timestamp, exit appends the elapsed ms to that stage's
 * sample list and, for the LIGHT stage (last timed stage), writes one
 * csv row for the chunk.
 *
 * <p>Bootstrap-loaded (ships in kestrel-harness-boot.jar next to
 * TickLogger): must stay dependency-free and public. All methods are
 * static and thread-safe; chunk-gen runs async off the tick thread.
 *
 * <p>Output: chunkgen-watch.csv next to the tick log:
 * chunk_x,chunk_z,total_ms,structure_starts_ms,...,light_ms
 */
public final class ChunkgenWatch {
    private ChunkgenWatch() {
    }

    /**
     * Stage order = csv column order. INITIALIZE_LIGHT keeps its own label:
     * initializeLight and light are separate engine
     * calls, and a row flushes only on light, the true last timed stage.
     */
    public static final String[] STAGES = {
            "STRUCTURE_STARTS", "STRUCTURE_REFERENCES", "BIOMES", "NOISE",
            "SURFACE", "CARVERS", "FEATURES", "INITIALIZE_LIGHT", "LIGHT",
            // G2 step 1: inside-supplier work columns. Never flushed into a
            // row (rows flush on LIGHT); read via summary() only.
            "NOISE_WORK", "BIOMES_WORK",
    };

    /** Runtime probe-hit counter (bootstrap-visible): incremented as bytecode from every enter probe. */
    public static final AtomicLong PROBE_HITS = new AtomicLong();

    private static volatile BufferedWriter out;
    private static final ConcurrentMap<Long, Long> open = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Long, double[]> rows = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, List<Double>> samples = new ConcurrentHashMap<>();

    public static void init(String csvPath) {
        try {
            out = new BufferedWriter(new FileWriter(csvPath));
            StringBuilder head = new StringBuilder("chunk_x,chunk_z,total_ms");
            for (String s : STAGES) {
                head.append(',').append(s.toLowerCase()).append("_ms");
            }
            out.write(head.toString());
            out.newLine();
            out.flush();
        } catch (Exception e) {
            System.out.println("[kestrel] ChunkgenWatch disabled: " + e);
            out = null;
        }
        for (String s : STAGES) {
            samples.put(s, Collections.synchronizedList(new ArrayList<>()));
        }
    }

    /** Probe entry: chunk packed pos via ChunkPos.pack, stage = method suffix. */
    public static void enter(long chunkPos, String stage) {
        if (out == null) {
            return;
        }
        // Key MUST be collision-free across (pos, stage): the old
        // pos*31+hashCode mixed initializeLight/light rows on the same
        // chunk (same pos, hash collision not even needed - two LIGHT
        // labels shared one slot). High 32 = pos, low 32 = stage index.
        open.put((chunkPos << 4) | stageIndex(stage), System.nanoTime());
    }

    /**
     * Probe exit: records ms; LIGHT completion flushes the chunk row.
     * NOTE: a stage whose method returns before its future completes
     * (NOISE: fillFromNoise) records ~0 - the real work lands async on
     * the worker pool. Inside-supplier timing is the
     * follow-up; this probe measures inline (worldgen-thread) cost.
     */
    public static void exit(long chunkPos, String stage) {
        if (out == null) {
            return;
        }
        Long t0 = open.remove((chunkPos << 4) | stageIndex(stage));
        if (t0 == null) {
            return;
        }
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        List<Double> v = samples.get(stage);
        if (v != null) {
            v.add(ms);
        }
        double[] row = rows.computeIfAbsent(chunkPos, k -> new double[STAGES.length]);
        synchronized (row) {
            for (int i = 0; i < STAGES.length; i++) {
                if (STAGES[i].equals(stage)) {
                    row[i] = ms;
                }
            }
            if ("LIGHT".equals(stage)) {
                writeRow(chunkPos, row);
                rows.remove(chunkPos);
            }
        }
    }

    private static void writeRow(long chunkPos, double[] row) {
        try {
            int cx = (int) (chunkPos >> 32);
            int cz = (int) chunkPos;
            double total = 0;
            for (double d : row) {
                total += d;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(cx).append(',').append(cz).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.3f", total));
            for (double d : row) {
                sb.append(',').append(String.format(java.util.Locale.ROOT, "%.3f", d));
            }
            synchronized (out) {
                out.write(sb.toString());
                out.newLine();
                out.flush();
            }
        } catch (Exception e) {
            System.out.println("[kestrel] ChunkgenWatch write failed: " + e);
        }
    }

    private static long stageIndex(String stage) {
        for (int i = 0; i < STAGES.length; i++) {
            if (STAGES[i].equals(stage)) {
                return i;
            }
        }
        return 15;
    }

    /** Median/p95 per stage, for the log tail. */
    public static String summary() {
        StringBuilder sb = new StringBuilder("chunkgen-watch ms/chunk (median/p95):");
        for (String s : STAGES) {
            List<Double> v = samples.get(s);
            if (v == null || v.isEmpty()) {
                sb.append(' ').append(s).append("=n/a");
                continue;
            }
            List<Double> sorted = new ArrayList<>(v);
            Collections.sort(sorted);
            sb.append(String.format(java.util.Locale.ROOT, " %s=%.3f/%.3f", s,
                    sorted.get(sorted.size() / 2),
                    sorted.get((int) (sorted.size() * 0.95))));
        }
        return sb.toString();
    }
}
