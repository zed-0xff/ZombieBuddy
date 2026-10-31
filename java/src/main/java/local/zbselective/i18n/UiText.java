package local.zbselective.i18n;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Display-only language state. No permission, protocol, identifier or signature values are translated. */
public final class UiText {
    private static volatile String language = "EN";
    private static volatile java.lang.reflect.Method translatorUi;
    private static boolean warningLogged;

    private UiText() {}

    /** Agent startup/standalone child: read preferences without initializing game classes. */
    public static void initialize() {
        String inherited = System.getProperty("zb.ui.language");
        if (inherited != null) { language = normalize(inherited); return; }
        Path cache = Path.of(System.getProperty("user.home"), "Zomboid");
        try {
            boolean foundCache = false;
            String[] args = ProcessHandle.current().info().arguments().orElse(new String[0]);
            for (int i = 0; i < args.length; i++) {
                if (args[i].startsWith("-cachedir=")) { cache = Path.of(unquote(args[i].substring(10))); foundCache = true; }
                else if (args[i].equals("-cachedir") && i + 1 < args.length) { cache = Path.of(unquote(args[++i])); foundCache = true; }
            }
            // Windows may not expose ProcessHandle.arguments; main-command text is still available.
            if (!foundCache) {
                var option = java.util.regex.Pattern.compile("(?:^|\\s)-cachedir(?:=|\\s+)(\"[^\"]+\"|.*?)(?=\\s+-\\S|$)")
                    .matcher(System.getProperty("sun.java.command", ""));
                if (option.find()) cache = Path.of(unquote(option.group(1).trim()));
            }
            language = readOptionsLanguage(cache.resolve("options.ini"));
        } catch (Exception error) { language = "EN"; }
    }

    static String readOptionsLanguage(Path file) throws java.io.IOException {
        if (!Files.isRegularFile(file)) return "EN";
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line = line.replace("\uFEFF", "").trim();
            int separator = line.indexOf('=');
            if (separator > 0 && line.substring(0, separator).trim().equalsIgnoreCase("language"))
                return normalize(line.substring(separator + 1).trim());
        }
        return "EN";
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
            ? value.substring(1, value.length() - 1) : value;
    }

    private static String normalize(String value) {
        return value != null && value.equalsIgnoreCase("CN") ? "CN" : "EN";
    }

    public static String language() { return language; }
    public static String childVmOption() { return "-Dzb.ui.language=" + language; }
    public static boolean isChinese() { return language.equals("CN"); }
    public static String text(String english, String chinese) { return isChinese() ? chinese : english; }

    /** Called only after the game's own Translator.setLanguage has finished. */
    public static void languageChanged(String gameLanguage) {
        language = normalize(gameLanguage);
        applyLuaUiTranslations();
    }

    public static boolean replacesLegacyPackage(String pkg) { return "cn.zbcn".equals(pkg); }

    /** Update only the original framework's nine UI keys; do not touch any other mod's text. */
    @SuppressWarnings("unchecked")
    public static void applyLuaUiTranslations() {
        try {
            java.lang.reflect.Method method = translatorUi;
            if (method == null) {
                method = Class.forName("zombie.core.Translator", false, UiText.class.getClassLoader()).getMethod("getUI");
                translatorUi = method;
            }
            Map<String,String> ui = (Map<String,String>) method.invoke(null);
            ui.putAll(isChinese() ? LuaUiTable.CN : LuaUiTable.EN);
        } catch (Exception error) {
            if (!warningLogged) {
                warningLogged = true;
                System.err.println("[ZB i18n] Game UI translation table unavailable: " + error);
            }
        }
    }

    /** Status notices remain English internally so later language changes can redraw them correctly. */
    public static String watermarkLine(String english) {
        if (!isChinese() || english == null) return english;
        return english.replace("Preload mod \"", "预加载模组 “")
            .replace("\" added/updated. Restart recommended.", "” 已添加或更新，建议重启游戏。")
            .replace("\" disabled. Restart the game to deactivate it.", "” 已停用，重启游戏后生效。");
    }
}
