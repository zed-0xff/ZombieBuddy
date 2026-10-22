package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllowAllPreloadTest {
    @TempDir Path root;
    @Test void clientAllowsPreloadsButEnforcesSteamBans() throws Exception { run("client"); }
    @Test void dedicatedAllowsPreloadsButEnforcesSteamBans() throws Exception { run("server"); }

    private void run(String role) throws Exception {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        String agent = System.getProperty("zb.test.authorTrustAgent");
        Path log = root.resolve(role + ".log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xverify:all", "-javaagent:" + agent + "=policy=allow-all,frontend=console,config_dir=" + root.resolve("bootstrap"),
                "-cp", agent + File.pathSeparator + System.getProperty("zb.test.authorTrustClasspath"),
                AllowAllPreloadProbe.class.getName(), root.resolve(role).toString(), role)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(55, TimeUnit.SECONDS), "Allow-all probe timed out");
            assertEquals(0, child.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("PRELOAD RESTART REQUIRED"), "New registrations need a visible console notice");
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }
}
