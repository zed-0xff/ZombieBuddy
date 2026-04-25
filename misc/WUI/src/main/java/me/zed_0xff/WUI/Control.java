package me.zed_0xff.WUI;

abstract class Control extends Element {
    public boolean enabled = true;
    final Window window;

    public Control(Window window, int x, int y, int w, int h){
        super(x, y, w, h);
        this.window = window;
    }

    /** Render this control at (originX + x, originY + y). */
    public abstract void render(int fontTex, int originX, int originY);

    /**
     * The interactive area of this control (in the control's own coordinate space).
     * Clicks and cursor changes are gated on this. Defaults to the full bounding box.
     */
    protected boolean isActiveAt(int mx, int my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    /**
     * Handle a mouse button event. Coordinates are in the same logical space as the control's
     * own x/y (i.e. already offset by the parent's content origin).
     * @param action GLFW_PRESS or GLFW_RELEASE
     */
    public void handleMouseButton(int action, int mx, int my) {}

    /** Return the cursor handle to use when the mouse is at (mx, my) in content-relative coords, or 0 for default. */
    public long cursorAt(int mx, int my) { return 0; }
}
