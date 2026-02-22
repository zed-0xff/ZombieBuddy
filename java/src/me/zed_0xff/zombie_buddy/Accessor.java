package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    public static <T> T tryGet(Object obj, String fieldName, T defaultValue) {
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
    @SuppressWarnings("unchecked")
    public static <T> T tryGet(Object obj, Field field, T defaultValue) {
        if (field == null) {
            return defaultValue;
        }
        if (obj == null && !Modifier.isStatic(field.getModifiers())) {
            return defaultValue;
        }
        try {
            field.setAccessible(true);
            return (T) field.get(obj);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    /**
     * Sets the named field on {@code obj} to {@code value}. Uses {@link Field#set(Object, Object)}
     * so primitive fields accept boxed values (e.g. Integer, Boolean).
     * If {@code obj} is a {@link Class}, the field is looked up on that class and set as a static field.
     *
     * @param obj       the instance to write to, or a Class for static field lookup
     * @param fieldName the field name (searches this class and superclasses)
     * @param value     the value to set
     * @return true if the field was set successfully, false if obj/fieldName is null, field not found, or set threw
     */
    public static <T> boolean trySet(Object obj, String fieldName, T value) {
        if (obj == null || fieldName == null || fieldName.isEmpty()) {
            return false;
        }
        Class<?> cls = obj instanceof Class ? (Class<?>) obj : obj.getClass();
        Field field = findField(cls, fieldName);
        Object instance = obj instanceof Class ? null : obj;
        return trySet(instance, field, value);
    }

    /**
     * Sets {@code field} on {@code obj} to {@code value}. Uses {@link Field#set(Object, Object)}
     * so primitive fields accept boxed values (e.g. Integer, Boolean).
     * For static fields, {@code obj} may be null.
     *
     * @param obj   the instance to write to (null for static fields)
     * @param field the field to set (may be null)
     * @param value the value to set
     * @return true if the field was set successfully, false if field is null, or obj is null for instance field, or set threw
     */
    public static <T> boolean trySet(Object obj, Field field, T value) {
        if (field == null) {
            return false;
        }
        if (obj == null && !Modifier.isStatic(field.getModifiers())) {
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

    public static Class<?> findClass(String... classNames) {
        if (classNames == null || classNames.length == 0) {
            return null;
        }
        for (String className : classNames) {
            if (className != null && !className.isEmpty()) {
                String normalized = className.replace('/', '.');
                Class<?> cls = null;
                try {
                    cls = Class.forName(normalized);
                } catch (ClassNotFoundException e) {
                    continue;
                }
                if (cls != null) {
                    return cls;
                }
            }
        }
        return null;
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

    /**
     * Finds a field by name in the class named {@code className} or any superclass.
     * Accepts multiple candidate names and returns the first found.
     *
     * @param className  fully qualified class name
     * @param fieldNames one or more field names to try in order; null/empty names are skipped
     * @return the first found field, or null if the class cannot be loaded or no field exists
     */
    public static Field findField(String className, String... fieldNames) {
        if (className == null || className.isEmpty() || fieldNames == null || fieldNames.length == 0) {
            return null;
        }
        try {
            Class<?> cls = Class.forName(className);
            return findField(cls, fieldNames);
        } catch (ClassNotFoundException e) {
            return null;
        }
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
     * Finds all methods with the given name in {@code cls} and its superclasses (declared on each class).
     * Includes overloads and overrides. Order: current class methods first, then superclass, etc.
     *
     * @param cls        the class to search (and its superclasses)
     * @param methodName the method name to match
     * @return list of matching methods (possibly empty); never null
     */
    public static List<Method> findMethodsByName(Class<?> cls, String methodName) {
        if (cls == null || methodName == null || methodName.isEmpty()) {
            return Collections.emptyList();
        }
        List<Method> out = new ArrayList<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (methodName.equals(m.getName())) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * Finds a no-arg method by name in {@code cls} or any superclass. Returns null if not found.
     */
    public static Method findNoArgMethod(Class<?> cls, String methodName) {
        return findExactMethod(cls, methodName, (Class<?>[]) null);
    }

    /**
     * Finds a method by name and parameter types in {@code cls} or any superclass. Returns null if not found.
     * Pass empty array or null for no-arg method. Results are cached per (class name, method name, parameter types).
     */
    public static Method findExactMethod(Class<?> cls, String methodName, Class<?>... parameterTypes) {
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
    public static Object callNoArg(Object obj, String methodName) throws ReflectiveOperationException {
        return callExact(obj, methodName, (Class<?>[]) null);
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
    public static Object callExact(Object obj, String methodName, Class<?>[] parameterTypes, Object... args) throws ReflectiveOperationException {
        if (obj == null || methodName == null || methodName.isEmpty()) {
            throw new IllegalArgumentException("obj and methodName must be non-null and non-empty");
        }
        Method m = findExactMethod(obj.getClass(), methodName, parameterTypes);
        if (m == null) {
            throw new NoSuchMethodException(methodName);
        }
        m.setAccessible(true);
        return m.invoke(obj, args);
    }

    /**
     * Invokes a method with the given name on {@code obj}, choosing an overload by argument count
     * and compatibility. Uses {@link #findMethodsByName} and tries each candidate with matching
     * parameter count; the first that accepts the arguments (via normal invoke boxing/assignment)
     * is used. Does not catch exceptions; callers must handle ReflectiveOperationException.
     *
     * @param obj        the instance to call on (may not be null)
     * @param methodName the name of the method
     * @param args       the arguments to pass (null treated as no arguments)
     * @return the return value of the method (possibly null)
     * @throws NoSuchMethodException  if no compatible overload is found
     * @throws ReflectiveOperationException if setAccessible or invoke fails
     */
    public static Object callByName(Object obj, String methodName, Object... args) throws ReflectiveOperationException {
        if (obj == null || methodName == null || methodName.isEmpty()) {
            throw new IllegalArgumentException("obj and methodName must be non-null and non-empty");
        }
        boolean staticCall = false;
        Class<?> targetClass;
        if (obj instanceof String className) {
            targetClass = findClass(className);
            if (targetClass == null) {
                throw new ClassNotFoundException("class not found: " + className);
            }
            staticCall = true;
        } else if (obj instanceof Class<?> c) {
            targetClass = c;
            staticCall = true;
        } else {
            targetClass = obj.getClass();
        }
        int nArgs = args == null ? 0 : args.length;
        Object[] invokeArgs = args == null ? new Object[0] : args;
        for (Method m : findMethodsByName(targetClass, methodName)) {
            if (m.getParameterCount() != nArgs) {
                continue;
            }
            if (staticCall && !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            try {
                m.setAccessible(true);
                Object receiver = Modifier.isStatic(m.getModifiers()) ? null : obj;
                return m.invoke(receiver, invokeArgs);
            } catch (IllegalArgumentException e) {
                // argument types don't match this overload, try next
                continue;
            }
        }
        throw new NoSuchMethodException("no compatible overload for " + methodName + " with " + nArgs + " argument(s)");
    }
}
