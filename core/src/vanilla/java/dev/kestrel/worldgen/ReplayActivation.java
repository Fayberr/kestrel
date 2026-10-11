package dev.kestrel.worldgen;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.PostProcess;
import dev.kestrel.loop.TickLoop;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunk activation inside the light replay: the trace records every light
 * operation vanilla ran, including the checks its post-processing pass queued
 * when chunks became ticking, but not the block changes behind them. This
 * reproduces those changes by running Kestrel's live pass ({@link PostProcess})
 * at the point vanilla's pass could first run.
 *
 * <p>When: {@code ChunkMap.prepareTickingChunk} needs the chunk's 3x3 at FULL
 * ({@code getChunkRangeFuture(chunk, 1, FULL)}), and FULL follows LIGHT. The
 * trace records each chunk's LIGHT stage as a LIGHT operation inside a light
 * executor batch, so a chunk may activate once the batch holding the last of
 * its nine LIGHT operations has drained. That is the earliest point the trace
 * allows. The trace records no tickets, so which of those chunks became
 * ticking is not in it; every chunk whose 3x3 reached LIGHT is activated. The
 * capture's saved world is consistent with that: every interior FULL chunk has
 * an empty PostProcessing list and every edge FULL chunk still has its marks.
 * When several chunks become ready after the same batch, they activate in the
 * order their completing LIGHT operations appear, each scanning its 3x3 in
 * dx-then-dz order; vanilla's own order there is whatever its futures complete
 * in, which the trace does not record.
 *
 * <p>No scheduled tick runs after a pass: the capture's saved chunks all carry
 * {@code LastUpdate} 1, so its game time never passed 1, while every tick the
 * pass schedules is at least one game tick out (water 5, falling blocks 2).
 *
 * <p>How: the chunk's 3x3 is copied out of the grid into a scratch store, the
 * chunk's own marks are copied out of its ProtoChunk, and the pass runs on a
 * scratch {@link TickLoop} reading brightness from the replay's light engine.
 * Every write it commits is then applied, in commit order, to the grid
 * ProtoChunk that owns the position, through {@code ProtoChunk.setBlockState},
 * which updates the chunk's sky-light sources and section status the way
 * {@code LevelChunk.setBlockState} does. The light checks those writes would
 * queue are not issued here: in replay mode the engine takes checks only from
 * the recorded operations, which already contain them.
 */
final class ReplayActivation {
    private final HeadlessNoise.Driver driver;
    private final Map<Long, ChunkAccess> grid;
    private final WorldgenLightLifecycle lighting;
    private final KestrelLevel.Dimension dimension;
    private final Set<Long> lit = new HashSet<>();
    private final Set<Long> activated = new LinkedHashSet<>();
    /**
     * Each written cell's state before the first activation write reached it,
     * that is, its generation output. See {@link #generationState}.
     */
    private final Map<Long, net.minecraft.world.level.block.state.BlockState> preActivation =
            new java.util.HashMap<>();
    private long writes;
    private long markedPositions;

    ReplayActivation(HeadlessNoise.Driver driver, Map<Long, ChunkAccess> grid,
            WorldgenLightLifecycle lighting) {
        this.driver = driver;
        this.grid = grid;
        this.lighting = lighting;
        this.dimension = switch (driver.dimension()) {
            case OVERWORLD -> KestrelLevel.Dimension.OVERWORLD;
            case NETHER -> KestrelLevel.Dimension.NETHER;
            case END -> KestrelLevel.Dimension.END;
        };
    }

    /** Called after a light batch drained, with the chunks it ran LIGHT for, in operation order. */
    void afterBatch(List<Long> newlyLit) {
        for (long key : newlyLit) {
            lit.add(key);
        }
        for (long key : newlyLit) {
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = x + dx;
                    int cz = z + dz;
                    if (ready(cx, cz)) {
                        activate(cx, cz);
                    }
                }
            }
        }
    }

    private boolean ready(int cx, int cz) {
        if (activated.contains(ChunkPos.pack(cx, cz))) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = ChunkPos.pack(cx + dx, cz + dz);
                if (!lit.contains(key) || !(grid.get(key) instanceof ProtoChunk)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void activate(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        activated.add(key);
        ProtoChunk center = (ProtoChunk) grid.get(key);
        ShortList[] marks = center.getPostProcessing();
        boolean any = false;
        for (ShortList list : marks) {
            if (list != null && !list.isEmpty()) {
                markedPositions += list.size();
                any = true;
            }
        }
        if (!any) {
            return;
        }
        KestrelLevel level = new KestrelLevel(dimension,
                driver.height().getMinY(), driver.height().getHeight());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                HeadlessNoise.writeStoreCompact(driver, grid.get(ChunkPos.pack(cx + dx, cz + dz)),
                        level.store(), cx + dx, cz + dz);
            }
        }
        ShortList[] copy = new ShortList[marks.length];
        for (int i = 0; i < marks.length; i++) {
            copy[i] = marks[i] == null ? null : new ShortArrayList(marks[i]);
        }
        level.putPostProcessing(key, copy);
        TickLoop loop = new TickLoop(level);
        List<PostProcess.Write> committed = PostProcess.run(loop, cx, cz, lighting.engine(), true);
        for (PostProcess.Write w : committed) {
            ChunkAccess owner = grid.get(ChunkPos.pack(w.pos().getX() >> 4, w.pos().getZ() >> 4));
            if (!(owner instanceof ProtoChunk proto)) {
                throw new IllegalStateException("post-processing wrote outside the replay grid at "
                        + w.pos().toShortString());
            }
            preActivation.putIfAbsent(w.pos().asLong(), proto.getBlockState(w.pos()));
            proto.setBlockState(w.pos(), w.newState(), PostProcess.FLAGS);
        }
        writes += committed.size();
        // LevelChunk.postProcessGeneration clears each section list it walked.
        for (ShortList list : marks) {
            if (list != null) {
                list.clear();
            }
        }
        if (System.getProperty("kestrel.postProcessTrace") != null) {
            for (PostProcess.Write w : committed) {
                System.out.println("POSTPROCESS_WRITE chunk=" + cx + "," + cz
                        + " pos=" + w.pos().toShortString()
                        + " old=" + w.oldState() + " new=" + w.newState());
            }
        }
    }

    /**
     * The state a cell had at the end of generation, before any activation
     * wrote it: the lifecycle point the trace's block snapshots were taken at
     * (they are recorded during FEATURES, before any chunk becomes ticking).
     * Tier R compares generation output against those snapshots, so it reads
     * this for the cells a pass changed; every other cell is unchanged since
     * generation anyway.
     */
    net.minecraft.world.level.block.state.BlockState generationState(
            net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState current) {
        return preActivation.getOrDefault(pos.asLong(), current);
    }

    String summary() {
        return "POSTPROCESS_REPLAY activated=" + activated.size()
                + " markedPositions=" + markedPositions + " writes=" + writes;
    }
}
