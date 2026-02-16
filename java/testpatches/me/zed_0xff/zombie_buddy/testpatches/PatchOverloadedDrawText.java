package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

public class PatchOverloadedDrawText {
    @Patch(className = "testjar.OverloadedDrawTextTarget", methodName = "DrawText")
    public static class DrawTextPatch {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(value = 1, readOnly = false) String text) {
            if (text != null) {
                text = "patched:" + text;
            }
        }
    }
}
