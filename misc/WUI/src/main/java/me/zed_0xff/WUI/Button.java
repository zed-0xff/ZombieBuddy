package me.zed_0xff.WUI;

class Button extends Label {
    static final NinePatch _normalDeco  = new NinePatch("button");
    static final NinePatch _pressedDeco = new NinePatch("buttonDown");

    public Button(int x, int y, int w, int h, String text) {
        super(x, y, w, h, text);
    }
}
