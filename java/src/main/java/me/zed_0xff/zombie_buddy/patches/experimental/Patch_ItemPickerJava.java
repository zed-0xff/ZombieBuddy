package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.annotations.Patch;

public class Patch_ItemPickerJava {
    @Patch(className = "zombie.inventory.ItemPickerJava", methodName = "Parse")
    public static class Patch_Parse {
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
