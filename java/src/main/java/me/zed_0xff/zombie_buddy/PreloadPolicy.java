package me.zed_0xff.zombie_buddy;

import java.util.Map;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

/** Steam bans apply to early preloads even when policy disables signature checks. */
public final class PreloadPolicy {
    private PreloadPolicy() {}

    public static void restartRequired(String modId) {
        Logger.warn("PRELOAD RESTART REQUIRED: '" + modId
                + "' was added/updated in preload configuration. Fully restart the game or dedicated server to execute its early entry point. The current process continues running.");
    }

    static ZBSVerifier.CheckResult banResult(SteamWorkshop.WorkshopItemID id,
            Map<SteamWorkshop.WorkshopItemID, SteamWorkshop.ItemDetails> details) {
        var item = id == null || details == null ? null : details.get(id);
        if (item == null || item.ban() == null || !Boolean.TRUE.equals(item.ban().status())) return null;
        String reason = "Steam Workshop mod is banned"
                + (Utils.isBlank(item.ban().reason()) ? "" : ": " + item.ban().reason());
        return new ZBSVerifier.CheckResult(new ModFlags(MF_BANNED), null,
                item.creatorSteamId64(), reason, reason, null);
    }
}
