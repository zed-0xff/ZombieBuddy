package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ClassLoader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.classgraph.*;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

import zombie.core.Core;
import zombie.core.GameVersion;
import zombie.gameStates.ChooseGameInfo;

public class Loader {
    public static Instrumentation g_instrumentation;
    public static int g_verbosity = 0;

    private static final String APPROVALS_FILE_NAME = "java_mod_approvals.txt";
    private static final String POLICY_PROMPT       = "prompt";
    private static final String POLICY_DENY_NEW     = "deny-new";
    private static final String POLICY_ALLOW_ALL    = "allow-all";
    private static final Set<String> VALID_POLICIES = Set.of(POLICY_PROMPT, POLICY_DENY_NEW, POLICY_ALLOW_ALL);

    // Write-once policy: must be set during Agent.premain, before any other Java
    // mod is on the classpath. Subsequent set attempts are logged and ignored,
    // so a later-loading Java mod cannot call Loader.setPolicy("allow-all").
    // (Reflection/bytecode-redefinition can still bypass this — same threat
    // model as the approvals file; see ~/.zombie_buddy/ comment.)
    private static volatile String g_jarPolicy = POLICY_PROMPT;
    private static volatile boolean g_jarPolicyLocked = false;

    public static synchronized void setPolicy(String value) {
        if (g_jarPolicyLocked) {
            Logger.warn("Ignoring attempt to change policy (already locked at '" + g_jarPolicy + "')");
            return;
        }
        String v = value == null ? "" : value.trim().toLowerCase();
        if (!VALID_POLICIES.contains(v)) {
            Logger.warn("Invalid policy '" + value + "', keeping '" + g_jarPolicy + "'. Valid: " + VALID_POLICIES);
            g_jarPolicyLocked = true;
            return;
        }
        g_jarPolicy = v;
        g_jarPolicyLocked = true;
        Logger.info("Policy set to '" + g_jarPolicy + "' (locked)");
    }

    public static String getPolicy() {
        return g_jarPolicy;
    }

    static Set<String> g_known_classes = new HashSet<>();
    static Set<File> g_known_jars = new HashSet<>();

    // Decision records use the composite key "<modId>|<sha256>" and a value
    // of "yes" or "no". Same encoding for both the persistent approvals file
    // and the in-memory session map, so matching is DRY. A JAR with a new
    // hash produces a new key → the user is re-prompted, while old records
    // for previous hashes are preserved (rollback = no re-prompt).
    private static final String KEY_SEP = "|";
    static final String DECISION_YES = "yes";
    static final String DECISION_NO  = "no";
    private static final Map<String, String> g_sessionDecisions = new HashMap<>();

    private enum Approval { ALLOW_PERSIST, ALLOW_SESSION, DENY_PERSIST, DENY_SESSION }

    private static String recordKey(String modId, String hash) {
        return modId + KEY_SEP + hash;
    }

    /**
     * Per-modId load/decision snapshot captured at loadMods() time so Lua
     * (and other callers) can display "loaded / blocked / session / persisted"
     * next to each mod in the UI. Keyed by modId; for multi-dir B42 mods the
     * version-dir entry (which typically carries the JAR) overwrites the
     * common-dir one.
     */
    static final class JavaModLoadState {
        final boolean loaded;     // true if the JAR was loaded this run
        final String reason;      // "loaded" or a short skip reason
        final String sha256;      // JAR sha256, null if no JAR
        final String decision;    // DECISION_YES / DECISION_NO / null
        final boolean persisted;  // true if decision came from the file (vs session map)

        JavaModLoadState(boolean loaded, String reason, String sha256, String decision, boolean persisted) {
            this.loaded = loaded;
            this.reason = reason;
            this.sha256 = sha256;
            this.decision = decision;
            this.persisted = persisted;
        }
    }

    private static final Map<String, JavaModLoadState> g_jarLoadStatus = new HashMap<>();

    static JavaModLoadState getJarLoadState(String modId) {
        return modId == null ? null : g_jarLoadStatus.get(modId);
    }

    // Key for grouping patches by class+method
    private static record PatchTarget(String className, String methodName) {}
    
    // Annotation pattern constants for reuse
    private static final Set<String> ADVICE_ANNOTATION_PATTERNS = Set.of("Advice$OnMethodEnter", "Advice$OnMethodExit");
    private static final Set<String> RUNTIME_TYPE_PATTERNS = Set.of("RuntimeType");
    private static final Set<String> METHOD_DELEGATION_SPECIAL_ANNOTATIONS = Set.of("This", "SuperCall", "SuperMethod");
    private static final Set<String> ALL_ARGUMENTS_PATTERNS = Set.of("Advice$AllArguments");
    
    /**
     * Checks if a method has any annotation matching the given patterns.
     * @param method The method to check
     * @param annotationPatterns Set of annotation name patterns to look for
     * @return true if the method has any matching annotation
     */
    private static boolean hasAnnotation(Method method, Set<String> annotationPatterns) {
        for (java.lang.annotation.Annotation ann : method.getAnnotations()) {
            String annType = ann.annotationType().getName();
            for (String pattern : annotationPatterns) {
                if (annType.contains(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Finds the first method in a class that has any of the specified annotation patterns.
     * @param clazz The class to search
     * @param annotationPatterns Set of annotation name patterns to look for
     * @return The first matching method, or null if none found
     */
    private static Method findMethodWithAnnotation(Class<?> clazz, Set<String> annotationPatterns) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (hasAnnotation(method, annotationPatterns)) {
                return method;
            }
        }
        return null;
    }
    
    /**
     * Checks if a method has a parameter annotated with any of the specified annotation patterns.
     * @param method The method to check
     * @param annotationPatterns Set of annotation name patterns to look for
     * @return true if any parameter has a matching annotation
     */
    private static boolean hasParameterAnnotation(Method method, Set<String> annotationPatterns) {
        java.lang.annotation.Annotation[][] paramAnns = method.getParameterAnnotations();
        for (java.lang.annotation.Annotation[] paramAnn : paramAnns) {
            for (java.lang.annotation.Annotation ann : paramAnn) {
                String annType = ann.annotationType().getName();
                for (String pattern : annotationPatterns) {
                    if (annType.contains(pattern)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Infers method/constructor signature from a patch method's parameter annotations.
     * @param patchMethod The patch method to analyze
     * @param specialAnnotationPatterns Set of annotation name patterns to ignore (e.g., "This", "SuperCall", "Return", "Local")
     * @return List of parameter types in order, or null if signature cannot be inferred
     */
    private static List<Class<?>> inferSignatureFromMethod(Method patchMethod, Set<String> specialAnnotationPatterns) {
        java.lang.annotation.Annotation[][] paramAnns = patchMethod.getParameterAnnotations();
        Class<?>[] paramTypes = patchMethod.getParameterTypes();
        java.util.Map<Integer, Class<?>> argumentMap = new java.util.HashMap<>();
        
        for (int i = 0; i < paramAnns.length; i++) {
            boolean isSpecial = false;
            int argumentIndex = -1;
            
            for (java.lang.annotation.Annotation ann : paramAnns[i]) {
                String annType = ann.annotationType().getName();
                
                // Check if this is a special annotation we should ignore
                for (String pattern : specialAnnotationPatterns) {
                    if (annType.contains(pattern)) {
                        isSpecial = true;
                        break;
                    }
                }
                if (isSpecial) break;
                
                // Check for @Argument annotation
                if (annType.contains("Argument")) {
                    try {
                        java.lang.reflect.Method valueMethod = ann.annotationType().getMethod("value");
                        argumentIndex = (Integer) valueMethod.invoke(ann);
                    } catch (Exception e) {
                        argumentIndex = i; // Fallback to sequential
                    }
                    Class<?> paramType = paramTypes[i];
                    argumentMap.put(argumentIndex, paramType.isArray() ? paramType.getComponentType() : paramType);
                    break;
                }
            }
            
            // Skip special parameters
            if (isSpecial) {
                continue;
            }
            
            // If not @Argument, include as regular parameter
            if (argumentIndex == -1) {
                argumentMap.put(i, paramTypes[i]);
            }
        }
        
        // Build signature list from the argument map
        if (argumentMap.isEmpty()) {
            return null;
        }
        
        int maxIndex = argumentMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        boolean hasCompleteSequence = true;
        for (int idx = 0; idx <= maxIndex; idx++) {
            if (!argumentMap.containsKey(idx)) {
                hasCompleteSequence = false;
                break;
            }
        }
        
        if (hasCompleteSequence) {
            List<Class<?>> sig = new ArrayList<>();
            for (int idx = 0; idx <= maxIndex; idx++) {
                sig.add(argumentMap.get(idx));
            }
            return sig;
        }
        
        return null;
    }

    private static Path getApprovalsFilePath() {
        // Store outside the Zomboid user tree: everything under ${user.home}/Zomboid
        // is writable from Lua (cache, saves, mods), so a malicious Java mod could
        // pre-approve its own hash from Lua before we ever check it.
        return Path.of(System.getProperty("user.home"), ".zombie_buddy", APPROVALS_FILE_NAME);
    }

    private static Properties loadJarApprovals() {
        Properties props = new Properties();
        try {
            Path p = getApprovalsFilePath();
            if (!Files.exists(p)) return props;
            try (FileInputStream fis = new FileInputStream(p.toFile())) {
                props.load(fis);
            }
        } catch (Exception e) {
            Logger.error("Could not load Java mod approvals: " + e);
        }
        return props;
    }

    private static void saveJarApprovals(Properties props) {
        try {
            Path p = getApprovalsFilePath();
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            try (FileOutputStream fos = new FileOutputStream(p.toFile())) {
                props.store(fos, "ZombieBuddy Java mod decisions (modId|sha256 -> yes | no)");
            }
        } catch (Exception e) {
            Logger.error("Could not save Java mod approvals: " + e);
        }
    }

    private static String sha256Hex(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            Logger.error("Could not hash file " + file + ": " + e);
            return null;
        }
    }

    private static final String DIALOG_TITLE = "ZombieBuddy Java mod approval";

    /**
     * Shows a yes/no TinyFD dialog. Returns:
     *   TRUE  — Yes, FALSE — No,
     *   null  — no dialog shown (dialog unavailable / native error).
     * Using LWJGL's bundled TinyFD instead of Swing because PZ launches with
     * {@code -Djava.awt.headless=true}, which makes every AWT call throw
     * {@link java.awt.HeadlessException} no matter how hard we poke the cache.
     */
    private static Boolean tinyfdYesNo(String msg) {
        Class<?> dialogClass = Accessor.findClass("org.lwjgl.util.tinyfd.TinyFileDialogs");
        if (dialogClass == null) {
            if (Core.getInstance().getGameVersion().isGreaterThan(GameVersion.parse("42.14"))) {
                Logger.error("tinyfdYesNo(): game version > 42.14 but TinyFileDialogs missing; returning null");
                return null;
            }
            Logger.info("tinyfdYesNo(): pre-42.15 and TinyFileDialogs missing; defaulting YES");
            return Boolean.TRUE;
        }
        try {
            Object result = Accessor.callByName(
                dialogClass,
                "tinyfd_messageBox",
                DIALOG_TITLE,
                msg,
                "yesno",
                "warning",
                false   // default: No
            );
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            Logger.error("Could not show dialog: " + t);
            return null;
        }
    }

    /**
     * Two-stage prompt: allow/deny, then (always) "remember this decision?".
     * Pressing No / closing a dialog defaults to session-only — never silently
     * persists on an accident.
     */
    private static Approval askUserApproveJar(String modId, File jarFile, String sha256) {
        String modified = (jarFile != null && jarFile.exists())
            ? java.time.Instant.ofEpochMilli(jarFile.lastModified())
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
            : "<unknown>";
        Boolean allow = tinyfdYesNo(
            "Allow Java mod to load?\n\n"
                + "Mod: " + modId + "\n\n"
                + "JAR: " + jarFile + "\n\n"
                + "Modified: " + modified + "\n\n"
                + "SHA-256: " + sha256 + "\n\n"
                + "Only allow if you trust this mod source."
        );
        if (allow == null) return Approval.DENY_SESSION; // dialog failed — fail closed, but don't persist

        String verb = allow ? "APPROVAL" : "DENIAL";
        String yesHint = allow
            ? "Yes — save to ~/.zombie_buddy/" + APPROVALS_FILE_NAME + " and do not ask again."
            : "Yes — save as denied; this JAR will be blocked without asking.";
        String noHint = allow
            ? "No  — allow only for this game session."
            : "No  — deny only for this game session (ask again next launch).";

        Boolean remember = tinyfdYesNo(
            "Remember this " + verb + " across game sessions?\n\n"
                + "Mod: " + modId + "\n\n"
                + yesHint + "\n\n"
                + noHint
        );
        boolean persist = Boolean.TRUE.equals(remember);
        if (allow)  return persist ? Approval.ALLOW_PERSIST : Approval.ALLOW_SESSION;
        else        return persist ? Approval.DENY_PERSIST  : Approval.DENY_SESSION;
    }

    private static boolean isJarAllowedByPolicy(String modId, JavaModInfo jModInfo, Properties approvals, String hash) {
        File jarFile = jModInfo != null ? jModInfo.getJarFileAsFile() : null;
        if (hash == null) return false;

        String policy = g_jarPolicy;
        String modKey = modId != null && !modId.isEmpty() ? modId : jModInfo.javaPkgName();
        String recKey = recordKey(modKey, hash);

        String decision = approvals.getProperty(recKey);
        if (decision == null) decision = g_sessionDecisions.get(recKey);
        if (DECISION_YES.equals(decision)) return true;
        if (DECISION_NO.equals(decision)) {
            Logger.warn("Blocking Java mod by stored denial: " + modKey + " (" + jarFile + ")");
            return false;
        }

        if (POLICY_ALLOW_ALL.equals(policy)) {
            approvals.setProperty(recKey, DECISION_YES);
            return true;
        }
        if (POLICY_DENY_NEW.equals(policy)) {
            Logger.warn("Blocking Java mod by policy=deny-new: " + modKey + " (" + jarFile + ")");
            return false;
        }

        switch (askUserApproveJar(modKey, jarFile, hash)) {
            case ALLOW_PERSIST:
                approvals.setProperty(recKey, DECISION_YES);
                return true;
            case ALLOW_SESSION:
                g_sessionDecisions.put(recKey, DECISION_YES);
                return true;
            case DENY_PERSIST:
                approvals.setProperty(recKey, DECISION_NO);
                return false;
            case DENY_SESSION:
            default:
                g_sessionDecisions.put(recKey, DECISION_NO);
                return false;
        }
    }

    public static void loadMods(ArrayList<String> mods) {
        ArrayList<JavaModInfo> jModInfos = new ArrayList<>();
        ArrayList<String> jModIds = new ArrayList<>();

        for (String mod_id : mods) {
            var mod = ChooseGameInfo.getAvailableModDetails(mod_id);
            if (mod == null) continue;

            if (Accessor.hasPublicMethod(mod, "getVersionDir") && Accessor.hasPublicMethod(mod, "getCommonDir")) {
                // B42+
                // follow lua engine logic, load common dir first, then version dir
                // so version dir could override common dir
                JavaModInfo jModInfoCommon = JavaModInfo.parse(mod.getCommonDir());
                JavaModInfo jModInfoVersion = JavaModInfo.parse(mod.getVersionDir());

                if (jModInfoCommon != null) {
                    jModInfos.add(jModInfoCommon);
                    jModIds.add(mod_id);
                    if (jModInfoVersion == null) {
                        // when mod.info is in common dir, but JAR is in version dir
                        jModInfoVersion = JavaModInfo.parseMerged(mod.getCommonDir(), mod.getVersionDir());
                    }
                }
                if (jModInfoVersion != null) {
                    jModInfos.add(jModInfoVersion);
                    jModIds.add(mod_id);
                }
            } else {
                // B41
                JavaModInfo jModInfo = JavaModInfo.parse(mod.getDir());
                if (jModInfo != null) {
                    jModInfos.add(jModInfo);
                    jModIds.add(mod_id);
                }
            }
        }

        Logger.info("java mod list to load:");
        printModList(jModInfos);

        // Find the last occurrence index for each package name
        Map<String, Integer> lastPkgNameIndex = new HashMap<>();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            lastPkgNameIndex.put(jModInfo.javaPkgName(), i);
        }

        // Process the list to determine which mods should be skipped
        String myPackageName = Loader.class.getPackage().getName();
        ArrayList<Boolean> shouldSkipList = new ArrayList<>();
        ArrayList<String> skipReasons = new ArrayList<>();
        Properties approvals = loadJarApprovals();
        Properties approvalsBefore = (Properties) approvals.clone();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            String modId = i < jModIds.size() ? jModIds.get(i) : jModInfo.javaPkgName();
            File jarFile = jModInfo.getJarFileAsFile();
            String hash = sha256Hex(jarFile);
            boolean shouldSkip = false;
            String skipReason = "";
            
            // Skip ZombieBuddy itself - it's loaded as a Java agent, not through normal mod loading
            if (jModInfo.javaPkgName().equals(myPackageName)) {
                shouldSkip = true;
                skipReason = " (loaded as Java agent, skipping normal mod loading)"
                    + SelfUpdater.getExclusionReasonSuffix(jarFile);
            }
            
            // Check if this mod's package name appears in a later mod
            Integer lastIndex = lastPkgNameIndex.get(jModInfo.javaPkgName());
            if (lastIndex != null && lastIndex > i) {
                shouldSkip = true;
                skipReason = " (package " + jModInfo.javaPkgName() + " is overridden by later mod)";
            }

            // Enforce Java JAR policy for new/changed binaries.
            if (!shouldSkip && !isJarAllowedByPolicy(modId, jModInfo, approvals, hash)) {
                shouldSkip = true;
                skipReason = " (blocked by policy=" + g_jarPolicy + ", modId=" + modId + ")";
            }
            
            shouldSkipList.add(shouldSkip);
            skipReasons.add(skipReason);

            // Snapshot for ZombieBuddy.getJavaModStatus(). Skipped when no JAR
            // (e.g. metadata-only mod.info entries in B42 common dirs).
            if (jarFile != null) {
                String decision = null;
                boolean persisted = false;
                if (hash != null) {
                    String recKey = recordKey(modId, hash);
                    String v = approvals.getProperty(recKey);
                    if (v != null) { decision = v; persisted = true; }
                    else {
                        v = g_sessionDecisions.get(recKey);
                        if (v != null) { decision = v; persisted = false; }
                    }
                }
                g_jarLoadStatus.put(modId, new JavaModLoadState(
                    !shouldSkip,
                    shouldSkip ? skipReason.trim() : "loaded",
                    hash,
                    decision,
                    persisted
                ));
            }
        }

        if (!approvalsBefore.equals(approvals)) {
            saveJarApprovals(approvals);
        }
        
        if (!shouldSkipList.isEmpty()) {
            // Print excluded mods first
            for (int i = 0; i < jModInfos.size(); i++) {
                if (shouldSkipList.get(i)) {
                    JavaModInfo jModInfo = jModInfos.get(i);
                    String reason = i < skipReasons.size() ? skipReasons.get(i) : "";
                    Logger.info("Excluded: " + jModInfo.modDirectory().getAbsolutePath() + reason);
                }
            }
            
            // Build list of mods that will be loaded
            ArrayList<JavaModInfo> modsToLoad = new ArrayList<>();
            for (int i = 0; i < jModInfos.size(); i++) {
                if (!shouldSkipList.get(i)) {
                    modsToLoad.add(jModInfos.get(i));
                }
            }
            Logger.info("java mod list after processing:");
            printModList(modsToLoad);
        }

        // Load only the mods that should be loaded
        for (int i = 0; i < jModInfos.size(); i++) {
            if (!shouldSkipList.get(i)) {
                loadJavaMod(jModInfos.get(i));
            }
        }
    }

    public static void printModList(ArrayList<JavaModInfo> jModInfos) {
        int longestPathLength = 0;
        for (JavaModInfo jModInfo : jModInfos) {
            if (jModInfo.modDirectory().getAbsolutePath().length() > longestPathLength) {
                longestPathLength = jModInfo.modDirectory().getAbsolutePath().length();
            }
        }

        String formatString = "    %-" + longestPathLength + "s %s";
        for (JavaModInfo jModInfo : jModInfos) {
            Logger.info(String.format(formatString, jModInfo.modDirectory().getAbsolutePath(), jModInfo.javaPkgName()));
        }
    }

    // Note: isPreMain parameter is reserved for future use
    public static void ApplyPatchesFromPackage(String packageName, ClassLoader modLoader, boolean isPreMain) {
        // Load and invoke optional Main class
        String mainClassName = packageName + ".Main";
        if (g_known_classes.contains(mainClassName)) {
            Logger.info("Java class " + mainClassName + " already loaded, skipping.");
        } else {
            g_known_classes.add(mainClassName);

            Logger.info("loading class " + mainClassName);
            Class<?> cls = null;
            try {
                cls = Class.forName(mainClassName);
                try_call_main(cls);
                Logger.info("loaded " + mainClassName);
            } catch (ClassNotFoundException e) {
                // Main class is optional - if it doesn't exist, that's fine
                Logger.info("Main class " + mainClassName + " not found (optional, skipping)");
            } catch (Exception e) {
                Logger.error("failed to load Java class " + mainClassName + ": " + e);
            }
        }

        List<Class<?>> patches = CollectPatches(packageName, modLoader);
        if (patches.isEmpty()) {
            Logger.info("no patches in package " + packageName);
            return;
        }

        // Group patches by target class+method
        Map<PatchTarget, List<Class<?>>> advicePatches = new HashMap<>();
        Map<PatchTarget, Class<?>> delegationPatches = new HashMap<>();
        Set<String> classesToWarmUp = new HashSet<>(); // Classes that need warm-up

        for (Class<?> patch : patches) {
            Patch ann = patch.getAnnotation(Patch.class);
            if (ann == null) continue;

            // TODO: show error on game UI (if debug mode is enabled?)
            if (ann.className().equals("zombie.Lua.LuaManager$Exposer") && ann.methodName().equals("exposeAll") && !ann.IKnowWhatIAmDoing()) {
                Logger.error("XXX");
                Logger.error("XXX don't patch Exposer.exposeAll, use @Exposer.LuaClass annotation!");
                Logger.error("XXX");
                continue;
            }

            PatchTarget target = new PatchTarget(ann.className(), ann.methodName());
            
            // Track classes that need warm-up
            if (ann.warmUp()) {
                classesToWarmUp.add(ann.className());
            }
            
            if (ann.isAdvice()) {
                advicePatches.computeIfAbsent(target, k -> new ArrayList<>()).add(patch);
            } else {
                if (delegationPatches.containsKey(target)) {
                    Logger.info("WARNING: multiple MethodDelegation patches for " + 
                        ann.className() + "." + ann.methodName() + " - only last one will apply!");
                }
                delegationPatches.put(target, patch);
            }
        }

        // Collect all target classes that need patching
        Set<String> targetClasses = new HashSet<>();
        for (PatchTarget t : advicePatches.keySet()) targetClasses.add(t.className());
        for (PatchTarget t : delegationPatches.keySet()) targetClasses.add(t.className());

        // Check which target classes are already loaded
        Set<String> loadedClasses = new HashSet<>();
        for (Class<?> c : g_instrumentation.getAllLoadedClasses()) {
            String className = c.getName();
            if (targetClasses.contains(className)) {
                loadedClasses.add(className);
            }
        }
        if (!loadedClasses.isEmpty() && g_verbosity > 0) {
            Logger.info("Already loaded classes to retransform: " + loadedClasses);
        }

        // Warn about MethodDelegation on already-loaded classes (won't work with retransformation)
        // for (var entry : delegationPatches.entrySet()) {
        //     if (loadedClasses.contains(entry.getKey().className())) {
        //         Logger.error("WARNING: MethodDelegation patch for already-loaded class " + 
        //             entry.getKey().className() + "." + entry.getKey().methodName() + 
        //             " - this may not work! Use isAdvice=true for loaded classes.");
        //     }
        // }

        // Check if we have Advice patches on already-loaded classes
        // If so, we need to disable class format changes for retransformation to work
        boolean hasAdviceOnLoadedClasses = false;
        Set<String> advLoadedClasses = new HashSet<>();
        for (var entry : advicePatches.entrySet()) {
            if (loadedClasses.contains(entry.getKey().className())) {
                hasAdviceOnLoadedClasses = true;
                advLoadedClasses.add(entry.getKey().className());
                break;
            }
        }

        var bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly();
        if (g_verbosity > 0) {
            if (g_verbosity <= 2) {
                bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut().withTransformationsOnly();
            } else { // 3+
                bbLogger = AgentBuilder.Listener.StreamWriting.toSystemOut();
            }
        }

        AgentBuilder builder = new AgentBuilder.Default();
        
        // Only disable class format changes if we have Advice patches on already-loaded classes
        // This is needed for retransformation to work, but breaks MethodDelegation
        if (hasAdviceOnLoadedClasses) {
            if (g_verbosity > 0) {
                Logger.info("Disabling class format changes for Advice patches on already-loaded classes");
            }
            builder = builder.disableClassFormatChanges();
        }
        
        builder = builder
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(bbLogger)
            .with(new AgentBuilder.Listener.Adapter() {
                @Override
                public void onTransformation(net.bytebuddy.description.type.TypeDescription td, 
                                             ClassLoader cl, 
                                             net.bytebuddy.utility.JavaModule jm,
                                             boolean loaded,
                                             net.bytebuddy.dynamic.DynamicType dt) {
                    Logger.info("Transformed: " + td.getName() + (loaded ? " (retransformed)" : " (new load)"));
                }

                @Override
                public void onError(String typeName, ClassLoader cl, net.bytebuddy.utility.JavaModule jm,
                                   boolean loaded, Throwable throwable) {
                    Logger.error("ERROR transforming " + typeName + ": " + throwable.getMessage());
                    throwable.printStackTrace();
                }

                @Override
                public void onIgnored(net.bytebuddy.description.type.TypeDescription td, 
                                     ClassLoader cl, 
                                     net.bytebuddy.utility.JavaModule jm,
                                     boolean loaded) {
                    // Log ignored classes that we're targeting
                    String className = td.getName();
                    if (targetClasses.contains(className)) {
                        Logger.error("WARNING: Target class IGNORED: " + className + " (loaded=" + loaded + ")");
                    }
                }
            });

        for (String className : targetClasses) {
            // Collect all method patches for this class
            Map<String, List<Class<?>>> methodAdvices = new HashMap<>();
            Map<String, Class<?>> methodDelegations = new HashMap<>();

            for (var entry : advicePatches.entrySet()) {
                if (entry.getKey().className().equals(className)) {
                    methodAdvices.put(entry.getKey().methodName(), entry.getValue());
                }
            }
            for (var entry : delegationPatches.entrySet()) {
                if (entry.getKey().className().equals(className)) {
                    methodDelegations.put(entry.getKey().methodName(), entry.getValue());
                }
            }

            builder = builder
                .type(SyntaxSugar.typeMatcher(className))
                .transform((bl, td, cl, mo, pd) -> {
                    var result = bl;
                    
                    // Apply stacked Advice patches per method
                    for (var entry : methodAdvices.entrySet()) {
                        String methodName = entry.getKey();
                        List<Class<?>> advices = entry.getValue();
                        
                        Logger.info("patching " + className + "." + methodName + " with " + advices.size() + " advice(s)");
                        
                        // Check if method exists in the type description
                        var methods = td.getDeclaredMethods().filter(SyntaxSugar.methodMatcher(methodName));
                        if (methods.isEmpty()) {
                            Logger.error("WARNING: Method " + methodName + " not found in " + td.getName());
                        }
                        
                        // Apply each advice via separate .visit() calls - they stack
                        for (Class<?> adviceClass : advices) {
                            // Get the Patch annotation to read strictMatch parameter
                            Patch patchAnn = adviceClass.getAnnotation(Patch.class);
                            boolean strictMatch = patchAnn != null && patchAnn.strictMatch();
                            
                            // Transform patch class to replace Patch.* annotations with ByteBuddy's
                            Class<?> transformedClass;
                            try {
                                transformedClass = PatchTransformer.transformPatchClass(adviceClass, g_instrumentation, g_verbosity, false);
                                if (transformedClass == null) {
                                    Logger.error("ERROR: PatchTransformer returned null for " + adviceClass.getName());
                                    continue;
                                }
                            } catch (Exception e) {
                                Logger.error("ERROR: Failed to transform patch class " + adviceClass.getName() + ": " + e.getMessage());
                                e.printStackTrace();
                                continue;
                            }
                            
                            // KISS approach: Simple and predictable method matching
                            var methodMatcher = SyntaxSugar.methodMatcher(methodName);
                            
                            boolean hasAllArguments = false;
                            boolean hasNoParamMethod = false;
                            List<Class<?>> inferredTypes = null;
                            Integer minParameterCount = null; // For incomplete @Argument sequences
                            // List of argument constraint maps from each advice method
                            java.util.List<java.util.Map<Integer, Class<?>>> allAdviceMaps = new java.util.ArrayList<>();
                            // List of booleans indicating if exact parameter count is required for each advice method
                            java.util.List<Boolean> allAdviceExactMatch = new java.util.ArrayList<>();
                            
                            // Track all inferred signatures to detect multiple methods with different signatures
                            java.util.Set<List<Class<?>>> allInferredSignatures = new java.util.HashSet<>();
                            
                            for (Method adviceMethod : transformedClass.getDeclaredMethods()) {
                                // Check if this method has advice annotations
                                if (!hasAnnotation(adviceMethod, ADVICE_ANNOTATION_PATTERNS)) continue;
                                
                                java.lang.annotation.Annotation[][] paramAnns = adviceMethod.getParameterAnnotations();
                                
                                // Check for @AllArguments annotation
                                if (hasParameterAnnotation(adviceMethod, ALL_ARGUMENTS_PATTERNS)) {
                                            hasAllArguments = true;
                                }
                                
                                // If method has no parameters (and no @AllArguments), match only methods with no parameters
                                if (adviceMethod.getParameterCount() == 0 && !hasAllArguments) {
                                    hasNoParamMethod = true;
                                }
                                
                                // Try to infer signature from @Argument annotations
                                // Process all methods to detect multiple signatures, not just the first one
                                if (!hasAllArguments) {
                                    Class<?>[] paramTypes = adviceMethod.getParameterTypes();
                                    
                                    // Map to collect argument indices and their types for THIS advice method
                                    java.util.Map<Integer, Class<?>> argumentMap = new java.util.HashMap<>();
                                    boolean hasAnyArguments = false;
                                    boolean allParamsAreSpecial = paramTypes.length > 0; // Will be set to false if we find a non-special param
                                    int regularParamCount = 0;
                                    
                                    for (int i = 0; i < paramAnns.length; i++) {
                                        boolean isArgument = false;
                                        boolean isLocal = false;
                                        boolean isThis = false;
                                        int argumentIndex = -1;
                                        
                                        for (java.lang.annotation.Annotation ann : paramAnns[i]) {
                                            String annType = ann.annotationType().getName();
                                            if (annType.contains("Advice$Argument")) {
                                                isArgument = true;
                                                hasAnyArguments = true;
                                                allParamsAreSpecial = false; // @Argument is not "special" in this context
                                                // Read the index from the annotation's value() method
                                                try {
                                                    java.lang.reflect.Method valueMethod = ann.annotationType().getMethod("value");
                                                    argumentIndex = (Integer) valueMethod.invoke(ann);
                                                } catch (Exception e) {
                                                    // Fallback: if we can't read the value, assume sequential (old behavior)
                                                    argumentIndex = i;
                                                }
                                                Class<?> paramType = paramTypes[i];
                                                Class<?> typeToStore = paramType.isArray() ? paramType.getComponentType() : paramType;
                                                argumentMap.put(argumentIndex, typeToStore);
                                                break;
                                            } else if (annType.contains("Advice$Local")) {
                                                // @Local parameters are not part of the target method signature
                                                isLocal = true;
                                                break;
                                            } else if (annType.contains("Advice$This")) {
                                                // @This parameters are not part of the target method signature
                                                isThis = true;
                                                break;
                                            }
                                        }
                                        
                                        // Skip @Local and @This parameters - they're not part of the target method signature
                                        if (isLocal || isThis) {
                                            continue;
                                        }
                                        
                                        // If not @Argument and not @Return/@AllArguments/@Local/@This, include it as a regular parameter
                                        if (!isArgument) {
                                            boolean isSpecial = false;
                                            for (java.lang.annotation.Annotation ann : paramAnns[i]) {
                                                String annType = ann.annotationType().getName();
                                                if (annType.contains("Advice$Return") || annType.contains("Advice$AllArguments") || annType.contains("Advice$Local") || annType.contains("Advice$This")) {
                                                    isSpecial = true;
                                                    break;
                                                }
                                            }
                                            if (!isSpecial) {
                                                // Regular parameter (not annotated) - assume it's in order
                                                allParamsAreSpecial = false;
                                                argumentMap.put(i, paramTypes[i]);
                                                regularParamCount++;
                                            }
                                        }
                                    }
                                    
                                    if (!argumentMap.isEmpty()) {
                                        allAdviceMaps.add(argumentMap);
                                        allAdviceExactMatch.add(!hasAnyArguments);
                                    }
                                    
                                    // If all parameters are special (e.g., only @Return), treat as matching methods with no parameters
                                    if (allParamsAreSpecial && paramTypes.length > 0) {
                                        hasNoParamMethod = true;
                                    }
                                    
                                    // Build signature list from the argument map
                                    if (hasAnyArguments && !argumentMap.isEmpty()) {
                                        // Find the maximum index to determine signature length
                                        int maxIndex = argumentMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                                        
                                        // Check if we have a complete sequence from 0 to maxIndex
                                        boolean hasCompleteSequence = true;
                                        for (int idx = 0; idx <= maxIndex; idx++) {
                                            if (!argumentMap.containsKey(idx)) {
                                                hasCompleteSequence = false;
                                                break;
                                            }
                                        }
                                        
                                        // For @Argument annotations, we use "at least N parameters" matching
                                        // This allows @Argument(0) to match methods with 1, 2, 3+ parameters
                                        // and @Argument(1) to match methods with 2, 3+ parameters, etc.
                                        int requiredParamCount = maxIndex + 1;
                                        if (minParameterCount == null || requiredParamCount > minParameterCount) {
                                            minParameterCount = requiredParamCount;
                                        }
                                        if (g_verbosity > 1) {
                                            if (hasCompleteSequence) {
                                                Logger.info("DEBUG: Complete @Argument sequence (0 to " + maxIndex + "), matching methods with at least " + requiredParamCount + " parameters");
                                            } else {
                                                Logger.info("DEBUG: Incomplete @Argument sequence, requires at least " + requiredParamCount + " parameters");
                                            }
                                        }
                                        
                                        // Still build signature for multiple signature detection, but don't use it for exact matching
                                        if (hasCompleteSequence) {
                                            // Build the signature list in order
                                            List<Class<?>> sig = new ArrayList<>();
                                            for (int idx = 0; idx <= maxIndex; idx++) {
                                                sig.add(argumentMap.get(idx));
                                            }
                                            allInferredSignatures.add(sig);
                                            // Don't set inferredTypes - we'll use minParameterCount for matching instead
                                        }
                                    } else if (!argumentMap.isEmpty()) {
                                        // No @Argument annotations, but we have regular parameters
                                        // Build signature from regular parameters in order
                                        List<Class<?>> sig = new ArrayList<>();
                                        for (int idx = 0; idx < paramTypes.length; idx++) {
                                            boolean isSpecial = false;
                                            for (java.lang.annotation.Annotation ann : paramAnns[idx]) {
                                                String annType = ann.annotationType().getName();
                                                if (annType.contains("Advice$Return") || annType.contains("Advice$AllArguments") || annType.contains("Advice$This")) {
                                                    isSpecial = true;
                                                    break;
                                                }
                                            }
                                            if (!isSpecial) {
                                                sig.add(paramTypes[idx]);
                                            }
                                        }
                                        if (!sig.isEmpty()) {
                                            allInferredSignatures.add(sig);
                                            if (inferredTypes == null) {
                                                inferredTypes = sig;
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // If we have multiple methods with different signatures, use name-based matching
                            // so ByteBuddy can match each method to the appropriate overload
                            boolean hasMultipleSignatures = allInferredSignatures.size() > 1;
                            if (g_verbosity > 1) {
                                Logger.info("DEBUG: allInferredSignatures.size() = " + allInferredSignatures.size() + " for " + className + "." + methodName);
                                for (List<Class<?>> sig : allInferredSignatures) {
                                    Logger.info("DEBUG:   signature: " + sig);
                                }
                            }
                            if (hasMultipleSignatures) {
                                inferredTypes = null; // Clear inferred types to force name-based matching
                            }

                            Logger.info(
                                    "hasMultipleSignatures: " + hasMultipleSignatures +
                                    ", hasAllArguments: " + hasAllArguments +
                                    ", hasNoParamMethod: " + hasNoParamMethod +
                                    ", inferredTypes: " + inferredTypes
                                    );
                            
                            // Apply matching strategy
                            if (hasAllArguments) {
                                // @AllArguments = match all overloads by name
                                if (g_verbosity > 0) {
                                    Logger.info("Using name-based matching for " + methodName + " (has @AllArguments)");
                                }
                            } else if (hasNoParamMethod && !strictMatch && allAdviceMaps.isEmpty()) {
                                // Match any method (default behavior for no-param advice when strictMatch=false)
                                if (g_verbosity > 0) {
                                    Logger.info("Matching any method for " + methodName + " (strictMatch=false, default)");
                                }
                            } else {
                                // Signature-aware matching for overloads and @Argument annotations
                                final java.util.List<java.util.Map<Integer, Class<?>>> maps = new java.util.ArrayList<>(allAdviceMaps);
                                final java.util.List<Boolean> exactMatches = new java.util.ArrayList<>(allAdviceExactMatch);
                                final int minParams = (minParameterCount != null) ? minParameterCount : 0;
                                final boolean strict = strictMatch;
                                final boolean noParam = hasNoParamMethod;
                                
                                methodMatcher = SyntaxSugar.methodMatcher(methodName)
                                    .and(new net.bytebuddy.matcher.ElementMatcher<net.bytebuddy.description.method.MethodDescription>() {
                                        @Override
                                        public boolean matches(net.bytebuddy.description.method.MethodDescription target) {
                                            int targetParamCount = target.getParameters().size();
                                            
                                            // If advice has no parameters, check strictMatch
                                            if (noParam && targetParamCount == 0) return true;
                                            if (strict && noParam && maps.isEmpty() && targetParamCount > 0) return false;
                                            
                                            for (int i = 0; i < maps.size(); i++) {
                                                java.util.Map<Integer, Class<?>> argMap = maps.get(i);
                                                boolean exact = exactMatches.get(i);
                                                
                                                if (exact && targetParamCount != argMap.size()) continue;
                                                if (!exact && targetParamCount < argMap.size()) continue;
                                                if (!exact && targetParamCount < minParams) continue;

                                                boolean allArgsMatch = true;
                                                for (java.util.Map.Entry<Integer, Class<?>> entry : argMap.entrySet()) {
                                                    int idx = entry.getKey();
                                                    if (idx >= targetParamCount) {
                                                        allArgsMatch = false;
                                                        break;
                                                    }
                                                    Class<?> expected = entry.getValue();
                                                    if (expected == Object.class) continue;
                                                    net.bytebuddy.description.type.TypeDescription actual = target.getParameters().get(idx).getType().asErasure();
                                                    if (!actual.isAssignableTo(expected)) {
                                                        allArgsMatch = false;
                                                        break;
                                                    }
                                                }
                                                if (allArgsMatch) return true;
                                            }
                                            
                                            // If no signatures match, and we have no-param advice with strictMatch=false, allow it
                                            return noParam && !strict;
                                        }
                                    });
                                    
                                if (g_verbosity > 0) {
                                    Logger.info("Using signature-aware matching for " + methodName + 
                                        " (signatures: " + maps.size() + ", minParams: " + minParams + ")");
                                }
                            }
                            
                            try {
                                result = result.visit(Advice.to(transformedClass).on(methodMatcher));
                                if (g_verbosity > 0) {
                                    Logger.info("Applied advice to " + className + "." + methodName);
                                }
                            } catch (Exception e) {
                                Logger.error("ERROR: Failed to apply advice to " + className + "." + methodName + ": " + e.getMessage());
                                if (g_verbosity > 0) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                    
                    // Apply MethodDelegation patches (only one per method)
                    for (var entry : methodDelegations.entrySet()) {
                        String methodName = entry.getKey();
                        Class<?> delegationClass = entry.getValue();
                        
                        // Transform the delegation class to convert Patch.* annotations to ByteBuddy annotations
                        Class<?> transformedDelegationClass = PatchTransformer.transformPatchClass(delegationClass, g_instrumentation, g_verbosity, true);
                        if (transformedDelegationClass == null) {
                            Logger.error("ERROR: PatchTransformer returned null for " + delegationClass.getName());
                            transformedDelegationClass = delegationClass; // Fall back to original
                        }
                        
                        Logger.info("patching " + className + "." + methodName + " with delegation");
                        
                        // Special handling for constructors: always use Object's constructor when overriding
                        if (methodName.equals("<init>")) {
                            try {
                                // Infer constructor signature from delegation method's @Argument annotations
                                Method delegationMethod = findMethodWithAnnotation(transformedDelegationClass, RUNTIME_TYPE_PATTERNS);
                                List<Class<?>> inferredConstructorSignature = null;
                                if (delegationMethod != null) {
                                    inferredConstructorSignature = inferSignatureFromMethod(delegationMethod, METHOD_DELEGATION_SPECIAL_ANNOTATIONS);
                                }
                                
                                // Build constructor matcher based on inferred signature
                                net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> constructorMatcher;
                                if (inferredConstructorSignature != null && !inferredConstructorSignature.isEmpty()) {
                                    // Match constructor with specific signature
                                    constructorMatcher = net.bytebuddy.matcher.ElementMatchers.isConstructor()
                                        .and(net.bytebuddy.matcher.ElementMatchers.takesArguments(inferredConstructorSignature.toArray(new Class<?>[0])));
                                } else {
                                    // No signature inferred, match all constructors (fallback)
                                    constructorMatcher = net.bytebuddy.matcher.ElementMatchers.isConstructor();
                                }
                                
                                // Always use Object's constructor when overriding a constructor.
                                // Field initializers will still run during object allocation (before any constructor is called).
                                java.lang.reflect.Constructor<?> objectConstructor = Object.class.getDeclaredConstructor();
                                result = result
                                    .constructor(constructorMatcher)
                                    .intercept(MethodCall.invoke(objectConstructor)
                                        .andThen(MethodDelegation.to(transformedDelegationClass)));
                            } catch (Exception e) {
                                Logger.error("ERROR: Could not set up constructor delegation: " + e.getMessage());
                                if (g_verbosity > 0) {
                                    e.printStackTrace();
                                }
                                // Fallback to SuperMethodCall
                                result = result
                                    .constructor(ElementMatchers.any())
                                    .intercept(net.bytebuddy.implementation.SuperMethodCall.INSTANCE.andThen(MethodDelegation.to(transformedDelegationClass)));
                            }
                        } else {
                        result = result
                            .method(SyntaxSugar.methodMatcher(methodName))
                            .intercept(MethodDelegation.to(transformedDelegationClass));
                        }
                    }
                    
                    return result;
                });
        }
        
        builder.installOn(g_instrumentation);

        // Explicitly retransform already-loaded classes to ensure Advice is applied
        // ByteBuddy's agent builder with RETRANSFORMATION strategy should handle this automatically,
        // but we call retransformClasses() explicitly to trigger the transformation immediately.
        // Note: ByteBuddy's Advice may not work correctly with retransformation for already-loaded classes
        // due to JVM limitations. If this doesn't work, patches need to be loaded before the target class.
        if (!advLoadedClasses.isEmpty()) {
            Logger.info("Explicitly retransforming " + advLoadedClasses.size() + " already-loaded class(es)");
            // Logger.info("WARNING: Advice patches on already-loaded classes may not work due to JVM retransformation limitations.");
            // Logger.info("Consider loading patches before the target class is loaded, or use MethodDelegation instead.");
            for (String className : advLoadedClasses) {
                try {
                    Class<?> cls = Class.forName(className);
                    // Retransform through ByteBuddy's agent pipeline
                    g_instrumentation.retransformClasses(cls);
                    if (g_verbosity > 0) {
                        Logger.info("Retransformed: " + className);
                    }
                } catch (ClassNotFoundException e) {
                    Logger.error("Could not find class for retransformation: " + className);
                } catch (Exception e) {
                    Logger.error("Error retransforming class " + className + ": " + e.getMessage());
                    if (g_verbosity > 0) {
                        e.printStackTrace();
                    }
                }
            }
        }

        // Warm up classes _AFTER_ installing the agent builder
        for (String className : classesToWarmUp) {
            Logger.info("warming up class: " + className);
            try {
                Class<?> cls = Class.forName(className);
                builder = builder.warmUp(cls);
            } catch (ClassNotFoundException e) {
                Logger.error("Could not find class for warm-up: " + className);
            } catch (Exception e) {
                Logger.error("Error warming up class " + className + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public static List<Class<?>> CollectPatches(String packageName, ClassLoader modLoader) {
        List<Class<?>> patches = new ArrayList<>();

        var classGraph = new ClassGraph()
                .enableAllInfo()              // Scan everything (annotations, methods, etc.)
                .acceptPackages(packageName); // Limit scan to package (includes subpackages)

        if (modLoader != null) {
            classGraph = classGraph.overrideClassLoaders(modLoader);
        } else {
            // When modLoader is null, we're scanning the system classloader
            // Explicitly add known JARs so ClassGraph can find classes in dynamically added JARs
            ArrayList<String> jarPaths = new ArrayList<>();
            
            // Always include the current JAR (ZombieBuddy's own JAR) so ClassGraph can scan it
            // This is especially important on Windows with fat JARs
            File currentJar = Utils.getCurrentJarFile();
            if (currentJar != null && currentJar.exists()) {
                jarPaths.add(currentJar.getAbsolutePath());
            }
            
            // Also add any other known JARs
            if (!g_known_jars.isEmpty()) {
                for (File jar : g_known_jars) {
                    String jarPath = jar.getAbsolutePath();
                    // Avoid duplicates
                    if (!jarPaths.contains(jarPath)) {
                        jarPaths.add(jarPath);
                    }
                }
            }
            
            if (!jarPaths.isEmpty()) {
                classGraph = classGraph.overrideClasspath((Object[]) jarPaths.toArray(new String[0]));
            }
        }

        try (ScanResult scanResult = classGraph.scan()) {
            // Log the number of classes scanned
            int totalClassesScanned = scanResult.getAllClasses().size();
            Logger.info("Scanned " + totalClassesScanned + " classes in package " + packageName);

            // Find all classes annotated with @Patch
            for (ClassInfo classInfo : scanResult.getClassesWithAnnotation(Patch.class.getName())) {
                try {
                    Class<?> patchClass = classInfo.loadClass();
                    // Only include classes from the exact package (exclude subpackages)
                    String classPackage = patchClass.getPackage() != null ? patchClass.getPackage().getName() : "";
                    if (!classPackage.equals(packageName)) {
                        continue; // Skip classes from subpackages
                    }
                    Logger.info("Found patch class: " + patchClass.getName());
                    patches.add(patchClass);
                } catch (Exception e) {
                    Logger.error("Error loading patch class " + classInfo.getName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }

            // Same scan: expose to Lua any classes annotated with @Exposer.LuaClass (exact package only)
            for (ClassInfo classInfo : scanResult.getClassesWithAnnotation(Exposer.LuaClass.class.getName())) {
                if (!classInfo.getPackageName().equals(packageName)) {
                    Logger.error("Class " + classInfo.getName() + " is annotated with @LuaClass but is not in the exact package " + packageName + ", skipping exposure");
                    continue;
                }
                try {
                    Class<?> cls = classInfo.loadClass();
                    Exposer.exposeClassToLua(cls);
                } catch (Exception e) {
                    Logger.error("Error exposing Lua class " + classInfo.getName() + ": " + e.getMessage());
                }
            }

            // Same scan: register classes that have @LuaMethod(global=true) (full class may not be exposed)
            for (ClassInfo classInfo : scanResult.getAllClasses()) {
                if (!classInfo.getPackageName().equals(packageName)) {
                    continue;
                }
                try {
                    Class<?> cls = classInfo.loadClass();
                    if (Exposer.hasGlobalLuaMethod(cls)) {
                        Exposer.addClassWithGlobalLuaMethod(cls);
                    }
                } catch (Throwable t) {
                    // skip (e.g. abstract, no class def)
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return patches;
    }

    public static void loadJavaMod(JavaModInfo modInfo) {
        Logger.info("------------------------------------------- loading Java mod: " + modInfo.modDirectory());
        
        // Load JAR file
        File jarFile = modInfo.getJarFileAsFile();
        if (jarFile == null) {
            Logger.error("Error! No JAR file specified for mod: " + modInfo.modDirectory());
            Logger.info("-------------------------------------------");
            return;
        }
        
        try {
            if (jarFile.exists()) {
                if (g_known_jars.contains(jarFile)) {
                    Logger.info("" + jarFile + " already added, skipping.");
                    Logger.info("-------------------------------------------");
                    return;
                } else {
                    // Validate that JAR contains the specified package
                    if (!validatePackageInJar(jarFile, modInfo.javaPkgName())) {
                        Logger.error("Error! JAR does not contain package " + modInfo.javaPkgName() + ": " + jarFile);
                        Logger.info("-------------------------------------------");
                        return;
                    }
                    
                    JarFile jf = new JarFile(jarFile);
                    g_instrumentation.appendToSystemClassLoaderSearch(jf);
                    g_known_jars.add(jarFile);
                    Logger.info("added to classpath: " + jarFile);
                }
            } else {
                Logger.error("classpath not found: " + jarFile);
                Logger.info("-------------------------------------------");
                return;
            }
        } catch (Exception e) {
            Logger.error("Error! invalid classpath: " + jarFile + " " + e);
            Logger.info("-------------------------------------------");
            return;
        }
        
        ApplyPatchesFromPackage(modInfo.javaPkgName(), null, false);
        
        Logger.info("-------------------------------------------");
    }

    /**
     * Validates that a JAR file contains the specified package.
     * @param jarFile The JAR file to check
     * @param packageName The package name to verify (e.g., "com.example.mymod")
     * @return true if the package exists in the JAR, false otherwise
     */
    private static boolean validatePackageInJar(File jarFile, String packageName) {
        if (jarFile == null || packageName == null || packageName.isEmpty()) {
            return false;
        }
        
        try {
            String packagePath = packageName.replace('.', '/');
            
            // Check if any entry in the JAR starts with the package path
            try (JarFile jf = new JarFile(jarFile)) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String entryName = entry.getName();
                    // Check if entry is in the package (either a class file or a directory)
                    // Match entries that start with packagePath + "/" to ensure we match files/dirs in the package
                    // Also match the package directory itself if it exists as an entry
                    if (entryName.startsWith(packagePath + "/") || 
                        entryName.equals(packagePath) || 
                        entryName.equals(packagePath + "/")) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Logger.error("Error validating package in JAR: " + e);
            return false;
        }
    }

    static void try_call_main(Class<?> cls) {
        Method main = null;
        try {
            main = cls.getMethod("main", String[].class);
        } catch (java.lang.NoSuchMethodException e) {
            return;
        } catch (Exception e) {
            Logger.error("" + cls + ": error getting main(): " + e);
            return;
        }

        // main cannot be null here if getMethod() succeeded
        try {
            String[] args = {}; // no arguments for now
            main.invoke(null, (Object) args);
            Logger.info("" + cls + ": main() invoked successfully");
        } catch (Exception e) {
            Logger.error("" + cls + ": error invoking main(): " + e);
        }
    }
}
