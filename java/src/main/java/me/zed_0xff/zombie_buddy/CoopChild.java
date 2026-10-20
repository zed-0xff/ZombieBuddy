package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.Arrays;
import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontend;

/** The local Coop child consumes client decisions; it never owns their persistence or UI. */
public final class CoopChild {
    private static final boolean READ_ONLY = detect();
    private static final ModApprovalFrontend FRONTEND = pending -> pending;

    private CoopChild() {}

    public static boolean isReadOnly() {
        return READ_ONLY;
    }

    private static boolean detect() {
        if (Boolean.getBoolean("zb.coop.child")) return true;
        String command = System.getProperty("sun.java.command", "");
        return command.startsWith("zombie.network.GameServer ")
                && Arrays.asList(command.split("\\s+")).contains("-coop");
    }

    public static ModApprovalFrontend frontend() {
        return FRONTEND;
    }

    public static void refreshConfig() {
        if (READ_ONLY) Loader.initConfig();
    }

    public static void deleteLegacy(Path path) throws IOException {
        if (!READ_ONLY) Files.delete(path);
    }

    public static boolean legacyExists(Path path, LinkOption... options) {
        return !READ_ONLY && Files.exists(path, options);
    }
}
