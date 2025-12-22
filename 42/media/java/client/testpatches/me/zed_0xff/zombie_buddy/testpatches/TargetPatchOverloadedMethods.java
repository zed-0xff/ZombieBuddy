package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

public class TargetPatchOverloadedMethods {
    // Scenario 1: Patch only the first method (calculate(int))
    @Patch(className = "testjar.OverloadedMethodsA", methodName = "calculate")
    public class TargetPatchOverloadedMethodsA {
        @Patch.OnExit
        public static void exitSingle(int x) {
            testjar.OverloadedMethodsA.patchCalled = "single_only";
        }
    }

    // Scenario 2: Patch only the second method (calculate(int, int))
    @Patch(className = "testjar.OverloadedMethodsB", methodName = "calculate")
    public class TargetPatchOverloadedMethodsB {
        @Patch.OnExit
        public static void exitDouble(int x, int y) {
            testjar.OverloadedMethodsB.patchCalled = "double_only";
        }
    }

    // Scenario 3: Patch both methods with different patches
    @Patch(className = "testjar.OverloadedMethodsC", methodName = "calculate")
    public class TargetPatchOverloadedMethodsCa {
        @Patch.OnExit
        public static void exitSingle(int x) {
            testjar.OverloadedMethodsC.patchCalled = "both_separate_single";
        }
    }

    @Patch(className = "testjar.OverloadedMethodsC", methodName = "calculate")
    public class TargetPatchOverloadedMethodsCb {
        @Patch.OnExit
        public static void exitDouble(int x, int y) {
            testjar.OverloadedMethodsC.patchCalled = "both_separate_double";
        }
    }

    // Scenario 4: Patch both methods with a single unified patch
    @Patch(className = "testjar.OverloadedMethodsD", methodName = "calculate")
    public class TargetPatchOverloadedMethodsD {
        // This method should be called for both calculate(int) and calculate(int, int)
        @Patch.OnExit
        public static void exitUnified(@Patch.AllArguments Object[] arguments) {
            testjar.OverloadedMethodsD.patchCalled = "unified";
        }
    }

    // Scenario 5: Patch both methods with different advice methods in a single class
    // XXX: won't work due to ByteBuddy IllegalStateException: Duplicate advice for Delegate ...
    @Patch(className = "testjar.OverloadedMethodsE", methodName = "calculate")
    public class TargetPatchOverloadedMethodsE {
        @Patch.OnExit
        public static void exitSingle(int x) {
            testjar.OverloadedMethodsE.patchCalled = "both_separate2_singgle";
        }

        @Patch.OnExit
        public static void exitDouble(int x, int y) {
            testjar.OverloadedMethodsE.patchCalled = "both_separate2_double";
        }
    }

    // Scenario 6: Multiple method matches with @Argument(1)
    // This should match calculate(int, int) and calculate(int, int, int) since both have at least 2 parameters
    @Patch(className = "testjar.OverloadedMethodsF", methodName = "calculate")
    public class TargetPatchOverloadedMethodsF {
        @Patch.OnExit
        public static void exitWithArg1(@Patch.Argument(1) int secondArg) {
            testjar.OverloadedMethodsF.patchCalled = "arg1_match";
        }
    }

    // Scenario 7: Double method only match with @Argument(2)
    // This should only match calculate(int, int, int) since it needs at least 3 parameters
    @Patch(className = "testjar.OverloadedMethodsG", methodName = "calculate")
    public class TargetPatchOverloadedMethodsG {
        @Patch.OnExit
        public static void exitWithArg2(@Patch.Argument(2) int thirdArg) {
            testjar.OverloadedMethodsG.patchCalled = "arg2_match";
        }
    }
}