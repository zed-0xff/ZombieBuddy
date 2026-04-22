package me.zed_0xff.zombie_buddy;

import java.util.Iterator;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Indented JSON text for Gson {@link JsonElement} with compact {@code mod_ids} and {@code author} objects.
 */
final class GsonPretty {

    private static final int SPACES_PER_LEVEL = 2;

    private GsonPretty() {}

    static String format(JsonElement root) {
        StringBuilder sb = new StringBuilder();
        write(root, sb, 0, null);
        sb.append('\n');
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0, n = depth * SPACES_PER_LEVEL; i < n; i++) {
            sb.append(' ');
        }
    }

    private static void write(JsonElement j, StringBuilder sb, int depth, String fieldName) {
        if (j == null || j.isJsonNull()) {
            sb.append("null");
            return;
        }
        if (j.isJsonPrimitive()) {
            JsonPrimitive p = j.getAsJsonPrimitive();
            if (p.isBoolean()) {
                sb.append(p.getAsBoolean());
                return;
            }
            if (p.isNumber()) {
                sb.append(p.getAsNumber().toString());
                return;
            }
            if (p.isString()) {
                appendQuoted(sb, p.getAsString());
                return;
            }
            sb.append("null");
            return;
        }
        if (j.isJsonArray()) {
            JsonArray arr = j.getAsJsonArray();
            if (arr.size() == 0) {
                sb.append("[]");
                return;
            }
            if ("mod_ids".equals(fieldName)) {
                writeCompactArray(arr, sb);
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < arr.size(); i++) {
                indent(sb, depth + 1);
                write(arr.get(i), sb, depth + 1, null);
                if (i < arr.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            indent(sb, depth);
            sb.append(']');
            return;
        }
        if (j.isJsonObject()) {
            JsonObject obj = j.getAsJsonObject();
            if (obj.entrySet().isEmpty()) {
                sb.append("{}");
                return;
            }
            if ("author".equals(fieldName)) {
                writeCompactObject(obj, sb);
                return;
            }
            sb.append("{\n");
            Iterator<Map.Entry<String, JsonElement>> it = obj.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonElement> e = it.next();
                indent(sb, depth + 1);
                appendQuoted(sb, e.getKey());
                sb.append(": ");
                write(e.getValue(), sb, depth + 1, e.getKey());
                if (it.hasNext()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            indent(sb, depth);
            sb.append('}');
            return;
        }
        sb.append("null");
    }

    private static void writeCompactArray(JsonArray arr, StringBuilder sb) {
        sb.append('[');
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (item == null || item.isJsonNull()) {
                sb.append("null");
            } else if (item.isJsonPrimitive()) {
                JsonPrimitive p = item.getAsJsonPrimitive();
                if (p.isString()) {
                    appendQuoted(sb, p.getAsString());
                } else if (p.isBoolean()) {
                    sb.append(p.getAsBoolean());
                } else if (p.isNumber()) {
                    sb.append(p.getAsNumber().toString());
                } else {
                    write(item, sb, 0, null);
                }
            } else {
                write(item, sb, 0, null);
            }
            if (i < arr.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(']');
    }

    private static void writeCompactObject(JsonObject obj, StringBuilder sb) {
        sb.append('{');
        Iterator<Map.Entry<String, JsonElement>> it = obj.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonElement> e = it.next();
            appendQuoted(sb, e.getKey());
            sb.append(": ");
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) {
                sb.append("null");
            } else if (v.isJsonPrimitive()) {
                JsonPrimitive p = v.getAsJsonPrimitive();
                if (p.isString()) {
                    appendQuoted(sb, p.getAsString());
                } else if (p.isBoolean()) {
                    sb.append(p.getAsBoolean());
                } else if (p.isNumber()) {
                    sb.append(p.getAsNumber().toString());
                } else {
                    write(v, sb, 0, null);
                }
            } else {
                write(v, sb, 0, null);
            }
            if (it.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append('}');
    }

    private static void appendQuoted(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
