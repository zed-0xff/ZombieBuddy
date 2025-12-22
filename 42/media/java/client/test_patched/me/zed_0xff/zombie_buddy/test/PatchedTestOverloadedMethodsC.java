package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsC;

public class PatchedTestOverloadedMethodsC {
    @Test
    void testOverloadedMethodsPatchBothSeparate() {
        // Scenario 3: Patch both methods with different patches
        OverloadedMethodsC.patchCalled = null;
        
        // Call single-parameter method - single patch should be called
        OverloadedMethodsC.calculate(5);
        assertEquals("both_separate_single", OverloadedMethodsC.patchCalled, "Single patch should be called");
        
        // Call two-parameter method - double patch should be called
        OverloadedMethodsC.patchCalled = null;
        OverloadedMethodsC.calculate(5, 7);
        assertEquals("both_separate_double", OverloadedMethodsC.patchCalled, "Double patch should be called");
        
        // Return values should be unchanged
        assertEquals(50, OverloadedMethodsC.calculate(5));
        assertEquals(35, OverloadedMethodsC.calculate(5, 7));
    }
}

