package me.zed_0xff.zombie_buddy.frontend;

import static me.zed_0xff.zombie_buddy.ModFlags.MF_PERSIST;

import me.zed_0xff.zombie_buddy.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Text-mode approvals on {@link System#in} / {@link System#out}.
 * Intended for headless dedicated servers where Swing/TinyFD are unavailable or undesirable.
 */
public final class ConsoleModApprovalFrontend implements ModApprovalFrontend {
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    @Override
    public List<JarBatchApprovalProtocol.Entry> approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending) {
        if (pending.isEmpty()) {
            return pending;
        }
        Logger.info("Java mod approval (console): " + pending.size() + " mod(s). Answer y/n.");
        for (JarBatchApprovalProtocol.Entry e : pending) {
            System.out.println();
            System.out.println("---");
            System.out.println(local.zbselective.i18n.UiText.text("Mod id:    ", "模组 ID:    ") + e.modId);
            System.out.println(local.zbselective.i18n.UiText.text("Workshop:  ", "创意工坊:  ") + (e.workshopItemId != null ? e.workshopItemId.value() : local.zbselective.i18n.UiText.text("(none)", "（无）")));
            System.out.println("JAR:       " + e.jarAbsolutePath);
            System.out.println("SHA-256:   " + e.sha256);
            System.out.println(local.zbselective.i18n.UiText.text("Updated:   ", "更新时间:   ") + formatDate(e.date));
            System.out.println(local.zbselective.i18n.UiText.text("ZBS valid: ", "ZBS 有效: ") + e.zbs.valid());
            if (!Utils.isBlank(e.zbs.notice())) {
                System.out.println(local.zbselective.i18n.UiText.text("ZBS note:  ", "ZBS 说明:  ") + e.zbs.notice());
            }
            boolean allow;
            if (e.zbs.invalid()) {
                System.out.println(local.zbselective.i18n.UiText.text("ZBS invalid — load will be denied.", "ZBS 无效——将拒绝加载。"));
                allow = false;
            } else {
                allow = readYesNo(local.zbselective.i18n.UiText.text("Allow this Java mod to load?", "允许此 Java 模组加载吗？"));
            }
            e.decision = allow;
            if (readYesNo(local.zbselective.i18n.UiText.text("Save this decision to disk?", "将此决定保存到磁盘吗？"))) {
                e.flags = e.flags.with(MF_PERSIST);
            } else {
                e.flags = e.flags.without(MF_PERSIST);
            }
        }
        return pending;
    }

    private boolean readYesNo(String prompt) {
        while (true) {
            System.out.print(prompt + " [y/n]: ");
            System.out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (Exception e) {
                Logger.error("Console approval read failed: " + e);
                return false;
            }
            if (line == null) {
                return false;
            }
            String s = line.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("y")) {
                return true;
            }
            if (s.startsWith("n")) {
                return false;
            }
            System.out.println(local.zbselective.i18n.UiText.text("Please answer y or n.", "请输入 y 或 n。"));
        }
    }

    private static String formatDate(Date date) {
        if (date == null) {
            return local.zbselective.i18n.UiText.text("(unknown)", "（未知）");
        }
        return new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(date);
    }
}
