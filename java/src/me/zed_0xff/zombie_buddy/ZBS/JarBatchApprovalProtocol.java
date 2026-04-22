package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON file protocol between {@link Loader} (game process) and
 * {@link BatchJarApprovalMain} (non-headless child JVM with Swing UI).
 */
public final class JarBatchApprovalProtocol {

    static final String HDR_REQ  = "ZB_BATCH_V6";
    static final String HDR_RESP = "ZB_BATCH_V3_OUT";
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String TOK_ALLOW_PERSIST = "ALLOW_PERSIST";
    static final String TOK_ALLOW_SESSION = "ALLOW_SESSION";
    static final String TOK_DENY_PERSIST  = "DENY_PERSIST";
    static final String TOK_DENY_SESSION  = "DENY_SESSION";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Entry {
        public final String modKey;
        public final String modId;
        /** Nullable workshop item id for this row. */
        public final JavaModInfo.WorkshopItemID workshopItemId;
        public final String jarAbsolutePath;
        public final String sha256;
        public final String modifiedHuman;
        /** {@code yes} / {@code no} to pre-select that radio; empty = default (No). */
        public final String priorHint;
        /** Display name from mod.info {@code name=}; may be empty (UI falls back to {@link #modId}). */
        public final String modDisplayName;
        /** {@code yes} / {@code no} / {@code unsigned} (missing .zbs while allowed) / empty (legacy). */
        public final String zbsValid;
        /** Author's Steam id from {@code .zbs} when present. */
        public final SteamID64 zbsSteamId;
        /** Non-empty when {@link #zbsValid} is {@code no}. */
        public final String zbsNotice;
        /** {@code yes} / {@code no} / {@code unknown}. */
        public final String steamBanStatus;
        /** Optional explanation (e.g. API error or Steam ban reason). */
        public final String steamBanReason;

        @JsonCreator
        public Entry(
            @JsonProperty("modKey") String modKey,
            @JsonProperty("modId") String modId,
            @JsonProperty("workshopItemId") JavaModInfo.WorkshopItemID workshopItemId,
            @JsonProperty("jarAbsolutePath") String jarAbsolutePath,
            @JsonProperty("sha256") String sha256,
            @JsonProperty("modifiedHuman") String modifiedHuman,
            @JsonProperty("priorHint") String priorHint,
            @JsonProperty("modDisplayName") String modDisplayName,
            @JsonProperty("zbsValid") String zbsValid,
            @JsonProperty("zbsSteamId") SteamID64 zbsSteamId,
            @JsonProperty("zbsNotice") String zbsNotice,
            @JsonProperty("steamBanStatus") String steamBanStatus,
            @JsonProperty("steamBanReason") String steamBanReason
        ) {
            this.modKey = modKey;
            this.modId = modId;
            this.workshopItemId = workshopItemId;
            this.jarAbsolutePath = jarAbsolutePath != null ? jarAbsolutePath : "";
            this.sha256 = sha256 != null ? sha256 : "";
            this.modifiedHuman = modifiedHuman != null ? modifiedHuman : "";
            this.priorHint = priorHint != null ? priorHint : "";
            this.modDisplayName = modDisplayName != null ? modDisplayName : "";
            this.zbsValid = zbsValid != null ? zbsValid : "";
            this.zbsSteamId = zbsSteamId;
            this.zbsNotice = zbsNotice != null ? zbsNotice : "";
            this.steamBanStatus = steamBanStatus != null ? steamBanStatus : "";
            this.steamBanReason = steamBanReason != null ? steamBanReason : "";
        }
    }

    /** One row in the batch response file: decision key, optional workshop id, JAR hash, token, optional trusted author SteamID64. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class OutLine {
        public final String modId;
        public final JavaModInfo.WorkshopItemID workshopItemId;
        public final String sha256;
        public final String token;
        public final String trustedAuthorSteamId;

        public OutLine(String modId, JavaModInfo.WorkshopItemID workshopItemId, String sha256, String token) {
            this(modId, workshopItemId, sha256, token, "");
        }

        @JsonCreator
        public OutLine(
            @JsonProperty("modId") String modId,
            @JsonProperty("workshopItemId") JavaModInfo.WorkshopItemID workshopItemId,
            @JsonProperty("sha256") String sha256,
            @JsonProperty("token") String token,
            @JsonProperty("trustedAuthorSteamId") String trustedAuthorSteamId
        ) {
            this.modId = modId != null ? modId : "";
            this.workshopItemId = workshopItemId;
            this.sha256 = sha256 != null ? sha256 : "";
            this.token = token != null ? token : "";
            this.trustedAuthorSteamId = trustedAuthorSteamId != null ? trustedAuthorSteamId : "";
        }
    }

    public static void writeRequest(Path path, List<Entry> entries) throws IOException {
        List<Entry> safe = entries == null ? Collections.emptyList() : entries;
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), new RequestEnvelope(HDR_REQ, safe));
    }

    public static List<Entry> readRequest(Path path) throws IOException {
        RequestEnvelope env = JSON.readValue(path.toFile(), RequestEnvelope.class);
        if (env == null || !HDR_REQ.equals(env.header)) {
            throw new IOException("Bad request header: " + (env != null ? env.header : null));
        }
        return env.entries != null ? env.entries : Collections.emptyList();
    }

    public static void writeResponse(Path path, List<OutLine> lines) throws IOException {
        List<OutLine> safe = lines == null ? Collections.emptyList() : lines;
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), new ResponseEnvelope(HDR_RESP, safe));
    }

    public static List<OutLine> readResponse(Path path) throws IOException {
        ResponseEnvelope env = JSON.readValue(path.toFile(), ResponseEnvelope.class);
        if (env == null || !HDR_RESP.equals(env.header)) {
            return null;
        }
        List<OutLine> out = env.lines != null ? env.lines : Collections.emptyList();
        for (OutLine line : out) {
            if (!isValidToken(line.token)) {
                return null;
            }
        }
        return out;
    }

    static boolean isValidToken(String tok) {
        return TOK_ALLOW_PERSIST.equals(tok)
            || TOK_ALLOW_SESSION.equals(tok)
            || TOK_DENY_PERSIST.equals(tok)
            || TOK_DENY_SESSION.equals(tok);
    }

    static String displayToken(String tok) {
        switch (tok) {
            case TOK_DENY_SESSION: return "Deny for this session only";
            case TOK_DENY_PERSIST:  return "Deny permanently (save)";
            case TOK_ALLOW_SESSION: return "Allow for this session only";
            case TOK_ALLOW_PERSIST: return "Allow permanently (save)";
            default:
                return tok;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RequestEnvelope {
        public final String header;
        public final List<Entry> entries;

        @JsonCreator
        RequestEnvelope(@JsonProperty("header") String header, @JsonProperty("entries") List<Entry> entries) {
            this.header = header;
            this.entries = entries != null ? entries : new ArrayList<>();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ResponseEnvelope {
        public final String header;
        public final List<OutLine> lines;

        @JsonCreator
        ResponseEnvelope(@JsonProperty("header") String header, @JsonProperty("lines") List<OutLine> lines) {
            this.header = header;
            this.lines = lines != null ? lines : new ArrayList<>();
        }
    }
}
