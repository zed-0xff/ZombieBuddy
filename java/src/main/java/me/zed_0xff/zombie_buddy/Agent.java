package me.zed_0xff.zombie_buddy;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontends;
import zombie.core.Core;

public class Agent {
    public static final Map<String, String> arguments = new HashMap<>();
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final ClassLoader _systemLoader = ClassLoader.getSystemClassLoader();

    public static void premain(String agentArgs, Instrumentation inst) {
        if (!initialized.compareAndSet(false, true)) {
            Logger.warn("ZombieBuddy agent already installed; ignoring duplicate -javaagent entry.");
            return;
        }

        Logger.info("activating " + ZombieBuddy.getFullVersionString());
        Loader.g_instrumentation = inst;
        Reflect.init(inst);

        if (!Utils.isBlank(agentArgs)) {
            String[] args = agentArgs.split(",");
            for (String arg : args) {
                String[] kv = arg.split("=", 2);
                String key = kv[0].toLowerCase();
                String value = (kv.length > 1) ? kv[1] : "";

                arguments.put(key, value);
            }
        }

        String propPrefix = arguments.get("prop_prefix");
        if (!Utils.isBlank(propPrefix)) {
            final String propertyPrefix = propPrefix.endsWith(".") ? propPrefix : propPrefix + ".";
            // Snapshot property names to avoid ConcurrentModificationException; null values skipped.
            System.getProperties().stringPropertyNames().stream()
                .filter(key -> key.startsWith(propertyPrefix))
                .forEach(key -> {
                    String value = System.getProperty(key);
                    if (value != null) {
                        arguments.put(key.substring(propertyPrefix.length()), value);
                    }
                });
        }

        if( arguments.containsKey("verbosity")) {
            try {
                Loader.g_verbosity = Integer.parseInt(arguments.get("verbosity"));
                Logger.setLevel(Loader.g_verbosity);
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
        Loader.configureApprovalFrontend(arguments.getOrDefault("frontend", ModApprovalFrontends.ARG_AUTO));

        // Check ZB_VERBOSITY environment variable - it overrides command line value
        String envVerbosity = System.getenv("ZB_VERBOSITY");
        if (!Utils.isBlank(envVerbosity)) {
            try {
                Loader.g_verbosity = Integer.parseInt(envVerbosity);
                Logger.info("set Loader verbosity to " + Loader.g_verbosity + " from ZB_VERBOSITY environment variable");
            } catch (NumberFormatException e) {
                Logger.error("invalid ZB_VERBOSITY value: " + envVerbosity);
            }
        }

        Exposer.exposeAnnotatedClasses(ZombieBuddy.class.getPackage().getName());

        String basePkg = ZombieBuddy.class.getPackage().getName();
        Loader.loadResourceJar(Loader.BUNDLED_PATCHES_JAR, basePkg + ".patches", Loader.Phase.PREMAIN);
        if (isExperimental()) {
            Loader.loadResourceJar(Loader.BUNDLED_EXPERIMENTAL_JAR, basePkg + ".patches.experimental", Loader.Phase.PREMAIN);
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
                        Logger.error("patches_jar entry must be in format <path>:<package_name>, got: " + entry);
                        continue;
                    }
                    String jarPath = parts[0].trim();
                    String packageName = parts[1].trim();
                    if (jarPath.isEmpty() || packageName.isEmpty()) {
                        Logger.error("patches_jar entry must have non-empty path and package name, got: " + entry);
                        continue;
                    }
                    patchesJarEntries.add(new PatchesJarEntry(jarPath, packageName));
                }
            }

            for (PatchesJarEntry entry : patchesJarEntries) {
                Loader.loadJar(entry.jarPath, entry.packageName, null, Loader.Phase.PREMAIN);
            }
        }
        
        // Register onGameInitComplete hooks based on arguments
        if (arguments.containsKey("exit_after_game_init")) {
            Callbacks.onGameInitComplete.register(Agent::exitOnGameInitComplete);
        }

        if (arguments.containsKey("expose_classes")) {
            String[] classes = arguments.get("expose_classes").split(",");
            for (String className : classes) {
                className = className.trim();
                if (!className.isEmpty()) {
                    Exposer.exposeClass(className);
                }
            }
        }

        Loader.initConfig();
        Logger.info("Agent installed.");

        Logger.debug("system classloader: " + _systemLoader);
        Logger.debug("Agent  classloader: " + Agent.class.getClassLoader());
        Logger.debug("ZB     classloader: " + ZombieBuddy.class.getClassLoader());

        Loader.preloadMods();
        Reflect.clearCaches();
    }

    public static boolean isExperimental() {
        return arguments.containsKey("experimental");
    }

    public static int getArgInt(String key, int defaultVal) {
        String v = arguments.get(key);
        if (v == null) return defaultVal;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    public static Path configDir() {
        String configured = arguments.get("config_dir");
        if (configured != null && !configured.trim().isEmpty()) {
            return Path.of(configured.trim());
        }
        return Path.of(System.getProperty("user.home"), ".zombie_buddy");
    }

    /** Called from Callbacks when exit_after_game_init was requested. */
    private static void exitOnGameInitComplete() {
        if (arguments.containsKey("exit_after_game_init")) {
            Logger.info("Exiting after game init as requested.");
            Core.getInstance().quit();
        }
    }
    
    private static class PatchesJarEntry {
        final Path jarPath;
        final String packageName;
        
        PatchesJarEntry(String jarPath, String packageName) {
            this.jarPath = Path.of(jarPath);
            this.packageName = packageName;
        }
    }

}
