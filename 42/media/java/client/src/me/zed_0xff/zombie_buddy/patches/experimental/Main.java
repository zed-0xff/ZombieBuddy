package me.zed_0xff.zombie_buddy.patches.experimental;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import me.zed_0xff.zombie_buddy.Agent;
import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Hooks;
import zombie.ZomboidFileSystem;

public class Main {
    public static void main(String[] args) {
        Exposer.exposeClassToLua(ZBPacketLog.class);

        // Configure HTTP server port and timeout from Agent.arguments (if provided)
        String portValue = Agent.arguments.get("lua_server_port");
        String timeoutValue = Agent.arguments.get("lua_task_timeout");

        if (portValue != null && !portValue.isEmpty()) {
            try {
                int serverPort;
                boolean isRandomPort = false;
                if ("random".equalsIgnoreCase(portValue)) {
                    serverPort = 0; // 0 means random port
                    isRandomPort = true;
                    System.out.println("[ZB] Using random port for HTTP server");
                } else {
                    serverPort = Integer.parseInt(portValue);
                    if (serverPort == 0) {
                        isRandomPort = true;
                        System.out.println("[ZB] Using random port for HTTP server");
                    }
                }
                HttpServer httpServer = new HttpServer(serverPort, isRandomPort);
                httpServer.start();

                // Register hook to write the random port file on game init
                if (isRandomPort) {
                    Hooks.register("onGameInitComplete", () -> {
                        HttpServer server = HttpServer.getInstance();
                        if (server != null && server.wasRandomPort()) {
                            writePortFile(server.getPort());
                        }
                    });
                }
            } catch (NumberFormatException e) {
                System.err.println("[ZB] invalid server_port value: " + portValue);
            } catch (Exception e) {
                System.err.println("[ZB] failed to start HTTP server: " + e.getMessage());
            }
        }

        // Configure Lua task timeout from Agent.arguments (if provided)
        if (timeoutValue != null && !timeoutValue.isEmpty()) {
            try {
                HttpServer.luaTaskTimeoutMs = Long.parseLong(timeoutValue);
                System.out.println("[ZB] Lua task timeout set to " + HttpServer.luaTaskTimeoutMs + "ms");
            } catch (NumberFormatException e) {
                System.err.println("[ZB] invalid lua_task_timeout value: " + timeoutValue);
            }
        }
    }

    private static void writePortFile(int port) {
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
