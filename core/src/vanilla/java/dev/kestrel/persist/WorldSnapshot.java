package dev.kestrel.persist;

import dev.kestrel.blockentity.SleeperRegistry;
import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 2 tick-thread snapshot: the complete own-code world state copied
 * off the live loop in one pass, so the saver thread can serialize and
 * write without ever touching tick-thread structures.
 *
 * <p>Contents: every live section's 4096 cells (unset cells captured as
 * null, re-resolved to air by the writer) plus the written bitset copy;
 * every block-entity record with its sleeper type and asleep flag; every
 * pending queue entry with its absolute trigger tick; completed chunk keys; the gameTime clock;
 * the level's minY/height geometry. Cancelled queue entries are dropped:
 * they are already dead on the tick thread ({@code drainScheduled}
 * skips them), so persisting them would resurrect ticks vanilla dropped.
 *
 * <p>Cost: one pass over live sections plus small queue/registry walks.
 * The snapshot holds full cell arrays until the saver finishes, which is
 * the peak-memory cost the async design accepts (documented in the plan
 * decision): the tick thread itself only pays the copy.
 *
 * <p>Thread-safety: capture runs on the tick thread; after capture the
 * snapshot is effectively immutable and owned by the saver thread.
 */
public final class WorldSnapshot {

    /**
     * One live section: key plus copied cells plus copied written flags,
     * plus the C1 mutation-provenance bitset ({@code edited}, null when no
     * cell of the section is live-layer owned). A compact section records
     * its whole-section seal as an all-true {@code edited} bitset, so the
     * on-disk format needs only one representation.
     */
    public record SectionData(long sectionKey, BlockState[] cells, boolean[] written,
            dev.kestrel.loop.SectionStore.CompactData compact, boolean[] edited) {
        public SectionData(long sectionKey, BlockState[] cells, boolean[] written) {
            this(sectionKey, cells, written, null, null);
        }

        public SectionData(long sectionKey, BlockState[] cells, boolean[] written,
                boolean[] edited) {
            this(sectionKey, cells, written, null, edited);
        }

        public SectionData(long sectionKey, BlockState[] cells, boolean[] written,
                dev.kestrel.loop.SectionStore.CompactData compact) {
            this(sectionKey, cells, written, compact, null);
        }

        boolean isEdited(int index) {
            return edited != null && edited[index];
        }

        BlockState cell(int index) {
            return compact != null
                    ? SectionCodec.compactCell(
                            compact.palette(), compact.data(), compact.bits(), index)
                    : cells[index];
        }

        boolean isWritten(int index) {
            BlockState state = compact != null ? cell(index) : null;
            return compact != null ? state != null && !state.isAir() : written[index];
        }
    }

    /** One block entity: facade position, sleep-set type, asleep flag. */
    public record BlockEntityData(int x, int y, int z, SleeperRegistry.Type type, boolean asleep) {
    }

    /** One pending tick: facade position, target kind/priority, trigger tick. */
    public record QueuedTickData(int x, int y, int z,
            dev.kestrel.loop.KestrelTickTarget.Kind kind,
            dev.kestrel.loop.KestrelTickTarget.Priority priority,
            long triggerTick) {
    }

    /**
     * One pending fluid tick (G11): facade position, fluid, vanilla
     * priority value, absolute trigger tick, and {@code seq}, the entry's
     * index in the world-wide drain order at capture. The sidecar splits
     * the queue by region; {@code seq} lets the loader restore entries in
     * their original order so same-trigger, same-priority ticks keep their
     * relative order across a reload (restore assigns subTickOrder in
     * restore order). Not part of the hash: list order already is.
     */
    public record QueuedFluidTickData(int x, int y, int z,
            net.minecraft.world.level.material.Fluid fluid, int priority, long triggerTick,
            int seq) {
    }

    /** Vanilla registry id of a tickable fluid ({@code fluid_ticks} "i"). */
    static String fluidId(net.minecraft.world.level.material.Fluid f) {
        return net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(f).toString();
    }

    /** Fluid for a {@code fluid_ticks} id, or null when not water/lava. */
    static net.minecraft.world.level.material.Fluid fluidOf(String id) {
        return switch (id) {
            case "minecraft:water" -> net.minecraft.world.level.material.Fluids.WATER;
            case "minecraft:flowing_water" -> net.minecraft.world.level.material.Fluids.FLOWING_WATER;
            case "minecraft:lava" -> net.minecraft.world.level.material.Fluids.LAVA;
            case "minecraft:flowing_lava" -> net.minecraft.world.level.material.Fluids.FLOWING_LAVA;
            default -> null;
        };
    }

    public final KestrelLevel.Dimension dimension;
    public final int minY;
    public final int height;
    public final long gameTime;
    public final List<SectionData> sections;
    public final List<BlockEntityData> blockEntities;
    public final List<QueuedTickData> queue;
    /** Pending fluid ticks in drain order (G11). */
    public final List<QueuedFluidTickData> fluidQueue;
    public final Set<Long> generatedChunks;
    /** Exact Vanilla biome PalettedContainer wire bytes by SectionPos key. */
    public final Map<Long, byte[]> biomeWireSections;
    /** Exact Vanilla light packet payload bytes by ChunkPos key. */
    public final Map<Long, byte[]> lightWireChunks;
    /**
     * Pending chunk-activation post-processing by ChunkPos key: one packed
     * offset array per section index (empty when none), vanilla's
     * {@code PostProcessing} chunk tag.
     */
    public final Map<Long, short[][]> postProcessing;

    private WorldSnapshot(KestrelLevel.Dimension dimension, int minY, int height, long gameTime,
            List<SectionData> sections, List<BlockEntityData> blockEntities,
            List<QueuedTickData> queue, List<QueuedFluidTickData> fluidQueue,
            Set<Long> generatedChunks,
            Map<Long, byte[]> biomeWireSections, Map<Long, byte[]> lightWireChunks,
            Map<Long, short[][]> postProcessing) {
        this.dimension = dimension;
        this.minY = minY;
        this.height = height;
        this.gameTime = gameTime;
        this.sections = sections;
        this.blockEntities = blockEntities;
        this.queue = queue;
        List<QueuedFluidTickData> fq = new ArrayList<>(fluidQueue);
        fq.sort(java.util.Comparator.comparingInt(QueuedFluidTickData::seq));
        this.fluidQueue = List.copyOf(fq);
        this.generatedChunks = Set.copyOf(generatedChunks);
        this.biomeWireSections = freezeWireMap(biomeWireSections);
        this.lightWireChunks = freezeWireMap(lightWireChunks);
        this.postProcessing = freezePostProcessing(postProcessing, height >> 4);
    }

    private static Map<Long, short[][]> freezePostProcessing(Map<Long, short[][]> source,
            int sectionCount) {
        Map<Long, short[][]> copy = new HashMap<>();
        for (Map.Entry<Long, short[][]> entry : source.entrySet()) {
            short[][] sections = entry.getValue();
            if (sections.length != sectionCount) {
                throw new IllegalArgumentException("post-processing for chunk key " + entry.getKey()
                        + " has " + sections.length + " section lists, expected " + sectionCount);
            }
            short[][] frozen = new short[sectionCount][];
            boolean any = false;
            for (int i = 0; i < sectionCount; i++) {
                frozen[i] = sections[i] == null ? new short[0] : sections[i].clone();
                any |= frozen[i].length > 0;
            }
            if (any) {
                copy.put(entry.getKey(), frozen);
            }
        }
        return Map.copyOf(copy);
    }

    private static Map<Long, byte[]> freezeWireMap(Map<Long, byte[]> source) {
        Map<Long, byte[]> copy = new HashMap<>();
        for (Map.Entry<Long, byte[]> entry : source.entrySet()) {
            byte[] value = entry.getValue();
            if (value == null || value.length == 0) {
                throw new IllegalArgumentException("persisted wire metadata must be non-empty");
            }
            copy.put(entry.getKey(), value.clone());
        }
        return Map.copyOf(copy);
    }

    /**
     * Capture the full snapshot off the live loop. Must run on the tick
     * thread: it walks live store arrays and the live queue/registry.
     */
    public static WorldSnapshot capture(TickLoop loop) {
        KestrelLevel level = loop.level();
        List<SectionData> sections = new ArrayList<>();
        for (long key : level.store().sectionKeys()) {
            boolean[] edited = level.store().editedBitset(key);
            dev.kestrel.loop.SectionStore.CompactData compact = level.store().compactData(key);
            if (compact != null) {
                sections.add(new SectionData(key, null, null, compact, edited));
                continue;
            }
            BlockState[] cells = new BlockState[SectionCodec.CELLS];
            boolean[] written = new boolean[SectionCodec.CELLS];
            for (int i = 0; i < SectionCodec.CELLS; i++) {
                cells[i] = level.store().readCell(key, i);
                written[i] = level.store().isCellWritten(key, i);
            }
            sections.add(new SectionData(key, cells, written, edited));
        }
        sections.sort((a, b) -> Long.compare(a.sectionKey(), b.sectionKey()));

        Map<Long, Boolean> asleepById = new HashMap<>();
        for (SleeperRegistry.SleeperEntry e : level.sleepers().dumpSleepers()) {
            asleepById.put(e.id(), e.asleep());
        }
        List<BlockEntityData> bes = new ArrayList<>();
        for (KestrelLevel.BlockEntityRecord rec : level.dumpBlockEntities()) {
            long id = rec.pos().asLong();
            Boolean asleep = asleepById.get(id);
            bes.add(new BlockEntityData(rec.pos().x(), rec.pos().y(), rec.pos().z(),
                    rec.type(), asleep != null && asleep));
        }

        List<QueuedTickData> queue = new ArrayList<>();
        for (TickLoop.QueuedTick q : loop.dumpQueue()) {
            queue.add(new QueuedTickData(q.pos().x(), q.pos().y(), q.pos().z(),
                    q.kind(), q.priority(), q.triggerTick()));
        }
        List<QueuedFluidTickData> fluidQueue = new ArrayList<>();
        for (TickLoop.QueuedFluidTick q : loop.dumpFluidQueue()) {
            fluidQueue.add(new QueuedFluidTickData(q.pos().x(), q.pos().y(), q.pos().z(),
                    q.fluid(), q.priority(), q.triggerTick(), fluidQueue.size()));
        }

        return new WorldSnapshot(level.dimension(), level.getMinY(), level.getHeight(),
                loop.gameTime(), sections, bes, queue, fluidQueue,
                level.store().generatedChunkKeys(),
                level.biomeWireSectionsSnapshot(), level.lightWireChunksSnapshot(),
                level.postProcessingSnapshot());
    }

    /**
     * Canonical 64-bit hash of the snapshot (FNV-1a over a fixed field
     * order): section keys, per-cell global state ids plus written bit,
     * block entities (pos, type, asleep), queue (pos, kind, priority,
     * trigger), gameTime, geometry. The save/reload proof gate compares
     * this hash before save against a fresh capture after reload:
     * identical means the round trip preserved everything the loop ticks
     * over.
     */
    public long hash() {
        long h = 0xcbf29ce484222325L;
        h = mix(h, dimension.ordinal());
        h = mix(h, minY);
        h = mix(h, height);
        h = mixLong(h, gameTime);
        for (SectionData s : sections) {
            h = mixLong(h, s.sectionKey());
            for (int i = 0; i < SectionCodec.CELLS; i++) {
                BlockState cell = s.cell(i);
                BlockState st = cell != null ? cell : Blocks.AIR.defaultBlockState();
                h = mix(h, net.minecraft.world.level.block.Block.getId(st));
                h = mix(h, s.isWritten(i) ? 1 : 0);
            }
        }
        for (BlockEntityData b : blockEntities) {
            h = mix(h, b.x());
            h = mix(h, b.y());
            h = mix(h, b.z());
            h = mix(h, b.type().ordinal());
            h = mix(h, b.asleep() ? 1 : 0);
        }
        for (QueuedTickData q : queue) {
            h = mix(h, q.x());
            h = mix(h, q.y());
            h = mix(h, q.z());
            h = mix(h, q.kind().ordinal());
            h = mix(h, vanillaPriorityValue(q.priority()));
            h = mixLong(h, q.triggerTick());
        }
        // Fluid queue (G11) mixes only when non-empty, so a world with no
        // pending fluid ticks keeps its pre-G11 hash.
        if (!fluidQueue.isEmpty()) {
            h = mix(h, 0x464c5544); // FLUD
            for (QueuedFluidTickData q : fluidQueue) {
                h = mix(h, q.x());
                h = mix(h, q.y());
                h = mix(h, q.z());
                h = mix(h, net.minecraft.core.registries.BuiltInRegistries.FLUID.getId(q.fluid()));
                h = mix(h, q.priority());
                h = mixLong(h, q.triggerTick());
            }
        }
        for (long chunkKey : generatedChunks.stream().sorted().toList()) {
            h = mixLong(h, chunkKey);
        }
        h = mix(h, 0x42494f4d); // BIOM
        h = mixWireMap(h, biomeWireSections);
        h = mix(h, 0x4c495447); // LITG
        h = mixWireMap(h, lightWireChunks);
        // Post-processing mixes only when some chunk has pending entries, so
        // a world without any keeps its earlier hash (same rule as FLUD).
        if (!postProcessing.isEmpty()) {
            h = mix(h, 0x50535450); // PSTP
            for (Map.Entry<Long, short[][]> entry : postProcessing.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                h = mixLong(h, entry.getKey());
                for (short[] section : entry.getValue()) {
                    h = mix(h, section.length);
                    for (short packed : section) {
                        h = mix(h, packed);
                    }
                }
            }
        }
        return h;
    }

    /**
     * Canonical hash of the LIVE loop without copying cells: walks the
     * same field order as {@link #hash} but reads through the store
     * seams. Used pre-save (tick thread) and post-reload so the proof
     * gate never pays a snapshot copy just to hash.
     */
    public static long hashLive(TickLoop loop) {
        long h = 0xcbf29ce484222325L;
        KestrelLevel level = loop.level();
        h = mixStatic(h, level.dimension().ordinal());
        h = mixStatic(h, level.getMinY());
        h = mixStatic(h, level.getHeight());
        h = mixLongStatic(h, loop.gameTime());
        List<Long> keys = new ArrayList<>(level.store().sectionKeys());
        keys.sort(Long::compare);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (long key : keys) {
            h = mixLongStatic(h, key);
            for (int i = 0; i < SectionCodec.CELLS; i++) {
                BlockState st = level.store().readCell(key, i);
                h = mixStatic(h, net.minecraft.world.level.block.Block.getId(
                        st != null ? st : air));
                h = mixStatic(h, level.store().isCellWritten(key, i) ? 1 : 0);
            }
        }
        Map<Long, Boolean> asleepById = new HashMap<>();
        for (SleeperRegistry.SleeperEntry e : level.sleepers().dumpSleepers()) {
            asleepById.put(e.id(), e.asleep());
        }
        for (KestrelLevel.BlockEntityRecord rec : level.dumpBlockEntities()) {
            Boolean asleep = asleepById.get(rec.pos().asLong());
            h = mixStatic(h, rec.pos().x());
            h = mixStatic(h, rec.pos().y());
            h = mixStatic(h, rec.pos().z());
            h = mixStatic(h, rec.type().ordinal());
            h = mixStatic(h, asleep != null && asleep ? 1 : 0);
        }
        // Queue priority serializes as the vanilla int value (TickPriority
        // CAN differ from facade ordinal: EXTREMELY cases collapse), so
        // the hash uses the vanilla value, matching the chunk codec path.
        for (TickLoop.QueuedTick q : loop.dumpQueue()) {
            h = mixStatic(h, q.pos().x());
            h = mixStatic(h, q.pos().y());
            h = mixStatic(h, q.pos().z());
            h = mixStatic(h, q.kind().ordinal());
            h = mixStatic(h, vanillaPriorityValue(q.priority()));
            h = mixLongStatic(h, q.triggerTick());
        }
        List<TickLoop.QueuedFluidTick> liveFluids = loop.dumpFluidQueue();
        if (!liveFluids.isEmpty()) {
            h = mixStatic(h, 0x464c5544); // FLUD, same rule as hash()
            for (TickLoop.QueuedFluidTick q : liveFluids) {
                h = mixStatic(h, q.pos().x());
                h = mixStatic(h, q.pos().y());
                h = mixStatic(h, q.pos().z());
                h = mixStatic(h, net.minecraft.core.registries.BuiltInRegistries.FLUID.getId(q.fluid()));
                h = mixStatic(h, q.priority());
                h = mixLongStatic(h, q.triggerTick());
            }
        }
        for (long chunkKey : level.store().generatedChunkKeys().stream().sorted().toList()) {
            h = mixLongStatic(h, chunkKey);
        }
        h = mixStatic(h, 0x42494f4d); // BIOM
        h = mixWireMapStatic(h, level.biomeWireSectionsSnapshot());
        h = mixStatic(h, 0x4c495447); // LITG
        h = mixWireMapStatic(h, level.lightWireChunksSnapshot());
        Map<Long, short[][]> livePost = level.postProcessingSnapshot();
        if (!livePost.isEmpty()) {
            h = mixStatic(h, 0x50535450); // PSTP, same rule as hash()
            for (Map.Entry<Long, short[][]> entry : livePost.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                h = mixLongStatic(h, entry.getKey());
                for (short[] section : entry.getValue()) {
                    h = mixStatic(h, section.length);
                    for (short packed : section) {
                        h = mixStatic(h, packed);
                    }
                }
            }
        }
        return h;
    }

    private long mixWireMap(long h, Map<Long, byte[]> map) {
        h = mix(h, map.size());
        for (Map.Entry<Long, byte[]> entry : map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            h = mixLong(h, entry.getKey());
            h = mix(h, entry.getValue().length);
            for (byte b : entry.getValue()) {
                h = mix(h, b & 0xff);
            }
        }
        return h;
    }

    private static long mixWireMapStatic(long h, Map<Long, byte[]> map) {
        h = mixStatic(h, map.size());
        for (Map.Entry<Long, byte[]> entry : map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            h = mixLongStatic(h, entry.getKey());
            h = mixStatic(h, entry.getValue().length);
            for (byte b : entry.getValue()) {
                h = mixStatic(h, b & 0xff);
            }
        }
        return h;
    }

    /** Vanilla int value for a facade priority (TickPriority.getValue). */
    static int vanillaPriorityValue(dev.kestrel.loop.KestrelTickTarget.Priority p) {
        return p.value();
    }

    /**
     * Restore the snapshot into an empty loop: clear storage, queue,
     * block entities and clock, then write every cell with its written
     * flag, re-register every block entity with its asleep flag, and
     * re-queue every pending tick at its absolute trigger. Must run on
     * the tick thread of the target loop.
     */
    public void restoreInto(TickLoop loop) {
        KestrelLevel level = loop.level();
        level.store().clearAll();
        loop.clearQueue();
        level.clearBlockEntities();
        level.clearGeneratedMetadata();
        loop.setGameTime(gameTime);
        for (SectionData s : sections) {
            if (s.compact() != null) {
                // A compact section cannot carry partial claims (a live
                // write promotes it to dense), so any claim at all restores
                // as the whole-section seal it was.
                level.store().putCompact(s.sectionKey(), s.compact().palette(),
                        s.compact().data(), s.compact().bits(), s.edited() != null);
                continue;
            }
            for (int i = 0; i < SectionCodec.CELLS; i++) {
                if (s.cells()[i] == null && !s.written()[i] && !s.isEdited(i)) {
                    continue;
                }
                level.store().writeCell(s.sectionKey(), i, s.cells()[i], s.written()[i],
                        s.isEdited(i));
            }
        }
        for (BlockEntityData b : blockEntities) {
            level.restoreBlockEntity(new dev.kestrel.loop.KestrelPos(b.x(), b.y(), b.z()),
                    b.type(), b.asleep());
        }
        for (QueuedTickData q : queue) {
            loop.restoreQueueEntry(new dev.kestrel.loop.KestrelPos(q.x(), q.y(), q.z()),
                    q.kind(), q.priority(), q.triggerTick());
        }
        for (QueuedFluidTickData q : fluidQueue) {
            loop.restoreFluidQueueEntry(new dev.kestrel.loop.KestrelPos(q.x(), q.y(), q.z()),
                    q.fluid(), q.priority(), q.triggerTick());
        }
        for (long chunkKey : generatedChunks) {
            level.store().markChunkGenerated(chunkKey);
        }
        for (Map.Entry<Long, byte[]> entry : biomeWireSections.entrySet()) {
            level.putBiomeWireSection(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<Long, byte[]> entry : lightWireChunks.entrySet()) {
            level.putLightWireChunk(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<Long, short[][]> entry : postProcessing.entrySet()) {
            short[][] sections = entry.getValue();
            it.unimi.dsi.fastutil.shorts.ShortList[] lists =
                    new it.unimi.dsi.fastutil.shorts.ShortList[sections.length];
            for (int i = 0; i < sections.length; i++) {
                lists[i] = sections[i].length == 0 ? null
                        : new it.unimi.dsi.fastutil.shorts.ShortArrayList(sections[i]);
            }
            level.putPostProcessing(entry.getKey(), lists);
        }
        // Section-local Y addressing: Kestrel sections key on world
        // section coords (SectionPos.asLong), while chunk NBT numbers
        // sections from minSectionY. ChunkCodec bridges the two (see
        // ChunkCodec.sectionY / worldSectionY); the snapshot path above
        // is section-keyed end to end and needs no translation.
        voidCheck();
    }

    /** Documents that restoreInto covers section addressing (no-op). */
    private void voidCheck() {
    }

    /** Rebuild a snapshot's world-facing parts from a chunk decode. */
    static WorldSnapshot fromDecoded(int minY, int height, long gameTime,
            KestrelLevel.Dimension dimension, List<SectionData> sections,
            List<BlockEntityData> bes, List<QueuedTickData> queue,
            List<QueuedFluidTickData> fluidQueue,
            Set<Long> generatedChunks, Map<Long, byte[]> biomeWireSections,
            Map<Long, byte[]> lightWireChunks, Map<Long, short[][]> postProcessing) {
        return new WorldSnapshot(dimension, minY, height, gameTime, sections, bes, queue,
                fluidQueue, generatedChunks, biomeWireSections, lightWireChunks, postProcessing);
    }

    private long mix(long h, int v) {
        h ^= v;
        h *= 0x100000001b3L;
        return h;
    }

    private long mixLong(long h, long v) {
        h = mix(h, (int) (v & 0xFFFFFFFFL));
        return mix(h, (int) (v >>> 32));
    }

    private static long mixStatic(long h, int v) {
        h ^= v;
        h *= 0x100000001b3L;
        return h;
    }

    private static long mixLongStatic(long h, long v) {
        h = mixStatic(h, (int) (v & 0xFFFFFFFFL));
        return mixStatic(h, (int) (v >>> 32));
    }

    /** Section origin for a store key (world coords of cell 0,0,0). */
    public static BlockPos sectionOrigin(long sectionKey) {
        return new BlockPos(SectionPos.x(sectionKey) << 4,
                SectionPos.y(sectionKey) << 4, SectionPos.z(sectionKey) << 4);
    }
}
