package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.*;
import net.bytebuddy.asm.Advice;

public class Patch_DebugLog {
    @Patch(className = "zombie.debug.DebugLog", methodName = "init")
    public static class Patch_setOut {
        @Advice.OnMethodExit
        public static void exit() {
            System.out.println("[ZB] after DebugLog.init");
            if (Loader.g_dump_env) {
                Agent.dumpEnv();
            }
        }
    }
}
