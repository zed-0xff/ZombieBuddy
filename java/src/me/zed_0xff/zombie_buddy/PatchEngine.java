package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.lang.annotation.Annotation;
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
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
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

    private static final List<Class<? extends Annotation>> ADVICE_ANNOTATION_TYPES = List.of(
            Advice.OnMethodEnter.class,
            Advice.OnMethodExit.class
            );
    private static final Set<Class<? extends Annotation>> DELEGATION_PARAM_SPECIAL_ANNOTATIONS = Set.of(
            net.bytebuddy.implementation.bind.annotation.SuperCall.class,
            net.bytebuddy.implementation.bind.annotation.SuperMethod.class,
            net.bytebuddy.implementation.bind.annotation.This.class
            );
    private static final Set<Class<? extends Annotation>> ADVICE_PARAM_SPECIAL_ANNOTATIONS = Set.of(
            Advice.Return.class,
            Advice.AllArguments.class,
            Advice.Local.class,
            Advice.This.class
            );

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

    private static List<Class<?>> inferSignatureFromMethod(Method patchMethod, Set<Class<? extends Annotation>> specialAnnotationTypes) {
        Annotation[][] paramAnns = patchMethod.getParameterAnnotations();
        Class<?>[] paramTypes = patchMethod.getParameterTypes();
        Map<Integer, Class<?>> argumentMap = new HashMap<>();

        for (int i = 0; i < paramAnns.length; i++) {
            boolean isSpecial = false;
            int argumentIndex = -1;

            for (Annotation ann : paramAnns[i]) {
                if (specialAnnotationTypes.contains(ann.annotationType())) {
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

        // Group patches by target class+method
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
                    Logger.warn("multiple MethodDelegation patches for " + ann.className() + "." + ann.methodName() + " - only last one will apply!");
                }
                delegationPatches.put(target, patch);
            }
        }

        // Collect all target classes that need patching
        Set<String> targetClasses = new HashSet<>();
        for (PatchTarget t : advicePatches.keySet()) targetClasses.add(t.className());
        for (PatchTarget t : delegationPatches.keySet()) targetClasses.add(t.className());

        // Check which target classes are already loaded
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

        // Warn about MethodDelegation on already-loaded classes (won't work with retransformation)
        // for (var entry : delegationPatches.entrySet()) {
        //     if (loadedClasses.contains(entry.getKey().className())) {
        //         Logger.error("WARNING: MethodDelegation patch for already-loaded class " + 
        //             entry.getKey().className() + "." + entry.getKey().methodName() + 
        //             " - this may not work! Use isAdvice=true for loaded classes.");
        //     }
        // }

        // Check if we have Advice patches on already-loaded classes
        // If so, we need to disable class format changes for retransformation to work
        boolean hasAdviceOnLoadedClasses = false;
        Set<String> advLoadedClasses = new HashSet<>();
        for (var entry : advicePatches.entrySet()) {
            if (loadedClasses.contains(entry.getKey().className())) {
                hasAdviceOnLoadedClasses = true;
                advLoadedClasses.add(entry.getKey().className());
                break;
            }
        }

        var bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly();
        if (Loader.g_verbosity > 0) {
            if (Loader.g_verbosity <= 2) {
                bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut().withTransformationsOnly();
            } else { // 3+
                bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut();
            }
        }

        AgentBuilder builder = new AgentBuilder.Default();
        
        // Only disable class format changes if we have Advice patches on already-loaded classes
        // This is needed for retransformation to work, but breaks MethodDelegation
        if (hasAdviceOnLoadedClasses) {
            Logger.debug("Disabling class format changes for Advice patches on already-loaded classes");
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
                    // Log ignored classes that we're targeting
                    String className = td.getName();
                    if (targetClasses.contains(className)) {
                        Logger.error("Target class IGNORED: " + className + " (loaded=" + loaded + ")");
                    }
                }
            });

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

            builder = builder
                .type(SyntaxSugar.typeMatcher(className))
                .transform((bl, td, cl, mo, pd) -> {
                    var result = bl;
                    
                    // Apply stacked Advice patches per method
                    for (var entry : methodAdvices.entrySet()) {
                        String methodName = entry.getKey();
                        List<Class<?>> advices = entry.getValue();
                        
                        Logger.info("patching " + className + "." + methodName + " with " + advices.size() + " advice(s)");
                        
                        // Check if method exists in the type description
                        var methods = td.getDeclaredMethods().filter(SyntaxSugar.methodMatcher(methodName));
                        if (methods.isEmpty()) {
                            Logger.error("Method " + methodName + " not found in " + td.getName());
                        }
                        
                        // Apply each advice via separate .visit() calls - they stack
                        for (Class<?> adviceClass : advices) {
                            // Get the Patch annotation to read strictMatch parameter
                            Patch patchAnn = adviceClass.getAnnotation(Patch.class);
                            boolean strictMatch = patchAnn != null && patchAnn.strictMatch();
                            
                            // Transform patch class to replace Patch.* annotations with ByteBuddy's
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
                            
                            // KISS approach: Simple and predictable method matching
                            var methodMatcher = SyntaxSugar.methodMatcher(methodName);
                            
                            boolean hasAllArguments = false;
                            boolean hasNoParamMethod = false;
                            List<Class<?>> inferredTypes = null;
                            Integer minParameterCount = null; // For incomplete @Argument sequences
                            // List of argument constraint maps from each advice method
                            java.util.List<java.util.Map<Integer, Class<?>>> allAdviceMaps = new java.util.ArrayList<>();
                            // List of booleans indicating if exact parameter count is required for each advice method
                            java.util.List<Boolean> allAdviceExactMatch = new java.util.ArrayList<>();
                            
                            // Track all inferred signatures to detect multiple methods with different signatures
                            java.util.Set<List<Class<?>>> allInferredSignatures = new java.util.HashSet<>();
                            
                            for (Method adviceMethod : transformedClass.getDeclaredMethods()) {
                                // Check if this method has advice annotations
                                if (!hasAnnotation(adviceMethod, ADVICE_ANNOTATION_TYPES)) continue;
                                
                                Annotation[][] paramAnns = adviceMethod.getParameterAnnotations();
                                
                                // Check for @AllArguments annotation
                                if (hasParameterAnnotation(adviceMethod, Advice.AllArguments.class)) {
                                    hasAllArguments = true;
                                }
                                
                                // If method has no parameters (and no @AllArguments), match only methods with no parameters
                                if (adviceMethod.getParameterCount() == 0 && !hasAllArguments) {
                                    hasNoParamMethod = true;
                                }
                                
                                // Try to infer signature from @Argument annotations
                                // Process all methods to detect multiple signatures, not just the first one
                                if (!hasAllArguments) {
                                    Class<?>[] paramTypes = adviceMethod.getParameterTypes();
                                    
                                    // Map to collect argument indices and their types for THIS advice method
                                    java.util.Map<Integer, Class<?>> argumentMap = new java.util.HashMap<>();
                                    boolean hasAnyArguments = false;
                                    boolean allParamsAreSpecial = paramTypes.length > 0; // Will be set to false if we find a non-special param
                                    int regularParamCount = 0;
                                    
                                    for (int i = 0; i < paramAnns.length; i++) {
                                        boolean isArgument = false;
                                        boolean skip = false;
                                        int argumentIndex = -1;
                                        
                                        for (Annotation ann : paramAnns[i]) {
                                            Logger.trace("param " + i + " annotation: " + ann);
                                            if (ann instanceof Advice.Argument arg) {
                                                isArgument = true;
                                                hasAnyArguments = true;
                                                allParamsAreSpecial = false; // @Argument is not "special" in this context
                                                argumentIndex = arg.value();
                                                Class<?> paramType = paramTypes[i];
                                                Class<?> typeToStore = paramType.isArray() ? paramType.getComponentType() : paramType;
                                                Logger.trace("arg #" + argumentIndex + " of type " + typeToStore);
                                                argumentMap.put(argumentIndex, typeToStore);
                                                break;
                                            }

                                            // Skip @Local / @This / @Return parameters - they're not part of the target method signature
                                            // (@AllArguments already handled above)
                                            if (ADVICE_PARAM_SPECIAL_ANNOTATIONS.contains(ann.annotationType())) {
                                                skip = true;
                                                break;
                                            }
                                        }
                                        
                                        // If not @Argument and not @Return/@AllArguments/@Local/@This, include it as a regular parameter
                                        if (!skip && !isArgument) {
                                            // Regular parameter (not annotated) - assume it's in order
                                            allParamsAreSpecial = false;
                                            Logger.trace("arg #" + i + " of type " + paramTypes[i]);
                                            argumentMap.put(i, paramTypes[i]);
                                            regularParamCount++;
                                        }
                                    }
                                    
                                    if (!argumentMap.isEmpty()) {
                                        allAdviceMaps.add(argumentMap);
                                        allAdviceExactMatch.add(!hasAnyArguments);
                                    }
                                    
                                    // If all parameters are special (e.g., only @Return), treat as matching methods with no parameters
                                    if (allParamsAreSpecial && paramTypes.length > 0) {
                                        hasNoParamMethod = true;
                                    }
                                    
                                    // Build signature list from the argument map
                                    if (hasAnyArguments && !argumentMap.isEmpty()) {
                                        // Find the maximum index to determine signature length
                                        int maxIndex = argumentMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                                        
                                        // Check if we have a complete sequence from 0 to maxIndex
                                        boolean hasCompleteSequence = true;
                                        for (int idx = 0; idx <= maxIndex; idx++) {
                                            if (!argumentMap.containsKey(idx)) {
                                                hasCompleteSequence = false;
                                                break;
                                            }
                                        }
                                        
                                        // For @Argument annotations, we use "at least N parameters" matching
                                        // This allows @Argument(0) to match methods with 1, 2, 3+ parameters
                                        // and @Argument(1) to match methods with 2, 3+ parameters, etc.
                                        int requiredParamCount = maxIndex + 1;
                                        if (minParameterCount == null || requiredParamCount > minParameterCount) {
                                            minParameterCount = requiredParamCount;
                                        }
                                        if (Loader.g_verbosity > 1) {
                                            if (hasCompleteSequence) {
                                                Logger.debug("Complete @Argument sequence (0 to " + maxIndex + "), matching methods with at least " + requiredParamCount + " parameters");
                                            } else {
                                                Logger.debug("Incomplete @Argument sequence, requires at least " + requiredParamCount + " parameters");
                                            }
                                        }
                                        
                                        // Still build signature for multiple signature detection, but don't use it for exact matching
                                        if (hasCompleteSequence) {
                                            // Build the signature list in order
                                            List<Class<?>> sig = new ArrayList<>();
                                            for (int idx = 0; idx <= maxIndex; idx++) {
                                                sig.add(argumentMap.get(idx));
                                            }
                                            allInferredSignatures.add(sig);
                                            // Don't set inferredTypes - we'll use minParameterCount for matching instead
                                        }
                                    } else if (!argumentMap.isEmpty()) {
                                        // No @Argument annotations, but we have regular parameters
                                        // Build signature from regular parameters in order
                                        List<Class<?>> sig = new ArrayList<>();
                                        for (int idx = 0; idx < paramTypes.length; idx++) {
                                            boolean isSpecial = false;
                                            for (Annotation ann : paramAnns[idx]) {
                                                if (ADVICE_PARAM_SPECIAL_ANNOTATIONS.contains(ann.annotationType())) {
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
                            
                            // If we have multiple methods with different signatures, use name-based matching
                            // so ByteBuddy can match each method to the appropriate overload
                            boolean hasMultipleSignatures = allInferredSignatures.size() > 1;
                            if (Loader.g_verbosity > 1) {
                                Logger.debug("allInferredSignatures.size() = " + allInferredSignatures.size() + " for " + className + "." + methodName);
                                for (List<Class<?>> sig : allInferredSignatures) {
                                    Logger.debug("  signature: " + sig);
                                }
                            }
                            if (hasMultipleSignatures) {
                                inferredTypes = null; // Clear inferred types to force name-based matching
                            }

                            Logger.info(
                                    "hasMultipleSignatures: " + hasMultipleSignatures +
                                    ", hasAllArguments: " + hasAllArguments +
                                    ", hasNoParamMethod: " + hasNoParamMethod +
                                    ", inferredTypes: " + inferredTypes
                                    );
                            
                            // Apply matching strategy
                            if (hasAllArguments) {
                                // @AllArguments = match all overloads by name
                                Logger.debug("Using name-based matching for " + methodName + " (has @AllArguments)");
                            } else if (hasNoParamMethod && !strictMatch && allAdviceMaps.isEmpty()) {
                                // Match any method (default behavior for no-param advice when strictMatch=false)
                                Logger.debug("Matching any method for " + methodName + " (strictMatch=false, default)");
                            } else {
                                // Signature-aware matching for overloads and @Argument annotations
                                final java.util.List<java.util.Map<Integer, Class<?>>> maps = new java.util.ArrayList<>(allAdviceMaps);
                                final java.util.List<Boolean> exactMatches = new java.util.ArrayList<>(allAdviceExactMatch);
                                final int minParams = (minParameterCount != null) ? minParameterCount : 0;
                                final boolean strict = strictMatch;
                                final boolean noParam = hasNoParamMethod;
                                
                                methodMatcher = SyntaxSugar.methodMatcher(methodName)
                                    .and(new net.bytebuddy.matcher.ElementMatcher<net.bytebuddy.description.method.MethodDescription>() {
                                        @Override
                                        public boolean matches(net.bytebuddy.description.method.MethodDescription target) {
                                            int targetParamCount = target.getParameters().size();
                                            
                                            // If advice has no parameters, check strictMatch
                                            if (noParam && targetParamCount == 0) return true;
                                            if (strict && noParam && maps.isEmpty() && targetParamCount > 0) return false;
                                            
                                            for (int i = 0; i < maps.size(); i++) {
                                                java.util.Map<Integer, Class<?>> argMap = maps.get(i);
                                                boolean exact = exactMatches.get(i);
                                                
                                                if (exact && targetParamCount != argMap.size()) continue;
                                                if (!exact && targetParamCount < argMap.size()) continue;
                                                if (!exact && targetParamCount < minParams) continue;

                                                boolean allArgsMatch = true;
                                                for (java.util.Map.Entry<Integer, Class<?>> entry : argMap.entrySet()) {
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
                                                if (allArgsMatch) return true;
                                            }
                                            
                                            // If no signatures match, and we have no-param advice with strictMatch=false, allow it
                                            return noParam && !strict;
                                        }
                                    });
                                    
                                Logger.debug("Using signature-aware matching for " + methodName + " (signatures: " + maps.size() + ", minParams: " + minParams + ")");
                            }
                            
                            try {
                                result = result.visit(Advice.to(transformedClass).on(methodMatcher));
                                Logger.debug("Applied advice to " + className + "." + methodName);
                            } catch (Exception e) {
                                Logger.error("ERROR: Failed to apply advice to " + className + "." + methodName + ": " + e.getMessage());
                                if (Loader.g_verbosity > 0) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                    
                    // Apply MethodDelegation patches (only one per method)
                    for (var entry : methodDelegations.entrySet()) {
                        String methodName = entry.getKey();
                        Class<?> delegationClass = entry.getValue();
                        
                        // Transform the delegation class to convert Patch.* annotations to ByteBuddy annotations
                        Class<?> transformedDelegationClass = PatchTransformer.transformPatchClass(delegationClass, Loader.g_instrumentation, Loader.g_verbosity, true);
                        if (transformedDelegationClass == null) {
                            Logger.error("ERROR: PatchTransformer returned null for " + delegationClass.getName());
                            transformedDelegationClass = delegationClass; // Fall back to original
                        }
                        
                        Logger.info("patching " + className + "." + methodName + " with delegation");
                        
                        // Special handling for constructors: always use Object's constructor when overriding
                        if (methodName.equals("<init>")) {
                            try {
                                // Infer constructor signature from delegation method's @Argument annotations
                                Method delegationMethod = findMethodWithAnnotation(transformedDelegationClass, RuntimeType.class);
                                List<Class<?>> inferredConstructorSignature = null;
                                if (delegationMethod != null) {
                                    inferredConstructorSignature = inferSignatureFromMethod(delegationMethod, DELEGATION_PARAM_SPECIAL_ANNOTATIONS);
                                }
                                
                                // Build constructor matcher based on inferred signature
                                net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> constructorMatcher;
                                if (inferredConstructorSignature != null && !inferredConstructorSignature.isEmpty()) {
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
                                        .andThen(MethodDelegation.to(transformedDelegationClass)));
                            } catch (Exception e) {
                                Logger.error("ERROR: Could not set up constructor delegation: " + e.getMessage());
                                if (Loader.g_verbosity > 0) {
                                    e.printStackTrace();
                                }
                                // Fallback to SuperMethodCall
                                result = result
                                    .constructor(ElementMatchers.any())
                                    .intercept(SuperMethodCall.INSTANCE.andThen(MethodDelegation.to(transformedDelegationClass)));
                            }
                        } else {
                        result = result
                            .method(SyntaxSugar.methodMatcher(methodName))
                            .intercept(MethodDelegation.to(transformedDelegationClass));
                        }
                    }
                    
                    return result;
                });
        }
        
        builder.installOn(Loader.g_instrumentation);

        // Explicitly retransform already-loaded classes to ensure Advice is applied
        // ByteBuddy's agent builder with RETRANSFORMATION strategy should handle this automatically,
        // but we call retransformClasses() explicitly to trigger the transformation immediately.
        // Note: ByteBuddy's Advice may not work correctly with retransformation for already-loaded classes
        // due to JVM limitations. If this doesn't work, patches need to be loaded before the target class.
        if (!advLoadedClasses.isEmpty()) {
            Logger.info("Explicitly retransforming " + advLoadedClasses.size() + " already-loaded class(es)");
            // Logger.info("WARNING: Advice patches on already-loaded classes may not work due to JVM retransformation limitations.");
            // Logger.info("Consider loading patches before the target class is loaded, or use MethodDelegation instead.");
            for (String className : advLoadedClasses) {
                try {
                    Class<?> cls = Class.forName(className);
                    // Retransform through ByteBuddy's agent pipeline
                    Loader.g_instrumentation.retransformClasses(cls);
                    Logger.debug("Retransformed: " + className);
                } catch (ClassNotFoundException e) {
                    Logger.error("Could not find class for retransformation: " + className);
                } catch (Exception e) {
                    Logger.error("Error retransforming class " + className + ": " + e.getMessage());
                    if (Loader.g_verbosity > 0) {
                        e.printStackTrace();
                    }
                }
            }
        }

        // Warm up classes _AFTER_ installing the agent builder
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
