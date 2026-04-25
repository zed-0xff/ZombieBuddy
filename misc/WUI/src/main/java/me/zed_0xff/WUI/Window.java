package me.zed_0xff.WUI;

import com.google.gson.Gson;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Win3.1-style faux window: title drag, resize from edges/corners, resize cursors on hover.
 * Coordinates match {@link HelloWorld}: origin top-left, y downward (framebuffer pixels).
 */
public class Window extends Element {
    Color backgroundColor = Color.WHITE;
    String title, status;
    List<Control> controls = new ArrayList<>();

    static final Color titleColor = Color.NAVY;
    static final ElementDecor _deco = new ElementDecor("window");

    static final int GRIP  = 8;
    static final int EDGE  = 6;
    static final int MIN_W = 120;
    static final int MIN_H = 80;
    static final int titleBarHeight = 23; // equal to top*.height in window.json

    private enum ResizeGrip {
        NONE, N, S, E, W, NE, NW, SE, SW
    }

    static long curArrow;
    static long curH;
    static long curV;
    static long curNwse;
    static long curNesw;
    static boolean cursorsCreated;

    boolean dragging;
    int dragGrabDx;
    int dragGrabDy;

    ResizeGrip activeResize = ResizeGrip.NONE;
    int resizeSnapX;
    int resizeSnapY;
    int resizeSnapW;
    int resizeSnapH;

    public Window(int x, int y, int width, int height, String title) {
        super(x, y, width, height);
        this.title  = title;
    }

    public void addControl(Control control) {
        controls.add(control);
    }

    public void setTitle(String s) {
        this.title = s;
    }

    public void setStatus(String s) {
        this.status = s;
    }

    /**
     * Loads {@code cursors.json} + image (see {@link CursorMgr}); falls back to GLFW standard cursors if missing/invalid.
     */
    public static void createCursors(File cursorsJson, Gson gson) {
        if (cursorsCreated) {
            return;
        }
        cursorsCreated = true;
        long[] handles = CursorMgr.loadCursors(cursorsJson, gson);
        if (handles != null) {
            curArrow = handles[0];
            curH     = handles[1];
            curV     = handles[2];
            curNwse  = handles[3];
            curNesw  = handles[4];
            // curText = handles[5]
            return;
        }
        createStandardCursors();
    }

    private static void createStandardCursors() {
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

    public static void destroyCursors() {
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

    public boolean contains(int mx, int my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    public boolean containsTitleBar(int mx, int my) {
        return mx >= x && mx < x + width && my >= y && my < y + titleBarHeight;
    }

    /** Title strip excluding edge grips so resize takes priority on corners/top border. */
    private boolean titleDragZone(int mx, int my) {
        if (!containsTitleBar(mx, my)) {
            return false;
        }
        if (mx < x + GRIP || mx >= x + width - GRIP) {
            return false;
        }
        return my >= y + EDGE;
    }

    private ResizeGrip hitTestResize(int mx, int my) {
        int x0 = x;
        int y0 = y;
        int x1 = x + width;
        int y1 = y + height;

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

    private void updateHoverCursor(long window, int mx, int my) {
        ResizeGrip g = hitTestResize(mx, my);
        if (g != ResizeGrip.NONE) {
            setCursor(window, cursorForGrip(g));
        } else {
            GLFW.glfwSetCursor(window, 0);
        }
    }

    private void applyResize(long window, int mx, int my, int viewW, int viewH) {
        int sx = resizeSnapX;
        int sy = resizeSnapY;
        int sw = resizeSnapW;
        int sh = resizeSnapH;
        int bot = sy + sh;
        int right = sx + sw;

        switch (activeResize) {
            case SE:
                x = sx;
                y = sy;
                width = Math.max(MIN_W, mx - sx);
                height = Math.max(MIN_H, my - sy);
                break;
            case NE:
                x = sx;
                y = my;
                width = Math.max(MIN_W, mx - sx);
                height = Math.max(MIN_H, bot - y);
                break;
            case NW:
                x = mx;
                y = my;
                width = Math.max(MIN_W, right - x);
                height = Math.max(MIN_H, bot - y);
                break;
            case SW:
                x = mx;
                y = sy;
                width = Math.max(MIN_W, right - x);
                height = Math.max(MIN_H, my - sy);
                break;
            case E:
                x = sx;
                width = Math.max(MIN_W, mx - sx);
                break;
            case W:
                x = mx;
                width = Math.max(MIN_W, right - mx);
                break;
            case S:
                y = sy;
                height = Math.max(MIN_H, my - sy);
                break;
            case N:
                y = my;
                height = Math.max(MIN_H, bot - my);
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
    public boolean handleMouseButton(long window, int button, int action, int mx, int my) {
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
                dragGrabDx = mx - x;
                dragGrabDy = my - y;
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
    public void handleCursorPos(long window, int mx, int my, int viewW, int viewH) {
        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (activeResize != ResizeGrip.NONE && leftDown) {
            applyResize(window, mx, my, viewW, viewH);
            return;
        }
        if (dragging && leftDown) {
            x = mx - dragGrabDx;
            y = my - dragGrabDy;
            clampPositionInView(viewW, viewH);
            GLFW.glfwSetCursor(window, 0);
            return;
        }
        updateHoverCursor(window, mx, my);
    }

    /** Move only: keep window on-screen without changing {@link #width} / {@link #height}. */
    private void clampPositionInView(int viewW, int viewH) {
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
    private void clampResizeInView(int viewW, int viewH) {
        x      = Math.max(0, x);
        y      = Math.max(0, y);
        width  = Math.min(width, Math.max(0, viewW - x));
        width  = Math.max(MIN_W, width);
        height = Math.min(height, Math.max(0, viewH - y));
        height = Math.max(MIN_H, height);
        x      = Math.max(0, Math.min(x, viewW - width));
        y      = Math.max(0, Math.min(y, viewH - height));
    }

    public void render(int fontTex) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        if (_deco.isLoaded()) {
            _deco.draw(x, y, width, height);
            fillRect(
                _deco.contentX(x),
                _deco.contentY(y),
                _deco.contentW(width),
                _deco.contentH(height),
                backgroundColor
            );
        } else {
            fillRect(x, y + titleBarHeight, width, height - titleBarHeight, backgroundColor);
            fillRect(x, y, width, titleBarHeight, titleColor);
            outlineRect(x, y, width, height, 1, Color.BLACK);
        }

        GL11.glColor3f(1f, 1f, 1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fontTex);

        int lh = font.face.lineHeight + 3;
        int ty = y + titleBarHeight - lh;
        int titleW = font.measureTextAdvancePx(title);
        int titleX = x + (width - titleW) / 2;
        font.drawText(titleX, ty, title);
        GL11.glColor3f(1f, 1f, 1f);
    }

    public void close() {
    }
}
