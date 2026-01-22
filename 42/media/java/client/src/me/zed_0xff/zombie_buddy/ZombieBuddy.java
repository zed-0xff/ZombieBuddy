package me.zed_0xff.zombie_buddy;

public class ZombieBuddy {
    private static String version = "unknown";
    
    static {
        loadVersionInfo();
        Exposer.exposeClassToLua(ZombieBuddy.class);
    }
    
    private static void loadVersionInfo() {
        try {
            Package pkg = ZombieBuddy.class.getPackage();
            if (pkg != null) {
                String implVersion = pkg.getImplementationVersion();
                if (implVersion != null && !implVersion.isEmpty()) {
                    version = implVersion;
                }
            }
        } catch (Exception e) {
            System.err.println("[ZB] Could not load version info: " + e.getMessage());
        }
    }
    
    public static String getVersion() {
        return version;
    }
    
    public static String getFullVersionString() {
        return "ZombieBuddy v" + version;
    }

    /**
     * Compares two version strings.
     * Supports semantic versioning (e.g., "1.0.0", "1.2.3-beta").
     * 
     * @param v1 First version string
     * @param v2 Second version string
     * @return Negative if v1 < v2, positive if v1 > v2, zero if v1 == v2
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
}
