package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

record Config(
    List<SteamID64> trusted_authors,
    Map<String, Config.PreloadMod> preload_mods,
    boolean auto_fix_mod_order,
    boolean fix_approval_dialog_cursor,
    boolean fix_macos_retina,
    boolean stealth_mode
) {
    record PreloadMod(
        Path infPath,
        Path jarPath
    ) {}

    static final String JSON_FILE_NAME = "config.json";
    private static final Config DEFAULT = new Config(new ArrayList<>(), new LinkedHashMap<>(), true, true, false, false);

    Config {
        trusted_authors = normalizeTrustedAuthors(trusted_authors);
        preload_mods = normalizePreloadMods(preload_mods);
    }

    Config() {
        this(DEFAULT.trusted_authors, DEFAULT.preload_mods, DEFAULT.auto_fix_mod_order, DEFAULT.fix_approval_dialog_cursor, DEFAULT.fix_macos_retina, DEFAULT.stealth_mode);
    }

    static Path jsonPath() {
        return Agent.configDir().resolve(JSON_FILE_NAME);
    }

    static Config load() {
        Path path = jsonPath();
        try {
            if (!Files.exists(path)) {
                return defaultConfig();
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (Utils.isBlank(json)) {
                return defaultConfig();
            }
            Config config = ZBGson.PRETTY.fromJson(json, Config.class);
            return config != null ? config : defaultConfig();
        } catch (Exception e) {
            Logger.error("Could not load config: " + e);
            return defaultConfig();
        }
    }

    static void save(Config config) {
        try {
            Path path = jsonPath();
            Files.createDirectories(path.getParent());
            Utils.writeFileAtomic(path, ZBGson.PRETTY.toJson(config != null ? config : defaultConfig()), StandardCharsets.UTF_8);
            Logger.info("ZombieBuddy config written to " + path);
        } catch (Exception e) {
            Logger.error("Could not save config: " + e);
        }
    }

    private static Config defaultConfig() {
        return new Config(DEFAULT.trusted_authors, DEFAULT.preload_mods, DEFAULT.auto_fix_mod_order, DEFAULT.fix_approval_dialog_cursor, DEFAULT.fix_macos_retina, DEFAULT.stealth_mode);
    }

    boolean trustsAuthor(SteamID64 authorId) {
        return authorId != null && trusted_authors.contains(authorId);
    }

    Config withTrustedAuthor(SteamID64 authorId) {
        return withTrustedAuthor(authorId, true);
    }

    Config withoutTrustedAuthor(SteamID64 authorId) {
        return withTrustedAuthor(authorId, false);
    }

    Config withPreloadMod(String id, PreloadMod mod) {
        return withPreloadMod(id, mod, true);
    }

    Config withoutPreloadMod(String id) {
        return withPreloadMod(id, null, false);
    }

    Config withAutoFixModOrder(boolean value) {
        if (value == auto_fix_mod_order) {
            return this;
        }
        return new Config(trusted_authors, preload_mods, value, fix_approval_dialog_cursor, fix_macos_retina, stealth_mode);
    }

    Config withFixApprovalDialogCursor(boolean value) {
        if (value == fix_approval_dialog_cursor) {
            return this;
        }
        return new Config(trusted_authors, preload_mods, auto_fix_mod_order, value, fix_macos_retina, stealth_mode);
    }

    Config withFixMacOSRetina(boolean value) {
        if (value == fix_macos_retina) {
            return this;
        }
        return new Config(trusted_authors, preload_mods, auto_fix_mod_order, fix_approval_dialog_cursor, value, stealth_mode);
    }

    Config withStealthMode(boolean value) {
        if (value == stealth_mode) {
            return this;
        }
        return new Config(trusted_authors, preload_mods, auto_fix_mod_order, fix_approval_dialog_cursor, fix_macos_retina, value);
    }

    private Config withTrustedAuthor(SteamID64 authorId, boolean trusted) {
        if (authorId == null || trusted_authors.contains(authorId) == trusted) {
            return this;
        }
        List<SteamID64> out = new ArrayList<>(trusted_authors);
        if (trusted) {
            out.add(authorId);
        } else {
            out.remove(authorId);
        }
        return withTrustedAuthors(out);
    }

    private Config withTrustedAuthors(List<SteamID64> trustedAuthors) {
        return new Config(trustedAuthors, preload_mods, auto_fix_mod_order, fix_approval_dialog_cursor, fix_macos_retina, stealth_mode);
    }

    private Config withPreloadMod(String id, PreloadMod mod, boolean enabled) {
        if (Utils.isBlank(id) || (enabled && !isValidPreloadMod(mod)) || (!enabled && !preload_mods.containsKey(id))) {
            return this;
        }
        Map<String, PreloadMod> out = new LinkedHashMap<>(preload_mods);
        if (enabled) {
            out.put(id, mod);
        } else {
            out.remove(id);
        }
        return withPreloadMods(out);
    }

    private Config withPreloadMods(Map<String, PreloadMod> preloadMods) {
        return new Config(trusted_authors, preloadMods, auto_fix_mod_order, fix_approval_dialog_cursor, fix_macos_retina, stealth_mode);
    }

    private static List<SteamID64> normalizeTrustedAuthors(List<SteamID64> input) {
        LinkedHashSet<SteamID64> out = new LinkedHashSet<>();
        if (input != null) {
            for (SteamID64 id : input) {
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static Map<String, PreloadMod> normalizePreloadMods(Map<String, PreloadMod> input) {
        LinkedHashMap<String, PreloadMod> out = new LinkedHashMap<>();
        if (input != null) {
            for (Map.Entry<String, PreloadMod> entry : input.entrySet()) {
                PreloadMod mod = entry.getValue();
                if (!Utils.isBlank(entry.getKey()) && isValidPreloadMod(mod)) {
                    out.put(entry.getKey(), mod);
                }
            }
        }
        return out;
    }

    private static boolean isValidPreloadMod(PreloadMod mod) {
        return mod != null && !Utils.isBlank(mod.infPath()) && !Utils.isBlank(mod.jarPath());
    }
}
