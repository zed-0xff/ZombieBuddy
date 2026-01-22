package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

public class PatchSkipOn {
    @Patch(className = "testjar.SkipOnTarget", methodName = "testMethod")
    public static class SkipOnPatch {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            return true; // Should skip original method
        }
    }
}
