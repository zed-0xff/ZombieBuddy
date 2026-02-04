package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.*;

// called by game every 20ms, both client and server
// NOT called by server if the game is paused
@Patch(className = "zombie.gameStates.IngameState", methodName = "UpdateStuff")
public class Patch_IngameState {
    @Patch.OnEnter
    static void enter() {
        HttpServer.pollLuaTasks();
    }
}
