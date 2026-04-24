package me.zed_0xff.zombie_buddy;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallbackI;

/**
 * Ctrl+T dumps all Java thread stacks.
 * Uses GLFW key callback - works even if game loop hangs.
 */
public class JavaStateDumper {
    private static GLFWKeyCallbackI originalKeyCallback = null;
    private static boolean installed = false;

    static {
        Callbacks.onDisplayCreate.register(JavaStateDumper::installKeyCallback);
    }

    public static void installKeyCallback() {
        if (installed) return;
        installed = true;
        
        try {
            if (!org.lwjglx.opengl.Display.isCreated()) return;
            long window = org.lwjglx.opengl.Display.getWindow();
            originalKeyCallback = GLFW.glfwSetKeyCallback(window, JavaStateDumper::handleKey);
            Logger.info("Installed GLFW key callback for Ctrl+T thread dump");
        } catch (Throwable e) {
            Logger.warn("Failed to install GLFW key callback: " + e);
        }
    }
    
    private static void handleKey(long window, int key, int scancode, int action, int mods) {
        if (key == GLFW.GLFW_KEY_T && action == GLFW.GLFW_PRESS && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
            dumpThreadStacks();
        }
        if (originalKeyCallback != null) {
            originalKeyCallback.invoke(window, key, scancode, action, mods);
        }
    }
    
    public static void dumpThreadStacks() {
        Logger.info("=== Thread Dump ===");
        for (var entry : Thread.getAllStackTraces().entrySet()) {
            Thread t = entry.getKey();
            StackTraceElement[] stack = entry.getValue();
            Logger.info(String.format("Thread: %s (id=%d, state=%s)", t.getName(), t.getId(), t.getState()));
            for (StackTraceElement el : stack) {
                Logger.info("    at " + el);
            }
        }
        Logger.info("=== End Thread Dump ===");
    }
}
