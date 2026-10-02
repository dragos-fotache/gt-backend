package com.dragosfotache.gtbackend.account;

/**
 * Small deterministic gates for deletion acceptance tests. All methods are
 * no-ops by default. Test doubles may briefly block to arrange concurrent
 * writers or faults. Implementations must not perform product writes.
 */
public interface AccountDeletionHooks {
    default void afterScopedKeyLock() {
    }

    /**
     * Pre-admission gate invoked after blocking ownership, digest and
     * session-inventory steps and before the single final service-time
     * freshness sample. It may block briefly to arrange concurrent writers
     * or faults. The name is historical; placement is before the final
     * freshness decision, so a test advancing time here is observed by the
     * subsequent check rather than bypassing it.
     */
    default void afterFinalChecks() {
    }

    default void beforeCommit() {
    }
}
