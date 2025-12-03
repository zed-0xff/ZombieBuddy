package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.implementation.bind.annotation.*;

import java.util.concurrent.Callable;

import zombie.core.Core;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

public class Patch_Loading_Screen {
    public static final String WATERMARK = "[ZB]";
    static boolean m_draw_watermark = false;

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
    public class Patch_GameWindow {
        public static void intercept(@SuperCall Callable<?> original) throws Exception {
            m_draw_watermark = true;
            original.call();
            m_draw_watermark = false;

            // XXX
            var exit = zombie.GameWindow.class.getMethod("exit");
            exit.invoke(null);
        }
    }
    
    @Patch(className = "zombie.ui.TextManager", methodName = "DrawStringCentre")
    public class Patch_DrawStringCentre {
        public static void intercept(@SuperCall Callable<?> original) throws Exception {
            original.call();
            if (m_draw_watermark)
                draw_watermark();
        }
    }
    
    @Patch(className = "zombie.gameStates.MainScreenState", methodName = "renderBackground")
    public class Patch_MainScreenState {
        public static void intercept(@SuperCall Callable<?> original) throws Exception {
            original.call();
            draw_watermark();
        }
    }
}

