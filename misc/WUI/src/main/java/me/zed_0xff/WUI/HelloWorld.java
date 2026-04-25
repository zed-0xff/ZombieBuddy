package me.zed_0xff.WUI;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

public final class HelloWorld {
    static final int WIN_W = 640;
    static final int WIN_H = 480;

    static Font font;

    private static final int[] SCALES = {1, 2, 3};
    private static volatile int scaleIdx = 1;

    /** UI scale: ortho and mouse are in logical px; one logical px maps to this many framebuffer px. */
    static int uiScale() {
        return SCALES[scaleIdx % SCALES.length];
    }

    static void renderFrame(long glWindow, int fontTex, Window window) {
        applyFrameProjection(glWindow);

        GL11.glClearColor(Color.GRAY.getRf(), Color.GRAY.getGf(), Color.GRAY.getBf(), 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fontTex);

        window.render(fontTex);
    }

    public static void main(String[] args) throws IOException {
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");

        long glWindow = GLFW.glfwCreateWindow(WIN_W, WIN_H, "Desktop", 0, 0);
        if (glWindow == 0) throw new RuntimeException("glfwCreateWindow failed");

        GLFW.glfwMakeContextCurrent(glWindow);
        GLFW.glfwSwapInterval(1);
        GL.createCapabilities();
        initGlState();

        File assets = new File(".");
        File cursorsJson = new File(assets, "cursors.json");
        CursorMgr.create(cursorsJson);

        Window window = new Window(80, 48, 420, 260, "Window")
                .addControl(w -> new Button(     w, 10, 10, 100, 20, "OK"))
                .addControl(w -> new CheckBox(   w, 10, 40, 100, 20, "test"))
                .addControl(w -> new RadioButton(w, 10, 70, 100, 20, "R1", "A"))
                .addControl(w -> new RadioButton(w, 50, 70, 100, 20, "R2", "A"))
                .addControl(w -> new RadioButton(w, 10, 90, 100, 20, "R3", "B"))
                .addControl(w -> new RadioButton(w, 50, 90, 100, 20, "R4", "B"));

        GLFW.glfwSetMouseButtonCallback(glWindow, (win, button, action, mods) -> {
            double[] cx = new double[1];
            double[] cy = new double[1];
            GLFW.glfwGetCursorPos(win, cx, cy);
            double[] f = cursorToFramebuffer(win, cx[0], cy[0]);
            int scale = uiScale();
            int lx = (int)(f[0] / scale);
            int ly = (int)(f[1] / scale);
            /*boolean startedDrag =*/ window.handleMouseButton(win, button, action, lx, ly);
            // if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
            //     && action == GLFW.GLFW_PRESS
            //     && !startedDrag
            //     && !window.contains(lx, ly)) {
            //     scaleIdx = (scaleIdx + 1) % SCALES.length;
            //     int ui = uiScale();
            //     GLFW.glfwSetWindowTitle(win, "Desktop — " + ui + "x");
            // }
        });

        GLFW.glfwSetCursorPosCallback(glWindow, (win, xpos, ypos) -> {
            double[] f = cursorToFramebuffer(win, xpos, ypos);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer fbw = stack.mallocInt(1);
                IntBuffer fbh = stack.mallocInt(1);
                GLFW.glfwGetFramebufferSize(win, fbw, fbh);
                int scale = uiScale();
                int vw = fbw.get(0) / scale;
                int vh = fbh.get(0) / scale;
                window.handleCursorPos(win, (int)(f[0] / scale), (int)(f[1] / scale), vw, vh);
            }
        });

        GLFW.glfwSetFramebufferSizeCallback(glWindow, (win, w, h) -> applyFrameProjection(win));

        int fontTex = Element.font.fontTex;
        GLFW.glfwSetWindowTitle(glWindow, "Desktop — " + uiScale() + "x");

        GLFW.glfwSetWindowRefreshCallback(glWindow, win -> {
            renderFrame(win, fontTex, window);
            GLFW.glfwSwapBuffers(win);
        });

        while (!GLFW.glfwWindowShouldClose(glWindow)) {
            renderFrame(glWindow, fontTex, window);
            GLFW.glfwSwapBuffers(glWindow);
            GLFW.glfwPollEvents();
        }

        GL11.glDeleteTextures(fontTex);
        GLFW.glfwSetCursor(glWindow, 0);
        CursorMgr.destroy();
        GLFW.glfwDestroyWindow(glWindow);
        GLFW.glfwTerminate();
    }

    /** One-time GL state; projection matches framebuffer each frame (HiDPI). */
    static void initGlState() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Viewport + ortho in framebuffer pixels (fixes 0.5x look on Retina until resize). */
    static void applyFrameProjection(long glWindow) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer fbw = stack.mallocInt(1);
            IntBuffer fbh = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(glWindow, fbw, fbh);
            int w = Math.max(1, fbw.get(0));
            int h = Math.max(1, fbh.get(0));
            int scale = uiScale();
            GL11.glViewport(0, 0, w, h);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, w / scale, h / scale, 0, -1, 1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
        }
    }

    /** Map glfwGetCursorPos (glWindow coords) to framebuffer pixel coords used by glOrtho. */
    static double[] cursorToFramebuffer(long glWindow, double cxWin, double cyWin) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer winW = stack.mallocInt(1);
            IntBuffer winH = stack.mallocInt(1);
            IntBuffer fbw  = stack.mallocInt(1);
            IntBuffer fbh  = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(glWindow, winW, winH);
            GLFW.glfwGetFramebufferSize(glWindow, fbw, fbh);
            int ww = winW.get(0);
            int wh = winH.get(0);
            int fw = fbw.get(0);
            int fh = fbh.get(0);
            if (ww <= 0 || wh <= 0) {
                return new double[] {cxWin, cyWin};
            }
            return new double[] {cxWin * fw / ww, cyWin * fh / wh};
        }
    }
}
