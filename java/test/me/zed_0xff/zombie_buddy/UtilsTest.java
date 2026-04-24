package me.zed_0xff.zombie_buddy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UtilsTest {
    @Test
    void bytesToHex_emptyArray_returnsEmptyString() {
        assertEquals("", Utils.bytesToHex(new byte[0], "%02X", ":"));
    }

    @Test
    void bytesToHex_null_returnsNull() {
        assertNull(Utils.bytesToHex(null, "%02X", ":"));
    }

    @Test
    void bytesToHex_singleByte_uppercase() {
        assertEquals("00", Utils.bytesToHex(new byte[] { 0 }, "%02X", ":"));
        assertEquals("FF", Utils.bytesToHex(new byte[] { (byte) 0xFF }, "%02X", ":"));
        assertEquals("0A", Utils.bytesToHex(new byte[] { 10 }, "%02X", ":"));
    }

    @Test
    void bytesToHex_singleByte_lowercase() {
        assertEquals("00", Utils.bytesToHex(new byte[] { 0 }, "%02x", ""));
        assertEquals("ff", Utils.bytesToHex(new byte[] { (byte) 0xFF }, "%02x", ""));
        assertEquals("0a", Utils.bytesToHex(new byte[] { 10 }, "%02x", ""));
    }

    @Test
    void bytesToHex_multipleBytes_withColons() {
        assertEquals("00:01:02", Utils.bytesToHex(new byte[] { 0, 1, 2 }, "%02X", ":"));
        assertEquals("A7:75:10:1B", Utils.bytesToHex(new byte[] {
            (byte) 0xA7, (byte) 0x75, (byte) 0x10, (byte) 0x1B
        }, "%02X", ":"));
    }

    @Test
    void bytesToHex_multipleBytes_noSeparator() {
        assertEquals("000102", Utils.bytesToHex(new byte[] { 0, 1, 2 }, "%02x", ""));
        assertEquals("a775101b", Utils.bytesToHex(new byte[] {
            (byte) 0xA7, (byte) 0x75, (byte) 0x10, (byte) 0x1B
        }, "%02x", ""));
    }
}
