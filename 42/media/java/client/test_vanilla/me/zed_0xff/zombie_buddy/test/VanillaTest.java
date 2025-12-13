package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.TargetClass;

public class VanillaTest {
    @Test
    void testGet42Returns42() {
        assertEquals(42, TargetClass.get_42());
    }
    
    @Test
    void testGetStringReturnsHello() {
        assertEquals("hello", TargetClass.getString());
    }
}

