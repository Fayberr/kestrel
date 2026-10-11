package dev.kestrel.net;

import dev.kestrel.loop.SectionStore;
import dev.kestrel.loop.TickLoop;
import dev.kestrel.worldgen.AsyncChunkGenerator;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 3 chunk streaming: per-tick, per-player cache-center plus
 * batch send of persisted sections around the player.
 *
 * <p>Shape follows vanilla's {@code PlayerChunkSender.sendNextChunks}
 * ({@code PlayerChunkSender.java:50-74}): batch start, N chunk
 * packets, batch finished. Kestrel sends up to 4 chunks per player per
 * tick in expanding-square order (center first), which keeps the join
 * burst small while converging quickly. The client acks batches
 * ({@code onChunkBatchReceivedByClient}, {@code :114-126}); Kestrel
 * ignores the acks for flow control (tick-driven anyway) but must
 * still accept the packet id or the client stream stalls server-side.
 *
 * <p>Section bytes come from the live store (same cells the tick loop
 * ticks over), encoded by {@link Outbound#sectionBytes} with
 * nonEmpty/fluid counts walked honestly. Unlived slots send all-air
 * sections (vanilla would skip them on disc, but on the wire every
 * slot in range is present). Light is FULL BRIGHT (sky mask set,
 * 0xFF layers) so unlit custom chunks render lit. Biomes claim
 * plains (id resolved once at construction).
 *
 * <p>Thread-safety: tick thread only. Called from
 * {@link NetServer#tick}.
 */
public final class ChunkSender {

    /** Chunks per player per tick (keeps the join burst small). */
    public static final int CHUNKS_PER_TICK = 4;

    /**
     * Final live-route gate only: generated chunks must never fall back to
     * synthetic plains biomes or full-bright lighting on the network path.
     * Production behavior is unchanged unless this diagnostic property is set.
     */
    private static final boolean FINAL_GATE_STRICT_METADATA =
            Boolean.getBoolean("kestrel.finalGateStrictMetadata");

    /**
     * Per-tick on-demand generation budget, shared by every player
     * (preresearch/join-latency-path-analysis.md fixes 1 and 4). Generation
     * runs inline on the tick thread (~231ms mean per fresh chunk on the
     * Pi), so once this much generation time has been spent in the current
     * tick no NEW fresh chunk starts; the stream resumes next tick. The
     * first fresh chunk of a tick always starts, so throughput never drops
     * below one fresh chunk per tick. Cached chunks are never throttled.
     *
     * <p>This is the default; {@link #setGenBudgetNanos} overrides it per
     * instance (wired to {@code --gen-budget-ms} in {@code KestrelServer},
     * dev/test convenience only). Raising it does not make a single
     * chunk generate any faster (it is still one blocking ~231ms call on
     * the tick thread); it only allows more than one such call per tick,
     * trading bigger per-tick stalls for faster convergence while
     * exploring fresh terrain. Do not raise it on a server anyone but
     * the tester is connected to.
     */
    static final long DEFAULT_GEN_BUDGET_NANOS = 40_000_000L;

    /**
     * Join-burst forced radius: the 3x3 around the spawn chunk. Legacy mode
     * generates it synchronously as before; async mode queues the same centers
     * and delays stage 1 until they have been published. The rest of the 5x5
     * is sent only if already generated, otherwise {@link #streamAround}
     * picks it up later.
     */
    static final int BURST_FORCED_RADIUS = 1;

    /**
     * Cap on the deferred-backfill queue ({@link #drainBackfill}). A fast
     * flight across fresh terrain abandons roughly one trailing edge
     * (2*radius+1 chunks) per chunk step, so this fills in a few dozen
     * chunk steps; beyond that the oldest holes are simply left to be
     * regenerated on demand if the player ever returns. Bounding this is
     * the point: an unbounded queue would turn one long creative flight
     * into hours of background generation the player never asked for.
     */
    static final int MAX_BACKFILL = 2048;

    /**
     * Minimum ticks between two backfill generations while at least one
     * player is online. Generation is inline on the tick thread (~231ms
     * per fresh chunk on the Pi, i.e. ~4.6 ticks), so backfilling costs a
     * visible hitch every time it runs. Once per second, and only on ticks
     * where the live view needed no generation at all, keeps the hitch
     * rate strictly below what normal live streaming already imposes while
     * still draining a full queue in well under an hour.
     */
    static final int BACKFILL_INTERVAL_TICKS = 20;

    /**
     * Minimum ticks between backfill generations with nobody online: there
     * is no one to rubber-band, so drain as fast as the tick loop allows.
     */
    static final int BACKFILL_IDLE_INTERVAL_TICKS = 1;

    private final TickLoop loop;
    private final int plainsBiomeId;
    private final BlockState air = Blocks.AIR.defaultBlockState();

    /**
     * G2 live wiring (preresearch/g2-livewiring-design.md): when set, a
     * chunk not yet in the store generates headlessly (instead of serving
     * empty/flat sections) the first time this sender needs it. Null for
     * boots that serve a persisted store only (loaded/imported worlds),
     * so those are never regenerated.
     */
    private dev.kestrel.worldgen.LiveGenerator generator;

    /**
     * Issue #160 prototype: when set, chunks that still need generation
     * are handed to this background pipeline instead of generating
     * inline, and {@link #publishAsyncResults()} must be called once per
     * tick to copy finished work into the live store. Null (default)
     * preserves the original synchronous, budget-gated behavior exactly.
     */
    private AsyncChunkGenerator asyncGenerator;

    /**
     * Chunk activation (post-processing when a chunk's 3x3 is lit), fed by
     * every generation and light publication below. Null: nothing activates.
     */
    private dev.kestrel.loop.ChunkActivation activation;

    /** Sent chunks per player (cleared on cache-center move). */
    private final Map<UUID, Set<Long>> sent = new HashMap<>();
    /** Last cache center per player (chunk key packed). */
    private final Map<UUID, Long> centers = new HashMap<>();
    /** Per-chunk write generation/version. */
    private final Map<Long, Long> chunkWrites = new HashMap<>();
    /** Last sent write version per player and chunk. */
    private final Map<UUID, Map<Long, Long>> sentWrites = new HashMap<>();
    /** Players whose current stream has been logged as started / complete. */
    private final Set<UUID> loggedStart = new HashSet<>();
    private final Set<UUID> loggedDone = new HashSet<>();
    /** Game tick the generation budget below belongs to. */
    private long budgetTick = Long.MIN_VALUE;
    /** Nanoseconds spent in on-demand generation during {@link #budgetTick}. */
    private long genNanosThisTick;
    /** Last tick a budget-exhaustion break was logged (throttle: once/sec-ish). */
    private long lastBudgetLogTick = Long.MIN_VALUE;
    /** Chunk keys already reported by the abandoned-ungenerated-chunk diagnostic
     *  (unbounded but one entry per distinct chunk coord ever abandoned; a
     *  world only has so many, and this is diagnostic-only, not shipped hot). */
    private final Set<Long> abandonedLogged = new HashSet<>();
    /**
     * Island-hole fix: chunks that fell out of a player's view radius
     * before they ever finished generating. {@link #spiral} only ever
     * walks the CURRENT center's radius, so without this queue nothing
     * would ever ask for these chunks again and they stay permanently
     * void (real missing collision data, not a client artifact).
     * Insertion-ordered so the drain is FIFO (oldest hole first), and a
     * Set so a coordinate abandoned twice is only queued once. Tick
     * thread only, like every other field here.
     */
    private final java.util.LinkedHashSet<Long> backfill = new java.util.LinkedHashSet<>();
    /** Last tick {@link #drainBackfill} actually generated a chunk. */
    private long lastBackfillTick = Long.MIN_VALUE;
    /** Last tick the "queue full" drop was logged (throttle: once per tick). */
    private long lastBackfillFullLogTick = Long.MIN_VALUE;
    /** Per-instance override of {@link #DEFAULT_GEN_BUDGET_NANOS}; see {@link #setGenBudgetNanos}. */
    private long genBudgetNanos = DEFAULT_GEN_BUDGET_NANOS;

    public ChunkSender(TickLoop loop, int plainsBiomeId) {
        this.loop = loop;
        this.plainsBiomeId = plainsBiomeId;
    }

    /**
     * Attach the on-demand generator (or clear it with null). Call before
     * the first {@link #streamAround}/{@link #streamBurst}; this is a
     * plain setter, not synchronized, matching the tick-thread-only
     * contract the rest of this class already has.
     */
    public void setGenerator(dev.kestrel.worldgen.LiveGenerator generator) {
        this.generator = generator;
    }

    /**
     * Override the per-tick on-demand generation budget (see
     * {@link #DEFAULT_GEN_BUDGET_NANOS}). Dev/test convenience only, wired
     * to {@code --gen-budget-ms}: a solo tester flying across fresh terrain
     * can raise this to converge faster at the cost of a visible per-tick
     * stall for every extra fresh chunk it allows that tick. Same
     * tick-thread-only, unsynchronized contract as {@link #setGenerator}.
     */
    public void setGenBudgetNanos(long nanos) {
        this.genBudgetNanos = nanos;
    }

    /**
     * Attach chunk activation. Its pass reads brightness from, and returns its
     * light work to, whichever generator owns the light engine: the async
     * worker when one is attached (work is queued to it), otherwise the
     * synchronous generator on this thread.
     */
    public void setActivation(dev.kestrel.loop.ChunkActivation activation) {
        this.activation = activation;
        activation.setLightOwner(new dev.kestrel.loop.ChunkActivation.LightOwner() {
            @Override
            public net.minecraft.world.level.lighting.LevelLightEngine engine() {
                if (asyncGenerator != null) {
                    return asyncGenerator.activationLightEngine();
                }
                return generator == null ? null : generator.activationLightEngine();
            }

            @Override
            public void applyWrites(List<dev.kestrel.loop.PostProcess.Write> writes) {
                if (asyncGenerator != null) {
                    asyncGenerator.submitActivationWrites(writes);
                } else if (generator != null) {
                    publishLightMetadata(generator.applyActivationWrites(writes));
                }
            }
        });
    }

    /**
     * Issue #160 prototype opt-in (off by default): attach a background
     * generation pipeline. Once set, {@link #streamAround} stops
     * generating fresh chunks inline and instead requests them from
     * {@code generator}, and the caller (see {@code NetServer#tick})
     * must call {@link #publishAsyncResults()} once per tick or finished
     * work is never copied into the live store. Pass null to go back to
     * the original synchronous path. Same tick-thread-only,
     * unsynchronized contract as {@link #setGenerator}.
     */
    public void setAsyncGenerator(AsyncChunkGenerator generator) {
        this.asyncGenerator = generator;
    }

    /** Current bounded async admission depth; final-gate telemetry only. */
    public int asyncQueueDepth() {
        return asyncGenerator == null ? 0 : asyncGenerator.queueDepth();
    }

    /** Stop and detach the background generator, waiting for its current job. */
    public void closeAsyncGenerator() {
        if (asyncGenerator != null) {
            asyncGenerator.close();
            asyncGenerator = null;
        }
    }

    /**
     * Record a write for a chunk key and return its new revision. This map
     * is the single revision authority for the client image of a chunk
     * (H3): generation, light publication and every committed simulation
     * write (via {@link NetServer}'s drain of the TickLoop journal) bump it
     * here, and nothing else writes it, so a revision only ever grows.
     */
    public long markDirty(long key) {
        return chunkWrites.merge(key, 1L, Long::sum);
    }

    /** True when {@code profile}'s client was sent {@code key} and holds it now. */
    boolean holdsChunk(UUID profile, long key) {
        Set<Long> done = sent.get(profile);
        return done != null && done.contains(key);
    }

    /**
     * Record that {@code profile}'s held copy of {@code key} was brought to
     * {@code revision} by block-update deltas, so the send-time backstop
     * keeps skipping it and the void-fall stale-copy diagnostic stays exact.
     */
    void markDelivered(UUID profile, long key, long revision) {
        sentWrites.computeIfAbsent(profile, k -> new HashMap<>()).put(key, revision);
    }

    /** Diagnostic (same-package checks): current write version for a chunk key. */
    long writeVersion(long key) {
        return chunkWrites.getOrDefault(key, 0L);
    }

    /**
     * Diagnostic (same-package checks, {@link NetServer}'s void-fall
     * detector): the write version of {@code key} that was last actually
     * sent on the wire to {@code profile}, or {@code -1} if this profile
     * has never been sent this chunk at all. Comparing this against
     * {@link #writeVersion} tells you whether a specific player's client
     * copy of a chunk is stale (chunk generated/rewritten since, but the
     * resend never happened for this profile) versus current.
     */
    long sentVersion(UUID profile, long key) {
        Map<Long, Long> versions = sentWrites.get(profile);
        if (versions == null) {
            return -1L;
        }
        return versions.getOrDefault(key, -1L);
    }

    /** Chunk revision (write version) tracking mutations to this chunk column. */
    public long chunkRevision(long key) {
        return writeVersion(key);
    }

    /**
     * Fully reclaim stream and tracking state for a departed player profile
     * (called on player disconnect).
     */
    public void prunePlayer(UUID profile) {
        if (profile == null) {
            return;
        }
        loggedStart.remove(profile);
        loggedDone.remove(profile);
        sent.remove(profile);
        centers.remove(profile);
        sentWrites.remove(profile);
    }

    /** Reclaim stream state for all profiles no longer active in the player map. */
    public void pruneDeparted(java.util.Set<UUID> activeProfiles) {
        if (activeProfiles == null) {
            return;
        }
        sent.keySet().removeIf(id -> !activeProfiles.contains(id));
        centers.keySet().removeIf(id -> !activeProfiles.contains(id));
        sentWrites.keySet().removeIf(id -> !activeProfiles.contains(id));
        loggedStart.removeIf(id -> !activeProfiles.contains(id));
        loggedDone.removeIf(id -> !activeProfiles.contains(id));
    }

    /** Alias for {@link #prunePlayer(UUID)}. */
    public void removeProfile(UUID profile) {
        prunePlayer(profile);
    }

    /** Forget a player's stream state (on disconnect). Delegates to {@link #prunePlayer(UUID)}. */
    public void forget(UUID profile) {
        prunePlayer(profile);
    }

    /** Diagnostic (same-package checks): true if any profile stream state is retained. */
    boolean hasProfileState(UUID profile) {
        return sent.containsKey(profile)
                || centers.containsKey(profile)
                || sentWrites.containsKey(profile)
                || loggedStart.contains(profile)
                || loggedDone.contains(profile);
    }

    /** Forget specific chunks so the next streamAround resends them. */
    public void forgetChunks(UUID profile, java.util.Set<Long> keys) {
        Set<Long> done = sent.get(profile);
        if (done != null) {
            done.removeAll(keys);
        }
    }

    /**
     * Stream around one player: cache-center on move, then up to
     * CHUNKS_PER_TICK unsent chunks in expanding-square order.
     */
    public void streamAround(KestrelPlayer player, NetServer.Session session) {
        int pcx = player.chunkX();
        int pcz = player.chunkZ();
        long center = (((long) pcx) << 32) | (pcz & 0xFFFFFFFFL);
        Set<Long> done = sent.computeIfAbsent(player.profile, k -> new HashSet<>());
        if (!Long.valueOf(center).equals(centers.get(player.profile))) {
            // Center move: keep everything already sent, drop only what
            // fell out of the new view (radius-16 walk re-streamed the
            // whole 1089 every chunk step otherwise: 272 full-pipe ticks
            // per move at 4/tick, unbounded queue growth, wedged
            // connection at the live edge 2026-09-14). Chunks entering
            // the view stream as new; chunks staying in view keep their
            // markers AND their write versions, so the send-time
            // backstop still skips them when unchanged.
            Long oldCenter = centers.get(player.profile);
            centers.put(player.profile, center);
            if (oldCenter == null) {
                done.clear();
                sentWrites.remove(player.profile);
            } else {
                int oldCx = (int) (oldCenter >> 32);
                int oldCz = (int) (long) oldCenter;
                int r = player.viewDistance;
                done.removeIf(key -> {
                    int kx = (int) (key >> 32);
                    int kz = (int) (long) key;
                    return Math.max(Math.abs(kx - pcx), Math.abs(kz - pcz)) > r;
                });
                // Diagnostic (island-hole hypothesis): a chunk that was
                // inside the OLD view radius but never finished its own
                // generation pass (isChunkGenerated false) before the
                // player's cache center moved past it will now fall
                // outside the new radius and never be revisited by
                // spiral() (which only walks the CURRENT center's radius).
                // That leaves a permanent hole, not just a late send.
                // Throttled: only worth knowing this happens, not every
                // occurrence.
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        int kx = oldCx + dx;
                        int kz = oldCz + dz;
                        if (Math.max(Math.abs(kx - pcx), Math.abs(kz - pcz)) <= r) {
                            continue;
                        }
                        boolean generated = loop.store().isChunkGenerated(
                                net.minecraft.world.level.ChunkPos.pack(kx, kz));
                        if (!generated) {
                            if (abandonedLogged.add(pack(kx, kz))) {
                                System.out.println("[kestrel] chunk abandoned ungenerated: chunk=" + kx + "," + kz
                                        + " player=" + player.name + " oldCenter=" + oldCx + "," + oldCz
                                        + " newCenter=" + pcx + "," + pcz + " tick=" + loop.gameTime());
                            }
                            // The fix, not just the diagnostic: remember it so
                            // drainBackfill finishes it later, out of view.
                            enqueueBackfill(kx, kz);
                        }
                    }
                }
                // Prune the write-version map the same way (entries for
                // out-of-view chunks only); keep the rest so the
                // backstop keeps skipping unchanged chunks.
                Map<Long, Long> versions =
                        sentWrites.get(player.profile);
                if (versions != null) {
                    versions.keySet().removeIf(key -> {
                        int kx = (int) (key >> 32);
                        int kz = (int) (long) key;
                        return Math.max(Math.abs(kx - pcx), Math.abs(kz - pcz)) > r;
                    });
                }
                // For chunks still in view but never sent under the new
                // center's generation state, the spiral below picks them
                // up via the done set; nothing else to do here.
            }
            loggedDone.remove(player.profile);
            session.send(Outbound.cacheCenter(pcx, pcz));
        }
        int radius = player.viewDistance;
        if (loggedStart.add(player.profile)) {
            // Memory runs prove the effective radius once (g2-memory-design 1.2).
            System.out.println("[kestrel] stream start player=" + player.name
                    + " radius=" + radius + " center=" + pcx + "," + pcz
                    + " tick=" + loop.gameTime());
        }
        List<int[]> order = spiral(pcx, pcz, radius, done);
        if (order.isEmpty()) {
            if (loggedDone.add(player.profile)) {
                // Load-window end marker: samples after this line see no streaming garbage.
                System.out.println("[kestrel] stream complete player=" + player.name
                        + " radius=" + radius + " chunks=" + done.size()
                        + " tick=" + loop.gameTime());
            }
            return;
        }
        Map<Long, Long> playerSent = sentWrites.computeIfAbsent(player.profile, k -> new HashMap<>());
        List<int[]> toSend = new ArrayList<>(Math.min(CHUNKS_PER_TICK, order.size()));
        for (int[] c : order) {
            long key = pack(c[0], c[1]);
            if (blockedOnGeneration(c[0], c[1])) {
                if (asyncGenerator == null) {
                    // Legacy path, unchanged: budget spent this tick, stop
                    // before starting another inline generation. Spiral
                    // order is center-first, so the stream resumes from
                    // this same chunk next tick.
                    long now = loop.gameTime();
                    if (now != lastBudgetLogTick) {
                        lastBudgetLogTick = now;
                        System.out.println("[kestrel] gen budget exhausted: nextChunk=" + c[0] + "," + c[1]
                                + " player=" + player.name + " genNanosThisTick=" + genNanosThisTick
                                + " tick=" + now);
                    }
                    break;
                }
                // Async path: this chunk was (already, or just now) handed
                // to the background pipeline. Do not stall the whole scan
                // behind it - keep looking, since a chunk further out in
                // spiral order may already be generated and ready to send
                // (issue #160: "already-generated chunk sends must remain
                // fast and must not wait behind generation").
                continue;
            }
            if (waitingForAsyncLight(c[0], c[1])) {
                continue;
            }
            Long lastSent = playerSent.get(key);
            long currentWrites = chunkWrites.getOrDefault(key, 0L);
            // Send-time backstop: a chunk already sent to this player that has zero
            // writes since its last send is skipped even if flagged in dirtyChunks.
            if (lastSent != null && lastSent == currentWrites) {
                done.add(key);
                continue;
            }
            toSend.add(c);
            if (toSend.size() >= CHUNKS_PER_TICK) {
                break;
            }
        }
        if (toSend.isEmpty()) {
            return;
        }
        // Respect the client's chunk-batch acknowledgement. Without this,
        // fast movement queues batches for obsolete cache centres faster
        // than the connection can flush them, so the client can stand in a
        // generated server chunk while still rendering an old all-void view.
        if (!session.startChunkBatch()) {
            return;
        }
        session.send(Outbound.batchStart());
        int sentCount = 0;
        for (int[] c : toSend) {
            if (blockedOnGeneration(c[0], c[1])) {
                if (asyncGenerator == null) {
                    break;
                }
                continue;
            }
            if (waitingForAsyncLight(c[0], c[1])) {
                continue;
            }
            long key = pack(c[0], c[1]);
            List<byte[]> secs = sectionBytes(c[0], c[1]);
            session.send(chunkPacket(c[0], c[1], secs));
            done.add(key);
            playerSent.put(key, chunkWrites.getOrDefault(key, 0L));
            sentCount++;
        }
        session.send(Outbound.batchFinished(sentCount));
    }

    /**
     * Join burst: cache-center plus one batch of the inner 5x5 around the
     * player, marking what it sent so the per-tick stream continues
     * outward. The 3x3 ({@link #BURST_FORCED_RADIUS}) always goes out,
     * generating if needed; outer-ring chunks go out only if cached, so a
     * fresh world no longer generates all 25 in one uncapped tick (~5.5s
     * on the Pi). Called once per join on the tick thread, before the
     * two-stage teleport.
     *
     * <p>Every call starts from clean stream state for the profile: a
     * rejoining player (same profile, often the same spawn-fallback
     * center) must never inherit the previous session's sent markers,
     * or the burst's {@code done.contains} skip plus an already-equal
     * center in {@link #streamAround} stream zero chunks and the client
     * falls through empty air (spawn-fallback empty-stream bug). The
     * start/done log markers reset too, so the new stream is logged.
     */
    /**
     * Prepare the forced inner join ring without ever blocking the tick thread
     * when async generation is enabled.
     *
     * <p>Legacy/synchronous mode returns true immediately so streamBurst keeps
     * its exact old behavior. Async mode first admits every still-missing
     * forced 3x3 center in the same dx->dz order streamBurst historically
     * reached them. Once all nine centers are published, it also waits for
     * their exact Vanilla LIGHT payloads. LIGHT is radius-1 dependent, so a
     * forced 3x3 needs generated centers out through the surrounding 5x5;
     * missing dependencies are admitted through the same bounded async queue.
     *
     * <p>This second readiness condition is essential: streamBurst refuses to
     * send an async-generated chunk without exact light. Returning true after
     * generation alone could therefore advance the join state while
     * streamBurst sent zero chunks, leaving chunkBatchPending/flow control with
     * no terrain batch for the client to acknowledge.
     */
    boolean prepareJoinBurst(KestrelPlayer player) {
        if (asyncGenerator == null) {
            return true;
        }
        int pcx = player.chunkX();
        int pcz = player.chunkZ();

        boolean generatedReady = true;
        for (int dx = -BURST_FORCED_RADIUS; dx <= BURST_FORCED_RADIUS; dx++) {
            for (int dz = -BURST_FORCED_RADIUS; dz <= BURST_FORCED_RADIUS; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (needsGeneration(cx, cz)) {
                    asyncGenerator.requestGeneration(cx, cz);
                    generatedReady = false;
                }
            }
        }
        if (!generatedReady) {
            return false;
        }

        boolean lightReady = true;
        for (int dx = -BURST_FORCED_RADIUS; dx <= BURST_FORCED_RADIUS; dx++) {
            for (int dz = -BURST_FORCED_RADIUS; dz <= BURST_FORCED_RADIUS; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long key = net.minecraft.world.level.ChunkPos.pack(cx, cz);
                if (loop.level().lightWireChunk(key) != null) {
                    continue;
                }

                lightReady = false;
                for (int ldx = -1; ldx <= 1; ldx++) {
                    for (int ldz = -1; ldz <= 1; ldz++) {
                        int nx = cx + ldx;
                        int nz = cz + ldz;
                        if (needsGeneration(nx, nz)) {
                            asyncGenerator.requestGeneration(nx, nz);
                        }
                    }
                }
            }
        }
        return lightReady;
    }

    public void streamBurst(KestrelPlayer player, NetServer.Session session,
            Runnable afterCacheCenter) {
        int pcx = player.chunkX();
        int pcz = player.chunkZ();
        long center = (((long) pcx) << 32) | (pcz & 0xFFFFFFFFL);
        // Rejoin reset: drop the previous session's stream state first.
        sent.remove(player.profile);
        sentWrites.remove(player.profile);
        loggedStart.remove(player.profile);
        loggedDone.remove(player.profile);
        Set<Long> done = sent.computeIfAbsent(player.profile, k -> new HashSet<>());
        centers.put(player.profile, center);
        session.send(Outbound.cacheCenter(pcx, pcz));
        afterCacheCenter.run();
        // Advertise the same radius the join packet and this sender's own
        // spiral() actually stream (player.viewDistance is the single
        // source of truth, negotiated from ClientInformation and clamped
        // in NetServer): without this the client's chunk cache runs at
        // whatever the join packet claimed even if that disagreed with
        // what actually arrives on the wire.
        session.send(Outbound.cacheRadius(player.viewDistance));
        List<int[]> order = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                long key = pack(pcx + dx, pcz + dz);
                boolean forced = Math.max(Math.abs(dx), Math.abs(dz)) <= BURST_FORCED_RADIUS;
                // The forced inner ring goes out regardless of any sent
                // marker; the outer ring still skips sent or ungenerated.
                if (!forced && done.contains(key)) {
                    continue;
                }
                if (!forced && needsGeneration(pcx + dx, pcz + dz)) {
                    continue;
                }
                if (asyncGenerator != null && waitingForAsyncLight(pcx + dx, pcz + dz)) {
                    continue;
                }
                order.add(new int[]{pcx + dx, pcz + dz});
            }
        }
        if (order.isEmpty()) {
            return;
        }
        Map<Long, Long> playerSent = sentWrites.computeIfAbsent(player.profile, k -> new HashMap<>());
        if (!session.startChunkBatch()) {
            return;
        }
        session.send(Outbound.batchStart());
        for (int[] cc : order) {
            long key = pack(cc[0], cc[1]);
            List<byte[]> secs = sectionBytes(cc[0], cc[1]);
            session.send(chunkPacket(cc[0], cc[1], secs));
            done.add(key);
            playerSent.put(key, chunkWrites.getOrDefault(key, 0L));
        }
        session.send(Outbound.batchFinished(order.size()));
    }

    /**
     * Queue an abandoned-ungenerated chunk for deferred generation, unless
     * it is already queued or the queue is at {@link #MAX_BACKFILL}. No-op
     * without a generator attached (persisted-store-only boots never
     * generate anything).
     */
    void enqueueBackfill(int chunkX, int chunkZ) {
        if (generator == null) {
            return;
        }
        long key = pack(chunkX, chunkZ);
        if (backfill.contains(key)) {
            return;
        }
        if (backfill.size() >= MAX_BACKFILL) {
            long now = loop.gameTime();
            if (now != lastBackfillFullLogTick) {
                lastBackfillFullLogTick = now;
                System.out.println("[kestrel] backfill queue full: dropping chunk=" + chunkX + "," + chunkZ
                        + " cap=" + MAX_BACKFILL + " tick=" + now);
            }
            return;
        }
        backfill.add(key);
    }

    /** Diagnostic: chunks currently waiting for deferred backfill generation. */
    public int backfillQueued() {
        return backfill.size();
    }

    /**
     * Finish at most one abandoned chunk per call, out of any player's
     * view. This is the island-hole fix: a chunk that fell out of the view
     * radius before it finished generating is never revisited by
     * {@link #spiral} (which only walks the current center's radius), so
     * without this it stays permanently void and players fall through it.
     *
     * <p>Strictly subordinate to live-view streaming by cadence: it runs
     * at most once per {@link #BACKFILL_INTERVAL_TICKS} while anyone is
     * online, and never more than one chunk per call. Time spent here is
     * charged to the same per-tick budget counter, so nothing else in this
     * tick starts another generation on top of it.
     *
     * <p>Chunks that got generated normally in the meantime (the player
     * walked back into range) are dropped from the queue for free - the
     * skip costs one store lookup, no generation.
     *
     * <p>Thread-safety: tick thread only, same contract as the rest of
     * this class. Called from {@link NetServer#tick} after every player
     * has streamed.
     *
     * @param onlinePlayers number of players currently in PLAY phase
     */
    public void drainBackfill(int onlinePlayers) {
        if (generator == null || backfill.isEmpty()) {
            return;
        }
        if (asyncGenerator != null) {
            drainBackfillAsync(onlinePlayers);
            return;
        }
        rollBudget();
        long now = loop.gameTime();
        long interval = onlinePlayers == 0
                ? BACKFILL_IDLE_INTERVAL_TICKS
                : BACKFILL_INTERVAL_TICKS;
        // MIN_VALUE means "never ran". Subtracting it from a positive
        // game time overflows negative, which used to make the very first
        // backfill appear perpetually too recent and starve the queue.
        if (!backfillDue(now, lastBackfillTick, interval)) {
            return;
        }
        int cx = 0;
        int cz = 0;
        boolean found = false;
        for (java.util.Iterator<Long> it = backfill.iterator(); it.hasNext(); ) {
            long key = it.next();
            it.remove();
            int kx = (int) (key >> 32);
            int kz = (int) (long) key;
            if (loop.store().isChunkGenerated(net.minecraft.world.level.ChunkPos.pack(kx, kz))) {
                // Filled in by the normal live path since it was queued.
                continue;
            }
            cx = kx;
            cz = kz;
            found = true;
            break;
        }
        if (!found) {
            return;
        }
        lastBackfillTick = now;
        long start = System.nanoTime();
        List<int[]> touched = generator.ensureChunk(loop.store(), cx, cz);
        publishGeneratedMetadata(touched);
        long spent = System.nanoTime() - start;
        genNanosThisTick += spent;
        for (int[] t : touched) {
            // Same stale-copy invalidation as the live send path: a chunk
            // this call wrote may already be held by a player as an all-air
            // copy, so mark it dirty and drop every player's sent marker to
            // force a resend. This is what makes the hole visibly fill in
            // for a player standing next to it.
            long tk = pack(t[0], t[1]);
            markDirty(tk);
            for (UUID id : sent.keySet()) {
                forgetChunks(id, Set.of(tk));
            }
        }
        System.out.println("[kestrel] backfilled abandoned chunk=" + cx + "," + cz
                + " genMs=" + spent / 1_000_000.0
                + " queued=" + backfill.size()
                + " players=" + onlinePlayers
                + " tick=" + now);
    }

    /**
     * Async backfill path: preserve the old cadence and at-most-one-admission
     * rule, but never execute LiveGenerator.ensureChunk on the tick thread.
     * Generated entries are cleaned lazily; queued entries remain in the
     * backfill set until publication marks the center generated.
     */
    private void drainBackfillAsync(int onlinePlayers) {
        long now = loop.gameTime();
        long interval = onlinePlayers == 0
                ? BACKFILL_IDLE_INTERVAL_TICKS
                : BACKFILL_INTERVAL_TICKS;
        if (!backfillDue(now, lastBackfillTick, interval)) {
            return;
        }

        for (java.util.Iterator<Long> it = backfill.iterator(); it.hasNext(); ) {
            long key = it.next();
            int cx = (int) (key >> 32);
            int cz = (int) (long) key;
            if (loop.store().isChunkGenerated(net.minecraft.world.level.ChunkPos.pack(cx, cz))) {
                it.remove();
                continue;
            }
            if (asyncGenerator.hasFailed(cx, cz)) {
                it.remove();
                System.out.println("[kestrel] dropping failed async backfill chunk="
                        + cx + "," + cz + " tick=" + now);
                continue;
            }
            if (asyncGenerator.isQueued(cx, cz)) {
                // A prior cadence slot already owns this backfill center.
                lastBackfillTick = now;
                return;
            }
            if (asyncGenerator.requestGeneration(cx, cz)) {
                lastBackfillTick = now;
                System.out.println("[kestrel] queued async backfill chunk=" + cx + "," + cz
                        + " queued=" + backfill.size()
                        + " players=" + onlinePlayers
                        + " tick=" + now);
            }
            // False here means bounded queue pressure. Do not advance the
            // cadence sentinel so the next tick may retry once capacity frees.
            return;
        }
    }

    /** Package-visible scheduling predicate for the overflow regression gate. */
    static boolean backfillDue(long now, long last, long interval) {
        return last == Long.MIN_VALUE || now - last >= interval;
    }

    /** True if sending (chunkX, chunkZ) would run inline generation first. */
    private boolean needsGeneration(int chunkX, int chunkZ) {
        return generator != null
                && !loop.store().isChunkGenerated(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
    }

    /**
     * True if (chunkX, chunkZ) cannot be sent right now because it still
     * needs generation and there is no way to produce it this instant.
     *
     * <p>No async generator attached (default): identical to the
     * original {@code needsGeneration(...) && !genBudgetLeft()} check -
     * same budget, same semantics, byte-for-byte unchanged behavior.
     *
     * <p>Async generator attached: never budget-gated. Instead this
     * (re)submits a background job for the chunk - {@link
     * AsyncChunkGenerator#requestGeneration} is idempotent, so calling it
     * again while already queued/in-flight is just a cheap set lookup -
     * and reports "blocked" so the caller skips this chunk rather than
     * waiting on it.
     */
    private boolean blockedOnGeneration(int chunkX, int chunkZ) {
        if (!needsGeneration(chunkX, chunkZ)) {
            return false;
        }
        if (asyncGenerator != null) {
            asyncGenerator.requestGeneration(chunkX, chunkZ);
            return true;
        }
        return !genBudgetLeft();
    }

    /**
     * Async live chunks are not network-ready until their exact Vanilla LIGHT
     * payload exists. While waiting, admit missing radius-1 generation
     * dependencies in deterministic dx->dz order. The bounded async queue
     * supplies backpressure; no tick-thread lighting or generation occurs.
     */
    private boolean waitingForAsyncLight(int chunkX, int chunkZ) {
        if (asyncGenerator == null || generator == null) {
            return false;
        }
        long key = net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ);
        if (!loop.store().isChunkGenerated(key)) {
            return true;
        }
        if (loop.level().lightWireChunk(key) != null) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int nx = chunkX + dx;
                int nz = chunkZ + dz;
                if (needsGeneration(nx, nz)) {
                    asyncGenerator.requestGeneration(nx, nz);
                }
            }
        }
        return true;
    }

    /** One drained async result after its tick-thread publication attempt. */
    public record AsyncPublication(
            int cx,
            int cz,
            long genNanos,
            long snapshotNanos,
            long publishNanos,
            Throwable failure) {
        public boolean failed() {
            return failure != null;
        }
    }

    /** Metadata for one production publication pass. */
    public record AsyncPublishBatch(List<AsyncPublication> results, long totalNanos) {
        public boolean isEmpty() {
            return results.isEmpty();
        }
    }

    /**
     * Sole writer into the live store for background-generated chunks.
     *
     * <p>The worker result already owns an immutable compact snapshot of every
     * touched section. The tick thread therefore never reads the worker's
     * mutable shadow store, eliminating the N/N+1 handoff race. Every touched
     * section slot is published, including absent/all-air slots, so generated
     * air can clear stale unclaimed generation state. Live-layer edits remain
     * protected by SectionStore's C1 provenance.
     *
     * <p>The returned metadata is intentionally the only completion-observation
     * seam used by the benchmark. This method owns drainCompleted(), so a
     * benchmark cannot accidentally consume results before production
     * publication and report fake near-zero tick cost.
     */
    public AsyncPublishBatch publishAsyncResults() {
        long totalStart = System.nanoTime();
        if (asyncGenerator == null) {
            return new AsyncPublishBatch(List.of(), System.nanoTime() - totalStart);
        }
        for (Map<Long, byte[]> refreshed : asyncGenerator.drainActivationLights()) {
            publishLightMetadata(refreshed);
        }
        List<AsyncChunkGenerator.Result> results = asyncGenerator.drainCompleted();
        if (results.isEmpty()) {
            return new AsyncPublishBatch(List.of(), System.nanoTime() - totalStart);
        }

        SectionStore live = loop.store();
        int minSectionY = live.getMinY() >> 4;
        int sectionCount = live.getHeight() >> 4;
        List<AsyncPublication> published = new ArrayList<>(results.size());

        for (AsyncChunkGenerator.Result r : results) {
            long publishStart = System.nanoTime();
            if (r.failed()) {
                System.out.println("[kestrel] async chunk generation failed: chunk=" + r.cx + "," + r.cz
                        + " genMs=" + (r.genNanos / 1_000_000.0)
                        + " error=" + r.failure);
                published.add(new AsyncPublication(
                        r.cx, r.cz, r.genNanos, r.snapshotNanos,
                        System.nanoTime() - publishStart, r.failure));
                continue;
            }

            for (Map.Entry<Long, byte[]> biome : r.biomes.entrySet()) {
                loop.level().putBiomeWireSection(biome.getKey(), biome.getValue());
            }
            if (activation != null) {
                activation.publishMarks(r.postProcessing);
            }

            for (AsyncChunkGenerator.ChunkRef t : r.touched) {
                for (int slot = 0; slot < sectionCount; slot++) {
                    long key = SectionPos.asLong(t.x(), minSectionY + slot, t.z());
                    live.putGeneratedCompact(key, r.sections.get(key));
                }

                long tk = pack(t.x(), t.z());
                markDirty(tk);
                for (UUID id : sent.keySet()) {
                    forgetChunks(id, Set.of(tk));
                }
            }
            live.markChunkGenerated(net.minecraft.world.level.ChunkPos.pack(r.cx, r.cz));
            // Light last: publishing it may activate chunks, whose pass must
            // see this result's blocks already live.
            publishLightMetadata(r.lights);
            published.add(new AsyncPublication(
                    r.cx, r.cz, r.genNanos, r.snapshotNanos,
                    System.nanoTime() - publishStart, null));
        }

        return new AsyncPublishBatch(List.copyOf(published), System.nanoTime() - totalStart);
    }

    /**
     * True while this tick's generation budget still allows starting a
     * fresh chunk. Package-visible (same pattern as {@link #writeVersion})
     * so the {@code --gen-budget-ms} override has a same-package check.
     */
    boolean genBudgetLeft() {
        rollBudget();
        return genNanosThisTick < genBudgetNanos;
    }

    /** Reset the generation budget when the game tick has moved on. */
    private void rollBudget() {
        long now = loop.gameTime();
        if (now != budgetTick) {
            budgetTick = now;
            genNanosThisTick = 0;
        }
    }

    /** Expanding-square chunk order, skipping already-sent. */
    static List<int[]> spiral(int pcx, int pcz, int radius, Set<Long> done) {
        List<int[]> out = new ArrayList<>();
        for (int r = 0; r <= radius && out.size() < CHUNKS_PER_TICK; r++) {
            for (int dx = -r; dx <= r && out.size() < CHUNKS_PER_TICK; dx++) {
                for (int dz = -r; dz <= r && out.size() < CHUNKS_PER_TICK; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    long key = pack(pcx + dx, pcz + dz);
                    if (!done.contains(key)) {
                        out.add(new int[]{pcx + dx, pcz + dz});
                    }
                }
            }
        }
        return out;
    }

    static long pack(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Section bytes for one chunk column in slot order: live cells
     * from the store (null reads as air), honest nonEmpty/fluid
     * counts. Unlived sections send all-air.
     */
    /**
     * Reused cell buffer for section encoding. Confined to the tick
     * thread ({@code sectionBytes} is only reached from the per-tick
     * stream) and never retained: {@link Outbound#sectionBytes} reads it
     * into its own palette and data arrays before returning. Every one of
     * the 4096 slots is overwritten on each use, so no stale cell can
     * survive into the next section.
     */
    private final BlockState[] scratchCells = new BlockState[4096];

    /** Lazily built constant for an absent (all-air) section slot. */
    private byte[] airSectionCache;

    private byte[] airSectionBytes() {
        if (airSectionCache == null) {
            airSectionCache = Outbound.airSectionBytes(air, plainsBiomeId);
        }
        return airSectionCache.clone();
    }

    List<byte[]> sectionBytes(int chunkX, int chunkZ) {
        if (needsGeneration(chunkX, chunkZ)) {
            long start = System.nanoTime();
            List<int[]> touchedChunks = generator.ensureChunk(loop.store(), chunkX, chunkZ);
            publishGeneratedMetadata(touchedChunks);
            rollBudget();
            genNanosThisTick += System.nanoTime() - start;
            for (int[] touched : touchedChunks) {
                // A chunk this call generated may be one already sent to a
                // player (a neighbour's radius-1 FEATURES write can still
                // land in it, or the player may hold an all-air copy sent
                // before this chunk existed): mark every touched chunk dirty
                // so the per-tick stream notices and resends it. Stale-copy
                // invalidation (stale-center fix 2026-09-14): forget the
                // sent marker for EVERY player, not just the current one,
                // or clients keep showing yesterday's terrain with the
                // server believing them complete.
                markDirty(pack(touched[0], touched[1]));
                for (java.util.UUID id : sent.keySet()) {
                    forgetChunks(id, java.util.Set.of(pack(touched[0], touched[1])));
                }
            }
        }
        int minSectionY = loop.store().getMinY() >> 4;
        int count = loop.store().getHeight() >> 4;
        boolean requireGeneratedMetadata = FINAL_GATE_STRICT_METADATA
                && loop.store().isChunkGenerated(
                        net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
        List<byte[]> out = new ArrayList<>(count);
        for (int slot = 0; slot < count; slot++) {
            long key = SectionPos.asLong(chunkX, minSectionY + slot, chunkZ);
            byte[] biomeWire = loop.level().biomeWireSection(key);
            if (requireGeneratedMetadata && biomeWire == null) {
                throw new IllegalStateException(
                        "final live gate: generated chunk missing biome wire chunk="
                                + chunkX + "," + chunkZ + " section=" + (minSectionY + slot));
            }
            if (!loop.store().hasSection(key)) {
                // Absent slot: the old path filled a fresh BlockState[4096]
                // by readCell, then overwrote all 4096 with air and sent a
                // constant. Emit that constant directly. Cached per (air,
                // biome) pair, cloned so a consumer that edits the buffer
                // cannot poison later sends.
                out.add(biomeWire == null
                        ? airSectionBytes()
                        : Outbound.airSectionBytes(air, biomeWire));
                continue;
            }
            BlockState[] cells = scratchCells;
            int nonEmpty = 0;
            int fluid = 0;
            for (int i = 0; i < 4096; i++) {
                BlockState st = loop.store().readCell(key, i);
                if (st == null) {
                    st = air;
                }
                cells[i] = st;
                if (!st.isAir()) {
                    nonEmpty++;
                }
                if (!st.getFluidState().isEmpty()) {
                    fluid++;
                }
            }
            out.add(biomeWire == null
                    ? Outbound.sectionBytes(cells, nonEmpty, fluid, air, plainsBiomeId)
                    : Outbound.sectionBytes(cells, nonEmpty, fluid, air, biomeWire));
        }
        return out;
    }

    private void publishGeneratedMetadata(List<int[]> touched) {
        if (generator == null || touched == null || touched.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, byte[]> entry : generator.snapshotBiomeWire(touched).entrySet()) {
            loop.level().putBiomeWireSection(entry.getKey(), entry.getValue());
        }
        if (activation != null) {
            activation.publishMarks(generator.snapshotPostProcessing(touched));
        }
        publishLightMetadata(generator.snapshotNewlyReadyLightWire(loop.store(), touched));
    }

    /**
     * Publish exact Vanilla light payloads into live tick-thread state.
     *
     * <p>Light is part of the client's chunk image just like block sections.
     * A later neighbouring LIGHT stage can change an already-sent chunk's
     * payload without touching its blocks, so every new/changed payload owns a
     * chunk revision and clears all sent markers. Identical reserialization is
     * a no-op, avoiding resend churn.
     *
     * <p>The input key uses Minecraft ChunkPos.pack (x low, z high); sender
     * tracking uses its historical pack (x high, z low), hence the explicit
     * coordinate conversion below.
     */
    void publishLightMetadata(Map<Long, byte[]> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, byte[]> entry : updates.entrySet()) {
            long worldKey = entry.getKey();
            byte[] next = entry.getValue();
            byte[] previous = loop.level().lightWireChunk(worldKey);
            if (previous != null && java.util.Arrays.equals(previous, next)) {
                continue;
            }

            loop.level().putLightWireChunk(worldKey, next);
            int cx = (int) worldKey;
            int cz = (int) (worldKey >> 32);
            long streamKey = pack(cx, cz);
            markDirty(streamKey);
            for (UUID id : sent.keySet()) {
                forgetChunks(id, Set.of(streamKey));
            }
        }
        if (activation != null) {
            // Every key here is a chunk the generator's engine has lit.
            activation.lightPublished(updates.keySet());
        }
    }

    private Outbound.Packet chunkPacket(int chunkX, int chunkZ, List<byte[]> sections) {
        long key = net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ);
        byte[] light = loop.level().lightWireChunk(key);
        if (FINAL_GATE_STRICT_METADATA && loop.store().isChunkGenerated(key) && light == null) {
            throw new IllegalStateException(
                    "final live gate: generated chunk missing exact light wire chunk="
                            + chunkX + "," + chunkZ);
        }
        return light == null
                ? Outbound.chunk(chunkX, chunkZ, sections, sections.size() + 2)
                : Outbound.chunk(chunkX, chunkZ, sections, light);
    }
}
