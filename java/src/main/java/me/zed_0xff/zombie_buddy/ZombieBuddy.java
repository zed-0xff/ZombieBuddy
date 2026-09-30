package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

//@Exposer.LuaClass doesn't always work here because ZB is loaded in premain
public class ZombieBuddy {
    private static String version = "unknown";
    
    static {
        loadVersionInfo();
    }
    
    private static void loadVersionInfo() {
        try {
            Package pkg = ZombieBuddy.class.getPackage();
            if (pkg != null) {
                String implVersion = pkg.getImplementationVersion();
                if (!Utils.isBlank(implVersion)) {
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
        return "ZombieBuddy v" + version + " [LY Optimized 1.0.0]";
    }

    public static void setAutoFixModOrder(boolean value) {
        Loader.setAutoFixModOrder(value);
    }

    // lua-facing variant of Loader.getActiveJavaMods(). Returns a list of modIds for JARs that were loaded this run.
    public static KahluaTable getActiveJavaMods() {
        var tbl = LuaManager.platform.newTable();
        for (var modInfo : Loader.getActiveJavaMods()) {
            var modTbl = LuaManager.platform.newTable();
            modTbl.rawset("id", modInfo.id());
            modTbl.rawset("jarPath", modInfo.jarPath());
            modTbl.rawset("flags", LuaUtils.mapToLuaTable(modInfo.flags().toMap()));
            tbl.rawset(modInfo.id(), modTbl);
        }
        return tbl;
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
            tbl.rawset("file",      closure.prototype.file);
            tbl.rawset("filename",  closure.prototype.filename);
            tbl.rawset("name",      closure.prototype.name);
            tbl.rawset("numParams", Double.valueOf(closure.prototype.numParams));
            tbl.rawset("isVararg",  closure.prototype.isVararg);

            if (closure.prototype.lines != null && closure.prototype.lines.length > 0) {
                tbl.rawset("line", Double.valueOf(closure.prototype.lines[0]));
            }
            String path = closure.prototype.filename;
            if (!Utils.isBlank(path)) {
                long lastModified = 0L;
                try { lastModified = Files.getLastModifiedTime(Path.of(path)).toMillis(); } catch (IOException ignored) {}
                if (lastModified != 0L) {
                    tbl.rawset("fileLastModified", Double.valueOf(lastModified));
                }
            }
            return tbl;
        }

        return null;
    }

    /** Returns metadata for any callable: Lua closure or Java function. */
    public static KahluaTable getCallableInfo(Object obj) {
        if (obj instanceof LuaClosure closure) {
            if (closure == null || closure.prototype == null)
                return null;
            var tbl = getClosureInfo(obj);
            if (tbl != null) tbl.rawset("kind", "lua");
            return tbl;
        }
        if (obj instanceof JavaFunction) {
            var tbl = LuaManager.platform.newTable();
            tbl.rawset("kind", "java");
            Class<?> c = obj.getClass();
            tbl.rawset("className", c.getName());
            tbl.rawset("simpleName", c.getSimpleName());
            tbl.rawset("name", obj.toString());
            LuaUtils.addInvokersInfo(tbl, obj);
            return tbl;
        }
        return null;
    }
}
