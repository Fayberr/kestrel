package dev.kestrel.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Kestrel's replacement for vanilla's {@code SecondaryPoiSensor} (analysis
 * target E3).
 *
 * <p><b>The premise in the grounding doc was wrong, and the source says so.</b>
 * E3 was written up as "subscribe to block changes in the 9x5x9 volume instead
 * of polling", to be built on the {@code setBlock} interception hook shared
 * with step 3. Re-reading the real 26.2 sensor closes that design:
 * <ul>
 *   <li>The scan volume is centred on {@code body.blockPosition()}, which moves
 *       every time the villager walks a block. A change-subscribed cache would
 *       have to be invalidated on movement as well as on block change, and a
 *       one-block horizontal step shifts in a 5x9 slab (45 fresh reads) while a
 *       diagonal step shifts in up to 171. Vanilla's poll costs 405 reads per
 *       40 ticks, i.e. ~10 per tick. A walking villager under the "event-driven"
 *       design would pay several times that. The subscription is a pessimisation
 *       for exactly the mobs that matter.</li>
 *   <li>Evaluating lazily at read time instead is a parity break: the memory is
 *       a snapshot taken at the scan tick, and a villager that moved since would
 *       report a volume centred on the wrong block.</li>
 * </ul>
 * So E3 needs no interception hook, and is not blocked on one.
 *
 * <p><b>What the real waste is.</b> The same shape as E1 and E2: the inner loop
 * redoes work that does not depend on the inner loop.
 * <ol>
 *   <li>{@code body.getVillagerData().profession().value().secondaryPoi()} is
 *       re-evaluated on all 405 iterations. It cannot change mid-scan. Hoisted:
 *       405 evaluations to 1.</li>
 *   <li><b>The probe set is empty for every profession but one.</b> Only
 *       {@code FARMER} declares a secondary POI ({@code ImmutableSet.of(FARMLAND)});
 *       the other 14 registered professions, including {@code NONE} (every
 *       unemployed villager) and {@code NITWIT}, declare {@code ImmutableSet.of()}.
 *       {@code SECONDARY_POIS} is nevertheless in {@code Villager}'s sensor set
 *       unconditionally, so those villagers run 405 block reads per 40 ticks to
 *       compute a guaranteed-empty list. An empty set contains nothing, so the
 *       result is {@code eraseMemory} whatever the world holds: the whole loop
 *       is skippable. This is checked at runtime, not hardcoded to FARMER, so a
 *       datapack-registered profession with a secondary POI still scans.</li>
 *   <li>{@code center.offset(x, y, z)} allocates a fresh immutable
 *       {@code BlockPos} per position: 405 allocations per scan, of which all
 *       but the hits are garbage immediately. Kestrel probes with one
 *       {@code MutableBlockPos} and allocates only on a hit.</li>
 *   <li>{@code level.getBlockState(pos)} re-derives {@code isInValidBounds} and
 *       re-enters {@code ServerChunkCache.getChunk} on all 405 positions.
 *       {@code isInValidBounds} factors exactly into
 *       {@code isInsideBuildHeight(y) && ChunkPos.isValid(cx, cz)}, so the
 *       height test is per-y and the validity test plus the chunk fetch are per
 *       chunk column. The 9x9 footprint spans at most four chunks, so the
 *       405 chunk fetches become at most four.</li>
 * </ol>
 *
 * <p><b>Parity.</b> The traversal order, the volume, the probe predicate and
 * the memory write are unchanged; only redundant recomputation is removed.
 * Specifically:
 * <ul>
 *   <li>The innermost read stays {@code LevelChunk.getBlockState}, so the debug
 *       world branch and the crash-report wrapper are preserved verbatim
 *       instead of reimplemented as a raw section read.</li>
 *   <li>Out-of-bounds positions still resolve to {@code VOID_AIR}, exactly what
 *       {@code Level.getBlockState} returns for them, so a probe set that
 *       somehow contained {@code VOID_AIR} would still match there.</li>
 *   <li>The chunk memo never evicts, so the SET and the FIRST-TOUCH ORDER of
 *       distinct chunks fetched during a scan are identical to vanilla's. Only
 *       repeat fetches of an already-fetched chunk are removed.</li>
 *   <li>Hit positions are handed on as freshly allocated immutable
 *       {@code BlockPos} with the same coordinates {@code offset} would have
 *       produced, including int wraparound at the coordinate extremes.</li>
 * </ul>
 *
 * <p>The one difference worth stating out loud rather than burying: when the
 * probe set is empty, Kestrel performs zero {@code getChunk} calls where
 * vanilla performed up to four. That is unobservable in game state because a
 * villager only ticks its brain from an entity-ticking chunk, whose whole 3x3
 * neighbourhood is loaded at FULL status, so those fetches are pure lookups
 * that neither load nor generate. What does change is two diagnostics-only
 * profiler counters ({@code getChunk}, {@code getChunkCacheMiss}), which are
 * not world state and are not hashed by the harness.
 *
 * <p>No RNG is touched: scan cadence and {@code randomlyDelayStart} jitter are
 * the sensor's, and this class never runs unless the sensor already decided to
 * tick.
 */
public final class SecondaryPoiScan {

    /** Vanilla's horizontal search radius ({@code horizontalSearch = 4}). */
    public static final int RADIUS_XZ = 4;
    /** Vanilla's vertical search radius (the {@code y = -2..2} loop). */
    public static final int RADIUS_Y = 2;
    /** Positions vanilla probes per scan. */
    public static final int VOLUME = (2 * RADIUS_XZ + 1) * (2 * RADIUS_Y + 1) * (2 * RADIUS_XZ + 1);

    private static final Block OUT_OF_BOUNDS_BLOCK = Blocks.VOID_AIR;

    private SecondaryPoiScan() {
    }

    /** Supplies the block at a world position, however the caller wants to reach it. */
    @FunctionalInterface
    public interface BlockLookup<B> {
        B blockAt(int x, int y, int z);
    }

    /** Receives matching positions in vanilla's traversal order. */
    @FunctionalInterface
    public interface HitSink {
        void hit(int x, int y, int z);
    }

    /**
     * Walks vanilla's 9x5x9 volume in vanilla's order and reports matches.
     *
     * <p>Generic on purpose, same reasoning as {@link PlayerScan}: the identity
     * being proven is traversal order plus the probe predicate, so the proof
     * can drive it with a controlled oracle instead of a live level, and can
     * count the lookups it actually receives.
     *
     * @return whether any position matched (vanilla's {@code !jobSites.isEmpty()})
     */
    public static <B> boolean scan(Set<?> probeSet,
                                   int centerX, int centerY, int centerZ,
                                   BlockLookup<B> lookup,
                                   HitSink sink) {
        if (probeSet.isEmpty()) {
            // No block can be contained in an empty set, so vanilla's loop is a
            // guaranteed no-op that ends in eraseMemory. Skip all 405 reads.
            skippedScans++;
            return false;
        }
        scannedVolumes++;
        boolean any = false;
        for (int dx = -RADIUS_XZ; dx <= RADIUS_XZ; dx++) {
            int x = centerX + dx;
            for (int dy = -RADIUS_Y; dy <= RADIUS_Y; dy++) {
                int y = centerY + dy;
                for (int dz = -RADIUS_XZ; dz <= RADIUS_XZ; dz++) {
                    int z = centerZ + dz;
                    if (probeSet.contains(lookup.blockAt(x, y, z))) {
                        sink.hit(x, y, z);
                        any = true;
                    }
                }
            }
        }
        return any;
    }

    /**
     * Full replacement for {@code SecondaryPoiSensor.doTick}, writing the same
     * memory value the real sensor would have written.
     */
    public static void doTick(ServerLevel level, Villager body) {
        Set<Block> probeSet = body.getVillagerData().profession().value().secondaryPoi();
        Brain<?> brain = body.getBrain();
        if (probeSet.isEmpty()) {
            skippedScans++;
            brain.eraseMemory(MemoryModuleType.SECONDARY_JOB_SITE);
            return;
        }

        ResourceKey<Level> dimension = level.dimension();
        BlockPos center = body.blockPosition();
        List<GlobalPos> jobSites = new ArrayList<>();
        boolean any = scan(probeSet, center.getX(), center.getY(), center.getZ(),
                new LevelProbe(level),
                (x, y, z) -> jobSites.add(GlobalPos.of(dimension, new BlockPos(x, y, z))));

        if (any) {
            brain.setMemory(MemoryModuleType.SECONDARY_JOB_SITE, jobSites);
        } else {
            brain.eraseMemory(MemoryModuleType.SECONDARY_JOB_SITE);
        }
    }

    /**
     * {@code Level.getBlockState} with its loop-invariant parts hoisted.
     *
     * <p>{@code Level.isInValidBounds(pos)} is exactly
     * {@code isInsideBuildHeight(pos.getY()) && ChunkPos.isValid(cx, cz)}, and
     * neither half depends on the whole position, so the height test is
     * memoized on y and the chunk (with its validity test) on the chunk column.
     * The column cache holds four entries and never evicts, because a 9-wide
     * footprint spans at most two chunks per axis.
     */
    private static final class LevelProbe implements BlockLookup<Block> {
        private final ServerLevel level;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private final long[] columnKeys = new long[4];
        private final LevelChunk[] columns = new LevelChunk[4];
        private int columnCount;
        private int cachedY = Integer.MIN_VALUE;
        private boolean cachedYInside;

        LevelProbe(ServerLevel level) {
            this.level = level;
        }

        @Override
        public Block blockAt(int x, int y, int z) {
            if (y != this.cachedY) {
                this.cachedY = y;
                this.cachedYInside = this.level.isInsideBuildHeight(y);
            }
            if (!this.cachedYInside) {
                return OUT_OF_BOUNDS_BLOCK;
            }
            int chunkX = SectionPos.blockToSectionCoord(x);
            int chunkZ = SectionPos.blockToSectionCoord(z);
            long key = ChunkPos.pack(chunkX, chunkZ);
            LevelChunk chunk = null;
            for (int i = 0; i < this.columnCount; i++) {
                if (this.columnKeys[i] == key) {
                    chunk = this.columns[i];
                    break;
                }
            }
            if (chunk == null) {
                if (!ChunkPos.isValid(chunkX, chunkZ)) {
                    return OUT_OF_BOUNDS_BLOCK;
                }
                chunk = this.level.getChunk(chunkX, chunkZ);
                if (this.columnCount < this.columns.length) {
                    this.columnKeys[this.columnCount] = key;
                    this.columns[this.columnCount] = chunk;
                    this.columnCount++;
                }
            }
            this.cursor.set(x, y, z);
            return chunk.getBlockState(this.cursor).getBlock();
        }
    }

    // ---- counters, for the proof and the bench only ----

    private static long skippedScans;
    private static long scannedVolumes;

    /** Scans short-circuited because the profession declares no secondary POI. */
    public static long skippedScans() {
        return skippedScans;
    }

    /** Scans that actually walked the 9x5x9 volume. */
    public static long scannedVolumes() {
        return scannedVolumes;
    }

    public static void resetCounters() {
        skippedScans = 0L;
        scannedVolumes = 0L;
    }
}
