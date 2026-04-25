package me.zed_0xff.WUI;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

class RadioButton extends ToggleBase {
    private static final Atlas ATLAS = new Atlas("radiobutton");
    private final String key;

    public RadioButton(Window window, int x, int y, int w, int h, String text, String key) {
        super(window, x, y, w, h, text);
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    @Override protected Atlas getAtlas() { return ATLAS; }

    @Override
    public void handleMouseButton(int action, int mx, int my) {
        if (action == GLFW.GLFW_PRESS && isActiveAt(mx, my)) {
            pressed = true;
        } else if (action == GLFW.GLFW_RELEASE) {
            if (pressed && isActiveAt(mx, my) && !checked) {
                window.onRadioButtonChecked(this);
                checked = true;
            }
            pressed = false;
        }
    }

    @Override
    protected void renderFallback(int bx, int by) {
        int r = height / 2;
        int cx = bx + r, cy = by + r;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        glColor(Color.BLACK);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < 12; i++) {
            double a = 2 * Math.PI * i / 12;
            GL11.glVertex2f((float)(cx + r * Math.cos(a)), (float)(cy + r * Math.sin(a)));
        }
        GL11.glEnd();
        if (checked) {
            int ir = r - 3;
            glColor(textColor);
            GL11.glBegin(GL11.GL_POLYGON);
            for (int i = 0; i < 12; i++) {
                double a = 2 * Math.PI * i / 12;
                GL11.glVertex2f((float)(cx + ir * Math.cos(a)), (float)(cy + ir * Math.sin(a)));
            }
            GL11.glEnd();
        }
    }
}
