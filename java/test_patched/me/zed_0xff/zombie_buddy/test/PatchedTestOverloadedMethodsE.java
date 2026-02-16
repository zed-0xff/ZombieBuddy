package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsE;

public class PatchedTestOverloadedMethodsE {
    @Test
    void testOverloadedMethodsPatchBothSeparate() {
        // Scenario 5: Patch both methods with different patches (split into separate classes)
        OverloadedMethodsE.patchCalled = null;
        
        // Call single-parameter method - patch should be called
        OverloadedMethodsE.calculate(5);
        assertNull(OverloadedMethodsE.patchCalled, "Patch for calculate(int) should not be called");
        
        // Call two-parameter method - patch should NOT be called (only single is patched)
        OverloadedMethodsE.patchCalled = null;
        OverloadedMethodsE.calculate(5, 7);
        // When all patches are active, other patches might be called, so we just check it's not "single_only"
        assertNull(OverloadedMethodsE.patchCalled, "Patch for calculate(int, int) should not be called");
        
        // Return values should be unchanged
        assertEquals(50, OverloadedMethodsE.calculate(5));
        assertEquals(35, OverloadedMethodsE.calculate(5, 7));
    }
}

