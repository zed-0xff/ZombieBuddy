package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "testjar.TargetClass", methodName = "get_42")
public class TargetPatch {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) int returnValue) {
        returnValue = 999;
    }
}

