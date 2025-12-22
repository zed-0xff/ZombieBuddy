package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.AdviceLocalTarget;

public class PatchedTestAdviceLocal {
    @Test
    void testAdviceLocalDefaultInitialization() {
        // Reset flags
        AdviceLocalTarget.localIntValue = -1;
        AdviceLocalTarget.localIntegerValue = 999;
        AdviceLocalTarget.primitiveInitialized = false;
        AdviceLocalTarget.referenceInitialized = false;
        AdviceLocalTarget.enterCalled = false;
        AdviceLocalTarget.exitCalled = false;
        
        // Call the method
        int result = AdviceLocalTarget.calculate(5, 7);
        
        // Verify the original method still works
        assertEquals(12, result, "Original method should return correct value");
        
        // Verify enter was called
        assertTrue(AdviceLocalTarget.enterCalled, "@OnEnter should be called");
        
        // Verify exit was called
        assertTrue(AdviceLocalTarget.exitCalled, "@OnExit should be called");
        
        // Verify primitive local variable was initialized to 0 (default value)
        assertTrue(AdviceLocalTarget.primitiveInitialized, 
            "Primitive @Local variable should be initialized to 0 (default value)");
        assertEquals(0, AdviceLocalTarget.localIntValue, 
            "Primitive @Local variable should be 0 in exit as well");
        
        // Verify reference local variable was initialized to null (default value)
        assertTrue(AdviceLocalTarget.referenceInitialized, 
            "Reference @Local variable should be initialized to null (default value)");
        assertNull(AdviceLocalTarget.localIntegerValue, 
            "Reference @Local variable should be null in exit as well");
    }
}

