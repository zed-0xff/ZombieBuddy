package me.zed_0xff.zombie_buddy.patches.experimental;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;

import se.krka.kahlua.vm.KahluaThread;
import zombie.Lua.LuaManager;

import me.zed_0xff.zombie_buddy.Accessor;
import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.ZombieBuddy;
import me.zed_0xff.zombie_buddy.patches.experimental.http.LuaHandler;
import me.zed_0xff.zombie_buddy.patches.experimental.http.LogHandler;
import me.zed_0xff.zombie_buddy.patches.experimental.http.RootHandler;
import me.zed_0xff.zombie_buddy.patches.experimental.http.StatusHandler;
import me.zed_0xff.zombie_buddy.patches.experimental.http.VersionHandler;

public class HttpServer {
    private com.sun.net.httpserver.HttpServer server;
    private final String host;
    private int port;
    private boolean wasRandomPort;
    private static HttpServer instance;
    
    // Timeout for waiting for Lua task execution (in milliseconds)
    public static long luaTaskTimeoutMs = 1000;
    public static int g_verbosity = 0;
    
    // Queue for Lua tasks to be executed on the main thread (for dedicated servers)
    private static final ConcurrentLinkedQueue<LuaTask> luaTaskQueue = new ConcurrentLinkedQueue<>();

    /** Cached result of one-time check for debugOwnerThread field on Lua thread class. 0 = not inited, 1 = present, -1 = absent. */
    private static volatile int hasDebugOwnerThreadField = 0;
    /** Cached Field for debugOwnerThread, set once when present. */
    private static volatile Field cachedDebugOwnerThreadField = null;

    /** Request header: comma-separated global variable names to capture on error; their values are added to JSON as errorGlobals. The Lua code (e.g. ZBSpec.lua) sets those globals; we only read them when an error occurs. */
    private static final String HEADER_ERROR_GLOBALS = "X-ZombieBuddy-Error-Globals";

    private static void ensureDebugOwnerThreadCache() {
        if (hasDebugOwnerThreadField != 0) return;
        Object thread = LuaManager.thread;
        if (thread == null) return;
        synchronized (HttpServer.class) {
            if (hasDebugOwnerThreadField != 0) return;
            // field is on KahluaThread (B42+); search hierarchy in case runtime class is a subclass
            Field f = Accessor.findField(thread.getClass(), "debugOwnerThread");
            if (f != null) {
                cachedDebugOwnerThreadField = f;
                hasDebugOwnerThreadField = 1;
            } else {
                hasDebugOwnerThreadField = -1;  // B41 or field absent
            }
        }
    }

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
     * 
     * Only executes tasks if we're on the correct Lua thread (debugOwnerThread).
     * This handles the case where the Lua thread owner changes during loading.
     */
    public static void maybeRunLuaTasks() {
        // Only poll if we're on the correct Lua thread
        // During client loading, debugOwnerThread is the loader thread, not gameThread
        if (!isOnLuaThread()) {
            return;
        }
        
        runLuaTasks();
    }

    /**
     * Runs all Lua tasks in the queue.
     * Be sure to call this from the correct Lua thread ONLY.
     */
    public static void runLuaTasks() {
        LuaTask task;
        while ((task = luaTaskQueue.poll()) != null) {
            task.execute();
        }
    }
    
    private static boolean isOnLuaThread() {
        Object thread = LuaManager.thread;
        if (thread == null) return false;

        ensureDebugOwnerThreadCache();
        if (hasDebugOwnerThreadField == -1) {
            // B41 has no debugOwnerThread field, so we don't know if we're on the correct thread
            return false;
        }
        if (hasDebugOwnerThreadField == 1 && cachedDebugOwnerThreadField != null) {
            Object owner = Accessor.tryGet(thread, cachedDebugOwnerThreadField, null);
            return owner == Thread.currentThread();
        }
        return false;
    }
    
    public static void runOnLuaThread(Runnable task) throws Exception {
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
        this("127.0.0.1", port, isRandomPort);
    }

    public HttpServer(String host, int port, boolean isRandomPort) {
        this.host = host != null && !host.isEmpty() ? host : "127.0.0.1";
        this.port = port;
        this.wasRandomPort = isRandomPort;
    }

    public void start() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(host, port), 0);
        // Get the actual port that was bound (important for port 0 = random)
        port = server.getAddress().getPort();
        
        server.createContext("/", new RootHandler());
        server.createContext("/status", new StatusHandler());
        server.createContext("/version", new VersionHandler());
        server.createContext("/lua", new LuaHandler());
        server.createContext("/log", new LogHandler());
        server.setExecutor(null);
        server.start();
        
        instance = this;
        Logger.info("HTTP server started at http://" + host + ":" + port);
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
            Logger.info("HTTP server stopped");
        }
    }

    public static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        sendResponse(exchange, statusCode, response, "text/plain; charset=UTF-8");
    }

    public static void sendJsonResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        sendResponse(exchange, statusCode, response, "application/json; charset=UTF-8");
    }

    public static void sendResponse(HttpExchange exchange, int statusCode, String response, String contentType) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void logRequest(HttpExchange exchange) {
        if (g_verbosity > 0) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().toString();
            Logger.info(method + " " + path);
        }
    }

    public static int parseIntParam(String query, String name, int defaultValue) {
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

    public static String parseStringParam(String query, String name, String defaultValue) {
        if (query == null || query.isEmpty()) {
            return defaultValue;
        }
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) {
                // URL decode the value
                try {
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return defaultValue;
    }

    /** Parses a boolean query param: "true"/"1" => true, "false"/"0" => false, else default. */
    public static boolean parseBoolParam(String query, String name, boolean defaultValue) {
        String s = parseStringParam(query, name, defaultValue ? "true" : "false");
        if (s == null || s.isEmpty()) return defaultValue;
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        return defaultValue;
    }

    private static String sanitizeErrorGlobalName(String name) {
        if (name == null || !name.trim().matches("[a-zA-Z_][a-zA-Z0-9_]*")) return null;
        return name.trim();
    }

    /** Parse X-ZombieBuddy-Error-Globals header: names of globals whose values to include in error response (values are set by Lua, e.g. ZBSpec.lua sets ZBSpec_currentTest). Comma-separated, multiple headers allowed. Returns unique sanitized names. */
    public static List<String> parseErrorGlobalNames(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get(HEADER_ERROR_GLOBALS);
        Set<String> names = new LinkedHashSet<>();
        if (values != null) {
            for (String v : values) {
                for (String part : v.split(",")) {
                    String s = sanitizeErrorGlobalName(part.trim());
                    if (s != null) names.add(s);
                }
            }
        }
        return new ArrayList<>(names);
    }

    public static String[] extractErrorsFromList(int errorListSizeBefore) {
        int errorListSizeAfter = KahluaThread.m_errors_list.size();
        if (errorListSizeAfter <= errorListSizeBefore) {
            return null;
        }
        String[] errors = new String[errorListSizeAfter - errorListSizeBefore];
        for (int i = errorListSizeBefore; i < errorListSizeAfter; i++) {
            errors[i - errorListSizeBefore] = KahluaThread.m_errors_list.get(i);
        }
        return errors;
    }

    /** Parse multipart-like Lua format: ---FILE:filename---\ncontent\n---FILE:... Returns list of [filename, content] pairs. */
    public static java.util.List<String[]> parseMultipartLua(String body, String defaultChunkName) {
        java.util.List<String[]> chunks = new java.util.ArrayList<>();
        String delimiter = "---FILE:";
        
        if (!body.contains(delimiter)) {
            // No multipart format, treat as single chunk
            chunks.add(new String[] { defaultChunkName, body });
            return chunks;
        }
        
        String[] parts = body.split("---FILE:");
        for (String part : parts) {
            if (part.trim().isEmpty()) continue;
            
            int endOfName = part.indexOf("---\n");
            if (endOfName == -1) {
                endOfName = part.indexOf("---\r\n");
            }
            
            if (endOfName > 0) {
                String fileName = part.substring(0, endOfName).trim();
                String content = part.substring(endOfName + (part.charAt(endOfName + 3) == '\r' ? 5 : 4));
                chunks.add(new String[] { fileName, content });
            } else {
                // Malformed, use as-is with default name
                chunks.add(new String[] { defaultChunkName, part });
            }
        }
        
        return chunks;
    }

}
