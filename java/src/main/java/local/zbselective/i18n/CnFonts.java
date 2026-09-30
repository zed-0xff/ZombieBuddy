package local.zbselective.i18n;

import java.io.InputStream;

import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;

/**
 * Merges a bundled CJK-capable TrueType subset into the ImGui font atlas used
 * by ZombieBuddy's approval dialog. Called by the bytecode hook injected into
 * {@code ImguiApprovalMain.configureImguiStyle} and
 * {@code ImguiModApprovalFrontend.configureImguiStyle}, right before the
 * temporary font config is destroyed (after the default font has been added,
 * before the font texture is built).
 */
public final class CnFonts {

    private static final String FONT_RESOURCE = "/fonts/NotoSansSC-subset.ttf";
    private static volatile boolean warnLogged = false;

    /**
     * Glyph ranges for the bundled subset. Without explicit ranges, Dear ImGui
     * only rasterizes the default ASCII/Latin-1 ranges, so every CJK codepoint
     * falls back to the '?' glyph even though the font is merged into the atlas.
     */
    private static final short[] GLYPH_RANGES = new short[] {
            (short) 0x20, (short) 0x7E,     // ASCII
            (short) 0xA0, (short) 0xFF,     // Latin-1 Supplement (includes U+00B7 middle dot)
            (short) 0x2000, (short) 0x206F, // General Punctuation: — ‘ ’ “ ” …
            (short) 0x3000, (short) 0x303F, // CJK Symbols and Punctuation
            (short) 0x4E00, (short) 0x9FFF, // CJK Unified Ideographs
            (short) 0xFF00, (short) 0xFFEF, // Halfwidth and Fullwidth Forms
            (short) 0                       // range terminator
    };

    private CnFonts() {}

    public static void install(ImFontConfig defaultConfig) {
        try {
            if (defaultConfig == null) {
                return;
            }
            byte[] ttf = loadResource();
            if (ttf == null) {
                return;
            }
            ImGuiIO io = ImGui.getIO();
            float size = defaultConfig.getSizePixels() > 0f
                    ? defaultConfig.getSizePixels()
                    : 13.0f;
            ImFontConfig cfg = new ImFontConfig();
            try {
                cfg.setMergeMode(true);
                cfg.setSizePixels(size);
                cfg.setGlyphRanges(GLYPH_RANGES);
                ImFontAtlas atlas = io.getFonts();
                atlas.addFontFromMemoryTTF(ttf, size, cfg);
            } finally {
                cfg.destroy();
            }
        } catch (Throwable t) {
            if (!warnLogged) {
                warnLogged = true;
                local.zbselective.RuntimeState.log("ImGui CJK font install failed (dialog stays English-safe): " + t);
            }
        }
    }

    private static byte[] loadResource() {
        try (InputStream in = CnFonts.class.getResourceAsStream(FONT_RESOURCE)) {
            return in == null ? null : in.readAllBytes();
        } catch (Throwable t) {
            return null;
        }
    }
}
