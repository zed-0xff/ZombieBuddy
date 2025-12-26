package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Test patch using @Patch.This as Object instead of the actual class type.
 * This tests that patches can avoid LinkageError by not referencing game classes directly.
 * 
 * Note: This pattern is used in RealisticCarPhysicsZB mod where game classes like
 * CarController and WorldSimulation are passed as Object to avoid LinkageError.
 */
public class TargetPatchThisAsObject {
    
    /**
     * Test advice patch with @Patch.This as Object for an instance method.
     * This demonstrates the pattern used in RealisticCarPhysicsZB mod.
     */
    @Patch(className = "testjar.AdviceLocalTarget", methodName = "multiply")
    public static class AdvicePatchWithObjectThis {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            // Cast to proper type in the body to avoid LinkageError
            // The important part is using Object instead of the actual class type in the signature
            testjar.AdviceLocalTarget target = (testjar.AdviceLocalTarget) self;
            // Set an instance flag to indicate the patch was called
            target.instancePatchCalled = true;
        }
    }
}

