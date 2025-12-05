package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.lang.ClassLoader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.jar.JarFile;
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

            if (jModInfoCommon != null) jModInfos.add(jModInfoCommon);
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
        ArrayList<Boolean> shouldSkipList = new ArrayList<>();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            boolean shouldSkip = false;
            
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
                    
                    Integer lastIndex = lastPkgNameIndex.get(jModInfo.javaPkgName());
                    if (lastIndex != null && lastIndex > i) {
                        reason = " (package " + jModInfo.javaPkgName() + " is overridden by later mod)";
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
            if (g_verbosity == 1) {
                bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut().withTransformationsOnly();
            } else {
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
                .type(SyntaxSugar.name2matcher(className))
                .transform((bl, td, cl, mo, pd) -> {
                    var result = bl;
                    
                    // Apply stacked Advice patches per method
                    for (var entry : methodAdvices.entrySet()) {
                        String methodName = entry.getKey();
                        List<Class<?>> advices = entry.getValue();
                        
                        System.out.println("[ZB] patching " + className + "." + methodName + " with " + advices.size() + " advice(s)");
                        
                        // Check if method exists in the type description
                        var methods = td.getDeclaredMethods().filter(ElementMatchers.named(methodName));
                        if (methods.isEmpty()) {
                            System.err.println("[ZB] WARNING: Method " + methodName + " not found in " + td.getName());
                        }
                        
                        // Apply each advice via separate .visit() calls - they stack
                        for (Class<?> adviceClass : advices) {
                            // Transform patch class to replace Patch.* annotations with ByteBuddy's
                            Class<?> transformedClass = PatchTransformer.transformPatchClass(adviceClass, g_instrumentation, g_verbosity);
                            
                            // Build method matcher - if advice class has parameter-annotated methods, 
                            // use those to determine which overload to match
                            net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> methodMatcher = 
                                ElementMatchers.named(methodName);
                            
                            // Try to infer parameter types from the advice class methods
                            // This helps ByteBuddy match the correct overload when there are multiple
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
                                    // Skip @Return annotated parameters (they're for return values, not method params)
                                    List<Class<?>> methodParamTypes = new ArrayList<>();
                                    java.lang.annotation.Annotation[][] paramAnns = adviceMethod.getParameterAnnotations();
                                    for (int i = 0; i < paramTypes.length; i++) {
                                        // Check if this parameter has @Return annotation (skip it)
                                        boolean isReturnParam = false;
                                        for (java.lang.annotation.Annotation ann : paramAnns[i]) {
                                            if (ann.annotationType().getName().contains("Advice$Return")) {
                                                isReturnParam = true;
                                                break;
                                            }
                                        }
                                        if (!isReturnParam) {
                                            methodParamTypes.add(paramTypes[i]);
                                        }
                                    }
                                    
                                    if (!methodParamTypes.isEmpty()) {
                                        // Match method with these parameter types
                                        methodMatcher = ElementMatchers.named(methodName)
                                            .and(ElementMatchers.takesArguments(methodParamTypes.toArray(new Class<?>[0])));
                                        break; // Use first matching method's signature
                                    }
                                }
                            }
                            
                            result = result.visit(Advice.to(transformedClass).on(methodMatcher));
                        }
                    }
                    
                    // Apply MethodDelegation patches (only one per method)
                    for (var entry : methodDelegations.entrySet()) {
                        String methodName = entry.getKey();
                        Class<?> delegationClass = entry.getValue();
                        
                        System.out.println("[ZB] patching " + className + "." + methodName + " with delegation");
                        
                        result = result
                            .method(ElementMatchers.named(methodName)) // TODO: use SyntaxSugar.name2matcher
                            .intercept(MethodDelegation.to(delegationClass));
                    }
                    
                    return result;
                });
        }
        
        builder.installOn(g_instrumentation);

        for (String className : classesToWarmUp) {
            System.out.println("[ZB] warming up class: " + className);
            try {
                Class<?> cls = Class.forName(className);
                builder = builder.warmUp(cls);
            } catch (ClassNotFoundException e) {
                System.err.println("[ZB] Could not find class for warm-up: " + className);
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
            if (!g_known_jars.isEmpty()) {
                String[] jarPaths = g_known_jars.stream()
                        .map(File::getAbsolutePath)
                        .toArray(String[]::new);
                classGraph = classGraph.overrideClasspath((Object[]) jarPaths);
            }
        }

        try (ScanResult scanResult = classGraph.scan()) {
            // Log the number of classes scanned
            int totalClassesScanned = scanResult.getAllClasses().size();
            System.out.println("[ZB] Scanned " + totalClassesScanned + " classes in package " + packageName);

            // Find all classes annotated with @Patch
            for (ClassInfo classInfo : scanResult.getClassesWithAnnotation(Patch.class.getName())) {
                Class<?> patchClass = classInfo.loadClass();
                System.out.println("[ZB] Found patch class: " + patchClass.getName());
                patches.add(patchClass);
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
        try {
            if (jarFile.exists()) {
                if (g_known_jars.contains(jarFile)) {
                    System.out.println("[ZB] " + jarFile + " already added, skipping.");
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
        
        // Always apply patches from the package, regardless of whether Main class exists
        ApplyPatchesFromPackage(modInfo.javaPkgName(), null, false);
        
        System.out.println("[ZB] -------------------------------------------");
    }
    
    /**
     * Validates that a JAR file contains the specified package.
     * @param jarFile The JAR file to check
     * @param packageName The package name to verify (e.g., "com.example.mymod")
     * @return true if the package exists in the JAR, false otherwise
     */
    private static boolean validatePackageInJar(File jarFile, String packageName) {
        try {
            String packagePath = packageName.replace('.', '/');
            
            // Check if any entry in the JAR starts with the package path
            try (JarFile jf = new JarFile(jarFile)) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String entryName = entry.getName();
                    // Check if entry is in the package (either a class file or a directory)
                    if (entryName.startsWith(packagePath + "/") || entryName.equals(packagePath + "/")) {
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

        if (main == null) {
            System.err.println("[ZB] " + cls + ": main == null");
            return;
        }

        try {
            String[] args = {}; // no arguments for now
            main.invoke(null, (Object) args);
            System.out.println("[ZB] " + cls + ": main() invoked successfully");
        } catch (Exception e) {
            System.err.println("[ZB] " + cls + ": error invoking main(): " + e);
        }
    }
}
