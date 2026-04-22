package me.zed_0xff.zombie_buddy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text-mode approvals on {@link System#in} / {@link System#out}.
 * Intended for headless dedicated servers where Swing/TinyFD are unavailable or undesirable.
 */
public final class ConsoleJavaModApprovalFrontend implements JavaModApprovalFrontend {

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    @Override
    public void approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending, JarDecisionTable disk) {
        if (pending.isEmpty()) {
            return;
        }
        Logger.info("Java mod approval (console): " + pending.size() + " mod(s). Answer y/n.");
        List<JarBatchApprovalProtocol.OutLine> out = new ArrayList<>(pending.size());
        for (JarBatchApprovalProtocol.Entry e : pending) {
            System.out.println();
            System.out.println("---");
            System.out.println("Mod key:   " + e.modKey);
            System.out.println("Mod id:    " + e.modId);
            System.out.println("Workshop:  " + (e.workshopItemId != null ? e.workshopItemId.value() : "(none)"));
            System.out.println("JAR:       " + e.jarAbsolutePath);
            System.out.println("SHA-256:   " + e.sha256);
            System.out.println("Updated:   " + e.modifiedHuman);
            System.out.println("ZBS valid: " + (e.zbsValid != null && !e.zbsValid.isEmpty() ? e.zbsValid : "(unknown)"));
            if (e.zbsNotice != null && !e.zbsNotice.isEmpty()) {
                System.out.println("ZBS note:  " + e.zbsNotice);
            }
            boolean persist;
            String tok;
            if ("no".equals(e.zbsValid)) {
                System.out.println("ZBS invalid — load will be denied. Choose session vs persist for this denial.");
                persist = readYesNo("Remember this denial across launches? (y = persist, n = session only)");
                tok = persist
                    ? JarBatchApprovalProtocol.TOK_DENY_PERSIST
                    : JarBatchApprovalProtocol.TOK_DENY_SESSION;
            } else {
                boolean allow = readYesNo("Allow this Java mod to load?");
                persist = readYesNo("Remember this decision across launches?");
                if (allow && persist) {
                    tok = JarBatchApprovalProtocol.TOK_ALLOW_PERSIST;
                } else if (allow) {
                    tok = JarBatchApprovalProtocol.TOK_ALLOW_SESSION;
                } else if (persist) {
                    tok = JarBatchApprovalProtocol.TOK_DENY_PERSIST;
                } else {
                    tok = JarBatchApprovalProtocol.TOK_DENY_SESSION;
                }
            }
            out.add(new JarBatchApprovalProtocol.OutLine(e.modKey, e.workshopItemId, e.sha256, tok, ""));
        }
        Loader.applyBatchApprovalLines(out, disk);
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
            System.out.println("Please answer y or n.");
        }
    }
}
