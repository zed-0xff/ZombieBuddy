package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.*;

// called by server every 100ms even if game is paused (no players connected)
@Patch(className = "zombie.network.ServerMap", methodName = "preupdate")
public class Patch_ServerMap {
    @Patch.OnEnter
    static void enter() {
        HttpServer.pollLuaTasks();
    }
}
