package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

public class Patch_LuaEventManager {

    // handles all triggerEvent() methods, NOT synchronized on Lua thread
    @Patch(className = "zombie.Lua.LuaEventManager", methodName = "triggerEvent")
    public static class Patch_triggerEvent {
        @Patch.OnEnter
        public static void enter(@Patch.AllArguments Object[] args) {
            String eventName = (String) args[0];
            if (ZBEventLog.shouldLog(eventName)) {
                if (args.length > 1) {
                    Logger.info("EVENT: " + eventName + "( " + Logger.formatArgs(args, 1) + " )");
                } else {
                    Logger.info("EVENT: " + eventName);
                }
            }
        }
    }

    // runs synchronized on Lua thread
    @Patch(className = "zombie.Lua.LuaEventManager", methodName = "checkEvent")
    public static class Patch_checkEvent {
        @Patch.OnEnter
        public static void enter(String eventName) {
            HttpServer.runLuaTasks();
        }
    }
}
