package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches {@value #REMOTE_URL} and caches it under {@code ~/.zombie_buddy/}{@value #CACHE_FILE_NAME}.
 *
 * <p>File format: JSON object with {@code updated_at}, {@code authors}, and {@code signature} fields.
 * The signature is Ed25519 over {@code "ZBAuthors:" + sha256hex(file_with_signature_value_cleared)},
 * verified against the hardcoded {@link #AUTHORS_PUBKEY_HEX}.
 */
public final class KnownAuthors {
    public static final String REMOTE_URL      = "https://raw.githubusercontent.com/zed-0xff/ZombieBuddy/refs/heads/master/authors.json";
    public static final String CACHE_FILE_NAME = "authors.json";

    // Ed25519 public key for verifying authors.json. Same key as ZBS signing key, baked into
    // the JAR (which is X.509-signed), so it can't be tampered with at runtime.
    static final String AUTHORS_PUBKEY_HEX = "c0fe7daaa1fbd1ee54096d96669a7bbb10cd0a2ad08949d664d4804c50e34db1";

    // Matches the signature field value; groups: (prefix-with-quote) (hex) (closing-quote)
    private static final Pattern SIG_PATTERN =
        Pattern.compile("(\"signature\"\\s*:\\s*\")([0-9a-fA-F]*)(\")");


    private static final Type FILE_DATA_TYPE = new TypeToken<FileData>() {}.getType();

    private static final class FileData {
        @SerializedName("updated_at") String updatedAt;
        @SerializedName("authors")    List<AuthorEntry> authors;
        @SerializedName("signature")  String signature;
    }

    public static final class AuthorEntry {
        @SerializedName("id")
        public SteamID64 id;
        @SerializedName("name")
        public String name;
        @SerializedName("keys")
        public List<String> keys;
    }

    private KnownAuthors() {}

    public static Path cachePath() {
        return Agent.configDir().resolve(CACHE_FILE_NAME);
    }

    /**
     * Refreshes from the network when possible; verifies signature before caching.
     * Falls back to the local cache (also re-verified). Returns empty map if both fail.
     */
    public static Map<SteamID64, AuthorEntry> loadAuthors() {
        String body = fetchRemoteBody();
        if (body != null) {
            if (verifySignature(body)) {
                writeCache(body);
            } else {
                Logger.warn("authors.json: invalid signature from network, ignoring");
                body = null;
            }
        }
        if (body == null) {
            body = readCache();
            if (body != null && !verifySignature(body)) {
                Logger.warn("authors.json: invalid signature in cache, ignoring");
                body = null;
            }
        }
        return body != null ? parseAuthorsJSON(body) : Collections.emptyMap();
    }

    public static Map<SteamID64, String> loadSteamIdToDisplayName() {
        Map<SteamID64, String> out = new LinkedHashMap<>();
        for (Map.Entry<SteamID64, AuthorEntry> entry : loadAuthors().entrySet()) {
            AuthorEntry author = entry.getValue();
            if (author != null && !Utils.isBlank(author.name)) {
                out.put(entry.getKey(), author.name);
            }
        }
        return out;
    }

    public static List<AuthorEntry> loadAuthorEntries() {
        return new ArrayList<>(loadAuthors().values());
    }

    /**
     * Verifies the Ed25519 signature embedded in the authors.json body.
     * Canonical form: the raw body bytes with the signature hex value replaced by {@code ""}.
     * Signed payload: {@code "ZBAuthors:" + sha256hex(canonical_bytes)}.
     */
    static boolean verifySignature(String body) {
        Matcher m = SIG_PATTERN.matcher(body);
        if (!m.find()) {
            Logger.warn("authors.json: no 'signature' field found");
            return false;
        }
        String sigHex = m.group(2);
        if (sigHex.length() != 128) {
            Logger.warn("authors.json: signature wrong length (" + sigHex.length() + "), expected 128 hex chars");
            return false;
        }
        // Canonical: same bytes but signature value cleared to ""
        String canonical = m.replaceFirst("$1$3");
        String hashHex = Utils.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        if (hashHex == null) {
            Logger.warn("authors.json: failed to compute sha256 of canonical form");
            return false;
        }
        byte[] msg = ("ZBAuthors:" + hashHex).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] pubRaw = hexToBytes(AUTHORS_PUBKEY_HEX);
            Ed25519PublicKeyParameters pub = new Ed25519PublicKeyParameters(pubRaw, 0);
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, pub);
            signer.update(msg, 0, msg.length);
            boolean ok = signer.verifySignature(hexToBytes(sigHex));
            if (!ok) Logger.warn("authors.json: signature verification failed");
            return ok;
        } catch (Exception e) {
            Logger.warn("authors.json: signature verification error: " + e.getMessage());
            return false;
        }
    }

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) != 0) throw new IllegalArgumentException("odd hex length: " + hex.length());
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                           |  Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    private static void writeCache(String body) {
        try {
            Files.createDirectories(Agent.configDir());
            Utils.writeFileAtomic(cachePath(), body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.warn("Could not write author names cache: " + e.getMessage());
        }
    }

    private static String readCache() {
        try {
            Path p = cachePath();
            if (Files.isRegularFile(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Logger.warn("Could not read authors cache: " + e.getMessage());
        }
        return null;
    }

    private static String fetchRemoteBody() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(REMOTE_URL))
                .timeout(Duration.ofSeconds(25))
                .header(
                    "User-Agent",
                    "ZombieBuddy/KnownAuthors (Java; +https://github.com/zed-0xff/ZombieBuddy)"
                )
                .GET()
                .build();
            HttpResponse<String> resp = NetworkClients.get(true).send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                return resp.body();
            }
            Logger.warn("Authors list HTTP " + resp.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.warn("Authors list fetch interrupted");
        } catch (IOException e) {
            Logger.warn("Authors list fetch failed: " + e.getMessage());
        }
        return null;
    }

    static Map<SteamID64, AuthorEntry> parseAuthorsJSON(String body) {
        Map<SteamID64, AuthorEntry> out = new LinkedHashMap<>();
        if (Utils.isBlank(body)) {
            return out;
        }
        try {
            FileData data = ZBGson.PRETTY.fromJson(body, FILE_DATA_TYPE);
            if (data != null && data.authors != null) {
                for (AuthorEntry e : data.authors) {
                    if (e != null && e.id != null) {
                        out.put(e.id, e);
                    }
                }
            }
        } catch (Exception e) {
            Logger.warn("Failed to parse authors.json: " + e.getMessage());
        }
        return out;
    }
}
