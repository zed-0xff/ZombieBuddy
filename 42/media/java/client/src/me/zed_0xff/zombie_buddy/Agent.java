package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import me.zed_0xff.zombie_buddy.patches.experimental.Main;
import zombie.core.Core;

public class Agent {
    public static final Map<String, String> arguments = new HashMap<>();

    public static void premain(String agentArgs, Instrumentation inst) {
        Logger.out.println("[ZB] activating " + ZombieBuddy.getFullVersionString());
        Loader.g_instrumentation = inst;

        if (agentArgs != null && !agentArgs.isEmpty()) {
            Logger.out.println("[ZB] agentArgs: " + agentArgs);
            String[] args = agentArgs.split(",");
            for (String arg : args) {
                String[] kv = arg.split("=", 2);
                String key = kv[0].toLowerCase();
                String value = (kv.length > 1) ? kv[1] : "";

                arguments.put(key, value);
            }
        }

        if( arguments.containsKey("verbosity")) {
            try {
                Loader.g_verbosity = Integer.parseInt(arguments.get("verbosity"));
                Logger.err.println("[ZB] set verbosity to " + Loader.g_verbosity);
            } catch (NumberFormatException e) {
                Logger.err.println("[ZB] invalid verbosity value: " + arguments.get("verbosity"));
            }
        }

        // Check ZB_VERBOSITY environment variable - it overrides command line value
        String envVerbosity = System.getenv("ZB_VERBOSITY");
        if (envVerbosity != null && !envVerbosity.isEmpty()) {
            try {
                Loader.g_verbosity = Integer.parseInt(envVerbosity);
                Logger.err.println("[ZB] set verbosity to " + Loader.g_verbosity + " from ZB_VERBOSITY environment variable");
            } catch (NumberFormatException e) {
                Logger.err.println("[ZB] invalid ZB_VERBOSITY value: " + envVerbosity);
            }
        }

        Loader.ApplyPatchesFromPackage(ZombieBuddy.class.getPackage().getName() + ".patches", null, true);

        // Load experimental patches if enabled
        if (arguments.containsKey("experimental")) {
            Loader.ApplyPatchesFromPackage(ZombieBuddy.class.getPackage().getName() + ".patches.experimental", null, true);
        }
        
        if( arguments.containsKey("patches_jar")) {
            // Support multiple JARs separated by semicolon
            // Each entry must be in format <path>:<package_name>
            List<PatchesJarEntry> patchesJarEntries = new ArrayList<>();
            String[] entries = arguments.get("patches_jar").split(";");
            for (String entry : entries) {
                entry = entry.trim();
                if (!entry.isEmpty()) {
                    String[] parts = entry.split(":", 2);
                    if (parts.length != 2) {
                        Logger.err.println(
                                "[ZB] patches_jar entry must be in format <path>:<package_name>, got: " + entry);
                        continue;
                    }
                    String jarPath = parts[0].trim();
                    String packageName = parts[1].trim();
                    if (jarPath.isEmpty() || packageName.isEmpty()) {
                        Logger.err.println(
                                "[ZB] patches_jar entry must have non-empty path and package name, got: " + entry);
                        continue;
                    }
                    patchesJarEntries.add(new PatchesJarEntry(jarPath, packageName));
                }
            }

            for (PatchesJarEntry entry : patchesJarEntries) {
                loadPatchesFromJar(entry.jarPath, entry.packageName);
            }
        }
        
        // Register onGameInitComplete hooks based on arguments
        if (arguments.containsKey("exit_after_game_init")) {
            Hooks.register("onGameInitComplete", Agent::onGameInitCompleteExitHook);
        }

        Logger.out.println("[ZB] Agent installed.");
    }

    /** Called from Hooks when exit_after_game_init was requested. */
    private static void onGameInitCompleteExitHook() {
        if (arguments.containsKey("exit_after_game_init")) {
            Logger.out.println("[ZB] Exiting after game init as requested.");
            Core.getInstance().quit();
        }
    }
    
    private static void loadPatchesFromJar(String jarPath, String packageName) {
        try {
            File jarFile = new File(jarPath);
            if (!jarFile.exists()) {
                Logger.err.println("[ZB] patches_jar file not found: " + jarPath);
                return;
            }
            
            // Add JAR to classpath
            JarFile jf = new JarFile(jarFile);
            Loader.g_instrumentation.appendToSystemClassLoaderSearch(jf);
            Loader.g_known_jars.add(jarFile);
            Logger.out.println("[ZB] added patches JAR to classpath: " + jarFile);
            
            // Scan for patches in the specified package
            Loader.ApplyPatchesFromPackage(packageName, null, true);

        } catch (Exception e) {
            Logger.err.println("[ZB] Error loading patches from JAR " + jarPath + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static class PatchesJarEntry {
        final String jarPath;
        final String packageName;
        
        PatchesJarEntry(String jarPath, String packageName) {
            this.jarPath = jarPath;
            this.packageName = packageName;
        }
    }

    public static void dumpEnv() {
        Logger.out.println("[ZB] environment variables:");
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
            Logger.out.println("    " + String.format(keyFormat, entry.getKey()) + " = " + String.format(valueFormat, entry.getValue()));
        }
    }
}
