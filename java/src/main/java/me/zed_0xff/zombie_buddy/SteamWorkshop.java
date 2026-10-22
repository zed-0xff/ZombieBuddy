package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Steam Workshop API client for fetching mod details and ban status.
 */
public final class SteamWorkshop {
    public static final String PZ_APP_ID = "108600";

    /** Strongly-typed Steam account id. */
    public static record SteamID64(long value) {
        public SteamID64(String s) { this(Long.parseLong(s)); }
        @Override public String toString() { return Long.toString(value); }
    }

    /** Strongly-typed Steam Workshop item id ({@code publishedfileid}). */
    public static record WorkshopItemID(long value) {}

    public static String authorProfileUrl(SteamID64 steamId) {
        return steamId != null
            ? "https://steamcommunity.com/profiles/" + steamId.value() + "/"
            : "";
    }

    public static String authorWorkshopUrl(SteamID64 steamId) {
        return steamId != null
            ? "https://steamcommunity.com/profiles/" + steamId.value() + "/myworkshopfiles/?appid=" + PZ_APP_ID
            : "";
    }

    public static String workshopItemUrl(WorkshopItemID workshopItemId) {
        return workshopItemId != null
            ? "https://steamcommunity.com/sharedfiles/filedetails/?id=" + workshopItemId.value()
            : "";
    }

    private static final String STEAM_GET_PUBLISHED_FILE_DETAILS_URL = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/";
    private static final int BATCH_SIZE = 100;
    public  static final int DEFAULT_TIMEOUT = 5;

    private record CachedResponse(String body, long expireAt) {
        boolean isExpired() { return System.currentTimeMillis() > expireAt; }
    }
    static final int HTTP_CACHE_TTL_S = parseHttpCacheTTL();
    private static final ConcurrentHashMap<String, CachedResponse> HTTP_CACHE = new ConcurrentHashMap<>();

    private static int parseHttpCacheTTL() {
        return Math.max(0, Agent.getArgInt("http_cache_ttl", 3600));
    }

    static String getCachedBody(String url, String requestBody) {
        if (HTTP_CACHE_TTL_S <= 0) return null;
        CachedResponse r = HTTP_CACHE.get(url + "\0" + requestBody);
        return (r != null && !r.isExpired()) ? r.body() : null;
    }

    static void putCachedBody(String url, String requestBody, String body) {
        if (HTTP_CACHE_TTL_S <= 0) return;
        HTTP_CACHE.put(url + "\0" + requestBody, new CachedResponse(body, System.currentTimeMillis() + HTTP_CACHE_TTL_S * 1000L));
    }

    static final Duration HTTP_TIMEOUT = parseHttpTimeout();
    private static HttpClient httpClient;

    /** Built lazily: HttpClient creation opens a Selector, which can fail on some platforms (e.g. Wine/Proton) and must not break class init. */
    static synchronized HttpClient http() throws IOException {
        if (httpClient == null) {
            try {
                httpClient = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            } catch (RuntimeException | Error e) {
                throw new IOException("Could not create HTTP client: " + e, e);
            }
        }
        return httpClient;
    }

    private static Duration parseHttpTimeout() {
        return Duration.ofSeconds(Agent.getArgInt("http_client_timeout", DEFAULT_TIMEOUT));
    }

    private SteamWorkshop() {}

    /**
     * Ban status info for a Workshop item.
     * {@code status}: true = banned, false = not banned, null = unknown.
     */
    public record BanInfo(Boolean status, String reason) {
        public BanInfo {
            reason = reason != null ? reason : "";
        }
    }

    /** Details fetched from Steam API for a Workshop item. */
    public record ItemDetails(BanInfo ban, SteamID64 creatorSteamId64) {}

    /**
     * Fetch Workshop item details (ban status, creator) for the given IDs.
     * @return map of workshop ID to details; unknown items get {@code null} ban status
     */
    public static Map<WorkshopItemID, ItemDetails> fetchItemDetails(
        Set<WorkshopItemID> workshopIds
    ) {
        Map<WorkshopItemID, ItemDetails> out = new HashMap<>();
        if (Utils.isBlank(workshopIds)) return out;

        Logger.info("checking mods ban status");
        try {
            List<WorkshopItemID> ids = new ArrayList<>(workshopIds);
            for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
                int to = Math.min(ids.size(), from + BATCH_SIZE);
                fetchChunk(ids.subList(from, to), out);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            setUnknownDetails(out, workshopIds, "Steam API request interrupted");
        } catch (Exception e) {
            setUnknownDetails(out, workshopIds, "Steam API request failed: " + e.getMessage());
        }
        return out;
    }

    private static void fetchChunk(
        List<WorkshopItemID> chunk,
        Map<WorkshopItemID, ItemDetails> out
    ) throws Exception {
        StringBuilder bodySb = new StringBuilder();
        bodySb.append("itemcount=").append(chunk.size());
        for (int i = 0; i < chunk.size(); i++) {
            bodySb.append("&publishedfileids[").append(i).append("]=")
                .append(URLEncoder.encode(Long.toString(chunk.get(i).value()), java.nio.charset.StandardCharsets.UTF_8));
        }
        String requestBody = bodySb.toString();
        String responseBody = getCachedBody(STEAM_GET_PUBLISHED_FILE_DETAILS_URL, requestBody);
        if (responseBody == null) {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(STEAM_GET_PUBLISHED_FILE_DETAILS_URL))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            HttpResponse<String> resp = http().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                setUnknownDetails(out, new HashSet<>(chunk),
                    "Steam API request failed (HTTP " + resp.statusCode() + ")");
                return;
            }
            responseBody = resp.body();
            putCachedBody(STEAM_GET_PUBLISHED_FILE_DETAILS_URL, requestBody, responseBody);
        }
        JsonElement root = JsonParser.parseString(responseBody);
        JsonElement response = root != null && root.isJsonObject() ? root.getAsJsonObject().get("response") : null;
        JsonElement details = (response != null && response.isJsonObject())
            ? response.getAsJsonObject().get("publishedfiledetails")
            : null;
        if (details == null || !details.isJsonArray()) {
            setUnknownDetails(out, new HashSet<>(chunk), "Steam API response missing publishedfiledetails");
            return;
        }
        Set<WorkshopItemID> seen = new HashSet<>();
        for (JsonElement it : details.getAsJsonArray()) {
            if (it == null || !it.isJsonObject()) continue;
            JsonObject itObj = it.getAsJsonObject();
            WorkshopItemID id = parsePublishedFileId(itObj.get("publishedfileid"));
            if (id == null) continue;
            seen.add(id);
            int banned = 0;
            JsonElement bannedJson = itObj.get("banned");
            if (bannedJson != null && bannedJson.isJsonPrimitive() && bannedJson.getAsJsonPrimitive().isNumber()) {
                banned = bannedJson.getAsInt();
            }
            String reason = "";
            JsonElement banReasonJson = itObj.get("ban_reason");
            if (banReasonJson != null && banReasonJson.isJsonPrimitive() && banReasonJson.getAsJsonPrimitive().isString()) {
                reason = banReasonJson.getAsString();
            }
            SteamID64 creator = parseCreatorSteamId64(it);
            out.put(id, new ItemDetails(
                new BanInfo(banned != 0, reason),
                creator
            ));
        }
        for (WorkshopItemID id : chunk) {
            if (!seen.contains(id) && !out.containsKey(id)) {
                out.put(id, new ItemDetails(
                    new BanInfo(null, "Steam API response missing mod id"),
                    null
                ));
            }
        }
    }

    private static void setUnknownDetails(
        Map<WorkshopItemID, ItemDetails> out,
        Set<WorkshopItemID> workshopIds,
        String reason
    ) {
        for (WorkshopItemID id : workshopIds) {
            if (id == null) continue;
            out.put(id, new ItemDetails(new BanInfo(null, reason), null));
        }
    }

    private static WorkshopItemID parsePublishedFileId(JsonElement idJson) {
        if (idJson == null || idJson.isJsonNull()) return null;
        if (!idJson.isJsonPrimitive()) return null;
        JsonPrimitive p = idJson.getAsJsonPrimitive();
        if (p.isString()) {
            try {
                return new WorkshopItemID(Long.parseLong(p.getAsString().trim()));
            } catch (Exception e) {
                return null;
            }
        }
        if (p.isNumber()) {
            try {
                return new WorkshopItemID(p.getAsLong());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static SteamID64 parseCreatorSteamId64(JsonElement item) {
        if (item == null || !item.isJsonObject()) return null;
        JsonElement cj = item.getAsJsonObject().get("creator");
        if (cj == null || cj.isJsonNull()) return null;
        if (!cj.isJsonPrimitive()) return null;
        JsonPrimitive p = cj.getAsJsonPrimitive();
        if (p.isString()) {
            String s = p.getAsString().trim();
            return s.isEmpty() ? null : new SteamID64(Long.parseLong(s));
        }
        if (p.isNumber()) {
            return new SteamID64(p.getAsLong());
        }
        return null;
    }

    /**
     * Get the creator SteamID64 for ZBS verification.
     * @return null if no workshop item or creator unavailable
     */
    public static SteamID64 getUploaderID(
        WorkshopItemID workshopItemId,
        Map<WorkshopItemID, ItemDetails> byId
    ) {
        if (workshopItemId == null) return null;
        ItemDetails d = byId.get(workshopItemId);
        return d != null ? d.creatorSteamId64() : null;
    }

    /** Convert WorkshopItemID to string, or null. */
    public static String idToString(WorkshopItemID wid) {
        return wid != null ? Long.toString(wid.value()) : null;
    }
}
