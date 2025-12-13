package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

public class Agent {
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[ZB] activating " + ZombieBuddy.getFullVersionString());
        Loader.g_instrumentation = inst;

        List<String> patchesJarPaths = new ArrayList<>();

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
                            Loader.g_verbosity = Integer.parseInt(value);
                            System.err.println("[ZB] set verbosity to " + Loader.g_verbosity);
                        } catch (NumberFormatException e) {
                            System.err.println("[ZB] invalid verbosity value: " + value);
                        }
                        break;

                    case "exit_after_game_init":
                        Loader.g_exit_after_game_init = true;
                        System.err.println("[ZB] will exit after game init");
                        break;

                    case "dump_env":
                        Loader.g_dump_env = true;
                        dumpEnv();
                        break;

                    case "patches_jar":
                        // Support multiple JARs separated by colon
                        String[] jarPaths = value.split(":");
                        for (String jarPath : jarPaths) {
                            jarPath = jarPath.trim();
                            if (!jarPath.isEmpty()) {
                                patchesJarPaths.add(jarPath);
                            }
                        }
                        System.out.println("[ZB] patches_jar specified: " + value + " (" + patchesJarPaths.size() + " JAR(s))");
                        break;

                    default:
                        System.err.println("[ZB] unknown agent argument: " + key);
                        break;
                }
            }
        }

        Loader.ApplyPatchesFromPackage(ZombieBuddy.class.getPackage().getName() + ".patches", null, true);
        
        // Load patches from external JAR(s) if specified
        for (String jarPath : patchesJarPaths) {
            loadPatchesFromJar(jarPath);
        }
        
        System.out.println("[ZB] Agent installed.");
    }
    
    private static void loadPatchesFromJar(String jarPath) {
        try {
            File jarFile = new File(jarPath);
            if (!jarFile.exists()) {
                System.err.println("[ZB] patches_jar file not found: " + jarPath);
                return;
            }
            
            // Add JAR to classpath
            JarFile jf = new JarFile(jarFile);
            Loader.g_instrumentation.appendToSystemClassLoaderSearch(jf);
            Loader.g_known_jars.add(jarFile);
            System.out.println("[ZB] added patches JAR to classpath: " + jarFile);
            
            // Scan for patches in the JAR - look for packages containing "patches"
            // We'll scan for the testpatches package specifically
            String packageName = "me.zed_0xff.zombie_buddy.testpatches";
            Loader.ApplyPatchesFromPackage(packageName, null, true);
            
        } catch (Exception e) {
            System.err.println("[ZB] Error loading patches from JAR " + jarPath + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void dumpEnv() {
        System.out.println("[ZB] environment variables:");
        // find the longest key and pad all keys to that length
        int maxKeyLength = 0;
        for (var entry : System.getenv().entrySet()) {
            if (entry.getKey().length() > maxKeyLength) {
                maxKeyLength = entry.getKey().length();
            }
        }
        String keyFormat = "%-" + maxKeyLength + "s";
        String valueFormat = "%s";

        for (var entry : System.getenv().entrySet()) {
            System.out.println("    " + String.format(keyFormat, entry.getKey()) + " = " + String.format(valueFormat, entry.getValue()));
        }
    }
}
