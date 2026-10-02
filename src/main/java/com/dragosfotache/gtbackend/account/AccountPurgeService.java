package com.dragosfotache.gtbackend.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deadline-aware physical purge for disabled accounts. Runs the section 4
 * phase order with one transaction per phase, persists progress on the intent
 * row, and marks completion only after the same-transaction completeness
 * check passes. The recorded {@code purge_by} deadline is never moved.
 */
@Service
public class AccountPurgeService {
    private static final Logger log = LoggerFactory.getLogger(AccountPurgeService.class);

    private final AccountPurgeRepository repository;
    private final Clock clock;
    private final TransactionTemplate sweepClaim;
    private final TransactionTemplate phase;
    private final int rowBudget;
    private final int accountBudget;

    public AccountPurgeService(AccountPurgeRepository repository, Clock clock,
                               PlatformTransactionManager transactionManager,
                               @Value("${account.deletion.purge.row-budget:500}") int rowBudget,
                               @Value("${account.deletion.purge.account-budget:20}") int accountBudget) {
        if (rowBudget < 1) throw new IllegalStateException("Purge row budget must be positive");
        if (accountBudget < 1) throw new IllegalStateException("Purge account budget must be positive");
        this.repository = repository;
        this.clock = clock;
        this.sweepClaim = new TransactionTemplate(transactionManager);
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.phase = requiresNew;
        this.rowBudget = rowBudget;
        this.accountBudget = accountBudget;
    }

    public enum AccountOutcome { COMPLETED, PROGRESS, SKIPPED }

    public record AccountResult(UUID accountId, AccountOutcome outcome, long rowsDeleted,
                                boolean breached) {
    }

    public record SweepResult(int claimed, int completed, int progressed, int skipped,
                              int breached, int failed) {
    }

    public static class PurgeException extends RuntimeException {
        public final int status;
        public final String code;

        public PurgeException(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
    }

    /**
     * No-arg scheduled purge entry: runs one bounded sweep over due intents
     * with the configured account budget. The scheduler and the test seam
     * exercise this same entry.
     */
    public SweepResult purgeDue() {
        return purgeNextBatch(accountBudget);
    }

    /**
     * Claims up to {@code accountBudget} intents ordered by {@code purge_by}
     * and runs a bounded purge pass for each. Per-account faults are recorded
     * on the intent row without aborting the sweep.
     */
    public SweepResult purgeNextBatch(int accountBudget) {
        if (accountBudget < 1) throw new PurgeException(500, "internal_error");
        List<UUID> claimed = sweepClaim.execute(tx -> repository.claimDueIntents(accountBudget));
        if (claimed == null) throw new PurgeException(500, "internal_error");
        int completed = 0;
        int progressed = 0;
        int skipped = 0;
        int breached = 0;
        int failed = 0;
        for (UUID id : claimed) {
            try {
                AccountResult result = purgeAccount(id);
                switch (result.outcome()) {
                    case COMPLETED -> completed++;
                    case PROGRESS -> progressed++;
                    case SKIPPED -> skipped++;
                }
                if (result.breached()) breached++;
            } catch (PurgeException failure) {
                failed++;
            } catch (RuntimeException unexpected) {
                noteFault(id, sqlFault(unexpected));
                failed++;
            }
        }
        return new SweepResult(claimed.size(), completed, progressed, skipped, breached, failed);
    }

    /**
     * Runs one bounded pass over every phase for a single account. Committed
     * phases stay committed; the failed phase rolls back and the intent
     * returns to {@code pending} with {@code attempts + 1}.
     */
    public AccountResult purgeAccount(UUID oldAccountId) {
        AccountPurgeRepository.IntentRow current = phase.execute(tx -> repository.lockIntent(oldAccountId));
        if (current == null) throw new PurgeException(500, "internal_error");
        if ("complete".equals(current.state())) {
            return new AccountResult(oldAccountId, AccountOutcome.COMPLETED, 0L, false);
        }
        Boolean disabled = phase.execute(tx -> repository.readAccountDisabled(oldAccountId));
        if (disabled != null && !disabled) throw new PurgeException(500, "internal_error");
        if ("pending".equals(current.state())) {
            Instant begun = microsecondPrecision(clock.instant());
            phase.execute(tx -> {
                repository.lockIntent(oldAccountId);
                repository.beginProgress(oldAccountId, begun);
                return null;
            });
        }
        List<String> phases = List.of("sets", "entries", "roots", "exercises", "reservations",
                "sync_log", "snapshots", "replay", "auth", "binding", "account");
        long deleted = 0L;
        try {
            for (String step : phases) {
                deleted += runPhase(oldAccountId, step);
            }
            return finishAccount(oldAccountId, deleted);
        } catch (PurgeException failure) {
            noteFault(oldAccountId, failure);
            throw failure;
        } catch (RuntimeException unexpected) {
            PurgeException mapped = sqlFault(unexpected);
            noteFault(oldAccountId, mapped);
            throw mapped;
        }
    }

    private int runPhase(UUID oldAccountId, String current) {
        Integer removed = phase.execute(tx -> {
            if (!repository.lockAccount(oldAccountId)) return 0;
            AccountPurgeRepository.IntentRow intent = repository.lockIntent(oldAccountId);
            if (intent == null) throw new IllegalStateException("Purge intent is missing");
            if ("complete".equals(intent.state())) throw new IllegalStateException("Account purge is complete");
            int count = switch (current) {
                case "sets" -> repository.deleteWorkoutSets(oldAccountId, rowBudget)
                        + repository.deleteRoutineSets(oldAccountId, rowBudget);
                case "entries" -> repository.deleteWorkoutExercises(oldAccountId, rowBudget)
                        + repository.deleteRoutineExercises(oldAccountId, rowBudget);
                case "roots" -> repository.deleteWorkoutRoots(oldAccountId, rowBudget)
                        + repository.deleteRoutineRoots(oldAccountId, rowBudget);
                case "exercises" -> repository.deleteExerciseMuscleGroups(oldAccountId, rowBudget)
                        + repository.deleteExerciseEquipment(oldAccountId, rowBudget)
                        + repository.deleteOwnedExercises(oldAccountId, rowBudget);
                case "reservations" -> repository.deleteChildReservations(oldAccountId, rowBudget)
                        + repository.deleteRootReservations(oldAccountId, rowBudget);
                case "sync_log" -> repository.deleteSyncChanges(oldAccountId, rowBudget)
                        + repository.deleteSyncBatches(oldAccountId, rowBudget)
                        + repository.deleteAccountSyncState(oldAccountId);
                case "snapshots" -> repository.deleteSnapshotRecords(oldAccountId, rowBudget)
                        + repository.deleteSnapshotHeaders(oldAccountId, rowBudget)
                        + repository.deleteCursorTokens(oldAccountId, rowBudget);
                case "replay" -> repository.deleteProcessedMutations(oldAccountId, rowBudget)
                        + repository.deleteResourceMutations(oldAccountId, rowBudget);
                case "auth" -> repository.deleteAuthIdempotency(oldAccountId, rowBudget)
                        + repository.deleteAuthCredentials(oldAccountId, rowBudget)
                        + repository.deleteAuthSessions(oldAccountId, rowBudget);
                case "binding" -> repository.deleteBindings(oldAccountId);
                case "account" -> repository.deleteAccountRow(oldAccountId);
                default -> throw new IllegalStateException("Unknown purge phase");
            };
            repository.markPhase(oldAccountId, current, microsecondPrecision(clock.instant()));
            return count;
        });
        if (removed == null) throw new IllegalStateException("Purge phase returned no result");
        return removed;
    }

    private AccountResult finishAccount(UUID oldAccountId, long deleted) {
        return phase.execute(tx -> {
            repository.lockAccount(oldAccountId);
            AccountPurgeRepository.IntentRow intent = repository.lockIntent(oldAccountId);
            if (intent == null) throw new IllegalStateException("Purge intent is missing");
            if (!repository.ledgerRowPresent(oldAccountId))
                throw new IllegalStateException("Deletion ledger anchor is missing");
            Map<String, Long> remaining = repository.verifyCounts(oldAccountId);
            List<String> nonzero = new ArrayList<>();
            for (Map.Entry<String, Long> entry : remaining.entrySet()) {
                if (entry.getValue() != 0L) nonzero.add(entry.getKey());
            }
            Instant now = microsecondPrecision(clock.instant());
            if (!nonzero.isEmpty()) {
                repository.markPhase(oldAccountId, "verify_pending", now);
                return new AccountResult(oldAccountId, AccountOutcome.PROGRESS, deleted, false);
            }
            repository.markComplete(oldAccountId, now);
            boolean breached = now.isAfter(intent.purgeBy());
            if (breached) {
                // Fixed sanitized message: no account identifier, deadline, SQL or payload.
                log.warn("Account purge finished after its recorded deadline.");
            }
            return new AccountResult(oldAccountId, AccountOutcome.COMPLETED, deleted, breached);
        });
    }

    private void noteFault(UUID oldAccountId, PurgeException failure) {
        try {
            phase.execute(tx -> {
                AccountPurgeRepository.IntentRow intent = repository.lockIntent(oldAccountId);
                if (intent != null && !"complete".equals(intent.state())) {
                    repository.noteFault(oldAccountId, failure.code,
                            microsecondPrecision(clock.instant()));
                }
                return null;
            });
        } catch (RuntimeException nested) {
            // The original sanitized error is already being propagated.
            log.warn("Account purge fault bookkeeping failed; the phase transaction was rolled back.");
        }
    }

    private static Instant microsecondPrecision(Instant value) {
        return Instant.ofEpochSecond(value.getEpochSecond(), (value.getNano() / 1000) * 1000L);
    }

    private static PurgeException sqlFault(RuntimeException failure) {
        boolean transientFailure = LifecycleGoogle.isRetryablePersistenceFault(failure);
        return new PurgeException(transientFailure ? 503 : 500,
                transientFailure ? "temporarily_unavailable" : "internal_error");
    }
}
