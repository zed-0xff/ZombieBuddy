package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "zombie.Lua.LuaEventManager", methodName = "triggerEvent")
public class Patch_LuaEventManager {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) String eventName) {
        if (ZBEventLog.shouldLog(eventName)) {
            Logger.out.println("[ZB] triggerEvent: " + eventName);
        }
    }
}
