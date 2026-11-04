package me.zed_0xff.zombie_buddy;

import java.io.*;
import java.lang.reflect.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontend;
import me.zed_0xff.zombie_buddy.Loader.JavaModLoadState;
import zombie.gameStates.ChooseGameInfo;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

/** Actual packaged-agent JVMs: read-only child, client update, and writable dedicated control. */
public final class CoopReadOnlyProbe {
    private static final SteamWorkshop.SteamID64 A = new SteamWorkshop.SteamID64(76561198000000010L);
    private static final SteamWorkshop.SteamID64 B = new SteamWorkshop.SteamID64(76561198000000011L);
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void set(String name, Object value) throws Exception { field(Loader.class, name).set(null, value); }
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = Loader.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(null, args);
    }
    private static String json(Object value) throws Exception { Object gson = field(ZBGson.class, "PRETTY").get(null); return (String)gson.getClass().getMethod("toJson", Object.class).invoke(gson, value); }
    private static Config config() throws Exception { return (Config)field(Loader.class, "g_config").get(null); }
    private static Path file(Path root, String name) { return root.resolve("config").resolve(name); }
    private static void await(Path signal) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(signal)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Barrier timeout: " + signal);
            Thread.sleep(20);
        }
    }
    private static String fixture(Path root, String id) throws Exception {
        Path dir = Files.createDirectories(root.resolve(id));
        Files.writeString(dir.resolve("mod.info"), "name=" + id + "\nid=" + id
                + "\njavaJarFile=fixture.jar\njavaPkgName=local.coop.fixture\n");
        Path jar = dir.resolve("fixture.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fixture.txt")); out.write(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
        }
        return Utils.sha256Hex(jar);
    }
    @SuppressWarnings("unchecked") private static void register(Path root, String id) throws Exception {
        String dir = root.resolve(id).toAbsolutePath().toString();
        var mod = new ChooseGameInfo.Mod(dir); mod.setId(id); mod.setName(id); mod.commonDir = dir; mod.versionDir = dir;
        field(mod.getClass(), "available").set(mod, true); field(mod.getClass(), "availableDone").set(mod, true);
        ((Map<String, ChooseGameInfo.Mod>)field(ChooseGameInfo.class, "Mods").get(null)).put(id, mod);
    }
    private static void writeClientState(Path root, boolean updated) throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.writeString(file(root, "config.json"), json(new Config().withTrustedAuthor(updated ? B : A)));
        var data = new ModApprovalsStore.FileData();
        data.mods.add(new ModApprovalsStore.ModEntry("CoopAllowed", null, Utils.sha256Hex(root.resolve("CoopAllowed/fixture.jar")), true, null, null));
        data.mods.add(new ModApprovalsStore.ModEntry("CoopDenied", null, Utils.sha256Hex(root.resolve("CoopDenied/fixture.jar")), false, null, null));
        if (updated) data.mods.add(new ModApprovalsStore.ModEntry("CoopUnknown", null,
                Utils.sha256Hex(root.resolve("CoopUnknown/fixture.jar")), true, null, null));
        Files.writeString(file(root, "mod_approvals.json"), json(data));
        Files.writeString(file(root, "java_mod_approvals.txt"), "client-owned legacy sentinel");
    }
    private static boolean active(String id) throws Exception {
        @SuppressWarnings("unchecked") Map<Path, JavaModLoadState> states = (Map<Path, JavaModLoadState>)field(Loader.class, "g_jarLoadStatus").get(null);
        return states.values().stream().anyMatch(s -> id.equals(s.id()) && s.flags().has(MF_ACTIVE));
    }
    private static void load(String id) throws Exception {
        ((Map<?, ?>)field(Loader.class, "g_jarLoadStatus").get(null)).clear();
        Loader.loadMods(new ArrayList<>(List.of(id)));
    }
    private static final class NoNetwork extends HttpClient {
        public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        public javax.net.ssl.SSLContext sslContext() { return null; }
        public javax.net.ssl.SSLParameters sslParameters() { return null; }
        public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
        public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) throws IOException { throw new IOException("Offline Coop probe"); }
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new AssertionError(); }
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new AssertionError(); }
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static void offline() throws Exception {
        try {
            Method put = SteamWorkshop.class.getDeclaredMethod("putCachedBody", String.class, String.class, String.class);
            put.setAccessible(true); put.invoke(null, KnownAuthors.REMOTE_URL, "", "{}");
        } catch (NoSuchMethodException stableBase) {
            Map clients = (Map)field(Class.forName("me.zed_0xff.zombie_buddy.LazyHttpClients"), "CLIENTS").get(null);
            for (Object role : Class.forName("me.zed_0xff.zombie_buddy.LazyHttpClients$Role").getEnumConstants()) clients.put(role, new NoNetwork());
        }
    }
    private static void child(Path root, String mode) throws Exception {
        boolean writer = mode.equals("writer");
        check(CoopChild.isReadOnly() != writer, "Correct process role");
        System.setProperty("zb.coop.child", "false"); Agent.arguments.put("coop_child", "false");
        check(CoopChild.isReadOnly() != writer, "Role frozen before mutable options/properties change");
        Loader.initConfig();
        if (writer) {
            Config.save(new Config().withTrustedAuthor(B));
            ModApprovalsStore.save(new ModApprovalsStore.FileData());
            check(Config.load().trustsAuthor(B) && Files.exists(file(root, "mod_approvals.json")), "Dedicated control remains writable");
            return;
        }
        String old = json(config());
        call("storeTrustedAuthor", new Class<?>[]{SteamWorkshop.SteamID64.class}, B);
        call("removeTrustedAuthor", new Class<?>[]{SteamWorkshop.SteamID64.class}, A);
        Class<?> preload = Class.forName(Loader.class.getName() + "$PreloadMods");
        for (String name : List.of("add", "remove")) {
            Method m = name.equals("add") ? preload.getDeclaredMethod(name, String.class, Path.class, Path.class) : preload.getDeclaredMethod(name, String.class);
            m.setAccessible(true);
            m.invoke(null, name.equals("add") ? new Object[]{"child", Path.of("child.info"), Path.of("child.jar")} : new Object[]{"child"});
        }
        Loader.setAutoFixModOrder(false); Loader.setFixApprovalDialogCursor(false);
        try { Loader.class.getMethod("setFixMacOSRetina", boolean.class).invoke(null, true); } catch (NoSuchMethodException stableBase) {}
        check(old.equals(json(config())), "No child mutation of trust, preloads or options");
        JarDecisionTable injected = new JarDecisionTable();
        var row = new JarBatchApprovalProtocol.Entry("child", null, root.resolve("CoopUnknown/fixture.jar"),
                root.resolve("CoopUnknown/mod.info"), "injected-hash", null, true,
                new ModFlags(MF_PERSIST).with(MF_TRUST_AUTHOR).with(MF_PRELOAD), "child",
                new JarBatchApprovalProtocol.Entry.ZBSignature(true, B, "fixture"), null);
        Loader.applyBatchApprovalLines(List.of(row), injected);
        check(injected.get("injected-hash") == null && old.equals(json(config())), "Child cannot apply independent UI decisions");
        call("storeDecision", new Class<?>[]{String.class, boolean.class, String.class, SteamWorkshop.WorkshopItemID.class, SteamWorkshop.SteamID64.class}, "child-hash", true, "child", null, B);
        check(!(boolean)field(Loader.class, "g_modApprovalsDirty").get(null), "Child cannot queue saved decisions");
        Config.save(new Config().withTrustedAuthor(B));
        ModApprovalsStore.save(new ModApprovalsStore.FileData());
        ModApprovalsStore.load();
        check(mode.equals("absent") ? !Files.exists(file(root, "config.json")) : Config.load().trustsAuthor(A), "Direct Config.save cannot create or overwrite client state");
        check(mode.equals("absent") ? !Files.exists(file(root, "mod_approvals.json")) : ModApprovalsStore.load().mods.size() == 2, "Direct approval save cannot create or overwrite client state");
        if (!mode.equals("absent")) check(Files.exists(file(root, "java_mod_approvals.txt")), "Legacy client file is not deleted");
        offline();
        Class<?> rand = Class.forName("zombie.core.random.RandStandard"); rand.getMethod("init").invoke(rand.getField("INSTANCE").get(null));
        set("g_hasDoLoadingText", false);
        set("g_approvalFrontend", (ModApprovalFrontend)pending -> { throw new AssertionError("Child opened approval UI"); });
        for (String id : List.of("CoopAllowed", "CoopDenied", "CoopUnknown")) register(root, id);
        set("g_jarPolicy", "prompt");
        load("CoopUnknown"); check(!active("CoopUnknown"), "Unknown prompt-policy mod is denied without UI");
        if (!mode.equals("absent")) {
            load("CoopAllowed"); check(active("CoopAllowed"), "Saved client allow is consumed");
            load("CoopDenied"); check(!active("CoopDenied"), "Saved client denial is consumed");
        }
        set("g_jarPolicy", "allow-all");
        load("CoopUnknown"); check(active("CoopUnknown"), "Explicit client allow-all policy remains session-only");
        if (!mode.equals("absent")) { load("CoopDenied"); check(!active("CoopDenied"), "Client denial still wins over allow-all"); }
        if (mode.equals("seeded")) {
            Files.writeString(root.resolve("ready"), "ready"); await(root.resolve("go"));
            set("g_jarPolicy", "prompt"); load("CoopUnknown");
            check(active("CoopUnknown") && config().trustsAuthor(B) && !config().trustsAuthor(A), "Next load reads newer client approvals and trust");
            Config.save(new Config()); ModApprovalsStore.save(new ModApprovalsStore.FileData());
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(root);
        if (args.length > 1) { child(root, args[1]); System.out.println("PASS " + checks + " Coop ownership assertions: " + args[1]); return; }
        Path agent = Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (String mode : List.of("seeded", "absent", "writer")) {
            Path dir = Files.createDirectories(root.resolve(mode));
            for (String id : List.of("CoopAllowed", "CoopDenied", "CoopUnknown")) fixture(dir, id);
            if (mode.equals("seeded")) writeClientState(dir, false);
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xverify:all", "-Djava.awt.headless=true", "-Dserver=true", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"));
            if (!mode.equals("writer")) command.add("-Dzb.coop.child=true");
            command.addAll(List.of("-javaagent:" + agent + "=policy=prompt,frontend=swing,config_dir=" + dir.resolve("config"),
                    "-cp", agent + java.io.File.pathSeparator + System.getProperty("java.class.path"), CoopReadOnlyProbe.class.getName(), dir.toString(), mode));
            Path log = dir.resolve("child.log");
            Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            Map<Path, byte[]> expected = new LinkedHashMap<>();
            try {
                if (mode.equals("seeded")) {
                    await(dir.resolve("ready")); writeClientState(dir, true);
                    for (String name : List.of("config.json", "mod_approvals.json", "java_mod_approvals.txt")) expected.put(file(dir, name), Files.readAllBytes(file(dir, name)));
                    Files.writeString(dir.resolve("go"), "go");
                }
                check(p.waitFor(30, TimeUnit.SECONDS), "Child exited in time");
                check(p.exitValue() == 0, Files.readString(log));
                Files.readAllLines(log).stream().filter(s -> s.startsWith("PASS ")).forEach(System.out::println);
                for (var e : expected.entrySet()) check(Arrays.equals(e.getValue(), Files.readAllBytes(e.getKey())), "Newer client file preserved byte-for-byte: " + e.getKey());
                if (mode.equals("absent")) for (String name : List.of("config.json", "mod_approvals.json")) check(!Files.exists(file(dir, name)), "Child created no decision store");
            } finally { if (p.isAlive()) p.destroyForcibly(); }
        }
        System.out.println("PASS " + checks + " parent ownership assertions; real agent JVMs, no game/server started");
    }
}
