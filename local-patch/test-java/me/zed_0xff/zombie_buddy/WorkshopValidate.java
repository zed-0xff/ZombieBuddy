package me.zed_0xff.zombie_buddy;

import java.nio.file.*;
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
        if (!desc.contains("authors.json") || !desc.contains("codex/optimized-b42") || !desc.contains("将删除"))
            throw new AssertionError("Workshop description missing requested installation/source/temporary notice");
        System.out.println("PASS actual game Workshop validator; " + item.getTitle());
    }
}
