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
    
    @Test
    void testGetStringToNullReturnsNull() {
        assertNull(TargetClass.getStringToNull());
    }
    
    @Test
    void testAddMoveEventScalesCoordinates() {
        // addMoveEvent should scale coordinates by 2.0 (simulating retina scaling)
        // Original: stores (100.0, 200.0)
        // Patched: stores (100.0 * 2.0, 200.0 * 2.0) = (200.0, 400.0)
        testjar.MouseCoordinates.addMoveEvent(100.0, 200.0);
        assertEquals(200.0, testjar.MouseCoordinates.getLastX(), 0.001);
        assertEquals(400.0, testjar.MouseCoordinates.getLastY(), 0.001);
        
        // Test with different coordinates
        testjar.MouseCoordinates.addMoveEvent(50.5, 75.5);
        assertEquals(101.0, testjar.MouseCoordinates.getLastX(), 0.001); // 50.5 * 2.0
        assertEquals(151.0, testjar.MouseCoordinates.getLastY(), 0.001); // 75.5 * 2.0
    }
}

