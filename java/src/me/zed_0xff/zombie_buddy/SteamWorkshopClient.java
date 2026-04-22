package me.zed_0xff.zombie_buddy;

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
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Steam Workshop API client for fetching mod details and ban status.
 */
public final class SteamWorkshopClient {

    static final String BAN_STATUS_YES = "yes";
    static final String BAN_STATUS_NO = "no";
    static final String BAN_STATUS_UNKNOWN = "unknown";

    private static final String STEAM_GET_PUBLISHED_FILE_DETAILS_URL =
        "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private static final int BATCH_SIZE = 100;

    private SteamWorkshopClient() {}

    /** Ban status info for a Workshop item. */
    public static final class BanInfo {
        public final String status;
        public final String reason;

        public BanInfo(String status, String reason) {
            this.status = status != null ? status : BAN_STATUS_UNKNOWN;
            this.reason = reason != null ? reason : "";
        }
    }

    /** Details fetched from Steam API for a Workshop item. */
    public static final class ItemDetails {
        public final BanInfo ban;
        public final SteamID64 creatorSteamId64;

        public ItemDetails(BanInfo ban, SteamID64 creatorSteamId64) {
            this.ban = ban;
            this.creatorSteamId64 = creatorSteamId64;
        }
    }

    /**
     * Fetch Workshop item details (ban status, creator) for the given IDs.
     * @return map of workshop ID to details; unknown items get BAN_STATUS_UNKNOWN
     */
    public static Map<JavaModInfo.WorkshopItemID, ItemDetails> fetchItemDetails(
        Set<JavaModInfo.WorkshopItemID> workshopIds
    ) {
        Map<JavaModInfo.WorkshopItemID, ItemDetails> out = new HashMap<>();
        if (workshopIds == null || workshopIds.isEmpty()) {
            return out;
        }
        Logger.info("checking mods ban status");
        try {
            List<JavaModInfo.WorkshopItemID> ids = new ArrayList<>(workshopIds);
            for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
                int to = Math.min(ids.size(), from + BATCH_SIZE);
                List<JavaModInfo.WorkshopItemID> chunk = ids.subList(from, to);
                fetchChunk(chunk, out);
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
        List<JavaModInfo.WorkshopItemID> chunk,
        Map<JavaModInfo.WorkshopItemID, ItemDetails> out
    ) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("itemcount=").append(chunk.size());
        for (int i = 0; i < chunk.size(); i++) {
            body.append("&publishedfileids[").append(i).append("]=")
                .append(URLEncoder.encode(Long.toString(chunk.get(i).value()), java.nio.charset.StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(STEAM_GET_PUBLISHED_FILE_DETAILS_URL))
            .timeout(Duration.ofSeconds(25))
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            setUnknownDetails(out, new HashSet<>(chunk),
                "Steam API request failed (HTTP " + resp.statusCode() + ")");
            return;
        }
        JsonElement root = JsonParser.parseString(resp.body());
        JsonElement response = root != null && root.isJsonObject() ? root.getAsJsonObject().get("response") : null;
        JsonElement details = (response != null && response.isJsonObject())
            ? response.getAsJsonObject().get("publishedfiledetails")
            : null;
        if (details == null || !details.isJsonArray()) {
            setUnknownDetails(out, new HashSet<>(chunk), "Steam API response missing publishedfiledetails");
            return;
        }
        Set<JavaModInfo.WorkshopItemID> seen = new HashSet<>();
        for (JsonElement it : details.getAsJsonArray()) {
            if (it == null || !it.isJsonObject()) continue;
            JsonObject itObj = it.getAsJsonObject();
            JavaModInfo.WorkshopItemID id = parsePublishedFileId(itObj.get("publishedfileid"));
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
                new BanInfo(banned != 0 ? BAN_STATUS_YES : BAN_STATUS_NO, reason),
                creator
            ));
        }
        for (JavaModInfo.WorkshopItemID id : chunk) {
            if (!seen.contains(id) && !out.containsKey(id)) {
                out.put(id, new ItemDetails(
                    new BanInfo(BAN_STATUS_UNKNOWN, "Steam API response missing mod id"),
                    null
                ));
            }
        }
    }

    private static void setUnknownDetails(
        Map<JavaModInfo.WorkshopItemID, ItemDetails> out,
        Set<JavaModInfo.WorkshopItemID> workshopIds,
        String reason
    ) {
        for (JavaModInfo.WorkshopItemID id : workshopIds) {
            if (id == null) continue;
            out.put(id, new ItemDetails(new BanInfo(BAN_STATUS_UNKNOWN, reason), null));
        }
    }

    private static JavaModInfo.WorkshopItemID parsePublishedFileId(JsonElement idJson) {
        if (idJson == null || idJson.isJsonNull()) return null;
        if (!idJson.isJsonPrimitive()) return null;
        JsonPrimitive p = idJson.getAsJsonPrimitive();
        if (p.isString()) {
            try {
                return new JavaModInfo.WorkshopItemID(Long.parseLong(p.getAsString().trim()));
            } catch (Exception e) {
                return null;
            }
        }
        if (p.isNumber()) {
            try {
                return new JavaModInfo.WorkshopItemID(p.getAsLong());
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
            return s.isEmpty() ? null : new SteamID64(s);
        }
        if (p.isNumber()) {
            try {
                return new SteamID64(Long.toString(p.getAsLong()));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Get the creator SteamID64 for ZBS verification.
     * @return null if no workshop item or creator unavailable
     */
    public static SteamID64 getUploaderForVerification(
        JavaModInfo.WorkshopItemID workshopItemId,
        Map<JavaModInfo.WorkshopItemID, ItemDetails> byId
    ) {
        if (workshopItemId == null) return null;
        ItemDetails d = byId.get(workshopItemId);
        return d != null ? d.creatorSteamId64 : null;
    }

    /** Convert WorkshopItemID to string, or null. */
    public static String idToString(JavaModInfo.WorkshopItemID wid) {
        return wid != null ? Long.toString(wid.value()) : null;
    }
}
