// package me.zed_0xff.zombie_buddy.patches.experimental;
// 
// import me.zed_0xff.zombie_buddy.Logger;
// import me.zed_0xff.zombie_buddy.Patch;
// 
// public class Patch_ChooseGameInfo {
//     @Patch(className = "zombie.gameStates.ChooseGameInfo", methodName = "getAvailableModDetails")
//     public class Patch_getAvailableModDetails {
//         @Patch.OnEnter
//         public static void enter(String modId, @Patch.Local("t0") long t0) {
//             // Logger.info("ChooseGameInfo.getAvailableModDetails(\"" + modId + "\") ...");
//             t0 = System.nanoTime();
//         }
// 
//         @Patch.OnExit
//         public static void exit(String modId, @Patch.Local("t0") long t0) {
//             long vanillaElapsedMs = (System.nanoTime() - t0) / 1_000_000L;
//             if ( vanillaElapsedMs > 1000 ) Logger.info("ChooseGameInfo.getAvailableModDetails(\"" + modId + "\") took " + vanillaElapsedMs + " ms");
//         }
//     }
// 
//     @Patch(className = "zombie.gameStates.ChooseGameInfo", methodName = "getModDetails")
//     public class Patch_getModDetails {
//         @Patch.OnEnter
//         public static void enter(String modId, @Patch.Local("t0") long t0) {
//             // Logger.info("ChooseGameInfo.getModDetails(\"" + modId + "\") ...");
//             t0 = System.nanoTime();
//         }
// 
//         @Patch.OnExit
//         public static void exit(String modId, @Patch.Local("t0") long t0) {
//             long vanillaElapsedMs = (System.nanoTime() - t0) / 1_000_000L;
//             if ( vanillaElapsedMs > 1000 ) Logger.info("ChooseGameInfo.getModDetails(\"" + modId + "\") took " + vanillaElapsedMs + " ms");
//         }
//     }
// }
