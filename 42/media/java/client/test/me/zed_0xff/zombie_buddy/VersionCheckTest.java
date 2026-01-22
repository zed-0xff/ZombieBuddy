package me.zed_0xff.zombie_buddy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class VersionCheckTest {
    @Test
    void testCompareVersions() {
        assertEquals(0, ZombieBuddy.compareVersions("1.0.0", "1.0.0"));
        assertEquals(1, ZombieBuddy.compareVersions("1.1.0", "1.0.0"));
        assertEquals(-1, ZombieBuddy.compareVersions("1.0.0", "1.1.0"));
        assertEquals(1, ZombieBuddy.compareVersions("1.10.0", "1.2.0"));
        assertEquals(-1, ZombieBuddy.compareVersions("1.2.0", "1.10.0"));
        assertEquals(0, ZombieBuddy.compareVersions("1.0.0-beta", "1.0.0"));
        assertEquals(1, ZombieBuddy.compareVersions("2.0", "1.9.9"));
        assertEquals(-1, ZombieBuddy.compareVersions("unknown", "1.0.0"));
        assertEquals(1, ZombieBuddy.compareVersions("1.0.0", "unknown"));
    }

    @Test
    void testIsVersionInRange() {
        // Current version is 1.2.3
        String current = "1.2.3";
        
        // No limits
        assertTrue(JavaModInfo.isVersionInRange(current, null, null));
        assertTrue(JavaModInfo.isVersionInRange(current, "", ""));
        
        // Min limit
        assertTrue(JavaModInfo.isVersionInRange(current, "1.0.0", null));
        assertTrue(JavaModInfo.isVersionInRange(current, "1.2.3", null));
        assertFalse(JavaModInfo.isVersionInRange(current, "1.2.4", null));
        assertFalse(JavaModInfo.isVersionInRange(current, "2.0.0", null));
        
        // Max limit
        assertTrue(JavaModInfo.isVersionInRange(current, null, "2.0.0"));
        assertTrue(JavaModInfo.isVersionInRange(current, null, "1.2.3"));
        assertFalse(JavaModInfo.isVersionInRange(current, null, "1.2.2"));
        assertFalse(JavaModInfo.isVersionInRange(current, null, "1.0.0"));
        
        // Both limits
        assertTrue(JavaModInfo.isVersionInRange(current, "1.0.0", "2.0.0"));
        assertTrue(JavaModInfo.isVersionInRange(current, "1.2.3", "1.2.3"));
        assertFalse(JavaModInfo.isVersionInRange(current, "1.2.4", "2.0.0"));
        assertFalse(JavaModInfo.isVersionInRange(current, "1.0.0", "1.2.2"));
    }
}
