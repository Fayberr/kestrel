package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Regression gate for High Finding H3 (docs/ARCHITECTURE-AUDIT.md):
 * 'Mutations have no single authoritative revision and notification path'.
 *
 * <p>The defect: only player dig/place told clients about a change (by hand,
 * in NetServer, with a second revision map that {@code syncChunkWrites}
 * copied over ChunkSender's own). Scheduled ticks, redstone commits and
 * shape side effects committed through {@link TickLoop} never reached a
 * client that already held the chunk, and the two revision maps could move
 * a revision backwards.
 *
 * <p>The fix: every committed {@link TickLoop} write lands in one mutation
 * journal; {@code NetServer.publishBlockChanges} drains it each tick, bumps
 * the chunk's revision once in ChunkSender (the only revision authority) and
 * sends the final cell states to every client holding the chunk.
 *
 * <p>This gate follows the audit's smallest verification. Two real loopback
 * clients fully receive a flat world, then without any manual dirty marking:
 * <ol>
 *   <li>a scheduled redstone-torch transition,</li>
 *   <li>a fence write whose own shape pass connects it to adjacent stone,</li>
 *   <li>a write in a second chunk,</li>
 *   <li>a burst of more than {@code MAX_BLOCK_UPDATES_PER_CHUNK} writes in one
 *       chunk (full-resend fallback),</li>
 *   <li>a player placement over the wire (update must precede the ack).</li>
 * </ol>
 * Each client decodes chunk packets through vanilla's own codec and
 * {@code PalettedContainer}, applies block updates in arrival order, and the
 * resulting image must equal the server store cell for cell. Revisions must
 * strictly increase for changed chunks and never decrease.
 *
 * <p>PASS means the notification path is complete. {@code --demonstrate-defect}
 * is kept as an alias that now fails.
 */
public final class ScheduledTickRevisionCheck {
    private ScheduledTickRevisionCheck() {
    }

    /** Flat-world floor half-width (cells); covers chunks -2..1 on both axes. */
    private static final int FLOOR = 24;
    /** Client image region compared against the server: y 0..3 over the floor. */
    private static final int MAX_Y = 3;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        System.out.println("=== ScheduledTickRevisionCheck: H3 regression gate ===");
        if (args.length > 0 && args[0].equals("--demonstrate-defect")) {
            failures.add("--demonstrate-defect: H3 is fixed; this gate only asserts the notification path now");
        }

        NetServer server = null;
        try {
            server = run(failures);
        } catch (Exception e) {
            failures.add("threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (server != null) {
                server.close();
            }
        }

        boolean passed = failures.isEmpty();
        System.out.println("ScheduledTickRevisionCheck: " + (passed ? "PASS" : "FAIL"));
        if (!passed) {
            for (String f : failures) {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    private static NetServer run(List<String> failures) throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);
        for (int x = -FLOOR; x < FLOOR; x++) {
            for (int z = -FLOOR; z < FLOOR; z++) {
                loop.setBlock(new BlockPos(x, 0, z), stone, 3);
            }
        }
        // Scene set up before anyone joins, so it arrives inside the chunks.
        BlockPos torchPos = new BlockPos(5, 1, 5);
        BlockState unlitTorch = Blocks.REDSTONE_TORCH.defaultBlockState()
                .setValue(RedstoneTorchBlock.LIT, false);
        loop.setBlock(torchPos, unlitTorch, 2 | 16 | 512);
        BlockPos fenceNeighbor = new BlockPos(7, 1, 8);
        BlockPos fencePos = new BlockPos(6, 1, 8);
        loop.setBlock(fencePos, Blocks.OAK_FENCE.defaultBlockState(), 2 | 16 | 512);

        NetServer server = new NetServer(loop, 0, 256, 4242424242L);
        server.start();
        ChunkSender sender = new ChunkSender(loop, NetCheck.plainsId());
        Client a = new Client("H3A");
        Client b = new Client("H3B");
        a.link = NetCheck.joinLoopback(loop, server, sender, a.name, 2);
        a.absorb(a.link.joinSeen);
        b.link = NetCheck.joinLoopback(loop, server, sender, b.name, 2);
        b.absorb(b.link.joinSeen);
        if (a.link == null || b.link == null) {
            failures.add("clients failed to join");
            return server;
        }
        pump(loop, server, sender, List.of(a, b), 8);
        long chunk00 = ChunkSender.pack(0, 0);
        expect(a.chunks.contains(chunk00) && b.chunks.contains(chunk00),
                "setup: both clients hold chunk (0,0) before any change", failures);
        expect(a.image.get(torchPos.asLong()) != null
                        && a.image.get(torchPos.asLong()) == Block.getId(unlitTorch),
                "setup: client A sees the unlit torch from the chunk packet", failures);
        int chunksBefore = a.chunkPackets;

        Map<Long, Long> revBefore = revisions(sender);

        // 1. Scheduled transition: the torch lights on its own tick.
        loop.scheduleTick(torchPos, Blocks.REDSTONE_TORCH, 0, TickPriority.NORMAL);
        // 2. Shape side effect: placing stone east updates the existing fence.
        loop.setBlock(fenceNeighbor, stone, 3);
        // 3. A second chunk.
        BlockPos otherChunk = new BlockPos(-3, 1, -3);
        loop.setBlock(otherChunk, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        pump(loop, server, sender, List.of(a, b), 3);

        BlockState torchNow = loop.getBlockState(torchPos);
        expect(torchNow.is(Blocks.REDSTONE_TORCH) && torchNow.getValue(RedstoneTorchBlock.LIT),
                "scene: scheduled tick lit the torch on the server (got " + torchNow + ")", failures);
        BlockState fenceNow = loop.getBlockState(fencePos);
        expect(fenceNow.is(Blocks.OAK_FENCE) && fenceNow.getValue(FenceBlock.EAST),
                "scene: fence shape pass connected east (got " + fenceNow + ")", failures);
        expect(a.chunkPackets == chunksBefore,
                "deltas: small changes arrive as block updates, not chunk resends (got "
                        + (a.chunkPackets - chunksBefore) + " chunk packets)", failures);

        // 4. Burst: more than the per-chunk delta cap in chunk (-2,-2).
        int burst = NetServer.MAX_BLOCK_UPDATES_PER_CHUNK + 16;
        for (int i = 0; i < burst; i++) {
            loop.setBlock(new BlockPos(-32 + (i % 16), 1, -32 + (i / 16)),
                    Blocks.DIRT.defaultBlockState(), 3);
        }
        int aChunks = a.chunkPackets;
        pump(loop, server, sender, List.of(a, b), 4);
        expect(a.chunkPackets > aChunks,
                "burst: over-cap chunk was resent whole", failures);

        // 5. Player placement over the wire: update precedes ack, both see it.
        a.orderLog.clear();
        NetCheck.hold(server.players.get(Wire.offlineUuid("H3A")),
                net.minecraft.world.item.Items.STONE, 8);
        a.link.sendC(PacketIds.PLAY_USE_ITEM_ON,
                NetCheck.useItemOn(0, new BlockPos(3, 0, 3), 1, 77));
        NetCheck.drainInbound(server);
        pump(loop, server, sender, List.of(a, b), 3);
        BlockPos placed = new BlockPos(3, 1, 3);
        int updateAt = a.orderLog.indexOf("update:" + placed.asLong());
        int ackAt = a.orderLog.indexOf("ack:77");
        expect(updateAt >= 0 && ackAt >= 0 && updateAt < ackAt,
                "place: block update arrives before the ack (order " + a.orderLog + ")", failures);

        Map<Long, Long> revAfter = revisions(sender);
        for (Map.Entry<Long, Long> e : revBefore.entrySet()) {
            long now = revAfter.getOrDefault(e.getKey(), 0L);
            expect(now >= e.getValue(), "revision: chunk " + e.getKey() + " went backwards "
                    + e.getValue() + " -> " + now, failures);
        }
        for (BlockPos p : List.of(torchPos, fencePos, otherChunk, placed)) {
            long key = ChunkSender.pack(p.getX() >> 4, p.getZ() >> 4);
            expect(revAfter.getOrDefault(key, 0L) > revBefore.getOrDefault(key, 0L),
                    "revision: changed chunk " + (p.getX() >> 4) + "," + (p.getZ() >> 4)
                            + " revision did not increase", failures);
        }

        // Exact agreement: each client's image equals the server store.
        for (Client c : List.of(a, b)) {
            int compared = 0;
            List<String> diffs = new ArrayList<>();
            for (int x = -FLOOR - 8; x < FLOOR; x++) {
                for (int z = -FLOOR - 8; z < FLOOR; z++) {
                    if (!c.chunks.contains(ChunkSender.pack(x >> 4, z >> 4))) {
                        continue;
                    }
                    for (int y = 0; y <= MAX_Y; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        int want = Block.getId(loop.getBlockState(p));
                        Integer got = c.image.get(p.asLong());
                        compared++;
                        if (got == null || got != want) {
                            diffs.add(p.toShortString() + " want " + Block.stateById(want)
                                    + " got " + (got == null ? "none" : Block.stateById(got)));
                        }
                    }
                }
            }
            System.out.println("  " + c.name + ": compared " + compared + " cells over "
                    + c.chunks.size() + " chunks, diffs=" + diffs.size()
                    + ", chunkPackets=" + c.chunkPackets + ", blockUpdates=" + c.blockUpdates);
            expect(compared > 0, c.name + ": nothing compared", failures);
            expect(diffs.isEmpty(), c.name + ": client image differs from server in "
                    + diffs.size() + " cells, first " + diffs.subList(0, Math.min(5, diffs.size())), failures);
        }
        return server;
    }

    private static Map<Long, Long> revisions(ChunkSender sender) {
        Map<Long, Long> out = new HashMap<>();
        for (int cx = -3; cx <= 2; cx++) {
            for (int cz = -3; cz <= 2; cz++) {
                long key = ChunkSender.pack(cx, cz);
                out.put(key, sender.chunkRevision(key));
            }
        }
        return out;
    }

    static void expect(boolean cond, String what, List<String> failures) {
        if (!cond) {
            failures.add(what);
        }
    }

    /** Tick the server trio and drain both clients, answering teleport and keepalive. */
    private static void pump(TickLoop loop, NetServer server, ChunkSender sender,
            List<Client> clients, int ticks) throws Exception {
        for (int i = 0; i < ticks; i++) {
            KestrelServer.runIteration(server, loop, sender);
            for (Client c : clients) {
                List<Wire.Inbound> seen = new ArrayList<>();
                try {
                    c.link.socket.setSoTimeout(50);
                    while (true) {
                        Wire.Inbound in = c.link.read();
                        seen.add(in);
                        if (in.id() == PacketIds.PLAY_POSITION && c.link.playTeleportId < 0) {
                            c.link.playTeleportId = in.reader().varInt();
                            c.link.sendC(PacketIds.PLAY_ACCEPT_TELEPORT,
                                    new Wire.Writer().vint(c.link.playTeleportId).toBytes());
                            c.link.sendC(PacketIds.PLAY_PLAYER_LOADED, new byte[0]);
                        } else if (in.id() == PacketIds.PLAY_KEEPALIVE_OUT) {
                            c.link.sendC(PacketIds.PLAY_KEEPALIVE_IN,
                                    new Wire.Writer().l(in.reader().l()).toBytes());
                        }
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // drained
                }
                c.absorb(seen);
            }
        }
    }

    /**
     * One loopback client plus its block image, built only from what arrived
     * on the wire: chunk packets (vanilla codec + vanilla PalettedContainer)
     * then block updates, in arrival order.
     */
    private static final class Client {
        final String name;
        NetCheck.Loopback link;
        final Map<Long, Integer> image = new HashMap<>();
        final java.util.Set<Long> chunks = new java.util.HashSet<>();
        final List<String> orderLog = new ArrayList<>();
        int chunkPackets;
        int blockUpdates;

        Client(String name) {
            this.name = name;
        }

        void absorb(List<Wire.Inbound> packets) throws Exception {
            for (Wire.Inbound in : packets) {
                if (in.id() == PacketIds.PLAY_CHUNK) {
                    applyChunk(in);
                } else if (in.id() == PacketIds.PLAY_BLOCK_UPDATE) {
                    Wire.Reader r = in.reader();
                    long pos = r.l();
                    int id = r.varInt();
                    image.put(pos, id);
                    blockUpdates++;
                    orderLog.add("update:" + pos);
                } else if (in.id() == PacketIds.PLAY_SECTION_BLOCKS_UPDATE) {
                    // Several cells of one section in one tick arrive as one
                    // SectionBlocksUpdate, as from a vanilla server
                    // (ClientboundSectionBlocksUpdatePacket).
                    var pkt = (net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket)
                            PlayShapeCheck.decodePlay(in.id(), in.payload());
                    pkt.runUpdates((pos, state) -> {
                        image.put(pos.asLong(), Block.getId(state));
                        blockUpdates++;
                        orderLog.add("update:" + pos.asLong());
                    });
                } else if (in.id() == PacketIds.PLAY_BLOCK_CHANGED_ACK) {
                    orderLog.add("ack:" + in.reader().varInt());
                }
            }
        }

        private void applyChunk(Wire.Inbound in) {
            var pkt = (net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket)
                    PlayShapeCheck.decodePlay(in.id(), in.payload());
            int cx = pkt.getX();
            int cz = pkt.getZ();
            chunkPackets++;
            chunks.add(ChunkSender.pack(cx, cz));
            var buf = pkt.getChunkData().getReadBuffer();
            var strategy = net.minecraft.world.level.chunk.Strategy.createForBlockStates(
                    Block.BLOCK_STATE_REGISTRY);
            // Sections bottom-up from minY -64; the image covers y 0..15,
            // section index 4, so read (and discard) the four below it.
            for (int section = 0; section <= 4; section++) {
                buf.readShort();
                buf.readShort();
                var states = new net.minecraft.world.level.chunk.PalettedContainer<BlockState>(
                        Blocks.AIR.defaultBlockState(), strategy);
                states.read(buf);
                skipBiomes(buf);
                if (section == 4) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = 0; y <= MAX_Y; y++) {
                                long pos = new BlockPos(cx * 16 + x, y, cz * 16 + z).asLong();
                                image.put(pos, Block.getId(states.get(x, y, z)));
                            }
                        }
                    }
                }
            }
        }

        /** Skip one 4x4x4 biome container: bits, palette by tier, packed longs. */
        private static void skipBiomes(net.minecraft.network.FriendlyByteBuf buf) {
            int bits = buf.readByte();
            if (bits == 0) {
                buf.readVarInt();
                return;
            }
            if (bits <= 3) {
                int n = buf.readVarInt();
                for (int i = 0; i < n; i++) {
                    buf.readVarInt();
                }
            }
            int perLong = 64 / bits;
            int longs = (64 + perLong - 1) / perLong;
            buf.skipBytes(longs * 8);
        }
    }
}
