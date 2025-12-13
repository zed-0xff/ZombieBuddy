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
    String jarFile,
    /** Package name (from javaPkgName entry) */
    String javaPkgName
) {
    /**
     * Creates a JavaModInfo with null values.
     */
    public JavaModInfo(File modDirectory, File modInfoFile) {
        this(modDirectory, modInfoFile, null, null);
    }
    
    /**
     * Checks if this mod has a JAR file specified.
     */
    public boolean hasJarFile() {
        return jarFile != null && !jarFile.isEmpty();
    }
    
    /**
     * Gets the JAR file as a File object relative to the mod directory.
     */
    public File getJarFileAsFile() {
        if (jarFile == null || jarFile.isEmpty()) {
            return null;
        }
        return new File(modDirectory, jarFile);
    }
    
    /**
     * Gets the fully qualified Main class name.
     * The Main class is always named "Main" in the package specified by javaPkgName.
     * javaPkgName is mandatory when jarFile is present, so this should never return null
     * for a valid Java mod.
     */
    public String getMainClassName() {
        if (javaPkgName == null || javaPkgName.isEmpty()) {
            return null;
        }
        return javaPkgName + ".Main";
    }
    
    /**
     * Internal record to hold parsed values from a mod.info file.
     */
    private record ParsedValues(String jarFile, String javaPkgName) {}
    
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
        
        String jarFile = null;
        String javaPkgName = null;
        
        try (var reader = new java.io.BufferedReader(new java.io.FileReader(modInfoFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 13) {
                    continue;
                }
                
                String lowerLine = line.toLowerCase();
                if (lowerLine.startsWith("javajarfile=")) {
                    if (jarFile != null) {
                        System.err.println("[ZB] Warning! Multiple javaJarFile entries found, only the first one will be used: " + modInfoFile);
                        continue;
                    }
                    String jarPath = line.split("=", 2)[1].trim();
                    if (!jarPath.isEmpty()) {
                        if (!jarPath.endsWith(".jar")) {
                            System.err.println("[ZB] Error! javaJarFile entry must end with \".jar\": " + jarPath);
                            continue;
                        }
                        jarFile = jarPath;
                    }
                } else if (lowerLine.startsWith("javapkgname=")) {
                    if (javaPkgName != null) {
                        System.err.println("[ZB] Warning! Multiple javaPkgName entries found, only the first one will be used: " + modInfoFile);
                        continue;
                    }
                    String pkgName = line.split("=", 2)[1].trim();
                    if (!pkgName.isEmpty()) {
                        javaPkgName = pkgName;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ZB] error reading " + modInfoFile + ": " + e);
            return null;
        }
        
        return new ParsedValues(jarFile, javaPkgName);
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
            return null;
        }
        
        File modInfoFile = new File(modDirectory, "mod.info");
        ParsedValues parsed = parseModInfoFile(modInfoFile);
        if (parsed == null) {
            return null;
        }
        
        String jarFile = parsed.jarFile();
        String javaPkgName = parsed.javaPkgName();
        
        // Both javaJarFile and javaPkgName are required - return null if either is missing or invalid
        if (jarFile == null || jarFile.isEmpty()) {
            // No Java mod configuration found, return null
            return null;
        }
        
        if (javaPkgName == null || javaPkgName.isEmpty()) {
            System.err.println("[ZB] Error! Mod has javaJarFile but missing required javaPkgName: " + modInfoFile);
            return null;
        }
        
        return new JavaModInfo(modDirectory, modInfoFile, jarFile, javaPkgName);
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
        
        // Only read mod.info from commonDir
        if (commonParsed == null) {
            return null;
        }
        
        String jarFile = commonParsed.jarFile();
        String javaPkgName = commonParsed.javaPkgName();
        
        // Both javaJarFile and javaPkgName are required - return null if either is missing or invalid
        if (jarFile == null || jarFile.isEmpty()) {
            return null;
        }
        
        if (javaPkgName == null || javaPkgName.isEmpty()) {
            System.err.println("[ZB] Error! Mod has javaJarFile but missing required javaPkgName: " + commonModInfoFile);
            return null;
        }
        
        // Check if JAR exists in versionDir (using the same relative path from mod.info)
        // If it does, use versionDir as modDirectory; otherwise use commonDir
        File jarInVersion = new File(versionDir, jarFile);
        File modDirectory = jarInVersion.exists() ? versionDir : commonDir;
        
        return new JavaModInfo(modDirectory, commonModInfoFile, jarFile, javaPkgName);
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
}

