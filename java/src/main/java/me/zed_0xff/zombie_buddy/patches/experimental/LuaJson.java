package me.zed_0xff.zombie_buddy.patches.experimental;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

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
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return toJsonValue(luaValue, 0, maxDepth, seen).toString();
    }

    /** Returns the Lua value as a Gson {@link JsonElement} tree (no string round-trip). */
    public static JsonElement toJsonTree(Object luaValue) {
        return toJsonTree(luaValue, DEFAULT_MAX_DEPTH);
    }

    /** Returns the Lua value as a Gson {@link JsonElement} tree (no string round-trip). */
    public static JsonElement toJsonTree(Object luaValue, int maxDepth) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return toJsonValue(luaValue, 0, maxDepth, seen);
    }

    private static JsonElement toJsonValue(Object value, int depth, int maxDepth, Set<Object> seen) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Map) {
            if (seen.contains(value)) {
                return new JsonPrimitive("[ref]");
            }
            if (depth >= maxDepth) {
                return new JsonPrimitive("[object]");
            }
            seen.add(value);
            JsonObject obj = new JsonObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                String key = e.getKey() != null ? e.getKey().toString() : "null";
                obj.add(key, toJsonValue(e.getValue(), depth + 1, maxDepth, seen));
            }
            return obj;
        }
        if (value instanceof KahluaTable) {
            if (seen.contains(value)) {
                return new JsonPrimitive("[ref]");
            }
            if (depth >= maxDepth) {
                return new JsonPrimitive("[table]");
            }
            seen.add(value);
            KahluaTable table = (KahluaTable) value;
            if (isArray(table)) {
                JsonArray arr = new JsonArray();
                int len = table.len();
                for (int i = 1; i <= len; i++) {
                    arr.add(toJsonValue(table.rawget(i), depth + 1, maxDepth, seen));
                }
                return arr;
            }
            JsonObject obj = new JsonObject();
            KahluaTableIterator iter = table.iterator();
            while (iter.advance()) {
                Object key = iter.getKey();
                String keyStr = (key instanceof Double)
                    ? String.valueOf(((Double) key).longValue())
                    : key.toString();
                JsonElement val = toJsonValue(iter.getValue(), depth + 1, maxDepth, seen);
                obj.add(keyStr, val);
            }
            return obj;
        }
        if (value instanceof Double) {
            Double d = (Double) value;
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return new JsonPrimitive(d.longValue());
            }
            return new JsonPrimitive(d);
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        if (value instanceof String) {
            return new JsonPrimitive((String) value);
        }
        if (value instanceof List) {
            if (seen.contains(value)) {
                return new JsonPrimitive("[ref]");
            }
            if (depth >= maxDepth) {
                return new JsonPrimitive("[list]");
            }
            seen.add(value);
            JsonArray arr = new JsonArray();
            for (Object elt : (List<?>) value) {
                arr.add(toJsonValue(elt, depth + 1, maxDepth, seen));
            }
            return arr;
        }
        return new JsonPrimitive(value.toString());
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
    public static JsonObject serializeJavaException(Throwable ex) {
        JsonObject o = new JsonObject();
        o.addProperty("className", ex.getClass().getName());
        String message = ex.getMessage();
        if (message == null || message.isEmpty()) message = ex.toString();
        StringBuilder fullMessage = new StringBuilder(message);
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            fullMessage.append(" Caused by: ");
            String cm = cause.getMessage();
            fullMessage.append(cm != null && !cm.isEmpty() ? cm : cause.toString());
        }
        o.addProperty("message", fullMessage.toString());
        StackTraceElement[] stack = ex.getStackTrace();
        if (stack != null && stack.length > 0) {
            StackTraceElement frame = stack[0];
            o.addProperty("file", frame.getFileName());
            o.addProperty("line", frame.getLineNumber());
            o.addProperty("method", frame.getMethodName());
            JsonArray stackTrace = new JsonArray();
            for (StackTraceElement f : stack) {
                String fn = f.getFileName();
                stackTrace.add(new JsonPrimitive(
                    f.getClassName() + "." + f.getMethodName() + "(" + (fn != null ? fn : "?") + ":" + f.getLineNumber() + ")"
                ));
            }
            o.add("stackTrace", stackTrace);
        }
        return o;
    }

    /** Serialize a KahluaException to JSON for HTTP error response. */
    public static JsonObject serializeKahluaException(KahluaException ex, String[] kahluaErrors) {
        JsonObject o = new JsonObject();
        o.addProperty("errorString", ex.getMessage());
        o.add("kahluaErrors", stringArrayToJsonArray(kahluaErrors));
        return o;
    }

    /** Serialize a LuaReturn (failed protected call) to JSON for HTTP error response. */
    public static JsonObject serializeLuaReturn(LuaReturn luaReturn, String[] kahluaErrors) {
        JsonObject o = new JsonObject();
        o.addProperty("errorString", luaReturn.getErrorString());
        o.addProperty("luaStackTrace", luaReturn.getLuaStackTrace());
        Object errorObj = luaReturn.getErrorObject();
        if (errorObj != null) {
            o.addProperty("errorObject", String.valueOf(errorObj));
        } else {
            o.add("errorObject", JsonNull.INSTANCE);
        }
        RuntimeException javaEx = luaReturn.getJavaException();
        o.add("javaException", javaEx != null ? serializeJavaException(javaEx) : JsonNull.INSTANCE);
        o.add("kahluaErrors", stringArrayToJsonArray(kahluaErrors));
        return o;
    }

    private static JsonElement stringArrayToJsonArray(String[] kahluaErrors) {
        if (kahluaErrors == null) {
            return JsonNull.INSTANCE;
        }
        JsonArray arr = new JsonArray();
        for (String s : kahluaErrors) {
            arr.add(s != null ? new JsonPrimitive(s) : JsonNull.INSTANCE);
        }
        return arr;
    }
}
