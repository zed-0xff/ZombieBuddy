package me.zed_0xff.zombie_buddy.frontend;

import me.zed_0xff.zombie_buddy.JarBatchApprovalProtocol;
import me.zed_0xff.zombie_buddy.JarDecisionTable;
import me.zed_0xff.zombie_buddy.Loader;
import me.zed_0xff.zombie_buddy.Logger;

import zombie.GameWindow;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs a subprocess executing {@link SwingApprovalMain} (javax.swing). If the subprocess fails,
 * no decisions are applied here; {@link me.zed_0xff.zombie_buddy.Loader} will treat still-unapproved mods according to policy.
 */
public final class SwingModApprovalFrontend implements ModApprovalFrontend {

    private static final String LOADING_WAIT_JAVA_MOD_APPROVAL = "Waiting for Java mods approval…";
    private static final String LOADING_MODS = "Loading Mods";

    @Override
    public void approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending, JarDecisionTable disk) {
        if (pending.isEmpty()) {
            return;
        }
        if (runSwingSubprocessBatch(pending, disk)) {
            return;
        }
        Logger.warn("Swing batch approval failed or unavailable (" + pending.size() + " pending mods)");
    }

    private boolean runSwingSubprocessBatch(List<JarBatchApprovalProtocol.Entry> pending, JarDecisionTable disk) {
        String jarPath = Loader.getZombieBuddyJarPathForSubprocess();
        if (jarPath == null) {
            Logger.warn("Batch approval skipped: ZombieBuddy not loaded from a JAR (or path unknown)");
            return false;
        }
        GameWindow.DoLoadingText(LOADING_WAIT_JAVA_MOD_APPROVAL);
        Path tmpIn = null;
        Path tmpOut = null;
        try {
            tmpIn = java.nio.file.Files.createTempFile("zb-batch-req-", ".json");
            tmpOut = java.nio.file.Files.createTempFile("zb-batch-resp-", ".json");
            JarBatchApprovalProtocol.writeRequest(tmpIn, pending);
            String javaExe = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java"
            ).toAbsolutePath().toString();
            ProcessBuilder pb = new ProcessBuilder(
                javaExe,
                "-Djava.awt.headless=false",
                "-cp",
                jarPath,
                SwingApprovalMain.class.getName(),
                tmpIn.toAbsolutePath().toString(),
                tmpOut.toAbsolutePath().toString()
            );
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Logger.info("Starting batch approval subprocess: commandLine=" + pb.command()
                + " pendingEntries=" + pending.size()
                + " timeoutSeconds=" + Loader.g_batchApprovalTimeoutSeconds + " (0 = no timeout)");
            Process p = pb.start();
            boolean timedOut = false;
            if (Loader.g_batchApprovalTimeoutSeconds <= 0) {
                p.waitFor();
            } else {
                timedOut = !p.waitFor(Loader.g_batchApprovalTimeoutSeconds, TimeUnit.SECONDS);
            }
            if (timedOut) {
                Logger.warn("Batch approval subprocess timed out after " + Loader.g_batchApprovalTimeoutSeconds + "s");
                p.destroyForcibly();
                return false;
            }
            if (p.exitValue() != 0) {
                Logger.info("Batch approval subprocess exited with " + p.exitValue());
                return false;
            }
            List<JarBatchApprovalProtocol.OutLine> lines = JarBatchApprovalProtocol.readResponse(tmpOut);
            if (lines == null) {
                Logger.warn("Batch approval response malformed");
                return false;
            }
            if (lines.size() != pending.size()) {
                Logger.warn("Batch approval response row count mismatch");
                return false;
            }
            for (JarBatchApprovalProtocol.Entry e : pending) {
                int matches = 0;
                for (JarBatchApprovalProtocol.OutLine ol : lines) {
                    if (e.modKey.equals(ol.modId) && e.sha256.equals(ol.sha256)) {
                        matches++;
                    }
                }
                if (matches != 1) {
                    Logger.warn("Batch approval missing or duplicate row for " + e.modKey);
                    return false;
                }
            }
            Loader.applyBatchApprovalLines(lines, disk);
            return true;
        } catch (Exception e) {
            Logger.error("Batch approval subprocess failed: " + e);
            return false;
        } finally {
            GameWindow.DoLoadingText(LOADING_MODS);
            try {
                if (tmpIn != null) {
                    java.nio.file.Files.deleteIfExists(tmpIn);
                }
                if (tmpOut != null) {
                    java.nio.file.Files.deleteIfExists(tmpOut);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
