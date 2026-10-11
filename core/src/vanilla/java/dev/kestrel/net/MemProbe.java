package dev.kestrel.net;

import dev.kestrel.loop.SectionStore;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Locale;

/**
 * In-process memory marker for the G2 step-2 measurement
 * ({@code preresearch/g2-memory-design.md} 1.4 items 3 and 5, and the
 * fallback when {@code jcmd} cannot attach).
 *
 * <p>One line per sample: heap used before the GC (the load peak when
 * sampled right after import), then {@code System.gc()} and heap
 * used/committed/max, non-heap used/committed, live compact/dense section
 * counts, and modelled block-storage bytes. The bench parses these lines;
 * the jcmd histogram checks the dense-array portion of the model.
 *
 * <p>Thread-safety: {@code sectionCount} is a plain {@code HashMap.size()}
 * read from a non-tick thread. The value is a benign racy int; the store
 * only grows during import (before the listener opens) and on writes.
 */
public final class MemProbe {

    private static final double MIB = 1024.0 * 1024.0;

    private MemProbe() {
    }

    /** Sample, optionally after a full GC, and return the log line. */
    public static String sample(String label, SectionStore store, boolean gc) {
        MemoryMXBean mx = ManagementFactory.getMemoryMXBean();
        long preGc = mx.getHeapMemoryUsage().getUsed();
        if (gc) {
            System.gc();
        }
        MemoryUsage heap = mx.getHeapMemoryUsage();
        MemoryUsage nonHeap = mx.getNonHeapMemoryUsage();
        int sections = store.sectionCount();
        int compact = store.compactSectionCount();
        int dense = sections - compact;
        return String.format(Locale.ROOT,
                "[kestrel] mem label=%s gc=%s heapUsedPreGcMiB=%.1f heapUsedMiB=%.1f"
                        + " heapCommittedMiB=%.1f heapMaxMiB=%.1f nonHeapUsedMiB=%.1f"
                        + " nonHeapCommittedMiB=%.1f sections=%d compact=%d dense=%d modelBMiB=%.2f",
                label, gc, preGc / MIB, heap.getUsed() / MIB, heap.getCommitted() / MIB,
                heap.getMax() / MIB, nonHeap.getUsed() / MIB, nonHeap.getCommitted() / MIB,
                sections, compact, dense, store.estimatedStorageBytes() / MIB);
    }

    /** Daemon thread printing a sample every {@code seconds} without forcing GC. */
    public static void startPeriodic(SectionStore store, int seconds) {
        startPeriodic(store, seconds, false);
    }

    /** Daemon thread printing a sample every {@code seconds}, optionally forcing GC. */
    public static void startPeriodic(SectionStore store, int seconds, boolean forceGc) {
        Thread t = new Thread(() -> {
            long n = 0;
            while (true) {
                try {
                    Thread.sleep(seconds * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                System.out.println(sample("periodic-" + (++n), store, forceGc));
            }
        }, "kestrel-mem-probe");
        t.setDaemon(true);
        t.start();
    }
}
