package dev.kestrel.persist;

import dev.kestrel.loop.TickLoop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stage 2 async saver: snapshot on the tick thread, serialize plus write
 * on a single background thread, completion callback with the snapshot
 * hash.
 *
 * <p>Ordering contract: one in-flight save per store instance. A second
 * {@code saveAsync} while one is pending is rejected loudly (the caller
 * serializes saves: autosave ticks, not concurrent writers). The tick
 * thread never blocks on disk: {@code saveAsync} returns after the
 * snapshot copy, and the {@code writeSnapshot} I/O runs entirely on the
 * saver thread, which owns no tick-thread structures after capture.
 *
 * <p>Failure model: I/O errors complete the future exceptionally; the
 * live world is untouched (the snapshot was a copy). The executor is a
 * single daemon thread named {@code kestrel-saver}: saves serialize in
 * submission order even across store instances.
 *
 * <p>Thread-safety: the in-flight guard is an atomic boolean per
 * instance; the snapshot crosses threads exactly once (tick to saver).
 */
public final class AsyncSaver implements AutoCloseable {

    private static final ThreadFactory DAEMON = r -> {
        Thread t = new Thread(r, "kestrel-saver");
        t.setDaemon(true);
        return t;
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor(DAEMON);
    private final AtomicLong inFlight = new AtomicLong(0);
    private volatile boolean closed;

    /**
     * Save the live loop asynchronously: capture the snapshot on the
     * calling (tick) thread now, write it on the saver thread. The
     * future completes with the snapshot hash.
     */
    public CompletableFuture<Long> saveAsync(TickLoop loop, Path worldRoot) {
        if (closed) {
            CompletableFuture<Long> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("saver closed"));
            return failed;
        }
        if (!inFlight.compareAndSet(0, 1)) {
            CompletableFuture<Long> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(
                    new IOException("save already in flight (one per saver)"));
            return rejected;
        }
        WorldSnapshot snap;
        try {
            snap = WorldSnapshot.capture(loop);
        } catch (RuntimeException e) {
            inFlight.set(0);
            CompletableFuture<Long> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
        long hash = snap.hash();
        return CompletableFuture.supplyAsync(() -> {
            try {
                WorldStore.writeSnapshot(snap, worldRoot);
                return hash;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                inFlight.set(0);
            }
        }, executor);
    }

    /** True while a save is in flight. */
    public boolean isSaving() {
        return inFlight.get() != 0;
    }

    /** Shut down the saver thread (outstanding saves run to completion). */
    @Override
    public void close() {
        closed = true;
        executor.shutdown();
    }
}
