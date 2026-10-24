package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreloadSelectionTest {
    @TempDir Path root;

    @Test void successfulVanillaSaveSynchronizesSwitchesAndEmptySelection() throws Exception { run("save"); }
    @Test void failedVanillaSaveDoesNotChangePreloads() throws Exception { run("failed"); }
    @Test void firstDefaultLoadRepairsStaleRegistrationBeforeMerging() throws Exception { run("load"); }
    @Test void savegameLoadDoesNotEraseGlobalDefaultRegistrations() throws Exception { run("context"); }
    @Test void existingRegistrationStillNeedsNoticeWhenNotEarlyLoaded() throws Exception { run("late"); }
    @Test void actuallyEarlyLoadedJarDoesNotProduceFalseRestartNotice() throws Exception { run("early"); }
    @Test void approvedMovedJarUpdatesRegistrationAndRequiresRestart() throws Exception { run("updated"); }

    private void run(String mode) throws Exception {
        Path agent = Path.of(System.getProperty("zb.test.agentJar")).toAbsolutePath();
        assertTrue(Files.isRegularFile(agent), "unitTest must build the actual agent");
        Set<String> paths = new LinkedHashSet<>(); paths.add(agent.toString());
        paths.addAll(Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) if (url.getProtocol().equals("file")) paths.add(Path.of(url.toURI()).toString());
            }
        }
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        Path output = root.resolve(mode + ".log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xverify:all", "-javaagent:" + agent + "=policy=deny-new,frontend=console,config_dir=" + root.resolve("bootstrap"),
                "-cp", String.join(java.io.File.pathSeparator, paths), PreloadSelectionProbe.class.getName(),
                root.resolve("case").toString(), mode).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "Source preload-selection probe timed out");
            assertEquals(0, child.exitValue(), Files.readString(output));
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }
}
