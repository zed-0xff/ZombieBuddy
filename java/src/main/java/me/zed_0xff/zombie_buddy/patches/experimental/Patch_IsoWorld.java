package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.annotations.Patch;

public class Patch_IsoWorld {
    @Patch(className = "zombie.iso.IsoWorld", methodName = "init")
    public static class Patch_init {
        @Patch.OnEnter
        public static void enter() {
            HttpServer.maybeRunLuaTasks();
        }

        @Patch.OnExit
        public static void exit() {
            HttpServer.maybeRunLuaTasks();
        }
    }
}
