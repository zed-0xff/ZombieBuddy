package me.zed_0xff.zombie_buddy;

public class GameUtils {
    public static void onGameInitComplete() {
        // Run any registered experimental or core hooks
        Hooks.run("onGameInitComplete");
    }
}
