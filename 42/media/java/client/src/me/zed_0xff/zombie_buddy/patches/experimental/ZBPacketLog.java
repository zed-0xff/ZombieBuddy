package me.zed_0xff.zombie_buddy.patches.experimental;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import se.krka.kahlua.vm.KahluaTable;

import me.zed_0xff.zombie_buddy.Exposer;

/**
 * Control plane for getPacket() logging. By default logging is disabled.
 * Lua can enable/disable and filter by packet class name (simple name, no package).
 */
@Exposer.LuaClass
public class ZBPacketLog {
    private static volatile boolean enabled = false;
    private static final Set<String> include = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> exclude = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Returns true if getPacket() should log the given packet (simple class name). */
    public static boolean shouldLog(String simpleClassName) {
        if (!enabled) return false;
        if (exclude.contains(simpleClassName)) return false;
        if (!include.isEmpty() && !include.contains(simpleClassName)) return false;
        return true;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void enable() {
        enabled = true;
    }

    public static void disable() {
        enabled = false;
    }

    /** Add packet class names to include filter. Accepts KahluaTable (array of strings). */
    public static void include(KahluaTable names) {
        addNames(names, include);
    }

    public static void include(String name) {
        include.add(name);
    }

    /** Add packet class names to exclude filter. Accepts KahluaTable (array of strings). */
    public static void exclude(KahluaTable names) {
        addNames(names, exclude);
    }

    public static void exclude(String name) {
        exclude.add(name);
    }

    private static void addNames(KahluaTable t, Set<String> set) {
        if (t == null) return;
        for (int i = 1; ; i++) {
            Object v = t.rawget(Double.valueOf(i));
            if (v == null) break;
            String part = String.valueOf(v).trim();
            if (!part.isEmpty()) set.add(part);
        }
    }

    /** Reset to default: disabled, no include/exclude filters. */
    public static void reset() {
        enabled = false;
        include.clear();
        exclude.clear();
    }
}
