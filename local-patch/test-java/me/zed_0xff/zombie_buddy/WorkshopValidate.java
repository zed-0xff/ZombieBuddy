package me.zed_0xff.zombie_buddy;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import zombie.core.znet.SteamWorkshopItem;

public final class WorkshopValidate {
    public static void main(String[] args) throws Exception {
        // Initialize only the filesystem base used by the validator's path allowlist.
        zombie.ZomboidFileSystem.instance.base.set(Path.of(args[1]).toFile());
        var item = new SteamWorkshopItem(args[0]);
        String preview = item.validatePreviewImage(Path.of(args[0], "preview.png"));
        if (preview != null) throw new AssertionError("preview: " + preview);
        String contents = item.validateContents();
        if (contents != null) throw new AssertionError("contents: " + contents);
        if (!item.readWorkshopTxt()) throw new AssertionError("workshop.txt cannot be read");
        String desc = item.getDescription();
        if (!desc.contains("authors.json") || !desc.contains("b42-compatibility-performance-localization") || !desc.contains("将删除"))
            throw new AssertionError("Workshop description missing requested installation/source/temporary notice");
        int descriptionBytes = item.getSubmitDescription().getBytes(StandardCharsets.UTF_8).length;
        int titleBytes = item.getTitle().getBytes(StandardCharsets.UTF_8).length;
        if (descriptionBytes >= 8000) throw new AssertionError("Steam description exceeds byte limit: " + descriptionBytes);
        if (titleBytes > 128) throw new AssertionError("Steam title exceeds byte limit: " + titleBytes);
        System.out.println("Steam submit metadata: description=" + descriptionBytes + "/8000 bytes, title=" + titleBytes + "/128 bytes");
        System.out.println("PASS actual game Workshop validator; " + item.getTitle());
    }
}
