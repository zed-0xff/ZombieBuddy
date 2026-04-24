package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.annotations.SerializedName;

/**
 * Persistent Java-mod JAR allow/deny decisions under {@code ~/.zombie_buddy/}.
 * JSON shape:
 * {
 *   "formatVersion": 1,
 *   "mods": [
 *     {
 *       "id": "ZBetterModList",
 *       "workshop_id": 3709229404,
 *       "jar_hash": "c180d888eac78369a58dd266e98095ca7e86e16533294ee43ec750e729827064",
 *       "decision": true,
 *       "time": "2026-04-01T12:34:56Z",
 *       "author_id": 76561198043849998
 *     }
 *   ],
 *   "authors": [
 *     { "id": 76561198043849998, "trust": true, "keys": [ "…64 hex Ed25519 pubkey(s)…" ], "name": "..." }
 *   ]
 * }
 */
public final class ModApprovalsStore {

    public static final String JSON_FILE_NAME = "mod_approvals.json";
    public static final String LEGACY_TXT_FILE_NAME = "java_mod_approvals.txt";
    private static final int FORMAT_VERSION = 1;

    private ModApprovalsStore() {}

    /** Root JSON structure. */
    public static final class FileData {
        @SerializedName("formatVersion")
        public int formatVersion = FORMAT_VERSION;
        
        @SerializedName("mods")
        public List<ModEntry> mods = new ArrayList<>();
        
        @SerializedName("authors")
        public List<AuthorEntry> authors = new ArrayList<>();
    }

    /** A single mod approval entry. */
    public static final class ModEntry {
        @SerializedName("id")
        public String id = "";
        
        @SerializedName("workshop_id")
        public WorkshopItemID workshopId;
        
        @SerializedName("jar_hash")
        public String jarHash;
        
        @SerializedName("decision")
        public boolean decision;
        
        @SerializedName("time")
        public String time;
        
        @SerializedName("author_id")
        public SteamID64 authorId;

        public ModEntry() {}

        public ModEntry(String id, WorkshopItemID workshopId, String jarHash, boolean decision, String time, SteamID64 authorId) {
            this.id = id != null ? id : "";
            this.workshopId = workshopId;
            this.jarHash = jarHash;
            this.decision = decision;
            this.time = time;
            this.authorId = authorId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ModEntry other)) return false;
            return decision == other.decision
                && Objects.equals(id, other.id)
                && Objects.equals(workshopId, other.workshopId)
                && Objects.equals(jarHash, other.jarHash)
                && Objects.equals(authorId, other.authorId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, workshopId, jarHash, decision, authorId);
        }
    }

    /** Per SteamID64: trust flag and optional Ed25519 pubkey hex strings. */
    public static final class AuthorEntry {
        @SerializedName("id")
        public SteamID64 id;
        
        @SerializedName("trust")
        public boolean trust;
        
        @SerializedName("keys")
        public Set<String> keys = new LinkedHashSet<>();
        
        @SerializedName("name")
        public String name;

        public AuthorEntry() {}

        public AuthorEntry(SteamID64 id, boolean trust, Set<String> keys, String name) {
            this.id = id;
            this.trust = trust;
            this.name = name != null && !name.trim().isEmpty() ? name.trim() : null;
            if (keys != null) {
                for (String k : keys) {
                    if (k != null && !k.isEmpty()) {
                        this.keys.add(k.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AuthorEntry other)) return false;
            return trust == other.trust 
                && Objects.equals(id, other.id)
                && keys.equals(other.keys) 
                && Objects.equals(name, other.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, trust, keys, name);
        }
    }

    static Path directory() {
        return Path.of(System.getProperty("user.home"), ".zombie_buddy");
    }

    static Path jsonPath() {
        return directory().resolve(JSON_FILE_NAME);
    }

    static Path legacyTxtPath() {
        return directory().resolve(LEGACY_TXT_FILE_NAME);
    }

    static FileData load() {
        Path jp = jsonPath();
        Path leg = legacyTxtPath();
        FileData data = new FileData();
        try {
            if (Files.exists(jp)) {
                String json = Files.readString(jp, StandardCharsets.UTF_8);
                if (json != null && !json.trim().isEmpty()) {
                    FileData loaded = ZBGson.PRETTY.fromJson(json, FileData.class);
                    if (loaded != null) {
                        data = loaded;
                        if (data.mods == null) data.mods = new ArrayList<>();
                        if (data.authors == null) data.authors = new ArrayList<>();
                        Logger.info("Java mod approvals read from " + jp + ": " + data.mods.size()
                            + " mod(s), " + data.authors.size() + " author(s)");
                    }
                }
            }
            if (Files.exists(leg)) {
                Files.delete(leg);
                Logger.info("Deleted legacy Java mod approvals file " + leg.getFileName());
            }
        } catch (Exception e) {
            Logger.error("Could not load Java mod approvals: " + e);
        }
        return data;
    }

    static void save(FileData data) {
        try {
            Path jp = jsonPath();
            Files.createDirectories(jp.getParent());
            
            // Resolve author names
            Map<SteamID64, String> knownNames = KnownAuthors.loadSteamIdToDisplayName();
            if (knownNames != null) {
                for (AuthorEntry ae : data.authors) {
                    if (ae.id != null && (ae.name == null || ae.name.isEmpty())) {
                        String resolved = knownNames.get(ae.id);
                        if (resolved != null && !resolved.isEmpty()) {
                            ae.name = resolved;
                        }
                    }
                }
            }
            
            Files.writeString(jp, ZBGson.PRETTY.toJson(data), StandardCharsets.UTF_8);
            Logger.info("Java mod approvals written to " + jp + ": " + data.mods.size()
                + " mod(s), " + data.authors.size() + " author(s)");
        } catch (Exception e) {
            Logger.error("Could not save Java mod approvals: " + e);
        }
    }
}
