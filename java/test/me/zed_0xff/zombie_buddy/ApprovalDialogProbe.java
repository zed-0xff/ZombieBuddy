package me.zed_0xff.zombie_buddy;

import imgui.*;
import imgui.type.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

/**
 * Explicit integration probe: main uses native ImGui mouse/draw data and the packaged persistence path.
 * Normal JUnit runs call only stateTests(), which needs no native graphics context. See test/README.md.
 */
public final class ApprovalDialogProbe {
    static int checks;
    static final Class<?> DIALOG;
    static {
        try { DIALOG = Class.forName("me.zed_0xff.zombie_buddy.frontend.ImguiApprovalDialog"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; }
    static Object field(Object target, String name) throws Exception {
        Field f = (target instanceof Class<?> c ? c : target.getClass()).getDeclaredField(name);
        f.setAccessible(true); return f.get(target instanceof Class<?> ? null : target);
    }
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Method m = type.getDeclaredMethod(name, types); m.setAccessible(true);
        return m.invoke(target instanceof Class<?> ? null : target, args);
    }
    static JarBatchApprovalProtocol.Entry entry(String name, Boolean decision, int flags,
            JarBatchApprovalProtocol.Entry.ZBSignature signature, JarBatchApprovalProtocol.Entry.SteamBan ban) {
        return new JarBatchApprovalProtocol.Entry(name, null, Path.of(name + ".jar").toAbsolutePath(),
                Path.of("mod.info").toAbsolutePath(), name + "-hash", null, decision,
                new ModFlags(flags), name, signature, ban);
    }
    static JarBatchApprovalProtocol.Entry entry(String name) { return entry(name, null, 0, null, null); }
    static Object dialog(List<JarBatchApprovalProtocol.Entry> entries) throws Exception {
        var ctor = DIALOG.getDeclaredConstructor(List.class, AtomicReference.class); ctor.setAccessible(true);
        return ctor.newInstance(entries, new AtomicReference<>());
    }
    @SuppressWarnings("unchecked") static List<JarBatchApprovalProtocol.Entry> finish(Object dialog, boolean save) throws Exception {
        ((ImBoolean)field(dialog, "persistDecisions")).set(save);
        return (List<JarBatchApprovalProtocol.Entry>)call(dialog, "buildLines", new Class<?>[0]);
    }
    static void choose(Object dialog, int index, boolean allow) throws Exception {
        boolean launchAllow = ((ApprovalChoices)field(dialog, "choices")).toggle(index, allow);
        ((ImBoolean[])field(dialog, "allow"))[index].set(launchAllow);
    }
    static void trust(Object dialog, int index, boolean selected) throws Exception {
        call(dialog, "applyTrustAuthor", new Class<?>[]{int.class, boolean.class}, index, selected);
    }

    static float noX, noY, size;
    static Set<Integer> colors = new HashSet<>();
    static void frame(Object dialog, boolean down, float x, float y, boolean interactive) throws Exception {
        ImGui.getIO().setMousePos(x, y); ImGui.getIO().setMouseDown(0, down);
        ImGui.newFrame();
        ImGui.setNextWindowPos(10, 10); ImGui.setNextWindowSize(600, 300);
        ImGui.begin("Approval regression");
        for (String name : List.of("tableRowStartY", "tableRowHeight")) {
            Field f = DIALOG.getDeclaredField(name); f.setAccessible(true);
            f.setFloat(null, name.equals("tableRowStartY") ? ImGui.getCursorPosY() : ImGui.getFrameHeight());
        }
        call(dialog, "drawAllowCheckboxes", new Class<?>[]{int.class, boolean.class}, 0, interactive);
        noX = (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2;
        noY = (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2;
        size = ImGui.getFrameHeight();
        ImGui.end(); ImGui.render();
        colors.clear();
        var data = ImGui.getDrawData();
        check(ImDrawData.SIZEOF_IM_DRAW_VERT == 20, "Installed ImDrawVert layout");
        for (int i = 0; i < data.getCmdListsCount(); i++) {
            ByteBuffer vertices = data.getCmdListVtxBufferData(i).order(ByteOrder.nativeOrder());
            for (int offset = 16; offset < vertices.limit(); offset += 20) colors.add(vertices.getInt(offset));
        }
    }
    static void click(Object dialog, boolean yes) throws Exception {
        float x = noX - (yes ? size + ImGui.getStyle().getItemSpacingX() : 0), y = noY;
        frame(dialog, false, x, y, true);
        frame(dialog, true, x, y, true);
        frame(dialog, false, x, y, true);
        frame(dialog, false, -100, -100, true);
    }
    static void mouseTests() throws Exception {
        ImGui.createContext();
        try {
            ImGui.getIO().setIniFilename(null); ImGui.getIO().setDisplaySize(800, 600);
            ImGui.getIO().setDeltaTime(1.0f / 60);
            ImGui.getIO().getFonts().getTexDataAsRGBA32(new ImInt(), new ImInt());
            Object d = dialog(List.of(entry("mouse")));
            frame(d, false, -100, -100, true); frame(d, false, -100, -100, true);
            check(colors.contains(0xff969696) && !colors.contains(0xff0000ff), "Untouched X really draws grey");
            click(d, false);
            check(colors.contains(0xff0000ff), "First No click really draws red");
            var lines = finish(d, true);
            check(Boolean.FALSE.equals(lines.get(0).decision) && lines.get(0).flags.has(MF_PERSIST), "Actual No click is explicit and persisted");
            click(d, true);
            check(Boolean.TRUE.equals(finish(d, true).get(0).decision), "Actual Yes click allows");
            click(d, false);
            check(Boolean.FALSE.equals(finish(d, true).get(0).decision), "Actual Yes-to-No click denies");
            click(d, false);
            check(colors.contains(0xff969696) && !colors.contains(0xff0000ff), "Second No click restores grey X");
            check(Boolean.FALSE.equals(finish(d, false).get(0).decision), "Cleared No denies this launch");
            check(!finish(d, false).get(0).flags.has(MF_PERSIST), "Cleared No without Save has no disk effect");
            click(d, true); click(d, true);
            check(colors.contains(0xff969696) && !colors.contains(0xff00ff00), "Second Yes click clears green check and restores grey X");
            check(!finish(d, false).get(0).decision, "Cleared Yes denies this launch");
            click(d, false);
            check(colors.contains(0xff0000ff), "No click after reset selects explicit denial again");
            Object saved = dialog(List.of(entry("saved-mouse", true, MF_PERSIST, null, null)));
            frame(saved, false, -100, -100, true); click(saved, true);
            check(colors.contains(0xff969696) && !((ImBoolean[])field(saved, "allow"))[0].get(), "Click on saved Allow clears it");
            var savedReset = finish(saved, true).get(0);
            check(savedReset.decision == null && savedReset.flags.has(MF_PERSIST), "Saved Allow reset produces explicit removal request");
            saved = dialog(List.of(entry("saved-no-mouse", false, MF_PERSIST, null, null)));
            frame(saved, false, -100, -100, true); click(saved, false);
            check(colors.contains(0xff969696), "Click on saved Deny clears red X");
            Object disabled = dialog(List.of(entry("blocked", null, 0,
                    new JarBatchApprovalProtocol.Entry.ZBSignature(false, null, "invalid"), null)));
            frame(disabled, false, -100, -100, false); clickDisabled(disabled);
            check(!finish(disabled, true).get(0).flags.has(MF_PERSIST), "Disabled row cannot record a click");
        } finally { ImGui.destroyContext(); }
    }
    static void clickDisabled(Object d) throws Exception {
        float x = noX, y = noY;
        frame(d, true, x, y, false); frame(d, false, x, y, false);
    }
    @SuppressWarnings("unchecked") static void stateTests() throws Exception {
        Object d = dialog(List.of(entry("untouched"), entry("yes"), entry("no")));
        choose(d, 1, true); choose(d, 2, false);
        var result = finish(d, true);
        check(Boolean.FALSE.equals(result.get(0).decision) && !result.get(0).flags.has(MF_PERSIST), "Untouched is denied only for session");
        check(result.get(1).flags.has(MF_PERSIST) && result.get(2).flags.has(MF_PERSIST), "Both explicit choices persist");
        check(result.stream().allMatch(e -> !e.flags.has(MF_TRUST_AUTHOR)), "No implicit author trust");
        check(finish(d, false).stream().noneMatch(e -> e.flags.has(MF_PERSIST | MF_TRUST_AUTHOR)), "Save off applies only to session");
        var savedNo = entry("saved-no", false, MF_PERSIST, null, null);
        var savedYes = entry("saved-yes", true, MF_PERSIST, null, null);
        Object old = dialog(List.of(savedNo, savedYes));
        check(((ApprovalChoices)field(old, "choices")).denyColor(0, false, 123) == 123, "Saved refusal remains visually explicit");
        result = finish(old, true);
        check(!result.get(0).decision && result.get(1).decision, "Existing decisions kept");
        check(result.stream().noneMatch(e -> e.flags.has(MF_PERSIST)), "Untouched existing rows not rewritten");
        var sid = new SteamWorkshop.SteamID64(76561197974968019L);
        var signed = new JarBatchApprovalProtocol.Entry.ZBSignature(true, sid, "verified");
        Object authors = dialog(List.of(entry("author-a", null, 0, signed, null), entry("author-b", null, 0, signed, null), entry("other")));
        trust(authors, 0, true); result = finish(authors, true);
        check(result.get(0).decision && result.get(1).decision && result.get(0).flags.hasAll(MF_PERSIST, MF_TRUST_AUTHOR)
                && result.get(1).flags.hasAll(MF_PERSIST, MF_TRUST_AUTHOR), "Explicit author trust covers group");
        check(!result.get(2).flags.has(MF_PERSIST), "Other author unaffected");
        Object mixed = dialog(List.of(entry("valid-author", null, 0, signed, null),
                entry("invalid-same-author", null, 0, new JarBatchApprovalProtocol.Entry.ZBSignature(false, sid, "invalid"), null)));
        trust(mixed, 0, true);
        var mixedLines = finish(mixed, true);
        check(mixedLines.get(0).decision && mixedLines.get(0).flags.hasAll(MF_PERSIST, MF_TRUST_AUTHOR), "Eligible trusted author row persists");
        check(!mixedLines.get(1).decision && !mixedLines.get(1).flags.has(MF_PERSIST | MF_TRUST_AUTHOR), "Grouped trust cannot save a forced refusal");
        trust(authors, 0, false); result = finish(authors, true);
        check(!result.get(0).decision && !result.get(1).decision && result.stream().noneMatch(e -> e.flags.has(MF_PERSIST)), "Undoing new trust does not save default refusals");
        choose(authors, 0, true); trust(authors, 0, true); trust(authors, 0, false);
        result = finish(authors, true);
        check(result.get(0).decision && result.get(0).flags.has(MF_PERSIST) && !result.get(0).flags.has(MF_TRUST_AUTHOR), "Trust toggle restores explicit Yes");
        choose(authors, 0, true); trust(authors, 0, true); trust(authors, 0, false);
        check(!((ImBoolean[])field(authors, "allow"))[0].get(), "Trust toggle restores explicit undecided state");
        var trusted = dialog(List.of(entry("old-trust", true, MF_PERSIST | MF_TRUST_AUTHOR, signed, null)));
        trust(trusted, 0, false); result = finish(trusted, true);
        check(result.get(0).decision && result.get(0).flags.has(MF_PERSIST) && !result.get(0).flags.has(MF_TRUST_AUTHOR), "Explicit author untrust preserves old allow");
        Object blocked = dialog(List.of(entry("invalid", null, 0, new JarBatchApprovalProtocol.Entry.ZBSignature(false, null, "invalid"), null),
                entry("banned", null, 0, signed, new JarBatchApprovalProtocol.Entry.SteamBan("banned"))));
        result = finish(blocked, true);
        check(result.stream().allMatch(e -> !e.decision && !e.flags.has(MF_PERSIST | MF_TRUST_AUTHOR)), "Safety refusals cannot become implicit saved choices");
        result = (List<JarBatchApprovalProtocol.Entry>)call(DIALOG, "denyAll", new Class<?>[]{List.class}, finish(authors, true));
        check(result.stream().allMatch(e -> !e.decision && !e.flags.has(MF_PERSIST | MF_TRUST_AUTHOR)), "Cancel never saves");
    }
    static void diskTest(Path root, boolean read) throws Exception {
        Files.createDirectories(root); Agent.arguments.put("config_dir", root.toString());
        if (read) {
            var table = ModApprovalsStore.load().mods;
            check(table.size() == 3, "Restart reads old and two explicit choices only");
            check(table.stream().noneMatch(e -> e.id.equals("later")), "Undecided mod absent on restart and needs approval again");
            check(table.stream().anyMatch(e -> e.id.equals("yes") && e.decision), "Allowed decision retained on restart");
            check(table.stream().anyMatch(e -> e.id.equals("no") && !e.decision), "Explicit refusal retained on restart");
            check(table.stream().noneMatch(e -> e.id.startsWith("reset-")), "Explicitly cleared old decisions absent on restart");
            return;
        }
        var old = new ModApprovalsStore.ModEntry("old", null, "old-hash", true, "before", null);
        var list = new ArrayList<ModApprovalsStore.ModEntry>(); list.add(old);
        list.add(new ModApprovalsStore.ModEntry("reset-yes", null, "reset-yes-hash", true, "before", null));
        list.add(new ModApprovalsStore.ModEntry("reset-no", null, "reset-no-hash", false, "before", null));
        Field stored = Loader.class.getDeclaredField("g_storedEntries"); stored.setAccessible(true); stored.set(null, list);
        Object d = dialog(List.of(entry("later"), entry("yes"), entry("no"),
                entry("reset-yes", true, MF_PERSIST, null, null), entry("reset-no", false, MF_PERSIST, null, null)));
        choose(d, 1, true); choose(d, 2, false);
        choose(d, 3, true); choose(d, 4, false);
        Path response = root.resolve("response.json");
        var table = new JarDecisionTable();
        JarBatchApprovalProtocol.writeResponse(response, finish(d, false));
        call(Loader.class, "applyBatchApprovalLines", new Class<?>[]{List.class, JarDecisionTable.class}, JarBatchApprovalProtocol.readResponse(response), table);
        check(list.size() == 3 && list.get(0) == old, "Save off preserves old saved decisions including explicit resets");
        JarBatchApprovalProtocol.writeResponse(response, finish(d, true));
        call(Loader.class, "applyBatchApprovalLines", new Class<?>[]{List.class, JarDecisionTable.class}, JarBatchApprovalProtocol.readResponse(response), table);
        check(Boolean.FALSE.equals(table.get("later-hash")), "Original loader denies undecided mod in current session");
        check(Boolean.FALSE.equals(table.get("reset-yes-hash")) && Boolean.FALSE.equals(table.get("reset-no-hash")), "Both resets deny current session");
        check(list.size() == 3 && list.get(0) == old, "Only selected rows added and existing record untouched");
        var data = new ModApprovalsStore.FileData(); data.mods = list; ModApprovalsStore.save(data);
        byte[] before = Files.readAllBytes(root.resolve("mod_approvals.json"));
        Object cancel = dialog(List.of(entry("cancel")));
        var denied = call(DIALOG, "denyAll", new Class<?>[]{List.class}, finish(cancel, true));
        call(Loader.class, "applyBatchApprovalLines", new Class<?>[]{List.class, JarDecisionTable.class}, denied, table);
        ModApprovalsStore.save(data);
        check(Arrays.equals(before, Files.readAllBytes(root.resolve("mod_approvals.json"))), "Cancel leaves saved decisions byte-identical");
        var cancelReset = dialog(List.of(entry("yes", true, MF_PERSIST, null, null)));
        choose(cancelReset, 0, true);
        denied = call(DIALOG, "denyAll", new Class<?>[]{List.class}, finish(cancelReset, true));
        call(Loader.class, "applyBatchApprovalLines", new Class<?>[]{List.class, JarDecisionTable.class}, denied, table);
        ModApprovalsStore.save(data);
        check(Arrays.equals(before, Files.readAllBytes(root.resolve("mod_approvals.json"))), "Cancel discards pending reset of saved decision");
        int exit = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "java.exe" : "java").toString(),
                "-Xverify:all", "-cp", System.getProperty("java.class.path"), ApprovalDialogProbe.class.getName(), root.toString(), "read").inheritIO().start().waitFor();
        check(exit == 0, "Independent restart process");
        Path emptyRoot = root.resolve("last-reset");
        Agent.arguments.put("config_dir", emptyRoot.toString());
        var only = new ArrayList<ModApprovalsStore.ModEntry>();
        only.add(new ModApprovalsStore.ModEntry("last", null, "last-hash", true, "before", null));
        stored.set(null, only);
        Object last = dialog(List.of(entry("last", true, MF_PERSIST, null, null)));
        choose(last, 0, true);
        JarBatchApprovalProtocol.writeResponse(response, finish(last, true));
        call(Loader.class, "applyBatchApprovalLines", new Class<?>[]{List.class, JarDecisionTable.class}, JarBatchApprovalProtocol.readResponse(response), table);
        check(only.isEmpty() && Boolean.FALSE.equals(table.get("last-hash")), "Last saved choice can be cleared while staying denied this launch");
        data.mods = only; ModApprovalsStore.save(data);
        check(ModApprovalsStore.load().mods.isEmpty(), "Empty approvals file remains valid after clearing last record");
        var author = new SteamWorkshop.SteamID64(76561197974968019L);
        var otherAuthor = new SteamWorkshop.SteamID64(76561198043849998L);
        Field config = Loader.class.getDeclaredField("g_config"); config.setAccessible(true);
        Config previousConfig = (Config)config.get(null);
        config.set(null, new Config().withTrustedAuthor(author).withTrustedAuthor(otherAuthor));
        Object signed = dialog(List.of(entry("signed-reset", true, MF_PERSIST | MF_TRUST_AUTHOR,
                new JarBatchApprovalProtocol.Entry.ZBSignature(true, author, "verified"), null)));
        trust(signed, 0, false); choose(signed, 0, true);
        JarBatchApprovalProtocol.writeResponse(response, finish(signed, false));
        call(Loader.class, "applyBatchApprovalLines", new Class<?>[]{List.class, JarDecisionTable.class}, JarBatchApprovalProtocol.readResponse(response), table);
        check(((Config)config.get(null)).trustsAuthor(author), "Save off preserves author trust when clearing a row");
        JarBatchApprovalProtocol.writeResponse(response, finish(signed, true));
        call(Loader.class, "applyBatchApprovalLines", new Class<?>[]{List.class, JarDecisionTable.class}, JarBatchApprovalProtocol.readResponse(response), table);
        Config afterReset = (Config)config.get(null);
        check(!afterReset.trustsAuthor(author) && afterReset.trustsAuthor(otherAuthor), "Saved row reset honors explicit untrust and preserves unrelated author");
        Config.save(afterReset);
        check(!Config.load().trustsAuthor(author) && Config.load().trustsAuthor(otherAuthor), "Original config writer retains untrust across reload");
        config.set(null, previousConfig);
        Agent.arguments.put("config_dir", root.toString()); stored.set(null, list);
    }
    public static void main(String[] args) throws Exception {
        boolean read = args.length > 1 && args[1].equals("read");
        if (!read) { stateTests(); mouseTests(); }
        diskTest(Path.of(args[0]), read);
        System.out.println("PASS " + checks + " packaged approval assertions" + (read ? " on restart" : "; actual ImGui clicks/colors, protocol, original store and restart"));
    }
}
