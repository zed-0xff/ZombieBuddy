package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthorTrustSeparationTest {
    @TempDir Path root;

    @Test void realSignedAuthorChoicesRemainIndependentAcrossFrontendsAndRestarts() throws Exception {
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path log = root.resolve("author-trust.log");
        String agent = System.getProperty("zb.test.authorTrustAgent");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-Xverify:all", "-javaagent:" + agent + "=policy=deny-new,frontend=console,config_dir=" + root.resolve("bootstrap"),
            "-cp", agent + java.io.File.pathSeparator + System.getProperty("zb.test.authorTrustClasspath"),
            AuthorTrustSeparationProbe.class.getName(), root.resolve("fixtures").toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(55, TimeUnit.SECONDS), "Author trust probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
