package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.TargetClass;
import testjar.CustomObject;

public class VanillaTest {
    @Test
    void testGet42Returns42() {
        assertEquals(42, TargetClass.get_42());
    }
    
    @Test
    void testGetStringReturnsHello() {
        assertEquals("hello", TargetClass.getString());
    }
    
    @Test
    void testGetCustomObjectReturnsOriginal() {
        CustomObject obj = TargetClass.getCustomObject();
        assertNotNull(obj);
        assertEquals("CustomObject{intValue=100, stringValue='original'}", obj.toString());
    }
}

