package dev.kestrel.net;

import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/**
 * Stage 3 proof battery: wire framing plus a live loopback join.
 *
 * <ol>
 *   <li>VarInt round-trips (0, 1, 127, 128, 255, 2147483647, -1).</li>
 *   <li>Frame/unframe identity without compression, and with threshold
 *       256 (small passes raw with 0 prefix, large deflates).</li>
 *   <li>Section bytes: all-air section decodes through vanilla's own
 *       network read path (LevelChunkSection read); stone section
 *       carries nonEmptyCount and a 4-bit palette.</li>
 *   <li>Live loopback: boot a corpus loop, start NetServer on
 *       127.0.0.1:0, drive the tick loop on this thread while a
 *       loopback client performs the full offline handshake
 *       (handshake, hello, compression, finished, ack, brand, features,
 *       select-known-packs, registries, tags, finish, join burst,
 *       chunks, position, keepalive echo). Asserts the client reaches
 *       play with chunks and the server tracks the player.</li>
 *   <li>Movement: the loopback client sends a PosRot packet; after
 *       ticks the server's player position matches.</li>
 *   <li>Online crypto (offline-safe, no network): RSA key pair
 *       generation, challenge unwrap round-trip through a real
 *       RSA-encrypted key packet, AES/CFB8 link round-trip chunked
 *       like the session I/O path, server-hex-hash shape (unsigned
 *       hex), session JSON profile parse (undashed id, skin
 *       properties), LoginFinished property codec round-trip.</li>
 *   <li>Registry data (needs {@code -Dkestrel.datapack=<dir>} with the
 *       extracted vanilla datapack; skipped otherwise): every served
 *       entry's NBT bytes decode through vanilla's own
 *       {@code NbtIo.readAnyTag} with zero trailing bytes, every
 *       non-empty validated registry from the crash report is present
 *       with entries, tags reference only served ids, and the
 *       UpdateTags packet has no trailing bytes.</li>
 *   <li>Place: a second loopback join sends UseItemOn packets (payload
 *       first decoded through vanilla's own
 *       {@code ServerboundUseItemOnPacket.STREAM_CODEC}). An accepted
 *       placement lands in the store and echoes a BlockUpdate plus a
 *       BlockChangedAck; an invalid face, a solid target, and a target
 *       inside the player's box are absorbed without a store write; a
 *       replaceable clicked block is replaced in place.</li>
 *   <li>Time: SetTime and the rain game events decode through vanilla's
 *       codec with ids matching the vanilla table (in PlayShapeCheck);
 *       the WorldTime counter advances monotonically without wrapping;
 *       the join burst sends full sync with overworld and the_end clocks
 *       (PlayerList.java:654, ServerClockManager.java:130); periodic broadcasts
 *       send advancing gameTime with empty clock maps (MinecraftServer.java:1179);
 *       a /time set broadcasts the changed overworld clock immediately;
 *       and sees START_RAINING, rising levels, then
 *       STOP_RAINING when a forced rain timer runs out.</li>
 *   <li>Chat: loopback join with two clients. Validates ServerboundChatPacket
 *       and Outbound.systemChat payload shapes against vanilla codecs, asserts
 *       serverbound chat and clientbound system_chat IDs match vanilla's tables.
 *       Client A sends chat, both A and B receive the broadcast (proves broadcast,
 *       not echo). Negative control proves peer B received the message. Empty and
 *       overlong messages are absorbed without broadcast while keeping sessions
 *       alive; chat commands are absorbed.</li>
 *   <li>Persist (G4): (a) canned level + player state writes and reads
 *       back equal, overwrite replaces, WorldTime.restore applies every
 *       field without edges; (b) loopback: move a player, set time to N
 *       with rain, save, boot brand-new loop + server objects on the
 *       same dir, rejoin: time == N, weather equal, first SetTime is N
 *       (+ ticks run), join position packet carries the saved pos, the
 *       placed block diff survives; (c) missing files and corrupt or
 *       invalid files (garbage bytes, bad number, out of range, wrong
 *       format, missing key) boot clean defaults (clock 0, clear, spawn
 *       0.5,1.0,0.5) without throwing, on a pre-dirtied clock.</li>
 *   <li>Resend: loopback join with view distance 2 sends initial 5x5 burst.
 *       After join burst is drained, 40 idle ticks with zero block writes send
 *       zero chunk packets (PLAY_CHUNK 0x2D count == 0). Flagging an unchanged
 *       chunk with zero writes is skipped by the send-time backstop. One block
 *       write via use-item-on resends exactly the dirty chunk (distinct chunk
 *       coordinates == 1, matching target chunk) and no others.</li>
 *   <li>Movement (S8): (a) legal walk (0.2 m/tick steps) accepted, position tracks;
 *       (b) teleport 100 blocks in one packet rejected, position unchanged;
 *       (c) sustained fly (+1 m/tick x 30 ticks) triggers rubber-band (server
 *       position packet observed, player back at legal pos); (d) negative
 *       control: exactly-0-violations assertion on the legal walk.</li>
 *   <li>Harden (S13 fault injection, two loopback clients, the flooder
 *       and a bystander): (d) a legal burst of cap - 1 placements queued
 *       in one tick is fully applied with zero drops; (a) a flood of
 *       5000 placements in one tick applies exactly the per-tick cap,
 *       counts the drops, keeps the flooder connected, and the
 *       bystander's move in the same tick still applies; (b) a 10x chat,
 *       an oversized frame skipped mid-stream, a ratio bomb, and an
 *       over-cap declared length are absorbed with the session connected
 *       and its next move applied; (c) a negative frame length, a
 *       zero-length frame, a truncated payload, corrupt zlib, and a
 *       preset-dictionary zlib stream (which used to spin the I/O thread)
 *       are counted without a throw while the server keeps ticking.</li>
 * </ol>
 *
 * <p>Exits non-zero on any mismatch.
 */
public final class NetCheck {
    private NetCheck() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        checkVarInt(failures);
        checkFrame(failures);
        checkSection(failures);
        checkSectionRoundTrip(failures);
        checkLoopback(failures);
        checkPlace(failures);
        checkTime(failures);
        checkChat(failures);
        checkPersist(failures);
        checkOnlineCrypto(failures);
        checkSyncedRegistryKeys(failures);
        checkRegistryData(failures);
        checkResend(failures);
        checkMove(failures);
        checkHarden(failures);
        PlayShapeCheck.checkPlayShapes(failures);

        System.out.println("NetCheck: " + (failures.isEmpty() ? "PASS" : "FAIL"));
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

    // ---- 1: VarInt ----

    private static void checkVarInt(List<String> failures) {
        try {
            int[] cases = {0, 1, 127, 128, 255, 300, 16384, 2097151, 2147483647, -1, -2147483648};
            for (int v : cases) {
                byte[] enc = Wire.varInt(v);
                int[] dec = Wire.readVarInt(enc, 0);
                expect(dec[0] == v && dec[1] == enc.length,
                        "varint: " + v + " round-trips", failures);
            }
            expect(Wire.varInt(0).length == 1, "varint: 0 is 1 byte", failures);
            expect(Wire.varInt(128).length == 2, "varint: 128 is 2 bytes", failures);
            expect(Wire.varInt(-1).length == 5, "varint: -1 is 5 bytes", failures);
        } catch (Exception e) {
            failures.add("varint: threw " + e);
        }
    }

    // ---- 2: framing ----

    private static void checkFrame(List<String> failures) {
        try {
            byte[] payload = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] framed = Wire.frame(0x31, payload, -1);
            int[] len = Wire.readVarInt(framed, 0);
            byte[] body = new byte[len[0]];
            System.arraycopy(framed, len[1], body, 0, len[0]);
            Wire.Inbound in = Wire.decode(Wire.unframe(body, -1));
            expect(in.id() == 0x31, "frame: id survives", failures);
            expect(new String(in.payload(), java.nio.charset.StandardCharsets.UTF_8).equals("hello"),
                    "frame: payload survives", failures);

            // Threshold 256: small passes raw (0 prefix), large deflates.
            byte[] small = Wire.frame(0x01, new byte[10], 256);
            int[] slen = Wire.readVarInt(small, 0);
            byte[] sbody = new byte[slen[0]];
            System.arraycopy(small, slen[1], sbody, 0, slen[0]);
            expect(sbody[0] == 0, "frame: small passes raw", failures);
            byte[] bigPayload = new byte[1000];
            byte[] big = Wire.frame(0x01, bigPayload, 256);
            int[] blen = Wire.readVarInt(big, 0);
            byte[] bbody = new byte[blen[0]];
            System.arraycopy(big, blen[1], bbody, 0, blen[0]);
            int[] declared = Wire.readVarInt(bbody, 0);
            expect(declared[0] == 1001, "frame: declared length is uncompressed size", failures);
            byte[] back = Wire.unframe(bbody, 256);
            expect(back.length == 1001, "frame: large inflates", failures);
        } catch (Exception e) {
            failures.add("frame: threw " + e);
        }
    }

    // ---- 3: section bytes through vanilla's network read ----

    private static void checkSection(List<String> failures) {
        try {
            BlockState air = Blocks.AIR.defaultBlockState();
            int plains = 1;
            BlockState[] cells = new BlockState[4096];
            byte[] airBytes = Outbound.sectionBytes(cells, 0, 0, air, plains);
            // nonEmpty(0) fluid(0) bits(0) + air id varint + biomes bits(0) + plains id
            expect((airBytes[0] & 0xFF) == 0 && (airBytes[1] & 0xFF) == 0
                            && (airBytes[2] & 0xFF) == 0 && (airBytes[3] & 0xFF) == 0,
                    "section: all-air shorts+counts are zero", failures);

            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState[] mixed = new BlockState[4096];
            for (int i = 0; i < 4096; i++) {
                mixed[i] = i < 100 ? stone : null;
            }
            byte[] mixedBytes = Outbound.sectionBytes(mixed, 100, 0, air, plains);
            int nonEmpty = ((mixedBytes[0] & 0xFF) << 8) | (mixedBytes[1] & 0xFF);
            expect(nonEmpty == 100, "section: nonEmpty count honest, got " + nonEmpty, failures);
            expect((mixedBytes[4] & 0xFF) == 4, "section: 2-state palette writes bits 4", failures);

            // Palette ids are real global registry ids: decode the
            // bits byte + palette manually and resolve through vanilla.
            int off = 4; // skip the two shorts
            int bits = mixedBytes[off++] & 0xFF;
            expect(bits == 4, "section: bits byte is 4", failures);
            int[] r = Wire.readVarInt(mixedBytes, off);
            int count = r[0];
            off = r[1];
            expect(count == 2, "section: palette has 2 entries", failures);
            int[] ids = new int[count];
            for (int i = 0; i < count; i++) {
                r = Wire.readVarInt(mixedBytes, off);
                ids[i] = r[0];
                off = r[1];
            }
            int airId = net.minecraft.world.level.block.Block.getId(air);
            int stoneId = net.minecraft.world.level.block.Block.getId(stone);
            expect((ids[0] == airId && ids[1] == stoneId)
                            || (ids[0] == stoneId && ids[1] == airId),
                    "section: palette ids are global registry ids", failures);
            // Data longs follow with no length prefix: 256 longs for 4-bit.
            int remaining = mixedBytes.length - off;
            // minus biomes (bits 0 + plains varint, 1-2 bytes)
            expect(remaining >= 256 * 8,
                    "section: 256 data longs present, remaining=" + remaining, failures);
        } catch (Exception e) {
            failures.add("section: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    /**
     * Feed {@link Outbound#sectionBytes} back through vanilla's OWN
     * network reader ({@code LevelChunkSection.read}) and assert every
     * one of the 4096 cells comes back as the BlockState that went in.
     *
     * <p>This is the check the block-model artifacts actually need.
     * Models are chosen by the global BlockState id in a chunk section
     * palette, and nothing else: a 2026-09-15 byte diff of the whole
     * configuration phase against the real vanilla server showed
     * Kestrel's registry ids and tag ids already match vanilla exactly,
     * so a wrong model can only come from this encoder. The old
     * {@code checkSection} hand-decoded the palette and asserted on the
     * header bytes, which cannot catch a bit-packing error, and the
     * class javadoc already claimed a vanilla read path that was never
     * there.
     *
     * <p>Every palette tier is exercised, because the tier boundaries
     * are where an off-by-one lives: 1 state (single value, bits 0),
     * 2 and 16 (linear, bits 4), 17 (hash, bits 5), 256 (hash, bits 8)
     * and 257 (global palette, bits from the registry size). Trapdoor
     * states are used for the large tiers on purpose, since trapdoors
     * are the blocks that have been seen rendered wrong in a real client.
     */
    private static void checkSectionRoundTrip(List<String> failures) {
        try {
            // Vanilla's own block-state strategy over vanilla's own id
            // map. NOT PalettedContainerFactory.create(RegistryAccess):
            // that also builds the biome container, which needs the
            // dynamic minecraft:worldgen/biome registry that
            // BuiltInRegistries does not carry, so it throws "Missing
            // registry". Only the block-states container decides which
            // model the client draws, so that is what this pins.
            var strategy = net.minecraft.world.level.chunk.Strategy.createForBlockStates(
                    net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY);
            BlockState air = Blocks.AIR.defaultBlockState();

            // A deep pool of distinct states, trapdoor-heavy so the
            // reported artifact is directly covered.
            List<BlockState> pool = new ArrayList<>();
            for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    pool.add(state);
                }
                if (pool.size() > 4096) {
                    break;
                }
            }
            List<BlockState> trapdoors = new ArrayList<>();
            for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                if (block instanceof net.minecraft.world.level.block.TrapDoorBlock) {
                    trapdoors.addAll(block.getStateDefinition().getPossibleStates());
                }
            }
            expect(!trapdoors.isEmpty(), "roundtrip: found trapdoor states to test", failures);
            // Trapdoors first, then whatever else is needed for depth.
            List<BlockState> states = new ArrayList<>(trapdoors);
            for (BlockState st : pool) {
                if (states.size() > 4096) {
                    break;
                }
                if (!states.contains(st)) {
                    states.add(st);
                }
            }

            for (int distinct : new int[]{1, 2, 16, 17, 32, 33, 256, 257, 1024}) {
                if (distinct > states.size()) {
                    failures.add("roundtrip: not enough distinct states for tier " + distinct);
                    continue;
                }
                BlockState[] cells = new BlockState[4096];
                int nonEmpty = 0;
                for (int i = 0; i < 4096; i++) {
                    // Deterministic spread so every palette index is
                    // used and neighbouring cells differ, which is what
                    // makes a bit-packing slip visible.
                    cells[i] = states.get((i * 31 + i / 64) % distinct);
                    if (!cells[i].isAir()) {
                        nonEmpty++;
                    }
                }
                byte[] encoded = Outbound.sectionBytes(cells, nonEmpty, 0, air, 1);

                io.netty.buffer.ByteBuf rawBuf = io.netty.buffer.Unpooled.wrappedBuffer(encoded);
                var buf = new net.minecraft.network.FriendlyByteBuf(rawBuf);
                // LevelChunkSection.read's own order, read off the
                // 26.2 jar: short nonEmpty, short fluid, states
                // container, biomes container.
                int gotNonEmpty = buf.readShort();
                int gotFluid = buf.readShort();
                expect(gotNonEmpty == nonEmpty, "roundtrip: tier " + distinct
                        + " nonEmpty want " + nonEmpty + " got " + gotNonEmpty, failures);
                expect(gotFluid == 0, "roundtrip: tier " + distinct
                        + " fluid want 0 got " + gotFluid, failures);
                var container =
                        new net.minecraft.world.level.chunk.PalettedContainer<BlockState>(
                                air, strategy);
                container.read(buf);
                // Biome tail: single-value palette, bits 0 + VarInt id.
                expect(buf.readableBytes() >= 2, "roundtrip: tier " + distinct
                        + " has no biome container", failures);
                int biomeBits = buf.readByte();
                int biomeId = buf.readVarInt();
                expect(biomeBits == 0, "roundtrip: tier " + distinct
                        + " biome palette bits want 0 got " + biomeBits, failures);
                expect(biomeId == 1, "roundtrip: tier " + distinct
                        + " biome id want 1 got " + biomeId, failures);
                expect(buf.readableBytes() == 0,
                        "roundtrip: tier " + distinct + " leaves "
                                + buf.readableBytes() + " unread bytes", failures);

                int mismatches = 0;
                String first = null;
                for (int i = 0; i < 4096; i++) {
                    // Strategy.getIndex(x, y, z) is (y<<8)|(z<<4)|x,
                    // the same order sectionBytes packs.
                    BlockState got = container.get(i & 15, (i >> 8) & 15, (i >> 4) & 15);
                    BlockState want = cells[i];
                    if (!got.equals(want)) {
                        mismatches++;
                        if (first == null) {
                            first = "cell " + i + " want " + want + " got " + got;
                        }
                    }
                }
                expect(mismatches == 0, "roundtrip: tier " + distinct + " has "
                        + mismatches + " wrong cells, first: " + first, failures);
            }
        } catch (Exception e) {
            failures.add("roundtrip: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    // ---- 4+5: live loopback join + movement ----

    /** Minimal loopback client speaking the full offline handshake. */
    static final class Loopback {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final byte[] buf = new byte[2 << 20];
        int len;
        int threshold = -1;
        int playTeleportId = -1;
        /** Every PLAY_CHUNK ever read off this socket (cumulative). */
        int chunksSeen;
        /** Every packet read during the join follow-up drain (stage 1 + 2). */
        final List<Wire.Inbound> joinSeen = new ArrayList<>();

        Loopback(int port) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(2000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        void send(int id, byte[] payload) throws Exception {
            out.write(Wire.frame(id, payload, -1));
            out.flush();
        }

        void sendC(int id, byte[] payload) throws Exception {
            out.write(Wire.frame(id, payload, threshold));
            out.flush();
        }

        Wire.Inbound read() throws Exception {
            while (true) {
                boolean decoded = false;
                Wire.Inbound result = null;
                try {
                    int[] l = Wire.readVarInt(buf, 0);
                    if (len >= l[1] + l[0]) {
                        byte[] frame = new byte[l[0]];
                        System.arraycopy(buf, l[1], frame, 0, l[0]);
                        int rest = len - l[1] - l[0];
                        System.arraycopy(buf, l[1] + l[0], buf, 0, rest);
                        len = rest;
                        result = Wire.decode(Wire.unframe(frame, threshold));
                        decoded = true;
                    }
                } catch (java.io.IOException e) {
                    // Truncated header or incomplete frame: read more.
                    // NOTE: readVarInt on an empty buffer throws rather
                    // than returning null, so the socket read below is
                    // the only path forward; never spin without it.
                }
                if (decoded) {
                    if (result.id() == PacketIds.PLAY_CHUNK) {
                        chunksSeen++;
                    } else if (result.id() == PacketIds.PLAY_CHUNK_BATCH_FINISHED) {
                        // A real 26.2 client acknowledges only after it has
                        // incorporated the batch. Keep the loopback clients
                        // protocol-correct so the server's in-flight batch
                        // gate can be exercised by every streaming check.
                        sendC(PacketIds.PLAY_CHUNK_BATCH_RECEIVED,
                                new Wire.Writer().f(64.0f).toBytes());
                    }
                    return result;
                }
                int n = in.read(buf, len, buf.length - len);
                if (n < 0) {
                    throw new java.io.IOException("server closed");
                }
                len += n;
            }
        }
    }

    private static void checkLoopback(List<String> failures) {
        NetServer server = null;
        try {
            // Corpus: stone floor + wire line so spawn lookup finds ground.
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    loop.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            server = new NetServer(loop, 0, 256, 4242424242L);
            server.start();
            int port = server.boundPort();
            expect(port > 0, "loopback: server bound", failures);
            ChunkSender sender = new ChunkSender(loop, plainsId());

            Loopback client = new Loopback(port);
            // Handshake (intent login) + hello.
            Wire.Writer hs = new Wire.Writer();
            hs.vint(776).utf("127.0.0.1").s(port).vint(2);
            client.send(0x00, hs.toBytes());
            Wire.Writer hello = new Wire.Writer();
            hello.utf("LoopBot");
            hello.uuid(UUID.randomUUID());
            client.send(0x00, hello.toBytes());

            // Tick the world while the handshake proceeds on the I/O thread.
            boolean compression = false;
            boolean finished = false;
            boolean inConfig = false;
            boolean joined = false;
            boolean teleported = false;
            int chunks = 0;
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline && !(joined && teleported)) {
                KestrelServer.runIteration(server, loop, sender);
                Wire.Inbound in;
                try {
                    client.socket.setSoTimeout(100);
                    in = client.read();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (!compression && in.id() == PacketIds.LOGIN_COMPRESSION) {
                    int t = in.reader().varInt();
                    expect(t == 256, "loopback: compression threshold 256", failures);
                    client.threshold = t;
                    compression = true;
                } else if (!finished && in.id() == PacketIds.LOGIN_FINISHED) {
                    finished = true;
                    client.sendC(PacketIds.LOGIN_ACK, new byte[0]);
                } else if (!inConfig && in.id() == PacketIds.CONFIG_SELECT_KNOWN_OUT) {
                    inConfig = true;
                    // Echo empty packs + finish config.
                    Wire.Writer reply = new Wire.Writer();
                    reply.vint(0);
                    client.sendC(PacketIds.CONFIG_SELECT_KNOWN, reply.toBytes());
                    client.sendC(PacketIds.CONFIG_FINISH, new byte[0]);
                } else if (in.id() == PacketIds.PLAY_JOIN) {
                    joined = true;
                } else if (in.id() == PacketIds.PLAY_CHUNK) {
                    chunks++;
                } else if (in.id() == PacketIds.PLAY_POSITION) {
                    Wire.Reader r = in.reader();
                    client.playTeleportId = r.varInt();
                    teleported = true;
                    // Accept the teleport.
                    Wire.Writer ack = new Wire.Writer();
                    ack.vint(client.playTeleportId);
                    client.sendC(PacketIds.PLAY_ACCEPT_TELEPORT, ack.toBytes());
                    // Send player-loaded + a movement packet.
                    client.sendC(PacketIds.PLAY_PLAYER_LOADED, new byte[0]);
                    Wire.Writer mv = new Wire.Writer();
                    mv.d(0.5).d(2.0).d(0.5).f(90.0f).f(0.0f).b(1);
                    client.sendC(PacketIds.PLAY_MOVE_POSROT, mv.toBytes());
                }
            }
            expect(finished, "loopback: login finished received", failures);
            expect(inConfig, "loopback: config entered", failures);
            expect(joined, "loopback: play join received", failures);

            // Let chunks stream + movement apply.
            for (int i = 0; i < 60; i++) {
                KestrelServer.runIteration(server, loop, sender);
                try {
                    client.socket.setSoTimeout(50);
                    while (true) {
                        Wire.Inbound in = client.read();
                        if (in.id() == PacketIds.PLAY_CHUNK) {
                            chunks++;
                        } else if (in.id() == PacketIds.PLAY_POSITION
                                && client.playTeleportId < 0) {
                            Wire.Reader r = in.reader();
                            client.playTeleportId = r.varInt();
                            Wire.Writer ack = new Wire.Writer();
                            ack.vint(client.playTeleportId);
                            client.sendC(PacketIds.PLAY_ACCEPT_TELEPORT, ack.toBytes());
                            // First position sync: accept, announce loaded,
                            // then drive one movement (the join drain exits
                            // on JOIN, so its POSITION branch is unreachable;
                            // the move MUST be sent here).
                            client.sendC(PacketIds.PLAY_PLAYER_LOADED, new byte[0]);
                            Wire.Writer mv = new Wire.Writer();
                            mv.d(0.5).d(2.0).d(0.5).f(90.0f).f(0.0f).b(1);
                            client.sendC(PacketIds.PLAY_MOVE_POSROT, mv.toBytes());
                        } else if (in.id() == PacketIds.PLAY_KEEPALIVE_OUT) {
                            long id = in.reader().l();
                            Wire.Writer reply = new Wire.Writer();
                            reply.l(id);
                            client.sendC(PacketIds.PLAY_KEEPALIVE_IN, reply.toBytes());
                        }
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // drained
                }
            }
            expect(chunks > 0, "loopback: chunks streamed (" + chunks + ")", failures);
            expect(server.players.size() == 1,
                    "loopback: server tracks one player, got " + server.players.size(),
                    failures);
            KestrelPlayer p = server.players.values().iterator().next();
            expect(Math.abs(p.x - 0.5) < 0.01 && Math.abs(p.z - 0.5) < 0.01,
                    "loopback: movement applied, pos=" + p.x + "," + p.y + "," + p.z,
                    failures);
            expect(p.yaw == 90.0f, "loopback: yaw applied, got " + p.yaw, failures);
            client.socket.close();
        } catch (Exception e) {
            failures.add("loopback: threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    static int plainsId() {
        return 1;
    }

    /** Packet order observed through a real joined session and one live iteration. */
    public record InventoryOrderProbe(List<Integer> joinPackets,
            List<Integer> handlingIteration, List<Integer> deferredIteration) {}

    /**
     * G17 behavioural ordering probe. It uses the same socket session and
     * {@link KestrelServer#runIteration} entry point as the live server.
     */
    public static InventoryOrderProbe inventoryOrderProbe() throws Exception {
        NetServer server = null;
        Loopback client = null;
        try {
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            for (int x = -32; x <= 32; x++) {
                for (int z = -8; z <= 8; z++) {
                    loop.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            server = new NetServer(loop, 0, 256, 4242424242L);
            server.start();
            NetServer runningServer = server;
            ChunkSender sender = new ChunkSender(loop, plainsId());
            client = joinLoopback(loop, server, sender, "InvOrderBot", 2);
            if (client.playTeleportId < 0 || server.players.size() != 1) {
                throw new IllegalStateException("ordering probe did not complete join");
            }

            List<Integer> joinPackets = client.joinSeen.stream().map(Wire.Inbound::id).toList();
            // Drain join stragglers so the next capture contains exactly the
            // output caused by the following live iteration.
            pump(loop, server, sender, client, 2);

            var tracked = server.entityWorld().spawnItem(
                    playerX(server), playerY(server), playerZ(server) + 2.0,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND),
                    0.0, 0.0, 0.0);
            // Pairing is synchronous when the entity is added. Flush and
            // drain it before arranging the movement used by the measured
            // iteration.
            pump(loop, server, sender, client, 2);
            if (server.entityTracker().seenBy(tracked.getId()).size() != 1) {
                throw new IllegalStateException("ordering probe entity is not tracked by its session");
            }
            tracked.setPos(tracked.position().x + 0.25,
                    tracked.position().y, tracked.position().z);
            tracked.needsSync = true;
            tracked.setNoGravity(true);

            KestrelPlayer player = server.players.values().iterator().next();
            bindItemPrototypes();
            player.inventory.inventory().setItem(0,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 2));
            player.inventory.inventory().setItem(40,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));

            client.sendC(PacketIds.PLAY_USE_ITEM_ON,
                    useItemOn(0, new BlockPos(3, 0, 3), 1, 77));
            client.sendC(PacketIds.PLAY_PLAYER_ACTION,
                    new Wire.Writer().vint(6).l(BlockPos.ZERO.asLong()).b(0).vint(0).toBytes());
            if (!waitFor(() -> runningServer.events.size() >= 2, 5000)) {
                throw new IllegalStateException("ordering probe inbound actions did not arrive");
            }

            KestrelServer.runIteration(server, loop, sender);
            List<Integer> handling = captureOrderingPackets(client);
            KestrelServer.runIteration(server, loop, sender);
            List<Integer> deferred = captureOrderingPackets(client);
            System.out.println("  live-order handling ids " + handling);
            System.out.println("  live-order deferred ids " + deferred);
            return new InventoryOrderProbe(joinPackets, handling, deferred);
        } finally {
            if (client != null) {
                client.socket.close();
            }
            if (server != null) {
                server.close();
            }
        }
    }

    private static List<Integer> captureOrderingPackets(Loopback client) throws Exception {
        List<Wire.Inbound> seen = new ArrayList<>();
        try {
            client.socket.setSoTimeout(250);
            while (true) {
                seen.add(client.read());
            }
        } catch (java.net.SocketTimeoutException expected) {
            // The iteration has flushed completely.
        }
        return seen.stream().map(Wire.Inbound::id)
                .filter(id -> id == PacketIds.PLAY_BLOCK_UPDATE
                        || id == PacketIds.PLAY_SECTION_BLOCKS_UPDATE
                        || id == PacketIds.PLAY_CONTAINER_SET_SLOT
                        || id == PacketIds.PLAY_MOVE_ENTITY_POS
                        || id == PacketIds.PLAY_MOVE_ENTITY_POS_ROT
                        || id == PacketIds.PLAY_ENTITY_POSITION_SYNC
                        || id == PacketIds.PLAY_BLOCK_CHANGED_ACK)
                .toList();
    }

    private static double playerX(NetServer server) {
        return server.players.values().iterator().next().x;
    }

    private static double playerZ(NetServer server) {
        return server.players.values().iterator().next().z;
    }

    private static double playerY(NetServer server) {
        return server.players.values().iterator().next().y;
    }

    // ---- 8: block placing over loopback ----

    /** UseItemOn payload in the 26.2 codec order (cursor at the face center). */
    static byte[] useItemOn(int hand, BlockPos pos, int face, int sequence) {
        Wire.Writer w = new Wire.Writer();
        w.vint(hand).l(pos.asLong()).vint(face)
                .f(0.5f).f(1.0f).f(0.5f).bool(false).bool(false)
                .vint(sequence);
        return w.toBytes();
    }

    /**
     * Tick the loop and drain client packets for a number of ticks,
     * answering the first position sync and keepalives. Returns every
     * packet received.
     */
    static List<Wire.Inbound> pump(TickLoop loop, NetServer server, ChunkSender sender,
            Loopback client, int ticks) throws Exception {
        List<Wire.Inbound> seen = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            // Let the I/O thread deliver what the client sent before the
            // tick drains the queue: without this, a sendC+pump(1) pair
            // races the socket read and every second move lands a tick
            // late (each pollEvents drains the queue one move behind).
            drainInbound(server);
            KestrelServer.runIteration(server, loop, sender);
            try {
                client.socket.setSoTimeout(50);
                while (true) {
                    Wire.Inbound in = client.read();
                    seen.add(in);
                    if (in.id() == PacketIds.PLAY_POSITION && client.playTeleportId < 0) {
                        client.playTeleportId = in.reader().varInt();
                        client.sendC(PacketIds.PLAY_ACCEPT_TELEPORT,
                                new Wire.Writer().vint(client.playTeleportId).toBytes());
                        client.sendC(PacketIds.PLAY_PLAYER_LOADED, new byte[0]);
                    } else if (in.id() == PacketIds.PLAY_KEEPALIVE_OUT) {
                        client.sendC(PacketIds.PLAY_KEEPALIVE_IN,
                                new Wire.Writer().l(in.reader().l()).toBytes());
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                // drained
            }
        }
        return seen;
    }

    /**
     * Wait until the server's inbound event queue holds an event or the
     * deadline passes. Test-only: the I/O thread delivers socket bytes
     * asynchronously, and pump(1) would otherwise race it.
     */
    static void drainInbound(NetServer server) throws Exception {
        long deadline = System.currentTimeMillis() + 500;
        while (server.events.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    /** True when a BlockUpdate for pos carrying state arrived. */
    private static boolean sawBlockUpdate(List<Wire.Inbound> seen, BlockPos pos, BlockState state)
            throws Exception {
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_BLOCK_UPDATE) {
                Wire.Reader r = in.reader();
                if (r.l() == pos.asLong() && r.varInt() == Block.getId(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when a BlockChangedAck for sequence arrived. */
    private static boolean sawAck(List<Wire.Inbound> seen, int sequence) throws Exception {
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_BLOCK_CHANGED_ACK && in.reader().varInt() == sequence) {
                return true;
            }
        }
        return false;
    }

    /** Bind every unbound item to vanilla's default prototype, as the item checks do. */
    static void bindItemPrototypes() {
        net.minecraft.core.registries.BuiltInRegistries.ITEM.listElements().forEach(h -> {
            if (!h.areComponentsBound()) {
                h.bindComponents(net.minecraft.core.component.DataComponents.COMMON_ITEM_COMPONENTS);
            }
        });
    }

    /** Put {@code stack} in the player's selected hotbar slot. */
    static void hold(KestrelPlayer player, net.minecraft.world.item.Item item, int count) {
        bindItemPrototypes();
        player.inventory.inventory().setItem(player.inventory.selectedSlot(),
                new net.minecraft.world.item.ItemStack(item, count));
    }

    /** The item count in the player's selected slot. */
    static int heldCount(KestrelPlayer player) {
        return player.inventory.inventory().getItem(player.inventory.selectedSlot()).getCount();
    }

    private static void checkPlace(List<String> failures) {
        NetServer server = null;
        try {
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState air = Blocks.AIR.defaultBlockState();

            // The test payload builder must match vanilla's codec byte
            // for byte: decode through the real serverbound codec.
            byte[] probe = useItemOn(0, new BlockPos(3, 0, 3), 1, 11);
            net.minecraft.network.FriendlyByteBuf probeBuf = new net.minecraft.network.FriendlyByteBuf(
                    io.netty.buffer.Unpooled.wrappedBuffer(probe));
            net.minecraft.network.protocol.game.ServerboundUseItemOnPacket decoded =
                    net.minecraft.network.protocol.game.ServerboundUseItemOnPacket.STREAM_CODEC
                            .decode(probeBuf);
            expect(decoded.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                            && decoded.getHitResult().getBlockPos().equals(new BlockPos(3, 0, 3))
                            && decoded.getHitResult().getDirection() == net.minecraft.core.Direction.UP
                            && decoded.getSequence() == 11
                            && probeBuf.readableBytes() == 0,
                    "place: UseItemOn payload decodes through vanilla codec", failures);
            int[] vanillaUseItemOn = {-1};
            ((net.minecraft.network.ProtocolInfo.DetailsProvider)
                    net.minecraft.network.protocol.game.GameProtocols.SERVERBOUND_TEMPLATE)
                    .details().listPackets((t, id) -> {
                        if (t.equals(net.minecraft.network.protocol.game.GamePacketTypes
                                .SERVERBOUND_USE_ITEM_ON)) {
                            vanillaUseItemOn[0] = id;
                        }
                    });
            expect(vanillaUseItemOn[0] == PacketIds.PLAY_USE_ITEM_ON,
                    "place: use_item_on id matches vanilla, got " + vanillaUseItemOn[0], failures);

            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    loop.setBlock(new BlockPos(x, 0, z), stone, 3);
                }
            }
            server = new NetServer(loop, 0, 256, 4242424242L);
            server.start();
            int port = server.boundPort();
            ChunkSender sender = new ChunkSender(loop, plainsId());

            Loopback client = new Loopback(port);
            Wire.Writer hs = new Wire.Writer();
            hs.vint(776).utf("127.0.0.1").s(port).vint(2);
            client.send(0x00, hs.toBytes());
            client.send(0x00, new Wire.Writer().utf("PlaceBot").uuid(UUID.randomUUID()).toBytes());
            boolean joined = false;
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline && !joined) {
                KestrelServer.runIteration(server, loop, sender);
                Wire.Inbound in;
                try {
                    client.socket.setSoTimeout(100);
                    in = client.read();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (in.id() == PacketIds.LOGIN_COMPRESSION && client.threshold < 0) {
                    client.threshold = in.reader().varInt();
                } else if (in.id() == PacketIds.LOGIN_FINISHED) {
                    client.sendC(PacketIds.LOGIN_ACK, new byte[0]);
                } else if (in.id() == PacketIds.CONFIG_SELECT_KNOWN_OUT) {
                    client.sendC(PacketIds.CONFIG_SELECT_KNOWN, new Wire.Writer().vint(0).toBytes());
                    client.sendC(PacketIds.CONFIG_FINISH, new byte[0]);
                } else if (in.id() == PacketIds.PLAY_JOIN) {
                    joined = true;
                }
            }
            expect(joined, "place: play join received", failures);
            pump(loop, server, sender, client, 20);
            expect(server.players.size() == 1, "place: server tracks the placer", failures);
            KestrelPlayer p = server.players.values().iterator().next();
            hold(p, net.minecraft.world.item.Items.STONE, 64);

            // Accepted: click the floor's top face away from the player.
            BlockPos placed = new BlockPos(3, 1, 3);
            long writesBefore = loop.setBlockCount();
            client.sendC(PacketIds.PLAY_USE_ITEM_ON, useItemOn(0, new BlockPos(3, 0, 3), 1, 1));
            List<Wire.Inbound> seen = pump(loop, server, sender, client, 10);
            expect(loop.getBlockState(placed) == stone,
                    "place: block landed in store, got " + loop.getBlockState(placed), failures);
            expect(heldCount(p) == 63, "place: one stone consumed, got " + heldCount(p), failures);
            expect(loop.setBlockCount() > writesBefore, "place: store write counted", failures);
            expect(sawBlockUpdate(seen, placed, stone),
                    "place: BlockUpdate echo arrived", failures);
            expect(sawAck(seen, 1), "place: BlockChangedAck(1) arrived", failures);

            // Invalid face: absorbed, acked, no write, session alive.
            writesBefore = loop.setBlockCount();
            client.sendC(PacketIds.PLAY_USE_ITEM_ON, useItemOn(0, new BlockPos(4, 0, 4), 9, 2));
            seen = pump(loop, server, sender, client, 10);
            expect(loop.setBlockCount() == writesBefore,
                    "place: invalid face causes no store write", failures);
            expect(loop.getBlockState(new BlockPos(4, 1, 4)) == air
                            && loop.getBlockState(new BlockPos(4, 0, 4)) == stone,
                    "place: invalid face leaves state unchanged", failures);
            expect(sawAck(seen, 2), "place: invalid face still acked (packet parsed)", failures);
            expect(server.players.size() == 1, "place: invalid face keeps the session", failures);

            // Solid target: clicked face neighbor already stone, absorbed
            // with the rollback updates sent to the placer.
            BlockPos solid = new BlockPos(4, 1, 2);
            loop.setBlock(solid, Blocks.GRANITE.defaultBlockState(), 3);
            writesBefore = loop.setBlockCount();
            client.sendC(PacketIds.PLAY_USE_ITEM_ON, useItemOn(0, new BlockPos(4, 0, 2), 1, 3));
            seen = pump(loop, server, sender, client, 10);
            expect(loop.setBlockCount() == writesBefore
                            && loop.getBlockState(solid) == Blocks.GRANITE.defaultBlockState(),
                    "place: solid target not overwritten", failures);
            expect(sawBlockUpdate(seen, solid, Blocks.GRANITE.defaultBlockState()) && sawAck(seen, 3),
                    "place: solid target rollback update + ack arrived", failures);

            // Player hitbox: the block under the player's feet, top face.
            BlockPos feet = new BlockPos((int) Math.floor(p.x), (int) Math.floor(p.y),
                    (int) Math.floor(p.z));
            BlockState feetBefore = loop.getBlockState(feet);
            writesBefore = loop.setBlockCount();
            client.sendC(PacketIds.PLAY_USE_ITEM_ON, useItemOn(0, feet.below(), 1, 4));
            seen = pump(loop, server, sender, client, 10);
            expect(feetBefore.canBeReplaced() && loop.setBlockCount() == writesBefore
                            && loop.getBlockState(feet) == feetBefore,
                    "place: target inside the player box absorbed, feet=" + feet
                            + " state=" + loop.getBlockState(feet), failures);
            expect(sawAck(seen, 4), "place: hitbox placement acked", failures);

            // Replaceable clicked block (short grass) is replaced in place.
            // Flags 18 skip shape updates so the grass survives on stone.
            BlockPos grass = new BlockPos(2, 1, 4);
            loop.setBlock(grass, Blocks.SHORT_GRASS.defaultBlockState(), 18);
            client.sendC(PacketIds.PLAY_USE_ITEM_ON, useItemOn(0, grass, 1, 5));
            seen = pump(loop, server, sender, client, 10);
            expect(loop.getBlockState(grass) == stone
                            && loop.getBlockState(grass.above()) == air,
                    "place: replaceable clicked block replaced in place", failures);
            expect(sawBlockUpdate(seen, grass, stone),
                    "place: in-place replacement echoed", failures);
            client.socket.close();
        } catch (Exception e) {
            failures.add("place: threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    // ---- 9: day time + weather over loopback ----

    /** Join a loopback client through play (offline handshake, empty packs). */
    static Loopback joinLoopback(TickLoop loop, NetServer server, ChunkSender sender,
            String name) throws Exception {
        return joinLoopbackPort(loop, server, sender, server.boundPort(), name, 10);
    }

    /** Join a loopback client through play with a specific view distance. */
    static Loopback joinLoopback(TickLoop loop, NetServer server, ChunkSender sender,
            String name, int viewDistance) throws Exception {
        return joinLoopbackPort(loop, server, sender, server.boundPort(), name, viewDistance);
    }

    /** Join a loopback client on an explicit port (two-client groups need it). */
    static Loopback joinLoopbackPort(TickLoop loop, NetServer server, ChunkSender sender,
            int port, String name) throws Exception {
        return joinLoopbackPort(loop, server, sender, port, name, 10);
    }

    /** Join a loopback client on an explicit port with a specific view distance. */
    static Loopback joinLoopbackPort(TickLoop loop, NetServer server, ChunkSender sender,
            int port, String name, int viewDistance) throws Exception {
        Loopback client = new Loopback(port);
        client.send(0x00, new Wire.Writer().vint(776).utf("127.0.0.1").s(port).vint(2).toBytes());
        client.send(0x00, new Wire.Writer().utf(name).uuid(UUID.randomUUID()).toBytes());
        long deadline = System.currentTimeMillis() + 30000;
        boolean joined = false;
        while (System.currentTimeMillis() < deadline && !joined) {
            KestrelServer.runIteration(server, loop, sender);
            Wire.Inbound in;
            try {
                client.socket.setSoTimeout(100);
                in = client.read();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (in.id() == PacketIds.LOGIN_COMPRESSION && client.threshold < 0) {
                client.threshold = in.reader().varInt();
            } else if (in.id() == PacketIds.LOGIN_FINISHED) {
                client.sendC(PacketIds.LOGIN_ACK, new byte[0]);
            } else if (in.id() == PacketIds.CONFIG_SELECT_KNOWN_OUT) {
                if (viewDistance != 10) {
                    Wire.Writer ci = new Wire.Writer();
                    ci.utf("en_us").b(viewDistance);
                    client.sendC(PacketIds.CONFIG_CLIENT_INFO, ci.toBytes());
                }
                client.sendC(PacketIds.CONFIG_SELECT_KNOWN, new Wire.Writer().vint(0).toBytes());
                client.sendC(PacketIds.CONFIG_FINISH, new byte[0]);
            } else if (in.id() == PacketIds.PLAY_JOIN) {
                joined = true;
            }
        }
        // Two-stage join: the teleport lands on the tick after the join +
        // spawn chunks. Drain a bounded follow-up (same tick trio as the
        // live server) so the accept/loaded answers reach the server and
        // the player is placed before the caller proceeds.
        deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && client.playTeleportId < 0) {
            drainInbound(server);
            KestrelServer.runIteration(server, loop, sender);
            try {
                client.socket.setSoTimeout(100);
                while (true) {
                    Wire.Inbound in = client.read();
                    client.joinSeen.add(in);
                    if (in.id() == PacketIds.PLAY_POSITION && client.playTeleportId < 0) {
                        client.playTeleportId = in.reader().varInt();
                        client.sendC(PacketIds.PLAY_ACCEPT_TELEPORT,
                                new Wire.Writer().vint(client.playTeleportId).toBytes());
                        client.sendC(PacketIds.PLAY_PLAYER_LOADED, new byte[0]);
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                // keep draining until the teleport arrives or time runs out
            }
        }
        return client;
    }

    /** Overworld day times of every SetTime carrying overworld clock in arrival order, decoded by vanilla's codec. */
    private static List<Long> dayTimes(List<Wire.Inbound> seen) {
        List<Long> out = new ArrayList<>();
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_SET_TIME) {
                var pkt = (net.minecraft.network.protocol.game.ClientboundSetTimePacket)
                        PlayShapeCheck.decodePlay(in.id(), in.payload());
                long dt = PlayShapeCheck.overworldDayTime(pkt);
                if (dt >= 0) {
                    out.add(dt);
                }
            }
        }
        return out;
    }

    /** Decoded SetTime packets in arrival order. */
    private static List<net.minecraft.network.protocol.game.ClientboundSetTimePacket> setTimePackets(
            List<Wire.Inbound> seen) {
        List<net.minecraft.network.protocol.game.ClientboundSetTimePacket> out = new ArrayList<>();
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_SET_TIME) {
                out.add((net.minecraft.network.protocol.game.ClientboundSetTimePacket)
                        PlayShapeCheck.decodePlay(in.id(), in.payload()));
            }
        }
        return out;
    }

    /** Vanilla-decoded game events in arrival order. */
    private static List<net.minecraft.network.protocol.game.ClientboundGameEventPacket> gameEvents(
            List<Wire.Inbound> seen) {
        List<net.minecraft.network.protocol.game.ClientboundGameEventPacket> out = new ArrayList<>();
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_GAME_EVENT) {
                out.add((net.minecraft.network.protocol.game.ClientboundGameEventPacket)
                        PlayShapeCheck.decodePlay(in.id(), in.payload()));
            }
        }
        return out;
    }

    private static void checkTime(List<String> failures) {
        // (c) unit: the counter advances monotonically and raises no clock jump on rollover.
        WorldTime unit = new WorldTime(1L);
        unit.setDayTime(23999);
        unit.consumeClockJump();
        unit.tick();
        expect(unit.dayTime() == 24000 && !unit.consumeClockJump(),
                "time: counter advances 23999 -> 24000 without clock jump, got " + unit.dayTime(), failures);
        for (int i = 0; i < WorldTime.DAY_LENGTH; i++) {
            unit.tick();
        }
        expect(unit.dayTime() == 48000, "time: a full day of ticks advances to 48000, got " + unit.dayTime(),
                failures);

        NetServer server = null;
        try {
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    loop.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            server = new NetServer(loop, 0, 256, 4242424242L);
            server.start();
            ChunkSender sender = new ChunkSender(loop, plainsId());
            Loopback client = joinLoopback(loop, server, sender, "TimeBot");
            expect(client != null, "time: play join received", failures);
            if (client == null) {
                return;
            }

            // (b) advancing: the join burst carries full sync SetTime with all clocks
            // (PlayerList.java:654 sendLevelInfo, ServerClockManager.java:130 createFullSyncPacket);
            // periodic broadcast (MinecraftServer.java:1113/1179 forceGameTimeSynchronization,
            // tickCount % 20) adds periodic SetTime packets with gameTime advancing and empty clock maps.
            List<Wire.Inbound> timeSeen = new ArrayList<>(client.joinSeen);
            timeSeen.addAll(pump(loop, server, sender, client,
                    NetServer.TIME_SYNC_TICKS * 2 + 5));
            List<net.minecraft.network.protocol.game.ClientboundSetTimePacket> timePkts = setTimePackets(timeSeen);
            expect(timePkts.size() >= 3, "time: >= 3 SetTime packets observed, got " + timePkts.size(), failures);

            // Join packet is full sync: 2 clocks (overworld and the_end)
            if (!timePkts.isEmpty()) {
                var joinPkt = timePkts.get(0);
                expect(joinPkt.clockUpdates().size() == 2
                                && PlayShapeCheck.overworldDayTime(joinPkt) >= 0
                                && PlayShapeCheck.theEndTicks(joinPkt) >= 0,
                        "time: join SetTime carries both overworld and the_end clocks, got " + joinPkt.clockUpdates(), failures);
            }

            // Periodic packets carry empty clock maps (MinecraftServer.java:1179)
            for (int i = 1; i < timePkts.size(); i++) {
                expect(timePkts.get(i).clockUpdates().isEmpty(),
                        "time: periodic SetTime carries empty clock map, got " + timePkts.get(i).clockUpdates(), failures);
            }

            // GameTime advances across sync periods
            boolean increasing = timePkts.size() >= 3;
            for (int i = 1; i < timePkts.size(); i++) {
                increasing &= timePkts.get(i).gameTime() > timePkts.get(i - 1).gameTime();
            }
            expect(increasing, "time: strictly increasing gameTimes across sync periods, got " + timePkts, failures);
            for (int i = 1; i < timePkts.size(); i++) {
                // Vanilla MinecraftServer.tickChildren:1124/1185 sends overworld().getGameTime()
                // at tickCount % 20 == 0 before ServerLevel.tickTime:477 does gameTime + 1.
                // On a world where gameTime tracks tickCount, periodic gameTime is tickCount - 1
                // (e.g. 19, 39, 59, 79), so gameTime % 20 == 19.
                expect(timePkts.get(i).gameTime() % NetServer.TIME_SYNC_TICKS == NetServer.TIME_SYNC_TICKS - 1,
                        "time: periodic gameTime matches vanilla pre-increment timing (gameTime % 20 == 19), got "
                                + timePkts.get(i).gameTime(), failures);
            }
            if (timePkts.size() >= 3) {
                expect(timePkts.get(2).gameTime() - timePkts.get(1).gameTime() == NetServer.TIME_SYNC_TICKS,
                        "time: periodic sync interval is exactly TIME_SYNC_TICKS (20)", failures);
            }
            if (timePkts.size() >= 2) {
                expect(timePkts.get(timePkts.size() - 1).gameTime() - timePkts.get(0).gameTime()
                                >= NetServer.TIME_SYNC_TICKS * 2 - 1,
                        "time: observed gameTime advanced by >= 2 sync periods", failures);
            }

            // (c) loopback: set near the end of the day; the set broadcasts
            // at once, but the rollover at 24000 sends no extra wrap packet.
            server.time.setDayTime(23990);
            List<Long> days = dayTimes(pump(loop, server, sender, client, 15));
            int high = -1;
            for (int i = 0; i < days.size(); i++) {
                if (high < 0 && days.get(i) >= 23990) {
                    high = i;
                }
            }
            expect(high >= 0,
                    "time: loopback sees set time 2399x, got " + days, failures);
            expect(days.stream().allMatch(d -> d >= 23990),
                    "time: day times monotonically advance without wrapping, got " + days, failures);
            expect(!days.contains(0L),
                    "time: rollover sends no wrap packet to 0, got " + days, failures);

            // Weather: forced rain for 40 ticks rises to 0.4 (START at the
            // 0.2 crossing), then the timer flips it and the level falls
            // back through 0.2 (STOP) without another command.
            server.time.setRaining(true, 40);
            List<net.minecraft.network.protocol.game.ClientboundGameEventPacket> events =
                    gameEvents(pump(loop, server, sender, client, 100));
            int start = -1;
            int stop = -1;
            float peak = 0.0f;
            int levels = 0;
            for (int i = 0; i < events.size(); i++) {
                var ge = events.get(i);
                if (ge.getEvent() == net.minecraft.network.protocol.game.ClientboundGameEventPacket
                        .START_RAINING && start < 0) {
                    start = i;
                } else if (ge.getEvent() == net.minecraft.network.protocol.game
                        .ClientboundGameEventPacket.STOP_RAINING && stop < 0) {
                    stop = i;
                } else if (ge.getEvent() == net.minecraft.network.protocol.game
                        .ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
                    levels++;
                    peak = Math.max(peak, ge.getParam());
                }
            }
            expect(start >= 0 && stop > start,
                    "time: START_RAINING then STOP_RAINING when the rain timer ran out, start="
                            + start + " stop=" + stop, failures);
            expect(peak > 0.35f && peak < 0.45f && levels >= 40,
                    "time: rain level rose to ~0.4 in per-tick steps, peak=" + peak
                            + " levels=" + levels, failures);
            expect(!server.time.raining() && server.time.rainLevel() < 0.2f,
                    "time: weather cleared after the timer, level=" + server.time.rainLevel(),
                    failures);
            client.socket.close();
        } catch (Exception e) {
            failures.add("time: threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    // ---- 9: chat receive and broadcast over loopback ----

    /** Serverbound chat payload in the 26.2 codec order (unsigned). */
    static byte[] clientChat(String message) {
        Wire.Writer w = new Wire.Writer();
        w.utf(message);
        w.l(System.currentTimeMillis()); // timestamp Instant
        w.l(0L); // salt
        w.bool(false); // hasSignature = false
        w.vint(0); // offset = 0
        w.bytes(new byte[3]); // 20-bit fixed BitSet
        w.b(0); // checksum = 0
        return w.toBytes();
    }

    /** True when a SystemChat packet carrying expected text arrived. */
    static boolean sawSystemChat(List<Wire.Inbound> seen, String expected) {
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_SYSTEM_CHAT) {
                byte[] payload = in.payload();
                if (payload.length >= 3 && payload[0] == 8) {
                    int len = ((payload[1] & 0xFF) << 8) | (payload[2] & 0xFF);
                    if (payload.length >= 3 + len + 1) {
                        String text = new String(payload, 3, len, java.nio.charset.StandardCharsets.UTF_8);
                        boolean overlay = payload[3 + len] != 0;
                        if (text.equals(expected) && !overlay) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void pumpPair(TickLoop loop, NetServer server, ChunkSender sender,
            Loopback a, Loopback b, List<Wire.Inbound> seenA, List<Wire.Inbound> seenB,
            int ticks) throws Exception {
        for (int i = 0; i < ticks; i++) {
            KestrelServer.runIteration(server, loop, sender);
            drainLoopback(a, seenA);
            drainLoopback(b, seenB);
        }
    }

    private static void drainLoopback(Loopback client, List<Wire.Inbound> seen) throws Exception {
        try {
            client.socket.setSoTimeout(50);
            while (true) {
                Wire.Inbound in = client.read();
                seen.add(in);
                if (in.id() == PacketIds.PLAY_POSITION && client.playTeleportId < 0) {
                    client.playTeleportId = in.reader().varInt();
                    client.sendC(PacketIds.PLAY_ACCEPT_TELEPORT,
                            new Wire.Writer().vint(client.playTeleportId).toBytes());
                    client.sendC(PacketIds.PLAY_PLAYER_LOADED, new byte[0]);
                } else if (in.id() == PacketIds.PLAY_KEEPALIVE_OUT) {
                    client.sendC(PacketIds.PLAY_KEEPALIVE_IN,
                            new Wire.Writer().l(in.reader().l()).toBytes());
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            // drained
        }
    }

    private static void checkChat(List<String> failures) {
        NetServer server = null;
        try {
            // (a) Codec grounding checks: serverbound & clientbound shapes + IDs
            byte[] probe = clientChat("Grounding test");
            net.minecraft.network.FriendlyByteBuf probeBuf = new net.minecraft.network.FriendlyByteBuf(
                    io.netty.buffer.Unpooled.wrappedBuffer(probe));
            net.minecraft.network.protocol.game.ServerboundChatPacket decoded =
                    net.minecraft.network.protocol.game.ServerboundChatPacket.STREAM_CODEC.decode(probeBuf);
            expect(decoded.message().equals("Grounding test")
                            && decoded.signature() == null
                            && probeBuf.readableBytes() == 0,
                    "chat: ServerboundChatPacket decodes through vanilla codec", failures);

            int[] vanillaChat = {-1};
            int[] vanillaChatCommand = {-1};
            ((net.minecraft.network.ProtocolInfo.DetailsProvider)
                    net.minecraft.network.protocol.game.GameProtocols.SERVERBOUND_TEMPLATE)
                    .details().listPackets((t, id) -> {
                        if (t.equals(net.minecraft.network.protocol.game.GamePacketTypes.SERVERBOUND_CHAT)) {
                            vanillaChat[0] = id;
                        } else if (t.equals(net.minecraft.network.protocol.game.GamePacketTypes.SERVERBOUND_CHAT_COMMAND)) {
                            vanillaChatCommand[0] = id;
                        }
                    });
            expect(vanillaChat[0] == PacketIds.PLAY_CHAT,
                    "chat: serverbound chat id matches vanilla (" + vanillaChat[0] + ")", failures);
            expect(vanillaChatCommand[0] == PacketIds.PLAY_CHAT_COMMAND,
                    "chat: serverbound chat command id matches vanilla (" + vanillaChatCommand[0] + ")", failures);

            Outbound.Packet outPkt = Outbound.systemChat("<Alice> Hello world!");
            net.minecraft.network.RegistryFriendlyByteBuf outBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.wrappedBuffer(outPkt.payload()), net.minecraft.core.RegistryAccess.EMPTY);
            net.minecraft.network.protocol.game.ClientboundSystemChatPacket decSys =
                    net.minecraft.network.protocol.game.ClientboundSystemChatPacket.STREAM_CODEC.decode(outBuf);
            expect(decSys.content().getString().equals("<Alice> Hello world!")
                            && !decSys.overlay()
                            && outBuf.readableBytes() == 0,
                    "chat: Outbound.systemChat decodes through ClientboundSystemChatPacket.STREAM_CODEC", failures);

            int[] vanillaSysChat = {-1};
            ((net.minecraft.network.ProtocolInfo.DetailsProvider)
                    net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE)
                    .details().listPackets((t, id) -> {
                        if (t.equals(net.minecraft.network.protocol.game.GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT)) {
                            vanillaSysChat[0] = id;
                        }
                    });
            expect(vanillaSysChat[0] == PacketIds.PLAY_SYSTEM_CHAT,
                    "chat: clientbound system_chat id matches vanilla (" + vanillaSysChat[0] + ")", failures);

            // (b) Loopback join with two clients: Client A sends chat, BOTH A and B receive broadcast
            BlockState stone = Blocks.STONE.defaultBlockState();
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    loop.setBlock(new BlockPos(x, 0, z), stone, 3);
                }
            }
            server = new NetServer(loop, 0, 256, 4242424242L);
            server.start();
            int port = server.boundPort();
            ChunkSender sender = new ChunkSender(loop, plainsId());

            Loopback clientA = joinLoopbackPort(loop, server, sender, port, "ChatBotA");
            Loopback clientB = joinLoopbackPort(loop, server, sender, port, "ChatBotB");

            List<Wire.Inbound> seenA = new ArrayList<>();
            List<Wire.Inbound> seenB = new ArrayList<>();
            pumpPair(loop, server, sender, clientA, clientB, seenA, seenB, 20);

            expect(server.players.size() == 2,
                    "chat: both players joined and tracked (got " + server.players.size() + ")", failures);

            // Client A sends chat message
            seenA.clear();
            seenB.clear();
            clientA.sendC(PacketIds.PLAY_CHAT, clientChat("Hello from A!"));
            pumpPair(loop, server, sender, clientA, clientB, seenA, seenB, 10);

            boolean aReceived = sawSystemChat(seenA, "<ChatBotA> Hello from A!");
            boolean bReceived = sawSystemChat(seenB, "<ChatBotA> Hello from A!");
            expect(aReceived, "chat: sender A received chat broadcast", failures);
            expect(bReceived, "chat: peer B received chat broadcast (proves broadcast, not echo)", failures);

            // (d) Negative control: verify B received the message sent by A
            // (If broadcast were disabled or only echoed to sender, B would have received nothing).
            expect(bReceived && !seenB.isEmpty(),
                    "chat: negative control - broadcast reached peer B with non-empty packets", failures);

            // (c) Empty message absorbed (no broadcast, session stays connected)
            seenA.clear();
            seenB.clear();
            clientA.sendC(PacketIds.PLAY_CHAT, clientChat(""));
            pumpPair(loop, server, sender, clientA, clientB, seenA, seenB, 10);
            expect(!sawSystemChat(seenA, "") && !sawSystemChat(seenB, ""),
                    "chat: empty message absorbed, no broadcast sent", failures);
            expect(server.players.size() == 2,
                    "chat: session stays connected after empty message", failures);

            // Overlong message (>256 chars) absorbed (no broadcast, session stays connected)
            seenA.clear();
            seenB.clear();
            String overlong = "x".repeat(300);
            clientA.sendC(PacketIds.PLAY_CHAT, clientChat(overlong));
            pumpPair(loop, server, sender, clientA, clientB, seenA, seenB, 10);
            expect(!sawSystemChat(seenA, "<ChatBotA> " + overlong)
                            && !sawSystemChat(seenB, "<ChatBotA> " + overlong),
                    "chat: overlong message (>256) absorbed, no broadcast sent", failures);
            expect(server.players.size() == 2,
                    "chat: session stays connected after overlong message", failures);

            // (4) Chat command absorbed explicitly (no command execution yet)
            clientA.sendC(PacketIds.PLAY_CHAT_COMMAND, new Wire.Writer().utf("msg ChatBotB hello").toBytes());
            pumpPair(loop, server, sender, clientA, clientB, seenA, seenB, 10);
            expect(server.players.size() == 2,
                    "chat: chat command absorbed, session stays connected", failures);

            clientA.socket.close();
            clientB.socket.close();
        } catch (Exception e) {
            failures.add("chat: threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    // ---- 11: G4 save everything (clock, weather, player pos, block diffs) ----

    /** Stone 17x17 platform at y=0 (spawn resolves to 0.5, 1.0, 0.5). */
    private static TickLoop platformLoop() {
        TickLoop loop = new TickLoop(new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                loop.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        return loop;
    }

    /** First PlayerPosition in arrival order as {x, y, z, yaw, pitch}, null when none. */
    private static double[] firstPosition(List<Wire.Inbound> seen) throws Exception {
        for (Wire.Inbound in : seen) {
            if (in.id() == PacketIds.PLAY_POSITION) {
                Wire.Reader r = in.reader();
                r.varInt();
                double x = r.d();
                double y = r.d();
                double z = r.d();
                r.d();
                r.d();
                r.d();
                return new double[] {x, y, z, r.f(), r.f()};
            }
        }
        return null;
    }

    private static void deleteTree(java.nio.file.Path root) {
        try (var walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(p);
            }
        } catch (Exception ignored) {
        }
    }

    private static void checkPersist(List<String> failures) {
        java.nio.file.Path root = null;
        try {
            root = java.nio.file.Files.createTempDirectory("kestrel-g4");
            checkPersistUnit(root.resolve("unit"), failures);
            java.nio.file.Path world = root.resolve("world");
            checkPersistLoopback(world, failures);
            checkPersistDefaults(root.resolve("empty"), world, failures);
        } catch (Exception e) {
            failures.add("persist: threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (root != null) {
                deleteTree(root);
            }
        }
    }

    /** (a) canned state writes and reads back equal, including the WorldTime seam. */
    private static void checkPersistUnit(java.nio.file.Path dir, List<String> failures)
            throws Exception {
        StateStore.LevelState level = new StateStore.LevelState(987654321L, 12345, true, 777, 0.37f);
        UUID id = Wire.offlineUuid("Unit Bot");
        StateStore.PlayerState player = new StateStore.PlayerState(id, "Unit Bot=é",
                3.25, 64.000000001, -7.1, 45.5f, -12.25f, 4);
        StateStore.writeLevel(dir, level);
        StateStore.writePlayer(dir, player);
        expect(level.equals(StateStore.readLevel(dir)),
                "persist unit: level round-trips, got " + StateStore.readLevel(dir), failures);
        expect(player.equals(StateStore.readPlayer(dir, id)),
                "persist unit: player round-trips, got " + StateStore.readPlayer(dir, id), failures);
        List<StateStore.PlayerState> all = StateStore.readAllPlayers(dir);
        expect(all.size() == 1 && all.get(0).equals(player),
                "persist unit: player dir lists the one player, got " + all, failures);

        // Overwrite replaces (temp + move), never appends or keeps the old value.
        StateStore.LevelState later = new StateStore.LevelState(987654400L, 0, false, 0, 0.0f);
        StateStore.writeLevel(dir, later);
        expect(later.equals(StateStore.readLevel(dir)),
                "persist unit: second write replaces the first, got " + StateStore.readLevel(dir),
                failures);

        WorldTime wt = new WorldTime(1L);
        wt.restore(level.dayTime(), level.raining(), level.rainTime(), level.rainLevel());
        expect(wt.dayTime() == 12345 && wt.raining() && wt.rainTime() == 777
                        && wt.rainLevel() == 0.37f && wt.rainVisible(),
                "persist unit: WorldTime.restore applies every field", failures);
        expect(!wt.consumeClockJump() && !wt.rainStarted() && !wt.rainLevelChanged(),
                "persist unit: restore raises no clock jump or rain edge", failures);
    }

    /** (b) set time, move, save, reboot a fresh server on the same dir, rejoin: restored. */
    private static void checkPersistLoopback(java.nio.file.Path world, List<String> failures)
            throws Exception {
        final long dayN = 12345;
        final double mx = 3.5;
        final double my = 1.0;
        final double mz = -2.5;
        final float myaw = 45.0f;
        final float mpitch = 10.0f;
        final BlockPos marker = new BlockPos(5, 1, 5);
        final long seed = 4242424242L;
        UUID profile = Wire.offlineUuid("PersistBot");
        StateStore.LevelState expected;
        long savedGameTime;

        NetServer server = null;
        try {
            TickLoop loop = platformLoop();
            loop.setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            server = new NetServer(loop, 0, 256, seed);
            server.loadState(world);
            server.start();
            ChunkSender sender = new ChunkSender(loop, plainsId());
            Loopback client = joinLoopback(loop, server, sender, "PersistBot");
            // Legally walk to (mx, my, mz) in steps <= 0.28 m/tick.
            for (int s = 1; s <= 20; s++) {
                double curX = 0.5 + (mx - 0.5) * s / 20.0;
                double curZ = 0.5 + (mz - 0.5) * s / 20.0;
                client.sendC(PacketIds.PLAY_MOVE_POSROT, new Wire.Writer()
                        .d(curX).d(my).d(curZ).f(myaw).f(mpitch).b(1).toBytes());
                pump(loop, server, sender, client, 1);
            }
            server.time.setRaining(true, 5000);
            // Rain ramps 0.01/tick; 30 ticks lands rainLevel at 0.30
            // (visible threshold is 0.2). The 20 walk ticks above cost
            // wall ticks too and the pre-S8 instant teleport hid it.
            pump(loop, server, sender, client, 30);
            KestrelPlayer live = server.players.get(profile);
            expect(live != null && live.x == mx && live.y == my && live.z == mz
                            && live.yaw == myaw && live.pitch == mpitch,
                    "persist loopback: move applied before save", failures);
            // Set then save with no tick between: the saved clock is exactly N.
            server.time.setDayTime(dayN);
            dev.kestrel.persist.WorldStore.save(loop, world);
            server.saveState(world);
            expected = server.levelState();
            savedGameTime = loop.gameTime();
            expect(expected.dayTime() == dayN && expected.raining() && expected.rainLevel() > 0.2f,
                    "persist loopback: pre-save state is N + raining, got " + expected, failures);
            client.socket.close();
        } finally {
            if (server != null) {
                server.close();
            }
        }

        server = null;
        try {
            // Reboot: brand-new loop + server objects, same directory.
            TickLoop loop = new TickLoop(new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            dev.kestrel.persist.WorldStore.load(loop, world);
            expect(loop.getBlockState(marker).is(Blocks.GOLD_BLOCK),
                    "persist loopback: block diff survives reboot", failures);
            expect(loop.gameTime() == savedGameTime,
                    "persist loopback: gameTime restored, got " + loop.gameTime()
                            + " want " + savedGameTime, failures);
            server = new NetServer(loop, 0, 256, seed);
            server.loadState(world);
            expect(server.time.dayTime() == dayN,
                    "persist loopback: time == N after reboot, got " + server.time.dayTime(), failures);
            expect(server.levelState().equals(expected),
                    "persist loopback: clock + weather restored, got " + server.levelState()
                            + " want " + expected, failures);

            server.start();
            ChunkSender sender = new ChunkSender(loop, plainsId());
            long bootTick = loop.gameTime();
            Loopback client = joinLoopback(loop, server, sender, "PersistBot");
            List<Wire.Inbound> seen = new ArrayList<>(client.joinSeen);
            seen.addAll(pump(loop, server, sender, client, 5));
            List<Long> days = dayTimes(seen);
            long elapsed = loop.gameTime() - bootTick;
            // The burst SetTime is the restored N advanced by the ticks
            // run before it; a default boot would show a value near 0.
            expect(!days.isEmpty() && days.get(0) >= dayN && days.get(0) <= dayN + elapsed,
                    "persist loopback: first SetTime is restored N (+" + elapsed + " ticks), got "
                            + days, failures);
            double[] pos = firstPosition(seen);
            expect(pos != null && pos[0] == mx && pos[1] == my && pos[2] == mz
                            && (float) pos[3] == myaw && (float) pos[4] == mpitch,
                    "persist loopback: join position packet carries the saved pos, got "
                            + java.util.Arrays.toString(pos), failures);
            KestrelPlayer back = server.players.get(profile);
            expect(back != null && back.x == mx && back.z == mz,
                    "persist loopback: server record restored", failures);
            client.socket.close();
        } finally {
            if (server != null) {
                server.close();
            }
        }

        // (d) void-fall leftover: a saved position deep in the void is NOT
        // resumed; the join places the player at spawn instead.
        server = null;
        try {
            TickLoop loop = new TickLoop(new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384));
            server = new NetServer(loop, 0, 256, seed);
            server.start();
            ChunkSender sender = new ChunkSender(loop, plainsId());
            UUID voidProfile = Wire.offlineUuid("VoidBot");
            java.nio.file.Path voidWorld = java.nio.file.Files.createTempDirectory("kestrel-void");
            StateStore.writePlayer(voidWorld, new StateStore.PlayerState(
                    voidProfile, "VoidBot", 8.5, -250000.0, 8.5, 0.0f, 0.0f, 0));
            server.loadState(voidWorld);
            Loopback client = joinLoopback(loop, server, sender, "VoidBot");
            List<Wire.Inbound> seenV = new ArrayList<>(client.joinSeen);
            seenV.addAll(pump(loop, server, sender, client, 5));
            double[] vpos = firstPosition(seenV);
            expect(vpos != null && vpos[1] > -128.0,
                    "persist loopback: void-saved join falls back to spawn, got "
                            + java.util.Arrays.toString(vpos),
                    failures);
            client.socket.close();
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    /** (c) missing and corrupt files boot clean defaults (clock 0, clear, spawn) without throwing. */
    private static void checkPersistDefaults(java.nio.file.Path empty, java.nio.file.Path world,
            List<String> failures) throws Exception {
        UUID profile = Wire.offlineUuid("PersistBot");

        // Missing: nothing on disk at all.
        NetServer missing = new NetServer(platformLoop(), 0, 256, 1L);
        try {
            missing.loadState(empty);
            expect(missing.time.dayTime() == 0 && !missing.time.raining()
                            && missing.time.rainLevel() == 0.0f,
                    "persist defaults: missing files -> clock 0, clear, got " + missing.levelState(),
                    failures);
            expect(missing.savedPlayer(profile) == null,
                    "persist defaults: missing files -> no saved player", failures);
        } catch (Exception e) {
            failures.add("persist defaults: missing files threw " + e);
        } finally {
            missing.close();
        }

        // Invalid level variants each read as null (all-or-nothing).
        java.nio.file.Path levelFile = StateStore.levelPath(world);
        String[] badLevels = {
                "format=1\ngameTime=10\ndayTime=banana\nraining=false\nrainTime=0\nrainLevel=0.0\n",
                "format=1\ngameTime=10\ndayTime=-1\nraining=false\nrainTime=0\nrainLevel=0.0\n",
                "format=2\ngameTime=10\ndayTime=5\nraining=false\nrainTime=0\nrainLevel=0.0\n",
                "format=1\ngameTime=10\ndayTime=5\nraining=false\nrainTime=0\n",
                "format=1\ngameTime=10\ndayTime=5\nraining=yes\nrainTime=0\nrainLevel=0.0\n",
                "format=1\ngameTime=10\ndayTime=5\nraining=true\nrainTime=0\nrainLevel=NaN\n",
        };
        for (String bad : badLevels) {
            java.nio.file.Files.writeString(levelFile, bad);
            expect(StateStore.readLevel(world) == null,
                    "persist defaults: invalid level reads null: " + bad.replace('\n', ' '), failures);
        }
        java.nio.file.Files.writeString(levelFile,
                "format=1\ngameTime=10\ndayTime=24000\nraining=false\nrainTime=0\nrainLevel=0.0\n");
        StateStore.LevelState s24k = StateStore.readLevel(world);
        expect(s24k != null && s24k.dayTime() == 24000,
                "persist defaults: dayTime=24000 is valid", failures);

        // Corrupt: binary garbage over both files from (b); the server is
        // pre-dirtied so defaults must be actively applied, not left over.
        java.nio.file.Files.write(levelFile, new byte[] {0, (byte) 0xFF, '\\', 'u', 'z', 0x7F, '=', 1});
        java.nio.file.Files.write(StateStore.playerPath(world, profile),
                new byte[] {(byte) 0xCA, (byte) 0xFE, 0, '\n', '='});
        NetServer server = null;
        try {
            TickLoop loop = platformLoop();
            server = new NetServer(loop, 0, 256, 1L);
            server.time.setDayTime(999);
            server.time.setRaining(true, 100);
            try {
                server.loadState(world);
            } catch (Exception e) {
                failures.add("persist defaults: corrupt files threw " + e);
            }
            expect(server.time.dayTime() == 0 && !server.time.raining()
                            && server.time.rainTime() == 0 && server.time.rainLevel() == 0.0f,
                    "persist defaults: corrupt level -> clock 0, clear, got " + server.levelState(),
                    failures);
            expect(server.savedPlayer(profile) == null,
                    "persist defaults: corrupt player file -> no saved player", failures);

            server.start();
            ChunkSender sender = new ChunkSender(loop, plainsId());
            Loopback client = joinLoopback(loop, server, sender, "PersistBot");
            List<Wire.Inbound> seenD = new ArrayList<>(client.joinSeen);
            seenD.addAll(pump(loop, server, sender, client, 5));
            double[] pos = firstPosition(seenD);
            expect(pos != null && pos[0] == 0.5 && pos[1] == 1.0 && pos[2] == 0.5
                            && pos[3] == 0.0 && pos[4] == 0.0,
                    "persist defaults: corrupt player joins at spawn 0.5,1.0,0.5, got "
                            + java.util.Arrays.toString(pos), failures);
            client.socket.close();
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    // ---- 7: registry data through vanilla's NBT read ----

    /**
     * Pin {@link SyncedRegistries#KEYS} to vanilla's real
     * {@code RegistryDataLoader.SYNCHRONIZED_REGISTRIES}, read live off
     * the 26.2 jar.
     *
     * <p>Why this is worth a check of its own: the client owns every
     * registry that is NOT on that list as a code-built static, and it
     * silently discards RegistryData for a key the list does not name
     * ({@code RegistryDataCollector.loadRegistries} only ever looks up
     * the keys in SYNCHRONIZED_REGISTRIES). So sending an extra one is
     * invisible on a stock client and cannot be caught by playing the
     * game. A 2026-09-15 byte diff of the config phase against the real
     * vanilla server found Kestrel shipping three such phantom packets
     * (game_event, mob_effect, sound_event), each with zero entries,
     * which reads on the wire as "these registries are empty".
     *
     * <p>Comparing against the jar rather than a hand-copied list is
     * the point: a hardcoded expectation would just be the same mistake
     * written twice.
     */
    private static void checkSyncedRegistryKeys(List<String> failures) {
        try {
            List<String> vanilla = new ArrayList<>();
            for (var data : net.minecraft.resources.RegistryDataLoader.SYNCHRONIZED_REGISTRIES) {
                vanilla.add(data.key().identifier().toString());
            }
            expect(vanilla.equals(SyncedRegistries.KEYS),
                    "registry: synced keys must equal vanilla SYNCHRONIZED_REGISTRIES"
                            + " (vanilla " + vanilla.size() + ", kestrel "
                            + SyncedRegistries.KEYS.size() + "); extra="
                            + minus(SyncedRegistries.KEYS, vanilla)
                            + " missing=" + minus(vanilla, SyncedRegistries.KEYS),
                    failures);
        } catch (Exception e) {
            failures.add("registry: synced key comparison threw " + e);
        }
    }

    /** Entries of {@code a} that {@code b} does not contain, order kept. */
    private static List<String> minus(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        for (String s : a) {
            if (!b.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static void checkRegistryData(List<String> failures) {
        String packDir = System.getProperty("kestrel.datapack");
        if (packDir == null) {
            System.out.println("  (registry group skipped: no -Dkestrel.datapack)");
            return;
        }
        try {
            List<Datapack.RegistryEntries> packs =
                    Datapack.load(java.nio.file.Path.of(packDir), SyncedRegistries.KEYS);
            int total = 0;
            for (Datapack.RegistryEntries re : packs) {
                for (Map.Entry<String, byte[]> e : re.entries()) {
                    total++;
                    java.io.DataInputStream in = new java.io.DataInputStream(
                            new java.io.ByteArrayInputStream(e.getValue()));
                    net.minecraft.nbt.Tag back = net.minecraft.nbt.NbtIo.readAnyTag(
                            in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                    if (!(back instanceof net.minecraft.nbt.CompoundTag)) {
                        failures.add("registry: non-compound entry " + re.key()
                                + " " + e.getKey());
                    }
                    if (in.available() != 0) {
                        failures.add("registry: trailing bytes in " + re.key()
                                + " " + e.getKey());
                    }
                }
            }
            expect(total > 300, "registry: served entries (" + total + ")", failures);
            // Every validated registry from the real-client crash
            // report must be non-empty.
            for (String must : List.of("minecraft:wolf_variant", "minecraft:cat_variant",
                    "minecraft:chicken_variant", "minecraft:cow_variant",
                    "minecraft:pig_variant", "minecraft:frog_variant",
                    "minecraft:painting_variant", "minecraft:zombie_nautilus_variant",
                    "minecraft:wolf_sound_variant", "minecraft:cat_sound_variant",
                    "minecraft:chicken_sound_variant", "minecraft:cow_sound_variant",
                    "minecraft:pig_sound_variant")) {
                int n = 0;
                for (Datapack.RegistryEntries re : packs) {
                    if (re.key().equals(must)) {
                        n = re.entries().size();
                    }
                }
                expect(n > 0, "registry: " + must + " non-empty", failures);
            }
            // Tags reference only served ids (synced regs) or the
            // static network order (static tag-only regs, whose entries
            // stay empty by design).
            Map<String, Map<String, List<Integer>>> tags = Datapack.tags(packs);
            for (Datapack.RegistryEntries re : packs) {
                int bound = re.staticOrder() != null
                        ? re.staticOrder().size() : re.entries().size();
                Map<String, List<Integer>> rt = tags.getOrDefault(re.key(), Map.of());
                for (Map.Entry<String, List<Integer>> t : rt.entrySet()) {
                    for (int id : t.getValue()) {
                        if (id < 0 || id >= bound) {
                            failures.add("registry: tag id out of range " + re.key()
                                    + " " + t.getKey() + " -> " + id);
                        }
                    }
                }
            }
            // Tags preserve datapack file order (vanilla serializes
            // member lists in file order, not sorted: live capture
            // proves is_fall = [10, 8, 39]).
            Map<String, List<Integer>> damageTags =
                    tags.getOrDefault("minecraft:damage_type", Map.of());
            List<Integer> isFall =
                    damageTags.getOrDefault("minecraft:is_fall", List.of());
            expect(isFall.equals(List.of(10, 8, 39)),
                    "registry: is_fall in file order [10, 8, 39] (got " + isFall + ")",
                    failures);
            // UpdateTags packet has no trailing bytes.
            Outbound.Packet tagsPacket = Outbound.updateTags(tags);
            Wire.Reader r = new Wire.Reader(tagsPacket.payload());
            int regs = r.varInt();
            for (int i = 0; i < regs; i++) {
                r.utf();
                int nt = r.varInt();
                for (int j = 0; j < nt; j++) {
                    r.utf();
                    int ni = r.varInt();
                    for (int k = 0; k < ni; k++) {
                        r.varInt();
                    }
                }
            }
            expect(!r.hasMore(), "registry: tags packet has no trailing bytes", failures);
            // Empty tags are still sent (client freezes "Unbound
            // tags" otherwise): dialog's pause_screen_additions and
            // quick_actions are empty in the datapack but required.
            Map<String, List<Integer>> dialogTags =
                    tags.getOrDefault("minecraft:dialog", Map.of());
            expect(dialogTags.containsKey("minecraft:pause_screen_additions")
                            && dialogTags.containsKey("minecraft:quick_actions"),
                    "registry: empty dialog tags present", failures);
            // Ids-only encoding (negotiated-pack path): every entry
            // present=false with zero value bytes, ids identical to the
            // full encoding so tag order still matches.
            for (Datapack.RegistryEntries re : packs) {
                Outbound.Packet ids = Outbound.registryDataIdsOnly(re.key(), re.entries());
                Wire.Reader ir = new Wire.Reader(ids.payload());
                expect(ir.utf().equals(re.key()), "registry: ids-only key " + re.key(),
                        failures);
                int n = ir.varInt();
                expect(n == re.entries().size(),
                        "registry: ids-only count " + re.key(), failures);
                for (int i = 0; i < n; i++) {
                    String id = ir.utf();
                    boolean present = ir.bool();
                    expect(id.equals(re.entries().get(i).getKey())
                                    && !present,
                            "registry: ids-only entry " + re.key() + " " + id, failures);
                }
                expect(!ir.hasMore(),
                        "registry: ids-only no trailing bytes " + re.key(), failures);
            }
        } catch (Exception e) {
            failures.add("registry: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    // ---- 6: online crypto without network ----

    private static void checkOnlineCrypto(List<String> failures) {
        try {
            java.security.KeyPair pair = SessionCipher.generateKeyPair();
            expect(pair.getPublic().getEncoded().length > 0, "crypto: RSA public key nonempty",
                    failures);
            byte[] challenge = SessionCipher.freshChallenge();
            expect(challenge.length == 4, "crypto: challenge is 4 bytes", failures);

            // Client side: fresh AES secret, RSA-encrypt both fields with
            // the server public key (mirrors ServerboundKeyPacket ctor).
            javax.crypto.KeyGenerator aes = javax.crypto.KeyGenerator.getInstance("AES");
            aes.init(128);
            javax.crypto.SecretKey secret = aes.generateKey();
            javax.crypto.Cipher rsa = javax.crypto.Cipher.getInstance("RSA");
            rsa.init(javax.crypto.Cipher.ENCRYPT_MODE, pair.getPublic());
            byte[] encSecret = rsa.doFinal(secret.getEncoded());
            byte[] encChallenge = rsa.doFinal(challenge);
            Wire.Writer kw = new Wire.Writer();
            kw.byteArray(encSecret);
            kw.byteArray(encChallenge);
            SessionCipher.KeyPacket parsed =
                    SessionCipher.KeyPacket.parse(kw.toBytes());
            SessionCipher.UnwrappedSecret unwrapped =
                    SessionCipher.unwrap(parsed, pair.getPrivate(), challenge);
            expect(unwrapped.challengeOk(), "crypto: challenge round-trips", failures);
            expect(java.util.Arrays.equals(unwrapped.secret().getEncoded(), secret.getEncoded()),
                    "crypto: secret round-trips", failures);

            // Link ciphers: encrypt framed bytes in small chunks (like
            // the session write path), decrypt in odd chunks (like the
            // read path); payload must survive.
            SessionCipher.LinkCiphers link = SessionCipher.linkCiphers(secret);
            byte[] framed = Wire.frame(0x31, "link-test".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8), -1);
            java.io.ByteArrayOutputStream enc = new java.io.ByteArrayOutputStream();
            for (int off = 0; off < framed.length; off += 3) {
                int n = Math.min(3, framed.length - off);
                enc.write(SessionCipher.update(link.encrypt(), framed, off, n));
            }
            byte[] cipherBytes = enc.toByteArray();
            expect(cipherBytes.length == framed.length, "crypto: CFB8 preserves length", failures);
            java.io.ByteArrayOutputStream dec = new java.io.ByteArrayOutputStream();
            for (int off = 0; off < cipherBytes.length; off += 5) {
                int n = Math.min(5, cipherBytes.length - off);
                dec.write(SessionCipher.update(link.decrypt(), cipherBytes, off, n));
            }
            expect(java.util.Arrays.equals(dec.toByteArray(), framed),
                    "crypto: link round-trips across chunk boundaries", failures);

            // Server hash: SIGNED hex like vanilla (leading minus when
            // the digest is negative), deterministic for same inputs.
            String h1 = SessionCipher.serverHash(pair.getPublic(), secret);
            String h2 = SessionCipher.serverHash(pair.getPublic(), secret);
            expect(h1.equals(h2) && h1.matches("-?[0-9a-f]+"),
                    "crypto: server hash is stable signed hex (" + h1 + ")", failures);
            // Cross-check against the JDK directly: SHA-1("", secret,
            // pubkey) rendered via new BigInteger(digest).toString(16).
            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            sha1.update(new byte[0]);
            sha1.update(secret.getEncoded());
            sha1.update(pair.getPublic().getEncoded());
            String ref = new java.math.BigInteger(sha1.digest()).toString(16);
            expect(h1.equals(ref), "crypto: server hash matches vanilla formula", failures);

            // Login disconnect payload: JSON text component the vanilla
            // client parses (no trailing bytes).
            Outbound.Packet disc = Outbound.loginDisconnect("Failed to verify username!");
            Wire.Reader dr = new Wire.Reader(disc.payload());
            expect(dr.utf().equals("\"Failed to verify username!\""),
                    "crypto: login disconnect is a JSON string", failures);
            expect(!dr.hasMore(), "crypto: login disconnect has no trailing bytes", failures);

            // Session JSON: undashed id, name, skin property with signature.
            String json = "{\"id\":\"069a79f444e94726a5befca90bf9a7e3\","
                    + "\"name\":\"Notch\",\"properties\":[{\"name\":\"textures\","
                    + "\"value\":\"abc123\",\"signature\":\"sig456\"},"
                    + "{\"name\":\"unsigned\",\"value\":\"v2\"}]}";
            SessionCipher.VerifiedProfile vp = SessionCipher.parseProfile(json);
            expect(vp.id().equals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90bf9a7e3")),
                    "crypto: undashed id parses", failures);
            expect(vp.name().equals("Notch"), "crypto: name parses", failures);
            expect(vp.properties().size() == 2
                            && vp.properties().get(0).signature().equals("sig456")
                            && vp.properties().get(1).signature() == null,
                    "crypto: properties parse with optional signature", failures);

            // LoginFinished property codec: uuid + name + properties + session id.
            UUID id = vp.id();
            Outbound.Packet fin = Outbound.loginFinished(id, "Notch", vp.properties());
            Wire.Reader r = new Wire.Reader(fin.payload());
            expect(r.uuid().equals(id), "crypto: finished profile uuid", failures);
            expect(r.utf().equals("Notch"), "crypto: finished name", failures);
            int count = r.varInt();
            expect(count == 2, "crypto: finished property count", failures);
            String pn = r.utf();
            String pv = r.utf();
            boolean signed = r.bool();
            String sig = signed ? r.utf() : null;
            expect(pn.equals("textures") && pv.equals("abc123") && sig.equals("sig456"),
                    "crypto: finished signed property", failures);
            r.utf();
            r.utf();
            expect(!r.bool(), "crypto: finished unsigned property has no signature", failures);
            expect(r.uuid().equals(id), "crypto: finished session id", failures);
        } catch (Exception e) {
            failures.add("crypto: threw " + e);
            e.printStackTrace(System.out);
        }
    }

    // ---- 11: chunk resend discipline and packet counting ----

    private static void checkResend(List<String> failures) {
        NetServer server = null;
        try {
            BlockState stone = Blocks.STONE.defaultBlockState();
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    loop.setBlock(new BlockPos(x, 0, z), stone, 3);
                }
            }
            server = new NetServer(loop, 0, 256, 4242424242L);
            server.start();
            int port = server.boundPort();
            ChunkSender sender = new ChunkSender(loop, plainsId());

            // Loopback join with viewDistance=2 (5x5 chunks initial view)
            Loopback client = joinLoopback(loop, server, sender, "ResendBot", 2);
            expect(client != null, "resend: client joined", failures);
            if (client == null) {
                return;
            }

            // Two-stage join delivers the 5x5 across ticks; the Loopback
            // counts every chunk off the wire, so settle then assert the
            // cumulative total (no post-hoc drain to race).
            pump(loop, server, sender, client, 5);
            expect(client.chunksSeen == 25,
                    "resend: join burst delivered 25 initial chunks (got " + client.chunksSeen + ")",
                    failures);
            int burstChunks = client.chunksSeen;

            // (a) Pump 40 idle ticks with zero block writes: assert ZERO chunk packets after join burst
            pump(loop, server, sender, client, 40);
            expect(client.chunksSeen == burstChunks,
                    "resend: zero chunk packets during 40 idle ticks after join burst (got "
                            + (client.chunksSeen - burstChunks) + ")",
                    failures);

            // (c) Send-time backstop proof: artificially flag a clean chunk in dirtyChunks
            // with zero writes. Send-time backstop must skip it: zero chunk packets sent.
            long cleanChunkKey = ChunkSender.pack(1, 1);
            server.dirtyChunks().add(cleanChunkKey);
            int beforeBackstop = client.chunksSeen;
            pump(loop, server, sender, client, 5);
            expect(client.chunksSeen == beforeBackstop,
                    "resend: send-time backstop skipped flagged clean chunk (got "
                            + (client.chunksSeen - beforeBackstop) + ")",
                    failures);

            // (b) One block write: place stone away from player, pump 5 ticks.
            // H3: committed writes travel as block-update deltas from the
            // TickLoop mutation journal to every client holding the chunk,
            // so a single placement must NOT resend the chunk (the delta
            // also advances this player's sent revision, so the backstop
            // keeps skipping it). ScheduledTickRevisionCheck proves the
            // client image stays exact.
            BlockPos targetPos = new BlockPos(3, 1, 3);
            hold(server.players.values().iterator().next(), net.minecraft.world.item.Items.STONE, 8);
            client.sendC(PacketIds.PLAY_USE_ITEM_ON, useItemOn(0, new BlockPos(3, 0, 3), 1, 99));
            List<Wire.Inbound> writeSeen = pump(loop, server, sender, client, 5);
            long resentChunkPackets = writeSeen.stream()
                    .filter(in -> in.id() == PacketIds.PLAY_CHUNK)
                    .count();
            expect(resentChunkPackets == 0,
                    "resend: single placement arrives as a delta, zero chunk packets (got "
                            + resentChunkPackets + ")", failures);
            long placedChunk = ChunkSender.pack(targetPos.getX() >> 4, targetPos.getZ() >> 4);
            expect(sender.sentVersion(Wire.offlineUuid("ResendBot"), placedChunk)
                            == sender.writeVersion(placedChunk),
                    "resend: delta advanced the player's sent revision to the chunk revision", failures);
            expect(sawAck(writeSeen, 99), "resend: placement acked", failures);
            expect(sawBlockUpdate(writeSeen, targetPos, Blocks.STONE.defaultBlockState()),
                    "resend: block update echo arrived", failures);

            // Further idle pump: zero chunk packets after dirty resend settled
            List<Wire.Inbound> postWriteIdle = pump(loop, server, sender, client, 10);
            long postIdleChunks = postWriteIdle.stream()
                    .filter(in -> in.id() == PacketIds.PLAY_CHUNK)
                    .count();
            expect(postIdleChunks == 0,
                    "resend: zero chunk packets during post-write idle ticks (got " + postIdleChunks + ")",
                    failures);

            // (d) Rejoin regression (spawn-fallback empty stream): the same
            // profile disconnects and joins again at the same center. The
            // burst must resend the full 5x5, not skip everything on the
            // previous session's sent markers.
            client.socket.close();
            long deadline = System.currentTimeMillis() + 10000;
            while (server.sessions.containsKey(Wire.offlineUuid("ResendBot"))
                    && System.currentTimeMillis() < deadline) {
                KestrelServer.runIteration(server, loop, sender);
                Thread.sleep(5);
            }
            Loopback rejoin = joinLoopback(loop, server, sender, "ResendBot", 2);
            expect(rejoin != null, "resend: rejoin client joined", failures);
            rejoin.joinSeen.addAll(pump(loop, server, sender, rejoin, 5));
            long rejoinChunks = rejoin.joinSeen.stream()
                    .filter(in -> in.id() == PacketIds.PLAY_CHUNK)
                    .count();
            expect(rejoinChunks >= 9,
                    "resend: rejoin burst streams chunks (got " + rejoinChunks + ")", failures);
            rejoin.socket.close();

            // (e) Center move keeps sent markers (no resend storm): join a
            // fresh client at view distance 2, settle to stream-complete,
            // then move one chunk east. Only the newly-entered column
            // (5 chunks) may stream, not the whole 5x5 again.
            Loopback walker = joinLoopback(loop, server, sender, "WalkBot", 2);
            expect(walker != null, "resend: walk client joined", failures);
            pump(loop, server, sender, walker, 10);
            int settled = walker.chunksSeen;
            expect(settled == 25,
                    "resend: walk client settled at 25 chunks (got " + settled + ")", failures);
            UUID walkProfile = Wire.offlineUuid("WalkBot");
            KestrelPlayer rec = server.players.get(walkProfile);
            expect(rec != null, "resend: walk client has a server record", failures);
            expect(rec != null && rec.chunkX() == 0,
                    "resend: walk client starts in chunk 0 (got "
                            + (rec == null ? "gone" : rec.chunkX()) + ")",
                    failures);
            // Step east one chunk (16 blocks in 1.0 m steps, legal
            // under the S8 1.20 m/tick cap, pumped one tick at a time;
            // pump already drains inbound before each tick, and each
            // step is <= 1.0 m so no violation can fire).
            for (int s = 0; s < 16; s++) {
                walker.sendC(PacketIds.PLAY_MOVE_POSROT, new Wire.Writer()
                        .d(0.5 + (s + 1) * 1.0).d(1.0).d(0.5).f(0.0f).f(0.0f).b(1).toBytes());
                pump(loop, server, sender, walker, 1);
            }
            KestrelPlayer moved = server.players.get(walkProfile);
            expect(moved != null && moved.chunkX() == 1 && moved.x == 16.5
                            && moved.moveViolations == 0,
                    "resend: walk client reached chunk 1 at x=16.5 with 0 violations (got "
                            + (moved == null ? "gone"
                                    : moved.chunkX() + " x=" + moved.x
                                            + " viol=" + moved.moveViolations)
                            + ")",
                    failures);
            // Settle AFTER the move: 10 idle ticks drain whatever the
            // center change queued. Storm (HEAD clear-all) queues the
            // whole 5x5 = 25; fix queues only the new column = 5.
            pump(loop, server, sender, walker, 10);
            int afterMove = walker.chunksSeen - settled;
            expect(afterMove <= 10,
                    "resend: one-chunk move streams at most the new column (got "
                            + afterMove + ")",
                    failures);
            walker.socket.close();
        } catch (Exception e) {
            failures.add("resend: threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    // ---- 13: movement checks (anti-cheat Roadmap S8) ----

    private static void checkMove(List<String> failures) {
        NetServer server = null;
        try {
            BlockState stone = Blocks.STONE.defaultBlockState();
            KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
            TickLoop loop = new TickLoop(level);
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    loop.setBlock(new BlockPos(x, 0, z), stone, 3);
                }
            }
            server = new NetServer(loop, 0, 256, 4242424242L);
            server.start();
            int port = server.boundPort();
            ChunkSender sender = new ChunkSender(loop, plainsId());

            Loopback client = joinLoopback(loop, server, sender, "MoveBot");
            expect(client != null, "move: client joined", failures);
            if (client == null) {
                return;
            }

            // Drain initial join burst and position sync. Two-stage join:
            // keep pumping until the server record is placed (stage 2 done).
            UUID profile = Wire.offlineUuid("MoveBot");
            for (int i = 0; i < 40; i++) {
                KestrelPlayer rec = server.players.get(profile);
                if (rec != null && rec.placed) {
                    break;
                }
                pump(loop, server, sender, client, 1);
            }
            KestrelPlayer p = server.players.get(profile);
            expect(p != null && p.placed,
                    "move: player placed after two-stage join", failures);
            expect(p != null, "move: server tracks MoveBot", failures);
            if (p == null) {
                client.socket.close();
                return;
            }

            // Spawn placed player at (0.5, 1.0, 0.5)
            expect(Math.abs(p.x - 0.5) < 0.01 && Math.abs(p.y - 1.0) < 0.01 && Math.abs(p.z - 0.5) < 0.01,
                    "move: initial spawn pos is 0.5, 1.0, 0.5", failures);

            // (a) Legal walk: 10 steps of 0.2 m/tick along +X.
            // 0.2 m/tick is within MAX_HORIZONTAL_SPEED (0.60 m/tick).
            // Two-stage join: pump an extra settle tick first so the
            // stage-2 teleport round-trips before the walk starts (else
            // the last step is still in flight when we assert).
            pump(loop, server, sender, client, 1);
            for (int step = 1; step <= 10; step++) {
                double targetX = 0.5 + step * 0.2;
                client.sendC(PacketIds.PLAY_MOVE_POSROT, new Wire.Writer()
                        .d(targetX).d(1.0).d(0.5).f(0.0f).f(0.0f).b(1).toBytes());
                pump(loop, server, sender, client, 1);
            }
            pump(loop, server, sender, client, 1);
            expect(Math.abs(p.x - 2.5) < 0.01 && Math.abs(p.y - 1.0) < 0.01 && Math.abs(p.z - 0.5) < 0.01,
                    "move: legal walk position tracks to 2.5, 1.0, 0.5 (got " + p.x + "," + p.y + "," + p.z + ")",
                    failures);

            // (d) Negative control: exactly-0-violations assertion on the legal walk.
            expect(p.moveViolations == 0,
                    "move: negative control: legal walk has exactly 0 violations (got " + p.moveViolations + ")",
                    failures);
            expect(p.consecutiveMoveViolations == 0,
                    "move: negative control: legal walk has 0 consecutive violations (got " + p.consecutiveMoveViolations + ")",
                    failures);

            // (b) Teleport 100 blocks in one packet: rejected, position unchanged.
            double preTeleportX = p.x;
            double preTeleportY = p.y;
            double preTeleportZ = p.z;
            client.sendC(PacketIds.PLAY_MOVE_POSROT, new Wire.Writer()
                    .d(preTeleportX + 100.0).d(preTeleportY).d(preTeleportZ).f(0.0f).f(0.0f).b(1).toBytes());
            pump(loop, server, sender, client, 1);
            expect(Math.abs(p.x - preTeleportX) < 0.01 && Math.abs(p.y - preTeleportY) < 0.01 && Math.abs(p.z - preTeleportZ) < 0.01,
                    "move: teleport 100 blocks rejected, position unchanged (got " + p.x + "," + p.y + "," + p.z + ")",
                    failures);
            expect(p.moveViolations == 1,
                    "move: teleport 100 blocks incremented violations to 1 (got " + p.moveViolations + ")",
                    failures);
            expect(p.consecutiveMoveViolations == 1,
                    "move: consecutive violations is 1 (got " + p.consecutiveMoveViolations + ")",
                    failures);

            // (c) Sustained fly (+1 m/tick x 30 ticks): triggers rubber-band.
            // Server position packet observed, player back at legal pos (2.5, 1.0, 0.5).
            List<Wire.Inbound> flySeen = new ArrayList<>();
            for (int tick = 1; tick <= 30; tick++) {
                double flyY = preTeleportY + tick * 1.0;
                client.sendC(PacketIds.PLAY_MOVE_POSROT, new Wire.Writer()
                        .d(preTeleportX).d(flyY).d(preTeleportZ).f(0.0f).f(0.0f).b(0).toBytes());
                flySeen.addAll(pump(loop, server, sender, client, 1));
            }

            // Assert rubber-band position packet was emitted and received
            boolean sawRubberBand = flySeen.stream().anyMatch(in -> in.id() == PacketIds.PLAY_POSITION);
            expect(sawRubberBand,
                    "move: sustained fly triggers rubber-band (PLAY_POSITION packet observed)",
                    failures);

            // Assert rubber-band packet carries authoritative server position and valid teleport id
            Wire.Inbound rbPacket = flySeen.stream()
                    .filter(in -> in.id() == PacketIds.PLAY_POSITION)
                    .findFirst().orElse(null);
            if (rbPacket != null) {
                Wire.Reader r = rbPacket.reader();
                int rbId = r.varInt();
                double rbX = r.d();
                double rbY = r.d();
                double rbZ = r.d();
                expect(rbId > 0,
                        "move: rubber-band carries valid teleport id (" + rbId + ")",
                        failures);
                expect(Math.abs(rbX - preTeleportX) < 0.01 && Math.abs(rbY - preTeleportY) < 0.01 && Math.abs(rbZ - preTeleportZ) < 0.01,
                        "move: rubber-band position matches legal pos (" + rbX + "," + rbY + "," + rbZ + ")",
                        failures);
            }

            // Assert player on server is back at legal position (2.5, 1.0, 0.5)
            expect(Math.abs(p.x - preTeleportX) < 0.01 && Math.abs(p.y - preTeleportY) < 0.01 && Math.abs(p.z - preTeleportZ) < 0.01,
                    "move: player back at legal pos after sustained fly (got " + p.x + "," + p.y + "," + p.z + ")",
                    failures);

            // Assert violation counts: 1 (teleport) + 30 (fly ticks) = 31 total violations.
            // Consecutive carries the teleport's 1 into the fly loop, so the
            // rubber-band reset fires at fly tick 19 (1+19=20), and fly ticks
            // 20..30 accumulate 11 after it.
            expect(p.moveViolations == 31,
                    "move: total violations is 31 (got " + p.moveViolations + ")",
                    failures);
            expect(p.consecutiveMoveViolations == 11,
                    "move: consecutive violations after rubber-band reset is 11 (got " + p.consecutiveMoveViolations + ")",
                    failures);

            // (e) Swim: rising in water is buoyancy, not fly. Flood a
            // water CUBE (x/z -8..8, y 0..20) so the whole swim path is
            // wet, then swim up +3 m/tick x 5 ticks from y=1.0 (would
            // trip the 0.50 jump cap and the gravity gate on land). All
            // five must apply with zero new violations. Flags=2 skips
            // shape updates (a lone water block shape-updates to air
            // without neighbours); the cube's interior keeps its fluid.
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    for (int y = 0; y <= 20; y++) {
                        loop.setBlock(new BlockPos(x, y, z),
                                Blocks.WATER.defaultBlockState(), 2);
                    }
                }
            }
            int violBefore = p.moveViolations;
            double swimX = p.x;
            double swimZ = p.z;
            for (int tick = 1; tick <= 5; tick++) {
                double swimY = 1.0 + tick * 3.0;
                client.sendC(PacketIds.PLAY_MOVE_POSROT, new Wire.Writer()
                        .d(swimX).d(swimY).d(swimZ).f(0.0f).f(0.0f).b(0).toBytes());
                pump(loop, server, sender, client, 1);
            }
            expect(Math.abs(p.y - 16.0) < 0.01,
                    "move: swim-up applies to y=16.0 (got " + p.y + ")", failures);
            expect(p.moveViolations == violBefore,
                    "move: swim-up adds zero violations (got "
                            + (p.moveViolations - violBefore) + ")",
                    failures);

            // (f) Fly still caught next to water: +8 m in one tick over
            // the swim cap trips the rise check even when wet.
            client.sendC(PacketIds.PLAY_MOVE_POSROT, new Wire.Writer()
                    .d(swimX).d(24.0).d(swimZ).f(0.0f).f(0.0f).b(0).toBytes());
            pump(loop, server, sender, client, 1);
            expect(p.moveViolations == violBefore + 1,
                    "move: +8 m jump in water still violates (got "
                            + (p.moveViolations - violBefore) + ")",
                    failures);

            client.socket.close();
        } catch (Exception e) {
            failures.add("move: threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    // ---- 12: S13 inbound hardening (fault injection) ----

    /** Poll a condition every few ms until it holds or the timeout passes. */
    private static boolean waitFor(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    /**
     * Air cell for burst placement i: an 8x8 grid around the spawn column in two layers from baseY,
     * all within reach of a player standing at the spawn and clear of its body.
     */
    private static BlockPos burstPos(int i, int baseY) {
        int cell = i % 128;
        return new BlockPos(-3 + cell % 8, baseY + cell / 64, -3 + (cell / 8) % 8);
    }

    /** Send n placements as one write: clicked air is replaced in place, so each one is a distinct accepted write. */
    private static void sendPlaceBurst(Loopback client, int n, int baseY) throws Exception {
        java.io.ByteArrayOutputStream burst = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < n; i++) {
            burst.write(Wire.frame(PacketIds.PLAY_USE_ITEM_ON,
                    useItemOn(0, burstPos(i, baseY), 1, 1000 + i), client.threshold));
        }
        client.out.write(burst.toByteArray());
        client.out.flush();
    }

    /** Stone each burst click is given; a placement consumes one, so the drop is the applied count. */
    private static final int BURST_STONE = 6000;

    /** Placements applied since {@link #BURST_STONE} was handed over: one stone is consumed per placement. */
    private static int countPlaced(KestrelPlayer player) {
        return BURST_STONE - heldCount(player);
    }

    /** Empty the burst grid again between bursts. */
    private static void clearBurst(TickLoop loop, int baseY) {
        for (int i = 0; i < 128; i++) {
            loop.setBlock(burstPos(i, baseY), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static byte[] movePos(double x, double y, double z) {
        return new Wire.Writer().d(x).d(y).d(z).b(1).toBytes();
    }

    /** Raw frame around an already-enveloped body (compression prefix included). */
    private static byte[] rawFrame(byte[] enveloped) {
        return Wire.concat(Wire.varInt(enveloped.length), enveloped);
    }

    private static void checkHarden(List<String> failures) {
        NetServer server = null;
        try {
            TickLoop loop = platformLoop();
            server = new NetServer(loop, 0, 256, 4242424242L);
            server.start();
            ChunkSender sender = new ChunkSender(loop, plainsId());
            Loopback flood = joinLoopback(loop, server, sender, "HardenFlood");
            Loopback calm = joinLoopback(loop, server, sender, "HardenCalm");
            List<Wire.Inbound> seenF = new ArrayList<>();
            List<Wire.Inbound> seenC = new ArrayList<>();
            pumpPair(loop, server, sender, flood, calm, seenF, seenC, 20);
            // Stragglers (teleport accept, loaded) drain before the counted bursts.
            Thread.sleep(200);
            KestrelServer.runIteration(server, loop, sender);
            UUID floodId = Wire.offlineUuid("HardenFlood");
            UUID calmId = Wire.offlineUuid("HardenCalm");
            NetServer.Session fs = server.sessions.get(floodId);
            NetServer.Session cs = server.sessions.get(calmId);
            expect(server.players.size() == 2 && fs != null && cs != null,
                    "harden: both clients joined", failures);
            if (fs == null || cs == null) {
                return;
            }
            int cap = NetServer.MAX_EVENTS_PER_SESSION_PER_TICK;

            // (d) Negative control: cap - 1 placements in one tick all apply.
            int legal = cap - 1;
            KestrelPlayer burstP = server.players.get(floodId);
            hold(burstP, net.minecraft.world.item.Items.STONE, BURST_STONE);
            sendPlaceBurst(flood, legal, 3);
            expect(waitFor(() -> fs.admittedPackets.get() >= legal, 10000),
                    "harden: legal burst reached the server", failures);
            KestrelServer.runIteration(server, loop, sender);
            int legalPlaced = countPlaced(burstP);
            expect(legalPlaced == legal,
                    "harden: legal burst of " + legal + " fully applied (got " + legalPlaced + ")", failures);
            expect(fs.droppedEvents.get() == 0,
                    "harden: legal burst dropped nothing (got " + fs.droppedEvents.get() + ")", failures);
            pumpPair(loop, server, sender, flood, calm, seenF, seenC, 5);

            // (a) Flood: 5000 placements in one tick from one client, plus
            // a legal move from the bystander in the same tick. Bystander
            // pre-walks to 1.0 (two 0.25 steps) so the flooded-tick move
            // 1.0 -> 1.25 is a legal 0.25 m step under S8 (0.60 m/tick).
            for (double wx : new double[]{0.75, 1.0}) {
                calm.sendC(PacketIds.PLAY_MOVE_POS, movePos(wx, 1.0, 0.5));
            }
            expect(waitFor(() -> cs.admittedPackets.get() >= 2, 10000),
                    "harden: bystander pre-walk reached the server", failures);
            KestrelServer.runIteration(server, loop, sender);
            KestrelPlayer preCalm = server.players.get(calmId);
            expect(preCalm != null && preCalm.x == 1.0,
                    "harden: bystander pre-walk applied (x="
                            + (preCalm == null ? "gone" : preCalm.x) + ")", failures);
            int floodN = 5000;
            clearBurst(loop, 3);
            hold(burstP, net.minecraft.world.item.Items.STONE, BURST_STONE);
            sendPlaceBurst(flood, floodN, 3);
            calm.sendC(PacketIds.PLAY_MOVE_POS, movePos(1.25, 1.0, 0.5));
            expect(waitFor(() -> fs.admittedPackets.get() >= floodN
                            && cs.admittedPackets.get() >= 1, 10000),
                    "harden: flood and bystander move reached the server", failures);
            long t0 = System.nanoTime();
            KestrelServer.runIteration(server, loop, sender);
            long tickMs = (System.nanoTime() - t0) / 1_000_000;
            int floodPlaced = countPlaced(burstP);
            expect(floodPlaced == cap,
                    "harden: flood applied exactly the per-tick cap " + cap + " (got " + floodPlaced + ")",
                    failures);
            expect(fs.droppedEvents.get() >= floodN - cap,
                    "harden: flood drops counted (got " + fs.droppedEvents.get() + ")", failures);
            expect(tickMs < 5000, "harden: flooded tick completed (" + tickMs + " ms)", failures);
            KestrelPlayer calmP = server.players.get(calmId);
            expect(calmP != null && calmP.x == 1.25,
                    "harden: bystander move applied in the flooded tick (x="
                            + (calmP == null ? "gone" : calmP.x) + ")", failures);
            expect(cs.droppedEvents.get() == 0, "harden: bystander dropped nothing", failures);
            pumpPair(loop, server, sender, flood, calm, seenF, seenC, 10);
            expect(countPlaced(burstP) == cap,
                    "harden: flood excess dropped, not deferred", failures);
            expect(server.players.size() == 2 && !fs.closed.get(),
                    "harden: flooder stays connected", failures);

            // (b) Oversized: 10x chat, a frame past the cap skipped
            // mid-stream, a ratio bomb, an over-cap declared length.
            // Flood pre-walks 0.5 -> 1.0 (two legal 0.25 steps) first, so the
            // post-oversized move 1.0 -> 1.25 is legal under S8 (0.60 m/tick).
            for (double wx : new double[]{0.75, 1.0}) {
                flood.sendC(PacketIds.PLAY_MOVE_POS, movePos(wx, 1.0, 0.5));
            }
            expect(waitFor(() -> fs.admittedPackets.get() >= 2, 10000),
                    "harden: flood pre-walk reached the server", failures);
            KestrelServer.runIteration(server, loop, sender);
            long oversized0 = fs.oversizedFrames.get();
            long malformed0 = fs.malformedPackets.get();
            flood.sendC(PacketIds.PLAY_CHAT, clientChat("x".repeat(2560)));
            int big = NetServer.MAX_INBOUND_FRAME + 1000;
            java.io.ByteArrayOutputStream wire = new java.io.ByteArrayOutputStream();
            wire.write(Wire.varInt(big));
            wire.write(new byte[big]);
            wire.write(rawFrame(Wire.concat(Wire.varInt(1000), Wire.zlibCompress(new byte[4 << 20]))));
            wire.write(rawFrame(Wire.concat(Wire.varInt(NetServer.MAX_INBOUND_UNCOMPRESSED + 1),
                    Wire.zlibCompress(new byte[64]))));
            wire.write(Wire.frame(PacketIds.PLAY_MOVE_POS, movePos(1.25, 1.0, 0.5), flood.threshold));
            flood.out.write(wire.toByteArray());
            flood.out.flush();
            expect(waitFor(() -> fs.oversizedFrames.get() >= oversized0 + 2
                            && fs.malformedPackets.get() >= malformed0 + 1, 10000),
                    "harden: oversized frame, over-cap length, and ratio bomb counted (oversized="
                            + (fs.oversizedFrames.get() - oversized0) + " malformed="
                            + (fs.malformedPackets.get() - malformed0) + ")", failures);
            Thread.sleep(100);
            pumpPair(loop, server, sender, flood, calm, seenF, seenC, 10);
            KestrelPlayer floodP = server.players.get(floodId);
            expect(!sawSystemChat(seenC, "<HardenFlood> " + "x".repeat(2560)),
                    "harden: 10x chat absorbed without broadcast", failures);
            expect(floodP != null && floodP.x == 1.25,
                    "harden: move after the oversized frame applied (stream resynced, x="
                            + (floodP == null ? "gone" : floodP.x) + ")", failures);
            expect(server.players.size() == 2 && !fs.closed.get(),
                    "harden: oversized input keeps the session", failures);

            // (c) Malformed: negative length alone (discards the buffer),
            // then zero-length, truncated payload, corrupt zlib, FDICT zlib.
            long malformed1 = fs.malformedPackets.get();
            long gameTime0 = loop.gameTime();
            flood.out.write(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F});
            flood.out.flush();
            expect(waitFor(() -> fs.malformedPackets.get() >= malformed1 + 1, 5000),
                    "harden: negative frame length counted", failures);
            wire.reset();
            wire.write(0x00);
            wire.write(Wire.frame(PacketIds.PLAY_MOVE_POS, new byte[3], flood.threshold));
            wire.write(rawFrame(Wire.concat(Wire.varInt(300), new byte[]{1, 2, 3, 4, 5, 6, 7, 8})));
            // FDICT header + dict id + trailing input: the pre-S13 inflate
            // loop spun forever here (needsDictionary, never needsInput).
            wire.write(rawFrame(Wire.concat(Wire.varInt(300),
                    new byte[]{0x78, (byte) 0xBB, 0, 0, 0, 1, 9, 9, 9, 9})));
            flood.out.write(wire.toByteArray());
            flood.out.flush();
            expect(waitFor(() -> fs.malformedPackets.get() >= malformed1 + 5, 5000),
                    "harden: zero-length, truncated, corrupt zlib, and FDICT zlib counted (got "
                            + (fs.malformedPackets.get() - malformed1) + ")", failures);
            flood.sendC(PacketIds.PLAY_MOVE_POS, movePos(1.75, 1.0, 0.5));
            // Calm pre-walks 1.25 -> 2.0 in legal 0.25 steps (S8: 0.60 m/tick).
            // NOTE: admittedPackets was reset by the last pollEvents, so wait
            // for admission of exactly these 3 before draining.
            for (double wx : new double[]{1.5, 1.75, 2.0}) {
                calm.sendC(PacketIds.PLAY_MOVE_POS, movePos(wx, 1.0, 0.5));
            }
            expect(waitFor(() -> cs.admittedPackets.get() >= 3, 10000),
                    "harden: calm post-malformed walk reached the server", failures);
            Thread.sleep(100);
            pumpPair(loop, server, sender, flood, calm, seenF, seenC, 10);
            expect(loop.gameTime() > gameTime0, "harden: server kept ticking", failures);
            floodP = server.players.get(floodId);
            calmP = server.players.get(calmId);
            expect(floodP != null && floodP.x == 1.75 && calmP != null && calmP.x == 2.0,
                    "harden: both clients' moves apply after malformed input", failures);
            expect(server.players.size() == 2 && !fs.closed.get(),
                    "harden: malformed input keeps the session", failures);

            flood.socket.close();
            calm.socket.close();
        } catch (Exception e) {
            failures.add("harden: threw " + e);
            e.printStackTrace(System.out);
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }
}
