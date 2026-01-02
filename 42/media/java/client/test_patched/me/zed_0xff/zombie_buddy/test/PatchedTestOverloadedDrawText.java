package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedDrawTextTarget;

public class PatchedTestOverloadedDrawText {
    @Test
    void testDrawTextOverloadPatched() {
        // This call should be patched because index 1 is a String
        OverloadedDrawTextTarget.lastText = null;
        OverloadedDrawTextTarget.DrawText(null, "hello", 1.0, 2.0);
        assertEquals("patched:hello", OverloadedDrawTextTarget.lastText);
    }

    @Test
    void testDrawTextOtherOverloadNotPatched() {
        // This call should NOT be patched because index 1 is a double (x)
        // If ZombieBuddy tried to patch it, we'd get the IllegalStateException during class loading
        OverloadedDrawTextTarget.lastX = 0;
        OverloadedDrawTextTarget.DrawText("world", 10.0, 20.0, 1.0);
        assertEquals(10.0, OverloadedDrawTextTarget.lastX);
    }
}
