package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Exposer;
public class Main {
    public static void main(String[] args) {
        Exposer.exposeClassToLua(ZBPacketLog.class);
    }
}
