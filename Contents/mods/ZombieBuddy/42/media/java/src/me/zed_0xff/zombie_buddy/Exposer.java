package me.zed_0xff.zombie_buddy;

import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

public class Exposer {
    private static final HashSet<Class<?>> g_exposed_classes = new HashSet<>();

    public static void exposeClassToLua(Class<?> cls) {
        g_exposed_classes.add(cls);
    }

    public static void exposeClassesToLua(Class<?>... classes) {
        for (Class<?> cls : classes) {
            exposeClassToLua(cls);
        }
    }

    public static List<Class<?>> getExposedClasses() {
        return new ArrayList<>(g_exposed_classes);
    }

    public static boolean isClassExposed(Class<?> cls) {
        return g_exposed_classes.contains(cls);
    }
}
