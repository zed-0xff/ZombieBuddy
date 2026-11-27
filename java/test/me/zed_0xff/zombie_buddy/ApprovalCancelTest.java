package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static me.zed_0xff.zombie_buddy.ModFlags.*;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalCancelTest {
    @TempDir Path root;

    @Test void cancelRestoresCurrentSavedDecisionsAndTrustRegardlessOfSave() throws Exception {
        ApprovalCancelProbe.stateTests(root);
    }

    @Test void cancellationMarkerIsSeparateNonstickyAndConsumedWithoutCreatingStores() throws Exception {
        String previousDir = Agent.arguments.get("config_dir");
        Agent.arguments.put("config_dir", root.toString());
        try {
            Field bit = ApprovalChoices.class.getDeclaredField("CANCEL_PENDING"); bit.setAccessible(true);
            int marker = bit.getInt(null);
            Field untrust = ApprovalChoices.class.getDeclaredField("UNTRUST_ONLY"); untrust.setAccessible(true);
            assertEquals(0, marker & (MF_VALID | MF_SIGNED | MF_ACTIVE | MF_PERSIST | MF_BANNED | MF_PRELOAD | MF_TRUST_AUTHOR | untrust.getInt(null)));
            assertFalse(new ModFlags(marker).getSticky().has(marker));
            var entry = ApprovalDialogProbe.entry("new");
            var rows = ApprovalChoices.cancel(List.of(entry));
            Path response = root.resolve("response.json");
            JarBatchApprovalProtocol.writeResponse(response, rows);
            var received = JarBatchApprovalProtocol.readResponse(response);
            assertTrue(received.get(0).flags.has(marker), "Cancellation survives real JSON IPC");
            ApprovalChoices.applyCancelled(received);
            assertEquals(Boolean.FALSE, received.get(0).decision);
            assertFalse(received.get(0).flags.has(marker | MF_PERSIST | MF_TRUST_AUTHOR | MF_PRELOAD));
            assertFalse(Files.exists(root.resolve("config.json")));
            assertFalse(Files.exists(root.resolve("mod_approvals.json")));
        } finally {
            if (previousDir == null) Agent.arguments.remove("config_dir");
            else Agent.arguments.put("config_dir", previousDir);
        }
    }

    @Test void ordinaryConfirmationIsNotResolvedAsCancellation() throws Exception {
        var allowed = ApprovalDialogProbe.entry("allow", true, MF_PERSIST, null, null);
        var reset = ApprovalDialogProbe.entry("reset", null, MF_PERSIST, null, null);
        ApprovalChoices.applyCancelled(List.of(allowed, reset));
        assertEquals(Boolean.TRUE, allowed.decision);
        assertTrue(allowed.flags.has(MF_PERSIST));
        assertNull(reset.decision);
        assertTrue(reset.flags.has(MF_PERSIST));
    }
}
