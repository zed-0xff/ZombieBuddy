package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

@Patch(className = "zombie.core.Core", methodName = "getSVNRevisionString")
class Patch_getSVNRevisionString {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) String result) {
        result = result + " [ZB]"; // modify return value
    }
}
