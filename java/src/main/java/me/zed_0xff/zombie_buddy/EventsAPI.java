package me.zed_0xff.zombie_buddy;

import java.util.ArrayList;
import java.util.HashMap;

import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;

import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.j2se.KahluaTableImpl;

public class EventsAPI {

    /** __index handler: when Lua does events.EventName and key is missing, call getByName(key). */
    private static final JavaFunction INDEX_GET_BY_NAME = (callFrame, nArgs) -> {
        Object key = nArgs >= 2 ? callFrame.get(1) : null;
        String name = key != null ? key.toString() : null;
        Object result = getByName(name);
        return callFrame.push(result);
    };

    public static void init() {
        var zb = LuaManager.env.rawget("ZombieBuddy");
        if (zb instanceof KahluaTable tbl) {
            var events = LuaManager.platform.newTable();
            try {
                LuaManager.exposer.exposeGlobalClassFunction(events, EventsAPI.class, EventsAPI.class.getMethod("getAll"), "getAll");
                LuaManager.exposer.exposeGlobalClassFunction(events, EventsAPI.class, EventsAPI.class.getMethod("getByName", String.class), "getByName");
                LuaManager.exposer.exposeGlobalClassFunction(events, EventsAPI.class, EventsAPI.class.getMethod("getByFile", String.class), "getByFile");
            } catch (ReflectiveOperationException e) {
                Logger.error("Error exposing static methods: " + e.getMessage());
            }
            var mt = LuaManager.platform.newTable();
            mt.rawset("__index", INDEX_GET_BY_NAME);
            events.setMetatable(mt);
            tbl.rawset("Events", events);
        } else {
            Logger.error("ZombieBuddy table not found");
        }
    }

    public static Object getAll() {
        if (LuaManager.platform == null) {
            return null;
        }

        var eventList = new ArrayList<Event>();
        var eventMap = new HashMap<String, Event>();

        LuaEventManager.getEvents(eventList, eventMap);

        var tbl = LuaManager.platform.newTable();
        for (var event : eventList) {
            tbl.rawset(event.name, Accessor.tryGet(event, "callbacks", null));
        }
        return tbl;
    }
 
    public static Object getByName(String eventName) {
        if (LuaManager.platform == null) {
            return null;
        }

        var eventList = new ArrayList<Event>();
        var eventMap = new HashMap<String, Event>();

        LuaEventManager.getEvents(eventList, eventMap);

        var event = eventMap.get(eventName);
        if (event == null) {
            return null;
        }
        return event.callbacks;
    }

    public static Object getByFile(String filename) {
        if (LuaManager.platform == null || filename == null) {
            return null;
        }

        var eventList = new ArrayList<Event>();
        var eventMap = new HashMap<String, Event>();

        LuaEventManager.getEvents(eventList, eventMap);

        var tbl = LuaManager.platform.newTable();
        var tbl2 = LuaManager.platform.newTable();

        for (var event : eventList) {
            ArrayList<LuaClosure> callbacks = Accessor.tryGet(event, "callbacks", null);
            if (callbacks != null) {
                int idx = 1;
                for (var callback : callbacks) {
                    if (filename.equals(ZombieBuddy.getClosureFilename(callback))) {
                        tbl2.rawset(idx++, callback);
                    }
                }
                if (idx > 1) {
                    tbl.rawset(event.name, tbl2);
                    tbl2 = LuaManager.platform.newTable();
                }
            }
        }
        return tbl;
    }
}
