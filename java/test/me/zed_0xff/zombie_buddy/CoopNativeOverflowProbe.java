package me.zed_0xff.zombie_buddy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Fresh-JVM probe of the actual shaded agent; no game, Coop server or native loader is started. */
public final class CoopNativeOverflowProbe {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static Path source(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
    }
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toRealPath();
        check(Files.isRegularFile(jar), "Expected a real agent JAR");
        check(Loader.g_instrumentation != null, "The actual agent premain must have run");
        check(Files.isSameFile(source(Agent.class), jar), "Agent must come from the built JAR");
        check(Files.isSameFile(source(CoopServerJvmArguments.class), jar), "Forwarding helper must come from that same JAR");
        check(Files.isSameFile(CoopServerJvmArguments.loadedJar(), jar), "Public inheritance path must resolve that JAR");
        Charset encoding = Charset.forName(System.getProperty("native.encoding",
                System.getProperty("sun.jnu.encoding", Charset.defaultCharset().name())));
        int limit = CoopServerJvmArguments.WINDOWS_AGENT_OPTIONS_MAX;
        String marker = "option-value-not-for-logs";
        int overhead = "ZombieBuddy.jar=config_dir=".getBytes(encoding).length + 1;
        String exact = marker + "a".repeat(limit - overhead - marker.getBytes(encoding).length);
        check("ZombieBuddy.jar=config_dir=".concat(exact).getBytes(encoding).length + 1 == limit,
                "Exact native-encoded payload includes JAR prefix and trailing NUL");
        var original = new ArrayList<>(List.of("java", "-XX:+UseG1GC", "-cp", "game.jar",
                "zombie.network.GameServer", "-coop", "-adminpassword", "private-not-logged"));
        var snapshot = new ArrayList<>(original);
        var savedArguments = new HashMap<>(Agent.arguments);
        String savedOs = System.getProperty("os.name");
        var stdout = System.out; var stderr = System.err;
        var captured = new ByteArrayOutputStream();
        try (var sink = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            // This selects the Windows forwarding branch; the probe never invokes a native loader.
            System.setProperty("os.name", "Windows 11");
            System.setOut(sink); System.setErr(sink); Logger.setLevel(Logger.INFO);
            Agent.arguments.clear(); Agent.arguments.put("config_dir", exact);
            List<String> accepted = CoopServerJvmArguments.inherit(original);
            check(accepted != original && accepted.size() == original.size() + 1,
                    "Exact-capacity options must pass the real inherit path");
            check(accepted.get(1).equals("-agentlib:zbNative=config_dir=" + exact), "Exact value must be forwarded intact");
            check(snapshot.equals(original), "Successful forwarding must not mutate the original command");
            check(captured.size() == 0, "Successful forwarding must stay quiet");

            Agent.arguments.put("config_dir", exact + "a");
            try {
                CoopServerJvmArguments.prepare(original, "Windows 11", jar, Agent.arguments);
                throw new AssertionError("Oversized payload must be rejected");
            } catch (IOException expected) {
                check("Shared options exceed the Windows native agent buffer".equals(expected.getMessage()),
                        "Rejection must specifically be the buffer limit, not JAR resolution");
            }
            check(CoopServerJvmArguments.inherit(original) == original, "Overflow must return the very same command object");
            check(snapshot.equals(original), "Overflow must preserve every original argument");
            String warning = captured.toString(StandardCharsets.UTF_8);
            check(warning.contains("Could not pass ZombieBuddy to the Coop JVM: IOException"), "Expected sanitized fallback warning");
            check(!warning.contains("private-not-logged"), "Never log the command password");
            check(!warning.contains(marker) && !warning.contains(exact), "Never log forwarded option values");

            Agent.arguments.put("config_dir", exact);
            captured.reset();
            check(CoopServerJvmArguments.inherit(original).get(1).endsWith(exact),
                    "Valid options must still forward after an overflow");
            check(captured.size() == 0, "Forwarding recovery must stay quiet");
        } finally {
            System.setOut(stdout); System.setErr(stderr);
            System.setProperty("os.name", savedOs);
            Agent.arguments.clear(); Agent.arguments.putAll(savedArguments);
        }
        String version;
        try (var archive = new JarFile(jar.toFile())) {
            version = archive.getManifest().getMainAttributes().getValue("Implementation-Version");
        }
        System.out.println("PASS actual agent premain and native-option overflow fallback: " + checks
                + " assertions; version=" + version + "; agent-sha256=" + Utils.sha256Hex(jar));
    }
}
