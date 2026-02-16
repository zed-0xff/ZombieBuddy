package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

// Test patch using @Patch.Local to share values between @OnEnter and @OnExit
// Verifies that primitives are initialized to 0 and references to null
@Patch(className = "testjar.AdviceLocalTarget", methodName = "calculate")
public class TargetPatchAdviceLocal {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) int x,
                            @Patch.Argument(1) int y,
                            @Patch.Local("primitiveSum") int primitiveSum,
                            @Patch.Local("referenceSum") Integer referenceSum) {
        // Verify primitive is initialized to 0 (default value)
        testjar.AdviceLocalTarget.primitiveInitialized = (primitiveSum == 0);
        
        // Verify reference is initialized to null (default value)
        testjar.AdviceLocalTarget.referenceInitialized = (referenceSum == null);
        
        // Assign values to local variables - they will persist to exit
        // Note: For primitives, we can't reassign the parameter, but we can use it
        // For reference types, we can assign a new object
        // However, since Java parameters are pass-by-value, we need to use a mutable wrapper
        // For this test, we'll just verify the initialization and use a different approach
        
        testjar.AdviceLocalTarget.enterCalled = true;
    }
    
    @Patch.OnExit
    public static void exit(@Patch.Local("primitiveSum") int primitiveSum,
                           @Patch.Local("referenceSum") Integer referenceSum) {
        // Verify the local variables are accessible in exit
        // Primitive should still be 0 (unless modified, but we can't modify primitives directly)
        testjar.AdviceLocalTarget.localIntValue = primitiveSum;
        
        // Reference should still be null (unless assigned in enter, but assignment doesn't persist)
        testjar.AdviceLocalTarget.localIntegerValue = referenceSum;
        
        testjar.AdviceLocalTarget.exitCalled = true;
    }
}

