package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.*;

import java.io.File;
import java.net.URI;

public class Patch_hide_dirs {

    @Patch(className= "zombie.ZomboidFileSystem", methodName= "walkGameAndModFilesInternal")
    public static class Patch_ZFS_walkGameAndModFilesInternal {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) File rootFile, @Patch.Argument(1) String relPath) {
            if (DirHider.shouldHide(rootFile, relPath)) {
                Logger.info("[HIDE] " + rootFile + ", " + relPath);
                return true;
            }
            return false;
        }
    }

    @Patch(className= "zombie.ZomboidFileSystem", methodName= "listAllFilesInternal")
    public static class Patch_ZFS_listAllFilesInternal {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) File folder) {
            if (DirHider.shouldHide(folder)) {
                Logger.info("[HIDE] " + folder);
                return true;
            }
            return false;
        }
    }

    @Patch(className= "zombie.ZomboidFileSystem", methodName= "listAllDirectoriesInternal")
    public static class Patch_ZFS_listAllDirectoriesInternal {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) File folder) {
            if (DirHider.shouldHide(folder)) {
                Logger.info("[HIDE] " + folder);
                return true;
            }
            return false;
        }
    }

    @Patch(className="zombie.ZomboidFileSystem", methodName="searchFolders")
    public static class Patch_ZFS_searchFolders {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) File fo) {
            if (DirHider.shouldHide(fo)) {
                Logger.info("[HIDE] " + fo);
                return true;
            }
            return false;
        }
    }

    @Patch(className="zombie.Lua.LuaManager", methodName="searchFolders")
    public static class Patch_LuaMgr_searchFolders {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) URI base, @Patch.Argument(1) File fo) {
            if (DirHider.shouldHide(base, fo)) {
                Logger.info("[HIDE] " + base + ", " + fo);
                return true;
            }
            return false;
        }
    }

    @Patch(className="zombie.scripting.ScriptManager", methodName="searchFolders")
    public static class Patch_ScriptMgr_searchFolders {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) URI base, @Patch.Argument(1) File fo) {
            if (DirHider.shouldHide(base, fo)) {
                Logger.info("[HIDE] " + base + ", " + fo);
                return true;
            }
            return false;
        }
    }

    @Patch(className="zombie.radio.RadioData", methodName="searchForFiles")
    public static class Patch_RadioData_searchForFiles {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) File path) {
            if (DirHider.shouldHide(path)) {
                Logger.info("[HIDE] " + path);
                return true;
            }
            return false;
        }
    }
}
