package me.zed_0xff.zombie_buddy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import me.zed_0xff.zombie_buddy.KnownAuthors.AuthorEntry;
import me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import me.zed_0xff.zombie_buddy.ZBSVerifier.*;

import org.w3c.dom.*;

import java.io.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Persistent Steam-profile keys used after the signed authors list cannot verify a JAR. Keys are
 * saved only after they verify a JAR. Cache entries have no expiry; matching signatures require no
 * profile access and the first verified name is retained. This stores identity information, not
 * author trust or JAR approval decisions.
 */
final class LocalAuthors {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern KEY =
            Pattern.compile("JavaModZBS:([0-9a-fA-F]{64})(?![0-9a-fA-F])");
    private static final Map<Path, Store> STORES = new HashMap<>();
    // One profile response (including failure) per author/process; multiple mods cannot hammer
    // Steam.
    private static final Map<String, Profile> FETCHED = new HashMap<>();
    private static final Map<String, String> FAILED = new HashMap<>();

    static final class Entry {
        String steamId, name, source, firstVerifiedAt, lastSteamVerifiedAt;
        List<String> keys;
    }

    static final class Store {
        int schemaVersion = 1;
        Map<String, Entry> authors = new LinkedHashMap<>();
    }

    record Profile(String name, List<String> keys) {}

    @FunctionalInterface
    interface ProfileFetcher {
        Profile fetch(SteamID64 id) throws Exception;
    }

    // Package-private seam for isolated tests; no runtime config can redirect profile requests.
    static ProfileFetcher profileFetcher = LocalAuthors::fetchProfile;

    private static Path cachePath() {
        return Agent.configDir().resolve("authors.local.json").toAbsolutePath().normalize();
    }

    private static Store read(Path path) throws IOException {
        if (!Files.exists(path)) return new Store();
        try {
            Store store =
                    JSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Store.class);
            if (store == null || store.schemaVersion != 1 || store.authors == null)
                throw new IOException("Unsupported local author cache");
            for (var row : store.authors.entrySet()) {
                Entry e = row.getValue();
                if (e == null
                        || !row.getKey().matches("[0-9]{17}")
                        || !row.getKey().equals(e.steamId)
                        || e.name == null
                        || e.name.isBlank()
                        || !"steam_profile".equals(e.source)
                        || e.keys == null
                        || e.keys.isEmpty()
                        || e.keys.stream().anyMatch(k -> k == null || !k.matches("[0-9a-f]{64}")))
                    throw new IOException("Invalid local author cache entry");
                Instant.parse(e.firstVerifiedAt);
                Instant.parse(e.lastSteamVerifiedAt);
            }
            return store;
        } catch (RuntimeException e) {
            throw new IOException("Malformed local author cache", e);
        }
    }

    private static Store store(Path path) {
        return STORES.computeIfAbsent(
                path,
                p -> {
                    try {
                        return read(p);
                    } catch (IOException e) {
                        Logger.warn("Local author cache ignored: " + e.getMessage());
                        return new Store();
                    }
                });
    }

    static synchronized Verification verify(ZBSVerifier.ParsedZBS parsed, String hash) {
        SteamID64 id = parsed.sid;
        Path path = cachePath();
        String sid = id.toString();
        Entry cached = store(path).authors.get(sid);
        if (cached != null) {
            Verification result =
                    ZBSVerifier.verifyWithKeys(parsed, hash, cached.keys, "local author cache");
            if (result instanceof ValidSignature) return result;
        }
        // A Coop/server process may already have saved this author since our initial read.
        try {
            Entry disk = read(path).authors.get(sid);
            if (disk != null && (cached == null || !disk.keys.equals(cached.keys))) {
                STORES.get(path).authors.put(sid, disk);
                cached = disk;
                Verification result =
                        ZBSVerifier.verifyWithKeys(parsed, hash, disk.keys, "local author cache");
                if (result instanceof ValidSignature) return result;
            }
        } catch (IOException e) {
            /* Preserve corrupt data; a successful online check can still verify this JAR. */
        }

        Profile profile;
        if (FAILED.containsKey(sid)) return new VerificationError(id, FAILED.get(sid));
        try {
            profile = FETCHED.get(sid);
            if (profile == null) {
                Logger.info(
                        "Local author cache: Steam profile lookup for "
                                + sid
                                + (cached == null
                                        ? " (first verification)"
                                        : " (signature mismatch)"));
                profile = profileFetcher.fetch(id);
                if (profile == null
                        || profile.name() == null
                        || profile.name().isBlank()
                        || profile.keys() == null
                        || profile.keys().isEmpty())
                    throw new IOException("Steam profile has no usable name/signing key");
                FETCHED.put(sid, profile);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String message = "Steam profile verification unavailable: " + e.getMessage();
            FAILED.put(sid, message);
            return new VerificationError(id, message);
        }
        for (String key : profile.keys()) {
            if (key == null || !key.matches("[0-9a-fA-F]{64}")) continue;
            key = key.toLowerCase(Locale.ROOT);
            Verification result =
                    ZBSVerifier.verifyWithKeys(parsed, hash, List.of(key), "Steam profile");
            if (result instanceof ValidSignature) {
                try {
                    save(path, sid, profile, key);
                } catch (IOException e) {
                    Logger.warn("Verified author could not be cached: " + e.getMessage());
                }
                return result;
            }
        }
        return new InvalidSignature(id, "Signature does not match the Steam profile signing keys.");
    }

    private static void save(Path path, String sid, Profile profile, String matchedKey)
            throws IOException {
        Files.createDirectories(path.getParent());
        try (FileChannel channel =
                        FileChannel.open(
                                path.resolveSibling("authors.local.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            Store merged =
                    read(path); // Re-read under process lock; never overwrite another author's
            // entries.
            Entry entry = merged.authors.get(sid);
            String now = Instant.now().toString();
            if (entry == null) {
                entry = new Entry();
                entry.steamId = sid;
                entry.name = profile.name();
                entry.source = "steam_profile";
                entry.firstVerifiedAt = now;
                merged.authors.put(sid, entry);
            }
            // Retain previously proven keys only while they are still published in this fetched
            // profile.
            Set<String> published = new HashSet<>();
            for (String k : profile.keys())
                if (k != null) published.add(k.toLowerCase(Locale.ROOT));
            Set<String> proven = new LinkedHashSet<>();
            if (entry.keys != null)
                for (String k : entry.keys) if (published.contains(k)) proven.add(k);
            proven.add(matchedKey);
            entry.keys = new ArrayList<>(proven);
            entry.lastSteamVerifiedAt = now;
            Path temp = Files.createTempFile(path.getParent(), "authors.local-", ".tmp");
            try {
                Files.writeString(temp, JSON.toJson(merged) + "\n", StandardCharsets.UTF_8);
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            STORES.put(path, merged);
        }
    }

    static synchronized String displayName(SteamID64 id, Map<SteamID64, AuthorEntry> official) {
        if (id == null) return "";
        Entry cached = store(cachePath()).authors.get(id.toString());
        if (cached != null) return cached.name;
        AuthorEntry entry = official == null ? null : official.get(id);
        return entry != null && entry.name != null && !entry.name.isBlank()
                ? entry.name
                : id.toString();
    }

    private static Profile fetchProfile(SteamID64 id) throws Exception {
        URI uri = URI.create(SteamWorkshop.authorProfileUrl(id) + "?xml=1");
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(SteamWorkshop.HTTP_TIMEOUT)
                        .header("User-Agent", "ZombieBuddy")
                        .GET()
                        .build();
        HttpResponse<String> response =
                SteamWorkshop.http().send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new IOException("Steam HTTP " + response.statusCode());
        return parseProfile(id, response.body());
    }

    static Profile parseProfile(SteamID64 id, String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document doc =
                factory.newDocumentBuilder()
                        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element root = doc.getDocumentElement();
        if (!"profile".equals(root.getTagName()) || !id.toString().equals(field(root, "steamID64")))
            throw new IOException("Steam profile identity mismatch");
        String name = field(root, "steamID");
        if (name.isBlank()) throw new IOException("Steam profile name unavailable");
        Matcher matcher = KEY.matcher(field(root, "summary"));
        Set<String> found = new LinkedHashSet<>();
        while (matcher.find()) found.add(matcher.group(1).toLowerCase(Locale.ROOT));
        return new Profile(name, new ArrayList<>(found));
    }

    private static String field(Element root, String tag) throws IOException {
        String value = null;
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element e && tag.equals(e.getTagName())) {
                if (value != null) throw new IOException("Duplicate Steam profile field " + tag);
                value = e.getTextContent();
            }
        return value == null ? "" : value;
    }
}
