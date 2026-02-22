package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// getRelativeFile(
//      file:/users/zed/zomboid/mods/zscienceskill/42.13/,
//      "/Users/zed/Zomboid/mods/ZScienceSkill/tmp/cache_client_42.13.2/mods/ZScienceSkill/42.13/media/scripts/ZScienceSkill_items.txt"
// ) => "/Users/zed/Zomboid/mods/ZScienceSkill/tmp/cache_client_42.13.2/mods/ZScienceSkill/42.13/media/scripts/ZScienceSkill_items.txt"
//
// but should've returned relative path. it because it's a symlink

public class Fix_ZFS_getRelativeFile_symlinks {
    @Patch(className= "zombie.ZomboidFileSystem", methodName= "getRelativeFile")
    public static class Patch_ZFS_getRelativeFile {
        @Patch.OnExit
        public static void exit(URI root, String path, @Patch.Return(readOnly = false) String result) {
            if (result == null || path == null || root == null) return;
            if (!result.equals(path) || !path.startsWith("/")) return;

            try {
                File rootFile = new File(root);
                String rootCanonical = rootFile.getCanonicalPath();
                File current = new File(path);
                List<String> segments = new ArrayList<>();
                while (current != null) {
                    File parent = current.getParentFile();
                    if (parent == null) return;
                    if (parent.getCanonicalPath().equals(rootCanonical)) {
                        segments.add(current.getName());
                        Collections.reverse(segments);
                        result = String.join("/", segments);
                        Logger.info("Fixed getRelativeFile() result: " + result);
                        return;  // Patch.Return: result is written back
                    }
                    segments.add(current.getName());
                    current = parent;
                }
            } catch (Exception e) {
                // leave result unchanged
            }
        }
    }
}