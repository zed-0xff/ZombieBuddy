package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.glfw.GLFW;

public class Patch_MacOSRetina {
    private static Boolean cachedPatchNeeded = null;
    
    // Reusable arrays for GLFW calls (avoid allocations)
    private static final int[] fbWidth = new int[1];
    private static final int[] fbHeight = new int[1];

    public static boolean isPatchNeeded() {
        if (cachedPatchNeeded == null) {
            // Only apply on macOS
            cachedPatchNeeded = System.getProperty("os.name").contains("OS X");
        }
        return cachedPatchNeeded;
    }
    
    /**
     * Get current framebuffer width from GLFW.
     * Always fetches the current value to handle window resizes.
     */
    public static int getFramebufferWidth() {
        if (org.lwjglx.opengl.Display.isCreated()) {
            long window = org.lwjglx.opengl.Display.getWindow();
            GLFW.glfwGetFramebufferSize(window, fbWidth, fbHeight);
            return fbWidth[0];
        }
        return 0;
    }
    
    /**
     * Get current framebuffer height from GLFW.
     * Always fetches the current value to handle window resizes.
     */
    public static int getFramebufferHeight() {
        if (org.lwjglx.opengl.Display.isCreated()) {
            long window = org.lwjglx.opengl.Display.getWindow();
            GLFW.glfwGetFramebufferSize(window, fbWidth, fbHeight);
            return fbHeight[0];
        }
        return 0;
    }

    // Patch nglfwCreateWindow to set retina hint right before window creation
    // This is more reliable than patching glfwWindowHint because it happens after all hint-setting code
    @Patch(className = "org.lwjgl.glfw.GLFW", methodName = "nglfwCreateWindow")
    public static class Patch_nglfwCreateWindow {
        @Patch.OnEnter
        public static void enter(int width, int height, long title, long monitor, long share) {
            if (Patch_MacOSRetina.isPatchNeeded() && monitor == 0) { // monitor == 0 means windowed mode
                // Set retina framebuffer hint right before window creation
                GLFW.glfwWindowHint(GLFW.GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW.GLFW_TRUE);
            }
        }
    }

    // Patch Display.getWidth() to return framebuffer width on macOS retina displays
    @Patch(className = "org.lwjglx.opengl.Display", methodName = "getWidth")
    public static class Patch_DisplayGetWidth {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int originalWidth) {
            if (Patch_MacOSRetina.isPatchNeeded() && org.lwjglx.opengl.Display.isCreated()) {
                int fbWidth = Patch_MacOSRetina.getFramebufferWidth();
                if (fbWidth > 0) {
                    originalWidth = fbWidth;
                }
            }
        }
    }

    // Patch Display.getHeight() to return framebuffer height on macOS retina displays
    // This ensures setScreenSize() gets the framebuffer size instead of window size
    @Patch(className = "org.lwjglx.opengl.Display", methodName = "getHeight")
    public static class Patch_DisplayGetHeight {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int originalHeight) {
            if (Patch_MacOSRetina.isPatchNeeded() && org.lwjglx.opengl.Display.isCreated()) {
                int fbHeight = Patch_MacOSRetina.getFramebufferHeight();
                if (fbHeight > 0) {
                    originalHeight = fbHeight;
                }
            }
        }
    }

    // Patch Display.getDesktopDisplayMode() to return framebuffer size on macOS retina displays
    // This fixes the "Desktop resolution" log and ensures Core.init() gets framebuffer size
    // The desktopDisplayMode is initialized from GLFW.glfwGetVideoMode() in the static initializer,
    // but that happens before the window exists, so we patch getDesktopDisplayMode() as a fallback
    @Patch(className = "org.lwjglx.opengl.Display", methodName = "getDesktopDisplayMode")
    public static class Patch_DisplayGetDesktopDisplayMode {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) org.lwjglx.opengl.DisplayMode result) {
            if (Patch_MacOSRetina.isPatchNeeded() && org.lwjglx.opengl.Display.isCreated()) {
                int fbWidth = Patch_MacOSRetina.getFramebufferWidth();
                int fbHeight = Patch_MacOSRetina.getFramebufferHeight();
                if (fbWidth > 0 && fbHeight > 0) {
                    result = new org.lwjglx.opengl.DisplayMode(fbWidth, fbHeight);
                }
            }
        }
    }

    // Patch Mouse.addMoveEvent() to scale mouse coordinates for Retina displays
    // Mouse coordinates from GLFW are in window space, but the game expects framebuffer space
    // On Retina displays, we need to scale by 2x (content scale factor)
    @Patch(className = "org.lwjglx.input.Mouse", methodName = "addMoveEvent")
    public static class Patch_MouseAddMoveEvent {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(value = 0, readOnly = false) double mouseX, 
                                 @Patch.Argument(value = 1, readOnly = false) double mouseY) {
            if (Patch_MacOSRetina.isPatchNeeded() && org.lwjglx.opengl.Display.isCreated()) {
                long window = org.lwjglx.opengl.Display.getWindow();
                float[] xscale = new float[1];
                float[] yscale = new float[1];
                GLFW.glfwGetWindowContentScale(window, xscale, yscale);
                // Scale mouse coordinates from window space to framebuffer space
                mouseX *= xscale[0];
                mouseY *= yscale[0];
            }
        }
    }
}

