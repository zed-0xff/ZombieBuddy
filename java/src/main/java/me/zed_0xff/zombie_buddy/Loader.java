package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;

import java.io.File;
import java.lang.ClassLoader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.jar.JarFile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import zombie.GameWindow;
import zombie.core.znet.SteamUtils;
import zombie.gameStates.ChooseGameInfo;

import me.zed_0xff.zombie_buddy.ModApprovalsStore.AuthorEntry;

public class Loader {
    public static Instrumentation g_instrumentation;
    public static int g_verbosity = 0;

    /**
     * Max time to wait for the batch Swing approval subprocess (seconds).
     * {@code 0} = no limit ({@link Process#waitFor()} until exit).
     */
    public static int g_batchApprovalTimeoutSeconds = 0;

    /**
     * When {@code true} (default), a missing {@code .zbs} next to the JAR is allowed as &quot;unsigned&quot;
     * (unless {@link #g_jarPolicy} is {@code allow-all}, which skips ZBS checks entirely).
     * When {@code false}, missing {@code .zbs} is treated like a failed signature for prompt UI and load gating.
     * Invalid signatures (present {@code .zbs} but verification fails) are always blocked when ZBS is enforced.
     */
    public static boolean g_allowUnsignedMods = true;

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

    /** ZBS verification applies for every policy except {@code allow-all}. */
    private static boolean zbsSignatureChecksEnabled() {
        return SteamUtils.isSteamModeEnabled() && !POLICY_ALLOW_ALL.equals(g_jarPolicy);
    }

    static Set<String> g_known_classes = new HashSet<>();
    static Set<File> g_known_jars = new HashSet<>();

    // JAR approval decisions keyed by sha256 hash.
    static final String DECISION_YES = "yes";
    static final String DECISION_NO  = "no";

    // Persisted entries loaded from disk - the source of truth for saving
    private static List<ModApprovalsStore.ModEntry> g_storedEntries = new ArrayList<>();
    // Session-only decisions (not persisted)
    private static final JarDecisionTable g_sessionJarDecisions = new JarDecisionTable();
    // Author trust entries
    private static final Map<SteamID64, AuthorEntry> g_authors = new HashMap<>();

    private static final Object g_approvalFrontendLock = new Object();
    private static volatile ModApprovalFrontend g_approvalFrontend;
    /** Set in {@link #configureApprovalFrontend}; resolved lazily — do not touch LWJGL during agent {@code premain}. */
    private static String g_approvalFrontendConfig = ModApprovalFrontends.ARG_AUTO;

    /** Called from {@link Agent#premain} with {@code approval_frontend=...} (default {@code auto}). */
    public static void configureApprovalFrontend(String value) {
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

    static void doLoadingWaitModApproval() {
        GameWindow.DoLoadingText(LOADING_WAIT_JAVA_MOD_APPROVAL);
    }

    static void doLoadingModsDefault() {
        GameWindow.DoLoadingText(LOADING_MODS);
    }

    private static String lookupJarDecision(JarDecisionTable disk, String sha256) {
        if (sha256 == null) return null;
        String d = disk.get(sha256);
        if (d != null) return d;
        return g_sessionJarDecisions.get(sha256);
    }

    /**
     * For batch UI: if this mod id has allow/deny for <b>other</b> JAR hashes, suggest the same
     * (any other {@code no} wins over {@code yes}; empty = default No in the dialog).
     */
    private static String priorHintForBatchRow(List<ModApprovalsStore.ModEntry> storedEntries, String modId, String hash) {
        if (modId == null || modId.isEmpty()) return "";
        boolean anyOtherNo = false;
        boolean anyOtherYes = false;
        for (ModApprovalsStore.ModEntry e : storedEntries) {
            String wsIdStr = e.workshopId != null ? Long.toString(e.workshopId.value()) : null;
            if (!modId.equals(e.id) && !modId.equals(wsIdStr)) continue;
            if (hash.equals(e.jarHash)) continue;
            if (e.decision) anyOtherYes = true;
            else anyOtherNo = true;
        }
        if (anyOtherNo) return DECISION_NO;
        if (anyOtherYes) return DECISION_YES;
        return "";
    }

    /**
     * Per-modId load/decision snapshot captured at loadJavaMods() time so Lua
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

    static ArrayList<String> getJavaMods() {
        return new ArrayList<>(g_jarLoadStatus.keySet());
    }

    /** Shown on the loading screen while batch or native approval UI is blocking. */
    private static final String LOADING_WAIT_JAVA_MOD_APPROVAL = "Waiting for Java mods approval…";
    /** Restores the same label the game uses while loading mods (see {@link GameWindow#DoLoadingText}). */
    private static final String LOADING_MODS = "Loading Mods";

    private static JarDecisionTable buildJarDecisionTable(List<ModApprovalsStore.ModEntry> mods) {
        JarDecisionTable table = new JarDecisionTable();
        for (ModApprovalsStore.ModEntry entry : mods) {
            if (entry.jarHash != null) {
                table.put(entry.jarHash, entry.decision ? DECISION_YES : DECISION_NO);
            }
        }
        return table;
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
                if (modId != null && !modId.isEmpty()) e.id = modId;
                if (workshopId != null) e.workshopId = workshopId;
                if (authorId != null) e.authorId = authorId;
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
    }

    private static void mergeAuthorKeysFromVerification(
        Map<SteamID64, AuthorEntry> authors,
        ZBSVerifier.Verification zbs
    ) {
        if (authors == null || zbs == null || zbs.sid == null || zbs.profileKeys == null || zbs.profileKeys.isEmpty()) {
            return;
        }
        authors.compute(zbs.sid, (k, existing) -> {
            boolean trust = existing != null && existing.trust;
            String name = existing != null ? existing.name : null;
            Set<String> keys = new LinkedHashSet<>();
            if (existing != null) {
                keys.addAll(existing.keys);
            }
            keys.addAll(zbs.profileKeys);
            return new AuthorEntry(k, trust, keys, name);
        });
    }

    private static boolean isJarAllowedByPolicy(String modId, JavaModInfo jModInfo, JarDecisionTable disk, String hash) {
        File jarFile = jModInfo != null ? jModInfo.getJarFileAsFile() : null;
        if (hash == null) return false;

        String policy = g_jarPolicy;
        String decision = lookupJarDecision(disk, hash);
        if (DECISION_YES.equals(decision)) return true;
        if (DECISION_NO.equals(decision)) {
            Logger.warn("Blocking Java mod by stored denial: " + modId + " (" + jarFile + ")");
            return false;
        }

        if (POLICY_ALLOW_ALL.equals(policy)) {
            disk.put(hash, DECISION_YES);
            storeDecision(hash, true, modId, jModInfo != null ? jModInfo.getWorkshopItemID() : null, null);
            return true;
        }
        if (POLICY_DENY_NEW.equals(policy)) {
            Logger.warn("Blocking Java mod by policy=deny-new: " + modId + " (" + jarFile + ")");
            return false;
        }

        // policy=prompt: decisions come from approvePendingMods() in loadJavaMods(); should not reach here.
        Logger.warn("No approval decision for " + modId + " (hash " + hash + ") — denying session-only.");
        g_sessionJarDecisions.put(hash, DECISION_NO);
        return false;
    }

    /**
     * Path to the ZombieBuddy JAR on disk (for spawning a non-headless child JVM). Returns null if not running from a plain JAR file.
     */
    public static String getZombieBuddyJarPathForSubprocess() {
        return Utils.getZombieBuddyJarPath();
    }

    public static void applyBatchApprovalLines(List<JarBatchApprovalProtocol.OutLine> lines, JarDecisionTable disk) {
        applyBatchApprovalLines(lines, disk, g_authors);
    }

    public static void applyBatchApprovalLines(
        List<JarBatchApprovalProtocol.OutLine> lines,
        JarDecisionTable disk,
        Map<SteamID64, AuthorEntry> authors
    ) {
        if (lines == null) return;
        for (JarBatchApprovalProtocol.OutLine ol : lines) {
            String tok = ol.token;
            if (!JarBatchApprovalProtocol.isValidToken(tok)) continue;
            String hash = ol.sha256;
            boolean allow = JarBatchApprovalProtocol.TOK_ALLOW_PERSIST.equals(tok)
                         || JarBatchApprovalProtocol.TOK_ALLOW_SESSION.equals(tok);
            boolean persist = JarBatchApprovalProtocol.TOK_ALLOW_PERSIST.equals(tok)
                           || JarBatchApprovalProtocol.TOK_DENY_PERSIST.equals(tok);
            if (persist) {
                disk.put(hash, allow ? DECISION_YES : DECISION_NO);
                storeDecision(hash, allow, ol.modId, ol.workshopItemId, ol.trustedAuthorSteamId);
            } else {
                g_sessionJarDecisions.put(hash, allow ? DECISION_YES : DECISION_NO);
            }
            if (authors != null && ol.trustedAuthorSteamId != null) {
                authors.compute(ol.trustedAuthorSteamId, (k, v) -> {
                    if (v == null) {
                        return new AuthorEntry(k, true, new LinkedHashSet<>(), null);
                    }
                    return new AuthorEntry(k, true, new LinkedHashSet<>(v.keys), v.name);
                });
            }
        }
    }

    private static boolean isAuthorTrusted(SteamID64 sid) {
        if (sid == null) {
            return false;
        }
        AuthorEntry ae = g_authors.get(sid);
        return ae != null && ae.trust;
    }

    public static void loadJavaMods(ArrayList<String> mods) {
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
        ModApprovalsStore.FileData fileData = ModApprovalsStore.load();
        // Store entries for persistence and build lookup table
        g_storedEntries = new ArrayList<>(fileData.mods);
        int storedEntriesCountBefore = g_storedEntries.size();
        JarDecisionTable approvals = buildJarDecisionTable(g_storedEntries);
        JarDecisionTable approvalsBefore = approvals.copy();
        Map<SteamID64, AuthorEntry> authorsBefore = new HashMap<>();
        g_authors.clear();
        for (AuthorEntry ae : fileData.authors) {
            if (ae.id != null) {
                authorsBefore.put(ae.id, new AuthorEntry(ae.id, ae.trust, new LinkedHashSet<>(ae.keys), ae.name));
                g_authors.put(ae.id, new AuthorEntry(ae.id, ae.trust, new LinkedHashSet<>(ae.keys), ae.name));
            }
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
        boolean steamModeEnabled = SteamUtils.isSteamModeEnabled();
        Set<WorkshopItemID> workshopIdsToCheck = new HashSet<>();
        if (steamModeEnabled) {
            for (JavaModInfo jModInfo : jModInfos) {
                WorkshopItemID workshopItemId = jModInfo.getWorkshopItemID();
                if (workshopItemId != null) {
                    workshopIdsToCheck.add(workshopItemId);
                }
            }
        }
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById = steamModeEnabled
            ? SteamWorkshop.fetchItemDetails(workshopIdsToCheck)
            : new HashMap<>();

        // Pre-compute per-mod context to avoid duplicate work in prompt and load loops
        record ModCtx(String modId, File jarFile, String hash, WorkshopItemID workshopItemId,
                      SteamWorkshop.ItemDetails workshopDetails, SteamWorkshop.BanInfo banInfo, boolean steamBanned) {}
        List<ModCtx> modContexts = new ArrayList<>();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            String modId = i < jModIds.size() ? jModIds.get(i) : jModInfo.javaPkgName();
            File jarFile = jModInfo.getJarFileAsFile();
            String hash = Utils.sha256Hex(jarFile);
            WorkshopItemID workshopItemId = jModInfo.getWorkshopItemID();
            SteamWorkshop.ItemDetails workshopDetails = steamModeEnabled && workshopItemId != null
                ? workshopDetailsById.get(workshopItemId) : null;
            SteamWorkshop.BanInfo banInfo = workshopItemId == null
                ? new SteamWorkshop.BanInfo(SteamWorkshop.BAN_STATUS_UNKNOWN, "Workshop id not found in mod path.")
                : (workshopDetails != null ? workshopDetails.ban : null);
            boolean steamBanned = steamModeEnabled && banInfo != null && SteamWorkshop.BAN_STATUS_YES.equals(banInfo.status);
            modContexts.add(new ModCtx(modId, jarFile, hash, workshopItemId, workshopDetails, banInfo, steamBanned));
        }

        if (POLICY_PROMPT.equals(g_jarPolicy)) {
            ArrayList<JarBatchApprovalProtocol.Entry> batchEntries = new ArrayList<>();
            for (int i = 0; i < jModInfos.size(); i++) {
                if (structuralOnlySkip.get(i)) continue;
                ModCtx ctx = modContexts.get(i);
                if (ctx.hash == null) continue;
                JavaModInfo jModInfo = jModInfos.get(i);
                String workshopIdStr = SteamWorkshop.idToString(ctx.workshopItemId);
                String modified = (ctx.jarFile != null && ctx.jarFile.exists())
                    ? java.time.Instant.ofEpochMilli(ctx.jarFile.lastModified())
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    : "<unknown>";
                String modDisplay = jModInfo.displayName();
                if (modDisplay == null || modDisplay.trim().isEmpty()) {
                    modDisplay = ctx.modId != null ? ctx.modId : "";
                }
                File zbsFile = ctx.jarFile != null ? new File(ctx.jarFile.getAbsolutePath() + ".zbs") : null;
                WorkshopItemID workshopItemId = steamModeEnabled ? ctx.workshopItemId : null;
                String steamBanStatus = ctx.banInfo != null ? ctx.banInfo.status : SteamWorkshop.BAN_STATUS_NO;
                String steamBanReason = ctx.banInfo != null ? ctx.banInfo.reason : "";
                ZBSCheck.Result zbsResult = steamModeEnabled
                    ? ZBSCheck.check(ctx.jarFile, ctx.hash, ctx.workshopItemId, 
                        steamModeEnabled, g_allowUnsignedMods, workshopDetailsById)
                    : ZBSCheck.Result.DISABLED;
                if (zbsResult.verification() != null) {
                    mergeAuthorKeysFromVerification(g_authors, zbsResult.verification());
                }
                String zbsValid = zbsResult.valid();
                SteamID64 zbsSID = zbsResult.sid();
                String zbsNotice = zbsResult.notice();
                if (!ctx.steamBanned && "yes".equals(zbsValid) && isAuthorTrusted(zbsSID)) {
                    // Auto-approve signed mods from trusted authors
                    approvals.put(ctx.hash, DECISION_YES);
                    storeDecision(ctx.hash, true, ctx.modId, ctx.workshopItemId, zbsSID);
                    continue;
                }
                if (!ctx.steamBanned) {
                    String decision = lookupJarDecision(approvals, ctx.hash);
                    if (DECISION_YES.equals(decision) || DECISION_NO.equals(decision)) continue;
                }
                batchEntries.add(new JarBatchApprovalProtocol.Entry(
                    ctx.modId,  // modKey is now just modId
                    ctx.modId,
                    ctx.workshopItemId,
                    ctx.jarFile.getAbsolutePath(),
                    ctx.hash,
                    modified,
                    priorHintForBatchRow(g_storedEntries, ctx.modId, ctx.hash),
                    modDisplay,
                    zbsValid,
                    zbsSID,
                    zbsNotice,
                    steamBanStatus,
                    steamBanReason
                ));
            }
            if (!batchEntries.isEmpty()) {
                approvalFrontend().approvePendingMods(batchEntries, approvals);
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
                skipReason = " (loaded as Java agent, skipping normal mod loading)"
                    + SelfUpdater.getExclusionReasonSuffix(ctx.jarFile);
            }
            
            // Check if this mod's package name appears in a later mod
            Integer lastIndex = lastPkgNameIndex.get(jModInfo.javaPkgName());
            if (lastIndex != null && lastIndex > i) {
                shouldSkip = true;
                skipReason = " (package " + jModInfo.javaPkgName() + " is overridden by later mod)";
            }

            if (!shouldSkip && ctx.steamBanned) {
                shouldSkip = true;
                skipReason = " (Steam Workshop mod is banned"
                    + (ctx.banInfo.reason != null && !ctx.banInfo.reason.isEmpty() ? ": " + ctx.banInfo.reason : "")
                    + ", modId=" + ctx.modId + ")";
            }

            // ZBS: skipped entirely for policy=allow-all; invalid signatures always block when checks apply.
            if (!shouldSkip && zbsSignatureChecksEnabled() && ctx.jarFile != null && ctx.hash != null) {
                ZBSCheck.Result zbsResult = ZBSCheck.check(ctx.jarFile, ctx.hash, 
                    ctx.workshopItemId, steamModeEnabled, g_allowUnsignedMods, workshopDetailsById);
                if (zbsResult.verification() != null) {
                    mergeAuthorKeysFromVerification(g_authors, zbsResult.verification());
                }
                if (zbsResult.shouldBlock()) {
                    shouldSkip = true;
                    skipReason = " (" + zbsResult.blockReason() + ", modId=" + ctx.modId + ")";
                }
            }

            // Enforce Java JAR policy for new/changed binaries.
            if (!shouldSkip && !isJarAllowedByPolicy(ctx.modId, jModInfo, approvals, ctx.hash)) {
                shouldSkip = true;
                skipReason = " (blocked by policy=" + g_jarPolicy + ", modId=" + ctx.modId + ")";
            }
            
            shouldSkipList.add(shouldSkip);
            skipReasons.add(skipReason);

            // Snapshot for ZombieBuddy.getJavaModStatus(). Skipped when no JAR
            // (e.g. metadata-only mod.info entries in B42 common dirs).
            if (ctx.jarFile != null) {
                String decision = null;
                boolean persisted = false;
                if (ctx.hash != null) {
                    String v = approvals.get(ctx.hash);
                    if (v != null) {
                        decision = v;
                        persisted = true;
                    } else {
                        v = g_sessionJarDecisions.get(ctx.hash);
                        if (v != null) {
                            decision = v;
                            persisted = false;
                        }
                    }
                }
                g_jarLoadStatus.put(ctx.modId, new JavaModLoadState(
                    !shouldSkip,
                    shouldSkip ? skipReason.trim() : "loaded",
                    ctx.hash,
                    decision,
                    persisted
                ));
            }
        }

        // Save if anything changed
        if (!approvalsBefore.equals(approvals)
            || !authorsBefore.equals(g_authors)
            || g_storedEntries.size() != storedEntriesCountBefore) {
            ModApprovalsStore.FileData dataToSave = new ModApprovalsStore.FileData();
            dataToSave.mods = new ArrayList<>(g_storedEntries);
            dataToSave.authors = new ArrayList<>(g_authors.values());
            ModApprovalsStore.save(dataToSave);
        }
        
        if (!shouldSkipList.isEmpty()) {
            // Print excluded mods first
            for (int i = 0; i < jModInfos.size(); i++) {
                if (shouldSkipList.get(i)) {
                    JavaModInfo jModInfo = jModInfos.get(i);
                    String reason = i < skipReasons.size() ? skipReasons.get(i) : "";
                    Logger.info("Excluded: " + jModInfo.modDir().getAbsolutePath() + reason);
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
            if (jModInfo.modDir().getAbsolutePath().length() > longestPathLength) {
                longestPathLength = jModInfo.modDir().getAbsolutePath().length();
            }
        }

        String formatString = "    %-" + longestPathLength + "s %s";
        for (JavaModInfo jModInfo : jModInfos) {
            Logger.info(String.format(formatString, jModInfo.modDir().getAbsolutePath(), jModInfo.javaPkgName()));
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
                Logger.info("Main class " + mainClassName + " not found (optional, skipping)");
            } catch (Exception e) {
                Logger.error("failed to load Java class " + mainClassName + ": " + e);
            }
        }

        PatchEngine.applyPatches(packageName, modLoader, isPreMain);
    }

    public static List<Class<?>> CollectPatches(String packageName, ClassLoader modLoader) {
        return PatchEngine.collectPatches(packageName, modLoader);
    }

    // Patch code moved to PatchEngine.java
    public static void loadJavaMod(JavaModInfo modInfo) {
        Logger.info("------------------------------------------- loading Java mod: " + modInfo.modDir());
        
        // Load JAR file
        File jarFile = modInfo.getJarFileAsFile();
        if (jarFile == null) {
            Logger.error("Error! No JAR file specified for mod: " + modInfo.modDir());
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

    private static boolean validatePackageInJar(File jarFile, String packageName) {
        if (jarFile == null || packageName == null || packageName.isEmpty()) {
            return false;
        }
        try {
            String packagePath = packageName.replace('.', '/');
            try (JarFile jf = new JarFile(jarFile)) {
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
            Logger.error("Error validating package in JAR " + jarFile + ": " + e);
            return false;
        }
    }
}
