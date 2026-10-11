package dev.kestrel.persist;

import dev.kestrel.blockentity.SleeperRegistry;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2 chunk encode/decode: snapshot sections to vanilla-shaped chunk
 * NBT and back.
 *
 * <p>Chunk NBT layout, grounded in
 * {@code SerializableChunkData.write} (decompile
 * {@code SerializableChunkData.java:417-481}): flat (no {@code Level}
 * wrapper); int {@code DataVersion} (26.2 writes 4903,
 * {@code SharedConstants.java:18}); ints {@code xPos}/{@code zPos} plus
 * int {@code yPos} (minSectionY); longs {@code LastUpdate}/
 * {@code InhabitedTime} (zero: Kestrel tracks no ages); string
 * {@code Status} ({@code minecraft:full}); {@code sections} list of
 * {@code {Y: byte, block_states: {palette, data?}, biomes:
 * {palette:[plains], no data}}}; {@code block_entities} list (Stage 2:
 * position stubs only, see below); {@code block_ticks}/
 * {@code fluid_ticks} lists of {@code {i, x, y, z, t, p}};
 * {@code PostProcessing} (one list of packed shorts per section, see
 * {@link #postProcessingTag});
 * {@code Heightmaps} (MOTION_BLOCKING recomputed on write);
 * {@code structures} (empty compound).
 *
 * <p>Section numbering: the on-disc section {@code Y} is the WORLD
 * section Y ({@code -4..19} for the overworld), exactly what vanilla
 * writes ({@code SerializableChunkData.write}, javap offsets 305-314) and
 * range-checks against {@code getMinSectionY()..getMaxSectionY()} on
 * parse (javap offsets 514-542). Inside Kestrel a decoded section is a
 * chunk-local slot {@code 0..sections-1} with
 * {@code slot = Y - minSectionY}. The write path emits live slots only
 * (vanilla skips empty sections too); the read path accepts missing
 * slots and skips out-of-range Y values, which drops vanilla's
 * light-only tags one below and one above the build range.
 *
 * <p>Section {@code Y} is a SIGNED byte on disc: world section Y outside
 * {@code -128..127} cannot round-trip through real Anvil. Kestrel's
 * overworld geometry (minY -64, height 384: 24 sections) fits; taller
 * geometries are rejected at save time rather than silently wrapped.
 *
 * <p>Scheduled ticks: vanilla stores per-chunk {@code SavedTick}s with
 * {@code t} as a DELAY relative to the save tick and rehydrates with
 * {@code unpack(currentTick)} adding it back
 * ({@code SavedTick.java:51-53}). Kestrel's absolute triggers convert
 * with {@code delay = trigger - gameTime} on write; non-positive delays
 * (already-due entries) clamp to 0, matching vanilla's
 * schedule-on-load posture. Filter field-for-field on read:
 * {@code filterTickListForChunk} ({@code SavedTick.java:46-49}).
 *
 * <p>Block entities: Stage 2 writes one stub per registered record:
 * string {@code id} (vanilla block-entity type id, e.g.
 * {@code minecraft:furnace}), ints {@code x/y/z}, byte
 * {@code keepPacked: 1}. Vanilla's loader branches on keepPacked: true
 * entries stay packed NBT ({@code SerializableChunkData.java:506-515});
 * Stage 1 bodies are empty by design, so there is nothing to pack yet
 * and the stub round-trips through real vanilla as inert packed data.
 * The SLEEPER id-to-type mapping (which record is a furnace) is NOT
 * trusted from chunk NBT: it lives in the sidecar, which is authoritative.
 *
 * <p>Biomes: every section writes the single-plains palette
 * ({@code minecraft:plains}). Kestrel models no biome data; vanilla
 * requires the tag present for a clean parse, and plains is the palette
 * factory default ({@code PalettedContainerFactory.java:29}).
 *
 * <p>Thread-safety: stateless. Runs on the saver thread (encode) or the
 * tick thread (decode-into-snapshot-parts).
 */
public final class ChunkCodec {

    /** Status Kestrel-written chunks carry. */
    public static final String STATUS_FULL = "minecraft:full";

    /** Biome every section claims (Kestrel models no biomes). */
    public static final String PLAINS_ID = "minecraft:plains";

    private ChunkCodec() {
    }

    /** World section Y for chunk-local section slot Y. */
    static int worldSectionY(int minSectionY, int slotY) {
        return minSectionY + slotY;
    }

    /** Chunk-local slot for a world section Y. */
    static int slotY(int minSectionY, int worldY) {
        return worldY - minSectionY;
    }

    /**
     * Encode one chunk column: gather the snapshot's sections for this
     * (chunkX, chunkZ), emit the full chunk compound. Ticks filter to
     * this chunk; gameTime anchors the delay conversion.
     */
    public static Nbt.Tag.TCompound encodeChunk(WorldSnapshot snap, int chunkX, int chunkZ) {
        int minSectionY = snap.minY >> 4;
        int sectionCount = snap.height >> 4;
        if (minSectionY < -128 || minSectionY + sectionCount - 1 > 127) {
            throw new IllegalArgumentException("section Y out of signed-byte range: minSectionY="
                    + minSectionY + " count=" + sectionCount);
        }
        Nbt.Tag.TCompound tag = Nbt.compound();
        Nbt.putInt(tag, "DataVersion", SharedConstants.WORLD_VERSION);
        Nbt.putInt(tag, "xPos", chunkX);
        Nbt.putInt(tag, "yPos", minSectionY);
        Nbt.putInt(tag, "zPos", chunkZ);
        Nbt.putLong(tag, "LastUpdate", 0L);
        Nbt.putLong(tag, "InhabitedTime", 0L);
        Nbt.putString(tag, "Status", STATUS_FULL);

        Map<Integer, WorldSnapshot.SectionData> bySlot = new HashMap<>();
        for (WorldSnapshot.SectionData s : snap.sections) {
            int sx = SectionPos.x(s.sectionKey());
            int sz = SectionPos.z(s.sectionKey());
            // Store keys are world SECTION coords (block>>4): a chunk
            // column holds exactly one section per Y slot, with section
            // X/Z equal to the chunk X/Z directly (16 blocks share one
            // section coord, so no second shift).
            if (sx != chunkX || sz != chunkZ) {
                continue;
            }
            int slot = slotY(minSectionY, SectionPos.y(s.sectionKey()));
            if (slot >= 0 && slot < sectionCount) {
                bySlot.put(slot, s);
            }
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        List<Nbt.Tag> sectionTags = new ArrayList<>(sectionCount);
        for (int slot = 0; slot < sectionCount; slot++) {
            WorldSnapshot.SectionData s = bySlot.get(slot);
            if (s == null) {
                // Vanilla skips empty sections on write
                // (SerializableChunkData.write:451): no live section
                // means no section tag. The read path fills air.
                continue;
            }
            Nbt.Tag.TCompound sectionTag = Nbt.compound();
            if (s.compact() != null) {
                Nbt.put(sectionTag, "block_states", SectionCodec.encodeCompact(
                        s.compact().palette(), s.compact().data(), s.compact().bits()));
            } else {
                BlockState[] cells = new BlockState[SectionCodec.CELLS];
                for (int i = 0; i < SectionCodec.CELLS; i++) {
                    cells[i] = s.cells()[i] != null ? s.cells()[i] : air;
                }
                Nbt.put(sectionTag, "block_states", SectionCodec.encode(cells, air));
            }
            Nbt.Tag.TCompound biomes = Nbt.compound();
            Nbt.put(biomes, "palette",
                    new Nbt.Tag.TList(Nbt.TAG_STRING, List.of(new Nbt.Tag.TString(PLAINS_ID))));
            Nbt.put(sectionTag, "biomes", biomes);
            Nbt.putByte(sectionTag, "Y", (byte) worldSectionY(minSectionY, slot));
            sectionTags.add(sectionTag);
        }
        Nbt.put(tag, "sections", new Nbt.Tag.TList(Nbt.TAG_COMPOUND, sectionTags));

        List<Nbt.Tag> beTags = new ArrayList<>();
        for (WorldSnapshot.BlockEntityData b : snap.blockEntities) {
            if ((b.x() >> 4) != chunkX || (b.z() >> 4) != chunkZ) {
                continue;
            }
            Nbt.Tag.TCompound be = Nbt.compound();
            Nbt.putString(be, "id", blockEntityId(b.type()));
            Nbt.putInt(be, "x", b.x());
            Nbt.putInt(be, "y", b.y());
            Nbt.putInt(be, "z", b.z());
            Nbt.putBool(be, "keepPacked", true);
            beTags.add(be);
        }
        Nbt.put(tag, "block_entities", new Nbt.Tag.TList(Nbt.TAG_COMPOUND, beTags));

        List<Nbt.Tag> blockTicks = new ArrayList<>();
        for (WorldSnapshot.QueuedTickData q : snap.queue) {
            if ((q.x() >> 4) != chunkX || (q.z() >> 4) != chunkZ) {
                continue;
            }
            if (q.kind() == dev.kestrel.loop.KestrelTickTarget.Kind.OTHER) {
                continue;
            }
            Nbt.Tag.TCompound tick = Nbt.compound();
            Nbt.putString(tick, "i", tickTargetId(q.kind()));
            Nbt.putInt(tick, "x", q.x());
            Nbt.putInt(tick, "y", q.y());
            Nbt.putInt(tick, "z", q.z());
            long delay = q.triggerTick() - snap.gameTime;
            Nbt.putInt(tick, "t", (int) Math.max(0, delay));
            Nbt.putInt(tick, "p", WorldSnapshot.vanillaPriorityValue(q.priority()));
            blockTicks.add(tick);
        }
        Nbt.put(tag, "block_ticks", new Nbt.Tag.TList(Nbt.TAG_COMPOUND, blockTicks));
        List<Nbt.Tag> fluidTicks = new ArrayList<>();
        for (WorldSnapshot.QueuedFluidTickData q : snap.fluidQueue) {
            if ((q.x() >> 4) != chunkX || (q.z() >> 4) != chunkZ) {
                continue;
            }
            Nbt.Tag.TCompound tick = Nbt.compound();
            Nbt.putString(tick, "i", WorldSnapshot.fluidId(q.fluid()));
            Nbt.putInt(tick, "x", q.x());
            Nbt.putInt(tick, "y", q.y());
            Nbt.putInt(tick, "z", q.z());
            Nbt.putInt(tick, "t", (int) Math.max(0, q.triggerTick() - snap.gameTime));
            Nbt.putInt(tick, "p", q.priority());
            fluidTicks.add(tick);
        }
        Nbt.put(tag, "fluid_ticks", new Nbt.Tag.TList(Nbt.TAG_COMPOUND, fluidTicks));

        Nbt.put(tag, "PostProcessing", postProcessingTag(
                snap.postProcessing.get(ChunkPos.pack(chunkX, chunkZ)), sectionCount));

        Nbt.Tag.TCompound heightmaps = Nbt.compound();
        Nbt.putLongArray(heightmaps, "MOTION_BLOCKING",
                computeMotionBlocking(snap, chunkX, chunkZ, minSectionY, sectionCount));
        Nbt.put(tag, "Heightmaps", heightmaps);
        Nbt.put(tag, "structures", Nbt.compound());
        return tag;
    }

    /**
     * {@code SerializableChunkData.packOffsets} ({@code SerializableChunkData.java:597-609}):
     * one inner list per section index of the chunk ({@code getSectionsCount()}
     * entries, {@code SerializableChunkData.java:390-392}), each holding that
     * section's packed offsets as shorts in list order. A section with nothing
     * pending writes an empty list, which vanilla's {@code ListTag} serializes
     * with element type END, so an empty list is written that way here too.
     */
    static Nbt.Tag.TList postProcessingTag(short[][] sections, int sectionCount) {
        List<Nbt.Tag> post = new ArrayList<>(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            short[] offsets = sections == null ? null : sections[i];
            if (offsets == null || offsets.length == 0) {
                post.add(new Nbt.Tag.TList(Nbt.TAG_END, new ArrayList<>()));
                continue;
            }
            List<Nbt.Tag> shorts = new ArrayList<>(offsets.length);
            for (short packed : offsets) {
                shorts.add(new Nbt.Tag.TShort(packed));
            }
            post.add(new Nbt.Tag.TList(Nbt.TAG_SHORT, shorts));
        }
        return new Nbt.Tag.TList(Nbt.TAG_LIST, post);
    }

    /**
     * The read half, {@code SerializableChunkData.parse}
     * ({@code SerializableChunkData.java:138-152}): {@code getListOrEmpty}, then
     * per index {@code getList(i).orElse(null)} (a non-list entry reads as
     * nothing) and per element {@code getShortOr(i, 0)} (a non-short element
     * reads as 0). Vanilla then hands index i to {@code addPackedPostProcess(i)},
     * which indexes the chunk's section array, so a non-empty list past the
     * last section cannot load in vanilla either and fails here.
     *
     * @return one array per section index (empty when none), or null when the
     *         chunk has nothing pending
     */
    static short[][] readPostProcessing(Nbt.Tag.TCompound tag, int sectionCount, int chunkX, int chunkZ)
            throws Nbt.NbtException {
        if (!(Nbt.getOrNull(tag, "PostProcessing") instanceof Nbt.Tag.TList outer)) {
            return null;
        }
        short[][] sections = new short[sectionCount][];
        boolean any = false;
        List<Nbt.Tag> lists = outer.items();
        for (int i = 0; i < lists.size(); i++) {
            if (!(lists.get(i) instanceof Nbt.Tag.TList inner) || inner.items().isEmpty()) {
                continue;
            }
            if (i >= sectionCount) {
                throw new Nbt.NbtException("PostProcessing list " + i + " of chunk " + chunkX + ","
                        + chunkZ + " is past the last section (" + sectionCount + ")");
            }
            short[] offsets = new short[inner.items().size()];
            for (int j = 0; j < offsets.length; j++) {
                offsets[j] = inner.items().get(j) instanceof Nbt.Tag.TShort sh ? sh.value() : 0;
            }
            sections[i] = offsets;
            any = true;
        }
        if (!any) {
            return null;
        }
        for (int i = 0; i < sectionCount; i++) {
            if (sections[i] == null) {
                sections[i] = new short[0];
            }
        }
        return sections;
    }

    /**
     * Decode one chunk compound into snapshot parts: section cells (air
     * where slots are missing), block-tick entries re-based to absolute
     * triggers against gameTime. Block-entity STUBS are decoded for the
     * vanilla-compat check only; sleeper registration comes from the
     * sidecar (authoritative).
     */
    public static DecodedChunk decodeChunk(Nbt.Tag.TCompound tag, int minY, int height,
            long gameTime) throws Nbt.NbtException {
        return decodeChunk(tag, minY, height, gameTime, null);
    }

    /**
     * Decode with an optional import census (null skips counting). The
     * census reads the same tags the decode keeps, so its tag counts are
     * also the section-Y parity check (design 0.3).
     */
    public static DecodedChunk decodeChunk(Nbt.Tag.TCompound tag, int minY, int height,
            long gameTime, ImportCensus census) throws Nbt.NbtException {
        int chunkX = Nbt.getInt(tag, "xPos");
        int chunkZ = Nbt.getInt(tag, "zPos");
        int minSectionY = minY >> 4;
        int sectionCount = height >> 4;
        BlockState air = Blocks.AIR.defaultBlockState();

        Map<Integer, BlockState[]> bySlot = new HashMap<>();
        Nbt.Tag sectionsTag = Nbt.getOrNull(tag, "sections");
        if (sectionsTag instanceof Nbt.Tag.TList list) {
            for (Nbt.Tag t : list.items()) {
                if (!(t instanceof Nbt.Tag.TCompound section)) {
                    continue;
                }
                // World section Y, sign-extended from the byte; vanilla
                // light-only tags sit one outside the build range.
                int worldY = Nbt.getByteOr(section, "Y", (byte) 0);
                if (census != null) {
                    census.tags++;
                }
                if (worldY < minSectionY || worldY > minSectionY + sectionCount - 1) {
                    if (census != null) {
                        census.tagsLightOnly++;
                    }
                    continue;
                }
                int slot = slotY(minSectionY, worldY);
                Nbt.Tag statesTag = Nbt.getOrNull(section, "block_states");
                if (statesTag instanceof Nbt.Tag.TCompound states) {
                    // Step 2b cheap win (g2-memory-design 2.5): skip air-only
                    // palettes before decoding. The palette is one NBT list;
                    // a single air entry means all 4096 cells are air, and an
                    // absent section reads as air everywhere (SectionStore
                    // get/readCell, BlockHash onlyAir). Census still counts
                    // the tag so tag counts stay the parity check.
                    int paletteSize = Nbt.getList(states, "palette").items().size();
                    if (paletteSize == 1
                            && StateNbt.decode((Nbt.Tag.TCompound) Nbt.getList(states,
                                    "palette").items().get(0)).isAir()) {
                        if (census != null) {
                            census.sectionAirOnly(paletteSize);
                        }
                        continue;
                    }
                    BlockState[] cells = SectionCodec.decode(states, air);
                    bySlot.put(slot, cells);
                    if (census != null) {
                        census.section(paletteSize, cells);
                    }
                } else if (census != null) {
                    census.tagsNoStates++;
                }
            }
        }

        List<SlotCells> sections = new ArrayList<>(sectionCount);
        for (int slot = 0; slot < sectionCount; slot++) {
            BlockState[] cells = bySlot.get(slot);
            if (cells == null) {
                continue;
            }
            sections.add(new SlotCells(slot, cells));
        }

        List<WorldSnapshot.QueuedTickData> queue = new ArrayList<>();
        Nbt.Tag ticksTag = Nbt.getOrNull(tag, "block_ticks");
        if (ticksTag instanceof Nbt.Tag.TList list) {
            for (Nbt.Tag t : list.items()) {
                if (!(t instanceof Nbt.Tag.TCompound tick)) {
                    continue;
                }
                String id = tick.entries().get("i") instanceof Nbt.Tag.TString s
                        ? s.value() : "";
                dev.kestrel.loop.KestrelTickTarget.Kind kind = kindOf(id);
                if (kind == dev.kestrel.loop.KestrelTickTarget.Kind.OTHER) {
                    continue;
                }
                Nbt.Tag x = tick.entries().get("x");
                Nbt.Tag y = tick.entries().get("y");
                Nbt.Tag z = tick.entries().get("z");
                Nbt.Tag delay = tick.entries().get("t");
                Nbt.Tag pri = tick.entries().get("p");
                if (!(x instanceof Nbt.Tag.TInt xi) || !(y instanceof Nbt.Tag.TInt yi)
                        || !(z instanceof Nbt.Tag.TInt zi) || !(delay instanceof Nbt.Tag.TInt d)
                        || !(pri instanceof Nbt.Tag.TInt p)) {
                    continue;
                }
                // Vanilla filters rehydrated ticks to the owning chunk
                // (SavedTick.filterTickListForChunk): drop strays here too.
                if ((xi.value() >> 4) != chunkX || (zi.value() >> 4) != chunkZ) {
                    continue;
                }
                queue.add(new WorldSnapshot.QueuedTickData(xi.value(), yi.value(), zi.value(),
                        kind, priorityOf(p.value()), gameTime + d.value()));
            }
        }
        return new DecodedChunk(chunkX, chunkZ, sections, queue,
                readPostProcessing(tag, sectionCount, chunkX, chunkZ));
    }

    /**
     * Import-only decode that preserves the on-disc palette and packed longs
     * instead of allocating a dense 4096-reference array per section.
     */
    public static CompactChunk decodeCompactChunk(Nbt.Tag.TCompound tag, int minY, int height,
            ImportCensus census) throws Nbt.NbtException {
        int chunkX = Nbt.getInt(tag, "xPos");
        int chunkZ = Nbt.getInt(tag, "zPos");
        int minSectionY = minY >> 4;
        int sectionCount = height >> 4;
        List<SlotCompact> sections = new ArrayList<>();
        Nbt.Tag sectionsTag = Nbt.getOrNull(tag, "sections");
        if (sectionsTag instanceof Nbt.Tag.TList list) {
            for (Nbt.Tag entry : list.items()) {
                if (!(entry instanceof Nbt.Tag.TCompound section)) {
                    continue;
                }
                int worldY = Nbt.getByteOr(section, "Y", (byte) 0);
                if (census != null) {
                    census.tags++;
                }
                if (worldY < minSectionY || worldY >= minSectionY + sectionCount) {
                    if (census != null) {
                        census.tagsLightOnly++;
                    }
                    continue;
                }
                Nbt.Tag statesTag = Nbt.getOrNull(section, "block_states");
                if (!(statesTag instanceof Nbt.Tag.TCompound states)) {
                    if (census != null) {
                        census.tagsNoStates++;
                    }
                    continue;
                }
                SectionCodec.CompactSection compact = SectionCodec.decodeCompact(states);
                if (compact.palette().length == 1 && compact.palette()[0].isAir()) {
                    if (census != null) {
                        census.sectionAirOnly(1);
                    }
                    continue;
                }
                if (census != null) {
                    census.compactSection(compact);
                }
                sections.add(new SlotCompact(slotY(minSectionY, worldY), compact));
            }
        }
        return new CompactChunk(chunkX, chunkZ, sections,
                readPostProcessing(tag, sectionCount, chunkX, chunkZ));
    }

    /** One decoded section slot: chunk-local Y slot plus 4096 cells. */
    public record SlotCells(int slot, BlockState[] cells) {
    }

    /**
     * Decoded chunk parts: slot cells keyed later by WorldStore, plus the
     * pending post-processing lists (null when the chunk has none).
     */
    public record DecodedChunk(int chunkX, int chunkZ,
            List<SlotCells> sections,
            List<WorldSnapshot.QueuedTickData> queue,
            short[][] postProcessing) {
    }

    /** One imported compact section keyed by chunk-local Y slot. */
    public record SlotCompact(int slot, SectionCodec.CompactSection section) {
    }

    /** Import-only chunk parts that retain compact palette storage. */
    public record CompactChunk(int chunkX, int chunkZ, List<SlotCompact> sections,
            short[][] postProcessing) {
    }

    /**
     * Store key for a decoded slot: chunk-NBT sections ARE the same 16^3
     * volumes as store sections (section X/Z equal the chunk X/Z, world
     * section Y is minSectionY + slot).
     */
    public static long storeKey(int chunkX, int chunkZ, int minSectionY, int slot) {
        return SectionPos.asLong(chunkX, worldSectionY(minSectionY, slot), chunkZ);
    }

    /** Vanilla block-entity type id for a sleeper type (stub only). */
    static String blockEntityId(SleeperRegistry.Type type) {
        return switch (type) {
            case FURNACE -> "minecraft:furnace";
            case SIGN -> "minecraft:sign";
            case SHULKER -> "minecraft:shulker_box";
            case BREWING_STAND -> "minecraft:brewing_stand";
            case BEEHIVE -> "minecraft:beehive";
            case CAMPFIRE -> "minecraft:campfire";
            case CRAFTER -> "minecraft:crafter";
            case SCULK_CATALYST -> "minecraft:sculk_catalyst";
            case SCULK_SENSOR -> "minecraft:sculk_sensor";
            case SCULK_SHRIEKER -> "minecraft:sculk_shrieker";
        };
    }

    /** Block id for a schedulable tick kind. */
    static String tickTargetId(dev.kestrel.loop.KestrelTickTarget.Kind kind) {
        return switch (kind) {
            case REDSTONE_TORCH -> "minecraft:redstone_torch";
            case REPEATER -> "minecraft:repeater";
            case OTHER -> "minecraft:air";
        };
    }

    /** Tick kind for a saved block id (unknown ids are OTHER). */
    static dev.kestrel.loop.KestrelTickTarget.Kind kindOf(String id) {
        return switch (id) {
            case "minecraft:redstone_torch" -> dev.kestrel.loop.KestrelTickTarget.Kind.REDSTONE_TORCH;
            case "minecraft:repeater" -> dev.kestrel.loop.KestrelTickTarget.Kind.REPEATER;
            default -> dev.kestrel.loop.KestrelTickTarget.Kind.OTHER;
        };
    }

    /** Facade priority for a vanilla int value (clamped like byValue). */
    static dev.kestrel.loop.KestrelTickTarget.Priority priorityOf(int v) {
        return dev.kestrel.loop.KestrelTickTarget.Priority.byValue(v);
    }

    /**
     * MOTION_BLOCKING heightmap: 16x16 packed 9-bit heights (y+1 of the
     * top motion-blocking cell, 0 when the column is empty), index
     * {@code (z<<4)|x}, low index in low bits. A block counts when
     * vanilla's predicate holds: {@code blocksMotion() || fluid non-empty}
     * ({@code Heightmap.java:151}).
     */
    static long[] computeMotionBlocking(WorldSnapshot snap, int chunkX, int chunkZ,
            int minSectionY, int sectionCount) {
        Map<Long, WorldSnapshot.SectionData> byKey = new HashMap<>();
        for (WorldSnapshot.SectionData s : snap.sections) {
            byKey.put(s.sectionKey(), s);
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        int top = snap.minY + snap.height;
        long[] data = new long[36];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int height = 0;
                for (int y = top - 1; y >= snap.minY; y--) {
                    BlockState st = stateAt(byKey, air, chunkX, chunkZ, y, lx, lz);
                    if (st.blocksMotion() || !st.getFluidState().isEmpty()) {
                        height = y + 1 - snap.minY;
                        break;
                    }
                }
                int idx = (lz << 4) | lx;
                int li = idx * 9 / 64;
                int bit = (idx * 9) % 64;
                data[li] |= (long) height << bit;
                if (bit > 55) {
                    data[li + 1] |= (long) height >>> (64 - bit);
                }
            }
        }
        return data;
    }

    private static BlockState stateAt(Map<Long, WorldSnapshot.SectionData> byKey, BlockState air,
            int chunkX, int chunkZ, int y, int lx, int lz) {
        int wx = (chunkX << 4) + lx;
        int wz = (chunkZ << 4) + lz;
        long key = SectionPos.asLong(SectionPos.blockToSectionCoord(wx),
                SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(wz));
        WorldSnapshot.SectionData section = byKey.get(key);
        if (section == null) {
            return air;
        }
        BlockState st = section.cell((y & 15) << 8 | (lz & 15) << 4 | (lx & 15));
        return st != null ? st : air;
    }

    /** Chunk position for a store section key. */
    public static ChunkPos chunkOf(long sectionKey) {
        return new ChunkPos(SectionPos.x(sectionKey) >> 4, SectionPos.z(sectionKey) >> 4);
    }
}
