package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.TargetClass;
import testjar.CustomObject;

public class PatchedTest {
    @Test
    void testGet42Returns999() {
        assertEquals(999, TargetClass.get_42());
    }
    
    @Test
    void testGetStringReturnsHelloWorld() {
        assertEquals("hello world", TargetClass.getString());
    }
    
    @Test
    void testGetCustomObjectReturnsPatched() {
        CustomObject obj = TargetClass.getCustomObject();
        assertNotNull(obj);
        assertEquals("CustomObject{intValue=200, stringValue='patched'}", obj.toString());
    }
}

