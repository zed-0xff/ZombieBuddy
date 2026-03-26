package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Loader;
import me.zed_0xff.zombie_buddy.Patch;


@Patch(className = "zombie.modding.ActiveMods", methodName = "setModActive")
public class Patch_setModActive {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) String modID, @Patch.Argument(1) boolean active) {
        if (active) {
            Loader.g_activated_mods.add(modID);
        } else {
            Loader.g_activated_mods.remove(modID);
        }
    }
}
