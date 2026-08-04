package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.ModFlags.*;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import zombie.GameTime;
import zombie.GameWindow;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.gameStates.GameState;
import zombie.gameStates.GameStateMachine;
import zombie.gameStates.IngameState;
import zombie.ui.TextManager;
import zombie.ui.UIFont;
import zombie.ui.UIManager;

@Exposer.LuaClass(name = "ZombieBuddy.Watermark")
public final class Watermark {
    private static final String ICON_RESOURCE = "zb_icon.png";

    private static final float GREEN_R  = 0.5f;
    private static final float GREEN_G  = 1.0f;
    private static final float GREEN_B  = 0.5f;
    private static final float YELLOW_R = 1.0f;
    private static final float YELLOW_G = 1.0f;
    private static final float YELLOW_B = 0.0f;
    private static final float GRAY     = 0.9f;

    private static final float DEFAULT_ALPHA = 0.4f;
    private static final int DEFAULT_TTL   = 200;
    private static final int FADE_DURATION = 10;

    private static Texture icon;
    private static boolean iconLoadAttempted;

    private static boolean _in_init = true;
    private static int _ttl         = DEFAULT_TTL;
    private static float _alpha     = DEFAULT_ALPHA;
    private static String _midLine  = null;

    private Watermark() {}

    static {
        Callbacks.onGameInitComplete.register(() -> { _in_init = false; });
        Callbacks.onEndFrameUI.register(Watermark::maybeDraw);
    }

    static void setMidLine(String text) {
        _midLine = text;
    }

    public static void setAlpha(float alpha) {
        _alpha = alpha;
    }

    public static void maybeDraw() {
        if (isEnabled()) {
            draw();
        } else if (_ttl > 0) {
            if (_ttl > FADE_DURATION && isIngameState()) {
                // fade immediately on game start
                _ttl = FADE_DURATION;
            }
            _ttl--;
            draw();
        }
    }

    private static float getCurrentAlpha() {
        float alpha = _alpha;
        if (!isEnabled() && _ttl < FADE_DURATION) {
            alpha *= _ttl / (float)FADE_DURATION;
        }
        return alpha;
    }

    private static boolean isEnabled() {
        return _in_init
                || !isIngameState();
                // || GameTime.isGamePaused() && !UIManager.isShowPausedMessage();
    }

    private static boolean _isIngameState_available = true;
    private static boolean isIngameState() {
        if (!_isIngameState_available) {
            return isIngameState_B41();
        }

        try {
            return GameWindow.isIngameState();
        } catch (NoSuchMethodError e) { // B41: java.lang.NoSuchMethodError: 'boolean zombie.GameWindow.isIngameState()'
            _isIngameState_available = false;
            return isIngameState_B41();
        }
    }

    private static VarHandle vh_states = null;
    private static boolean isIngameState_B41() {
        if (vh_states == null) {
            vh_states = Reflect.on(GameStateMachine.class).getVarHandle(ArrayList.class, "states", "States");
            if (vh_states == null) return false;
        }
        return (IngameState.instance != null && (GameWindow.states.current == IngameState.instance || 
                    ((ArrayList<GameState>)(vh_states.get(GameWindow.states))).contains(IngameState.instance)));
    }

    private static void draw() {
        String base = ZombieBuddy.getFullVersionString() + " loaded";
        String newVersion = SelfUpdater.getNewVersion();

        var font     = UIFont.Small;
        var textMgr  = TextManager.instance;
        var textH    = textMgr.MeasureStringY(font, base);
        var iconTex  = loadIcon();
        var iconSize = Utils.isHiRes() ? 128 : 64;
        var textX    = iconSize + 4;
        var textY    = 0;

        if (Utils.isMac()) {
            textY += 16; // camera brow
        }

        if (iconTex != null) {
            float alpha = getCurrentAlpha();
            SpriteRenderer.instance.renderi(iconTex, 0, 0, iconSize, iconSize, 1.0f, 1.0f, 1.0f, alpha, null);
        }

        if (newVersion != null) {
            var segments = new ArrayList<TextSegment>();
            segments.add(TextSegment.green(base));
            segments.add(TextSegment.yellow(" (New version " + newVersion + " installed. Please restart the game)"));
            drawSegments(font, textMgr, textX, textY, segments);
        } else {
            drawGreen(font, textMgr, textX, textY, base);
        }
        textY += textH;

        if (!Utils.isBlank(_midLine)) {
            drawYellow(font, textMgr, textX, textY, _midLine);
            textY += textH;
        }

        drawActiveJavaMods(font, textMgr, textX, textY, textH);
    }

    private static void drawActiveJavaMods(UIFont font, TextManager textMgr, int textX, int textY, int lineH) {
        List<Loader.JavaModLoadState> mods =
            Loader.getActiveJavaMods().stream()
            .sorted(Comparator.comparing(
                        Loader.JavaModLoadState::id,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                        ))
            .toList();

        String prefix = (mods.size() == 0 ? "No" : mods.size()) + " active Java mods";
        if (mods.isEmpty()) {
            drawGreen(font, textMgr, textX, textY, prefix);
            return;
        }

        int maxW = Math.max(80, Core.getInstance().getScreenWidth() - textX - 4);
        ArrayList<TextSegment> line = new ArrayList<>();
        String lineText = prefix + ": ";
        boolean lineHasMod = false;
        line.add(TextSegment.green(lineText));
        for (var mod : mods) {
            String idText = mod.id() + (mod.flags().has(MF_PRELOAD) ? " (preload)" : "");
            String text = (lineHasMod ? ", " : "") + idText;
            String candidate = lineText + text;
            if (lineHasMod && textMgr.MeasureStringX(font, candidate) > maxW) {
                drawSegments(font, textMgr, textX, textY, line);
                textY += lineH;
                line.clear();
                lineText = "";
                lineHasMod = false;
                text = idText;
            }
            boolean signed = mod.flags().has(MF_SIGNED);
            line.add(new TextSegment(text, signed ? GREEN_R : GRAY, signed ? GREEN_G : GRAY, signed ? GREEN_B : GRAY));
            lineText += text;
            lineHasMod = true;
        }
        drawSegments(font, textMgr, textX, textY, line);
    }

    private static void drawSegments(UIFont font, TextManager textMgr, int x, int y, ArrayList<TextSegment> segments) {
        int cursorX = x;
        float alpha = getCurrentAlpha();
        for (TextSegment segment : segments) {
            textMgr.DrawString(font, cursorX, y, segment.text, segment.r, segment.g, segment.b, alpha);
            cursorX += textMgr.MeasureStringX(font, segment.text);
        }
    }

    private static void drawGreen(UIFont font, TextManager textMgr, int x, int y, String text) {
        textMgr.DrawString(font, x, y, text, GREEN_R, GREEN_G, GREEN_B, getCurrentAlpha());
    }

    private static void drawYellow(UIFont font, TextManager textMgr, int x, int y, String text) {
        textMgr.DrawString(font, x, y, text, YELLOW_R, YELLOW_G, YELLOW_B, getCurrentAlpha());
    }

    private static Texture loadIcon() {
        if (iconLoadAttempted) {
            return icon;
        }
        iconLoadAttempted = true;
        try (InputStream in = Watermark.class.getClassLoader().getResourceAsStream(ICON_RESOURCE)) {
            if (in == null) {
                Logger.warn("Could not load watermark icon: resource not found: " + ICON_RESOURCE);
                return null;
            }
            icon = new Texture(ICON_RESOURCE, new BufferedInputStream(in), false);
        } catch (Exception e) {
            Logger.warn("Could not load watermark icon: " + e.getMessage());
            icon = null;
        }
        return icon;
    }

    private static final class ActiveJavaMod {
        final String name;
        final boolean signed;

        ActiveJavaMod(String name, boolean signed) {
            this.name = name;
            this.signed = signed;
        }
    }

    private static final class TextSegment {
        final String text;
        final float r;
        final float g;
        final float b;

        TextSegment(String text, float r, float g, float b) {
            this.text = text;
            this.r = r;
            this.g = g;
            this.b = b;
        }

        static TextSegment green(String text) {
            return new TextSegment(text, GREEN_R, GREEN_G, GREEN_B);
        }

        static TextSegment yellow(String text) {
            return new TextSegment(text, YELLOW_R, YELLOW_G, YELLOW_B);
        }
    }
}
