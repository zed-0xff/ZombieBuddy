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
}
