package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Patch;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// getRelativeFile(
//  file:/users/zed/zomboid/mods/zscienceskill/42.13/,
//      "/Users/zed/Zomboid/mods/ZScienceSkill/tmp/cache_client_42.13.2/mods/ZScienceSkill/42.13/media/scripts/ZScienceSkill_items.txt"
// ) => "/Users/zed/Zomboid/mods/ZScienceSkill/tmp/cache_client_42.13.2/mods/ZScienceSkill/42.13/media/scripts/ZScienceSkill_items.txt"
//
// but should've returned relative path. it because it's a symlink
//
// getRelativeFile(
//  file:/Users/zed/Zomboid/mods/ZBSpec/mods/ZBSpec/41/,
//      "/Users/zed/Zomboid/mods/ZBSpec/mods/ZBSpec/common/media/lua/shared/zbsHook.lua"
// ) => "/Users/zed/Zomboid/mods/ZBSpec/mods/ZBSpec/common/media/lua/shared/zbsHook.lua"
//
// readlink /Users/zed/Zomboid/mods/ZBSpec/mods/ZBSpec/41/media/lua => ../../common/media/lua

public class Fix_ZFS_getRelativeFile_symlinks {
    // rootCanonical -> (linkCanonical -> logicalRelativePath)
    public static final Map<String, Map<String, String>> symlinkMappings = new ConcurrentHashMap<>();
    
    public static String fixResult(URI root, String path, String result) {
        if (result == null || path == null || root == null) return result;
        if (!result.equals(path) || !path.startsWith("/")) return result;
        if (!"file".equals(root.getScheme())) return result;

        try {
            File rootFile = new File(root);
            String rootCanonical = rootFile.getCanonicalPath();
            String pathCanonical = new File(path).getCanonicalPath();
            
            // Direct case: path is directly under root
            if (pathCanonical.startsWith(rootCanonical + File.separator)) {
                return pathCanonical.substring(rootCanonical.length() + 1).replace(File.separatorChar, '/');
            }
            
            // Symlink case: check cached mappings
            Map<String, String> mappings = symlinkMappings.computeIfAbsent(rootCanonical,
                k -> scanSymlinks(rootFile, 2));
            
            for (var entry : mappings.entrySet()) {
                String linkCanonical = entry.getKey();
                if (pathCanonical.startsWith(linkCanonical + File.separator)) {
                    String remainder = pathCanonical.substring(linkCanonical.length() + 1).replace(File.separatorChar, '/');
                    return entry.getValue() + "/" + remainder;
                }
                if (pathCanonical.equals(linkCanonical)) {
                    return entry.getValue();
                }
            }
        } catch (Exception ignored) {}
        return result;
    }
    
    private static Map<String, String> scanSymlinks(File dir, int depth) {
        Map<String, String> result = new ConcurrentHashMap<>();
        scanSymlinksRecursive(dir, dir.toPath().toAbsolutePath().normalize(), result, depth);
        return result;
    }
    
    private static void scanSymlinksRecursive(File dir, java.nio.file.Path rootPath, Map<String, String> result, int depth) {
        if (depth < 0) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        
        for (File child : children) {
            try {
                var childPath = child.toPath();
                if (Files.isSymbolicLink(childPath)) {
                    String logical = rootPath.relativize(childPath.toAbsolutePath().normalize())
                        .toString().replace(File.separatorChar, '/');
                    result.put(child.getCanonicalPath(), logical);
                } else if (depth > 0 && child.isDirectory()) {
                    scanSymlinksRecursive(child, rootPath, result, depth - 1);
                }
            } catch (Exception ignored) {}
        }
    }
    
    @Patch(className = "zombie.ZomboidFileSystem", methodName = "getRelativeFile")
    public static class Patch_ZFS_getRelativeFile {
        @Patch.OnExit
        public static void exit(URI root, String path, @Patch.Return(readOnly = false) String result) {
            result = fixResult(root, path, result);
        }
    }
}
