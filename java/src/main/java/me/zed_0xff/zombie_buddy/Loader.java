package me.zed_0xff.zombie_buddy;

import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontend;
import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontends;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

import java.io.IOException;
import java.lang.ClassLoader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.jar.JarFile;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.glfw.GLFW;
import org.lwjglx.opengl.Display;
import zombie.GameWindow;
import zombie.gameStates.ChooseGameInfo;

public class Loader {
    enum Phase {
        PREMAIN,
        MAIN
    }

    // Package-private: only ZombieBuddy's own classes may touch the instrumentation handle.
    // External mods (different package) cannot access this field directly, which prevents
    // an approved mod from gaining unrestricted bytecode manipulation of the entire JVM.
    static Instrumentation g_instrumentation;
    static int g_verbosity = 0;
    static boolean g_forceApprovalDialog = false;

    // called by Patch_GameWindow.Patch_DoLoadingText
    public static boolean g_hasDoLoadingText = false;

    /**
     * When {@code true} (default), a missing {@code .zbs} next to the JAR is allowed as &quot;unsigned&quot;
     * (unless {@link #g_jarPolicy} is {@code allow-all}, which skips ZBS checks entirely).
     * When {@code false}, missing {@code .zbs} is treated like a failed signature for prompt UI and load gating.
     * Invalid signatures (present {@code .zbs} but verification fails) are always blocked when ZBS is enforced.
     */
    static boolean g_allowUnsignedMods = true;

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

    static synchronized void setPolicy(String value) {
        if (g_jarPolicyLocked) {
            Logger.warn("Ignoring attempt to change policy (already locked at '" + g_jarPolicy + "')");
            return;
        }
        String v = value == null ? "" : value.trim().toLowerCase();
        if (!VALID_POLICIES.contains(v)) {
            Logger.warn("Invalid policy '" + value + "', keeping '" + g_jarPolicy + "'. Valid: " + VALID_POLICIES);
            return;
        }
        g_jarPolicy = v;
        g_jarPolicyLocked = true;
        Logger.info("Policy set to '" + g_jarPolicy + "' (locked)");
    }

    static String getPolicy() {
        return g_jarPolicy;
    }

    /** ZBS verification applies for every policy except {@code allow-all}. */
    private static boolean zbsSignatureChecksEnabled() {
        return !POLICY_ALLOW_ALL.equals(g_jarPolicy);
    }

    private record ZBSContext(
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById,
        Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthorsBySteamId
    ) {}

    private static ZBSContext loadZBSContext(Iterable<WorkshopItemID> workshopIds) {
        Set<WorkshopItemID> ids = new HashSet<>();
        for (WorkshopItemID id : workshopIds) {
            if (id != null) ids.add(id);
        }
        return new ZBSContext(SteamWorkshop.fetchItemDetails(ids), KnownAuthors.loadAuthors());
    }

    private static ZBSVerifier.CheckResult zbsCheck(
        Path jarPath, String hash, WorkshopItemID workshopItemId, ZBSContext ctx
    ) {
        if (!zbsSignatureChecksEnabled() || jarPath == null || hash == null) {
            return ZBSVerifier.CheckResult.DISABLED;
        }
        return ZBSVerifier.check(jarPath, hash, workshopItemId, workshopItemId != null,
            g_allowUnsignedMods, ctx.workshopDetailsById(), ctx.knownAuthorsBySteamId());
    }

    private static boolean zbsBlocked(ZBSVerifier.CheckResult r) {
        return r != ZBSVerifier.CheckResult.DISABLED && !r.flags().has(MF_VALID);
    }

    static final Set<Path> g_known_jars = new HashSet<>(); // used by PatchEngine

    // Persisted entries loaded from disk - the source of truth for saving
    private static List<ModApprovalsStore.ModEntry> g_storedEntries = new ArrayList<>();
    private static boolean g_modApprovalsDirty;
    private static final JarDecisionTable g_sessionJarDecisions = new JarDecisionTable();
    private static Config g_config = new Config();
    private static boolean g_configDirty;

    private static final Object g_approvalFrontendLock = new Object();
    private static volatile ModApprovalFrontend g_approvalFrontend;
    /** Set in {@link #configureApprovalFrontend}; resolved lazily — do not touch LWJGL during agent {@code premain}. */
    private static String g_approvalFrontendConfig = ModApprovalFrontends.ARG_AUTO;

    /** Called from {@link Agent#premain} to load config from disk before any mod loading. */
    static void initConfig() {
        g_config = Config.load();
        g_configDirty = false;
    }

    /** Called from {@link Agent#premain} with {@code frontend=...} (default {@code auto}). */
    static void configureApprovalFrontend(String value) {
        String v = value == null ? "" : value.trim();
        synchronized (g_approvalFrontendLock) {
            g_approvalFrontendConfig = v.isEmpty() ? ModApprovalFrontends.ARG_AUTO : v;
            g_approvalFrontend = null;
        }
        Logger.info("Java mod approval frontend: " + g_approvalFrontendConfig);
    }

    private static ModApprovalFrontend approvalFrontend() {
        ModApprovalFrontend f = g_approvalFrontend;
        if (f != null) {
            return f;
        }
        synchronized (g_approvalFrontendLock) {
            f = g_approvalFrontend;
            if (f != null) {
                return f;
            }
            g_approvalFrontend = ModApprovalFrontends.resolve(g_approvalFrontendConfig);
            return g_approvalFrontend;
        }
    }

    private static Boolean lookupJarDecision(JarDecisionTable disk, String sha256) {
        if (sha256 == null) return null;
        return disk.get(sha256);
    }

    private static void applySessionDecisions(JarDecisionTable approvals) {
        for (String hash : g_sessionJarDecisions.hashes()) {
            approvals.put(hash, g_sessionJarDecisions.get(hash));
        }
    }

    /**
     * Per-modId load/decision snapshot captured at loadMods() time so Lua
     * (and other callers) can display "loaded / blocked" next to each mod in
     * the UI. Keyed by modId; for multi-dir B42 mods the version-dir entry
     * (which typically carries the JAR) overwrites the common-dir one.
     */
    static record JavaModLoadState (
            String id,
            Path jarPath,
            ModFlags flags,
            String reason,      // "loaded" or a short skip reason
            String sha256,      // JAR sha256, null if no JAR
            Boolean decision    // true = allow, false = deny, null = undecided
    ) {}

    // key is jar pathname
    private static final Map<Path, JavaModLoadState> g_jarLoadStatus = new ConcurrentHashMap<>();

    // returns readonly snapshot
    static List<JavaModLoadState> getActiveJavaMods() {
        return g_jarLoadStatus.values().stream()
            .filter(s -> s.flags().has(MF_ACTIVE))
            .toList();
    }

    private static JarDecisionTable buildJarDecisionTable(List<ModApprovalsStore.ModEntry> mods) {
        JarDecisionTable table = new JarDecisionTable();
        for (ModApprovalsStore.ModEntry entry : mods) {
            if (entry.jarHash != null) {
                table.put(entry.jarHash, entry.decision);
            }
        }
        return table;
    }

    private static boolean isDecisionStored(String jarHash, Boolean decision) {
        if (jarHash == null || decision == null) return false;
        for (ModApprovalsStore.ModEntry entry : g_storedEntries) {
            if (jarHash.equals(entry.jarHash) && entry.decision == decision) {
                return true;
            }
        }
        return false;
    }

    private static List<ModApprovalsStore.ModEntry> copyStoredEntries(List<ModApprovalsStore.ModEntry> entries) {
        List<ModApprovalsStore.ModEntry> copy = new ArrayList<>();
        if (entries == null) return copy;
        for (ModApprovalsStore.ModEntry entry : entries) {
            if (entry == null) continue;
            copy.add(new ModApprovalsStore.ModEntry(
                entry.id,
                entry.workshopId,
                entry.jarHash,
                entry.decision,
                entry.time,
                entry.authorId
            ));
        }
        return copy;
    }

    /**
     * Add or update a decision in g_storedEntries (for persistence).
     * Updates existing entry with matching jarHash, or adds a new one.
     */
    private static void storeDecision(String jarHash, boolean allow, String modId, 
            WorkshopItemID workshopId, SteamID64 authorId) {
        if (jarHash == null) return;
        // Update existing entry if found
        for (ModApprovalsStore.ModEntry e : g_storedEntries) {
            if (jarHash.equals(e.jarHash)) {
                e.decision = allow;
                if (!Utils.isBlank(modId)) e.id = modId;
                if (workshopId != null) e.workshopId = workshopId;
                if (authorId != null) e.authorId = authorId;
                g_modApprovalsDirty = true;
                return;
            }
        }
        // Add new entry
        g_storedEntries.add(new ModApprovalsStore.ModEntry(
            modId != null ? modId : "",
            workshopId,
            jarHash,
            allow,
            null,
            authorId
        ));
        g_modApprovalsDirty = true;
    }

    private static boolean isJarAllowedByPolicy(String modId, JavaModInfo jModInfo, JarDecisionTable disk, String hash) {
        Path jarPath = jModInfo != null ? jModInfo.jarPath() : null;
        if (hash == null) return false;

        String policy = g_jarPolicy;
        Boolean decision = lookupJarDecision(disk, hash);
        if (Boolean.TRUE.equals(decision)) return true;
        if (Boolean.FALSE.equals(decision)) {
            Logger.warn("Blocking Java mod by stored denial: " + modId + " (" + jarPath + ")");
            return false;
        }

        if (POLICY_ALLOW_ALL.equals(policy)) {
            disk.put(hash, Boolean.TRUE);
            storeDecision(hash, true, modId, jModInfo != null ? jModInfo.getWorkshopItemID() : null, null);
            return true;
        }
        if (POLICY_DENY_NEW.equals(policy)) {
            Logger.warn("Blocking Java mod by policy=deny-new: " + modId + " (" + jarPath + ")");
            return false;
        }

        // policy=prompt: decisions come from approvePendingMods() in loadMods(); should not reach here.
        Logger.warn("No approval decision for " + modId + " (hash " + hash + ") — denying.");
        return false;
    }

    static void applyBatchApprovalLines(List<JarBatchApprovalProtocol.Entry> entries, JarDecisionTable disk) {
        if (entries == null) return;
        for (JarBatchApprovalProtocol.Entry entry : entries) {
            if (entry == null || entry.decision == null || Utils.isBlank(entry.sha256)) continue;
            boolean allow = entry.decision;
            disk.put(entry.sha256, allow);
            g_sessionJarDecisions.put(entry.sha256, allow);
            if (entry.flags.has(MF_PERSIST)) {
                storeDecision(entry.sha256, allow, entry.modId, entry.workshopItemId,
                    entry.zbs.valid() ? entry.zbs.authorSteamId() : null);
            }
            if (entry.flags.has(MF_PERSIST) && entry.zbs.valid() && entry.zbs.authorSteamId() != null) {
                if (entry.flags.has(MF_TRUST_AUTHOR)) {
                    storeTrustedAuthor(entry.zbs.authorSteamId());
                } else if (isAuthorTrusted(entry.zbs.authorSteamId())) {
                    // 'trust author' checkbox unchecked
                    removeTrustedAuthor(entry.zbs.authorSteamId());
                }
            }
            if (entry.flags.has(MF_PRELOAD) && !Utils.isBlank(entry.modId) && !Utils.isBlank(entry.infAbsolutePath) && !Utils.isBlank(entry.jarAbsolutePath)) {
                if (allow) {
                    PreloadMods.add(entry.modId, entry.infAbsolutePath, entry.jarAbsolutePath);
                } else {
                    PreloadMods.remove(entry.modId);
                }
            }
        }
    }

    private static boolean isAuthorTrusted(SteamID64 sid) {
        if (sid == null) {
            return false;
        }
        return g_config.trustsAuthor(sid);
    }

    private static void storeTrustedAuthor(SteamID64 sid) {
        if (sid == null) {
            return;
        }
        Config nextConfig = g_config.withTrustedAuthor(sid);
        if (!nextConfig.equals(g_config)) {
            g_config = nextConfig;
            g_configDirty = true;
        }
    }

    private static void removeTrustedAuthor(SteamID64 sid) {
        if (sid == null) {
            return;
        }
        Config nextConfig = g_config.withoutTrustedAuthor(sid);
        if (!nextConfig.equals(g_config)) {
            g_config = nextConfig;
            g_configDirty = true;
        }
    }

    private static final class PreloadMods {
        private PreloadMods() {}

        private static final String MANIFEST_ATTR = "ZB-Preload";

        private static void add(String id, Path infPath, Path jarPath) {
            Config nextConfig = g_config.withPreloadMod(id, new Config.PreloadMod(infPath, jarPath));
            if (!nextConfig.equals(g_config)) {
                g_config = nextConfig;
                g_configDirty = true;
                Watermark.setMidLine("Preload mod \"" + id + "\" added/updated. Restart recommended.");
            }
        }

        private static void remove(String id) {
            Config nextConfig = g_config.withoutPreloadMod(id);
            if (!nextConfig.equals(g_config)) {
                g_config = nextConfig;
                g_configDirty = true;
            }
        }

        private static List<String> getIds() {
            return new ArrayList<>(g_config.preload_mods().keySet());
        }

        /**
         * Returns true when the JAR's {@code META-INF/MANIFEST.MF} contains {@code ZB-Preload: true}.
         * Callers must verify the JAR's ZBS signature before trusting this value.
         */
        static boolean hasManifestPreload(Path jarPath) {
            if (jarPath == null || !Files.isRegularFile(jarPath)) {
                return false;
            }
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jarPath.toFile(), false)) {
                java.util.jar.Manifest mf = jf.getManifest();
                if (mf == null) {
                    return false;
                }
                String val = mf.getMainAttributes().getValue(MANIFEST_ATTR);
                return "true".equalsIgnoreCase(val != null ? val.trim() : null);
            } catch (Exception e) {
                return false;
            }
        }
    }

    static void preloadMods() {
        if (g_config.preload_mods().isEmpty()) {
            return;
        }

        // First pass: validate and collect entries
        record PreloadEntry(String id, String javaPkgName, Path canJarPath, String hash, WorkshopItemID workshopItemId) {}
        List<PreloadEntry> entries = new ArrayList<>();

        for (Map.Entry<String, Config.PreloadMod> e : g_config.preload_mods().entrySet()) {
            String id = e.getKey();
            Config.PreloadMod preloadMod = e.getValue();
            // Skip inactive/unprepared preloads before JAR hashing and network metadata lookup.
            if (!local.zbselective.RuntimeState.shouldPreload(id, preloadMod.jarPath())) continue;
            String javaPkgName = JavaModInfo.javaPkgNameFrom(preloadMod.infPath());
            if (Utils.isBlank(javaPkgName)) {
                Logger.warn("Preload mod '" + id + "': cannot read javaPkgName from " + preloadMod.infPath() + "; removing.");
                PreloadMods.remove(id);
                continue;
            }
            try {
                Path jarCanonicalPath = preloadMod.jarPath().toRealPath();
                Logger.info("Preloading pkg '" + javaPkgName + "' from " + jarCanonicalPath);
                if (!Files.isRegularFile(jarCanonicalPath)) {
                    Logger.warn("Preload pkg '" + javaPkgName + "': JAR not found at " + jarCanonicalPath + "; removing.");
                    PreloadMods.remove(id);
                    continue;
                }
                if (!PreloadMods.hasManifestPreload(jarCanonicalPath)) {
                    Logger.warn("Preload pkg '" + javaPkgName + "': JAR manifest missing '" + PreloadMods.MANIFEST_ATTR + "' entry in " + jarCanonicalPath + "; removing.");
                    PreloadMods.remove(id);
                    continue;
                }
                entries.add(new PreloadEntry(id, javaPkgName, jarCanonicalPath, Utils.sha256Hex(jarCanonicalPath), JavaModInfo.workshopItemIdFromInfPath(preloadMod.infPath())));
            } catch (IOException ex) {
                Logger.warn("Preload pkg '" + javaPkgName + "': cannot resolve JAR path " + preloadMod.jarPath() + "; removing.");
                PreloadMods.remove(id);
            }
        }
        if (entries.isEmpty()) return;

        ZBSContext zbsCtx = loadZBSContext(entries.stream().map(entry -> entry.workshopItemId()).toList());

        // Second pass: ZBS check and load
        for (PreloadEntry entry : entries) {
            ZBSVerifier.CheckResult zbsResult = zbsCheck(entry.canJarPath(), entry.hash(), entry.workshopItemId(), zbsCtx);
            if (zbsBlocked(zbsResult)) {
                Logger.warn("Preload pkg '" + entry.javaPkgName() + "': ZBS check failed (" + zbsResult.blockReason() + "); skipping.");
                PreloadMods.remove(entry.id());
                continue;
            }
            loadJar(entry.canJarPath(), entry.javaPkgName(), entry.hash(), Phase.PREMAIN);
            g_known_jars.remove(entry.canJarPath()); // remove from known_jars to allow Main.main run
            g_jarLoadStatus.put(entry.canJarPath(), new JavaModLoadState(
                        entry.id(),
                        entry.canJarPath(),
                        zbsResult.flags().with(MF_PRELOAD).with(MF_ACTIVE),
                        "preloaded",
                        entry.hash(),
                        null
                        ));
        }
        if (g_configDirty) {
            Config.save(g_config);
            g_configDirty = false;
        }
    }

    /**
     * Adds {@code jarFile} to the system classpath if not already present.
     * Validates that {@code packageName} exists in the JAR. If {@code approvedHash}
     * is non-null, re-hashes as a TOCTOU guard against file swaps after approval.
     *
     * @return true if the JAR was added; false on any failure or already-loaded.
     */
    private static boolean addJarToClasspath(Path jarPath, String packageName, String approvedHash) {
        // Resolve symlinks and normalise path once; all subsequent operations use the canonical
        // file so that symlink-swap attacks and path-alias bypasses of g_known_jars are eliminated.
        try {
            jarPath = jarPath.toRealPath();
        } catch (IOException e) {
            Logger.error("Cannot resolve canonical path for " + jarPath + ": " + e);
            return false;
        }
        if (g_known_jars.contains(jarPath)) {
            return false;
        }
        if (!validatePackageInJar(jarPath, packageName)) {
            Logger.error("JAR does not contain package " + packageName + ": " + jarPath);
            return false;
        }
        // Open the JarFile before re-hashing so both operations target the same inode.
        // This closes the symlink-swap window between the hash check and the load; a hard
        // file-replacement race (atomic rename) cannot be fully prevented via the Java
        // instrumentation API since appendToSystemClassLoaderSearch re-reads by path internally.
        JarFile jf;
        try {
            jf = new JarFile(jarPath.toFile());
        } catch (IOException e) {
            Logger.error("Cannot open JAR " + jarPath + ": " + e);
            return false;
        }
        if (approvedHash != null) {
            String currentHash = Utils.sha256Hex(jarPath);
            if (!approvedHash.equals(currentHash)) {
                try { jf.close(); } catch (IOException ignored) {}
                Logger.error("SECURITY: JAR hash changed between approval and load for "
                    + jarPath + " — expected " + approvedHash + ", got " + currentHash
                    + ". Aborting load.");
                return false;
            }
        }
        try {
            g_instrumentation.appendToSystemClassLoaderSearch(jf);
            g_known_jars.add(jarPath);
            Logger.info("added to classpath: " + jarPath);
            return true;
        } catch (Exception e) {
            try { jf.close(); } catch (IOException ignored) {}
            Logger.error("Error adding JAR to classpath " + jarPath + ": " + e);
            return false;
        }
    }

    private static void untrustAuthorForBannedMod(
        String modId,
        Path jarPath,
        String hash,
        WorkshopItemID workshopItemId,
        boolean steamBanned,
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById
    ) {
        if (!steamBanned || jarPath == null || hash == null) {
            return;
        }
        SteamID64 uploaderID = SteamWorkshop.getUploaderID(workshopItemId, workshopDetailsById);
        if (uploaderID != null && isAuthorTrusted(uploaderID)) {
            removeTrustedAuthor(uploaderID);
            Logger.warn("Removed trusted-author flag for " + uploaderID + " because banned mod was detected: " + modId);
        }
    }

    private static String authorDisplayName(
        SteamID64 sid,
        Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthors
    ) {
        if (sid == null) {
            return "";
        }
        KnownAuthors.AuthorEntry known = knownAuthors != null ? knownAuthors.get(sid) : null;
        return known != null && !Utils.isBlank(known.name) ? known.name : sid.toString();
    }

    private static final List<String> PINNED_MOD_ORDER = List.of(
        "ZombieBuddy",
        "zdk",
        "ZModUnbork"
    );

    private static void autoFixModOrder(List<String> mods) {
        if (Utils.isBlank(mods)) {
            return;
        }
        Map<String, Integer> before = pinnedModIndices(mods);
        int targetIndex = 0;
        for (String modId : before.keySet()) {
            moveModToIndex(mods, modId, targetIndex);
            targetIndex++;
        }
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            String modId = entry.getKey();
            int oldIndex = entry.getValue();
            int newIndex = mods.indexOf(modId);
            if (newIndex != oldIndex) {
                Logger.info("Auto-fix mod order: " + oldIndex + " -> " + newIndex + " " + modId);
            }
        }
    }

    private static Map<String, Integer> pinnedModIndices(List<String> mods) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String modId : PINNED_MOD_ORDER) {
            int index = mods.indexOf(modId);
            if (index >= 0) {
                out.put(modId, index);
            }
        }
        return out;
    }

    private static void moveModToIndex(List<String> mods, String modId, int index) {
        int oldIndex = mods.indexOf(modId);
        if (oldIndex < 0) return;
        mods.remove(oldIndex);
        mods.add(Math.min(index, mods.size()), modId);
    }

    /** Binary compatibility for mods compiled against the original ArrayList entry point. */
    public static void loadMods(ArrayList<String> mods) {
        loadMods((List<String>) mods);
    }

    public static void loadMods(List<String> mods) {
        local.zbselective.RuntimeState.begin(mods);
        if (g_config.auto_fix_mod_order()) {
            autoFixModOrder(mods);
        }
        ArrayList<JavaModInfo> jModInfos = new ArrayList<>();
        ArrayList<String> jModIds = new ArrayList<>();
        HashSet<String> processedIds = new HashSet<>();

        g_curr_modSet = new HashSet<>(mods);
        if ( "default".equals(g_curr_modSetID) && "default".equals(g_prev_modSetID) && g_curr_modSet != null && g_prev_modSet != null) {
            for (String mod_id : PreloadMods.getIds()) {
                if( g_prev_modSet.contains(mod_id) && !g_curr_modSet.contains(mod_id) ) {
                    Logger.info("Preload mod '" + mod_id + "' was removed from the default mod list; removing from preload.");
                    PreloadMods.remove(mod_id);
                    Watermark.setMidLine("Preload mod \"" + mod_id + "\" disabled. Restart the game to deactivate it.");
                }
            }
        }

        ArrayList<String> mergedIds = new ArrayList<>();
        mergedIds.addAll(local.zbselective.RuntimeState.enabledPreloadIds(PreloadMods.getIds())); // only enabled preloads
        mergedIds.addAll(mods);

        Boolean isB42 = null;
        for (String mod_id : mergedIds) {
            if (Utils.isBlank(mod_id)) continue;
            if (processedIds.contains(mod_id)) continue;
            processedIds.add(mod_id);

            var mod = ChooseGameInfo.getAvailableModDetails(mod_id);
            if (mod == null) continue;

            if (isB42 == null) {
                isB42 = Accessor.hasPublicMethod(mod, "getVersionDir") && Accessor.hasPublicMethod(mod, "getCommonDir");
            }

            JavaModInfo jModInfo = isB42
                ? Utils.firstNonNull(
                        () -> JavaModInfo.parse( mod.getVersionDir() ),
                        () -> JavaModInfo.parse( mod.getCommonDir() ),
                        () -> JavaModInfo.parseMerged( mod.getCommonDir(), mod.getVersionDir() )
                        )
                : JavaModInfo.parse(mod.getDir()); // B41

            if (jModInfo != null) {
                jModInfos.add(jModInfo);
                jModIds.add(mod_id);
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
        ModApprovalsStore.FileData fileData = ModApprovalsStore.load();
        // Store entries for persistence and build lookup table
        g_storedEntries = new ArrayList<>(fileData.mods);
        g_modApprovalsDirty = false;
        List<ModApprovalsStore.ModEntry> storedEntriesBefore = copyStoredEntries(g_storedEntries);
        JarDecisionTable approvals = buildJarDecisionTable(g_storedEntries);
        if (!g_forceApprovalDialog) {
            checkShiftKey();
        }
        if (!g_forceApprovalDialog) {
            applySessionDecisions(approvals);
        }
        if (g_forceApprovalDialog) {
            Logger.info("Shift held during game load; forcing Java mod approval dialog.");
        }
        // Structural-only skip flags (must match the policy loop below) — used to batch all PROMPT dialogs.
        ArrayList<Boolean> structuralOnlySkip = new ArrayList<>();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            boolean stSkip = false;
            if (jModInfo.javaPkgName().equals(myPackageName)) {
                stSkip = true;
            }
            Integer lastIdx = lastPkgNameIndex.get(jModInfo.javaPkgName());
            if (lastIdx != null && lastIdx > i) {
                stSkip = true;
            }
            structuralOnlySkip.add(stSkip);
        }
        ZBSContext zbsCtx = loadZBSContext(jModInfos.stream().map(JavaModInfo::getWorkshopItemID).toList());
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById = zbsCtx.workshopDetailsById();
        Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthorsBySteamId = zbsCtx.knownAuthorsBySteamId();

        // Pre-compute per-mod context to avoid duplicate work in prompt and load loops
        record ModCtx(String modId, Path jarPath, String hash, WorkshopItemID workshopItemId,
                      SteamWorkshop.ItemDetails workshopDetails, SteamWorkshop.BanInfo banInfo, boolean steamBanned,
                      ZBSVerifier.CheckResult zbsResult) {}
        List<ModCtx> modContexts = new ArrayList<>();

        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            String modId = i < jModIds.size() ? jModIds.get(i) : jModInfo.javaPkgName();
            Path jarPath = jModInfo.jarPath();
            String hash = Utils.sha256Hex(jarPath);
            WorkshopItemID workshopItemId = jModInfo.getWorkshopItemID();
            SteamWorkshop.ItemDetails workshopDetails = workshopItemId != null ? workshopDetailsById.get(workshopItemId) : null;
            SteamWorkshop.BanInfo banInfo = workshopItemId == null
                ? new SteamWorkshop.BanInfo(null, "Workshop id not found in mod path.")
                : (workshopDetails != null ? workshopDetails.ban() : null);
            boolean steamBanned = banInfo != null && Boolean.TRUE.equals(banInfo.status());
            modContexts.add(new ModCtx(modId, jarPath, hash, workshopItemId, workshopDetails, banInfo, steamBanned, zbsCheck(jarPath, hash, workshopItemId, zbsCtx)));
        }
        for (ModCtx ctx : modContexts) {
            untrustAuthorForBannedMod(
                ctx.modId,
                ctx.jarPath,
                ctx.hash,
                ctx.workshopItemId,
                ctx.steamBanned,
                workshopDetailsById
            );
        }

        if (POLICY_PROMPT.equals(g_jarPolicy)) {
            // Late Shift re-check: on first boot the display window may not exist when
            // checkShiftKey() runs above (before workshop/ZBS work). By the time we reach
            // here the window is more likely to be ready, so try once more.
            if (!g_forceApprovalDialog) {
                checkShiftKey();
                if (g_forceApprovalDialog) {
                    Logger.info("Shift held during game load (late detection); forcing Java mod approval dialog.");
                }
            }
            ArrayList<JarBatchApprovalProtocol.Entry> batchEntries = new ArrayList<>();
            for (int i = 0; i < jModInfos.size(); i++) {
                if (structuralOnlySkip.get(i)) continue;;

                ModCtx ctx = modContexts.get(i);
                if (ctx.hash == null) continue;

                JavaModInfo jModInfo = jModInfos.get(i);
                Date date = null;
                if (ctx.jarPath != null) {
                    try { date = new Date(Files.getLastModifiedTime(ctx.jarPath).toMillis()); } catch (IOException ignored) {}
                }
                String modDisplay = jModInfo.displayName();
                if (modDisplay == null || modDisplay.trim().isEmpty()) {
                    modDisplay = ctx.modId != null ? ctx.modId : "";
                }
                JarBatchApprovalProtocol.Entry.SteamBan steamBan = ctx.steamBanned
                    ? new JarBatchApprovalProtocol.Entry.SteamBan(ctx.banInfo != null ? ctx.banInfo.reason() : "")
                    : null;
                SteamID64 authorID = ctx.zbsResult.sid();
                ModFlags flags = ctx.zbsResult.flags();
                JarBatchApprovalProtocol.Entry.ZBSignature zbs = new JarBatchApprovalProtocol.Entry.ZBSignature(
                    flags.hasAll(MF_SIGNED, MF_VALID),
                    authorID,
                    flags.hasAll(MF_SIGNED, MF_VALID) ? authorDisplayName(authorID, knownAuthorsBySteamId) : ctx.zbsResult.notice()
                );
                Boolean decision = lookupJarDecision(approvals, ctx.hash);
                if (decision == null) {
                    // When force-dialog skips applySessionDecisions, still surface session decisions
                    // so the user sees their earlier choices pre-filled in the dialog.
                    decision = g_sessionJarDecisions.get(ctx.hash);
                }
                if (!g_forceApprovalDialog && !ctx.steamBanned && zbs.valid() && isAuthorTrusted(authorID)) {
                    // Auto-approve for this session only — trusted-author approvals are not persisted
                    // because the trust comes from the author record, not the specific JAR hash.
                    approvals.put(ctx.hash, Boolean.TRUE);
                    if (jModInfo.javaPreload() && PreloadMods.hasManifestPreload(ctx.jarPath)) {
                        PreloadMods.add(ctx.modId, jModInfo.infPath(), ctx.jarPath);
                    }
                    continue;
                }
                if (!g_forceApprovalDialog && !ctx.steamBanned) {
                    if (decision != null) continue;
                }
                ModFlags entryFlags = new ModFlags(isDecisionStored(ctx.hash, decision) ? MF_PERSIST : MF_NONE);
                if (zbs.valid() && authorID != null && isAuthorTrusted(authorID) && !ctx.steamBanned) {
                    entryFlags = entryFlags.with(MF_TRUST_AUTHOR);
                }
                final boolean bHasManifestPreload = PreloadMods.hasManifestPreload(ctx.jarPath);
                if (jModInfo.javaPreload() && zbs.valid() && bHasManifestPreload) {
                    entryFlags = entryFlags.with(MF_PRELOAD);
                } else if (jModInfo.javaPreload() || bHasManifestPreload) {
                    Logger.warn(ctx.modId + ": javaPreload=" + jModInfo.javaPreload() + ", hasManifestPreload=" + bHasManifestPreload
                        + ", zbs.valid()=" + zbs.valid());
                }
                batchEntries.add(new JarBatchApprovalProtocol.Entry(
                    ctx.modId,
                    ctx.workshopItemId,
                    ctx.jarPath,
                    jModInfo.infPath(),
                    ctx.hash,
                    date,
                    decision,
                    entryFlags,
                    modDisplay,
                    zbs,
                    steamBan
                ));
            }
            if (!batchEntries.isEmpty()) {
                if (g_hasDoLoadingText) {
                    GameWindow.DoLoadingText("Waiting for Java mods approval…");
                }
                List<JarBatchApprovalProtocol.Entry> decided = batchEntries;
                try {
                    decided = approvalFrontend().approvePendingMods(batchEntries);
                } finally {
                    if (g_hasDoLoadingText) {
                        GameWindow.DoLoadingText("Loading Mods");
                    }
                }
                applyBatchApprovalLines(decided, approvals);
                g_forceApprovalDialog = false; // reset after one batch, can be re-triggered by holding Shift on next load
            }
        }

        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            ModCtx ctx = modContexts.get(i);
            boolean shouldSkip = false;
            String skipReason = "";
            
            // Skip ZombieBuddy itself - it's loaded as a Java agent, not through normal mod loading
            if (jModInfo.javaPkgName().equals(myPackageName)) {
                shouldSkip = true;
                skipReason = " (loaded as Java agent, skipping normal mod loading)" + SelfUpdater.getExclusionReasonSuffix(ctx.jarPath);
            }
            
            // Check if this mod's package name appears in a later mod
            Integer lastIndex = lastPkgNameIndex.get(jModInfo.javaPkgName());
            if (lastIndex != null && lastIndex > i) {
                shouldSkip = true;
                skipReason = " (package " + jModInfo.javaPkgName() + " is overridden by later mod)";
            }

            if (!shouldSkip && ctx.steamBanned) {
                shouldSkip = true;
                skipReason = " (Steam Workshop mod is banned" + (!Utils.isBlank(ctx.banInfo.reason()) ? ": " + ctx.banInfo.reason() : "") + ", modId=" + ctx.modId + ")";
            }

            ModFlags flags = ctx.zbsResult == ZBSVerifier.CheckResult.DISABLED ? ModFlags.EMPTY : ctx.zbsResult.flags();
            if (!shouldSkip && zbsBlocked(ctx.zbsResult)) {
                shouldSkip = true;
                skipReason = " (" + ctx.zbsResult.blockReason() + ", modId=" + ctx.modId + ")";
            }

            if (flags.hasAll(MF_VALID, MF_SIGNED) && isAuthorTrusted(ctx.zbsResult.uploaderID())) {
                flags = flags.with(MF_TRUST_AUTHOR);
            }

            // Enforce Java JAR policy for new/changed binaries.
            if (!shouldSkip && !isJarAllowedByPolicy(ctx.modId, jModInfo, approvals, ctx.hash)) {
                shouldSkip = true;
                skipReason = " (blocked by policy=" + g_jarPolicy + ", modId=" + ctx.modId + ")";
            }

            Path canonicalJarPath = null;
            if (ctx.jarPath != null) {
                try {
                    canonicalJarPath = ctx.jarPath.toRealPath();
                } catch (IOException e) {
                    Logger.error("Cannot resolve canonical path for " + ctx.jarPath + ": " + e);
                    shouldSkip = true;
                    skipReason = " (cannot resolve JAR path)";
                }
            }
            
            shouldSkipList.add(shouldSkip);
            skipReasons.add(skipReason);

            if (canonicalJarPath != null) {
                Boolean decision = null;
                if (ctx.hash != null) {
                    decision = approvals.get(ctx.hash);
                    if (isDecisionStored(ctx.hash, decision)) {
                        flags = flags.with(MF_PERSIST);
                    }
                }
                if (!shouldSkip) {
                    flags = flags.with(MF_ACTIVE);
                }

                // merge in "sticky" flags like MF_ACTIVE or MF_PRELOAD
                JavaModLoadState prev = g_jarLoadStatus.get(canonicalJarPath);
                if (prev != null) {
                    flags = flags.merge(prev.flags.getSticky());
                }

                g_jarLoadStatus.put(canonicalJarPath, new JavaModLoadState(
                    ctx.modId,
                    canonicalJarPath,
                    flags,
                    shouldSkip ? skipReason.trim() : "loaded",
                    ctx.hash,
                    decision
                ));
            }
        }

        // Save if anything changed
        if (g_modApprovalsDirty
            && (!storedEntriesBefore.equals(g_storedEntries)
                || g_storedEntries.size() != storedEntriesBefore.size())) {
            ModApprovalsStore.FileData dataToSave = new ModApprovalsStore.FileData();
            dataToSave.mods = new ArrayList<>(g_storedEntries);
            ModApprovalsStore.save(dataToSave);
        }
        if (g_configDirty) {
            Config.save(g_config);
            g_configDirty = false;
        }

        if (!shouldSkipList.isEmpty()) {
            // Print excluded mods first
            for (int i = 0; i < jModInfos.size(); i++) {
                if (shouldSkipList.get(i)) {
                    JavaModInfo jModInfo = jModInfos.get(i);
                    String reason = i < skipReasons.size() ? skipReasons.get(i) : "";
                    Logger.info("Excluded: " + jModInfo.infPath().toAbsolutePath() + reason);
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
                loadJar(jModInfos.get(i).jarPath(), jModInfos.get(i).javaPkgName(), modContexts.get(i).hash, Phase.MAIN);
            }
        }
        local.zbselective.RuntimeState.finish();
    }

    private static void checkShiftKey() {
        try {
            long window = Display.getWindow();
            if (window == 0) {
                return;
            }
            if(GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS) {
                g_forceApprovalDialog = true;
            }
        } catch (Throwable t) {
        }
    }

    static {
        Callbacks.onDisplayCreate.register(Loader::checkShiftKey);
    }

    private static int _maxPkgLen = 0;
    static void printModList(ArrayList<JavaModInfo> jModInfos) {
        for (JavaModInfo jModInfo : jModInfos) {
            int len = jModInfo.javaPkgName().toString().length();
            if (len > _maxPkgLen) {
                _maxPkgLen = len;
            }
        }

        String formatString = "    %-" + _maxPkgLen + "s %s";
        for (JavaModInfo jModInfo : jModInfos) {
            Logger.info(String.format(formatString, jModInfo.javaPkgName(), jModInfo.jarPath().toAbsolutePath()));
        }
    }

    // called by Agent and Loader
    static boolean loadJar(Path jarPath, String packageName, String approvedHash, Phase phase) {
        if (!Files.isRegularFile(jarPath)) {
            Logger.error("JAR not found: " + jarPath);
            return false;
        }
        if (Utils.isBlank(packageName)) {
            Logger.error("Invalid package name: '" + packageName + "' for JAR " + jarPath);
            return false;
        }
        if (!local.zbselective.RuntimeState.allowJar(jarPath, packageName, approvedHash, phase.name())) {
            return false;
        }
        if (!addJarToClasspath(jarPath, packageName, approvedHash)) {
            return false;
        }
        return ApplyPatchesFromPackage(packageName, null, phase);
    }

    private static final Set<String> _known_mains = new HashSet<>();
    private static final HashMap<String, EnumSet<Phase>> _known_package_phases = new HashMap<>();

    // called by Agent and Loader
    static boolean ApplyPatchesFromPackage(String packageName, ClassLoader modLoader, Phase phase) {
        EnumSet<Phase> phases =
            _known_package_phases.computeIfAbsent(packageName, k -> EnumSet.noneOf(Phase.class));

        if (!phases.add(phase)) {
            final String pkgStr = _maxPkgLen > 0 ? String.format("%-" + _maxPkgLen + "s", packageName) : packageName;
            Logger.debug("package " + pkgStr + " phase " + phase + " already applied, skipping.");
            return false;
        }

        // Load and invoke optional [Pre]Main class
        String mainClassName = packageName + "." + (phase == Phase.PREMAIN ? "PreMain" : "Main");
        if (_known_mains.contains(mainClassName)) {
            Logger.debug("Java " + phase + " class " + mainClassName + " already loaded, skipping.");
        } else {
            _known_mains.add(mainClassName);

            Class<?> cls = Accessor.findClass(mainClassName);
            Logger.info("trying to load " + mainClassName + ": " + cls);
            if (cls != null) {
                try_call_main(cls, phase);
            }
        }

        // only apply patches on the first phase that loads this package
        if (phases.size() == 1) {
            PatchEngine.applyPatches(packageName, modLoader);
        }
        return true;
    }

    static void try_call_main(Class<?> cls, Phase phase) {
        final String methodName = phase == Phase.PREMAIN ? "premain" : "main";

        Method method = null;
        try {
            method = cls.getMethod(methodName, String[].class);
        } catch (java.lang.NoSuchMethodException e) {
            return;
        } catch (Throwable t) {
            Logger.error(cls + ": error getting " + methodName + "(): " + t);
            return;
        }

        // method cannot be null here if getMethod() succeeded
        try {
            String[] args = {}; // no arguments for now
            method.invoke(null, (Object) args);
            Logger.info(cls + ": method " + methodName + "() invoked successfully");
        } catch (Throwable t) {
            Logger.error(cls + ": error invoking " + methodName + "(): " + t);
        }
    }

    private static boolean validatePackageInJar(Path jarPath, String packageName) {
        if (jarPath == null || Utils.isBlank(packageName)) {
            return false;
        }
        try {
            String packagePath = packageName.replace('.', '/');
            try (JarFile jf = new JarFile(jarPath.toFile())) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entryName.startsWith(packagePath + "/") || 
                        entryName.equals(packagePath) || 
                        entryName.equals(packagePath + "/")) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Logger.error("Error validating package in JAR " + jarPath + ": " + e);
            return false;
        }
    }

    private static String g_prev_modSetID = null, g_curr_modSetID = null;
    private static HashSet<String> g_prev_modSet = null, g_curr_modSet = null;

    public static void onEnterLoadMods(String activeMods) {
        g_curr_modSetID = activeMods;
    }

    public static void onExitLoadMods(String activeMods) {
        g_prev_modSetID = g_curr_modSetID;
        g_prev_modSet = g_curr_modSet;
        g_curr_modSetID = null;
        g_curr_modSet = null;
    }

    public static void setAutoFixModOrder(boolean value) {
        Config newConfig = g_config.withAutoFixModOrder(value);
        if (newConfig.equals(g_config)) {
            return;
        }
        g_config = newConfig;
        Config.save(g_config);
        g_configDirty = false;
    }

    public static boolean fixApprovalDialogCursor() {
        return g_config.fix_approval_dialog_cursor();
    }

    public static void setFixApprovalDialogCursor(boolean value) {
        Config newConfig = g_config.withFixApprovalDialogCursor(value);
        if (newConfig.equals(g_config)) {
            return;
        }
        g_config = newConfig;
        Config.save(g_config);
        g_configDirty = false;
    }
}
