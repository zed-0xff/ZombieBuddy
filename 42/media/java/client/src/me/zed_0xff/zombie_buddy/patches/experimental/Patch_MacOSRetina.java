package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.glfw.GLFW;

public class Patch_MacOSRetina {
    private static Boolean cachedPatchNeeded = null;

    public static boolean isPatchNeeded() {
        if (cachedPatchNeeded == null) {
            if (System.getProperty("os.name").contains("OS X")) {
                boolean is_fullscreen = false;

                // read "~/Zomboid/options.ini" to check if "fullScreen=false" is set
                String userHome = System.getProperty("user.home");
                java.io.File optionsFile = new java.io.File(userHome + "/Zomboid/options.ini");
                if (optionsFile.exists()) {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(optionsFile))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.trim().equalsIgnoreCase("fullScreen=true")) {
                                is_fullscreen = true;
                            }
                        }
                    } catch (java.io.IOException e) {
                        e.printStackTrace();
                    }
                }
                cachedPatchNeeded = !is_fullscreen;
            } else {
                cachedPatchNeeded = false;
            }
        }
        return cachedPatchNeeded;
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
            if (Patch_MacOSRetina.isPatchNeeded()) {
                originalWidth = 3456;
            }
        }
    }

    // Patch Display.getHeight() to return framebuffer height on macOS retina displays
    // This ensures setScreenSize() gets the framebuffer size instead of window size
    @Patch(className = "org.lwjglx.opengl.Display", methodName = "getHeight")
    public static class Patch_DisplayGetHeight {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int originalHeight) {
            if (Patch_MacOSRetina.isPatchNeeded()) {
                originalHeight = 2234;
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
            if (Patch_MacOSRetina.isPatchNeeded()) {
                result = new org.lwjglx.opengl.DisplayMode(3456, 2234);
            }
        }
    }

    // Patch Mouse.addMoveEvent() to scale mouse coordinates for Retina displays
    // Mouse coordinates from GLFW are in window space, but the game expects framebuffer space
    // On Retina displays, we need to scale by 2x (content scale factor)
    @Patch(className = "org.lwjglx.input.Mouse", methodName = "addMoveEvent", isAdvice = false)
    public static class Patch_MouseAddMoveEvent {
        @net.bytebuddy.implementation.bind.annotation.RuntimeType
        public static void addMoveEvent(@net.bytebuddy.implementation.bind.annotation.Argument(0) double mouseX, 
                                        @net.bytebuddy.implementation.bind.annotation.Argument(1) double mouseY,
                                        @net.bytebuddy.implementation.bind.annotation.SuperMethod java.lang.reflect.Method superMethod) throws Throwable {
            if (Patch_MacOSRetina.isPatchNeeded() && org.lwjglx.opengl.Display.isCreated()) {
                long window = org.lwjglx.opengl.Display.getWindow();
                float[] xscale = new float[1];
                float[] yscale = new float[1];
                GLFW.glfwGetWindowContentScale(window, xscale, yscale);
                // Scale mouse coordinates from window space to framebuffer space
                mouseX *= xscale[0];
                mouseY *= yscale[0];
            }
            // Call original method with scaled coordinates
            superMethod.invoke(null, mouseX, mouseY);
        }
    }
}

