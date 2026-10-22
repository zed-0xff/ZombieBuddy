package me.zed_0xff.zombie_buddy.frontend;

import static me.zed_0xff.zombie_buddy.ModFlags.*;
import me.zed_0xff.zombie_buddy.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Text approvals for headless servers, with independent author and hash decisions. */
public final class ConsoleModApprovalFrontend implements ModApprovalFrontend {
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    @Override public List<JarBatchApprovalProtocol.Entry> approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending) {
        if (pending.isEmpty()) return pending;
        Logger.info("Java mod approval (console): " + pending.size() + " mod(s). Answer y/n.");
        Set<SteamWorkshop.SteamID64> trusted = new HashSet<>();
        Set<SteamWorkshop.SteamID64> banned = new HashSet<>();
        for (var entry : pending) {
            if (entry.zbs != null && entry.zbs.authorSteamId() != null && entry.steamBan != null)
                banned.add(entry.zbs.authorSteamId());
        }
        for (var entry : pending) {
            System.out.println();
            System.out.println("---");
            System.out.println("Mod id:    " + entry.modId);
            System.out.println("Workshop:  " + (entry.workshopItemId != null ? entry.workshopItemId.value() : "(none)"));
            System.out.println("JAR:       " + entry.jarAbsolutePath);
            System.out.println("SHA-256:   " + entry.sha256);
            System.out.println("Updated:   " + (entry.date == null ? "(unknown)" : new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(entry.date)));
            System.out.println("ZBS valid: " + (entry.zbs != null && entry.zbs.valid()));
            if (entry.zbs != null && !Utils.isBlank(entry.zbs.notice())) System.out.println("ZBS note:  " + entry.zbs.notice());
            entry.flags = entry.flags.without(MF_PERSIST);
            if (entry.zbs == null || entry.zbs.invalid() || entry.steamBan != null || entry.flags.has(MF_BANNED)) {
                entry.decision = false;
                AuthorTrustChoices.mark(entry, false, false, false);
                System.out.println("Blocked by signature or Steam ban checks.");
                continue;
            }
            var author = entry.zbs.authorSteamId();
            boolean canTrust = AuthorTrustChoices.eligible(entry) && !banned.contains(author);
            if (canTrust && (trusted.contains(author) || entry.flags.has(MF_TRUST_AUTHOR))) {
                AuthorTrustChoices.mark(entry, true, false, false);
                System.out.println("Author already trusted; no individual mod approval required.");
                continue;
            }
            if (canTrust) {
                String name = Utils.isBlank(entry.zbs.notice()) ? author.toString() : entry.zbs.notice() + " (" + author + ")";
                if (readYesNo("Trust Author " + name + "?")) {
                    boolean save = readYesNo("Save to Disk?");
                    AuthorTrustChoices.mark(entry, true, true, save);
                    trusted.add(author);
                    continue;
                }
            }
            AuthorTrustChoices.mark(entry, false, false, false);
            entry.decision = readYesNo("Allow this Java mod to load?");
            if (readYesNo("Save to Disk?")) entry.flags = entry.flags.with(MF_PERSIST);
        }
        return pending;
    }

    private boolean readYesNo(String prompt) {
        while (true) {
            System.out.print(prompt + " [y/n]: ");
            System.out.flush();
            String line;
            try { line = in.readLine(); }
            catch (Exception e) { Logger.error("Console approval read failed: " + e); return false; }
            if (line == null) return false;
            String answer = line.trim().toLowerCase(Locale.ROOT);
            if (answer.isEmpty()) continue;
            if (answer.startsWith("y")) return true;
            if (answer.startsWith("n")) return false;
            System.out.println("Please answer y or n.");
        }
    }
}
