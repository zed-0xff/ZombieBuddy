package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper to read (including private) field values from objects via reflection.
 * Returns a default value if the field is missing or inaccessible.
 */
public final class Accessor {

    private static final ConcurrentHashMap<String, Boolean> hasPublicMethodCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Optional<Field>> findFieldCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Optional<Method>> findMethodCache = new ConcurrentHashMap<>();

    private Accessor() {}

    /**
     * Gets the value of the named field on {@code obj}, or {@code defaultValue} if
     * the object is null, the field does not exist, or it cannot be read.
     * If {@code obj} is a {@link Class}, the field is looked up on that class and
     * read as a static field (instance argument is ignored for static fields).
     *
     * @param obj          the instance to read from, or a Class for static field lookup (may be null)
     * @param fieldName    the field name (searches this class and superclasses)
     * @param defaultValue value to return when the field cannot be read
     * @return the field value (including null), or {@code defaultValue} only if the field could not be read
     */
    public static Object tryGet(Object obj, String fieldName, Object defaultValue) {
        if (obj == null || fieldName == null || fieldName.isEmpty()) {
            return defaultValue;
        }
        Class<?> cls = obj instanceof Class ? (Class<?>) obj : obj.getClass();
        Field field = findField(cls, fieldName);
        Object instance = obj instanceof Class ? null : obj;
        return tryGet(instance, field, defaultValue);
    }

    /**
     * Gets the value of {@code field} on {@code obj}, or {@code defaultValue} if
     * the field is null or it cannot be read. For static fields, {@code obj} may be null.
     *
     * @param obj          the instance to read from (null for static fields)
     * @param field        the field to read (may be null)
     * @param defaultValue value to return when the field cannot be read
     * @return the field value (including null), or {@code defaultValue} only if the field could not be read
     */
    public static Object tryGet(Object obj, Field field, Object defaultValue) {
        if (field == null) {
            return defaultValue;
        }
        if (obj == null && !Modifier.isStatic(field.getModifiers())) {
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
    public static boolean trySet(Object obj, String fieldName, Object value) {
        if (obj == null || fieldName == null || fieldName.isEmpty()) {
            return false;
        }
        Field field = findField(obj.getClass(), fieldName);
        return trySet(obj, field, value);
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
    public static boolean trySet(Object obj, Field field, Object value) {
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
     * Finds a field by name in {@code cls} or any superclass. Accepts multiple candidate
     * names and returns the first found. Results are cached per (class name, field name).
     *
     * @param cls        the class to search (and its superclasses)
     * @param fieldNames one or more field names to try in order; null/empty names are skipped
     * @return the first found field, or null if none exist
     */
    public static Field findField(Class<?> cls, String... fieldNames) {
        if (cls == null || fieldNames == null || fieldNames.length == 0) {
            return null;
        }
        for (String fieldName : fieldNames) {
            if (fieldName != null && !fieldName.isEmpty()) {
                Field f = findFieldCached(cls, fieldName);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private static Field findFieldCached(Class<?> cls, String fieldName) {
        String key = cls.getName() + "\0" + fieldName;
        return findFieldCache.computeIfAbsent(key, k -> Optional.ofNullable(findFieldUncached(cls, fieldName))).orElse(null);
    }

    private static Field findFieldUncached(Class<?> cls, String fieldName) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // continue to superclass
            }
        }
        return null;
    }

    /**
     * Finds a no-arg method by name in {@code cls} or any superclass. Returns null if not found.
     */
    public static Method findMethod(Class<?> cls, String methodName) {
        return findMethod(cls, methodName, (Class<?>[]) null);
    }

    /**
     * Finds a method by name and parameter types in {@code cls} or any superclass. Returns null if not found.
     * Pass empty array or null for no-arg method. Results are cached per (class name, method name, parameter types).
     */
    public static Method findMethod(Class<?> cls, String methodName, Class<?>... parameterTypes) {
        String key = buildFindMethodCacheKey(cls, methodName, parameterTypes);
        return findMethodCache.computeIfAbsent(key, k -> Optional.ofNullable(findMethodUncached(cls, methodName, parameterTypes))).orElse(null);
    }

    private static String buildFindMethodCacheKey(Class<?> cls, String methodName, Class<?>[] parameterTypes) {
        StringBuilder sb = new StringBuilder(cls.getName()).append('\0').append(methodName).append('\0');
        if (parameterTypes != null && parameterTypes.length > 0) {
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) sb.append('\0');
                sb.append(parameterTypes[i].getName());
            }
        }
        return sb.toString();
    }

    private static Method findMethodUncached(Class<?> cls, String methodName, Class<?>[] parameterTypes) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                if (parameterTypes == null || parameterTypes.length == 0) {
                    return c.getDeclaredMethod(methodName);
                }
                return c.getDeclaredMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException e) {
                // continue to superclass
            }
        }
        return null;
    }

    /**
     * Returns true if {@code obj}'s class (including inherited public methods) has a public
     * method with the given name (any parameter count).
     *
     * @throws IllegalArgumentException if obj or methodName is null, or methodName is empty
     */
    public static boolean hasPublicMethod(Object obj, String methodName) {
        if (obj == null || methodName == null || methodName.isEmpty()) {
            throw new IllegalArgumentException("obj and methodName must be non-null and non-empty");
        }
        Class<?> cls = obj.getClass();
        String cacheKey = cls.getName() + "\0" + methodName;
        return hasPublicMethodCache.computeIfAbsent(cacheKey, k -> {
            for (Method m : cls.getMethods()) {
                if (methodName.equals(m.getName())) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Invokes the named no-arg method on {@code obj}. Searches class hierarchy for the method,
     * sets it accessible, then invokes. Does not catch exceptions; callers must handle
     * ReflectiveOperationException (or IllegalAccessException, InvocationTargetException, etc.).
     *
     * @param obj        the instance to call on (may not be null)
     * @param methodName the name of the no-arg method
     * @return the return value of the method (possibly null)
     * @throws NoSuchMethodException  if the method is not found
     * @throws ReflectiveOperationException if setAccessible or invoke fails
     */
    public static Object call(Object obj, String methodName) throws ReflectiveOperationException {
        return call(obj, methodName, (Class<?>[]) null);
    }

    /**
     * Invokes the named method on {@code obj} with the given parameter types and arguments.
     * Searches class hierarchy for the method, sets it accessible, then invokes. Does not catch
     * exceptions; callers must handle ReflectiveOperationException.
     *
     * @param obj             the instance to call on (may not be null)
     * @param methodName      the name of the method
     * @param parameterTypes  the method parameter types (null or empty for no-arg)
     * @param args            the arguments to pass (will be unboxed for primitive params)
     * @return the return value of the method (possibly null)
     * @throws NoSuchMethodException  if the method is not found
     * @throws ReflectiveOperationException if setAccessible or invoke fails
     */
    public static Object call(Object obj, String methodName, Class<?>[] parameterTypes, Object... args) throws ReflectiveOperationException {
        if (obj == null || methodName == null || methodName.isEmpty()) {
            throw new IllegalArgumentException("obj and methodName must be non-null and non-empty");
        }
        Method m = findMethod(obj.getClass(), methodName, parameterTypes);
        if (m == null) {
            throw new NoSuchMethodException(methodName);
        }
        m.setAccessible(true);
        return m.invoke(obj, args);
    }
}
