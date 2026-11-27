package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreloadRecoveryTest {
    @TempDir Path root;

    @Test void savedAllowRestoresRegistrationAndPreloadsOnRestart() throws Exception {
        PreloadRecoveryProbe.run(root, "allow");
        PreloadRecoveryProbe.restart(root);
    }

    @Test void verifiedAuthorTrustAloneRestoresRegistration() throws Exception {
        PreloadRecoveryProbe.run(root, "trusted");
        PreloadRecoveryProbe.run(root.resolve("local"), "trusted-local");
    }

    @Test void savedAllowWorksUnderDenyNewPolicy() throws Exception {
        PreloadRecoveryProbe.run(root, "deny-new");
    }

    @Test void noneligibleModsDoNotGainRegistrations() throws Exception {
        for (String mode : java.util.List.of("deny", "unsigned", "invalid", "banned",
                "no-manifest", "no-declaration", "session", "allow-all")) {
            PreloadRecoveryProbe.run(root.resolve(mode), mode);
        }
    }

    @Test void currentDialogDoesNotRecoverAnOmittedPreloadChoice() throws Exception {
        PreloadRecoveryProbe.run(root, "dialog");
    }

    @Test void existingRegistrationIsPreserved() throws Exception {
        PreloadRecoveryProbe.run(root, "existing");
    }
}
