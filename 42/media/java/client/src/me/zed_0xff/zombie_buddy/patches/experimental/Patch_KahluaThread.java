package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Patch;

import java.util.Arrays;
import java.util.HashSet;

import zombie.Lua.LuaManager;

public class Patch_KahluaThread {
    public static final HashSet<String> METHODS = new HashSet<>(Arrays.asList(
        "zbCall",
        "zbInspect", 
        "zbGet",
        "zbSet"
    ));

    /**
     * When key is "zbInspect" and table is a Java object (not a KahluaTable), return the global
     * zbInspect function so obj.zbInspect is the function and obj:zbInspect() works.
     */
    @Patch(className = "se.krka.kahlua.vm.KahluaThread", methodName = "tableget")
    public static class Patch_tableget {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(Object table, Object key) {
            return METHODS.contains(key);
        }

        @Patch.OnExit
        public static void exit( Object table, Object key, @Patch.Return(readOnly = false) Object returnValue) {
            if (key instanceof String keyStr) {
                if (METHODS.contains(keyStr)) {
                    returnValue = LuaManager.getFunctionObject(keyStr);
                }
            }
        }
    }
}
