package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.TargetClass;
import testjar.CustomObject;
import testjar.OverloadedMethodsB;

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
    
    @Test
    void testCalculateSingleParameter() {
        // calculate(5) should return 50 (unpatched: 5 * 10)
        assertEquals(50, OverloadedMethodsB.calculate(5));
        assertEquals(100, OverloadedMethodsB.calculate(10));
    }
    
    @Test
    void testCalculateTwoParameters() {
        // calculate(5, 7) should return 35 (unpatched: 5 * 7)
        assertEquals(35, OverloadedMethodsB.calculate(5, 7));
        assertEquals(20, OverloadedMethodsB.calculate(4, 5));
    }
    
    @Test
    void testOverloadedMethodsNoPatches() {
        // Verify no patches are active in vanilla tests
        // patchCalled should remain null
        OverloadedMethodsB.patchCalled = null;
        OverloadedMethodsB.calculate(5);
        assertNull(OverloadedMethodsB.patchCalled, "patchCalled should be null when no patches are applied");
        
        OverloadedMethodsB.patchCalled = null;
        OverloadedMethodsB.calculate(5, 7);
        assertNull(OverloadedMethodsB.patchCalled, "patchCalled should be null when no patches are applied");
    }
}

