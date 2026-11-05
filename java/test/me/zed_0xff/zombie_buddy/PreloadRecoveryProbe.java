package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontend;

/** Actual loader/save gate with an ephemeral Ed25519 signature and offline HTTP fixtures. */
public final class PreloadRecoveryProbe {
    private static final String ID = "PreloadRecoveryFixture";
    private static final SteamWorkshop.SteamID64 AUTHOR = new SteamWorkshop.SteamID64(76561198000000019L);
    private static final long WORKSHOP_ID = 3709229404L;

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void set(String name, Object value) throws Exception {
        field(Loader.class, name).set(null, value);
    }

    private static void initializeGameRandom() throws Exception {
        Class<?> type = Class.forName("zombie.core.random.RandStandard");
        type.getMethod("init").invoke(type.getField("INSTANCE").get(null));
    }

    private static void offline(String key, boolean banned) {
        SteamWorkshop.putCachedBody(KnownAuthors.REMOTE_URL, "", "{}");
        SteamWorkshop.putCachedBody(SteamWorkshop.authorProfileUrl(AUTHOR), "", "JavaModZBS:" + key);
        SteamWorkshop.putCachedBody("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/",
            "itemcount=1&publishedfileids[0]=" + WORKSHOP_ID,
            "{\"response\":{\"publishedfiledetails\":[{\"publishedfileid\":\"" + WORKSHOP_ID
                + "\",\"creator\":\"" + AUTHOR + "\",\"banned\":" + (banned ? 1 : 0) + "}]}}");
        assertEquals("{}", SteamWorkshop.getCachedBody(KnownAuthors.REMOTE_URL, ""));
    }

    private static Path fixture(Path root, String mode) throws Exception {
        Path dir = Files.createDirectories(root.resolve(mode.equals("trusted-local") ? "local-mod"
            : "steamapps/workshop/content/108600/" + WORKSHOP_ID + "/mods/fixture"));
        Files.writeString(dir.resolve("mod.info"), "name=Preload recovery fixture\nid=" + ID
            + "\njavaJarFile=fixture.jar\njavaPkgName=local.preload.recovery\njavaPreload=" + !mode.equals("no-declaration") + "\n");
        Path classes = Files.createDirectories(root.resolve("classes"));
        var sources = new ArrayList<String>();
        for (String name : List.of("Main", "PreMain")) {
            Path source = root.resolve(name + ".java");
            String method = name.equals("Main") ? "main" : "premain";
            Files.writeString(source, "package local.preload.recovery; public class " + name
                + " { public static void " + method + "(String[] args) { System.setProperty(\"recovery." + method + "\", \"yes\"); } }");
            sources.add(source.toString());
        }
        var args = new ArrayList<>(List.of("--release", "17", "-d", classes.toString()));
        args.addAll(sources);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (!mode.equals("no-manifest")) manifest.getMainAttributes().putValue("ZB-Preload", "true");
        Path jar = dir.resolve("fixture.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = pair.getPublic().getEncoded();
        String key = HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
        offline(key, mode.equals("banned"));
        Files.writeString(root.resolve("public-key.txt"), key);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(("ZBS:" + AUTHOR + ":" + Utils.sha256Hex(jar)).getBytes(StandardCharsets.UTF_8));
        if (!mode.equals("unsigned")) Files.writeString(Path.of(jar + ".zbs"), "ZBS\nSteamID64:" + AUTHOR
            + "\nSignature:" + (mode.equals("invalid") ? "00" : HexFormat.of().formatHex(signature.sign())) + "\n");
        return jar;
    }

    @SuppressWarnings("unchecked")
    private static void exercise(Path root, String mode) throws Exception {
        initializeGameRandom();
        root = root.toAbsolutePath();
        Files.createDirectories(root);
        Path jar = fixture(root, mode);
        Path configDir = Files.createDirectories(root.resolve("config"));
        Agent.arguments.put("config_dir", configDir.toString());
        set("g_jarPolicy", mode.equals("allow-all") ? "allow-all" : mode.equals("deny-new") ? "deny-new" : "prompt");
        set("g_allowUnsignedMods", true);
        set("g_hasDoLoadingText", false);
        set("g_forceApprovalDialog", mode.equals("dialog"));
        ((JarDecisionTable) field(Loader.class, "g_sessionJarDecisions").get(null)).hashes().clear();
        ((Map<?, ?>) field(Loader.class, "g_jarLoadStatus").get(null)).clear();
        String hash = Utils.sha256Hex(jar);
        var stored = new ModApprovalsStore.FileData();
        if (!Set.of("session", "trusted", "trusted-local", "allow-all").contains(mode)) {
            stored.mods.add(new ModApprovalsStore.ModEntry(ID, null, hash, !mode.equals("deny"), "before", AUTHOR));
        }
        stored.mods.add(new ModApprovalsStore.ModEntry("unrelated", null, "unrelated-hash", false, "before", null));
        Files.writeString(configDir.resolve("mod_approvals.json"), ZBGson.PRETTY.toJson(stored));
        Config initial = new Config(new ArrayList<>(), new LinkedHashMap<>(), false, false, true, true);
        if (mode.startsWith("trusted")) initial = initial.withTrustedAuthor(AUTHOR);
        if (mode.equals("existing")) initial = initial.withPreloadMod(ID,
            new Config.PreloadMod(jar.getParent().resolve("mod.info"), jar));
        Files.writeString(configDir.resolve("config.json"), ZBGson.PRETTY.toJson(initial));
        Loader.initConfig();
        byte[] approvalsBefore = Files.readAllBytes(configDir.resolve("mod_approvals.json"));
        byte[] configBefore = Files.readAllBytes(configDir.resolve("config.json"));
        Class<?> type = Class.forName("zombie.gameStates.ChooseGameInfo$Mod");
        String dir = jar.getParent().toString();
        Object mod = type.getConstructor(String.class).newInstance(dir);
        type.getMethod("setId", String.class).invoke(mod, ID);
        type.getMethod("setName", String.class).invoke(mod, ID);
        type.getField("commonDir").set(mod, dir);
        type.getField("versionDir").set(mod, dir);
        field(type, "available").set(mod, true);
        field(type, "availableDone").set(mod, true);
        ((Map<String, Object>) field(Class.forName("zombie.gameStates.ChooseGameInfo"), "Mods").get(null)).put(ID, mod);
        int[] prompts = {0};
        set("g_approvalFrontend", (ModApprovalFrontend) rows -> {
            prompts[0]++;
            for (var row : rows) {
                row.decision = !mode.equals("banned");
                // A current frontend choice omits preload. Recovery must not undo it,
                // even if a matching saved Allow exists before entering this dialog.
                row.flags = row.flags.without(MF_PRELOAD).without(MF_PERSIST).without(MF_TRUST_AUTHOR);
            }
            return rows;
        });
        Loader.loadMods(new ArrayList<>(List.of(ID)));
        Config after = Config.load();
        boolean recovered = Set.of("allow", "trusted", "trusted-local", "deny-new").contains(mode);
        var states = (Map<Path, Loader.JavaModLoadState>) field(Loader.class, "g_jarLoadStatus").get(null);
        var state = states.get(jar.toRealPath());
        if (recovered) {
            assertNotNull(state);
            assertTrue(state.flags().hasAll(MF_ACTIVE, MF_VALID, MF_SIGNED), "Fixture must pass actual signature and load checks");
            assertTrue(after.preload_mods().containsKey(ID), "Saved Allow or verified Author Trust must restore missing registration");
            assertEquals(new Config.PreloadMod(jar.getParent().resolve("mod.info"), jar.toRealPath()), after.preload_mods().get(ID));
            assertEquals(0, prompts[0]);
            assertNull(System.getProperty("recovery.premain"), "Registration cannot preload retroactively");
            byte[] once = Files.readAllBytes(configDir.resolve("config.json"));
            Loader.loadMods(new ArrayList<>(List.of(ID)));
            assertArrayEquals(once, Files.readAllBytes(configDir.resolve("config.json")), "Existing entry must not be rewritten");
        } else {
            assertEquals(initial.preload_mods(), after.preload_mods(), "No recovery for " + mode);
            assertArrayEquals(configBefore, Files.readAllBytes(configDir.resolve("config.json")));
        }
        assertEquals(initial.trusted_authors(), after.trusted_authors());
        assertFalse(after.auto_fix_mod_order());
        assertFalse(after.fix_approval_dialog_cursor());
        assertTrue(after.fix_macos_retina());
        assertTrue(after.stealth_mode());
        // allow-all deliberately persists its own approval in existing upstream logic.
        if (!mode.equals("allow-all")) assertArrayEquals(approvalsBefore, Files.readAllBytes(configDir.resolve("mod_approvals.json")));
        if (mode.equals("dialog")) assertEquals(1, prompts[0]);
    }

    public static void run(Path root, String mode) throws Exception {
        launch(root, mode);
    }

    public static void restart(Path root) throws Exception {
        launch(root, "restart");
    }

    private static void launch(Path root, String mode) throws Exception {
        Files.createDirectories(root);
        var paths = new LinkedHashSet<String>(Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        for (ClassLoader loader = PreloadRecoveryProbe.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) if (url.getProtocol().equals("file")) paths.add(Path.of(url.toURI()).toString());
            }
        }
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path output = root.resolve(mode + ".log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-Xverify:all", "-cp", String.join(java.io.File.pathSeparator, paths), PreloadRecoveryProbe.class.getName(),
            root.toAbsolutePath().toString(), mode)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(45, java.util.concurrent.TimeUnit.SECONDS), "Preload probe timed out: " + mode);
            assertEquals(0, child.exitValue(), Files.readString(output));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    public static void main(String[] args) throws Exception {
        initializeGameRandom();
        Path root = Path.of(args[0]);
        if (!args[1].equals("restart")) {
            exercise(root, args[1]);
            return;
        }
        Agent.arguments.put("config_dir", root.resolve("config").toString());
        offline(Files.readString(root.resolve("public-key.txt")), false);
        Loader.initConfig();
        Loader.preloadMods();
        assertEquals("yes", System.getProperty("recovery.premain"), "Fresh JVM must execute actual signed PreMain");
        assertTrue(Config.load().trusted_authors().isEmpty(), "Saved hash Allow needs no author trust");
    }
}
