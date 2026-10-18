package me.zed_0xff.zombie_buddy.patches;

import java.util.List;

import me.zed_0xff.zombie_buddy.Loader;
import me.zed_0xff.zombie_buddy.Reflect;
import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.network.GameClient;

/**
 * Stealth mode: hides ZombieBuddy's own Lua files from the multiplayer Lua checksum,
 * so a client with ZB can join a server without it ("File doesn't exist on the server").
 * Only applies when ZombieBuddy is not in the server's mod list.
 */
public class Patch_NetChecksum {
    public static boolean isOwnFile(String relPath) {
        if (relPath == null) {
            return false;
        }
        String p = relPath.replace('\\', '/').toLowerCase();
        return p.endsWith("media/lua/client/zombiebuddy.lua") || p.endsWith("media/lua/client/zombiebuddy_options.lua");
    }

    public static boolean serverLacksZB() {
        if (GameClient.instance == null) {
            return false;
        }
        // field was renamed ServerMods -> serverMods in 42.13; unknown => don't hide anything
        Object mods = Reflect.on(GameClient.instance).field("serverMods", "ServerMods").get(null);
        return mods instanceof List<?> list && !list.contains("ZombieBuddy");
    }

    @Patch(className = "zombie.network.NetChecksum$Checksummer", methodName = "addFile")
    public static class Patch_addFile {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(String relPath, String absPath) {
            return Loader.stealthMode() && Patch_NetChecksum.isOwnFile(relPath) && Patch_NetChecksum.serverLacksZB();
        }
    }
}
