package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Loader;
import net.bytebuddy.asm.Advice;

import zombie.core.Core;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

public class Patch_Loading_Screen {
    public static final String WATERMARK = "[ZB]";
    public static boolean m_draw_watermark = false;

    public static void draw_watermark() {
        var font = UIFont.Small;
        var textMgr = TextManager.instance;
        var textW = textMgr.MeasureStringX(font, WATERMARK);
        var textH = textMgr.MeasureStringY(font, WATERMARK);
        var scrW = Core.getInstance().getScreenWidth();
        var scrH = Core.getInstance().getScreenHeight();
        textMgr.DrawString(font, scrW - textW, scrH - textH, WATERMARK, 1.0, 1.0, 1.0, 0.25);
    }

    @Patch(className = "zombie.GameWindow", methodName = "init")
    class Patch_GameWindow {
        @Advice.OnMethodEnter
        static void enter() {
            m_draw_watermark = true;
        }

        @Advice.OnMethodExit
        static void exit() {
            m_draw_watermark = false;

            if (Loader.g_exit_after_game_load) {
                System.out.println("[ZB] Exiting after loading as requested.");
                Core.getInstance().quit();
            }
        }
    }
    
    @Patch(className = "zombie.ui.TextManager", methodName = "DrawStringCentre")
    class Patch_DrawStringCentre {
        @Advice.OnMethodExit
        static void exit() {
            if (m_draw_watermark)
                draw_watermark();
        }
    }
    
    @Patch(className = "zombie.gameStates.MainScreenState", methodName = "renderBackground")
    class Patch_MainScreenState {
        @Advice.OnMethodExit
        static void exit() {
            draw_watermark();
        }
    }
}

