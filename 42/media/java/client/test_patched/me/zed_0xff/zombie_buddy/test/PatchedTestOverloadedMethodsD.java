package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsD;

public class PatchedTestOverloadedMethodsD {
    @Test
    void testOverloadedMethodsPatchUnified() {
        // Scenario 4: Patch both methods with a single unified patch
        OverloadedMethodsD.patchCalled = null;
        
        // Call single-parameter method - unified patch should be called
        OverloadedMethodsD.calculate(5);
        assertEquals("unified", OverloadedMethodsD.patchCalled, "Unified patch should be called for calculate(int)");
        
        // Call two-parameter method - unified patch should be called again
        OverloadedMethodsD.patchCalled = null;
        OverloadedMethodsD.calculate(5, 7);
        assertEquals("unified", OverloadedMethodsD.patchCalled, "Unified patch should be called for calculate(int, int)");
        
        // Return values should be unchanged
        assertEquals(50, OverloadedMethodsD.calculate(5));
        assertEquals(35, OverloadedMethodsD.calculate(5, 7));
    }
}

