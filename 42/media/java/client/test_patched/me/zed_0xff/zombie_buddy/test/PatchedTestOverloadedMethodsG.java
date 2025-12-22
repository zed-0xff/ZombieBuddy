package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsG;

public class PatchedTestOverloadedMethodsG {
    @Test
    void testOverloadedMethodsPatchWithArg2() {
        // Scenario 7: Double method only match with @Argument(2)
        // @Argument(2) should only match calculate(int, int, int)
        // but NOT calculate(int) or calculate(int, int) since they don't have a third parameter
        
        // Call single-parameter method - patch should NOT be called
        OverloadedMethodsG.patchCalled = null;
        OverloadedMethodsG.calculate(5);
        assertNull(OverloadedMethodsG.patchCalled, "Patch with @Argument(2) should NOT match calculate(int)");
        
        // Call two-parameter method - patch should NOT be called
        OverloadedMethodsG.patchCalled = null;
        OverloadedMethodsG.calculate(5, 7);
        assertNull(OverloadedMethodsG.patchCalled, "Patch with @Argument(2) should NOT match calculate(int, int)");
        
        // Call three-parameter method - patch should be called
        OverloadedMethodsG.patchCalled = null;
        OverloadedMethodsG.calculate(2, 3, 4);
        assertEquals("arg2_match", OverloadedMethodsG.patchCalled, "Patch with @Argument(2) should match calculate(int, int, int)");
        
        // Return values should be unchanged
        assertEquals(50, OverloadedMethodsG.calculate(5));
        assertEquals(35, OverloadedMethodsG.calculate(5, 7));
        assertEquals(24, OverloadedMethodsG.calculate(2, 3, 4));
    }
}

