package me.zed_0xff.zombie_buddy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Accessor} tryGet, trySet, findExactMethod, hasPublicMethod, and call.
 */
public class AccessorTest {

    @SuppressWarnings("unused")
    public static class Target {
        public static final int publicStaticFinalInt = 123;
        public int publicInt = 42;
        private int privateInt = -7;
        public Integer publicInteger = 100;
        private Integer privateInteger = 200;
        public String publicString = "hello";
        private String privateString = "world";
        public Object publicNull = null;

        public String getPublicString() {
            return publicString;
        }

        private int privateGetInt() {
            return privateInt;
        }
    }

    @SuppressWarnings("unused")
    public static class TargetSub extends Target {
        private int childOnly = 999;
    }

    // --- tryGet(Object, String, Object) ---

    @Test
    void tryGet_withNullObj_returnsDefault() {
        Object def = new Object();
        assertSame(def, Accessor.tryGet(null, "publicInt", def));
    }

    @Test
    void tryGet_withNullFieldName_returnsDefault() {
        Target t = new Target();
        Object def = new Object();
        assertSame(def, Accessor.tryGet(t, (String) null, def));
    }

    @Test
    void tryGet_withEmptyFieldName_returnsDefault() {
        Target t = new Target();
        Object def = new Object();
        assertSame(def, Accessor.tryGet(t, "", def));
    }

    @Test
    void tryGet_publicStaticFinalInt_returnsBoxedValue() {
        Object raw = Accessor.tryGet(Target.class, "publicStaticFinalInt", 0);
        assertNotNull(raw);
        assertTrue(raw instanceof Integer);
        assertEquals(123, ((Number) raw).intValue());
    }

    @Test
    void tryGet_publicInt_returnsBoxedValue() {
        Target t = new Target();
        Object raw = Accessor.tryGet(t, "publicInt", 0);
        assertNotNull(raw);
        assertTrue(raw instanceof Integer);
        assertEquals(42, ((Number) raw).intValue());
    }

    @Test
    void tryGet_privateInt_returnsBoxedValue() {
        Target t = new Target();
        Object raw = Accessor.tryGet(t, "privateInt", 0);
        assertNotNull(raw);
        assertEquals(-7, ((Number) raw).intValue());
    }

    @Test
    void tryGet_publicInteger_returnsValue() {
        Target t = new Target();
        assertEquals(100, Accessor.tryGet(t, "publicInteger", 0));
    }

    @Test
    void tryGet_privateInteger_returnsValue() {
        Target t = new Target();
        assertEquals(200, Accessor.tryGet(t, "privateInteger", 0));
    }

    @Test
    void tryGet_publicString_returnsValue() {
        Target t = new Target();
        assertEquals("hello", Accessor.tryGet(t, "publicString", "default"));
    }

    @Test
    void tryGet_privateString_returnsValue() {
        Target t = new Target();
        assertEquals("world", Accessor.tryGet(t, "privateString", "default"));
    }

    @Test
    void tryGet_nullField_returnsNullNotDefault() {
        Target t = new Target();
        Object result = Accessor.tryGet(t, "publicNull", "default");
        assertNull(result);
    }

    @Test
    void tryGet_missingField_returnsDefault() {
        Target t = new Target();
        Object def = new Object();
        assertSame(def, Accessor.tryGet(t, "doesNotExist", def));
    }

    @Test
    void tryGet_fieldInSuperclass_returnsValue() {
        TargetSub sub = new TargetSub();
        assertEquals(42, Accessor.tryGet(sub, "publicInt", 0));
        assertEquals(-7, Accessor.tryGet(sub, "privateInt", 0));
        assertEquals(999, Accessor.tryGet(sub, "childOnly", 0));
    }

    @Test
    void tryGet_withIntDefault_returnsDefaultWhenFieldMissing() {
        Target t = new Target();
        Object result = Accessor.tryGet(t, "missing", 0);
        assertEquals(0, result);
    }

    // --- tryGet(Object, Field, Object) ---

    @Test
    void tryGetField_withNullObj_returnsDefault() throws NoSuchFieldException {
        Field f = Target.class.getDeclaredField("publicInt");
        Object def = new Object();
        assertSame(def, Accessor.tryGet(null, f, def));
    }

    @Test
    void tryGetField_withNullField_returnsDefault() {
        Target t = new Target();
        Object def = new Object();
        assertSame(def, Accessor.tryGet(t, (Field) null, def));
    }

    @Test
    void tryGetField_validField_returnsValue() throws NoSuchFieldException {
        Target t = new Target();
        Field f = Target.class.getDeclaredField("publicInt");
        assertEquals(42, Accessor.tryGet(t, f, 0));
    }

    @Test
    void tryGetField_privateField_returnsValue() throws NoSuchFieldException {
        Target t = new Target();
        Field f = Target.class.getDeclaredField("privateInt");
        assertEquals(-7, Accessor.tryGet(t, f, 0));
    }

    // --- trySet ---

    @Test
    void trySet_success_returnsTrue() {
        Target t = new Target();
        assertTrue(Accessor.trySet(t, "publicInt", 99));
        assertEquals(99, Accessor.tryGet(t, "publicInt", 0));
    }

    @Test
    void trySet_privateField_success() {
        Target t = new Target();
        assertTrue(Accessor.trySet(t, "privateString", "updated"));
        assertEquals("updated", Accessor.tryGet(t, "privateString", null));
    }

    @Test
    void trySet_nullObj_returnsFalse() {
        assertFalse(Accessor.trySet(null, "publicInt", 1));
    }

    @Test
    void trySet_missingField_returnsFalse() {
        Target t = new Target();
        assertFalse(Accessor.trySet(t, "doesNotExist", 1));
    }

    // --- findExactMethod ---

    @Test
    void findMethod_noArg_found() {
        assertNotNull(Accessor.findExactMethod(Target.class, "getPublicString"));
    }

    @Test
    void findMethod_noArg_notFound() {
        assertNull(Accessor.findExactMethod(Target.class, "noSuchMethod"));
    }

    // --- hasPublicMethod ---

    @Test
    void hasPublicMethod_exists_returnsTrue() {
        Target t = new Target();
        assertTrue(Accessor.hasPublicMethod(t, "getPublicString"));
    }

    @Test
    void hasPublicMethod_notExists_returnsFalse() {
        Target t = new Target();
        assertFalse(Accessor.hasPublicMethod(t, "noSuchMethod"));
    }

    @Test
    void hasPublicMethod_nullObj_throws() {
        assertThrows(IllegalArgumentException.class, () -> Accessor.hasPublicMethod(null, "getPublicString"));
    }

    @Test
    void hasPublicMethod_nullMethodName_throws() {
        Target t = new Target();
        assertThrows(IllegalArgumentException.class, () -> Accessor.hasPublicMethod(t, null));
    }

    // --- call ---

    @Test
    void call_noArg_returnsValue() throws Exception {
        Target t = new Target();
        assertEquals("hello", Accessor.callNoArg(t, "getPublicString"));
    }

    @Test
    void call_notFound_throws() {
        Target t = new Target();
        assertThrows(Exception.class, () -> Accessor.callNoArg(t, "noSuchMethod"));
    }
}
