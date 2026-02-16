package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

// Test patch using MethodDelegation (isAdvice=false) with @SuperCall
@Patch(className = "testjar.MethodDelegationSuperCallTarget", methodName = "multiply", isAdvice = false)
public class TargetPatchMethodDelegationSuperCall {
    @Patch.RuntimeType
    public static int multiply(@Patch.Argument(0) int x,
                               @Patch.Argument(1) int y,
                               @Patch.SuperCall java.util.concurrent.Callable<Integer> callable) throws Exception {
        // Modify the result: multiply by 2 using @SuperCall
        int originalResult = callable.call();
        return originalResult * 2;
    }
}

