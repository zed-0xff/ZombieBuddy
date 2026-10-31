package me.zed_0xff.zombie_buddy.patches.experimental;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

import me.zed_0xff.zombie_buddy.*;

public class PreMain {
    public static void premain(String[] args) {
        JavaStateDumper.init();
        WatchesAPI.init();

        // Configure HTTP server host, port and timeout from Agent.arguments (if provided)
        String hostValue    = Agent.arguments.get("lua_server_host");
        String portValue    = Agent.arguments.get("lua_server_port");
        String timeoutValue = Agent.arguments.get("lua_task_timeout");
        String tokenValue   = Agent.arguments.get("lua_server_token");
        boolean allowRemote = Boolean.parseBoolean(Agent.arguments.getOrDefault("lua_server_allow_remote", "false"));

        if (!Utils.isBlank(portValue)) {
            try {
                int serverPort;
                boolean isRandomPort = false;
                if ("random".equalsIgnoreCase(portValue)) {
                    serverPort = 0; // 0 means random port
                    isRandomPort = true;
                    Logger.info("Using random port for HTTP server");
                } else {
                    serverPort = Integer.parseInt(portValue);
                    if (serverPort == 0) {
                        isRandomPort = true;
                        Logger.info("Using random port for HTTP server");
                    }
                }

                HttpServer.g_verbosity = Logger.getLevel();

                String bindHost = (!Utils.isBlank(hostValue)) ? hostValue : "127.0.0.1";
                if (!allowRemote && !HttpAuth.isLoopbackHost(bindHost)) {
                    Logger.warn("Refusing non-loopback HTTP API bind '" + bindHost
                        + "' without lua_server_allow_remote=true; using 127.0.0.1");
                    bindHost = "127.0.0.1";
                }
                if (!HttpAuth.isLoopbackHost(bindHost)) {
                    Logger.warn("HTTP API is network-accessible on " + bindHost
                        + "; the bearer token is sent over cleartext HTTP. Use a trusted network/VPN.");
                }

                String requestedToken = tokenValue != null ? tokenValue.trim() : "";
                boolean generatedToken = !HttpAuth.isValidToken(requestedToken);
                final String authToken = generatedToken ? HttpAuth.generateToken() : requestedToken;
                if (generatedToken && !requestedToken.isEmpty()) {
                    Logger.warn("lua_server_token must be at least " + HttpAuth.MIN_TOKEN_LENGTH
                        + " printable characters; generating a random token");
                }
                HttpServer.setAuthToken(authToken);

                HttpServer httpServer = new HttpServer(bindHost, serverPort, isRandomPort);
                httpServer.start();

                // The game cache directory is not guaranteed to be available during premain.
                Callbacks.onGameInitComplete.register(() -> {
                    if (!writeTokenFile(authToken)) {
                        if (generatedToken) {
                            HttpServer.setAuthToken(null);
                            Logger.error("HTTP API /lua and /log disabled: could not write token file");
                        } else {
                            Logger.warn("Could not write token file; use lua_server_token directly");
                        }
                    }
                    HttpServer server = HttpServer.getInstance();
                    if (server != null && server.wasRandomPort()) {
                        writePortFile(server.getPort());
                    }
                });
            } catch (NumberFormatException e) {
                Logger.error("invalid server_port value: " + portValue);
            } catch (Exception e) {
                Logger.error("failed to start HTTP server: " + e.getMessage());
            }
        }

        // Configure Lua task timeout from Agent.arguments (if provided)
        if (!Utils.isBlank(timeoutValue)) {
            try {
                HttpServer.luaTaskTimeoutMs = Long.parseLong(timeoutValue);
                Logger.info("Lua task timeout set to " + HttpServer.luaTaskTimeoutMs + "ms");
            } catch (NumberFormatException e) {
                Logger.error("invalid lua_task_timeout value: " + timeoutValue);
            }
        }
    }

    private static void writePortFile(int port) {
        try {
            Path dir = tokenDir();
            Files.createDirectories(dir);
            Path portFile = dir.resolve("zbLuaAPI.txt");
            Files.writeString(portFile, String.valueOf(port));
            Logger.info("Wrote random API port " + port + " to " + portFile.toAbsolutePath());
        } catch (Exception e) {
            Logger.error("Failed to write port file: " + e.getMessage());
            Logger.printStackTrace(e);
        }
    }

    private static Path tokenDir() {
        String cacheDir = Utils.getCacheDir();
        return !Utils.isBlank(cacheDir) ? Path.of(cacheDir) : Agent.configDir();
    }

    /**
     * Writes the HTTP API bearer token with owner-only permissions where supported.
     * Returns false when the token could not be persisted.
     */
    private static boolean writeTokenFile(String token) {
        Path temp = null;
        try {
            Path dir = tokenDir();
            Files.createDirectories(dir);
            Path target = dir.resolve("zbLuaAPI.token");

            try {
                temp = Files.createTempFile(dir, ".zbLuaAPI.token-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                // Non-POSIX filesystem (e.g. Windows): rely on the user profile ACL.
                temp = Files.createTempFile(dir, ".zbLuaAPI.token-", ".tmp");
            }

            Files.writeString(temp, token, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Best effort on non-POSIX filesystems.
            }

            Logger.info("Wrote HTTP API token to " + target.toAbsolutePath());
            return true;
        } catch (Exception e) {
            Logger.error("Failed to write HTTP API token file: " + e.getMessage());
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
            return false;
        }
    }
}
