package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

    @Test
    void inheritsOnlySharedSettingsOnEverySupportedPlatform() throws Exception {
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
                        tempDir.resolve("Freigaben Ä").toString(),
                        "verbosity",
                        "1",
                        "experimental",
                        "",
                        "exit_after_game_init",
                        "",
                        "patches_jar",
                        "client-only.jar");
        List<String> original = command();
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
