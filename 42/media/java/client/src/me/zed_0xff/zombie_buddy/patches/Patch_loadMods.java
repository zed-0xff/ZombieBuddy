package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Loader;
import me.zed_0xff.zombie_buddy.Logger;

import java.util.ArrayList;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import zombie.ZomboidFileSystem;

@Patch(className = "zombie.ZomboidFileSystem", methodName = "loadMods")
public class Patch_loadMods {

    // XXX can't use "@This ZomboidFileSystem self" because it triggers following error:
    //   Exception in thread "main" java.lang.LinkageError:
    //     loader 'app' attempted duplicate class definition for zombie.ZomboidFileSystem. (zombie.ZomboidFileSystem is in unnamed module of loader 'app')
    @Patch.OnEnter
    public static void enter(ArrayList<String> mods) {
        Logger.info("before ZomboidFileSystem.loadMods: " + mods.size() + " mods");

        Loader.loadMods(mods);
    }
}
