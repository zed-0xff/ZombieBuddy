package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Utility methods for the mod loader.
 */
public final class LoaderUtils {

    /** Result of ZBS signature verification check. */
    public record ZBSCheckResult(
        String valid,                    // "yes", "no", "unsigned", or ""
        SteamID64 sid,                   // signer ID or null
        String notice,                   // UI notice text
        boolean shouldBlock,             // true if JAR should be blocked
        String blockReason,              // reason for blocking (if shouldBlock)
        ZBSVerifier.Verification verification  // the raw verification result, or null
    ) {
        public static final ZBSCheckResult DISABLED = new ZBSCheckResult("", null, "", false, null, null);
        public static final ZBSCheckResult UNSIGNED_ALLOWED = new ZBSCheckResult("unsigned", null, "", false, null, null);
        
        public static ZBSCheckResult missingNotAllowed() {
            return new ZBSCheckResult("no", null, "Missing .zbs file (allow_unsigned_mods=false)", 
                true, "missing .zbs file; allow_unsigned_mods=false", null);
        }
    }

    /**
     * Perform ZBS signature verification for a JAR file.
     */
    public static ZBSCheckResult checkZBS(
            File jarFile,
            String jarHash,
            JavaModInfo.WorkshopItemID workshopItemId,
            boolean steamModeEnabled,
            boolean allowUnsignedMods,
            Map<JavaModInfo.WorkshopItemID, SteamWorkshopClient.ItemDetails> workshopDetailsById
    ) {
        File zbsFile = new File(jarFile.getAbsolutePath() + ".zbs");
        
        if (!zbsFile.isFile()) {
            return allowUnsignedMods ? ZBSCheckResult.UNSIGNED_ALLOWED : ZBSCheckResult.missingNotAllowed();
        }

        SteamID64 uploaderID = steamModeEnabled
            ? SteamWorkshopClient.getUploaderForVerification(workshopItemId, workshopDetailsById)
            : null;
        ZBSVerifier.Verification zbs = ZBSVerifier.verify(jarFile, zbsFile, jarHash, uploaderID);
        
        boolean valid = zbs instanceof ZBSVerifier.ValidSignature;
        String notice = zbsNoticeForUi(zbs);
        
        if (valid) {
            return new ZBSCheckResult("yes", zbs.sid, notice, false, null, zbs);
        } else {
            return new ZBSCheckResult("no", zbs.sid, notice, true, "invalid ZBS: " + zbs.detailedMessage, zbs);
        }
    }

    private LoaderUtils() {}

    /**
     * Compute SHA-256 hash of a byte array.
     * @return raw hash bytes or null on error
     */
    public static byte[] sha256(byte[] data) {
        if (data == null) return null;
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            Logger.error("Could not compute SHA-256: " + e);
            return null;
        }
    }

    /**
     * Compute SHA-256 hash of a byte array as lowercase hex string.
     * @return hex string or null on error
     */
    public static String sha256Hex(byte[] data) {
        byte[] hash = sha256(data);
        return hash != null ? bytesToHexLower(hash) : null;
    }

    /**
     * Compute SHA-256 hash of a file as lowercase hex string.
     * @return hex string or null if file doesn't exist or error occurs
     */
    public static String sha256Hex(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
            return bytesToHexLower(md.digest());
        } catch (Exception e) {
            Logger.error("Could not hash file " + file + ": " + e);
            return null;
        }
    }

    /**
     * Convert byte array to lowercase hex string (no separators).
     */
    public static String bytesToHexLower(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Path to the ZombieBuddy JAR on disk (for spawning subprocess).
     * @return absolute path or null if not running from a JAR
     */
    public static String getZombieBuddyJarPath() {
        try {
            CodeSource cs = LoaderUtils.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            Path p = Path.of(cs.getLocation().toURI());
            if (!Files.isRegularFile(p)) return null;
            return p.toAbsolutePath().toString();
        } catch (Exception e) {
            Logger.warn("Could not resolve ZombieBuddy JAR path: " + e);
            return null;
        }
    }

    /**
     * Format ZBS verification result for UI display.
     */
    public static String zbsNoticeForUi(ZBSVerifier.Verification zbs) {
        if (zbs == null) return "";
        String shortMsg = zbs.shortMessage != null ? zbs.shortMessage.trim() : "";
        String detail = zbs.detailedMessage != null ? zbs.detailedMessage.trim() : "";
        if (shortMsg.isEmpty()) return detail;
        if (detail.isEmpty() || shortMsg.equals(detail)) return shortMsg;
        return shortMsg + "\n" + detail;
    }

    /**
     * Validate that a JAR file contains the specified package.
     * @return true if the package exists in the JAR
     */
    public static boolean validatePackageInJar(File jarFile, String packageName) {
        if (jarFile == null || packageName == null || packageName.isEmpty()) {
            return false;
        }
        try {
            String packagePath = packageName.replace('.', '/');
            try (JarFile jf = new JarFile(jarFile)) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entryName.startsWith(packagePath + "/") || 
                        entryName.equals(packagePath) || 
                        entryName.equals(packagePath + "/")) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Logger.error("Error validating package in JAR " + jarFile + ": " + e);
            return false;
        }
    }
}
