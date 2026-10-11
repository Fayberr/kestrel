package dev.kestrel.net;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Scripted synthetic client that drives handshake -> login -> the whole
 * configuration phase against ANY 26.2 server (Kestrel or the real
 * Mojang server jar) and dumps every received packet.
 *
 * <p>Purpose: settle the Fabric-only blockstate artifact by diffing
 * Kestrel's config phase byte for byte against vanilla's, with the
 * client side held identical. The client is the controlled variable,
 * the server is the independent one, so any difference in the capture
 * is a Kestrel divergence and nothing else.
 *
 * <p>Two artifacts are written per run:
 * <ul>
 *   <li>{@code <out>} readable structured decode, one stable line per
 *       field, built for {@code diff -u}.</li>
 *   <li>{@code <out>.hex} full payload hex, one packet per record,
 *       for the byte-for-byte comparison.</li>
 * </ul>
 *
 * <p>Both files are deterministic: no timestamps, no addresses, no
 * iteration-order dependence. The only client-side nondeterminism
 * (profile UUID) is pinned to a name-derived offline UUID.
 *
 * <p>Usage:
 * <pre>
 * java -cp "$(cat ~/kestrel-run/cp.txt)" dev.kestrel.net.ConfigProbe \
 *     --host 127.0.0.1 --port 25599 --out /tmp/kestrel.capture
 * </pre>
 *
 * <p>Always point it at a throwaway server. 25577 is the live play server,
 * so the probe refuses that port unless {@code --allow-live} is passed: an
 * accidental live capture has already happened once.
 *
 * <p>Options: {@code --packs echo|none|extra} controls the
 * SelectKnownPacks answer (echo is what a stock client does, extra
 * models a client that reports an additional pack), {@code --vd N}
 * sets the advertised view distance, {@code --play N} continues into
 * play and captures N chunk packets so an identical column can be
 * compared across servers, {@code --dump-ids} prints the vanilla
 * configuration protocol id tables and exits.
 */
public final class ConfigProbe {

    private ConfigProbe() {
    }

    /** Hard stop for the whole conversation. */
    private static final long RUN_BUDGET_MS = 120_000L;

    /** Per-read socket timeout while waiting for the next packet. */
    private static final int READ_TIMEOUT_MS = 20_000;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * When set, RegistryData entries are rendered as SNBT text instead
     * of a digest. A digest tells you THAT two servers disagree, this
     * tells you what about. Off by default because it makes the
     * capture roughly fifty times larger.
     */
    private static boolean snbt;

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 25577;
        String name = "ProbeBot";
        String out = "probe.capture";
        String packs = "echo";
        int viewDistance = 2;
        int protocol = Wire.PROTOCOL_26_2;
        int playChunks = 0;
        boolean dumpIds = false;
        boolean allowLive = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--name" -> name = args[++i];
                case "--out" -> out = args[++i];
                case "--packs" -> packs = args[++i];
                case "--vd" -> viewDistance = Integer.parseInt(args[++i]);
                case "--protocol" -> protocol = Integer.parseInt(args[++i]);
                case "--play" -> playChunks = Integer.parseInt(args[++i]);
                case "--snbt" -> snbt = true;
                case "--dump-ids" -> dumpIds = true;
                case "--allow-live" -> allowLive = true;
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }

        if (dumpIds) {
            dumpIds();
            return;
        }

        if (port == 25577 && !allowLive) {
            System.out.println("[probe] refusing to connect to port 25577: that is the live play"
                    + " server, which must never be probed. Point --port at a throwaway server,"
                    + " or pass --allow-live if you really mean to probe the live one.");
            System.exit(2);
        }

        Capture cap = new Capture();
        int exit = 0;
        try {
            run(host, port, name, packs, viewDistance, protocol, playChunks, cap);
        } catch (Exception e) {
            cap.note("ABORTED " + e.getClass().getSimpleName() + ": " + e.getMessage());
            exit = 1;
        }
        Files.writeString(Path.of(out), cap.structured.toString(), StandardCharsets.UTF_8);
        Files.writeString(Path.of(out + ".hex"), cap.hex.toString(), StandardCharsets.UTF_8);
        System.out.println("[probe] wrote " + out + " (" + cap.packets + " packets) and "
                + out + ".hex");
        if (exit != 0) {
            System.out.println("[probe] run did not complete cleanly, see the capture tail");
        }
        System.exit(exit);
    }

    // ---- conversation ----

    private static void run(String host, int port, String name, String packs,
            int viewDistance, int protocol, int playChunks, Capture cap) throws Exception {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            Conn conn = new Conn(socket);

            cap.note("probe protocol=" + protocol + " packs=" + packs
                    + " viewDistance=" + viewDistance + " playChunks=" + playChunks);

            // Handshake: the server never answers this, so nothing to capture.
            conn.send(PacketIds.HS_INTENTION, new Wire.Writer()
                    .vint(protocol).utf(host).s(port).vint(PacketIds.INTENT_LOGIN).toBytes());
            // LoginHello with a name-derived offline UUID, so the capture
            // is identical across runs of the same name.
            conn.send(PacketIds.LOGIN_HELLO, new Wire.Writer()
                    .utf(name).uuid(Wire.offlineUuid(name)).toBytes());

            long deadline = System.currentTimeMillis() + RUN_BUDGET_MS;
            String phase = "login";
            boolean configDone = false;
            int chunksSeen = 0;

            while (System.currentTimeMillis() < deadline) {
                Wire.Inbound in = conn.read();
                cap.record(phase, in);

                if (phase.equals("login")) {
                    if (in.id() == PacketIds.LOGIN_COMPRESSION) {
                        // Threshold applies from the NEXT packet on, both
                        // directions. Setting it late corrupts the stream.
                        conn.threshold = in.reader().varInt();
                        cap.note("compression threshold=" + conn.threshold);
                    } else if (in.id() == PacketIds.LOGIN_HELLO_OUT) {
                        throw new IOException("server requested encryption:"
                                + " run it with online-mode=false");
                    } else if (in.id() == PacketIds.LOGIN_DISCONNECT) {
                        throw new IOException("login disconnect: "
                                + new String(in.payload(), StandardCharsets.UTF_8));
                    } else if (in.id() == PacketIds.LOGIN_FINISHED) {
                        conn.sendC(PacketIds.LOGIN_ACK, new byte[0]);
                        phase = "config";
                        // A stock client volunteers its information and brand
                        // immediately on entering configuration. Vanilla's
                        // ServerConfigurationPacketListenerImpl needs the
                        // ClientInformation before it will run its task queue.
                        conn.sendC(PacketIds.CONFIG_CLIENT_INFO, clientInformation(viewDistance));
                        conn.sendC(SB_CONFIG_CUSTOM_PAYLOAD, new Wire.Writer()
                                .utf("minecraft:brand")
                                .bytes(new Wire.Writer().utf("vanilla").toBytes())
                                .toBytes());
                    }
                    continue;
                }

                if (phase.equals("config")) {
                    if (in.id() == PacketIds.CONFIG_SELECT_KNOWN_OUT) {
                        conn.sendC(PacketIds.CONFIG_SELECT_KNOWN,
                                knownPacksReply(in.payload(), packs));
                    } else if (in.id() == PacketIds.CONFIG_KEEPALIVE_OUT) {
                        conn.sendC(PacketIds.CONFIG_KEEPALIVE_IN, in.payload());
                    } else if (in.id() == PacketIds.CONFIG_DISCONNECT) {
                        throw new IOException("config disconnect, see capture tail");
                    } else if (in.id() == PacketIds.CONFIG_FINISH_OUT) {
                        configDone = true;
                        cap.note("configuration phase complete");
                        if (playChunks <= 0) {
                            return;
                        }
                        conn.sendC(PacketIds.CONFIG_FINISH, new byte[0]);
                        phase = "play";
                    }
                    continue;
                }

                // play: only what is needed to keep the server streaming.
                if (in.id() == PacketIds.PLAY_KEEPALIVE_OUT) {
                    conn.sendC(PacketIds.PLAY_KEEPALIVE_IN, in.payload());
                } else if (in.id() == PacketIds.PLAY_POSITION) {
                    int teleportId = in.reader().varInt();
                    conn.sendC(PacketIds.PLAY_ACCEPT_TELEPORT,
                            new Wire.Writer().vint(teleportId).toBytes());
                    conn.sendC(PacketIds.PLAY_PLAYER_LOADED, new byte[0]);
                } else if (in.id() == PacketIds.PLAY_CHUNK_BATCH_FINISHED) {
                    // Unthrottled ack, otherwise the server stops after one batch.
                    conn.sendC(PacketIds.PLAY_CHUNK_BATCH_RECEIVED,
                            new Wire.Writer().f(64.0f).toBytes());
                } else if (in.id() == PacketIds.PLAY_CHUNK) {
                    chunksSeen++;
                    if (chunksSeen >= playChunks) {
                        cap.note("captured " + chunksSeen + " chunk packets");
                        return;
                    }
                } else if (in.id() == PacketIds.PLAY_DISCONNECT) {
                    throw new IOException("play disconnect, see capture tail");
                }
            }
            if (!configDone) {
                throw new IOException("budget exhausted before FinishConfiguration");
            }
            throw new IOException("budget exhausted in play after "
                    + chunksSeen + " of " + playChunks + " chunks");
        }
    }

    /**
     * Serverbound custom_payload in configuration. Configuration
     * serverbound registration order is client_information 0,
     * cookie_response 1, custom_payload 2 ({@code ConfigurationProtocols}).
     */
    private static final int SB_CONFIG_CUSTOM_PAYLOAD = 0x02;

    /**
     * ClientInformation encoded through vanilla's own writer, so the
     * field order is correct by construction rather than by my reading
     * of the record. Vanilla's server parses this strictly and a wrong
     * layout disconnects the probe, which would look like a Kestrel
     * difference when it is a probe bug.
     */
    private static byte[] clientInformation(int viewDistance) {
        io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
        net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(raw);
        net.minecraft.server.level.ClientInformation info =
                new net.minecraft.server.level.ClientInformation(
                        "en_us",
                        viewDistance,
                        net.minecraft.world.entity.player.ChatVisiblity.FULL,
                        true,
                        0,
                        net.minecraft.world.entity.HumanoidArm.RIGHT,
                        false,
                        true,
                        net.minecraft.server.level.ParticleStatus.ALL);
        info.write(buf);
        byte[] outBytes = new byte[buf.readableBytes()];
        buf.readBytes(outBytes);
        return outBytes;
    }

    /**
     * Build the SelectKnownPacks answer.
     *
     * <ul>
     *   <li>{@code echo} return exactly what the server asked for. This
     *       is what a stock client with only the vanilla pack does, and
     *       it is the only answer that satisfies vanilla's
     *       {@code List.equals} check in SynchronizeRegistriesTask.</li>
     *   <li>{@code none} empty list, which forces the full-values path
     *       on both servers.</li>
     *   <li>{@code extra} echo plus one additional pack, modelling a
     *       client that reports a pack the server did not request.</li>
     * </ul>
     */
    private static byte[] knownPacksReply(byte[] requested, String mode) throws IOException {
        List<String[]> asked = new ArrayList<>();
        Wire.Reader r = new Wire.Reader(requested);
        int n = r.varInt();
        for (int i = 0; i < n; i++) {
            asked.add(new String[]{r.utf(), r.utf(), r.utf()});
        }
        List<String[]> reply = switch (mode) {
            case "echo" -> asked;
            case "none" -> List.of();
            case "extra" -> {
                List<String[]> withExtra = new ArrayList<>(asked);
                withExtra.add(new String[]{"probe", "extra", "1"});
                yield withExtra;
            }
            default -> throw new IllegalArgumentException("bad --packs: " + mode);
        };
        Wire.Writer w = new Wire.Writer().vint(reply.size());
        for (String[] p : reply) {
            w.utf(p[0]).utf(p[1]).utf(p[2]);
        }
        return w.toBytes();
    }

    // ---- socket plumbing ----

    /** Length-prefixed packet stream with an optional compression envelope. */
    private static final class Conn {
        private final InputStream in;
        private final OutputStream out;
        private final byte[] buf = new byte[8 << 20];
        private int len;
        int threshold = -1;

        Conn(Socket socket) throws IOException {
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        /** Send uncompressed (login, before the threshold packet). */
        void send(int id, byte[] payload) throws IOException {
            out.write(Wire.frame(id, payload, -1));
            out.flush();
        }

        /** Send under the negotiated threshold. */
        void sendC(int id, byte[] payload) throws IOException {
            out.write(Wire.frame(id, payload, threshold));
            out.flush();
        }

        Wire.Inbound read() throws IOException {
            while (true) {
                if (len > 0) {
                    int[] header = null;
                    try {
                        header = Wire.readVarInt(buf, 0);
                    } catch (IOException truncated) {
                        // Incomplete length prefix: fall through and read more.
                        header = null;
                    }
                    if (header != null && len >= header[1] + header[0]) {
                        byte[] frame = new byte[header[0]];
                        System.arraycopy(buf, header[1], frame, 0, header[0]);
                        int rest = len - header[1] - header[0];
                        System.arraycopy(buf, header[1] + header[0], buf, 0, rest);
                        len = rest;
                        return Wire.decode(Wire.unframe(frame, threshold));
                    }
                }
                if (len == buf.length) {
                    throw new IOException("frame larger than the " + buf.length + " byte buffer");
                }
                int n = in.read(buf, len, buf.length - len);
                if (n < 0) {
                    throw new IOException("server closed the connection");
                }
                len += n;
            }
        }
    }

    // ---- capture ----

    /** Accumulates the two output artifacts. */
    private static final class Capture {
        final StringBuilder structured = new StringBuilder();
        final StringBuilder hex = new StringBuilder();
        int packets;

        void note(String line) {
            structured.append("# ").append(line).append('\n');
            hex.append("# ").append(line).append('\n');
        }

        void record(String phase, Wire.Inbound in) {
            packets++;
            String head = String.format(Locale.ROOT, "[%03d] %s id=0x%02X len=%d sha1=%s",
                    packets, phase, in.id(), in.payload().length, sha1(in.payload()));
            structured.append(head).append('\n');
            hex.append(head).append('\n');
            hex.append(hexBlock(in.payload()));
            String decoded = decode(phase, in);
            if (decoded != null) {
                structured.append(decoded);
            }
        }
    }

    /**
     * Structured decode of the four config packets the investigation
     * turns on. Everything else is represented by its id, length and
     * sha1 in the header line, which is enough to spot a difference,
     * and by full hex in the companion file.
     */
    private static String decode(String phase, Wire.Inbound in) {
        try {
            if (phase.equals("config") && in.id() == PacketIds.CONFIG_SELECT_KNOWN_OUT) {
                return decodeSelectKnownPacks(in.payload());
            }
            if (phase.equals("config") && in.id() == PacketIds.CONFIG_REGISTRY_DATA) {
                return decodeRegistryData(in.payload());
            }
            if (phase.equals("config") && in.id() == PacketIds.CONFIG_TAGS) {
                return decodeUpdateTags(in.payload());
            }
            if (phase.equals("config") && in.id() == PacketIds.CONFIG_FEATURES) {
                return decodeStringList("      feature", in.payload());
            }
            if (phase.equals("config") && in.id() == PacketIds.CONFIG_FINISH_OUT) {
                return "      (FinishConfiguration, empty payload)\n";
            }
        } catch (Exception e) {
            return "      DECODE FAILED: " + e + '\n';
        }
        return null;
    }

    private static String decodeSelectKnownPacks(byte[] payload) throws IOException {
        StringBuilder sb = new StringBuilder();
        Wire.Reader r = new Wire.Reader(payload);
        int n = r.varInt();
        sb.append("      SelectKnownPacks count=").append(n).append('\n');
        for (int i = 0; i < n; i++) {
            sb.append("        pack ").append(r.utf()).append(':')
                    .append(r.utf()).append(':').append(r.utf()).append('\n');
        }
        return sb.toString();
    }

    /**
     * RegistryData decoded through vanilla's own stream codec. Doing it
     * by hand is not an option: entries carry length-free network NBT,
     * so skipping a value means parsing it, and a hand parser that is
     * subtly wrong would invent a diff.
     */
    private static String decodeRegistryData(byte[] payload) {
        io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.wrappedBuffer(payload);
        net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(raw);
        var pkt = net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket
                .STREAM_CODEC.decode(buf);
        StringBuilder sb = new StringBuilder();
        sb.append("      RegistryData registry=").append(pkt.registry().identifier())
                .append(" entries=").append(pkt.entries().size())
                .append(" trailing=").append(buf.readableBytes()).append('\n');
        int index = 0;
        for (var entry : pkt.entries()) {
            sb.append("        ").append(index++).append(' ').append(entry.id());
            if (entry.data().isEmpty()) {
                sb.append(" data=absent\n");
            } else if (snbt) {
                // Sorted so a pure field-order difference between two
                // serializers does not read as a value difference.
                sb.append(" data=\n");
                for (String line : snbtLines(entry.data().get())) {
                    sb.append("          ").append(line).append('\n');
                }
            } else {
                sb.append(" data=").append(sha1(nbtBytes(entry.data().get()))).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * One flattened {@code path = value} line per NBT leaf, sorted.
     *
     * <p>Flattening matters: two serializers can emit the same compound
     * with different field order and different-but-equivalent numeric
     * widths, and a raw SNBT diff would drown the real differences in
     * that noise. Sorting by path makes the diff show only fields whose
     * VALUE or presence actually differs.
     */
    private static List<String> snbtLines(net.minecraft.nbt.Tag tag) {
        List<String> out = new ArrayList<>();
        flatten("", tag, out);
        java.util.Collections.sort(out);
        return out;
    }

    private static void flatten(String path, net.minecraft.nbt.Tag tag, List<String> out) {
        if (tag instanceof net.minecraft.nbt.CompoundTag compound) {
            for (String key : compound.keySet()) {
                flatten(path + '.' + key, compound.get(key), out);
            }
            if (compound.isEmpty()) {
                out.add(path + " = {}");
            }
            return;
        }
        if (tag instanceof net.minecraft.nbt.ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(path + '[' + i + ']', list.get(i), out);
            }
            if (list.isEmpty()) {
                out.add(path + " = []");
            }
            return;
        }
        // Numeric suffixes (b/s/L/f/d) are dropped on purpose: a byte 1
        // and an int 1 are the same value to every codec that reads
        // them, and keeping the suffix would flag a non-difference.
        String text = tag.toString();
        if (tag instanceof net.minecraft.nbt.NumericTag number) {
            double d = number.doubleValue();
            text = d == Math.rint(d) && !Double.isInfinite(d)
                    ? Long.toString((long) d)
                    : Double.toString(d);
        }
        out.add(path + " = " + text);
    }

    /** Canonical bytes of one NBT value, for a stable per-entry fingerprint. */
    private static byte[] nbtBytes(net.minecraft.nbt.Tag tag) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream data = new java.io.DataOutputStream(bytes)) {
            net.minecraft.nbt.NbtIo.writeAnyTag(tag, data);
        } catch (IOException e) {
            throw new IllegalStateException("re-serializing NBT failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * UpdateTags: registry key -> tag id -> raw registry ids. Decoded by
     * hand because the shape is plain VarInts and vanilla hides the
     * payload map behind a private field with no accessor.
     *
     * <p>Lead (b) lives here: the ids are raw registry indices, so a
     * client whose registry order differs resolves every tag to the
     * wrong entries. The dump keeps wire order rather than sorting,
     * because order is exactly what is under test.
     */
    private static String decodeUpdateTags(byte[] payload) throws IOException {
        StringBuilder sb = new StringBuilder();
        Wire.Reader r = new Wire.Reader(payload);
        int registries = r.varInt();
        sb.append("      UpdateTags registries=").append(registries).append('\n');
        for (int i = 0; i < registries; i++) {
            String registry = r.utf();
            int tagCount = r.varInt();
            sb.append("        registry ").append(registry)
                    .append(" tags=").append(tagCount).append('\n');
            for (int t = 0; t < tagCount; t++) {
                String tag = r.utf();
                int idCount = r.varInt();
                StringBuilder ids = new StringBuilder();
                for (int k = 0; k < idCount; k++) {
                    if (k > 0) {
                        ids.append(',');
                    }
                    ids.append(r.varInt());
                }
                sb.append("          ").append(tag).append(" n=").append(idCount)
                        .append(" ids=").append(ids).append('\n');
            }
        }
        sb.append("        trailing=").append(payload.length - r.consumed()).append('\n');
        return sb.toString();
    }

    private static String decodeStringList(String label, byte[] payload) throws IOException {
        StringBuilder sb = new StringBuilder();
        Wire.Reader r = new Wire.Reader(payload);
        int n = r.varInt();
        for (int i = 0; i < n; i++) {
            sb.append(label).append(' ').append(r.utf()).append('\n');
        }
        return sb.toString();
    }

    // ---- vanilla protocol id tables ----

    /**
     * Print the real 26.2 configuration id tables. The probe speaks
     * these ids from {@link PacketIds}, so this is the cheap way to
     * prove the constants still match the jar.
     */
    private static void dumpIds() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        System.out.println("-- configuration clientbound --");
        ((net.minecraft.network.ProtocolInfo.DetailsProvider)
                net.minecraft.network.protocol.configuration.ConfigurationProtocols
                        .CLIENTBOUND_TEMPLATE)
                .details().listPackets((type, id) ->
                        System.out.printf(Locale.ROOT, "  %3d 0x%02X %s%n", id, id, type.id()));
        System.out.println("-- configuration serverbound --");
        ((net.minecraft.network.ProtocolInfo.DetailsProvider)
                net.minecraft.network.protocol.configuration.ConfigurationProtocols
                        .SERVERBOUND_TEMPLATE)
                .details().listPackets((type, id) ->
                        System.out.printf(Locale.ROOT, "  %3d 0x%02X %s%n", id, id, type.id()));
    }

    // ---- formatting ----

    private static String hexBlock(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int off = 0; off < data.length; off += 32) {
            sb.append(String.format(Locale.ROOT, "  %06X  ", off));
            int end = Math.min(off + 32, data.length);
            for (int i = off; i < end; i++) {
                sb.append(HEX[(data[i] >> 4) & 0xF]).append(HEX[data[i] & 0xF]);
            }
            sb.append('\n');
        }
        if (data.length == 0) {
            sb.append("  (empty)\n");
        }
        return sb.toString();
    }

    private static String sha1(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest) {
                sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 missing", e);
        }
    }
}
