package me.zed_0xff.zombie_buddy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;

public class HttpServer {
    private com.sun.net.httpserver.HttpServer server;
    private int port;
    private boolean wasRandomPort;
    private static HttpServer instance;
    
    // Timeout for waiting for Lua task execution (in milliseconds)
    public static long luaTaskTimeoutMs = 1000;
    
    // Queue for Lua tasks to be executed on the main thread (for dedicated servers)
    private static final ConcurrentLinkedQueue<LuaTask> luaTaskQueue = new ConcurrentLinkedQueue<>();
    
    private static class LuaTask {
        final Runnable task;
        final CountDownLatch latch = new CountDownLatch(1);
        
        LuaTask(Runnable task) {
            this.task = task;
        }
        
        void execute() {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        }
        
        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }
    
    /**
     * Called from the game's main thread (client or server) to process queued Lua tasks.
     * Should be called from OnTick or similar.
     */
    public static void pollLuaTasks() {
        LuaTask task;
        while ((task = luaTaskQueue.poll()) != null) {
            task.execute();
        }
    }
    
    private static boolean isOnLuaThread() {
        return LuaManager.thread != null && 
               LuaManager.thread.debugOwnerThread == Thread.currentThread();
    }
    
    private static void runOnLuaThread(Runnable task) throws Exception {
        if (isOnLuaThread()) {
            // Already on the correct thread
            task.run();
        } else {
            // Queue and wait for the game's tick to execute it on the correct thread
            // This works for both client (IngameState.UpdateStuff) and server (ServerMap.preupdate)
            LuaTask luaTask = new LuaTask(task);
            luaTaskQueue.add(luaTask);
            if (!luaTask.await(luaTaskTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Timeout waiting for Lua task execution (" + luaTaskTimeoutMs + "ms)");
            }
        }
    }

    public HttpServer(int port, boolean isRandomPort) {
        this.port = port;
        this.wasRandomPort = isRandomPort;
    }

    public void start() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        // Get the actual port that was bound (important for port 0 = random)
        port = server.getAddress().getPort();
        
        server.createContext("/", new RootHandler());
        server.createContext("/status", new StatusHandler());
        server.createContext("/version", new VersionHandler());
        server.createContext("/lua", new LuaExecHandler());
        server.createContext("/log", new LogHandler());
        server.setExecutor(null);
        server.start();
        
        instance = this;
        System.out.println("[ZB] HTTP server started at http://127.0.0.1:" + port);
    }

    public int getPort() {
        return port;
    }

    public boolean wasRandomPort() {
        return wasRandomPort;
    }

    public static HttpServer getInstance() {
        return instance;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            System.out.println("[ZB] HTTP server stopped");
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        sendResponse(exchange, statusCode, response, "text/plain; charset=UTF-8");
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        sendResponse(exchange, statusCode, response, "application/json; charset=UTF-8");
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response, String contentType) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void logRequest(HttpExchange exchange) {
        if (Loader.g_verbosity > 0) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().toString();
            System.out.println("[ZB] " + method + " " + path);
        }
    }

    private static int parseIntParam(String query, String name, int defaultValue) {
        if (query == null || query.isEmpty()) {
            return defaultValue;
        }
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) {
                try {
                    return Integer.parseInt(kv[1]);
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logRequest(exchange);
            String response = "ZombieBuddy HTTP Server\n\nEndpoints:\n  /status - server status\n  /version - version info\n  /lua - POST lua code to execute\n  /log - GET last log lines (?lines=N, default 100)\n";
            sendResponse(exchange, 200, response);
        }
    }

    private static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logRequest(exchange);
            String response = "ok\n";
            sendResponse(exchange, 200, response);
        }
    }

    private static class VersionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logRequest(exchange);
            String response = ZombieBuddy.getFullVersionString() + "\n";
            sendResponse(exchange, 200, response);
        }
    }

    private static class LogHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logRequest(exchange);
            int lines = parseIntParam(exchange.getRequestURI().getQuery(), "lines", 100);
            String logPath = ZomboidFileSystem.instance.getCacheDir() + "/console.txt";
            
            try {
                String content = tailFile(logPath, lines);
                sendResponse(exchange, 200, content);
            } catch (Exception e) {
                sendResponse(exchange, 500, "Error reading log: " + e.getMessage() + "\n");
            }
        }

        private String tailFile(String path, int lines) throws IOException {
            try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
                long fileLength = file.length();
                if (fileLength == 0) {
                    return "";
                }

                List<String> result = new ArrayList<>();
                StringBuilder currentLine = new StringBuilder();
                long pos = fileLength - 1;

                while (pos >= 0 && result.size() < lines) {
                    file.seek(pos);
                    int ch = file.read();
                    if (ch == '\n') {
                        if (currentLine.length() > 0) {
                            result.add(currentLine.reverse().toString());
                            currentLine = new StringBuilder();
                        }
                    } else if (ch != '\r') {
                        currentLine.append((char) ch);
                    }
                    pos--;
                }

                if (currentLine.length() > 0 && result.size() < lines) {
                    result.add(currentLine.reverse().toString());
                }

                Collections.reverse(result);
                return String.join("\n", result) + "\n";
            }
        }
    }

    private static class LuaExecHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logRequest(exchange);
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed. Use POST.\n");
                return;
            }

            int depth = parseIntParam(exchange.getRequestURI().getQuery(), "depth", 1);

            String luaCode = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (luaCode.isEmpty()) {
                sendResponse(exchange, 400, "Empty request body\n");
                return;
            }

            AtomicReference<Object> resultRef = new AtomicReference<>();
            AtomicReference<String> errorRef = new AtomicReference<>();

            try {
                runOnLuaThread(() -> {
                    try {
                        int errorListSizeBefore = KahluaThread.m_errors_list.size();
                        LuaClosure closure = LuaCompiler.loadstring(luaCode, "http_exec", LuaManager.env);
                        LuaReturn luaReturn = LuaManager.caller.protectedCall(LuaManager.thread, closure, new Object[0]);
                        if (luaReturn.isSuccess()) {
                            if (!luaReturn.isEmpty()) {
                                resultRef.set(luaReturn.getFirst());
                            }
                        } else {
                            StringBuilder err = new StringBuilder();
                            
                            // Check if new errors were added to KahluaThread.m_errors_list
                            int errorListSizeAfter = KahluaThread.m_errors_list.size();
                            if (errorListSizeAfter > errorListSizeBefore) {
                                // Errors are added in pairs: first the exception, then the stack trace
                                // Look through new entries for one containing the actual error message
                                for (int i = errorListSizeBefore; i < errorListSizeAfter; i++) {
                                    String entry = KahluaThread.m_errors_list.get(i);
                                    if (entry != null && entry.contains("Exception")) {
                                        // Extract the error message from the exception stack trace
                                        String[] lines = entry.split("\n");
                                        if (lines.length > 0) {
                                            String firstLine = lines[0].trim();
                                            // Format: "java.lang.RuntimeException: Tried to call nil"
                                            int colonIdx = firstLine.indexOf(": ");
                                            if (colonIdx > 0) {
                                                err.append(firstLine.substring(colonIdx + 2));
                                            } else {
                                                err.append(firstLine);
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                            
                            // If no error from m_errors_list, try other sources
                            if (err.length() == 0) {
                                String errorStr = luaReturn.getErrorString();
                                if (errorStr != null && !errorStr.trim().isEmpty()) {
                                    err.append(errorStr.trim());
                                }
                            }
                            
                            // Add stack trace if not already included
                            String stackTrace = luaReturn.getLuaStackTrace();
                            if (stackTrace != null && !stackTrace.trim().isEmpty()) {
                                String trimmedStack = stackTrace.trim();
                                if (!err.toString().contains(trimmedStack)) {
                                    if (err.length() > 0) err.append("\n");
                                    err.append(trimmedStack);
                                }
                            }
                            
                            if (err.length() == 0) {
                                err.append("Unknown Lua error");
                            }
                            errorRef.set(err.toString());
                        }
                    } catch (Exception e) {
                        StringBuilder err = new StringBuilder();
                        err.append(e.getClass().getName()).append(": ").append(e.getMessage());
                        for (StackTraceElement ste : e.getStackTrace()) {
                            err.append("\n  at ").append(ste.toString());
                        }
                        errorRef.set(err.toString());
                    }
                });
            } catch (Exception e) {
                StringBuilder err = new StringBuilder();
                err.append(e.getClass().getName()).append(": ").append(e.getMessage());
                for (StackTraceElement ste : e.getStackTrace()) {
                    err.append("\n  at ").append(ste.toString());
                }
                errorRef.set(err.toString());
            }

            if (errorRef.get() != null) {
                sendResponse(exchange, 500, errorRef.get() + "\n");
            } else {
                String json = LuaJson.toJson(resultRef.get(), depth);
                sendJsonResponse(exchange, 200, json);
            }
        }
    }
}
