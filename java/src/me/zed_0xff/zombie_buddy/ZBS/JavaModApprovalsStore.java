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
 *   "jarDecisions": {
 *     "ModId": { "sha256hex": true | false }
 *   },
 *   "authors": {
 *     "7656119…": { "trust": true, "keys": [ "…64 hex Ed25519 pubkey(s)…" ] }
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
    private static final String KEY_JAR_DECISIONS = "jarDecisions";
    private static final String KEY_AUTHORS = "authors";

    private static final String KEY_TRUST = "trust";
    private static final String KEY_KEYS = "keys";

    private JavaModApprovalsStore() {}

    /** Per SteamID64: trust flag and optional JavaModZBS pubkey hex strings. */
    public static final class AuthorEntry {
        public boolean trust;
        /** Ed25519 public keys from profile ({@code JavaModZBS}), lowercased hex. */
        public final Set<String> keys;

        public AuthorEntry(boolean trust, Set<String> keys) {
            this.trust = trust;
            this.keys = new LinkedHashSet<>();
            if (keys != null) {
                for (String k : keys) {
                    if (k != null && !k.isEmpty()) {
                        this.keys.add(k.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        public AuthorEntry copy() {
            return new AuthorEntry(trust, new LinkedHashSet<>(keys));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AuthorEntry other)) return false;
            return trust == other.trust && keys.equals(other.keys);
        }

        @Override
        public int hashCode() {
            return Objects.hash(trust, keys);
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
        private final Map<String, AuthorEntry> authors;

        Snapshot(JarDecisionTable jarDecisions, Map<String, AuthorEntry> authors) {
            this.jarDecisions = jarDecisions;
            this.authors = authors;
        }

        JarDecisionTable jarDecisions() {
            return jarDecisions;
        }

        Map<String, AuthorEntry> authors() {
            return authors;
        }
    }

    static Snapshot loadSnapshot() {
        Path jp = jsonPath();
        Path leg = legacyTxtPath();
        JarDecisionTable table = new JarDecisionTable();
        Map<String, AuthorEntry> authors = new HashMap<>();
        try {
            if (Files.exists(jp)) {
                readJsonInto(jp, table, authors);
                Logger.info("Java mod approvals JSON read from " + jp + ": " + table.decisionCount()
                    + " decision(s), " + authors.size() + " author(s)");
            }
            if (Files.exists(leg)) {
                Files.delete(leg);
                Logger.info("Deleted legacy Java mod approvals file " + leg.getFileName()
                    + " without importing (use " + jp.getFileName() + " only)");
            }
            return new Snapshot(table, authors);
        } catch (Exception e) {
            Logger.error("Could not load Java mod approvals: " + e);
        }
        return new Snapshot(table, authors);
    }

    static JarDecisionTable load() {
        return loadSnapshot().jarDecisions();
    }

    static Map<String, AuthorEntry> loadAuthors() {
        return new HashMap<>(loadSnapshot().authors());
    }

    /**
     * Writes {@code jarDecisions}. If a file already exists, reads it first and merges,
     * replacing {@code jarDecisions} and {@code authors}; other top-level keys are kept when possible.
     */
    static void save(JarDecisionTable table) {
        save(table, loadAuthors());
    }

    static void save(JarDecisionTable table, Map<String, AuthorEntry> authors) {
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
            root.set(KEY_JAR_DECISIONS, jarDecisionsToJson(table));
            root.set(KEY_AUTHORS, authorsToJson(authors));
            Files.writeString(jp, MjsonPretty.format(root), StandardCharsets.UTF_8);
            int written = table == null ? 0 : table.decisionCount();
            int authorCount = authors == null ? 0 : authors.size();
            Logger.info("Java mod approvals JSON written to " + jp + ": " + written
                + " decision(s), " + authorCount + " author(s)");
        } catch (Exception e) {
            Logger.error("Could not save Java mod approvals: " + e);
        }
    }

    static Json jarDecisionsToJson(JarDecisionTable table) {
        Json jarDecisions = Json.object();
        if (table == null) return jarDecisions;
        for (String modId : table.modIds()) {
            Json hashes = Json.object();
            for (Map.Entry<String, String> e : table.hashesOf(modId).entrySet()) {
                String v = e.getValue();
                if (Loader.DECISION_YES.equals(v) || Loader.DECISION_NO.equals(v)) {
                    hashes.set(e.getKey(), Loader.DECISION_YES.equals(v));
                }
            }
            jarDecisions.set(modId, hashes);
        }
        return jarDecisions;
    }

    static Json authorsToJson(Map<String, AuthorEntry> authors) {
        Json o = Json.object();
        if (authors == null || authors.isEmpty()) {
            return o;
        }
        List<String> ids = new ArrayList<>(authors.keySet());
        Collections.sort(ids);
        for (String steamId : ids) {
            AuthorEntry ae = authors.get(steamId);
            if (ae == null || steamId == null || steamId.isEmpty()) continue;
            Json a = Json.object();
            a.set(KEY_TRUST, ae.trust);
            List<Json> keyElems = new ArrayList<>();
            List<String> sortedKeys = new ArrayList<>(ae.keys);
            Collections.sort(sortedKeys);
            for (String k : sortedKeys) {
                keyElems.add(Json.make(k));
            }
            a.set(KEY_KEYS, Json.array(keyElems.toArray(new Object[0])));
            o.set(steamId, a);
        }
        return o;
    }

    private static void readJsonInto(Path jp, JarDecisionTable into, Map<String, AuthorEntry> authors) throws Exception {
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
                String modId = modEntry.getKey();
                Json inner = modEntry.getValue();
                if (inner == null || !inner.isObject()) continue;
                for (Map.Entry<String, Json> he : inner.asJsonMap().entrySet()) {
                    Json jv = he.getValue();
                    if (jv == null || !jv.isBoolean()) continue;
                    into.put(modId, he.getKey(), jv.asBoolean() ? Loader.DECISION_YES : Loader.DECISION_NO);
                }
            }
        }
        readAuthorsBlock(root, authors);
    }

    private static void readAuthorsBlock(Json root, Map<String, AuthorEntry> authors) {
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
                authors.put(steamId.trim(), new AuthorEntry(trust, keys));
            }
        }
    }
}
