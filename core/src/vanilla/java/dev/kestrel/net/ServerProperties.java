package dev.kestrel.net;

import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.server.dedicated.Settings;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;

/**
 * Server properties component managing {@code server.properties} at the server root.
 *
 * <p>Provides full parity with vanilla Minecraft 26.2 dedicated server configuration:
 * reads {@code <root>/server.properties}, generates the full vanilla default configuration
 * when the file is missing, and preserves all comments, blank lines, and unknown configuration
 * keys across round-trips and rewrites.
 *
 * <h2>Configuration Precedence</h2>
 * Configuration values are resolved in strict priority order:
 * <ol>
 *   <li><b>Explicit command-line arguments:</b> Positional arguments ({@code <world> <port> [seed]})
 *       and flags (such as {@code --online}, {@code --offline}, {@code --no-save}) always take
 *       highest precedence and override any corresponding value in {@code server.properties}.
 *       This guarantees that existing deploy invocations keep working byte for byte.</li>
 *   <li><b>File values:</b> Values defined in {@code <root>/server.properties}.</li>
 *   <li><b>Vanilla defaults:</b> Vanilla 26.2 defaults derived from
 *       {@link net.minecraft.server.dedicated.DedicatedServerProperties}.</li>
 * </ol>
 *
 * <h2>Consumed / Honoured Keys</h2>
 * The following 14 vanilla keys are actively consumed and wired into Kestrel subsystems today. Consumed
 * means a caller outside this class reads the key and behaviour changes because of it; keys that are only
 * parsed, validated and round-tripped are listed in the next section even when they are read on startup.
 * <ul>
 *   <li>{@code server-port}: TCP listener bind port (default: {@code 25565}).</li>
 *   <li>{@code motd}: server message of the day (default: {@code A Minecraft Server}).</li>
 *   <li>{@code max-players}: maximum simultaneous connected players before logins are refused (default: {@code 20}).</li>
 *   <li>{@code view-distance}: server view distance cap in chunks (default: {@code 10}).</li>
 *   <li>{@code entity-broadcast-range-percentage}: scales every entity tracking range, as
 *       {@code DedicatedServer.getScaledTrackingDistance} does (default: {@code 100}).</li>
 *   <li>{@code simulation-distance}: simulation distance in chunks sent in the join packet (default: {@code 10}).</li>
 *   <li>{@code online-mode}: whether to run full Mojang RSA/AES authentication (default: {@code true}).</li>
 *   <li>{@code gamemode}: default game mode for joining players (default: {@code survival}).</li>
 *   <li>{@code difficulty}: world difficulty level sent on join (default: {@code easy}).</li>
 *   <li>{@code hardcore}: whether hardcore mode is enabled in the join packet (default: {@code false}).</li>
 *   <li>{@code spawn-protection}: Chebyshev radius in blocks around spawn protected from modifications (default: {@code 16}).</li>
 *   <li>{@code allow-flight}: whether flight is allowed without anti-cheat speed violations (default: {@code false}).</li>
 *   <li>{@code generate-structures}: whether structures are generated during world generation (default: {@code true}).</li>
 *   <li>{@code pause-when-empty-seconds}: seconds of empty server before simulation pauses (default: {@code 60}).</li>
 * </ul>
 *
 * <h2>Parsed but Not Yet Consumed Keys</h2>
 * All remaining 55 vanilla 26.2 properties are parsed, validated, and round-tripped with full fidelity,
 * but are not yet consumed by gameplay mechanics in Kestrel. This includes twelve keys that earlier
 * versions of this comment listed as consumed while nothing read them: {@code level-name},
 * {@code level-seed}, {@code white-list}, {@code enforce-whitelist}, {@code force-gamemode},
 * {@code pvp}, {@code player-idle-timeout}, {@code network-compression-threshold},
 * {@code op-permission-level}, {@code level-type}, {@code enable-rcon} and {@code enable-query}.
 * Verified on 2026-09-26 by grepping every one of them, by accessor name and by literal key, outside
 * this file: no caller. Note that {@code enable-rcon} and {@code enable-query} are not even logged as
 * unbuilt, which an earlier version of this comment claimed.
 * {@code accepts-transfers}, {@code broadcast-console-to-ops}, {@code broadcast-rcon-to-ops},
 * {@code bug-report-link}, {@code chat-spam-threshold-seconds}, {@code command-spam-threshold-seconds},
 * {@code enable-code-of-conduct}, {@code enable-jmx-monitoring}, {@code enable-status},
 * {@code enforce-secure-profile},
 * {@code function-permission-level}, {@code generator-settings}, {@code hide-online-players},
 * {@code initial-disabled-packs}, {@code initial-enabled-packs}, {@code log-ips},
 * {@code management-server-allowed-origins}, {@code management-server-enabled},
 * {@code management-server-host}, {@code management-server-port}, {@code management-server-secret},
 * {@code management-server-tls-enabled}, {@code management-server-tls-keystore},
 * {@code management-server-tls-keystore-password}, {@code max-chained-neighbor-updates},
 * {@code max-tick-time}, {@code max-world-size},
 * {@code prevent-proxy-connections}, {@code query.port}, {@code rate-limit}, {@code rcon.password},
 * {@code rcon.port}, {@code region-file-compression}, {@code require-resource-pack},
 * {@code resource-pack}, {@code resource-pack-id}, {@code resource-pack-prompt},
 * {@code resource-pack-sha1}, {@code server-ip}, {@code status-heartbeat-interval},
 * {@code sync-chunk-writes}, {@code text-filtering-config}, {@code text-filtering-version},
 * {@code use-native-transport}.
 */
public final class ServerProperties {

    /** Default fallback seed when none is configured in properties or on command line. */
    public static final long DEFAULT_SEED = 4242424242L;

    private final Path path;
    private final Properties properties;
    private DedicatedServerProperties dedicated;
    private final List<LineEntry> lineEntries;

    private sealed interface LineEntry permits CommentEntry, PropertyEntry {}
    private record CommentEntry(String line) implements LineEntry {}
    private record PropertyEntry(String key, String delimiter, String parsedValue, List<String> rawLines)
            implements LineEntry {}

    private ServerProperties(Path path, Properties properties, DedicatedServerProperties dedicated,
            List<LineEntry> lineEntries) {
        this.path = path;
        this.properties = properties;
        this.dedicated = dedicated;
        this.lineEntries = lineEntries;
    }

    /**
     * Resolves the {@code server.properties} path from a root directory or explicit properties path.
     */
    public static Path resolvePropertiesPath(Path rootOrFile) {
        if (Files.isDirectory(rootOrFile)) {
            return rootOrFile.resolve("server.properties");
        }
        if (rootOrFile.getFileName() != null
                && rootOrFile.getFileName().toString().endsWith(".properties")) {
            return rootOrFile;
        }
        return rootOrFile.resolve("server.properties");
    }

    /**
     * Loads {@code server.properties} from the given root directory or file path.
     * If the file does not exist, writes the full vanilla default file and returns the loaded instance.
     */
    public static ServerProperties loadOrCreate(Path rootOrFile) throws IOException {
        Path target = resolvePropertiesPath(rootOrFile);
        if (!Files.exists(target)) {
            writeVanillaDefaults(target);
        }
        return load(target);
    }

    /**
     * Loads {@code server.properties} from an existing file path.
     */
    public static ServerProperties load(Path target) throws IOException {
        Path resolved = resolvePropertiesPath(target);
        List<String> rawLines = Files.exists(resolved)
                ? Files.readAllLines(resolved, StandardCharsets.UTF_8)
                : new ArrayList<>();

        Properties props = Settings.loadFromFile(resolved);
        DedicatedServerProperties dedicated = new DedicatedServerProperties(props);
        List<LineEntry> lineEntries = parseLineEntries(rawLines);

        return new ServerProperties(resolved, props, dedicated, lineEntries);
    }

    /**
     * Writes the full vanilla default properties to the specified path using vanilla's DedicatedServerSettings.
     */
    public static void writeVanillaDefaults(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(target)) {
            Files.createFile(target);
        }
        DedicatedServerSettings settings = new DedicatedServerSettings(target);
        settings.forceSave();
    }

    private static List<LineEntry> parseLineEntries(List<String> rawLines) {
        List<LineEntry> result = new ArrayList<>();
        int i = 0;
        while (i < rawLines.size()) {
            String line = rawLines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                result.add(new CommentEntry(line));
                i++;
                continue;
            }

            List<String> propRawLines = new ArrayList<>();
            propRawLines.add(line);
            StringBuilder fullLine = new StringBuilder(line);
            while (hasTrailingContinuation(fullLine.toString()) && i + 1 < rawLines.size()) {
                i++;
                String nextLine = rawLines.get(i);
                propRawLines.add(nextLine);
                fullLine.setLength(fullLine.length() - 1);
                fullLine.append(nextLine.stripLeading());
            }

            try {
                Properties single = new Properties();
                single.load(new StringReader(fullLine.toString()));
                if (!single.isEmpty()) {
                    String key = single.stringPropertyNames().iterator().next();
                    String val = single.getProperty(key);
                    String delim = extractDelimiter(fullLine.toString());
                    result.add(new PropertyEntry(key, delim, val, propRawLines));
                } else {
                    result.add(new CommentEntry(line));
                }
            } catch (IOException e) {
                result.add(new CommentEntry(line));
            }
            i++;
        }
        return result;
    }

    private static boolean hasTrailingContinuation(String s) {
        int backslashes = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            if (s.charAt(i) == '\\') {
                backslashes++;
            } else {
                break;
            }
        }
        return (backslashes % 2) != 0;
    }

    private static String extractDelimiter(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                break;
            }
            i++;
        }
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i < s.length()) {
            char c = s.charAt(i);
            if (c == '=' || c == ':') {
                return String.valueOf(c);
            }
        }
        return "=";
    }

    /**
     * Saves the current properties back to the source file path, preserving comments and unknown keys.
     */
    public void save() throws IOException {
        store(this.path);
    }

    /**
     * Stores properties to the specified target path, preserving comments and unknown keys.
     */
    public void store(Path target) throws IOException {
        List<String> output = new ArrayList<>();
        Set<String> writtenKeys = new LinkedHashSet<>();

        for (LineEntry entry : lineEntries) {
            if (entry instanceof CommentEntry comment) {
                output.add(comment.line());
            } else if (entry instanceof PropertyEntry prop) {
                String current = properties.getProperty(prop.key());
                if (current != null) {
                    writtenKeys.add(prop.key());
                    if (current.equals(prop.parsedValue())) {
                        output.addAll(prop.rawLines());
                    } else {
                        output.add(prop.key() + prop.delimiter() + escapeValue(current));
                    }
                }
            }
        }

        // Append any properties present in the Properties map that were not in the source file
        for (String key : properties.stringPropertyNames()) {
            if (!writtenKeys.contains(key)) {
                output.add(key + "=" + escapeValue(properties.getProperty(key)));
                writtenKeys.add(key);
            }
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, output, StandardCharsets.UTF_8);
    }

    private static String escapeValue(String val) {
        if (val == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < val.length(); i++) {
            char c = val.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\f' -> sb.append("\\f");
                case ':' -> sb.append("\\:");
                case '=' -> sb.append("\\=");
                default -> {
                    if (i == 0 && c == ' ') {
                        sb.append("\\ ");
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ---- Getters and Setters ----

    public Path path() {
        return path;
    }

    public Properties rawProperties() {
        return properties;
    }

    public DedicatedServerProperties dedicated() {
        return dedicated;
    }

    public String get(String key) {
        return properties.getProperty(key);
    }

    public String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public void set(String key, String value) {
        properties.setProperty(key, value);
        this.dedicated = new DedicatedServerProperties(properties);
    }

    // Typed accessors for consumed keys
    public int serverPort() {
        return dedicated.serverPort;
    }

    public void setServerPort(int port) {
        set("server-port", Integer.toString(port));
    }

    public String levelName() {
        return dedicated.levelName;
    }

    public void setLevelName(String name) {
        set("level-name", name);
    }

    public String levelSeed() {
        return properties.getProperty("level-seed", "");
    }

    public void setLevelSeed(String seed) {
        set("level-seed", seed);
    }

    /**
     * Resolves the configured seed into a long value, defaulting to {@link #DEFAULT_SEED} if empty.
     */
    public long levelSeedLong() {
        String s = levelSeed();
        if (s == null || s.isBlank()) {
            return DEFAULT_SEED;
        }
        return parseSeed(s);
    }

    public static long parseSeed(String seedStr) {
        if (seedStr == null || seedStr.isBlank()) {
            return DEFAULT_SEED;
        }
        OptionalLong parsed = WorldOptions.parseSeed(seedStr);
        return parsed.orElse(DEFAULT_SEED);
    }

    public String motd() {
        return dedicated.motd.get();
    }

    public void setMotd(String motd) {
        set("motd", motd);
    }

    public int maxPlayers() {
        return dedicated.maxPlayers.get();
    }

    public void setMaxPlayers(int maxPlayers) {
        set("max-players", Integer.toString(maxPlayers));
    }

    public int viewDistance() {
        return dedicated.viewDistance.get();
    }

    /** {@code entity-broadcast-range-percentage}, read by the entity tracker. */
    public int entityBroadcastRangePercentage() {
        return dedicated.entityBroadcastRangePercentage.get();
    }

    public void setViewDistance(int viewDistance) {
        set("view-distance", Integer.toString(viewDistance));
    }

    public int simulationDistance() {
        return dedicated.simulationDistance.get();
    }

    public void setSimulationDistance(int simulationDistance) {
        set("simulation-distance", Integer.toString(simulationDistance));
    }

    public boolean onlineMode() {
        return dedicated.onlineMode;
    }

    public void setOnlineMode(boolean online) {
        set("online-mode", Boolean.toString(online));
    }

    public boolean whiteList() {
        return dedicated.whiteList.get();
    }

    public void setWhiteList(boolean whiteList) {
        set("white-list", Boolean.toString(whiteList));
    }

    public boolean enforceWhitelist() {
        return dedicated.enforceWhitelist.get();
    }

    public void setEnforceWhitelist(boolean enforce) {
        set("enforce-whitelist", Boolean.toString(enforce));
    }

    public GameType gamemode() {
        return dedicated.gameMode.get();
    }

    public void setGamemode(GameType gameType) {
        set("gamemode", gameType.getName());
    }

    public boolean forceGamemode() {
        return dedicated.forceGameMode.get();
    }

    public void setForceGamemode(boolean force) {
        set("force-gamemode", Boolean.toString(force));
    }

    public Difficulty difficulty() {
        return dedicated.difficulty.get();
    }

    public void setDifficulty(Difficulty difficulty) {
        set("difficulty", difficulty.getSerializedName());
    }

    public boolean hardcore() {
        return dedicated.hardcore;
    }

    public void setHardcore(boolean hardcore) {
        set("hardcore", Boolean.toString(hardcore));
    }

    public boolean pvp() {
        return Boolean.parseBoolean(properties.getProperty("pvp", "true"));
    }

    public void setPvp(boolean pvp) {
        set("pvp", Boolean.toString(pvp));
    }

    public int spawnProtection() {
        return dedicated.spawnProtection.get();
    }

    public void setSpawnProtection(int spawnProtection) {
        set("spawn-protection", Integer.toString(spawnProtection));
    }

    public boolean allowFlight() {
        return dedicated.allowFlight.get();
    }

    public void setAllowFlight(boolean allowFlight) {
        set("allow-flight", Boolean.toString(allowFlight));
    }

    public int pauseWhenEmptySeconds() {
        return dedicated.pauseWhenEmptySeconds.get();
    }

    public void setPauseWhenEmptySeconds(int seconds) {
        set("pause-when-empty-seconds", Integer.toString(seconds));
    }

    public int playerIdleTimeout() {
        return dedicated.playerIdleTimeout.get();
    }

    public void setPlayerIdleTimeout(int timeoutMinutes) {
        set("player-idle-timeout", Integer.toString(timeoutMinutes));
    }

    public int networkCompressionThreshold() {
        return dedicated.networkCompressionThreshold;
    }

    public void setNetworkCompressionThreshold(int threshold) {
        set("network-compression-threshold", Integer.toString(threshold));
    }

    public int opPermissionLevel() {
        return dedicated.opPermissions.get() != null
                ? dedicated.opPermissions.get().level().id()
                : 4;
    }

    public void setOpPermissionLevel(int level) {
        set("op-permission-level", Integer.toString(level));
    }

    public boolean generateStructures() {
        return dedicated.worldOptions.generateStructures();
    }

    public void setGenerateStructures(boolean generate) {
        set("generate-structures", Boolean.toString(generate));
    }

    public String levelType() {
        return properties.getProperty("level-type", "minecraft:normal");
    }

    public void setLevelType(String levelType) {
        set("level-type", levelType);
    }

    public boolean enableRcon() {
        return dedicated.enableRcon;
    }

    public void setEnableRcon(boolean enable) {
        set("enable-rcon", Boolean.toString(enable));
    }

    public boolean enableQuery() {
        return dedicated.enableQuery;
    }

    public void setEnableQuery(boolean enable) {
        set("enable-query", Boolean.toString(enable));
    }

    // ---- Effective Configuration Resolution with Precedence ----

    /**
     * Resolved effective server launch configuration combining command-line arguments and
     * properties with command-line arguments winning.
     */
    public record EffectiveConfig(
            Path worldDir,
            int port,
            long seed,
            boolean online,
            boolean importMode,
            boolean noSave,
            int[] importSquare,
            int[] generateSquare,
            int[] spawnArg,
            int memProbeSeconds,
            Path blockHashCsv,
            Long genBudgetMs,
            boolean asyncChunkGen,
            int carverThreads,
            ServerProperties properties
    ) {}

    /**
     * Resolves effective server configuration given root directory and command-line arguments.
     * Command-line arguments override any property in {@code server.properties}.
     */
    public static EffectiveConfig resolve(Path root, String[] args) throws IOException {
        Path propsPath = root.resolve("server.properties");
        for (String a : args) {
            if (a.startsWith("--properties=")) {
                propsPath = Path.of(a.substring("--properties=".length()));
            } else if (a.startsWith("--server-properties=")) {
                propsPath = Path.of(a.substring("--server-properties=".length()));
            }
        }

        ServerProperties properties = loadOrCreate(propsPath);

        // Precedence step 1: start from properties file
        Path world = Path.of(properties.levelName());
        int port = properties.serverPort();
        long seed = properties.levelSeedLong();
        boolean online = properties.onlineMode();

        // Parse positional CLI arguments
        int argi = 0;
        if (args.length > argi && !args[argi].startsWith("--")) {
            world = Path.of(args[argi++]);
        }
        if (args.length > argi && !args[argi].startsWith("--")) {
            port = Integer.parseInt(args[argi++]);
        }
        if (args.length > argi && !args[argi].startsWith("--")) {
            seed = parseSeed(args[argi++]);
        }

        boolean importMode = false;
        boolean noSave = false;
        int[] importSquare = null;
        int[] generateSquare = null;
        int[] spawnArg = null;
        int memProbeSeconds = 0;
        Path blockHashCsv = null;
        Long genBudgetMs = null;
        boolean asyncChunkGen = true;
        int carverThreads = Math.max(0, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));

        String usage = "usage: KestrelServer [worldDir] [port] [seed] [--online]"
                + " [--import] [--import-square=cx,cz,half] [--spawn=x,y,z] [--no-save]"
                + " [--mem-probe=seconds] [--block-hash-csv=path]"
                + " [--generate=cx,cz,half] [--gen-budget-ms=N] [--sync-chunkgen]"
                + " [--async-chunkgen] [--carver-threads=N] [--properties=path]";

        for (; argi < args.length; argi++) {
            String a = args[argi];
            if (a.equals("--online")) {
                online = true;
            } else if (a.equals("--offline")) {
                online = false;
            } else if (a.equals("--import")) {
                importMode = true;
            } else if (a.equals("--no-save")) {
                noSave = true;
            } else if (a.startsWith("--import-square=")) {
                importSquare = ints(a.substring("--import-square=".length()), 3, usage);
            } else if (a.startsWith("--spawn=")) {
                spawnArg = ints(a.substring("--spawn=".length()), 3, usage);
            } else if (a.startsWith("--mem-probe=")) {
                memProbeSeconds = ints(a.substring("--mem-probe=".length()), 1, usage)[0];
            } else if (a.startsWith("--block-hash-csv=")) {
                blockHashCsv = Path.of(a.substring("--block-hash-csv=".length()));
            } else if (a.startsWith("--generate=")) {
                generateSquare = ints(a.substring("--generate=".length()), 3, usage);
            } else if (a.startsWith("--gen-budget-ms=")) {
                genBudgetMs = (long) ints(a.substring("--gen-budget-ms=".length()), 1, usage)[0];
            } else if (a.equals("--async-chunkgen")) {
                asyncChunkGen = true;
            } else if (a.equals("--sync-chunkgen")) {
                asyncChunkGen = false;
            } else if (a.startsWith("--carver-threads=")) {
                carverThreads = Integer.parseInt(a.substring("--carver-threads=".length()));
            } else if (a.startsWith("--properties=") || a.startsWith("--server-properties=")) {
                // already handled
            } else {
                throw new IllegalArgumentException("Unknown argument: " + a + "\n" + usage);
            }
        }

        if (importMode) {
            noSave = true;
        }

        return new EffectiveConfig(
                world,
                port,
                seed,
                online,
                importMode,
                noSave,
                importSquare,
                generateSquare,
                spawnArg,
                memProbeSeconds,
                blockHashCsv,
                genBudgetMs,
                asyncChunkGen,
                carverThreads,
                properties
        );
    }

    private static int[] ints(String s, int count, String usage) {
        String[] parts = s.split(",");
        if (parts.length != count) {
            throw new IllegalArgumentException(usage);
        }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }
}
