package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoopReadOnlyTest {
    @TempDir Path root;
    @Test void fallbackRecognizesOnlyTheCoopServerCommand() throws Exception {
        String oldCommand = System.getProperty("sun.java.command"), oldRole = System.getProperty("zb.coop.child");
        var detect = CoopChild.class.getDeclaredMethod("detect"); detect.setAccessible(true);
        try {
            System.clearProperty("zb.coop.child");
            System.setProperty("sun.java.command", "zombie.network.GameServer -coop -servername local");
            assertEquals(true, detect.invoke(null));
            for (String command : java.util.List.of("zombie.network.GameServer -servername dedicated", "other.Main -coop")) {
                System.setProperty("sun.java.command", command); assertEquals(false, detect.invoke(null));
            }
        } finally {
            if (oldCommand == null) System.clearProperty("sun.java.command"); else System.setProperty("sun.java.command", oldCommand);
            if (oldRole == null) System.clearProperty("zb.coop.child"); else System.setProperty("zb.coop.child", oldRole);
        }
    }
    @Test void actualChildAgentsCannotChangeClientDecisions() throws Exception {
        Path log = root.resolve("ownership.log");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xverify:all", "-cp", System.getProperty("zb.test.agentJar") + java.io.File.pathSeparator
                        + System.getProperty("zb.test.probeClasspath"), CoopReadOnlyProbe.class.getName(), root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(p.waitFor(50, TimeUnit.SECONDS), "Ownership probe timed out");
            assertEquals(0, p.exitValue(), Files.readString(log));
        } finally { if (p.isAlive()) p.destroyForcibly(); }
    }
}
