package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.LogOverlay;
import me.zed_0xff.zombie_buddy.Patch;

/**
 * Hooks into game logging and rendering to display log overlay.
 * 
 * XXX any of these function should NOT write to console (or Logger)
 */
public class Patch_log_overlay {
    @Patch(className = "zombie.core.Core", methodName = "EndFrameUI")
    public static class Patch_UIManager_EndFrameUI {
        @Patch.OnEnter
        public static void exit() {
            LogOverlay.draw();
        }
    }
    
    @Patch(className = "zombie.ui.UIManager", methodName = "update")
    public static class Patch_UIManager_update {
        @Patch.OnExit
        public static void exit() {
            LogOverlay.checkOsdToggle();
        }
    }
    
    @Patch(className = "zombie.core.ProxyPrintStream", methodName = "println")
    public static class Patch_println {
        @Patch.OnEnter
        public static void enter(Object obj) {
            LogOverlay.addLine(obj.toString());
        }
    }

    @Patch(className = "zombie.core.ProxyPrintStream", methodName = "print")
    public static class Patch_print {
        @Patch.OnEnter
        public static void enter(String str) {
            LogOverlay.addLine(str);
        }
    }

    @Patch(className = "zombie.core.ProxyPrintStream", methodName = "write")
    public static class Patch_write {
        @Patch.OnEnter
        public static void enter(byte [] buf, int off, int len) {
            if (buf == null || off < 0 || len < 0) return;
            LogOverlay.addLine(new String(buf, off, len));
        }
    }
}
