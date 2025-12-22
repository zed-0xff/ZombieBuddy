package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

// has to be public class to work
@Patch(className = "testjar.MouseCoordinates", methodName = "addMoveEvent", isAdvice = false)
public class TargetPatchMouseAddMoveEvent {
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

