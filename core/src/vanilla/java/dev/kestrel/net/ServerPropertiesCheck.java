package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dedicated.DedicatedServerSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Roadmap S32 reproduction gate: verifies {@code server.properties} parity with
 * vanilla 26.2.
 *
 * <ol>
 *   <li><b>Vanilla defaults.</b> The file the component writes into an empty
 *       directory is diffed against a file written independently by vanilla's
 *       own {@link DedicatedServerSettings}, key by key and value by value. A
 *       minimum key count guards against two empty files trivially matching.</li>
 *   <li><b>Round trip.</b> Comments, blank lines, unknown keys and the
 *       delimiter style survive a load, a set and a save, and no known key is
 *       lost on the way.</li>
 *   <li><b>The file is authoritative.</b> What the file says is what the
 *       component reports, and what a {@link NetServer} handed those
 *       properties reports.</li>
 *   <li><b>Precedence.</b> A command line argument beats the file, and the file
 *       beats Kestrel's built-in default. Verified through the real
 *       {@link KestrelServer#resolvePort} and
 *       {@link KestrelServer#resolveOnline}, with a non-default value in the
 *       file so the two branches cannot pass by coincidence.</li>
 * </ol>
 */
public final class ServerPropertiesCheck {

    /** Keys vanilla always writes. Two empty files would otherwise "match". */
    private static final int MIN_VANILLA_KEYS = 40;

    /**
     * Keys vanilla generates randomly for each file, so two independent writes
     * must differ. They are excluded from the value diff and checked for
     * presence and non-emptiness instead.
     */
    private static final Set<String> NON_DETERMINISTIC = Set.of("management-server-secret");

    private ServerPropertiesCheck() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        Path tempDir = Files.createTempDirectory("kestrel-properties-test-");
        try {
            checkVanillaDefaults(tempDir.resolve("defaults"), failures);
            checkRoundTrip(tempDir.resolve("roundtrip"), failures);
            checkFileIsAuthoritative(tempDir.resolve("authoritative"), failures);
            checkPrecedence(tempDir.resolve("precedence"), failures);
        } finally {
            deleteTree(tempDir);
        }

        if (!failures.isEmpty()) {
            System.err.println("ServerPropertiesCheck FAILED with " + failures.size() + " failure(s):");
            for (String f : failures) {
                System.err.println("  - " + f);
            }
            System.exit(1);
        }
        System.out.println("ServerPropertiesCheck: PASS");
    }

    /**
     * 1. The written default file must be vanilla's own default file. The
     * reference is produced by vanilla's {@link DedicatedServerSettings} in a
     * separate directory, so this compares two independent writes rather than
     * comparing the component against itself.
     */
    private static void checkVanillaDefaults(Path dir, List<String> failures) throws IOException {
        Files.createDirectories(dir);
        ServerProperties ours = ServerProperties.loadOrCreate(dir);
        assertField(Files.isRegularFile(ours.path()), "loadOrCreate did not create " + ours.path(), failures);

        Path referenceDir = Files.createTempDirectory("kestrel-vanilla-reference-");
        try {
            Path reference = referenceDir.resolve("server.properties");
            Files.createFile(reference);
            new DedicatedServerSettings(reference).forceSave();

            Map<String, String> oursPairs = readPairs(ours.path());
            Map<String, String> vanillaPairs = readPairs(reference);

            assertField(vanillaPairs.size() >= MIN_VANILLA_KEYS,
                    "vanilla reference wrote only " + vanillaPairs.size() + " keys, expected at least "
                            + MIN_VANILLA_KEYS + ": the reference itself is broken",
                    failures);

            Set<String> missing = new TreeSet<>(vanillaPairs.keySet());
            missing.removeAll(oursPairs.keySet());
            assertField(missing.isEmpty(), "keys vanilla writes but Kestrel does not: " + missing, failures);

            Set<String> extra = new TreeSet<>(oursPairs.keySet());
            extra.removeAll(vanillaPairs.keySet());
            assertField(extra.isEmpty(), "keys Kestrel writes but vanilla does not: " + extra, failures);

            List<String> valueDiffs = new ArrayList<>();
            for (Map.Entry<String, String> e : vanillaPairs.entrySet()) {
                if (NON_DETERMINISTIC.contains(e.getKey())) {
                    continue;
                }
                String got = oursPairs.get(e.getKey());
                if (got != null && !got.equals(e.getValue())) {
                    valueDiffs.add(e.getKey() + ": kestrel=" + got + " vanilla=" + e.getValue());
                }
            }
            assertField(valueDiffs.isEmpty(), "default values differ from vanilla: " + valueDiffs, failures);

            for (String random : NON_DETERMINISTIC) {
                if (vanillaPairs.containsKey(random)) {
                    assertField(!oursPairs.getOrDefault(random, "").isBlank(),
                            "randomly generated key " + random + " was written empty", failures);
                }
            }

            System.out.println("[ServerPropertiesCheck] default file: " + oursPairs.size()
                    + " keys, identical to vanilla's own write ("
                    + NON_DETERMINISTIC.size() + " non-deterministic key excluded)");
        } finally {
            deleteTree(referenceDir);
        }

        // A handful of well known defaults, asserted literally as well, so a
        // wholesale change in vanilla cannot make both sides drift together.
        assertEqual(ours.serverPort(), 25565, "default server-port", failures);
        assertEqual(ours.onlineMode(), Boolean.TRUE, "default online-mode", failures);
        assertEqual(ours.maxPlayers(), 20, "default max-players", failures);
        assertEqual(ours.viewDistance(), 10, "default view-distance", failures);
        assertEqual(ours.motd(), "A Minecraft Server", "default motd", failures);
        assertEqual(ours.pauseWhenEmptySeconds(), 60,
                "default pause-when-empty-seconds", failures);
    }

    /**
     * 2. Comments, blank lines, unknown keys and the delimiter style survive a
     * load, a set and a save, and no known key is lost.
     */
    private static void checkRoundTrip(Path dir, List<String> failures) throws IOException {
        Files.createDirectories(dir);
        ServerProperties first = ServerProperties.loadOrCreate(dir);
        Path file = first.path();

        Map<String, String> before = readPairs(file);
        String decorated = Files.readString(file, StandardCharsets.UTF_8)
                + System.lineSeparator() + "# an owner's comment"
                + System.lineSeparator() + "custom-unknown-key=keep-me"
                + System.lineSeparator() + System.lineSeparator()
                + "level-name : a-colon-delimited-value" + System.lineSeparator();
        Files.writeString(file, decorated, StandardCharsets.UTF_8);

        ServerProperties reloaded = ServerProperties.load(file);
        reloaded.setMaxPlayers(77);
        reloaded.save();

        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertField(after.contains("# an owner's comment"), "comment was not preserved", failures);
        assertField(after.contains("custom-unknown-key=keep-me"), "unknown key was not preserved", failures);
        assertField(after.contains("level-name : a-colon-delimited-value"),
                "colon delimiter was not preserved", failures);

        Map<String, String> afterPairs = readPairs(file);
        Set<String> lost = new TreeSet<>(before.keySet());
        lost.removeAll(afterPairs.keySet());
        assertField(lost.isEmpty(), "keys lost across the round trip: " + lost, failures);

        ServerProperties reread = ServerProperties.load(file);
        assertEqual(reread.maxPlayers(), 77, "modified max-players survives the rewrite", failures);
        assertEqual(reread.get("custom-unknown-key"), "keep-me", "unknown key is readable", failures);
        System.out.println("[ServerPropertiesCheck] round trip preserved "
                + before.size() + " keys plus comments and unknown keys");
    }

    /**
     * 3. The file is authoritative: what it says is what the component, and a
     * NetServer given those properties, reports.
     */
    private static void checkFileIsAuthoritative(Path dir, List<String> failures) throws IOException {
        Files.createDirectories(dir);
        ServerProperties props = ServerProperties.loadOrCreate(dir);
        props.setMaxPlayers(33);
        props.setViewDistance(7);
        props.setSimulationDistance(5);
        props.set("server-port", "25599");
        props.setMotd("Kestrel properties test");
        props.setPauseWhenEmptySeconds(0);
        props.save();

        ServerProperties reread = ServerProperties.load(props.path());
        assertEqual(reread.maxPlayers(), 33, "file max-players", failures);
        assertEqual(reread.viewDistance(), 7, "file view-distance", failures);
        assertEqual(reread.simulationDistance(), 5, "file simulation-distance", failures);
        assertEqual(reread.serverPort(), 25599, "file server-port", failures);
        assertEqual(reread.motd(), "Kestrel properties test", "file motd", failures);
        assertEqual(reread.pauseWhenEmptySeconds(), 0,
                "file pause-when-empty-seconds, including zero", failures);
        assertEqual(KestrelServer.resolvePauseWhenEmptySeconds(null, reread), 0,
                "live pause setting comes from server.properties", failures);

        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        try (NetServer net = new NetServer(loop, 0, Wire.DEFAULT_COMPRESSION_THRESHOLD, 4242424242L, false)) {
            // The startup path hands NetServer the resolved values, so mirror it
            // here: properties for the settings NetServer reads itself, plus the
            // resolved motd and cap for the ones it carries as fields.
            net.setProperties(reread);
            net.setMotd(KestrelServer.resolveMotd(null, reread));
            net.setMaxPlayers(KestrelServer.resolveMaxPlayers(null, reread));
            assertEqual(net.maxPlayers(), 33, "NetServer carries the file's max-players", failures);
            assertEqual(net.motd(), "Kestrel properties test", "NetServer carries the file's motd", failures);

            // A command line value overrides the file, per resolveMaxPlayers.
            assertEqual(KestrelServer.resolveMaxPlayers(5, reread), 5,
                    "command line max-players beats the file", failures);
            assertEqual(KestrelServer.resolveMotd("from the command line", reread),
                    "from the command line", "command line motd beats the file", failures);
        }
    }

    /**
     * 4. Precedence, through the real resolution functions, with a
     * non-default value in the file so the file branch cannot pass by
     * coincidence with Kestrel's own default.
     */
    private static void checkPrecedence(Path dir, List<String> failures) throws IOException {
        Files.createDirectories(dir);
        ServerProperties props = ServerProperties.loadOrCreate(dir);
        props.set("server-port", "25588");
        props.set("online-mode", "false");
        props.save();
        ServerProperties fromFile = ServerProperties.load(props.path());

        assertEqual(KestrelServer.resolvePort(null, fromFile), 25588,
                "file port is used when the command line is silent", failures);
        assertEqual(KestrelServer.resolvePort(12345, fromFile), 12345,
                "command line port beats the file", failures);

        assertEqual(KestrelServer.resolveOnline(null, fromFile), Boolean.FALSE,
                "file online-mode is used when the command line is silent", failures);
        assertEqual(KestrelServer.resolveOnline(Boolean.TRUE, fromFile), Boolean.TRUE,
                "command line --online beats the file", failures);

        ServerProperties onlineFile = ServerProperties.loadOrCreate(Files.createDirectories(
                dir.resolve("online")));
        assertEqual(onlineFile.onlineMode(), Boolean.TRUE, "vanilla default online-mode is true", failures);
        assertEqual(KestrelServer.resolveOnline(Boolean.FALSE, onlineFile), Boolean.FALSE,
                "command line --offline beats an online file", failures);
        System.out.println("[ServerPropertiesCheck] precedence: command line > file > built-in default");
    }

    /** Parses key/value pairs, ignoring comments and blank lines. */
    private static Map<String, String> readPairs(Path file) throws IOException {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            int colon = trimmed.indexOf(':');
            int split = eq < 0 ? colon : (colon < 0 ? eq : Math.min(eq, colon));
            if (split <= 0) {
                continue;
            }
            pairs.put(trimmed.substring(0, split).trim(), trimmed.substring(split + 1).trim());
        }
        return new TreeMap<>(pairs);
    }

    private static void deleteTree(Path root) {
        try (var s = Files.walk(root)) {
            s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void assertEqual(Object actual, Object expected, String message, List<String> failures) {
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(message + ": expected " + expected + " but was " + actual);
        }
    }

    private static void assertField(boolean condition, String message, List<String> failures) {
        if (!condition) {
            failures.add(message);
        }
    }
}
