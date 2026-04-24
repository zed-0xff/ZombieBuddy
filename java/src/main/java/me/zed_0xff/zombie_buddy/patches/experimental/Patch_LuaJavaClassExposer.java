// package me.zed_0xff.zombie_buddy.patches.experimental;
// 
// import me.zed_0xff.zombie_buddy.Logger;
// import me.zed_0xff.zombie_buddy.Patch;
// 
// import zombie.Lua.LuaManager;
// import se.krka.kahlua.vm.KahluaTable;
// 
// // just to check that last argument is always LuaManager.env
// @Patch(className = "se.krka.kahlua.integration.expose.LuaJavaClassExposer", methodName = "exposeMethod")
// public class Patch_LuaJavaClassExposer {
//     @Patch.OnExit
//     public static void enter(
//         @Patch.Argument(2) String methodName,
//         @Patch.Argument(3) KahluaTable staticBase
//     ) {
//         Logger.debug("exposeMethod", methodName, staticBase, LuaManager.env);
//     }
// }
