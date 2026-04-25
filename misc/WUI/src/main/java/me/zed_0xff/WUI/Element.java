package me.zed_0xff.WUI;

import org.lwjgl.opengl.GL11;

abstract class Element {
    public int x, y, width, height;

    static Font font = new Font();

    public Element(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.width = w;
        this.height = h;
    }

    static void glColor(Color color){
        int c = color.getInt();
        GL11.glColor3ub((byte)((c>>16) & 0xff), (byte)((c>>8) & 0xff), (byte)(c & 0xff));
    }

    static void fillRect(int x0, int y0, int w, int h, Color color) {
        glColor(color);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x0 + w, y0);
        GL11.glVertex2f(x0 + w, y0 + h);
        GL11.glVertex2f(x0, y0 + h);
        GL11.glEnd();
    }

    static void outlineRect(int x0, int y0, int w, int h, int lineWidth, Color color) {
        glColor(color);
        GL11.glLineWidth(lineWidth);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x0 + w, y0);
        GL11.glVertex2f(x0 + w, y0 + h);
        GL11.glVertex2f(x0, y0 + h);
        GL11.glEnd();
    }
}
