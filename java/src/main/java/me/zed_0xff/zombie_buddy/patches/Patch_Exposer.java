package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.*;

public class Patch_Exposer {
    @Patch(className = "zombie.Lua.LuaManager$Exposer", methodName = "exposeAll", warmUp = true, IKnowWhatIAmDoing = true)
    public static class Patch_exposeAll {
        @Patch.OnExit
        public static void exit() {
            Logger.debug("after Exposer.exposeAll");
            Exposer.afterExposeAll();
            EventsAPI.init();

            if (Agent.isExperimental()) {
                WatchesAPI.init();
            }
        }
    }
}
