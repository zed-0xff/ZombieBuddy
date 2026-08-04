package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedMethodsE;

public class PatchedTestOverloadedMethodsE {
    @Test
    void testOverloadedMethodsPatchBothSeparate() {
        OverloadedMethodsE.patchCalled = null;

        OverloadedMethodsE.calculate(5);
        assertEquals("both_separate_single", OverloadedMethodsE.patchCalled, "Single patch should be called");

        OverloadedMethodsE.patchCalled = null;
        OverloadedMethodsE.calculate(5, 7);
        assertEquals("both_separate_double", OverloadedMethodsE.patchCalled, "Double patch should be called");

        assertEquals(50, OverloadedMethodsE.calculate(5));
        assertEquals(35, OverloadedMethodsE.calculate(5, 7));
    }
}
