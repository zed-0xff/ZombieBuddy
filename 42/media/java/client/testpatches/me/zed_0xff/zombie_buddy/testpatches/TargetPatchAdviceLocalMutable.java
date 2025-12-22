package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

// Test patch using @Patch.Local to verify values can be assigned and persist
// This demonstrates that @Local variables are initialized to default values (0 for primitives, null for references)
// and that assignments persist between enter and exit
@Patch(className = "testjar.AdviceLocalMutableTest", methodName = "calculate")
public class TargetPatchAdviceLocalMutable {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) int x,
                            @Patch.Argument(1) int y,
                            @Patch.Local("sum") int sum) {
        // @Local primitive is initialized to 0 (default value)
        // We can assign to it and the value will persist to exit
        sum = x + y;
        testjar.AdviceLocalMutableTest.enterCalled = true;
    }
    
    @Patch.OnExit
    public static void exit(@Patch.Local("sum") int sum) {
        // The assigned value from enter persists here
        testjar.AdviceLocalMutableTest.sharedValue = sum;
        testjar.AdviceLocalMutableTest.exitCalled = true;
    }
}

