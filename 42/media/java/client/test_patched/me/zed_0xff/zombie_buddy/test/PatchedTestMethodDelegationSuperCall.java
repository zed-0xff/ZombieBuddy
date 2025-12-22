package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.MethodDelegationSuperCallTest;

public class PatchedTestMethodDelegationSuperCall {
    @Test
    void testMethodDelegationSuperCall() {
        // Test MethodDelegation patch using @SuperCall for multiply(int, int)
        // The patch should multiply the original result by 2
        MethodDelegationSuperCallTest.wasCalled = false;
        
        int result = MethodDelegationSuperCallTest.multiply(5, 7);
        // Original: 5 * 7 = 35, Patched: 35 * 2 = 70
        assertEquals(70, result, "MethodDelegation with @SuperCall should modify the return value");
        
        // Verify the original method was called (via callable.call())
        assertTrue(MethodDelegationSuperCallTest.wasCalled, "Original method should be called via SuperCall");
    }
}

