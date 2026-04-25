package me.zed_0xff.WUI;

import com.google.gson.Gson;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Win3.1-style faux window: title drag, resize from edges/corners, resize cursors on hover.
 * Coordinates match {@link HelloWorld}: origin top-left, y downward (framebuffer pixels).
 */
public class Window extends Element {
    Color bgColor = Color.WHITE;
    String title, status;
    private final List<Control> controls = new ArrayList<>();
    private final HashMap<String, List<RadioButton>> radioButtons = new HashMap<>();

    // static final Color titleBgColor = Color.NAVY;
    static final Color titleFgColor = Color.WHITE;
    static final ElementDecor _deco = new ElementDecor("window");

    static final int GRIP  = 8;
    static final int EDGE  = 6;
    static final int MIN_W = 120;
    static final int MIN_H = 80;
    static final int titleBarHeight = 23; // equal to top*.height in window.json

    private enum ResizeGrip {
        NONE, N, S, E, W, NE, NW, SE, SW
    }

    boolean dragging;
    int dragGrabDx;
    int dragGrabDy;

    ResizeGrip activeResize = ResizeGrip.NONE;
    int resizeSnapX;
    int resizeSnapY;
    int resizeSnapW;
    int resizeSnapH;

    private Rect contentRect;

    public Window(int x, int y, int width, int height, String title) {
        super(x, y, width, height);
        this.title  = title;
        recalcContentRect();
    }

    public Window addControl(java.util.function.Function<Window, Control> factory) {
        Control control = factory.apply(this);
        if (control == null) {
            throw new IllegalArgumentException("Factory returned null");
        }
        controls.add(control);
        if (control instanceof RadioButton radio) {
            String key = radio.getKey();
            radioButtons.computeIfAbsent(key, k -> new ArrayList<>()).add(radio);
        }
        return this;
    }

    public void setTitle(String s) {
        this.title = s;
    }

    public void setStatus(String s) {
        this.status = s;
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
            case N: case S: return CursorMgr.resizeV;
            case E: case W: return CursorMgr.resizeH;
            case NW: case SE: return CursorMgr.curNWSE;
            case NE: case SW: return CursorMgr.curNESW;
            default: return CursorMgr.arrow;
        }
    }

    private void updateHoverCursor(long window, int mx, int my) {
        ResizeGrip g = hitTestResize(mx, my);
        if (g != ResizeGrip.NONE) {
            CursorMgr.set(window, cursorForGrip(g));
        } else if (contains(mx, my)) {
            long cur = 0;
            if (!controls.isEmpty() && !contentRect.isEmpty()) {
                int cx = mx - contentRect.x(), cy = my - contentRect.y();
                for (Control c : controls) {
                    long cc = c.cursorAt(cx, cy);
                    if (cc != 0) { cur = cc; break; }
                }
            }
            CursorMgr.set(window, cur);
        } else {
            CursorMgr.setDefault(window);
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
        recalcContentRect();
        CursorMgr.set(window, cursorForGrip(activeResize));
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
        }
        if (action == GLFW.GLFW_RELEASE) {
            dragging = false;
            activeResize = ResizeGrip.NONE;
            updateHoverCursor(window, mx, my);
        }
        // Forward press/release to child controls (content-relative coords).
        if (!controls.isEmpty() && !contentRect.isEmpty()) {
            for (Control c : controls) {
                c.handleMouseButton(action, mx - contentRect.x(), my - contentRect.y());
            }
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
            recalcContentRect();
            CursorMgr.set(window, 0);
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

    private void recalcContentRect() {
        if (_deco.isLoaded()) {
            contentRect = _deco.contentRect(x, y, width, height);
        } else {
            contentRect = new Rect(x, y + titleBarHeight, width, height - titleBarHeight);
        }
    }

    public void render(int fontTex) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        _deco.render(x, y, width, height, bgColor);

        glColor(titleFgColor);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fontTex);

        font.drawTextCentered(x, y + _deco.textY, width, title);

        // Render child controls clipped to the content rect.
        if (!controls.isEmpty() && !contentRect.isEmpty()) {
            int scale = HelloWorld.uiScale();
            IntBuffer vp = BufferUtils.createIntBuffer(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
            int fbH = vp.get(3);

            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(
                contentRect.x() * scale,
                fbH - (contentRect.y() + contentRect.h()) * scale,
                contentRect.w() * scale,
                contentRect.h() * scale
            );
            for (Control c : controls) {
                c.render(fontTex, contentRect.x(), contentRect.y());
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    public void close() {
    }

    void onRadioButtonChecked(RadioButton radio) {
        String key = radio.getKey();
        radioButtons.get(key)
                .stream()
                .filter(r -> r != radio)
                .forEach(r -> r.checked = false);
    }
}
