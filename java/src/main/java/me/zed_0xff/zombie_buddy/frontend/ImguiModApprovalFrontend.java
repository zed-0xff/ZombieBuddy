package me.zed_0xff.zombie_buddy.frontend;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import me.zed_0xff.zombie_buddy.JarBatchApprovalProtocol;
import me.zed_0xff.zombie_buddy.Loader;
import me.zed_0xff.zombie_buddy.Logger;

import imgui.ImDrawData;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiMouseCursor;
import imgui.gl3.ImGuiImplGl3;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjglx.opengl.Display;
import zombie.core.opengl.RenderThread;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

/**
 * Proof-of-concept approval dialog using Project Zomboid's bundled ImGui renderer.
 *
 * <p>This owns its ImGui context so it can run when {@code Core.debug} and
 * {@code Core.imGui} are disabled. It still reuses PZ's bundled imgui-java GL3 backend.
 */
public final class ImguiModApprovalFrontend implements ModApprovalFrontend {
    /**
     * Snaps an arbitrary pixel size to the nearest integer multiple of 13 —
     * ProggyClean's native raster size — so the ImGui font atlas is never
     * scaled by a fractional ratio (which would produce blurry/uneven glyphs).
     */
    static float snapFontSize(float raw) {
        int multiple = Math.max(1, Math.round(raw / 13.0f));
        return multiple * 13.0f;
    }

    @Override
    public List<JarBatchApprovalProtocol.Entry> approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending) {
        if (pending.isEmpty()) {
            return pending;
        }

        AtomicReference<List<JarBatchApprovalProtocol.Entry>> result = new AtomicReference<>();
        ImguiApprovalDialog dialog = new ImguiApprovalDialog(pending, result);

        OwnedImguiContext imgui = createImguiContext(Display.getWindow());
        try {
            while (result.get() == null && dialog.isOpen()) {
                pumpImguiLoadingFrame(imgui, dialog);
                try {
                    Thread.sleep(16);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.compareAndSet(null, ImguiApprovalDialog.denyAll(pending));
                    break;
                }
            }
        } finally {
            closeImguiContext(imgui);
        }

        List<JarBatchApprovalProtocol.Entry> lines = result.get();
        return lines != null ? lines : ImguiApprovalDialog.denyAll(pending);
    }

    private static OwnedImguiContext createImguiContext(long windowHandle) {
        AtomicReference<OwnedImguiContext> ref = new AtomicReference<>();
        RenderThread.invokeOnRenderContext(() -> ref.set(new OwnedImguiContext(windowHandle)));
        return ref.get();
    }

    private static void closeImguiContext(OwnedImguiContext imgui) {
        if (imgui != null) {
            RenderThread.invokeOnRenderContext(imgui::close);
        }
    }

    private static void pumpImguiLoadingFrame(OwnedImguiContext imgui, ImguiApprovalDialog dialog) {
        RenderThread.invokeOnRenderContext(() -> {
            Display.processMessages();
            imgui.render(dialog);
            Display.update(false);
        });
    }

    private static final class OwnedImguiContext implements AutoCloseable {
        private final long windowHandle;
        private final ImGuiImplGl3 gl3 = new ImGuiImplGl3();
        private final int[] winWidth = new int[1];
        private final int[] winHeight = new int[1];
        private final int[] fbWidth = new int[1];
        private final int[] fbHeight = new int[1];
        private final double[] mouseX = new double[1];
        private final double[] mouseY = new double[1];
        private double lastTime;
        private final long arrowCursor;
        private final long handCursor;
        private int currentCursor = Integer.MIN_VALUE;
        private final int savedCursorMode;
        private boolean closed;

        OwnedImguiContext(long windowHandle) {
            this.windowHandle = windowHandle;
            arrowCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_ARROW_CURSOR);
            handCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
            savedCursorMode = GLFW.glfwGetInputMode(windowHandle, GLFW.GLFW_CURSOR);
            if (Loader.fixApprovalDialogCursor()) {
                GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            }
            ImGui.createContext();
            ImGuiIO io = ImGui.getIO();
            io.addConfigFlags(64); // Keyboard navigation, same flag PZ enables for its own context.
            configureImguiStyle(windowHandle);
            String glslVersion = GLFW.glfwGetPlatform() == 393218 ? "#version 120" : null;
            gl3.init(glslVersion);
            gl3.updateFontsTexture();
        }

        void render(ImguiApprovalDialog dialog) {
            updateIo();
            ImGui.newFrame();
            dialog.draw();
            ImGui.render();
            ImDrawData drawData = ImGui.getDrawData();
            if (drawData != null) {
                try {
                    GL11.glClearColor(0.02f, 0.02f, 0.02f, 1.0f);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                    gl3.renderDrawData(drawData);
                } finally {
                    ImGui.freeDrawData(drawData);
                }
            }
            updateCursor();
        }

        private void updateCursor() {
            int imguiCursor = ImGui.getMouseCursor();
            if (imguiCursor == currentCursor) {
                return;
            }
            currentCursor = imguiCursor;
            GLFW.glfwSetCursor(windowHandle, imguiCursor == ImGuiMouseCursor.Hand ? handCursor : arrowCursor);
        }

        private void updateIo() {
            ImGuiIO io = ImGui.getIO();
            GLFW.glfwGetWindowSize(windowHandle, winWidth, winHeight);
            GLFW.glfwGetFramebufferSize(windowHandle, fbWidth, fbHeight);
            io.setDisplaySize(winWidth[0], winHeight[0]);
            if (winWidth[0] > 0 && winHeight[0] > 0) {
                io.setDisplayFramebufferScale(
                        (float) fbWidth[0] / (float) winWidth[0],
                        (float) fbHeight[0] / (float) winHeight[0]);
            }
            double now = GLFW.glfwGetTime();
            io.setDeltaTime(lastTime > 0.0 ? (float) (now - lastTime) : 1.0f / 60.0f);
            lastTime = now;

            GLFW.glfwGetCursorPos(windowHandle, mouseX, mouseY);
            io.setMousePos((float) mouseX[0], (float) mouseY[0]);
            for (int i = 0; i < 5; i++) {
                io.setMouseDown(i, GLFW.glfwGetMouseButton(windowHandle, i) != 0);
            }
        }

        private static void configureImguiStyle(long windowHandle) {
            float framebufferScale = framebufferScale(windowHandle);
            float vanillaFontSize = TextManager.instance.getFontHeight(UIFont.Small);
            float fontSize = snapFontSize(vanillaFontSize / framebufferScale);
            float scale = ImGui.getIO().getFontGlobalScale();
            Logger.debug("ImGui scale=" + scale
                    + ", framebufferScale=" + framebufferScale
                    + ", vanillaFontSize=" + vanillaFontSize
                    + ", imguiFontSize=" + fontSize);

            ImFontConfig fontConfig = new ImFontConfig();
            fontConfig.setSizePixels(fontSize);
            try {
                ImGui.getIO().getFonts().addFontDefault(fontConfig);
                local.zbselective.i18n.CnFonts.install(fontConfig);
            } finally {
                fontConfig.destroy();
            }
            ImGui.styleColorsDark();
            ImGui.getStyle().setFramePadding(
                    ImGui.getStyle().getFramePaddingX(),
                    3.0f / framebufferScale);
        }

        private static float framebufferScale(long windowHandle) {
            int[] winWidth = new int[1];
            int[] winHeight = new int[1];
            int[] fbWidth = new int[1];
            int[] fbHeight = new int[1];
            GLFW.glfwGetWindowSize(windowHandle, winWidth, winHeight);
            GLFW.glfwGetFramebufferSize(windowHandle, fbWidth, fbHeight);
            if (winWidth[0] <= 0 || winHeight[0] <= 0) {
                return 1.0f;
            }
            return Math.max(
                    1.0f,
                    Math.max(
                            (float) fbWidth[0] / (float) winWidth[0],
                            (float) fbHeight[0] / (float) winHeight[0]));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, savedCursorMode);
            GLFW.glfwSetCursor(windowHandle, 0);
            if (arrowCursor != 0) {
                GLFW.glfwDestroyCursor(arrowCursor);
            }
            if (handCursor != 0) {
                GLFW.glfwDestroyCursor(handCursor);
            }
            gl3.dispose();
            ImGui.destroyContext();
        }
    }
}
