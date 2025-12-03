package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.ZombieBuddy;

import net.bytebuddy.asm.Advice;

import net.bytebuddy.implementation.bind.annotation.SuperCall;
import java.util.concurrent.Callable;

// TODO: set className once
public class Patch_LuaManager {
    @Patch(className = "zombie.Lua.LuaManager", methodName = "init", isAdvice = true)
    public class Patch_init {
        @Advice.OnMethodEnter
        public static void enter() {
            System.out.println("[ZB] before LuaManager.init");
            // ZombieBuddy.OnLuaInit();
        }

        @Advice.OnMethodExit
        public static void exit() {
            System.out.println("[ZB] after LuaManager.init");
            // ZombieBuddy.OnLuaInit();
        }
    }

    @Patch(className = "zombie.Lua.LuaManager", methodName = "init", isAdvice = true)
    public class Patch_init2 {
        @Advice.OnMethodEnter
        public static void enter() {
            System.out.println("[ZB] before LuaManager.init 2");
            // ZombieBuddy.OnLuaInit();
        }

        @Advice.OnMethodExit
        public static void exit() {
            System.out.println("[ZB] after LuaManager.init 2");
            // ZombieBuddy.OnLuaInit();
        }
    }

    // @Patch(className = "zombie.Lua.LuaManager", methodName = "init")
    // public class Patch_init3 {
    //     public static void intercept(@SuperCall Callable<?> original) throws Exception {
    //         System.out.println("[ZB] before LuaManager.init 3");
    //         original.call();
    //         System.out.println("[ZB] after LuaManager.init 3");
    //     }
    // }

    // @Patch(className = "zombie.Lua.LuaManager", methodName = "RunLua")
    // public class Patch_RunLua {
    //     public static Object intercept(@SuperCall Callable<?> original, @AllArguments Object[] args) throws Exception {
    //         if (args.length == 2 && args[0] instanceof String fname) {
    //             // System.out.println("[d] LM.RunLua: " + fname);
    //         }
    // 
    //         return original.call();
    //     }
    // }
    // 
    // @Patch(className = "zombie.Lua.LuaManager", methodName = "LoadDirBase")
    // public class Patch_LoadDirBase {
    //     public static Object intercept(@SuperCall Callable<?> original, @AllArguments Object[] args) throws Exception {
    //         if (args.length == 2 && args[0] instanceof String fname) {
    //             System.out.println("[d] LM.LoadDirBase: " + fname);
    //         }
    // 
    //         return original.call();
    //     }
    // }
    // 
    // @Patch(className = "zombie.Lua.LuaManager", methodName = "searchFolders")
    // public class Patch_searchFolders {
    //     public static Object intercept(@SuperCall Callable<?> original, @AllArguments Object[] args) throws Exception {
    //         if (args.length == 2) {
    //             System.out.println("[d] LM.searchFolders: " + args[0] + ", " + args[1]);
    //         }
    // 
    //         return original.call();
    //     }
    // }
}
