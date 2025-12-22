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
    
    @Test
    void testGetStringToNullReturnsTest() {
        assertEquals("test", TargetClass.getStringToNull());
    }
    
    @Test
    void testAddMoveEventStoresCoordinates() {
        // addMoveEvent should store coordinates as-is (unpatched)
        testjar.MouseCoordinates.addMoveEvent(100.0, 200.0);
        assertEquals(100.0, testjar.MouseCoordinates.getLastX(), 0.001);
        assertEquals(200.0, testjar.MouseCoordinates.getLastY(), 0.001);
        
        testjar.MouseCoordinates.addMoveEvent(50.5, 75.5);
        assertEquals(50.5, testjar.MouseCoordinates.getLastX(), 0.001);
        assertEquals(75.5, testjar.MouseCoordinates.getLastY(), 0.001);
    }
}

