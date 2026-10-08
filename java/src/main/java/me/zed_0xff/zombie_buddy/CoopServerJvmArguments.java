package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shares the client's ZombieBuddy approval settings with the local Coop child JVM. */
public final class CoopServerJvmArguments {
    private static final List<String> SHARED_OPTIONS =
            List.of("policy", "allow_unsigned_mods", "frontend", "config_dir", "verbosity");

    private CoopServerJvmArguments() {}

    /** Called only at CoopMaster's ProcessBuilder construction site. */
    public static List<String> inherit(List<String> command) {
        try {
            return prepare(
                    command, System.getProperty("os.name", ""), loadedJar(), Agent.arguments);
        } catch (IOException | RuntimeException e) {
            // Do not log the command: it contains the host's admin password.
            Logger.warn(
                    "Could not pass ZombieBuddy to the Coop JVM: " + e.getClass().getSimpleName());
            return command;
        }
    }

    static Path loadedJar() throws IOException {
        try {
            var source = Agent.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                Path location = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(location)) return location.toRealPath();
            }
            URL resource = Agent.class.getResource("/me/zed_0xff/zombie_buddy/Agent.class");
            if (resource != null && resource.openConnection() instanceof JarURLConnection jar) {
                return Path.of(jar.getJarFileURL().toURI()).toRealPath();
            }
        } catch (Exception e) {
            throw new IOException("Cannot locate the running ZombieBuddy JAR", e);
        }
        throw new IOException("Running ZombieBuddy has no JAR location");
    }

    static List<String> prepare(
            List<String> command, String os, Path jar, Map<String, String> options)
            throws IOException {
        int main = command.indexOf("zombie.network.GameServer");
        if (main < 0 || !command.subList(main + 1, command.size()).contains("-coop"))
            return command;
        Path installed = jar.toRealPath();
        if (!Files.isRegularFile(installed)) throw new IOException("Agent is not a regular file");
        String agent =
                os.toLowerCase(Locale.ROOT).startsWith("windows")
                        ? "-agentlib:zbNative"
                        : "-javaagent:" + installed;
        if (!os.toLowerCase(Locale.ROOT).startsWith("windows")
                && installed.toString().contains("=")) {
            throw new IOException("Agent path cannot contain '='");
        }
        List<String> shared = new ArrayList<>();
        for (String key : SHARED_OPTIONS) {
            String value = options.get(key);
            if (value != null) {
                if (value.indexOf(',') >= 0
                        || value.indexOf('\n') >= 0
                        || value.indexOf('\r') >= 0
                        || value.indexOf('\0') >= 0) {
                    throw new IOException("Invalid shared option");
                }
                shared.add(key + "=" + value);
            }
        }
        if (!shared.isEmpty()) agent += "=" + String.join(",", shared);
        List<String> inherited = new ArrayList<>(command);
        // Run ZombieBuddy's premain before other Java agents can load game classes.
        inherited.add(1, agent);
        return inherited;
    }
}
