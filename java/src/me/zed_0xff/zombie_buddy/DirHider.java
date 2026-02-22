package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class DirHider {
    private static boolean g_enabled = true;

    public static void setEnabled(boolean enabled) {
        g_enabled = enabled;
    }

    public static boolean isEnabled() {
        return g_enabled;
    }

    private static final Set<String> HIDE_NAMES = new HashSet<>(Arrays.asList(
        "tmp",
        ".git",
        ".hg",
        ".svn",
        ".vscode",
        ".idea",
        ".DS_Store"
    ));

    private static boolean shouldHideName(String name) {
        return name != null && HIDE_NAMES.contains(name.toLowerCase());
    }

    private static boolean shouldHidePath(Path path) {
        if (path == null) return false;
        if (path.getNameCount() == 0 ) return false;

        for (Path element : path) {
            if (shouldHideName(element.toString())) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldHide(File file) {
        return g_enabled && (file != null) && shouldHidePath(file.toPath());
    }

    public static boolean shouldHide(File base, String relPath) {
        if (!g_enabled) return false;
        if (base != null && shouldHidePath(base.toPath())) return true;
        if (relPath != null && shouldHidePath(Path.of(relPath))) return true;
        return false;
    }

    public static boolean shouldHide(URI base, File relPath) {
        if (!g_enabled) return false;
        if (base != null && shouldHidePath(Path.of(base))) return true;
        if (relPath != null && shouldHidePath(relPath.toPath())) return true;
        return false;
    }
}
