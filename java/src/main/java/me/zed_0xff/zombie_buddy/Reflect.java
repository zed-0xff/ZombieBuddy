package me.zed_0xff.zombie_buddy;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

import org.objectweb.asm.Type;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Fluent reflection chain. Entry point is {@link #on(Object)}; strings are resolved as class
 * names. Every step returns a new {@code Reflect}; a failed step produces a null-valued chain
 * that silently propagates — call {@link #isPresent()} or {@link #as} at the end to detect
 * failure.
 */
public class Reflect {

    public enum Flag {
        PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE,
        STATIC, INSTANCE,
        DECLARED // do not ascend in class hierarchy; only declared members of the subject's class
    }

    public static final Flag PUBLIC          = Flag.PUBLIC;
    public static final Flag PROTECTED       = Flag.PROTECTED;
    public static final Flag PACKAGE_PRIVATE = Flag.PACKAGE_PRIVATE;
    public static final Flag PRIVATE         = Flag.PRIVATE;
    public static final Flag STATIC          = Flag.STATIC;
    public static final Flag INSTANCE        = Flag.INSTANCE;
    public static final Flag DECLARED        = Flag.DECLARED;

    private static final Reflect REFLECT_NULL = new Reflect(null);
    private static final Object  MISS         = ClassInfo.MISS;

    private final Object m_obj;

    static void init(Instrumentation inst) {
        // inst.addTransformer(new java.lang.instrument.ClassFileTransformer() {
        //     @Override
        //     public byte[] transform(Module module, ClassLoader loader, String name,
        //             Class<?> cls, java.security.ProtectionDomain domain, byte[] bytes) {
        //         if (name != null) System.out.println("[d] class loaded: " + name);
        //         return null;
        //     }
        // });
    }

    public static class RField {
        private final VarHandle m_handle;
        private final Object    m_receiver;

        private RField(VarHandle handle, Object receiver) {
            m_handle = handle;
            m_receiver = receiver;
        }

        public boolean isPresent() {
            return m_handle != null;
        }

        public VarHandle handle() {
            return m_handle;
        }

        public Object get(Object defaultValue) {
            if (m_handle == null) return defaultValue;

            try {
                return m_receiver == null ? m_handle.get() : m_handle.get(m_receiver);
            } catch (Throwable t) {
                return defaultValue;
            }
        }

        public <T> Optional<T> as(Class<T> type) {
            if (type == null || m_handle == null) return Optional.empty();

            Object value = get(null);
            if (!type.isInstance(value))
                return Optional.empty();

            return Optional.of(type.cast(value));
        }

        public boolean set(Object value) {
            if (m_handle == null) return false;

            try {
                if (m_receiver == null) m_handle.set(value);
                else m_handle.set(m_receiver, value);
            } catch (Throwable t) {
                Logger.once.warn("failed to set field value", m_handle, t);
                return false;
            }

            return true;
        }
    }

    private Reflect(Object obj) {
        this.m_obj = obj;
    }

    /**
     * Entry point for all chains. Strings are resolved as class names (null-valued chain if
     * not found). Everything else is wrapped directly as the subject.
     */
    public static Reflect on(Object obj) {
        if (obj instanceof String s) {
            obj = findClass(s);
        }
        return obj == null ? REFLECT_NULL : new Reflect(obj);
    }

    public interface MHResolver extends Supplier<MethodHandle> {}
    private static final ConcurrentHashMap<MHResolver, MethodHandle> _mh_cache = new ConcurrentHashMap<>();
    public static MethodHandle fastcall(MHResolver resolve) {
        MethodHandle mh = _mh_cache.get(resolve);
        if (mh != null) return mh;

        mh = resolve.get();
        if (mh == null) {
            Logger.once.warn("fastcall resolver returned null:", resolve);
        } else {
            _mh_cache.put(resolve, mh);
        }
        return mh;
    }

    public static Reflect on(String s, Flag... flags) {
        EnumSet<Flag> flagSet = toFlagSet(flags);
        Class<?> cls = findClass(s);
        if (cls != null && matchesMod(cls.getModifiers(), flagSet)) {
            return new Reflect(cls);
        }
        return REFLECT_NULL;
    }

    private static Class<?> findClass(String... classNames) {
        if (classNames == null || classNames.length == 0) {
            return null;
        }
        for (String className : classNames) {
            if (!Utils.isBlank(className)) {
                String normalized = Utils.toCanonicalName(className);
                Class<?> cls = _name2class.computeIfAbsent(normalized, k -> findClassUncached(k));
                if (cls != ClassNotFound.class) {
                    return cls;
                }
            }
        }
        return null;
    }

    private static Class<?> findClassUncached(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError e) {
            return ClassNotFound.class;
        }
    }

    private static final class ClassNotFound {}
    private static final ClassInfo CLASS_NOT_FOUND                       = new ClassInfo(ClassNotFound.class);
    private static final ConcurrentHashMap<Class<?>, ClassInfo> _cache   = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Class<?>> _name2class = new ConcurrentHashMap<>();

    public static void clearCaches() {
        _cache.clear();
        _name2class.clear();
    }

    private static final RField RFIELD_NULL = new RField(null, null);

    public RField field(String... fieldName) {
        if (m_obj == null || Utils.isBlank(fieldName))
            return RFIELD_NULL;

        Class<?> cls = getType();
        if (cls == null) return RFIELD_NULL;

        ClassInfo cinfo = getClassInfo(cls);
        if (cinfo == null) return RFIELD_NULL;
    
        for (String name : fieldName) {
            if (Utils.isBlank(name)) continue;

            Field f = cinfo.fields().get(name);
            if (f == null) continue;

            VarHandle vh = cinfo.getVarHandle(f);
            if (vh != null) {
                Object receiver = Modifier.isStatic(f.getModifiers()) ? null : m_obj;
                return new RField(vh, receiver);
            }
        }
        return RFIELD_NULL;
    }

    public RField staticField(String fieldName) {
        Class<?> cls = getType();
        if (cls == null || Utils.isBlank(fieldName)) return RFIELD_NULL;

        ClassInfo cinfo = getClassInfo(cls);
        if (cinfo == null) return RFIELD_NULL;

        Field f = cinfo.fields().get(fieldName);
        if (f == null || !Modifier.isStatic(f.getModifiers())) return RFIELD_NULL;
    
        return new RField(cinfo.getVarHandle(f), null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeUnchecked(MethodHandle mh, Object... args) {
        try {
            return (T) mh.invokeWithArguments(args);
        } catch (Throwable t) {
            Logger.printStackTrace(t);
            return null;
        }
    }

    /** Tries {@code getInstance()} static method, then falls back to a static {@code instance} field. */
    public Reflect getInstance() {
        Class<?> cls = getType();
        if (cls == null) return REFLECT_NULL;

        Object instance = null;
        MethodHandle mh = getMethodHandle(cls, "getInstance");
        if (mh != null && mh.type().parameterCount() == 0) {
            instance = invokeUnchecked(mh);
        }

        if (instance == null) {
            VarHandle vh = getVarHandle(cls, "instance");
            if (vh != null)
                instance = vh.get();
        }

        return instance != null ? new Reflect(instance) : REFLECT_NULL;
    }

    public List<Method> declaredMethods(Flag... flags) {
        Flag[] flags2 =  new Flag[flags.length + 1];
        System.arraycopy(flags, 0, flags2, 0, flags.length);
        flags2[flags.length] = Flag.DECLARED;
        return methods(flags2);
    }

    /**
     * Returns declared methods of the subject's class hierarchy, excluding synthetic, bridge, and
     * Object-declared methods. Flags further filter by access/static; empty flags = no extra filtering.
     * Access flags (PUBLIC/PROTECTED/PACKAGE_PRIVATE/PRIVATE) are OR-combined within the group.
     * Static flags (STATIC/INSTANCE) are OR-combined within the group.
     * Both groups must match when both are present.
     */
    public List<Method> methods(Flag... flags) {
        Class<?> cls = getType();
        if (cls == null) return Collections.emptyList();

        ClassInfo cinfo = getClassInfo(cls);
        if (cinfo == null) return Collections.emptyList();

        EnumSet<Flag> flagSet = toFlagSet(flags);
        List<Method> out = new ArrayList<>();
        for (Method m : cinfo.methods()) {
            if (flagSet != null) {
                if (!matchesMod(m.getModifiers(), flagSet)) continue;
                if (flagSet.contains(Flag.DECLARED) && m.getDeclaringClass() != cls) continue;
            }
            out.add(m);
        }
        return out;
    }

    /**
     * Returns all declared fields of the subject's class hierarchy (one per name, most-derived
     * wins) matching {@code flags}. Same flag semantics as {@link #methods(Flag...)}.
     */
    public Map<String, Field> fields(Flag... flags) {
        Class<?> cls = getType();
        if (cls == null) return Collections.emptyMap();

        ClassInfo cinfo = getClassInfo(cls);
        if (cinfo == null) return Collections.emptyMap();

        EnumSet<Flag> flagSet = toFlagSet(flags);
        Map<String, Field> out = new LinkedHashMap<>();
        for (Map.Entry<String, Field> e : cinfo.fields().entrySet()) {
            Field f = e.getValue();
            if (f.isSynthetic()) continue;
            if (flagSet != null) {
                if (!matchesMod(f.getModifiers(), flagSet)) continue;
                if (flagSet.contains(Flag.DECLARED) && f.getDeclaringClass() != cls) continue;
            }

            out.put(e.getKey(), f);
        }
        return out;
    }

    private static EnumSet<Flag> toFlagSet(Flag[] flags) {
        if (flags == null || flags.length == 0)
            return null;

        EnumSet<Flag> set = EnumSet.noneOf(Flag.class);
        for (Flag f : flags) {
            if (f != null) set.add(f);
        }
        return set.isEmpty() ? null : set;
    }

    private static boolean matchesMod(int mod, EnumSet<Flag> flags) {
        boolean hasAccess = flags.contains(Flag.PUBLIC) || flags.contains(Flag.PROTECTED) || flags.contains(Flag.PACKAGE_PRIVATE) || flags.contains(Flag.PRIVATE);
        if (hasAccess) {
            boolean ok = (flags.contains(Flag.PUBLIC)          &&  Modifier.isPublic(mod))
                      || (flags.contains(Flag.PROTECTED)       &&  Modifier.isProtected(mod))
                      || (flags.contains(Flag.PRIVATE)         &&  Modifier.isPrivate(mod))
                      || (flags.contains(Flag.PACKAGE_PRIVATE) && !Modifier.isPublic(mod)
                                                               && !Modifier.isProtected(mod)
                                                               && !Modifier.isPrivate(mod));
            if (!ok) return false;
        }
        boolean hasStatic = flags.contains(Flag.STATIC) || flags.contains(Flag.INSTANCE);
        if (hasStatic) {
            boolean ok = (flags.contains(Flag.STATIC)   &&  Modifier.isStatic(mod))
                      || (flags.contains(Flag.INSTANCE) && !Modifier.isStatic(mod));
            if (!ok) return false;
        }
        return true;
    }

    public Class<?> getType() {
        if (m_obj == null) return null;
        return m_obj instanceof Class<?> c ? c : m_obj.getClass();
    }

    private static ClassInfo getClassInfo(Class<?> cls) {
        ClassInfo result = _cache.computeIfAbsent(cls, c -> {
            try {
                return new ClassInfo(c);
            } catch (Exception e) {
                Logger.once.error("Failed to create ClassInfo for %s: %s", c, e);
                return CLASS_NOT_FOUND;
            }
        });
        return result == CLASS_NOT_FOUND ? null : result;
    }

    /** Shorthand for {@code field(name).as(type)}. */
    // public <T> Optional<T> field(String fieldName, Class<T> type) {
    //     return field(fieldName).as(type);
    // }

    public <T> Optional<T> as(Class<T> type) {
        if (m_obj == null || type == null || !type.isInstance(m_obj))
            return Optional.empty();

        return Optional.of(type.cast(m_obj));
    }

    // public Optional<Object> asObject() {
    //     return Optional.ofNullable(m_obj);
    // }

    public Object orElse(Object defaultValue) {
        return m_obj != null ? m_obj : defaultValue;
    }

    public boolean isPresent() {
        return m_obj != null;
    }

    public VarHandle getVarHandle(String fieldDescriptor, String... names) {
        return getVarHandle(descriptorClass(fieldDescriptor), names);
    }

    // call it once and cache the result
    public VarHandle getVarHandle(Class<?> type, String... names) {
        Class<?> cls = getType();
        if (cls == null) return null;
    
        ClassInfo cinfo = getClassInfo(cls);
        if (cinfo == null) return null;

        var varCache = cinfo.varCache;
        for (String fieldName : names) {
            Object v = varCache.get(fieldName);

            if (v == null) {
                try {
                    v = cinfo.lookup.findVarHandle(cls, fieldName, type);
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    try {
                        v = cinfo.lookup.findStaticVarHandle(cls, fieldName, type);
                    } catch (NoSuchFieldException | IllegalAccessException e2) {
                        v = MISS;
                    }
                }

                varCache.put(fieldName, v);
            }

            // v cannot be null here because ConcurrentHashMap doesn't allow null values
            if (v instanceof VarHandle vh) {
                return vh;
            }
        }

        return null;
    }

    // XXX implicit getInstance() call or 'instance' static variable read
    public MethodHandle getInstanceBoundMethodHandle(Class<?> returnType, String... names) {
        MethodHandle mhTarget = getMethodHandle(returnType, names);
        if (mhTarget == null) return null;

        MethodHandle mh_getInstance = getMethodHandle(getType(), "getInstance");
        if (mh_getInstance == null) {
            VarHandle vh_instance = getVarHandle(getType(), "instance");
            if (vh_instance == null) return null;
            mh_getInstance = vh_instance.toMethodHandle(VarHandle.AccessMode.GET);
        }

        return MethodHandles.foldArguments(mhTarget, mh_getInstance);
    }

    // no parameterTypes
    public MethodHandle getMethodHandle(Class<?> returnType, String... names) {
        MethodType mt = MethodType.methodType(returnType);
        return getMethodHandle(mt, names);
    }

    public MethodHandle getMethodHandle(Class<?> returnType, Class<?>[] parameterTypes, String... names) {
        MethodType mt = MethodType.methodType(returnType, parameterTypes);
        return getMethodHandle(mt, names);
    }

    public MethodHandle getMethodHandle(String methodDescriptor, String... names) {
        Class<?> cls = getType();
        if (cls == null) {
            return null;
        }

        try {
            MethodType mt = MethodType.fromMethodDescriptorString(methodDescriptor, cls.getClassLoader());
            return getMethodHandle(mt, names);
        } catch (IllegalArgumentException | TypeNotPresentException e) {
            return null;
        }
    }

    // call it once and cache the result
    public MethodHandle getMethodHandle(MethodType mt, String... names) {
        Class<?> cls = getType();
        if (cls == null) return null;

        ClassInfo cinfo = getClassInfo(cls);
        if (cinfo == null) return null;

        String mtKey = mt.toString();
        var methodCache = cinfo.methodCache;
        for (String methodName : names) {
            String cacheKey = methodName + mtKey;
            Object v = methodCache.get(cacheKey);

            if (v == null) {
                v = lookupMethodHandle(cinfo, cls, methodName, mt);
                if (v == null) {
                    v = MISS;
                }

                methodCache.put(cacheKey, v);
            }

            if (v instanceof MethodHandle mh) return mh;
        }

        return null;
    }

    private static Object lookupMethodHandle(ClassInfo cinfo, Class<?> cls, String methodName, MethodType mt) {
        try {
            return cinfo.lookup.findVirtual(cls, methodName, mt);
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
        }

        try {
            return cinfo.lookup.findStatic(cls, methodName, mt);
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
        }

        Method declared = findDeclaredMethod(cls, methodName, mt);
        if (declared == null) {
            return null;
        }

        ClassInfo declaringInfo = getClassInfo(declared.getDeclaringClass());
        if (declaringInfo == null || declaringInfo.lookup == null) {
            return null;
        }

        try {
            return declaringInfo.lookup.unreflect(declared);
        } catch (IllegalAccessException e) {
            Logger.once.error("unreflect failed for " + declared.getDeclaringClass().getName() + "." + methodName + mt + ": " + e);
            return null;
        }
    }

    private static Method findDeclaredMethod(Class<?> cls, String methodName, MethodType mt) {
        Class<?>[] params = mt.parameterArray();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(methodName, params);
            } catch (NoSuchMethodException ignored) {
            }
        }

        return null;
    }

    private static final Map<Class<?>, Object> DEFAULTS = Map.of(
            boolean.class, false,
            byte.class, (byte) 0,
            short.class, (short) 0,
            int.class, 0,
            long.class, 0L,
            float.class, 0f,
            double.class, 0d,
            char.class, '\0'
            );

    private static Object defaultValue(Class<?> type) {
        return DEFAULTS.get(type);
    }

    // public Object getFieldValue(String fieldName, Object defaultValue) {
    //     if (m_obj == null || Utils.isBlank(fieldName)) return defaultValue;
    //
    // }

    // XXX returns implicit default value for primitives
    @SuppressWarnings("unchecked")
    public <T> T get(String fieldName, Class<T> type) {
        VarHandle vh = getVarHandle(type, fieldName);

        if (vh == null) {
            if (type.isPrimitive()) {
                Logger.warn("using implicit default for " + type.getName() + " " + fieldName);
                return (T) defaultValue(type);
            }
            return null;
        }

        return (T) vh.get(m_obj);
    }

    // safe for primitives
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String fieldName, Class<T> type, T defaultValue) {
        VarHandle vh = getVarHandle(type, fieldName);

        if (vh == null)
            return defaultValue;

        return (T) vh.get(m_obj);
    }

    public Object get(Field field, Object defaultValue) {
        Class<?> cls = getType();
        if (cls == null) return defaultValue;
    
        ClassInfo cinfo = getClassInfo(cls);
        if (cinfo == null) return defaultValue;

        VarHandle vh = cinfo.getVarHandle(field);
        if (vh == null) return defaultValue;

        return vh.get(m_obj);
    }

    public boolean set(String fieldName, Object value) {
        Class<?> cls = getType();
        if (cls == null) return false;

        ClassInfo cinfo = getClassInfo(cls);
        if (cinfo == null) return false;

        Field f = cinfo.fields().get(fieldName);
        if (f == null) return false;

        VarHandle vh = cinfo.getVarHandle(f);
        if (vh == null) return false;

        Object receiver = Modifier.isStatic(f.getModifiers()) ? null : m_obj;

        try {
            if (receiver == null) vh.set(value);
            else vh.set(receiver, value);
            return true;
        } catch (Throwable t) {
            Logger.printStackTrace(t);
            return false;
        }
    }

    public static Class<?> descriptorClass(String descriptor) {
        Type type = Type.getType(descriptor);

        return switch (type.getSort()) {
            case Type.BOOLEAN -> boolean.class;
            case Type.BYTE    -> byte.class;
            case Type.SHORT   -> short.class;
            case Type.CHAR    -> char.class;
            case Type.INT     -> int.class;
            case Type.LONG    -> long.class;
            case Type.FLOAT   -> float.class;
            case Type.DOUBLE  -> double.class;
            case Type.ARRAY, Type.OBJECT -> {
                try {
                    yield Class.forName(type.getClassName());
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("class not found for descriptor: " + descriptor, e);
                }
            }
            default -> throw new IllegalArgumentException("unsupported descriptor: " + descriptor);
        };
    }

    public boolean setAccessible(boolean accessible) {
        Logger.debug("Reflect.setAccessible", m_obj);
        if (m_obj instanceof AccessibleObject ao) {
            try {
                ao.setAccessible(accessible);
                return true;
            } catch (SecurityException e) {
                Logger.printStackTrace(e);
                return false;
            }
        }
        return false;
    }
}
