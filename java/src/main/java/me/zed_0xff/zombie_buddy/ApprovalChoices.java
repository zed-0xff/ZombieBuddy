package me.zed_0xff.zombie_buddy;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

/** Per-dialog user intent, separate from the fail-closed decision for this launch. */
public final class ApprovalChoices {
    // IPC-only bit above the existing ModFlags range. Never sticky or stored as a hash decision.
    private static final int UNTRUST_ONLY = 1 << 7;
    private static final int CANCEL_PENDING = 1 << 8;
    // Loading-JVM state only. Never populated from frontend flags or signature claims.
    private static final Set<SteamWorkshop.SteamID64> banRevokedAuthors = new HashSet<>();

    public static void beginLoad() {
        banRevokedAuthors.clear();
    }

    public static void recordBanRevocation(SteamWorkshop.SteamID64 author) {
        if (author != null) {
            banRevokedAuthors.add(author);
        }
    }

    public static boolean wasBanRevoked(SteamWorkshop.SteamID64 author) { return banRevokedAuthors.contains(author); }

    /** Save automatic ban revocations before a frontend can open or exit the process. */
    public static void saveBanRevocations(Config config) {
        if (!banRevokedAuthors.isEmpty()) Config.save(config);
        // Keep the loader's dirty flag for its original final save/retry. Config.save
        // handles write failures internally; the in-memory exclusion remains authoritative.
    }
    private final boolean[] selected;
    private final boolean[] value;
    private final Boolean[] choice;
    private final boolean[] cleared;
    private final boolean[] initialTrust;
    private final boolean[] trust;
    private final boolean[] blocked;

    public ApprovalChoices(List<JarBatchApprovalProtocol.Entry> entries) {
        int size = entries.size();
        selected = new boolean[size];
        value = new boolean[size];
        choice = new Boolean[size];
        cleared = new boolean[size];
        initialTrust = new boolean[size];
        trust = new boolean[size];
        blocked = new boolean[size];
        for (int i = 0; i < size; i++) {
            var entry = entries.get(i);
            initialTrust[i] = trust[i] = entry.flags.has(MF_TRUST_AUTHOR);
            choice[i] = Boolean.TRUE.equals(entry.decision) ? Boolean.TRUE
                    : entry.flags.has(MF_PERSIST) && Boolean.FALSE.equals(entry.decision) ? Boolean.FALSE : null;
            // IPC readers reject missing metadata; also fail closed for direct callers.
            blocked[i] = entry.zbs == null || entry.zbs.invalid() || entry.steamBan != null;
        }
    }

    public boolean toggle(int index, boolean allow) {
        if (blocked[index]) return false;
        if (Boolean.valueOf(allow).equals(choice[index])) {
            choice[index] = null;
            selected[index] = false;
            cleared[index] = true;
            return false;
        }
        choice[index] = allow;
        selected[index] = true;
        cleared[index] = false;
        return value[index] = allow;
    }

    public void trust(int index, boolean enabled) {
        trust[index] = enabled;
    }

    public boolean restoreAllow(int index, boolean initialAllow) {
        return cleared[index] ? false : selected[index] ? value[index] : initialAllow;
    }

    public boolean shouldPersist(int index, boolean save) {
        return save && !blocked[index] && selected[index];
    }

    public int denyColor(int index, boolean forced, int red) {
        return forced || Boolean.FALSE.equals(choice[index]) ? red : 0xff969696;
    }

    public String denyTooltip(int index, boolean forced) {
        return forced ? "Blocked by signature or Steam ban checks."
                : Boolean.FALSE.equals(choice[index]) ? "Deny selected. Click again to reset to undecided."
                : "Not selected: denied for this launch only. Click to explicitly deny.";
    }

    /** Null + persist is a transient explicit reset; ordinary undecided rows still return false. */
    public List<JarBatchApprovalProtocol.Entry> finish(List<JarBatchApprovalProtocol.Entry> entries, boolean save) {
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            entry.flags = entry.flags.without(UNTRUST_ONLY);
            if (save && cleared[i] && !blocked[i]) {
                entry.decision = null;
                entry.flags = entry.flags.with(MF_PERSIST);
            }
            AuthorTrustChoices.mark(entry, trust[i], trust[i] != initialTrust[i], save);
        }
        return entries;
    }

    /** Cancel carries no UI choices; the loading process resolves saved state after IPC. */
    public static List<JarBatchApprovalProtocol.Entry> cancel(List<JarBatchApprovalProtocol.Entry> entries) {
        for (var entry : entries) {
            entry.decision = false;
            entry.flags = AuthorTrustChoices.cancelFlags(entry.flags).without(MF_PERSIST | MF_TRUST_AUTHOR | MF_PRELOAD | UNTRUST_ONLY)
                    .with(CANCEL_PENDING);
        }
        return entries;
    }

    /** Restore disk approvals/trust, ignoring unsaved UI and earlier session choices. Never writes. */
    public static void applyCancelled(List<JarBatchApprovalProtocol.Entry> entries) {
        if (entries == null || entries.stream().noneMatch(e -> e != null && e.flags != null && e.flags.has(CANCEL_PENDING))) return;
        var approvals = new JarDecisionTable();
        for (var stored : ModApprovalsStore.load().mods) {
            approvals.put(stored.jarHash, stored.decision);
        }
        var config = Config.load();
        for (var entry : entries) {
            if (entry == null || entry.flags == null || !entry.flags.has(CANCEL_PENDING)) continue;
            boolean eligible = entry.zbs != null && !entry.zbs.invalid() && entry.steamBan == null
                    && !entry.flags.has(MF_BANNED) && !Utils.isBlank(entry.sha256);
            // Match normal prompt handling: verified author trust precedes the stored hash decision.
            boolean trusted = eligible && entry.zbs.valid() && config.trustsAuthor(entry.zbs.authorSteamId())
                    && !banRevokedAuthors.contains(entry.zbs.authorSteamId());
            entry.decision = eligible && (trusted || Boolean.TRUE.equals(approvals.get(entry.sha256)));
            entry.flags = entry.flags.without(CANCEL_PENDING | UNTRUST_ONLY | MF_PERSIST | MF_TRUST_AUTHOR | MF_PRELOAD);
            AuthorTrustChoices.cancelled(entry);
        }
    }

    /**
     * Contract: null decision + MF_PERSIST means remove the previous saved decision, not persist a deny.
     * Consume this transient marker before ordinary launch processing; it must never reach the store.
     * Missing/invalid signature metadata cannot clear approvals or author trust. The marker still
     * becomes a nonpersistent denial for this launch, with existing stored decisions preserved.
     * UNTRUST_ONLY + MF_PERSIST revokes only author trust, leaving the hash list/dirty flag intact.
     * Cancel strips MF_PERSIST, so even a previously built untrust marker cannot revoke trust.
     * Nonpersistent rows must not reach the original loader's persistent preload add/remove path.
     * @return true only when a stored hash record was removed; the loader must mark approvals dirty.
     */
    public static boolean applyClears(List<JarBatchApprovalProtocol.Entry> entries, List<ModApprovalsStore.ModEntry> stored,
            Consumer<SteamWorkshop.SteamID64> removeTrustedAuthor) {
        if (entries == null) return false;
        boolean changed = false;
        for (var entry : entries) {
            if (entry != null && entry.flags != null && !entry.flags.has(MF_PERSIST)) {
                entry.flags = entry.flags.without(MF_PRELOAD);
            }
            if (entry != null && entry.flags != null && entry.flags.has(UNTRUST_ONLY)) {
                if (entry.flags.has(MF_PERSIST) && entry.zbs != null && entry.zbs.valid()
                        && entry.zbs.authorSteamId() != null && entry.steamBan == null && !Utils.isBlank(entry.sha256)) {
                    removeTrustedAuthor.accept(entry.zbs.authorSteamId());
                }
                // Consume separately from explicit hash resets and ordinary saved Allow/Deny.
                if (entry.decision == null) entry.decision = false;
                entry.flags = entry.flags.without(UNTRUST_ONLY | MF_PERSIST | MF_TRUST_AUTHOR | MF_PRELOAD);
                continue;
            }
            if (entry == null || entry.decision != null || entry.flags == null || !entry.flags.has(MF_PERSIST)) continue;
            if (entry.zbs != null && !entry.zbs.invalid() && entry.steamBan == null && !Utils.isBlank(entry.sha256)) {
                changed |= stored.removeIf(old -> old != null && entry.sha256.equals(old.jarHash));
            }
            entry.decision = false;
            entry.flags = entry.flags.without(MF_PERSIST | MF_TRUST_AUTHOR);
        }
        return changed;
    }
}
