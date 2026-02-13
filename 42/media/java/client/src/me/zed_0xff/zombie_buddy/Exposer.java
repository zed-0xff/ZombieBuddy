package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import se.krka.kahlua.integration.annotations.LuaMethod;

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
        g_exposed_classes.add(cls);
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

    /**
     * Exposes to Lua only those classes that are annotated with {@link LuaClass}.
     * Use this so that adding {@code @Exposer.LuaClass} to a class is enough to have it exposed
     * when you pass it here (e.g. from your experimental Main or Agent).
     */
    public static void exposeAnnotatedClasses(Class<?>... candidates) {
        for (Class<?> cls : candidates) {
            if (cls != null && cls.isAnnotationPresent(LuaClass.class)) {
                exposeClassToLua(cls);
            }
        }
    }

    /**
     * Scans the given package for classes annotated with {@link LuaClass} and exposes them.
     * Uses ClassGraph (same as Loader). Called from {@link Loader#ApplyPatchesFromPackage}.
     */
    public static void exposeAnnotatedClassesInPackage(String packageName) {
        var classGraph = new ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .acceptPackages(packageName);

        // Use same classpath as Loader so we see classes from current JAR and any g_known_jars
        ArrayList<String> jarPaths = new ArrayList<>();
        File currentJar = Utils.getCurrentJarFile();
        if (currentJar != null && currentJar.exists()) {
            jarPaths.add(currentJar.getAbsolutePath());
        }
        for (File jar : Loader.g_known_jars) {
            String path = jar.getAbsolutePath();
            if (!jarPaths.contains(path)) {
                jarPaths.add(path);
            }
        }
        if (!jarPaths.isEmpty()) {
            classGraph = classGraph.overrideClasspath((Object[]) jarPaths.toArray(new String[0]));
        }

        try (ScanResult scanResult = classGraph.scan()) {
            for (ClassInfo classInfo : scanResult.getClassesWithAnnotation(LuaClass.class.getName())) {
                if (!classInfo.getPackageName().equals(packageName)) {
                    continue; // exact package only, no subpackages
                }
                try {
                    Class<?> cls = classInfo.loadClass();
                    exposeClassToLua(cls);
                } catch (Exception e) {
                    Logger.error("Exposer: failed to load " + classInfo.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Logger.error("Exposer.exposeAnnotatedClassesInPackage(" + packageName + "): " + e.getMessage());
        }
    }

    public static List<Class<?>> getExposedClasses() {
        return new ArrayList<>(g_exposed_classes);
    }

    public static boolean isClassExposed(Class<?> cls) {
        return g_exposed_classes.contains(cls);
    }
}
