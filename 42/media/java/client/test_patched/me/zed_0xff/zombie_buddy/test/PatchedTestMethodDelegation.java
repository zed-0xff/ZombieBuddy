package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.MethodDelegationTest;

public class PatchedTestMethodDelegation {
    @Test
    void testMethodDelegationMultiply() {
        // Test MethodDelegation patch for multiply(int, int)
        // The patch should add 100 to the original result
        MethodDelegationTest.wasCalled = false;
        
        int result = MethodDelegationTest.multiply(5, 7);
        // Original: 5 * 7 = 35, Patched: 35 + 100 = 135
        assertEquals(135, result, "MethodDelegation should modify the return value");
        
        // Verify the original method was called (via superMethod.invoke)
        assertTrue(MethodDelegationTest.wasCalled, "Original method should be called via superMethod");
    }
    
    @Test
    void testMethodDelegationNoArgs() {
        // Test MethodDelegation patch for getMessage() with no parameters
        String result = MethodDelegationTest.getMessage();
        // Original: "original", Patched: "original_patched"
        assertEquals("original_patched", result, "MethodDelegation should modify the return value");
    }
}

