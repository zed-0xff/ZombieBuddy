package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.AdviceLocalMutableTest;

public class PatchedTestAdviceLocalMutable {
    @Test
    void testAdviceLocalAssignmentAndPersistence() {
        // Reset flags
        AdviceLocalMutableTest.sharedValue = 0;
        AdviceLocalMutableTest.enterCalled = false;
        AdviceLocalMutableTest.exitCalled = false;
        
        // Call the method
        int result = AdviceLocalMutableTest.calculate(5, 7);
        
        // Verify the original method still works
        assertEquals(12, result, "Original method should return correct value");
        
        // Verify enter was called
        assertTrue(AdviceLocalMutableTest.enterCalled, "@OnEnter should be called");
        
        // Verify exit was called
        assertTrue(AdviceLocalMutableTest.exitCalled, "@OnExit should be called");
        
        // Verify the local variable assignment persisted from enter to exit
        // The @Local variable was initialized to 0, then assigned x+y (12) in enter,
        // and that value should persist to exit
        assertEquals(12, AdviceLocalMutableTest.sharedValue, 
            "@Local variable assignment should persist from enter to exit");
    }
}

