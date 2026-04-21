package me.zed_0xff.zombie_buddy;

import java.io.IOException;
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
import java.util.Map;

/**
 * Fetches {@value #REMOTE_URL} and caches it under {@code ~/.zombie_buddy/}{@value #CACHE_FILE_NAME}.
 * Minimal line parser (not full YAML): non-empty, non-{@code #} lines split on first {@code ": "}
 * as {@code SteamID64: DisplayName}; builds a map of SteamID64 → display name.
 */
public final class SteamAuthorNames {
    public static final String REMOTE_URL      = "https://raw.githubusercontent.com/zed-0xff/ZombieBuddy/refs/heads/master/authors.yml";
    public static final String CACHE_FILE_NAME = "authors.yml";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private SteamAuthorNames() {}

    public static Path cachePath() {
        return JavaModApprovalsStore.directory().resolve(CACHE_FILE_NAME);
    }

    /**
     * Refreshes from the network when possible, writes cache on success, otherwise reads stale cache.
     */
    public static Map<String, String> loadSteamIdToDisplayName() {
        String body = fetchRemoteBody();
        if (body != null) {
            try {
                Files.createDirectories(JavaModApprovalsStore.directory());
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
        return body != null ? parseAuthorsYml(body) : Collections.emptyMap();
    }

    private static String fetchRemoteBody() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(REMOTE_URL))
                .timeout(Duration.ofSeconds(25))
                .header(
                    "User-Agent",
                    "ZombieBuddy/SteamAuthorNames (Java; +https://github.com/zed-0xff/ZombieBuddy)"
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

    /** Each line: {@code SteamID64: Display name} (split on first {@code ": "}). */
    static Map<String, String> parseAuthorsYml(String body) {
        Map<String, String> out = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        for (String line : body.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int sep = s.indexOf(": ");
            if (sep < 0) {
                continue;
            }
            String steamId64 = s.substring(0, sep).trim();
            String displayName = s.substring(sep + 2).trim();
            if (!steamId64.isEmpty() && !displayName.isEmpty()) {
                out.put(steamId64, displayName);
            }
        }
        return out;
    }
}
