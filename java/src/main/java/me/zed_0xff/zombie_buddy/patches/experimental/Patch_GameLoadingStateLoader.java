package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/** Drain HTTP Lua tasks on GameLoadingThread while Lua ownership points there. */
public class Patch_GameLoadingStateLoader {
    @Patch(className = "zombie.gameStates.GameLoadingState$1", methodName = "run")
    public static class Patch_run {
        @Patch.OnEnter
        public static void enter() {
            HttpServer.maybeRunLuaTasks();
        }

        @Patch.OnExit
        public static void exit() {
            HttpServer.maybeRunLuaTasks();
        }
    }

    @Patch(className = "zombie.gameStates.GameLoadingState$1", methodName = "runInner")
    public static class Patch_runInner {
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
