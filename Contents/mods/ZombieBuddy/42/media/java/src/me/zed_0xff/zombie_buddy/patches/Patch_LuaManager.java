package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

public class Patch_LuaManager {
    @Patch(className = "zombie.Lua.LuaManager", methodName = "init")
    public class Patch_init {
        @Advice.OnMethodEnter
        public static void enter() {
            System.out.println("[ZB] before LuaManager.init");
        }

        @Advice.OnMethodExit
        public static void exit() {
            System.out.println("[ZB] after LuaManager.init");
        }
    }
}
