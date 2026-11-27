package me.zed_0xff.zombie_buddy;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import zombie.ZomboidFileSystem;
import zombie.gameStates.ChooseGameInfo;
import zombie.modding.ActiveMods;

/** Actual instrumented Vanilla save, actual Loader and isolated process preload state. */
public final class PreloadSelectionProbe {
    private static int checks;
    private static final SteamWorkshop.SteamID64 AUTHOR = new SteamWorkshop.SteamID64(76561198000000019L);
    private static final java.security.KeyPair KEY;
    static { try { KEY = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (java.security.GeneralSecurityException error) { throw new ExceptionInInitializerError(error); } }

    private static String json(Object value) throws Exception {
        Object gson = field(ZBGson.class, "PRETTY").get(null);
        return (String) gson.getClass().getMethod("toJson", Object.class).invoke(gson, value);
    }

    private static void offline() {
        byte[] encoded = KEY.getPublic().getEncoded();
        String key = HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
        SteamWorkshop.putCachedBody(KnownAuthors.REMOTE_URL, "", "{}");
        SteamWorkshop.putCachedBody(SteamWorkshop.authorProfileUrl(AUTHOR), "", "JavaModZBS:" + key);
    }

    private static void approve(Fixture fixture) throws Exception {
        ModApprovalsStore.FileData data = ModApprovalsStore.load();
        data.mods.add(new ModApprovalsStore.ModEntry(fixture.id(), null, Utils.sha256Hex(fixture.jar()), true, "fixture", AUTHOR));
        Files.writeString(Config.jsonPath().resolveSibling("mod_approvals.json"), json(data));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private record Fixture(String id, Path info, Path jar) {}

    @SuppressWarnings("unchecked") private static Fixture fixture(Path root, String id, String pkg, String value) throws Exception {
        Files.createDirectories(root);
        Path classes = Files.createDirectories(root.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("--release", "17", "-d", classes.toString()));
        for (String name : List.of("Main", "PreMain")) {
            Path source = root.resolve(name + ".java");
            String method = name.equals("Main") ? "main" : "premain";
            Files.writeString(source, "package " + pkg + "; public class " + name
                    + " { public static void " + method + "(String[] args) { System.setProperty(\"selection."
                    + method + "\", \"" + value + "\"); } }");
            arguments.add(source.toString());
        }
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)) == 0, "Compile real mod fixture");
        Path info = root.resolve("mod.info"), jar = root.resolve("fixture.jar");
        Files.writeString(info, "name=" + id + "\nid=" + id + "\njavaJarFile=fixture.jar\njavaPkgName=" + pkg + "\njavaPreload=true\n");
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("ZB-Preload", "true");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file)); out.closeEntry();
            }
        }
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(KEY.getPrivate());
        signer.update(("ZBS:" + AUTHOR + ":" + Utils.sha256Hex(jar)).getBytes(StandardCharsets.UTF_8));
        Files.writeString(Path.of(jar + ".zbs"), "ZBS\nSteamID64:" + AUTHOR + "\nSignature:" + HexFormat.of().formatHex(signer.sign()) + "\n");
        ChooseGameInfo.Mod mod = new ChooseGameInfo.Mod(root.toString()); mod.setId(id); mod.setName(id);
        mod.commonDir = root.toString(); mod.versionDir = root.toString();
        field(mod.getClass(), "available").set(mod, true); field(mod.getClass(), "availableDone").set(mod, true);
        ((Map<String,Object>) field(ChooseGameInfo.class, "Mods").get(null)).put(id, mod);
        return new Fixture(id, info, jar.toRealPath());
    }

    private static void config(Path root, Fixture... fixtures) throws Exception {
        Path directory = Files.createDirectories(root.resolve("config"));
        Agent.arguments.put("config_dir", directory.toString());

        Map<String,Object> preloads = new LinkedHashMap<>();
        for (Fixture fixture : fixtures) preloads.put(fixture.id(), Map.of("infPath", fixture.info().toString(), "jarPath", fixture.jar().toString()));
        Files.writeString(directory.resolve("config.json"), json(Map.of("preload_mods", preloads,
                "trusted_authors", List.of(76561198000000019L), "auto_fix_mod_order", false, "fix_approval_dialog_cursor", true, "fix_macos_retina", true, "stealth_mode", true)));
        Files.writeString(directory.resolve("mod_approvals.json"), "{\"formatVersion\":2,\"mods\":[]}\n");
        Loader.initConfig();
        for (Fixture fixture : fixtures) approve(fixture);
    }

    private static void select(String... ids) {
        var mods = ActiveMods.getById("default").getMods(); mods.clear(); mods.addAll(List.of(ids));
    }

    private static void load(String modSet, String... ids) {
        Loader.onEnterLoadMods(modSet);
        try { Loader.loadMods(new ArrayList<>(List.of(ids))); }
        finally { Loader.onExitLoadMods(modSet); }
    }

    private static void preload() throws Exception {
        var method = Loader.class.getDeclaredMethod("preloadMods"); method.setAccessible(true); method.invoke(null);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]).toAbsolutePath()); String mode = args[1];
        // Must happen before fixtures initialize any game multiplayer static state.
        zombie.core.random.RandStandard.INSTANCE.init();
        offline();
        field(Loader.class, "g_hasDoLoadingText").set(null, false);
        field(Loader.class, "g_forceApprovalDialog").set(null, false);
        Path gameCache = Files.createDirectories(root.resolve("game-cache"));
        ZomboidFileSystem.instance.setCacheDir(gameCache.toString());
        Fixture wip = fixture(root.resolve("wip"), "Selection_WIP", "local.selection.dual", "wip");
        Fixture release = fixture(root.resolve("release"), "Selection", "local.selection.dual", "release");
        config(root, wip, release); select(wip.id());
        Path configFile = root.resolve("config/config.json"), approvals = root.resolve("config/mod_approvals.json");
        byte[] approvalsBefore = Files.readAllBytes(approvals);
        PrintStream original = System.out, originalError = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        System.setOut(capture); System.setErr(capture);
        try {
            if (mode.equals("save") || mode.equals("coop") || mode.equals("failed")) {
                if (mode.equals("failed")) Files.createDirectories(gameCache.resolve("mods/default.txt"));
                byte[] before = Files.readAllBytes(configFile);
                // This is the real Vanilla entry used by saveModsFile() in the Lua ModSelector.
                zombie.Lua.LuaManager.GlobalObject.saveModsFile();
                check(Arrays.equals(approvalsBefore, Files.readAllBytes(approvals)), "Vanilla save hook leaves hash approvals byte-identical");
                if (mode.equals("coop") || mode.equals("failed")) {
                    check(Arrays.equals(before, Files.readAllBytes(configFile)), "Read-only Coop / failed Vanilla save must preserve config byte-for-byte");
                } else {
                    check(Files.readString(gameCache.resolve("mods/default.txt")).contains(wip.id()), "Vanilla writes actual selected default mod");
                    check(Config.load().preload_mods().keySet().equals(Set.of(wip.id())), "Actual save removes stale release registration before any loader call");
                    check(((String) field(Watermark.class, "_midLine").get(null)).contains("Full restart required"), "Successful selection change sets visible restart watermark");
                    load("default", wip.id());
                    check("wip".equals(System.getProperty("selection.main")), "Only selected WIP Main executes; release cannot override it");
                    check(Loader.getActiveJavaMods().stream().noneMatch(state -> state.id().equals(release.id())), "Inactive release absent from active Java mods");
                    select(); zombie.Lua.LuaManager.GlobalObject.saveModsFile();
                    check(Config.load().preload_mods().isEmpty(), "Saving empty selection removes last preload even without ResetLua");
                    select(release.id()); zombie.Lua.LuaManager.GlobalObject.saveModsFile(); load("default", release.id());
                    check(Config.load().preload_mods().keySet().equals(Set.of(release.id())), "Switching WIP to release registers only selected release after ordinary authorization");
                }
            } else if (mode.equals("load")) {
                // First default load in a fresh process has no preceding default snapshot.
                load("default", wip.id());
                check(Config.load().preload_mods().keySet().equals(Set.of(wip.id())), "First default load repairs stale registry");
                check("wip".equals(System.getProperty("selection.main")), "Fresh default load uses WIP Java code");
            } else if (mode.equals("context")) {
                load("currentGame", wip.id());
                check(Config.load().preload_mods().size() == 2, "Savegame selection must not erase global default registrations");
                ActiveMods savegame = ActiveMods.getById("currentGame");
                savegame.getMods().clear(); savegame.getMods().add(wip.id());
                byte[] before = Files.readAllBytes(configFile);
                check(new zombie.modding.ActiveModsFile().write(gameCache.resolve("savegame-mods.txt").toString(), savegame), "Actual nondefault Vanilla write succeeds");
                check(Arrays.equals(before, Files.readAllBytes(configFile)), "Nondefault save hook preserves global preload config byte-for-byte");
            } else {
                config(root, wip);
                if (mode.equals("early") || mode.equals("updated")) {
                    preload(); check("wip".equals(System.getProperty("selection.premain")), "Actual preload executes PreMain");
                }
                if (mode.equals("updated")) { wip = fixture(root.resolve("updated"), wip.id(), "local.selection.dual", "updated"); approve(wip); }
                load("default", wip.id());
                check(Files.isSameFile(Config.load().preload_mods().get(wip.id()).jarPath(), wip.jar()), "Registration follows validated current jar path");
                final String selectedId = wip.id();
                check(Loader.getActiveJavaMods().stream().anyMatch(state -> state.id().equals(selectedId)
                    && state.flags().hasAll(ModFlags.MF_ACTIVE, ModFlags.MF_VALID, ModFlags.MF_SIGNED)), "Fixture is actually active, valid and signed");
                String log = bytes.toString(StandardCharsets.UTF_8);
                check((log.contains("PRELOAD RESTART REQUIRED") || log.contains("full restart required")) != mode.equals("early"), "Warn for registered-but-late or changed jar, stay quiet for actual matching early preload");
                String watermark = (String) field(Watermark.class, "_midLine").get(null);
                check(mode.equals("early") || (watermark != null && watermark.contains(wip.id()) && (watermark.contains("Full restart required") || watermark.contains("Restart recommended"))), "Late/changed preload sets visible named restart watermark");
                check(log.split("PRELOAD RESTART REQUIRED", -1).length <= 2, "Existing PR-92 registration and new late-load notice must not duplicate warnings");
                load("default", wip.id());
                String again = bytes.toString(StandardCharsets.UTF_8);
                check(again.split("PRELOAD RESTART REQUIRED", -1).length == log.split("PRELOAD RESTART REQUIRED", -1).length, "Repeated load does not spam restart console");
            }
            Config saved = Config.load();
            check(saved.trusted_authors().equals(List.of(new SteamWorkshop.SteamID64(76561198000000019L))), "Author trust preserved");
            check(!saved.auto_fix_mod_order() && saved.fix_approval_dialog_cursor() && saved.fix_macos_retina() && saved.stealth_mode(), "Unrelated settings preserved");
        } finally { System.setOut(original); System.setErr(originalError); original.print(bytes.toString(StandardCharsets.UTF_8)); }
        System.out.println("PASS " + checks + " preload selection assertions (" + mode + "); real agent/Vanilla save/loader; no game session");
    }
}
