package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ZBSVerifierTest {

    private static final SteamID64 AUTHOR_ID = new SteamID64(76561198000000000L);
    private static final SteamID64 OTHER_ID = new SteamID64(76561198000000001L);
    private static final WorkshopItemID WORKSHOP_ID = new WorkshopItemID(3709229404L);

    @TempDir
    Path tempDir;

    @Test
    void verify_acceptsValidSignatureFromKnownAuthorKey() throws Exception {
        SignedFixture fixture = signedFixture(AUTHOR_ID);

        ZBSVerifier.Verification result = ZBSVerifier.verify(
            fixture.jarPath,
            fixture.zbsPath,
            fixture.jarHash,
            AUTHOR_ID,
            knownAuthors(AUTHOR_ID, fixture.publicKeyHex)
        );

        assertInstanceOf(ZBSVerifier.ValidSignature.class, result);
        assertEquals(AUTHOR_ID, result.sid);
        assertEquals(List.of(fixture.publicKeyHex), result.profileKeys);
    }

    @Test
    void verify_rejectsUploaderMismatchBeforeCheckingSignature() throws Exception {
        SignedFixture fixture = signedFixture(AUTHOR_ID);

        ZBSVerifier.Verification result = ZBSVerifier.verify(
            fixture.jarPath,
            fixture.zbsPath,
            fixture.jarHash,
            OTHER_ID,
            knownAuthors(AUTHOR_ID, fixture.publicKeyHex)
        );

        assertInstanceOf(ZBSVerifier.InvalidSignature.class, result);
        assertEquals(AUTHOR_ID, result.sid);
        assertEquals("Declared SteamID64 does not match Workshop item uploader.", result.detailedMessage);
    }

    @Test
    void verify_rejectsTamperedJarHash() throws Exception {
        SignedFixture fixture = signedFixture(AUTHOR_ID);
        String previousConfig = Agent.arguments.put("config_dir", tempDir.toString());
        LocalAuthors.ProfileFetcher previousFetcher = LocalAuthors.profileFetcher;
        LocalAuthorsTest.restart();
        LocalAuthors.profileFetcher = id -> new LocalAuthors.Profile("Test Author", List.of(fixture.publicKeyHex));
        try {
            ZBSVerifier.Verification result = ZBSVerifier.verify(
                fixture.jarPath,
                fixture.zbsPath,
                "0".repeat(64),
                AUTHOR_ID,
                knownAuthors(AUTHOR_ID, fixture.publicKeyHex)
            );

            assertInstanceOf(ZBSVerifier.InvalidSignature.class, result);
            assertEquals("Signature does not match the Steam profile signing keys.", result.detailedMessage);
            assertFalse(Files.exists(tempDir.resolve("authors.local.json")));
        } finally {
            LocalAuthors.profileFetcher = previousFetcher;
            if (previousConfig == null) Agent.arguments.remove("config_dir");
            else Agent.arguments.put("config_dir", previousConfig);
            LocalAuthorsTest.restart();
        }
    }

    @Test
    void verify_reportsMalformedSidecar() throws Exception {
        Path jarPath = writeJar("malformed jar");
        Path zbsPath = tempDir.resolve("malformed.jar.zbs");
        Files.writeString(zbsPath, "ZBS\nSteamID64:not-a-steamid\nSignature:00\n");

        ZBSVerifier.Verification result = ZBSVerifier.verify(
            jarPath,
            zbsPath,
            sha256Hex(jarPath),
            AUTHOR_ID,
            Map.of()
        );

        assertInstanceOf(ZBSVerifier.InvalidSignature.class, result);
        assertNull(result.sid);
        assertTrue(result.detailedMessage.contains("Second line must be SteamID64"));
    }

    @Test
    void verify_reportsMissingSidecar() throws Exception {
        Path jarPath = writeJar("missing zbs");
        Path missingZBS = tempDir.resolve("missing.jar.zbs");

        ZBSVerifier.Verification result = ZBSVerifier.verify(jarPath, missingZBS, sha256Hex(jarPath));

        assertInstanceOf(ZBSVerifier.MissingSignature.class, result);
        assertNull(result.sid);
        assertTrue(result.detailedMessage.contains("Missing .zbs file next to JAR"));
    }

    @Test
    void check_treatsMissingSidecarAsUnsignedWhenAllowed() throws Exception {
        Path jarPath = writeJar("unsigned allowed");
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetails = workshopDetails(AUTHOR_ID);

        ZBSVerifier.CheckResult result = ZBSVerifier.check(
            jarPath,
            sha256Hex(jarPath),
            WORKSHOP_ID,
            true,
            true,
            workshopDetails
        );

        assertFalse(result.flags().has(MF_SIGNED));
        assertEquals(AUTHOR_ID, result.uploaderID());
        assertTrue(result.flags().has(MF_VALID));
        assertNull(result.verification());
    }

    @Test
    void check_blocksMissingSidecarWhenUnsignedNotAllowed() throws Exception {
        Path jarPath = writeJar("unsigned blocked");
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetails = workshopDetails(AUTHOR_ID);

        ZBSVerifier.CheckResult result = ZBSVerifier.check(
            jarPath,
            sha256Hex(jarPath),
            WORKSHOP_ID,
            true,
            false,
            workshopDetails
        );

        assertFalse(result.flags().has(MF_SIGNED));
        assertEquals(AUTHOR_ID, result.uploaderID());
        assertFalse(result.flags().has(MF_VALID));
        assertEquals("missing .zbs file; allow_unsigned_mods=false", result.blockReason());
    }

    @Test
    void check_bindsSignatureToWorkshopUploader() throws Exception {
        SignedFixture fixture = signedFixture(AUTHOR_ID);
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetails = workshopDetails(AUTHOR_ID);

        ZBSVerifier.CheckResult result = ZBSVerifier.check(
            fixture.jarPath,
            fixture.jarHash,
            WORKSHOP_ID,
            true,
            true,
            workshopDetails,
            knownAuthors(AUTHOR_ID, fixture.publicKeyHex)
        );

        assertTrue(result.flags().hasAll(MF_VALID, MF_SIGNED));
        assertEquals(AUTHOR_ID, result.uploaderID());
        assertInstanceOf(ZBSVerifier.ValidSignature.class, result.verification());
    }

    @Test
    void noticeForUi_combinesShortAndDetailedMessages() {
        ZBSVerifier.Verification result = new ZBSVerifier.InvalidSignature(AUTHOR_ID, "Long explanation.");

        assertEquals("Invalid signature.\nLong explanation.", ZBSVerifier.noticeForUi(result));
    }

    private SignedFixture signedFixture(SteamID64 sid) throws Exception {
        Path jarPath = writeJar("jar contents for " + sid);
        String jarHash = sha256Hex(jarPath);

        byte[] seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) (i + 1);
        }
        Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(seed, 0);
        String publicKeyHex = hex(privateKey.generatePublicKey().getEncoded());

        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        byte[] message = ("ZBS:" + sid.value() + ":" + jarHash).getBytes(StandardCharsets.UTF_8);
        signer.update(message, 0, message.length);
        String signatureHex = hex(signer.generateSignature());

        Path zbsPath = jarPath.resolveSibling(jarPath.getFileName().toString() + ".zbs");
        Files.writeString(
            zbsPath,
            "ZBS\nSteamID64:" + sid.value() + "\nSignature:" + signatureHex + "\n",
            StandardCharsets.UTF_8
        );

        return new SignedFixture(jarPath, zbsPath, jarHash, publicKeyHex);
    }

    private Path writeJar(String content) throws Exception {
        Path jar = tempDir.resolve("test-" + Math.abs(content.hashCode()) + ".jar");
        Files.writeString(jar, content, StandardCharsets.UTF_8);
        return jar;
    }

    private static Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthors(SteamID64 sid, String publicKeyHex) {
        KnownAuthors.AuthorEntry author = new KnownAuthors.AuthorEntry();
        author.id = sid;
        author.name = "Test Author";
        author.keys = List.of(publicKeyHex);
        Map<SteamID64, KnownAuthors.AuthorEntry> authors = new LinkedHashMap<>();
        authors.put(sid, author);
        return authors;
    }

    private static Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetails(SteamID64 uploaderID) {
        return Map.of(
            WORKSHOP_ID,
            new SteamWorkshop.ItemDetails(
                new SteamWorkshop.BanInfo(false, ""),
                uploaderID
            )
        );
    }

    private static String sha256Hex(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private record SignedFixture(Path jarPath, Path zbsPath, String jarHash, String publicKeyHex) {}
}
