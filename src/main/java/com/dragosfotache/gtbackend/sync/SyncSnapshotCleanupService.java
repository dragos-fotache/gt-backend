package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.persistence.SyncSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Atomically removes at most one bounded batch of expired snapshot payloads and headers. */
@Service
public class SyncSnapshotCleanupService {
    public static final int DEFAULT_SNAPSHOT_BUDGET = 100;
    private final Clock clock;
    private final SyncSnapshotRepository snapshots;
    private final TransactionTemplate cleanupTransaction;

    public SyncSnapshotCleanupService(Clock clock, SyncSnapshotRepository snapshots,
                                      PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.snapshots = snapshots;
        this.cleanupTransaction = new TransactionTemplate(transactionManager);
    }

    public CleanupProgress deleteExpiredSnapshots() {
        Instant cutoff = clock.instant();
        return cleanupTransaction.execute(status -> {
            List<SyncSnapshotRepository.ExpiredSnapshot> expired =
                    snapshots.lockExpired(cutoff, DEFAULT_SNAPSHOT_BUDGET);
            for (var snapshot : expired) snapshots.delete(snapshot, cutoff);
            return new CleanupProgress(expired.size(), expired.size() == DEFAULT_SNAPSHOT_BUDGET);
        });
    }

    public record CleanupProgress(int snapshotsDeleted, boolean mayHaveMore) { }
}
