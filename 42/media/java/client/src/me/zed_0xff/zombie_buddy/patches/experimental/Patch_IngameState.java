package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.*;

/**
 * Patch IngameState.UpdateStuff() to poll Lua tasks.
 * Called every ~20ms during gameplay. Redundant with Patch_UIManager on client,
 * but kept for compatibility. Safe to call pollLuaTasks() multiple times per frame.
 */
@Patch(className = "zombie.gameStates.IngameState", methodName = "UpdateStuff")
public class Patch_IngameState {
    @Patch.OnEnter
    static void enter() {
        HttpServer.pollLuaTasks();
    }
}
