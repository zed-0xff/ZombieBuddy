package me.zed_0xff.zombie_buddy;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import me.zed_0xff.zombie_buddy.frontend.*;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

/** Real signed mods, frontend IPC, loader save gates and fresh-process author lifetime. */
public final class AuthorTrustSeparationProbe {
    private static final SteamWorkshop.SteamID64 AUTHOR = new SteamWorkshop.SteamID64(76561198000000029L);
    private static final List<String> IDS = List.of("TrustFirst", "TrustSecond", "TrustLater");
    private static int checks;
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void set(String name, Object value) throws Exception { field(Loader.class, name).set(null, value); }
    private static Path jar(Path root, String id) { return root.resolve(id).resolve("fixture.jar"); }

    private static void fixtures(Path root) throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = pair.getPublic().getEncoded();
        Files.writeString(root.resolve("public-key.txt"), HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length)));
        for (String id : IDS) {
            Path dir = Files.createDirectories(root.resolve(id));
            String pkg = "local.trust." + id.toLowerCase(Locale.ROOT);
            Files.writeString(dir.resolve("mod.info"), "name=" + id + "\nid=" + id + "\njavaJarFile=fixture.jar\njavaPkgName=" + pkg
                + "\njavaPreload=" + !id.equals(IDS.get(2)) + "\n");
            Path classes = Files.createDirectories(dir.resolve("classes"));
            var sources = new ArrayList<String>();
            for (String name : List.of("Main", "PreMain")) {
                Path source = dir.resolve(name + ".java");
                String method = name.equals("Main") ? "main" : "premain";
                Files.writeString(source, "package " + pkg + "; public class " + name + " { public static void " + method
                    + "(String[] args) { System.setProperty(\"trust." + id + "." + method + "\", \"yes\"); } }");
                sources.add(source.toString());
            }
            var args = new ArrayList<>(List.of("--release", "17", "-d", classes.toString())); args.addAll(sources);
            check(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)) == 0, "Signed fixture compiles");
            Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            if (!id.equals(IDS.get(2))) manifest.getMainAttributes().putValue("ZB-Preload", "true");
            try (var out = new JarOutputStream(Files.newOutputStream(jar(root, id)), manifest); var files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                    out.write(Files.readAllBytes(file)); out.closeEntry();
                }
            }
            Signature signer = Signature.getInstance("Ed25519"); signer.initSign(pair.getPrivate());
            signer.update(("ZBS:" + AUTHOR + ":" + Utils.sha256Hex(jar(root, id))).getBytes(StandardCharsets.UTF_8));
            Files.writeString(Path.of(jar(root, id) + ".zbs"), "ZBS\nSteamID64:" + AUTHOR + "\nSignature:" + HexFormat.of().formatHex(signer.sign()) + "\n");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"}) private static void offline(Path root) throws Exception {
        String key = Files.readString(root.resolve("public-key.txt"));
        try {
            var cache = SteamWorkshop.class.getDeclaredMethod("putCachedBody", String.class, String.class, String.class);
            cache.setAccessible(true); cache.invoke(null, KnownAuthors.REMOTE_URL, "", "{}");
            cache.invoke(null, SteamWorkshop.authorProfileUrl(AUTHOR), "", "JavaModZBS:" + key);
        } catch (NoSuchMethodException localEdition) {
            var ctor = Class.forName("me.zed_0xff.zombie_buddy.ApprovalPersistenceProbe$NoNetwork").getDeclaredConstructor(); ctor.setAccessible(true);
            Object client = ctor.newInstance();
            Map clients = (Map) field(Class.forName("me.zed_0xff.zombie_buddy.LazyHttpClients"), "CLIENTS").get(null);
            for (Object role : Class.forName("me.zed_0xff.zombie_buddy.LazyHttpClients$Role").getEnumConstants()) clients.put(role, client);
        }
        try {
            Class<?> local = Class.forName("me.zed_0xff.zombie_buddy.LocalAuthors");
            Field fetcher = field(local, "profileFetcher");
            var ctor = Class.forName(local.getName() + "$Profile").getDeclaredConstructor(String.class, List.class); ctor.setAccessible(true);
            Object profile = ctor.newInstance("Signed trust fixture", List.of(key));
            fetcher.set(null, Proxy.newProxyInstance(local.getClassLoader(), new Class<?>[]{fetcher.getType()}, (proxy, method, args) -> profile));
            for (String name : List.of("STORES", "FETCHED", "FAILED")) ((Map<?, ?>) field(local, name).get(null)).clear();
        } catch (ClassNotFoundException upstreamWithoutCache) { }
    }

    @SuppressWarnings("unchecked") private static void configure(Path root, boolean force) throws Exception {
        Agent.arguments.put("config_dir", root.resolve("config").toString()); offline(root); Loader.initConfig();
        set("g_jarPolicy", "prompt"); set("g_allowUnsignedMods", true); set("g_forceApprovalDialog", force); set("g_hasDoLoadingText", false);
        ((JarDecisionTable) field(Loader.class, "g_sessionJarDecisions").get(null)).hashes().clear();
        ((Map<?, ?>) field(Loader.class, "g_jarLoadStatus").get(null)).clear();
        Class<?> type = Class.forName("zombie.gameStates.ChooseGameInfo$Mod");
        Map<String, Object> mods = (Map<String, Object>) field(Class.forName("zombie.gameStates.ChooseGameInfo"), "Mods").get(null);
        for (String id : IDS) {
            String dir = root.resolve(id).toString(); Object mod = type.getConstructor(String.class).newInstance(dir);
            type.getMethod("setId", String.class).invoke(mod, id); type.getMethod("setName", String.class).invoke(mod, id);
            type.getField("commonDir").set(mod, dir); type.getField("versionDir").set(mod, dir);
            field(type, "available").set(mod, true); field(type, "availableDone").set(mod, true); mods.put(id, mod);
        }
    }

    @SuppressWarnings("unchecked") private static boolean active(Path root, String id) throws Exception {
        var states = (Map<Path, Loader.JavaModLoadState>) field(Loader.class, "g_jarLoadStatus").get(null);
        var state = states.get(jar(root, id).toRealPath());
        if (state != null && state.flags().has(MF_ACTIVE)) {
            check(state.flags().hasAll(MF_VALID, MF_SIGNED), "Loaded fixture must pass genuine signature verification");
            return true;
        }
        return false;
    }

    private static void run(Path root, String mode) throws Exception {
        Files.createDirectories(root); fixtures(root);
        Files.createDirectories(root.resolve("config")); Agent.arguments.put("config_dir", root.resolve("config").toString());
        var seed = new ModApprovalsStore.FileData();
        seed.mods.add(new ModApprovalsStore.ModEntry("unrelated", null, "keep", false, "before", null));
        if (mode.startsWith("ui")) seed.mods.add(new ModApprovalsStore.ModEntry(IDS.get(0), null, Utils.sha256Hex(jar(root, IDS.get(0))), false, "before", AUTHOR));
        ModApprovalsStore.save(seed);
        Config.save(new Config());
        configure(root, mode.startsWith("ui"));
        byte[] approvals = Files.readAllBytes(root.resolve("config/mod_approvals.json"));
        byte[] config = Files.readAllBytes(root.resolve("config/config.json"));
        int[] calls = {0};
        InputStream oldIn = System.in; PrintStream oldOut = System.out;
        var transcript = new ByteArrayOutputStream();
        try {
            String input = mode.equals("console-deny-author") ? "n\ny\ny\nn\nn\ny\n" : "y\n" + (mode.equals("console-saved") ? "y\n" : "n\n");
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            var console = new ConsoleModApprovalFrontend();
            System.setOut(new PrintStream(transcript, true, StandardCharsets.UTF_8));
            set("g_approvalFrontend", (ModApprovalFrontend) rows -> {
                calls[0]++;
                try {
                    List<JarBatchApprovalProtocol.Entry> result;
                    if (mode.startsWith("ui")) {
                        Object dialog = ApprovalDialogProbe.dialog(rows);
                        Object[] allow = (Object[]) ApprovalDialogProbe.field(dialog, "allow");
                        boolean[] before = new boolean[allow.length];
                        for (int i = 0; i < allow.length; i++) before[i] = (boolean) allow[i].getClass().getMethod("get").invoke(allow[i]);
                        ApprovalDialogProbe.trust(dialog, 0, true);
                        for (int i = 0; i < allow.length; i++) check(before[i] == (boolean) allow[i].getClass().getMethod("get").invoke(allow[i]),
                            "Trust checkbox never changes individual Allow/Deny selections");
                        if (mode.equals("ui-direct")) ApprovalDialogProbe.choose(dialog, 0, true);
                        result = ApprovalDialogProbe.finish(dialog, !mode.equals("ui-session"));
                        if (mode.equals("ui-cancel")) result = ApprovalChoices.cancel(result);
                    } else result = console.approvePendingMods(rows);
                    Path response = root.resolve("response.json"); JarBatchApprovalProtocol.writeResponse(response, result);
                    return JarBatchApprovalProtocol.readResponse(response);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            Loader.loadMods(new ArrayList<>(IDS.subList(0, 2)));
        } finally { System.setIn(oldIn); System.setOut(oldOut); }
        Files.writeString(root.resolve("transcript.txt"), transcript.toString(StandardCharsets.UTF_8));
        check(calls[0] == 1, "Initial batch reaches actual frontend once");
        boolean cancel = mode.equals("ui-cancel"), decline = mode.equals("console-deny-author");
        boolean saved = mode.equals("console-saved") || mode.equals("ui-saved") || mode.equals("ui-direct");
        check(Config.load().trustsAuthor(AUTHOR) == saved, "Only saved author choices reach config.json");
        check(active(root, IDS.get(0)) == !cancel, "Independent author authority allows first signed mod");
        check(active(root, IDS.get(1)) == !(cancel || decline), "Independent author authority allows following signed mod");
        if (!decline && !mode.equals("ui-direct")) check(Arrays.equals(approvals, Files.readAllBytes(root.resolve("config/mod_approvals.json"))),
            "Trust-only operation preserves approval file byte-for-byte, including saved Deny");
        if (!saved && !decline) check(Arrays.equals(config, Files.readAllBytes(root.resolve("config/config.json"))), "Session/Cancel leaves trust and preload configuration byte-identical");
        check(Config.load().preload_mods().containsKey(IDS.get(0)) == (saved || decline), "Saved author trust or explicit saved Allow registers preload; session trust does not");
        check(Config.load().preload_mods().containsKey(IDS.get(1)) == saved, "Saved author trust registers following signed preloads without saving their hashes");
        if (mode.equals("ui-direct") || decline) {
            var stored = ModApprovalsStore.load().mods;
            check(stored.stream().anyMatch(e -> Utils.sha256Hex(jar(root, IDS.get(0))).equals(e.jarHash) && e.decision), "Explicit individual Allow still persists");
            check(stored.stream().filter(e -> !e.jarHash.equals("keep")).count() == (decline ? 2 : 1), "Only explicitly chosen individual hashes are stored");
        }
        if (mode.startsWith("console") && !decline) {
            String text = transcript.toString(StandardCharsets.UTF_8);
            check(text.contains("Trust Author ") && text.contains("Save to Disk?"), "Console asks author then persistence");
            check(!text.contains("Allow this Java mod to load?"), "Accepted author skips every individual prompt in the batch");
            check(text.indexOf("Trust Author ") == text.lastIndexOf("Trust Author "), "Following same-author mod requires no repeated trust question");
        }
        if (!cancel && !decline) {
            set("g_approvalFrontend", (ModApprovalFrontend) rows -> { throw new AssertionError("Trusted author was prompted again during the same process"); });
            Loader.loadMods(new ArrayList<>(List.of(IDS.get(2))));
            check(active(root, IDS.get(2)), "Later batch/new signed mod consumes author trust for this session");
            if (!saved) check(Arrays.equals(config, Files.readAllBytes(root.resolve("config/config.json"))), "Later session load still cannot persist preload authority");
        }
        if (mode.equals("console-saved") || mode.equals("console-session")) restart(root, saved);
    }

    private static void restart(Path root, boolean saved) throws Exception {
        String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path log = root.resolve("restart.log");
        Path agent = Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", exe).toString(), "-Xverify:all", "-cp",
            System.getProperty("java.class.path"), "-javaagent:" + agent + "=policy=deny-new,frontend=console,config_dir=" + root.resolve("restart-bootstrap"),
            AuthorTrustSeparationProbe.class.getName(), root.toString(), saved ? "saved" : "session")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            check(child.waitFor(45, java.util.concurrent.TimeUnit.SECONDS), "Fresh trust JVM exits in time");
            check(child.exitValue() == 0, Files.readString(log));
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    private static void afterRestart(Path root, boolean saved) throws Exception {
        configure(root, false);
        int[] prompts = {0};
        set("g_approvalFrontend", (ModApprovalFrontend) rows -> {
            prompts[0]++;
            for (var row : rows) { row.decision = false; row.flags = row.flags.without(MF_PERSIST | MF_PRELOAD | MF_TRUST_AUTHOR); }
            return rows;
        });
        if (saved) {
            Loader.preloadMods();
            check("yes".equals(System.getProperty("trust." + IDS.get(0) + ".premain")), "Author-only saved preload executes actual PreMain on restart");
        }
        Loader.loadMods(new ArrayList<>(IDS));
        check(prompts[0] == (saved ? 0 : 1), "Saved trust bypasses new process prompts; session trust expires");
        check(active(root, IDS.get(2)) == saved, "New process authority matches saved/session lifetime");
        check(ModApprovalsStore.load().mods.size() == 1, "Restart creates no derived hash approvals");
    }

    private static void safety() throws Exception {
        for (String mode : List.of("unsigned", "invalid", "banned", "missing-signature", "missing-flags", "missing-hash")) {
            var signature = mode.equals("unsigned") ? JarBatchApprovalProtocol.Entry.ZBSignature.none()
                : new JarBatchApprovalProtocol.Entry.ZBSignature(!mode.equals("invalid"), AUTHOR, mode.equals("invalid") ? "invalid" : "fixture");
            var entry = ApprovalDialogProbe.entry("safety-" + mode, false, MF_PERSIST | MF_PRELOAD, signature,
                mode.equals("banned") ? new JarBatchApprovalProtocol.Entry.SteamBan("fixture") : null);
            AuthorTrustChoices.mark(entry, true, true, true);
            if (mode.equals("missing-signature")) field(entry.getClass(), "zbs").set(entry, null);
            if (mode.equals("missing-flags")) entry.flags = null;
            if (mode.equals("missing-hash")) field(entry.getClass(), "sha256").set(entry, "");
            int[] mutations = {0};
            AuthorTrustChoices.applyAuthors(List.of(entry), author -> mutations[0]++, author -> mutations[0]++);
            var table = new JarDecisionTable();
            AuthorTrustChoices.authorize(List.of(entry), table, new Config(), row -> mutations[0]++);
            check(mutations[0] == 0 && table.get(entry.sha256) == null, "Malformed/unsigned/blocked author intent cannot mutate trust or authorize: " + mode);
        }
        for (String mode : List.of("eof", "unsigned", "invalid", "banned-group")) {
            var signature = new JarBatchApprovalProtocol.Entry.ZBSignature(true, AUTHOR, "fixture author");
            var first = ApprovalDialogProbe.entry("console-safety", null, 0,
                mode.equals("unsigned") ? JarBatchApprovalProtocol.Entry.ZBSignature.none()
                    : mode.equals("invalid") ? new JarBatchApprovalProtocol.Entry.ZBSignature(false, AUTHOR, "invalid") : signature, null);
            List<JarBatchApprovalProtocol.Entry> rows = new ArrayList<>(List.of(first));
            if (mode.equals("banned-group")) rows.add(ApprovalDialogProbe.entry("banned", null, 0, signature,
                new JarBatchApprovalProtocol.Entry.SteamBan("fixture")));
            InputStream oldIn = System.in; PrintStream oldOut = System.out;
            var output = new ByteArrayOutputStream();
            try {
                System.setIn(new ByteArrayInputStream((mode.equals("eof") ? "" : "y\ny\n").getBytes(StandardCharsets.UTF_8)));
                System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
                new ConsoleModApprovalFrontend().approvePendingMods(rows);
            } finally { System.setIn(oldIn); System.setOut(oldOut); }
            String text = output.toString(StandardCharsets.UTF_8);
            if (mode.equals("eof")) check(Boolean.FALSE.equals(first.decision) && !first.flags.has(MF_PERSIST | MF_TRUST_AUTHOR), "Console EOF neither trusts nor saves");
            else check(!text.contains("Trust Author "), "No author prompt for unsigned, invalid or ban-associated author: " + mode);
            if (mode.equals("invalid")) check(!text.contains("Allow this Java mod to load?") && !first.flags.has(MF_PERSIST), "Invalid signatures cannot acquire saved console decisions");
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> rand = Class.forName("zombie.core.random.RandStandard"); rand.getMethod("init").invoke(rand.getField("INSTANCE").get(null));
        Path root = Path.of(args[0]).toAbsolutePath();
        if (args.length > 1 && (args[1].equals("saved") || args[1].equals("session"))) afterRestart(root, args[1].equals("saved"));
        else if (args.length > 1) run(root, args[1]);
        else {
            safety();
            for (String mode : List.of("console-saved", "console-session", "console-deny-author", "ui-saved", "ui-session", "ui-direct", "ui-cancel")) run(root.resolve(mode), mode);
        }
        System.out.println("PASS " + checks + " independent-author assertions; genuine signatures, actual UI/console IPC/loader/save gates and fresh JVMs; no game session");
    }
}
