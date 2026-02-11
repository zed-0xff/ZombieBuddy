package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

public class Patch_LuaManager {
    @Patch(className = "zombie.Lua.LuaManager", methodName = "init")
    public class Patch_init {
        @Patch.OnEnter
        public static void enter() {
            Logger.out.println("[ZB] before LuaManager.init");
        }

        @Patch.OnExit
        public static void exit() {
            Logger.out.println("[ZB] after LuaManager.init");
        }
    }
}
