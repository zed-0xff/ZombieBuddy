package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import zombie.core.Core;
import zombie.ZomboidFileSystem;

public class GameUtils {
    public static boolean g_exit_after_game_init = false;

    public static void onGameInitComplete() {
        // Write the HTTP server port to a file ONLY if it was random
        HttpServer httpServer = HttpServer.getInstance();
        if (httpServer != null && httpServer.wasRandomPort()) {
            writePortFile(httpServer.getPort());
        }

        if (g_exit_after_game_init) {
            System.out.println("[ZB] Exiting after game init as requested.");
            Core.getInstance().quit();
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
}
