package dev.kestrel.genbench;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chunk generation throughput probe for Paper: request every chunk of a
 * size x size square (spiral order from its centre) through
 * World.getChunkAtAsync with a bounded number in flight, then log wall time
 * and chunks per second and stop the server.
 * System properties: genbench.x, genbench.z (square centre, chunk coords),
 * genbench.size, genbench.inflight, genbench.out (result file).
 */
public final class GenBench extends JavaPlugin {
    private final List<int[]> todo = new ArrayList<>();
    private final AtomicInteger done = new AtomicInteger();
    private int next;
    private int inFlight;
    private long t0;
    private long cpu0;
    private final java.util.Map<String, Long> threadCpu0 = new java.util.HashMap<>();

    static long processCpu() {
        return ((com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }

    static java.util.Map<String, Long> threadCpu() {
        java.util.Map<String, Long> m = new java.util.TreeMap<>();
        var tm = java.lang.management.ManagementFactory.getThreadMXBean();
        for (var ti : tm.dumpAllThreads(false, false)) {
            long c = tm.getThreadCpuTime(ti.getThreadId());
            if (c > 0) m.merge(ti.getThreadName().replaceAll("[-#]?\\d+$", "#"), c, Long::sum);
        }
        return m;
    }

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::start, 40L);
    }

    private void start() {
        int cx = Integer.getInteger("genbench.x", 0);
        int cz = Integer.getInteger("genbench.z", 0);
        int size = Integer.getInteger("genbench.size", 32);
        int half = size / 2;
        // square spiral from the centre outward, clipped to the square
        int x = 0, z = 0, dx = 1, dz = 0, leg = 1, steps = 0, turns = 0;
        while (todo.size() < size * size) {
            if (x >= -half && x < size - half && z >= -half && z < size - half) {
                todo.add(new int[]{cx + x, cz + z});
            }
            x += dx; z += dz; steps++;
            if (steps == leg) {
                steps = 0; int t = dx; dx = -dz; dz = t; turns++;
                if (turns % 2 == 0) leg++;
            }
        }
        getLogger().info("GENBENCH start chunks=" + todo.size() + " centre=" + cx + "," + cz
                + " inflight=" + Integer.getInteger("genbench.inflight", 64));
        threadCpu0.putAll(threadCpu());
        cpu0 = processCpu();
        t0 = System.nanoTime();
        pump();
    }

    private void pump() {
        int max = Integer.getInteger("genbench.inflight", 64);
        World world = Bukkit.getWorlds().get(0);
        while (inFlight < max && next < todo.size()) {
            int[] c = todo.get(next++);
            inFlight++;
            world.getChunkAtAsync(c[0], c[1], true).whenComplete((chunk, err) -> {
                if (err != null) getLogger().severe("GENBENCH failed " + c[0] + "," + c[1] + " " + err);
                Bukkit.getScheduler().runTask(this, () -> {
                    inFlight--;
                    int n = done.incrementAndGet();
                    if (n % 256 == 0) getLogger().info("GENBENCH progress " + n + " "
                            + String.format("%.1f", n / ((System.nanoTime() - t0) / 1e9)) + " chunks/s");
                    if (n == todo.size()) finish(); else pump();
                });
            });
        }
    }

    private void finish() {
        double s = (System.nanoTime() - t0) / 1e9;
        double cpu = (processCpu() - cpu0) / 1e9;
        String line = String.format("GENBENCH done chunks=%d seconds=%.3f chunksPerSecond=%.2f cpuSeconds=%.1f cores=%.2f",
                todo.size(), s, todo.size() / s, cpu, cpu / s);
        threadCpu().forEach((k, v) -> {
            double d = (v - threadCpu0.getOrDefault(k, 0L)) / 1e9;
            if (d > 0.5) getLogger().info(String.format("GENBENCH thread %s cpu=%.1fs", k, d));
        });
        getLogger().info(line);
        String out = System.getProperty("genbench.out");
        if (out != null) {
            try (FileWriter w = new FileWriter(out, true)) { w.write(line + "\n"); } catch (Exception e) { }
        }
        Bukkit.shutdown();
    }
}
