package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

public final class Utils {
    private Utils() {}

    private static final Pattern PRERELEASE_PATTERN = Pattern.compile("^([a-z]+)(\\d*)");

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

    public static int compareVersionsForUpdate(String v1, String v2) {
        if (v1 == null || v1.equals("unknown")) return v2 == null || v2.equals("unknown") ? 0 : -1;
        if (v2 == null || v2.equals("unknown")) return 1;

        ParsedVersion parsed1 = parseVersion(v1);
        ParsedVersion parsed2 = parseVersion(v2);
        int core = compareVersions(parsed1.core, parsed2.core);
        if (core != 0) return core;
        return comparePrerelease(parsed1.prerelease, parsed2.prerelease);
    }

    private static ParsedVersion parseVersion(String version) {
        String[] parts = version.split("-", 2);
        String prerelease = parts.length > 1 ? parts[1] : "";
        return new ParsedVersion(parts[0], prerelease);
    }

    private static int comparePrerelease(String p1, String p2) {
        boolean release1 = p1 == null || p1.isEmpty();
        boolean release2 = p2 == null || p2.isEmpty();
        if (release1 || release2) {
            if (release1 == release2) return 0;
            return release1 ? 1 : -1;
        }

        ParsedPrerelease parsed1 = parsePrerelease(p1);
        ParsedPrerelease parsed2 = parsePrerelease(p2);
        if (parsed1.rank != parsed2.rank) {
            return Integer.compare(parsed1.rank, parsed2.rank);
        }
        if (!parsed1.name.equals(parsed2.name)) {
            return parsed1.name.compareTo(parsed2.name);
        }
        return Integer.compare(parsed1.number, parsed2.number);
    }

    private static ParsedPrerelease parsePrerelease(String prerelease) {
        String normalized = prerelease.toLowerCase(Locale.ROOT);
        Matcher m = PRERELEASE_PATTERN.matcher(normalized);
        if (!m.find()) {
            return new ParsedPrerelease(normalized, 0, -1);
        }
        String name = m.group(1);
        String number = m.group(2);
        return new ParsedPrerelease(name, prereleaseRank(name), number.isEmpty() ? -1 : Integer.parseInt(number));
    }

    private static int prereleaseRank(String name) {
        if ("alpha".equals(name)) return 1;
        if ("beta".equals(name)) return 2;
        return 0;
    }

    private record ParsedVersion(String core, String prerelease) {}

    private record ParsedPrerelease(String name, int rank, int number) {}

    /**
     * Compares two semantic version strings to determine if version1 is newer than version2.
     * Supports formats like "1.0.0", "1.2.3", "2.0.0-beta", etc.
     *
     * @param version1 The first version to compare
     * @param version2 The second version to compare
     * @return true if version1 is newer than version2, false otherwise
     */
    public static boolean isVersionNewer(String version1, String version2) {
        return compareVersionsForUpdate(version1, version2) > 0;
    }

    /**
     * Converts a byte array to a hexadecimal string.
     *
     * @param bytes The byte array to convert
     * @param fmt Format string for each byte (e.g. "%02X" for uppercase, "%02x" for lowercase)
     * @param sep Separator between bytes (e.g. ":" for fingerprints, "" for hashes)
     * @return The hexadecimal string representation, or null if bytes is null
     */
    public static String bytesToHex(byte[] bytes, String fmt, String sep) {
        if (bytes == null) return null;
        StringBuilder sb = new StringBuilder(bytes.length * (2 + sep.length()));
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0 && !sep.isEmpty()) sb.append(sep);
            sb.append(String.format(fmt, bytes[i]));
        }
        return sb.toString();
    }

    /**
     * Compute SHA-256 hash of a byte array.
     * @return raw hash bytes or null on error
     */
    public static byte[] sha256(byte[] data) {
        if (data == null) return null;
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            Logger.error("Could not compute SHA-256: " + e);
            return null;
        }
    }

    /**
     * Compute SHA-256 hash of a byte array as lowercase hex string.
     * @return hex string or null on error
     */
    public static String sha256Hex(byte[] data) {
        byte[] hash = sha256(data);
        return hash != null ? bytesToHex(hash, "%02x", "") : null;
    }

    /**
     * Compute SHA-256 hash of a file as lowercase hex string.
     * @return hex string or null if file doesn't exist or error occurs
     */
    public static String sha256Hex(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
            return bytesToHex(md.digest(), "%02x", "");
        } catch (Exception e) {
            Logger.error("Could not hash file " + file + ": " + e);
            return null;
        }
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

    /**
     * Path to the ZombieBuddy JAR on disk (for spawning subprocess).
     * @return absolute path or null if not running from a JAR
     */
    public static String getZombieBuddyJarPath() {
        try {
            java.security.CodeSource cs = Utils.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            java.nio.file.Path p = java.nio.file.Path.of(cs.getLocation().toURI());
            if (!java.nio.file.Files.isRegularFile(p)) return null;
            return p.toAbsolutePath().toString();
        } catch (Exception e) {
            Logger.warn("Could not resolve ZombieBuddy JAR path: " + e);
            return null;
        }
    }

    /**
     * If {@code obj} is a kahlua-exposed Java invoker (MultiLuaJavaInvoker / LuaJavaInvoker),
     * fills {@code out.invokers} with per-overload metadata.
     */
    public static void addInvokersInfo(KahluaTable out, Object obj) {
        if (out == null || obj == null) return;
        try {
            Class<?> c = obj.getClass();
            List<?> list;
            if ("se.krka.kahlua.integration.expose.MultiLuaJavaInvoker".equals(c.getName())) {
                Object invokers = c.getMethod("getInvokers").invoke(obj);
                list = (invokers instanceof List<?> l && !l.isEmpty()) ? l : null;
            } else if ("se.krka.kahlua.integration.expose.LuaJavaInvoker".equals(c.getName())) {
                list = Collections.singletonList(obj);
            } else {
                list = null;
            }
            if (list == null || list.isEmpty()) return;

            Class<?> invokerClass = list.get(0).getClass();
            if (!"se.krka.kahlua.integration.expose.LuaJavaInvoker".equals(invokerClass.getName())) return;

            Field clazzField = invokerClass.getDeclaredField("clazz");
            clazzField.setAccessible(true);
            Field nameField = invokerClass.getDeclaredField("name");
            nameField.setAccessible(true);
            Field callerField = invokerClass.getDeclaredField("caller");
            callerField.setAccessible(true);

            var invokersTbl = LuaManager.platform.newTable();
            for (int i = 0; i < list.size(); i++) {
                Object inv = list.get(i);
                var invTbl = LuaManager.platform.newTable();
                Class<?> targetClass = (Class<?>) clazzField.get(inv);
                String methodName = (String) nameField.get(inv);
                Object caller = callerField.get(inv);
                invTbl.rawset("targetClass", targetClass.getName());
                invTbl.rawset("targetSimpleClass", targetClass.getSimpleName());
                invTbl.rawset("methodName", methodName);
                if (caller != null && "se.krka.kahlua.integration.expose.caller.MethodCaller".equals(caller.getClass().getName())) {
                    Field methodField = caller.getClass().getDeclaredField("method");
                    methodField.setAccessible(true);
                    Method m = (Method) methodField.get(caller);
                    invTbl.rawset("declaringClass", m.getDeclaringClass().getName());
                }
                Object debugData = invokerClass.getMethod("getMethodDebugData").invoke(inv);
                invTbl.rawset("methodDebugData", debugData != null ? debugData.toString() : "");
                invokersTbl.rawset(Double.valueOf(i + 1), invTbl);
            }
            out.rawset("invokers", invokersTbl);
        } catch (Exception e) {
            out.rawset("unwrapError", e.getMessage());
        }
    }
}
