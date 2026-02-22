package me.zed_0xff.zombie_buddy;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

import zombie.Lua.LuaManager;
import se.krka.kahlua.vm.KahluaTable;

/**
 * Experimental API for watching arbitrary Java method calls.
 * Add/Remove/Clear watches; when a watched method is called, logs class, method, and arguments.
 */
public class WatchesAPI {
    public static final int MAX_STRING_LENGTH = 150;

    private static final String KEY_SEP = "#";
    private static final ConcurrentHashMap<String, Boolean> REGISTRY = new ConcurrentHashMap<>();
    private static volatile boolean transformerInstalled;

    /** Advice class used by ByteBuddy; must be public for ByteBuddy to load it. */
    public static class WatchAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin String origin, @Advice.AllArguments Object[] args) {
            onMethodEntered(origin, args);
        }
    }

    public static void onMethodEntered(String origin, Object[] args) {
        String className = parseClassName(origin);
        String methodName = parseMethodName(origin);
        if (className == null || methodName == null) return;
        String key = key(className, methodName);
        if (!REGISTRY.containsKey(key)) return;
        StringBuilder sb = new StringBuilder();
        sb.append("[Watch] ").append(className).append('.').append(methodName);
        if (args != null && args.length > 0) {
            sb.append("(");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(toString(args[i]));
            }
            sb.append(")");
        } else {
            sb.append("()");
        }
        Logger.info(sb.toString());
    }

    private static String toString(Object o) {
        if (o == null) return "null";
        if (o instanceof Object[] arr) return Arrays.toString(arr);
        String s = o.toString();
        if (s.length() > MAX_STRING_LENGTH) s = s.substring(0, MAX_STRING_LENGTH - 3) + "...";
        if (o instanceof String) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return s;
    }

    /** Parses @Origin string like "void pkg.Class.method(args)" to "pkg.Class". */
    private static String parseClassName(String origin) {
        int hash = origin.indexOf('#');
        if (hash >= 0) {
            return origin.substring(0, hash).trim();
        }
        int paren = origin.indexOf('(');
        if (paren < 0) return null;
        String beforeParen = origin.substring(0, paren).trim();
        int dot = beforeParen.lastIndexOf('.');
        if (dot < 0) return null;
        int space = beforeParen.lastIndexOf(' ');
        return beforeParen.substring(space >= 0 ? space + 1 : 0, dot);
    }

    /** Parses @Origin to get method name. Strips ByteBuddy rebase suffix (e.g. $original$0). */
    private static String parseMethodName(String origin) {
        int hash = origin.indexOf('#');
        String raw;
        int paren = origin.indexOf('(');
        if (paren < 0) return null;
        if (hash >= 0) {
            raw = origin.substring(hash + 1, paren).trim();
        } else {
            int dot = origin.substring(0, paren).lastIndexOf('.');
            if (dot < 0) return null;
            raw = origin.substring(dot + 1, paren).trim();
        }
        int dollar = raw.indexOf('$');
        return dollar >= 0 ? raw.substring(0, dollar) : raw;
    }

    private static String key(String className, String methodName) {
        return className + KEY_SEP + methodName;
    }

    private static Set<String> getWatchedMethodsForClass(String className) {
        String prefix = className + KEY_SEP;
        Set<String> methods = new HashSet<>();
        for (String k : REGISTRY.keySet()) {
            if (k.startsWith(prefix)) methods.add(k.substring(prefix.length()));
        }
        return methods;
    }

    private static boolean hasWatchesForClass(String className) {
        return !getWatchedMethodsForClass(className).isEmpty();
    }

    /** Install the ClassFileTransformer if not already installed. */
    private static void ensureTransformer() {
        if (transformerInstalled) return;
        Instrumentation inst = Loader.g_instrumentation;
        if (inst == null) return;
        synchronized (WatchesAPI.class) {
            if (transformerInstalled) return;
            inst.addTransformer(createTransformer(), true);
            transformerInstalled = true;
            Logger.info("Watches transformer installed (experimental)");
        }
    }

    private static ClassFileTransformer createTransformer() {
        return new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (className == null || classfileBuffer == null) return null;
                String dotName = className.replace('/', '.');
                if (!hasWatchesForClass(dotName)) return null;

                Set<String> methods = getWatchedMethodsForClass(dotName);
                if (methods.isEmpty()) return null;

                net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> matcher =
                    net.bytebuddy.matcher.ElementMatchers.none();
                for (String m : methods) {
                    matcher = matcher.or(net.bytebuddy.matcher.ElementMatchers.named(m));
                }

                try {
                    TypeDescription type;
                    String resourceName = className + ".class";
                    ClassFileLocator simpleLocator = ClassFileLocator.Simple.of(resourceName, classfileBuffer);
                    ClassFileLocator locator = loader != null
                        ? new ClassFileLocator.Compound(simpleLocator, ClassFileLocator.ForClassLoader.of(loader))
                        : simpleLocator;
                    if (classBeingRedefined != null) {
                        type = TypeDescription.ForLoadedType.of(classBeingRedefined);
                    } else {
                        TypePool pool = TypePool.Default.of(locator);
                        type = pool.describe(dotName).resolve();
                    }
                    return new ByteBuddy()
                        .rebase(type, locator)
                        .visit(Advice.to(WatchAdvice.class).on(matcher))
                        .make()
                        .getBytes();
                } catch (Exception e) {
                    Logger.error("Watches transform failed for " + dotName + ": " + e.getMessage());
                    return null;
                }
            }
        };
    }

    // --- Lua API ---

    public static void init() {
        ensureTransformer();
        var zb = LuaManager.env.rawget("ZombieBuddy");
        if (!(zb instanceof KahluaTable tbl)) {
            Logger.error("ZombieBuddy table not found");
            return;
        }
        var watches = LuaManager.platform.newTable();
        try {
            LuaManager.exposer.exposeGlobalClassFunction(watches, WatchesAPI.class,
                WatchesAPI.class.getMethod("add", String.class, String.class), "Add");
            LuaManager.exposer.exposeGlobalClassFunction(watches, WatchesAPI.class,
                WatchesAPI.class.getMethod("remove", String.class, String.class), "Remove");
            LuaManager.exposer.exposeGlobalClassFunction(watches, WatchesAPI.class,
                WatchesAPI.class.getMethod("clear"), "Clear");
        } catch (ReflectiveOperationException e) {
            Logger.error("Watches expose failed: " + e.getMessage());
        }
        syncToWatchesTable(watches);
        tbl.rawset("Watches", watches);
    }

    /** Updates the watches table with class_name -> [method1, method2, ...] from REGISTRY. */
    private static void syncToWatchesTable(KahluaTable watches) {
        if (watches == null) return;
        Map<String, Set<String>> byClass = new HashMap<>();
        for (String k : REGISTRY.keySet()) {
            int sep = k.indexOf(KEY_SEP);
            if (sep < 0) continue;
            String className = k.substring(0, sep);
            String methodName = k.substring(sep + KEY_SEP.length());
            byClass.computeIfAbsent(className, x -> new HashSet<>()).add(methodName);
        }
        var it = watches.iterator();
        while (it.advance()) {
            Object key = it.getKey();
            if (!"Add".equals(key) && !"Remove".equals(key) && !"Clear".equals(key)) {
                watches.rawset(key, null);
            }
        }
        for (Map.Entry<String, Set<String>> e : byClass.entrySet()) {
            KahluaTable methods = LuaManager.platform.newTable();
            int idx = 1;
            for (String m : e.getValue()) {
                methods.rawset(idx++, m);
            }
            watches.rawset(e.getKey(), methods);
        }
    }

    private static String normalizeClassName(String className) {
        return className != null ? className.replace('/', '.') : null;
    }

    /**
     * Add a watch for the given class and method. Logs every call with arguments.
     * @return true on success, error string if class or method does not exist
     */
    public static Object add(String className, String methodName) {
        if (className == null || methodName == null) {
            String err = "class name and method name are required";
            Logger.error("Watch Add: " + err);
            return err;
        }
        className = normalizeClassName(className);
        String err = checkClassAndMethodExists(className, methodName);
        if (err != null) {
            Logger.error("Watch Add: " + err);
            return err;
        }
        ensureTransformer();
        String k = key(className, methodName);
        if (REGISTRY.putIfAbsent(k, Boolean.TRUE) == null) {
            Logger.info("Watch added: " + className + "." + methodName);
            retransform(className);
            syncToWatchesTable(getWatchesTable());
        }
        return Boolean.TRUE;
    }

    /** Returns null if class and method exist, else an error message. */
    private static String checkClassAndMethodExists(String className, String methodName) {
        Class<?> cls = Accessor.findClass(className);
        if (cls == null) {
            return "class does not exist: " + className;
        }
        if (Accessor.findMethodsByName(cls, methodName).isEmpty()) {
            return "method does not exist: " + className + "." + methodName;
        }
        return null;
    }

    /**
     * Remove a watch. Logging stops; the instrumentation remains but checks the registry.
     * @return true if the watch was removed, false if it was not in the registry
     */
    public static boolean remove(String className, String methodName) {
        if (className == null || methodName == null) return false;
        className = normalizeClassName(className);
        String k = key(className, methodName);
        if (REGISTRY.remove(k) != null) {
            Logger.info("Watch removed: " + className + "." + methodName);
            syncToWatchesTable(getWatchesTable());
            return true;
        }
        return false;
    }

    /**
     * Remove all watches.
     */
    public static void clear() {
        REGISTRY.clear();
        Logger.info("Watches cleared");
        syncToWatchesTable(getWatchesTable());
    }

    private static KahluaTable getWatchesTable() {
        Object zb = LuaManager.env != null ? LuaManager.env.rawget("ZombieBuddy") : null;
        Object w = (zb instanceof KahluaTable t) ? t.rawget("Watches") : null;
        return w instanceof KahluaTable wt ? wt : null;
    }

    private static void retransform(String className) {
        Instrumentation inst = Loader.g_instrumentation;
        if (inst == null) return;
        try {
            Class<?> cls = Class.forName(className);
            inst.retransformClasses(cls);
        } catch (ClassNotFoundException e) {
            Logger.info("Class not yet loaded, watch will apply on first load: " + className);
        } catch (Exception e) {
            Logger.error("Retransform failed for " + className + ": " + e.getMessage());
        }
    }
}
