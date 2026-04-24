package me.zed_0xff.zombie_buddy;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies {@code .jar.zbs} sidecars signed with Ed25519 (see ZModUnbork Gradle {@code signJarZBS}).
 * The sidecar has three lines ({@code signature} is 128 hex chars); {@code SteamID64} must be SteamID64 (17-digit account id).
 * The Ed25519 public key (64 hex) is read from the author's Steam profile page as {@code JavaModZBS:...} (profile summary).
 */
public final class ZBSVerifier {

    /** SteamID64: 17-digit decimal account id (Workshop {@code creator} uses the same form). */
    private static final Pattern LINE_STEAM_ID = Pattern.compile("^SteamID64:(\\d{17})$");
    /** Ed25519 Signature: 64 bytes as 128 hex characters. */
    private static final Pattern LINE_SIGNATURE = Pattern.compile("^Signature:([0-9a-fA-F]{128})$");
    /** Pubkey may appear anywhere in the profile HTML (summary, etc.). */
    private static final Pattern JAVA_MOD_ZBS_IN_HTML = Pattern.compile("JavaModZBS:([0-9a-fA-F]{64})");

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private ZBSVerifier() {}

    /**
     * @param jarSha256Hex lowercase hex SHA-256 of the JAR (same as Loader uses)
     */
    public static Verification verify(File jarFile, File zbsFile, String jarSha256Hex) {
        return verify(jarFile, zbsFile, jarSha256Hex, null);
    }

    /**
     * @param uploaderID Workshop item {@code creator} (SteamID64) when the mod is installed from the Workshop content path;
     *        {@code null} to skip uploader binding (e.g. no workshop id in path).
     */
    public static Verification verify(
        File jarFile,
        File zbsFile,
        String jarSha256Hex,
        SteamID64 uploaderID
    ) {
        if (zbsFile == null || !zbsFile.isFile()) {
            return new MissingSignature(null, "Missing .zbs file next to JAR: " + (zbsFile != null ? zbsFile.getName() : ""));
        }
        SteamID64 sid;
        byte[] sig;
        try {
            ParsedZBS p = parseZBS(zbsFile);
            sid = p.sid;
            sig = p.signature;
        } catch (IOException e) {
            return new InvalidSignature(null, "Could not read .zbs: " + e.getMessage());
        }
        if (uploaderID != null) {
            if (!uploaderID.equals(sid)) {
                return new InvalidSignature(sid, "Declared SteamID64 does not match Workshop item uploader.");
            }
        }
        List<String> pubHexes;
        try {
            pubHexes = fetchJavaModZBSHexesFromSteam(sid);
        } catch (Exception e) {
            return new VerificationError(sid, e.getMessage(), Collections.emptyList());
        }
        if (pubHexes.isEmpty()) {
            return new VerificationError(
                sid,
                "Could not find JavaModZBS:<64 hex> on Steam profile — add it to your profile summary.",
                pubHexes
            );
        }
        try {
            String canonical = "ZBS:" + sid.value() + ":" + jarSha256Hex.toLowerCase(Locale.ROOT);
            byte[] msg = canonical.getBytes(StandardCharsets.UTF_8);
            for (String pubHex : pubHexes) {
                byte[] pubRaw;
                try {
                    pubRaw = hexToBytes(pubHex);
                } catch (Exception e) {
                    return new VerificationError(sid, "Invalid JavaModZBS hex on Steam profile.", pubHexes);
                }
                if (pubRaw.length != 32) {
                    return new VerificationError(sid, "JavaModZBS on Steam profile must be 64 hex chars (32-byte Ed25519 public key).", pubHexes);
                }
                Ed25519PublicKeyParameters pub = new Ed25519PublicKeyParameters(pubRaw, 0);
                Ed25519Signer signer = new Ed25519Signer();
                signer.init(false, pub);
                signer.update(msg, 0, msg.length);
                boolean ok = signer.verifySignature(sig);
                if (ok) {
                    return new ValidSignature(sid, pubHexes);
                }
            }
            return new InvalidSignature(sid, "Invalid signature — JAR may have been tampered with.", pubHexes);
        } catch (Exception e) {
            return new VerificationError(sid, e.getMessage(), pubHexes);
        }
    }

    /** Steam Community profile URL for SteamID64 from {@code .zbs}. */
    public static String steamProfileUrl(String sid) {
        return "https://steamcommunity.com/profiles/" + sid + "/";
    }

    private static List<String> fetchJavaModZBSHexesFromSteam(SteamID64 sid) throws IOException {
        String url = steamProfileUrl(sid.toString());
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(25))
            .header(
                "User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 ZombieBuddy"
            )
            .GET()
            .build();
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching Steam profile.", e);
        }
        int code = resp.statusCode();
        if (code != 200) {
            throw new IOException("Could not load Steam profile (HTTP " + code + ").");
        }
        String body = resp.body();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Matcher m = JAVA_MOD_ZBS_IN_HTML.matcher(body);
        while (m.find()) {
            keys.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(keys);
    }

    private static final class ParsedZBS {
        final SteamID64 sid;
        final byte[] signature;

        ParsedZBS(SteamID64 sid, byte[] signature) {
            this.sid = sid;
            this.signature = signature;
        }
    }

    private static ParsedZBS parseZBS(File zbsFile) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(zbsFile.toPath(), StandardCharsets.UTF_8)) {
            String l1 = r.readLine();
            String l2 = r.readLine();
            String l3 = r.readLine();
            if (l1 == null || l2 == null || l3 == null) {
                throw new IOException("Expected at least 3 lines (ZBS, SteamID64, Signature)");
            }
            l1 = l1.trim();
            l2 = l2.trim();
            l3 = l3.trim();
            if (!"ZBS".equals(l1)) {
                throw new IOException("First line must be ZBS");
            }
            Matcher m2 = LINE_STEAM_ID.matcher(l2);
            if (!m2.matches()) {
                throw new IOException("Second line must be SteamID64:<17 dec>");
            }
            Matcher m3 = LINE_SIGNATURE.matcher(l3);
            if (!m3.matches()) {
                throw new IOException("Third line must be Signature:<128 hex>");
            }
            SteamID64 sid = new SteamID64(m2.group(1));
            byte[] sig;
            try {
                sig = hexToBytes(m3.group(1));
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid signature hex: " + e.getMessage());
            }
            return new ParsedZBS(sid, sig);
        }
    }

    private static byte[] hexToBytes(String hex) {
        String h = hex.trim().toLowerCase(Locale.ROOT);
        if ((h.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        int n = h.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int hi = Character.digit(h.charAt(i * 2), 16);
            int lo = Character.digit(h.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("non-hex");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public abstract static class Verification {
        /** Typed SteamID64 from .zbs when available; null on missing/unreadable signature files. */
        public final SteamID64 sid;
        /** Short human-readable message for compact UI cells. */
        public final String shortMessage;
        /** Extended human-readable message for tooltips/logging. */
        public final String detailedMessage;
        /** JavaModZBS keys parsed from profile HTML (lowercase hex). */
        public final List<String> profileKeys;

        protected Verification(SteamID64 sid, String shortMessage, String detailedMessage) {
            this(sid, shortMessage, detailedMessage, Collections.emptyList());
        }

        protected Verification(SteamID64 sid, String shortMessage, String detailedMessage, List<String> profileKeys) {
            this.sid = sid;
            this.shortMessage = shortMessage != null ? shortMessage : "";
            this.detailedMessage = detailedMessage != null ? detailedMessage : "";
            this.profileKeys = profileKeys == null ? Collections.emptyList() : List.copyOf(profileKeys);
        }
    }

    /** Signature is valid for this JAR hash and Steam author key. */
    public static final class ValidSignature extends Verification {
        public ValidSignature(SteamID64 sid) {
            super(sid, "", "");
        }

        public ValidSignature(SteamID64 sid, List<String> profileKeys) {
            super(sid, "", "", profileKeys);
        }
    }

    /** Missing .zbs sidecar file (caller may allow this as unsigned). */
    public static final class MissingSignature extends Verification {
        public MissingSignature(SteamID64 sid, String message) {
            super(sid, "Missing signature file.", message);
        }
    }

    /** .zbs exists but signature does not validate or is malformed. */
    public static final class InvalidSignature extends Verification {
        public InvalidSignature(SteamID64 sid, String message) {
            super(sid, "Invalid signature.", message);
        }

        public InvalidSignature(SteamID64 sid, String message, List<String> profileKeys) {
            super(sid, "Invalid signature.", message, profileKeys);
        }
    }

    /** Verification failed due to external/operational problems (Steam API/profile/key fetch, etc.). */
    public static final class VerificationError extends Verification {
        public VerificationError(SteamID64 sid, String message) {
            super(sid, "Could not verify signature.", message);
        }

        public VerificationError(SteamID64 sid, String message, List<String> profileKeys) {
            super(sid, "Could not verify signature.", message, profileKeys);
        }
    }
}
