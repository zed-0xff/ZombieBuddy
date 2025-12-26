package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.ConstructorDelegationTargetA1;
import testjar.ConstructorDelegationTargetA2;

public class VanillaTestConstructorDelegation {
    @Test
    void testConstructorDelegationVanillaA1() {
        // Test that constructor works without MethodDelegation patch
        var instance = new ConstructorDelegationTargetA1(42, "test");
        
        assertEquals(42, instance.getValue());
        assertEquals("test", instance.getName());
        assertTrue(instance.constructorCalled, "Original constructor should be called");
        assertFalse(instance.patchIntercepted, "Patch should NOT be applied in vanilla");
        assertEquals(42, instance.defaultField, "Default field should remain unchanged");

        var instance0 = new ConstructorDelegationTargetA1();
        assertEquals(42, instance0.defaultField, "Default field should remain unchanged");
    }

    @Test
    void testConstructorDelegationVanillaA2() {
        // Test that constructor works without MethodDelegation patch
        var instance = new ConstructorDelegationTargetA2(42, "test");
        
        assertEquals(42, instance.getValue());
        assertEquals("test", instance.getName());
        assertTrue(instance.constructorCalled, "Original constructor should be called");
        assertFalse(instance.patchIntercepted, "Patch should NOT be applied in vanilla");
        assertEquals(42, instance.defaultField, "Default field should remain unchanged");
    }
}
