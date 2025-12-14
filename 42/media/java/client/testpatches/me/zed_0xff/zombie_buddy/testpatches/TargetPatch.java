package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;
import testjar.CustomObject;

@Patch(className = "testjar.TargetClass", methodName = "get_42")
public class TargetPatch {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) int returnValue) {
        returnValue = 999;
    }
}

@Patch(className = "testjar.TargetClass", methodName = "getString")
class TargetPatchString {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        returnValue = returnValue + " world";
    }
}

@Patch(className = "testjar.TargetClass", methodName = "getCustomObject")
class TargetPatchCustomObject {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) CustomObject returnValue) {
        returnValue = new CustomObject(200, "patched");
    }
}

// should trigger warnings:
//  - not existing method
//  - non-void return type
@Patch(className = "testjar.Class", methodName = "notExistingMethod")
class BadTargetPatch {
    @Patch.OnExit
    public static int exit() {
        return 0;
    }
}

