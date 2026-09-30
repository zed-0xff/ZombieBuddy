package me.zed_0xff.zombie_buddy.frontend;

import imgui.ImDrawData;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiMouseCursor;
import imgui.gl3.ImGuiImplGl3;
import me.zed_0xff.zombie_buddy.JarBatchApprovalProtocol;
import me.zed_0xff.zombie_buddy.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWWindowRefreshCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import zombie.ui.TextManager;
import zombie.ui.UIFont;

/**
 * Standalone ImGui approval dialog entry point.
 *
 * <p>Args: {@code <requestFile> <responseFile>}. This is the ImGui equivalent
 * of {@link SwingApprovalMain}: it owns a GLFW window and writes the same
 * {@link JarBatchApprovalProtocol} response file.
 */
public final class ImguiApprovalMain {
    private static final int WIDTH = 1024;
    private static final int HEIGHT = 768;
    private static final float FALLBACK_FONT_SIZE = 13.0f;

    private ImguiApprovalMain() {}

    public static void main(String[] args) {
        local.zbselective.i18n.UiText.initialize();
        if (args == null || args.length != 2) {
            System.err.println(local.zbselective.i18n.UiText.text("Usage: ImguiApprovalMain <requestFile> <responseFile>", "用法: ImguiApprovalMain <请求文件> <响应文件>"));
            System.exit(2);
        }

        Path req = Paths.get(args[0]);
        Path resp = Paths.get(args[1]);

        try {
            List<JarBatchApprovalProtocol.Entry> entries = JarBatchApprovalProtocol.readRequest(req);
            if (entries.isEmpty()) {
                JarBatchApprovalProtocol.writeResponse(resp, new ArrayList<>());
                System.exit(0);
                return;
            }

            List<JarBatchApprovalProtocol.Entry> out = runDialog(entries);
            if (out == null) {
                System.exit(2);
                return;
            }
            JarBatchApprovalProtocol.writeResponse(resp, out);
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static List<JarBatchApprovalProtocol.Entry> runDialog(List<JarBatchApprovalProtocol.Entry> entries) {
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException(local.zbselective.i18n.UiText.text("GLFW initialization failed", "GLFW 初始化失败"));
        }

        long window = 0;
        ImGuiImplGl3 gl3 = null;
        GLFWScrollCallback scrollCallback = null;
        GLFWWindowRefreshCallback refreshCallback = null;
        StandaloneIo standaloneIo = null;
        boolean imguiCreated = false;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
            if (GLFW.glfwGetPlatform() == 393218) {
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 2);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
            }

            window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, local.zbselective.i18n.UiText.text("ZombieBuddy Java Mod Approval", "ZombieBuddy Java 模组审批"), 0, 0);
            if (window == 0) {
                throw new IllegalStateException(local.zbselective.i18n.UiText.text("Could not create GLFW window", "无法创建 GLFW 窗口"));
            }

            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            GLFW.glfwSwapInterval(1);
            GLFW.glfwShowWindow(window);

            ImGui.createContext();
            imguiCreated = true;
            ImGuiIO io = ImGui.getIO();
            io.addConfigFlags(64);
            io.setIniFilename(null);
            configureImguiStyle();

            gl3 = new ImGuiImplGl3();
            gl3.init(GLFW.glfwGetPlatform() == 393218 ? "#version 120" : null);
            gl3.updateFontsTexture();

            AtomicReference<List<JarBatchApprovalProtocol.Entry>> result = new AtomicReference<>();
            ImguiApprovalDialog dialog = new ImguiApprovalDialog(entries, result);
            standaloneIo = new StandaloneIo(window);
            standaloneIo.initCursors();
            StandaloneIo callbackIo = standaloneIo;
            scrollCallback = GLFWScrollCallback.create(
                    (w, xOffset, yOffset) -> callbackIo.addScroll(xOffset, yOffset));
            GLFW.glfwSetScrollCallback(window, scrollCallback);
            ImGuiImplGl3 renderer = gl3;
            refreshCallback = GLFWWindowRefreshCallback.create(w -> {
                callbackIo.update();
                renderFrame(renderer, callbackIo, dialog);
                GLFW.glfwSwapBuffers(w);
            });
            GLFW.glfwSetWindowRefreshCallback(window, refreshCallback);

            while (result.get() == null && dialog.isOpen() && !GLFW.glfwWindowShouldClose(window)) {
                GLFW.glfwPollEvents();
                renderFrame(gl3, standaloneIo, dialog);
                GLFW.glfwSwapBuffers(window);
            }
            return result.get();
        } finally {
            if (refreshCallback != null) {
                if (window != 0) {
                    GLFW.glfwSetWindowRefreshCallback(window, null);
                }
                refreshCallback.free();
            }
            if (scrollCallback != null) {
                if (window != 0) {
                    GLFW.glfwSetScrollCallback(window, null);
                }
                scrollCallback.free();
            }
            if (window != 0) {
                GLFW.glfwSetCursor(window, 0);
            }
            if (standaloneIo != null) {
                standaloneIo.destroyCursors();
            }
            if (gl3 != null) {
                gl3.dispose();
            }
            if (imguiCreated) {
                ImGui.destroyContext();
            }
            if (window != 0) {
                GLFW.glfwDestroyWindow(window);
            }
            GLFW.glfwTerminate();
        }
    }

    private static void renderFrame(ImGuiImplGl3 gl3, StandaloneIo standaloneIo, ImguiApprovalDialog dialog) {
        standaloneIo.update();
        ImGui.newFrame();
        dialog.draw();
        ImGui.render();

        ImDrawData drawData = ImGui.getDrawData();
        if (drawData != null) {
            GL11.glViewport(0, 0, standaloneIo.fbWidth(), standaloneIo.fbHeight());
            GL11.glClearColor(0.02f, 0.02f, 0.02f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            try {
                gl3.renderDrawData(drawData);
            } finally {
                ImGui.freeDrawData(drawData);
            }
        }
        standaloneIo.updateCursor();
    }

    private static void configureImguiStyle() {
        float fontSize = fontSize();

        ImFontConfig fontConfig = new ImFontConfig();
        fontConfig.setSizePixels(fontSize);
        try {
            ImGui.getIO().getFonts().addFontDefault(fontConfig);
                local.zbselective.i18n.CnFonts.install(fontConfig);
        } finally {
            fontConfig.destroy();
        }
        ImGui.styleColorsDark();
        ImGui.getStyle().setFramePadding(ImGui.getStyle().getFramePaddingX(), 3.0f);
    }

    private static float fontSize() {
        try {
            TextManager textManager = TextManager.instance;
            if (textManager != null) {
                int fontHeight = textManager.getFontHeight(UIFont.Small);
                if (fontHeight > 0) return ImguiModApprovalFrontend.snapFontSize(fontHeight);
            }
        } catch (RuntimeException ignored) {
            // Standalone approval dialog can run before PZ's font manager is initialized.
        }
        return FALLBACK_FONT_SIZE;
    }

    private static final class StandaloneIo {
        private final long window;
        private final int[] winWidth = new int[1];
        private final int[] winHeight = new int[1];
        private final int[] fbWidth = new int[1];
        private final int[] fbHeight = new int[1];
        private final double[] mouseX = new double[1];
        private final double[] mouseY = new double[1];
        private float scrollX;
        private float scrollY;
        private double lastTime;
        private long arrowCursor;
        private long handCursor;
        private int currentCursor = Integer.MIN_VALUE;

        StandaloneIo(long window) {
            this.window = window;
        }

        void initCursors() {
            arrowCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_ARROW_CURSOR);
            handCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
        }

        void addScroll(double xOffset, double yOffset) {
            scrollX += (float) xOffset;
            scrollY += (float) yOffset;
        }

        void update() {
            ImGuiIO io = ImGui.getIO();
            GLFW.glfwGetWindowSize(window, winWidth, winHeight);
            GLFW.glfwGetFramebufferSize(window, fbWidth, fbHeight);
            io.setDisplaySize(winWidth[0], winHeight[0]);
            if (winWidth[0] > 0 && winHeight[0] > 0) {
                io.setDisplayFramebufferScale(
                        (float) fbWidth[0] / (float) winWidth[0],
                        (float) fbHeight[0] / (float) winHeight[0]);
            }

            double now = GLFW.glfwGetTime();
            io.setDeltaTime(lastTime > 0.0 ? (float) (now - lastTime) : 1.0f / 60.0f);
            lastTime = now;

            GLFW.glfwGetCursorPos(window, mouseX, mouseY);
            io.setMousePos((float) mouseX[0], (float) mouseY[0]);
            for (int i = 0; i < 5; i++) {
                io.setMouseDown(i, GLFW.glfwGetMouseButton(window, i) != 0);
            }
            io.setMouseWheelH(scrollX);
            io.setMouseWheel(scrollY);
            scrollX = 0.0f;
            scrollY = 0.0f;
        }

        void updateCursor() {
            int imguiCursor = ImGui.getMouseCursor();
            if (imguiCursor == currentCursor) {
                return;
            }
            currentCursor = imguiCursor;
            GLFW.glfwSetCursor(window, imguiCursor == ImGuiMouseCursor.Hand ? handCursor : arrowCursor);
        }

        void destroyCursors() {
            if (arrowCursor != 0) {
                GLFW.glfwDestroyCursor(arrowCursor);
                arrowCursor = 0;
            }
            if (handCursor != 0) {
                GLFW.glfwDestroyCursor(handCursor);
                handCursor = 0;
            }
        }

        int fbWidth() {
            return Math.max(1, fbWidth[0]);
        }

        int fbHeight() {
            return Math.max(1, fbHeight[0]);
        }
    }
}
