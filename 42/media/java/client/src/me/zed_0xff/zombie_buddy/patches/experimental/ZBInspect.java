package me.zed_0xff.zombie_buddy.patches.experimental;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.Lua.LuaManager;

public class ZBInspect {

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
        KahluaTable methods = zbMethods(obj, bPrivate);
        KahluaTable fields = zbFields(obj, bPrivate);
        KahluaTableIterator it = methods.iterator();
        while (it.advance()) {
            result.rawset(it.getKey(), it.getValue());
        }
        it = fields.iterator();
        while (it.advance()) {
            result.rawset(it.getKey(), it.getValue());
        }
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
                    try {
                        f.setAccessible(true);
                        Object value = f.get(obj);
                        result.rawset(name, value);
                    } catch (Throwable t) {
                        result.rawset(name, "[inaccessible]");
                    }
                }
            }
        } else {
            Field[] fields = cls.getFields();
            for (Field f : fields) {
                if (f.isSynthetic()) {
                    continue;
                }
                String name = f.getName();
                try {
                    Object value = f.get(obj);
                    result.rawset(name, value);
                } catch (Throwable t) {
                    result.rawset(name, "[inaccessible]");
                }
            }
        }

        return result;
    }
}
