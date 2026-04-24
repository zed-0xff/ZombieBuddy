package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches {@value #REMOTE_URL} and caches it under {@code ~/.zombie_buddy/}{@value #CACHE_FILE_NAME}.
 * JSON format: array of {@code {"id": <long>, "name": "<string>", "keys": [...]}}
 */
public final class KnownAuthors {
    public static final String REMOTE_URL      = "https://raw.githubusercontent.com/zed-0xff/ZombieBuddy/refs/heads/master/authors.json";
    public static final String CACHE_FILE_NAME = "authors.json";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final Type AUTHOR_LIST_TYPE = new TypeToken<List<AuthorEntry>>() {}.getType();

    public static final class AuthorEntry {
        @SerializedName("id")
        public SteamID64 id;
        @SerializedName("name")
        public String name;
        @SerializedName("keys")
        public List<String> keys;
    }

    private KnownAuthors() {}

    public static Path cachePath() {
        return ModApprovalsStore.directory().resolve(CACHE_FILE_NAME);
    }

    /**
     * Refreshes from the network when possible, writes cache on success, otherwise reads stale cache.
     */
    public static Map<SteamID64, String> loadSteamIdToDisplayName() {
        String body = fetchRemoteBody();
        if (body != null) {
            try {
                Files.createDirectories(ModApprovalsStore.directory());
                Files.writeString(cachePath(), body, StandardCharsets.UTF_8);
            } catch (IOException e) {
                Logger.warn("Could not write author names cache: " + e.getMessage());
            }
        } else {
            try {
                Path p = cachePath();
                if (Files.isRegularFile(p)) {
                    body = Files.readString(p, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                Logger.warn("Could not read author names cache: " + e.getMessage());
            }
        }
        return body != null ? parseAuthorsJSON(body) : Collections.emptyMap();
    }

    /**
     * Loads the full author entries (including keys) from network or cache.
     */
    public static List<AuthorEntry> loadAuthorEntries() {
        String body = fetchRemoteBody();
        if (body != null) {
            try {
                Files.createDirectories(ModApprovalsStore.directory());
                Files.writeString(cachePath(), body, StandardCharsets.UTF_8);
            } catch (IOException e) {
                Logger.warn("Could not write author names cache: " + e.getMessage());
            }
        } else {
            try {
                Path p = cachePath();
                if (Files.isRegularFile(p)) {
                    body = Files.readString(p, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                Logger.warn("Could not read author names cache: " + e.getMessage());
            }
        }
        if (body == null || body.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<AuthorEntry> entries = ZBGson.PRETTY.fromJson(body, AUTHOR_LIST_TYPE);
            return entries != null ? entries : Collections.emptyList();
        } catch (Exception e) {
            Logger.warn("Failed to parse authors.json: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String fetchRemoteBody() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(REMOTE_URL))
                .timeout(Duration.ofSeconds(25))
                .header(
                    "User-Agent",
                    "ZombieBuddy/KnownAuthors (Java; +https://github.com/zed-0xff/ZombieBuddy)"
                )
                .GET()
                .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                return resp.body();
            }
            Logger.warn("Author names list HTTP " + resp.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.warn("Author names list fetch interrupted");
        } catch (IOException e) {
            Logger.warn("Author names list fetch failed: " + e.getMessage());
        }
        return null;
    }

    static Map<SteamID64, String> parseAuthorsJSON(String body) {
        Map<SteamID64, String> out = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        try {
            List<AuthorEntry> entries = ZBGson.PRETTY.fromJson(body, AUTHOR_LIST_TYPE);
            if (entries != null) {
                for (AuthorEntry e : entries) {
                    if (e.id != null && e.name != null && !e.name.isEmpty()) {
                        out.put(e.id, e.name);
                    }
                }
            }
        } catch (Exception e) {
            Logger.warn("Failed to parse authors.json: " + e.getMessage());
        }
        return out;
    }
}
