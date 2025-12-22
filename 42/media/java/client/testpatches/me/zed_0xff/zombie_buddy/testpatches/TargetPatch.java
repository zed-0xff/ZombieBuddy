package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;
import testjar.CustomObject;

public class TargetPatch {
    @Patch(className = "testjar.TargetClass", methodName = "get_42")
    public class TargetPatchGet42 {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int returnValue) {
            returnValue = 999;
        }
    }

    @Patch(className = "testjar.TargetClass", methodName = "getString")
    public class TargetPatchString {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) String returnValue) {
            returnValue = returnValue + " world";
        }
    }

    @Patch(className = "testjar.TargetClass", methodName = "getCustomObject")
    public class TargetPatchCustomObject {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) CustomObject returnValue) {
            returnValue = new CustomObject(200, "patched");
        }
    }

    @Patch(className = "testjar.TargetClass", methodName = "getStringToNull")
    public class TargetPatchStringToNull {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) String returnValue) {
            returnValue = null;
        }
    }

    // should trigger warnings:
    //  - not existing method
    //  - non-void return type
    @Patch(className = "testjar.Class", methodName = "notExistingMethod")
    public class BadTargetPatch {
        @Patch.OnExit
        public static int exit() {
            return 0;
        }
    }
}