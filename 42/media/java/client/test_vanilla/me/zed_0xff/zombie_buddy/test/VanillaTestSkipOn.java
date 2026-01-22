package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.SkipOnTarget;

public class VanillaTestSkipOn {
    @Test
    void testSkipOnVanilla() {
        SkipOnTarget.counter = 0;
        int result = SkipOnTarget.testMethod(5);
        assertEquals(10, result);
        assertEquals(1, SkipOnTarget.counter);
    }
}
