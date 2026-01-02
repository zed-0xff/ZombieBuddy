package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.ArgumentOverrideTarget;

public class PatchedTestArgumentOverride {
    @Test
    void testMultiplyArgumentOverride() {
        // Original multiply(2, 3) = 6
        // Patched multiply(2, 3) where b is overridden to 10 should return 2 * 10 = 20
        assertEquals(20, ArgumentOverrideTarget.multiply(2, 3));
    }
}
