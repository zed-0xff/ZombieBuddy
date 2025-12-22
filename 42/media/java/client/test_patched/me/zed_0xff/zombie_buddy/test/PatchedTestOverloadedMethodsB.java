package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsB;

public class PatchedTestOverloadedMethodsB {
    @Test
    void testOverloadedMethodsPatchDoubleOnly() {
        // Scenario 2: Patch only the second method (calculate(int, int))
        OverloadedMethodsB.patchCalled = null;
        
        // Call two-parameter method - patch should be called
        OverloadedMethodsB.calculate(5, 7);
        assertEquals("double_only", OverloadedMethodsB.patchCalled, "Patch for calculate(int, int) should be called");
        
        // Call single-parameter method - patch should NOT be called (only double is patched)
        OverloadedMethodsB.patchCalled = null;
        OverloadedMethodsB.calculate(5);
        // When all patches are active, other patches might be called, so we just check it's not "double_only"
        assertNotEquals("double_only", OverloadedMethodsB.patchCalled, "Patch for calculate(int, int) should NOT be called for calculate(int)");
        
        // Return values should be unchanged
        assertEquals(50, OverloadedMethodsB.calculate(5));
        assertEquals(35, OverloadedMethodsB.calculate(5, 7));
    }
}

