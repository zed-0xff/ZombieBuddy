package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.glfw.GLFW;

public class Patch_MacOSRetina {
    public static final boolean IS_MACOS = System.getProperty("os.name").contains("OS X");

    // Patch nglfwCreateWindow to set retina hint right before window creation
    // This is more reliable than patching glfwWindowHint because it happens after all hint-setting code
    @Patch(className = "org.lwjgl.glfw.GLFW", methodName = "nglfwCreateWindow")
    public static class Patch_nglfwCreateWindow {
        @Patch.OnEnter
        public static void enter(int width, int height, long title, long monitor, long share) {
            if (IS_MACOS && monitor == 0) { // monitor == 0 means windowed mode
                // Set retina framebuffer hint right before window creation
                GLFW.glfwWindowHint(GLFW.GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW.GLFW_TRUE);
            }
        }
    }

    // Patch Display.getWidth() to return framebuffer width on macOS retina displays
    // This ensures setScreenSize() gets the framebuffer size instead of window size
    @Patch(className = "org.lwjglx.opengl.Display", methodName = "getWidth")
    public static class Patch_DisplayGetWidth {
        @Patch.OnExit
        public static int exit(@Patch.Return(readOnly = false) int originalWidth) {
            if (IS_MACOS && org.lwjglx.opengl.Display.isCreated()) {
                int framebufferWidth = org.lwjglx.opengl.Display.getFramebufferWidth();
                if (framebufferWidth > 0 && framebufferWidth != originalWidth) {
                    return framebufferWidth;
                }
            }
            System.out.println("[ZBMacOSRetinaFix] Display.getWidth() - returning original width: " + originalWidth);
            return originalWidth;
        }
    }

    // Patch Display.getHeight() to return framebuffer height on macOS retina displays
    // This ensures setScreenSize() gets the framebuffer size instead of window size
    @Patch(className = "org.lwjglx.opengl.Display", methodName = "getHeight")
    public static class Patch_DisplayGetHeight {
        @Patch.OnExit
        public static int exit(@Patch.Return(readOnly = false) int originalHeight) {
            if (IS_MACOS && org.lwjglx.opengl.Display.isCreated()) {
                int framebufferHeight = org.lwjglx.opengl.Display.getFramebufferHeight();
                if (framebufferHeight > 0 && framebufferHeight != originalHeight) {
                    return framebufferHeight;
                }
            }
            return originalHeight;
        }
    }

    // Patch GLFWVidMode.width() to return framebuffer width on macOS retina displays
    // This ensures desktopDisplayMode gets framebuffer dimensions when created
    // If Display is not created yet (static initializer), return 2x the logical resolution
    @Patch(className = "org.lwjgl.glfw.GLFWVidMode", methodName = "width")
    public static class Patch_GLFWVidModeWidth {
        @Patch.OnExit
        public static int exit(@Patch.Return(readOnly = false) int originalWidth) {
            if (IS_MACOS) {
                if (org.lwjglx.opengl.Display.isCreated()) {
                    int framebufferWidth = org.lwjglx.opengl.Display.getFramebufferWidth();
                    if (framebufferWidth > 0 && framebufferWidth != originalWidth) {
                        System.out.println("[ZBMacOSRetinaFix] GLFWVidMode.width() - Returning framebuffer width: " + framebufferWidth);
                        return framebufferWidth;
                    }
                } else {
                    System.out.println("[ZBMacOSRetinaFix] GLFWVidMode.width() - Display not created yet (static initializer), returning 2x resolution: " + originalWidth * 2);
                    // Display not created yet (static initializer) - return 2x resolution for retina
                    return originalWidth * 2;
                }
            }
            System.out.println("[ZBMacOSRetinaFix] GLFWVidMode.width() - Returning original width: " + originalWidth);
            return originalWidth;
        }
    }

    @Patch(className = "org.lwjgl.glfw.GLFWVidMode", methodName = "getWidth")
    public static class Patch_GLFWVidModeGetWidth {
        @Patch.OnExit
        public static int exit(@Patch.Return(readOnly = false) int originalWidth) {
            if (IS_MACOS) {
                if (org.lwjglx.opengl.Display.isCreated()) {
                    int framebufferWidth = org.lwjglx.opengl.Display.getFramebufferWidth();
                    if (framebufferWidth > 0 && framebufferWidth != originalWidth) {
                        System.out.println("[ZBMacOSRetinaFix] GLFWVidMode.getWidth() - Returning framebuffer width: " + framebufferWidth);
                        return framebufferWidth;
                    }
                } else {
                    System.out.println("[ZBMacOSRetinaFix] GLFWVidMode.getWidth() - Display not created yet (static initializer), returning 2x resolution: " + originalWidth * 2);
                    // Display not created yet (static initializer) - return 2x resolution for retina
                    return originalWidth * 2;
                }
            }
            System.out.println("[ZBMacOSRetinaFix] GLFWVidMode.getWidth() - Returning original width: " + originalWidth);
            return originalWidth;
        }
    }

    // Patch GLFWVidMode.height() to return framebuffer height on macOS retina displays
    // This ensures desktopDisplayMode gets framebuffer dimensions when created
    // If Display is not created yet (static initializer), return 2x the logical resolution
    @Patch(className = "org.lwjgl.glfw.GLFWVidMode", methodName = "height")
    public static class Patch_GLFWVidModeHeight {
        @Patch.OnExit
        public static int exit(@Patch.Return(readOnly = false) int originalHeight) {
            if (IS_MACOS) {
                if (org.lwjglx.opengl.Display.isCreated()) {
                    int framebufferHeight = org.lwjglx.opengl.Display.getFramebufferHeight();
                    if (framebufferHeight > 0 && framebufferHeight != originalHeight) {
                        return framebufferHeight;
                    }
                } else {
                    // Display not created yet (static initializer) - return 2x resolution for retina
                    return originalHeight * 2;
                }
            }
            return originalHeight;
        }
    }

    // Patch Display.getDesktopDisplayMode() to return framebuffer size on macOS retina displays
    // This fixes the "Desktop resolution" log and ensures Core.init() gets framebuffer size
    // The desktopDisplayMode is initialized from GLFW.glfwGetVideoMode() in the static initializer,
    // but that happens before the window exists, so we patch getDesktopDisplayMode() as a fallback
    @Patch(className = "org.lwjglx.opengl.Display", methodName = "getDesktopDisplayMode")
    public static class Patch_DisplayGetDesktopDisplayMode {
        @Patch.OnExit
        public static org.lwjglx.opengl.DisplayMode exit(@Patch.Return(readOnly = false) org.lwjglx.opengl.DisplayMode originalMode) {
            if (IS_MACOS && org.lwjglx.opengl.Display.isCreated()) {
                int framebufferWidth = org.lwjglx.opengl.Display.getFramebufferWidth();
                int framebufferHeight = org.lwjglx.opengl.Display.getFramebufferHeight();
                int windowWidth = org.lwjglx.opengl.Display.getWidth();
                int windowHeight = org.lwjglx.opengl.Display.getHeight();
                
                if (framebufferWidth > 0 && framebufferHeight > 0 && 
                    (framebufferWidth != windowWidth || framebufferHeight != windowHeight)) {
                    // Return a new DisplayMode with framebuffer dimensions
                    // Note: Using public constructor which only takes width and height
                    System.out.println("[ZBMacOSRetinaFix] Returning new mode: " + framebufferWidth + "x" + framebufferHeight);
                    return new org.lwjglx.opengl.DisplayMode(framebufferWidth, framebufferHeight);
                }
            }
            System.out.println("[ZBMacOSRetinaFix] Returning original mode: " + originalMode.getWidth() + "x" + originalMode.getHeight());
            return originalMode;
        }
    }
}

