package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.net.http.*;
import java.io.IOException;
import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontend;
import zombie.gameStates.ChooseGameInfo;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

/** Isolated full Loader.loadMods/save-gate regression; no native UI or game process. */
public final class ApprovalPersistenceProbe {
    private static final String ID = "ApprovalResetFixture";
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void set(String name, Object value) throws Exception { field(Loader.class, name).set(null, value); }
    private static final class NoNetwork extends HttpClient {
        public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        public javax.net.ssl.SSLContext sslContext() { return null; }
        public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
        public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
        public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) throws IOException {
            throw new IOException("Controlled offline persistence fixture; no network performed");
        }
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new AssertionError("Unexpected async HTTP"); }
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new AssertionError("Unexpected async HTTP"); }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void offlineAuthors() throws Exception {
        try {
            // Current upstream supports a response cache; no request is needed for this invalid fixture.
            var put = SteamWorkshop.class.getDeclaredMethod("putCachedBody", String.class, String.class, String.class);
            put.setAccessible(true); put.invoke(null, KnownAuthors.REMOTE_URL, "", "{}");
            var get = SteamWorkshop.class.getDeclaredMethod("getCachedBody", String.class, String.class);
            get.setAccessible(true);
            check("{}".equals(get.invoke(null, KnownAuthors.REMOTE_URL, "")), "Offline author fixture installed");
        } catch (NoSuchMethodException olderLocalBase) {
            // The local 2.3.4-based edition instead has the existing injectable lazy-client map.
            Map clients = (Map)field(Class.forName("me.zed_0xff.zombie_buddy.LazyHttpClients"), "CLIENTS").get(null);
            for (Object role : Class.forName("me.zed_0xff.zombie_buddy.LazyHttpClients$Role").getEnumConstants()) clients.put(role, new NoNetwork());
            check(clients.values().stream().allMatch(v -> v instanceof NoNetwork), "Offline lazy-client fixtures installed");
        }
    }
    @SuppressWarnings("unchecked")
    private static void configure(Path root, boolean force) throws Exception {
        Files.createDirectories(root.resolve("config"));
        Agent.arguments.put("config_dir", root.resolve("config").toString());
        offlineAuthors(); // No workshop ID or signature sidecar exists, so no profile lookup is needed.
        set("g_jarPolicy", "prompt"); set("g_allowUnsignedMods", true);
        set("g_hasDoLoadingText", false); set("g_forceApprovalDialog", force);
        ((JarDecisionTable)field(Loader.class, "g_sessionJarDecisions").get(null)).hashes().clear();
        ((Map<?, ?>)field(Loader.class, "g_jarLoadStatus").get(null)).clear();
        String dir = root.resolve("fixture").toAbsolutePath().toString();
        var mod = new ChooseGameInfo.Mod(dir);
        mod.setId(ID); mod.setName("Approval reset fixture"); mod.commonDir = dir; mod.versionDir = dir;
        field(mod.getClass(), "available").set(mod, true);
        field(mod.getClass(), "availableDone").set(mod, true);
        ((Map<String, ChooseGameInfo.Mod>)field(ChooseGameInfo.class, "Mods").get(null)).put(ID, mod);
    }
    private static String makeFixture(Path root) throws Exception {
        Path dir = root.resolve("fixture"); Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.info"), "name=Approval reset fixture\nid=" + ID
                + "\njavaJarFile=fixture.jar\njavaPkgName=local.approval.reset\n");
        Path jar = dir.resolve("fixture.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fixture.txt")); out.write(root.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return Utils.sha256Hex(jar);
    }
    private static List<JarBatchApprovalProtocol.Entry> response(Path root,
            List<JarBatchApprovalProtocol.Entry> pending, String action) throws Exception {
        check(pending.size() == 1 && ID.equals(pending.get(0).modId), "Actual loader supplied the fixture row");
        Object dialog = ApprovalDialogProbe.dialog(pending);
        if (!action.equals("untouched")) {
            Boolean initial = pending.get(0).decision;
            ApprovalDialogProbe.choose(dialog, 0, initial == null || initial);
            if (initial == null) ApprovalDialogProbe.choose(dialog, 0, true);
        }
        List<JarBatchApprovalProtocol.Entry> result = ApprovalDialogProbe.finish(dialog, !action.equals("save-off"));
        if (action.equals("cancel")) {
            @SuppressWarnings("unchecked") var denied = (List<JarBatchApprovalProtocol.Entry>)ApprovalDialogProbe.call(
                    ApprovalDialogProbe.DIALOG, "denyAll", new Class<?>[]{List.class}, result);
            result = denied;
        }
        if (action.equals("blocked")) {
            var e = result.get(0);
            result = List.of(new JarBatchApprovalProtocol.Entry(e.modId, e.workshopItemId, e.jarAbsolutePath,
                    e.infAbsolutePath, e.sha256, e.date, e.decision, e.flags, e.modDisplayName,
                    new JarBatchApprovalProtocol.Entry.ZBSignature(false, null, "invalid"), null));
        }
        Path path = root.resolve("response.json"); JarBatchApprovalProtocol.writeResponse(path, result);
        return JarBatchApprovalProtocol.readResponse(path);
    }
    private static void runCase(Path root, Boolean initial, boolean keepOther, String action, boolean changed) throws Exception {
        String hash = makeFixture(root); configure(root, true);
        var original = new ArrayList<ModApprovalsStore.ModEntry>();
        if (initial != null) original.add(new ModApprovalsStore.ModEntry(ID, null, hash, initial, "before", null));
        if (keepOther) original.add(new ModApprovalsStore.ModEntry("keep", null, "unrelated-hash", true, "before", null));
        var seed = new ModApprovalsStore.FileData(); seed.mods = original;
        ModApprovalsStore.save(seed); // Fixture setup only. Never manually save after Loader.loadMods().
        Path path = Agent.configDir().resolve("mod_approvals.json"); byte[] before = Files.readAllBytes(path);
        int[] calls = {0};
        set("g_approvalFrontend", (ModApprovalFrontend)pending -> {
            calls[0]++;
            try { return response(root, pending, action); } catch (Exception e) { throw new AssertionError(e); }
        });
        Loader.loadMods(new ArrayList<>(List.of(ID)));
        check(calls[0] == 1, "Actual loader called the frontend exactly once");
        var stored = ModApprovalsStore.load().mods;
        if (changed) {
            check(stored.size() == (keepOther ? 1 : 0), "Actual loader save gate durably removes reset hash");
            check(stored.stream().noneMatch(e -> hash.equals(e.jarHash)), "Reset is absent from disk, not saved as deny");
            check(!Arrays.equals(before, Files.readAllBytes(path)), "Loader itself wrote the changed file");
        } else {
            check(Arrays.equals(before, Files.readAllBytes(path)), "No-op, Save off, Cancel or blocked reset preserves disk bytes");
            check(stored.size() == original.size(), "Unchanged disk records retained");
        }
        check((boolean)field(Loader.class, "g_modApprovalsDirty").get(null) == changed, "Dirty flag matches actual removal");
        if (keepOther) check(stored.stream().anyMatch(e -> "unrelated-hash".equals(e.jarHash) && e.decision), "Unrelated approval retained");
        var session = (JarDecisionTable)field(Loader.class, "g_sessionJarDecisions").get(null);
        check(Boolean.FALSE.equals(session.get(hash)), "Reset or undecided fixture denied for current launch");
        if (changed) {
            String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "java.exe" : "java";
            int exit = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", exe).toString(),
                    "-Xverify:all", "-cp", System.getProperty("java.class.path"), ApprovalPersistenceProbe.class.getName(),
                    root.toString(), "restart").inheritIO().start().waitFor();
            check(exit == 0, "Fresh JVM prompts again after reset without persisting an implicit refusal");
        }
    }
    private static void restart(Path root) throws Exception {
        configure(root, false); byte[] before = Files.readAllBytes(Agent.configDir().resolve("mod_approvals.json"));
        int[] calls = {0};
        set("g_approvalFrontend", (ModApprovalFrontend)pending -> {
            calls[0]++;
            check(pending.size() == 1 && pending.get(0).decision == null && !pending.get(0).flags.has(MF_PERSIST),
                    "Next launch asks again with undecided unsaved row");
            try { return response(root, pending, "untouched"); } catch (Exception e) { throw new AssertionError(e); }
        });
        Loader.loadMods(new ArrayList<>(List.of(ID)));
        check(calls[0] == 1, "Fresh process uses real loader prompt path");
        check(!(boolean)field(Loader.class, "g_modApprovalsDirty").get(null), "Untouched next-launch row stays nonpersistent");
        check(Arrays.equals(before, Files.readAllBytes(Agent.configDir().resolve("mod_approvals.json"))), "Next launch preserves cleared disk state");
    }
    public static void main(String[] args) throws Exception {
        Class<?> rand = Class.forName("zombie.core.random.RandStandard");
        rand.getMethod("init").invoke(rand.getField("INSTANCE").get(null));
        Path root = Path.of(args[0]).toAbsolutePath();
        if (args.length > 1 && args[1].equals("restart")) restart(root);
        else {
            runCase(root.resolve("last-allow"), true, false, "reset", true);
            runCase(root.resolve("last-deny"), false, false, "reset", true);
            runCase(root.resolve("keep-unrelated"), true, true, "reset", true);
            runCase(root.resolve("save-off"), true, false, "save-off", false);
            runCase(root.resolve("cancel"), false, false, "cancel", false);
            runCase(root.resolve("absent-hash"), null, false, "reset", false);
            runCase(root.resolve("blocked"), true, false, "blocked", false);
            runCase(root.resolve("untouched"), null, false, "untouched", false);
        }
        System.out.println("PASS " + checks + " actual loader persistence assertions" + (args.length > 1 ? " on restart" : "; real save gate, no manual post-reset save"));
    }
}
