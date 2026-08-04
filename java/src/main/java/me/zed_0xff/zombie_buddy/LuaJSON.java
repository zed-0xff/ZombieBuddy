package me.zed_0xff.zombie_buddy;

import java.lang.NoClassDefFoundError;
import java.util.Collections;
import java.util.EnumSet;
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

public class LuaJSON {
    private static final boolean KAHLUA_AVAILABLE = Reflect.on("se.krka.kahlua.vm.KahluaTable").isPresent();

    public static final int DEFAULT_MAX_DEPTH = 1;
    public static final int DEFAULT_MAX_LEN   = 0; // no limit

    public enum Flags {
        ARR_INJECT_NULL,       // inject a null as the first element of JSON arrays to preserve Lua's 1-based indexing
        ADD_SPACE_AFTER_COMMA, // add a space after commas for more readable output
        STRIP_QUOTES           // unquote strings without spaces or special characters (for more compact output)
    }

    private final EnumSet<Flags> m_flags;
    private final int            m_maxLen;
    private final int            m_maxDepth;
    private final String         m_comma;

    public LuaJSON()              { this(DEFAULT_MAX_DEPTH, DEFAULT_MAX_LEN); }
    public LuaJSON(int max_depth) { this(max_depth,         DEFAULT_MAX_LEN); }

    public LuaJSON(int max_depth, int max_len, Flags... flags) {
        m_maxDepth = max_depth;
        m_maxLen   = max_len;
        m_flags    = flags.length > 0 ? EnumSet.copyOf(List.of(flags)) : EnumSet.noneOf(Flags.class);
        m_comma    = m_flags.contains(Flags.ADD_SPACE_AFTER_COMMA) ? ", " : ",";
    }

    public String toJson(Object luaValue) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        StringBuilder sb = new StringBuilder();
        writeJson(sb, luaValue, 0, seen);
        if (m_maxLen > 0 && sb.length() > m_maxLen) { sb.setLength(m_maxLen); sb.append("..."); }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Length-limited streaming serializer (used by toJson when m_maxLen > 0)
    // -------------------------------------------------------------------------

    private boolean overLimit(StringBuilder sb) { return m_maxLen > 0 && sb.length() >= m_maxLen; }
    private boolean arrInjectNull()             { return m_flags.contains(Flags.ARR_INJECT_NULL); }
    private boolean stripQuotes()               { return m_flags.contains(Flags.STRIP_QUOTES); }

    private static boolean needsQuoting(String s) {
        if (s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20 || c == ' ') return true;
        }
        return false;
    }

    private void writeStr(StringBuilder sb, String s) {
        if (stripQuotes() && !needsQuoting(s)) { sb.append(s); return; }
        sb.append('"'); escapeJson(sb, s); sb.append('"');
    }

    private void writeKey(StringBuilder sb, String key) { writeStr(sb, key); sb.append(':'); }

    private void writeJson(StringBuilder sb, Object value, int depth, Set<Object> seen) {
        if (overLimit(sb)) return;
        if (value == null) { sb.append("null"); return; }

        if (value instanceof Map<?,?> map) {
            if (seen.contains(map))  { sb.append("\"[ref]\"");    return; }
            if (depth >= m_maxDepth) { sb.append("\"[object]\""); return; }
            seen.add(map);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?,?> e : map.entrySet()) {
                if (overLimit(sb)) break;
                if (!first) sb.append(m_comma);
                first = false;
                writeKey(sb, e.getKey() != null ? e.getKey().toString() : "null");
                writeJson(sb, e.getValue(), depth + 1, seen);
            }
            sb.append('}');
            return;
        }

        if (value instanceof List<?> list) {
            if (seen.contains(list))  { sb.append("\"[ref]\"");  return; }
            if (depth >= m_maxDepth)  { sb.append("\"[list]\""); return; }
            seen.add(list);
            sb.append('[');
            boolean first = true;
            for (Object elt : list) {
                if (overLimit(sb)) break;
                if (!first) sb.append(m_comma);
                first = false;
                writeJson(sb, elt, depth + 1, seen);
            }
            sb.append(']');
            return;
        }

        if (value instanceof Number  n) { sb.append(normalizeNumber(n)); return; }
        if (value instanceof Boolean b) { sb.append(b); return; }
        if (value instanceof String  s) { writeStr(sb, s); return; }

        if (KAHLUA_AVAILABLE && value instanceof KahluaTable tbl) {
            if (seen.contains(tbl))  { sb.append("\"[ref]\"");   return; }
            if (depth >= m_maxDepth) { sb.append("\"[table]\""); return; }
            seen.add(tbl);
            if (isLuaArray(tbl)) {
                sb.append('[');
                if (arrInjectNull()) sb.append("null");
                int len = tbl.len();
                boolean anyWritten = arrInjectNull();
                for (int i = 1; i <= len; i++) {
                    if (overLimit(sb)) break;
                    if (anyWritten) sb.append(m_comma);
                    writeJson(sb, tbl.rawget(i), depth + 1, seen);
                    anyWritten = true;
                }
                sb.append(']');
            } else {
                sb.append('{');
                KahluaTableIterator iter = tbl.iterator();
                boolean first = true;
                while (iter.advance()) {
                    if (overLimit(sb)) break;
                    if (!first) sb.append(m_comma);
                    first = false;
                    Object key = iter.getKey();
                    writeKey(sb, (key instanceof Double d) ? String.valueOf(d.longValue()) : key.toString());
                    writeJson(sb, iter.getValue(), depth + 1, seen);
                }
                sb.append('}');
            }
            return;
        }

        writeStr(sb, value.toString());
    }

    private static Number normalizeNumber(Number n) {
        if (n instanceof Double d && d == Math.floor(d) && !Double.isInfinite(d)) return d.longValue();
        if (n instanceof Float  f && f == Math.floor(f) && !Float.isInfinite(f))  return (long)(float)f;
        return n;
    }

    private static void escapeJson(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
    }

    /** Returns the Lua value as a Gson {@link JsonElement} tree (no string round-trip). */
    public JsonElement toJsonTree(Object luaValue) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return toJsonValue(luaValue, 0, seen);
    }

    private JsonElement toJsonValue(Object value, int depth, Set<Object> seen) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Map) {
            if (seen.contains(value)) {
                return new JsonPrimitive("[ref]");
            }
            if (depth >= m_maxDepth) {
                return new JsonPrimitive("[object]");
            }
            seen.add(value);
            JsonObject obj = new JsonObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                String key = e.getKey() != null ? e.getKey().toString() : "null";
                obj.add(key, toJsonValue(e.getValue(), depth + 1, seen));
            }
            return obj;
        }
        if (KAHLUA_AVAILABLE && value instanceof KahluaTable) {
            if (seen.contains(value)) {
                return new JsonPrimitive("[ref]");
            }
            if (depth >= m_maxDepth) {
                return new JsonPrimitive("[table]");
            }
            seen.add(value);
            KahluaTable table = (KahluaTable) value;
            if (isLuaArray(table)) {
                JsonArray arr = new JsonArray();
                if (arrInjectNull()) {
                    arr.add(JsonNull.INSTANCE); // Lua arrays are 1-indexed, so add a dummy element at index 0
                }
                int len = table.len();
                for (int i = 1; i <= len; i++) {
                    arr.add(toJsonValue(table.rawget(i), depth + 1, seen));
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
                JsonElement val = toJsonValue(iter.getValue(), depth + 1, seen);
                obj.add(keyStr, val);
            }
            return obj;
        }
        if (value instanceof Number n) { return new JsonPrimitive(normalizeNumber(n)); }
        if (value instanceof Boolean b) { return new JsonPrimitive(b); }
        if (value instanceof String) {
            return new JsonPrimitive((String) value);
        }
        if (value instanceof List) {
            if (seen.contains(value)) {
                return new JsonPrimitive("[ref]");
            }
            if (depth >= m_maxDepth) {
                return new JsonPrimitive("[list]");
            }
            seen.add(value);
            JsonArray arr = new JsonArray();
            for (Object elt : (List<?>) value) {
                arr.add(toJsonValue(elt, depth + 1, seen));
            }
            return arr;
        }
        return new JsonPrimitive(value.toString());
    }

    public static boolean canSerialize(Object value) {
        return value == null
            || value instanceof Map
            || value instanceof List
            || value instanceof Number
            || value instanceof Boolean
            || value instanceof String
            || (KAHLUA_AVAILABLE && value instanceof KahluaTable);
    }

    private static boolean isLuaArray(KahluaTable table) {
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
        if (Utils.isBlank(message)) message = ex.toString();
        StringBuilder fullMessage = new StringBuilder(message);
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            fullMessage.append(" Caused by: ");
            String cm = cause.getMessage();
            fullMessage.append(!Utils.isBlank(cm) ? cm : cause.toString());
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
