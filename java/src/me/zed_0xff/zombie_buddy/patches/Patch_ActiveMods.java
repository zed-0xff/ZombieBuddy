// package me.zed_0xff.zombie_buddy.patches;
// 
// import me.zed_0xff.zombie_buddy.Logger;
// import me.zed_0xff.zombie_buddy.Patch;
// 
// public class Patch_ActiveMods {
//     @Patch(className = "zombie.modding.ActiveMods", methodName = "checkMissingMods")
//     public class Patch_checkMissingMods {
//         @Patch.OnEnter
//         public static void enter(@Patch.Local("t0") long t0) {
//             Logger.info("ActiveMods.checkMissingMods() ...");
//             t0 = System.nanoTime();
//         }
// 
//         @Patch.OnExit
//         public static void exit(@Patch.Local("t0") long t0) {
//             long vanillaElapsedMs = (System.nanoTime() - t0) / 1_000_000L;
//             Logger.info("ActiveMods.checkMissingMods() took " + vanillaElapsedMs + " ms");
//         }
//     }
// 
//     public class Patch_checkMissingMaps {
//         @Patch.OnEnter
//         public static void enter(@Patch.Local("t0") long t0) {
//             Logger.info("ActiveMods.checkMissingMaps() ...");
//             t0 = System.nanoTime();
//         }
//     }
// 
//     @Patch.OnExit
//     public static void exit(@Patch.Local("t0") long t0) {
//         long vanillaElapsedMs = (System.nanoTime() - t0) / 1_000_000L;
//         Logger.info("ActiveMods.checkMissingMaps() took " + vanillaElapsedMs + " ms");
//     }
// }
