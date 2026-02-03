package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.*;

import zombie.core.Core;
import zombie.ui.TextManager;
import zombie.ui.UIFont;
import zombie.ZomboidFileSystem;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class Patch_Loading_Screen {
    public static boolean m_draw_watermark = false;

    public static void draw_watermark() {
        String watermark = "ZB " + ZombieBuddy.getVersion();
        String newVersion = Loader.getNewVersion();
        if (newVersion != null) {
            watermark += " (New version " + newVersion + " installed. Please restart the game)";
        }
        var font = UIFont.Small;
        var textMgr = TextManager.instance;
        var textW = textMgr.MeasureStringX(font, watermark);
        var textH = textMgr.MeasureStringY(font, watermark);
        var scrW = Core.getInstance().getScreenWidth();
        var scrH = Core.getInstance().getScreenHeight();
        textMgr.DrawString(font, scrW - textW, scrH - textH, watermark, 1.0, 1.0, 1.0, 0.25);
    }

    @Patch(className = "zombie.GameWindow", methodName = "init")
    class Patch_GameWindow {
        @Patch.OnEnter
        static void enter() {
            m_draw_watermark = true;
        }

        @Patch.OnExit
        static void exit() {
            m_draw_watermark = false;

            // Write the HTTP server port to a file ONLY if it was random
            HttpServer httpServer = HttpServer.getInstance();
            if (httpServer != null && httpServer.wasRandomPort()) {
                writePortFile(httpServer.getPort());
            }

            if (Loader.g_exit_after_game_init) {
                System.out.println("[ZB] Exiting after game init as requested.");
                Core.getInstance().quit();
            }
        }
    }
    
    public static void writePortFile(int port) {
        try {
            String cacheDir = ZomboidFileSystem.instance.getCacheDir();
            File portFile = new File(cacheDir + File.separator + "zbLuaAPI.txt");
            try (FileWriter writer = new FileWriter(portFile)) {
                writer.write(String.valueOf(port));
            }
            System.out.println("[ZB] Wrote random API port " + port + " to " + portFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[ZB] Failed to write port file: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @Patch(className = "zombie.ui.TextManager", methodName = "DrawStringCentre")
    class Patch_DrawStringCentre {
        @Patch.OnExit
        static void exit() {
            if (m_draw_watermark)
                draw_watermark();
        }
    }
    
    @Patch(className = "zombie.gameStates.MainScreenState", methodName = "renderBackground")
    class Patch_MainScreenState {
        @Patch.OnExit
        static void exit() {
            draw_watermark();
        }
    }
}

