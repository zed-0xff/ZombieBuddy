package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.OverloadedDrawTextTarget;

public class VanillaTestOverloadedDrawText {
    @Test
    void testDrawTextNoOverride() {
        OverloadedDrawTextTarget.lastText = null;
        OverloadedDrawTextTarget.DrawText(null, "hello", 1.0, 2.0);
        assertEquals("hello", OverloadedDrawTextTarget.lastText);

        OverloadedDrawTextTarget.lastX = 0;
        OverloadedDrawTextTarget.DrawText("world", 10.0, 20.0, 1.0);
        assertEquals(10.0, OverloadedDrawTextTarget.lastX);
    }
}
