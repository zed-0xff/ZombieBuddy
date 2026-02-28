package me.zed_0xff.zombie_buddy;

import java.io.File;

import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

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
            Logger.error("Could not load version info: " + e.getMessage());
        }
    }
    
    public static String getVersion() {
        return version;
    }
    
    public static String getFullVersionString() {
        return "ZombieBuddy v" + version;
    }

    public static String getClosureFilename(Object obj) {
        if (obj instanceof LuaClosure closure) {
            if (closure == null || closure.prototype == null)
                return null;

            return closure.prototype.filename;
        }

        return null;
    }

    public static KahluaTable getClosureInfo(Object obj) {
        if (obj instanceof LuaClosure closure) {
            if (closure == null || closure.prototype == null)
                return null;

            var tbl = LuaManager.platform.newTable();
            tbl.rawset("file",     closure.prototype.file);
            tbl.rawset("filename", closure.prototype.filename);
            tbl.rawset("name",     closure.prototype.name);

            if (closure.prototype.lines != null && closure.prototype.lines.length > 0) {
                tbl.rawset("line", Double.valueOf(closure.prototype.lines[0]));
            }
            String path = closure.prototype.filename;
            if (path != null && !path.isEmpty()) {
                long lastModified = new File(path).lastModified();
                if (lastModified != 0L) {
                    tbl.rawset("fileLastModified", Double.valueOf(lastModified));
                }
            }
            return tbl;
        }

        return null;
    }
}
