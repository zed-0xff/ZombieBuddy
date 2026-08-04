package me.zed_0xff.zombie_buddy.patches.experimental.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.luaj.kahluafork.compiler.FuncState;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.Coroutine;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;

import me.zed_0xff.zombie_buddy.Callbacks;
import me.zed_0xff.zombie_buddy.LuaJSON;
import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.patches.experimental.HttpServer;

public class LuaHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            handleRequest(exchange);
        } catch (Throwable t) {
            Logger.error("LuaHandler failed: " + t.getMessage());
            if (HttpServer.g_verbosity > 0) {
                Logger.printStackTrace(t);
            }

            sendError(exchange, 99, LuaJSON.serializeJavaException(t));
        }
    }

    private static boolean _lua_ready = false;
    static {
        Callbacks.beforeLuaInit.register(() -> { _lua_ready = false; });
        Callbacks.afterLuaInit.register(()  -> { _lua_ready = true; });
    }

    private static void handleRequest(HttpExchange exchange) throws Exception {
        HttpServer.logRequest(exchange);
        if (!_lua_ready) {
            HttpServer.sendResponse(exchange, 503, "Lua not initialized yet\n");
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpServer.sendResponse(exchange, 405, "Method not allowed. Use POST.\n");
            return;
        }

        String query       = exchange.getRequestURI().getQuery();

        int depth          = HttpServer.parseIntParam(query, "depth", 1);
        String chunkName   = HttpServer.parseStringParam(query, "chunkname", "http_exec");
        boolean threadCall = HttpServer.parseBoolParam(query, "thread", false);
        boolean sandbox    = HttpServer.parseBoolParam(query, "sandbox", true);
        boolean json_arr_1 = HttpServer.parseBoolParam(query, "json_arr_1", false);
        final LuaJSON lj   = json_arr_1 ? new LuaJSON(depth, 0, LuaJSON.Flags.ARR_INJECT_NULL) : new LuaJSON(depth, 0);

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isEmpty()) {
            HttpServer.sendResponse(exchange, 400, "Empty request body\n");
            return;
        }

        final List<String[]> chunks = HttpServer.parseMultipartLua(body, chunkName);

        AtomicReference<Object> resultRef = new AtomicReference<>();
        AtomicReference<JsonObject> errorPayloadRef = new AtomicReference<>();
        final Map<String, Object> errorGlobalValues = new HashMap<>();
        AtomicInteger errCode = new AtomicInteger(0);
        AtomicInteger errorListSizeBeforeRef = new AtomicInteger(0);
        final List<String> errorGlobalNames = HttpServer.parseErrorGlobalNames(exchange);

        try {
            HttpServer.runOnLuaThread(() -> runLua(exchange, chunks, chunkName, threadCall, sandbox, resultRef, errorPayloadRef, errorGlobalValues, errCode, errorListSizeBeforeRef, errorGlobalNames));
        } catch (Throwable t) {
            Logger.error(t.toString());
            Logger.printStackTrace(t);
            errCode.set(3);
            errorPayloadRef.set(LuaJSON.serializeJavaException(t));
        }

        if (errorPayloadRef.get() != null) {
            sendLuaError(exchange, errCode.get(), errorPayloadRef.get(), errorListSizeBeforeRef.get(), errorGlobalValues, lj);
            return;
        }

        HttpServer.sendJsonResponse(exchange, 200, lj.toJson(resultRef.get()));
    }

    private static void runLua(
            HttpExchange exchange,
            List<String[]> chunks,
            String defaultChunkName,
            boolean threadCall,
            boolean sandbox,
            AtomicReference<Object> resultRef,
            AtomicReference<JsonObject> errorPayloadRef,
            Map<String, Object> errorGlobalValues,
            AtomicInteger errCode,
            AtomicInteger errorListSizeBeforeRef,
            List<String> errorGlobalNames) {
        KahluaTable sharedEnv = LuaManager.env;
        try {
            int errorListSizeBefore = KahluaThread.m_errors_list.size();
            errorListSizeBeforeRef.set(errorListSizeBefore);

            if (sandbox) {
                sharedEnv = LuaManager.platform.newTable();
                KahluaTable mt = LuaManager.platform.newTable();
                mt.rawset("__index", LuaManager.env);
                sharedEnv.setMetatable(mt);
                sharedEnv.rawset("_G", sharedEnv);
            }

            LuaClosure closure = null;
            for (int i = 0; i < chunks.size(); i++) {
                String[] chunk = chunks.get(i);
                String fileName = chunk[0];
                String luaCode = chunk[1];
                FuncState.currentFile = fileName;
                FuncState.currentfullFile = fileName;
                closure = LuaCompiler.loadstring(luaCode, fileName, sharedEnv);

                if (i < chunks.size() - 1) {
                    callNoArgs(LuaManager.thread, closure);
                }
            }

            if (closure == null) {
                errCode.set(2);
                errorPayloadRef.set(LuaJSON.serializeJavaException(new IllegalStateException("No Lua chunk loaded")));
                return;
            }

            if (threadCall) {
                KahluaThread workerThread = new KahluaThread(LuaManager.platform, sharedEnv);
                Object ret = workerThread.call(closure, null, null, null);
                if (ret != null) {
                    resultRef.set(ret);
                }
            } else {
                Object ret = callNoArgs(LuaManager.thread, closure);
                if (ret != null) {
                    resultRef.set(ret);
                }
            }
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause != null) {
                if (cause instanceof se.krka.kahlua.vm.KahluaException) {
                    errCode.set(1);
                    errorPayloadRef.set(LuaJSON.serializeKahluaException((se.krka.kahlua.vm.KahluaException) cause, HttpServer.extractErrorsFromList(errorListSizeBeforeRef.get())));
                    break;
                }
                cause = cause.getCause();
            }
            if (errorPayloadRef.get() == null) {
                errCode.set(2);
                errorPayloadRef.set(LuaJSON.serializeJavaException(t));
            }
        } finally {
            if (errorPayloadRef.get() != null && !errorGlobalNames.isEmpty()) {
                for (String name : errorGlobalNames) {
                    Object value = sharedEnv.rawget(name);
                    if (value != null) {
                        errorGlobalValues.put(name, value);
                    }
                }
            }
            FuncState.currentFile = null;
            FuncState.currentfullFile = null;
        }
    }

    private static Object callNoArgs(KahluaThread thread, Object functionObject) {
        Coroutine coroutine = thread.getCurrentCoroutine();
        int oldTop = coroutine.getTop();
        try {
            return thread.call(functionObject, null, null, null);
        } finally {
            coroutine.setTop(oldTop);
        }
    }

    private static void sendLuaError(HttpExchange exchange, int code, JsonObject payload, int errorListSizeBefore, Map<String, Object> errorGlobalValues, LuaJSON lj) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("err_code", code);
        if (code == 1) {
            root.add("luaReturn", payload);
        } else {
            root.add("javaException", payload);
            String[] errors = HttpServer.extractErrorsFromList(errorListSizeBefore);
            if (errors != null) {
                JsonArray arr = new JsonArray();
                for (String s : errors) {
                    arr.add(s != null ? new JsonPrimitive(s) : JsonNull.INSTANCE);
                }
                root.add("kahluaErrors", arr);
            } else {
                root.add("kahluaErrors", JsonNull.INSTANCE);
            }
        }
        if (!errorGlobalValues.isEmpty()) {
            root.add("errorGlobals", lj.toJsonTree(errorGlobalValues));
        }
        HttpServer.sendJsonResponse(exchange, 500, root.toString());
    }

    private static void sendError(HttpExchange exchange, int code, JsonObject payload) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("err_code", code);
            root.add("javaException", payload);
            HttpServer.sendJsonResponse(exchange, 500, root.toString());
        } catch (Throwable t) {
            Logger.error("LuaHandler could not send error response: " + t.getMessage());
            if (HttpServer.g_verbosity > 0) {
                Logger.printStackTrace(t);
            }
        }
    }
}
