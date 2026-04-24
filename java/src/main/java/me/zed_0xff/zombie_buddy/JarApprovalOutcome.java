package me.zed_0xff.zombie_buddy;

/**
 * Result of a per-mod Java JAR approval prompt (allow/deny × session/persist).
 */
public enum JarApprovalOutcome {
    ALLOW_PERSIST,
    ALLOW_SESSION,
    DENY_PERSIST,
    DENY_SESSION;

    /** Token written to batch protocol / {@link Loader#applyBatchApprovalLines}. */
    public String toBatchToken() {
        return switch (this) {
            case ALLOW_PERSIST -> JarBatchApprovalProtocol.TOK_ALLOW_PERSIST;
            case ALLOW_SESSION -> JarBatchApprovalProtocol.TOK_ALLOW_SESSION;
            case DENY_PERSIST -> JarBatchApprovalProtocol.TOK_DENY_PERSIST;
            case DENY_SESSION -> JarBatchApprovalProtocol.TOK_DENY_SESSION;
        };
    }
}
