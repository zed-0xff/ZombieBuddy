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

import mjson.Json;
import org.luaj.kahluafork.compiler.FuncState;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;

import me.zed_0xff.zombie_buddy.patches.experimental.HttpServer;
import me.zed_0xff.zombie_buddy.patches.experimental.LuaJson;

public class LuaHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        HttpServer.logRequest(exchange);
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpServer.sendResponse(exchange, 405, "Method not allowed. Use POST.\n");
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        int depth = HttpServer.parseIntParam(query, "depth", 1);
        String chunkName = HttpServer.parseStringParam(query, "chunkname", "http_exec");
        boolean threadCall = HttpServer.parseBoolParam(query, "thread", false);
        boolean sandbox = HttpServer.parseBoolParam(query, "sandbox", true);

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isEmpty()) {
            HttpServer.sendResponse(exchange, 400, "Empty request body\n");
            return;
        }

        final List<String[]> chunks = HttpServer.parseMultipartLua(body, chunkName);

        AtomicReference<Object> resultRef = new AtomicReference<>();
        AtomicReference<Json> errorPayloadRef = new AtomicReference<>();
        final Map<String, Object> errorGlobalValues = new HashMap<>();
        AtomicInteger errCode = new AtomicInteger(0);
        AtomicInteger errorListSizeBeforeRef = new AtomicInteger(-1);
        final List<String> errorGlobalNames = HttpServer.parseErrorGlobalNames(exchange);

        try {
            HttpServer.runOnLuaThread(() -> {
                String prevFile = FuncState.currentFile;
                String prevFullFile = FuncState.currentfullFile;
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
                    for (String[] chunk : chunks) {
                        String fileName = chunk[0];
                        String luaCode = chunk[1];
                        FuncState.currentFile = fileName;
                        FuncState.currentfullFile = fileName;
                        closure = LuaCompiler.loadstring(luaCode, fileName, sharedEnv);

                        if (chunks.indexOf(chunk) < chunks.size() - 1) {
                            LuaReturn ret = LuaManager.caller.protectedCall(LuaManager.thread, closure, new Object[0]);
                            if (!ret.isSuccess()) {
                                errCode.set(1);
                                errorPayloadRef.set(LuaJson.serializeLuaReturn(ret, HttpServer.extractErrorsFromList(errorListSizeBefore)));
                                return;
                            }
                        }
                    }

                    if (threadCall) {
                        KahluaThread workerThread = new KahluaThread(LuaManager.platform, sharedEnv);
                        Object ret = workerThread.call(closure, null, null, null);
                        if (ret != null) {
                            resultRef.set(ret);
                        }
                    } else {
                        LuaReturn luaReturn = LuaManager.caller.protectedCall(LuaManager.thread, closure, new Object[0]);

                        if (luaReturn.isSuccess()) {
                            if (!luaReturn.isEmpty()) {
                                resultRef.set(luaReturn.getFirst());
                            }
                        } else {
                            errCode.set(1);
                            errorPayloadRef.set(LuaJson.serializeLuaReturn(luaReturn, HttpServer.extractErrorsFromList(errorListSizeBefore)));
                        }
                    }
                } catch (Exception e) {
                    Throwable cause = e;
                    while (cause != null) {
                        if (cause instanceof se.krka.kahlua.vm.KahluaException) {
                            errCode.set(1);
                            errorPayloadRef.set(LuaJson.serializeKahluaException((se.krka.kahlua.vm.KahluaException) cause, HttpServer.extractErrorsFromList(errorListSizeBeforeRef.get())));
                            break;
                        }
                        cause = cause.getCause();
                    }
                    if (errorPayloadRef.get() == null) {
                        errCode.set(2);
                        errorPayloadRef.set(LuaJson.serializeJavaException(e));
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
            errCode.set(3);
            errorPayloadRef.set(LuaJson.serializeJavaException(e));
        }

        if (errorPayloadRef.get() != null) {
            Json root = Json.object();
            int code = errCode.get();
            root.set("err_code", code);
            if (code == 1) {
                root.set("luaReturn", errorPayloadRef.get());
            } else {
                root.set("javaException", errorPayloadRef.get());
                String[] errors = HttpServer.extractErrorsFromList(errorListSizeBeforeRef.get());
                root.set("kahluaErrors", errors != null ? Json.make(java.util.Arrays.asList(errors)) : Json.nil());
            }
            if (!errorGlobalValues.isEmpty()) {
                root.set("errorGlobals", LuaJson.toJsonTree(errorGlobalValues));
            }
            HttpServer.sendJsonResponse(exchange, 500, root.toString());
            return;
        }

        HttpServer.sendJsonResponse(exchange, 200, LuaJson.toJson(resultRef.get(), depth));
    }
}
