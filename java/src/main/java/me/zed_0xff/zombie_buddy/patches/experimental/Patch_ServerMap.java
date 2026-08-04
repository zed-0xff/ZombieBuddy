package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.annotations.Patch;

// called by server every 100ms even if game is paused (no players connected)
@Patch(className = "zombie.network.ServerMap", methodName = "preupdate")
public class Patch_ServerMap {
    @Patch.OnEnter
    public static void enter() {
        HttpServer.maybeRunLuaTasks();
    }
}
