package me.zed_0xff.zombie_buddy;

import java.io.File;

import zombie.Lua.LuaManager;

public final class Utils {
    private Utils() {}

    public static boolean isClient() {
        return LuaManager.GlobalObject.isClient();
    }

    public static boolean isServer() {
        return LuaManager.GlobalObject.isServer();
    }

    /**
     * Compares two version strings.
     * Supports semantic versioning (e.g., "1.0.0", "1.2.3-beta").
     *
     * @param v1 First version string
     * @param v2 Second version string
     * @return Negative if v1 &lt; v2, positive if v1 &gt; v2, zero if v1 == v2
     */
    public static int compareVersions(String v1, String v2) {
        if (v1 == null || v1.equals("unknown")) return v2 == null || v2.equals("unknown") ? 0 : -1;
        if (v2 == null || v2.equals("unknown")) return 1;

        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int p1 = 0;
            if (i < parts1.length) {
                String s = parts1[i].replaceAll("[^0-9].*", "");
                if (!s.isEmpty()) p1 = Integer.parseInt(s);
            }
            int p2 = 0;
            if (i < parts2.length) {
                String s = parts2[i].replaceAll("[^0-9].*", "");
                if (!s.isEmpty()) p2 = Integer.parseInt(s);
            }
            if (p1 < p2) return -1;
            if (p1 > p2) return 1;
        }
        return 0;
    }

    /**
     * Compares two semantic version strings to determine if version1 is newer than version2.
     * Supports formats like "1.0.0", "1.2.3", "2.0.0-beta", etc.
     *
     * @param version1 The first version to compare
     * @param version2 The second version to compare
     * @return true if version1 is newer than version2, false otherwise
     */
    public static boolean isVersionNewer(String version1, String version2) {
        return compareVersions(version1, version2) > 0;
    }

    /**
     * Converts a byte array to a hexadecimal string with colons (standard fingerprint format).
     *
     * @param bytes The byte array to convert
     * @return The hexadecimal string representation (e.g. "A7:75:10:1B:...")
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(":");
            }
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

    /**
     * Returns the JAR file that contains the currently running ZombieBuddy code.
     *
     * @return the JAR file, or null if not found or not running from a JAR
     */
    public static File getCurrentJarFile() {
        try {
            java.security.CodeSource codeSource = Utils.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                java.net.URL location = codeSource.getLocation();
                if (location != null) {
                    java.net.URI uri = location.toURI();
                    File jarFile = new File(uri);
                    if (jarFile.exists() && jarFile.getName().endsWith(".jar")) {
                        return jarFile;
                    }
                }
            }
        } catch (Exception e) {
            Logger.error("Error getting current JAR file path: " + e.getMessage());
        }
        return null;
    }
}
