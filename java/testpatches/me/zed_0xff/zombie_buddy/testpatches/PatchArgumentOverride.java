package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

public class PatchArgumentOverride {
    @Patch(className = "testjar.ArgumentOverrideTarget", methodName = "multiply")
    public static class MultiplyPatch {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(value = 1, readOnly = false) int b) {
            b = 10;
        }
    }
}
