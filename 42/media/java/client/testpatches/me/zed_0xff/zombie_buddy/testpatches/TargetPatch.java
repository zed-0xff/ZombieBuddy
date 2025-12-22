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

@Patch(className = "testjar.TargetClass", methodName = "getStringToNull")
class TargetPatchStringToNull {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        returnValue = null;
    }
}

// Test case for Patch_MouseAddMoveEvent - scales mouse coordinates before calling original
@Patch(className = "testjar.MouseCoordinates", methodName = "addMoveEvent", isAdvice = false)
class TargetPatchMouseAddMoveEvent {
    @Patch.RuntimeType
    public static void addMoveEvent(@Patch.Argument(0) double mouseX, 
                                    @Patch.Argument(1) double mouseY,
                                    @Patch.SuperMethod java.lang.reflect.Method superMethod) throws Throwable {
        // Simulate retina scaling: scale coordinates by 2.0 (like Patch_MouseAddMoveEvent does)
        // In the real patch, this would check isPatchNeeded() and get window content scale
        double scaleFactor = 2.0;
        mouseX *= scaleFactor;
        mouseY *= scaleFactor;
        // Call original method with scaled coordinates
        superMethod.invoke(null, mouseX, mouseY);
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

