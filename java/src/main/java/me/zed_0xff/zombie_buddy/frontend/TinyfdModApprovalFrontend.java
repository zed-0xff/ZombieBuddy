package me.zed_0xff.zombie_buddy.frontend;

import me.zed_0xff.zombie_buddy.*;

import zombie.core.Core;
import zombie.core.GameVersion;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * LWJGL {@code TinyFileDialogs} (works when the game JVM is {@code java.awt.headless=true}).
 * No multi-mod window: {@link #approvePendingMods} prompts one entry at a time.
 */
public final class TinyfdModApprovalFrontend implements ModApprovalFrontend {

    private static String DIALOG_TITLE() { return local.zbselective.i18n.UiText.text("ZombieBuddy Java mod approval", "ZombieBuddy Java 模组审批"); }
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    @Override
    public List<JarBatchApprovalProtocol.Entry> approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending) {
        if (pending.isEmpty()) {
            return pending;
        }
        for (JarBatchApprovalProtocol.Entry e : pending) {
            Boolean allow = promptForEntry(e);
            if (allow != null) {
                e.decision = allow;
            }
        }
        return pending;
    }

    private static Boolean promptForEntry(JarBatchApprovalProtocol.Entry e) {
        if (e.zbs.invalid()) {
            String note = !Utils.isBlank(e.zbs.notice())
                ? e.zbs.notice()
                : local.zbselective.i18n.UiText.text("Invalid ZBS — load will be denied.", "ZBS 无效——将拒绝加载。");
            tinyfdYesNo(
                local.zbselective.i18n.UiText.text("ZBS invalid — this Java mod cannot be loaded.\n\n", "ZBS 无效——此 Java 模组无法加载。\n\n")
                    + note
                    + local.zbselective.i18n.UiText.text("\n\nIt will be denied.", "\n\n该模组将被拒绝加载。")
            );
            return false;
        }

        String modified = formatDate(e.date);
        String zbsLine = "";
        if (e.zbs.valid() || e.zbs.invalid() || e.zbs.unsigned()) {
            String sid = e.zbs.authorSteamId() != null ? e.zbs.authorSteamId().toString() : "";
            zbsLine = "ZBS: " + zbsStatus(e)
                + (!sid.isEmpty() ? " (Steam: " + sid + ")" : "")
                + "\n\n";
        }
        Boolean allow = tinyfdYesNo(
            "Allow Java mod to load?\n\n"
                + zbsLine
                + local.zbselective.i18n.UiText.text("Mod: ", "模组: ") + e.modId + "\n\n"
                + "JAR: " + e.jarAbsolutePath + "\n\n"
                + local.zbselective.i18n.UiText.text("Modified: ", "修改时间: ") + modified + "\n\n"
                + "SHA-256: " + e.sha256 + "\n\n"
                + local.zbselective.i18n.UiText.text("Only allow if you trust this mod source.", "仅在您信任此模组来源时允许加载。")
        );
        if (allow == null) {
            return false;
        }
        return allow;
    }

    private static String zbsStatus(JarBatchApprovalProtocol.Entry e) {
        if (e.zbs.valid()) {
            return local.zbselective.i18n.UiText.text("valid", "有效");
        }
        if (e.zbs.invalid()) {
            return local.zbselective.i18n.UiText.text("invalid", "无效");
        }
        return local.zbselective.i18n.UiText.text("unsigned", "未签名");
    }

    private static String formatDate(Date date) {
        if (date == null) {
            return local.zbselective.i18n.UiText.text("<unknown>", "未知");
        }
        return new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(date);
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
                DIALOG_TITLE(),
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
