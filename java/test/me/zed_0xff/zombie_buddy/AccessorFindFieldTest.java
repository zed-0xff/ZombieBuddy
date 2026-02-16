package me.zed_0xff.zombie_buddy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Accessor#findField(Class, String...)} varargs overload.
 */
public class AccessorFindFieldTest {

    @SuppressWarnings("unused")
    public static class Target {
        public int first = 1;
        private String second = "two";
    }

    @Test
    void findField_singleName_returnsField() {
        Field f = Accessor.findField(Target.class, "first");
        assertNotNull(f);
        assertEquals("first", f.getName());
    }

    @Test
    void findField_varargs_returnsFirstFound() {
        Field f = Accessor.findField(Target.class, "missing", "alsoMissing", "first", "second");
        assertNotNull(f);
        assertEquals("first", f.getName());
    }

    @Test
    void findField_varargs_skipsMissingReturnsSecondCandidate() {
        Field f = Accessor.findField(Target.class, "missing", "second");
        assertNotNull(f);
        assertEquals("second", f.getName());
    }

    @Test
    void findField_varargs_noneFound_returnsNull() {
        assertNull(Accessor.findField(Target.class, "missing", "alsoMissing"));
    }

    @Test
    void findField_nullClass_returnsNull() {
        assertNull(Accessor.findField((Class<?>) null, "first"));
    }

    @Test
    void findField_noNames_returnsNull() {
        assertNull(Accessor.findField(Target.class));
    }

    @Test
    void findField_skipsNullAndEmptyNames() {
        Field f = Accessor.findField(Target.class, null, "", "first");
        assertNotNull(f);
        assertEquals("first", f.getName());
    }
}
