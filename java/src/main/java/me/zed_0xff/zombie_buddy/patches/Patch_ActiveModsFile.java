package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Loader;
import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.modding.ActiveMods;

/** The ModSelector saves default mods here, including saves without a Lua reset. */
@Patch(className = "zombie.modding.ActiveModsFile", methodName = "write")
public class Patch_ActiveModsFile {
    @Patch.OnExit
    public static void exit(@Patch.Argument(1) ActiveMods mods, @Patch.Return boolean written) {
        if (written && mods == ActiveMods.getById("default")) {
            Loader.onDefaultModsSaved(mods.getMods());
        }
    }
}
