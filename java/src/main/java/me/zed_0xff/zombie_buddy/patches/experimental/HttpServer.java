package me.zed_0xff.zombie_buddy.patches.experimental;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.VarHandle;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import se.krka.kahlua.vm.KahluaThread;
import zombie.Lua.LuaManager;
import zombie.gameStates.GameLoadingState;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Reflect;
import me.zed_0xff.zombie_buddy.Utils;
import me.zed_0xff.zombie_buddy.patches.experimental.http.*;

public class HttpServer {
    private com.sun.net.httpserver.HttpServer server;
    private final String host;
    private int port;
    private boolean wasRandomPort;
    private static HttpServer instance;
    
    // Timeout for waiting for Lua task execution (in milliseconds)
    public static long luaTaskTimeoutMs = 5000;
    public static int g_verbosity = 0;
    
    // Queue for Lua tasks to be executed on the main thread (for dedicated servers)
    private static final ConcurrentLinkedQueue<LuaTask> luaTaskQueue = new ConcurrentLinkedQueue<>();
    private static final long LOADING_LUA_TASK_TIMEOUT_MS = 30_000L;
    private static VarHandle vh_gameThread = null;
    private static VarHandle vh_debugOwnerThread = null;
    private static boolean didResolveDebugOwnerThread = false;

    /** Request header: comma-separated global variable names to capture on error; their values are added to JSON as errorGlobals. The Lua code (e.g. ZBSpec.lua) sets those globals; we only read them when an error occurs. */
    private static final String HEADER_ERROR_GLOBALS = "X-ZombieBuddy-Error-Globals";

    private static class LuaTask {
        final Runnable task;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Throwable failure;

        LuaTask(Runnable task) {
            this.task = task;
        }

        void execute() {
            try {
                task.run();
            } catch (Throwable t) {
                failure = t;
            } finally {
                latch.countDown();
            }
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }
    
    private static Thread luaOwnerThread() {
        KahluaThread kt = LuaManager.thread;
        if (kt == null) {
            return null;
        }

        ensureDebugOwnerThreadCache();
        if (vh_debugOwnerThread == null) {
            return null;
        }

        return (Thread) vh_debugOwnerThread.get(kt);
    }

    private static void ensureDebugOwnerThreadCache() {
        if (didResolveDebugOwnerThread) {
            return;
        }

        synchronized (HttpServer.class) {
            if (didResolveDebugOwnerThread) {
                return;
            }

            vh_debugOwnerThread = Reflect.on(KahluaThread.class).getVarHandle(Thread.class, "debugOwnerThread");
            didResolveDebugOwnerThread = true;
        }
    }

    private static void ensureGameThreadCache() {
        if (vh_gameThread != null) {
            return;
        }

        synchronized (HttpServer.class) {
            if (vh_gameThread != null) {
                return;
            }

            vh_gameThread = Reflect.on("zombie.GameWindow").getVarHandle(Thread.class, "gameThread", "GameThread");
            if (vh_gameThread == null) {
                Logger.once.error("failed to get GameWindow.gameThread/GameThread");
            }
        }
    }

    private static Thread gameThread() {
        ensureGameThreadCache();
        if (vh_gameThread == null) {
            return null;
        }

        return (Thread) vh_gameThread.get();
    }

    private static Thread activeLoadingThread() {
        Thread loader = GameLoadingState.loader;
        return loader != null && loader.isAlive() ? loader : null;
    }

    /** True when the current thread may run queued HTTP Lua work. */
    private static boolean canDrainLuaQueue() {
        if (LuaManager.thread == null) {
            return false;
        }

        Thread current = Thread.currentThread();
        Thread owner = luaOwnerThread();
        if (owner != null && owner == current) {
            return true;
        }

        Thread loader = activeLoadingThread();
        if (loader != null) {
            return loader == current;
        }

        Thread game = gameThread();
        if (game != null && game == current && (owner == null || owner == game)) {
            return true;
        }

        // Dedicated server / no GameWindow: drain only on the recorded Lua owner thread.
        return owner != null && owner == current;
    }

    /**
     * Called from the game's main thread (client or server) to process queued Lua tasks.
     * Should be called from OnTick or similar.
     */
    public static void maybeRunLuaTasks() {
        if (luaTaskQueue.isEmpty()) {
            return;
        }

        if (!canDrainLuaQueue()) {
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
        return canDrainLuaQueue();
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
            long timeoutMs = effectiveLuaTaskTimeoutMs();
            if (!luaTask.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Timeout waiting for Lua task execution (" + timeoutMs + "ms). ");
            }

            if (luaTask.failure != null) {
                Throwable f = luaTask.failure;
                if (f instanceof Exception e) {
                    throw e;
                }

                throw new RuntimeException(f);
            }
        }
    }

    private static long effectiveLuaTaskTimeoutMs() {
        if (activeLoadingThread() != null) {
            return Math.max(luaTaskTimeoutMs, LOADING_LUA_TASK_TIMEOUT_MS);
        }

        return luaTaskTimeoutMs;
    }

    public HttpServer(int port, boolean isRandomPort) {
        this("127.0.0.1", port, isRandomPort);
    }

    public HttpServer(String host, int port, boolean isRandomPort) {
        this.host = !Utils.isBlank(host) ? host : "127.0.0.1";
        this.port = port;
        this.wasRandomPort = isRandomPort;
    }

    public void start() throws IOException {
        InetSocketAddress bindAddress = resolveBindAddress(host, port);
        server = com.sun.net.httpserver.HttpServer.create(bindAddress, 0);
        // Get the actual port that was bound (important for port 0 = random)
        port = server.getAddress().getPort();

        server.createContext("/", new RootHandler());
        server.createContext("/status", new StatusHandler());
        server.createContext("/version", new VersionHandler());
        server.createContext("/lua", new LuaHandler());
        server.createContext("/log", new LogHandler());
        // Default executor runs handlers on the dispatcher thread (blocks accept). Use a thread pool.
        server.setExecutor(httpExecutor());
        server.start();

        instance = this;
        Logger.info("HTTP server started at http://" + server.getAddress().getHostString() + ":" + port);
    }

    private static InetSocketAddress resolveBindAddress(String host, int port) {
        if ("0.0.0.0".equals(host) || "*".equals(host)) {
            return new InetSocketAddress(port);
        }

        return new InetSocketAddress(host, port);
    }

    private static ExecutorService httpExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "ZB-HTTP-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };

        return Executors.newCachedThreadPool(factory);
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
        if (Utils.isBlank(query)) {
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
        if (Utils.isBlank(query)) {
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
        if (Utils.isBlank(s)) return defaultValue;
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
