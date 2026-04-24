package me.zed_0xff.zombie_buddy.patches.experimental;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import me.zed_0xff.zombie_buddy.Agent;
import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "org.lwjglx.opengl.Display", methodName = "setTitle")
public class Patch_Display_title {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(value = 0, readOnly = false) String title) {
        if (Agent.arguments.containsKey("window_title")) {
            String rawTitle = Agent.arguments.get("window_title");
            try {
                title = URLDecoder.decode(rawTitle, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // Keep raw value if percent-encoding is invalid.
                title = rawTitle;
            }
        }
    }
}