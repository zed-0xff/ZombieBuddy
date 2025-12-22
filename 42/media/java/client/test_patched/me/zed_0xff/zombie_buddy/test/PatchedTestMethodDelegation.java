package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.MethodDelegationTarget;

public class PatchedTestMethodDelegation {
    @Test
    void testMethodDelegationMultiply() {
        // Test MethodDelegation patch for multiply(int, int)
        // The patch should add 100 to the original result
        MethodDelegationTarget.wasCalled = false;
        
        int result = MethodDelegationTarget.multiply(5, 7);
        // Original: 5 * 7 = 35, Patched: 35 + 100 = 135
        assertEquals(135, result, "MethodDelegation should modify the return value");
        
        // Verify the original method was called (via superMethod.invoke)
        assertTrue(MethodDelegationTarget.wasCalled, "Original method should be called via superMethod");
    }
    
    @Test
    void testMethodDelegationNoArgs() {
        // Test MethodDelegation patch for getMessage() with no parameters
        String result = MethodDelegationTarget.getMessage();
        // Original: "original", Patched: "original_patched"
        assertEquals("original_patched", result, "MethodDelegation should modify the return value");
    }
}

