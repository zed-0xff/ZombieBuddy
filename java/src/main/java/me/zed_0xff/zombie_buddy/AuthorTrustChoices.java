package me.zed_0xff.zombie_buddy;

import java.util.*;
import java.util.function.Consumer;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

/** Independent author choices carried through frontend IPC; never stored as hash decisions. */
public final class AuthorTrustChoices {
    private static final int SEPARATE = 1 << 9;
    private static final int CHANGED = 1 << 10;
    private static final int SAVE = 1 << 11;
    private static final int CANCELLED = 1 << 12;
    private static final int PRELOAD = 1 << 13;
    private static final int MARKERS = SEPARATE | CHANGED | SAVE | CANCELLED | PRELOAD;
    private static final Map<java.nio.file.Path, Map<SteamWorkshop.SteamID64, Boolean>> sessions = new HashMap<>();

    private AuthorTrustChoices() {}

    private static Map<SteamWorkshop.SteamID64, Boolean> session() {
        return sessions.computeIfAbsent(Agent.configDir().toAbsolutePath().normalize(), key -> new HashMap<>());
    }

    public static boolean eligible(JarBatchApprovalProtocol.Entry entry) {
        return entry != null && entry.flags != null && entry.zbs != null && entry.zbs.valid()
            && entry.zbs.authorSteamId() != null && entry.steamBan == null && !entry.flags.has(MF_BANNED)
            && !Utils.isBlank(entry.sha256);
    }

    public static void mark(JarBatchApprovalProtocol.Entry entry, boolean trusted, boolean changed, boolean save) {
        entry.flags = entry.flags.without(MARKERS | MF_TRUST_AUTHOR).with(SEPARATE);
        if (!eligible(entry)) return;
        if (trusted) entry.flags = entry.flags.with(MF_TRUST_AUTHOR);
        if (changed) entry.flags = entry.flags.with(CHANGED | (save ? SAVE : 0));
    }

    static ModFlags cancelFlags(ModFlags flags) { return flags.without(MARKERS); }
    static void cancelled(JarBatchApprovalProtocol.Entry entry) { entry.flags = entry.flags.with(CANCELLED); }
    static void revokeSession(SteamWorkshop.SteamID64 author) { session().put(author, false); }

    static boolean isTrusted(SteamWorkshop.SteamID64 author, Config config) {
        return author != null && session().getOrDefault(author, config.trustsAuthor(author));
    }

    static boolean isPersistentlyTrusted(SteamWorkshop.SteamID64 author, Config config) {
        return author != null && config.trustsAuthor(author) && !Boolean.FALSE.equals(session().get(author));
    }

    static boolean isTrustedForRevocation(SteamWorkshop.SteamID64 author, Config config) {
        return author != null && (config.trustsAuthor(author) || Boolean.TRUE.equals(session().get(author)));
    }

    public static void applyAuthors(List<JarBatchApprovalProtocol.Entry> entries,
            Consumer<SteamWorkshop.SteamID64> add, Consumer<SteamWorkshop.SteamID64> remove) {
        if (entries == null) return;
        Set<SteamWorkshop.SteamID64> banned = new HashSet<>();
        Set<SteamWorkshop.SteamID64> savedTrust = new HashSet<>();
        for (var entry : entries) {
            if (entry != null && entry.zbs != null && entry.zbs.authorSteamId() != null
                    && (entry.steamBan != null || entry.flags != null && entry.flags.has(MF_BANNED))) banned.add(entry.zbs.authorSteamId());
        }
        for (var entry : entries) {
            if (!eligible(entry) || entry.flags.has(CANCELLED) || banned.contains(entry.zbs.authorSteamId())) continue;
            var author = entry.zbs.authorSteamId();
            if (!entry.flags.has(SEPARATE)) {
                // Preserve the existing contract for frontends that do not send independent intent.
                if (entry.flags.has(MF_PERSIST)) {
                    if (entry.flags.has(MF_TRUST_AUTHOR)) add.accept(author); else remove.accept(author);
                }
                continue;
            }
            if (!entry.flags.has(CHANGED)) continue;
            boolean trusted = entry.flags.has(MF_TRUST_AUTHOR);
            if (trusted && ApprovalChoices.wasBanRevoked(author)) continue;
            if (entry.flags.has(SAVE)) {
                if (trusted) add.accept(author); else remove.accept(author);
                session().remove(author);
                if (trusted) savedTrust.add(author); else savedTrust.remove(author);
            } else {
                session().put(author, trusted);
            }
        }
        for (var entry : entries) {
            if (eligible(entry) && savedTrust.contains(entry.zbs.authorSteamId()) && entry.flags.has(MF_PRELOAD))
                entry.flags = entry.flags.with(PRELOAD);
        }
    }

    /** Override launch authority only after the original loader has saved direct hash choices. */
    public static void authorize(List<JarBatchApprovalProtocol.Entry> entries, JarDecisionTable decisions,
            Config config, Consumer<JarBatchApprovalProtocol.Entry> addPreload) {
        if (entries == null) return;
        for (var entry : entries) {
            if (entry == null || entry.flags == null) continue;
            try {
                if (!eligible(entry) || entry.flags.has(CANCELLED)
                        || ApprovalChoices.wasBanRevoked(entry.zbs.authorSteamId())) continue;
                if (isTrusted(entry.zbs.authorSteamId(), config)) {
                    decisions.put(entry.sha256, true);
                    if (entry.flags.has(PRELOAD) && config.trustsAuthor(entry.zbs.authorSteamId())
                            && !Utils.isBlank(entry.modId) && entry.infAbsolutePath != null && entry.jarAbsolutePath != null)
                        addPreload.accept(entry);
                }
            } finally {
                entry.flags = entry.flags.without(MARKERS);
            }
        }
    }
}
