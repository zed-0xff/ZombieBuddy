package me.zed_0xff.WUI;

abstract class ButtonBase extends Label {
    public ButtonBase(Window window, int x, int y, int w, int h, String text) {
        super(window, x, y, w, h, text);
    }

    @Override
    public long cursorAt(int mx, int my) {
        return (enabled && isActiveAt(mx, my)) ? CursorMgr.hand : 0;
    }
}