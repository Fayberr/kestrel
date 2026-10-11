package dev.kestrel.net;

/** Regression gate for the deferred-generation cadence sentinel. */
public final class BackfillTimingCheck {
    private BackfillTimingCheck() {
    }

    public static void main(String[] args) {
        boolean ok = true;
        ok &= expect(ChunkSender.backfillDue(19_640L, Long.MIN_VALUE, 20L),
                "the first queued chunk is immediately eligible");
        ok &= expect(!ChunkSender.backfillDue(119L, 100L, 20L),
                "a backfill waits for the full interval");
        ok &= expect(ChunkSender.backfillDue(120L, 100L, 20L),
                "a backfill runs at the interval boundary");
        System.out.println(ok ? "BackfillTimingCheck: PASS" : "BackfillTimingCheck: FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    private static boolean expect(boolean condition, String message) {
        System.out.println((condition ? "[backfill] OK: " : "[backfill] FAIL: ") + message);
        return condition;
    }
}
