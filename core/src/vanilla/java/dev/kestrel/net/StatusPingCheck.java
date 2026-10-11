package dev.kestrel.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.kestrel.loop.KestrelLevel;
import dev.kestrel.loop.TickLoop;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.players.NameAndId;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Roadmap S34 reproduction gate: verifies server list ping against vanilla 26.2.
 *
 * <p>Validates:
 * <ol>
 *   <li>Handshake intent 1 (status) with arbitrary protocol version.</li>
 *   <li>Serverbound status request and clientbound status response JSON shape.</li>
 *   <li>Ping/pong exchange echoing the 64-bit timestamp, followed by disconnect.</li>
 *   <li>Favicon loading from server-icon.png (valid 64x64 PNG included, invalid omitted).</li>
 *   <li>Player count, max players, and sample list serialization.</li>
 *   <li>Section-sign color codes in MOTD description.</li>
 *   <li>Malformed input handling: duplicate status request, ping before status, unknown packets.</li>
 *   <li>Legacy pings (&lt;1.3, 1.4-1.5, and 1.6 custom payload) matching LegacyQueryHandler.</li>
 *   <li>Differential oracle: diffs Kestrel's wire status response against vanilla's own
 *       ClientboundStatusResponsePacket.STREAM_CODEC and ServerStatus.CODEC.</li>
 * </ol>
 */
public final class StatusPingCheck {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private StatusPingCheck() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();

        Path tempDir = Files.createTempDirectory("kestrel-status-test-");
        try {
            checkStatusSuite(tempDir, failures);
        } finally {
            try (var s = Files.walk(tempDir)) {
                s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }

        if (!failures.isEmpty()) {
            System.err.println("StatusPingCheck FAILED with " + failures.size() + " failure(s):");
            for (String f : failures) {
                System.err.println("  - " + f);
            }
            System.exit(1);
        }
        System.out.println("StatusPingCheck: PASS");
    }

    private static void checkStatusSuite(Path tempDir, List<String> failures) {
        KestrelLevel level = new KestrelLevel(KestrelLevel.Dimension.OVERWORLD, -64, 384);
        TickLoop loop = new TickLoop(level);

        try (NetServer net = new NetServer(loop, 0, Wire.DEFAULT_COMPRESSION_THRESHOLD, 4242424242L, false)) {
            String testMotd = "§aKestrel §bTest §cServer";
            int testMaxPlayers = 42;
            net.setMotd(testMotd);
            net.setMaxPlayers(testMaxPlayers);
            net.setIconPath(tempDir.resolve("nonexistent-icon.png"));
            net.start();

            int port = net.boundPort();
            System.out.println("[StatusPingCheck] Kestrel listening on ephemeral port " + port);

            // 1. Basic modern status response without favicon
            testModernStatusWithoutFavicon(port, testMotd, testMaxPlayers, failures);

            // 2. Modern status response with valid 64x64 favicon
            Path iconPath = tempDir.resolve("server-icon.png");
            byte[] iconBytes = createTestPng(64, 64);
            Files.write(iconPath, iconBytes);
            net.setIconPath(iconPath);
            testModernStatusWithFavicon(port, testMotd, testMaxPlayers, iconBytes, failures);

            // 3. Invalid favicon dimension (32x32) is omitted
            Path badIconPath = tempDir.resolve("bad-icon.png");
            byte[] badIconBytes = createTestPng(32, 32);
            Files.write(badIconPath, badIconBytes);
            net.setIconPath(badIconPath);
            testModernStatusWithInvalidFavicon(port, failures);
            net.setIconPath(iconPath); // restore valid icon

            // 4. Player sample serialization when players are online
            testPlayerSample(net, port, failures);

            // 5. Ping/pong timestamp exchange and clean disconnect
            testPingPong(port, failures);

            // 6. Malformed and unexpected inputs
            testMalformedAndUnexpectedInputs(port, failures);

            // 7. Legacy ping protocols
            testLegacyPings(port, testMotd, testMaxPlayers, failures);

            // 8. Differential oracle: verify wire bytes and json against vanilla's exact codec
            testDifferentialOracle(port, testMotd, testMaxPlayers, iconBytes, failures);

        } catch (Exception e) {
            failures.add("Exception running StatusPingCheck suite: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private static void testModernStatusWithoutFavicon(int port, String expectedMotd, int expectedMax, List<String> failures) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendHandshake(out, port, Wire.PROTOCOL_26_2, PacketIds.INTENT_STATUS);
            sendStatusRequest(out);

            JsonObject json = readStatusResponse(in);
            assertField(json.has("version"), "status JSON missing version field", failures);
            if (json.has("version")) {
                JsonObject version = json.getAsJsonObject("version");
                assertEqual(version.get("name").getAsString(), "26.2", "version.name", failures);
                assertEqual(version.get("protocol").getAsInt(), Wire.PROTOCOL_26_2, "version.protocol", failures);
            }

            assertField(json.has("players"), "status JSON missing players field", failures);
            if (json.has("players")) {
                JsonObject players = json.getAsJsonObject("players");
                assertEqual(players.get("max").getAsInt(), expectedMax, "players.max", failures);
                assertEqual(players.get("online").getAsInt(), 0, "players.online", failures);
                assertField(!players.has("sample"), "empty sample must be omitted per vanilla", failures);
            }

            assertField(json.has("description"), "status JSON missing description field", failures);
            if (json.has("description")) {
                assertEqual(json.get("description").getAsString(), expectedMotd, "description", failures);
            }

            assertField(!json.has("favicon"), "favicon must be omitted when icon does not exist", failures);
            assertField(!json.has("enforcesSecureChat"), "enforcesSecureChat=false must be omitted per vanilla", failures);
        } catch (Exception e) {
            failures.add("testModernStatusWithoutFavicon threw: " + e);
        }
    }

    private static void testModernStatusWithFavicon(int port, String expectedMotd, int expectedMax, byte[] expectedBytes, List<String> failures) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendHandshake(out, port, Wire.PROTOCOL_26_2, PacketIds.INTENT_STATUS);
            sendStatusRequest(out);

            JsonObject json = readStatusResponse(in);
            assertField(json.has("favicon"), "favicon field must be present when server-icon.png exists", failures);
            if (json.has("favicon")) {
                String faviconUri = json.get("favicon").getAsString();
                String prefix = "data:image/png;base64,";
                assertField(faviconUri.startsWith(prefix), "favicon URI must start with data:image/png;base64,", failures);
                String base64Data = faviconUri.substring(prefix.length());
                byte[] decoded = Base64.getDecoder().decode(base64Data);
                assertEqual(decoded.length, expectedBytes.length, "favicon decoded byte length", failures);
                boolean matches = java.util.Arrays.equals(decoded, expectedBytes);
                assertField(matches, "favicon decoded bytes match server-icon.png exactly", failures);
            }
        } catch (Exception e) {
            failures.add("testModernStatusWithFavicon threw: " + e);
        }
    }

    private static void testModernStatusWithInvalidFavicon(int port, List<String> failures) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendHandshake(out, port, Wire.PROTOCOL_26_2, PacketIds.INTENT_STATUS);
            sendStatusRequest(out);

            JsonObject json = readStatusResponse(in);
            assertField(!json.has("favicon"), "invalid 32x32 favicon must be omitted per vanilla", failures);
        } catch (Exception e) {
            failures.add("testModernStatusWithInvalidFavicon threw: " + e);
        }
    }

    private static void testPlayerSample(NetServer net, int port, List<String> failures) {
        UUID testId = UUID.fromString("00000000-0000-0000-0000-000000000042");
        String testPlayerName = "TestPlayer";
        KestrelPlayer dummy = new KestrelPlayer(testId, testPlayerName, 0, 64, 0);
        net.players.put(testId, dummy);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendHandshake(out, port, Wire.PROTOCOL_26_2, PacketIds.INTENT_STATUS);
            sendStatusRequest(out);

            JsonObject json = readStatusResponse(in);
            JsonObject players = json.getAsJsonObject("players");
            assertField(players.has("sample"), "sample field must be present when players are online", failures);
            if (players.has("sample")) {
                var sample = players.getAsJsonArray("sample");
                assertEqual(sample.size(), 1, "sample array size", failures);
                JsonObject p = sample.get(0).getAsJsonObject();
                assertEqual(p.get("id").getAsString(), testId.toString(), "sample[0].id", failures);
                assertEqual(p.get("name").getAsString(), testPlayerName, "sample[0].name", failures);
            }
        } catch (Exception e) {
            failures.add("testPlayerSample threw: " + e);
        } finally {
            net.players.remove(testId);
        }
    }

    private static void testPingPong(int port, List<String> failures) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendHandshake(out, port, Wire.PROTOCOL_26_2, PacketIds.INTENT_STATUS);
            sendStatusRequest(out);
            readStatusResponse(in); // drain status response

            long pingTime = 9876543210123L;
            sendPing(out, pingTime);

            long pongTime = readPong(in);
            assertEqual(pongTime, pingTime, "pong timestamp echo", failures);

            // Vanilla disconnects immediately after sending pong
            int next = in.read();
            assertEqual(next, -1, "connection must be closed after pong", failures);
        } catch (Exception e) {
            failures.add("testPingPong threw: " + e);
        }
    }

    private static void testMalformedAndUnexpectedInputs(int port, List<String> failures) {
        // 1. Duplicate status request on same connection: vanilla disconnects without sending second response
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendHandshake(out, port, Wire.PROTOCOL_26_2, PacketIds.INTENT_STATUS);
            sendStatusRequest(out);
            readStatusResponse(in);

            sendStatusRequest(out); // second request
            int next = in.read();
            assertEqual(next, -1, "second status request must trigger immediate disconnect", failures);
        } catch (Exception e) {
            failures.add("duplicate status request threw: " + e);
        }

        // 2. Ping request sent before status request: vanilla responds with pong and disconnects
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendHandshake(out, port, Wire.PROTOCOL_26_2, PacketIds.INTENT_STATUS);
            long pingTime = 55555555L;
            sendPing(out, pingTime);

            long pongTime = readPong(in);
            assertEqual(pongTime, pingTime, "ping before status must echo timestamp", failures);
            int next = in.read();
            assertEqual(next, -1, "connection must be closed after pong", failures);
        } catch (Exception e) {
            failures.add("ping before status threw: " + e);
        }

        // 3. Unknown packet id in status state (0x02)
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendHandshake(out, port, Wire.PROTOCOL_26_2, PacketIds.INTENT_STATUS);
            byte[] badPacket = new byte[]{0x01, 0x02}; // length 1, id 2
            out.write(badPacket);
            out.flush();

            int next = in.read();
            assertEqual(next, -1, "unknown packet id in status must trigger disconnect", failures);
        } catch (Exception e) {
            failures.add("unknown packet in status threw: " + e);
        }

        // 4. Different protocol version in status handshake (e.g. protocol 765) is accepted
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendHandshake(out, port, 765, PacketIds.INTENT_STATUS);
            sendStatusRequest(out);
            JsonObject json = readStatusResponse(in);
            assertField(json.has("version"), "status intent accepts mismatched protocol versions", failures);
        } catch (Exception e) {
            failures.add("mismatched protocol in status handshake threw: " + e);
        }
    }

    private static void testLegacyPings(int port, String expectedMotd, int expectedMax, List<String> failures) {
        // 1. Pre-1.3 legacy ping (0xFE only)
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(0xFE);
            out.flush();

            String response = readLegacyResponse(in);
            String expected = String.format(java.util.Locale.ROOT, "%s§%d§%d", expectedMotd, 0, expectedMax);
            assertEqual(response, expected, "pre-1.3 legacy ping response", failures);

            int next = in.read();
            assertEqual(next, -1, "legacy ping socket must be closed after reply", failures);
        } catch (Exception e) {
            failures.add("pre-1.3 legacy ping threw: " + e);
        }

        // 2. 1.4-1.5 legacy ping (0xFE 0x01)
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(new byte[]{(byte) 0xFE, 0x01});
            out.flush();

            String response = readLegacyResponse(in);
            String expected = String.format(java.util.Locale.ROOT, "§1\u0000%d\u0000%s\u0000%s\u0000%d\u0000%d",
                    127, "26.2", expectedMotd, 0, expectedMax);
            assertEqual(response, expected, "1.4-1.5 legacy ping response", failures);

            int next = in.read();
            assertEqual(next, -1, "legacy 1.4-1.5 ping socket must be closed after reply", failures);
        } catch (Exception e) {
            failures.add("1.4-1.5 legacy ping threw: " + e);
        }

        // 3. 1.6 custom payload legacy ping (0xFE 0x01 0xFA MC|PingHost ...)
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(0xFE);
            baos.write(0x01);
            baos.write(0xFA); // packet 250
            writeLegacyString(baos, "MC|PingHost");
            // payload: protocol byte (73), hostname UTF-16BE, port int (4 bytes)
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(73); // protocol 73
            writeLegacyString(payload, "localhost");
            payload.write((port >> 24) & 0xFF);
            payload.write((port >> 16) & 0xFF);
            payload.write((port >> 8) & 0xFF);
            payload.write(port & 0xFF);
            byte[] pBytes = payload.toByteArray();
            baos.write((pBytes.length >> 8) & 0xFF);
            baos.write(pBytes.length & 0xFF);
            baos.write(pBytes);

            out.write(baos.toByteArray());
            out.flush();

            String response = readLegacyResponse(in);
            String expected = String.format(java.util.Locale.ROOT, "§1\u0000%d\u0000%s\u0000%s\u0000%d\u0000%d",
                    127, "26.2", expectedMotd, 0, expectedMax);
            assertEqual(response, expected, "1.6 custom payload legacy ping response", failures);

            int next = in.read();
            assertEqual(next, -1, "legacy 1.6 ping socket must be closed after reply", failures);
        } catch (Exception e) {
            failures.add("1.6 legacy ping threw: " + e);
        }
    }

    private static void testDifferentialOracle(int port, String motd, int maxPlayers, byte[] iconBytes, List<String> failures) {
        try {
            // 1. Build vanilla's reference ServerStatus using vanilla's exact dedicated server model
            ServerStatus vanillaStatus = new ServerStatus(
                    Component.nullToEmpty(motd),
                    Optional.of(new ServerStatus.Players(maxPlayers, 0, List.of())),
                    Optional.of(ServerStatus.Version.current()),
                    Optional.of(new ServerStatus.Favicon(iconBytes)),
                    false
            );

            // 2. Encode with vanilla's official ClientboundStatusResponsePacket.STREAM_CODEC
            var rawBuf = Unpooled.buffer();
            var friendlyBuf = new FriendlyByteBuf(rawBuf);
            ClientboundStatusResponsePacket packet = new ClientboundStatusResponsePacket(vanillaStatus);
            ClientboundStatusResponsePacket.STREAM_CODEC.encode(friendlyBuf, packet);

            // Read the JSON string encoded by vanilla's codec
            int vanillaStringLen = friendlyBuf.readVarInt();
            String vanillaJsonString = friendlyBuf.readCharSequence(vanillaStringLen, StandardCharsets.UTF_8).toString();
            JsonObject vanillaJson = GSON.fromJson(vanillaJsonString, JsonObject.class);

            // 3. Ping Kestrel over the raw socket and fetch Kestrel's live status JSON
            JsonObject kestrelJson;
            byte[] kestrelWirePayload;
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                sendHandshake(out, port, Wire.PROTOCOL_26_2, PacketIds.INTENT_STATUS);
                sendStatusRequest(out);

                int frameLen = readVarInt(in);
                int packetId = readVarInt(in);
                assertEqual(packetId, 0x00, "status response packet id", failures);
                int jsonLen = readVarInt(in);
                kestrelWirePayload = in.readNBytes(jsonLen);
                String kestrelJsonString = new String(kestrelWirePayload, StandardCharsets.UTF_8);
                kestrelJson = GSON.fromJson(kestrelJsonString, JsonObject.class);
            }

            // 4. Field-by-field diff between vanilla oracle and Kestrel
            System.out.println("[StatusPingCheck] Diffing Kestrel status JSON against vanilla reference...");
            assertEqual(kestrelJson.get("version"), vanillaJson.get("version"), "oracle diff: version", failures);
            assertEqual(kestrelJson.get("players"), vanillaJson.get("players"), "oracle diff: players", failures);
            assertEqual(kestrelJson.get("description"), vanillaJson.get("description"), "oracle diff: description", failures);
            assertEqual(kestrelJson.get("favicon"), vanillaJson.get("favicon"), "oracle diff: favicon", failures);
            assertEqual(kestrelJson.get("enforcesSecureChat"), vanillaJson.get("enforcesSecureChat"), "oracle diff: enforcesSecureChat", failures);

            // 5. Interoperability proof: feed Kestrel's raw wire payload directly into vanilla's codec decoder
            var kestrelBuf = new FriendlyByteBuf(Unpooled.buffer());
            kestrelBuf.writeVarInt(kestrelWirePayload.length);
            kestrelBuf.writeBytes(kestrelWirePayload);
            ClientboundStatusResponsePacket decodedByVanilla = ClientboundStatusResponsePacket.STREAM_CODEC.decode(kestrelBuf);

            assertField(decodedByVanilla != null, "vanilla STREAM_CODEC decodes Kestrel wire packet", failures);
            if (decodedByVanilla != null) {
                ServerStatus decodedStatus = decodedByVanilla.status();
                assertEqual(decodedStatus.description().getString(), motd, "vanilla decoded description", failures);
                assertField(decodedStatus.players().isPresent(), "vanilla decoded players present", failures);
                if (decodedStatus.players().isPresent()) {
                    assertEqual(decodedStatus.players().get().max(), maxPlayers, "vanilla decoded maxPlayers", failures);
                    assertEqual(decodedStatus.players().get().online(), 0, "vanilla decoded onlineCount", failures);
                }
                assertField(decodedStatus.version().isPresent(), "vanilla decoded version present", failures);
                if (decodedStatus.version().isPresent()) {
                    assertEqual(decodedStatus.version().get().name(), "26.2", "vanilla decoded version name", failures);
                    assertEqual(decodedStatus.version().get().protocol(), Wire.PROTOCOL_26_2, "vanilla decoded version protocol", failures);
                }
                assertField(decodedStatus.favicon().isPresent(), "vanilla decoded favicon present", failures);
                assertEqual(decodedStatus.enforcesSecureChat(), false, "vanilla decoded enforcesSecureChat", failures);
            }

        } catch (Exception e) {
            failures.add("testDifferentialOracle threw: " + e);
            e.printStackTrace(System.err);
        }
    }

    // ---- Helper network methods ----

    private static void sendHandshake(OutputStream out, int port, int protocolVersion, int intent) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, PacketIds.HS_INTENTION);
        writeVarInt(body, protocolVersion);
        writeString(body, "127.0.0.1");
        body.write((port >> 8) & 0xFF);
        body.write(port & 0xFF);
        writeVarInt(body, intent);

        byte[] bytes = body.toByteArray();
        writeVarInt(out, bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static void sendStatusRequest(OutputStream out) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, PacketIds.STATUS_REQUEST);
        byte[] bytes = body.toByteArray();
        writeVarInt(out, bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static JsonObject readStatusResponse(InputStream in) throws IOException {
        int frameLen = readVarInt(in);
        int packetId = readVarInt(in);
        if (packetId != PacketIds.STATUS_RESPONSE) {
            throw new IOException("expected packet id " + PacketIds.STATUS_RESPONSE + ", got " + packetId);
        }
        int jsonLen = readVarInt(in);
        byte[] jsonBytes = in.readNBytes(jsonLen);
        String jsonStr = new String(jsonBytes, StandardCharsets.UTF_8);
        return GSON.fromJson(jsonStr, JsonObject.class);
    }

    private static void sendPing(OutputStream out, long time) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, PacketIds.STATUS_PING);
        DataOutputStream dos = new DataOutputStream(body);
        dos.writeLong(time);

        byte[] bytes = body.toByteArray();
        writeVarInt(out, bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static long readPong(InputStream in) throws IOException {
        int frameLen = readVarInt(in);
        int packetId = readVarInt(in);
        if (packetId != PacketIds.STATUS_PONG) {
            throw new IOException("expected packet id " + PacketIds.STATUS_PONG + ", got " + packetId);
        }
        DataInputStream dis = new DataInputStream(in);
        return dis.readLong();
    }

    private static String readLegacyResponse(InputStream in) throws IOException {
        int packetId = in.read();
        if (packetId != 255) {
            throw new IOException("expected legacy disconnect packet 255, got " + packetId);
        }
        int charCount = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        byte[] chars = in.readNBytes(charCount * 2);
        return new String(chars, StandardCharsets.UTF_16BE);
    }

    private static void writeLegacyString(OutputStream out, String str) throws IOException {
        out.write((str.length() >> 8) & 0xFF);
        out.write(str.length() & 0xFF);
        out.write(str.getBytes(StandardCharsets.UTF_16BE));
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.read();
            if (b == -1) {
                throw new EOFException("unexpected end of stream reading varint");
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("varint too big");
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static byte[] createTestPng(int width, int height) {
        try {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    img.setRGB(x, y, 0xFF00AA00);
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertEqual(Object actual, Object expected, String message, List<String> failures) {
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(message + ": expected <" + expected + ">, got <" + actual + ">");
        }
    }

    private static void assertField(boolean condition, String message, List<String> failures) {
        if (!condition) {
            failures.add(message);
        }
    }
}
