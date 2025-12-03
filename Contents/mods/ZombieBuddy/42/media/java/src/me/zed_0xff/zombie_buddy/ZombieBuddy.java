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

public class ZombieBuddy {
    public static Instrumentation g_instrumentation;
    public static int g_verbosity = 0;
    public static boolean g_exit_after_game_load = false;

    static Set<String> g_known_classes = new HashSet<>();
    static Set<File> g_known_jars = new HashSet<>();

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[ZB] installing Agent ..");
        if (agentArgs != null && !agentArgs.isEmpty()) {
            System.out.println("[ZB] agentArgs: " + agentArgs);
            String[] args = agentArgs.split(",");
            for (String arg : args) {
                String[] kv = arg.split("=", 2);
                String key = kv[0].toLowerCase();
                String value = (kv.length > 1) ? kv[1] : "";

                switch (key) {
                    case "verbosity":
                        try {
                            g_verbosity = Integer.parseInt(value);
                            System.err.println("[ZB] set verbosity to " + g_verbosity);
                        } catch (NumberFormatException e) {
                            System.err.println("[ZB] invalid verbosity value: " + value);
                        }
                        break;

                    case "exit_after_game_load":
                        g_exit_after_game_load = true;
                        System.err.println("[ZB] will exit after game load");
                        break;

                    default:
                        System.err.println("[ZB] unknown agent argument: " + key);
                        break;
                }
            }
        }

        g_instrumentation = inst;
        ApplyPatchesFromPackage(ZombieBuddy.class.getPackage().getName() + ".patches", null);

        System.out.println("[ZB] Agent installed.");
    }

    // Key for grouping patches by class+method
    private static record PatchTarget(String className, String methodName) {}

    public static void ApplyPatchesFromPackage(String packageName, ClassLoader modLoader) {
        List<Class<?>> patches = CollectPatches(packageName, modLoader);
        if (patches.isEmpty()) {
            System.out.println("[ZB] no patches in package " + packageName);
            return;
        }

        // Group patches by target class+method
        Map<PatchTarget, List<Class<?>>> advicePatches = new HashMap<>();
        Map<PatchTarget, Class<?>> delegationPatches = new HashMap<>();

        for (Class<?> patch : patches) {
            Patch ann = patch.getAnnotation(Patch.class);
            if (ann == null) continue;

            PatchTarget target = new PatchTarget(ann.className(), ann.methodName());
            
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
            if (targetClasses.contains(c.getName())) {
                loadedClasses.add(c.getName());
            }
        }
        if (!loadedClasses.isEmpty()) {
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

        AgentBuilder builder = new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly())
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
                    // Only log if it's a class we're targeting
                    if (targetClasses.contains(td.getName())) {
                        System.out.println("[ZB] Ignored (unexpected): " + td.getName());
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
                        
                        // Apply each advice via separate .visit() calls - they stack
                        for (Class<?> adviceClass : advices) {
                            result = result.visit(Advice.to(adviceClass).on(ElementMatchers.named(methodName))); // TODO: use SyntaxSugar.name2matcher
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

    public static void maybe_load_java(String dir) {
        if (dir == null || dir.isEmpty())
            return;

        String modinfo_fname = dir + File.separator + "mod.info";
        File modinfo_file = new File(modinfo_fname);
        if (!modinfo_file.exists()){
            // System.out.println("[ZB] no mod.info in " + dir);
            return;
        }

        ArrayList<String> mainClasses = new ArrayList<>();

        try (var reader = new java.io.BufferedReader(new java.io.FileReader(modinfo_file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 15)
                    continue;

                String prefix = line.substring(0, 14).toLowerCase();
                if (prefix.startsWith("javajarfile=")) {
                    String classPath = line.split("=", 2)[1].trim();
                    if (!classPath.isEmpty()) {
                        if (!classPath.endsWith(".jar")) {
                            System.err.println("[ZB] Error! javaJarFile entry must end with \".jar\": " + classPath);
                            continue;
                        }

                        File f = new File(dir, classPath);
                        try {
                            if (f.exists()) {
                                if (g_known_jars.contains(f)) {
                                    System.out.println("[ZB] " + f + " already added, skipping.");
                                    continue;
                                }

                                JarFile jarFile = new JarFile(f);
                                g_instrumentation.appendToSystemClassLoaderSearch(jarFile);
                                g_known_jars.add(f);
                                System.out.println("[ZB] added to classpath: " + f);
                            } else {
                                System.err.println("[ZB] classpath not found: " + f);
                            }
                        } catch (Exception e) {
                            System.err.println("[ZB] Error! invalid classpath: " + f + " " + e);
                        }
                    }
                } else if (prefix.startsWith("javamainclass=")) {
                    String mainClass = line.split("=", 2)[1].trim();
                    if (!mainClass.isEmpty())
                        mainClasses.add(mainClass);
                }
            }
        } catch (Exception e) {
            System.err.println("[ZB] error reading " + modinfo_fname + ": " + e);
            return;
        }
        
        // Load and invoke main classes
        for (String clsName : mainClasses) {
            if (g_known_classes.contains(clsName)) {
                System.out.println("[ZB] Java class " +  clsName + " already loaded, skipping.");
                continue;
            } 
            g_known_classes.add(clsName);

            System.out.println("[ZB] loading class " + clsName);
            Class<?> cls = null;
            try {
                cls = Class.forName(clsName);
            } catch (Exception e) {
                System.err.println("[ZB] failed to load Java class " + clsName + ": " + e);
                continue;
            }

            try_call_main(cls);
            ApplyPatchesFromPackage(cls.getPackageName(), null);
            //register_hooks(cls);

            System.out.println("[ZB] loaded " + clsName);
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

    // public static void register_hooks(Class<?> cls) {
    //     Method onLuaInit = null;
    //     try {
    //         onLuaInit = cls.getMethod("OnLuaInit");
    //     } catch (java.lang.NoSuchMethodException e) {
    //         return;
    //     } catch (Exception e) {
    //         System.err.println("[ZB] " + cls + ": error getting OnLuaInit(): " + e);
    //         return;
    //     }
    //     if (onLuaInit != null) {
    //         lua_init_hooks.add(onLuaInit);
    //     }
    // }
    // 
    // public static void OnLuaInit() {
    //     for (Method hook : lua_init_hooks) {
    //         try {
    //             hook.invoke(null);
    //             System.out.println("[ZB] OnLuaInit: invoked " + hook.getDeclaringClass().getName());
    //         } catch (Exception e) {
    //             System.err.println("[ZB] OnLuaInit: error invoking " + hook.getDeclaringClass().getName() + ": " + e);
    //         }
    //     }
    // }
}
