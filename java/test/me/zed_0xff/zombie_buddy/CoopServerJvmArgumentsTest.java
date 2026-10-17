package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.ToolProvider;

class CoopServerJvmArgumentsTest {
    @TempDir Path tempDir;

    @Test
    void nativeCapacityCoversWindowsForwardingLimit() throws Exception {
        String source = Files.readString(Path.of("..", "c", "windows", "zbNative.c"));
        var capacity =
                java.util.regex.Pattern.compile("(?m)^#define AGENT_OPTIONS_MAX (\\d+)$")
                        .matcher(source);
        assertTrue(capacity.find());
        assertTrue(
                CoopServerJvmArguments.WINDOWS_AGENT_OPTIONS_MAX <= Integer.parseInt(capacity.group(1)),
                "The Java forwarding limit must not exceed the native buffer capacity");
    }

    @Test
    void validatesNativeBytesIncludingJarPrefixAndTerminator() throws Exception {
        int valueBytes = CoopServerJvmArguments.WINDOWS_AGENT_OPTIONS_MAX - 28;
        String exact = "config_dir=" + "a".repeat(valueBytes);
        for (Charset encoding :
                List.of(
                        StandardCharsets.US_ASCII,
                        StandardCharsets.UTF_8,
                        Charset.forName("windows-1252"))) {
            CoopServerJvmArguments.validateWindowsOptions(exact, encoding);
            assertThrows(
                    IOException.class,
                    () -> CoopServerJvmArguments.validateWindowsOptions(exact + "a", encoding));
        }
        String unicode = "config_dir=" + "a".repeat(valueBytes - 2) + "Ä";
        CoopServerJvmArguments.validateWindowsOptions(unicode, StandardCharsets.UTF_8);
        assertThrows(
                IOException.class,
                () ->
                        CoopServerJvmArguments.validateWindowsOptions(
                                unicode + "a", StandardCharsets.UTF_8));
        CoopServerJvmArguments.validateWindowsOptions(
                "config_dir=" + "Ä".repeat(valueBytes), Charset.forName("windows-1252"));
        assertThrows(
                IOException.class,
                () ->
                        CoopServerJvmArguments.validateWindowsOptions(
                                "config_dir=漢", Charset.forName("windows-1252")));
        assertThrows(
                IOException.class,
                () ->
                        CoopServerJvmArguments.validateWindowsOptions(
                                "config_dir=\uD800", StandardCharsets.UTF_8));
    }

    @Test
    void rejectsOversizedWindowsOptionsWithoutLimitingJavaAgentPlatforms() throws Exception {
        Path jar = agent();
        var options =
                Map.of("config_dir", "a".repeat(CoopServerJvmArguments.WINDOWS_AGENT_OPTIONS_MAX));
        assertThrows(
                IOException.class,
                () -> CoopServerJvmArguments.prepare(command(), "Windows 11", jar, options));
        for (String platform : List.of("Linux", "Mac OS X")) {
            assertEquals(
                    command().size() + 1,
                    CoopServerJvmArguments.prepare(command(), platform, jar, options).size());
        }
    }

    @Test
    void nativeOverflowFromLoadedAgentPreservesOriginalCommandWithoutLoggingSecrets() throws Exception {
        Path builtAgent = Path.of(System.getProperty("zb.test.agentJar"));
        assertTrue(Files.isRegularFile(builtAgent), "The actual built agent JAR is required");
        Path loadedAgent = Files.copy(builtAgent, tempDir.resolve("ZombieBuddy probe Ä.jar"));
        Path config = Files.createDirectory(tempDir.resolve("config"));
        String classpath = loadedAgent + java.io.File.pathSeparator
                + System.getProperty("zb.test.probeClasspath");
        String javaExecutable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                ? "java.exe" : "java";
        Path output = tempDir.resolve("native-overflow-probe.log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", javaExecutable).toString(),
                "-Xverify:all", "-Djava.awt.headless=true",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-javaagent:" + loadedAgent + "=policy=deny-new,frontend=console,config_dir=" + config,
                "-cp", classpath, CoopNativeOverflowProbe.class.getName(), loadedAgent.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Loaded-agent probe timed out");
            String log = Files.readString(output, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), log);
            assertTrue(log.contains("PASS actual agent premain and native-option overflow fallback"), log);
            assertFalse(log.contains("private-not-logged"), "Admin password must not appear in logs");
            assertFalse(log.contains("option-value-not-for-logs"), "Forwarded values must not appear in logs");
            log.lines().filter(line -> line.startsWith("PASS actual agent premain")).forEach(System.out::println);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private List<String> command() {
        return new ArrayList<>(
                List.of(
                        "java",
                        "-XX:+UseG1GC",
                        "-cp",
                        "game.jar",
                        "zombie.network.GameServer",
                        "-coop",
                        "-servername",
                        "Local host"));
    }

    private Path agent() throws Exception {
        return Files.writeString(tempDir.resolve("Agent with spaces Ä.jar"), "fixture");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-8", "windows-1252", "MS932"})
    void inheritsOnlySharedSettingsOnEverySupportedPlatform(String nativeEncoding) throws Exception {
        Path jar = agent().toRealPath();
        Map<String, String> options =
                Map.of(
                        "policy",
                        "deny-new",
                        "allow_unsigned_mods",
                        "false",
                        "frontend",
                        "console",
                        "config_dir",
                        "./Freigaben",
                        "verbosity",
                        "1",
                        "experimental",
                        "",
                        "exit_after_game_init",
                        "",
                        "patches_jar",
                        "client-only.jar");
        List<String> original = command();
        String previousEncoding = System.setProperty("native.encoding", nativeEncoding);
        try {
            for (String os : List.of("Windows 11", "Linux", "Mac OS X", "Darwin")) {
                List<String> inherited = CoopServerJvmArguments.prepare(original, os, jar, options);
                String prefix = os.startsWith("Windows") ? "-agentlib:zbNative" : "-javaagent:" + jar;
                assertEquals(
                        prefix
                                + "=policy=deny-new,allow_unsigned_mods=false,frontend=console,config_dir="
                                + options.get("config_dir")
                                + ",verbosity=1",
                        inherited.get(1));
                assertEquals(original, inherited.stream().filter(s -> !s.startsWith(prefix)).toList());
                assertEquals(8, original.size());
            }
        } finally {
            if (previousEncoding == null) System.clearProperty("native.encoding");
            else System.setProperty("native.encoding", previousEncoding);
        }
    }

    @Test
    void retainsGcChoiceAndWorksWithNoGcOrExplicitOptions() throws Exception {
        List<String> original = command();
        original.remove("-XX:+UseG1GC");
        List<String> inherited =
                CoopServerJvmArguments.prepare(original, "Linux", agent(), Map.of());
        assertEquals(original.size() + 1, inherited.size());
        assertEquals("-javaagent:" + agent().toRealPath(), inherited.get(1));
        original.add(1, "-XX:+UseZGC");
        assertTrue(
                CoopServerJvmArguments.prepare(original, "Linux", agent(), Map.of())
                        .contains("-XX:+UseZGC"));
    }

    @Test
    void preservesOtherJvmArgumentsWithoutReadingOrRewritingArgumentFiles() throws Exception {
        Path jar = agent();
        List<String> original = command();
        original.add(1, "-javaagent:another-agent.jar");
        original.add(1, "@unrelated.args");
        List<String> inherited = CoopServerJvmArguments.prepare(original, "Linux", jar, Map.of());
        assertEquals(original.size() + 1, inherited.size());
        assertTrue(inherited.contains("-javaagent:another-agent.jar"));
        assertTrue(inherited.contains("@unrelated.args"));
    }

    @Test
    void doesNotTreatApplicationArgumentsAsJvmAgentsOrPatchDedicatedLaunches() throws Exception {
        List<String> original = command();
        original.add("-javaagent:ZombieBuddy.jar");
        assertEquals(
                original.size() + 1,
                CoopServerJvmArguments.prepare(original, "Linux", agent(), Map.of()).size());
        original.remove("-coop");
        assertSame(original, CoopServerJvmArguments.prepare(original, "Linux", agent(), Map.of()));
        List<String> otherApplication = List.of("java", "application.Main");
        assertSame(
                otherApplication,
                CoopServerJvmArguments.prepare(otherApplication, "Linux", agent(), Map.of()));
    }

    @Test
    void rejectsUnrepresentableOptions() throws Exception {
        Path jar = agent();
        for (String value : List.of("invalid,value", "line\nbreak", "line\rbreak", "nul\0value")) {
            assertThrows(
                    IOException.class,
                    () ->
                            CoopServerJvmArguments.prepare(
                                    command(), "Linux", jar, Map.of("config_dir", value)));
        }
    }

    @Test
    void actualChildJvmReceivesUnicodeOptionsAsOneAgentArgument() throws Exception {
        // A tiny recording agent verifies JVM parsing without starting the game or a server.
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path source = tempDir.resolve("RecordingAgent.java");
        Files.writeString(
                source,
                "public class RecordingAgent { public static void premain(String args)"
                        + " {System.setProperty(\"coop.received\", args); } public static void"
                        + " main(String[] args)"
                        + " {System.out.print(System.getProperty(\"coop.received\")); }}");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", classes.toString(), source.toString()));
        Path jar = tempDir.resolve("Recording Agent Ä.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", "RecordingAgent");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("RecordingAgent.class"));
            out.write(Files.readAllBytes(classes.resolve("RecordingAgent.class")));
            out.closeEntry();
        }
        String settings =
                "policy=deny-new,frontend=console,config_dir="
                        + tempDir.resolve("Konfiguration Ä")
                        + ",verbosity=1";
        List<String> inherited =
                CoopServerJvmArguments.prepare(
                        command(),
                        "Linux",
                        jar,
                        Map.of(
                                "policy",
                                "deny-new",
                                "frontend",
                                "console",
                                "config_dir",
                                tempDir.resolve("Konfiguration Ä").toString(),
                                "verbosity",
                                "1"));
        String argument =
                inherited.stream()
                        .filter(s -> s.startsWith("-javaagent:"))
                        .findFirst()
                        .orElseThrow();
        Path output = tempDir.resolve("child-output.txt");
        Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-Dfile.encoding=UTF-8",
                                "-Dstdout.encoding=UTF-8",
                                "-Dstderr.encoding=UTF-8",
                                argument,
                                "-cp",
                                jar.toString(),
                                "RecordingAgent")
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(output));
            assertEquals(settings, Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
