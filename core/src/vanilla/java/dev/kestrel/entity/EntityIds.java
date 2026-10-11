package dev.kestrel.entity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one network entity id minter, shared by players and entities (S14
 * slice 3). Vanilla keeps a single process-wide counter,
 * {@code ServerLevel.ENTITY_COUNTER} ({@code ServerLevel.java:195}), and
 * {@code getNextEntityId} ({@code :312-319}) takes
 * {@code incrementAndGet} until it finds an id that is neither 0 nor held
 * by a live entity. Kestrel does the same over its own live set, so a
 * player and an item can never share an id and a wrapped counter never
 * hands out an id whose owner is still live.
 *
 * <p>An id is live from {@link #claim()} until {@link #release(int)}; the
 * owner releases it when it leaves the world (entity removal, player
 * disconnect). A player keeps the id it was minted at construction for its
 * whole session: the id has already been sent in the join packet.
 *
 * <p>Thread-safety: players are minted on the login (I/O) path and
 * entities on the tick thread, so the counter is atomic and the live set
 * concurrent, as vanilla's counter is atomic.
 */
public final class EntityIds {
    private EntityIds() {
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final Set<Integer> LIVE = ConcurrentHashMap.newKeySet();

    /** {@code ServerLevel.getNextEntityId}: the next id that is not 0 and not live, now live. */
    public static int claim() {
        int id = 0;
        while (id == 0 || !LIVE.add(id)) {
            id = COUNTER.incrementAndGet();
        }
        return id;
    }

    /** The owner left the world; its id may be handed out again after a wrap. */
    public static void release(int id) {
        LIVE.remove(id);
    }

    /** True while an owner holds {@code id}. */
    public static boolean isLive(int id) {
        return LIVE.contains(id);
    }

    /** The last value the counter produced (for the gate). */
    static int peek() {
        return COUNTER.get();
    }

    /** Move the counter (for the gate's wrap test only). */
    static void setCounterForTest(int value) {
        COUNTER.set(value);
    }
}
