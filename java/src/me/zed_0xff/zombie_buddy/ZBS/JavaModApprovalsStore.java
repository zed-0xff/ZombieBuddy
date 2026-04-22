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

import mjson.Json;

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
 *       "hashes": { "sha256hex": true | false },
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
    private static final String KEY_HASHES = "hashes";
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
            Json root;
            if (Files.exists(jp)) {
                try {
                    String existing = Files.readString(jp, StandardCharsets.UTF_8).trim();
                    root = existing.isEmpty() ? Json.object() : Json.read(existing);
                    if (!root.isObject()) {
                        root = Json.object();
                    }
                } catch (Exception e) {
                    Logger.warn("Approvals JSON unreadable; rewriting: " + e);
                    root = Json.object();
                }
            } else {
                root = Json.object();
            }
            if (!root.has(KEY_FORMAT_VERSION)) {
                root.set(KEY_FORMAT_VERSION, FORMAT_VERSION);
            }
            Map<SteamID64, String> knownNames = SteamAuthorNames.loadSteamIdToDisplayName();
            root.set(KEY_JAR_DECISIONS, jarDecisionsToJson(table, decisionModIds, decisionAuthors, knownNames));
            root.set(KEY_AUTHORS, authorsToJson(authors, knownNames));
            Files.writeString(jp, MjsonPretty.format(root), StandardCharsets.UTF_8);
            int written = table == null ? 0 : table.decisionCount();
            int authorCount = authors == null ? 0 : authors.size();
            Logger.info("Java mod approvals JSON written to " + jp + ": " + written
                + " decision(s), " + authorCount + " author(s)");
        } catch (Exception e) {
            Logger.error("Could not save Java mod approvals: " + e);
        }
    }

    static Json jarDecisionsToJson(
        JarDecisionTable table,
        Map<String, Set<String>> decisionModIds,
        Map<String, DecisionAuthor> decisionAuthors,
        Map<SteamID64, String> knownNames
    ) {
        Json jarDecisions = Json.object();
        if (table == null) return jarDecisions;
        for (String workshopItemId : table.modIds()) {
            Json hashes = Json.object();
            for (Map.Entry<String, String> e : table.hashesOf(workshopItemId).entrySet()) {
                String v = e.getValue();
                if (Loader.DECISION_YES.equals(v) || Loader.DECISION_NO.equals(v)) {
                    hashes.set(e.getKey(), Loader.DECISION_YES.equals(v));
                }
            }
            Json row = Json.object();
            List<Json> modIds = new ArrayList<>();
            Set<String> mids = decisionModIds != null ? decisionModIds.get(workshopItemId) : null;
            if (mids != null) {
                List<String> sorted = new ArrayList<>(mids);
                Collections.sort(sorted);
                for (String mid : sorted) {
                    if (mid != null && !mid.isEmpty()) {
                        modIds.add(Json.make(mid));
                    }
                }
            }
            row.set(KEY_MOD_IDS, Json.array(modIds.toArray(new Object[0])));
            row.set(KEY_HASHES, hashes);
            DecisionAuthor da = decisionAuthors != null ? decisionAuthors.get(workshopItemId) : null;
            if (da != null && da.id != null && da.id.value() != null && !da.id.value().isEmpty()) {
                Json author = Json.object();
                author.set(KEY_ID, da.id.value());
                String resolvedName = da.name;
                if ((resolvedName == null || resolvedName.isEmpty()) && knownNames != null) {
                    resolvedName = knownNames.get(da.id);
                }
                if (resolvedName != null && !resolvedName.isEmpty()) {
                    author.set(KEY_NAME, resolvedName);
                }
                row.set(KEY_AUTHOR, author);
            }
            jarDecisions.set(workshopItemId, row);
        }
        return jarDecisions;
    }

    static Json authorsToJson(Map<SteamID64, AuthorEntry> authors, Map<SteamID64, String> knownNames) {
        Json o = Json.object();
        if (authors == null || authors.isEmpty()) {
            return o;
        }
        List<SteamID64> ids = new ArrayList<>(authors.keySet());
        ids.sort((a, b) -> a.value().compareTo(b.value()));
        for (SteamID64 sid : ids) {
            AuthorEntry ae = authors.get(sid);
            if (ae == null || sid == null || sid.value() == null || sid.value().isEmpty()) continue;
            Json a = Json.object();
            a.set(KEY_TRUST, ae.trust);
            List<Json> keyElems = new ArrayList<>();
            List<String> sortedKeys = new ArrayList<>(ae.keys);
            Collections.sort(sortedKeys);
            for (String k : sortedKeys) {
                keyElems.add(Json.make(k));
            }
            a.set(KEY_KEYS, Json.array(keyElems.toArray(new Object[0])));
            String resolvedName = ae.name;
            if ((resolvedName == null || resolvedName.isEmpty()) && knownNames != null) {
                resolvedName = knownNames.get(sid);
            }
            if (resolvedName != null && !resolvedName.isEmpty()) {
                a.set(KEY_NAME, resolvedName);
                ae.name = resolvedName;
            }
            o.set(sid.value(), a);
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
        Json root = Json.read(text);
        if (!root.isObject()) {
            Logger.warn("Java mod approvals file is not a JSON object; ignoring nested decisions");
            return;
        }
        Json jarDecisions = root.at(KEY_JAR_DECISIONS);
        if (jarDecisions != null && jarDecisions.isObject()) {
            for (Map.Entry<String, Json> modEntry : jarDecisions.asJsonMap().entrySet()) {
                String workshopItemId = modEntry.getKey();
                Json row = modEntry.getValue();
                if (row == null || !row.isObject()) continue;

                Json mids = row.at(KEY_MOD_IDS);
                if (mids != null && mids.isArray()) {
                    Set<String> out = decisionModIds.computeIfAbsent(workshopItemId, k -> new LinkedHashSet<>());
                    for (Json item : mids.asJsonList()) {
                        if (item != null && item.isString()) {
                            String mid = item.asString().trim();
                            if (!mid.isEmpty()) out.add(mid);
                        }
                    }
                }

                Json author = row.at(KEY_AUTHOR);
                if (author != null && author.isObject()) {
                    Json idj = author.at(KEY_ID);
                    if (idj != null && idj.isString()) {
                        String id = idj.asString().trim();
                        if (!id.isEmpty()) {
                            String name = null;
                            Json namej = author.at(KEY_NAME);
                            if (namej != null && namej.isString()) {
                                String v = namej.asString().trim();
                                if (!v.isEmpty()) name = v;
                            }
                            decisionAuthors.put(workshopItemId, new DecisionAuthor(new SteamID64(id), name));
                        }
                    }
                }

                Json inner = row.at(KEY_HASHES);
                if (inner == null || !inner.isObject()) continue;
                for (Map.Entry<String, Json> he : inner.asJsonMap().entrySet()) {
                    Json jv = he.getValue();
                    if (jv == null || !jv.isBoolean()) continue;
                    into.put(workshopItemId, he.getKey(), jv.asBoolean() ? Loader.DECISION_YES : Loader.DECISION_NO);
                }
            }
        }
        readAuthorsBlock(root, authors);
    }

    private static void readAuthorsBlock(Json root, Map<SteamID64, AuthorEntry> authors) {
        Json authorsObj = root.at(KEY_AUTHORS);
        if (authorsObj != null && authorsObj.isObject()) {
            for (Map.Entry<String, Json> e : authorsObj.asJsonMap().entrySet()) {
                String steamId = e.getKey();
                Json node = e.getValue();
                if (steamId == null || steamId.isEmpty() || node == null || !node.isObject()) continue;
                boolean trust = false;
                Json tj = node.at(KEY_TRUST);
                if (tj != null && tj.isBoolean()) {
                    trust = tj.asBoolean();
                }
                String name = null;
                Json nj = node.at(KEY_NAME);
                if (nj != null && nj.isString()) {
                    String v = nj.asString().trim();
                    if (!v.isEmpty()) name = v;
                }
                Set<String> keys = new LinkedHashSet<>();
                Json kj = node.at(KEY_KEYS);
                if (kj != null && kj.isArray()) {
                    for (Json item : kj.asJsonList()) {
                        if (item != null && item.isString()) {
                            String k = item.asString().trim();
                            if (!k.isEmpty()) {
                                keys.add(k.toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                }
                authors.put(new SteamID64(steamId.trim()), new AuthorEntry(trust, keys, name));
            }
        }

    }
}
