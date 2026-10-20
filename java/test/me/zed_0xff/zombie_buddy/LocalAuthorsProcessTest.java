package me.zed_0xff.zombie_buddy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class LocalAuthorsProcessTest {
    @TempDir Path tempDir;

    @Test
    void mergesConcurrentHeadlessWritersAndRestartsOffline() throws Exception {
        LocalAuthorsProcessFixture.main(new String[] {tempDir.toString()});
    }
}
