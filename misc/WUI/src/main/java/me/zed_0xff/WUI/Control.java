package me.zed_0xff.WUI;

abstract class Control extends Element {
    public boolean enabled;

    public Control(int x, int y, int w, int h){
        super(x, y, w, h);
    }
}
