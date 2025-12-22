package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.AdviceLocalTest;

public class PatchedTestAdviceLocal {
    @Test
    void testAdviceLocalDefaultInitialization() {
        // Reset flags
        AdviceLocalTest.localIntValue = -1;
        AdviceLocalTest.localIntegerValue = new Integer(999);
        AdviceLocalTest.primitiveInitialized = false;
        AdviceLocalTest.referenceInitialized = false;
        AdviceLocalTest.enterCalled = false;
        AdviceLocalTest.exitCalled = false;
        
        // Call the method
        int result = AdviceLocalTest.calculate(5, 7);
        
        // Verify the original method still works
        assertEquals(12, result, "Original method should return correct value");
        
        // Verify enter was called
        assertTrue(AdviceLocalTest.enterCalled, "@OnEnter should be called");
        
        // Verify exit was called
        assertTrue(AdviceLocalTest.exitCalled, "@OnExit should be called");
        
        // Verify primitive local variable was initialized to 0 (default value)
        assertTrue(AdviceLocalTest.primitiveInitialized, 
            "Primitive @Local variable should be initialized to 0 (default value)");
        assertEquals(0, AdviceLocalTest.localIntValue, 
            "Primitive @Local variable should be 0 in exit as well");
        
        // Verify reference local variable was initialized to null (default value)
        assertTrue(AdviceLocalTest.referenceInitialized, 
            "Reference @Local variable should be initialized to null (default value)");
        assertNull(AdviceLocalTest.localIntegerValue, 
            "Reference @Local variable should be null in exit as well");
    }
}

