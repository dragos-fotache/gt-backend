package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.persistence.SyncHistoryPruningRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Callable bounded service operation for pruning expired sync history. */
@Service
public class SyncHistoryPruningService {
    public static final int DEFAULT_ACCOUNT_BUDGET = 100;
    public static final int DEFAULT_BATCHES_PER_ACCOUNT = 500;
    private static final Duration RETENTION = Duration.ofDays(90);

    private final Clock serviceClock;
    private final SyncHistoryPruningRepository repository;

    public SyncHistoryPruningService(Clock serviceClock, SyncHistoryPruningRepository repository) {
        this.serviceClock = serviceClock;
        this.repository = repository;
    }

    /**
     * Uses one invocation-wide service-time cutoff. Each account commits independently; a failed
     * account is rolled back and the exception escapes rather than returning partial success.
     */
    public CleanupProgress pruneExpiredHistory() {
        Instant cutoff = serviceClock.instant().minus(RETENTION);
        List<UUID> candidates = repository.findCandidates(cutoff, DEFAULT_ACCOUNT_BUDGET);
        int prunedAccounts = 0;
        int skippedAccounts = 0;
        int batchesPruned = 0;
        long changesPruned = 0;

        for (UUID accountId : candidates) {
            SyncHistoryPruningRepository.AccountPruneResult result =
                    repository.pruneAccount(accountId, cutoff, DEFAULT_BATCHES_PER_ACCOUNT);
            switch (result.outcome()) {
                case PRUNED -> {
                    prunedAccounts++;
                    batchesPruned += result.batchesPruned();
                    changesPruned += result.changesPruned();
                }
                case SKIPPED -> skippedAccounts++;
                case UNCHANGED -> { }
            }
        }
        return new CleanupProgress(candidates.size(), prunedAccounts, skippedAccounts, batchesPruned, changesPruned);
    }

    public record CleanupProgress(int candidatesConsidered, int accountsPruned, int accountsSkipped,
                                  int batchesPruned, long changesPruned) { }
}
