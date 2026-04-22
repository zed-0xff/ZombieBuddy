package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.classgraph.*;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.SuperMethodCall;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Bytecode patching engine using ByteBuddy.
 * Handles scanning for @Patch classes and applying them via Advice or MethodDelegation.
 */
public final class PatchEngine {

    private PatchEngine() {}

    private static record PatchTarget(String className, String methodName) {}

    private static final Set<String> ADVICE_ANNOTATION_PATTERNS = Set.of("Advice$OnMethodEnter", "Advice$OnMethodExit");
    private static final Set<String> RUNTIME_TYPE_PATTERNS = Set.of("RuntimeType");
    private static final Set<String> METHOD_DELEGATION_SPECIAL_ANNOTATIONS = Set.of("This", "SuperCall", "SuperMethod");
    private static final Set<String> ALL_ARGUMENTS_PATTERNS = Set.of("Advice$AllArguments");

    private static boolean hasAnnotation(Method method, Set<String> annotationPatterns) {
        for (java.lang.annotation.Annotation ann : method.getAnnotations()) {
            String annType = ann.annotationType().getName();
            for (String pattern : annotationPatterns) {
                if (annType.contains(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Method findMethodWithAnnotation(Class<?> clazz, Set<String> annotationPatterns) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (hasAnnotation(method, annotationPatterns)) {
                return method;
            }
        }
        return null;
    }

    private static boolean hasParameterAnnotation(Method method, Set<String> annotationPatterns) {
        java.lang.annotation.Annotation[][] paramAnns = method.getParameterAnnotations();
        for (java.lang.annotation.Annotation[] paramAnn : paramAnns) {
            for (java.lang.annotation.Annotation ann : paramAnn) {
                String annType = ann.annotationType().getName();
                for (String pattern : annotationPatterns) {
                    if (annType.contains(pattern)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<Class<?>> inferSignatureFromMethod(Method patchMethod, Set<String> specialAnnotationPatterns) {
        java.lang.annotation.Annotation[][] paramAnns = patchMethod.getParameterAnnotations();
        Class<?>[] paramTypes = patchMethod.getParameterTypes();
        Map<Integer, Class<?>> argumentMap = new HashMap<>();

        for (int i = 0; i < paramAnns.length; i++) {
            boolean isSpecial = false;
            int argumentIndex = -1;

            for (java.lang.annotation.Annotation ann : paramAnns[i]) {
                String annType = ann.annotationType().getName();
                for (String pattern : specialAnnotationPatterns) {
                    if (annType.contains(pattern)) {
                        isSpecial = true;
                        break;
                    }
                }
                if (isSpecial) break;

                if (annType.contains("Argument")) {
                    try {
                        java.lang.reflect.Method valueMethod = ann.annotationType().getMethod("value");
                        argumentIndex = (Integer) valueMethod.invoke(ann);
                    } catch (Exception e) {
                        argumentIndex = i;
                    }
                    Class<?> paramType = paramTypes[i];
                    argumentMap.put(argumentIndex, paramType.isArray() ? paramType.getComponentType() : paramType);
                    break;
                }
            }

            if (isSpecial) continue;
            if (argumentIndex == -1) {
                argumentMap.put(i, paramTypes[i]);
            }
        }

        if (argumentMap.isEmpty()) return null;

        int maxIndex = argumentMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        boolean hasCompleteSequence = true;
        for (int idx = 0; idx <= maxIndex; idx++) {
            if (!argumentMap.containsKey(idx)) {
                hasCompleteSequence = false;
                break;
            }
        }

        if (hasCompleteSequence) {
            List<Class<?>> sig = new ArrayList<>();
            for (int idx = 0; idx <= maxIndex; idx++) {
                sig.add(argumentMap.get(idx));
            }
            return sig;
        }
        return null;
    }

    /**
     * Apply patches from a package using ByteBuddy.
     * @param packageName the package containing @Patch classes
     * @param modLoader the class loader for the mod (null for system loader)
     * @param isPreMain reserved for future use
     */
    public static void applyPatches(String packageName, ClassLoader modLoader, boolean isPreMain) {
        List<Class<?>> patches = collectPatches(packageName, modLoader);
        if (patches.isEmpty()) {
            Logger.info("no patches in package " + packageName);
            return;
        }

        Map<PatchTarget, List<Class<?>>> advicePatches = new HashMap<>();
        Map<PatchTarget, Class<?>> delegationPatches = new HashMap<>();
        Set<String> classesToWarmUp = new HashSet<>();

        for (Class<?> patch : patches) {
            Patch ann = patch.getAnnotation(Patch.class);
            if (ann == null) continue;

            if (ann.className().equals("zombie.Lua.LuaManager$Exposer") && ann.methodName().equals("exposeAll") && !ann.IKnowWhatIAmDoing()) {
                Logger.error("XXX");
                Logger.error("XXX don't patch Exposer.exposeAll, use @Exposer.LuaClass annotation!");
                Logger.error("XXX");
                continue;
            }

            PatchTarget target = new PatchTarget(ann.className(), ann.methodName());
            if (ann.warmUp()) {
                classesToWarmUp.add(ann.className());
            }

            if (ann.isAdvice()) {
                advicePatches.computeIfAbsent(target, k -> new ArrayList<>()).add(patch);
            } else {
                if (delegationPatches.containsKey(target)) {
                    Logger.info("WARNING: multiple MethodDelegation patches for " +
                        ann.className() + "." + ann.methodName() + " - only last one will apply!");
                }
                delegationPatches.put(target, patch);
            }
        }

        Set<String> targetClasses = new HashSet<>();
        for (PatchTarget t : advicePatches.keySet()) targetClasses.add(t.className());
        for (PatchTarget t : delegationPatches.keySet()) targetClasses.add(t.className());

        Set<String> loadedClasses = new HashSet<>();
        for (Class<?> c : Loader.g_instrumentation.getAllLoadedClasses()) {
            String className = c.getName();
            if (targetClasses.contains(className)) {
                loadedClasses.add(className);
            }
        }
        if (!loadedClasses.isEmpty() && Loader.g_verbosity > 0) {
            Logger.info("Already loaded classes to retransform: " + loadedClasses);
        }

        boolean hasAdviceOnLoadedClasses = false;
        for (var entry : advicePatches.entrySet()) {
            if (loadedClasses.contains(entry.getKey().className())) {
                hasAdviceOnLoadedClasses = true;
                break;
            }
        }

        var bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly();
        if (Loader.g_verbosity > 0) {
            if (Loader.g_verbosity <= 2) {
                bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut().withTransformationsOnly();
            } else {
                bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut();
            }
        }

        AgentBuilder builder = new AgentBuilder.Default();
        if (hasAdviceOnLoadedClasses) {
            if (Loader.g_verbosity > 0) {
                Logger.info("Disabling class format changes for Advice patches on already-loaded classes");
            }
            builder = builder.disableClassFormatChanges();
        }

        builder = builder
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(bbLogger)
            .with(new AgentBuilder.Listener.Adapter() {
                @Override
                public void onTransformation(net.bytebuddy.description.type.TypeDescription td,
                                             ClassLoader cl,
                                             net.bytebuddy.utility.JavaModule jm,
                                             boolean loaded,
                                             net.bytebuddy.dynamic.DynamicType dt) {
                    Logger.info("Transformed: " + td.getName() + (loaded ? " (retransformed)" : " (new load)"));
                }

                @Override
                public void onError(String typeName, ClassLoader cl, net.bytebuddy.utility.JavaModule jm,
                                   boolean loaded, Throwable throwable) {
                    Logger.error("ERROR transforming " + typeName + ": " + throwable.getMessage());
                    throwable.printStackTrace();
                }

                @Override
                public void onIgnored(net.bytebuddy.description.type.TypeDescription td,
                                     ClassLoader cl,
                                     net.bytebuddy.utility.JavaModule jm,
                                     boolean loaded) {
                    String className = td.getName();
                    if (targetClasses.contains(className)) {
                        Logger.error("WARNING: Target class IGNORED: " + className + " (loaded=" + loaded + ")");
                    }
                }
            });

        for (String className : targetClasses) {
            Map<String, List<Class<?>>> methodAdvices = new HashMap<>();
            Map<String, Class<?>> methodDelegations = new HashMap<>();

            for (var entry : advicePatches.entrySet()) {
                if (entry.getKey().className().equals(className)) {
                    methodAdvices.put(entry.getKey().methodName(), entry.getValue());
                }
            }
            for (var entry : delegationPatches.entrySet()) {
                if (entry.getKey().className().equals(className)) {
                    methodDelegations.put(entry.getKey().methodName(), entry.getValue());
                }
            }

            builder = builder
                .type(SyntaxSugar.typeMatcher(className))
                .transform((bl, td, cl, mo, pd) -> {
                    var result = bl;

                    for (var entry : methodAdvices.entrySet()) {
                        String methodName = entry.getKey();
                        List<Class<?>> advices = entry.getValue();

                        Logger.info("patching " + className + "." + methodName + " with " + advices.size() + " advice(s)");

                        var methods = td.getDeclaredMethods().filter(SyntaxSugar.methodMatcher(methodName));
                        if (methods.isEmpty()) {
                            Logger.error("WARNING: Method " + methodName + " not found in " + td.getName());
                        }

                        for (Class<?> adviceClass : advices) {
                            Patch patchAnn = adviceClass.getAnnotation(Patch.class);
                            boolean strictMatch = patchAnn != null && patchAnn.strictMatch();

                            Class<?> transformedClass;
                            try {
                                transformedClass = PatchTransformer.transformPatchClass(adviceClass, Loader.g_instrumentation, Loader.g_verbosity, false);
                                if (transformedClass == null) {
                                    Logger.error("ERROR: PatchTransformer returned null for " + adviceClass.getName());
                                    continue;
                                }
                            } catch (Exception e) {
                                Logger.error("ERROR: Failed to transform patch class " + adviceClass.getName() + ": " + e.getMessage());
                                e.printStackTrace();
                                continue;
                            }

                            var methodMatcher = SyntaxSugar.methodMatcher(methodName);

                            boolean hasAllArguments = false;
                            boolean hasNoParamMethod = false;
                            List<Class<?>> inferredTypes = null;
                            Integer minParameterCount = null;
                            List<Map<Integer, Class<?>>> allAdviceMaps = new ArrayList<>();
                            List<Boolean> allAdviceExactMatch = new ArrayList<>();
                            Set<List<Class<?>>> allInferredSignatures = new HashSet<>();

                            for (Method adviceMethod : adviceClass.getDeclaredMethods()) {
                                if (!hasAnnotation(adviceMethod, ADVICE_ANNOTATION_PATTERNS)) continue;

                                java.lang.annotation.Annotation[][] paramAnns = adviceMethod.getParameterAnnotations();

                                if (hasParameterAnnotation(adviceMethod, ALL_ARGUMENTS_PATTERNS)) {
                                    hasAllArguments = true;
                                }

                                if (adviceMethod.getParameterCount() == 0 && !hasAllArguments) {
                                    hasNoParamMethod = true;
                                }

                                if (!hasAllArguments) {
                                    Class<?>[] paramTypes = adviceMethod.getParameterTypes();
                                    Map<Integer, Class<?>> argumentMap = new HashMap<>();
                                    boolean hasAnyArguments = false;
                                    boolean allParamsAreSpecial = paramTypes.length > 0;
                                    int regularParamCount = 0;

                                    for (int i = 0; i < paramAnns.length; i++) {
                                        boolean isArgument = false;
                                        boolean isLocal = false;
                                        boolean isThis = false;
                                        int argumentIndex = -1;

                                        for (java.lang.annotation.Annotation ann : paramAnns[i]) {
                                            String annType = ann.annotationType().getName();
                                            if (annType.contains("Advice$Argument")) {
                                                isArgument = true;
                                                hasAnyArguments = true;
                                                allParamsAreSpecial = false;
                                                try {
                                                    java.lang.reflect.Method valueMethod = ann.annotationType().getMethod("value");
                                                    argumentIndex = (Integer) valueMethod.invoke(ann);
                                                } catch (Exception e) {
                                                    argumentIndex = i;
                                                }
                                                Class<?> paramType = paramTypes[i];
                                                Class<?> typeToStore = paramType.isArray() ? paramType.getComponentType() : paramType;
                                                argumentMap.put(argumentIndex, typeToStore);
                                                break;
                                            } else if (annType.contains("Advice$Local")) {
                                                isLocal = true;
                                                break;
                                            } else if (annType.contains("Advice$This")) {
                                                isThis = true;
                                                break;
                                            }
                                        }

                                        if (isLocal || isThis) continue;

                                        if (!isArgument) {
                                            boolean isSpecial = false;
                                            for (java.lang.annotation.Annotation ann : paramAnns[i]) {
                                                String annType = ann.annotationType().getName();
                                                if (annType.contains("Advice$Return") || annType.contains("Advice$AllArguments") || annType.contains("Advice$Local") || annType.contains("Advice$This")) {
                                                    isSpecial = true;
                                                    break;
                                                }
                                            }
                                            if (!isSpecial) {
                                                allParamsAreSpecial = false;
                                                argumentMap.put(i, paramTypes[i]);
                                                regularParamCount++;
                                            }
                                        }
                                    }

                                    if (!argumentMap.isEmpty()) {
                                        allAdviceMaps.add(argumentMap);
                                        allAdviceExactMatch.add(!hasAnyArguments);
                                    }

                                    if (allParamsAreSpecial && paramTypes.length > 0) {
                                        hasNoParamMethod = true;
                                    }

                                    if (hasAnyArguments && !argumentMap.isEmpty()) {
                                        int maxIndex = argumentMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                                        boolean hasCompleteSequence = true;
                                        for (int idx = 0; idx <= maxIndex; idx++) {
                                            if (!argumentMap.containsKey(idx)) {
                                                hasCompleteSequence = false;
                                                break;
                                            }
                                        }
                                        int requiredParamCount = maxIndex + 1;
                                        if (minParameterCount == null || requiredParamCount > minParameterCount) {
                                            minParameterCount = requiredParamCount;
                                        }
                                        if (Loader.g_verbosity > 1) {
                                            if (hasCompleteSequence) {
                                                Logger.info("DEBUG: Complete @Argument sequence (0 to " + maxIndex + "), matching methods with at least " + requiredParamCount + " parameters");
                                            } else {
                                                Logger.info("DEBUG: Incomplete @Argument sequence, requires at least " + requiredParamCount + " parameters");
                                            }
                                        }
                                        if (hasCompleteSequence) {
                                            List<Class<?>> sig = new ArrayList<>();
                                            for (int idx = 0; idx <= maxIndex; idx++) {
                                                sig.add(argumentMap.get(idx));
                                            }
                                            allInferredSignatures.add(sig);
                                        }
                                    } else if (!argumentMap.isEmpty()) {
                                        List<Class<?>> sig = new ArrayList<>();
                                        for (int idx = 0; idx < paramTypes.length; idx++) {
                                            boolean isSpecial = false;
                                            for (java.lang.annotation.Annotation ann : paramAnns[idx]) {
                                                String annType = ann.annotationType().getName();
                                                if (annType.contains("Advice$Return") || annType.contains("Advice$AllArguments") || annType.contains("Advice$This")) {
                                                    isSpecial = true;
                                                    break;
                                                }
                                            }
                                            if (!isSpecial) {
                                                sig.add(paramTypes[idx]);
                                            }
                                        }
                                        if (!sig.isEmpty()) {
                                            allInferredSignatures.add(sig);
                                            if (inferredTypes == null) {
                                                inferredTypes = sig;
                                            }
                                        }
                                    }
                                }
                            }

                            boolean hasMultipleSignatures = allInferredSignatures.size() > 1;
                            if (Loader.g_verbosity > 1) {
                                Logger.info("DEBUG: allInferredSignatures.size() = " + allInferredSignatures.size() + " for " + className + "." + methodName);
                                for (List<Class<?>> sig : allInferredSignatures) {
                                    Logger.info("DEBUG:   signature: " + sig);
                                }
                            }
                            if (hasMultipleSignatures) {
                                inferredTypes = null;
                            }

                            if (strictMatch && hasNoParamMethod && !hasAllArguments && inferredTypes == null && minParameterCount == null) {
                                methodMatcher = methodMatcher.and(ElementMatchers.takesNoArguments());
                                if (Loader.g_verbosity > 1) {
                                    Logger.info("DEBUG: Matching only no-parameter methods for " + methodName);
                                }
                            } else if (minParameterCount != null) {
                                final int minParams = minParameterCount;
                                methodMatcher = methodMatcher.and(ElementMatchers.takesGenericArguments(
                                    args -> {
                                        int count = 0;
                                        for (var ignored : args) count++;
                                        return count >= minParams;
                                    }
                                ));
                                if (!allAdviceMaps.isEmpty()) {
                                    for (int mapIdx = 0; mapIdx < allAdviceMaps.size(); mapIdx++) {
                                        Map<Integer, Class<?>> argMap = allAdviceMaps.get(mapIdx);
                                        for (Map.Entry<Integer, Class<?>> argEntry : argMap.entrySet()) {
                                            final int argIdx = argEntry.getKey();
                                            final Class<?> expectedType = argEntry.getValue();
                                            methodMatcher = methodMatcher.and(ElementMatchers.takesGenericArguments(
                                                args -> {
                                                    int idx = 0;
                                                    for (var arg : args) {
                                                        if (idx == argIdx) {
                                                            return arg.asErasure().isAssignableFrom(expectedType);
                                                        }
                                                        idx++;
                                                    }
                                                    return false;
                                                }
                                            ));
                                        }
                                    }
                                }
                                if (Loader.g_verbosity > 1) {
                                    Logger.info("DEBUG: Matching methods with at least " + minParams + " parameters for " + methodName);
                                }
                            } else if (inferredTypes != null && !inferredTypes.isEmpty()) {
                                methodMatcher = methodMatcher.and(ElementMatchers.takesArguments(inferredTypes.toArray(new Class<?>[0])));
                                if (Loader.g_verbosity > 1) {
                                    Logger.info("DEBUG: Matching exact signature " + inferredTypes + " for " + methodName);
                                }
                            }

                            result = result.visit(Advice.to(transformedClass).on(methodMatcher));
                        }
                    }

                    for (var entry : methodDelegations.entrySet()) {
                        String methodName = entry.getKey();
                        Class<?> delegateClass = entry.getValue();

                        Patch patchAnn = delegateClass.getAnnotation(Patch.class);

                        Method delegateMethod = findMethodWithAnnotation(delegateClass, RUNTIME_TYPE_PATTERNS);
                        if (delegateMethod == null) {
                            for (Method m : delegateClass.getDeclaredMethods()) {
                                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) &&
                                    java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                                    delegateMethod = m;
                                    break;
                                }
                            }
                        }

                        if (delegateMethod == null) {
                            Logger.error("ERROR: No suitable delegate method found in " + delegateClass.getName());
                            continue;
                        }

                        List<Class<?>> inferredTypes = inferSignatureFromMethod(delegateMethod, 
                            Set.of("Return", "StubValue", "DefaultCall", "FieldValue", "Origin", "This", "SuperCall", "SuperMethod"));

                        var methodMatcher = SyntaxSugar.methodMatcher(methodName);
                        if (inferredTypes != null && !inferredTypes.isEmpty()) {
                            if (Loader.g_verbosity > 1) {
                                Logger.info("DEBUG: MethodDelegation inferred signature " + inferredTypes + " for " + methodName);
                            }
                            methodMatcher = methodMatcher.and(ElementMatchers.takesArguments(inferredTypes.toArray(new Class<?>[0])));
                        }

                        boolean isAdviceCompatible = patchAnn != null && patchAnn.isAdvice();
                        if (isAdviceCompatible) {
                            Class<?> transformedClass;
                            try {
                                transformedClass = PatchTransformer.transformPatchClass(delegateClass, Loader.g_instrumentation, Loader.g_verbosity, true);
                                if (transformedClass == null) {
                                    Logger.error("ERROR: PatchTransformer returned null for MethodDelegation " + delegateClass.getName());
                                    continue;
                                }
                            } catch (Exception e) {
                                Logger.error("ERROR: Failed to transform MethodDelegation class " + delegateClass.getName() + ": " + e.getMessage());
                                e.printStackTrace();
                                continue;
                            }
                            result = result.visit(Advice.to(transformedClass).on(methodMatcher));
                        } else {
                            Logger.info("patching " + className + "." + methodName + " with MethodDelegation to " + delegateClass.getName());
                            Class<?> transformedClass;
                            try {
                                transformedClass = PatchTransformer.transformPatchClass(delegateClass, Loader.g_instrumentation, Loader.g_verbosity, true);
                                if (transformedClass == null) {
                                    Logger.error("ERROR: PatchTransformer returned null for MethodDelegation " + delegateClass.getName());
                                    continue;
                                }
                            } catch (Exception e) {
                                Logger.error("ERROR: Failed to transform MethodDelegation class " + delegateClass.getName() + ": " + e.getMessage());
                                e.printStackTrace();
                                continue;
                            }
                            if (methodName.equals("<init>")) {
                                try {
                                    result = result.constructor(methodMatcher)
                                        .intercept(MethodCall.invoke(Object.class.getConstructor())
                                            .andThen(MethodDelegation.to(transformedClass)));
                                } catch (NoSuchMethodException e) {
                                    Logger.error("ERROR: Object() constructor not found: " + e.getMessage());
                                    continue;
                                }
                            } else {
                                result = result.method(methodMatcher)
                                    .intercept(MethodDelegation.to(transformedClass));
                            }
                        }
                    }
                    return result;
                });
        }

        builder.installOn(Loader.g_instrumentation);

        for (String className : classesToWarmUp) {
            Logger.info("warming up class: " + className);
            try {
                Class<?> cls = Class.forName(className);
                builder = builder.warmUp(cls);
            } catch (ClassNotFoundException e) {
                Logger.error("Could not find class for warm-up: " + className);
            } catch (Exception e) {
                Logger.error("Error warming up class " + className + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Collect @Patch classes and register @LuaClass/@LuaMethod classes from a package.
     */
    public static List<Class<?>> collectPatches(String packageName, ClassLoader modLoader) {
        List<Class<?>> patches = new ArrayList<>();

        var classGraph = new ClassGraph()
                .enableAllInfo()
                .acceptPackages(packageName);

        if (modLoader != null) {
            classGraph = classGraph.overrideClassLoaders(modLoader);
        } else {
            ArrayList<String> jarPaths = new ArrayList<>();
            File currentJar = Utils.getCurrentJarFile();
            if (currentJar != null && currentJar.exists()) {
                jarPaths.add(currentJar.getAbsolutePath());
            }
            if (!Loader.g_known_jars.isEmpty()) {
                for (File jar : Loader.g_known_jars) {
                    String jarPath = jar.getAbsolutePath();
                    if (!jarPaths.contains(jarPath)) {
                        jarPaths.add(jarPath);
                    }
                }
            }
            if (!jarPaths.isEmpty()) {
                classGraph = classGraph.overrideClasspath((Object[]) jarPaths.toArray(new String[0]));
            }
        }

        try (ScanResult scanResult = classGraph.scan()) {
            int totalClassesScanned = scanResult.getAllClasses().size();
            Logger.info("Scanned " + totalClassesScanned + " classes in package " + packageName);

            for (ClassInfo classInfo : scanResult.getClassesWithAnnotation(Patch.class.getName())) {
                try {
                    Class<?> patchClass = classInfo.loadClass();
                    String classPackage = patchClass.getPackage() != null ? patchClass.getPackage().getName() : "";
                    if (!classPackage.equals(packageName)) {
                        continue;
                    }
                    Logger.info("Found patch class: " + patchClass.getName());
                    patches.add(patchClass);
                } catch (Exception e) {
                    Logger.error("Error loading patch class " + classInfo.getName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }

            for (ClassInfo classInfo : scanResult.getClassesWithAnnotation(Exposer.LuaClass.class.getName())) {
                if (!classInfo.getPackageName().equals(packageName)) {
                    Logger.error("Class " + classInfo.getName() + " is annotated with @LuaClass but is not in the exact package " + packageName + ", skipping exposure");
                    continue;
                }
                try {
                    Class<?> cls = classInfo.loadClass();
                    Exposer.exposeClassToLua(cls);
                } catch (Exception e) {
                    Logger.error("Error exposing Lua class " + classInfo.getName() + ": " + e.getMessage());
                }
            }

            for (ClassInfo classInfo : scanResult.getAllClasses()) {
                if (!classInfo.getPackageName().equals(packageName)) {
                    continue;
                }
                try {
                    Class<?> cls = classInfo.loadClass();
                    if (Exposer.hasGlobalLuaMethod(cls)) {
                        Exposer.addClassWithGlobalLuaMethod(cls);
                    }
                } catch (Throwable t) {
                    // skip
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return patches;
    }
}
