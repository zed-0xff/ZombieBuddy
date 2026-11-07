package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import me.zed_0xff.zombie_buddy.ModFlags;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * JSON file protocol between {@link Loader} (game process) and
 * {@link me.zed_0xff.zombie_buddy.frontend.SwingApprovalMain} (non-headless child JVM with Swing UI).
 */
public final class JarBatchApprovalProtocol {

    static final String HDR_REQ  = "ZB_BATCH_V8";
    static final String HDR_RESP = "ZB_BATCH_V8_OUT";
    // IPC must retain explicit null flags for whole-batch validation. The shared
    // persistent-store adapter keeps its existing null-to-EMPTY normalization.
    private static final Gson JSON = ZBGson.PRETTY.newBuilder()
            .registerTypeAdapter(ModFlags.class, ZBGson.PRETTY.getAdapter(ModFlags.class).nullSafe())
            .create();

    /** Normal construction supplies non-null flags/signature; readers also validate after Gson deserialization. */
    public static final class Entry {
        public final String modId;
        public final WorkshopItemID workshopItemId;
        public final Path jarAbsolutePath;
        public final Path infAbsolutePath;
        public final String sha256;
        public final Date date;
        /** In responses, null with MF_PERSIST explicitly clears a saved hash decision; the loader denies this launch. */
        public Boolean decision;
        public ModFlags flags = ModFlags.EMPTY;
        public final String modDisplayName;
        public final ZBSignature zbs;
        public final SteamBan steamBan;

        public Entry(
            String modId,
            WorkshopItemID workshopItemId,
            Path jarAbsolutePath,
            Path infAbsolutePath,
            String sha256,
            Date date,
            Boolean decision,
            ModFlags flags,
            String modDisplayName,
            ZBSignature zbs,
            SteamBan steamBan
        ) {
            this.modId = modId;
            this.workshopItemId = workshopItemId;
            this.jarAbsolutePath = jarAbsolutePath;
            this.infAbsolutePath = infAbsolutePath;
            this.sha256 = sha256 != null ? sha256 : "";
            this.date = date;
            this.decision = decision;
            this.flags = flags != null ? flags : ModFlags.EMPTY;
            this.modDisplayName = modDisplayName != null ? modDisplayName : "";
            this.zbs = zbs != null ? zbs : ZBSignature.none();
            this.steamBan = steamBan;
        }

        public record SteamBan(String reason) {
            public SteamBan {
                reason = reason != null ? reason : "";
            }
        }

        public record ZBSignature(
            boolean valid,
            SteamID64 authorSteamId,
            String notice
        ) {
            public ZBSignature {
                notice = notice != null ? notice : "";
            }

            public static ZBSignature none() {
                return new ZBSignature(false, null, "");
            }

            public boolean invalid() {
                return !valid && !Utils.isBlank(notice);
            }

            public boolean unsigned() {
                return !valid && authorSteamId == null && Utils.isBlank(notice);
            }
        }
    }

    public static void writeRequest(Path path, List<Entry> entries) throws IOException {
        List<Entry> safe = entries == null ? Collections.emptyList() : entries;
        Utils.writeFileAtomic(path, JSON.toJson(new RequestEnvelope(HDR_REQ, safe)), StandardCharsets.UTF_8);
    }

    public static List<Entry> readRequest(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            RequestEnvelope env = JSON.fromJson(r, RequestEnvelope.class);
            if (env == null || !HDR_REQ.equals(env.header)) {
                throw new IOException("Bad request header: " + (env != null ? env.header : null));
            }
            return checkedEntries(env.entries);
        }
    }

    public static void writeResponse(Path path, List<Entry> entries) throws IOException {
        List<Entry> safe = entries == null ? Collections.emptyList() : entries;
        Utils.writeFileAtomic(path, JSON.toJson(new ResponseEnvelope(HDR_RESP, safe)), StandardCharsets.UTF_8);
    }

    public static List<Entry> readResponse(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            ResponseEnvelope env = JSON.fromJson(r, ResponseEnvelope.class);
            if (env == null || !HDR_RESP.equals(env.header)) {
                return null;
            }
            return checkedEntries(env.entries);
        }
    }

    private static List<Entry> checkedEntries(List<Entry> entries) throws IOException {
        if (entries == null) return Collections.emptyList();
        // Gson can bypass Entry's constructor and field initializers. Reject incomplete
        // metadata before any frontend or loader processes the batch; never treat it as unsigned.
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry == null) throw new IOException("Null approval entry at index " + i);
            if (entry.zbs == null) throw new IOException("Missing approval signature metadata at index " + i);
            if (entry.flags == null) throw new IOException("Missing approval flags at index " + i);
        }
        // A null decision is valid (undecided/request or explicit reset/response).
        return entries;
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
        @SerializedName("entries")
        public final List<Entry> entries;

        ResponseEnvelope(String header, List<Entry> entries) {
            this.header = header;
            this.entries = entries != null ? entries : new ArrayList<>();
        }
    }
}
