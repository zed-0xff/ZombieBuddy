package me.zed_0xff.zombie_buddy;

import java.util.List;

/**
 * UI for Java mod approvals. Implementations present {@code pending} in one batch UI if available,
 * otherwise prompt one mod at a time until every row is applied to {@code disk} / session.
 */
public interface ModApprovalFrontend {

    /**
     * Apply approval decisions for every entry in {@code pending}. Empty list is a no-op.
     */
    void approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending, JarDecisionTable disk);
}
