package me.zed_0xff.zombie_buddy.patches;

import java.lang.reflect.Modifier;

import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

public class Patch_Exposer {
    @Patch(className = "zombie.Lua.LuaManager$Exposer", methodName = "exposeAll", warmUp = true, IKnowWhatIAmDoing = true)
    public static class Patch_exposeAll {
        @Patch.OnEnter
        public static void enter() {
            Logger.out.println("[ZB] before Exposer.exposeAll");

            if ( zombie.Lua.LuaManager.exposer == null ) {
                Logger.out.println("[ZB] Error! LuaManager.exposer is null!");
                return;
            }

            // Full class exposure: only for classes in Exposer.getExposedClasses()
            for (Class<?> cls : Exposer.getExposedClasses()) {
                Logger.out.println("[ZB] Exposing class to Lua: " + cls.getName());
                zombie.Lua.LuaManager.exposer.setExposed(cls);
            }
            // Global functions: for every class that has @LuaMethod(global=true), even if not fully exposed
            for (Class<?> cls : Exposer.getClassesWithGlobalLuaMethod()) {
                Object instance = newInstance(cls);
                if (instance != null) {
                    try {
                        Logger.out.println("[ZB] Exposing global functions from class: " + cls.getName());
                        zombie.Lua.LuaManager.exposer.exposeGlobalFunctions(instance);
                    } catch (Exception e) {
                        Logger.err.println("[ZB] exposeGlobalFunctions(" + cls.getName() + "): " + e.getMessage());
                    }
                }
            }
        }

        public static Object newInstance(Class<?> cls) {
            try {
                if (Modifier.isAbstract(cls.getModifiers()) || cls.isInterface()) {
                    return null;
                }
                return cls.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
