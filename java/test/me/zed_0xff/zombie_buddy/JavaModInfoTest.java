package me.zed_0xff.zombie_buddy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import java.nio.file.Path;
import java.nio.file.Files;

class JavaModInfoTest {
    private static final String HOME_DIR  = System.getProperty("user.home");
    @TempDir Path tempDir;
    private String cacheDir;

    private MockedStatic<Utils> utilsMock;
    @BeforeEach
    void setUp() throws Exception {
        cacheDir = tempDir.resolve("Zomboid").toString();
        Path workshop = Files.createDirectories(Path.of(cacheDir, "Workshop", "ZBExhume41"));
        Files.writeString(workshop.resolve("workshop.txt"), "version=1\nid=3718604798\n");
        utilsMock = mockStatic(Utils.class, CALLS_REAL_METHODS);
        utilsMock.when(Utils::getCacheDir).thenReturn(cacheDir);
        utilsMock.when(Utils::getCachePath).thenReturn(Path.of(cacheDir));
    }

    @AfterEach
    void tearDown() {
        utilsMock.close();
    }

    @Test
    void workshopItemIdFromInfPath_valid() {
        assertEquals(
                new WorkshopItemID(3718604798L),
                JavaModInfo.workshopItemIdFromInfPath( Path.of(cacheDir, "Workshop/ZBExhume41/Contents/mods/ZBExhume41/common/mod.info"))
                );
        assertEquals(
                new WorkshopItemID(2986022978L),
                JavaModInfo.workshopItemIdFromInfPath( Path.of(HOME_DIR, "/Library/Application Support/Steam/steamapps/workshop/content/108600/2986022978/mods/DoubleDeckerBusInterior"))
                );
    }

    @Test
    void workshopItemIdFromInfPath_invalid() {
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of(cacheDir, "Workshop/ZBExhume41/Contents/mods/ZBExhume41/common/foo/mod.info")));
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of(cacheDir, "Workshop/ZBExhume41/Contents/mods/ZBExhume41/mod.info")));
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of(cacheDir, "Workshop/ZBExhume41/Contents/mods/ZBExhume41/42.13/media/java/ZBExhume41.jar")));
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of("/etc/passwd")));
    }

    @Test
    void workshopItemIdFromInfPath_oneDirBelowRoot() {
        // 4 levels up from mod.info lands on "/x", whose parent is the root (no file name)
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of("/x/cache/mods/ZBPopFix/42.20/mod.info")));
    }
}
