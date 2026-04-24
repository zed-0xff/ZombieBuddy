package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import zombie.core.Core;

public class Agent {
    public static final Map<String, String> arguments = new HashMap<>();

    public static void premain(String agentArgs, Instrumentation inst) {
        Logger.info("activating " + ZombieBuddy.getFullVersionString());
        Loader.g_instrumentation = inst;

        if (agentArgs != null && !agentArgs.isEmpty()) {
            Logger.info("agentArgs: " + agentArgs);
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
                Logger.info("set verbosity to " + Loader.g_verbosity);
            } catch (NumberFormatException e) {
                Logger.error("invalid verbosity value: " + arguments.get("verbosity"));
            }
        }

        // Java mod loading policy for unknown/changed JARs.
        // Values: prompt (default), deny-new, allow-all
        // Always lock, even if no arg was passed, so a later-loading Java mod
        // can't be the first to call Loader.setPolicy().
        Loader.setPolicy(arguments.getOrDefault("policy", "prompt"));

        // When false, a missing .zbs sidecar is treated like an invalid signature (except policy=allow-all skips ZBS).
        Loader.g_allowUnsignedMods = Boolean.parseBoolean(arguments.getOrDefault("allow_unsigned_mods", "true"));
        Logger.info("allow_unsigned_mods=" + Loader.g_allowUnsignedMods);

        // Java mod UI: auto (default), swing (Swing batch + TinyFD per-mod), tinyfd, console (stdin/headless).
        Loader.configureApprovalFrontend(arguments.getOrDefault("approval_frontend", ModApprovalFrontends.ARG_AUTO));

        if (arguments.containsKey("batch_approval_timeout")) {
            try {
                int sec = Integer.parseInt(arguments.get("batch_approval_timeout").trim());
                Loader.g_batchApprovalTimeoutSeconds = Math.max(0, sec);
                Logger.info("set batch_approval_timeout to " + Loader.g_batchApprovalTimeoutSeconds + "s (0 = no timeout)");
            } catch (NumberFormatException e) {
                Logger.error("invalid batch_approval_timeout value: " + arguments.get("batch_approval_timeout"));
            }
        }

        // Check ZB_VERBOSITY environment variable - it overrides command line value
        String envVerbosity = System.getenv("ZB_VERBOSITY");
        if (envVerbosity != null && !envVerbosity.isEmpty()) {
            try {
                Loader.g_verbosity = Integer.parseInt(envVerbosity);
                Logger.info("set verbosity to " + Loader.g_verbosity + " from ZB_VERBOSITY environment variable");
            } catch (NumberFormatException e) {
                Logger.error("invalid ZB_VERBOSITY value: " + envVerbosity);
            }
        }

        Loader.ApplyPatchesFromPackage(ZombieBuddy.class.getPackage().getName() + ".patches", null, true);

        // Load experimental patches if enabled
        if (isExperimental()) {
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
                        Logger.error(
                                "patches_jar entry must be in format <path>:<package_name>, got: " + entry);
                        continue;
                    }
                    String jarPath = parts[0].trim();
                    String packageName = parts[1].trim();
                    if (jarPath.isEmpty() || packageName.isEmpty()) {
                        Logger.error(
                                "patches_jar entry must have non-empty path and package name, got: " + entry);
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
            Callbacks.onGameInitComplete.register(Agent::exitOnGameInitComplete);
        }

        // Expose classes with @Exposer.LuaClass annotation from main package
        Exposer.exposeAnnotatedClasses(ZombieBuddy.class.getPackage().getName());

        if (arguments.containsKey("expose_classes")) {
            String[] classes = arguments.get("expose_classes").split(",");
            for (String className : classes) {
                className = className.trim();
                if (!className.isEmpty()) {
                    Exposer.exposeClass(className);
                }
            }
        }

        Logger.info("Agent installed.");
    }

    public static boolean isExperimental() {
        return arguments.containsKey("experimental");
    }

    /** Called from Callbacks when exit_after_game_init was requested. */
    private static void exitOnGameInitComplete() {
        if (arguments.containsKey("exit_after_game_init")) {
            Logger.info("Exiting after game init as requested.");
            Core.getInstance().quit();
        }
    }
    
    private static void loadPatchesFromJar(String jarPath, String packageName) {
        try {
            File jarFile = new File(jarPath);
            if (!jarFile.exists()) {
                Logger.error("patches_jar file not found: " + jarPath);
                return;
            }
            
            // Add JAR to classpath
            JarFile jf = new JarFile(jarFile);
            Loader.g_instrumentation.appendToSystemClassLoaderSearch(jf);
            Loader.g_known_jars.add(jarFile);
            Logger.info("added patches JAR to classpath: " + jarFile);
            
            // Scan for patches in the specified package
            Loader.ApplyPatchesFromPackage(packageName, null, true);

        } catch (Exception e) {
            Logger.error("Error loading patches from JAR " + jarPath + ": " + e.getMessage());
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

}
