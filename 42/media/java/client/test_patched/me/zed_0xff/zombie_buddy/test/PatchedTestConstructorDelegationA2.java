package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.ConstructorDelegationTargetA2;

public class PatchedTestConstructorDelegationA2 {
    
    @Test
    void testConstructorDelegation() {
        // Test MethodDelegation patch for constructor with parameters
        var instance = new ConstructorDelegationTargetA2(42, "test");
        
        // Verify the original constructor was NOT called (we're bypassing it)
        assertFalse(instance.constructorCalled, "Original constructor should NOT be called when using MethodDelegation without SuperMethodCall");
        
        // Verify the patch intercepted the constructor
        assertTrue(instance.patchIntercepted, "Patch should have intercepted the constructor");
        
        // Verify the instance was constructed correctly
        assertEquals(420, instance.getValue());
        assertEquals("test patched", instance.getName());

        assertEquals(0, instance.defaultField, "Default field should be 0 because it was not initialized");
    }
}

