package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

import zombie.Lua.LuaManager;
import zombie.network.packets.INetworkPacket;

// doesn't work on _client_ without warmup
@Patch(className = "zombie.network.PacketsCache", methodName = "getPacket", warmUp = true)
public class Patch_PacketsCache {
    @Patch.OnExit
    public static void exit(@Patch.Return Object obj) {
        if (obj == null)
            return;
        if (!ZBPacketLog.isEnabled())
            return;

        String simpleName = obj.getClass().getSimpleName();
        if (!ZBPacketLog.shouldLog(simpleName))
            return;

        String prefix = "default: ";
        if (LuaManager.GlobalObject.isClient())
            prefix = "client: ";
        else if (LuaManager.GlobalObject.isServer())
            prefix = "server: ";

        INetworkPacket pkt = (INetworkPacket) obj;
        try {
            Logger.out.println(prefix + pkt.getDescription());
        } catch (Exception e) {
            Logger.out.println(prefix + simpleName + " - " + e.getMessage());
        }
    }
}
