package me.zed_0xff.zombie_buddy.patches.experimental;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mjson.Json;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.luaj.kahluafork.compiler.FuncState;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.Coroutine;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;

import me.zed_0xff.zombie_buddy.Accessor;
import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.ZombieBuddy;

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
        server.createContext("/lua", new LuaExecHandler());
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

    private static void logRequest(HttpExchange exchange) {
        if (g_verbosity > 0) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().toString();
            Logger.info(method + " " + path);
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

    private static String parseStringParam(String query, String name, String defaultValue) {
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

    private static String sanitizeErrorGlobalName(String name) {
        if (name == null || !name.trim().matches("[a-zA-Z_][a-zA-Z0-9_]*")) return null;
        return name.trim();
    }

    /** Parse X-ZombieBuddy-Error-Globals header: names of globals whose values to include in error response (values are set by Lua, e.g. ZBSpec.lua sets ZBSpec_currentTest). Comma-separated, multiple headers allowed. Returns unique sanitized names. */
    private static List<String> parseErrorGlobalNames(HttpExchange exchange) {
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

    private static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logRequest(exchange);
            String response = "ZombieBuddy HTTP Server\n\nEndpoints:\n  /status - server status\n  /version - version info\n  /lua - POST lua code to execute (if returns job_*, waits for async completion)\n  /log - GET last log lines (?lines=N, default 100)\n";
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

            String query = exchange.getRequestURI().getQuery();
            int depth = parseIntParam(query, "depth", 1);
            String chunkName = parseStringParam(query, "chunkname", "http_exec");
            boolean rawCall = parseIntParam(query, "raw", 0) == 1;
            boolean sandbox = !"false".equalsIgnoreCase(parseStringParam(query, "sandbox", "true"));

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.isEmpty()) {
                sendResponse(exchange, 400, "Empty request body\n");
                return;
            }

            // Parse multipart-like format: ---FILE:filename---\ncontent\n---FILE:...
            // Each file is executed with its own chunkname for correct line numbers
            final java.util.List<String[]> chunks = parseMultipartLua(body, chunkName);

            AtomicReference<Object> resultRef = new AtomicReference<>();
            AtomicReference<Json> errorPayloadRef = new AtomicReference<>();
            final Map<String, Object> errorGlobalValues = new HashMap<>();
            AtomicInteger errCode = new AtomicInteger(0);
            AtomicInteger errorListSizeBeforeRef = new AtomicInteger(-1);
            final List<String> errorGlobalNames = parseErrorGlobalNames(exchange);

            try {
                runOnLuaThread(() -> {
                    // Set the file info for better stack traces
                    String prevFile = FuncState.currentFile;
                    String prevFullFile = FuncState.currentfullFile;
                    // Env used for execution (sandbox or global); declared here so finally can read error globals from it
                    se.krka.kahlua.vm.KahluaTable sharedEnv = LuaManager.env;
                    try {
                        int errorListSizeBefore = KahluaThread.m_errors_list.size();
                        errorListSizeBeforeRef.set(errorListSizeBefore);
                        
                        // Use sandbox (request-scoped env) unless sandbox=false
                        // Sandbox: inherits from _G for reads; writes stay in request env.
                        // Override _G in the sandbox so that _G.foo = x writes to sandbox, not real _G.
                        if (sandbox) {
                            sharedEnv = LuaManager.platform.newTable();
                            se.krka.kahlua.vm.KahluaTable mt = LuaManager.platform.newTable();
                            mt.rawset("__index", LuaManager.env);
                            sharedEnv.setMetatable(mt);
                            sharedEnv.rawset("_G", sharedEnv);
                        }
                        
                        // Execute all chunks in sequence, same Lua context
                        LuaClosure closure = null;
                        for (String[] chunk : chunks) {
                            String fileName = chunk[0];
                            String luaCode = chunk[1];
                            FuncState.currentFile = fileName;
                            FuncState.currentfullFile = fileName;
                            closure = LuaCompiler.loadstring(luaCode, fileName, sharedEnv);
                            
                            if (chunks.indexOf(chunk) < chunks.size() - 1) {
                                // Not the last chunk - execute immediately (spec_helper, etc.)
                                LuaReturn ret = LuaManager.caller.protectedCall(LuaManager.thread, closure, new Object[0]);
                                if (!ret.isSuccess()) {
                                    errCode.set(1);
                                    errorPayloadRef.set(serializeLuaReturn(ret, errorListSizeBefore));
                                    return;
                                }
                            }
                        }
                        
                        // Last chunk uses the configured execution mode (raw or protected)
                        
                        if (rawCall) {
                            // Coroutine-based call - allows yielding
                            // Save current coroutine to restore later
                            Coroutine originalCoroutine = LuaManager.thread.getCurrentCoroutine();
                            
                            // Create a new coroutine with canYield=true
                            Coroutine co = new Coroutine(
                                LuaManager.thread.getPlatform(),
                                LuaManager.thread.getEnvironment()
                            );
                            
                            // Set up the stack properly - closure at base, arguments after
                            // For no arguments: closure at [0], localBase=1, returnBase=0, nArguments=0
                            co.objectStack[0] = closure;
                            co.setTop(1);  // stack has 1 item (the closure)
                            
                            // Push call frame with canYield=true (last two params: localCall, insideCoroutine)
                            // localBase=1 (after closure), returnBase=0 (where closure is), nArguments=0
                            se.krka.kahlua.vm.LuaCallFrame callFrame = co.pushNewCallFrame(closure, null, 1, 0, 0, true, true);
                            callFrame.init();
                            
                            // Set up coroutine parent relationship and thread
                            co.resume(originalCoroutine);
                            LuaManager.thread.currentCoroutine = co;
                            
                            // Run until yield or completion - use reflection since luaMainloop is private
                            Accessor.callNoArg(LuaManager.thread, "luaMainloop");
                            
                            // Get result from returnBase (0)
                            if (co.getTop() > 0) {
                                resultRef.set(co.objectStack[0]);
                            }
                            
                            // Restore original coroutine
                            LuaManager.thread.currentCoroutine = originalCoroutine;
                        } else {
                            // Protected call - safe but can't yield
                            LuaReturn luaReturn = LuaManager.caller.protectedCall(LuaManager.thread, closure, new Object[0]);
                            
                            if (luaReturn.isSuccess()) {
                                if (!luaReturn.isEmpty()) {
                                    resultRef.set(luaReturn.getFirst());
                                }
                            } else {
                                errCode.set(1);
                                errorPayloadRef.set(serializeLuaReturn(luaReturn, errorListSizeBefore));
                            }
                        }
                    } catch (Exception e) {
                        // Inside lambda: Lua/Java errors during execution (errCode 1 or 2). Must handle here so finally can capture error globals from sharedEnv.
                        Throwable cause = e;
                        while (cause != null) {
                            if (cause instanceof se.krka.kahlua.vm.KahluaException) {
                                errCode.set(1);  // Use code 1 so it formats as luaReturn
                                errorPayloadRef.set(serializeKahluaException((se.krka.kahlua.vm.KahluaException) cause, errorListSizeBeforeRef.get()));
                                break;
                            }
                            cause = cause.getCause();
                        }
                        if (errorPayloadRef.get() == null) {
                            errCode.set(2);
                            errorPayloadRef.set(serializeJavaException(e));
                        }
                    } finally {
                        if (errorPayloadRef.get() != null && !errorGlobalNames.isEmpty()) {
                            for (String name : errorGlobalNames) {
                                Object value = sharedEnv.rawget(name);
                                if (value != null) errorGlobalValues.put(name, value);
                            }
                        }
                        FuncState.currentFile = prevFile;
                        FuncState.currentfullFile = prevFullFile;
                    }
                });
            } catch (Exception e) {
                // Outside lambda: runOnLuaThread failed (e.g. timeout when not on Lua thread). errCode 3.
                errCode.set(3);
                errorPayloadRef.set(serializeJavaException(e));
            }

            if (errorPayloadRef.get() != null) {
                Json root = Json.object();
                int code = errCode.get();
                root.set("err_code", code);
                if (code == 1) {
                    root.set("luaReturn", errorPayloadRef.get());
                } else {
                    root.set("javaException", errorPayloadRef.get());
                    String[] errors = extractErrorsFromList(errorListSizeBeforeRef.get());
                    root.set("kahluaErrors", errors != null ? Json.make(Arrays.asList(errors)) : Json.nil());
                }
                if (!errorGlobalValues.isEmpty()) {
                    root.set("errorGlobals", LuaJson.toJsonTree(errorGlobalValues));
                }
                sendJsonResponse(exchange, 500, root.toString());
                return;
            }
            
            sendJsonResponse(exchange, 200, LuaJson.toJson(resultRef.get(), depth));
        }
    }

    private static Json serializeJavaException(Throwable ex) {
        Json o = Json.object();
        o.set("className", ex.getClass().getName());
        String message = ex.getMessage();
        if (message == null || message.isEmpty()) message = ex.toString();
        StringBuilder fullMessage = new StringBuilder(message);
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            fullMessage.append(" Caused by: ");
            String cm = cause.getMessage();
            fullMessage.append(cm != null && !cm.isEmpty() ? cm : cause.toString());
        }
        o.set("message", fullMessage.toString());
        StackTraceElement[] stack = ex.getStackTrace();
        if (stack != null && stack.length > 0) {
            StackTraceElement frame = stack[0];
            o.set("file", frame.getFileName());
            o.set("line", frame.getLineNumber());
            o.set("method", frame.getMethodName());
            Json stackTrace = Json.array();
            for (StackTraceElement f : stack) {
                String fn = f.getFileName();
                stackTrace.add(f.getClassName() + "." + f.getMethodName() + "(" + (fn != null ? fn : "?") + ":" + f.getLineNumber() + ")");
            }
            o.set("stackTrace", stackTrace);
        }
        return o;
    }

    private static Json serializeKahluaException(se.krka.kahlua.vm.KahluaException ex, int errorListSizeBefore) {
        Json o = Json.object();
        o.set("errorString", ex.getMessage());
        String[] errors = extractErrorsFromList(errorListSizeBefore);
        o.set("kahluaErrors", errors != null ? Json.make(Arrays.asList(errors)) : Json.nil());
        return o;
    }

    private static Json serializeLuaReturn(LuaReturn luaReturn, int errorListSizeBefore) {
        Json o = Json.object();
        o.set("errorString", luaReturn.getErrorString());
        o.set("luaStackTrace", luaReturn.getLuaStackTrace());
        Object errorObj = luaReturn.getErrorObject();
        o.set("errorObject", errorObj != null ? String.valueOf(errorObj) : Json.nil());
        RuntimeException javaEx = luaReturn.getJavaException();
        o.set("javaException", javaEx != null ? serializeJavaException(javaEx) : Json.nil());
        String[] errors = extractErrorsFromList(errorListSizeBefore);
        o.set("kahluaErrors", errors != null ? Json.make(Arrays.asList(errors)) : Json.nil());
        return o;
    }

    private static String[] extractErrorsFromList(int errorListSizeBefore) {
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

    // Parse multipart-like Lua format: ---FILE:filename---\ncontent\n---FILE:...
    // Returns list of [filename, content] pairs
    private static java.util.List<String[]> parseMultipartLua(String body, String defaultChunkName) {
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
