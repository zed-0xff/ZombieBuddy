package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class DirHider {
    private static final Set<String> canonHideNames = new HashSet<>(Arrays.asList(
        "tmp",
        ".git",
        ".hg",
        ".svn",
        ".vscode",
        ".idea",
        ".DS_Store"
    ));

    public static boolean shouldHideName(String name) {
        return name != null && canonHideNames.contains(name.toLowerCase());
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
        return (file != null) && shouldHidePath(file.toPath());
    }

    public static boolean shouldHide(File base, String relPath) {
        return ((base != null) && shouldHidePath(base.toPath()))
         || ((relPath != null) && shouldHidePath(Path.of(relPath)));
    }

    public static boolean shouldHide(URI base, File relPath) {
        return ((base != null) && shouldHidePath(Path.of(base)))
         || ((relPath != null) && shouldHidePath(relPath.toPath()));
    }
}
