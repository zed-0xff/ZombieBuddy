package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Loader;
import me.zed_0xff.zombie_buddy.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import zombie.core.Core;
import zombie.GameWindow;

public class Patch_ZomboidFileSystem {
    // @Patch(className = "zombie.ZomboidFileSystem", methodName = "loadMods")
    // public class Patch_loadMods1 {
    //     @Patch.OnEnter
    //     public static void enter(String activeMods, @Patch.Local("t0") long t0) {
    //         Logger.info("ZomboidFileSystem.loadMods(\"" + activeMods + "\") ...");
    //         t0 = System.nanoTime();
    //     }
    // 
    //     @Patch.OnExit
    //     public static void exit(String activeMods, @Patch.Local("t0") long t0) {
    //         long vanillaElapsedMs = (System.nanoTime() - t0) / 1_000_000L;
    //         if ( vanillaElapsedMs > 1000 ) Logger.info("ZomboidFileSystem.loadMods(\"" + activeMods + "\") took " + vanillaElapsedMs + " ms");
    //     }
    // }

    @Patch(className = "zombie.ZomboidFileSystem", methodName = "loadMods")
    public class Patch_loadMods2 {
        @Patch.OnEnter
        public static void enter(ArrayList<String> mods, @Patch.Local("t0") long t0) {
            Logger.info("ZomboidFileSystem.loadMods(" + mods.size() + " mods) ...");
            long loaderStartNs = System.nanoTime();
            Loader.loadMods(mods);
            long loaderElapsedMs = (System.nanoTime() - loaderStartNs) / 1_000_000L;
            if ( loaderElapsedMs > 1000 ) Logger.info("Loader.loadMods() took " + loaderElapsedMs + " ms");
            t0 = System.nanoTime();
        }

        @Patch.OnExit
        public static void exit(ArrayList<String> mods, @Patch.Local("t0") long t0) {
            long vanillaElapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            if ( vanillaElapsedMs > 1000 ) Logger.info("ZomboidFileSystem.loadMods(" + mods.size() + " mods) took " + vanillaElapsedMs + " ms");
        }
    }

    // @Patch(className = "zombie.ZomboidFileSystem", methodName = "getAllModFolders")
    // public class Patch_getAllModFolders {
    //     @Patch.OnEnter
    //     public static void enter(@Patch.Local("t0") long t0) {
    //         t0 = System.nanoTime();
    //     }
    // 
    //     @Patch.OnExit
    //     public static void exit(List<String> out, @Patch.Local("t0") long t0) {
    //         long vanillaElapsedMs = (System.nanoTime() - t0) / 1_000_000L;
    //         if ( vanillaElapsedMs > 1000 ) Logger.info("ZomboidFileSystem.getAllModFolders() took " + vanillaElapsedMs + " ms");
    //     }
    // }

    @Patch(className = "zombie.ZomboidFileSystem", methodName = "getAllModFoldersAux")
    public class Patch_getAllModFoldersAux {
        public static Boolean _is41 = null;

        @Patch.OnExit
        public static void exit(String str, List<String> list) {
            if (_is41 == null) {
                _is41 = Core.getInstance().getGameVersion().getMajor() == 41;
            }
            if (_is41) {
                append_subdir_41(str, list);
            }
        }

        public static void append_subdir_41(String str, List<String> list) {
            DirectoryStream.Filter<Path> filter = new DirectoryStream.Filter<Path>() {
                @Override
                public boolean accept(Path path) throws IOException {
                    return Files.isDirectory(path, new LinkOption[0])
                            && Files.isDirectory(path.resolve("41"), new LinkOption[0])
                            && Files.exists(path.resolve("41/mod.info"), new LinkOption[0]);
                }
            };
            Path path = FileSystems.getDefault().getPath(str, new String[0]);
            if (!Files.exists(path, new LinkOption[0])) {
                return;
            }
            try {
                DirectoryStream<Path> directoryStreamNewDirectoryStream = Files.newDirectoryStream(path, filter);
                try {
                    for (Path path2 : directoryStreamNewDirectoryStream) {
                        String prevPath = path2.toAbsolutePath().toString();
                        // skip if already in list
                        if (list.contains(prevPath)) {
                            continue;
                        }

                        String newPath = path2.resolve("41").toAbsolutePath().toString();
                        if (list.contains(newPath)) {
                            continue;
                        }
                        // if (!this.m_watchedModFolders.contains(string)) {
                        //     this.m_watchedModFolders.add(string);
                        //     DebugFileWatcher.instance.addDirectory(string);
                        //     Path pathResolve = path2.resolve("media");
                        //     if (Files.exists(pathResolve, new LinkOption[0])) {
                        //         DebugFileWatcher.instance.addDirectoryRecurse(pathResolve.toAbsolutePath().toString());
                        //     }
                        // }
                        Logger.info("adding mod folder: " + newPath);
                        list.add(newPath);
                    }
                    if (directoryStreamNewDirectoryStream != null) {
                        directoryStreamNewDirectoryStream.close();
                    }
                } finally {
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // @Patch(className = "zombie.ZomboidFileSystem", methodName = "loadMod")
    // public class Patch_loadMod {
    //     @Patch.OnEnter
    //     public static void enter(String modId) {
    //         GameWindow.DoLoadingText("Loading mod: " + modId);
    //     }
    // }

    // // loadModAndRequired calls loadModsAux, which calls loadModAndRequired
    // @Patch(className = "zombie.ZomboidFileSystem", methodName = "loadModAndRequired")
    // public class Patch_loadModAndRequired {
    //     public static int _depth = 0;

    //     @Patch.OnEnter
    //     public static void enter(@Patch.Argument(0) String modId) {
    //         if (_depth == 0) {
    //             GameWindow.DoLoadingText("Loading mod: " + modId);
    //         }
    //         _depth++;
    //     }

    //     @Patch.OnExit
    //     public static void exit(@Patch.Argument(0) String modId) {
    //         _depth--;
    //     }
    // }

    // @Patch(className = "zombie.ZomboidFileSystem", methodName = "loadModPackFiles")
    // public class Patch_loadModPackFiles {
    //     @Patch.OnEnter
    //     public static void enter() {
    //         GameWindow.DoLoadingText("Loading pack files");
    //     }
    // }
}
