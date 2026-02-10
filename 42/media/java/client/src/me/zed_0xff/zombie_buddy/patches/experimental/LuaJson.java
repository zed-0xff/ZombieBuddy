package me.zed_0xff.zombie_buddy.patches.experimental;

import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;

public class LuaJson {
    private static final int DEFAULT_MAX_DEPTH = 1;

    public static String toJson(Object luaValue) {
        return toJson(luaValue, DEFAULT_MAX_DEPTH);
    }

    public static String toJson(Object luaValue, int maxDepth) {
        StringBuilder sb = new StringBuilder();
        writeValue(luaValue, sb, 0, maxDepth);
        return sb.toString();
    }

    private static void writeValue(Object value, StringBuilder sb, int depth, int maxDepth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof KahluaTable) {
            if (depth >= maxDepth) {
                sb.append("\"[table]\"");
            } else {
                writeTable((KahluaTable) value, sb, depth, maxDepth);
            }
        } else if (value instanceof Double) {
            writeNumber((Double) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else {
            writeString(value.toString(), sb);
        }
    }

    private static void writeNumber(Double d, StringBuilder sb) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            sb.append(d.longValue());
        } else {
            sb.append(d);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void writeTable(KahluaTable table, StringBuilder sb, int depth, int maxDepth) {
        if (isArray(table)) {
            writeArray(table, sb, depth, maxDepth);
        } else {
            writeObject(table, sb, depth, maxDepth);
        }
    }

    private static boolean isArray(KahluaTable table) {
        int len = table.len();
        if (len > 0) {
            return true;
        }
        // Empty table with no keys -> empty array
        KahluaTableIterator iter = table.iterator();
        if (!iter.advance()) {
            return true;
        }
        // Has keys but len==0, check if first key is integer 1
        Object key = iter.getKey();
        return (key instanceof Double) && ((Double) key) == 1.0;
    }

    private static void writeArray(KahluaTable table, StringBuilder sb, int depth, int maxDepth) {
        sb.append('[');
        int len = table.len();
        for (int i = 1; i <= len; i++) {
            if (i > 1) sb.append(',');
            writeValue(table.rawget(i), sb, depth + 1, maxDepth);
        }
        sb.append(']');
    }

    private static void writeObject(KahluaTable table, StringBuilder sb, int depth, int maxDepth) {
        sb.append('{');
        KahluaTableIterator iter = table.iterator();
        boolean first = true;
        while (iter.advance()) {
            if (!first) sb.append(',');
            first = false;

            Object key = iter.getKey();
            String keyStr = (key instanceof Double)
                ? String.valueOf(((Double) key).longValue())
                : key.toString();
            writeString(keyStr, sb);
            sb.append(':');
            writeValue(iter.getValue(), sb, depth + 1, maxDepth);
        }
        sb.append('}');
    }
}
