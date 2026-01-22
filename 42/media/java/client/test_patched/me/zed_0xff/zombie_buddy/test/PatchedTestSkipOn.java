package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.SkipOnTarget;

public class PatchedTestSkipOn {
    @Test
    void testSkipOnPatched() {
        SkipOnTarget.counter = 0;
        int result = SkipOnTarget.testMethod(5);
        // Original method returns x * 2 = 10. 
        // If skipped, it should return default value for int, which is 0.
        assertEquals(0, result);
        // Counter should not be incremented if the method was skipped.
        assertEquals(0, SkipOnTarget.counter);
    }
}
