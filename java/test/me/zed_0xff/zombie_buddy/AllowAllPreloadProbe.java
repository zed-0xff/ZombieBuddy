package me.zed_0xff.zombie_buddy;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontend;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

/** Actual allow-all loader/preload paths in client and dedicated roles; controlled Steam responses. */
public final class AllowAllPreloadProbe {
    private static final String ID = "AllowAllFixture";
    private static final long WORKSHOP = 123456791L;
    private static int checks;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static Path directory(Path root) { return root.resolve("content/108600/" + WORKSHOP + "/mods/" + ID + "/42"); }
    private static Path jar(Path root) { return directory(root).resolve("fixture.jar"); }
    private static String marker(Path root) { return "allowall." + root.getFileName() + ".premain"; }
    private static String response(boolean banned) {
        return "{\"response\":{\"publishedfiledetails\":[{\"publishedfileid\":\"" + WORKSHOP
                + "\",\"banned\":" + (banned ? 1 : 0) + ",\"creator\":\"76561198000000039\",\"ban_reason\":\"controlled ban\"}]}}";
    }

    private static final class FixtureClient extends HttpClient {
        private final boolean banned;
        FixtureClient(boolean banned) { this.banned = banned; }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public javax.net.ssl.SSLContext sslContext() { return null; }
        public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            String body = request.uri().getHost().equals("api.steampowered.com") ? response(banned) : "{}";
            return (HttpResponse<T>) new HttpResponse<String>() {
                public int statusCode() { return 200; }
                public HttpRequest request() { return request; }
                public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
                public String body() { return body; }
                public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
                public URI uri() { return request.uri(); }
                public Version version() { return Version.HTTP_1_1; }
            };
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new AssertionError("No async network"); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new AssertionError("No async network"); }
    }

    @SuppressWarnings({"rawtypes", "unchecked"}) private static void offline(boolean banned) throws Exception {
        try {
            var cache = SteamWorkshop.class.getDeclaredMethod("putCachedBody", String.class, String.class, String.class);
            cache.setAccessible(true);
            cache.invoke(null, "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/",
                    "itemcount=1&publishedfileids[0]=" + WORKSHOP, response(banned));
            cache.invoke(null, KnownAuthors.REMOTE_URL, "", "{}");
        } catch (NoSuchMethodException localEdition) {
            Map clients = (Map) field(Class.forName("me.zed_0xff.zombie_buddy.LazyHttpClients"), "CLIENTS").get(null);
            for (Object role : Class.forName("me.zed_0xff.zombie_buddy.LazyHttpClients$Role").getEnumConstants())
                clients.put(role, new FixtureClient(banned));
        }
    }

    private static void fixture(Path root, String mode) throws Exception {
        Path dir = Files.createDirectories(directory(root));
        String pkg = "local.allowall." + mode.replace('-', '_');
        Files.writeString(dir.resolve("mod.info"), "name=" + ID + "\nid=" + ID + "\njavaJarFile=fixture.jar\njavaPkgName="
                + pkg + "\njavaPreload=" + !mode.equals("no-declaration") + "\n");
        Path source = root.resolve("PreMain.java"), classes = Files.createDirectories(root.resolve("classes"));
        Files.writeString(source, "package " + pkg + "; public class PreMain { public static void premain(String[] args) { System.setProperty(\""
                + marker(root) + "\", \"yes\"); } }");
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-d", classes.toString(), source.toString()) == 0,
                "Fixture compiles");
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (!mode.equals("no-manifest")) manifest.getMainAttributes().putValue("ZB-Preload", "true");
        try (var out = new JarOutputStream(Files.newOutputStream(jar(root)), manifest); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));
                out.write(Files.readAllBytes(file)); out.closeEntry();
            }
        }
        if (mode.equals("invalid-signature")) Files.writeString(Path.of(jar(root) + ".zbs"), "invalid sidecar fixture\n");
        if (mode.equals("signed")) {
            var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Signature signer = Signature.getInstance("Ed25519"); signer.initSign(key.getPrivate());
            signer.update(("ZBS:76561198000000039:" + Utils.sha256Hex(jar(root))).getBytes(StandardCharsets.UTF_8));
            Files.writeString(Path.of(jar(root) + ".zbs"), "ZBS\nSteamID64:76561198000000039\nSignature:"
                    + HexFormat.of().formatHex(signer.sign()) + "\n");
        }
    }

    @SuppressWarnings("unchecked") private static void configure(Path root, boolean banned) throws Exception {
        Agent.arguments.put("config_dir", root.resolve("config").toString()); offline(banned); Loader.initConfig();
        check("allow-all".equals(Loader.getPolicy()), "Policy originates from real agent startup");
        field(Loader.class, "g_allowUnsignedMods").set(null, false);
        field(Loader.class, "g_forceApprovalDialog").set(null, false);
        field(Loader.class, "g_hasDoLoadingText").set(null, false);
        ((JarDecisionTable) field(Loader.class, "g_sessionJarDecisions").get(null)).hashes().clear();
        ((Map<?,?>) field(Loader.class, "g_jarLoadStatus").get(null)).clear();
        field(Loader.class, "g_approvalFrontend").set(null, (ModApprovalFrontend) rows -> { throw new AssertionError("allow-all must never prompt"); });
        String dir = directory(root).toString();
        var mod = new zombie.gameStates.ChooseGameInfo.Mod(dir); mod.setId(ID); mod.setName(ID); mod.commonDir = dir; mod.versionDir = dir;
        field(mod.getClass(), "available").set(mod, true); field(mod.getClass(), "availableDone").set(mod, true);
        ((Map<String, Object>) field(zombie.gameStates.ChooseGameInfo.class, "Mods").get(null)).put(ID, mod);
    }

    private static void preload() throws Exception {
        Method method = Loader.class.getDeclaredMethod("preloadMods"); method.setAccessible(true); method.invoke(null);
    }

    private static void restart(Path root, boolean server) throws Exception {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        Path log = root.resolve("restart.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-Xverify:all",
                "-javaagent:" + Path.of(Loader.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    + "=policy=allow-all,frontend=console,config_dir=" + root.resolve("bootstrap"),
                "-cp", System.getProperty("java.class.path"), AllowAllPreloadProbe.class.getName(), root.toString(),
                server ? "server" : "client", "restart").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            check(child.waitFor(40, TimeUnit.SECONDS), "Preload restart finishes");
            check(child.exitValue() == 0, "Fresh JVM executes real PreMain: " + Files.readString(log));
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    @SuppressWarnings("unchecked") private static void run(Path root, String mode, boolean server) throws Exception {
        Files.createDirectories(root); fixture(root, mode); Files.createDirectories(root.resolve("config"));
        Agent.arguments.put("config_dir", root.resolve("config").toString());
        var stored = new ModApprovalsStore.FileData();
        stored.mods.add(new ModApprovalsStore.ModEntry("unrelated", null, "keep", true, "before", null));
        if (mode.equals("saved-deny")) stored.mods.add(new ModApprovalsStore.ModEntry(ID, null, Utils.sha256Hex(jar(root)), false, "before", null));
        ModApprovalsStore.save(stored);
        Config seed = new Config();
        if (mode.equals("banned-preload")) seed = seed.withPreloadMod(ID, new Config.PreloadMod(directory(root).resolve("mod.info"), jar(root)));
        Config.save(seed);
        boolean banned = mode.startsWith("banned"); configure(root, banned);
        byte[] approvals = Files.readAllBytes(root.resolve("config/mod_approvals.json"));
        if (mode.equals("banned-preload")) {
            preload();
            check(System.getProperty(marker(root)) == null, "Steam-banned PreMain never executes under allow-all");
            check(!Config.load().preload_mods().containsKey(ID), "Banned early preload is removed");
        }
        Loader.loadMods(new ArrayList<>(List.of(ID)));
        var states = (Map<Path,Loader.JavaModLoadState>) field(Loader.class,"g_jarLoadStatus").get(null);
        var state = states.get(jar(root).toRealPath());
        boolean allowed = !banned && !mode.equals("saved-deny");
        check(state != null && state.flags().has(MF_ACTIVE) == allowed, "Normal load respects Steam bans and existing explicit Deny");
        check(state.flags().has(MF_BANNED) == banned, "Ban is represented in load state");
        boolean registered = allowed && !mode.equals("no-manifest") && !mode.equals("no-declaration");
        check(Config.load().preload_mods().containsKey(ID) == registered, "allow-all registers eligible non-banned preloads: " + mode);
        check(Config.load().trusted_authors().isEmpty(), "allow-all creates no author trust");
        var after = ModApprovalsStore.load().mods;
        check(after.stream().anyMatch(e -> e.jarHash.equals("keep") && e.decision), "Unrelated explicit hash approval remains");
        if (!allowed) check(Arrays.equals(approvals, Files.readAllBytes(root.resolve("config/mod_approvals.json"))), "Blocked mods preserve saved hash records byte-for-byte");
        else check(after.stream().anyMatch(e -> e.jarHash.equals(Utils.sha256Hex(jar(root))) && e.decision), "Original allow-all hash persistence is retained");
        if (registered) {
            byte[] once = Files.readAllBytes(root.resolve("config/config.json"));
            Loader.loadMods(new ArrayList<>(List.of(ID)));
            check(Arrays.equals(once, Files.readAllBytes(root.resolve("config/config.json"))), "Existing registration is stable");
            check(System.getProperty(marker(root)) == null, "Early entry requires full restart");
            restart(root, server);
        }
    }

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        boolean server = args[1].equals("server"); zombie.network.GameServer.server = server;
        check(zombie.Lua.LuaManager.GlobalObject.isServer() == server, "Actual game API matches requested process role");
        Path root = Path.of(args[0]).toAbsolutePath();
        if (args.length > 2 && args[2].equals("restart")) {
            configure(root, false); preload();
            check("yes".equals(System.getProperty(marker(root))), "Actual preload path executes unsigned/invalid/unapproved PreMain under allow-all");
            check(Config.load().trusted_authors().isEmpty(), "Restart needs no author trust");
        } else if (args.length > 2) run(root.resolve(args[2]), args[2], server);
        else for (String mode : List.of("unsigned", "signed", "invalid-signature", "saved-deny", "no-manifest", "no-declaration", "banned-normal", "banned-preload"))
            run(root.resolve(mode), mode, server);
        System.out.println("PASS " + checks + " allow-all preload assertions; role=" + (server ? "dedicated" : "client") + "; actual loader and fresh JVMs; no game session");
    }
}
