package dev.kestrel.net;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import dev.kestrel.net.Outbound.ProfileProperty;

/**
 * Stage 3b online-mode login crypto: RSA challenge, AES/CFB8 link
 * encryption, Mojang session verification. All constants grounded in
 * the 26.2 decompile:
 *
 * <ul>
 *   <li>Key pair: RSA 1024 ({@code Crypt.java:31,68-76}).</li>
 *   <li>Challenge: 4 random bytes, sent raw as a byte array
 *       ({@code ServerLoginPacketListenerImpl.java:70},
 *       {@code ClientboundHelloPacket.java:34-38}). The client
 *       RSA-encrypts the challenge back with the server public key;
 *       Kestrel decrypts and compares
 *       ({@code ServerboundKeyPacket.java:47-53}).</li>
 *   <li>Shared secret: client generates 16 AES bytes, RSA-encrypts
 *       with the server public key; Kestrel RSA-decrypts to a
 *       {@code SecretKeySpec(key, "AES")}
 *       ({@code Crypt.java:155-163}).</li>
 *   <li>Link cipher: {@code AES/CFB8/NoPadding}, IV = raw secret
 *       bytes, opmodes 1 (encrypt) and 2 (decrypt)
 *       ({@code Crypt.java:187-195}). Applied to the whole framed
 *       stream in both directions, added BEFORE the splitter/prepender
 *       ({@code Connection.java:529-531}): on the raw-TCP server that
 *       means every byte read is decrypted first and every byte
 *       written is encrypted last.</li>
 *   <li>Server hash: SHA-1 over ISO-8859-1 server id (empty string for
 *       Kestrel, like vanilla {@code ServerLoginPacketListenerImpl.java:63})
 *       + raw secret bytes + X.509 public key bytes
 *       ({@code Crypt.java:78-94}), rendered as unsigned hex
 *       ({@code ServerLoginPacketListenerImpl.java:184}) and passed to
 *       Mojang {@code hasJoinedServer} ({@code :203}).</li>
 * </ul>
 *
 * <p>Thread-safety: the RSA key pair is generated once per NetServer
 * (vanilla generates it once per MinecraftServer) and shared
 * read-only. One SessionCipher per connection holds its challenge and
 * link ciphers. The HTTPS verify runs on a short-lived auth thread,
 * like vanilla's {@code User Authenticator} ({@code :193-236}).
 */
public final class SessionCipher {

    /** Mojang session endpoint for {@code hasJoinedServer}. */
    static final String SESSION_URL =
            "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private SessionCipher() {
    }

    /** Generate the server RSA-1024 key pair (once per NetServer). */
    public static KeyPair generateKeyPair() throws IOException {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(1024);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IOException("RSA key generation failed", e);
        }
    }

    /** Mint a fresh 4-byte login challenge. */
    public static byte[] freshChallenge() {
        byte[] challenge = new byte[4];
        RANDOM.nextBytes(challenge);
        return challenge;
    }

    /** Parsed ServerboundKeyPacket: RSA-encrypted secret + challenge. */
    public record KeyPacket(byte[] encryptedSecret, byte[] encryptedChallenge) {
        static KeyPacket parse(byte[] payload) throws IOException {
            Wire.Reader r = new Wire.Reader(payload);
            return new KeyPacket(r.byteArray(), r.byteArray());
        }
    }

    /** Result of decrypting a key packet: AES secret plus challenge match flag. */
    public record UnwrappedSecret(SecretKey secret, boolean challengeOk) {
    }

    /**
     * RSA-decrypt both key-packet fields with the server private key and
     * rebuild the AES secret. Mirrors {@code ServerboundKeyPacket}:
     * {@code getSecretKey} ({@code ServerboundKeyPacket.java:43-45}) plus
     * {@code isChallengeValid} ({@code :47-53}).
     */
    public static UnwrappedSecret unwrap(KeyPacket packet, PrivateKey privateKey,
            byte[] expectedChallenge) throws IOException {
        try {
            Cipher rsa = Cipher.getInstance("RSA");
            rsa.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] secretBytes = rsa.doFinal(packet.encryptedSecret());
            SecretKey secret = new SecretKeySpec(secretBytes, "AES");
            rsa.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] challengeBytes = rsa.doFinal(packet.encryptedChallenge());
            boolean ok = java.util.Arrays.equals(expectedChallenge, challengeBytes);
            return new UnwrappedSecret(secret, ok);
        } catch (Exception e) {
            throw new IOException("key packet decrypt failed", e);
        }
    }

    /** Stream cipher pair for the encrypted link (1 = encrypt, 2 = decrypt). */
    public record LinkCiphers(Cipher decrypt, Cipher encrypt) {
    }

    /** Build the AES/CFB8 link ciphers from the shared secret. */
    public static LinkCiphers linkCiphers(SecretKey secret) throws IOException {
        try {
            IvParameterSpec iv = new IvParameterSpec(secret.getEncoded());
            Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, secret, iv);
            Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE, secret, iv);
            return new LinkCiphers(decrypt, encrypt);
        } catch (Exception e) {
            throw new IOException("link cipher init failed", e);
        }
    }

    /**
     * Feed bytes through a stream cipher chunk-wise, like Netty's
     * {@code CipherBase.cipher} which calls {@code update} per inbound
     * buffer ({@code CipherBase.java:25-42}). CFB8 is a byte stream so
     * chunk boundaries do not matter.
     */
    public static byte[] update(Cipher cipher, byte[] in, int off, int len) throws IOException {
        try {
            byte[] out = new byte[cipher.getOutputSize(len)];
            int n = cipher.update(in, off, len, out);
            if (n == out.length) {
                return out;
            }
            byte[] trim = new byte[n];
            System.arraycopy(out, 0, trim, 0, n);
            return trim;
        } catch (Exception e) {
            throw new IOException("link cipher update failed", e);
        }
    }

    /**
     * Server hash for the session check: SHA-1(serverId + secret +
     * pubkey) ({@code Crypt.java:78-94}), rendered as SIGNED hex
     * ({@code ServerLoginPacketListenerImpl.java:184}: bare
     * {@code new BigInteger(digest)}). Negative digests get a leading
     * minus; the client computes the same signed form, so an unsigned
     * render here would never match Mojang's record.
     */
    public static String serverHash(PublicKey publicKey, SecretKey secret) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update("".getBytes(StandardCharsets.ISO_8859_1));
            sha1.update(secret.getEncoded());
            sha1.update(publicKey.getEncoded());
            return new BigInteger(sha1.digest()).toString(16);
        } catch (Exception e) {
            throw new IOException("server hash failed", e);
        }
    }

    /** Verified Mojang profile: real uuid plus skin properties. */
    public record VerifiedProfile(UUID id, String name, List<ProfileProperty> properties) {
    }

    /**
     * GET {@code hasJoinedServer} and parse the minimal profile shape
     * ({@code id} dashed-or-not, {@code name}, {@code properties[]} of
     * {@code name/value/signature}). Hand-rolled JSON scan: no Gson on
     * the vanilla classpath, and the response is one flat object.
     * Returns null when Mojang says the session is invalid (HTTP 204 /
     * empty body), mirroring vanilla's {@code result == null} branch
     * ({@code ServerLoginPacketListenerImpl.java:204-208}).
     */
    public static VerifiedProfile verify(String name, String hash) throws IOException {
        String url = String.format(SESSION_URL,
                URLEncoder.encode(name, StandardCharsets.UTF_8),
                URLEncoder.encode(hash, StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Kestrel/1.0")
                .GET()
                .build();
        HttpResponse<String> res;
        try {
            res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("session verify interrupted", e);
        } catch (Exception e) {
            throw new IOException("session verify request failed: " + e.getMessage(), e);
        }
        if (res.statusCode() == 204 || res.body() == null || res.body().isBlank()) {
            return null;
        }
        if (res.statusCode() != 200) {
            throw new IOException("session verify HTTP " + res.statusCode());
        }
        return parseProfile(res.body());
    }

    /** Parse the session-server profile JSON (hand-rolled, flat shape). */
    static VerifiedProfile parseProfile(String json) throws IOException {
        String id = stringField(json, "\"id\"");
        String name = stringField(json, "\"name\"");
        if (id == null || name == null) {
            throw new IOException("session verify: missing id/name in " + json);
        }
        List<ProfileProperty> props = new ArrayList<>();
        int arr = json.indexOf("\"properties\"");
        if (arr >= 0) {
            int start = json.indexOf('[', arr);
            int end = start >= 0 ? json.indexOf(']', start) : -1;
            if (start >= 0 && end > start) {
                String body = json.substring(start + 1, end);
                for (String obj : splitObjects(body)) {
                    String pn = stringField(obj, "\"name\"");
                    String pv = stringField(obj, "\"value\"");
                    String ps = stringField(obj, "\"signature\"");
                    if (pn != null && pv != null) {
                        props.add(new ProfileProperty(pn, pv, ps));
                    }
                }
            }
        }
        return new VerifiedProfile(dashedUuid(id), name, List.copyOf(props));
    }

    /** Extract a top-level "key": "value" string from a flat JSON object. */
    static String stringField(String json, String key) {
        int k = json.indexOf(key);
        if (k < 0) {
            return null;
        }
        int colon = json.indexOf(':', k + key.length());
        if (colon < 0) {
            return null;
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = q1 + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(n);
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    /** Split top-level {...} objects inside a JSON array body. */
    static List<String> splitObjects(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inStr = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(body.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return out;
    }

    /** Mojang ids are undashed hex; insert dashes for UUID parse. */
    static UUID dashedUuid(String id) throws IOException {
        String hex = id.replace("-", "");
        if (hex.length() != 32) {
            throw new IOException("bad profile id " + id);
        }
        String dashed = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-"
                + hex.substring(20);
        try {
            return UUID.fromString(dashed);
        } catch (IllegalArgumentException e) {
            throw new IOException("bad profile id " + id, e);
        }
    }

    /** Rebuild an X.509 RSA public key (for tests). */
    static PublicKey publicKey(byte[] encoded) throws IOException {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IOException("bad public key", e);
        }
    }
}
