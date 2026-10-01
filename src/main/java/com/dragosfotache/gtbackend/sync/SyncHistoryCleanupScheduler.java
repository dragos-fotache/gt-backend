package com.dragosfotache.gtbackend.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the bounded sync-history cleanup and leaves failed accounts for later ticks. */
@Component
@ConditionalOnProperty(prefix = "sync.history.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SyncHistoryCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(SyncHistoryCleanupScheduler.class);

    private final SyncHistoryPruningService cleanup;

    public SyncHistoryCleanupScheduler(SyncHistoryPruningService cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelayString = "${sync.history.cleanup.fixed-delay-ms:60000}",
            initialDelayString = "${sync.history.cleanup.initial-delay-ms:60000}")
    public void runCleanup() {
        try {
            cleanup.pruneExpiredHistory();
        } catch (RuntimeException failure) {
            // Do not attach exception details: database errors can contain SQL or row identifiers.
            log.warn("Sync-history cleanup invocation failed; uncommitted account work was rolled back.");
        }
    }
}
