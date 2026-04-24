package me.zed_0xff.zombie_buddy;

import zombie.core.Core;
import zombie.core.GameVersion;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * LWJGL {@code TinyFileDialogs} (works when the game JVM is {@code java.awt.headless=true}).
 * No multi-mod window: {@link #approvePendingMods} prompts one entry at a time.
 */
public final class TinyfdJavaModApprovalFrontend implements JavaModApprovalFrontend {

    private static final String DIALOG_TITLE = "ZombieBuddy Java mod approval";

    @Override
    public void approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending, JarDecisionTable disk) {
        if (pending.isEmpty()) {
            return;
        }
        for (JarBatchApprovalProtocol.Entry e : pending) {
            JarApprovalOutcome o = promptForEntry(e);
            Loader.applyBatchApprovalLines(
                List.of(new JarBatchApprovalProtocol.OutLine(e.modKey, e.workshopItemId, e.sha256, o.toBatchToken(), "")),
                disk
            );
        }
    }

    private static JarApprovalOutcome promptForEntry(JarBatchApprovalProtocol.Entry e) {
        File jarFile = e.jarAbsolutePath != null && !e.jarAbsolutePath.isEmpty()
            ? new File(e.jarAbsolutePath)
            : null;
        String modKey = e.modKey;
        String sha256 = e.sha256;

        Loader.doLoadingWaitJavaModApproval();
        try {
            if ("no".equals(e.zbsValid)) {
                String note = e.zbsNotice != null && !e.zbsNotice.isEmpty()
                    ? e.zbsNotice
                    : "Invalid ZBS — load will be denied.";
                Boolean remember = tinyfdYesNo(
                    "ZBS invalid — this Java mod cannot be loaded.\n\n"
                        + note
                        + "\n\nRemember this denial across game sessions?\n\n"
                        + "Yes — save to ~/.zombie_buddy/ as denied.\n"
                        + "No — deny only for this session."
                );
                if (remember == null) {
                    return JarApprovalOutcome.DENY_SESSION;
                }
                return Boolean.TRUE.equals(remember)
                    ? JarApprovalOutcome.DENY_PERSIST
                    : JarApprovalOutcome.DENY_SESSION;
            }

            String modified = (jarFile != null && jarFile.exists())
                ? java.time.Instant.ofEpochMilli(jarFile.lastModified())
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                : "<unknown>";
            String zbsLine = "";
            if (e.zbsValid != null && !e.zbsValid.isEmpty()) {
                String sid = e.zbsSteamId != null ? e.zbsSteamId.toString() : "";
                zbsLine = "ZBS: " + e.zbsValid
                    + (!sid.isEmpty() ? " (Steam: " + sid + ")" : "")
                    + "\n\n";
            }
            Boolean allow = tinyfdYesNo(
                "Allow Java mod to load?\n\n"
                    + zbsLine
                    + "Mod: " + modKey + "\n\n"
                    + "JAR: " + jarFile + "\n\n"
                    + "Modified: " + modified + "\n\n"
                    + "SHA-256: " + sha256 + "\n\n"
                    + "Only allow if you trust this mod source."
            );
            if (allow == null) {
                return JarApprovalOutcome.DENY_SESSION;
            }

            String verb = allow ? "APPROVAL" : "DENIAL";
            String yesHint = allow
                ? "Yes — save to ~/.zombie_buddy/" + JavaModApprovalsStore.JSON_FILE_NAME + " and do not ask again."
                : "Yes — save as denied; this JAR will be blocked without asking.";
            String noHint = allow
                ? "No  — allow only for this game session."
                : "No  — deny only for this game session (ask again next launch).";

            Boolean remember = tinyfdYesNo(
                "Remember this " + verb + " across game sessions?\n\n"
                    + "Mod: " + modKey + "\n\n"
                    + yesHint + "\n\n"
                    + noHint
            );
            boolean persist = Boolean.TRUE.equals(remember);
            if (allow) {
                return persist ? JarApprovalOutcome.ALLOW_PERSIST : JarApprovalOutcome.ALLOW_SESSION;
            }
            return persist ? JarApprovalOutcome.DENY_PERSIST : JarApprovalOutcome.DENY_SESSION;
        } finally {
            Loader.doLoadingModsDefault();
        }
    }

    /**
     * Returns TRUE (Yes), FALSE (No), or null if the dialog could not be shown.
     */
    private static Boolean tinyfdYesNo(String msg) {
        Class<?> dialogClass = Accessor.findClass("org.lwjgl.util.tinyfd.TinyFileDialogs");
        if (dialogClass == null) {
            if (Core.getInstance().getGameVersion().isGreaterThan(GameVersion.parse("42.14"))) {
                Logger.error("tinyfdYesNo(): game version > 42.14 but TinyFileDialogs missing; returning null");
                return null;
            }
            Logger.info("tinyfdYesNo(): pre-42.15 and TinyFileDialogs missing; defaulting YES");
            return Boolean.TRUE;
        }
        try {
            Object result = Accessor.callByName(
                dialogClass,
                "tinyfd_messageBox",
                DIALOG_TITLE,
                msg,
                "yesno",
                "warning",
                false
            );
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            Logger.error("Could not show dialog: " + t);
            return null;
        }
    }
}
