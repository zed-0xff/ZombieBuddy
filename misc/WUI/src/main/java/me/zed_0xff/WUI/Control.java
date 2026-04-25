package me.zed_0xff.WUI;

abstract class Control extends Element {
    public boolean enabled;

    public Control(int x, int y, int w, int h){
        super(x, y, w, h);
    }

    /** Render this control at (originX + x, originY + y). */
    public abstract void render(int fontTex, int originX, int originY);

    /**
     * Handle a mouse button event. Coordinates are in the same logical space as the control's
     * own x/y (i.e. already offset by the parent's content origin).
     * @param action GLFW_PRESS or GLFW_RELEASE
     * @param mx     logical mouse X relative to control origin
     * @param my     logical mouse Y relative to control origin
     */
    public void handleMouseButton(int action, int mx, int my) {}
}
