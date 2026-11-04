package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoopSessionApprovalsTest {
    @TempDir Path root;

    private Object read(Path file, Path config, String nonce) throws Exception {
        String oldFile = System.getProperty(CoopSessionApprovals.FILE_PROPERTY);
        String oldNonce = System.getProperty(CoopSessionApprovals.NONCE_PROPERTY);
        try {
            System.setProperty(CoopSessionApprovals.FILE_PROPERTY, file.toString());
            System.setProperty(CoopSessionApprovals.NONCE_PROPERTY, nonce);
            Method read = CoopSessionApprovals.class.getDeclaredMethod("read", Path.class);
            read.setAccessible(true);
            return read.invoke(null, config);
        } finally {
            if (oldFile == null) System.clearProperty(CoopSessionApprovals.FILE_PROPERTY);
            else System.setProperty(CoopSessionApprovals.FILE_PROPERTY, oldFile);
            if (oldNonce == null) System.clearProperty(CoopSessionApprovals.NONCE_PROPERTY);
            else System.setProperty(CoopSessionApprovals.NONCE_PROPERTY, oldNonce);
        }
    }

    private Object component(Object state, String name) throws Exception {
        Method method = state.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(state);
    }

    private String nonce(Path file) { return file.getFileName().toString().substring(8, 40); }

    @Test void strictSingleUseTransferIncludesBothDirectionsWithoutPersistentStores() throws Exception {
        String first = "a".repeat(64), second = "b".repeat(64);
        var hashes = Map.of(first, true, second, false);
        var authors = Map.of("76561198000000010", true, "76561198000000011", false);
        Path file = CoopSessionApprovals.publish(hashes, authors, root);
        var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl != null) {
            var owner = acl.getOwner();
            assertTrue(acl.getAcl().stream().allMatch(entry -> entry.principal().equals(owner)));
        }
        Object state = read(file, root, nonce(file));
        assertEquals(true, component(state, "usable"));
        assertEquals(hashes, component(state, "jars"));
        assertEquals(authors, component(state, "authors"));
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(file.getParent()));
        assertEquals(false, component(read(file, root, nonce(file)), "usable"));
        assertFalse(Files.exists(root.resolve("config.json")));
        assertFalse(Files.exists(root.resolve("mod_approvals.json")));
    }

    @Test void rejectsUnboundedMalformedOrMisboundAuthorityAndLeavesUnrelatedPathsAlone() throws Exception {
        assertThrows(IOException.class, () -> CoopSessionApprovals.publish(Map.of("bad", true), Map.of(), root));
        assertThrows(IOException.class, () -> CoopSessionApprovals.publish(Map.of(), Map.of("bad", true), root));
        for (String mode : List.of("version", "boolean", "count", "duplicate", "trailing", "oversize", "scope", "nonce", "nonceHeader")) {
            Path file = CoopSessionApprovals.publish(Map.of("a".repeat(64), true), Map.of(), root);
            byte[] bytes = Files.readAllBytes(file);
            if (mode.equals("version")) bytes[7] = 2;
            if (mode.equals("nonceHeader")) bytes[10] = bytes[10] == '0' ? (byte)'1' : (byte)'0';
            if (mode.equals("boolean")) bytes[bytes.length - 5] = 2;
            if (mode.equals("trailing")) bytes = Arrays.copyOf(bytes, bytes.length + 1);
            if (mode.equals("oversize")) bytes = new byte[CoopSessionApprovals.MAX_BYTES + 1];
            if (mode.equals("count")) {
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                    in.readInt(); in.readInt(); in.readUTF(); in.readUTF();
                    int offset = bytes.length - in.available();
                    java.nio.ByteBuffer.wrap(bytes).putInt(offset, CoopSessionApprovals.MAX_ENTRIES + 1);
                }
            }
            if (mode.equals("duplicate")) {
                ByteArrayOutputStream duplicate = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(duplicate)) {
                    out.writeInt(CoopSessionApprovals.MAGIC); out.writeInt(CoopSessionApprovals.VERSION);
                    out.writeUTF(nonce(file)); out.writeUTF(root.toAbsolutePath().normalize().toString());
                    out.writeInt(2);
                    out.writeUTF("a".repeat(64)); out.writeByte(1);
                    out.writeUTF("a".repeat(64)); out.writeByte(0);
                    out.writeInt(0);
                }
                bytes = duplicate.toByteArray();
            }
            Files.write(file, bytes);
            Object state = read(file, mode.equals("scope") ? root.resolve("other") : root,
                    mode.equals("nonce") ? "0".repeat(32) : nonce(file));
            assertEquals(false, component(state, "usable"), mode);
        }
        String nonce = "0".repeat(32);
        Path outside = Files.createDirectories(root.resolve("zombie-buddy-coop-unrelated")).resolve("session-" + nonce + ".bin");
        Files.writeString(outside, "unrelated sentinel");
        assertEquals(false, component(read(outside, root, nonce), "usable"));
        assertEquals("unrelated sentinel", Files.readString(outside));
    }

    @Test void actualAgentChildrenConsumeLatestProcessDecisionsWithoutWrites() throws Exception {
        Path log = root.resolve("session-processes.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xverify:all", "-cp", System.getProperty("zb.test.agentJar") + File.pathSeparator
                + System.getProperty("zb.test.probeClasspath"), CoopSessionApprovalsProbe.class.getName(), root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(80, TimeUnit.SECONDS), "Coop session probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    @Test void rejectedDirectorySymlinkCannotDeleteTheRealSnapshot() throws Exception {
        Path file = CoopSessionApprovals.publish(Map.of(), Map.of(), root);
        Path link = file.getParent().resolveSibling("zombie-buddy-coop-link-" + UUID.randomUUID());
        try {
            try { Files.createSymbolicLink(link, file.getParent()); }
            catch (UnsupportedOperationException | FileSystemException unavailable) {
                org.junit.jupiter.api.Assumptions.abort("Directory symlinks are unavailable: " + unavailable.getClass().getSimpleName());
            }
            byte[] original = Files.readAllBytes(file);
            assertEquals(false, component(read(link.resolve(file.getFileName()), root, nonce(file)), "usable"));
            assertArrayEquals(original, Files.readAllBytes(file));
        } finally {
            Files.deleteIfExists(link);
            read(file, root, nonce(file));
        }
    }
}
