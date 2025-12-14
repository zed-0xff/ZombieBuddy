package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.io.InputStream;
import java.lang.ClassLoader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.classgraph.*;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

import zombie.ZomboidFileSystem;

public class Loader {
    public static Instrumentation g_instrumentation;
    public static int g_verbosity = 0;
    public static boolean g_dump_env = false;
    public static boolean g_exit_after_game_init = false;

    static Set<String> g_known_classes = new HashSet<>();
    static Set<File> g_known_jars = new HashSet<>();
    static String g_new_version = null;

    private static final byte[] EXPECTED_FINGERPRINT = {
        (byte)0xA7, (byte)0x75, (byte)0x10, (byte)0x1B, (byte)0xFB, (byte)0x6C, (byte)0x33, (byte)0xA9,
        (byte)0x2C, (byte)0xDF, (byte)0x25, (byte)0x20, (byte)0xAC, (byte)0x8D, (byte)0x02, (byte)0x95,
        (byte)0xCE, (byte)0xBF, (byte)0x89, (byte)0x0C, (byte)0x84, (byte)0x05, (byte)0x97, (byte)0x37,
        (byte)0x7F, (byte)0xD0, (byte)0x9B, (byte)0x17, (byte)0xD0, (byte)0xEA, (byte)0xDD, (byte)0x97
    };

    // Key for grouping patches by class+method
    private static record PatchTarget(String className, String methodName) {}

    public static void loadMods(ArrayList<String> mods) {
        ArrayList<JavaModInfo> jModInfos = new ArrayList<>();

        for (String mod_id : mods) {
            String modDir = ZomboidFileSystem.instance.getModDir(mod_id);
            if (modDir == null) continue;
            
            var mod = ZomboidFileSystem.instance.getModInfoForDir(modDir);
            if (mod == null) continue;

            // follow lua engine logic, load common dir first, then version dir
            // so version dir could override common dir
            JavaModInfo jModInfoCommon = JavaModInfo.parse(mod.getCommonDir());
            JavaModInfo jModInfoVersion = JavaModInfo.parse(mod.getVersionDir());

            if (jModInfoCommon != null) {
                jModInfos.add(jModInfoCommon);
                if (jModInfoVersion == null) {
                    // when mod.info is in common dir, but JAR is in version dir
                    jModInfoVersion = JavaModInfo.parseMerged(mod.getCommonDir(), mod.getVersionDir());
                }
            }
            if (jModInfoVersion != null) jModInfos.add(jModInfoVersion);
        }

        System.out.println("[ZB] java mod list to load:");
        printModList(jModInfos);

        // Find the last occurrence index for each package name
        Map<String, Integer> lastPkgNameIndex = new HashMap<>();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            lastPkgNameIndex.put(jModInfo.javaPkgName(), i);
        }

        // Process the list to determine which mods should be skipped
        String myPackageName = Loader.class.getPackage().getName();
        ArrayList<Boolean> shouldSkipList = new ArrayList<>();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            boolean shouldSkip = false;
            
            // Skip ZombieBuddy itself - it's loaded as a Java agent, not through normal mod loading
            if (jModInfo.javaPkgName().equals(myPackageName)) {
                shouldSkip = true;
            }
            
            // Check if this mod's package name appears in a later mod
            Integer lastIndex = lastPkgNameIndex.get(jModInfo.javaPkgName());
            if (lastIndex != null && lastIndex > i) {
                shouldSkip = true;
            }
            
            shouldSkipList.add(shouldSkip);
        }
        
        if (!shouldSkipList.isEmpty()) {
            // Print excluded mods first
            for (int i = 0; i < jModInfos.size(); i++) {
                if (shouldSkipList.get(i)) {
                    JavaModInfo jModInfo = jModInfos.get(i);
                    String reason = "";
                    
                    // Skip ZombieBuddy itself - it's loaded as a Java agent
                    if (jModInfo.javaPkgName().equals(myPackageName)) {
                        reason = " (loaded as Java agent, skipping normal mod loading)";
                        
                        // Read signature and version from JAR manifest
                        File jarFile = jModInfo.getJarFileAsFile();
                        if (jarFile != null && jarFile.exists()) {
                            try {
                                Certificate[] certs = verifyJarAndGetCerts(jarFile);
                                if (certs != null && certs.length > 0) {
                                    System.out.println("[ZB] " + jarFile + " is signed with " + certs.length + " certificate(s)");
                                    for (int certIdx = 0; certIdx < certs.length; certIdx++) {
                                        if (certs[certIdx] instanceof X509Certificate) {
                                            X509Certificate x509Cert = (X509Certificate) certs[certIdx];
                                            byte[] fingerprint = getCertFingerprint(x509Cert, certIdx + 1);
                                            if (fingerprint != null && java.util.Arrays.equals(fingerprint, EXPECTED_FINGERPRINT)) {
                                                // get version from manifest
                                                Manifest manifest = getJarManifest(jarFile);
                                                if (manifest != null) {
                                                    String manifestVersion = manifest.getMainAttributes().getValue("Implementation-Version");
                                                    if (manifestVersion != null) {
                                                        reason += " (version " + manifestVersion + ")";
                                                        
                                                        // Compare with current version
                                                        String currentVersion = ZombieBuddy.getVersion();
                                                        if (isVersionNewer(manifestVersion, currentVersion)) {
                                                            updateSelf(jarFile, manifestVersion);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                System.err.println("[ZB] Error verifying JAR signature: " + e.getMessage());
                            }
                        } else {
                            reason += " (" + (jarFile != null ? jarFile.getAbsolutePath() : "null") + " not found)";
                        }
                    } else {
                        Integer lastIndex = lastPkgNameIndex.get(jModInfo.javaPkgName());
                        if (lastIndex != null && lastIndex > i) {
                            reason = " (package " + jModInfo.javaPkgName() + " is overridden by later mod)";
                        }
                    }
                    
                    System.out.println("[ZB] Excluded: " + jModInfo.modDirectory().getAbsolutePath() + reason);
                }
            }
            
            // Build list of mods that will be loaded
            ArrayList<JavaModInfo> modsToLoad = new ArrayList<>();
            for (int i = 0; i < jModInfos.size(); i++) {
                if (!shouldSkipList.get(i)) {
                    modsToLoad.add(jModInfos.get(i));
                }
            }
            System.out.println("[ZB] java mod list after processing:");
            printModList(modsToLoad);
        }

        // Load only the mods that should be loaded
        for (int i = 0; i < jModInfos.size(); i++) {
            if (!shouldSkipList.get(i)) {
                loadJavaMod(jModInfos.get(i));
            }
        }
    }

    public static void printModList(ArrayList<JavaModInfo> jModInfos) {
        int longestPathLength = 0;
        for (JavaModInfo jModInfo : jModInfos) {
            if (jModInfo.modDirectory().getAbsolutePath().length() > longestPathLength) {
                longestPathLength = jModInfo.modDirectory().getAbsolutePath().length();
            }
        }

        String formatString = "[ZB]     %-" + longestPathLength + "s %s";
        for (JavaModInfo jModInfo : jModInfos) {
            System.out.println(String.format(formatString, jModInfo.modDirectory().getAbsolutePath(), jModInfo.javaPkgName()));
        }
    }

    public static void ApplyPatchesFromPackage(String packageName, ClassLoader modLoader, boolean isPreMain) {
        // Note: isPreMain parameter is reserved for future use
        List<Class<?>> patches = CollectPatches(packageName, modLoader);
        if (patches.isEmpty()) {
            System.out.println("[ZB] no patches in package " + packageName);
            return;
        }

        // Group patches by target class+method
        Map<PatchTarget, List<Class<?>>> advicePatches = new HashMap<>();
        Map<PatchTarget, Class<?>> delegationPatches = new HashMap<>();
        Set<String> classesToWarmUp = new HashSet<>(); // Classes that need warm-up

        for (Class<?> patch : patches) {
            Patch ann = patch.getAnnotation(Patch.class);
            if (ann == null) continue;

            // TODO: show error on game UI (if debug mode is enabled?)
            if (ann.className().equals("zombie.Lua.LuaManager$Exposer") && ann.methodName().equals("exposeAll") && !ann.IKnowWhatIAmDoing()) {
                System.err.println("[ZB] XXX");
                System.err.println("[ZB] XXX don't patch Exposer.exposeAll, use ZombieBuddy.Exposer.exposeClassToLua() instead!");
                System.err.println("[ZB] XXX");
                continue;
            }

            PatchTarget target = new PatchTarget(ann.className(), ann.methodName());
            
            // Track classes that need warm-up
            if (ann.warmUp()) {
                classesToWarmUp.add(ann.className());
            }
            
            if (ann.isAdvice()) {
                advicePatches.computeIfAbsent(target, k -> new ArrayList<>()).add(patch);
            } else {
                if (delegationPatches.containsKey(target)) {
                    System.out.println("[ZB] WARNING: multiple MethodDelegation patches for " + 
                        ann.className() + "." + ann.methodName() + " - only last one will apply!");
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
        for (Class<?> c : g_instrumentation.getAllLoadedClasses()) {
            String className = c.getName();
            if (targetClasses.contains(className)) {
                loadedClasses.add(className);
            }
        }
        if (!loadedClasses.isEmpty() && g_verbosity > 0) {
            System.out.println("[ZB] Already loaded classes to retransform: " + loadedClasses);
        }

        // Warn about MethodDelegation on already-loaded classes (won't work with retransformation)
        for (var entry : delegationPatches.entrySet()) {
            if (loadedClasses.contains(entry.getKey().className())) {
                System.err.println("[ZB] WARNING: MethodDelegation patch for already-loaded class " + 
                    entry.getKey().className() + "." + entry.getKey().methodName() + 
                    " - this may not work! Use isAdvice=true for loaded classes.");
            }
        }

        var bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly();
        if (g_verbosity > 0) {
            if (g_verbosity <= 2) {
                bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut().withTransformationsOnly();
            } else { // 3+
                bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut();
            }
        }

        AgentBuilder builder = new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(bbLogger)
            .with(new AgentBuilder.Listener.Adapter() {
                @Override
                public void onTransformation(net.bytebuddy.description.type.TypeDescription td, 
                                             ClassLoader cl, 
                                             net.bytebuddy.utility.JavaModule jm,
                                             boolean loaded,
                                             net.bytebuddy.dynamic.DynamicType dt) {
                    System.out.println("[ZB] Transformed: " + td.getName() + (loaded ? " (retransformed)" : " (new load)"));
                }

                @Override
                public void onError(String typeName, ClassLoader cl, net.bytebuddy.utility.JavaModule jm,
                                   boolean loaded, Throwable throwable) {
                    System.err.println("[ZB] ERROR transforming " + typeName + ": " + throwable.getMessage());
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
                        System.err.println("[ZB] WARNING: Target class IGNORED: " + className + " (loaded=" + loaded + ")");
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
                        
                        System.out.println("[ZB] patching " + className + "." + methodName + " with " + advices.size() + " advice(s)");
                        
                        // Check if method exists in the type description
                        var methods = td.getDeclaredMethods().filter(SyntaxSugar.methodMatcher(methodName));
                        if (methods.isEmpty()) {
                            System.err.println("[ZB] WARNING: Method " + methodName + " not found in " + td.getName());
                        }
                        
                        // Apply each advice via separate .visit() calls - they stack
                        for (Class<?> adviceClass : advices) {
                            // Transform patch class to replace Patch.* annotations with ByteBuddy's
                            Class<?> transformedClass;
                            try {
                                transformedClass = PatchTransformer.transformPatchClass(adviceClass, g_instrumentation, g_verbosity);
                                if (transformedClass == null) {
                                    System.err.println("[ZB] ERROR: PatchTransformer returned null for " + adviceClass.getName());
                                    continue;
                                }
                            } catch (Exception e) {
                                System.err.println("[ZB] ERROR: Failed to transform patch class " + adviceClass.getName() + ": " + e.getMessage());
                                e.printStackTrace();
                                continue;
                            }
                            
                            // Build method matcher - if advice class has parameter-annotated methods, 
                            // use those to determine which overload to match
                            var methodMatcher = SyntaxSugar.methodMatcher(methodName);
                            
                            // Try to infer parameter types from the advice class methods
                            // This helps ByteBuddy match the correct overload when there are multiple
                            boolean foundMethodSignature = false;
                            for (Method adviceMethod : transformedClass.getDeclaredMethods()) {
                                // Check if this method has advice annotations
                                boolean hasAdviceAnnotation = false;
                                for (java.lang.annotation.Annotation ann : adviceMethod.getAnnotations()) {
                                    String annType = ann.annotationType().getName();
                                    if (annType.contains("Advice$OnMethodEnter") || 
                                        annType.contains("Advice$OnMethodExit")) {
                                        hasAdviceAnnotation = true;
                                        break;
                                    }
                                }
                                
                                if (hasAdviceAnnotation && adviceMethod.getParameterCount() > 0) {
                                    // Build parameter type matcher from the advice method signature
                                    Class<?>[] paramTypes = adviceMethod.getParameterTypes();
                                    // Skip @Return, @Argument, and @AllArguments annotated parameters
                                    // @Return is for return values, @Argument/@AllArguments reference existing params
                                    List<Class<?>> methodParamTypes = new ArrayList<>();
                                    java.lang.annotation.Annotation[][] paramAnns = adviceMethod.getParameterAnnotations();
                                    for (int i = 0; i < paramTypes.length; i++) {
                                        // Check if this parameter has @Return, @Argument, or @AllArguments annotation (skip it)
                                        boolean shouldSkip = false;
                                        for (java.lang.annotation.Annotation ann : paramAnns[i]) {
                                            String annType = ann.annotationType().getName();
                                            if (annType.contains("Advice$Return") || 
                                                annType.contains("Advice$Argument") ||
                                                annType.contains("Advice$AllArguments")) {
                                                shouldSkip = true;
                                                break;
                                            }
                                        }
                                        if (!shouldSkip) {
                                            methodParamTypes.add(paramTypes[i]);
                                        }
                                    }
                                    
                                    if (!methodParamTypes.isEmpty()) {
                                        // Match method with these parameter types
                                        methodMatcher = SyntaxSugar.methodMatcher(methodName)
                                            .and(ElementMatchers.takesArguments(methodParamTypes.toArray(new Class<?>[0])));
                                        foundMethodSignature = true;
                                        break; // Use first matching method's signature
                                    } else if (methodParamTypes.isEmpty() && adviceMethod.getParameterCount() > 0) {
                                        // All parameters are @Argument, @AllArguments, or @Return
                                        // Check if we have @AllArguments - if so, we can't infer signature, use name-based matching
                                        boolean hasAllArguments = false;
                                        for (int i = 0; i < paramAnns.length; i++) {
                                            for (java.lang.annotation.Annotation ann : paramAnns[i]) {
                                                String annType = ann.annotationType().getName();
                                                if (annType.contains("Advice$AllArguments")) {
                                                    hasAllArguments = true;
                                                    break;
                                                }
                                            }
                                            if (hasAllArguments) break;
                                        }
                                        
                                        if (hasAllArguments) {
                                            // @AllArguments doesn't provide signature info - use name-based matching
                                            if (g_verbosity > 0) {
                                                System.out.println("[ZB] Using name-based matching for " + methodName + " (has @AllArguments)");
                                            }
                                            foundMethodSignature = true;
                                            break;
                                        }
                                        
                                        // Check if all parameters are @Return - if so, use name-based matching
                                        boolean allReturn = true;
                                        for (int i = 0; i < paramAnns.length; i++) {
                                            boolean hasReturn = false;
                                            for (java.lang.annotation.Annotation ann : paramAnns[i]) {
                                                String annType = ann.annotationType().getName();
                                                if (annType.contains("Advice$Return")) {
                                                    hasReturn = true;
                                                    break;
                                                }
                                            }
                                            if (!hasReturn) {
                                                allReturn = false;
                                                break;
                                            }
                                        }
                                        
                                        if (allReturn && paramAnns.length > 0) {
                                            // All parameters are @Return - use name-based matching (method has no parameters)
                                            if (g_verbosity > 0) {
                                                System.out.println("[ZB] Using name-based matching for " + methodName + " (all parameters are @Return)");
                                            }
                                            foundMethodSignature = true;
                                            break;
                                        }
                                        
                                        // Try to infer signature from @Argument annotations
                                        // Parameters with @Argument are in order, so we can infer the signature
                                        List<Class<?>> inferredTypes = new ArrayList<>();
                                        for (int i = 0; i < paramAnns.length; i++) {
                                            boolean isArgument = false;
                                            boolean isReturn = false;
                                            for (java.lang.annotation.Annotation ann : paramAnns[i]) {
                                                String annType = ann.annotationType().getName();
                                                if (annType.contains("Advice$Argument")) {
                                                    isArgument = true;
                                                    Class<?> paramType = paramTypes[i];
                                                    if (paramType.isArray()) {
                                                        // Array type means it's modifiable - use the component type for matching
                                                        inferredTypes.add(paramType.getComponentType());
                                                    } else {
                                                        inferredTypes.add(paramType);
                                                    }
                                                    break;
                                                } else if (annType.contains("Advice$Return")) {
                                                    isReturn = true;
                                                    break;
                                                }
                                            }
                                            // If this parameter is not @Argument or @Return, include it
                                            if (!isArgument && !isReturn) {
                                                inferredTypes.add(paramTypes[i]);
                                            }
                                        }
                                        if (!inferredTypes.isEmpty()) {
                                            methodMatcher = SyntaxSugar.methodMatcher(methodName)
                                                .and(ElementMatchers.takesArguments(inferredTypes.toArray(new Class<?>[0])));
                                            System.out.println("[ZB] Inferred method signature for " + methodName + 
                                                " from @Argument annotations: " + inferredTypes);
                                            foundMethodSignature = true;
                                            break;
                                        }
                                        // If we still can't infer, just use name-based matching
                                        System.out.println("[ZB] Could not infer signature from @Argument annotations, using name-based matching for " + methodName);
                                        foundMethodSignature = true;
                                        break;
                                    }
                                }
                            }
                            
                            // If we couldn't infer signature and there are multiple overloads, log a warning
                            if (!foundMethodSignature && g_verbosity > 0) {
                                System.out.println("[ZB] Could not infer method signature for " + methodName + 
                                    " from advice class " + transformedClass.getName() + 
                                    " - using name-based matching only");
                            }
                            
                            try {
                                result = result.visit(Advice.to(transformedClass).on(methodMatcher));
                                if (g_verbosity > 1) {
                                    System.out.println("[ZB] Applied advice to " + className + "." + methodName);
                                }
                            } catch (Exception e) {
                                System.err.println("[ZB] ERROR: Failed to apply advice to " + className + "." + methodName + ": " + e.getMessage());
                                if (g_verbosity > 0) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                    
                    // Apply MethodDelegation patches (only one per method)
                    for (var entry : methodDelegations.entrySet()) {
                        String methodName = entry.getKey();
                        Class<?> delegationClass = entry.getValue();
                        
                        System.out.println("[ZB] patching " + className + "." + methodName + " with delegation");
                        
                        result = result
                            .method(SyntaxSugar.methodMatcher(methodName))
                            .intercept(MethodDelegation.to(delegationClass));
                    }
                    
                    return result;
                });
        }
        
        builder.installOn(g_instrumentation);

        // Warm up classes _AFTER_ installing the agent builder
        for (String className : classesToWarmUp) {
            System.out.println("[ZB] warming up class: " + className);
            try {
                Class<?> cls = Class.forName(className);
                builder = builder.warmUp(cls);
            } catch (ClassNotFoundException e) {
                System.err.println("[ZB] Could not find class for warm-up: " + className);
            } catch (Exception e) {
                System.err.println("[ZB] Error warming up class " + className + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public static List<Class<?>> CollectPatches(String packageName, ClassLoader modLoader) {
        List<Class<?>> patches = new ArrayList<>();

        var classGraph = new ClassGraph()
                .enableAllInfo()              // Scan everything (annotations, methods, etc.)
                .acceptPackages(packageName); // Limit scan to package

        if (modLoader != null) {
            classGraph = classGraph.overrideClassLoaders(modLoader);
        } else {
            // When modLoader is null, we're scanning the system classloader
            // Explicitly add known JARs so ClassGraph can find classes in dynamically added JARs
            ArrayList<String> jarPaths = new ArrayList<>();
            
            // Always include the current JAR (ZombieBuddy's own JAR) so ClassGraph can scan it
            // This is especially important on Windows with fat JARs
            File currentJar = getCurrentJarFile();
            if (currentJar != null && currentJar.exists()) {
                jarPaths.add(currentJar.getAbsolutePath());
            }
            
            // Also add any other known JARs
            if (!g_known_jars.isEmpty()) {
                for (File jar : g_known_jars) {
                    String jarPath = jar.getAbsolutePath();
                    // Avoid duplicates
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
            // Log the number of classes scanned
            int totalClassesScanned = scanResult.getAllClasses().size();
            System.out.println("[ZB] Scanned " + totalClassesScanned + " classes in package " + packageName);

            // Find all classes annotated with @Patch
            for (ClassInfo classInfo : scanResult.getClassesWithAnnotation(Patch.class.getName())) {
                try {
                    Class<?> patchClass = classInfo.loadClass();
                    System.out.println("[ZB] Found patch class: " + patchClass.getName());
                    patches.add(patchClass);
                } catch (Exception e) {
                    System.err.println("[ZB] Error loading patch class " + classInfo.getName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return patches;
    }

    public static void loadJavaMod(JavaModInfo modInfo) {
        System.out.println("[ZB] ------------------------------------------- loading Java mod: " + modInfo.modDirectory());
        
        // Load JAR file
        File jarFile = modInfo.getJarFileAsFile();
        if (jarFile == null) {
            System.err.println("[ZB] Error! No JAR file specified for mod: " + modInfo.modDirectory());
            System.out.println("[ZB] -------------------------------------------");
            return;
        }
        
        try {
            if (jarFile.exists()) {
                if (g_known_jars.contains(jarFile)) {
                    System.out.println("[ZB] " + jarFile + " already added, skipping.");
                    System.out.println("[ZB] -------------------------------------------");
                    return;
                } else {
                    // Validate that JAR contains the specified package
                    if (!validatePackageInJar(jarFile, modInfo.javaPkgName())) {
                        System.err.println("[ZB] Error! JAR does not contain package " + modInfo.javaPkgName() + ": " + jarFile);
                        System.out.println("[ZB] -------------------------------------------");
                        return;
                    }
                    
                    JarFile jf = new JarFile(jarFile);
                    g_instrumentation.appendToSystemClassLoaderSearch(jf);
                    g_known_jars.add(jarFile);
                    System.out.println("[ZB] added to classpath: " + jarFile);
                }
            } else {
                System.err.println("[ZB] classpath not found: " + jarFile);
                System.out.println("[ZB] -------------------------------------------");
                return;
            }
        } catch (Exception e) {
            System.err.println("[ZB] Error! invalid classpath: " + jarFile + " " + e);
            System.out.println("[ZB] -------------------------------------------");
            return;
        }
        
        // Load and invoke Main class (optional)
        String mainClassName = modInfo.getMainClassName();
        if (mainClassName != null) {
            if (g_known_classes.contains(mainClassName)) {
                System.out.println("[ZB] Java class " + mainClassName + " already loaded, skipping.");
            } else {
                g_known_classes.add(mainClassName);

                System.out.println("[ZB] loading class " + mainClassName);
                Class<?> cls = null;
                try {
                    cls = Class.forName(mainClassName);
                    try_call_main(cls);
                    System.out.println("[ZB] loaded " + mainClassName);
                } catch (ClassNotFoundException e) {
                    // Main class is optional - if it doesn't exist, that's fine
                    System.out.println("[ZB] Main class " + mainClassName + " not found (optional, skipping)");
                } catch (Exception e) {
                    System.err.println("[ZB] failed to load Java class " + mainClassName + ": " + e);
                }
            }
        }
        
        // Always apply patches from the package, regardless of whether Main class exists
        ApplyPatchesFromPackage(modInfo.javaPkgName(), null, false);
        
        System.out.println("[ZB] -------------------------------------------");
    }
    
    /**
     * Prints detailed information about an X.509 certificate including fingerprints.
     * @param cert The X.509 certificate to print
     * @param certNumber The certificate number (for display purposes)
     */
    private static byte[] getCertFingerprint(X509Certificate cert, int certNumber) {
        if (g_verbosity > 0) {
            System.out.println("[ZB]   Certificate " + certNumber + ":");
            System.out.println("[ZB]     Subject: " + cert.getSubjectX500Principal().getName());
            System.out.println("[ZB]     Issuer: " + cert.getIssuerX500Principal().getName());
            System.out.println("[ZB]     Serial Number: " + cert.getSerialNumber().toString(16).toUpperCase());
            System.out.println("[ZB]     Valid From: " + cert.getNotBefore());
            System.out.println("[ZB]     Valid Until: " + cert.getNotAfter());
        }
        
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] sha256Bytes = sha256.digest(cert.getEncoded());
            if (g_verbosity > 0) {
                String sha256Fingerprint = bytesToHex(sha256Bytes);
                System.out.println("[ZB]     SHA-256 Fingerprint: " + sha256Fingerprint);
            }
            return sha256Bytes;
        } catch (Exception e) {
            System.err.println("[ZB]     Error computing certificate fingerprints: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Converts a hexadecimal string with colons to a byte array.
     * @param hexString The hexadecimal string (e.g., "A7:75:10:1B:...")
     * @return The byte array representation
     */
    private static byte[] hexToBytes(String hexString) {
        // Remove colons and convert to bytes
        String cleanHex = hexString.replace(":", "").replace(" ", "");
        if (cleanHex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }
        byte[] bytes = new byte[cleanHex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int index = i * 2;
            bytes[i] = (byte) Integer.parseInt(cleanHex.substring(index, index + 2), 16);
        }
        return bytes;
    }
    
    /**
     * Compares two semantic version strings to determine if version1 is newer than version2.
     * Supports formats like "1.0.0", "1.2.3", "2.0.0-beta", etc.
     * @param version1 The first version to compare
     * @param version2 The second version to compare
     * @return true if version1 is newer than version2, false otherwise
     */
    private static boolean isVersionNewer(String version1, String version2) {
        if (version1 == null || version2 == null) {
            return false;
        }
        
        // Handle "unknown" version
        if ("unknown".equals(version2)) {
            return true; // Any version is newer than unknown
        }
        
        try {
            // Split versions by dots and compare each segment
            String[] v1Parts = version1.split("\\.");
            String[] v2Parts = version2.split("\\.");
            
            int maxLength = Math.max(v1Parts.length, v2Parts.length);
            
            for (int i = 0; i < maxLength; i++) {
                int v1Part = 0;
                int v2Part = 0;
                
                // Parse numeric part (ignore suffixes like "-beta", "-alpha", etc.)
                if (i < v1Parts.length) {
                    String v1Str = v1Parts[i].replaceAll("[^0-9].*", "");
                    if (!v1Str.isEmpty()) {
                        v1Part = Integer.parseInt(v1Str);
                    }
                }
                
                if (i < v2Parts.length) {
                    String v2Str = v2Parts[i].replaceAll("[^0-9].*", "");
                    if (!v2Str.isEmpty()) {
                        v2Part = Integer.parseInt(v2Str);
                    }
                }
                
                if (v1Part > v2Part) {
                    return true;
                } else if (v1Part < v2Part) {
                    return false;
                }
                // If equal, continue to next segment
            }
            
            // If all segments are equal, check if one has more segments
            if (v1Parts.length > v2Parts.length) {
                return true;
            }
            
            return false; // versions are equal or version1 is not newer
        } catch (Exception e) {
            // If parsing fails, do string comparison as fallback
            return version1.compareTo(version2) > 0;
        }
    }
    
    /**
     * Converts a byte array to a hexadecimal string with colons (standard fingerprint format).
     * @param bytes The byte array to convert
     * @return The hexadecimal string representation
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(":");
            }
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

    public static Manifest getJarManifest(File jarFile){
        if (jarFile == null) {
            return null;
        }
        try (JarFile jar = new JarFile(jarFile, true)) {
            return jar.getManifest();
        } catch (Exception e) {
            System.err.println("[ZB] Error getting JAR manifest: " + e);
            return null;
        }
    }
    
    public static Certificate[] verifyJarAndGetCerts(File jarPath) throws Exception {
        Manifest mf = getJarManifest(jarPath);
        if (mf == null)
            return null;

        try (JarFile jar = new JarFile(jarPath, true)) {
            Certificate[] signer = null;
    
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
    
                try (InputStream is = jar.getInputStream(e)) {
                    // Read the entire entry to force verification.
                    is.readAllBytes();
                }
    
                // If this entry was signed, it will have certs here.
                Certificate[] certs = e.getCertificates();
                if (certs != null) {
                    signer = certs;
                }
            }
    
            if (signer == null)
                throw new SecurityException("No signed entries found");
    
            return signer;
        }
    }    

    public static String getNewVersion() {
        return g_new_version;
    }

    /**
     * Gets the File object representing the JAR file that contains the currently running code.
     * @return The JAR file, or null if not found or not running from a JAR
     */
    public static File getCurrentJarFile() {
        try {
            java.security.CodeSource codeSource = Loader.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                java.net.URL location = codeSource.getLocation();
                if (location != null) {
                    // Convert URL to File, handling file:// URLs
                    java.net.URI uri = location.toURI();
                    File jarFile = new File(uri);
                    if (jarFile.exists() && jarFile.getName().endsWith(".jar")) {
                        return jarFile;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ZB] Error getting current JAR file path: " + e.getMessage());
        }
        return null;
    }
    
    private static void updateSelf(File jarFile, String manifestVersion) {
        File currentJarFile = getCurrentJarFile();
        if (currentJarFile == null) {
            return;
        }
        g_new_version = manifestVersion;
        System.out.println("[ZB] replacing " + currentJarFile + " with " + jarFile);
        try {
            // Try to rename existing JAR to .bak before replacing
            File backupFile = new File(currentJarFile.getAbsolutePath() + ".bak");
            if (currentJarFile.exists()) {
                try {
                    Files.move(currentJarFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[ZB] Renamed existing JAR to " + backupFile);
                } catch (Exception e) {
                    // On Windows, this might fail if JAR is still locked - continue anyway
                    System.out.println("[ZB] Could not rename existing JAR (may be locked): " + e.getMessage());
                }
            }
            
            // Try to replace the current JAR with the new one
            Files.copy(jarFile.toPath(), currentJarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[ZB] Successfully replaced JAR file");
        } catch (Exception e) {
            // If replacement failed (e.g., JAR is locked on Windows), copy to .new as fallback
            System.err.println("[ZB] Error replacing JAR file: " + e.getMessage());
            System.err.println("[ZB] JAR may be locked (e.g., on Windows). Copying to .new file for deferred update...");
            try {
                File newJarFile = new File(currentJarFile.getAbsolutePath() + ".new");
                Files.copy(jarFile.toPath(), newJarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[ZB] Copied new JAR to " + newJarFile + " - update will be applied on next game launch");
            } catch (Exception e2) {
                System.err.println("[ZB] Error copying to .new file: " + e2.getMessage());
                e2.printStackTrace();
            }
            e.printStackTrace();
        }
    }

    /**
     * Validates that a JAR file contains the specified package.
     * @param jarFile The JAR file to check
     * @param packageName The package name to verify (e.g., "com.example.mymod")
     * @return true if the package exists in the JAR, false otherwise
     */
    private static boolean validatePackageInJar(File jarFile, String packageName) {
        if (jarFile == null || packageName == null || packageName.isEmpty()) {
            return false;
        }
        
        try {
            String packagePath = packageName.replace('.', '/');
            
            // Check if any entry in the JAR starts with the package path
            try (JarFile jf = new JarFile(jarFile)) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String entryName = entry.getName();
                    // Check if entry is in the package (either a class file or a directory)
                    // Match entries that start with packagePath + "/" to ensure we match files/dirs in the package
                    // Also match the package directory itself if it exists as an entry
                    if (entryName.startsWith(packagePath + "/") || 
                        entryName.equals(packagePath) || 
                        entryName.equals(packagePath + "/")) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            System.err.println("[ZB] Error validating package in JAR: " + e);
            return false;
        }
    }

    static void try_call_main(Class<?> cls) {
        Method main = null;
        try {
            main = cls.getMethod("main", String[].class);
        } catch (java.lang.NoSuchMethodException e) {
            return;
        } catch (Exception e) {
            System.err.println("[ZB] " + cls + ": error getting main(): " + e);
            return;
        }

        // main cannot be null here if getMethod() succeeded
        try {
            String[] args = {}; // no arguments for now
            main.invoke(null, (Object) args);
            System.out.println("[ZB] " + cls + ": main() invoked successfully");
        } catch (Exception e) {
            System.err.println("[ZB] " + cls + ": error invoking main(): " + e);
        }
    }
}
