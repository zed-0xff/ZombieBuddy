package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

public class Patch_LuaEventManager {

    // handles all triggerEvent() methods, NOT synchronized on Lua thread
    @Patch(className = "zombie.Lua.LuaEventManager", methodName = "triggerEvent")
    public static class Patch_triggerEvent {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) String eventName) {
            if (ZBEventLog.shouldLog(eventName)) {
                Logger.info("triggerEvent: " + eventName);
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
