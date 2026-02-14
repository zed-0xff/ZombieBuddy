package me.zed_0xff.zombie_buddy.patches.experimental;

import java.util.Arrays;
import java.util.Map;

import mjson.Json;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.vm.KahluaException;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;

public class LuaJson {
    private static final int DEFAULT_MAX_DEPTH = 1;

    public static String toJson(Object luaValue) {
        return toJson(luaValue, DEFAULT_MAX_DEPTH);
    }

    public static String toJson(Object luaValue, int maxDepth) {
        return toJsonValue(luaValue, 0, maxDepth).toString();
    }

    /** Returns the Lua value as an mjson Json tree (no string round-trip). */
    public static Json toJsonTree(Object luaValue) {
        return toJsonTree(luaValue, DEFAULT_MAX_DEPTH);
    }

    /** Returns the Lua value as an mjson Json tree (no string round-trip). */
    public static Json toJsonTree(Object luaValue, int maxDepth) {
        return toJsonValue(luaValue, 0, maxDepth);
    }

    private static Json toJsonValue(Object value, int depth, int maxDepth) {
        if (value == null) {
            return Json.nil();
        }
        if (value instanceof Map) {
            if (depth >= maxDepth) {
                return Json.make("[object]");
            }
            Json obj = Json.object();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                String key = e.getKey() != null ? e.getKey().toString() : "null";
                obj.set(key, toJsonValue(e.getValue(), depth + 1, maxDepth));
            }
            return obj;
        }
        if (value instanceof KahluaTable) {
            if (depth >= maxDepth) {
                return Json.make("[table]");
            }
            KahluaTable table = (KahluaTable) value;
            if (isArray(table)) {
                Json arr = Json.array();
                int len = table.len();
                for (int i = 1; i <= len; i++) {
                    arr.add(toJsonValue(table.rawget(i), depth + 1, maxDepth));
                }
                return arr;
            }
            Json obj = Json.object();
            KahluaTableIterator iter = table.iterator();
            while (iter.advance()) {
                Object key = iter.getKey();
                String keyStr = (key instanceof Double)
                    ? String.valueOf(((Double) key).longValue())
                    : key.toString();
                Json val = toJsonValue(iter.getValue(), depth + 1, maxDepth);
                obj.set(keyStr, val);
            }
            return obj;
        }
        if (value instanceof Double) {
            Double d = (Double) value;
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return Json.make(d.longValue());
            }
            return Json.make(d);
        }
        if (value instanceof Boolean) {
            return Json.make((Boolean) value);
        }
        if (value instanceof String) {
            return Json.make((String) value);
        }
        return Json.make(value.toString());
    }

    private static boolean isArray(KahluaTable table) {
        int len = table.len();
        if (len > 0) {
            return true;
        }
        KahluaTableIterator iter = table.iterator();
        if (!iter.advance()) {
            return true;
        }
        Object key = iter.getKey();
        return (key instanceof Double) && ((Double) key) == 1.0;
    }

    /** Serialize a Java exception to JSON for HTTP error response. */
    public static Json serializeJavaException(Throwable ex) {
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

    /** Serialize a KahluaException to JSON for HTTP error response. */
    public static Json serializeKahluaException(KahluaException ex, String[] kahluaErrors) {
        Json o = Json.object();
        o.set("errorString", ex.getMessage());
        o.set("kahluaErrors", kahluaErrors != null ? Json.make(Arrays.asList(kahluaErrors)) : Json.nil());
        return o;
    }

    /** Serialize a LuaReturn (failed protected call) to JSON for HTTP error response. */
    public static Json serializeLuaReturn(LuaReturn luaReturn, String[] kahluaErrors) {
        Json o = Json.object();
        o.set("errorString", luaReturn.getErrorString());
        o.set("luaStackTrace", luaReturn.getLuaStackTrace());
        Object errorObj = luaReturn.getErrorObject();
        o.set("errorObject", errorObj != null ? String.valueOf(errorObj) : Json.nil());
        RuntimeException javaEx = luaReturn.getJavaException();
        o.set("javaException", javaEx != null ? serializeJavaException(javaEx) : Json.nil());
        o.set("kahluaErrors", kahluaErrors != null ? Json.make(Arrays.asList(kahluaErrors)) : Json.nil());
        return o;
    }
}
