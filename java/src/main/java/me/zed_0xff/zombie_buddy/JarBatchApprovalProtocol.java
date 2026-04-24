package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * JSON file protocol between {@link Loader} (game process) and
 * {@link BatchJarApprovalMain} (non-headless child JVM with Swing UI).
 */
public final class JarBatchApprovalProtocol {

    static final String HDR_REQ  = "ZB_BATCH_V6";
    static final String HDR_RESP = "ZB_BATCH_V6_OUT";
    private static final Gson JSON = ZBGson.PRETTY;

    static final String TOK_ALLOW_PERSIST = "ALLOW_PERSIST";
    static final String TOK_ALLOW_SESSION = "ALLOW_SESSION";
    static final String TOK_DENY_PERSIST  = "DENY_PERSIST";
    static final String TOK_DENY_SESSION  = "DENY_SESSION";

    public static final class Entry {
        @SerializedName("modKey")
        public final String modKey;
        @SerializedName("modId")
        public final String modId;
        /** Nullable workshop item id for this row. */
        @SerializedName("workshopItemId")
        public final WorkshopItemID workshopItemId;
        @SerializedName("jarAbsolutePath")
        public final String jarAbsolutePath;
        @SerializedName("sha256")
        public final String sha256;
        @SerializedName("modifiedHuman")
        public final String modifiedHuman;
        /** {@code yes} / {@code no} to pre-select that radio; empty = default (No). */
        @SerializedName("priorHint")
        public final String priorHint;
        /** Display name from mod.info {@code name=}; may be empty (UI falls back to {@link #modId}). */
        @SerializedName("modDisplayName")
        public final String modDisplayName;
        /** {@code yes} / {@code no} / {@code unsigned} (missing .zbs while allowed) / empty (legacy). */
        @SerializedName("zbsValid")
        public final String zbsValid;
        /** Author's Steam id from {@code .zbs} when present. */
        @SerializedName("zbsSteamId")
        public final SteamID64 zbsSteamId;
        /** Non-empty when {@link #zbsValid} is {@code no}. */
        @SerializedName("zbsNotice")
        public final String zbsNotice;
        /** {@code yes} / {@code no} / {@code unknown}. */
        @SerializedName("steamBanStatus")
        public final String steamBanStatus;
        /** Optional explanation (e.g. API error or Steam ban reason). */
        @SerializedName("steamBanReason")
        public final String steamBanReason;

        public Entry(
            String modKey,
            String modId,
            WorkshopItemID workshopItemId,
            String jarAbsolutePath,
            String sha256,
            String modifiedHuman,
            String priorHint,
            String modDisplayName,
            String zbsValid,
            SteamID64 zbsSteamId,
            String zbsNotice,
            String steamBanStatus,
            String steamBanReason
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
    public static final class OutLine {
        @SerializedName("modId")
        public final String modId;
        @SerializedName("workshopItemId")
        public final WorkshopItemID workshopItemId;
        @SerializedName("sha256")
        public final String sha256;
        @SerializedName("token")
        public final String token;
        @SerializedName("trustedAuthorSteamId")
        public final SteamID64 trustedAuthorSteamId;

        public OutLine(
            String modId,
            WorkshopItemID workshopItemId,
            String sha256,
            String token,
            SteamID64 trustedAuthorSteamId
        ) {
            this.modId = modId != null ? modId : "";
            this.workshopItemId = workshopItemId;
            this.sha256 = sha256 != null ? sha256 : "";
            this.token = token != null ? token : "";
            this.trustedAuthorSteamId = trustedAuthorSteamId;
        }
    }

    public static void writeRequest(Path path, List<Entry> entries) throws IOException {
        List<Entry> safe = entries == null ? Collections.emptyList() : entries;
        try (Writer w = Files.newBufferedWriter(path)) {
            JSON.toJson(new RequestEnvelope(HDR_REQ, safe), w);
        }
    }

    public static List<Entry> readRequest(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            RequestEnvelope env = JSON.fromJson(r, RequestEnvelope.class);
            if (env == null || !HDR_REQ.equals(env.header)) {
                throw new IOException("Bad request header: " + (env != null ? env.header : null));
            }
            return env.entries != null ? env.entries : Collections.emptyList();
        }
    }

    public static void writeResponse(Path path, List<OutLine> lines) throws IOException {
        List<OutLine> safe = lines == null ? Collections.emptyList() : lines;
        try (Writer w = Files.newBufferedWriter(path)) {
            JSON.toJson(new ResponseEnvelope(HDR_RESP, safe), w);
        }
    }

    public static List<OutLine> readResponse(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            ResponseEnvelope env = JSON.fromJson(r, ResponseEnvelope.class);
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

    private static final class RequestEnvelope {
        @SerializedName("header")
        public final String header;
        @SerializedName("entries")
        public final List<Entry> entries;

        RequestEnvelope(String header, List<Entry> entries) {
            this.header = header;
            this.entries = entries != null ? entries : new ArrayList<>();
        }
    }

    private static final class ResponseEnvelope {
        @SerializedName("header")
        public final String header;
        @SerializedName("lines")
        public final List<OutLine> lines;

        ResponseEnvelope(String header, List<OutLine> lines) {
            this.header = header;
            this.lines = lines != null ? lines : new ArrayList<>();
        }
    }
}
