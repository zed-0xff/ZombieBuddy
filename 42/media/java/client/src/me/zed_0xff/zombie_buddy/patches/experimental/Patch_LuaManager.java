package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Patch;

public class Patch_LuaManager {
    @Patch(className = "zombie.Lua.LuaManager", methodName = "init")
    public class Patch_init {
        @Patch.OnEnter
        public static void enter() {
            System.out.println("[ZB] before LuaManager.init");
        }

        @Patch.OnExit
        public static void exit() {
            System.out.println("[ZB] after LuaManager.init");
        }
    }
}
