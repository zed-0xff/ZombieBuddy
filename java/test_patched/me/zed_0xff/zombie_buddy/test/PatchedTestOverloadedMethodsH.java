package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsH;

public class PatchedTestOverloadedMethodsH {
    @Test
    void testOverloadedMethodsPatchWithArg1AndLocal() {
        // Scenario 8: Multiple method matches with @Argument(1) and @Local variable
        // @Argument(1) should match calculate(int, int) and calculate(int, int, int)
        // but NOT calculate(int) since it doesn't have a second parameter
        // @Local variable should be shared between enter and exit
        
        // Call single-parameter method - patch should NOT be called
        OverloadedMethodsH.patchCalled = null;
        OverloadedMethodsH.calculate(5);
        assertNull(OverloadedMethodsH.patchCalled, "Patch with @Argument(1) should NOT match calculate(int)");
        
        // Call two-parameter method - patch should be called, local variable should work
        OverloadedMethodsH.patchCalled = null;
        OverloadedMethodsH.calculate(5, 7);
        assertEquals("arg1_local_7", OverloadedMethodsH.patchCalled, 
            "Patch with @Argument(1) and @Local should match calculate(int, int) and share local variable");
        
        // Call three-parameter method - patch should be called, local variable should work
        OverloadedMethodsH.patchCalled = null;
        OverloadedMethodsH.calculate(2, 3, 4);
        assertEquals("arg1_local_3", OverloadedMethodsH.patchCalled, 
            "Patch with @Argument(1) and @Local should match calculate(int, int, int) and share local variable");
        
        // Return values should be unchanged
        assertEquals(50, OverloadedMethodsH.calculate(5));
        assertEquals(35, OverloadedMethodsH.calculate(5, 7));
        assertEquals(24, OverloadedMethodsH.calculate(2, 3, 4));
    }
}

