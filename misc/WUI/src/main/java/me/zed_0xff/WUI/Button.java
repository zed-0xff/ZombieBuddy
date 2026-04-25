package me.zed_0xff.WUI;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

class Button extends ButtonBase {
    static final ElementDecor _normalDeco  = new ElementDecor("button");
    static final ElementDecor _pressedDeco = new ElementDecor("buttonDown");

    Color bgColor = Color.GRAY;
    private boolean pressed;

    public Button(Window window, int x, int y, int w, int h, String text) {
        super(window, x, y, w, h, text);
    }

    @Override
    public void handleMouseButton(int action, int mx, int my) {
        if (action == GLFW.GLFW_PRESS && isActiveAt(mx, my)) {
            pressed = true;
        } else if (action == GLFW.GLFW_RELEASE) {
            pressed = false;
        }
    }

    @Override
    public void render(int fontTex, int originX, int originY) {
        int bx = originX + x;
        int by = originY + y;

        ElementDecor deco = (pressed && _pressedDeco.isLoaded()) ? _pressedDeco : _normalDeco;
        deco.render(bx, by, width, height, bgColor);

        if (text != null && !text.isEmpty()) {
            // ElementDecor.draw() disables texturing and unbinds its texture; restore font texture for glyphs.
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, fontTex);
            glColor(textColor);
            font.drawTextCentered(bx, by + deco.textY, width, text);
        }
    }
}
