package dev.kestrel.loop;

import it.unimi.dsi.fastutil.shorts.ShortList;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * When a live chunk "becomes ticking", and so runs its post-processing
 * ({@link PostProcess}). Vanilla does it in {@code ChunkMap.prepareTickingChunk}
 * ({@code ChunkMap.java:684-700}), which waits for the chunk's 3x3 at FULL
 * ({@code getChunkRangeFuture(chunk, 1, FULL)}), runs
 * {@code LevelChunk.postProcessGeneration}, and only then marks the chunk
 * ready to send to players.
 *
 * <h2>The mapping onto Kestrel</h2>
 *
 * FULL follows LIGHT, and LIGHT needs INITIALIZE_LIGHT in radius 1
 * ({@code ChunkPyramid}), so a chunk's 3x3 at FULL means every chunk within two
 * of it ran FEATURES. In Kestrel a chunk's LIGHT is published exactly when the
 * 3x3 around it is generated ({@code LiveGenerator.snapshotNewlyReadyLightWire}),
 * so "the 3x3 around C has published LIGHT in this session" is the same
 * condition, and it is the trigger here: {@link #lightPublished} is called
 * with every chunk whose LIGHT payload the sender publishes, after that
 * publication's blocks are in the live store, and each chunk whose 3x3 is now
 * complete activates. At that point no later generation can write into or
 * read from the chunk's 3x3 (every writer is within one chunk of its target
 * and every such writer has run), so the pass sees final generated terrain.
 * The lists come from generation ({@link #publishMarks}) or from disc.
 *
 * <p>Brightness for the pass ({@code MushroomBlock.canSurvive}) comes from
 * the generator's light engine, the one that produced the published LIGHT
 * payloads; the pass's writes go back to that engine through
 * {@link LightOwner#applyWrites}, which is how {@code LevelChunk.setBlockState}
 * would queue its light checks.
 *
 * <h2>Where Kestrel cannot express vanilla's trigger exactly</h2>
 *
 * These are debts for the chunk-lifecycle work (S16):
 * <ul>
 *   <li><b>Tickets.</b> Vanilla activates only chunks at BLOCK_TICKING level:
 *       every chunk within a player's view distance plus one (the
 *       PLAYER_LOADING ticket is at ENTITY_TICKING), the spawn area's START
 *       ticket and forced chunks. Kestrel has no tickets and activates every
 *       chunk whose 3x3 is complete, so a chunk generated only as a
 *       dependency, beyond any player's view, can activate here and would not
 *       in vanilla until a player came near.</li>
 *   <li><b>Send order.</b> Vanilla never sends a chunk before its pass. Kestrel
 *       sends a chunk once it is generated (sync) or lit (async), which can be
 *       before its 3x3 is lit, so a client may receive the pre-pass chunk and
 *       then the pass's changes as block updates plus a light resend.</li>
 *   <li><b>Outer rings.</b> Vanilla generates enough around a player to make
 *       every chunk within view distance plus one ticking. Kestrel generates
 *       only what it streams, so chunks at the edge of what it generated stay
 *       unactivated until more is generated around them.</li>
 *   <li><b>Loaded chunks.</b> A chunk loaded from disc with pending lists
 *       activates only once this session's engine has lit its whole 3x3,
 *       because the engine does not hold light it did not compute; vanilla
 *       loads the saved light into its engine instead. Until then its marks
 *       stay pending (and persisted).</li>
 *   <li><b>Order within one publication.</b> When one publication completes
 *       several 3x3s, they activate in publication order, each scanning its
 *       neighbours dx then dz. Vanilla runs whichever future completes first.</li>
 *   <li><b>Worldgen scheduled ticks.</b> Vanilla's chunk carries the ticks
 *       generation scheduled into the level's tick lists when it loads, and the
 *       pass's own schedules then keep only the first per position. Kestrel
 *       does not carry generation ticks into the live loop at all, so the
 *       pass's schedules are the only ones.</li>
 * </ul>
 *
 * <p>Thread-safety: tick thread only.
 */
public final class ChunkActivation {

    /** The light engine that lit the live chunks, and the way back into it. */
    public interface LightOwner {
        /** The engine to read brightness from (its visible storage). */
        LevelLightEngine engine();

        /**
         * Queue the light consequences of the pass's writes, in commit order,
         * the way {@code LevelChunk.setBlockState} does for each write.
         */
        void applyWrites(List<PostProcess.Write> writes);
    }

    private final TickLoop loop;
    private LightOwner lightOwner;
    /** Chunks whose LIGHT this session's engine computed and the sender published. */
    private final Set<Long> lit = new HashSet<>();
    /** Chunks already activated in this session. */
    private final Set<Long> activated = new HashSet<>();
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    /** Every activated chunk key, in activation order. */
    private final List<Long> activationOrder = new java.util.ArrayList<>();
    private boolean draining;
    private long activations;
    private long passes;
    private long writes;

    public ChunkActivation(TickLoop loop) {
        this.loop = loop;
    }

    public void setLightOwner(LightOwner lightOwner) {
        this.lightOwner = lightOwner;
    }

    /**
     * Keep generation's marks for chunks that have not activated yet. A chunk
     * that already ran its pass keeps nothing: its marks were consumed.
     */
    public void publishMarks(Map<Long, ShortList[]> marks) {
        for (Map.Entry<Long, ShortList[]> entry : marks.entrySet()) {
            if (!activated.contains(entry.getKey())) {
                loop.level().putPostProcessing(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Record that these chunks' LIGHT payloads were published (their blocks
     * are already live), then activate every chunk whose 3x3 is now lit.
     * Re-entrant calls from the pass's own light refresh only queue.
     */
    public void lightPublished(Collection<Long> chunkKeys) {
        for (long key : chunkKeys) {
            if (lit.add(key)) {
                pending.add(key);
            }
        }
        if (draining || lightOwner == null || lightOwner.engine() == null) {
            return;
        }
        draining = true;
        try {
            while (!pending.isEmpty()) {
                long key = pending.poll();
                int x = ChunkPos.getX(key);
                int z = ChunkPos.getZ(key);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        tryActivate(x + dx, z + dz);
                    }
                }
            }
        } finally {
            draining = false;
        }
    }

    /** True when every chunk of the 3x3 around (cx, cz) is lit in this session. */
    boolean ready(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!lit.contains(ChunkPos.pack(cx + dx, cz + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private void tryActivate(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        if (activated.contains(key) || !ready(cx, cz)) {
            return;
        }
        activated.add(key);
        activationOrder.add(key);
        activations++;
        if (loop.level().postProcessing(key) == null) {
            return;
        }
        passes++;
        List<PostProcess.Write> committed = PostProcess.run(loop, cx, cz, lightOwner.engine(), false);
        writes += committed.size();
        if (!committed.isEmpty()) {
            lightOwner.applyWrites(committed);
        }
    }

    /** Activated chunk keys ({@code ChunkPos.pack}) in the order they activated. */
    public List<Long> activationOrder() {
        return List.copyOf(activationOrder);
    }

    /** The engine that lit the activated chunks (G30's light reads), or null before one is attached. */
    public LevelLightEngine lightEngine() {
        return lightOwner == null ? null : lightOwner.engine();
    }

    public boolean isActivated(int cx, int cz) {
        return activated.contains(ChunkPos.pack(cx, cz));
    }

    public String summary() {
        return "activated=" + activations + " passes=" + passes + " writes=" + writes;
    }
}
