package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

import java.util.List;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.FileSystems;
import java.io.IOException;

import zombie.core.Core;


@Patch(className = "zombie.ZomboidFileSystem", methodName = "getAllModFoldersAux")
public class Patch_searchForModInfo {
    @Patch.OnExit
    public static void exit(String str, List<String> list) {
        Logger.info("after ZomboidFileSystem.getAllModFoldersAux: str:" + str + " list:" + list);

        if (Core.getInstance().getGameVersion().getMajor() == 41) {
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
