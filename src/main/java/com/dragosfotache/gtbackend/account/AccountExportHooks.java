package com.dragosfotache.gtbackend.account;

/**
 * Lightweight deterministic coordination gates for export tests. Every method is a no-op by
 * default; test doubles may briefly block to let a separate connection arrange writers,
 * disablement, or faults, and may throw an unchecked fault to simulate one. A throwing
 * gate fails the export through the normal sanitized path: a mapped status before the
 * first byte, a truncated stream afterwards, never a completed partial success.
 * Implementations must not perform product writes. The streaming transaction holds no
 * row locks while gates run, so a gate may use its own connection without circular waits.
 */
public interface AccountExportHooks {
    default void afterSnapshotEstablished() {
    }

    default void afterConsumptionCommitted() {
    }

    default void beforeFirstByte() {
    }

    default void beforeSegment(int elementIndex) {
    }

    /**
     * Runs before the current-view live check of each outward segment of at most
     * 8 KiB, including segments inside a single large root. The ordinal counts
     * every segment of the export from zero. May block briefly or throw an
     * unchecked deterministic fault under the same contract as the other gates.
     */
    default void beforeByteSegment(long ordinal) {
    }

    /**
     * Runs before the guarded success flush, so a final-flush fault can be
     * exercised without timing guesses.
     */
    default void beforeSuccessFlush() {
    }

    default void beforeFinalClosure() {
    }
}
