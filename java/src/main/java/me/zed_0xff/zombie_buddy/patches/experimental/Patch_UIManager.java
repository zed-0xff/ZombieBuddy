package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.*;

/**
 * Patch UIManager.update() to poll Lua tasks on every frame.
 * This runs on the main game thread (GameWindow.gameThread) which is the Lua thread owner.
 * Works in all client states: main menu, loading, in-game, paused.
 */
@Patch(className = "zombie.ui.UIManager", methodName = "update")
public class Patch_UIManager {
    @Patch.OnEnter
    static void enter() {
        HttpServer.maybeRunLuaTasks();
    }
}
