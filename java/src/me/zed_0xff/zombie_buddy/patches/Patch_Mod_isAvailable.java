package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Loader;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.gameStates.ChooseGameInfo;


@Patch(className = "zombie.gameStates.ChooseGameInfo$Mod", methodName = "isAvailable", warmUp = true)
public class Patch_Mod_isAvailable {
    public static final ThreadLocal<Boolean> bypass = ThreadLocal.withInitial(() -> false);

    @Patch.OnExit
    public static void exit(@Patch.This ChooseGameInfo.Mod self, @Patch.Return(readOnly = false) boolean available) {
        if (bypass.get()) {
            bypass.set(false);
            return;
        }
        if (available && !Loader.isModAllowedToLoad(self)) {
            available = false;
        }
    }
}
