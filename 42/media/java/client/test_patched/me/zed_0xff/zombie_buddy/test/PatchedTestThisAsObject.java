package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.AdviceLocalTarget;

public class PatchedTestThisAsObject {
    
    @Test
    void testThisAsObjectPattern() {
        // Create an instance
        AdviceLocalTarget target = new AdviceLocalTarget();
        
        // Reset flag
        target.instancePatchCalled = false;
        
        // Call the instance method - the patch should set instancePatchCalled to true
        int result = target.multiply(5);
        
        // Verify the original method still works (10 * 5 = 50)
        assertEquals(50, result);
        
        // Verify the patch was called (using @Patch.This as Object pattern)
        assertTrue(target.instancePatchCalled, "Patch with @Patch.This as Object should have been called");
    }
}

