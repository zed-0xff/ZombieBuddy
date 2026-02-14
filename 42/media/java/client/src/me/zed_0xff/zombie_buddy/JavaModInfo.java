package me.zed_0xff.zombie_buddy;

import java.io.File;

/**
 * Represents Java mod information parsed from a mod.info file.
 * Contains JAR file path and package name for a Java mod.
 */
public record JavaModInfo(
    /** The directory containing the mod.info file */
    File modDirectory,
    /** The mod.info file itself */
    File modInfoFile,
    /** JAR file path relative to modDirectory (from javaJarFile entry) */
    String jarFilePath,
    /** Package name (from javaPkgName entry) */
    String javaPkgName,
    /** Minimum ZombieBuddy version required (from zbVersionMin entry) */
    String zbVersionMin,
    /** Maximum ZombieBuddy version required (from zbVersionMax entry) */
    String zbVersionMax
) {
    /**
     * Creates a JavaModInfo with null values.
     */
    public JavaModInfo(File modDirectory, File modInfoFile) {
        this(modDirectory, modInfoFile, null, null, null, null);
    }
    
    /**
     * Checks if this mod has a JAR file specified.
     */
    public boolean hasJarFile() {
        return !isEmpty(jarFilePath);
    }

    /**
     * Gets the JAR file as a File object relative to the mod directory.
     */
    public File getJarFileAsFile() {
        if (isEmpty(jarFilePath)) {
            return null;
        }
        return new File(modDirectory, jarFilePath);
    }
    
    /**
     * Internal record to hold parsed values from a mod.info file.
     */
    private record ParsedValues(String jarFilePath, String javaPkgName, String zbVersionMin, String zbVersionMax) {}

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static String trimmedValue(String line) {
        return line.split("=", 2)[1].trim();
    }

    private static String versionMismatchMessage(String minVersion, String maxVersion) {
        return "(requires: " + (minVersion != null ? minVersion : "any") + " to "
            + (maxVersion != null ? maxVersion : "any") + ", ZombieBuddy version: " + ZombieBuddy.getVersion() + ")";
    }

    /**
     * Validates parsed values and creates JavaModInfo, or null if invalid.
     * @param logMissingJarFile if true, log when javaJarFile is missing (parse); if false, silent (parseMerged)
     */
    private static JavaModInfo validateAndCreate(ParsedValues parsed, File modInfoFile, File modDirectory, boolean logMissingJarFile) {
        String jarFilePath = parsed.jarFilePath();
        String javaPkgName = parsed.javaPkgName();
        String zbVersionMin = parsed.zbVersionMin();
        String zbVersionMax = parsed.zbVersionMax();

        if (isEmpty(jarFilePath)) {
            if (logMissingJarFile && Loader.g_verbosity > 0) {
                Logger.info("No javaJarFile entry found in mod.info, skipping Java mod: " + modInfoFile);
            }
            return null;
        }
        if ( Utils.isServer() ) {
            if (jarFilePath.contains("media/java/client/")) {
                Logger.error("Skipping client-only mod: " + modInfoFile);
                return null;
            }
        } else {
            if (jarFilePath.contains("media/java/server/")) {
                Logger.error("Skipping server-only mod: " + modInfoFile);
                return null;
            }
        }
        if (isEmpty(javaPkgName)) {
            Logger.error("Error! Mod has javaJarFile but missing required javaPkgName: " + modInfoFile);
            return null;
        }
        if (!isVersionInRange(ZombieBuddy.getVersion(), zbVersionMin, zbVersionMax)) {
            Logger.error("Skipping mod due to version mismatch: " + modInfoFile + " " + versionMismatchMessage(zbVersionMin, zbVersionMax));
            return null;
        }
        return new JavaModInfo(modDirectory, modInfoFile, jarFilePath, javaPkgName, zbVersionMin, zbVersionMax);
    }

    /**
     * Parses a mod.info file and extracts jarFile and javaPkgName values.
     * Returns null if the file doesn't exist, cannot be read, or parsing fails.
     *
     * @param modInfoFile The mod.info file to parse
     * @return ParsedValues containing jarFile and javaPkgName, or null if parsing fails
     */
    private static ParsedValues parseModInfoFile(File modInfoFile) {
        if (modInfoFile == null || !modInfoFile.exists() || !modInfoFile.isFile()) {
            return null;
        }

        String jarFilePath = null;
        String javaPkgName = null;
        String zbVersionMin = null;
        String zbVersionMax = null;

        try (var reader = new java.io.BufferedReader(new java.io.FileReader(modInfoFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || !line.contains("=")) {
                    continue;
                }
                String lowerLine = line.toLowerCase();
                String value = trimmedValue(line);

                if (lowerLine.startsWith("javajarfile=")) {
                    if (jarFilePath != null) {
                        Logger.error("Warning! Multiple javaJarFile entries found, only the first one will be used: " + modInfoFile);
                        continue;
                    }
                    if (!value.isEmpty()) {
                        if (!value.endsWith(".jar")) {
                            Logger.error("Error! javaJarFile entry must end with \".jar\": " + value);
                            continue;
                        }
                        jarFilePath = value;
                    }
                } else if (lowerLine.startsWith("javapkgname=")) {
                    if (javaPkgName != null) {
                        Logger.error("Warning! Multiple javaPkgName entries found, only the first one will be used: " + modInfoFile);
                        continue;
                    }
                    if (!value.isEmpty()) {
                        javaPkgName = value;
                    }
                } else if (lowerLine.startsWith("zbversionmin=")) {
                    zbVersionMin = value;
                } else if (lowerLine.startsWith("zbversionmax=")) {
                    zbVersionMax = value;
                }
            }
        } catch (Exception e) {
            Logger.error("error reading " + modInfoFile + ": " + e);
            return null;
        }

        return new ParsedValues(jarFilePath, javaPkgName, zbVersionMin, zbVersionMax);
    }
    
    /**
     * Parses a mod.info file and returns a JavaModInfo object.
     * Returns null if the mod.info file doesn't exist or cannot be read.
     * 
     * @param modDirectory The directory containing the mod.info file
     * @return JavaModInfo object, or null if the file doesn't exist or cannot be parsed
     */
    public static JavaModInfo parse(File modDirectory) {
        if (modDirectory == null || !modDirectory.isDirectory()) {
            if (Loader.g_verbosity > 0) {
                Logger.info("Mod directory does not exist or is not a directory: " + modDirectory);
            }
            return null;
        }
        
        File modInfoFile = new File(modDirectory, "mod.info");
        ParsedValues parsed = parseModInfoFile(modInfoFile);
        if (parsed == null) {
            if (Loader.g_verbosity > 0) {
                Logger.info("mod.info not found or failed to parse in directory: " + modDirectory);
            }
            return null;
        }
        return validateAndCreate(parsed, modInfoFile, modDirectory, true);
    }
    
    /**
     * Parses a mod.info file from a directory path string.
     * 
     * @param modDirectoryPath The path to the directory containing the mod.info file
     * @return JavaModInfo object, or null if the file doesn't exist or cannot be parsed
     */
    public static JavaModInfo parse(String modDirectoryPath) {
        if (modDirectoryPath == null || modDirectoryPath.isEmpty()) {
            return null;
        }
        return parse(new File(modDirectoryPath));
    }
    
    /**
     * Parses mod.info from commonDir and uses versionDir to locate the JAR file.
     * This is useful when mod.info is in commonDir but the JAR file is in versionDir.
     * Only reads mod.info from commonDir, not from versionDir.
     * 
     * @param commonDir The common directory containing mod.info
     * @param versionDir The version directory where the JAR file may be located
     * @return JavaModInfo object, or null if mod.info doesn't exist or cannot be parsed
     */
    public static JavaModInfo parseMerged(File commonDir, File versionDir) {
        if (commonDir == null || !commonDir.isDirectory()) {
            return null;
        }
        if (versionDir == null || !versionDir.isDirectory()) {
            return null;
        }
        
        File commonModInfoFile = new File(commonDir, "mod.info");
        ParsedValues commonParsed = parseModInfoFile(commonModInfoFile);
        
        if (commonParsed == null) {
            return null;
        }
        // Check if JAR exists in versionDir (using the same relative path from mod.info)
        String jarFilePath = commonParsed.jarFilePath();
        File jarInVersion = new File(versionDir, jarFilePath);
        File modDirectory = jarInVersion.exists() ? versionDir : commonDir;
        return validateAndCreate(commonParsed, commonModInfoFile, modDirectory, false);
    }
    
    /**
     * Parses mod.info from commonDir path and uses versionDir path to locate the JAR file.
     * This is useful when mod.info is in commonDir but the JAR file is in versionDir.
     * Only reads mod.info from commonDir, not from versionDir.
     * 
     * @param commonDirPath The path to the common directory containing mod.info
     * @param versionDirPath The path to the version directory where the JAR file may be located
     * @return JavaModInfo object, or null if mod.info doesn't exist or cannot be parsed
     */
    public static JavaModInfo parseMerged(String commonDirPath, String versionDirPath) {
        if (commonDirPath == null || commonDirPath.isEmpty()) {
            return null;
        }
        if (versionDirPath == null || versionDirPath.isEmpty()) {
            return null;
        }
        return parseMerged(new File(commonDirPath), new File(versionDirPath));
    }

    /**
     * Checks if a version is within the specified minimum and maximum range.
     * 
     * @param currentVersion The current version to check
     * @param minVersion The minimum version allowed (inclusive), or null if no minimum
     * @param maxVersion The maximum version allowed (inclusive), or null if no maximum
     * @return true if the version is in range, false otherwise
     */
    static boolean isVersionInRange(String currentVersion, String minVersion, String maxVersion) {
        if (currentVersion == null || currentVersion.equals("unknown")) {
            // If we don't know our own version, we can't really check.
            // But usually this means we are in development mode.
            return true;
        }
        
        if (minVersion != null && !minVersion.isEmpty()) {
            if (Utils.compareVersions(currentVersion, minVersion) < 0) {
                return false;
            }
        }
        
        if (maxVersion != null && !maxVersion.isEmpty()) {
            if (Utils.compareVersions(currentVersion, maxVersion) > 0) {
                return false;
            }
        }
        
        return true;
    }
}

