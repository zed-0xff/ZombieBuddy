import com.google.gson.Gson;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.io.File;

/**
 * Win3.1-style faux window: title drag, resize from edges/corners, resize cursors on hover.
 * Coordinates match {@link HelloWorld}: origin top-left, y downward (framebuffer pixels).
 */
public final class SimpleUiWindow {
    private static final float GRIP = 8f;
    private static final float EDGE = 6f;
    private static final float MIN_W = 120f;
    private static final float MIN_H = 80f;

    private static final float CLIENT_BG = 1f;
    private static final float TITLE_BAR_R = 0f;
    private static final float TITLE_BAR_G = 0f;
    private static final float TITLE_BAR_B = 0xb8 / 255f;

    private enum ResizeGrip {
        NONE, N, S, E, W, NE, NW, SE, SW
    }

    private static long curArrow;
    private static long curH;
    private static long curV;
    private static long curNwse;
    private static long curNesw;
    private static boolean cursorsCreated;

    public float x;
    public float y;
    public float width;
    public float height;
    public final String title;
    public final float titleBarHeight;
    public final float titleFontScale;

    private boolean dragging;
    private float dragGrabDx;
    private float dragGrabDy;

    private ResizeGrip activeResize = ResizeGrip.NONE;
    private float resizeSnapX;
    private float resizeSnapY;
    private float resizeSnapW;
    private float resizeSnapH;

    private WindowDecor windowDecor;

    public SimpleUiWindow(
        String title,
        float x,
        float y,
        float width,
        float height,
        float titleBarHeight,
        float titleFontScale
    ) {
        this.title = title;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.titleBarHeight = titleBarHeight;
        this.titleFontScale = titleFontScale;
    }

    public void setWindowDecor(WindowDecor decor) {
        this.windowDecor = decor;
    }

    /**
     * Loads {@code cursors.json} + image (see {@link CursorLoader}); falls back to GLFW standard cursors if missing/invalid.
     */
    public static void createResizeCursors(File cursorsJson, Gson gson) {
        if (cursorsCreated) {
            return;
        }
        cursorsCreated = true;
        long[] handles = CursorLoader.loadResizeCursors(cursorsJson, gson);
        if (handles != null) {
            curArrow = handles[0];
            curH     = handles[1];
            curV     = handles[2];
            curNwse  = handles[3];
            curNesw  = handles[4];
            // curText = handles[5]
            return;
        }
        createStandardResizeCursors();
    }

    private static void createStandardResizeCursors() {
        curArrow = GLFW.glfwCreateStandardCursor(GLFW.GLFW_ARROW_CURSOR);
        curH     = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HRESIZE_CURSOR);
        curV     = GLFW.glfwCreateStandardCursor(GLFW.GLFW_VRESIZE_CURSOR);
        curNwse  = GLFW.glfwCreateStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR);
        curNesw  = GLFW.glfwCreateStandardCursor(GLFW.GLFW_RESIZE_NESW_CURSOR);

        if (curNwse == 0) {
            curNwse = GLFW.glfwCreateStandardCursor(GLFW.GLFW_CROSSHAIR_CURSOR);
        }
        if (curNwse == 0) {
            curNwse = curH;
        }
        if (curNesw == 0) {
            curNesw = GLFW.glfwCreateStandardCursor(GLFW.GLFW_CROSSHAIR_CURSOR);
        }
        if (curNesw == 0) {
            curNesw = curV;
        }
    }

    public static void destroyResizeCursors() {
        if (!cursorsCreated) {
            return;
        }
        cursorsCreated = false;
        long[] handles = {curH, curV, curNwse, curNesw, curArrow};
        for (int i = 0; i < handles.length; i++) {
            long c = handles[i];
            if (c == 0) {
                continue;
            }
            boolean duplicate = false;
            for (int j = 0; j < i; j++) {
                if (handles[j] == c) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                GLFW.glfwDestroyCursor(c);
            }
        }
        curArrow = curH = curV = curNwse = curNesw = 0;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    public boolean containsTitleBar(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + titleBarHeight;
    }

    /** Title strip excluding edge grips so resize takes priority on corners/top border. */
    private boolean titleDragZone(double mx, double my) {
        if (!containsTitleBar(mx, my)) {
            return false;
        }
        if (mx < x + GRIP || mx >= x + width - GRIP) {
            return false;
        }
        return my >= y + EDGE;
    }

    private ResizeGrip hitTestResize(double mx, double my) {
        float x0 = x;
        float y0 = y;
        float x1 = x + width;
        float y1 = y + height;

        boolean onN = my >= y0 && my < y0 + EDGE;
        boolean onS = my > y1 - EDGE && my <= y1;
        boolean onW = mx >= x0 && mx < x0 + EDGE;
        boolean onE = mx > x1 - EDGE && mx <= x1;

        if (onN && mx < x0 + GRIP) {
            return ResizeGrip.NW;
        }
        if (onN && mx > x1 - GRIP) {
            return ResizeGrip.NE;
        }
        if (onS && mx < x0 + GRIP) {
            return ResizeGrip.SW;
        }
        if (onS && mx > x1 - GRIP) {
            return ResizeGrip.SE;
        }
        if (onN) {
            return ResizeGrip.N;
        }
        if (onS) {
            return ResizeGrip.S;
        }
        if (onW) {
            return ResizeGrip.W;
        }
        if (onE) {
            return ResizeGrip.E;
        }
        return ResizeGrip.NONE;
    }

    private static long cursorForGrip(ResizeGrip g) {
        switch (g) {
            case N:
            case S:
                return curV;
            case E:
            case W:
                return curH;
            case NW:
            case SE:
                return curNwse;
            case NE:
            case SW:
                return curNesw;
            default:
                return curArrow;
        }
    }

    private void setCursor(long window, long cursor) {
        GLFW.glfwSetCursor(window, cursor == 0 ? curArrow : cursor);
    }

    private void updateHoverCursor(long window, double mx, double my) {
        ResizeGrip g = hitTestResize(mx, my);
        if (g != ResizeGrip.NONE) {
            setCursor(window, cursorForGrip(g));
        } else {
            GLFW.glfwSetCursor(window, 0);
        }
    }

    private void applyResize(long window, double mx, double my, float viewW, float viewH) {
        float sx = resizeSnapX;
        float sy = resizeSnapY;
        float sw = resizeSnapW;
        float sh = resizeSnapH;
        float bot = sy + sh;
        float right = sx + sw;

        switch (activeResize) {
            case SE:
                x = sx;
                y = sy;
                width = Math.max(MIN_W, (float) mx - sx);
                height = Math.max(MIN_H, (float) my - sy);
                break;
            case NE:
                x = sx;
                y = (float) my;
                width = Math.max(MIN_W, (float) mx - sx);
                height = Math.max(MIN_H, bot - y);
                break;
            case NW:
                x = (float) mx;
                y = (float) my;
                width = Math.max(MIN_W, right - x);
                height = Math.max(MIN_H, bot - y);
                break;
            case SW:
                x = (float) mx;
                y = sy;
                width = Math.max(MIN_W, right - x);
                height = Math.max(MIN_H, (float) my - sy);
                break;
            case E:
                x = sx;
                width = Math.max(MIN_W, (float) mx - sx);
                break;
            case W:
                x = (float) mx;
                width = Math.max(MIN_W, right - (float) mx);
                break;
            case S:
                y = sy;
                height = Math.max(MIN_H, (float) my - sy);
                break;
            case N:
                y = (float) my;
                height = Math.max(MIN_H, bot - (float) my);
                break;
            default:
                break;
        }
        clampResizeInView(viewW, viewH);
        setCursor(window, cursorForGrip(activeResize));
    }

    /**
     * @return true if this press starts title drag or resize (caller may skip other click actions).
     * @param mx,my logical coords (framebuffer px / {@link HelloWorld#uiScale})
     */
    public boolean handleMouseButton(long window, int button, int action, double mx, double my) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (action == GLFW.GLFW_PRESS) {
            ResizeGrip g = hitTestResize(mx, my);
            if (g != ResizeGrip.NONE) {
                activeResize = g;
                resizeSnapX = x;
                resizeSnapY = y;
                resizeSnapW = width;
                resizeSnapH = height;
                return true;
            }
            if (titleDragZone(mx, my)) {
                dragging = true;
                dragGrabDx = (float) (mx - x);
                dragGrabDy = (float) (my - y);
                return true;
            }
            return false;
        }
        if (action == GLFW.GLFW_RELEASE) {
            dragging = false;
            activeResize = ResizeGrip.NONE;
            updateHoverCursor(window, mx, my);
        }
        return false;
    }

    /** @param viewW,viewH logical viewport size (matches ortho after UI scale). */
    public void handleCursorPos(long window, double mx, double my, float viewW, float viewH) {
        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (activeResize != ResizeGrip.NONE && leftDown) {
            applyResize(window, mx, my, viewW, viewH);
            return;
        }
        if (dragging && leftDown) {
            x = (float) (mx - dragGrabDx);
            y = (float) (my - dragGrabDy);
            clampPositionInView(viewW, viewH);
            GLFW.glfwSetCursor(window, 0);
            return;
        }
        updateHoverCursor(window, mx, my);
    }

    /** Move only: keep window on-screen without changing {@link #width} / {@link #height}. */
    private void clampPositionInView(float viewW, float viewH) {
        if (x + width > viewW) {
            x = viewW - width;
        }
        if (y + height > viewH) {
            y = viewH - height;
        }
        if (x < 0) {
            x = 0;
        }
        if (y < 0) {
            y = 0;
        }
    }

    /** After resize: cap size to the desktop and enforce {@link #MIN_W}×{@link #MIN_H}, then position. */
    private void clampResizeInView(float viewW, float viewH) {
        x      = Math.max(0f, x);
        y      = Math.max(0f, y);
        width  = Math.min(width, Math.max(0f, viewW - x));
        width  = Math.max(MIN_W, width);
        height = Math.min(height, Math.max(0f, viewH - y));
        height = Math.max(MIN_H, height);
        x      = Math.max(0f, Math.min(x, viewW - width));
        y      = Math.max(0f, Math.min(y, viewH - height));
    }

    public void render(int fontTex) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        if (windowDecor != null) {
            windowDecor.draw(x, y, width, height);
            GL11.glColor3f(CLIENT_BG, CLIENT_BG, CLIENT_BG);
            fillRect(
                windowDecor.contentX(x),
                windowDecor.contentY(y),
                windowDecor.contentW(width),
                windowDecor.contentH(height)
            );
        } else {
            GL11.glColor3f(CLIENT_BG, CLIENT_BG, CLIENT_BG);
            fillRect(x, y + titleBarHeight, width, height - titleBarHeight);

            GL11.glColor3f(TITLE_BAR_R, TITLE_BAR_G, TITLE_BAR_B);
            fillRect(x, y, width, titleBarHeight);

            GL11.glColor3f(0.14f, 0.14f, 0.16f);
            GL11.glLineWidth(1f);
            outlineRect(x, y, width, height);
        }

        GL11.glColor3f(1f, 1f, 1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fontTex);

        int lh = HelloWorld.font.face.lineHeight + 3;
        float ty = y + titleBarHeight - lh * titleFontScale;
        float titleW = HelloWorld.measureTextAdvancePx(title) * titleFontScale;
        float titleX = x + (width - titleW) / 2f;
        HelloWorld.drawText(title, titleX, ty, titleFontScale);
        GL11.glColor3f(1f, 1f, 1f);
    }

    private static void fillRect(float x0, float y0, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x0 + w, y0);
        GL11.glVertex2f(x0 + w, y0 + h);
        GL11.glVertex2f(x0, y0 + h);
        GL11.glEnd();
    }

    private static void outlineRect(float x0, float y0, float w, float h) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x0 + w, y0);
        GL11.glVertex2f(x0 + w, y0 + h);
        GL11.glVertex2f(x0, y0 + h);
        GL11.glEnd();
    }
}
