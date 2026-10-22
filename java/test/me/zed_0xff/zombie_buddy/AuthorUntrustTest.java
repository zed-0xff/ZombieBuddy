package me.zed_0xff.zombie_buddy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthorUntrustTest {
    @TempDir Path root;
    @Test void untrustPreservesUntouchedHashDecisionsThroughRealDialogAndProtocol() throws Exception {
        AuthorUntrustProbe.stateTests(root);
    }
}
