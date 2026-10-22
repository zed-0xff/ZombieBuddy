package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import static me.zed_0xff.zombie_buddy.ModFlags.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

class JarBatchApprovalProtocolTest {

    @TempDir
    Path tempDir;

    @Test
    void parseRequestFixture_loadsAllFields() throws IOException {
        String json = loadFixture("jar_batch_request_sample.json");
        Path reqPath = tempDir.resolve("request.json");
        Files.writeString(reqPath, json);

        List<JarBatchApprovalProtocol.Entry> entries = JarBatchApprovalProtocol.readRequest(reqPath);

        assertEquals(2, entries.size());

        // Check first entry
        JarBatchApprovalProtocol.Entry e1 = entries.get(0);
        assertEquals("TestMod", e1.modId);
        assertEquals(3709229404L, e1.workshopItemId.value());
        assertEquals(Path.of("/path/to/mod.jar"), e1.jarAbsolutePath);
        assertEquals("abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234", e1.sha256);
        assertNotNull(e1.date);
        assertEquals(Boolean.TRUE, e1.decision);
        assertEquals("Test Mod Display Name", e1.modDisplayName);
        assertTrue(e1.zbs.valid());
        assertEquals(76561198043849998L, e1.zbs.authorSteamId().value());
        assertNull(e1.steamBan);

        // Check second entry (no workshopItemId, no authorSteamId)
        JarBatchApprovalProtocol.Entry e2 = entries.get(1);
        assertEquals("LocalMod", e2.modId);
        assertNull(e2.workshopItemId);
        assertNull(e2.zbs.authorSteamId());
        assertFalse(e2.zbs.valid());
        assertTrue(e2.zbs.unsigned());
    }

    @Test
    void parseResponseFixture_loadsAllFields() throws IOException {
        String json = loadFixture("jar_batch_response_sample.json");
        Path respPath = tempDir.resolve("response.json");
        Files.writeString(respPath, json);

        List<JarBatchApprovalProtocol.Entry> lines = JarBatchApprovalProtocol.readResponse(respPath);

        assertNotNull(lines);
        assertEquals(2, lines.size());

        // Check first line
        JarBatchApprovalProtocol.Entry l1 = lines.get(0);
        assertEquals("TestMod", l1.modId);
        assertEquals(3709229404L, l1.workshopItemId.value());
        assertEquals("abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234", l1.sha256);
        assertEquals(Boolean.TRUE, l1.decision);
        assertEquals(76561198043849998L, l1.zbs.authorSteamId().value());

        // Check second line (no workshopItemId, no authorSteamId)
        JarBatchApprovalProtocol.Entry l2 = lines.get(1);
        assertEquals("LocalMod", l2.modId);
        assertNull(l2.workshopItemId);
        assertEquals(Boolean.FALSE, l2.decision);
        assertNull(l2.zbs.authorSteamId());
    }

    @Test
    void requestRoundTrip_preservesData() throws IOException {
        List<JarBatchApprovalProtocol.Entry> original = new ArrayList<>();
        original.add(new JarBatchApprovalProtocol.Entry(
            "RoundTripMod",
            new WorkshopItemID(9876543210L),
            Path.of("/path/to/roundtrip.jar"),
            Path.of("/path/to/mod.info"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            new Date(1_777_000_000_000L),
            Boolean.FALSE,
            ModFlags.EMPTY,
            "Round Trip Mod",
            new JarBatchApprovalProtocol.Entry.ZBSignature(true, new SteamID64(76561198099999999L), ""),
            null
        ));

        Path reqPath = tempDir.resolve("roundtrip_request.json");
        JarBatchApprovalProtocol.writeRequest(reqPath, original);
        List<JarBatchApprovalProtocol.Entry> parsed = JarBatchApprovalProtocol.readRequest(reqPath);

        assertEquals(1, parsed.size());
        JarBatchApprovalProtocol.Entry e = parsed.get(0);
        assertEquals("RoundTripMod", e.modId);
        assertEquals(9876543210L, e.workshopItemId.value());
        assertEquals(76561198099999999L, e.zbs.authorSteamId().value());
    }

    @Test
    void responseRoundTrip_preservesData() throws IOException {
        List<JarBatchApprovalProtocol.Entry> original = new ArrayList<>();
        original.add(new JarBatchApprovalProtocol.Entry(
            "RoundTripMod",
            new WorkshopItemID(9876543210L),
            Path.of("/path/to/roundtrip.jar"),
            Path.of("/path/to/mod.info"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            new Date(1_777_000_000_000L),
            Boolean.TRUE,
            new ModFlags(MF_PERSIST),
            "Round Trip Mod",
            new JarBatchApprovalProtocol.Entry.ZBSignature(true, new SteamID64(76561198099999999L), ""),
            null
        ));

        Path respPath = tempDir.resolve("roundtrip_response.json");
        JarBatchApprovalProtocol.writeResponse(respPath, original);
        List<JarBatchApprovalProtocol.Entry> parsed = JarBatchApprovalProtocol.readResponse(respPath);

        assertNotNull(parsed);
        assertEquals(1, parsed.size());
        JarBatchApprovalProtocol.Entry l = parsed.get(0);
        assertEquals("RoundTripMod", l.modId);
        assertEquals(9876543210L, l.workshopItemId.value());
        assertEquals(Boolean.TRUE, l.decision);
        assertEquals(76561198099999999L, l.zbs.authorSteamId().value());
    }

    @Test
    void readResponse_rejectsBadHeader() throws IOException {
        String json = """
            {
              "header": "WRONG_HEADER",
              "entries": []
            }
            """;
        Path respPath = tempDir.resolve("bad_header_response.json");
        Files.writeString(respPath, json);

        List<JarBatchApprovalProtocol.Entry> result = JarBatchApprovalProtocol.readResponse(respPath);
        assertNull(result);
    }

    @Test
    void readRequest_rejectsBadHeader() throws IOException {
        String json = """
            {
              "header": "WRONG_HEADER",
              "entries": []
            }
            """;
        Path reqPath = tempDir.resolve("bad_header_request.json");
        Files.writeString(reqPath, json);

        assertThrows(IOException.class, () -> JarBatchApprovalProtocol.readRequest(reqPath));
    }

    @Test
    void serialize_writesNumbersNotStrings() throws IOException {
        List<JarBatchApprovalProtocol.Entry> entries = new ArrayList<>();
        entries.add(new JarBatchApprovalProtocol.Entry(
            "NumericTest",
            new WorkshopItemID(1234567890L),
            Path.of("/path/to/roundtrip.jar"),
            Path.of("/path/to/mod.info"),
            "hash",
            new Date(1_777_000_000_000L),
            null,
            ModFlags.EMPTY,
            "",
            new JarBatchApprovalProtocol.Entry.ZBSignature(true, new SteamID64(76561198000000000L), ""),
            null
        ));

        Path reqPath = tempDir.resolve("numeric_request.json");
        JarBatchApprovalProtocol.writeRequest(reqPath, entries);
        String json = Files.readString(reqPath);

        assertTrue(json.contains("\"workshopItemId\": 1234567890"),
            "workshopItemId should be numeric: " + json);
        assertTrue(json.contains("\"authorSteamId\": 76561198000000000"),
            "authorSteamId should be numeric: " + json);
    }

    @Test
    void readRequest_rejectsMissingOrNullSignatureMetadata() throws IOException {
        for (String signature : new String[]{"", ",\"zbs\":null"}) {
            Path path = tempDir.resolve("missing-signature-request.json");
            Files.writeString(path, "{\"header\":\"" + JarBatchApprovalProtocol.HDR_REQ
                    + "\",\"entries\":[{\"modId\":\"broken\",\"flags\":8" + signature + "}]}");
            assertThrows(IOException.class, () -> JarBatchApprovalProtocol.readRequest(path));
        }
    }

    @Test
    void readResponse_rejectsMissingOrNullSignatureMetadata() throws IOException {
        for (String signature : new String[]{"", ",\"zbs\":null"}) {
            Path path = tempDir.resolve("missing-signature-response.json");
            Files.writeString(path, "{\"header\":\"" + JarBatchApprovalProtocol.HDR_RESP
                    + "\",\"entries\":[{\"modId\":\"broken\",\"sha256\":\"hash\",\"flags\":8" + signature + "}]}");
            assertThrows(IOException.class, () -> JarBatchApprovalProtocol.readResponse(path));
        }
    }

    @Test
    void readers_rejectNullRows() throws IOException {
        Path path = tempDir.resolve("null-row.json");
        Files.writeString(path, "{\"header\":\"" + JarBatchApprovalProtocol.HDR_REQ + "\",\"entries\":[null]}");
        assertThrows(IOException.class, () -> JarBatchApprovalProtocol.readRequest(path));
        Files.writeString(path, "{\"header\":\"" + JarBatchApprovalProtocol.HDR_RESP + "\",\"entries\":[null]}");
        assertThrows(IOException.class, () -> JarBatchApprovalProtocol.readResponse(path));
    }

    @Test
    void readers_rejectMissingFlags() throws IOException {
        Path path = tempDir.resolve("missing-flags.json");
        String entry = "\",\"entries\":[{\"modId\":\"broken\",\"zbs\":{\"valid\":false,\"notice\":\"\"}}]}";
        Files.writeString(path, "{\"header\":\"" + JarBatchApprovalProtocol.HDR_REQ + entry);
        assertThrows(IOException.class, () -> JarBatchApprovalProtocol.readRequest(path));
        Files.writeString(path, "{\"header\":\"" + JarBatchApprovalProtocol.HDR_RESP + entry);
        assertThrows(IOException.class, () -> JarBatchApprovalProtocol.readResponse(path));
    }

    @Test
    void constructor_normalizesOptionalSignatureAndFlags() throws IOException {
        var entry = new JarBatchApprovalProtocol.Entry("unsigned", null, null, null, "hash", null,
                false, null, "Unsigned", null, null);
        assertNotNull(entry.zbs);
        assertTrue(entry.zbs.unsigned());
        assertEquals(ModFlags.EMPTY, entry.flags);
        Path path = tempDir.resolve("unsigned.json");
        JarBatchApprovalProtocol.writeRequest(path, List.of(entry));
        assertTrue(JarBatchApprovalProtocol.readRequest(path).get(0).zbs.unsigned());
    }

    @Test
    void responseValidation_preservesLegitimateNullResetDecision() throws IOException {
        var entry = new JarBatchApprovalProtocol.Entry("reset", null, null, null, "hash", null,
                null, new ModFlags(MF_PERSIST), "Reset", null, null);
        Path path = tempDir.resolve("reset.json");
        JarBatchApprovalProtocol.writeResponse(path, List.of(entry));
        var reset = JarBatchApprovalProtocol.readResponse(path).get(0);
        assertNull(reset.decision);
        assertTrue(reset.flags.has(MF_PERSIST));
        assertTrue(reset.zbs.unsigned());
    }

    @Test
    void invalidLaterRow_preventsAnyEarlierResetFromBeingApplied() throws IOException {
        Path path = tempDir.resolve("partly-invalid-response.json");
        Files.writeString(path, "{\"header\":\"" + JarBatchApprovalProtocol.HDR_RESP
                + "\",\"entries\":[{\"sha256\":\"keep-hash\",\"flags\":8,\"zbs\":{\"valid\":false,\"notice\":\"\"}},"
                + "{\"sha256\":\"broken-hash\",\"flags\":8}]}");
        var saved = new ModApprovalsStore.ModEntry("keep", null, "keep-hash", true, "before", null);
        var stored = new ArrayList<>(List.of(saved));
        assertThrows(IOException.class, () -> ApprovalChoices.applyClears(
                JarBatchApprovalProtocol.readResponse(path), stored,
                author -> fail("Invalid batch must not change author trust")));
        assertEquals(1, stored.size());
        assertSame(saved, stored.get(0));
    }

    @Test
    void readersRejectExplicitNullFlagsWithoutChangingPersistentAdapter() throws IOException {
        assertSame(ModFlags.EMPTY, ZBGson.PRETTY.fromJson("null", ModFlags.class));
        Path path = tempDir.resolve("null-flags.json");
        for (boolean response : List.of(false, true)) {
            String header = response ? JarBatchApprovalProtocol.HDR_RESP : JarBatchApprovalProtocol.HDR_REQ;
            Files.writeString(path, "{\"header\":\"" + header + "\",\"entries\":[{\"flags\":null,\"zbs\":{}}]}");
            assertThrows(IOException.class, () -> {
                if (response) JarBatchApprovalProtocol.readResponse(path);
                else JarBatchApprovalProtocol.readRequest(path);
            });
        }
    }

    @Test
    void explicitNullFlagsInLaterRowRejectWholeRequestAndResponse() throws IOException {
        Path path = tempDir.resolve("null-flags-after-reset.json");
        var saved = new ModApprovalsStore.ModEntry("keep", null, "keep-hash", true, "before", null);
        var stored = new ArrayList<>(List.of(saved));
        for (boolean response : List.of(false, true)) {
            String header = response ? JarBatchApprovalProtocol.HDR_RESP : JarBatchApprovalProtocol.HDR_REQ;
            Files.writeString(path, "{\"header\":\"" + header
                    + "\",\"entries\":[{\"sha256\":\"keep-hash\",\"flags\":8,\"zbs\":{}},"
                    + "{\"flags\":null,\"zbs\":{}}]}");
            assertThrows(IOException.class, () -> ApprovalChoices.applyClears(
                    response ? JarBatchApprovalProtocol.readResponse(path) : JarBatchApprovalProtocol.readRequest(path),
                    stored, author -> fail("Malformed batch must not change trust")));
            assertEquals(List.of(saved), stored, "Earlier reset must not be applied");
        }
    }

    private static String loadFixture(String name) throws IOException {
        try (InputStream is = JarBatchApprovalProtocolTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (is == null) {
                Path p = Path.of("test/fixtures", name);
                if (Files.exists(p)) {
                    return Files.readString(p, StandardCharsets.UTF_8);
                }
                throw new IOException("Fixture not found: " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
