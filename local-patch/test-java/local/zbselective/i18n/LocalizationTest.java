package local.zbselective.i18n;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import me.zed_0xff.zombie_buddy.*;
import me.zed_0xff.zombie_buddy.frontend.ConsoleModApprovalFrontend;

public final class LocalizationTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("child")) {
            UiText.initialize();
            System.out.println(UiText.language() + ":" + UiText.text("Allow", "允许"));
            return;
        }
        if (args[0].equals("font")) { font(); return; }
        check(UiText.language().equals(args[0]), "Agent reads game language before any translation mod is enabled");
        check(UiText.text("Allow", "允许").equals(args[0].equals("CN") ? "允许" : "Allow"), "startup display language");
        Class<?> dialog = Class.forName("me.zed_0xff.zombie_buddy.frontend.ImguiApprovalDialog");
        Method column = dialog.getDeclaredMethod("COL_ALLOW"); column.setAccessible(true);
        Class<?> translator = Class.forName("zombie.core.Translator");
        Class<?> language = Class.forName("zombie.core.Language");
        Constructor<?> constructor = language.getDeclaredConstructor(String.class,String.class,String.class,boolean.class);
        constructor.setAccessible(true);
        Method setLanguage = translator.getMethod("setLanguage", language);
        @SuppressWarnings("unchecked") Map<String,String> ui = (Map<String,String>) translator.getMethod("getUI").invoke(null);
        ui.put("UI_Unrelated_Test", "leave untouched");
        setLanguage.invoke(null, constructor.newInstance("CN","简体中文","EN",false));
        check(column.invoke(null).equals("允许"), "static ImGui column label switches live to CN");
        check(ui.get("UI_ZB_WatermarkOpacity").equals("水印不透明度"), "original Lua settings translated from JAR");
        check(UiText.childVmOption().equals("-Dzb.ui.language=CN"), "Swing/restart subprocess inherits current CN");
        check(UiText.watermarkLine("Preload mod \"OpaqueID\" added/updated. Restart recommended.").contains("OpaqueID"), "opaque mod ID preserved");
        console(true);
        setLanguage.invoke(null, constructor.newInstance("EN","English",null,false));
        check(column.invoke(null).equals("Allow"), "static ImGui column label switches live back to EN");
        check(ui.get("UI_ZB_WatermarkOpacity").equals("Watermark Opacity"), "Lua settings revert to English");
        check(ui.get("UI_Unrelated_Test").equals("leave untouched"), "other mods' translation keys preserved");
        console(false);
        check(UiText.replacesLegacyPackage("cn.zbcn") && !UiText.replacesLegacyPackage("fixture.sample"), "only superseded CN package is suppressed");
        Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
        Class<?> phase = Class.forName("me.zed_0xff.zombie_buddy.Loader$Phase");
        @SuppressWarnings({"rawtypes","unchecked"}) Object main = Enum.valueOf((Class)phase,"MAIN");
        Method load = loader.getDeclaredMethod("loadJar",Path.class,String.class,String.class,phase);load.setAccessible(true);
        check(!(Boolean)load.invoke(null,Path.of(args[1]),"cn.zbcn","not-an-approval",main), "old CN JAR never runs its fixed-Chinese transformer");
        System.out.println("PASS automatic localization " + args[0]);
    }

    private static void console(boolean chinese) throws Exception {
        InputStream input = System.in; PrintStream output = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream("y\nn\n".getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(captured,true,StandardCharsets.UTF_8));
            var entry = new JarBatchApprovalProtocol.Entry("OpaqueID",null,Path.of("unchanged.jar"),Path.of("unchanged.info"),
                "unchanged-sha256",new Date(0),null,ModFlags.EMPTY,"Display name",
                new JarBatchApprovalProtocol.Entry.ZBSignature(true,null,""),null);
            new ConsoleModApprovalFrontend().approvePendingMods(new ArrayList<>(List.of(entry)));
            check(Boolean.TRUE.equals(entry.decision), "localized prompt keeps y=yes decision");
            check(!entry.flags.has(ModFlags.MF_PERSIST), "localized prompt keeps n=no persistence");
            check(entry.modId.equals("OpaqueID") && entry.sha256.equals("unchanged-sha256"), "protocol identifiers/hash untouched");
            String text = captured.toString(StandardCharsets.UTF_8);
            check(text.contains(chinese ? "允许此 Java 模组加载吗？" : "Allow this Java mod to load?"), "console display language");
            captured.reset();
            System.setIn(new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)));
            var bad = new JarBatchApprovalProtocol.Entry("BadSignature",null,Path.of("bad.jar"),Path.of("bad.info"),"bad-hash",
                new Date(0),null,ModFlags.EMPTY,"Bad",new JarBatchApprovalProtocol.Entry.ZBSignature(false,null,"bad signature"),null);
            new ConsoleModApprovalFrontend().approvePendingMods(new ArrayList<>(List.of(bad)));
            check(Boolean.FALSE.equals(bad.decision), "invalid signature remains denied in both languages");
        } finally { System.setIn(input); System.setOut(output); }
    }

    private static void font() {
        imgui.ImGui.createContext();
        imgui.ImFontConfig config = new imgui.ImFontConfig();
        config.setSizePixels(26);
        try {
            var atlas = imgui.ImGui.getIO().getFonts();
            var base = atlas.addFontDefault(config);
            CnFonts.install(config);
            check(atlas.build(), "native ImGui font atlas builds");
            for (char ch : "修复性能优化允许拒绝审批水印重启中英".toCharArray())
                check(base.findGlyphNoFallback(ch).ptr!=0, "CJK glyph available: " + ch);
            check(base.findGlyphNoFallback('A').ptr!=0, "Latin glyph preserved");
            System.out.println("PASS native ImGui CJK glyphs");
        } finally { config.destroy(); imgui.ImGui.destroyContext(); }
    }
}
