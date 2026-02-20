package me.zed_0xff.zombie_buddy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import se.krka.kahlua.integration.annotations.LuaMethod;

import zombie.Lua.LuaManager;

public class Exposer {

    /**
     * Marker annotation for classes that should be exposed to Lua.
     *
     * Usage:
     *   @Exposer.LuaClass
     *   public class MyLuaApi { ... }
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface LuaClass {
    }

    private static final HashSet<Class<?>> g_exposed_classes = new HashSet<>();
    /** Classes that have at least one method with @LuaMethod(global=true); may or may not be in g_exposed_classes. */
    private static final HashSet<Class<?>> g_classesWithGlobalLuaMethod = new HashSet<>();

    /** Returns true if the class has any public method with {@code @LuaMethod(global = true)}. */
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

    // just call me once and the class will be exposed forever (until the game app is closed/restarted ofcourse)
    public static void exposeClassToLua(Class<?> cls) {
        if (g_exposed_classes.contains(cls)) {
            return;
        }
        g_exposed_classes.add(cls);

        var exposer = LuaManager.exposer;
        if (exposer != null) {
            // mods land here because lua context is already created and exposeAll() is already called
            Logger.info("Exposing class to Lua: " + cls.getName());
            exposer.setExposed(cls);
            exposer.exposeLikeJavaRecursively(cls, LuaManager.env);
        }
    }

    /** Resolves the class by name and exposes it to Lua. Returns true if the class was found and exposed, false otherwise. */
    public static boolean exposeClassToLua(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        try {
            Class<?> cls = Class.forName(className);
            exposeClassToLua(cls);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static List<Class<?>> getExposedClasses() {
        return new ArrayList<>(g_exposed_classes);
    }

    public static boolean isClassExposed(Class<?> cls) {
        return g_exposed_classes.contains(cls);
    }

    /**
     * Runs the exposure machinery: exposes all classes in {@link #getExposedClasses()}
     * and global functions from {@link #getClassesWithGlobalLuaMethod()} using the
     * game's LuaManager.exposer. Call this from the Exposer.exposeAll patch OnEnter.
     */
    public static void runExposeAll() {
        var exposer = LuaManager.exposer;
        if (exposer == null) {
            Logger.info("Error! LuaManager.exposer is null!");
            return;
        }
        for (Class<?> cls : getExposedClasses()) {
            Logger.info("Exposing class to Lua: " + cls.getName());
            exposer.setExposed(cls);
        }
        for (Class<?> cls : getClassesWithGlobalLuaMethod()) {
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
    }

    /** Creates a no-arg instance of the class, or null if abstract/interface or no no-arg constructor. */
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
}
