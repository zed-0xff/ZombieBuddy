package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Utils {
    private Utils() {}

    /** Converts a canonical/binary class name (dots) to an ASM internal name (slashes). */
    public static String toInternalName(String className) { return className.replace('.', '/'); }

    /** Converts a class to its ASM internal name. */
    public static String toInternalName(Class<?> cls) { return cls.getName().replace('.', '/'); }

    /** Converts an ASM internal name (slashes) to a canonical class name (dots). */
    public static String toCanonicalName(String internalName) {
        if (isBlank(internalName)) return internalName;

        //   "Lnet/bytebuddy/asm/Advice$NoExceptionHandler;"
        // => "net/bytebuddy/asm/Advice$NoExceptionHandler"
        if (internalName.startsWith("L") && internalName.endsWith(";")) {
            internalName = internalName.substring(1, internalName.length() - 1);
        }
        return internalName.replace('/', '.'); // => "net.bytebuddy.asm.Advice$NoExceptionHandler"
    }

    private static final Pattern PRERELEASE_PATTERN = Pattern.compile("^([a-z]+)(\\d*)");

    static <T> T firstNonNull(Supplier<T>... suppliers) {
        for (Supplier<T> s : suppliers) {
            T v = s.get();
            if (v != null) return v;
        }
        return null;
    }

    private static String _cacheDir = null;
    public static String getCacheDir() {
        if (_cacheDir == null) {
            try {
                _cacheDir = (String) Reflect
                    .fastcall(() -> Reflect.on("zombie.ZomboidFileSystem").getInstanceBoundMethodHandle(String.class, "getCacheDir"))
                    .invokeExact();
            } catch (Throwable t) {
                Logger.printStackTrace(t);
            }
        }
        return _cacheDir;
    }

    private static Path _cachePath;
    public static Path getCachePath() {
        if (_cachePath == null) {
            String cacheDir = getCacheDir();
            if (cacheDir != null) {
                _cachePath = Path.of(cacheDir);
            }
        }
        return _cachePath;
    }

    public static boolean isSameFile(Path p1, Path p2) {
        if (p1 == null || p2 == null) return false;
        try {
            return Files.isSameFile(p1, p2);
        } catch (IOException e) {
            Logger.debug("Error comparing files " + p1 + " and " + p2 + ": " + e);
            return false;
        }
    }

    public static boolean isClient() {
        try {
            return (boolean) Reflect
                .fastcall(() -> Reflect.on("zombie.Lua.LuaManager$GlobalObject").getMethodHandle(boolean.class, "isClient"))
                .invokeExact();
        } catch (Throwable t) {
            Logger.printStackTrace(t);
            return false;
        }
    }

    public static boolean isServer() {
        try {
            return (boolean) Reflect
                .fastcall(() -> Reflect.on("zombie.Lua.LuaManager$GlobalObject").getMethodHandle(boolean.class, "isServer"))
                .invokeExact();
        } catch (Throwable t) {
            Logger.printStackTrace(t);
            return false;
        }
    }

    public static boolean isMac() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }

    public static boolean isHiRes() {
        try {
            int width = (int) Reflect
                .fastcall(() -> Reflect.on("zombie.core.Core").getInstanceBoundMethodHandle(int.class, "getScreenWidth"))
                .invokeExact();

            return width > 2000;
        } catch (Throwable t) {
            Logger.printStackTrace(t);
            return false;
        }
    }

    public static boolean isBlank(Object obj) {
        if (obj == null) return true;
        if (obj instanceof String s)        return isBlank(s);
        if (obj instanceof Path p)          return isBlank(p);
        if (obj instanceof Collection<?> c) return isBlank(c);
        if (obj instanceof Map<?, ?> m)     return isBlank(m);
        return false;
    }

    public static boolean isBlank(String str) {
        return str == null || str.isEmpty();
    }

    public static boolean isBlank(Path path) {
        return path == null || path.toString().isEmpty();
    }

    public static boolean isBlank(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    public static boolean isBlank(Map<?, ?> map) {
        return map == null || map.isEmpty();
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
        boolean release1 = Utils.isBlank(p1);
        boolean release2 = Utils.isBlank(p2);
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
     * Writes {@code content} to {@code target} atomically: writes to a sibling temp file first,
     * then renames. Readers see either the old complete file or the new complete file, never a
     * partial write. Falls back to a non-atomic move if the OS/filesystem doesn't support it.
     */
    public static void writeFileAtomic(Path target, String content, Charset charset) throws IOException {
        Path dir = target.getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, charset);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
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
    public static String sha256Hex(Path path) {
        if (path == null || !Files.isRegularFile(path)) return null;
        try (var in = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
            return bytesToHex(md.digest(), "%02x", "");
        } catch (Exception e) {
            Logger.error("Could not hash file " + path + ": " + e);
            return null;
        }
    }

    /**
     * Returns the JAR file that contains the currently running ZombieBuddy code.
     *
     * @return the JAR path, or null if not found or not running from a JAR
     */
    public static Path getCurrentJarPath() {
        try {
            java.security.CodeSource codeSource = Utils.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                java.net.URL location = codeSource.getLocation();
                if (location != null) {
                    java.net.URI uri = location.toURI();
                    Path jarPath = Path.of(uri);
                    if (Files.exists(jarPath) && jarPath.getFileName().toString().endsWith(".jar")) {
                        return jarPath;
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
}
