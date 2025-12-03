package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.implementation.bind.annotation.*;

import java.util.concurrent.Callable;

@Patch(className = "zombie.core.Core", methodName = "getSVNRevisionString")
public class Patch_getSVNRevisionString {
    public static String getSVNRevisionString(@SuperCall Callable<String> zuper) throws Exception {
        return zuper.call() + " [ZB]";
    }
}
