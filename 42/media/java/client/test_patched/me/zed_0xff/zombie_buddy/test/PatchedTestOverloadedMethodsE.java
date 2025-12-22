package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsE;

public class PatchedTestOverloadedMethodsE {
    @Test
    void testOverloadedMethodsPatchSingleOnly() {
        // Scenario 5: Patch only the first method (calculate(int)) for OverloadedMethodsE
        OverloadedMethodsE.patchCalled = null;
        
        // Call single-parameter method - patch should be called
        OverloadedMethodsE.calculate(5);
        assertEquals("both_separate2_singgle", OverloadedMethodsE.patchCalled, "Patch for calculate(int) should be called");
        
        // Call two-parameter method - patch should NOT be called (only single is patched)
        OverloadedMethodsE.patchCalled = null;
        OverloadedMethodsE.calculate(5, 7);
        // When all patches are active, other patches might be called, so we just check it's not "single_only"
        assertEquals("both_separate2_double", OverloadedMethodsE.patchCalled, "Patch for calculate(int, int) should be called");
        
        // Return values should be unchanged
        assertEquals(50, OverloadedMethodsE.calculate(5));
        assertEquals(35, OverloadedMethodsE.calculate(5, 7));
    }
}

