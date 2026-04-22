package me.zed_0xff.zombie_buddy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Persistent Java-mod JAR allow/deny decisions under {@code ~/.zombie_buddy/}.
 * <p>
 * JSON shape (extensible root):
 * <pre>
 * {
 *   "formatVersion": 1,
 *   "jarDecisionsByWorkshopItem": {
 *     "WorkshopItemID": {
 *       "mod_ids": [ "..." ],
 *       "decisions": { "sha256hex": true | false },
 *       "author": { "id": "...", "name": "..." }
 *     }
 *   },
 *   "authors": {
 *     "7656119…": { "trust": true, "keys": [ "…64 hex Ed25519 pubkey(s)…" ], "name": "..." }
 *   }
 * }
 * </pre>
 */
public final class JavaModApprovalsStore {

    /** Current JSON file. */
    public static final String JSON_FILE_NAME = "java_mod_approvals.json";

    /**
     * Obsolete line-oriented file from older ZombieBuddy builds. If still present on disk,
     * it is deleted at startup without importing its contents.
     */
    public static final String LEGACY_TXT_FILE_NAME = "java_mod_approvals.txt";

    private static final int FORMAT_VERSION = 1;
    private static final String KEY_FORMAT_VERSION = "formatVersion";
    private static final String KEY_JAR_DECISIONS = "jarDecisionsByWorkshopItem";
    private static final String KEY_AUTHORS = "authors";
    private static final String KEY_DECISIONS = "decisions";
    private static final String KEY_MOD_IDS = "mod_ids";
    private static final String KEY_AUTHOR = "author";
    private static final String KEY_ID = "id";

    private static final String KEY_TRUST = "trust";
    private static final String KEY_KEYS = "keys";
    private static final String KEY_NAME = "name";

    private JavaModApprovalsStore() {}

    /** Per SteamID64: trust flag and optional JavaModZBS pubkey hex strings. */
    public static final class AuthorEntry {
        public boolean trust;
        /** Ed25519 public keys from profile ({@code JavaModZBS}), lowercased hex. */
        public final Set<String> keys;
        /** Optional friendly display name from authors.yml (non-authoritative metadata). */
        public String name;

        public AuthorEntry(boolean trust, Set<String> keys) {
            this(trust, keys, null);
        }

        public AuthorEntry(boolean trust, Set<String> keys, String name) {
            this.trust = trust;
            this.keys = new LinkedHashSet<>();
            this.name = name != null && !name.trim().isEmpty() ? name.trim() : null;
            if (keys != null) {
                for (String k : keys) {
                    if (k != null && !k.isEmpty()) {
                        this.keys.add(k.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        public AuthorEntry copy() {
            return new AuthorEntry(trust, new LinkedHashSet<>(keys), name);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AuthorEntry other)) return false;
            return trust == other.trust && keys.equals(other.keys) && Objects.equals(name, other.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(trust, keys, name);
        }
    }

    /** Optional convenience metadata for each Workshop item decision row. */
    public static final class DecisionAuthor {
        public final SteamID64 id;
        public final String name;

        public DecisionAuthor(SteamID64 id, String name) {
            this.id = id;
            this.name = name != null && !name.trim().isEmpty() ? name.trim() : null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DecisionAuthor other)) return false;
            return Objects.equals(id, other.id) && Objects.equals(name, other.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name);
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

    static final class Snapshot {
        private final JarDecisionTable jarDecisions;
        private final Map<SteamID64, AuthorEntry> authors;
        private final Map<String, Set<String>> decisionModIds;
        private final Map<String, DecisionAuthor> decisionAuthors;

        Snapshot(
            JarDecisionTable jarDecisions,
            Map<SteamID64, AuthorEntry> authors,
            Map<String, Set<String>> decisionModIds,
            Map<String, DecisionAuthor> decisionAuthors
        ) {
            this.jarDecisions = jarDecisions;
            this.authors = authors;
            this.decisionModIds = decisionModIds;
            this.decisionAuthors = decisionAuthors;
        }

        JarDecisionTable jarDecisions() {
            return jarDecisions;
        }

        Map<SteamID64, AuthorEntry> authors() {
            return authors;
        }

        Map<String, Set<String>> decisionModIds() {
            return decisionModIds;
        }

        Map<String, DecisionAuthor> decisionAuthors() {
            return decisionAuthors;
        }
    }

    static Snapshot loadSnapshot() {
        Path jp = jsonPath();
        Path leg = legacyTxtPath();
        JarDecisionTable table = new JarDecisionTable();
        Map<SteamID64, AuthorEntry> authors = new HashMap<>();
        Map<String, Set<String>> decisionModIds = new HashMap<>();
        Map<String, DecisionAuthor> decisionAuthors = new HashMap<>();
        try {
            if (Files.exists(jp)) {
                readJsonInto(jp, table, authors, decisionModIds, decisionAuthors);
                Logger.info("Java mod approvals JSON read from " + jp + ": " + table.decisionCount()
                    + " decision(s), " + authors.size() + " author(s)");
            }
            if (Files.exists(leg)) {
                Files.delete(leg);
                Logger.info("Deleted legacy Java mod approvals file " + leg.getFileName()
                    + " without importing (use " + jp.getFileName() + " only)");
            }
            return new Snapshot(table, authors, decisionModIds, decisionAuthors);
        } catch (Exception e) {
            Logger.error("Could not load Java mod approvals: " + e);
        }
        return new Snapshot(table, authors, decisionModIds, decisionAuthors);
    }

    static JarDecisionTable load() {
        return loadSnapshot().jarDecisions();
    }

    static Map<SteamID64, AuthorEntry> loadAuthors() {
        return new HashMap<>(loadSnapshot().authors());
    }

    /**
     * Writes {@code jarDecisions}. If a file already exists, reads it first and merges,
     * replacing {@code jarDecisions} and {@code authors}; other top-level keys are kept when possible.
     */
    static void save(JarDecisionTable table) {
        Snapshot snap = loadSnapshot();
        save(table, snap.authors(), snap.decisionModIds(), snap.decisionAuthors());
    }

    static void save(JarDecisionTable table, Map<SteamID64, AuthorEntry> authors) {
        Snapshot snap = loadSnapshot();
        save(table, authors, snap.decisionModIds(), snap.decisionAuthors());
    }

    static void save(JarDecisionTable table, Map<SteamID64, AuthorEntry> authors, Map<String, Set<String>> decisionModIds) {
        Snapshot snap = loadSnapshot();
        save(table, authors, decisionModIds, snap.decisionAuthors());
    }

    static void save(
        JarDecisionTable table,
        Map<SteamID64, AuthorEntry> authors,
        Map<String, Set<String>> decisionModIds,
        Map<String, DecisionAuthor> decisionAuthors
    ) {
        try {
            Path jp = jsonPath();
            if (jp.getParent() != null) {
                Files.createDirectories(jp.getParent());
            }
            JsonObject root;
            if (Files.exists(jp)) {
                try {
                    root = parseRootObject(Files.readString(jp, StandardCharsets.UTF_8).trim());
                } catch (Exception e) {
                    Logger.warn("Approvals JSON unreadable; rewriting: " + e);
                    root = new JsonObject();
                }
            } else {
                root = new JsonObject();
            }
            if (!root.has(KEY_FORMAT_VERSION)) {
                root.addProperty(KEY_FORMAT_VERSION, FORMAT_VERSION);
            }
            Map<SteamID64, String> knownNames = SteamAuthorNames.loadSteamIdToDisplayName();
            root.add(KEY_JAR_DECISIONS, jarDecisionsToJson(table, decisionModIds, decisionAuthors, knownNames));
            root.add(KEY_AUTHORS, authorsToJson(authors, knownNames));
            Files.writeString(jp, ZbGson.PRETTY.toJson(root), StandardCharsets.UTF_8);
            int written = table == null ? 0 : table.decisionCount();
            int authorCount = authors == null ? 0 : authors.size();
            Logger.info("Java mod approvals JSON written to " + jp + ": " + written
                + " decision(s), " + authorCount + " author(s)");
        } catch (Exception e) {
            Logger.error("Could not save Java mod approvals: " + e);
        }
    }

    static JsonObject jarDecisionsToJson(
        JarDecisionTable table,
        Map<String, Set<String>> decisionModIds,
        Map<String, DecisionAuthor> decisionAuthors,
        Map<SteamID64, String> knownNames
    ) {
        JsonObject jarDecisions = new JsonObject();
        if (table == null) return jarDecisions;
        for (String workshopItemId : table.modIds()) {
            if (!isWorkshopItemIdKey(workshopItemId)) {
                continue;
            }
            JsonObject hashes = new JsonObject();
            for (Map.Entry<String, String> e : table.hashesOf(workshopItemId).entrySet()) {
                String v = e.getValue();
                if (Loader.DECISION_YES.equals(v) || Loader.DECISION_NO.equals(v)) {
                    hashes.addProperty(e.getKey(), Loader.DECISION_YES.equals(v));
                }
            }
            JsonObject row = new JsonObject();
            JsonArray modIds = new JsonArray();
            Set<String> mids = decisionModIds != null ? decisionModIds.get(workshopItemId) : null;
            if (mids != null) {
                List<String> sorted = new ArrayList<>(mids);
                Collections.sort(sorted);
                for (String mid : sorted) {
                    if (mid != null && !mid.isEmpty()) {
                        modIds.add(mid);
                    }
                }
            }
            row.add(KEY_MOD_IDS, modIds);
            row.add(KEY_DECISIONS, hashes);
            DecisionAuthor da = decisionAuthors != null ? decisionAuthors.get(workshopItemId) : null;
            if (da != null && da.id != null && da.id.value() != null && !da.id.value().isEmpty()) {
                JsonObject author = new JsonObject();
                author.addProperty(KEY_ID, da.id.value());
                String resolvedName = da.name;
                if ((resolvedName == null || resolvedName.isEmpty()) && knownNames != null) {
                    resolvedName = knownNames.get(da.id);
                }
                if (resolvedName != null && !resolvedName.isEmpty()) {
                    author.addProperty(KEY_NAME, resolvedName);
                }
                row.add(KEY_AUTHOR, author);
            }
            jarDecisions.add(workshopItemId, row);
        }
        return jarDecisions;
    }

    static JsonObject authorsToJson(Map<SteamID64, AuthorEntry> authors, Map<SteamID64, String> knownNames) {
        JsonObject o = new JsonObject();
        if (authors == null || authors.isEmpty()) {
            return o;
        }
        List<SteamID64> ids = new ArrayList<>(authors.keySet());
        ids.sort((a, b) -> a.value().compareTo(b.value()));
        for (SteamID64 sid : ids) {
            AuthorEntry ae = authors.get(sid);
            if (ae == null || sid == null || sid.value() == null || sid.value().isEmpty()) continue;
            JsonObject a = new JsonObject();
            a.addProperty(KEY_TRUST, ae.trust);
            JsonArray keyElems = new JsonArray();
            List<String> sortedKeys = new ArrayList<>(ae.keys);
            Collections.sort(sortedKeys);
            for (String k : sortedKeys) {
                keyElems.add(k);
            }
            a.add(KEY_KEYS, keyElems);
            String resolvedName = ae.name;
            if ((resolvedName == null || resolvedName.isEmpty()) && knownNames != null) {
                resolvedName = knownNames.get(sid);
            }
            if (resolvedName != null && !resolvedName.isEmpty()) {
                a.addProperty(KEY_NAME, resolvedName);
                ae.name = resolvedName;
            }
            o.add(sid.value(), a);
        }
        return o;
    }

    private static void readJsonInto(
        Path jp,
        JarDecisionTable into,
        Map<SteamID64, AuthorEntry> authors,
        Map<String, Set<String>> decisionModIds,
        Map<String, DecisionAuthor> decisionAuthors
    ) throws Exception {
        String raw = Files.readString(jp, StandardCharsets.UTF_8);
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return;
        }
        JsonElement rootEl = JsonParser.parseString(text);
        if (!rootEl.isJsonObject()) {
            Logger.warn("Java mod approvals file is not a JSON object; ignoring nested decisions");
            return;
        }
        JsonObject root = rootEl.getAsJsonObject();
        JsonElement jarDecisionsEl = root.get(KEY_JAR_DECISIONS);
        if (jarDecisionsEl != null && jarDecisionsEl.isJsonObject()) {
            for (Map.Entry<String, JsonElement> modEntry : jarDecisionsEl.getAsJsonObject().entrySet()) {
                String workshopItemId = modEntry.getKey();
                if (!isWorkshopItemIdKey(workshopItemId)) {
                    continue;
                }
                JsonElement rowEl = modEntry.getValue();
                if (rowEl == null || !rowEl.isJsonObject()) continue;
                JsonObject row = rowEl.getAsJsonObject();

                JsonElement midsEl = row.get(KEY_MOD_IDS);
                if (midsEl != null && midsEl.isJsonArray()) {
                    Set<String> out = decisionModIds.computeIfAbsent(workshopItemId, k -> new LinkedHashSet<>());
                    for (JsonElement item : midsEl.getAsJsonArray()) {
                        String mid = optTrimString(item);
                        if (mid != null) {
                            out.add(mid);
                        }
                    }
                }

                JsonElement authorEl = row.get(KEY_AUTHOR);
                if (authorEl != null && authorEl.isJsonObject()) {
                    JsonObject author = authorEl.getAsJsonObject();
                    String id = optTrimString(author, KEY_ID);
                    if (id != null) {
                        decisionAuthors.put(workshopItemId, new DecisionAuthor(new SteamID64(id), optTrimString(author, KEY_NAME)));
                    }
                }

                JsonElement innerEl = row.get(KEY_DECISIONS);
                if (innerEl == null || !innerEl.isJsonObject()) continue;
                JsonObject inner = innerEl.getAsJsonObject();
                for (Map.Entry<String, JsonElement> he : inner.entrySet()) {
                    Boolean allow = optBoolean(he.getValue());
                    if (allow == null) continue;
                    into.put(workshopItemId, he.getKey(), allow ? Loader.DECISION_YES : Loader.DECISION_NO);
                }
            }
        }
        readAuthorsBlock(root, authors);
    }

    private static void readAuthorsBlock(JsonObject root, Map<SteamID64, AuthorEntry> authors) {
        JsonElement authorsObjEl = root.get(KEY_AUTHORS);
        if (authorsObjEl != null && authorsObjEl.isJsonObject()) {
            JsonObject authorsObj = authorsObjEl.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : authorsObj.entrySet()) {
                String steamId = e.getKey();
                JsonElement nodeEl = e.getValue();
                if (steamId == null || steamId.isEmpty() || nodeEl == null || !nodeEl.isJsonObject()) continue;
                JsonObject node = nodeEl.getAsJsonObject();
                Boolean t = optBoolean(node.get(KEY_TRUST));
                boolean trust = Boolean.TRUE.equals(t);
                String name = optTrimString(node, KEY_NAME);
                Set<String> keys = new LinkedHashSet<>();
                JsonElement kj = node.get(KEY_KEYS);
                if (kj != null && kj.isJsonArray()) {
                    for (JsonElement item : kj.getAsJsonArray()) {
                        String k = optTrimString(item);
                        if (k != null) {
                            keys.add(k.toLowerCase(Locale.ROOT));
                        }
                    }
                }
                authors.put(new SteamID64(steamId.trim()), new AuthorEntry(trust, keys, name));
            }
        }

    }

    private static JsonObject parseRootObject(String trimmed) {
        if (trimmed == null || trimmed.isEmpty()) {
            return new JsonObject();
        }
        JsonElement el = JsonParser.parseString(trimmed);
        return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    /** Non-empty trimmed string, or {@code null}. */
    private static String optTrimString(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return null;
        }
        String s = e.getAsString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String optTrimString(JsonObject o, String key) {
        return o == null ? null : optTrimString(o.get(key));
    }

    private static Boolean optBoolean(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return e.getAsBoolean();
    }

    private static boolean isWorkshopItemIdKey(String key) {
        if (key == null) return false;
        String s = key.trim();
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        try {
            return Long.parseLong(s) > 0L;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
