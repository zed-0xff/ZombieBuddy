package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;

public class Patch_Exposer {
    @Patch(className = "zombie.Lua.LuaManager$Exposer", methodName = "exposeAll", warmUp = true, IKnowWhatIAmDoing = true)
    public static class Patch_exposeAll {
        @Patch.OnEnter
        public static void enter() {
            System.out.println("[ZB] before Exposer.exposeAll");

            if ( zombie.Lua.LuaManager.exposer == null ) {
                System.out.println("[ZB] Error! LuaManager.exposer is null!");
                return;
            }

            for (Class<?> cls : Exposer.getExposedClasses()) {
                System.out.println("[ZB] Exposing class to Lua: " + cls.getName());
                zombie.Lua.LuaManager.exposer.setExposed(cls);
            }
        }
    }
}
