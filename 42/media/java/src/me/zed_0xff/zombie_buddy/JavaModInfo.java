package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents Java mod information parsed from a mod.info file.
 * Contains JAR file paths and main class names for a Java mod.
 */
public record JavaModInfo(
    /** The directory containing the mod.info file */
    File modDirectory,
    /** The mod.info file itself */
    File modInfoFile,
    /** List of JAR file paths relative to modDirectory (from javaJarFile entries) */
    List<String> jarFiles,
    /** List of main class names (from javaMainClass entries) */
    List<String> mainClasses
) {
    /**
     * Creates a JavaModInfo with empty lists.
     */
    public JavaModInfo(File modDirectory, File modInfoFile) {
        this(modDirectory, modInfoFile, new ArrayList<>(), new ArrayList<>());
    }
    
    /**
     * Returns an unmodifiable view of the jarFiles list.
     */
    @Override
    public List<String> jarFiles() {
        return Collections.unmodifiableList(jarFiles);
    }
    
    /**
     * Returns an unmodifiable view of the mainClasses list.
     */
    @Override
    public List<String> mainClasses() {
        return Collections.unmodifiableList(mainClasses);
    }
    
    /**
     * Checks if this mod has any JAR files specified.
     */
    public boolean hasJarFiles() {
        return !jarFiles.isEmpty();
    }
    
    /**
     * Checks if this mod has any main classes specified.
     */
    public boolean hasMainClasses() {
        return !mainClasses.isEmpty();
    }
    
    /**
     * Gets a JAR file as a File object relative to the mod directory.
     */
    public File getJarFile(int index) {
        if (index < 0 || index >= jarFiles.size()) {
            throw new IndexOutOfBoundsException("JAR file index out of bounds: " + index);
        }
        return new File(modDirectory, jarFiles.get(index));
    }
    
    /**
     * Gets all JAR files as File objects relative to the mod directory.
     */
    public List<File> getJarFilesAsFiles() {
        List<File> files = new ArrayList<>();
        for (String jarPath : jarFiles) {
            files.add(new File(modDirectory, jarPath));
        }
        return files;
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
        if (!modInfoFile.exists() || !modInfoFile.isFile()) {
            return null;
        }
        
        List<String> jarFiles = new ArrayList<>();
        List<String> mainClasses = new ArrayList<>();
        
        try (var reader = new java.io.BufferedReader(new java.io.FileReader(modInfoFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 15) {
                    continue;
                }
                
                String prefix = line.substring(0, 14).toLowerCase();
                if (prefix.startsWith("javajarfile=")) {
                    String jarPath = line.split("=", 2)[1].trim();
                    if (!jarPath.isEmpty()) {
                        if (!jarPath.endsWith(".jar")) {
                            System.err.println("[ZB] Error! javaJarFile entry must end with \".jar\": " + jarPath);
                            continue;
                        }
                        jarFiles.add(jarPath);
                    }
                } else if (prefix.startsWith("javamainclass=")) {
                    String mainClass = line.split("=", 2)[1].trim();
                    if (!mainClass.isEmpty()) {
                        mainClasses.add(mainClass);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ZB] error reading " + modInfoFile + ": " + e);
            return null;
        }
        
        return new JavaModInfo(modDirectory, modInfoFile, jarFiles, mainClasses);
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
}

