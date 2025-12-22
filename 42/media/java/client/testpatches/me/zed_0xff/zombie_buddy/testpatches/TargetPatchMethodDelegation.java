package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

// Test patch using MethodDelegation (isAdvice=false) with @SuperMethod
@Patch(className = "testjar.MethodDelegationTarget", methodName = "multiply", isAdvice = false)
public class TargetPatchMethodDelegation {
    @Patch.RuntimeType
    public static int multiply(@Patch.Argument(0) int x,
                               @Patch.Argument(1) int y,
                               @Patch.SuperMethod java.lang.reflect.Method superMethod) throws Throwable {
        // Modify the result: add 100 to the original result
        int originalResult = (Integer) superMethod.invoke(null, x, y);
        return originalResult + 100;
    }

    // Test patch using MethodDelegation for a method with no parameters
    @Patch(className = "testjar.MethodDelegationTarget", methodName = "getMessage", isAdvice = false)
    public static class TargetPatchMethodDelegationNoArgs {
        @Patch.RuntimeType
        public static String getMessage(@Patch.SuperMethod java.lang.reflect.Method superMethod) throws Throwable {
            // Return a modified message
            String original = (String) superMethod.invoke(null);
            return original + "_patched";
        }
    }
}

