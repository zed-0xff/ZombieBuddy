package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

public class Patch_Exposer {
    @Patch(className = "zombie.Lua.LuaManager$Exposer", methodName = "exposeAll", warmUp = true)
    public static class Patch_exposeAll {
        @Advice.OnMethodEnter
        public static void enter() {
            System.out.println("[ZB] before Exposer.exposeAll");

            if ( zombie.Lua.LuaManager.exposer == null ) {
                System.out.println("[ZB] Error! LuaManager.exposer is null!");
                return;
            }

            zombie.Lua.LuaManager.exposer.setExposed(null);
        }
    }
}
