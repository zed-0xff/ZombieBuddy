package me.zed_0xff.WUI;

import org.lwjgl.opengl.GL11;

class Label extends Control {
    String text;
    Color textColor = Color.BLACK;

    public Label(Window window, int x, int y, int w, int h, String text) {
        super(window, x, y, w, h);
        this.text = text;
    }

    @Override
    public void render(int fontTex, int originX, int originY) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // Controls must not assume GL state (decor drawing can disable texturing / unbind textures).
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fontTex);
        glColor(textColor);
        int tx = originX + x;
        int ty = originY + y;
        font.drawText(tx, ty, text);
    }
}
