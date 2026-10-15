package me.zed_0xff.zombie_buddy;

import java.util.List;
import java.util.function.Consumer;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

/** Per-dialog user intent, separate from the fail-closed decision for this launch. */
public final class ApprovalChoices {
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
        return save && !blocked[index] && (selected[index] || trust[index] != initialTrust[index]);
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
            if (save && cleared[i] && !blocked[i] && !trust[i]) {
                var entry = entries.get(i);
                entry.decision = null;
                entry.flags = entry.flags.with(MF_PERSIST).without(MF_TRUST_AUTHOR);
            }
        }
        return entries;
    }

    /**
     * Contract: null decision + MF_PERSIST means remove the previous saved decision, not persist a deny.
     * Consume this transient marker before ordinary launch processing; it must never reach the store.
     * Missing/invalid signature metadata cannot clear approvals or author trust. The marker still
     * becomes a nonpersistent denial for this launch, with existing stored decisions preserved.
     * @return true only when a stored hash record was removed; the loader must mark approvals dirty.
     */
    public static boolean applyClears(List<JarBatchApprovalProtocol.Entry> entries, List<ModApprovalsStore.ModEntry> stored,
            Consumer<SteamWorkshop.SteamID64> removeTrustedAuthor) {
        if (entries == null) return false;
        boolean changed = false;
        for (var entry : entries) {
            if (entry == null || entry.decision != null || entry.flags == null || !entry.flags.has(MF_PERSIST)) continue;
            if (entry.zbs != null && !entry.zbs.invalid() && entry.steamBan == null && !Utils.isBlank(entry.sha256)) {
                changed |= stored.removeIf(old -> old != null && entry.sha256.equals(old.jarHash));
                if (entry.zbs.valid() && entry.zbs.authorSteamId() != null) {
                    removeTrustedAuthor.accept(entry.zbs.authorSteamId());
                }
            }
            entry.decision = false;
            entry.flags = entry.flags.without(MF_PERSIST | MF_TRUST_AUTHOR);
        }
        return changed;
    }
}
