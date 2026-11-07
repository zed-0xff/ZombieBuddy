package me.zed_0xff.zombie_buddy;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontend;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

/** Real dialog/protocol/loader save gate. Signature metadata is a controlled UI fixture. */
public final class AuthorUntrustProbe {
    private static final SteamWorkshop.SteamID64 AUTHOR = new SteamWorkshop.SteamID64(76561198000000010L);
    private static final SteamWorkshop.SteamID64 OTHER = new SteamWorkshop.SteamID64(76561198000000011L);
    private static int checks;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static Field field(String name) throws Exception { Field f = Loader.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static JarBatchApprovalProtocol.Entry signed(String id, Boolean decision) {
        return ApprovalDialogProbe.entry(id, decision, MF_TRUST_AUTHOR | (decision == null ? 0 : MF_PERSIST),
                new JarBatchApprovalProtocol.Entry.ZBSignature(true, AUTHOR, "verified UI fixture"), null);
    }
    @SuppressWarnings("unchecked") public static void stateTests(Path root) throws Exception {
        Files.createDirectories(root);
        Map<String, Object> previous = new LinkedHashMap<>();
        for (String name : List.of("g_config", "g_configDirty", "g_storedEntries", "g_modApprovalsDirty")) previous.put(name, field(name).get(null));
        try {
            int sequence = 0;
            for (Boolean initial : new Boolean[]{null, false, true}) {
                for (String action : List.of("save", "save-off", "cancel", "deny", "reset")) {
                    String id = "untrust-" + sequence++;
                    var entry = signed(id, initial);
                    entry.flags = entry.flags.with(MF_PRELOAD);
                    var stored = new ArrayList<ModApprovalsStore.ModEntry>();
                    var unrelated = new ModApprovalsStore.ModEntry("keep", null, "keep-hash", true, "before", OTHER);
                    stored.add(unrelated);
                    if (initial != null) stored.add(new ModApprovalsStore.ModEntry(id, null, entry.sha256, initial, "before", AUTHOR));
                    var before = new ArrayList<>(stored);
                    field("g_storedEntries").set(null, stored); field("g_modApprovalsDirty").set(null, false);
                    field("g_config").set(null, new Config().withTrustedAuthor(AUTHOR).withTrustedAuthor(OTHER)
                            .withPreloadMod(id, new Config.PreloadMod(entry.infAbsolutePath, entry.jarAbsolutePath)));
                    field("g_configDirty").set(null, false);
                    Object dialog = ApprovalDialogProbe.dialog(List.of(entry));
                    ApprovalDialogProbe.trust(dialog, 0, false);
                    if (action.equals("deny")) {
                        if (Boolean.FALSE.equals(initial)) ApprovalDialogProbe.choose(dialog, 0, true);
                        ApprovalDialogProbe.choose(dialog, 0, false);
                    } else if (action.equals("reset")) {
                        boolean allow = initial == null || initial;
                        if (initial == null) ApprovalDialogProbe.choose(dialog, 0, allow);
                        ApprovalDialogProbe.choose(dialog, 0, allow);
                    }
                    var lines = ApprovalDialogProbe.finish(dialog, !action.equals("save-off"));
                    if (action.equals("cancel")) lines = (List<JarBatchApprovalProtocol.Entry>)ApprovalDialogProbe.call(
                            ApprovalDialogProbe.DIALOG, "denyAll", new Class<?>[]{List.class}, lines);
                    Path response = root.resolve("response-" + id + ".json");
                    JarBatchApprovalProtocol.writeResponse(response, lines);
                    var received = JarBatchApprovalProtocol.readResponse(response);
                    Loader.applyBatchApprovalLines(received, new JarDecisionTable());
                    boolean save = !action.equals("save-off") && !action.equals("cancel");
                    var config = (Config)field("g_config").get(null);
                    check(config.trustsAuthor(AUTHOR) != save && config.trustsAuthor(OTHER), "Only saved explicit untrust changes the selected author: " + action);
                    check(config.preload_mods().containsKey(id) == !(action.equals("deny") || action.equals("reset")),
                            "Only an explicit saved Deny/reset changes preloads: " + action);
                    check((boolean)field("g_configDirty").get(null) == save, "Save-off/cancel leave config clean");
                    check(stored.contains(unrelated), "Unrelated saved decision preserved");
                    if (action.equals("deny")) {
                        check(stored.stream().anyMatch(e -> entry.sha256.equals(e.jarHash) && !e.decision), "Explicit Deny still persists");
                    } else if (action.equals("reset")) {
                        check(stored.stream().noneMatch(e -> entry.sha256.equals(e.jarHash)), "Explicit reset still removes the selected hash");
                    } else {
                        check(stored.equals(before), "Untrust alone must neither save nor clear an untouched hash: " + initial + "/" + action);
                        check(!(boolean)field("g_modApprovalsDirty").get(null), "Untrust-only keeps the hash store clean");
                    }
                    check(received.stream().noneMatch(e -> e.decision == null && e.flags.has(MF_PERSIST)), "No reset marker reaches ordinary persistence");
                    field("g_configDirty").set(null, false);
                }
            }
            Field bit = ApprovalChoices.class.getDeclaredField("UNTRUST_ONLY"); bit.setAccessible(true);
            int marker = bit.getInt(null);
            check((marker & (MF_VALID | MF_SIGNED | MF_ACTIVE | MF_PERSIST | MF_BANNED | MF_PRELOAD | MF_TRUST_AUTHOR)) == 0,
                    "Transient trust marker does not overlap existing flags");
            check(new ModFlags(marker).getSticky().value() == 0, "Transient marker is never sticky");
            for (String state : List.of("valid", "cancel", "unsigned", "invalid", "banned", "missing")) {
                var signature = state.equals("unsigned") ? new JarBatchApprovalProtocol.Entry.ZBSignature(false, null, null)
                        : state.equals("invalid") ? new JarBatchApprovalProtocol.Entry.ZBSignature(false, AUTHOR, "invalid")
                        : new JarBatchApprovalProtocol.Entry.ZBSignature(true, AUTHOR, "fixture");
                var entry = ApprovalDialogProbe.entry("marker-" + state, null,
                        marker | MF_PRELOAD | MF_TRUST_AUTHOR | (state.equals("cancel") ? 0 : MF_PERSIST), signature,
                        state.equals("banned") ? new JarBatchApprovalProtocol.Entry.SteamBan("fixture") : null);
                if (state.equals("missing")) { Field zbs = entry.getClass().getDeclaredField("zbs"); zbs.setAccessible(true); zbs.set(entry, null); }
                var old = new ModApprovalsStore.ModEntry("keep", null, entry.sha256, true, "before", AUTHOR);
                var stored = new ArrayList<>(List.of(old)); int[] revoked = {0};
                check(!ApprovalChoices.applyClears(List.of(entry), stored, id -> revoked[0]++), "Trust marker does not dirty the hash store");
                check(stored.equals(List.of(old)) && revoked[0] == (state.equals("valid") ? 1 : 0), "Trust marker safety: " + state);
                check(!entry.flags.has(marker | MF_PERSIST | MF_TRUST_AUTHOR | MF_PRELOAD), "All transient persistence/preload bits consumed");
            }
        } finally {
            for (var e : previous.entrySet()) field(e.getKey()).set(null, e.getValue());
        }
    }
    private static Object fixtureCall(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = ApprovalPersistenceProbe.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(null, args);
    }
    private static void configure(Path root, boolean force) throws Exception {
        fixtureCall("configure", new Class<?>[]{Path.class, boolean.class}, root, force);
        Loader.initConfig();
    }
    private static void restart(Path root) throws Exception {
        configure(root, false);
        Path approvals = Agent.configDir().resolve("mod_approvals.json"); byte[] before = Files.readAllBytes(approvals);
        check(!Config.load().trustsAuthor(AUTHOR) && Config.load().trustsAuthor(OTHER), "Untrust retained after JVM restart");
        int[] calls = {0};
        field("g_approvalFrontend").set(null, (ModApprovalFrontend)pending -> {
            calls[0]++;
            check(pending.size() == 1 && pending.get(0).decision == null && !pending.get(0).flags.has(MF_PERSIST), "New hash prompts again after saved untrust");
            try { return ApprovalDialogProbe.finish(ApprovalDialogProbe.dialog(pending), true); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        Loader.loadMods(new ArrayList<>(List.of("ApprovalResetFixture")));
        check(calls[0] == 1 && Arrays.equals(before, Files.readAllBytes(approvals)), "Restart prompt does not persist an implicit refusal");
    }
    private static void gate(Path root, boolean keepOther) throws Exception {
        fixtureCall("makeFixture", new Class<?>[]{Path.class}, root);
        Files.createDirectories(root.resolve("config")); Agent.arguments.put("config_dir", root.resolve("config").toString());
        Config.save(new Config().withTrustedAuthor(AUTHOR).withTrustedAuthor(OTHER));
        var data = new ModApprovalsStore.FileData();
        if (keepOther) data.mods.add(new ModApprovalsStore.ModEntry("keep", null, "keep-hash", true, "before", OTHER));
        ModApprovalsStore.save(data);
        byte[] before = Files.readAllBytes(Agent.configDir().resolve("mod_approvals.json"));
        configure(root, true); int[] calls = {0};
        field("g_approvalFrontend").set(null, (ModApprovalFrontend)pending -> {
            calls[0]++;
            var e = pending.get(0);
            var input = new JarBatchApprovalProtocol.Entry(e.modId, e.workshopItemId, e.jarAbsolutePath, e.infAbsolutePath,
                    e.sha256, e.date, null, e.flags.with(MF_TRUST_AUTHOR), e.modDisplayName,
                    new JarBatchApprovalProtocol.Entry.ZBSignature(true, AUTHOR, "controlled signed UI fixture"), e.steamBan);
            try {
                Object dialog = ApprovalDialogProbe.dialog(List.of(input)); ApprovalDialogProbe.trust(dialog, 0, false);
                Path response = root.resolve("untrust-response.json");
                JarBatchApprovalProtocol.writeResponse(response, ApprovalDialogProbe.finish(dialog, true));
                return JarBatchApprovalProtocol.readResponse(response);
            } catch (Exception ex) { throw new AssertionError(ex); }
        });
        Loader.loadMods(new ArrayList<>(List.of("ApprovalResetFixture")));
        check(calls[0] == 1, "Actual loader forced-dialog path used");
        check(!Config.load().trustsAuthor(AUTHOR) && Config.load().trustsAuthor(OTHER), "Actual loader saves only the author revocation");
        check(Arrays.equals(before, Files.readAllBytes(Agent.configDir().resolve("mod_approvals.json"))), "Actual save gate preserves approval bytes");
        check(!(boolean)field("g_modApprovalsDirty").get(null), "Actual save gate has no hash mutation");
        Path log = root.resolve("restart.log");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xverify:all", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                AuthorUntrustProbe.class.getName(), root.toString(), "restart").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            check(p.waitFor(25, TimeUnit.SECONDS), "Restart finished in time");
            check(p.exitValue() == 0, Files.readString(log));
            Files.readAllLines(log).stream().filter(s -> s.startsWith("PASS ")).forEach(System.out::println);
        } finally { if (p.isAlive()) p.destroyForcibly(); }
    }
    public static void main(String[] args) throws Exception {
        Class<?> rand = Class.forName("zombie.core.random.RandStandard"); rand.getMethod("init").invoke(rand.getField("INSTANCE").get(null));
        Path root = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(root);
        if (args.length > 1) restart(root);
        else { stateTests(root.resolve("states")); gate(root.resolve("keep-other"), true); gate(root.resolve("empty-store"), false); }
        System.out.println("PASS " + checks + " author-untrust assertions; actual dialog/protocol/loader gate, synthetic signature metadata, no game/UI window");
    }
}
