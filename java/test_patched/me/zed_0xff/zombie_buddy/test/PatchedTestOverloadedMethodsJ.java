package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsJ;

public class PatchedTestOverloadedMethodsJ {
    @Test
    void testOverloadedMethodsPatchWithArg0() {
        // Scenario 10: Single method match with one int argument and @Local variable
        
        // Call single-parameter method - patch should be called
        OverloadedMethodsJ.patchCalled = null;
        OverloadedMethodsJ.calculate(5);
        assertEquals("arg0_local_5", OverloadedMethodsJ.patchCalled, "Patch with (int firstArg, @Patch.Local) should match calculate(int)");
        
        // Call two-parameter method - patch should NOT be called
        OverloadedMethodsJ.patchCalled = null;
        OverloadedMethodsJ.calculate(5, 7);
        assertNull(OverloadedMethodsJ.patchCalled, "Patch with (int firstArg, @Patch.Local) should NOT match calculate(int, int)");
        
        // Call three-parameter method - patch should NOT be called
        OverloadedMethodsJ.patchCalled = null;
        OverloadedMethodsJ.calculate(2, 3, 4);
        assertNull(OverloadedMethodsJ.patchCalled, "Patch with (int firstArg, @Patch.Local) should NOT match calculate(int, int, int)");
        
        // Return values should be unchanged
        assertEquals(50, OverloadedMethodsJ.calculate(5));
        assertEquals(35, OverloadedMethodsJ.calculate(5, 7));
        assertEquals(24, OverloadedMethodsJ.calculate(2, 3, 4));
    }
}

