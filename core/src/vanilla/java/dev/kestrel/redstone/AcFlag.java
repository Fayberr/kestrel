package dev.kestrel.redstone;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Opt-in flag + update-order config for the Alternate Current port.
 *
 * <p>Transcribed from {@code alternate.current.wire.Config} (SpaceWalkerRS,
 * MIT) with ONE deliberate divergence: AC upstream defaults
 * {@code enabled = true}; Kestrel's parity contract demands default OFF, so
 * this class defaults to disabled. Everything else (file keys
 * {@code enabled} / {@code update-order}, unknown-key tolerance, malformed
 * values skipped not fatal, overworld-primary with other dimensions
 * derived) follows upstream.
 *
 * <p>Headless by design: persistence is path-based ({@link #load(Path)},
 * {@link #save(Path, boolean)}), dimension derivation is a pure delegate
 * ({@link #derived(AcFlag)}), so no {@code ServerLevel} or storage access
 * is needed. The future live loop owns binding these to real levels.
 */
public class AcFlag {

    public static final AcUpdateOrder DEFAULT_ORDER = AcUpdateOrder.HORIZONTAL_FIRST_OUTWARD;

    private boolean enabled;
    private AcUpdateOrder order;
    private boolean modified;

    public AcFlag() {
        // Kestrel divergence from upstream: default OFF (exact by default,
        // owners opt into speed knowingly).
        this.enabled = false;
        this.order = DEFAULT_ORDER;
        this.modified = true;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.modified = true;
    }

    public AcUpdateOrder order() {
        return order;
    }

    public void setOrder(AcUpdateOrder order) {
        this.order = Objects.requireNonNull(order);
        this.modified = true;
    }

    /** A dimension config derived from the overworld primary (AC Derived). */
    public static AcFlag derived(AcFlag primary) {
        return new Derived(primary);
    }

    /** Load {@code enabled} / {@code update-order} keys; tolerant like upstream. */
    public void load(Path path, Appendable log) throws IOException {
        if (!Files.exists(path)) {
            modified = true;
            return;
        }
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("[=]");
                if (parts.length != 2) {
                    continue;
                }
                try {
                    switch (parts[0]) {
                        case "enabled":
                            setEnabled(Boolean.parseBoolean(parts[1]));
                            break;
                        case "update-order":
                            setOrder(byId(parts[1]));
                            break;
                        default:
                            log.append("skipping unknown option '").append(parts[0]).append("'\n");
                    }
                } catch (Exception e) {
                    log.append("skipping bad value '").append(parts[1]).append("'\n");
                }
            }
            modified = false;
        } catch (IOException e) {
            log.append("unable to load Alternate Current config!\n");
            modified = true;
            throw e;
        }
    }

    /** Save only when modified (AC Primary.save semantics). */
    public void save(Path path, boolean silent, Appendable log) throws IOException {
        if (!modified) {
            return;
        }
        if (!silent) {
            log.append("saving Alternate Current config\n");
        }
        try (BufferedWriter bw = Files.newBufferedWriter(path)) {
            bw.write("enabled=" + enabled);
            bw.newLine();
            bw.write("update-order=" + order.name().toLowerCase(Locale.ENGLISH));
            bw.newLine();
        } catch (IOException e) {
            log.append("unable to save Alternate Current config!\n");
            throw e;
        }
        modified = false;
    }

    /** Parse an order id case-insensitively (AC UpdateOrder.byId). */
    public static AcUpdateOrder byId(String id) {
        return AcUpdateOrder.valueOf(id.toUpperCase(Locale.ENGLISH));
    }

    /** All valid order ids, for command suggestions (AC UPDATE_ORDERS). */
    public static String[] orderIds() {
        AcUpdateOrder[] orders = AcUpdateOrder.values();
        String[] ids = new String[orders.length];
        for (int i = 0; i < orders.length; i++) {
            ids[i] = orders[i].name().toLowerCase(Locale.ENGLISH);
        }
        return ids;
    }

    private static final class Derived extends AcFlag {
        private final AcFlag primary;

        Derived(AcFlag primary) {
            this.primary = primary;
        }

        @Override
        public boolean enabled() {
            return primary.enabled();
        }

        @Override
        public void setEnabled(boolean enabled) {
            primary.setEnabled(enabled);
        }

        @Override
        public AcUpdateOrder order() {
            return primary.order();
        }

        @Override
        public void setOrder(AcUpdateOrder order) {
            primary.setOrder(order);
        }

        @Override
        public void load(Path path, Appendable log) {
            // Derived dimensions never load; upstream Derived.load is empty.
        }

        @Override
        public void save(Path path, boolean silent, Appendable log) {
            // Derived dimensions never save; upstream Derived.save is empty.
        }
    }
}
