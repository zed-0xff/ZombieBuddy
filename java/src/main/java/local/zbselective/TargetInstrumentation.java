package local.zbselective;

import java.lang.annotation.Annotation;
import java.lang.instrument.*;
import java.lang.reflect.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/** Each ByteBuddy builder keeps its own exact targets, including wildcard semantics. */
public final class TargetInstrumentation {
    private static final Map<String, Targets> targets = new ConcurrentHashMap<>();
    private static final LongAdder skipped = new LongAdder(), forwarded = new LongAdder();
    private static final ThreadLocal<Boolean> transforming = new ThreadLocal<>();

    public static void remember(List<Class<?>> patches, String pkg) {
        Set<String> names = new HashSet<>();
        for (Class<?> patch : patches) {
            for (Annotation annotation : patch.getAnnotations()) {
                if (!annotation.annotationType().getName().equals("me.zed_0xff.zombie_buddy.Patch")) continue;
                try {
                    String name = (String) annotation.annotationType().getMethod("className").invoke(annotation);
                    if (!name.startsWith("me.zed_0xff.")) names.add(name);
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }
        }
        targets.put(pkg, new Targets(names));
        RuntimeState.log("targets for " + pkg + ": " + names);
    }

    public static Instrumentation wrap(Instrumentation delegate, String pkg) {
        Targets selection = targets.get(pkg);
        if (selection == null) throw new IllegalStateException("No patch targets collected for " + pkg);
        // Resolve ThreadLocal machinery before installing a transformer that uses it.
        transforming.set(Boolean.FALSE);
        Map<ClassFileTransformer, ClassFileTransformer> wrappers = Collections.synchronizedMap(new IdentityHashMap<>());
        return (Instrumentation) Proxy.newProxyInstance(TargetInstrumentation.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class}, (proxy, method, args) -> {
                if (method.getName().equals("addTransformer")) {
                    ClassFileTransformer original = (ClassFileTransformer) args[0];
                    ClassFileTransformer filtered = filter(original, selection);
                    wrappers.put(original, filtered);
                    args = args.clone(); args[0] = filtered;
                } else if (method.getName().equals("removeTransformer")) {
                    ClassFileTransformer original = (ClassFileTransformer) args[0];
                    ClassFileTransformer filtered = wrappers.remove(original);
                    if (filtered != null) { args = args.clone(); args[0] = filtered; }
                }
                try {
                    Object result = method.invoke(delegate, args);
                    if (method.getName().equals("getAllLoadedClasses") || method.getName().equals("getInitiatedClasses")) {
                        List<Class<?>> relevant = new ArrayList<>();
                        for (Class<?> type : (Class<?>[]) result)
                            if (selection.matches(type.getName().replace('.', '/'))) relevant.add(type);
                        return relevant.toArray(new Class<?>[0]);
                    }
                    return result;
                } catch (InvocationTargetException e) { throw e.getCause(); }
            });
    }

    static ClassFileTransformer filter(ClassFileTransformer original, Targets selection) {
        return new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> old, ProtectionDomain domain, byte[] bytes)
                    throws IllegalClassFormatException {
                if (Boolean.TRUE.equals(transforming.get())) return null;
                transforming.set(Boolean.TRUE);
                try {
                    if (!selection.matches(name)) { skipped.increment(); return null; }
                    forwarded.increment();
                    return original.transform(loader, name, old, domain, bytes);
                } finally { transforming.set(Boolean.FALSE); }
            }
            @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> old,
                    ProtectionDomain domain, byte[] bytes) throws IllegalClassFormatException {
                if (Boolean.TRUE.equals(transforming.get())) return null;
                transforming.set(Boolean.TRUE);
                try {
                    if (!selection.matches(name)) { skipped.increment(); return null; }
                    forwarded.increment();
                    return original.transform(module, loader, name, old, domain, bytes);
                } finally { transforming.set(Boolean.FALSE); }
            }
        };
    }

    static final class Targets {
        private final Set<String> exact = new HashSet<>();
        private final List<Rule> patterns = new ArrayList<>();
        private record Rule(int kind, String part, Pattern regex) {
            boolean matches(String name) {
                return switch (kind) {
                    case 0 -> true;
                    case 1 -> name.contains(part);
                    case 2 -> name.endsWith(part);
                    case 3 -> name.startsWith(part);
                    default -> regex.matcher(name).matches();
                };
            }
        }
        Targets(Set<String> names) {
            for (String name : names) {
                if (name.equals("*")) patterns.add(new Rule(0, "", null));
                else if (name.startsWith("*") && name.endsWith("*")) {
                    String part = name.substring(1, name.length() - 1); patterns.add(new Rule(1, part, null));
                } else if (name.startsWith("*")) {
                    String part = name.substring(1); patterns.add(new Rule(2, part, null));
                } else if (name.endsWith("*")) {
                    String part = name.substring(0, name.length() - 1); patterns.add(new Rule(3, part, null));
                } else if (name.startsWith("/") && name.endsWith("/")) {
                    Pattern pattern = Pattern.compile(name.substring(1, name.length() - 1));
                    pattern.matcher("java.lang.String").matches(); // warm Matcher before the load callback
                    patterns.add(new Rule(4, "", pattern));
                } else exact.add(name.replace('.', '/'));
            }
        }
        boolean matches(String internalName) {
            // Null names cannot be rejected safely (some VM generated/anonymous classes).
            if (internalName == null || exact.contains(internalName)) return true;
            if (patterns.isEmpty()) return false;
            String dotted = internalName.replace('/', '.');
            // No streams/lambdas: their lazy support-class loading can cause ClassCircularityError here.
            for (int i = 0; i < patterns.size(); i++) if (patterns.get(i).matches(dotted)) return true;
            return false;
        }
    }

    public static String stats() { return "ignored=" + skipped.sum() + ", forwarded=" + forwarded.sum(); }
}
