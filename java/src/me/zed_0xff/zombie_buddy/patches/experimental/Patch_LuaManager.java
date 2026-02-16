package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.*;

import zombie.Lua.LuaManager;

public class Patch_LuaManager {
    public static boolean isInitialized = false;

    @Patch(className = "zombie.Lua.LuaManager", methodName = "init")
    public class Patch_init {
        @Patch.OnEnter
        public static void enter() {
            Logger.info("before LuaManager.init");
        }

        @Patch.OnExit
        public static void exit() {
            Logger.info("after LuaManager.init");
            if (!isInitialized) {
                isInitialized = true;
                if (Agent.arguments.containsKey("lua_init_script")) {
                    String initScript = Agent.arguments.get("lua_init_script");
                    Logger.info("Running init script: " + initScript);
                    LuaManager.RunLua(initScript);
                }
            }
        }
    }
}
