package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsI;

public class PatchedTestOverloadedMethodsI {
    @Test
    void testOverloadedMethodsPatchWithArg0() {
        // Scenario 9: Multiple method matches with @Argument(0)
        // @Argument(0) should match calculate(int), calculate(int, int), and calculate(int, int, int)
        // since all have at least 1 parameter
        
        // Call single-parameter method - patch should be called
        OverloadedMethodsI.patchCalled = null;
        OverloadedMethodsI.calculate(5);
        assertEquals("arg0_match", OverloadedMethodsI.patchCalled, 
            "Patch with @Argument(0) should match calculate(int)");
        
        // Call two-parameter method - patch should be called
        OverloadedMethodsI.patchCalled = null;
        OverloadedMethodsI.calculate(5, 7);
        assertEquals("arg0_match", OverloadedMethodsI.patchCalled, 
            "Patch with @Argument(0) should match calculate(int, int)");
        
        // Call three-parameter method - patch should be called
        OverloadedMethodsI.patchCalled = null;
        OverloadedMethodsI.calculate(2, 3, 4);
        assertEquals("arg0_match", OverloadedMethodsI.patchCalled, 
            "Patch with @Argument(0) should match calculate(int, int, int)");
        
        // Return values should be unchanged
        assertEquals(50, OverloadedMethodsI.calculate(5));
        assertEquals(35, OverloadedMethodsI.calculate(5, 7));
        assertEquals(24, OverloadedMethodsI.calculate(2, 3, 4));
    }
}

