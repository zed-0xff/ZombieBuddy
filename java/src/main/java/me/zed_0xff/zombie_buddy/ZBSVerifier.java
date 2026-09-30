package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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


    private ZBSVerifier() {}

    /** Loader-facing ZBS check result, including policy/UI information. */
    record CheckResult(
        ModFlags flags,
        SteamID64 sid,              // signer ID or null
        SteamID64 uploaderID,       // Workshop uploader ID or null
        String notice,              // UI notice text
        String blockReason,         // reason for blocking (if blocked)
        Verification verification   // the raw verification result, or null
    ) {
        static final CheckResult DISABLED = new CheckResult(ModFlags.EMPTY, null, null, "", null, null);

        static CheckResult unsignedAllowed(SteamID64 uploaderID) {
            return new CheckResult(new ModFlags(MF_VALID), null, uploaderID, "", null, null);
        }

        static CheckResult missingNotAllowed(SteamID64 uploaderID) {
            return new CheckResult(ModFlags.EMPTY, null, uploaderID, local.zbselective.i18n.UiText.text("Missing .zbs file (allow_unsigned_mods=false)", "缺少 .zbs 文件（allow_unsigned_mods=false）"),
                local.zbselective.i18n.UiText.text("missing .zbs file; allow_unsigned_mods=false", "缺少 .zbs 文件；allow_unsigned_mods=false"), null);
        }
    }

    /**
     * Perform a loader-facing ZBS check for a JAR file. This wraps the raw
     * signature verification with unsigned-mod policy, Workshop uploader binding,
     * UI notice text, and load-blocking decisions.
     */
    public static CheckResult check(
            Path jarPath,
            String jarHash,
            WorkshopItemID workshopItemId,
            boolean steamModeEnabled,
            boolean allowUnsignedMods,
            Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById
    ) {
        return check(jarPath, jarHash, workshopItemId, steamModeEnabled, allowUnsignedMods, workshopDetailsById, null);
    }

    public static CheckResult check(
            Path jarPath,
            String jarHash,
            WorkshopItemID workshopItemId,
            boolean steamModeEnabled,
            boolean allowUnsignedMods,
            Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById,
            Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthors
    ) {
        SteamID64 uploaderID = steamModeEnabled ? SteamWorkshop.getUploaderID(workshopItemId, workshopDetailsById) : null;
        Path zbsPath = jarPath.resolveSibling(jarPath.getFileName().toString() + ".zbs");

        if (!Files.isRegularFile(zbsPath)) {
            return allowUnsignedMods
                ? CheckResult.unsignedAllowed(uploaderID)
                : CheckResult.missingNotAllowed(uploaderID);
        }

        Verification zbs = verify(jarPath, zbsPath, jarHash, uploaderID, knownAuthors);

        boolean valid = zbs instanceof ValidSignature;
        String notice = noticeForUi(zbs);

        Logger.info("ZBS verification " + (valid ? "valid" : "invalid") + " for " + jarPath + ": " +
            "uploaderID=" + (uploaderID != null ? uploaderID : "N/A") +
            ", shortMessage=" + (zbs.shortMessage != null ? zbs.shortMessage.trim() : "null") +
            ", detailedMessage=" + (zbs.detailedMessage != null ? zbs.detailedMessage.trim() : "null"));

        if (valid) {
            return new CheckResult(new ModFlags(MF_VALID | MF_SIGNED), zbs.sid, uploaderID, notice, null, zbs);
        } else {
            return new CheckResult(new ModFlags(MF_SIGNED), zbs.sid, uploaderID, notice, "invalid ZBS: " + zbs.detailedMessage, zbs);
        }
    }


    /**
     * Format a raw ZBS verification result for UI display.
     */
    public static String noticeForUi(Verification zbs) {
        if (zbs == null) return "";
        String shortMsg = zbs.shortMessage != null ? zbs.shortMessage.trim() : "";
        String detail = zbs.detailedMessage != null ? zbs.detailedMessage.trim() : "";
        if (shortMsg.isEmpty()) return detail;
        if (detail.isEmpty() || shortMsg.equals(detail)) return shortMsg;
        return shortMsg + "\n" + detail;
    }

    /**
     * @param jarSha256Hex lowercase hex SHA-256 of the JAR (same as Loader uses)
     */
    public static Verification verify(Path jarPath, Path zbsPath, String jarSha256Hex) {
        return verify(jarPath, zbsPath, jarSha256Hex, null);
    }

    public static Verification verify(
        Path jarPath,
        Path zbsPath,
        String jarSha256Hex,
        SteamID64 uploaderID
    ) {
        return verify(jarPath, zbsPath, jarSha256Hex, uploaderID, Collections.emptyMap());
    }

    /**
     * @param uploaderID Workshop item {@code creator} (SteamID64) when the mod is installed from the Workshop content path;
     *        {@code null} to skip uploader binding (e.g. no workshop id in path).
     */
    public static Verification verify(
        Path jarPath,
        Path zbsPath,
        String jarSha256Hex,
        SteamID64 uploaderID,
        Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthors
    ) {
        if (zbsPath == null || !Files.isRegularFile(zbsPath)) {
            return new MissingSignature(null, local.zbselective.i18n.UiText.text("Missing .zbs file next to JAR: ", "JAR 旁缺少 .zbs 文件：") + zbsPath);
        }
        SteamID64 sid;
        byte[] sig;
        try {
            ParsedZBS p = parseZBS(zbsPath);
            sid = p.sid;
            sig = p.signature;
        } catch (IOException e) {
            return new InvalidSignature(null, local.zbselective.i18n.UiText.text("Could not read .zbs: ", "无法读取 .zbs：") + e.getMessage());
        }
        if (uploaderID != null) {
            if (!uploaderID.equals(sid)) {
                return new InvalidSignature(sid, local.zbselective.i18n.UiText.text("Declared SteamID64 does not match Workshop item uploader.", "声明的 SteamID64 与创意工坊上传者不匹配。"));
            }
        }
        List<String> pubHexes = knownJavaModZBSHexes(sid, knownAuthors);
        String keySource = local.zbselective.i18n.UiText.text("known authors list", "已知作者列表");
        if (pubHexes.isEmpty()) {
            keySource = local.zbselective.i18n.UiText.text("Steam profile", "Steam 个人资料");
            try {
                pubHexes = fetchJavaModZBSHexesFromSteam(sid);
            } catch (Exception e) {
                return new VerificationError(sid, e.getMessage(), Collections.emptyList());
            }
            if (pubHexes.isEmpty()) {
                return new VerificationError(
                    sid,
                    local.zbselective.i18n.UiText.text("Could not find JavaModZBS:<64 hex> on Steam profile — add it to your profile summary.", "在 Steam 个人资料中未找到 JavaModZBS:<64 hex>——请将其添加到个人资料简介中。"),
                    pubHexes
                );
            }
        }
        try {
            String canonical = "ZBS:" + sid.value() + ":" + jarSha256Hex.toLowerCase(Locale.ROOT);
            byte[] msg = canonical.getBytes(StandardCharsets.UTF_8);
            for (String pubHex : pubHexes) {
                byte[] pubRaw;
                try {
                    pubRaw = hexToBytes(pubHex);
                } catch (Exception e) {
                    return new VerificationError(sid, local.zbselective.i18n.UiText.text("Invalid JavaModZBS hex in ", "JavaModZBS 十六进制值无效，来源：") + keySource + ".", pubHexes);
                }
                if (pubRaw.length != 32) {
                    return new VerificationError(sid, local.zbselective.i18n.UiText.text("JavaModZBS in ", "JavaModZBS（来自 ") + keySource + local.zbselective.i18n.UiText.text(" must be 64 hex chars (32-byte Ed25519 public key).", "）必须为 64 个十六进制字符（32 字节 Ed25519 公钥）。"), pubHexes);
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
            return new InvalidSignature(sid, local.zbselective.i18n.UiText.text("Invalid signature — JAR may have been tampered with.", "签名无效——JAR 可能已被篡改。"), pubHexes);
        } catch (Exception e) {
            return new VerificationError(sid, e.getMessage(), pubHexes);
        }
    }

    private static List<String> knownJavaModZBSHexes(
        SteamID64 sid,
        Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthors
    ) {
        if (sid == null || knownAuthors == null) {
            return Collections.emptyList();
        }
        KnownAuthors.AuthorEntry author = knownAuthors.get(sid);
        if (author == null || Utils.isBlank(author.keys)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String key : author.keys) {
            if (key != null && !key.trim().isEmpty()) {
                keys.add(key.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(keys);
    }

    private static List<String> fetchJavaModZBSHexesFromSteam(SteamID64 sid) throws IOException {
        String url = SteamWorkshop.authorProfileUrl(sid);
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
            resp = NetworkClients.get(true).send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    private static ParsedZBS parseZBS(Path zbsPath) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(zbsPath, StandardCharsets.UTF_8)) {
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
            SteamID64 sid = new SteamID64(Long.parseLong(m2.group(1)));
            byte[] sig;
            try {
                sig = hexToBytes(m3.group(1));
            } catch (IllegalArgumentException e) {
                throw new IOException(local.zbselective.i18n.UiText.text("Invalid signature hex: ", "无效的签名十六进制值：") + e.getMessage());
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
        /** JavaModZBS keys used for verification (lowercase hex). */
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
            super(sid, local.zbselective.i18n.UiText.text("Missing signature file.", "缺少签名文件。"), message);
        }
    }

    /** .zbs exists but signature does not validate or is malformed. */
    public static final class InvalidSignature extends Verification {
        public InvalidSignature(SteamID64 sid, String message) {
            super(sid, local.zbselective.i18n.UiText.text("Invalid signature.", "签名无效。"), message);
        }

        public InvalidSignature(SteamID64 sid, String message, List<String> profileKeys) {
            super(sid, local.zbselective.i18n.UiText.text("Invalid signature.", "签名无效。"), message, profileKeys);
        }
    }

    /** Verification failed due to external/operational problems (Steam API/profile/key fetch, etc.). */
    public static final class VerificationError extends Verification {
        public VerificationError(SteamID64 sid, String message) {
            super(sid, local.zbselective.i18n.UiText.text("Could not verify signature.", "无法验证签名。"), message);
        }

        public VerificationError(SteamID64 sid, String message, List<String> profileKeys) {
            super(sid, local.zbselective.i18n.UiText.text("Could not verify signature.", "无法验证签名。"), message, profileKeys);
        }
    }
}
