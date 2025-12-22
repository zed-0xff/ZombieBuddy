package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsA;

public class PatchedTestOverloadedMethodsA {
    @Test
    void testOverloadedMethodsPatchSingleOnly() {
        // Scenario 1: Patch only the first method (calculate(int))
        OverloadedMethodsA.patchCalled = null;
        
        // Call single-parameter method - patch should be called
        OverloadedMethodsA.calculate(5);
        assertEquals("single_only", OverloadedMethodsA.patchCalled, "Patch for calculate(int) should be called");
        
        // Call two-parameter method - patch should NOT be called (only single is patched)
        OverloadedMethodsA.patchCalled = null;
        OverloadedMethodsA.calculate(5, 7);
        // When all patches are active, other patches might be called, so we just check it's not "single_only"
        assertNotEquals("single_only", OverloadedMethodsA.patchCalled, "Patch for calculate(int) should NOT be called for calculate(int, int)");
        
        // Return values should be unchanged
        assertEquals(50, OverloadedMethodsA.calculate(5));
        assertEquals(35, OverloadedMethodsA.calculate(5, 7));
    }
}

