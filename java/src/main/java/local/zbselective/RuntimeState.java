package local.zbselective;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Cache selects a restart boundary, never grants permission or loads mod code. */
public final class RuntimeState {
    public record Entry(String id, String path, String pkg, String hash) {}
    private static Map<String, Entry> boot = Map.of();
    private static final Set<String> eligibleMain = new HashSet<>();
    private static final Map<String, Entry> loaded = new LinkedHashMap<>();
    private static final Map<String, Path> packageJars = new ConcurrentHashMap<>();
    private static Map<String, Entry> selected = new LinkedHashMap<>();
    private static Set<String> enabled = Set.of(), preloadIds = Set.of();
    private static boolean pending;
    private static String bootProfile = "default", profile = "default";
    private static boolean firstBatch = true, menuLoad, gameProfileSeen;
    private static Path ownJar, statePath;

    public static synchronized void initialize(Path own) throws IOException {
        ownJar = own;
        // Dedicated namespace; config_dir is not interpreted as permission to use another cache.
        statePath = Path.of(System.getProperty("zbselective.state", me.zed_0xff.zombie_buddy.Agent.configDir().resolve("selective-hooks.properties").toString()));
        boot = read(statePath);
        eligibleMain.clear(); eligibleMain.addAll(boot.keySet());
        if (Files.isRegularFile(statePath)) {
            Properties p = new Properties();
            try (var reader = Files.newBufferedReader(statePath, StandardCharsets.UTF_8)) { p.load(reader); }
            bootProfile = p.getProperty("profile", "default");
        }
        Path defaults = Path.of(System.getProperty("zbselective.defaultMods", Path.of(System.getProperty("user.home"),
            "Zomboid", "mods", "default.txt").toString()));
        preloadIds = bootProfile.equals("default") ? readDefaultIds(defaults)
            : Set.copyOf(boot.values().stream().map(Entry::id).toList());
        log("startup cache: " + boot.size() + " approved previously selected JAR(s)");
    }

    static Set<String> readDefaultIds(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return Set.of();
        Set<String> result = new HashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            var matcher = java.util.regex.Pattern.compile("^\\s*mod\\s*=\\s*(.*?)\\s*,?\\s*$").matcher(line);
            if (matcher.matches()) result.add(matcher.group(1).replaceAll(",$", "").trim().replaceFirst("^\\\\", ""));
        }
        return Set.copyOf(result);
    }

    public static synchronized boolean shouldPreload(String id, Path jar) {
        if (!preloadIds.contains(id)) return false;
        try {
            Entry entry = boot.get(jar.toRealPath().toString());
            return entry != null && entry.id().equals(id);
        } catch (IOException error) { return false; }
    }

    public static synchronized void begin(List<String> mods) {
        try {
            Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader", false, ClassLoader.getSystemClassLoader());
            Field field = loader.getDeclaredField("g_curr_modSetID"); field.setAccessible(true);
            Object value = field.get(null);
            profile = value == null ? "default" : value.toString();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot resolve mod profile", error); }
        // Default-menu reloads must not overwrite an active or pending save selection.
        // Keep this distinction after returning from a world, not only on the first startup batch.
        boolean defaultProfile = profile.equals("default");
        menuLoad = defaultProfile && (gameProfileSeen || !bootProfile.equals("default"));
        if (!defaultProfile) gameProfileSeen = true;
        firstBatch = false;
        enabled = new HashSet<>(mods);
        selected = new LinkedHashMap<>();
        pending = false;
    }

    public static synchronized List<String> enabledPreloadIds(List<String> ids) {
        // Never inject a registered-but-disabled preload into the current profile.
        return ids.stream().filter(enabled::contains).toList();
    }

    public static synchronized boolean allowJar(Path jar, String pkg, String hash, String phase) {
        try {
            Path real = jar.toRealPath();
            // Explicit patches_jar command-line entries are not approval-managed mods.
            if (hash == null && phase.equals("PREMAIN")) {
                packageJars.put(pkg, real);
                return true;
            }
            if (hash == null) throw new IOException("Missing approved JAR hash: " + jar);
            Entry previous = boot.get(real.toString());
            if (phase.equals("PREMAIN")) {
                if (previous == null || !previous.pkg().equals(pkg) || !previous.hash().equals(hash)
                        || !preloadIds.contains(previous.id())) {
                    log("skip inactive/unprepared preload: " + pkg);
                    return false;
                }
                packageJars.put(pkg, real);
                return true;
            }
            String id = modId(real);
            Entry entry = new Entry(id, real.toString(), pkg, hash);
            if (menuLoad) {
                // Shared, already-loaded code may serve the menu. Never add/reload other Java code here.
                if (entry.equals(loaded.get(entry.path()))) {
                    packageJars.put(pkg, real);
                    return true;
                }
                log("menu Java code deferred until a game selection: " + id + " / " + pkg);
                return false;
            }
            selected.put(entry.path(), entry);
            // Even a cache hit reaches this point ONLY through upstream policy/ZBS/ban checks.
            if (!entry.equals(previous) || !eligibleMain.contains(entry.path())) {
                pending = true;
                log("approved; deferred until restart: " + id + " / " + pkg);
                return false;
            }
            packageJars.put(pkg, real);
            return true;
        } catch (Exception error) {
            throw new IllegalStateException("SelectiveHooks cannot prepare Java mod " + pkg, error);
        }
    }

    public static synchronized void installed(Path jar, String pkg, String hash, String phase) {
        if (hash == null) return; // Explicit command-line patches are outside the mod selection.
        try {
            Path real = jar.toRealPath();
            Entry entry = phase.equals("PREMAIN") ? boot.get(real.toString())
                : new Entry(modId(real), real.toString(), pkg, hash);
            loaded.put(real.toString(), Objects.requireNonNull(entry));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot record installed Java mod " + pkg, error);
        }
    }

    public static synchronized boolean isInstalled(Path jar) {
        try { return loaded.containsKey(jar.toRealPath().toString()); }
        catch (IOException error) { return false; }
    }

    private static String modId(Path jar) throws ReflectiveOperationException {
        Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader", false, ClassLoader.getSystemClassLoader());
        Field field = loader.getDeclaredField("g_jarLoadStatus");
        field.setAccessible(true);
        Object status = ((Map<?,?>) field.get(null)).get(jar);
        if (status == null) throw new IllegalStateException("Missing upstream decision snapshot: " + jar);
        Method id = status.getClass().getDeclaredMethod("id");
        id.setAccessible(true);
        return (String) id.invoke(status);
    }

    public static synchronized Object[] scanClasspath(Object[] original, String pkg) {
        Path jar = packageJars.get(pkg);
        if (jar != null) return new Object[]{jar};
        // Framework package uses its own JAR. Preserve fallback for direct/custom classloader API calls.
        if (pkg.startsWith("me.zed_0xff.zombie_buddy") && original.length > 0)
            return new Object[]{original[0]};
        return original;
    }

    public static synchronized void finish() {
        if (menuLoad) {
            log("menu ready; current Java environment and saved game selection retained");
            return;
        }
        // Already installed Java hooks cannot be safely unloaded in a running JVM.
        boolean removedOrChanged = loaded.values().stream().anyMatch(e -> !e.equals(selected.get(e.path())));
        // A cached-but-disabled mod enabled later in this JVM also needs a restart.
        eligibleMain.retainAll(selected.keySet());
        try { write(statePath, selected); }
        catch (IOException error) { throw new IllegalStateException("Cannot save restart plan", error); }
        if (pending || removedOrChanged) {
            log("RESTART REQUIRED: selection/approval changed; restart plan saved to " + statePath);
            requestRestart();
        }
        log("selection ready: " + selected.size() + " Java mod(s); " + TargetInstrumentation.stats());
    }

    static void requestRestart() {
        String message = local.zbselective.i18n.UiText.text(
            "Java mod selection changed. A full game restart is required.\n\nNew/updated mods have been deferred.\nClick OK to close the game, then start it again.",
            "Java 模组已获准或启用列表发生变化。\n\n为按需加载 Java hook，必须完整重启游戏。\n待加载记录已保存，新模组尚未执行。\n点击确定后游戏将关闭，请重新启动游戏。");
        try {
            if (!Boolean.parseBoolean(System.getProperty("zbselective.restartUi", "true"))) {
                System.err.println(message);
                System.exit(42);
                throw new IllegalStateException("Restart exit blocked");
            }
            String exe = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Process dialog = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", exe).toString(),
                "-Djava.awt.headless=false", local.zbselective.i18n.UiText.childVmOption(),
                "-cp", ownJar.toString(), RestartDialog.class.getName(), message)
                .inheritIO().start();
            if (!dialog.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) dialog.destroyForcibly();
        } catch (Exception error) { log("restart dialog unavailable: " + error); }
        // loadMods runs before starting/loading a world; no gameplay is allowed with missing hooks.
        System.exit(42);
        throw new IllegalStateException("Restart was blocked");
    }

    static Map<String, Entry> read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return Map.of();
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(reader); }
        if (!"1".equals(p.getProperty("schema"))) throw new IOException("Unsupported/corrupt restart cache");
        int count = Integer.parseInt(p.getProperty("count"));
        if (count < 0 || count > 10000) throw new IOException("Invalid restart cache size");
        Map<String, Entry> result = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String prefix = i + ".";
            Entry entry = new Entry(Objects.requireNonNull(p.getProperty(prefix + "id")),
                Objects.requireNonNull(p.getProperty(prefix + "path")),
                Objects.requireNonNull(p.getProperty(prefix + "pkg")),
                Objects.requireNonNull(p.getProperty(prefix + "hash")));
            result.put(entry.path(), entry);
        }
        return Map.copyOf(result);
    }

    static void write(Path path, Map<String, Entry> entries) throws IOException {
        Properties p = new Properties();
        p.setProperty("schema", "1"); p.setProperty("count", Integer.toString(entries.size()));
        p.setProperty("profile", profile);
        int i = 0;
        for (Entry entry : entries.values()) {
            String prefix = i++ + ".";
            p.setProperty(prefix + "id", entry.id()); p.setProperty(prefix + "path", entry.path());
            p.setProperty(prefix + "pkg", entry.pkg()); p.setProperty(prefix + "hash", entry.hash());
        }
        Path dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "selective-hooks-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { p.store(writer, "ZombieBuddySelectiveHooks: restart plan, NOT authorization"); }
            // Windows scanners may briefly hold the previous file without delete sharing.
            for (int attempt = 0; ; attempt++) {
                try {
                    try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
                    break;
                } catch (AccessDeniedException busy) {
                    if (attempt >= 5) throw busy;
                    try { Thread.sleep(50L * (attempt + 1)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                }
            }
        } finally { Files.deleteIfExists(temp); }
    }

    public static void log(String text) { System.err.println("[ZBSelectiveHooks] " + text); }

}
