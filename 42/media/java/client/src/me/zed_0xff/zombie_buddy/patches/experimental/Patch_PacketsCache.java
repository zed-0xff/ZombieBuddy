package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.*;

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
        if (Utils.isClient())
            prefix = "client: ";
        else if (Utils.isServer())
            prefix = "server: ";

        INetworkPacket pkt = (INetworkPacket) obj;
        try {
            Logger.info(prefix + pkt.getDescription());
        } catch (Exception e) {
            Logger.info(prefix + simpleName + " - " + e.getMessage());
        }
    }
}
