package dev.kestrel.net;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * G4 server state files: the world clock, weather, and per-player
 * position that the Stage 2 region format has no slot for.
 *
 * <p>The region format ({@code persist.WorldStore}: Anvil {@code .mca}
 * plus {@code .kex} sidecar per region) is keyed by region and carries
 * only block storage, block entities, the tick queue, and the loop's
 * gameTime. World-scoped and player-scoped state does not belong in a
 * per-region file, so it lives next to it in the vanilla spots, as
 * human-readable properties instead of vanilla's NBT:
 * <ul>
 *   <li>{@code <world>/kestrel-level.properties} (vanilla {@code level.dat}):
 *       gameTime, dayTime, raining, rainTime, rainLevel.</li>
 *   <li>{@code <world>/playerdata/<uuid>.properties} (vanilla
 *       {@code playerdata/<uuid>.dat}): name, x/y/z, yaw/pitch, heldSlot.</li>
 * </ul>
 *
 * <p>INVENTORY_STUB: the only inventory state persisted is the held
 * hotbar slot index. The inventory is tracked in memory and placement consumes
 * from it ({@code BlockPlacing}), but item stacks are not written yet, so
 * none are saved and none are faked; real item-stack NBT arrives with the
 * inventory persistence slice.
 *
 * <p>Load is all-or-nothing per file: a missing file, a wrong format
 * version, a missing key, an unparseable or out-of-range value reads as
 * null and the caller keeps its defaults. Nothing half-loads. Writes go
 * to a temp file then move into place, so a crash mid-save leaves the
 * previous file intact.
 *
 * <p>Thread-safety: stateless.
 */
public final class StateStore {

    /** Format version written as {@code format=}; readers refuse others. */
    public static final int FORMAT = 1;

    /** Level state file under the world root. */
    public static final String LEVEL_FILE = "kestrel-level.properties";

    /** Player state folder under the world root. */
    public static final String PLAYER_DIR = "playerdata";

    private static final String PLAYER_SUFFIX = ".properties";

    /** Hotbar slots (vanilla {@code Inventory.getSelectionSize}). */
    public static final int HOTBAR_SLOTS = 9;

    private StateStore() {
    }

    /** World clocks (overworld dayTime and theEndTicks) plus weather. gameTime echoes the sidecar's loop clock. */
    public record LevelState(long gameTime, long dayTime, boolean raining, int rainTime,
            float rainLevel, long theEndTicks) {
        /** Backward-compatible constructor defaulting theEndTicks to 0L. */
        public LevelState(long gameTime, long dayTime, boolean raining, int rainTime, float rainLevel) {
            this(gameTime, dayTime, raining, rainTime, rainLevel, 0L);
        }

        /** Fresh-world defaults: midnight-zero clocks, clear, no countdown. */
        public static final LevelState DEFAULT = new LevelState(0, 0, false, 0, 0.0f, 0L);
    }

    /** One player's saved position and INVENTORY_STUB held slot. */
    public record PlayerState(UUID profile, String name, double x, double y, double z,
            float yaw, float pitch, int heldSlot) {
    }

    public static Path levelPath(Path worldRoot) {
        return worldRoot.resolve(LEVEL_FILE);
    }

    public static Path playerPath(Path worldRoot, UUID profile) {
        return worldRoot.resolve(PLAYER_DIR).resolve(profile + PLAYER_SUFFIX);
    }

    // ---- write ----

    public static void writeLevel(Path worldRoot, LevelState s) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("# Kestrel level state (G4). dayTime >= 0; rainTime counts down to a flip.\n");
        b.append("format=").append(FORMAT).append('\n');
        b.append("gameTime=").append(s.gameTime()).append('\n');
        b.append("dayTime=").append(s.dayTime()).append('\n');
        b.append("theEndTicks=").append(s.theEndTicks()).append('\n');
        b.append("raining=").append(s.raining()).append('\n');
        b.append("rainTime=").append(s.rainTime()).append('\n');
        b.append("rainLevel=").append(s.rainLevel()).append('\n');
        writeAtomic(levelPath(worldRoot), b.toString());
    }

    public static void writePlayer(Path worldRoot, PlayerState s) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("# Kestrel player state (G4). heldSlot is the INVENTORY_STUB: no item stacks yet.\n");
        b.append("format=").append(FORMAT).append('\n');
        b.append("profile=").append(s.profile()).append('\n');
        b.append("name=").append(escape(s.name())).append('\n');
        // Double/Float.toString round-trip exactly through parseDouble/parseFloat.
        b.append("x=").append(s.x()).append('\n');
        b.append("y=").append(s.y()).append('\n');
        b.append("z=").append(s.z()).append('\n');
        b.append("yaw=").append(s.yaw()).append('\n');
        b.append("pitch=").append(s.pitch()).append('\n');
        b.append("heldSlot=").append(s.heldSlot()).append('\n');
        writeAtomic(playerPath(worldRoot, s.profile()), b.toString());
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.ISO_8859_1);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Properties escaping for the one free-text value: anything outside [A-Za-z0-9_-] as a u-escape. */
    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-') {
                b.append(c);
            } else {
                b.append(String.format("\\u%04x", (int) c));
            }
        }
        return b.toString();
    }

    // ---- read ----

    /** Level state, or null when missing or invalid (warns on invalid). */
    public static LevelState readLevel(Path worldRoot) {
        Path path = levelPath(worldRoot);
        Properties p = load(path);
        if (p == null) {
            return null;
        }
        try {
            long gameTime = Long.parseLong(req(p, "gameTime"));
            long dayTime = Long.parseLong(req(p, "dayTime"));
            String endStr = p.getProperty("theEndTicks");
            long theEndTicks = endStr != null ? Long.parseLong(endStr) : 0L;
            boolean raining = parseBool(req(p, "raining"));
            int rainTime = Integer.parseInt(req(p, "rainTime"));
            float rainLevel = Float.parseFloat(req(p, "rainLevel"));
            if (gameTime < 0 || dayTime < 0 || theEndTicks < 0 || rainTime < 0
                    || !(rainLevel >= 0.0f && rainLevel <= 1.0f)) {
                throw new IllegalArgumentException("value out of range");
            }
            return new LevelState(gameTime, dayTime, raining, rainTime, rainLevel, theEndTicks);
        } catch (RuntimeException e) {
            warn(path, e);
            return null;
        }
    }

    /** One player's state, or null when missing or invalid (warns on invalid). */
    public static PlayerState readPlayer(Path worldRoot, UUID profile) {
        return readPlayerFile(playerPath(worldRoot, profile), profile);
    }

    /** Every valid player file under the world root; invalid ones are skipped with a warning. */
    public static List<PlayerState> readAllPlayers(Path worldRoot) {
        List<PlayerState> out = new ArrayList<>();
        Path dir = worldRoot.resolve(PLAYER_DIR);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var stream = Files.list(dir)) {
            for (Path f : stream.sorted().toList()) {
                String fn = f.getFileName().toString();
                if (!fn.endsWith(PLAYER_SUFFIX)) {
                    continue;
                }
                UUID id;
                try {
                    id = UUID.fromString(fn.substring(0, fn.length() - PLAYER_SUFFIX.length()));
                } catch (IllegalArgumentException e) {
                    warn(f, e);
                    continue;
                }
                PlayerState s = readPlayerFile(f, id);
                if (s != null) {
                    out.add(s);
                }
            }
        } catch (IOException e) {
            warn(dir, e);
        }
        return out;
    }

    private static PlayerState readPlayerFile(Path path, UUID expected) {
        Properties p = load(path);
        if (p == null) {
            return null;
        }
        try {
            UUID profile = UUID.fromString(req(p, "profile"));
            if (!profile.equals(expected)) {
                throw new IllegalArgumentException("profile " + profile + " in file for " + expected);
            }
            String name = req(p, "name");
            double x = finite(Double.parseDouble(req(p, "x")));
            double y = finite(Double.parseDouble(req(p, "y")));
            double z = finite(Double.parseDouble(req(p, "z")));
            float yaw = (float) finite(Float.parseFloat(req(p, "yaw")));
            float pitch = (float) finite(Float.parseFloat(req(p, "pitch")));
            int heldSlot = Integer.parseInt(req(p, "heldSlot"));
            if (heldSlot < 0 || heldSlot >= HOTBAR_SLOTS) {
                throw new IllegalArgumentException("heldSlot out of range " + heldSlot);
            }
            return new PlayerState(profile, name, x, y, z, yaw, pitch, heldSlot);
        } catch (RuntimeException e) {
            warn(path, e);
            return null;
        }
    }

    /** Parsed properties with a matching format, null when missing; null plus a warning when unreadable. */
    private static Properties load(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            Properties p = new Properties();
            p.load(new StringReader(Files.readString(path, StandardCharsets.ISO_8859_1)));
            if (!String.valueOf(FORMAT).equals(p.getProperty("format"))) {
                throw new IllegalArgumentException("unknown format " + p.getProperty("format"));
            }
            return p;
        } catch (IOException | RuntimeException e) {
            warn(path, e);
            return null;
        }
    }

    private static String req(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null) {
            throw new IllegalArgumentException("missing key " + key);
        }
        return v.trim();
    }

    private static boolean parseBool(String v) {
        if (v.equals("true")) {
            return true;
        }
        if (v.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException("not a boolean: " + v);
    }

    private static double finite(double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("non-finite value " + v);
        }
        return v;
    }

    private static void warn(Path path, Exception e) {
        System.out.println("[kestrel] ignoring unreadable state file " + path + ": " + e.getMessage());
    }
}
