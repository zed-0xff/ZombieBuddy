package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.*;

import zombie.core.Core;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

public class Patch_watermark {
    public static boolean _draw_watermark = false;

    @Patch(className = "zombie.GameWindow", methodName = "init")
    class Patch_GameWindow_init {
        @Patch.OnEnter
        static void enter() {
            _draw_watermark = true;
        }

        @Patch.OnExit
        static void exit() {
            _draw_watermark = false;
            Callbacks.onGameInitComplete.run();
        }
    }

    public static void draw_watermark() {
        String watermark = "ZB " + ZombieBuddy.getVersion();
        String newVersion = SelfUpdater.getNewVersion();
        if (newVersion != null) {
            watermark += " (New version " + newVersion + " installed. Please restart the game)";
        }
        var font    = UIFont.Small;
        var textMgr = TextManager.instance;
        var textW   = textMgr.MeasureStringX(font, watermark);
        var textH   = textMgr.MeasureStringY(font, watermark);
        var scrW    = Core.getInstance().getScreenWidth();
        var scrH    = Core.getInstance().getScreenHeight();
        textMgr.DrawString(font, scrW - textW, scrH - textH, watermark, 1.0, 1.0, 1.0, 0.25);
    }

    @Patch(className = "zombie.core.Core", methodName = "EndFrameUI")
    class Patch_Core_EndFrameUI {
        @Patch.OnEnter
        static void enter() {
            if (_draw_watermark)
                draw_watermark();
        }
    }
    
    // @Patch(className = "zombie.gameStates.MainScreenState", methodName = "renderBackground")
    // class Patch_MainScreenState_renderBackground {
    //     @Patch.OnExit
    //     static void exit() {
    //         draw_watermark();
    //     }
    // }
}
