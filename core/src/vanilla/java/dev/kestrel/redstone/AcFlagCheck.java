package dev.kestrel.redstone;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Correctness proof for {@link AcFlag}: default OFF (the Kestrel divergence
 * from upstream's default-ON), order default, persistence round-trip,
 * tolerant parsing (unknown keys, bad values, comments), save-only-when-
 * modified, derived-dimension delegation, and order-id table. Pure JDK, no
 * vanilla classes. Exits non-zero on any mismatch.
 */
public final class AcFlagCheck {
    private AcFlagCheck() {
    }

    public static void main(String[] args) throws Exception {
        List<String> failures = new ArrayList<>();

        // Default OFF, default order (divergence from upstream default-ON).
        AcFlag fresh = new AcFlag();
        if (fresh.enabled()) {
            failures.add("default: must be OFF");
        }
        if (fresh.order() != AcFlag.DEFAULT_ORDER) {
            failures.add("default: order must be HORIZONTAL_FIRST_OUTWARD");
        }

        // Round-trip: enable + change order, save, reload into a fresh flag.
        Path tmp = Files.createTempFile("acflag", ".conf");
        try {
            fresh.setEnabled(true);
            fresh.setOrder(AcUpdateOrder.VERTICAL_FIRST_INWARD);
            StringBuilder log = new StringBuilder();
            fresh.save(tmp, true, log);
            if (!fresh.enabled() || fresh.order() != AcUpdateOrder.VERTICAL_FIRST_INWARD) {
                failures.add("round-trip: setters must stick");
            }
            AcFlag reloaded = new AcFlag();
            reloaded.load(tmp, log);
            if (!reloaded.enabled()) {
                failures.add("round-trip: enabled must survive save/load");
            }
            if (reloaded.order() != AcUpdateOrder.VERTICAL_FIRST_INWARD) {
                failures.add("round-trip: order must survive save/load");
            }
            // Save-only-when-modified: second save must not touch the file.
            long before = Files.getLastModifiedTime(tmp).toMillis();
            Thread.sleep(5);
            reloaded.save(tmp, true, log);
            // Re-set the same values: modified flag set by setter, so force
            // a clean load first, then save must be a no-op.
            reloaded.load(tmp, log);
            reloaded.save(tmp, true, log);
            long after = Files.getLastModifiedTime(tmp).toMillis();
            if (after != before) {
                failures.add("save: unmodified flag must not rewrite the file");
            }

            // Tolerant parsing: comments, unknown keys, bad values skipped.
            Files.writeString(tmp, "# comment\nenabled=false\nbogus-key=1\nupdate-order=not_an_order\n");
            StringBuilder log2 = new StringBuilder();
            AcFlag tolerant = new AcFlag();
            tolerant.setEnabled(true);
            tolerant.load(tmp, log2);
            if (tolerant.enabled()) {
                failures.add("tolerant: enabled=false must apply");
            }
            if (tolerant.order() != AcFlag.DEFAULT_ORDER) {
                failures.add("tolerant: bad order value must keep previous order");
            }
            String logs = log2.toString();
            if (!logs.contains("bogus-key") || !logs.contains("not_an_order")) {
                failures.add("tolerant: skipped keys/values must be logged, got: " + logs);
            }

            // Missing file: stays default, marked modified.
            Path missing = tmp.resolveSibling("acflag-missing-" + System.nanoTime() + ".conf");
            AcFlag fresh2 = new AcFlag();
            fresh2.load(missing, new StringBuilder());
            if (fresh2.enabled()) {
                failures.add("missing: must stay OFF");
            }

            // Derived dimension delegates both ways, never touches disk.
            AcFlag primary = new AcFlag();
            AcFlag derived = AcFlag.derived(primary);
            derived.setEnabled(true);
            if (!primary.enabled()) {
                failures.add("derived: setEnabled must reach primary");
            }
            derived.setOrder(AcUpdateOrder.VERTICAL_FIRST_OUTWARD);
            if (primary.order() != AcUpdateOrder.VERTICAL_FIRST_OUTWARD
                    || derived.order() != AcUpdateOrder.VERTICAL_FIRST_OUTWARD) {
                failures.add("derived: order must delegate");
            }
            primary.setEnabled(false);
            if (derived.enabled()) {
                failures.add("derived: reads must follow primary");
            }

            // Order ids: 4 valid, parse case-insensitively.
            String[] ids = AcFlag.orderIds();
            if (ids.length != 4) {
                failures.add("orderIds: must list 4 orders");
            }
            try {
                if (AcFlag.byId("Horizontal_First_Outward") != AcUpdateOrder.HORIZONTAL_FIRST_OUTWARD) {
                    failures.add("byId: case-insensitive parse broken");
                }
            } catch (Exception e) {
                failures.add("byId: threw on valid id");
            }
            boolean threw = false;
            try {
                AcFlag.byId("nope");
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            if (!threw) {
                failures.add("byId: must throw on unknown id");
            }
        } finally {
            Files.deleteIfExists(tmp);
        }

        System.out.println("AcFlagCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }
}
