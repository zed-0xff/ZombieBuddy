package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Field;

/**
 * Helper to read (including private) field values from objects via reflection.
 * Returns a default value if the field is missing or inaccessible.
 */
public final class Accessor {

    private Accessor() {}

    /**
     * Gets the value of the named field on {@code obj}, or {@code defaultValue} if
     * the object is null, the field does not exist, or it cannot be read.
     *
     * @param obj          the instance to read from (may be null)
     * @param fieldName    the field name (searches this class and superclasses)
     * @param defaultValue value to return when the field cannot be read
     * @return the field value (including null), or {@code defaultValue} only if the field could not be read
     */
    public static Object getFieldValueOrDefault(Object obj, String fieldName, Object defaultValue) {
        if (obj == null || fieldName == null || fieldName.isEmpty()) {
            return defaultValue;
        }
        Field field = findField(obj.getClass(), fieldName);
        return getFieldValueOrDefault(obj, field, defaultValue);
    }

    /**
     * Gets the value of {@code field} on {@code obj}, or {@code defaultValue} if
     * the object is null, the field is null, or it cannot be read.
     *
     * @param obj          the instance to read from (may be null)
     * @param field        the field to read (may be null)
     * @param defaultValue value to return when the field cannot be read
     * @return the field value (including null), or {@code defaultValue} only if the field could not be read
     */
    public static Object getFieldValueOrDefault(Object obj, Field field, Object defaultValue) {
        if (obj == null || field == null) {
            return defaultValue;
        }
        try {
            field.setAccessible(true);
            return field.get(obj);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    /**
     * Sets the named field on {@code obj} to {@code value}. Uses {@link Field#set(Object, Object)}
     * so primitive fields accept boxed values (e.g. Integer, Boolean).
     *
     * @param obj       the instance to write to (may be null)
     * @param fieldName the field name (searches this class and superclasses)
     * @param value     the value to set
     * @return true if the field was set successfully, false if obj/fieldName is null, field not found, or set threw
     */
    public static boolean setField(Object obj, String fieldName, Object value) {
        if (obj == null || fieldName == null || fieldName.isEmpty()) {
            return false;
        }
        Field field = findField(obj.getClass(), fieldName);
        return setField(obj, field, value);
    }

    /**
     * Sets {@code field} on {@code obj} to {@code value}. Uses {@link Field#set(Object, Object)}
     * so primitive fields accept boxed values (e.g. Integer, Boolean).
     *
     * @param obj   the instance to write to (may be null)
     * @param field the field to set (may be null)
     * @param value the value to set
     * @return true if the field was set successfully, false if obj/field is null or set threw
     */
    public static boolean setField(Object obj, Field field, Object value) {
        if (obj == null || field == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            field.set(obj, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Finds a field by name in {@code cls} or any superclass. Returns null if not found.
     */
    public static Field findField(Class<?> cls, String fieldName) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // continue to superclass
            }
        }
        return null;
    }
}
