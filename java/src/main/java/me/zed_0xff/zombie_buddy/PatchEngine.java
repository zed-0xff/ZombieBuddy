package me.zed_0xff.zombie_buddy;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import me.zed_0xff.zombie_buddy.transformers.TransformedJar;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.SuperMethodCall;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Bytecode patching engine using ByteBuddy.
 * Handles scanning for @Patch classes and applying them via Advice or MethodDelegation.
 */
public final class PatchEngine {

    private PatchEngine() {}

    private static record PatchTarget(String className, String methodName) {}

    private static final List<Class<? extends Annotation>> ADVICE_ANNOTATION_TYPES = List.of(
            Advice.OnMethodEnter.class,
            Advice.OnMethodExit.class
            );

    // arguments are "non-special"
    private static final HashSet<Class<? extends Annotation>> ARGUMENT_ANNOTATIONS = new HashSet<>(List.of(
            Advice.Argument.class,
            net.bytebuddy.implementation.bind.annotation.Argument.class
            ));

    private static boolean isSpecialAnnotation(Annotation ann) {
        var annType = ann.annotationType();
        if (ARGUMENT_ANNOTATIONS.contains(annType)) {
            return false;
        }

        String annTypeName = annType.getName();
        boolean result = annTypeName.startsWith("net.bytebuddy.implementation.bind.annotation.") || annTypeName.startsWith("net.bytebuddy.asm.Advice");
        // Logger.trace("isSpecialAnnotation: " + ann + " -> " + result);
        return result;
    }

    private static boolean hasAnnotation(Method method, List<Class<? extends Annotation>> annTypes) {
        for (var annType : annTypes) {
            if (method.isAnnotationPresent(annType)) {
                return true;
            }
        }
        return false;
    }

    private static Method findMethodWithAnnotation(Class<?> clazz, Class<? extends Annotation> annType) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annType)) {
                return method;
            }
        }
        return null;
    }

    private static boolean hasParameterAnnotation(Method method, Class<? extends Annotation> annType) {
        Annotation[][] paramAnns = method.getParameterAnnotations();
        for (Annotation[] paramAnn : paramAnns) {
            for (Annotation ann : paramAnn) {
                if (annType.isInstance(ann)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Class<?>> inferSignatureFromMethod(Method patchMethod) {
        Annotation[][] paramAnns = patchMethod.getParameterAnnotations();
        Class<?>[] paramTypes = patchMethod.getParameterTypes();
        Map<Integer, Class<?>> argumentMap = new HashMap<>();

        for (int i = 0; i < paramAnns.length; i++) {
            boolean isSpecial = false;
            int argumentIndex = -1;

            for (Annotation ann : paramAnns[i]) {
                if (isSpecialAnnotation(ann)) {
                    isSpecial = true;
                    break;
                }
                // https://javadoc.io/static/net.bytebuddy/byte-buddy/1.18.8/net/bytebuddy/implementation/bind/annotation/Argument.html
                if (ann instanceof net.bytebuddy.implementation.bind.annotation.Argument arg) {
                    argumentIndex = arg.value();
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
     * Apply patches using ByteBuddy.
     * @param patchClassNames sorted @Patch class names from {@link me.zed_0xff.zombie_buddy.transformers.TransformedJar}
     * @param modLoader the class loader that defines the patch classes
     * @param transformedJar transformed mod jar contents (required for MethodDelegation into foreign class loaders)
     */
    public static void applyPatches(List<String> patchClassNames, ClassLoader modLoader, TransformedJar transformedJar) {
        List<Class<?>> patches = new ArrayList<>();
        for (String patchClassName : patchClassNames) {
            try {
                Class<?> patchClass = modLoader.loadClass(patchClassName);
                Logger.info("Found patch class: " + patchClass.getName());
                patches.add(patchClass);
            } catch (ClassNotFoundException e) {
                Logger.error("Error loading patch class " + patchClassName + ": " + e.getMessage());
            }
        }
        if (patches.isEmpty()) {
            Logger.info("no patches to apply");
            return;
        }

        // Group patches by target class+method
        Map<PatchTarget, List<Class<?>>> advicePatches = new HashMap<>();
        Map<PatchTarget, Class<?>> delegationPatches = new HashMap<>();

        for (Class<?> patch : patches) {
            Patch ann = patch.getAnnotation(Patch.class);
            if (ann == null) continue;
            if (ann.className().startsWith("me.zed_0xff.")) continue; // refuse to patch our own classes

            // if (ann.className().equals("zombie.Lua.LuaManager$Exposer") && ann.methodName().equals("exposeAll") && !ann.IKnowWhatIAmDoing()) {
                // Logger.error("XXX");
                // Logger.error("XXX don't patch Exposer.exposeAll, use @Exposer.LuaClass annotation!");
                // Logger.error("XXX");
                // continue;
            // }

            PatchTarget target = new PatchTarget(ann.className(), ann.methodName());

            if (ann.isAdvice()) {
                advicePatches.computeIfAbsent(target, k -> new ArrayList<>()).add(patch);
            } else {
                if (delegationPatches.containsKey(target)) {
                    Logger.warn("multiple MethodDelegation patches for " + ann.className() + "." + ann.methodName() + " - only last one will apply!");
                }
                delegationPatches.put(target, patch);
            }
        }

        // Collect all target classes that need patching
        Set<String> targetClasses = new HashSet<>();
        for (PatchTarget t : advicePatches.keySet()) targetClasses.add(t.className());
        for (PatchTarget t : delegationPatches.keySet()) targetClasses.add(t.className());

        // Check which target classes are already loaded (use the JVM's Class instance, not Class.forName)
        Set<String> loadedClasses = new HashSet<>();
        Map<String, Class<?>> loadedClassByName = new HashMap<>();
        for (Class<?> c : Loader.g_instrumentation.getAllLoadedClasses()) {
            String className = c.getName();
            if (targetClasses.contains(className)) {
                loadedClasses.add(className);
                loadedClassByName.putIfAbsent(className, c);
            }
        }
        if (!loadedClasses.isEmpty()) {
            Logger.info("Already loaded classes to retransform: " + loadedClasses);
        }

        // Warn about MethodDelegation on already-loaded classes (won't work with retransformation)
        // for (var entry : delegationPatches.entrySet()) {
        //     if (loadedClasses.contains(entry.getKey().className())) {
        //         Logger.error("WARNING: MethodDelegation patch for already-loaded class " + 
        //             entry.getKey().className() + "." + entry.getKey().methodName() + 
        //             " - this may not work! Use isAdvice=true for loaded classes.");
        //     }
        // }

        Set<String> patchOnFirstLoad = new HashSet<>(targetClasses);
        patchOnFirstLoad.removeAll(loadedClasses);
        if (!patchOnFirstLoad.isEmpty()) {
            Logger.info("Patch targets not loaded yet (will instrument on first use): " + patchOnFirstLoad);
        }

        var bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly();
        if (Loader.g_verbosity > 0) {
            if (Loader.g_verbosity <= 2) {
                bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut().withTransformationsOnly();
            } else { // 3+
                bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut();
            }
        }

        boolean needsRetransformPhase = !loadedClasses.isEmpty();

        AgentBuilder firstLoadBuilder = null;
        if (!patchOnFirstLoad.isEmpty()) {
            firstLoadBuilder = new AgentBuilder.Default()
                // DISABLED: weave on first define only — do not auto-retransform loaded types on installOn
                // (retransform of loaded types needs disableClassFormatChanges in phase 2).
                .with(AgentBuilder.RedefinitionStrategy.DISABLED)
                .with(bbLogger)
                .with(zbListenerFor(patchOnFirstLoad));
        }

        AgentBuilder loadedBuilder = null;
        if (needsRetransformPhase) {
            loadedBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(bbLogger)
                .with(zbListenerFor(loadedClasses))
                .disableClassFormatChanges();
        }

        // Same rules as phase 1 but with disable — installed only for deferred targets that loaded before phase 1 could weave them.
        AgentBuilder deferredRetransformBuilder = null;
        if (!patchOnFirstLoad.isEmpty()) {
            deferredRetransformBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(bbLogger)
                .with(zbListenerFor(patchOnFirstLoad))
                .disableClassFormatChanges();
        }

        for (String className : targetClasses) {
            // Collect all method patches for this class
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

            final Map<String, List<Class<?>>> classAdvices = Map.copyOf(methodAdvices);
            final Map<String, Class<?>> classDelegations = Map.copyOf(methodDelegations);

            var patchTransform = new net.bytebuddy.agent.builder.AgentBuilder.Transformer() {
                @Override
                public net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
                        net.bytebuddy.dynamic.DynamicType.Builder<?> bl,
                        net.bytebuddy.description.type.TypeDescription td,
                        ClassLoader cl,
                        net.bytebuddy.utility.JavaModule mo,
                        java.security.ProtectionDomain pd) {
                    if (cl != null && cl != modLoader && modLoader != ZombieBuddy.class.getClassLoader()) {
                        ModJarInjector.exposePatchesInClassLoader(transformedJar, cl);
                    }

                    var result = bl;
                    
                    // Apply stacked Advice patches per method
                    for (var entry : classAdvices.entrySet()) {
                        String methodName = entry.getKey();
                        List<Class<?>> advices = entry.getValue();
                        
                        Logger.info("patching " + className + "." + methodName + " with " + advices.size() + " advice(s)");
                        
                        // Check if method exists in the type description
                        var methods = td.getDeclaredMethods().filter(SyntaxSugar.methodMatcher(methodName));
                        if (methods.isEmpty()) {
                            Logger.warn("Method " + methodName + " not found in " + td.getName());
                        }
                        
                        // Apply each advice via separate .visit() calls - they stack
                        for (Class<?> adviceClass : advices) {
                            Patch patchAnn = adviceClass.getAnnotation(Patch.class);
                            boolean strictMatch = patchAnn != null && patchAnn.strictMatch();

                            Class<?> patchClass = ModJarInjector.loadInClassLoader(adviceClass, cl);
                            patchClass = PatchTransformer.preparePatch(patchClass, td);
                            if (patchClass == null) continue;

                            var methodMatcher = buildAdviceMethodMatcher(methodName, strictMatch, patchClass, null);

                            try {
                                ClassFileLocator locator = classFileLocator(transformedJar, modLoader);
                                result = result.visit(Advice.to(patchClass, locator).on(methodMatcher));
                                Logger.debug("Applied advice to " + className + "." + methodName);
                            } catch (Exception e) {
                                Logger.error("ERROR: Failed to apply advice to " + className + "." + methodName + ": " + e.getMessage());
                                if (Loader.g_verbosity > 0) {
                                    Logger.printStackTrace(e);
                                }
                            }
                        }
                    }
                    
                    // Apply MethodDelegation patches (only one per method)
                    for (var entry : classDelegations.entrySet()) {
                        String methodName = entry.getKey();
                        Class<?> delegationClass = entry.getValue();

                        Class<?> patchClass = ModJarInjector.loadInClassLoader(delegationClass, cl);
                        patchClass = PatchTransformer.preparePatch(patchClass, td);
                        if (patchClass == null) continue;

                        Logger.info("patching " + className + "." + methodName + " with delegation");
                        
                        // Special handling for constructors: always use Object's constructor when overriding
                        if (methodName.equals("<init>")) {
                            try {
                                // Infer constructor signature from delegation method's @Argument annotations
                                Method delegationMethod = findMethodWithAnnotation(patchClass, RuntimeType.class);
                                List<Class<?>> inferredConstructorSignature = null;
                                if (delegationMethod != null) {
                                    inferredConstructorSignature = inferSignatureFromMethod(delegationMethod);
                                }
                                
                                // Build constructor matcher based on inferred signature
                                net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> constructorMatcher;
                                if (!Utils.isBlank(inferredConstructorSignature)) {
                                    // Match constructor with specific signature
                                    constructorMatcher = net.bytebuddy.matcher.ElementMatchers.isConstructor()
                                        .and(net.bytebuddy.matcher.ElementMatchers.takesArguments(inferredConstructorSignature.toArray(new Class<?>[0])));
                                } else {
                                    // No signature inferred, match all constructors (fallback)
                                    constructorMatcher = net.bytebuddy.matcher.ElementMatchers.isConstructor();
                                }
                                
                                // Always use Object's constructor when overriding a constructor.
                                // Field initializers will still run during object allocation (before any constructor is called).
                                java.lang.reflect.Constructor<?> objectConstructor = Object.class.getDeclaredConstructor();
                                result = result
                                    .constructor(constructorMatcher)
                                    .intercept(MethodCall.invoke(objectConstructor)
                                        .andThen(MethodDelegation.to(patchClass)));
                            } catch (Throwable t) {
                                Logger.error("ERROR: Could not set up constructor delegation: " + t.getMessage());
                                if (Loader.g_verbosity > 0) {
                                    Logger.printStackTrace(t);
                                }
                                // Fallback to SuperMethodCall
                                result = result
                                    .constructor(ElementMatchers.any())
                                    .intercept(SuperMethodCall.INSTANCE.andThen(MethodDelegation.to(patchClass)));
                            }
                        } else {
                            result = result
                                .method(SyntaxSugar.methodMatcher(methodName))
                                .intercept(MethodDelegation.to(patchClass));
                        }
                    }
                    
                    return result;
                }
            };

            if (firstLoadBuilder != null && patchOnFirstLoad.contains(className)) {
                firstLoadBuilder = firstLoadBuilder.type(SyntaxSugar.typeMatcher(className)).transform(patchTransform);
            }
            if (deferredRetransformBuilder != null && patchOnFirstLoad.contains(className)) {
                deferredRetransformBuilder = deferredRetransformBuilder.type(SyntaxSugar.typeMatcher(className)).transform(patchTransform);
            }
            if (loadedBuilder != null && loadedClasses.contains(className)) {
                loadedBuilder = loadedBuilder.type(SyntaxSugar.typeMatcher(className)).transform(patchTransform);
            }
        }

        ClassLoader gameClassLoader = gameClassLoaderFrom(loadedClassByName);
        boolean mixedLoadedAndDeferred = !loadedClasses.isEmpty() && !patchOnFirstLoad.isEmpty();

        // Phase 1: not-yet-loaded targets — no disableClassFormatChanges (Advice may add helper methods on first define).
        if (firstLoadBuilder != null) {
            firstLoadBuilder.installOn(Loader.g_instrumentation);
        }

        // Deferred targets that loaded between scan and phase-1 installOn need phase-2 retransform (with disable).
        Set<String> slipInLoaded = new HashSet<>();
        for (String name : patchOnFirstLoad) {
            if (findLoadedTargetClass(name, loadedClassByName) != null) {
                slipInLoaded.add(name);
            }
        }

        // Mixed case: define still-unloaded deferred targets through the phase-1 agent (e.g. AnimNode).
        if (mixedLoadedAndDeferred) {
            loadDeferredTargets(patchOnFirstLoad, gameClassLoader, loadedClassByName);
        }

        // Phase 2: already-loaded targets — disableClassFormatChanges restricts Advice to method-body changes only.
        if (needsRetransformPhase || !slipInLoaded.isEmpty()) {
            if (loadedBuilder != null) {
                loadedBuilder.installOn(Loader.g_instrumentation);
            }
            if (deferredRetransformBuilder != null && !slipInLoaded.isEmpty()) {
                deferredRetransformBuilder.installOn(Loader.g_instrumentation);
            }
            // installOn with RETRANSFORMATION already retransforms matching loaded types; explicit retransformClasses was redundant.
        }
    }

    private static ClassFileLocator classFileLocator(TransformedJar transformedJar, ClassLoader modLoader) {
        if (transformedJar == null || transformedJar.classes().isEmpty()) {
            return ClassFileLocator.ForClassLoader.of(modLoader);
        }

        return new ClassFileLocator.Compound(
            new ClassFileLocator.Simple(transformedJar.classes()),
            ClassFileLocator.ForClassLoader.of(modLoader)
        );
    }

    private static AgentBuilder.Listener zbListenerFor(Set<String> warnIfIgnoredTargets) {
        return new AgentBuilder.Listener.Adapter() {
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
                Logger.printStackTrace(throwable);
            }

            @Override
            public void onIgnored(net.bytebuddy.description.type.TypeDescription td,
                                 ClassLoader cl,
                                 net.bytebuddy.utility.JavaModule jm,
                                 boolean loaded) {
                String className = td.getName();
                if (warnIfIgnoredTargets.contains(className)) {
                    Logger.error("Target class IGNORED: " + className + " (loaded=" + loaded + ")");
                }
            }
        };
    }

    private static Class<?> findLoadedTargetClass(String name, Map<String, Class<?>> known) {
        Class<?> cls = known.get(name);
        if (cls != null) {
            return cls;
        }

        for (Class<?> c : Loader.g_instrumentation.getAllLoadedClasses()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }

        return null;
    }

    private static ClassLoader gameClassLoaderFrom(Map<String, Class<?>> loadedGameClasses) {
        for (Class<?> c : loadedGameClasses.values()) {
            ClassLoader cl = c.getClassLoader();
            if (cl != null) {
                return cl;
            }
        }

        return ClassLoader.getSystemClassLoader();
    }

    /** First define of deferred targets after phase-1 installOn so the agent weaves Advice (mixed loaded/deferred case only). */
    private static void loadDeferredTargets(Set<String> deferred, ClassLoader cl, Map<String, Class<?>> known) {
        for (String name : deferred) {
            if (findLoadedTargetClass(name, known) != null) {
                continue;
            }

            try {
                Class.forName(name, false, cl);
                Logger.info("Loaded deferred target: " + name);
            } catch (ClassNotFoundException e) {
                Logger.error("Could not load deferred target " + name + ": " + e.getMessage());
            } catch (Exception e) {
                Logger.error("Error loading deferred target " + name + ": " + e.getMessage());
                if (Loader.g_verbosity > 0) {
                    Logger.printStackTrace(e);
                }
            }
        }
    }

    private static net.bytebuddy.matcher.ElementMatcher.Junction<MethodDescription> buildAdviceMethodMatcher(
            String methodName,
            boolean strictMatch,
            Class<?> patchClass,
            Method onlyMethod) {
        var methodMatcher = SyntaxSugar.methodMatcher(methodName);

        boolean hasAllArguments = false;
        boolean hasNoParamMethod = false;
        List<Class<?>> inferredTypes = null;
        Integer minParameterCount = null;
        List<Map<Integer, Class<?>>> allAdviceMaps = new ArrayList<>();
        List<Boolean> allAdviceExactMatch = new ArrayList<>();
        Set<List<Class<?>>> allInferredSignatures = new HashSet<>();

        for (Method adviceMethod : patchClass.getDeclaredMethods()) {
            if (onlyMethod != null && adviceMethod != onlyMethod) {
                continue;
            }

            if (!hasAnnotation(adviceMethod, ADVICE_ANNOTATION_TYPES)) {
                continue;
            }

            Annotation[][] paramAnns = adviceMethod.getParameterAnnotations();

            if (hasParameterAnnotation(adviceMethod, Advice.AllArguments.class)) {
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

                for (int i = 0; i < paramAnns.length; i++) {
                    boolean isArgument = false;
                    boolean skip = false;
                    int argumentIndex = -1;

                    for (Annotation ann : paramAnns[i]) {
                        if (ann instanceof Advice.Argument arg) {
                            isArgument = true;
                            hasAnyArguments = true;
                            allParamsAreSpecial = false;
                            argumentIndex = arg.value();
                            Class<?> paramType = paramTypes[i];
                            Class<?> typeToStore = paramType.isArray() ? paramType.getComponentType() : paramType;
                            argumentMap.put(argumentIndex, typeToStore);
                            break;
                        }

                        if (isSpecialAnnotation(ann)) {
                            skip = true;
                            break;
                        }
                    }

                    if (!skip && !isArgument) {
                        allParamsAreSpecial = false;
                        argumentMap.put(i, paramTypes[i]);
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
                        for (Annotation ann : paramAnns[idx]) {
                            if (isSpecialAnnotation(ann)) {
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

        if (allInferredSignatures.size() > 1) {
            inferredTypes = null;
        }

        if (hasAllArguments) {
            return methodMatcher;
        }

        if (hasNoParamMethod && !strictMatch && allAdviceMaps.isEmpty()) {
            return methodMatcher;
        }

        final List<Map<Integer, Class<?>>> maps = new ArrayList<>(allAdviceMaps);
        final List<Boolean> exactMatches = new ArrayList<>(allAdviceExactMatch);
        final int minParams = (minParameterCount != null) ? minParameterCount : 0;
        final boolean strict = strictMatch;
        final boolean noParam = hasNoParamMethod;

        return methodMatcher.and(new net.bytebuddy.matcher.ElementMatcher<MethodDescription>() {
            @Override
            public boolean matches(MethodDescription target) {
                int targetParamCount = target.getParameters().size();
                boolean allArgsMatch = false;
                boolean result = false;

                while (true) {
                    if (noParam && targetParamCount == 0) {
                        result = true;
                        break;
                    }
                    if (strict && noParam && maps.isEmpty() && targetParamCount > 0) {
                        result = false;
                        break;
                    }

                    for (int i = 0; i < maps.size(); i++) {
                        Map<Integer, Class<?>> argMap = maps.get(i);
                        boolean exact = exactMatches.get(i);

                        if (exact && targetParamCount != argMap.size()) continue;
                        if (!exact && targetParamCount < argMap.size()) continue;
                        if (!exact && targetParamCount < minParams) continue;

                        allArgsMatch = true;
                        for (Map.Entry<Integer, Class<?>> entry : argMap.entrySet()) {
                            int idx = entry.getKey();
                            if (idx >= targetParamCount) {
                                allArgsMatch = false;
                                break;
                            }
                            Class<?> expected = entry.getValue();
                            if (expected == Object.class) continue;
                            net.bytebuddy.description.type.TypeDescription actual = target.getParameters().get(idx).getType().asErasure();
                            if (!actual.isAssignableTo(expected)) {
                                allArgsMatch = false;
                                break;
                            }
                        }
                        if (allArgsMatch) break;
                    }
                    break;
                }

                return allArgsMatch || (noParam && !strict && minParams == 0);
            }
        });
    }
}
