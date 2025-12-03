package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.lang.ClassLoader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.*;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

public class ZombieBuddy {
    public static Instrumentation g_instrumentation;
    //public static List<Method> lua_init_hooks = new ArrayList<>();

    static Set<String> g_known_classes = new HashSet<>();
    static DynamicClassLoader g_modLoader = null;

    public static class DynamicClassLoader extends URLClassLoader {
        public DynamicClassLoader(java.net.URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public void addURL(java.net.URL url) {
            super.addURL(url);
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[ZB] installing Agent ..");

        g_instrumentation = inst;
        g_modLoader = new DynamicClassLoader( new java.net.URL[] {}, ZombieBuddy.class.getClassLoader() );
        ApplyPatchesFromPackage(ZombieBuddy.class.getPackage().getName() + ".patches", null);

        System.out.println("[ZB] Agent installed.");
    }

    public static void ApplyPatchesFromPackage(String packageName, ClassLoader modLoader) {
        List<Class<?>> patches = CollectPatches(packageName, modLoader);
        if (patches.isEmpty()) {
            System.out.println("[ZB] no patches in package " + packageName);
            return;
        }

        AgentBuilder builder = new AgentBuilder.Default()
            .with(AgentBuilder.Listener.StreamWriting.toSystemOut().withTransformationsOnly()); // logs transformations AND errors

        for (Class<?> patch : patches) {
            Patch ann = patch.getAnnotation(Patch.class);
            if (ann == null) continue;

            System.out.println("[ZB] patching " + ann.className() + "." + ann.methodName() + " ..");

            builder = builder
                .type(ElementMatchers.named(ann.className()))
                .transform((bl, td, cl, mo, pd) ->
                        bl.method(ElementMatchers.named(ann.methodName()))
                        .intercept(ann.isAdvice() ? Advice.to(patch) : MethodDelegation.to(patch))
                        );
        }

        builder.installOn(g_instrumentation);
    }

    public static List<Class<?>> CollectPatches(String packageName, ClassLoader modLoader) {
        List<Class<?>> patches = new ArrayList<>();

        var classGraph = new ClassGraph()
                .enableAllInfo()             // Scan everything (annotations, methods, etc.)
                .acceptPackages(packageName); // Limit scan to package

        if (modLoader != null)
            classGraph = classGraph.overrideClassLoaders(modLoader);

        try (ScanResult scanResult = classGraph.scan()) {

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
                if (prefix.equals("javaclasspath=")) {
                    String classPath = line.substring(14).trim();
                    if (!classPath.isEmpty()) {
                        File f = new File(dir, classPath);
                        try {
                            if (f.exists()) {
                                var url = f.toURI().toURL();
                                if (!java.util.Arrays.asList(g_modLoader.getURLs()).contains(url)) {
                                    g_modLoader.addURL(url);
                                    System.out.println("[ZB] added classpath: " + f);
                                }
                            } else {
                                System.err.println("[ZB] classpath not found: " + f);
                            }
                        } catch (Exception e) {
                            System.err.println("[ZB] invalid classpath URL: " + f + " " + e);
                        }
                    }
                } else if (prefix.equals("javamainclass=")) {
                    String mainClass = line.substring(14).trim();
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
                cls = Class.forName(clsName, true, g_modLoader);
            } catch (Exception e) {
                System.err.println("[ZB] failed to load Java class " + clsName + ": " + e);
                continue;
            }

            try_call_main(cls);
            ApplyPatchesFromPackage(cls.getPackageName(), g_modLoader);
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
