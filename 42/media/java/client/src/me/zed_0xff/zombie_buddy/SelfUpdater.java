package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Handles replacing the running ZombieBuddy JAR with a newer version
 * (e.g. from the mods directory). Supports immediate replace or deferred
 * replace via a .new file when the current JAR is locked (e.g. on Windows).
 * Also handles verification of JAR signatures and certificates.
 */
public final class SelfUpdater {
    private SelfUpdater() {}

    private static String pendingNewVersion = null;

    /**
     * Expected SHA-256 fingerprint of the ZombieBuddy signing certificate.
     * Used to verify that a JAR is signed by the trusted ZombieBuddy key.
     */
    private static final byte[] EXPECTED_FINGERPRINT = {
        (byte)0xA7, (byte)0x75, (byte)0x10, (byte)0x1B, (byte)0xFB, (byte)0x6C, (byte)0x33, (byte)0xA9,
        (byte)0x2C, (byte)0xDF, (byte)0x25, (byte)0x20, (byte)0xAC, (byte)0x8D, (byte)0x02, (byte)0x95,
        (byte)0xCE, (byte)0xBF, (byte)0x89, (byte)0x0C, (byte)0x84, (byte)0x05, (byte)0x97, (byte)0x37,
        (byte)0x7F, (byte)0xD0, (byte)0x9B, (byte)0x17, (byte)0xD0, (byte)0xEA, (byte)0xDD, (byte)0x97
    };

    /**
     * Returns the version string of a pending update (set when a newer JAR
     * was found and replace was scheduled). Null if no update is pending.
     */
    public static String getNewVersion() {
        return pendingNewVersion;
    }

    /**
     * Builds the exclusion reason suffix when ZombieBuddy is excluded from normal mod loading
     * (loaded as Java agent). Reads version from manifest, optionally checks and performs
     * self-update, and returns a string like " (version 1.2.3)" or " (version 1.2.3) -> updating to 1.2.4".
     *
     * @param jarFile the ZombieBuddy JAR file from the mod directory (may be null)
     * @return suffix to append to the exclusion reason (e.g. " (version 1.0.0)" or " (path not found)")
     */
    public static String getExclusionReasonSuffix(File jarFile) {
        if (jarFile == null) {
            return " (null not found)";
        }
        if (!jarFile.exists()) {
            return " (" + jarFile.getAbsolutePath() + " not found)";
        }

        StringBuilder sb = new StringBuilder();
        Manifest manifest = getJarManifest(jarFile);
        if (manifest != null) {
            String manifestVersion = manifest.getMainAttributes().getValue("Implementation-Version");
            if (manifestVersion != null) {
                sb.append(" (version ").append(manifestVersion).append(")");
            }
        }

        File currentJarFile = Utils.getCurrentJarFile();
        String currentVersion = ZombieBuddy.getVersion();
        int verbosity = Loader.g_verbosity;
        if (checkAndUpdateIfNewer(jarFile, currentJarFile, currentVersion, verbosity)) {
            String newVer = getNewVersion();
            if (newVer != null) {
                sb.append(" -> updating to ").append(newVer);
            }
        }
        return sb.toString();
    }

    /**
     * Replaces the current JAR with the new one and records the new version.
     * If the current JAR cannot be replaced (e.g. locked), copies the new JAR
     * to a .new file for deferred update on next launch.
     *
     * @param currentJarFile the JAR file that is currently running (from getCurrentJarFile())
     * @param newJarFile     the new JAR file to install
     * @param newVersion    the Implementation-Version of the new JAR (for getNewVersion())
     */
    public static void performUpdate(File currentJarFile, File newJarFile, String newVersion) {
        if (currentJarFile == null) {
            return;
        }
        pendingNewVersion = newVersion;
        Logger.info("replacing " + currentJarFile + " with " + newJarFile);
        try {
            File backupFile = new File(currentJarFile.getAbsolutePath() + ".bak");
            if (currentJarFile.exists()) {
                try {
                    Files.move(currentJarFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Logger.info("Renamed existing JAR to " + backupFile);
                } catch (Exception e) {
                    Logger.info("Could not rename existing JAR (may be locked): " + e.getMessage());
                }
            }

            Files.copy(newJarFile.toPath(), currentJarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Logger.info("Successfully replaced JAR file");
        } catch (Exception e) {
            Logger.error("Error replacing JAR file: " + e.getMessage());
            Logger.error("JAR may be locked (e.g., on Windows). Copying to .new file for deferred update...");
            try {
                File deferredFile = new File(currentJarFile.getAbsolutePath() + ".new");
                Files.copy(newJarFile.toPath(), deferredFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Logger.info("Copied new JAR to " + deferredFile + " - update will be applied on next game launch");
            } catch (Exception e2) {
                Logger.error("Error copying to .new file: " + e2.getMessage());
                e2.printStackTrace();
            }
            e.printStackTrace();
        }
    }

    /**
     * Checks if a JAR file is signed with the expected certificate and has a newer version,
     * and if so, performs the update. This is the main entry point for self-update checks.
     *
     * @param jarFile        the JAR file to check (from mod directory)
     * @param currentJarFile the currently running JAR file
     * @param currentVersion the current ZombieBuddy version (from ZombieBuddy.getVersion())
     * @param verbosity      verbosity level (0 = quiet, higher = more logging)
     * @return true if an update was performed or scheduled, false otherwise
     */
    public static boolean checkAndUpdateIfNewer(File jarFile, File currentJarFile, String currentVersion, int verbosity) {
        if (jarFile == null || !jarFile.exists()) {
            return false;
        }

        try {
            Certificate[] certs = verifyJarAndGetCerts(jarFile);
            if (certs == null || certs.length == 0) {
                return false;
            }

            if (verbosity > 0) {
                Logger.info("" + jarFile + " is signed with " + certs.length + " certificate(s)");
            }

            for (int certIdx = 0; certIdx < certs.length; certIdx++) {
                if (certs[certIdx] instanceof X509Certificate) {
                    X509Certificate x509Cert = (X509Certificate) certs[certIdx];
                    byte[] fingerprint = getCertFingerprint(x509Cert, certIdx + 1, verbosity);
                    if (fingerprint != null && java.util.Arrays.equals(fingerprint, EXPECTED_FINGERPRINT)) {
                        Manifest manifest = getJarManifest(jarFile);
                        if (manifest != null) {
                            String manifestVersion = manifest.getMainAttributes().getValue("Implementation-Version");
                            if (manifestVersion != null) {
                                if (Utils.isVersionNewer(manifestVersion, currentVersion)) {
                                    performUpdate(currentJarFile, jarFile, manifestVersion);
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logger.error("Error verifying JAR signature: " + e.getMessage());
        }
        return false;
    }

    /**
     * Verifies a JAR file's signature and returns its certificates.
     * Throws SecurityException if the JAR is not signed.
     *
     * @param jarPath the JAR file to verify
     * @return array of certificates from signed entries, or null if no manifest
     * @throws Exception if verification fails or JAR cannot be read
     */
    public static Certificate[] verifyJarAndGetCerts(File jarPath) throws Exception {
        Manifest mf = getJarManifest(jarPath);
        if (mf == null) {
            return null;
        }

        try (JarFile jar = new JarFile(jarPath, true)) {
            Certificate[] signer = null;

            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;

                try (InputStream is = jar.getInputStream(e)) {
                    is.readAllBytes();
                }

                Certificate[] certs = e.getCertificates();
                if (certs != null) {
                    signer = certs;
                }
            }

            if (signer == null) {
                throw new SecurityException("No signed entries found");
            }

            return signer;
        }
    }

    /**
     * Gets the manifest from a JAR file.
     *
     * @param jarFile the JAR file
     * @return the manifest, or null if not found or error
     */
    public static Manifest getJarManifest(File jarFile) {
        if (jarFile == null) {
            return null;
        }
        try (JarFile jar = new JarFile(jarFile, true)) {
            return jar.getManifest();
        } catch (Exception e) {
            Logger.error("Error getting JAR manifest: " + e);
            return null;
        }
    }

    /**
     * Computes and optionally prints the SHA-256 fingerprint of an X.509 certificate.
     *
     * @param cert       the certificate
     * @param certNumber certificate number (for logging)
     * @param verbosity  verbosity level (0 = quiet)
     * @return SHA-256 fingerprint bytes, or null on error
     */
    private static byte[] getCertFingerprint(X509Certificate cert, int certNumber, int verbosity) {
        if (verbosity > 0) {
            Logger.info("  Certificate " + certNumber + ":");
            Logger.info("    Subject: " + cert.getSubjectX500Principal().getName());
            Logger.info("    Issuer: " + cert.getIssuerX500Principal().getName());
            Logger.info("    Serial Number: " + cert.getSerialNumber().toString(16).toUpperCase());
            Logger.info("    Valid From: " + cert.getNotBefore());
            Logger.info("    Valid Until: " + cert.getNotAfter());
        }

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] sha256Bytes = sha256.digest(cert.getEncoded());
            if (verbosity > 0) {
                String sha256Fingerprint = Utils.bytesToHex(sha256Bytes);
                Logger.info("    SHA-256 Fingerprint: " + sha256Fingerprint);
            }
            return sha256Bytes;
        } catch (Exception e) {
            Logger.error("    Error computing certificate fingerprints: " + e.getMessage());
        }
        return null;
    }
}
