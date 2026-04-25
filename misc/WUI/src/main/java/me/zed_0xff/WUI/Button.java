package me.zed_0xff.WUI;

class Button extends Label {
    static final ElementDecor _normalDeco  = new ElementDecor("button");
    static final ElementDecor _pressedDeco = new ElementDecor("buttonDown");

    public Button(int x, int y, int w, int h, String text) {
        super(x, y, w, h, text);
    }
}
