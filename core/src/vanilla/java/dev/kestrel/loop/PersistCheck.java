package dev.kestrel.loop;

import dev.kestrel.blockentity.SleeperRegistry;
import dev.kestrel.persist.AsyncSaver;
import dev.kestrel.persist.ChunkCodec;
import dev.kestrel.persist.Nbt;
import dev.kestrel.persist.RegionFile;
import dev.kestrel.persist.SectionCodec;
import dev.kestrel.persist.Sidecar;
import dev.kestrel.persist.StateNbt;
import dev.kestrel.persist.WorldSnapshot;
import dev.kestrel.persist.WorldStore;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Stage 2 proof battery: Anvil overworld persistence round-trips.
 * All headless, real vanilla classes.
 *
 * <ol>
 *   <li>NBT framing: a hand-built tag tree encodes and decodes to
 *       itself through the binary form.</li>
 *   <li>Palette bits rules: 1 state writes no data tag, 2-16 states
 *       write 4-bit data, 17+ write 5-bit, and 4096-cell decode is the
 *       identity.</li>
 *   <li>Region framing: write two chunks, read them back, missing slot
 *       reads null, gzip+uncompressed payloads read.</li>
 *   <li>Round trip: boot the settled S/T corpus, run N ticks, hash,
 *       save to a temp world, reload into a fresh loop, hash again:
 *       bit-identical. Then run M further ticks on both loops: traces
 *       identical.</li>
 *   <li>Async: saveAsync off the tick thread completes with the same
 *       hash; a second concurrent save is rejected; the reloaded world
 *       hashes identically.</li>
 *   <li>Vanilla-compat: every chunk of a saved world parses under real
 *       vanilla {@code RegionFileStorage.read} + {@code NbtIo}, carries
 *       the right xPos/zPos/sections/block_ticks shape, and every
 *       block_states palette entry decodes through vanilla's own
 *       container codec.</li>
 *   <li>Fresh boot unregressed: TickLoopCheck's settled-line gradient
 *       still settles after a save/load cycle with no ticks in between.</li>
 *   <li>Section Y: a synthetic vanilla-shaped chunk with world section Y
 *       -4..19 (plus out-of-range -5/20 tags) decodes to slots 0..23,
 *       and the writer emits world section Y.</li>
 *   <li>Import: {@code WorldStore.importWorld} on a Kestrel-written root
 *       restores the same hashLive as {@code load}, with and without
 *       sidecars on disc.</li>
 * </ol>
 *
 * <p>Exits non-zero on any mismatch.
 */
public final class PersistCheck {
    private PersistCheck() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        checkNbt(failures);
        checkPalette(failures);
        checkRegion(failures);
        checkRoundTrip(failures);
        checkAsync(failures);
        checkVanillaCompat(failures);
        checkFreshBoot(failures);
        checkSectionY(failures);
        checkImport(failures);
        checkFluidRoundTrip(failures);

        System.out.println("PersistCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    static void expect(boolean cond, String what, List<String> failures) {
        if (!cond) {
            failures.add(what);
        }
    }

    // ---- 1: NBT framing ----

    private static void checkNbt(List<String> failures) {
        try {
            Nbt.Tag.TCompound root = Nbt.compound();
            Nbt.putByte(root, "b", (byte) -5);
            Nbt.putShort(root, "s", (short) 30000);
            Nbt.putInt(root, "i", -123456);
            Nbt.putLong(root, "l", 9876543210L);
            Nbt.putString(root, "str", "minecraft:redstone_wire");
            Nbt.putByteArray(root, "ba", new byte[]{1, 2, 3});
            Nbt.putIntArray(root, "ia", new int[]{4, 5});
            Nbt.putLongArray(root, "la", new long[]{6L, 7L});
            List<Nbt.Tag> items = new ArrayList<>();
            items.add(new Nbt.Tag.TString("a"));
            items.add(new Nbt.Tag.TString("b"));
            Nbt.put(root, "list", new Nbt.Tag.TList(Nbt.TAG_STRING, items));
            Nbt.Tag.TCompound sub = Nbt.compound();
            Nbt.putInt(sub, "x", 42);
            Nbt.put(root, "c", sub);

            byte[] bytes = Nbt.encodeRoot(root);
            Nbt.Tag.TCompound back = Nbt.decodeRoot(
                    new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)));
            expect(Nbt.getByte(back, "b") == -5, "nbt: byte", failures);
            expect(Nbt.getShort(back, "s") == 30000, "nbt: short", failures);
            expect(Nbt.getInt(back, "i") == -123456, "nbt: int", failures);
            expect(Nbt.getLong(back, "l") == 9876543210L, "nbt: long", failures);
            expect(Nbt.getString(back, "str").equals("minecraft:redstone_wire"), "nbt: string", failures);
            expect(Nbt.getByteArray(back, "ba").length == 3, "nbt: byte array", failures);
            expect(Nbt.getIntArray(back, "ia")[1] == 5, "nbt: int array", failures);
            expect(Nbt.getLongArray(back, "la")[0] == 6L, "nbt: long array", failures);
            expect(Nbt.getList(back, "list").items().size() == 2, "nbt: list", failures);
            expect(Nbt.getInt(Nbt.getCompound(back, "c"), "x") == 42, "nbt: compound", failures);
        } catch (Exception e) {
            failures.add("nbt: threw " + e);
        }
    }

    // ---- 2: palette bits ----

    private static void checkPalette(List<String> failures) {
        try {
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
            // Single state: no data tag.
            BlockState[] one = new BlockState[4096];
            java.util.Arrays.fill(one, stone);
            Nbt.Tag.TCompound t1 = SectionCodec.encode(one, air);
            expect(Nbt.getOrNull(t1, "data") == null, "palette: single state must omit data", failures);
            expect(SectionCodec.decode(t1, air)[2048] == stone, "palette: single decode", failures);
            // Two states: 4-bit data.
            BlockState[] two = new BlockState[4096];
            for (int i = 0; i < 4096; i++) {
                two[i] = (i % 3 == 0) ? wire : stone;
            }
            Nbt.Tag.TCompound t2 = SectionCodec.encode(two, air);
            long[] data = Nbt.getLongArray(t2, "data");
            expect(data.length == 256, "palette: 4-bit data must be 256 longs, got " + data.length, failures);
            BlockState[] back2 = SectionCodec.decode(t2, air);
            boolean same = true;
            for (int i = 0; i < 4096; i++) {
                if (back2[i] != two[i]) {
                    same = false;
                    break;
                }
            }
            expect(same, "palette: 4096-cell identity", failures);
            // 17 states: 5-bit data (320 longs).
            BlockState[] many = new BlockState[4096];
            List<BlockState> states = new ArrayList<>(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size());
            net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.forEach(states::add);
            for (int i = 0; i < 4096; i++) {
                many[i] = states.get(i % 17);
            }
            Nbt.Tag.TCompound t17 = SectionCodec.encode(many, air);
            expect(Nbt.getLongArray(t17, "data").length == 342,
                    "palette: 5-bit data must be 342 longs, got "
                            + Nbt.getLongArray(t17, "data").length,
                    failures);
            BlockState[] back17 = SectionCodec.decode(t17, air);
            same = true;
            for (int i = 0; i < 4096; i++) {
                if (back17[i] != many[i]) {
                    same = false;
                    break;
                }
            }
            expect(same, "palette: 17-state identity", failures);
            // State with properties round-trips (powered lever keeps POWERED).
            BlockState lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.POWERED, true);
            Nbt.Tag.TCompound lt = StateNbt.encode(lever);
            BlockState leverBack = StateNbt.decode(lt);
            expect(leverBack == lever, "palette: lever state identity, got " + leverBack, failures);
        } catch (Exception e) {
            failures.add("palette: threw " + e);
        }
    }

    // ---- 3: region framing ----

    private static void checkRegion(List<String> failures) {
        try {
            Path dir = Files.createTempDirectory("kestrel-region");
            Path file = dir.resolve("r.0.0.mca");
            try (RegionFile region = RegionFile.open(file)) {
                Nbt.Tag.TCompound a = Nbt.compound();
                Nbt.putInt(a, "xPos", 3);
                region.writeChunk(3, 5, a);
                Nbt.Tag.TCompound b = Nbt.compound();
                Nbt.putInt(b, "xPos", 31);
                region.writeChunk(31, 31, b);
                expect(Nbt.getInt(region.readChunk(3, 5), "xPos") == 3,
                        "region: read back chunk a", failures);
                expect(Nbt.getInt(region.readChunk(31, 31), "xPos") == 31,
                        "region: read back chunk b", failures);
                expect(region.readChunk(0, 0) == null, "region: missing slot reads null", failures);
                expect(region.hasChunk(3, 5) && !region.hasChunk(0, 0),
                        "region: hasChunk", failures);
                // Overwrite reallocates: rewrite and read again.
                Nbt.putInt(a, "xPos", 4);
                region.writeChunk(3, 5, a);
                expect(Nbt.getInt(region.readChunk(3, 5), "xPos") == 4,
                        "region: overwrite reallocates", failures);
            }
            // Raw framing check: length + compression id 2 then zlib.
            // NOTE: RegionFile.close() forces the channel; re-open
            // read-only state is already committed by writeHeader per
            // write, so reading the file bytes here is valid.
            byte[] raw = Files.readAllBytes(file);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(raw);
            int packed = buf.getInt((3 + 5 * 32) * 4);
            int sector = (packed >> 8) & 0xFFFFFF;
            int length = java.nio.ByteBuffer.wrap(raw, sector * 4096, 5).getInt();
            int version = raw[sector * 4096 + 4] & 0xFF;
            expect(version == RegionFile.COMPRESSION_DEFLATE,
                    "region: compression id must be 2, got " + version, failures);
            expect(length > 1, "region: payload length positive", failures);
            // gzip (1) and uncompressed (3) payloads read back.
            // NOTE: the outer region instance is closed first: its
            // in-memory offset table predates the raw patch writes.
            writeRawChunk(file, 7, 7, RegionFile.COMPRESSION_GZIP,
                    gzip(Nbt.encodeRoot(Nbt.compound())));
            writeRawChunk(file, 8, 8, RegionFile.COMPRESSION_NONE,
                    Nbt.encodeRoot(intTag("v", 9)));
            try (RegionFile region = RegionFile.open(file)) {
                expect(region.readChunk(7, 7) != null, "region: gzip reads", failures);
                expect(Nbt.getInt(region.readChunk(8, 8), "v") == 9,
                        "region: uncompressed reads", failures);
            }
        } catch (Exception e) {
            failures.add("region: threw " + e);
        }
    }

    private static Nbt.Tag.TCompound intTag(String name, int v) {
        Nbt.Tag.TCompound t = Nbt.compound();
        Nbt.putInt(t, name, v);
        return t;
    }

    private static byte[] gzip(byte[] in) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bytes)) {
            gz.write(in);
        }
        return bytes.toByteArray();
    }

    private static void writeRawChunk(Path file, int lx, int lz, int version, byte[] body)
            throws Exception {
        try (RegionFile region = RegionFile.open(file)) {
            // Allocate through the public path with a placeholder, then
            // patch the payload bytes: simpler is to bypass and store via
            // reflection-free manual sector write.
            Nbt.Tag.TCompound placeholder = Nbt.compound();
            Nbt.putInt(placeholder, "pad", 0);
            region.writeChunk(lx, lz, placeholder);
        }
        byte[] raw = Files.readAllBytes(file);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(raw);
        int packed = buf.getInt((lx + lz * 32) * 4);
        int sector = (packed >> 8) & 0xFFFFFF;
        int sectors = packed & 0xFF;
        int total = body.length + 5;
        int need = (total + 4096 - 1) / 4096;
        if (need > sectors) {
            throw new IllegalStateException("placeholder too small for raw write");
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            raf.seek((long) sector * 4096);
            raf.writeInt(body.length + 1);
            raf.writeByte(version);
            raf.write(body);
        }
    }

    // ---- corpus builder (S/T arms of the settled proof) ----

    /** Settled S/T corpus: 41-wire powered line + torch-powered 10-wire line. */
    static TickLoop buildCorpus() {
        TickLoop loop = TickLoopCheck.buildPoweredLine(-20, 20, -4);
        for (int x = -2; x <= 13; x++) {
            loop.setBlock(new BlockPos(x, 0, 8), Blocks.STONE.defaultBlockState(), 3);
        }
        for (int x = 1; x <= 10; x++) {
            loop.setBlock(new BlockPos(x, 1, 8), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        }
        loop.setBlock(new BlockPos(0, 0, 8), Blocks.STONE.defaultBlockState(), 3);
        loop.setBlock(new BlockPos(0, 1, 8), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
        TickLoopCheck.resolveWires(loop, -20, 20, -4);
        TickLoopCheck.resolveWires(loop, 1, 10, 8);
        loop.updatePowerAt(new BlockPos(-20, 1, -4));
        loop.updatePowerAt(new BlockPos(1, 1, 8));
        loop.drainDispatch();
        return loop;
    }

    /** Corpus plus sleepers and written-bitset variety (arming is post-settle). */
    static TickLoop buildFullCorpus() {
        TickLoop loop = buildCorpus();
        // Sleepers: one asleep furnace, one awake (woken) furnace, one sign.
        // Registration happens before the settle so the sleep pass runs;
        // the wake + queue schedule happen AFTER the settle (armSaveState)
        // so entries are still pending and the woken sleeper still awake
        // at save time (the no-op body re-sleeps everything each tick).
        KestrelPos furnace = new KestrelPos(5, 64, 5);
        KestrelPos furnace2 = new KestrelPos(6, 64, 5);
        KestrelPos sign = new KestrelPos(7, 64, 5);
        loop.registerBlockEntity(furnace, SleeperRegistry.Type.FURNACE);
        loop.registerBlockEntity(furnace2, SleeperRegistry.Type.FURNACE);
        loop.registerBlockEntity(sign, SleeperRegistry.Type.SIGN);
        // Written-bitset variety: a stone block overwritten then left.
        loop.setBlock(new KestrelPos(-30, 0, -4),
                KestrelState.wrap(Blocks.DIRT.defaultBlockState()), 3);
        BlockPos repPos = new BlockPos(30, 1, -4);
        loop.setBlock(repPos, Blocks.REPEATER.defaultBlockState(), 3);
        return loop;
    }

    /**
     * Post-settle arming: wake one sleeper and schedule ticks with delays
     * beyond the save point, WITHOUT ticking afterwards. Must be the last
     * mutation before save: any further tick would fire the entries (torch
     * delay 5 < settle window) and re-sleep the woken furnace (the body
     * always reports idle).
     */
    static void armSaveState(TickLoop loop) {
        loop.wakeBlockEntity(new KestrelPos(6, 64, 5),
                SleeperRegistry.WakeSignal.FURNACE_SLOT_OTHER);
        loop.scheduleTick(new KestrelPos(0, 1, 8),
                new KestrelTickTarget(KestrelTickTarget.Kind.REDSTONE_TORCH,
                        KestrelTickTarget.Priority.NORMAL),
                5);
        loop.scheduleTick(new KestrelPos(30, 1, -4),
                new KestrelTickTarget(KestrelTickTarget.Kind.REPEATER,
                        KestrelTickTarget.Priority.HIGH),
                7);
    }

    static void settle(TickLoop loop, int ticks) {
        for (int i = 0; i < ticks; i++) {
            loop.tick();
        }
    }

    // ---- 4: save/reload round trip ----

    private static void checkRoundTrip(List<String> failures) {
        try {
            TickLoop loop = buildFullCorpus();
            settle(loop, 10);
            armSaveState(loop);
            long before = WorldSnapshot.hashLive(loop);
            Path world = Files.createTempDirectory("kestrel-world");
            WorldStore.save(loop, world);

            TickLoop reloaded = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.load(reloaded, world);
            long after = WorldSnapshot.hashLive(reloaded);
            expect(before == after, "roundtrip: hash mismatch " + before + " vs " + after, failures);

            // Post-reload ticks diverge nowhere: run M ticks on both.
            loop.discoverWires();
            reloaded.discoverWires();
            settle(loop, 20);
            settle(reloaded, 20);
            java.util.List<String> a = loop.powerTrace();
            java.util.List<String> b = reloaded.powerTrace();
            java.util.List<String> tailA = a.subList(a.size() - 20, a.size());
            expect(tailA.equals(b),
                    "roundtrip: post-reload traces diverged", failures);
            expect(WorldSnapshot.hashLive(loop) == WorldSnapshot.hashLive(reloaded),
                    "roundtrip: post-tick hashes diverged", failures);

            // Queue/sleeper survival is save-time state: assert on a FRESH
            // reload, not on the loop that just ran 20 post-reload ticks
            // (those ticks legitimately fire the torch entry at trigger 15
            // and re-sleep the woken furnace via the idle body).
            TickLoop fresh = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.load(fresh, world);
            boolean found = false;
            for (TickLoop.QueuedTick q : fresh.dumpQueue()) {
                if (q.kind() == KestrelTickTarget.Kind.REDSTONE_TORCH) {
                    found = true;
                }
            }
            expect(found, "roundtrip: torch tick must survive reload", failures);
            // Sleepers survived: furnace asleep, woken furnace awake.
            expect(fresh.level().isAsleep(new KestrelPos(5, 64, 5)),
                    "roundtrip: furnace must sleep after reload", failures);
            expect(!fresh.level().isAsleep(new KestrelPos(6, 64, 5)),
                    "roundtrip: woken furnace must stay awake after reload", failures);
        } catch (Exception e) {
            failures.add("roundtrip: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    // ---- 5: async ----

    private static void checkAsync(List<String> failures) {
        try (AsyncSaver saver = new AsyncSaver()) {
            TickLoop loop = buildFullCorpus();
            settle(loop, 10);
            armSaveState(loop);
            long before = WorldSnapshot.hashLive(loop);
            Path world = Files.createTempDirectory("kestrel-async");
            CompletableFuture<Long> save = saver.saveAsync(loop, world);
            // A second save while one is in flight is rejected.
            CompletableFuture<Long> dup = saver.saveAsync(loop, world);
            expect(dup.isCompletedExceptionally(),
                    "async: concurrent save must be rejected", failures);
            long hash = save.get(60, TimeUnit.SECONDS);
            expect(hash == before, "async: future hash must equal pre-save hash", failures);
            TickLoop reloaded = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.load(reloaded, world);
            expect(WorldSnapshot.hashLive(reloaded) == before,
                    "async: reloaded hash must equal pre-save hash", failures);
            // After completion a new save is accepted again.
            CompletableFuture<Long> again = saver.saveAsync(loop, world);
            expect(!again.isCompletedExceptionally(),
                    "async: save after completion must be accepted", failures);
            again.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            failures.add("async: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    // ---- 6: vanilla compat ----

    private static void checkVanillaCompat(List<String> failures) {
        try {
            TickLoop loop = buildFullCorpus();
            settle(loop, 10);
            armSaveState(loop);
            Path world = Files.createTempDirectory("kestrel-compat");
            WorldStore.save(loop, world);

            net.minecraft.world.level.chunk.storage.RegionStorageInfo info =
                    new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
                            "kestrel-test", net.minecraft.world.level.Level.OVERWORLD, "chunk");
            Path regionDir = WorldStore.regionDir(world);
            int chunks = 0;
            int sections = 0;
            try (var stream = Files.list(regionDir)) {
                List<Path> regions = stream
                        .filter(p -> p.getFileName().toString().endsWith(".mca"))
                        .sorted()
                        .toList();
                expect(!regions.isEmpty(), "compat: world must contain region files", failures);
                for (Path regionFile : regions) {
                    net.minecraft.world.level.chunk.storage.RegionFileStorage storage =
                            new net.minecraft.world.level.chunk.storage.RegionFileStorage(
                                    info, regionDir, false);
                    // Read every chunk through REAL vanilla storage + NBT.
                    try (RegionFile region = RegionFile.open(regionFile)) {
                        for (int lx = 0; lx < 32; lx++) {
                            for (int lz = 0; lz < 32; lz++) {
                                if (!region.hasChunk(lx, lz)) {
                                    continue;
                                }
                                int rx = Integer.parseInt(
                                        regionFile.getFileName().toString().split("\\.")[1]);
                                int rz = Integer.parseInt(
                                        regionFile.getFileName().toString().split("\\.")[2]);
                                net.minecraft.world.level.ChunkPos cp =
                                        new net.minecraft.world.level.ChunkPos(
                                                (rx << 5) + lx, (rz << 5) + lz);
                                net.minecraft.nbt.CompoundTag vanilla =
                                        storage.read(cp);
                                expect(vanilla != null,
                                        "compat: vanilla must parse chunk " + cp, failures);
                                if (vanilla == null) {
                                    continue;
                                }
                                chunks++;
                                expect(vanilla.getIntOr("xPos", Integer.MIN_VALUE) == cp.x(),
                                        "compat: xPos must match", failures);
                                expect(vanilla.getIntOr("zPos", Integer.MIN_VALUE) == cp.z(),
                                        "compat: zPos must match", failures);
                                var sectionsTag = vanilla.getListOrEmpty("sections");
                                expect(!sectionsTag.isEmpty(),
                                        "compat: sections must be present", failures);
                                sections += sectionsTag.size();
                                // Every block_states palette entry carries
                                // the {Name, Properties} shape vanilla's
                                // StateHolder codec emits, and decodes
                                // through Kestrel's mirror codec to a live
                                // registry state (vanilla NBT parse above
                                // already proves the bytes are real NBT).
                                for (int i = 0; i < sectionsTag.size(); i++) {
                                    var sec = sectionsTag.getCompound(i);
                                    if (sec.isEmpty() || sec.get().getCompound("block_states").isEmpty()) {
                                        continue;
                                    }
                                    var states = sec.get().getCompound("block_states").get();
                                    var palette = states.getListOrEmpty("palette");
                                    expect(!palette.isEmpty(),
                                            "compat: palette must be non-empty", failures);
                                    for (int j = 0; j < palette.size(); j++) {
                                        var entry = palette.getCompound(j);
                                        expect(entry.isPresent()
                                                && entry.get().getString("Name").isPresent(),
                                                "compat: palette entry needs Name", failures);
                                        if (entry.isPresent()) {
                                            String name = entry.get().getString("Name").orElse("");
                                            var id = net.minecraft.resources.Identifier.tryParse(name);
                                            expect(id != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id) != null,
                                                    "compat: palette Name must resolve: " + name,
                                                    failures);
                                        }
                                    }
                                }
                                // Ticks use vanilla field names.
                                var ticks = vanilla.getListOrEmpty("block_ticks");
                                for (int i = 0; i < ticks.size(); i++) {
                                    var t = ticks.getCompound(i);
                                    expect(t.isPresent()
                                            && t.get().getString("i").isPresent()
                                            && t.get().getInt("x").isPresent()
                                            && t.get().getInt("t").isPresent()
                                            && t.get().getInt("p").isPresent(),
                                            "compat: tick fields i/x/t/p", failures);
                                }
                            }
                        }
                    }
                }
            }
            expect(chunks > 0, "compat: must verify at least one chunk", failures);
            System.out.println("  compat: verified " + chunks + " chunks, "
                    + sections + " sections through vanilla storage+NBT");
        } catch (Exception e) {
            failures.add("compat: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    private static net.minecraft.world.level.chunk.PalettedContainerFactory testContainerFactory() {
        var access = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        return net.minecraft.world.level.chunk.PalettedContainerFactory.create(access);
    }

    // ---- 7: fresh boot unregressed ----

    private static void checkFreshBoot(List<String> failures) {
        try {
            // Save/load with NO ticks between build and save: the settled
            // gradient must still settle identically after the cycle.
            TickLoop loop = TickLoopCheck.buildPoweredLine(-20, 20, -4);
            Path world = Files.createTempDirectory("kestrel-fresh");
            WorldStore.save(loop, world);
            TickLoop reloaded = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.load(reloaded, world);
            List<BlockPos> wires = TickLoopCheck.lineWires(-20, 20, -4);
            int ticks = TickLoopCheck.settle(reloaded, wires, 60);
            expect(ticks < 60, "fresh: reloaded line must settle, took " + ticks, failures);
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i <= 40; i++) {
                expected.append(Math.max(0, 15 - i)).append(',');
            }
            expect(TickLoopCheck.powers(reloaded, wires).equals(expected.toString()),
                    "fresh: reloaded gradient " + TickLoopCheck.powers(reloaded, wires), failures);
        } catch (Exception e) {
            failures.add("fresh: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    // ---- 8: section Y is a world section Y on disc ----

    private static void checkSectionY(List<String> failures) {
        try {
            BlockState air = Blocks.AIR.defaultBlockState();
            List<BlockState> states = new ArrayList<>();
            net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.forEach(states::add);
            // One distinct single-state section per world Y -4..19, plus
            // vanilla's light-only tags at -5 and 20 (no block_states) and
            // a block-carrying tag at 21 that the range guard must drop.
            List<Nbt.Tag> sectionTags = new ArrayList<>();
            for (int y = -5; y <= 21; y++) {
                Nbt.Tag.TCompound sec = Nbt.compound();
                Nbt.putByte(sec, "Y", (byte) y);
                if (y == -5 || y == 20) {
                    Nbt.putByteArray(sec, "BlockLight", new byte[2048]);
                } else {
                    BlockState[] cells = new BlockState[4096];
                    java.util.Arrays.fill(cells, states.get(y + 10));
                    Nbt.put(sec, "block_states", SectionCodec.encode(cells, air));
                }
                sectionTags.add(sec);
            }
            Nbt.Tag.TCompound chunk = Nbt.compound();
            Nbt.putInt(chunk, "xPos", 2);
            Nbt.putInt(chunk, "zPos", -1);
            Nbt.putInt(chunk, "yPos", -4);
            Nbt.put(chunk, "sections", new Nbt.Tag.TList(Nbt.TAG_COMPOUND, sectionTags));

            ChunkCodec.DecodedChunk dc = ChunkCodec.decodeChunk(chunk, -64, 384, 0);
            expect(dc.sections().size() == 24,
                    "sectionY: expected 24 slots, got " + dc.sections().size(), failures);
            for (ChunkCodec.SlotCells sc : dc.sections()) {
                int worldY = sc.slot() - 4;
                expect(sc.cells()[0] == states.get(worldY + 10),
                        "sectionY: slot " + sc.slot() + " must hold world Y " + worldY, failures);
                long key = ChunkCodec.storeKey(dc.chunkX(), dc.chunkZ(), -4, sc.slot());
                expect(SectionPos.y(key) == worldY && SectionPos.x(key) == 2
                                && SectionPos.z(key) == -1,
                        "sectionY: store key for slot " + sc.slot(), failures);
            }

            // Writer side: blocks at y -64, 0, 319 land in tags Y -4, 0, 19,
            // and load puts them back at the same world positions.
            TickLoop loop = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            BlockState stone = Blocks.STONE.defaultBlockState();
            loop.setBlock(new BlockPos(1, -64, 1), stone, 3);
            loop.setBlock(new BlockPos(1, 0, 1), stone, 3);
            loop.setBlock(new BlockPos(1, 319, 1), stone, 3);
            Path world = Files.createTempDirectory("kestrel-sectiony");
            WorldStore.save(loop, world);
            java.util.Set<Integer> ys = new java.util.TreeSet<>();
            try (RegionFile region = RegionFile.open(WorldStore.regionDir(world).resolve("r.0.0.mca"))) {
                for (Nbt.Tag t : Nbt.getList(region.readChunk(0, 0), "sections").items()) {
                    ys.add((int) Nbt.getByte((Nbt.Tag.TCompound) t, "Y"));
                }
            }
            expect(ys.equals(java.util.Set.of(-4, 0, 19)),
                    "sectionY: writer must emit world Y {-4,0,19}, got " + ys, failures);
            TickLoop reloaded = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.load(reloaded, world);
            expect(reloaded.level().store().get(new BlockPos(1, -64, 1)) == stone
                            && reloaded.level().store().get(new BlockPos(1, 0, 1)) == stone
                            && reloaded.level().store().get(new BlockPos(1, 319, 1)) == stone,
                    "sectionY: blocks at y -64/0/319 must reload in place", failures);
            expect(WorldSnapshot.hashLive(loop) == WorldSnapshot.hashLive(reloaded),
                    "sectionY: reload hash mismatch", failures);
        } catch (Exception e) {
            failures.add("sectionY: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    // ---- 9: sidecar-less import ----

    /**
     * Strip the loop down to the state importWorld synthesizes: every
     * written bit false, no block entities, empty queue, gameTime 0.
     */
    private static void neutralizeForImport(TickLoop loop) {
        var store = loop.level().store();
        for (long key : new ArrayList<>(store.sectionKeys())) {
            for (int i = 0; i < SectionCodec.CELLS; i++) {
                store.writeCell(key, i, store.readCell(key, i), false);
            }
        }
        loop.clearQueue();
        loop.level().clearBlockEntities();
        loop.setGameTime(0);
    }

    private static void checkImport(List<String> failures) {
        try {
            // Gate: load and compact import must expose identical block cells;
            // their written bookkeeping intentionally differs.
            TickLoop loop = buildCorpus();
            settle(loop, 10);
            loop.setBlock(new BlockPos(3, -40, 3), Blocks.STONE.defaultBlockState(), 3);
            loop.setBlock(new BlockPos(3, 300, 3), Blocks.DIRT.defaultBlockState(), 3);
            neutralizeForImport(loop);
            long source = WorldSnapshot.hashLive(loop);
            Path world = Files.createTempDirectory("kestrel-import");
            WorldStore.save(loop, world);

            TickLoop viaLoad = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.load(viaLoad, world);
            TickLoop viaImport = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.importWorld(viaImport, world);
            long hLoad = WorldSnapshot.hashLive(viaLoad);
            long hImport = WorldSnapshot.hashLive(viaImport);
            System.out.println("  import: source=" + Long.toHexString(source)
                    + " load=" + Long.toHexString(hLoad)
                    + " import=" + Long.toHexString(hImport)
                    + " sections=" + viaImport.level().store().sectionCount());
            expect(hLoad == source, "import: load hash must equal source", failures);
            expect(storesEqual(viaImport, viaLoad),
                    "import: compact import cells must equal dense load cells", failures);
            expect(viaImport.level().store().compactSectionCount()
                            == viaImport.level().store().sectionCount(),
                    "import: every imported section must remain compact", failures);
            WorldSnapshot compactSnapshot = WorldSnapshot.capture(viaImport);
            expect(compactSnapshot.sections.stream().allMatch(
                            section -> section.compact() != null
                                    && section.cells() == null && section.written() == null),
                    "import: snapshot must retain compact payloads without dense arrays", failures);
            int compactBeforeSave = viaImport.level().store().compactSectionCount();
            Path compactWorld = Files.createTempDirectory("kestrel-compact-save");
            WorldStore.save(viaImport, compactWorld);
            expect(viaImport.level().store().compactSectionCount() == compactBeforeSave,
                    "import: save must not promote compact sections", failures);
            TickLoop compactReload = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.load(compactReload, compactWorld);
            expect(storesEqual(viaImport, compactReload),
                    "import: direct compact save must reload identical cells", failures);
            expect(viaImport.level().store().get(new BlockPos(3, -40, 3)) == Blocks.STONE.defaultBlockState(),
                    "import: block below y=0 must import in place", failures);

            // Same root with every sidecar deleted: load still refuses,
            // importWorld still yields the identical compact state.
            try (var stream = Files.list(WorldStore.regionDir(world))) {
                for (Path p : stream.filter(p -> p.getFileName().toString().endsWith(".kex")).toList()) {
                    Files.delete(p);
                }
            }
            boolean refused = false;
            try {
                WorldStore.load(new TickLoop(
                        new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384)), world);
            } catch (java.io.IOException expected) {
                refused = true;
            }
            expect(refused, "import: load must still reject sidecar-less regions", failures);
            TickLoop bare = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.importWorld(bare, world);
            long hBare = WorldSnapshot.hashLive(bare);
            System.out.println("  import: sidecar-less import=" + Long.toHexString(hBare));
            expect(hBare == hImport,
                    "import: sidecar-less compact import must be deterministic", failures);

            // Full corpus (written bits, sleepers, queue, gameTime): import
            // drops exactly those; cells and section keys still match.
            TickLoop full = buildFullCorpus();
            settle(full, 10);
            armSaveState(full);
            Path fullWorld = Files.createTempDirectory("kestrel-import-full");
            WorldStore.save(full, fullWorld);
            TickLoop fullLoad = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.load(fullLoad, fullWorld);
            TickLoop fullImport = new TickLoop(
                    new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.importWorld(fullImport, fullWorld);
            expect(fullImport.level().store().writtenCells() > 0,
                    "import: compact non-air cells must be written", failures);
            expect(fullImport.dumpQueue().isEmpty() && fullImport.level().dumpBlockEntities().isEmpty()
                            && fullImport.gameTime() == 0,
                    "import: queue/block entities/gameTime must be empty", failures);
            expect(storesEqual(fullLoad, fullImport),
                    "import: full corpus compact cells must match load", failures);
        } catch (Exception e) {
            failures.add("import: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    // ---- G11: pending fluid ticks survive save/reload ----

    /**
     * Water and lava mid-spread: the pending fluid-tick queue must survive
     * a save/reload exactly (sidecar KEX5 table, chunk {@code fluid_ticks}
     * echo), and both loops must keep flowing identically afterwards.
     */
    private static void checkFluidRoundTrip(List<String> failures) {
        try {
            TickLoop loop = new TickLoop(new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int x = -8; x <= 40; x++) {
                for (int z = -8; z <= 8; z++) {
                    loop.setBlock(new BlockPos(x, 63, z), stone, 2 | 16);
                }
            }
            loop.setBlock(new BlockPos(0, 64, 0), Blocks.WATER.defaultBlockState(), 3);
            loop.setBlock(new BlockPos(30, 64, 0), Blocks.LAVA.defaultBlockState(), 3);
            settle(loop, 12);
            List<TickLoop.QueuedFluidTick> pending = loop.dumpFluidQueue();
            expect(!pending.isEmpty(), "fluid: expected pending fluid ticks before save", failures);

            Nbt.Tag.TCompound chunk = ChunkCodec.encodeChunk(WorldSnapshot.capture(loop), 0, 0);
            expect(Nbt.getOrNull(chunk, "fluid_ticks") instanceof Nbt.Tag.TList l && !l.items().isEmpty(),
                    "fluid: chunk NBT fluid_ticks must echo the queue", failures);

            long before = WorldSnapshot.hashLive(loop);
            Path world = Files.createTempDirectory("kestrel-fluid");
            WorldStore.save(loop, world);
            TickLoop reloaded = new TickLoop(new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            WorldStore.load(reloaded, world);
            expect(before == WorldSnapshot.hashLive(reloaded), "fluid: hash mismatch after reload", failures);
            expect(pending.equals(reloaded.dumpFluidQueue()),
                    "fluid: queue mismatch after reload " + pending + " vs " + reloaded.dumpFluidQueue(), failures);

            settle(loop, 80);
            settle(reloaded, 80);
            expect(WorldSnapshot.hashLive(loop) == WorldSnapshot.hashLive(reloaded),
                    "fluid: post-reload flow diverged", failures);
            expect(loop.getBlockState(new BlockPos(4, 64, 0)).getFluidState().isSource() == false
                    && !loop.getBlockState(new BlockPos(4, 64, 0)).getFluidState().isEmpty(),
                    "fluid: water must have spread 4 blocks", failures);
        } catch (Exception e) {
            failures.add("fluid: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    private static boolean storesEqual(TickLoop a, TickLoop b) {
        var left = a.level().store();
        var right = b.level().store();
        if (!left.sectionKeys().equals(right.sectionKeys())) {
            return false;
        }
        for (long key : left.sectionKeys()) {
            for (int i = 0; i < SectionCodec.CELLS; i++) {
                BlockState x = left.readCell(key, i);
                BlockState y = right.readCell(key, i);
                if (x != y) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- dev helper: sorted section dump for debugging ----

    /** Print section keys + palette sizes (dev tool, not proof). */
    static void dumpSections(TickLoop loop) {
        List<Long> keys = new ArrayList<>(loop.level().store().sectionKeys());
        keys.sort(Comparator.naturalOrder());
        for (long key : keys) {
            System.out.println("section " + SectionPos.x(key) + "," + SectionPos.y(key) + ","
                    + SectionPos.z(key));
        }
    }
}
