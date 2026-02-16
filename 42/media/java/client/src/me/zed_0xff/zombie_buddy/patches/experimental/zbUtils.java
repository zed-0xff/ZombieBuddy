package me.zed_0xff.zombie_buddy.patches.experimental;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Accessor;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

public class zbUtils {

    @LuaMethod(name = "zbInspect", global = true)
    public static KahluaTable zbInspect(Object obj) {
        return zbInspect(obj, false);
    }

    @LuaMethod(name = "zbInspect", global = true)
    public static KahluaTable zbInspect(Object obj, Boolean bPrivate) {
        if (obj == null) {
            return null;
        }
        KahluaTable result = LuaManager.platform.newTable();
        result.rawset("fields", zbFields(obj, bPrivate));
        result.rawset("methods", zbMethods(obj, bPrivate));
        return result;
    }

    @LuaMethod(name = "zbMethods", global = true)
    public static KahluaTable zbMethods(Object obj) {
        return zbMethods(obj, false);
    }

    @LuaMethod(name = "zbMethods", global = true)
    public static KahluaTable zbMethods(Object obj, Boolean bPrivate) {
        if (obj == null) {
            return null;
        }
        boolean includePrivate = bPrivate != null && bPrivate.booleanValue();
        KahluaTable result = LuaManager.platform.newTable();
        Class<?> cls = obj.getClass();

        if (includePrivate) {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                Method[] methods = c.getDeclaredMethods();
                for (Method m : methods) {
                    if (m.isSynthetic() || m.isBridge() || m.getDeclaringClass() == Object.class) {
                        continue;
                    }
                    String name = m.getName();
                    int paramCount = m.getParameterTypes().length;
                    Object existing = result.rawget(name);
                    if (existing instanceof Double) {
                        int prev = ((Double) existing).intValue();
                        if (paramCount <= prev) {
                            continue;
                        }
                    }
                    result.rawset(name, (double) paramCount);
                }
            }
        } else {
            Method[] methods = cls.getMethods();
            for (Method m : methods) {
                if (m.isSynthetic() || m.isBridge() || m.getDeclaringClass() == Object.class) {
                    continue;
                }
                String name = m.getName();
                int paramCount = m.getParameterTypes().length;
                Object existing = result.rawget(name);
                if (existing instanceof Double) {
                    int prev = ((Double) existing).intValue();
                    if (paramCount <= prev) {
                        continue;
                    }
                }
                result.rawset(name, (double) paramCount);
            }
        }

        return result;
    }

    @LuaMethod(name = "zbFields", global = true)
    public static KahluaTable zbFields(Object obj) {
        return zbFields(obj, false);
    }

    @LuaMethod(name = "zbFields", global = true)
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

    @LuaMethod(name = "zbGet", global = true)
    public static Object zbGet(Object obj, String name) {
        return Accessor.tryGet(obj, name, null);
    }

    @LuaMethod(name = "zbGet", global = true)
    public static Object zbGet(Object obj, String name, Object defaultValue) {
        return Accessor.tryGet(obj, name, defaultValue);
    }

    @LuaMethod(name = "zbSet", global = true)
    public static boolean zbSet(Object obj, String name, Object value) {
        return Accessor.trySet(obj, name, value);
    }

    @LuaMethod(name = "zbCall", global = true)
    public static Object zbCall(Object obj, String name, Object... args) throws ReflectiveOperationException {
        return Accessor.callByName(obj, name, args);
    }

    /**
     * Returns a new Lua table containing the keys of the given table (1-based integer indices).
     * Only works for KahluaTable; returns null otherwise. Kahlua has no built-in "keys" API,
     * so we iterate via {@link KahluaTable#iterator()} and collect keys.
     */
    @LuaMethod(name = "zbKeys", global = true)
    public static KahluaTable zbKeys(Object obj) {
        if (!(obj instanceof KahluaTable tbl)) {
            return null;
        }
        KahluaTable out = LuaManager.platform.newTable();
        var it = tbl.iterator();
        int i = 1;
        while (it.advance()) {
            out.rawset(i++, it.getKey());
        }
        return out;
    }

    @LuaMethod(name = "zbValues", global = true)
    public static KahluaTable zbValues(Object obj) {
        if (!(obj instanceof KahluaTable tbl)) {
            return null;
        }
        KahluaTable out = LuaManager.platform.newTable();
        var it = tbl.iterator();
        int i = 1;
        while (it.advance()) {
            out.rawset(i++, it.getValue());
        }
        return out;
    }

    @LuaMethod(name = "zbGrep", global = true)
    public static KahluaTable zbGrep(Object obj, String pattern) {
        if (!(obj instanceof KahluaTable tbl)) {
            return null;
        }
        KahluaTable out = LuaManager.platform.newTable();
        var it = tbl.iterator();
        while (it.advance()) {
            if (it.getKey().toString().contains(pattern) || it.getValue().toString().contains(pattern)) {
                out.rawset(it.getKey(), it.getValue());
            }
        }
        return out;
    }
}
