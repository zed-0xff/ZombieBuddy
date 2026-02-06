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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.luaj.kahluafork.compiler.FuncState;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.Coroutine;
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

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.isEmpty()) {
                sendResponse(exchange, 400, "Empty request body\n");
                return;
            }

            // Parse multipart-like format: ---FILE:filename---\ncontent\n---FILE:...
            // Each file is executed with its own chunkname for correct line numbers
            final java.util.List<String[]> chunks = parseMultipartLua(body, chunkName);

            AtomicReference<Object> resultRef = new AtomicReference<>();
            AtomicReference<String> errorRef = new AtomicReference<>();
            AtomicInteger errCode = new AtomicInteger(0);
            AtomicInteger errorListSizeBeforeRef = new AtomicInteger(-1);

            try {
                runOnLuaThread(() -> {
                    // Set the file info for better stack traces
                    String prevFile = FuncState.currentFile;
                    String prevFullFile = FuncState.currentfullFile;
                    try {
                        int errorListSizeBefore = KahluaThread.m_errors_list.size();
                        errorListSizeBeforeRef.set(errorListSizeBefore);
                        
                        // Execute all chunks in sequence, same Lua context
                        LuaClosure closure = null;
                        for (String[] chunk : chunks) {
                            String fileName = chunk[0];
                            String luaCode = chunk[1];
                            FuncState.currentFile = fileName;
                            FuncState.currentfullFile = fileName;
                            closure = LuaCompiler.loadstring(luaCode, fileName, LuaManager.env);
                            
                            if (chunks.indexOf(chunk) < chunks.size() - 1) {
                                // Not the last chunk - execute immediately (spec_helper, etc.)
                                LuaReturn ret = LuaManager.caller.protectedCall(LuaManager.thread, closure, new Object[0]);
                                if (!ret.isSuccess()) {
                                    errCode.set(1);
                                    errorRef.set(serializeLuaReturn(ret, errorListSizeBefore));
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
                            java.lang.reflect.Method luaMainloop = LuaManager.thread.getClass().getDeclaredMethod("luaMainloop");
                            luaMainloop.setAccessible(true);
                            luaMainloop.invoke(LuaManager.thread);
                            
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
                                errorRef.set(serializeLuaReturn(luaReturn, errorListSizeBefore));
                            }
                        }
                    } catch (Exception e) {
                        // Look for KahluaException in the exception chain for better error messages
                        Throwable cause = e;
                        while (cause != null) {
                            if (cause instanceof se.krka.kahlua.vm.KahluaException) {
                                errCode.set(1);  // Use code 1 so it formats as luaReturn
                                errorRef.set(serializeKahluaException((se.krka.kahlua.vm.KahluaException) cause, errorListSizeBeforeRef.get()));
                                break;
                            }
                            cause = cause.getCause();
                        }
                        if (errorRef.get() == null) {
                            errCode.set(2);
                            errorRef.set(serializeJavaException(e));
                        }
                    } finally {
                        FuncState.currentFile = prevFile;
                        FuncState.currentfullFile = prevFullFile;
                    }
                });
            } catch (Exception e) {
                errCode.set(3);
                errorRef.set(serializeJavaException(e));
            }

            if (errorRef.get() != null) {
                String errorData = errorRef.get();
                String response;
                int code = errCode.get();
                if (code == 1) {
                    // Lua execution failed - errorData is serializeLuaReturn JSON
                    response = "{\"err_code\": " + code + ", \"luaReturn\": " + errorData + "}";
                } else {
                    // Java exception (code 2 or 3) - errorData is serializeJavaException JSON
                    StringBuilder responseBuilder = new StringBuilder();
                    responseBuilder.append("{\"err_code\": ").append(code).append(", \"javaException\": ").append(errorData);
                    String[] errors = extractErrorsFromList(errorListSizeBeforeRef.get());
                    if (errors != null) {
                        responseBuilder.append(", \"kahluaErrors\": ").append(serializeErrorsArray(errors));
                    }
                    responseBuilder.append("}");
                    response = responseBuilder.toString();
                }
                sendJsonResponse(exchange, 500, response);
                return;
            }
            
            sendJsonResponse(exchange, 200, LuaJson.toJson(resultRef.get(), depth));
        }
    }

    private static String serializeJavaException(Throwable ex) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"className\": \"" + escapeJson(ex.getClass().getName()) + "\"");
        json.append(", \"message\": \"" + escapeJson(ex.getMessage()) + "\"");
        
        // Add first relevant stack frame
        StackTraceElement[] stack = ex.getStackTrace();
        if (stack != null && stack.length > 0) {
            StackTraceElement frame = stack[0];
            json.append(", \"file\": \"" + escapeJson(frame.getFileName()) + "\"");
            json.append(", \"line\": " + frame.getLineNumber());
            json.append(", \"method\": \"" + escapeJson(frame.getMethodName()) + "\"");
        }
        
        json.append("}");
        return json.toString();
    }

    // Serialize KahluaException with error message and any errors from m_errors_list
    private static String serializeKahluaException(se.krka.kahlua.vm.KahluaException ex, int errorListSizeBefore) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"errorString\": \"" + escapeJson(ex.getMessage()) + "\"");
        
        // errors from m_errors_list (contains stack trace if flushErrorMessage was called)
        String[] errors = extractErrorsFromList(errorListSizeBefore);
        json.append(", \"kahluaErrors\": ");
        if (errors != null) {
            json.append("[");
            for (int i = 0; i < errors.length; i++) {
                if (i > 0) json.append(", ");
                json.append("\"" + escapeJson(errors[i]) + "\"");
            }
            json.append("]");
        } else {
            json.append("null");
        }
        
        json.append("}");
        return json.toString();
    }

    private static String serializeLuaReturn(LuaReturn luaReturn, int errorListSizeBefore) {
        StringBuilder json = new StringBuilder();
        json.append("{");

        // errorString
        String errorStr = luaReturn.getErrorString();
        json.append("\"errorString\": ");
        json.append(errorStr != null ? "\"" + escapeJson(errorStr) + "\"" : "null");
        
        // luaStackTrace
        String stackTrace = luaReturn.getLuaStackTrace();
        json.append(", \"luaStackTrace\": ");
        json.append(stackTrace != null ? "\"" + escapeJson(stackTrace) + "\"" : "null");
        
        // errorObject (as string)
        Object errorObj = luaReturn.getErrorObject();
        json.append(", \"errorObject\": ");
        json.append(errorObj != null ? "\"" + escapeJson(String.valueOf(errorObj)) + "\"" : "null");
        
        // javaException
        RuntimeException javaEx = luaReturn.getJavaException();
        json.append(", \"javaException\": ");
        json.append(javaEx != null ? serializeJavaException(javaEx) : "null");
        
        // errors from m_errors_list
        String[] errors = extractErrorsFromList(errorListSizeBefore);
        json.append(", \"kahluaErrors\": ");
        if (errors != null) {
            json.append("[");
            for (int i = 0; i < errors.length; i++) {
                if (i > 0) json.append(", ");
                json.append("\"" + escapeJson(errors[i]) + "\"");
            }
            json.append("]");
        } else {
            json.append("null");
        }
        
        json.append("}");
        return json.toString();
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

    private static String serializeErrorsArray(String[] errors) {
        if (errors == null) {
            return "null";
        }
        StringBuilder json = new StringBuilder();
        json.append("[");
        for (int i = 0; i < errors.length; i++) {
            if (i > 0) json.append(", ");
            json.append("\"").append(escapeJson(errors[i])).append("\"");
        }
        json.append("]");
        return json.toString();
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
