package com.dragosfotache.gtbackend.persistence;

import java.util.UUID;

/**
 * Deterministic gates for durable-copy fence races. Every method is a no-op
 * by default; test doubles may briefly block to arrange deletion acceptance
 * against a held read-side fence. The account UUID is an ephemeral
 * discriminator only. Implementations must not perform product writes.
 * Callbacks run immediately before and after the existing bound FOR SHARE
 * liveness fence on new-copy paths only, inside the caller's transaction.
 */
public interface DurableCopyHooks {
    default void beforeCursorFence(UUID accountId) {
    }

    default void afterCursorFence(UUID accountId) {
    }

    default void beforeSnapshotFence(UUID accountId) {
    }

    default void afterSnapshotFence(UUID accountId) {
    }
}
