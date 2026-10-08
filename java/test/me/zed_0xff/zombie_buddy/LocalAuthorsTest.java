package me.zed_0xff.zombie_buddy;

import me.zed_0xff.zombie_buddy.KnownAuthors.AuthorEntry;
import me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import me.zed_0xff.zombie_buddy.ZBSVerifier.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

class LocalAuthorsTest {
    @org.junit.jupiter.api.io.TempDir Path tempRoot;
    String previousConfig;
    LocalAuthors.ProfileFetcher previousFetcher;

    @org.junit.jupiter.api.BeforeEach
    void isolateGlobalState() throws Exception {
        previousConfig = Agent.arguments.get("config_dir");
        previousFetcher = LocalAuthors.profileFetcher;
        restart();
        checks = requests = 0;
    }

    @org.junit.jupiter.api.AfterEach
    void restoreGlobalState() throws Exception {
        LocalAuthors.profileFetcher = previousFetcher;
        if (previousConfig == null) Agent.arguments.remove("config_dir");
        else Agent.arguments.put("config_dir", previousConfig);
        restart();
    }

    static int checks, requests;
    static final SteamID64 ID = new SteamID64(76561197974968019L);
    static Path root, jar, zbs;
    static KeyPair first, second;

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    static String key(KeyPair pair) {
        byte[] bytes = pair.getPublic().getEncoded();
        return HexFormat.of().formatHex(Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length));
    }

    static String hash() throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }

    static void sign(KeyPair pair, SteamID64 id) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(pair.getPrivate());
        s.update(("ZBS:" + id + ":" + hash()).getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                zbs,
                "ZBS\nSteamID64:"
                        + id
                        + "\nSignature:"
                        + HexFormat.of().formatHex(s.sign())
                        + "\n");
    }

    static Verification verify(SteamID64 uploader, Map<SteamID64, AuthorEntry> official)
            throws Exception {
        return ZBSVerifier.verify(jar, zbs, hash(), uploader, official);
    }

    static Map<SteamID64, AuthorEntry> official(String publicKey) {
        AuthorEntry entry = new AuthorEntry();
        entry.id = ID;
        entry.name = "Official name";
        entry.keys = List.of(publicKey);
        return Map.of(ID, entry);
    }

    static void restart() throws Exception {
        for (String name : List.of("STORES", "FETCHED", "FAILED")) {
            Field f = LocalAuthors.class.getDeclaredField(name);
            f.setAccessible(true);
            ((Map<?, ?>) f.get(null)).clear();
        }
    }

    static void profile(String name, String... keys) {
        LocalAuthors.profileFetcher =
                id -> {
                    requests++;
                    return new LocalAuthors.Profile(name, List.of(keys));
                };
    }

    static String cache() throws Exception {
        return Files.readString(root.resolve("authors.local.json"));
    }

    @org.junit.jupiter.api.Test
    void unavailableHttpClientRejectsVerificationWithoutCachingOrRetrying() throws Exception {
        root = tempRoot;
        Agent.arguments.put("config_dir", root.toString());
        jar = root.resolve("http-unavailable.jar");
        zbs = root.resolve("http-unavailable.jar.zbs");
        Files.writeString(jar, "signed artifact requiring profile lookup");
        first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        sign(first, ID);

        try (var steam = org.mockito.Mockito.mockStatic(SteamWorkshop.class)) {
            steam.when(() -> SteamWorkshop.authorProfileUrl(ID))
                    .thenReturn("https://steamcommunity.com/profiles/" + ID + "/");
            steam.when(SteamWorkshop::http)
                    .thenThrow(new java.io.IOException("Could not create HTTP client: selector unavailable"));
            Verification result = verify(ID, Map.of());
            check(result instanceof VerificationError, "Unavailable HTTP client fails closed");
            check(result.detailedMessage.contains("selector unavailable"), "Preserve HTTP failure details");
            check(!Files.exists(root.resolve("authors.local.json")), "Failed lookup cannot persist keys");
            check(verify(ID, Map.of()) instanceof VerificationError, "Reuse profile failure in this process");
            steam.verify(SteamWorkshop::http, org.mockito.Mockito.times(1));
        }
    }

    @org.junit.jupiter.api.Test
    void triesOfficialThenCacheThenProfileAndRefreshesOnlyVerifiedKeys() throws Exception {
        root = tempRoot;
        Agent.arguments.put("config_dir", root.toString());
        jar = root.resolve("fallback.jar");
        zbs = root.resolve("fallback.jar.zbs");
        Files.writeString(jar, "three-stage signature verification");
        first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair third = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        sign(first, ID);
        profile("Original name", key(first));
        check(
                verify(ID, official(key(first))) instanceof ValidSignature && requests == 0,
                "Official success short-circuits all fallback sources");
        check(
                !Files.exists(root.resolve("authors.local.json")),
                "Official success does not create local cache");
        check(
                verify(ID, official(key(second))) instanceof ValidSignature && requests == 1,
                "Official mismatch permits Steam fallback on cache miss");
        restart();
        LocalAuthors.profileFetcher =
                id -> {
                    throw new AssertionError("Cache hit must not fetch Steam");
                };
        check(
                verify(ID, official(key(second))) instanceof ValidSignature,
                "Official mismatch permits offline local key");
        check(
                verify(ID, official("not-a-key")) instanceof ValidSignature,
                "Unusable official keys also permit a valid later source");
        sign(third, ID);
        restart();
        profile("Changed name", key(third));
        check(
                verify(ID, official(key(second))) instanceof ValidSignature && requests == 2,
                "Both earlier sources can mismatch before Steam succeeds");
        check(
                cache().contains(key(third)) && !cache().contains(key(first)),
                "Verified Steam refresh replaces retired cached key");
        check(
                cache().contains("Original name") && !cache().contains("Changed name"),
                "Key refresh retains the first verified display name");
        String refreshed = cache();
        sign(first, ID);
        check(
                verify(ID, official(key(second))) instanceof InvalidSignature && requests == 2,
                "Reject only after official, local and fetched profile keys all mismatch");
        check(refreshed.equals(cache()), "Failed profile check cannot overwrite verified cache");
        restart();
        sign(third, ID);
        LocalAuthors.profileFetcher =
                id -> {
                    throw new AssertionError("Updated cache must verify offline");
                };
        check(
                verify(ID, official(key(second))) instanceof ValidSignature,
                "Refreshed local key survives restart and official mismatch");
    }

    @org.junit.jupiter.api.Test
    void verifiesPersistentCacheAndIdentityBoundaries() throws Exception {
        root = tempRoot;
        Files.createDirectories(root);
        Agent.arguments.put("config_dir", root.toString());
        jar = root.resolve("fixture.jar");
        zbs = root.resolve("fixture.jar.zbs");
        Files.writeString(jar, "first signed artifact");
        first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        profile("Erster Name ä & 中文", key(first), key(second));
        check(verify(ID, Map.of()) instanceof MissingSignature, "Missing signature");
        Files.writeString(zbs, "ZBS\nSteamID64:" + ID + "\nSignature:invalid\n");
        check(verify(ID, Map.of()) instanceof InvalidSignature, "Malformed signature");
        check(requests == 0, "No requests for missing/malformed signatures");
        sign(first, ID);
        check(
                verify(new SteamID64(76561198000000000L), Map.of()) instanceof InvalidSignature,
                "Uploader mismatch");
        check(requests == 0, "No request on uploader mismatch");
        check(verify(ID, official(key(first))) instanceof ValidSignature, "Official key");
        check(
                requests == 0 && !Files.exists(root.resolve("authors.local.json")),
                "Official success needs no fallback or cache write");
        check(
                verify(ID, official(key(second))) instanceof ValidSignature,
                "Official mismatch falls through to valid Steam profile key");
        check(verify(ID, Map.of()) instanceof ValidSignature, "Initial online verification");
        check(requests == 1, "Single initial request");
        String original = cache();
        check(
                original.contains("Erster Name") && original.contains(ID.toString()),
                "Store initial name and SteamID");
        check(
                original.contains(key(first)) && !original.contains(key(second)),
                "Only proven key stored");
        check(!original.contains("trusted"), "Caching grants no author approval");
        check(
                !Files.exists(root.resolve("config.json"))
                        && !Files.exists(root.resolve("mod_approvals.json")),
                "Approval stores untouched");
        check(
                verify(ID, Map.of()) instanceof ValidSignature && requests == 1,
                "Repeated checks are offline");
        Files.writeString(jar, "updated mod using same author key");
        sign(first, ID);
        check(
                verify(ID, Map.of()) instanceof ValidSignature && requests == 1,
                "Mod updates with same key need no profile request");
        check(original.equals(cache()), "Cache hits perform no writes");
        restart();
        LocalAuthors.profileFetcher =
                id -> {
                    requests++;
                    throw new java.io.IOException("offline");
                };
        check(
                verify(ID, Map.of()) instanceof ValidSignature && requests == 1,
                "Persistent cache across restart works offline");
        check(
                LocalAuthors.displayName(ID, official(key(first))).equals("Erster Name ä & 中文"),
                "First name remains display label");
        Method display =
                Loader.class.getDeclaredMethod("authorDisplayName", SteamID64.class, Map.class);
        display.setAccessible(true);
        check(
                display.invoke(null, ID, Map.of()).equals("Erster Name ä & 中文"),
                "Real approval display hook");
        // Old timestamps do not trigger profile access.
        String old = cache().replaceAll("20[0-9]{2}-[^\"]+Z", "2000-01-01T00:00:00Z");
        Files.writeString(root.resolve("authors.local.json"), old);
        restart();
        check(verify(ID, Map.of()) instanceof ValidSignature && requests == 1, "No age expiry");
        restart();
        profile("Changed Steam name", key(second));
        sign(second, ID);
        check(
                verify(ID, Map.of()) instanceof ValidSignature && requests == 2,
                "Mismatch refresh and key rotation");
        check(
                cache().contains(key(second)) && !cache().contains(key(first)),
                "Rotation drops no-longer-published key");
        check(
                cache().contains("Erster Name") && !cache().contains("Changed Steam name"),
                "Name immutable on key rotation");
        check(cache().contains("2000-01-01T00:00:00Z"), "First verification time retained");
        String rotated = cache();
        Files.writeString(jar, "tampered");
        check(verify(ID, Map.of()) instanceof InvalidSignature, "Tampered JAR rejected");
        check(
                requests == 2 && cache().equals(rotated),
                "Reuse current-run response; no cache poisoning");
        restart();
        LocalAuthors.profileFetcher =
                id -> {
                    requests++;
                    throw new java.io.IOException("HTTP 429");
                };
        check(
                verify(ID, Map.of()) instanceof VerificationError,
                "Network failure cannot approve mismatch");
        check(
                verify(ID, Map.of()) instanceof VerificationError && requests == 3,
                "Multiple mods do not repeat failed profile request");
        check(cache().equals(rotated), "Network failure preserves cache");
        restart();
        profile("Third name", key(second));
        sign(second, ID);
        check(
                verify(ID, official(key(first))) instanceof ValidSignature && requests == 3,
                "Official mismatch falls through to matching local key without Steam lookup");
        check(
                verify(ID, Map.of()) instanceof ValidSignature && requests == 3,
                "Local matching key still needs no lookup");
        // Simulate another process writing an author, then save a second author.
        SteamID64 other = new SteamID64(76561198000000001L);
        sign(first, other);
        profile("Other author", key(first));
        check(verify(other, Map.of()) instanceof ValidSignature, "Second author");
        check(
                cache().contains(ID.toString()) && cache().contains(other.toString()),
                "Merged author records");
        restart();
        String malformed = "{broken";
        Files.writeString(root.resolve("authors.local.json"), malformed);
        profile("Online only", key(first));
        sign(first, ID);
        check(
                verify(ID, Map.of()) instanceof ValidSignature,
                "Bad cache allows valid fresh verification");
        check(cache().equals(malformed), "Corrupt cache preserved rather than overwritten");
        String xml =
                "<profile><steamID64>"
                        + ID
                        + "</steamID64><steamID><![CDATA[Name < &"
                        + " 中文]]></steamID><summary><![CDATA[JavaModZBS:"
                        + key(first)
                        + "]]></summary></profile>";
        var parsed = LocalAuthors.parseProfile(ID, xml);
        check(
                parsed.name().equals("Name < & 中文") && parsed.keys().equals(List.of(key(first))),
                "Name and key from same XML response");
        boolean rejected = false;
        try {
            LocalAuthors.parseProfile(other, xml);
        } catch (Exception e) {
            rejected = true;
        }
        check(rejected, "Reject wrong profile identity");
        rejected = false;
        try {
            LocalAuthors.parseProfile(
                    ID, "<!DOCTYPE profile [<!ENTITY x SYSTEM 'file:///not-read'>]>" + xml);
        } catch (Exception e) {
            rejected = true;
        }
        check(rejected, "Reject XML external entities");
        rejected = false;
        try {
            LocalAuthors.parseProfile(
                    ID, xml.replace("</profile>", "<steamID>duplicate</steamID></profile>"));
        } catch (Exception e) {
            rejected = true;
        }
        check(rejected, "Reject ambiguous profile names");
        check(
                LocalAuthors.parseProfile(ID, xml.replace("JavaModZBS:", "no key:"))
                        .keys()
                        .isEmpty(),
                "Missing profile key");
        System.out.println(
                "PASS author cache: "
                        + checks
                        + " checks; real original Ed25519 verifier; "
                        + requests
                        + " controlled profile requests; no external network");
    }
}
