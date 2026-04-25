package me.zed_0xff.WUI;

class Label extends Control {
    String text;

    public Label(int x, int y, int w, int h, String text) {
        super(x, y, w, h);
        this.text = text;
    }
}
