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

        List<PatchesJarEntry> patchesJarEntries = new ArrayList<>();
        boolean experimentalEnabled = false;

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
                        GameUtils.g_exit_after_game_init = true;
                        System.err.println("[ZB] will exit after game init");
                        break;

                    case "dump_env":
                        Loader.g_dump_env = true;
                        dumpEnv();
                        break;

                    case "patches_jar":
                        // Support multiple JARs separated by semicolon
                        // Each entry must be in format <path>:<package_name>
                        String[] entries = value.split(";");
                        for (String entry : entries) {
                            entry = entry.trim();
                            if (!entry.isEmpty()) {
                                String[] parts = entry.split(":", 2);
                                if (parts.length != 2) {
                                    System.err.println("[ZB] patches_jar entry must be in format <path>:<package_name>, got: " + entry);
                                    continue;
                                }
                                String jarPath = parts[0].trim();
                                String packageName = parts[1].trim();
                                if (jarPath.isEmpty() || packageName.isEmpty()) {
                                    System.err.println("[ZB] patches_jar entry must have non-empty path and package name, got: " + entry);
                                    continue;
                                }
                                patchesJarEntries.add(new PatchesJarEntry(jarPath, packageName));
                            }
                        }
                        System.out.println("[ZB] patches_jar specified: " + value + " (" + patchesJarEntries.size() + " JAR(s))");
                        break;

                    case "experimental":
                        experimentalEnabled = true;
                        System.out.println("[ZB] experimental patches enabled");
                        break;

                    case "lua_server_port":
                        try {
                            int serverPort;
                            boolean isRandomPort = false;
                            if ("random".equalsIgnoreCase(value)) {
                                serverPort = 0; // 0 means random port
                                isRandomPort = true;
                                System.out.println("[ZB] Using random port for HTTP server");
                            } else {
                                serverPort = Integer.parseInt(value);
                                if (serverPort == 0) {
                                    isRandomPort = true;
                                    System.out.println("[ZB] Using random port for HTTP server");
                                }
                            }
                            HttpServer httpServer = new HttpServer(serverPort, isRandomPort);
                            httpServer.start();
                        } catch (NumberFormatException e) {
                            System.err.println("[ZB] invalid server_port value: " + value);
                        } catch (Exception e) {
                            System.err.println("[ZB] failed to start HTTP server: " + e.getMessage());
                        }
                        break;

                    case "lua_task_timeout":
                        try {
                            HttpServer.luaTaskTimeoutMs = Long.parseLong(value);
                            System.out.println("[ZB] Lua task timeout set to " + HttpServer.luaTaskTimeoutMs + "ms");
                        } catch (NumberFormatException e) {
                            System.err.println("[ZB] invalid lua_task_timeout value: " + value);
                        }
                        break;

                    default:
                        System.err.println("[ZB] unknown agent argument: " + key);
                        break;
                }
            }
        }

        // Check ZB_VERBOSITY environment variable - it overrides command line value
        String envVerbosity = System.getenv("ZB_VERBOSITY");
        if (envVerbosity != null && !envVerbosity.isEmpty()) {
            try {
                Loader.g_verbosity = Integer.parseInt(envVerbosity);
                System.err.println("[ZB] set verbosity to " + Loader.g_verbosity + " from ZB_VERBOSITY environment variable");
            } catch (NumberFormatException e) {
                System.err.println("[ZB] invalid ZB_VERBOSITY value: " + envVerbosity);
            }
        }

        Loader.ApplyPatchesFromPackage(ZombieBuddy.class.getPackage().getName() + ".patches", null, true);
        
        // Load experimental patches if enabled
        if (experimentalEnabled) {
            Loader.ApplyPatchesFromPackage(ZombieBuddy.class.getPackage().getName() + ".patches.experimental", null, true);
        }
        
        // Load patches from external JAR(s) if specified
        for (PatchesJarEntry entry : patchesJarEntries) {
            loadPatchesFromJar(entry.jarPath, entry.packageName);
        }
        
        System.out.println("[ZB] Agent installed.");
    }
    
    private static void loadPatchesFromJar(String jarPath, String packageName) {
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
            
            // Scan for patches in the specified package
            Loader.ApplyPatchesFromPackage(packageName, null, true);
            
        } catch (Exception e) {
            System.err.println("[ZB] Error loading patches from JAR " + jarPath + ": " + e.getMessage());
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
