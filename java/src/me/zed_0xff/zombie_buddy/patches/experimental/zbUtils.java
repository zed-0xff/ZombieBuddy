package me.zed_0xff.zombie_buddy.patches.experimental;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.zed_0xff.zombie_buddy.Accessor;
import me.zed_0xff.zombie_buddy.Utils;
import me.zed_0xff.zombie_buddy.ZombieBuddy;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;

public class zbUtils {

    @LuaMethod(name = "zbinspect", global = true)
    public static KahluaTable zbInspect(Object obj) {
        return zbInspect(obj, false);
    }

    @LuaMethod(name = "zbinspect", global = true)
    public static KahluaTable zbInspect(Object obj, Boolean bPrivate) {
        if (obj == null) {
            return null;
        }
        KahluaTable result = LuaManager.platform.newTable();
        result.rawset("fields", zbFields(obj, bPrivate));
        result.rawset("methods", zbMethods(obj, bPrivate));
        return result;
    }

    @LuaMethod(name = "zbmethod", global = true)
    public static KahluaTable zbMethod(Object obj) {
        return ZombieBuddy.getCallableInfo(obj);
    }

    @LuaMethod(name = "zbmethods", global = true)
    public static KahluaTable zbMethods(Object obj) {
        return zbMethods(obj, false);
    }

    @LuaMethod(name = "zbmethods", global = true)
    public static KahluaTable zbMethods(Object obj, Boolean bPrivate) {
        if (obj == null) {
            return null;
        }

        boolean includePrivate = bPrivate != null && bPrivate.booleanValue();
        KahluaTable outArr = LuaManager.platform.newTable();

        // If this is a kahlua-exposed Java callable (LuaJavaInvoker / MultiLuaJavaInvoker),
        // return an array of its overload signatures sorted by methodName.
        if (obj instanceof JavaFunction) {
            KahluaTable tmp = LuaManager.platform.newTable();
            Utils.addInvokersInfo(tmp, obj);
            Object invokers = tmp.rawget("invokers");
            if (invokers instanceof KahluaTable invTbl) {
                ArrayList<KahluaTable> invList = new ArrayList<>();
                var it = invTbl.iterator();
                while (it.advance()) {
                    Object v = it.getValue();
                    if (v instanceof KahluaTable) invList.add((KahluaTable) v);
                }
                invList.sort(
                    Comparator.<KahluaTable, String>comparing(
                            t -> (String) t.rawget("methodName"),
                            Comparator.nullsLast(Comparator.naturalOrder())
                    ).thenComparing(
                            t -> (String) t.rawget("methodDebugData"),
                            Comparator.nullsLast(Comparator.naturalOrder())
                    )
                );

                int idx = 1;
                for (KahluaTable t : invList) {
                    Object s = t.rawget("methodDebugData");
                    if (s != null) outArr.rawset(Double.valueOf(idx++), s.toString());
                }
                return outArr;
            }
        }

        Class<?> cls = obj.getClass();
        HashMap<String, ArrayList<String>> byName = new HashMap<>();

        if (includePrivate) {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                Method[] methods = c.getDeclaredMethods();
                for (Method m : methods) {
                    if (m.isSynthetic() || m.isBridge() || m.getDeclaringClass() == Object.class) {
                        continue;
                    }
                    addMethodSignature(byName, m);
                }
            }
        } else {
            Method[] methods = cls.getMethods();
            for (Method m : methods) {
                if (m.isSynthetic() || m.isBridge() || m.getDeclaringClass() == Object.class) {
                    continue;
                }
                addMethodSignature(byName, m);
            }
        }

        ArrayList<String> names = new ArrayList<>(byName.keySet());
        Collections.sort(names);
        int idx = 1;
        for (String name : names) {
            ArrayList<String> sigs = byName.get(name);
            if (sigs == null || sigs.isEmpty()) continue;
            Collections.sort(sigs);
            for (String sig : sigs) {
                outArr.rawset(Double.valueOf(idx++), sig);
            }
        }

        return outArr;
    }

    private static void addMethodSignature(Map<String, ArrayList<String>> byName, Method m) {
        String name = m.getName();
        String sig = buildMethodSignature(m);
        ArrayList<String> list = byName.get(name);
        if (list == null) {
            list = new ArrayList<>();
            byName.put(name, list);
        }
        if (!list.contains(sig)) list.add(sig);
    }

    private static String buildMethodSignature(Method m) {
        StringBuilder sb = new StringBuilder();
        Class<?> rt = m.getReturnType();
        sb.append(rt != null ? rt.getSimpleName() : "void");
        sb.append(' ');
        sb.append(m.getName());
        sb.append('(');
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(", ");
            Class<?> pt = pts[i];
            sb.append(pt != null ? pt.getSimpleName() : "Object");
        }
        sb.append(')');
        return sb.toString();
    }

    @LuaMethod(name = "zbfields", global = true)
    public static KahluaTable zbFields(Object obj) {
        return zbFields(obj, false);
    }

    @LuaMethod(name = "zbfields", global = true)
    public static KahluaTable zbFields(Object obj, Boolean bPrivate) {
        if (obj == null) {
            return null;
        }
        boolean includePrivate = bPrivate != null && bPrivate.booleanValue();
        KahluaTable result = LuaManager.platform.newTable();
        Class<?> cls = obj.getClass();

        if (includePrivate) {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                Field[] fields = c.getDeclaredFields();
                for (Field f : fields) {
                    if (f.isSynthetic()) {
                        continue;
                    }
                    String name = f.getName();
                    Object value = Accessor.tryGet(obj, f, "[inaccessible]");
                    result.rawset(name, value);
                }
            }
        } else {
            Field[] fields = cls.getFields();
            for (Field f : fields) {
                if (f.isSynthetic()) {
                    continue;
                }
                String name = f.getName();
                Object value = Accessor.tryGet(obj, f, "[inaccessible]");
                result.rawset(name, value);
            }
        }

        return result;
    }

    @LuaMethod(name = "zbget", global = true)
    public static Object zbGet(Object obj, String name) {
        return Accessor.tryGet(obj, name, null);
    }

    @LuaMethod(name = "zbget", global = true)
    public static Object zbGet(Object obj, String name, Object defaultValue) {
        return Accessor.tryGet(obj, name, defaultValue);
    }

    @LuaMethod(name = "zbset", global = true)
    public static boolean zbSet(Object obj, String name, Object value) {
        return Accessor.trySet(obj, name, value);
    }

    @LuaMethod(name = "zbcall", global = true)
    public static Object zbCall(Object obj, String name, Object... args) throws ReflectiveOperationException {
        return Accessor.callByName(obj, name, args);
    }

    /**
     * Returns a new Lua table containing the keys of the given table (1-based integer indices).
     * Only works for KahluaTable; returns null otherwise. Kahlua has no built-in "keys" API,
     * so we iterate via {@link KahluaTable#iterator()} and collect keys.
     */
    @LuaMethod(name = "zbkeys", global = true)
    public static KahluaTable zbKeys(Object obj) {
        if (obj instanceof KahluaTable tbl) {
            KahluaTable out = LuaManager.platform.newTable();
            var it = tbl.iterator();
            int i = 1;
            while (it.advance()) {
                out.rawset(i++, it.getKey());
            }
            return out;
        }
        if (obj instanceof Map<?, ?> map) {
            KahluaTable out = LuaManager.platform.newTable();
            int i = 1;
            for (Object k : map.keySet()) {
                out.rawset(i++, k);
            }
            return out;
        }
        return null;
    }

    @LuaMethod(name = "zbvalues", global = true)
    public static KahluaTable zbValues(Object obj) {
        if (obj instanceof KahluaTable tbl) {
            KahluaTable out = LuaManager.platform.newTable();
            var it = tbl.iterator();
            int i = 1;
            while (it.advance()) {
                out.rawset(i++, it.getValue());
            }
            return out;
        }
        if (obj instanceof Map<?, ?> map) {
            KahluaTable out = LuaManager.platform.newTable();
            int i = 1;
            for (Object v : map.values()) {
                out.rawset(i++, v);
            }
            return out;
        }
        return null;
    }

    @LuaMethod(name = "zbmap", global = true)
    public static KahluaTable zbMap(Object obj, String methodName) {
        if (obj == null || methodName == null || methodName.isEmpty()) return null;
        return zbMapByMethod(obj, methodName);
    }

    @LuaMethod(name = "zbmap", global = true)
    public static KahluaTable zbMap(Object obj, LuaClosure closure) {
        if (obj == null || closure == null) return null;

        if (obj instanceof KahluaTable tbl) {
            KahluaTable out = LuaManager.platform.newTable();
            var it = tbl.iterator();
            while (it.advance()) {
                Object key = it.getKey();
                Object value = it.getValue();
                LuaReturn ret = LuaManager.caller.protectedCall(LuaManager.thread, closure, key, value);
                if (!ret.isSuccess()) {
                    String msg = ret.getErrorString();
                    String st = ret.getLuaStackTrace();
                    throw new RuntimeException((msg != null ? msg : "lua error") + (st != null ? ("\n" + st) : ""));
                }
                if (ret.isEmpty()) {
                    continue;
                }

                Object newKey;
                Object newVal;
                if (ret.size() == 1) {
                    newKey = key;
                    newVal = ret.get(0);
                } else {
                    newKey = ret.get(0);
                    newVal = ret.get(1);
                }

                // nil key is not allowed in Lua tables; treat it as "skip"
                if (newKey != null) {
                    out.rawset(newKey, newVal);
                }
            }
            return out;
        }

        if (obj instanceof Map<?, ?> map) {
            KahluaTable out = LuaManager.platform.newTable();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object key = e.getKey();
                Object value = e.getValue();
                LuaReturn ret = LuaManager.caller.protectedCall(LuaManager.thread, closure, key, value);
                if (!ret.isSuccess()) {
                    String msg = ret.getErrorString();
                    String st = ret.getLuaStackTrace();
                    throw new RuntimeException((msg != null ? msg : "lua error") + (st != null ? ("\n" + st) : ""));
                }
                if (ret.isEmpty()) {
                    continue;
                }

                Object newKey;
                Object newVal;
                if (ret.size() == 1) {
                    newKey = key;
                    newVal = ret.get(0);
                } else {
                    newKey = ret.get(0);
                    newVal = ret.get(1);
                }

                if (newKey != null) {
                    out.rawset(newKey, newVal);
                }
            }
            return out;
        }

        if (obj instanceof List<?> list) {
            KahluaTable out = LuaManager.platform.newTable();
            for (int i = 0; i < list.size(); i++) {
                Object key = Double.valueOf(i + 1);
                Object value = list.get(i);
                LuaReturn ret = LuaManager.caller.protectedCall(LuaManager.thread, closure, key, value);
                if (!ret.isSuccess()) {
                    String msg = ret.getErrorString();
                    String st = ret.getLuaStackTrace();
                    throw new RuntimeException((msg != null ? msg : "lua error") + (st != null ? ("\n" + st) : ""));
                }
                if (ret.isEmpty()) {
                    continue;
                }

                Object newKey;
                Object newVal;
                if (ret.size() == 1) {
                    newKey = key;
                    newVal = ret.get(0);
                } else {
                    newKey = ret.get(0);
                    newVal = ret.get(1);
                }

                if (newKey != null) {
                    out.rawset(newKey, newVal);
                }
            }
            return out;
        }

        return null;
    }

    private static Object invokeNoArgMethod(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static KahluaTable zbMapByMethod(Object obj, String methodName) {
        if (obj instanceof KahluaTable tbl) {
            KahluaTable out = LuaManager.platform.newTable();
            var it = tbl.iterator();
            while (it.advance()) {
                Object key = it.getKey();
                Object value = it.getValue();
                Object result = invokeNoArgMethod(value, methodName);
                out.rawset(key, result != null ? result : value);
            }
            return out;
        }
        if (obj instanceof Map<?, ?> map) {
            KahluaTable out = LuaManager.platform.newTable();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object key = e.getKey();
                Object value = e.getValue();
                Object result = invokeNoArgMethod(value, methodName);
                out.rawset(key, result != null ? result : value);
            }
            return out;
        }
        if (obj instanceof List<?> list) {
            KahluaTable out = LuaManager.platform.newTable();
            for (int i = 0; i < list.size(); i++) {
                Object value = list.get(i);
                Object result = invokeNoArgMethod(value, methodName);
                out.rawset(Double.valueOf(i + 1), result != null ? result : value);
            }
            return out;
        }
        return null;
    }

    @LuaMethod(name = "zbgrep", global = true)
    public static KahluaTable zbGrep(Object obj, String pattern) {
        return zbGrep(obj, pattern, false);
    }

    @LuaMethod(name = "zbgrep", global = true)
    public static KahluaTable zbGrep(Object obj, String pattern, boolean caseSensitive) {
        if (obj == null || pattern == null) return null;
        KahluaTable out = LuaManager.platform.newTable();

        if (obj instanceof KahluaTable tbl) {
            var it = tbl.iterator();
            while (it.advance()) {
                String keyStr = it.getKey().toString();
                String valStr = it.getValue().toString();
                if (matches(keyStr, valStr, pattern, caseSensitive)) {
                    out.rawset(it.getKey(), it.getValue());
                }
            }
        } else if (obj instanceof List<?> list) {
            int i = 1;
            for (Object item : list) {
                String s = item != null ? item.toString() : "null";
                if (matches(s, pattern, caseSensitive)) {
                    out.rawset(i++, item);
                }
            }
        } else if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String keyStr = e.getKey() != null ? e.getKey().toString() : "null";
                String valStr = e.getValue() != null ? e.getValue().toString() : "null";
                if (matches(keyStr, valStr, pattern, caseSensitive)) {
                    out.rawset(e.getKey(), e.getValue());
                }
            }
        } else {
            KahluaTable inspected = zbInspect(obj);
            if (inspected == null) return null;

            KahluaTable result = LuaManager.platform.newTable();
            result.rawset("fields", zbGrep(inspected.rawget("fields"), pattern, caseSensitive));
            result.rawset("methods", zbGrep(inspected.rawget("methods"), pattern, caseSensitive));
            return result;
        }
        return out;
    }

    private static boolean matches(String a, String pattern, boolean caseSensitive) {
        if (caseSensitive) return a.contains(pattern);
        return a.toLowerCase().contains(pattern.toLowerCase());
    }

    private static boolean matches(String a, String b, String pattern, boolean caseSensitive) {
        if (caseSensitive) return a.contains(pattern) || b.contains(pattern);
        String p = pattern.toLowerCase();
        return a.toLowerCase().contains(p) || b.toLowerCase().contains(p);
    }

    /**
     * Reads the game console log file (same as HTTP log endpoint) and returns a Lua table
     * of lines that contain the given substring (1-based indices). Returns null if the
     * file cannot be read or substring is null/empty.
     */
    @LuaMethod(name = "zbgreplog", global = true)
    public static KahluaTable zbgreplog(String substring) {
        if (substring == null || substring.isEmpty()) {
            return null;
        }
        String logPath = ExpUtils.getConsoleLogPath();
        List<String> matches = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(logPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(substring)) {
                    matches.add(line);
                }
            }
        } catch (IOException e) {
            return null;
        }
        KahluaTable out = LuaManager.platform.newTable();
        for (int i = 0; i < matches.size(); i++) {
            out.rawset(i + 1, matches.get(i));
        }
        return out;
    }
}
