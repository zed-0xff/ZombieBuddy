package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Loader;

import net.bytebuddy.asm.Advice;

import zombie.ZomboidFileSystem;

@Patch(className = "zombie.ZomboidFileSystem", methodName = "loadMod")
public class Patch_loadMod {

    // XXX can't use "@This ZomboidFileSystem self" because it triggers following error:
    //   Exception in thread "main" java.lang.LinkageError:
    //     loader 'app' attempted duplicate class definition for zombie.ZomboidFileSystem. (zombie.ZomboidFileSystem is in unnamed module of loader 'app')
    @Advice.OnMethodExit
    public static void exit(String mod_id) {
        System.out.println("[ZB] ZomboidFileSystem.loadMod: " + mod_id);

        String modDir = ZomboidFileSystem.instance.getModDir(mod_id);
        if (modDir == null) return;
        
        var mod = ZomboidFileSystem.instance.getModInfoForDir(modDir);
        if (mod == null) return;
        
        Loader.maybe_load_java(mod.getVersionDir());
        Loader.maybe_load_java(mod.getCommonDir());
    }
}
