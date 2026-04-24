package me.zed_0xff.zombie_buddy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JavaModApprovalsStoreTest {

    @Test
    void parseFixture_loadsAllFields() throws IOException {
        String json = loadFixture("java_mod_approvals_sample.json");
        JavaModApprovalsStore.FileData data = ZBGson.PRETTY.fromJson(json, JavaModApprovalsStore.FileData.class);

        assertEquals(1, data.formatVersion);
        assertEquals(3, data.mods.size());
        assertEquals(2, data.authors.size());

        // Check first mod
        JavaModApprovalsStore.ModEntry mod1 = data.mods.get(0);
        assertEquals("TestMod1", mod1.id);
        assertEquals(3709229404L, mod1.workshopId.value());
        assertEquals("c180d888eac78369a58dd266e98095ca7e86e16533294ee43ec750e729827064", mod1.jarHash);
        assertTrue(mod1.decision);
        assertEquals("2026-04-01T12:34:56Z", mod1.time);
        assertEquals(76561198043849998L, mod1.authorId.value());

        // Check second mod
        JavaModApprovalsStore.ModEntry mod2 = data.mods.get(1);
        assertEquals("TestMod2", mod2.id);
        assertFalse(mod2.decision);

        // Check third mod (no workshop_id, no author_id)
        JavaModApprovalsStore.ModEntry mod3 = data.mods.get(2);
        assertEquals("LocalMod", mod3.id);
        assertNull(mod3.workshopId);
        assertNull(mod3.authorId);
        assertTrue(mod3.decision);

        // Check authors
        JavaModApprovalsStore.AuthorEntry ae1 = findAuthorById(data, 76561198043849998L);
        assertNotNull(ae1);
        assertEquals(76561198043849998L, ae1.id.value());
        assertTrue(ae1.trust);
        assertEquals("TrustedAuthor", ae1.name);
        assertEquals(1, ae1.keys.size());
        assertTrue(ae1.keys.contains("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"));

        JavaModApprovalsStore.AuthorEntry ae2 = findAuthorById(data, 76561198012345678L);
        assertNotNull(ae2);
        assertFalse(ae2.trust);
    }

    @Test
    void roundTrip_preservesData() {
        JavaModApprovalsStore.FileData original = new JavaModApprovalsStore.FileData();
        
        JavaModApprovalsStore.ModEntry mod = new JavaModApprovalsStore.ModEntry(
            "RoundTripMod",
            new JavaModInfo.WorkshopItemID(9876543210L),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            true,
            "2026-04-24T15:30:00Z",
            new SteamID64(76561198099999999L)
        );
        original.mods.add(mod);

        Set<String> keys = new LinkedHashSet<>();
        keys.add("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210");
        original.authors.add(new JavaModApprovalsStore.AuthorEntry(
            new SteamID64(76561198099999999L), true, keys, "RoundTripAuthor"));

        String json = ZBGson.PRETTY.toJson(original);
        JavaModApprovalsStore.FileData parsed = ZBGson.PRETTY.fromJson(json, JavaModApprovalsStore.FileData.class);

        assertEquals(original.formatVersion, parsed.formatVersion);
        assertEquals(original.mods.size(), parsed.mods.size());
        assertEquals(original.authors.size(), parsed.authors.size());

        JavaModApprovalsStore.ModEntry parsedMod = parsed.mods.get(0);
        assertEquals(mod.id, parsedMod.id);
        assertEquals(mod.workshopId.value(), parsedMod.workshopId.value());
        assertEquals(mod.jarHash, parsedMod.jarHash);
        assertEquals(mod.decision, parsedMod.decision);
        assertEquals(mod.time, parsedMod.time);
        assertEquals(mod.authorId.value(), parsedMod.authorId.value());

        JavaModApprovalsStore.AuthorEntry parsedAuthor = findAuthorById(parsed, 76561198099999999L);
        assertNotNull(parsedAuthor);
        assertEquals("RoundTripAuthor", parsedAuthor.name);
        assertTrue(parsedAuthor.trust);
    }

    @Test
    void serialize_writesNumbersNotStrings() {
        JavaModApprovalsStore.FileData data = new JavaModApprovalsStore.FileData();
        data.mods.add(new JavaModApprovalsStore.ModEntry(
            "NumericTest",
            new JavaModInfo.WorkshopItemID(1234567890L),
            "hash",
            true,
            null,
            new SteamID64(76561198000000000L)
        ));
        data.authors.add(new JavaModApprovalsStore.AuthorEntry(
            new SteamID64(76561198000000000L), true, null, "TestAuthor"));

        String json = ZBGson.PRETTY.toJson(data);
        
        // workshop_id should be a number, not a quoted string
        assertTrue(json.contains("\"workshop_id\": 1234567890"), 
            "workshop_id should be numeric: " + json);
        assertFalse(json.contains("\"workshop_id\": \"1234567890\""),
            "workshop_id should not be a string: " + json);
        
        // author_id in mods should be a number
        assertTrue(json.contains("\"author_id\": 76561198000000000"),
            "author_id should be numeric: " + json);
        
        // id in authors should be a number
        assertTrue(json.contains("\"id\": 76561198000000000"),
            "author id should be numeric: " + json);
    }

    @Test
    void nullFields_handledGracefully() {
        JavaModApprovalsStore.FileData data = new JavaModApprovalsStore.FileData();
        data.mods.add(new JavaModApprovalsStore.ModEntry(
            "NullFieldsMod",
            null,  // no workshop_id
            "somehash",
            false,
            null,  // no time
            null   // no author_id
        ));

        String json = ZBGson.PRETTY.toJson(data);
        JavaModApprovalsStore.FileData parsed = ZBGson.PRETTY.fromJson(json, JavaModApprovalsStore.FileData.class);

        assertEquals(1, parsed.mods.size());
        JavaModApprovalsStore.ModEntry mod = parsed.mods.get(0);
        assertEquals("NullFieldsMod", mod.id);
        assertNull(mod.workshopId);
        assertNull(mod.authorId);
        assertNull(mod.time);
    }

    private static JavaModApprovalsStore.AuthorEntry findAuthorById(JavaModApprovalsStore.FileData data, long id) {
        for (JavaModApprovalsStore.AuthorEntry ae : data.authors) {
            if (ae.id != null && ae.id.value() == id) {
                return ae;
            }
        }
        return null;
    }

    private static String loadFixture(String name) throws IOException {
        try (InputStream is = JavaModApprovalsStoreTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (is == null) {
                // Try alternate path for gradle test runner
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
