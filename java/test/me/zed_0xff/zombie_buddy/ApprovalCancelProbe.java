package me.zed_0xff.zombie_buddy;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontend;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

/** Cancel through the packaged dialog, JSON IPC and actual loader save gate. No game process. */
public final class ApprovalCancelProbe {
    private static final SteamWorkshop.SteamID64 AUTHOR = new SteamWorkshop.SteamID64(76561198000000010L);
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static Field field(String name) throws Exception {
        Field f = Loader.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object persistence(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ApprovalPersistenceProbe.class.getDeclaredMethod(name, types); m.setAccessible(true);
        return m.invoke(null, args);
    }
    @SuppressWarnings("unchecked")
    private static List<JarBatchApprovalProtocol.Entry> cancel(Object dialog, boolean save, Path response) throws Exception {
        // Exercise cancellation even after a reset/untrust response has already been built.
        var entries = ApprovalDialogProbe.finish(dialog, save);
        entries = (List<JarBatchApprovalProtocol.Entry>)ApprovalDialogProbe.call(
                ApprovalDialogProbe.DIALOG, "denyAll", new Class<?>[]{List.class}, entries);
        check(entries.stream().noneMatch(e -> e.flags.has(MF_PERSIST | MF_TRUST_AUTHOR)),
                "Cancel cannot carry persistence or author edits");
        JarBatchApprovalProtocol.writeResponse(response, entries);
        return JarBatchApprovalProtocol.readResponse(response);
    }
    static void matrix(Path root, boolean save) throws Exception {
        ApprovalChoices.beginLoad(); // Direct response-path matrix, outside loadMods.
        Files.createDirectories(root);
        Agent.arguments.put("config_dir", root.toString());
        var signed = new JarBatchApprovalProtocol.Entry.ZBSignature(true, AUTHOR, "controlled verified UI fixture");
        var invalid = new JarBatchApprovalProtocol.Entry.ZBSignature(false, AUTHOR, "controlled invalid fixture");
        var rows = new ArrayList<JarBatchApprovalProtocol.Entry>();
        rows.add(ApprovalDialogProbe.entry("allowed", true, MF_PERSIST, null, null));
        rows.add(ApprovalDialogProbe.entry("denied", false, MF_PERSIST, null, null));
        rows.add(ApprovalDialogProbe.entry("new", null, 0, null, null));
        rows.add(ApprovalDialogProbe.entry("trusted-new", null, MF_TRUST_AUTHOR, signed, null));
        rows.add(ApprovalDialogProbe.entry("trusted-denied", false, MF_TRUST_AUTHOR | MF_PERSIST, signed, null));
        rows.add(ApprovalDialogProbe.entry("invalid", true, MF_PERSIST, invalid, null));
        rows.add(ApprovalDialogProbe.entry("banned", true, MF_PERSIST | MF_TRUST_AUTHOR, signed,
                new JarBatchApprovalProtocol.Entry.SteamBan("controlled ban")));
        rows.add(ApprovalDialogProbe.entry("preload", true, MF_PERSIST | MF_PRELOAD, signed, null));
        rows.add(ApprovalDialogProbe.entry("revoked", true, MF_PERSIST, null, null));
        rows.add(ApprovalDialogProbe.entry("later-approved", null, 0, null, null));
        rows.add(ApprovalDialogProbe.entry("unsaved-session", true, 0, null, null));
        rows.add(ApprovalDialogProbe.entry("untrusted-new", null, 0,
                new JarBatchApprovalProtocol.Entry.ZBSignature(true, new SteamWorkshop.SteamID64(76561198000000011L), "other"), null));
        Object dialog = ApprovalDialogProbe.dialog(rows);
        ApprovalDialogProbe.choose(dialog, 0, true); // reset a saved Allow
        ApprovalDialogProbe.choose(dialog, 1, true); // switch a saved Deny to Allow
        ApprovalDialogProbe.choose(dialog, 2, true); // allow a new hash without confirming
        ApprovalDialogProbe.trust(dialog, 3, false); // discard author untrust
        ApprovalDialogProbe.trust(dialog, 11, true); // discard newly selected author trust
        var result = cancel(dialog, save, root.resolve("response.json"));

        // State saved after dialog creation must win over its old defaults and session decisions.
        var seed = new ModApprovalsStore.FileData();
        for (int i : new int[]{0, 1, 4, 5, 6, 7, 8, 9}) {
            var row = rows.get(i);
            seed.mods.add(new ModApprovalsStore.ModEntry(row.modId, null, row.sha256, i != 1 && i != 4 && i != 8, "before", null));
        }
        ModApprovalsStore.save(seed);
        var config = new Config().withTrustedAuthor(AUTHOR)
                .withPreloadMod("preload", new Config.PreloadMod(rows.get(7).infAbsolutePath, rows.get(7).jarAbsolutePath));
        Config.save(config);
        byte[] approvalsBefore = Files.readAllBytes(root.resolve("mod_approvals.json"));
        byte[] configBefore = Files.readAllBytes(root.resolve("config.json"));
        field("g_storedEntries").set(null, new ArrayList<>(seed.mods));
        field("g_modApprovalsDirty").set(null, false);
        field("g_config").set(null, config); field("g_configDirty").set(null, false);
        var session = (JarDecisionTable)field("g_sessionJarDecisions").get(null);
        session.put(rows.get(10).sha256, true);
        var table = new JarDecisionTable();
        Loader.applyBatchApprovalLines(result, table);
        boolean[] expected = {true, false, false, true, true, false, false, true, false, true, false, false};
        for (int i = 0; i < expected.length; i++) {
            check(Boolean.valueOf(expected[i]).equals(table.get(rows.get(i).sha256)), "Saved-state Cancel result: " + rows.get(i).modId);
            check(Boolean.valueOf(expected[i]).equals(session.get(rows.get(i).sha256)), "Session result restored: " + rows.get(i).modId);
            check(!result.get(i).flags.has(MF_PERSIST | MF_TRUST_AUTHOR | MF_PRELOAD) && result.get(i).flags.value() < 128,
                    "No transient Cancel/reset/untrust marker reaches original processing");
        }
        check(!(boolean)field("g_modApprovalsDirty").get(null) && !(boolean)field("g_configDirty").get(null), "Cancel raises neither save gate");
        check(Arrays.equals(approvalsBefore, Files.readAllBytes(root.resolve("mod_approvals.json"))), "Saved approvals byte-identical");
        check(Arrays.equals(configBefore, Files.readAllBytes(root.resolve("config.json"))), "Trust and preload config byte-identical");
        check(config.equals(field("g_config").get(null)), "Pending dialog trust/preload edits discarded");
    }
    /** Unit-test entry: no graphics context; restore all shared loader/config state afterward. */
    @SuppressWarnings("unchecked")
    static void stateTests(Path root) throws Exception {
        Field revokedField = ApprovalChoices.class.getDeclaredField("banRevokedAuthors"); revokedField.setAccessible(true);
        var revoked = (Set<SteamWorkshop.SteamID64>)revokedField.get(null);
        var previousRevocations = new HashSet<>(revoked);
        String previousDir = Agent.arguments.get("config_dir");
        Map<String, Object> previous = new LinkedHashMap<>();
        for (String name : List.of("g_storedEntries", "g_modApprovalsDirty", "g_config", "g_configDirty"))
            previous.put(name, field(name).get(null));
        var session = (JarDecisionTable)field("g_sessionJarDecisions").get(null);
        Map<String, Boolean> previousSession = new HashMap<>();
        for (String hash : session.hashes()) previousSession.put(hash, session.get(hash));
        try {
            matrix(root.resolve("save-on"), true);
            matrix(root.resolve("save-off"), false);
        } finally {
            revoked.clear(); revoked.addAll(previousRevocations);
            for (var state : previous.entrySet()) field(state.getKey()).set(null, state.getValue());
            session.hashes().clear(); previousSession.forEach(session::put);
            if (previousDir == null) Agent.arguments.remove("config_dir");
            else Agent.arguments.put("config_dir", previousDir);
        }
    }
    private static void loader(Path root, Boolean saved, boolean save, boolean restart) throws Exception {
        String hash = restart ? Utils.sha256Hex(root.resolve("fixture/fixture.jar"))
                : (String)persistence("makeFixture", new Class<?>[]{Path.class}, root);
        persistence("configure", new Class<?>[]{Path.class, boolean.class}, root, !restart);
        if (!restart) {
            var seed = new ModApprovalsStore.FileData();
            if (saved != null) seed.mods.add(new ModApprovalsStore.ModEntry("ApprovalResetFixture", null, hash, saved, "before", null));
            ModApprovalsStore.save(seed); Config.save(new Config());
        }
        byte[] before = Files.readAllBytes(Agent.configDir().resolve("mod_approvals.json"));
        byte[] configBefore = Files.readAllBytes(Agent.configDir().resolve("config.json"));
        int[] calls = {0};
        field("g_approvalFrontend").set(null, (ModApprovalFrontend)pending -> {
            calls[0]++;
            try {
                check(pending.size() == 1 && Objects.equals(pending.get(0).decision, saved), "Actual loader supplies saved state");
                Object dialog = ApprovalDialogProbe.dialog(pending);
                ApprovalDialogProbe.choose(dialog, 0, !Boolean.TRUE.equals(saved));
                return cancel(dialog, save, root.resolve("response.json"));
            } catch (Exception e) { throw new AssertionError(e); }
        });
        Loader.loadMods(new ArrayList<>(List.of("ApprovalResetFixture")));
        check(calls[0] == (restart && saved != null ? 0 : 1), "Only unresolved hashes prompt on ordinary restart");
        var session = (JarDecisionTable)field("g_sessionJarDecisions").get(null);
        if (!restart || saved == null) check(Boolean.valueOf(Boolean.TRUE.equals(saved)).equals(session.get(hash)), "Actual loader keeps saved Allow/Deny and denies unresolved hashes");
        check(Arrays.equals(before, Files.readAllBytes(Agent.configDir().resolve("mod_approvals.json"))), "Actual loader save gate preserves approval bytes");
        check(Arrays.equals(configBefore, Files.readAllBytes(Agent.configDir().resolve("config.json"))), "Actual loader save gate preserves config bytes");
        check(!(boolean)field("g_modApprovalsDirty").get(null) && !(boolean)field("g_configDirty").get(null), "No actual-loader persistence after Cancel");
        if (!restart) {
            String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
            int exit = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-Xverify:all", "-cp", System.getProperty("java.class.path"), ApprovalCancelProbe.class.getName(),
                    root.toString(), "restart", String.valueOf(saved)).inheritIO().start().waitFor();
            check(exit == 0, "Fresh JVM retains decisions and asks again only for unresolved hashes");
        }
    }
    private static void bannedAuthor(Path root, Boolean saved, boolean save, boolean staleDisk) throws Exception {
        String hash = (String)persistence("makeFixture", new Class<?>[]{Path.class}, root);
        persistence("configure", new Class<?>[]{Path.class, boolean.class}, root, true);
        installBannedFixture(root);
        var other = new SteamWorkshop.SteamID64(76561198000000011L);
        Config.save(new Config().withTrustedAuthor(AUTHOR).withTrustedAuthor(other));
        Loader.initConfig();
        var seed = new ModApprovalsStore.FileData();
        if (saved != null) seed.mods.add(new ModApprovalsStore.ModEntry("ApprovalResetFixture", null, hash, saved, "before", AUTHOR));
        ModApprovalsStore.save(seed);
        int[] calls = {0};
        field("g_approvalFrontend").set(null, (ModApprovalFrontend)pending -> {
            calls[0]++;
            try {
                check(!((Config)field("g_config").get(null)).trustsAuthor(AUTHOR), "Actual ban handler revokes live trust");
                check(!Config.load().trustsAuthor(AUTHOR), "Ban revocation is saved before frontend opens");
                if (staleDisk) Config.save(new Config().withTrustedAuthor(AUTHOR).withTrustedAuthor(other));
                var signed = new JarBatchApprovalProtocol.Entry.ZBSignature(true, AUTHOR, "controlled signed fixture");
                var e = pending.stream().filter(row -> row.modId.equals("ApprovalResetFixture")).findFirst().orElseThrow();
                var sibling = new JarBatchApprovalProtocol.Entry(e.modId, e.workshopItemId, e.jarAbsolutePath,
                        e.infAbsolutePath, e.sha256, e.date, saved, e.flags, e.modDisplayName, signed, null);
                var rows = List.of(ApprovalDialogProbe.entry("banned", false, MF_BANNED, signed,
                                new JarBatchApprovalProtocol.Entry.SteamBan("controlled ban")),
                        sibling, ApprovalDialogProbe.entry("other-trusted", null, MF_TRUST_AUTHOR,
                                new JarBatchApprovalProtocol.Entry.ZBSignature(true, other, "other"), null),
                        ApprovalDialogProbe.entry("revoked-saved-allow", true, MF_PERSIST, signed, null));
                var data = ModApprovalsStore.load();
                data.mods.add(new ModApprovalsStore.ModEntry("revoked-saved-allow", null,
                        rows.get(3).sha256, true, "before", AUTHOR));
                ModApprovalsStore.save(data); // Fixture seed only, before Cancel processing.
                return cancel(ApprovalDialogProbe.dialog(rows), save, root.resolve("response.json"));
            } catch (Exception ex) { throw new AssertionError(ex); }
        });
        Loader.loadMods(new ArrayList<>(List.of("ApprovalResetFixture", "BannedFixture")));
        var session = (JarDecisionTable)field("g_sessionJarDecisions").get(null);
        check(calls[0] == 1, "Actual loader processes ban/Cancel response");
        check(Boolean.FALSE.equals(session.get(hash)), "Cancel cannot restore banned uploader trust for eligible sibling or override saved Deny");
        check(Boolean.TRUE.equals(session.get("other-trusted-hash")), "Unrelated saved author trust still applies");
        check(Boolean.TRUE.equals(session.get("revoked-saved-allow-hash")), "Revocation does not erase explicit saved Allow");
        check(!Config.load().trustsAuthor(AUTHOR) && Config.load().trustsAuthor(other), "Actual save gate persists automatic revocation only");
        check(!(boolean)field("g_modApprovalsDirty").get(null), "Cancel never dirties hash approvals");
        // Compare the sibling record itself; the unrelated saved-Allow row was seeded in the frontend.
        var siblingRecords = ModApprovalsStore.load().mods.stream().filter(e -> hash.equals(e.jarHash)).toList();
        check(saved == null ? siblingRecords.isEmpty() : siblingRecords.size() == 1 && saved.equals(siblingRecords.get(0).decision),
                "No implicit hash approval/refusal is saved after ban/Cancel");
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void installBannedFixture(Path root) throws Exception {
        Path dir = root.resolve("content/108600/123456789/mods/BannedFixture/42");
        Files.createDirectories(dir);
        Files.copy(root.resolve("fixture/fixture.jar"), dir.resolve("fixture.jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(dir.resolve("mod.info"), "name=Banned fixture\nid=BannedFixture\njavaJarFile=fixture.jar\njavaPkgName=local.approval.banned\n");
        var mod = new zombie.gameStates.ChooseGameInfo.Mod(dir.toString());
        mod.setId("BannedFixture"); mod.setName("Banned fixture"); mod.commonDir = dir.toString(); mod.versionDir = dir.toString();
        for (String name : List.of("available", "availableDone")) {
            Field f = mod.getClass().getDeclaredField(name); f.setAccessible(true); f.set(mod, true);
        }
        Field mods = zombie.gameStates.ChooseGameInfo.class.getDeclaredField("Mods"); mods.setAccessible(true);
        ((Map)mods.get(null)).put("BannedFixture", mod);
        // Actual upstream HTTP parser/cache, with an explicit no-network response fixture.
        SteamWorkshop.putCachedBody("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/",
                "itemcount=1&publishedfileids[0]=123456789",
                "{\"response\":{\"publishedfiledetails\":[{\"publishedfileid\":\"123456789\",\"banned\":1,\"creator\":\""
                        + AUTHOR + "\",\"ban_reason\":\"controlled ban\"}]}}");
    }
    public static void main(String[] args) throws Exception {
        Class<?> rand = Class.forName("zombie.core.random.RandStandard");
        rand.getMethod("init").invoke(rand.getField("INSTANCE").get(null));
        Path root = Path.of(args[0]).toAbsolutePath();
        if (args.length > 1) loader(root, args[2].equals("null") ? null : Boolean.valueOf(args[2]), true, true);
        else {
            for (Boolean saved : new Boolean[]{null, false}) for (boolean save : new boolean[]{true, false})
                for (boolean staleDisk : new boolean[]{false, true})
                    bannedAuthor(root.resolve("banned-" + saved + "-" + save + "-" + staleDisk), saved, save, staleDisk);
            matrix(root.resolve("save-on"), true); matrix(root.resolve("save-off"), false);
            for (Boolean saved : new Boolean[]{true, false, null}) for (boolean save : new boolean[]{true, false})
                loader(root.resolve("loader-" + saved + "-" + save), saved, save, false);
        }
        System.out.println("PASS " + checks + " Cancel assertions; verified-author metadata is synthetic; actual loader/save gates and fresh JVMs");
    }
}
