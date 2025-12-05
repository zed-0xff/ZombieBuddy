package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "zombie.core.Core", methodName = "getSVNRevisionString")
class Patch_getSVNRevisionString {
    @Patch.OnExit
    static void exit(@Patch.Return(readOnly = false) String result) {
        result = result + " [ZB]"; // modify return value
    }
}
