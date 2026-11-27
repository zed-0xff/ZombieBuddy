package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import me.zed_0xff.zombie_buddy.annotations.Internal;
import me.zed_0xff.zombie_buddy.annotations.Patch;
import me.zed_0xff.zombie_buddy.transformers.JarContext;
import me.zed_0xff.zombie_buddy.transformers.Pipeline;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.pool.TypePool;

/** Runtime preparation for patch classes. Mod patch jars are pre-transformed by {@link Pipeline}; agent-bundled patches are transformed here on first use. */
final class PatchTransformer {
    private static final ThreadLocal<String>      g_transformingClass  = new ThreadLocal<>();
    private static final ThreadLocal<ClassLoader> g_transformingLoader = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, Class<?>> g_transformedClasses = new ConcurrentHashMap<>();

    private interface IMemberHandle {
        String targetClass();
        String[] candidates();
        boolean optional();
    }

    private interface IVarHandle extends IMemberHandle {
        Class<?> fieldType();
    }

    private interface IMethodHandle extends IMemberHandle {
        Class<?> returnType();
        Class<?>[] parameterTypes();
    }

    private record MemberHandleInfo(
            String targetClass,
            String[] candidates,
            boolean optional,
            Class<?> returnType,
            Class<?>[] parameterTypes
    ) implements IMethodHandle {}

    private record VarHandleInfo(
            String targetClass,
            String[] candidates,
            boolean optional,
            Class<?> fieldType
    ) implements IVarHandle {}

    /** The runtime patch class plus its bytecode-level description, so callers never have to resolve descriptor types through reflection. */
    record PreparedPatch(Class<?> type, TypeDescription description) {}

    /**
     * Populates {@link MethodHandle}/{@link VarHandle} fields on {@code patchClass}.
     * Returns null if a non-optional handle cannot be resolved (caller should drop the patch).
     */
    public static PreparedPatch preparePatch(Class<?> patchClass, ClassFileLocator locator) {
        Patch patchAnn0 = patchClass.getAnnotation(Patch.class);
        String targetCls0 = (patchAnn0 != null) ? patchAnn0.className() : "";
        String prevClass = g_transformingClass.get();
        ClassLoader prevLoader = g_transformingLoader.get();
        if (!targetCls0.isEmpty()) {
            g_transformingClass.set(targetCls0);
            g_transformingLoader.set(patchClass.getClassLoader());
        }
        try {
            Class<?> resolved = ensureTransformed(patchClass);
            if (resolved == null) return null;

            TypeDescription description = resolvePatchDescription(resolved, locator);
            Class<?> prepared = preparePatchInner(resolved, description, resolved.getAnnotation(Patch.class));
            if (prepared == null) return null;

            return new PreparedPatch(prepared, description);
        } finally {
            if (prevClass != null) {
                g_transformingClass.set(prevClass);
                g_transformingLoader.set(prevLoader);
            } else {
                g_transformingClass.remove();
                g_transformingLoader.remove();
            }
        }
    }

    private static byte[] readClassFile(Class<?> patchClass) {
        String resourceName = patchClass.getName().replace('.', '/') + ".class";
        try (InputStream in = patchClass.getClassLoader().getResourceAsStream(resourceName)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            Logger.error("Could not read class file for " + patchClass.getName() + ": " + e.getMessage());
            return null;
        }

        byte[] bytes = Loader.getTransformedClassBytes(patchClass.getName());
        if (bytes != null) {
            return bytes;
        }

        Logger.error("Could not read class file for " + patchClass.getName());
        return null;
    }

    private static Class<?> ensureTransformed(Class<?> patchClass) {
        String className = patchClass.getName();
        Class<?> cached = g_transformedClasses.get(className);
        if (cached != null) {
            return cached;
        }

        synchronized (("patch-transform:" + className).intern()) {
            cached = g_transformedClasses.get(className);
            if (cached != null) {
                return cached;
            }

            byte[] classBytes = readClassFile(patchClass);
            if (classBytes == null) {
                return patchClass;
            }

            byte[] transformedBytes;
            try (JarContext jctx = JarContext.forClass(className, classBytes)) {
                transformedBytes = Pipeline.patchLoad().transformClass(className, classBytes, jctx);
            } catch (Exception e) {
                Logger.error("Failed to transform patch class " + className + ": " + e.getMessage());
                if (Loader.g_verbosity > 0) {
                    Logger.printStackTrace(e);
                }

                return null;
            }

            if (Arrays.equals(classBytes, transformedBytes)) {
                g_transformedClasses.put(className, patchClass);
                return patchClass;
            }

            try {
                if (Loader.g_instrumentation != null) {
                    Loader.g_instrumentation.redefineClasses(new ClassDefinition(patchClass, transformedBytes));
                }
            } catch (Exception e) {
                Logger.debug("Could not redefine patch class " + className + " (non-fatal): " + e.getMessage());
            }

            try {
                ClassLoader freshLoader = new ByteArrayClassLoader.ChildFirst(
                        patchClass.getClassLoader(),
                        Map.of(className, transformedBytes),
                        ByteArrayClassLoader.PersistenceHandler.MANIFEST);
                Class<?> freshClass = freshLoader.loadClass(className);
                g_transformedClasses.put(className, freshClass);
                return freshClass;
            } catch (Exception e) {
                Logger.error("Failed to load transformed patch class " + className + ": " + e.getMessage());
                if (Loader.g_verbosity > 0) {
                    Logger.printStackTrace(e);
                }

                return null;
            }
        }
    }

    private static TypeDescription resolvePatchDescription(Class<?> patchClass, ClassFileLocator locator) {
        try {
            return TypePool.Default.of(locator).describe(patchClass.getName()).resolve();
        } catch (RuntimeException e) {
            Logger.debug("Could not resolve patch type " + patchClass.getName()
                    + " from class file locator, falling back to loaded type: " + e.getMessage());
            return TypeDescription.ForLoadedType.of(patchClass);
        }
    }

    private static Class<?> preparePatchInner(Class<?> patchClass, TypeDescription patchTd, Patch patchAnn) {
        String defaultTargetCls = (patchAnn != null) ? patchAnn.className() : "";
        Map<String, IMemberHandle> memberHandles = new HashMap<>();
        Map<String, IMemberHandle> paramHandleInfos = new HashMap<>();
        Map<String, String> nameMap = null;

        for (Field f : patchClass.getDeclaredFields()) {
            Patch.NameMap nm = f.getAnnotation(Patch.NameMap.class);
            if (nm != null) {
                if (nameMap == null) {
                    nameMap = buildNameMap(patchTd);
                }
                if (!setNameMapField(patchClass, f, nameMap)) {
                    return null;
                }
                continue;
            }

            Patch.MethodHandle mh = f.getAnnotation(Patch.MethodHandle.class);
            if (mh != null) {
                memberHandles.put(f.getName(), methodHandleInfo(mh, defaultTargetCls, f.getName()));
                continue;
            }

            Patch.VarHandle vh = f.getAnnotation(Patch.VarHandle.class);
            if (vh != null) {
                memberHandles.put(f.getName(), varHandleInfo(vh, defaultTargetCls, f.getName()));
            }
        }

        for (MethodDescription method : patchTd.getDeclaredMethods()) {
            int pi = 0;
            for (ParameterDescription param : method.getParameters()) {
                AnnotationDescription.Loadable<Patch.MethodHandle> mh =
                        param.getDeclaredAnnotations().ofType(Patch.MethodHandle.class);
                if (mh != null) {
                    String storeKey = patchClass.getName() + "#" + method.getName() + "#" + pi;
                    String pName = param.isNamed() ? param.getName() : null;
                    paramHandleInfos.put(storeKey, methodHandleInfo(mh.load(), defaultTargetCls, pName));
                } else {
                    AnnotationDescription.Loadable<Patch.VarHandle> vh =
                            param.getDeclaredAnnotations().ofType(Patch.VarHandle.class);
                    if (vh != null) {
                        String storeKey = patchClass.getName() + "#" + method.getName() + "#" + pi;
                        String pName = param.isNamed() ? param.getName() : null;
                        paramHandleInfos.put(storeKey, varHandleInfo(vh.load(), defaultTargetCls, pName));
                    }
                }
                pi++;
            }
        }

        if (memberHandles.isEmpty() && paramHandleInfos.isEmpty()) {
            return patchClass;
        }

        if (!memberHandles.isEmpty() && !populateMemberHandles(patchClass, patchClass, memberHandles)) {
            return null;
        }

        if (!paramHandleInfos.isEmpty() && !populateParamHandles(paramHandleInfos)) {
            return null;
        }

        return patchClass;
    }

    private static Map<String, String> buildNameMap(TypeDescription patchTd) {
        Map<String, String> out = new LinkedHashMap<>();
        for (MethodDescription method : patchTd.getDeclaredMethods()) {
            for (ParameterDescription param : method.getParameters()) {
                AnnotationDescription.Loadable<Advice.FieldValue> fv =
                        param.getDeclaredAnnotations().ofType(Advice.FieldValue.class);
                if (fv == null) {
                    continue;
                }

                Advice.FieldValue fieldValue = fv.load();
                if (Utils.isBlank(fieldValue.value())) {
                    continue;
                }

                AnnotationDescription.Loadable<Patch.Field> field =
                        param.getDeclaredAnnotations().ofType(Patch.Field.class);
                String logicalName = field != null && !Utils.isBlank(field.load().logicalName())
                        ? field.load().logicalName()
                        : param.isNamed() ? param.getName() : fieldValue.value();
                out.put(logicalName, fieldValue.value());
            }
        }

        return Map.copyOf(out);
    }

    private static boolean setNameMapField(Class<?> patchClass, Field field, Map<String, String> nameMap) {
        if (!Map.class.isAssignableFrom(field.getType())) {
            Logger.error("@Patch.NameMap field must be assignable from Map: " + patchClass.getName() + "#" + field.getName());
            return false;
        }

        try {
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Map<?, ?> existing) {
                @SuppressWarnings("unchecked")
                Map<String, String> target = (Map<String, String>) existing;
                target.clear();
                target.putAll(nameMap);
            } else {
                field.set(null, new LinkedHashMap<>(nameMap));
            }
            return true;
        } catch (Exception e) {
            Logger.error("Failed to set @Patch.NameMap field " + patchClass.getName() + "#" + field.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private static IMethodHandle methodHandleInfo(Patch.MethodHandle mh, String defaultTargetCls, String fallbackName) {
        String tc = mh.owner() != void.class ? mh.owner().getName()
                  : !mh.className().isEmpty() ? mh.className()
                  : defaultTargetCls;
        String[] cs = mh.name().length > 0 ? mh.name()
                  : fallbackName != null ? new String[]{fallbackName}
                  : new String[]{};
        return new MemberHandleInfo(tc, cs, mh.optional(), mh.returnType(), mh.paramTypes());
    }

    private static IVarHandle varHandleInfo(Patch.VarHandle vh, String defaultTargetCls, String fallbackName) {
        String tc = vh.owner() != void.class ? vh.owner().getName()
                  : !vh.className().isEmpty() ? vh.className()
                  : defaultTargetCls;
        String[] cs = vh.name().length > 0 ? vh.name()
                  : fallbackName != null ? new String[]{fallbackName}
                  : new String[]{};
        return new VarHandleInfo(tc, cs, vh.optional(), vh.type());
    }

    private static boolean populateHandles(Map<String, IMemberHandle> infos, BiFunction<String, IMemberHandle, String> nullMsg, BiFunction<String, Object, Boolean> setter) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        boolean allOk = true;
        for (var entry : infos.entrySet()) {
            String key = entry.getKey();
            IMemberHandle info = entry.getValue();
            Object handle = resolveMemberHandle(info, lookup);
            if (handle == null) {
                if (!info.optional()) {
                    Logger.error(nullMsg.apply(key, info));
                    allOk = false;
                }
                continue;
            }
            if (!setter.apply(key, handle)) allOk = false;
        }

        return allOk;
    }

    private static boolean populateMemberHandles(Class<?> freshClass, Class<?> originalClass, Map<String, IMemberHandle> infos) {
        return populateHandles(infos,
            (k, i) -> "MemberHandle not resolved: " + k + " in " + i.targetClass(),
            (k, h) -> { setStaticField(freshClass, k, h); if (originalClass != freshClass) setStaticField(originalClass, k, h); return true; });
    }

    private static boolean populateParamHandles(Map<String, IMemberHandle> infos) {
        return populateHandles(infos,
            (k, i) -> "Parameter MemberHandle not resolved: " + k,
            (k, h) -> {
                if (h instanceof MethodHandle mh) { Internal.HandleStore.putMethod(k, mh); return true; }
                if (h instanceof VarHandle    vh) { Internal.HandleStore.putVar(k, vh); return true; }
                Logger.error("Parameter MemberHandle resolved to unexpected type " + h.getClass() + " for key " + k);
                return false;
            });
    }

    private static void setStaticField(Class<?> cls, String fieldName, Object value) {
        try {
            Field f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Exception e) {
            Logger.warn("Failed to set field " + fieldName + " on " + cls.getName() + ": " + e.getMessage());
        }
    }

    private static Object resolveMemberHandle(IMemberHandle info, MethodHandles.Lookup lookup) {
        if (info.targetClass().isEmpty()) {
            Logger.warn("MemberHandle has no target class");
            return null;
        }

        Class<?> targetClass = findClass(info.targetClass());
        if (targetClass == null) {
            Logger.warn("MemberHandle target class " + info.targetClass() + " not found");
            return null;
        }

        if (info instanceof IVarHandle ivh) {
            return Reflect.on(targetClass).getVarHandle(ivh.fieldType(), ivh.candidates());
        }

        if (info instanceof IMethodHandle imh) {
            boolean hasSignature = imh.returnType() != void.class || imh.parameterTypes().length > 0;
            for (String name : info.candidates()) {
                Method m = findMemberHandleMethod(targetClass, name, hasSignature ? imh.returnType() : null, imh.parameterTypes());
                if (m != null) {
                    try {
                        m.setAccessible(true);
                        return lookup.unreflect(m);
                    } catch (Exception e) {
                        Logger.warn("MemberHandle unreflect failed for " + name + ": " + e.getMessage());
                    }
                }
            }
        }

        return null;
    }

    private static Class<?> findClass(String name) {
        String busy = g_transformingClass.get();
        if (busy != null && busy.equals(name)) {
            return findAlreadyLoadedClass(g_transformingLoader.get(), name);
        }

        return Reflect.on(name).getType();
    }

    private static MethodHandle mh_findLoadedClass = null;

    private static Class<?> findAlreadyLoadedClass(ClassLoader cl, String name) {
        if (mh_findLoadedClass == null) {
            mh_findLoadedClass = Reflect.on(ClassLoader.class).getMethodHandle(Class.class, new Class<?>[]{String.class}, "findLoadedClass");
            if (mh_findLoadedClass == null) {
                Logger.error("ClassLoader.findLoadedClass method handle not found");
                return null;
            }
        }
        if (cl == null) return null;

        try {
            return (Class<?>) mh_findLoadedClass.invokeExact(cl, name);
        } catch (Throwable t) {
            Logger.error("findLoadedClass failed for " + name + ": " + t.getMessage());
            return null;
        }
    }

    private static Method findMemberHandleMethod(Class<?> cls, String name, Class<?> returnType, Class<?>[] params) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (returnType != null && !m.getReturnType().equals(returnType)) continue;
                if (!Arrays.equals(m.getParameterTypes(), params)) continue;
                return m;
            }
        }

        return null;
    }

    private PatchTransformer() {}
}
