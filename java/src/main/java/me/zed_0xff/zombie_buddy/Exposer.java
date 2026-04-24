package me.zed_0xff.zombie_buddy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;

import zombie.Lua.LuaManager;

public class Exposer {

    /**
     * Marker annotation for classes that should be exposed to Lua.
     *
     * Usage:
     *   @Exposer.LuaClass
     *   public class MyApi { ... }  // Accessible as MyApi
     *
     *   @Exposer.LuaClass(name = "ZombieBuddy.Utils")
     *   public class Utils { ... }  // Accessible as ZombieBuddy.Utils
     *
     *   @Exposer.LuaClass(name = "ZB.API.Logger")
     *   public class MyLogger { ... }  // Accessible as ZB.API.Logger (nested tables)
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface LuaClass {
        /** Optional Lua name. Dots create nested tables. Default: class simple name. */
        String name() default "";
    }

    // Class -> Lua name (may contain dots for nesting, empty = use simple name)
    private static final HashMap<Class<?>, String> g_exposed_classes = new HashMap<>();
    private static final HashMap<Class<?>, HashSet<String>> g_exposed_methods = new HashMap<>();
    private static final HashSet<Class<?>> g_classesWithGlobalLuaMethod = new HashSet<>();

    public static boolean hasGlobalLuaMethod(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            LuaMethod ann = m.getAnnotation(LuaMethod.class);
            if (ann != null && ann.global()) {
                return true;
            }
        }
        return false;
    }

    public static void addClassWithGlobalLuaMethod(Class<?> cls) {
        if (cls != null && hasGlobalLuaMethod(cls)) {
            g_classesWithGlobalLuaMethod.add(cls);
        }
    }

    public static List<Class<?>> getClassesWithGlobalLuaMethod() {
        return new ArrayList<>(g_classesWithGlobalLuaMethod);
    }

    public static void exposeClassToLua(Class<?> cls) {
        Logger.warn("exposeClassToLua() method is deprecated, use exposeClass() or @LuaClass annotation instead");
        exposeClass(cls, null);
    }

    public static boolean exposeClassToLua(String className) {
        Logger.warn("exposeClassToLua() method is deprecated, use exposeClass() or @LuaClass annotation instead");
        return exposeClass(className);
    }

    public static void exposeClass(Class<?> cls) {
        exposeClass(cls, null);
    }

    public static void exposeClass(Class<?> cls, String name) {
        if (g_exposed_classes.containsKey(cls)) {
            return;
        }
        g_exposed_classes.put(cls, name != null ? name : "");

        // If exposer is already available, expose immediately (for mods loaded after initial exposure)
        if (LuaManager.exposer != null && LuaManager.env != null) {
            exposeClassNow(cls);
        }
    }

    private static void exposeClassNow(Class<?> cls) {
        var exposer = LuaManager.exposer;
        var env = LuaManager.env;
        String name = g_exposed_classes.get(cls);
        String simpleName = cls.getSimpleName();

        exposer.setExposed(cls);
        exposer.exposeLikeJavaRecursively(cls, env);

        if (name != null && !name.isEmpty()) {
            Object classObj = env.rawget(simpleName);
            if (classObj != null) {
                setNestedValue(env, name, classObj);
                Logger.info("Exposing class to Lua: " + name);
            }
        } else {
            Logger.info("Exposing class to Lua: " + simpleName);
        }
    }

    public static boolean exposeClass(String className) {
        Class<?> cls = Accessor.findClass(className);
        if (cls == null) {
            Logger.warn("exposeClass(\"" + className + "\"): class not found");
            return false;
        }
        exposeClass(cls);
        return true;
    }

    public static void exposeMethod(String className, String methodName) {
        Class<?> cls = Accessor.findClass(className);
        if (cls == null) {
            Logger.warn("exposeMethod(\"" + className + "\", \"" + methodName + "\"): class not found");
            return;
        }
        g_exposed_methods.computeIfAbsent(cls, k -> new HashSet<>()).add(methodName);
    }

    public static List<Class<?>> getExposedClasses() {
        return new ArrayList<>(g_exposed_classes.keySet());
    }

    public static boolean isClassExposed(Class<?> cls) {
        return g_exposed_classes.containsKey(cls);
    }

    public static void afterExposeAll() {
        var exposer = LuaManager.exposer;
        if (exposer == null) {
            Logger.error("LuaManager.exposer is null!");
            return;
        }
        var env = LuaManager.env;
        if (env == null) {
            Logger.error("LuaManager.env is null!");
            return;
        }

        // Expose classes
        for (Class<?> cls : g_exposed_classes.keySet()) {
            exposeClassNow(cls);
        }

        // Expose global functions
        for (Class<?> cls : g_classesWithGlobalLuaMethod) {
            Object instance = newInstance(cls);
            if (instance != null) {
                try {
                    Logger.info("Exposing global functions from class: " + cls.getName());
                    exposer.exposeGlobalFunctions(instance);
                } catch (Exception e) {
                    Logger.error("exposeGlobalFunctions(" + cls.getName() + "): " + e.getMessage());
                }
            }
        }

        // Expose individual methods (for @HiddenFromLua overrides)
        for (var entry : g_exposed_methods.entrySet()) {
            Class<?> cls = entry.getKey();
            for (String methodName : entry.getValue()) {
                Logger.info("Exposing method " + cls.getName() + "." + methodName + "()");
                for (var method : cls.getMethods()) {
                    if (method.getName().equals(methodName)) {
                        try {
                            exposer.exposeMethod(cls, method, method.getName(), env);
                        } catch (Exception e) {
                            Logger.error("exposeMethod(" + cls.getName() + ", " + methodName + "): " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    /**
     * Sets a value in nested tables. Creates intermediate tables as needed.
     * Example: setNestedValue(env, "ZB.API.Logger", obj) creates ZB.API.Logger = obj
     */
    private static void setNestedValue(KahluaTable root, String path, Object value) {
        if (root == null || path == null || value == null) return;

        String[] parts = path.split("\\.");
        KahluaTable current = root;

        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.rawget(parts[i]);
            if (next == null) {
                next = LuaManager.platform.newTable();
                current.rawset(parts[i], next);
            }
            if (next instanceof KahluaTable) {
                current = (KahluaTable) next;
            } else {
                Logger.error("Cannot create nested table at " + parts[i] + " - already exists as non-table");
                return;
            }
        }

        current.rawset(parts[parts.length - 1], value);
    }

    public static Object newInstance(Class<?> cls) {
        try {
            if (Modifier.isAbstract(cls.getModifiers()) || cls.isInterface()) {
                return null;
            }
            return cls.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    public static void exposeAnnotatedClasses(String packageName) {
        try (var scanResult = new io.github.classgraph.ClassGraph()
                .acceptPackages(packageName)
                .enableAnnotationInfo()
                .scan()) {
            exposeAnnotatedClasses(scanResult, null);
        }
    }

    public static void exposeAnnotatedClasses(io.github.classgraph.ScanResult scanResult, String packageName) {
        for (var classInfo : scanResult.getClassesWithAnnotation(LuaClass.class.getName())) {
            if (packageName != null && !classInfo.getPackageName().equals(packageName)) {
                Logger.error("Class " + classInfo.getName() + " is annotated with @LuaClass but is not in the exact package "
                        + packageName + ", skipping exposure");
                continue;
            }
            try {
                Class<?> cls = classInfo.loadClass();
                LuaClass ann = cls.getAnnotation(LuaClass.class);
                String name = (ann != null) ? ann.name() : "";
                exposeClass(cls, name);
            } catch (Exception e) {
                Logger.error("Error exposing Lua class " + classInfo.getName() + ": " + e.getMessage());
            }
        }
    }
}
