package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsF;

public class PatchedTestOverloadedMethodsF {
    @Test
    void testOverloadedMethodsPatchWithArg1() {
        // Scenario 6: Multiple method matches with @Argument(1)
        // @Argument(1) should match calculate(int, int) and calculate(int, int, int)
        // but NOT calculate(int) since it doesn't have a second parameter
        
        // Call single-parameter method - patch should NOT be called
        OverloadedMethodsF.patchCalled = null;
        OverloadedMethodsF.calculate(5);
        assertNull(OverloadedMethodsF.patchCalled, "Patch with @Argument(1) should NOT match calculate(int)");
        
        // Call two-parameter method - patch should be called
        OverloadedMethodsF.patchCalled = null;
        OverloadedMethodsF.calculate(5, 7);
        assertEquals("arg1_match", OverloadedMethodsF.patchCalled, "Patch with @Argument(1) should match calculate(int, int)");
        
        // Call three-parameter method - patch should be called
        OverloadedMethodsF.patchCalled = null;
        OverloadedMethodsF.calculate(2, 3, 4);
        assertEquals("arg1_match", OverloadedMethodsF.patchCalled, "Patch with @Argument(1) should match calculate(int, int, int)");
        
        // Return values should be unchanged
        assertEquals(50, OverloadedMethodsF.calculate(5));
        assertEquals(35, OverloadedMethodsF.calculate(5, 7));
        assertEquals(24, OverloadedMethodsF.calculate(2, 3, 4));
    }
}

