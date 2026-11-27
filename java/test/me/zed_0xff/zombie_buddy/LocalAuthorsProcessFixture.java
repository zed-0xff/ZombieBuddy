package me.zed_0xff.zombie_buddy;

import me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import me.zed_0xff.zombie_buddy.ZBSVerifier.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class LocalAuthorsProcessFixture {
    static final long A = 76561198000000010L, B = 76561198000000011L;

    static String hash(Path jar) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }

    static String childClasspath() throws Exception {
        // Gradle's worker classpath does not expose the test runtime in java.class.path.
        Set<String> entries =
                new LinkedHashSet<>(
                        Arrays.asList(
                                System.getProperty("java.class.path")
                                        .split(
                                                java.util.regex.Pattern.quote(
                                                        java.io.File.pathSeparator))));
        for (Class<?> type :
                List.of(
                        LocalAuthorsProcessFixture.class,
                        ZBSVerifier.class,
                        com.google.gson.Gson.class,
                        org.bouncycastle.crypto.signers.Ed25519Signer.class,
                        net.bytebuddy.asm.Advice.class,
                        net.bytebuddy.agent.ByteBuddyAgent.class)) {
            entries.add(
                    Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                            .toString());
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    static Process child(Path root, long id, String mode) throws Exception {
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Djava.awt.headless=true",
                        "-Xverify:all",
                        "-cp",
                        childClasspath(),
                        LocalAuthorsProcessFixture.class.getName(),
                        root.toString(),
                        Long.toString(id),
                        mode)
                .redirectErrorStream(true)
                .redirectOutput(root.resolve(id + "-" + mode + ".log").toFile())
                .start();
    }

    static void finish(Process process, Path root, long id, String mode) throws Exception {
        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Process timeout");
        }
        if (process.exitValue() != 0)
            throw new AssertionError(Files.readString(root.resolve(id + "-" + mode + ".log")));
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        if (args.length > 1) {
            long number = Long.parseLong(args[1]);
            var id = new SteamID64(number);
            String mode = args[2];
            Agent.arguments.put("config_dir", root.resolve("shared-config").toString());
            Path jar = root.resolve(number + ".jar"), zbs = root.resolve(number + ".jar.zbs");
            if (mode.equals("write")) {
                KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                Files.writeString(jar, "headless server fixture " + id);
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(pair.getPrivate());
                signer.update(("ZBS:" + id + ":" + hash(jar)).getBytes(StandardCharsets.UTF_8));
                Files.writeString(
                        zbs,
                        "ZBS\nSteamID64:"
                                + id
                                + "\nSignature:"
                                + HexFormat.of().formatHex(signer.sign())
                                + "\n");
                byte[] encoded = pair.getPublic().getEncoded();
                String key =
                        HexFormat.of()
                                .formatHex(
                                        Arrays.copyOfRange(
                                                encoded, encoded.length - 32, encoded.length));
                LocalAuthors.profileFetcher =
                        sid -> {
                            Files.writeString(root.resolve(number + ".ready"), "ready");
                            Instant deadline = Instant.now().plusSeconds(15);
                            while (!Files.exists(root.resolve("go"))) {
                                if (Instant.now().isAfter(deadline))
                                    throw new AssertionError("Barrier timeout");
                                Thread.sleep(20);
                            }
                            return new LocalAuthors.Profile(
                                    "First server name " + id, List.of(key));
                        };
            } else
                LocalAuthors.profileFetcher =
                        sid -> {
                            throw new AssertionError("Offline server attempted profile access");
                        };
            if (!(ZBSVerifier.verify(jar, zbs, hash(jar), id, Map.of()) instanceof ValidSignature))
                throw new AssertionError("Server signature check failed");
            if (!LocalAuthors.displayName(id, Map.of()).equals("First server name " + id))
                throw new AssertionError("Persistent name missing");
            System.out.println("PASS headless process " + id + " " + mode);
            return;
        }
        Process a = child(root, A, "write"), b = child(root, B, "write");
        try {
            Instant deadline = Instant.now().plusSeconds(15);
            while (!Files.exists(root.resolve(A + ".ready"))
                    || !Files.exists(root.resolve(B + ".ready"))) {
                if (Instant.now().isAfter(deadline))
                    throw new AssertionError("Children did not reach simultaneous writes");
                Thread.sleep(20);
            }
            Files.writeString(root.resolve("go"), "go");
            finish(a, root, A, "write");
            finish(b, root, B, "write");
        } finally {
            if (a.isAlive()) a.destroyForcibly();
            if (b.isAlive()) b.destroyForcibly();
        }
        String cache = Files.readString(root.resolve("shared-config/authors.local.json"));
        if (!cache.contains(Long.toString(A)) || !cache.contains(Long.toString(B)))
            throw new AssertionError("Concurrent write lost an author");
        finish(child(root, A, "offline"), root, A, "offline");
        finish(child(root, B, "offline"), root, B, "offline");
        if (Files.exists(root.resolve("shared-config/config.json"))
                || Files.exists(root.resolve("shared-config/mod_approvals.json")))
            throw new AssertionError("Approval files modified");
        System.out.println(
                "PASS headless server cache: two concurrent JVM writers, shared config, two offline"
                    + " JVM restarts, immutable names, no GUI or external network");
    }
}
