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

    // Scenario 5: Patch both methods with different advice methods
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
}