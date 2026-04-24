package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;

import java.io.File;
import java.util.Map;

/**
 * ZBS signature verification check utilities.
 */
public final class ZBSCheck {

    private ZBSCheck() {}

    /** Result of ZBS signature verification check. */
    public record Result(
        String valid,                    // "yes", "no", "unsigned", or ""
        SteamID64 sid,                   // signer ID or null
        String notice,                   // UI notice text
        boolean shouldBlock,             // true if JAR should be blocked
        String blockReason,              // reason for blocking (if shouldBlock)
        ZBSVerifier.Verification verification  // the raw verification result, or null
    ) {
        public static final Result DISABLED = new Result("", null, "", false, null, null);
        public static final Result UNSIGNED_ALLOWED = new Result("unsigned", null, "", false, null, null);
        
        public static Result missingNotAllowed() {
            return new Result("no", null, "Missing .zbs file (allow_unsigned_mods=false)", 
                true, "missing .zbs file; allow_unsigned_mods=false", null);
        }
    }

    /**
     * Perform ZBS signature verification for a JAR file.
     */
    public static Result check(
            File jarFile,
            String jarHash,
            WorkshopItemID workshopItemId,
            boolean steamModeEnabled,
            boolean allowUnsignedMods,
            Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById
    ) {
        File zbsFile = new File(jarFile.getAbsolutePath() + ".zbs");
        
        if (!zbsFile.isFile()) {
            return allowUnsignedMods ? Result.UNSIGNED_ALLOWED : Result.missingNotAllowed();
        }

        SteamID64 uploaderID = steamModeEnabled
            ? SteamWorkshop.getUploaderForVerification(workshopItemId, workshopDetailsById)
            : null;
        ZBSVerifier.Verification zbs = ZBSVerifier.verify(jarFile, zbsFile, jarHash, uploaderID);
        
        boolean valid = zbs instanceof ZBSVerifier.ValidSignature;
        String notice = noticeForUi(zbs);
        
        if (valid) {
            return new Result("yes", zbs.sid, notice, false, null, zbs);
        } else {
            return new Result("no", zbs.sid, notice, true, "invalid ZBS: " + zbs.detailedMessage, zbs);
        }
    }

    /**
     * Format ZBS verification result for UI display.
     */
    public static String noticeForUi(ZBSVerifier.Verification zbs) {
        if (zbs == null) return "";
        String shortMsg = zbs.shortMessage != null ? zbs.shortMessage.trim() : "";
        String detail = zbs.detailedMessage != null ? zbs.detailedMessage.trim() : "";
        if (shortMsg.isEmpty()) return detail;
        if (detail.isEmpty() || shortMsg.equals(detail)) return shortMsg;
        return shortMsg + "\n" + detail;
    }
}
