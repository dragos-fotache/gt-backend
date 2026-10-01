package com.dragosfotache.gtbackend.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs bounded snapshot payload cleanup and reports only sanitized failures. */
@Component
@ConditionalOnProperty(prefix = "sync.snapshot.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SyncSnapshotCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(SyncSnapshotCleanupScheduler.class);
    private final SyncSnapshotCleanupService cleanup;

    public SyncSnapshotCleanupScheduler(SyncSnapshotCleanupService cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelayString = "${sync.snapshot.cleanup.fixed-delay-ms:60000}",
            initialDelayString = "${sync.snapshot.cleanup.initial-delay-ms:60000}")
    public void runCleanup() {
        try {
            cleanup.deleteExpiredSnapshots();
        } catch (RuntimeException failure) {
            // Database diagnostics can contain account identifiers or SQL; keep scheduler logs fixed.
            log.warn("Snapshot cleanup invocation failed; uncommitted snapshot work was rolled back.");
        }
    }
}
