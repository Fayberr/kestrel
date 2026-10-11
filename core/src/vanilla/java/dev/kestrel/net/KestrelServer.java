package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.persist.WorldStore;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stage 3 runnable server: loads (or creates) an overworld, ticks it
 * at 20 TPS, serves it on TCP. Usage:
 * {@code KestrelServer <worldDir> <port> [seed]}.
 *
 * <p>First boot (no persisted region files, no {@code --import}) drives
 * vanilla's own generator headlessly for {@code seed}
 * ({@link dev.kestrel.worldgen.HeadlessNoise}, {@code --generate}
 * optionally fills a square eagerly); any chunk beyond what booted
 * eagerly (or all of it, with no {@code --generate}) generates on demand
 * the first time the {@link ChunkSender} needs it
 * ({@link dev.kestrel.worldgen.LiveGenerator}, see
 * {@code preresearch/g2-livewiring-design.md}). Later boots load the
 * persisted world. The world saves every 6000 ticks (5 minutes, vanilla cadence), on
 * shutdown, and on a {@code save-all} console line: block diffs via
 * {@code WorldStore}, clock/weather/player positions via
 * {@link StateStore} (restored before the listener opens). Tick
 * networking (events, join bursts, chunks, keepalive) runs after every
 * world tick.
 *
 * <p>Player records live in the server's map; the tick thread owns
 * them, and it also owns every save (autosave, save-all, shutdown). The
 * shutdown hook only raises the stop flag, wakes the inter-tick sleep,
 * closes the listener, and joins the tick thread (bounded by
 * {@code -Dkestrel.shutdownTimeoutMs}, default 60000); the tick thread
 * finishes its current tick, then takes the final snapshot and save.
 * A save never overlaps a tick or another save.
 *
 * <p>World exclusivity: boot takes an exclusive OS lock on
 * {@code <worldDir>/.kestrel.lock} before touching region files or
 * binding the port, and refuses to start (exit 1) if another process
 * holds it. The lock is held until the final shutdown save completes.
 *
 * <p>G2 step-2 memory-run flags ({@code preresearch/g2-memory-design.md}
 * section 1): {@code --import} reads a sidecar-less vanilla world through
 * {@link WorldStore#importWorld} and logs the section census;
 * {@code --import-square=cx,cz,half} keeps only that chunk square;
 * {@code --spawn=x,y,z} pins the spawn (required for imports, the scan
 * hangs on them); {@code --no-save} disables autosave, save-all and the
 * shutdown save ({@code --import} implies it, so a corpus is never
 * rewritten); {@code --mem-probe=S} logs a GC'd {@link MemProbe} line
 * every S seconds; {@code --block-hash-csv=PATH} writes the blocks-only
 * per-chunk hash ({@link BlockHash}) after import.
 */
public final class KestrelServer {

    /**
     * File a packaged release carries next to its classpath root, holding the
     * commit the release was built from. See {@link #readBuildStamp()}.
     */
    private static final String BUILD_STAMP_FILE = "BUILD-COMMIT.txt";

    private KestrelServer() {
    }

    public static void main(String[] args) throws Exception {
        // Always the very first line out, before anything can fail: answers
        // "which fix is actually running" from the log/journal alone. Added
        // after a 2026-09-17 incident where a live-behavior bug report
        // turned out to be a JVM that had been running since before the fix
        // landed - a rebuilt classpath directory does not get picked up by
        // an already-running JVM, only a restart does, and there was no way
        // to tell from the log which commit was live without javap
        // bytecode inspection. Never allowed to fail boot (see logBuildInfo).
        logBuildInfo();
        String usage = "usage: KestrelServer <worldDir> [port] [seed] [--online] [--offline]"
                + " [--import] [--import-square=cx,cz,half] [--spawn=x,y,z] [--no-save]"
                + " [--mem-probe=seconds] [--block-hash-csv=path]"
                + " [--generate=cx,cz,half] (eager headless-generation square on a fresh"
                + " boot, centered spawn; every fresh boot live-generates on demand"
                + " beyond it regardless of this flag)"
                + " [--gen-budget-ms=N] (only with --sync-chunkgen; dev/test only: raises the per-tick on-demand"
                + " generation budget above the default 40ms so a solo tester exploring"
                + " fresh terrain converges faster, at the cost of a bigger tick stall"
                + " per extra fresh chunk that tick; do not use with other players online)"
                + " [--sync-chunkgen] (generate fresh chunks on the tick thread under the"
                + " per-tick budget instead of the default background generator; same"
                + " terrain, much slower; --async-chunkgen is accepted and is the default)"
                + " [--carver-threads=N] (threads for the chunk-local BIOMES/NOISE/SURFACE/"
                + "CARVERS stages; output is identical to 0, which runs them inline;"
                + " default: cores - 1, at most 3)"
                + " [--pause-when-empty-seconds=N] (seconds without players before pausing tick loop;"
                + " default: 60 from server.properties)"
                + " [--properties=path] (server.properties file, or the directory holding"
                + " it; default: the world's parent directory. Created with vanilla's own"
                + " defaults when missing. Command line arguments always override the file.)";
        if (args.length < 1) {
            System.out.println(usage);
            System.exit(2);
        }
        Path world = Path.of(args[0]);
        // The port is optional when a server.properties is present: an owner who
        // set server-port there expects it to be honoured, exactly as vanilla
        // does. A port on the command line still wins.
        int argi = 1;
        Integer portArg = null;
        if (args.length > argi && !args[argi].startsWith("--")) {
            portArg = Integer.parseInt(args[argi++]);
        }
        long seed = 4242424242L;
        if (args.length > argi && !args[argi].startsWith("--")) {
            seed = Long.parseLong(args[argi++]);
        }
        // Tri-state so that the command line can override the file in both
        // directions. Kestrel's own default is offline; vanilla's file default
        // is online-mode=true, and the file wins when no flag is given.
        Boolean onlineArg = null;
        Path propertiesArg = null;
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
        String motd = null;
        Integer maxPlayers = null;
        Integer pauseWhenEmptySecondsArg = null;
        for (; argi < args.length; argi++) {
            String a = args[argi];
            if (a.equals("--online")) {
                onlineArg = Boolean.TRUE;
            } else if (a.equals("--offline")) {
                onlineArg = Boolean.FALSE;
            } else if (a.startsWith("--properties=")) {
                propertiesArg = Path.of(a.substring("--properties=".length()));
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
            } else if (a.startsWith("--motd=")) {
                motd = a.substring("--motd=".length());
            } else if (a.startsWith("--max-players=")) {
                maxPlayers = Integer.parseInt(a.substring("--max-players=".length()));
            } else if (a.startsWith("--pause-when-empty-seconds=")) {
                pauseWhenEmptySecondsArg = Integer.parseInt(a.substring("--pause-when-empty-seconds=".length()));
            } else {
                System.out.println(usage);
                System.exit(2);
            }
        }
        // server.properties lives at the server root, which for Kestrel is the
        // world's parent directory (vanilla puts the world in a level-name
        // subdirectory of the root). It is created with vanilla's own defaults,
        // written by vanilla's DedicatedServerSettings, so an owner finds the
        // same file a vanilla dedicated server would have produced.
        Path propsTarget = propertiesArg != null
                ? propertiesArg
                : (world.getParent() != null ? world.getParent() : Path.of("."));
        boolean hadProperties = Files.exists(ServerProperties.resolvePropertiesPath(propsTarget));
        ServerProperties serverProperties = ServerProperties.loadOrCreate(propsTarget);
        int port = resolvePort(portArg, serverProperties);
        boolean online = resolveOnline(onlineArg, serverProperties);
        // Command line arguments win over the file, which wins over Kestrel's
        // built-in default. resolve* is where that rule lives, so the gate can
        // verify it without starting a server.
        motd = resolveMotd(motd, serverProperties);
        maxPlayers = resolveMaxPlayers(maxPlayers, serverProperties);
        int pauseWhenEmptySeconds = resolvePauseWhenEmptySeconds(pauseWhenEmptySecondsArg, serverProperties);
        System.out.println("[kestrel] properties " + serverProperties.path()
                + (hadProperties ? " (loaded)" : " (created with vanilla defaults)")
                + " port=" + port + " online=" + online + " max-players=" + maxPlayers
                + " view-distance=" + serverProperties.viewDistance()
                + " pause-when-empty-seconds=" + pauseWhenEmptySeconds);

        if (importMode) {
            noSave = true;
            if (spawnArg == null) {
                System.out.println("[kestrel] --import needs --spawn=x,y,z (the spawn scan hangs on imports)");
                System.exit(2);
            }
        }
        final boolean saving = !noSave;

        // Single writer per world root: lock before any region access or
        // socket bind. The channel/lock stay reachable until shutdown.
        WorldLock worldLock = WorldLock.acquire(world);

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        // G2 live wiring (preresearch/g2-livewiring-design.md): the driver
        // loads for EVERY boot (fresh, loaded, or imported), and the
        // LiveGenerator attaches whenever the store may need new chunks.
        // Loaded worlds MUST generate beyond their persisted square too:
        // without a generator every unlived chunk sends all-air and the
        // client sees a hard edge it can never walk past (2026-09-14).
        // Persisted cells are never regenerated (ensureChunk skips chunks
        // the store marks generated, and the loaded square restores as
        // generated below), so live data is never overwritten.
        dev.kestrel.worldgen.LiveGenerator liveGenerator = null;
        dev.kestrel.worldgen.HeadlessNoise.Structural liveStructural = null;
        try {
            dev.kestrel.worldgen.HeadlessNoise.Structural structural =
                    dev.kestrel.worldgen.HeadlessNoise.loadStructural(seed);
            liveStructural = structural;
            // Structure placement reads the live game time (a desert-village camel stores
            // LastPoseTick = gameTime - 53), so worldgen must see the loop's clock, not 0.
            // Forked worker drivers share this clock.
            structural.driver().clock().follow(loop::gameTime);
            liveGenerator = new dev.kestrel.worldgen.LiveGenerator(structural);
            liveGenerator.enableParallelCarvers(carverThreads);
            System.out.println("[kestrel] live generator structures=on carverThreads="
                    + liveGenerator.parallelCarverThreads());
        } catch (Exception e) {
            System.out.println("[kestrel] live generator unavailable: " + e);
        }
        // Chunk activation (vanilla's post-processing when a chunk becomes
        // ticking), see ChunkActivation for the trigger and its inexact cases.
        dev.kestrel.loop.ChunkActivation activation = new dev.kestrel.loop.ChunkActivation(loop);
        if (liveGenerator != null) {
            dev.kestrel.worldgen.LiveGenerator bootGenerator = liveGenerator;
            // Until the ChunkSender takes over below: the eager boot square
            // generates on this thread, so its light work does too.
            activation.setLightOwner(new dev.kestrel.loop.ChunkActivation.LightOwner() {
                @Override
                public net.minecraft.world.level.lighting.LevelLightEngine engine() {
                    return bootGenerator.activationLightEngine();
                }

                @Override
                public void applyWrites(java.util.List<dev.kestrel.loop.PostProcess.Write> writes) {
                    for (var light : bootGenerator.applyActivationWrites(writes).entrySet()) {
                        loop.level().putLightWireChunk(light.getKey(), light.getValue());
                    }
                }
            });
        }
        Path regionDir = WorldStore.regionDir(world);
        boolean fresh;
        try (var stream = Files.list(regionDir)) {
            fresh = stream.filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .findAny().isEmpty();
        } catch (java.io.IOException e) {
            fresh = true;
        }
        if (importMode) {
            long t0 = System.nanoTime();
            dev.kestrel.persist.ImportCensus census = new dev.kestrel.persist.ImportCensus();
            WorldStore.ChunkFilter filter = importSquare == null ? null
                    : WorldStore.square(importSquare[0], importSquare[1], importSquare[2]);
            WorldStore.importWorld(loop, world, filter, census);
            System.out.println("[kestrel] imported world " + world
                    + (importSquare == null ? " square=all" : " square=" + importSquare[0] + ","
                            + importSquare[1] + "," + importSquare[2])
                    + " ms=" + (System.nanoTime() - t0) / 1_000_000);
            System.out.println("[kestrel] " + census.line());
            System.out.println("[kestrel] " + census.storeLine());
            // Load-peak marker (design 1.4 item 5): before any GC, the decode lists are garbage now.
            System.out.println(MemProbe.sample("post-import", loop.store(), true));
            long worldHash = writeBlockHashes(loop, blockHashCsv);
            System.out.println("[kestrel] import blocksHash=" + Long.toHexString(worldHash)
                    + (blockHashCsv == null ? "" : " csv=" + blockHashCsv));
        } else if (!fresh) {
            WorldStore.load(loop, world);
            System.out.println("[kestrel] loaded world " + world
                    + " hash=" + Long.toHexString(dev.kestrel.persist.WorldSnapshot.hashLive(loop)));
        } else {
            // Fresh boot: --generate=cx,cz,half optionally fills a square
            // eagerly at boot (preview/bench convenience, and it fixes the
            // spawn point). The driver + LiveGenerator above already exist,
            // so anything beyond the eager square (or all of it, with no
            // --generate) generates on demand once the ChunkSender is wired
            // below. Every chunk goes through the same full pipeline
            // (NOISE+SURFACE+CARVERS+FEATURES) either way. Post-FULL
            // scheduled transitions such as leaf-distance ticks are not
            // folded into the shared FEATURES generation grid.
            long t0 = System.nanoTime();
            int gcx = 0, gcz = 0, ghalf = 0;
            if (generateSquare != null) {
                gcx = generateSquare[0];
                gcz = generateSquare[1];
                ghalf = generateSquare[2];
            }
            if (liveGenerator != null) {
                for (int dx = -ghalf; dx <= ghalf; dx++) {
                    for (int dz = -ghalf; dz <= ghalf; dz++) {
                        java.util.List<int[]> touched =
                                liveGenerator.ensureChunk(loop.store(), gcx + dx, gcz + dz);
                        for (var biome : liveGenerator.snapshotBiomeWire(touched).entrySet()) {
                            loop.level().putBiomeWireSection(biome.getKey(), biome.getValue());
                        }
                        activation.publishMarks(liveGenerator.snapshotPostProcessing(touched));
                        java.util.Map<Long, byte[]> lights =
                                liveGenerator.snapshotNewlyReadyLightWire(loop.store(), touched);
                        for (var light : lights.entrySet()) {
                            loop.level().putLightWireChunk(light.getKey(), light.getValue());
                        }
                        activation.lightPublished(lights.keySet());
                    }
                }
            }
            if (spawnArg == null) {
                spawnArg = new int[]{gcx * 16 + 8, 120, gcz * 16 + 8};
            }
            if (saving) {
                WorldStore.save(loop, world);
            }
            System.out.println("[kestrel] generated world square=" + gcx + "," + gcz + ","
                    + ghalf + " ms=" + (System.nanoTime() - t0) / 1_000_000
                    + " sections=" + loop.store().sectionCount());
        }
        if (!importMode) {
            System.out.println(MemProbe.sample("post-load", loop.store(), true));
        }
        if (!saving) {
            System.out.println("[kestrel] saving disabled (autosave, save-all, shutdown save)");
        }

        int plains = 1; // datapack biome ids need RegistryAccess; plains conventionally first
        try {
            var access = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                    net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            var biomes = access.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
            plains = biomes.getId(biomes.getOrThrow(
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.BIOME,
                            net.minecraft.resources.Identifier.parse("minecraft:plains"))).value());
        } catch (Exception e) {
            System.out.println("[kestrel] biome lookup fell back to 1: " + e);
        }
        ChunkSender sender = new ChunkSender(loop, plains);
        sender.setGenerator(liveGenerator);
        sender.setActivation(activation);
        if (genBudgetMs != null && asyncChunkGen) {
            System.out.println("[kestrel] WARNING: --gen-budget-ms has no effect with async chunk"
                    + " generation (the default); pass --sync-chunkgen to use it");
        } else if (genBudgetMs != null) {
            sender.setGenBudgetNanos(genBudgetMs * 1_000_000L);
            System.out.println("[kestrel] WARNING: --gen-budget-ms=" + genBudgetMs
                    + " overrides the default 40ms on-demand generation budget"
                    + " (dev/test only, larger per-tick stalls, do not use with other players online)");
        }
        if (asyncChunkGen && liveGenerator != null) {
            sender.setAsyncGenerator(new dev.kestrel.worldgen.AsyncChunkGenerator(liveGenerator, loop.store()));
            System.out.println("[kestrel] chunk generation: async (default), background worker,"
                    + " immutable publication handoff, queueCap="
                    + dev.kestrel.worldgen.AsyncChunkGenerator.DEFAULT_MAX_QUEUED
                    + "; --sync-chunkgen generates on the tick thread instead");
        } else if (liveGenerator != null) {
            System.out.println("[kestrel] chunk generation: sync (--sync-chunkgen), on the tick thread"
                    + " under the per-tick budget");
        }
        NetServer netServer = new NetServer(loop, port, Wire.DEFAULT_COMPRESSION_THRESHOLD, seed, online);
        netServer.setProperties(serverProperties);
        if (motd != null) {
            netServer.setMotd(motd);
        }
        if (maxPlayers != null) {
            netServer.setMaxPlayers(maxPlayers);
        }
        if (spawnArg != null) {
            netServer.setFixedSpawn(new net.minecraft.core.BlockPos(spawnArg[0], spawnArg[1], spawnArg[2]));
            System.out.println("[kestrel] fixed spawn " + spawnArg[0] + "," + spawnArg[1] + "," + spawnArg[2]);
        }
        // Vanilla datapack registry data for configuration: extracted
        // once from the server jar into <world>/datapack (or set
        // -Dkestrel.datapack=<dir>). Without it the client gets empty
        // registries and disconnects on "Registry must be non-empty".
        Path packDir = Path.of(System.getProperty("kestrel.datapack",
                world.resolve("datapack").toString()));
        try {
            // Static tag-only registries (block/item/fluid/entity/
            // potion/poi): vanilla's tags packet references their ids
            // in live network (protocol_id) order. Read that order from
            // the real registries once at startup; Datapack serves only
            // their tags (never values).
            java.util.Map<String, java.util.List<String>> staticOrders =
                    new java.util.LinkedHashMap<>();
            try {
                var access = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                        BuiltInRegistries.REGISTRY);
                Object[][] statics = {
                        {"minecraft:block",
                                net.minecraft.core.registries.Registries.BLOCK},
                        {"minecraft:item",
                                net.minecraft.core.registries.Registries.ITEM},
                        {"minecraft:fluid",
                                net.minecraft.core.registries.Registries.FLUID},
                        {"minecraft:entity_type",
                                net.minecraft.core.registries.Registries.ENTITY_TYPE},
                        {"minecraft:potion",
                                net.minecraft.core.registries.Registries.POTION},
                        {"minecraft:point_of_interest_type",
                                net.minecraft.core.registries.Registries.POINT_OF_INTEREST_TYPE},
                        {"minecraft:game_event",
                                net.minecraft.core.registries.Registries.GAME_EVENT},
                };
                for (Object[] s : statics) {
                    // registry IDs: iterate the live registry in raw-id
                    // order (get(id) walks the id->holder map, same ids
                    // the tags packet references on the wire).
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<?>>
                            rkey = (net.minecraft.resources.ResourceKey) s[1];
                    var reg = access.lookupOrThrow(rkey);
                    int size = reg.keySet().size();
                    java.util.List<String> ids = new java.util.ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        var holder = reg.get(i);
                        if (holder.isEmpty()) {
                            throw new IllegalStateException(
                                    "unresolvable holder " + i + " in " + s[0]);
                        }
                        ids.add(holder.get().key().identifier().toString());
                    }
                    staticOrders.put((String) s[0], ids);
                }
            } catch (Exception e) {
                System.out.println("[kestrel] static order fell back to empty: " + e);
            }
            var packs = Datapack.load(packDir, SyncedRegistries.KEYS, staticOrders);
            long total = 0;
            for (var re : packs) {
                total += re.entries().size();
            }
            netServer.setDatapack(packs);
            // Warm the memoized tag graph now so no join (not even the
            // first) pays the tag-file walk on its config phase.
            long tagsStart = System.nanoTime();
            int tagRegs = Datapack.tags(packs).size();
            System.out.println("[kestrel] datapack " + packDir + " (" + total + " entries, "
                    + tagRegs + " tag registries cached in "
                    + (System.nanoTime() - tagsStart) / 1_000_000 + "ms)");
        } catch (Exception e) {
            System.out.println("[kestrel] WARNING: no datapack at " + packDir
                    + " (" + e.getMessage() + "): clients will fail registry load");
        }
        // G4: clock, weather, and player records restore before the
        // listener opens, so no join burst can see the default clock.
        netServer.loadState(world);
        System.out.println("[kestrel] state dayTime=" + netServer.time.dayTime()
                + " raining=" + netServer.time.raining());
        // G30: random ticks, fed by the players' simulation tickets.
        LiveRandomTicks.install(loop, netServer, activation, liveStructural, seed,
                serverProperties.simulationDistance());
        netServer.start();
        if (memProbeSeconds > 0) {
            MemProbe.startPeriodic(loop.store(), memProbeSeconds);
        }
        System.out.println("[kestrel] listening on port " + netServer.boundPort()
                + " (connect with a 26.2 client, " + (online ? "online" : "offline") + " mode)");

        AtomicBoolean stop = new AtomicBoolean();
        // Counted down by the shutdown hook so the inter-tick wait returns
        // at once. Not Thread.interrupt: an interrupt landing mid-tick
        // would close any interruptible channel the tick is writing to.
        CountDownLatch wake = new CountDownLatch(1);
        Thread tickThread = Thread.currentThread();
        long shutdownTimeoutMs = Long.getLong("kestrel.shutdownTimeoutMs", 60_000L);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop.set(true);
            wake.countDown();
            // Stop ingress; queued events stay for the tick thread, and
            // player records remain in the map for the final capture.
            netServer.close();
            try {
                tickThread.join(shutdownTimeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (tickThread.isAlive()) {
                // Never save from here: the tick thread may still be
                // mid-tick or mid-save. Losing the save beats corrupting it.
                System.out.println("[kestrel] WARNING: tick thread did not stop within "
                        + shutdownTimeoutMs + "ms, exiting without shutdown save");
            }
        }, "kestrel-shutdown"));

        // Console "save-all": the tick loop honors the request at the end
        // of its current tick, so the save runs on the tick thread.
        AtomicBoolean saveRequested = new AtomicBoolean();
        Thread console = new Thread(() -> {
            try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String command = line.trim();
                    if (command.equals("save-all")) {
                        saveRequested.set(true);
                    } else if (command.equals("stop")) {
                        stop.set(true);
                        wake.countDown();
                    }
                }
            } catch (java.io.IOException ignored) {
            }
        }, "kestrel-console");
        console.setDaemon(true);
        console.start();

        java.io.BufferedWriter benchmarkTickCsv = null;
        String benchmarkTickCsvProperty = System.getProperty("kestrel.benchmark.tickCsv");
        if (benchmarkTickCsvProperty != null && !benchmarkTickCsvProperty.isBlank()) {
            Path benchmarkTickCsvPath = Path.of(benchmarkTickCsvProperty).toAbsolutePath();
            Path parent = benchmarkTickCsvPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            benchmarkTickCsv = Files.newBufferedWriter(
                    benchmarkTickCsvPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            benchmarkTickCsv.write("tick,workNanos,players,asyncQueueDepth");
            benchmarkTickCsv.newLine();
            benchmarkTickCsv.flush();
        }

        ServerLoop serverLoop = new ServerLoop(loop, netServer, sender, world, saving, pauseWhenEmptySeconds);
        long next = System.currentTimeMillis();
        try {
            while (!stop.get()) {
                long tickWorkStart = System.nanoTime();
                boolean manual = saveRequested.getAndSet(false);
                serverLoop.tick(manual);
                long tickWorkNanos = System.nanoTime() - tickWorkStart;
                if (benchmarkTickCsv != null) {
                    benchmarkTickCsv.write(serverLoop.tickCount() + "," + tickWorkNanos + ","
                            + netServer.players.size() + "," + sender.asyncQueueDepth());
                    benchmarkTickCsv.newLine();
                    if (serverLoop.tickCount() % 20 == 0) {
                        benchmarkTickCsv.flush();
                    }
                }
                next += 50;
                long sleep = next - System.currentTimeMillis();
                if (sleep > 0) {
                    wake.await(sleep, TimeUnit.MILLISECONDS);
                } else {
                    next = System.currentTimeMillis();
                }
            }
        } finally {
            if (benchmarkTickCsv != null) {
                try {
                    benchmarkTickCsv.flush();
                    benchmarkTickCsv.close();
                } catch (java.io.IOException e) {
                    System.out.println("[kestrel] WARNING: benchmark tick CSV close failed: " + e);
                }
            }
            // Tick thread is quiescent here: no tick or autosave is in
            // flight, so the final snapshot sees a consistent world.
            netServer.close();
            if (saving) {
                try {
                    long h = WorldStore.save(loop, world);
                    netServer.saveState(world);
                    System.out.println("[kestrel] saved on shutdown tick " + serverLoop.tickCount()
                            + " hash=" + Long.toHexString(h));
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                }
            }
            sender.closeAsyncGenerator();
            if (liveGenerator != null) {
                liveGenerator.close();
            }
            worldLock.close();
        }
    }

    /**
     * One live server iteration, shared verbatim with network gates.
     *
     * <p>Vanilla runs levels first at MinecraftServer:1119-1136, then the
     * connection at :1138-1139. Connection.tick flushes queued inbound work
     * at :350-353 before ServerGamePacketListenerImpl.tick sends the block
     * acknowledgement at :296-300. PlayerList.tick follows at :1140-1141.</p>
     */
    public static void runIteration(NetServer netServer, TickLoop loop, ChunkSender sender) {
        runIteration(netServer, loop, sender, true);
    }

    /** Run one iteration, optionally omitting only the level phase for vanilla's empty pause. */
    static void runIteration(NetServer netServer, TickLoop loop, ChunkSender sender, boolean tickLevel) {
        netServer.setChunkSender(sender);
        netServer.wireWorldTickOrdering(sender);
        if (tickLevel) {
            loop.tick();
        }
        if (tickLevel) {
            netServer.pollEvents();
        } else {
            netServer.tickConnection();
        }
        if (tickLevel) {
            netServer.tick(sender);
        } else {
            netServer.tickNetworkTail(sender);
        }
        sender.pruneDeparted(netServer.players.keySet());
    }

    /** Exclusive OS file lock on {@code <worldDir>/.kestrel.lock}, held for the process lifetime. */
    private static final class WorldLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private WorldLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        /** Lock {@code world} or exit(1) with an explicit error if another process holds it. */
        static WorldLock acquire(Path world) {
            Path lockFile = world.resolve(".kestrel.lock");
            FileChannel channel = null;
            FileLock lock = null;
            try {
                Files.createDirectories(world);
                channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    lock = null;
                }
                if (lock == null) {
                    channel.close();
                    System.out.println("[kestrel] ERROR: world " + world.toAbsolutePath()
                            + " is locked by another running server (" + lockFile
                            + "); refusing to boot");
                    System.exit(1);
                }
                // Diagnostic only: who holds it. The OS lock is the guard.
                channel.truncate(0);
                channel.write(java.nio.ByteBuffer.wrap(
                        (ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.US_ASCII)));
                channel.force(false);
                return new WorldLock(channel, lock);
            } catch (java.io.IOException e) {
                System.out.println("[kestrel] ERROR: cannot lock world " + world.toAbsolutePath()
                        + " (" + lockFile + "): " + e + "; refusing to boot");
                System.exit(1);
                throw new AssertionError(e);
            }
        }

        @Override
        public void close() {
            try {
                lock.release();
                channel.close();
            } catch (java.io.IOException ignored) {
            }
        }
    }

    /**
     * Logs "[kestrel] build commit=&lt;short-hash&gt;[-dirty] startedAt=&lt;instant&gt;
     * pid=&lt;pid&gt;" so a future "was the fix actually running" question is one
     * grep away instead of javap bytecode inspection against the live
     * classpath directory (see the 2026-09-17 stale-JVM incident this was
     * added for). Deliberately never throws: a diagnostic banner must never
     * be the reason boot fails, so any failure just logs "unknown".
     *
     * <p>The commit comes from git when the classpath sits inside a checkout.
     * A packaged release zip has no {@code .git} directory, so it falls back to
     * a {@code BUILD-COMMIT.txt} stamp beside the classpath root and reports the
     * commit that way instead of "unknown".
     */
    /**
     * Effective port: the command line wins over {@code server.properties}.
     *
     * <p>Pure and package private on purpose, so {@code ServerPropertiesCheck}
     * can verify the documented precedence rule without starting a server.
     */
    static int resolvePort(Integer fromArgs, ServerProperties fromFile) {
        return fromArgs != null ? fromArgs : fromFile.serverPort();
    }

    /**
     * Effective auth mode: the command line wins over {@code server.properties}.
     * Kestrel's own default is offline, vanilla's file default is online.
     */
    static boolean resolveOnline(Boolean fromArgs, ServerProperties fromFile) {
        return fromArgs != null ? fromArgs : fromFile.onlineMode();
    }

    /** Effective MOTD: the command line wins over {@code server.properties}. */
    static String resolveMotd(String fromArgs, ServerProperties fromFile) {
        return fromArgs != null ? fromArgs : fromFile.motd();
    }

    /** Effective max-players: the command line wins over {@code server.properties}. */
    static int resolveMaxPlayers(Integer fromArgs, ServerProperties fromFile) {
        return fromArgs != null ? fromArgs : fromFile.maxPlayers();
    }

    /** Effective pause-when-empty-seconds: the command line wins over {@code server.properties}. */
    public static int resolvePauseWhenEmptySeconds(Integer fromArgs, ServerProperties fromFile) {
        return fromArgs != null ? fromArgs : fromFile.pauseWhenEmptySeconds();
    }

    /**
     * Main server tick loop driving the world tick, networking, autosave, and
     * the pause-when-empty slice (S32).
     *
     * <p>Vanilla reference: {@code MinecraftServer.tickServer} (:972-1000).
     */
    public static final class ServerLoop {
        private final TickLoop loop;
        private final NetServer netServer;
        private final ChunkSender sender;
        private final Path world;
        private final boolean saving;
        private final int pauseWhenEmptySeconds;

        private int emptyTicks;
        private long tick;

        public ServerLoop(TickLoop loop, NetServer netServer, ChunkSender sender,
                          Path world, boolean saving, int pauseWhenEmptySeconds) {
            this.loop = loop;
            this.netServer = netServer;
            this.sender = sender;
            this.world = world;
            this.saving = saving;
            this.pauseWhenEmptySeconds = pauseWhenEmptySeconds;
        }

        public ServerLoop(TickLoop loop, NetServer netServer, ChunkSender sender,
                          int pauseWhenEmptySeconds) {
            this(loop, netServer, sender, null, false, pauseWhenEmptySeconds);
        }

        public int pauseWhenEmptySeconds() {
            return pauseWhenEmptySeconds;
        }

        public int emptyTicks() {
            return emptyTicks;
        }

        public int emptyTickThreshold() {
            return pauseWhenEmptySeconds * 20;
        }

        public boolean isPaused() {
            int threshold = emptyTickThreshold();
            return threshold > 0 && emptyTicks >= threshold;
        }

        public long tickCount() {
            return tick;
        }

        public TickLoop loop() {
            return loop;
        }

        public NetServer netServer() {
            return netServer;
        }

        public ChunkSender sender() {
            return sender;
        }

        public void tickConnection() {
            if (sender != null) {
                runIteration(netServer, loop, sender, false);
            } else {
                netServer.tickConnection();
            }
        }

        public void autoSave() {
            if (saving && world != null) {
                try {
                    long h = WorldStore.save(loop, world);
                    netServer.saveState(world);
                    System.out.println("[kestrel] autosaved tick " + tick + " hash=" + Long.toHexString(h));
                } catch (Exception e) {
                    System.out.println("[kestrel] autosave failed: " + e);
                }
            }
        }

        public void tick() {
            tick(false);
        }

        public void tick(boolean manualSave) {
            int emptyTickThreshold = pauseWhenEmptySeconds * 20;
            if (emptyTickThreshold > 0) {
                if (netServer.playerCount() == 0) {
                    emptyTicks++;
                } else {
                    emptyTicks = 0;
                }

                if (emptyTicks >= emptyTickThreshold) {
                    if (emptyTicks == emptyTickThreshold) {
                        System.out.println("[kestrel] Server empty for " + pauseWhenEmptySeconds + " seconds, pausing");
                        autoSave();
                    } else if (manualSave) {
                        autoSave();
                        System.out.println("[kestrel] saved (save-all) tick " + tick);
                    }

                    tickConnection();
                    return;
                }
            }

            if (sender != null) {
                runIteration(netServer, loop, sender);
            } else {
                loop.tick();
                netServer.pollEvents();
                netServer.tickTime();
            }
            tick++;

            if (saving && world != null && (manualSave || (tick > 0 && tick % 6000 == 0))) {
                try {
                    long h = WorldStore.save(loop, world);
                    netServer.saveState(world);
                    System.out.println("[kestrel] " + (manualSave ? "saved (save-all)" : "autosaved")
                            + " tick " + tick + " hash=" + Long.toHexString(h));
                } catch (Exception e) {
                    System.out.println("[kestrel] save failed: " + e);
                }
            }
        }
    }

    private static void logBuildInfo() {
        String commit = "unknown";
        try {
            Path repoRoot = findRepoRoot();
            if (repoRoot != null) {
                String hash = runGit(repoRoot, "rev-parse", "--short=12", "HEAD");
                if (hash != null && !hash.isBlank()) {
                    String status = runGit(repoRoot, "status", "--porcelain");
                    commit = hash + (status != null && !status.isBlank() ? "-dirty" : "");
                }
            } else {
                // Packaged release: no .git directory to walk to, so the build
                // stamps the commit next to the classpath root instead.
                String stamp = readBuildStamp();
                if (stamp != null) {
                    commit = stamp;
                }
            }
        } catch (Exception e) {
            // Fall through with "unknown" - see javadoc above.
        }
        System.out.println("[kestrel] build commit=" + commit
                + " startedAt=" + java.time.Instant.now()
                + " pid=" + ProcessHandle.current().pid());
    }

    /**
     * Finds the git repo root by walking up from every {@code java.class.path}
     * entry looking for a {@code .git} directory. Works regardless of the
     * process's actual working directory (the live systemd unit's cwd is
     * the user's home, not the repo) as long as at least one classpath
     * entry lives inside the checkout - true for {@code core/build/classes/
     * java/main}, which every real deployment's classpath includes.
     *
     * <p>A worktree's {@code .git} is a file and a packaged release has none, so
     * both take the {@link #readBuildStamp()} path instead.
     */
    private static Path findRepoRoot() {
        return walkClasspathUp(dir -> Files.isDirectory(dir.resolve(".git")));
    }

    /**
     * Reads the commit a packaged release was built from, which the release
     * build writes as {@link #BUILD_STAMP_FILE} next to the classpath root,
     * because a zip has no {@code .git} directory for {@link #findRepoRoot()}
     * to find. Returns the first non-blank line, or null when there is no stamp
     * (a plain checkout, or a classpath with no stamp beside it).
     */
    private static String readBuildStamp() {
        Path root = walkClasspathUp(dir -> Files.isRegularFile(dir.resolve(BUILD_STAMP_FILE)));
        if (root == null) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(root.resolve(BUILD_STAMP_FILE))) {
                if (!line.isBlank()) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {
            // Diagnostic only: an unreadable stamp is the same as no stamp.
        }
        return null;
    }

    /**
     * Walks up from every {@code java.class.path} entry and returns the first
     * directory matching {@code matches}. Shared by {@link #findRepoRoot()} and
     * {@link #readBuildStamp()} so both look in the same places.
     */
    private static Path walkClasspathUp(java.util.function.Predicate<Path> matches) {
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path p = Path.of(entry).toAbsolutePath().normalize();
                for (Path dir = Files.isDirectory(p) ? p : p.getParent();
                        dir != null; dir = dir.getParent()) {
                    if (matches.test(dir)) {
                        return dir;
                    }
                }
            } catch (Exception ignored) {
                // Not a usable path (e.g. a bare jar name); try the next entry.
            }
        }
        return null;
    }

    /**
     * Runs {@code git -C repoRoot args...}, bounded to 2 seconds, returning
     * trimmed stdout on exit code 0 or null on any failure/timeout (never
     * throws, matching {@link #logBuildInfo}'s "diagnostic only" contract).
     */
    private static String runGit(Path repoRoot, String... args) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("git");
            cmd.add("-C");
            cmd.add(repoRoot.toString());
            cmd.addAll(java.util.List.of(args));
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (var in = proc.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            boolean finished = proc.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null;
            }
            return proc.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int[] ints(String csv, int n, String usage) {
        String[] parts = csv.split(",");
        if (parts.length != n) {
            System.out.println(usage);
            System.exit(2);
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    /**
     * Blocks-only hash of every imported chunk column (sorted x, then z),
     * optionally one CSV row each; returns an FNV fold over the rows as a
     * one-number world summary for the run log.
     */
    private static long writeBlockHashes(TickLoop loop, Path csv) throws java.io.IOException {
        java.util.TreeSet<Long> chunks = new java.util.TreeSet<>();
        for (long key : loop.store().sectionKeys()) {
            chunks.add(((long) net.minecraft.core.SectionPos.x(key) << 32)
                    | (net.minecraft.core.SectionPos.z(key) & 0xFFFFFFFFL));
        }
        long fold = 0xcbf29ce484222325L;
        StringBuilder sb = csv == null ? null : new StringBuilder("chunk_x,chunk_z,blocks_hash\n");
        for (long c : chunks) {
            int cx = (int) (c >> 32);
            int cz = (int) c;
            long h = dev.kestrel.persist.BlockHash.chunk(loop.store(), cx, cz);
            fold = (fold ^ cx) * 0x100000001b3L;
            fold = (fold ^ cz) * 0x100000001b3L;
            fold = (fold ^ h) * 0x100000001b3L;
            if (sb != null) {
                sb.append(cx).append(',').append(cz).append(',')
                        .append(String.format(java.util.Locale.ROOT, "%016x", h)).append('\n');
            }
        }
        if (sb != null) {
            Files.writeString(csv, sb.toString());
        }
        return fold;
    }
}
